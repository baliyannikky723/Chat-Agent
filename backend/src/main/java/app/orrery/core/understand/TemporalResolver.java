package app.orrery.core.understand;

import app.orrery.core.understand.TimeRef.Direction;
import app.orrery.core.understand.TimeRef.Kind;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns time phrases into calendar dates in the user's timezone.
 *
 * <pre>
 *   "On Monday we shortlisted…"          → Mon 9 Nov 2026
 *   "Last Tuesday I asked you…"          → Tue 10 Nov 2026, then Tue 3 Nov 2026
 *   "What did I ask you on 3 November?"  → Tue 3 Nov 2026
 * </pre>
 *
 * Why code and not the model: language models are unreliable at calendar arithmetic,
 * this runs in microseconds, and it is unit-tested. Ambiguous phrases return several
 * ranked candidates; the memory layer keeps the one where the topic actually appears.
 */
public final class TemporalResolver {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("january", 1), Map.entry("feb", 2), Map.entry("february", 2),
            Map.entry("mar", 3), Map.entry("march", 3), Map.entry("apr", 4), Map.entry("april", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("june", 6), Map.entry("jul", 7), Map.entry("july", 7),
            Map.entry("aug", 8), Map.entry("august", 8), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("september", 9), Map.entry("oct", 10), Map.entry("october", 10), Map.entry("nov", 11),
            Map.entry("november", 11), Map.entry("dec", 12), Map.entry("december", 12));
    private static final String MONTH_RE =
            "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?";

