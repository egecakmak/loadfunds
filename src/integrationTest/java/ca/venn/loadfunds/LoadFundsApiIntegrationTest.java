package ca.venn.loadfunds;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LoadFundsApiIntegrationTest {

    private static final Instant PROCESSED_AT = Instant.parse("2026-07-08T12:00:00Z");
    private static final long DAILY_AMOUNT_LIMIT_CENTS = 500_000L;
    private static final long WEEKLY_AMOUNT_LIMIT_CENTS = 2_000_000L;
    private static final int DAILY_LOAD_COUNT_LIMIT = 3;

    private final URI baseUri = URI.create(System.getProperty("loadfunds.base-url"));
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void reportsHealthy() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("status").asText()).isEqualTo("UP");
    }

    @Test
    void acceptsRequestAndReturnsStableResponseContract() throws Exception {
        String customerId = unique("accepted-customer");
        String loadId = unique("accepted-load");

        HttpResponse<String> response = post(request(loadId, customerId, "$15.00", PROCESSED_AT));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(
            value -> assertThat(value).startsWith("application/json")
        );
        JsonNode body = json(response);
        assertThat(body.get("id").asText()).isEqualTo(loadId);
        assertThat(body.get("customer_id").asText()).isEqualTo(customerId);
        assertThat(body.get("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(body.get("rejection_reason").isNull()).isTrue();
    }

    @Test
    void enforcesConfiguredDailyAmountLimit() throws Exception {
        String customerId = unique("daily-amount-limit-customer");
        assertAccepted(post(request(
            unique("load"),
            customerId,
            money(DAILY_AMOUNT_LIMIT_CENTS),
            PROCESSED_AT
        )));

        HttpResponse<String> response = post(request(
            unique("load"), customerId, "$0.01", PROCESSED_AT.plusSeconds(60)));

        assertDeclined(response, "DAILY_AMOUNT_EXCEEDED");
    }

    @Test
    void enforcesConfiguredWeeklyAmountLimit() throws Exception {
        String customerId = unique("weekly-amount-limit-customer");
        Instant monday = Instant.parse("2026-07-06T12:00:00Z");
        int daysToLimit = (int) (WEEKLY_AMOUNT_LIMIT_CENTS / DAILY_AMOUNT_LIMIT_CENTS);

        for (int day = 0; day < daysToLimit; day++) {
            assertAccepted(post(request(
                unique("load"),
                customerId,
                money(DAILY_AMOUNT_LIMIT_CENTS),
                monday.plusSeconds(day * 86_400L)
            )));
        }

        HttpResponse<String> response = post(request(
            unique("load"), customerId, "$0.01", monday.plusSeconds(daysToLimit * 86_400L)));

        assertDeclined(response, "WEEKLY_AMOUNT_EXCEEDED");
    }

    @Test
    void enforcesConfiguredDailyCountLimit() throws Exception {
        String customerId = unique("daily-count-limit-customer");

        for (int i = 0; i < DAILY_LOAD_COUNT_LIMIT; i++) {
            assertAccepted(post(request(
                unique("load"),
                customerId,
                "$1.00",
                PROCESSED_AT.plusSeconds(i * 60L)
            )));
        }

        HttpResponse<String> response = post(request(
            unique("load"),
            customerId,
            "$1.00",
            PROCESSED_AT.plusSeconds(DAILY_LOAD_COUNT_LIMIT * 60L)
        ));

        assertDeclined(response, "DAILY_COUNT_EXCEEDED");
    }

    @Test
    void returnsOriginalDecisionForDuplicate() throws Exception {
        String customerId = unique("duplicate-customer");
        String loadId = unique("duplicate-load");
        JsonNode first = json(post(request(loadId, customerId, "$15.00", PROCESSED_AT)));
        JsonNode duplicate = json(post(request(loadId, customerId, "$50.00", PROCESSED_AT.plusSeconds(60))));

        assertThat(first.get("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(duplicate.get("outcome").asText()).isEqualTo("DUPLICATE_ACCEPTED");
        assertThat(duplicate.get("rejection_reason").isNull()).isTrue();
    }

    @Test
    void rejectsGetFundsAsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = get("/funds");

        assertProblem(response, 405, "Only POST is supported");
    }

    @Test
    void rejectsInvalidRequestsWithoutLeakingInternalErrors() throws Exception {
        String customerId = unique("invalid-customer");
        HttpResponse<String> missingId = post(requestMissing("id"));
        HttpResponse<String> missingCustomer = post(requestMissing("customer_id"));
        HttpResponse<String> missingAmount = post(requestMissing("load_amount"));
        HttpResponse<String> missingTime = post(requestMissing("time"));
        HttpResponse<String> zero = post(request(unique("zero"), customerId, "$0.00", PROCESSED_AT));
        HttpResponse<String> malformedAmount = post(request(
            unique("bad-amount"), customerId, "1.00", PROCESSED_AT));
        HttpResponse<String> future = post(request(
            unique("future"), customerId, "$1.00", Instant.parse("2099-01-01T00:00:00Z")));
        HttpResponse<String> malformed = postRaw("{not-json");

        assertProblem(missingId, 400, "id");
        assertProblem(missingCustomer, 400, "customerId");
        assertProblem(missingAmount, 400, "loadAmount");
        assertProblem(missingTime, 400, "time");
        assertProblem(zero, 400, "loadAmount");
        assertProblem(malformedAmount, 400, "loadAmount");
        assertProblem(future, 400, "time");
        assertProblem(malformed, 400, "Malformed JSON request");
    }

    private JsonNode[] concurrently(Map<String, String> firstRequest, Map<String, String> secondRequest)
        throws Exception {
        HttpResponse<String>[] responses = concurrentlyResponses(firstRequest, secondRequest);
        return new JsonNode[] {json(responses[0]), json(responses[1])};
    }

    private HttpResponse<String>[] concurrentlyResponses(
        Map<String, String> firstRequest,
        Map<String, String> secondRequest
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> postWhenReleased(firstRequest, ready, start));
            var second = executor.submit(() -> postWhenReleased(secondRequest, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new HttpResponse[] {
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
            };
        }
    }

    private HttpResponse<String> postWhenReleased(
        Map<String, String> request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return post(request);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder(resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> post(Map<String, String> request) throws Exception {
        return postRaw(mapper.writeValueAsString(request));
    }

    private HttpResponse<String> postRaw(String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(resolve("/funds"))
                       .header("Content-Type", "application/json")
                       .POST(HttpRequest.BodyPublishers.ofString(body))
                       .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private static Map<String, String> request(
        String loadId,
        String customerId,
        String amount,
        Instant processedAt
    ) {
        return Map.of(
            "id", loadId,
            "customer_id", customerId,
            "load_amount", amount,
            "time", processedAt.toString()
        );
    }

    private static Map<String, String> requestMissing(String field) {
        Map<String, String> request = new LinkedHashMap<>(request(
            unique("validation-load"),
            unique("validation-customer"),
            "$1.00",
            PROCESSED_AT
        ));
        request.remove(field);
        return request;
    }

    private JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private void assertAccepted(HttpResponse<String> response) {
        JsonNode body = json(response);
        assertThat(body.get("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(body.get("rejection_reason").isNull()).isTrue();
    }

    private void assertDeclined(HttpResponse<String> response, String reason) {
        JsonNode body = json(response);
        assertThat(body.get("outcome").asText()).isEqualTo("DECLINED");
        assertThat(body.get("rejection_reason").asText()).isEqualTo(reason);
    }

    private void assertProblem(HttpResponse<String> response, int status, String detailFragment) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        if (!detailFragment.isEmpty()) {
            assertThat(body.get("detail").asText()).contains(detailFragment);
        }
    }

    private URI resolve(String path) {
        return baseUri.resolve(path);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String money(long cents) {
        return "$" + cents / 100 + "." + "%02d".formatted(cents % 100);
    }
}
