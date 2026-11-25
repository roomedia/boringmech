// NativeCycle JVM 단위 테스트 — 가짜 Env/Net/Transport로 사이클 1회 완주,
// 단계 순서·예외 격리·세대 가드·요약 문구를 검증한다. 실전 네트워크 없음.
// 실행: android/test/run_native_cycle_test.sh
import com.roomedia.boringmech.engine.BoughtReconcile;
import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.NativeCycle;
import com.roomedia.boringmech.engine.PositionBook;
import com.roomedia.boringmech.engine.PositionsFetcher;
import com.roomedia.boringmech.engine.RedeemPlan;
import com.roomedia.boringmech.engine.StopLoss;
import com.roomedia.boringmech.engine.StopLossFallback;
import com.roomedia.boringmech.engine.TempNo;
import com.roomedia.boringmech.engine.TradeSettings;
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
        long nowMsOverride = 0;

        @Override public boolean isRunning(int g) { return running && g == gen; }
        @Override public int generation() { return gen; }
        @Override public long nowMs() { return nowMsOverride != 0 ? nowMsOverride : NOW; }
        @Override public void log(String body) { logs.add(body); }
        @Override public void beatHeartbeat(int cycle) {}
        @Override public void beatProgress(int cycle) {}
        @Override public void setStage(String stage, int cycle) { stages.add(stage); }
        @Override public void putSnapCache(String json) { snapCache = json; }
        @Override public String getSnapCache() { return snapCache; }
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
        boolean failPositions = false;
        int marketsCalls = 0;
        boolean failMarkets = false;
        boolean failBuySlugs = false;
        boolean failSettled = false;
        NativeCycle.MarketContext marketContext;
        Runnable onFetchMarkets;
        int positionsCalls = 0;
        Object latestRelease;

        @Override
        public List<Object> fetchPositions(String status) throws Exception {
            if (failPositions) throw new Exception("positions boom");
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
            marketsCalls++;
            if (onFetchMarkets != null) onFetchMarkets.run();
            if (failMarkets) throw new Exception("markets boom");
            return markets;
        }

        @Override
        public Object gammaPageBySlug(String slug) {
            return gammaPages.get(slug);
        }

        List<Object> tempEvents = new ArrayList<>();
        int tempEventsFailures = 0;
        int tempEventsCalls = 0;
        List<String> lastTempSlugs;

        @Override
        public NativeCycle.TempEventsResult fetchTempEvents(List<String> eventSlugs) {
            tempEventsCalls++;
            lastTempSlugs = eventSlugs;
            if (tempEvents == null) {
                return null;
            }
            return new NativeCycle.TempEventsResult(tempEvents, tempEventsFailures);
        }

        @Override
        public NativeCycle.MarketContext fetchMarketContext(String conditionId) {
            return marketContext;
        }

        @Override
        public double clobCash() {
            return cash;
        }

        /** 호출 횟수 — 첫 호출(선정) 뒤 다른 북을 돌려주는 재확인 테스트용. */
        int orderBookCalls = 0;
        final Map<String, Object> booksAfterFirst = new HashMap<>();

        @Override
        public Object orderBook(String tokenId) {
            orderBookCalls++;
            if (orderBookCalls > 1 && booksAfterFirst.containsKey(tokenId)) {
                return booksAfterFirst.get(tokenId);
            }
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

        BigInteger redeemBalance;

        @Override
        public BigInteger erc1155Balance(String token, String owner, String tokenId) {
            return redeemBalance != null ? redeemBalance : BigInteger.ZERO;
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
        // 건당 금액 $6 — 가짜 체결 응답(making 6 · taking 7.79)과 맞춘다
        env.durable.put("bm_settings", "{\"betUsd\":6}");
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

    /**
     * 캐시 자정 경계: 기준 UTC 날짜가 바뀌면 15분이 안 지났어도 전날
     * 캐시를 버리고 새 날짜 집합(오늘+내일)으로 다시 조회한다. 같은
     * 날짜 집합 안에서는 종전대로 캐시를 쓴다.
     */
    private static void tempCacheExpiresAtUtcMidnight() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.markets = new ArrayList<>();
        tempStage(net);
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        // 자정 5분 전 (11-15) — 첫 조회는 11-15·11-16 집합.
        env.nowMsOverride = Instant.parse("2026-11-15T23:55:00Z").toEpochMilli();
        c.runIteration(1);
        check("temp midnight: 첫 조회 1회", net.tempEventsCalls == 1);
        check("temp midnight: 첫 집합은 15·16일", net.lastTempSlugs != null
                && net.lastTempSlugs.contains("highest-temperature-in-seoul-on-november-15-2026")
                && net.lastTempSlugs.contains("highest-temperature-in-seoul-on-november-16-2026"));
        // 자정 5분 뒤 (11-16) — 캐시 나이 10분 < 15분이지만 날짜가
        // 바뀌었으므로 새 집합(16·17일)으로 다시 조회한다.
        env.nowMsOverride = Instant.parse("2026-11-16T00:05:00Z").toEpochMilli();
        c.runIteration(1);
        check("temp midnight: 자정 경계에서 재조회", net.tempEventsCalls == 2);
        check("temp midnight: 새 집합은 16·17일", net.lastTempSlugs != null
                && net.lastTempSlugs.contains("highest-temperature-in-seoul-on-november-16-2026")
                && net.lastTempSlugs.contains("highest-temperature-in-seoul-on-november-17-2026")
                && !net.lastTempSlugs.contains("highest-temperature-in-seoul-on-november-15-2026"));
        // 같은 날짜 집합 안 (00:10) — 캐시를 그대로 쓴다.
        env.nowMsOverride = Instant.parse("2026-11-16T00:10:00Z").toEpochMilli();
        c.runIteration(1);
        check("temp midnight: 같은 집합에서는 캐시 유지", net.tempEventsCalls == 2);
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

    /** 예약 슬롯 2개 설정 — 슬롯 2개가 전부 기온 몫이 돼 메인이 못 산다. */
    private static void tempReserveTwoSlots() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 20; // 자본 $20 · 건당 $5 → 슬롯 2
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageTwo(net);
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempNoReserveSlots\":2}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String bought = env.durable.getOrDefault("bm_bought", "");
        check("reserve2: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve2: 주문 2회", t.posts == 2);
        check("reserve2: 기온 2건 모두 매수",
                bought.contains("highest-temperature-in-seoul-on-november-15-2026-7c")
                        && bought.contains("highest-temperature-in-seoul-on-november-15-2026-8c"));
        check("reserve2: 메인은 못 삼", !bought.contains("test-buy-market"));
    }

    /** 차선: sizing 슬롯 0이어도 예약이 있으면 기온이 기온 전용 금액($3)으로 진입한다.
     *  메인 금액은 기본 $5 그대로라 슬롯 0인 것과 함께 금액 독립성을 본다. */
    private static void tempLaneBuysAtUserAmount() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 10;            // 평가 $30 · 현금 하한 $9 · 여유액 $1 → sizing 슬롯 0
        net.positionsValue = 20;
        net.markets = new ArrayList<>();
        tempStage(net);
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":3}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String tempSlug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        check("lane $3: 결과 OK", r == NativeCycle.Result.OK);
        check("lane $3: 메인 건당 $5 · 슬롯 0", env.logged("건당 $5 · 슬롯 0"));
        check("lane $3: 기온 매수는 $3", env.logged("매수 " + tempSlug + " · $3 @"));
        check("lane $3: 차선 진입 로그", env.logged("기온 예약 차선으로 진입을 시도합니다"));
        check("lane $3: 주문 1회", t.posts == 1);
        check("lane $3: 기온 매수 + 차선 표기",
                env.durable.getOrDefault("bm_bought", "").contains(tempSlug)
                        && env.logged("· 예약 차선"));
    }

    /** 차선: 예약 0개면 슬롯 0에서 기존대로 스캔 자체를 건너뛴다. */
    private static void tempLaneOffWhenReserveZero() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 10;
        net.positionsValue = 20;
        net.markets = new ArrayList<>();
        tempStage(net);
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":3,\"tempNoReserveSlots\":0}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("lane off: 결과 OK", r == NativeCycle.Result.OK);
        check("lane off: 슬롯 없음 반환", env.logged("슬롯 없음 — 이번 사이클 매수 없음"));
        check("lane off: 이벤트 조회 없음", net.tempEventsCalls == 0);
        check("lane off: 주문 없음", t.posts == 0);
    }

    /** 차선: 남은 현금이 건당 + 예비금에 못 미치면 차선도 서지 않는다. */
    private static void tempLaneNeedsCashAboveReserve() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 5.5;           // 기온 건당 $3 + 예비금 $2.87 = $5.87에 못 미친다
        net.positionsValue = 20;
        net.markets = new ArrayList<>();
        tempStage(net);
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":3}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("lane cash: 결과 OK", r == NativeCycle.Result.OK);
        check("lane cash: 슬롯 없음 반환", env.logged("슬롯 없음 — 이번 사이클 매수 없음"));
        check("lane cash: 주문 없음", t.posts == 0);
    }

    /** 차선: 예약 1개면 슬롯 0에서 기온 2건 중 1건만 차선으로 산다. */
    private static void tempLaneLimitedToReserveCount() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 10;
        net.positionsValue = 20;
        net.markets = new ArrayList<>();
        tempStageTwo(net);
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":3}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String bought = env.durable.getOrDefault("bm_bought", "");
        check("lane count: 결과 OK", r == NativeCycle.Result.OK);
        check("lane count: 주문 1회", t.posts == 1);
        check("lane count: 첫 기온만 매수",
                bought.contains("highest-temperature-in-seoul-on-november-15-2026-7c")
                        && !bought.contains("highest-temperature-in-seoul-on-november-15-2026-8c"));
    }

    /** 금액 가드: 기온 금액이 메인 금액보다 크면 일반 슬롯 지출이 현금
     *  하한을 침범하지 않는다 — 첫 건($50)만 체결되고 둘째 건은 하한
     *  가드에서 걸린다. 슬롯(건수)은 메인 금액 기준이라 14개나 남는다. */
    private static void tempBetAboveMainRespectsCashFloor() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 100;           // 평가 $100 · 현금 하한 $30 · 여유액 $70 · 메인 건당 $5 → 슬롯 14
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        tempStageTwo(net);
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":50,\"tempNoReserveSlots\":0}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String bought = env.durable.getOrDefault("bm_bought", "");
        check("floor guard: 결과 OK", r == NativeCycle.Result.OK);
        check("floor guard: 주문 1회만", t.posts == 1);
        check("floor guard: 기온 1건만 매수",
                bought.contains("highest-temperature-in-seoul-on-november-15-2026-7c")
                        ^ bought.contains("highest-temperature-in-seoul-on-november-15-2026-8c"));
        check("floor guard: 현금하한 탈락 집계", env.logged("현금하한 1"));
    }

    /** 금액 가드: 기온 금액 0이면 일반 슬롯에서도 0원 제출을 시도하지 않는다. */
    private static void tempBetZeroSkipsTempBuys() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 25;            // 메인 건당 $5 → 슬롯 3
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        tempStage(net);
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":0,\"tempNoReserveSlots\":0}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("bet zero: 결과 OK", r == NativeCycle.Result.OK);
        check("bet zero: 주문 없음", t.posts == 0);
        check("bet zero: 금액없음 탈락 집계", env.logged("금액없음 1"));
        check("bet zero: 매수 기록 없음",
                !env.durable.getOrDefault("bm_bought", "").contains("highest-temperature"));
    }

    /** 최소 수량은 메인에서 기존대로다 — 건당 $3으로 낮춰도 메인 후보는 탈락한다. */
    private static void mainStillRejectsBelowMinSizeAtLowBet() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 12;            // 건당 금액 $3 설정
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        env.durable.put("bm_settings", "{\"betUsd\":3}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("main min: 결과 OK", r == NativeCycle.Result.OK);
        check("main min: 주문 없음", t.posts == 0);
        check("main min: 최소수량미달 탈락", env.logged("최소수량미달"));
    }

    /** 기온 이벤트 무대 — endDate만 바꿔 올린다 (후보 검증 무대는 걸지 않는다). */
    private static void tempStageEndOnly(FakeNet net, String endDate) {
        String eventSlug = "highest-temperature-in-seoul-on-november-15-2026";
        Map<String, Object> tm = new LinkedHashMap<>();
        tm.put("slug", eventSlug + "-7c");
        tm.put("question", "Will the highest temperature in Seoul be 7°C on November 15?");
        tm.put("groupItemTitle", "7°C");
        tm.put("active", true);
        tm.put("closed", false);
        tm.put("endDate", endDate);
        tm.put("outcomePrices", "[\"0.03\",\"0.97\"]");
        tm.put("feeType", "weather_fees");
        tm.put("feeSchedule", jmap("{\"rate\":0.05}"));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("slug", eventSlug);
        ev.put("endDate", endDate);
        ev.put("markets", new ArrayList<>(List.of(tm)));
        net.tempEvents = new ArrayList<>(List.of(ev));
    }

    /**
     * 창 대기 예약: 창이 열리기 전(03:00Z, 창 06:00~12:00)에는 기온
     * 후보가 없어도 오늘 창이 열릴 이벤트가 있으면 메인이 마지막
     * 슬롯을 먹지 않는다 — 미룬 메인을 같은 사이클에 다시 태우지도
     * 않는다.
     */
    private static void tempReserveUpcomingWindowHoldsMain() {
        FakeEnv env = new FakeEnv();
        env.nowMsOverride = Instant.parse("2026-11-15T03:00:00Z").toEpochMilli();
        FakeNet net = new FakeNet();
        net.cash = 12; // 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnly(net, "2026-11-15T12:00:00Z");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("reserve upcoming: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve upcoming: 주문 없음", t.posts == 0);
        check("reserve upcoming: 메인은 못 삼",
                !env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("reserve upcoming: 예약 로그", env.logged("기온 예약 — 진입 창이 남은 이벤트가 있어"));
    }

    /** 창 대기 예약: 창이 지난 이벤트만 있으면 연기하지 않고 메인이 산다. */
    private static void tempReservePastWindowNoHold() {
        FakeEnv env = new FakeEnv(); // NOW = 12:00Z, 이벤트 창은 06:00~11:00로 지났다
        FakeNet net = new FakeNet();
        // 슬롯 1이면서 매수 후 현금이 현금 가드 하한(기본 $7.87) 위에
        // 남는 금액 — 이 테스트는 홀드 해제만 본다 (가드는 별도 테스트).
        net.cash = 14;
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnly(net, "2026-11-15T11:00:00Z");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("reserve past: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve past: 메인이 슬롯 획득", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("reserve past: 예약 로그 없음", !env.logged("기온 예약 — 진입 창이 남은"));
    }

    /** 창 대기 예약: 예약 슬롯 0이면 창이 남아 있어도 메인이 산다. */
    private static void tempReserveZeroUpcomingNoHold() {
        FakeEnv env = new FakeEnv();
        env.nowMsOverride = Instant.parse("2026-11-15T03:00:00Z").toEpochMilli();
        FakeNet net = new FakeNet();
        net.cash = 12; // 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnly(net, "2026-11-15T12:00:00Z");
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempNoReserveSlots\":0}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("reserve zero upcoming: 결과 OK", r == NativeCycle.Result.OK);
        check("reserve zero upcoming: 메인이 슬롯 획득", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
    }

    /** v0.3.39 감사 D (사이클) — 창이 남아 있어도 이벤트 markets가 전부
     *  기매수면 후보가 될 수 없어 예약을 걸지 않고 메인이 산다. */
    private static void tempUpcomingAllBoughtNoHold() {
        FakeEnv env = new FakeEnv();
        env.nowMsOverride = Instant.parse("2026-11-15T03:00:00Z").toEpochMilli();
        FakeNet net = new FakeNet();
        // 슬롯 1, 매수 후 현금이 현금 가드 하한 위에 남는 금액 (위와 같은 이유)
        net.cash = 14;
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnly(net, "2026-11-15T12:00:00Z");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        env.durable.put("bm_bought",
                "[\"highest-temperature-in-seoul-on-november-15-2026-7c\"]");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("auditD cycle: 결과 OK", r == NativeCycle.Result.OK);
        check("auditD cycle: 메인이 슬롯 획득", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("auditD cycle: 예약 로그 없음", !env.logged("기온 예약 — 진입 창이 남은"));
    }

    /** v0.3.39 감사 E — 기온 이벤트 조회 전수 실패(null)면 예약을 유지해
     *  메인이 마지막 슬롯을 먹지 않고, 실패 로그가 남는다. */
    private static void tempFetchAllFailKeepsReserve() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 12; // 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        net.tempEvents = null; // Net 구현의 전수 실패 신호
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("auditE: 결과 OK", r == NativeCycle.Result.OK);
        check("auditE: 전수 실패 로그", env.logged("기온 이벤트 조회 전수 실패"));
        check("auditE: 예약 유지로 주문 없음", t.posts == 0);
        check("auditE: 메인은 못 삼",
                !env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
    }

    /** v0.3.39 감사 E (대조군) — 조회 성공·이벤트 없음(빈 목록)이면
     *  예약 없이 메인이 산다. */
    private static void tempFetchEmptyNoReserve() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        // 슬롯 1, 매수 후 현금이 현금 가드 하한 위에 남는 금액 (위와 같은 이유)
        net.cash = 14;
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        net.tempEvents = new ArrayList<>(); // 성공적 무이벤트
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("auditE empty: 결과 OK", r == NativeCycle.Result.OK);
        check("auditE empty: 전수 실패 로그 없음", !env.logged("전수 실패"));
        check("auditE empty: 메인이 슬롯 획득", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
    }

    /** 기온 이벤트 무대 — 이벤트 슬러그와 endDate를 지정한다 (후보 검증 무대는 걸지 않는다). */
    private static void tempStageEndOnlyDated(FakeNet net, String eventSlug, String endDate) {
        Map<String, Object> tm = new LinkedHashMap<>();
        tm.put("slug", eventSlug + "-7c");
        tm.put("question", "Will the highest temperature in Seoul be 7°C?");
        tm.put("groupItemTitle", "7°C");
        tm.put("active", true);
        tm.put("closed", false);
        tm.put("endDate", endDate);
        tm.put("outcomePrices", "[\"0.03\",\"0.97\"]");
        tm.put("feeType", "weather_fees");
        tm.put("feeSchedule", jmap("{\"rate\":0.05}"));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("slug", eventSlug);
        ev.put("endDate", endDate);
        ev.put("markets", new ArrayList<>(List.of(tm)));
        net.tempEvents = new ArrayList<>(List.of(ev));
    }

    /**
     * 홀드: 전날 저녁(20:00Z)에는 오늘 창이 지났어도 내일 이벤트가
     * 조회 범위에 들어와 홀드가 걸리고, 메인이 마지막 슬롯을 먹지
     * 않는다. 조회 슬러그가 내일 날짜까지 넓어졌는지도 함께 본다.
     */
    private static void tempHoldTomorrowEventEveningBefore() {
        FakeEnv env = new FakeEnv();
        env.nowMsOverride = Instant.parse("2026-11-15T20:00:00Z").toEpochMilli();
        FakeNet net = new FakeNet();
        net.cash = 12; // 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnlyDated(net,
                "highest-temperature-in-seoul-on-november-16-2026", "2026-11-16T12:00:00Z");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("hold tomorrow: 결과 OK", r == NativeCycle.Result.OK);
        check("hold tomorrow: 조회 슬러그 80개 (오늘+내일)", net.lastTempSlugs != null
                && net.lastTempSlugs.size() == 80);
        check("hold tomorrow: 내일 슬러그 포함", net.lastTempSlugs != null
                && net.lastTempSlugs.contains("highest-temperature-in-seoul-on-november-16-2026"));
        check("hold tomorrow: 주문 없음", t.posts == 0);
        check("hold tomorrow: 메인은 못 삼",
                !env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("hold tomorrow: 홀드 로그", env.logged("기온 예약 — 진입 창이 남은 이벤트가 있어"));
    }

    /**
     * 홀드: 슬롯이 현금이 정한 값(자본 $20 → 슬롯 2, 건수 상한 70은
     * 닿지 않는다)이어도 메인은 예약 1개만큼 덜 쓴다 — 건당 베팅액만큼의
     * 현금이 그대로 남는다. 기온 후보는 없고 내일 이벤트만으로 걸리는
     * 홀드다.
     */
    private static void tempHoldAppliesToCashSlots() {
        FakeEnv env = new FakeEnv(); // NOW = 2026-11-15T12:00Z
        FakeNet net = new FakeNet();
        net.cash = 20; // 현금 기준 슬롯 2
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "main-market-a", "111", "222");
        mainStage(net, "main-market-b", "333", "444");
        tempStageEndOnlyDated(net,
                "highest-temperature-in-seoul-on-november-16-2026", "2026-11-16T12:00:00Z");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String bought = env.durable.getOrDefault("bm_bought", "");
        check("hold cash: 결과 OK", r == NativeCycle.Result.OK);
        check("hold cash: 주문 1회만", t.posts == 1);
        check("hold cash: 메인 1건만 매수",
                bought.contains("main-market-a") != bought.contains("main-market-b"));
        check("hold cash: 홀드 로그", env.logged("기온 예약 — 진입 창이 남은 이벤트가 있어"));
    }

    /**
     * 홀드와 연기의 차이: 기온 후보가 검증에서 탈락해 슬롯을 못 써도
     * 차단된 메인은 같은 사이클에 다시 타지 않는다 — 빈 슬롯은 빈
     * 채로 남는다 (연기 방식이면 두 번째 패스에서 메인이 먹었다).
     */
    private static void tempHoldMainNotRetriedAfterTempFails() {
        FakeEnv env = new FakeEnv(); // NOW = 2026-11-15T12:00Z, 기온 창 안
        FakeNet net = new FakeNet();
        net.cash = 20; // 슬롯 2
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "main-market-a", "111", "222");
        mainStage(net, "main-market-b", "333", "444");
        // 이벤트·시장은 올리되 gamma 무대를 걸지 않아 기온 후보가 검증 탈락한다
        tempStageEndOnly(net, "2026-11-15T23:59:00Z");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        String bought = env.durable.getOrDefault("bm_bought", "");
        check("hold noretry: 결과 OK", r == NativeCycle.Result.OK);
        check("hold noretry: 기온 후보 로그", env.logged("기온 후보 1건"));
        check("hold noretry: 주문 1회만", t.posts == 1);
        check("hold noretry: 메인 1건만 매수",
                bought.contains("main-market-a") != bought.contains("main-market-b"));
        check("hold noretry: 기온은 못 삼", !bought.contains("highest-temperature-in-seoul"));
        check("hold noretry: 홀드 로그", env.logged("기온 예약 — 진입 창이 남은 이벤트가 있어"));
    }

    /**
     * 2026-11-26 진단 재현: 전날 저녁, 조회가 부분 실패해 내일 이벤트가
     * 목록에서 빠졌으면(전수 실패는 아님) 종전에는 홀드가 조용히 풀려
     * 메인이 마지막 슬롯을 먹었다. 부분 실패도 안전 방향 — 홀드를
     * 유지하고 사유 로그가 남아야 한다.
     */
    private static void tempFetchPartialFailKeepsReserve() {
        FakeEnv env = new FakeEnv();
        env.nowMsOverride = Instant.parse("2026-11-15T20:00:00Z").toEpochMilli();
        FakeNet net = new FakeNet();
        net.cash = 12; // 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        // 내일 이벤트는 조회 실패로 목록에 없다 — 오늘분만 성공한 부분 실패.
        net.tempEvents = new ArrayList<>();
        net.tempEventsFailures = 40;
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("partial: 결과 OK", r == NativeCycle.Result.OK);
        check("partial: 일부 실패 로그", env.logged("기온 이벤트 조회 일부 실패 (40건)"));
        check("partial: 예약 유지로 주문 없음", t.posts == 0);
        check("partial: 메인은 못 삼",
                !env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("partial: 홀드 로그", env.logged("기온 예약 — 조회 일부 실패로 메인"));
    }

    /**
     * 홀드 해제 전이 로그: 홀드가 걸린 사이클 다음에 완전한 조회로
     * 창이 남은 이벤트가 없음이 확인돼 홀드가 풀리면, 해제 로그가
     * 남고 그 사이클부터 메인이 산다.
     */
    private static void tempHoldReleaseLogged() {
        FakeEnv env = new FakeEnv();
        env.nowMsOverride = Instant.parse("2026-11-15T20:00:00Z").toEpochMilli();
        FakeNet net = new FakeNet();
        // 슬롯 1, 2차 매수 후 현금이 현금 가드 하한 위에 남는 금액 (위와 같은 이유)
        net.cash = 14;
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnlyDated(net,
                "highest-temperature-in-seoul-on-november-16-2026", "2026-11-16T12:00:00Z");
        env.durable.put("bm_settings", "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r1 = c.runIteration(1);
        check("release: 1차 결과 OK", r1 == NativeCycle.Result.OK);
        check("release: 1차 홀드로 주문 없음", t.posts == 0);
        check("release: 1차 해제 로그 없음", !env.logged("기온 예약 해제"));
        // 캐시(15분)를 넘긴 뒤 — 완전한 조회에서 이벤트가 사라졌다.
        env.nowMsOverride = Instant.parse("2026-11-15T20:20:00Z").toEpochMilli();
        net.tempEvents = new ArrayList<>();
        NativeCycle.Result r2 = c.runIteration(1);
        check("release: 2차 결과 OK", r2 == NativeCycle.Result.OK);
        check("release: 해제 로그", env.logged("기온 예약 해제 — 진입 창이 남은 이벤트가 없어"));
        check("release: 2차 메인이 슬롯 획득", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
    }

    /**
     * 현금 가드 (2026-11-26) — 홀드가 풀린 상태(창이 지난 이벤트만
     * 있음)에서도 예약 현금은 메인이 먼저 쓰지 못한다. 01:27 사고
     * 재현값: 기온 건당 $6, 예약 1 → 하한 $8.87, 현금 $8.67에서 메인
     * 건당 $5를 사면 매수 후 $3.67로 하한 아래라 차단된다.
     */
    private static void tempCashGuardBlocksMainWhenHoldOff() {
        FakeEnv env = new FakeEnv(); // NOW = 12:00Z, 이벤트 창은 지났다 (홀드 해제 상태)
        FakeNet net = new FakeNet();
        net.cash = 8.67; // 슬롯 1
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnly(net, "2026-11-15T11:00:00Z");
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":6}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("cashguard cycle: 결과 OK", r == NativeCycle.Result.OK);
        check("cashguard cycle: 가드로 주문 없음", t.posts == 0);
        check("cashguard cycle: 메인은 못 삼",
                !env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("cashguard cycle: 가드 로그 (하한·현금 포함)",
                env.logged("기온 예약 현금 가드 — 메인 매수를 막습니다 (보호 하한 $8.87 · 현금 $8.67"));
        check("cashguard cycle: 사유 집계", env.logged("기온예약현금가드 1"));
    }

    /** 현금 가드 경계 — 매수 후 현금이 하한과 정확히 같으면 메인이 산다. */
    private static void tempCashGuardBoundaryAllowsMain() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.cash = 13.87; // 13.87 − 5 = 8.87 = 하한
        net.positionsValue = 0;
        net.markets = new ArrayList<>();
        mainStage(net, "test-buy-market", "111", "222");
        tempStageEndOnly(net, "2026-11-15T11:00:00Z");
        env.durable.put("bm_settings",
                "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempBetUsd\":6}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("cashguard boundary: 결과 OK", r == NativeCycle.Result.OK);
        check("cashguard boundary: 메인이 슬롯 획득", t.posts == 1
                && env.durable.getOrDefault("bm_bought", "").contains("test-buy-market"));
        check("cashguard boundary: 가드 로그 없음", !env.logged("기온 예약 현금 가드"));
    }

    /** 손절 판정 경계 — 폭 파라미터와 결정 구간 설정값 (JS book 테스트와 같은 케이스). */
    private static void stopLossVerdictDrops() {
        check("verdict temp boundary", "triggered".equals(StopLoss.verdict(0.90, 0.75, 0.15)));
        check("verdict temp above line", "holding".equals(StopLoss.verdict(0.90, 0.76, 0.15)));
        check("verdict temp fires main holds",
                "triggered".equals(StopLoss.verdict(0.90, 0.70, 0.15))
                        && "holding".equals(StopLoss.verdict(0.90, 0.70)));
        check("verdict decided wins", "decided".equals(StopLoss.verdict(0.90, 0.97, 0.15)));
        check("verdict custom decided",
                "decided".equals(StopLoss.verdict(0.90, 0.80, 0.15, 0.80, 0.03))
                        && "triggered".equals(StopLoss.verdict(0.90, 0.74, 0.15, 0.97, 0.03)));
    }

    /** 매매 설정 해석 — 기본값은 기존 상수와 같고, 범위 밖·어긋난 쌍은 기본값으로 되돌린다. */
    private static void tradeSettingsParse() {
        TradeSettings d = TradeSettings.parse(null);
        check("cfg defaults", d.bandLo == 0.6 && d.bandHi == 0.9
                && d.priorityBandLo == 0.7 && d.priorityBandHi == 0.8
                && d.volumeMin == 10000 && d.endWindowDays == 45
                && d.spreadMax == 0.02 && d.askDepthMinUsd == 10
                && d.slippageCap == 0.015 && d.snapshotMatchTol == 0.03
                && d.askAboveSignal == 0.05
                && d.betUsd == 5 && d.cashFloorPct == 0.3
                && d.reserveUsd == 2.87 && d.maxPositions == 70 && d.verifyCap == 60
                && d.stopDrop == 0.25 && d.tempStopDrop == 0.15
                && d.decidedHigh == 0.97 && d.decidedLow == 0.03);
        check("cfg bad json defaults", TradeSettings.parse("not json").bandLo == 0.6);
        TradeSettings c = TradeSettings.parse("{\"bandLo\":0.5,\"betUsd\":7,\"maxPositions\":10}");
        check("cfg custom kept", c.bandLo == 0.5 && c.betUsd == 7 && c.maxPositions == 10
                && c.bandHi == 0.9);
        // 비율 키는 더 이상 읽지 않는다 — 저장된 옛 키가 있어도 금액 기본값이다
        check("cfg legacy ratio keys ignored",
                TradeSettings.parse("{\"betPct\":0.02,\"betMinUsd\":3}").betUsd == 5);
        // 기온 건당 금액 — 메인 금액과 독립, 기본 $5
        check("cfg temp bet", TempNo.parseSettings(null).betUsd == 5
                && TempNo.parseSettings("{\"tempBetUsd\":3}").betUsd == 3
                && TempNo.parseSettings("{\"tempBetUsd\":0}").betUsd == 0
                && TempNo.parseSettings("{\"tempBetUsd\":-1}").betUsd == 5
                && TempNo.parseSettings("{\"betUsd\":9}").betUsd == 5);
        check("cfg band pair reset", TradeSettings.parse("{\"bandLo\":0.95,\"bandHi\":0.9}").bandLo == 0.6
                && TradeSettings.parse("{\"bandLo\":0.95,\"bandHi\":0.9}").bandHi == 0.9);
        check("cfg decided pair reset",
                TradeSettings.parse("{\"decidedLow\":0.98,\"decidedHigh\":0.97}").decidedHigh == 0.97);
        check("cfg int fallbacks", TradeSettings.parse("{\"maxPositions\":0}").maxPositions == 70
                && TradeSettings.parse("{\"maxPositions\":3.5}").maxPositions == 70
                && TradeSettings.parse("{\"verifyCap\":\"30\"}").verifyCap == 30);
        check("cfg drop fallbacks", TradeSettings.parse("{\"stopDrop\":0}").stopDrop == 0.25
                && TradeSettings.parse("{\"tempStopDrop\":0.2}").tempStopDrop == 0.2);
        check("cfg verifyCap matches orchestrator", d.verifyCap == NativeCycle.VERIFY_CAP);
        // 기본값 리터럴이 출처 상수와 어긋나지 않는지 전수 대조한다
        check("cfg defaults match source constants",
                d.bandLo == com.roomedia.boringmech.engine.ScanFilter.BAND_LO
                        && d.bandHi == com.roomedia.boringmech.engine.ScanFilter.BAND_HI
                        && d.priorityBandLo == com.roomedia.boringmech.engine.ScanFilter.PRIORITY_BAND_LO
                        && d.priorityBandHi == com.roomedia.boringmech.engine.ScanFilter.PRIORITY_BAND_HI
                        && d.volumeMin == com.roomedia.boringmech.engine.ScanFilter.VOLUME_MIN
                        && d.endWindowDays == com.roomedia.boringmech.engine.ScanFilter.END_WINDOW_DAYS
                        && d.spreadMax == com.roomedia.boringmech.engine.CandidateVerifier.SPREAD_MAX
                        && d.askDepthMinUsd == com.roomedia.boringmech.engine.CandidateVerifier.ASK_DEPTH_MIN_USD
                        && d.slippageCap == com.roomedia.boringmech.engine.CandidateVerifier.SLIPPAGE_CAP
                        && d.snapshotMatchTol == com.roomedia.boringmech.engine.CandidateVerifier.SNAPSHOT_MATCH_TOL
                        && d.askAboveSignal == com.roomedia.boringmech.engine.CandidateVerifier.ASK_ABOVE_SIGNAL
                        && d.betUsd == PositionBook.BET_USD
                        && d.cashFloorPct == PositionBook.CASH_FLOOR_PCT
                        && d.reserveUsd == PositionBook.RESERVE_USD
                        && d.maxPositions == (int) PositionBook.MAX_POSITIONS
                        && d.stopDrop == StopLoss.STOP_DROP
                        && d.tempStopDrop == StopLoss.TEMP_STOP_DROP
                        && d.decidedHigh == StopLoss.DECIDED_HIGH
                        && d.decidedLow == StopLoss.DECIDED_LOW);
        // 커스텀 사이징이 실제로 반영되는지
        check("cfg sizing bet", PositionBook.sizing(1000, 1000, 0,
                TradeSettings.parse("{\"betUsd\":7}")).bet == 7);
        // 금액 방식 — 자본이 변해도 건당 금액은 설정값 그대로다
        check("cfg sizing bet ignores equity", PositionBook.sizing(1000, 100, 0,
                TradeSettings.parse("{\"betUsd\":7}")).bet == 7
                && PositionBook.sizing(1000, 100000, 0,
                TradeSettings.parse("{\"betUsd\":7}")).bet == 7
                && PositionBook.sizing(1000, 100, 0).bet == PositionBook.sizing(1000, 100000, 0).bet);
        check("cfg sizing slots", PositionBook.sizing(100000, 100000, 0,
                TradeSettings.parse("{\"maxPositions\":2}")).slots == 2);
    }

    /** 기온 포지션 손절 시나리오 공통 무대 — No 0.9 진입, 현재가 0.25로 발화 상태. */
    private static OrderTransport tempStopLossStage(FakeEnv env, FakeNet net, String slug) {
        return tempStopLossStage(env, net, slug, "0.25");
    }

    /** noPrice는 현재 No 가격 — 진입 0.9 기준 기온 폭(-0.15) 발화선은 0.75, 메인 폭(-0.25)은 0.65. */
    private static OrderTransport tempStopLossStage(FakeEnv env, FakeNet net, String slug,
                                                    String noPrice) {
        double no = Double.parseDouble(noPrice);
        String yesPrice = String.valueOf(1 - no);
        String askPrice = String.valueOf(no + 0.01);
        double taking = 10 * no;
        String takingStr = taking == Math.floor(taking)
                ? String.valueOf((long) taking) : String.valueOf(taking);
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
        gamma.put("outcomePrices", "[\"" + yesPrice + "\",\"" + noPrice + "\"]");
        net.gammaPages.put(slug, new ArrayList<>(List.of(gamma)));
        net.books.put("222", jmap("{\"tick_size\":\"0.01\",\"neg_risk\":true,"
                + "\"asks\":[{\"price\":\"" + askPrice + "\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"" + noPrice + "\",\"size\":\"100\"}]}"));
        net.condBalance = BigInteger.valueOf(10_000_000);
        OrderTransport t = new OrderTransport();
        t.responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"10\",\"takingAmount\":\"" + takingStr + "\","
                + "\"transactionHashes\":[\"0xdef\"]}";
        return t;
    }

    /** 기온 태그 건도 손절한다 — 폭만 기온 전용(-0.15)이다. */
    private static void tempStopLossTaggedApplies() {
        String slug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = tempStopLossStage(env, net, slug);
        env.durable.put("bm_bought",
                "[{\"slug\":\"" + slug + "\",\"strategy\":\"temp_no\"}]");
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp-sl tagged: 결과 OK", r == NativeCycle.Result.OK);
        check("temp-sl tagged: 손절 매도 실행", env.logged("손절 매도 " + slug));
    }

    /** 폭 판별 — 현재가 0.70은 기온 폭(-0.15)에서는 발화, 메인 폭(-0.25)에서는 유지. */
    private static void tempStopLossTaggedMidApplies() {
        String slug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = tempStopLossStage(env, net, slug, "0.70");
        env.durable.put("bm_bought",
                "[{\"slug\":\"" + slug + "\",\"strategy\":\"temp_no\"}]");
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp-sl tagged mid: 결과 OK", r == NativeCycle.Result.OK);
        check("temp-sl tagged mid: 손절 매도 실행", env.logged("손절 매도 " + slug));
    }

    private static void tempStopLossUntaggedMidHolds() {
        String slug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = tempStopLossStage(env, net, slug, "0.70");
        env.durable.put("bm_bought", "[\"" + slug + "\"]");
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("temp-sl untagged mid: 결과 OK", r == NativeCycle.Result.OK);
        check("temp-sl untagged mid: 매도 제출 없음", t.posts == 0);
        check("temp-sl untagged mid: 손절 로그 없음", !env.logged("손절 매도 " + slug));
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

    // ── 손절 폴백 (마지막 스냅샷 장부 기준) ─────────────────────────────

    /** 폴백 행 구성 — 무효 행·처분 기록·무효 집합을 걸러내고 판정 입력을 보존한다. */
    private static void fallbackRowsBuilder() {
        Object cache = JsonText.parse("{\"positions\":["
                + "{\"slug\":\"ok\",\"token_id\":\"1\",\"outcome\":\"No\",\"avg_price\":0.9,\"current_size\":10},"
                + "{\"slug\":\"zero-entry\",\"token_id\":\"2\",\"outcome\":\"No\",\"avg_price\":0,\"current_size\":10},"
                + "{\"slug\":\"zero-size\",\"token_id\":\"3\",\"outcome\":\"No\",\"avg_price\":0.9,\"current_size\":0},"
                + "{\"slug\":\"no-token\",\"outcome\":\"No\",\"avg_price\":0.9,\"current_size\":10},"
                + "{\"slug\":\"sold\",\"token_id\":\"4\",\"outcome\":\"No\",\"avg_price\":0.9,\"current_size\":10},"
                + "{\"slug\":\"dead\",\"token_id\":\"5\",\"outcome\":\"No\",\"avg_price\":0.9,\"current_size\":10}"
                + "]}");
        List<Map<String, Object>> rows = StopLossFallback.rowsFromCache(
                cache, Set.of("4"), Set.of("5"));
        check("fallback-rows: 유효 행만 1건", rows.size() == 1);
        check("fallback-rows: 판정 입력 보존",
                rows.size() == 1 && "ok".equals(rows.get(0).get("slug"))
                        && Double.compare((Double) rows.get(0).get("avg_price"), 0.9) == 0
                        && Double.compare((Double) rows.get(0).get("current_size"), 10.0) == 0);
        check("fallback-rows: fallback 표기",
                rows.size() == 1 && Boolean.TRUE.equals(rows.get(0).get("fallback")));
        // 임계 동일성 — 폴백 행의 진입가를 같은 판정 함수에 넣으면 API 행과 같은 결과
        check("fallback-rows: 임계 동일성",
                rows.size() == 1 && "triggered".equals(StopLoss.verdict(
                        (Double) rows.get(0).get("avg_price"), 0.25, 0.25, 0.97, 0.03))
                        && "holding".equals(StopLoss.verdict(
                        (Double) rows.get(0).get("avg_price"), 0.70, 0.25, 0.97, 0.03)));
    }

    /** 무효 집합 — 재확인 창(10분) 안만 제외하고, 창이 지나면 다시 확인한다. */
    private static void fallbackDeadSetWindow() {
        List<Object> dead = StopLossFallback.parseDead("[{\"t\":\"9\",\"ts\":1000}]");
        check("fallback-dead-set: 창 안 활성",
                StopLossFallback.deadActiveTokens(dead, 1000 + 599_999).contains("9"));
        check("fallback-dead-set: 창 지나면 재확인",
                !StopLossFallback.deadActiveTokens(dead, 1000 + 600_000).contains("9"));
        check("fallback-dead-set: 깨진 원문은 빈 집합",
                StopLossFallback.parseDead("{oops").isEmpty());
    }

    /** 폴백 무대 — Data API 전수 실패 + 스냅샷 캐시 장부에 포지션 1건 (진입 0.9). */
    private static OrderTransport fallbackStage(FakeEnv env, FakeNet net, String slug,
                                                String tokenId, String noPrice) {
        double no = Double.parseDouble(noPrice);
        String yesPrice = String.valueOf(1 - no);
        env.snapCache = "{\"positions\":[{\"slug\":\"" + slug + "\",\"token_id\":\"" + tokenId
                + "\",\"outcome\":\"No\",\"avg_price\":0.9,\"current_price\":" + noPrice
                + ",\"current_size\":10}]}";
        net.failPositions = true;
        Map<String, Object> gamma = new LinkedHashMap<>();
        gamma.put("outcomes", "[\"Yes\",\"No\"]");
        gamma.put("outcomePrices", "[\"" + yesPrice + "\",\"" + noPrice + "\"]");
        net.gammaPages.put(slug, new ArrayList<>(List.of(gamma)));
        net.books.put(tokenId, jmap("{\"tick_size\":\"0.01\",\"neg_risk\":false,"
                + "\"asks\":[{\"price\":\"0.26\",\"size\":\"100\"}],"
                + "\"bids\":[{\"price\":\"" + noPrice + "\",\"size\":\"100\"}]}"));
        net.condBalance = BigInteger.valueOf(10_000_000);
        OrderTransport t = new OrderTransport();
        double taking = 10 * no;
        String takingStr = taking == Math.floor(taking)
                ? String.valueOf((long) taking) : String.valueOf(taking);
        t.responseBody = "{\"success\":true,\"status\":\"matched\","
                + "\"makingAmount\":\"10\",\"takingAmount\":\"" + takingStr + "\","
                + "\"transactionHashes\":[\"0xdef\"]}";
        return t;
    }

    /** Data API가 통째로 죽어도 장부 기준으로 손절이 발동하고, 매수는 나가지 않는다. */
    private static void fallbackStopLossFires() {
        String slug = "fallback-market-a";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = fallbackStage(env, net, slug, "222", "0.25");
        FakeLedgerStore store = new FakeLedgerStore();
        NativeCycle c = newCycle(env, net, t, store, () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("fallback-fire: 결과 FAILED (사이클은 원래 오류로 종료)",
                r == NativeCycle.Result.FAILED);
        check("fallback-fire: 폴백 발동 로그",
                env.logged("폴백 손절 실행 — 마지막 스냅샷 장부 1건 기준"));
        check("fallback-fire: 매도 제출 1회", t.posts == 1);
        check("fallback-fire: 폴백 손절 매도 로그", env.logged("폴백 손절 매도 " + slug));
        List<Object> recs = StopLossLedger.records(store);
        check("fallback-fire: 원장 기록 1건", recs.size() == 1);
        check("fallback-fire: 기록에 fallback 표기",
                recs.size() == 1 && Boolean.TRUE.equals(Js.field(recs.get(0), "fallback")));
        check("fallback-fire: 매수 미실행 (스캔 도달 없음)",
                net.marketsCalls == 0 && !env.stages.contains("scan"));
    }

    /** 폴백에서도 기온 태그 건은 기온 전용 폭으로 판정한다 (기준 동일성). */
    private static void fallbackTempTagUsesTempDrop() {
        String slug = "highest-temperature-in-seoul-on-november-15-2026-7c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        // 현재가 0.70 — 메인 폭 발화선 0.65 위, 기온 폭 발화선 0.75 아래
        OrderTransport t = fallbackStage(env, net, slug, "222", "0.70");
        env.durable.put("bm_bought",
                "[{\"slug\":\"" + slug + "\",\"strategy\":\"temp_no\"}]");
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("fallback-temp: 기온 폭으로 폴백 손절 실행",
                t.posts == 1 && env.logged("폴백 손절 매도 " + slug));
    }

    /** 태그 없는 건은 폴백에서도 메인 폭 미발화로 들고 있는다. */
    private static void fallbackUntaggedHoldsAtMidPrice() {
        String slug = "fallback-market-b";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = fallbackStage(env, net, slug, "222", "0.70");
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("fallback-hold: 메인 폭 미발화로 매도 없음", t.posts == 0);
        check("fallback-hold: 폴백 발동은 기록됨", env.logged("폴백 손절 실행"));
    }

    /** 처분 완료 토큰은 잔고 0으로 걸러지고, 실패 로그가 사이클마다 쌓이지 않는다. */
    private static void fallbackDisposedTokenSkippedOnce() {
        String slug = "fallback-market-c";
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        OrderTransport t = fallbackStage(env, net, slug, "333", "0.25");
        net.condBalance = BigInteger.ZERO;
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        c.runIteration(1);
        check("fallback-dead: 매도 제출 없음", t.posts == 0);
        check("fallback-dead: 무효 집합에 토큰 기록",
                env.durable.getOrDefault("bm_stoploss_fallback_dead", "").contains("333"));
        long logs = env.logs.stream()
                .filter(l -> l.contains("잔고 없음 (처분 완료로 간주)")).count();
        check("fallback-dead: 건너뜀 로그는 1회만", logs == 1);
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

    /** relayer 제출 401 — 같은 자격증명으로는 재제출하지 않고(래치), 자격증명이 바뀌면 다시 시도한다. */
    private static void redeemRelayerAuthLatch() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        net.marketContext = new NativeCycle.MarketContext(false, "v1", null, List.of("111", "222"));
        net.redeemBalance = BigInteger.valueOf(1000);
        String cid = "0x" + "ab".repeat(32);
        env.durable.put("bm_redeem_pending",
                "[{\"condition_id\":\"" + cid
                        + "\",\"status\":\"REDEEMABLE\",\"current_value\":12.5}]");
        final int[] submitPosts = {0};
        RawHttp.Transport t = new RawHttp.Transport() {
            @Override
            public RawHttp.Raw post(String url, Map<String, String> h, String body) {
                if (url.endsWith("/submit")) {
                    submitPosts[0]++;
                    return new RawHttp.Raw(401, "{\"error\":\"invalid authorization\"}", null);
                }
                return new RawHttp.Raw(200, "{}", null);
            }

            @Override
            public RawHttp.Raw get(String url, Map<String, String> h) {
                if (url.contains("transactions/params")) {
                    return new RawHttp.Raw(200, "{\"nonce\":\"0\"}", null);
                }
                return new RawHttp.Raw(200, "{}", null);
            }
        };
        final RedeemSubmitter.RelayerCreds[] current =
                {new RedeemSubmitter.RelayerCreds("key1", EOA)};
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> current[0]);
        c.runIteration(1);
        check("auth401: 첫 사이클은 제출 시도", submitPosts[0] == 1);
        check("auth401: 인증 실패 래치 로그", env.logged("인증 실패로 판정해 이 키로는 제출을 중단합니다"));
        c.runIteration(1);
        check("auth401: 같은 키로는 재제출 없음", submitPosts[0] == 1);
        check("auth401: 보류 로그", env.logged(
                "리딤 보류 1건 (약 $12.5) — Relayer API 키 인증 실패(401)로 제출을 중단합니다 (키 재등록 후 자동 재개)"));
        current[0] = new RedeemSubmitter.RelayerCreds("key2", EOA);
        c.runIteration(1);
        check("auth401: 키가 바뀌면 다시 제출 시도", submitPosts[0] == 2);
        check("auth401: isAuthError 판정", RedeemSubmitter.isAuthError("relayer 제출 HTTP 401: {}")
                && RedeemSubmitter.isAuthError("relayer 조회 HTTP 401: {}")
                && !RedeemSubmitter.isAuthError("relayer 제출 HTTP 403: {}")
                && !RedeemSubmitter.isAuthError("relayer 제출 HTTP 500: {}")
                && !RedeemSubmitter.isAuthError(null));
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

    /** 측정 대상 무대 — 활성 시장 1개 + 최소 수량·틱이 읽히는 오더북. */
    /** 수동 매도 표시 독립 가드 — 수동 매도 기록은 감사 원장으로 남지만
     *  정산 표시는 CLOSED 행의 realized_pnl을 따른다 (2026-11-24 API 표시
     *  교체: 종전에는 기록의 매도 실현손익이 표시를 덮었다). */
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
        // 다음 사이클 — 포지션은 사라지고 CLOSED 행만 남는다. 실제 매도는
        // 진입 0.75 → 0.55, 실현 -2.0으로 기록되지만 표시는 행의 +2.5를 따른다.
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
        check("manual-book: 표시는 API 행 기준 승 판정", env.snapCache.contains("\"settledWins\":1")
                && env.snapCache.contains("\"settledLosses\":0"));
        check("manual-book: 실현손익은 API 행값 2.5", env.snapCache.contains("\"settledRealizedPnl\":2.5"));
        check("manual-book: 손절 건수 무오염", env.snapCache.contains("\"settledStopLoss\":0"));
    }

    /** 정산 표시 폴백 가드 (2026-11-24) — CLOSED 조회 실패 사이클은 마지막
     *  성공 API 표시값을 유지하고, 성공 이력이 없으면 미상(null)이다.
     *  내부 계산값으로 대체하지 않는다. */
    private static void settledDisplayFallback() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        Map<String, Object> closed = manualRow();
        closed.put("status", "CLOSED");
        closed.put("current_price", 0.0);
        closed.put("current_size", 0.0);
        closed.put("current_value", 0.0);
        closed.put("realized_pnl", -2.5);
        net.closedRows = new ArrayList<>(List.of(closed));
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        check("fallback: 성공 사이클 표시", env.snapCache != null
                && env.snapCache.contains("\"settledRealizedPnl\":-2.5")
                && env.snapCache.contains("\"settledCount\":1"));
        net.failSettled = true;
        c.runIteration(1);
        check("fallback: 실패 사이클은 마지막 성공값 유지", env.snapCache != null
                && env.snapCache.contains("\"settledRealizedPnl\":-2.5")
                && env.snapCache.contains("\"settledCount\":1"));
        // 성공 이력이 없는 엔진은 실패 사이클에서 미상(null)이다
        FakeEnv env2 = new FakeEnv();
        FakeNet net2 = new FakeNet();
        net2.failSettled = true;
        NativeCycle c2 = newCycle(env2, net2, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c2.runIteration(1);
        check("fallback: 성공 이력 없으면 미상", env2.snapCache != null
                && env2.snapCache.contains("\"settledCount\":null"));
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

    /** v0.3.39 감사 C — pending status 승격: LOST로 먼저 수집된 토큰이
     *  나중에 CLOSED 승으로 오면 승자 판정에 잡혀야 한다 (강등은 없음). */
    private static void redeemStatusPromotion() {
        String cid = "0x" + "cc".repeat(32);
        Map<String, Object> lostRow = new LinkedHashMap<>();
        lostRow.put("condition_id", cid);
        lostRow.put("token_id", "t1");
        lostRow.put("slug", "s1");
        lostRow.put("current_value", 0.0);
        RedeemPlan.Ingest c1 = RedeemPlan.ingest(new ArrayList<>(), new HashSet<>(),
                null, new ArrayList<>(List.of(lostRow)), null);
        check("auditC: cycle1 collects lost row", c1.pending.size() == 1);
        check("auditC: cycle1 status is LOST",
                "REDEEMABLE_LOST".equals(com.roomedia.boringmech.engine.Js.field(c1.pending.get(0), "status")));
        check("auditC: cycle1 no target yet", RedeemPlan.planRedeems(c1.pending).targets.isEmpty());
        Map<String, Object> closedWin = new LinkedHashMap<>();
        closedWin.put("condition_id", cid);
        closedWin.put("token_id", "t1");
        closedWin.put("slug", "s1");
        closedWin.put("current_price", 1.0);
        closedWin.put("current_value", 0.0);
        closedWin.put("entry_cost_usdc", 10.0);
        closedWin.put("realized_pnl", 2.0);
        RedeemPlan.Ingest c2 = RedeemPlan.ingest(c1.pending, new HashSet<>(),
                null, null, new ArrayList<>(List.of(closedWin)));
        check("auditC: status promoted LOST → REDEEMABLE",
                "REDEEMABLE".equals(com.roomedia.boringmech.engine.Js.field(c2.pending.get(0), "status")));
        RedeemPlan.Plan plan2 = RedeemPlan.planRedeems(c2.pending);
        check("auditC: promoted entry becomes redeem target",
                plan2.targets.size() == 1 && plan2.worthless.isEmpty());
        RedeemPlan.Ingest c3 = RedeemPlan.ingest(c2.pending, new HashSet<>(),
                null, new ArrayList<>(List.of(lostRow)), null);
        check("auditC: later lost row does not demote",
                "REDEEMABLE".equals(com.roomedia.boringmech.engine.Js.field(c3.pending.get(0), "status")));
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

    // ── v0.3.38 감사 수리 가드 ──────────────────────────────────────

    /** 감사 1·4·3·9 — 설정 파서 타입 엄격화, decided 쌍 교차 가드,
     *  bet<=0 슬롯 차단, strategyOf 전 항목 스캔 경화. */
    private static void auditSettingsGuards() {
        check("audit1: cashFloorPct null falls back",
                TradeSettings.parse("{\"cashFloorPct\":null}").cashFloorPct == 0.3);
        check("audit1: betUsd true falls back",
                TradeSettings.parse("{\"betUsd\":true}").betUsd == 5);
        check("audit1: volumeMin true falls back",
                TradeSettings.parse("{\"volumeMin\":true}").volumeMin == 10000);
        check("audit1: askDepthMinUsd null falls back",
                TradeSettings.parse("{\"askDepthMinUsd\":null}").askDepthMinUsd == 10);
        check("audit1: bandLo empty string falls back",
                TradeSettings.parse("{\"bandLo\":\"\"}").bandLo == 0.6);
        check("audit1: bandLo array falls back",
                TradeSettings.parse("{\"bandLo\":[0.5]}").bandLo == 0.6);
        check("audit1: bandLo object falls back",
                TradeSettings.parse("{\"bandLo\":{\"x\":1}}").bandLo == 0.6);
        check("audit1: numeric string with spaces kept",
                TradeSettings.parse("{\"betUsd\":\" 7 \"}").betUsd == 7);
        TradeSettings cross = TradeSettings.parse("{\"decidedLow\":0.90,\"decidedHigh\":0.95}");
        check("audit4: decided pair above 0.5 resets", cross.decidedLow == 0.03 && cross.decidedHigh == 0.97);
        TradeSettings below = TradeSettings.parse("{\"decidedLow\":0.4,\"decidedHigh\":0.45}");
        check("audit4: decided pair below 0.5 resets", below.decidedLow == 0.03 && below.decidedHigh == 0.97);
        TradeSettings straddle = TradeSettings.parse("{\"decidedLow\":0.2,\"decidedHigh\":0.8}");
        check("audit4: decided pair straddling 0.5 kept",
                straddle.decidedLow == 0.2 && straddle.decidedHigh == 0.8);
        PositionBook.Sizing zs = PositionBook.sizing(100000, 1, 0,
                TradeSettings.parse("{\"betUsd\":0}"));
        check("audit3: bet 0 and slots closed", zs.bet == 0 && zs.slots == 0);
        Map<String, Object> tagged = new LinkedHashMap<>();
        tagged.put("slug", "dup-mkt");
        tagged.put("strategy", "temp_no");
        List<Object> dupPlainFirst = new ArrayList<>(List.of("dup-mkt", tagged));
        check("audit9: tag behind plain entry found",
                "temp_no".equals(BoughtReconcile.strategyOf(dupPlainFirst, "dup-mkt")));
        List<Object> dupTaggedFirst = new ArrayList<>(List.of(tagged, "dup-mkt"));
        check("audit9: tag ahead of plain entry found",
                "temp_no".equals(BoughtReconcile.strategyOf(dupTaggedFirst, "dup-mkt")));
        check("audit9: duplicates without tag stay main",
                BoughtReconcile.strategyOf(new ArrayList<>(List.of("dup-mkt", "dup-mkt")), "dup-mkt") == null);
    }

    /** 감사 2 — 설정은 사이클 시작에 1회 파싱된다. 스캔 단계에서 설정을
     *  바꿔도 이번 사이클의 스캔은 시작 시점 설정을 그대로 쓴다. */
    private static void cycleSettingsParsedOnce() {
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
        net.onFetchMarkets = () -> env.durable.put("bm_settings", "{\"bandLo\":0.99,\"bandHi\":1.0}");
        OrderTransport t = new OrderTransport();
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        NativeCycle.Result r = c.runIteration(1);
        check("audit2: cycle ok despite mid-cycle settings change", r == NativeCycle.Result.OK);
        check("audit2: scan used cycle-start settings", t.posts == 1);
    }

    /** 감사 5 — 수동 매도가 상수가 아니라 slippageCap 설정을 탄다.
     *  현재가 0.55, 캡 0.1이면 하한가 0.45 → takerAmount는 10주×0.45. */
    private static void manualSellSlippageCapFromSettings() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        env.durable.put("bm_settings", "{\"slippageCap\":0.1}");
        final String[] captured = new String[1];
        RawHttp.Transport t = new RawHttp.Transport() {
            @Override
            public RawHttp.Raw post(String url, Map<String, String> h, String body) {
                captured[0] = body;
                return new RawHttp.Raw(200, "{\"success\":true,\"status\":\"matched\","
                        + "\"makingAmount\":\"10\",\"takingAmount\":\"4.5\","
                        + "\"transactionHashes\":[\"0x123\"]}", null);
            }

            @Override
            public RawHttp.Raw get(String url, Map<String, String> h) {
                throw new AssertionError("GET 금지: " + url);
            }
        };
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":" + NOW + "}");
        c.runIteration(1);
        check("audit5: manual sell submitted", captured[0] != null);
        check("audit5: floor price uses settings slippageCap",
                captured[0] != null && captured[0].contains("\"takerAmount\":\"4500000\""));
    }

    /** v0.3.39 감사 B — 수동 매도는 넘겨받은 설정을 쓰고 durable을 다시
     *  파싱하지 않는다. durable에 설정이 없는데(기본 캡 0.015) 넘겨받은
     *  캡 0.1이 적용되면 하한가 0.45가 된다. */
    private static void manualSellUsesPassedSettings() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        manualStage(net);
        final String[] captured = new String[1];
        RawHttp.Transport t = new RawHttp.Transport() {
            @Override
            public RawHttp.Raw post(String url, Map<String, String> h, String body) {
                captured[0] = body;
                return new RawHttp.Raw(200, "{\"success\":true,\"status\":\"matched\","
                        + "\"makingAmount\":\"10\",\"takingAmount\":\"4.5\","
                        + "\"transactionHashes\":[\"0x123\"]}", null);
            }

            @Override
            public RawHttp.Raw get(String url, Map<String, String> h) {
                throw new AssertionError("GET 금지: " + url);
            }
        };
        NativeCycle c = newCycle(env, net, t, new FakeLedgerStore(), () -> null);
        try {
            c.snapshot(); // lastOpenRows 채우기 (수동 매도 대상 행)
        } catch (Exception e) {
            check("auditB: snapshot for manual stage", false);
            return;
        }
        env.durable.put("bm_manual_sell_req",
                "{\"token_id\":\"123456789\",\"slug\":\"test-ms-market\",\"ts\":" + NOW + "}");
        int sold = c.processManualSell(1,
                com.roomedia.boringmech.engine.TradeSettings.parse("{\"slippageCap\":0.1}"));
        check("auditB: manual sell sold with passed cfg", sold == 1);
        check("auditB: floor price uses passed slippageCap, not re-parsed durable",
                captured[0] != null && captured[0].contains("\"takerAmount\":\"4500000\""));
    }

    /** 감사 7 — 리딤 pending이 300건 캡으로 잘릴 때 잘린 항목은 아카이브에
     *  보존되고, 다음 사이클이 아카이브를 다시 회수해 재평가한다. 해소된
     *  id는 아카이브에서도 제거된다. */
    private static void redeemPendingArchiveSurvives() {
        FakeEnv env = new FakeEnv();
        FakeNet net = new FakeNet();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"condition_id\":\"").append(String.format("0x%064x", i + 1))
                    .append("\",\"token_id\":\"t").append(i)
                    .append("\",\"status\":\"REDEEMABLE\",\"current_value\":5}");
        }
        sb.append("]");
        env.durable.put("bm_redeem_pending", sb.toString());
        String firstCid = String.format("0x%064x", 1);
        String newCid = String.format("0x%064x", 9999);
        Map<String, Object> winRow = new LinkedHashMap<>();
        winRow.put("condition_id", newCid);
        winRow.put("token_id", "tnew");
        winRow.put("slug", "new-win");
        winRow.put("current_value", 5.0);
        net.winRows = new ArrayList<>(List.of(winRow));
        NativeCycle c = newCycle(env, net, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c.runIteration(1);
        String pending = env.durable.getOrDefault("bm_redeem_pending", "");
        String archive = env.durable.getOrDefault("bm_redeem_pending_archive", "");
        check("audit7: pending body capped at 300", !pending.contains(firstCid) && pending.contains(newCid));
        check("audit7: truncated entry archived, not dropped", archive.contains(firstCid));

        // 아카이브에 있는 worthless 항목은 다음 사이클에 다시 회수돼
        // streak 종결까지 재평가되고, 해소되면 아카이브에서도 빠진다.
        FakeEnv env2 = new FakeEnv();
        FakeNet net2 = new FakeNet();
        String worthlessCid = String.format("0x%064x", 777);
        env2.durable.put("bm_redeem_pending_archive",
                "[{\"condition_id\":\"" + worthlessCid + "\",\"token_id\":\"tw\","
                        + "\"status\":\"REDEEMABLE\",\"current_value\":0,\"zero_streak\":2}]");
        NativeCycle c2 = newCycle(env2, net2, new OrderTransport(), new FakeLedgerStore(), () -> null);
        c2.runIteration(1);
        check("audit7: archived entry re-evaluated to closure",
                env2.durable.getOrDefault("bm_redeem_closed", "").contains(worthlessCid));
        check("audit7: resolved id removed from archive",
                !env2.durable.getOrDefault("bm_redeem_pending_archive", "").contains(worthlessCid));
    }

    public static void main(String[] args) {
        happyPath();
        buyPath();
        tempBuyPath();
        tempFetchAllFailNotCached();
        tempCacheExpiresAtUtcMidnight();
        tempOffByDefault();
        tempReserveSlotGoesToTemp();
        tempReserveMainOnlyUsesAllSlots();
        tempReserveTempBeyondOne();
        tempReserveVerifyCap();
        tempReserveOffMainFirst();
        tempStopLossTaggedApplies();
        tempStopLossTaggedMidApplies();
        tempStopLossUntaggedMidHolds();
        tempStopLossUntaggedApplies();
        tempStopLossNoRecordApplies();
        tempReserveTwoSlots();
        tempLaneBuysAtUserAmount();
        tempLaneOffWhenReserveZero();
        tempLaneNeedsCashAboveReserve();
        tempLaneLimitedToReserveCount();
        tempBetAboveMainRespectsCashFloor();
        tempBetZeroSkipsTempBuys();
        mainStillRejectsBelowMinSizeAtLowBet();
        tempReserveUpcomingWindowHoldsMain();
        tempReservePastWindowNoHold();
        tempReserveZeroUpcomingNoHold();
        tempUpcomingAllBoughtNoHold();
        tempFetchAllFailKeepsReserve();
        tempFetchEmptyNoReserve();
        tempHoldTomorrowEventEveningBefore();
        tempHoldAppliesToCashSlots();
        tempHoldMainNotRetriedAfterTempFails();
        tempFetchPartialFailKeepsReserve();
        tempHoldReleaseLogged();
        tempCashGuardBlocksMainWhenHoldOff();
        tempCashGuardBoundaryAllowsMain();
        tradeSettingsParse();
        stopLossVerdictDrops();
        stopLossPath();
        snapshotFailureFallback();
        fallbackRowsBuilder();
        fallbackDeadSetWindow();
        fallbackStopLossFires();
        fallbackTempTagUsesTempDrop();
        fallbackUntaggedHoldsAtMidPrice();
        fallbackDisposedTokenSkippedOnce();
        marketsFailureIsolation();
        reconcileFailureIsolation();
        redeemRelayerAuthLatch();
        redeemNoKey();
        stopDuringCycle();
        updateCheck();
        manualSellPath();
        manualSellSettledAggregation();
        settledDisplayFallback();
        manualSellStale();
        manualSellZeroShares();
        manualSellUnknownToken();
        manualSellLockShared();
        feeRateCache();
        openFilterConsistency();
        stopLossBoundary();
        ledgerArchiveKeepsSlug();
        redeemIngestDropsDone();
        redeemStatusPromotion();
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
        auditSettingsGuards();
        cycleSettingsParsedOnce();
        manualSellSlippageCapFromSettings();
        manualSellUsesPassedSettings();
        redeemPendingArchiveSurvives();
        pureLogic();
        System.out.println("NativeCycleTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
