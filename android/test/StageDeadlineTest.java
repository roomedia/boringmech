// StageDeadline JVM 단위 테스트 — Android 없이 순수 판정만 검증한다.
// 실행: android/test/run_watchdog_logic_test.sh (WatchdogLogicTest와 함께 돈다)
import com.roomedia.boringmech.StageDeadline;
import com.roomedia.boringmech.StageDeadline.Input;

public class StageDeadlineTest {
    private static int passed = 0;
    private static int failed = 0;

    private static final long NOW = 10_000_000L;
    private static final long DEADLINE = StageDeadline.STAGE_DEADLINE_MS;

    private static Input base() {
        Input in = new Input();
        in.now = NOW;
        in.runIntent = true;
        in.engineAlive = true;
        in.stage = "snapshot";
        in.stageSinceMs = NOW - 10_000L;
        in.progressMs = NOW - 10_000L;
        in.submitInFlight = false;
        in.submitSinceMs = 0;
        in.lastStageRestartMs = 0;
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

    private static void expect(String name, Input in, boolean frozen) {
        boolean got = StageDeadline.isStageFrozen(in);
        check(name + " (want " + frozen + ", got " + got + ")", got == frozen);
    }

    public static void main(String[] args) {
        // 정상 단계 — 상한 안이면 미발동
        expect("정상: 방금 진입한 단계", base(), false);

        Input justUnder = base();
        justUnder.stageSinceMs = NOW - DEADLINE + 1;
        justUnder.progressMs = NOW - DEADLINE + 1;
        expect("정상: 상한 직전", justUnder, false);

        // 상한 초과 — 발동
        Input over = base();
        over.stageSinceMs = NOW - DEADLINE - 1;
        over.progressMs = NOW - DEADLINE - 1;
        expect("동결: 상한 초과 snapshot", over, true);

        for (String s : new String[]{"stoploss", "redeem", "scan"}) {
            Input w = base();
            w.stage = s;
            w.stageSinceMs = NOW - DEADLINE - 60_000L;
            w.progressMs = w.stageSinceMs;
            expect("동결: 상한 초과 " + s, w, true);
        }

        // idle·stopped는 오래 머물러도 정상
        for (String s : new String[]{"idle", "stopped"}) {
            Input w = base();
            w.stage = s;
            w.stageSinceMs = NOW - 3_600_000L;
            w.progressMs = w.stageSinceMs;
            expect("정상: " + s + " 장기 체류", w, false);
        }

        // 단계 안 전진이 있으면(진행 신호 최신) 오판하지 않는다
        Input progressing = base();
        progressing.stageSinceMs = NOW - 3_600_000L;
        progressing.progressMs = NOW - 5_000L;
        expect("정상: 단계 진입은 오래됐지만 진행 중", progressing, false);

        // 제출 진행 중이면 미룬다 (가드 상한 안)
        Input submitting = base();
        submitting.stageSinceMs = NOW - DEADLINE - 60_000L;
        submitting.progressMs = submitting.stageSinceMs;
        submitting.submitInFlight = true;
        submitting.submitSinceMs = NOW - 60_000L;
        expect("보류: 제출 진행 중 (가드 상한 안)", submitting, false);

        // 제출이 가드 상한보다 오래됐으면 제출 자체가 멈춘 것으로 보고 발동
        Input stuckSubmit = base();
        stuckSubmit.stageSinceMs = NOW - DEADLINE - 60_000L;
        stuckSubmit.progressMs = stuckSubmit.stageSinceMs;
        stuckSubmit.submitInFlight = true;
        stuckSubmit.submitSinceMs = NOW - 700_000L;
        expect("동결: 제출 가드 상한 초과", stuckSubmit, true);

        // 제출 시작 시각이 없으면(미상) 무기한 미루지 않는다
        Input unknownSubmit = base();
        unknownSubmit.stageSinceMs = NOW - DEADLINE - 60_000L;
        unknownSubmit.progressMs = unknownSubmit.stageSinceMs;
        unknownSubmit.submitInFlight = true;
        unknownSubmit.submitSinceMs = 0;
        expect("동결: 제출 시작 시각 미상", unknownSubmit, true);

        // 재시작 직후 쿨다운 — 같은 판정 반복 발동 방지
        Input cooldown = base();
        cooldown.stageSinceMs = NOW - DEADLINE - 60_000L;
        cooldown.progressMs = cooldown.stageSinceMs;
        cooldown.lastStageRestartMs = NOW - 60_000L;
        expect("보류: 재시작 쿨다운", cooldown, false);

        Input cooldownOver = base();
        cooldownOver.stageSinceMs = NOW - DEADLINE - 60_000L;
        cooldownOver.progressMs = cooldownOver.stageSinceMs;
        cooldownOver.lastStageRestartMs = NOW - DEADLINE - 1;
        expect("동결: 쿨다운 지남", cooldownOver, true);

        // 실행 의도 없음·엔진 없음·단계 미상이면 판정하지 않는다
        Input noIntent = base();
        noIntent.runIntent = false;
        noIntent.stageSinceMs = NOW - 3_600_000L;
        noIntent.progressMs = noIntent.stageSinceMs;
        expect("정상: 실행 의도 없음", noIntent, false);

        Input dead = base();
        dead.engineAlive = false;
        dead.stageSinceMs = NOW - 3_600_000L;
        dead.progressMs = dead.stageSinceMs;
        expect("정상: 엔진 없음 (워치독 재생성 담당)", dead, false);

        Input noStage = base();
        noStage.stage = null;
        noStage.stageSinceMs = NOW - 3_600_000L;
        expect("정상: 단계 미상", noStage, false);

        Input noTs = base();
        noTs.stageSinceMs = 0;
        noTs.progressMs = 0;
        expect("정상: 단계 시각 없음", noTs, false);

        // 경과 초 계산
        Input age = base();
        age.stageSinceMs = NOW - 213_000L;
        age.progressMs = age.stageSinceMs;
        check("경과 초 계산", StageDeadline.stageAgeSec(age) == 213L);

        System.out.println("StageDeadlineTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
