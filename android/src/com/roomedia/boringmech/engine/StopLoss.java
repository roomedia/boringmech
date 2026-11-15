package com.roomedia.boringmech.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * engine.js 손절 판정 이식 — stopLossVerdict·pickOutcomeIndex·Gamma 가격
 * 파싱(fetchGammaPrices의 후단)·checkStopLossPosition의 판정 흐름·손절
 * 원장 목록 연산(parseStopLossList·recordStopLoss·시도 토큰).
 * 가격은 Gamma outcomePrices만 쓴다 — 조회 실패·빈 응답이면 null로 그 건을
 * 스킵하게 하는 것이 원본 의미론이다 (0으로 취급하지 않는다).
 */
public final class StopLoss {
    private StopLoss() {}

    // engine.js 상수 — 보유 현재가(Gamma) ≤ 진입가 − STOP_DROP이면 전량 매도.
    // 사실상 결정된 마켓(≥DECIDED_HIGH·≤DECIDED_LOW)은 정산이 처리하므로 제외.
    public static final double STOP_DROP = 0.25;
    public static final double DECIDED_HIGH = 0.97;
    public static final double DECIDED_LOW = 0.03;
    public static final int RECENT_CAP = 200;
    public static final int ARCHIVE_CAP = 10_000;
    public static final int ATTEMPTED_CAP = 200;

    /** 손절 판정 — "skip" | "decided" | "triggered" | "holding". */
    public static String verdict(double entry, double current) {
        if (!Double.isFinite(entry) || !Double.isFinite(current)) {
            return "skip";
        }
        if (current >= DECIDED_HIGH || current <= DECIDED_LOW) {
            return "decided";
        }
        if (current <= entry - STOP_DROP) {
            return "triggered";
        }
        return "holding";
    }

