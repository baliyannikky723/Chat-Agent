package app.orrery.web;

import app.orrery.core.AgentRuntime;
import app.orrery.core.config.Settings;
import app.orrery.core.events.AgentEvent;
import app.orrery.core.store.Store.UserRow;
import app.orrery.core.understand.TemporalResolver;
import app.orrery.core.util.CancelToken;
import app.orrery.web.ApiDtos.ClockRequest;
import app.orrery.web.ApiDtos.ClockStatus;
import app.orrery.web.ApiDtos.HealthDto;
import app.orrery.web.ApiDtos.LlmStatus;
import app.orrery.web.ApiDtos.OkDto;
import app.orrery.web.ApiDtos.SearchStatus;
import app.orrery.web.ApiDtos.StatusDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Status for the setup screen, plus the demo tools (only with DEMO_TOOLS=true). */
@RestController
@RequestMapping("/api")
public class SystemController {

    static final String VERSION = "1.0.0";

    private final AgentRuntime runtime;

    public SystemController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/health")
    public HealthDto health() {
        Settings s = runtime.settings();
        return new HealthDto(true, runtime.ready(), s.llm() == null ? null : s.llm().provider().id,
                s.llm() == null ? null : runtime.router() == null ? s.llm().model() : runtime.router().mainModel(),
                s.search().stream().map(k -> k.provider().id).toList(), VERSION);
    }

    @GetMapping("/status")
    public StatusDto status(HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        Settings s = runtime.settings();
        LlmStatus llm = s.llm() == null
                ? new LlmStatus(false, null, null, null, s.llmMissing())
                : new LlmStatus(true, s.llm().provider().id, runtime.router() == null ? s.llm().model() : runtime.router().mainModel(),
                        s.llm().fastModel(), null);
        SearchStatus search = new SearchStatus(!s.search().isEmpty(), s.search().stream().map(k -> k.provider().id).toList(), s.searchMissing());
        return new StatusDto(runtime.ready(), llm, search, clock(user), VERSION);
    }

    private ClockStatus clock(UserContext user) {
        UserRow row = runtime.store().user(user.id());
        long offset = row == null ? 0 : row.clockOffsetMillis();
        String now = TemporalResolver.zonedNow(runtime.clock().now(offset), user.timezone()).toOffsetDateTime().toString();
        return new ClockStatus(runtime.settings().demoTools(), offset, now, user.timezone());
    }

    @PostMapping("/dev/clock")
    public ClockStatus setClock(@RequestBody(required = false) ClockRequest body, HttpServletRequest request) {
        requireDemoTools();
        UserContext user = UserContext.of(request);
        long offset = 0;
        if (body != null && body.localDateTime() != null && !body.localDateTime().isBlank()) {
            try {
                Instant target = LocalDateTime.parse(body.localDateTime().strip()).atZone(ZoneId.of(user.timezone())).toInstant();
                offset = target.toEpochMilli() - System.currentTimeMillis();
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("Use the format 2026-11-09T14:30.");
            }
        }
        runtime.store().setClockOffset(user.id(), offset);
        return clock(user);
    }

    /** Streams progress while three backdated conversations are created through the real agent. */
    @PostMapping(value = "/dev/seed-demo", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter seedDemo(HttpServletRequest request) {
        requireDemoTools();
        UserContext user = UserContext.of(request);
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        CancelToken cancel = new CancelToken();
        SseEventSink sink = new SseEventSink(emitter, cancel);
        if (!runtime.ready()) {
            sink.emit(new AgentEvent.Warning("Add your API keys first: demo history is created by the real agent."));
            sink.complete();
            return emitter;
        }
        runtime.turnPool().execute(() -> {
            try {
                int created = runtime.seeder().run(user.id(), sink, cancel);
                sink.emit(new AgentEvent.Status("done", "Created " + created + " past conversations"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                sink.emit(new AgentEvent.Warning("Demo history failed: " + e.getMessage()));
            } finally {
                sink.complete();
            }
        });
        return emitter;
    }

    private void requireDemoTools() {
        if (!runtime.settings().demoTools()) throw new ApiException(404, "not_found", "Demo tools are off. Set DEMO_TOOLS=true in .env.");
    }
}
