package app.orrery.core.understand;

import java.time.LocalDate;

/** An inclusive range of local calendar dates. A single day has start == end. */
public record DayRange(LocalDate start, LocalDate end) {

    public DayRange {
        if (end.isBefore(start)) {
            LocalDate t = start;
            start = end;
            end = t;
        }
    }

    public static DayRange of(LocalDate day) {
        return new DayRange(day, day);
    }

    public boolean isSingleDay() {
        return start.equals(end);
    }

    public boolean contains(LocalDate d) {
        return !d.isBefore(start) && !d.isAfter(end);
    }
}
