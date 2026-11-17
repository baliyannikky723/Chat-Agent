package app.orrery.core.agent;

import app.orrery.core.config.Settings;
import app.orrery.core.events.AgentEvent;
import app.orrery.core.events.AgentEvent.SourceRef;
import app.orrery.core.events.AgentEvent.TurnError;
import app.orrery.core.events.AgentEvent.TurnMetrics;
import app.orrery.core.events.EventSink;
import app.orrery.core.jobs.JobRunner;
import app.orrery.core.json.Json;
import app.orrery.core.llm.LlmException;
import app.orrery.core.llm.LlmModels.AssistantMessage;
import app.orrery.core.llm.LlmModels.ChatMessage;
import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.LlmResult;
import app.orrery.core.llm.LlmModels.ToolCall;
import app.orrery.core.llm.LlmModels.ToolChoice;
import app.orrery.core.llm.LlmModels.ToolResultMessage;
import app.orrery.core.llm.LlmModels.ToolSpec;
import app.orrery.core.llm.LlmModels.Usage;
import app.orrery.core.llm.LlmModels.UserMessage;
import app.orrery.core.llm.ModelRouter;
import app.orrery.core.memory.MemoryContext;
import app.orrery.core.memory.MemoryRecall;
import app.orrery.core.search.SearchService;
import app.orrery.core.store.Store;
import app.orrery.core.store.Store.ConversationRow;
import app.orrery.core.store.Store.MessageRow;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.store.Store.UserRow;
import app.orrery.core.time.AgentClock;
import app.orrery.core.time.Times;
import app.orrery.core.understand.IntentAnalyzer;
import app.orrery.core.understand.TemporalResolver;
import app.orrery.core.understand.TimeRef;
import app.orrery.core.understand.TopicTerms;
import app.orrery.core.understand.TurnIntent;
import app.orrery.core.util.CancelToken;
import app.orrery.core.util.Citations;
import app.orrery.core.util.Ids;
import app.orrery.core.util.Text;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one turn, start to finish:
 *
 * <pre>
 *  persist question → understand (dates, intent) → recall memory → [title, in parallel]
 *    → model ⇄ web_search (parallel searches, ≤ 5 steps) → stream answer
 *    → persist answer + sources → queue memory extraction
 * </pre>
 *
 * Memory is context, not a tool: it is resolved deterministically before the model is
 * called, so the model keeps exactly one tool (web_search) and a turn needs no extra
 * round-trip just to "remember".
 */
