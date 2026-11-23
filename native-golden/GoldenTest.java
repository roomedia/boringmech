import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.roomedia.boringmech.crypto.Amounts;
import com.roomedia.boringmech.crypto.BatchSigner;
import com.roomedia.boringmech.crypto.CallData;
import com.roomedia.boringmech.crypto.ClobAuth;
import com.roomedia.boringmech.crypto.Funder;
import com.roomedia.boringmech.crypto.Hex;
import com.roomedia.boringmech.crypto.JsNumber;
import com.roomedia.boringmech.crypto.OrderSigner;
import com.roomedia.boringmech.crypto.PolyHmac;
import com.roomedia.boringmech.crypto.Redeem;
import com.roomedia.boringmech.crypto.Secp256k1;

/**
 * golden.json 전 케이스를 Java 암호 코어로 재현해 대조하는 JVM 테스트 러너.
 * 사용법: java GoldenTest <golden.json 경로>
 * 종료 코드: 전부 통과 0, 하나라도 실패 1.
 */
public class GoldenTest {
    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Map<String, Object> root = obj(new JsonParser(json).parse());

        Map<String, Object> meta = obj(root.get("meta"));
        BigInteger priv = new BigInteger(str(meta.get("testPrivateKey")).substring(2), 16);
        String fixedSalt = decStr(meta.get("fixedSalt"));
        String fixedOrderTimestamp = decStr(meta.get("fixedOrderTimestamp"));

        testFunder(root, priv, meta);
        testAmounts(root);
        testLimitAmounts(root);
        testOrderSignatures(root, priv, fixedSalt, fixedOrderTimestamp);
        testLimitOrderSignatures(root, priv, fixedSalt, fixedOrderTimestamp);
        testL1(root, priv);
        testL2(root);
        testRedeem(root);
        testBatch(root, priv);

