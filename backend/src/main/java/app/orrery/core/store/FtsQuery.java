package app.orrery.core.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a safe SQLite FTS5 MATCH expression from topic terms: each term is reduced to
 * letters and digits, quoted, prefix-matched and OR-ed.
 *
 * <pre>[postgres, hosting] → "postgres"* OR "hosting"*</pre>
 *
 * Prefix matching lets "postgres" find "postgresql"; the porter tokenizer on the index
 * side makes "hosting" find "hosted". Quoting means user text can never inject FTS
 * operators such as NOT or NEAR.
 */
public final class FtsQuery {

    private FtsQuery() {}

    public static String build(List<String> terms) {
        List<String> parts = new ArrayList<>();
        for (String term : terms) {
            for (String piece : term.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (piece.length() < 2) continue;
                String quoted = "\"" + piece + "\"" + (piece.length() >= 3 ? "*" : "");
                if (!parts.contains(quoted)) parts.add(quoted);
                if (parts.size() >= 12) break;
            }
        }
        return String.join(" OR ", parts);
    }
}
