package com.roomedia.boringmech.engine;

import java.util.Map;

/**
 * engine.js 정산 기준선 판정 이식 — parseSettledBaseline·settledNoticeFor.
 * 정산 확정 알림의 비교 기준선은 영속 저장한다 — 재로드·재시작 때마다
 * 첫 사이클이 그 시점 누계를 조용히 흡수해, 엔진이 죽어 있던 동안의
 * 정산은 알림이 영영 가지 않던 문제의 판정부.
 */
public final class SettledBaseline {
    private SettledBaseline() {}

    /** 기준선 {count, realized} — 각 필드는 null일 수 있다 (미확정 허용). */
    public static final class Baseline {
        public final Double count;
        public final Double realized;

        public Baseline(Double count, Double realized) {
            this.count = count;
            this.realized = realized;
        }
    }

    /**
     * 원문(JSON 문자열) → 기준선. 형식이 깨졌거나 count·realized 중 하나라도
     * "null도 유한 숫자도 아닌 값"이면 null (기준선 없음).
     */
    public static Baseline parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Object o = raw;
        try {
            o = JsonText.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!(o instanceof Map)) {
            return null;
        }
        Object countRaw = Js.field(o, "count");
        Object realizedRaw = Js.field(o, "realized");
        // field(v) = v === null || Number.isFinite(v) ? v : undefined
        if (!fieldOk(countRaw) || !fieldOk(realizedRaw)) {
            return null;
        }
        return new Baseline(asNullableDouble(countRaw), asNullableDouble(realizedRaw));
    }

    private static boolean fieldOk(Object v) {
        return v == null || (v instanceof Double && Double.isFinite((Double) v));
    }

    private static Double asNullableDouble(Object v) {
        return v instanceof Double ? (Double) v : null;
    }

    /** 신규 정산 알림분 {count, realizedDelta} — 알릴 게 없으면 null. */
    public static final class Notice {
        public final double count;
        public final double realizedDelta;

        Notice(double count, double realizedDelta) {
            this.count = count;
            this.realizedDelta = realizedDelta;
        }
    }

    /**
     * 기준선 대비 신규 정산분. snap은 스냅샷 모델 — settledCount·
     * settledRealizedPnl이 null(미확정)이면 알리지 않는다.
     */
    public static Notice noticeFor(Baseline base, Object snap) {
        if (base == null || snap == null) {
            return null;
        }
        Double settledCount = asNullableDouble(Js.field(snap, "settledCount"));
        if (settledCount == null || base.count == null) {
            return null;
        }
        if (settledCount <= base.count) {
            return null;
        }
        Double settledRealized = asNullableDouble(Js.field(snap, "settledRealizedPnl"));
        if (settledRealized == null || base.realized == null) {
            return null;
        }
        return new Notice(
                settledCount - base.count,
                Js.round2(settledRealized - base.realized));
    }
}