    /**
     * 포지션의 outcome 인덱스 — Gamma outcomes 이름 대조(대소문자·공백 무시)가
     * 1순위, 실패하면 Data API 행의 outcome_index로 폴백. 못 정하면 -1.
     */
    public static int pickOutcomeIndex(List<Object> outcomes, Object row) {
        if (outcomes != null && !outcomes.isEmpty()) {
            Object nameRaw = Js.field(row, "outcome");
            String name = (nameRaw == Js.UNDEFINED || nameRaw == null)
                    ? "" : Js.trimJs(Js.str(nameRaw)).toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                for (int i = 0; i < outcomes.size(); i++) {
                    String o = Js.trimJs(Js.str(outcomes.get(i))).toLowerCase(Locale.ROOT);
                    if (o.equals(name)) {
                        return i;
                    }
                }
            }
        }
        double idx = Js.fieldNum(row, "outcome_index");
        return Double.isFinite(idx) && idx == Math.floor(idx) && idx >= 0 ? (int) idx : -1;
    }

    /** Gamma 가격 응답 파싱 결과. */
    public static final class GammaPrices {
        public final List<Object> outcomes;
        public final double[] prices;

        GammaPrices(List<Object> outcomes, double[] prices) {
            this.outcomes = outcomes;
            this.prices = prices;
        }
    }

    /**
     * fetchGammaPrices의 응답 해석부 — 페이지(배열 모델) 첫 마켓의
     * outcomes·outcomePrices를 검증해 돌려준다. 형식이 어긋나면 전부 null.
     */
    public static GammaPrices parseGammaPrices(Object page) {
        Object m = null;
        if (page instanceof List && !((List<?>) page).isEmpty()) {
            m = ((List<?>) page).get(0);
        }
        if (!Js.truthy(m)) {
            return null;
        }
        List<Object> outcomes;
        List<Double> prices;
        try {
            outcomes = parseJsonArray(Js.field(m, "outcomes"));
            prices = ScanFilter.parsePrices(Js.field(m, "outcomePrices"));
            if (prices == null) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (outcomes == null || outcomes.isEmpty()) {
            return null;
        }
        if (outcomes.size() != prices.size()) {
            return null;
        }
        double[] ps = new double[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            if (!Double.isFinite(prices.get(i))) {
                return null;
            }
            ps[i] = prices.get(i);
        }
        return new GammaPrices(outcomes, ps);
    }

    /** checkStopLossPosition 판정 결과 — execute 단계 직전까지의 흐름. */
    public static final class Eval {
        /** skip | bad_entry | no_index | holding | recheck_dropped | triggered */
        public final String action;
        /** triggered·holding·recheck_dropped일 때의 현재가, 그 외 null. */
        public final Double current;

        Eval(String action, Double current) {
            this.action = action;
            this.current = current;
        }
    }

    /**
     * 손절 판정 흐름 (매도 실행 직전까지) — gammaPage1·gammaPage2는
     * fetchGammaPrices 원응답 모델, 조회 실패는 null로 넘긴다.
     * 1차 조회에서 발화일 때만 2차로 재검증하고, 재검증에서도 발화일 때만
     * triggered가 된다 (원본 checkStopLossPosition과 같은 순서·같은 가드).
     */
    public static Eval evaluate(Object row, Object gammaPage1, Object gammaPage2) {
        Object slug = Js.field(row, "slug");
        double entry = Js.fieldNum(row, "avg_price");
        double size = Js.fieldNum(row, "current_size");
        if (!Js.truthy(slug) || !Js.truthy(Js.field(row, "token_id")) || !Double.isFinite(entry)) {
            return new Eval("skip", null);
        }
        if (!Double.isFinite(size) || size <= 0) {
            return new Eval("skip", null);
        }
        // 진입가가 0·음수면 발화 비교가 영원히 거짓이 돼 손절이 조용히 무력화된다.
        if (entry <= 0) {
            return new Eval("bad_entry", null);
        }
        GammaPrices first = parseGammaPrices(gammaPage1);
        if (first == null) {
            return new Eval("skip", null); // 조회 실패 — 0으로 취급하지 않고 스킵
        }
        int idx = pickOutcomeIndex(first.outcomes, row);
        if (idx < 0 || idx >= first.prices.length) {
            return new Eval("no_index", null);
        }
        if (!"triggered".equals(verdict(entry, first.prices[idx]))) {
            return new Eval("holding", first.prices[idx]);
        }
        // 매도 직전 재검증 — 같은 가드로 다시 읽어 여전히 발화 상태일 때만 판다.
        GammaPrices second = parseGammaPrices(gammaPage2);
        if (second == null) {
            return new Eval("skip", null);
        }
        int idx2 = pickOutcomeIndex(second.outcomes, row);
        if (idx2 < 0 || idx2 >= second.prices.length) {
            return new Eval("skip", null);
        }
        double current = second.prices[idx2];
        if (!"triggered".equals(verdict(entry, current))) {
            return new Eval("recheck_dropped", current);
        }
        return new Eval("triggered", current);
    }

    // ── 손절 원장 목록 연산 ─────────────────────────────────────

    /**
     * parseStopLossList — 원문(JSON 문자열)을 기록 목록으로 읽는다.
     * token_id가 문자열이고 realized_pnl이 유한 숫자인 행만 남긴다.
     */
    public static List<Object> parseStopLossList(String raw) {
        String text = raw == null || raw.isEmpty() ? "[]" : raw;
        Object parsed;
        try {
            parsed = JsonText.parse(text);
        } catch (IllegalArgumentException e) {
            return new ArrayList<>();
        }
        if (!(parsed instanceof List)) {
            return new ArrayList<>();
        }
        return filterRecords((List<?>) parsed);
    }

    /** parseStopLossList의 행 필터 — token_id가 문자열이고 realized_pnl이 유한 숫자인 행만. */
    static List<Object> filterRecords(List<?> rows) {
        List<Object> out = new ArrayList<>();
        for (Object r : rows) {
            if (Js.truthy(r)
                    && Js.field(r, "token_id") instanceof String
                    && Double.isFinite(Js.num(Js.field(r, "realized_pnl")))) {
                out.add(r);
            }
        }
        return out;
    }

    /** recordStopLoss의 목록 회전 결과 — recent·archive·합본(아카이브+최근 순). */
    public static final class Ledger {
        public final List<Object> recent;
        public final List<Object> archive;
        public final List<Object> merged;

        Ledger(List<Object> recent, List<Object> archive) {
            this.recent = recent;
            this.archive = archive;
            // stopLossRecords()는 저장분을 다시 파싱(필터)해 읽는다 — 검증 없이
            // 들어간 기록은 합본에서 빠지는 것이 원본 동작이다.
            this.merged = filterRecords(archive);
            this.merged.addAll(filterRecords(recent));
        }
    }

    /**
     * recordStopLoss의 순수 목록 연산 — 최근 RECENT_CAP건은 전체 기록을
     * 유지하고, 밀려난 건은 {token_id, realized_pnl}만 아카이브로 옮긴다.
     * 아카이브는 ARCHIVE_CAP건에서 뒤쪽만 남긴다.
     */
    public static Ledger appendRecord(List<Object> recentIn, List<Object> archiveIn, Object rec) {
        List<Object> recent = new ArrayList<>(recentIn);
        recent.add(rec);
        List<Object> archive = new ArrayList<>(archiveIn);
        while (recent.size() > RECENT_CAP) {
            Object old = recent.remove(0);
            Map<String, Object> slim = new java.util.LinkedHashMap<>();
            slim.put("token_id", Js.field(old, "token_id"));
            slim.put("realized_pnl", Js.num(Js.field(old, "realized_pnl")));
            archive.add(slim);
        }
        if (archive.size() > ARCHIVE_CAP) {
            archive = new ArrayList<>(archive.subList(archive.size() - ARCHIVE_CAP, archive.size()));
        }
        return new Ledger(recent, archive);
    }

    /** stopLossAttemptedTokens — 원문에서 문자열 토큰만 집합으로 읽는다. */
    public static Set<Object> parseAttempted(String raw) {
        Set<Object> out = new LinkedHashSet<>();
        String text = raw == null || raw.isEmpty() ? "[]" : raw;
        Object parsed;
        try {
            parsed = JsonText.parse(text);
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (parsed instanceof List) {
            for (Object t : (List<?>) parsed) {
                if (t instanceof String) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * recordStopLossAttempt의 순수 연산 — 추가 후 저장 형태(최대 ATTEMPTED_CAP건,
     * 뒤쪽 유지)를 돌려준다. 빈 토큰·중복이면 집합은 그대로다.
     */
    public static List<Object> addAttempted(Set<Object> set, Object tokenId) {
        Set<Object> next = new LinkedHashSet<>(set);
        if (Js.truthy(tokenId) && !next.contains(tokenId)) {
            next.add(tokenId);
        }
        List<Object> list = new ArrayList<>(next);
        if (list.size() > ATTEMPTED_CAP) {
            list = new ArrayList<>(list.subList(list.size() - ATTEMPTED_CAP, list.size()));
        }
        return list;
    }

    /** JSON.parse(문자열 필드 || "[]") — 파싱 실패는 예외, 비배열은 null. */
    private static List<Object> parseJsonArray(Object raw) {
        String text = Js.truthy(raw) ? (raw instanceof String ? (String) raw : Js.str(raw)) : "[]";
        Object parsed = JsonText.parse(text);
        if (parsed instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) parsed;
            return l;
        }
        return null;
    }
}
