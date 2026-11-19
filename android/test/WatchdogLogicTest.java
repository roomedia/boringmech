// WatchdogLogic JVM 단위 테스트 — Android 없이 순수 판정만 검증한다.
// 실행: android/test/run_watchdog_logic_test.sh
import com.roomedia.boringmech.WatchdogLogic;
import com.roomedia.boringmech.WatchdogLogic.Action;
import com.roomedia.boringmech.WatchdogLogic.Decision;
import com.roomedia.boringmech.WatchdogLogic.Input;

public class WatchdogLogicTest {
    private static int passed = 0;
    private static int failed = 0;

    private static final long NOW = 10_000_000L;

    private static Input base() {
        Input in = new Input();
        in.now = NOW;
        in.runIntent = true;
        in.webViewExists = true;
        in.serviceStartMs = NOW - 3_600_000L; // 1시간 전 시작
        in.lastReloadMs = 0;
        in.heartbeatMs = NOW - 10_000L;
        in.cycleChangedMs = NOW - 10_000L;
        in.progressMs = NOW - 10_000L;
        in.submitInFlight = false;
        in.bootSinceMs = 0;
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

    private static void expect(String name, Input in, Action action) {
        Decision d = WatchdogLogic.decide(in);
        check(name + " (want " + action + ", got " + d.action + ")", d.action == action);
    }

    public static void main(String[] args) {
        // 정상 가동 — 아무것도 하지 않는다
        expect("normal", base(), Action.NONE);

        // 실행 의도 없음 — WebView가 없어도 만들지 않는다
        Input in = base();
        in.runIntent = false;
        in.webViewExists = false;
        expect("no intent, no webview", in, Action.NONE);

        // WebView 없음 + 실행 의도 — 재생성 지시
        in = base();
        in.webViewExists = false;
        expect("webview null recovery", in, Action.CREATE);

        // 하트비트 stale (기록 있음)
        in = base();
        in.heartbeatMs = NOW - WatchdogLogic.HEARTBEAT_STALE_MS - 1;
        expect("heartbeat stale", in, Action.RELOAD);

        // 하트비트 경계 — 정확히 상한이면 아직 stale 아님
        in = base();
        in.heartbeatMs = NOW - WatchdogLogic.HEARTBEAT_STALE_MS;
        expect("heartbeat at limit", in, Action.NONE);

        // 하트비트 없음 — 앵커(서비스 시작) 기준 유예 안
        in = base();
        in.heartbeatMs = 0;
        in.cycleChangedMs = 0;
        in.progressMs = 0;
        in.serviceStartMs = NOW - WatchdogLogic.LOAD_GRACE_MS + 1;
        expect("no heartbeat within grace", in, Action.NONE);

        // 하트비트 없음 — 유예 경과
        in.serviceStartMs = NOW - WatchdogLogic.LOAD_GRACE_MS - 1;
        expect("no heartbeat past grace", in, Action.RELOAD);

        // 재로드 후 유예는 1회분만 — lastReload가 앵커를 새로 잡는다
        in = base();
        in.heartbeatMs = 0;
        in.cycleChangedMs = 0;
        in.progressMs = 0;
        in.lastReloadMs = NOW - WatchdogLogic.LOAD_GRACE_MS + 1;
        expect("grace from last reload", in, Action.NONE);
        in.lastReloadMs = NOW - WatchdogLogic.LOAD_GRACE_MS - 1;
        expect("grace from last reload expired", in, Action.RELOAD);

        // 사이클 정체 — 타이머 하트비트는 살아 있으나 번호·진행 모두 정지
        in = base();
        in.cycleChangedMs = NOW - WatchdogLogic.CYCLE_STALL_MS - 1;
        in.progressMs = NOW - WatchdogLogic.CYCLE_STALL_MS - 1;
        Decision d = WatchdogLogic.decide(in);
        check("cycle stall reloads", d.action == Action.RELOAD);
        check("cycle stall reason flagged", d.cycleReason);

        // [가드] 긴 단일 사이클 진행 중 — 번호는 안 바뀌었지만 내부
        // 진행 신호가 최근이면 정체로 판정하지 않는다 (오재로드 방지)
        in = base();
        in.cycleChangedMs = NOW - 600_000L; // 번호는 10분째 그대로
        in.progressMs = NOW - 5_000L;       // 검증 후보를 방금 처리함
        expect("long cycle in progress", in, Action.NONE);

        // [가드] 단계 내부까지 멈춘 경우 — 진행 신호도 오래됐으면 재로드
        in = base();
        in.cycleChangedMs = NOW - 600_000L;
        in.progressMs = NOW - WatchdogLogic.CYCLE_STALL_MS - 1;
        d = WatchdogLogic.decide(in);
        check("stuck inside stage reloads", d.action == Action.RELOAD);
        check("stuck inside stage reason", d.cycleReason);

        // 제출 가드 — 정체여도 제출 경과가 상한 안이면 보류
        in = base();
        in.heartbeatMs = NOW - 300_000L;
        in.cycleChangedMs = NOW - 300_000L;
        in.progressMs = NOW - 300_000L;
        in.submitInFlight = true;
        in.submitSinceMs = NOW - 300_000L;
        expect("submit guard defers", in, Action.NONE);
        // 가드 상한 경과 — 제출이 멈춘 것으로 보고 재로드
        in.submitSinceMs = NOW - WatchdogLogic.SUBMIT_GUARD_MAX_MS - 1;
        expect("submit guard expired", in, Action.RELOAD);
        // 회귀: 하트비트가 신선해도(전용 타이머) 제출 경과가 상한을 넘으면
        // 재로드한다 — 종전 하트비트 기준 판정은 네이티브에서 영원히
        // 발동하지 않았다.
        in = base();
        in.heartbeatMs = NOW - 30_000L;
        in.cycleChangedMs = NOW - 600_000L;
        in.progressMs = NOW - 600_000L;
        in.submitInFlight = true;
        in.submitSinceMs = NOW - WatchdogLogic.SUBMIT_GUARD_MAX_MS - 1;
        expect("submit guard expired despite fresh heartbeat", in, Action.RELOAD);

        // 부팅 감시 — 로드 직후 유예 안에서는 stale이어도 손대지 않는다
        in = base();
        in.bootSinceMs = NOW - 30_000L;
        in.heartbeatMs = NOW - 3_600_000L; // 이전 세대의 오래된 하트비트
        in.progressMs = 0;
        expect("boot grace", in, Action.NONE);

        // 부팅 실패 — 90초 안에 하트비트·진행 모두 없음
        in = base();
        in.bootSinceMs = NOW - WatchdogLogic.BOOT_TIMEOUT_MS - 1;
        in.heartbeatMs = NOW - 3_600_000L;
        in.progressMs = 0;
        d = WatchdogLogic.decide(in);
        check("boot timeout recreates", d.action == Action.RECREATE);
        check("boot timeout counted", d.bootFailure);

        // 부팅 중 진행 신호가 있으면 기한이 미뤄진다 (느린 복원)
        in = base();
        in.bootSinceMs = NOW - 200_000L;
        in.heartbeatMs = NOW - 3_600_000L;
        in.progressMs = NOW - 10_000L; // 복원 단계가 방금 진행됨
        expect("boot progress extends", in, Action.NONE);

        // 진행까지 멈춘 부팅 — 마지막 진행으로부터 90초 경과 시 실패
        in.progressMs = NOW - WatchdogLogic.BOOT_TIMEOUT_MS - 1;
        d = WatchdogLogic.decide(in);
        check("boot progress stalled recreates", d.action == Action.RECREATE);
        check("boot progress stalled counted", d.bootFailure);

        // 부팅 완료 — 첫 하트비트가 오면 감시 해제 신호
        in = base();
        in.bootSinceMs = NOW - 60_000L;
        in.heartbeatMs = NOW - 5_000L;
        d = WatchdogLogic.decide(in);
        check("boot completed flagged", d.bootCompleted);
        check("boot completed no action", d.action == Action.NONE);

        // 부팅 감시 중 WebView 소멸 — 재생성, 기한 전이면 실패로 세지 않음
        in = base();
        in.webViewExists = false;
        in.bootSinceMs = NOW - 10_000L;
        in.progressMs = 0;
        d = WatchdogLogic.decide(in);
        check("boot webview gone creates", d.action == Action.CREATE);
        check("boot webview gone not failure yet", !d.bootFailure);
        // 기한이 지난 뒤의 소멸은 부팅 실패로 기록
        in.bootSinceMs = NOW - WatchdogLogic.BOOT_TIMEOUT_MS - 1;
        in.heartbeatMs = 0;
        d = WatchdogLogic.decide(in);
        check("boot webview gone late creates", d.action == Action.CREATE);
        check("boot webview gone late counted", d.bootFailure);

        // 지시 확인 — 실행 ack·페이지 완료·하트비트 중 하나면 확인
        check("directive ack by exec",
                WatchdogLogic.isDirectiveAcked(1000, 1000, 0, 0));
        check("directive ack by page finish",
                WatchdogLogic.isDirectiveAcked(1000, 0, 1500, 0));
        check("directive ack by heartbeat",
                WatchdogLogic.isDirectiveAcked(1000, 0, 0, 2000));
        check("directive unacked",
                !WatchdogLogic.isDirectiveAcked(1000, 500, 500, 500));
        check("directive repost due",
                WatchdogLogic.shouldRepostDirective(1000 + WatchdogLogic.DIRECTIVE_REPOST_MS, 1000));
        check("directive repost not yet",
                !WatchdogLogic.shouldRepostDirective(1000 + WatchdogLogic.DIRECTIVE_REPOST_MS - 1, 1000));

        System.out.println("watchdog logic: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
