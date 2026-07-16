package ca.venn.loadfunds.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ControllerTimingInterceptorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ControllerTimingInterceptor interceptor = new ControllerTimingInterceptor(registry);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void recordsSuccessfulControllerTimerWithHandlerMethodName() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HandlerMethod handler = handlerMethod("loadFunds");

        boolean continueChain = interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        assertThat(continueChain).isTrue();
        assertThat(timerCount("loadFunds", "success")).isEqualTo(1L);
    }

    @Test
    void recordsErrorControllerTimerWithHandlerMethodName() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HandlerMethod handler = handlerMethod("loadFunds");

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, new IllegalStateException("failed"));

        assertThat(timerCount("loadFunds", "error")).isEqualTo(1L);
    }

    @Test
    void doesNothingWhenNoTimerSampleWasStarted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        interceptor.afterCompletion(request, response, handlerMethod("loadFunds"), null);

        assertThat(registry.find("loadfunds.controller").timer()).isNull();
    }

    @Test
    void recordsUnknownHandlerWhenHandlerIsNotAMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        assertThat(timerCount("unknown", "success")).isEqualTo(1L);
    }

    private static HandlerMethod handlerMethod(String methodName) throws NoSuchMethodException {
        Method method = TestController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(new TestController(), method);
    }

    private long timerCount(String handler, String outcome) {
        return registry.get("loadfunds.controller")
                       .tag("handler", handler)
                       .tag("outcome", outcome)
                       .timer()
                       .count();
    }

    private static final class TestController {

        @SuppressWarnings("unused")
        void loadFunds() {
        }
    }
}
