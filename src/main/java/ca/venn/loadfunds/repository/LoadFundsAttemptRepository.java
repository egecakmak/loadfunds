package ca.venn.loadfunds.repository;

import java.time.Instant;
import java.util.Optional;

import ca.venn.loadfunds.model.loadfunds.LoadFundsStatus;
import ca.venn.loadfunds.persistence.LoadFundsAttemptEntity;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface LoadFundsAttemptRepository extends JpaRepository<LoadFundsAttemptEntity, Long> {

    String QUERY_TIMEOUT_MS = "3000";

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = QUERY_TIMEOUT_MS))
    Optional<LoadFundsAttemptEntity> findByCustomerIdAndLoadFundsId(String customerId, String loadId);

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = QUERY_TIMEOUT_MS))
    @Query("""
        select coalesce(sum(a.amountCents), 0L) as amountCents,
               count(a)                         as loadCount
          from LoadFundsAttemptEntity a
         where a.customerId = :customerId
           and a.status = :status
           and a.processedAt >= :from
           and a.processedAt <  :to
        """)
    TotalsEntity totalsBetween(@Param("customerId") String customerId,
                               @Param("status") LoadFundsStatus status,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    interface TotalsEntity {
        long getAmountCents();
        long getLoadCount();
    }
}
