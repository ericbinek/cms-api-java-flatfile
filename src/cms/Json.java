package cms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON parser/serializer, hand-rolled to keep the target dependency-free.
 * Handles the JSON value types the CMS uses — objects, arrays, strings, numbers,
 * true/false/null — and guards nesting depth. The number scanner is lenient
 * (it accepts non-canonical forms such as 01 or 1.) rather than strictly
 * enforcing RFC 8259; that is sufficient for the trusted data this CMS reads and
 * writes. Maps preserve insertion order via LinkedHashMap.
 */
public final class Json {

    private Json() {}

    public static class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    public static Object parse(String src) {
        Parser p = new Parser(src);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.isEnd()) throw new JsonException("Unexpected trailing input at position " + p.pos);
        return value;
    }

    // ---------------- Serializer ----------------

    private static void write(StringBuilder sb, Object value) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof Boolean) { sb.append(value.toString()); return; }
        if (value instanceof Number) {
            Number n = (Number) value;
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("Cannot serialize " + d);
            }
            if (n instanceof Long || n instanceof Integer) {
                sb.append(n.toString());
                return;
            }
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
                sb.append(Long.toString((long) d));
                return;
            }
            sb.append(n.toString());
            return;
        }
        if (value instanceof String) { writeString(sb, (String) value); return; }
        if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object e : (List<?>) value) {
                if (!first) sb.append(',');
                write(sb, e);
                first = false;
            }
            sb.append(']');
            return;
        }
        if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                writeString(sb, entry.getKey().toString());
                sb.append(':');
                write(sb, entry.getValue());
                first = false;
            }
            sb.append('}');
            return;
        }
        throw new JsonException("Cannot serialize: " + value.getClass());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------- Parser ----------------

    private static final class Parser {
        static final int MAX_DEPTH = 512;
        final String src;
        int pos = 0;
        int depth = 0;

        Parser(String src) { this.src = src; }

        boolean isEnd() { return pos >= src.length(); }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            // Guard recursion depth so a deeply nested payload yields a JsonException
            // (mapped to 400) instead of a StackOverflowError (500).
            if (++depth > MAX_DEPTH) throw new JsonException("Maximum nesting depth exceeded at position " + pos);
            try {
                skipWhitespace();
                if (isEnd()) throw new JsonException("Unexpected end of input");
                char c = src.charAt(pos);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw new JsonException("Unexpected char at " + pos + ": " + c);
            } finally {
                depth--;
            }
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') throw new JsonException("Expected ':' at " + pos);
                pos++;
                map.put(key, parseValue());
                skipWhitespace();
                if (pos >= src.length()) throw new JsonException("Unterminated object");
                char next = src.charAt(pos);
                if (next == ',') { pos++; continue; }
                if (next == '}') { pos++; return map; }
                throw new JsonException("Expected ',' or '}' at " + pos);
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= src.length()) throw new JsonException("Unterminated array");
                char next = src.charAt(pos);
                if (next == ',') { pos++; continue; }
                if (next == ']') { pos++; return list; }
                throw new JsonException("Expected ',' or ']' at " + pos);
            }
        }

        String parseString() {
            if (pos >= src.length() || src.charAt(pos) != '"') throw new JsonException("Expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw new JsonException("Unterminated escape");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > src.length()) throw new JsonException("Bad unicode escape");
                            int code;
                            try {
                                code = Integer.parseInt(src.substring(pos, pos + 4), 16);
                            } catch (NumberFormatException e) {
                                throw new JsonException("Bad unicode escape at position " + pos);
                            }
                            sb.append((char) code);
                            pos += 4;
                            break;
                        default: throw new JsonException("Bad escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonException("Unterminated string");
        }

        Object parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            boolean isDecimal = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            }
            String text = src.substring(start, pos);
            try {
                if (!isDecimal) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException overflow) {
                        // Integer literal outside long range — fall back to double.
                    }
                }
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                // Malformed numbers ("-", "1e", ".") must surface as JsonException so
                // readCollection reports "Data file corrupted", not a raw 500.
                throw new JsonException("Invalid number at position " + start + ": " + text);
            }
        }

        Boolean parseBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("Expected boolean at " + pos);
        }

        Object parseNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("Expected null at " + pos);
        }
    }
}
