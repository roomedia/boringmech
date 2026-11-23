package com.roomedia.boringmech.crypto;

/**
 * JS Number 의미론 재현 — SDK의 금액 산출이 double 연산과 Number#toString에
 * 기대고 있어, Java에서 같은 결과를 내려면 이 변환들을 그대로 따라야 한다.
 */
public final class JsNumber {
    private JsNumber() {}

    /**
     * ECMAScript Number::toString (10진). 최단 왕복 자릿수를 구해
     * ECMAScript 표기 규칙(고정/지수 전환 경계)으로 조립한다.
     * JDK 17의 Double.toString은 최단 표기가 아닌 경우가 있어(예:
     * 1234567890123456789 → 1.23456789012345677E18, JS는 …56800)
     * 자릿수는 BigDecimal 정확값에서 직접 탐색한다.
     */
    public static String toString(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        if (v == 0) {
            return "0";
        }
        if (v < 0) {
            return "-" + toString(-v);
        }
        String[] de = shortestDigits(v);
        String digits = de[0];
        long e10 = Long.parseLong(de[1]); // value = digits(정수) * 10^e10
        int k = digits.length();
        long n = e10 + k; // value = digits * 10^(n-k)
        StringBuilder sb = new StringBuilder();
        if (k <= n && n <= 21) {
            sb.append(digits);
            for (long i = 0; i < n - k; i++) {
                sb.append('0');
            }
        } else if (0 < n && n <= 21) {
            sb.append(digits, 0, (int) n).append('.').append(digits.substring((int) n));
        } else if (-6 < n && n <= 0) {
            sb.append("0.");
            for (long i = 0; i < -n; i++) {
                sb.append('0');
            }
            sb.append(digits);
        } else if (k == 1) {
            sb.append(digits).append('e').append(n - 1 >= 0 ? "+" : "").append(n - 1);
        } else {
            sb.append(digits.charAt(0)).append('.').append(digits.substring(1))
                    .append('e').append(n - 1 >= 0 ? "+" : "").append(n - 1);
        }
        return sb.toString();
    }

    /**
     * 최단 왕복 자릿수 탐색 — 정확값에 가장 가까운 p자리 10진수 중 double로
     * 되돌렸을 때 원래 값과 같아지는 가장 짧은 표기를 찾는다.
     * 반환: {자릿수 문자열(말행 0 없음), e10} — value = digits × 10^e10.
     */
    private static String[] shortestDigits(double v) {
        java.math.BigDecimal exact = new java.math.BigDecimal(v);
        for (int p = 1; p <= 17; p++) {
            java.math.BigDecimal r = exact.round(
                    new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN));
            java.math.BigDecimal ulp =
                    new java.math.BigDecimal(java.math.BigInteger.ONE, r.scale());
            java.math.BigDecimal[] candidates = {r, r.add(ulp), r.subtract(ulp)};
            java.math.BigDecimal best = null;
            java.math.BigDecimal bestDist = null;
            for (java.math.BigDecimal c : candidates) {
                if (c.signum() <= 0 || c.doubleValue() != v) {
                    continue;
                }
                java.math.BigDecimal dist = c.subtract(exact).abs();
                if (best == null || dist.compareTo(bestDist) < 0) {
                    best = c;
                    bestDist = dist;
                }
            }
            if (best != null) {
                java.math.BigDecimal s = best.stripTrailingZeros();
                return new String[]{s.unscaledValue().toString(),
                        Long.toString(-(long) s.scale())};
            }
        }
        // 도달 불가 (17자리면 항상 왕복한다) — 정확값 폴백
        java.math.BigDecimal s = exact.stripTrailingZeros();
        return new String[]{s.unscaledValue().toString(),
                Long.toString(-(long) s.scale())};
    }

    /** SDK utilities.decimalPlaces — num.toString()의 소수부 길이가 정본이다. */
    public static int decimalPlaces(double num) {
        if (num == Math.floor(num) && !Double.isInfinite(num)) {
            return 0;
        }
        String[] arr = toString(num).split("\\.", -1);
        if (arr.length <= 1) {
            return 0;
        }
        return arr[1].length();
    }

    /** SDK utilities.roundDown: Math.floor(num * 10^d) / 10^d. */
    public static double roundDown(double num, int decimals) {
        if (decimalPlaces(num) <= decimals) {
            return num;
        }
        double p = Math.pow(10, decimals);
        return Math.floor(num * p) / p;
    }

    /** SDK utilities.roundNormal: Math.round((num + EPSILON) * 10^d) / 10^d. */
    public static double roundNormal(double num, int decimals) {
        if (decimalPlaces(num) <= decimals) {
            return num;
        }
        double p = Math.pow(10, decimals);
        // Number.EPSILON = 2.220446049250313e-16
        return Math.round((num + 2.220446049250313e-16) * p) / p;
    }

    /** SDK utilities.roundUp: Math.ceil(num * 10^d) / 10^d. */
    public static double roundUp(double num, int decimals) {
        if (decimalPlaces(num) <= decimals) {
            return num;
        }
        double p = Math.pow(10, decimals);
        return Math.ceil(num * p) / p;
    }
}
