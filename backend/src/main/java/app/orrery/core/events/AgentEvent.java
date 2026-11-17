package app.orrery.core.events;

import app.orrery.core.json.Json;
import java.util.List;

/**
 * The contract between the agent and the UI. Every turn streams as a sequence of these
 * events over Server-Sent Events, so the UI never has to guess what the agent is doing:
 * recalling memory, searching, writing, or failing. Mirrored in web/src/lib/events.ts.
 */
public sealed interface AgentEvent {

    String type();

    /** JSON with "type" first, then the record's fields. */
    default String toJson() {
        String body = Json.write(this);
        String head = "{\"type\":\"" + type() + "\"";
        return body.length() <= 2 ? head + "}" : head + "," + body.substring(1);
    }

    record TurnStarted(String turnId, String conversationId, String userMessageId, String now, boolean isNewConversation)
            implements AgentEvent {
        public String type() {
            return "turn.started";
        }
    }

    record MemoryRecalled(RecallSummary recall) implements AgentEvent {
        public String type() {
            return "memory.recalled";
        }
    }

    /** phase: recalling | thinking | searching | reading | writing */
    record Status(String phase, String label) implements AgentEvent {
        public String type() {
            return "status";
        }
    }

    record SearchStarted(String id, String query, String recency, String since) implements AgentEvent {
        public String type() {
            return "search.started";
        }
    }

    record SearchCompleted(String id, String query, List<SourceRef> sources, boolean cached, long ms, String error)
            implements AgentEvent {
        public String type() {
            return "search.completed";
        }
    }

    record TextDelta(String text) implements AgentEvent {
        public String type() {
            return "text.delta";
        }
    }

    /** The model wrote a preamble and then decided to search: the UI drops that preamble. */
    record TextReset() implements AgentEvent {
        public String type() {
            return "text.reset";
        }
    }

    record Warning(String message) implements AgentEvent {
        public String type() {
            return "warning";
        }
    }

    record ConversationTitled(String conversationId, String title) implements AgentEvent {
        public String type() {
            return "conversation.titled";
        }
    }

    record TurnCompleted(String assistantMessageId, String text, List<SourceRef> sources, List<Integer> cited, TurnMetrics metrics)
            implements AgentEvent {
        public String type() {
            return "turn.completed";
        }
    }

    record TurnFailed(TurnError error, String partialText) implements AgentEvent {
        public String type() {
            return "turn.failed";
        }
    }

    // ── payloads ────────────────────────────────────────────────────────────

    /** A web page the answer can cite as [n]. Numbers are unique within one turn. */
    record SourceRef(int n, String url, String title, String domain, String publishedAt, String snippet) {}

    record Reference(String phrase, String resolved) {}

    /** A past turn pulled from memory for this message. */
    record RecallHit(String conversationId, String userMessageId, String title, String date, String label, String question, int claims) {}

    /** status: found | none | mismatch */
    record RecallSummary(String status, List<Reference> references, List<RecallHit> hits, String note) {}

    record TurnMetrics(
            Long ttftMs,
            long totalMs,
            int searches,
            int sources,
            int steps,
            String provider,
            String model,
            Long inputTokens,
            Long outputTokens) {}

    /**
     * code: llm_auth | llm_rate_limited | llm_model_not_found | llm_unavailable |
     * llm_bad_request | not_configured | busy | internal
     */
    record TurnError(String code, String message, String hint, boolean retryable) {}
}
