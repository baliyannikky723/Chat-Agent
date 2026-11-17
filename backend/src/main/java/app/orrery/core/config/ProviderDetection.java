package app.orrery.core.config;

import app.orrery.core.config.Settings.LlmProvider;
import app.orrery.core.config.Settings.LlmSettings;
import app.orrery.core.config.Settings.SearchKey;
import app.orrery.core.config.Settings.SearchProviderId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads API keys from the environment and picks providers.
 *
 * <p>With {@code LLM_PROVIDER=auto} (the default) the first key found wins, in the order
 * OpenAI, Anthropic, Gemini, Groq, OpenRouter. Search keys are all kept: the first is
 * primary and the rest are automatic failover.
 */
public final class ProviderDetection {

    /** Current defaults per provider; every one can be overridden with LLM_MODEL / LLM_FAST_MODEL. */
    record Defaults(String model, String fastModel, List<String> fallbacks) {}

    static final Map<LlmProvider, Defaults> DEFAULTS = Map.of(
            LlmProvider.OPENAI, new Defaults("gpt-5.6-terra", "gpt-5.4-mini", List.of("gpt-5.5", "gpt-5.4-mini", "gpt-4.1-mini")),
            LlmProvider.ANTHROPIC, new Defaults("claude-sonnet-5-5", "claude-haiku-5-5", List.of("claude-haiku-5-5", "claude-sonnet-4-5")),
            LlmProvider.GOOGLE, new Defaults("gemini-flash-latest", "gemini-flash-lite-latest", List.of("gemini-3.7-flash", "gemini-3.6-flash", "gemini-2.5-flash")),
            LlmProvider.GROQ, new Defaults("openai/gpt-oss-120b", "openai/gpt-oss-20b", List.of("llama-3.3-70b-versatile", "openai/gpt-oss-20b")),
            LlmProvider.OPENROUTER, new Defaults("openai/gpt-5.4-mini", "openai/gpt-5.4-mini", List.of("google/gemini-2.5-flash")),
            LlmProvider.OPENAI_COMPATIBLE, new Defaults("", "", List.of()));

    private static final Map<LlmProvider, List<String>> LLM_KEY_ENVS = Map.of(
            LlmProvider.OPENAI, List.of("OPENAI_API_KEY"),
            LlmProvider.ANTHROPIC, List.of("ANTHROPIC_API_KEY"),
            LlmProvider.GOOGLE, List.of("GEMINI_API_KEY", "GOOGLE_GENERATIVE_AI_API_KEY", "GOOGLE_API_KEY"),
            LlmProvider.GROQ, List.of("GROQ_API_KEY"),
            LlmProvider.OPENROUTER, List.of("OPENROUTER_API_KEY"));

