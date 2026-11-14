package com.roomedia.boringmech;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

public class MainActivity extends Activity {
    private WebView web;
    private BroadcastReceiver engineStopReceiver;
    private BroadcastReceiver downloadReceiver;
    private long pendingDownloadId = -1;
    private String pendingFileName;
    private String pendingSha256;

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
    }

    private File downloadDest(String fileName) {
        return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
    }

    private void startApkDownload(String url, String fileName, String sha256) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (pendingDownloadId != -1) {
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(pendingDownloadId));
            boolean active = false;
            if (c != null) {
                if (c.moveToFirst()) {
                    int s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    active = s == DownloadManager.STATUS_PENDING
                            || s == DownloadManager.STATUS_RUNNING
                            || s == DownloadManager.STATUS_PAUSED;
                }
                c.close();
            }
            if (active) {
                Toast.makeText(this, "이미 업데이트를 받는 중입니다", Toast.LENGTH_SHORT).show();
                return;
            }
            pendingDownloadId = -1;
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
        pendingFileName = fileName;
        pendingSha256 = sha256;
        pendingDownloadId = dm.enqueue(req);
        Toast.makeText(this, "업데이트 다운로드 시작", Toast.LENGTH_SHORT).show();
    }

    private void onDownloadComplete(long id) {
        if (id != pendingDownloadId) return;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        boolean success = false;
        if (c != null) {
            if (c.moveToFirst()) {
                success = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        == DownloadManager.STATUS_SUCCESSFUL;
            }
            c.close();
        }
        final String fileName = pendingFileName;
        final String expected = pendingSha256;
        pendingDownloadId = -1;
        pendingFileName = null;
        pendingSha256 = null;
        if (!success || fileName == null) {
            Toast.makeText(this, "업데이트 다운로드 실패", Toast.LENGTH_SHORT).show();
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
