// 기온 No 페이드 JVM 단위 테스트 — TempNo 순수 로직(정수/꼬리 판정·밴드
// 경계·진입 창 경계·토글 OFF·설정 해석·수수료), StopLoss.evaluate의
// 태그 기준 기온 전용 손절 폭, CandidateVerifier의 기온 밴드·창 분기를
// 대조한다.
// 실행: android/test/run_temp_no_test.sh
import com.roomedia.boringmech.engine.BoughtReconcile;
import com.roomedia.boringmech.engine.CandidateVerifier;
import com.roomedia.boringmech.engine.JsonText;
import com.roomedia.boringmech.engine.ScanFilter;
import com.roomedia.boringmech.engine.StopLoss;
import com.roomedia.boringmech.engine.TempNo;
import com.roomedia.boringmech.engine.TradeSettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TempNoTest {
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

    @SuppressWarnings("unchecked")
    private static List<Object> jlist(String text) {
        return (List<Object>) JsonText.parse(text);
    }

    private static final String EVENT_SLUG = "highest-temperature-in-seoul-on-november-18-2026";
    private static final long END_MS = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli();
    private static final long AT_7AM = Instant.parse("2026-11-18T07:00:00Z").toEpochMilli();

    private static String marketJson(String title, String noPrice) {
        double yes = 1 - Double.parseDouble(noPrice);
        return "{\"slug\":\"" + EVENT_SLUG + "-m" + title + "\","
                + "\"question\":\"Will the highest temperature in Seoul be X?\","
                + "\"groupItemTitle\":\"" + title + "\","
                + "\"active\":true,\"closed\":false,\"acceptingOrders\":true,"
                + "\"endDate\":\"2026-11-18T12:00:00Z\","
                + "\"outcomePrices\":\"[" + yes + "," + noPrice + "]\","
                + "\"clobTokenIds\":\"[\\\"t0\\\",\\\"t1\\\"]\","
                + "\"feeType\":\"weather_fees\",\"feeSchedule\":{\"rate\":0.05}}";
    }

    private static List<Object> eventOf(String... markets) {
        StringBuilder sb = new StringBuilder();
        sb.append("[{\"slug\":\"").append(EVENT_SLUG)
                .append("\",\"endDate\":\"2026-11-18T12:00:00Z\",\"markets\":[");
        for (int i = 0; i < markets.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(markets[i]);
        }
        sb.append("]}]");
        return jlist(sb.toString());
    }

    private static TempNo.Settings settings(boolean enabled, double bandLo) {
        return TempNo.parseSettings("{\"tempNoEnabled\":" + enabled
                + ",\"tempNoBandLo\":" + bandLo + "}");
    }

    private static int scanCount(List<Object> events, TempNo.Settings s, long nowMs) {
        return TempNo.scanTempCandidates(events, new HashSet<>(), new HashSet<>(), s, nowMs, null).size();
    }

    public static void main(String[] args) throws Exception {
        // ── 슬러그·제목 판정 ──
        check("event slug", TempNo.isTempSlug(EVENT_SLUG));
        check("market slug", TempNo.isTempSlug(EVENT_SLUG + "-7c"));
        check("other slug", !TempNo.isTempSlug("will-btc-hit-100k"));
        check("null slug", !TempNo.isTempSlug(null));
        check("exact C", TempNo.isExactTempTitle("3°C"));
        check("exact F", TempNo.isExactTempTitle("72°F"));
        check("negative exact", TempNo.isExactTempTitle("-3°C"));
        check("tail below", !TempNo.isExactTempTitle("6°C or below"));
        check("tail higher", !TempNo.isExactTempTitle("21°C or higher"));
        check("range bucket", !TempNo.isExactTempTitle("50-51°F"));
        check("empty title", !TempNo.isExactTempTitle(""));
        check("null title", !TempNo.isExactTempTitle(null));

        // ── 이벤트 슬러그 생성 ──
        checkEq("slug seoul", TempNo.eventSlugFor("seoul", AT_7AM), EVENT_SLUG);
        checkEq("cities 40", TempNo.CITIES.size(), 40);
        checkEq("today slugs 40", TempNo.todayEventSlugs(AT_7AM).size(), 40);
        // 조회 범위는 오늘+내일 이틀치 — 내일 이벤트가 전날부터 보인다
        checkEq("fetch slugs 80", TempNo.eventSlugsForFetch(AT_7AM).size(), 80);
        check("fetch slugs include tomorrow seoul",
                TempNo.eventSlugsForFetch(AT_7AM)
                        .contains("highest-temperature-in-seoul-on-november-19-2026"));
        check("fetch slugs include today seoul",
                TempNo.eventSlugsForFetch(AT_7AM).contains(EVENT_SLUG));
        check("fetch slugs no duplicates",
                new HashSet<>(TempNo.eventSlugsForFetch(AT_7AM)).size() == 80);

        // ── 진입 창 경계 ──
        check("window 05:59 closed", !TempNo.windowOpen(
                Instant.parse("2026-11-18T05:59:59Z").toEpochMilli(), END_MS));
        check("window 06:00 open", TempNo.windowOpen(
                Instant.parse("2026-11-18T06:00:00Z").toEpochMilli(), END_MS));
        check("window 11:59 open", TempNo.windowOpen(
                Instant.parse("2026-11-18T11:59:59Z").toEpochMilli(), END_MS));
        check("window endDate closed", !TempNo.windowOpen(END_MS, END_MS));
        check("window hour 0 opens at midnight", TempNo.windowOpen(
                Instant.parse("2026-11-18T00:00:00Z").toEpochMilli(), END_MS, 0));
        check("window hour 9 closed at 05:00", !TempNo.windowOpen(
                Instant.parse("2026-11-18T05:00:00Z").toEpochMilli(), END_MS, 9));
        check("window hour 9 opens at 09:00", TempNo.windowOpen(
                Instant.parse("2026-11-18T09:00:00Z").toEpochMilli(), END_MS, 9));

        // ── 설정 해석 ──
        TempNo.Settings def = TempNo.parseSettings(null);
        check("settings null off", !def.enabled && def.bandLo == TempNo.DEFAULT_BAND_LO);
        check("settings defaults", def.bandHi == TempNo.BAND_HI
                && def.entryHourUtc == TempNo.ENTRY_HOUR_UTC
                && def.reserveSlots == TempNo.DEFAULT_RESERVE_SLOTS);
        check("settings bad json", !TempNo.parseSettings("not json").enabled);
        TempNo.Settings custom = settings(true, 0.93);
        check("settings custom", custom.enabled && custom.bandLo == 0.93);
        check("settings band above hi falls back",
                TempNo.parseSettings("{\"tempNoEnabled\":true,\"tempNoBandLo\":0.999}").bandLo
                        == TempNo.DEFAULT_BAND_LO);
        check("settings string true is off",
                !TempNo.parseSettings("{\"tempNoEnabled\":\"true\"}").enabled);
        // 하한=상한(빈 밴드)은 쌍째로 기본값으로 되돌린다
        TempNo.Settings pairReset = TempNo.parseSettings("{\"tempNoBandLo\":0.998}");
        check("settings band lo == hi resets pair",
                pairReset.bandLo == TempNo.DEFAULT_BAND_LO && pairReset.bandHi == TempNo.BAND_HI);
        TempNo.Settings customBand = TempNo.parseSettings(
                "{\"tempNoBandLo\":0.85,\"tempNoBandHi\":0.99}");
        check("settings custom band hi kept", customBand.bandLo == 0.85 && customBand.bandHi == 0.99);
        check("settings band hi above 1 falls back",
                TempNo.parseSettings("{\"tempNoBandHi\":1.5}").bandHi == TempNo.BAND_HI);
        check("settings entry hour kept",
                TempNo.parseSettings("{\"tempEntryHourUtc\":0}").entryHourUtc == 0
                        && TempNo.parseSettings("{\"tempEntryHourUtc\":23}").entryHourUtc == 23);
        check("settings entry hour falls back",
                TempNo.parseSettings("{\"tempEntryHourUtc\":24}").entryHourUtc == TempNo.ENTRY_HOUR_UTC
                        && TempNo.parseSettings("{\"tempEntryHourUtc\":6.5}").entryHourUtc
                                == TempNo.ENTRY_HOUR_UTC);
        check("settings reserve kept",
                TempNo.parseSettings("{\"tempNoReserveSlots\":0}").reserveSlots == 0
                        && TempNo.parseSettings("{\"tempNoReserveSlots\":3}").reserveSlots == 3
                        && TempNo.parseSettings("{\"tempNoReserveSlots\":70}").reserveSlots == 70);
        check("settings reserve falls back",
                TempNo.parseSettings("{\"tempNoReserveSlots\":71}").reserveSlots == 1
                        && TempNo.parseSettings("{\"tempNoReserveSlots\":-1}").reserveSlots == 1
                        && TempNo.parseSettings("{\"tempNoReserveSlots\":2.5}").reserveSlots == 1);
        // v0.3.38 감사 1 — 손상 타입은 강제변환(null→0, true→1)이 아니라 기본값
        check("audit1: reserve null falls back",
                TempNo.parseSettings("{\"tempNoReserveSlots\":null}").reserveSlots == 1);
        check("audit1: reserve true falls back",
                TempNo.parseSettings("{\"tempNoReserveSlots\":true}").reserveSlots == 1);
        check("audit1: reserve empty string falls back",
                TempNo.parseSettings("{\"tempNoReserveSlots\":\"\"}").reserveSlots == 1);
        check("audit1: bandLo true falls back",
                TempNo.parseSettings("{\"tempNoBandLo\":true}").bandLo == TempNo.DEFAULT_BAND_LO);
        check("audit1: entry hour null falls back",
                TempNo.parseSettings("{\"tempEntryHourUtc\":null}").entryHourUtc == TempNo.ENTRY_HOUR_UTC);
        // v0.3.38 감사 6 — 예약 슬롯 교차 클램프
        check("audit6: reserve clamped to maxPositions", TempNo.effectiveReserveSlots(70, 2) == 2);
        check("audit6: small reserve kept", TempNo.effectiveReserveSlots(1, 70) == 1);
        check("audit6: zero reserve kept", TempNo.effectiveReserveSlots(0, 70) == 0);

        // ── 수수료 해석 ──
        Object weather = JsonText.parse("{\"feeType\":\"weather_fees\"}");
        checkEq("fee weather", TempNo.feeRateFor(weather), 0.05);
        Object schedOnly = JsonText.parse("{\"feeSchedule\":{\"rate\":0.05}}");
        checkEq("fee schedule fallback", TempNo.feeRateFor(schedOnly), 0.05);
        checkEq("fee unknown", TempNo.feeRateFor(JsonText.parse("{}")), null);

        // ── 후보 스캔 ──
        TempNo.Settings on = settings(true, 0.80);
        checkEq("scan toggle off", scanCount(eventOf(marketJson("7°C", "0.9")),
                settings(false, 0.80), AT_7AM), 0);
        checkEq("scan band lo inclusive", scanCount(eventOf(marketJson("7°C", "0.8")), on, AT_7AM), 1);
        checkEq("scan band hi inclusive", scanCount(eventOf(marketJson("7°C", "0.998")), on, AT_7AM), 1);
        checkEq("scan 0.999 excluded", scanCount(eventOf(marketJson("7°C", "0.999")), on, AT_7AM), 0);
        checkEq("scan below lo excluded", scanCount(eventOf(marketJson("7°C", "0.799")), on, AT_7AM), 0);
        checkEq("scan custom lo excludes", scanCount(eventOf(marketJson("7°C", "0.92")),
                settings(true, 0.93), AT_7AM), 0);
        checkEq("scan custom lo inclusive", scanCount(eventOf(marketJson("7°C", "0.93")),
                settings(true, 0.93), AT_7AM), 1);
        {
            TempNo.Settings hi99 = TempNo.parseSettings(
                    "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempNoBandHi\":0.99}");
            checkEq("scan custom hi excludes 0.998", scanCount(eventOf(marketJson("7°C", "0.998")),
                    hi99, AT_7AM), 0);
            checkEq("scan custom hi inclusive", scanCount(eventOf(marketJson("7°C", "0.99")),
                    hi99, AT_7AM), 1);
            TempNo.Settings hour9 = TempNo.parseSettings(
                    "{\"tempNoEnabled\":true,\"tempNoBandLo\":0.8,\"tempEntryHourUtc\":9}");
            checkEq("scan entry hour 9 closed at 07:00", scanCount(eventOf(marketJson("7°C", "0.9")),
                    hour9, AT_7AM), 0);
            checkEq("scan entry hour 9 open at 09:00", scanCount(eventOf(marketJson("7°C", "0.9")),
                    hour9, Instant.parse("2026-11-18T09:00:00Z").toEpochMilli()), 1);
        }
        checkEq("scan tail excluded", scanCount(eventOf(marketJson("6°C or below", "0.9")), on, AT_7AM), 0);
        checkEq("scan range excluded", scanCount(eventOf(marketJson("50-51°F", "0.9")), on, AT_7AM), 0);
        checkEq("scan before window", scanCount(eventOf(marketJson("7°C", "0.9")), on,
                Instant.parse("2026-11-18T05:59:00Z").toEpochMilli()), 0);
        checkEq("scan after end", scanCount(eventOf(marketJson("7°C", "0.9")), on, END_MS), 0);
        {
            List<Object> ev = eventOf(marketJson("7°C", "0.9"));
            @SuppressWarnings("unchecked")
            List<Object> markets = (List<Object>) ((java.util.Map<?, ?>) ev.get(0)).get("markets");
            String slug = (String) ((java.util.Map<?, ?>) markets.get(0)).get("slug");
            Set<String> held = new HashSet<>();
            held.add(slug);
            checkEq("scan held excluded",
                    TempNo.scanTempCandidates(ev, held, new HashSet<>(), on, AT_7AM, null).size(), 0);
            Set<String> exclude = new HashSet<>();
            exclude.add(slug);
            checkEq("scan main-slug excluded",
                    TempNo.scanTempCandidates(ev, new HashSet<>(), new HashSet<>(), on, AT_7AM, exclude).size(), 0);
            List<ScanFilter.Candidate> cands = TempNo.scanTempCandidates(
                    ev, new HashSet<>(), new HashSet<>(), on, AT_7AM, null);
            checkEq("candidate side", cands.get(0).side, "no");
            checkEq("candidate strategy", cands.get(0).strategy, TempNo.STRATEGY);
            check("candidate band", cands.get(0).bandLo == 0.80 && cands.get(0).bandHi == 0.998);
            checkEq("candidate feeRate", cands.get(0).feeRate, 0.05);
            check("candidate price", cands.get(0).price == 0.9);
        }

        // ── 기온 전용 손절 폭 판정 (태그 방식) ──
        {
            Object page = jlist("[{\"outcomes\":\"[\\\"Yes\\\",\\\"No\\\"]\","
                    + "\"outcomePrices\":\"[0.9,0.1]\"}]");
            String tempSlug = EVENT_SLUG + "-7c";
            Object tempRow = JsonText.parse("{\"slug\":\"" + tempSlug + "\","
                    + "\"token_id\":\"t1\",\"avg_price\":0.98,\"current_size\":10,"
                    + "\"outcome\":\"No\"}");
            // 기온 태그 건도 손절한다 — 폭만 메인(-0.25)이 아닌 기온 전용(-0.15)
            checkEq("stoploss temp tagged triggers",
                    StopLoss.evaluate(tempRow, page, page, true).action, "triggered");
            checkEq("stoploss temp untagged triggers",
                    StopLoss.evaluate(tempRow, page, page, false).action, "triggered");
            checkEq("stoploss temp default triggers",
                    StopLoss.evaluate(tempRow, page, page).action, "triggered");
            Object mainRow = JsonText.parse("{\"slug\":\"some-other-market\","
                    + "\"token_id\":\"t1\",\"avg_price\":0.98,\"current_size\":10,"
                    + "\"outcome\":\"No\"}");
            checkEq("stoploss main still triggers", StopLoss.evaluate(mainRow, page, page).action, "triggered");

            // 폭 판별 — 진입 0.90, No 0.70: 기온 폭(-0.15)에서는 발화,
            // 메인 폭(-0.25)에서는 유지. No 0.80이면 기온 폭에서도 유지.
            Object page70 = jlist("[{\"outcomes\":\"[\\\"Yes\\\",\\\"No\\\"]\","
                    + "\"outcomePrices\":\"[0.3,0.7]\"}]");
            Object page80 = jlist("[{\"outcomes\":\"[\\\"Yes\\\",\\\"No\\\"]\","
                    + "\"outcomePrices\":\"[0.2,0.8]\"}]");
            Object row90 = JsonText.parse("{\"slug\":\"" + tempSlug + "\","
                    + "\"token_id\":\"t1\",\"avg_price\":0.90,\"current_size\":10,"
                    + "\"outcome\":\"No\"}");
            checkEq("stoploss temp drop fires at -0.20",
                    StopLoss.evaluate(row90, page70, page70, true).action, "triggered");
            checkEq("stoploss main drop holds at -0.20",
                    StopLoss.evaluate(row90, page70, page70, false).action, "holding");
            checkEq("stoploss temp drop holds at -0.10",
                    StopLoss.evaluate(row90, page80, page80, true).action, "holding");

            // 매수 기록 태그 조회 — 문자열·객체·부재 형태를 가른다
            @SuppressWarnings("unchecked")
            List<Object> tagged = (List<Object>) JsonText.parse(
                    "[{\"slug\":\"" + tempSlug + "\",\"strategy\":\"temp_no\"}]");
            @SuppressWarnings("unchecked")
            List<Object> plain = (List<Object>) JsonText.parse("[\"" + tempSlug + "\"]");
            @SuppressWarnings("unchecked")
            List<Object> empty = (List<Object>) JsonText.parse("[]");
            checkEq("tag: strategyOf tagged",
                    BoughtReconcile.strategyOf(tagged, tempSlug), TempNo.STRATEGY);
            checkEq("tag: strategyOf plain", BoughtReconcile.strategyOf(plain, tempSlug), null);
            checkEq("tag: strategyOf missing", BoughtReconcile.strategyOf(empty, tempSlug), null);
            check("tagged: tagged temp", TempNo.isTempTagged(tagged, tempSlug));
            check("tagged: plain temp not tagged", !TempNo.isTempTagged(plain, tempSlug));
            check("tagged: no record not tagged", !TempNo.isTempTagged(empty, tempSlug));
            check("tagged: tagged non-temp not tagged",
                    !TempNo.isTempTagged(tagged, "some-other-market"));

            // 정화는 태그 항목을 slug로 대조하고 원본 형태 그대로 남긴다
            Set<Object> boughtSet = new java.util.LinkedHashSet<>(tagged);
            Set<Object> onChain = new HashSet<>();
            onChain.add(tempSlug);
            BoughtReconcile.Reconcile rec = BoughtReconcile.reconcileBought(
                    boughtSet, onChain, new HashSet<>());
            check("reconcile: tagged kept", rec.kept.size() == 1 && rec.dropped.isEmpty());
            check("reconcile: tag preserved",
                    TempNo.STRATEGY.equals(BoughtReconcile.strategyOf(rec.kept, tempSlug)));
            BoughtReconcile.Reconcile rec2 = BoughtReconcile.reconcileBought(
                    boughtSet, new HashSet<>(), new HashSet<>());
            check("reconcile: tagged dropped when no truth",
                    rec2.dropped.size() == 1 && rec2.kept.isEmpty());
        }

        // ── 슬롯 예약 판정 (JS tempReserveBlocksMain과 같은 경계) ──────
        {
            int cap = 60; // NativeCycle.VERIFY_CAP
            check("reserve: main, slots 2, verified 0 → no block",
                    !TempNo.reserveBlocksMain(null, 2, 0, cap, 1));
            check("reserve: main, slots 1 → block",
                    TempNo.reserveBlocksMain(null, 1, 0, cap, 1));
            check("reserve: main, slots 0 → block",
                    TempNo.reserveBlocksMain(null, 0, 0, cap, 1));
            check("reserve: main, verified cap-2 → no block",
                    !TempNo.reserveBlocksMain(null, 5, cap - 2, cap, 1));
            check("reserve: main, verified cap-1 → block",
                    TempNo.reserveBlocksMain(null, 5, cap - 1, cap, 1));
            check("reserve: temp is never blocked",
                    !TempNo.reserveBlocksMain(TempNo.STRATEGY, 1, cap - 1, cap, 1));
            check("reserve: other strategy counts as main",
                    TempNo.reserveBlocksMain("something_else", 1, 0, cap, 1));
            // 예약 0 — 슬롯·검증이 바닥이어도 차단하지 않는다
            check("reserve 0: nothing blocked",
                    !TempNo.reserveBlocksMain(null, 0, cap, cap, 0));
            // 예약 3 — 경계가 3으로 넓어진다
            check("reserve 3: slots 3 → block",
                    TempNo.reserveBlocksMain(null, 3, 0, cap, 3));
            check("reserve 3: slots 4 → no block",
                    !TempNo.reserveBlocksMain(null, 4, 0, cap, 3));
            check("reserve 3: verified cap-3 → block",
                    TempNo.reserveBlocksMain(null, 9, cap - 3, cap, 3));
            check("reserve 3: verified cap-4 → no block",
                    !TempNo.reserveBlocksMain(null, 9, cap - 4, cap, 3));
        }

        // ── 예약 차선 판정 (JS tempReserveLaneAllows와 같은 경계) ──
        {
            // 기준: bet $3, 예비금 $2.5 → 현금이 5.5 이상이면 차선 허용
            check("lane: temp + hold + cash enough → allow",
                    TempNo.reserveLaneAllows(TempNo.STRATEGY, true, 1, 0, 70, 10, 3, 2.5));
            check("lane: cash exactly bet+reserve → allow",
                    TempNo.reserveLaneAllows(TempNo.STRATEGY, true, 1, 0, 70, 5.5, 3, 2.5));
            check("lane: cash below bet+reserve → deny",
                    !TempNo.reserveLaneAllows(TempNo.STRATEGY, true, 1, 0, 70, 5.49, 3, 2.5));
            check("lane: main strategy → deny",
                    !TempNo.reserveLaneAllows(null, true, 1, 0, 70, 10, 3, 2.5));
            check("lane: hold off → deny",
                    !TempNo.reserveLaneAllows(TempNo.STRATEGY, false, 1, 0, 70, 10, 3, 2.5));
            check("lane: lane exhausted → deny",
                    !TempNo.reserveLaneAllows(TempNo.STRATEGY, true, 0, 0, 70, 10, 3, 2.5));
            check("lane: positions full → deny",
                    !TempNo.reserveLaneAllows(TempNo.STRATEGY, true, 1, 70, 70, 10, 3, 2.5));
            check("lane: bet 0 → deny (sizing bet=0 가드 우회 차단)",
                    !TempNo.reserveLaneAllows(TempNo.STRATEGY, true, 1, 0, 70, 100, 0, 2.5));
        }

        // ── 차선 현금 차단 판정 (JS tempLaneCashBlocked와 같은 경계) ──
        {
            // 나머지 조건이 전부 통과하고 현금만 모자랄 때만 true다.
            check("laneCash: cash below bet+reserve → blocked",
                    TempNo.laneCashBlocked(TempNo.STRATEGY, true, 1, 0, 70, 5.49, 3, 2.5));
            check("laneCash: cash exactly bet+reserve → not blocked",
                    !TempNo.laneCashBlocked(TempNo.STRATEGY, true, 1, 0, 70, 5.5, 3, 2.5));
            check("laneCash: cash enough → not blocked",
                    !TempNo.laneCashBlocked(TempNo.STRATEGY, true, 1, 0, 70, 10, 3, 2.5));
            check("laneCash: main strategy → not blocked",
                    !TempNo.laneCashBlocked(null, true, 1, 0, 70, 0, 3, 2.5));
            check("laneCash: hold off → not blocked",
                    !TempNo.laneCashBlocked(TempNo.STRATEGY, false, 1, 0, 70, 0, 3, 2.5));
            check("laneCash: lane exhausted → not blocked",
                    !TempNo.laneCashBlocked(TempNo.STRATEGY, true, 0, 0, 70, 0, 3, 2.5));
            check("laneCash: positions full → not blocked",
                    !TempNo.laneCashBlocked(TempNo.STRATEGY, true, 1, 70, 70, 0, 3, 2.5));
            check("laneCash: bet 0 → not blocked",
                    !TempNo.laneCashBlocked(TempNo.STRATEGY, true, 1, 0, 70, 0, 0, 2.5));
        }

        // ── 창 대기 판정 (JS tempHasUpcomingWindow와 같은 경계) ──────
        // 이벤트 endDate 2026-11-18T12:00Z, 진입 창은 당일 06:00~12:00 UTC.
        {
            long beforeWindow = Instant.parse("2026-11-18T03:00:00Z").toEpochMilli();
            long afterWindow = Instant.parse("2026-11-18T13:00:00Z").toEpochMilli();
            List<Object> ev = eventOf(marketJson("7°C", "0.97"));
            check("upcoming: pre-window event → true",
                    TempNo.hasUpcomingWindow(ev, beforeWindow, TempNo.ENTRY_HOUR_UTC));
            check("upcoming: window open → true",
                    TempNo.hasUpcomingWindow(ev, AT_7AM, TempNo.ENTRY_HOUR_UTC));
            check("upcoming: past event only → false",
                    !TempNo.hasUpcomingWindow(ev, afterWindow, TempNo.ENTRY_HOUR_UTC));
            // 내일 이벤트는 전날 저녁부터 보인다 — 홀드 구간이 전날
            // 저녁까지 닿는다 (JS tempHasUpcomingWindow와 같은 경계)
            List<Object> evTomorrow = jlist("[{\"slug\":\"highest-temperature-in-seoul-on-november-19-2026\","
                    + "\"endDate\":\"2026-11-19T12:00:00Z\",\"markets\":[{"
                    + "\"slug\":\"highest-temperature-in-seoul-on-november-19-2026-7c\","
                    + "\"groupItemTitle\":\"7°C\",\"active\":true,\"closed\":false,"
                    + "\"acceptingOrders\":true}]}]");
            check("upcoming: tomorrow event seen the evening before → true",
                    TempNo.hasUpcomingWindow(evTomorrow,
                            Instant.parse("2026-11-18T20:00:00Z").toEpochMilli(),
                            TempNo.ENTRY_HOUR_UTC));
            check("upcoming: tomorrow event after its endDate → false",
                    !TempNo.hasUpcomingWindow(evTomorrow,
                            Instant.parse("2026-11-19T13:00:00Z").toEpochMilli(),
                            TempNo.ENTRY_HOUR_UTC));
            check("upcoming: empty events → false",
                    !TempNo.hasUpcomingWindow(new ArrayList<>(), beforeWindow,
                            TempNo.ENTRY_HOUR_UTC));
            check("upcoming: null events → false",
                    !TempNo.hasUpcomingWindow(null, beforeWindow, TempNo.ENTRY_HOUR_UTC));
            List<Object> nonTemp = jlist("[{\"slug\":\"some-other-event\","
                    + "\"endDate\":\"2026-11-18T12:00:00Z\",\"markets\":[]}]");
            check("upcoming: non-temp slug → false",
                    !TempNo.hasUpcomingWindow(nonTemp, beforeWindow, TempNo.ENTRY_HOUR_UTC));
            List<Object> badEnd = jlist("[{\"slug\":\"" + EVENT_SLUG + "\","
                    + "\"endDate\":\"not-a-date\",\"markets\":[]}]");
            check("upcoming: bad endDate → false",
                    !TempNo.hasUpcomingWindow(badEnd, beforeWindow, TempNo.ENTRY_HOUR_UTC));

            // v0.3.39 감사 D — 후보 가능 시장이 없으면 upcoming이 아니다
            String mktSlug = EVENT_SLUG + "-m7°C";
            check("auditD: all markets held → false",
                    !TempNo.hasUpcomingWindow(ev, beforeWindow, TempNo.ENTRY_HOUR_UTC,
                            Set.of(mktSlug), Set.of()));
            check("auditD: all markets bought → false",
                    !TempNo.hasUpcomingWindow(ev, beforeWindow, TempNo.ENTRY_HOUR_UTC,
                            Set.of(), Set.of(mktSlug)));
            check("auditD: candidate market present → true",
                    TempNo.hasUpcomingWindow(ev, beforeWindow, TempNo.ENTRY_HOUR_UTC,
                            Set.of("some-other-market"), Set.of()));
            List<Object> closedEv = jlist("[{\"slug\":\"" + EVENT_SLUG + "\","
                    + "\"endDate\":\"2026-11-18T12:00:00Z\",\"markets\":[{"
                    + "\"slug\":\"" + mktSlug + "\",\"groupItemTitle\":\"7°C\","
                    + "\"active\":true,\"closed\":true,\"acceptingOrders\":true}]}]");
            check("auditD: all markets closed → false",
                    !TempNo.hasUpcomingWindow(closedEv, beforeWindow, TempNo.ENTRY_HOUR_UTC,
                            Set.of(), Set.of()));
            List<Object> tailEv = eventOf(marketJson("6°C or below", "0.97"));
            check("auditD: tail bucket only → false",
                    !TempNo.hasUpcomingWindow(tailEv, beforeWindow, TempNo.ENTRY_HOUR_UTC,
                            Set.of(), Set.of()));
            List<Object> noMarkets = jlist("[{\"slug\":\"" + EVENT_SLUG + "\","
                    + "\"endDate\":\"2026-11-18T12:00:00Z\"}]");
            check("auditD: no markets → false",
                    !TempNo.hasUpcomingWindow(noMarkets, beforeWindow, TempNo.ENTRY_HOUR_UTC,
                            Set.of(), Set.of()));
        }

        // ── 검증 분기 (밴드·창) ──
        {
            Object page = jlist("[{\"slug\":\"" + EVENT_SLUG + "-7c\",\"active\":true,"
                    + "\"closed\":false,\"endDate\":\"2026-11-18T12:00:00Z\","
                    + "\"outcomePrices\":\"[0.03,0.97]\",\"clobTokenIds\":\"[\\\"t0\\\",\\\"t1\\\"]\"}]");
            Object book = JsonText.parse("{\"asks\":[{\"price\":0.97,\"size\":100}],"
                    + "\"bids\":[{\"price\":0.96,\"size\":100}],\"tick_size\":\"0.001\","
                    + "\"min_order_size\":5,\"neg_risk\":true}");
            CandidateVerifier.GammaSource gamma = slug -> page;
            CandidateVerifier.BookSource books = new CandidateVerifier.BookSource() {
                @Override public Object getOrderBook(String tokenId) { return book; }
                @Override public double getTickSize(String tokenId) { return 0.001; }
            };
            ScanFilter.Candidate tempCand = new ScanFilter.Candidate(
                    EVENT_SLUG + "-7c", "q", "no", 0.97, "2026-11-18T12:00:00Z",
                    TempNo.STRATEGY, 0.80, TempNo.BAND_HI, 0.05);
            CandidateVerifier.Result okRes = CandidateVerifier.verifyCandidate(tempCand, 5, gamma, books, AT_7AM);
            check("verify temp ok", okRes.ok);
            check("verify temp limitPrice", okRes.ok && Math.abs(okRes.limitPrice - 0.985) < 1e-9);
            check("verify temp negRisk", okRes.ok && okRes.negRisk);

            // 메인 후보는 당일 만기면 만기 범위에서 먼저 걸리므로, 밴드
            // 검사만 보려면 미래 만기 페이지를 따로 쓴다.
            Object mainPage = jlist("[{\"slug\":\"some-main-market\",\"active\":true,"
                    + "\"closed\":false,\"endDate\":\"2026-12-01T12:00:00Z\","
                    + "\"outcomePrices\":\"[0.03,0.97]\",\"clobTokenIds\":\"[\\\"t0\\\",\\\"t1\\\"]\"}]");
            CandidateVerifier.GammaSource mainGamma = slug -> mainPage;
            ScanFilter.Candidate mainCand = new ScanFilter.Candidate(
                    "some-main-market", "q", "no", 0.97, "2026-12-01T12:00:00Z");
            CandidateVerifier.Result mainRes = CandidateVerifier.verifyCandidate(mainCand, 5, mainGamma, books, AT_7AM);
            check("verify main band rejects 0.97 ask",
                    !mainRes.ok && "ask_out_of_band".equals(mainRes.reason));

            CandidateVerifier.Result earlyRes = CandidateVerifier.verifyCandidate(
                    tempCand, 5, gamma, books, Instant.parse("2026-11-18T05:00:00Z").toEpochMilli());
            check("verify temp before window rejects",
                    !earlyRes.ok && "end_out_of_range".equals(earlyRes.reason));

            Object richBook = JsonText.parse("{\"asks\":[{\"price\":0.999,\"size\":100}],"
                    + "\"bids\":[{\"price\":0.99,\"size\":100}],\"tick_size\":\"0.001\","
                    + "\"min_order_size\":5,\"neg_risk\":true}");
            CandidateVerifier.BookSource richBooks = new CandidateVerifier.BookSource() {
                @Override public Object getOrderBook(String tokenId) { return richBook; }
                @Override public double getTickSize(String tokenId) { return 0.001; }
            };
            CandidateVerifier.Result richRes = CandidateVerifier.verifyCandidate(tempCand, 5, gamma, richBooks, AT_7AM);
            check("verify temp ask 0.999 rejects",
                    !richRes.ok && "ask_out_of_band".equals(richRes.reason));

            // 상한 근처 탈락 메모 — 상한 0.998 바로 위 ask 0.999는 가격
            // 쌍 메모가 붙고, 메인과 조건 밖 탈락에는 붙지 않는다.
            check("nearcap: temp ask 0.999 over cap 0.998 carries note",
                    ("기온 상한 근처 탈락 " + EVENT_SLUG + "-7c · 스냅샷 0.97"
                            + " · bestAsk 0.999 · 밴드 상한 0.998").equals(richRes.note));
            check("nearcap: main reject carries no note", mainRes.note == null);
            ScanFilter.Candidate tempNarrowCand = new ScanFilter.Candidate(
                    EVENT_SLUG + "-7c", "q", "no", 0.97, "2026-11-18T12:00:00Z",
                    TempNo.STRATEGY, 0.80, 0.90, 0.05);
            CandidateVerifier.Result farRes = CandidateVerifier.verifyCandidate(
                    tempNarrowCand, 5, gamma, books, AT_7AM);
            check("nearcap: temp ask 0.97 over cap 0.90 (gap 0.07) no note",
                    !farRes.ok && "ask_out_of_band".equals(farRes.reason) && farRes.note == null);
            Object lowBook = JsonText.parse("{\"asks\":[{\"price\":0.75,\"size\":100}],"
                    + "\"bids\":[{\"price\":0.74,\"size\":100}],\"tick_size\":\"0.001\","
                    + "\"min_order_size\":5,\"neg_risk\":true}");
            CandidateVerifier.BookSource lowBooks = new CandidateVerifier.BookSource() {
                @Override public Object getOrderBook(String tokenId) { return lowBook; }
                @Override public double getTickSize(String tokenId) { return 0.001; }
            };
            CandidateVerifier.Result lowRes = CandidateVerifier.verifyCandidate(
                    tempCand, 5, gamma, lowBooks, AT_7AM);
            check("nearcap: temp ask below bandLo no note",
                    !lowRes.ok && "ask_out_of_band".equals(lowRes.reason) && lowRes.note == null);
            check("nearcap: exactly 0.01 over cap → note",
                    TempNo.nearCapRejectNote(TempNo.STRATEGY, "s", 0.9, 0.91, 0.9) != null);
            check("nearcap: main strategy → null",
                    TempNo.nearCapRejectNote(null, "s", 0.998, 0.999, 0.998) == null);
            check("nearcap: NaN ask → null",
                    TempNo.nearCapRejectNote(TempNo.STRATEGY, "s", 0.998, Double.NaN, 0.998) == null);

            // 진입 시작 시각 설정 — 07:00에 시작 9시면 창 밖이다
            CandidateVerifier.Result hour9Res = CandidateVerifier.verifyCandidate(
                    tempCand, 5, gamma, books, AT_7AM, TradeSettings.defaults(), 9);
            check("verify temp entry hour 9 rejects at 07:00",
                    !hour9Res.ok && "end_out_of_range".equals(hour9Res.reason));

            // 최소 수량 검사 — 기온은 유저 설정 금액이 정본이라 bet $3
            // (추정 약 3주 < min_order_size 5)에서도 탈락하지 않는다.
            CandidateVerifier.Result tempMinRes = CandidateVerifier.verifyCandidate(
                    tempCand, 3, gamma, books, AT_7AM);
            check("verify temp bet $3 passes min size", tempMinRes.ok);

            // 메인은 같은 금액에서 기존대로 최소 수량에 걸린다.
            Object mainLowPage = jlist("[{\"slug\":\"some-main-market\",\"active\":true,"
                    + "\"closed\":false,\"endDate\":\"2026-12-01T12:00:00Z\","
                    + "\"outcomePrices\":\"[0.25,0.75]\",\"clobTokenIds\":\"[\\\"t0\\\",\\\"t1\\\"]\"}]");
            Object mainLowBook = JsonText.parse("{\"asks\":[{\"price\":0.75,\"size\":100}],"
                    + "\"bids\":[{\"price\":0.74,\"size\":100}],\"tick_size\":\"0.001\","
                    + "\"min_order_size\":5,\"neg_risk\":false}");
            CandidateVerifier.BookSource mainLowBooks = new CandidateVerifier.BookSource() {
                @Override public Object getOrderBook(String tokenId) { return mainLowBook; }
                @Override public double getTickSize(String tokenId) { return 0.001; }
            };
            ScanFilter.Candidate mainLowCand = new ScanFilter.Candidate(
                    "some-main-market", "q", "no", 0.75, "2026-12-01T12:00:00Z");
            CandidateVerifier.Result mainMinRes = CandidateVerifier.verifyCandidate(
                    mainLowCand, 3, slug -> mainLowPage, mainLowBooks, AT_7AM);
            check("verify main bet $3 still rejects below min size",
                    !mainMinRes.ok && "below_min_order_size".equals(mainMinRes.reason));
            CandidateVerifier.Result mainMin5Res = CandidateVerifier.verifyCandidate(
                    mainLowCand, 5, slug -> mainLowPage, mainLowBooks, AT_7AM);
            check("verify main bet $5 passes min size", mainMin5Res.ok);

            // 메인 밴드를 설정으로 넓히면 같은 호가도 통과한다
            TradeSettings wideCfg = TradeSettings.parse("{\"bandHi\":0.99}");
            CandidateVerifier.Result wideRes = CandidateVerifier.verifyCandidate(
                    mainCand, 5, mainGamma, books, AT_7AM, wideCfg, TempNo.ENTRY_HOUR_UTC);
            check("verify main custom bandHi 0.99 accepts 0.97 ask", wideRes.ok);
        }

        // ── v0.3.39 감사 A — 매수 지정가 틱 유효성 ──────────────────
        // bandHi 0.998은 틱 0.01·0.005의 배수가 아니라, 종전 식은 0.998을
        // 그대로 내 priceValid 상한(1 - tick)을 넘어 서명이 전건 실패했다.
        {
            double lp1 = CandidateVerifier.buyLimitPrice(0.99, 0.015, 0.01, 0.998);
            check("auditA: tick 0.01 → 0.99", Math.abs(lp1 - 0.99) < 1e-9);
            check("auditA: tick 0.01 multiple+valid",
                    Math.abs(lp1 / 0.01 - Math.round(lp1 / 0.01)) < 1e-9
                            && lp1 >= 0.01 && lp1 <= 1 - 0.01);
            double lp2 = CandidateVerifier.buyLimitPrice(0.99, 0.015, 0.005, 0.998);
            check("auditA: tick 0.005 → 0.995", Math.abs(lp2 - 0.995) < 1e-9);
            check("auditA: tick 0.005 multiple+valid",
                    Math.abs(lp2 / 0.005 - Math.round(lp2 / 0.005)) < 1e-9
                            && lp2 >= 0.005 && lp2 <= 1 - 0.005);
            check("auditA: fine tick unchanged",
                    Math.abs(CandidateVerifier.buyLimitPrice(0.97, 0.015, 0.001, 0.998) - 0.985) < 1e-9);
            check("auditA: main band unchanged",
                    Math.abs(CandidateVerifier.buyLimitPrice(0.75, 0.015, 0.01, 0.9) - 0.77) < 1e-9);

            // 검증 전 경로 — 틱 0.01 북에서 기온 후보(상한 0.998)의 지정가
            Object page = jlist("[{\"slug\":\"" + EVENT_SLUG + "-7c\",\"active\":true,"
                    + "\"closed\":false,\"endDate\":\"2026-11-18T12:00:00Z\","
                    + "\"outcomePrices\":\"[0.03,0.97]\",\"clobTokenIds\":\"[\\\"t0\\\",\\\"t1\\\"]\"}]");
            Object book = JsonText.parse("{\"asks\":[{\"price\":0.99,\"size\":100}],"
                    + "\"bids\":[{\"price\":0.98,\"size\":100}],\"tick_size\":\"0.01\","
                    + "\"min_order_size\":5,\"neg_risk\":true}");
            CandidateVerifier.GammaSource gamma = slug -> page;
            CandidateVerifier.BookSource books = new CandidateVerifier.BookSource() {
                @Override public Object getOrderBook(String tokenId) { return book; }
                @Override public double getTickSize(String tokenId) { return 0.01; }
            };
            ScanFilter.Candidate cand = new ScanFilter.Candidate(
                    EVENT_SLUG + "-7c", "q", "no", 0.97, "2026-11-18T12:00:00Z",
                    TempNo.STRATEGY, 0.80, TempNo.BAND_HI, 0.05);
            CandidateVerifier.Result res = CandidateVerifier.verifyCandidate(cand, 5, gamma, books, AT_7AM);
            check("auditA: verify ok at tick 0.01", res.ok);
            check("auditA: verify limitPrice 0.99 valid",
                    res.ok && Math.abs(res.limitPrice - 0.99) < 1e-9
                            && res.limitPrice >= 0.01 && res.limitPrice <= 1 - 0.01);
        }

        System.out.println("TempNo tests: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
