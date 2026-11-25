package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.write.ModelJson;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 도시 점수 섀도우 기록 — JS engine.js의 섀도우 절과 같은 규칙이다.
 *
 * 도시×현지시각 연구(citytime-sort-20261126.md)의 판정은 "섀도우 기록만
 * 가치 있음"이었다. 거래 동작은 바꾸지 않고, 기온 후보 평가를 점수표와
 * 나란히 기록해 표본을 쌓는다. 점수표는 연구 산출물
 * (queue-20261124/citytime_sort_keys.json, 2026-11-26 생성, 재료 기간
 * 2026-10-06..11-18, 건당 $5·체결 페널티 0.5센트)의 축소 점수를 그대로
 * 옮긴 것이고, 연구가 갱신되면 이 표만 교체한다. 축소 결과 버킷 간 실제
 * 분산이 0이라 현재 표는 도시마다 전 버킷 같은 값이지만, 조회는
 * 셀 → 도시 평균 순으로 걸어 다음 표에서 버킷 편차가 생겨도 그대로
 * 쓰게 한다. 표에 없는 도시는 무점수(중립) — 순위 뒤로 밀고 기록에도
 * score: null로 남긴다.
 *
 * 저장소는 durable 키 bm_shadow 하나에 사이클 기록(최근분)과 일별
 * 롤업을 함께 담는다. 보관 상한을 넘으면 오래된 사이클 기록부터 버리고
 * 일별 롤업은 최근 14일만 남긴다. 기록은 부수 경로라 실패가 거래를
 * 막지 않게 호출자(NativeCycle)가 try/catch로 격리한다.
 */
public final class ShadowLog {
    private ShadowLog() {}

    public static final String STORE_KEY = "bm_shadow";
    public static final String SCORE_GENERATED = "2026-11-26";
    public static final String[] BUCKETS = {"00-06", "06-10", "10-14", "14-18", "18-24"};
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_CYCLES = 400;
    public static final int MAX_DAYS = 14;

    public static final Map<String, String> CITY_TIMEZONES = buildTimezones();
    public static final Map<String, Map<String, Double>> CITY_SCORES = buildScores();

