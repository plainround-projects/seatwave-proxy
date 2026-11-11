# The `logs` package, line by line (part 2 of 2 of the report and logs notes)

This is part 2 of 2. Part 1 (`java-report-logs-walkthrough-1.md`) read the `report` package. Part 2 reads the `logs` package: how a log file of the proxy becomes a table of operations numbers.

## What it is

The `logs` package reads a copy of NGINX's access log and prints one row per listening port. Each row says how many requests the proxy handled, how many it passed on to SeatWave, how often SeatWave answered with a server error, how often the cache had the answer, and how much time the proxy itself added.

The package has three files: `AccessLine` (one parsed log line), `LogStats` (turns a list of lines into the table) and `LogStatsCli` (the command).

An **access log** is a file in which a web server writes one line for every request it answered. NGINX writes it as it goes, using a format the configuration file defines.

## Why this repository uses it

A benchmark shows what a path can do under a made-up load. The access log shows what a proxy really did, request by request. Module 9 asks for both: the numbers of the operator, not only of the tester. The log lines carry the status the client got (`status`), the status SeatWave sent (`us`), the whole request time (`rt`), SeatWave's part of it (`urt`), and whether the cache answered (`cache`). From these the program computes the **time the proxy added** (the request time less SeatWave's time), the **cache hit ratio**, and the **share of SeatWave 5xx answers**, which are the first numbers an operator watches.

The README captured a run over a 1,584,174-line log from the lab. It shows, for example, port 8443 (PRODUCTION, published on the host as 20900) with 507,455 requests, 5.4 percent cache hits overall and 100 percent for static files, and port 8445 (the lab without upstream reuse, published as 20904) with 22.65 percent of its passed-on requests ending in a 5xx. The log's `us` field also holds a 502 that NGINX itself makes when it cannot reach SeatWave, so the column "SeatWave 5xx %" counts those too.

## Where it appears

| File | What it does |
|---|---|
| `logs/AccessLine.java` | Parses one line of the `seatwave` log format into a record. |
| `logs/LogStats.java` | Groups lines by port and computes the table. |
| `logs/LogStatsCli.java` | `main`: reads a file, counts unreadable lines, prints the table. |

The log format itself is defined in `nginx/nginx.conf`. The Gradle task `logStats` runs the command: `./gradlew -q logStats --args='build/stack/access.log'`. The access log lives inside the proxy container; the README copies it out first with `docker exec seatwave-proxy-proxy-1 cat /var/log/nginx/seatwave/access.log > build/stack/access.log`.

## How it works underneath

**The log format.** These lines of `nginx/nginx.conf` define what NGINX writes for every request:

```java
    log_format seatwave 'time=$time_iso8601 port=$server_port status=$status us=$upstream_status '
                        'rt=$request_time urt=$upstream_response_time uct=$upstream_connect_time '
                        'cache=$upstream_cache_status bytes=$bytes_sent id=$request_id '
                        'client=$remote_addr method=$request_method uri=$uri';
```

Each `$name` is a variable that NGINX fills in per request:

| Variable | Log key | Meaning |
|---|---|---|
| `$time_iso8601` | `time` | When the request ended. |
| `$server_port` | `port` | The port of the proxy that answered. Each lab variant has its own port, so the port names the variant. |
| `$status` | `status` | The status the client received. |
| `$upstream_status` | `us` | The status SeatWave (the **upstream**) sent; `-` if SeatWave was not asked. |
| `$request_time` | `rt` | Seconds from the first byte of the request to the last byte sent to the client, with millisecond resolution. |
| `$upstream_response_time` | `urt` | Seconds SeatWave took to answer; `-` if not asked. |
| `$upstream_connect_time` | `uct` | Seconds to connect to SeatWave. This package does not use it. |
| `$upstream_cache_status` | `cache` | `HIT`, `MISS`, `BYPASS` or another cache state; `-` if the request did not use the cache. |
| `$bytes_sent` | `bytes` | Bytes sent to the client. Not used here. |
| `$request_id` | `id` | A random id for the request. Not used here. |
| `$remote_addr` | `client` | The client's address. Not used here. |
| `$request_method` | `method` | `GET`, `POST`. Not used here. |
| `$uri` | `uri` | The path, last, so that it can be taken as the rest of the line. |

