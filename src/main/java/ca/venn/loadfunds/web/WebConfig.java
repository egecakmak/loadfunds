package ca.venn.loadfunds.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the local demo metrics dashboard at {@code /dashboard} (a friendly alias
 * for the static {@code dashboard.html}), and registers the controller-latency
 * interceptor for the load endpoint. Available whenever the service is running.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ControllerTimingInterceptor controllerTimingInterceptor;

    public WebConfig(
        @Qualifier(WebMetricsConfig.CONTROLLER_TIMING_INTERCEPTOR)
        ControllerTimingInterceptor controllerTimingInterceptor
    ) {
        this.controllerTimingInterceptor = controllerTimingInterceptor;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/dashboard").setViewName("redirect:/dashboard.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(controllerTimingInterceptor).addPathPatterns("/funds");
    }
}
