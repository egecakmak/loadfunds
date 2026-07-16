package ca.venn.loadfunds.model.loadfunds;

import ca.venn.loadfunds.model.velocity.Decision;

/**
 * duplicate=true -> load_funds_id already seen for this customer.
 */
public record LoadFundsOutcome(Decision decision, boolean duplicate) {

    public static LoadFundsOutcome fresh(Decision d) {
        return new LoadFundsOutcome(d, false);
    }

    public static LoadFundsOutcome duplicate(Decision d) {
        return new LoadFundsOutcome(d, true);
    }
}