**A line looks like this** (a line from the unit test):

```text
time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=200 rt=0.004 urt=0.001 uct=0.000 cache=MISS bytes=300473 id=cdcc client=10.209.9.1 method=GET uri=/static/poster-arena.bin
```

**Retries and lists.** When NGINX tries more than one upstream server or retries, `$upstream_status` and `$upstream_response_time` hold one value per attempt, separated by `, ` (and by ` : ` after an internal redirect). A line can be `us=502, 502 urt=0.001, 0.001`. The parser joins those pieces first, so that every key has one word.

**What "added by the proxy" means.** It is `rt - urt`. `rt` runs until the last byte has been written to the client. So it includes the time the proxy spent sending the answer to the client, which for a slow client is the client's time, not the proxy's. `urt` runs until SeatWave's answer is read. NGINX writes these times with millisecond resolution (`0.001`), so a request that the proxy handled in 0.4 ms can show `rt=0.000` or `rt=0.001`. In the README table the "proxy-added p50" is 0.00 and the p99 is 1.00 for every port: the proxy added less than a millisecond for most requests and at most one tick of the log's clock for 99 in 100. Do not read 1.00 as a measured 1.000 ms.

**What counts as "asked SeatWave".** A request where `urt` is not `-`. A cache hit has `urt=-`, so it is not counted as asked, and its proxy time is not part of the added-time numbers.

**Java words new in this part.** `Map<String, String>` (a table of key-value pairs) and `HashMap`; `TreeMap` (a map kept in key order); `computeIfAbsent`; streams with `filter`, `map`, `sorted`, `count`, `toList`; a lambda `line -> ...`; the method reference `AccessLine::askedUpstream`; `BigDecimal` (exact decimal numbers) and `longValueExact`; a **multi-catch** `catch (A | B e)` handles either exception type; `Files.readAllLines`.

## Walkthrough of this repository's use

### `logs/AccessLine.java`

```java
package io.plainround.seatwave.proxy.logs;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
```

- `Result` is `result4j`'s success-or-error type. `BigDecimal` reads decimal numbers exactly. `Duration`, `HashMap`, `Map`, `List`, `Objects` are the standard helpers.

```java
/**
 * One line of the proxy's access log, in the {@code seatwave} format of {@code nginx/nginx.conf}:
 * {@code key=value} pairs separated by spaces, with the request's URI last.
 *
 * @param port the proxy's listening port, which names the lab variant
 * @param status the status the proxy sent the client
 * @param upstreamStatus the status SeatWave sent, or 0 when SeatWave was not asked (a cache hit)
 * @param requestTime from the first byte of the request to the last byte of the response
 * @param upstreamTime SeatWave's part of it, or {@link Duration#ZERO} when SeatWave was not asked
 * @param cacheStatus {@code HIT}, {@code MISS}, {@code BYPASS} and so on, or an empty string
 * @param askedUpstream whether the proxy sent the request on to SeatWave
 * @param uri the request's path
 */
public record AccessLine(
    int port,
    int status,
    int upstreamStatus,
    Duration requestTime,
    Duration upstreamTime,
    String cacheStatus,
    boolean askedUpstream,
    String uri) {
```

- The record has eight components, documented in the Javadoc: `port`, `status`, `upstreamStatus` (0 when SeatWave was not asked), `requestTime`, `upstreamTime` (zero when not asked), `cacheStatus` (an empty text when the cache was not used), `askedUpstream` and `uri`.
- A cache `HIT` has no upstream, so `upstreamStatus` is 0 and `askedUpstream` is false.

