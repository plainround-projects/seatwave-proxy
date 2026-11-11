# The `bench` package, part 1: settings, value types and the report table

This is part 1 of 2. Part 1 reads the small files of the package: what a bench run is told to do (`Scenario`, `ConnectionMode`, `BenchSettings`), what it measures (`LoadResult`, `Counters`, `BenchRow`) and how the table is drawn (`BenchReport`). Part 2 (`java-bench-walkthrough-2.md`) reads the files that send the requests and read the container counters (`LoadRun`, `RawResponse`, `ContainerCounters`, `BenchCli`).

## What it is

The `bench` package is a load generator written in plain Java. A **load generator** is a program that sends many requests to a server on purpose, so that you can see how fast the server answers and what the answering costs. The package sends requests to SeatWave directly and through the NGINX proxy, and prints one row of numbers per way of reaching it.

A **benchmark** (a bench for short) is such a measurement, repeated under the same conditions so that two ways of doing the same thing can be compared. This one compares, for example, plain HTTP with HTTPS, a proxy that reuses its connections to SeatWave with one that does not, and three compression levels.

Part 1 covers seven of the eleven files. None of them sends a request. They hold the settings, the checked numbers and the text of the table.

## Why this repository uses it

Module 9 says that a proxy is worth putting in front of a service only if you can show what it saves and what it costs. "It feels faster" is not evidence. The bench turns each claim into a number that anyone can reproduce: requests per second, the time the middle request and the slowest one percent took, how many bytes crossed the wire, and how much processor time the proxy and SeatWave spent per thousand requests.

The package follows three rules of this course. A reported number comes from at least three runs (`Runs.MINIMUM_RUNS`), and the row shows the median run, never the mean. Every setting is read from a file with no defaults, and every value is checked against a short allow-list, so that a typo cannot send load to another machine. A number that cannot be right (a negative count, more cache hits than lookups) cannot be created at all.

## Where it appears

| File | What it does |
|---|---|
| `bench/ConnectionMode.java` | Two values: `REUSE` (keep one connection open) and `NEW` (a new connection for every request). |
| `bench/Scenario.java` | One way of reaching SeatWave: a name, an address, whether to ask for gzip, and the connection mode. |
| `bench/Counters.java` | Three numbers read from inside one container: processor time, connections accepted, connections opened. |
| `bench/LoadResult.java` | What the clients saw during one run: counts, times and bytes. |
| `bench/BenchRow.java` | One run joined with what it cost the proxy and SeatWave. |
| `bench/BenchSettings.java` | Reads and checks `config/bench.properties`. |
| `bench/BenchReport.java` | Picks the median run of a scenario and prints its row. |

Part 2 reads `LoadRun.java`, `RawResponse.java`, `ContainerCounters.java` and `BenchCli.java`.

The bench is started by the Gradle task `bench` (`./gradlew -q bench --args='config/bench.properties'`), which runs `BenchCli.main`. The file `config/bench.properties` lists 16 scenarios.

## How it works underneath

**A run, a scenario, a row.** A **run** is one stretch of time (10 seconds in `config/bench.properties`) during which a fixed number of **clients** (8) send requests as fast as they can. A **client** here is a thread of the bench program that sends a request, waits for the whole answer, and then sends the next one. This is called a **closed loop**: the clients never send faster than the server answers. A closed loop measures what the path can do at that number of clients.

Before the measured runs there is one **warm-up** run whose numbers are thrown away. The first seconds of a Java program are slower, because its code is still being loaded and compiled to machine code, and the proxy's first connections are still being opened. The warm-up takes that cost out of the measured runs.

Each scenario then gets 3 measured runs (`run.repeats=3`). Each run becomes one `BenchRow`. The table prints the **median run by requests per second**: sort the three runs by throughput and take the middle one. The row is that real run, with all its own numbers, so every figure in the row belongs to one run. The throughput of all three runs is printed under the row so that you can see how far apart they were.

**Throughput and latency.** **Throughput** is how many requests were answered per second. **Latency** is how long one request took from sending to the last byte. Both are measured together because they trade against each other. A **percentile** says how many requests were at least as fast as a value: the 50th percentile (p50) is the median request, and the 99th (p99) is the value that 99 requests in 100 beat. p99 shows the slow tail that an average hides.

**Wire bytes and plain bytes.** **Wire bytes** are the body bytes as they crossed the network. When the proxy compresses a response with gzip, the wire bytes are fewer than the **plain bytes**, which are the bytes after the client decompresses. The row shows both per response.

**What each side cost.** The bench asks each container how much processor time it has used and how many TCP connections it has accepted and opened, once before and once after each run (this is part 2). The difference is the cost of the run. Processor time is divided by the number of requests sent, failed ones included, to give **CPU milliseconds per thousand requests** (`CPU ms/1k`): the lower, the cheaper.

**A row that is not a steady rate is flagged.** When more than 1 percent of the median run's requests failed, or the three runs are far apart (the fastest more than twice the slowest), the table prints a warning line under the row. Its median is still a real run, but it does not describe a steady rate, and the reader is told so.

