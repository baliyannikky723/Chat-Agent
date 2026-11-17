package app.orrery.core.memory;

import app.orrery.core.events.AgentEvent.RecallHit;
import app.orrery.core.events.AgentEvent.RecallSummary;
import app.orrery.core.events.AgentEvent.Reference;
import app.orrery.core.memory.MemoryContext.RecalledTurn;
import app.orrery.core.memory.MemoryContext.Resolution;
import app.orrery.core.store.FtsQuery;
import app.orrery.core.store.Store;
import app.orrery.core.store.Store.ClaimRow;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.store.Store.TurnRow;
import app.orrery.core.understand.DayRange;
import app.orrery.core.understand.TemporalResolver;
import app.orrery.core.understand.TimeRef;
import app.orrery.core.understand.TurnIntent;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the past turns a message refers to, before the model is called.
 *
 * <ol>
 *   <li>Time phrases ("last Tuesday") give ranked candidate dates.</li>
 *   <li>Each candidate is searched in order; with topic words ("Postgres hosting") the
 *       first candidate where the topic appears wins, without them every turn that day
 *       counts ("What did I ask you on 3 November?").</li>
 *   <li>If the date has nothing on the topic but another date does, that turn is used
 *       and the mismatch is reported instead of guessing.</li>
 *   <li>Nothing found: the model is told so, with the nearest days that do have history,
 *       so it can say "I have no record of that" instead of inventing one.</li>
 * </ol>
 */
public final class MemoryRecall {

    private static final int MAX_TURNS = 6;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    private final Store store;

    public MemoryRecall(Store store) {
        this.store = store;
    }

    public record Request(
            String userId,
            TurnIntent intent,
            List<TimeRef> refs,
            List<String> topicTerms,
            ZonedDateTime now,
            String currentConversationId,
            List<String> excludeUserMessageIds,
            String recheckAssistantMessageId) {}

    public MemoryContext recall(Request r) {
        List<String> exclude = r.excludeUserMessageIds() == null ? List.of() : r.excludeUserMessageIds();
        LocalDate today = r.now().toLocalDate();

        // "Re-check" button: the exact past answer is known; no guessing needed.
        if (r.recheckAssistantMessageId() != null) {
            TurnRow turn = store.turnByAssistantMessage(r.userId(), r.recheckAssistantMessageId());
            if (turn != null) {
                List<RecalledTurn> turns = enrich(List.of(turn));
                return new MemoryContext(true, r.refs(), List.of(), turns, List.of(), null, Map.of(),
                        store.historyBounds(r.userId(), exclude), List.of(), turn.localDate(), true,
                        summary("found", r.refs(), turns, null));
            }
        }

        if (!r.intent().needsMemory()) {
            List<RecalledTurn> related = related(r, exclude);
            return new MemoryContext(false, r.refs(), List.of(), List.of(), related, null, Map.of(), null,
                    store.conversationIndex(r.userId(), r.currentConversationId(), 10), changeSince(r, List.of()), false, null);
        }

        String fts = FtsQuery.build(r.topicTerms());
        List<TimeRef> backRefs = lookingBack(r.refs());
        List<Resolution> resolutions = new ArrayList<>();
        Map<String, TurnRow> hits = new LinkedHashMap<>();
        boolean byRelevance = false; // topic-only matches keep their ranking; a day's listing reads oldest first
        String mismatch = null;
        Map<LocalDate, Integer> nearby = Map.of();

        for (TimeRef ref : backRefs) {
            DayRange chosen = null;
            for (DayRange cand : ref.candidates()) {
                List<TurnRow> rows = fts.isEmpty()
                        ? store.turnsInRange(r.userId(), cand.start(), cand.end(), exclude, 20)
                        : strongest(store.searchTurns(r.userId(), fts, cand.start(), cand.end(), exclude, MAX_TURNS));
                if (!rows.isEmpty()) {
                    chosen = cand;
                    for (TurnRow row : rows) hits.putIfAbsent(row.userMessageId(), row);
                    break;
                }
            }
            resolutions.add(new Resolution(ref.phrase(), ref.label(), chosen, ref.candidates()));
        }

        if (hits.isEmpty() && !backRefs.isEmpty()) {
            DayRange asked = backRefs.get(0).preferred();
            if (!fts.isEmpty()) {
                List<TurnRow> anywhere = strongest(recencyWeighted(store.searchTurns(r.userId(), fts, null, null, exclude, 5), today));
                if (!anywhere.isEmpty()) {
                    byRelevance = true;
                    for (TurnRow row : anywhere.subList(0, Math.min(2, anywhere.size()))) hits.putIfAbsent(row.userMessageId(), row);
                    List<String> dates = hits.values().stream().map(t -> SHORT_DAY.format(t.localDate())).distinct().toList();
                    mismatch = "No matching conversation on " + TemporalResolver.formatRange(asked)
                            + ". The closest match on this topic is from " + String.join(", ", dates) + ".";
                }
            }
            if (hits.isEmpty()) {
                nearby = store.turnCountsByDay(r.userId(), asked.start().minusDays(7), asked.end().plusDays(7), exclude);
            }
        }

        if (backRefs.isEmpty()) {
            List<TurnRow> rows = fts.isEmpty() ? List.of() : strongest(recencyWeighted(store.searchTurns(r.userId(), fts, null, null, exclude, 5), today));
            byRelevance = !rows.isEmpty();
            if (rows.isEmpty()) rows = store.recentTurns(r.userId(), r.currentConversationId(), 3);
            for (TurnRow row : rows) hits.putIfAbsent(row.userMessageId(), row);
        }

        List<TurnRow> ordered = new ArrayList<>(hits.values());
        if (ordered.size() > MAX_TURNS) ordered = new ArrayList<>(ordered.subList(0, MAX_TURNS));
        if (!byRelevance) ordered.sort(Comparator.comparing(TurnRow::createdAt));
        List<RecalledTurn> turns = enrich(ordered);

        String status = turns.isEmpty() ? "none" : mismatch != null ? "mismatch" : "found";
        String note = mismatch;
        if (turns.isEmpty() && !backRefs.isEmpty()) {
            note = "No conversation found for " + backRefs.get(0).label() + ".";
        } else if (turns.isEmpty()) {
            note = "No earlier conversation matches this.";
        }

        return new MemoryContext(true, r.refs(), resolutions, turns, List.of(), mismatch, nearby,
                store.historyBounds(r.userId(), exclude),
                store.conversationIndex(r.userId(), r.currentConversationId(), 10),
                changeSince(r, turns), false, summary(status, r.refs(), turns, note));
    }

