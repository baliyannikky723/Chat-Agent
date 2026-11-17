package app.orrery.support;

import app.orrery.core.events.AgentEvent;
import app.orrery.core.events.EventSink;
import java.util.ArrayList;
import java.util.List;

/** Records every event a turn emits, in order. */
public final class CollectingSink implements EventSink {

    private final List<AgentEvent> events = new ArrayList<>();

    @Override
    public synchronized void emit(AgentEvent event) {
        events.add(event);
    }

    public synchronized List<AgentEvent> events() {
        return List.copyOf(events);
    }

    public synchronized List<String> types() {
        return events.stream().map(AgentEvent::type).toList();
    }

    public synchronized <T extends AgentEvent> List<T> all(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public synchronized <T extends AgentEvent> T first(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    public synchronized String streamedText() {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.TextDelta d) sb.append(d.text());
            if (e instanceof AgentEvent.TextReset) sb.setLength(0);
        }
        return sb.toString();
    }
}
