package ca.venn.loadfunds.cli;

import ca.venn.loadfunds.model.loadfunds.LoadFundsAttempt;
import ca.venn.loadfunds.model.loadfunds.LoadFundsOutcome;
import ca.venn.loadfunds.model.velocity.Decision;
import ca.venn.loadfunds.model.velocity.RejectionReason;
import ca.venn.loadfunds.logging.CorrelationIdFilter;
import ca.venn.loadfunds.service.LoadFundsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class LoadFundsFileRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void writesResponsesForNonDuplicateLoads() throws Exception {
        Path input = tempDir.resolve("input.jsonl");
        Path output = tempDir.resolve("output.jsonl");
        Files.write(input, List.of(
            "{\"id\":\"load-1\",\"customer_id\":\"customer-1\",\"load_amount\":\"$123.45\",\"time\":\"2026-07-16T12:00:00Z\"}",
            "  ",
            "{\"id\":\"load-2\",\"customer_id\":\"customer-1\",\"load_amount\":\"$10.00\",\"time\":\"2026-07-16T12:01:00Z\"}",
            "{not-json}",
            "{\"id\":\"load-3\",\"customer_id\":\"customer-1\",\"load_amount\":\"$20.50\",\"time\":\"2026-07-16T12:02:00Z\"}"
        ));
        QueuedLoadFundsService service = new QueuedLoadFundsService(
            LoadFundsOutcome.fresh(Decision.accept()),
            LoadFundsOutcome.duplicate(Decision.accept()),
            LoadFundsOutcome.fresh(Decision.decline(RejectionReason.WEEKLY_AMOUNT_EXCEEDED))
        );
        LoadFundsFileRunner runner = new LoadFundsFileRunner(
            service,
            new JsonMapper(),
            input,
            output
        );

        runner.run(null);

        assertThat(Files.readAllLines(output)).containsExactly(
            "{\"id\":\"load-1\",\"customer_id\":\"customer-1\",\"accepted\":true}",
            "{\"id\":\"load-3\",\"customer_id\":\"customer-1\",\"accepted\":false}"
        );
        assertThat(service.attempts()).containsExactly(
            new LoadFundsAttempt("load-1", "customer-1", 12_345L, Instant.parse("2026-07-16T12:00:00Z")),
            new LoadFundsAttempt("load-2", "customer-1", 1_000L, Instant.parse("2026-07-16T12:01:00Z")),
            new LoadFundsAttempt("load-3", "customer-1", 2_050L, Instant.parse("2026-07-16T12:02:00Z"))
        );
        assertThat(service.requestIdsAtCall()).containsExactly("load-1", "load-2", "load-3");
        assertThat(MDC.get(CorrelationIdFilter.REQUEST_ID)).isNull();
    }

    @Test
    void treatsServiceFailuresAsFailedLinesAndContinuesProcessing() throws Exception {
        Path input = tempDir.resolve("input.jsonl");
        Path output = tempDir.resolve("output.jsonl");
        Files.write(input, List.of(
            "{\"id\":\"load-1\",\"customer_id\":\"customer-1\",\"load_amount\":\"$1.00\",\"time\":\"2026-07-16T12:00:00Z\"}",
            "{\"id\":\"load-2\",\"customer_id\":\"customer-1\",\"load_amount\":\"$2.00\",\"time\":\"2026-07-16T12:01:00Z\"}"
        ));
        LoadFundsService service = new LoadFundsService() {
            private int calls;

            @Override
            public LoadFundsOutcome decide(LoadFundsAttempt in) {
                calls++;
                if (calls == 1) {
                    throw new IllegalStateException("service failed");
                }
                return LoadFundsOutcome.fresh(Decision.accept());
            }
        };
        LoadFundsFileRunner runner = new LoadFundsFileRunner(
            service,
            new JsonMapper(),
            input,
            output
        );

        runner.run(null);

        assertThat(Files.readAllLines(output)).containsExactly(
            "{\"id\":\"load-2\",\"customer_id\":\"customer-1\",\"accepted\":true}"
        );
    }

    @Test
    void writesResponsesToStdoutWithoutClosingIt() throws Exception {
        Path input = tempDir.resolve("input.jsonl");
        Files.writeString(input,
            "{\"id\":\"load-1\",\"customer_id\":\"customer-1\",\"load_amount\":\"$1.00\","
                + "\"time\":\"2026-07-16T12:00:00Z\"}\n");
        LoadFundsFileRunner runner = new LoadFundsFileRunner(
            ignored -> LoadFundsOutcome.fresh(Decision.accept()),
            new JsonMapper(),
            input,
            null
        );
        PrintStream originalStdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

            runner.run(null);
            System.out.print("still-open");
        } finally {
            System.setOut(originalStdout);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8))
            .contains("{\"id\":\"load-1\",\"customer_id\":\"customer-1\",\"accepted\":true}"
                + System.lineSeparator())
            .endsWith("still-open");
    }

    private static final class QueuedLoadFundsService implements LoadFundsService {

        private final Queue<LoadFundsOutcome> outcomes;
        private final List<LoadFundsAttempt> attempts = new ArrayList<>();
        private final List<String> requestIdsAtCall = new ArrayList<>();

        private QueuedLoadFundsService(LoadFundsOutcome... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public LoadFundsOutcome decide(LoadFundsAttempt in) {
            attempts.add(in);
            requestIdsAtCall.add(MDC.get(CorrelationIdFilter.REQUEST_ID));
            return outcomes.remove();
        }

        private List<LoadFundsAttempt> attempts() {
            return attempts;
        }

        private List<String> requestIdsAtCall() {
            return requestIdsAtCall;
        }
    }
}
