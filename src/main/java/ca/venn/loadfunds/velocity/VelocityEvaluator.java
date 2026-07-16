package ca.venn.loadfunds.velocity;

import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import ca.venn.loadfunds.model.velocity.Totals;
import ca.venn.loadfunds.model.velocity.VelocityLimits;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@AllArgsConstructor
public class VelocityEvaluator {

    private final VelocityLimits limits;

    public Decision evaluate(long amountCents, Totals day, Totals week) {

        long dayCountAfterThisLoad = day.loadCount() + 1L;
        logCheck("dailyCount", dayCountAfterThisLoad, limits.dailyLoadCount());
        if (dayCountAfterThisLoad > limits.dailyLoadCount()) {
            Decision decision = Decision.decline(RejectionReason.DAILY_COUNT_EXCEEDED);
            logDecision(decision, amountCents, dayCountAfterThisLoad, dayAmountAfterThisLoad(day, amountCents),
                        weekAmountAfterThisLoad(week, amountCents));
            return decision;
        }

        long dayAmountAfterThisLoad = day.amountCents() + amountCents;
        logCheck("dailyAmount", dayAmountAfterThisLoad, limits.dailyAmountCents());
        if (dayAmountAfterThisLoad > limits.dailyAmountCents()) {
            Decision decision = Decision.decline(RejectionReason.DAILY_AMOUNT_EXCEEDED);
            logDecision(decision, amountCents, dayCountAfterThisLoad, dayAmountAfterThisLoad,
                        weekAmountAfterThisLoad(week, amountCents));
            return decision;
        }

        long weekAmountAfterThisLoad = week.amountCents() + amountCents;
        logCheck("weeklyAmount", weekAmountAfterThisLoad, limits.weeklyAmountCents());
        if (weekAmountAfterThisLoad > limits.weeklyAmountCents()) {
            Decision decision = Decision.decline(RejectionReason.WEEKLY_AMOUNT_EXCEEDED);
            logDecision(decision, amountCents, dayCountAfterThisLoad, dayAmountAfterThisLoad,
                        weekAmountAfterThisLoad);
            return decision;
        }

        Decision decision = Decision.accept();
        logDecision(decision, amountCents, dayCountAfterThisLoad, dayAmountAfterThisLoad, weekAmountAfterThisLoad);
        return decision;
    }

    /** headroom = threshold - value after this load; a negative value means this check tripped. */
    private void logCheck(String limit, long afterThisLoad, long threshold) {
        log.atDebug()
           .addKeyValue("limit", limit)
           .addKeyValue("afterThisLoad", afterThisLoad)
           .addKeyValue("threshold", threshold)
           .addKeyValue("headroom", threshold - afterThisLoad)
           .log("Velocity check");
    }

    private void logDecision(Decision decision,
                             long amountCents,
                             long dayCountAfterThisLoad,
                             long dayAmountAfterThisLoad,
                             long weekAmountAfterThisLoad) {
        log.atDebug()
           .addKeyValue("accepted", decision.accepted())
           .addKeyValue("reason", decision.reason())
           .addKeyValue("amountCents", amountCents)
           .addKeyValue("dayCountAfterThisLoad", dayCountAfterThisLoad)
           .addKeyValue("dailyCountLimit", limits.dailyLoadCount())
           .addKeyValue("dayAmountCentsAfterThisLoad", dayAmountAfterThisLoad)
           .addKeyValue("dailyAmountLimitCents", limits.dailyAmountCents())
           .addKeyValue("weekAmountCentsAfterThisLoad", weekAmountAfterThisLoad)
           .addKeyValue("weeklyAmountLimitCents", limits.weeklyAmountCents())
           .log("Velocity decision");
    }

    private long dayAmountAfterThisLoad(Totals day, long amountCents) {
        return day.amountCents() + amountCents;
    }

    private long weekAmountAfterThisLoad(Totals week, long amountCents) {
        return week.amountCents() + amountCents;
    }

}
