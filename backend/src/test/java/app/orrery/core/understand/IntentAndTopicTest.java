package app.orrery.core.understand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentAndTopicTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 14, 21, 0, 0, 0, ZoneId.of("Asia/Kolkata"));

    @Test
    void theThreeBriefExamplesNeedMemoryAndAVerification() {
        for (String q : List.of(
                "On Monday we shortlisted 5 CRM tools for a 20-person sales team. Has any of them changed pricing or shipped something big since?",
                "What did I ask you on 3 November? Is your answer still correct today?",
                "Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?")) {
            TurnIntent intent = IntentAnalyzer.analyze(q);
            assertTrue(intent.recall(), "recall: " + q);
            assertTrue(intent.verify(), "verify: " + q);
        }
        assertTrue(IntentAnalyzer.analyze("Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?").sinceThen());
    }

    @Test
    void freshQuestionsAreNotRecall() {
        TurnIntent intent = IntentAnalyzer.analyze("What's the latest stable version of Kubernetes?");
        assertFalse(intent.recall());
        assertTrue(intent.freshness());
        TurnIntent timeless = IntentAnalyzer.analyze("Explain how TCP congestion control works");
        assertFalse(timeless.freshness());
        assertFalse(timeless.needsMemory());
    }

    @Test
    void greetingsAreSmallTalk() {
        assertTrue(IntentAnalyzer.analyze("hey!").smallTalk());
        assertTrue(IntentAnalyzer.analyze("Thanks so much 🙏").smallTalk());
        assertFalse(IntentAnalyzer.analyze("hey, what's new with Postgres 18?").smallTalk());
    }

    @Test
    void acknowledgementsAreSmallTalkButRequestsAreNot() {
        for (String ack : List.of("Thanks, that was helpful!", "great, thanks!", "ok thank you so much", "Perfect, that helps.",
                "Got it, thanks again", "That’s very useful", "hello there", "much appreciated 🙏", "That's exactly what I needed, thanks")) {
            assertTrue(IntentAnalyzer.analyze(ack).smallTalk(), ack);
        }
        for (String request : List.of("thanks, and what about Supabase?", "thanks! is Neon still $19?", "great, now compare HubSpot",
                "ok so what changed since Monday?", "this is great but is it still true today?")) {
            assertFalse(IntentAnalyzer.analyze(request).smallTalk(), request);
        }
    }

    @Test
    void topicTermsDropTimePhrasesAndFiller() {
        String q = "Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?";
        List<TimeRef> refs = TemporalResolver.resolve(q, NOW, "en-IN", true);
        assertEquals(List.of("postgres", "hosting", "pricing"), TopicTerms.extract(q, refs));

        String q2 = "What did I ask you on 3 November? Is your answer still correct today?";
        assertEquals(List.of(), TopicTerms.extract(q2, TemporalResolver.resolve(q2, NOW, "en-IN", true)));
    }
}
