# The tests of the tools, part 1: the bench and the slow clients

This is part 1 of 2. It reads the tests of the `bench` package (six files) and the `slow` package (one file). Part 2 (`tests-tools-walkthrough-2.md`) reads the tests of `probe`, `logs` and `report`.

## What it is

A **unit test** is a small program that calls a piece of your code with chosen inputs and checks that the answer is the one you expect. If the answer is different, the test fails and says so. The repository runs its tests with **JUnit 6.1.3**, the test engine, and checks answers with **AssertJ 3.27.7**, a library of readable checks such as `assertThat(x).isEqualTo(y)`. Gradle runs them all with `./gradlew test` (or `./gradlew check`, which also formats the code and measures coverage).

The tests in this part check the load generator, its settings, the report table, the parser of container counters and the slow-client program. Some start a real SeatWave server on the loopback address and send real requests to it. Others call a method with a text and look at the answer.

## Why this repository uses it

The course rule is that a number printed by a tool must be trustworthy. A tool that measures a proxy can be wrong in many quiet ways: a percentile off by one, a byte count that includes the chunk framing, a median taken the wrong way. Each test below pins one such rule to an exact value, so a change that breaks the rule turns the build red. The tests also make the tools safe to change without the Docker stack: `./gradlew check` needs no Docker.

The README's result is "119 tests, 0 failures, line coverage 91.8 percent (1,937 of 2,111 lines)". The writer of these notes ran the whole suite on a copy of the repository and got the same two numbers: 119 tests and 1,937 of 2,111 lines. The tests of this part are 24 of the 119: `ContainerCountersTest` 5, `BenchCliTest` 2, `LoadRunTest` 5, `BenchSettingsTest` 2, `RawResponseTest` 3, `BenchReportTest` 2, `SlowClientsTest` 5 (the writer's count from the Gradle result files).

## Where it appears

| File | What it checks |
|---|---|
| `bench/ContainerCountersTest.java` | Parsing of `cpu.stat` and `/proc/net/snmp`; running a (fake) `docker`; counter arithmetic. |
| `bench/BenchCliTest.java` | The exit statuses and messages of the bench command. |
| `bench/LoadRunTest.java` | The load generator against a real SeatWave: reused connections, new connections, errors; the percentile rule; refused inputs. |
| `bench/BenchSettingsTest.java` | The real `config/bench.properties`, and refusal of a bad copy. |
| `bench/RawResponseTest.java` | Chunked and gzip bodies, a body ended by the connection, cut-off answers. |
| `bench/BenchReportTest.java` | The median run and the printed row; the refusal of fewer than three runs. |
| `slow/SlowClientsTest.java` | Slow clients against a real SeatWave; failed downloads; the real settings files; the printed result. |

Another test file, `backend/TestBackend.java`, is a helper that belongs to the backend tests (see `tests-backend-walkthrough.md`). These tests use five things from it: `TestBackend.untrusting()` starts a SeatWave on `127.0.0.1` on ports the system picks (8 worker threads, a queue of 64, quick slow routes) that trusts no forwarded address of this machine; `plain(path)`, `tls(path)` and `admin(path)` give addresses on its three listeners; `metrics()` reads its operations text; `TestBackend.TLS` is the folder with the TEST certificates; and `close()` stops it. A `TestBackend` is `AutoCloseable`, so `try (TestBackend backend = ...)` stops it at the end of the block.

## How it works underneath

**A test class and a test method.** A test class is an ordinary class. A method marked `@Test` is a test: JUnit finds it, makes a **new object of the class for each test method**, runs the method, and records whether it threw an exception. An `AssertionError` (thrown by a failed AssertJ check) is a failure. Any other exception is an error. Tests may run in any order, so each must stand alone.

**`@BeforeEach` and `@AfterEach`.** A method with `@BeforeEach` runs before every test method of the class; one with `@AfterEach` runs after it. `LoadRunTest` uses them to start and stop a server for each test.

**`@TempDir`.** A parameter marked `@TempDir Path folder` receives a new empty folder that JUnit deletes afterwards. Tests write their input files there.

**AssertJ.** `assertThat(actual)` starts a check, and the call that follows says what must hold: `isEqualTo(expected)`, `isZero()`, `isPositive()`, `isTrue()`, `contains(...)` for texts and lists, `startsWith(...)`, `hasSize(...)`. `assertThatThrownBy(() -> code())` runs the code and checks the exception it throws: `isInstanceOf(Type.class)`, `hasMessage("exact text")`, `hasMessageContaining("part")`. The `() -> code()` is a **lambda**: a short unnamed function passed along so that AssertJ can run it inside its own `try`.

**Static imports.** `import static org.assertj.core.api.Assertions.assertThat;` lets the file write `assertThat(...)` instead of `Assertions.assertThat(...)`.

**Test names are sentences.** The method names read as claims (`countsAnythingButASuccessAsAnError`). When one fails, the name says what stopped being true.

**Real servers, real sockets.** `LoadRunTest` and `SlowClientsTest` start the real SeatWave class in the same Java program, on a port the operating system picks (port 0 means "any free port"). They talk to it over real sockets on the loopback address. That is slower than a plain call (the whole suite takes seconds) but it tests the real HTTP and TLS code.

