package app.orrery.web;

import app.orrery.core.events.AgentEvent.SourceRef;
import java.util.List;

/** Request and response bodies of the REST API. Mirrored in web/src/lib/types.ts. */
public final class ApiDtos {

    private ApiDtos() {}

    public record ChatRequest(String conversationId, String message, String recheckMessageId) {}

    public record RenameRequest(String title) {}

    /** localDateTime: "2026-11-09T14:30" in the user's timezone; null resets to real time. */
    public record ClockRequest(String localDateTime) {}

    public record ConversationDto(String id, String title, String createdAt, String updatedAt, String lastLocalDate, int turns) {}

    public record MessageDto(
            String id,
            String role,
            String content,
            String status,
            String createdAt,
            String localDate,
            List<SourceRef> sources,
            List<Integer> cited,
            List<SearchDto> searches,
            Object metrics,
            Object recall) {}

    /** One web search an answer ran: what was asked, the date filter, and what came back. */
    public record SearchDto(String query, String since, String recency, int results, boolean cached, long ms, String error) {}

    public record ConversationDetailDto(String id, String title, String createdAt, List<MessageDto> messages) {}

    public record TimelineTurnDto(
            String conversationId,
            String conversationTitle,
            String userMessageId,
            String assistantMessageId,
            String question,
            String time,
            int claims) {}

    public record TimelineDayDto(String date, String label, List<TimelineTurnDto> turns) {}

    public record LedgerPointDto(String value, String statement, String asOf, String asOfLocal, String sourceUrl, String conversationId, String messageId) {}

    public record LedgerEntryDto(String entity, String attribute, List<LedgerPointDto> history) {}

    public record LlmStatus(boolean configured, String provider, String model, String fastModel, String missing) {}

    public record SearchStatus(boolean configured, List<String> providers, String missing) {}

    public record ClockStatus(boolean demoTools, long offsetMs, String now, String timezone) {}

    public record StatusDto(boolean ready, LlmStatus llm, SearchStatus search, ClockStatus clock, String version) {}

    public record OkDto(boolean ok) {}

    /** Liveness plus what is configured; needs no user, so the launcher can print it. */
    public record HealthDto(boolean ok, boolean ready, String provider, String model, List<String> search, String version) {}
}