**Settings are data, not code.** The scenarios are listed in `config/bench.properties`, a **properties file**: lines of `key=value` (see `java-properties.md`). `BenchSettings` reads the file through `SettingsReader`, a helper class of the `config` package that reads typed values and collects one problem sentence per bad key, so that you see every mistake at once. There are no defaults: a missing key is a problem, never a silent fallback.

**Java words used in this part.**

| Word | Meaning |
|---|---|
| `package` | The folder-like name a file belongs to (`io.plainround.seatwave.proxy.bench`). |
| `import` | Lets the file use a class from another package by its short name. |
| `class`, `final class` | A blueprint for objects. `final` means no other class may extend it. |
| `record` | A short way to write a class that only holds values. The compiler writes the constructor, the getters (called as `name()`), `equals`, `hashCode` and `toString`. |
| compact constructor | The part of a record written without a parameter list. It runs first, to check the values, before the fields are set. |
| `enum` | A type with a fixed list of values, such as `REUSE` and `NEW`. |
| `static` | Belongs to the class, not to one object. |
| `private` / `public` | Visible only inside the class / visible everywhere. |
| `Pattern` | A compiled regular expression: a short text that describes which strings are allowed. |
| `Duration` | A length of time with nanosecond resolution. |

See `java.md` for the language in general.

## Walkthrough of this repository's use

### `bench/ConnectionMode.java`

```java
package io.plainround.seatwave.proxy.bench;

/** How a load run uses connections. */
public enum ConnectionMode {
  /** Every client keeps one connection open and sends request after request on it. */
  REUSE,
  /**
   * Every request opens a new connection and closes it: with HTTPS each request pays a full TLS
   * handshake, which is how the run counts handshakes per second.
   */
  NEW
}
```

- `package ...bench;` puts the file in the `bench` package.
- The `/** ... */` blocks are **Javadoc** comments. Tools turn them into documentation. The compiler ignores them.
- `public enum ConnectionMode` declares a type with exactly two values. `REUSE` means each client keeps one connection open and sends request after request on it (HTTP keep-alive). `NEW` means each request opens a new connection and closes it. With HTTPS, a new connection means a full TLS handshake for every request, which is how the bench counts handshakes per second.
- The comma between `REUSE` and `NEW` separates the values. The last value has no comma.

### `bench/Scenario.java`

```java
package io.plainround.seatwave.proxy.bench;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One way of reaching SeatWave in a load run.
 *
 * @param name a short name, for example {@code direct-tls}
 * @param uri the resource, on 127.0.0.1 or a container name of the lab network
 * @param acceptsGzip whether requests say {@code Accept-Encoding: gzip}
 * @param mode whether each client reuses its connection or opens one per request
 */
public record Scenario(String name, URI uri, boolean acceptsGzip, ConnectionMode mode) {
```

- `java.net.URI` holds an address such as `https://127.0.0.1:20900/events/1/seats?section=A`. `Objects` has helper methods, here `requireNonNull`. `Pattern` is for the name check.
- The Javadoc `@param` lines document the four components.
- `public record Scenario(String name, URI uri, boolean acceptsGzip, ConnectionMode mode)` declares a record with four components. After it is built, `scenario.name()`, `scenario.uri()`, `scenario.acceptsGzip()` and `scenario.mode()` return them, and a record never changes after construction.

```java
  /**
   * A scenario name: lower-case letters, digits and hyphens, at most 17 characters, the width of
   * the bench table's first column.
   */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,16}");
```

- `NAME` is a **regular expression**: `[a-z]` is one lower-case letter, then `[a-z0-9-]{0,16}` is from 0 to 16 lower-case letters, digits or hyphens. The first character must therefore be a letter, and the whole name is at most 17 characters, the width of the first column of the bench table (`BenchReport.ROW`, below), so a name can never push its row out of line. (The first version allowed 24 characters for a 17-character column.) Names such as `direct-tls` and `page-production` pass; `Bad Name` does not (capital letter and space).
- `public static final` makes it a constant that `BenchSettings` reuses.

```java
  /**
   * Creates the scenario.
   *
   * @throws IllegalArgumentException if the name is invalid or the URI is not http or https
   */
  public Scenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(mode, "mode");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("invalid scenario name: " + name);
    }
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
      throw new IllegalArgumentException("a scenario URI must be http or https: " + uri);
    }
  }
}
```

- The compact constructor runs when a `Scenario` is created. It refuses a null name, address or mode with `Objects.requireNonNull(value, "name")`: that throws `NullPointerException` whose message is the second argument. `acceptsGzip` is a `boolean`, which cannot be null.
- `NAME.matcher(name).matches()` asks whether the whole name fits the pattern. If not, `IllegalArgumentException` says which name was bad. `IllegalArgumentException` is Java's standard "you gave me a value I cannot accept".
- `"http".equals(uri.getScheme())` puts the constant first on purpose. The scheme of an address without one is `null`, and `"http".equals(null)` is simply false, where `null.equals(...)` would crash. The scheme must be `http` or `https`, so `ftp://...` is refused.
- `!a && !b` means "neither": the exception is thrown only when the scheme is not `http` and also not `https`.

### `bench/Counters.java`

