package com.roomedia.boringmech.write;

import com.roomedia.boringmech.crypto.JsNumber;
import com.roomedia.boringmech.engine.Js;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 손절 순수 로직 — engine.js stopLossVerdict·pickOutcomeIndex·roundTick과
 * 매도 체결 후 금액 산출(soldShares·proceeds·exitPrice·realized)의 이식.
 * 판정은 Gamma outcomePrices만 쓴다 (조회 실패는 호출자가 null로 넘겨 스킵).
 */
public final class StopLoss {
    private StopLoss() {}

    public static final double STOP_DROP = 0.25;
    public static final double DECIDED_HIGH = 0.97;
    public static final double DECIDED_LOW = 0.03;
    public static final double SLIPPAGE_CAP = 0.015;

    /** "skip" | "decided" | "triggered" | "holding" */
    public static String stopLossVerdict(double entry, double current) {
        if (!Double.isFinite(entry) || !Double.isFinite(current)) {
            return "skip";
        }
        if (current >= DECIDED_HIGH || current <= DECIDED_LOW) {
            return "decided";
        }
        // 경계 허용 오차 — engine/StopLoss.verdict와 동일 (십진 경계 쌍의
        // double 연산 오차로 인한 미발화 방지).
        if (current <= entry - STOP_DROP + 1e-9) {
            return "triggered";
        }
        return "holding";
    }

    /**
     * 포지션의 outcome 인덱스 — Gamma outcomes 이름 대조가 1순위,
     * 실패하면 Data API 행의 outcome_index로 폴백. 못 찾으면 -1.
     */
    public static int pickOutcomeIndex(List<?> outcomes, Map<?, ?> row) {
        if (outcomes != null && !outcomes.isEmpty()) {
            Object outcomeField = row == null ? Js.UNDEFINED : Js.field(row, "outcome");
            // JS: String(row?.outcome ?? "") — null·undefined면 빈 문자열
            String name = (outcomeField == Js.UNDEFINED || outcomeField == null)
                    ? "" : Js.str(outcomeField).trim().toLowerCase();
            if (!name.isEmpty()) {
                for (int i = 0; i < outcomes.size(); i++) {
                    if (String.valueOf(outcomes.get(i)).trim().toLowerCase().equals(name)) {
                        return i;
                    }
                }
            }
        }
        double idx = row == null ? Double.NaN : Js.fieldNum(row, "outcome_index");
        return isInteger(idx) && idx >= 0 ? (int) idx : -1;
    }

    private static boolean isInteger(double d) {
        return Double.isFinite(d) && d == Math.floor(d)
                && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    /** engine.js roundTick — 틱 단위 반올림 후 소수 6자리로 고정. */
    public static double roundTick(double price, double tick) {
        double q = Math.floor(price / tick + 0.5);
        return toFixed6(q * tick);
    }

    /** JS Number.prototype.toFixed(6) 후 Number() — double의 정확한 10진 전개 기준 반올림. */
    static double toFixed6(double v) {
        if (!Double.isFinite(v)) {
            return v;
        }
        return new BigDecimal(v).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    /** engine.js round4 — Math.round((x + EPSILON) * 10000) / 10000. */
    public static double round4(double x) {
        if (!Double.isFinite(x)) {
            return x;
        }
        return Math.round((x + Js.EPSILON) * 10000) / 10000.0;
    }

    /** 매도 하한가 — floorPrice = min(max(tick, roundTick(current − SLIPPAGE_CAP, tick)), 1 − tick). */
    public static double floorPrice(double current, double tick) {
        return Math.min(Math.max(tick, roundTick(current - SLIPPAGE_CAP, tick)), 1 - tick);
    }

    /** 매도 체결 산출 결과. */
    public static final class FillMath {
        public final double soldShares;
        public final double proceeds;
        public final double exitPrice;
        public final double realized;

        FillMath(double soldShares, double proceeds, double exitPrice, double realized) {
            this.soldShares = soldShares;
            this.proceeds = proceeds;
            this.exitPrice = exitPrice;
            this.realized = realized;
        }
    }

    /**
     * executeStopLossSellLocked의 체결 산출 — resp는 postOrder 반환 모델.
     * makingAmount가 falsy면 제출 수량으로, takingAmount가 falsy면
     * soldShares × current로 폴백한다 (JS || 의미론).
     */
    public static FillMath computeFill(Object resp, double shares, double current, double entry) {
        // JS: Number(resp.makingAmount) || shares — 0·NaN이면 shares로 폴백
        double makingNum = resp instanceof Map ? Js.fieldNum(resp, "makingAmount") : Double.NaN;
        double soldShares = (makingNum == 0 || Double.isNaN(makingNum)) ? shares : makingNum;
        // JS: round2(Number(resp.takingAmount) || soldShares * current)
        double takingNum = resp instanceof Map ? Js.fieldNum(resp, "takingAmount") : Double.NaN;
        double proceeds = Js.round2(
                (takingNum == 0 || Double.isNaN(takingNum)) ? soldShares * current : takingNum);
        double exitPrice = soldShares > 0 ? proceeds / soldShares : current;
        double realized = Js.round2(proceeds - soldShares * entry);
        return new FillMath(soldShares, proceeds, exitPrice, realized);
    }

    /** 알림용 부호 금액 표기 — engine.js signedUsd. */
    public static String signedUsd(double v) {
        return (v < 0 ? "-$" : "+$") + JsNumber.toString(Math.abs(v));
    }
}
