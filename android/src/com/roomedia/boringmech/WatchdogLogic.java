package com.roomedia.boringmech;

// 워치독 순수 판정 — Android 의존이 없어 JVM 단위 테스트로 검증한다.
// EngineService의 워치독 스레드가 prefs·공유 상태에서 읽은 값을 Input에
// 담아 넘기면, 이번 틱에 취할 행동만 돌려준다. 부수효과(카운터·지시·
// 상태 전이)는 전부 EngineService 쪽이 소유한다.
//
// 판정 값들은 기존 운용값을 그대로 유지한다:
//  - 하트비트 stale 200초 (JS HEARTBEAT_STALE_MS와 같은 값)
//  - 페이지 로드 유예 240초, 사이클 정체 240초
//  - 제출 가드 상한 600초 (JS SUBMIT_GUARD_MAX_MS와 같은 값)
// 새로 생긴 값은 부팅 감시 90초뿐이다 — 페이지 로드 지시 후 첫
// 하트비트(또는 복원·사이클 진행 신호)가 이 안에 없으면 부팅 실패로
// 보고 WebView를 재생성한다.
public final class WatchdogLogic {
    public static final long PERIOD_MS = 30_000;
    public static final long HEARTBEAT_STALE_MS = 200_000;
    public static final long LOAD_GRACE_MS = 240_000;
    public static final long CYCLE_STALL_MS = 240_000;
    public static final long SUBMIT_GUARD_MAX_MS = 600_000;
    public static final long BOOT_TIMEOUT_MS = 90_000;
    // 재로드·재생성 지시를 메인 스레드로 보낸 뒤 이 시간까지 실행·로드
    // 신호가 없으면 메인 스레드가 멈춘 것으로 보고 지시를 반복한다.
    public static final long DIRECTIVE_REPOST_MS = 30_000;

    public enum Action {NONE, CREATE, RELOAD, RECREATE}

    public static final class Input {
        public long now;
        public boolean runIntent;
        public boolean webViewExists;
        // 유예 앵커 — 서비스 시작 시각. 재로드·브로드캐스트·렌더러 사망
        // 으로는 움직이지 않는다 (움직이는 앵커가 유예 무한 연장의 원인이었다).
        public long serviceStartMs;
        // 워치독 자신이 마지막으로 재로드·재생성을 지시한 시각 (0=없음).
        // 재로드 후 유예는 이 시각 기준 1회분만 인정한다.
        public long lastReloadMs;
        public long heartbeatMs;    // 0=기록 없음
        public long cycleChangedMs; // 사이클 번호가 마지막으로 바뀐 시각, 0=없음
        // 사이클 내부 진행 신호 — 루프가 단계·후보를 처리할 때마다 갱신
        // 된다. 타이머 하트비트와 분리된 값이라, 긴 단일 사이클이 진행
        // 중이면 이 값만으로 정체가 아니라고 판정할 수 있다.
        public long progressMs;     // 0=없음
        public boolean submitInFlight;
        // 제출이 시작된 시각, 0=없음/미상. 가드 상한은 이 시각부터의
        // 경과로 잰다 — 네이티브 하트비트는 전용 타이머 스레드가 찍어
        // 엔진 스레드 정지와 무관하게 신선하므로 경과 기준이 못 된다.
        public long submitSinceMs;
        // 현재 페이지 세대의 부팅을 기다리기 시작한 시각, 0=부팅 완료.
        // 로드·재로드·재생성 지시 때마다 새로 잡힌다.
        public long bootSinceMs;
    }

    public static final class Decision {
        public Action action = Action.NONE;
        // 사이클 정체가 (공동) 사유 — 별도 카운터 구분용
        public boolean cycleReason;
        // 이번 행동과 함께 부팅 실패를 1회 기록해야 한다
        public boolean bootFailure;
        // 첫 하트비트가 도착해 부팅 감시가 끝났다 (호출자가 bootSince 해제)
        public boolean bootCompleted;
    }

    public static Decision decide(Input in) {
        Decision d = new Decision();
        if (!in.runIntent) return d;

        // WebView 자체가 없으면 복구 경로는 재생성뿐이다. 부팅 감시 중
        // 사라진 경우, 감시 기한이 지난 뒤면 부팅 실패로 함께 기록한다.
        if (!in.webViewExists) {
            d.action = Action.CREATE;
            if (in.bootSinceMs > 0
                    && in.now - Math.max(in.bootSinceMs, in.progressMs) > BOOT_TIMEOUT_MS) {
                d.bootFailure = true;
            }
            return d;
        }

        // 부팅 감시 구간 — 이 구간에서는 stale·정체 판정을 적용하지
        // 않는다. 첫 하트비트가 오면 감시를 끝내고 같은 틱에 일반
        // 판정으로 넘어간다. 진행 신호(복원 단계·사이클 내부)가 오면
        // 기한이 그만큼 미뤄진다 — 느린 복원을 부팅 실패로 오인하지
        // 않기 위해서다. 진행까지 멈추면 기한 뒤 재생성한다.
        if (in.bootSinceMs > 0) {
            if (in.heartbeatMs > in.bootSinceMs) {
                d.bootCompleted = true;
            } else {
                long bootBase = Math.max(in.bootSinceMs, in.progressMs);
                if (in.now - bootBase > BOOT_TIMEOUT_MS) {
                    d.action = Action.RECREATE;
                    d.bootFailure = true;
                }
                return d;
            }
        }

        long anchor = Math.max(in.serviceStartMs, in.lastReloadMs);
        boolean stale = in.heartbeatMs > 0
                ? in.now - in.heartbeatMs > HEARTBEAT_STALE_MS
                : in.now - anchor > LOAD_GRACE_MS;
        // 사이클 정체 — 번호 전진과 내부 진행 중 늦은 쪽을 기준으로
        // 삼는다. 검증만 긴 사이클은 번호가 안 바뀌어도 진행 신호가
        // 움직이므로 정체가 아니고, 단계 내부까지 멈춘 경우에만 걸린다.
        long cycleBase = Math.max(Math.max(in.cycleChangedMs, in.progressMs), anchor);
        boolean cycleStalled = in.now - cycleBase > CYCLE_STALL_MS;
        if (!stale && !cycleStalled) return d;
        // 제출 진행 중이면 재로드를 미룬다 (F1) — 단, 제출 시작부터
        // 가드 상한보다 오래됐으면 제출이 멈춘 것으로 보고 무시한다.
        // 시작 시각이 없으면(미상) 무기한 미루지 않는다.
        if (in.submitInFlight && in.submitSinceMs > 0
                && in.now - in.submitSinceMs <= SUBMIT_GUARD_MAX_MS) {
            return d;
        }
        d.action = Action.RELOAD;
        d.cycleReason = cycleStalled;
        return d;
    }

    // 지시가 확인됐는가 — 실행 ack, 페이지 로드 완료, 하트비트 중
    // 어느 하나라도 지시 시각 이후에 왔으면 확인된 것으로 본다.
    public static boolean isDirectiveAcked(long issuedMs, long ackMs,
                                           long pageFinishMs, long heartbeatMs) {
        return ackMs >= issuedMs || pageFinishMs >= issuedMs || heartbeatMs >= issuedMs;
    }

    // 확인 안 된 지시를 반복할 때가 됐는가.
    public static boolean shouldRepostDirective(long now, long lastPostMs) {
        return now - lastPostMs >= DIRECTIVE_REPOST_MS;
    }

    private WatchdogLogic() {
    }
}
