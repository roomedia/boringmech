import com.roomedia.boringmech.engine.BoughtReconcile;
import com.roomedia.boringmech.engine.CandidateVerifier;
import com.roomedia.boringmech.engine.FillJudge;
import com.roomedia.boringmech.engine.Js;
import com.roomedia.boringmech.engine.PositionBook;
import com.roomedia.boringmech.engine.RedeemPlan;
import com.roomedia.boringmech.engine.ScanFilter;
import com.roomedia.boringmech.engine.SettledBaseline;
import com.roomedia.boringmech.engine.StopLoss;
import com.roomedia.boringmech.engine.TempNo;
import com.roomedia.boringmech.engine.TradeSettings;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 네이티브 이관 3단계 골든 대조 러너.
 * fixtures.json 전 케이스를 네이티브 판정 이식에 통과시켜 expected.json
 * (engine.js 원본 실행 결과)과 구조 단위로 대조한다.
 * 숫자 비교는 == (반올림·연산 순서까지 같은 double 연산의 재현이 기준이라
 * 비트 단위 일치를 요구하되, JSON 왕복에서 사라지는 -0.0 부호만 동등 취급).
 * 비유한 수치는 expected 쪽 센티넬 문자열("__NaN__" 등)로 대조한다.
 *
 * 실행: run.sh (인자: native-golden3 디렉터리 경로, 기본 ".")
 */
public class Golden3Test {
    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        Map<String, Object> fixtures = asMap(Json.parse(Files.readString(dir.resolve("fixtures.json"))));
        Map<String, Object> expected = asMap(Json.parse(Files.readString(dir.resolve("expected.json"))));
        Map<String, Object> expectedCases = asMap(expected.get("cases"));

        for (Object cObj : asList(fixtures.get("cases"))) {
            Map<String, Object> c = asMap(cObj);
            String name = (String) c.get("name");
            String kind = (String) c.get("kind");
            Map<String, Object> inp = revive(asMap(c.get("input")));
            Object exp = expectedCases.get(name);
            runCase(name, kind, inp, exp);
        }
        runSyntheticTests();

        System.out.println("----");
        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- 케이스 실행 ---

    private interface Body {
        Object run() throws Exception;
    }

    private static void runCase(String name, String kind, Map<String, Object> inp, Object exp) {
        int before = failures;
        boolean expectThrow = exp instanceof Map
                && Boolean.TRUE.equals(((Map<?, ?>) exp).get("throw"))
                && ((Map<?, ?>) exp).size() == 1;
        Object actual = null;
        Exception thrown = null;
        try {
            actual = dispatch(kind, inp);
        } catch (Exception e) {
            thrown = e;
        }
        checks++;
        if (expectThrow) {
            if (thrown == null) {
                failures++;
                System.out.println("  FAIL " + name + ": 예외를 기대했는데 결과가 나옴: " + actual);
            }
        } else if (thrown != null) {
            failures++;
            System.out.println("  FAIL " + name + ": 예외 발생 " + thrown);
        } else if (!deepEq(exp, actual)) {
            failures++;
            System.out.println("  FAIL " + name + ":\n    기대 " + exp + "\n    실제 " + actual);
        }
        System.out.println((failures == before ? "PASS " : "FAIL ") + name);
    }

