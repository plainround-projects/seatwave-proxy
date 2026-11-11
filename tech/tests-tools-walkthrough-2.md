# The tests of the tools, part 2: the probe, the log reader and the curl report

This is part 2 of 2. Part 1 (`tests-tools-walkthrough-1.md`) explained how a JUnit test and an AssertJ check work, and read the tests of the bench and the slow clients. Part 2 reads the tests of three more packages: `probe` (two files), `logs` (one file) and `report` (four files). The words `@Test`, `@TempDir`, `assertThat`, `assertThatThrownBy` and "text block" are explained in part 1; this part explains them again in short where it first uses them.

## What it is

The tests here check the outage probe, the access-log reader and Module 5's curl report. Most of them call a method with a text or a list of values and compare the answer with an exact expected value. Only one file, `ProbeCliTest`, starts a server: the probe's request loop needs something to ask.

## Why this repository uses it

The three tools turn raw observations into numbers that go into the module's text: the length of an outage, the share of cache hits, the dominant phase of a request. Each test below pins one rule of that arithmetic to an exact value. A quiet error in a rule (for example an off-by-one in a median) would change the numbers in the README without any error message, and a test is the only thing that can notice.

The tests of this part are 28 of the 119 in the README's count: `ProbeCliTest` 2, `TimelineTest` 4, `LogStatsTest` 4, `CurlLineTest` 7, `TimingReportTest` 4, `PhaseTimingsTest` 5 and `ReportCliTest` 2 (the writer's count from the Gradle result files). With the 24 tests of part 1, the five packages have 52.

## Where it appears

| File | What it checks |
|---|---|
| `probe/ProbeCliTest.java` | The request loop against a live and a dead address; the refusal of a bad settings file. |
| `probe/TimelineTest.java` | Grouping into spans, the outage length, the printed text, the guards of `Span` and `Outcome`. |
| `logs/LogStatsTest.java` | Parsing of log lines, the table of numbers per port, the command's exit statuses. |
| `report/CurlLineTest.java` | Turning a curl line into phases; the zero rule; every refusal. |
| `report/TimingReportTest.java` | The median run of a row, the table text, the guards. |
| `report/PhaseTimingsTest.java` | The invariant: no negative phase, phases add up; the tie rule of `dominant`. |
| `report/ReportCliTest.java` | The command: a good file, bad usage, a missing file, too few runs. |

## How it works underneath

**A test with no server.** Most tests here call a `static` method with an input and compare. They have no files, no network and no threads, so they are fast and cannot fail by chance. Their inputs are constants at the top of the class, so the reader sees the data first.

**Building test data.** A test of a parser needs lines. `CurlLineTest` keeps two real-looking curl lines as constants; `TimingReportTest` and `ReportCliTest` reuse them by changing one number with `String.replace`. This keeps the data in one place.

**Testing a command.** `ProbeCli.run`, `LogStatsCli.run` and `ReportCli.run` take the output streams as parameters and return the exit status. A test hands them in-memory streams and reads back what was printed. It never calls `System.exit`, which would end the test program.

**The weak spots of a test suite.** Line coverage says which lines ran, not whether they were checked. The section "What these tests do not run" below lists, from the coverage report, what no test touches.

**Java words new in this part.** `static` factory helper methods in a test class; a `switch` expression with record patterns used inside a helper (`parsed`, `problem`) to take a `Result` apart; `Collections.nCopies(3, x)` (a list of three copies of `x`); `String.repeat(200)`; `hasSizeBetween`, `allMatch`, `endsWith`, `contains` on texts and lists.

## Walkthrough of this repository's use

### `probe/ProbeCliTest.java`

```java
package io.plainround.seatwave.proxy.probe;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

- Imports: `TestBackend` (a SeatWave on a free port, from the backend tests), the in-memory streams, `URI`, the file helpers, `Duration`, `List`, and the JUnit annotations.

```java
class ProbeCliTest {

  @Test
  void recordsOneOutcomePerIntervalAndTellsSuccessFromFailure() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      ProbeSettings settings =
          new ProbeSettings(
              backend.plain("/health"),
              TestBackend.TLS.resolve("seatwave-test-ca.pem"),
              Duration.ofMillis(20),
              Duration.ofMillis(200),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1));
      ProbeSettings refused =
          new ProbeSettings(
              URI.create("http://127.0.0.1:1/health"),
              settings.authorityPem(),
              Duration.ofMillis(20),
              Duration.ofMillis(60),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1));

```

- The first test builds two sets of settings in code. `settings` points at the live backend's `/health` (plain HTTP), the TEST authority file, an interval of 20 ms, a duration of 200 ms, and 1-second timeouts. `refused` points at `http://127.0.0.1:1/health`: port 1 on this machine, where nothing normally listens, with a duration of 60 ms.
- `try (TestBackend backend = TestBackend.untrusting())` starts the server and stops it at the end of the block.

