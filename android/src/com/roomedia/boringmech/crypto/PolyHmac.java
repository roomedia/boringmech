package com.roomedia.boringmech.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * L2 인증 HMAC — SDK buildPolyHmacSignature 재현.
 * 메시지 = timestamp + method + requestPath + (body가 있을 때만 body),
 * 키 = secret을 base64url 디코딩한 바이트, 출력 = base64url (패딩 유지).
 */
public final class PolyHmac {
    private PolyHmac() {}

    /**
     * @param timestamp 초 단위 Unix 시각 (JS에서 숫자를 문자열로 이은 것과 같은 10진 표기)
     * @param body      없으면 null (JS undefined에 대응 — 메시지에 붙이지 않는다)
     */
    public static String buildSignature(String secret, long timestamp, String method,
                                        String requestPath, String body) {
        String message = timestamp + method + requestPath;
        if (body != null) {
            message += body;
        }
        byte[] keyBytes = decodeBase64UrlSecret(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig)
                    .replace('+', '-')
                    .replace('/', '_');
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    /** SDK decodeBase64Secret: -/_를 +//로 바꾸고 패딩을 채운 뒤 디코딩. */
    static byte[] decodeBase64UrlSecret(String secret) {
        String normalized = secret.replace('-', '+').replace('_', '/');
        int pad = (4 - normalized.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(normalized);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        return Base64.getDecoder().decode(sb.toString());
    }
}
