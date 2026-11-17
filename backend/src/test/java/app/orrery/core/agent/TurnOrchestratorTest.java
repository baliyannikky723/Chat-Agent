package app.orrery.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.AgentRuntime;
import app.orrery.core.agent.TurnOrchestrator.TurnInput;
import app.orrery.core.agent.TurnOrchestrator.TurnOutcome;
import app.orrery.core.config.ProviderDetection;
import app.orrery.core.config.Settings;
import app.orrery.core.events.AgentEvent;
import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.llm.ModelRouter;
import app.orrery.core.llm.OpenAiCompatibleClient;
import app.orrery.core.llm.OpenAiCompatibleClient.Flavor;
import app.orrery.core.search.PageReader;
import app.orrery.core.search.SearchService;
import app.orrery.core.search.TavilyProvider;
import app.orrery.core.store.Store.MessageRow;
import app.orrery.core.time.AgentClock;
import app.orrery.core.util.CancelToken;
import app.orrery.infra.SqliteStore;
import app.orrery.support.CollectingSink;
import app.orrery.support.FakeHttp;
import app.orrery.support.FakeHttp.Recorded;
import app.orrery.support.FakeHttp.Reply;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A whole turn over real sockets: a fake Groq-compatible model that streams tool calls,
 * a fake Tavily, and SQLite. Then a second turn, four days later, that has to remember it.
 */
class TurnOrchestratorTest {

    /** Saturday 14 Nov 2026, 21:00 in India. */
    private static final Instant SATURDAY = Instant.parse("2026-11-14T15:30:00Z");
    /** Tuesday 10 Nov 2026, 18:40 in India. */
    private static final Instant TUESDAY = Instant.parse("2026-11-10T13:10:00Z");

    private FakeHttp llm;
    private FakeHttp tavily;
    private SqliteStore store;
    private AgentRuntime runtime;
    private volatile Function<JNode, Reply> mainModel;

    static List<String> textChunks(String text) {
        List<String> out = new ArrayList<>();
        int cut = Math.max(1, text.length() / 2);
        out.add(Json.write(Json.obj("choices", List.of(Json.obj("index", 0, "delta", Json.obj("content", text.substring(0, cut)))))));
        out.add(Json.write(Json.obj("choices", List.of(Json.obj("index", 0, "delta", Json.obj("content", text.substring(cut)), "finish_reason", "stop")))));
        out.add(Json.write(Json.obj("x_groq", Json.obj("usage", Json.obj("prompt_tokens", 900, "completion_tokens", 40)), "choices", List.of())));
        out.add("[DONE]");
        return out;
    }

    static List<String> toolCalls(String preamble, String... argumentsJson) {
        List<String> out = new ArrayList<>();
        if (preamble != null) out.add(Json.write(Json.obj("choices", List.of(Json.obj("index", 0, "delta", Json.obj("content", preamble))))));
        for (int i = 0; i < argumentsJson.length; i++) {
            Map<String, Object> call = Json.obj("index", i, "id", "call_" + i, "type", "function",
                    "function", Json.obj("name", "web_search", "arguments", argumentsJson[i]));
            out.add(Json.write(Json.obj("choices", List.of(Json.obj("index", 0, "delta", Json.obj("tool_calls", List.of(call)))))));
        }
        out.add(Json.write(Json.obj("choices", List.of(Json.obj("index", 0, "delta", Json.obj(), "finish_reason", "tool_calls")))));
        out.add(Json.write(Json.obj("x_groq", Json.obj("usage", Json.obj("prompt_tokens", 900, "completion_tokens", 20)), "choices", List.of())));
        out.add("[DONE]");
        return out;
    }

    private static boolean hasToolResults(JNode body) {
        return body.get("messages").items().stream().anyMatch(m -> "tool".equals(m.get("role").text()));
    }