    /** Past-looking phrases; "today" only counts when it is the only reference. */
    private static List<TimeRef> lookingBack(List<TimeRef> refs) {
        List<TimeRef> past = refs.stream().filter(t -> t.direction() == TimeRef.Direction.PAST && !t.since()).toList();
        if (!past.isEmpty()) return past;
        return refs.stream().filter(t -> t.direction() == TimeRef.Direction.PRESENT && !t.since()).toList();
    }

    private static LocalDate changeSince(Request r, List<RecalledTurn> turns) {
        for (TimeRef ref : r.refs()) if (ref.since()) return ref.preferred().start();
        if ((r.intent().sinceThen() || r.intent().verify()) && !turns.isEmpty()) {
            return turns.stream().map(t -> t.turn().localDate()).min(Comparator.naturalOrder()).orElse(null);
        }
        return null;
    }

    /** Same topic, no explicit reference: offered to the model as optional context. */
    private List<RecalledTurn> related(Request r, List<String> exclude) {
        if (r.topicTerms().size() < 2) return List.of();
        String fts = FtsQuery.build(r.topicTerms());
        if (fts.isEmpty()) return List.of();
        List<TurnRow> rows = store.searchTurns(r.userId(), fts, null, null, exclude, 3);
        List<TurnRow> strong = rows.stream().filter(t -> overlap(t, r.topicTerms()) >= 2).limit(2).toList();
        return enrich(strong);
    }

    static int overlap(TurnRow t, List<String> terms) {
        String hay = (t.question() + " " + (t.answer() == null ? "" : t.answer()) + " " + String.join(" ", t.entities()))
                .toLowerCase(Locale.ROOT);
        int n = 0;
        for (String term : terms) {
            String stem = term.length() > 5 ? term.substring(0, term.length() - 2) : term;
            if (hay.contains(stem.toLowerCase(Locale.ROOT))) n++;
        }
        return n;
    }

    /** Drops weak full-text matches: anything scoring under half of the best match. */
    static List<TurnRow> strongest(List<TurnRow> rows) {
        if (rows.size() < 2) return rows;
        double top = rows.stream().mapToDouble(TurnRow::score).max().orElse(0);
        if (top <= 0) return rows;
        return rows.stream().filter(t -> t.score() >= top * 0.5).toList();
    }

    private static List<TurnRow> recencyWeighted(List<TurnRow> rows, LocalDate today) {
        List<TurnRow> out = new ArrayList<>();
        for (TurnRow t : rows) {
            long age = Math.max(0, ChronoUnit.DAYS.between(t.localDate(), today));
            out.add(t.withScore(t.score() / (1.0 + age / 30.0)));
        }
        out.sort(Comparator.comparingDouble(TurnRow::score).reversed());
        return out;
    }

    private List<RecalledTurn> enrich(List<TurnRow> rows) {
        List<String> answerIds = rows.stream().map(TurnRow::assistantMessageId).filter(id -> id != null).toList();
        Map<String, List<ClaimRow>> claims = store.claimsFor(answerIds);
        Map<String, List<SourceRow>> sources = store.sourcesFor(answerIds);
        List<RecalledTurn> out = new ArrayList<>();
        for (TurnRow t : rows) {
            String id = t.assistantMessageId();
            List<SourceRow> cited = id == null ? List.of()
                    : sources.getOrDefault(id, List.of()).stream().filter(SourceRow::cited).toList();
            out.add(new RecalledTurn(t, localTime(t), id == null ? List.of() : claims.getOrDefault(id, List.of()), cited));
        }
        return out;
    }

    static String localTime(TurnRow t) {
        try {
            return TIME.format(ZonedDateTime.ofInstant(Instant.parse(t.createdAt()), ZoneId.of(t.timezone())));
        } catch (DateTimeException | NullPointerException e) {
            return "";
        }
    }

    private static RecallSummary summary(String status, List<TimeRef> refs, List<RecalledTurn> turns, String note) {
        List<Reference> references = refs.stream().map(t -> new Reference(t.phrase(), t.label())).toList();
        List<RecallHit> hits = turns.stream().map(rt -> {
            TurnRow t = rt.turn();
            String label = SHORT_DAY.format(t.localDate()) + (rt.localTime().isEmpty() ? "" : ", " + rt.localTime());
            String title = t.conversationTitle() == null || t.conversationTitle().isBlank() ? null : t.conversationTitle();
            return new RecallHit(t.conversationId(), t.userMessageId(), title, t.localDate().toString(), label,
                    t.question(), rt.claims().size());
        }).toList();
        return new RecallSummary(status, references, hits, note);
    }
}
