# The `probe` package, line by line

## What it is

The `probe` package is the outage probe of the single-point-of-failure lab. One client sends one small request at a fixed rhythm (every 100 ms for 30 seconds in `config/probe.properties`) and writes down how each request ended: with a status code such as `200`, or with a kind of failure such as `connection refused`. At the end it prints the history as a few lines, one for each stretch of equal results, and the length of the outage.

The package has four files: `ProbeSettings` (the settings), `Outcome` (one request's result), `Timeline` (turns hundreds of outcomes into a few lines) and `ProbeCli` (the command, and the loop that sends the requests).

A **single point of failure** is one part of a system whose loss stops the whole system. With one proxy in front of SeatWave, the proxy is one. The lab stops the proxy for ten seconds, then SeatWave, then reloads the proxy's configuration, and watches what a fan would see.

## Why this repository uses it

Module 9 says that a proxy adds one more part that can fail. "It can fail" is not a number. The probe turns it into one: how many seconds a fan was without service, and what the failure looked like from outside (a refused connection is not a timeout, and the difference matters to a user and to a retry rule).

The script `scripts/spof.sh` (see `shell-scripts-walkthrough.md`) starts this probe in the background and, at fixed seconds, stops and starts a container or reloads NGINX. The probe knows nothing about Docker. It only asks for the page and records.

## Where it appears

| File | What it does |
|---|---|
| `probe/ProbeSettings.java` | Reads `config/probe.properties`: the address, the certificate authority, the interval, the duration and two timeouts. |
| `probe/Outcome.java` | One request: when it was sent, its result text, its latency. |
| `probe/Timeline.java` | Groups outcomes into spans, finds the outage, writes the text. |
| `probe/ProbeCli.java` | `main`, the sending loop and the mapping from Java exceptions to result texts. |

The Gradle task `probe` runs `ProbeCli`: `./gradlew -q probe --args='config/probe.properties'`.

## How it works underneath

**A probe sends on a schedule.** It does not send "as fast as it can". The loop has a list of planned send times: time zero, then every interval after it. For each planned time it waits until that time and sends. If a request takes longer than the interval (a timeout), the next planned time has already passed, so the loop sends the next request at once, and goes on without waiting until it has caught up. The number of requests is therefore always the duration divided by the interval: 300 for 30 seconds at 100 ms. In the README's backend outage, the stretch after the outage shows 242 answers, more than the 143 that a 100 ms rhythm gives in 14 seconds, because the loop was catching up.

**Keep-alive.** The probe uses one `HttpClient`, so its requests reuse one connection to the proxy, as a busy browser tab does. When the proxy stops, that connection breaks and the next request has to connect again.

**What "failure" looks like.** The Java HTTP client reports problems as exceptions. Four cases are told apart:

| Result text | Meaning |
|---|---|
| `connection refused` | The operating system answered the connection attempt with a refusal: nothing listens on that port (the proxy is stopped). |
| `connect timeout` | The connection did not come up within the connect timeout. The same text appears if the TCP connection is up but the TLS handshake does not finish. |
| `request timeout` | The connection worked, but the whole answer did not arrive within the request timeout. |
| `connection lost` | Anything else that is an input-output error: a connection closed or reset under the request, or a failed TLS handshake. |

**A span.** Consecutive outcomes with the same result form a **span**. A 30-second probe has 300 outcomes but, in the README's proxy outage, only four spans: before, a short "connection lost", the refusals, and after.

**The outage.** The code defines the outage as the time from the first failed request to the first success after the last failed one. It uses the planned send times of those two requests, not the times the answers arrived.

**Java words new in this file.** A **checked exception** is one the compiler forces you to catch or declare (`IOException`, `InterruptedException`); `throws` declares it. **Catch order**: Java checks the `catch` blocks from top to bottom and takes the first that fits, so a more specific exception class must come before a more general one, or the code does not compile. `Optional<Duration>` is a box that holds a duration or nothing. A **nested record** is a record declared inside another class. `try (client) { ... }` closes an existing variable at the end of the block.

## Walkthrough of this repository's use

### `probe/ProbeSettings.java`

```java
package io.plainround.seatwave.proxy.probe;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
```

- The same helper imports as the other settings classes: `Result`, `SettingsReader`, `URI`, `Path`, `Duration`, `List`, `Objects`, `Pattern`.

```java
/**
 * Everything the probe needs, read from a settings file with no defaults.
 *
 * @param uri the resource to ask for, on 127.0.0.1 in the lab's port range
 * @param authorityPem the TEST certificate authority the client trusts
 * @param interval the time between two requests
 * @param duration how long the probe runs
 * @param connectTimeout the connect timeout of every request
 * @param requestTimeout the whole-request timeout of every request
 */
public record ProbeSettings(
    URI uri,
    Path authorityPem,
    Duration interval,
    Duration duration,
    Duration connectTimeout,
    Duration requestTimeout) {
```

- A record of six components: `uri` (what to ask for), `authorityPem` (the TEST certificate authority file), `interval` (the time between two requests), `duration` (how long the probe runs), `connectTimeout` and `requestTimeout`.

```java
  private static final Pattern LAB_URI =
      Pattern.compile("https?://127\\.0\\.0\\.1:209[0-9]{2}/[A-Za-z0-9/._?=&-]*");
  private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_./-]{1,200}");
  private static final int MAX_MILLIS = 600_000;
```

- `LAB_URI` is the same allow-list as in the bench: `http` or `https`, the address `127.0.0.1`, a port from 20900 to 20999, and a path. A probe cannot be aimed at another host.
- `FILE` allows a path of up to 200 characters of letters, digits and `_ . / -`.
- `MAX_MILLIS` is ten minutes, an upper bound for any time.

```java
  /** Checks that nothing is missing. */
  public ProbeSettings {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(authorityPem, "authorityPem");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
  }
```

- The compact constructor checks that no component is missing.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<ProbeSettings, List<String>> from(SettingsReader reader) {
    String uri = reader.textMatching("probe.uri", LAB_URI);
    String authority = reader.textMatching("tls.authorityPem", FILE);
    Duration interval = reader.millisBetween("probe.intervalMillis", 1, MAX_MILLIS);
    Duration duration = reader.millisBetween("probe.durationMillis", 1, MAX_MILLIS);
    Duration connect = reader.millisBetween("client.connectTimeoutMillis", 1, MAX_MILLIS);
    Duration request = reader.millisBetween("client.requestTimeoutMillis", 1, MAX_MILLIS);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new ProbeSettings(
            URI.create(uri), Path.of(authority), interval, duration, connect, request));
  }
}
```

- `from(reader)` reads the six keys: `probe.uri`, `tls.authorityPem`, `probe.intervalMillis` (from 1 ms), `probe.durationMillis`, `client.connectTimeoutMillis` and `client.requestTimeoutMillis`. All are required: a missing key becomes a problem sentence.
- If there are problems, the method returns them as an error. Otherwise it builds the record.
- Nothing checks that the interval is shorter than the duration, or that the timeouts are shorter than the interval. The shipped file has an interval of 100 ms, a connect timeout of 200 ms and a request timeout of 1,000 ms; a timeout longer than the interval makes the loop catch up, as described above.

### `probe/Outcome.java`

```java
package io.plainround.seatwave.proxy.probe;

