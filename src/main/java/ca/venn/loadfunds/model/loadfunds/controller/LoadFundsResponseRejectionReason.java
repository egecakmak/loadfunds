package ca.venn.loadfunds.model.loadfunds.controller;

import ca.venn.loadfunds.model.velocity.RejectionReason;

public enum LoadFundsResponseRejectionReason {
    DAILY_COUNT_EXCEEDED,
    DAILY_AMOUNT_EXCEEDED,
    WEEKLY_AMOUNT_EXCEEDED;

    public static LoadFundsResponseRejectionReason from(RejectionReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case DAILY_COUNT_EXCEEDED -> DAILY_COUNT_EXCEEDED;
            case DAILY_AMOUNT_EXCEEDED -> DAILY_AMOUNT_EXCEEDED;
            case WEEKLY_AMOUNT_EXCEEDED -> WEEKLY_AMOUNT_EXCEEDED;
        };
    }
}
