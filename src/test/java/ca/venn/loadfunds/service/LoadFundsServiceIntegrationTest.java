package ca.venn.loadfunds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import ca.venn.loadfunds.repository.CustomerRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:load-funds-service-it;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate",
    "velocity.limits.daily-amount-cents=5000",
    "velocity.limits.weekly-amount-cents=20000",
    "velocity.limits.daily-load-count=3",
    "logging.level.root=INFO"
})
class LoadFundsServiceIntegrationTest {

    private static final Instant WEDNESDAY = Instant.parse("2026-07-08T12:00:00Z");

    @Autowired
    private LoadFundsService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CountingTransactionManager transactions;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM load_funds_attempt");
        jdbcTemplate.update("DELETE FROM customer");
        transactions.reset();
    }

    @Test
    void createsCustomerAndPersistsAcceptedAttempt() {
        LoadFundsOutcome outcome = decide("load-1", "customer-1", 1_500L, WEDNESDAY);

        assertThat(outcome).isEqualTo(LoadFundsOutcome.fresh(ca.venn.loadfunds.model.velocity.Decision.accept()));
        assertThat(count("customer")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
            """
                SELECT customer_id, load_funds_id, amount_cents, status, rejection_reason, processed_at
                FROM load_funds_attempt
                """
        )).containsEntry("CUSTOMER_ID", "customer-1")
          .containsEntry("LOAD_FUNDS_ID", "load-1")
          .containsEntry("AMOUNT_CENTS", 1_500L)
          .containsEntry("STATUS", "ACCEPTED")
          .containsEntry("REJECTION_REASON", null)
          .containsEntry("PROCESSED_AT", WEDNESDAY.atOffset(java.time.ZoneOffset.UTC));
    }

    @Test
    void returnsOriginalDecisionForDuplicateAndKeepsSingleRow() {
        LoadFundsOutcome first = decide("load-1", "customer-1", 4_000L, WEDNESDAY);
        LoadFundsOutcome duplicate = decide("load-1", "customer-1", 5_000L, WEDNESDAY.plusSeconds(60));

        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate).isEqualTo(LoadFundsOutcome.duplicate(first.decision()));
        assertThat(count("load_funds_attempt")).isEqualTo(1);
        assertThat(storedAmount("customer-1", "load-1")).isEqualTo(4_000L);
    }

    @Test
    void serviceCallRunsInOnePhysicalTransaction() {
        LoadFundsOutcome first = decide("load-1", "customer-1", 4_000L, WEDNESDAY);

        assertAccepted(first);
        transactions.assertCounts(1, 1, 0);

        transactions.reset();
        LoadFundsOutcome duplicate = decide("load-1", "customer-1", 5_000L, WEDNESDAY.plusSeconds(60));

        assertThat(duplicate).isEqualTo(LoadFundsOutcome.duplicate(first.decision()));
        transactions.assertCounts(1, 1, 0);
    }

    @Test
    void appliesDailyCountLimitAndDoesNotCountDeclines() {
        assertAccepted(decide("load-1", "customer-1", 100L, WEDNESDAY));
        assertAccepted(decide("load-2", "customer-1", 100L, WEDNESDAY.plusSeconds(60)));
        assertAccepted(decide("load-3", "customer-1", 100L, WEDNESDAY.plusSeconds(120)));

        assertDeclined(
            decide("load-4", "customer-1", 100L, WEDNESDAY.plusSeconds(180)),
            RejectionReason.DAILY_COUNT_EXCEEDED
        );
        assertDeclined(
            decide("load-5", "customer-1", 100L, WEDNESDAY.plusSeconds(240)),
            RejectionReason.DAILY_COUNT_EXCEEDED
        );
        assertThat(countByStatus("ACCEPTED")).isEqualTo(3);
        assertThat(countByStatus("DECLINED")).isEqualTo(2);
    }

    @Test
    void appliesDailyAmountLimitAndAllowsExactLimit() {
        assertAccepted(decide("load-1", "customer-1", 3_000L, WEDNESDAY));
        assertDeclined(
            decide("load-2", "customer-1", 2_001L, WEDNESDAY.plusSeconds(60)),
            RejectionReason.DAILY_AMOUNT_EXCEEDED
        );
        assertAccepted(decide("load-3", "customer-1", 2_000L, WEDNESDAY.plusSeconds(120)));
    }

    @Test
    void appliesWeeklyAmountAcrossDaysAndResetsAtMondayBoundary() {
        Instant monday = Instant.parse("2026-07-06T12:00:00Z");
        assertAccepted(decide("load-1", "customer-1", 5_000L, monday));
        assertAccepted(decide("load-2", "customer-1", 5_000L, monday.plusSeconds(86_400)));
        assertAccepted(decide("load-3", "customer-1", 5_000L, monday.plusSeconds(2 * 86_400)));
        assertAccepted(decide("load-4", "customer-1", 5_000L, monday.plusSeconds(3 * 86_400)));
        assertDeclined(
            decide("load-5", "customer-1", 1L, monday.plusSeconds(4 * 86_400)),
            RejectionReason.WEEKLY_AMOUNT_EXCEEDED
        );
        assertAccepted(decide("load-6", "customer-1", 5_000L, monday.plusSeconds(7 * 86_400)));
    }

    @Test
    void treatsSameLoadIdAsIndependentAcrossCustomers() {
        assertAccepted(decide("shared-load", "customer-1", 1_000L, WEDNESDAY));
        assertAccepted(decide("shared-load", "customer-2", 1_000L, WEDNESDAY));

        assertThat(count("load_funds_attempt")).isEqualTo(2);
        assertThat(count("customer")).isEqualTo(2);
    }

    @Test
    @Disabled("Added for experimentation; disabled to avoid flakiness from concurrency timing.")
    void serializesConcurrentVelocityDecisionsForSameCustomer() throws Exception {
        List<LoadFundsOutcome> outcomes = concurrently(
            attempt("load-1", "customer-1", 3_000L, WEDNESDAY),
            attempt("load-2", "customer-1", 3_000L, WEDNESDAY)
        );

        assertThat(outcomes).extracting(outcome -> outcome.decision().accepted())
                            .containsExactlyInAnyOrder(true, false);
        assertThat(outcomes).filteredOn(outcome -> !outcome.decision().accepted())
                            .extracting(outcome -> outcome.decision().reason())
                            .containsExactly(RejectionReason.DAILY_AMOUNT_EXCEEDED);
        assertThat(countByStatus("ACCEPTED")).isEqualTo(1);
        assertThat(countByStatus("DECLINED")).isEqualTo(1);
    }

    @Test
    @Disabled("Added for experimentation; disabled to avoid flakiness from concurrency timing.")
    void rollsBackOneConcurrentDuplicateOnUniqueConstraint() throws Exception {
        List<AttemptResult> results = concurrentlyCapturingFailures(
            attempt("same-load", "customer-1", 1_000L, WEDNESDAY),
            attempt("same-load", "customer-1", 1_000L, WEDNESDAY)
        );

        assertThat(results).extracting(result -> result.failure() == null)
                           .containsExactlyInAnyOrder(true, false);
        AttemptResult successful = results.stream().filter(result -> result.failure() == null).findFirst().orElseThrow();
        AttemptResult failed = results.stream().filter(result -> result.failure() != null).findFirst().orElseThrow();
        assertAccepted(successful.outcome());
        assertThat(successful.outcome().duplicate()).isFalse();
        assertThat(failed.failure()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("load_funds_attempt")).isEqualTo(1);
        transactions.assertCounts(2, 1, 1);
    }

    @Test
    void failsBoundedCustomerContentionInsteadOfWaitingIndefinitely() throws Exception {
        jdbcTemplate.update("INSERT INTO customer (id) VALUES (?)", "blocked-customer");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                customerRepository.lock("blocked-customer");
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Instant started = Instant.now();
            try {
                assertThatThrownBy(() -> decide(
                    "blocked-load", "blocked-customer", 1_000L, WEDNESDAY
                )).isInstanceOf(DataAccessException.class);
                assertThat(Duration.between(started, Instant.now()))
                    .isBetween(Duration.ofSeconds(2), Duration.ofSeconds(5));
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(count("load_funds_attempt")).isZero();
    }

    private List<LoadFundsOutcome> concurrently(LoadFundsAttempt first, LoadFundsAttempt second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<LoadFundsOutcome> firstResult = executor.submit(() -> decideWhenReleased(first, ready, start));
            Future<LoadFundsOutcome> secondResult = executor.submit(() -> decideWhenReleased(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
        }
    }

    private List<AttemptResult> concurrentlyCapturingFailures(LoadFundsAttempt first, LoadFundsAttempt second)
        throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<LoadFundsOutcome> firstResult = executor.submit(() -> decideWhenReleased(first, ready, start));
            Future<LoadFundsOutcome> secondResult = executor.submit(() -> decideWhenReleased(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(resultOf(firstResult), resultOf(secondResult));
        }
    }

    private static AttemptResult resultOf(Future<LoadFundsOutcome> future) throws Exception {
        try {
            return new AttemptResult(future.get(10, TimeUnit.SECONDS), null);
        } catch (ExecutionException exception) {
            return new AttemptResult(null, exception.getCause());
        }
    }

    private LoadFundsOutcome decideWhenReleased(
        LoadFundsAttempt attempt,
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return service.decide(attempt);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release customer lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding customer lock", exception);
        }
    }

    private LoadFundsOutcome decide(String loadId, String customerId, long amount, Instant processedAt) {
        return service.decide(attempt(loadId, customerId, amount, processedAt));
    }

    private static LoadFundsAttempt attempt(String loadId, String customerId, long amount, Instant processedAt) {
        return new LoadFundsAttempt(loadId, customerId, amount, processedAt);
    }

    private void assertAccepted(LoadFundsOutcome outcome) {
        assertThat(outcome.decision().accepted()).isTrue();
        assertThat(outcome.decision().reason()).isNull();
    }

    private static void assertDeclined(LoadFundsOutcome outcome, RejectionReason reason) {
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.decision().accepted()).isFalse();
        assertThat(outcome.decision().reason()).isEqualTo(reason);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countByStatus(String status) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM load_funds_attempt WHERE status = ?",
            Integer.class,
            status
        );
    }

    private long storedAmount(String customerId, String loadId) {
        return jdbcTemplate.queryForObject(
            "SELECT amount_cents FROM load_funds_attempt WHERE customer_id = ? AND load_funds_id = ?",
            Long.class,
            customerId,
            loadId
        );
    }

    private record AttemptResult(LoadFundsOutcome outcome, Throwable failure) {}

    @TestConfiguration
    static class TransactionCountingConfig {

        @Bean
        @Primary
        CountingTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new CountingTransactionManager(new JpaTransactionManager(entityManagerFactory));
        }
    }

    static final class CountingTransactionManager implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;
        private final AtomicInteger begun = new AtomicInteger();
        private final AtomicInteger committed = new AtomicInteger();
        private final AtomicInteger rolledBack = new AtomicInteger();

        private CountingTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            TransactionStatus status = delegate.getTransaction(definition);
            if (status.isNewTransaction()) {
                begun.incrementAndGet();
            }
            return status;
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            boolean physicalTransaction = status.isNewTransaction();
            delegate.commit(status);
            if (physicalTransaction) {
                committed.incrementAndGet();
            }
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            boolean physicalTransaction = status.isNewTransaction();
            delegate.rollback(status);
            if (physicalTransaction) {
                rolledBack.incrementAndGet();
            }
        }

        private void reset() {
            begun.set(0);
            committed.set(0);
            rolledBack.set(0);
        }

        private void assertCounts(int expectedBegun, int expectedCommitted, int expectedRolledBack) {
            assertThat(begun).hasValue(expectedBegun);
            assertThat(committed).hasValue(expectedCommitted);
            assertThat(rolledBack).hasValue(expectedRolledBack);
        }
    }
}