```java
package io.plainround.seatwave.proxy.bench;

/**
 * Counters read from inside one container: its CPU time from its control group, and the TCP
 * connections its network namespace opened, from the kernel's {@code /proc/net/snmp}.
 *
 * @param cpuMicros CPU time used by every process of the container, in microseconds
 * @param passiveOpens connections the container accepted
 * @param activeOpens connections the container opened to others
 */
public record Counters(long cpuMicros, long passiveOpens, long activeOpens) {
```

- A record of three `long` values (a `long` is a 64-bit whole number, big enough for microseconds of processor time).
- `cpuMicros` is the processor time all processes of the container have used so far, in microseconds, read from the container's **control group**. A control group (cgroup) is the Linux kernel's way to count and limit what a group of processes uses. Processor time is time a processor core spent running the container's programs.
- `passiveOpens` counts TCP connections the container **accepted** (someone connected to it). `activeOpens` counts connections it **opened** (it connected to someone). Both come from the kernel's file `/proc/net/snmp` (see part 2).
- A **TCP connection** is the reliable two-way channel that HTTP travels over. Opening one takes a round trip, and servers keep counters of how many they opened.

```java
  /**
   * Checks that no counter is negative.
   *
   * @throws IllegalArgumentException if one is
   */
  public Counters {
    if (cpuMicros < 0 || passiveOpens < 0 || activeOpens < 0) {
      throw new IllegalArgumentException("a counter cannot be negative");
    }
  }
```

- The compact constructor refuses a negative counter. A counter that only grows can never be negative, so a negative value means something is wrong, for example a difference taken the wrong way round.
- `||` means "or". The three comparisons are joined, so the exception is thrown if any one counter is below zero.

```java
  /**
   * Returns how much each counter grew since an earlier reading.
   *
   * @param earlier the reading before the run
   * @return the growth, which is what one run cost
   * @throws IllegalArgumentException if a counter went down, which happens when the container
   *     started again between the two readings
   */
  public Counters since(Counters earlier) {
    return new Counters(
        Math.subtractExact(cpuMicros, earlier.cpuMicros),
        Math.subtractExact(passiveOpens, earlier.passiveOpens),
        Math.subtractExact(activeOpens, earlier.activeOpens));
  }
}
```

- `since(earlier)` returns how much each counter grew since an earlier reading. The bench reads the counters before a run and after it, and `after.since(before)` is what the run cost.
- `Math.subtractExact(a, b)` subtracts and throws `ArithmeticException` if the result does not fit in a `long`. It cannot silently wrap around.
- The new `Counters` goes through the checking constructor, so if the container was restarted between the two readings (its counters begin again at zero), the difference is negative and the constructor throws `IllegalArgumentException("a counter cannot be negative")`. The Javadoc's `@throws` line says so. `BenchCli` catches that exception and stops the bench with a clear message and exit status 1 (part 2), instead of the stack trace the first version printed.

### `bench/LoadResult.java`

```java
package io.plainround.seatwave.proxy.bench;

import java.time.Duration;
import java.util.Objects;
```

- `Duration` and `Objects` are imported. There is nothing else.

```java
/**
 * What one load run measured.
 *
 * @param requests requests that got a 2xx answer
 * @param errors requests that failed or got anything else
 * @param elapsed how long the run lasted
 * @param p50 the median latency of the successful requests
 * @param p99 the 99th percentile latency of the successful requests
 * @param wireBytes body bytes received, as they crossed the wire (compressed if gzip was used)
 * @param plainBytes body bytes after decompression; equal to wireBytes when nothing was compressed
 * @param cacheHits responses whose {@code X-Cache-Status} was HIT
 * @param cacheLookups responses that carried an {@code X-Cache-Status} at all
 */
public record LoadResult(
    long requests,
    long errors,
    Duration elapsed,
    Duration p50,
    Duration p99,
    long wireBytes,
    long plainBytes,
    long cacheHits,
    long cacheLookups) {
```

- The record has nine components. The Javadoc says what each one is, and the rules that follow:
  - `requests` counts only requests that got a 2xx answer (a status from 200 to 299). `errors` counts the rest. Latencies and bytes are therefore for successful requests only.
  - `p50` and `p99` are the median and 99th-percentile latency of the successful requests.
  - `wireBytes` and `plainBytes` are described above.
  - `cacheHits` counts responses whose `X-Cache-Status` header said `HIT`; `cacheLookups` counts responses that had that header at all. Only a proxy that caches sets it.
- `X-Cache-Status` is a header that this repository's NGINX configuration adds to show whether the answer came from its cache.

```java
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  /**
   * Checks the counts.
   *
   * @throws IllegalArgumentException if a count is negative or there were more hits than lookups
   */
  public LoadResult {
    Objects.requireNonNull(elapsed, "elapsed");
    Objects.requireNonNull(p50, "p50");
    Objects.requireNonNull(p99, "p99");
    if (requests < 0 || errors < 0 || wireBytes < 0 || plainBytes < 0 || cacheHits < 0) {
      throw new IllegalArgumentException("a count cannot be negative");
    }
    if (cacheHits > cacheLookups) {
      throw new IllegalArgumentException("more cache hits than lookups");
    }
  }
```

