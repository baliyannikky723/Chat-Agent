package app.orrery.core.agent;

import app.orrery.core.events.AgentEvent.SourceRef;
import app.orrery.core.search.SearchModels.SearchHit;
import app.orrery.core.search.Urls;
import app.orrery.core.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives every page found during one turn a stable citation number. The same page found by
 * two different searches keeps its first number, so [3] always means one page.
 */
public final class SourceRegistry {

    private final Map<String, SourceRef> byUrl = new LinkedHashMap<>();

    public synchronized SourceRef register(SearchHit hit) {
        String key = Urls.canonical(hit.url());
        SourceRef existing = byUrl.get(key);
        if (existing != null) return existing;
        SourceRef ref = new SourceRef(byUrl.size() + 1, hit.url(), hit.title(), Urls.domain(hit.url()), hit.publishedAt(),
                Text.truncate(hit.snippet() == null ? "" : hit.snippet(), 280));
        byUrl.put(key, ref);
        return ref;
    }

    public synchronized List<SourceRef> all() {
        return new ArrayList<>(byUrl.values());
    }

    public synchronized int size() {
        return byUrl.size();
    }

    public synchronized Map<Integer, String> domains() {
        Map<Integer, String> out = new HashMap<>();
        for (SourceRef s : byUrl.values()) out.put(s.n(), s.domain());
        return out;
    }
}