        System.out.println();
        System.out.println("golden: " + passed + "/" + (passed + failed) + " passed");
        for (String f : failures) {
            System.out.println("FAIL " + f);
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    private static void testFunder(Map<String, Object> root, BigInteger priv, Map<String, Object> meta) {
        Map<String, Object> c = obj(root.get("funderDerivation"));
        String eoa = str(obj(c.get("input")).get("eoa"));
        Map<String, Object> exp = obj(c.get("expected"));
        String derivedEoa = Secp256k1.address(priv);
        String uups = Funder.deriveUups(eoa);
        String beacon = Funder.deriveBeacon(eoa);
        boolean ok = derivedEoa.equals(str(meta.get("eoa")))
                && uups.equals(str(exp.get("uups")))
                && beacon.equals(str(exp.get("beacon")));
        check("funderDerivation", ok,
                "eoa=" + derivedEoa + " uups=" + uups + " beacon=" + beacon
                        + " expected uups=" + exp.get("uups") + " beacon=" + exp.get("beacon"));
    }

    private static void testAmounts(Map<String, Object> root) {
        for (Object o : arr(root.get("amounts"))) {
            Map<String, Object> c = obj(o);
            String name = "amounts/" + str(c.get("name"));
            try {
                Map<String, Object> in = obj(c.get("input"));
                Map<String, Object> exp = obj(c.get("expected"));
                Map<String, Object> expCfg = obj(exp.get("roundConfig"));
                Amounts.RoundingConfig cfg = Amounts.roundingConfig(str(in.get("tickSize")));
                boolean cfgOk = cfg.price == intVal(expCfg.get("price"))
                        && cfg.size == intVal(expCfg.get("size"))
                        && cfg.amount == intVal(expCfg.get("amount"));
                boolean buy = str(in.get("side")).equals("BUY");
                double amount = Double.parseDouble(str(in.get("amount")));
                double price = Double.parseDouble(str(in.get("price")));
                double[] raw = Amounts.rawAmounts(buy, amount, price, cfg);
                String rawMaker = JsNumber.toString(raw[0]);
                String rawTaker = JsNumber.toString(raw[1]);
                String maker = Amounts.parseUnits(rawMaker, 6).toString();
                String taker = Amounts.parseUnits(rawTaker, 6).toString();
                boolean ok = cfgOk
                        && rawMaker.equals(str(exp.get("rawMakerAmt")))
                        && rawTaker.equals(str(exp.get("rawTakerAmt")))
                        && maker.equals(str(exp.get("makerAmount")))
                        && taker.equals(str(exp.get("takerAmount")));
                check(name, ok, "rawMaker=" + rawMaker + " (exp " + exp.get("rawMakerAmt") + ")"
                        + " rawTaker=" + rawTaker + " (exp " + exp.get("rawTakerAmt") + ")"
                        + " maker=" + maker + " (exp " + exp.get("makerAmount") + ")"
                        + " taker=" + taker + " (exp " + exp.get("takerAmount") + ")"
                        + " cfgOk=" + cfgOk);
            } catch (Exception e) {
                check(name, false, "exception: " + e);
            }
        }
    }

    private static void testLimitAmounts(Map<String, Object> root) {
        for (Object o : arr(root.get("limitAmounts"))) {
            Map<String, Object> c = obj(o);
            String name = "limitAmounts/" + str(c.get("name"));
            try {
                Map<String, Object> in = obj(c.get("input"));
                Map<String, Object> exp = obj(c.get("expected"));
                Map<String, Object> expCfg = obj(exp.get("roundConfig"));
                Amounts.RoundingConfig cfg = Amounts.roundingConfig(str(in.get("tickSize")));
                boolean cfgOk = cfg.price == intVal(expCfg.get("price"))
                        && cfg.size == intVal(expCfg.get("size"))
                        && cfg.amount == intVal(expCfg.get("amount"));
                boolean buy = str(in.get("side")).equals("BUY");
                double size = Double.parseDouble(str(in.get("size")));
                double price = Double.parseDouble(str(in.get("price")));
                double[] raw = Amounts.limitRawAmounts(buy, size, price, cfg);
                String rawMaker = JsNumber.toString(raw[0]);
                String rawTaker = JsNumber.toString(raw[1]);
                String maker = Amounts.parseUnits(rawMaker, 6).toString();
                String taker = Amounts.parseUnits(rawTaker, 6).toString();
                boolean ok = cfgOk
                        && rawMaker.equals(str(exp.get("rawMakerAmt")))
                        && rawTaker.equals(str(exp.get("rawTakerAmt")))
                        && maker.equals(str(exp.get("makerAmount")))
                        && taker.equals(str(exp.get("takerAmount")));
                check(name, ok, "rawMaker=" + rawMaker + " (exp " + exp.get("rawMakerAmt") + ")"
                        + " rawTaker=" + rawTaker + " (exp " + exp.get("rawTakerAmt") + ")"
                        + " maker=" + maker + " (exp " + exp.get("makerAmount") + ")"
                        + " taker=" + taker + " (exp " + exp.get("takerAmount") + ")"
                        + " cfgOk=" + cfgOk);
            } catch (Exception e) {
                check(name, false, "exception: " + e);
            }
        }
    }

    private static void testLimitOrderSignatures(Map<String, Object> root, BigInteger priv,
                                                 String fixedSalt, String fixedOrderTimestamp) {
        for (Object o : arr(root.get("limitOrderSignatures"))) {
            Map<String, Object> c = obj(o);
            String name = "limitOrderSignatures/" + str(c.get("name"));
            try {
                Map<String, Object> in = obj(c.get("input"));
                Map<String, Object> uo = obj(in.get("userOrder"));
                Map<String, Object> options = obj(in.get("options"));
                Map<String, Object> orderJson = obj(in.get("order"));
                Map<String, Object> domain = obj(in.get("domain"));

                boolean buy = str(uo.get("side")).equals("BUY");
                Amounts.RoundingConfig cfg = Amounts.roundingConfig(str(options.get("tickSize")));
                String[] amts = Amounts.limitOrderAmounts(
                        buy, num(uo.get("size")), num(uo.get("price")), cfg);
                boolean amtOk = amts[0].equals(str(orderJson.get("makerAmount")))
                        && amts[1].equals(str(orderJson.get("takerAmount")));

                OrderSigner.Order order = new OrderSigner.Order();
                order.salt = new BigInteger(fixedSalt);
                order.maker = str(orderJson.get("maker"));
                order.signer = str(orderJson.get("signer"));
                order.tokenId = new BigInteger(str(orderJson.get("tokenId")));
                order.makerAmount = new BigInteger(amts[0]);
                order.takerAmount = new BigInteger(amts[1]);
                order.side = buy ? 0 : 1;
                order.signatureType = intVal(orderJson.get("signatureType"));
                order.timestamp = new BigInteger(fixedOrderTimestamp);
                order.metadata = Hex.hexToBytes(str(orderJson.get("metadata")));
                order.builder = Hex.hexToBytes(str(orderJson.get("builder")));

                String sig = OrderSigner.signOrder(priv, order, str(domain.get("version")),
                        BigInteger.valueOf(intVal(in.get("chainId"))), str(in.get("exchangeContract")));
                String expected = str(obj(c.get("expected")).get("signature"));
                check(name, amtOk && sig.equals(expected),
                        "amtOk=" + amtOk + " sig=" + sig + " expected=" + expected);
            } catch (Exception e) {
                check(name, false, "exception: " + e);
            }
        }
    }

    private static void testOrderSignatures(Map<String, Object> root, BigInteger priv,
                                            String fixedSalt, String fixedOrderTimestamp) {
        for (Object o : arr(root.get("orderSignatures"))) {
            Map<String, Object> c = obj(o);
            String name = "orderSignatures/" + str(c.get("name"));
            try {
                Map<String, Object> in = obj(c.get("input"));
                Map<String, Object> umo = obj(in.get("userMarketOrder"));
                Map<String, Object> options = obj(in.get("options"));
                Map<String, Object> orderJson = obj(in.get("order"));
                Map<String, Object> domain = obj(in.get("domain"));

                boolean buy = str(umo.get("side")).equals("BUY");
                Amounts.RoundingConfig cfg = Amounts.roundingConfig(str(options.get("tickSize")));
                String[] amts = Amounts.marketOrderAmounts(
                        buy, num(umo.get("amount")), num(umo.get("price")), cfg);
                boolean amtOk = amts[0].equals(str(orderJson.get("makerAmount")))
                        && amts[1].equals(str(orderJson.get("takerAmount")));

                OrderSigner.Order order = new OrderSigner.Order();
                order.salt = new BigInteger(fixedSalt);
                order.maker = str(orderJson.get("maker"));
                order.signer = str(orderJson.get("signer"));
                order.tokenId = new BigInteger(str(orderJson.get("tokenId")));
                order.makerAmount = new BigInteger(amts[0]);
                order.takerAmount = new BigInteger(amts[1]);
                order.side = buy ? 0 : 1;
                order.signatureType = intVal(orderJson.get("signatureType"));
                order.timestamp = new BigInteger(fixedOrderTimestamp);
                order.metadata = Hex.hexToBytes(str(orderJson.get("metadata")));
                order.builder = Hex.hexToBytes(str(orderJson.get("builder")));

                String sig = OrderSigner.signOrder(priv, order, str(domain.get("version")),
                        BigInteger.valueOf(intVal(in.get("chainId"))), str(in.get("exchangeContract")));
                String expected = str(obj(c.get("expected")).get("signature"));
                check(name, amtOk && sig.equals(expected),
                        "amtOk=" + amtOk + " sig=" + sig + " expected=" + expected);
            } catch (Exception e) {
                check(name, false, "exception: " + e);
            }
        }
    }

    private static void testL1(Map<String, Object> root, BigInteger priv) {
        Map<String, Object> c = obj(root.get("l1Signature"));
        try {
            Map<String, Object> in = obj(c.get("input"));
            String sig = ClobAuth.sign(priv,
                    BigInteger.valueOf(intVal(in.get("chainId"))),
                    str(in.get("address")),
                    decStr(in.get("timestamp")),
                    BigInteger.valueOf(intVal(in.get("nonce"))));
            String expected = str(obj(c.get("expected")).get("signature"));
            check("l1Signature", sig.equals(expected), "sig=" + sig + " expected=" + expected);
        } catch (Exception e) {
            check("l1Signature", false, "exception: " + e);
        }
    }

    private static void testL2(Map<String, Object> root) {
        for (Object o : arr(root.get("l2Hmac"))) {
            Map<String, Object> c = obj(o);
            String name = "l2Hmac/" + str(c.get("name"));
            try {
                Map<String, Object> in = obj(c.get("input"));
                Object body = in.get("body");
                String sig = PolyHmac.buildSignature(
                        str(in.get("secret")),
                        (long) num(in.get("timestamp")),
                        str(in.get("method")),
                        str(in.get("requestPath")),
                        body == null ? null : str(body));
                String expected = str(obj(c.get("expected")).get("signature"));
                check(name, sig.equals(expected), "sig=" + sig + " expected=" + expected);
            } catch (Exception e) {
                check(name, false, "exception: " + e);
            }
        }
    }

    private static void testRedeem(Map<String, Object> root) {
        for (Object o : arr(root.get("redeemCalldata"))) {
            Map<String, Object> c = obj(o);
            String name = "redeemCalldata/" + str(c.get("name"));
            try {
                Map<String, Object> in = obj(c.get("input"));
                Map<String, Object> exp = obj(c.get("expected"));
                CallData call;
                if (str(in.get("kind")).equals("ctf")) {
                    List<Object> sets = arr(in.get("indexSets"));
                    BigInteger[] indexSets = new BigInteger[sets.size()];
                    for (int i = 0; i < sets.size(); i++) {
                        indexSets[i] = new BigInteger(decStr(sets.get(i)));
                    }
                    call = Redeem.ctfRedeem(
                            str(in.get("collateralToken")),
                            Hex.hexToBytes(str(in.get("parentCollectionId"))),
                            Hex.hexToBytes(str(in.get("conditionId"))),
                            indexSets,
                            Boolean.TRUE.equals(in.get("negRisk")));
                } else {
                    call = Redeem.v2Redeem(
                            str(in.get("conditionId")),
                            new BigInteger(decStr(in.get("outcomeIndex"))),
                            new BigInteger(decStr(in.get("amount"))));
                }
                boolean ok = call.target.equals(str(exp.get("target")))
                        && call.value.toString().equals(decStr(exp.get("value")))
                        && call.dataHex().equals(str(exp.get("data")));
                check(name, ok, "target=" + call.target + " data=" + call.dataHex()
                        + " expected target=" + exp.get("target") + " data=" + exp.get("data"));
            } catch (Exception e) {
                check(name, false, "exception: " + e);
            }
        }
    }

    private static void testBatch(Map<String, Object> root, BigInteger priv) {
        for (Object o : arr(root.get("batchSignatures"))) {
            Map<String, Object> c = obj(o);
            String name = "batchSignatures/" + str(c.get("name"));
            try {
                Map<String, Object> in = obj(c.get("input"));
                Map<String, Object> domain = obj(in.get("domain"));
                Map<String, Object> msg = obj(in.get("message"));
                List<CallData> calls = new ArrayList<>();
                for (Object co : arr(msg.get("calls"))) {
                    Map<String, Object> cm = obj(co);
                    calls.add(new CallData(
                            str(cm.get("target")),
                            new BigInteger(decStr(cm.get("value"))),
                            Hex.hexToBytes(str(cm.get("data")))));
                }
                String sig = BatchSigner.sign(priv,
                        BigInteger.valueOf(intVal(domain.get("chainId"))),
                        str(msg.get("wallet")),
                        new BigInteger(decStr(msg.get("nonce"))),
                        new BigInteger(decStr(msg.get("deadline"))),
                        calls);
                String expected = str(obj(c.get("expected")).get("signature"));
                check(name, sig.equals(expected), "sig=" + sig + " expected=" + expected);
            } catch (Exception e) {
                check(name, false, "exception: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            failures.add(name + " — " + detail);
            System.out.println("FAIL " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    private static String str(Object o) {
        return (String) o;
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    private static int intVal(Object o) {
        return ((Number) o).intValue();
    }

    /** JSON 문자열이면 그대로, 숫자면 JS식 10진 표기로. */
    private static String decStr(Object o) {
        if (o instanceof String) {
            return (String) o;
        }
        double d = num(o);
        if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return JsNumber.toString(d);
    }

    // ------------------------------------------------------------------
    /** 테스트 전용 최소 JSON 파서. */
    static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = parseValue();
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("trailing content at " + pos);
            }
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private Object parseValue() {
            skipWs();
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, pos)) {
                throw new IllegalArgumentException("expected " + literal + " at " + pos);
            }
            pos += literal.length();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + pos);
                }
                pos++;
                map.put(key, parseValue());
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' at " + (pos - 1));
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' at " + (pos - 1));
                }
            }
        }

        private String parseString() {
            if (s.charAt(pos) != '"') {
                throw new IllegalArgumentException("expected string at " + pos);
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("bad escape: " + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Double parseNumber() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
