package com.roomedia.boringmech.write;

import com.roomedia.boringmech.engine.Js;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * app/src/order-bridge.js의 동등 이식 — 네이티브 전송 결과 {status, body,
 * error}를 SDK post의 반환 모양으로 바꾸고, retryOnError면 무응답·5xx에
 * 한해 30ms 뒤 1회 재시도한다.
 *
 * shapeNativeResult는 예외를 던지지 않는다 (원본 그대로):
 * - status 0(전송 실패): {error: 메시지} — 상태 필드 없음
 * - 2xx: 본문이 JSON 객체·배열이면 파싱 모델, 아니면 본문 문자열
 *   (빈 본문이면 "")
 * - 비2xx + 본문 있음: JSON이 아니면 {error: 본문, status},
 *   객체인데 "error" 키가 없으면 {error: 객체, status},
 *   "error" 키가 있으면 {...객체, status}
 * - 비2xx + 본문 없음: {error: "Request failed with status code N"}
 *
 * 전송 자체(postSync 등가)가 예외를 던지는 경우도 원본 nativePostOnce처럼
 * {status:0, error: 메시지}로 접어 넣는다.
 */
public final class OrderBridge {
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final RawHttp.Transport transport;
    private final JsonCodec codec;
    private final Sleeper sleeper;

    public OrderBridge(RawHttp.Transport transport, JsonCodec codec, Sleeper sleeper) {
        this.transport = transport;
        this.codec = codec;
        this.sleeper = sleeper;
    }

    /** order-bridge.js isOrderPostUrl. */
    public static boolean isOrderPostUrl(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        String path;
        try {
            path = new URL(endpoint).getPath();
        } catch (MalformedURLException e) {
            int q = endpoint.indexOf('?');
            path = q >= 0 ? endpoint.substring(0, q) : endpoint;
        }
        return "/order".equals(path) || "/orders".equals(path);
    }

    /** shapeNativeResult — 반환은 Map 모델 또는 문자열. */
    public Object shapeNativeResult(RawHttp.Raw raw) {
        if (raw.status == 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", raw.error != null ? raw.error : "Network Error");
            return out;
        }
        Object parsed = null;
        boolean isObject = false;
        if (raw.body != null && !raw.body.isEmpty()) {
            try {
                parsed = codec.parse(raw.body);
                isObject = parsed instanceof Map || parsed instanceof java.util.List;
            } catch (Exception ignored) {
                // JSON이 아닌 본문은 문자열 그대로
            }
        }
        if (raw.status >= 200 && raw.status < 300) {
            return isObject ? parsed : (raw.body == null ? "" : raw.body);
        }
        if (raw.body != null && !raw.body.isEmpty()) {
            if (!isObject) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("error", raw.body);
                out.put("status", (double) raw.status);
                return out;
            }
            Map<?, ?> obj = (Map<?, ?>) parsed;
            if (!obj.containsKey("error")) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("error", parsed);
                out.put("status", (double) raw.status);
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : obj.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            out.put("status", (double) raw.status);
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "Request failed with status code " + raw.status);
        return out;
    }

    /** nativePostOnce — 전송 예외는 {status:0} 봉투로 접는다. */
    RawHttp.Raw postOnce(String url, Map<String, String> headers, String body) {
        try {
            return transport.post(url, headers, body);
        } catch (Exception e) {
            String m = e.getMessage();
            return new RawHttp.Raw(0, "", m != null ? m : e.toString());
        }
    }

    /** bridgePost — SDK post와 같은 시그니처. */
    public Object bridgePost(String url, Map<String, String> headers, String body,
                             boolean retryOnError) throws InterruptedException {
        RawHttp.Raw r = postOnce(url, headers, body);
        if (retryOnError && (r.status == 0 || (r.status >= 500 && r.status < 600))) {
            sleeper.sleep(30);
            r = postOnce(url, headers, body);
        }
        return shapeNativeResult(r);
    }

    /** ClobWriteClient가 쓰는 정적 진입점 — 일회용 인스턴스로 위임한다. */
    public static Object bridgePost(RawHttp.Transport transport, String url,
                                    Map<String, String> headers, String body,
                                    boolean retryOnError, JsonCodec codec,
                                    Sleeper sleeper) throws InterruptedException {
        return new OrderBridge(transport, codec, sleeper)
                .bridgePost(url, headers, body, retryOnError);
    }

    /**
     * DELETE 전송 + 결과 shaping — SDK del 등가. 재시도하지 않는다:
     * 취소는 멱등 확인(열린 주문 조회)이 별도로 있어, 응답 유실 시
     * 호출자가 조회로 확정하는 쪽이 중복 취소를 만들지 않는다.
     * 전송 예외는 postOnce와 같이 {status:0} 봉투로 접는다.
     */
    public static Object bridgeDelete(RawHttp.Transport transport, String url,
                                      Map<String, String> headers, String body,
                                      JsonCodec codec) {
        RawHttp.Raw r;
        try {
            r = transport.delete(url, headers, body);
        } catch (Exception e) {
            String m = e.getMessage();
            r = new RawHttp.Raw(0, "", m != null ? m : e.toString());
        }
        return new OrderBridge(transport, codec, ms -> { }).shapeNativeResult(r);
    }

    /** 전송 실패 모양({error}만 있고 status 없음)인지 — judgeFill의 unknown 판정과 같은 기준. */
    static boolean isRawTransportFailure(Object shaped) {
        if (!(shaped instanceof Map)) {
            return false;
        }
        Object st = Js.field(shaped, "status");
        return st == Js.UNDEFINED && Js.field(shaped, "error") != Js.UNDEFINED;
    }
}
