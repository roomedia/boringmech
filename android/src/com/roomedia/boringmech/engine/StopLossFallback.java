package com.roomedia.boringmech.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 손절 폴백 (마지막 스냅샷 장부 기준) — engine.js runFallbackStopLoss의
 * 순수 연산 이식.
 *
 * 스냅샷 실패에 이은 포지션 재조회까지 실패하면 재조회도 같은 Data API라
 * 그 사이클의 손절이 전 건 사라진다 (2026-11-25 01:42 실측 — 429·503
 * 전역 장애). 마지막 성공 스냅샷 캐시의 종목 목록에서 토큰·진입가·
 * 수량을 복원해 기존 판정·실행 경로에 그대로 태운다. 판정 기준은
 * 바꾸지 않고, 이 경로에서 매수는 절대 실행하지 않는다.
 */
public final class StopLossFallback {
    private StopLossFallback() {}

    /** 무효 토큰 집합의 durable 키 — JS LS.stopLossFallbackDead와 같다. */
    public static final String DEAD_KEY = "bm_stoploss_fallback_dead";
    /** 무효로 표시된 토큰의 잔고를 다시 확인하는 간격 (10분). */
    public static final long DEAD_RECHECK_MS = 600_000L;
    public static final int DEAD_CAP = 1000;

    /**
     * 무효 집합 파싱 — {t: token_id, ts: epoch ms} 항목만 남긴다.
     * 원문이 깨졌으면 빈 목록 (폴백이 조용히 전 건 재확인으로 떨어진다).
     */
    public static List<Object> parseDead(String raw) {
        List<Object> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        Object parsed;
        try {
            parsed = JsonText.parse(raw);
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (!(parsed instanceof List)) {
            return out;
        }
        for (Object e : (List<?>) parsed) {
            if (!(e instanceof Map)) {
                continue;
            }
            Object t = Js.field(e, "t");
            double ts = Js.fieldNum(e, "ts");
            if (t instanceof String && !((String) t).isEmpty() && Double.isFinite(ts)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("t", t);
                m.put("ts", ts);
                out.add(m);
            }
        }
        return out;
    }

    /** 재확인 창 안에 있는 무효 토큰 집합 — 폴백 행 구성에서 제외할 대상. */
    public static Set<String> deadActiveTokens(List<Object> entries, long nowMs) {
        Set<String> out = new LinkedHashSet<>();
        for (Object e : entries) {
            Object t = Js.field(e, "t");
            double ts = Js.fieldNum(e, "ts");
            if (t instanceof String && Double.isFinite(ts)
                    && nowMs - ts < DEAD_RECHECK_MS) {
                out.add((String) t);
            }
        }
        return out;
    }

    /** 무효 집합에 토큰을 표시한 새 목록 — 기존 항목은 교체하고 캡을 적용한다. */
    public static List<Object> withDead(List<Object> entries, String tokenId, long nowMs) {
        List<Object> out = new ArrayList<>();
        for (Object e : entries) {
            if (!tokenId.equals(Js.field(e, "t"))) {
                out.add(e);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", tokenId);
        m.put("ts", (double) nowMs);
        out.add(m);
        if (out.size() > DEAD_CAP) {
            out = new ArrayList<>(out.subList(out.size() - DEAD_CAP, out.size()));
        }
        return out;
    }

    public static boolean hasToken(List<Object> entries, String tokenId) {
        for (Object e : entries) {
            if (tokenId.equals(Js.field(e, "t"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 폴백 손절 행 구성 — 스냅샷 캐시 모델의 positions에서 판정에 필요한
     * 필드만 복원한다. 이미 손절·수동 매도로 처분이 기록된 토큰과 무효
     * 집합(재확인 창 안)의 토큰은 빼고, 남은 행에 fallback 표기를 단다.
     * 판정 입력(보유 쪽·진입가·수량)은 API 행과 같은 값이어야 하며,
     * 캐시가 마지막 스냅샷 행의 요약을 그대로 담고 있어 성립한다.
     */
    public static List<Map<String, Object>> rowsFromCache(Object cacheModel,
                                                          Set<String> soldTokens,
                                                          Set<String> deadTokens) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object positionsRaw = Js.field(cacheModel, "positions");
        if (!(positionsRaw instanceof List)) {
            return out;
        }
        for (Object p : (List<?>) positionsRaw) {
            if (!(p instanceof Map)) {
                continue;
            }
            Object slugRaw = Js.field(p, "slug");
            String slug = slugRaw instanceof String && !((String) slugRaw).isEmpty()
                    ? (String) slugRaw : null;
            Object tokenRaw = Js.field(p, "token_id");
            String tokenId = tokenRaw instanceof String && !((String) tokenRaw).isEmpty()
                    ? (String) tokenRaw : null;
            double entry = Js.fieldNum(p, "avg_price");
            double size = Js.fieldNum(p, "current_size");
            if (slug == null || tokenId == null) {
                continue;
            }
            if (!Double.isFinite(entry) || entry <= 0) {
                continue;
            }
            if (!Double.isFinite(size) || size <= 0) {
                continue;
            }
            if (soldTokens.contains(tokenId) || deadTokens.contains(tokenId)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", slug);
            row.put("token_id", tokenId);
            row.put("condition_id", null);
            Object outcome = Js.field(p, "outcome");
            row.put("outcome", outcome instanceof String ? outcome : null);
            row.put("outcome_index", null);
            row.put("avg_price", entry);
            row.put("current_size", size);
            row.put("fallback", Boolean.TRUE);
            out.add(row);
        }
        return out;
    }
}
