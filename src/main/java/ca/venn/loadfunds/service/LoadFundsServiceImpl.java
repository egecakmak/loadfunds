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

@Slf4j
@Service
@AllArgsConstructor
public class LoadFundsServiceImpl implements LoadFundsService {

    private final LoadFundsAttemptRepository loadFundsAttemptRepository;
    private final CustomerRepository customerRepository;
    private final VelocityEvaluator velocityEvaluator;

    @Override
    // This timeout is effectively unnecessary as we have timeouts for each query but still adding this. Query timeouts
    // could be dropped if desire which would simplify things.
    @Transactional(timeout = 5)
    public LoadFundsOutcome decide(LoadFundsAttempt in) {

        log.atDebug()
           .addKeyValue("customerId", in.customerId())
           .log("Processing load funds attempt");

        // Idempotency check. Concurrent requests race here, but the unique constraint database in that case
        // will act as a guardrail.
        // Has a timeout of 3 seconds.
        var seen = loadFundsAttemptRepository.findByCustomerIdAndLoadFundsId(in.customerId(), in.loadId());
        if (seen.isPresent()) {
            log.atInfo()
               .addKeyValue("loadFundsId", in.loadId())
               .addKeyValue("outcome", "duplicate")
               .log("Duplicate load funds attempt ignored");
            return LoadFundsOutcome.duplicate(seen.get().toDecision());
        }

        // Make sure customerId exists prior to acquiring the lock for it for the sake of this take-home.
        // Has a timeout of 3 seconds.
        customerRepository.ensureExists(in.customerId());

        // Put a lock on the customer to TOCTOU issues with load attempts and velocity decisions
        log.atDebug()
           .addKeyValue("customerId", in.customerId())
           .log("Acquiring customer lock");
        // Has a timeout of 3 seconds.
        customerRepository.lock(in.customerId());
        log.atDebug()
           .addKeyValue("customerId", in.customerId())
           .log("Customer lock acquired");

        Window dayWindow = Window.dayOf(in.processedAt());
        Window weekWindow = Window.weekOf(in.processedAt());

        // Has a timeout of 3 seconds.
        TotalsEntity dayTotalsEntity = loadFundsAttemptRepository.totalsBetween(in.customerId(),
                                                                                LoadFundsStatus.ACCEPTED,
                                                                                dayWindow.from(), dayWindow.to());
        // Has a timeout of 3 seconds.
        TotalsEntity weekTotalsEntity = loadFundsAttemptRepository.totalsBetween(in.customerId(),
                                                                                 LoadFundsStatus.ACCEPTED,
                                                                                 weekWindow.from(), weekWindow.to());

        Totals dayTotals = convertTotalsEntityToModel(dayTotalsEntity);
        Totals weekTotals = convertTotalsEntityToModel(weekTotalsEntity);

        log.atDebug()
           .addKeyValue("dayUsedCents", dayTotals.amountCents())
           .addKeyValue("dayCount", dayTotals.loadCount())
           .addKeyValue("weekUsedCents", weekTotals.amountCents())
           .log("Velocity totals computed");

        Decision decision = velocityEvaluator.evaluate(in.amountCents(), dayTotals, weekTotals);

        // saveAndFlush, not save: push the INSERT to the DB inside the locked
        // region so failures surface here rather than as UnexpectedRollbackException
        // at commit. The unique constraint is a backstop that shouldn't fire.
        // Has a timeout of 3 seconds.
        loadFundsAttemptRepository.saveAndFlush(LoadFundsAttemptEntity.of(in, decision));

        log.atInfo()
           .addKeyValue("loadFundsId", in.loadId())
           .addKeyValue("amountCents", in.amountCents())
           .addKeyValue("accepted", decision.accepted())
           .addKeyValue("reason", decision.reason())
           .addKeyValue("dayUsedCents", dayTotals.amountCents())
           .addKeyValue("dayCount", dayTotals.loadCount())
           .addKeyValue("weekUsedCents", weekTotals.amountCents())
           .log("Load funds attempt recorded");

        return LoadFundsOutcome.fresh(decision);
    }

    private Totals convertTotalsEntityToModel(final TotalsEntity totalsEntity) {
        return new Totals(totalsEntity.getAmountCents(), (int) totalsEntity.getLoadCount());
    }
}
