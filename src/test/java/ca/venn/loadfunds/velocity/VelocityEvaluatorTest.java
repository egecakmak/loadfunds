package ca.venn.loadfunds.velocity;

import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import ca.venn.loadfunds.model.velocity.Totals;
import ca.venn.loadfunds.model.velocity.VelocityLimits;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityEvaluatorTest {

    private final VelocityEvaluator evaluator = new VelocityEvaluator(
        new VelocityLimits(5_000L, 20_000L, 3)
    );

    @Test
    void acceptsWhenProjectedTotalsAreWithinLimits() {
        Decision decision = evaluator.evaluate(
            2_500L,
            new Totals(2_499L, 2),
            new Totals(17_499L, 7)
        );

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void acceptsWhenProjectedTotalsEqualLimits() {
        Decision decision = evaluator.evaluate(
            2_500L,
            new Totals(2_500L, 2),
            new Totals(17_500L, 8)
        );

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void declinesWhenDailyLoadCountWouldBeExceeded() {
        Decision decision = evaluator.evaluate(
            100L,
            new Totals(0L, 3),
            new Totals(0L, 0)
        );

        assertDeclined(decision, RejectionReason.DAILY_COUNT_EXCEEDED);
    }

    @Test
    void declinesWhenDailyAmountWouldBeExceeded() {
        Decision decision = evaluator.evaluate(
            2_501L,
            new Totals(2_500L, 2),
            new Totals(0L, 0)
        );

        assertDeclined(decision, RejectionReason.DAILY_AMOUNT_EXCEEDED);
    }

    @Test
    void declinesWhenWeeklyAmountWouldBeExceeded() {
        Decision decision = evaluator.evaluate(
            2_501L,
            new Totals(0L, 0),
            new Totals(17_500L, 10)
        );

        assertDeclined(decision, RejectionReason.WEEKLY_AMOUNT_EXCEEDED);
    }

    @Test
    void returnsFirstFailedCheckWhenMultipleLimitsWouldBeExceeded() {
        Decision decision = evaluator.evaluate(
            10_000L,
            new Totals(10_000L, 3),
            new Totals(20_000L, 10)
        );

        assertDeclined(decision, RejectionReason.DAILY_COUNT_EXCEEDED);
    }

    private static void assertDeclined(Decision decision, RejectionReason reason) {
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reason()).isEqualTo(reason);
    }
}
