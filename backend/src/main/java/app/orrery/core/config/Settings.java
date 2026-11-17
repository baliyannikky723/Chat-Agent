package app.orrery.core.config;

import java.util.List;
import java.util.Locale;

/**
 * Everything the agent needs to know about its environment, resolved once at boot.
 *
 * <p>Design rule: a missing key never crashes the server and never switches on a silent
 * "mock" mode. The server starts, {@code /api/status} says exactly which key is missing,
 * the UI shows it, and chat stays disabled until it is fixed.
 */
public record Settings(
        LlmSettings llm,
        String llmMissing,
        List<SearchKey> search,
        String searchMissing,
        int searchMaxResults,
        int searchMaxPerTurn,
        boolean demoTools,
        String dataDir) {

    public boolean ready() {
        return llm != null && !search.isEmpty();
    }

    public enum LlmProvider {
        OPENAI("openai", "https://api.openai.com/v1"),
        ANTHROPIC("anthropic", "https://api.anthropic.com"),
        GOOGLE("google", "https://generativelanguage.googleapis.com/v1beta/openai"),
        GROQ("groq", "https://api.groq.com/openai/v1"),
        OPENROUTER("openrouter", "https://openrouter.ai/api/v1"),
        OPENAI_COMPATIBLE("openai-compatible", null);

        public final String id;
        public final String defaultBaseUrl;

        LlmProvider(String id, String defaultBaseUrl) {
            this.id = id;
            this.defaultBaseUrl = defaultBaseUrl;
        }

        public static LlmProvider fromId(String id) {
            if (id == null) return null;
            String norm = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (norm.equals("gemini")) norm = "google";
            for (LlmProvider p : values()) if (p.id.equals(norm)) return p;
            return null;
        }
    }

    public enum SearchProviderId {
        TAVILY("tavily"),
        BRAVE("brave"),
        SERPER("serper");

        public final String id;

        SearchProviderId(String id) {
            this.id = id;
        }

        public static SearchProviderId fromId(String id) {
            if (id == null) return null;
            for (SearchProviderId p : values()) if (p.id.equalsIgnoreCase(id.trim())) return p;
            return null;
        }
    }

    public record LlmSettings(
            LlmProvider provider,
            String apiKey,
            String keyEnv,
            String model,
            String fastModel,
            List<String> fallbacks,
            String baseUrl,
            String reasoningEffort) {}

    public record SearchKey(SearchProviderId provider, String apiKey, String keyEnv) {}
}
