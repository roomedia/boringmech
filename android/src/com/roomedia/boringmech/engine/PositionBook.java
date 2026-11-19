package com.roomedia.boringmech.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * engine.js의 장부 집계·사이징 순수 로직 이식.
 * 동등 재현이 목적이라 원본의 quirk도 그대로 둔다 (예: stopLossSlugs는
 * 원장 원본 전체의 slug를 검증 없이 수집, open 행은 dedupe하지 않음).
 *
 * 인자 규약: 행 목록은 List&lt;Object&gt; (원소는 Map 모델), 조회 실패처럼
 * JS에서 배열이 아닌 값은 null로 넘긴다 (summarizeBook의 settledKnown 판정).
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

    /**
     * CLOSED 행의 승패 판정 — 1순위 current_price (1 이상이면 승, 0 이하면 패),
     * 2순위 realized_pnl 부호. 둘 다로 못 가르면 null.
     */
    public static String judgeClosedRow(Object p) {
        double cp = Js.fieldNum(p, "current_price");
        if (Double.isFinite(cp)) {
            if (cp >= 1) {
                return "win";
            }
            if (cp <= 0) {
                return "loss";
            }
        }
        double rp = Js.fieldNum(p, "realized_pnl");
        if (Double.isFinite(rp)) {
            if (rp > 0) {
                return "win";
            }
            if (rp < 0) {
                return "loss";
            }
        }
        return null;
    }

    /**
     * 정산 행의 실현손익 — win: current_value − entry_cost_usdc,
     * loss: −entry_cost_usdc, closed: realized_pnl.
     * 필요한 필드가 숫자가 아니면 null (합산 제외 대상).
     */
    static Double realizedOf(String kind, Object p) {
        double cost = Js.fieldNum(p, "entry_cost_usdc");
        if ("win".equals(kind)) {
            double value = Js.fieldNum(p, "current_value");
            return Double.isFinite(value) && Double.isFinite(cost) ? value - cost : null;
        }
        if ("loss".equals(kind)) {
            return Double.isFinite(cost) ? -cost : null;
        }
        double rp = Js.fieldNum(p, "realized_pnl");
        return Double.isFinite(rp) ? rp : null;
    }

    /** 건당 베팅액 — 평가금의 1%, 하한 $5. (equity || 0) 의미론 포함. */
    public static double betUsdFor(double equity) {
        double e = (equity == 0 || Double.isNaN(equity)) ? 0 : equity;
        return Js.round2(Math.max(BET_MIN_USD, e * BET_PCT));
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
        // 비유한 입력이면 사이징을 닫는다 — 측정값을 못 믿으면 사지 않는다
        if (!Double.isFinite(cash) || !Double.isFinite(equity) || !Double.isFinite(openCount)) {
            return new Sizing(RESERVE_USD, 0, betUsdFor(0), 0);
        }
        double cashFloor = Js.round2(Math.max(RESERVE_USD, equity * CASH_FLOOR_PCT));
        double available = Js.round2(Math.max(0, cash - cashFloor));
        double bet = betUsdFor(equity);
        double cashSlots = Math.floor((available + 1e-9) / bet);
        double slots = Math.max(0, Math.min(cashSlots, MAX_POSITIONS - openCount));
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

    private static final class Entry {
        final String verdict;
        final Double pnl;
        final String kind;

        Entry(String verdict, Double pnl, String kind) {
            this.verdict = verdict;
            this.pnl = pnl;
            this.kind = kind;
        }
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

    private interface VerdictFn {
        String of(Object row);
    }

    /**
     * 포지션 장부 집계 — engine.js summarizeBook의 동등 이식.
     * attemptedTokens는 JS Set 대응 (원소 자료형까지 같은 값으로 조회한다).
     */
    public static Book summarizeBook(
            List<Object> allRows,
            List<Object> winRows,
            List<Object> lostRows,
            List<Object> closedRows,
            List<Object> stopLossRecords,
            Set<Object> attemptedTokens) {
        return summarizeBook(allRows, winRows, lostRows, closedRows,
                stopLossRecords, null, attemptedTokens);
    }

    /**
     * manualSellRecords — 수동 매도 원장 기록. 손절 기록과 같은 우선순위로
     * 집계에 먼저 들어가되 kind가 달라 settledStopLoss에는 세지 않는다.
     * 기록이 없으면 수동 매도로 청산된 토큰이 CLOSED 행의 최종가로 잘못
     * 가려지고 실현손익도 실제 회수액과 어긋난다 (v0.3.33 감사).
     */
    public static Book summarizeBook(
            List<Object> allRows,
            List<Object> winRows,
            List<Object> lostRows,
            List<Object> closedRows,
            List<Object> stopLossRecords,
            List<Object> manualSellRecords,
            Set<Object> attemptedTokens) {
        Set<Object> attempted = attemptedTokens != null ? attemptedTokens : Set.of();
        Book book = new Book();

        // open 판정은 isOpenRow 단일 기준을 쓴다 (스냅샷 종목 목록과 동일).
        List<Object> open = openRows(allRows);
        book.openCount = open.size();

        double value = 0;
        double cost = 0;
        for (Object p : open) {
            double v = Js.fieldNum(p, "current_value");
            double c = Js.fieldNum(p, "current_size") * Js.fieldNum(p, "avg_price");
            if (Double.isFinite(v)) {
                value += v;
            }
            if (Double.isFinite(c)) {
                cost += c;
            }
        }
        book.positionsPnlPct =
                open.size() > 0 && cost > 0 ? ((value - cost) / cost) * 100 : null;

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

        boolean settledKnown = winRows != null && lostRows != null && closedRows != null;
        if (settledKnown) {
            Map<String, Entry> entries = new LinkedHashMap<>();
            int[] seq = {0};

            // 손절 기록이 조회 목록보다 먼저다 — 같은 토큰의 CLOSED 행은
            // dedupe로 걸러져 기록을 덮지 못한다.
            if (stopLossRecords != null) {
                for (Object r : stopLossRecords) {
                    if (!Js.truthy(r)) {
                        continue;
                    }
                    Object tokenId = Js.field(r, "token_id");
                    if (!(tokenId instanceof String) || ((String) tokenId).isEmpty()) {
                        continue;
                    }
                    String key = "t:" + tokenId;
                    if (entries.containsKey(key)) {
                        continue;
                    }
                    double rp = Js.fieldNum(r, "realized_pnl");
                    boolean known = Double.isFinite(rp);
                    String verdict = known ? (rp > 0 ? "win" : rp < 0 ? "loss" : null) : null;
                    entries.put(key, new Entry(verdict, known ? rp : null, "stoploss"));
                }
            }
            // 수동 매도 기록도 조회 목록보다 먼저다 — 실제 매도 실현손익으로
            // 가르고, 손절 건수에는 넣지 않는다 (kind "manual").
            if (manualSellRecords != null) {
                for (Object r : manualSellRecords) {
                    if (!Js.truthy(r)) {
                        continue;
                    }
                    Object tokenId = Js.field(r, "token_id");
                    if (!(tokenId instanceof String) || ((String) tokenId).isEmpty()) {
                        continue;
                    }
                    String key = "t:" + tokenId;
                    if (entries.containsKey(key)) {
                        continue;
                    }
                    double rp = Js.fieldNum(r, "realized_pnl");
                    boolean known = Double.isFinite(rp);
                    String verdict = known ? (rp > 0 ? "win" : rp < 0 ? "loss" : null) : null;
                    entries.put(key, new Entry(verdict, known ? rp : null, "manual"));
                }
            }
            // REDEEMABLE 조회분은 행 필드로 검증해 가르고, 못 가릴 때만 출처 폴백.
            add(entries, winRows, "win", p -> {
                String v = judgeClosedRow(p);
                return v != null ? v : "win";
            }, seq);
            add(entries, lostRows, "loss", p -> "loss", seq);
            // 결과 불명 손절을 시도한 토큰의 CLOSED 행은 realized_pnl 부호로 판정.
            add(entries, closedRows, "closed", p -> {
                if (attempted.contains(Js.field(p, "token_id"))) {
                    double rp = Js.fieldNum(p, "realized_pnl");
                    if (Double.isFinite(rp)) {
                        return rp > 0 ? "win" : rp < 0 ? "loss" : null;
                    }
                    return null;
                }
                return judgeClosedRow(p);
            }, seq);

            int settledWins = 0;
            int settledLosses = 0;
            int settledStopLoss = 0;
            int settledRealizedExcluded = 0;
            double pnlSum = 0;
            int pnlKnown = 0;
            for (Entry e : entries.values()) {
                if ("win".equals(e.verdict)) {
                    settledWins += 1;
                } else if ("loss".equals(e.verdict)) {
                    settledLosses += 1;
                }
                if ("stoploss".equals(e.kind)) {
                    settledStopLoss += 1;
                }
                if (e.pnl == null) {
                    settledRealizedExcluded += 1;
                } else {
                    pnlSum += e.pnl;
                    pnlKnown += 1;
                }
            }
            book.settledCount = entries.size();
            book.settledWins = settledWins;
            book.settledLosses = settledLosses;
            book.settledStopLoss = settledStopLoss;
            book.settledRealizedExcluded = settledRealizedExcluded;
            // 합산 가능한 행이 하나도 없는데 정산 건만 있으면 null — 0으로 지어내지 않는다.
            if (pnlKnown > 0 || book.settledCount == 0) {
                book.settledRealizedPnl = Js.round2(pnlSum);
            }
        }
        return book;
    }

    private static void add(
            Map<String, Entry> entries, List<Object> rows, String kind, VerdictFn verdictOf, int[] seq) {
        for (Object p : rows) {
            String key = PositionBook.positionKey(p);
            if (key == null) {
                key = "u:" + (seq[0]++);
            }
            if (!entries.containsKey(key)) {
                String verdict = verdictOf.of(p);
                // 실현손익 종류는 REDEEMABLE 조회분에 한해 확정 판정을 따른다.
                String kindOf = "win".equals(kind) && "loss".equals(verdict) ? "loss" : kind;
                entries.put(key, new Entry(verdict, realizedOf(kindOf, p), kind));
            }
        }
    }
}
