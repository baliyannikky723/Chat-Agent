package app.orrery.core.llm;

import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.LlmResult;
import app.orrery.core.llm.LlmModels.StreamCallbacks;
import app.orrery.core.util.CancelToken;

/** One model API. Always streams; a non-streaming call is a stream nobody listens to. */
public interface LlmClient {

    String provider();

    LlmResult stream(LlmRequest request, StreamCallbacks callbacks, CancelToken cancel) throws LlmException;

    default LlmResult complete(LlmRequest request, CancelToken cancel) throws LlmException {
        return stream(request, delta -> {}, cancel);
    }
}
