package com.roomedia.boringmech.write;

import com.roomedia.boringmech.engine.Js;

import java.util.List;
import java.util.Map;

/**
 * engine.js judgeFill의 동등 이식 — 제출 응답의 체결 판정.
 *
 * 입력은 JSON 모델(Map) 또는 그 외 값이다. 판정은 응답의 실제 필드로만
 * 한다: error가 있으면 전송 실패(status 없음·0)인지 명시 거부인지 가르고,
 * errorMsg·success:false를 거른 뒤, 체결 증거(status matched/filled·
 * 트랜잭션 해시·체결 ID·실제 지불액)가 하나라도 있어야 체결로 인정한다.
 */
public final class JudgeFill {
    private JudgeFill() {}

    public static final class Verdict {
        public final boolean filled;
        /** filled | unfilled | unknown | error | rejected */
        public final String kind;
        public final String status;
        /** error 계열일 때만 — 원본 메시지 (객체면 JSON.stringify). */
        public final String error;

        Verdict(boolean filled, String kind, String status, String error) {
            this.filled = filled;
            this.kind = kind;
            this.status = status;
            this.error = error;
        }
    }

    /** status 문자열이 HTTP 5xx 숫자 코드인가. "matched" 같은 단어 상태와 구분한다. */
    private static boolean isHttp5xx(String status) {
        try {
            double code = Double.parseDouble(status);
            return code >= 500 && code < 600;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static Verdict judgeFill(Object resp) {
        if (!(resp instanceof Map)) {
            return new Verdict(false, "unfilled", "", null);
        }
        Map<?, ?> r = (Map<?, ?>) resp;
        Object error = field(r, "error");
        if (Js.truthy(error)) {
            // resp.status ?? "" — null·undefined일 때만 빈 문자열
            Object st = field(r, "status");
            String status = (st == Js.UNDEFINED || st == null) ? "" : Js.str(st);
            // HTTP 상태가 없는 error는 전송 단계 실패 — 결과 불명.
            // HTTP 5xx도 서버 처리 여부를 확정할 수 없어 결과 불명으로
            // 분류한다 (본문이 있어도 명시 거부로 보지 않는다).
            boolean transport = status.isEmpty() || status.equals("0") || isHttp5xx(status);
            return new Verdict(false, transport ? "unknown" : "error", status,
                    error instanceof String ? (String) error : ModelJson.stringify(error));
        }
        Object errorMsg = field(r, "errorMsg");
        if (Js.truthy(errorMsg)) {
            return new Verdict(false, "error", strOrEmpty(field(r, "status")), Js.str(errorMsg));
        }
        if (Boolean.FALSE.equals(field(r, "success"))) {
            return new Verdict(false, "rejected", strOrEmpty(field(r, "status")), null);
        }
        String status = strOrEmpty(field(r, "status")).toLowerCase();
        Object hashes = field(r, "transactionsHashes");
        Object trades = field(r, "tradeIDs");
        int hashCount = hashes instanceof List ? ((List<?>) hashes).size() : 0;
        int tradeCount = trades instanceof List ? ((List<?>) trades).size() : 0;
        double making = Js.num(orZero(field(r, "makingAmount")));
        boolean filled = Boolean.TRUE.equals(field(r, "success"))
                && (status.equals("matched") || status.equals("filled")
                    || hashCount > 0 || tradeCount > 0 || making > 0);
        return filled
                ? new Verdict(true, "filled", status, null)
                : new Verdict(false, "unfilled", status, null);
    }

    private static Object field(Map<?, ?> r, String key) {
        return r.containsKey(key) ? r.get(key) : Js.UNDEFINED;
    }

    /** JS String(v || "") — falsy면 빈 문자열. */
    private static String strOrEmpty(Object v) {
        if (!Js.truthy(v)) {
            return "";
        }
        return Js.str(v);
    }

    /** JS (v || 0) — falsy면 0. */
    private static Object orZero(Object v) {
        return Js.truthy(v) ? v : (Object) 0.0;
    }
}
