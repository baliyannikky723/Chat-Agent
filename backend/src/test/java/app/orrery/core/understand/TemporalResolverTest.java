package app.orrery.core.understand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Saturday 14 November 2026, 21:00 in India — the day the brief's examples are asked. */
class TemporalResolverTest {

    private static final ZonedDateTime SATURDAY = ZonedDateTime.of(2026, 11, 14, 21, 0, 0, 0, ZoneId.of("Asia/Kolkata"));

    private static List<TimeRef> resolve(String text, boolean preferPast) {
        return TemporalResolver.resolve(text, SATURDAY, "en-IN", preferPast);
    }

    private static TimeRef only(String text, boolean preferPast) {
        List<TimeRef> refs = resolve(text, preferPast);
        assertEquals(1, refs.size(), "refs for: " + text + " → " + refs);
        return refs.get(0);
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void onMondayMeansTheMondayJustGone() {
        TimeRef ref = only("On Monday we shortlisted 5 CRM tools for a 20-person sales team.", true);
        assertEquals(DayRange.of(d(11, 9)), ref.preferred());
        assertEquals("Monday 9 Nov 2026", ref.label());
    }

    @Test
    void lastTuesdayOffersTheWeekBeforeAsSecondCandidate() {
        TimeRef ref = only("Last Tuesday I asked you about Postgres hosting.", true);
        assertEquals(List.of(DayRange.of(d(11, 10)), DayRange.of(d(11, 3))), ref.candidates());
    }

    @Test
    void dayAndMonthWithoutYearIsTheMostRecentPastOne() {
        List<TimeRef> refs = resolve("What did I ask you on 3 November? Is your answer still correct today?", true);
        assertEquals(DayRange.of(d(11, 3)), refs.get(0).preferred());
        assertEquals("today", refs.get(1).phrase());
        // 25 December looking back is last year's
        assertEquals(DayRange.of(LocalDate.of(2025, 12, 25)), only("what did we discuss on 25 December", true).preferred());
    }

    @Test
    void monthNameFirstAndOrdinalsWork() {
        assertEquals(DayRange.of(d(11, 3)), only("November 3rd", true).preferred());
        assertEquals(DayRange.of(LocalDate.of(2025, 11, 3)), only("Nov 3, 2025", true).preferred());
        assertEquals(DayRange.of(d(11, 3)), only("on the 3rd of November", true).preferred());
        assertEquals(DayRange.of(d(11, 3)), only("on the 3rd", true).preferred());
    }

    @Test
    void numericDatesFollowTheLocale() {
        assertEquals(DayRange.of(d(11, 3)), TemporalResolver.resolve("03/11/2026", SATURDAY, "en-IN", true).get(0).preferred());
        assertEquals(DayRange.of(d(3, 11)), TemporalResolver.resolve("03/11/2026", SATURDAY, "en-US", true).get(0).preferred());
        assertTrue(resolve("we offer 24/7 support", false).isEmpty(), "24/7 is not a date");
    }

    @Test
    void relativeDaysAndWeeks() {
        assertEquals(DayRange.of(d(11, 13)), only("yesterday", false).preferred());
        assertEquals(DayRange.of(d(11, 12)), only("the day before yesterday", false).preferred());
        assertEquals(DayRange.of(d(11, 11)), only("3 days ago", false).preferred());
        assertEquals(new DayRange(d(11, 11), d(11, 13)), only("a couple of days ago", false).preferred());
        assertEquals(new DayRange(d(11, 2), d(11, 8)), only("last week", false).preferred());
        assertEquals(new DayRange(d(11, 9), d(11, 14)), only("this week", false).preferred());
        assertEquals(new DayRange(d(10, 1), d(10, 31)), only("last month", false).preferred());
        assertEquals(new DayRange(d(10, 1), d(10, 31)), only("in October", true).preferred());
    }

    @Test
    void sinceAlwaysLooksBackAndRunsToToday() {
        TimeRef ref = only("what about since Monday?", false);
        assertTrue(ref.since());
        assertEquals(new DayRange(d(11, 9), d(11, 14)), ref.preferred());
        assertEquals("since Monday", ref.phrase());
    }

    @Test
    void futureWordsLookForward() {
        assertEquals(DayRange.of(d(11, 20)), only("next Friday", false).preferred());
        assertEquals(DayRange.of(d(11, 15)), only("what's the weather tomorrow", false).preferred());
    }

    @Test
    void whenTodayIsTheNamedWeekdayBothDaysAreCandidates() {
        ZonedDateTime monday = ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
        TimeRef ref = TemporalResolver.resolve("On Monday we shortlisted CRMs", monday, "en-IN", true).get(0);
        assertEquals(List.of(DayRange.of(d(11, 16)), DayRange.of(d(11, 9))), ref.candidates());
    }

    @Test
    void shortWeekdayWordsNeedAQualifier() {
        assertTrue(resolve("the sun is hot and I sat down", false).isEmpty());
        assertEquals(DayRange.of(d(11, 8)), only("last sun", false).preferred());
    }

    @Test
    void timezoneDecidesWhatTodayIs() {
        // 21:00 in India is 15:30 UTC, but 01:30 the next day in Tokyo.
        ZonedDateTime tokyo = SATURDAY.withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
        assertEquals(DayRange.of(d(11, 14)), TemporalResolver.resolve("yesterday", tokyo, "en-GB", false).get(0).preferred());
    }
}
