package app.orrery.core.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Persistence port of the agent core. Production uses {@code SqliteStore} (JDBC + FTS5);
 * tests use an in-memory implementation. All methods are scoped by user id so one
 * person can never read another's memory.
 */
public interface Store {

    // users
    UserRow ensureUser(String id, String timezone, String locale);

    UserRow user(String id);

    void setClockOffset(String userId, long offsetMillis);

    void deleteUserData(String userId);

    // conversations
    ConversationRow createConversation(String id, String userId, String createdAt);

    ConversationRow conversation(String userId, String id);

    List<ConversationSummary> listConversations(String userId, int limit);

    void touchConversation(String id, String at);

    boolean setTitleIfEmpty(String conversationId, String title);

    boolean renameConversation(String userId, String id, String title);

    boolean deleteConversation(String userId, String id);

    // messages
    void insertMessage(MessageRow message);

    MessageRow message(String userId, String id);

    /** Most recent {@code limit} messages of a conversation, oldest first. */
    List<MessageRow> conversationMessages(String conversationId, int limit);

    void setMessageMemory(String messageId, String keywords, List<String> entities);

    void insertSources(String messageId, List<SourceRow> sources);

    Map<String, List<SourceRow>> sourcesFor(List<String> messageIds);

    void insertSearches(String messageId, List<SearchLog> searches);

    /** The searches each answer ran, oldest first, keyed by assistant message id. */
    Map<String, List<SearchLog>> searchesFor(List<String> messageIds);

    // memory recall
    List<TurnRow> turnsInRange(String userId, LocalDate start, LocalDate end, List<String> excludeUserMessageIds, int limit);

    /** Full-text search over past questions and answers. {@code ftsQuery} comes from {@code FtsQuery.build}. */
    List<TurnRow> searchTurns(String userId, String ftsQuery, LocalDate start, LocalDate end, List<String> excludeUserMessageIds, int limit);

    List<TurnRow> recentTurns(String userId, String excludeConversationId, int limit);

    TurnRow turnByAssistantMessage(String userId, String assistantMessageId);

    HistoryBounds historyBounds(String userId, List<String> excludeUserMessageIds);

    Map<LocalDate, Integer> turnCountsByDay(String userId, LocalDate start, LocalDate end, List<String> excludeUserMessageIds);

    List<IndexEntry> conversationIndex(String userId, String excludeConversationId, int limit);

    // claim ledger
    void replaceClaims(String userId, String conversationId, String messageId, String asOf, LocalDate asOfLocal, List<NewClaim> claims);

    Map<String, List<ClaimRow>> claimsFor(List<String> messageIds);

    List<ClaimRow> ledger(String userId, int limit);

    List<TimelineRow> timeline(String userId, LocalDate since);

    // durable jobs
    void enqueueJob(String kind, String payloadJson, long runAfterEpochMillis);

    List<JobRow> claimJobs(int limit, long nowEpochMillis);

    void finishJob(long id);

    void failJob(long id, String error, Long retryAtEpochMillis);

    int resetRunningJobs();

    /** Jobs not yet finished or failed for good. */
    int pendingJobs();

    // ── rows ────────────────────────────────────────────────────────────────

    record UserRow(String id, String timezone, String locale, long clockOffsetMillis) {}

    record ConversationRow(String id, String userId, String title, String createdAt, String updatedAt) {}

    record ConversationSummary(String id, String title, String createdAt, String updatedAt, String lastLocalDate, int turns) {}

    /**
     * One message. A "turn" is a user message plus the assistant reply sharing its
     * {@code turnId}. {@code localDate} is the calendar day in the user's timezone when it
     * was sent — "last Tuesday" is answered against it.
     */
    record MessageRow(
            String id,
            String conversationId,
            String userId,
            String turnId,
            String role,
            String content,
            String status,
            String createdAt,
            LocalDate localDate,
            String timezone,
            String keywords,
            List<String> entities,
            String model,
            String metricsJson,
            String recallJson) {}

    record SourceRow(int n, String url, String title, String domain, String publishedAt, String snippet, boolean cited) {}

    record SearchLog(String query, String paramsJson, String provider, int resultCount, boolean cached, long durationMs, String error, String createdAt) {}

    /** A past question and the answer given to it. */
    record TurnRow(
            String userMessageId,
            String conversationId,
            String conversationTitle,
            String question,
            String createdAt,
            LocalDate localDate,
            String timezone,
            String assistantMessageId,
            String answer,
            List<String> entities,
            double score) {

        public TurnRow withScore(double s) {
            return new TurnRow(userMessageId, conversationId, conversationTitle, question, createdAt, localDate, timezone,
                    assistantMessageId, answer, entities, s);
        }
    }

    record HistoryBounds(LocalDate first, LocalDate last, int turns) {}

    record IndexEntry(String conversationId, String title, LocalDate date, List<String> entities) {}

    record NewClaim(String entity, String attribute, String value, String statement, String sourceUrl) {}

    record ClaimRow(
            long id,
            String messageId,
            String conversationId,
            String entity,
            String attribute,
            String value,
            String statement,
            String sourceUrl,
            String asOf,
            LocalDate asOfLocal) {}

    record TimelineRow(
            String conversationId,
            String conversationTitle,
            String userMessageId,
            String assistantMessageId,
            String question,
            LocalDate localDate,
            String localTime,
            int claims) {}

    record JobRow(long id, String kind, String payloadJson, int attempts) {}
}