    private static final List<LlmProvider> AUTO_ORDER =
            List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.GOOGLE, LlmProvider.GROQ, LlmProvider.OPENROUTER);

    private static final Map<SearchProviderId, List<String>> SEARCH_KEY_ENVS = Map.of(
            SearchProviderId.TAVILY, List.of("TAVILY_API_KEY"),
            SearchProviderId.BRAVE, List.of("BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY"),
            SearchProviderId.SERPER, List.of("SERPER_API_KEY"));

    private static final Pattern PLACEHOLDER =
            Pattern.compile("^(your|xxx|changeme|replace|paste|put[-_ ]?your|<).*", Pattern.CASE_INSENSITIVE);

    private ProviderDetection() {}

    public record Secret(String value, String name) {}

    /** Template placeholders ("your-key-here", "sk-...", "<key>") count as not set. */
    public static boolean isPlaceholder(String value) {
        if (value == null || value.length() < 8) return true;
        return PLACEHOLDER.matcher(value).matches() || value.contains("...") || value.matches("^sk-?\\.*$");
    }

    public static Secret readSecret(Map<String, String> env, List<String> names) {
        for (String name : names) {
            String raw = env.get(name);
            if (raw == null) continue;
            String v = raw.trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1).trim();
            }
            if (isPlaceholder(v)) continue;
            return new Secret(v, name);
        }
        return null;
    }

    private static String str(Map<String, String> env, String name) {
        String v = env.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int intFrom(Map<String, String> env, String name, int fallback, int min, int max) {
        String v = str(env, name);
        if (v == null) return fallback;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolFrom(Map<String, String> env, String name, boolean fallback) {
        String v = str(env, name);
        if (v == null) return fallback;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    public static Settings fromEnv(Map<String, String> env) {
        LlmPick llm = pickLlm(env);
        SearchPick search = pickSearch(env);
        return new Settings(
                llm.settings,
                llm.missing,
                search.keys,
                search.missing,
                intFrom(env, "SEARCH_MAX_RESULTS", 6, 3, 10),
                intFrom(env, "SEARCH_MAX_PER_TURN", 8, 2, 16),
                boolFrom(env, "DEMO_TOOLS", false),
                str(env, "ORRERY_DATA_DIR") != null ? str(env, "ORRERY_DATA_DIR") : "./data");
    }

    private record LlmPick(LlmSettings settings, String missing) {}

    private record SearchPick(List<SearchKey> keys, String missing) {}

    private static LlmSettings build(Map<String, String> env, LlmProvider provider, Secret key, String baseUrl) {
        Defaults d = DEFAULTS.get(provider);
        String model = str(env, "LLM_MODEL") != null ? str(env, "LLM_MODEL") : d.model();
        String fast = str(env, "LLM_FAST_MODEL") != null ? str(env, "LLM_FAST_MODEL") : (d.fastModel().isEmpty() ? model : d.fastModel());
        List<String> fallbacks = new ArrayList<>();
        for (String f : d.fallbacks()) if (!f.equals(model)) fallbacks.add(f);
        String effort = str(env, "LLM_REASONING_EFFORT") != null ? str(env, "LLM_REASONING_EFFORT").toLowerCase(Locale.ROOT) : "low";
        String url = baseUrl != null ? baseUrl : provider.defaultBaseUrl;
        return new LlmSettings(provider, key.value(), key.name(), model, fast, List.copyOf(fallbacks), stripSlash(url), effort);
    }

    private static String stripSlash(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static LlmPick pickLlm(Map<String, String> env) {
        String wantedRaw = str(env, "LLM_PROVIDER");
        String wanted = wantedRaw == null ? "auto" : wantedRaw.toLowerCase(Locale.ROOT);
        String baseUrl = str(env, "LLM_BASE_URL");

        boolean anyNamedKey = AUTO_ORDER.stream().anyMatch(p -> readSecret(env, LLM_KEY_ENVS.get(p)) != null);
        if (wanted.equals("openai-compatible") || (wanted.equals("auto") && baseUrl != null && !anyNamedKey)) {
            String model = str(env, "LLM_MODEL");
            if (baseUrl == null || model == null) {
                return new LlmPick(null, "LLM_BASE_URL and LLM_MODEL are required for LLM_PROVIDER=openai-compatible");
            }
            Secret key = readSecret(env, List.of("LLM_API_KEY"));
            if (key == null) key = new Secret("not-needed", "LLM_API_KEY"); // local servers such as Ollama
            return new LlmPick(build(env, LlmProvider.OPENAI_COMPATIBLE, key, baseUrl), null);
        }

        if (!wanted.equals("auto")) {
            LlmProvider p = LlmProvider.fromId(wanted);
            if (p == null || p == LlmProvider.OPENAI_COMPATIBLE) {
                return new LlmPick(null, "LLM_PROVIDER=\"" + wantedRaw + "\" is not supported. Use auto, openai, anthropic, google, groq, openrouter or openai-compatible");
            }
            Secret key = readSecret(env, LLM_KEY_ENVS.get(p));
            if (key == null) return new LlmPick(null, LLM_KEY_ENVS.get(p).get(0) + " is not set (LLM_PROVIDER=" + p.id + ")");
            return new LlmPick(build(env, p, key, baseUrl), null);
        }

        for (LlmProvider p : AUTO_ORDER) {
            Secret key = readSecret(env, LLM_KEY_ENVS.get(p));
            if (key != null) return new LlmPick(build(env, p, key, null), null);
        }
        return new LlmPick(null, "No model key found. Set one of OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, GROQ_API_KEY or OPENROUTER_API_KEY");
    }

    private static SearchPick pickSearch(Map<String, String> env) {
        String wantedRaw = str(env, "SEARCH_PROVIDER");
        String wanted = wantedRaw == null ? "auto" : wantedRaw.toLowerCase(Locale.ROOT);
        List<SearchKey> keys = new ArrayList<>();
        for (SearchProviderId id : SearchProviderId.values()) {
            Secret key = readSecret(env, SEARCH_KEY_ENVS.get(id));
            if (key != null) keys.add(new SearchKey(id, key.value(), key.name()));
        }
        if (!wanted.equals("auto")) {
            SearchProviderId preferred = SearchProviderId.fromId(wanted);
            if (preferred == null) {
                return new SearchPick(List.of(), "SEARCH_PROVIDER=\"" + wantedRaw + "\" is not supported. Use auto, tavily, brave or serper");
            }
            SearchKey first = keys.stream().filter(k -> k.provider() == preferred).findFirst().orElse(null);
            if (first == null) {
                return new SearchPick(List.of(), SEARCH_KEY_ENVS.get(preferred).get(0) + " is not set (SEARCH_PROVIDER=" + preferred.id + ")");
            }
            List<SearchKey> ordered = new ArrayList<>();
            ordered.add(first);
            for (SearchKey k : keys) if (k != first) ordered.add(k);
            return new SearchPick(List.copyOf(ordered), null);
        }
        if (keys.isEmpty()) {
            return new SearchPick(List.of(), "No search key found. Set TAVILY_API_KEY (free tier, no card), BRAVE_API_KEY or SERPER_API_KEY");
        }
        return new SearchPick(List.copyOf(keys), null);
    }

    /** "…abcd": safe to print in logs and the status page. */
    public static String mask(String key) {
        if (key == null || key.length() <= 4) return "****";
        return "…" + key.substring(key.length() - 4);
    }
}
