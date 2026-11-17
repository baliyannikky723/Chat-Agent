package app.orrery.core.agent;

import app.orrery.core.agent.TurnOrchestrator.TurnInput;
import app.orrery.core.agent.TurnOrchestrator.TurnOutcome;
import app.orrery.core.events.AgentEvent;
import app.orrery.core.events.EventSink;
import app.orrery.core.jobs.JobRunner;
import app.orrery.core.store.Store;
import app.orrery.core.store.Store.UserRow;
import app.orrery.core.time.AgentClock;
import app.orrery.core.understand.TemporalResolver;
import app.orrery.core.util.CancelToken;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Demo tool (DEMO_TOOLS=true): creates real, backdated history so the three example
 * questions from the brief can be asked straight away. Each scenario runs through the
 * actual agent — real searches, real answers — with "now" set to a past date.
 */
public final class DemoSeeder {

    public record Scenario(LocalDate date, LocalTime time, String question) {}

    private final TurnOrchestrator orchestrator;
    private final Store store;
    private final AgentClock clock;
    private final JobRunner jobs;

    public DemoSeeder(TurnOrchestrator orchestrator, Store store, AgentClock clock, JobRunner jobs) {
        this.orchestrator = orchestrator;
        this.store = store;
        this.clock = clock;
        this.jobs = jobs;
    }

    /** Monday this week, 3 November, and last Tuesday — relative to the user's today. */
    public static List<Scenario> scenarios(LocalDate today) {
        List<Scenario> out = new ArrayList<>();
        LocalDate monday = today.getDayOfWeek() == DayOfWeek.MONDAY ? today.minusWeeks(1) : today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        LocalDate tuesday = today.with(TemporalAdjusters.previous(DayOfWeek.TUESDAY));
        LocalDate nov3 = LocalDate.of(today.getYear(), 11, 3);
        if (!nov3.isBefore(today)) nov3 = nov3.minusYears(1);
        out.add(new Scenario(nov3, LocalTime.of(11, 5),
                "What is the latest LTS version of Node.js, and when does it reach end of life?"));
        out.add(new Scenario(monday, LocalTime.of(14, 30),
                "Help me shortlist 5 CRM tools for a 20-person sales team. Include current pricing for each."));
        out.add(new Scenario(tuesday, LocalTime.of(18, 40),
                "What are the best managed Postgres hosting options for a small SaaS, and what do they cost?"));
        out.sort(Comparator.comparing(Scenario::date));
        return out;
    }

    public int run(String userId, EventSink progress, CancelToken cancel) throws InterruptedException {
        UserRow user = store.user(userId);
        ZoneId zone = TemporalResolver.zonedNow(Instant.EPOCH, user == null ? "UTC" : user.timezone()).getZone();
        LocalDate today = TemporalResolver.zonedNow(clock.now(user == null ? 0 : user.clockOffsetMillis()), zone.getId()).toLocalDate();
        int done = 0;
        for (Scenario s : scenarios(today)) {
            if (cancel.isCancelled()) break;
            Instant at = s.date().atTime(s.time()).atZone(zone).toInstant();
            progress.emit(new AgentEvent.Status("seeding", TemporalResolver.formatDay(s.date()) + ": " + s.question()));
            TurnOutcome outcome = orchestrator.run(new TurnInput(userId, null, s.question(), null, at), EventSink.NOOP, cancel);
            if (outcome.ok()) done++;
            else progress.emit(new AgentEvent.Warning("Could not create the conversation for " + TemporalResolver.formatDay(s.date())));
        }
        progress.emit(new AgentEvent.Status("seeding", "Saving the facts from each answer"));
        jobs.awaitIdle(90_000);
        return done;
    }
}
