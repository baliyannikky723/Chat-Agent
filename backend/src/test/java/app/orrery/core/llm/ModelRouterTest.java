package app.orrery.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.LlmResult;
import app.orrery.core.llm.LlmModels.StreamCallbacks;
import app.orrery.core.llm.LlmModels.ToolChoice;
import app.orrery.core.llm.LlmModels.Usage;
import app.orrery.core.llm.LlmModels.UserMessage;
import app.orrery.core.util.CancelToken;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ModelRouterTest {

    /** A client whose answer per model is scripted. */
    static final class Scripted implements LlmClient {
        final List<String> calls = new ArrayList<>();
        final Function<String, Object> behaviour;

        Scripted(Function<String, Object> behaviour) {
            this.behaviour = behaviour;
        }

        public String provider() {
            return "test";
        }

        public LlmResult stream(LlmRequest request, StreamCallbacks callbacks, CancelToken cancel) throws LlmException {
            calls.add(request.model());
            Object out = behaviour.apply(request.model() + "#" + calls.size());
            if (out instanceof LlmException e) throw e;
            String text = (String) out;
            if (text.startsWith("partial:")) {
                callbacks.onText("partial");
                throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, "dropped", 0);
            }
            callbacks.onText(text);
            return new LlmResult(text, List.of(), "stop", Usage.NONE, request.model());
        }
    }

    private static LlmRequest req() {
        return new LlmRequest(null, "s", List.of(new UserMessage("q")), List.of(), ToolChoice.AUTO, 100, "low", false);
    }

    @Test
    void fallsBackWhenTheModelIsRetiredAndRemembersIt() throws Exception {
        Scripted client = new Scripted(key -> key.startsWith("old#") ? new LlmException(LlmException.Kind.MODEL_NOT_FOUND, 404, "gone", 0) : "ok");
        ModelRouter router = new ModelRouter(client, "old", "fast", List.of("new"), "low");
        assertEquals("ok", router.streamMain(req(), d -> {}, CancelToken.none()).text());
        assertEquals("new", router.mainModel());
        router.streamMain(req(), d -> {}, CancelToken.none());
        assertEquals(List.of("old", "new", "new"), client.calls);
    }

    @Test
    void retriesTransientErrorsBeforeAnyText() throws Exception {
        Scripted client = new Scripted(key -> key.endsWith("#1") ? new LlmException(LlmException.Kind.UNAVAILABLE, 503, "busy", 10) : "ok");
        ModelRouter router = new ModelRouter(client, "m", "m", List.of(), "low");
        assertEquals("ok", router.streamMain(req(), d -> {}, CancelToken.none()).text());
        assertEquals(2, client.calls.size());
    }

    @Test
    void neverRetriesOnceTextReachedTheUser() {
        Scripted client = new Scripted(key -> "partial:");
        ModelRouter router = new ModelRouter(client, "m", "m", List.of("other"), "low");
        assertThrows(LlmException.class, () -> router.streamMain(req(), d -> {}, CancelToken.none()));
        assertEquals(1, client.calls.size());
    }

    @Test
    void fastModelFallsBackToTheMainModel() throws Exception {
        Scripted client = new Scripted(key -> key.startsWith("fast#") ? new LlmException(LlmException.Kind.MODEL_NOT_FOUND, 404, "no", 0) : "title");
        ModelRouter router = new ModelRouter(client, "main", "fast", List.of(), "low");
        assertEquals("title", router.completeFast(req(), CancelToken.none()).text());
        assertEquals(List.of("fast", "main"), client.calls);
    }
}
