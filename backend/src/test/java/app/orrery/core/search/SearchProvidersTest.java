package app.orrery.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.search.SearchModels.SearchHit;
import app.orrery.core.search.SearchModels.SearchRequest;
import app.orrery.core.util.CancelToken;
import app.orrery.support.FakeHttp;
import app.orrery.support.FakeHttp.Reply;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchProvidersTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void tavilySendsDateFiltersAndParsesResults() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/search", req -> Reply.json("""
                    {"query":"neon pricing","results":[
                      {"title":"Neon Pricing","url":"https://neon.com/pricing","content":"Launch $19/month","score":0.91,"published_date":"2026-11-12","raw_content":null},
                      {"title":"","url":"","content":"skipped: no url"}]}"""));
            TavilyProvider tavily = new TavilyProvider(http, "tvly-test", server.baseUrl());
            List<SearchHit> hits = tavily.search(new SearchRequest("neon pricing", null, LocalDate.of(2026, 11, 9), "news", false), 6, CancelToken.none());
            assertEquals(1, hits.size());
            assertEquals("https://neon.com/pricing", hits.get(0).url());
            assertEquals("2026-11-12", hits.get(0).publishedAt());
            JNode body = Json.tree(server.requests("/search").get(0).body());
            assertEquals("2026-11-09", body.get("start_date").text());
            assertEquals("news", body.get("topic").text());
            assertEquals("basic", body.get("search_depth").text());
            assertTrue(body.get("time_range").isMissing(), "since wins over recency");
            assertEquals("Bearer tvly-test", server.requests("/search").get(0).headers().get("Authorization").get(0));
        }
    }

    @Test
    void tavilyDeepAsksForPageContent() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/search", req -> Reply.json("{\"results\":[]}"));
            new TavilyProvider(http, "k", server.baseUrl()).search(new SearchRequest("q", "week", null, "general", true), 5, CancelToken.none());
            JNode body = Json.tree(server.requests("/search").get(0).body());
            assertEquals("advanced", body.get("search_depth").text());
            assertEquals("markdown", body.get("include_raw_content").text());
            assertEquals("week", body.get("time_range").text());
        }
    }

    @Test
    void providerErrorsAreClassified() {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/search", req -> Reply.status(401, "{\"detail\":\"bad key\"}"));
            SearchException e = assertThrows(SearchException.class,
                    () -> new TavilyProvider(http, "k", server.baseUrl()).search(new SearchRequest("q", null, null, null, false), 5, CancelToken.none()));
            assertEquals(SearchException.Kind.AUTH, e.kind());
        }
    }

    @Test
    void braveUsesFreshnessRangesAndStripsMarkup() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/res/v1/web/search", req -> Reply.json("""
                    {"web":{"results":[{"title":"<strong>Supabase</strong> Pricing","url":"https://supabase.com/pricing",
                      "description":"Pro plan from <strong>$25</strong>","page_age":"2026-11-10T00:00:00","extra_snippets":["Free tier"]}]}}"""));
            BraveProvider brave = new BraveProvider(http, "brave-key", server.baseUrl(), () -> LocalDate.of(2026, 11, 14));
            List<SearchHit> hits = brave.search(new SearchRequest("supabase pricing", null, LocalDate.of(2026, 11, 9), "general", false), 5, CancelToken.none());
            assertEquals("Supabase Pricing", hits.get(0).title());
            assertTrue(hits.get(0).snippet().startsWith("Pro plan from $25"));
            assertEquals("2026-11-10T00:00:00", hits.get(0).publishedAt());
            String query = server.requests().get(0).query();
            assertTrue(query.contains("freshness=2026-11-09to2026-11-15"), query);
            assertEquals("brave-key", server.requests().get(0).headers().get("X-subscription-token").get(0));
        }
    }

    @Test
    void serperMapsRecencyToTbsAndKeepsTheAnswerBox() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/search", req -> Reply.json("""
                    {"answerBox":{"title":"Node.js","link":"https://nodejs.org","answer":"Node.js 24 is the active LTS"},
                     "organic":[{"title":"Node.js Releases","link":"https://nodejs.org/en/about/previous-releases","snippet":"LTS schedule","date":"Nov 2, 2026"}]}"""));
            SerperProvider serper = new SerperProvider(http, "serper-key", server.baseUrl(), () -> LocalDate.of(2026, 11, 14));
            List<SearchHit> hits = serper.search(new SearchRequest("node lts", "month", null, null, false), 5, CancelToken.none());
            assertEquals(2, hits.size());
            assertEquals("Node.js 24 is the active LTS", hits.get(0).snippet());
            assertEquals("qdr:m", Json.tree(server.requests().get(0).body()).get("tbs").text());
            assertNull(hits.get(0).publishedAt());
        }
    }
}
