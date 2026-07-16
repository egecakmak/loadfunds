package ca.venn.loadfunds.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the load-funds service so the {@link LoadFundsService} bean callers see is
 * the fully decorated chain: metrics -> transactional business logic.
 */
@Configuration
public class LoadFundsConfig {

    @Bean
    @Primary
    LoadFundsService loadFundsService(
        @Qualifier("loadFundsServiceImpl") LoadFundsService implementation,
        MeterRegistry registry
    ) {
        return new MeteredLoadFundsService(implementation, registry);
    }
}
