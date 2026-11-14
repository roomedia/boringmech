package com.roomedia.boringmech;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// 엔진 호스트 — WebView를 소유하고 엔진 페이지(사이클)를 구동하는
// 포그라운드 서비스. 앱이 백그라운드로 가거나 액티비티가 죽어도
// 엔진은 여기서 계속 돈다 ("무인 자동화 서버" 요구).
// 실행 의도의 네이티브 미러(SharedPreferences)도 여기서 관리한다 —
// 알림에서 정지하면 미러를 먼저 지워 다음 실행 때 자동 재개되지 않게 한다.
// 워치독: JS가 사이클마다 남기는 하트비트를 30초마다 확인해
// 200초(사이클 3개 + 여유, JS HEARTBEAT_STALE_MS와 같은 값) 이상
// stale이면 엔진 페이지를 재로드한다. 재로드 횟수는 JS가 복원 직후
// 읽어 로그로 남긴다.
public class EngineService extends Service {
    public static final String ACTION_STOP = "com.roomedia.boringmech.ACTION_STOP";
    public static final String ACTION_RELOAD_ENGINE =
            "com.roomedia.boringmech.RELOAD_ENGINE";

    private static final String PREFS = "bm_engine";
    private static final String KEY_RUN_INTENT = "run_intent";
    private static final String KEY_HEARTBEAT_MS = "heartbeat_ms";
    private static final String KEY_HEARTBEAT_CYCLE = "heartbeat_cycle";
    private static final String KEY_WD_RELOADS = "wd_reloads";
    private static final String KEY_SNAP_CACHE = "snap_cache";
    private static final String CHANNEL_ID = "bm_engine";
    private static final int NOTIFICATION_ID = 1;

    private static final long WATCHDOG_PERIOD_MS = 30_000;
    private static final long HEARTBEAT_STALE_MS = 200_000;
    private static final long LOAD_GRACE_MS = 240_000;

    private PowerManager.WakeLock wakeLock;
    private WebView engineWeb;
    private long engineLoadedAtMs;
    private Handler handler;
    private BroadcastReceiver reloadReceiver;
    private BroadcastReceiver downloadReceiver;

    private final Runnable watchdogTick = new Runnable() {
        @Override
        public void run() {
            tickWatchdog();
            if (handler != null) handler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };

    private final Bridge.Host engineHost = new Bridge.Host() {
        @Override
        public String hostMode() {
            return "engine";
        }

        @Override
        public void onRunIntentChanged(boolean on) {
            // 엔진 JS가 스스로 정지했으면(failsafe 등) 서비스도 내린다
            if (!on) stopSelf();
        }

        @Override
        public void onReloadEngine() {
            // 재로드는 뷰어 → 서비스 방향 전용이라 엔진 호스트에서는 무시
        }
    };

    static boolean getRunIntent(Context ctx) {
        return prefs(ctx).getBoolean(KEY_RUN_INTENT, false);
    }

    static void setRunIntent(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_RUN_INTENT, on).apply();
    }

    static void recordHeartbeat(Context ctx, int cycle) {
        prefs(ctx).edit()
                .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
                .putInt(KEY_HEARTBEAT_CYCLE, cycle)
                .apply();
    }

    static long getHeartbeatMs(Context ctx) {
        return prefs(ctx).getLong(KEY_HEARTBEAT_MS, 0);
    }

    static int getWatchdogReloads(Context ctx) {
        return prefs(ctx).getInt(KEY_WD_RELOADS, 0);
    }

    static void putSnapCache(Context ctx, String json) {
        if (json == null) return;
        prefs(ctx).edit().putString(KEY_SNAP_CACHE, json).apply();
    }

    static String getSnapCache(Context ctx) {
        return prefs(ctx).getString(KEY_SNAP_CACHE, null);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());

        // 뷰어에서 연동이 바뀌면 엔진 페이지를 다시 읽어 자격증명을 맞춘다
        reloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (engineWeb != null) {
                    engineLoadedAtMs = System.currentTimeMillis();
                    engineWeb.reload();
                }
            }
        };
        IntentFilter reloadFilter = new IntentFilter(ACTION_RELOAD_ENGINE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reloadReceiver, reloadFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(reloadReceiver, reloadFilter);
        }

        // 업데이트 다운로드 완료 — 액티비티가 없어도 서비스가 이어받는다
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                SelfUpdate.onDownloadComplete(EngineService.this,
                        intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1));
            }
        };
        IntentFilter dlFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, dlFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, dlFilter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setRunIntent(this, false);
            if (engineWeb != null) {
                engineWeb.evaluateJavascript("window.BM && BM.stop();", null);
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!getRunIntent(this)) {
            // 실행 의도가 없는데 시스템이 서비스를 되살린 경우 — 할 일이 없다
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundWithNotification();
        acquireWakeLock();
        ensureEngine();
        handler.removeCallbacks(watchdogTick);
        handler.postDelayed(watchdogTick, WATCHDOG_PERIOD_MS);
        return START_STICKY;
    }

    private void ensureEngine() {
        if (engineWeb != null) return;
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(this, engineHost), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                    // 엔진 호스트에서 외부 링크를 못 열어도 엔진은 계속 돈다
                }
                return true;
            }
        });
        engineWeb = web;
        engineLoadedAtMs = System.currentTimeMillis();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void tickWatchdog() {
        if (engineWeb == null || !getRunIntent(this)) return;
        long now = System.currentTimeMillis();
        long hb = getHeartbeatMs(this);
        boolean stale = hb > 0
                ? now - hb > HEARTBEAT_STALE_MS
                : now - engineLoadedAtMs > LOAD_GRACE_MS;
        if (!stale) return;
        SharedPreferences p = prefs(this);
        p.edit()
                .putInt(KEY_WD_RELOADS, p.getInt(KEY_WD_RELOADS, 0) + 1)
                .remove(KEY_HEARTBEAT_MS)
                .apply();
        engineLoadedAtMs = now;
        engineWeb.reload();
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "boringmech 엔진", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopAction = PendingIntent.getService(this, 1,
                new Intent(this, EngineService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("boringmech 실행 중")
                .setContentText("자동매매 엔진이 동작 중입니다")
                .setContentIntent(content)
                .addAction(android.R.drawable.ic_media_pause, "정지", stopAction)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boringmech:engine");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(watchdogTick);
        }
        if (reloadReceiver != null) {
            unregisterReceiver(reloadReceiver);
        }
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
        }
        if (engineWeb != null) {
            engineWeb.destroy();
            engineWeb = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