    private static final Map<String, Integer> WEEKDAYS = Map.ofEntries(
            Map.entry("monday", 1), Map.entry("mon", 1), Map.entry("tuesday", 2), Map.entry("tue", 2),
            Map.entry("tues", 2), Map.entry("wednesday", 3), Map.entry("wed", 3), Map.entry("thursday", 4),
            Map.entry("thu", 4), Map.entry("thur", 4), Map.entry("thurs", 4), Map.entry("friday", 5),
            Map.entry("fri", 5), Map.entry("saturday", 6), Map.entry("sat", 6), Map.entry("sunday", 7),
            Map.entry("sun", 7));
    private static final String WEEKDAY_FULL_RE = "monday|tuesday|wednesday|thursday|friday|saturday|sunday";
    private static final String WEEKDAY_ANY_RE = WEEKDAY_FULL_RE + "|mon|tues?|wed|thu(?:rs?)?|fri|sat|sun";

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12), Map.entry("couple", 2), Map.entry("couple of", 2), Map.entry("few", 3));

    private static final Pattern PAST_CUES = Pattern.compile(
            "\\b(did|was|were|had|asked|told|said|talked|discussed|mentioned|shortlisted|looked|compared|went|ago|earlier|previously|before|recommended|suggested|picked|chose|decided|covered|reviewed|searched|gave|found|answered|replied|shared|showed|wrote|planned|bought|signed|booked)\\b",
            FLAGS);
    private static final Pattern FUTURE_CUES =
            Pattern.compile("\\b(will|going to|gonna|upcoming|coming|next|plan(ning)? to|schedule[ds]?|tomorrow|soon|later this)\\b", FLAGS);
    private static final Pattern SINCE_LEAD = Pattern.compile("\\b(since|after)\\s+$", FLAGS);

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private record Ctx(LocalDate today, Direction sentence, String locale) {
        boolean past() {
            return sentence == Direction.PAST;
        }
    }

    private record Partial(Kind kind, List<DayRange> candidates, Direction direction) {}

    @FunctionalInterface
    private interface Handler {
        Partial handle(Matcher m, Ctx ctx);
    }

    private record Rule(Pattern pattern, Handler handler) {}

    private static final List<Rule> RULES = buildRules();

    private TemporalResolver() {}

    /**
     * @param preferPast memory questions look backwards: a bare "Monday" means the last one
     */
    public static List<TimeRef> resolve(String text, ZonedDateTime now, String locale, boolean preferPast) {
        if (text == null || text.isEmpty()) return List.of();
        LocalDate today = now.toLocalDate();
        String loc = locale == null ? "en-GB" : locale;
        Ctx ctx = new Ctx(today, sentenceDirection(text, preferPast), loc);
        // "since Monday" always looks back, whatever the rest of the sentence says.
        Ctx pastCtx = new Ctx(today, Direction.PAST, loc);
        boolean[] consumed = new boolean[text.length()];
        List<TimeRef> refs = new ArrayList<>();

        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (end <= start || overlaps(consumed, start, end)) continue;
                boolean sinceLead = SINCE_LEAD.matcher(text.substring(Math.max(0, start - 8), start)).find();
                Partial p = rule.handler().handle(m, sinceLead ? pastCtx : ctx);
                if (p == null || p.candidates().isEmpty()) continue;
                for (int i = start; i < end; i++) consumed[i] = true;
                String phrase = text.substring(start, end).strip();
                refs.add(new TimeRef(phrase, start, p.kind(), List.copyOf(p.candidates()), p.direction(), false,
                        formatRange(p.candidates().get(0))));
            }
        }

        // "since Monday" / "after 3 Nov": the window runs from that date until today.
        List<TimeRef> out = new ArrayList<>(refs.size());
        for (TimeRef ref : refs) {
            int windowStart = Math.max(0, ref.index() - 8);
            String before = text.substring(windowStart, ref.index());
            Matcher lead = SINCE_LEAD.matcher(before);
            boolean leadFound = lead.find();
            boolean inPhrase = ref.phrase().toLowerCase(Locale.ROOT).startsWith("since ");
            if (!leadFound && !inPhrase) {
                out.add(ref);
                continue;
            }
            List<DayRange> widened = new ArrayList<>();
            for (DayRange c : ref.candidates()) {
                widened.add(new DayRange(c.start(), c.start().isAfter(today) ? c.start() : today));
            }
            String phrase = ref.phrase();
            int index = ref.index();
            if (leadFound) {
                index = windowStart + lead.start();
                phrase = text.substring(index, ref.index() + ref.phrase().length());
            }
            out.add(new TimeRef(phrase, index, Kind.RANGE, List.copyOf(widened), Direction.PAST, true,
                    "since " + formatDay(widened.get(0).start())));
        }
        out.sort(Comparator.comparingInt(TimeRef::index));
        return List.copyOf(out);
    }

    public static ZonedDateTime zonedNow(java.time.Instant now, String timeZone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (DateTimeException | NullPointerException e) {
            zone = ZoneId.of("UTC");
        }
        return ZonedDateTime.ofInstant(now, zone);
    }

    public static String formatDay(LocalDate d) {
        return DAY_LABEL.format(d);
    }

    public static String formatRange(DayRange r) {
        LocalDate a = r.start();
        LocalDate b = r.end();
        if (a.equals(b)) return formatDay(a);
        if (a.getDayOfMonth() == 1 && a.getYear() == b.getYear() && a.getMonth() == b.getMonth() && b.getDayOfMonth() == b.lengthOfMonth()) {
            return MONTH_LABEL.format(a);
        }
        if (a.getDayOfYear() == 1 && b.getMonthValue() == 12 && b.getDayOfMonth() == 31 && a.getYear() == b.getYear()) {
            return String.valueOf(a.getYear());
        }
        if (a.getYear() == b.getYear() && a.getMonth() == b.getMonth()) {
            return a.getDayOfMonth() + "–" + DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(b);
        }
        if (a.getYear() == b.getYear()) {
            return DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(a) + " – "
                    + DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(b);
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        return f.format(a) + " – " + f.format(b);
    }

    static Direction sentenceDirection(String text, boolean preferPast) {
        if (preferPast) return Direction.PAST;
        if (PAST_CUES.matcher(text).find()) return Direction.PAST;
        if (FUTURE_CUES.matcher(text).find()) return Direction.FUTURE;
        return Direction.FUTURE;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static boolean overlaps(boolean[] consumed, int start, int end) {
        for (int i = start; i < end; i++) if (consumed[i]) return true;
        return false;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Direction dirOf(LocalDate d, LocalDate today) {
        return d.isBefore(today) ? Direction.PAST : d.isAfter(today) ? Direction.FUTURE : Direction.PRESENT;
    }

    private static Partial day(LocalDate d, LocalDate today) {
        return new Partial(Kind.DAY, List.of(DayRange.of(d)), dirOf(d, today));
    }

    /** Year for a day+month written without one: the most recent past one when looking back. */
    private static LocalDate pickYear(Ctx ctx, int month, int dayOfMonth) {
        LocalDate thisYear = safeDate(ctx.today().getYear(), month, dayOfMonth);
        if (thisYear == null) return null;
        if (ctx.past() && thisYear.isAfter(ctx.today())) return safeDate(ctx.today().getYear() - 1, month, dayOfMonth);
        return thisYear;
    }

    private static DayRange monthRange(Ctx ctx, int month, Integer year) {
        int y = year != null ? year : ctx.today().getYear();
        if (year == null && ctx.past() && month > ctx.today().getMonthValue()) y -= 1;
        LocalDate start = safeDate(y, month, 1);
        if (start == null) return null;
        return new DayRange(start, start.with(TemporalAdjusters.lastDayOfMonth()));
    }

    private static Integer month(String raw) {
        return MONTHS.get(raw.toLowerCase(Locale.ROOT).replace(".", ""));
    }

    private static Integer count(String raw) {
        String key = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if (!key.equals("a") && !key.equals("an")) key = key.replaceFirst("^a ", "");
        Integer word = NUMBER_WORDS.get(key);
        if (word != null) return word;
        try {
            int n = Integer.parseInt(key);
            return n > 0 && n < 1000 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ── rules, most specific first ─────────────────────────────────────────

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        // 2026-11-03
        rules.add(new Rule(Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b"), (m, ctx) -> {
            LocalDate d = safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            return d == null ? null : day(d, ctx.today());
        }));

        // 3 November, 3rd of Nov, 3 Nov 2025
        rules.add(new Rule(Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+(" + MONTH_RE + ")\\.?(?:,?\\s+(\\d{4}))?\\b", FLAGS),
                (m, ctx) -> {
                    Integer mo = month(m.group(2));
                    if (mo == null) return null;
                    int dom = Integer.parseInt(m.group(1));
                    LocalDate d = m.group(3) != null ? safeDate(Integer.parseInt(m.group(3)), mo, dom) : pickYear(ctx, mo, dom);
                    return d == null ? null : day(d, ctx.today());
                }));

        // November 3, Nov 3rd, November 3, 2025 (not "May 5%" or "Jan 3:00")
        rules.add(new Rule(Pattern.compile("\\b(" + MONTH_RE + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b(?!\\s*(?:%|am\\b|pm\\b|:))", FLAGS),
                (m, ctx) -> {
                    Integer mo = month(m.group(1));
                    if (mo == null) return null;
                    int dom = Integer.parseInt(m.group(2));
                    LocalDate d = m.group(3) != null ? safeDate(Integer.parseInt(m.group(3)), mo, dom) : pickYear(ctx, mo, dom);
                    return d == null ? null : day(d, ctx.today());
                }));

        // 03/11/2026 always; 03/11 only after on/since/from/before/after/by ("24/7 support" stays text)
        rules.add(new Rule(Pattern.compile("\\b(?:(on|since|from|before|after|by)\\s+)?(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{4}|\\d{2}))?\\b", FLAGS),
                (m, ctx) -> {
                    if (m.group(1) == null && m.group(4) == null) return null;
                    int a = Integer.parseInt(m.group(2));
                    int b = Integer.parseInt(m.group(3));
                    String loc = ctx.locale().toLowerCase(Locale.ROOT);
                    boolean monthFirst = loc.startsWith("en-us") || loc.equals("en");
                    int dd = monthFirst ? b : a;
                    int mm = monthFirst ? a : b;
                    if (mm > 12 && dd <= 12) {
                        int t = dd;
                        dd = mm;
                        mm = t;
                    }
                    if (mm < 1 || mm > 12) return null;
                    Integer year = null;
                    if (m.group(4) != null) year = m.group(4).length() == 2 ? 2000 + Integer.parseInt(m.group(4)) : Integer.parseInt(m.group(4));
                    LocalDate d = year != null ? safeDate(year, mm, dd) : pickYear(ctx, mm, dd);
                    return d == null ? null : day(d, ctx.today());
                }));

        // Monday of last week / last week's Monday
        rules.add(new Rule(Pattern.compile("\\b(?:(" + WEEKDAY_FULL_RE + ")\\s+(?:of\\s+)?last\\s+week|last\\s+week'?s?\\s+(" + WEEKDAY_FULL_RE + "))\\b", FLAGS),
                (m, ctx) -> {
                    String w = m.group(1) != null ? m.group(1) : m.group(2);
                    int wd = WEEKDAYS.get(w.toLowerCase(Locale.ROOT));
                    LocalDate d = weekStart(ctx.today()).minusWeeks(1).plusDays(wd - 1L);
                    return new Partial(Kind.DAY, List.of(DayRange.of(d)), Direction.PAST);
                }));

        rules.add(new Rule(Pattern.compile("\\b(?:the\\s+)?day\\s+before\\s+yesterday\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.DAY, List.of(DayRange.of(ctx.today().minusDays(2))), Direction.PAST)));
        rules.add(new Rule(Pattern.compile("\\b(?:the\\s+)?day\\s+after\\s+tomorrow\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.DAY, List.of(DayRange.of(ctx.today().plusDays(2))), Direction.FUTURE)));
        rules.add(new Rule(Pattern.compile("\\b(?:yesterday|last\\s+night)\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.DAY, List.of(DayRange.of(ctx.today().minusDays(1))), Direction.PAST)));
        rules.add(new Rule(Pattern.compile("\\b(?:earlier\\s+today|today|tonight|this\\s+(?:morning|afternoon|evening))\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.DAY, List.of(DayRange.of(ctx.today())), Direction.PRESENT)));
        rules.add(new Rule(Pattern.compile("\\btomorrow\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.DAY, List.of(DayRange.of(ctx.today().plusDays(1))), Direction.FUTURE)));

        // 3 days ago, a couple of weeks ago, a few days ago
        rules.add(new Rule(Pattern.compile(
                "\\b(a\\s+few|a\\s+couple(?:\\s+of)?|couple(?:\\s+of)?|few|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d{1,3})\\s+(day|week|month|year)s?\\s+ago\\b",
                FLAGS), (m, ctx) -> {
            String raw = m.group(1).toLowerCase(Locale.ROOT);
            boolean fuzzy = raw.contains("few") || raw.contains("couple");
            Integer n = count(raw);
            if (n == null) return null;
            LocalDate t = ctx.today();
            switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "day" -> {
                    if (fuzzy) {
                        return new Partial(Kind.RANGE, List.of(n == 2 ? new DayRange(t.minusDays(3), t.minusDays(1)) : new DayRange(t.minusDays(6), t.minusDays(2))), Direction.PAST);
                    }
                    LocalDate d = t.minusDays(n);
                    return new Partial(Kind.DAY, List.of(DayRange.of(d), new DayRange(d.minusDays(1), d.plusDays(1))), Direction.PAST);
                }
                case "week" -> {
                    LocalDate c = t.minusWeeks(n);
                    int spread = fuzzy ? 7 : 3;
                    return new Partial(Kind.RANGE, List.of(new DayRange(c.minusDays(spread), c.plusDays(spread)), new DayRange(c.minusDays(10), c.plusDays(10))), Direction.PAST);
                }
                case "month" -> {
                    LocalDate c = t.minusMonths(n);
                    return new Partial(Kind.RANGE, List.of(
                            new DayRange(c.withDayOfMonth(1), c.with(TemporalAdjusters.lastDayOfMonth())),
                            new DayRange(c.minusDays(20), c.plusDays(20))), Direction.PAST);
                }
                default -> {
                    LocalDate c = t.minusYears(n);
                    return new Partial(Kind.RANGE, List.of(new DayRange(c.withDayOfYear(1), c.with(TemporalAdjusters.lastDayOfYear()))), Direction.PAST);
                }
            }
        }));

        // last Tuesday, this Friday, next Monday, on Monday, Monday (morning)
        rules.add(new Rule(Pattern.compile("\\b(?:(last|this|past|previous|next|coming|on)\\s+)?(" + WEEKDAY_ANY_RE + ")\\b(?:\\s+(?:morning|afternoon|evening|night))?", FLAGS),
                (m, ctx) -> {
                    String qualifier = m.group(1) == null ? null : m.group(1).toLowerCase(Locale.ROOT);
                    String word = m.group(2).toLowerCase(Locale.ROOT);
                    boolean full = word.matches(WEEKDAY_FULL_RE);
                    if (!full && qualifier == null) return null; // "sun", "sat", "wed" are ordinary words
                    Integer wdBoxed = WEEKDAYS.get(word);
                    if (wdBoxed == null) return null;
                    DayOfWeek wd = DayOfWeek.of(wdBoxed);
                    LocalDate t = ctx.today();
                    if ("last".equals(qualifier) || "past".equals(qualifier) || "previous".equals(qualifier)) {
                        LocalDate p = t.with(TemporalAdjusters.previous(wd));
                        return new Partial(Kind.DAY, List.of(DayRange.of(p), DayRange.of(p.minusWeeks(1))), Direction.PAST);
                    }
                    if ("next".equals(qualifier) || "coming".equals(qualifier)) {
                        LocalDate n = t.with(TemporalAdjusters.next(wd));
                        return new Partial(Kind.DAY, List.of(DayRange.of(n), DayRange.of(n.plusWeeks(1))), Direction.FUTURE);
                    }
                    if ("this".equals(qualifier)) {
                        LocalDate d = weekStart(t).plusDays(wdBoxed - 1L);
                        return new Partial(Kind.DAY, List.of(DayRange.of(d)), dirOf(d, t));
                    }
                    // Bare or "on Monday": the sentence decides the direction.
                    if (ctx.past()) {
                        if (t.getDayOfWeek() == wd) {
                            return new Partial(Kind.DAY, List.of(DayRange.of(t), DayRange.of(t.minusWeeks(1))), Direction.PAST);
                        }
                        return new Partial(Kind.DAY, List.of(DayRange.of(t.with(TemporalAdjusters.previous(wd)))), Direction.PAST);
                    }
                    if (t.getDayOfWeek() == wd) {
                        return new Partial(Kind.DAY, List.of(DayRange.of(t), DayRange.of(t.plusWeeks(1))), Direction.PRESENT);
                    }
                    return new Partial(Kind.DAY, List.of(DayRange.of(t.with(TemporalAdjusters.next(wd)))), Direction.FUTURE);
                }));

        rules.add(new Rule(Pattern.compile("\\b(?:last|previous|the\\s+past)\\s+(?:7|seven)\\s+days\\b|\\b(?:the\\s+)?past\\s+(?:week|few\\s+days)\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.RANGE, List.of(new DayRange(ctx.today().minusDays(7), ctx.today())), Direction.PAST)));
        rules.add(new Rule(Pattern.compile("\\b(?:last|previous)\\s+week\\b|\\bthe\\s+week\\s+before\\b", FLAGS), (m, ctx) -> {
            LocalDate start = weekStart(ctx.today()).minusWeeks(1);
            return new Partial(Kind.RANGE, List.of(new DayRange(start, start.plusDays(6)),
                    new DayRange(ctx.today().minusDays(7), ctx.today().minusDays(1))), Direction.PAST);
        }));
        rules.add(new Rule(Pattern.compile("\\b(earlier\\s+)?this\\s+week\\b", FLAGS), (m, ctx) -> {
            LocalDate start = weekStart(ctx.today());
            LocalDate end = m.group(1) != null ? ctx.today().minusDays(1) : ctx.today();
            return new Partial(Kind.RANGE, List.of(new DayRange(start, end.isBefore(start) ? start : end)), Direction.PRESENT);
        }));
        rules.add(new Rule(Pattern.compile("\\bnext\\s+week\\b", FLAGS), (m, ctx) -> {
            LocalDate start = weekStart(ctx.today()).plusWeeks(1);
            return new Partial(Kind.RANGE, List.of(new DayRange(start, start.plusDays(6))), Direction.FUTURE);
        }));
        rules.add(new Rule(Pattern.compile("\\b(?:last|previous)\\s+month\\b", FLAGS), (m, ctx) -> {
            LocalDate start = ctx.today().withDayOfMonth(1).minusMonths(1);
            return new Partial(Kind.RANGE, List.of(new DayRange(start, start.with(TemporalAdjusters.lastDayOfMonth())),
                    new DayRange(ctx.today().minusDays(30), ctx.today())), Direction.PAST);
        }));
        rules.add(new Rule(Pattern.compile("\\b(earlier\\s+)?this\\s+month\\b", FLAGS), (m, ctx) -> {
            LocalDate start = ctx.today().withDayOfMonth(1);
            LocalDate end = m.group(1) != null ? ctx.today().minusDays(1) : ctx.today();
            return new Partial(Kind.RANGE, List.of(new DayRange(start, end.isBefore(start) ? start : end)), Direction.PRESENT);
        }));
        rules.add(new Rule(Pattern.compile("\\b(?:last|previous)\\s+year\\b", FLAGS), (m, ctx) -> {
            LocalDate start = ctx.today().withDayOfYear(1).minusYears(1);
            return new Partial(Kind.RANGE, List.of(new DayRange(start, start.with(TemporalAdjusters.lastDayOfYear()))), Direction.PAST);
        }));
        rules.add(new Rule(Pattern.compile("\\bthis\\s+year\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.RANGE, List.of(new DayRange(ctx.today().withDayOfYear(1), ctx.today())), Direction.PRESENT)));

        // in October, during March 2026, since November, early November
        rules.add(new Rule(Pattern.compile("\\b(?:in|during|from|early|late|mid|since)\\s+(?:early\\s+|late\\s+|mid[- ])?(" + MONTH_RE + ")\\b(?:\\s+(\\d{4}))?", FLAGS),
                (m, ctx) -> {
                    Integer mo = month(m.group(1));
                    if (mo == null) return null;
                    DayRange r = monthRange(ctx, mo, m.group(2) != null ? Integer.parseInt(m.group(2)) : null);
                    if (r == null) return null;
                    return new Partial(Kind.RANGE, List.of(r), r.end().isBefore(ctx.today()) ? Direction.PAST : Direction.PRESENT);
                }));
        rules.add(new Rule(Pattern.compile("\\b(" + MONTH_RE + ")\\s+(\\d{4})\\b", FLAGS), (m, ctx) -> {
            Integer mo = month(m.group(1));
            if (mo == null) return null;
            DayRange r = monthRange(ctx, mo, Integer.parseInt(m.group(2)));
            if (r == null) return null;
            return new Partial(Kind.RANGE, List.of(r), r.end().isBefore(ctx.today()) ? Direction.PAST : Direction.PRESENT);
        }));

        // on the 3rd
        rules.add(new Rule(Pattern.compile("\\b(?:on\\s+)?the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b", FLAGS), (m, ctx) -> {
            int dom = Integer.parseInt(m.group(1));
            if (dom < 1 || dom > 31) return null;
            LocalDate t = ctx.today();
            LocalDate d = safeDate(t.getYear(), t.getMonthValue(), dom);
            if (ctx.past() && (d == null || d.isAfter(t))) {
                LocalDate prev = t.minusMonths(1);
                d = safeDate(prev.getYear(), prev.getMonthValue(), dom);
            }
            return d == null ? null : day(d, t);
        }));

        rules.add(new Rule(Pattern.compile("\\bthe\\s+other\\s+day\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.RANGE, List.of(new DayRange(ctx.today().minusDays(7), ctx.today().minusDays(1))), Direction.PAST)));
        rules.add(new Rule(Pattern.compile("\\b(?:recently|lately)\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.RANGE, List.of(new DayRange(ctx.today().minusDays(14), ctx.today())), Direction.PAST)));
        rules.add(new Rule(Pattern.compile("\\ba\\s+(?:while|bit)\\s+(?:ago|back)\\b", FLAGS),
                (m, ctx) -> new Partial(Kind.RANGE, List.of(new DayRange(ctx.today().minusDays(90), ctx.today().minusDays(7))), Direction.PAST)));

        return List.copyOf(rules);
    }
}
