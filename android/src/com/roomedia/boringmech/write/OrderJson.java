package com.roomedia.boringmech.write;

import com.roomedia.boringmech.crypto.JsNumber;

/**
 * POST /order 본문의 수동 직렬화 — SDK orderToJsonV2 + JSON.stringify 재현.
 *
 * 본문 문자열은 L2 HMAC의 대상이자 전송 바이트 그 자체라 필드 순서가
 * 고정돼야 한다: {deferExec, postOnly, order{salt, maker, signer, taker?,
 * tokenId, makerAmount, takerAmount, side, signatureType, timestamp,
 * expiration, metadata, builder, signature}, owner, orderType}.
 *
 * 핵심 quirk — salt는 orderToJsonV2가 parseInt(order.salt, 10)로 숫자로
 * 바꾼다. parseInt는 double 경유라 2^53을 넘는 salt는 정밀도를 잃는다
 * (골든 고정 salt 1234567890123456789 → 본문 1234567890123456800).
 * signatureType도 원본 signed order에서는 숫자라 그대로 숫자로 나간다.
 * 나머지 필드는 문자열 그대로다.
 */
public final class OrderJson {
    private OrderJson() {}

    public static String orderToJsonV2(SignedOrder order, String owner, String orderType) {
        return orderToJsonV2(order, owner, orderType, false, false);
    }

    public static String orderToJsonV2(SignedOrder order, String owner, String orderType,
                                       boolean postOnly, boolean deferExec) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"deferExec\":").append(deferExec);
        sb.append(",\"postOnly\":").append(postOnly);
        sb.append(",\"order\":{");
        sb.append("\"salt\":").append(jsParseInt(order.salt));
        sb.append(",\"maker\":").append(ModelJson.quote(order.maker));
        sb.append(",\"signer\":").append(ModelJson.quote(order.signer));
        if (order.taker != null) {
            sb.append(",\"taker\":").append(ModelJson.quote(order.taker));
        }
        sb.append(",\"tokenId\":").append(ModelJson.quote(order.tokenId));
        sb.append(",\"makerAmount\":").append(ModelJson.quote(order.makerAmount));
        sb.append(",\"takerAmount\":").append(ModelJson.quote(order.takerAmount));
        sb.append(",\"side\":").append(ModelJson.quote(order.side));
        sb.append(",\"signatureType\":").append(order.signatureType);
        sb.append(",\"timestamp\":").append(ModelJson.quote(order.timestamp));
        sb.append(",\"expiration\":").append(ModelJson.quote(order.expiration));
        sb.append(",\"metadata\":").append(ModelJson.quote(order.metadata));
        sb.append(",\"builder\":").append(ModelJson.quote(order.builder));
        sb.append(",\"signature\":").append(ModelJson.quote(order.signature));
        sb.append('}');
        sb.append(",\"owner\":").append(ModelJson.quote(owner == null ? "" : owner));
        sb.append(",\"orderType\":").append(ModelJson.quote(orderType));
        sb.append('}');
        return sb.toString();
    }

    /**
     * JS parseInt(s, 10) 후 JSON.stringify된 숫자 표기.
     * parseInt는 선행 공백·부호 뒤 10진 숫자 접두사를 읽어 double로 만든다 —
     * 정수 범위에서는 Double.parseDouble과 결과가 같고, 표기는
     * ECMAScript Number::toString을 따른다.
     */
    public static String jsParseInt(String s) {
        if (s == null) {
            return "null"; // JSON.stringify(NaN) = "null" (parseInt 실패 케이스)
        }
        String t = s.trim();
        int i = 0;
        if (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            i++;
        }
        int startDigits = i;
        while (i < t.length() && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
            i++;
        }
        if (i == startDigits) {
            return "null";
        }
        String numText = t.substring(0, i);
        double d;
        try {
            d = Double.parseDouble(numText);
        } catch (NumberFormatException e) {
            return "null";
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        return JsNumber.toString(d);
    }
}
