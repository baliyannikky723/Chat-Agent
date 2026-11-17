package app.orrery.core.time;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Timestamps are stored as fixed-width UTC strings ("2026-11-09T14:30:05.123Z") so they
 * sort correctly as text in SQL and parse back with {@link Instant#parse}.
 */
public final class Times {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {}

    public static String iso(Instant instant) {
        return ISO_MILLIS.format(instant);
    }
}