import java.time.Duration;
import java.util.Objects;

/**
 * One probe request: when it was sent, how it ended and how long it took.
 *
 * @param sentAt the time since the probe started
 * @param result the status code, such as {@code 200} or {@code 502}, or the kind of failure, such
 *     as {@code connection refused}
 * @param latency how long the request took
 */
public record Outcome(Duration sentAt, String result, Duration latency) {
```

- A record of three components. `sentAt` is the time since the probe started (not a clock time). `result` is the status code as text (`"200"`, `"502"`) or one of the four failure texts above. `latency` is how long the request took.

```java
  /** Checks that nothing is missing or negative. */
  public Outcome {
    Objects.requireNonNull(sentAt, "sentAt");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(latency, "latency");
    if (sentAt.isNegative() || latency.isNegative() || result.isBlank()) {
      throw new IllegalArgumentException("an outcome needs a time, a result and a latency");
    }
  }
```

- The compact constructor refuses a missing value, a negative time, and a blank result (`isBlank()` is true for an empty text or one of spaces only).

```java
  /**
   * Says whether the client got what it asked for.
   *
   * @return true for 200
   */
  public boolean isSuccess() {
    return result.equals("200");
  }
}
```

- `isSuccess()` is true only when the result text is exactly `"200"`. A `204` or a `301` would count as a failure. The shipped target `/health` answers 200.

### `probe/Timeline.java`

```java
package io.plainround.seatwave.proxy.probe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
```

- `ArrayList` and `List` hold the outcomes and the spans; `Locale` fixes the number format; `Optional` is the box for "an outage, or none".

```java
/**
 * Turns a probe's outcomes into spans of equal results, so an outage reads as three lines (before,
 * during, after) instead of hundreds. Thread-safe: it holds no state.
 */
