# The `backend` tests, line by line

## What it is

This file reads the eight test files of the `backend` package, one test at a time:

- `TestBackend.java`: a helper that starts a real SeatWave on this machine for one test, and a client to talk to it.
- `BackendServerTest.java`: 20 tests that talk to a running backend over HTTP.
- `ForwardedInvariantTest.java`: the module's invariant, end to end.
- `HoldKeysTest.java`, `HoldLedgerTest.java`, `SeatCatalogTest.java`: unit tests of single classes.
- `BackendSettingsTest.java`, `BackendCliTest.java`: the settings and the command-line entry point.

A **test** is a small program that calls the code and checks the result. A test that fails stops the build. The tests of this package carry the weight of the module: they are the proof that the backend behaves as the proxy labs assume. The libraries used (see `junit.md`, `assertj.md`, `awaitility.md`):

- **JUnit** finds and runs the methods marked `@Test`, and runs the methods marked `@BeforeEach` and `@AfterEach` around each one.
- **AssertJ** gives `assertThat(actual)...` checks that read like English and print a clear message on failure.
- **Awaitility** waits for a condition to become true (`await().atMost(...).until(...)`) instead of sleeping for a guessed time.

## Why this repository uses it

The README states 119 tests and a line-coverage floor of 80 percent. These tests start the real server on ports the system picks, so they run without Docker. The tests that need the real proxy are in a separate task. Because SeatWave is the thing the whole module measures, every route, error shape and cache header is pinned down here.

## Where it appears

| File | Tests | What it covers |
|---|---|---|
| `backend/TestBackend.java` | none (helper) | Starts and closes a backend; builds URIs and a client. |
| `backend/BackendServerTest.java` | 20 | Routes, errors, headers, paging, holds, static files, long poll, stream, TLS, metrics, queue limit. |
| `backend/ForwardedInvariantTest.java` | 4 | Forged and trusted forwarding headers over HTTP, and the request id. |
| `backend/HoldKeysTest.java` | 3 | Claim, replay, reuse, full table. |
| `backend/HoldLedgerTest.java` | 2 | One winner among 100 racers; expiry. |
| `backend/SeatCatalogTest.java` | 4 | Seat labels, cursors, last page, version 0 text. |
| `backend/BackendSettingsTest.java` | 3 | The real container settings; all problems together; direct construction. |
| `backend/BackendCliTest.java` | 4 | Usage text, invalid file, missing TLS file, a valid start. |

## How it works underneath

**How a test runs.** `./gradlew test` compiles the tests, runs them in a fresh Java process, and writes a report. The task depends on `testCertificates`, so the TEST certificates exist in `build/tls`, and it sets the system property `seatwave.tls.directory` to that folder. The working directory of the tests is the project folder, which is why some tests open `config/backend-local.properties` with a relative path. Tests tagged `stack` are left out; they need Docker.

**A test in three parts.** Arrange (set up), act (do the thing), assert (check). Most tests here are 3 to 15 lines in that shape.

**A real server per test.** `TestBackend.start` builds settings with ports `0` (any free port), a loopback address and the TEST key store, starts the server, and returns a handle with the real ports. Each test gets a fresh server, so no state leaks between tests. The server is closed after the test.

**Why timing is controlled.** The slow routes are the long poll (25 seconds in production) and the event stream. The tests use `quickTiming()`: a 200 ms long poll, a 100 ms heartbeat and a 350 ms stream lifetime.

## Walkthrough of this repository's use

### `backend/TestBackend.java`

```java
package io.plainround.seatwave.proxy.backend;

import io.plainround.seatwave.proxy.forwarded.TrustedProxies;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;
```

The imports show what the helper uses: the JDK's `HttpClient` to send requests, `URI` for addresses, `SSLContext` for TLS.

```java
/**
 * A SeatWave backend on 127.0.0.1 with ports the system picks, for one test, and a client for it.
 * The TEST certificates come from the build's testCertificates task.
 */
public final class TestBackend implements AutoCloseable {

  /** The folder the build writes the TEST certificates to. */
  public static final Path TLS = Path.of(System.getProperty("seatwave.tls.directory"));

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final BackendServer server;
  private final HttpClient client;

  private TestBackend(BackendServer server, HttpClient client) {
    this.server = server;
    this.client = client;
  }
```

`TestBackend implements AutoCloseable`, so tests use it in a try-with-resources block (or close it in `@AfterEach`). `TLS` is the folder of the TEST certificates, read from the system property that Gradle sets; `System.getProperty` returns `null` if it is not set, and `Path.of(null)` would fail with a `NullPointerException`, so running these tests outside Gradle needs `-Dseatwave.tls.directory=...`. `TIMEOUT` is ten seconds. The two fields are the server under test and the client.

```java
  /**
   * Starts a backend.
   *
   * @param trusted the trusted proxy addresses
   * @param timing the slow routes' durations
   * @param workers worker threads
   * @param queue queue capacity
   * @return the running backend
   */
  public static TestBackend start(
      List<String> trusted, BackendSettings.Timing timing, int workers, int queue) {
    try {
      BackendSettings settings =
          new BackendSettings(
              InetAddress.getLoopbackAddress(),
              0,
              0,
              0,
              TLS.resolve("seatwave-server.p12"),
              TLS.resolve("store.password"),
              128,
              workers,
              queue,
              TrustedProxies.parse(trusted).orOnErrorThrow(IllegalArgumentException::new),
              timing);
      BackendServer server =
          BackendServer.start(
              settings,
              TlsFiles.serverContext(settings.tlsKeystore(), settings.tlsPasswordFile()),
              Clock.systemUTC());
      return new TestBackend(server, client());
    } catch (IOException cannotStart) {
      throw new UncheckedIOException(cannotStart);
    }
  }
```

