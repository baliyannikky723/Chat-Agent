package app.orrery.core.understand;

import java.util.List;

/**
 * A time phrase found in a message, resolved to dates in the user's timezone.
 *
 * @param phrase     the matched text, e.g. "last Tuesday"
 * @param index      where the phrase starts in the message
 * @param candidates ranked interpretations; the first is preferred
 * @param since      "since Monday": the range runs from that date until today
 * @param label      human label of the preferred candidate, e.g. "Monday 9 Nov 2026"
 */
public record TimeRef(
        String phrase,
        int index,
        Kind kind,
        List<DayRange> candidates,
        Direction direction,
        boolean since,
        String label) {

    public enum Kind { DAY, RANGE }

    public enum Direction { PAST, FUTURE, PRESENT }

    public DayRange preferred() {
        return candidates.get(0);
    }

    public boolean lookingBack() {
        return direction != Direction.FUTURE;
    }
}
