package com.roomedia.boringmech;

/**
 * 재시작 중복 가드 — 순수 판정.
 *
 * 배경 (2026-11-19 제보 37): 앱 실행 직후 "이전 실행 의도 복원 — 엔진
 * 자동 재개" 로그 쌍이 1초 간격으로 두 번 찍혔다. start()는 스레드
 * 생존 가드가 있지만 restart()는 무조건 세대를 올려 새 세션을 만든다.
 * 시작 직후에 연동 브로드캐스트·워치독 지시 같은 재시작 요청이 겹치면
 * 방금 뜬 세션을 죽이고 다시 세우는 중복 재개가 된다.
 *
 * 세션이 시작된 지 얼마 안 됐고 살아 있으면, 그 세션은 자격증명을
 * 방금 읽은 상태라 재시작으로 얻을 게 없다 — 요청을 건너뛴다.
 * 동결 복구 목적의 재시작은 단계 데드라인(180초)·워치독 정체 판정
 * (240초)이 소유하므로 이 가드(10초)와 겹치지 않는다.
 */
public final class RestartGuard {

    /** 이 안에 들어온 재시작 요청은 중복으로 본다. */
    public static final long MIN_SESSION_AGE_MS = 10_000L;

    private RestartGuard() {
    }

    /**
     * @param sessionStartMs 현재 세션이 시작된 시각 (0이면 세션 없음)
     * @param nowMs          판정 시각
     * @param sessionAlive   현재 세션 스레드 생존 여부
     * @return 건너뛰어야 하면 true
     */
    public static boolean shouldSkipRestart(long sessionStartMs, long nowMs,
                                            boolean sessionAlive) {
        if (!sessionAlive || sessionStartMs <= 0) {
            return false;
        }
        long age = nowMs - sessionStartMs;
        return age >= 0 && age < MIN_SESSION_AGE_MS;
    }
}
