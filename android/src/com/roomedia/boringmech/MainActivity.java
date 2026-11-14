package com.roomedia.boringmech;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private BroadcastReceiver engineStopReceiver;
    private BroadcastReceiver downloadReceiver;

    // 셀프 업데이트 pending은 메모리가 아니라 SharedPreferences에 둔다 —
    // 다운로드 중 액티비티가 죽어도 다음 실행에서 상태를 복원해 이어가기 위함.
    private static final String UPDATE_PREFS = "self-update";
    private static final String KEY_DL_ID = "dl_id";
    private static final String KEY_DL_FILE = "dl_file";
    private static final String KEY_DL_SHA = "dl_sha";

    private SharedPreferences updatePrefs() {
        return getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
    }

    private long pendingDownloadId() {
        return updatePrefs().getLong(KEY_DL_ID, -1);
    }

    private void savePending(long id, String fileName, String sha256) {
        updatePrefs().edit()
                .putLong(KEY_DL_ID, id)
                .putString(KEY_DL_FILE, fileName)
                .putString(KEY_DL_SHA, sha256)
                .apply();
    }

    private void clearPending() {
        updatePrefs().edit()
                .remove(KEY_DL_ID).remove(KEY_DL_FILE).remove(KEY_DL_SHA)
                .apply();
    }

    // WebView 엔진과의 다리 — 실행 의도를 네이티브 미러에 남기고
    // 포그라운드 서비스를 엔진 상태에 맞춰 올리고 내린다.
    public class Bridge {
        @JavascriptInterface
        public void setRunIntent(final boolean on) {
            runOnUiThread(() -> {
                EngineService.setRunIntent(MainActivity.this, on);
                Intent service = new Intent(MainActivity.this, EngineService.class);
                if (on) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(service);
                    } else {
                        startService(service);
                    }
                } else {
                    stopService(service);
                }
            });
        }

        @JavascriptInterface
        public boolean getRunIntent() {
            return EngineService.getRunIntent(MainActivity.this);
        }

        // 셀프 업데이트 — 릴리즈 APK를 DownloadManager로 받고, 끝나면
        // 해시 대조를 거쳐 패키지 인스톨러를 띄운다. 실제 설치 확정은
        // 인스톨러 화면에서 사용자의 탭 1회 (자동 설치는 정책상 불가).
        @JavascriptInterface
        public void downloadAndInstall(final String url, final String fileName,
                                       final String sha256) {
            runOnUiThread(() -> startApkDownload(url, fileName, sha256));
        }

        // 주문 POST 전용 동기 전송 — CLOB /order·/orders의 실제 응답은
        // Access-Control-Allow-Origin이 https://polymarket.com 고정이라
        // WebView(오리진 null)가 응답 읽기를 차단한다. 서명·헤더·본문은
        // SDK가 만든 것을 그대로 받아 네이티브로 보내 CORS를 우회한다.
        // @JavascriptInterface는 백그라운드 스레드에서 불리므로 동기 수행이 안전하다.
        // 반환: {"status": <HTTP 코드, 네트워크 실패 시 0>, "body": <응답 원문>,
        //        "error": <전송 실패 사유, 있을 때만>}
        @JavascriptInterface
        public String postSync(final String url, final String headersJson,
                               final String bodyJson) {
            int status = 0;
            String body = "";
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                if (headersJson != null) {
                    JSONObject headers = new JSONObject(headersJson);
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        conn.setRequestProperty(k, headers.optString(k, ""));
                    }
                }
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] payload = (bodyJson == null ? "" : bodyJson)
                        .getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.close();
                status = conn.getResponseCode();
                InputStream in = status >= 400
                        ? conn.getErrorStream() : conn.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    in.close();
                    body = buf.toString("UTF-8");
                }
            } catch (Exception e) {
                status = 0;
                body = "";
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                if (conn != null) conn.disconnect();
            }
            try {
                JSONObject out = new JSONObject();
                out.put("status", status);
                out.put("body", body);
                if (error != null) out.put("error", error);
                return out.toString();
            } catch (Exception e) {
                return "{\"status\":0,\"body\":\"\",\"error\":\"bridge encode failed\"}";
            }
        }
    }

    private File downloadDest(String fileName) {
        return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
    }

    private void startApkDownload(String url, String fileName, String sha256) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long existing = pendingDownloadId();
        if (existing != -1) {
            int s = downloadStatus(dm, existing);
            if (s == DownloadManager.STATUS_PENDING
                    || s == DownloadManager.STATUS_RUNNING
                    || s == DownloadManager.STATUS_PAUSED) {
                Toast.makeText(this, "이미 업데이트를 받는 중입니다", Toast.LENGTH_SHORT).show();
                return;
            }
            // 끝난 다운로드가 남아 있으면 정리하고 새로 받는다
            clearPending();
        }
        File dest = downloadDest(fileName);
        if (dest.exists() && !dest.delete()) {
            Toast.makeText(this, "기존 업데이트 파일을 지우지 못했습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setTitle(fileName);
        req.setMimeType("application/vnd.android.package-archive");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
        long id;
        try {
            id = dm.enqueue(req);
        } catch (Exception e) {
            Toast.makeText(this, "업데이트 다운로드를 시작하지 못했습니다 — "
                    + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        savePending(id, fileName, sha256);
        Toast.makeText(this, "업데이트 다운로드 시작", Toast.LENGTH_SHORT).show();
    }

    // DownloadManager에서 상태만 읽는다. 기록이 없으면 -1.
    private int downloadStatus(DownloadManager dm, long id) {
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        int status = -1;
        if (c != null) {
            if (c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
            c.close();
        }
        return status;
    }

    private void onDownloadComplete(long id) {
        if (id != pendingDownloadId()) return;
        finishDownload(id);
    }

    // 액티비티가 죽었다 살아나도 미완 pending을 복원한다 — 성공이면 검증+설치를
    // 이어서 실행하고, 실패면 사유를 표시한 뒤 pending을 비운다.
    // 진행 중인 다운로드는 그대로 두고 다음 기회(완료 방송·다음 복원)에 맡긴다.
    private void restorePendingUpdate() {
        long id = pendingDownloadId();
        if (id == -1) return;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        int status = downloadStatus(dm, id);
        if (status == DownloadManager.STATUS_SUCCESSFUL
                || status == DownloadManager.STATUS_FAILED
                || status == -1) {
            finishDownload(id);
        }
    }

    // 종료 상태에 도달한 다운로드의 뒷단 — 검증·설치 또는 실패 고지.
    // 어느 분기로 끝나든 토스트를 남기고 pending을 비운다 (조용한 종료 금지).
    private void finishDownload(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        final String fileName = updatePrefs().getString(KEY_DL_FILE, null);
        final String expected = updatePrefs().getString(KEY_DL_SHA, null);
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        int status = -1;
        int reason = 0;
        if (c != null) {
            if (c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_FAILED) {
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                }
            }
            c.close();
        }
        clearPending();
        if (status == -1) {
            Toast.makeText(this, "업데이트 다운로드 기록을 찾지 못해 취소합니다",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (status == DownloadManager.STATUS_FAILED) {
            Toast.makeText(this, "업데이트 다운로드 실패 — " + downloadReasonText(reason),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL || fileName == null) {
            Toast.makeText(this, "업데이트 다운로드가 끝나지 않아 취소합니다",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final File file = downloadDest(fileName);
        // 해시 대조는 파일 IO라 백그라운드에서 한다
        new Thread(() -> {
            String problem = null;
            if (expected != null && expected.startsWith("sha256:")) {
                try {
                    String actual = sha256Hex(file);
                    if (!actual.equalsIgnoreCase(expected.substring("sha256:".length()))) {
                        problem = "파일 해시가 릴리즈와 다릅니다";
                    }
                } catch (Exception e) {
                    problem = "파일 해시를 읽지 못했습니다";
                }
            }
            final String issue = problem;
            runOnUiThread(() -> {
                if (issue != null) {
                    file.delete();
                    Toast.makeText(this, "업데이트 검증 실패 — " + issue + ". 설치를 중단합니다",
                            Toast.LENGTH_LONG).show();
                } else {
                    launchInstaller(dm, id);
                }
            });
        }).start();
    }

    private static String downloadReasonText(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "저장 공간이 부족합니다";
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "이어받기가 불가능합니다 (사유 코드 " + reason + ")";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "다운로드 저장소를 찾지 못했습니다";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "같은 이름의 파일이 이미 있습니다";
            case DownloadManager.ERROR_FILE_ERROR:
                return "파일 처리 오류가 발생했습니다";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "전송 중 데이터 오류가 발생했습니다";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "리다이렉트가 너무 많습니다";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "서버 응답 오류입니다";
            default:
                return "사유 코드 " + reason;
        }
    }

    private void launchInstaller(DownloadManager dm, long downloadId) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "이 앱의 설치를 허용한 뒤 업데이트를 다시 눌러 주세요",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri uri = dm.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            Toast.makeText(this, "받은 업데이트 파일을 찾지 못했습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(install);
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://")) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
        });

        // 알림의 정지 액션이 서비스로 들어오면 여기로 브로드캐스트가 온다
        engineStopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                web.evaluateJavascript("window.BM && BM.stop();", null);
            }
        };
        IntentFilter filter = new IntentFilter(EngineService.ACTION_ENGINE_STOP_REQUEST);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(engineStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(engineStopReceiver, filter);
        }

        // 업데이트 APK 다운로드 완료 알림
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onDownloadComplete(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1));
            }
        };
        IntentFilter dlFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, dlFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, dlFilter);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        web.loadUrl("file:///android_asset/index.html");

        // 이전 실행에서 끝나지 않은 업데이트 다운로드가 있으면 이어서 처리
        restorePendingUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restorePendingUpdate();
    }

    @Override
    protected void onDestroy() {
        if (engineStopReceiver != null) {
            unregisterReceiver(engineStopReceiver);
        }
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
