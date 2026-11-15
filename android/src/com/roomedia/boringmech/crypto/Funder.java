package com.roomedia.boringmech.crypto;

import java.math.BigInteger;

/**
 * Deposit wallet(funder) 주소 파생 — app/src/depositWallet.js의 이식.
 * CREATE2: salt = keccak256(abi.encode(factory, walletId)),
 * 주소 = keccak256(0xff || factory || salt || keccak256(initBytecode))의 하위 20바이트.
 */
public final class Funder {
    private Funder() {}

    public static final String FACTORY = "0x00000000000Fb5C9ADea0298D729A0CB3823Cc07";
    private static final String IMPLEMENTATION = "0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB";
    private static final String BEACON = "0x7A18EDfe055488A3128f01F563e5B479D92ffc3a";

    private static final byte[] UUPS_CONST1 =
            Hex.hexToBytes("0xcc3735a920a3ca505d382bbc545af43d6000803e6038573d6000fd5b3d6000f3");
    private static final byte[] UUPS_CONST2 =
            Hex.hexToBytes("0x5155f3363d3d373d3d363d7f360894a13ba1a3210667c828492db98dca3e2076");
    private static final BigInteger UUPS_PREFIX_BASE = new BigInteger("61003d3d8160233d3973", 16);
    private static final byte[] BEACON_CONST1 =
            Hex.hexToBytes("0xb3582b35133d50545afa5036515af43d6000803e604d573d6000fd5b3d6000f3");
    private static final byte[] BEACON_CONST2 =
            Hex.hexToBytes("0x1b60e01b36527fa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6c");
    private static final byte[] BEACON_CONST3 =
            Hex.hexToBytes("0x60195155f3363d3d373d3d363d602036600436635c60da");
    private static final BigInteger BEACON_PREFIX_BASE = new BigInteger("6100523d8160233d3973", 16);

    /** abi.encode(address factory, bytes32 walletId) — walletId는 signer 주소를 32바이트로 채운 값. */
    private static byte[] depositWalletArgs(String signer) {
        byte[] walletId = new byte[32];
        byte[] addr = Hex.hexToBytes(signer);
        System.arraycopy(addr, 0, walletId, 12, 20);
        return Abi.concat(Abi.wordAddress(FACTORY), walletId);
    }

    /** base + (argsByteLength << 56)를 10바이트 빅엔디안으로. */
    private static byte[] prefixBytes(BigInteger base, int argsByteLength) {
        BigInteger v = base.add(BigInteger.valueOf(argsByteLength).shiftLeft(56));
        byte[] b = v.toByteArray();
        byte[] out = new byte[10];
        int len = Math.min(b.length, 10);
        System.arraycopy(b, b.length - len, out, 10 - len, len);
        return out;
    }

    private static String create2(byte[] bytecodeHash, byte[] args) {
        byte[] salt = Keccak.hash256(args);
        byte[] digest = Keccak.hash256(Abi.concat(
                new byte[]{(byte) 0xff},
                Hex.hexToBytes(FACTORY),
                salt,
                bytecodeHash));
        byte[] addr = new byte[20];
        System.arraycopy(digest, 12, addr, 0, 20);
        return Secp256k1.toChecksumAddress(addr);
    }

    public static String deriveUups(String signer) {
        byte[] args = depositWalletArgs(signer);
        byte[] bytecode = Abi.concat(
                prefixBytes(UUPS_PREFIX_BASE, args.length),
                Hex.hexToBytes(IMPLEMENTATION),
                Hex.hexToBytes("0x6009"),
                UUPS_CONST2,
                UUPS_CONST1,
                args);
        return create2(Keccak.hash256(bytecode), args);
    }

    public static String deriveBeacon(String signer) {
        byte[] args = depositWalletArgs(signer);
        byte[] bytecode = Abi.concat(
                prefixBytes(BEACON_PREFIX_BASE, args.length),
                Hex.hexToBytes(BEACON),
                BEACON_CONST3,
                BEACON_CONST2,
                BEACON_CONST1,
                args);
        return create2(Keccak.hash256(bytecode), args);
    }
}
