// NativeCycle JVM 단위 테스트 — 가짜 Env/Net/Transport로 사이클 1회 완주,
// 단계 순서·예외 격리·세대 가드·요약 문구를 검증한다. 실전 네트워크 없음.
// 실행: android/test/run_native_cycle_test.sh
import com.roomedia.boringmech.engine.BoughtReconcile;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.NativeCycle;
import com.roomedia.boringmech.engine.PositionBook;
import com.roomedia.boringmech.write.ClobWriteClient;
import com.roomedia.boringmech.write.JsonCodec;
import com.roomedia.boringmech.write.ManualSell;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.RawHttp;
import com.roomedia.boringmech.write.RedeemSubmitter;
import com.roomedia.boringmech.write.StopLossLedger;
import com.roomedia.boringmech.write.StopLossSeller;
import com.roomedia.boringmech.write.SubmitGuard;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class NativeCycleTest {
    private static int passed = 0;
    private static int failed = 0;

    private static final long NOW = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli();
    private static final String FUNDER = "0x2222222222222222222222222222222222222222";
    private static final String EOA = "0x1111111111111111111111111111111111111111";
    private static final BigInteger PK =
            new BigInteger("1111111111111111111111111111111111111111111111111111111111111111", 16);

    private static final JsonCodec CODEC = new JsonCodec() {
        @Override
        public Object parse(String text) {
            return JsonText.parse(text);
        }

        @Override
        public String stringify(Object model) {
            return ModelJson.stringify(model);
        }
    };

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> jlist(String text) {
        return (List<Object>) JsonText.parse(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jmap(String text) {
        return (Map<String, Object>) JsonText.parse(text);
    }

    // ── 가짜 환경 ───────────────────────────────────────────────────────────

    static final class FakeEnv implements NativeCycle.Env {
        boolean running = true;
        int gen = 1;
        final List<String> logs = new ArrayList<>();
        final List<String> stages = new ArrayList<>();
        final Map<String, String> durable = new HashMap<>();
        final List<String> notifications = new ArrayList<>();
        String snapCache;
        String updateInfo;
        long selfReloadMs = 0;

        @Override public boolean isRunning(int g) { return running && g == gen; }
        @Override public int generation() { return gen; }
        @Override public long nowMs() { return NOW; }
        @Override public void log(String body) { logs.add(body); }
        @Override public void beatHeartbeat(int cycle) {}
        @Override public void beatProgress(int cycle) {}
        @Override public void setStage(String stage, int cycle) { stages.add(stage); }
        @Override public void putSnapCache(String json) { snapCache = json; }
        @Override public void notifyMoney(String t, String b) { notifications.add(t + "|" + b); }
        @Override public String durableRead(String k) { return durable.get(k); }
        @Override public void durableWrite(String k, String v) { durable.put(k, v); }
        @Override public void requestStop() { running = false; gen++; }
        @Override public long lastSelfReloadMs() { return selfReloadMs; }
        @Override public void saveSelfReloadMs(long ms) { selfReloadMs = ms; }
        @Override public String appVersion() { return "0.3.28"; }
        @Override public void saveUpdateInfo(String json) { updateInfo = json; }

        boolean logged(String sub) {
            for (String l : logs) {
                if (l.contains(sub)) return true;
            }
            return false;
        }

        int stageIndex(String s) {
            return stages.indexOf(s);
        }
    }

    static final class FakeNet implements NativeCycle.Net {
        double cash = 500;
        double positionsValue = 100;
        List<Object> positions = new ArrayList<>();
        List<Object> winRows = new ArrayList<>();
        List<Object> lostRows = new ArrayList<>();
        List<Object> closedRows = new ArrayList<>();
        List<Object> markets = new ArrayList<>();
        final Map<String, Object> gammaPages = new HashMap<>();
        final Map<String, Object> books = new HashMap<>();
        BigInteger condBalance = BigInteger.ZERO;
        boolean failValue = false;
        boolean failMarkets = false;
        boolean failBuySlugs = false;
        Runnable onFetchMarkets;
        int positionsCalls = 0;
        Object latestRelease;

        @Override
        public List<Object> fetchPositions(String status) {
            if (status == null) {
                positionsCalls++;
                return positions;
            }
            switch (status) {
                case "REDEEMABLE": return winRows;
                case "REDEEMABLE_LOST": return lostRows;
                default: return closedRows;
            }
        }

        @Override
        public double fetchPositionsValue() throws Exception {
            if (failValue) throw new Exception("value boom");
            return positionsValue;
        }

        @Override
        public BoughtReconcile.BuySlugs fetchBuySlugs() throws Exception {
            if (failBuySlugs) throw new Exception("activity boom");
            return BoughtReconcile.fetchBuySlugs(FUNDER, (f, cursor) -> new ArrayList<>());
        }

        @Override
        public List<Object> fetchMarkets() throws Exception {
            if (onFetchMarkets != null) onFetchMarkets.run();
            if (failMarkets) throw new Exception("markets boom");
            return markets;
        }

        @Override
        public Object gammaPageBySlug(String slug) {
            return gammaPages.get(slug);
        }

        @Override
        public NativeCycle.MarketContext fetchMarketContext(String conditionId) {
            return null;
        }

        @Override
        public double clobCash() {
            return cash;
        }

        @Override
        public Object orderBook(String tokenId) {
            return books.get(tokenId);
        }

        @Override
        public double tickSize(String tokenId) {
            return 0.01;
        }

        @Override
        public BigInteger conditionalBalance(String tokenId) {
            return condBalance;
        }

        @Override
        public BigInteger erc1155Balance(String token, String owner, String tokenId) {
            return BigInteger.ZERO;
        }

        @Override
        public Object fetchLatestReleaseJson() {
            return latestRelease;
        }
    }

    static final class FakeLedgerStore implements StopLossLedger.Store {
        final Map<String, Object> map = new HashMap<>();

        @Override public Object read(String key) { return map.get(key); }
        @Override public void write(String key, Object model) { map.put(key, model); }
    }

    static final class OrderTransport implements RawHttp.Transport {
        int posts = 0;
        String responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"6\",\"takingAmount\":\"7.79\","
                + "\"transactionHashes\":[\"0xabc\"]}";

        @Override
        public RawHttp.Raw post(String url, Map<String, String> h, String body) {
            posts++;
            return new RawHttp.Raw(200, responseBody, null);
        }

        @Override
        public RawHttp.Raw get(String url, Map<String, String> h) {
            throw new AssertionError("GET 금지: " + url);
        }
    }

    private static ClobWriteClient client(RawHttp.Transport t) {
        return new ClobWriteClient.Builder()
                .privateKey(PK)
                .funder(FUNDER)
                .creds(new ClobWriteClient.Creds(EOA, "key", "c2VjcmV0", "pass"))
                .transport(t)
                .codec(CODEC)
                .timeout(ClobWriteClient.DIRECT_TIMEOUT)
                .versionResolver(force -> 2)
                .marketInfo(new ClobWriteClient.MarketInfoSource() {
                    @Override public void ensureMarketInfoCached(String tokenId) {}
                    @Override public String getTickSize(String tokenId) { return "0.01"; }
                    @Override public boolean getNegRisk(String tokenId) { return false; }
                })
                .tradesSource(id -> new ArrayList<>())
                .build();
    }

    private static NativeCycle newCycle(FakeEnv env, FakeNet net, RawHttp.Transport t,
                                        FakeLedgerStore store,
                                        Supplier<RedeemSubmitter.RelayerCreds> relayer) {
        RedeemSubmitter submitter = new RedeemSubmitter(t, CODEC, PK, EOA, FUNDER,
                ms -> {}, () -> NOW);
        return new NativeCycle(env, net, client(t), submitter, new SubmitGuard(), store,
                FUNDER, relayer, ClobWriteClient.DIRECT_TIMEOUT);
    }

    private static Map<String, Object> market(String slug, String prices) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", slug);
        m.put("question", "Will it rain tomorrow?");
        m.put("active", true);
        m.put("closed", false);
        m.put("endDate", "2026-11-25T00:00:00Z");
        m.put("volume", 20000.0);
        m.put("outcomePrices", prices);
        return m;
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────

    private static void happyPath() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>(List.of(market("band-out-market", "[\"0.5\",\"0.5\"]")));
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("happy: 결과 OK", r == NativeCycle.Result.OK);
        PositionBook.Sizing s = PositionBook.sizing(500, 600, 0);
        check("happy: 사이클 로그", env.logged("사이클 1 · 잔고 $500 · 평가 $600 · 건당 $"
                + com.roomedia.boringmech.crypto.JsNumber.toString(s.bet)
                + " · 슬롯 " + (int) s.slots + " · 보유 0"));
        check("happy: 단계 순서", env.stages.equals(List.of("snapshot", "stoploss", "redeem", "scan", "idle")));
        check("happy: 요약 로그", env.logged("사이클 요약 · 후보 0 · 검증 0 · 조회 1 (밴드밖 1) · 제출 0"));
        check("happy: 스냅샷 캐시", env.snapCache != null && env.snapCache.contains("\"cash\":500"));
        check("happy: 정산 기준선 저장", env.durable.containsKey("bm_settled_base"));
    }

    private static void buyPath() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>(List.of(market("test-buy-market", "[\"0.75\",\"0.25\"]")));
        Map<String, Object> gamma = market("test-buy-market", "[\"0.75\",\"0.25\"]");
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("clobTokenIds", "[\"111\",\"222\"]");
        net.gammaPages.put("test-buy-market", new ArrayList<>(List.of(gamma)));
        net.books.put("111", jmap("{\"tick_size\":\"0.01\",\"neg_risk\":false,\"min_order_size\":\"5\","
                + "\"asks\":[{\"price\":\"0.75\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"0.74\",\"size\":\"100\"}]}"));
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("buy: 결과 OK", r == NativeCycle.Result.OK);
        check("buy: 주문 1회 제출", t.posts == 1);
        check("buy: 매수 로그", env.logged("매수 test-buy-market · $6 @ 0.77 체결가 0.77 · 상태 matched"));
        check("buy: 체결 알림", env.notifications.contains("매수 체결|건당 $6 · 체결가 0.77"));
        check("buy: 기매수 기록", env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("buy: 요약 제출 1", env.logged("사이클 요약 · 후보 1 · 검증 1 · 조회 1 · 제출 1"));
    }

    private static void stopLossPath() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slug", "test-sl-market");
        row.put("token_id", "123456789");
        row.put("outcome", "Yes");
        row.put("avg_price", 0.75);
        row.put("current_size", 10.0);
        row.put("status", "OPEN");
        row.put("condition_id", "0x" + "cd".repeat(32));
        net.positions = new ArrayList<>(List.of(row));
        Map<String, Object> gamma = new LinkedHashMap<>();
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("outcomePrices", "[\"0.4\",\"0.6\"]");
        net.gammaPages.put("test-sl-market", new ArrayList<>(List.of(gamma)));
        net.books.put("123456789", jmap("{\"tick_size\":\"0.01\",\"neg_risk\":false,"
                + "\"asks\":[{\"price\":\"0.41\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"0.4\",\"size\":\"100\"}]}"));
        net.condBalance = BigInteger.valueOf(10_000_000);
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"10\",\"takingAmount\":\"4\","
                + "\"transactionHashes\":[\"0xdef\"]}";
        FakeLedgerStore store = new FakeLedgerStore();
        NativeCycle c = newCycle(env, net, t, store, () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("stoploss: 결과 OK", r == NativeCycle.Result.OK);
        check("stoploss: 매도 로그", env.logged("손절 매도 test-sl-market · 진입 0.75 → 매도 0.4"));
        check("stoploss: 원장 기록", !store.map.isEmpty());
        check("stoploss: 매도 후 재스냅샷", net.positionsCalls >= 2);
        check("stoploss: 알림", env.notifications.stream().anyMatch(n -> n.startsWith("손절 실행|")));
    }

    private static void snapshotFailureFallback() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.failValue = true;
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("fallback: 결과 FAILED", r == NativeCycle.Result.FAILED);
        check("fallback: 손절 단독 실행 로그",
                env.logged("스냅샷 실패로 손절만 단독 실행함 — 원래 오류: value boom"));
        check("fallback: 사이클 오류 로그", env.logged("사이클 오류: value boom"));
        check("fallback: scan 미실행", !env.stages.contains("scan"));
    }

    private static void marketsFailureIsolation() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.failMarkets = true;
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("isolation: 결과 FAILED", r == NativeCycle.Result.FAILED);
        check("isolation: 앞 단계는 실행됨",
                env.stageIndex("snapshot") >= 0
                        && env.stageIndex("snapshot") < env.stageIndex("stoploss")
                        && env.stageIndex("stoploss") < env.stageIndex("redeem")
                        && env.stageIndex("redeem") < env.stageIndex("scan"));
        check("isolation: 사이클 오류 로그", env.logged("사이클 오류: markets boom"));
    }

    private static void reconcileFailureIsolation() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.failBuySlugs = true;
        env.durable.put("bm_bought", "[\"old-slug\"]");
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("reconcile: 결과 OK (격리)", r == NativeCycle.Result.OK);
        check("reconcile: 실패 로그 1회",
                env.logged("기매수 정화 실패 — 매수 이력 조회 오류: activity boom (다음 사이클 재시도)"));
        check("reconcile: 스캔까지 완주", env.logged("사이클 요약"));
    }

    private static void redeemNoKey() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        env.durable.put("bm_redeem_pending",
                "[{\"condition_id\":\"0x" + "ab".repeat(32)
                        + "\",\"status\":\"REDEEMABLE\",\"current_value\":12.5}]");
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("redeem: 결과 OK", r == NativeCycle.Result.OK);
        check("redeem: 키 없음 보류 로그", env.logged(
                "리딤 대기 1건 (약 $12.5) — Relayer API 키가 없어 제출을 보류합니다 (키 등록 후 자동 실행)"));
    }

    private static void stopDuringCycle() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>(List.of(market("test-buy-market", "[\"0.75\",\"0.25\"]")));
        net.onFetchMarkets = env::requestStop;
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("stop: 결과 ABORTED", r == NativeCycle.Result.ABORTED);
        check("stop: 제출 없음", t.posts == 0);
        check("stop: 매수 로그 없음", !env.logged("매수 test-buy-market"));
    }

    private static void updateCheck() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.latestRelease = jmap("{\"tag_name\":\"v0.3.29\",\"assets\":[{"
                + "\"name\":\"boringmech-v0.3.29.apk\","
                + "\"browser_download_url\":\"https://example.com/boringmech.apk\","
                + "\"digest\":\"sha256:abc\"}]}");
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        for (int i = 0; i < 30; i++) {
            c.runIteration(1);
        }
        check("update: 정보 저장", env.updateInfo != null && env.updateInfo.contains("v0.3.29"));
        long count = env.logs.stream()
                .filter(l -> l.contains("새 버전 v0.3.29 사용 가능 — 업데이트 버튼으로 설치하세요"))
                .count();
        check("update: 안내 로그 1회", count == 1);
    }

    private static Map<String, Object> manualRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slug", "test-ms-market");
        row.put("token_id", "123456789");
        row.put("title", "Test market question?");
        row.put("outcome", "Yes");
        row.put("avg_price", 0.75);
        row.put("current_price", 0.55);
        row.put("current_size", 10.0);
        row.put("current_value", 5.5);
        row.put("status", "OPEN");
        row.put("condition_id", "0x" + "cd".repeat(32));
        return row;
    }

    /** 수동 매도 공용 무대 — Gamma 0.55는 손절 미발화(holding) 구간이다. */
    private static void manualStage(FakeNet net) {
        net.positions = new ArrayList<>(List.of(manualRow()));
        Map<String, Object> gamma = new LinkedHashMap<>();
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("outcomePrices", "[\"0.55\",\"0.45\"]");
        net.gammaPages.put("test-ms-market", new ArrayList<>(List.of(gamma)));
        net.books.put("123456789", jmap("{\"tick_size\":\"0.01\",\"neg_risk\":false,"
                + "\"asks\":[{\"price\":\"0.56\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"0.55\",\"size\":\"100\"}]}"));
        net.condBalance = BigInteger.valueOf(10_000_000);
    }

    private static void manualSellPath() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"10\",\"takingAmount\":\"5.5\","
                + "\"transactionHashes\":[\"0x123\"]}";
        FakeLedgerStore store = new FakeLedgerStore();
        NativeCycle c = newCycle(env, net, t, store, () -> null);
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":" + NOW + "}");
        NativeCycle.Result r = c.runIteration(1);
        check("manual: 결과 OK", r == NativeCycle.Result.OK);
        check("manual: 주문 1회 제출", t.posts == 1);
        check("manual: 매도 로그", env.logged("수동 매도 test-ms-market · 진입 0.75 → 매도 0.55"));
        check("manual: 알림", env.notifications.stream().anyMatch(n -> n.startsWith("수동 매도 실행|")));
        String ledger = env.durable.getOrDefault("bm_manual_sell", "");
        check("manual: 별도 장부 기록", ledger.contains("\"kind\":\"manual\"")
                && ledger.contains("test-ms-market"));
        check("manual: 손절 원장 무오염", store.map.isEmpty());
        check("manual: 요청 소비됨", "".equals(env.durable.get("bm_manual_sell_req")));
        check("manual: 스냅샷 종목 목록", env.snapCache != null
                && env.snapCache.contains("\"positions\"")
                && env.snapCache.contains("\"title\":\"Test market question?\""));
        // 같은 요청이 남아 있지 않으므로 다음 사이클에 재제출되지 않는다 (이중 제출 방지)
        c.runIteration(1);
        check("manual: 이중 제출 없음", t.posts == 1);
    }

    private static void manualSellStale() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":"
                        + (NOW - ManualSell.MAX_REQUEST_AGE_MS - 1) + "}");
        c.runIteration(1);
        check("manual-stale: 제출 없음", t.posts == 0);
        check("manual-stale: 만료 로그", env.logged("수동 매도 요청 만료"));
        check("manual-stale: 요청 소비됨", "".equals(env.durable.get("bm_manual_sell_req")));
    }

    private static void manualSellZeroShares() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) net.positions.get(0);
        row.put("current_size", 0.0);
        net.condBalance = BigInteger.ZERO;
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":" + NOW + "}");
        c.runIteration(1);
        check("manual-zero: 제출 없음", t.posts == 0);
        check("manual-zero: 수량 부족 로그", env.logged("수동 매도 건너뜀"));
    }

    private static void manualSellUnknownToken() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"999\",\"slug\":\"not-held\",\"ts\":" + NOW + "}");
        c.runIteration(1);
        check("manual-unknown: 제출 없음", t.posts == 0);
        check("manual-unknown: 미보유 로그", env.logged("보유 중인 종목이 아닙니다"));
    }

    static final class FakeManualRecorder implements StopLossSeller.ManualSellRecorder {
        final List<Map<String, Object>> records = new ArrayList<>();
        final List<String> attempts = new ArrayList<>();

        @Override public void record(Map<String, Object> rec) { records.add(rec); }
        @Override public void recordAttempt(String tokenId) { attempts.add(tokenId); }
    }

    private static void manualSellLockShared() {
        List<String> logs = new ArrayList<>();
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"10\",\"takingAmount\":\"5.5\","
                + "\"transactionHashes\":[\"0x123\"]}";
        SubmitGuard guard = new SubmitGuard();
        FakeManualRecorder recorder = new FakeManualRecorder();
        FakeLedgerStore store = new FakeLedgerStore();
        StopLossSeller seller = new StopLossSeller(client(t),
                tokenId -> BigInteger.valueOf(10_000_000),
                new StopLossSeller.OrderBookSource() {
                    @Override
                    public StopLossSeller.OrderBookInfo getOrderBook(String tokenId) {
                        return new StopLossSeller.OrderBookInfo("0.01", false);
                    }

                    @Override
                    public String getTickSize(String tokenId) {
                        return "0.01";
                    }
                },
                guard, store, logs::add, null, ClobWriteClient.DIRECT_TIMEOUT, () -> NOW,
                recorder);
        Map<String, Object> row = manualRow();
        // 손절이 같은 slug 잠금을 쥐고 있으면 수동 매도는 제출하지 않는다 (락 공유)
        check("lock: 선행 잠금 획득", guard.tryAcquireSubmitLock("test-ms-market"));
        boolean blocked = seller.executeManualSell(row, 0.55);
        check("lock: 잠금 중 수동 매도 차단", !blocked && t.posts == 0);
        check("lock: 잠금 로그", logs.stream().anyMatch(l -> l.contains("수동 매도 제출 잠금 중")));
        guard.releaseSubmitLock("test-ms-market");
        boolean ok = seller.executeManualSell(row, 0.55);
        check("lock: 해제 후 매도 성공", ok && t.posts == 1);
        check("lock: 별도 장부 기록", recorder.records.size() == 1
                && "manual".equals(recorder.records.get(0).get("kind")));
        check("lock: 손절 원장 무오염", store.map.isEmpty());
    }

    private static void feeRateCache() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        @SuppressWarnings("unchecked")
        Map<String, Object> gamma =
                (Map<String, Object>) ((List<Object>) net.gammaPages.get("test-ms-market")).get(0);
        gamma.put("feeType", "politics_fees");
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("fee-cache: 첫 스냅샷은 미상", env.snapCache != null
                && env.snapCache.contains("\"fee_rate\":null"));
        c.runIteration(1);
        check("fee-cache: 손절 조회 후 요율 반영", env.snapCache != null
                && env.snapCache.contains("\"fee_rate\":0.04"));
    }

    private static void pureLogic() {
        check("reload: 9회는 부족", !NativeCycle.shouldSelfReload(9, 0, NOW));
        check("reload: 10회+쿨다운 경과", NativeCycle.shouldSelfReload(10, 0, NOW));
        check("reload: 쿨다운 중", !NativeCycle.shouldSelfReload(10, NOW - 1000, NOW));
        Map<String, Integer> scan = new LinkedHashMap<>();
        scan.put("band_out", 3);
        scan.put("sports", 1);
        scan.put("total", 10);
        Map<String, Integer> rejects = new LinkedHashMap<>();
        rejects.put("no_asks", 2);
        String s = NativeCycle.summarizeCycle(3, 2, rejects, 1, 1, 0, scan);
        check("summary: JS 형식", s.equals(
                "사이클 요약 · 후보 3 · 검증 2 · 조회 10 (밴드밖 3·스포츠 1) · 탈락 매도호가없음 2 · 제출 1 · 미체결 1"));
        // 수동 매도 수수료 매핑 — 명세 요율표 (크립토 0.07·스포츠/기타 0.05·정치/금융/테크 0.04·지정학 0)
        check("fee: crypto", ManualSell.feeRateForFeeType("crypto_fees") == 0.07);
        check("fee: sports", ManualSell.feeRateForFeeType("sports_fees_v2") == 0.05);
        check("fee: politics", ManualSell.feeRateForFeeType("politics_fees") == 0.04);
        check("fee: finance", ManualSell.feeRateForFeeType("finance_fees") == 0.04);
        check("fee: tech", ManualSell.feeRateForFeeType("tech_fees") == 0.04);
        check("fee: geopolitics", ManualSell.feeRateForFeeType("geopolitics_fees") == 0.0);
        check("fee: 기타", ManualSell.feeRateForFeeType("economy_fees") == 0.05);
        check("fee: 판정 불가", ManualSell.feeRateForFeeType(null) == null
                && ManualSell.feeRateForFeeType("") == null);
        // 추정식 대조 — 실계정 실측: 15.625주 @0.64 크립토 매수 수수료 $0.252와 공식이 일치한다
        check("fee: 추정식 실측 대조", Math.abs(ManualSell.estimateFee(15.625, 0.64, 0.07) - 0.252) < 0.001);
        check("fee: 추정식 0.915", Math.abs(ManualSell.estimateFee(15.625, 0.915, 0.07) - 0.0851) < 0.001);
        ManualSell.Request rq = ManualSell.parseRequest(
                jmap("{\"token_id\":\"123\",\"slug\":\"s\",\"ts\":1000}"));
        check("req: 파싱", rq != null && "123".equals(rq.tokenId) && rq.tsMs == 1000);
        check("req: 토큰 없음 거부", ManualSell.parseRequest(
                jmap("{\"slug\":\"s\",\"ts\":1000}")) == null);
        check("req: ts 없음 거부", ManualSell.parseRequest(
                jmap("{\"token_id\":\"123\"}")) == null);
        check("req: 신선도 경계", !ManualSell.isStale(rq, 1000 + ManualSell.MAX_REQUEST_AGE_MS)
                && ManualSell.isStale(rq, 1000 + ManualSell.MAX_REQUEST_AGE_MS + 1));
    }

    public static void main(String[] args) {
        happyPath();
        buyPath();
        stopLossPath();
        snapshotFailureFallback();
        marketsFailureIsolation();
        reconcileFailureIsolation();
        redeemNoKey();
        stopDuringCycle();
        updateCheck();
        manualSellPath();
        manualSellStale();
        manualSellZeroShares();
        manualSellUnknownToken();
        manualSellLockShared();
        feeRateCache();
        pureLogic();
        System.out.println("NativeCycleTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
