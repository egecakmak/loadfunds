package ca.venn.loadfunds.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Times controller handler execution into the {@code loadfunds.controller} timer,
 * tagged by handler method and outcome. Kept out of the controller itself: the
 * interceptor spans from just before the handler runs to after it completes.
 */
@AllArgsConstructor
public class ControllerTimingInterceptor implements HandlerInterceptor {

    private static final String SAMPLE = ControllerTimingInterceptor.class.getName() + ".sample";

    private final MeterRegistry registry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(SAMPLE, Timer.start(registry));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!(request.getAttribute(SAMPLE) instanceof Timer.Sample sample)) return;
        String handlerName = handler instanceof HandlerMethod hm ? hm.getMethod().getName() : "unknown";
        sample.stop(registry.timer("loadfunds.controller",
                                   "handler", handlerName,
                                   "outcome", ex == null ? "success" : "error"));
    }
}
