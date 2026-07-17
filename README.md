# Load Funds

This Spring Boot application evaluates customer load-funds attempts against
daily (and count) and weekly velocity limits. The main business logic is in
`LoadFundsServiceImpl` and can be invoked through either the HTTP API or the CLI
file runner.

## Requirements

- JDK 25
- The included Gradle wrapper; no local Gradle installation is required

## Start The Service

```bash
./gradlew bootRun
```

## Build And Test

Build the application and run the standard test suite:

```bash
./gradlew test
```

Run the API integration tests against a running service:

First start the service
```bash
./gradlew bootRun
```

In another terminal run the integration tests:

```bash
./gradlew integrationTest
```

The service listens on `http://localhost:8080`. Submit loads with
`POST /funds`; health and operational endpoints are under `/actuator`.
The HTTP response includes an `outcome` value: `ACCEPTED`, `DECLINED`,
`DUPLICATE_ACCEPTED`, or `DUPLICATE_DECLINED`.

## Replay A File Over HTTP

Start the service then use the following command to replay the supplied input file
and compare the responses with the ground truth:

```bash
./gradlew replayLoadFunds \
  -Ploadfunds.input='src/integrationTest/resources/Venn - Back-End - Input.txt' \
  -Ploadfunds.output='src/integrationTest/resources/Venn - Back-End - Replay Generated Output .txt' \
  -Ploadfunds.detailedOutput=build/replay/responses.jsonl \
  -Ploadfunds.ground-truth='src/integrationTest/resources/Venn - Back-End - Output .txt'
```

`loadfunds.output` contains `id`, `customer_id`, and `accepted` in the same
format as the supplied ground truth. `loadfunds.detailedOutput` keeps status,
response bodies, errors, sequence numbers, and request durations for every
attempt.

## Run The File Runner

The file runner invokes the same service logic directly without HTTP:

```bash
./gradlew runFile \
  -Pinput='src/integrationTest/resources/Venn - Back-End - Input.txt' \
  -Poutput='src/integrationTest/resources/Venn - Back-End - Generated Output.txt'
```

The expected summary is `written="999" duplicates="1" failed="0"`. Compare the
generated output with the supplied ground truth:

```bash
diff --strip-trailing-cr -u \
  'src/integrationTest/resources/Venn - Back-End - Output .txt' \
  'src/integrationTest/resources/Venn - Back-End - Generated Output.txt'
```

Application logs default to DEBUG and are written to stderr and
`logs/loadfunds.log`.

Omit `output` to write JSON lines to stdout, which can be redirected to a file.

```bash
./gradlew -q runFile \
  -Pinput='src/integrationTest/resources/Venn - Back-End - Input.txt' > /tmp/result
```

## Service Runtime Limits

The service uses blocking Spring MVC and database access. Its waits are bounded so lock or connection contention cannot occupy request threads indefinitely:

- Customer lock timeout: 3 seconds
- Transaction timeout: 5 seconds
- Hikari connection acquisition timeout: 3 seconds
- Hikari maximum pool size: 10 connections
- Tomcat worker pool: 10 minimum, 200 maximum

Use the production profile to disable request payload logging and verbose synchronous logging on request threads:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

# Bonus: Dashboard
There is dashboard at `/dashboard` that allows observing various metrics creating requests.
