package ca.venn.loadfunds.controller;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsRequest;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsResponse;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsResponseOutcome;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsResponseRejectionReason;
import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import ca.venn.loadfunds.service.LoadFundsService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadFundsControllerTest {

    private static final String LOAD_FUNDS_ID = "load-123";
    private static final String CUSTOMER_ID = "customer-456";
    private static final Instant PROCESSED_AT = Instant.parse("2026-07-16T12:00:00Z");

    private static final LoadFundsRequest REQUEST_123_45 = new LoadFundsRequest(
        LOAD_FUNDS_ID, CUSTOMER_ID, "$123.45", PROCESSED_AT);

    private static final LoadFundsRequest REQUEST_25_00 = new LoadFundsRequest(
        LOAD_FUNDS_ID, CUSTOMER_ID, "$25.00", PROCESSED_AT);

    private static final LoadFundsAttempt ATTEMPT_123_45 = new LoadFundsAttempt(
        LOAD_FUNDS_ID,
        CUSTOMER_ID,
        12_345L,
        PROCESSED_AT
    );

    private static final LoadFundsAttempt ATTEMPT_25_00 = new LoadFundsAttempt(
        LOAD_FUNDS_ID,
        CUSTOMER_ID,
        2_500L,
        PROCESSED_AT
    );

    private static final LoadFundsResponse ACCEPTED_RESPONSE = new LoadFundsResponse(
        LOAD_FUNDS_ID, CUSTOMER_ID, LoadFundsResponseOutcome.ACCEPTED, null);

    private static final LoadFundsResponse DECLINED_DAILY_RESPONSE = new LoadFundsResponse(
        LOAD_FUNDS_ID,
        CUSTOMER_ID,
        LoadFundsResponseOutcome.DECLINED,
        LoadFundsResponseRejectionReason.DAILY_AMOUNT_EXCEEDED
    );

    private static final LoadFundsResponse DECLINED_DAILY_COUNT_RESPONSE = new LoadFundsResponse(
        LOAD_FUNDS_ID,
        CUSTOMER_ID,
        LoadFundsResponseOutcome.DECLINED,
        LoadFundsResponseRejectionReason.DAILY_COUNT_EXCEEDED
    );

    private static final LoadFundsResponse DUPLICATE_ACCEPTED_RESPONSE = new LoadFundsResponse(
        LOAD_FUNDS_ID, CUSTOMER_ID, LoadFundsResponseOutcome.DUPLICATE_ACCEPTED, null);

    private static final LoadFundsResponse DUPLICATE_DECLINED_WEEKLY_RESPONSE = new LoadFundsResponse(
        LOAD_FUNDS_ID,
        CUSTOMER_ID,
        LoadFundsResponseOutcome.DUPLICATE_DECLINED,
        LoadFundsResponseRejectionReason.WEEKLY_AMOUNT_EXCEEDED
    );

    @Mock
    private LoadFundsService service;

    @InjectMocks
    private LoadFundsController controller;

    @Test
    void returnsAcceptedResponseAndPassesConvertedAttemptToService() {
        when(service.decide(any()))
            .thenReturn(LoadFundsOutcome.fresh(Decision.accept()));

        LoadFundsResponse response = controller.loadFunds(REQUEST_123_45);

        assertThat(response).isEqualTo(ACCEPTED_RESPONSE);
        verify(service).decide(ATTEMPT_123_45);
    }

    @Test
    void returnsDeclinedResponseWhenServiceDeclinesAttempt() {
        when(service.decide(any()))
            .thenReturn(LoadFundsOutcome.fresh(Decision.decline(RejectionReason.DAILY_AMOUNT_EXCEEDED)));

        LoadFundsResponse response = controller.loadFunds(REQUEST_25_00);

        assertThat(response).isEqualTo(DECLINED_DAILY_RESPONSE);
        verify(service).decide(ATTEMPT_25_00);
    }

    @Test
    void returnsDailyCountRejectionReasonWhenServiceDeclinesAttempt() {
        when(service.decide(any()))
            .thenReturn(LoadFundsOutcome.fresh(Decision.decline(RejectionReason.DAILY_COUNT_EXCEEDED)));

        LoadFundsResponse response = controller.loadFunds(REQUEST_25_00);

        assertThat(response).isEqualTo(DECLINED_DAILY_COUNT_RESPONSE);
    }

    @Test
    void exposesDuplicateStatusAndOriginalDecision() {
        when(service.decide(any()))
            .thenReturn(LoadFundsOutcome.duplicate(Decision.accept()));

        LoadFundsResponse response = controller.loadFunds(REQUEST_25_00);

        assertThat(response).isEqualTo(DUPLICATE_ACCEPTED_RESPONSE);
    }

    @Test
    void exposesDuplicateDeclinedStatusAndOriginalReason() {
        when(service.decide(any()))
            .thenReturn(LoadFundsOutcome.duplicate(Decision.decline(RejectionReason.WEEKLY_AMOUNT_EXCEEDED)));

        LoadFundsResponse response = controller.loadFunds(REQUEST_25_00);

        assertThat(response).isEqualTo(DUPLICATE_DECLINED_WEEKLY_RESPONSE);
    }
}