```java
  private static final List<String> REQUIRED =
      List.of("port", "status", "us", "rt", "urt", "cache");
  private static final String URI_FIELD = " uri=";
  private static final String NONE = "-";
  private static final int NANOS_DIGITS = 9;
```

- `REQUIRED` lists the six keys a line must have: `port`, `status`, `us`, `rt`, `urt`, `cache`. The others in the log are ignored.
- `URI_FIELD` is the text `" uri="` with a space in front, the marker of the last field. `NONE` is `"-"`, the log's text for "no value". `NANOS_DIGITS = 9` is the number of decimal places between seconds and nanoseconds.

```java
  /** Checks that nothing is missing. */
  public AccessLine {
    Objects.requireNonNull(requestTime, "requestTime");
    Objects.requireNonNull(upstreamTime, "upstreamTime");
    Objects.requireNonNull(cacheStatus, "cacheStatus");
    Objects.requireNonNull(uri, "uri");
  }
```

- The compact constructor checks that the four objects exist.

```java
  /**
   * Returns the time the proxy itself added: the whole request less SeatWave's part.
   *
   * @return the difference, never below zero
   */
  public Duration proxyAdded() {
    Duration added = requestTime.minus(upstreamTime);
    return added.isNegative() ? Duration.ZERO : added;
  }
```

- `proxyAdded()` is `requestTime.minus(upstreamTime)`, never below zero (`added.isNegative() ? Duration.ZERO : added`). With the log's millisecond resolution, `urt` can round up past `rt`, which would give a negative time.

```java
  /**
   * Parses one line.
   *
   * @param line for example {@code port=8443 status=200 us=200 rt=0.002 urt=0.001 cache=MISS
   *     uri=/static/seat-map.js}
   * @return the line, or why it cannot be read
   */
  public static Result<AccessLine, String> parse(String line) {
    Objects.requireNonNull(line, "line");
    int uriAt = line.indexOf(URI_FIELD);
    if (uriAt < 0) {
      return Result.error("no uri= field");
    }
```

- `parse(line)` returns `Result<AccessLine, String>`: the parsed line or a sentence that says why not. A bad line is expected input (a half-written line at the end of a log, for instance), so it is a value, not an exception.
- `line.indexOf(" uri=")` finds the start of the last field. `-1` means there is none, and the line is refused with "no uri= field". The path is last because a path can hold anything, and everything after `" uri="` is the path.

```java
    Map<String, String> fields = new HashMap<>();
    // NGINX lists one value per upstream attempt as "502, 200", and per internal redirect with
    // " : "; both are joined to "502,200" first, so every field is one token.
    String joined = line.substring(0, uriAt).replace(", ", ",").replace(" : ", ",");
    for (String token : joined.strip().split(" +")) {
      String[] pair = token.split("=", 2);
      if (pair.length == 2) {
        fields.put(pair[0], pair[1]);
      }
    }
```

- `fields` collects the key-value words before the path.
- `line.substring(0, uriAt)` is the part before the path. `.replace(", ", ",")` and `.replace(" : ", ",")` join lists such as `502, 502` into `502,502`, so each key is one word.
- `joined.strip().split(" +")` cuts at runs of spaces. Each word is cut at the first `=` (`split("=", 2)`) into a key and a value; a word without `=` is skipped.

```java
    for (String key : REQUIRED) {
      if (!fields.containsKey(key)) {
        return Result.error("no " + key + "= field");
      }
    }
```

- Every required key must be present, or the line is refused with a sentence naming the missing key.

```java
    try {
      boolean asked = !fields.get("urt").equals(NONE);
      return Result.success(
          new AccessLine(
              Integer.parseInt(fields.get("port")),
              Integer.parseInt(fields.get("status")),
              asked ? lastNumber(fields.get("us")) : 0,
              seconds(fields.get("rt")),
              asked ? summedSeconds(fields.get("urt")) : Duration.ZERO,
              fields.get("cache").equals(NONE) ? "" : fields.get("cache"),
              asked,
              line.substring(uriAt + URI_FIELD.length()).strip()));
    } catch (NumberFormatException | ArithmeticException notANumber) {
      return Result.error("a number is invalid");
    }
  }
```

