package ca.venn.loadfunds.service;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeteredLoadFundsServiceTest {

    private static final LoadFundsAttempt ATTEMPT = new LoadFundsAttempt(
        "load-123",
        "customer-456",
        1_500L,
        Instant.parse("2026-07-16T12:00:00Z")
    );

    @Test
    void recordsAcceptedDecisionMetricsAndReturnsDelegateOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoadFundsOutcome expected = LoadFundsOutcome.fresh(Decision.accept());
        MeteredLoadFundsService service = new MeteredLoadFundsService(in -> expected, registry);

        LoadFundsOutcome actual = service.decide(ATTEMPT);

        assertThat(actual).isEqualTo(expected);
        assertThat(counter(registry, "accepted", "none")).isEqualTo(1.0);
        assertThat(timerCount(registry, "accepted")).isEqualTo(1L);
    }

    @Test
    void recordsDeclinedDecisionMetricsWithReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoadFundsOutcome declined = LoadFundsOutcome.fresh(
            Decision.decline(RejectionReason.DAILY_AMOUNT_EXCEEDED)
        );
        MeteredLoadFundsService service = new MeteredLoadFundsService(in -> declined, registry);

        LoadFundsOutcome actual = service.decide(ATTEMPT);

        assertThat(actual).isEqualTo(declined);
        assertThat(counter(registry, "declined", "DAILY_AMOUNT_EXCEEDED")).isEqualTo(1.0);
        assertThat(timerCount(registry, "declined")).isEqualTo(1L);
    }

    @Test
    void recordsDuplicateDecisionMetricsSeparatelyFromAcceptedDecision() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoadFundsOutcome duplicate = LoadFundsOutcome.duplicate(Decision.accept());
        MeteredLoadFundsService service = new MeteredLoadFundsService(in -> duplicate, registry);

        LoadFundsOutcome actual = service.decide(ATTEMPT);

        assertThat(actual).isEqualTo(duplicate);
        assertThat(counter(registry, "duplicate", "none")).isEqualTo(1.0);
        assertThat(timerCount(registry, "duplicate")).isEqualTo(1L);
    }

    @Test
    void recordsErrorDecisionMetricsWhenDelegateThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredLoadFundsService service = new MeteredLoadFundsService(in -> {
            throw new IllegalStateException("delegate failed");
        }, registry);

        assertThatThrownBy(() -> service.decide(ATTEMPT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("delegate failed");
        assertThat(counter(registry, "error", "none")).isEqualTo(1.0);
        assertThat(timerCount(registry, "error")).isEqualTo(1L);
    }

    private static double counter(SimpleMeterRegistry registry, String outcome, String reason) {
        return registry.get("loadfunds.decisions")
                       .tag("outcome", outcome)
                       .tag("reason", reason)
                       .counter()
                       .count();
    }

    private static long timerCount(SimpleMeterRegistry registry, String outcome) {
        return registry.get("loadfunds.decide")
                       .tag("outcome", outcome)
                       .timer()
                       .count();
    }
}