- `NANOS_PER_SECOND = 1_000_000_000.0` is a `double` constant (the underscores only make the digits readable). There are a billion nanoseconds in a second.
- The constructor refuses missing durations, any negative count, and more hits than lookups (a hit is a kind of lookup, so that would be impossible).
- Note what it does not check: it does not check that `p99` is at least `p50`, or that `plainBytes` is at least `wireBytes`.

```java
  /**
   * Returns successful requests per second.
   *
   * @return the throughput of the run
   */
  public double requestsPerSecond() {
    return elapsed.isZero() ? 0 : requests * NANOS_PER_SECOND / elapsed.toNanos();
  }
}
```

- `requestsPerSecond()` is requests divided by elapsed seconds. `requests * NANOS_PER_SECOND / elapsed.toNanos()` multiplies first so the division is done on a `double`, which keeps the fraction. `condition ? a : b` is the **conditional operator**: if `elapsed` is zero it returns 0 instead of dividing by zero.
- `elapsed` is the whole run, from the first client starting to the last client finishing, not just the requested duration. A run that overruns shows up in the printed `(requests in seconds)` list.

### `bench/BenchRow.java`

```java
package io.plainround.seatwave.proxy.bench;

import java.util.Objects;

/**
 * One measured run of one scenario: what the clients saw and what it cost each container.
 *
 * @param scenario the scenario
 * @param result what the clients measured
 * @param proxy how much the proxy's counters grew during the run
 * @param backend how much SeatWave's counters grew during the run
 */
public record BenchRow(Scenario scenario, LoadResult result, Counters proxy, Counters backend) {
```

- One run of one scenario: the scenario, what the clients measured (`LoadResult`), how much the proxy's counters grew, and how much SeatWave's grew. A record can hold other records.

```java
  private static final double PER_THOUSAND = 1_000.0;
  private static final double MICROS_PER_MILLI = 1_000.0;

  /** Checks that nothing is missing. */
  public BenchRow {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(proxy, "proxy");
    Objects.requireNonNull(backend, "backend");
  }
```

- `PER_THOUSAND` and `MICROS_PER_MILLI` are constants so that the arithmetic below has no unexplained numbers.
- The compact constructor checks that none of the four parts is missing.

```java
  /**
   * Returns the proxy's CPU time per thousand requests sent.
   *
   * @return milliseconds of CPU, or 0 when nothing was sent
   */
  public double proxyCpuMillisPerThousand() {
    return perThousand(proxy.cpuMicros() / MICROS_PER_MILLI);
  }
```

- `proxyCpuMillisPerThousand()` turns the proxy's microseconds into milliseconds (`/ MICROS_PER_MILLI`) and then scales to "per thousand requests" in `perThousand`.

```java
  /**
   * Returns SeatWave's CPU time per thousand requests sent.
   *
   * @return milliseconds of CPU, or 0 when nothing was sent
   */
  public double backendCpuMillisPerThousand() {
    return perThousand(backend.cpuMicros() / MICROS_PER_MILLI);
  }
```

- The same for SeatWave.

The report prints the connection counts as they are (`backend().passiveOpens()`), not per thousand, so the record has no method for that. (The first version had one, `backendConnectionsPerThousand()`, that nothing called.)

```java
  private double perThousand(double amount) {
    // Every request sent, successful or not: a container spends CPU on a failed request too, so
    // dividing by the successes alone would charge the failures' CPU to them.
    long sent = Math.addExact(result.requests(), result.errors());
    return sent == 0 ? 0 : amount * PER_THOUSAND / sent;
  }
}
```

- `perThousand(amount)` is `amount * 1000 / sent`, where `sent` is every request of the run, successful or failed: `Math.addExact` adds the two counts and throws instead of wrapping around if the sum did not fit in a `long`. The comment gives the reason: the processor time covers every request the container handled, failed ones included. The first version divided by the successful requests only, so a run with many errors looked more expensive per request than it was. The README's first `proxy-no-reuse` row, measured with that version, shows it: 2,991 successful requests, 3,330 errors, and 3328.0 CPU ms per thousand.
- With no request sent the method returns 0, not a division error.

### `bench/BenchSettings.java`

```java
package io.plainround.seatwave.proxy.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import io.plainround.seatwave.proxy.report.Runs;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
```

- `Result` is the type from the `result4j` library (see `result4j.md`): a value that is either a success holding a value or an error holding a reason. A method that can fail for expected reasons returns a `Result` instead of throwing.
- `SettingsReader` is the repository's typed reader of properties files. `Path` is a file path. `ArrayList` and `List` are lists. `URI` is an address.

```java
/**
 * Everything a bench run needs, read from a settings file with no defaults.
 *
 * @param authorityPem the TEST certificate authority the clients trust
 * @param connectTimeout the connect timeout of every request
 * @param requestTimeout the whole-request timeout of every request
 * @param warmup a run made first and thrown away, so class loading, the JIT compilers and the
 *     proxy's first connections do not land in a measured run
 * @param measure how long each measured run lasts
 * @param repeats measured runs per scenario, at least {@link Runs#MINIMUM_RUNS}
 * @param clients how many clients send at once
 * @param proxyContainer the proxy's container, whose CPU and connections are read
 * @param backendContainer SeatWave's container, likewise
 * @param scenarios the scenarios, in the order they run
 */
public record BenchSettings(
    Path authorityPem,
    Duration connectTimeout,
    Duration requestTimeout,
    Duration warmup,
    Duration measure,
    int repeats,
    int clients,
    String proxyContainer,
    String backendContainer,
    List<Scenario> scenarios) {
```

