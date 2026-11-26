// 도시 점수 섀도우 기록 JVM 테스트 — ShadowLog 순수 로직(도시 파싱·현지
// 버킷·점수 조회·사이클 기록·일별 롤업·보관 상한)과 JS engine.js에 내장된
// 점수표·시간대 표와의 패리티를 대조한다.
// 실행: android/test/run_shadow_log_test.sh
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.ScanFilter;
import com.roomedia.boringmech.engine.ShadowLog;
import com.roomedia.boringmech.write.ModelJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ShadowLogTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void checkEq(String name, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name + " expected=" + expected + " got=" + actual);
        }
    }

    private static String slug(String city, int temp) {
        return "highest-temperature-in-" + city + "-on-october-6-2026-" + temp + "c";
    }

    private static final String END = "2026-10-06T12:00:00Z";
    private static final long T0 = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli();

    private static ShadowLog.CandInput cand(String city, int temp, double price) {
        return ShadowLog.CandInput.of(new ScanFilter.Candidate(
                slug(city, temp), "", "no", price, END, "temp_no", 0.9, 0.998, null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowOf(Map<String, Object> rec, String slugPart) {
        for (Object o : (List<Object>) rec.get("cands")) {
            Map<String, Object> row = (Map<String, Object>) o;
            if (String.valueOf(row.get("slug")).contains(slugPart)) {
                return row;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        // ── 도시 파싱 ──────────────────────────────────────────
        checkEq("도시 파싱: 단순", ShadowLog.cityOfSlug(slug("amsterdam", 16)), "amsterdam");
        checkEq("도시 파싱: 하이픈 도시", ShadowLog.cityOfSlug(slug("buenos-aires", 30)), "buenos-aires");
        checkEq("도시 파싱: on 포함 도시명", ShadowLog.cityOfSlug(slug("wellington", 21)), "wellington");
        checkEq("도시 파싱: 이벤트 슬러그",
                ShadowLog.cityOfSlug("highest-temperature-in-seoul-on-october-6-2026"), "seoul");
        checkEq("도시 파싱: 기온 슬러그 아님", ShadowLog.cityOfSlug("some-other-market"), null);
        checkEq("도시 파싱: null", ShadowLog.cityOfSlug(null), null);
        checkEq("도시 파싱: 꼬리 없음", ShadowLog.cityOfSlug("highest-temperature-in-"), null);

        // ── 현지 시각 버킷 ─────────────────────────────────────
        checkEq("버킷: 서울 09:00", ShadowLog.localBucketOf("seoul", T0), "06-10");
        checkEq("버킷: 암스테르담 02:00 (CEST)", ShadowLog.localBucketOf("amsterdam", T0), "00-06");
        checkEq("버킷: LA 전날 17:00 (PDT)", ShadowLog.localBucketOf("los-angeles", T0), "14-18");
        checkEq("버킷: 웰링턴 13:00 (NZDT)", ShadowLog.localBucketOf("wellington", T0), "10-14");
        checkEq("버킷: 뉴욕 겨울 07:00 (EST)",
                ShadowLog.localBucketOf("nyc", Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()), "06-10");
        checkEq("버킷: 암스테르담 겨울 05:00 (CET)",
                ShadowLog.localBucketOf("amsterdam", Instant.parse("2026-01-15T04:00:00Z").toEpochMilli()), "00-06");
        checkEq("버킷: 암스테르담 여름 06:00 (CEST) — 서머타임 반영",
                ShadowLog.localBucketOf("amsterdam", Instant.parse("2026-07-15T04:00:00Z").toEpochMilli()), "06-10");
        checkEq("버킷: 모르는 도시", ShadowLog.localBucketOf("atlantis", T0), null);

        // ── 점수 조회 ──────────────────────────────────────────
        checkEq("점수: 정확 셀", ShadowLog.scoreFor("seoul", "06-10"), -0.0485);
        checkEq("점수: 정확 셀 (파리)", ShadowLog.scoreFor("paris", "14-18"), -0.1539);
        checkEq("점수: 버킷 없음 → 도시 평균", ShadowLog.scoreFor("austin", "10-14"), 0.0091);
        checkEq("점수: 버킷 없음 → 도시 평균 (런던)", ShadowLog.scoreFor("london", "14-18"), 0.0465);
        checkEq("점수: 버킷 없음 → 도시 평균 (LA)", ShadowLog.scoreFor("los-angeles", "06-10"), 0.1166);
        checkEq("점수: 모르는 도시 → 무점수", ShadowLog.scoreFor("atlantis", "06-10"), null);
        {
            Map<String, Map<String, Double>> synth = new LinkedHashMap<>();
            Map<String, Double> x = new LinkedHashMap<>();
            x.put("00-06", 1.0);
            x.put("06-10", 3.0);
            synth.put("x", x);
            synth.put("y", new LinkedHashMap<>());
            checkEq("점수: 합성표 정확 셀", ShadowLog.scoreFor("x", "00-06", synth), 1.0);
            checkEq("점수: 합성표 도시 평균", ShadowLog.scoreFor("x", "10-14", synth), 2.0);
            checkEq("점수: 빈 도시 → 무점수", ShadowLog.scoreFor("y", "00-06", synth), null);
        }

        // ── 이벤트 날짜 키 ─────────────────────────────────────
        checkEq("날짜 키: ISO", ShadowLog.eventDayKey(END), "2026-10-06");
        checkEq("날짜 키: 깨진 값", ShadowLog.eventDayKey("garbage"), null);
        checkEq("날짜 키: null", ShadowLog.eventDayKey(null), null);

        // ── 사이클 기록 ────────────────────────────────────────
        {
            ShadowLog.CandInput dallas = cand("dallas", 30, 0.95);
            ShadowLog.CandInput paris = cand("paris", 18, 0.99);
            ShadowLog.CandInput atlantis = ShadowLog.CandInput.of(new ScanFilter.Candidate(
                    "highest-temperature-in-atlantis-on-october-6-2026-20c",
                    "", "no", 0.97, END, "temp_no", 0.9, 0.998, null));
            Map<String, Object> rec = ShadowLog.buildCycleRecord(
                    T0, 7, 42.5, 3, 2,
                    List.of(paris, dallas, atlantis), Set.of(dallas.slug));
            checkEq("기록: 사이클 번호", rec.get("cycle"), 7);
            checkEq("기록: 현금", rec.get("cash"), 42.5);
            Map<String, Object> dRow = rowOf(rec, "dallas");
            Map<String, Object> pRow = rowOf(rec, "paris");
            Map<String, Object> aRow = rowOf(rec, "atlantis");
            checkEq("기록: 최고 점수가 1위", dRow.get("rank"), 1);
            checkEq("기록: 댈러스 점수", dRow.get("score"), 0.1278);
            checkEq("기록: 진입 표시", dRow.get("entered"), Boolean.TRUE);
            checkEq("기록: 파리 2위", pRow.get("rank"), 2);
            checkEq("기록: 미진입 표시", pRow.get("entered"), Boolean.FALSE);
            checkEq("기록: 무점수 도시는 score null", aRow.get("score"), null);
            checkEq("기록: 무점수 도시는 bucket null", aRow.get("bucket"), null);
            checkEq("기록: 무점수는 순위 뒤", aRow.get("rank"), 3);
            checkEq("기록: 이벤트 날짜", dRow.get("date"), "2026-10-06");
            checkEq("기록: 도시", dRow.get("city"), "dallas");
        }
        {
            // 동률(같은 도시 두 시장)은 평가 순서를 유지한다.
            ShadowLog.CandInput a = cand("seoul", 28, 0.95);
            ShadowLog.CandInput b = cand("seoul", 29, 0.96);
            Map<String, Object> rec = ShadowLog.buildCycleRecord(
                    T0, 1, 10, 1, 0, List.of(a, b), Set.of());
            List<?> rows = (List<?>) rec.get("cands");
            checkEq("기록: 동률은 평가 순서 1", ((Map<?, ?>) rows.get(0)).get("rank"), 1);
            checkEq("기록: 동률은 평가 순서 2", ((Map<?, ?>) rows.get(1)).get("rank"), 2);
        }

        // ── 저장소 파싱 ────────────────────────────────────────
        {
            Map<String, Object> empty = ShadowLog.parseStore(null);
            checkEq("저장소: null → cycles 빈 배열", ((List<?>) empty.get("cycles")).size(), 0);
            checkEq("저장소: null → days 빈 배열", ((List<?>) empty.get("days")).size(), 0);
            Map<String, Object> bad = ShadowLog.parseStore("garbage");
            checkEq("저장소: 깨진 원문 → 빈 저장소", ((List<?>) bad.get("cycles")).size(), 0);
            Map<String, Object> partial = ShadowLog.parseStore("{\"cycles\":[1],\"days\":\"x\"}");
            checkEq("저장소: 부분 손상 관용 (객체 아닌 항목 제거)", ((List<?>) partial.get("cycles")).size(), 0);
            checkEq("저장소: 부분 손상 days → 빈 배열", ((List<?>) partial.get("days")).size(), 0);
            Map<String, Object> mixed = ShadowLog.parseStore(
                    "{\"cycles\":[null,{\"a\":1}],\"days\":[null,{\"date\":\"2026-10-06\"}]}");
            checkEq("저장소: null 항목은 cycles에서 걸러짐", ((List<?>) mixed.get("cycles")).size(), 1);
            checkEq("저장소: null 항목은 days에서 걸러짐", ((List<?>) mixed.get("days")).size(), 1);
        }

        // ── 일별 롤업과 섀도우 픽 ──────────────────────────────
        {
            Map<String, Object> store = ShadowLog.parseStore(null);
            ShadowLog.CandInput dallas = cand("dallas", 30, 0.95);
            ShadowLog.CandInput seoul = cand("seoul", 28, 0.96);
            ShadowLog.CandInput paris = cand("paris", 18, 0.99);
            ShadowLog.appendCycle(store, ShadowLog.buildCycleRecord(
                    T0, 1, 50, 5, 0, List.of(paris, seoul, dallas), Set.of(seoul.slug)));
            ShadowLog.appendCycle(store, ShadowLog.buildCycleRecord(
                    T0 + 60_000, 2, 45, 4, 1, List.of(dallas, seoul),
                    Set.of(seoul.slug, dallas.slug)));
            List<?> cycles = (List<?>) store.get("cycles");
            List<?> days = (List<?>) store.get("days");
            checkEq("롤업: 사이클 2건 보관", cycles.size(), 2);
            checkEq("롤업: 하루로 합쳐짐", days.size(), 1);
            Map<?, ?> day = (Map<?, ?>) days.get(0);
            checkEq("롤업: 날짜", day.get("date"), "2026-10-06");
            List<?> dayCands = (List<?>) day.get("cands");
            checkEq("롤업: 후보는 slug로 중복 제거", dayCands.size(), 3);
            Map<?, ?> dallasDay = null;
            for (Object o : dayCands) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (dallas.slug.equals(m.get("slug"))) {
                    dallasDay = m;
                }
            }
            checkEq("롤업: 첫 관측 가격 유지", dallasDay.get("price"), 0.95);
            checkEq("롤업: 첫 관측 시각", dallasDay.get("firstTs"), (double) T0);
            checkEq("롤업: 진입은 순서대로 한 번만", day.get("entered"),
                    List.of(seoul.slug, dallas.slug));
            Map<?, ?> shadow = (Map<?, ?>) day.get("shadow");
            checkEq("섀도우 픽: 상한 1", shadow.get("1"), List.of(dallas.slug));
            checkEq("섀도우 픽: 상한 2", shadow.get("2"), List.of(dallas.slug, seoul.slug));
            checkEq("섀도우 픽: 상한 3", shadow.get("3"),
                    List.of(dallas.slug, seoul.slug, paris.slug));
        }

        // ── 보관 상한 ──────────────────────────────────────────
        {
            Map<String, Object> store = ShadowLog.parseStore(null);
            ShadowLog.CandInput c = cand("seoul", 28, 0.95);
            for (int i = 0; i < 5; i++) {
                ShadowLog.appendCycle(store, ShadowLog.buildCycleRecord(
                        T0 + i * 1000L, i, 1, 1, 0, List.of(c), Set.of()),
                        ShadowLog.MAX_BYTES, 2, ShadowLog.MAX_DAYS);
            }
            List<?> cycles = (List<?>) store.get("cycles");
            checkEq("상한: 사이클 수 상한", cycles.size(), 2);
            checkEq("상한: 오래된 사이클부터 버림", ((Map<?, ?>) cycles.get(0)).get("cycle"), 3);
        }
        {
            Map<String, Object> store = ShadowLog.parseStore(null);
            ShadowLog.CandInput c = cand("seoul", 28, 0.95);
            ShadowLog.appendCycle(store, ShadowLog.buildCycleRecord(
                    T0, 1, 1, 1, 0, List.of(c), Set.of()), 10, ShadowLog.MAX_CYCLES, ShadowLog.MAX_DAYS);
            checkEq("상한: 바이트 상한이 사이클을 비움", ((List<?>) store.get("cycles")).size(), 0);
            checkEq("상한: 마지막 하루는 남김", ((List<?>) store.get("days")).size(), 1);
        }
        {
            Map<String, Object> store = ShadowLog.parseStore(null);
            List<String> dates = new ArrayList<>();
            for (int d = 1; d <= 5; d++) {
                String end = "2026-10-0" + d + "T12:00:00Z";
                ShadowLog.CandInput c = ShadowLog.CandInput.of(new ScanFilter.Candidate(
                        slug("seoul", 28), "", "no", 0.95, end, "temp_no", 0.9, 0.998, null));
                ShadowLog.appendCycle(store, ShadowLog.buildCycleRecord(
                        T0, d, 1, 1, 0, List.of(c), Set.of()),
                        ShadowLog.MAX_BYTES, ShadowLog.MAX_CYCLES, 3);
                dates.add("2026-10-0" + d);
            }
            List<?> days = (List<?>) store.get("days");
            List<String> kept = new ArrayList<>();
            for (Object o : days) {
                kept.add(String.valueOf(((Map<?, ?>) o).get("date")));
            }
            checkEq("상한: 최근 날짜만 보관", kept,
                    List.of("2026-10-03", "2026-10-04", "2026-10-05"));
        }

        // ── JS 표 패리티 ───────────────────────────────────────
        {
            Path engineJs = Path.of(System.getProperty("repoDir"), "app/src/engine.js");
            String src = Files.readString(engineJs);
            Map<?, ?> jsTz = extractJsonObject(src, "export const CITY_TIMEZONES =");
            Map<?, ?> jsScores = extractJsonObject(src, "export const CITY_SHADOW_SCORES =");
            checkEq("패리티: 시간대 도시 수", jsTz.size(), ShadowLog.CITY_TIMEZONES.size());
            for (Map.Entry<?, ?> e : jsTz.entrySet()) {
                checkEq("패리티: 시간대 " + e.getKey(),
                        ShadowLog.CITY_TIMEZONES.get(String.valueOf(e.getKey())),
                        String.valueOf(e.getValue()));
            }
            checkEq("패리티: 점수 도시 수", jsScores.size(), ShadowLog.CITY_SCORES.size());
            for (Map.Entry<?, ?> e : jsScores.entrySet()) {
                Map<?, ?> perCity = (Map<?, ?>) e.getValue();
                Map<String, Double> javaCity = ShadowLog.CITY_SCORES.get(String.valueOf(e.getKey()));
                check("패리티: 점수 도시 존재 " + e.getKey(), javaCity != null);
                if (javaCity == null) {
                    continue;
                }
                checkEq("패리티: 점수 버킷 수 " + e.getKey(), perCity.size(), javaCity.size());
                for (Map.Entry<?, ?> b : perCity.entrySet()) {
                    checkEq("패리티: 점수 " + e.getKey() + " " + b.getKey(),
                            javaCity.get(String.valueOf(b.getKey())),
                            ((Number) b.getValue()).doubleValue());
                }
            }
        }

        // ── 입력 계약 가드 (분리 7단계) ────────────────────────
        {
            // 계약 밖 필드(question·side·strategy·band·feeRate)가 달라도
            // 계약 3필드가 같으면 기록은 바이트 단위로 같다.
            ScanFilter.Candidate base = new ScanFilter.Candidate(
                    slug("dallas", 30), "질문 A", "no", 0.95, END, "temp_no", 0.9, 0.998, null);
            ScanFilter.Candidate other = new ScanFilter.Candidate(
                    slug("dallas", 30), "질문 B", "yes", 0.95, END, "main", 0.6, 0.9, 0.07);
            Map<String, Object> ra = ShadowLog.buildCycleRecord(
                    T0, 7, 42.5, 3, 2, ShadowLog.CandInput.ofAll(List.of(base)), Set.of());
            Map<String, Object> rb = ShadowLog.buildCycleRecord(
                    T0, 7, 42.5, 3, 2, ShadowLog.CandInput.ofAll(List.of(other)), Set.of());
            checkEq("계약: 계약 밖 필드는 기록에 영향 없음",
                    ModelJson.stringify(rb), ModelJson.stringify(ra));
            ShadowLog.CandInput in = ShadowLog.CandInput.of(base);
            checkEq("계약: slug 복사", in.slug, base.slug);
            checkEq("계약: price 복사", in.price, base.price);
            checkEq("계약: endDate 복사", in.endDate, base.endDate);
            checkEq("계약: ofAll null → 빈 배열", ShadowLog.CandInput.ofAll(null).size(), 0);
        }

        // ── 기준선 바이트 대조 (리팩터 전 코드 산출) ───────────
        {
            Path fixture = Path.of(System.getProperty("repoDir"),
                    "android/test/fixtures/shadow-contract-baseline.json");
            Map<?, ?> baseline = (Map<?, ?>) JsonText.parse(Files.readString(fixture));
            List<?> baseRecords = (List<?>) baseline.get("records");
            String baseStore = (String) baseline.get("store");
            List<List<ShadowLog.CandInput>> scenCands = new ArrayList<>(List.of(
                    List.of(cand("paris", 18, 0.99), cand("dallas", 30, 0.95),
                            cand("atlantis", 20, 0.97)),
                    List.of(cand("dallas", 30, 0.95), cand("seoul", 28, 0.96)),
                    List.of(ShadowLog.CandInput.of(new ScanFilter.Candidate(
                                    slug("seoul", 28), "", "no", 0.95, "", "temp_no", 0.9, 0.998, null)),
                            cand("seoul", 29, 0.96)),
                    List.of(cand("wellington", 21, 0.93), cand("dallas", 30, 0.95))));
            // S4의 wellington은 날짜가 다르다 — 픽스처와 같은 endDate로 교체
            scenCands.set(3, List.of(
                    ShadowLog.CandInput.of(new ScanFilter.Candidate(
                            slug("wellington", 21), "", "no", 0.93,
                            "2026-10-07T12:00:00Z", "temp_no", 0.9, 0.998, null)),
                    cand("dallas", 30, 0.95)));
            long[] ts = {T0, T0 + 60_000, T0 + 120_000, T0 + 180_000};
            int[] cycle = {7, 8, 9, 10};
            double[] cash = {42.5, 45, 0, 12.34};
            int[] slots = {3, 4, 0, 2};
            int[] open = {2, 1, 0, 5};
            List<Set<String>> entered = List.of(
                    Set.of(slug("dallas", 30)),
                    Set.of(slug("seoul", 28), slug("dallas", 30)),
                    Set.of(),
                    Set.of(slug("wellington", 21)));
            checkEq("기준선: 시나리오 수 일치", baseRecords.size(), scenCands.size());
            Map<String, Object> store = ShadowLog.parseStore(null);
            for (int i = 0; i < scenCands.size(); i++) {
                Map<String, Object> rec = ShadowLog.buildCycleRecord(
                        ts[i], cycle[i], cash[i], slots[i], open[i],
                        scenCands.get(i), entered.get(i));
                checkEq("기준선: 레코드 " + (i + 1) + " 바이트 동일",
                        ModelJson.stringify(rec), baseRecords.get(i));
                ShadowLog.appendCycle(store, rec);
            }
            checkEq("기준선: 저장소 직렬화 바이트 동일",
                    ModelJson.stringify(store), baseStore);
        }

        System.out.println("shadow-log: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** JS 소스에서 `marker` 뒤 첫 JSON 객체 리터럴을 균형 스캔으로 뽑아 파싱한다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractJsonObject(String src, String marker) {
        int at = src.indexOf(marker);
        if (at < 0) {
            throw new IllegalStateException("marker not found: " + marker);
        }
        int start = src.indexOf('{', at);
        int depth = 0;
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return (Map<String, Object>) JsonText.parse(src.substring(start, i + 1));
                }
            }
        }
        throw new IllegalStateException("unbalanced object after " + marker);
    }
}
