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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Serper — Google results as JSON. Date filters use Google's {@code tbs}: {@code qdr:w}
 * for "past week" or {@code cdr:1,cd_min:MM/DD/YYYY,cd_max:MM/DD/YYYY} for a range.
 */
public final class SerperProvider implements SearchProvider {

    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final HttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final Supplier<LocalDate> today;

    public SerperProvider(HttpClient client, String apiKey) {
        this(client, apiKey, "https://google.serper.dev", () -> LocalDate.now(ZoneOffset.UTC));
    }

    public SerperProvider(HttpClient client, String apiKey, String baseUrl, Supplier<LocalDate> today) {
        this.client = client;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.today = today;
    }

    @Override
    public String id() {
        return "serper";
    }

    String tbs(SearchRequest req) {
        if (req.since() != null) {
            return "cdr:1,cd_min:" + US_DATE.format(req.since()) + ",cd_max:" + US_DATE.format(today.get().plusDays(1));
        }
        if (req.recency() == null) return null;
        return switch (req.recency()) {
            case "day" -> "qdr:d";
            case "week" -> "qdr:w";
            case "month" -> "qdr:m";
            case "year" -> "qdr:y";
            default -> null;
        };
    }

    @Override
    public List<SearchHit> search(SearchRequest req, int maxResults, CancelToken cancel) throws SearchException {
        boolean news = "news".equals(req.topic());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", req.query());
        body.put("num", Math.min(maxResults, 20));
        String tbs = tbs(req);
        if (tbs != null) body.put("tbs", tbs);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + (news ? "/news" : "/search")))
                .timeout(Duration.ofSeconds(15))
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        return parse(HttpCalls.send(client, request, id(), 15_000, cancel));
    }

    static List<SearchHit> parse(String raw) throws SearchException {
        JNode root;
        try {
            root = Json.tree(raw);
        } catch (JsonException e) {
            throw new SearchException(SearchException.Kind.UNAVAILABLE, "serper", "unreadable response");
        }
        List<SearchHit> hits = new ArrayList<>();
        JNode box = root.get("answerBox");
        if (box.isObject() && box.get("link").asString() != null) {
            String text = box.get("answer").asString(box.get("snippet").text());
            hits.add(new SearchHit(box.get("link").text(), box.get("title").text(), text, null, null, 1.1));
        }
        List<JNode> results = root.get("news").isArray() ? root.get("news").items() : root.get("organic").items();
        int rank = 0;
        for (JNode r : results) {
            String url = r.get("link").asString();
            if (url == null || url.isBlank()) continue;
            String source = r.get("source").asString();
            String snippet = r.get("snippet").text();
            if (source != null && !source.isBlank()) snippet = source + ": " + snippet;
            hits.add(new SearchHit(url, r.get("title").text(), snippet, null, r.get("date").asString(), 1.0 - rank++ * 0.05));
        }
        return hits;
    }
}
