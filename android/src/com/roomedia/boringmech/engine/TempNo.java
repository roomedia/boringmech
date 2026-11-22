package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.write.ManualSell;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 기온 No 페이드 — engine.js 기온 섹션 이식.
 *
 * 일일 최고기온 이벤트(highest-temperature-in-<city>-on-<date>, negRisk
 * 그룹)의 정확한 정수 기온 시장 No를 밴드 안에서 사서 정산까지 보유한다.
 * 근거는 전용 백테스트 PASS (backtest-temp-no-20261119.md). 손절은 메인과
 * 분리해 기온 전용 폭(-0.15)을 적용하고, 기온 판정은 매수 태그가 있는
 * 건만 본다 (isTempTagged — StopLoss.evaluate와
 * NativeCycle.checkStopLossPosition의 판정). 기본 OFF — 뷰어 설정
 * (bm_settings)의 tempNoEnabled가 켜질 때만 후보를 만든다.
 */
public final class TempNo {
    private TempNo() {}

    public static final String STRATEGY = "temp_no";
    public static final String SETTINGS_KEY = "bm_settings";
    public static final double BAND_HI = 0.998;
    public static final double DEFAULT_BAND_LO = 0.80;
    /** 기온 슬롯 예약 수 — 기본 1개, 설정(tempNoReserveSlots)으로 0~MAX 지정. 0이면 예약 없음. */
    public static final int DEFAULT_RESERVE_SLOTS = 1;
    public static final int MAX_RESERVE_SLOTS = 70;
    /** 기온(weather) 카테고리 테이커 수수료율 — feeType 매핑의 기본값과 같다. */
    public static final double FEE_RATE = 0.05;
    public static final long CACHE_MS = 15 * 60_000L;
    public static final String SLUG_PREFIX = "highest-temperature-in-";
    /** 기온 진입 시작 시각(UTC 시) — 기본 6, 설정(tempEntryHourUtc)으로 0~23 지정. */
    public static final int ENTRY_HOUR_UTC = 6;
    private static final String[] MONTHS = {
            "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december",
    };
    /** 도시 슬러그 전체 — 백테스트 캐시(bt-temp-no-20261119)의 public-search 수확분. */
    public static final List<String> CITIES = Arrays.asList(
            "amsterdam", "ankara", "austin", "beijing", "buenos-aires", "busan",
            "cape-town", "chengdu", "chongqing", "dallas", "guangzhou", "helsinki",
            "hong-kong", "houston", "istanbul", "jeddah", "kuala-lumpur", "london",
            "los-angeles", "madrid", "miami", "milan", "munich", "nyc", "paris",
            "qingdao", "san-francisco", "sao-paulo", "seattle", "seoul", "shanghai",
            "shenzhen", "singapore", "taipei", "tel-aviv", "tokyo", "toronto",
            "warsaw", "wellington", "wuhan");

    private static final Pattern EXACT_TITLE_RE = Pattern.compile("^-?\\d+°[CF]$");

    /** 기온 이벤트·하위 시장 슬러그 판정 — 손절 제외의 슬러그 쪽 조건 (매수 태그와 함께 본다). */
    public static boolean isTempSlug(String slug) {
        return slug != null && slug.startsWith(SLUG_PREFIX);
    }

    /**
     * 기온 태그 판정 — 기온 슬러그이고 매수 기록에 temp_no 태그가 있을
     * 때만 기온으로 본다 (기온 전용 손절 폭 적용 대상). 메인 전략이 밴드
     * 겹침으로 산 기온 시장은 태그가 없어 메인 폭이 적용되고, 기록을
     * 찾을 수 없는 경우도 메인으로 간주한다 (안전 기본값).
     */
    public static boolean isTempTagged(List<Object> boughtEntries, String slug) {
        return isTempSlug(slug)
                && STRATEGY.equals(BoughtReconcile.strategyOf(boughtEntries, slug));
    }