    @BeforeEach
    void setUp() {
        HttpClient http = HttpClient.newHttpClient();
        llm = new FakeHttp();
        tavily = new FakeHttp();
        llm.route("/chat/completions", (Recorded req) -> {
            JNode body = Json.tree(req.body());
            String system = body.get("messages").at(0).get("content").text();
            if (system.startsWith("You write short")) return Reply.sse(textChunks("Managed Postgres hosting"));
            if (system.startsWith("You extract structured facts")) {
                return Reply.sse(textChunks("""
                        {"entities":["Neon","Supabase"],"keywords":["postgres","postgresql","managed database","hosting"],
                         "claims":[{"entity":"Neon","attribute":"Launch plan price","value":"$19/month","statement":"Neon's Launch plan costs $19 per month.","source":1},
                                   {"entity":"Supabase","attribute":"Pro plan price","value":"$25/month","statement":"Supabase Pro costs $25 per month.","source":2}]}"""));
            }
            return mainModel.apply(body);
        });
        // Deep searches get full page text back, so no test ever reaches the real internet.
        tavily.route("/search", req -> {
            boolean deep = req.body().contains("\"advanced\"");
            String page = deep ? "Pricing page. ".repeat(80) : null;
            return Reply.json(Json.write(Json.obj("results", List.of(
                    Json.obj("title", "Neon pricing", "url", "https://neon.com/pricing", "content", "Launch: $19/month. Scale: $69/month.",
                            "score", 0.9, "published_date", "2026-11-12", "raw_content", page),
                    Json.obj("title", "Supabase pricing", "url", "https://supabase.com/pricing", "content", "Pro: $25/month per project.",
                            "score", 0.8, "raw_content", page)))));
        });

        store = app.orrery.support.TestDb.fresh();
        store.ensureUser("u", "Asia/Kolkata", "en-IN");
        Settings settings = ProviderDetection.fromEnv(Map.of("GROQ_API_KEY", "gsk-test-1234567890", "TAVILY_API_KEY", "tvly-test-1234567890"));
        ModelRouter router = new ModelRouter(new OpenAiCompatibleClient(http, "groq", Flavor.GROQ, llm.baseUrl(), "gsk-test"),
                "openai/gpt-oss-120b", "openai/gpt-oss-20b", List.of(), "low");
        SearchService search = new SearchService(List.of(new TavilyProvider(http, "tvly-test", tavily.baseUrl())), new PageReader(true), 6);
        runtime = new AgentRuntime(settings, store, AgentClock.fixed(SATURDAY), router, search);
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        llm.close();
        tavily.close();
    }

    private void searchThenAnswer(String answer) {
        mainModel = body -> hasToolResults(body)
                ? Reply.sse(textChunks(answer))
                : Reply.sse(toolCalls(null, "{\"query\":\"Neon pricing 2026\"}", "{\"query\":\"Supabase pricing 2026\",\"depth\":\"deep\"}"));
    }

