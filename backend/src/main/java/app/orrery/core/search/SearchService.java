package app.orrery.core.search;

import app.orrery.core.search.SearchException.Kind;
import app.orrery.core.search.SearchModels.SearchHit;
import app.orrery.core.search.SearchModels.SearchRequest;
import app.orrery.core.search.SearchModels.SearchResponse;
import app.orrery.core.util.CancelToken;
import app.orrery.core.util.Text;
import app.orrery.core.util.TtlCache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything between the agent's web_search tool and the provider APIs:
 *
 * <ul>
 *   <li><b>Cache</b> — 10 minutes for time-bound queries, 60 minutes otherwise.</li>
 *   <li><b>In-flight de-duplication</b> — identical queries running at the same time share one request.</li>
 *   <li><b>Failover</b> — a provider that is down, out of quota or rejects the key hands over to the next.</li>
 *   <li><b>Normalisation</b> — canonical URLs, duplicates removed, HTML stripped, sizes capped.</li>
 *   <li><b>Deep reads</b> — for exact figures, the top pages are fetched and read in parallel.</li>
 * </ul>
 */
public final class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int SNIPPET_CHARS = 600;
    private static final int CONTENT_CHARS = 3500;
    private static final int DEEP_READS = 3;

    private final List<SearchProvider> providers;
    private final PageReader reader;
    private final int maxResults;
    private final TtlCache<String, SearchResponse> cache = new TtlCache<>(500);
    private final Map<String, CompletableFuture<SearchResponse>> inflight = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public SearchService(List<SearchProvider> providers, PageReader reader, int maxResults) {
        this.providers = List.copyOf(providers);
        this.reader = reader;
        this.maxResults = maxResults;
    }

    public List<String> providerIds() {
        return providers.stream().map(SearchProvider::id).toList();
    }

    public boolean configured() {
        return !providers.isEmpty();
    }

    public SearchResponse search(SearchRequest raw, CancelToken cancel) throws SearchException {
        SearchRequest req = normalise(raw);
        if (req.query().isEmpty()) throw new SearchException(Kind.BAD_REQUEST, "search", "empty query");
        String key = cacheKey(req);
        SearchResponse cached = cache.get(key);
        if (cached != null) return new SearchResponse(cached.hits(), cached.provider(), true, 0);

        CompletableFuture<SearchResponse> mine = new CompletableFuture<>();
        CompletableFuture<SearchResponse> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) {
            try {
                SearchResponse shared = existing.get(30, TimeUnit.SECONDS);
                return new SearchResponse(shared.hits(), shared.provider(), true, 0);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SearchException se && se.kind() != Kind.CANCELLED) throw se;
                // the other caller was cancelled: run our own search below
            } catch (TimeoutException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new SearchException(Kind.TIMEOUT, "search", "shared request did not finish");
            }
            return runAndCache(req, key, cancel, null);
        }
        return runAndCache(req, key, cancel, mine);
    }

    private SearchResponse runAndCache(SearchRequest req, String key, CancelToken cancel, CompletableFuture<SearchResponse> mine)
            throws SearchException {
        long start = System.currentTimeMillis();
        try {
            SearchResponse response = runWithFailover(req, cancel, start);
            cache.put(key, response, req.timeBound() ? 10 * 60_000L : 60 * 60_000L);
            if (mine != null) mine.complete(response);
            return response;
        } catch (SearchException e) {
            if (mine != null) mine.completeExceptionally(e);
            throw e;
        } catch (RuntimeException e) {
            SearchException wrapped = new SearchException(Kind.UNAVAILABLE, "search", e.toString());
            if (mine != null) mine.completeExceptionally(wrapped);
            throw wrapped;
        } finally {
            if (mine != null) inflight.remove(key, mine);
        }
    }

    private SearchResponse runWithFailover(SearchRequest req, CancelToken cancel, long start) throws SearchException {
        if (providers.isEmpty()) throw new SearchException(Kind.AUTH, "search", "no search provider is configured");
        SearchException first = null;
        for (SearchProvider provider : providers) {
            try {
                List<SearchHit> hits = clean(provider.search(req, maxResults, cancel));
                if (req.deep()) hits = deepen(hits, cancel);
                return new SearchResponse(hits, provider.id(), false, System.currentTimeMillis() - start);
            } catch (SearchException e) {
                if (e.kind() == Kind.CANCELLED) throw e;
                log.warn("search provider {} failed: {}", provider.id(), e.describe());
                if (first == null) first = e;
            }
        }
        throw first;
    }

    static SearchRequest normalise(SearchRequest r) {
        String q = Text.collapse(r.query());
        if (q.length() > 300) q = q.substring(0, 300);
        String recency = r.recency() == null ? null : switch (r.recency().toLowerCase(Locale.ROOT)) {
            case "day", "week", "month", "year" -> r.recency().toLowerCase(Locale.ROOT);
            default -> null;
        };
        String topic = "news".equalsIgnoreCase(r.topic()) ? "news" : "general";
        return new SearchRequest(q, recency, r.since(), topic, r.deep());
    }

    private String cacheKey(SearchRequest r) {
        return String.join("|", String.join(",", providerIds()), r.query().toLowerCase(Locale.ROOT),
                String.valueOf(r.recency()), String.valueOf(r.since()), r.topic(), String.valueOf(r.deep()));
    }

    List<SearchHit> clean(List<SearchHit> raw) {
        Map<String, SearchHit> unique = new LinkedHashMap<>();
        for (SearchHit h : raw) {
            if (h.url() == null || !h.url().startsWith("http")) continue;
            String canonical = Urls.canonical(h.url());
            if (unique.containsKey(canonical)) continue;
            String title = HtmlText.stripTags(h.title());
            if (title.isBlank()) title = Urls.domain(h.url());
            String snippet = Text.truncate(HtmlText.stripTags(h.snippet()), SNIPPET_CHARS);
            String content = h.content() == null ? null : Text.truncate(h.content().strip(), CONTENT_CHARS);
            unique.put(canonical, new SearchHit(h.url().strip(), Text.truncate(title, 160), snippet, content, h.publishedAt(), h.score()));
            if (unique.size() >= maxResults) break;
        }
        return new ArrayList<>(unique.values());
    }

    /** Reads the top pages whose provider content is too thin to answer from. */
    private List<SearchHit> deepen(List<SearchHit> hits, CancelToken cancel) {
        List<Future<SearchHit>> futures = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            boolean thin = h.content() == null || h.content().length() < 800;
            if (i < DEEP_READS && thin) {
                futures.add(pool.submit(() -> {
                    try {
                        PageReader.Page page = reader.read(h.url(), cancel);
                        if (page.text().length() < 200) return h;
                        SearchHit read = h.withContent(Text.truncate(page.text(), CONTENT_CHARS));
                        return h.publishedAt() == null && page.publishedAt() != null
                                ? new SearchHit(read.url(), read.title(), read.snippet(), read.content(), page.publishedAt(), read.score())
                                : read;
                    } catch (Exception e) {
                        log.debug("page read failed for {}: {}", h.url(), e.getMessage());
                        return h;
                    }
                }));
            } else {
                futures.add(CompletableFuture.completedFuture(h));
            }
        }
        List<SearchHit> out = new ArrayList<>(hits.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get(10, TimeUnit.SECONDS));
            } catch (Exception e) {
                futures.get(i).cancel(true);
                out.add(hits.get(i));
            }
        }
        return out;
    }
}
