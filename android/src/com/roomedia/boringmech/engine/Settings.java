package com.roomedia.boringmech.engine;

import java.util.Map;

/**
 * 매매 설정(bm_settings) 단일 정본 해석 — engine.js parseSettings 이식.
 * 블롭을 한 번만 풀어 FR별 네임스페이스(scan/sizing/stoploss/temp)로
 * 나눈 불변 객체를 돌려준다. 필드 검증·쌍 리셋 규칙은 전부 여기 한
 * 곳에만 있다. 소비자는 이 결과를 그대로 쓰거나, 투영
 * (TradeSettings.parse·TempNo.parseSettings)으로 기존 형태만 받는다.
 *
 * 기본값은 출처 상수(ScanFilter.BAND_LO 등)를 참조하지 않고 리터럴로
 * 둔다 — 설정 해석기가 스캔·검증·손절 클래스를 끌어오지 않게 하기
 * 위해서다. 리터럴과 출처 상수의 일치는 NativeCycleTest가 고정한다.
 * orderTestRounds는 저장 호환용 유산 필드라 네임스페이스 밖에 둔다.
 */
public final class Settings {
    // 만기 창 기본값 이관 (v0.3.64) — engine.js parseSettings와 같은 규칙.
    // 저장값이 옛 기본값과 정확히 같고 이관 표식이 없으면 기본값이 저장된
    // 것으로 보고 새 기본값(14)으로 읽는다.
    public static final int END_WINDOW_DAYS_LEGACY = 45;
    public static final String END_WINDOW_MIGRATED_KEY = "endWindowDaysMigrated";

    private Settings(Scan scan, Sizing sizing, StopLoss stoploss, Temp temp, int orderTestRounds) {
        this.scan = scan;
        this.sizing = sizing;
        this.stoploss = stoploss;
        this.temp = temp;
        this.orderTestRounds = orderTestRounds;
    }

    /** 스캔 네임스페이스 — 후보 선정·검증 수치. */
    public static final class Scan {
        public final double bandLo;            // ScanFilter.BAND_LO
        public final double bandHi;            // ScanFilter.BAND_HI
        public final double priorityBandLo;    // ScanFilter.PRIORITY_BAND_LO
        public final double priorityBandHi;    // ScanFilter.PRIORITY_BAND_HI
        public final double volumeMin;         // ScanFilter.VOLUME_MIN
        public final int endWindowDays;        // ScanFilter.END_WINDOW_DAYS
        public final double spreadMax;         // CandidateVerifier.SPREAD_MAX
        public final double askDepthMinUsd;    // CandidateVerifier.ASK_DEPTH_MIN_USD
        public final double slippageCap;       // CandidateVerifier.SLIPPAGE_CAP
        public final double snapshotMatchTol;  // CandidateVerifier.SNAPSHOT_MATCH_TOL
        public final double askAboveSignal;    // CandidateVerifier.ASK_ABOVE_SIGNAL

        Scan(double bandLo, double bandHi, double priorityBandLo, double priorityBandHi,
             double volumeMin, int endWindowDays, double spreadMax, double askDepthMinUsd,
             double slippageCap, double snapshotMatchTol, double askAboveSignal) {
            this.bandLo = bandLo;
            this.bandHi = bandHi;
            this.priorityBandLo = priorityBandLo;
            this.priorityBandHi = priorityBandHi;
            this.volumeMin = volumeMin;
            this.endWindowDays = endWindowDays;
            this.spreadMax = spreadMax;
            this.askDepthMinUsd = askDepthMinUsd;
            this.slippageCap = slippageCap;
            this.snapshotMatchTol = snapshotMatchTol;
            this.askAboveSignal = askAboveSignal;
        }
    }

    /** 사이징 네임스페이스 — 건당 금액·현금 하한·보유 상한. */
    public static final class Sizing {
        public final double betUsd;        // PositionBook.BET_USD
        public final double cashFloorPct;  // PositionBook.CASH_FLOOR_PCT
        public final double reserveUsd;    // PositionBook.RESERVE_USD
        public final int maxPositions;     // PositionBook.MAX_POSITIONS
        public final int verifyCap;        // NativeCycle.VERIFY_CAP

