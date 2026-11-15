package com.roomedia.boringmech.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * engine.js verifyCandidate 이식 — 후보 검증 전 단계.
 * 네트워크는 인터페이스 뒤에 분리한다 (Gamma 페이지 조회·CLOB 오더북 조회).
 * 조회 자체의 예외는 원본처럼 그대로 던진다 — 호출자가 verify_error로 센다.
 * 판정 결과는 Result { ok, reason | tokenId, limitPrice, estShares, tickSize, negRisk }.
 */
public final class CandidateVerifier {
    private CandidateVerifier() {}

    // engine.js 상수
    public static final double SPREAD_MAX = 0.02;
    public static final double ASK_DEPTH_MIN_USD = 10.0;
    public static final double SLIPPAGE_CAP = 0.015;
    public static final double SNAPSHOT_MATCH_TOL = 0.03;
    /** 신호가 대비 호가 상한 여유 — bestAsk > cand.price + 0.05면 탈락. */
    public static final double ASK_ABOVE_SIGNAL = 0.05;

    /** Gamma 마켓 페이지(배열 모델)를 slug로 조회한다. */
    public interface GammaSource {
        Object fetchBySlug(String slug) throws Exception;
    }

    /** CLOB 오더북 조회 + 틱 폴백 조회. getTickSize는 Number 변환까지 끝낸 값을 돌려준다. */
    public interface BookSource {
        Object getOrderBook(String tokenId) throws Exception;

        double getTickSize(String tokenId) throws Exception;
    }

    public static final class Result {
        public final boolean ok;
        public final String reason;
        public final String tokenId;
        public final double limitPrice;
        public final double estShares;
        public final String tickSize;
        public final boolean negRisk;

        private Result(boolean ok, String reason, String tokenId, double limitPrice,
                       double estShares, String tickSize, boolean negRisk) {
            this.ok = ok;
            this.reason = reason;
            this.tokenId = tokenId;
            this.limitPrice = limitPrice;
            this.estShares = estShares;
            this.tickSize = tickSize;
            this.negRisk = negRisk;
        }

        static Result reject(String reason) {
            return new Result(false, reason, null, 0, 0, null, false);
        }

        static Result ok(String tokenId, double limitPrice, double estShares,
                         String tickSize, boolean negRisk) {
            return new Result(true, null, tokenId, limitPrice, estShares, tickSize, negRisk);
        }
    }

