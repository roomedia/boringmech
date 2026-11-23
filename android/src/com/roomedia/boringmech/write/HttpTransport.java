package com.roomedia.boringmech.write;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HttpURLConnection 동기 전송 — Bridge.postSync/getSyncRaw의 네이티브 등가.
 *
 * Bridge와 같은 규약: 연결 15초·읽기 30초 타임아웃, POST는 헤더 적용 뒤
 * Content-Type: application/json을 강제하고 고정 길이로 본문을 쓴다,
 * 400 이상이면 error stream을 본문으로 읽는다, 예외는 status 0 봉투로
 * 바꾼다(던지지 않는다). WebView를 거치지 않으므로 CORS 자체가 없다.
 */
public final class HttpTransport implements RawHttp.Transport {
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int READ_TIMEOUT_MS = 30_000;

    @Override
    public RawHttp.Raw post(String url, Map<String, String> headers, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.close();
            int status = conn.getResponseCode();
            return new RawHttp.Raw(status, readBody(conn, status), null);
        } catch (Exception e) {
            return RawHttp.Raw.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 주문 취소용 DELETE — POST와 같은 규약으로 본문을 고정 길이로 쓴다. */
    @Override
    public RawHttp.Raw delete(String url, Map<String, String> headers, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.close();
            int status = conn.getResponseCode();
            return new RawHttp.Raw(status, readBody(conn, status), null);
        } catch (Exception e) {
            return RawHttp.Raw.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public RawHttp.Raw get(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int status = conn.getResponseCode();
            return new RawHttp.Raw(status, readBody(conn, status), null);
        } catch (Exception e) {
            return RawHttp.Raw.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection conn, int status) throws Exception {
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return buf.toString("UTF-8");
    }
}