    private static Map<String, String> buildTimezones() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("amsterdam", "Europe/Amsterdam");
        m.put("ankara", "Europe/Istanbul");
        m.put("austin", "America/Chicago");
        m.put("beijing", "Asia/Shanghai");
        m.put("buenos-aires", "America/Argentina/Buenos_Aires");
        m.put("busan", "Asia/Seoul");
        m.put("cape-town", "Africa/Johannesburg");
        m.put("chengdu", "Asia/Shanghai");
        m.put("chongqing", "Asia/Shanghai");
        m.put("dallas", "America/Chicago");
        m.put("guangzhou", "Asia/Shanghai");
        m.put("helsinki", "Europe/Helsinki");
        m.put("hong-kong", "Asia/Hong_Kong");
        m.put("houston", "America/Chicago");
        m.put("istanbul", "Europe/Istanbul");
        m.put("jeddah", "Asia/Riyadh");
        m.put("kuala-lumpur", "Asia/Kuala_Lumpur");
        m.put("london", "Europe/London");
        m.put("los-angeles", "America/Los_Angeles");
        m.put("madrid", "Europe/Madrid");
        m.put("miami", "America/New_York");
        m.put("milan", "Europe/Rome");
        m.put("munich", "Europe/Berlin");
        m.put("nyc", "America/New_York");
        m.put("paris", "Europe/Paris");
        m.put("qingdao", "Asia/Shanghai");
        m.put("san-francisco", "America/Los_Angeles");
        m.put("sao-paulo", "America/Sao_Paulo");
        m.put("seattle", "America/Los_Angeles");
        m.put("seoul", "Asia/Seoul");
        m.put("shanghai", "Asia/Shanghai");
        m.put("shenzhen", "Asia/Shanghai");
        m.put("singapore", "Asia/Singapore");
        m.put("taipei", "Asia/Taipei");
        m.put("tel-aviv", "Asia/Jerusalem");
        m.put("tokyo", "Asia/Tokyo");
        m.put("toronto", "America/Toronto");
        m.put("warsaw", "Europe/Warsaw");
        m.put("wellington", "Pacific/Auckland");
        m.put("wuhan", "Asia/Shanghai");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Map<String, Double>> buildScores() {
        Map<String, Map<String, Double>> m = new LinkedHashMap<>();
        m.put("amsterdam", Map.of("00-06", 0.0942, "06-10", 0.0942, "10-14", 0.0942, "14-18", 0.0942));
        m.put("ankara", Map.of("00-06", -0.0423, "06-10", -0.0423, "10-14", -0.0423, "14-18", -0.0423));
        m.put("austin", Map.of("00-06", 0.0091, "06-10", 0.0091, "18-24", 0.0091));
        m.put("beijing", Map.of("06-10", 0.0532, "10-14", 0.0532, "14-18", 0.0532, "18-24", 0.0532));
        m.put("buenos-aires", Map.of("00-06", -0.1237, "06-10", -0.1237, "18-24", -0.1237));
        m.put("busan", Map.of("06-10", 0.0721, "10-14", 0.0721, "14-18", 0.0721, "18-24", 0.0721));
        m.put("cape-town", Map.of("00-06", -0.0993, "06-10", -0.0993, "10-14", -0.0993, "14-18", -0.0993));
        m.put("chengdu", Map.of("06-10", 0.0131, "10-14", 0.0131, "14-18", 0.0131, "18-24", 0.0131));
        m.put("chongqing", Map.of("06-10", 0.0361, "10-14", 0.0361, "14-18", 0.0361, "18-24", 0.0361));
        m.put("dallas", Map.of("00-06", 0.1278, "06-10", 0.1278, "18-24", 0.1278));
        m.put("guangzhou", Map.of("06-10", -0.0264, "10-14", -0.0264, "14-18", -0.0264, "18-24", -0.0264));
        m.put("helsinki", Map.of("00-06", 0.0743, "06-10", 0.0743, "10-14", 0.0743, "14-18", 0.0743));
        m.put("hong-kong", Map.of("06-10", -0.0103, "10-14", -0.0103, "14-18", -0.0103, "18-24", -0.0103));
        m.put("houston", Map.of("00-06", -0.0195, "06-10", -0.0195, "18-24", -0.0195));
        m.put("istanbul", Map.of("00-06", 0.0768, "06-10", 0.0768, "10-14", 0.0768, "14-18", 0.0768));
        m.put("jeddah", Map.of("00-06", -0.0635, "06-10", -0.0635, "10-14", -0.0635, "14-18", -0.0635));
        m.put("kuala-lumpur", Map.of("06-10", 0.0504, "10-14", 0.0504, "14-18", 0.0504, "18-24", 0.0504));
        m.put("london", Map.of("00-06", 0.0465, "06-10", 0.0465, "10-14", 0.0465));
        m.put("los-angeles", Map.of("00-06", 0.1166, "14-18", 0.1166, "18-24", 0.1166));
        m.put("madrid", Map.of("00-06", -0.0793, "06-10", -0.0793, "10-14", -0.0793, "14-18", -0.0793));
        m.put("miami", Map.of("00-06", 0.082, "06-10", 0.082, "18-24", 0.082));
        m.put("milan", Map.of("00-06", -0.0341, "06-10", -0.0341, "10-14", -0.0341, "14-18", -0.0341));
        m.put("munich", Map.of("00-06", 0.0165, "06-10", 0.0165, "10-14", 0.0165, "14-18", 0.0165));
        m.put("nyc", Map.of("00-06", 0.1007, "06-10", 0.1007, "18-24", 0.1007));
        m.put("paris", Map.of("00-06", -0.1539, "06-10", -0.1539, "10-14", -0.1539, "14-18", -0.1539));
        m.put("qingdao", Map.of("06-10", 0.0707, "10-14", 0.0707, "14-18", 0.0707, "18-24", 0.0707));
        m.put("san-francisco", Map.of("00-06", 0.0613, "14-18", 0.0613, "18-24", 0.0613));
        m.put("sao-paulo", Map.of("00-06", 0.0309, "06-10", 0.0309, "18-24", 0.0309));
        m.put("seattle", Map.of("00-06", 0.002, "14-18", 0.002, "18-24", 0.002));
        m.put("seoul", Map.of("06-10", -0.0485, "10-14", -0.0485, "14-18", -0.0485, "18-24", -0.0485));
        m.put("shanghai", Map.of("06-10", 0.0623, "10-14", 0.0623, "14-18", 0.0623, "18-24", 0.0623));
        m.put("shenzhen", Map.of("06-10", -0.0759, "10-14", -0.0759, "14-18", -0.0759, "18-24", -0.0759));
        m.put("singapore", Map.of("06-10", 0.0646, "10-14", 0.0646, "14-18", 0.0646, "18-24", 0.0646));
        m.put("taipei", Map.of("06-10", -0.0012, "10-14", -0.0012, "14-18", -0.0012, "18-24", -0.0012));
        m.put("tel-aviv", Map.of("00-06", 0.0073, "06-10", 0.0073, "10-14", 0.0073, "14-18", 0.0073));
        m.put("tokyo", Map.of("06-10", -0.0722, "10-14", -0.0722, "14-18", -0.0722, "18-24", -0.0722));
        m.put("toronto", Map.of("00-06", -0.1553, "06-10", -0.1553, "18-24", -0.1553));
        m.put("warsaw", Map.of("00-06", 0.0303, "06-10", 0.0303, "10-14", 0.0303, "14-18", 0.0303));
        m.put("wellington", Map.of("00-06", 0.0517, "10-14", 0.0517, "14-18", 0.0517, "18-24", 0.0517));
        m.put("wuhan", Map.of("06-10", 0.0846, "10-14", 0.0846, "14-18", 0.0846, "18-24", 0.0846));
        return Collections.unmodifiableMap(m);
    }