    public static Result verifyCandidate(
            ScanFilter.Candidate cand, double bet, GammaSource gamma, BookSource books, long nowMs)
            throws Exception {
        Object page = gamma.fetchBySlug(cand.slug);
        Object m = null;
        if (page instanceof List && !((List<?>) page).isEmpty()) {
            m = ((List<?>) page).get(0);
        }
        if (!Js.truthy(m)) {
            return Result.reject("gamma_market_missing");
        }
        if (ScanFilter.isSportsMarket(m)) {
            return Result.reject("sports");
        }
        if (Boolean.FALSE.equals(Js.field(m, "acceptingOrders"))
                || Boolean.TRUE.equals(Js.field(m, "archived"))) {
            return Result.reject("market_not_accepting");
        }
        List<Object> tokens;
        List<Double> prices;
        try {
            // 거래 식별자는 마켓의 프로토콜 버전으로 고른다: v2는 positionIds
            // 배열을 그대로, v1(CTF)은 clobTokenIds JSON 문자열을 파싱한다.
            Object posIds = Js.field(m, "positionIds");
            if ("v2".equals(Js.field(m, "version")) && posIds instanceof List
                    && ((List<?>) posIds).size() >= 2) {
                @SuppressWarnings("unchecked")
                List<Object> t = (List<Object>) posIds;
                tokens = t;
            } else {
                tokens = parseJsonArray(Js.field(m, "clobTokenIds"));
            }
            prices = ScanFilter.parsePrices(Js.field(m, "outcomePrices"));
            if (prices == null) {
                throw new IllegalArgumentException("outcomePrices parse");
            }
        } catch (IllegalArgumentException e) {
            return Result.reject("gamma_parse_error");
        }
        int idx = "yes".equals(cand.side) ? 0 : 1;
        if (tokens.size() <= idx || prices.size() <= idx) {
            return Result.reject("gamma_shape_error");
        }
        if (!Double.isFinite(prices.get(0)) || !Double.isFinite(prices.get(1))) {
            return Result.reject("gamma_shape_error");
        }
        // 스캔 후 상태가 바뀐 마켓은 사지 않는다 — 활성·종료·만기를 다시 본다.
        if (!Js.truthy(Js.field(m, "active")) || Js.truthy(Js.field(m, "closed"))) {
            return Result.reject("market_inactive");
        }
        {
            String todayIso = ScanFilter.isoDay(nowMs);
            String endMaxIso = ScanFilter.isoDay(nowMs + ScanFilter.END_WINDOW_DAYS_MS);
            Object endRaw = Js.field(m, "endDate");
            String endFull = Js.truthy(endRaw) ? Js.str(endRaw) : "";
            String end = endFull.length() > 10 ? endFull.substring(0, 10) : endFull;
            if (!(todayIso.compareTo(end) < 0 && end.compareTo(endMaxIso) <= 0)) {
                return Result.reject("end_out_of_range");
            }
        }
        if (Math.abs(prices.get(idx) - cand.price) > SNAPSHOT_MATCH_TOL) {
            return Result.reject("snapshot_mismatch");
        }
        // 선택한 쪽이 여전히 우세 쪽인지 다시 본다.
        if (prices.get(idx) < prices.get(1 - idx)) {
            return Result.reject("side_flipped");
        }
        Object tokenRaw = tokens.get(idx);
        String tokenId = tokenRaw instanceof String ? (String) tokenRaw : Js.str(tokenRaw);
        Object book = books.getOrderBook(tokenId);
        List<double[]> asks = bookSide(book, "asks");
        List<double[]> bids = bookSide(book, "bids");
        // asks 오름차순·bids 내림차순 (원본 sort는 안정 정렬 — Java List.sort도 같다)
        asks.sort((a, b) -> Double.compare(a[0], b[0]));
        bids.sort((a, b) -> Double.compare(b[0], a[0]));
        if (asks.isEmpty()) {
            return Result.reject("no_asks");
        }
        if (bids.isEmpty()) {
            return Result.reject("no_bids");
        }
        double bestAsk = asks.get(0)[0];
        double bestBid = bids.get(0)[0];
        if (bestAsk < ScanFilter.BAND_LO || bestAsk > ScanFilter.BAND_HI) {
            return Result.reject("ask_out_of_band");
        }
        // 신호가보다 크게 비싸진 호가는 사지 않는다.
        if (bestAsk > cand.price + ASK_ABOVE_SIGNAL) {
            return Result.reject("ask_above_signal");
        }
        if (bestAsk - bestBid > SPREAD_MAX + 1e-9) {
            return Result.reject("spread_too_wide");
        }
        if (asks.get(0)[1] * bestAsk < Math.max(ASK_DEPTH_MIN_USD, bet)) {
            return Result.reject("ask_depth_low");
        }
        Object tickRaw = Js.field(book, "tick_size");
        double tick = Js.truthy(tickRaw) ? Js.num(tickRaw) : books.getTickSize(tokenId);
        double limitPrice = Math.min(Js.roundTick(bestAsk + SLIPPAGE_CAP, tick), ScanFilter.BAND_HI);
        // 최소 주문 수량 검사용 추정치 — 실제 주식수는 시장가 주문이 금액에서 역산한다
        double estShares = bet / limitPrice;
        double minSize = Js.numOrZero(Js.field(book, "min_order_size"));
        if (estShares < minSize) {
            return Result.reject("below_min_order_size");
        }
        return Result.ok(
                tokenId,
                limitPrice,
                estShares,
                Js.str(tick),
                Js.truthy(Js.field(book, "neg_risk")));
    }

    /** 오더북 한쪽(asks/bids) — {price, size} 숫자 쌍으로 변환하고 비유한 값을 버린다. */
    private static List<double[]> bookSide(Object book, String key) {
        Object raw = Js.field(book, key);
        List<double[]> out = new ArrayList<>();
        if (raw == Js.UNDEFINED || raw == null) {
            return out;
        }
        if (!(raw instanceof List)) {
            // 원본은 (book.asks || []).map(...) — 배열이 아닌 truthy 값이면
            // TypeError로 던져 호출자가 verify_error로 센다.
            throw new IllegalStateException(key + ".map is not a function");
        }
        for (Object a : (List<?>) raw) {
            double price = Js.fieldNum(a, "price");
            double size = Js.fieldNum(a, "size");
            if (Double.isFinite(price) && Double.isFinite(size)) {
                out.add(new double[]{price, size});
            }
        }
        return out;
    }

    /** JSON.parse(문자열 필드 || "[]") — 파싱 실패는 예외, 비배열은 빈 목록으로 본다. */
    private static List<Object> parseJsonArray(Object raw) {
        String text = Js.truthy(raw) ? (raw instanceof String ? (String) raw : Js.str(raw)) : "[]";
        Object parsed = JsonText.parse(text);
        if (parsed instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) parsed;
            return l;
        }
        return new ArrayList<>();
    }
}
