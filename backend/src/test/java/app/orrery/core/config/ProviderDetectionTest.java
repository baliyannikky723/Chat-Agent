package app.orrery.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.config.Settings.LlmProvider;
import app.orrery.core.config.Settings.SearchProviderId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderDetectionTest {

    @Test
    void picksTheFirstRealKeyAndIgnoresPlaceholders() {
        Settings s = ProviderDetection.fromEnv(Map.of(
                "OPENAI_API_KEY", "your-openai-key-here",
                "GROQ_API_KEY", "gsk_live_1234567890abcdef",
                "TAVILY_API_KEY", "tvly-dev-1234567890abcdef"));
        assertNotNull(s.llm());
        assertEquals(LlmProvider.GROQ, s.llm().provider());
        assertEquals("openai/gpt-oss-120b", s.llm().model());
        assertEquals("https://api.groq.com/openai/v1", s.llm().baseUrl());
        assertTrue(s.ready());
    }

    @Test
    void missingKeysAreExplainedNotFatal() {
        Settings s = ProviderDetection.fromEnv(Map.of());
        assertNull(s.llm());
        assertFalse(s.ready());
        assertTrue(s.llmMissing().contains("OPENAI_API_KEY"));
        assertTrue(s.searchMissing().contains("TAVILY_API_KEY"));
    }

    @Test
    void explicitProviderWithoutItsKeySaysWhichKey() {
        Settings s = ProviderDetection.fromEnv(Map.of("LLM_PROVIDER", "anthropic", "OPENAI_API_KEY", "sk-proj-abcdefghijklmnop"));
        assertNull(s.llm());
        assertEquals("ANTHROPIC_API_KEY is not set (LLM_PROVIDER=anthropic)", s.llmMissing());
    }

    @Test
    void geminiKeyAliasesAndModelOverride() {
        Settings s = ProviderDetection.fromEnv(Map.of("GEMINI_API_KEY", "AIzaSyD-1234567890abcdef", "LLM_MODEL", "gemini-3.7-flash"));
        assertEquals(LlmProvider.GOOGLE, s.llm().provider());
        assertEquals("gemini-3.7-flash", s.llm().model());
        assertFalse(s.llm().fallbacks().contains("gemini-3.7-flash"));
    }

    @Test
    void allSearchKeysAreKeptPreferredFirstForFailover() {
        Settings s = ProviderDetection.fromEnv(Map.of(
                "SEARCH_PROVIDER", "brave",
                "TAVILY_API_KEY", "tvly-dev-1234567890abcdef",
                "BRAVE_API_KEY", "BSA-1234567890abcdef"));
        assertEquals(List.of(SearchProviderId.BRAVE, SearchProviderId.TAVILY), s.search().stream().map(Settings.SearchKey::provider).toList());
    }

    @Test
    void localOpenAiCompatibleServerNeedsNoKey() {
        Settings s = ProviderDetection.fromEnv(Map.of("LLM_PROVIDER", "openai-compatible", "LLM_BASE_URL", "http://localhost:11434/v1/",
                "LLM_MODEL", "qwen3:8b"));
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, s.llm().provider());
        assertEquals("http://localhost:11434/v1", s.llm().baseUrl());
    }

    @Test
    void dotEnvParsing() {
        Map<String, String> env = DotEnv.parse(List.of("# comment", "export A=1", "B=\"two words\"", "C=three # trailing", "BAD LINE", "D="));
        assertEquals("1", env.get("A"));
        assertEquals("two words", env.get("B"));
        assertEquals("three", env.get("C"));
        assertEquals("", env.get("D"));
        assertFalse(env.containsKey("BAD LINE"));
    }

    @Test
    void dotEnvToleratesAByteOrderMarkAndWindowsLineEndings() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("orrery", ".env");
        java.nio.file.Files.write(file, "\uFEFFGROQ_API_KEY=gsk_abc\r\nTAVILY_API_KEY=tvly-xyz # comment\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> env = DotEnv.load(file, Map.of());
        assertEquals("gsk_abc", env.get("GROQ_API_KEY"));
        assertEquals("tvly-xyz", env.get("TAVILY_API_KEY"));
        java.nio.file.Files.write(file, new byte[] {'A', '=', '1', '\n', (byte) 0xE9, '\n', 'B', '=', '2', '\n'});
        Map<String, String> lenient = DotEnv.load(file, Map.of());
        assertEquals("1", lenient.get("A"));
        assertEquals("2", lenient.get("B"));
    }
}
