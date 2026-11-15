import com.roomedia.boringmech.crypto.Amounts;
import com.roomedia.boringmech.crypto.CallData;
import com.roomedia.boringmech.crypto.Hex;
import com.roomedia.boringmech.crypto.JsNumber;
import com.roomedia.boringmech.crypto.OrderSigner;
import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.write.ClobWriteClient;
import com.roomedia.boringmech.write.JsonCodec;
import com.roomedia.boringmech.write.JudgeFill;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.OrderBridge;
import com.roomedia.boringmech.write.OrderJson;
import com.roomedia.boringmech.write.RawHttp;
import com.roomedia.boringmech.write.RedeemSubmitter;
import com.roomedia.boringmech.write.SignedOrder;
import com.roomedia.boringmech.write.StopLoss;
import com.roomedia.boringmech.write.StopLossLedger;
import com.roomedia.boringmech.write.SubmitGuard;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 네이티브 이관 4단계(쓰기 경로) 골든 대조 러너.
 * golden4.json(gen_golden4.mjs가 SDK·engine.js·order-bridge.js 원본으로
 * 생성)을 네이티브 write 패키지에 통과시켜 바이트·판정 단위로 대조한다.
 * 실제 네트워크는 쓰지 않는다 — 전송은 전부 가짜 Transport다.
 *
 * 실행: run.sh (인자: native-golden4 디렉터리 경로, 기본 ".")
 */
public class Golden4Test {
    private static int failures = 0;
    private static int checks = 0;

    private static final JsonCodec TEST_CODEC = new JsonCodec() {
        @Override
        public Object parse(String text) {
            return Json.parse(text);
        }

        @Override
        public String stringify(Object model) {
            return ModelJson.stringify(model);
        }
    };

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        Map<String, Object> g = asMap(Json.parse(Files.readString(dir.resolve("golden4.json"))));
        Map<String, Object> meta = asMap(g.get("meta"));
        BigInteger pk = new BigInteger(((String) meta.get("privateKey")).substring(2), 16);
        String eoa = (String) meta.get("eoa");
        String funder = (String) meta.get("funder");

        testOrderBodies(g, pk);
        testL2Headers(g, pk, eoa, funder, meta);
        testJudgeFill(g);
        testVerdicts(g);
        testOutcomeIndex(g);
        testRoundTick(g);
        testFillMath(g);
        testBridgeShape(g);
        testBridgeRetry(g);
        testRedeemBody(g, eoa, funder);
        testSubmitGuard();
        testLedger();
        testRedeemGate(pk, eoa, funder);
        testStopLossSeller(pk, eoa, funder, meta);

        System.out.println("----");
        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // ── 주문 본문 ──

    private static void testOrderBodies(Map<String, Object> g, BigInteger pk) {
        for (Object cObj : asList(g.get("orderBodies"))) {
            Map<String, Object> c = asMap(cObj);
            String name = "orderBody/" + c.get("name");
            Map<String, Object> in = asMap(c.get("input"));
            Map<String, Object> exp = asMap(c.get("expected"));
            int version = (int) num(in.get("version"));
            boolean negRisk = Boolean.TRUE.equals(in.get("negRisk"));
            boolean buy = "BUY".equals(in.get("side"));
            String tickSize = (String) in.get("tickSize");
            String[] amts = Amounts.marketOrderAmounts(buy, num(in.get("amount")), num(in.get("price")),
                    Amounts.roundingConfig(tickSize));
            SignedOrder o = new SignedOrder();
            o.salt = (String) in.get("salt");
            o.maker = (String) in.get("maker");
            o.signer = (String) in.get("signer");
            o.tokenId = (String) in.get("tokenId");
            o.makerAmount = amts[0];
            o.takerAmount = amts[1];
            o.side = (String) in.get("side");
            o.signatureType = (int) num(in.get("signatureType"));
            o.timestamp = (String) in.get("timestamp");
            o.expiration = "0";
            o.metadata = (String) in.get("metadata");
            o.builder = (String) in.get("builderCode");
            String exchange = version == 3 ? ClobWriteClient.EXCHANGE_V3
                    : negRisk ? ClobWriteClient.NEG_RISK_EXCHANGE_V2 : ClobWriteClient.EXCHANGE_V2;
            o.signature = OrderSigner.signOrder(pk, o.toSignerOrder(), String.valueOf(version),
                    BigInteger.valueOf(137), exchange);
            eq(name + " signature", exp.get("signature"), o.signature);
            String body = OrderJson.orderToJsonV2(o, (String) in.get("owner"), (String) in.get("orderType"));
            eq(name + " body", exp.get("body"), body);
        }
    }

