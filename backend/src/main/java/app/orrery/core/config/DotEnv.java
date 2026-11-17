package app.orrery.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal .env reader so {@code java -jar} picks up the same file {@code npm run dev} uses.
 * Real environment variables always win over the file.
 */
public final class DotEnv {

    private DotEnv() {}

    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean first = true;
        for (String raw : lines) {
            // Windows editors may save a byte-order mark at the start of the file.
            String line = (first && raw.startsWith("\uFEFF") ? raw.substring(1) : raw).strip();
            first = false;
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                char q = value.charAt(0);
                int close = value.indexOf(q, 1);
                value = close > 0 ? value.substring(1, close) : value.substring(1);
                if (q == '"') value = value.replace("\\n", "\n");
            } else {
                int hash = value.indexOf(" #");
                if (hash >= 0) value = value.substring(0, hash).strip();
            }
            out.put(key, value);
        }
        return out;
    }

    /** Environment merged over the .env file at {@code path} (if it exists). */
    public static Map<String, String> load(Path path, Map<String, String> environment) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (path != null && Files.isRegularFile(path)) {
            try {
                // Decoded leniently: one stray non-UTF-8 character must not hide every key in the file.
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                merged.putAll(parse(text.lines().toList()));
            } catch (IOException ignored) {
                // unreadable .env: fall back to the real environment
            }
        }
        for (Map.Entry<String, String> e : environment.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) merged.put(e.getKey(), e.getValue());
            else merged.putIfAbsent(e.getKey(), e.getValue());
        }
        return merged;
    }
}
