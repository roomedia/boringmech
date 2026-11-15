package com.roomedia.boringmech.crypto;

import java.math.BigInteger;

/**
 * 시장가 주문 금액 산출 — SDK getMarketOrderRawAmounts + viem parseUnits(…, 6)의 재현.
 * 중간 연산은 전부 JS와 같은 double 연산 순서를 따른다.
 */
public final class Amounts {
    private Amounts() {}

    public static final int COLLATERAL_DECIMALS = 6;

    public static final class RoundingConfig {
        public final int price;
        public final int size;
        public final int amount;

        public RoundingConfig(int price, int size, int amount) {
            this.price = price;
            this.size = size;
            this.amount = amount;
        }
    }

    /** SDK ROUNDING_CONFIG 표. */
    public static RoundingConfig roundingConfig(String tickSize) {
        switch (tickSize) {
            case "0.1":
                return new RoundingConfig(1, 2, 3);
            case "0.01":
                return new RoundingConfig(2, 2, 4);
            case "0.005":
                return new RoundingConfig(3, 2, 5);
            case "0.0025":
                return new RoundingConfig(4, 2, 6);
            case "0.001":
                return new RoundingConfig(3, 2, 5);
            case "0.0001":
                return new RoundingConfig(4, 2, 6);
            default:
                throw new IllegalArgumentException("unknown tick size: " + tickSize);
        }
    }

    /** {rawMakerAmt, rawTakerAmt} — JS double 그대로. */
    public static double[] rawAmounts(boolean buy, double amount, double price, RoundingConfig cfg) {
        double rawPrice = JsNumber.roundDown(price, cfg.price);
        double rawMakerAmt = JsNumber.roundDown(amount, cfg.size);
        double rawTakerAmt = buy ? rawMakerAmt / rawPrice : rawMakerAmt * rawPrice;
        if (JsNumber.decimalPlaces(rawTakerAmt) > cfg.amount) {
            rawTakerAmt = JsNumber.roundUp(rawTakerAmt, cfg.amount + 4);
            if (JsNumber.decimalPlaces(rawTakerAmt) > cfg.amount) {
                rawTakerAmt = JsNumber.roundDown(rawTakerAmt, cfg.amount);
            }
        }
        return new double[]{rawMakerAmt, rawTakerAmt};
    }

    /** {makerAmount, takerAmount} — 6자리 스케일 정수 문자열. */
    public static String[] marketOrderAmounts(boolean buy, double amount, double price, RoundingConfig cfg) {
        double[] raw = rawAmounts(buy, amount, price, cfg);
        return new String[]{
                parseUnits(JsNumber.toString(raw[0]), COLLATERAL_DECIMALS).toString(),
                parseUnits(JsNumber.toString(raw[1]), COLLATERAL_DECIMALS).toString(),
        };
    }

    /**
     * viem parseUnits (Value.from) 재현 — 문자열 기반이며, 소수 자릿수가
     * decimals를 넘으면 반올림(절댓값 기준 5 이상 올림, 자리올림 전파)을 한다.
     */
    public static BigInteger parseUnits(String value, int decimals) {
        if (!value.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            throw new IllegalArgumentException("invalid decimal number: " + value);
        }
        String[] parts = value.split("\\.", -1);
        String integer = parts[0];
        String fraction = parts.length > 1 ? parts[1] : "0";
        boolean negative = integer.startsWith("-");
        if (negative) {
            integer = integer.substring(1);
        }
        if (integer.isEmpty()) {
            integer = "0";
        }
        fraction = fraction.replaceAll("0+$", "");
        if (decimals == 0) {
            if (!fraction.isEmpty() && fraction.charAt(0) >= '5') {
                integer = new BigInteger(integer).add(BigInteger.ONE).toString();
            }
            fraction = "";
        } else if (fraction.length() > decimals) {
            String left = fraction.substring(0, decimals);
            int roundDigit = fraction.charAt(decimals) - '0';
            if (roundDigit >= 5) {
                String carried = carry(left);
                if (carried.length() > decimals) {
                    fraction = carried.substring(1);
                    integer = new BigInteger(integer).add(BigInteger.ONE).toString();
                } else {
                    fraction = carried;
                }
            } else {
                fraction = left;
            }
        } else {
            StringBuilder sb = new StringBuilder(fraction);
            while (sb.length() < decimals) {
                sb.append('0');
            }
            fraction = sb.toString();
        }
        BigInteger result = new BigInteger(integer + fraction);
        return negative ? result.negate() : result;
    }

    /** 10진 자리수 문자열에 1을 더한다 (viem Value.carry와 동일). */
    private static String carry(String digits) {
        char[] out = digits.toCharArray();
        int i = out.length - 1;
        while (i >= 0) {
            int d = (out[i] - '0') + 1;
            if (d < 10) {
                out[i] = (char) ('0' + d);
                return new String(out);
            }
            out[i] = '0';
            i--;
        }
        return "1" + new String(out);
    }
}