- The record has ten components, each documented. `authorityPem` is the file with the TEST certificate authority: the one authority whose certificates the clients will trust, so that the bench can verify the proxy's and SeatWave's TEST certificates. `warmup` is the discarded first run, `measure` the length of each measured run, `repeats` how many measured runs per scenario, `clients` how many send at once.
- `proxyContainer` and `backendContainer` are the Docker container names whose counters are read.
- `List<Scenario>` is a list whose items are `Scenario` values: this is a **generic** type, the angle brackets say what the list holds.

```java
  // Only the lab's own addresses: this machine's loopback address, where the stack publishes its
  // ports, and nothing else, so a typo cannot send load to another host.
  private static final Pattern LAB_URI =
      Pattern.compile("https?://127\\.0\\.0\\.1:209[0-9]{2}/[A-Za-z0-9/._?=&-]*");
  private static final Pattern YES_NO = Pattern.compile("yes|no");
  private static final Pattern MODE = Pattern.compile("reuse|new");
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MAX_RUN_MILLIS = 600_000;
  private static final int MAX_REPEATS = 50;
  private static final int MAX_CLIENTS = 2_000;
```

- `LAB_URI` is the allow-list for scenario addresses: `https?` means `http` or `https` (the `?` makes the `s` optional), then `://127\.0\.0\.1:`, which is exactly this machine's loopback address (the dots are escaped with `\` because a bare `.` means "any character"), then `209[0-9]{2}`, which is port 20900 to 20999, then `/` and a path of letters, digits and `/ . _ ? = & -`. Any other host, such as `example.com`, fails the match. A typo therefore cannot send load to another computer. The two lines of comment above it say so.
- `YES_NO` and `MODE` list the only words allowed for the `gzip` and `connection` keys of a scenario.
- The `MAX_...` numbers are upper bounds that stop a typo from asking for a one-hour run or a million clients: 60 seconds for a timeout, 10 minutes for a run, 50 repeats, 2,000 clients.

```java
  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if there are too few repeats, no clients or no scenario
   */
  public BenchSettings {
    Objects.requireNonNull(authorityPem, "authorityPem");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(warmup, "warmup");
    Objects.requireNonNull(measure, "measure");
    Objects.requireNonNull(proxyContainer, "proxyContainer");
    Objects.requireNonNull(backendContainer, "backendContainer");
    scenarios = List.copyOf(scenarios);
    if (repeats < Runs.MINIMUM_RUNS || clients < 1 || scenarios.isEmpty()) {
      throw new IllegalArgumentException("too few repeats, no clients or no scenario");
    }
  }
```

- The constructor checks nothing is missing, then `scenarios = List.copyOf(scenarios)` replaces the incoming list with an unmodifiable copy. Inside a compact constructor you may assign to a parameter, and the record then stores the new value. Nobody can change the list afterwards, and a null list or a null item fails right here.
- The last check enforces the course rule: at least `Runs.MINIMUM_RUNS` (3) repeats, at least one client, at least one scenario.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<BenchSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String authority = reader.filePath("tls.authorityPem");
    Duration connect = reader.millisBetween("client.connectTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration request = reader.millisBetween("client.requestTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration warmup = reader.millisBetween("run.warmupMillis", 0, MAX_RUN_MILLIS);
    Duration measure = reader.millisBetween("run.measureMillis", 1, MAX_RUN_MILLIS);
    int repeats = reader.intBetween("run.repeats", Runs.MINIMUM_RUNS, MAX_REPEATS);
    int clients = reader.intBetween("run.clients", 1, MAX_CLIENTS);
    String proxy = reader.textMatching("counters.proxyContainer", ContainerCounters.CONTAINER_NAME);
    String backend =
        reader.textMatching("counters.backendContainer", ContainerCounters.CONTAINER_NAME);
    List<Scenario> scenarios = new ArrayList<>();
    for (String name : reader.listMatching("scenario.names", Scenario.NAME)) {
```

- `from(SettingsReader reader)` is a `static` factory: it builds the settings from a reader. It returns `Result<BenchSettings, List<String>>`: either the settings, or the list of problem sentences.
- Each `reader.something("key", ...)` call reads one key. `textMatching` requires the value to match a pattern. `millisBetween(key, low, high)` reads a whole number of milliseconds inside the range and returns a `Duration`. `intBetween` reads a whole number. A missing or bad key records a problem and returns a harmless stand-in (an empty string or the lower bound), so the code can continue and collect all the problems.
- Note the ranges: `run.warmupMillis` may be 0 (no warm-up), `run.measureMillis` must be at least 1, `run.repeats` must be from `Runs.MINIMUM_RUNS` to 50.
- The container names must match `ContainerCounters.CONTAINER_NAME`, which is also the allow-list used before a `docker exec` runs (part 2).

```java
      String prefix = "scenario." + name + ".";
      String uri = reader.textMatching(prefix + "uri", LAB_URI);
      String gzip = reader.textMatching(prefix + "gzip", YES_NO);
      String mode = reader.textMatching(prefix + "connection", MODE);
      if (!uri.isEmpty() && !gzip.isEmpty() && !mode.isEmpty()) {
        scenarios.add(
            new Scenario(
                name,
                URI.create(uri),
                gzip.equals("yes"),
                mode.equals("reuse") ? ConnectionMode.REUSE : ConnectionMode.NEW));
      }
    }
```

- `reader.listMatching("scenario.names", Scenario.NAME)` reads the comma-separated list of names. For each name the code builds the key prefix, for example `scenario.production.`, and reads the three keys of that scenario: `uri`, `gzip` and `connection`.
- `if (!uri.isEmpty() && ...)` builds the `Scenario` only when all three were valid. An invalid key has already put its sentence into the problem list, and `Scenario` is not created from stand-in values.
- `gzip.equals("yes")` turns the word into a `boolean`. The conditional `mode.equals("reuse") ? ConnectionMode.REUSE : ConnectionMode.NEW` turns the word into the enum value. Only `reuse` and `new` can arrive here, because `MODE` has already checked.
- The scenarios are kept in file order, which is the order in which they run.
- The list is not checked for repeated names: a name listed twice would run twice.

```java
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new BenchSettings(
            Path.of(authority),
            connect,
            request,
            warmup,
            measure,
            repeats,
            clients,
            proxy,
            backend,
            scenarios));
  }
}
```

- If the reader has collected any problem, the method returns `Result.error(reader.problems())`: the settings are not built. Otherwise `Result.success(new BenchSettings(...))` builds them, converting the certificate path text with `Path.of(authority)`. The constructor's own checks run again here.

### `bench/BenchReport.java`

```java
package io.plainround.seatwave.proxy.bench;

