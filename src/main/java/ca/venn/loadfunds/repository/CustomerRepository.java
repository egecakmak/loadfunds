package ca.venn.loadfunds.repository;

import java.util.Optional;

import ca.venn.loadfunds.persistence.CustomerEntity;
import io.micrometer.core.annotation.Timed;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {

    String QUERY_TIMEOUT_MS = "3000";
    String LOCK_TIMEOUT_MS = "3000";

    /**
     * Atomic H2 upsert. The key-based form serializes concurrent first requests
     * for the same customer on the primary-key index instead of allowing both
     * transactions to observe an absent row and race to insert it.
     */
    @Modifying
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = QUERY_TIMEOUT_MS))
    @Query(value = "MERGE INTO customer (id) KEY (id) VALUES (:id)", nativeQuery = true)
    void ensureExists(@Param("id") String id);

    /**
     * SELECT ... FOR UPDATE. Serializes velocity decisions for this customer
     * until the transaction ends; released by commit or rollback, so a crashed
     * process needs no cleanup.
     *
     * Explicit @Query, not findById: a derived find can be served from the
     * persistence context without reaching the DB, and a lock that doesn't
     * reach the DB isn't a lock.
     */
    @Timed(value = "loadfunds.customer.lock", description = "Time to acquire the pessimistic customer lock")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MS),
        @QueryHint(name = "jakarta.persistence.query.timeout", value = QUERY_TIMEOUT_MS)
    })
    @Query("select c from CustomerEntity c where c.id = :id")
    Optional<CustomerEntity> lock(@Param("id") String id);
}
