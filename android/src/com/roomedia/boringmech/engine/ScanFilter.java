package com.roomedia.boringmech.engine;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * engine.js 스캔 필터 이식 — isSportsMarket + scanCandidates.
 * 시각 의존성(오늘·만기 상한 날짜)은 nowMs 인자로 주입한다 — 원본은
 * new Date()의 UTC 날짜(toISOString)를 쓰므로 같은 UTC 기준으로 계산한다.
 */
public final class ScanFilter {
    private ScanFilter() {}

    // engine.js 상수
    public static final double BAND_LO = 0.6;
    public static final double BAND_HI = 0.9;
    public static final double PRIORITY_BAND_LO = 0.7;
    public static final double PRIORITY_BAND_HI = 0.8;
    public static final double VOLUME_MIN = 10000;
    public static final int END_WINDOW_DAYS = 45;
    public static final long END_WINDOW_DAYS_MS = END_WINDOW_DAYS * 86400_000L;

    private static final Pattern SHORT_TERM_RE =
            Pattern.compile("(bitcoin|ethereum|solana|xrp|dogecoin|cardano)-up-or-down|up-or-down-");

    /** UTC 날짜 문자열 — JS Date#toISOString().slice(0, 10)와 같다. */
    public static String isoDay(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /**
     * 스포츠 판정 — 경기 마켓(sportsMarketType)만이 아니라 우승 아웃라이트까지.
     * 아웃라이트는 sportsMarketType이 비어 있지만 Gamma가 스포츠 수수료
     * 체계(feeType: sports_fees_*)를 붙이므로 그걸로 가른다.
     */
    public static boolean isSportsMarket(Object m) {
        if (Js.truthy(Js.field(m, "sportsMarketType"))) {
            return true;
        }
        Object fee = Js.field(m, "feeType");
        String feeStr = Js.truthy(fee) ? Js.str(fee) : "";
        return feeStr.startsWith("sports");
    }

    /** 스캔 후보 (engine.js scanCandidates 반환 원소). */
    public static final class Candidate {
        public final String slug;
        public final String question;
        public final String side;
        public final double price;
        public final String endDate;
        /** 전략 식별 — 메인 후보는 null, 기온 No 페이드는 TempNo.STRATEGY. */
        public final String strategy;
        /** 검증 단계에서 쓰는 후보 자신의 밴드 — 메인은 BAND_LO~BAND_HI와 같다. */
        public final double bandLo;
        public final double bandHi;
        /** 스캔 시점에 해석한 테이커 수수료율 — 해석 불가면 null (표시·기록용). */
        public final Double feeRate;

        public Candidate(String slug, String question, String side, double price, String endDate) {
            this(slug, question, side, price, endDate, null, BAND_LO, BAND_HI, null);
        }

        public Candidate(String slug, String question, String side, double price, String endDate,
                         String strategy, double bandLo, double bandHi, Double feeRate) {
            this.slug = slug;
            this.question = question;
            this.side = side;
            this.price = price;
            this.endDate = endDate;
            this.strategy = strategy;
            this.bandLo = bandLo;
            this.bandHi = bandHi;
            this.feeRate = feeRate;
        }
    }

    /**
     * 마켓 목록 → 매수 후보. 탈락 사유는 stats(사유→건수)에 쌓고 마지막에
     * total을 채운다 (stats가 null이면 세지 않는다 — 원본과 같다).
     * 정렬: 0.70–0.80 구간 먼저, 그 안에서 만기 가까운 순 (안정 정렬).
     */
    public static List<Candidate> scanCandidates(
            List<Object> markets, Set<String> heldSlugs, Set<String> boughtSlugs,
            Map<String, Integer> stats, long nowMs) {
        return scanCandidates(markets, heldSlugs, boughtSlugs, stats, nowMs,
                TradeSettings.defaults());
    }

    public static List<Candidate> scanCandidates(
            List<Object> markets, Set<String> heldSlugs, Set<String> boughtSlugs,
            Map<String, Integer> stats, long nowMs, TradeSettings cfg) {
        String todayIso = isoDay(nowMs);
        String endMaxIso = isoDay(nowMs + cfg.endWindowDays * 86400_000L);
        List<Candidate> out = new ArrayList<>();
        for (Object m : markets) {
            if (!Js.truthy(Js.field(m, "active")) || Js.truthy(Js.field(m, "closed"))) {
                drop(stats, "inactive");
                continue;
            }
            // 주문 수용 중단·보관 마켓 (=== false / === true 엄격 비교가 원본이다)
            if (Boolean.FALSE.equals(Js.field(m, "acceptingOrders"))
                    || Boolean.TRUE.equals(Js.field(m, "archived"))) {
                drop(stats, "not_accepting");
                continue;
            }
            Object slugRaw = Js.field(m, "slug");
            String slug = Js.truthy(slugRaw) ? Js.str(slugRaw) : "";
            if (SHORT_TERM_RE.matcher(slug).find()) {
                drop(stats, "short_term");
                continue;
            }
            if (isSportsMarket(m)) {
                drop(stats, "sports");
                continue;
            }
            if (heldSlugs.contains(slug) || boughtSlugs.contains(slug)) {
                drop(stats, "owned");
                continue;
            }
            Object endRaw = Js.field(m, "endDate");
            String endFull = Js.truthy(endRaw) ? Js.str(endRaw) : "";
            String end = endFull.length() > 10 ? endFull.substring(0, 10) : endFull;
            if (!(todayIso.compareTo(end) < 0 && end.compareTo(endMaxIso) <= 0)) {
                drop(stats, "end_out");
                continue;
            }
            // NaN은 모든 비교를 false로 만들어 가드를 통과한다 — 비유한 숫자는 탈락.
            double volume = Js.numOrZero(Js.field(m, "volume"));
            if (!Double.isFinite(volume) || volume < cfg.volumeMin) {
                drop(stats, "volume_low");
                continue;
            }
            List<Double> prices = parsePrices(Js.field(m, "outcomePrices"));
            if (prices == null || prices.size() < 2
                    || !Double.isFinite(prices.get(0)) || !Double.isFinite(prices.get(1))) {
                drop(stats, "price_bad");
                continue;
            }
            String side = prices.get(0) >= prices.get(1) ? "yes" : "no";
            double price = "yes".equals(side) ? prices.get(0) : prices.get(1);
            if (price < cfg.bandLo || price > cfg.bandHi) {
                drop(stats, "band_out");
                continue;
            }
            Object qRaw = Js.field(m, "question");
            out.add(new Candidate(
                    slug,
                    Js.truthy(qRaw) ? Js.str(qRaw) : "",
                    side,
                    price,
                    endFull,
                    null,
                    cfg.bandLo,
                    cfg.bandHi,
                    null));
        }
        if (stats != null) {
            stats.put("total", markets.size());
        }
        out.sort((a, b) -> {
            int g = bandGroup(a.price, cfg) - bandGroup(b.price, cfg);
            if (g != 0) {
                return g;
            }
            return a.endDate.compareTo(b.endDate);
        });
        return out;
    }

    private static int bandGroup(double p, TradeSettings cfg) {
        return p >= cfg.priorityBandLo && p < cfg.priorityBandHi ? 0 : 1;
    }

    private static void drop(Map<String, Integer> stats, String reason) {
        if (stats != null) {
            stats.merge(reason, 1, Integer::sum);
        }
    }

    /**
     * JSON.parse(문자열 필드 || "[]").map(Number) — 파싱 실패·비배열이면 null.
     * 원본은 비배열 파싱 결과도 prices.length·원소 검사에서 결국 price_bad로
     * 떨어지므로, 여기서 null로 합쳐도 판정은 같다.
     */
    static List<Double> parsePrices(Object raw) {
        String text = Js.truthy(raw) ? (raw instanceof String ? (String) raw : Js.str(raw)) : "[]";
        Object parsed;
        try {
            parsed = JsonText.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!(parsed instanceof List)) {
            return null;
        }
        List<Double> out = new ArrayList<>();
        for (Object e : (List<?>) parsed) {
            out.add(Js.num(e));
        }
        return out;
    }
}