`start(trusted, timing, workers, queue)` builds a `BackendSettings` directly (the 12-argument constructor), with: the loopback address, three ports of 0, the TEST key store and password file from `TLS`, a backlog of 128, the worker and queue sizes from the arguments, and the trusted proxies parsed from the text list (`orOnErrorThrow(IllegalArgumentException::new)` turns a `Result` error into an exception, which is right in a test). It starts the server with `TlsFiles.serverContext(...)` and the real clock. An `IOException` is wrapped in `UncheckedIOException` so test code does not need `throws` clauses.

```java

  /**
   * Starts a backend that trusts no peer of this machine, with quick slow routes.
   *
   * @return the running backend
   */
  public static TestBackend untrusting() {
    return start(List.of("192.0.2.1"), quickTiming(), 8, 64);
  }

  /**
   * Returns slow routes that finish quickly: a 200 ms long poll and a 100 ms heartbeat.
   *
   * @return the timing
   */
  public static BackendSettings.Timing quickTiming() {
    return new BackendSettings.Timing(
        Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofMillis(350));
  }
```

`untrusting()` is the common case: the only trusted address is `192.0.2.1` (a documentation address no test uses), so the machine's own `127.0.0.1` is **not** trusted. It uses 8 workers and a queue of 64. `quickTiming()` returns the short durations.

```java

  /**
   * Returns a client that trusts the TEST certificate authority only.
   *
   * @return the client
   */
  public static HttpClient client() {
    try {
      SSLContext tls = TlsFiles.clientContext(TLS.resolve("seatwave-test-ca.pem"));
      return HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .sslContext(tls)
          .connectTimeout(TIMEOUT)
          .build();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
```

`client()` builds a JDK `HttpClient` that speaks HTTP/1.1, trusts only the TEST CA (through `TlsFiles.clientContext`) and gives up connecting after ten seconds.

```java

  /**
   * Returns a plain HTTP address on the backend.
   *
   * @param pathAndQuery for example {@code /health}
   * @return the address
   */
  public URI plain(String pathAndQuery) {
    return URI.create("http://127.0.0.1:" + server.plainPort() + pathAndQuery);
  }

  /**
   * Returns an HTTPS address on the backend.
   *
   * @param pathAndQuery for example {@code /health}
   * @return the address
   */
  public URI tls(String pathAndQuery) {
    return URI.create("https://127.0.0.1:" + server.tlsPort() + pathAndQuery);
  }

  /**
   * Returns an address on the operations listener.
   *
   * @param path for example {@code /metrics}
   * @return the address
   */
  public URI admin(String path) {
    return URI.create("http://127.0.0.1:" + server.adminPort() + path);
  }
```

Four small methods build addresses for the three listeners: `plain(path)` for the plain HTTP port, `tls(path)` for HTTPS, `admin(path)` for the operations port. All use `127.0.0.1` and the port the server really got.

```java

  /**
   * Sends one request and reads the body as text.
   *
   * @param request the request
   * @return the response
   */
  public HttpResponse<String> send(HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", interrupted);
    }
  }

  /**
   * Sends a GET.
   *
   * @param uri the address
   * @return the response
   */
  public HttpResponse<String> get(URI uri) {
    return send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build());
  }
```

`send` sends a request and reads the body as text. `client.send(...)` can throw `IOException` (wrapped as `UncheckedIOException`) or `InterruptedException` (the flag is restored and an `IllegalStateException` thrown). `get` builds a GET with a ten-second timeout.

```java

  /**
   * Returns SeatWave's metrics text.
   *
   * @return the text
   */
  public String metrics() {
    return get(admin("/metrics")).body();
  }

  /**
   * Returns the server itself.
   *
   * @return the server
   */
  public BackendServer server() {
    return server;
  }

  @Override
  public void close() {
    client.close();
    server.close();
  }
}
```

`metrics()` reads the text of `/metrics`. `server()` returns the server. `close()` closes the client and then the server.

### `backend/BackendServerTest.java`

```java
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
```

The imports bring in AssertJ's `assertThat` and Awaitility's `await` as **static imports**, so they can be called without the class name. `@BeforeEach` runs before every test and starts an untrusting backend; `@AfterEach` closes it. A test method is a method marked `@Test`; its name is a sentence that says what it checks.

```java
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
```

**Version 0 seat map.** `GET /events/1/seats?section=A` must return status 200 and a body of exactly 36,894 characters that starts with the first seat in Module 1's shape. The headers must carry `Cache-Control: no-cache`, `Deprecation: @1794355200` (11 November 2026, 00:00 UTC, as seconds), `Sunset: Tue, 11 May 2027 00:00:00 GMT`, and a `Server-Timing` header that starts with `app;dur=`. `firstValue(name)` returns an `Optional<String>`; `hasValue(...)` checks it holds that value. The test compares the length in characters; the seat map is plain ASCII, so characters and bytes are equal.

```java
  @Test
  void refusesAVersionZeroSeatMapWithoutAValidSection() {
    HttpResponse<String> response = backend.get(backend.plain("/events/1/seats?section=Z"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("content-type")).hasValue("application/problem+json");
    assertThat(response.body())
        .contains("\"type\":\"https://seatwave.example/problems/invalid-request\"");
  }
```

**Bad section.** A section of `Z` gives 400, the media type `application/problem+json`, and a body that has the type URL `.../problems/invalid-request`.

```java
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
```

**Paging.** The first request for section B asks for 500 seats; the test pulls the `nextCursor` out of the body with a regular expression (`replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1")` keeps only what group 1 captured). The second request sends it. The first page starts with `B-1` and contains `B-500`; the second contains `B-501` and `B-1000` and has no `nextCursor`. This proves the cursor continues exactly where the page ended and that the last page is marked by the field's absence.

