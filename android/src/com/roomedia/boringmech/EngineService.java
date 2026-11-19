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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

// 엔진 호스트 — 네이티브 엔진 스레드(NativeEngine)를 소유하는 포그라운드
// 서비스. 앱이 백그라운드로 가거나 액티비티가 죽어도 엔진은 여기서 계속
// 돈다 ("무인 자동화 서버" 요구). JS 엔진 페이지는 더 이상 띄우지 않는다 —
// 네이티브 루프와 JS 루프가 동시에 도는 경로는 구조적으로 없다.
// 실행 의도의 네이티브 미러(SharedPreferences)도 여기서 관리한다 —
// 알림에서 정지하면 미러를 먼저 지워 다음 실행 때 자동 재개되지 않게 한다.
//
// 워치독은 전용 스레드에서 돈다. 무장은 onCreate 즉시, 판정·예약은
// 워치독 스레드가 소유하고, 지시 실행도 워치독 스레드에서 바로 한다
// (네이티브 엔진 시작·재시작은 스레드 안전하고 메인 스레드를 거치지
// 않으므로 지시 무응답 경로 자체가 없다). 판정은 WatchdogLogic(순수
// Java)이 맡고, webViewExists 입력은 네이티브 엔진 스레드 생존으로
// 읽는다 — CREATE/RELOAD/RECREATE 모두 엔진 (재)시작으로 매핑된다.
// 감시 항목:
//  - 하트비트 stale 200초 (사이클 3개 + 여유, 종전과 같은 값)
//  - 사이클 정체 240초 — 번호 전진과 사이클 내부 진행 신호 중 늦은
//    쪽 기준. 검증만 긴 사이클은 진행 신호가 움직여 오재로드하지 않고,
//    단계 내부까지 멈춘 경우에만 걸린다.
//  - 부팅 감시 90초 — 엔진 시작·재시작 뒤 첫 하트비트가 없으면 부팅
//    실패로 세고 엔진을 재생성한다. 복원 진행 신호가 오면 기한은
//    그만큼 미뤄진다.
//  - 단계 데드라인 180초 (StageDeadline) — 작업 단계(snapshot 등)가
//    상한을 넘겨 멈추면 로그·오류 알림 후 세션을 자가 재시작한다.
//    제출 가드 의미론은 워치독과 같다. 워치독 스레드까지 멈추는
//    프로세스 단위 정지는 앱 내부로 감지할 수 없다는 한계가 있다.
//  - 엔진 스레드 소멸 시 재생성.
// 재로드 횟수는 네이티브 엔진이 복원 직후 읽어 로그로 남긴다.
//
// 최외곽에는 프로세스 밖 감시가 한 층 더 있다 (ExternalWatchdog):
// AlarmManager 알람이 ExternalWatchdogReceiver를 깨워, 실행 의도가
// 켜져 있는데 활동 신호가 6분 넘게 멈춰 있으면 이 서비스를 재기동한다.
// 내부 워치독이 프로세스 사망과 함께 죽는 구조적 한계를 메우는 층이다.
public class EngineService extends Service {
    public static final String ACTION_STOP = "com.roomedia.boringmech.ACTION_STOP";
    public static final String ACTION_RELOAD_ENGINE =
            "com.roomedia.boringmech.RELOAD_ENGINE";

