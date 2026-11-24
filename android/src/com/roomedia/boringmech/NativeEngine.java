package com.roomedia.boringmech;

import android.content.Context;
import android.content.SharedPreferences;

import com.roomedia.boringmech.crypto.ClobAuth;
import com.roomedia.boringmech.crypto.Secp256k1;
import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.NativeCycle;
import com.roomedia.boringmech.write.ClobWriteClient;
import com.roomedia.boringmech.write.HttpTransport;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.OrderBridge;
import com.roomedia.boringmech.write.RawHttp;
import com.roomedia.boringmech.write.RedeemSubmitter;
import com.roomedia.boringmech.write.SubmitGuard;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 네이티브 엔진 러너 — JS 엔진 페이지의 restore/start/loop 스케줄링 등가.
 *
 * 세션(페이지 로드 등가)마다 비밀 복원 → NativeCycle 구축 → 선행 스냅샷 →
 * 업데이트 확인 → 워치독 복구 로그 → 사이클 루프(완료 후 60초) 순으로 돈다.
 * 사이클 오류 10회 연속이면 NativeCycle이 RESTART를 돌려주고, 러너는 복원부터
 * 다시 하는 새 세션으로 교체한다 (JS의 페이지 자가 재로드 등가).
 *
 * 이중 실행 방지: 엔진 스레드는 이 객체 하나만 소유하고, 세대(generation)가
 * 바뀐 세션은 isRunning(gen)이 거짓이 되어 스스로 종료한다. 재시작 시 새
 * 세션은 구 스레드의 종료를 기다린 뒤에 시작하고, 제출 가드는 엔진 단위
 * 단일 인스턴스를 전 세션이 공유하며 세대 게이트로 구 세션의 제출 경로를
 * 막는다 — 두 세션이 동시에 제출 가능한 상태는 구조적으로 생기지 않는다.
 * JS 엔진 페이지는 EngineService가 더 이상 띄우지 않으므로 네이티브와
 * 동시에 돌 경로가 없다.
 */
final class NativeEngine {

    interface Host {
        /** 엔진 스레드 생존 상태 — 워치독의 webViewExists 입력으로 연결된다. */
        void onEngineAliveChanged(boolean alive);

        /** failsafe·정지 — 서비스를 내린다. */
        void onEngineStopRequested();
    }

    private static final long CYCLE_MS = 60_000L;
    private static final long HEARTBEAT_MS = 30_000L;
    private static final long SECRET_WAIT_MS = 20_000L;
    private static final long RESTORE_RETRY_GATE_MS = 120_000L;
    /**
     * 재시작 시 구 세션 스레드 종료를 기다리는 상한. 제출 호출 타임아웃
     * (20초)과 전송 읽기 타임아웃(30초)을 모두 덮는 값 — 이 상한을 넘긴
     * 구 스레드는 어떤 제출 호출 안에도 있을 수 없고, 세대 게이트가 그
     * 이후의 제출 경로를 전부 막는다.
     */
    private static final long PREDECESSOR_JOIN_CAP_MS = 45_000L;

    private final Context ctx;
    private final Host host;
    private final RawHttp.Transport transport = new HttpTransport();
    private final OrderBridge orderBridge = new OrderBridge(transport, NativeNet.CODEC, ms -> {
    });
    /**
     * 제출 가드는 엔진 단위 단일 인스턴스 — 모든 세션이 같은 잠금·카운터를
     * 공유한다. 세대 공급자를 묶어 두어, 세대가 바뀌면 구 세션은 새 제출을
     * 하나도 시작할 수 없다.
     */
    private final SubmitGuard guard;

    private volatile boolean started;
    private volatile int generation;
    private volatile NativeCycle currentCycle;
    private Thread thread;
    private long lastRestoreFailMs;
    /** 현재 세션 시작 시각 — RestartGuard의 중복 재시작 판정 재료. */
    private volatile long sessionStartMs;

    NativeEngine(Context ctx, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.guard = new SubmitGuard(on -> EngineService.setSubmitInFlight(this.ctx, on));
        this.guard.bindGenerationSource(() -> generation);
    }

    synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        if (System.currentTimeMillis() - lastRestoreFailMs < RESTORE_RETRY_GATE_MS) {
            return;
        }
        // 살아 있는 엔진 스레드가 없을 때만 stale한 제출 중 플래그를
        // 정리한다 — 제출 소유자만 리셋할 수 있다는 원칙의 유일한 예외로,
        // 이 시점에는 제출 중인 세션이 존재하지 않는다.
        guard.resetInFlight();
        EngineService.setSubmitInFlight(ctx, false);
        started = true;
        sessionStartMs = System.currentTimeMillis();
        final int gen = ++generation;
        host.onEngineAliveChanged(true);
        thread = new Thread(() -> runSession(gen, null), "bm-engine");
        thread.setDaemon(true);
        thread.start();
    }

    /** 워치독 지시·자격증명 변경에 대한 재시작 — 복원 게이트를 건너뛴다. */
    synchronized void restart() {
        // 중복 재개 가드 — 방금 시작한 세션이 살아 있으면 재시작 요청을
        // 건너뛴다 (제보 37: 시작 1초 뒤 재시작으로 로그 쌍이 중복됨).
        // 동결 복구 재시작은 데드라인·정체 판정이 훨씬 긴 시간축에서
        // 내리므로 이 가드에 걸리지 않는다.
        if (RestartGuard.shouldSkipRestart(sessionStartMs,
                System.currentTimeMillis(),
                thread != null && thread.isAlive())) {
            return;
        }
        lastRestoreFailMs = 0;
        started = true;
        sessionStartMs = System.currentTimeMillis();
        // 세대부터 올린다 — 이 순간부터 구 세션은 SubmitGuard 세대
        // 게이트에서 새 제출을 시작할 수 없다.
        final int gen = ++generation;
        Thread old = thread;
        if (old != null) {
            old.interrupt();
        }
        host.onEngineAliveChanged(true);
        // 새 세션은 runSession 진입부에서 구 스레드의 종료를 기다린 뒤에
        // 시작한다 — 두 세션이 동시에 제출 가능한 상태는 생기지 않는다.
        thread = new Thread(() -> runSession(gen, old), "bm-engine");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        started = false;
        generation++;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
        host.onEngineAliveChanged(false);
    }

    boolean isAlive() {
        Thread t = thread;
        return started && t != null && t.isAlive();
    }

    private boolean isCurrent(int gen) {
        return started && gen == generation;
    }

    // ── 세션 ────────────────────────────────────────────────────────────────

    private static final class Session {
        final NativeCycle cycle;

        Session(NativeCycle cycle) {
            this.cycle = cycle;
        }
    }

    private void runSession(int gen, Thread predecessor) {
        Thread hb = null;
        try {
            if (!awaitPredecessor(gen, predecessor)) {
                return;
            }
            Session session = buildSession(gen);
            if (session == null) {
                if (isCurrent(gen)) {
                    lastRestoreFailMs = System.currentTimeMillis();
                }
                return;
            }
            currentCycle = session.cycle;
            hb = startHeartbeat(gen);
            session.cycle.resetSession();
            appendLog("이전 실행 의도 복원 — 엔진 자동 재개");
            appendLog("엔진 실행 시작");
            while (isCurrent(gen)) {
                NativeCycle.Result r = session.cycle.runIteration(gen);
                if (!isCurrent(gen)) {
                    break;
                }
                if (r == NativeCycle.Result.RESTART) {
                    // 자가 재시작 — 복원부터 다시 (페이지 재로드 등가)
                    Session next = buildSession(gen);
                    if (next == null) {
                        if (isCurrent(gen)) {
                            lastRestoreFailMs = System.currentTimeMillis();
                        }
                        return;
                    }
                    session = next;
                    currentCycle = session.cycle;
                    session.cycle.resetSession();
                    appendLog("이전 실행 의도 복원 — 엔진 자동 재개");
                    appendLog("엔진 실행 시작");
                    continue;
                }
                if (!sleepInterruptibly(CYCLE_MS)) {
                    break;
                }
            }
        } finally {
            if (hb != null) {
                hb.interrupt();
            }
            synchronized (this) {
                if (gen == generation) {
                    thread = null;
                    host.onEngineAliveChanged(false);
                }
            }
        }
    }

    /**
     * 재시작으로 교체되는 구 세션 스레드의 종료를 기다린다. 기다리는 동안
     * 이 세션은 세션을 구축하지 않으므로 제출 가능한 상태가 아니다.
     * 상한을 넘기면 구 세션은 세대 게이트로 제출 경로가 무력화된 상태이므로
     * 로그를 남기고 진행한다. 더 새 세대에 밀렸으면 false (즉시 종료).
     */
    private boolean awaitPredecessor(int gen, Thread predecessor) {
        if (predecessor == null || predecessor == Thread.currentThread()) {
            return true;
        }
        long deadline = System.currentTimeMillis() + PREDECESSOR_JOIN_CAP_MS;
        while (predecessor.isAlive()) {
            if (!isCurrent(gen)) {
                return false;
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                break;
            }
            try {
                predecessor.join(Math.min(remain, 1_000L));
            } catch (InterruptedException e) {
                break;
            }
        }
        if (!isCurrent(gen)) {
            return false;
        }
        if (predecessor.isAlive()) {
            appendLog("이전 세션 종료 대기 초과 — 세대 가드로 구 세션 제출 경로를 무력화한 채 새 세션을 시작합니다");
        }
        return true;
    }

    private Thread startHeartbeat(int gen) {
        Thread hb = new Thread(() -> {
            while (isCurrent(gen)) {
                if (!sleepInterruptibly(HEARTBEAT_MS)) {
                    break;
                }
                if (!isCurrent(gen)) {
                    break;
                }
                // 자가 재시작으로 세션이 교체돼도 현재 사이클 번호를 따라간다
                NativeCycle c = currentCycle;
                if (c != null) {
                    EngineService.recordHeartbeat(ctx, c.cycle());
                }
            }
        }, "bm-heartbeat");
        hb.setDaemon(true);
        hb.start();
        return hb;
    }

    private static boolean sleepInterruptibly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * restore() 이식 — 비밀 대기 → funder 탐지 → creds 확보 → 조립 →
     * 선행 스냅샷 → 업데이트 확인 → 워치독 복구 로그. 실패하면 null.
     */
    private Session buildSession(int gen) {
        // 사이클 오류 에스컬레이션으로 자가 재시작한 직후면 그 흔적을 남긴다
        long selfReloadAt = DurableStore.selfReloadMs(ctx);
        if (selfReloadAt > 0
                && System.currentTimeMillis() - selfReloadAt < NativeCycle.SELF_RESTART_COOLDOWN_MS) {
            appendLog("사이클 오류 연속으로 자가 재로드한 페이지입니다 — 복원을 이어갑니다");
        }
        // 이전 실행이 사이클 중간 단계에서 끝난 흔적이 있으면 중단 지점을 남긴다
        String prevStageJson = DurableStore.stageJson(ctx);
        if (prevStageJson != null) {
            try {
                Object prev = JsonText.parse(prevStageJson);
                Object stage = Js.field(prev, "stage");
                if (Js.truthy(stage) && !"idle".equals(stage) && !"stopped".equals(stage)) {
                    Object cyc = Js.field(prev, "cycle");
                    String cycText = (cyc instanceof Number)
                            ? com.roomedia.boringmech.crypto.JsNumber.toString(((Number) cyc).doubleValue())
                            : "?";
                    appendLog("이전 실행 중단 지점: " + Js.str(stage) + " 단계 (사이클 " + cycText
                            + ") — 정상 완료 없이 종료된 흔적입니다");
                }
            } catch (Exception ignored) {
            }
        }
        Bridge.NativeSecrets sec = null;
        while (isCurrent(gen)) {
            sec = Bridge.loadNativeSecrets(ctx);
            if (sec != null) {
                break;
            }
            // 뷰어가 아직 연동·미러를 마치지 않았다 — JS restore가 복원
            // 단계마다 beatProgress로 부팅 감시를 미루는 것과 같은 역할.
            env.beatProgress(0);
            if (!sleepInterruptibly(SECRET_WAIT_MS)) {
                return null;
            }
        }
        if (sec == null || !isCurrent(gen)) {
            return null;
        }
        String pkHex = sec.privateKey.trim();
        BigInteger pk;
        String eoa;
        try {
            pk = new BigInteger(pkHex.startsWith("0x") ? pkHex.substring(2) : pkHex, 16);
            eoa = Secp256k1.address(pk);
        } catch (Exception e) {
            appendLog("복원 실패 — 개인키가 올바르지 않습니다");
            return null;
        }
        NativeNet bootNet = new NativeNet(transport, null, null);
        String funder = sec.funder;
        if (funder == null) {
            try {
                funder = retry(() -> bootNet.detectFunder(eoa));
            } catch (Exception e) {
                funder = null;
            }
        }
        if (funder == null) {
            appendLog("복원 실패 — deposit wallet을 찾지 못했습니다 (네트워크 상태 확인 필요)");
            return null;
        }
        env.beatProgress(0);
        Bridge.Creds creds = sec.creds;
        if (creds == null) {
            try {
                creds = retry(() -> deriveCreds(pk, eoa));
            } catch (Exception e) {
                creds = null;
            }
        }
        if (creds == null) {
            appendLog("복원 실패 — API 키를 만들지 못했습니다 (네트워크 상태 확인 필요)");
            return null;
        }
        env.beatProgress(0);

        NativeNet net = new NativeNet(transport, funder, creds);
        net.setProgressBeat(() -> EngineService.recordProgress(ctx));
        ClobWriteClient.TimeoutRunner timeout = ClobWriteClient.executorTimeout(20_000);
        ClobWriteClient client = new ClobWriteClient.Builder()
                .privateKey(pk)
                .funder(funder)
                .creds(new ClobWriteClient.Creds(creds.eoa, creds.key, creds.secret, creds.passphrase))
                .transport(transport)
                .codec(NativeNet.CODEC)
                .timeout(timeout)
                .retryOnError(false)
                .build();
        RedeemSubmitter submitter = new RedeemSubmitter(transport, NativeNet.CODEC,
                pk, eoa, funder, Thread::sleep, System::currentTimeMillis);
        NativeCycle cycle = new NativeCycle(env, net, client, submitter, guard,
                DurableStore.ledgerStore(ctx), funder,
                () -> Bridge.loadRelayerCreds(ctx), timeout);
        try {
            retry(() -> {
                cycle.snapshot();
                return null;
            });
        } catch (Exception e) {
            appendLog("복원 중 스냅샷 실패: " + slice(msg(e), 120) + " — 사이클에서 재시도합니다");
        }
        cycle.checkForUpdate();
        reportWatchdogRecovery();
        return new Session(cycle);
    }

    /** createOrDeriveApiKey 이식 — POST /auth/api-key, 없으면 GET /auth/derive-api-key. */
    private Bridge.Creds deriveCreds(BigInteger pk, String eoa) {
        long ts = System.currentTimeMillis() / 1000;
        String sig = ClobAuth.sign(pk, BigInteger.valueOf(137), eoa, String.valueOf(ts), BigInteger.ZERO);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "boringmech-app/0.3");
        headers.put("POLY_ADDRESS", eoa);
        headers.put("POLY_SIGNATURE", sig);
        headers.put("POLY_TIMESTAMP", String.valueOf(ts));
        headers.put("POLY_NONCE", "0");
        Bridge.Creds creds = null;
        try {
            RawHttp.Raw r = transport.post(NativeNet.CLOB_HOST + "/auth/api-key", headers, "");
            creds = credsFromModel(orderBridge.shapeNativeResult(r), eoa);
        } catch (Exception ignored) {
        }
        if (creds == null) {
            try {
                RawHttp.Raw r = transport.get(NativeNet.CLOB_HOST + "/auth/derive-api-key", headers);
                creds = credsFromModel(orderBridge.shapeNativeResult(r), eoa);
            } catch (Exception ignored) {
            }
        }
        if (creds == null) {
            return null;
        }
        // 파생한 creds는 미러에 직접 남긴다 — 뷰어 boot()는 LS creds가
        // 있을 때만 미러를 덮으므로 네이티브 파생분이 지워지지 않는다.
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("key", creds.key);
        model.put("secret", creds.secret);
        model.put("passphrase", creds.passphrase);
        String enc = Bridge.encryptSecretText(ModelJson.stringify(model));
        if (enc != null) {
            EngineService.setEoa(ctx, eoa);
            EngineService.setCredsCipher(ctx, "enc1:" + enc);
        }
        return creds;
    }

    private static Bridge.Creds credsFromModel(Object model, String eoa) {
        if (!(model instanceof Map)) {
            return null;
        }
        Object key = Js.field(model, "apiKey");
        Object secret = Js.field(model, "secret");
        Object passphrase = Js.field(model, "passphrase");
        if (!Js.truthy(key) || !Js.truthy(secret) || !Js.truthy(passphrase)) {
            return null;
        }
        return new Bridge.Creds(eoa, Js.str(key), Js.str(secret), Js.str(passphrase));
    }

    /** reportWatchdogRecovery 이식. */
    private void reportWatchdogRecovery() {
        SharedPreferences p = ctx.getSharedPreferences("bm_engine", Context.MODE_PRIVATE);
        int n = EngineService.getWatchdogReloads(ctx);
        int seen = p.getInt("wd_seen", 0);
        if (n > seen) {
            appendLog("워치독 복구 — 멈춘 엔진 페이지를 재로드했습니다 (누적 " + n + "회)");
            p.edit().putInt("wd_seen", n).apply();
        }
        int c = EngineService.getWatchdogCycleReloads(ctx);
        int seenCycle = p.getInt("wd_cycle_seen", 0);
        if (c > seenCycle) {
            appendLog("워치독 복구 — 사이클 정체(번호 무전진)로 재로드했습니다 (누적 " + c + "회)");
            p.edit().putInt("wd_cycle_seen", c).apply();
        }
    }

    private interface Attempt<T> {
        T run() throws Exception;
    }

    /** retryAsync 이식 — 3회, 실패 사이 2초. */
    private static <T> T retry(Attempt<T> attempt) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return attempt.run();
            } catch (Exception e) {
                last = e;
                if (i < 2 && !sleepInterruptibly(2_000)) {
                    throw e;
                }
            }
        }
        throw last != null ? last : new Exception("retry 실패");
    }

    private void appendLog(String body) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        DurableStore.appendLog(ctx, "[" + ts + "] " + body);
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return m != null ? m : String.valueOf(e);
    }

    private static String slice(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ── NativeCycle.Env 구현 ────────────────────────────────────────────────

    private final NativeCycle.Env env = new NativeCycle.Env() {
        @Override
        public boolean isRunning(int gen) {
            return NativeEngine.this.isCurrent(gen);
        }

        @Override
        public int generation() {
            return generation;
        }

        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }

        @Override
        public void log(String body) {
            appendLog(body);
        }

        @Override
        public void beatHeartbeat(int cycle) {
            EngineService.recordHeartbeat(ctx, cycle);
        }

        @Override
        public void beatProgress(int cycle) {
            EngineService.recordHeartbeat(ctx, cycle);
            EngineService.recordProgress(ctx);
        }

        @Override
        public void setStage(String stage, int cycle) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", stage);
            m.put("cycle", (double) cycle);
            m.put("ts", (double) System.currentTimeMillis());
            DurableStore.putStage(ctx, ModelJson.stringify(m));
        }

        @Override
        public void putSnapCache(String json) {
            EngineService.putSnapCache(ctx, json);
        }

        @Override
        public String getSnapCache() {
            return EngineService.getSnapCache(ctx);
        }

        @Override
        public void notifyMoney(String title, String body) {
            Bridge.notifyMoney(ctx, title, body);
        }

        @Override
        public void notifyError(String key, String title, String body) {
            Bridge.notifyError(ctx, key, title, body);
        }

        @Override
        public String durableRead(String key) {
            return DurableStore.durableRead(ctx, key);
        }

        @Override
        public void durableWrite(String key, String value) {
            DurableStore.durableWrite(ctx, key, value);
        }

        @Override
        public void requestStop() {
            EngineService.setRunIntent(ctx, false);
            NativeCycle c = currentCycle;
            setStage("stopped", c != null ? c.cycle() : 0);
            appendLog("엔진 정지");
            synchronized (NativeEngine.this) {
                started = false;
                generation++;
            }
            host.onEngineStopRequested();
        }

        @Override
        public long lastSelfReloadMs() {
            return DurableStore.selfReloadMs(ctx);
        }

        @Override
        public void saveSelfReloadMs(long ms) {
            DurableStore.saveSelfReloadMs(ctx, ms);
        }

        @Override
        public String appVersion() {
            try {
                String v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
                if (v == null) {
                    return null;
                }
                return v.startsWith("v") ? v.substring(1) : v;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void saveUpdateInfo(String json) {
            DurableStore.putUpdateInfo(ctx, json);
        }
    };
}
