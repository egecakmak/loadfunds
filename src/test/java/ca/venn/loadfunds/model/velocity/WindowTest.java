package ca.venn.loadfunds.model.velocity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowTest {

    @Test
    void rejectsEmptyWindow() {
        Instant instant = Instant.parse("2026-07-08T00:00:00Z");

        assertThatThrownBy(() -> new Window(instant, instant))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Empty window: 2026-07-08T00:00:00Z..2026-07-08T00:00:00Z");
    }

    @Test
    void rejectsWindowWhereEndIsBeforeStart() {
        assertThatThrownBy(() -> new Window(
            Instant.parse("2026-07-08T00:00:01Z"),
            Instant.parse("2026-07-08T00:00:00Z")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Empty window: 2026-07-08T00:00:01Z..2026-07-08T00:00:00Z");
    }

    @Test
    void dayOfReturnsUtcCalendarDayContainingInstant() {
        Window window = Window.dayOf(Instant.parse("2026-07-08T23:59:59Z"));

        assertThat(window).isEqualTo(new Window(
            Instant.parse("2026-07-08T00:00:00Z"),
            Instant.parse("2026-07-09T00:00:00Z")
        ));
    }

    @Test
    void dayOfTreatsMidnightAsStartOfNewDay() {
        Window window = Window.dayOf(Instant.parse("2026-07-09T00:00:00Z"));

        assertThat(window).isEqualTo(new Window(
            Instant.parse("2026-07-09T00:00:00Z"),
            Instant.parse("2026-07-10T00:00:00Z")
        ));
    }

    @Test
    void weekOfReturnsMondayToNextMondayUtcWindow() {
        Window window = Window.weekOf(Instant.parse("2026-07-08T12:00:00Z"));

        assertThat(window).isEqualTo(new Window(
            Instant.parse("2026-07-06T00:00:00Z"),
            Instant.parse("2026-07-13T00:00:00Z")
        ));
    }

    @Test
    void weekOfTreatsMondayMidnightAsStartOfNewWeek() {
        Window window = Window.weekOf(Instant.parse("2026-07-13T00:00:00Z"));

        assertThat(window).isEqualTo(new Window(
            Instant.parse("2026-07-13T00:00:00Z"),
            Instant.parse("2026-07-20T00:00:00Z")
        ));
    }

    @Test
    void weekOfIncludesSundayUntilNextMonday() {
        Window window = Window.weekOf(Instant.parse("2026-07-12T23:59:59Z"));

        assertThat(window).isEqualTo(new Window(
            Instant.parse("2026-07-06T00:00:00Z"),
            Instant.parse("2026-07-13T00:00:00Z")
        ));
    }
}
