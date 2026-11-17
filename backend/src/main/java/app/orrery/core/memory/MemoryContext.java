package app.orrery.core.memory;

import app.orrery.core.events.AgentEvent.RecallSummary;
import app.orrery.core.store.Store.ClaimRow;
import app.orrery.core.store.Store.HistoryBounds;
import app.orrery.core.store.Store.IndexEntry;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.store.Store.TurnRow;
import app.orrery.core.understand.DayRange;
import app.orrery.core.understand.TimeRef;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What the memory layer found for one message, before the model is called.
 *
 * @param needed      the message refers to the past (or asks to re-check something)
 * @param turns       past turns to show the model, with the facts stated in them
 * @param related     past turns on the same topic, offered as optional context
 * @param mismatch    asked about one date, but the topic only appears on another
 * @param nearby      turn counts on days around a requested date that had no match
 * @param changeSince start of the "has anything changed since" window, if any
 */
public record MemoryContext(
        boolean needed,
        List<TimeRef> refs,
        List<Resolution> resolutions,
        List<RecalledTurn> turns,
        List<RecalledTurn> related,
        String mismatch,
        Map<LocalDate, Integer> nearby,
        HistoryBounds bounds,
        List<IndexEntry> index,
        LocalDate changeSince,
        boolean recheck,
        RecallSummary summary) {

    /** How one time phrase was resolved: which candidate range had a match, if any. */
    public record Resolution(String phrase, String label, DayRange chosen, List<DayRange> tried) {}

    public record RecalledTurn(TurnRow turn, String localTime, List<ClaimRow> claims, List<SourceRow> sources) {}

    public boolean found() {
        return !turns.isEmpty();
    }
}