```java
      List<Outcome> ok = ProbeCli.probe(TestBackend.client(), settings);
      List<Outcome> down = ProbeCli.probe(TestBackend.client(), refused);

      assertThat(ok).hasSizeBetween(5, 11).allMatch(Outcome::isSuccess);
      assertThat(down)
          .isNotEmpty()
          .allMatch(outcome -> outcome.result().equals("connection refused"));
    }
  }
```

- `ProbeCli.probe(TestBackend.client(), settings)` runs the loop and returns the outcomes. For the live backend: `hasSizeBetween(5, 11)` and `allMatch(Outcome::isSuccess)`. For the dead address: at least one outcome (`isNotEmpty()`) and every result text equals `connection refused`.
- A 200 ms probe at a 20 ms interval always sends exactly 10 requests (the loop count depends on the planned send times only). The range 5 to 11 is much wider than needed.
- The test depends on port 1 being closed. On a machine that listens on port 1, the second check would fail.
- Only `connection refused` is checked. The texts `connect timeout`, `request timeout` and `connection lost` are produced by lines that no test runs.

```java
  @Test
  void refusesAnAddressOutsideTheLabAndBadUsage(@TempDir Path folder) throws Exception {
    Path file =
        Files.writeString(
            folder.resolve("probe.properties"),
            Files.readString(Path.of("config/probe.properties"), StandardCharsets.UTF_8)
                .replace("https://127.0.0.1:20900/health", "https://seatwave.example/health"),
            StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
    PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

```

- The second test reads the real `config/probe.properties`, replaces the address `https://127.0.0.1:20900/health` with `https://seatwave.example/health` (outside the lab), and writes the result to a temporary file. `err` is a byte buffer for the error output; `out` swallows the normal output.

```java
    assertThat(ProbeCli.run(new String[] {file.toString()}, out, errors))
        .isEqualTo(ProbeCli.EXIT_BAD_USAGE);
    assertThat(ProbeCli.run(new String[0], out, errors)).isEqualTo(ProbeCli.EXIT_BAD_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("probe.uri must match", "usage: probe");
  }
}
```

- `ProbeCli.run` with the bad file and with no arguments must both return `EXIT_BAD_USAGE`. The error text must contain `probe.uri must match` and `usage: probe`.
- If the address text in `config/probe.properties` changes, the `replace` does nothing and the file stays valid. The test would then start a 30-second probe against the real stack address and fail on the status, not on a clear message.

### `probe/TimelineTest.java`

```java
package io.plainround.seatwave.proxy.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
```

- Imports for `Duration`, `List`, and AssertJ.

```java
class TimelineTest {

  private static final List<Outcome> OUTAGE =
      List.of(
          outcome(0, "200", 3),
          outcome(100, "200", 4),
          outcome(200, "connection refused", 1),
          outcome(300, "connection refused", 2),
          outcome(400, "200", 9));
```

- `OUTAGE` is a small made-up history: two `200` at 0 ms and 100 ms, two `connection refused` at 200 ms and 300 ms, one `200` at 400 ms. The helper `outcome(sentAtMillis, result, latencyMillis)` (at the end of the file) builds them.

```java
  @Test
  void groupsEqualResultsIntoSpansInOrder() {
    List<Timeline.Span> spans = Timeline.spans(OUTAGE);

    assertThat(spans).hasSize(3);
    assertThat(spans.get(1).outcomes()).hasSize(2).allMatch(each -> !each.isSuccess());
  }
```

- `Timeline.spans(OUTAGE)` must give 3 spans. The middle span has 2 outcomes, and none of them is a success.

```java
  @Test
  void measuresTheOutageFromTheFirstFailureToTheFirstSuccessAfterTheLast() {
    assertThat(Timeline.outage(OUTAGE)).contains(Duration.ofMillis(200));
    assertThat(Timeline.outage(List.of(outcome(0, "200", 1)))).isEmpty();
    assertThat(Timeline.outage(List.of(outcome(0, "200", 1), outcome(100, "502", 1)))).isEmpty();
  }
```

- The outage of `OUTAGE`: first failure at 200 ms, first success after the last failure at 400 ms: 200 ms.
- One success only: no outage (empty). A success then a failure (`502`) at the end: also empty, because service never came back inside the probe.

```java
  @Test
  void writesOneLinePerSpanAndTheOutage() {
    String text = Timeline.render(OUTAGE);

    assertThat(text)
        .contains(
            "  0.000 s to   0.100 s  200                    x    2  latency p50     3.00 ms, max     4.00 ms")
        .contains("connection refused     x    2")
        .endsWith(
            "outage: 0.200 s from the first failed request to the first success after the last"
                + System.lineSeparator());
    assertThat(Timeline.render(List.of(outcome(0, "200", 1)))).contains("outage: none");
  }
```

