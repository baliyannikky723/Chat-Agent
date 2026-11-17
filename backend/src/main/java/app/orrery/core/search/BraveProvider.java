package app.orrery.core.search;

import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.json.JsonException;
import app.orrery.core.search.SearchModels.SearchHit;
import app.orrery.core.search.SearchModels.SearchRequest;
import app.orrery.core.util.CancelToken;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Brave Search — an independent web index with a publish-date filter
 * ({@code freshness=pd|pw|pm|py} or {@code YYYY-MM-DDtoYYYY-MM-DD}). Snippets only; the
 * search service reads full pages itself when a query needs exact figures.
 */
public final class BraveProvider implements SearchProvider {

    private final HttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final Supplier<LocalDate> today;

    public BraveProvider(HttpClient client, String apiKey) {
        this(client, apiKey, "https://api.search.brave.com", () -> LocalDate.now(ZoneOffset.UTC));
    }

    public BraveProvider(HttpClient client, String apiKey, String baseUrl, Supplier<LocalDate> today) {
        this.client = client;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.today = today;
    }

    @Override
    public String id() {
        return "brave";
    }

    String freshness(SearchRequest req) {
        if (req.since() != null) return req.since() + "to" + today.get().plusDays(1);
        if (req.recency() == null) return null;
        return switch (req.recency()) {
            case "day" -> "pd";
            case "week" -> "pw";
            case "month" -> "pm";
            case "year" -> "py";
            default -> null;
        };
    }

    URI uri(SearchRequest req, int maxResults) {
        boolean news = "news".equals(req.topic());
        StringBuilder q = new StringBuilder(baseUrl).append(news ? "/res/v1/news/search" : "/res/v1/web/search");
        q.append("?q=").append(URLEncoder.encode(req.query(), StandardCharsets.UTF_8));
        q.append("&count=").append(Math.min(maxResults, 20));
        if (!news) q.append("&extra_snippets=true");
        String fresh = freshness(req);
        if (fresh != null) q.append("&freshness=").append(fresh);
        return URI.create(q.toString());
    }

    @Override
    public List<SearchHit> search(SearchRequest req, int maxResults, CancelToken cancel) throws SearchException {
        HttpRequest request = HttpRequest.newBuilder(uri(req, maxResults))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();
        return parse(HttpCalls.send(client, request, id(), 15_000, cancel));
    }

    static List<SearchHit> parse(String raw) throws SearchException {
        JNode root;
        try {
            root = Json.tree(raw);
        } catch (JsonException e) {
            throw new SearchException(SearchException.Kind.UNAVAILABLE, "brave", "unreadable response");
        }
        List<JNode> results = root.path("web", "results").isArray() ? root.path("web", "results").items() : root.get("results").items();
        List<SearchHit> hits = new ArrayList<>();
        int rank = 0;
        for (JNode r : results) {
            String url = r.get("url").asString();
            if (url == null || url.isBlank()) continue;
            StringBuilder snippet = new StringBuilder(HtmlText.stripTags(r.get("description").text()));
            for (JNode extra : r.get("extra_snippets").items()) {
                if (snippet.length() > 900) break;
                snippet.append(" … ").append(HtmlText.stripTags(extra.text()));
            }
            String published = r.get("page_age").asString();
            if (published == null) published = r.get("age").asString();
            hits.add(new SearchHit(url, HtmlText.stripTags(r.get("title").text()), snippet.toString().strip(), null, published,
                    1.0 - rank++ * 0.05));
        }
        return hits;
    }
}
