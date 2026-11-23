// 주문 경로 측정 기능 JVM 단위 테스트 — 지정가 빌더 검증·취소 요청 구성·
// 열린 주문 조회·체결 판정·통계·설정 파싱을 가짜 전송으로 검증한다.
// 실전 네트워크 없음. 실행: android/test/run_orderpath_test.sh
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.NativeCycle;
import com.roomedia.boringmech.engine.TradeSettings;
import com.roomedia.boringmech.write.ClobWriteClient;
import com.roomedia.boringmech.write.JsonCodec;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.OrderPathTest;
import com.roomedia.boringmech.write.RawHttp;
import com.roomedia.boringmech.write.SignedOrder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrderPathFeatureTest {
    private static int passed = 0;
    private static int failed = 0;

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
    private static Object j(String text) {
        return JsonText.parse(text);
    }

    /** 요청을 기록하고 스크립트된 응답을 돌려주는 전송. */
    static final class RecordingTransport implements RawHttp.Transport {
        String postUrl;
        String postBody;
        Map<String, String> postHeaders;
        String postResponse = "{\"success\":true,\"status\":\"live\",\"orderID\":\"ord-1\","
                + "\"makingAmount\":\"0\",\"takingAmount\":\"0\"}";
        String deleteUrl;
        String deleteBody;
        Map<String, String> deleteHeaders;
        String deleteResponse = "{\"canceled\":[\"ord-1\"],\"not_canceled\":{}}";
        String getUrl;
        Map<String, String> getHeaders;
        String getResponse = "{\"data\":[],\"next_cursor\":\"LTE=\"}";
        int getStatus = 200;

        @Override
        public RawHttp.Raw post(String url, Map<String, String> h, String body) {
            postUrl = url;
            postBody = body;
            postHeaders = h;
            return new RawHttp.Raw(200, postResponse, null);
        }

        @Override
        public RawHttp.Raw get(String url, Map<String, String> h) {
            getUrl = url;
            getHeaders = h;
            return new RawHttp.Raw(getStatus, getResponse, null);
        }

        @Override
        public RawHttp.Raw delete(String url, Map<String, String> h, String body) {
            deleteUrl = url;
            deleteBody = body;
            deleteHeaders = h;
            return new RawHttp.Raw(200, deleteResponse, null);
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

    public static void main(String[] args) throws Exception {
        testPureLogic();
        testClient();
        testSettingsAndSummary();
        System.out.println("orderpath: " + passed + "/" + (passed + failed) + " passed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ── 순수 로직 ────────────────────────────────────────────────────────

    private static void testPureLogic() {
        // 요청 파싱·신선도
        OrderPathTest.Request req = OrderPathTest.parseRequest(j("{\"ts\":1000}"));
        check("pure: 요청 파싱", req != null && req.tsMs == 1000);
        check("pure: 빈 요청 무효", OrderPathTest.parseRequest(j("{}")) == null);
        check("pure: ts 0 무효", OrderPathTest.parseRequest(j("{\"ts\":0}")) == null);
        check("pure: 비객체 무효", OrderPathTest.parseRequest(j("[1]")) == null);
        check("pure: 신선도 경계 안", !OrderPathTest.isStale(req, 1000 + 600_000));
        check("pure: 신선도 경계 밖", OrderPathTest.isStale(req, 1000 + 600_001));

        // 시장 선정 — 거래량 내림차순, 비활성·종료·주문중단·식별자 없음 제외
        @SuppressWarnings("unchecked")
        List<Object> markets = (List<Object>) j("["
                + "{\"active\":true,\"closed\":false,\"acceptingOrders\":true,\"slug\":\"a\",\"volume\":100,\"clobTokenIds\":\"[\\\"111\\\"]\"},"
                + "{\"active\":true,\"closed\":false,\"version\":\"v2\",\"slug\":\"b\",\"volume\":5000,\"positionIds\":[\"222\",\"333\"]},"
                + "{\"active\":true,\"closed\":true,\"slug\":\"c\",\"volume\":9000,\"clobTokenIds\":\"[\\\"444\\\"]\"},"
                + "{\"active\":true,\"closed\":false,\"acceptingOrders\":false,\"slug\":\"d\",\"volume\":9000,\"clobTokenIds\":\"[\\\"555\\\"]\"},"
                + "{\"active\":false,\"closed\":false,\"slug\":\"e\",\"volume\":9000,\"clobTokenIds\":\"[\\\"666\\\"]\"},"
                + "{\"active\":true,\"closed\":false,\"slug\":\"f\",\"volume\":9000}"
                + "]");
        List<OrderPathTest.Target> targets = OrderPathTest.pickTargets(markets, 5);
        check("pure: 선정 2건", targets.size() == 2);
        check("pure: 거래량 1순위는 v2 positionId", targets.get(0).slug.equals("b")
                && "222".equals(targets.get(0).positionId) && targets.get(0).tokenId == null
                && "222".equals(targets.get(0).assetKey()));
        check("pure: 2순위는 clobTokenIds", targets.get(1).slug.equals("a")
                && "111".equals(targets.get(1).tokenId) && "111".equals(targets.get(1).assetKey()));
        check("pure: 개수 제한", OrderPathTest.pickTargets(markets, 1).size() == 1);

        // 수량 계획 — min_order_size 그대로, 없으면 NaN (건너뜀 신호)
        check("pure: 수량 그대로", OrderPathTest.planSize(5) == 5);
        check("pure: 수량 0은 NaN", Double.isNaN(OrderPathTest.planSize(0)));
        check("pure: 수량 음수는 NaN", Double.isNaN(OrderPathTest.planSize(-1)));
        check("pure: 수량 NaN은 NaN", Double.isNaN(OrderPathTest.planSize(Double.NaN)));

        // 체결 판정
        check("pure: live는 미체결", !OrderPathTest.isFillResponse(
                j("{\"success\":true,\"status\":\"live\",\"orderID\":\"x\"}")));
        check("pure: matched는 체결", OrderPathTest.isFillResponse(
                j("{\"success\":true,\"status\":\"matched\"}")));
        check("pure: matched는 success 무관하게 체결", OrderPathTest.isFillResponse(
                j("{\"success\":false,\"status\":\"matched\"}")));
        check("pure: unmatched는 미체결", !OrderPathTest.isFillResponse(
                j("{\"success\":true,\"status\":\"unmatched\"}")));
        check("pure: 해시 있으면 체결", OrderPathTest.isFillResponse(
                j("{\"success\":true,\"status\":\"live\",\"transactionsHashes\":[\"0x1\"]}")));
        check("pure: delayed 판정", OrderPathTest.isDelayedResponse(
                j("{\"success\":true,\"status\":\"delayed\"}")));
        check("pure: 주문 ID 추출", "ord-7".equals(OrderPathTest.orderIdOf(
                j("{\"orderID\":\"ord-7\"}"))));
        check("pure: 주문 ID 없음", OrderPathTest.orderIdOf(j("{\"success\":true}")) == null);

        // 취소 판정
        Object cancelResp = j("{\"canceled\":[\"a\",\"b\"],\"not_canceled\":{\"c\":\"already canceled\"}}");
        check("pure: 취소 확정", OrderPathTest.cancelConfirmed(cancelResp, "b"));
        check("pure: 취소 미확정", !OrderPathTest.cancelConfirmed(cancelResp, "c"));
        check("pure: 취소 거부 사유", "already canceled".equals(
                OrderPathTest.cancelRefusal(cancelResp, "c")));
        check("pure: 거부 사유 없음", OrderPathTest.cancelRefusal(cancelResp, "a") == null);

        // 열린 주문 잔류
        @SuppressWarnings("unchecked")
        List<Object> open = (List<Object>) j("[{\"id\":\"x\",\"price\":\"0.01\"},{\"id\":\"y\"}]");
        check("pure: 잔류 있음", OrderPathTest.openOrderPresent(open, "y"));
        check("pure: 잔류 없음", !OrderPathTest.openOrderPresent(open, "z"));
        check("pure: 빈 목록", !OrderPathTest.openOrderPresent(List.of(), "x"));
        check("pure: null 목록", !OrderPathTest.openOrderPresent(null, "x"));

        // 호가 안전 판정 (감사 F1) — 최저 매도호가가 0.01보다 위에 있을 때만 안전
        Object bookSafe = j("{\"asks\":[{\"price\":\"0.50\",\"size\":\"10\"},{\"price\":\"0.20\",\"size\":\"5\"}]}");
        check("pure: bestAsk는 최소값", OrderPathTest.bestAsk(bookSafe) == 0.20);
        check("pure: bestAsk 안전", OrderPathTest.isSafeTestBook(bookSafe));
        Object bookAt = j("{\"asks\":[{\"price\":\"0.01\",\"size\":\"10\"}]}");
        check("pure: bestAsk 0.01은 체결 가능이라 unsafe", !OrderPathTest.isSafeTestBook(bookAt));
        Object bookBelow = j("{\"asks\":[{\"price\":\"0.001\",\"size\":\"10\"}]}");
        check("pure: bestAsk 0.001은 unsafe", !OrderPathTest.isSafeTestBook(bookBelow));
        Object bookZeroSize = j("{\"asks\":[{\"price\":\"0.001\",\"size\":\"0\"},{\"price\":\"0.30\",\"size\":\"3\"}]}");
        check("pure: 수량 0 호가는 건너뜀", OrderPathTest.bestAsk(bookZeroSize) == 0.30);
        check("pure: 호가 없으면 unsafe", !OrderPathTest.isSafeTestBook(j("{\"asks\":[]}")));
        check("pure: 북이 없으면 unsafe", !OrderPathTest.isSafeTestBook(null));

        // 결과 불명 판정 (감사 F2) — 전송 실패 {error}는 unknown, 명시 거부는 아님
        check("pure: 전송 실패는 unknown", OrderPathTest.isUnknownPostResponse(
                j("{\"error\":\"Network Error\"}")));
        check("pure: 5xx는 unknown", OrderPathTest.isUnknownPostResponse(
                j("{\"error\":\"boom\",\"status\":503}")));
        check("pure: 4xx 거부는 unknown 아님", !OrderPathTest.isUnknownPostResponse(
                j("{\"error\":\"bad\",\"status\":400}")));
        check("pure: live는 unknown 아님", !OrderPathTest.isUnknownPostResponse(
                j("{\"success\":true,\"status\":\"live\"}")));

        // 자기 주문 신원 대조 (감사 F3) — 전부 일치할 때만 인정
        long postStart = 1_700_000_000_000L;
        Object own = j("{\"id\":\"o1\",\"side\":\"BUY\",\"asset_id\":\"111\",\"price\":\"0.01\","
                + "\"original_size\":\"5\",\"size_matched\":\"0\",\"order_type\":\"GTC\","
                + "\"expiration\":\"0\",\"created_at\":1700000000,"
                + "\"maker_address\":\"" + FUNDER + "\"}");
        check("pure: 신원 일치면 자기 주문", OrderPathTest.isOwnTestOrder(own, "111", 5, postStart, FUNDER));
        Object wrongSize = j("{\"id\":\"o2\",\"side\":\"BUY\",\"asset_id\":\"111\",\"price\":\"0.01\","
                + "\"original_size\":\"10\",\"size_matched\":\"0\",\"order_type\":\"GTC\","
                + "\"expiration\":\"0\",\"created_at\":1700000000}");
        check("pure: 수량 다르면 남의 주문", !OrderPathTest.isOwnTestOrder(wrongSize, "111", 5, postStart, FUNDER));
        Object oldOrder = j("{\"id\":\"o3\",\"side\":\"BUY\",\"asset_id\":\"111\",\"price\":\"0.01\","
                + "\"original_size\":\"5\",\"size_matched\":\"0\",\"order_type\":\"GTC\","
                + "\"expiration\":\"0\",\"created_at\":1699999000}");
        check("pure: 제출 전 생성은 남의 주문", !OrderPathTest.isOwnTestOrder(oldOrder, "111", 5, postStart, FUNDER));
        Object sellOrder = j("{\"id\":\"o4\",\"side\":\"SELL\",\"asset_id\":\"111\",\"price\":\"0.01\","
                + "\"original_size\":\"5\",\"size_matched\":\"0\",\"order_type\":\"GTC\","
                + "\"expiration\":\"0\",\"created_at\":1700000000}");
        check("pure: 매도는 남의 주문", !OrderPathTest.isOwnTestOrder(sellOrder, "111", 5, postStart, FUNDER));
        Object matchedOrder = j("{\"id\":\"o5\",\"side\":\"BUY\",\"asset_id\":\"111\",\"price\":\"0.01\","
                + "\"original_size\":\"5\",\"size_matched\":\"1\",\"order_type\":\"GTC\","
                + "\"expiration\":\"0\",\"created_at\":1700000000}");
        check("pure: 일부 체결은 자기 주문 아님", !OrderPathTest.isOwnTestOrder(matchedOrder, "111", 5, postStart, FUNDER));

        // 통계 — 중앙값은 하위 중앙, p90은 nearest-rank
        long[] empty = OrderPathTest.summarizeMs(List.of());
        check("pure: 통계 빈 표본", empty[0] == 0 && empty[1] == 0 && empty[2] == 0 && empty[3] == 0);
        long[] one = OrderPathTest.summarizeMs(List.of(100L));
        check("pure: 통계 1건", one[0] == 1 && one[1] == 100 && one[2] == 100 && one[3] == 100);
        long[] four = OrderPathTest.summarizeMs(List.of(40L, 10L, 30L, 20L));
        check("pure: 통계 4건", four[0] == 4 && four[1] == 20 && four[2] == 40 && four[3] == 40);
        List<Long> ten = new ArrayList<>();
        for (long v = 1; v <= 10; v++) ten.add(v);
        long[] t = OrderPathTest.summarizeMs(ten);
        check("pure: 통계 10건", t[0] == 10 && t[1] == 5 && t[2] == 9 && t[3] == 10);
    }

    // ── 클라이언트 ───────────────────────────────────────────────────────

    private static void testClient() throws Exception {
        RecordingTransport t = new RecordingTransport();
        ClobWriteClient c = client(t);

        // 지정가 서명 + GTC 제출 본문 — 금액은 지정가 규칙 (size × price)
        ClobWriteClient.LimitOrderRequest req =
                new ClobWriteClient.LimitOrderRequest("12345", true, 0.01, 5);
        req.tickSize = "0.01";
        SignedOrder signed = c.createLimitOrder(req);
        check("client: 지정가 서명 생성", signed != null && signed.signature != null);
        Object resp = c.postOrder(signed, "GTC");
        check("client: 제출 URL", t.postUrl != null && t.postUrl.endsWith("/order"));
        check("client: 본문 GTC", t.postBody.contains("\"orderType\":\"GTC\""));
        check("client: 본문 expiration 0", t.postBody.contains("\"expiration\":\"0\""));
        check("client: 본문 makerAmount", t.postBody.contains("\"makerAmount\":\"50000\""));
        check("client: 본문 takerAmount", t.postBody.contains("\"takerAmount\":\"5000000\""));
        check("client: 응답 주문 ID", "ord-1".equals(OrderPathTest.orderIdOf(resp)));
        check("client: live는 미체결", !OrderPathTest.isFillResponse(resp));

        // GTD expiration은 본문에만 실린다
        ClobWriteClient.LimitOrderRequest gtd =
                new ClobWriteClient.LimitOrderRequest("12345", true, 0.5, 5);
        gtd.tickSize = "0.01";
        gtd.expiration = 1760003600L;
        c.postOrder(c.createLimitOrder(gtd), "GTD");
        check("client: GTD expiration 본문", t.postBody.contains("\"expiration\":\"1760003600\""));

        // 가격 검증 — 틱 미만·(1-틱) 초과·0은 서명 전에 막힌다
        check("client: 틱 미만 가격 거부", throwsWith(
                () -> c.createLimitOrder(limitReq(0.001)), "invalid price"));
        check("client: 상한 초과 가격 거부", throwsWith(
                () -> c.createLimitOrder(limitReq(0.999)), "invalid price"));
        check("client: 0 가격 거부", throwsWith(
                () -> c.createLimitOrder(limitReq(0)), "invalid price"));
        check("client: 경계 가격 허용", !throwsWith(
                () -> c.createLimitOrder(limitReq(0.99)), "invalid price"));

        // 취소 요청 구성 — DELETE /order, 본문 {"orderID": ...}, L2 헤더
        Object cancelResp = c.cancelOrder("ord-1");
        check("client: 취소 URL", t.deleteUrl != null && t.deleteUrl.endsWith("/order"));
        check("client: 취소 본문", "{\"orderID\":\"ord-1\"}".equals(t.deleteBody));
        check("client: 취소 L2 서명 헤더", t.deleteHeaders != null
                && t.deleteHeaders.get("POLY_SIGNATURE") != null
                && !t.deleteHeaders.get("POLY_SIGNATURE").isEmpty());
        check("client: 취소 확정 판정", OrderPathTest.cancelConfirmed(cancelResp, "ord-1"));

        // 열린 주문 조회 구성 — 쿼리·파싱
        t.getResponse = "{\"data\":[{\"id\":\"ord-1\",\"price\":\"0.01\"}],\"next_cursor\":\"LTE=\"}";
        List<Object> open = c.getOpenOrders("ord-1", null, null);
        check("client: 조회 URL", t.getUrl != null
                && t.getUrl.contains("/data/orders?next_cursor=MA==&id=ord-1"));
        check("client: 조회 파싱", open.size() == 1
                && OrderPathTest.openOrderPresent(open, "ord-1"));
        List<Object> byAsset = c.getOpenOrders(null, null, "12345");
        check("client: 조회 asset_id 쿼리", t.getUrl.contains("&asset_id=12345"));
        check("client: 조회 결과 재사용", byAsset.size() == 1);

        // 조회 실패는 빈 목록이 아니라 예외 — 잔류 오독 방지
        t.getStatus = 500;
        t.getResponse = "{}";
        boolean threw = false;
        try {
            c.getOpenOrders("ord-1", null, null);
        } catch (Exception e) {
            threw = true;
        }
        check("client: 조회 실패는 예외", threw);
    }

    private static ClobWriteClient.LimitOrderRequest limitReq(double price) {
        ClobWriteClient.LimitOrderRequest r =
                new ClobWriteClient.LimitOrderRequest("12345", true, price, 5);
        r.tickSize = "0.01";
        return r;
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static boolean throwsWith(ThrowingCall call, String needle) {
        try {
            call.run();
            return false;
        } catch (Exception e) {
            return e.getMessage() != null && e.getMessage().contains(needle);
        }
    }

    // ── 설정·요약 ────────────────────────────────────────────────────────

    private static void testSettingsAndSummary() {
        check("settings: 기본 30", TradeSettings.parse(null).orderTestRounds == 30);
        check("settings: 빈 객체 기본 30", TradeSettings.parse("{}").orderTestRounds == 30);
        check("settings: 45 적용", TradeSettings.parse("{\"orderTestRounds\":45}").orderTestRounds == 45);
        check("settings: 0은 기본값", TradeSettings.parse("{\"orderTestRounds\":0}").orderTestRounds == 30);
        check("settings: 101은 기본값", TradeSettings.parse("{\"orderTestRounds\":101}").orderTestRounds == 30);
        check("settings: 소수는 기본값", TradeSettings.parse("{\"orderTestRounds\":45.5}").orderTestRounds == 30);
        check("settings: 경계 1", TradeSettings.parse("{\"orderTestRounds\":1}").orderTestRounds == 1);
        check("settings: 경계 100", TradeSettings.parse("{\"orderTestRounds\":100}").orderTestRounds == 100);

        String text = NativeCycle.stageDurationsText(1, 2, 3, 4, 5);
        check("summary: 소요 표기", text.equals("소요 스냅샷 1ms · 손절 2ms · 스캔 3ms · 검증 4ms · 주문 5ms"));
        String withDur = NativeCycle.summarizeCycle(1, 1, Map.of(), 1, 0, 0, null, text);
        check("summary: 소요 포함", withDur.endsWith(" · " + text));
        String without = NativeCycle.summarizeCycle(1, 1, Map.of(), 1, 0, 0, null);
        check("summary: 소요 없으면 기존 문구", !without.contains("소요"));
    }
}