- `render` must contain the exact first line: `  0.000 s to   0.100 s  200                    x    2  latency p50     3.00 ms, max     4.00 ms`. The two `200` outcomes have latencies 3 and 4 ms: the median of two is the lower one, 3, and the maximum is 4.
- It must contain `connection refused     x    2`, and end with the outage line `outage: 0.200 s from the first failed request to the first success after the last` plus the system's line break.
- For a history with no failure, the text must contain `outage: none`.

```java
  @Test
  void refusesAnEmptyOrMixedSpanAndAnOutcomeWithoutAResult() {
    assertThatThrownBy(() -> new Timeline.Span(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Timeline.Span(OUTAGE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> outcome(0, " ", 1)).isInstanceOf(IllegalArgumentException.class);
  }
```

- Three guards: a `Span` with no outcomes is refused; a `Span` built from `OUTAGE` (mixed results) is refused; an `Outcome` with a blank result is refused. Each throws `IllegalArgumentException`.

```java
  private static Outcome outcome(long sentAtMillis, String result, long latencyMillis) {
    return new Outcome(Duration.ofMillis(sentAtMillis), result, Duration.ofMillis(latencyMillis));
  }
}
```

- The helper `outcome` makes an `Outcome` from three numbers and a text.

### `logs/LogStatsTest.java`

```java
package io.plainround.seatwave.proxy.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

- Imports for in-memory output, files, `Duration`, `List` and the JUnit and AssertJ pieces.

```java
class LogStatsTest {

