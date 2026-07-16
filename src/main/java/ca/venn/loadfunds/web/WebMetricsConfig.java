package ca.venn.loadfunds.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebMetricsConfig {

    public static final String CONTROLLER_TIMING_INTERCEPTOR = "controllerTimingInterceptor";

    @Bean(CONTROLLER_TIMING_INTERCEPTOR)
    ControllerTimingInterceptor controllerTimingInterceptor(MeterRegistry registry) {
        return new ControllerTimingInterceptor(registry);
    }
}