public final class TurnOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TurnOrchestrator.class);
    private static final int MAX_STEPS = 5;
    private static final int MAX_OUTPUT_TOKENS = 4096;
    private static final int WINDOW_CHARS = 24_000;

    public static final String EXTRACT_JOB = "extract_memory";

    private final Store store;
    private final ModelRouter router;
    private final SearchService search;
    private final MemoryRecall recall;
    private final AgentClock clock;
    private final JobRunner jobs;
    private final Settings settings;
    private final ConversationLocks locks = new ConversationLocks();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public TurnOrchestrator(Store store, ModelRouter router, SearchService search, MemoryRecall recall, AgentClock clock,
                            JobRunner jobs, Settings settings) {
        this.store = store;
        this.router = router;
        this.search = search;
        this.recall = recall;
        this.clock = clock;
        this.jobs = jobs;
        this.settings = settings;
    }

    /**
     * @param nowOverride  fixes "now" for this turn (demo seeding); null uses the clock
     * @param recheckMessageId the assistant answer the user asked to re-check, or null
     */
    public record TurnInput(String userId, String conversationId, String message, String recheckMessageId, Instant nowOverride) {}

    public record TurnOutcome(String conversationId, String assistantMessageId, String text, boolean ok) {}

    public TurnOutcome run(TurnInput in, EventSink sink, CancelToken cancel) {
        long t0 = System.currentTimeMillis();
        UserRow user = store.user(in.userId());
        if (user == null) user = store.ensureUser(in.userId(), "UTC", "en-GB");
        Instant now = in.nowOverride() != null ? in.nowOverride() : clock.now(user.clockOffsetMillis());
        ZonedDateTime zNow = TemporalResolver.zonedNow(now, user.timezone());
        LocalDate today = zNow.toLocalDate();

        ConversationRow conversation = in.conversationId() == null ? null : store.conversation(user.id(), in.conversationId());
        boolean isNew = conversation == null;
        if (isNew) conversation = store.createConversation(Ids.uuidv7(now.toEpochMilli()), user.id(), Times.iso(now));
        String conversationId = conversation.id();
        if (!locks.tryLock(conversationId)) {
            sink.emit(new AgentEvent.TurnFailed(new TurnError("busy", "A reply in this conversation is still being written.",
                    "Wait for it to finish, or press Stop.", true), null));
            return new TurnOutcome(conversationId, null, null, false);
        }

        StringBuilder visible = new StringBuilder();
        String userMessageId = Ids.uuidv7(now.toEpochMilli());
        try {
            // Window first: it must not include the question we are about to save.
            List<MessageRow> history = store.conversationMessages(conversationId, 40);
            Map<String, List<SourceRow>> historySources = store.sourcesFor(
                    history.stream().filter(m -> "assistant".equals(m.role())).map(MessageRow::id).toList());
            List<ChatMessage> messages = new ArrayList<>(ConversationWindow.build(history, historySources, WINDOW_CHARS));

            store.insertMessage(new MessageRow(userMessageId, conversationId, user.id(), userMessageId, "user", in.message(),
                    "complete", Times.iso(now), today, user.timezone(), "", List.of(), null, null, null));
            store.touchConversation(conversationId, Times.iso(now));
            sink.emit(new AgentEvent.TurnStarted(userMessageId, conversationId, userMessageId, zNow.toOffsetDateTime().toString(), isNew));

            // 1. Understand — deterministic, microseconds.
            boolean recheck = in.recheckMessageId() != null;
            TurnIntent intent = recheck ? new TurnIntent(true, true, true, true, false) : IntentAnalyzer.analyze(in.message());
            List<TimeRef> refs = TemporalResolver.resolve(in.message(), zNow, user.locale(), intent.needsMemory());
            List<String> terms = TopicTerms.extract(in.message(), refs);

            // 2. Recall — before the model is called.
            MemoryContext memory = null;
            if (!intent.smallTalk()) {
                if (intent.needsMemory()) sink.emit(new AgentEvent.Status("recalling", "Checking past conversations"));
                List<String> exclude = new ArrayList<>();
                for (MessageRow m : history) if ("user".equals(m.role())) exclude.add(m.id());
                exclude.add(userMessageId);
                memory = recall.recall(new MemoryRecall.Request(user.id(), intent, refs, terms, zNow, conversationId, exclude,
                        in.recheckMessageId()));
                if (memory.summary() != null) sink.emit(new AgentEvent.MemoryRecalled(memory.summary()));
            }

            // 3. Title in parallel with the answer.
            Future<String> titleFuture = isNew && !recheck
                    ? pool.submit(() -> TitleWriter.write(router, in.message(), cancel))
                    : null;

            // 4. Model ⇄ web_search loop.
            String system = PromptBuilder.system(zNow, intent, memory);
            UserMessage question = new UserMessage(in.message());
            if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof UserMessage prev) {
                messages.set(messages.size() - 1, new UserMessage(prev.content() + "\n\n" + in.message()));
            } else {
                messages.add(question);
            }

            boolean found = memory != null && memory.found();
            boolean mustSearch = !intent.smallTalk() && ((intent.verify() && found) || (intent.freshness() && !intent.recall()));
            int budget = searchBudget(intent, memory);
            SourceRegistry registry = new SourceRegistry();
            WebSearchTool tool = new WebSearchTool(search, registry, sink, budget, cancel, today);
            List<ToolSpec> tools = intent.smallTalk() ? List.of() : List.of(WebSearchTool.spec());

            Usage usage = Usage.NONE;
            String finalText = null;
            String model = router.mainModel();
            Long ttft = null;
            int steps = 0;
            sink.emit(new AgentEvent.Status("thinking", intent.smallTalk() ? "Replying" : "Thinking"));

            for (int step = 0; step < MAX_STEPS && finalText == null; step++) {
                cancel.throwIfCancelled();
                ToolChoice choice = tools.isEmpty() ? ToolChoice.AUTO
                        : step == 0 && mustSearch ? ToolChoice.REQUIRED
                        : step == MAX_STEPS - 1 || tool.exhausted() ? ToolChoice.NONE
                        : ToolChoice.AUTO;
                long[] firstToken = {0};
                StringBuilder stepText = new StringBuilder();
                LlmRequest request = new LlmRequest(null, system, List.copyOf(messages), tools, choice, MAX_OUTPUT_TOKENS,
                        router.reasoningEffort(), false);
                LlmResult result = router.streamMain(request, delta -> {
                    if (firstToken[0] == 0) firstToken[0] = System.currentTimeMillis();
                    stepText.append(delta);
                    visible.append(delta);
                    sink.emit(new AgentEvent.TextDelta(delta));
                }, cancel);
                steps++;
                usage = usage.plus(result.usage());
                model = result.model();

                if (result.toolCalls().isEmpty()) {
                    finalText = result.text();
                    if (firstToken[0] > 0) ttft = firstToken[0] - t0;
                    break;
                }

                // The model decided to search: anything it wrote first was a preamble.
                if (stepText.length() > 0) {
                    sink.emit(new AgentEvent.TextReset());
                    visible.setLength(0);
                }
                messages.add(new AssistantMessage(result.text(), result.toolCalls()));
                sink.emit(new AgentEvent.Status("searching", result.toolCalls().size() == 1 ? "Searching the web"
                        : "Running " + result.toolCalls().size() + " searches"));
                List<String> outputs = runTools(result.toolCalls(), tool);
                for (int i = 0; i < result.toolCalls().size(); i++) {
                    ToolCall call = result.toolCalls().get(i);
                    messages.add(new ToolResultMessage(call.id(), call.name(), outputs.get(i)));
                }
                sink.emit(new AgentEvent.Status("reading", "Reading " + registry.size() + (registry.size() == 1 ? " source" : " sources")));
            }

            if (finalText == null || finalText.isBlank()) {
                // The model ended without text (or kept searching): ask once more, tools off.
                long[] firstToken = {0};
                LlmRequest request = new LlmRequest(null, system, List.copyOf(messages), tools.isEmpty() ? List.of() : tools,
                        ToolChoice.NONE, MAX_OUTPUT_TOKENS, router.reasoningEffort(), false);
                LlmResult result = router.streamMain(request, delta -> {
                    if (firstToken[0] == 0) firstToken[0] = System.currentTimeMillis();
                    visible.append(delta);
                    sink.emit(new AgentEvent.TextDelta(delta));
                }, cancel);
                steps++;
                usage = usage.plus(result.usage());
                finalText = result.text();
                if (firstToken[0] > 0) ttft = firstToken[0] - t0;
            }
            if (finalText == null || finalText.isBlank()) {
                finalText = "I couldn't put an answer together this time. Please try again, or rephrase the question.";
                sink.emit(new AgentEvent.TextDelta(finalText));
            }
            finalText = finalText.strip();

            // 5. Persist the answer, its sources and searches.
            List<SourceRef> sources = registry.all();
            List<Integer> cited = Citations.cited(finalText, sources.size());
            long total = System.currentTimeMillis() - t0;
            TurnMetrics metrics = new TurnMetrics(ttft, total, tool.used(), sources.size(), steps, router.provider(), model,
                    usage.inputTokens(), usage.outputTokens());
            Instant answeredAt = now.plusMillis(Math.max(1, total));
            String assistantId = Ids.uuidv7(answeredAt.toEpochMilli());
            store.insertMessage(new MessageRow(assistantId, conversationId, user.id(), userMessageId, "assistant", finalText,
                    "complete", Times.iso(answeredAt), today, user.timezone(), "", List.of(), model, Json.write(metrics),
                    memory == null || memory.summary() == null ? null : Json.write(memory.summary())));
            Set<Integer> citedSet = new LinkedHashSet<>(cited);
            List<SourceRow> rows = sources.stream()
                    .map(s -> new SourceRow(s.n(), s.url(), s.title(), s.domain(), s.publishedAt(), s.snippet(), citedSet.contains(s.n())))
                    .toList();
            store.insertSources(assistantId, rows);
            store.insertSearches(assistantId, tool.logs());
            store.touchConversation(conversationId, Times.iso(answeredAt));

            if (titleFuture != null) {
                String title = awaitTitle(titleFuture, in.message());
                if (store.setTitleIfEmpty(conversationId, title)) sink.emit(new AgentEvent.ConversationTitled(conversationId, title));
            }
            // Cited answers always go to the claim ledger, however short; long uncited ones too (for recall keywords).
            if (!intent.smallTalk() && (!sources.isEmpty() || finalText.length() > 200)) {
                jobs.enqueue(EXTRACT_JOB, Json.obj("userId", user.id(), "messageId", assistantId));
            }
            sink.emit(new AgentEvent.TurnCompleted(assistantId, finalText, sources, cited, metrics));
            log.info("turn {} done in {} ms (ttft {} ms, {} searches, {} sources, {} steps, model {})", userMessageId, total, ttft,
                    tool.used(), sources.size(), steps, model);
            return new TurnOutcome(conversationId, assistantId, finalText, true);

        } catch (CancelToken.CancelledException e) {
            savePartial(user, conversationId, userMessageId, now, today, visible, "stopped");
            return new TurnOutcome(conversationId, null, visible.toString(), false);
        } catch (LlmException e) {
            if (e.kind() == LlmException.Kind.CANCELLED || cancel.isCancelled()) {
                savePartial(user, conversationId, userMessageId, now, today, visible, "stopped");
                return new TurnOutcome(conversationId, null, visible.toString(), false);
            }
            log.warn("turn failed: {} {}", e.kind(), e.getMessage());
            savePartial(user, conversationId, userMessageId, now, today, visible, "error");
            sink.emit(new AgentEvent.TurnFailed(toError(e), visible.length() == 0 ? null : visible.toString()));
            return new TurnOutcome(conversationId, null, null, false);
        } catch (RuntimeException e) {
            log.error("turn crashed", e);
            savePartial(user, conversationId, userMessageId, now, today, visible, "error");
            sink.emit(new AgentEvent.TurnFailed(new TurnError("internal", "Something went wrong while answering.",
                    "Try again. If it keeps happening, check the server log.", true), visible.length() == 0 ? null : visible.toString()));
            return new TurnOutcome(conversationId, null, null, false);
        } finally {
            locks.unlock(conversationId);
        }
    }

    /** Verify turns re-check every recalled item, so they get a bigger search budget. */
    int searchBudget(TurnIntent intent, MemoryContext memory) {
        int base = settings.searchMaxPerTurn();
        if (memory == null || !memory.found() || !intent.verify()) return base;
        Set<String> entities = new LinkedHashSet<>();
        for (MemoryContext.RecalledTurn t : memory.turns()) {
            entities.addAll(t.turn().entities());
            for (var c : t.claims()) entities.add(c.entity());
        }
        return Math.max(base, Math.min(14, entities.size() * 2 + 2));
    }

    private List<String> runTools(List<ToolCall> calls, WebSearchTool tool) {
        List<Future<String>> futures = new ArrayList<>();
        for (ToolCall call : calls) {
            futures.add(pool.submit(() -> WebSearchTool.NAME.equals(call.name())
                    ? tool.execute(call)
                    : "Unknown tool \"" + call.name() + "\". The only tool is web_search."));
        }
        List<String> out = new ArrayList<>();
        for (Future<String> f : futures) {
            try {
                out.add(f.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                f.cancel(true);
                out.add("Search failed: it took too long. Answer with what you have and say what you could not check.");
            }
        }
        return out;
    }

    private static String awaitTitle(Future<String> future, String message) {
        try {
            return future.get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return Text.firstWords(message, 7);
        }
    }

    private void savePartial(UserRow user, String conversationId, String turnId, Instant now, LocalDate today, StringBuilder visible, String status) {
        String text = visible.toString().strip();
        if (text.isEmpty()) return;
        try {
            Instant at = now.plusMillis(1);
            store.insertMessage(new MessageRow(Ids.uuidv7(at.toEpochMilli()), conversationId, user.id(), turnId, "assistant", text,
                    status, Times.iso(at), today, user.timezone(), "", List.of(), router.mainModel(), null, null));
        } catch (RuntimeException e) {
            log.warn("could not save partial answer: {}", e.toString());
        }
    }

    TurnError toError(LlmException e) {
        String keyEnv = settings.llm() == null ? "your model API key" : settings.llm().keyEnv();
        String provider = router.provider();
        return switch (e.kind()) {
            case AUTH -> new TurnError("llm_auth", provider + " rejected the API key, or the account has no credit.",
                    "Check " + keyEnv + " in .env, then restart with npm run dev.", false);
            case RATE_LIMITED -> new TurnError("llm_rate_limited", provider + " is rate limiting this key.",
                    "Wait a minute and try again, or set a different provider's key in .env.", true);
            case MODEL_NOT_FOUND -> new TurnError("llm_model_not_found", "Model \"" + router.mainModel() + "\" is not available on " + provider + ".",
                    "Set LLM_MODEL in .env to a model your account can use.", false);
            case UNAVAILABLE -> new TurnError("llm_unavailable", provider + " did not respond properly.", "Try again in a moment.", true);
            case BAD_REQUEST -> new TurnError("llm_bad_request", "The model provider rejected the request.", Text.truncate(e.getMessage(), 300), false);
            case CANCELLED -> new TurnError("internal", "Cancelled.", null, true);
        };
    }
}
