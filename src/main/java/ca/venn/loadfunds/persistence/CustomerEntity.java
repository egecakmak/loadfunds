package ca.venn.loadfunds.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer")
public class CustomerEntity {

    @Id
    private String id;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected CustomerEntity() {}

}

