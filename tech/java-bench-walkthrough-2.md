# The `bench` package, part 2: sending requests, reading the answers, reading the counters

This is part 2 of 2. Part 1 (`java-bench-walkthrough-1.md`) read the settings, the checked value types and the report table. Part 2 reads the four files that do the work: `RawResponse` (reads an HTTP answer byte by byte), `ContainerCounters` (asks Docker for a container's processor time and connection counts), `LoadRun` (the load generator itself) and `BenchCli` (the command that ties everything together).

## What it is

`LoadRun` is a **closed-loop load generator**. It starts a fixed number of clients. Each client sends one request, waits for the whole answer, records how long that took, and sends the next, until the time is up. `RawResponse` is a small parser for the HTTP/1.1 answer that a client gets when it opens a new connection for every request. `ContainerCounters` runs the command `docker exec <container> cat /sys/fs/cgroup/cpu.stat /proc/net/snmp` and reads three numbers out of what it prints. `BenchCli` is the program you start: it reads the settings, runs every scenario (a warm-up, then the measured runs), reads the counters before and after each run, and prints the table.

A **socket** is the program's end of a network connection. A **thread** is one line of execution inside a program; many threads run at the same time. A **process** is a program that is running. `docker exec` starts a command inside a running container, and `cat` is a tiny program that prints files.

## Why this repository uses it

Module 9 compares paths: direct to SeatWave, through the proxy, with and without reuse of connections, with and without compression. A comparison is only fair if the same program sends the same requests in the same way every time and counts the same things. Written in the JDK alone, the bench needs nothing installed beyond Java. It reads each container's processor time itself, which common load tools do not. And it can force a new TLS connection for every request, which is how the lab counts TLS handshakes per second.

The repository's architecture note says why it is not an off-the-shelf tool: it "needs only the JDK, reads each container's CPU from its control group, and can open a new TLS connection per request."

## Where it appears

| File | What it does |
|---|---|
| `bench/RawResponse.java` | Parses one whole HTTP/1.1 answer: status, wire bytes, plain bytes after gzip, the `X-Cache-Status` header. Removes chunked framing. |
| `bench/ContainerCounters.java` | Runs `docker exec ... cat` and parses processor time and TCP counters. |
| `bench/LoadRun.java` | Starts the clients, collects their tallies, computes p50 and p99. |
| `bench/BenchCli.java` | The `main` method of the `bench` Gradle task; the exit statuses 0, 1 and 2. |

`BenchCli` is started by `./gradlew -q bench --args='config/bench.properties'`. The task is defined in `build.gradle.kts` and runs the class `io.plainround.seatwave.proxy.bench.BenchCli`.

## How it works underneath

**One request, step by step, with reuse.** The clients share one Java `HttpClient`. The first request of a client opens a TCP connection to the server (one round trip: SYN, SYN-ACK, ACK) and, for HTTPS, does the TLS handshake (one more round trip for TLS 1.3). The answer is read in full. The connection then stays open (**keep-alive**), and the client's next request goes down the same connection, with no handshake. The bench uses `HttpClient.Version.HTTP_1_1`, so the client's connection pool opens one connection for each busy client; HTTP/2 would put many requests on one connection.

**One request, step by step, with a new connection.** The client opens a plain socket, does the TLS handshake itself if the address is `https`, writes the request text, and reads until the server closes. It asks for `Connection: close`, so the server closes after the answer. The next request opens a new socket. Every request pays every handshake, so the throughput of this mode is about handshakes per second.

**What an HTTP/1.1 answer looks like on the wire.** It is text and bytes:

```text
HTTP/1.1 200 OK\r\n
Content-Type: application/json\r\n
Transfer-Encoding: chunked\r\n
\r\n
1f4\r\n
...500 bytes of body...\r\n
0\r\n
\r\n
```

The first line is the **status line**. Then come the **headers**, one per line. A blank line (two line breaks in a row, written `\r\n\r\n`; `\r` is "carriage return" and `\n` is "line feed") ends the headers. Then comes the body. If the server knows the body's length in advance, it sends `Content-Length: n`. If it does not (NGINX does not when it compresses on the fly), it sends the body in **chunks** (`Transfer-Encoding: chunked`): each chunk is its size in hexadecimal, a line break, that many bytes, a line break. A chunk of size `0` ends the body. `RawResponse` removes this framing before it counts bytes.

**Gzip.** The client says `Accept-Encoding: gzip`. The proxy may then answer with `Content-Encoding: gzip`: the body is compressed to save bytes on the wire. The client decompresses it to know the plain size.

**What a control group file looks like.** In a container, the file `/sys/fs/cgroup/cpu.stat` has lines such as `usage_usec 1834567`: the processor time, in microseconds, that all processes of the container have used since it started. The file `/proc/net/snmp` is the kernel's network counters. Its `Tcp:` header line names the columns and the next `Tcp:` line gives the numbers, in the same order:

```text
Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab
Tcp: 1 200 120000 -1 42 314 0 3 7
```

Here `ActiveOpens` is 42 and `PassiveOpens` is 314. The parser finds the column by name, so it does not depend on the column order.

**Virtual threads.** A **virtual thread** is a very cheap thread that the Java runtime schedules itself (Java 21 and later). A program can have thousands, each blocked on a network call, without the memory cost of operating-system threads. `LoadRun` gives each client one.

**A monotonic clock.** `System.nanoTime()` returns nanoseconds from an arbitrary starting point. It never jumps backwards, unlike the wall clock, which can be set. Use it to measure durations.

**Java words new in this part.** `try`-with-resources (`try (X x = ...)` closes `x` at the end, even after an error); `Process` and `ProcessBuilder` (start another program); `Future` (a promise of a result that a thread will compute); `ExecutorService` (a pool that runs tasks); `Callable` (a task that returns a value); a **lambda** `() -> ...` (a short unnamed function); `instanceof` with a variable (`socket instanceof SSLSocket secure` tests the type and names the result in one step); a `switch` over a `Result` with record patterns (see `result4j.md`); an **exception** (an error that jumps out of the code until something catches it: `throw`, `try`, `catch`, `finally`).

## Walkthrough of this repository's use

### `bench/RawResponse.java`

```java
package io.plainround.seatwave.proxy.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
```

- The imports: the byte-array streams read and write memory instead of files; `StandardCharsets` names text encodings; `Arrays` copies arrays; `Locale` fixes case conversion; `Matcher` and `Pattern` are the regular-expression tools; `GZIPInputStream` decompresses gzip.

```java
/**
 * Reads one whole HTTP/1.1 response that a {@code Connection: close} request received: the status,
 * the body as it crossed the wire, and its size after decompression.
 *
 * <p>The body is either delimited by the end of the connection or sent in chunks (RFC 9112, Section
 * 7.1), which NGINX uses when it compresses on the fly and so does not know the length in advance;
 * the chunk framing is removed before bytes are counted. Thread-safe: it holds no state.
 */
final class RawResponse {
```

- The Javadoc says what the class does and cites RFC 9112 (the HTTP/1.1 standard), Section 7.1, for chunked bodies.
- `final class RawResponse` has no `public`: it is visible only inside the `bench` package. "Thread-safe: it holds no state" means that two threads can call it at the same time, because it has no fields that change.

```java
  private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] (\\d{3})( .*)?");
  private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
  private static final int HEX = 16;

  private RawResponse() {}
```

- `STATUS_LINE` matches `HTTP/1.0` or `HTTP/1.1`, a space, three digits (captured in group 1), and then an optional space and reason text. `\\d` in a Java string is the regex `\d`, one digit. `( .*)?` is an optional group. `.matches()` later requires the whole line to fit.
- `HEADER_END` is the four bytes `\r\n\r\n` that end the headers, turned into bytes with `getBytes(StandardCharsets.US_ASCII)`.
- `HEX = 16` is the base for reading chunk sizes.
- The private constructor makes the class a bag of static methods.

```java
  static LoadRun.Answer parse(byte[] whole) throws IOException {
    int headerEnd = indexOf(whole, HEADER_END);
    if (headerEnd < 0) {
      throw new IOException("the response ended before its headers did");
    }
    String[] lines = new String(whole, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n", -1);
    Matcher status = STATUS_LINE.matcher(lines[0]);
    if (!status.matches()) {
      throw new IOException("not an HTTP/1.1 status line: " + lines[0]);
    }
```

- `parse(byte[] whole)` takes everything the server sent, as bytes, and returns a `LoadRun.Answer`. `throws IOException` says it can fail with an input-output error.
- `indexOf(whole, HEADER_END)` is the position of the blank line. If there is none, the answer ended inside its headers, and the method throws `IOException("the response ended before its headers did")`.
- `new String(whole, 0, headerEnd, StandardCharsets.ISO_8859_1)` turns the header bytes into text, one byte per character. `split("\r\n", -1)` cuts it into lines; the `-1` keeps empty trailing pieces.
- `lines[0]` is the status line. If it does not match the pattern, the method throws an error that quotes the line.

```java
    boolean isChunked = false;
    boolean isGzip = false;
    String cacheStatus = "";
    for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
      String line = lines[lineIndex].toLowerCase(Locale.ROOT);
      isChunked |= line.startsWith("transfer-encoding:") && line.contains("chunked");
      isGzip |= line.startsWith("content-encoding:") && line.contains("gzip");
      if (line.startsWith("x-cache-status:")) {
        cacheStatus = lines[lineIndex].substring("x-cache-status:".length()).strip();
      }
    }
```

- `isChunked`, `isGzip` and `cacheStatus` start as false, false and empty.
- The loop looks at each header line in lower case (`toLowerCase(Locale.ROOT)`, because header names are not case-sensitive). `isChunked |= condition` means `isChunked = isChunked | condition`: it becomes true and stays true. A `Transfer-Encoding` header containing `chunked`, or a `Content-Encoding` header containing `gzip`, sets the flag.
- For `X-Cache-Status`, the value is cut from the original line, not the lower-case copy, so `HIT` keeps its capitals; `strip()` removes the spaces around it.

```java
    int bodyStart = headerEnd + HEADER_END.length;
    byte[] body =
        isChunked ? unchunk(whole, bodyStart) : Arrays.copyOfRange(whole, bodyStart, whole.length);
    long plain = isGzip ? gunzippedLength(body) : body.length;
    return new LoadRun.Answer(Integer.parseInt(status.group(1)), body.length, plain, cacheStatus);
  }
```

- The body starts right after the blank line. A chunked body is decoded by `unchunk`; otherwise the body is everything to the end (`Arrays.copyOfRange`), because with `Connection: close` the end of the connection is the end of the body.
- `plain` is the size after decompression if the body is gzip, else the body length.
- `Integer.parseInt(status.group(1))` turns the three digits into a number. The method returns an `Answer(status, wire bytes, plain bytes, cache status)`.
- It does not compare the body with a `Content-Length` header. A server that closes the connection early would still give a "complete" body of fewer bytes.

```java
  private static byte[] unchunk(byte[] whole, int start) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    int position = start;
    while (true) {
      int lineEnd = indexOf(whole, "\r\n".getBytes(StandardCharsets.US_ASCII), position);
      if (lineEnd < 0) {
        throw new IOException("a chunk size line is cut off");
      }
      String sizeText = new String(whole, position, lineEnd - position, StandardCharsets.US_ASCII);
      int semicolon = sizeText.indexOf(';');
```

- `unchunk` walks through the chunks. `ByteArrayOutputStream` collects the body bytes. `position` is where the next chunk-size line starts.
- It finds the end of the size line (`\r\n`). If there is none, it throws `a chunk size line is cut off`.
- `sizeText` is the line, for example `1f4`.

```java
      int size;
      try {
        size =
            Integer.parseInt(
                (semicolon < 0 ? sizeText : sizeText.substring(0, semicolon)).strip(), HEX);
      } catch (NumberFormatException notHex) {
        throw new IOException("a chunk size is not hexadecimal: " + sizeText, notHex);
      }
```

- A chunk size may be followed by `;` and an extension, so the text is cut at the first `;`.
- `Integer.parseInt(text, HEX)` reads base 16. On a bad text, `NumberFormatException` is caught and rethrown as an `IOException` that says the size is not hexadecimal and passes the original exception as the cause.

```java
      position = lineEnd + 2;
      if (size == 0) {
        return body.toByteArray();
      }
      if (position + size > whole.length) {
        throw new IOException("a chunk is cut off");
      }
      body.write(whole, position, size);
      position += size + 2;
    }
  }
```

- `position = lineEnd + 2` moves past the line break. A size of 0 is the last chunk: return the collected bytes. Trailers after it are ignored.
- If the chunk would run past the end of the data, the answer was cut off: error.
- Otherwise the chunk's bytes are appended, and `position += size + 2` skips the data and the line break after it. `while (true)` loops until a `return` or a `throw`.

```java
  static long gunzippedLength(byte[] compressed) throws IOException {
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      return in.transferTo(OutputStream.nullOutputStream());
    }
  }
```

- `gunzippedLength` decompresses and only counts. `GZIPInputStream` wraps the compressed bytes; `transferTo(OutputStream.nullOutputStream())` copies everything into a stream that throws it away and returns how many bytes there were. The `try (...)` closes the stream afterwards. `LoadRun` uses it too, so it is not `private`.

```java
  private static int indexOf(byte[] data, byte[] pattern) {
    return indexOf(data, pattern, 0);
  }

  private static int indexOf(byte[] data, byte[] pattern, int from) {
    for (int start = from; start <= data.length - pattern.length; start++) {
      boolean isMatch = true;
      for (int offset = 0; offset < pattern.length && isMatch; offset++) {
        isMatch = data[start + offset] == pattern[offset];
      }
      if (isMatch) {
        return start;
      }
    }
    return -1;
  }
}
```

- `indexOf(data, pattern, from)` is a plain search for a byte sequence: for each possible start, compare the bytes; stop at the first match; return -1 if there is none. `isMatch` is the flag set false at the first differing byte, which also ends the inner loop. The two-argument version starts at 0.
- The Java standard library has no such search for byte arrays, and the loop is short.

### `bench/ContainerCounters.java`

```java
package io.plainround.seatwave.proxy.bench;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

- `Result` is `result4j`'s success-or-error type. `TimeUnit` names time units for waiting.

```java
/**
 * Reads {@link Counters} from a running container with {@code docker exec}, which needs nothing
 * installed in the image beyond {@code cat}: both the NGINX image and SeatWave's have it.
 *
 * <p>The command is a fixed list of arguments, never a shell line, and the container name is
 * checked against an allow-list first, so no input can change what runs. Thread-safe: it holds only
 * immutable fields.
 */
public final class ContainerCounters {
```

- The class doc is the safety note. The command is "a fixed list of arguments, never a shell line". A **shell line** is one piece of text that a shell program (such as `sh`) cuts into words; text from outside could then add commands, for example `name; rm -rf /`. A list of separate arguments is passed to the program as they are, so nothing can be added. The container name is also checked against an allow-list.

```java
  /** A Compose container name: lower-case letters, digits and hyphens. */
  public static final Pattern CONTAINER_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

  // A reading is a few hundred bytes; docker exec answers in well under this on a working engine.
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
  private static final Pattern CPU_USAGE = Pattern.compile("(?m)^usage_usec (\\d+)$");
  private static final Pattern TCP_LINES =
      Pattern.compile("(?m)^Tcp: ([A-Za-z ]+)\\nTcp: ([-0-9 ]+)$");

  private final List<String> commandPrefix;
```

- `CONTAINER_NAME` matches a Docker Compose container name: a lower-case letter or digit, then up to 62 lower-case letters, digits or hyphens. `BenchSettings` uses it too.
- `COMMAND_TIMEOUT` is 10 seconds. The comment says why: a reading is a few hundred bytes, so a working engine answers in far less.
- `CPU_USAGE` is `(?m)^usage_usec (\d+)$`. `(?m)` means `^` and `$` match at each line start and end. The digits are captured in group 1.
- `TCP_LINES` is `(?m)^Tcp: ([A-Za-z ]+)\nTcp: ([-0-9 ]+)$`: a `Tcp:` line of names, a line break, then a `Tcp:` line of numbers (a minus sign is allowed because `MaxConn` is `-1`). A line starting `TcpExt:` does not match, because the pattern wants `Tcp:` with the colon right after.
- `commandPrefix` is the start of the command: the program name and `exec`.

```java
  /**
   * Creates the reader.
   *
   * @param dockerCommand the docker executable, for example {@code docker}
   */
  public ContainerCounters(String dockerCommand) {
    this.commandPrefix = List.of(Objects.requireNonNull(dockerCommand, "dockerCommand"), "exec");
  }
```

- The constructor builds the prefix `List.of(dockerCommand, "exec")`. In production `BenchCli` passes `"docker"`; the tests pass a small fake script instead. `List.of` makes an unmodifiable list.

```java
  /**
   * Reads one container's counters now.
   *
   * @param container the container's name, for example {@code seatwave-proxy-proxy-1}
   * @return the counters
   * @throws IOException if docker fails, takes too long or prints something unreadable
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public Counters read(String container) throws IOException, InterruptedException {
    if (!CONTAINER_NAME.matcher(container).matches()) {
      throw new IllegalArgumentException("not a container name: " + container);
    }
    List<String> command =
        List.of(
            commandPrefix.get(0),
            commandPrefix.get(1),
            container,
            "cat",
            "/sys/fs/cgroup/cpu.stat",
            "/proc/net/snmp");
```

- `read(container)` refuses a name that does not match the allow-list with `IllegalArgumentException`, before anything runs.
- The command is a list: `docker`, `exec`, the container, `cat`, and two file paths. `cat` with two files prints them one after the other.

```java
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException("docker exec " + container + " did not answer in " + COMMAND_TIMEOUT);
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IOException("docker exec " + container + " failed: " + output.strip());
      }
      return switch (parse(output)) {
        case Result.Success<Counters, String>(Counters counters) -> counters;
        case Result.Error<Counters, String>(String reason) ->
            throw new IOException("cannot read the counters of " + container + ": " + reason);
      };
```

- `new ProcessBuilder(command).redirectErrorStream(true).start()` starts the program. `redirectErrorStream(true)` merges its error output into its normal output, so an error message such as `No such container` is read with the rest.
- `process.waitFor(10000, MILLISECONDS)` waits at most ten seconds and returns false if the program has not ended. Then the method throws an `IOException`.
- It reads all output as UTF-8 text. If the exit status is not 0, the method throws an `IOException` that includes the output: that is where `No such container` comes from.
- The `return switch (parse(output)) { ... }` looks at the `Result`. `case Result.Success<Counters, String>(Counters counters) -> counters` matches the success case and pulls its value out. The error case throws an `IOException` that names the container and the reason.
- The output is read after `waitFor`. This is safe here because the output is a few kilobytes, below the pipe's buffer. A program that printed a megabyte would block on a full pipe and look like a timeout.

```java
    } finally {
      // Released on every path, including the timeout.
      process.destroyForcibly();
    }
  }
```

- `finally` always runs. `process.destroyForcibly()` kills the `docker` program if it is still alive, on every path, including the timeout. The comment says so.

```java
  /**
   * Parses the output of {@code cat /sys/fs/cgroup/cpu.stat /proc/net/snmp}.
   *
   * @param output the two files, one after the other
   * @return the counters, or what was missing
   */
  public static Result<Counters, String> parse(String output) {
    Objects.requireNonNull(output, "output");
    Matcher cpu = CPU_USAGE.matcher(output);
    if (!cpu.find()) {
      return Result.error("no usage_usec line: the control group's cpu.stat was not read");
    }
    Matcher tcp = TCP_LINES.matcher(output);
    if (!tcp.find()) {
      return Result.error("no Tcp lines: /proc/net/snmp was not read");
    }
```

- `parse` is `public static` and takes the text, so the test can call it without Docker. It returns `Result<Counters, String>`.
- No `usage_usec` line: `Result.error("no usage_usec line: ...")`. No `Tcp:` pair of lines: another error. The messages say which file was not read.
- `tcp.group(1)` is the list of column names and `tcp.group(2)` the list of numbers.

```java
    List<String> names = List.of(tcp.group(1).strip().split(" +"));
    List<String> numbers = List.of(tcp.group(2).strip().split(" +"));
    int active = names.indexOf("ActiveOpens");
    int passive = names.indexOf("PassiveOpens");
    if (active < 0 || passive < 0 || names.size() != numbers.size()) {
      return Result.error("the Tcp lines have no ActiveOpens or PassiveOpens column");
    }
    try {
      return Result.success(
          new Counters(
              Long.parseLong(cpu.group(1)),
              Long.parseLong(numbers.get(passive)),
              Long.parseLong(numbers.get(active))));
    } catch (NumberFormatException notANumber) {
      return Result.error("a Tcp counter is not a number");
    }
  }
```

- `strip().split(" +")` cuts at runs of spaces, giving two lists of the same length. `names.indexOf("ActiveOpens")` is the column number, or -1 if there is no such column.
- If either column is missing, or the lists differ in length, the result is an error.
- Otherwise the three numbers are parsed: `Long.parseLong(cpu.group(1))` for processor time, then the `PassiveOpens` and `ActiveOpens` columns. `NumberFormatException` becomes an error value (the message names a "Tcp counter", although it can also be an oversized processor time). The `Counters` constructor rejects negatives.

### `bench/LoadRun.java`

```java
package io.plainround.seatwave.proxy.bench;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
```

- Imports, in groups: `java.io` and `java.net` for streams and sockets; `java.net.http` for the HTTP client; `java.nio.charset` for text encoding; `java.time.Duration`; `java.util` collections; `java.util.concurrent` for threads and futures; `javax.net.ssl` for TLS.

```java
/**
 * A closed-loop load generator: a fixed number of clients, each on its own virtual thread, sends
 * one request, waits for the whole answer, and sends the next, until the run's time is up.
 *
 * <p>A closed loop measures what the path can do at that concurrency: throughput and latency
 * together. In {@link ConnectionMode#REUSE} the clients share one {@link HttpClient} over HTTP/1.1,
 * which keeps one connection per client open. In {@link ConnectionMode#NEW} each request opens a
 * socket, does the TLS handshake if the scheme is https, sends {@code Connection: close} and reads
 * to the end, so each request pays every handshake. Thread-safe: each run uses only its own clients
 * and collections.
 */
public final class LoadRun {
```

- The class comment is the design in one paragraph. Closed loop: "measures what the path can do at that concurrency: throughput and latency together." With reuse, the clients share one `HttpClient`. With new connections, each request is a socket of its own with `Connection: close`.
- "Thread-safe: each run uses only its own clients and collections." Two runs at once would not interfere.

```java
  private static final int FIRST_SUCCESS = 200;
  private static final int FIRST_NON_SUCCESS = 300;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
  private static final int HTTPS_PORT = 443;
  private static final int HTTP_PORT = 80;
  // Long enough for the slowest scenario's response; the workers are told to stop before this.
  private static final Duration JOIN_GRACE = Duration.ofSeconds(30);
```

- 200 and 300 bound the "success" status codes. The two percentile constants are 0.50 and 0.99. 443 and 80 are the default ports of HTTPS and HTTP.
- `JOIN_GRACE` is 30 seconds: how long the program waits for a client to finish after the run's time is up. The comment says it is "long enough for the slowest scenario's response".

```java
  private final SSLContext tls;
  private final Duration connectTimeout;
  private final Duration requestTimeout;

  /**
   * Creates the generator.
   *
   * @param tls trusts the TEST certificate authority only
   * @param connectTimeout the connect timeout of every request
   * @param requestTimeout the whole-request timeout of every request
   */
  public LoadRun(SSLContext tls, Duration connectTimeout, Duration requestTimeout) {
    this.tls = Objects.requireNonNull(tls, "tls");
    this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
  }
```

- The three fields are the TLS settings and the two timeouts. They are `final`: set once, never changed.
- The constructor checks for null and stores them. `this.tls = Objects.requireNonNull(tls, "tls")` checks and assigns in one step, because `requireNonNull` returns its argument.
- `SSLContext tls` holds the trust decision: it trusts the TEST certificate authority only (made by `TlsFiles.clientContext`). A request to a server with any other certificate fails.

```java
  /**
   * Runs one scenario.
   *
   * @param scenario where to send requests, and how
   * @param duration how long the clients keep sending
   * @param clients how many clients send at once, 1 or more
   * @return what the run measured
   * @throws InterruptedException if the thread is interrupted while the run is going on
   * @throws IOException if a client failed in a way that is a bug, not a refused request
   */
  public LoadResult run(Scenario scenario, Duration duration, int clients)
      throws InterruptedException, IOException {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(duration, "duration");
    if (clients < 1) {
      throw new IllegalArgumentException("a run needs at least one client");
    }
```

- `run(scenario, duration, clients)` runs one scenario for a time. It returns a `LoadResult`. It declares `throws InterruptedException, IOException`. `InterruptedException` is what a waiting thread gets if someone asks it to stop.
- The two null checks and the check `clients < 1` come first.

```java
    long startNanos = System.nanoTime();
    long endNanos = startNanos + duration.toNanos();
    List<Future<Tally>> tallies = new ArrayList<>(clients);
    try (HttpClient shared = client();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int clientIndex = 0; clientIndex < clients; clientIndex++) {
        tallies.add(pool.submit(() -> loop(shared, scenario, endNanos)));
      }
      List<Tally> finished = new ArrayList<>(clients);
      for (Future<Tally> tally : tallies) {
        finished.add(await(tally));
      }
      return combine(finished, Duration.ofNanos(System.nanoTime() - startNanos));
    }
  }
```

- `startNanos` is now; `endNanos` is when the clients must stop.
- `try (HttpClient shared = client(); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())` opens two resources and closes both at the end of the block, even after an error. Closing the pool waits for tasks that are still running.
- The `for` loop submits one task per client: `pool.submit(() -> loop(shared, scenario, endNanos))`. The lambda runs `loop` in a new virtual thread and returns its `Tally`. The `Future<Tally>` is the promise of that result, kept in `tallies`.
- Then each future is waited for in turn with `await`, and the finished tallies are combined. `Duration.ofNanos(System.nanoTime() - startNanos)` is how long the run really lasted, which can be slightly longer than the requested time, because each client finishes its last request.

```java
  private HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(tls)
        .connectTimeout(connectTimeout)
        .build();
  }
```

- `client()` builds the shared `HttpClient`: HTTP/1.1 only, the repository's TLS settings, and the connect timeout.

```java
  private Tally loop(HttpClient shared, Scenario scenario, long endNanos) {
    Tally tally = new Tally();
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(scenario.uri()).timeout(requestTimeout).GET();
    if (scenario.acceptsGzip()) {
      builder.header("Accept-Encoding", "gzip");
    }
    HttpRequest request = builder.build();
    while (System.nanoTime() < endNanos && !Thread.currentThread().isInterrupted()) {
      long sentNanos = System.nanoTime();
      try {
        Answer answer =
            scenario.mode() == ConnectionMode.REUSE
                ? reused(shared, request)
                : fresh(scenario, sentNanos + requestTimeout.toNanos());
        tally.record(answer, System.nanoTime() - sentNanos);
      } catch (IOException refused) {
        // A refused or broken request is a measured error, not a reason to stop the run.
        tally.errors++;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return tally;
  }
```

- `loop` is one client's whole life. It makes a `Tally`, builds the request once (`GET`, the timeout, and `Accept-Encoding: gzip` if the scenario asks), then repeats while time remains and the thread has not been interrupted.
- In each turn it notes `sentNanos`, sends the request by `reused` or `fresh` according to the mode, and records the answer and the time it took (`System.nanoTime() - sentNanos`).
- `catch (IOException refused)` counts a failed request as an error and carries on: the comment says "A refused or broken request is a measured error, not a reason to stop the run."
- `catch (InterruptedException interrupted)` sets the thread's interrupt flag again (catching the exception clears it) and lets the loop condition end the loop.
- A client whose connection is refused every time (a stopped server) repeats this at full speed, so the error count can be very large.

```java
  private static Answer reused(HttpClient shared, HttpRequest request)
      throws IOException, InterruptedException {
    HttpResponse<byte[]> response = shared.send(request, HttpResponse.BodyHandlers.ofByteArray());
    byte[] body = response.body();
    boolean isGzip =
        response
            .headers()
            .firstValue("content-encoding")
            .map("gzip"::equalsIgnoreCase)
            .orElse(false);
    return new Answer(
        response.statusCode(),
        body.length,
        isGzip ? RawResponse.gunzippedLength(body) : body.length,
        response.headers().firstValue("x-cache-status").orElse(""));
  }
```

- `reused` sends the request on the shared client and reads the body into a byte array. `shared.send(request, HttpResponse.BodyHandlers.ofByteArray())` waits for the full answer.
- `isGzip` looks at the `content-encoding` header: `firstValue` gives an `Optional<String>`, `.map("gzip"::equalsIgnoreCase)` tests it (`"gzip"::equalsIgnoreCase` is a method reference), and `.orElse(false)` covers a missing header.
- It returns an `Answer` with the status, the wire size, the plain size (decompressed if gzip) and the `x-cache-status` header or an empty string.

```java
  private Answer fresh(Scenario scenario, long deadlineNanos) throws IOException {
    String host = scenario.uri().getHost();
    boolean isHttps = "https".equals(scenario.uri().getScheme());
    int port =
        scenario.uri().getPort() > 0 ? scenario.uri().getPort() : isHttps ? HTTPS_PORT : HTTP_PORT;
    try (Socket socket = isHttps ? tls.getSocketFactory().createSocket() : new Socket()) {
      socket.connect(new InetSocketAddress(host, port), Math.toIntExact(connectTimeout.toMillis()));
      socket.setSoTimeout(Math.toIntExact(requestTimeout.toMillis()));
```

- `fresh` is the new-connection path. The port is the address's port, or 443 or 80 if there is none: `getPort() > 0 ? getPort() : isHttps ? 443 : 80` is a conditional inside a conditional.
- `try (Socket socket = isHttps ? tls.getSocketFactory().createSocket() : new Socket())` makes a TLS-capable socket for `https` and a plain one for `http`, and closes it at the end.
- `socket.connect(new InetSocketAddress(host, port), timeout)` opens the TCP connection, with the connect timeout in milliseconds. `Math.toIntExact` converts a `long` to an `int` and throws if it does not fit. `setSoTimeout` limits how long one read may wait.

```java
      if (socket instanceof SSLSocket secure) {
        SSLParameters parameters = secure.getSSLParameters();
        // The same name and address checks HttpClient makes: the certificate must be for this host.
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        secure.setSSLParameters(parameters);
        secure.startHandshake();
      }
```

- `socket instanceof SSLSocket secure` is true for the TLS socket and gives it the name `secure`. `setEndpointIdentificationAlgorithm("HTTPS")` turns on the check that the certificate is for this host name or address: the comment says it is "the same name and address checks HttpClient makes". Without it, any certificate from the trusted authority would be accepted for any host.
- `secure.startHandshake()` runs the TLS handshake now, so its cost falls inside the timed request.

```java
      String path =
          scenario.uri().getRawPath()
              + (scenario.uri().getRawQuery() == null ? "" : "?" + scenario.uri().getRawQuery());
      // The Host header as HttpClient writes it: with the port, so both modes send the same
      // request.
      String head =
          "GET "
              + path
              + " HTTP/1.1\r\nHost: "
              + scenario.uri().getRawAuthority()
              + "\r\nConnection: close\r\n"
              + (scenario.acceptsGzip() ? "Accept-Encoding: gzip\r\n" : "")
              + "\r\n";
      OutputStream out = socket.getOutputStream();
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      byte[] whole = socket.getInputStream().readAllBytes();
      if (System.nanoTime() > deadlineNanos) {
        throw new IOException("the request took longer than " + requestTimeout);
      }
      return RawResponse.parse(whole);
    }
  }
```

- The request text is built by hand: the line `GET <path> HTTP/1.1`, a `Host:` header, `Connection: close`, and `Accept-Encoding: gzip` if wanted, then a blank line. The path is the raw path plus `?` and the raw query if there is one.
- It is written as ASCII bytes and flushed. `readAllBytes()` reads until the server closes the connection, which `Connection: close` makes it do after the answer.
- The deadline check after the read throws if the whole exchange took longer than the request timeout. `setSoTimeout` alone limits only one read at a time, so a server that trickled bytes could otherwise exceed the timeout.
- `RawResponse.parse(whole)` does the rest.
- The `Host` header is `scenario.uri().getRawAuthority()`: the host and the port as the address writes them, `127.0.0.1:20904` for example. The comment says why: `HttpClient`, which the reused mode uses, sends the port too, so both modes now send the same request. The first version sent the host alone here. NGINX's `$host` drops the port either way, so the lab's numbers did not depend on it; `LoadRunTest.sendsTheSameHostHeaderWithThePortInBothModes` checks it.

```java
  private static Tally await(Future<Tally> tally) throws InterruptedException, IOException {
    try {
      return tally.get(JOIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException stuck) {
      tally.cancel(true);
      throw new IOException("a client did not finish within " + JOIN_GRACE, stuck);
    } catch (ExecutionException failed) {
      throw new IOException("a client failed", failed.getCause());
    }
  }
```

- `await` waits for one client's tally, at most 30 seconds. A timeout cancels the task and throws; a client that threw becomes an `IOException` with the original as its cause. A failure of the client itself would be a bug, not a refused request.

```java
  private static LoadResult combine(List<Tally> tallies, Duration elapsed) {
    List<Long> latencies = new ArrayList<>();
    long errors = 0;
    long wire = 0;
    long plain = 0;
    long hits = 0;
    long lookups = 0;
    for (Tally tally : tallies) {
      latencies.addAll(tally.latencyNanos);
      errors += tally.errors;
      wire += tally.wireBytes;
      plain += tally.plainBytes;
      hits += tally.cacheHits;
      lookups += tally.cacheLookups;
    }
    Collections.sort(latencies);
    return new LoadResult(
        latencies.size(),
        errors,
        elapsed,
        percentile(latencies, PERCENTILE_50),
        percentile(latencies, PERCENTILE_99),
        wire,
        plain,
        hits,
        lookups);
  }
```

- `combine` adds up the tallies: all latencies in one list, sums of the counts.
- `Collections.sort(latencies)` puts the latencies in order, smallest first. The percentiles are read from the sorted list.
- `latencies.size()` is the number of successful requests: only those have a latency.

```java
  /**
   * Returns a percentile of sorted latencies by the nearest-rank rule: the smallest value with at
   * least that share of the values at or below it.
   *
   * @param sortedNanos the latencies, sorted from fastest to slowest
   * @param share for example 0.99
   * @return the percentile, or zero when there are no values
   */
  static Duration percentile(List<Long> sortedNanos, double share) {
    if (sortedNanos.isEmpty()) {
      return Duration.ZERO;
    }
    int rank = (int) Math.ceil(share * sortedNanos.size());
    return Duration.ofNanos(sortedNanos.get(Math.max(rank, 1) - 1));
  }
```

- The nearest-rank rule: the p-th percentile of n values is the value at position `ceil(p * n)` in the sorted list, counting from 1. For 10 values, p50 is position 5 and p99 is position `ceil(9.9) = 10`, the largest. `Math.max(rank, 1) - 1` turns the position into a list index (a list starts at 0) and keeps it from being below 0.
- With few requests, p99 is simply the slowest: with fewer than 100 values the 99th-percentile position is the last one.
- Floating-point care: `0.99 * n` is a `double`. The writer of these notes checked every n from 1 to 200,000 and found no case where `ceil(0.99 * n)` or `ceil(0.5 * n)` differs from the exact integer result.
- The method is `static` and not `private` so that `LoadRunTest` can call it.

```java
  /** One response as a client saw it. */
  record Answer(int status, long wireBytes, long plainBytes, String cacheStatus) {}
```

- `Answer` is a record: one response as a client saw it. It is nested in `LoadRun` and visible in the package, so `RawResponse` can build it.

```java
  /** One client's counts; confined to that client's thread until the run joins it. */
  private static final class Tally {
    private final List<Long> latencyNanos = new ArrayList<>();
    private long errors;
    private long wireBytes;
    private long plainBytes;
    private long cacheHits;
    private long cacheLookups;

    void record(Answer answer, long elapsedNanos) {
      if (answer.status() < FIRST_SUCCESS || answer.status() >= FIRST_NON_SUCCESS) {
        errors++;
        return;
      }
      latencyNanos.add(elapsedNanos);
      wireBytes += answer.wireBytes();
      plainBytes += answer.plainBytes();
      if (!answer.cacheStatus().isEmpty()) {
        cacheLookups++;
        if (answer.cacheStatus().toUpperCase(Locale.ROOT).equals("HIT")) {
          cacheHits++;
        }
      }
    }
  }
```

- `Tally` is one client's counts. It is `private static final` and has plain fields (no `volatile`, no lock). The comment says it is "confined to that client's thread until the run joins it": only one thread writes to it, and `Future.get` makes its fields visible to the thread that reads them afterwards.
- `record` first tests the status. Outside 200 to 299, it counts an error and returns without recording a latency or bytes. A success adds its latency and bytes.
- If the answer carried `X-Cache-Status`, it is a cache lookup; if the value (upper-cased) is `HIT`, it is also a hit.

### `bench/BenchCli.java`

```java
package io.plainround.seatwave.proxy.bench;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Command-line entry point of the bench: runs every scenario of a settings file against the running
 * stack and prints one row per scenario. It reads each container's CPU time and TCP counters before
 * and after every run with {@code docker exec}. Exit status 2 means bad usage or an invalid
 * settings file; 1 means the stack could not be reached or read.
 */
public final class BenchCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_STACK_FAILED = 1;
  private static final double MILLIS_PER_SECOND = 1_000.0;

  private BenchCli() {}
```

- The class comment lists the exit statuses. An **exit status** is the number a program returns to the shell when it ends: 0 means success and anything else means failure. 2 is the usual shell convention for "used incorrectly"; here 2 means bad usage or an invalid settings file, and 1 means the stack could not be reached or read.
- `static final int EXIT_BAD_USAGE = 2` and `EXIT_STACK_FAILED = 1` have package visibility so the test can compare against them.
- `private BenchCli() {}` stops anyone from creating an object.

```java
  /**
   * Runs the bench.
   *
   * @param arguments the settings file, for example {@code config/bench.properties}
   * @throws InterruptedException if the thread is interrupted during a run
   */
  public static void main(String[] arguments) throws InterruptedException {
    System.exit(run(arguments, new ContainerCounters("docker"), System.out, System.err));
  }

  /**
   * Runs the bench and returns the exit status.
   *
   * @param arguments the command line
   * @param counters reads each container's counters
   * @param out where the table is printed
   * @param err where problems are printed
   * @return 0, 1 or 2, as the class documentation says
   * @throws InterruptedException if the thread is interrupted during a run
   */
```

- `main` is the method Java calls when the program starts. It does nothing but call `run` and pass the result to `System.exit`. Splitting the two means the tests call `run` and look at the returned number, and never end the test program.
- `new ContainerCounters("docker")` is the real reader, which runs the `docker` program found on the path.

```java
  static int run(String[] arguments, ContainerCounters counters, PrintStream out, PrintStream err)
      throws InterruptedException {
    if (arguments.length != 1) {
      err.println("usage: bench <bench.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<BenchSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), BenchSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    return bench(loaded.get(), counters, out, err);
  }

  /**
   * Runs every scenario of checked settings and prints the table.
   *
   * @param settings the checked settings
   * @param counters reads each container's counters
   * @param out where the table is printed
   * @param err where problems are printed
   * @return 0, or 1 if the stack could not be reached or read
   * @throws InterruptedException if the thread is interrupted during a run
   */
  static int bench(
      BenchSettings settings, ContainerCounters counters, PrintStream out, PrintStream err)
      throws InterruptedException {
```

- `run(arguments, counters, out, err)` takes the output streams as parameters. `PrintStream` is a stream with `println` and `printf`; in production they are `System.out` and `System.err`, and in tests they are in-memory buffers.
- Exactly one argument is required: the settings file. Otherwise it prints the usage and returns 2.
- `run` checks the command line and the file, then hands the checked settings to `bench`, which does the work. `bench` is package-private (no `public`), so `BenchCliTest` can call it with settings it built itself, pointing at an in-process SeatWave, which `config/bench.properties` (ports 20900 to 20999 only) would not allow.
- `SettingsFiles.load(Path.of(arguments[0]), BenchSettings::from, err)` reads the file, builds the settings, and prints every problem to `err` if it cannot. `BenchSettings::from` is a **method reference**: it passes the method as the function that turns a reader into settings. The result is an `Optional`: a box that holds a value or nothing. Empty means the problems were printed, so return 2.

```java
    try {
      LoadRun runner =
          new LoadRun(
              TlsFiles.clientContext(settings.authorityPem()),
              settings.connectTimeout(),
              settings.requestTimeout());
      out.printf(
          Locale.ROOT,
          "Java %s on %s %s; %d clients over HTTP/1.1; warm-up %.0f s, then %d runs of %.0f s each;"
              + " each row is the median run by rps%n%n",
          Runtime.version(),
          System.getProperty("os.name"),
          System.getProperty("os.arch"),
          settings.clients(),
          settings.warmup().toMillis() / MILLIS_PER_SECOND,
          settings.repeats(),
          settings.measure().toMillis() / MILLIS_PER_SECOND);
```

- A `LoadRun` is made with the TEST authority and the two timeouts. `TlsFiles.clientContext` throws `IOException` if the certificate file is missing: that goes to the `catch` below and gives status 1.
- `out.printf(Locale.ROOT, ...)` prints the first line: Java version (`Runtime.version()`), the operating system name and architecture, the number of clients, the warm-up in seconds, the number and length of the measured runs. `%d` is a whole number, `%s` a text, `%.0f` a decimal with no digits after the point. The line says "each row is the median run by rps" so that a reader knows what a row is.

```java
      out.println(BenchReport.HEADER);
      for (Scenario scenario : settings.scenarios()) {
        out.print(BenchReport.render(measure(scenario, settings, runner, counters)));
      }
      return 0;
    } catch (IOException stackFailed) {
      err.println("the bench stopped: " + stackFailed.getMessage());
      return EXIT_STACK_FAILED;
    }
  }

```

- `out.println(BenchReport.HEADER)` prints the table's header line once. For each scenario, `measure(...)` returns the list of runs and `BenchReport.render` prints the row. Each row appears when its scenario has finished, so the first row can take 5 + 3 x 10 seconds to appear.
- `return 0` means success. `catch (IOException stackFailed)` covers a refused certificate file, a container that cannot be read, a client that did not finish: it prints `the bench stopped: ...` to `err` and returns 1. Rows already printed stay printed.
- A counter that went down during a run (the container restarted) arrives here as an `IOException` from `growth` (below), so it also ends with `the bench stopped: ...` and status 1.

```java
  private static List<BenchRow> measure(
      Scenario scenario, BenchSettings settings, LoadRun runner, ContainerCounters counters)
      throws IOException, InterruptedException {
    if (!settings.warmup().isZero()) {
      runner.run(scenario, settings.warmup(), settings.clients());
    }
    List<BenchRow> runs = new ArrayList<>();
    for (int repeat = 0; repeat < settings.repeats(); repeat++) {
      Counters proxyBefore = counters.read(settings.proxyContainer());
      Counters backendBefore = counters.read(settings.backendContainer());
      LoadResult result = runner.run(scenario, settings.measure(), settings.clients());
      Counters proxyAfter = counters.read(settings.proxyContainer());
      Counters backendAfter = counters.read(settings.backendContainer());
      runs.add(
          new BenchRow(
              scenario,
              result,
              growth(settings.proxyContainer(), proxyBefore, proxyAfter),
              growth(settings.backendContainer(), backendBefore, backendAfter)));
    }
    return runs;
  }

  private static Counters growth(String container, Counters before, Counters after)
      throws IOException {
    try {
      return after.since(before);
    } catch (IllegalArgumentException wentDown) {
      // A counter that went down means the container started again during the run, so the run
      // measured nothing; that is a failure of the stack, reported like one, not a stack trace.
      throw new IOException(
          "the counters of "
              + container
              + " went down during a run, so the container restarted; run the bench again",
          wentDown);
    }
  }
}
```

- `measure` runs one scenario. If the warm-up is not zero, it runs the scenario once for the warm-up time and ignores the result.
- For each repeat: read both containers' counters, run the load, read both counters again, and build a `BenchRow` whose counters are the differences (`growth(...)`, which is `after.since(before)`). The four reads happen outside the timed run. Each one starts a small `cat` process inside the container, which adds a little processor time to that container's counter around the run. The amount is small next to a 10-second run, but it is not zero.
- The list of rows is returned to `BenchReport.render`.
- `growth(container, before, after)` computes the difference. `since` throws `IllegalArgumentException` when a counter went down, which happens only when the container started again between the two readings, so its counters began again at zero. That is a failure of the stack, not a bug in the code, so `growth` turns it into an `IOException` whose message names the container and says what to do: `the counters of seatwave-proxy-proxy-1 went down during a run, so the container restarted; run the bench again`. `BenchCliTest.stopsWithExitStatusOneWhenAContainerRestartsDuringARun` checks it with a stand-in `docker` that reports lower numbers each time.

## Try it yourself

All commands run in the repository folder. Numbers differ on every run and on every computer. The writer of these notes did not run the Docker stack (it belongs to another part of the work), so the full `bench` command is explained from the README's captured output in `measuring-a-proxy.md`.

**1. Start SeatWave on this machine and send load at it.** In one terminal, build the test certificates and start the backend:

```text
./gradlew -q testCertificates
./gradlew -q serve --args='config/backend-local.properties'
```

The writer ran it with a copy of that file whose three ports were 20980, 20981 and 20982, because other work on the machine used 20930 to 20932. In a second terminal, save this as `build/TryLoad.java`:

```text
import io.plainround.seatwave.proxy.bench.BenchReport;
import io.plainround.seatwave.proxy.bench.BenchRow;
import io.plainround.seatwave.proxy.bench.ConnectionMode;
import io.plainround.seatwave.proxy.bench.Counters;
import io.plainround.seatwave.proxy.bench.LoadResult;
import io.plainround.seatwave.proxy.bench.LoadRun;
import io.plainround.seatwave.proxy.bench.Scenario;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class TryLoad {
  public static void main(String[] args) throws Exception {
    LoadRun runner =
        new LoadRun(
            TlsFiles.clientContext(Path.of("build/tls/seatwave-test-ca.pem")),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10));
    Counters zero = new Counters(0, 0, 0);
    for (ConnectionMode mode : ConnectionMode.values()) {
      Scenario scenario =
          new Scenario(
              "local-" + mode.name().toLowerCase(),
              URI.create("https://127.0.0.1:20930/events/1/seats?section=A"),
              false,
              mode);
      List<BenchRow> runs = new ArrayList<>();
      for (int repeat = 0; repeat < 3; repeat++) {
        LoadResult result = runner.run(scenario, Duration.ofSeconds(2), 4);
        runs.add(new BenchRow(scenario, result, zero, zero));
      }
      System.out.print(BenchReport.render(runs));
    }
  }
}
```

```text
java -cp build/classes/java/main build/TryLoad.java
```

The program uses the same `LoadRun` as the bench, with 4 clients for 2 seconds, three runs per mode, and zero counters, because there is no container to read. The output of a run with version 1.0.1, on port 20930, while other programs kept the machine busy:

```text
local-reuse          11223     0.28     1.18       0        36894         36894      -              0.0                 0.0               0             0                  0
  runs (rps): 5669 (11341 in 2.0 s), 11223 (22452 in 2.0 s), 13210 (26423 in 2.0 s)
  warning: the runs differ by more than 2 times (5669 to 13210 rps), so the median is not a steady rate
local-new              549     6.89    15.19       0        36894         36894      -              0.0                 0.0               0             0                  0
  runs (rps): 517 (1034 in 2.0 s), 549 (1099 in 2.0 s), 598 (1198 in 2.0 s)
```

Read it: with reuse, the median run was 11,223 requests per second; the three runs were 5,669, 11,223 and 13,210. The first run is the slowest: it paid for loading and compiling code, which is why the real bench has a warm-up. This program has none, and the spread was wide enough for the report to print its warning line under the row. With a new connection and a full TLS handshake for every request, the same server answered about 549 per second, and the p50 latency was 6.89 ms instead of 0.28 ms. Each response was 36,894 bytes on the wire and after decompression, which is the seat map of section A. Stop SeatWave with Ctrl-C afterwards.

**2. Run the bench with a missing or bad settings file.** No Docker is needed:

```text
./gradlew -q bench
./gradlew -q bench --args=config/nothing.properties
```

```text
usage: bench <bench.properties>
```

```text
the settings file config/nothing.properties does not exist
```

Gradle then prints its own failure text, because the program ended with status 2.

**3. Parse a container's counters.** Save this as `build/TryCounters.java`:

```text
import io.plainround.seatwave.proxy.bench.ContainerCounters;

public class TryCounters {
  public static void main(String[] args) {
    String output =
        "usage_usec 1834567\nuser_usec 1200000\nsystem_usec 634567\n"
            + "Ip: Forwarding DefaultTTL\nIp: 1 64\n"
            + "Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab\n"
            + "Tcp: 1 200 120000 -1 42 314 0 3 7\n";
    System.out.println(ContainerCounters.parse(output));
    System.out.println(ContainerCounters.parse("usage_usec 1\n"));
    System.out.println(ContainerCounters.parse("Tcp: ActiveOpens\nTcp: 1\n"));
  }
}
```

```text
java -cp build/classes/java/main:$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1) build/TryCounters.java
```

```text
Success[result=Counters[cpuMicros=1834567, passiveOpens=314, activeOpens=42]]
Error[error=no Tcp lines: /proc/net/snmp was not read]
Error[error=no usage_usec line: the control group's cpu.stat was not read]
```

**4. Run the unit tests of this part.**

```text
./gradlew test --tests '*LoadRunTest' --tests '*RawResponseTest' --tests '*ContainerCountersTest' --tests '*BenchCliTest'
```

`BUILD SUCCESSFUL`. One of them, `BenchCliTest`, writes an absolute path to the test certificates into a settings file; in version 1.0.0 that failed in a folder path longer than about 170 characters, because the old file-path pattern allowed 200 characters at most. Version 1.0.1 accepts any path without control characters.

## Mistakes people make

- **Building a shell line from outside text.** `ContainerCounters` passes a list of arguments and checks the container name first. Do not glue text into one string for a shell.
- **Waiting for a program before reading its output.** If the output is bigger than the pipe's buffer, the program blocks and `waitFor` times out. The output here is a few kilobytes, so it is safe; a long output needs a reader running at the same time.
- **Measuring with one new connection per request, then comparing to reuse.** The two numbers mean different things: handshakes per second against requests per second.
- **Counting an error's time.** Failed requests have no latency in the result; the p99 describes successes only. A server that mostly fails can look fast.
- **Believing p99 from a handful of requests.** With fewer than 100 values, p99 is the largest value. A run of 8 requests has a "p99" that is just the maximum.
- **Closed-loop blindness.** When the server stalls, every client waits and sends nothing. A stall of 5 seconds adds only 8 slow samples, not thousands. A closed loop shows what the path can do; it understates how bad a stall was for users who arrive during it.
- **Trusting a counter difference across a restart.** A restarted container resets its counters. The negative difference is refused, but with a general error message.

## Check yourself

1. Why does the bench ask for `Connection: close` in `fresh`? (So the server closes after the answer, and `readAllBytes` knows where the answer ends. Each request then pays the TCP and TLS handshakes.)
2. A chunked body begins `1f4\r\n`. How many bytes does the first chunk hold, and why is the size cut at a `;`? (`0x1f4` = 500 bytes; a chunk size may carry an extension after `;`, which the parser ignores.)
3. Which two files does `ContainerCounters` read, and what does each give? (`/sys/fs/cgroup/cpu.stat` gives `usage_usec`, the processor time; `/proc/net/snmp` gives `ActiveOpens` and `PassiveOpens` in its `Tcp:` lines.)
4. What does the nearest-rank rule give for p99 of 50 sorted latencies, and why? (Position `ceil(0.99 * 50) = 50`, the last and largest, because fewer than 100 values leave no room below the maximum.)
5. How does `main` differ from `run`, and why does it matter? (`main` calls `System.exit`; `run` returns the status. Tests call `run`, so they can check the status and the printed text without ending the test program.)

## Further reading

- Java SE 25 API: `java.net.http.HttpClient`, https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- Java SE 25 API: `java.lang.ProcessBuilder`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessBuilder.html
- Java SE 25 API: `javax.net.ssl.SSLSocket`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLSocket.html
- JEP 444, virtual threads, https://openjdk.org/jeps/444
- RFC 9112, HTTP/1.1, Section 7.1 (chunked transfer coding), https://www.rfc-editor.org/rfc/rfc9112#section-7.1
- Linux kernel documentation: control group v2 (`cpu.stat`), https://docs.kernel.org/admin-guide/cgroup-v2.html
- Linux kernel documentation: `/proc/net/snmp` counters (RFC 1213 TCP group), https://www.rfc-editor.org/rfc/rfc1213
