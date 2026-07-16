package ca.venn.loadfunds.model.loadfunds;

import java.time.Instant;

public record LoadFundsAttempt(String loadId,
                               String customerId,
                               long amountCents,
                               Instant processedAt) {

}
