package com.roomedia.boringmech.write;

import com.roomedia.boringmech.crypto.JsNumber;
import com.roomedia.boringmech.engine.Js;

import java.util.List;
import java.util.Map;

/**
 * JS JSON.stringify의 모델 직렬화 재현 (Map/List/String/Double/Boolean/null).
 *
 * 규약:
 * - 객체 키는 삽입 순서(LinkedHashMap)대로, undefined 값은 키째로 생략된다
 *   (모델에서는 Js.UNDEFINED 센티넬이 undefined에 대응한다).
 * - 배열의 undefined·함수는 null로, 숫자의 NaN·±Infinity는 null로 바뀐다.
 * - 숫자 표기는 JsNumber(ECMAScript Number::toString)를 따른다.
 * - 문자열 이스케이프는 JSON.stringify와 같다: ", \, 제어문자만 이스케이프하고
 *   그 외 유니코드는 그대로 둔다.
 */
public final class ModelJson {
    private ModelJson() {}

    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, false);
        return sb.toString();
    }

    /** 객체 값 위치 — undefined면 false를 돌려줘 키를 생략하게 한다. */
    private static boolean write(StringBuilder sb, Object v, boolean inArray) {
        if (v == Js.UNDEFINED) {
            if (inArray) {
                sb.append("null");
                return true;
            }
            return false;
        }
        if (v == null) {
            sb.append("null");
            return true;
        }
        if (v instanceof String) {
            writeString(sb, (String) v);
            return true;
        }
        if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
            return true;
        }
        if (v instanceof Double || v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(JsNumber.toString(d));
            }
            return true;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                StringBuilder tmp = new StringBuilder();
                if (!write(tmp, e.getValue(), false)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                sb.append(tmp);
            }
            sb.append('}');
            return true;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object e : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, e, true);
            }
            sb.append(']');
            return true;
        }
        // 그 외 객체는 JS에서 toJSON/문자열로 떨어지지만 모델 범위 밖이다.
        writeString(sb, String.valueOf(v));
        return true;
    }

    /** JSON 문자열 리터럴 이스케이프 — 주문 본문 수동 직렬화와 공용. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        writeString(sb, s);
        return sb.toString();
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
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
}
