package ca.venn.loadfunds.model.velocity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * A half-open, calendar-aligned time window: [from, to).
 *
 * Windows are aligned to calendar boundaries, not rolled forward from the
 * instant they're derived from. A load at Wednesday 14:00 falls in the week
 * window Monday 00:00 -> next Monday 00:00. Half-open so that consecutive
 * windows tile without overlap: midnight belongs to the new day only.
 */
public record Window(Instant from, Instant to) {

    public Window {
        if (!from.isBefore(to)) throw new IllegalArgumentException("Empty window: " + from + ".." + to);
    }

    /** The UTC day containing {@code t}. Days end at midnight UTC. */
    public static Window dayOf(Instant t) {
        LocalDate start = t.atZone(ZoneOffset.UTC).toLocalDate();
        return between(start, start.plusDays(1));
    }

    /** The week containing {@code t}. Weeks start Monday, i.e. one second after 23:59:59 Sunday. */
    public static Window weekOf(Instant t) {
        LocalDate start = t.atZone(ZoneOffset.UTC).toLocalDate()
                           .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return between(start, start.plusWeeks(1));
    }

    private static Window between(LocalDate from, LocalDate to) {
        return new Window(startOfDayUtc(from), startOfDayUtc(to));
    }

    private static Instant startOfDayUtc(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}


