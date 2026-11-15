package com.roomedia.boringmech.crypto;

/** 16진수 인코딩/디코딩 유틸. */
public final class Hex {
    private Hex() {}

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    /** 0x 접두사 없는 소문자 hex. */
    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = DIGITS[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(out);
    }

    public static String toHexPrefixed(byte[] bytes) {
        return "0x" + toHex(bytes);
    }

    /** 0x 접두사가 있어도 없어도 된다. */
    public static byte[] hexToBytes(String hex) {
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if ((h.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length: " + hex);
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