```java
  @Test
  void pagesAcrossSectionsWhenNoSectionIsGiven() {
    HttpResponse<String> first = backend.get(backend.plain("/v1/events/1/seats?limit=3"));
    String cursor = "czEuQS0xMDAw"; // s1.A-1000: the last seat of section A
    HttpResponse<String> next =
        backend.get(backend.plain("/v1/events/1/seats?limit=2&cursor=" + cursor));
    HttpResponse<String> last =
        backend.get(backend.plain("/v1/events/1/seats?limit=2&cursor=czEuVC0xMDAw"));

    assertThat(first.body()).contains("A-1", "A-2", "A-3", "nextCursor");
    assertThat(next.body()).contains("\"seat\":\"B-1\"", "\"seat\":\"B-2\"");
    assertThat(last.body()).isEqualTo("{\"items\":[]}");
  }
```

**Paging across sections.** With no `section`, a limit of 3 returns `A-1`, `A-2`, `A-3` and a cursor (the checks use `contains`, which also matches `A-10`, so they are looser than they look). The hard-coded cursor `czEuQS0xMDAw` is Base64 for `s1.A-1000`, the last seat of section A, so the next page must start at `B-1` and `B-2`. The cursor `czEuVC0xMDAw` is `s1.T-1000`, the very last seat; the page after it is exactly `{"items":[]}`: no items and no cursor.

```java
  @Test
  void reportsEveryProblemOfASeatPageRequestInOneReply() {
    HttpResponse<String> response =
        backend.get(backend.plain("/v1/events/1/seats?section=Z&limit=0&cursor=bm9wZQ"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body())
        .contains("section must be one letter", "limit must be from 1 to 500", "cursor is not one");
  }
```

**Every problem in one reply.** A request with a bad section, `limit=0` and a made-up cursor (`bm9wZQ` is Base64 for `nope`) gives one 400 whose body mentions all three problems.

```java
  @Test
  void refusesUnknownOrRepeatedSeatPageParametersAndAnUnknownEvent() {
    assertThat(backend.get(backend.plain("/v1/events/1/seats?state=held")).body())
        .contains("unknown parameter state");
    assertThat(backend.get(backend.plain("/v1/events/1/seats?limit=1&limit=2")).body())
        .contains("must appear once");
    assertThat(backend.get(backend.plain("/v1/events/2/seats")).statusCode()).isEqualTo(404);
  }
```

**Strict parameters.** An unknown parameter (`state`), a repeated parameter (`limit` twice) and an unknown event (2) are refused with the right words and status (400, 400, 404).

```java
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
```

**Idempotent hold.** The same key and body twice: both 201, with a `Location` header that starts with `/v1/holds/`, `Cache-Control: no-store`, and a second body that equals the first. The helper `hold(key, seat, customer)` is at the end of the file. Keys are written with the quotes, as the contract wants (`"\"key-1\""`).

```java
  @Test
  void refusesTheSameKeyWithAnotherBody() {
    hold("\"key-2\"", "A-8", "fan-1");

    HttpResponse<String> reused = hold("\"key-2\"", "A-9", "fan-1");

    assertThat(reused.statusCode()).isEqualTo(422);
    assertThat(reused.body()).contains("idempotency-key-reused");
  }
```

**Key reused.** The same key with a different seat gives 422 and the problem slug `idempotency-key-reused`.

```java
  @Test
  void refusesASeatThatAnotherCustomerHolds() {
    hold("\"key-3\"", "C-1", "fan-1");

    HttpResponse<String> second = hold("\"key-4\"", "C-1", "fan-2");

    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(second.body()).contains("seat-unavailable");
    assertThat(backend.get(backend.plain("/events/1/seats?section=C")).body())
        .contains("{\"seat\":\"C-1\",\"state\":\"held\"}");
  }
```

**Seat taken.** A second customer, with a new key, asking for the same seat gets 409 `seat-unavailable`. A version 0 seat map for section C then shows `{"seat":"C-1","state":"held"}`: the hold is visible to the other API.

```java
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
```

**Refused before any work.** No key gives 400, the wrong media type `text/plain` gives 415, and a body of 1,100 characters gives 413. Note the third request has a valid key and type, so the size is what is refused.

```java
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
```