    // ── L2 헤더 ──

    private static void testL2Headers(Map<String, Object> g, BigInteger pk, String eoa,
                                       String funder, Map<String, Object> meta) {
        Map<String, String> bodiesByName = new LinkedHashMap<>();
        for (Object cObj : asList(g.get("orderBodies"))) {
            Map<String, Object> c = asMap(cObj);
            bodiesByName.put((String) c.get("name"), (String) asMap(c.get("expected")).get("body"));
        }
        String apiKey = (String) asMap(asMap(asList(g.get("orderBodies")).get(0))
                .get("input")).get("owner");
        for (Object cObj : asList(g.get("l2Headers"))) {
            Map<String, Object> c = asMap(cObj);
            String name = "l2/" + c.get("name");
            Map<String, Object> in = asMap(c.get("input"));
            long ts = (long) num(in.get("timestamp"));
            ClobWriteClient client = new ClobWriteClient.Builder()
                    .privateKey(pk)
                    .funder(funder)
                    .creds(new ClobWriteClient.Creds(eoa, apiKey,
                            (String) meta.get("secret"), (String) meta.get("passphrase")))
                    .transport(new RawHttp.Transport() {
                        @Override
                        public RawHttp.Raw post(String url, Map<String, String> h, String b) {
                            throw new AssertionError("네트워크 금지");
                        }

                        @Override
                        public RawHttp.Raw get(String url, Map<String, String> h) {
                            throw new AssertionError("네트워크 금지");
                        }
                    })
                    .codec(TEST_CODEC)
                    .clock(() -> ts * 1000)
                    .build();
            String body = in.get("bodyCase") != null ? bodiesByName.get((String) in.get("bodyCase")) : null;
            Map<String, String> headers = client.createL2Headers(
                    (String) in.get("method"), (String) in.get("path"), body);
            Map<String, Object> exp = asMap(c.get("expected"));
            for (Map.Entry<String, Object> e : exp.entrySet()) {
                eq(name + " " + e.getKey(), e.getValue(), headers.get(e.getKey()));
            }
        }
    }

    // ── judgeFill ──

    private static void testJudgeFill(Map<String, Object> g) {
        for (Object cObj : asList(g.get("judgeFill"))) {
            Map<String, Object> c = asMap(cObj);
            String name = "judgeFill/" + c.get("name");
            JudgeFill.Verdict v = JudgeFill.judgeFill(c.get("resp"));
            Map<String, Object> exp = asMap(c.get("expected"));
            eq(name + " filled", exp.get("filled"), v.filled);
            eq(name + " kind", exp.get("kind"), v.kind);
            eq(name + " status", exp.get("status"), v.status);
            Object expErr = exp.containsKey("error") ? exp.get("error") : null;
            eq(name + " error", expErr, v.error);
        }
    }

    // ── 손절 판정·인덱스·틱·체결 산출 ──

    private static void testVerdicts(Map<String, Object> g) {
        for (Object cObj : asList(g.get("stopLossVerdict"))) {
            Map<String, Object> c = asMap(cObj);
            double entry = Double.parseDouble((String) c.get("entry"));
            double current = Double.parseDouble((String) c.get("current"));
            eq("verdict/" + c.get("entry") + "," + c.get("current"),
                    c.get("expected"), StopLoss.stopLossVerdict(entry, current));
        }
    }

