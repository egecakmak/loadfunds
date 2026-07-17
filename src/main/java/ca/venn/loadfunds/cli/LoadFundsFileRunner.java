package ca.venn.loadfunds.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.controller.LoadFundsRequest;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.logging.CorrelationIdFilter;
import ca.venn.loadfunds.util.Money;
import ca.venn.loadfunds.service.LoadFundsService;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty("velocity.input")
@Slf4j
public class LoadFundsFileRunner implements ApplicationRunner {

    private static final Logger WIRE = LoggerFactory.getLogger("WIRE");

    private final LoadFundsService service;
    private final ObjectMapper mapper;
    private final Path input;
    private final Path output;   // null -> stdout

    public LoadFundsFileRunner(LoadFundsService service, ObjectMapper mapper,
                               @Value("${velocity.input}") Path input,
                               @Value("${velocity.output:#{null}}") Path output) {
        this.service = service;
        this.mapper = mapper;
        this.input = input;
        this.output = output;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.atInfo()
           .addKeyValue("input", input.toString())
           .addKeyValue("output", output != null ? output.toString() : "stdout")
           .log("Starting file load run");

        try (var lines = Files.lines(input); var out = writer()) {

            final AtomicInteger processed = new AtomicInteger();
            final AtomicInteger duplicated = new AtomicInteger();
            final AtomicInteger failed = new AtomicInteger();

            lines.filter(l -> !l.isBlank()).forEach(line -> {
                try {
                    LoadFundsRequest req = mapper.readValue(line, LoadFundsRequest.class);
                    try (var ignored = MDC.putCloseable(CorrelationIdFilter.REQUEST_ID, req.id())) {
                        LoadFundsOutcome outcome = service.decide(
                            new LoadFundsAttempt(req.id(),
                                                 req.customerId(),
                                                 Money.toCents(req.loadAmount()),
                                                 req.time()));

                        // repeated load ids get no response.
                        if (outcome.duplicate()) {
                            duplicated.getAndIncrement();
                            return;
                        }

                        String response = mapper.writeValueAsString(
                            new LoadFundsFileResponse(req.id(), req.customerId(), outcome.decision().accepted()));

                        out.write(response);
                        out.newLine();
                        processed.getAndIncrement();
                    }
                } catch (Exception e) {
                    failed.getAndIncrement();
                    log.error("Skipping processing line due to error: {}", line, e);
                }
            });
            log.atInfo()
               .addKeyValue("written", processed.get())
               .addKeyValue("duplicates", duplicated.get())
               .addKeyValue("failed", failed.get())
               .log("File load run complete");
        }
    }

    private BufferedWriter writer() throws IOException {
        return output != null
            ? Files.newBufferedWriter(output)
            : new NonClosingBufferedWriter(System.out);
    }

    // When output is stdout, close should only flush; closing System.out can
    // break later console output from the app.
    private static final class NonClosingBufferedWriter extends BufferedWriter {

        private NonClosingBufferedWriter(OutputStream output) {
            super(new OutputStreamWriter(output));
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private record LoadFundsFileResponse(
        String id,
        @JsonProperty("customer_id") String customerId,
        boolean accepted
    ) {}
}
