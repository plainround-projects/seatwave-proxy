package io.plainround.seatwave.proxy.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The module's invariant against the running stack (scripts/stack.sh test): SeatWave trusts a
 * forwarded client address only from the proxy, and the proxy never caches a POST or a response
 * marked private, while it does cache a static file. Never part of ./gradlew check.
 */
@Tag("stack")
class StackInvariantTest {

  private static final String PROXY = "https://127.0.0.1:20900";
  private static final String SEATWAVE_DIRECT = "http://127.0.0.1:20911";
  private static final String METRICS = "http://127.0.0.1:20912/metrics";
  private static final String FORGED = "203.0.113.7";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int SEATS_PER_SECTION = 1_000;
  private static final int CONFLICT = 409;
  private static final int MAX_SEAT_ATTEMPTS = 50;
  // A random first seat, so two runs within one hold's 300 s rarely start on the same seat.
  private static final AtomicInteger NEXT_SEAT =
      new AtomicInteger(new SplittableRandom().nextInt(SEATS_PER_SECTION));
  private static HttpClient client;

  @BeforeAll
  static void connect() {
    client = TestBackend.client();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }

  @Test
  void ignoresAForgedHeaderSentStraightToSeatWave() throws Exception {
    HttpResponse<String> view = get(SEATWAVE_DIRECT + "/lab/client", "X-Forwarded-For", FORGED);

    assertThat(view.body()).doesNotContain(FORGED).contains("\"source\":\"peer\"");
  }

  @Test
  void replacesAForgedHeaderWithTheAddressTheProxySaw() throws Exception {
    HttpResponse<String> view = get(PROXY + "/lab/client", "X-Forwarded-For", FORGED);

    assertThat(view.body())
        .doesNotContain(FORGED)
        .contains("\"scheme\":\"https\"", "\"source\":\"forwarded\"");
  }

  @Test
  void neverAnswersAPostFromTheCache() throws Exception {
    long before = count("POST /v1/holds", "201");

    HttpResponse<String> first = hold("A");
    HttpResponse<String> second = hold("A");

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(201);
    assertThat(first.body()).isNotEqualTo(second.body());
    for (HttpResponse<String> answer : List.of(first, second)) {
      assertThat(answer.headers().firstValue("x-cache-status")).isNotEqualTo(Optional.of("HIT"));
    }
    assertThat(count("POST /v1/holds", "201")).isEqualTo(before + 2);
  }

  @Test
  void neverAnswersAPrivateResponseFromTheCache() throws Exception {
    String location = hold("B").headers().firstValue("location").orElseThrow();
    long before = count("GET /v1/holds/{holdId}", "200");

    HttpResponse<String> first = get(PROXY + location);
    HttpResponse<String> second = get(PROXY + location);

    for (HttpResponse<String> answer : List.of(first, second)) {
      assertThat(answer.headers().firstValue("x-cache-status")).isNotEqualTo(Optional.of("HIT"));
    }
    assertThat(first.body()).isEqualTo(second.body());
    assertThat(count("GET /v1/holds/{holdId}", "200")).isEqualTo(before + 2);
  }

  @Test
  void answersAStaticFileFromTheCacheOnceItHoldsIt() throws Exception {
    get(PROXY + "/static/seat-map.js");
    long before = count("GET /static/{file}", "200");

    HttpResponse<String> cached = get(PROXY + "/static/seat-map.js");

    assertThat(cached.headers().firstValue("x-cache-status")).hasValue("HIT");
    assertThat(count("GET /static/{file}", "200")).isEqualTo(before);
  }

  @Test
  void refusesABodyOverItsLimitWithProblemDetailsAtTheProxyOrAtSeatWave() throws Exception {
    HttpResponse<String> overProxyLimit = post("{\"x\":\"" + "a".repeat(5_000) + "\"}");
    HttpResponse<String> overSeatWaveLimit = post("{\"x\":\"" + "a".repeat(2_000) + "\"}");

    assertThat(overProxyLimit.statusCode()).isEqualTo(413);
    assertThat(overProxyLimit.body()).contains("The body is over the proxy limit");
    assertThat(overSeatWaveLimit.statusCode()).isEqualTo(413);
    assertThat(overSeatWaveLimit.body()).contains("The body is over 1,024 bytes");
  }

  private static HttpResponse<String> hold(String section)
      throws IOException, InterruptedException {
    // Holds last 300 s and the stack may still hold seats from an earlier run, so a taken seat
    // (409) moves on to the next number instead of failing the test; the seats of one run never
    // repeat, because the counter only grows.
    HttpResponse<String> answer = null;
    for (int attempt = 0; attempt < MAX_SEAT_ATTEMPTS; attempt++) {
      String seat = section + "-" + (1 + NEXT_SEAT.getAndIncrement() % SEATS_PER_SECTION);
      answer = post("{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"stack-test\"}");
      if (answer.statusCode() != CONFLICT) {
        return answer;
      }
    }
    return answer;
  }

  private static HttpResponse<String> post(String body) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(PROXY + "/v1/holds"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> get(String uri, String... headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).timeout(TIMEOUT).GET();
    if (headers.length > 0) {
      request.headers(headers);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static long count(String route, String status) throws IOException, InterruptedException {
    String metrics = get(METRICS).body();
    Matcher matcher =
        Pattern.compile(
                "seatwave_requests_total\\{route=\""
                    + Pattern.quote(route)
                    + "\",status=\""
                    + status
                    + "\"} (\\d+)")
            .matcher(metrics);
    return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
  }
}