    @Test
    void aTurnSearchesInParallelCitesStreamsAndIsSaved() throws Exception {
        searchThenAnswer("Neon's Launch plan is $19/month [1], and Supabase Pro is $25/month [2].");
        CollectingSink sink = new CollectingSink();
        TurnOutcome outcome = runtime.orchestrator().run(new TurnInput("u", null, "What do Neon and Supabase cost?", null, null), sink, CancelToken.none());

        assertTrue(outcome.ok());
        List<String> types = sink.types();
        assertEquals("turn.started", types.get(0));
        assertEquals("turn.completed", types.get(types.size() - 1));
        assertEquals(2, sink.all(AgentEvent.SearchStarted.class).size(), "two searches from one model step");
        assertTrue(types.contains("conversation.titled"));
        assertEquals("Neon's Launch plan is $19/month [1], and Supabase Pro is $25/month [2].", sink.streamedText());

        AgentEvent.TurnCompleted done = sink.first(AgentEvent.TurnCompleted.class);
        assertEquals(List.of(1, 2), done.cited());
        assertEquals(2, done.sources().size());
        assertEquals(2, done.metrics().searches());
        assertEquals(Long.valueOf(1800), done.metrics().inputTokens(), "usage summed across both model calls");
        assertNotNull(done.metrics().ttftMs());

        // Persisted: question, answer, cited sources, a title.
        List<MessageRow> messages = store.conversationMessages(outcome.conversationId(), 10);
        assertEquals(List.of("user", "assistant"), messages.stream().map(MessageRow::role).toList());
        assertEquals("2026-11-14", messages.get(0).localDate().toString());
        assertTrue(store.sourcesFor(List.of(outcome.assistantMessageId())).get(outcome.assistantMessageId()).get(0).cited());
        assertEquals("Managed Postgres hosting", store.listConversations("u", 5).get(0).title());

        // The main request: one tool, required on the first step (pricing = fresh info), parallel results returned.
        JNode first = Json.tree(llm.requests("/chat/completions").stream()
                .filter(r -> r.body().contains("You are Orrery")).findFirst().orElseThrow().body());
        assertEquals("web_search", first.get("tools").at(0).path("function", "name").text());
        assertEquals(1, first.get("tools").size());
        assertEquals("required", first.get("tool_choice").text());
        assertTrue(first.get("messages").at(0).get("content").text().contains("Saturday 14 November 2026, 21:00"));
        JNode deep = Json.tree(tavily.requests("/search").stream().filter(r -> r.body().contains("Supabase")).findFirst().orElseThrow().body());
        assertEquals("advanced", deep.get("search_depth").text());

        // Background extraction writes the claim ledger.
        assertTrue(runtime.jobs().awaitIdle(10_000));
        var claims = store.claimsFor(List.of(outcome.assistantMessageId())).get(outcome.assistantMessageId());
        assertEquals(2, claims.size());
        assertEquals("https://neon.com/pricing", claims.get(0).sourceUrl());
    }

    @Test
    void lastTuesdayIsRecalledWithItsFactsAndRecheckedSinceThatDay() throws Exception {
        searchThenAnswer("Neon's Launch plan is $19/month [1], and Supabase Pro is $25/month [2].");
        TurnOutcome past = runtime.orchestrator().run(new TurnInput("u", null,
                "What are the best managed Postgres hosting options for a small SaaS?", null, TUESDAY), new CollectingSink(), CancelToken.none());
        assertTrue(runtime.jobs().awaitIdle(10_000));
        assertEquals("2026-11-10", store.conversationMessages(past.conversationId(), 5).get(0).localDate().toString());

        mainModel = body -> hasToolResults(body)
                ? Reply.sse(textChunks("| Item | Then (Tue 10 Nov) | Now | Status |\n|---|---|---|---|\n| Neon Launch | $19 | $19 [1] | Unchanged |"))
                : Reply.sse(toolCalls(null, "{\"query\":\"Neon pricing\",\"since\":\"2026-11-10\"}"));
        CollectingSink sink = new CollectingSink();
        TurnOutcome now = runtime.orchestrator().run(new TurnInput("u", null,
                "Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?", null, null), sink, CancelToken.none());
        assertTrue(now.ok());

        AgentEvent.MemoryRecalled recalled = sink.first(AgentEvent.MemoryRecalled.class);
        assertEquals("found", recalled.recall().status());
        assertEquals("2026-11-10", recalled.recall().hits().get(0).date());

        Recorded main = llm.requests("/chat/completions").stream()
                .filter(r -> r.body().contains("You are Orrery") && r.body().contains("Last Tuesday I asked you")).findFirst().orElseThrow();
        String system = Json.tree(main.body()).get("messages").at(0).get("content").text();
        assertTrue(system.contains("Neon · Launch plan price: $19/month"), system);
        assertTrue(system.contains("Change window: since Tuesday 10 Nov 2026"), system);
        assertEquals("required", Json.tree(main.body()).get("tool_choice").text());

        JNode searched = Json.tree(tavily.requests("/search").get(tavily.requests("/search").size() - 1).body());
        assertEquals("2026-11-10", searched.get("start_date").text(), "the model's since= reaches the search API");
    }

