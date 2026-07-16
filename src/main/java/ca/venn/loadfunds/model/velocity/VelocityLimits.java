package ca.venn.loadfunds.model.velocity;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("velocity.limits")
@Validated
public record VelocityLimits(
    @Positive long dailyAmountCents,
    @Positive long weeklyAmountCents,
    @Positive int dailyLoadCount
) {}