        Sizing(double betUsd, double cashFloorPct, double reserveUsd,
               int maxPositions, int verifyCap) {
            this.betUsd = betUsd;
            this.cashFloorPct = cashFloorPct;
            this.reserveUsd = reserveUsd;
            this.maxPositions = maxPositions;
            this.verifyCap = verifyCap;
        }
    }

    /** 손절 네임스페이스 — 손절 폭과 결정 구간. */
    public static final class StopLoss {
        public final double stopDrop;      // StopLoss.STOP_DROP
        public final double tempStopDrop;  // StopLoss.TEMP_STOP_DROP
        public final double decidedHigh;   // StopLoss.DECIDED_HIGH
        public final double decidedLow;    // StopLoss.DECIDED_LOW

        StopLoss(double stopDrop, double tempStopDrop, double decidedHigh, double decidedLow) {
            this.stopDrop = stopDrop;
            this.tempStopDrop = tempStopDrop;
            this.decidedHigh = decidedHigh;
            this.decidedLow = decidedLow;
        }
    }

    /** 온도 네임스페이스 — 기온 No 보조 전략 수치. */
    public static final class Temp {
        public final boolean enabled;
        public final double bandLo;        // TempNo.DEFAULT_BAND_LO
        public final double bandHi;        // TempNo.BAND_HI
        public final int entryHourUtc;     // TempNo.ENTRY_HOUR_UTC
        public final int reserveSlots;     // TempNo.DEFAULT_RESERVE_SLOTS
        public final double betUsd;        // TempNo.DEFAULT_BET_USD

        Temp(boolean enabled, double bandLo, double bandHi,
             int entryHourUtc, int reserveSlots, double betUsd) {
            this.enabled = enabled;
            this.bandLo = bandLo;
            this.bandHi = bandHi;
            this.entryHourUtc = entryHourUtc;
            this.reserveSlots = reserveSlots;
            this.betUsd = betUsd;
        }
    }

    public final Scan scan;
    public final Sizing sizing;
    public final StopLoss stoploss;
    public final Temp temp;
    /** 주문 경로 측정 반복 횟수 — 가격·수량은 코드 고정이라 이 값만 조정 가능하다. */
    // 주문 경로 측정 기능은 v0.3.43에서 제거됐다 — 저장된 설정과의 호환을
    // 위해 파싱만 남긴 유산 필드다 (읽어서 동작하는 곳은 없다).
    public final int orderTestRounds;

    /** 기본값 그대로의 설정 — 설정을 못 읽거나 비었을 때의 동작은 기존과 같다. */
    public static Settings defaults() {
        return parse(null);
    }

    /**
     * 설정 숫자 필드의 엄격 해석 — 실제 숫자(Double)와 숫자 문자열(공백
     * 제거 후 비어 있지 않고 완전히 숫자로 파싱되는 것)만 인정한다.
     * null·불리언·배열·객체는 Js.num 강제변환(null→0, true→1, []→0)으로
     * 위험한 값이 되는 대신 NaN으로 떨어져 필드 기본값을 쓰게 한다
     * (v0.3.38 감사 축 4). Js.num 자체는 다른 사용처가 있어 건드리지 않고
     * 이 필드 추출 지점에서만 거른다.
     */
    static double strictFieldNum(Object o, String key) {
        Object v = Js.field(o, key);
        if (v instanceof Double) {
            return (Double) v;
        }
        if (v instanceof String) {
            String t = Js.trimJs((String) v);
            return t.isEmpty() ? Double.NaN : Js.parseNumber(t);
        }
        return Double.NaN;
    }

    private interface Ok {
        boolean test(double v);
    }

    private static double numField(Object o, String key, double def, Ok ok) {
        double v = strictFieldNum(o, key);
        return Double.isFinite(v) && ok.test(v) ? v : def;
    }

