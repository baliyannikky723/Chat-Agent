package app.orrery.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.search.Urls;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextAndCitationsTest {

    @Test
    void findsCitedNumbersButNotMarkdownLinks() {
        String text = "Neon costs $19 [1][3]. Supabase Pro is $25 [2, 4]. See [docs](https://x.y) and [9].";
        assertEquals(List.of(1, 2, 3, 4), Citations.cited(text, 4));
    }

    @Test
    void rewritesCitationsToDomainsForHistory() {
        String out = Citations.rewriteToDomains("Neon is $19 [1]. Both are cheap [1][2].", Map.of(1, "neon.com", 2, "supabase.com"));
        assertEquals("Neon is $19 (neon.com). Both are cheap (neon.com) (supabase.com).", out);
    }

    @Test
    void canonicalUrlsDropTrackingAndWww() {
        assertEquals("https://hubspot.com/pricing?plan=pro", Urls.canonical("https://www.HubSpot.com/pricing/?utm_source=x&plan=pro#top"));
        assertEquals("hubspot.com", Urls.domain("https://www.hubspot.com/pricing"));
    }

    @Test
    void truncationAndTitles() {
        assertTrue(Text.truncate("one two three four five", 12).endsWith("…"));
        assertEquals("CRM shortlist for sales", Text.cleanTitle("\"Title: CRM shortlist for sales.\""));
        assertEquals("Shortlist 5 CRM tools for a…", Text.firstWords("Shortlist 5 CRM tools for a 20-person sales team", 6));
    }

    @Test
    void uuidv7IsTimeOrdered() {
        String a = Ids.uuidv7(1_000);
        String b = Ids.uuidv7(2_000);
        assertTrue(Ids.isUuid(a));
        assertTrue(a.compareTo(b) < 0);
        assertEquals('7', a.charAt(14));
    }

    @Test
    void rateLimiterAllowsABurstThenWaits() {
        long[] now = {0};
        RateLimiter limiter = new RateLimiter(2, 1000, () -> now[0]);
        assertEquals(0L, limiter.tryAcquire("u"));
        assertEquals(0L, limiter.tryAcquire("u"));
        assertTrue(limiter.tryAcquire("u") > 0);
        now[0] = 1000;
        assertEquals(0L, limiter.tryAcquire("u"));
    }
}
