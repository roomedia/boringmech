package com.roomedia.boringmech.crypto;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DepositWallet Batch 서명 — engine.js submitRedeemBatch의 signTypedData 재현.
 * 도메인은 지갑(DepositWallet) 자체가 verifyingContract다.
 */
public final class BatchSigner {
    private BatchSigner() {}

    public static String sign(BigInteger privateKey, BigInteger chainId, String wallet,
                              BigInteger nonce, BigInteger deadline, List<CallData> calls) {
        Map<String, List<Eip712.Field>> types = Eip712.types(
                "Batch", Eip712.fields(
                        "wallet", "address",
                        "nonce", "uint256",
                        "deadline", "uint256",
                        "calls", "Call[]"),
                "Call", Eip712.fields(
                        "target", "address",
                        "value", "uint256",
                        "data", "bytes"));
        List<Object> callValues = new ArrayList<>();
        for (CallData c : calls) {
            callValues.add(Eip712.value(
                    "target", c.target,
                    "value", c.value,
                    "data", c.data));
        }
        Map<String, Object> message = Eip712.value(
                "wallet", wallet,
                "nonce", nonce,
                "deadline", deadline,
                "calls", callValues);
        Map<String, Object> domain = Eip712.value(
                "name", "DepositWallet",
                "version", "1",
                "chainId", chainId,
                "verifyingContract", wallet);
        byte[] sig = Eip712.sign(privateKey, domain, "Batch", types, message);
        return Hex.toHexPrefixed(sig);
    }
}