    /** 설정 원문(JSON) 해석 — JS parseSettings와 같은 규칙. */
    public static Settings parse(String raw) {
        Object o = null;
        if (raw != null && !raw.isEmpty()) {
            try {
                o = JsonText.parse(raw);
            } catch (IllegalArgumentException e) {
                o = null;
            }
        }
        // scan
        double bandLo = 0.6;
        double bandHi = 0.9;
        double priorityBandLo = 0.7;
        double priorityBandHi = 0.8;
        double volumeMin = 10000;
        int endWindowDays = 14;
        double spreadMax = 0.02;
        double askDepthMinUsd = 10;
        double slippageCap = 0.015;
        double snapshotMatchTol = 0.03;
        double askAboveSignal = 0.05;
        // sizing
        double betUsd = 5;
        double cashFloorPct = 0.3;
        double reserveUsd = 2.87;
        int maxPositions = 70;
        int verifyCap = 60;
        // stoploss
        double stopDrop = 0.25;
        double tempStopDrop = 0.15;
        double decidedHigh = 0.97;
        double decidedLow = 0.03;
        int orderTestRounds = 30;
        // temp
        boolean tempEnabled = false;
        double tempBandLo = 0.80;
        double tempBandHi = 0.998;
        int tempEntryHourUtc = 6;
        int tempReserveSlots = 1;
        double tempBetUsd = 5.0;
        if (o instanceof Map) {
            bandLo = numField(o, "bandLo", bandLo, v -> v > 0 && v < 1);
            bandHi = numField(o, "bandHi", bandHi, v -> v > 0 && v <= 1);
            priorityBandLo = numField(o, "priorityBandLo", priorityBandLo, v -> v >= 0);
            priorityBandHi = numField(o, "priorityBandHi", priorityBandHi, v -> v > 0 && v <= 1);
            volumeMin = numField(o, "volumeMin", volumeMin, v -> v >= 0);
            endWindowDays = (int) numField(o, "endWindowDays", endWindowDays,
                    v -> v == Math.floor(v) && v >= 1 && v <= 365);
            // 만기 창 기본값 이관 (v0.3.64) — JS parseSettings와 같은 규칙:
            // 저장값이 옛 기본값과 정확히 같고 이관 표식이 없으면 새
            // 기본값으로 읽는다. 45가 아닌 값과 표식이 있는 45는 사용자
            // 확정값이라 건드리지 않는다.
            if (!Boolean.TRUE.equals(Js.field(o, END_WINDOW_MIGRATED_KEY))
                    && strictFieldNum(o, "endWindowDays") == END_WINDOW_DAYS_LEGACY) {
                endWindowDays = 14;
            }
            spreadMax = numField(o, "spreadMax", spreadMax, v -> v > 0 && v <= 1);
            askDepthMinUsd = numField(o, "askDepthMinUsd", askDepthMinUsd, v -> v >= 0);
            slippageCap = numField(o, "slippageCap", slippageCap, v -> v >= 0 && v <= 0.5);
            snapshotMatchTol = numField(o, "snapshotMatchTol", snapshotMatchTol, v -> v >= 0 && v <= 1);
            askAboveSignal = numField(o, "askAboveSignal", askAboveSignal, v -> v >= 0 && v <= 1);
            if (!(bandLo < bandHi)) {
                bandLo = 0.6;
                bandHi = 0.9;
            }
            if (!(priorityBandLo < priorityBandHi)) {
                priorityBandLo = 0.7;
                priorityBandHi = 0.8;
            }
            betUsd = numField(o, "betUsd", betUsd, v -> v >= 0);
            cashFloorPct = numField(o, "cashFloorPct", cashFloorPct, v -> v >= 0 && v <= 1);
            reserveUsd = numField(o, "reserveUsd", reserveUsd, v -> v >= 0);
            maxPositions = (int) numField(o, "maxPositions", maxPositions,
                    v -> v == Math.floor(v) && v >= 1 && v <= 10000);
            verifyCap = (int) numField(o, "verifyCap", verifyCap,
                    v -> v == Math.floor(v) && v >= 1 && v <= 10000);
            stopDrop = numField(o, "stopDrop", stopDrop, v -> v > 0 && v < 1);
            tempStopDrop = numField(o, "tempStopDrop", tempStopDrop, v -> v > 0 && v < 1);
            decidedHigh = numField(o, "decidedHigh", decidedHigh, v -> v > 0 && v < 1);
            decidedLow = numField(o, "decidedLow", decidedLow, v -> v > 0 && v < 1);
            if (!(decidedLow < decidedHigh)) {
                decidedLow = 0.03;
                decidedHigh = 0.97;
            }
            // decided 쌍이 0.5를 사이에 두지 않으면 모든 가격이 decided
            // 판정이 돼 손절이 영구히 발화하지 않는다 — 순서가 맞아도 쌍째로
            // 기본값으로 되돌린다 (v0.3.38 감사 축 2).
            if (decidedLow >= 0.5 || decidedHigh <= 0.5) {
                decidedLow = 0.03;
                decidedHigh = 0.97;
            }
            orderTestRounds = (int) numField(o, "orderTestRounds", orderTestRounds,
                    v -> v == Math.floor(v) && v >= 1 && v <= 100);
            // 온도 — 토글은 정확히 true일 때만 켜진 것으로 보고, 밴드
            // 하한은 (0, 0.998] 범위를 벗어나면 기본값으로 되돌린다
            // (못 믿는 설정으로 사지 않는다). 밴드 하한·상한은 순서가
            // 어긋나면 쌍째로 기본값으로 되돌린다. 예약 슬롯 수는
            // 0~70의 정수만 받고, 그 외(음수·소수·범위 초과)는 기본값
            // 1로 되돌린다. 기온 건당 금액은 메인 건당 금액과 독립이며,
            // 음수·비유한 값이면 기본값으로 되돌린다.
            double hi = strictFieldNum(o, "tempNoBandHi");
            tempBandHi = Double.isFinite(hi) && hi > 0 && hi <= 1 ? hi : 0.998;
            double lo = strictFieldNum(o, "tempNoBandLo");
            tempBandLo = Double.isFinite(lo) && lo > 0 && lo <= 0.998 ? lo : 0.80;
            if (!(tempBandLo < tempBandHi)) {
                tempBandLo = 0.80;
                tempBandHi = 0.998;
            }
            double hour = strictFieldNum(o, "tempEntryHourUtc");
            tempEntryHourUtc = Double.isFinite(hour) && hour == Math.floor(hour)
                    && hour >= 0 && hour <= 23
                    ? (int) hour : 6;
            double rs = strictFieldNum(o, "tempNoReserveSlots");
            tempReserveSlots = Double.isFinite(rs) && rs == Math.floor(rs)
                    && rs >= 0 && rs <= 70
                    ? (int) rs : 1;
            double tb = strictFieldNum(o, "tempBetUsd");
            tempBetUsd = Double.isFinite(tb) && tb >= 0 ? tb : 5.0;
            tempEnabled = Boolean.TRUE.equals(Js.field(o, "tempNoEnabled"));
        }
        return new Settings(
                new Scan(bandLo, bandHi, priorityBandLo, priorityBandHi, volumeMin,
                        endWindowDays, spreadMax, askDepthMinUsd, slippageCap,
                        snapshotMatchTol, askAboveSignal),
                new Sizing(betUsd, cashFloorPct, reserveUsd, maxPositions, verifyCap),
                new StopLoss(stopDrop, tempStopDrop, decidedHigh, decidedLow),
                new Temp(tempEnabled, tempBandLo, tempBandHi, tempEntryHourUtc,
                        tempReserveSlots, tempBetUsd),
                orderTestRounds);
    }

