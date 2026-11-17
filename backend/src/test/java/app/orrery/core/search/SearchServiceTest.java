package app.orrery.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.search.SearchModels.SearchHit;
import app.orrery.core.search.SearchModels.SearchRequest;
import app.orrery.core.search.SearchModels.SearchResponse;
import app.orrery.core.util.CancelToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

    /** A provider whose behaviour each test scripts. */
    static final class Scripted implements SearchProvider {
        final String id;
        final AtomicInteger calls = new AtomicInteger();
        volatile SearchException failWith;
        volatile List<SearchHit> hits = List.of();
        volatile CountDownLatch gate;

        Scripted(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public List<SearchHit> search(SearchRequest request, int maxResults, CancelToken cancel) throws SearchException {
            calls.incrementAndGet();
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failWith != null) throw failWith;
            return hits;
        }
    }

    private static SearchHit hit(String url) {
        return new SearchHit(url, "Title " + url, "snippet for " + url, null, null, 1.0);
    }

    private static SearchRequest req(String q) {
        return new SearchRequest(q, null, null, "general", false);
    }

    @Test
    void cachesIdenticalQueries() throws Exception {
        Scripted p = new Scripted("a");
        p.hits = List.of(hit("https://a.com/1"));
        SearchService service = new SearchService(List.of(p), new PageReader(), 6);
        SearchResponse first = service.search(req("Neon   pricing"), CancelToken.none());
        SearchResponse second = service.search(req("neon pricing"), CancelToken.none());
        assertFalse(first.cached());
        assertTrue(second.cached());
        assertEquals(1, p.calls.get());
    }

    @Test
    void failsOverToTheNextProvider() throws Exception {
        Scripted down = new Scripted("down");
        down.failWith = new SearchException(SearchException.Kind.QUOTA, "down", "plan limit");
        Scripted up = new Scripted("up");
        up.hits = List.of(hit("https://b.com"));
        SearchResponse r = new SearchService(List.of(down, up), new PageReader(), 6).search(req("q"), CancelToken.none());
        assertEquals("up", r.provider());
        assertEquals(1, r.hits().size());
    }

    @Test
    void reportsTheFirstErrorWhenAllProvidersFail() {
        Scripted a = new Scripted("a");
        a.failWith = new SearchException(SearchException.Kind.AUTH, "a", "bad key");
        Scripted b = new Scripted("b");
        b.failWith = new SearchException(SearchException.Kind.UNAVAILABLE, "b", "503");
        SearchException e = assertThrows(SearchException.class, () -> new SearchService(List.of(a, b), new PageReader(), 6).search(req("q"), CancelToken.none()));
        assertEquals(SearchException.Kind.AUTH, e.kind());
    }

    @Test
    void removesDuplicateUrlsAndCapsResults() throws Exception {
        Scripted p = new Scripted("a");
        List<SearchHit> many = new ArrayList<>();
        many.add(hit("https://www.x.com/page?utm_source=feed"));
        many.add(hit("https://x.com/page"));
        for (int i = 0; i < 10; i++) many.add(hit("https://site" + i + ".com"));
        p.hits = many;
        SearchResponse r = new SearchService(List.of(p), new PageReader(), 4).search(req("q"), CancelToken.none());
        assertEquals(4, r.hits().size());
        assertEquals("https://www.x.com/page?utm_source=feed", r.hits().get(0).url());
        assertEquals("https://site0.com", r.hits().get(1).url());
    }

    @Test
    void concurrentIdenticalQueriesShareOneRequest() throws Exception {
        Scripted p = new Scripted("a");
        p.hits = List.of(hit("https://a.com"));
        p.gate = new CountDownLatch(1);
        SearchService service = new SearchService(List.of(p), new PageReader(), 6);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<SearchResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> service.search(req("same query"), CancelToken.none())));
        Thread.sleep(200);
        p.gate.countDown();
        for (Future<SearchResponse> f : futures) assertEquals(1, f.get().hits().size());
        pool.shutdown();
        assertEquals(1, p.calls.get());
    }
}
