package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackendServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private TestBackend backend;

  @BeforeEach
  void start() {
    backend = TestBackend.untrusting();
  }

  @AfterEach
  void stop() {
    backend.close();
  }

  @Test
  void servesModuleOnesSeatMapByteForByteWithTheDeprecationHeaders() {
    HttpResponse<String> response = backend.get(backend.plain("/events/1/seats?section=A"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .hasSize(36_894)
        .startsWith("[{\"seat\":\"A-1\",\"state\":\"available\"}");
    assertThat(response.headers().firstValue("cache-control")).hasValue("no-cache");
    assertThat(response.headers().firstValue("deprecation")).hasValue("@1794355200");
    assertThat(response.headers().firstValue("sunset")).hasValue("Tue, 11 May 2027 00:00:00 GMT");
    assertThat(response.headers().firstValue("server-timing").orElseThrow()).startsWith("app;dur=");
  }

  @Test
  void refusesAVersionZeroSeatMapWithoutAValidSection() {
    HttpResponse<String> response = backend.get(backend.plain("/events/1/seats?section=Z"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("content-type")).hasValue("application/problem+json");
    assertThat(response.body())
        .contains("\"type\":\"https://seatwave.example/problems/invalid-request\"");
  }

  @Test
  void pagesVersionOneSeatsWithACursorUntilTheLastPage() {
    HttpResponse<String> first =
        backend.get(backend.plain("/v1/events/1/seats?section=B&limit=500"));
    String cursor = first.body().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
    HttpResponse<String> second =
        backend.get(backend.plain("/v1/events/1/seats?section=B&limit=500&cursor=" + cursor));

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.body())
        .startsWith("{\"items\":[{\"seat\":\"B-1\",\"section\":\"B\"")
        .contains("\"seat\":\"B-500\"");
    assertThat(second.body())
        .contains("\"seat\":\"B-501\"", "\"seat\":\"B-1000\"")
        .doesNotContain("nextCursor");
  }

  @Test
  void pagesAcrossSectionsWhenNoSectionIsGiven() {
    HttpResponse<String> first = backend.get(backend.plain("/v1/events/1/seats?limit=3"));
    String cursor = "czEuQS0xMDAw"; // s1.A-1000: the last seat of section A
    HttpResponse<String> next =
        backend.get(backend.plain("/v1/events/1/seats?limit=2&cursor=" + cursor));
    HttpResponse<String> last =
        backend.get(backend.plain("/v1/events/1/seats?limit=2&cursor=czEuVC0xMDAw"));

    // Each label with its closing quote, so "A-1" cannot be matched by "A-10".
    assertThat(first.body())
        .contains("\"seat\":\"A-1\"", "\"seat\":\"A-2\"", "\"seat\":\"A-3\"", "nextCursor")
        .doesNotContain("\"seat\":\"A-4\"");
    assertThat(first.body().split("\"seat\":", -1)).hasSize(4);
    assertThat(next.body()).contains("\"seat\":\"B-1\"", "\"seat\":\"B-2\"");
    assertThat(last.body()).isEqualTo("{\"items\":[]}");
  }

  @Test
  void reportsEveryProblemOfASeatPageRequestInOneReply() {
    HttpResponse<String> response =
        backend.get(backend.plain("/v1/events/1/seats?section=Z&limit=0&cursor=bm9wZQ"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body())
        .contains("section must be one letter", "limit must be from 1 to 500", "cursor is not one");
  }

  @Test
  void refusesUnknownOrRepeatedSeatPageParametersAndAnUnknownEvent() {
    assertThat(backend.get(backend.plain("/v1/events/1/seats?state=held")).body())
        .contains("unknown parameter state");
    assertThat(backend.get(backend.plain("/v1/events/1/seats?limit=1&limit=2")).body())
        .contains("must appear once");
    assertThat(backend.get(backend.plain("/v1/events/2/seats")).statusCode()).isEqualTo(404);
  }

  @Test
  void holdsASeatOnceAndReplaysTheSameReplyForTheSameKeyAndBody() {
    HttpResponse<String> first = hold("\"key-1\"", "A-7", "fan-1");
    HttpResponse<String> replay = hold("\"key-1\"", "A-7", "fan-1");

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(first.headers().firstValue("location").orElseThrow()).startsWith("/v1/holds/");
    assertThat(first.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
  }

  @Test
  void refusesTheSameKeyWithAnotherBody() {
    hold("\"key-2\"", "A-8", "fan-1");

    HttpResponse<String> reused = hold("\"key-2\"", "A-9", "fan-1");

    assertThat(reused.statusCode()).isEqualTo(422);
    assertThat(reused.body()).contains("idempotency-key-reused");
  }

  @Test
  void refusesASeatThatAnotherCustomerHolds() {
    hold("\"key-3\"", "C-1", "fan-1");

    HttpResponse<String> second = hold("\"key-4\"", "C-1", "fan-2");

    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(second.body()).contains("seat-unavailable");
    assertThat(backend.get(backend.plain("/events/1/seats?section=C")).body())
        .contains("{\"seat\":\"C-1\",\"state\":\"held\"}");
  }

  @Test
  void refusesAHoldWithoutAKeyWithTheWrongTypeOrOverTheSizeLimit() {
    HttpRequest noKey = post("/v1/holds", "application/json", body("A-1", "fan-1")).build();
    HttpRequest wrongType =
        post("/v1/holds", "text/plain", body("A-1", "fan-1"))
            .header("Idempotency-Key", "\"k\"")
            .build();
    HttpRequest tooLarge =
        post("/v1/holds", "application/json", "{\"x\":\"" + "a".repeat(1_100) + "\"}")
            .header("Idempotency-Key", "\"k\"")
            .build();

    assertThat(backend.send(noKey).statusCode()).isEqualTo(400);
    assertThat(backend.send(wrongType).statusCode()).isEqualTo(415);
    assertThat(backend.send(tooLarge).statusCode()).isEqualTo(413);
  }

  @Test
  void refusesAHoldWhoseFieldsBreakTheContract() {
    HttpResponse<String> badFields =
        backend.send(
            post(
                    "/v1/holds",
                    "application/json",
                    "{\"eventId\":0,\"seat\":\"Z-1\",\"customer\":\"a b\"}")
                .header("Idempotency-Key", "\"key-5\"")
                .build());
    HttpResponse<String> unknownField =
        backend.send(
            post(
                    "/v1/holds",
                    "application/json",
                    "{\"eventId\":1,\"seat\":\"A-1\",\"customer\":\"f\",\"x\":1}")
                .header("Idempotency-Key", "\"key-6\"")
                .build());
    HttpResponse<String> otherEvent =
        backend.send(
            post(
                    "/v1/holds",
                    "application/json",
                    "{\"eventId\":2,\"seat\":\"A-1\",\"customer\":\"f\"}")
                .header("Idempotency-Key", "\"key-7\"")
                .build());

    assertThat(badFields.statusCode()).isEqualTo(400);
    assertThat(badFields.body()).contains("/eventId", "/seat", "/customer");
    assertThat(unknownField.body()).contains("/x is not a field of this request");
    assertThat(otherEvent.statusCode()).isEqualTo(404);
  }

  @Test
  void marksAHoldPrivateSoNoSharedCacheMayKeepIt() {
    String location =
        hold("\"key-8\"", "D-4", "fan-1").headers().firstValue("location").orElseThrow();

    HttpResponse<String> read = backend.get(backend.plain(location));

    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(read.headers().firstValue("cache-control")).hasValue("private, max-age=60");
    assertThat(read.body()).contains("\"seat\":\"D-4\"", "\"state\":\"held\"");
    assertThat(backend.get(backend.plain("/v1/holds/" + UUID.randomUUID())).statusCode())
        .isEqualTo(404);
    assertThat(backend.get(backend.plain(location + "?x=1")).statusCode()).isEqualTo(400);
  }

  @Test
  void answersOtherMethodsWithMethodNotAllowedAndTheAllowHeader() {
    HttpResponse<String> putHold =
        backend.send(
            HttpRequest.newBuilder(backend.plain("/v1/holds"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build());
    HttpResponse<String> postEvents =
        backend.send(post("/v1/events", "application/json", "{}").build());

    assertThat(putHold.statusCode()).isEqualTo(405);
    assertThat(putHold.headers().firstValue("allow")).hasValue("POST");
    assertThat(postEvents.statusCode()).isEqualTo(405);
    assertThat(postEvents.headers().firstValue("allow")).hasValue("GET, HEAD");
  }

  @Test
  void answersAnUnknownPathWithNotFoundWhateverTheMethod() {
    HttpResponse<String> postNothing =
        backend.send(post("/nothing", "application/json", "{}").build());

    assertThat(postNothing.statusCode()).isEqualTo(404);
    assertThat(postNothing.headers().firstValue("allow")).isEmpty();
  }

  @Test
  void answersHeadWithTheHeadersOfGetAndNoBody() {
    HttpResponse<String> head =
        backend.send(
            HttpRequest.newBuilder(backend.plain("/events/1/seats?section=A"))
                .timeout(TIMEOUT)
                .HEAD()
                .build());

    assertThat(head.statusCode()).isEqualTo(200);
    assertThat(head.body()).isEmpty();
    assertThat(head.headers().firstValue("content-length")).hasValue("36894");
    assertThat(head.headers().firstValue("cache-control")).hasValue("no-cache");
  }

  @Test
  void acceptsOnlyTheJsonMediaTypeWithOrWithoutParameters() {
    HttpResponse<String> lookalike =
        backend.send(
            post("/v1/holds", "application/jsonx", body("E-1", "fan-1"))
                .header("Idempotency-Key", "\"key-9\"")
                .build());
    HttpResponse<String> withCharset =
        backend.send(
            post("/v1/holds", "Application/JSON; charset=utf-8", body("E-2", "fan-1"))
                .header("Idempotency-Key", "\"key-10\"")
                .build());

    assertThat(lookalike.statusCode()).isEqualTo(415);
    assertThat(withCharset.statusCode()).isEqualTo(201);
  }

  @Test
  void servesStaticFilesWithAFixedPublicLifetime() {
    HttpResponse<String> script = backend.get(backend.plain("/static/seat-map.js"));
    HttpResponse<byte[]> poster = bytes("/static/poster-arena.bin");

    assertThat(script.headers().firstValue("cache-control")).hasValue("public, max-age=300");
    assertThat(script.headers().firstValue("content-type")).hasValue("text/javascript");
    assertThat(script.body()).contains("loadSection");
    assertThat(poster.body()).hasSize(300_000);
    assertThat(backend.get(backend.plain("/static/missing.js")).statusCode()).isEqualTo(404);
    assertThat(backend.get(backend.plain("/static/seat-map.js?v=2")).statusCode()).isEqualTo(400);
  }

  @Test
  void listsTheOneEventAndAnswersHealthAndUnknownPaths() {
    assertThat(backend.get(backend.plain("/v1/events")).body())
        .isEqualTo(
            "{\"items\":[{\"eventId\":1,\"name\":\"Arena Night\",\"startsAt\":\"2026-12-08T20:20:51.783Z\"}]}");
    assertThat(backend.get(backend.plain("/health")).body()).isEqualTo("{\"status\":\"up\"}");
    assertThat(backend.get(backend.plain("/health?x")).statusCode()).isEqualTo(400);
    assertThat(backend.get(backend.plain("/nothing")).statusCode()).isEqualTo(404);
  }

  @Test
  void holdsALongPollForItsWholeTimeAndRefusesAnUnreadablePosition() {
    long startNanos = System.nanoTime();
    HttpResponse<String> poll = backend.get(backend.plain("/v1/events/1/seat-changes?after=1:42"));
    Duration waited = Duration.ofNanos(System.nanoTime() - startNanos);

    assertThat(poll.statusCode()).isEqualTo(200);
    assertThat(poll.body()).isEqualTo("{\"items\":[],\"position\":\"1:42\"}");
    assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    assertThat(backend.get(backend.plain("/v1/events/1/seat-changes?after=%20")).statusCode())
        .isEqualTo(400);
    assertThat(backend.get(backend.plain("/v1/events/9/seat-changes?after=1")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void streamsTheReconnectDelayAndHeartbeatsThenEnds() {
    HttpResponse<String> stream = backend.get(backend.plain("/v1/events/1/seat-changes/stream"));

    assertThat(stream.headers().firstValue("content-type")).hasValue("text/event-stream");
    assertThat(stream.body()).startsWith("retry: 1000\n\n: heartbeat\n\n");
    assertThat(backend.get(backend.plain("/v1/events/1/seat-changes/stream?x=1")).statusCode())
        .isEqualTo(400);
    assertThat(backend.get(backend.plain("/v1/events/3/seat-changes/stream")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void answersOverTlsVersionOnePointThreeWithTheTestCertificate() {
    HttpResponse<String> response = backend.get(backend.tls("/health"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.sslSession().orElseThrow().getProtocol()).isEqualTo("TLSv1.3");
  }

  @Test
  void countsRequestsByRouteAndStatusOnTheOperationsListener() {
    backend.get(backend.plain("/health"));
    backend.get(backend.plain("/nothing"));

    // A request is counted when its worker finishes, which can be just after the client has read
    // the whole answer, so the test waits for the count instead of reading it once.
    await()
        .atMost(TIMEOUT)
        .until(() -> {
          String text = backend.metrics();
          return text.contains("route=\"other\",status=\"404\"} 1")
              && text.contains("route=\"GET /health\",status=\"200\"} 1")
              && text.contains("seatwave_workers_busy 0");
        });
    String metrics = backend.metrics();

    assertThat(metrics)
        .contains("seatwave_requests_total{route=\"GET /health\",status=\"200\"} 1")
        .contains("seatwave_requests_total{route=\"other\",status=\"404\"} 1")
        .contains("seatwave_workers 8")
        .contains("seatwave_workers_busy 0");
    assertThat(backend.get(backend.admin("/health")).body()).isEqualTo("{\"status\":\"up\"}");
    assertThat(backend.get(backend.admin("/other")).statusCode()).isEqualTo(404);
  }

  @Test
  void refusesARequestWhenEveryWorkerIsBusyAndTheQueueIsFull() {
    try (TestBackend tiny =
        TestBackend.start(
            List.of("192.0.2.1"),
            new BackendSettings.Timing(
                Duration.ofSeconds(3), Duration.ofMillis(100), Duration.ofMillis(350)),
            1,
            1)) {
      CompletableFuture<HttpResponse<String>> holding =
          CompletableFuture.supplyAsync(
              () -> tiny.get(tiny.plain("/v1/events/1/seat-changes?after=1")));
      await().atMost(TIMEOUT).until(() -> tiny.metrics().contains("seatwave_workers_busy 1"));
      // The single worker is held for 3 s: one more request waits in the queue, the next is
      // refused.
      CompletableFuture<Void> more =
          CompletableFuture.allOf(
              CompletableFuture.runAsync(() -> tryGet(tiny, "/health")),
              CompletableFuture.runAsync(() -> tryGet(tiny, "/health")),
              CompletableFuture.runAsync(() -> tryGet(tiny, "/health")));

      await()
          .atMost(TIMEOUT)
          .until(() -> !tiny.metrics().contains("seatwave_rejected_queue_full_total 0"));

      assertThat(holding.join().statusCode()).isEqualTo(200);
      more.join();
      // A refusal closes one connection; the server itself keeps serving.
      assertThat(tiny.get(tiny.plain("/health")).statusCode()).isEqualTo(200);
    }
  }

  private static void tryGet(TestBackend tiny, String path) {
    try {
      tiny.get(tiny.plain(path));
    } catch (java.io.UncheckedIOException refused) {
      // The refused request's connection is closed by the server: that is the behavior under test.
      assertThat(refused).hasCauseInstanceOf(java.io.IOException.class);
    }
  }

  private HttpResponse<String> hold(String key, String seat, String customer) {
    return backend.send(
        post("/v1/holds", "application/json", body(seat, customer))
            .header("Idempotency-Key", key)
            .build());
  }

  private HttpRequest.Builder post(String path, String type, String json) {
    return HttpRequest.newBuilder(backend.plain(path))
        .timeout(TIMEOUT)
        .header("Content-Type", type)
        .POST(HttpRequest.BodyPublishers.ofString(json));
  }

  private static String body(String seat, String customer) {
    return "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"" + customer + "\"}";
  }

  private HttpResponse<byte[]> bytes(String path) {
    try {
      return TestBackend.client()
          .send(
              HttpRequest.newBuilder(backend.plain(path)).build(),
              HttpResponse.BodyHandlers.ofByteArray());
    } catch (java.io.IOException failed) {
      throw new java.io.UncheckedIOException(failed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