- `asked` is true unless `urt` is the text `-`.
- The `AccessLine` is built from: the port and the status as whole numbers (`Integer.parseInt`), the last upstream status (`lastNumber`) if asked, else 0, the request time in seconds, the sum of the upstream times if asked, the cache status (empty if `-`), the `asked` flag, and the path (the text after `" uri="`, stripped).
- `catch (NumberFormatException | ArithmeticException notANumber)` returns "a number is invalid". `NumberFormatException` comes from the `parseInt` calls and from `new BigDecimal`; `ArithmeticException` from `longValueExact` when a time has more than nine decimals. A bad number is a refused line, not a crash.

```java
  private static int lastNumber(String text) {
    // After a retry NGINX lists one status per attempt, "502, 200": the last one is what was sent.
    String[] parts = text.split(",");
    return parts[parts.length - 1].equals(NONE) ? 0 : Integer.parseInt(parts[parts.length - 1]);
  }
```

- `lastNumber("502,200")` splits at the comma and takes the last part: the status that SeatWave's final attempt sent. The comment says why: after a retry, "the last one is what was sent". A `-` in the last place gives 0.
- This means a request that was retried and then succeeded counts as a success in the 5xx percentage, even though SeatWave failed once.

```java
  private static Duration summedSeconds(String text) {
    Duration sum = Duration.ZERO;
    for (String part : text.split(",")) {
      if (!part.equals(NONE)) {
        sum = sum.plus(seconds(part));
      }
    }
    return sum;
  }
```

- `summedSeconds("0.001,0.001")` adds the time of every attempt that has one (skipping `-`), so the total upstream time of a retried request is the sum of the attempts.

```java
  private static Duration seconds(String text) {
    // NGINX writes these times with millisecond resolution, "0.001"; BigDecimal keeps them exact.
    return Duration.ofNanos(new BigDecimal(text).movePointRight(NANOS_DIGITS).longValueExact());
  }
}
```

- `seconds("0.001")` reads the text with `new BigDecimal`, which holds the digits exactly, multiplies by a billion with `movePointRight(9)`, and converts to a `long` with `longValueExact()`, which throws if the value is not a whole number of nanoseconds. A `double` could round some values; the comment says why `BigDecimal` is used.

### `logs/LogStats.java`

```java
package io.plainround.seatwave.proxy.logs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
```

- `TreeMap` keeps its keys sorted, so the rows come out in port order.

```java
/**
 * Turns access log lines into the proxy's operations numbers, per listening port: requests, the
 * share SeatWave answered with a 5xx, the cache hit ratio over every lookup and over static files
 * alone, and the time the proxy itself added at p50 and p99. Thread-safe: it holds no state.
 */
public final class LogStats {
```

- The class comment lists the columns: requests, the share of SeatWave 5xx, the cache hit ratio overall and for static files alone, and the added time at p50 and p99.

```java
  /** The table's header line. */
  public static final String HEADER =
      "port   requests  to SeatWave  SeatWave 5xx %  cache lookups  hit %  static hit %"
          + "  proxy-added p50 ms  proxy-added p99 ms";

  private static final int FIRST_SERVER_ERROR = 500;
  private static final String STATIC_PREFIX = "/static/";
  private static final double PERCENT = 100.0;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
```

- `HEADER` is the table's first line, in two pieces joined with `+`.
- `FIRST_SERVER_ERROR = 500` is the first status counted as a server error. `STATIC_PREFIX = "/static/"` is the path that marks static files. The rest are the unit constants and the two percentile shares.

```java
  private LogStats() {}

  /**
   * Writes one row per port.
   *
   * @param lines the parsed lines
   * @return the table
   */
  public static String render(List<AccessLine> lines) {
    Objects.requireNonNull(lines, "lines");
    Map<Integer, List<AccessLine>> byPort = new TreeMap<>();
    for (AccessLine line : lines) {
      byPort.computeIfAbsent(line.port(), port -> new ArrayList<>()).add(line);
    }
    StringBuilder text = new StringBuilder(HEADER).append(System.lineSeparator());
    byPort.forEach((port, group) -> text.append(row(port, group)));
    return text.toString();
  }
```

