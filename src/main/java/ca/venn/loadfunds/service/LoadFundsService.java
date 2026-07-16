package ca.venn.loadfunds.service;

import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.repository.LoadFundsAttemptRepository.TotalsEntity;
import ca.venn.loadfunds.velocity.VelocityEvaluator;
import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsStatus;
import ca.venn.loadfunds.model.velocity.Totals;
import ca.venn.loadfunds.model.velocity.Window;
import ca.venn.loadfunds.persistence.LoadFundsAttemptEntity;
import ca.venn.loadfunds.repository.CustomerRepository;
import ca.venn.loadfunds.repository.LoadFundsAttemptRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * todos
 * tests unit integration flyway cli error handling
 * metrics (actuator application repo/db)
 * logs
 * fix versions
 * exception handling (what happens when we do GET)
 * go over locks
 * lock tests
 * transactions
 * go over annotation types in entities
 * save and flush
 * threadpools
 * lock timeout?
 */
@Service
@Slf4j
@AllArgsConstructor
public class LoadFundsService {

    private final LoadFundsAttemptRepository loadFundsAttemptRepository;
    private final CustomerRepository customerRepository;
    private final VelocityEvaluator velocityEvaluator;

    @Transactional
    public LoadFundsOutcome decide(LoadFundsAttempt in) {

        // Duplicate check before locking: no reason to serialize on a customer
        // just to discover we already answered this.
        var seen = loadFundsAttemptRepository.findByCustomerIdAndLoadFundsId(in.customerId(), in.loadId());
        if (seen.isPresent()) {
            log.info("duplicate load_id={} customer_id={}", in.loadId(), in.customerId());
            return LoadFundsOutcome.duplicate(seen.get().toDecision());
        }

        // Make sure customerId exists prior to acquiring the lock for it for the sake of this take-home.
        customerRepository.ensureExists(in.customerId());

        // Put a lock on the customer to TOCTOU issues with load attempts and velocity decisions.
        customerRepository.lock(in.customerId());

        Window dayWindow = Window.dayOf(in.processedAt());
        Window weekWindow = Window.weekOf(in.processedAt());

        TotalsEntity dayTotalsEntity = loadFundsAttemptRepository.totalsBetween(in.customerId(),
                                                                                LoadFundsStatus.ACCEPTED,
                                                                                dayWindow.from(), dayWindow.to());
        TotalsEntity weekTotalsEntity = loadFundsAttemptRepository.totalsBetween(in.customerId(),
                                                                                 LoadFundsStatus.ACCEPTED,
                                                                                 weekWindow.from(), weekWindow.to());

        Totals dayTotals = convertTotalsEntityToModel(dayTotalsEntity);
        Totals weekTotals = convertTotalsEntityToModel(weekTotalsEntity);

        Decision decision = velocityEvaluator.evaluate(in.amountCents(), dayTotals, weekTotals);

        // saveAndFlush, not save: push the INSERT to the DB inside the locked
        // region so failures surface here rather than as UnexpectedRollbackException
        // at commit. The unique constraint is a backstop that shouldn't fire.
        loadFundsAttemptRepository.saveAndFlush(LoadFundsAttemptEntity.of(in, decision));

        log.info("decision load_id={} customer_id={} amount_cents={} accepted={} reason={} "
                     + "day_used={} day_count={} week_used={}",
                 in.loadId(), in.customerId(), in.amountCents(),
                 decision.accepted(), decision.reason(),
                 dayTotals.amountCents(), dayTotals.loadCount(), weekTotals.amountCents());

        return LoadFundsOutcome.fresh(decision);
    }

    private Totals convertTotalsEntityToModel(final TotalsEntity totalsEntity) {
        return new Totals(totalsEntity.getAmountCents(), (int) totalsEntity.getLoadCount());
    }
}

