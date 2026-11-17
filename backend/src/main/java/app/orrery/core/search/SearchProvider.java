package app.orrery.core.search;

import app.orrery.core.search.SearchModels.SearchHit;
import app.orrery.core.search.SearchModels.SearchRequest;
import app.orrery.core.util.CancelToken;
import java.util.List;

/** One web search API. Adapters translate {@link SearchRequest} into the provider's own parameters. */
public interface SearchProvider {

    String id();

    List<SearchHit> search(SearchRequest request, int maxResults, CancelToken cancel) throws SearchException;
}
