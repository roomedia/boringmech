// 기매수 장부 단일 소유자 테스트 — 공리적 분리 4단계 (감사 결합 4).
// 1) 거동: BoughtLedger가 종전 NativeCycle 전용 메서드
//    (boughtSlugsObjects·boughtSlugStrings·markBought)와 같은 읽기·
//    쓰기·검증 거동을 내는지 인메모리 Store로 대조한다.
// 2) 구조 가드: NativeCycle.java에 "bm_bought" 리터럴이 없고 전 호출
//    지점이 boughtLedger 경유인지 소스로 확인한다.
// 실행: android/test/run_bought_ledger_test.sh
import com.roomedia.boringmech.engine.BoughtLedger;
import com.roomedia.boringmech.write.ModelJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BoughtLedgerTest {
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

    /** 인메모리 Store — dropWrites면 쓰기를 버려 검증 실패 로그를 재현한다. */
    private static final class MemStore implements BoughtLedger.Store {
        final Map<String, String> map = new HashMap<>();
        final List<String> logs = new ArrayList<>();
        boolean dropWrites = false;

        @Override
        public String read(String key) {
            return map.get(key);
        }

        @Override
        public void write(String key, String value) {
            if (!dropWrites) {
                map.put(key, value);
            }
        }

        @Override
        public void log(String body) {
            logs.add(body);
        }
    }

    public static void main(String[] args) throws Exception {
        // ── entries ──
        MemStore s = new MemStore();
        BoughtLedger ledger = new BoughtLedger(s);
        eq("entries: missing is empty", ledger.entries().size(), 0);
        s.map.put(BoughtLedger.KEY, "[]");
        eq("entries: empty array", ledger.entries().size(), 0);
        s.map.put(BoughtLedger.KEY, "[\"a\",\"b\"]");
        eq("entries: two strings", ledger.entries().toString(), "[a, b]");
        s.map.put(BoughtLedger.KEY, "{broken");
        eq("entries: broken is empty", ledger.entries().size(), 0);
        s.map.put(BoughtLedger.KEY, "{\"a\":1}");
        eq("entries: non-array is empty", ledger.entries().size(), 0);

        // ── entrySet / slugStrings ──
        s.map.put(BoughtLedger.KEY, "[\"a\",\"a\",\"b\"]");
        eq("entrySet: duplicates collapse", ledger.entrySet().size(), 2);
        s.map.put(BoughtLedger.KEY,
                "[\"a\",{\"slug\":\"t1\",\"strategy\":\"temp_no\"},{\"slug\":\"t2\"},42,{\"x\":1}]");
        eq("slugStrings: both entry shapes", ledger.slugStrings().toString(), "[a, t1, t2]");

        // ── isTempTagged (기온 슬러그 패턴 + temp_no 태그가 둘 다 있어야 한다) ──
        String tempSlug = "highest-temperature-in-taipei-on-november-27-2026";
        s.map.put(BoughtLedger.KEY,
                "[\"a\",{\"slug\":\"" + tempSlug + "\",\"strategy\":\"temp_no\"},{\"slug\":\"t2\"}]");
        eq("tempTagged: tagged temp slug", ledger.isTempTagged(tempSlug), true);
        eq("tempTagged: untagged entry", ledger.isTempTagged("t2"), false);
        eq("tempTagged: missing slug", ledger.isTempTagged("zz"), false);
        s.map.put(BoughtLedger.KEY,
                "[{\"slug\":\"" + tempSlug + "\"},{\"slug\":\"" + tempSlug + "\",\"strategy\":\"temp_no\"}]");
        eq("tempTagged: tag behind duplicate", ledger.isTempTagged(tempSlug), true);
        s.map.put(BoughtLedger.KEY, "[{\"slug\":\"plain\",\"strategy\":\"temp_no\"}]");
        eq("tempTagged: tag on non-temp slug", ledger.isTempTagged("plain"), false);

        // ── corrupted ──
        MemStore c = new MemStore();
        BoughtLedger cl = new BoughtLedger(c);
        eq("corrupted: missing", cl.corrupted(), false);
        c.map.put(BoughtLedger.KEY, "");
        eq("corrupted: empty", cl.corrupted(), false);
        c.map.put(BoughtLedger.KEY, "[\"a\"]");
        eq("corrupted: intact", cl.corrupted(), false);
        c.map.put(BoughtLedger.KEY, "{broken");
        eq("corrupted: broken json", cl.corrupted(), true);
        c.map.put(BoughtLedger.KEY, "{\"a\":1}");
        eq("corrupted: non-array", cl.corrupted(), true);

        // ── mark ──
        MemStore m = new MemStore();
        BoughtLedger ml = new BoughtLedger(m);
        m.map.put(BoughtLedger.KEY, "[\"a\",\"b\"]");
        ml.mark("c", null);
        eq("mark: main appends string", m.map.get(BoughtLedger.KEY), "[\"a\",\"b\",\"c\"]");
        ml.mark("a", null);
        eq("mark: re-mark moves to end once", m.map.get(BoughtLedger.KEY), "[\"b\",\"c\",\"a\"]");
        ml.mark("t", "temp_no");
        eq("mark: temp writes tagged object", m.map.get(BoughtLedger.KEY),
                "[\"b\",\"c\",\"a\",{\"slug\":\"t\",\"strategy\":\"temp_no\"}]");
        ml.mark("t", null);
        eq("mark: main re-mark drops tag", m.map.get(BoughtLedger.KEY), "[\"b\",\"c\",\"a\",\"t\"]");
        eq("mark: no failure logs", m.logs.size(), 0);

        // ── mark 검증 실패 로그 ──
        MemStore f = new MemStore();
        f.dropWrites = true;
        f.map.put(BoughtLedger.KEY, "[\"a\"]");
        BoughtLedger fl = new BoughtLedger(f);
        fl.mark("z", null);
        eq("mark: failed write logs once", f.logs.size(), 1);
        eq("mark: failure log text", f.logs.get(0),
                "매수 기록 저장 확인 실패 z — 저장소 쓰기가 반영되지 않았습니다");

        // ── writeEntries 왕복 ──
        MemStore w = new MemStore();
        BoughtLedger wl = new BoughtLedger(w);
        List<Object> kept = new ArrayList<>();
        kept.add("x");
        Map<String, Object> tagged = new LinkedHashMap<>();
        tagged.put("slug", "t");
        tagged.put("strategy", "temp_no");
        kept.add(tagged);
        wl.writeEntries(kept);
        eq("writeEntries: raw form", w.map.get(BoughtLedger.KEY),
                ModelJson.stringify(kept));
        eq("writeEntries: round trip slugs", wl.slugStrings().toString(), "[x, t]");

        // ── 구조 가드 ──
        String nativeCycle = Files.readString(Path.of(args[0]));
        eq("guard: no bm_bought literal in NativeCycle",
                nativeCycle.contains("\"bm_bought\""), false);
        eq("guard: no boughtSlugsObjects", nativeCycle.contains("boughtSlugsObjects"), false);
        eq("guard: no boughtSlugStrings", nativeCycle.contains("boughtSlugStrings"), false);
        eq("guard: mark via ledger", nativeCycle.contains("boughtLedger.mark("), true);
        eq("guard: slugs via ledger", nativeCycle.contains("boughtLedger.slugStrings()"), true);
        eq("guard: corrupted via ledger", nativeCycle.contains("boughtLedger.corrupted()"), true);
        eq("guard: reconcile via ledger", nativeCycle.contains("boughtLedger.entrySet()"), true);
        String ledgerSrc = Files.readString(Path.of(args[1]));
        eq("guard: key literal lives in BoughtLedger",
                ledgerSrc.contains("KEY = \"bm_bought\""), true);

        System.out.println("BoughtLedgerTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