- `render(lines)` first groups the lines by port. `byPort.computeIfAbsent(line.port(), port -> new ArrayList<>()).add(line)` returns the list for that port, creating it the first time, and adds the line.
- It then writes the header and one row per port: `byPort.forEach((port, group) -> text.append(row(port, group)))`. A `TreeMap` is walked in key order.

```java
  private static String row(int port, List<AccessLine> group) {
    List<AccessLine> asked = group.stream().filter(AccessLine::askedUpstream).toList();
    long serverErrors =
        asked.stream().filter(line -> line.upstreamStatus() >= FIRST_SERVER_ERROR).count();
    List<AccessLine> lookups =
        group.stream().filter(line -> !line.cacheStatus().isEmpty()).toList();
    long hits = lookups.stream().filter(line -> line.cacheStatus().equals("HIT")).count();
    List<AccessLine> staticLookups =
        lookups.stream().filter(line -> line.uri().startsWith(STATIC_PREFIX)).toList();
    long staticHits =
        staticLookups.stream().filter(line -> line.cacheStatus().equals("HIT")).count();
    List<Duration> added = asked.stream().map(AccessLine::proxyAdded).sorted().toList();
```

- `row` computes the numbers of one port. `asked` is the lines that went on to SeatWave (`AccessLine::askedUpstream`). `serverErrors` counts the asked lines whose upstream status is 500 or more.
- `lookups` is the lines that used the cache (a non-empty cache status). `hits` is those with status `HIT`.
- `staticLookups` is the lookups whose path starts with `/static/`; `staticHits` is the hits among them.
- `added` is the proxy-added time of each asked line, sorted, ready for the percentiles.

```java
    return String.format(
        Locale.ROOT,
        "%-6d %8d %12d %15s %14d %6s %13s %19.2f %19.2f%n",
        port,
        group.size(),
        asked.size(),
        asked.isEmpty()
            ? "-"
            : String.format(Locale.ROOT, "%.2f", PERCENT * serverErrors / asked.size()),
        lookups.size(),
        share(hits, lookups.size()),
        share(staticHits, staticLookups.size()),
        millis(percentile(added, PERCENTILE_50)),
        millis(percentile(added, PERCENTILE_99)));
  }
```

- One `String.format` writes the row. `%-6d` is the port left-aligned in 6 characters, `%8d` the number of requests, `%12d` the number asked, `%15s` the 5xx percentage (a text, because it may be `-`), `%14d` the lookups, `%6s` the hit percentage, `%13s` the static hit percentage, and `%19.2f` twice for the two percentiles in milliseconds.
- The 5xx percentage is `100 * serverErrors / asked.size()`, or `-` if nothing was asked. The percentage is over the requests that were passed on, not over all requests: cache hits are not in the denominator.
- The cache columns are over the lookups (all lines that used the cache), not over all requests.

```java
  private static String share(long part, long whole) {
    return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f", PERCENT * part / whole);
  }
```

- `share(part, whole)` is the percentage with one decimal, or `-` if the whole is zero.

```java
  static Duration percentile(List<Duration> sorted, double share) {
    if (sorted.isEmpty()) {
      return Duration.ZERO;
    }
    int rank = (int) Math.ceil(share * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }
```

- `percentile` is the nearest-rank rule on durations: `ceil(share * n)`, counted from 1. For 3 values, p50 is position 2 and p99 is position 3.

```java
  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
```

- `millis` converts a `Duration` to milliseconds.

### `logs/LogStatsCli.java`

```java
package io.plainround.seatwave.proxy.logs;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
```

- `Files` and `Path` read the file; `StandardCharsets` names the encoding; `PrintStream` is the output type; `Result` is the result type.

