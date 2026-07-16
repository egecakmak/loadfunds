package ca.venn.loadfunds.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestIdAndIgnoresInboundHeader() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Map<String, String>> context = new AtomicReference<>();
        FilterChain chain = (req, res) -> context.set(MDC.getCopyOfContextMap());

        filter.doFilterInternal(request, response, chain);

        assertThat(context.get())
            .hasSize(1)
            .containsKey(CorrelationIdFilter.REQUEST_ID)
            .doesNotContainValue("caller-supplied");
        verifyNoInteractions(request);
        verify(response).setHeader("X-Request-Id", context.get().get(CorrelationIdFilter.REQUEST_ID));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void removesRequestIdWhenRequestFails() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("request failed");
        };

        assertThatThrownBy(
            () -> filter.doFilterInternal(request, response, chain)
        ).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.REQUEST_ID)).isNull();
    }
}
