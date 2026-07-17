package ca.venn.loadfunds.replay;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class LoadFundsReplay {

    private static final ObjectMapper MAPPER = new JsonMapper();

    private LoadFundsReplay() {
    }

    public static void main(String[] args) throws Exception {
        ReplaySummary summary = replay(ReplayConfig.parse(args));
        System.err.println(summary.format());
        if (!summary.successful()) {
            System.exit(1);
        }
    }

    public static ReplaySummary replay(ReplayConfig config) throws IOException {
        config.validate();
        List<RequestLine> requests = readRequests(config.input());
        List<ReplayResult> results = new ArrayList<>(requests.size());

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

        long started = System.nanoTime();
        for (RequestLine request : requests) {
            results.add(send(client, config, request));
        }

        writeOutput(config.output(), results);
        writeDetailedOutput(config.detailedOutput(), results);
        int verificationFailures = config.groundTruth() == null
            ? 0
            : verify(config.groundTruth(), results);
        return summarize(results, verificationFailures, Duration.ofNanos(System.nanoTime() - started));
    }

    private static List<RequestLine> readRequests(Path input) throws IOException {
        List<RequestLine> requests = new ArrayList<>();
        try (var lines = Files.lines(input)) {
            lines.filter(line -> !line.isBlank())
                .forEach(line -> requests.add(new RequestLine(requests.size() + 1, line)));
        }
        return requests;
    }

    private static ReplayResult send(HttpClient client, ReplayConfig config, RequestLine request) {
        long started = System.nanoTime();
        String requestId = null;
        try {
            JsonNode input = MAPPER.readTree(request.body());
            requestId = requiredText(input, "id");
            requiredText(input, "customer_id");

            HttpRequest httpRequest = HttpRequest.newBuilder(config.fundsUri())
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.body()))
                .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = null;
            String error = null;
            try {
                responseBody = MAPPER.readTree(response.body());
            } catch (Exception e) {
                error = "Response body is not JSON";
            }
            if (response.statusCode() != 200) {
                error = "Unexpected HTTP status " + response.statusCode();
            }
            return new ReplayResult(
                request.sequence(),
                requestId,
                response.statusCode(),
                responseBody,
                response.body(),
                error,
                elapsedMillis(started)
            );
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ReplayResult(
                request.sequence(),
                requestId,
                null,
                null,
                null,
                e.getClass().getSimpleName() + ": " + e.getMessage(),
                elapsedMillis(started)
            );
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing text field '" + field + "'");
        }
        return value.asText();
    }

    private static void writeOutput(Path output, List<ReplayResult> results) throws IOException {
        createParentDirectories(output);
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            for (ReplayResult result : results) {
                if (result.error() != null || result.body() == null
                    || duplicate(result.body())) {
                    continue;
                }
                writer.write(MAPPER.writeValueAsString(new ReplayOutput(
                    requiredText(result.body(), "id"),
                    requiredText(result.body(), "customer_id"),
                    accepted(result.body())
                )));
                writer.newLine();
            }
        }
    }

    private static void writeDetailedOutput(Path output, List<ReplayResult> results) throws IOException {
        createParentDirectories(output);
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            for (ReplayResult result : results) {
                writer.write(MAPPER.writeValueAsString(result));
                writer.newLine();
            }
        }
    }

    private static void createParentDirectories(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static int verify(Path groundTruth, List<ReplayResult> results) throws IOException {
        Map<DecisionKey, ExpectedDecision> expected = new LinkedHashMap<>();
        try (var lines = Files.lines(groundTruth)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                JsonNode node = MAPPER.readTree(line);
                String id = requiredText(node, "id");
                String customerId = requiredText(node, "customer_id");
                ExpectedDecision previous = expected.put(
                    new DecisionKey(customerId, id),
                    new ExpectedDecision(node.path("accepted").asBoolean())
                );
                if (previous != null) {
                    throw new IllegalArgumentException(
                        "Duplicate ground-truth decision for customer_id=" + customerId + ", id=" + id
                    );
                }
            });
        }

        int failures = 0;
        Set<DecisionKey> compared = new HashSet<>();
        for (ReplayResult result : results) {
            if (result.error() != null || result.body() == null || duplicate(result.body())) {
                continue;
            }
            String id = result.body().path("id").asText();
            String customerId = result.body().path("customer_id").asText();
            DecisionKey key = new DecisionKey(customerId, id);
            ExpectedDecision decision = expected.get(key);
            if (decision == null) {
                failures++;
            } else {
                compared.add(key);
                if (decision.accepted() != accepted(result.body())) {
                    failures++;
                }
            }
        }
        failures += expected.size() - compared.size();
        return failures;
    }

    private static ReplaySummary summarize(
        List<ReplayResult> results,
        int verificationFailures,
        Duration duration
    ) {
        int sent = 0;
        int accepted = 0;
        int declined = 0;
        int duplicates = 0;
        int failed = 0;
        for (ReplayResult result : results) {
            if (result.status() != null) {
                sent++;
            }
            if (result.error() != null || result.body() == null) {
                failed++;
            } else if (duplicate(result.body())) {
                duplicates++;
            } else if (accepted(result.body())) {
                accepted++;
            } else {
                declined++;
            }
        }
        return new ReplaySummary(
            results.size(), sent, accepted, declined, duplicates, failed, verificationFailures, duration
        );
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static boolean accepted(JsonNode body) {
        String outcome = requiredText(body, "outcome");
        return outcome.equals("ACCEPTED") || outcome.equals("DUPLICATE_ACCEPTED");
    }

    private static boolean duplicate(JsonNode body) {
        String outcome = requiredText(body, "outcome");
        return outcome.equals("DUPLICATE_ACCEPTED") || outcome.equals("DUPLICATE_DECLINED");
    }

    private record RequestLine(int sequence, String body) {
    }

    private record DecisionKey(String customerId, String id) {
    }

    private record ExpectedDecision(boolean accepted) {
    }

    private record ReplayOutput(
        String id,
        @com.fasterxml.jackson.annotation.JsonProperty("customer_id") String customerId,
        boolean accepted
    ) {
    }

    public record ReplayResult(
        int sequence,
        String requestId,
        Integer status,
        JsonNode body,
        String rawBody,
        String error,
        long durationMs
    ) {
    }

    public record ReplaySummary(
        int input,
        int sent,
        int accepted,
        int declined,
        int duplicates,
        int failed,
        int verificationFailures,
        Duration duration
    ) {
        public boolean successful() {
            return failed == 0 && verificationFailures == 0;
        }

        public String format() {
            return "Replay complete: input=%d sent=%d accepted=%d declined=%d duplicates=%d failed=%d "
                .formatted(input, sent, accepted, declined, duplicates, failed)
                + "verificationFailures=%d durationMs=%d"
                .formatted(verificationFailures, duration.toMillis());
        }
    }

    public record ReplayConfig(
        URI baseUrl,
        Path input,
        Path output,
        Path detailedOutput,
        Path groundTruth
    ) {
        public URI fundsUri() {
            String base = baseUrl.toString();
            return URI.create((base.endsWith("/") ? base : base + "/") + "funds");
        }

        public void validate() {
            if (input == null || output == null || detailedOutput == null || baseUrl == null) {
                throw new IllegalArgumentException("base-url, input, output, and detailed-output are required");
            }
        }

        public static ReplayConfig parse(String[] args) {
            Map<String, String> options = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Expected --name=value but got: " + arg);
                }
                int separator = arg.indexOf('=');
                options.put(arg.substring(2, separator), arg.substring(separator + 1));
            }
            String input = options.get("input");
            if (input == null) {
                throw new IllegalArgumentException("--input is required");
            }
            return new ReplayConfig(
                URI.create(options.getOrDefault("base-url", "http://localhost:8080")),
                Path.of(input),
                Path.of(options.getOrDefault(
                    "output",
                    "src/integrationTest/resources/Venn - Back-End - Replay Generated Output .txt"
                )),
                Path.of(options.getOrDefault("detailed-output", "build/replay/responses.jsonl")),
                options.containsKey("ground-truth") ? Path.of(options.get("ground-truth")) : null
            );
        }
    }

}
