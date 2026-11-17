package app.orrery.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.store.Store.NewClaim;
import app.orrery.core.understand.IntentAnalyzer;
import app.orrery.core.understand.TemporalResolver;
import app.orrery.core.understand.TimeRef;
import app.orrery.core.understand.TopicTerms;
import app.orrery.core.understand.TurnIntent;
import app.orrery.infra.SqliteStore;
import app.orrery.support.TestDb;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The brief's three example questions, asked on Saturday 14 Nov 2026 with history in place. */
class MemoryRecallTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 14, 21, 0, 0, 0, ZoneId.of("Asia/Kolkata"));

    private SqliteStore store;
    private MemoryRecall recall;

    @BeforeEach
    void setUp() {
        store = TestDb.fresh();
        store.ensureUser("u", "Asia/Kolkata", "en-IN");
        store.createConversation("c-node", "u", "2026-11-03T05:30:00.000Z");
        store.createConversation("c-crm", "u", "2026-11-09T09:00:00.000Z");
        store.createConversation("c-pg", "u", "2026-11-10T13:10:00.000Z");
        TestDb.turn(store, "c-node", "u", "q-node", "a-node", LocalDate.of(2026, 11, 3), "05:30",
                "What is the latest LTS version of Node.js?", "Node.js 24 is the active LTS line [1].");
        TestDb.turn(store, "c-crm", "u", "q-crm", "a-crm", LocalDate.of(2026, 11, 9), "09:00",
                "Help me shortlist 5 CRM tools for a 20-person sales team. Include pricing.",
                "1. HubSpot Sales Hub Professional — $90/seat/month [1]\n2. Pipedrive Growth — $39/seat/month [2]");
        TestDb.turn(store, "c-pg", "u", "q-pg", "a-pg", LocalDate.of(2026, 11, 10), "13:10",
                "What are the best managed Postgres hosting options for a small SaaS?", "Neon Launch is $19/month; Supabase Pro is $25/month.");
        store.setMessageMemory("a-crm", "crm sales pipeline hubspot pipedrive", List.of("HubSpot Sales Hub", "Pipedrive"));
        store.replaceClaims("u", "c-crm", "a-crm", "2026-11-09T09:00:09.000Z", LocalDate.of(2026, 11, 9), List.of(
                new NewClaim("HubSpot Sales Hub", "Professional price", "$90/seat/month", "HubSpot Sales Hub Professional costs $90 per seat per month.", null),
                new NewClaim("Pipedrive", "Growth price", "$39/seat/month", "Pipedrive Growth costs $39 per seat per month.", null)));
        recall = new MemoryRecall(store);
    }

    private MemoryContext ask(String message) {
        TurnIntent intent = IntentAnalyzer.analyze(message);
        List<TimeRef> refs = TemporalResolver.resolve(message, NOW, "en-IN", intent.needsMemory());
        return recall.recall(new MemoryRecall.Request("u", intent, refs, TopicTerms.extract(message, refs), NOW, "current",
                List.of("q-now"), null));
    }

    @Test
    void onMondayFindsTheCrmShortlistWithItsFacts() {
        MemoryContext ctx = ask("On Monday we shortlisted 5 CRM tools for a 20-person sales team. Has any of them changed pricing or shipped something big since?");
        assertTrue(ctx.found());
        assertEquals("q-crm", ctx.turns().get(0).turn().userMessageId());
        assertEquals(2, ctx.turns().get(0).claims().size());
        assertEquals(LocalDate.of(2026, 11, 9), ctx.changeSince());
        assertEquals("found", ctx.summary().status());
        String prompt = MemoryRenderer.render(ctx);
        assertTrue(prompt.contains("Monday 9 Nov 2026"), prompt);
        assertTrue(prompt.contains("HubSpot Sales Hub · Professional price: $90/seat/month"), prompt);
        assertTrue(prompt.contains("since=\"2026-11-09\""), prompt);
    }

    @Test
    void lastTuesdayPicksTheTuesdayWhereTheTopicAppears() {
        MemoryContext ctx = ask("Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?");
        assertEquals(List.of("q-pg"), ctx.turns().stream().map(t -> t.turn().userMessageId()).toList());
        assertEquals(LocalDate.of(2026, 11, 10), ctx.resolutions().get(0).chosen().start());
    }

    @Test
    void aDateWithNoTopicListsEverythingAskedThatDay() {
        MemoryContext ctx = ask("What did I ask you on 3 November? Is your answer still correct today?");
        assertEquals(List.of("q-node"), ctx.turns().stream().map(t -> t.turn().userMessageId()).toList());
        assertEquals("Tue 3 Nov 2026, 11:00", ctx.summary().hits().get(0).label());
    }

    @Test
    void aMissingDayIsReportedHonestlyWithNearbyDays() {
        MemoryContext ctx = ask("What did I ask you on 5 November?");
        assertFalse(ctx.found());
        assertEquals("none", ctx.summary().status());
        assertTrue(ctx.nearby().containsKey(LocalDate.of(2026, 11, 3)));
        String prompt = MemoryRenderer.render(ctx);
        assertTrue(prompt.contains("No conversation with this user was found for Thursday 5 Nov 2026"), prompt);
        assertTrue(prompt.contains("Do not guess what was said"), prompt);
    }

    @Test
    void topicOnTheWrongDayIsAMismatchNotAGuess() {
        MemoryContext ctx = ask("Yesterday I asked you about Postgres hosting, any price changes since?");
        assertEquals("mismatch", ctx.summary().status());
        assertEquals("q-pg", ctx.turns().get(0).turn().userMessageId());
        assertNotNull(ctx.mismatch());
        assertTrue(ctx.mismatch().contains("Friday 13 Nov 2026"), ctx.mismatch());
    }

    @Test
    void unrelatedQuestionsGetOnlyTheConversationIndex() {
        MemoryContext ctx = ask("Explain how TCP congestion control works");
        assertFalse(ctx.needed());
        assertTrue(ctx.turns().isEmpty());
        assertEquals(3, ctx.index().size());
    }

    @Test
    void sameTopicWithoutAnExplicitReferenceIsOfferedAsRelated() {
        MemoryContext ctx = ask("Is Pipedrive good for a small sales team?");
        assertFalse(ctx.needed());
        assertEquals("q-crm", ctx.related().get(0).turn().userMessageId());
    }

    @Test
    void recheckButtonLoadsThatExactAnswer() {
        TurnIntent intent = new TurnIntent(true, true, true, true, false);
        MemoryContext ctx = recall.recall(new MemoryRecall.Request("u", intent, List.of(), List.of(), NOW, "current", List.of(), "a-pg"));
        assertTrue(ctx.recheck());
        assertEquals("q-pg", ctx.turns().get(0).turn().userMessageId());
        assertEquals(LocalDate.of(2026, 11, 10), ctx.changeSince());
    }
}
