// ExternalWatchdog JVM 단위 테스트 — Android 없이 순수 판정만 검증한다.
// 실행: android/test/run_external_watchdog_test.sh
import com.roomedia.boringmech.ExternalWatchdog;
import com.roomedia.boringmech.ExternalWatchdog.Input;

public class ExternalWatchdogTest {
    private static int passed = 0;
    private static int failed = 0;

    private static final long NOW = 20_000_000L;

    private static Input base() {
        Input in = new Input();
        in.now = NOW;
        in.runIntent = true;
        in.heartbeatMs = NOW - 30_000L;      // 30초 전 하트비트 (정상)
        in.cycleChangedMs = NOW - 60_000L;
        in.progressMs = NOW - 60_000L;
        in.lastExternalRestartMs = 0;
        return in;
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void expect(String name, Input in, boolean want) {
        boolean got = ExternalWatchdog.shouldRestart(in);
        check(name + " (want " + want + ", got " + got + ")", got == want);
    }

    public static void main(String[] args) {
        // 정상 가동 — 하트비트가 신선하면 재기동하지 않는다
        expect("normal", base(), false);

        // 실행 의도 없음 — 활동이 아무리 오래 멈춰 있어도 대상이 아니다
        Input in = base();
        in.runIntent = false;
        in.heartbeatMs = NOW - ExternalWatchdog.STALL_LIMIT_MS - 1;
        in.cycleChangedMs = in.heartbeatMs;
        in.progressMs = in.heartbeatMs;
        expect("no intent, all stale", in, false);

        // 실행 의도 없음 + 활동 기록 전무 — 그래도 재기동하지 않는다
        in = base();
        in.runIntent = false;
        in.heartbeatMs = 0;
        in.cycleChangedMs = 0;
        in.progressMs = 0;
        expect("no intent, no records", in, false);

        // 하트비트 정지가 상한을 넘음 — 재기동
        in = base();
        in.heartbeatMs = NOW - ExternalWatchdog.STALL_LIMIT_MS - 1;
        in.cycleChangedMs = in.heartbeatMs;
        in.progressMs = in.heartbeatMs;
        expect("all signals stale past limit", in, true);

        // 경계 — 정확히 상한이면 아직 사망으로 보지 않는다
        in = base();
        in.heartbeatMs = NOW - ExternalWatchdog.STALL_LIMIT_MS;
        in.cycleChangedMs = in.heartbeatMs;
        in.progressMs = in.heartbeatMs;
        expect("all signals exactly at limit", in, false);

        // 하트비트는 멈췄지만 진행 신호가 신선 — 가장 최근 신호 기준이라
        // 재기동하지 않는다 (살아 있는 긴 사이클을 죽이지 않는다)
        in = base();
        in.heartbeatMs = NOW - ExternalWatchdog.STALL_LIMIT_MS - 1;
        in.cycleChangedMs = in.heartbeatMs;
        in.progressMs = NOW - 10_000L;
        expect("progress fresh, heartbeat stale", in, false);

        // 사이클 전진만 신선해도 같다
        in = base();
        in.heartbeatMs = NOW - ExternalWatchdog.STALL_LIMIT_MS - 1;
        in.progressMs = in.heartbeatMs;
        in.cycleChangedMs = NOW - 10_000L;
        expect("cycle change fresh, others stale", in, false);

        // 활동 기록 전무 + 실행 의도 — 부팅 직후 복원 케이스, 재기동한다
        in = base();
        in.heartbeatMs = 0;
        in.cycleChangedMs = 0;
        in.progressMs = 0;
        expect("no records with intent (boot)", in, true);

        // 쿨다운 — 재기동 직후에는 활동이 멈춰 있어도 다시 재기동하지 않는다
        in = base();
        in.heartbeatMs = NOW - ExternalWatchdog.STALL_LIMIT_MS - 1;
        in.cycleChangedMs = in.heartbeatMs;
        in.progressMs = in.heartbeatMs;
        in.lastExternalRestartMs = NOW - 60_000L;
        expect("cooldown blocks repeat restart", in, false);

        // 쿨다운 경계 — 쿨다운이 정확히 지나면 다시 판정한다
        in.lastExternalRestartMs = NOW - ExternalWatchdog.RESTART_COOLDOWN_MS;
        expect("cooldown exactly over", in, true);

        // 쿨다운 경과 + 여전히 정지 — 재기동한다 (복구 실패 시 재시도)
        in.lastExternalRestartMs = NOW - ExternalWatchdog.RESTART_COOLDOWN_MS - 1;
        expect("cooldown over, still stale", in, true);

        // 쿨다운 경과 + 활동 재개됨 — 재기동하지 않는다
        in = base();
        in.lastExternalRestartMs = NOW - ExternalWatchdog.RESTART_COOLDOWN_MS - 1;
        expect("cooldown over, activity resumed", in, false);

        // 재기동 시각이 미래로 튄 경우(시계 이상) — 쿨다운으로 보고
        // 재기동하지 않는다
        in = base();
        in.heartbeatMs = NOW - ExternalWatchdog.STALL_LIMIT_MS - 1;
        in.cycleChangedMs = in.heartbeatMs;
        in.progressMs = in.heartbeatMs;
        in.lastExternalRestartMs = NOW + 60_000L;
        expect("restart timestamp in future", in, false);

        // stallAgeSec — 기록이 없으면 -1, 있으면 경과 초
        in = base();
        in.heartbeatMs = 0;
        in.cycleChangedMs = 0;
        in.progressMs = 0;
        check("stallAgeSec no records", ExternalWatchdog.stallAgeSec(in) == -1);
        in.heartbeatMs = NOW - 125_000L;
        check("stallAgeSec 125s", ExternalWatchdog.stallAgeSec(in) == 125);

        System.out.println("ExternalWatchdogTest: " + passed + " passed, "
                + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