import io.plainround.seatwave.proxy.report.Runs;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes the bench table: one row per scenario, the median of its runs by requests per second, with
 * every run's throughput listed so the spread stays visible. A row whose run had many failures, or
 * whose runs differ widely, gets a warning line under it, because its median is not a steady rate.
 * Thread-safe: it holds no state.
 */
public final class BenchReport {
```

- It imports `Runs` from the `report` package: the bench shares the "at least three runs, take the median" rule with Module 5's report.
- `Comparator` orders values; `Collectors` joins strings; `Locale` fixes the number format.
- A class made of static methods only is written `final` with a private constructor (below), so nobody creates objects of it.

```java
  /** The table's header line. */
  public static final String HEADER =
      "scenario              rps   p50 ms   p99 ms  errors  wire B/resp  plain B/resp  hit %"
          + "  proxy CPU ms/1k  SeatWave CPU ms/1k  proxy accepted  proxy opened  SeatWave accepted";

  private static final String ROW =
      "%-17s %8.0f %8.2f %8.2f %7d %12.0f %13.0f %6s %16.1f %19.1f %15d %13d %18d%n";
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double PERCENT = 100.0;
  // More than 1 percent of failed requests, or a fastest run more than twice the slowest, and the
  // row is not a steady measurement: it is shown, with a warning, never quietly as a median.
  private static final double ERROR_SHARE_LIMIT = 0.01;
  private static final double SPREAD_LIMIT = 2.0;
```

- `HEADER` is the table's first line. The two string pieces are joined with `+` because one line would be too long. The column titles are padded with spaces so that they sit above the numbers.
- `ROW` is a **format string** for `String.format`. Each `%...` fills in one value:
  - `%-17s` is a text, padded on the right to 17 characters (the minus sign means left-aligned).
  - `%8.0f` is a decimal number in 8 characters with no digits after the point; `%8.2f` has two digits after the point.
  - `%7d` is a whole number in 7 characters.
  - `%6s` is a text in 6 characters (used for the hit percentage, which may be the text `-`).
  - `%16.1f` and `%19.1f` are the two CPU columns with one digit after the point.
  - `%15d`, `%13d`, `%18d` are the three connection counts.
  - `%n` is a line break for the current system.
- A scenario name longer than 17 characters would push its row out of line, so `Scenario.NAME` allows at most 17. The names in `config/bench.properties` are at most 16.
- `PERCENT = 100.0` and the two `NANOS_PER_...` constants name the numbers used for unit conversion.
- `ERROR_SHARE_LIMIT = 0.01` and `SPREAD_LIMIT = 2.0` are the two thresholds of the warning lines (below): more than 1 percent of the median run's requests failed, or the fastest run was more than twice the slowest. The comment says what they are for: such a row is still shown, with a warning, never quietly as a median.

```java
  private BenchReport() {}

