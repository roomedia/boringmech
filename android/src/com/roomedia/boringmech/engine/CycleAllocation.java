package com.roomedia.boringmech.engine;

/**
 * 자금 배분 계층 (공리적 분리 2단계, 감사 결합 2) — JS
 * computeCycleAllocation과 같은 계약.
 *
 * 사이징(스냅샷 슬롯·금액·현금 하한) 위에 얹는 단일 배분 지점. 사이클
 * 시점의 현금·보유 수·예약 설정·기온 건당 금액을 입력으로, 각 전략이
 * 받을 배분 결과(메인 허용 슬롯과 금액, 온도 레인 허용 건수와 금액,
 * 현금 가드 하한)를 한 번에 계산한다. 메인 전략과 온도 전략은 이
 * 결과만 받고 서로의 내부 상태(예약 슬롯, 포지션 수, 가드 판정)를
 * 직접 읽지 않는다. 이 계층은 새 판정을 만들지 않는다 — TempNo의
 * 순수 판정들을 합성해 재배치만 하며, 거동은 픽스처
 * (allocation-parity)로 고정한다. cash·openCount는 사이징 입력으로,
 * 그 결과인 slots·bet·cashFloor가 함께 들어온다.
 */
public final class CycleAllocation {
    // 메인 배분 — 사이징 결과 그대로다.
    public final int mainSlots;
    public final double mainBet;
    public final double cashFloor;
    public final int maxPositions;
    public final int verifyCap;
    public final double reserveUsd;
    // 온도 배분 — 예약 실효값, 홀드, 차선 허용 건수, 현금 가드 하한.
    public final boolean tempEnabled;
    public final double tempBetUsd;
    public final int reserveSlotsEff;
    public final boolean tempHold;
    public final int laneSlots;
    public final Double cashGuardFloor;

    private CycleAllocation(int mainSlots, double mainBet, double cashFloor,
                            int maxPositions, int verifyCap, double reserveUsd,
                            boolean tempEnabled, double tempBetUsd,
                            int reserveSlotsEff, boolean tempHold, int laneSlots,
                            Double cashGuardFloor) {
        this.mainSlots = mainSlots;
        this.mainBet = mainBet;
        this.cashFloor = cashFloor;
        this.maxPositions = maxPositions;
        this.verifyCap = verifyCap;
        this.reserveUsd = reserveUsd;
        this.tempEnabled = tempEnabled;
        this.tempBetUsd = tempBetUsd;
        this.reserveSlotsEff = reserveSlotsEff;
        this.tempHold = tempHold;
        this.laneSlots = laneSlots;
        this.cashGuardFloor = cashGuardFloor;
    }

    /**
     * 사이클 자금 배분 — tempHoldSignal은 드라이버가 계산한 홀드 신호
     * (조회 전수·일부 실패 또는 진입 창이 남은 이벤트 존재)다. 배분은
     * 사이클 상수만으로 정해지고, 사이클 중 변하는 잔여 카운터(슬롯·
     * 차선·현금)는 드라이버가 들고 아래 판정들에 넘긴다.
     */
    public static CycleAllocation compute(int slots, double bet, double cashFloor,
                                          double cash, int openCount,
                                          int maxPositions, int verifyCap, double reserveUsd,
                                          boolean tempEnabled, int tempReserveSlots,
                                          double tempBetUsd, boolean tempHoldSignal) {
        int reserveSlotsEff = TempNo.effectiveReserveSlots(tempReserveSlots, maxPositions);
        boolean tempHold = tempEnabled && reserveSlotsEff > 0 && tempHoldSignal;
        return new CycleAllocation(
                slots, bet, cashFloor, maxPositions, verifyCap, reserveUsd,
                tempEnabled, tempBetUsd, reserveSlotsEff, tempHold,
                tempHold ? reserveSlotsEff : 0,
                TempNo.cashGuardFloor(tempEnabled, reserveSlotsEff, tempBetUsd, reserveUsd));
    }

    /**
     * 스캔 전 차선 진입 가능성 — sizing 슬롯이 0인 사이클에서 예약
     * 차선이 성립할 수 있어 스캔까지 진행할지를 정한다. 배분 계산
     * 전(기온 조회 전)에 필요해 홀드 없이 예약 실효값만으로 판정한다.
     */
    public static boolean laneEntryPossible(boolean enabled, int reserveSlots,
                                            int maxPositions, int openCount,
                                            double tempBetUsd, double cash, double reserveUsd) {
        int reserveEff = TempNo.effectiveReserveSlots(reserveSlots, maxPositions);
        return enabled && reserveEff > 0
                && openCount < maxPositions
                && tempBetUsd > 0
                && cash >= tempBetUsd + reserveUsd;
    }

    /** 배분 기준 메인 차단 — 홀드가 켜진 사이클에서만 예약 판정을 적용한다. */
    public boolean blocksMain(String strategy, int slotsLeft, int verified) {
        if (!tempHold) {
            return false;
        }
        return TempNo.reserveBlocksMain(strategy, slotsLeft, verified, verifyCap, reserveSlotsEff);
    }

    /** 배분 기준 예약 차선 허용 — 차선 입력은 전부 배분 결과에서 온다. */
    public boolean laneAllows(String strategy, int laneLeft, int positionsUsed, double cashLeft) {
        return TempNo.reserveLaneAllows(strategy, tempHold, laneLeft, positionsUsed,
                maxPositions, cashLeft, tempBetUsd, reserveUsd);
    }

    /** 배분 기준 차선 현금 차단 — temp_cash_short 집계용, 차선 허용과 같은 입력이다. */
    public boolean laneCashBlocked(String strategy, int laneLeft, int positionsUsed, double cashLeft) {
        return TempNo.laneCashBlocked(strategy, tempHold, laneLeft, positionsUsed,
                maxPositions, cashLeft, tempBetUsd, reserveUsd);
    }

    /**
     * 배분 기준 현금 가드 — 하한은 배분 시점에 한 번 계산해 두고,
     * 후보별 판정은 하한과 후보 금액만으로 한다
     * (TempNo.cashGuardBlocks와 같은 경계: 기온 후보 제외, 비유한
     * 입력 무차단, 매수 후 현금이 하한과 같으면 허용).
     */
    public boolean cashGuardBlocks(String strategy, double cashLeft, double bet) {
        if (TempNo.STRATEGY.equals(strategy)) {
            return false;
        }
        if (cashGuardFloor == null) {
            return false;
        }
        if (!Double.isFinite(cashLeft) || !Double.isFinite(bet)) {
            return false;
        }
        return Js.round2(cashLeft - bet) < cashGuardFloor;
    }
}
