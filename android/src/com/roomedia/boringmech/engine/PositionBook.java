package com.roomedia.boringmech.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * engine.js의 장부 집계·사이징 순수 로직 이식.
 * 동등 재현이 목적이라 원본의 quirk도 그대로 둔다 (예: stopLossSlugs는
 * 원장 원본 전체의 slug를 검증 없이 수집, open 행은 dedupe하지 않음).
 *
 * 인자 규약: 행 목록은 List&lt;Object&gt; (원소는 Map 모델), 조회 실패처럼
 * JS에서 배열이 아닌 값은 null로 넘긴다 (summarizeBook의 정산 미상 판정).
 */
public final class PositionBook {
    private PositionBook() {}

    // engine.js 상수 (29~33행)
    public static final double BET_PCT = 0.01;
    public static final double BET_MIN_USD = 5.0;
    public static final double CASH_FLOOR_PCT = 0.3;
    public static final double RESERVE_USD = 2.87;
    public static final double MAX_POSITIONS = 70;

    /** 포지션 식별 키 — 정산 목록 간 중복 제거용. 포지션은 토큰(자산) 단위다. */
    public static String positionKey(Object p) {
        Object tokenId = Js.field(p, "token_id");
        if (Js.truthy(tokenId)) {
            return "t:" + Js.str(tokenId);
        }
        Object conditionId = Js.field(p, "condition_id");
        if (Js.truthy(conditionId)) {
            Object outcomeIndex = Js.field(p, "outcome_index");
            // JS: p.outcome_index ?? "" — null·undefined일 때만 빈 문자열
            String oi = (outcomeIndex == Js.UNDEFINED || outcomeIndex == null)
                    ? "" : Js.str(outcomeIndex);
            return "c:" + Js.str(conditionId) + "|" + oi;
        }
        return null;
    }

    // 정산·손익 표시의 정본은 Data API v2 포지션 행 자체다 (2026-11-24 방향
    // 전환, engine.js summarizeBook 앞 주석과 동일). 정산 표시 = CLOSED
    // 조회분의 realized_pnl 합산, 승패도 그 부호. 내부 원장 기록은 표시
    // 경로에서 빼고 손절 건수 분류(토큰 교집합)에만 쓴다.

    /** 건당 베팅액 — 평가금의 1%, 하한 $5. (equity || 0) 의미론 포함. */
    public static double betUsdFor(double equity) {
        return betUsdFor(equity, TradeSettings.defaults());
    }

    public static double betUsdFor(double equity, TradeSettings cfg) {
        double e = (equity == 0 || Double.isNaN(equity)) ? 0 : equity;
        return Js.round2(Math.max(cfg.betMinUsd, e * cfg.betPct));
    }

    /** 사이징 결과 (engine.js sizing 반환 객체). slots는 JS number라 double로 둔다. */
    public static final class Sizing {
        public final double cashFloor;
        public final double available;
        public final double bet;
        public final double slots;

        Sizing(double cashFloor, double available, double bet, double slots) {
            this.cashFloor = cashFloor;
            this.available = available;
            this.bet = bet;
            this.slots = slots;
        }
    }

    public static Sizing sizing(double cash, double equity, double openCount) {
        return sizing(cash, equity, openCount, TradeSettings.defaults());
    }

    public static Sizing sizing(double cash, double equity, double openCount, TradeSettings cfg) {
        // 비유한 입력이면 사이징을 닫는다 — 측정값을 못 믿으면 사지 않는다
        if (!Double.isFinite(cash) || !Double.isFinite(equity) || !Double.isFinite(openCount)) {
            return new Sizing(cfg.reserveUsd, 0, betUsdFor(0, cfg), 0);
        }
        double cashFloor = Js.round2(Math.max(cfg.reserveUsd, equity * cfg.cashFloorPct));
        double available = Js.round2(Math.max(0, cash - cashFloor));
        double bet = betUsdFor(equity, cfg);
        // bet이 0 이하면 나눗셈이 무한대로 터져 슬롯이 상한까지 찬다
        // (betMinUsd=0 + 작은 betPct 조합) — 베팅액이 없으면 슬롯도 없다.
        double cashSlots = bet > 0 ? Math.floor((available + 1e-9) / bet) : 0;
        double slots = Math.max(0, Math.min(cashSlots, cfg.maxPositions - openCount));
        return new Sizing(cashFloor, available, bet, slots);
    }

    /** 장부 집계 결과 (engine.js summarizeBook 반환 객체). settled*는 미확정이면 null. */
    public static final class Book {
        public int openCount;
        public Double positionsPnlPct;
        public Integer settledCount;
        public Integer settledWins;
        public Integer settledLosses;
        public Double settledRealizedPnl;
        public Integer settledRealizedExcluded;
        public Integer settledStopLoss;
        public final LinkedHashSet<String> heldSlugs = new LinkedHashSet<>();
        public final LinkedHashSet<String> stopLossSlugs = new LinkedHashSet<>();
    }

