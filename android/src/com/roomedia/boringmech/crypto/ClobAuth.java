package com.roomedia.boringmech.crypto;

import java.math.BigInteger;
import java.util.Map;

/** L1 인증 서명 — SDK buildClobEip712Signature (ClobAuth typed data) 재현. */
public final class ClobAuth {
    private ClobAuth() {}

    public static final String MSG_TO_SIGN = "This message attests that I control the given wallet";

    /**
     * @param timestamp 초 단위 Unix 시각의 10진 문자열 (JS timestamp.toString())
     */
    public static String sign(BigInteger privateKey, BigInteger chainId, String address,
                              String timestamp, BigInteger nonce) {
        Map<String, Object> domain = Eip712.value(
                "name", "ClobAuthDomain",
                "version", "1",
                "chainId", chainId);
        Map<String, java.util.List<Eip712.Field>> types = Eip712.types(
                "ClobAuth", Eip712.fields(
                        "address", "address",
                        "timestamp", "string",
                        "nonce", "uint256",
                        "message", "string"));
        Map<String, Object> message = Eip712.value(
                "address", address,
                "timestamp", timestamp,
                "nonce", nonce,
                "message", MSG_TO_SIGN);
        byte[] sig = Eip712.sign(privateKey, domain, "ClobAuth", types, message);
        return Hex.toHexPrefixed(sig);
    }
}
