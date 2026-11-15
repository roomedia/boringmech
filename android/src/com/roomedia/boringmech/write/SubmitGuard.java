package com.roomedia.boringmech.write;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 제출 잠금 + 제출 진행 가드 — engine.js submitLocks·beginSubmit/endSubmit의
 * 네이티브 등가.
 *
 * 잠금은 slug(또는 redeem:{conditionId}) 단위 Set이며, 서명 시작부터 결과
 * 확정까지 재진입을 막는다. 진행 가드는 카운터가 0↔1로 바뀔 때만 리스너를
 * 부른다 — 리스너가 네이티브 워치독의 submitInFlight 플래그로 연결되는
 * 지점이다 (연결은 통합 단계). 모든 메서드는 동기화돼 있어, 루프가 겹쳐도
 * 두 번째 제출은 잠금에서 걸린다.
 *
 * 이 가드는 NativeEngine 단위 단일 인스턴스로 모든 세션이 공유한다.
 * 세션 세대(generation) 공급자를 묶어 두면, 재시작으로 세대가 바뀐 뒤에는
 * 구 세대의 gen으로는 잠금 획득·제출 시작이 전부 거부된다 — 구 세션이
 * 제출 직전 체크를 통과했더라도 실제 제출 경로는 여기서 걸린다. 해제
 * (release·endSubmit)는 세대와 무관하게 소유자가 항상 할 수 있다.
 */
public final class SubmitGuard {
    /** submitInFlight 상태가 바뀔 때만 불린다 (false→true, true→false). */
    public interface InFlightListener {
        void onSubmitInFlightChanged(boolean on);
    }

    private final Set<String> locks = new HashSet<>();
    private int submitCount = 0;
    private InFlightListener listener;
    private volatile IntSupplier generationSource;

    public SubmitGuard() {}

    public SubmitGuard(InFlightListener listener) {
        this.listener = listener;
    }

    public synchronized void setInFlightListener(InFlightListener listener) {
        this.listener = listener;
    }

    /** 현재 세션 세대 공급자 — NativeEngine이 자기 generation을 묶는다. */
    public void bindGenerationSource(IntSupplier source) {
        this.generationSource = source;
    }

    /** gen이 현재 세대인가. 공급자가 없으면 (단독 사용) 항상 참이다. */
    public boolean isGenerationActive(int gen) {
        IntSupplier s = generationSource;
        return s == null || s.getAsInt() == gen;
    }

    /** 공급자가 돌려주는 현재 세대 (없으면 0). */
    public int currentGeneration() {
        IntSupplier s = generationSource;
        return s == null ? 0 : s.getAsInt();
    }

    // ── 제출 잠금 ──

    public synchronized boolean tryAcquireSubmitLock(String key) {
        return tryAcquireSubmitLock(key, currentGeneration());
    }

    /** 세대 게이트付き — 구 세대 세션은 새 잠금을 하나도 얻지 못한다. */
    public synchronized boolean tryAcquireSubmitLock(String key, int gen) {
        if (!isGenerationActive(gen)) {
            return false;
        }
        if (locks.contains(key)) {
            return false;
        }
        locks.add(key);
        return true;
    }

    public synchronized void releaseSubmitLock(String key) {
        locks.remove(key);
    }

    public synchronized boolean isSubmitLocked(String key) {
        return locks.contains(key);
    }

    // ── 제출 진행 가드 ──

    public void beginSubmit() {
        beginSubmit(currentGeneration());
    }

    /**
     * 세대 게이트付き 제출 시작. 구 세대면 카운터를 건드리지 않고 false를
     * 돌려준다 — 호출자는 제출을 중단하고 잠금만 해제해야 한다.
     */
    public synchronized boolean beginSubmit(int gen) {
        if (!isGenerationActive(gen)) {
            return false;
        }
        submitCount += 1;
        if (submitCount == 1 && listener != null) {
            listener.onSubmitInFlightChanged(true);
        }
        return true;
    }

    public synchronized void endSubmit() {
        if (submitCount <= 0) {
            submitCount = 0;
            return;
        }
        submitCount -= 1;
        if (submitCount == 0 && listener != null) {
            listener.onSubmitInFlightChanged(false);
        }
    }

    public synchronized boolean submitInFlight() {
        return submitCount > 0;
    }

    /**
     * 제출 카운터 강제 리셋 — 엔진 스레드가 하나도 살아 있지 않을 때만
     * 불러야 한다 (NativeEngine.start의 신규 기동 경로 전용). 겹친 구
     * 세션이 제출 중일 때 새 세션이 부르면 그 제출의 플래그가 지워진다.
     */
    public synchronized void resetInFlight() {
        boolean was = submitCount > 0;
        submitCount = 0;
        if (was && listener != null) {
            listener.onSubmitInFlightChanged(false);
        }
    }
}
