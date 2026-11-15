package com.roomedia.boringmech.crypto;

/**
 * JS Number 의미론 재현 — SDK의 금액 산출이 double 연산과 Number#toString에
 * 기대고 있어, Java에서 같은 결과를 내려면 이 변환들을 그대로 따라야 한다.
 */
public final class JsNumber {
    private JsNumber() {}

    /**
     * ECMAScript Number::toString (10진). Double.toString의 최단 자리수를 뽑아
     * ECMAScript 표기 규칙(고정/지수 전환 경계)으로 다시 조립한다.
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
        String j = Double.toString(v);
        int eIdx = j.indexOf('E');
        int exp = 0;
        String mant = j;
        if (eIdx >= 0) {
            exp = Integer.parseInt(j.substring(eIdx + 1));
            mant = j.substring(0, eIdx);
        }
        int dot = mant.indexOf('.');
        String digits;
        int fracDigits;
        if (dot >= 0) {
            digits = mant.substring(0, dot) + mant.substring(dot + 1);
            fracDigits = mant.length() - dot - 1;
        } else {
            digits = mant;
            fracDigits = 0;
        }
        // value = digits(정수) * 10^(exp - fracDigits)
        long e10 = (long) exp - fracDigits;
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        digits = digits.substring(start);
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
            e10++;
        }
        digits = digits.substring(0, end);
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

    /** SDK utilities.roundUp: Math.ceil(num * 10^d) / 10^d. */
    public static double roundUp(double num, int decimals) {
        if (decimalPlaces(num) <= decimals) {
            return num;
        }
        double p = Math.pow(10, decimals);
        return Math.ceil(num * p) / p;
    }
}
