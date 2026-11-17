package app.orrery.core;

import app.orrery.core.agent.DemoSeeder;
import app.orrery.core.agent.MemoryExtractor;
import app.orrery.core.agent.TurnOrchestrator;
import app.orrery.core.config.Settings;
import app.orrery.core.config.Settings.LlmSettings;
import app.orrery.core.config.Settings.SearchKey;
import app.orrery.core.jobs.JobRunner;
import app.orrery.core.llm.AnthropicClient;
import app.orrery.core.llm.LlmClient;
import app.orrery.core.llm.ModelRouter;
import app.orrery.core.llm.OpenAiCompatibleClient;
import app.orrery.core.llm.OpenAiCompatibleClient.Flavor;
import app.orrery.core.memory.MemoryRecall;
import app.orrery.core.search.BraveProvider;
import app.orrery.core.search.PageReader;
import app.orrery.core.search.SearchProvider;
import app.orrery.core.search.SearchService;
import app.orrery.core.search.SerperProvider;
import app.orrery.core.search.TavilyProvider;
import app.orrery.core.store.Store;
import app.orrery.core.time.AgentClock;
import app.orrery.core.util.RateLimiter;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the framework-free core from {@link Settings}. Spring creates exactly one of these;
 * tests create their own with mock endpoints. When a key is missing the runtime still
 * starts — {@link #ready()} is false and the UI explains what to set.
 */
public final class AgentRuntime implements AutoCloseable {

    private final Settings settings;
    private final Store store;
    private final AgentClock clock;
    private final ModelRouter router;
    private final SearchService search;
    private final JobRunner jobs;
    private final TurnOrchestrator orchestrator;
    private final DemoSeeder seeder;
    private final RateLimiter turnLimiter = new RateLimiter(8, 5_000);
    private final ExecutorService turnPool = Executors.newVirtualThreadPerTaskExecutor();

    public AgentRuntime(Settings settings, Store store, HttpClient http, AgentClock clock) {
        this(settings, store, clock, settings.llm() == null ? null : router(settings.llm(), http),
                new SearchService(searchProviders(settings.search(), http), new PageReader(), settings.searchMaxResults()));
    }

    /** For tests: inject the model router and search service directly. */
    public AgentRuntime(Settings settings, Store store, AgentClock clock, ModelRouter router, SearchService search) {
        this.settings = settings;
        this.store = store;
        this.clock = clock;
        this.router = router;
        this.search = search;
        this.jobs = new JobRunner(store);
        if (router != null && search.configured()) {
            MemoryRecall recall = new MemoryRecall(store);
            this.orchestrator = new TurnOrchestrator(store, router, search, recall, clock, jobs, settings);
            MemoryExtractor extractor = new MemoryExtractor(store, router);
            jobs.register(TurnOrchestrator.EXTRACT_JOB, payload ->
                    extractor.extract(payload.get("userId").text(), payload.get("messageId").text()));
            this.seeder = new DemoSeeder(orchestrator, store, clock, jobs);
        } else {
            this.orchestrator = null;
            this.seeder = null;
        }
    }

    static ModelRouter router(LlmSettings llm, HttpClient http) {
        LlmClient client = switch (llm.provider()) {
            case ANTHROPIC -> new AnthropicClient(http, llm.baseUrl(), llm.apiKey());
            case OPENAI -> new OpenAiCompatibleClient(http, "openai", Flavor.OPENAI, llm.baseUrl(), llm.apiKey());
            case GROQ -> new OpenAiCompatibleClient(http, "groq", Flavor.GROQ, llm.baseUrl(), llm.apiKey());
            case GOOGLE -> new OpenAiCompatibleClient(http, "google", Flavor.GOOGLE, llm.baseUrl(), llm.apiKey());
            case OPENROUTER -> new OpenAiCompatibleClient(http, "openrouter", Flavor.OPENROUTER, llm.baseUrl(), llm.apiKey());
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleClient(http, "openai-compatible", Flavor.GENERIC, llm.baseUrl(), llm.apiKey());
        };
        return new ModelRouter(client, llm.model(), llm.fastModel(), llm.fallbacks(), llm.reasoningEffort());
    }

    static List<SearchProvider> searchProviders(List<SearchKey> keys, HttpClient http) {
        List<SearchProvider> out = new ArrayList<>();
        for (SearchKey k : keys) {
            out.add(switch (k.provider()) {
                case TAVILY -> new TavilyProvider(http, k.apiKey());
                case BRAVE -> new BraveProvider(http, k.apiKey());
                case SERPER -> new SerperProvider(http, k.apiKey());
            });
        }
        return out;
    }

    public void start() {
        jobs.start();
    }

    public boolean ready() {
        return orchestrator != null;
    }

    public Settings settings() {
        return settings;
    }

    public Store store() {
        return store;
    }

    public AgentClock clock() {
        return clock;
    }

    public ModelRouter router() {
        return router;
    }

    public SearchService search() {
        return search;
    }

    public JobRunner jobs() {
        return jobs;
    }

    public TurnOrchestrator orchestrator() {
        return orchestrator;
    }

    public DemoSeeder seeder() {
        return seeder;
    }

    public RateLimiter turnLimiter() {
        return turnLimiter;
    }

    public ExecutorService turnPool() {
        return turnPool;
    }

    @Override
    public void close() {
        jobs.close();
        turnPool.shutdown();
    }
}
