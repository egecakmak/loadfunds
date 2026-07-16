package ca.venn.loadfunds.controller;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.util.Money;
import ca.venn.loadfunds.service.LoadFundsService;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsRequest;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsResponse;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsResponseOutcome;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsResponseRejectionReason;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@Slf4j
@RequestMapping("/funds")
public class LoadFundsController {

    private final LoadFundsService loadFundsService;

    @PostMapping
    public LoadFundsResponse loadFunds(@Valid @RequestBody LoadFundsRequest req) {

        log.atDebug()
           .addKeyValue("loadFundsId", req.id())
           .addKeyValue("customerId", req.customerId())
           .log("Received load funds request");

        LoadFundsAttempt loadFundsAttempt = new LoadFundsAttempt(req.id(),
                                                                 req.customerId(),
                                                                 Money.toCents(req.loadAmount()),
                                                                 req.time());

        LoadFundsOutcome outcome = loadFundsService.decide(loadFundsAttempt);
        LoadFundsResponse response = new LoadFundsResponse(
            req.id(),
            req.customerId(),
            LoadFundsResponseOutcome.from(outcome),
            LoadFundsResponseRejectionReason.from(outcome.decision().reason())
        );

        log.atDebug()
           .addKeyValue("loadFundsId", req.id())
           .addKeyValue("customerId", req.customerId())
           .addKeyValue("outcome", response.outcome())
           .addKeyValue("reason", response.rejectionReason())
           .log("Returning load funds response");

        return response;

    }

}
