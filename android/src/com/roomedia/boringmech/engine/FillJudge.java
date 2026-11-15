package com.roomedia.boringmech.engine;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * engine.js judgeFill + isFailsafe 이식 — 체결 판정은 응답의 실제 필드로만
 * 한다. CLOB는 FOK가 미체결이어도 success:true + status:"unmatched"로
 * 응답하므로, 체결 증거(status matched/filled·트랜잭션 해시·체결 ID·실제
 * 지불액)가 하나라도 있어야 체결로 인정한다.
 * HTTP 상태가 없는 error는 전송 단계 실패라 "unknown"(결과 불명)으로
 * 가른다 — 체결됐을 수 있어 명시 거부와 다르게 취급해야 한다.
 */
public final class FillJudge {
    private FillJudge() {}

    private static final String[] FAILSAFE_KEYWORDS = {
            "403", "forbidden", "geoblock", "geo-block", "signature",
            "maker address not allowed", "invalid signature", "not allowed",
    };

    /** 텍스트에 정지급(failsafe) 키워드가 있는가 — 대소문자 무시 부분 일치. */
    public static boolean isFailsafe(String text) {
        String t = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        for (String k : FAILSAFE_KEYWORDS) {
            if (t.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** judgeFill 결과 — error는 해당 분기에서만 채워지고 그 외에는 null. */
    public static final class Fill {
        public final boolean filled;
        /** filled | unfilled | error | unknown | rejected */
        public final String kind;
        public final String status;
        public final String error;

        Fill(boolean filled, String kind, String status, String error) {
            this.filled = filled;
            this.kind = kind;
            this.status = status;
            this.error = error;
        }
    }

    public static Fill judgeFill(Object resp) {
        // JS: !resp || typeof resp !== "object" — 배열은 object라 통과시킨다.
        if (resp == null || (!(resp instanceof Map) && !(resp instanceof List))) {
            return new Fill(false, "unfilled", "", null);
        }
        Object err = Js.field(resp, "error");
        if (Js.truthy(err)) {
            Object stRaw = Js.field(resp, "status");
            // resp.status ?? "" — null·undefined일 때만 빈 문자열
            String status = (stRaw == Js.UNDEFINED || stRaw == null) ? "" : Js.str(stRaw);
            boolean transport = status.isEmpty() || status.equals("0");
            String error = err instanceof String ? (String) err : JsonText.stringify(err);
            return new Fill(false, transport ? "unknown" : "error", status, error);
        }
        Object errorMsg = Js.field(resp, "errorMsg");
        if (Js.truthy(errorMsg)) {
            return new Fill(false, "error", statusOrEmpty(resp), Js.str(errorMsg));
        }
        if (Boolean.FALSE.equals(Js.field(resp, "success"))) {
            return new Fill(false, "rejected", statusOrEmpty(resp), null);
        }
        String status = statusOrEmpty(resp).toLowerCase(Locale.ROOT);
        Object hashes = Js.field(resp, "transactionsHashes");
        Object trades = Js.field(resp, "tradeIDs");
        int hashCount = hashes instanceof List ? ((List<?>) hashes).size() : 0;
        int tradeCount = trades instanceof List ? ((List<?>) trades).size() : 0;
        double making = Js.numOrZero(Js.field(resp, "makingAmount"));
        boolean filled = Boolean.TRUE.equals(Js.field(resp, "success"))
                && (status.equals("matched") || status.equals("filled")
                        || hashCount > 0 || tradeCount > 0 || making > 0);
        return filled
                ? new Fill(true, "filled", status, null)
                : new Fill(false, "unfilled", status, null);
    }

    /** String(resp.status || "") — falsy면 빈 문자열. */
    private static String statusOrEmpty(Object resp) {
        Object st = Js.field(resp, "status");
        return Js.truthy(st) ? Js.str(st) : "";
    }
}
