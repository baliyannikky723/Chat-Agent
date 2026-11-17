package app.orrery.web;

import app.orrery.core.AgentRuntime;
import app.orrery.core.agent.TurnOrchestrator.TurnInput;
import app.orrery.core.events.AgentEvent;
import app.orrery.core.events.AgentEvent.TurnError;
import app.orrery.core.events.EventSink;
import app.orrery.core.util.CancelToken;
import app.orrery.core.util.Ids;
import app.orrery.web.ApiDtos.ChatRequest;
import app.orrery.web.ApiDtos.OkDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POST /api/chat streams one turn as Server-Sent Events. The turn runs on a virtual thread;
 * this request thread returns immediately. POST /api/chat/{turnId}/cancel stops it at once
 * (closing the model and search connections) instead of waiting for the next write to fail.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    static final int MAX_MESSAGE_CHARS = 8000;

    private final AgentRuntime runtime;
    private final Map<String, CancelToken> active = new ConcurrentHashMap<>();

    public ChatController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest body, HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        SseEmitter emitter = new SseEmitter(15 * 60_000L);
        CancelToken cancel = new CancelToken();
        SseEventSink sink = new SseEventSink(emitter, cancel);
        emitter.onTimeout(() -> cancel.cancel("timeout"));
        emitter.onError(e -> cancel.cancel("connection error"));

        String failure = validate(body);
        if (failure != null) {
            sink.emit(new AgentEvent.TurnFailed(new TurnError("internal", failure, null, false), null));
            sink.complete();
            return emitter;
        }
        if (!runtime.ready()) {
            String missing = runtime.settings().llmMissing() != null ? runtime.settings().llmMissing() : runtime.settings().searchMissing();
            sink.emit(new AgentEvent.TurnFailed(new TurnError("not_configured", "Orrery is not set up yet.",
                    missing + ". Add it to .env and restart with npm run dev.", false), null));
            sink.complete();
            return emitter;
        }
        long wait = runtime.turnLimiter().tryAcquire(user.id());
        if (wait > 0) {
            sink.emit(new AgentEvent.TurnFailed(new TurnError("busy", "You're sending messages faster than the limit.",
                    "Try again in " + Math.max(1, wait / 1000) + " s.", true), null));
            sink.complete();
            return emitter;
        }

        String message = body.message() == null || body.message().isBlank() ? "Is this still correct today?" : body.message().strip();
        TurnInput input = new TurnInput(user.id(), blankToNull(body.conversationId()), message, blankToNull(body.recheckMessageId()), null);
        EventSink tracking = event -> {
            if (event instanceof AgentEvent.TurnStarted started) active.put(started.turnId(), cancel);
            sink.emit(event);
        };
        runtime.turnPool().execute(() -> {
            try {
                runtime.orchestrator().run(input, tracking, cancel);
            } finally {
                active.values().removeIf(t -> t == cancel);
                sink.complete();
            }
        });
        return emitter;
    }

    @PostMapping("/chat/{turnId}/cancel")
    public OkDto cancel(@PathVariable("turnId") String turnId) {
        CancelToken token = active.remove(turnId);
        if (token != null) token.cancel("stopped by user");
        return new OkDto(token != null);
    }

    static String validate(ChatRequest body) {
        if (body == null) return "Empty request.";
        boolean recheck = body.recheckMessageId() != null && !body.recheckMessageId().isBlank();
        if (!recheck && (body.message() == null || body.message().isBlank())) return "Type a message first.";
        if (body.message() != null && body.message().length() > MAX_MESSAGE_CHARS) {
            return "That message is too long (" + body.message().length() + " characters). The limit is " + MAX_MESSAGE_CHARS + ".";
        }
        if (body.conversationId() != null && !body.conversationId().isBlank() && !Ids.isUuid(body.conversationId())) {
            return "Unknown conversation.";
        }
        if (recheck && !Ids.isUuid(body.recheckMessageId())) return "Unknown message to re-check.";
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
