package com.roomedia.boringmech;

// 외부 감시 순수 판정 — Android 의존이 없어 JVM 단위 테스트로 검증한다.
//
// 배경: 엔진 무응답이 세 차례 재발했는데(v0.3.31 idle, v0.3.33 snapshot,
// v0.3.34 idle), 정지할 때마다 엔진·하트비트·워치독 틱이 전부 함께 멈췄고
// 재개는 서비스가 새로 뜰 때만 일어났다. 즉 본체는 개별 스레드 동결이
// 아니라 프로세스 자체의 사망(또는 전체 정지)이고, 내부 워치독이 같은
// 프로세스 안에 있어 같이 죽는 구조가 문제다. 이 판정은 프로세스 밖
// (AlarmManager → BroadcastReceiver)에서 도는 최외곽 감시의 판정이다.
//
// 판정 기준은 활동 신호 중 가장 최근 것 — 하트비트(30초 타이머),
// 사이클 번호 전진, 사이클 내부 진행 중 가장 늦은 시각을 기준으로
// 삼는다 (WatchdogLogic의 cycleBase와 같은 방식). 내부 워치독이 살아
// 있는 단계 동결은 내부 판정(240초)이 먼저 맡고, 이 판정은 그보다
// 늦은 6분에 프로세스 사망으로 보고 서비스를 재기동한다.
public final class ExternalWatchdog {
    /** 외부 감시 알람 주기 — inexact여도 되며 절전에서도 발화해야 한다. */
    public static final long ALARM_INTERVAL_MS = 300_000L;
    /** 활동 신호가 이보다 오래 멈추면 프로세스 사망으로 판정한다. */
    public static final long STALL_LIMIT_MS = 360_000L;
    /** 재기동 직후 쿨다운 — 부팅이 진행 중인 동안 반복 재기동을 막는다. */
    public static final long RESTART_COOLDOWN_MS = 360_000L;

    public static final class Input {
        public long now;
        public boolean runIntent;
        // 마지막 하트비트 시각 (30초 타이머). 0=기록 없음.
        public long heartbeatMs;
        // 사이클 번호가 마지막으로 바뀐 시각. 0=기록 없음.
        public long cycleChangedMs;
        // 사이클 내부 진행 신호. 0=기록 없음.
        public long progressMs;
        // 외부 감시가 마지막으로 재기동을 지시한 시각. 0=없음.
        public long lastExternalRestartMs;
    }

    /** 활동 신호 중 가장 최근 시각. 기록이 전혀 없으면 0. */
    public static long lastActivityMs(Input in) {
        return Math.max(in.heartbeatMs,
                Math.max(in.cycleChangedMs, in.progressMs));
    }

    /**
     * true면 EngineService를 재기동해야 한다. 조건:
     *  - 실행 의도가 켜져 있을 것 (꺼져 있으면 감시 대상이 아니다)
     *  - 재기동 쿨다운이 지났을 것 (부팅 중인 세션을 반복해서
     *    재기동하지 않는다. 시각이 미래로 튄 경우도 쿨다운으로 본다)
     *  - 활동 기록이 전혀 없거나(부팅 직후 등), 마지막 활동이 상한보다
     *    오래됐을 것. 경계값(정확히 상한)은 아직 사망으로 보지 않는다.
     */
    public static boolean shouldRestart(Input in) {
        if (!in.runIntent) {
            return false;
        }
        if (in.lastExternalRestartMs > 0
                && in.now - in.lastExternalRestartMs < RESTART_COOLDOWN_MS) {
            return false;
        }
        long base = lastActivityMs(in);
        if (base <= 0) {
            return true;
        }
        return in.now - base > STALL_LIMIT_MS;
    }

    /** 마지막 활동으로부터의 경과 초 — 재기동 로그 문구용. */
    public static long stallAgeSec(Input in) {
        long base = lastActivityMs(in);
        if (base <= 0) {
            return -1;
        }
        return Math.max(0L, (in.now - base) / 1000L);
    }

    private ExternalWatchdog() {
    }
}
