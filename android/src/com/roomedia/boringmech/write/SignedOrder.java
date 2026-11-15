package com.roomedia.boringmech.write;

import java.math.BigInteger;

/**
 * SDK가 서명한 주문 (ExchangeOrderBuilderV2/V3의 buildSignedOrder 반환).
 * 필드는 orderToJsonV2가 읽는 그대로이며, side는 문자열 "BUY"/"SELL",
 * 금액·timestamp·expiration은 10진 문자열, salt는 생성기의 문자열 원문이다
 * (본문 직렬화 때 parseInt 의미론으로 숫자가 되는 것은 OrderJson이 맡는다).
 */
public final class SignedOrder {
    public String salt;
    public String maker;
    public String signer;
    /** V2 주문에는 없다 — 있으면 본문에 포함된다 (orderToJsonV2의 taker 자리). */
    public String taker;
    public String tokenId;
    public String makerAmount;
    public String takerAmount;
    /** "BUY" 또는 "SELL". */
    public String side;
    public int signatureType;
    public String timestamp;
    public String expiration = "0";
    public String metadata;
    public String builder;
    public String signature;

    /** OrderSigner.Order로 변환 (서명 재생성·골든 대조용). */
    public com.roomedia.boringmech.crypto.OrderSigner.Order toSignerOrder() {
        com.roomedia.boringmech.crypto.OrderSigner.Order o =
                new com.roomedia.boringmech.crypto.OrderSigner.Order();
        o.salt = new BigInteger(salt);
        o.maker = maker;
        o.signer = signer;
        o.tokenId = new BigInteger(tokenId);
        o.makerAmount = new BigInteger(makerAmount);
        o.takerAmount = new BigInteger(takerAmount);
        o.side = "BUY".equals(side) ? 0 : 1;
        o.signatureType = signatureType;
        o.timestamp = new BigInteger(timestamp);
        o.metadata = com.roomedia.boringmech.crypto.Hex.hexToBytes(metadata);
        o.builder = com.roomedia.boringmech.crypto.Hex.hexToBytes(builder);
        return o;
    }
}
