package com.roomedia.boringmech.crypto;

import java.math.BigInteger;

/** 이더리움 호출 1건 (target, value, data) — 리딤 콜과 Batch 서명의 공통 단위. */
public final class CallData {
    public final String target;
    public final BigInteger value;
    public final byte[] data;

    public CallData(String target, BigInteger value, byte[] data) {
        this.target = target;
        this.value = value;
        this.data = data;
    }

    public String dataHex() {
        return Hex.toHexPrefixed(data);
    }
}
