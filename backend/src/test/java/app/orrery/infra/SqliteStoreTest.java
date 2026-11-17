package app.orrery.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.store.FtsQuery;
import app.orrery.core.store.Store.ConversationSummary;
import app.orrery.core.store.Store.JobRow;
import app.orrery.core.store.Store.MessageRow;
import app.orrery.core.store.Store.NewClaim;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.store.Store.TurnRow;
import app.orrery.support.TestDb;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqliteStoreTest {

    private SqliteStore store;

    @BeforeEach
    void setUp() {
        store = TestDb.fresh();
        store.ensureUser("u1", "Asia/Kolkata", "en-IN");
        store.ensureUser("u2", "UTC", "en-GB");
        store.createConversation("c1", "u1", "2026-11-09T09:00:00.000Z");
        store.createConversation("c2", "u1", "2026-11-10T13:00:00.000Z");
        store.createConversation("c3", "u2", "2026-11-10T13:00:00.000Z");
        TestDb.turn(store, "c1", "u1", "q1", "a1", LocalDate.of(2026, 11, 9), "09:00", "Help me shortlist 5 CRM tools for a 20-person sales team",
                "HubSpot, Pipedrive, Salesforce, Zoho CRM and Freshsales [1].");
        TestDb.turn(store, "c2", "u1", "q2", "a2", LocalDate.of(2026, 11, 10), "13:00", "What are the best managed PostgreSQL hosting options?",
                "Neon, Supabase and AWS RDS. Neon Launch is $19/month [1].");
        TestDb.turn(store, "c3", "u2", "q3", "a3", LocalDate.of(2026, 11, 10), "13:00", "Another user's Postgres hosting question", "Private answer.");
    }

    @Test
    void usersAreUpsertedAndClockOffsetsStored() {
        assertEquals("Asia/Kolkata", store.user("u1").timezone());
        store.ensureUser("u1", "Europe/London", "en-GB");
        assertEquals("Europe/London", store.user("u1").timezone());
        store.setClockOffset("u1", -86_400_000L);
        assertEquals(-86_400_000L, store.user("u1").clockOffsetMillis());
        assertNull(store.user("nobody"));
    }

    @Test
    void fullTextRecallIsStemmedPrefixMatchedAndScopedToTheUser() {
        String fts = FtsQuery.build(List.of("postgres", "hosting"));
        assertEquals("\"postgres\"* OR \"hosting\"*", fts);
        List<TurnRow> hits = store.searchTurns("u1", fts, null, null, List.of(), 5);
        assertEquals(1, hits.size());
        assertEquals("q2", hits.get(0).userMessageId());
        assertEquals("a2", hits.get(0).assistantMessageId());
        assertTrue(hits.get(0).score() > 0);

        assertTrue(store.searchTurns("u1", fts, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3), List.of(), 5).isEmpty());
        assertTrue(store.searchTurns("u1", fts, null, null, List.of("q2"), 5).isEmpty(), "excluded ids are skipped");
        assertEquals("q1", store.searchTurns("u1", FtsQuery.build(List.of("pipedrive")), null, null, List.of(), 5).get(0).userMessageId(),
                "answers are searchable too");
    }

    @Test
    void extractedKeywordsBecomeSearchable() {
        assertTrue(store.searchTurns("u1", FtsQuery.build(List.of("database")), null, null, List.of(), 5).isEmpty());
        store.setMessageMemory("a2", "postgres postgresql managed database", List.of("Neon", "Supabase"));
        List<TurnRow> hits = store.searchTurns("u1", FtsQuery.build(List.of("database")), null, null, List.of(), 5);
        assertEquals("q2", hits.get(0).userMessageId());
        assertEquals(List.of("Neon", "Supabase"), hits.get(0).entities());
    }

    @Test
    void turnsByDateRangeAndHistoryBounds() {
        List<TurnRow> day = store.turnsInRange("u1", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9), List.of(), 10);
        assertEquals(List.of("q1"), day.stream().map(TurnRow::userMessageId).toList());
        var bounds = store.historyBounds("u1", List.of());
        assertEquals(LocalDate.of(2026, 11, 9), bounds.first());
        assertEquals(2, bounds.turns());
        assertEquals(1, store.turnCountsByDay("u1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of()).get(LocalDate.of(2026, 11, 10)));
    }

    @Test
    void conversationsListNewestFirstWithFallbackTitles() {
        List<ConversationSummary> list = store.listConversations("u1", 10);
        assertEquals(List.of("c2", "c1"), list.stream().map(ConversationSummary::id).toList());
        assertEquals("What are the best managed PostgreSQL hosting…", list.get(0).title());
        assertTrue(store.setTitleIfEmpty("c2", "Managed Postgres hosting"));
        assertFalse(store.setTitleIfEmpty("c2", "Another"));
        assertEquals("Managed Postgres hosting", store.listConversations("u1", 10).get(0).title());
        assertEquals(1, store.listConversations("u1", 10).get(0).turns());
    }

    @Test
    void sourcesClaimsAndTimeline() {
        store.insertSources("a2", List.of(new SourceRow(1, "https://neon.com/pricing", "Neon pricing", "neon.com", "2026-11-01", "Launch $19", true),
                new SourceRow(2, "https://supabase.com/pricing", "Supabase", "supabase.com", null, "", false)));
        assertEquals(2, store.sourcesFor(List.of("a2")).get("a2").size());
        assertTrue(store.sourcesFor(List.of("a2")).get("a2").get(0).cited());

        store.replaceClaims("u1", "c2", "a2", "2026-11-10T13:00:09.000Z", LocalDate.of(2026, 11, 10), List.of(
                new NewClaim("Neon", "Launch plan price", "$19/month", "Neon's Launch plan costs $19 per month.", "https://neon.com/pricing")));
        assertEquals("$19/month", store.claimsFor(List.of("a2")).get("a2").get(0).value());
        assertEquals(1, store.ledger("u1", 10).size());
        assertEquals(0, store.ledger("u2", 10).size());

        var timeline = store.timeline("u1", LocalDate.of(2026, 11, 1));
        assertEquals("q2", timeline.get(0).userMessageId());
        assertEquals("18:30", timeline.get(0).localTime(), "13:00 UTC is 18:30 in India");
        assertEquals(1, timeline.get(0).claims());
    }

    @Test
    void deletingAConversationRemovesItFromSearch() {
        assertTrue(store.deleteConversation("u1", "c2"));
        assertFalse(store.deleteConversation("u2", "c1"), "cannot delete another user's conversation");
        assertTrue(store.searchTurns("u1", FtsQuery.build(List.of("postgres")), null, null, List.of(), 5).isEmpty());
        assertNull(store.conversation("u1", "c2"));
    }

    @Test
    void jobsAreClaimedOnceRetriedAndRecovered() {
        store.enqueueJob("extract_memory", "{\"messageId\":\"a1\"}", 0);
        store.enqueueJob("extract_memory", "{\"messageId\":\"a2\"}", Long.MAX_VALUE);
        List<JobRow> claimed = store.claimJobs(5, 1000);
        assertEquals(1, claimed.size());
        assertEquals(1, claimed.get(0).attempts());
        assertTrue(store.claimJobs(5, 1000).isEmpty(), "a running job is not claimed twice");
        store.failJob(claimed.get(0).id(), "boom", 2000L);
        assertTrue(store.claimJobs(5, 1500).isEmpty(), "not before its retry time");
        assertEquals(2, store.claimJobs(5, 2500).get(0).attempts());
        assertEquals(1, store.resetRunningJobs());
        assertEquals(2, store.pendingJobs());
    }

    @Test
    void messagesComeBackOldestFirst() {
        List<MessageRow> msgs = store.conversationMessages("c1", 10);
        assertEquals(List.of("q1", "a1"), msgs.stream().map(MessageRow::id).toList());
        assertEquals("{\"totalMs\":9000}", msgs.get(1).metricsJson());
    }
}
