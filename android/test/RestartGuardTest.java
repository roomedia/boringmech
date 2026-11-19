// RestartGuard JVM 단위 테스트 — Android 없이 순수 판정만 검증한다.
// 실행: android/test/run_restart_guard_test.sh
import com.roomedia.boringmech.RestartGuard;

public class RestartGuardTest {
    private static int passed = 0;
    private static int failed = 0;

    private static final long START = 1_000_000L;

    private static void check(String name, boolean got, boolean want) {
        if (got == want) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name + " (want " + want + ", got " + got + ")");
        }
    }

    public static void main(String[] args) {
        // 시작 직후(1초) 살아 있는 세션 — 중복 재시작을 건너뛴다 (제보 37)
        check("1s after start, alive",
                RestartGuard.shouldSkipRestart(START, START + 1_000L, true), true);
        // 경계 직전 — 건너뛴다
        check("just before boundary, alive",
                RestartGuard.shouldSkipRestart(
                        START, START + RestartGuard.MIN_SESSION_AGE_MS - 1, true), true);
        // 경계 — 허용한다
        check("at boundary, alive",
                RestartGuard.shouldSkipRestart(
                        START, START + RestartGuard.MIN_SESSION_AGE_MS, true), false);
        // 오래된 세션 — 동결 복구 재시작을 막지 않는다
        check("180s after start, alive",
                RestartGuard.shouldSkipRestart(START, START + 180_000L, true), false);
        // 세션이 죽어 있으면 시작 직후라도 재시작한다
        check("1s after start, dead",
                RestartGuard.shouldSkipRestart(START, START + 1_000L, false), false);
        // 세션 기록 없음 — 재시작한다
        check("no session",
                RestartGuard.shouldSkipRestart(0, START + 1_000L, true), false);
        // 시계 역행 — 중복으로 보지 않는다 (재시작 허용)
        check("clock skew",
                RestartGuard.shouldSkipRestart(START, START - 1_000L, true), false);

        System.out.println("RestartGuardTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
