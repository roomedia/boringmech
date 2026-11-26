package com.roomedia.boringmech.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사이클 1회의 단계 간 공유 컨텍스트 (공리적 분리 3단계 — 결합 3).
 * NativeCycle의 단계 메서드는 이 객체 하나로만 상태를 주고받는다.
 *
 * <p>가변 필드의 소유권: snap은 snapshot 단계가 취득하고 stoploss·redeem
 * 단계가 자기 단계 안에서만 갱신한다. settleNotice는 snapshot이 계산해
 * settle이 소비한다. 스캔 산출물(candidates·boughtAll·scanStats·기온
 * 조회 결과)은 scan이 쓰고 selection·shadow·summary는 읽기만 한다.
 * timing 필드는 각 단계가 자기 필드만 쓴다. 선정 카운터는 selection
 * 단계의 지역 상태이고, 결과만 이 객체에 남긴다.
 */
final class CycleContext {
    public final int gen;
    /** 사이클 시작에 1회 파싱한 설정 뷰 (읽기 전용). */
    public final TradeSettings tradeCfg;
    public final TempNo.Settings tempSettings;

    public NativeCycle.Snap snap;
    public SettledBaseline.Notice settleNotice;
    public double recovered;

    // 스캔 단계 산출
    public List<ScanFilter.Candidate> candidates;
    public Set<String> boughtAll;
    public Map<String, Integer> scanStats;
    public List<Object> tempEventsList;
    /** 섀도우 입력 계약 — scan이 스캔 후보를 변환해 채우고 shadow만 읽는다. */
    public List<ShadowLog.CandInput> shadowInputs;
    public boolean tempFetchFailed;
    public boolean tempFetchIncomplete;

    // 선정 단계 산출
    public Set<String> boughtBeforeBuy;
    public int verified;
    public Map<String, Integer> rejects;
    public int filledCount;
    public int unfilledCount;
    public int failedCount;

    // 단계별 소요 (밀리초) — 사이클 요약에만 붙는 측정값이다. 단계 진입
    // 기록(setStage·StageDeadline 판정)은 건드리지 않는다. verify·order는
    // 선정 단계 안의 누적, scan은 스캔 전체 경과다.
    public long timingT0;
    public long timingSnapshotMs;
    public long timingStopLossMs;
    public long timingScanStartMs;
    public long timingScanMs;
    public long timingVerifyMs;
    public long timingOrderMs;

    CycleContext(int gen, TradeSettings tradeCfg, TempNo.Settings tempSettings) {
        this.gen = gen;
        this.tradeCfg = tradeCfg;
        this.tempSettings = tempSettings;
    }
}
