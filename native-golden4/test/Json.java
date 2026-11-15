import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 테스트 전용 최소 JSON 파서 — engine 패키지의 Map/List 모델로 변환한다.
 * 객체 → LinkedHashMap, 배열 → List, 숫자 → Double, null → Java null.
 * (엔진 본체는 JSON에 의존하지 않고, 이 파서는 골든 대조 하네스에서만 쓴다.)
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("JSON 뒤쪽에 남은 문자: " + p.i);
        }
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private Object value() {
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
            String key = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw new IllegalArgumentException("JSON 객체 ':' 없음 @" + i);
            }
            i++;
            ws();
            m.put(key, value());
            ws();
            if (i >= s.length()) {
                throw new IllegalArgumentException("JSON 객체 미종결");
            }
            char c = s.charAt(i);
            if (c == ',') {
                i++;
            } else if (c == '}') {
                i++;
                return m;
            } else {
                throw new IllegalArgumentException("JSON 객체 구분자 오류 @" + i);
            }
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
                throw new IllegalArgumentException("JSON 배열 미종결");
            }
            char c = s.charAt(i);
            if (c == ',') {
                i++;
            } else if (c == ']') {
                i++;
                return l;
            } else {
                throw new IllegalArgumentException("JSON 배열 구분자 오류 @" + i);
            }
        }
    }

    private String string() {
        if (s.charAt(i) != '"') {
            throw new IllegalArgumentException("JSON 문자열 시작 오류 @" + i);
        }
        i++;
        StringBuilder sb = new StringBuilder();
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
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("JSON 이스케이프 오류: \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("JSON 문자열 미종결");
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
        return Double.parseDouble(s.substring(start, i));
    }
}
