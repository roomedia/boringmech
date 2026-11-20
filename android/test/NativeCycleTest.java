// NativeCycle JVM 단위 테스트 — 가짜 Env/Net/Transport로 사이클 1회 완주,
// 단계 순서·예외 격리·세대 가드·요약 문구를 검증한다. 실전 네트워크 없음.
// 실행: android/test/run_native_cycle_test.sh
import com.roomedia.boringmech.engine.BoughtReconcile;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.NativeCycle;
import com.roomedia.boringmech.engine.PositionBook;
import com.roomedia.boringmech.engine.PositionsFetcher;
import com.roomedia.boringmech.engine.RedeemPlan;
import com.roomedia.boringmech.engine.StopLoss;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        final List<String> errorNotifications = new ArrayList<>();
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
        boolean throwOnNotify = false;
        @Override public void notifyMoney(String t, String b) {
            if (throwOnNotify) {
                throw new RuntimeException("notify boom");
            }
            notifications.add(t + "|" + b);
        }
        @Override public void notifyError(String k, String t, String b) {
            errorNotifications.add(k + "|" + t + "|" + b);
        }

        boolean errorNotified(String key) {
            for (String n : errorNotifications) {
                if (n.startsWith(key + "|")) return true;
            }
            return false;
        }
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
        boolean failSettled = false;
        NativeCycle.MarketContext marketContext;
        Runnable onFetchMarkets;
        int positionsCalls = 0;
        Object latestRelease;

        @Override
        public List<Object> fetchPositions(String status) throws Exception {
            if (status == null) {
                positionsCalls++;
                return positions;
            }
            if (failSettled) throw new Exception("settled boom");
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

        List<Object> tempEvents = new ArrayList<>();
        int tempEventsCalls = 0;

        @Override
        public List<Object> fetchTempEvents(List<String> eventSlugs) {
            tempEventsCalls++;
            return tempEvents;
        }

        @Override
        public NativeCycle.MarketContext fetchMarketContext(String conditionId) {
            return marketContext;
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
        boolean throwOnPost = false;
        int responseStatus = 200;
        String responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"6\",\"takingAmount\":\"7.79\","
                + "\"transactionHashes\":[\"0xabc\"]}";

        @Override
        public RawHttp.Raw post(String url, Map<String, String> h, String body) {
            posts++;
            if (throwOnPost) {
                throw new RuntimeException("transport boom");
            }
            return new RawHttp.Raw(responseStatus, responseBody, null);
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

    /** 서명 실패 재현용 — 개인키 0은 서명 단계에서 예외를 던진다. */
    private static NativeCycle newCycleZeroKey(FakeEnv env, FakeNet net, RawHttp.Transport t,
                                               FakeLedgerStore store) {
        ClobWriteClient zeroClient = new ClobWriteClient.Builder()
                .privateKey(BigInteger.ZERO)
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
        RedeemSubmitter submitter = new RedeemSubmitter(t, CODEC, PK, EOA, FUNDER,
                ms -> {}, () -> NOW);
        return new NativeCycle(env, net, zeroClient, submitter, new SubmitGuard(), store,
                FUNDER, () -> null, ClobWriteClient.DIRECT_TIMEOUT);
    }

    /** 매수 무대 공용 — buyPath와 같은 후보·북. */
    private static void buyStage(FakeNet net) {
        net.markets = new ArrayList<>(List.of(market("test-buy-market", "[\"0.75\",\"0.25\"]")));
        Map<String, Object> gamma = market("test-buy-market", "[\"0.75\",\"0.25\"]");
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("clobTokenIds", "[\"111\",\"222\"]");
        net.gammaPages.put("test-buy-market", new ArrayList<>(List.of(gamma)));
        net.books.put("111", jmap("{\"tick_size\":\"0.01\",\"neg_risk\":false,\"min_order_size\":\"5\","
                + "\"asks\":[{\"price\":\"0.75\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"0.74\",\"size\":\"100\"}]}"));
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
        check("buy: 체결 알림", env.notifications.stream().anyMatch(ntx ->
                ntx.startsWith("매수 체결|test-buy-market") && ntx.contains("체결가 0.77")
                        && ntx.contains("7.79주") && ntx.contains("$6")));
        check("buy: 오류 알림 없음", env.errorNotifications.isEmpty());
        check("buy: 기매수 기록", env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("buy: 요약 제출 1", env.logged("사이클 요약 · 후보 1 · 검증 1 · 조회 1 · 제출 1"));
    }

    /** 기온 무대 공용 — NOW(2026-11-15T12:00Z)가 진입 창 안에 들게 endDate를 당일 23:59로 둔다. */
    private static void tempStage(FakeNet net) {
        String eventSlug = "highest-temperature-in-seoul-on-november-15-2026";
        String marketSlug = eventSlug + "-7c";
        Map<String, Object> tm = new LinkedHashMap<>();
        tm.put("slug", marketSlug);
        tm.put("question", "Will the highest temperature in Seoul be 7°C on November 15?");
        tm.put("groupItemTitle", "7°C");
        tm.put("active", true);
        tm.put("closed", false);
        tm.put("endDate", "2026-11-15T23:59:00Z");
        tm.put("outcomePrices", "[\"0.03\",\"0.97\"]");
        tm.put("feeType", "weather_fees");
        tm.put("feeSchedule", jmap("{\"rate\":0.05}"));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("slug", eventSlug);
        ev.put("endDate", "2026-11-15T23:59:00Z");
        ev.put("markets", new ArrayList<>(List.of(tm)));
        net.tempEvents = new ArrayList<>(List.of(ev));
        Map<String, Object> gamma = new LinkedHashMap<>(tm);
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("clobTokenIds", "[\"111\",\"222\"]");
        net.gammaPages.put(marketSlug, new ArrayList<>(List.of(gamma)));
        net.books.put("222", jmap("{\"tick_size\":\"0.01\",\"neg_risk\":true,\"min_order_size\":\"5\","
                + "\"asks\":[{\"price\":\"0.97\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"0.96\",\"size\":\"100\"}]}"));
    }

    private static void tempBuyPath() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>();
        tempStage(net);
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String marketSlug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        check("temp: 결과 OK", r == NativeCycle.Result.OK);
        check("temp: 이벤트 조회 1회", net.tempEventsCalls == 1);
        check("temp: 후보 로그", env.logged("기온 후보 1건 (밴드 0.8–0.998)"));
        check("temp: 주문 1회 제출", t.posts == 1);
        check("temp: 매수 로그 수수료율", env.logged("매수 " + marketSlug) && env.logged("수수료율 0.05"));
        check("temp: 매수 로그 전략 태그", env.logged("전략 temp_no"));
        check("temp: 기매수 기록", env.durable.getOrDefault("bm_bought", "").contains(marketSlug));
        check("temp: 기매수 태그", env.durable.getOrDefault("bm_bought", "")
                .contains("\"strategy\":\"temp_no\""));
        check("temp: 요약 제출 1", env.logged("사이클 요약 · 후보 1 · 검증 1 · 조회 0 · 제출 1"));
        // 두 번째 사이클은 15분 캐시라 이벤트를 다시 조회하지 않는다.
        c.runIteration(1);
        check("temp: 캐시로 재조회 없음", net.tempEventsCalls == 1);
    }

    private static void tempFetchAllFailNotCached() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>();
        tempStage(net);
        // 전 도시 조회 실패 — Net 구현이 null을 돌려주는 경우 (JS의 전수
        // 실패 미캐시와 같은 규칙인지 본다).
        net.tempEvents = null;
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp allfail: 결과 OK", r == NativeCycle.Result.OK);
        check("temp allfail: 주문 없음", t.posts == 0);
        c.runIteration(1);
        check("temp allfail: 캐시하지 않아 재조회", net.tempEventsCalls == 2);
    }

    private static void tempOffByDefault() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>();
        tempStage(net);
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp off: 결과 OK", r == NativeCycle.Result.OK);
        check("temp off: 이벤트 조회 없음", net.tempEventsCalls == 0);
        check("temp off: 주문 없음", t.posts == 0);
        check("temp off: 후보 로그 없음", !env.logged("기온 후보"));
    }

    /** 매수 가능한 메인 시장 1건을 무대에 올린다 (gamma·북 포함). */
    private static void mainStage(FakeNet net, String slug, String yesToken, String noToken) {
        Map<String, Object> gamma = market(slug, "[\"0.75\",\"0.25\"]");
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("clobTokenIds", "[\"" + yesToken + "\",\"" + noToken + "\"]");
        net.gammaPages.put(slug, new ArrayList<>(List.of(gamma)));
        net.books.put(yesToken, jmap("{\"tick_size\":\"0.01\",\"neg_risk\":false,\"min_order_size\":\"5\","
                + "\"asks\":[{\"price\":\"0.75\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"0.74\",\"size\":\"100\"}]}"));
        net.markets.add(market(slug, "[\"0.75\",\"0.25\"]"));
    }

    /** 기온 이벤트에 정확한 정수 시장 2건(7°C·8°C)을 올린다. */
    private static void tempStageTwo(FakeNet net) {
        String eventSlug = "highest-temperature-in-seoul-on-november-15-2026";
        List<Object> markets = new ArrayList<>();
        String[][] specs = {
                {"7°C", eventSlug + "-7c", "511", "512"},
                {"8°C", eventSlug + "-8c", "513", "514"},
        };
        for (String[] sp : specs) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("slug", sp[1]);
            tm.put("question", "Will the highest temperature in Seoul be " + sp[0] + " on November 15?");
            tm.put("groupItemTitle", sp[0]);
            tm.put("active", true);
            tm.put("closed", false);
            tm.put("endDate", "2026-11-15T23:59:00Z");
            tm.put("outcomePrices", "[\"0.03\",\"0.97\"]");
            tm.put("feeType", "weather_fees");
            tm.put("feeSchedule", jmap("{\"rate\":0.05}"));
            markets.add(tm);
            Map<String, Object> gamma = new LinkedHashMap<>(tm);
            gamma.put("outcomes", "[\"Yes\",\"No\"]");
            gamma.put("clobTokenIds", "[\"" + sp[2] + "\",\"" + sp[3] + "\"]");
            net.gammaPages.put(sp[1], new ArrayList<>(List.of(gamma)));
            net.books.put(sp[3], jmap("{\"tick_size\":\"0.01\",\"neg_risk\":true,\"min_order_size\":\"5\","
                    + "\"asks\":[{\"price\":\"0.97\",\"size\":\"100\"}],"
                    + "\"bids\":[{\"price\":\"0.96\",\"size\":\"100\"}]}"));
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("slug", eventSlug);
        ev.put("endDate", "2026-11-15T23:59:00Z");
        ev.put("markets", markets);
        net.tempEvents = new ArrayList<>(List.of(ev));
    }

    /** 예약: 슬롯 1개 + 메인·기온 후보가 있으면 기온이 슬롯을 얻는다. */
    private static void tempReserveSlotGoesToTemp() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 12; // 자본 $12 · 건당 $5 → 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStage(net);
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String tempSlug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        check("reserve slot: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve slot: 주문 1회", t.posts == 1);
        check("reserve slot: 기온이 슬롯 획득",
                env.durable.getOrDefault("bm_bought", "").contains(tempSlug));
        check("reserve slot: 메인은 못 삼",
                !env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("reserve slot: 요약", env.logged("사이클 요약 · 후보 2 · 검증 1 · 조회 1 · 제출 1"));
    }

    /** 예약: 토글 ON이지만 기온 후보가 없으면 메인이 전 슬롯을 쓴다. */
    private static void tempReserveMainOnlyUsesAllSlots() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 20; // 자본 $20 · 건당 $5 → 슬롯 2
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "main-market-a", "111", "222");
        mainStage(net, "main-market-b", "333", "444");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("reserve mainonly: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve mainonly: 이벤트 조회는 함", net.tempEventsCalls == 1);
        check("reserve mainonly: 주문 2회", t.posts == 2);
        check("reserve mainonly: 메인 둘 다 매수",
                env.durable.getOrDefault("bm_bought", "").contains("main-market-a")
                        && env.durable.getOrDefault("bm_bought", "").contains("main-market-b"));
    }

    /** 예약은 하한이다: 슬롯 여유가 있으면 기온이 예약 1개를 넘어 더 쓴다. */
    private static void tempReserveTempBeyondOne() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 25; // 자본 $25 · 건당 $5 → 슬롯 3
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageTwo(net);
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String bought = env.durable.getOrDefault("bm_bought", "");
        check("reserve beyond: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve beyond: 주문 3회", t.posts == 3);
        check("reserve beyond: 기온 2건 모두 매수",
                bought.contains("highest-temperature-in-seoul-on-november-15-2026-7c")
                        && bought.contains("highest-temperature-in-seoul-on-november-15-2026-8c"));
        check("reserve beyond: 메인도 매수", bought.contains("test-buy-market"));
    }

    /** 예약: 검증 캡 경계 — 메인 61건이 캡을 다 쓰기 전에 기온이 검증 기회를 얻는다. */
    private static void tempReserveVerifyCap() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            // gamma 페이지가 없어 전부 검증 탈락하는 메인 후보들
            net.markets.add(market("cap-market-" + i, "[\"0.75\",\"0.25\"]"));
        }
        tempStage(net);
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("reserve cap: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve cap: 기온이 검증·체결", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "")
                        .contains("highest-temperature-in-seoul-on-november-15-2026-7c"));
        check("reserve cap: 검증 60에서 멈춤", env.logged("사이클 요약 · 후보 62 · 검증 60"));
    }

    /** 예약 OFF: 슬롯 1개면 기존처럼 메인이 먼저 산다 (기온 스캔 자체를 안 한다). */
    private static void tempReserveOffMainFirst() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 12; // 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStage(net);
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("reserve off: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve off: 이벤트 조회 없음", net.tempEventsCalls == 0);
        check("reserve off: 메인이 슬롯 획득", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
    }

    /** 기온 포지션 손절 시나리오 공통 무대 — No 0.9 진입, 현재가 0.25로 발화 상태. */
    private static OrderTransport tempStopLossStage(FakeEnv env, FakeNet net, String slug) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slug", slug);
        row.put("token_id", "222");
        row.put("outcome", "No");
        row.put("avg_price", 0.9);
        row.put("current_size", 10.0);
        row.put("status", "OPEN");
        row.put("condition_id", "0x" + "ef".repeat(32));
        net.positions = new ArrayList<>(List.of(row));
        Map<String, Object> gamma = new LinkedHashMap<>();
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("outcomePrices", "[\"0.75\",\"0.25\"]");
        net.gammaPages.put(slug, new ArrayList<>(List.of(gamma)));
        net.books.put("222", jmap("{\"tick_size\":\"0.01\",\"neg_risk\":true,"
                + "\"asks\":[{\"price\":\"0.26\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"0.25\",\"size\":\"100\"}]}"));
        net.condBalance = BigInteger.valueOf(10_000_000);
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"10\",\"takingAmount\":\"2.5\","
                + "\"transactionHashes\":[\"0xdef\"]}";
        return t;
    }

    private static void tempStopLossTaggedExempt() {
        String slug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = tempStopLossStage(env, net, slug);
        env.durable.put("bm_bought",
                "[{\"slug\":\"" + slug + "\",\"strategy\":\"temp_no\"}]");
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp-sl tagged: 결과 OK", r == NativeCycle.Result.OK);
        check("temp-sl tagged: 매도 제출 없음", t.posts == 0);
        check("temp-sl tagged: 손절 로그 없음", !env.logged("손절 매도 " + slug));
    }

    private static void tempStopLossUntaggedApplies() {
        String slug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = tempStopLossStage(env, net, slug);
        // 메인 전략이 산 기온 시장 — 기록은 문자열 형태(태그 없음)다
        env.durable.put("bm_bought", "[\"" + slug + "\"]");
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp-sl untagged: 결과 OK", r == NativeCycle.Result.OK);
        check("temp-sl untagged: 손절 매도 실행", env.logged("손절 매도 " + slug));
    }

    private static void tempStopLossNoRecordApplies() {
        String slug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = tempStopLossStage(env, net, slug);
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp-sl norecord: 결과 OK", r == NativeCycle.Result.OK);
        check("temp-sl norecord: 손절 매도 실행", env.logged("손절 매도 " + slug));
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
        check("stoploss: 알림", env.notifications.stream().anyMatch(n -> n.startsWith("손절 체결|")
                && n.contains("test-sl-market") && n.contains("체결가 0.4") && n.contains("실현손익")));
        check("stoploss: 오류 알림 없음", env.errorNotifications.isEmpty());
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
        check("manual: 알림", env.notifications.stream().anyMatch(n -> n.startsWith("수동 매도 체결|")
                && n.contains("test-ms-market") && n.contains("실현손익")));
        check("manual: 상태 기록 filled", env.durable.getOrDefault("bm_manual_sell_status", "")
                .contains("\"outcome\":\"filled\""));
        check("manual: 오류 알림 없음", env.errorNotifications.isEmpty());
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

    /** 수동 매도 집계 가드 — 수동 매도로 청산된 토큰은 CLOSED 행의 최종가가
     *  아니라 실제 매도 실현손익으로 집계되고, 손절 건수에는 안 잡힌다
     *  (v0.3.33 감사: 원장이 쓰기만 되고 읽히지 않던 결함). */
    private static void manualSellSettledAggregation() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"10\",\"takingAmount\":\"5.5\","
                + "\"transactionHashes\":[\"0x123\"]}";
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":" + NOW + "}");
        c.runIteration(1);
        check("manual-book: 매도 실행", t.posts == 1);
        // 다음 사이클 — 포지션은 사라지고, 최종가 1.0(승으로 보일) CLOSED
        // 행만 남는다. 실제 매도는 진입 0.75 → 0.55, 실현 -2.0이다.
        net.positions = new ArrayList<>();
        Map<String, Object> closed = manualRow();
        closed.put("status", "CLOSED");
        closed.put("current_price", 1.0);
        closed.put("current_size", 0.0);
        closed.put("current_value", 0.0);
        closed.put("realized_pnl", 2.5);
        net.closedRows = new ArrayList<>(List.of(closed));
        c.runIteration(1);
        check("manual-book: 정산 1건", env.snapCache != null
                && env.snapCache.contains("\"settledCount\":1"));
        check("manual-book: 실제 매도 기준 패 판정", env.snapCache.contains("\"settledLosses\":1")
                && env.snapCache.contains("\"settledWins\":0"));
        check("manual-book: 실현손익은 매도값 -2", env.snapCache.contains("\"settledRealizedPnl\":-2"));
        check("manual-book: 손절 건수 무오염", env.snapCache.contains("\"settledStopLoss\":0"));
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
        check("manual-stale: 상태 기록 expired (뷰어 표시)", env.durable
                .getOrDefault("bm_manual_sell_status", "").contains("\"outcome\":\"expired\""));
        check("manual-stale: 만료 오류 알림", env.errorNotified(
                com.roomedia.boringmech.write.NotifyPolicy.KEY_MANUAL_EXPIRED));
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

    /** open 판정 통일 가드 — PositionBook 장부 기준과 스냅샷 종목 목록이
     *  어긋나던 v0.3.31 실기기 결함(status 없는 보유 행 누락)의 재발 방지. */
    private static void openFilterConsistency() {
        Map<String, Object> noStatus = manualRow();
        noStatus.remove("status");
        Map<String, Object> closedUnit = manualRow();
        closedUnit.put("status", "CLOSED");
        check("open-filter: status 없는 행은 open", PositionBook.isOpenRow(noStatus));
        check("open-filter: 명시적 OPEN은 open", PositionBook.isOpenRow(manualRow()));
        check("open-filter: CLOSED 행은 제외", !PositionBook.isOpenRow(closedUnit));

        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        @SuppressWarnings("unchecked")
        Map<String, Object> liveRow = (Map<String, Object>) net.positions.get(0);
        liveRow.remove("status");
        Map<String, Object> closedRow = manualRow();
        closedRow.put("slug", "closed-market");
        closedRow.put("title", "Closed market?");
        closedRow.put("token_id", "987654321");
        closedRow.put("status", "CLOSED");
        net.positions.add(closedRow);
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("open-filter: 스냅샷 종목 목록에 status 없는 행 포함", env.snapCache != null
                && env.snapCache.contains("\"title\":\"Test market question?\""));
        check("open-filter: 스냅샷 종목 목록에서 CLOSED 행 제외", env.snapCache != null
                && !env.snapCache.contains("Closed market?"));
        check("open-filter: openCount는 1건", env.snapCache != null
                && env.snapCache.contains("\"openCount\":1"));
    }

    // ── v0.3.32 전면 감사 수리 가드 ─────────────────────────────────────

    /** 감사 F2 — 십진으로 정확히 −0.25인 가격 쌍이 double 오차로 미발화하면 안 된다. */
    private static void stopLossBoundary() {
        check("boundary: 0.30→0.05 발화", "triggered".equals(StopLoss.verdict(0.30, 0.05)));
        check("boundary: 0.35→0.10 발화", "triggered".equals(StopLoss.verdict(0.35, 0.10)));
        check("boundary: 0.41→0.16 발화", "triggered".equals(StopLoss.verdict(0.41, 0.16)));
        check("boundary: 0.57→0.32 발화", "triggered".equals(StopLoss.verdict(0.57, 0.32)));
        check("boundary: 0.70→0.45 발화", "triggered".equals(StopLoss.verdict(0.70, 0.45)));
        check("boundary: −0.24는 보유", "holding".equals(StopLoss.verdict(0.30, 0.06)));
    }

    /** 감사 F1 — 원장 아카이브 회전이 slug를 버리면 재매수 차단이 풀린다. */
    private static void ledgerArchiveKeepsSlug() {
        FakeLedgerStore store = new FakeLedgerStore();
        for (int i = 0; i < 201; i++) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("token_id", "tok" + i);
            rec.put("slug", "slug-" + i);
            rec.put("realized_pnl", -1.0);
            StopLossLedger.recordStopLoss(store, rec);
        }
        List<Object> all = StopLossLedger.records(store);
        check("archive: 전체 201건 유지", all.size() == 201);
        Object first = all.get(0);
        check("archive: 회전된 기록도 slug 보존",
                "slug-0".equals(com.roomedia.boringmech.engine.Js.field(first, "slug")));
    }

    /** 감사 F3 — ingest는 기존 pending 항목에도 done 검사를 적용해야 한다. */
    private static void redeemIngestDropsDone() {
        String doneCid = "0x" + "ab".repeat(32);
        String liveCid = "0x" + "cd".repeat(32);
        List<Object> pendingIn = new ArrayList<>();
        Map<String, Object> doneEntry = new LinkedHashMap<>();
        doneEntry.put("condition_id", doneCid);
        doneEntry.put("token_id", "t1");
        pendingIn.add(doneEntry);
        Map<String, Object> liveEntry = new LinkedHashMap<>();
        liveEntry.put("condition_id", liveCid);
        liveEntry.put("token_id", "t2");
        pendingIn.add(liveEntry);
        Set<Object> done = new HashSet<>();
        done.add(doneCid);
        RedeemPlan.Ingest ing = RedeemPlan.ingest(pendingIn, done, null, null, null);
        check("ingest: 기리딤 건은 pending에서 제외", ing.pending.size() == 1
                && liveCid.equals(com.roomedia.boringmech.engine.Js.field(ing.pending.get(0), "condition_id")));
        check("ingest: 제외는 변경으로 표시", ing.changed);
    }

    /** 감사 F4 — CTF 토큰 ID가 없으면 잔고 게이트 없이 제출하지 않는다. */
    private static void redeemCtfNoGateNoSubmit() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.marketContext = new NativeCycle.MarketContext(false, "v1", null, null);
        String cid = "0x" + "ab".repeat(32);
        env.durable.put("bm_redeem_pending",
                "[{\"condition_id\":\"" + cid + "\",\"status\":\"REDEEMABLE\",\"current_value\":12.5}]");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(),
                () -> new RedeemSubmitter.RelayerCreds("key", EOA));
        c.runIteration(1);
        check("ctf-gate: 제출 없음", t.posts == 0);
        check("ctf-gate: pending 유지", env.durable.getOrDefault("bm_redeem_pending", "").contains(cid));
    }

    /** 감사 F5 — 정산 조회 실패(증거 없음) 사이클에서는 worthless streak가 오르지 않는다. */
    private static void redeemStreakNeedsEvidence() {
        String cid = "0x" + "ab".repeat(32);
        String seed = "[{\"condition_id\":\"" + cid + "\",\"status\":\"REDEEMABLE\","
                + "\"current_value\":0,\"zero_streak\":2}]";
        // 증거 없는 사이클 — streak 동결, 종결 없음
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.failSettled = true;
        env.durable.put("bm_redeem_pending", seed);
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("streak: 증거 없으면 동결", env.durable.getOrDefault("bm_redeem_pending", "")
                .contains("\"zero_streak\":2"));
        check("streak: 증거 없으면 종결 없음", env.durable.get("bm_redeem_closed") == null);
        // 증거 있는 사이클 — streak가 올라 3에서 종결된다 (대조군)
        FakeEnv env2 = new FakeEnv();
        FakeNet net2 = new FakeNet();
        env2.durable.put("bm_redeem_pending", seed);
        NativeCycle c2 = newCycle(env2, net2, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c2.runIteration(1);
        check("streak: 증거 있으면 종결", env2.durable.getOrDefault("bm_redeem_closed", "").contains(cid));
    }

    /** 감사 F6 — has_more인데 커서가 없거나 순환하면 완결로 판정하지 않는다. */
    private static void buySlugsIncompleteCursor() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "TRADE");
        row.put("side", "BUY");
        row.put("slug", "s1");
        Map<String, Object> pageNoCursor = new LinkedHashMap<>();
        pageNoCursor.put("data", new ArrayList<>(List.of(row)));
        Map<String, Object> pg1 = new LinkedHashMap<>();
        pg1.put("has_more", true);
        pageNoCursor.put("pagination", pg1);
        try {
            BoughtReconcile.BuySlugs r = BoughtReconcile.fetchBuySlugs(FUNDER, (f, cur) -> pageNoCursor);
            check("buyslugs: 커서 없으면 미완결", !r.complete);
        } catch (Exception e) {
            check("buyslugs: 커서 없으면 미완결", false);
        }
        Map<String, Object> pageLast = new LinkedHashMap<>();
        pageLast.put("data", new ArrayList<>(List.of(row)));
        Map<String, Object> pg2 = new LinkedHashMap<>();
        pg2.put("has_more", false);
        pageLast.put("pagination", pg2);
        try {
            BoughtReconcile.BuySlugs r = BoughtReconcile.fetchBuySlugs(FUNDER, (f, cur) -> pageLast);
            check("buyslugs: has_more=false면 완결", r.complete && r.slugs.contains("s1"));
        } catch (Exception e) {
            check("buyslugs: has_more=false면 완결", false);
        }
    }

    /** 감사 F8 — has_more인데 next_cursor가 없으면 부분 목록을 확정하지 않는다. */
    private static void positionsFetcherPartial() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("data", new ArrayList<>(List.of(manualRow())));
        Map<String, Object> pg = new LinkedHashMap<>();
        pg.put("has_more", true);
        page.put("pagination", pg);
        boolean threw = false;
        try {
            PositionsFetcher.fetchPositions(FUNDER, null, (f, s, cur) -> page);
        } catch (PositionsFetcher.FetchException e) {
            threw = true;
        } catch (Exception e) {
            threw = false;
        }
        check("positions: has_more+커서 없음은 실패", threw);
    }

    /** 감사 F9 — 구 세대는 수동 매도 요청을 소비하지 않는다 (새 세션이 이어받는다). */
    private static void manualSellStaleGeneration() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        String req = "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":" + NOW + "}";
        env.durable.put("bm_manual_sell_req", req);
        int sold = c.processManualSell(999);
        check("manual-gen: 구 세대 매도 없음", sold == 0 && t.posts == 0);
        check("manual-gen: 요청 보존", req.equals(env.durable.get("bm_manual_sell_req")));
    }

    // ── 알림 가드 (v0.3.34) ─────────────────────────────────────────────
    // 체결 판정 결과에 따라 알림 이벤트가 갈리는지, 오류 알림 중복
    // 억제가 동작하는지를 검증한다. 알림은 판정 이후에만 생성되고,
    // 미체결 같은 확정 결과에는 오류 알림이 없어야 한다.

    private static void notifyBuySignFailure() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        buyStage(net);
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycleZeroKey(env, net, t, new FakeLedgerStore());
        c.runIteration(1);
        check("notify-sign: 제출 없음", t.posts == 0);
        check("notify-sign: 서명 실패 로그", env.logged("주문 서명 실패 test-buy-market"));
        check("notify-sign: 서명 실패 오류 알림", env.errorNotified(
                com.roomedia.boringmech.write.NotifyPolicy.KEY_SIGN_FAIL));
        check("notify-sign: 체결 알림 없음", env.notifications.isEmpty());
        check("notify-sign: 기매수 기록 없음",
                !env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
    }

    private static void notifyBuyUnknown() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        buyStage(net);
        OrderTransport t = new OrderTransport();
        t.throwOnPost = true;
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("notify-unknown: 결과 불명 로그", env.logged("주문 결과 불명 test-buy-market"));
        check("notify-unknown: 결과 불명 알림 (오류로 표기하지 않음)", env.errorNotifications.stream()
                .anyMatch(n -> n.startsWith(com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_UNKNOWN
                        + "|주문 결과 불명|")));
        check("notify-unknown: 중복 방지 기록", env.durable.getOrDefault("bm_bought", "")
                .contains("test-buy-market"));
        check("notify-unknown: 체결 알림 없음", env.notifications.isEmpty());
    }

    private static void notifyBuyRejected() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        buyStage(net);
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":false,\"status\":\"rejected\"}";
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("notify-rejected: 주문 실패 로그", env.logged("주문 실패 test-buy-market"));
        check("notify-rejected: 주문 실패 오류 알림", env.errorNotified(
                com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_FAILED));
        check("notify-rejected: 체결 알림 없음", env.notifications.isEmpty());
    }

    private static void notifyBuyUnfilledQuiet() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        buyStage(net);
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"unmatched\"}";
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("notify-unfilled: 미체결 로그", env.logged("미체결 test-buy-market"));
        check("notify-unfilled: 오류 알림 없음 (확정 결과는 잡음이 아니다)",
                env.errorNotifications.isEmpty());
        check("notify-unfilled: 체결 알림 없음", env.notifications.isEmpty());
    }

    private static void notifyManualSellUnknown() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        OrderTransport t = new OrderTransport();
        t.throwOnPost = true;
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":" + NOW + "}");
        c.runIteration(1);
        check("notify-ms-unknown: 실패 로그 (판정 경유)", env.logged("수동 매도 실패 test-ms-market"));
        check("notify-ms-unknown: 결과 불명 알림", env.errorNotified(
                com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_UNKNOWN));
        check("notify-ms-unknown: 시도 토큰 기록", env.durable
                .getOrDefault("bm_manual_sell_attempted", "").contains("123456789"));
        check("notify-ms-unknown: 상태 기록 failed", env.durable
                .getOrDefault("bm_manual_sell_status", "").contains("\"outcome\":\"failed\""));
        check("notify-ms-unknown: 체결 알림 없음", env.notifications.isEmpty());
    }

    private static void notifyCycleErrorEscalation() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.failValue = true;
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = null;
        for (int i = 0; i < NativeCycle.ERROR_SELF_RESTART_THRESHOLD; i++) {
            r = c.runIteration(1);
        }
        check("notify-cycle: 임계에서 RESTART", r == NativeCycle.Result.RESTART);
        check("notify-cycle: 엔진 오류 알림", env.errorNotified(
                com.roomedia.boringmech.write.NotifyPolicy.KEY_CYCLE_ERRORS));
    }

    private static void notifyPolicyDedupe() {
        java.util.Map<String, Long> last = new java.util.HashMap<>();
        String key = com.roomedia.boringmech.write.NotifyPolicy.KEY_SIGN_FAIL;
        check("policy: 첫 발송 허용", com.roomedia.boringmech.write.NotifyPolicy
                .shouldSendError(last, key, 1_000_000L));
        com.roomedia.boringmech.write.NotifyPolicy.markErrorSent(last, key, 1_000_000L);
        check("policy: 간격 안 재발송 억제", !com.roomedia.boringmech.write.NotifyPolicy
                .shouldSendError(last, key, 1_000_000L
                        + com.roomedia.boringmech.write.NotifyPolicy.ERROR_DEDUPE_MS - 1));
        check("policy: 간격 지나면 허용", com.roomedia.boringmech.write.NotifyPolicy
                .shouldSendError(last, key, 1_000_000L
                        + com.roomedia.boringmech.write.NotifyPolicy.ERROR_DEDUPE_MS));
        check("policy: 다른 사유는 독립", com.roomedia.boringmech.write.NotifyPolicy
                .shouldSendError(last,
                        com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_FAILED, 1_000_001L));
        check("policy: 키 없음은 항상 허용", com.roomedia.boringmech.write.NotifyPolicy
                .shouldSendError(last, null, 1_000_000L));
    }

    // HTTP 5xx+본문은 명시 거부가 아니라 결과 불명이다 — 감사 축 1 발견.
    // 결과 불명이면 중복 방지를 위해 기매수 기록이 남아야 한다.
    private static void notifyBuyHttp5xxIsUnknown() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        buyStage(net);
        OrderTransport t = new OrderTransport();
        t.responseStatus = 502;
        t.responseBody = "Bad Gateway";
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("notify-5xx: 결과 불명 로그", env.logged("주문 결과 불명 test-buy-market"));
        check("notify-5xx: 결과 불명 알림", env.errorNotified(
                com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_UNKNOWN));
        check("notify-5xx: 주문 실패 알림 아님", !env.errorNotified(
                com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_FAILED));
        check("notify-5xx: 중복 방지 기록", env.durable.getOrDefault("bm_bought", "")
                .contains("test-buy-market"));
    }

    // 알림 발송이 예외를 던져도 체결 판정이 뒤집히지 않는지 — 감사 축 2
    // 발견 F-1의 가드. 체결은 원장에 남고 상태는 filled로 기록돼야 한다.
    private static void notifyThrowDoesNotFlipFill() {
        FakeEnv env = new FakeEnv();
        env.throwOnNotify = true;
        FakeNet net = new FakeNet();
        buyStage(net);
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("notify-throw: 매수 체결 로그 유지", env.logged("매수 test-buy-market"));
        check("notify-throw: 기매수 기록 유지",
                env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("notify-throw: 오류 알림으로 뒤집히지 않음",
                !env.errorNotified(com.roomedia.boringmech.write.NotifyPolicy.KEY_ORDER_FAILED));
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

    /** 감사 축1 F1 — 해시 해소 폴링(30초 예산)이 제출 타임아웃(20초) 안에
     *  있으면 폴링 구간이 잘려 체결 건이 결과 불명으로 떨어진다. 폴링은
     *  제출 타임아웃 밖에서 자체 데드라인으로 끝나야 한다. */
    private static void postOrderResolveOutsideTimeout() {
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"matched\",\"makingAmount\":\"6\","
                + "\"takingAmount\":\"7.79\",\"tradeIDs\":[\"trade-1\"]}";
        ClobWriteClient c = new ClobWriteClient.Builder()
                .privateKey(PK)
                .funder(FUNDER)
                .creds(new ClobWriteClient.Creds(EOA, "key", "c2VjcmV0", "pass"))
                .transport(t)
                .codec(CODEC)
                .timeout(ClobWriteClient.executorTimeout(150))
                .versionResolver(force -> 2)
                .marketInfo(new ClobWriteClient.MarketInfoSource() {
                    @Override public void ensureMarketInfoCached(String tokenId) {}
                    @Override public String getTickSize(String tokenId) { return "0.01"; }
                    @Override public boolean getNegRisk(String tokenId) { return false; }
                })
                .tradesSource(id -> {
                    Thread.sleep(600);
                    Map<String, Object> trade = new LinkedHashMap<>();
                    trade.put("id", "trade-1");
                    trade.put("status", "CONFIRMED");
                    trade.put("transaction_hash", "0xhash1");
                    return new ArrayList<>(List.of(trade));
                })
                .build();
        try {
            ClobWriteClient.MarketOrderRequest req =
                    new ClobWriteClient.MarketOrderRequest("111", true, 6, 0.77);
            req.tickSize = "0.01";
            req.negRisk = false;
            Object resp = c.postOrder(c.createMarketOrder(req), "FOK");
            check("resolve-outside-timeout: 해시 해소 완료",
                    ModelJson.stringify(resp).contains("0xhash1"));
        } catch (Exception e) {
            check("resolve-outside-timeout: 해시 해소 완료 (" + e + ")", false);
        }
    }

    /** 감사 축2 F1 — 결과 불명 손절 시도의 slug가 기록되고, 그 slug는
     *  bm_bought에 없어도 재매수 차단 집합에 들어간다. */
    private static void stopLossUnknownRecordsSlug() {
        // 1부: 손절 제출이 전송 단계에서 끊기면 시도 slug가 남는다
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
        t.throwOnPost = true;
        FakeLedgerStore store = new FakeLedgerStore();
        NativeCycle c = newCycle(env, net, t, store, () -> null);
        c.runIteration(1);
        check("attempt-slug: 시도 slug 기록",
                StopLossLedger.attemptedSlugs(store).contains("test-sl-market"));
        check("attempt-slug: 시도 토큰 기록",
                StopLossLedger.attemptedTokens(store).contains("123456789"));

        // 2부: 시도 slug만으로 매수 후보가 차단된다 (bm_bought에 없는 slug)
        FakeEnv env2 = new FakeEnv();
        FakeNet net2 = new FakeNet();
        buyStage(net2);
        FakeLedgerStore store2 = new FakeLedgerStore();
        StopLossLedger.recordAttemptSlug(store2, "test-buy-market");
        OrderTransport t2 = new OrderTransport();
        NativeCycle c2 = newCycle(env2, net2, t2, store2, () -> null);
        c2.runIteration(1);
        check("attempt-slug: 재매수 차단", t2.posts == 0);
    }

    /** 감사 축3 F1 — 수동 매도 원장·시도가 캡을 넘어도 아카이브로 보존돼
     *  장부 집계 입력에서 사라지지 않는다. */
    private static void manualLedgerArchiveSurvives() {
        List<Object> recent = new ArrayList<>();
        List<Object> archive = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("token_id", "mtok" + i);
            rec.put("slug", "m-slug-" + i);
            rec.put("realized_pnl", -1.0);
            ManualSell.CappedLists r = ManualSell.appendRecordWithArchive(archive, recent, rec);
            recent = r.recent;
            archive = r.archive;
        }
        check("manual-archive: 최근 캡 200", recent.size() == ManualSell.LEDGER_CAP);
        check("manual-archive: 아카이브 1건", archive.size() == 1);
        List<Object> combined = ManualSell.combineRecords(archive, recent);
        check("manual-archive: 집계 입력 201건 유지", combined.size() == 201);
        check("manual-archive: 회전된 기록의 slug 보존",
                "m-slug-0".equals(com.roomedia.boringmech.engine.Js.field(combined.get(0), "slug")));

        List<Object> aRecent = new ArrayList<>();
        List<Object> aArchive = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            ManualSell.CappedLists r = ManualSell.appendAttemptedWithArchive(
                    aArchive, aRecent, "atok" + i);
            aRecent = r.recent;
            aArchive = r.archive;
        }
        List<Object> allAttempted = ManualSell.combineAttempted(aArchive, aRecent);
        check("manual-archive: 시도 토큰 201개 유지", allAttempted.size() == 201
                && allAttempted.contains("atok0") && allAttempted.contains("atok200"));
    }

    /** 감사 축3 F2 — worthless 종결이 closed 캡(500)을 넘겨도 밀려난 id가
     *  done 아카이브에 남아, CLOSED 승 행으로 재수집되지 않는다. */
    private static void redeemDoneArchiveSurvives() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 501; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"condition_id\":\"").append(String.format("0x%064x", i + 1))
                    .append("\",\"status\":\"REDEEMABLE\",\"current_value\":0,\"zero_streak\":2}");
        }
        sb.append("]");
        env.durable.put("bm_redeem_pending", sb.toString());
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        String closed = env.durable.getOrDefault("bm_redeem_closed", "");
        String archive = env.durable.getOrDefault("bm_redeem_done_archive", "");
        String firstCid = String.format("0x%064x", 1);
        check("done-archive: closed 캡 500 유지", !closed.contains(firstCid));
        check("done-archive: 밀려난 id 아카이브 보존", archive.contains(firstCid));

        // 아카이브된 id의 CLOSED 승 행이 와도 pending으로 재수집되지 않는다
        FakeEnv env2 = new FakeEnv();
        FakeNet net2 = new FakeNet();
        env2.durable.put("bm_redeem_done_archive", "[\"" + firstCid + "\"]");
        Map<String, Object> winRow = new LinkedHashMap<>();
        winRow.put("condition_id", firstCid);
        winRow.put("token_id", "t1");
        winRow.put("current_price", 1.0);
        winRow.put("entry_cost_usdc", 5.0);
        winRow.put("realized_pnl", 5.0);
        net2.closedRows = new ArrayList<>(List.of(winRow));
        NativeCycle c2 = newCycle(env2, net2, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c2.runIteration(1);
        check("done-archive: 재수집 차단",
                !env2.durable.getOrDefault("bm_redeem_pending", "").contains(firstCid));
    }

    public static void main(String[] args) {
        happyPath();
        buyPath();
        tempBuyPath();
        tempFetchAllFailNotCached();
        tempOffByDefault();
        tempReserveSlotGoesToTemp();
        tempReserveMainOnlyUsesAllSlots();
        tempReserveTempBeyondOne();
        tempReserveVerifyCap();
        tempReserveOffMainFirst();
        tempStopLossTaggedExempt();
        tempStopLossUntaggedApplies();
        tempStopLossNoRecordApplies();
        stopLossPath();
        snapshotFailureFallback();
        marketsFailureIsolation();
        reconcileFailureIsolation();
        redeemNoKey();
        stopDuringCycle();
        updateCheck();
        manualSellPath();
        manualSellSettledAggregation();
        manualSellStale();
        manualSellZeroShares();
        manualSellUnknownToken();
        manualSellLockShared();
        feeRateCache();
        openFilterConsistency();
        stopLossBoundary();
        ledgerArchiveKeepsSlug();
        redeemIngestDropsDone();
        redeemCtfNoGateNoSubmit();
        redeemStreakNeedsEvidence();
        buySlugsIncompleteCursor();
        positionsFetcherPartial();
        manualSellStaleGeneration();
        notifyBuySignFailure();
        notifyBuyUnknown();
        notifyBuyRejected();
        notifyBuyUnfilledQuiet();
        notifyManualSellUnknown();
        notifyCycleErrorEscalation();
        notifyPolicyDedupe();
        notifyThrowDoesNotFlipFill();
        notifyBuyHttp5xxIsUnknown();
        postOrderResolveOutsideTimeout();
        stopLossUnknownRecordsSlug();
        manualLedgerArchiveSurvives();
        redeemDoneArchiveSurvives();
        pureLogic();
        System.out.println("NativeCycleTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
