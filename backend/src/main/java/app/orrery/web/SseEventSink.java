package app.orrery.web;

import app.orrery.core.events.AgentEvent;
import app.orrery.core.events.EventSink;
import app.orrery.core.util.CancelToken;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Writes agent events to one Server-Sent Events connection. Sends are serialised (the
 * agent emits from several threads during parallel searches), a comment ping every 15 s
 * keeps proxies from closing the stream, and a failed write cancels the turn.
 */
final class SseEventSink implements EventSink {

    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final SseEmitter emitter;
    private final CancelToken cancel;
    private final ScheduledFuture<?> heartbeat;
    private boolean closed;

    SseEventSink(SseEmitter emitter, CancelToken cancel) {
        this.emitter = emitter;
        this.cancel = cancel;
        this.heartbeat = HEARTBEAT.scheduleAtFixedRate(this::ping, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void emit(AgentEvent event) {
        if (closed) return;
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.toJson()));
        } catch (Exception e) {
            closed = true;
            cancel.cancel("client disconnected");
        }
    }

    private synchronized void ping() {
        if (closed) return;
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            closed = true;
            cancel.cancel("client disconnected");
        }
    }

    synchronized void complete() {
        heartbeat.cancel(false);
        if (closed) return;
        closed = true;
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // connection already gone
        }
    }
}