    private static final String PREFS = "bm_engine";
    private static final String KEY_RUN_INTENT = "run_intent";
    private static final String KEY_HEARTBEAT_MS = "heartbeat_ms";
    private static final String KEY_HEARTBEAT_CYCLE = "heartbeat_cycle";
    private static final String KEY_HEARTBEAT_CYCLE_CHANGED_MS = "heartbeat_cycle_changed_ms";
    private static final String KEY_PROGRESS_MS = "progress_ms";
    private static final String KEY_WD_RELOADS = "wd_reloads";
    private static final String KEY_WD_CYCLE_RELOADS = "wd_cycle_reloads";
    private static final String KEY_BOOT_FAILURES = "boot_failures";
    private static final String KEY_DIRECTIVE_UNACKED = "directive_unacked";
    private static final String KEY_SNAP_CACHE = "snap_cache";
    private static final String KEY_SUBMIT_IN_FLIGHT = "submit_in_flight";
    private static final String KEY_SUBMIT_SINCE_MS = "submit_since_ms";
    private static final String KEY_STAGE_RESTARTS = "stage_restarts";
    private static final String KEY_LAST_STAGE_RESTART_MS = "last_stage_restart_ms";
    private static final String KEY_EXT_RESTARTS = "ext_restarts";
    private static final String KEY_LAST_EXT_RESTART_MS = "last_ext_restart_ms";
    // 네이티브 섀도우용 미러 — JS boot/link가 남긴다 (Bridge.setFunderMirror 등)
    private static final String KEY_FUNDER = "funder";
    private static final String KEY_EOA = "eoa";
    private static final String KEY_CREDS_CIPHER = "creds_cipher";
    // 섀도우 대조 누적 기록은 별도 prefs 파일에 둔다
    private static final String SHADOW_PREFS = "bm_shadow";
    private static final long SHADOW_PERIOD_MS = 60_000;
    private static final String CHANNEL_ID = "bm_engine";
    private static final int NOTIFICATION_ID = 1;

    private PowerManager.WakeLock wakeLock;
    // 네이티브 엔진 — 시작·재시작·정지 모두 스레드 안전하다.
    private NativeEngine nativeEngine;
    private BroadcastReceiver reloadReceiver;
    private BroadcastReceiver downloadReceiver;
    private Thread shadowThread;
    private volatile boolean shadowRunning;

    // ── 워치독 (전용 스레드) ─────────────────────────────────────
    private HandlerThread watchdogThread;
    private Handler watchdogHandler;
    private volatile boolean watchdogRunning;
    // 마지막 틱 시각 — 제보 payload의 tick_alive로 실어, 틱 자체가
    // 멈췄는지를 원격에서 판별할 수 있게 한다.
    private static volatile long lastTickMs;
    // 유예 앵커 — 서비스 시작 시각. 어떤 복구 동작으로도 움직이지 않는다.
    private long serviceStartMs;
    // 아래 상태는 워치독 스레드에서만 읽고 쓴다 (엔진 생존 이벤트는
    // watchdogHandler로 post해 이 스레드에서 반영한다).
    private boolean wdEngineAlive;
    private long wdBootSinceMs;
    private long wdLastReloadMs;
    private WatchdogLogic.Action pendingAction;
    private long pendingIssuedMs;
    private long pendingLastPostMs;
    private long wdAckMs;

    private final Runnable watchdogTick = new Runnable() {
        @Override
        public void run() {
            try {
                tickWatchdog();
            } catch (Throwable t) {
                // 틱 내부 예외로 체인이 끊기지 않게 여기서 삼킨다 —
                // 종전 구조는 tickWatchdog 뒤 재예약이라 예외 한 번에
                // 워치독이 영구 정지했다.
                noteWatchdogError(String.valueOf(t));
            } finally {
                Handler h = watchdogHandler;
                if (h != null && watchdogRunning) {
                    h.postDelayed(this, WatchdogLogic.PERIOD_MS);
                }
            }
        }
    };

    private final NativeEngine.Host nativeHost = new NativeEngine.Host() {
        @Override
        public void onEngineAliveChanged(final boolean alive) {
            Handler h = watchdogHandler;
            if (h != null) {
                h.post(() -> {
                    wdEngineAlive = alive;
                    if (alive) {
                        // 새 엔진 세대의 부팅 감시는 시작 시각부터
                        wdBootSinceMs = System.currentTimeMillis();
                    }
                });
            }
        }

        @Override
        public void onEngineStopRequested() {
            // 네이티브 엔진이 스스로 정지했으면(failsafe 등) 서비스도 내린다
            stopSelf();
        }
    };