public final class Timeline {

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private Timeline() {}
```

- The class comment says what it does: an outage reads as three lines (before, during, after) instead of hundreds. "Thread-safe: it holds no state": all methods are static and use only their arguments.
- Two constants turn nanoseconds into seconds and milliseconds.

```java
  /**
   * Groups consecutive outcomes with the same result.
   *
   * @param outcomes in the order they were sent
   * @return one span per run of equal results
   */
  public static List<Span> spans(List<Outcome> outcomes) {
    Objects.requireNonNull(outcomes, "outcomes");
    List<Span> spans = new ArrayList<>();
    List<Outcome> current = new ArrayList<>();
    for (Outcome outcome : outcomes) {
      if (!current.isEmpty() && !current.getFirst().result().equals(outcome.result())) {
        spans.add(new Span(current));
        current = new ArrayList<>();
      }
      current.add(outcome);
    }
    if (!current.isEmpty()) {
      spans.add(new Span(current));
    }
    return List.copyOf(spans);
  }
```

- `spans(outcomes)` walks the outcomes in order. `current` is the span being built. When the next outcome has a different result from the first of `current`, the finished `current` is closed into a `Span` and a new list is started. The last span is added after the loop.
- `current.getFirst()` is the first item of a list (a method added in Java 21).
- `List.copyOf(spans)` returns an unmodifiable copy.

```java
  /**
   * Returns how long the clients were without service: from the first failed request to the first
   * success after the last failed one.
   *
   * @param outcomes in the order they were sent
   * @return the outage, or empty if nothing failed or service never came back
   */
  public static Optional<Duration> outage(List<Outcome> outcomes) {
    int firstFailure = -1;
    int lastFailure = -1;
    for (int index = 0; index < outcomes.size(); index++) {
      if (!outcomes.get(index).isSuccess()) {
        firstFailure = firstFailure < 0 ? index : firstFailure;
        lastFailure = index;
      }
    }
    if (firstFailure < 0 || lastFailure == outcomes.size() - 1) {
      return Optional.empty();
    }
    Duration start = outcomes.get(firstFailure).sentAt();
    return Optional.of(outcomes.get(lastFailure + 1).sentAt().minus(start));
  }
```

- `outage(outcomes)` scans once and remembers the index of the first failure and of the last failure. A failure is any outcome that is not `isSuccess()`. `firstFailure < 0 ? index : firstFailure` keeps the first index it saw.
- If nothing failed (`firstFailure < 0`), or the last outcome itself failed (`lastFailure == outcomes.size() - 1`: service never came back inside the probe), the answer is `Optional.empty()`.
- Otherwise the outage is the planned send time of the outcome after the last failure, minus the send time of the first failure: `Duration.minus`.
- Every failure counts, so an outage in two pieces is one long outage that includes the working time between its pieces. The README's proxy outage shows 10.502 s for a 10-second stop: a little more than the stop itself, because both ends are send times of requests, which are a fraction of a second apart from the stop and the start.

```java
  /**
   * Writes the spans and the outage as text.
   *
   * @param outcomes in the order they were sent
   * @return one line per span, and one line for the outage
   */
  public static String render(List<Outcome> outcomes) {
    StringBuilder text = new StringBuilder();
    for (Span span : spans(outcomes)) {
      text.append(
          String.format(
              Locale.ROOT,
              "%7.3f s to %7.3f s  %-22s x %4d  latency p50 %8.2f ms, max %8.2f ms%n",
              seconds(span.first().sentAt()),
              seconds(span.last().sentAt()),
              span.first().result(),
              span.outcomes().size(),
              millis(span.medianLatency()),
              millis(span.maxLatency())));
    }
```

- `render` writes one line per span. The format string `"%7.3f s to %7.3f s  %-22s x %4d  latency p50 %8.2f ms, max %8.2f ms%n"` has seven fields: the first and the last send time of the span in seconds with three decimals, the result text left-aligned in 22 characters, the number of requests, the median latency of the span and the largest.
- `span.first()` and `span.last()` are the first and last outcomes; `span.outcomes().size()` is how many.

```java
    text.append(
        outage(outcomes)
            .map(duration ->
                String.format(
                    Locale.ROOT,
                    "outage: %.3f s from the first failed request to the first success after"
                        + " the last%n",
                    seconds(duration)))
            .orElse("outage: none that ended inside the probe" + System.lineSeparator()));
    return text.toString();
  }
```

- The last line is the outage. `outage(outcomes).map(duration -> String.format(...))` turns the duration into text if there is one. `.orElse("outage: none that ended inside the probe" + lineSeparator)` is the text otherwise.
- The "none" text is printed in two different situations: nothing failed, and service never came back. A probe that failed 100 percent of the time prints `outage: none that ended inside the probe` too (the Try section shows it).

```java
  private static double seconds(Duration duration) {
    return duration.toNanos() / NANOS_PER_SECOND;
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
```

- Two converters, `seconds` and `millis`, from `Duration`.

```java
  /**
   * Consecutive outcomes with one result.
   *
   * @param outcomes at least one, all with the same result
   */
  public record Span(List<Outcome> outcomes) {

    /**
     * Copies and checks the outcomes.
     *
     * @throws IllegalArgumentException if the list is empty or mixes results
     */
    public Span {
      outcomes = List.copyOf(outcomes);
      if (outcomes.isEmpty()) {
        throw new IllegalArgumentException("a span needs at least one outcome");
      }
      String result = outcomes.getFirst().result();
      if (outcomes.stream().anyMatch(outcome -> !outcome.result().equals(result))) {
        throw new IllegalArgumentException("a span holds one result only");
      }
    }
```

- `Span` is a public record nested in `Timeline`. Its compact constructor copies the list, refuses an empty one, and refuses a list that mixes results (`anyMatch(outcome -> !outcome.result().equals(result))`). A span cannot be built wrongly.

```java
    Outcome first() {
      return outcomes.getFirst();
    }

    Outcome last() {
      return outcomes.getLast();
    }
```

- `first()` and `last()` are package-visible helpers for `render`.

```java
    Duration medianLatency() {
      List<Duration> sorted = outcomes.stream().map(Outcome::latency).sorted().toList();
      return sorted.get((sorted.size() - 1) / 2);
    }

    Duration maxLatency() {
      return outcomes.stream().map(Outcome::latency).max(Duration::compareTo).orElseThrow();
    }
  }
}
```

- `medianLatency()` sorts the latencies and takes the item at `(size - 1) / 2` (whole-number division), the lower middle for an even count. For 4 items the index is 1, and for 5 items it is 2. The result is a latency that was really measured.
- `maxLatency()` is the largest, found with `max(Duration::compareTo)`; `orElseThrow()` unwraps the `Optional` (a span is never empty).

### `probe/ProbeCli.java`

```java
package io.plainround.seatwave.proxy.probe;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
```

- Imports include the four exception types the HTTP client can throw: `ConnectException` (`java.net`), and `HttpConnectTimeoutException` and `HttpTimeoutException` (`java.net.http`), plus `IOException`.

```java
/**
 * Command-line entry point of the probe: one client sends a request at a fixed interval and records
 * how each one ended, while a script stops and starts a container. The output is the timeline of
 * what a fan saw. Exit status 2 means bad usage or an invalid settings file.
 */
public final class ProbeCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;

