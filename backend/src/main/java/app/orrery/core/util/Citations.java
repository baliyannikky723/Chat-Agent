package app.orrery.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading and rewriting the [n] citation markers the model writes. */
public final class Citations {

    /** [3], [1, 4] — but not markdown links like [3](https://…). */
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)\\](?!\\()");

    private Citations() {}

    /** Citation numbers that appear in {@code text}, sorted, limited to 1..maxN. */
    public static List<Integer> cited(String text, int maxN) {
        TreeSet<Integer> out = new TreeSet<>();
        if (text == null) return List.of();
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                int n = Integer.parseInt(part.strip());
                if (n >= 1 && n <= maxN) out.add(n);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Replaces [n] with "(domain)" so text from an earlier turn can be shown to the model
     * without it re-citing stale numbers. Unknown numbers are dropped.
     */
    public static String rewriteToDomains(String text, Map<Integer, String> domainByNumber) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = MARKER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            List<String> domains = new ArrayList<>();
            for (String part : m.group(1).split(",")) {
                String d = domainByNumber.get(Integer.parseInt(part.strip()));
                if (d != null && !domains.contains(d)) domains.add(d);
            }
            String replacement = domains.isEmpty() ? "" : " (" + String.join(", ", domains) + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll(" +\\(", " (").replaceAll("\\s+([.,;:])", "$1");
    }
}