**Java words new in this part.** A **text block** (`"""` ... `"""`) is a multi-line text; the indentation of the closing quotes is removed. `var` lets the compiler work out a type. `throws Exception` on a test method lets it call code that throws checked exceptions without a `try`. A **fake**: a small replacement for a real part (here, a shell script that pretends to be `docker`).

## Walkthrough of this repository's use

### `bench/ContainerCountersTest.java`

```java
package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

- The imports: `assertThat` and `assertThatThrownBy` from AssertJ; `Result` from `result4j`; `IOException`; `StandardCharsets` and the file classes (`Files`, `Path`, `PosixFilePermissions`) for writing the fake `docker`; `@Test` and `@TempDir` from JUnit.

```java
class ContainerCountersTest {

  // What `cat /sys/fs/cgroup/cpu.stat /proc/net/snmp` printed in the lab's proxy container.
  static final String SAMPLE =
      """
      usage_usec 1834567
      user_usec 1200000
      system_usec 634567
      Ip: Forwarding DefaultTTL
      Ip: 1 64
      Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab
      Tcp: 1 200 120000 -1 42 314 0 3 7
      Udp: InDatagrams NoPorts
      Udp: 0 0
      """;
```

- The class is not `public`: JUnit finds it anyway. `SAMPLE` is a **text block**: what `cat /sys/fs/cgroup/cpu.stat /proc/net/snmp` printed in the lab's proxy container (the comment says so). The first three lines come from `cpu.stat`; the rest are lines from `/proc/net/snmp`, including `Tcp:` header and value lines, and `Ip:` and `Udp:` lines that the parser must skip.
- The `Tcp:` values say `ActiveOpens` 42 and `PassiveOpens` 314, the numbers the next test expects.
- The constant is `static final` and not private, so another test could reuse it.

```java
  @Test
  void readsCpuTimeAndTheConnectionsOpenedAndAccepted() {
    Result<Counters, String> parsed = ContainerCounters.parse(SAMPLE);

    assertThat(parsed.discardError()).contains(new Counters(1_834_567, 314, 42));
  }
```

- `ContainerCounters.parse(SAMPLE)` returns a `Result`. `parsed.discardError()` turns it into an `Optional` that is empty for an error. `assertThat(optional).contains(value)` checks that the optional holds that value, which is compared with `equals`: a record equals another record of the same type with equal components. The expected value is `new Counters(1_834_567, 314, 42)`: processor time, passive opens, active opens.

```java
  @Test
  void saysWhichFileWasNotRead() {
    assertThat(ContainerCounters.parse("Tcp: ActiveOpens\nTcp: 1\n").isError()).isTrue();
    assertThat(ContainerCounters.parse("usage_usec 1\n").isError()).isTrue();
    assertThat(ContainerCounters.parse("usage_usec 1\nTcp: Other Columns\nTcp: 1 2\n").isError())
        .isTrue();
  }
```

- Three broken inputs, each must give an error: a `Tcp:` pair with no processor-time line; a processor-time line with no `Tcp:` lines; and a `Tcp:` pair that has neither `ActiveOpens` nor `PassiveOpens`. The test checks `isError()`, but not the message: the messages are the same for any of them as long as an error comes back.

```java
  @Test
  void runsTheCommandAsAListAndParsesWhatItPrints(@TempDir Path folder) throws Exception {
    Path fakeDocker = script(folder, "cat <<'END'\n" + SAMPLE + "END");

    Counters counters = new ContainerCounters(fakeDocker.toString()).read("seatwave-proxy-proxy-1");

    assertThat(counters).isEqualTo(new Counters(1_834_567, 314, 42));
  }
```

- `runsTheCommandAsAListAndParsesWhatItPrints` tests `read`, with no Docker. `script(folder, body)` (below) writes an executable file called `docker` into the temporary folder. The body is `cat <<'END'` followed by the sample and `END`: a shell **here-document**, which makes `cat` print the text between the two markers. The quotes around `END` stop the shell from changing the text.
- `new ContainerCounters(fakeDocker.toString())` uses the fake program's path as the "docker command", so the real `read` method starts a real process: `<fake> exec seatwave-proxy-proxy-1 cat /sys/... /proc/...`. The fake ignores its arguments and prints the sample. The result must equal the counters of the sample.
- This is the only test that runs a program with `ProcessBuilder`. It uses `#!/bin/sh` and POSIX file permissions, so it runs on macOS and Linux but not on Windows.

```java
  @Test
  void reportsAFailedCommandAndRefusesANameThatIsNotAContainerName(@TempDir Path folder)
      throws Exception {
    Path failingDocker = script(folder, "echo 'No such container' >&2; exit 1");
    ContainerCounters counters = new ContainerCounters(failingDocker.toString());

    assertThatThrownBy(() -> counters.read("seatwave-proxy-proxy-1"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("No such container");
    assertThatThrownBy(() -> counters.read("bad name; rm -rf"))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- The second fake prints `No such container` on the error stream (`>&2`) and exits with status 1. `read` must throw `IOException` whose message contains `No such container`: this proves the error output is merged and shown (`redirectErrorStream(true)`).
- `read("bad name; rm -rf")` must throw `IllegalArgumentException`: the name does not match the allow-list, and nothing runs. The text `rm -rf` is only a harmless string here; because the name check comes first, no command is ever built from it.
- `isInstanceOf(IOException.class)` checks the type of the thrown exception, including subclasses.

```java
  @Test
  void subtractsAnEarlierReadingAndRefusesNegativeCounters() {
    Counters later = new Counters(3_000, 20, 10);

    assertThat(later.since(new Counters(1_000, 5, 4))).isEqualTo(new Counters(2_000, 15, 6));
    assertThatThrownBy(() -> new Counters(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }
```

- `since` subtracts: `(3000, 20, 10) - (1000, 5, 4) = (2000, 15, 6)`.
- `new Counters(-1, 0, 0)` must throw `IllegalArgumentException`.

```java
  private static Path script(Path folder, String body) throws IOException {
    Path script = folder.resolve("docker");
    Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }
}
```

- The helper `script` writes `#!/bin/sh` (the first line that tells the system which program runs the file), a line break, the body and a line break to a file called `docker`. `setPosixFilePermissions(script, "rwx------")` lets only the owner read, write and run it. It returns the path. `Files.writeString` writes text to a file.

### `bench/BenchCliTest.java`

```java
package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

- Imports for the in-memory output streams (`ByteArrayOutputStream`, `PrintStream`), file helpers, and the JUnit annotations.

```java
class BenchCliTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
```

- Two fields, `out` and `err`, are byte buffers in memory. Because JUnit makes a new object of the class for each test, they start empty each time.

```java
  @Test
  void printsTheUsageAndAnySettingsProblemWithExitStatusTwo(@TempDir Path folder) throws Exception {
    Path bad =
        Files.writeString(
            folder.resolve("bad.properties"), "run.clients=0\n", StandardCharsets.UTF_8);
    ContainerCounters docker = new ContainerCounters("docker");

    int noArguments = BenchCli.run(new String[0], docker, print(out), print(err));
    int badFile = BenchCli.run(new String[] {bad.toString()}, docker, print(out), print(err));

    assertThat(noArguments).isEqualTo(BenchCli.EXIT_BAD_USAGE);
    assertThat(badFile).isEqualTo(BenchCli.EXIT_BAD_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("usage: bench", "run.clients must be from 1 to 2000, but was 0");
  }
```

- The first test checks both bad-usage paths. A settings file `bad.properties` that holds only `run.clients=0` is written to the temporary folder.
- `BenchCli.run(args, docker, out, err)` is called twice: with no arguments, and with the bad file. `new ContainerCounters("docker")` is passed but never used, because both calls stop before any container is read.
- Both must return `BenchCli.EXIT_BAD_USAGE` (2). The error text must contain `usage: bench` and `run.clients must be from 1 to 2000, but was 0` (the exact sentence of `intBetween`).
- `print(out)` wraps the buffer in a `PrintStream` (below).

```java
  @Test
  void stopsWithExitStatusOneWhenTheContainersCannotBeRead(@TempDir Path folder) throws Exception {
    Path settings = quickSettings(folder);
    ContainerCounters missingDocker = new ContainerCounters(folder.resolve("no-docker").toString());

    int status =
        BenchCli.run(new String[] {settings.toString()}, missingDocker, print(out), print(err));

    assertThat(status).isEqualTo(BenchCli.EXIT_STACK_FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("the bench stopped:");
    assertThat(out.toString(StandardCharsets.UTF_8)).contains(BenchReport.HEADER);
  }
```

- The second test starts a bench run whose "docker" is a path that does not exist (`folder.resolve("no-docker")`). The `quickSettings` file is the repository's real settings with the warm-up set to 0. `BenchCli.run` therefore reaches the first container reading, which cannot start the program and throws `IOException`.
- The status must be `EXIT_STACK_FAILED` (1), the error text must start with `the bench stopped:`, and the output must contain the table header: the header is printed before the first scenario.
- Nothing is sent to the network in this test, as long as the warm-up really is 0. The test does not check that; the helper below explains the weak link.

```java
  private static Path quickSettings(Path folder) throws IOException {
    String text =
        Files.readString(Path.of("config/bench.properties"), StandardCharsets.UTF_8)
            .replace("run.warmupMillis=5000", "run.warmupMillis=0")
            .replace(
                "tls.authorityPem=build/tls/",
                "tls.authorityPem=" + io.plainround.seatwave.proxy.backend.TestBackend.TLS + "/");
    return Files.writeString(folder.resolve("bench.properties"), text, StandardCharsets.UTF_8);
  }
```

- `quickSettings` reads `config/bench.properties` and replaces two texts: `run.warmupMillis=5000` becomes `run.warmupMillis=0`, and `tls.authorityPem=build/tls/` becomes the absolute path of the test certificates folder (`TestBackend.TLS`) plus `/`. The paths in the real file are relative to the project folder, which is where Gradle runs the tests, but the absolute path is safer.
- The new text is written to a file in the temporary folder.
- If the text in `config/bench.properties` changes, the `replace` silently does nothing. With a 5-second warm-up still in place, the test would start sending load to `127.0.0.1:20911` for five seconds before it reaches the missing `docker` program.
- The absolute path has to match the settings pattern for a file name: at most 200 characters of letters, digits and `_ . / -`. When the repository is checked out in a folder whose path is long, this test fails with a settings problem and exit status 2, although nothing in the code is wrong. The writer saw this in a scratch folder with a long path.

```java
  private static PrintStream print(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }
}
```

- `print(bytes)` makes a `PrintStream` that writes into the byte buffer in UTF-8, flushing at every line (`true`).

### `bench/LoadRunTest.java`

```java
package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.proxy.backend.TestBackend;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
```

- Imports include `TestBackend`, `TlsFiles` (to build the client's trust), `Duration` and `List`, and the three JUnit annotations.

```java
class LoadRunTest {

  private static final Duration RUN = Duration.ofMillis(300);
  private static final long SEAT_MAP_BYTES = 36_894;

  private TestBackend backend;
  private LoadRun runner;
```

- `RUN` is 300 ms, the length of every load run in these tests. `SEAT_MAP_BYTES` is 36,894, the size of the seat map of section A.
- `backend` and `runner` are fields set before each test.

```java
  @BeforeEach
  void start() throws Exception {
    backend = TestBackend.untrusting();
    runner =
        new LoadRun(
            TlsFiles.clientContext(TestBackend.TLS.resolve("seatwave-test-ca.pem")),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
  }
```

- `@BeforeEach void start()` starts an untrusting SeatWave and builds a `LoadRun` whose TLS settings trust the TEST authority (`seatwave-test-ca.pem`), with a 5-second connect timeout and a 5-second request timeout.

```java
  @AfterEach
  void stop() {
    backend.close();
  }
```

- `@AfterEach void stop()` closes the backend, so each test leaves nothing running.

```java
  @Test
  void reusesConnectionsAndCountsEveryByteOfEverySuccessfulRequest() throws Exception {
    Scenario scenario =
        new Scenario(
            "reuse", backend.tls("/events/1/seats?section=A"), false, ConnectionMode.REUSE);

    LoadResult result = runner.run(scenario, RUN, 2);

    assertThat(result.requests()).isPositive();
    assertThat(result.errors()).isZero();
    assertThat(result.wireBytes()).isEqualTo(result.requests() * SEAT_MAP_BYTES);
    assertThat(result.plainBytes()).isEqualTo(result.wireBytes());
    assertThat(result.p99()).isGreaterThanOrEqualTo(result.p50());
    assertThat(result.requestsPerSecond()).isPositive();
  }
```

- The first test sends the seat map over TLS with reused connections, from 2 clients, for 300 ms.
- It expects: at least one successful request (`isPositive()`); no errors (`isZero()`); the wire bytes equal to `requests * 36,894` exactly; the plain bytes equal to the wire bytes (no compression was asked); `p99` at least `p50`; and a positive throughput.
- The exact byte check is the strong one. It proves the bench counts every body byte of every request and counts nothing twice. The other checks hold for any run, however many requests it manages in 300 ms.

```java
  @Test
  void opensANewConnectionForEveryRequestOverTlsAndPlainHttp() throws Exception {
    LoadResult tls =
        runner.run(
            new Scenario("new-tls", backend.tls("/health"), true, ConnectionMode.NEW), RUN, 2);
    LoadResult plain =
        runner.run(
            new Scenario("new-plain", backend.plain("/health"), false, ConnectionMode.NEW), RUN, 2);

    assertThat(tls.requests()).isPositive();
    assertThat(tls.errors()).isZero();
    assertThat(plain.requests()).isPositive();
    assertThat(backend.metrics())
        .contains("seatwave_requests_total{route=\"GET /health\",status=\"200\"}");
  }
```

- The second test runs the new-connection mode, over TLS and over plain HTTP, against `/health`, with gzip asked (`true`) for the TLS one. Both must send at least one request successfully; the TLS one must have no errors.
- `backend.metrics()` reads SeatWave's metrics, which must contain the counter line for `GET /health` with status `200`: this shows the requests really reached the server.
- It does not check that each request used its own connection. The bench's own connection counters do that in the real lab.

```java
  @Test
  void countsAnythingButASuccessAsAnError() throws Exception {
    LoadResult result =
        runner.run(
            new Scenario("missing", backend.plain("/missing"), false, ConnectionMode.REUSE),
            RUN,
            1);

    assertThat(result.requests()).isZero();
    assertThat(result.errors()).isPositive();
    assertThat(result.p50()).isEqualTo(Duration.ZERO);
  }
```

- The third test aims at `/missing`, which SeatWave answers with 404. Nothing is a success: `requests()` is zero, `errors()` is positive and `p50()` is `Duration.ZERO` (no latencies to take a percentile of).

```java
  @Test
  void takesPercentilesByTheNearestRankRule() {
    List<Long> sorted = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

    assertThat(LoadRun.percentile(sorted, 0.50)).isEqualTo(Duration.ofNanos(5));
    assertThat(LoadRun.percentile(sorted, 0.99)).isEqualTo(Duration.ofNanos(10));
    assertThat(LoadRun.percentile(List.of(), 0.99)).isEqualTo(Duration.ZERO);
  }
```

- The fourth test calls the percentile method directly, with the sorted list 1 to 10 nanoseconds: p50 is 5, p99 is 10 (position `ceil(9.9) = 10`), and an empty list gives zero. This is the nearest-rank rule written out.

```java
  @Test
  void refusesARunWithoutClientsAndAScenarioThatIsNotHttp() {
    Scenario scenario = new Scenario("x", backend.plain("/health"), false, ConnectionMode.REUSE);

    assertThatThrownBy(() -> runner.run(scenario, RUN, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new Scenario("x", java.net.URI.create("ftp://127.0.0.1/"), false, ConnectionMode.NEW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Scenario("Bad Name", backend.plain("/"), false, ConnectionMode.NEW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- The fifth test checks three refusals: a run with 0 clients; a scenario with an `ftp://` address; a scenario named `Bad Name`. Each must throw `IllegalArgumentException`.
- The first lambda captures `scenario` and `runner`; Java lets a lambda use a local variable that is never changed afterwards (it is "effectively final").

### `bench/BenchSettingsTest.java`

```java
package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
```

- Imports include `Properties` (the Java class that holds the key-value pairs of a properties file) and `Result`.

```java
class BenchSettingsTest {

  @Test
  void readsTheBenchSettingsOfTheRepository() throws Exception {
    Properties file = SettingsReader.load(Path.of("config/bench.properties"));

    BenchSettings settings =
        BenchSettings.from(new SettingsReader(file))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));

    assertThat(settings.repeats()).isGreaterThanOrEqualTo(3);
    assertThat(settings.scenarios()).isNotEmpty();
    assertThat(settings.scenarios())
        .allMatch(scenario -> scenario.uri().getHost().equals("127.0.0.1"));
  }
```

- The first test loads the repository's real `config/bench.properties` and builds the settings. `orOnErrorThrow(problems -> new IllegalStateException(problems.toString()))` returns the value of a success or throws the given exception for an error, so a broken settings file fails this test with all problems in the message.
- It checks `repeats >= 3`, that there is at least one scenario, and that every scenario's host is `127.0.0.1`.
- `allMatch(...)` checks a condition for every item of a list.

```java
  @Test
  void refusesATargetOutsideTheLabAndReportsEveryProblem() throws Exception {
    Properties file = SettingsReader.load(Path.of("config/bench.properties"));
    String first = file.getProperty("scenario.names").split(",")[0].strip();
    file.setProperty("scenario." + first + ".uri", "https://seatwave.example/health");
    file.setProperty("run.repeats", "2");
    file.setProperty("counters.proxyContainer", "Proxy Container");

    Result<BenchSettings, List<String>> result = BenchSettings.from(new SettingsReader(file));

    assertThat(result.isError()).isTrue();
    assertThat(((Result.Error<BenchSettings, List<String>>) result).error())
        .anyMatch(line -> line.startsWith("scenario." + first + ".uri must match"))
        .anyMatch(line -> line.startsWith("run.repeats must be from 3"))
        .anyMatch(line -> line.startsWith("counters.proxyContainer must match"));
  }
}
```

- The second test breaks a copy in memory: the first scenario's address becomes `https://seatwave.example/health`, `run.repeats` becomes 2, and the proxy container name becomes `Proxy Container`. It then expects an error that names all three: the address does not match the allowed pattern, the repeats are below 3, and the container name does not match.
- `((Result.Error<BenchSettings, List<String>>) result).error()` is a **cast**: the code tells the compiler that this `Result` is the error kind, so it can read the list of problems. `anyMatch(...)` is true if at least one item fits. The three checks are about the start of each sentence.

### `bench/RawResponseTest.java`

```java
package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
```

- Imports for building bytes in memory, and for `GZIPOutputStream` to compress.

```java
class RawResponseTest {

  @Test
  void countsAChunkedGzipBodyAsWireBytesAndItsDecompressedSize() throws IOException {
    byte[] plain =
        "{\"seat\":\"A-1\",\"state\":\"available\"},".repeat(100).getBytes(StandardCharsets.UTF_8);
    byte[] compressed = gzip(plain);
    ByteArrayOutputStream response = new ByteArrayOutputStream();
    response.write(
        ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n"
                + "X-Cache-Status: MISS\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    int half = compressed.length / 2;
    chunk(response, compressed, 0, half);
    chunk(response, compressed, half, compressed.length - half);
    response.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

    LoadRun.Answer answer = RawResponse.parse(response.toByteArray());

    assertThat(answer).isEqualTo(new LoadRun.Answer(200, compressed.length, plain.length, "MISS"));
  }
```

- The first test builds a real-looking NGINX answer. The body is a JSON piece repeated 100 times (`"...".repeat(100)`), turned into bytes and compressed (`gzip`, below). The answer's head says `Transfer-Encoding: chunked`, `Content-Encoding: gzip` and `X-Cache-Status: MISS`. The compressed body is cut into two chunks (`chunk(...)` writes a hexadecimal size, a line break, the bytes and a line break), followed by the final `0` chunk.
- `RawResponse.parse` must give `new LoadRun.Answer(200, compressed.length, plain.length, "MISS")`: status 200; wire bytes equal to the compressed length (the chunk framing is not counted); plain bytes equal to the original length; and the cache status text.

```java
  @Test
  void readsABodyThatEndsWithTheConnection() throws IOException {
    byte[] whole =
        "HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);

    assertThat(RawResponse.parse(whole)).isEqualTo(new LoadRun.Answer(404, 2, 2, ""));
  }
```

- The second test has no chunks: the head says `Content-Length: 2` and the body is `{}`; the status is 404. The answer must be `Answer(404, 2, 2, "")`. `RawResponse` returns a 404 answer as an answer, not an error: whether the status is a success is `LoadRun`'s decision.

```java
  @Test
  void refusesACutOffOrMalformedResponse() {
    assertThatThrownBy(
            () -> RawResponse.parse("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII)))
        .hasMessage("the response ended before its headers did");
    assertThatThrownBy(
            () -> RawResponse.parse("SPDY 200\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))
        .hasMessageContaining("not an HTTP/1.1 status line");
    assertThatThrownBy(() ->
            RawResponse.parse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"
                    .getBytes(StandardCharsets.US_ASCII)))
        .hasMessageContaining("not hexadecimal");
    assertThatThrownBy(() ->
            RawResponse.parse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nff\r\nab"
                    .getBytes(StandardCharsets.US_ASCII)))
        .hasMessage("a chunk is cut off");
  }
```

- The third test has four broken inputs, each with the exact message or a part of it:
  - Only a status line with no blank line after the headers: `the response ended before its headers did`.
  - `SPDY 200` instead of an HTTP status line: `not an HTTP/1.1 status line`.
  - A chunk size `zz`: `not hexadecimal`.
  - A chunk size `ff` (255 bytes) with only two bytes after it: `a chunk is cut off`.

```java
  private static void chunk(ByteArrayOutputStream out, byte[] data, int from, int length)
      throws IOException {
    out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(data, from, length);
    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
  }
```

- `chunk` writes one chunk: the length in hexadecimal (`Integer.toHexString`), a line break, the slice of data, a line break.

```java
  private static byte[] gzip(byte[] plain) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
      out.write(plain);
    }
    return bytes.toByteArray();
  }
}
```

- `gzip` compresses bytes with `GZIPOutputStream`. The `try (...)` closes the stream, which writes the final compressed bytes; the buffer is read after the block.

### `bench/BenchReportTest.java`

```java
package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
```

- Imports for `URI`, `Duration`, `List`.

```java
class BenchReportTest {

  private static final Scenario SCENARIO =
      new Scenario(
          "production", URI.create("https://127.0.0.1:20900/health"), true, ConnectionMode.REUSE);
```

- A scenario named `production` aimed at the PRODUCTION port, gzip asked, reused connections. Nothing is sent: the test only builds values.

```java
  @Test
  void printsTheMedianRunByThroughputWithItsOwnCountersAndEveryRunsThroughput() {
    List<BenchRow> runs =
        List.of(row(1_000, 2_000_000), row(3_000, 6_000_000), row(2_000, 3_000_000));

    String text = BenchReport.render(runs);

    assertThat(BenchReport.median(runs).result().requests()).isEqualTo(2_000);
    assertThat(text)
        .startsWith("production")
        .contains("1500.0") // the proxy's 3,000,000 us of CPU over 2,000 requests, per thousand
        .contains("50.0") // a hit share of 50 percent
        .contains("runs (rps): 1000 (1000 in 1.0 s), 3000 (3000 in 1.0 s), 2000 (2000 in 1.0 s)");
  }
```

- Three runs with 1,000, 3,000 and 2,000 requests in one second, and proxy processor times of 2,000,000, 6,000,000 and 3,000,000 microseconds. The median by throughput is the 2,000-request run.
- The text must start with `production` and contain `1500.0`: the median run's proxy time is 3,000,000 microseconds = 3,000 ms, over 2,000 requests = 1.5 ms per request = 1,500 ms per thousand. It must contain `50.0` (half the responses were cache hits: `requests / 2` hits of `requests` lookups), and the exact runs line in the order the runs were given: `1000 (1000 in 1.0 s), 3000 (3000 in 1.0 s), 2000 (2000 in 1.0 s)`.
- The inline comments on the `contains` lines say what each number is.

```java
  @Test
  void refusesFewerThanThreeRunsAndMoreHitsThanLookups() {
    assertThatThrownBy(() -> BenchReport.median(List.of(row(1, 1), row(2, 2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new LoadResult(1, 0, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 1, 1, 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- `median` of two rows throws `IllegalArgumentException` (fewer than 3 runs). A `LoadResult` with 2 cache hits and 1 lookup throws as well.

```java
  private static BenchRow row(long requests, long proxyCpuMicros) {
    LoadResult result =
        new LoadResult(
            requests,
            0,
            Duration.ofSeconds(1),
            Duration.ofMillis(1),
            Duration.ofMillis(2),
            requests * 2_453,
            requests * 36_894,
            requests / 2,
            requests);
    return new BenchRow(
        SCENARIO, result, new Counters(proxyCpuMicros, 8, 4), new Counters(1_000, 4, 0));
  }
}
```

- `row(requests, proxyCpuMicros)` builds a `BenchRow` from numbers: a run of 1 second, p50 1 ms, p99 2 ms, wire bytes `requests * 2,453` and plain bytes `requests * 36,894` (the gzip and plain sizes of the README's production row), half of the requests as cache hits, and counters for the proxy and SeatWave. The test has no real network.

### `slow/SlowClientsTest.java`

```java
package io.plainround.seatwave.proxy.slow;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import io.plainround.seatwave.proxy.config.SettingsReader;
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

- Imports include `SettingsReader` and `Path` for the real settings files, the in-memory streams, `TestBackend`, and `@TempDir`.

```java
class SlowClientsTest {

  @Test
  void keepsSeatWaveWorkersBusyWhileSlowClientsReadAndCountsEveryDownload() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      SlowSettings settings =
          new SlowSettings(
              backend.plain("/static/poster-arena.bin"),
              4,
              20_000,
              Duration.ofMillis(5),
              4_096,
              backend.plain("/health"),
              backend.admin("/metrics"),
              Duration.ofMillis(5),
              Duration.ofSeconds(30));

      SlowResult result = SlowClients.run(settings);

      assertThat(result.completed()).isEqualTo(4);
      assertThat(result.failed()).isZero();
      assertThat(result.bodyBytes()).isGreaterThan(300_000);
      assertThat(result.workers()).isEqualTo(8);
      assertThat(result.workerSeconds()).isPositive();
      assertThat(settings.bytesPerSecond()).isEqualTo(4_000_000);
    }
  }
```

- The first test runs the real slow clients against a real SeatWave. The settings are built in code (the lab-address rule of `SlowSettings.from` is not used): 4 clients download `/static/poster-arena.bin` (about 300 KB), 20,000 bytes every 5 ms, receive buffer 4,096, a probe to `/health`, the metrics from the operations listener, a 5 ms sample pause and a 30-second timeout.
- It expects: 4 complete, 0 failed; more than 300,000 bytes per download (the poster is 300,000 bytes, plus headers); 8 workers in all (the `untrusting()` backend has 8); a positive `workerSeconds` (the server's busy-seconds counter grew); and `bytesPerSecond()` of 4,000,000 (20,000 bytes every 5 ms).
- The test name says it "keeps SeatWave workers busy", but it checks only that the worker counter grew. It does not check `workersBusyMax`, and with 4 clients on the loopback address a busy count of 1 or even 0 is possible, as the "Try it yourself" section of the slow-client notes shows.

```java
  @Test
  void countsADownloadThatIsNotAnOkResponseAsFailed() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      SlowSettings settings =
          new SlowSettings(
              backend.plain("/missing"),
              2,
              1_000,
              Duration.ofMillis(1),
              4_096,
              backend.plain("/health"),
              backend.admin("/metrics"),
              Duration.ofMillis(5),
              Duration.ofSeconds(30));

      SlowResult result = SlowClients.run(settings);

      assertThat(result.completed()).isZero();
      assertThat(result.failed()).isEqualTo(2);
    }
  }
```

- The second test aims 2 clients at `/missing` (404). Both must count as failed, none as complete: a download is complete only with status 200.

```java
  @Test
  void readsTheStatusOfAResponseHead() {
    assertThat(SlowClients.status("HTTP/1.1 200 OK\r\nDate: x")).isEqualTo(200);
    assertThat(SlowClients.status("garbage")).isZero();
    assertThat(SlowClients.percentile(List.of(), 0.5)).isEqualTo(Duration.ZERO);
  }
```

- Three small direct calls: the status of `HTTP/1.1 200 OK\r\nDate: x` is 200; `garbage` gives 0; the percentile of an empty list of durations is zero.

```java
  @Test
  void readsTheRepositorysSettingsAndRefusesAnAddressOutsideTheLabNetwork() throws Exception {
    for (String name : List.of("direct", "buffered", "unbuffered")) {
      SlowSettings.from(
              new SettingsReader(
                  SettingsReader.load(Path.of("config/slow-" + name + ".properties"))))
          .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));
    }
    var properties = SettingsReader.load(Path.of("config/slow-direct.properties"));
    properties.setProperty("slow.targetUri", "http://seatwave.example/");

    assertThat(SlowSettings.from(new SettingsReader(properties)).isError()).isTrue();
  }
```

- The fourth test loads the three real settings files `slow-direct`, `slow-buffered` and `slow-unbuffered` and requires each to be valid. It does not load the three `-poster` variants.
- It then takes the direct file, changes the target to `http://seatwave.example/` and requires an error: a target outside the lab network is refused. `var properties` lets the compiler pick the type (`Properties`).

```java
  @Test
  void printsTheResultsAndRefusesBadUsage(@TempDir Path folder) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);
    SlowSettings settings =
        SlowSettings.from(
                new SettingsReader(SettingsReader.load(Path.of("config/slow-direct.properties"))))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));
    SlowResult result =
        new SlowResult(
            500,
            0,
            37_188,
            Duration.ofMillis(1_370),
            Duration.ofMillis(2_140),
            64,
            0,
            64,
            104.45,
            Duration.ofMillis(2),
            Duration.ofMillis(1_370),
            Duration.ofMillis(1_370),
            8,
            0);
    Path bad = Files.writeString(folder.resolve("bad.properties"), "", StandardCharsets.UTF_8);

    SlowClientsCli.print(settings, result, printer);
    int usage = SlowClientsCli.run(new String[0], printer, printer);
    int badFile = SlowClientsCli.run(new String[] {bad.toString()}, printer, printer);

    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s)")
        .contains("at most 64 of 64 busy at once (0 readings missed); 104.45 worker-seconds")
        .contains("slow.targetUri is missing");
    assertThat(usage).isEqualTo(SlowClientsCli.EXIT_BAD_USAGE);
    assertThat(badFile).isEqualTo(SlowClientsCli.EXIT_BAD_USAGE);
  }
}
```

- The fifth test checks `SlowClientsCli`. It builds a `SlowResult` with the numbers of the README's direct run (500 complete, 37,188 bytes, 64 of 64 workers busy, 104.45 worker-seconds) and calls `print`. It then calls `run` with no arguments and with an empty settings file.
- The printed text must contain the first line's description, `500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s)`, and the third line's `at most 64 of 64 busy at once (0 readings missed); 104.45 worker-seconds`. The error output also goes to the same buffer, so it must contain `slow.targetUri is missing`.
- Both `run` calls must return `EXIT_BAD_USAGE`.
- The success path of `SlowClientsCli.run` (the part that runs the clients and prints) is not executed by any test: the Gradle coverage report lists lines 58 to 62 of `SlowClientsCli.java` as missed.

## Try it yourself

All commands run in the repository folder. Times and counts differ on every run. They do not need Docker.

**1. Run only this part's tests.**

```text
./gradlew test --tests 'io.plainround.seatwave.proxy.bench.*' --tests 'io.plainround.seatwave.proxy.slow.*'
```

Gradle prints `BUILD SUCCESSFUL`. To see each test's name, add `--info` or `-i`.

**2. Watch a test fail on purpose.** Open `src/test/java/io/plainround/seatwave/proxy/bench/LoadRunTest.java` in a copy of the repository (not in your only copy), change `Duration.ofNanos(5)` in `takesPercentilesByTheNearestRankRule` to `Duration.ofNanos(6)`, and run `./gradlew test --tests '*LoadRunTest'`. Gradle prints the test's name with `FAILED`, the exception type and line (`org.opentest4j.AssertionFailedError at LoadRunTest.java:86`) and `5 tests completed, 1 failed`. The full message (expected and actual value) is in `build/reports/tests/test/index.html`. Change it back.

**3. See the settings test catch a broken settings file.** In a copy, change `run.repeats=3` to `run.repeats=2` in `config/bench.properties` and run `./gradlew test --tests '*BenchSettingsTest'`. `readsTheBenchSettingsOfTheRepository` fails: Gradle prints `java.lang.IllegalStateException at BenchSettingsTest.java:20`, and the message in `build/test-results/test/TEST-io.plainround.seatwave.proxy.bench.BenchSettingsTest.xml` is `[run.repeats must be from 3 to 50, but was 2]`. Change it back.

**4. Read the coverage of this part.** Run `./gradlew check` and open `build/reports/jacoco/test/html/index.html` in a browser. The package `io.plainround.seatwave.proxy.bench` shows which lines the tests ran (green) and which they did not (red). On the writer's run these `bench` lines were not run by any test at all: `BenchCli` 35, 36, 79, 80, 81, 92, 96 to 101, 103 and 105 (the whole of a successful bench run, since a real run needs the stack); `LoadRun` 123 and 125 to 127 (a refused request in the loop, and an interrupt), 181 (the deadline check of a new connection), 190 to 194 (a client that hangs or fails) and 263 to 265 (the counting of `X-Cache-Status` hits and lookups, because no test talks to a cache); `ContainerCounters` 66, 74, 75, 112 and 113; `RawResponse` 65; `BenchReport` 51; `BenchRow` 50; `LoadResult` 42; `BenchSettings` 68. The same view shows `slow` lines 135 to 139, 156, 158, 160, 177, 179, 181, 194, 229, 252 to 255, 257 and 265 to 267 of `SlowClients.java`, and 35, 36 and 58 to 62 of `SlowClientsCli.java`, as not run.

## Mistakes people make

- **Testing with the clock.** A test that says "this must take under 50 ms" fails on a busy machine. These tests check counts and exact byte values, and use 300 ms only as the length of a run.
- **Sharing state between tests.** Each test class starts its own server and closes it. Tests may run in any order.
- **Leaving the counting of the headline numbers untested.** `LoadRun` counts cache hits, but no test sends it a response with `X-Cache-Status`. The `hit %` column of the README rests on lines that the tests never run.
- **Checking only that something happened.** `isPositive()` on requests proves little. The strong check is the exact byte count.
- **Writing a test that depends on the text of a file it does not own.** `quickSettings` replaces a literal in `config/bench.properties`. When that text changes, the test changes meaning without failing.
- **Assuming the repository lives in a short path.** The settings pattern for a file name allows 200 characters; a long checkout path breaks `BenchCliTest`.
- **A name that promises more than the test checks.** `keepsSeatWaveWorkersBusyWhileSlowClientsRead...` asserts less than its name says.

## Check yourself

1. Why does `LoadRunTest` start a new server for each test? (`@BeforeEach` and `@AfterEach`; tests must not share state and may run in any order.)
2. What does `assertThatThrownBy(() -> counters.read("bad name; rm -rf"))` prove? (The name is refused with `IllegalArgumentException` before any command is built.)
3. Why does the first `RawResponseTest` expect `compressed.length` and not the number of bytes on the wire including chunk sizes? (The bench counts body bytes, not the chunk framing, which `unchunk` removes.)
4. How does `BenchReportTest` get `1500.0`? (3,000,000 microseconds = 3,000 ms of processor time, over 2,000 requests, times 1,000.)
5. Name two things in this part that no test runs. (A successful bench run; a refused connection inside `LoadRun.loop`; the counting of cache hits in `LoadRun`; a successful `SlowClientsCli.run`.)

## Further reading

- JUnit documentation (this repository pins JUnit 6.1.3), https://docs.junit.org/
- AssertJ Core 3.27.7 documentation, https://assertj.github.io/doc/
- Java Language Specification: text blocks (Java SE 25), https://docs.oracle.com/javase/specs/jls/se25/html/jls-3.html#jls-3.10.6
- Java SE 25 API: `java.lang.ProcessBuilder`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessBuilder.html
