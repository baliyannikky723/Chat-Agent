package app.orrery.core.llm;

import java.util.List;
import java.util.Map;

/** Provider-neutral chat types. Each client translates them to its own wire format. */
public final class LlmModels {

    private LlmModels() {}

    public enum ToolChoice { AUTO, REQUIRED, NONE }

    /** A function the model may call; {@code parameters} is a JSON Schema object. */
    public record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    /**
     * A tool call the model made. {@code arguments} is the raw JSON string. {@code extra}
     * keeps provider fields that must be sent back untouched on the next request — Gemini 3
     * rejects multi-turn tool use unless its {@code extra_content.google.thought_signature} is echoed.
     */
    public record ToolCall(String id, String name, String arguments, Map<String, Object> extra) {}

    public sealed interface ChatMessage permits UserMessage, AssistantMessage, ToolResultMessage {}

    public record UserMessage(String content) implements ChatMessage {}

    public record AssistantMessage(String content, List<ToolCall> toolCalls) implements ChatMessage {
        public AssistantMessage(String content) {
            this(content, List.of());
        }
    }

    public record ToolResultMessage(String toolCallId, String toolName, String content) implements ChatMessage {}

    public record Usage(Long inputTokens, Long outputTokens) {
        public static final Usage NONE = new Usage(null, null);

        public Usage plus(Usage other) {
            if (other == null) return this;
            return new Usage(add(inputTokens, other.inputTokens), add(outputTokens, other.outputTokens));
        }

        private static Long add(Long a, Long b) {
            if (a == null) return b;
            if (b == null) return a;
            return a + b;
        }
    }

    /**
     * @param model          filled in by {@link ModelRouter}
     * @param reasoningEffort e.g. "low"; sent only to models that accept it
     * @param jsonOutput     ask for a single JSON object (native JSON mode where available)
     */
    public record LlmRequest(
            String model,
            String system,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ToolChoice toolChoice,
            int maxOutputTokens,
            String reasoningEffort,
            boolean jsonOutput) {

        public LlmRequest withModel(String m) {
            return new LlmRequest(m, system, messages, tools, toolChoice, maxOutputTokens, reasoningEffort, jsonOutput);
        }
    }

    /** finishReason: stop | tool_calls | length | content_filter | other */
    public record LlmResult(String text, List<ToolCall> toolCalls, String finishReason, Usage usage, String model) {}

    /** Receives answer text as it streams. */
    @FunctionalInterface
    public interface StreamCallbacks {
        void onText(String delta);
    }
}