    private static Object dispatch(String kind, Map<String, Object> inp) throws Exception {
        switch (kind) {
            case "scan": {
                Map<String, Integer> stats = new LinkedHashMap<>();
                List<ScanFilter.Candidate> cands = ScanFilter.scanCandidates(
                        asList(inp.get("markets")),
                        stringSet(inp.get("heldSlugs")),
                        stringSet(inp.get("boughtSlugs")),
                        stats,
                        num(inp.get("nowMs")).longValue());
                List<Object> candModels = new ArrayList<>();
                for (ScanFilter.Candidate c : cands) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("slug", c.slug);
                    m.put("question", c.question);
                    m.put("side", c.side);
                    m.put("price", c.price);
                    m.put("endDate", c.endDate);
                    candModels.add(m);
                }
                Map<String, Object> statsModel = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : stats.entrySet()) {
                    statsModel.put(e.getKey(), e.getValue().doubleValue());
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("candidates", candModels);
                out.put("stats", statsModel);
                return out;
            }
            case "verify": {
                Map<String, Object> candMap = asMap(inp.get("cand"));
                ScanFilter.Candidate cand = new ScanFilter.Candidate(
                        (String) candMap.get("slug"),
                        (String) candMap.get("question"),
                        (String) candMap.get("side"),
                        num(candMap.get("price")),
                        (String) candMap.get("endDate"));
                CandidateVerifier.Result r = CandidateVerifier.verifyCandidate(
                        cand,
                        num(inp.get("bet")),
                        slug -> inp.get("gammaPage"),
                        new CandidateVerifier.BookSource() {
                            @Override
                            public Object getOrderBook(String tokenId) throws Exception {
                                if (Boolean.TRUE.equals(inp.get("bookError"))) {
                                    throw new Exception("book boom");
                                }
                                return inp.get("book");
                            }

                            @Override
                            public double getTickSize(String tokenId) {
                                return num(inp.get("tickSizeFallback"));
                            }
                        },
                        num(inp.get("nowMs")).longValue());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", r.ok);
                if (r.ok) {
                    out.put("tokenId", r.tokenId);
                    out.put("limitPrice", r.limitPrice);
                    out.put("estShares", r.estShares);
                    out.put("tickSize", r.tickSize);
                    out.put("negRisk", r.negRisk);
                } else {
                    out.put("reason", r.reason);
                }
                return out;
            }
            case "slVerdict": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("verdict", StopLoss.verdict(num(inp.get("entry")), num(inp.get("current"))));
                return out;
            }
            case "pickIndex": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("index", (double) StopLoss.pickOutcomeIndex(asList(inp.get("outcomes")), inp.get("row")));
                return out;
            }
            case "gammaParse": {
                StopLoss.GammaPrices gp = StopLoss.parseGammaPrices(inp.get("page"));
                Map<String, Object> out = new LinkedHashMap<>();
                if (gp == null) {
                    out.put("parsed", null);
                } else {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("outcomes", new ArrayList<>(gp.outcomes));
                    List<Object> prices = new ArrayList<>();
                    for (double d : gp.prices) {
                        prices.add(d);
                    }
                    p.put("prices", prices);
                    out.put("parsed", p);
                }
                return out;
            }
            case "slEvaluate": {
                // 기온 전용 손절 폭은 태그 방식 — 입력의 매수 기록으로 판정한다 (원본과 동일).
                @SuppressWarnings("unchecked")
                Map<String, Object> rowMap = (Map<String, Object>) inp.get("row");
                Object slugRaw = rowMap == null ? null : rowMap.get("slug");
                boolean tempTagged = TempNo.isTempTagged(
                        asListOrNull(inp.get("bought")),
                        slugRaw instanceof String ? (String) slugRaw : null);
                StopLoss.Eval ev = StopLoss.evaluate(
                        inp.get("row"), inp.get("page1"), inp.get("page2"), tempTagged);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("action", ev.action);
                if (ev.current != null) {
                    out.put("current", ev.current);
                }
                return out;
            }
            case "plan": {
                RedeemPlan.Plan plan = RedeemPlan.planRedeems(asListOrNull(inp.get("entries")));
                Map<String, Object> out = new LinkedHashMap<>();
                List<Object> targets = new ArrayList<>();
                for (RedeemPlan.Target t : plan.targets) {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("condition_id", t.conditionId);
                    tm.put("wins", t.wins);
                    tm.put("losses", t.losses);
                    tm.put("expected_usd", t.expectedUsd);
                    targets.add(tm);
                }
                out.put("targets", targets);
                out.put("worthless", plan.worthless);
                out.put("errors", new ArrayList<>(plan.errors));
                return out;
            }
            case "verifyRecovery": {
                RedeemPlan.Recovery rec = RedeemPlan.verifyRecovery(
                        num(inp.get("before")), num(inp.get("after")), num(inp.get("expected")));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("delta_usd", rec.deltaUsd);
                out.put("expected_usd", rec.expectedUsd);
                out.put("tolerance", rec.tolerance);
                out.put("status", rec.status);
                return out;
            }
            case "balanceGate": {
                List<BigInteger> balances = null;
                if (inp.get("balances") instanceof List) {
                    balances = new ArrayList<>();
                    for (Object b : asList(inp.get("balances"))) {
                        balances.add(new BigInteger((String) b));
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("gate", RedeemPlan.redeemBalanceGate(balances));
                return out;
            }
            case "normalizeCid": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("cid", RedeemPlan.normalizeConditionId(inp.get("raw")));
                return out;
            }
            case "toBytes31": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("hex", RedeemPlan.toBytes31((String) inp.get("conditionId")));
                return out;
            }
            case "redeemCallCtf":
                return RedeemPlan.buildCtfRedeemCall(
                        (String) inp.get("conditionId"), Boolean.TRUE.equals(inp.get("negRisk")));
            case "redeemCallV2":
                return RedeemPlan.buildV2RedeemCall(
                        (String) inp.get("conditionId"),
                        new BigInteger((String) inp.get("outcomeIndex")),
                        new BigInteger((String) inp.get("amount")));
            case "judgeFill": {
                FillJudge.Fill f = FillJudge.judgeFill(inp.get("resp"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("filled", f.filled);
                out.put("kind", f.kind);
                out.put("status", f.status);
                if (f.error != null) {
                    out.put("error", f.error);
                }
                return out;
            }
            case "reconcile": {
                BoughtReconcile.Reconcile r = BoughtReconcile.reconcileBought(
                        objectSet(inp.get("bought")), objectSet(inp.get("onChain")), objectSet(inp.get("held")));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("kept", r.kept);
                out.put("dropped", r.dropped);
                return out;
            }
            case "buySlugs": {
                List<Object> pages = asList(inp.get("pages"));
                int[] qi = {0};
                BoughtReconcile.BuySlugs r = BoughtReconcile.fetchBuySlugs("0xFUNDER",
                        (funder, cursor) -> {
                            Object p = pages.get(qi[0]++);
                            if (p instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) p).get("$throw"))) {
                                throw new Exception("fetch boom");
                            }
                            return p;
                        });
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("slugs", new ArrayList<>(r.slugs));
                out.put("complete", r.complete);
                return out;
            }
            case "sizing": {
                PositionBook.Sizing s = PositionBook.sizing(
                        num(inp.get("cash")), num(inp.get("equity")), num(inp.get("openCount")));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("cashFloor", s.cashFloor);
                out.put("available", s.available);
                out.put("bet", s.bet);
                out.put("slots", s.slots);
                return out;
            }
            case "betUsd": {
                Object b = inp.get("betUsd");
                TradeSettings cfg = b instanceof Double
                        ? TradeSettings.parse("{\"betUsd\":" + b + "}")
                        : TradeSettings.defaults();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("bet", PositionBook.betUsdFor(cfg));
                return out;
            }
            case "settledParse": {
                SettledBaseline.Baseline b = SettledBaseline.parse((String) inp.get("raw"));
                Map<String, Object> out = new LinkedHashMap<>();
                if (b == null) {
                    out.put("base", null);
                } else {
                    Map<String, Object> bm = new LinkedHashMap<>();
                    bm.put("count", b.count);
                    bm.put("realized", b.realized);
                    out.put("base", bm);
                }
                return out;
            }
            case "settledNotice": {
                Map<String, Object> baseMap = asMap(inp.get("base"));
                SettledBaseline.Baseline base = baseMap == null ? null
                        : new SettledBaseline.Baseline(
                                asDoubleOrNull(baseMap.get("count")), asDoubleOrNull(baseMap.get("realized")));
                SettledBaseline.Notice n = SettledBaseline.noticeFor(base, inp.get("snap"));
                Map<String, Object> out = new LinkedHashMap<>();
                if (n == null) {
                    out.put("notice", null);
                } else {
                    Map<String, Object> nm = new LinkedHashMap<>();
                    nm.put("count", n.count);
                    nm.put("realizedDelta", n.realizedDelta);
                    out.put("notice", nm);
                }
                return out;
            }
            case "slLedger": {
                List<Object> recentIn = StopLoss.parseStopLossList((String) inp.get("recentRaw"));
                List<Object> archiveIn = StopLoss.parseStopLossList((String) inp.get("archiveRaw"));
                StopLoss.Ledger ledger = StopLoss.appendRecord(recentIn, archiveIn, inp.get("rec"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("recent", ledger.recent);
                out.put("archive", ledger.archive);
                out.put("merged", ledger.merged);
                return out;
            }
            case "slParse": {
                List<Object> merged = new ArrayList<>(
                        StopLoss.parseStopLossList((String) inp.get("archiveRaw")));
                merged.addAll(StopLoss.parseStopLossList((String) inp.get("recentRaw")));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("merged", merged);
                return out;
            }
            case "attempted": {
                Set<Object> set = StopLoss.parseAttempted((String) inp.get("raw"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("tokens", StopLoss.addAttempted(set, inp.get("add")));
                return out;
            }
            case "ingest": {
                Set<Object> done = new LinkedHashSet<>();
                for (Object h : asListOrEmpty(inp.get("history"))) {
                    Object cid = Js.field(h, "condition_id");
                    if (cid != Js.UNDEFINED) {
                        done.add(cid);
                    }
                }
                done.addAll(asListOrEmpty(inp.get("closed")));
                RedeemPlan.Ingest ing = RedeemPlan.ingest(
                        asListOrNull(inp.get("pending")),
                        done,
                        asListOrNull(inp.get("winRows")),
                        asListOrNull(inp.get("lostRows")),
                        asListOrNull(inp.get("closedRows")));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("pending", ing.pending);
                out.put("changed", ing.changed);
                return out;
            }
            case "corrupted": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("corrupted", BoughtReconcile.boughtCorrupted((String) inp.get("raw")));
                return out;
            }
            case "failsafe": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("hit", FillJudge.isFailsafe((String) inp.get("text")));
                return out;
            }
            default:
                throw new IllegalArgumentException("알 수 없는 kind: " + kind);
        }
    }

    // --- 합성 검증 (fixture 범위 밖 — 원본 의미론 기준 손계산) ---

    private static void runSyntheticTests() {
        int before = failures;

        // 손절 원장 회전: 최근 200건이 차 있으면 가장 오래된 건이
        // {token_id, realized_pnl}만 남아 아카이브로 이동한다.
        List<Object> recent = new ArrayList<>();
        for (int i = 0; i < StopLoss.RECENT_CAP; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("token_id", "tok-" + i);
            r.put("realized_pnl", (double) i);
            r.put("slug", "m-" + i);
            recent.add(r);
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("token_id", "tok-new");
        rec.put("realized_pnl", -1.5);
        StopLoss.Ledger ledger = StopLoss.appendRecord(recent, new ArrayList<>(), rec);
        check("ledger.cap.recentSize", ledger.recent.size() == StopLoss.RECENT_CAP,
                "size=" + ledger.recent.size());
        check("ledger.cap.archiveSize", ledger.archive.size() == 1,
                "size=" + ledger.archive.size());
        Object evicted = ledger.archive.get(0);
        check("ledger.cap.evictedSlim",
                "tok-0".equals(Js.field(evicted, "token_id"))
                        && Js.fieldNum(evicted, "realized_pnl") == 0
                        && "m-0".equals(Js.field(evicted, "slug")),
                String.valueOf(evicted));
        check("ledger.cap.firstKept",
                "tok-1".equals(Js.field(ledger.recent.get(0), "token_id")),
                String.valueOf(ledger.recent.get(0)));

        // 시도 토큰 캡: 200개가 차 있으면 가장 오래된 것이 밀려난다.
        Set<Object> attempted = new LinkedHashSet<>();
        for (int i = 0; i < StopLoss.ATTEMPTED_CAP; i++) {
            attempted.add("a-" + i);
        }
        List<Object> after = StopLoss.addAttempted(attempted, "a-new");
        check("attempted.cap.size", after.size() == StopLoss.ATTEMPTED_CAP, "size=" + after.size());
        check("attempted.cap.dropped", !after.contains("a-0") && after.contains("a-new"),
                after.subList(0, 2).toString());

        System.out.println((failures == before ? "PASS " : "FAIL ") + "합성 검증 (원장·시도 캡 회전)");
    }

    // --- 비교 헬퍼 ---

    private static boolean deepEq(Object exp, Object act) {
        if (exp instanceof String) {
            String s = (String) exp;
            if (s.equals("__NaN__")) {
                return act instanceof Double && Double.isNaN((Double) act);
            }
            if (s.equals("__Inf__")) {
                return act instanceof Double && (Double) act == Double.POSITIVE_INFINITY;
            }
            if (s.equals("__-Inf__")) {
                return act instanceof Double && (Double) act == Double.NEGATIVE_INFINITY;
            }
            if (s.equals("__UNDEF__")) {
                return act == null || act == Js.UNDEFINED;
            }
            return s.equals(act);
        }
        if (exp == null) {
            return act == null;
        }
        if (exp instanceof Double) {
            if (!(act instanceof Number)) {
                return false;
            }
            double e = (Double) exp;
            double a = ((Number) act).doubleValue();
            return e == a || (Double.isNaN(e) && Double.isNaN(a));
        }
        if (exp instanceof Boolean) {
            return exp.equals(act);
        }
        if (exp instanceof List) {
            List<?> actList = act instanceof Set ? new ArrayList<>((Set<?>) act)
                    : act instanceof List ? (List<?>) act : null;
            if (actList == null) {
                return false;
            }
            List<?> expList = (List<?>) exp;
            if (expList.size() != actList.size()) {
                return false;
            }
            for (int i = 0; i < expList.size(); i++) {
                if (!deepEq(expList.get(i), actList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (exp instanceof Map) {
            if (!(act instanceof Map)) {
                return false;
            }
            Map<?, ?> expMap = (Map<?, ?>) exp;
            Map<?, ?> actMap = (Map<?, ?>) act;
            for (Map.Entry<?, ?> e : expMap.entrySet()) {
                Object actVal = actMap.containsKey(e.getKey()) ? actMap.get(e.getKey()) : Js.UNDEFINED;
                if (!deepEq(e.getValue(), actVal)) {
                    return false;
                }
            }
            for (Object k : actMap.keySet()) {
                if (!expMap.containsKey(k) && actMap.get(k) != Js.UNDEFINED) {
                    return false;
                }
            }
            return true;
        }
        return exp.equals(act);
    }

    /** 입력 센티넬("__NaN__" 등)을 실제 double 값으로 되돌린다. */
    @SuppressWarnings("unchecked")
    private static <T> T revive(T v) {
        if (v instanceof String) {
            String s = (String) v;
            if (s.equals("__NaN__")) {
                return (T) Double.valueOf(Double.NaN);
            }
            if (s.equals("__Inf__")) {
                return (T) Double.valueOf(Double.POSITIVE_INFINITY);
            }
            if (s.equals("__-Inf__")) {
                return (T) Double.valueOf(Double.NEGATIVE_INFINITY);
            }
            return v;
        }
        if (v instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                m.put(String.valueOf(e.getKey()), revive(e.getValue()));
            }
            return (T) m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object e : (List<?>) v) {
                l.add(revive(e));
            }
            return (T) l;
        }
        return v;
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL " + name + ": " + detail);
        }
    }

    private static Double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }

    private static Double asDoubleOrNull(Object o) {
        return o instanceof Double ? (Double) o : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asListOrNull(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    private static List<Object> asListOrEmpty(Object o) {
        List<Object> l = asListOrNull(o);
        return l != null ? l : new ArrayList<>();
    }

    private static Set<String> stringSet(Object o) {
        Set<String> s = new LinkedHashSet<>();
        if (o instanceof List) {
            for (Object e : (List<?>) o) {
                s.add((String) e);
            }
        }
        return s;
    }

    private static Set<Object> objectSet(Object o) {
        Set<Object> s = new LinkedHashSet<>();
        if (o instanceof List) {
            s.addAll((List<?>) o);
        }
        return s;
    }
}
