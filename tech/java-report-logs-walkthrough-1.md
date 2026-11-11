# The `report` package, line by line (part 1 of 2 of the report and logs notes)

This is part 1 of 2. Part 1 reads the `report` package, which is Module 5's `curl -w` report. Part 2 (`java-report-logs-walkthrough-2.md`) reads the `logs` package, which turns the proxy's access log into numbers.

## What it is

The `report` package reads lines of timing numbers that `curl` wrote, checks them, and prints a table that says, for each target, where the time of a request went. It has ten files. Six are small value types (`Phase`, `ConnectionKind`, `CurlSample`, `CurlProblem`, `PhaseTimings`, `TargetTiming`), and four do the work: `Runs` (the median rule), `CurlLine` (reads one line), `TimingReport` (draws the table) and `ReportCli` (the `report` command).

A **median** is the middle value after sorting. A **phase** is one stage of a request: looking up the address (DNS), opening the TCP connection, the TLS handshake, waiting for the first byte, and receiving the rest.

## Why this repository uses it

Module 5 says to split a request into phases and look at the biggest one first. `curl -w` can print the clock readings. The script `scripts/curl-timings.sh` (see `shell-scripts-walkthrough.md`) writes one line per request. This package reads those lines, checks them, groups them by target and by connection kind (new or reused), and prints the median run of each group.

In Module 9 the question is what a proxy changes. The README runs the method before and after: it times the same seat map straight to SeatWave (`direct-https`, `direct-http`) and through the proxy (`proxy-http`, `proxy-tls`, `proxy-no-reuse`, `proxy-sidecar`, `production`), on a new connection and on a reused one. The table shows, for example, that a new TLS connection spends 8.47 ms in the handshake straight to SeatWave and 1.41 ms at the proxy, and that on a reused connection the first byte (about 1 ms) dominates everywhere.

A number that is not checked is worse than no number, so the rules are in the types. A `PhaseTimings` that is negative or does not add up cannot exist, and a table row with fewer than 3 runs cannot exist.

This package is byte-for-byte the same as the `report` package of the sibling repository `seatwave-network` for eight of this package's ten files, apart from the package name; `TimingReport` lost its second table and `ReportCli` gained a testable `run` method.

## Where it appears

| File | What it does |
|---|---|
| `report/Phase.java` | The five phases and the label each prints. |
| `report/ConnectionKind.java` | `NEW` or `REUSED`. |
| `report/Runs.java` | The one rule for a reported number: at least 3 runs, and the median. Also used by the bench. |
| `report/CurlSample.java` | One curl request, checked. |
| `report/CurlProblem.java` | Why a line did not become a `CurlSample`. |
| `report/PhaseTimings.java` | The five phases and the total, with the rule that they add up. |
| `report/TargetTiming.java` | One row of the table: all runs of one target and kind. |
| `report/CurlLine.java` | Parses one line of the script's output. |
| `report/TimingReport.java` | Groups samples into rows and writes the table as text. |
| `report/ReportCli.java` | The `report` command. |

The Gradle task `report` runs `ReportCli`: `./gradlew -q report --args='build/timings/before-after.txt'`.

## How it works underneath

**What curl writes.** The script runs `curl -w` with a format that prints **cumulative** clock readings in seconds, each measured from the start of the transfer:

