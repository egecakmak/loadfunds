package ca.venn.loadfunds.persistence;

import java.time.Instant;

import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsStatus;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "load_funds_attempt")
@Builder
@AllArgsConstructor
public class LoadFundsAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "load_funds_id", nullable = false, updatable = false)
    private String loadFundsId;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private LoadFundsStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 50, updatable = false)
    private RejectionReason rejectionReason;

    protected LoadFundsAttemptEntity() {}

    public static LoadFundsAttemptEntity of(LoadFundsAttempt a, Decision d) {
        var e = new LoadFundsAttemptEntity();
        e.customerId = a.customerId();
        e.loadFundsId = a.loadId();
        e.amountCents = a.amountCents();
        e.processedAt = a.processedAt();
        e.status = d.accepted() ? LoadFundsStatus.ACCEPTED : LoadFundsStatus.DECLINED;
        e.rejectionReason = d.reason();
        return e;
    }

    public Decision toDecision() {
        return status == LoadFundsStatus.ACCEPTED ? Decision.accept() : Decision.decline(rejectionReason);
    }
}

