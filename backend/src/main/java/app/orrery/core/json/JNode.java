package app.orrery.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Null-safe navigation over parsed JSON. Reading a key that is not there never throws:
 * {@code node.get("choices").at(0).get("delta").get("content").asString(null)} simply
 * yields null when any step is missing — which is what streaming chunks need.
 */
public final class JNode {

    private static final Object MISSING_VALUE = new Object();

    public static final JNode MISSING = new JNode(MISSING_VALUE);
    private static final JNode NULL = new JNode(null);

    private final Object value;

    private JNode(Object value) {
        this.value = value;
    }

    public static JNode of(Object value) {
        if (value == null) return NULL;
        if (value instanceof JNode node) return node;
        return new JNode(value);
    }

    public JNode get(String key) {
        if (value instanceof Map<?, ?> map) {
            if (!map.containsKey(key)) return MISSING;
            return of(map.get(key));
        }
        return MISSING;
    }

    public JNode at(int index) {
        if (value instanceof List<?> list && index >= 0 && index < list.size()) return of(list.get(index));
        return MISSING;
    }

    public JNode path(String... keys) {
        JNode node = this;
        for (String key : keys) node = node.get(key);
        return node;
    }

    public boolean isMissing() {
        return value == MISSING_VALUE;
    }

    public boolean isNull() {
        return value == null;
    }

    /** Missing or JSON null. */
    public boolean isAbsent() {
        return value == null || value == MISSING_VALUE;
    }

    public boolean isObject() {
        return value instanceof Map<?, ?>;
    }

    public boolean isArray() {
        return value instanceof List<?>;
    }

    public boolean isString() {
        return value instanceof String;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    /** Strings as-is, numbers and booleans as text, anything else as {@code fallback}. */
    public String asString(String fallback) {
        if (value instanceof String s) return s;
        if (value instanceof BigDecimal bd) return bd.toPlainString();
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return fallback;
    }

    public String asString() {
        return asString(null);
    }

    /** Never null: {@code ""} when absent. */
    public String text() {
        return asString("");
    }

    public long asLong(long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public int asInt(int fallback) {
        long l = asLong(fallback);
        return l > Integer.MAX_VALUE || l < Integer.MIN_VALUE ? fallback : (int) l;
    }

    public double asDouble(double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean asBoolean(boolean fallback) {
        if (value instanceof Boolean b) return b;
        return fallback;
    }

    public List<JNode> items() {
        if (!(value instanceof List<?> list)) return List.of();
        List<JNode> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(of(o));
        return out;
    }

    public Set<String> keys() {
        if (!(value instanceof Map<?, ?> map)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object k : map.keySet()) out.add(String.valueOf(k));
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap() {
        if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap((Map<String, Object>) map);
        return Map.of();
    }

    public int size() {
        if (value instanceof Map<?, ?> map) return map.size();
        if (value instanceof List<?> list) return list.size();
        return 0;
    }

    /** The underlying plain-Java value (null for JSON null and for missing). */
    public Object raw() {
        return value == MISSING_VALUE ? null : value;
    }

    @Override
    public String toString() {
        return isMissing() ? "<missing>" : Json.write(value);
    }
}
