package ca.venn.loadfunds.model.loadfunds.controller;

import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;

public enum LoadFundsResponseOutcome {
    ACCEPTED,
    DECLINED,
    DUPLICATE_ACCEPTED,
    DUPLICATE_DECLINED;

    public static LoadFundsResponseOutcome from(LoadFundsOutcome outcome) {
        if (outcome.duplicate()) {
            return outcome.decision().accepted() ? DUPLICATE_ACCEPTED : DUPLICATE_DECLINED;
        }
        return outcome.decision().accepted() ? ACCEPTED : DECLINED;
    }
}