- `dns` (curl's `time_namelookup`): when the host name was turned into an address.
- `connect` (`time_connect`): when the TCP connection was open.
- `tls` (`time_appconnect`): when the TLS handshake was done. It is 0 for plain HTTP.
- `ttfb` (`time_starttransfer`): when the first byte of the response arrived.
- `total` (`time_total`): when the last byte arrived.

The rest of the line is `http=` (protocol version), `code=` (status), `size=` (body bytes), `connects=` (how many new connections curl opened for this request: 1 or 0) and `server=` (the `Server-Timing` header, which may hold spaces, so it comes last). The script also writes `encoding=` and `cache=` fields; this package ignores keys it does not need.

A real line, made for these notes against SeatWave on this machine:

```text
local-https dns=0.000061 connect=0.000252 tls=0.003677 ttfb=0.004032 total=0.004430 http=1.1 code=200 size=36894 connects=1 encoding= cache= server=app;dur=0.09
```

**From readings to phases.** A phase is the difference between two readings. For the line above:

| Phase | Arithmetic | Result |
|---|---|---|
| dns | 0.000061 | 0.061 ms |
| connect | 0.000252 - 0.000061 | 0.191 ms |
| tls | 0.003677 - 0.000252 | 3.425 ms |
| first byte | 0.004032 - 0.003677 | 0.355 ms |
| transfer | 0.004430 - 0.004032 | 0.398 ms |
| total | 0.004430 | 4.430 ms |

The five phases add up to the total (each reading is added once and subtracted once).

**Zero means "did not happen".** On a reused connection curl writes `connect=0.000000 tls=0.000000`. Taking a difference with a zero would give a negative phase, so `CurlLine` replaces a zero reading with the previous reading: that phase is then 0 long.

**Why the median run, not the median of each phase.** A row shows one real run, the one with the median total, with all of its own phases. Medians taken phase by phase would give numbers that do not add up to the total. One real run always does.

**What is refused, and where.** A line is refused if it is malformed, if curl got no HTTP version (the request failed), if the status is not 2xx, if the size is negative, or if a phase is negative. A group is refused if it has fewer than 3 runs. A refusal is a value (see `result4j.md`), printed as `refused: ...` under the table, and the command then exits with status 1.

**Java words used in this part.**

| Word | Meaning |
|---|---|
| `enum` | A type with a fixed list of values (`Phase`, `ConnectionKind`). |
| `record` | A short way to write a class that only holds values; the compiler writes the constructor and accessors. |
| compact constructor | The part of a record that checks values before they are stored. |
| `sealed interface` | An interface that only a closed list of types may implement. |
| generic method `<T> T` | A method that works for any type `T`. |
| `Result` | From `result4j`: either a success with a value or an error with a reason. |
| `switch` with patterns | Takes a value apart by its type and its parts. |
| `Optional` | A box holding one value or nothing. |
| `BigDecimal` | A number with exact decimal digits. |

See `java.md` for the language in general.

## Walkthrough of this repository's use

### `report/Phase.java`

```java
package io.plainround.seatwave.proxy.report;

/** The five phases of one request, in the order they happen (Module 5, Section 6). */
public enum Phase {
  /** Turning the host name into an IP address (Module 6). */
  DNS("dns"),
  /** The TCP three-way handshake: one round trip. */
  CONNECT("connect"),
  /** The TLS handshake: one round trip for TLS 1.3, two for TLS 1.2; zero for plain HTTP. */
  TLS("tls"),
  /** From the end of the handshakes to the first byte of the response: one round trip + server. */
  FIRST_BYTE("first byte"),
  /** From the first byte of the response to the last. */
  TRANSFER("transfer");
```

- An enum of the five phases, in the order they happen. Each value carries a label in brackets, such as `FIRST_BYTE("first byte")`.
- `DNS` means turning a host name into an IP address. `CONNECT` is the TCP three-way handshake (one round trip). `TLS` is the TLS handshake (one round trip for TLS 1.3, two for TLS 1.2, none for plain HTTP). `FIRST_BYTE` runs from the end of the handshakes to the first byte of the response (one round trip plus the server's time). `TRANSFER` runs to the last byte.

```java
  private final String label;

  Phase(String label) {
    this.label = label;
  }

  /**
   * Returns the name a report prints.
   *
   * @return for example {@code first byte}
   */
  public String label() {
    return label;
  }
}
```

- The constructor `Phase(String label)` stores the label. An enum constructor is always private. `label()` returns it for the report to print.

### `report/ConnectionKind.java`

```java
package io.plainround.seatwave.proxy.report;

/** Whether a request opened its own connection or reused one that was already open. */
public enum ConnectionKind {
  /** A new connection: the request paid for the TCP handshake and, over HTTPS, the TLS one. */
  NEW,
  /** A reused (keep-alive) connection: the handshakes were already paid. */
  REUSED
}
```

- Two values: `NEW` (the request paid for the TCP handshake and, over HTTPS, the TLS handshake) and `REUSED` (keep-alive: the handshakes were paid before).

### `report/Runs.java`

```java
package io.plainround.seatwave.proxy.report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
```

- `Comparator` says how to order two values. `Duration` is a length of time.

```java
/**
 * The one rule for turning repeated runs into a reported number: at least {@link #MINIMUM_RUNS}
 * runs, and the median, never the mean or a single run. Thread-safe: it holds no state.
 */
public final class Runs {

  /**
   * The fewest runs a reported timing may come from. With three, one slow or one fast outlier
   * cannot become the reported value; fewer is a sample, not a measurement.
   */
  public static final int MINIMUM_RUNS = 3;

  private Runs() {}
```

- `MINIMUM_RUNS = 3` is a public constant: the fewest runs a reported timing may come from. With three, one slow or one fast outlier cannot become the reported value. Fewer is "a sample, not a measurement".
- The class has a private constructor and only static members.

```java
  /**
   * Returns the median run by a key: the middle one, or the lower of the two middle ones.
   *
   * @param runs the runs, at least {@link #MINIMUM_RUNS}
   * @param order the key to sort by, for example the total time
   * @param <T> the run type
   * @return the median run itself, so every field of the result belongs to one real run
   * @throws IllegalArgumentException if there are fewer than {@link #MINIMUM_RUNS} runs
   */
  public static <T> T medianBy(List<T> runs, Comparator<T> order) {
    Objects.requireNonNull(order, "order");
    requireEnough(runs);
    List<T> sorted = new ArrayList<>(runs);
    sorted.sort(order);
    // The lower middle: for an even count it is a value that was really measured, not an average.
    return sorted.get((sorted.size() - 1) / 2);
  }
```

- `public static <T> T medianBy(List<T> runs, Comparator<T> order)` is a **generic method**. `<T>` declares a type parameter: `T` stands for whatever type the runs have (a `Duration`, a `CurlSample`). The method returns the same type.
- `requireEnough(runs)` refuses fewer than 3.
- `new ArrayList<>(runs)` makes a copy, so the caller's list is not reordered. `sorted.sort(order)` sorts the copy.
- `sorted.get((sorted.size() - 1) / 2)` takes the middle. For 7 runs the index is 3, the exact middle. For 4 runs, the index is `(4 - 1) / 2 = 1` (whole-number division drops the fraction): the lower of the two middle ones. That value was really measured, not an average of two.
- The method returns the median **run itself**, so every field of the result belongs to one real run.

```java
  /**
   * Returns the median of durations, by the same rule.
   *
   * @param durations at least {@link #MINIMUM_RUNS} durations
   * @return the median duration
   */
  public static Duration median(List<Duration> durations) {
    return medianBy(durations, Comparator.naturalOrder());
  }
```

- `median(List<Duration>)` calls the same method with `Comparator.naturalOrder()`, which uses the natural order of `Duration` (shorter first).

```java
  /**
   * Refuses too few runs.
   *
   * @param runs the runs
   * @throws IllegalArgumentException if there are fewer than {@link #MINIMUM_RUNS}
   */
  public static void requireEnough(List<?> runs) {
    Objects.requireNonNull(runs, "runs");
    if (runs.size() < MINIMUM_RUNS) {
      throw new IllegalArgumentException(
          "a timing needs at least " + MINIMUM_RUNS + " runs, but had " + runs.size());
    }
  }
}
```

- `List<?>` is a list of any type, because only its size matters here. `?` is a **wildcard**.
- Too few runs throws `IllegalArgumentException` with a message that says how many there were.

### `report/CurlSample.java`

```java
package io.plainround.seatwave.proxy.report;

import java.util.Objects;
import java.util.regex.Pattern;
```

- `Pattern` is imported for the target-name check.

```java
/**
 * One request as curl measured it: one line of the timing script's output, checked.
 *
 * @param target the target's short name, for example {@code example-com}
 * @param protocol the HTTP version curl used, for example {@code HTTP/2}
 * @param kind whether curl opened a new connection for this request
 * @param status the HTTP status code, always 2xx
 * @param sizeBytes the response body's size in bytes
 * @param phases the five phases and the total
 * @param serverTiming the response's {@code Server-Timing} header, or an empty string
 */
public record CurlSample(
    String target,
    String protocol,
    ConnectionKind kind,
    int status,
    long sizeBytes,
    PhaseTimings phases,
    String serverTiming) {
```

- One request as curl measured it. The record has seven components: target name, protocol, connection kind, status, size, the phases (a `PhaseTimings`), and the `Server-Timing` text.

```java
  /** A target name: lower-case letters, digits and hyphens, at most 40 characters. */
  public static final Pattern TARGET_NAME = Pattern.compile("[a-z0-9-]{1,40}");

  private static final int FIRST_SUCCESS_STATUS = 200;
  private static final int FIRST_NON_SUCCESS_STATUS = 300;
```

- `TARGET_NAME` is `[a-z0-9-]{1,40}`: lower-case letters, digits and hyphens, from 1 to 40 characters. It is `public` because `CurlLine` checks the name too.
- 200 and 300 bound the successful status codes.

```java
  /**
   * Creates the sample.
   *
   * @throws IllegalArgumentException if the name, the status or the size is invalid
   */
  public CurlSample {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(protocol, "protocol");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(phases, "phases");
    Objects.requireNonNull(serverTiming, "serverTiming");
    if (!TARGET_NAME.matcher(target).matches()) {
      throw new IllegalArgumentException("invalid target name: " + target);
    }
    if (status < FIRST_SUCCESS_STATUS || status >= FIRST_NON_SUCCESS_STATUS) {
      throw new IllegalArgumentException("only a 2xx response is a timing, but was " + status);
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("the size cannot be negative");
    }
  }
}
```

- The compact constructor checks the nulls, the name, that the status is 2xx (a redirect, a 404 or a 500 is not a timing of the seat map), and that the size is not negative.

### `report/CurlProblem.java`

```java
package io.plainround.seatwave.proxy.report;

/** Why one line of curl output did not become a {@link CurlSample}. */
public sealed interface CurlProblem {
```

- `sealed interface CurlProblem` is a **sealed interface**: a closed family. Only the types listed (or, when no list is written, the types in the same file) may implement it. Sealed types arrived in Java 17.

```java
  /**
   * Says what went wrong, for the report's list of refused lines.
   *
   * @return one line of text
   */
  String describe();
```

- `String describe();` is the one method every problem must have: a line of text for the list of refused lines.

```java
  /**
   * The line is not in the timing script's format.
   *
   * @param line the line, cut to its first 80 characters
   * @param reason what is missing or invalid
   */
  record Malformed(String line, String reason) implements CurlProblem {
    @Override
    public String describe() {
      return "malformed line (" + reason + "): " + line;
    }
  }
```

- `record Malformed(String line, String reason) implements CurlProblem`: the line is not in the script's format. The line is cut to 80 characters by `CurlLine`. A record nested in an interface is implicitly `public` and `static`.
- `@Override` marks `describe()` as the implementation of the interface method.

```java
  /**
   * The line is well formed, but its numbers cannot be a timing.
   *
   * @param target the target's name
   * @param reason which rule the line breaks
   */
  record Rejected(String target, String reason) implements CurlProblem {
    @Override
    public String describe() {
      return "rejected run of " + target + ": " + reason;
    }
  }
}
```

- `Rejected`: the line is well formed but its numbers cannot be a timing (a failed request, a bad status, a negative phase).

### `report/TargetTiming.java`

```java
package io.plainround.seatwave.proxy.report;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
```

- Imports: `Comparator` for ordering, `Duration`, `List`, `Objects`.

```java
/**
 * One row of the timing report: every run of one target over one kind of connection.
 *
 * <p>The row reports its median run, chosen by total time, and that run's own phases. Because the
 * row's numbers all come from one real run, its phases still sum to its total; medians taken phase
 * by phase would not. A row cannot exist with fewer than {@link Runs#MINIMUM_RUNS} runs.
 *
 * @param target the target's name
 * @param kind new or reused connections
 * @param runs the runs, at least {@link Runs#MINIMUM_RUNS}, all of this target and kind
 */
public record TargetTiming(String target, ConnectionKind kind, List<CurlSample> runs) {
```

- One row of the phase table. The comment states the key idea: the row's numbers all come from **one real run**, so its phases still sum to its total. Medians taken phase by phase would not.

```java
  /**
   * Creates the row.
   *
   * @throws IllegalArgumentException if there are too few runs, or a run belongs to another row
   */
  public TargetTiming {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(kind, "kind");
    runs = List.copyOf(runs);
    Runs.requireEnough(runs);
    for (CurlSample run : runs) {
      if (!run.target().equals(target) || run.kind() != kind) {
        throw new IllegalArgumentException("a run of " + run.target() + " is not in this row");
      }
    }
  }
```

- The constructor refuses fewer than 3 runs and refuses any run that belongs to another target or another connection kind. `!run.target().equals(target)` compares text by content. `run.kind() != kind` compares enum values with `!=`, which is correct for enums.

```java
  /**
   * Returns the median run by total time.
   *
   * @return the run whose phases the report prints
   */
  public CurlSample medianRun() {
    return Runs.medianBy(runs, Comparator.comparing(run -> run.phases().total()));
  }
```

- `Comparator.comparing(run -> run.phases().total())` builds a comparator from a function: it orders runs by their total time. `run -> ...` is a **lambda**. `medianBy` then picks the middle run.

```java
  /**
   * Returns the shortest total of all runs.
   *
   * @return the fastest run's total
   */
  public Duration fastest() {
    return runs.stream()
        .map(run -> run.phases().total())
        .min(Comparator.naturalOrder())
        .orElseThrow();
  }
```

- `fastest()`: take each run's total, find the smallest with `min(Comparator.naturalOrder())`. `min` returns an `Optional` (there might be no items), and `orElseThrow()` unwraps it, or throws if empty. Since the row has at least 3 runs, it is never empty.

```java
  /**
   * Returns the longest total of all runs.
   *
   * @return the slowest run's total
   */
  public Duration slowest() {
    return runs.stream()
        .map(run -> run.phases().total())
        .max(Comparator.naturalOrder())
        .orElseThrow();
  }
}
```

- `slowest()` is the same with `max`.

### `report/PhaseTimings.java`

```java
package io.plainround.seatwave.proxy.report;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
```

- `Result` is from `result4j`. `Optional` is a box that holds one value or nothing.

```java
/**
 * The five phases of one request and its total, as measured by one run.
 *
 * <p>The invariant of the timing report lives here, where no code path can go around it: no phase
 * is negative, and the five phases add up to the total within {@link #SUM_TOLERANCE}. A value that
 * breaks either rule cannot be created, so no report row can hold it.
 *
 * @param dns the DNS phase
 * @param connect the TCP connect phase
 * @param tls the TLS handshake phase, zero for plain HTTP or a reused connection
 * @param firstByte from the end of the handshakes to the first response byte
 * @param transfer from the first response byte to the last
 * @param total the whole request, as the timing tool reported it
 */
public record PhaseTimings(
    Duration dns,
    Duration connect,
    Duration tls,
    Duration firstByte,
    Duration transfer,
    Duration total) {
```

- The class comment: the invariant of the timing report lives here, where no code path can go around it. **Invariant** means a rule that must always be true. Here: no phase is negative, and the five phases add up to the total within a tolerance. A value that breaks either rule cannot be created.
- The record has six components: five phases and the total.

```java
  /**
   * How far the sum of the phases may be from the total. Curl reports each clock reading to the
   * microsecond, so a clean run sums to its total within a few microseconds; a gap of a millisecond
   * means the readings are not from one clean transfer.
   */
  public static final Duration SUM_TOLERANCE = Duration.ofMillis(1);
```

- `SUM_TOLERANCE` is 1 ms. curl reports each reading to the microsecond, so a clean run sums to its total within a few microseconds. A gap of a millisecond means the readings are not from one clean transfer.

```java
  /**
   * Creates the timings.
   *
   * @throws IllegalArgumentException if a phase is negative or the phases do not sum to the total
   */
  public PhaseTimings {
    Optional<String> problem = problemWith(dns, connect, tls, firstByte, transfer, total);
    if (problem.isPresent()) {
      throw new IllegalArgumentException(problem.get());
    }
  }
```

- The compact constructor calls `problemWith(...)`, which returns an `Optional<String>`. If it holds a message, the constructor throws `IllegalArgumentException`. Building the record directly is therefore either valid or an exception.

```java
  /**
   * Checks the timings and creates them, or says what is wrong.
   *
   * @param dns the DNS phase
   * @param connect the TCP connect phase
   * @param tls the TLS phase
   * @param firstByte the first-byte phase
   * @param transfer the transfer phase
   * @param total the total
   * @return the timings, or the reason they break the invariant
   */
  public static Result<PhaseTimings, String> of(
      Duration dns,
      Duration connect,
      Duration tls,
      Duration firstByte,
      Duration transfer,
      Duration total) {
    Optional<String> problem = problemWith(dns, connect, tls, firstByte, transfer, total);
    if (problem.isPresent()) {
      return Result.error(problem.get());
    }
    return Result.success(new PhaseTimings(dns, connect, tls, firstByte, transfer, total));
  }
```

- `of(...)` is the **non-throwing** way to build one: it returns a `Result`. It checks first with the same method, and returns `Result.error(problem.get())` or `Result.success(new PhaseTimings(...))`. Code that handles input from a file uses this one: bad input is an expected failure, so it comes back as a value.
- This method and the one below are both called `of`. Java allows that because their parameters differ (**overloading**).

```java
  /**
   * Returns one phase.
   *
   * @param phase the phase
   * @return its duration
   */
  public Duration of(Phase phase) {
    return switch (phase) {
      case DNS -> dns;
      case CONNECT -> connect;
      case TLS -> tls;
      case FIRST_BYTE -> firstByte;
      case TRANSFER -> transfer;
    };
  }
```

- `of(Phase phase)` returns one phase's duration. The `switch` expression names each enum constant and has no `default`, so adding a sixth phase would stop the build until this method handles it.

```java
  /**
   * Returns the longest phase: the one to look at first.
   *
   * @return the dominant phase; on a tie, the earlier one
   */
  public Phase dominant() {
    Phase longest = Phase.DNS;
    for (Phase phase : Phase.values()) {
      // Strictly longer only, so a tie keeps the earlier phase.
      if (of(phase).compareTo(of(longest)) > 0) {
        longest = phase;
      }
    }
    return longest;
  }
```

- `dominant()` finds the longest phase. It starts with `DNS` and walks `Phase.values()` in order, replacing the current best only when a phase is **strictly** longer. A tie therefore keeps the earlier phase.
- `compareTo(...) > 0` is how `Duration` says "longer than".

```java
  private static Optional<String> problemWith(
      Duration dns,
      Duration connect,
      Duration tls,
      Duration firstByte,
      Duration transfer,
      Duration total) {
    Objects.requireNonNull(dns, "dns");
    Objects.requireNonNull(connect, "connect");
    Objects.requireNonNull(tls, "tls");
    Objects.requireNonNull(firstByte, "firstByte");
    Objects.requireNonNull(transfer, "transfer");
    Objects.requireNonNull(total, "total");
    List<Duration> phases = List.of(dns, connect, tls, firstByte, transfer);
    if (phases.stream().anyMatch(Duration::isNegative) || total.isNegative()) {
      return Optional.of("a phase is negative: the clock readings are out of order");
    }
    Duration sum = phases.stream().reduce(Duration.ZERO, Duration::plus);
    if (sum.minus(total).abs().compareTo(SUM_TOLERANCE) > 0) {
      return Optional.of(
          "the phases sum to "
              + sum.toNanos()
              + " ns, but the total is "
              + total.toNanos()
              + " ns");
    }
    return Optional.empty();
  }
}
```

- `problemWith(...)` holds both rules. It null-checks all six arguments.
- `List.of(dns, connect, tls, firstByte, transfer)` collects the five phases. `anyMatch(Duration::isNegative)` is true if any is negative. `Duration::isNegative` is a method reference.
- `phases.stream().reduce(Duration.ZERO, Duration::plus)` adds them: start from zero and add each phase in turn.
- `sum.minus(total).abs().compareTo(SUM_TOLERANCE) > 0` asks: is the gap between the sum and the total larger than 1 ms? If so, the message names both numbers in nanoseconds.
- `Optional.empty()` means no problem.
- A note for the careful reader: for a line read by `CurlLine`, the phases are built as differences of consecutive readings, so they sum to the total by construction and only the negative-phase rule can fail. The sum rule protects a `PhaseTimings` that is built any other way.

### `report/CurlLine.java`

```java
package io.plainround.seatwave.proxy.report;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
```

- `BigDecimal` is a number with exact decimal digits. `HashMap` and `Map` hold key-value pairs.

```java
/**
 * Parses one line that {@code scripts/curl-timings.sh} writes with {@code curl -w}.
 *
 * <p>The line holds curl's cumulative clock readings, each in seconds from the start of the
 * transfer: {@code dns=} (time_namelookup), {@code connect=} (time_connect), {@code tls=}
 * (time_appconnect), {@code ttfb=} (time_starttransfer) and {@code total=} (time_total). A phase is
 * the difference between two readings, and curl writes 0 for a step that did not happen, such as
 * the TLS handshake of plain HTTP or both handshakes of a reused connection. Thread-safe: it holds
 * no state.
 */
public final class CurlLine {

  private static final List<String> REQUIRED_KEYS =
      List.of("dns", "connect", "tls", "ttfb", "total", "http", "code", "size", "connects");
  // The Server-Timing header can hold spaces, so it is the last field and takes the rest.
  private static final String SERVER_TIMING_FIELD = " server=";
  private static final int SHOWN_LINE_CHARACTERS = 80;
  private static final int NANOS_DIGITS = 9;
  private static final int FIRST_SUCCESS_STATUS = 200;
  private static final int LAST_SUCCESS_STATUS = 299;

  private CurlLine() {}
```

- The class comment lists the keys and what each means (the list above).
- `REQUIRED_KEYS` lists the nine keys every line must have.
- The `server=` field can hold spaces (for example `cdn-cache; desc=HIT, edge; dur=1`, which the unit test uses), so it is cut off first and takes the rest of the line.
- `SHOWN_LINE_CHARACTERS = 80` bounds how much of a bad line is echoed. `NANOS_DIGITS = 9` is the number of decimal places between seconds and nanoseconds.

```java
  /**
   * Parses one line.
   *
   * @param line for example {@code example-com dns=0.002 connect=0.020 tls=0.045 ttfb=0.071
   *     total=0.072 http=2 code=200 size=528 connects=1 server=}
   * @return the checked sample, or why the line is malformed or cannot be a timing
   */
  public static Result<CurlSample, CurlProblem> parse(String line) {
    Objects.requireNonNull(line, "line");
    int serverAt = line.indexOf(SERVER_TIMING_FIELD);
    if (serverAt < 0) {
      return malformed(line, "no server= field");
    }
    String serverTiming = line.substring(serverAt + SERVER_TIMING_FIELD.length()).strip();
    String[] tokens = line.substring(0, serverAt).strip().split(" +");
    String target = tokens[0];
    if (!CurlSample.TARGET_NAME.matcher(target).matches()) {
      return malformed(line, "invalid target name");
    }
    Map<String, String> fields = new HashMap<>();
    for (int tokenIndex = 1; tokenIndex < tokens.length; tokenIndex++) {
      String[] pair = tokens[tokenIndex].split("=", 2);
      if (pair.length != 2 || fields.put(pair[0], pair[1]) != null) {
        return malformed(line, "invalid or repeated field " + tokens[tokenIndex]);
      }
    }
    for (String key : REQUIRED_KEYS) {
      if (!fields.containsKey(key)) {
        return malformed(line, "no " + key + "= field");
      }
    }
    try {
      return sample(target, fields, serverTiming);
    } catch (NumberFormatException | ArithmeticException notANumber) {
      // A number that does not parse is bad input, so it is a returned problem, not a crash.
      return malformed(line, "a number is invalid");
    }
  }
```

- `parse` returns `Result<CurlSample, CurlProblem>`: a good sample or one problem. Nothing here throws for bad input.
- `line.indexOf(" server=")` finds where the server field starts; `-1` means it is missing, so the line is malformed.
- `line.substring(serverAt + SERVER_TIMING_FIELD.length()).strip()` is the server timing text. `line.substring(0, serverAt).strip().split(" +")` cuts the rest at runs of spaces. The first token is the target name, checked against the allowed pattern.
- The loop takes each remaining token `key=value`. `split("=", 2)` cuts at the first equals sign into at most two parts. `fields.put(key, value)` stores the pair and returns the old value for that key, or `null`: `!= null` therefore catches a **repeated** key. A token without `=` (`pair.length != 2`) is invalid too.
- The second loop checks that every required key is present.
- `try { return sample(...); } catch (NumberFormatException | ArithmeticException notANumber)` turns a number that does not parse into a returned problem, not a crash. `NumberFormatException` comes from `Integer.parseInt` and `new BigDecimal(...)`; `ArithmeticException` from `longValueExact`.

```java
  private static Result<CurlSample, CurlProblem> sample(
      String target, Map<String, String> fields, String serverTiming) {
    String protocol =
        switch (fields.get("http")) {
          case "1.1" -> "HTTP/1.1";
          case "2" -> "HTTP/2";
          case "3" -> "HTTP/3";
          default -> "";
        };
    if (protocol.isEmpty()) {
      return Result.error(new CurlProblem.Rejected(target, "no HTTP version: the request failed"));
    }
    int status = Integer.parseInt(fields.get("code"));
    if (status < FIRST_SUCCESS_STATUS || status > LAST_SUCCESS_STATUS) {
      return Result.error(new CurlProblem.Rejected(target, "status " + status + " is not 2xx"));
    }
    long sizeBytes = Long.parseLong(fields.get("size"));
    if (sizeBytes < 0) {
      return Result.error(new CurlProblem.Rejected(target, "the size is negative"));
    }
    Duration dnsEnd = seconds(fields.get("dns"));
    Duration connectReading = seconds(fields.get("connect"));
    Duration tlsReading = seconds(fields.get("tls"));
    // A reading of 0 means the step did not happen, so its phase ends where the last one ended.
    Duration connectEnd = connectReading.isZero() ? dnsEnd : connectReading;
    Duration tlsEnd = tlsReading.isZero() ? connectEnd : tlsReading;
    Duration firstByteEnd = seconds(fields.get("ttfb"));
    Duration total = seconds(fields.get("total"));
    Result<PhaseTimings, String> phases =
        PhaseTimings.of(
            dnsEnd,
            connectEnd.minus(dnsEnd),
            tlsEnd.minus(connectEnd),
            firstByteEnd.minus(tlsEnd),
            total.minus(firstByteEnd),
            total);
    ConnectionKind kind =
        Integer.parseInt(fields.get("connects")) == 0 ? ConnectionKind.REUSED : ConnectionKind.NEW;
    return switch (phases) {
      case Result.Success<PhaseTimings, String>(PhaseTimings checked) ->
          Result.success(
              new CurlSample(target, protocol, kind, status, sizeBytes, checked, serverTiming));
      case Result.Error<PhaseTimings, String>(String reason) ->
          Result.error(new CurlProblem.Rejected(target, reason));
    };
  }
```

- `switch (fields.get("http")) { case "1.1" -> "HTTP/1.1"; ... default -> ""; }` maps curl's version text to a name. A **switch on a string** compares by content. An empty or unknown version (curl writes nothing when the request failed) gives `""`, and the line is rejected with "no HTTP version: the request failed".
- The status must be 2xx and the size not negative, or the line is rejected.
- `seconds(...)` converts each reading to a `Duration`. `connectReading.isZero() ? dnsEnd : connectReading` is the "zero means did not happen" rule: a reused connection's phase ends where the last one ended. The TLS line does the same, relative to `connectEnd`.
- The five phases are differences: `connectEnd.minus(dnsEnd)`, `tlsEnd.minus(connectEnd)`, `firstByteEnd.minus(tlsEnd)`, `total.minus(firstByteEnd)`. They are passed to `PhaseTimings.of`, which returns a `Result`.
- `ConnectionKind kind = Integer.parseInt(fields.get("connects")) == 0 ? REUSED : NEW`: curl's `num_connects` is how many new connections this request opened.
- The final `return switch (phases) { ... }` examines the `Result` with record patterns: a good set of phases becomes a `CurlSample`, and a bad one becomes a `Rejected` problem with the reason text.

```java
  private static Duration seconds(String text) {
    // BigDecimal keeps curl's microseconds exact; a double could round 0.000001 away.
    long nanos = new BigDecimal(text).movePointRight(NANOS_DIGITS).longValueExact();
    return Duration.ofNanos(nanos);
  }
```

- `new BigDecimal(text)` reads `0.026455` exactly. `movePointRight(9)` multiplies by 10^9, so it is `26455000`. `longValueExact()` converts to a `long`, and throws `ArithmeticException` if there would be a fraction (more than 9 decimals) or an overflow. A `double` would round some values: `BigDecimal` keeps curl's microseconds exact.
- `Duration.ofNanos(nanos)` makes the duration.

```java
  private static Result<CurlSample, CurlProblem> malformed(String line, String reason) {
    String shown =
        line.length() > SHOWN_LINE_CHARACTERS ? line.substring(0, SHOWN_LINE_CHARACTERS) : line;
    return Result.error(new CurlProblem.Malformed(shown, reason));
  }
}
```

- `malformed` cuts the line to 80 characters (so a huge line is not echoed) and returns `Result.error(new CurlProblem.Malformed(shown, reason))`.


### `report/TimingReport.java`

```java
package io.plainround.seatwave.proxy.report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
```

- `LinkedHashMap` is a map that remembers the order in which keys were first added, so targets are listed in the order they first appear. `Map` is the general type. `ArrayList` and `List` hold lists. `Locale` fixes the number format. `Duration` is a length of time. `Objects` has the null check.

```java
/**
 * Writes the phase table of the request timing report as plain text (Module 5's report).
 *
 * <p>The phase table takes checked curl samples, groups them by target and kind of connection, and
 * prints each group's median run. A group with too few runs is listed as refused, never printed as
 * a row. Thread-safe: it holds no state.
 */
public final class TimingReport {
```

- The class comment: it writes the phase table, and a group with too few runs is "listed as refused, never printed as a row". The sibling repository's version of this class also wrote a second table; this one has only the phase table.

```java
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double PERCENT = 100.0;
  private static final String PHASE_HEADER =
      "target            connection protocol  runs   dns ms connect ms   tls ms first byte ms"
          + " transfer ms   total ms   fastest  slowest  dominant";
  private static final String PHASE_ROW =
      "%-17s %-10s %-9s %4d %8.2f %10.2f %8.2f %13.2f %11.2f %10.2f %9.2f %8.2f  %s%n";
```

- `NANOS_PER_MILLI` and `PERCENT` are the unit constants.
- `PHASE_HEADER` is the header line, split with `+` to fit the line width (Java joins the pieces at compile time).
- `PHASE_ROW` is a **format string**. `%-17s` is a text padded to 17 characters, left-justified. `%-10s` and `%-9s` are the same with 10 and 9. `%4d` is a whole number in 4 characters. `%8.2f` is a decimal number in 8 characters with 2 digits after the point. `%s` is a text; `%n` is a line break. The widths line up the columns under the header.

```java
  private TimingReport() {}
```

- The private constructor stops anyone from creating an object of this all-static class.

```java
  /**
   * Groups samples into rows, keeping the order in which each target first appears.
   *
   * @param samples checked samples
   * @return one row per target and kind with enough runs, and one line per refused group
   */
  public static Grouped group(List<CurlSample> samples) {
    Map<String, List<CurlSample>> groups = new LinkedHashMap<>();
    for (CurlSample sample : samples) {
      groups
          .computeIfAbsent(sample.target() + " " + sample.kind(), key -> new ArrayList<>())
          .add(sample);
    }
```

- `group` puts samples into groups. The key is the target name plus the kind (`local-https NEW`). `computeIfAbsent(key, k -> new ArrayList<>())` returns the list for that key, creating an empty list the first time; then `.add(sample)`.

```java
    List<TargetTiming> rows = new ArrayList<>();
    List<String> refused = new ArrayList<>();
    for (List<CurlSample> group : groups.values()) {
      CurlSample first = group.getFirst();
      if (group.size() < Runs.MINIMUM_RUNS) {
        refused.add(
            String.format(
                Locale.ROOT,
                "too few runs for %s over a %s connection: %d, and a row needs %d",
                first.target(),
                first.kind().name().toLowerCase(Locale.ROOT),
                group.size(),
                Runs.MINIMUM_RUNS));
        continue;
      }
      rows.add(new TargetTiming(first.target(), first.kind(), group));
    }
    return new Grouped(rows, refused);
  }
```

- Each group becomes a row, or a refusal. A group of fewer than 3 samples adds a line to `refused` made with `String.format(...)` and `continue` skips to the next group. Otherwise `new TargetTiming(first.target(), first.kind(), group)`. `group.getFirst()` takes the first item.
- The method returns a `Grouped` record with the rows and the refusals.

```java
  /**
   * Writes the phase table, one sentence per row, and every refused line or group.
   *
   * @param samples checked samples
   * @param problems the lines that did not become samples
   * @return the text of the table
   */
  public static String renderPhases(List<CurlSample> samples, List<CurlProblem> problems) {
    Objects.requireNonNull(problems, "problems");
    Grouped grouped = group(samples);
    StringBuilder text = new StringBuilder(PHASE_HEADER).append(System.lineSeparator());
```

- `renderPhases` builds the text with a `StringBuilder`, starting with the header and a line separator (`System.lineSeparator()`, the line break of the operating system).

```java
    for (TargetTiming row : grouped.rows()) {
      CurlSample median = row.medianRun();
      PhaseTimings phases = median.phases();
      text.append(
          String.format(
              Locale.ROOT,
              PHASE_ROW,
              row.target(),
              row.kind().name().toLowerCase(Locale.ROOT),
              median.protocol(),
              row.runs().size(),
              millis(phases.dns()),
              millis(phases.connect()),
              millis(phases.tls()),
              millis(phases.firstByte()),
              millis(phases.transfer()),
              millis(phases.total()),
              millis(row.fastest()),
              millis(row.slowest()),
              phases.dominant().label()));
    }
```

- For each row it takes the **median run** and its phases, and formats one line with the format string and the values: target, kind in lower case, protocol, the number of runs, the five phases and the total in milliseconds, the fastest and slowest totals, and the label of the dominant phase.
- `Locale.ROOT` makes sure the decimal separator is a point, whatever the language of the computer.

```java
    text.append(System.lineSeparator());
    for (TargetTiming row : grouped.rows()) {
      text.append(sentence(row)).append(System.lineSeparator());
    }
    for (String refusedGroup : grouped.refused()) {
      text.append("refused: ").append(refusedGroup).append(System.lineSeparator());
    }
    for (CurlProblem problem : problems) {
      text.append("refused: ").append(problem.describe()).append(System.lineSeparator());
    }
    return text.toString();
  }
```

- A blank line, then one sentence per row, then each refused group and each refused line, with the prefix `refused: `.

```java
  private static String sentence(TargetTiming row) {
    PhaseTimings phases = row.medianRun().phases();
    Phase dominant = phases.dominant();
    double dominantMillis = millis(phases.of(dominant));
    double totalMillis = millis(phases.total());
    double share = totalMillis == 0 ? 0 : PERCENT * dominantMillis / totalMillis;
    return String.format(
        Locale.ROOT,
        "%s, %s connection: %s dominates the median run, %.2f of %.2f ms (%.0f percent).",
        row.target(),
        row.kind().name().toLowerCase(Locale.ROOT),
        dominant.label(),
        dominantMillis,
        totalMillis,
        share);
  }
```

- `sentence` writes the one-line summary: which phase dominates the median run, how many milliseconds, and what percentage of the total. `share` guards against dividing by zero with `totalMillis == 0 ? 0 : ...`. `%.0f` prints the percentage with no decimals.

```java
  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
```

- `millis` converts a `Duration` to milliseconds as a `double`: `toNanos()` divided by 1,000,000.0.

```java
  /**
   * The result of grouping.
   *
   * @param rows the rows with enough runs
   * @param refused one line per group with too few runs
   */
  public record Grouped(List<TargetTiming> rows, List<String> refused) {

    /** Copies both lists. */
    public Grouped {
      rows = List.copyOf(rows);
      refused = List.copyOf(refused);
    }
  }
}
```

- The `Grouped` record holds the rows and the refusals. Its compact constructor copies both lists with `List.copyOf`, which makes them unmodifiable.

### `report/ReportCli.java`

```java
package io.plainround.seatwave.proxy.report;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
```

- `Files.readAllLines` reads a whole text file into a list of lines. `StandardCharsets` names the encoding. `PrintStream` is a stream with `println` and `print`. `Result` is from `result4j`.

```java
/**
 * Command-line entry point of the report: reads the files {@code scripts/curl-timings.sh} wrote and
 * prints the phase table. Exit status 2 means bad usage or an unreadable file; 1 means at least one
 * line or group was refused, and the table says which.
 */
public final class ReportCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_REFUSED_LINES = 1;

  private ReportCli() {}
```

- The class comment gives the exit statuses. An **exit status** is the number a program returns to the shell when it ends: 0 means success and anything else means failure. 2 is the shell's usual "used incorrectly"; here 2 means bad usage or an unreadable file, and 1 means at least one line or group was refused.
- The two statuses are `static final` constants with package visibility, so the test can compare against them.

```java
  /**
   * Prints the report.
   *
   * @param arguments one or more files of curl timing lines
   */
  public static void main(String[] arguments) {
    System.exit(run(arguments, System.out, System.err));
  }
```

- `main` is the method Java calls at start-up. It does nothing but call `run(arguments, System.out, System.err)` and pass the result to `System.exit`. Because `run` returns the status instead of exiting, a test can call it and read the answer.

```java
  /**
   * Prints the report and returns the exit status.
   *
   * @param arguments the command line
   * @param out where the table is printed
   * @param err where problems are printed
   * @return 0, 1 or 2, as the class documentation says
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length == 0) {
      err.println("usage: report <curl-timings.txt>...");
      return EXIT_BAD_USAGE;
    }
```

- `run` takes the output streams as parameters (`out` for the table, `err` for problems). It needs at least one file name; otherwise it prints the usage and returns 2.
- It makes two lists: the good samples and the problems.

```java
    List<CurlSample> samples = new ArrayList<>();
    List<CurlProblem> problems = new ArrayList<>();
    for (String argument : arguments) {
      try {
        for (String line : Files.readAllLines(Path.of(argument), StandardCharsets.UTF_8)) {
          if (line.isBlank() || line.startsWith("#")) {
            continue;
          }
          switch (CurlLine.parse(line)) {
            case Result.Success<CurlSample, CurlProblem>(CurlSample sample) -> samples.add(sample);
            case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) ->
                problems.add(problem);
          }
        }
      } catch (IOException unreadable) {
        err.println("cannot read " + argument + ": " + unreadable.getMessage());
        return EXIT_BAD_USAGE;
      }
    }
```

- For each file it reads the lines. A blank line or a line that starts with `#` is skipped, so a file may have comments. `continue` jumps to the next line.
- `switch (CurlLine.parse(line))` with record patterns: a good result adds a sample, an error adds a problem. Each case is a single statement, so it needs no braces.
- `catch (IOException unreadable)` prints which file could not be read and returns 2. A file that cannot be read stops the whole run, even if earlier files were fine.

```java
    out.print(TimingReport.renderPhases(samples, problems));
    if (!problems.isEmpty() || !TimingReport.group(samples).refused().isEmpty()) {
      return EXIT_REFUSED_LINES;
    }
    return 0;
  }
```

- After all files, it prints the table with `out.print(TimingReport.renderPhases(samples, problems))`. If anything was refused (`!problems.isEmpty()`, or a group with too few runs, found by `TimingReport.group(samples).refused()`), it returns 1. Otherwise 0. The table is printed first, so a person sees both the good rows and the refusals.

## Try it yourself

All commands run in the repository folder. Numbers differ on every run and on every computer. The writer did not run `scripts/curl-timings.sh` (that script belongs to the Docker part of the work); it made the same kind of lines by hand with `curl`.

**1. Start SeatWave on this machine.** In one terminal: `./gradlew -q testCertificates`, then `./gradlew -q serve --args='config/backend-local.properties'`. The writer used a copy of that file with the ports 20980 to 20982, because other work on the machine used 20930 to 20932, so the address below says 20980 where you would write 20930.

**2. Make a file of real curl lines.** Run curl with two requests in one command, so that the second reuses the connection. The format is the one `scripts/curl-timings.sh` uses (curl 7.84 or newer, for `%header{...}`). This writes five pairs of lines into a file in `build/`, which git ignores:

```text
mkdir -p build
F='local-https dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total} http=%{http_version} code=%{http_code} size=%{size_download} connects=%{num_connects} encoding=%header{content-encoding} cache=%header{x-cache-status} server=%header{server-timing}\n'
U='https://127.0.0.1:20930/events/1/seats?section=A'
for i in 1 2 3 4 5; do curl -sS --cacert build/tls/seatwave-test-ca.pem -w "$F" -o /dev/null "$U" -o /dev/null "$U" >> build/sample-curl.txt; done
cat build/sample-curl.txt
```

The first line of each pair has `connects=1` and the second `connects=0`. The first pair looked like this:

```text
local-https dns=0.000089 connect=0.000346 tls=0.005237 ttfb=0.005640 total=0.006046 http=1.1 code=200 size=36894 connects=1 encoding= cache= server=app;dur=0.14
local-https dns=0.000009 connect=0.000000 tls=0.000000 ttfb=0.000286 total=0.000636 http=1.1 code=200 size=36894 connects=0 encoding= cache= server=app;dur=0.08
```

**3. Print the table.**

```text
./gradlew -q report --args=build/sample-curl.txt
```

```text
target            connection protocol  runs   dns ms connect ms   tls ms first byte ms transfer ms   total ms   fastest  slowest  dominant
local-https       new        HTTP/1.1     5     0.06       0.19     3.43          0.36        0.40       4.43      4.17     6.05  tls
local-https       reused     HTTP/1.1     5     0.01       0.00     0.00          0.28        0.35       0.64      0.59     0.73  transfer

local-https, new connection: tls dominates the median run, 3.43 of 4.43 ms (77 percent).
local-https, reused connection: transfer dominates the median run, 0.35 of 0.64 ms (55 percent).
```

Check by hand: the five new-connection totals sorted are 4.170, 4.244, 4.430, 4.792 and 6.046 ms. The median index is `(5 - 1) / 2 = 2`, so the median run is the 4.430 ms one, and the row prints that run's own phases (the table in "How it works underneath"). The slow first run (6.05 ms) is the "slowest" column and does not move the median.

**4. Feed it three bad lines.** Copy the file, then append a failed request (`http=` empty), a line that is not a timing at all, and a line whose connect reading is before its DNS reading:

```text
cp build/sample-curl.txt build/sample-bad.txt
printf 'local-https dns=0.000010 connect=0.000000 tls=0.000000 ttfb=0.000000 total=0.000000 http= code=000 size=0 connects=0 server=\nthis is not a timing line\nlocal-https dns=0.5 connect=0.1 tls=0.000000 ttfb=0.001000 total=0.002000 http=1.1 code=200 size=10 connects=1 server=\n' >> build/sample-bad.txt
./gradlew -q report --args=build/sample-bad.txt
```

After the same table, the program printed:

```text
refused: rejected run of local-https: no HTTP version: the request failed
refused: malformed line (no server= field): this is not a timing line
refused: rejected run of local-https: a phase is negative: the clock readings are out of order
```

The command then exits with status 1 and Gradle prints its own failure text. Delete the files in `build/` afterwards.

**5. Run it with no file.**

```text
./gradlew -q report
```

```text
usage: report <curl-timings.txt>...
```

The program exits with status 2.

**6. Run the unit tests.**

```text
./gradlew test --tests '*CurlLineTest' --tests '*TimingReportTest' --tests '*PhaseTimingsTest' --tests '*ReportCliTest'
```

Stop SeatWave with Ctrl-C when you are done.

## Mistakes people make

- **Taking the median of each phase separately.** The row would not add up to its total. This code takes one real run.
- **Reporting a mean.** One slow run drags it. The median is the rule.
- **Reporting fewer than 3 runs.** `Runs.requireEnough` and the record constructors refuse.
- **Trusting a zero reading.** A zero means the step did not happen. The code carries the previous reading forward and never subtracts a zero.
- **Using a `double` for seconds.** `0.000001` can be rounded away. `BigDecimal` keeps curl's microseconds exact.
- **Throwing for bad input.** A line a person or a failed curl produced is an expected failure, so it returns a `Result` and is listed. Exceptions are kept for programmer errors.
- **Printing the table only on success.** The table is printed even when lines are refused, followed by the refusals, so no bad line is silently dropped.
- **Reading a Mac's `localhost` numbers as network numbers.** In the README's table, the connection to a published port crosses Docker Desktop's port forwarder, and the README says so. Compare rows of the same table with each other, not with a different machine's.

## Check yourself

1. Why does the phase table print one real run instead of the median of each column? (The columns of a real run add up to its total; medians taken column by column do not.)
2. What does `connect=0.000000` mean, and how does `CurlLine` treat it? (The step did not happen, for example on a reused connection. The phase ends where the last one ended, so it is 0 long, never negative.)
3. For four runs, which index does `Runs.medianBy` take, and why is that a real value? (`(4 - 1) / 2 = 1`, the lower middle run: a value that was really measured, not an average.)
4. What are the two ways a `PhaseTimings` can be refused? (A negative phase, or phases that do not add up to the total within 1 ms.)
5. Which exit status means "some lines were refused", and which means "used incorrectly"? (1 for refused lines or groups, 2 for bad usage or an unreadable file.)

## Further reading

- curl documentation: `--write-out` variables (`time_namelookup`, `time_connect`, `time_appconnect`, `time_starttransfer`, `time_total`), https://curl.se/docs/manpage.html#-w
- Java SE 25 API: `java.math.BigDecimal`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/math/BigDecimal.html
- Java Language Specification: sealed classes and interfaces (Java SE 25), https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.1.6
- Java Language Specification: pattern matching for `switch` (Java SE 25), https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.11
- result4j 1.2.0, https://github.com/sviperll/result4j