  static final String MISS =
      "time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=200 rt=0.004 urt=0.001 uct=0.000"
          + " cache=MISS bytes=300473 id=cdcc client=10.209.9.1 method=GET uri=/static/poster-arena.bin";
  static final String HIT =
      "time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=- rt=0.000 urt=- uct=- cache=HIT"
          + " bytes=300463 id=ea17 client=10.209.9.1 method=GET uri=/static/poster-arena.bin";
  static final String BAD_GATEWAY =
      "time=2026-11-11T04:22:47+00:00 port=8447 status=502 us=502, 502 rt=0.003 urt=0.001, 0.001"
          + " uct=- cache=- bytes=335 id=23dd client=10.209.9.1 method=GET uri=/lab/client";
```

- Three real-looking log lines as constants. `MISS` is a cache miss for the poster on port 8443: `us=200`, request time 4 ms, upstream time 1 ms. `HIT` is a cache hit: `us=-`, `urt=-`, request time 0. `BAD_GATEWAY` is a request on port 8447 that was tried twice and answered `502` twice (`us=502, 502`, `urt=0.001, 0.001`), with no cache (`cache=-`).
- The strings are written in two pieces joined with `+`, to fit the line width.

```java
  @Test
  void readsALineAndSplitsTheProxysTimeFromSeatWaves() {
    AccessLine line = AccessLine.parse(MISS).discardError().orElseThrow();

    assertThat(line.port()).isEqualTo(8443);
    assertThat(line.cacheStatus()).isEqualTo("MISS");
    assertThat(line.proxyAdded()).isEqualTo(Duration.ofMillis(3));
    assertThat(AccessLine.parse(HIT).discardError().orElseThrow().askedUpstream()).isFalse();
    assertThat(AccessLine.parse(BAD_GATEWAY).discardError().orElseThrow().upstreamTime())
        .isEqualTo(Duration.ofMillis(2));
  }
```

- `AccessLine.parse(MISS).discardError().orElseThrow()` gets the line or fails the test. The checks: port 8443; cache status `MISS`; proxy-added time 3 ms (4 minus 1); the hit line did not ask SeatWave; the retried line has an upstream time of 2 ms (the sum of 1 ms and 1 ms).

```java
  @Test
  void refusesALineWithoutItsFields() {
    assertThat(AccessLine.parse("port=8443 status=200").isError()).isTrue();
    assertThat(AccessLine.parse("port=8443 uri=/").isError()).isTrue();
    assertThat(AccessLine.parse(MISS.replace("rt=0.004", "rt=fast")).isError()).isTrue();
  }
```

- Three refusals: a line with no `uri=`; a line with a `uri=` but no other fields; a line where `rt=fast` is not a number. Each result `isError()`.

```java
  @Test
  void writesHitRatioUpstreamErrorsAndProxyAddedTimePerPort() {
    List<AccessLine> lines =
        List.of(MISS, HIT, BAD_GATEWAY).stream()
            .map(text -> AccessLine.parse(text).discardError().orElseThrow())
            .toList();

    String table = LogStats.render(lines);

    assertThat(table)
        .contains(
            "8443          2            1            0.00              2   50.0          50.0                3.00")
        .contains(
            "8447          1            1          100.00              0      -             -                1.00");
  }
```

- The three lines are parsed and rendered. The text must contain the exact row of port 8443: 2 requests, 1 asked, `0.00` percent 5xx, 2 lookups, `50.0` hit percent, `50.0` static hit percent, `3.00` proxy-added p50 (then the p99 column, which is also 3.00 and is not part of the matched text). It must contain the row of port 8447: 1 request, 1 asked, `100.00` percent 5xx, no lookups (`-`), `1.00` ms added (`0.003 - 0.002`).
- The expected rows are long strings with exact spaces: they check the column widths of the format.

```java
  @Test
  void printsTheTableAndCountsUnreadableLines(@TempDir Path folder) throws Exception {
    Path log =
        Files.writeString(
            folder.resolve("access.log"), MISS + "\n" + "garbage\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);

    int status = LogStatsCli.run(new String[] {log.toString()}, printer, printer);
    int missing =
        LogStatsCli.run(new String[] {folder.resolve("none.log").toString()}, printer, printer);
    int usage = LogStatsCli.run(new String[0], printer, printer);

    assertThat(status).isEqualTo(LogStatsCli.EXIT_UNREADABLE_LINES);
    assertThat(missing).isEqualTo(LogStatsCli.EXIT_BAD_USAGE);
    assertThat(usage).isEqualTo(LogStatsCli.EXIT_BAD_USAGE);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(LogStats.HEADER, "1 line(s) could not be read");
  }
}
```

- The last test writes a log file of one good line and the text `garbage`, runs `LogStatsCli.run` on it, on a file that does not exist, and with no arguments. The statuses must be 1 (some lines unreadable), 2 (unreadable file) and 2 (usage). The output must contain the table header and `1 line(s) could not be read`.
- All three runs write to the same buffer.

### `report/CurlLineTest.java`

```java
package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CurlLineTest {

  static final String NEW_HTTPS =
      "seatwave-https dns=0.000080 connect=0.000247 tls=0.005957 ttfb=0.007095 total=0.008013"
          + " http=1.1 code=200 size=36894 connects=1 server=app;dur=0.26";
  static final String REUSED_HTTPS =
      "seatwave-https dns=0.000010 connect=0.000000 tls=0.000000 ttfb=0.000776 total=0.001610"
          + " http=1.1 code=200 size=36894 connects=0 server=app;dur=0.25";
```

- `NEW_HTTPS` is a curl line for a new HTTPS connection; `REUSED_HTTPS` is one for a reused connection (`connect=0.000000 tls=0.000000`, `connects=0`). The pieces are joined with `+`. They are `static final` and not private, so other test classes (`TimingReportTest`, `ReportCliTest`) use them.

```java
  @Test
  void turnsCumulativeReadingsIntoFivePhases() {
    CurlSample sample = parsed(NEW_HTTPS);

    PhaseTimings phases = sample.phases();
    assertThat(phases.dns()).isEqualTo(Duration.ofNanos(80_000));
    assertThat(phases.connect()).isEqualTo(Duration.ofNanos(167_000));
    assertThat(phases.tls()).isEqualTo(Duration.ofNanos(5_710_000));
    assertThat(phases.firstByte()).isEqualTo(Duration.ofNanos(1_138_000));
    assertThat(phases.transfer()).isEqualTo(Duration.ofNanos(918_000));
    assertThat(phases.total()).isEqualTo(Duration.ofNanos(8_013_000));
    assertThat(phases.dominant()).isEqualTo(Phase.TLS);
    assertThat(sample.kind()).isEqualTo(ConnectionKind.NEW);
    assertThat(sample.protocol()).isEqualTo("HTTP/1.1");
    assertThat(sample.serverTiming()).isEqualTo("app;dur=0.26");
  }
```

- The first test turns the cumulative readings into five phases and checks each in nanoseconds: dns 80 microseconds; connect `0.000247 - 0.000080 = 167` microseconds; tls `0.005957 - 0.000247 = 5,710` microseconds; first byte `0.007095 - 0.005957 = 1,138` microseconds; transfer `0.008013 - 0.007095 = 918` microseconds; total 8,013 microseconds.
- The dominant phase is `Phase.TLS`; the kind is `NEW`; the protocol text is `HTTP/1.1`; the server timing text is `app;dur=0.26`.
- `parsed(NEW_HTTPS)` (a helper at the end of the file) returns the sample or fails the test with the problem's text.

```java
  @Test
  void readsZeroHandshakeReadingsOfAReusedConnectionAsSkippedPhases() {
    CurlSample sample = parsed(REUSED_HTTPS);

    assertThat(sample.kind()).isEqualTo(ConnectionKind.REUSED);
    assertThat(sample.phases().connect()).isZero();
    assertThat(sample.phases().tls()).isZero();
    assertThat(sample.phases().firstByte()).isEqualTo(Duration.ofNanos(766_000));
  }
```

- The reused line: kind `REUSED`, connect and tls phases zero (`isZero()`), first byte `0.000776 - 0.000010 = 766` microseconds. This pins the "zero means did not happen" rule.

```java
  @Test
  void keepsAServerTimingHeaderWithSpaces() {
    String line =
        "example-com dns=0.002 connect=0.020 tls=0.045 ttfb=0.071 total=0.072 http=2 code=200"
            + " size=528 connects=1 server=cdn-cache; desc=HIT, edge; dur=1";

    assertThat(parsed(line).serverTiming()).isEqualTo("cdn-cache; desc=HIT, edge; dur=1");
    assertThat(parsed(line).protocol()).isEqualTo("HTTP/2");
  }
```

- A server-timing text with spaces and commas (`cdn-cache; desc=HIT, edge; dur=1`) is kept whole, because `server=` is the last field and takes the rest of the line. The same line has `http=2`, so the protocol text is `HTTP/2`.

```java
  @Test
  void rejectsReadingsThatAreOutOfOrder() {
    // The first byte cannot come before the TLS handshake ended: a phase would be negative.
    String line = NEW_HTTPS.replace("ttfb=0.007095", "ttfb=0.001000");

    assertThat(problem(line)).isInstanceOf(CurlProblem.Rejected.class);
    assertThat(problem(line).describe()).contains("a phase is negative");
  }
```

- A line whose first-byte reading (`0.001000`) is before the TLS reading gives a negative phase. The result must be a `CurlProblem.Rejected` whose text contains `a phase is negative`. `isInstanceOf(CurlProblem.Rejected.class)` checks the type.

```java
  @Test
  void rejectsAFailedOrUnsuccessfulRequest() {
    assertThat(problem(NEW_HTTPS.replace("code=200", "code=404")).describe())
        .contains("status 404 is not 2xx");
    assertThat(problem(NEW_HTTPS.replace("http=1.1", "http=0")).describe())
        .contains("the request failed");
    assertThat(problem(NEW_HTTPS.replace("size=36894", "size=-1")).describe())
        .contains("the size is negative");
  }
```

- Three rejections: status 404 gives `status 404 is not 2xx`; `http=0` (no HTTP version, as when curl fails) gives `the request failed`; `size=-1` gives `the size is negative`. The test changes one field of `NEW_HTTPS` with `replace` each time.

```java
  @Test
  void refusesMalformedLines() {
    assertThat(problem(NEW_HTTPS.replace(" server=app;dur=0.26", "")).describe())
        .contains("no server= field");
    assertThat(problem(NEW_HTTPS.replace("code=200", "code200")).describe())
        .contains("invalid or repeated field code200");
    assertThat(problem(NEW_HTTPS.replace("seatwave-https", "SeatWave")).describe())
        .contains("invalid target name");
    assertThat(problem(NEW_HTTPS.replace(" connects=1", "")).describe())
        .contains("no connects= field");
    assertThat(problem(NEW_HTTPS.replace("total=0.008013", "total=fast")).describe())
        .contains("a number is invalid");
    assertThat(problem(NEW_HTTPS.replace("dns=0.000080", "dns=0.000080 dns=1")).describe())
        .contains("invalid or repeated field dns=1");
  }
```

- Six malformed lines, each with its message: no `server=` field; a word `code200` with no `=`; an upper-case target name `SeatWave`; a missing `connects=` field; `total=fast` (not a number); and a repeated `dns=` key.
- The `describe()` text of a `CurlProblem` is what the report prints after `refused: `.

```java
  @Test
  void cutsALongMalformedLineInTheReport() {
    String longLine = "x".repeat(200);

    assertThat(problem(longLine).describe()).hasSizeLessThan(140);
  }
```

- A line of 200 `x` characters is malformed (no `server=`), and its description is shorter than 140 characters: the line is cut to 80 characters in the message, not echoed whole. `"x".repeat(200)` makes the text.

```java
  static CurlSample parsed(String line) {
    return switch (CurlLine.parse(line)) {
      case Result.Success<CurlSample, CurlProblem>(CurlSample sample) -> sample;
      case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) ->
          throw new AssertionError(problem.describe());
    };
  }
```

- `parsed(line)` takes the `Result` apart with a `switch`. A success returns the sample; an error throws `AssertionError` with the problem's text, which fails the test with a readable message.

```java
  private static CurlProblem problem(String line) {
    return switch (CurlLine.parse(line)) {
      case Result.Success<CurlSample, CurlProblem>(CurlSample sample) ->
          throw new AssertionError("parsed, but should not: " + sample);
      case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) -> problem;
    };
  }
}
```

- `problem(line)` is the mirror image: it returns the problem, and fails the test if the line parsed.

### `report/TimingReportTest.java`

```java
package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
```

- Imports for `Duration`, `List` and AssertJ.

```java
class TimingReportTest {