  /**
   * Picks the median run of one scenario by throughput.
   *
   * @param runs the measured runs, at least {@link Runs#MINIMUM_RUNS}
   * @return the median run itself, so every number in its row comes from one real run
   */
  public static BenchRow median(List<BenchRow> runs) {
    return Runs.medianBy(runs, Comparator.comparingDouble(row -> row.result().requestsPerSecond()));
  }
```

- The private constructor stops anyone from writing `new BenchReport()`.
- `median(runs)` calls `Runs.medianBy(runs, comparator)`. `Comparator.comparingDouble(row -> row.result().requestsPerSecond())` builds a comparator from a function: it orders rows by their throughput. `row -> ...` is a **lambda**, a short unnamed function. `medianBy` throws `IllegalArgumentException` for fewer than 3 runs.
- It returns the median `BenchRow` itself, not an average, so every number printed comes from one real run.

```java
  /**
   * Writes one scenario's row and the throughput of each of its runs.
   *
   * @param runs the measured runs of one scenario
   * @return two lines of text
   */
  public static String render(List<BenchRow> runs) {
    BenchRow row = median(runs);
    LoadResult result = row.result();
    double perResponse = result.requests() == 0 ? 0 : 1.0 / result.requests();
    String hitShare =
        result.cacheLookups() == 0
            ? "-"
            : String.format(
                Locale.ROOT, "%.1f", PERCENT * result.cacheHits() / result.cacheLookups());
```

- `render(runs)` returns the two lines of one scenario's output.
- `perResponse` is `1.0 / requests`, or 0 if there were no successful requests. Multiplying total bytes by it gives the average bytes per response.
- `hitShare` is the percentage of lookups that were hits, written with one decimal and a dot (`Locale.ROOT` fixes the dot whatever the computer's language is). With no lookups at all (a direct run, where no cache sits in the path) it is the text `-`. In the README, `direct-http` prints `-` and `production` prints `0.0`.

```java
    String line =
        String.format(
            Locale.ROOT,
            ROW,
            row.scenario().name(),
            result.requestsPerSecond(),
            millis(result.p50()),
            millis(result.p99()),
            result.errors(),
            result.wireBytes() * perResponse,
            result.plainBytes() * perResponse,
            hitShare,
            row.proxyCpuMillisPerThousand(),
            row.backendCpuMillisPerThousand(),
            row.proxy().passiveOpens(),
            row.proxy().activeOpens(),
            row.backend().passiveOpens());
```

- `String.format(Locale.ROOT, ROW, ...)` fills the 13 placeholders of `ROW`, in order: the scenario name, throughput, p50 in milliseconds, p99 in milliseconds, error count, wire bytes per response, plain bytes per response, hit share, proxy CPU per thousand, SeatWave CPU per thousand, then the three connection counts (`proxy().passiveOpens()` is connections the proxy accepted from clients, `proxy().activeOpens()` the connections it opened to SeatWave, `backend().passiveOpens()` the connections SeatWave accepted).
- These three are plain counts for the whole median run, not per thousand. They show the keep-alive effect: with reuse, the proxy opened 47 connections for about 43,000 requests; without it, 2,991.

```java
    String spread =
        runs.stream()
            .map(each ->
                String.format(
                    Locale.ROOT,
                    "%.0f (%d in %.1f s)",
                    each.result().requestsPerSecond(),
                    each.result().requests(),
                    each.result().elapsed().toNanos() / NANOS_PER_SECOND))
            .collect(Collectors.joining(", ", "  runs (rps): ", System.lineSeparator()));
    return line + spread + warnings(runs, result);
  }

