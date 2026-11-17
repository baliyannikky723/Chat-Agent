package app.orrery.core.events;

/** Where a turn's events go: an SSE connection in production, a list in tests. */
@FunctionalInterface
public interface EventSink {

    void emit(AgentEvent event);

    EventSink NOOP = event -> {};
}
