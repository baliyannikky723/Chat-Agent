package app.orrery.core.search;

import java.time.LocalDate;
import java.util.List;

/** Value types of the search layer. */
public final class SearchModels {

    private SearchModels() {}

    /**
     * @param recency day | week | month | year, or null for any time
     * @param since   only pages published on or after this date
     * @param topic   general | news
     * @param deep    read full page text of the top results (slower; for exact figures)
     */
    public record SearchRequest(String query, String recency, LocalDate since, String topic, boolean deep) {

        public boolean timeBound() {
            return recency != null || since != null || "news".equals(topic);
        }
    }

    /** One result, normalised across providers. {@code content} may hold full page text. */
    public record SearchHit(String url, String title, String snippet, String content, String publishedAt, double score) {

        public SearchHit withContent(String newContent) {
            return new SearchHit(url, title, snippet, newContent, publishedAt, score);
        }

        public SearchHit withUrl(String newUrl) {
            return new SearchHit(newUrl, title, snippet, content, publishedAt, score);
        }
    }

    public record SearchResponse(List<SearchHit> hits, String provider, boolean cached, long millis) {}
}
