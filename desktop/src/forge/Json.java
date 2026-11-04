package forge;

import java.util.*;

/** Small protocol codec; no reflection or polymorphic object construction. */
public final class Json {
    private final String source;
    private int pos;
    private Json(String source) { this.source = source; }
    public static Object parse(String source) {
        Json parser = new Json(source);
        Object result = parser.value(0);
        parser.space();
        if (parser.pos != source.length())
            throw new IllegalArgumentException("Trailing JSON data");
        return result;
    }
    private void space() {
        while (pos < source.length() && " \t\r\n".indexOf(source.charAt(pos)) >= 0)
            pos++;
    }
    private char take() {
        if (pos >= source.length())
            throw new IllegalArgumentException("Incomplete JSON");
        return source.charAt(pos++);
    }
    private Object value(int depth) {
        if (depth > 64)
            throw new IllegalArgumentException("JSON nesting limit");
        space();
        char c = take();
        if (c == '"')
            return string();
        if (c == '{') {
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (pos < source.length() && source.charAt(pos) == '}') {
                pos++;
                return result;
            }
            while (true) {
                space();
                if (take() != '"')
                    throw new IllegalArgumentException("Expected object key");
                String key = string();
                space();
                if (take() != ':')
                    throw new IllegalArgumentException("Expected colon");
                if (result.containsKey(key))
                    throw new IllegalArgumentException("Duplicate object key");
                result.put(key, value(depth + 1));
                space();
                c = take();
                if (c == '}')
                    return result;
                if (c != ',')
                    throw new IllegalArgumentException("Expected comma");
            }
        }
        if (c == '[') {
            List<Object> result = new ArrayList<>();
            space();
            if (pos < source.length() && source.charAt(pos) == ']') {
                pos++;
                return result;
            }
            while (true) {
                result.add(value(depth + 1));
                space();
                c = take();
                if (c == ']')
                    return result;
                if (c != ',')
                    throw new IllegalArgumentException("Expected comma");
            }
        }
        pos--;
        int start = pos;
        while (pos < source.length() && ",]} \n\r\t".indexOf(source.charAt(pos)) < 0)
            pos++;
        String token = source.substring(start, pos);
        if (token.equals("true"))
            return true;
        if (token.equals("false"))
            return false;
        if (token.equals("null"))
            return null;
        if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
            throw new IllegalArgumentException("Invalid JSON value");
        return new java.math.BigDecimal(token);
    }
    private String string() {
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = take();
            if (c == '"')
                return out.toString();
            if (c < 32)
                throw new IllegalArgumentException("Control character in JSON string");
            if (c == '\\') {
                c = take();
                switch (c) {
                case '"', '\\', '/' -> out.append(c);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    int n = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = Character.digit(take(), 16);
                        if (digit < 0)
                            throw new IllegalArgumentException("Invalid Unicode escape");
                        n = n * 16 + digit;
                    }
                    out.append((char)n);
                }
                default -> throw new IllegalArgumentException("Invalid string escape");
                }
            } else
                out.append(c);
        }
    }
    public static String write(Object value) {
        if (value == null)
            return "null";
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c))
                        out.append(String.format("\\u%04x", (int)c));
                    else
                        out.append(c);
                }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number)
            return value.toString();
        if(value instanceof Map<?,?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((k, v) -> parts.add(write(k.toString()) + ":" + write(v)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object v : list)
                parts.add(write(v));
            return "[" + String.join(",", parts) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON type");
    }
}
