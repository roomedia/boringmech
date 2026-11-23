package com.roomedia.boringmech.write;

import java.util.Map;

/**
 * 거래·오류 알림 정책 — 순수 로직 (Android 의존 없음, JVM 테스트 대상).
 *
 * 채널 분리: 체결·정산 같은 거래 이벤트는 거래 채널(기본 중요도),
 * 사용자가 행동해야 하거나 거래에 영향이 있는 오류는 오류 채널(높은
 * 중요도)로 나간다. 단발 네트워크 오류처럼 사이클이 흡수하는 잡음은
 * 호출자가 애초에 이벤트를 만들지 않는 것으로 거른다 — 이 클래스는
 * 만들어진 오류 이벤트의 중복 억제만 책임진다.
 *
 * 중복 억제: 같은 사유 키의 오류 알림은 ERROR_DEDUPE_MS 안에 1회만
 * 나간다. 상태(키→마지막 발송 시각)는 호출자가 보관한다 — 네이티브는
 * Bridge가 SharedPreferences에, 테스트는 Map에 둔다.
 *
 * 알림 발송 자체의 실패는 거래 흐름에 닿으면 안 된다 — 발송 어댑터
 * (Bridge)가 모든 예외를 삼키는 것이 규약이고, 이 클래스는 판정만 한다.
 */
public final class NotifyPolicy {
    private NotifyPolicy() {}

    /** 같은 사유 오류 알림의 최소 간격 — 30분. */
    public static final long ERROR_DEDUPE_MS = 30L * 60L * 1000L;

    // 오류 사유 키 — dedupe 단위. 종목이 달라도 사유가 같으면 한 번만 운다.
    public static final String KEY_SIGN_FAIL = "sign_fail";
    public static final String KEY_ORDER_UNKNOWN = "order_unknown";
    public static final String KEY_ORDER_FAILED = "order_failed";
    public static final String KEY_REDEEM_FAILED = "redeem_failed";
    public static final String KEY_CYCLE_ERRORS = "cycle_errors";
    public static final String KEY_STAGE_DEADLINE = "stage_deadline";
    public static final String KEY_MANUAL_EXPIRED = "manual_expired";
    public static final String KEY_EXT_WATCHDOG_BLOCKED = "ext_watchdog_blocked";
    public static final String KEY_ORDER_TEST_FILL = "order_test_fill";
    public static final String KEY_ORDER_TEST_RESIDUAL = "order_test_residual";

    /** 지금 이 사유로 오류 알림을 보내도 되는가. */
    public static boolean shouldSendError(Map<String, Long> lastSentByKey, String key, long nowMs) {
        if (key == null) {
            return true;
        }
        Long last = lastSentByKey.get(key);
        return last == null || nowMs - last >= ERROR_DEDUPE_MS;
    }

    /** 발송 기록 — shouldSendError가 true를 돌려준 뒤 실제로 보냈을 때만 부른다. */
    public static void markErrorSent(Map<String, Long> lastSentByKey, String key, long nowMs) {
        if (key != null) {
            lastSentByKey.put(key, nowMs);
        }
    }
}
