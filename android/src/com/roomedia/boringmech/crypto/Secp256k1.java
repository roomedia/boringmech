package com.roomedia.boringmech.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;

/**
 * secp256k1 원시 연산 — viem/@noble/curves의 서명과 바이트 단위로 같아야 한다.
 * 서명은 RFC 6979 결정적 nonce(HMAC-SHA256 DRBG), low-s 정규화, v = 27 + recoveryId.
 */
public final class Secp256k1 {
    private Secp256k1() {}

    private static final X9ECParameters CURVE = ECNamedCurveTable.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN =
            new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
    public static final BigInteger N = CURVE.getN();
    private static final BigInteger HALF_N = N.shiftRight(1);

    public static BigInteger privateKey(byte[] privKeyBytes) {
        return new BigInteger(1, privKeyBytes);
    }

    public static ECPoint publicPoint(BigInteger d) {
        return CURVE.getG().multiply(d).normalize();
    }

    /** 비압축 공개키의 x||y 64바이트. */
    public static byte[] publicKeyBytes(BigInteger d) {
        ECPoint p = publicPoint(d);
        byte[] out = new byte[64];
        System.arraycopy(toBytes32(p.getAffineXCoord().toBigInteger()), 0, out, 0, 32);
        System.arraycopy(toBytes32(p.getAffineYCoord().toBigInteger()), 0, out, 32, 32);
        return out;
    }

    public static byte[] addressBytes(BigInteger d) {
        byte[] h = Keccak.hash256(publicKeyBytes(d));
        return Arrays.copyOfRange(h, 12, 32);
    }

    public static String address(BigInteger d) {
        return toChecksumAddress(addressBytes(d));
    }

    /** EIP-55 체크섬 표기. */
    public static String toChecksumAddress(byte[] addressBytes) {
        String lower = Hex.toHex(addressBytes);
        byte[] hash = Keccak.hash256(lower.getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'f') {
                int b = hash[i / 2] & 0xFF;
                int nibble = (i % 2 == 0) ? (b >> 4) : (b & 0xF);
                if (nibble >= 8) {
                    c = Character.toUpperCase(c);
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 32바이트 해시에 서명해 r||s||v 65바이트를 돌려준다.
     * noble의 secp256k1.sign(hash, key, {lowS: true, extraEntropy: false})와 동일.
     */
    public static byte[] signHash(BigInteger d, byte[] hash) {
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(d, DOMAIN));
        BigInteger[] sig = signer.generateSignature(hash);
        BigInteger r = sig[0];
        BigInteger s = sig[1];
        if (s.compareTo(HALF_N) > 0) {
            s = N.subtract(s);
        }
        int recovery = recoverId(r, s, hash, publicPoint(d));
        byte[] out = new byte[65];
        System.arraycopy(toBytes32(r), 0, out, 0, 32);
        System.arraycopy(toBytes32(s), 0, out, 32, 32);
        out[64] = (byte) (27 + recovery);
        return out;
    }

    /** 최종 (r, s)로 공개키를 복원해 실제 공개키와 맞는 패리티를 찾는다. */
    private static int recoverId(BigInteger r, BigInteger s, byte[] hash, ECPoint pub) {
        BigInteger e = new BigInteger(1, hash);
        BigInteger rInv = r.modInverse(N);
        BigInteger sCoeff = s.multiply(rInv).mod(N);
        BigInteger eCoeff = e.multiply(rInv).mod(N).negate().mod(N);
        BigInteger pubX = pub.getAffineXCoord().toBigInteger();
        BigInteger pubY = pub.getAffineYCoord().toBigInteger();
        for (int i = 0; i < 2; i++) {
            byte[] comp = new byte[33];
            comp[0] = (byte) (0x02 | i);
            System.arraycopy(toBytes32(r), 0, comp, 1, 32);
            ECPoint pointR;
            try {
                pointR = CURVE.getCurve().decodePoint(comp);
            } catch (RuntimeException ex) {
                continue;
            }
            if (!pointR.multiply(N).isInfinity()) {
                continue;
            }
            ECPoint q = ECAlgorithms.sumOfTwoMultiplies(CURVE.getG(), eCoeff, pointR, sCoeff).normalize();
            if (q.getAffineXCoord().toBigInteger().equals(pubX)
                    && q.getAffineYCoord().toBigInteger().equals(pubY)) {
                return i;
            }
        }
        throw new IllegalStateException("signature recovery failed");
    }

    public static byte[] toBytes32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, 32 - len, len);
        return out;
    }
}