    @Test
    void aPreambleBeforeSearchingIsWithdrawn() {
        mainModel = body -> hasToolResults(body)
                ? Reply.sse(textChunks("Neon costs $19/month [1]."))
                : Reply.sse(toolCalls("Let me look that up. ", "{\"query\":\"Neon pricing\"}"));
        CollectingSink sink = new CollectingSink();
        runtime.orchestrator().run(new TurnInput("u", null, "How much is Neon?", null, null), sink, CancelToken.none());
        assertTrue(sink.types().contains("text.reset"));
        assertEquals("Neon costs $19/month [1].", sink.streamedText());
        assertEquals("Neon costs $19/month [1].", sink.first(AgentEvent.TurnCompleted.class).text());
    }

    @Test
    void smallTalkIsAnsweredWithoutToolsOrSearch() {
        mainModel = body -> Reply.sse(textChunks("Hi! What can I look up for you?"));
        CollectingSink sink = new CollectingSink();
        runtime.orchestrator().run(new TurnInput("u", null, "hey!", null, null), sink, CancelToken.none());
        assertEquals("Hi! What can I look up for you?", sink.first(AgentEvent.TurnCompleted.class).text());
        assertTrue(tavily.requests().isEmpty());
        Recorded main = llm.requests("/chat/completions").stream().filter(r -> r.body().contains("You are Orrery")).findFirst().orElseThrow();
        assertTrue(Json.tree(main.body()).get("tools").isMissing());
        assertFalse(sink.types().contains("memory.recalled"));
    }

    @Test
    void aRejectedKeyFailsWithAFixTheUserCanApply() {
        mainModel = body -> Reply.status(401, "{\"error\":{\"message\":\"Invalid API Key\"}}");
        CollectingSink sink = new CollectingSink();
        TurnOutcome outcome = runtime.orchestrator().run(new TurnInput("u", null, "What's new in Postgres 18?", null, null), sink, CancelToken.none());
        assertFalse(outcome.ok());
        AgentEvent.TurnFailed failed = sink.first(AgentEvent.TurnFailed.class);
        assertEquals("llm_auth", failed.error().code());
        assertTrue(failed.error().hint().contains("GROQ_API_KEY"), failed.error().hint());
        assertEquals(1, store.conversationMessages(outcome.conversationId(), 5).size(), "the question is kept, no fake answer");
    }

    @Test
    void aCancelledTurnEndsQuietly() {
        searchThenAnswer("unused");
        CancelToken cancel = new CancelToken();
        cancel.cancel("stopped by user");
        CollectingSink sink = new CollectingSink();
        TurnOutcome outcome = runtime.orchestrator().run(new TurnInput("u", null, "What does Neon cost?", null, null), sink, cancel);
        assertFalse(outcome.ok());
        assertFalse(sink.types().contains("turn.failed"));
    }

    @Test
    void conversationContextCarriesAcrossTurnsWithoutStaleCitations() {
        searchThenAnswer("Neon's Launch plan is $19/month [1], and Supabase Pro is $25/month [2].");
        TurnOutcome first = runtime.orchestrator().run(new TurnInput("u", null, "What do Neon and Supabase cost?", null, null),
                new CollectingSink(), CancelToken.none());
        mainModel = body -> Reply.sse(textChunks("Supabase is the cheaper pick for one project."));
        runtime.orchestrator().run(new TurnInput("u", first.conversationId(), "Which one is cheaper for a single project?", null, null),
                new CollectingSink(), CancelToken.none());
        Recorded followUp = llm.requests("/chat/completions").stream()
                .filter(r -> r.body().contains("You are Orrery") && r.body().contains("Which one is cheaper")).findFirst().orElseThrow();
        JNode messages = Json.tree(followUp.body()).get("messages");
        String previousAnswer = messages.at(2).get("content").text();
        assertEquals("assistant", messages.at(2).get("role").text());
        assertTrue(previousAnswer.contains("(neon.com)") && !previousAnswer.contains("[1]"), previousAnswer);
    }
}