```java
/**
 * Command-line entry point of the log statistics: reads a copy of the proxy's access log and prints
 * one row per listening port. Exit status 2 means bad usage or an unreadable file; 1 means some
 * lines could not be read, and they are counted.
 */
public final class LogStatsCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_UNREADABLE_LINES = 1;

  private LogStatsCli() {}
```

- The exit statuses: 2 for bad usage or an unreadable file, 1 when some lines could not be read (they are counted), 0 otherwise.

```java
  /**
   * Prints the statistics.
   *
   * @param arguments the log file
   */
  public static void main(String[] arguments) {
    System.exit(run(arguments, System.out, System.err));
  }
```

- `main` calls `run` and exits with its result.

```java
  /**
   * Prints the statistics and returns the exit status.
   *
   * @param arguments the command line
   * @param out where the table is printed
   * @param err where problems are printed
   * @return 0, 1 or 2, as the class documentation says
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 1) {
      err.println("usage: logStats <access.log>");
      return EXIT_BAD_USAGE;
    }
```

- `run` needs exactly one argument: the log file. `lines` collects the parsed lines; `unreadable` counts the lines that could not be parsed.

```java
    List<AccessLine> lines = new ArrayList<>();
    int unreadable = 0;
    try {
      for (String text : Files.readAllLines(Path.of(arguments[0]), StandardCharsets.UTF_8)) {
        switch (AccessLine.parse(text)) {
          case Result.Success<AccessLine, String>(AccessLine line) -> lines.add(line);
          case Result.Error<AccessLine, String>(String reason) -> unreadable++;
        }
      }
    } catch (IOException missing) {
      err.println("cannot read the log " + arguments[0] + ": " + missing.getMessage());
      return EXIT_BAD_USAGE;
    }
```

- `Files.readAllLines(Path.of(arguments[0]), UTF_8)` reads the whole file into memory as a list of text lines. For the README's 1,584,174-line log that is a large list; the program holds the lines, and then all the parsed records, in memory at once.
- `switch (AccessLine.parse(text))` takes the result apart: a good line is added; an unreadable one adds 1 to the counter. The reason text is not shown, only counted.
- `catch (IOException missing)` prints that the log cannot be read and returns 2.

```java
    out.print(LogStats.render(lines));
    if (unreadable > 0) {
      out.println(unreadable + " line(s) could not be read");
      return EXIT_UNREADABLE_LINES;
    }
    return 0;
  }
}
```

- The table is printed first. If any line was unreadable, one more line says how many, and the status is 1; the table above it is still printed. Otherwise 0.
- An empty line in the middle of a file counts as an unreadable line.

## Try it yourself

All commands run in the repository folder. Numbers differ on every run and on every computer. The access log of the lab lives in the proxy container, which these notes did not run (it needs Docker), so the commands use a small log written by hand in the same format.

**1. Write a log of five lines.** Save these lines as `build/mini-access.log` (the `build` folder is ignored by git). They are the unit test's lines plus two more:

```text
time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=200 rt=0.004 urt=0.001 uct=0.000 cache=MISS bytes=300473 id=cdcc client=10.209.9.1 method=GET uri=/static/poster-arena.bin
time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=- rt=0.000 urt=- uct=- cache=HIT bytes=300463 id=ea17 client=10.209.9.1 method=GET uri=/static/poster-arena.bin
time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=- rt=0.000 urt=- uct=- cache=HIT bytes=300463 id=ea18 client=10.209.9.1 method=GET uri=/static/poster-arena.bin
time=2026-11-11T04:22:48+00:00 port=8447 status=502 us=502, 502 rt=0.003 urt=0.001, 0.001 uct=- cache=- bytes=335 id=23dd client=10.209.9.1 method=GET uri=/lab/client
time=2026-11-11T04:22:48+00:00 port=8447 status=200 us=200 rt=0.002 urt=0.001 uct=- cache=- bytes=335 id=23de client=10.209.9.1 method=GET uri=/lab/client
```

