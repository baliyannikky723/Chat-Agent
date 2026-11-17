package app.orrery.core.memory;

import app.orrery.core.memory.MemoryContext.RecalledTurn;
import app.orrery.core.memory.MemoryContext.Resolution;
import app.orrery.core.store.Store.ClaimRow;
import app.orrery.core.store.Store.IndexEntry;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.store.Store.TurnRow;
import app.orrery.core.understand.TemporalResolver;
import app.orrery.core.understand.TimeRef;
import app.orrery.core.util.Citations;
import app.orrery.core.util.Text;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders what memory found as a section of the system prompt. The wording matters:
 * the model is told this is the complete record, so it says "I have no record of that"
 * rather than inventing a past conversation.
 */
public final class MemoryRenderer {

    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final int ANSWER_CHARS = 1400;
    private static final int RELATED_CHARS = 600;

    private MemoryRenderer() {}

    public static String render(MemoryContext ctx) {
        StringBuilder sb = new StringBuilder();

        if (!ctx.refs().isEmpty()) {
            sb.append("Time references in this message:\n");
            for (TimeRef ref : ctx.refs()) {
                sb.append("- \"").append(ref.phrase()).append("\" → ").append(ref.label());
                if (ref.candidates().size() > 1 && !ref.since()) {
                    sb.append(" (also possible: ")
                            .append(String.join(", ", ref.candidates().subList(1, ref.candidates().size()).stream()
                                    .map(TemporalResolver::formatRange).toList()))
                            .append(')');
                }
                sb.append('\n');
            }
            for (Resolution res : ctx.resolutions()) {
                if (res.chosen() != null && !res.chosen().equals(res.tried().get(0))) {
                    sb.append("- \"").append(res.phrase()).append("\" matched a conversation on ")
                            .append(TemporalResolver.formatRange(res.chosen())).append(", not ")
                            .append(TemporalResolver.formatRange(res.tried().get(0))).append(".\n");
                }
            }
            sb.append('\n');
        }

        if (ctx.needed()) {
            sb.append("## Memory\n");
            if (ctx.recheck()) {
                sb.append("The user pressed \"Re-check\" on this past answer. Re-verify it against the live web.\n\n");
            }
            if (ctx.found()) {
                if (ctx.mismatch() != null) sb.append(ctx.mismatch()).append(" Tell the user about the date difference.\n\n");
                sb.append("Matching past conversations. This is everything you can recall about them:\n\n");
                for (RecalledTurn rt : ctx.turns()) appendTurn(sb, rt, ANSWER_CHARS, true);
            } else {
                appendNothingFound(sb, ctx);
            }
            if (ctx.changeSince() != null) {
                sb.append("Change window: since ").append(TemporalResolver.formatDay(ctx.changeSince()))
                        .append(". When searching for changes, pass since=\"").append(ctx.changeSince()).append("\".\n\n");
            }
        } else if (!ctx.related().isEmpty()) {
            sb.append("## Possibly related past conversations\n");
            sb.append("Mention one only if it genuinely helps (for example: \"Last week you were comparing these\").\n\n");
            for (RecalledTurn rt : ctx.related()) appendTurn(sb, rt, RELATED_CHARS, false);
        }

        if (!ctx.index().isEmpty() && (!ctx.needed() || !ctx.found())) {
            sb.append("## Recent conversations (titles only)\n");
            for (IndexEntry e : ctx.index()) {
                sb.append("- ").append(SHORT_DAY.format(e.date())).append(" — ").append(e.title());
                if (!e.entities().isEmpty()) sb.append(" (").append(String.join(", ", e.entities())).append(')');
                sb.append('\n');
            }
            sb.append("You only have these titles. If the user seems to mean one of them, say which and ask for details you lack.\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private static void appendTurn(StringBuilder sb, RecalledTurn rt, int answerChars, boolean withFacts) {
        TurnRow t = rt.turn();
        sb.append("### ").append(TemporalResolver.formatDay(t.localDate()));
        if (!rt.localTime().isEmpty()) sb.append(", ").append(rt.localTime());
        if (t.conversationTitle() != null && !t.conversationTitle().isBlank()) {
            sb.append(" — \"").append(t.conversationTitle()).append('"');
        }
        sb.append('\n');
        sb.append("User asked: \"").append(Text.truncate(Text.collapse(t.question()), 600)).append("\"\n");
        if (t.answer() != null && !t.answer().isBlank()) {
            Map<Integer, String> domains = new HashMap<>();
            for (SourceRow s : rt.sources()) domains.put(s.n(), s.domain());
            String answer = Text.truncate(Citations.rewriteToDomains(t.answer(), domains).strip(), answerChars);
            sb.append("You answered:\n").append(quote(answer)).append('\n');
        } else {
            sb.append("(No answer was saved for this question.)\n");
        }
        if (withFacts) {
            if (!rt.claims().isEmpty()) {
                LocalDate asOf = rt.claims().get(0).asOfLocal();
                sb.append("Facts you stated then (as of ").append(TemporalResolver.formatDay(asOf)).append("):\n");
                for (ClaimRow c : rt.claims()) {
                    sb.append("- ").append(c.entity()).append(" · ").append(c.attribute()).append(": ").append(c.value());
                    String domain = domainOf(c.sourceUrl());
                    if (domain != null) sb.append(" (").append(domain).append(')');
                    sb.append('\n');
                }
            }
            if (!t.entities().isEmpty()) sb.append("Covered: ").append(String.join(", ", t.entities())).append('\n');
        }
        sb.append('\n');
    }

    private static void appendNothingFound(StringBuilder sb, MemoryContext ctx) {
        if (!ctx.resolutions().isEmpty()) {
            sb.append("No conversation with this user was found for ")
                    .append(String.join("; ", ctx.resolutions().stream().map(Resolution::label).toList())).append(".\n");
        } else {
            sb.append("No earlier conversation with this user matches this message.\n");
        }
        if (!ctx.nearby().isEmpty()) {
            sb.append("Nearby days that do have conversations: ");
            sb.append(String.join(", ", ctx.nearby().entrySet().stream()
                    .map(e -> SHORT_DAY.format(e.getKey()) + " (" + e.getValue() + (e.getValue() == 1 ? " question" : " questions") + ")")
                    .toList()));
            sb.append(".\n");
        }
        if (ctx.bounds() == null || ctx.bounds().turns() == 0) {
            sb.append("You have no earlier conversations with this user at all.\n");
        } else {
            sb.append("Your history with this user spans ").append(SHORT_DAY.format(ctx.bounds().first())).append(" to ")
                    .append(SHORT_DAY.format(ctx.bounds().last())).append(" (").append(ctx.bounds().turns())
                    .append(ctx.bounds().turns() == 1 ? " question" : " questions").append(").\n");
        }
        sb.append("Do not guess what was said. Say plainly, in one sentence, that you have no record of it. "
                + "Then be useful: ask for the missing detail, or answer the underlying question fresh if it stands on its own "
                + "(and say that is what you did).\n\n");
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) sb.append("> ").append(line).append('\n');
        return sb.toString();
    }

    static String domainOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = java.net.URI.create(url.strip()).getHost();
            if (host == null) return null;
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
