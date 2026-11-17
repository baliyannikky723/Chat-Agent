package app.orrery.web;

import app.orrery.core.AgentRuntime;
import app.orrery.core.store.Store;
import app.orrery.core.store.Store.ClaimRow;
import app.orrery.core.store.Store.TimelineRow;
import app.orrery.core.understand.TemporalResolver;
import app.orrery.web.ApiDtos.LedgerEntryDto;
import app.orrery.web.ApiDtos.LedgerPointDto;
import app.orrery.web.ApiDtos.OkDto;
import app.orrery.web.ApiDtos.TimelineDayDto;
import app.orrery.web.ApiDtos.TimelineTurnDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What Orrery remembers: questions by day, and the ledger of facts it has stated. */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final AgentRuntime runtime;
    private final Store store;

    public MemoryController(AgentRuntime runtime) {
        this.runtime = runtime;
        this.store = runtime.store();
    }

    @GetMapping("/timeline")
    public List<TimelineDayDto> timeline(@RequestParam(name = "days", defaultValue = "180") int days, HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        var row = store.user(user.id());
        long offset = row == null ? 0 : row.clockOffsetMillis();
        LocalDate today = TemporalResolver.zonedNow(runtime.clock().now(offset), user.timezone()).toLocalDate();
        LocalDate since = today.minusDays(Math.max(1, Math.min(days, 3650)));
        Map<LocalDate, List<TimelineTurnDto>> byDay = new LinkedHashMap<>();
        for (TimelineRow r : store.timeline(user.id(), since)) {
            byDay.computeIfAbsent(r.localDate(), d -> new ArrayList<>()).add(new TimelineTurnDto(r.conversationId(), r.conversationTitle(),
                    r.userMessageId(), r.assistantMessageId(), r.question(), r.localTime(), r.claims()));
        }
        List<TimelineDayDto> out = new ArrayList<>();
        byDay.forEach((date, turns) -> out.add(new TimelineDayDto(date.toString(), TemporalResolver.formatDay(date), turns)));
        return out;
    }

    @GetMapping("/ledger")
    public List<LedgerEntryDto> ledger(HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        Map<String, LedgerEntryDto> grouped = new LinkedHashMap<>();
        for (ClaimRow c : store.ledger(user.id(), 800)) {
            String key = c.entity().toLowerCase(Locale.ROOT) + "\u0000" + c.attribute().toLowerCase(Locale.ROOT);
            LedgerEntryDto entry = grouped.computeIfAbsent(key, k -> new LedgerEntryDto(c.entity(), c.attribute(), new ArrayList<>()));
            entry.history().add(new LedgerPointDto(c.value(), c.statement(), c.asOf(), c.asOfLocal().toString(), c.sourceUrl(),
                    c.conversationId(), c.messageId()));
        }
        return new ArrayList<>(grouped.values());
    }

    @DeleteMapping
    public OkDto forgetEverything(HttpServletRequest request) {
        store.deleteUserData(UserContext.of(request).id());
        return new OkDto(true);
    }
}
