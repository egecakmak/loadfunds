package ca.venn.loadfunds.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import ca.venn.loadfunds.replay.LoadFundsReplay.ReplayConfig;
import ca.venn.loadfunds.replay.LoadFundsReplay.ReplaySummary;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LoadFundsReplayTest {

    private final JsonMapper mapper = new JsonMapper();
    private final List<String> receivedIds = new ArrayList<>();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void replaysInOrderAndVerifiesFreshResponsesAgainstGroundTruth() throws Exception {
        Set<String> seen = new HashSet<>();
        URI baseUrl = startServer(request -> {
            String id = request.path("id").asText();
            String customerId = request.path("customer_id").asText();
            receivedIds.add(id);
            boolean duplicate = !seen.add(customerId + ":" + id);
            boolean accepted = !id.equals("load-2");
            String outcome = duplicate
                ? (accepted ? "DUPLICATE_ACCEPTED" : "DUPLICATE_DECLINED")
                : (accepted ? "ACCEPTED" : "DECLINED");
            return new Response(200,
                "{\"id\":\"%s\",\"customer_id\":\"%s\",\"outcome\":\"%s\"}"
                    .formatted(id, customerId, outcome));
        });
        Path input = writeInput(
            request("load-1"),
            request("load-2"),
            request("load-1"),
            request("load-1", "customer-2")
        );
        Path groundTruth = tempDir.resolve("expected.jsonl");
        Files.write(groundTruth, List.of(
            "{\"id\":\"load-1\",\"customer_id\":\"customer-1\",\"accepted\":true}",
            "{\"id\":\"load-2\",\"customer_id\":\"customer-1\",\"accepted\":false}",
            "{\"id\":\"load-1\",\"customer_id\":\"customer-2\",\"accepted\":true}"
        ));
        Path output = tempDir.resolve("output.jsonl");
        Path detailedOutput = tempDir.resolve("responses.jsonl");

        ReplaySummary summary = LoadFundsReplay.replay(
            config(baseUrl, input, output, detailedOutput, groundTruth)
        );

        assertThat(receivedIds).containsExactly("load-1", "load-2", "load-1", "load-1");
        assertThat(summary.successful()).isTrue();
        assertThat(summary.input()).isEqualTo(4);
        assertThat(summary.sent()).isEqualTo(4);
        assertThat(summary.accepted()).isEqualTo(2);
        assertThat(summary.declined()).isEqualTo(1);
        assertThat(summary.duplicates()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(summary.verificationFailures()).isZero();

        assertThat(Files.readAllLines(output)).containsExactly(
            "{\"id\":\"load-1\",\"customer_id\":\"customer-1\",\"accepted\":true}",
            "{\"id\":\"load-2\",\"customer_id\":\"customer-1\",\"accepted\":false}",
            "{\"id\":\"load-1\",\"customer_id\":\"customer-2\",\"accepted\":true}"
        );

        List<JsonNode> responses = readJsonLines(detailedOutput);
        assertThat(responses).extracting(node -> node.path("sequence").asInt())
            .containsExactly(1, 2, 3, 4);
        assertThat(responses).extracting(node -> node.path("requestId").asText())
            .containsExactly("load-1", "load-2", "load-1", "load-1");
    }

    @Test
    void recordsMalformedInputAndUnexpectedStatusesAsFailures() throws Exception {
        URI baseUrl = startServer(request -> new Response(500, "{\"error\":\"failed\"}"));
        Path input = writeInput("{not-json", request("load-1"));
        Path output = tempDir.resolve("output.jsonl");
        Path detailedOutput = tempDir.resolve("responses.jsonl");

        ReplaySummary summary = LoadFundsReplay.replay(
            config(baseUrl, input, output, detailedOutput, null)
        );

        assertThat(summary.successful()).isFalse();
        assertThat(summary.input()).isEqualTo(2);
        assertThat(summary.sent()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(2);
        assertThat(Files.readAllLines(output)).isEmpty();
        List<JsonNode> responses = readJsonLines(detailedOutput);
        assertThat(responses.get(0).path("status").isNull()).isTrue();
        assertThat(responses.get(0).path("error").asText()).contains("Unexpected character");
        assertThat(responses.get(1).path("status").asInt()).isEqualTo(500);
        assertThat(responses.get(1).path("error").asText()).isEqualTo("Unexpected HTTP status 500");
    }

    @Test
    void parsesCommandLineDefaults() {
        ReplayConfig config = ReplayConfig.parse(new String[] {"--input=input.jsonl"});

        assertThat(config.baseUrl()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(config.output()).isEqualTo(
            Path.of("src/integrationTest/resources/Venn - Back-End - Replay Generated Output .txt")
        );
        assertThat(config.detailedOutput()).isEqualTo(Path.of("build/replay/responses.jsonl"));
        assertThat(config.fundsUri()).isEqualTo(URI.create("http://localhost:8080/funds"));
    }

    private URI startServer(Function<JsonNode, Response> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/funds", exchange -> handle(exchange, handler));
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange, Function<JsonNode, Response> handler) throws IOException {
        try (exchange) {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            Response response = handler.apply(request);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private ReplayConfig config(
        URI baseUrl,
        Path input,
        Path output,
        Path detailedOutput,
        Path groundTruth
    ) {
        return new ReplayConfig(
            baseUrl,
            input,
            output,
            detailedOutput,
            groundTruth
        );
    }

    private Path writeInput(String... lines) throws IOException {
        Path input = tempDir.resolve("input.jsonl");
        Files.write(input, List.of(lines));
        return input;
    }

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        try (var lines = Files.lines(path)) {
            return lines.map(mapper::readTree).toList();
        }
    }

    private static String request(String id) {
        return request(id, "customer-1");
    }

    private static String request(String id, String customerId) {
        return "{\"id\":\"%s\",\"customer_id\":\"%s\",\"load_amount\":\"$1.00\","
            .formatted(id, customerId) + "\"time\":\"2026-07-16T12:00:00Z\"}";
    }

    private record Response(int status, String body) {
    }
}