    /**
     * 기온 슬롯 예약 판정 — 예약이 켜졌을 때(토글 ON + 기온 후보 존재,
     * 또는 오늘 진입 창이 열릴 이벤트 존재) 메인 후보를 뒤로 미룰지
     * 정하는 순수 함수 (JS tempReserveDefersMain과 같은 규칙). 예약
     * 활성 자체는 드라이버(NativeCycle)가 정한다. 메인 후보는 슬롯
     * reserveSlots개와 검증 reserveSlots건을 기온 몫으로 남겨 둔
     * 지점부터 미룬다. 기온 후보 자체는 미루지 않는다 (예약은 하한이지
     * 상한이 아니다). reserveSlots가 0이면 예약이 없어 아무것도
     * 미루지 않는다.
     */
    /**
     * 기온 예약 슬롯의 실효값 — 예약이 전체 포지션 상한을 넘으면 메인이
     * 사이클마다 전량 미뤄지므로, 실효 예약은 maxPositions로 제한한다
     * (v0.3.38 감사 축 3). 두 설정이 모두 있는 판정 지점에서만 계산한다.
     */
    public static int effectiveReserveSlots(int reserveSlots, int maxPositions) {
        return Math.max(0, Math.min(reserveSlots, maxPositions));
    }

    public static boolean reserveDefersMain(String strategy, int slotsLeft, int verified,
                                            int verifyCap, int reserveSlots) {
        if (STRATEGY.equals(strategy)) {
            return false;
        }
        if (reserveSlots <= 0) {
            return false;
        }
        return slotsLeft <= reserveSlots || verified >= verifyCap - reserveSlots;
    }

    /**
     * 정확한 정수 기온 판정 — groupItemTitle이 정수 기온만으로 된 것만
     * ("3°C"·"72°F"). "6°C or below"·"21°C or higher" 꼬리 버킷과
     * "50-51°F" 구간 버킷은 제외한다.
     */
    public static boolean isExactTempTitle(Object title) {
        return title instanceof String
                && EXACT_TITLE_RE.matcher(Js.trimJs((String) title)).matches();
    }

    /** 오늘(UTC) 날짜의 도시 이벤트 슬러그. */
    public static String eventSlugFor(String city, long nowMs) {
        LocalDate d = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate();
        return SLUG_PREFIX + city + "-on-" + MONTHS[d.getMonthValue() - 1]
                + "-" + d.getDayOfMonth() + "-" + d.getYear();
    }

    /** 오늘(UTC) 날짜의 전체 도시 이벤트 슬러그 목록. */
    public static List<String> todayEventSlugs(long nowMs) {
        List<String> out = new ArrayList<>();
        for (String city : CITIES) {
            out.add(eventSlugFor(city, nowMs));
        }
        return out;
    }

