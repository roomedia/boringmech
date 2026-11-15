package com.roomedia.boringmech.crypto;

import org.bouncycastle.crypto.digests.KeccakDigest;

/** Keccak-256 (SHA3-256과 패딩이 다르다 — 이더리움 표준 해시). */
public final class Keccak {
    private Keccak() {}

    public static byte[] hash256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }
}
