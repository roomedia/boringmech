package com.roomedia.boringmech.write;

import java.util.Map;

/**
 * 동기 HTTP 전송 추상화 — Bridge.postSync/getSyncRaw가 돌려주는 봉투
 * {status, body, error}를 객체로 옮긴 것이다.
 * status 0 = 네트워크 단계 실패 (error에 사유).
 */
public final class RawHttp {
    private RawHttp() {}

    public static final class Raw {
        public final int status;
        public final String body;
        public final String error;

        public Raw(int status, String body, String error) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.error = error;
        }

        public static Raw failure(String error) {
            return new Raw(0, "", error);
        }
    }

    /** 동기 전송 — 호출 스레드에서 끝까지 수행한다 (Bridge의 동기 메서드와 동일). */
    public interface Transport {
        Raw post(String url, Map<String, String> headers, String body);

        Raw get(String url, Map<String, String> headers);
    }
}
