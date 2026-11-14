package com.roomedia.boringmech;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

// 셀프 업데이트 기계장치 — 릴리즈 APK를 DownloadManager로 받고, 끝나면
// 해시 대조를 거쳐 패키지 인스톨러를 띄운다. 액티비티와 서비스가 함께
// 쓰므로 Context 기반으로 뽑아냈고, pending 상태는 SharedPreferences가
// 정본이라 어느 호스트가 죽었다 살아나도 이어서 처리한다.
// 완료 처리는 잠금으로 한 번만 실행된다 (양쪽 수신자가 동시에 불려도).
// 실제 설치 확정은 인스톨러 화면에서 사용자의 탭 1회 (자동 설치는 정책상 불가).
final class SelfUpdate {
    private static final String UPDATE_PREFS = "self-update";
    private static final String KEY_DL_ID = "dl_id";
    private static final String KEY_DL_FILE = "dl_file";
    private static final String KEY_DL_SHA = "dl_sha";
    private static final String CHANNEL_ID = "bm_update";
    private static final int NOTIFICATION_ID = 2;
    private static final Object FINISH_LOCK = new Object();

    private SelfUpdate() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE);
    }

    private static long pendingDownloadId(Context ctx) {
        return prefs(ctx).getLong(KEY_DL_ID, -1);
    }

    private static void savePending(Context ctx, long id, String fileName, String sha256) {
        prefs(ctx).edit()
                .putLong(KEY_DL_ID, id)
                .putString(KEY_DL_FILE, fileName)
                .putString(KEY_DL_SHA, sha256)
                .apply();
    }

    private static void clearPending(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_DL_ID).remove(KEY_DL_FILE).remove(KEY_DL_SHA)
                .apply();
    }

    private static File downloadDest(Context ctx, String fileName) {
        return new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
    }

    static void startDownload(Context ctx, String url, String fileName, String sha256) {
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        long existing = pendingDownloadId(ctx);
        if (existing != -1) {
            int s = downloadStatus(dm, existing);
            if (s == DownloadManager.STATUS_PENDING
                    || s == DownloadManager.STATUS_RUNNING
                    || s == DownloadManager.STATUS_PAUSED) {
                Toast.makeText(ctx, "이미 업데이트를 받는 중입니다", Toast.LENGTH_SHORT).show();
                return;
            }
            // 끝난 다운로드가 남아 있으면 정리하고 새로 받는다
            clearPending(ctx);
        }
        File dest = downloadDest(ctx, fileName);
        if (dest.exists() && !dest.delete()) {
            Toast.makeText(ctx, "기존 업데이트 파일을 지우지 못했습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setTitle(fileName);
        req.setMimeType("application/vnd.android.package-archive");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, fileName);
        long id;
        try {
            id = dm.enqueue(req);
        } catch (Exception e) {
            Toast.makeText(ctx, "업데이트 다운로드를 시작하지 못했습니다 — "
                    + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        savePending(ctx, id, fileName, sha256);
        Toast.makeText(ctx, "업데이트 다운로드 시작", Toast.LENGTH_SHORT).show();
    }

    // DownloadManager에서 상태만 읽는다. 기록이 없으면 -1.
    private static int downloadStatus(DownloadManager dm, long id) {
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

    static void onDownloadComplete(Context ctx, long id) {
        if (id != pendingDownloadId(ctx)) return;
        finishDownload(ctx, id);
    }

    // 호스트가 죽었다 살아나도 미완 pending을 복원한다 — 성공이면 검증+설치를
    // 이어서 실행하고, 실패면 사유를 표시한 뒤 pending을 비운다.
    // 진행 중인 다운로드는 그대로 두고 다음 기회(완료 방송·다음 복원)에 맡긴다.
    static void restorePending(Context ctx) {
        long id = pendingDownloadId(ctx);
        if (id == -1) return;
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        int status = downloadStatus(dm, id);
        if (status == DownloadManager.STATUS_SUCCESSFUL
                || status == DownloadManager.STATUS_FAILED
                || status == -1) {
            finishDownload(ctx, id);
        }
    }

    // 종료 상태에 도달한 다운로드의 뒷단 — 검증·설치 또는 실패 고지.
    // 어느 분기로 끝나든 토스트를 남기고 pending을 비운다 (조용한 종료 금지).
    private static void finishDownload(Context ctx, long id) {
        synchronized (FINISH_LOCK) {
            finishDownloadLocked(ctx, id);
        }
    }

    private static void finishDownloadLocked(Context ctx, long id) {
        if (id != pendingDownloadId(ctx)) return; // 다른 수신자가 이미 처리했다
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        final String fileName = prefs(ctx).getString(KEY_DL_FILE, null);
        final String expected = prefs(ctx).getString(KEY_DL_SHA, null);
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
        clearPending(ctx);
        if (status == -1) {
            Toast.makeText(ctx, "업데이트 다운로드 기록을 찾지 못해 취소합니다",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (status == DownloadManager.STATUS_FAILED) {
            Toast.makeText(ctx, "업데이트 다운로드 실패 — " + downloadReasonText(reason),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL || fileName == null) {
            Toast.makeText(ctx, "업데이트 다운로드가 끝나지 않아 취소합니다",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final File file = downloadDest(ctx, fileName);
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
            new Handler(Looper.getMainLooper()).post(() -> {
                if (issue != null) {
                    file.delete();
                    Toast.makeText(ctx, "업데이트 검증 실패 — " + issue + ". 설치를 중단합니다",
                            Toast.LENGTH_LONG).show();
                } else {
                    launchInstaller(ctx, dm, id);
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

    private static void launchInstaller(Context ctx, DownloadManager dm, long downloadId) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(ctx, "이 앱의 설치를 허용한 뒤 업데이트를 다시 눌러 주세요",
                    Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(settings);
            } catch (Exception ignored) {
                // 서비스 컨텍스트에서 설정 화면을 못 띄우면 토스트로 충분하다
            }
            return;
        }
        Uri uri = dm.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            Toast.makeText(ctx, "받은 업데이트 파일을 찾지 못했습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (ctx instanceof Activity) {
            ctx.startActivity(install);
        } else {
            // 백그라운드에서 액티비티를 직접 띄우는 건 OS가 막을 수 있어
            // 알림의 PendingIntent로 건넨다 — 탭 1회가 설치 확정이다.
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "boringmech 업데이트", NotificationManager.IMPORTANCE_HIGH));
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, install,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = (Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("업데이트 다운로드 완료")
                    .setContentText("탭하면 설치가 시작됩니다")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIFICATION_ID, notification);
            Toast.makeText(ctx, "업데이트 다운로드 완료 — 알림에서 설치하세요",
                    Toast.LENGTH_LONG).show();
        }
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
}
