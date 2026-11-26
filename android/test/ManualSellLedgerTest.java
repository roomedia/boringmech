// 수동 매도 장부·attempted 단일 소유자 테스트 — 공리적 분리 6단계 (감사 결합 6).
// 1) 전후 대조 픽스처: 종전 NativeCycle 인라인 합성(loadDurableJsonList +
//    ManualSell.append*WithArchive + 키별 쓰기)을 그대로 복제한 기준선과
//    ManualSellLedger를 같은 연산 순서로 돌려, 매 단계 뒤 저장소 전체가
//    같은지 대조한다.
// 2) 조회 거동: records·attemptedTokens의 합집합·필터·순서를 고정한다.
// 3) 구조 가드: NativeCycle.java가 수동 매도 장부 키와 회전 함수를 직접
//    만지지 않고 ManualSellLedger만 경유하는지 소스로 확인한다.
// 실행: android/test/run_manual_sell_ledger_test.sh
import com.roomedia.boringmech.write.ManualSell;
import com.roomedia.boringmech.write.ManualSellLedger;
import com.roomedia.boringmech.write.ModelJson;
import com.roomedia.boringmech.write.StopLossLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ManualSellLedgerTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void eq(String label, Object actual, Object expected) {
        String a = String.valueOf(actual);
        String e = String.valueOf(expected);
        if (a.equals(e)) {
            passed++;
        } else {
            failed++;
            if (failed <= 20) {
                System.out.println("FAIL: " + label + " expected=" + e + " got=" + a);
            }
        }
    }

    private static void check(String label, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + label);
        }
    }

    private static final String[] KEYS = {
        ManualSell.LEDGER_KEY, ManualSell.ARCHIVE_KEY,
        ManualSell.ATTEMPTED_KEY, ManualSell.ATTEMPTED_ARCHIVE_KEY,
    };

    private static String stateOf(StopLossLedger.InMemoryStore s) {
        StringBuilder b = new StringBuilder();
        for (String k : KEYS) {
            b.append(k).append('=');
            Object v = s.data.get(k);
            b.append(v == null ? "<absent>" : ModelJson.stringify(v)).append('\n');
        }
        return b.toString();
    }

    // ── 종전 인라인 합성 (기준선) ──────────────────────────────────
    private static List<Object> loadList(StopLossLedger.InMemoryStore s, String key) {
        Object v = s.read(key);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new ArrayList<>(l);
        }
        return new ArrayList<>();
    }

    private static void oldRecord(StopLossLedger.InMemoryStore s, Map<String, Object> rec) {
        ManualSell.CappedLists r = ManualSell.appendRecordWithArchive(
                loadList(s, ManualSell.ARCHIVE_KEY), loadList(s, ManualSell.LEDGER_KEY), rec);
        s.write(ManualSell.LEDGER_KEY, r.recent);
        s.write(ManualSell.ARCHIVE_KEY, r.archive);
    }

    private static void oldRecordAttempt(StopLossLedger.InMemoryStore s, String tokenId) {
        ManualSell.CappedLists r = ManualSell.appendAttemptedWithArchive(
                loadList(s, ManualSell.ATTEMPTED_ARCHIVE_KEY),
                loadList(s, ManualSell.ATTEMPTED_KEY), tokenId);
        s.write(ManualSell.ATTEMPTED_KEY, r.recent);
        s.write(ManualSell.ATTEMPTED_ARCHIVE_KEY, r.archive);
    }

    private static Map<String, Object> rec(String slug, String tokenId, double pnl) {
        Map<String, Object> r = StopLossLedger.record(
                slug, null, tokenId, "Yes", 0.80, 0.50, 10.0, 5.0, pnl,
                "2026-11-27T00:00:00.000Z");
        r.put("kind", "manual");
        return r;
    }

    public static void main(String[] args) throws Exception {
        StopLossLedger.InMemoryStore oldStore = new StopLossLedger.InMemoryStore();
        StopLossLedger.InMemoryStore newStore = new StopLossLedger.InMemoryStore();
        ManualSellLedger ledger = new ManualSellLedger(newStore);

        // 교차 경로 연산 순서 — 매 단계 뒤 저장소 전체를 대조한다.
        int step = 0;
        oldRecord(oldStore, rec("m-a", "tok-a", -3.0));
        ledger.record(rec("m-a", "tok-a", -3.0));
        eq("step " + (++step) + ": first record", stateOf(newStore), stateOf(oldStore));

        oldRecordAttempt(oldStore, "tok-a");
        ledger.recordAttempt("tok-a");
        eq("step " + (++step) + ": attempt after record", stateOf(newStore), stateOf(oldStore));

        oldRecordAttempt(oldStore, "tok-a");
        ledger.recordAttempt("tok-a");
        eq("step " + (++step) + ": duplicate attempt is a no-op", stateOf(newStore), stateOf(oldStore));

        oldRecordAttempt(oldStore, "");
        ledger.recordAttempt("");
        oldRecordAttempt(oldStore, null);
        ledger.recordAttempt(null);
        eq("step " + (++step) + ": empty/null attempt is a no-op", stateOf(newStore), stateOf(oldStore));

        // 기록 캡 회전 — 205건을 넣어 최근 200 + 아카이브 5를 만든다.
        for (int i = 0; i < 205; i++) {
            oldRecord(oldStore, rec("m-r" + i, "rtok" + i, -1.0));
            ledger.record(rec("m-r" + i, "rtok" + i, -1.0));
        }
        eq("step " + (++step) + ": record rotation at cap", stateOf(newStore), stateOf(oldStore));
        eq("records: recent size", loadList(newStore, ManualSell.LEDGER_KEY).size(), 200);
        eq("records: archive size", loadList(newStore, ManualSell.ARCHIVE_KEY).size(), 6);

        // attempted 캡 회전 — 아카이브로 넘어가 보존된다.
        for (int i = 0; i < 205; i++) {
            oldRecordAttempt(oldStore, "atok" + i);
            ledger.recordAttempt("atok" + i);
        }
        eq("step " + (++step) + ": attempt rotation at cap", stateOf(newStore), stateOf(oldStore));
        eq("attempted: recent size", loadList(newStore, ManualSell.ATTEMPTED_KEY).size(), 200);
        eq("attempted: archive size", loadList(newStore, ManualSell.ATTEMPTED_ARCHIVE_KEY).size(), 6);
        // 아카이브에 있는 토큰의 재시도도 무동작 (합집합 기준 중복).
        String before = stateOf(newStore);
        ledger.recordAttempt("atok0");
        eq("attempt in archive is a no-op", stateOf(newStore), before);

        // 손상·비배열 저장값은 빈 목록으로 보고 새 값으로 덮는다.
        for (StopLossLedger.InMemoryStore s : new StopLossLedger.InMemoryStore[]{oldStore, newStore}) {
            s.data.put(ManualSell.LEDGER_KEY, "not-a-list");
            s.data.put(ManualSell.ATTEMPTED_KEY, new LinkedHashMap<>());
        }
        oldRecord(oldStore, rec("m-b", "tok-b", 1.5));
        ledger.record(rec("m-b", "tok-b", 1.5));
        oldRecordAttempt(oldStore, "tok-b");
        ledger.recordAttempt("tok-b");
        eq("step " + (++step) + ": corrupt values treated as empty", stateOf(newStore), stateOf(oldStore));

        // ── 조회 거동 ──────────────────────────────────────────────
        StopLossLedger.InMemoryStore qs = new StopLossLedger.InMemoryStore();
        ManualSellLedger q = new ManualSellLedger(qs);
        eq("query: records empty", q.records().size(), 0);
        eq("query: attempted empty", q.attemptedTokens().size(), 0);

        List<Object> arc = new ArrayList<>();
        arc.add(rec("m-old", "tok-old", -2.0));
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("token_id", "tok-bad"); // realized_pnl 없음 — 집계에서 제외
        arc.add(bad);
        qs.data.put(ManualSell.ARCHIVE_KEY, arc);
        List<Object> recent = new ArrayList<>();
        recent.add(rec("m-new", "tok-new", 3.0));
        qs.data.put(ManualSell.LEDGER_KEY, recent);
        List<Object> records = q.records();
        eq("query: records filtered size", records.size(), 2);
        eq("query: records archive first",
                ((Map<?, ?>) records.get(0)).get("token_id"), "tok-old");
        eq("query: records recent last",
                ((Map<?, ?>) records.get(1)).get("token_id"), "tok-new");

        List<Object> aArc = new ArrayList<>(List.of("a1", "a2"));
        List<Object> aRec = new ArrayList<>(List.of("a2", "a3", 7, ""));
        qs.data.put(ManualSell.ATTEMPTED_ARCHIVE_KEY, aArc);
        qs.data.put(ManualSell.ATTEMPTED_KEY, aRec);
        eq("query: attempted union", q.attemptedTokens(), List.of("a1", "a2", "a3"));

        // ── 구조 가드 ──────────────────────────────────────────────
        String nativeCycle = Files.readString(Path.of(args[0]));
        for (String banned : new String[]{
                "appendRecordWithArchive", "appendAttemptedWithArchive",
                "ManualSell.LEDGER_KEY", "ManualSell.ARCHIVE_KEY",
                "ManualSell.ATTEMPTED_KEY", "ManualSell.ATTEMPTED_ARCHIVE_KEY"}) {
            check("guard: NativeCycle has no " + banned, !nativeCycle.contains(banned));
        }
        check("guard: NativeCycle wires ManualSellLedger",
                nativeCycle.contains("new ManualSellLedger("));
        // 저장 백엔드는 종전 인라인 합성과 같은 env durable 어댑터여야 한다
        // (ledgerStore로 직접 붙이면 백엔드가 갈리는 환경에서 기록이 샌다).
        check("guard: ManualSellLedger is backed by the env durable adapter",
                nativeCycle.contains("new ManualSellLedger(new StopLossLedger.Store()"));
        check("guard: NativeCycle reads via records()",
                nativeCycle.contains("manualSellLedger.records()"));
        String owner = Files.readString(Path.of(args[1]));
        check("guard: ManualSellLedger implements the fixed recorder interface",
                owner.contains("implements StopLossSeller.ManualSellRecorder"));

        System.out.println("ManualSellLedgerTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
