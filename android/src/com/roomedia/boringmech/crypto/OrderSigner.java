package com.roomedia.boringmech.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * CLOB 주문 서명 (POLY_1271) — SDK ExchangeOrderBuilderV2/V3의 buildSignedOrder 재현.
 *
 * ERC-7739식 래핑: 최종 서명 =
 *   innerSig(65B) || appDomainSep(32B) || contentsHash(32B)
 *   || ORDER 타입 문자열 원문(186B) || uint16 BE 길이
 * innerSig는 거래소 도메인을 그대로 쓴 TypedDataSign 구조체의 EIP-712 서명이다.
 */
public final class OrderSigner {
    private OrderSigner() {}

    public static final String ORDER_TYPE_STRING =
            "Order(uint256 salt,address maker,address signer,uint256 tokenId,"
                    + "uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType,"
                    + "uint256 timestamp,bytes32 metadata,bytes32 builder)";
    public static final String EXCHANGE_DOMAIN_NAME = "Polymarket CTF Exchange";

    private static final byte[] ORDER_TYPE_HASH =
            Keccak.hash256(ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8));
    private static final byte[] DOMAIN_TYPE_HASH = Keccak.hash256(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                    .getBytes(StandardCharsets.UTF_8));
    private static final byte[] EXCHANGE_NAME_HASH =
            Keccak.hash256(EXCHANGE_DOMAIN_NAME.getBytes(StandardCharsets.UTF_8));

    public static final class Order {
        public BigInteger salt;
        public String maker;
        public String signer;
        public BigInteger tokenId;
        public BigInteger makerAmount;
        public BigInteger takerAmount;
        /** 0 = BUY, 1 = SELL. */
        public int side;
        public int signatureType;
        public BigInteger timestamp;
        public byte[] metadata = new byte[32];
        public byte[] builder = new byte[32];
    }

    private static Map<String, List<Eip712.Field>> orderTypes() {
        return Eip712.types(
                "Order", Eip712.fields(
                        "salt", "uint256",
                        "maker", "address",
                        "signer", "address",
                        "tokenId", "uint256",
                        "makerAmount", "uint256",
                        "takerAmount", "uint256",
                        "side", "uint8",
                        "signatureType", "uint8",
                        "timestamp", "uint256",
                        "metadata", "bytes32",
                        "builder", "bytes32"),
                "TypedDataSign", Eip712.fields(
                        "contents", "Order",
                        "name", "string",
                        "version", "string",
                        "chainId", "uint256",
                        "verifyingContract", "address",
                        "salt", "bytes32"));
    }

    private static Map<String, Object> orderMessage(Order o) {
        return Eip712.value(
                "salt", o.salt,
                "maker", o.maker,
                "signer", o.signer,
                "tokenId", o.tokenId,
                "makerAmount", o.makerAmount,
                "takerAmount", o.takerAmount,
                "side", BigInteger.valueOf(o.side),
                "signatureType", BigInteger.valueOf(o.signatureType),
                "timestamp", o.timestamp,
                "metadata", o.metadata,
                "builder", o.builder);
    }

    /** hashStruct(Order) — SDK가 수동 ABI 인코딩으로 구하는 값과 같다. */
    public static byte[] contentsHash(Order o) {
        byte[] encoded = Abi.concat(
                ORDER_TYPE_HASH,
                Abi.word(o.salt),
                Abi.wordAddress(o.maker),
                Abi.wordAddress(o.signer),
                Abi.word(o.tokenId),
                Abi.word(o.makerAmount),
                Abi.word(o.takerAmount),
                Abi.word(BigInteger.valueOf(o.side)),
                Abi.word(BigInteger.valueOf(o.signatureType)),
                Abi.word(o.timestamp),
                o.metadata,
                o.builder);
        return Keccak.hash256(encoded);
    }

    /** 거래소 도메인 separator — SDK 생성자가 미리 계산해 두는 값. */
    public static byte[] appDomainSeparator(String domainVersion, BigInteger chainId, String exchangeContract) {
        byte[] encoded = Abi.concat(
                DOMAIN_TYPE_HASH,
                EXCHANGE_NAME_HASH,
                Keccak.hash256(domainVersion.getBytes(StandardCharsets.UTF_8)),
                Abi.word(chainId),
                Abi.wordAddress(exchangeContract));
        return Keccak.hash256(encoded);
    }

    /**
     * POLY_1271 주문 서명 전체.
     *
     * @param domainVersion "2" 또는 "3"
     */
    public static String signOrder(BigInteger privateKey, Order order, String domainVersion,
                                   BigInteger chainId, String exchangeContract) {
        Map<String, Object> domain = Eip712.value(
                "name", EXCHANGE_DOMAIN_NAME,
                "version", domainVersion,
                "chainId", chainId,
                "verifyingContract", exchangeContract);
        Map<String, Object> typedDataSign = Eip712.value(
                "contents", orderMessage(order),
                "name", "DepositWallet",
                "version", "1",
                "chainId", chainId,
                "verifyingContract", order.signer,
                "salt", new byte[32]);
        byte[] innerSig = Eip712.sign(privateKey, domain, "TypedDataSign", orderTypes(), typedDataSign);
        byte[] appDomainSep = appDomainSeparator(domainVersion, chainId, exchangeContract);
        byte[] contentsHash = contentsHash(order);
        String lenHex = String.format("%04x", ORDER_TYPE_STRING.length());
        return "0x"
                + Hex.toHex(innerSig)
                + Hex.toHex(appDomainSep)
                + Hex.toHex(contentsHash)
                + Hex.toHex(ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8))
                + lenHex;
    }
}