  @Test
  void reportsTheMedianRunWhosePhasesStillSumToItsTotal() {
    List<CurlSample> runs =
        List.of(run("0.008013"), run("0.040919"), run("0.006390"), run("0.007500"));

    TargetTiming row = new TargetTiming("seatwave-https", ConnectionKind.NEW, runs);

    // Four runs: the lower of the two middle ones, a run that was really measured.
    assertThat(row.medianRun().phases().total()).isEqualTo(Duration.ofNanos(7_500_000));
    assertThat(row.fastest()).isEqualTo(Duration.ofNanos(6_390_000));
    assertThat(row.slowest()).isEqualTo(Duration.ofNanos(40_919_000));
  }
```

- Four runs with totals 8.013, 40.919, 6.390 and 7.500 ms. Sorted: 6.390, 7.500, 8.013, 40.919. The median index of four is `(4 - 1) / 2 = 1`, so the median run is 7.500 ms, "the lower of the two middle ones, a run that was really measured" (the test's comment). The fastest is 6.390 ms and the slowest 40.919 ms.
- The helper `run(total)` (at the end) uses `NEW_HTTPS` with a different total and a fixed first-byte reading of 0.006000.

```java
  @Test
  void refusesARowWithTooFewRunsOrARunOfAnotherTarget() {
    assertThatThrownBy(
            () -> new TargetTiming("seatwave-https", ConnectionKind.NEW, List.of(run("0.008013"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a timing needs at least 3 runs, but had 1");
    List<CurlSample> mixed = List.of(run("0.008013"), run("0.008013"), run("0.008013"));
    assertThatThrownBy(() -> new TargetTiming("seatwave-https", ConnectionKind.REUSED, mixed))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- A row of one run: `IllegalArgumentException` with the exact message `a timing needs at least 3 runs, but had 1`. A row declared `REUSED` but built from three `NEW` runs: also refused.

```java
  @Test
  void printsEveryRowWithItsSentenceAndListsRefusedGroupsAndLines() {
    List<CurlSample> samples =
        List.of(
            run("0.008013"),
            run("0.006390"),
            run("0.007500"),
            CurlLineTest.parsed(CurlLineTest.REUSED_HTTPS));
    List<CurlProblem> problems = List.of(new CurlProblem.Rejected("example-com", "status 404"));

    String table = TimingReport.renderPhases(samples, problems);

    assertThat(table)
        .contains("seatwave-https    new        HTTP/1.1     3")
        .contains("seatwave-https, new connection: tls dominates the median run")
        .contains("refused: too few runs for seatwave-https over a reused connection: 1")
        .contains("refused: rejected run of example-com: status 404");
  }
```

- Three `NEW` runs plus one `REUSED` line (from the reused constant), and one problem object. The table must show the row `seatwave-https    new        HTTP/1.1     3`, the sentence `seatwave-https, new connection: tls dominates the median run`, a refused group `too few runs for seatwave-https over a reused connection: 1`, and the refused line `rejected run of example-com: status 404`.

```java
  @Test
  void refusesASampleThatIsNotA2xxTiming() {
    PhaseTimings phases = CurlLineTest.parsed(CurlLineTest.NEW_HTTPS).phases();

    assertThatThrownBy(
            () -> new CurlSample("x", "HTTP/1.1", ConnectionKind.NEW, 500, 1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CurlSample("Bad Name", "HTTP/1.1", ConnectionKind.NEW, 200, 1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CurlSample("x", "HTTP/1.1", ConnectionKind.NEW, 200, -1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- Three bad `CurlSample` values, each refused: status 500; a target name `Bad Name`; a size of -1. The phases come from a valid line.

```java
  private static CurlSample run(String totalSeconds) {
    return CurlLineTest.parsed(
        CurlLineTest.NEW_HTTPS
            .replace("total=0.008013", "total=" + totalSeconds)
            .replace("ttfb=0.007095", "ttfb=0.006000"));
  }
}
```

- The helper `run` builds a sample from `NEW_HTTPS` with the total replaced and the first-byte reading replaced by `0.006000`, so every total from 6.390 ms upward gives non-negative phases.

### `report/PhaseTimingsTest.java`

```java
package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import org.junit.jupiter.api.Test;
```

- Imports include `Result` to look at the success or error kind.

```java
class PhaseTimingsTest {

  private static final Duration TEN = Duration.ofMillis(10);
  private static final Duration ZERO = Duration.ZERO;
```

- Two constants: `TEN` is 10 ms; `ZERO` is zero.

```java
  @Test
  void acceptsPhasesThatSumToTheTotal() {
    PhaseTimings timings = new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(50));

    assertThat(timings.of(Phase.TRANSFER)).isEqualTo(TEN);
  }
```

- Five phases of 10 ms and a total of 50 ms are accepted. `timings.of(Phase.TRANSFER)` returns 10 ms.

```java
  @Test
  void refusesPhasesThatDoNotSumToTheTotal() {
    Result<PhaseTimings, String> refused =
        PhaseTimings.of(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52));

    assertThat(((Result.Error<PhaseTimings, String>) refused).error())
        .isEqualTo("the phases sum to 50000000 ns, but the total is 52000000 ns");
    assertThatThrownBy(() -> new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52)))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- A total of 52 ms for phases that sum to 50 is refused: the `of` factory returns an error whose text is exactly `the phases sum to 50000000 ns, but the total is 52000000 ns`, and the constructor throws `IllegalArgumentException`.

```java
  @Test
  void allowsAGapUpToTheTolerance() {
    Duration total = Duration.ofMillis(50).plus(PhaseTimings.SUM_TOLERANCE);

    assertThat(PhaseTimings.of(TEN, TEN, TEN, TEN, TEN, total)).isInstanceOf(Result.Success.class);
  }
```

- A gap equal to `SUM_TOLERANCE` (1 ms) is allowed: the total is 51 ms. The result is a `Result.Success`.

```java
  @Test
  void refusesANegativePhaseEvenWhenTheSumIsRight() {
    Duration minusTen = TEN.negated();

    // 10 - 10 + 10 + 10 + 0 = 20, so the total is right and only the negative phase is wrong.
    assertThat(PhaseTimings.of(TEN, minusTen, TEN, TEN, ZERO, TEN.plus(TEN)))
        .isInstanceOf(Result.Error.class);
  }
```

- A negative phase (-10 ms) is refused even if the sum is right: the phases are 10, -10, 10, 10 and 0, which add up to 20, and the total is 20 ms. The comment in the test shows the arithmetic. This pins the rule that the sum check cannot hide a negative phase.

```java
  @Test
  void namesTheLongestPhaseAndKeepsTheEarlierOneOnATie() {
    PhaseTimings tie = new PhaseTimings(ZERO, TEN, TEN, ZERO, ZERO, Duration.ofMillis(20));
    PhaseTimings allZero = new PhaseTimings(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);

    assertThat(tie.dominant()).isEqualTo(Phase.CONNECT);
    assertThat(allZero.dominant()).isEqualTo(Phase.DNS);
    assertThat(Phase.FIRST_BYTE.label()).isEqualTo("first byte");
  }
}
```

- `dominant()` with two equal longest phases (`connect` and `tls`, 10 ms each) gives the earlier one, `CONNECT`. All zeros gives `DNS`, the first. `Phase.FIRST_BYTE.label()` is `first byte`.

### `report/ReportCliTest.java`

```java
package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

- Imports include `Collections` for `nCopies`.

```java
class ReportCliTest {

  @Test
  void printsTheTableOfAFileOfCurlLines(@TempDir Path folder) throws Exception {
    Path lines =
        Files.write(
            folder.resolve("timings.txt"),
            Collections.nCopies(3, CurlLineTest.NEW_HTTPS),
            StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);

    int status = ReportCli.run(new String[] {lines.toString()}, printer, printer);

    assertThat(status).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("seatwave-https    new        HTTP/1.1     3");
  }
```

- A file of three copies of `NEW_HTTPS` (`Collections.nCopies(3, ...)`) is written to a temporary folder. `ReportCli.run` returns 0 and prints the row `seatwave-https    new        HTTP/1.1     3`.

```java
  @Test
  void refusesBadUsageAMissingFileAndTooFewRuns(@TempDir Path folder) throws Exception {
    Path one =
        Files.writeString(
            folder.resolve("one.txt"),
            "# a comment\n" + CurlLineTest.NEW_HTTPS + "\n",
            StandardCharsets.UTF_8);
    PrintStream printer =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    assertThat(ReportCli.run(new String[0], printer, printer)).isEqualTo(ReportCli.EXIT_BAD_USAGE);
    assertThat(ReportCli.run(new String[] {folder.resolve("none").toString()}, printer, printer))
        .isEqualTo(ReportCli.EXIT_BAD_USAGE);
    assertThat(ReportCli.run(new String[] {one.toString()}, printer, printer))
        .isEqualTo(ReportCli.EXIT_REFUSED_LINES);
  }
}
```

- A file with a comment line (`# a comment`) and one good line. The command is called three times: with no arguments (status 2), with a file that does not exist (status 2), and with the one-line file (status 1: a group of one run is refused). `ReportCli` skips the comment line. The test does not prove that, because the status is 1 whether the comment is skipped or counted as a malformed line.
- The output of all three goes into a stream that the test throws away. The messages themselves are not checked, only the statuses.

### What these tests do not run

From the JaCoCo report of the writer's run (lines that no test executes at all):

| File | Lines | What they are |
|---|---|---|
| `probe/ProbeCli.java` | 39, 40 | `main`. |
| | 61, 65 to 73, 75, 76, 78, 81 to 85 | The successful `run`: building the client, running the probe, printing the header line and the timeline. |
| | 125 to 128 | The `catch` blocks for `HttpConnectTimeoutException` and `HttpTimeoutException`: the texts `connect timeout` and `request timeout`. |
| | 131, 133 | The `catch` block for `IOException`: the text `connection lost`. |
| `probe/ProbeSettings.java` | 61, 63 | The successful `from`. |
| `logs/LogStatsCli.java` | 31, 32, 65 | `main` and the `return 0` of a clean log. |
| `logs/LogStats.java` | 68, 83 | The `-` for a port with no asked requests, and the empty-list guard of `percentile`. |
| `report/CurlLine.java` | 79 | The `HTTP/3` case. |
| `report/Runs.java` | 48 | `median(List<Duration>)`. |
| `report/ReportCli.java` | 31, 32, 57, 58 | `main`, and the line that adds a problem to the list. |

Two of these matter. The texts of three of the probe's four outcomes are not tested, yet the README's lab output (`request timeout`, `connection lost`, `connection refused`) depends on them. And `ReportCli.run` never meets a line that `CurlLine` refuses, so the path from a refused line to the printed `refused:` text is untested at the command level. `TimingReport` does test the text for a given problem.

## Try it yourself

All commands run in the repository folder. Times differ on every run. None needs Docker.

**1. Run this part's tests.**

```text
./gradlew test --tests 'io.plainround.seatwave.proxy.probe.*' --tests 'io.plainround.seatwave.proxy.logs.*' --tests 'io.plainround.seatwave.proxy.report.*'
```

`BUILD SUCCESSFUL`. 28 tests.

**2. Break a rule and watch the right test notice.** In a copy of the repository, open `src/main/java/io/plainround/seatwave/proxy/report/Runs.java` and change `(sorted.size() - 1) / 2` to `sorted.size() / 2`. Run `./gradlew test --tests '*TimingReportTest'`. Gradle prints `TimingReportTest > reportsTheMedianRunWhosePhasesStillSumToItsTotal() FAILED`, `org.opentest4j.AssertionFailedError at TimingReportTest.java:20` and `4 tests completed, 1 failed`: for four runs, the median index becomes 2 (8.013 ms) instead of 1 (7.500 ms). Change it back.

**3. See the lines no test runs.** Run `./gradlew check`, open `build/reports/jacoco/test/html/io.plainround.seatwave.proxy.probe/ProbeCli.java.html` in a browser, and look at the red lines in `send`.

**4. Check the tests are not flaky.** Run the probe tests five times in a row:

```text
for i in 1 2 3 4 5; do ./gradlew test --rerun --tests '*ProbeCliTest' --tests '*TimelineTest' -q || break; done
```

With `-q` (quiet), Gradle prints nothing when the tests pass, so no output means every run passed. The writer's five runs printed nothing. (`--rerun` makes Gradle run the tests again even if nothing changed.)

## Mistakes people make

- **Expecting one test to cover a whole tool.** The probe's texts for timeouts are untested while its refusal text is. Read the coverage report, then read the tests.
- **Using a wide range as an assertion.** `hasSizeBetween(5, 11)` accepts a loop that is badly wrong. The loop sends exactly 10.
- **Depending on an unused port.** `127.0.0.1:1` is closed on a normal computer, not on every computer.
- **Replacing text in a file you do not own.** `refusesAnAddressOutsideTheLabAndBadUsage` replaces the address in `config/probe.properties`. If the address changes, the test stops proving what its name says.
- **Comparing floating-point numbers with `isEqualTo`.** The tests here compare texts and `Duration` values, which are exact. A `double` such as `0.1 + 0.2` is not `0.3`.
- **Using the same data everywhere.** Several tests reuse `NEW_HTTPS`. A mistake in that constant would show in many tests at once, which is useful; but it also means that all of them test the same shape of line.

## Check yourself

1. Why does `ProbeCliTest` call `ProbeCli.probe` and not `ProbeCli.main`? (`main` ends the program with `System.exit`; `probe` and `run` return values a test can check.)
2. For the history `200, 200, refused, refused, 200` at 0 to 400 ms, what is the outage and why? (200 ms: from the first failure at 200 ms to the first success after the last failure at 400 ms.)
3. In `LogStatsTest`, why does the retried line have an upstream time of 2 ms? (The log lists one time per attempt, 0.001 and 0.001, and the parser adds them.)
4. Which rule does `refusesANegativePhaseEvenWhenTheSumIsRight` protect? (A negative phase is refused even when the phases still add up to the total.)
5. Name a text the probe can print that no test checks. (`connect timeout`, `request timeout` or `connection lost`.)

## Further reading

- JUnit documentation (this repository pins JUnit 6.1.3), https://docs.junit.org/
- AssertJ Core 3.27.7 documentation, https://assertj.github.io/doc/
- JaCoCo 0.8.16 documentation: counters and coverage, https://www.jacoco.org/jacoco/trunk/doc/counters.html
- Java SE 25 API: `java.util.Collections#nCopies`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Collections.html#nCopies(int,T)
