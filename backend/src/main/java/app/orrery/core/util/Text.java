package app.orrery.core.util;

import java.util.regex.Pattern;

/** String helpers used across the core. */
public final class Text {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Text() {}

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public static String collapse(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** Cuts at a word boundary near {@code max} and appends an ellipsis when shortened. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut < max * 0.6) cut = max - 1;
        return s.substring(0, cut).stripTrailing() + "…";
    }

    /** Rough token estimate (≈4 characters per token for English). Good enough for budgets. */
    public static int estimateTokens(String s) {
        return s == null ? 0 : (s.length() + 3) / 4;
    }

    /** "Shortlist 5 CRM tools for a 20-person sales team" → first {@code words} words. */
    public static String firstWords(String s, int words) {
        String clean = collapse(s);
        if (clean.isEmpty()) return "";
        String[] parts = clean.split(" ");
        if (parts.length <= words) return clean.replaceAll("[.;:]+$", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString().replaceAll("[.,;:!?]+$", "") + "…";
    }

    /** Title-safe single line: no quotes or trailing punctuation, at most 80 characters. */
    public static String cleanTitle(String raw) {
        String t = collapse(raw).replaceAll("^[\"'“”‘’#*\\s]+|[\"'“”‘’*\\s]+$", "");
        t = t.replaceFirst("(?i)^title:\\s*", "");
        t = t.replaceAll("[.。!?]+$", "");
        return truncate(t, 80);
    }
}