    private static void testOutcomeIndex(Map<String, Object> g) {
        for (Object cObj : asList(g.get("pickOutcomeIndex"))) {
            Map<String, Object> c = asMap(cObj);
            Object outcomes = c.get("outcomes");
            @SuppressWarnings("unchecked")
            List<Object> list = outcomes instanceof List ? (List<Object>) outcomes : null;
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) c.get("row");
            int actual = StopLoss.pickOutcomeIndex(list, row);
            eq("outcomeIndex/" + c.get("expected") + "@" + checks, (int) num(c.get("expected")), actual);
        }
    }

    private static void testRoundTick(Map<String, Object> g) {
        for (Object cObj : asList(g.get("roundTick"))) {
            Map<String, Object> c = asMap(cObj);
            double actual = StopLoss.roundTick(
                    Double.parseDouble((String) c.get("price")),
                    Double.parseDouble((String) c.get("tick")));
            eq("roundTick/" + c.get("price") + "," + c.get("tick"),
                    c.get("expected"), JsNumber.toString(actual));
        }
    }

    private static void testFillMath(Map<String, Object> g) {
        for (Object cObj : asList(g.get("fillMath"))) {
            Map<String, Object> c = asMap(cObj);
            StopLoss.FillMath f = StopLoss.computeFill(c.get("resp"),
                    Double.parseDouble((String) c.get("shares")),
                    Double.parseDouble((String) c.get("current")),
                    Double.parseDouble((String) c.get("entry")));
            Map<String, Object> exp = asMap(c.get("expected"));
            String name = "fillMath/" + checks;
            eq(name + " soldShares", exp.get("soldShares"), JsNumber.toString(f.soldShares));
            eq(name + " proceeds", exp.get("proceeds"), JsNumber.toString(f.proceeds));
            eq(name + " exitPrice", exp.get("exitPrice"), JsNumber.toString(f.exitPrice));
            eq(name + " realized", exp.get("realized"), JsNumber.toString(f.realized));
        }
    }

    // ── 브리지 shaping·재시도 ──

    private static RawHttp.Raw rawOf(Map<String, Object> m) {
        Object st = m.get("status");
        int status = st == null ? 0 : (int) num(st);
        String body = m.get("body") instanceof String ? (String) m.get("body") : "";
        String error = m.get("error") instanceof String ? (String) m.get("error") : null;
        return new RawHttp.Raw(status, body, error);
    }

    private static void testBridgeShape(Map<String, Object> g) {
        OrderBridge bridge = new OrderBridge(new RawHttp.Transport() {
            @Override
            public RawHttp.Raw post(String url, Map<String, String> h, String b) {
                throw new AssertionError("shape는 전송 없음");
            }

            @Override
            public RawHttp.Raw get(String url, Map<String, String> h) {
                throw new AssertionError("shape는 전송 없음");
            }
        }, TEST_CODEC, ms -> {});
        for (Object cObj : asList(g.get("bridgeShape"))) {
            Map<String, Object> c = asMap(cObj);
            String name = "bridgeShape/" + c.get("name");
            Map<String, Object> exp = asMap(c.get("expected"));
            Object actual = bridge.shapeNativeResult(rawOf(asMap(c.get("raw"))));
            if (exp.containsKey("error")) {
                fail(name, "예외 기대였으나 반환됨: " + actual);
            } else {
                eqCanon(name, exp.get("ok"), actual);
            }
        }
    }

    private static void testBridgeRetry(Map<String, Object> g) throws Exception {
        for (Object cObj : asList(g.get("bridgeRetry"))) {
            Map<String, Object> c = asMap(cObj);
            String name = "bridgeRetry/" + c.get("name");
            List<Object> outcomes = asList(c.get("outcomes"));
            int[] calls = {0};
            RawHttp.Transport fake = new RawHttp.Transport() {
                @Override
                public RawHttp.Raw post(String url, Map<String, String> h, String b) {
                    Map<String, Object> o = asMap(outcomes.get(Math.min(calls[0], outcomes.size() - 1)));
                    calls[0]++;
                    return rawOf(o);
                }

                @Override
                public RawHttp.Raw get(String url, Map<String, String> h) {
                    throw new AssertionError("GET 없음");
                }
            };
            OrderBridge bridge = new OrderBridge(fake, TEST_CODEC, ms -> {});
            Map<String, Object> exp = asMap(c.get("expected"));
            Object actual = null;
            String thrown = null;
            try {
                actual = bridge.bridgePost("https://clob.polymarket.com/order",
                        Map.of(), "{}", Boolean.TRUE.equals(c.get("retryOnError")));
            } catch (Exception e) {
                thrown = e.getMessage();
            }
            eq(name + " calls", (int) num(exp.get("calls")), calls[0]);
            if (exp.containsKey("error")) {
                eq(name + " error", exp.get("error"), thrown);
            } else {
                eqCanon(name + " result", exp.get("ok"), actual);
            }
        }
    }

    // ── 리딤 본문·게이트 ──

    private static void testRedeemBody(Map<String, Object> g, String eoa, String funder) {
        Map<String, Object> c = asMap(g.get("redeemBody"));
        Map<String, Object> in = asMap(c.get("input"));
        List<CallData> calls = new ArrayList<>();
        for (Object callObj : asList(in.get("calls"))) {
            Map<String, Object> call = asMap(callObj);
            calls.add(new CallData((String) call.get("target"), BigInteger.ZERO,
                    Hex.hexToBytes((String) call.get("data"))));
        }
        String body = RedeemSubmitter.submitBody(
                (String) in.get("eoa"), (String) in.get("funder"), (String) in.get("nonce"),
                (String) in.get("signature"), (String) in.get("conditionId"),
                (String) in.get("deadline"), calls);
        eq("redeemBody", c.get("expected"), body);
    }

    private static void testRedeemGate(BigInteger pk, String eoa, String funder) throws Exception {
        RawHttp.Transport boom = new RawHttp.Transport() {
            @Override
            public RawHttp.Raw post(String url, Map<String, String> h, String b) {
                throw new AssertionError("키 없으면 네트워크 금지");
            }

            @Override
            public RawHttp.Raw get(String url, Map<String, String> h) {
                throw new AssertionError("키 없으면 네트워크 금지");
            }
        };
        RedeemSubmitter s = new RedeemSubmitter(boom, TEST_CODEC, pk, eoa, funder,
                ms -> {}, System::currentTimeMillis);
        Object r = s.submitIfCreds("0xcond", List.of(), null);
        eq("redeemGate hold", null, r);
        eq("redeemGate enum", RedeemSubmitter.Gate.HOLD, RedeemSubmitter.gate(null));
    }

    // ── 제출 가드·원장 (합성) ──

    private static void testSubmitGuard() {
        List<Boolean> events = new ArrayList<>();
        SubmitGuard guard = new SubmitGuard(events::add);
        check("guard lock1", guard.tryAcquireSubmitLock("a"));
        check("guard lock2 denied", !guard.tryAcquireSubmitLock("a"));
        check("guard lock other", guard.tryAcquireSubmitLock("b"));
        guard.releaseSubmitLock("a");
        check("guard lock again", guard.tryAcquireSubmitLock("a"));
        guard.beginSubmit();
        guard.beginSubmit();
        check("guard inflight", guard.submitInFlight());
        eq("guard events after begin x2", List.of(true), events);
        guard.endSubmit();
        check("guard still inflight", guard.submitInFlight());
        guard.endSubmit();
        check("guard done", !guard.submitInFlight());
        eq("guard events final", List.of(true, false), events);
        guard.beginSubmit();
        guard.resetInFlight();
        check("guard reset", !guard.submitInFlight());
    }

    private static void testLedger() {
        StopLossLedger.InMemoryStore store = new StopLossLedger.InMemoryStore();
        for (int i = 0; i < 205; i++) {
            StopLossLedger.recordStopLoss(store, StopLossLedger.record(
                    "slug" + i, "cond", "tok" + i, "Yes", 0.9, 0.6, 5, 3, -1.5,
                    "2026-11-15T00:00:00.000Z"));
        }
        List<Object> records = StopLossLedger.records(store);
        eq("ledger total", 205, records.size());
        Object recentObj = store.read(StopLossLedger.KEY_RECENT);
        eq("ledger recent cap", 200, asList(recentObj).size());
        List<Object> archive = asList(store.read(StopLossLedger.KEY_ARCHIVE));
        eq("ledger archive", 5, archive.size());
        Map<String, Object> slim = asMap(archive.get(0));
        eq("ledger archive slim keys", 2, slim.size());
        for (int i = 0; i < 250; i++) {
            StopLossLedger.recordAttempt(store, "att" + i);
        }
        eq("ledger attempted cap", 200, StopLossLedger.attemptedTokens(store).size());
        check("ledger attempted keeps recent",
                StopLossLedger.attemptedTokens(store).contains("att249"));
        check("ledger attempted drops old",
                !StopLossLedger.attemptedTokens(store).contains("att0"));
    }

    // ── 손절 매도 오케스트레이션 (합성, 가짜 전송) ──

    private static void testStopLossSeller(BigInteger pk, String eoa, String funder,
                                            Map<String, Object> meta) {
        String token = "5217830831767552351603457242192184817666125891744021388456190691134219482513";
        runSellerScenario(pk, eoa, funder, meta, token,
                "{\"success\":true,\"status\":\"matched\",\"makingAmount\":\"5\","
                        + "\"takingAmount\":\"3.5\",\"transactionsHashes\":[\"0xh\"],\"tradeIDs\":[]}",
                true, 1, false, "seller/filled");
        runSellerScenario(pk, eoa, funder, meta, token,
                "{\"success\":true,\"status\":\"live\"}",
                false, 0, false, "seller/unfilled");
        runSellerScenario(pk, eoa, funder, meta, token, null,
                false, 0, true, "seller/unknown");
    }

    /** orderBodyJson이 null이면 /order 전송 실패(status 0)로 시뮬레이션한다. */
    private static void runSellerScenario(BigInteger pk, String eoa, String funder,
                                          Map<String, Object> meta, String token,
                                          String orderBodyJson, boolean expectSold,
                                          int expectRecords, boolean expectAttempt,
                                          String name) {
        RawHttp.Transport fake = new RawHttp.Transport() {
            @Override
            public RawHttp.Raw post(String url, Map<String, String> h, String b) {
                if (url.endsWith("/order")) {
                    return orderBodyJson == null
                            ? RawHttp.Raw.failure("연결 시간 초과")
                            : new RawHttp.Raw(200, orderBodyJson, null);
                }
                throw new AssertionError("예상 밖 POST " + url);
            }

            @Override
            public RawHttp.Raw get(String url, Map<String, String> h) {
                if (url.contains("/markets-by-token/")) {
                    return new RawHttp.Raw(200, "{\"condition_id\":\"cond1\"}", null);
                }
                if (url.contains("/clob-markets/")) {
                    return new RawHttp.Raw(200,
                            "{\"t\":[{\"t\":\"" + token + "\"}],\"mts\":0.01,\"nr\":false}", null);
                }
                if (url.endsWith("/version")) {
                    return new RawHttp.Raw(200, "{\"version\":2}", null);
                }
                throw new AssertionError("예상 밖 GET " + url);
            }
        };
        ClobWriteClient client = new ClobWriteClient.Builder()
                .privateKey(pk)
                .funder(funder)
                .creds(new ClobWriteClient.Creds(eoa,
                        "11111111-2222-3333-4444-555555555555",
                        (String) meta.get("secret"), (String) meta.get("passphrase")))
                .transport(fake)
                .codec(TEST_CODEC)
                .clock(() -> 1760000000000L)
                .saltGenerator(() -> "12345")
                .build();
        StopLossLedger.InMemoryStore store = new StopLossLedger.InMemoryStore();
        List<String> logs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        com.roomedia.boringmech.write.StopLossSeller seller =
                new com.roomedia.boringmech.write.StopLossSeller(
                        client,
                        tokenId -> BigInteger.valueOf(5_000_000),
                        new com.roomedia.boringmech.write.StopLossSeller.OrderBookSource() {
                            @Override
                            public com.roomedia.boringmech.write.StopLossSeller.OrderBookInfo
                            getOrderBook(String tokenId) {
                                return new com.roomedia.boringmech.write.StopLossSeller
                                        .OrderBookInfo("0.01", false);
                            }

                            @Override
                            public String getTickSize(String tokenId) {
                                return "0.01";
                            }
                        },
                        new SubmitGuard(),
                        store,
                        logs::add,
                        (title, body) -> notes.add(title + "|" + body),
                        ClobWriteClient.DIRECT_TIMEOUT,
                        () -> 1760000000000L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slug", "s1");
        row.put("token_id", token);
        row.put("avg_price", 0.95);
        row.put("current_size", 5.0);
        row.put("condition_id", "cond1");
        row.put("outcome", "Yes");
        boolean sold = seller.executeStopLossSell(row, 0.70);
        if (sold != expectSold) {
            System.out.println("[" + name + "] logs: " + logs);
        }
        eq(name + " sold", expectSold, sold);
        eq(name + " records", expectRecords, StopLossLedger.records(store).size());
        eq(name + " attempted", expectAttempt,
                StopLossLedger.attemptedTokens(store).contains(token));
        if (expectSold) {
            Map<String, Object> rec = asMap(StopLossLedger.records(store).get(0));
            eq(name + " realized", -1.25, ((Number) rec.get("realized_pnl")).doubleValue());
            eq(name + " notify", 1, notes.size());
        }
    }

    // ── 공용 ──

    private static void eq(String name, Object expected, Object actual) {
        checks++;
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    private static void eqCanon(String name, Object expected, Object actual) {
        checks++;
        String e = canon(expected);
        String a = canon(actual);
        if (!e.equals(a)) {
            failures++;
            System.out.println("FAIL " + name + "\n  expected: " + e + "\n  actual:   " + a);
        }
    }

    /** 키 순서 무시 구조 비교용 정규화 직렬화. */
    private static String canon(Object v) {
        if (v instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(ModelJson.quote(e.getKey())).append(':').append(canon(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canon(l.get(i)));
            }
            return sb.append(']').toString();
        }
        return ModelJson.stringify(v);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }

    private static void fail(String name, String why) {
        checks++;
        failures++;
        System.out.println("FAIL " + name + ": " + why);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }
}
