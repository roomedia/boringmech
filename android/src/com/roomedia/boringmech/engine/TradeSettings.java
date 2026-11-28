package com.roomedia.boringmech.engine;

/**
 * 매매 설정(bm_settings)의 평면 뷰 — 정본 해석(Settings)이 돌려준
 * scan/sizing/stoploss 네임스페이스를 기존 소비자가 쓰던 평면 형태로
 * 투영한 것이다. 필드 검증·기본값·쌍 리셋 규칙은 전부 Settings 한
 * 곳에만 있고, 이 클래스는 값을 옮길 뿐 해석을 다시 하지 않는다.
 *
 * 필드 기본값 리터럴은 투영 원본(Settings의 기본값과 같아야 하며,
 * 출처 상수(ScanFilter.BAND_LO 등)와의 일치는 NativeCycleTest가
 * 고정한다.
 */
public final class TradeSettings {
    private TradeSettings() {}

    public double bandLo = 0.6;            // ScanFilter.BAND_LO
    public double bandHi = 0.9;            // ScanFilter.BAND_HI
    public double priorityBandLo = 0.7;    // ScanFilter.PRIORITY_BAND_LO
    public double priorityBandHi = 0.8;    // ScanFilter.PRIORITY_BAND_HI
    public double volumeMin = 10000;       // ScanFilter.VOLUME_MIN
    public int endWindowDays = 14;         // ScanFilter.END_WINDOW_DAYS
    public double spreadMax = 0.02;        // CandidateVerifier.SPREAD_MAX
    public double askDepthMinUsd = 10;     // CandidateVerifier.ASK_DEPTH_MIN_USD
    public double slippageCap = 0.015;     // CandidateVerifier.SLIPPAGE_CAP
    public double snapshotMatchTol = 0.03; // CandidateVerifier.SNAPSHOT_MATCH_TOL
    public double askAboveSignal = 0.05;   // CandidateVerifier.ASK_ABOVE_SIGNAL
    public double betUsd = 5;              // PositionBook.BET_USD
    public double cashFloorPct = 0.3;      // PositionBook.CASH_FLOOR_PCT
    public double reserveUsd = 2.87;       // PositionBook.RESERVE_USD
    public int maxPositions = 70;          // PositionBook.MAX_POSITIONS
    public int verifyCap = 60;             // NativeCycle.VERIFY_CAP
    public double stopDrop = 0.25;         // StopLoss.STOP_DROP
    public double tempStopDrop = 0.15;     // StopLoss.TEMP_STOP_DROP
    public double decidedHigh = 0.97;      // StopLoss.DECIDED_HIGH
    public double decidedLow = 0.03;       // StopLoss.DECIDED_LOW
    /** 주문 경로 측정 반복 횟수 — 가격·수량은 코드 고정이라 이 값만 조정 가능하다. */
    // 주문 경로 측정 기능은 v0.3.43에서 제거됐다 — 저장된 설정과의 호환을
    // 위해 파싱만 남긴 유산 필드다 (읽어서 동작하는 곳은 없다).
    public int orderTestRounds = 30;

    /** 기본값 그대로의 설정 — 설정을 못 읽거나 비었을 때의 동작은 기존과 같다. */
    public static TradeSettings defaults() {
        return new TradeSettings();
    }

    /** 정본 해석(Settings)의 평면 투영. */
    public static TradeSettings fromSettings(Settings s) {
        TradeSettings out = new TradeSettings();
        out.bandLo = s.scan.bandLo;
        out.bandHi = s.scan.bandHi;
        out.priorityBandLo = s.scan.priorityBandLo;
        out.priorityBandHi = s.scan.priorityBandHi;
        out.volumeMin = s.scan.volumeMin;
        out.endWindowDays = s.scan.endWindowDays;
        out.spreadMax = s.scan.spreadMax;
        out.askDepthMinUsd = s.scan.askDepthMinUsd;
        out.slippageCap = s.scan.slippageCap;
        out.snapshotMatchTol = s.scan.snapshotMatchTol;
        out.askAboveSignal = s.scan.askAboveSignal;
        out.betUsd = s.sizing.betUsd;
        out.cashFloorPct = s.sizing.cashFloorPct;
        out.reserveUsd = s.sizing.reserveUsd;
        out.maxPositions = s.sizing.maxPositions;
        out.verifyCap = s.sizing.verifyCap;
        out.stopDrop = s.stoploss.stopDrop;
        out.tempStopDrop = s.stoploss.tempStopDrop;
        out.decidedHigh = s.stoploss.decidedHigh;
        out.decidedLow = s.stoploss.decidedLow;
        out.orderTestRounds = s.orderTestRounds;
        return out;
    }

    /** 설정 원문(JSON) 해석 — 정본(Settings.parse)의 투영. */
    public static TradeSettings parse(String raw) {
        return fromSettings(Settings.parse(raw));
    }

    /** 엄격 숫자 해석 — 정본(Settings.strictFieldNum)으로 위임한다. */
    static double strictFieldNum(Object o, String key) {
        return Settings.strictFieldNum(o, key);
    }
}
