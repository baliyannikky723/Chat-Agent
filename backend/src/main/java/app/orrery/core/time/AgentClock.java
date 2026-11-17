package app.orrery.core.time;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The agent never calls {@code Instant.now()} directly. It asks the clock.
 *
 * <p>That makes every date-dependent behaviour testable, and powers the demo clock: with
 * DEMO_TOOLS=true a person can set their "now" to last Monday, chat, jump forward, and ask
 * "what did we discuss on Monday?". The offset is per user, so it never affects anyone else.
 */
public interface AgentClock {

    Instant now(long userOffsetMillis);

    default Instant now() {
        return now(0);
    }

    static AgentClock system() {
        return offset -> Instant.ofEpochMilli(System.currentTimeMillis() + offset);
    }

    /** Frozen at {@code at} (plus the user offset) — for tests and scripted evals. */
    static AgentClock fixed(Instant at) {
        return offset -> at.plusMillis(offset);
    }

    /** True if {@code tz} is an IANA zone the JDK knows, e.g. "Asia/Kolkata". */
    static boolean isValidZone(String tz) {
        if (tz == null || tz.isBlank() || tz.length() > 64) return false;
        try {
            ZoneId.of(tz);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
