package ca.venn.loadfunds.service;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;

public interface LoadFundsService {

    LoadFundsOutcome decide(LoadFundsAttempt in);
}
