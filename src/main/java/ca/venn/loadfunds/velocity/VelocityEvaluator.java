package ca.venn.loadfunds.velocity;

import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import ca.venn.loadfunds.model.velocity.Totals;
import ca.venn.loadfunds.model.velocity.VelocityLimits;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class VelocityEvaluator {

    private final VelocityLimits limits;

    public Decision evaluate(long amountCents, Totals day, Totals week) {

        if (day.loadCount() + 1 > limits.dailyLoadCount())
            return Decision.decline(RejectionReason.DAILY_COUNT_EXCEEDED);

        if (day.amountCents() + amountCents > limits.dailyAmountCents())
            return Decision.decline(RejectionReason.DAILY_AMOUNT_EXCEEDED);

        if (week.amountCents() + amountCents > limits.weeklyAmountCents())
            return Decision.decline(RejectionReason.WEEKLY_AMOUNT_EXCEEDED);

        return Decision.accept();
    }

}