    /**
     * open 판정 단일 기준 — 장부 집계·스냅샷 종목 목록·손절 대상이 전부
     * 이 판정을 공유한다. status가 "OPEN"인 행이 본체. status가 비어 있는
     * 행은 수량이 있으면 보유로 본다 (실기기 응답에는 status가 없는 행이
     * 있다). 그 외 명시적 status("CLOSED" 등)는 보유가 아니다.
     */
    public static boolean isOpenRow(Object p) {
        Object status = Js.field(p, "status");
        if ("OPEN".equals(status)) {
            return true;
        }
        if (!Js.truthy(status)) {
            double size = Js.fieldNum(p, "current_size");
            return Double.isFinite(size) && size > 0;
        }
        return false;
    }

    /** open 행만 추린다 — 중복 행도 dedupe하지 않는다 (원본 그대로). */
    public static List<Object> openRows(List<Object> rows) {
        List<Object> open = new ArrayList<>();
        if (rows != null) {
            for (Object p : rows) {
                if (isOpenRow(p)) {
                    open.add(p);
                }
            }
        }
        return open;
    }

    /**
     * 포지션 장부 집계 — engine.js summarizeBook의 동등 이식.
     * 정산 표시는 CLOSED 조회분의 realized_pnl만으로 만든다 (2026-11-24
     * 방향 전환). 손절 기록은 손절 건수 분류(토큰 교집합)와 stopLossSlugs
     * (재매수 차단)에만 쓰인다.
     */
    public static Book summarizeBook(
            List<Object> allRows,
            List<Object> closedRows,
            List<Object> stopLossRecords) {
        Book book = new Book();

        // open 판정은 isOpenRow 단일 기준을 쓴다 (스냅샷 종목 목록과 동일).
        List<Object> open = openRows(allRows);
        book.openCount = open.size();

        // 보유 등락 — 행의 total_pnl을 그대로 합산하고, 없는 행만
        // 현재가치 − 수량×평균가로 폴백한다 (전부 API 필드).
        double openPnlSum = 0;
        double openCostBasis = 0;
        int openPnlRows = 0;
        for (Object p : open) {
            double v = Js.fieldNum(p, "current_value");
            if (!Double.isFinite(v)) {
                continue;
            }
            double pnl = Js.fieldNum(p, "total_pnl");
            if (!Double.isFinite(pnl)) {
                double c = Js.fieldNum(p, "current_size") * Js.fieldNum(p, "avg_price");
                if (!Double.isFinite(c)) {
                    continue;
                }
                pnl = v - c;
            }
            openPnlSum += pnl;
            openCostBasis += v - pnl;
            openPnlRows += 1;
        }
        book.positionsPnlPct =
                openPnlRows > 0 && openCostBasis > 0 ? (openPnlSum / openCostBasis) * 100 : null;

        for (Object p : open) {
            Object slug = Js.field(p, "slug");
            if (Js.truthy(slug)) {
                book.heldSlugs.add(Js.str(slug));
            }
        }
        // 손절 원장의 slug는 검증 없이 원본 전체에서 수집한다 (원본 quirk).
        if (stopLossRecords != null) {
            for (Object r : stopLossRecords) {
                Object slug = Js.field(r, "slug");
                if (Js.truthy(slug)) {
                    book.stopLossSlugs.add(Js.str(slug));
                }
            }
        }

        if (closedRows != null) {
            // 손절 분류용 토큰 집합 — 기록의 금액·승패는 표시로 가져오지 않는다.
            Set<String> stopTokens = new HashSet<>();
            if (stopLossRecords != null) {
                for (Object r : stopLossRecords) {
                    Object tokenId = Js.field(r, "token_id");
                    if (tokenId instanceof String && !((String) tokenId).isEmpty()) {
                        stopTokens.add("t:" + tokenId);
                    }
                }
            }
            Set<String> seen = new HashSet<>();
            int seq = 0;
            int settledCount = 0;
            int settledWins = 0;
            int settledLosses = 0;
            int settledStopLoss = 0;
            int settledRealizedExcluded = 0;
            double pnlSum = 0;
            int pnlKnown = 0;
            for (Object p : closedRows) {
                String key = PositionBook.positionKey(p);
                if (key == null) {
                    key = "u:" + (seq++);
                }
                if (!seen.add(key)) {
                    continue;
                }
                settledCount += 1;
                if (stopTokens.contains(key)) {
                    settledStopLoss += 1;
                }
                double rp = Js.fieldNum(p, "realized_pnl");
                if (!Double.isFinite(rp)) {
                    settledRealizedExcluded += 1;
                    continue;
                }
                if (rp > 0) {
                    settledWins += 1;
                } else if (rp < 0) {
                    settledLosses += 1;
                }
                pnlSum += rp;
                pnlKnown += 1;
            }
            book.settledCount = settledCount;
            book.settledWins = settledWins;
            book.settledLosses = settledLosses;
            book.settledStopLoss = settledStopLoss;
            book.settledRealizedExcluded = settledRealizedExcluded;
            // 합산 가능한 행이 하나도 없는데 정산 건만 있으면 null — 0으로 지어내지 않는다.
            if (pnlKnown > 0 || settledCount == 0) {
                book.settledRealizedPnl = Js.round2(pnlSum);
            }
        }
        return book;
    }
}