**Fields that break the contract.** One body with a bad event, a bad seat and a bad customer gives 400 and names `/eventId`, `/seat` and `/customer`. A body with an extra field `x` gives `/x is not a field of this request` (the JSON codec's message). A well-formed body for event 2 gives 404. Each uses its own key (`key-5`, `key-6`, `key-7`) so one test case does not replay another.

```java
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
```

**A hold is private.** After a hold, `GET` of its `Location` gives 200 with `Cache-Control: private, max-age=60` and the seat and state in the body. A random UUID gives 404, and a query on the hold path gives 400.

```java
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
    assertThat(postEvents.headers().firstValue("allow")).hasValue("GET");
  }
```

**Other methods.** A `PUT` to `/v1/holds` gives 405 with `Allow: POST`; a `POST` to `/v1/events` gives 405 with `Allow: GET`.

```java
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
```

**Static files.** The script has `Cache-Control: public, max-age=300`, the type `text/javascript` and the text `loadSection`. The poster is exactly 300,000 bytes. A missing file is 404 and a query on a static file (`?v=2`) is 400. The helper `bytes(path)` reads the body as raw bytes.

```java
  @Test
  void listsTheOneEventAndAnswersHealthAndUnknownPaths() {
    assertThat(backend.get(backend.plain("/v1/events")).body())
        .isEqualTo(
            "{\"items\":[{\"eventId\":1,\"name\":\"Arena Night\",\"startsAt\":\"2026-12-08T20:20:51.783Z\"}]}");
    assertThat(backend.get(backend.plain("/health")).body()).isEqualTo("{\"status\":\"up\"}");
    assertThat(backend.get(backend.plain("/health?x")).statusCode()).isEqualTo(400);
    assertThat(backend.get(backend.plain("/nothing")).statusCode()).isEqualTo(404);
  }
```

**The event list, health and unknown paths.** The event list must equal one exact JSON text, including the start time `2026-12-08T20:20:51.783Z`. `/health` returns `{"status":"up"}`; `/health?x` is 400; `/nothing` is 404.

```java
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
```

**The long poll.** The test measures the time of `GET .../seat-changes?after=1:42` with `System.nanoTime()` and checks the status, the exact body and that it waited at least 200 ms (the quick timing). A blank position (`after=%20`) is 400 and an unknown event is 404. The check is a lower bound only: it proves the server waits, not that it waits no more than needed.

```java
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
```

**The stream.** The body must start with `retry: 1000`, a blank line, and a heartbeat comment. With a heartbeat of 100 ms and a lifetime of 350 ms the stream ends by itself after about 300 ms, so `get` returns with the whole body. A query on the stream path is 400 and event 3 is 404.

```java
  @Test
  void answersOverTlsVersionOnePointThreeWithTheTestCertificate() {
    HttpResponse<String> response = backend.get(backend.tls("/health"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.sslSession().orElseThrow().getProtocol()).isEqualTo("TLSv1.3");
  }
```

**TLS 1.3.** `GET https://.../health` works with the TEST CA and the negotiated protocol (`sslSession().getProtocol()`) is `TLSv1.3`.

```java
  @Test
  void countsRequestsByRouteAndStatusOnTheOperationsListener() {
    backend.get(backend.plain("/health"));
    backend.get(backend.plain("/nothing"));

    String metrics = backend.metrics();

    assertThat(metrics)
        .contains("seatwave_requests_total{route=\"GET /health\",status=\"200\"} 1")
        .contains("seatwave_requests_total{route=\"other\",status=\"404\"} 1")
        .contains("seatwave_workers 8")
        .contains("seatwave_workers_busy 0");
    assertThat(backend.get(backend.admin("/health")).body()).isEqualTo("{\"status\":\"up\"}");
    assertThat(backend.get(backend.admin("/other")).statusCode()).isEqualTo(404);
  }
```

**Metrics.** After one `/health` and one `/nothing`, the metrics text on the admin port must contain a counter line for each (`route="GET /health",status="200"} 1` and `route="other",status="404"} 1`), `seatwave_workers 8` and `seatwave_workers_busy 0`. The admin `/health` works and the admin `/other` is 404. One weakness: the server counts a request in a `finally` block after the response has been sent, so a very fast client could read `/metrics` before the previous request was counted. The window is microseconds; the README reports 40 full runs without a failure.

```java
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
```

**The queue limit.** The most involved test. A backend is made with 1 worker and a queue of 1, and a 3-second long poll. The test (1) starts one long poll in another thread (`CompletableFuture.supplyAsync`, which runs a function on a background thread and returns a handle to its result); (2) waits, with Awaitility, until the metrics show `seatwave_workers_busy 1`, so it knows the only worker is busy; (3) starts three `/health` requests at once: one waits in the queue, the others are refused; (4) waits until the metrics no longer say `seatwave_rejected_queue_full_total 0`; (5) checks the long poll finished with 200; (6) checks the server still answers. `tryGet` accepts the failed requests: a refusal closes the connection, and the client reports an `IOException`, which the helper expects (`hasCauseInstanceOf`). The pieces are timing-aware but not timing-dependent: the long poll lasts 3 seconds, the waits time out at 10 seconds, and the test waits for the metric, not for a clock.

```java
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
```

Helpers. `hold` posts a body with a key. `post` starts a POST builder with a type and a JSON string. `body` writes the hold body text. `bytes` reads a response as raw bytes with a fresh client. The `java.io` names are written in full (`java.io.IOException`) instead of imported; that is a style choice.

### `backend/ForwardedInvariantTest.java`

```java
package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The module's invariant, end to end over HTTP: a forged forwarding header sent straight to
 * SeatWave is ignored, and the same header from a trusted proxy is believed.
 */
class ForwardedInvariantTest {
```

The class comment states the purpose: the module's invariant "end to end over HTTP".

```java
  @Test
  void ignoresAForgedXForwardedForSentStraightToSeatWave() {
    try (TestBackend backend = TestBackend.untrusting()) {
      HttpResponse<String> view =
          backend.send(
              HttpRequest.newBuilder(backend.plain("/lab/client"))
                  .header("X-Forwarded-For", "203.0.113.7")
                  .header("X-Forwarded-Proto", "https")
                  .build());

      assertThat(view.body())
          .isEqualTo("{\"address\":\"127.0.0.1\",\"scheme\":\"http\",\"source\":\"peer\"}");
      assertThat(backend.metrics()).contains("seatwave_forwarding_headers_ignored_total 1");
    }
  }
```

**Forged header, untrusted peer.** The backend trusts only `192.0.2.1`, and the test calls it from `127.0.0.1` with `X-Forwarded-For: 203.0.113.7` and `X-Forwarded-Proto: https`. `/lab/client` must answer with the peer's address, the scheme `http` and the source `peer`, and the metrics must show the ignored-header counter at 1.

```java
  @Test
  void believesTheSameHeaderFromATrustedProxy() {
    try (TestBackend backend =
        TestBackend.start(List.of("127.0.0.1"), TestBackend.quickTiming(), 4, 16)) {
      HttpResponse<String> view =
          backend.send(
              HttpRequest.newBuilder(backend.plain("/lab/client"))
                  .header("X-Forwarded-For", "203.0.113.7")
                  .header("X-Forwarded-Proto", "https")
                  .build());

      assertThat(view.body())
          .isEqualTo(
              "{\"address\":\"203.0.113.7\",\"scheme\":\"https\",\"source\":\"x_forwarded_for\"}");
      assertThat(backend.metrics()).contains("seatwave_forwarding_headers_ignored_total 0");
    }
  }
```

**Same header, trusted peer.** With `127.0.0.1` on the trusted list, the same request returns `203.0.113.7`, `https` and `x_forwarded_for`, and the ignored counter stays 0.

```java
  @Test
  void countsAnUnreadableHeaderFromATrustedProxyAndFallsBackToThePeer() {
    try (TestBackend backend =
        TestBackend.start(List.of("127.0.0.1"), TestBackend.quickTiming(), 4, 16)) {
      HttpResponse<String> view =
          backend.send(
              HttpRequest.newBuilder(backend.tls("/lab/client"))
                  .header("Forwarded", "for=unknown")
                  .build());

      assertThat(view.body())
          .isEqualTo("{\"address\":\"127.0.0.1\",\"scheme\":\"https\",\"source\":\"unreadable\"}");
      assertThat(backend.metrics()).contains("seatwave_forwarding_headers_unreadable_total 1");
    }
  }
```

**Unreadable.** A trusted peer sends `Forwarded: for=unknown` to the HTTPS port. The answer falls back to the peer, with the scheme `https` (the listener's) and the source `unreadable`, and the unreadable counter is 1.

```java
  @Test
  void usesTheProxysRequestIdOnlyWhenTheProxyIsTrusted() {
    String requestId = "0123456789abcdef0123456789abcdef";
    try (TestBackend untrusting = TestBackend.untrusting();
        TestBackend trusting =
            TestBackend.start(List.of("127.0.0.1"), TestBackend.quickTiming(), 4, 16)) {
      HttpRequest.Builder request =
          HttpRequest.newBuilder().header("X-Request-Id", requestId).GET();

      String fromStranger =
          untrusting
              .send(
                  request
                      .uri(untrusting.plain("/v1/holds/00000000-0000-0000-0000-000000000000"))
                      .build())
              .body();
      String fromProxy =
          trusting
              .send(
                  request
                      .uri(trusting.plain("/v1/holds/00000000-0000-0000-0000-000000000000"))
                      .build())
              .body();

      assertThat(fromStranger).doesNotContain(requestId);
      assertThat(fromProxy).contains("\"instance\":\"urn:seatwave:request:" + requestId + "\"");
    }
  }
}
```

**The request id.** The test starts two backends in one try-with-resources: one untrusting, one trusting `127.0.0.1`. It sends `X-Request-Id: 0123456789abcdef0123456789abcdef` to a `GET /v1/holds/<zero uuid>` (which is a 404, whose body includes the request id in its `instance`). The stranger's reply must not contain the id; the trusted peer's reply must contain `"instance":"urn:seatwave:request:<id>"`. One builder is reused for both requests; `.uri(...)` changes only the address.

### `backend/HoldKeysTest.java`

```java
package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HoldKeysTest {

  private static final Reply.Body CREATED = Reply.json(201, "{\"holdId\":\"x\"}");

  @Test
```

```java
  void givesTheFirstClaimTheWorkAndTellsASecondOneToWait() {
    HoldKeys keys = new HoldKeys();

    HoldKeys.Claim first = keys.claim("\"k\"", "fingerprint-1");
    HoldKeys.Claim second = keys.claim("\"k\"", "fingerprint-1");

    assertThat(first).isInstanceOf(HoldKeys.Claim.Owner.class);
    assertThat(second).isInstanceOf(HoldKeys.Claim.InProgress.class);
  }
```

The first claim for a key is `Owner`, and a second claim with the same fingerprint, while the first has not completed, is `InProgress`.

```java
  @Test
  void replaysTheStoredReplyAndRefusesTheKeyWithAnotherBody() {
    HoldKeys keys = new HoldKeys();
    keys.claim("\"k\"", "fingerprint-1");
    keys.complete("\"k\"", "fingerprint-1", CREATED);

    HoldKeys.Claim replay = keys.claim("\"k\"", "fingerprint-1");
    HoldKeys.Claim reused = keys.claim("\"k\"", "fingerprint-2");

    assertThat(replay).isEqualTo(new HoldKeys.Claim.Replay(CREATED));
    assertThat(reused).isInstanceOf(HoldKeys.Claim.Reused.class);
  }
```

After `complete`, the same fingerprint gives `Replay` with the stored reply (records compare by value, so `isEqualTo(new Claim.Replay(CREATED))` works) and another fingerprint gives `Reused`.

```java
  @Test
  void refusesANewKeyOnceTheTableIsFull() {
    HoldKeys keys = new HoldKeys();
    for (int keyIndex = 0; keyIndex < HoldKeys.MAX_KEYS; keyIndex++) {
      keys.claim("\"k" + keyIndex + "\"", "fingerprint");
    }

    HoldKeys.Claim overflow = keys.claim("\"one-more\"", "fingerprint");
    HoldKeys.Claim existing = keys.claim("\"k0\"", "fingerprint");

    assertThat(overflow).isInstanceOf(HoldKeys.Claim.Full.class);
    assertThat(existing).isInstanceOf(HoldKeys.Claim.InProgress.class);
    assertThat(List.of(overflow, existing)).doesNotHaveDuplicates();
  }
}
```

The table limit: claim 100,000 distinct keys, then one more is `Full`; an existing key is still answered (`InProgress`). The last assertion, `doesNotHaveDuplicates` on a list of the two claims, adds little: it only checks that the two results are different objects. No test exercises the lost-race branch of `claim` (another thread wins `putIfAbsent`), and none checks that `Full` does not leak a count.

### `backend/HoldLedgerTest.java`

```java
package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class HoldLedgerTest {

  private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
```

```java
  @Test
  void letsExactlyOneOfManyConcurrentRequestsHoldOneSeat() throws Exception {
    HoldLedger ledger = new HoldLedger(Clock.fixed(NOW, ZoneOffset.UTC));
    int racers = 100;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Optional<Hold>>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int racer = 0; racer < racers; racer++) {
        String customer = "fan-" + racer;
        results.add(
            pool.submit(() -> {
              start.await();
              return ledger.hold(1, "A-7", customer);
            }));
      }
      start.countDown();
      long winners = 0;
      for (Future<Optional<Hold>> result : results) {
        winners += result.get().isPresent() ? 1 : 0;
      }

      assertThat(winners).isEqualTo(1);
    }
  }
```

**One winner among many.** 100 virtual threads (cheap threads, one per task) each wait on a `CountDownLatch` and then call `ledger.hold(1, "A-7", customer)` at the same moment; `start.countDown()` opens the gate for all. The number of non-empty results must be exactly 1. The clock is fixed, so no hold can expire during the race. The `try (ExecutorService pool = ...)` form waits for the tasks to finish when the block ends.

```java
  @Test
  void letsAnotherCustomerHoldTheSeatOnceTheFirstHoldExpired() {
    MovableClock clock = new MovableClock(NOW);
    HoldLedger ledger = new HoldLedger(clock);
    Hold first = ledger.hold(1, "B-2", "fan-1").orElseThrow();

    clock.advance(HoldLedger.HOLD_DURATION.minusSeconds(1));
    Optional<Hold> tooEarly = ledger.hold(1, "B-2", "fan-2");
    clock.advance(Duration.ofSeconds(1));
    Optional<Hold> afterExpiry = ledger.hold(1, "B-2", "fan-2");

    assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    assertThat(tooEarly).isEmpty();
    assertThat(afterExpiry).isPresent();
    assertThat(ledger.isHeld("B-2")).isTrue();
    assertThat(ledger.find(first.holdId())).contains(first);
  }
```

**Expiry.** With a clock the test moves by hand: a hold lasts 300 seconds (`first.expiresAt()` is `NOW + 300 s`). At 299 seconds another customer is refused; at 300 seconds the same request succeeds (the hold is active only while `now` is before the expiry). The seat is held again, and the old hold can still be found by its id: the test pins down that `holdsById` keeps expired holds.

```java
  /** A clock a test moves forward by hand; used by one thread. */
  private static final class MovableClock extends Clock {
    private Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration step) {
      now = now.plus(step);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
```

`MovableClock` is a test double: a subclass of `java.time.Clock` that returns a stored `Instant` and lets the test advance it. The comment says it is used by one thread; it is not thread-safe, and that is fine for this test.

### `backend/SeatCatalogTest.java`

```java
package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SeatCatalogTest {

  private final SeatCatalog catalog = new SeatCatalog(new HoldLedger(Clock.systemUTC()));
```

```java
  @Test
  void acceptsOnlyTheArenasSeatLabels() {
    assertThat(SeatCatalog.isSeat("A-1")).isTrue();
    assertThat(SeatCatalog.isSeat("T-1000")).isTrue();
    assertThat(SeatCatalog.isSeat("A-1001")).isFalse();
    assertThat(SeatCatalog.isSeat("A-0")).isFalse();
    assertThat(SeatCatalog.isSeat("U-1")).isFalse();
    assertThat(SeatCatalog.isSeat("A-01")).isFalse();
  }
```

Seat labels: `A-1` and `T-1000` are valid; `A-1001`, `A-0`, `U-1` and `A-01` are not.

```java
  @Test
  void refusesACursorThisServerDidNotWrite() {
    assertThat(SeatCatalog.decode("czEuQS0xMDAw")).contains(new SeatCatalog.SeatPosition(0, 1000));
    assertThat(SeatCatalog.decode("czIuQS0x")).isEmpty(); // s2.A-1: another prefix
    assertThat(SeatCatalog.decode("czEuQS0xMDAx")).isEmpty(); // s1.A-1001: no such seat
    assertThat(SeatCatalog.decode("!!!")).isEmpty();
  }
```

Cursors: `czEuQS0xMDAw` decodes to position (0, 1000); `czIuQS0x` (`s2.A-1`) has another prefix, `czEuQS0xMDAx` (`s1.A-1001`) names no seat, and `!!!` is not Base64: all empty.

```java
  @Test
  void writesTheLastPageWithoutANextCursor() {
    SeatCatalog.Page page =
        catalog.page(Optional.of("T"), 500, Optional.of(new SeatCatalog.SeatPosition(19, 500)));

    assertThat(page.items()).hasSize(500);
    assertThat(page.items().getLast().seat()).isEqualTo("T-1000");
    assertThat(page.isLast()).isTrue();
  }
```

The last page: after position (19, 500) in section T with limit 500, there are exactly 500 items, the last one is `T-1000`, and the page is last.

```java
  @Test
  void writesVersionZerosSeatMapInModuleOnesTextOrder() {
    String map = new String(catalog.version0SeatMap("A"), java.nio.charset.StandardCharsets.UTF_8);

    assertThat(map).hasSize(36_894);
    assertThat(map).startsWith("[{\"seat\":\"A-1\",\"state\":\"available\"},{\"seat\":\"A-10\"");
  }
}
```

Version 0: 36,894 characters, starting with `A-1` followed by `A-10`, the text order.

### `backend/BackendSettingsTest.java`

```java
package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class BackendSettingsTest {
```

```java
  @Test
  void readsTheContainerSettingsOfTheRepository() throws IOException {
    Properties file = SettingsReader.load(Path.of("config/backend-container.properties"));

    BackendSettings settings =
        BackendSettings.from(new SettingsReader(file))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));

    assertThat(settings.plainPort()).isEqualTo(8080);
    assertThat(settings.workerThreads()).isEqualTo(64);
    assertThat(settings.backlog()).isEqualTo(1024);
    assertThat(settings.trustedProxies().addresses()).hasSize(2);
    assertThat(settings.timing().longPollHold()).isEqualTo(Duration.ofSeconds(25));
  }
```

The real `config/backend-container.properties` is loaded and must produce valid settings with the values the README states: port 8080, 64 workers, backlog 1,024, two trusted proxies, a 25-second long poll. A failing result is turned into an exception that lists the problems.

```java
  @Test
  void reportsEveryProblemIncludingTheRulesBetweenKeysTogether() throws IOException {
    Properties file = SettingsReader.load(Path.of("config/backend-local.properties"));
    file.setProperty("http.tlsPort", file.getProperty("http.plainPort"));
    file.setProperty("feed.streamLifetimeMillis", "10");
    file.setProperty("forwarded.trustedProxies", "999.1.1.1");
    file.setProperty("http.bindAddress", "300.1.1.1");
    file.remove("http.workerThreads");

    Result<BackendSettings, List<String>> result = BackendSettings.from(new SettingsReader(file));

    assertThat(result).isInstanceOf(Result.Error.class);
    List<String> problems = ((Result.Error<BackendSettings, List<String>>) result).error();
    assertThat(problems)
        .anyMatch(line -> line.equals("http.workerThreads is missing"))
        .anyMatch(
            line -> line.startsWith("http.plainPort, http.tlsPort and http.adminPort must differ"))
        .anyMatch(line -> line.startsWith("feed.streamLifetimeMillis must be longer"))
        .anyMatch(line ->
            line.startsWith("forwarded.trustedProxies: a trusted proxy must be an IP address"))
        .anyMatch(line -> line.equals("http.bindAddress must be an IP address, but was 300.1.1.1"));
  }
```

Start from the local file and break it five ways at once: the TLS port equals the plain port, the stream lifetime is shorter than the heartbeat, the trusted proxy is `999.1.1.1`, the bind address is `300.1.1.1`, and `http.workerThreads` is removed. The result must be an error that contains each of the five messages. The cast `(Result.Error<...>) result` reaches the list of problems.

```java
  @Test
  void refusesListenersThatShareAPortWhenBuiltDirectly() {
    BackendSettings.Timing timing = TestBackend.quickTiming();

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new BackendSettings(
                java.net.InetAddress.getLoopbackAddress(),
                20931,
                20930,
                20931,
                Path.of("x"),
                Path.of("y"),
                1,
                1,
                1,
                io.plainround.seatwave.proxy.forwarded.TrustedProxies.parse(List.of("127.0.0.1"))
                    .orOnErrorThrow(IllegalStateException::new),
                timing))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new BackendSettings.Timing(Duration.ofMillis(-1), Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

Direct construction: two listeners on the same port throw `IllegalArgumentException`, and a negative duration in `Timing` does too. The test builds the settings with a made-up key store path (`x`, `y`) because the constructor does not read files.

### `backend/BackendCliTest.java`

```java
package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendCliTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @Test
  void printsTheUsageWithoutExactlyOneArgument() {
    Optional<BackendServer> server = BackendCli.start(new String[0], print(out), print(err));

    assertThat(server).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: serve <backend.properties>");
  }
```

Two `ByteArrayOutputStream`s capture what the command prints, one for normal output and one for errors. The first test calls `BackendCli.start` with no arguments and expects an empty `Optional` and the usage line on the error stream. `server` is `Optional<BackendServer>`; `isEmpty()` is the check.

```java
  @Test
  void printsEveryProblemOfAnInvalidSettingsFile(@TempDir Path folder) throws IOException {
    Path file =
        Files.writeString(
            folder.resolve("bad.properties"), "http.plainPort=x\n", StandardCharsets.UTF_8);

    Optional<BackendServer> server =
        BackendCli.start(new String[] {file.toString()}, print(out), print(err));

    assertThat(server).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains(
            "problem(s):",
            "http.plainPort must be a whole number, but was x",
            "tls.keystore is missing");
  }
```

A settings file with only `http.plainPort=x` (written into a temporary folder that JUnit makes and deletes through `@TempDir`) must print the header `problem(s):`, the number error and `tls.keystore is missing`.

```java
  @Test
  void saysWhichTlsFileIsMissing(@TempDir Path folder) throws IOException {
    String settings =
        Files.readString(Path.of("config/backend-local.properties"), StandardCharsets.UTF_8)
            .replace("build/tls/seatwave-server.p12", "build/none.p12")
            .replace("http.plainPort=20931", "http.plainPort=0")
            .replace("http.tlsPort=20930", "http.tlsPort=0")
            .replace("http.adminPort=20932", "http.adminPort=0");
    Path file =
        Files.writeString(folder.resolve("local.properties"), settings, StandardCharsets.UTF_8);

    Optional<BackendServer> server =
        BackendCli.start(new String[] {file.toString()}, print(out), print(err));

    assertThat(server).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("SeatWave cannot start: the file build/none.p12 does not exist");
  }
```

The local settings are copied with the key store path changed to a file that does not exist, and the three ports set to 0. `start` must print `SeatWave cannot start: the file build/none.p12 does not exist`. The relative path `build/tls/store.password` still has to exist for the password step to pass first, so this test also depends on `testCertificates` and on the working directory being the project folder.

```java
  @Test
  void startsAndPrintsItsListenersFromAValidFile(@TempDir Path folder) throws IOException {
    String settings =
        Files.readString(Path.of("config/backend-local.properties"), StandardCharsets.UTF_8)
            .replace("http.plainPort=20931", "http.plainPort=0")
            .replace("http.tlsPort=20930", "http.tlsPort=0")
            .replace("http.adminPort=20932", "http.adminPort=0")
            .replace("build/tls/", TestBackend.TLS + "/");
    Path file =
        Files.writeString(folder.resolve("local.properties"), settings, StandardCharsets.UTF_8);

    Optional<BackendServer> server =
        BackendCli.start(new String[] {file.toString()}, print(out), print(err));

    try {
      assertThat(server).isPresent();
      assertThat(out.toString(StandardCharsets.UTF_8))
          .contains(
              "SeatWave plain HTTP on 127.0.0.1:",
              "(TLSv1.3)",
              "64 worker threads, trusted proxies [127.0.0.1]");
    } finally {
      server.ifPresent(BackendServer::close);
    }
  }
```

A valid start: the local settings with ports 0 and the key store path rewritten to the absolute TEST folder (`.replace("build/tls/", TestBackend.TLS + "/")`). The command must print the plain-HTTP line, `(TLSv1.3)`, and `64 worker threads, trusted proxies [127.0.0.1]`. The `finally` block closes the server whatever happens. **This test fails when the repository sits in a deep folder.** `BackendSettings` only accepts file paths of at most 200 characters made of letters, digits and `_ . / -`. In a copy under a scratch folder whose absolute path to `seatwave-server.p12` is 211 characters, the test failed (`Expecting Optional to contain a value but it was empty`) and the error stream said `tls.keystore must match [A-Za-z0-9_./-]{1,200}, but was <repo>/build/tls/seatwave-server.p12`. A folder name with a space or a plus sign would break it the same way. The paths of the real project (about 100 characters) are fine.

```java
  private static PrintStream print(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }
}
```

`print` wraps a byte stream in a `PrintStream` that flushes at once (`true`) and uses UTF-8.

## Try it yourself

All outputs come from a copy of the repository. Timings differ on every run.

**1. Run only this package's fast tests.**

```
./gradlew test --tests '*HoldLedgerTest' --tests '*HoldKeysTest' --tests '*SeatCatalogTest' --tests '*BackendSettingsTest'
```

They pass.

**2. Run the server tests.**

```
./gradlew test --tests '*BackendServerTest' --tests '*ForwardedInvariantTest'
```

They pass (`BackendServerTest` has the 3-second queue test, so allow some seconds).

**3. Run the whole suite and read the totals.**

```
./gradlew clean check
```

In the repository folder the README reports 119 tests, 0 failures and 91.8 percent line coverage (1,937 of 2,111 lines). In my copy under a long path, 119 tests ran and 2 failed: `BackendCliTest.startsAndPrintsItsListenersFromAValidFile` (the path length above) and `BenchCliTest.stopsWithExitStatusOneWhenTheContainersCannotBeRead` of the `bench` package (not examined here). Coverage in that run was 90.1 percent (1,902 of 2,111 lines). In my copy the lines of `BackendCli.start` after the settings check were not executed because of the first failure.

**4. See that the long-path failure is the path.** In a copy of the test, print the error stream before the assertion:

```
System.out.println("ERRTEXT:" + err.toString(StandardCharsets.UTF_8));
```

The run printed the `tls.keystore must match ...` and `tls.passwordFile must match ...` lines quoted above.

**5. Open the report.** After a run, `build/reports/tests/test/index.html` lists every test with its time, and `build/reports/jacoco/test/html/index.html` shows the coverage per class.

## Mistakes people make

- **Sleeping instead of waiting.** `Thread.sleep(500)` is a guess. `await().until(condition)` waits as long as needed and no longer.
- **Sharing state between tests.** Each test here gets its own server. The ledger and the key table are in memory, so a shared server would carry holds from one test to the next.
- **Reusing an Idempotency-Key across cases.** The tests use a different key for each case (`key-1` to `key-8`); reusing one would replay an earlier answer.
- **Asserting too little.** `contains("A-1")` also matches `A-10`. Check the whole line when exactness matters.
- **Absolute paths in settings.** The 200-character rule makes one test depend on how deep the clone is.
- **Assuming a refused request returns a status.** A queue refusal closes the connection; the client sees an exception.
- **Counting after the response.** Metrics updated after the response is sent can be read late by a very fast client; the tests accept this tiny window.

## Check yourself

1. Why does `TestBackend.untrusting()` trust `192.0.2.1`? Answer: to trust an address that no test uses, so that `127.0.0.1` (the test client) is an untrusted peer.
2. How does the test that makes the queue overflow know the only worker is busy? Answer: it waits (Awaitility) until `/metrics` shows `seatwave_workers_busy 1`.
3. What proves that only one of 100 racers holds seat A-7? Answer: `HoldLedgerTest` starts 100 threads at one latch and counts non-empty results; it must be exactly 1, because `compute` makes the check and the write one step.
4. What does the cursor `czEuQS0xMDAw` mean? Answer: Base64 for `s1.A-1000`: "after seat A-1000", so the next page starts at `B-1`.
5. Why does `BackendCliTest.startsAndPrintsItsListenersFromAValidFile` fail in a deep folder? Answer: the settings rule for file paths allows at most 200 characters; the test writes the absolute key store path into the settings, and it is longer than that.

## Further reading

- JUnit 6 User Guide (the version pinned in `gradle/libs.versions.toml` is 6.1.3): https://docs.junit.org/current/user-guide/
- AssertJ Core documentation (3.27.7): https://assertj.github.io/doc/
- Awaitility usage guide (4.3.0): https://github.com/awaitility/awaitility/wiki/Usage
- Java SE 25 API, `java.net.http.HttpClient`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- Java SE 25 API, `java.util.concurrent.CompletableFuture`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html