    /** 기온 시장·이벤트 슬러그에서 도시를 뽑는다. 형식이 어긋나면 null. */
    public static String cityOfSlug(String slug) {
        if (!TempNo.isTempSlug(slug)) {
            return null;
        }
        String rest = slug.substring(TempNo.SLUG_PREFIX.length());
        int idx = rest.indexOf("-on-");
        if (idx <= 0) {
            return null;
        }
        return rest.substring(0, idx);
    }

    /**
     * 도시 현지 시각 버킷 — 연구 bucket_of와 같은 경계. 시간대를 모르거나
     * 해석이 실패하면 null이다 (그 후보는 버킷 없음으로 기록된다).
     */
    public static String localBucketOf(String city, long nowMs) {
        String tz = city == null ? null : CITY_TIMEZONES.get(city);
        if (tz == null) {
            return null;
        }
        final int hour;
        try {
            hour = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(tz)).getHour();
        } catch (Exception e) {
            return null;
        }
        if (hour < 6) {
            return "00-06";
        }
        if (hour < 10) {
            return "06-10";
        }
        if (hour < 14) {
            return "10-14";
        }
        if (hour < 18) {
            return "14-18";
        }
        return "18-24";
    }

    /** 섀도우 점수 조회 — 내장 표 기준. */
    public static Double scoreFor(String city, String bucket) {
        return scoreFor(city, bucket, CITY_SCORES);
    }

    /**
     * 섀도우 점수 조회 — (도시, 버킷) 셀 → 도시 평균(표에 있는 버킷
     * 값들의 평균) → 없으면 null(무점수). 평균은 버킷 고정 순서로
     * 합산해 JS와 같은 값을 낸다.
     */
    public static Double scoreFor(String city, String bucket,
                                  Map<String, Map<String, Double>> table) {
        if (table == null || city == null) {
            return null;
        }
        Map<String, Double> perCity = table.get(city);
        if (perCity == null) {
            return null;
        }
        Double cell = bucket == null ? null : perCity.get(bucket);
        if (cell != null && Double.isFinite(cell)) {
            return cell;
        }
        double sum = 0;
        int n = 0;
        Double first = null;
        boolean allSame = true;
        for (String b : BUCKETS) {
            Double v = perCity.get(b);
            if (v != null && Double.isFinite(v)) {
                sum += v;
                n += 1;
                if (first == null) {
                    first = v;
                } else if (!v.equals(first)) {
                    allSame = false;
                }
            }
        }
        if (n == 0) {
            return null;
        }
        // 전 버킷 같은 값이면 평균도 그 값 — 합산 오차로 어긋나지 않게 한다.
        return allSame ? first : sum / n;
    }

    /** 기온 이벤트 날짜 키 — endDate의 UTC 날짜 (진입 창 판정과 같은 기준). */
    public static String eventDayKey(String endDate) {
        Long ms = TempNo.parseEndMs(endDate);
        if (ms == null) {
            return null;
        }
        return Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /**
     * 사이클 섀도우 기록 1건 — 그 사이클이 평가한 기온 후보 전체에
     * 도시·버킷·점수·순위를 붙인다. 순위는 점수 내림차순, 무점수는 뒤,
     * 동률은 평가 순서를 유지한다. entered는 그 사이클에 매수로 기록된
     * slug 집합이다.
     */
    public static Map<String, Object> buildCycleRecord(
            long ts, int cycle, double cash, int slots, int openCount,
            List<ScanFilter.Candidate> cands, Set<String> entered) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (cands != null) {
            for (ScanFilter.Candidate cand : cands) {
                String city = cityOfSlug(cand.slug);
                String bucket = city == null ? null : localBucketOf(city, ts);
                Double score = (city != null && bucket != null) ? scoreFor(city, bucket) : null;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slug", cand.slug);
                row.put("city", city);
                row.put("bucket", bucket);
                row.put("date", eventDayKey(cand.endDate));
                row.put("price", cand.price);
                row.put("score", score);
                row.put("rank", 0);
                row.put("entered", entered != null && entered.contains(cand.slug));
                rows.add(row);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            Double sa = (Double) rows.get(a).get("score");
            Double sb = (Double) rows.get(b).get("score");
            if (sa == null && sb == null) {
                return Integer.compare(a, b);
            }
            if (sa == null) {
                return 1;
            }
            if (sb == null) {
                return -1;
            }
            if (!sb.equals(sa)) {
                return Double.compare(sb, sa);
            }
            return Integer.compare(a, b);
        });
        for (int pos = 0; pos < order.size(); pos++) {
            rows.get(order.get(pos)).put("rank", pos + 1);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ts", (double) ts);
        record.put("cycle", cycle);
        record.put("cash", cash);
        record.put("slots", slots);
        record.put("openCount", openCount);
        record.put("cands", new ArrayList<Object>(rows));
        return record;
    }

    /** 저장 원문의 관용 파싱 — 깨진 값은 빈 저장소로 떨어진다. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseStore(String raw) {
        Object parsed = null;
        if (raw != null) {
            try {
                parsed = JsonText.parse(raw);
            } catch (Exception e) {
                parsed = null;
            }
        }
        List<Object> cycles = new ArrayList<>();
        List<Object> days = new ArrayList<>();
        if (parsed instanceof Map) {
            Object c = ((Map<?, ?>) parsed).get("cycles");
            if (c instanceof List) {
                for (Object o : (List<?>) c) {
                    if (o instanceof Map) {
                        cycles.add(o);
                    }
                }
            }
            Object d = ((Map<?, ?>) parsed).get("days");
            if (d instanceof List) {
                for (Object o : (List<?>) d) {
                    if (o instanceof Map) {
                        days.add(o);
                    }
                }
            }
        }
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("v", 1.0);
        store.put("cycles", cycles);
        store.put("days", days);
        return store;
    }

    /** 사이클 기록을 저장소에 붙이고 보관 상한을 적용한다 (기본 상한). */
    public static void appendCycle(Map<String, Object> store, Map<String, Object> record) {
        appendCycle(store, record, MAX_BYTES, MAX_CYCLES, MAX_DAYS);
    }

    /**
     * 사이클 기록을 저장소에 붙이고 보관 상한을 적용한다. 일별 롤업은
     * 후보를 첫 관측(가격·점수·버킷)으로만 남기고, 실제 진입은 진입
     * 순서대로 한 번만 남긴다 — 나중에 정산 결과와 slug로 결합하는
     * 재료다.
     */
    @SuppressWarnings("unchecked")
    public static void appendCycle(Map<String, Object> store, Map<String, Object> record,
                                   int maxBytes, int maxCycles, int maxDays) {
        List<Object> cycles = (List<Object>) store.get("cycles");
        List<Object> days = (List<Object>) store.get("days");
        cycles.add(record);
        Object recTs = record.get("ts");
        double ts = recTs instanceof Number ? ((Number) recTs).doubleValue() : 0;
        List<String> touched = new ArrayList<>();
        Object recCands = record.get("cands");
        if (recCands instanceof List) {
            for (Object o : (List<?>) recCands) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) o;
                Object dateRaw = row.get("date");
                Object slugRaw = row.get("slug");
                if (!(dateRaw instanceof String) || !(slugRaw instanceof String)) {
                    continue;
                }
                String date = (String) dateRaw;
                String slug = (String) slugRaw;
                Map<String, Object> day = dayOf(days, date);
                if (!touched.contains(date)) {
                    touched.add(date);
                }
                List<Object> dayCands = (List<Object>) day.get("cands");
                boolean seen = false;
                for (Object dc : dayCands) {
                    if (dc instanceof Map && slug.equals(((Map<?, ?>) dc).get("slug"))) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("slug", slug);
                    entry.put("city", row.get("city"));
                    entry.put("bucket", row.get("bucket"));
                    entry.put("price", row.get("price"));
                    entry.put("score", row.get("score"));
                    entry.put("firstTs", ts);
                    dayCands.add(entry);
                }
                if (Boolean.TRUE.equals(row.get("entered"))) {
                    List<Object> enteredList = (List<Object>) day.get("entered");
                    if (!enteredList.contains(slug)) {
                        enteredList.add(slug);
                    }
                }
            }
        }
        for (String date : touched) {
            refreshPicks(dayOf(days, date));
        }
        days.sort((a, b) -> {
            String da = a instanceof Map ? String.valueOf(((Map<?, ?>) a).get("date")) : "";
            String db = b instanceof Map ? String.valueOf(((Map<?, ?>) b).get("date")) : "";
            return da.compareTo(db);
        });
        while (days.size() > maxDays) {
            days.remove(0);
        }
        while (cycles.size() > maxCycles) {
            cycles.remove(0);
        }
        while (!cycles.isEmpty() && ModelJson.stringify(store).length() > maxBytes) {
            cycles.remove(0);
        }
        while (days.size() > 1 && ModelJson.stringify(store).length() > maxBytes) {
            days.remove(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dayOf(List<Object> days, String date) {
        for (Object o : days) {
            if (o instanceof Map && date.equals(((Map<?, ?>) o).get("date"))) {
                Map<String, Object> day = (Map<String, Object>) o;
                if (!(day.get("cands") instanceof List)) {
                    day.put("cands", new ArrayList<>());
                }
                if (!(day.get("entered") instanceof List)) {
                    day.put("entered", new ArrayList<>());
                }
                return day;
            }
        }
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", date);
        day.put("cands", new ArrayList<>());
        day.put("entered", new ArrayList<>());
        Map<String, Object> shadow = new LinkedHashMap<>();
        shadow.put("1", new ArrayList<>());
        shadow.put("2", new ArrayList<>());
        shadow.put("3", new ArrayList<>());
        day.put("shadow", shadow);
        days.add(day);
        return day;
    }

    /**
     * 일별 섀도우 픽 재계산 — 점수 내림차순(무점수 뒤), 동률은 첫 관측
     * 시각·slug 순. 연구 시뮬레이션의 score 정책과 같은 정렬이다.
     */
    @SuppressWarnings("unchecked")
    private static void refreshPicks(Map<String, Object> day) {
        List<Object> cands = (List<Object>) day.get("cands");
        List<Object> sorted = new ArrayList<>(cands);
        sorted.sort((a, b) -> {
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            Double sa = ma.get("score") instanceof Number
                    ? ((Number) ma.get("score")).doubleValue() : null;
            Double sb = mb.get("score") instanceof Number
                    ? ((Number) mb.get("score")).doubleValue() : null;
            if (sa == null && sb == null) {
                int c = Double.compare(firstTsOf(ma), firstTsOf(mb));
                return c != 0 ? c : slugOf(ma).compareTo(slugOf(mb));
            }
            if (sa == null) {
                return 1;
            }
            if (sb == null) {
                return -1;
            }
            if (!sb.equals(sa)) {
                return Double.compare(sb, sa);
            }
            int c = Double.compare(firstTsOf(ma), firstTsOf(mb));
            return c != 0 ? c : slugOf(ma).compareTo(slugOf(mb));
        });
        Map<String, Object> shadow = new LinkedHashMap<>();
        shadow.put("1", pickSlugs(sorted, 1));
        shadow.put("2", pickSlugs(sorted, 2));
        shadow.put("3", pickSlugs(sorted, 3));
        day.put("shadow", shadow);
    }

    private static double firstTsOf(Map<?, ?> m) {
        Object v = m.get("firstTs");
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private static String slugOf(Map<?, ?> m) {
        Object v = m.get("slug");
        return v instanceof String ? (String) v : "";
    }

    private static List<Object> pickSlugs(List<Object> sorted, int k) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < k; i++) {
            Object o = sorted.get(i);
            if (o instanceof Map) {
                out.add(slugOf((Map<?, ?>) o));
            }
        }
        return out;
    }

    /**
     * 기록 드라이버 — 저장소 읽기·붙이기·쓰기를 한 번에 한다. 기록은
     * 부수 경로라 호출자(NativeCycle)가 try/catch로 격리한다.
     */
    public static void recordCycle(NativeCycle.Env env, long ts, int cycle, double cash,
                                   int slots, int openCount,
                                   List<ScanFilter.Candidate> cands, Set<String> entered) {
        if (cands == null || cands.isEmpty()) {
            return;
        }
        Map<String, Object> record = buildCycleRecord(ts, cycle, cash, slots, openCount, cands, entered);
        Map<String, Object> store = parseStore(env.durableRead(STORE_KEY));
        appendCycle(store, record);
        env.durableWrite(STORE_KEY, ModelJson.stringify(store));
    }
}
