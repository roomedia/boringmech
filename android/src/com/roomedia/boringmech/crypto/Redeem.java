package com.roomedia.boringmech.crypto;

import java.math.BigInteger;

/**
 * 리딤 콜데이터 — engine.js buildCtfRedeemCall / buildV2RedeemCall 재현
 * (viem encodeFunctionData와 같은 바이트를 만든다).
 */
public final class Redeem {
    private Redeem() {}

    public static final String PUSD = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB";
    public static final String COLLATERAL_ADAPTER = "0xAdA100Db00Ca00073811820692005400218FcE1f";
    public static final String NEG_RISK_COLLATERAL_ADAPTER = "0xadA2005600Dec949baf300f4C6120000bDB6eAab";
    public static final String PROTOCOL_V2_ROUTER = "0x12121212006e4CD160D18e3f00711DA5c3372600";

    /** CTF redeemPositions(address,bytes32,bytes32,uint256[]) — 어댑터 경유. */
    public static CallData ctfRedeem(String collateralToken, byte[] parentCollectionId,
                                     byte[] conditionId, BigInteger[] indexSets, boolean negRisk) {
        byte[][] tail = new byte[indexSets.length][];
        for (int i = 0; i < indexSets.length; i++) {
            tail[i] = Abi.word(indexSets[i]);
        }
        byte[] data = Abi.concat(
                Abi.selector("redeemPositions(address,bytes32,bytes32,uint256[])"),
                Abi.wordAddress(collateralToken),
                parentCollectionId,
                conditionId,
                Abi.word(BigInteger.valueOf(128)),
                Abi.word(BigInteger.valueOf(indexSets.length)),
                Abi.concat(tail));
        String target = negRisk ? NEG_RISK_COLLATERAL_ADAPTER : COLLATERAL_ADAPTER;
        return new CallData(target, BigInteger.ZERO, data);
    }

    /**
     * conditionId 끝 바이트가 00/01이면 떼어 31바이트로 만든다 (engine.js toBytes31).
     */
    public static byte[] toBytes31(String conditionIdHex) {
        String hex = conditionIdHex.toLowerCase();
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (hex.length() == 64 && (hex.endsWith("00") || hex.endsWith("01"))) {
            hex = hex.substring(0, 62);
        }
        return Hex.hexToBytes(hex);
    }

    /** Protocol v2 Router redeem(bytes31,uint256,uint256). bytes31은 왼쪽 정렬. */
    public static CallData v2Redeem(String conditionIdHex, BigInteger outcomeIndex, BigInteger amount) {
        byte[] b31 = toBytes31(conditionIdHex);
        byte[] word = new byte[32];
        System.arraycopy(b31, 0, word, 0, b31.length);
        byte[] data = Abi.concat(
                Abi.selector("redeem(bytes31,uint256,uint256)"),
                word,
                Abi.word(outcomeIndex),
                Abi.word(amount));
        return new CallData(PROTOCOL_V2_ROUTER, BigInteger.ZERO, data);
    }
}
