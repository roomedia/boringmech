package com.roomedia.boringmech;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// 단계 데드라인 순수 판정 — Android 의존이 없어 JVM 단위 테스트로 검증한다.
//
// 배경: 사이클 단계(snapshot 등) 내부에서 엔진 스레드가 멈추면, 진행
// 신호(progressMs)도 함께 멈춰 워치독의 사이클 정체 판정(240초)이
// 복구를 맡는다. 이 판정은 그보다 이른 180초에 "어느 단계에서 얼마나
// 멈췄는지"를 로그·알림으로 남기고 세션 자가 재시작을 지시하는 별도
// 장치다 — 단발 복구가 아니라 재발 시에도 같은 경로로 걸리게 한다.
//
// 한계: 하트비트·워치독까지 함께 멈추는 프로세스 단위 정지는 앱 내부
// 장치로 감지할 수 없다. 이 판정은 워치독 스레드가 살아 있는 단계
// 동결형 재발에 대한 것이다.
//
// 제출 가드 의미론은 WatchdogLogic과 같다 — 제출 진행 중이면 미루되,
// 제출 시작부터 SUBMIT_GUARD_MAX_MS가 지나면 제출 자체가 멈춘 것으로
// 보고 가드를 무시한다.
public final class StageDeadline {
    /** 단계 상한 — 이보다 오래 한 단계에 머물면 동결로 판정한다. */
    public static final long STAGE_DEADLINE_MS = 180_000L;

    // 데드라인 대상 단계 — 사이클의 실제 작업 단계만 본다. idle(대기)·
    // stopped(정지)는 오래 머무는 것이 정상이라 제외한다.
    private static final Set<String> WORKING_STAGES = new HashSet<>(Arrays.asList(
            "snapshot", "stoploss", "redeem", "scan"));

    public static final class Input {
        public long now;
        public boolean runIntent;
        public boolean engineAlive;
        // 현재 단계명 (DurableStore stage). null·미상이면 판정하지 않는다.
        public String stage;
        // 단계 진입 시각 (stage 기록의 ts). 0이면 판정하지 않는다.
        public long stageSinceMs;
        // 사이클 내부 진행 신호 — 단계 안에서도 전진이 있으면 그 시각이
        // 실질 기준이다 (WatchdogLogic의 cycleBase와 같은 방식). 긴 검증
        // 단계가 후보마다 진행 신호를 남기면 오판하지 않는다.
        public long progressMs;
        public boolean submitInFlight;
        public long submitSinceMs;
        // 마지막 단계 데드라인 재시작 시각 — 재시작 직후 새 세션이 단계를
        // 잡기 전에 같은 판정이 반복 발동하지 않게 쿨다운으로 쓴다.
        public long lastStageRestartMs;
    }

    /** true면 단계 동결 — 호출자는 로그·알림 후 엔진을 재시작한다. */
    public static boolean isStageFrozen(Input in) {
        if (!in.runIntent || !in.engineAlive) {
            return false;
        }
        if (in.stage == null || !WORKING_STAGES.contains(in.stage)) {
            return false;
        }
        if (in.stageSinceMs <= 0) {
            return false;
        }
        if (in.lastStageRestartMs > 0
                && in.now - in.lastStageRestartMs < STAGE_DEADLINE_MS) {
            return false;
        }
        long base = Math.max(in.stageSinceMs, in.progressMs);
        if (in.now - base <= STAGE_DEADLINE_MS) {
            return false;
        }
        // 제출 진행 중이면 미룬다 (WatchdogLogic F1과 같은 의미론).
        if (in.submitInFlight && in.submitSinceMs > 0
                && in.now - in.submitSinceMs <= WatchdogLogic.SUBMIT_GUARD_MAX_MS) {
            return false;
        }
        return true;
    }

    /** 단계 경과 초 — 로그·알림 문구용. */
    public static long stageAgeSec(Input in) {
        long base = Math.max(in.stageSinceMs, in.progressMs);
        return Math.max(0L, (in.now - base) / 1000L);
    }

    private StageDeadline() {
    }
}