**2. Print the table.**

```text
./gradlew -q logStats --args='build/mini-access.log'
```

```text
port   requests  to SeatWave  SeatWave 5xx %  cache lookups  hit %  static hit %  proxy-added p50 ms  proxy-added p99 ms
8443          3            1            0.00              3   66.7          66.7                3.00                3.00
8447          2            2           50.00              0      -             -                1.00                1.00
```

Check by hand. Port 8443 has 3 requests; only the first went to SeatWave, and two were cache hits, so 2 of 3 lookups are hits: 66.7 percent, and all three are static. The one asked request has `rt - urt = 0.004 - 0.001 = 3 ms`, so p50 and p99 are both 3.00. Port 8447 has 2 requests, both asked. The first was answered `502` twice, so its last upstream status is 502: 1 of 2 is 50.00 percent. Its added time is `0.003 - (0.001 + 0.001) = 1 ms`; the second is `0.002 - 0.001 = 1 ms`.

**3. Add an unreadable line.**

```text
echo garbage >> build/mini-access.log
./gradlew -q logStats --args='build/mini-access.log'
```

The same table is printed, followed by:

```text
1 line(s) could not be read
```

The command exits with status 1.

**4. Name a file that does not exist.**

```text
./gradlew -q logStats --args='build/none.log'
```

```text
cannot read the log build/none.log: build/none.log
```

The message repeats the path because the error message of the missing file is the path. The command exits with status 2. With no argument it prints `usage: logStats <access.log>`.

**5. Run the unit tests.**

```text
./gradlew test --tests '*LogStatsTest'
```

## Mistakes people make

- **Reading `1.00` as one millisecond.** The log has millisecond resolution. A proxy-added figure of 0.00 and 1.00 means "under a tick" and "one tick". Measure with the bench if you need microseconds.
- **Taking proxy-added time for the proxy's own work.** It includes the time to send the answer to the client. A slow client inflates it.
- **Computing the hit ratio over all requests.** The columns are over the cache lookups only, and `hit %` and `static hit %` have different denominators.
- **Counting only the last status.** A retried 502 that ended in 200 is not in the 5xx percentage. The proxy hid a failure there; the `us=502, 200` lines are where to look.
- **Reading the whole log into memory.** The README's log has more than 1.5 million lines; `readAllLines` holds them all. A log of a busy week would not fit. A streaming reader would use constant memory. This program reads files that the lab produces, which are small enough.
- **Assuming blank lines are ignored.** They count as unreadable lines and make the exit status 1.

## Check yourself

1. What is `rt - urt`, and what does it include besides the proxy's work? (The request time less SeatWave's time; it includes the time to send the answer to the client.)
2. How does the parser treat `us=502, 200`? (It joins the list to `502,200`, takes the last number, 200, as the status; the upstream time is the sum of the attempts.)
3. Why is the path the last field of the log format? (It is read as the rest of the line after `" uri="`; other fields are single words.)
4. What is the denominator of the `SeatWave 5xx %` column, and of `hit %`? (The requests that were passed on to SeatWave; the cache lookups.)
5. Which exit status means that some lines were unreadable, and does the table still print? (1; yes, the table prints first and then the count of unreadable lines.)

## Further reading

- NGINX documentation: `ngx_http_log_module` (`log_format`, `access_log`), https://nginx.org/en/docs/http/ngx_http_log_module.html
- NGINX documentation: variables of `ngx_http_upstream_module` (`$upstream_status`, `$upstream_response_time`, `$upstream_cache_status`), https://nginx.org/en/docs/http/ngx_http_upstream_module.html#variables
- NGINX documentation: core variables (`$request_time`, `$request_id`), https://nginx.org/en/docs/http/ngx_http_core_module.html#variables
- Java SE 25 API: `java.math.BigDecimal`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/math/BigDecimal.html
- Java SE 25 API: `java.nio.file.Files#readAllLines`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#readAllLines(java.nio.file.Path,java.nio.charset.Charset)
