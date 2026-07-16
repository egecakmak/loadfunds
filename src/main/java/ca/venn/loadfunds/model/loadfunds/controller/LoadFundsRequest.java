package ca.venn.loadfunds.model.loadfunds.controller;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;

public record LoadFundsRequest(

    @NotBlank(message = "id is required")
    String id,

    @JsonProperty("customer_id")
    @NotBlank(message = "customer_id is required")
    String customerId,

    @JsonProperty("load_amount")
    @NotBlank(message = "load_amount is required")
    @Pattern(
        regexp = "^\\$(?:(?:[1-9]\\d*)\\.\\d{2}|0\\.(?:0[1-9]|[1-9]\\d))$",
        message = "load_amount must be a positive monetary amount in the format $123.45"
    )
    String loadAmount,

    @NotNull(message = "time is required")
    @PastOrPresent(message = "time cannot be in the future")
    Instant time
) {

}
