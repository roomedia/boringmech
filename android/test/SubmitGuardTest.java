// SubmitGuard 겹침 시나리오 JVM 테스트 — 세션 재시작이 겹치는 창에서
// 이중 제출이 구조적으로 막히는지, 손절 원장 read-modify-write가 세션
// 간 동시 기록에도 유실되지 않는지를 고정한다.
// 실행: android/test/run_submit_guard_test.sh
import com.roomedia.boringmech.write.StopLossLedger;
import com.roomedia.boringmech.write.SubmitGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SubmitGuardTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        sharedGuardBlocksSecondSession();
        staleGenerationCannotStartSubmit();
        sharedCounterSurvivesOtherSessionEnd();
        ledgerConcurrentWritesAreNotLost();
        System.out.println("submit guard: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 핵심 시나리오: 구 세션이 slug 잠금을 잡고 제출 중(블로킹 HTTP에
     * 걸린 상태)일 때, 재시작된 새 세션은 같은 slug를 제출할 수 없다.
     * 구 세션이 잠금을 풀기 전에는 몇 번을 시도해도 거부되고, 푼 뒤에야
     * 새 세션이 잡을 수 있다.
     */
    private static void sharedGuardBlocksSecondSession() {
        AtomicInteger generation = new AtomicInteger(1);
        List<Boolean> events = new ArrayList<>();
        SubmitGuard guard = new SubmitGuard(events::add);
        guard.bindGenerationSource(generation::get);

        // 구 세션(세대 1)이 제출을 시작하고 잠금을 쥔 채 멈춰 있다
        check("old session acquires slug", guard.tryAcquireSubmitLock("slug-x", 1));
        check("old session begins submit", guard.beginSubmit(1));
        check("submit in flight", guard.submitInFlight());

        // 재시작 — 세대가 2로 바뀐다
        generation.set(2);

        // 새 세션은 같은 slug를 잡을 수 없다 (구 세션 잠금이 공유 가드에 남아 있다)
        check("new session blocked on same slug", !guard.tryAcquireSubmitLock("slug-x", 2));
        check("new session still blocked on retry", !guard.tryAcquireSubmitLock("slug-x", 2));

        // 구 세션이 제출을 마치고 잠금을 풀면 새 세션이 잡을 수 있다
        guard.endSubmit();
        guard.releaseSubmitLock("slug-x");
        check("new session acquires after release", guard.tryAcquireSubmitLock("slug-x", 2));
        check("new session begins submit", guard.beginSubmit(2));
        guard.endSubmit();
        guard.releaseSubmitLock("slug-x");
        check("no submit in flight at end", !guard.submitInFlight());
    }

    /**
     * 세대 무력화: 세대가 바뀐 뒤 구 세션은 새 잠금 획득도, 제출 시작도
     * 할 수 없다. 제출 직전 체크를 통과했더라도 가드에서 걸린다. 단,
     * 이미 잡은 잠금의 해제와 제출 종료는 세대와 무관하게 가능하다
     * (소유자만 정리할 수 있어야 카운터·잠금이 누수되지 않는다).
     */
    private static void staleGenerationCannotStartSubmit() {
        AtomicInteger generation = new AtomicInteger(1);
        SubmitGuard guard = new SubmitGuard();
        guard.bindGenerationSource(generation::get);

        check("gen1 acquires other slug", guard.tryAcquireSubmitLock("slug-y", 1));
        generation.set(2);
        check("stale gen cannot acquire new lock", !guard.tryAcquireSubmitLock("slug-z", 1));
        check("stale gen cannot begin submit", !guard.beginSubmit(1));
        check("stale begin did not count", !guard.submitInFlight());
        // 소유자 해제는 허용된다
        guard.releaseSubmitLock("slug-y");
        check("gen2 acquires released slug", guard.tryAcquireSubmitLock("slug-y", 2));
        guard.releaseSubmitLock("slug-y");
    }

    /**
     * 카운터 일원화: 제출 카운터는 세션이 공유하므로, 한 세션의 endSubmit이
     * 다른 세션의 제출 중 플래그를 지우지 않는다. 리스너는 0↔1 전이에서만
     * 한 번씩 불린다.
     */
    private static void sharedCounterSurvivesOtherSessionEnd() {
        AtomicInteger generation = new AtomicInteger(1);
        List<Boolean> events = new ArrayList<>();
        SubmitGuard guard = new SubmitGuard(events::add);
        guard.bindGenerationSource(generation::get);

        check("session A begins", guard.beginSubmit(1));
        generation.set(2);
        check("session B begins", guard.beginSubmit(2));
        guard.endSubmit(); // B 종료
        check("A still in flight after B end", guard.submitInFlight());
        check("no false event yet", events.equals(List.of(true)));
        guard.endSubmit(); // A 종료
        check("all submits done", !guard.submitInFlight());
        check("listener events are 0-1 transitions only", events.equals(List.of(true, false)));
    }

    /**
     * 손절 원장 동기화: 두 세션이 같은 Store에 동시에 기록해도
     * read-modify-write가 겹쳐 유실되지 않는다.
     */
    private static void ledgerConcurrentWritesAreNotLost() throws Exception {
        StopLossLedger.InMemoryStore store = new StopLossLedger.InMemoryStore();
        int perThread = 100;
        Thread a = new Thread(() -> {
            for (int i = 0; i < perThread; i++) {
                StopLossLedger.recordStopLoss(store, StopLossLedger.record(
                        "slug-a" + i, "cond", "tok-a" + i, "Yes", 0.9, 0.6, 5, 3, -1.5,
                        "2026-11-15T00:00:00.000Z"));
                StopLossLedger.recordAttempt(store, "att-a" + i);
            }
        });
        Thread b = new Thread(() -> {
            for (int i = 0; i < perThread; i++) {
                StopLossLedger.recordStopLoss(store, StopLossLedger.record(
                        "slug-b" + i, "cond", "tok-b" + i, "Yes", 0.9, 0.6, 5, 3, -1.5,
                        "2026-11-15T00:00:00.000Z"));
                StopLossLedger.recordAttempt(store, "att-b" + i);
            }
        });
        a.start();
        b.start();
        a.join();
        b.join();
        check("ledger kept all records", StopLossLedger.records(store).size() == perThread * 2);
        check("ledger kept all attempts",
                StopLossLedger.attemptedTokens(store).size() == perThread * 2);
    }
}
