package ca.venn.loadfunds.service;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decorates {@link LoadFundsService} to record metrics.
 */
@AllArgsConstructor
@Slf4j
public class MeteredLoadFundsService implements LoadFundsService {

    private static final String NONE = "none";

    private final LoadFundsService delegate;
    private final MeterRegistry registry;


    @Override
    public LoadFundsOutcome decide(LoadFundsAttempt in) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        String reason = NONE;
        try {
            LoadFundsOutcome result = delegate.decide(in);
            outcome = outcomeOf(result);
            reason = reasonOf(result);
            return result;
        } finally {
            sample.stop(registry.timer("loadfunds.decide", "outcome", outcome));
            registry.counter("loadfunds.decisions", "outcome", outcome, "reason", reason).increment();
            log.atDebug()
               .addKeyValue("loadFundsId", in.loadId())
               .addKeyValue("outcome", outcome)
               .addKeyValue("reason", reason)
               .log("Load funds decision metrics recorded");
        }
    }

    private static String outcomeOf(LoadFundsOutcome result) {
        if (result.duplicate()) return "duplicate";
        return result.decision().accepted() ? "accepted" : "declined";
    }

    private static String reasonOf(LoadFundsOutcome result) {
        return result.decision().reason() != null ? result.decision().reason().name() : NONE;
    }
}
