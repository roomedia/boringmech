package com.roomedia.boringmech.engine;

/**
 * 매매 설정(bm_settings) 해석 — engine.js parseTradeSettings 이식.
 * 매매 판단 수치는 전부 설정으로 바꿀 수 있고, 기본값은 기존 하드코딩
 * 상수와 같다. 범위를 벗어난 값은 필드 단위로 기본값으로 되돌리고,
 * 하한·상한 쌍은 순서가 어긋나면 쌍째로 기본값으로 되돌린다
 * (못 믿는 설정으로 사지 않는다).
 *
 * 기본값은 출처 상수(ScanFilter.BAND_LO 등)를 참조하지 않고 리터럴로
 * 둔다 — 설정 해석기가 스캔·검증·손절 클래스를 끌어오지 않게 하기
 * 위해서다. 리터럴과 출처 상수의 일치는 NativeCycleTest가 고정한다.
 */
public final class TradeSettings {
    private TradeSettings() {}

    public double bandLo = 0.6;            // ScanFilter.BAND_LO
    public double bandHi = 0.9;            // ScanFilter.BAND_HI
    public double priorityBandLo = 0.7;    // ScanFilter.PRIORITY_BAND_LO
    public double priorityBandHi = 0.8;    // ScanFilter.PRIORITY_BAND_HI
    public double volumeMin = 10000;       // ScanFilter.VOLUME_MIN
    public int endWindowDays = 45;         // ScanFilter.END_WINDOW_DAYS
    public double spreadMax = 0.02;        // CandidateVerifier.SPREAD_MAX
    public double askDepthMinUsd = 10;     // CandidateVerifier.ASK_DEPTH_MIN_USD
    public double slippageCap = 0.015;     // CandidateVerifier.SLIPPAGE_CAP
    public double snapshotMatchTol = 0.03; // CandidateVerifier.SNAPSHOT_MATCH_TOL
    public double askAboveSignal = 0.05;   // CandidateVerifier.ASK_ABOVE_SIGNAL
    public double betPct = 0.01;           // PositionBook.BET_PCT
    public double betMinUsd = 5;           // PositionBook.BET_MIN_USD
    public double cashFloorPct = 0.3;      // PositionBook.CASH_FLOOR_PCT
    public double reserveUsd = 2.87;       // PositionBook.RESERVE_USD
    public int maxPositions = 70;          // PositionBook.MAX_POSITIONS
    public int verifyCap = 60;             // NativeCycle.VERIFY_CAP
    public double stopDrop = 0.25;         // StopLoss.STOP_DROP
    public double tempStopDrop = 0.15;     // StopLoss.TEMP_STOP_DROP
    public double decidedHigh = 0.97;      // StopLoss.DECIDED_HIGH
    public double decidedLow = 0.03;       // StopLoss.DECIDED_LOW

    /** 기본값 그대로의 설정 — 설정을 못 읽거나 비었을 때의 동작은 기존과 같다. */
    public static TradeSettings defaults() {
        return new TradeSettings();
    }

    private interface Ok {
        boolean test(double v);
    }

    private static double numField(Object o, String key, double def, Ok ok) {
        double v = Js.fieldNum(o, key);
        return Double.isFinite(v) && ok.test(v) ? v : def;
    }

    /** 설정 원문(JSON) 해석 — JS parseTradeSettings와 같은 규칙. */
    public static TradeSettings parse(String raw) {
        TradeSettings out = new TradeSettings();
        Object o = null;
        if (raw != null && !raw.isEmpty()) {
            try {
                o = JsonText.parse(raw);
            } catch (IllegalArgumentException e) {
                o = null;
            }
        }
        if (!(o instanceof java.util.Map)) {
            return out;
        }
        TradeSettings d = new TradeSettings();
        out.bandLo = numField(o, "bandLo", d.bandLo, v -> v > 0 && v < 1);
        out.bandHi = numField(o, "bandHi", d.bandHi, v -> v > 0 && v <= 1);
        out.priorityBandLo = numField(o, "priorityBandLo", d.priorityBandLo, v -> v >= 0);
        out.priorityBandHi = numField(o, "priorityBandHi", d.priorityBandHi, v -> v > 0 && v <= 1);
        out.volumeMin = numField(o, "volumeMin", d.volumeMin, v -> v >= 0);
        out.endWindowDays = (int) numField(o, "endWindowDays", d.endWindowDays,
                v -> v == Math.floor(v) && v >= 1 && v <= 365);
        out.spreadMax = numField(o, "spreadMax", d.spreadMax, v -> v > 0 && v <= 1);
        out.askDepthMinUsd = numField(o, "askDepthMinUsd", d.askDepthMinUsd, v -> v >= 0);
        out.slippageCap = numField(o, "slippageCap", d.slippageCap, v -> v >= 0 && v <= 0.5);
        out.snapshotMatchTol = numField(o, "snapshotMatchTol", d.snapshotMatchTol, v -> v >= 0 && v <= 1);
        out.askAboveSignal = numField(o, "askAboveSignal", d.askAboveSignal, v -> v >= 0 && v <= 1);
        out.betPct = numField(o, "betPct", d.betPct, v -> v > 0 && v <= 1);
        out.betMinUsd = numField(o, "betMinUsd", d.betMinUsd, v -> v >= 0);
        out.cashFloorPct = numField(o, "cashFloorPct", d.cashFloorPct, v -> v >= 0 && v <= 1);
        out.reserveUsd = numField(o, "reserveUsd", d.reserveUsd, v -> v >= 0);
        out.maxPositions = (int) numField(o, "maxPositions", d.maxPositions,
                v -> v == Math.floor(v) && v >= 1 && v <= 10000);
        out.verifyCap = (int) numField(o, "verifyCap", d.verifyCap,
                v -> v == Math.floor(v) && v >= 1 && v <= 10000);
        out.stopDrop = numField(o, "stopDrop", d.stopDrop, v -> v > 0 && v < 1);
        out.tempStopDrop = numField(o, "tempStopDrop", d.tempStopDrop, v -> v > 0 && v < 1);
        out.decidedHigh = numField(o, "decidedHigh", d.decidedHigh, v -> v > 0 && v < 1);
        out.decidedLow = numField(o, "decidedLow", d.decidedLow, v -> v > 0 && v < 1);
        if (!(out.bandLo < out.bandHi)) {
            out.bandLo = d.bandLo;
            out.bandHi = d.bandHi;
        }
        if (!(out.priorityBandLo < out.priorityBandHi)) {
            out.priorityBandLo = d.priorityBandLo;
            out.priorityBandHi = d.priorityBandHi;
        }
        if (!(out.decidedLow < out.decidedHigh)) {
            out.decidedLow = d.decidedLow;
            out.decidedHigh = d.decidedHigh;
        }
        return out;
    }
}