    static boolean getRunIntent(Context ctx) {
        return prefs(ctx).getBoolean(KEY_RUN_INTENT, false);
    }

    static void setRunIntent(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_RUN_INTENT, on).apply();
    }

    static void recordHeartbeat(Context ctx, int cycle) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = p.edit()
                .putLong(KEY_HEARTBEAT_MS, now)
                .putInt(KEY_HEARTBEAT_CYCLE, cycle);
        // 사이클 번호가 바뀐 순간만 전진 시각을 갱신한다 — 워치독의
        // 정체 판정은 이 시각을 기준으로 한다. 첫 기록도 전진으로 친다.
        if (!p.contains(KEY_HEARTBEAT_CYCLE)
                || p.getInt(KEY_HEARTBEAT_CYCLE, 0) != cycle) {
            e.putLong(KEY_HEARTBEAT_CYCLE_CHANGED_MS, now);
        }
        e.apply();
    }

    // 사이클 내부 진행 신호 — 루프가 단계·후보를 처리할 때만 갱신된다.
    // 30초 타이머 하트비트와 분리해 두는 것이 핵심이다: 타이머는 루프가
    // 죽어도 살아 있지만, 이 값은 루프가 실제로 전진할 때만 움직인다.
    static void recordProgress(Context ctx) {
        prefs(ctx).edit()
                .putLong(KEY_PROGRESS_MS, System.currentTimeMillis())
                .apply();
    }

    static long getHeartbeatMs(Context ctx) {
        return prefs(ctx).getLong(KEY_HEARTBEAT_MS, 0);
    }

    static long getCycleChangedMs(Context ctx) {
        return prefs(ctx).getLong(KEY_HEARTBEAT_CYCLE_CHANGED_MS, 0);
    }

    static long getProgressMs(Context ctx) {
        return prefs(ctx).getLong(KEY_PROGRESS_MS, 0);
    }

    // ── 외부 감시 (AlarmManager → ExternalWatchdogReceiver) ──────
    // 내부 워치독은 같은 프로세스라 프로세스 사망과 함께 죽는다.
    // 외부 감시는 시스템 알람이 리시버를 깨우는 구조라 프로세스가
    // 죽어도 살아남는다. 재기동 기록은 여기 남긴다 — 제보에서
    // "외부 감시가 실제로 발동했는가"를 가르는 재료다.
    static long getLastExternalRestartMs(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_EXT_RESTART_MS, 0);
    }

    static void recordExternalRestart(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit()
                .putInt(KEY_EXT_RESTARTS, p.getInt(KEY_EXT_RESTARTS, 0) + 1)
                .putLong(KEY_LAST_EXT_RESTART_MS, System.currentTimeMillis())
                .apply();
    }

    static int getHeartbeatCycle(Context ctx) {
        return prefs(ctx).getInt(KEY_HEARTBEAT_CYCLE, 0);
    }

    static int getWatchdogReloads(Context ctx) {
        return prefs(ctx).getInt(KEY_WD_RELOADS, 0);
    }

    static int getWatchdogCycleReloads(Context ctx) {
        return prefs(ctx).getInt(KEY_WD_CYCLE_RELOADS, 0);
    }

    static int getBootFailures(Context ctx) {
        return prefs(ctx).getInt(KEY_BOOT_FAILURES, 0);
    }

    static int getDirectiveUnacked(Context ctx) {
        return prefs(ctx).getInt(KEY_DIRECTIVE_UNACKED, 0);
    }

    static long getLastTickMs() {
        return lastTickMs;
    }

    // 제보 payload용 네이티브 상태 — 다음 사고 때 "워치독이 돌았는가,
    // 재로드는 걸렸는가, 부팅은 실패했는가"를 원격으로 가를 재료다.
    static String getNativeStateJson(Context ctx) {
        SharedPreferences p = prefs(ctx);
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("wd_reloads", p.getInt(KEY_WD_RELOADS, 0));
            o.put("wd_cycle_reloads", p.getInt(KEY_WD_CYCLE_RELOADS, 0));
            o.put("heartbeat_ms", p.getLong(KEY_HEARTBEAT_MS, 0));
            o.put("heartbeat_cycle", p.getInt(KEY_HEARTBEAT_CYCLE, 0));
            o.put("boot_failures", p.getInt(KEY_BOOT_FAILURES, 0));
            o.put("directive_unacked", p.getInt(KEY_DIRECTIVE_UNACKED, 0));
            o.put("stage_restarts", p.getInt(KEY_STAGE_RESTARTS, 0));
            o.put("ext_restarts", p.getInt(KEY_EXT_RESTARTS, 0));
            o.put("tick_alive", lastTickMs);
            return o.toString();
        } catch (Exception e) {
            return "{\"wd_reloads\":0,\"wd_cycle_reloads\":0,\"heartbeat_ms\":0,"
                    + "\"heartbeat_cycle\":0,\"boot_failures\":0,"
                    + "\"directive_unacked\":0,\"tick_alive\":0}";
        }
    }

    static void setSubmitInFlight(Context ctx, boolean on) {
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor e = p.edit().putBoolean(KEY_SUBMIT_IN_FLIGHT, on);
        if (on) {
            // 제출 시작 시각을 함께 남긴다 — 워치독 가드 상한의 경과 기준.
            if (p.getLong(KEY_SUBMIT_SINCE_MS, 0) == 0) {
                e.putLong(KEY_SUBMIT_SINCE_MS, System.currentTimeMillis());
            }
        } else {
            e.putLong(KEY_SUBMIT_SINCE_MS, 0);
        }
        e.apply();
    }

    static boolean getSubmitInFlight(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SUBMIT_IN_FLIGHT, false);
    }

    static long getSubmitSinceMs(Context ctx) {
        return prefs(ctx).getLong(KEY_SUBMIT_SINCE_MS, 0);
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

    // ── 네이티브 엔진 비밀 미러 (개인키·Relayer 키) ── 암호문 그대로만
    // 보관한다. 빈 값이면 미러를 비운다 (creds 미러와 같은 규칙).
    private static final String KEY_PK_CIPHER = "pk_cipher";
    private static final String KEY_RELAYER_CIPHER = "relayer_cipher";

    static void setPkCipher(Context ctx, String cipher) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (cipher != null && !cipher.isEmpty()) {
            e.putString(KEY_PK_CIPHER, cipher);
        } else {
            e.remove(KEY_PK_CIPHER);
        }
        e.apply();
    }

    static String getPkCipher(Context ctx) {
        return prefs(ctx).getString(KEY_PK_CIPHER, null);
    }

    static void setRelayerCipher(Context ctx, String cipher) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (cipher != null && !cipher.isEmpty()) {
            e.putString(KEY_RELAYER_CIPHER, cipher);
        } else {
            e.remove(KEY_RELAYER_CIPHER);
        }
        e.apply();
    }

    static String getRelayerCipher(Context ctx) {
        return prefs(ctx).getString(KEY_RELAYER_CIPHER, null);
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

    /** 틱 내부 오류의 최후 기록 — 섀도우 기록 파일에 흔적만 남긴다. */
    private void noteWatchdogError(String message) {
        try {
            shadowPrefs(this).edit()
                    .putString("lastWatchdogError", message)
                    .apply();
        } catch (Exception ignored) {
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
        serviceStartMs = System.currentTimeMillis();

        // 외부 감시 체인 장전은 여기서도 한다 — onStartCommand의 앞단
        // (포그라운드 전환 등)이 예외로 죽어도, 실행 의도가 켜져 있는 한
        // 체인이 장전돼 있어야 리시버가 서비스를 되살릴 수 있다
        // (v0.3.35 감사 축 4). 장전 자체는 예외를 삼키는 멱등 연산이다.
        if (getRunIntent(this)) {
            ExternalWatchdogScheduler.schedule(this);
        }

        // 워치독 무장은 여기서 즉시 한다 — 어떤 엔진 시작 경로보다 먼저
        // 걸려 있어야 시작이 멈춰도 틱이 돈다.
        watchdogRunning = true;
        watchdogThread = new HandlerThread("bm-watchdog");
        watchdogThread.start();
        watchdogHandler = new Handler(watchdogThread.getLooper());
        watchdogHandler.postDelayed(watchdogTick, WatchdogLogic.PERIOD_MS);

        // 뷰어에서 연동이 바뀌면 네이티브 엔진을 재시작해 자격증명을 맞춘다.
        // 엔진이 살아 있을 때만 재시작한다 (JS 시절 "engineWeb 있을 때만
        // reload"와 같다). 이 재시작은 유예 앵커를 움직이지 않는다 — 새
        // 엔진 세대의 부팅 감시만 워치독 스레드에서 새로 시작한다.
        reloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (nativeEngine != null && nativeEngine.isAlive()) {
                    nativeEngine.restart();
                    Handler h = watchdogHandler;
                    if (h != null) {
                        h.post(() -> wdBootSinceMs = System.currentTimeMillis());
                    }
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
            if (nativeEngine != null) {
                nativeEngine.stop();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!getRunIntent(this)) {
            // 실행 의도가 없는데 시스템이 서비스를 되살린 경우 — 할 일이 없다
            stopSelf();
            return START_NOT_STICKY;
        }
        // 외부 감시 체인 장전을 포그라운드 전환보다 먼저 한다 — 전환이
        // 예외로 죽어도 체인이 남아 리시버가 재시도를 이어간다. 이미
        // 걸려 있으면 교체될 뿐이라 매 시작마다 불러도 안전하다.
        ExternalWatchdogScheduler.schedule(this);
        startForegroundWithNotification();
        acquireWakeLock();
        ensureNativeEngine();
        return START_STICKY;
    }

    // 네이티브 엔진이 없으면 만들고 시작한다. 생존 상태는 NativeEngine
    // 콜백이 워치독 스레드에 반영한다 (존재 상태 + 새 세대 부팅 감시).
    private void ensureNativeEngine() {
        if (nativeEngine == null) {
            nativeEngine = new NativeEngine(this, nativeHost);
        }
        nativeEngine.start();
    }

    private void restartNativeEngine() {
        if (nativeEngine == null) {
            nativeEngine = new NativeEngine(this, nativeHost);
        }
        nativeEngine.restart();
    }

    // ── 워치독 틱 (워치독 스레드) ─────────────────────────────────
    private void tickWatchdog() {
        long now = System.currentTimeMillis();
        lastTickMs = now;

        // 미확인 지시 추적 — 실행자가 돌지 않으면 지시를 반복하고 횟수를
        // 남긴다. 네이티브 지시는 워치독 스레드에서 바로 실행돼 즉시
        // ack되므로 이 경로는 사실상 닿지 않지만, 판정 로직은 종전과
        // 같은 형태로 유지한다. 확인된 지시가 남아 있는 동안은 새
        // 판정을 내리지 않는다 (이중 지시로 카운터가 부풀지 않게).
        if (pendingAction != null) {
            if (WatchdogLogic.isDirectiveAcked(pendingIssuedMs, wdAckMs,
                    0, getHeartbeatMs(this))) {
                pendingAction = null;
            } else if (WatchdogLogic.shouldRepostDirective(now, pendingLastPostMs)) {
                SharedPreferences p = prefs(this);
                p.edit().putInt(KEY_DIRECTIVE_UNACKED,
                        p.getInt(KEY_DIRECTIVE_UNACKED, 0) + 1).apply();
                postDirective(pendingAction, pendingIssuedMs);
                pendingLastPostMs = now;
                return;
            } else {
                return;
            }
        }

        SharedPreferences p = prefs(this);

        // 단계 데드라인 — 부팅 감시 구간(bootSince>0)에서는 부팅 판정이
        // 복구를 소유하므로 건드리지 않는다. 그 외 구간에서 작업 단계가
        // 상한을 넘겨 멈춰 있으면, 워치독 정체 판정(240초)보다 먼저
        // 180초에 로그·알림을 남기고 세션을 자가 재시작한다.
        if (wdBootSinceMs <= 0 && checkStageDeadline(now, p)) {
            return;
        }

        WatchdogLogic.Input in = new WatchdogLogic.Input();
        in.now = now;
        in.runIntent = getRunIntent(this);
        in.webViewExists = wdEngineAlive;
        in.serviceStartMs = serviceStartMs;
        in.lastReloadMs = wdLastReloadMs;
        in.heartbeatMs = p.getLong(KEY_HEARTBEAT_MS, 0);
        in.cycleChangedMs = p.getLong(KEY_HEARTBEAT_CYCLE_CHANGED_MS, 0);
        in.progressMs = p.getLong(KEY_PROGRESS_MS, 0);
        in.submitInFlight = getSubmitInFlight(this);
        in.submitSinceMs = getSubmitSinceMs(this);
        in.bootSinceMs = wdBootSinceMs;

        WatchdogLogic.Decision d = WatchdogLogic.decide(in);
        if (d.bootCompleted) {
            wdBootSinceMs = 0;
        }
        if (d.bootFailure) {
            p.edit().putInt(KEY_BOOT_FAILURES,
                    p.getInt(KEY_BOOT_FAILURES, 0) + 1).apply();
        }
        if (d.action == WatchdogLogic.Action.NONE) return;

        if (d.action == WatchdogLogic.Action.RELOAD) {
            // 종전 재로드와 같은 부수효과 — 하트비트·제출 가드를 비우고
            // 재로드 횟수를 남긴다. 정체 사유는 별도 카운터로 구분한다.
            SharedPreferences.Editor e = p.edit()
                    .putInt(KEY_WD_RELOADS, p.getInt(KEY_WD_RELOADS, 0) + 1)
                    .remove(KEY_HEARTBEAT_MS)
                    .remove(KEY_HEARTBEAT_CYCLE)
                    .remove(KEY_HEARTBEAT_CYCLE_CHANGED_MS)
                    .putBoolean(KEY_SUBMIT_IN_FLIGHT, false)
                    .putLong(KEY_SUBMIT_SINCE_MS, 0);
            if (d.cycleReason) {
                e.putInt(KEY_WD_CYCLE_RELOADS,
                        p.getInt(KEY_WD_CYCLE_RELOADS, 0) + 1);
            }
            e.apply();
            wdLastReloadMs = now;
        }
        // 새 엔진 세대의 부팅 감시는 지시 시각부터 시작한다. 실행이
        // 늦어지면 그만큼 부팅 기한이 촉박해지지만, 시작 통지·진행
        // 신호가 오면 판정이 다시 미뤄진다.
        wdBootSinceMs = now;
        issueDirective(d.action, now);
    }

    /**
     * 단계 데드라인 점검 — 동결이면 로그·알림·카운터를 남기고 엔진을
     * 재시작한 뒤 true를 돌려준다 (이번 틱의 일반 판정은 건너뜀).
     * 판정 자체는 StageDeadline 순수 로직이 맡는다.
     */
    private boolean checkStageDeadline(long now, SharedPreferences p) {
        String stage = null;
        long stageTs = 0;
        try {
            String stageJson = DurableStore.stageJson(this);
            if (stageJson != null) {
                org.json.JSONObject o = new org.json.JSONObject(stageJson);
                stage = o.optString("stage", null);
                stageTs = o.optLong("ts", 0);
            }
        } catch (Exception ignored) {
        }
        StageDeadline.Input in = new StageDeadline.Input();
        in.now = now;
        in.runIntent = getRunIntent(this);
        in.engineAlive = wdEngineAlive;
        in.stage = stage;
        in.stageSinceMs = stageTs;
        in.progressMs = p.getLong(KEY_PROGRESS_MS, 0);
        in.submitInFlight = getSubmitInFlight(this);
        in.submitSinceMs = getSubmitSinceMs(this);
        in.lastStageRestartMs = p.getLong(KEY_LAST_STAGE_RESTART_MS, 0);
        if (!StageDeadline.isStageFrozen(in)) {
            return false;
        }
        long ageSec = StageDeadline.stageAgeSec(in);
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        DurableStore.appendLog(this, "[" + ts + "] 단계 데드라인 — " + stage + " 단계 "
                + ageSec + "초 정지 (상한 " + (StageDeadline.STAGE_DEADLINE_MS / 1000)
                + "초), 엔진을 재시작합니다");
        Bridge.notifyError(this, com.roomedia.boringmech.write.NotifyPolicy.KEY_STAGE_DEADLINE,
                "엔진 동결 감지", stage + " 단계 " + ageSec + "초 정지 — 엔진을 재시작합니다");
        p.edit()
                .putInt(KEY_STAGE_RESTARTS, p.getInt(KEY_STAGE_RESTARTS, 0) + 1)
                .putLong(KEY_LAST_STAGE_RESTART_MS, now)
                // RELOAD 경로와 동일하게 제출 상태를 지운다. 제출 가드 상한을
                // 지난 제출 때문에 발동한 경우 옛 submit_since가 남아 있으면
                // 새 세션의 워치독 판정이 오염된다.
                .putBoolean(KEY_SUBMIT_IN_FLIGHT, false)
                .putLong(KEY_SUBMIT_SINCE_MS, 0)
                .apply();
        restartNativeEngine();
        wdBootSinceMs = now;
        return true;
    }

    private void issueDirective(WatchdogLogic.Action action, long now) {
        pendingAction = action;
        pendingIssuedMs = now;
        pendingLastPostMs = now;
        postDirective(action, now);
    }

    // 지시를 워치독 스레드에서 바로 실행한다. 네이티브 엔진의 시작·
    // 재시작은 스레드 안전하고 메인 스레드를 거치지 않으므로 실행 즉시
    // ack를 남긴다 — 메인 스레드 정지로 지시가 무응답이 되는 경로는
    // 구조적으로 없다. CREATE/RELOAD/RECREATE 모두 엔진 (재)시작이다.
    private void postDirective(final WatchdogLogic.Action action, final long issuedMs) {
        try {
            switch (action) {
                case CREATE:
                    ensureNativeEngine();
                    break;
                case RELOAD:
                case RECREATE:
                    restartNativeEngine();
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
            // 실행 실패는 존재 상태·부팅 감시가 다음 틱에 다시 가른다.
        }
        if (issuedMs > wdAckMs) wdAckMs = issuedMs;
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
        // 외부 감시 체인은 실행 의도가 꺼진 종료에서만 해제한다 —
        // 시스템이 서비스를 죽인 경우(onDestroy가 불릴 수도, 안 불릴
        // 수도 있다)에는 체인이 남아 있어야 리시버가 되살릴 수 있다.
        if (!getRunIntent(this)) {
            ExternalWatchdogScheduler.cancel(this);
        }
        shadowRunning = false;
        if (shadowThread != null) {
            shadowThread.interrupt();
            shadowThread = null;
        }
        watchdogRunning = false;
        if (watchdogHandler != null) {
            watchdogHandler.removeCallbacks(watchdogTick);
            watchdogHandler = null;
        }
        if (watchdogThread != null) {
            watchdogThread.quitSafely();
            watchdogThread = null;
        }
        if (reloadReceiver != null) {
            unregisterReceiver(reloadReceiver);
        }
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
        }
        if (nativeEngine != null) {
            nativeEngine.stop();
            nativeEngine = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