    /** Gamma endDate(ISO) → epoch ms. 형식이 아니면 null. */
    public static Long parseEndMs(Object endDate) {
        if (!(endDate instanceof String) || ((String) endDate).isEmpty()) {
            return null;
        }
        try {
            return Instant.parse((String) endDate).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 진입 창 — 이벤트 당일 06:00 UTC ≤ now < 이벤트 endDate.
     * 이벤트 날짜는 endDate의 UTC 날짜로 정한다 (실측 endDate는 당일
     * 12:00 UTC). 창 밖이면 후보를 만들지 않는다.
     */
    public static boolean windowOpen(long nowMs, long endMs) {
        return windowOpen(nowMs, endMs, ENTRY_HOUR_UTC);
    }

    /** 진입 창 — 시작 시각(UTC 시)을 설정값으로 받는 판정. */
    public static boolean windowOpen(long nowMs, long endMs, int entryHourUtc) {
        return windowStartMs(endMs, entryHourUtc) <= nowMs && nowMs < endMs;
    }

    /** 진입 창 시작 시각 — endDate의 UTC 날짜에 entryHourUtc시. */
    public static long windowStartMs(long endMs, int entryHourUtc) {
        LocalDate day = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate();
        return day.atTime(entryHourUtc, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * 오늘 진입 창이 열리는 기온 이벤트 존재 판정 — 이벤트의 창
     * 시작이 endDate보다 먼저이고 아직 endDate가 지나지 않았으면 그
     * 이벤트의 창은 오늘 열리거나 열려 있다 (JS tempHasUpcomingWindow와
     * 같은 규칙). 예약 연기를 기온 후보 존재에만 걸면 창이 열리기
     * 전에는 후보가 없어 메인이 마지막 슬롯까지 먹어 버리므로,
     * 드라이버는 이 판정으로도 예약을 건다. 창이 지난 이벤트만
     * 있으면 false다.
     */
    public static boolean hasUpcomingWindow(List<Object> events, long nowMs, int entryHourUtc) {
        if (events == null) {
            return false;
        }
        for (Object ev : events) {
            Object slugRaw = Js.field(ev, "slug");
            if (!isTempSlug(slugRaw instanceof String ? (String) slugRaw : null)) {
                continue;
            }
            Long endMs = parseEndMs(Js.field(ev, "endDate"));
            if (endMs == null) {
                continue;
            }
            if (windowStartMs(endMs, entryHourUtc) < endMs && nowMs < endMs) {
                return true;
            }
        }
        return false;
    }

    /** 뷰어 설정(bm_settings) 해석 결과. */
    public static final class Settings {
        public final boolean enabled;
        public final double bandLo;
        public final double bandHi;
        public final int entryHourUtc;
        public final int reserveSlots;

        Settings(boolean enabled, double bandLo, int reserveSlots) {
            this(enabled, bandLo, BAND_HI, ENTRY_HOUR_UTC, reserveSlots);
        }

        Settings(boolean enabled, double bandLo, double bandHi, int entryHourUtc, int reserveSlots) {
            this.enabled = enabled;
            this.bandLo = bandLo;
            this.bandHi = bandHi;
            this.entryHourUtc = entryHourUtc;
            this.reserveSlots = reserveSlots;
        }
    }

    /**
     * 설정 원문(JSON) 해석 — 토글은 정확히 true일 때만 켜진 것으로 보고,
     * 밴드 하한은 (0, 0.998] 범위를 벗어나면 기본값으로 되돌린다
     * (못 믿는 설정으로 사지 않는다). 밴드 하한·상한은 순서가 어긋나면
     * 쌍째로 기본값으로 되돌린다. 예약 슬롯 수는 0~MAX_RESERVE_SLOTS의
     * 정수만 받고, 그 외(음수·소수·범위 초과)는 기본값 1로 되돌린다.
     */
    public static Settings parseSettings(String raw) {
        Object o = null;
        if (raw != null && !raw.isEmpty()) {
            try {
                o = JsonText.parse(raw);
            } catch (IllegalArgumentException e) {
                o = null;
            }
        }
        if (!(o instanceof java.util.Map)) {
            return new Settings(false, DEFAULT_BAND_LO, BAND_HI, ENTRY_HOUR_UTC,
                    DEFAULT_RESERVE_SLOTS);
        }
        double hi = TradeSettings.strictFieldNum(o, "tempNoBandHi");
        double bandHi = Double.isFinite(hi) && hi > 0 && hi <= 1 ? hi : BAND_HI;
        double lo = TradeSettings.strictFieldNum(o, "tempNoBandLo");
        double bandLo = Double.isFinite(lo) && lo > 0 && lo <= BAND_HI ? lo : DEFAULT_BAND_LO;
        if (!(bandLo < bandHi)) {
            bandLo = DEFAULT_BAND_LO;
            bandHi = BAND_HI;
        }
        double hour = TradeSettings.strictFieldNum(o, "tempEntryHourUtc");
        int entryHourUtc = Double.isFinite(hour) && hour == Math.floor(hour)
                && hour >= 0 && hour <= 23
                ? (int) hour : ENTRY_HOUR_UTC;
        double rs = TradeSettings.strictFieldNum(o, "tempNoReserveSlots");
        int reserveSlots = Double.isFinite(rs) && rs == Math.floor(rs)
                && rs >= 0 && rs <= MAX_RESERVE_SLOTS
                ? (int) rs : DEFAULT_RESERVE_SLOTS;
        return new Settings(Boolean.TRUE.equals(Js.field(o, "tempNoEnabled")),
                bandLo, bandHi, entryHourUtc, reserveSlots);
    }

    /**
     * 테이커 수수료율 — feeType 카테고리 매핑(ManualSell.feeRateForFeeType)이
     * 1순위, 판정 불가면 시장의 feeSchedule rate를 쓴다. 기온은 weather
     * 카테고리라 0.05다.
     */
    public static Double feeRateFor(Object market) {
        Object fee = Js.field(market, "feeType");
        Double byType = ManualSell.feeRateForFeeType(Js.truthy(fee) ? Js.str(fee) : null);
        if (byType != null) {
            return byType;
        }
        double rate = Js.fieldNum(Js.field(market, "feeSchedule"), "rate");
        return Double.isFinite(rate) && rate >= 0 ? rate : null;
    }

    /**
     * 기온 이벤트 목록 → No 페이드 후보. 후보는 메인 후보와 같은 선정
     * 패스(슬롯·검증 캡 공유)를 타며, excludeSlugs(메인 후보 slug)와
     * 겹치면 메인 쪽이 이긴다. 정렬은 만기 가까운 순, 동률은 No 가격
     * 높은 순이다.
     */
    public static List<ScanFilter.Candidate> scanTempCandidates(
            List<Object> events, Set<String> heldSlugs, Set<String> boughtSlugs,
            Settings settings, long nowMs, Set<String> excludeSlugs) {
        List<ScanFilter.Candidate> out = new ArrayList<>();
        if (settings == null || !settings.enabled || events == null) {
            return out;
        }
        for (Object ev : events) {
            Object evSlugRaw = Js.field(ev, "slug");
            if (!isTempSlug(evSlugRaw instanceof String ? (String) evSlugRaw : null)) {
                continue;
            }
            Long endMs = parseEndMs(Js.field(ev, "endDate"));
            if (endMs == null || !windowOpen(nowMs, endMs, settings.entryHourUtc)) {
                continue;
            }
            Object marketsRaw = Js.field(ev, "markets");
            if (!(marketsRaw instanceof List)) {
                continue;
            }
            for (Object m : (List<?>) marketsRaw) {
                if (!Js.truthy(Js.field(m, "active")) || Js.truthy(Js.field(m, "closed"))) {
                    continue;
                }
                if (Boolean.FALSE.equals(Js.field(m, "acceptingOrders"))
                        || Boolean.TRUE.equals(Js.field(m, "archived"))) {
                    continue;
                }
                Object slugRaw = Js.field(m, "slug");
                String slug = Js.truthy(slugRaw) ? Js.str(slugRaw) : "";
                if (heldSlugs.contains(slug) || boughtSlugs.contains(slug)
                        || (excludeSlugs != null && excludeSlugs.contains(slug))) {
                    continue;
                }
                if (!isExactTempTitle(Js.field(m, "groupItemTitle"))) {
                    continue;
                }
                List<Double> prices = ScanFilter.parsePrices(Js.field(m, "outcomePrices"));
                if (prices == null || prices.size() < 2
                        || !Double.isFinite(prices.get(0)) || !Double.isFinite(prices.get(1))) {
                    continue;
                }
                // No 가격 = Gamma outcomePrices[1] (Yes의 보수와 같은 값).
                double price = prices.get(1);
                if (price < settings.bandLo || price > settings.bandHi) {
                    continue;
                }
                Object qRaw = Js.field(m, "question");
                Object endRaw = Js.field(m, "endDate");
                String endFull = Js.truthy(endRaw) ? Js.str(endRaw)
                        : Js.str(Js.field(ev, "endDate"));
                out.add(new ScanFilter.Candidate(
                        slug,
                        Js.truthy(qRaw) ? Js.str(qRaw) : "",
                        "no",
                        price,
                        endFull,
                        STRATEGY,
                        settings.bandLo,
                        settings.bandHi,
                        feeRateFor(m)));
            }
        }
        out.sort((a, b) -> {
            int c = a.endDate.compareTo(b.endDate);
            if (c != 0) {
                return c;
            }
            return Double.compare(b.price, a.price);
        });
        return out;
    }
}
