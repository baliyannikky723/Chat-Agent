package app.orrery.core.json;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, strict RFC 8259 JSON reader and writer.
 *
 * <p>The agent core parses provider wire formats (streamed chat chunks, search results)
 * and emits its own events. Keeping this in-house means the core has zero third-party
 * dependencies, runs and tests with nothing but the JDK, and is untouched by framework
 * upgrades (Spring Boot 3 to 4 moved from Jackson 2 to Jackson 3). Spring's own Jackson
 * still handles the REST DTOs at the web edge.
 *
 * <p>Parsed values map to plain Java: objects to {@link LinkedHashMap}, arrays to
 * {@link ArrayList}, numbers to {@link Long} or {@link Double} (or {@link BigDecimal} when
 * they do not fit), plus {@link String}, {@link Boolean} and {@code null}.
 */
public final class Json {

    private static final int MAX_DEPTH = 256;

    private Json() {}

    /** Parses a complete JSON document. Throws {@link JsonException} on any syntax error. */
    public static Object parse(String text) {
        if (text == null) throw new JsonException("No JSON input");
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) throw p.error("Unexpected trailing characters");
        return value;
    }

    /** Parses and wraps the result for safe navigation. */
    public static JNode tree(String text) {
        return JNode.of(parse(text));
    }

    /**
     * Lenient extraction for model output that should be JSON but may be wrapped in prose
     * or a code fence: parses the outermost {...} object. Returns null if none parses.
     */
    public static JNode lenientObject(String text) {
        if (text == null) return null;
        String t = text.trim();
        try {
            Object v = parse(t);
            if (v instanceof Map<?, ?>) return JNode.of(v);
        } catch (JsonException ignored) {
            // fall through to substring search
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        while (start >= 0 && end > start) {
            try {
                Object v = parse(t.substring(start, end + 1));
                if (v instanceof Map<?, ?>) return JNode.of(v);
            } catch (JsonException ignored) {
                // try a shorter span
            }
            end = t.lastIndexOf('}', end - 1);
        }
        return null;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeValue(sb, value, 0);
        return sb.toString();
    }

    /** Builds an ordered object: {@code obj("a", 1, "b", "x")}. Null values are kept. */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("obj() needs key/value pairs");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    public static List<Object> arr(Object... items) {
        List<Object> list = new ArrayList<>(items.length);
        for (Object item : items) list.add(item);
        return list;
    }

    /** Embeds an already-serialized JSON fragment verbatim when writing. */
    public record Raw(String json) {}

    // ── Writer ──────────────────────────────────────────────────────────────

    private static void writeValue(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH) throw new JsonException("Value nested too deeply to write");
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double d) {
            writeDouble(sb, d);
        } else if (value instanceof Float f) {
            writeDouble(sb, f.doubleValue());
        } else if (value instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof Character c) {
            writeString(sb, String.valueOf(c));
        } else if (value instanceof JNode node) {
            writeValue(sb, node.raw(), depth);
        } else if (value instanceof Raw raw) {
            sb.append(raw.json());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (value instanceof Collection<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item, depth + 1);
            }
            sb.append(']');
        } else if (value instanceof Object[] items) {
            writeValue(sb, List.of(items), depth);
        } else if (value instanceof Enum<?> e) {
            writeString(sb, e.name().toLowerCase(Locale.ROOT));
        } else if (value instanceof TemporalAccessor) {
            writeString(sb, value.toString());
        } else if (value.getClass().isRecord()) {
            writeRecord(sb, (Record) value, depth);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeRecord(StringBuilder sb, Record record, int depth) {
        sb.append('{');
        boolean first = true;
        for (RecordComponent rc : record.getClass().getRecordComponents()) {
            Object v;
            try {
                var accessor = rc.getAccessor();
                accessor.setAccessible(true);
                v = accessor.invoke(record);
            } catch (ReflectiveOperationException ex) {
                throw new JsonException("Cannot read record component " + rc.getName() + ": " + ex.getMessage());
            }
            if (!first) sb.append(',');
            first = false;
            writeString(sb, rc.getName());
            sb.append(':');
            writeValue(sb, v, depth + 1);
        }
        sb.append('}');
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append("\\u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
                                .append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ── Parser ──────────────────────────────────────────────────────────────

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        JsonException error(String message) {
            int from = Math.max(0, pos - 20);
            int to = Math.min(s.length(), pos + 20);
            return new JsonException(message + " at position " + pos + " near: " + s.substring(from, to).replace('\n', ' '));
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON nested too deeply");
            if (atEnd()) throw error("Unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        private Object readLiteral(String word, Object value) {
            if (!s.startsWith(word, pos)) throw error("Invalid literal");
            pos += word.length();
            return value;
        }

        private Map<String, Object> readObject(int depth) {
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') throw error("Expected a string key");
                String key = readString();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') throw error("Expected ':'");
                pos++;
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) throw error("Unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        private List<Object> readArray(int depth) {
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) throw error("Unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = null;
            int start = pos;
            while (true) {
                if (atEnd()) throw error("Unterminated string");
                char c = s.charAt(pos);
                if (c == '"') {
                    String out = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                    pos++;
                    return out;
                }
                if (c == '\\') {
                    if (sb == null) sb = new StringBuilder();
                    sb.append(s, start, pos);
                    pos++;
                    if (atEnd()) throw error("Unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw error("Truncated unicode escape");
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int d = Character.digit(s.charAt(pos + i), 16);
                                if (d < 0) throw error("Invalid unicode escape");
                                code = (code << 4) | d;
                            }
                            pos += 4;
                            sb.append((char) code);
                        }
                        default -> throw error("Invalid escape '\\" + e + "'");
                    }
                    start = pos;
                } else if (c < 0x20) {
                    throw error("Unescaped control character in string");
                } else {
                    pos++;
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            if (atEnd()) throw error("Invalid number");
            if (s.charAt(pos) == '0') {
                pos++;
            } else if (Character.isDigit(s.charAt(pos))) {
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            } else {
                throw error("Invalid number");
            }
            boolean decimal = false;
            if (!atEnd() && s.charAt(pos) == '.') {
                decimal = true;
                pos++;
                if (atEnd() || !Character.isDigit(s.charAt(pos))) throw error("Invalid fraction");
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                if (atEnd() || !Character.isDigit(s.charAt(pos))) throw error("Invalid exponent");
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String text = s.substring(start, pos);
            if (!decimal) {
                if (text.length() <= 18) return Long.parseLong(text);
                BigInteger big = new BigInteger(text);
                return big.bitLength() < 64 ? (Object) big.longValue() : new BigDecimal(big);
            }
            double d = Double.parseDouble(text);
            return Double.isInfinite(d) ? new BigDecimal(text) : d;
        }
    }
}
