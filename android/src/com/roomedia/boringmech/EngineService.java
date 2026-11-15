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
import android.webkit.RenderProcessGoneDetail;
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
    private static final String KEY_SUBMIT_IN_FLIGHT = "submit_in_flight";
    // 네이티브 섀도우용 미러 — JS boot/link가 남긴다 (Bridge.setFunderMirror 등)
    private static final String KEY_FUNDER = "funder";
    private static final String KEY_EOA = "eoa";
    private static final String KEY_CREDS_CIPHER = "creds_cipher";
    // 섀도우 대조 누적 기록은 별도 prefs 파일에 둔다
    private static final String SHADOW_PREFS = "bm_shadow";
    private static final long SHADOW_PERIOD_MS = 60_000;
    private static final String CHANNEL_ID = "bm_engine";
    private static final int NOTIFICATION_ID = 1;

    private static final long WATCHDOG_PERIOD_MS = 30_000;
    private static final long HEARTBEAT_STALE_MS = 200_000;
    private static final long LOAD_GRACE_MS = 240_000;
    // 제출 가드의 상한 (F1, JS SUBMIT_GUARD_MAX_MS와 같은 값) — 하트비트가
    // 이보다 오래됐으면 제출이 끝난 것으로 보고 가드를 무시하고 재로드한다.
    private static final long SUBMIT_GUARD_MAX_MS = 600_000;

    private PowerManager.WakeLock wakeLock;
    private WebView engineWeb;
    private long engineLoadedAtMs;
    private Handler handler;
    private BroadcastReceiver reloadReceiver;
    private BroadcastReceiver downloadReceiver;
    private Thread shadowThread;
    private volatile boolean shadowRunning;

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

    static void setSubmitInFlight(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_SUBMIT_IN_FLIGHT, on).apply();
    }

    static boolean getSubmitInFlight(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SUBMIT_IN_FLIGHT, false);
    }

    static void putSnapCache(Context ctx, String json) {
        if (json == null) return;
        prefs(ctx).edit().putString(KEY_SNAP_CACHE, json).apply();
    }

    static String getSnapCache(Context ctx) {
        return prefs(ctx).getString(KEY_SNAP_CACHE, null);
    }

    // ── 섀도우 미러 ── 주소 형식이 아니면 미러를 비운다 — 손상된 값을
    // 그대로 쓰면 섀도우 조회가 엉뚱한 대상을 향한다 (JS boot의 검증과 동일).
    private static final java.util.regex.Pattern ADDRESS_RE =
            java.util.regex.Pattern.compile("^0x[0-9a-fA-F]{40}$");

    static void setFunder(Context ctx, String funder) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (funder != null && ADDRESS_RE.matcher(funder).matches()) {
            e.putString(KEY_FUNDER, funder);
        } else {
            e.remove(KEY_FUNDER);
        }
        e.apply();
    }

    static String getFunder(Context ctx) {
        return prefs(ctx).getString(KEY_FUNDER, null);
    }

    static void setEoa(Context ctx, String eoa) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (eoa != null && ADDRESS_RE.matcher(eoa).matches()) {
            e.putString(KEY_EOA, eoa);
        } else {
            e.remove(KEY_EOA);
        }
        e.apply();
    }

    static String getEoa(Context ctx) {
        return prefs(ctx).getString(KEY_EOA, null);
    }

    static void setCredsCipher(Context ctx, String cipher) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (cipher != null && !cipher.isEmpty()) {
            e.putString(KEY_CREDS_CIPHER, cipher);
        } else {
            e.remove(KEY_CREDS_CIPHER);
        }
        e.apply();
    }

    static String getCredsCipher(Context ctx) {
        return prefs(ctx).getString(KEY_CREDS_CIPHER, null);
    }

    // ── 섀도우 대조 기록 (bm_shadow) ──
    private static SharedPreferences shadowPrefs(Context ctx) {
        return ctx.getSharedPreferences(SHADOW_PREFS, MODE_PRIVATE);
    }

    /** 섀도우 1회 완료 기록 — runs는 항상, matches는 일치했을 때만 오른다. */
    static void recordShadowRun(Context ctx, boolean matched, String diffJson) {
        SharedPreferences p = shadowPrefs(ctx);
        SharedPreferences.Editor e = p.edit()
                .putInt("runs", p.getInt("runs", 0) + 1)
                .putLong("lastRunMs", System.currentTimeMillis())
                .putString("lastDiff", diffJson != null ? diffJson : "")
                .remove("lastError");
        if (matched) {
            e.putInt("matches", p.getInt("matches", 0) + 1);
        }
        e.apply();
    }

    /** 섀도우 1회 실패 기록 — runs에 세고 사유를 남긴다. */
    static void recordShadowError(Context ctx, String message) {
        SharedPreferences p = shadowPrefs(ctx);
        p.edit()
                .putInt("runs", p.getInt("runs", 0) + 1)
                .putLong("lastRunMs", System.currentTimeMillis())
                .putString("lastError", message != null ? message : "unknown")
                .apply();
    }

    /** 기록 단계 자체가 깨졌을 때의 최후 통지 — 카운터는 건드리지 않는다. */
    static void noteShadowCrash(Context ctx, String message) {
        try {
            shadowPrefs(ctx).edit()
                    .putString("lastError", "shadow crash: " + message)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    static String getShadowStatusJson(Context ctx) {
        SharedPreferences p = shadowPrefs(ctx);
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("runs", p.getInt("runs", 0));
            o.put("matches", p.getInt("matches", 0));
            o.put("lastDiff", p.getString("lastDiff", ""));
            o.put("lastRunMs", p.getLong("lastRunMs", 0));
            String err = p.getString("lastError", null);
            if (err != null) o.put("lastError", err);
            return o.toString();
        } catch (Exception e) {
            return "{\"runs\":0,\"matches\":0,\"lastDiff\":\"\",\"lastRunMs\":0}";
        }
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

        startShadowLoop();
    }

    // 네이티브 섀도우 루프 — 실행 의도가 켜져 있을 때만 60초 주기로
    // 읽기 전용 스냅샷을 만들어 JS 스냅샷과 대조한다. 별도 스레드에서
    // 돌고, 어떤 실패도 워치독·엔진 본체에 닿지 않게 여기서 전부 삼킨다.
    private void startShadowLoop() {
        if (shadowThread != null) return;
        shadowRunning = true;
        shadowThread = new Thread(() -> {
            while (shadowRunning) {
                try {
                    Thread.sleep(SHADOW_PERIOD_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!shadowRunning) break;
                if (!getRunIntent(EngineService.this)) continue;
                try {
                    ShadowSnapshot.runOnce(EngineService.this);
                } catch (Throwable t) {
                    noteShadowCrash(EngineService.this, String.valueOf(t));
                }
            }
        }, "bm-shadow");
        shadowThread.setDaemon(true);
        shadowThread.start();
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

            @Override
            public boolean onRenderProcessGone(WebView view,
                                                RenderProcessGoneDetail detail) {
                // 렌더러가 죽은 WebView는 reload로 되살릴 수 없다 (F6) —
                // 버리고 새로 만들어 페이지를 다시 띄운다.
                if (engineWeb != null) {
                    engineWeb.destroy();
                    engineWeb = null;
                }
                engineLoadedAtMs = System.currentTimeMillis();
                ensureEngine();
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
        // 제출 진행 중이면 재로드를 미룬다 (F1) — 재로드가 제출 대기를
        // 끊으면 체결 기록이 남지 않아 중복 체결 창이 열린다. 단,
        // 하트비트가 가드 상한보다 오래됐으면 제출이 끝난 것으로 본다.
        if (getSubmitInFlight(this) && now - hb <= SUBMIT_GUARD_MAX_MS) return;
        SharedPreferences p = prefs(this);
        p.edit()
                .putInt(KEY_WD_RELOADS, p.getInt(KEY_WD_RELOADS, 0) + 1)
                .remove(KEY_HEARTBEAT_MS)
                .putBoolean(KEY_SUBMIT_IN_FLIGHT, false)
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
        shadowRunning = false;
        if (shadowThread != null) {
            shadowThread.interrupt();
            shadowThread = null;
        }
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