    /**
     * 만기 창 기본값 이관이 필요한 블롭인지 — JS endWindowMigrationApplies와
     * 같은 판정. 블롭을 객체로 못 풀면 false.
     */
    public static boolean endWindowMigrationApplies(String raw) {
        Object o = null;
        if (raw != null && !raw.isEmpty()) {
            try {
                o = JsonText.parse(raw);
            } catch (IllegalArgumentException e) {
                o = null;
            }
        }
        if (!(o instanceof Map)) {
            return false;
        }
        return !Boolean.TRUE.equals(Js.field(o, END_WINDOW_MIGRATED_KEY))
                && strictFieldNum(o, "endWindowDays") == END_WINDOW_DAYS_LEGACY;
    }

    /**
     * 이관이 필요한 블롭이면 이관본(JSON 문자열)을, 아니면 null을 돌려준다
     * — JS migrateEndWindowSettings와 같은 계약. 이관본은 endWindowDays만
     * 새 기본값으로 바꾸고 이관 표식을 단다. 영속화와 로그는 호출자
     * (사이클 진입)가 맡는다.
     */
    @SuppressWarnings("unchecked")
    public static String migrateEndWindowSettings(String raw) {
        if (!endWindowMigrationApplies(raw)) {
            return null;
        }
        Map<String, Object> o = (Map<String, Object>) JsonText.parse(raw);
        Map<String, Object> next = new java.util.LinkedHashMap<>(o);
        next.put("endWindowDays", 14.0);
        next.put(END_WINDOW_MIGRATED_KEY, Boolean.TRUE);
        return JsonText.stringify(next);
    }
}
