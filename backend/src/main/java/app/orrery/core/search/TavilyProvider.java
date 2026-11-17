package app.orrery.core.search;

import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.json.JsonException;
import app.orrery.core.search.SearchModels.SearchHit;
import app.orrery.core.search.SearchModels.SearchRequest;
import app.orrery.core.util.CancelToken;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily — built for agents: returns cleaned page content, supports publish-date filters
 * (time_range, start_date) and can include the page text itself (include_raw_content).
 * Free tier: 1,000 credits a month, no card.
 */
public final class TavilyProvider implements SearchProvider {

    private final HttpClient client;
    private final String apiKey;
    private final String baseUrl;

    public TavilyProvider(HttpClient client, String apiKey) {
        this(client, apiKey, "https://api.tavily.com");
    }

    public TavilyProvider(HttpClient client, String apiKey, String baseUrl) {
        this.client = client;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String id() {
        return "tavily";
    }

    Map<String, Object> body(SearchRequest req, int maxResults) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", req.query());
        body.put("max_results", maxResults);
        body.put("search_depth", req.deep() ? "advanced" : "basic");
        body.put("topic", "news".equals(req.topic()) ? "news" : "general");
        if (req.since() != null) body.put("start_date", req.since().toString());
        else if (req.recency() != null) body.put("time_range", req.recency());
        body.put("include_published_date", true);
        body.put("include_answer", false);
        if (req.deep()) {
            body.put("include_raw_content", "markdown");
            body.put("chunks_per_source", 3);
        }
        return body;
    }

    @Override
    public List<SearchHit> search(SearchRequest req, int maxResults, CancelToken cancel) throws SearchException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/search"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body(req, maxResults))))
                .build();
        String raw = HttpCalls.send(client, request, id(), req.deep() ? 25_000 : 15_000, cancel);
        return parse(raw);
    }

    static List<SearchHit> parse(String raw) throws SearchException {
        JNode root;
        try {
            root = Json.tree(raw);
        } catch (JsonException e) {
            throw new SearchException(SearchException.Kind.UNAVAILABLE, "tavily", "unreadable response");
        }
        List<SearchHit> hits = new ArrayList<>();
        for (JNode r : root.get("results").items()) {
            String url = r.get("url").asString();
            if (url == null || url.isBlank()) continue;
            String raw2 = r.get("raw_content").asString();
            hits.add(new SearchHit(url, r.get("title").text(), r.get("content").text(),
                    raw2 == null || raw2.isBlank() ? null : raw2, r.get("published_date").asString(), r.get("score").asDouble(0)));
        }
        return hits;
    }
}