  private static String warnings(List<BenchRow> runs, LoadResult median) {
    StringBuilder text = new StringBuilder();
    long sent = median.requests() + median.errors();
    double errorShare = sent == 0 ? 0 : (double) median.errors() / sent;
    if (errorShare > ERROR_SHARE_LIMIT) {
      text.append(
          String.format(
              Locale.ROOT,
              "  warning: %.0f percent of the requests of this run failed; the other columns describe"
                  + " a failing path%n",
              PERCENT * errorShare));
    }
    double slowest =
        runs.stream().mapToDouble(each -> each.result().requestsPerSecond()).min().orElse(0);
    double fastest =
        runs.stream().mapToDouble(each -> each.result().requestsPerSecond()).max().orElse(0);
    if (fastest > slowest * SPREAD_LIMIT) {
      text.append(
          String.format(
              Locale.ROOT,
              "  warning: the runs differ by more than %.0f times (%.0f to %.0f rps), so the median"
                  + " is not a steady rate%n",
              SPREAD_LIMIT,
              slowest,
              fastest));
    }
    return text.toString();
  }
```

- `runs.stream()` turns the list into a **stream**, a pipeline of steps. `.map(each -> String.format(...))` turns each run into a piece of text like `4555 (45550 in 10.0 s)`: its throughput, how many requests, and how long it really lasted.
- `.collect(Collectors.joining(", ", "  runs (rps): ", System.lineSeparator()))` joins the pieces with `, `, puts `  runs (rps): ` in front and a line break at the end.
- The method returns the row line, the runs line and any warning lines.
- `warnings(runs, median)` builds those lines. `sent` is the median run's successful and failed requests together, and `errorShare` the failed part of it, from 0 to 1; `(double)` turns the whole number into a decimal before the division, so `1 / 4` is 0.25 and not 0. Above `ERROR_SHARE_LIMIT` the first warning prints the share as a percentage. Then `mapToDouble(...).min()` and `.max()` find the slowest and the fastest run's throughput (`orElse(0)` covers an empty list, which `median` has already refused). If the fastest is more than `SPREAD_LIMIT` times the slowest, the second warning prints both. For the runs 957, 299 and 4 requests per second, it prints `  warning: the runs differ by more than 2 times (4 to 957 rps), so the median is not a steady rate` (`BenchReportTest.warnsWhenTheRunsDifferByMoreThanTwoTimes` checks exactly that text).

```java
  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
```

- `millis(duration)` converts a `Duration` to a decimal number of milliseconds.

## Try it yourself

All commands run in the repository folder. Numbers differ on every run and on every computer.

**1. Read the real settings file and see the checks refuse a bad copy.** Save this as `build/TrySettings.java` (the `build` folder is ignored by git). It needs the compiled classes, so run `./gradlew -q compileJava` once first.

```text
import io.plainround.seatwave.proxy.bench.BenchSettings;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.nio.file.Path;
import java.util.Properties;

public class TrySettings {
  public static void main(String[] args) throws Exception {
    Properties file = SettingsReader.load(Path.of("config/bench.properties"));
    var good = BenchSettings.from(new SettingsReader(file));
    System.out.println(!good.isError() ? "valid" : "invalid");
    BenchSettings settings =
        good.orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));
    System.out.println(settings.scenarios().size() + " scenarios, first: " + settings.scenarios().getFirst());
    System.out.println(settings.clients() + " clients, " + settings.repeats() + " repeats");
    file.setProperty("run.repeats", "2");
    file.setProperty("scenario.direct-http.uri", "http://example.com/");
    file.setProperty("counters.proxyContainer", "Proxy Container");
    file.remove("run.clients");
    System.out.println(BenchSettings.from(new SettingsReader(file)));
  }
}
```

```text
java -cp build/classes/java/main:$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1) build/TrySettings.java
```

The output on the machine that wrote these notes (the last line was one line; it is wrapped here):

```text
valid
16 scenarios, first: Scenario[name=direct-http, uri=http://127.0.0.1:20911/events/1/seats?section=A, acceptsGzip=false, mode=REUSE]
8 clients, 3 repeats
Error[error=[run.repeats must be from 3 to 50, but was 2, run.clients is missing,
counters.proxyContainer must match [a-z0-9][a-z0-9-]{0,62}, but was Proxy Container,
scenario.direct-http.uri must match https?://127\.0\.0\.1:209[0-9]{2}/[A-Za-z0-9/._?=&-]*, but was http://example.com/]]
```

All four mistakes came back in one answer, and no load was possible.

**2. Run the unit tests of this part.**

```text
./gradlew test --tests '*BenchReportTest' --tests '*BenchSettingsTest'
```

Gradle prints `BUILD SUCCESSFUL`. `BenchReportTest` builds three runs of 1,000, 3,000 and 2,000 requests and checks that the median is the 2,000 one; two more tests check the CPU arithmetic of a failing row and both warning lines (`tests-tools-walkthrough-1.md`).

**3. Check a figure from the README by hand.** The README's `gzip-9` row says 601.7 proxy CPU milliseconds per thousand requests. Divide by 1,000: about 0.6 ms of proxy processor time for each request. The `gzip-1` row says 99.5, about 0.1 ms per request. The difference is the extra effort of compression level 9.

The full bench needs the Docker stack, which these notes did not run (see `measuring-a-proxy.md` for the README's captured output).

## Mistakes people make

- **Reporting one run.** A single run can be an outlier. The settings refuse fewer than 3 repeats, and `BenchReport.median` refuses fewer than 3 rows.
- **Averaging instead of taking the median.** One slow run drags a mean. The median is one real run.
- **Dividing by the wrong count.** Processor time covers failed requests too, so `BenchRow` divides it by every request sent. Dividing by the successes alone inflates the figure for a failing run, as the first version did. Still read the `errors` column, and any warning line, before you trust a CPU-per-thousand figure.
- **Forgetting the warm-up.** The first seconds of a Java program are slow. `run.warmupMillis=5000` throws them away. The TryLoad program in part 2 shows it: its first run is clearly slower than the next ones.
- **Letting a typo send load elsewhere.** `LAB_URI` allows only `127.0.0.1` on ports 20900 to 20999.
- **Locale surprises.** Without `Locale.ROOT`, a computer set to German would print `4555,0` with a comma. Every `String.format` here passes `Locale.ROOT`.

## Check yourself

1. Why is the median run printed, and not the average of three runs? (The median is one real run, so all its numbers belong together; a mean is dragged by one outlier and mixes runs.)
2. What does `X-Cache-Status` have to do with the `hit %` column? (Hits divided by responses that carried the header; with no header there are no lookups and the column shows `-`.)
3. What does `Counters.since` do when a container restarted between two readings? (The difference is negative and the `Counters` constructor throws `IllegalArgumentException`.)
4. Which three words may appear in a scenario's `connection` key and its `gzip` key? (`reuse` or `new`; `yes` or `no`.)
5. Why must a scenario address match `https?://127\.0\.0\.1:209[0-9]{2}/...`? (So that a typo cannot point the load at another host; the lab only uses this machine's loopback address, ports 20900 to 20999.)

## Further reading

- Java SE 25 API: `java.lang.Record` and `java.util.regex.Pattern`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Record.html and https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html
- Java Language Specification, record classes (Java SE 25), https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.10
- Java SE 25 API: `java.util.Formatter` (the `%` codes), https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Formatter.html
- Java SE 25 API: `java.time.Duration`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Duration.html
- result4j 1.2.0, https://github.com/sviperll/result4j
