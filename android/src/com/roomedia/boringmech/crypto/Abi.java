package com.roomedia.boringmech.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** ABI 인코딩 최소 집합 — EIP-712와 콜데이터 조립에 쓰는 정적 타입 위주. */
public final class Abi {
    private Abi() {}

    /** uint256/uint8/int 등 정수 타입의 32바이트 워드. */
    public static byte[] word(BigInteger v) {
        return Secp256k1.toBytes32(v.mod(BigInteger.ONE.shiftLeft(256)));
    }

    public static byte[] wordAddress(String hexAddress) {
        return wordAddressBytes(Hex.hexToBytes(hexAddress));
    }

    public static byte[] wordAddressBytes(byte[] address20) {
        if (address20.length != 20) {
            throw new IllegalArgumentException("address must be 20 bytes");
        }
        byte[] out = new byte[32];
        System.arraycopy(address20, 0, out, 12, 20);
        return out;
    }

    /** 함수 시그니처 문자열의 selector (keccak 첫 4바이트). */
    public static byte[] selector(String signature) {
        byte[] h = Keccak.hash256(signature.getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(h, 4);
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            bos.write(p, 0, p.length);
        }
        return bos.toByteArray();
    }
}