  private ProbeCli() {}
```

- The class comment: the probe records "how each one ended, while a script stops and starts a container". Exit status 2 means bad usage or an invalid settings file. There is no status 1: a probe that sees failures still exits 0, because seeing failures is its job.
- `EXIT_BAD_USAGE = 2` has package visibility for the test.

```java
  /**
   * Runs the probe.
   *
   * @param arguments the settings file, for example {@code config/probe.properties}
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static void main(String[] arguments) throws InterruptedException {
    System.exit(run(arguments, System.out, System.err));
  }
```

- `main` calls `run` and exits with its result.

```java
  /**
   * Runs the probe and returns the exit status.
   *
   * @param arguments the command line
   * @param out where the timeline is printed
   * @param err where problems are printed
   * @return 0, or 2 for bad usage
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) throws InterruptedException {
    if (arguments.length != 1) {
      err.println("usage: probe <probe.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<ProbeSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), ProbeSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    ProbeSettings settings = loaded.get();
```

- `run` needs exactly one argument. `SettingsFiles.load(path, ProbeSettings::from, err)` reads and checks the file, and prints the problems if there are any.

```java
    HttpClient client;
    try {
      client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .sslContext(TlsFiles.clientContext(settings.authorityPem()))
              .connectTimeout(settings.connectTimeout())
              .build();
    } catch (IOException unreadable) {
      err.println(unreadable.getMessage());
      return EXIT_BAD_USAGE;
    }
```

- The `HttpClient` is built with HTTP/1.1, the TEST authority's trust (`TlsFiles.clientContext`, which throws `IOException` for an unreadable certificate file) and the connect timeout. HTTP/1.1 keeps one connection open between requests.
- An unreadable certificate file is reported as `EXIT_BAD_USAGE` (2).

```java
    List<Outcome> outcomes;
    try (client) {
      outcomes = probe(client, settings);
    }
    out.printf(
        Locale.ROOT,
        "probe: GET %s every %d ms for %d s, one client, HTTP/1.1 keep-alive%n",
        settings.uri(),
        settings.interval().toMillis(),
        settings.duration().toSeconds());
    out.print(Timeline.render(outcomes));
    return 0;
  }
```

- `try (client) { outcomes = probe(client, settings); }` runs the loop and then closes the client.
- The first printed line says what ran: the address, the interval in milliseconds and the duration in seconds (`duration().toSeconds()`, which drops the fraction: a probe of 500 ms prints "for 0 s"). Then `Timeline.render(outcomes)` prints the spans and the outage line. The function returns 0.

```java
  /**
   * Sends one request per interval until the duration is over.
   *
   * @param client the one client, which keeps its connection open between requests
   * @param settings the target, the interval and the timeouts
   * @return every outcome, in the order sent
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  static List<Outcome> probe(HttpClient client, ProbeSettings settings)
      throws InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(settings.uri()).timeout(settings.requestTimeout()).GET().build();
    List<Outcome> outcomes = new ArrayList<>();
    long startNanos = System.nanoTime();
    long endNanos = startNanos + settings.duration().toNanos();
```

- `probe(client, settings)` is `static` and package-visible so that the test can call it. It builds the request once (`GET`, with the request timeout) and an empty list for the outcomes.
- `startNanos` is now, and `endNanos` is the end of the probe, both from `System.nanoTime()`, the clock that never jumps backwards.

```java
    for (long nextNanos = startNanos;
        nextNanos < endNanos;
        nextNanos += settings.interval().toNanos()) {
      long waitNanos = nextNanos - System.nanoTime();
      if (waitNanos > 0) {
        Thread.sleep(Duration.ofNanos(waitNanos));
      }
      long sentNanos = System.nanoTime();
      String result = send(client, request);
      outcomes.add(
          new Outcome(
              Duration.ofNanos(sentNanos - startNanos),
              result,
              Duration.ofNanos(System.nanoTime() - sentNanos)));
    }
    return outcomes;
  }
```

- The `for` loop's counter `nextNanos` is the planned send time. It starts at `startNanos` and grows by one interval at each turn while it is before `endNanos`. The loop runs `duration / interval` times, whatever happens inside.
- `waitNanos = nextNanos - System.nanoTime()` is how long until the planned time. If it is positive, `Thread.sleep(Duration.ofNanos(waitNanos))` waits. If it is zero or negative, the request is late and goes at once.
- `sentNanos` is the real send time. `send(client, request)` returns the result text. A new `Outcome` records the send time relative to the start, the text, and the elapsed time.

```java
  private static String send(HttpClient client, HttpRequest request) throws InterruptedException {
    try {
      return Integer.toString(
          client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    } catch (HttpConnectTimeoutException slowConnect) {
      return "connect timeout";
    } catch (HttpTimeoutException slowAnswer) {
      return "request timeout";
    } catch (ConnectException refused) {
      return "connection refused";
    } catch (IOException broken) {
      // Any other failure: the connection closed or reset under the request.
      return "connection lost";
    }
  }
```

- `send` returns the status code as text if the request completed: `client.send(request, BodyHandlers.discarding()).statusCode()`. `discarding()` throws the body away. `Integer.toString` makes the number into text.
- The four `catch` blocks map failures to texts, most specific first: `HttpConnectTimeoutException` (a kind of `HttpTimeoutException`) is "connect timeout"; `HttpTimeoutException` is "request timeout"; `ConnectException` is "connection refused"; any other `IOException` is "connection lost". If the more general `HttpTimeoutException` came first, the connect timeout would never be reached.
- A failed TLS handshake or an untrusted certificate is an `SSLException`, a kind of `IOException`, so it is reported as "connection lost". A TLS handshake that gets no answer is reported as "connect timeout". The labels name what the client could see, not always the real cause.
- `InterruptedException` is not caught: it is declared with `throws` and ends the probe.

## Try it yourself

All commands run in the repository folder. Numbers differ on every run and on every computer. The writer ran SeatWave on this machine; it did not run the Docker stack or `scripts/spof.sh`, so the outages below are made by hand, and the README's captured output stands for the real lab.

**1. Start SeatWave on this machine.** In one terminal run `./gradlew -q testCertificates` and then `./gradlew -q serve --args='config/backend-local.properties'`. The writer used a copy of that file with the ports 20980 to 20982, because other work on the machine used the default ones, so every address below says 20980 where you would write 20930.

**2. Make a short probe file.** Copy `config/probe.properties` to `build/probe-mine.properties` and change the address to `https://127.0.0.1:20930/health` and `probe.durationMillis` to `2000`. Run it:

```text
./gradlew -q probe --args='build/probe-mine.properties'
```

```text
probe: GET https://127.0.0.1:20980/health every 100 ms for 2 s, one client, HTTP/1.1 keep-alive
  0.000 s to   1.909 s  200                    x   20  latency p50     5.03 ms, max   193.15 ms
outage: none that ended inside the probe
```

Twenty requests, 2 s divided by 100 ms. The slowest (193 ms) is the first, which opened the connection and did the TLS handshake. The other 19 reused the connection.

**3. Make an outage by hand.** The server is paused (not stopped) with the signal `STOP` for three seconds, while an 8-second probe runs. In a third terminal, find the server's process id with `lsof -nP -iTCP:20930 -sTCP:LISTEN -t`, start the probe with `probe.durationMillis=8000` and, about 3 seconds later, run `kill -STOP <pid>` and 3 seconds after that `kill -CONT <pid>`. The writer ran the three steps from one command:

```text
(sleep 3; kill -STOP $PID; sleep 3; kill -CONT $PID) & ./gradlew -q probe --args='build/probe-stop.properties'; wait
```

```text
probe: GET https://127.0.0.1:20980/health every 100 ms for 8 s, one client, HTTP/1.1 keep-alive
  0.000 s to   2.304 s  200                    x   24  latency p50     4.25 ms, max   157.16 ms
  2.406 s to   2.406 s  request timeout        x    1  latency p50  1003.34 ms, max  1003.34 ms
  3.409 s to   5.035 s  connect timeout        x    9  latency p50   202.65 ms, max   204.49 ms
  5.237 s to   7.909 s  200                    x   46  latency p50     1.76 ms, max   166.50 ms
outage: 2.831 s from the first failed request to the first success after the last
```

Read it. The first request after the pause waited 1 s and timed out (`request timeout`, the open connection got no answer). The next ones could not finish a connection in 200 ms (`connect timeout`). The outage line says 2.831 s although the pause lasted 3 s, because it runs from the first failed request to the first success, using planned send times. The total is 24 + 1 + 9 + 46 = 80 requests, which is 8 s divided by 100 ms, even though the stretch after the outage has 46 answers in 2.7 s: the loop caught up.

**4. Probe a port where nothing listens.** Change the port to 20999:

```text
probe: GET https://127.0.0.1:20999/health every 100 ms for 2 s, one client, HTTP/1.1 keep-alive
  0.000 s to   1.902 s  connection refused     x   20  latency p50     4.44 ms, max    24.54 ms
outage: none that ended inside the probe
```

All 20 requests failed, yet the last line says no outage "ended". The text is true (service never came back) but easy to misread.

**5. Probe the plain HTTP port with HTTPS.** Port 20931 (20981 for the writer) speaks plain HTTP, so a TLS handshake never completes:

```text
probe: GET https://127.0.0.1:20981/health every 100 ms for 0 s, one client, HTTP/1.1 keep-alive
  0.000 s to   0.836 s  connect timeout        x    5  latency p50   203.76 ms, max   225.27 ms
outage: none that ended inside the probe
```

The writer set the probe's duration to 500 ms, and it was printed as "for 0 s", and the label says `connect timeout` although the TCP connection did succeed.

**6. Run the unit tests of this package.**

```text
./gradlew test --tests '*TimelineTest' --tests '*ProbeCliTest'
```

Stop SeatWave with Ctrl-C afterwards.

## Mistakes people make

- **Sending as fast as possible during an outage.** A probe that does not keep a schedule changes the load while it measures. This one sends a fixed number of requests; late ones are sent at once, which is a burst after a stall.
- **Reading only the failures.** The span lines also show the latency of the good requests. The first span's maximum (133 ms in the README's backend outage, against a median of 9.27 ms) is the first request, which opened the connection and did the TLS handshake.
- **Reading "none that ended inside the probe" as "no failures".** It also means "service never came back". Look at the span lines.
- **Trusting a label as a cause.** `connect timeout` can mean a stalled TLS handshake and `connection lost` can mean a rejected certificate.
- **A probe that is too gentle or too long.** One request each 100 ms puts the outage's resolution at 100 ms; and a connect timeout longer than the interval makes the probe stall for each lost connection.
- **Treating success as "any 2xx".** Only `200` counts. A health endpoint that answered `204` would be shown as down.

## Check yourself

1. How many requests does a 30-second probe with a 100 ms interval send, and why is it the same during an outage? (300; the loop is driven by the planned send times, so it always runs `duration / interval` times, catching up after slow requests.)
2. Why must `HttpConnectTimeoutException` be caught before `HttpTimeoutException`? (The first is a kind of the second. The first matching `catch` wins, so the general one would hide the specific one; the compiler also refuses the reverse order.)
3. What does the outage line measure? (The time from the first failed request's send time to the send time of the first success after the last failure.)
4. What are the four failure texts? (`connect timeout`, `request timeout`, `connection refused`, `connection lost`.)
5. Why does the probe exit 0 even when every request failed? (It records what happened; failures are the measurement. Only bad usage or settings give status 2.)

## Further reading

- Java SE 25 API: `java.net.http.HttpClient` and its exceptions `HttpTimeoutException` and `HttpConnectTimeoutException`, https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
- Java SE 25 API: `java.net.ConnectException`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/ConnectException.html
- Java Language Specification, the `try` statement and catch order (Java SE 25), https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.20
- RFC 9110, HTTP semantics, status code 200, https://www.rfc-editor.org/rfc/rfc9110#name-200-ok
