package app.orrery.core.llm;

import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.LlmResult;
import app.orrery.core.llm.LlmModels.StreamCallbacks;
import app.orrery.core.util.CancelToken;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the model for each call and handles failure policy in one place:
 *
 * <ul>
 *   <li><b>Fallbacks</b> — if the configured model is reported as not found (renamed or
 *       retired — model ids change monthly), the next default is tried and remembered.</li>
 *   <li><b>Retries</b> — rate limits and 5xx are retried with backoff, but only before any
 *       text reached the user, so an answer is never duplicated.</li>
 *   <li><b>Two tiers</b> — the main model answers; a fast model writes titles and extracts
 *       facts in the background.</li>
 * </ul>
 */
public final class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final int MAX_RETRIES = 2;

    private final LlmClient client;
    private final String fastModel;
    private final List<String> fallbacks;
    private final String reasoningEffort;
    private volatile String mainModel;

    public ModelRouter(LlmClient client, String mainModel, String fastModel, List<String> fallbacks, String reasoningEffort) {
        this.client = client;
        this.mainModel = mainModel;
        this.fastModel = fastModel == null || fastModel.isBlank() ? mainModel : fastModel;
        this.fallbacks = List.copyOf(fallbacks);
        this.reasoningEffort = reasoningEffort;
    }

    public String provider() {
        return client.provider();
    }

    public String mainModel() {
        return mainModel;
    }

    public String fastModel() {
        return fastModel;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public LlmResult streamMain(LlmRequest req, StreamCallbacks callbacks, CancelToken cancel) throws LlmException {
        List<String> candidates = new ArrayList<>();
        candidates.add(mainModel);
        for (String f : fallbacks) if (!candidates.contains(f)) candidates.add(f);
        return run(candidates, req, callbacks, cancel, true);
    }

    public LlmResult completeFast(LlmRequest req, CancelToken cancel) throws LlmException {
        List<String> candidates = new ArrayList<>();
        candidates.add(fastModel);
        if (!candidates.contains(mainModel)) candidates.add(mainModel);
        return run(candidates, req, delta -> {}, cancel, false);
    }

    private LlmResult run(List<String> candidates, LlmRequest req, StreamCallbacks callbacks, CancelToken cancel, boolean main)
            throws LlmException {
        LlmException last = null;
        int retries = 0;
        int i = 0;
        while (i < candidates.size()) {
            String model = candidates.get(i);
            boolean[] emitted = {false};
            try {
                LlmResult result = client.stream(req.withModel(model), delta -> {
                    emitted[0] = true;
                    callbacks.onText(delta);
                }, cancel);
                if (main && !model.equals(mainModel)) {
                    log.warn("model {} was not available; using {} from now on", mainModel, model);
                    mainModel = model;
                }
                return result;
            } catch (LlmException e) {
                last = e;
                if (e.kind() == LlmException.Kind.CANCELLED || emitted[0] || cancel.isCancelled()) throw e;
                if (e.kind() == LlmException.Kind.MODEL_NOT_FOUND) {
                    log.warn("model {} not found at {}: {}", model, client.provider(), e.getMessage());
                    i++;
                    retries = 0;
                    continue;
                }
                if (e.retryable() && retries < MAX_RETRIES) {
                    long wait = e.retryAfterMillis() > 0 ? Math.min(e.retryAfterMillis(), 8_000) : 800L << retries;
                    log.info("{} call failed ({}), retrying in {} ms", client.provider(), e.kind(), wait);
                    retries++;
                    sleep(wait, cancel);
                    continue;
                }
                throw e;
            }
        }
        throw last != null ? last : new LlmException(LlmException.Kind.MODEL_NOT_FOUND, "no model available");
    }

    private static void sleep(long millis, CancelToken cancel) throws LlmException {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "cancelled");
            try {
                Thread.sleep(Math.min(100, Math.max(1, until - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(LlmException.Kind.CANCELLED, "interrupted");
            }
        }
    }
}
