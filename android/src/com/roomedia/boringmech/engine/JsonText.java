package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.crypto.JsNumber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 텍스트 ↔ Map/List 모델 변환 — engine.js가 Gamma·Data API 응답 안의
 * 문자열 필드(outcomePrices·clobTokenIds·outcomes 등)에 거는 JSON.parse와,
 * judgeFill이 오류 객체에 거는 JSON.stringify를 재현한다.
 * 모델 규약은 Js 클래스와 같다 (객체 Map, 배열 List, 숫자 Double, null).
 * 파싱 실패는 IllegalArgumentException — JS의 SyntaxError throw에 대응한다.
 */
public final class JsonText {
    private JsonText() {}

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON.parse(null)");
        }
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("JSON 뒤쪽에 남은 문자 @" + p.pos());
        }
        return v;
    }

    /** JS JSON.stringify — undefined는 객체 키에서 빠지고 배열에서는 null이 된다. */
    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v == Js.UNDEFINED) {
            sb.append("null"); // 배열 원소 위치 — 객체 키는 아래에서 건너뛴다
            return;
        }
        if (v instanceof String) {
            writeString(sb, (String) v);
            return;
        }
        if (v instanceof Double) {
            double d = (Double) v;
            // JSON.stringify(NaN·±Infinity) = "null"
            sb.append(Double.isFinite(d) ? JsNumber.toString(d) : "null");
            return;
        }
        if (v instanceof Boolean) {
            sb.append((Boolean) v ? "true" : "false");
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object e : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, e);
            }
            sb.append(']');
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getValue() == Js.UNDEFINED) {
                    continue; // JSON.stringify는 undefined 값을 가진 키를 버린다
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        writeString(sb, Js.str(v));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        int pos() {
            return i;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("JSON 값 없음");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, i)) {
                throw new IllegalArgumentException("JSON 리터럴 오류: " + literal + " @" + i);
            }
            i += literal.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new IllegalArgumentException("JSON 객체 키 오류 @" + i);
                }
                String key = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("JSON 객체 콜론 오류 @" + i);
                }
                i++;
                ws();
                m.put(key, value());
                ws();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("JSON 객체 미종료");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return m;
                }
                throw new IllegalArgumentException("JSON 객체 구분자 오류 @" + i);
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("JSON 배열 미종료");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return l;
                }
                throw new IllegalArgumentException("JSON 배열 구분자 오류 @" + i);
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // "
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("JSON \\u 오류 @" + i);
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("JSON 이스케이프 오류 @" + i);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 문자열 미종료");
        }

        private Double number() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            if (start == i) {
                throw new IllegalArgumentException("JSON 숫자 오류 @" + i);
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("JSON 숫자 오류 @" + start);
            }
        }
    }
}
