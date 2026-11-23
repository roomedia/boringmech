// ClobWriteClient JVM 단위 테스트 — 지정가 빌더 검증·취소 요청 구성·
// 열린 주문 조회를 가짜 전송으로 검증한다. 주문 경로 측정 기능은
// v0.3.43에서 제거됐고, 지정가·취소·조회 메서드는 향후 지정가 전략의
// 기반이라 남아 있어 이 테스트가 그 표면을 고정한다.
// 실전 네트워크 없음. 실행: android/test/run_clob_write_test.sh
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.NativeCycle;
import com.roomedia.boringmech.engine.TradeSettings;
import com.roomedia.boringmech.write.ClobWriteClient;
import com.roomedia.boringmech.write.JsonCodec;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.RawHttp;
import com.roomedia.boringmech.write.SignedOrder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClobWriteClientTest {
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

    private static String fieldOf(Object model, String key) {
        if (model instanceof Map) {
            Object v = ((Map<?, ?>) model).get(key);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    private static boolean listFieldContains(Object model, String key, String value) {
        if (model instanceof Map) {
            Object v = ((Map<?, ?>) model).get(key);
            if (v instanceof List) {
                for (Object e : (List<?>) v) {
                    if (value.equals(String.valueOf(e))) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        testClient();
        testSettingsAndSummary();
        System.out.println("clobwrite: " + passed + "/" + (passed + failed) + " passed");
        System.exit(failed == 0 ? 0 : 1);
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
        check("client: 응답 주문 ID", "ord-1".equals(fieldOf(resp, "orderID")));
        check("client: live 상태", "live".equals(fieldOf(resp, "status")));

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
        check("client: 취소 확정 판정", listFieldContains(cancelResp, "canceled", "ord-1"));

        // 열린 주문 조회 구성 — 쿼리·파싱
        t.getResponse = "{\"data\":[{\"id\":\"ord-1\",\"price\":\"0.01\"}],\"next_cursor\":\"LTE=\"}";
        List<Object> open = c.getOpenOrders("ord-1", null, null);
        check("client: 조회 URL", t.getUrl != null
                && t.getUrl.contains("/data/orders?next_cursor=MA==&id=ord-1"));
        check("client: 조회 파싱", open.size() == 1
                && "ord-1".equals(fieldOf(open.get(0), "id")));
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
