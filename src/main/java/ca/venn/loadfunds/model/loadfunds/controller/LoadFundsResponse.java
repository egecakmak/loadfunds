package ca.venn.loadfunds.model.loadfunds.controller;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoadFundsResponse(
    String id,
    @JsonProperty("customer_id") String customerId,
    LoadFundsResponseOutcome outcome,
    @JsonProperty("rejection_reason") LoadFundsResponseRejectionReason rejectionReason
) {}
