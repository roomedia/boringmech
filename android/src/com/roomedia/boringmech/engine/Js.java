package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.crypto.JsNumber;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JS 값 의미론 재현 — engine.js의 Number() 변환·truthiness·템플릿 문자열
 * 변환을 Map/List 기반 JSON 모델 위에서 그대로 따라 한다.
 *
 * 모델 규약:
 *   JSON 객체 → Map&lt;String,Object&gt;, 배열 → List&lt;Object&gt;,
 *   문자열 → String, 숫자 → Double, 불리언 → Boolean, null → Java null.
 *   키 부재(undefined)와 null은 JS에서 Number 변환 결과가 다르므로
 *   (undefined→NaN, null→0) field()가 UNDEFINED 센티넬로 구분한다.
 */
public final class Js {
    private Js() {}

    /** JS undefined에 대응하는 센티넬 — field()가 키 부재를 알릴 때 쓴다. */
    public static final Object UNDEFINED = new Object();

    /** Number.EPSILON (2^-52). */
    public static final double EPSILON = 2.220446049250313e-16;

    /** 행(객체 모델)에서 필드를 읽는다. 행이 객체가 아니거나 키가 없으면 UNDEFINED. */
    public static Object field(Object row, String key) {
        if (row instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) row;
            if (m.containsKey(key)) {
                return m.get(key);
            }
        }
        return UNDEFINED;
    }

    /** JS truthiness — undefined·null·false·0·-0·NaN·""만 falsy, 객체·배열은 항상 truthy. */
    public static boolean truthy(Object v) {
        if (v == null || v == UNDEFINED) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Double) {
            double d = (Double) v;
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String) {
            return !((String) v).isEmpty();
        }
        return true;
    }

    /**
     * JS Number(v) — v는 "존재하는" 값이어야 한다 (키 부재는 fieldNum이 처리).
     * 배열은 원본 경로에서 숫자로 읽는 일이 없어 단일 원소까지만 근사한다
     * (Number([x]) = Number(String(x))와 같은 결과).
     */
    public static double num(Object v) {
        if (v == null) {
            return 0; // Number(null) = 0
        }
        if (v == UNDEFINED) {
            return Double.NaN; // Number(undefined) = NaN
        }
        if (v instanceof Double) {
            return (Double) v;
        }
        if (v instanceof Boolean) {
            return (Boolean) v ? 1 : 0;
        }
        if (v instanceof String) {
            return parseNumber((String) v);
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                return 0; // Number([]) = 0
            }
            if (l.size() == 1) {
                return num(l.get(0));
            }
            return Double.NaN;
        }
        return Double.NaN; // Number({}) = NaN
    }

    /** 필드의 JS Number 변환 — 키 부재(undefined)는 NaN. */
    public static double fieldNum(Object row, String key) {
        return num(field(row, key));
    }

    /** JS String(v) / 템플릿 리터럴 보간 변환. */
    public static String str(Object v) {
        if (v == UNDEFINED) {
            return "undefined";
        }
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Double) {
            return JsNumber.toString((Double) v);
        }
        if (v instanceof Boolean) {
            return (Boolean) v ? "true" : "false";
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder();
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object e = l.get(i);
                if (e != null && e != UNDEFINED) {
                    sb.append(str(e));
                }
            }
            return sb.toString();
        }
        return "[object Object]";
    }

    /** engine.js round2 — Math.round((x + Number.EPSILON) * 100) / 100. */
    public static double round2(double x) {
        if (!Double.isFinite(x)) {
            return x; // JS Math.round(NaN)=NaN, Math.round(±Infinity)=±Infinity
        }
        return Math.round((x + EPSILON) * 100) / 100.0;
    }

    // --- Number(String) 파싱 ---

    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern HEX_DIGITS = Pattern.compile("[0-9a-fA-F]+");
    private static final Pattern OCT_DIGITS = Pattern.compile("[0-7]+");
    private static final Pattern BIN_DIGITS = Pattern.compile("[01]+");

    /** JS Number(문자열) — 공백 제거 후 빈 문자열은 0, 진법 리터럴·Infinity 지원. */
    public static double parseNumber(String s) {
        String t = trimJsWhitespace(s);
        if (t.isEmpty()) {
            return 0;
        }
        if (t.equals("Infinity") || t.equals("+Infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (t.equals("-Infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        // 진법 리터럴에는 부호가 붙을 수 없다 (Number("-0x10") = NaN).
        if (t.length() > 2 && t.charAt(0) == '0') {
            char p = t.charAt(1);
            String digits = t.substring(2);
            try {
                if ((p == 'x' || p == 'X') && HEX_DIGITS.matcher(digits).matches()) {
                    return new BigInteger(digits, 16).doubleValue();
                }
                if ((p == 'o' || p == 'O') && OCT_DIGITS.matcher(digits).matches()) {
                    return new BigInteger(digits, 8).doubleValue();
                }
                if ((p == 'b' || p == 'B') && BIN_DIGITS.matcher(digits).matches()) {
                    return new BigInteger(digits, 2).doubleValue();
                }
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
            if (p == 'x' || p == 'X' || p == 'o' || p == 'O' || p == 'b' || p == 'B') {
                return Double.NaN;
            }
        }
        if (!DECIMAL.matcher(t).matches()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** JS StringTrim — 화이트스페이스·라인 터미네이터·유니코드 공백을 양끝에서 제거. */
    private static String trimJsWhitespace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && isJsWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isJsWhitespace(char c) {
        if (c <= 0x20) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f'
                    || c == '\r';
        }
        return c == '\u00A0' || c == '\uFEFF' || c == '\u2028' || c == '\u2029'
                || Character.getType(c) == Character.SPACE_SEPARATOR;
    }
}
