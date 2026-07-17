package ca.venn.loadfunds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.model.loadfunds.LoadFundsStatus;
import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import ca.venn.loadfunds.model.velocity.Totals;
import ca.venn.loadfunds.persistence.LoadFundsAttemptEntity;
import ca.venn.loadfunds.repository.CustomerRepository;
import ca.venn.loadfunds.repository.LoadFundsAttemptRepository;
import ca.venn.loadfunds.repository.LoadFundsAttemptRepository.TotalsEntity;
import ca.venn.loadfunds.velocity.VelocityEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class LoadFundsServiceImplTest {

    private static final Instant PROCESSED_AT = Instant.parse("2026-07-15T12:34:56Z");
    private static final Instant DAY_FROM = Instant.parse("2026-07-15T00:00:00Z");
    private static final Instant DAY_TO = Instant.parse("2026-07-16T00:00:00Z");
    private static final Instant WEEK_FROM = Instant.parse("2026-07-13T00:00:00Z");
    private static final Instant WEEK_TO = Instant.parse("2026-07-20T00:00:00Z");

    private static final LoadFundsAttempt ATTEMPT = new LoadFundsAttempt(
        "load-123",
        "customer-456",
        1_500L,
        PROCESSED_AT
    );

    @Mock
    private LoadFundsAttemptRepository attemptRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private VelocityEvaluator velocityEvaluator;

    private LoadFundsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LoadFundsServiceImpl(attemptRepository, customerRepository, velocityEvaluator);
    }

    @Test
    void returnsDuplicateDecisionWithoutLockingOrReevaluating() {
        Decision previousDecision = Decision.decline(RejectionReason.DAILY_COUNT_EXCEEDED);
        LoadFundsAttemptEntity existing = LoadFundsAttemptEntity.of(ATTEMPT, previousDecision);
        when(attemptRepository.findByCustomerIdAndLoadFundsId(ATTEMPT.customerId(), ATTEMPT.loadId()))
            .thenReturn(Optional.of(existing));

        LoadFundsOutcome outcome = service.decide(ATTEMPT);

        assertThat(outcome).isEqualTo(LoadFundsOutcome.duplicate(previousDecision));
        verify(attemptRepository).findByCustomerIdAndLoadFundsId(ATTEMPT.customerId(), ATTEMPT.loadId());
        verify(attemptRepository, never()).totalsBetween(any(), any(), any(), any());
        verify(attemptRepository, never()).saveAndFlush(any());
        verifyNoInteractions(customerRepository, velocityEvaluator);
    }

    @Test
    void evaluatesAndPersistsAcceptedAttemptUsingUtcDayAndWeekTotals() {
        TotalsEntity dayTotals = totals(2_500L, 2L);
        TotalsEntity weekTotals = totals(8_000L, 5L);
        Decision decision = Decision.accept();
        stubFreshAttempt(dayTotals, weekTotals, decision);

        LoadFundsOutcome outcome = service.decide(ATTEMPT);

        assertThat(outcome).isEqualTo(LoadFundsOutcome.fresh(decision));
        assertFreshInteractionOrder(dayTotals, weekTotals, decision);
        assertPersistedEntity(decision);
    }

    @Test
    void evaluatesAndPersistsDeclinedAttemptWithRejectionReason() {
        TotalsEntity dayTotals = totals(4_500L, 3L);
        TotalsEntity weekTotals = totals(19_000L, 7L);
        Decision decision = Decision.decline(RejectionReason.WEEKLY_AMOUNT_EXCEEDED);
        stubFreshAttempt(dayTotals, weekTotals, decision);

        LoadFundsOutcome outcome = service.decide(ATTEMPT);

        assertThat(outcome).isEqualTo(LoadFundsOutcome.fresh(decision));
        assertFreshInteractionOrder(dayTotals, weekTotals, decision);
        assertPersistedEntity(decision);
    }

    private void stubFreshAttempt(TotalsEntity dayTotals, TotalsEntity weekTotals, Decision decision) {
        when(attemptRepository.findByCustomerIdAndLoadFundsId(ATTEMPT.customerId(), ATTEMPT.loadId()))
            .thenReturn(Optional.empty());
        stubTotals(dayTotals, weekTotals);
        when(velocityEvaluator.evaluate(
            ATTEMPT.amountCents(),
            new Totals(dayTotals.getAmountCents(), (int) dayTotals.getLoadCount()),
            new Totals(weekTotals.getAmountCents(), (int) weekTotals.getLoadCount())
        )).thenReturn(decision);
    }

    private void stubTotals(TotalsEntity dayTotals, TotalsEntity weekTotals) {
        when(attemptRepository.totalsBetween(
            ATTEMPT.customerId(), LoadFundsStatus.ACCEPTED, DAY_FROM, DAY_TO
        )).thenReturn(dayTotals);
        when(attemptRepository.totalsBetween(
            ATTEMPT.customerId(), LoadFundsStatus.ACCEPTED, WEEK_FROM, WEEK_TO
        )).thenReturn(weekTotals);
    }

    private void assertFreshInteractionOrder(TotalsEntity dayTotals, TotalsEntity weekTotals, Decision decision) {
        var ordered = inOrder(attemptRepository, customerRepository, velocityEvaluator);
        ordered.verify(attemptRepository)
               .findByCustomerIdAndLoadFundsId(ATTEMPT.customerId(), ATTEMPT.loadId());
        ordered.verify(customerRepository).ensureExists(ATTEMPT.customerId());
        ordered.verify(customerRepository).lock(ATTEMPT.customerId());
        ordered.verify(attemptRepository)
               .totalsBetween(ATTEMPT.customerId(), LoadFundsStatus.ACCEPTED, DAY_FROM, DAY_TO);
        ordered.verify(attemptRepository)
               .totalsBetween(ATTEMPT.customerId(), LoadFundsStatus.ACCEPTED, WEEK_FROM, WEEK_TO);
        ordered.verify(velocityEvaluator).evaluate(
            ATTEMPT.amountCents(),
            new Totals(dayTotals.getAmountCents(), (int) dayTotals.getLoadCount()),
            new Totals(weekTotals.getAmountCents(), (int) weekTotals.getLoadCount())
        );
        ordered.verify(attemptRepository).saveAndFlush(any(LoadFundsAttemptEntity.class));
        ordered.verifyNoMoreInteractions();
    }

    private void assertPersistedEntity(Decision decision) {
        ArgumentCaptor<LoadFundsAttemptEntity> captor = ArgumentCaptor.forClass(LoadFundsAttemptEntity.class);
        verify(attemptRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue())
            .usingRecursiveComparison()
            .isEqualTo(LoadFundsAttemptEntity.of(ATTEMPT, decision));
    }

    private static TotalsEntity totals(long amountCents, long loadCount) {
        return new TestTotalsEntity(amountCents, loadCount);
    }

    private record TestTotalsEntity(long amountCents, long loadCount) implements TotalsEntity {

        @Override
        public long getAmountCents() {
            return amountCents;
        }

        @Override
        public long getLoadCount() {
            return loadCount;
        }
    }
}
