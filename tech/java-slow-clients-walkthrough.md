# The `slow` package, line by line

## What it is

The `slow` package is the Java program for Lab 3 of Module 9, the slow-client lab. It starts hundreds of clients that each download one page very slowly, the way people do on a weak mobile link. While they download, it watches how busy SeatWave's worker threads are and asks SeatWave a cheap question over and over, to see whether a fast visitor has to wait behind the slow ones.

The package has four files. `SlowSettings` holds and checks the settings. `SlowClients` does the work. `SlowResult` holds what was measured. `SlowClientsCli` is the command that reads a settings file, runs the clients and prints four lines.

A **slow client** is a client that reads the answer in small pieces with pauses in between. The server can write a piece only when the client has made room for it. A **worker thread** is one of the fixed number of threads (64 here) that SeatWave uses to answer requests. While a worker is writing to a slow client, it cannot answer anyone else.

## Why this repository uses it

A reverse proxy helps with slow clients, because it **buffers**: it reads the whole answer from SeatWave at full speed, frees SeatWave's worker, and then writes the answer to the slow client itself, at the client's speed. NGINX can hold thousands of slow connections cheaply. SeatWave, with 64 platform threads, cannot.

The lab measures it with three sets of numbers for the same page, once straight to SeatWave and once through the proxy with buffering on:

- how long the slow downloads took,
- how many of SeatWave's 64 workers were busy at once, and for how many worker-seconds in all,
- how long a fast, cheap request took in the meantime.

The README captured these numbers inside the lab network. For example, for the 37 KB seat map with 500 slow clients: straight to SeatWave, "at most 64 of 64 busy at once" and 108.35 worker-seconds; through the buffering proxy, "at most 1 of 64 busy at once" and 0.10 worker-seconds.

## Where it appears

| File | What it does |
|---|---|
| `slow/SlowSettings.java` | The nine settings: target, clients, bytes per tick, tick, receive buffer, fast probe, metrics address, sample pause, timeout. Reads `config/slow-*.properties`. |
| `slow/SlowResult.java` | The fourteen numbers one run measured. |
| `slow/SlowClients.java` | Starts the slow clients, the probe and the sampler, and combines what they saw. |
| `slow/SlowClientsCli.java` | The `main` method: reads the file, runs, prints four lines, returns 0, 1 or 2. |

The settings files are `config/slow-direct.properties`, `slow-buffered.properties` and `slow-unbuffered.properties` (the seat map), and the same three with `-poster` in the name (a 300 KB file). `compose.yaml` has a `tools` service that runs `SlowClientsCli` inside the lab network at 10.209.9.30:

```text
docker compose -p seatwave-proxy run --rm tools /app/config/slow-<variant>.properties
```

The Gradle task `slowClients` runs the same class on the host, but the settings only allow addresses of the lab network 10.209.9.0/24. With Docker Desktop on a Mac, that network is inside the virtual machine, and these notes did not test whether the host can reach it.

## How it works underneath

**Why the lab runs inside Docker.** On a Mac, Docker's containers run inside a virtual machine, and a published port is reached through a forwarder program, which buffers data on its own. Slow clients on the Mac would then be slowed by the forwarder, not by SeatWave, and the proxy's buffering would be hidden. The `tools` container is in the same network as the others, so nothing sits between the slow clients and the server.

**How a slow reader slows a fast writer.** TCP, the protocol under HTTP, has **flow control**. The receiver keeps a **receive buffer**, a small piece of memory where arriving bytes wait until the program reads them. It tells the sender how much room is left (the **receive window**). When the buffer is full, the window is zero, and the sender must stop. The sender's own program then blocks at its write call until the client reads some bytes. SeatWave's worker is that program.

The slow client sets a small receive buffer on purpose (4,096 bytes, "kept small like a phone's"), so the window stays small and the server can send only a little ahead of what the client has read. The client reads 5,000 bytes, waits 100 ms, and reads again: 50,000 bytes per second.

**What a fast probe shows.** One more client asks SeatWave for `/health` every 100 ms, straight to SeatWave's main port, the same port the slow clients use. If all 64 workers are held by slow writes, the probe waits in the queue. Its latency is what a fast visitor would suffer.

**The sampler.** SeatWave has a second listener, the operations listener on port 8081, with its own thread, so that `/metrics` answers even if all workers are busy. The sampler reads `/metrics` every 100 ms and keeps the highest `seatwave_workers_busy` it sees.

**Percentiles and the median** are explained in `java-bench-walkthrough-1.md`. The nearest-rank rule is in `java-bench-walkthrough-2.md`.

**Java words new in this file.** `AtomicBoolean` and `AtomicInteger` (a value that several threads may read and write safely, without a lock); `Thread.sleep` (pause the current thread); a **private record** inside a class (a small value type used only there); `readNBytes` (read until the buffer is full or the stream ends); `<T> T` (a generic method that works for any type); `stream().filter(...).map(...).sorted().toList()` (a pipeline over a list).

## Walkthrough of this repository's use

### `slow/SlowSettings.java`

```java
package io.plainround.seatwave.proxy.slow;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
```

- `Result`, `SettingsReader`, `URI`, `Duration`, `List`, `Objects`, `Pattern`: the same helpers as in `BenchSettings` (see `java-bench-walkthrough-1.md`).

```java
/**
 * Everything the slow-client run needs, read from a settings file with no defaults.
 *
 * @param target what every slow client downloads, over plain HTTP
 * @param clients how many slow clients start at once
 * @param bytesPerTick how many bytes a client reads each tick
 * @param tick the pause between two reads: with bytesPerTick it sets the client's speed
 * @param receiveBufferBytes the client's socket receive buffer, kept small like a phone's
 * @param fastProbe a cheap request sent straight to SeatWave throughout, to see its queue
 * @param metrics SeatWave's operations listener, read throughout
 * @param sample the pause between two readings of the probe and the metrics
 * @param timeout the most a client may take before the run gives up on it
 */
public record SlowSettings(
    URI target,
    int clients,
    int bytesPerTick,
    Duration tick,
    int receiveBufferBytes,
    URI fastProbe,
    URI metrics,
    Duration sample,
    Duration timeout) {
```

- A record of nine components. `target` is what every slow client downloads, over plain HTTP. `clients` is how many start at once. `bytesPerTick` and `tick` set the speed: with 5,000 and 100 ms, 50,000 bytes per second. `receiveBufferBytes` is the socket receive buffer. `fastProbe` is the cheap request. `metrics` is SeatWave's operations address. `sample` is the pause between two probe requests and two metrics readings. `timeout` is the longest the whole run may take.

```java
  // The lab network's addresses only (10.209.9.0/24 in compose.yaml), plain HTTP.
  private static final Pattern LAB_URI =
      Pattern.compile("http://10\\.209\\.9\\.[0-9]{1,3}:[0-9]{2,5}/[A-Za-z0-9/._?=&-]*");
  private static final int MAX_CLIENTS = 5_000;
  private static final int MAX_BYTES = 1_000_000;
  private static final int MAX_MILLIS = 600_000;
  // Linux rounds a receive buffer up to its own minimum; below 1,024 bytes the number means little.
  private static final int MIN_RECEIVE_BUFFER = 1_024;
  private static final double MILLIS_PER_SECOND = 1_000.0;
```

- `LAB_URI` allows only `http://10.209.9.<number>:<port>/<path>`: an address in the lab network. `[0-9]{1,3}` is one to three digits and `[0-9]{2,5}` is a port of two to five digits. The path may hold letters, digits and `/ . _ ? = & -`. Any other address is refused, so the 500 clients cannot be aimed at a machine outside the lab.
- The ceilings: 5,000 clients, 1,000,000 bytes per tick, 600,000 ms (ten minutes).
- `MIN_RECEIVE_BUFFER = 1_024`. The comment says why: Linux rounds a receive buffer up to its own minimum, so below 1,024 bytes the number means little.
- `MILLIS_PER_SECOND` is used for the speed below.

```java
  /** Checks that nothing is missing. */
  public SlowSettings {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(tick, "tick");
    Objects.requireNonNull(fastProbe, "fastProbe");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(sample, "sample");
    Objects.requireNonNull(timeout, "timeout");
  }
```

- The compact constructor checks that the objects exist. It does not check ranges: `from` does that.

```java
  /**
   * Returns how fast one client reads.
   *
   * @return bytes per second
   */
  public long bytesPerSecond() {
    return Math.round(bytesPerTick * (MILLIS_PER_SECOND / tick.toMillis()));
  }
```

- `bytesPerSecond()` is `bytesPerTick * (1000 / tickMillis)`, rounded to a whole number with `Math.round`. For 5,000 and 100 ms: `5000 * 10.0 = 50000`.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<SlowSettings, List<String>> from(SettingsReader reader) {
    String target = reader.textMatching("slow.targetUri", LAB_URI);
    int clients = reader.intBetween("slow.clients", 1, MAX_CLIENTS);
    int bytesPerTick = reader.intBetween("slow.bytesPerTick", 1, MAX_BYTES);
    Duration tick = reader.millisBetween("slow.tickMillis", 1, MAX_MILLIS);
    int buffer = reader.intBetween("slow.receiveBufferBytes", MIN_RECEIVE_BUFFER, MAX_BYTES);
    String probe = reader.textMatching("slow.fastProbeUri", LAB_URI);
    String metrics = reader.textMatching("slow.metricsUri", LAB_URI);
    Duration sample = reader.millisBetween("slow.sampleMillis", 1, MAX_MILLIS);
    Duration timeout = reader.millisBetween("slow.timeoutMillis", 1, MAX_MILLIS);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new SlowSettings(
            URI.create(target),
            clients,
            bytesPerTick,
            tick,
            buffer,
            URI.create(probe),
            URI.create(metrics),
            sample,
            timeout));
  }
}
```

- `from(reader)` reads the nine keys, with their allowed ranges. The text keys must match `LAB_URI`. The receive buffer is read between 1,024 and 1,000,000.
- If any problem was collected, it returns the list. Otherwise it builds the record, turning the two address texts into `URI` values with `URI.create`.
- Note that `slow.bytesPerTick` may be as small as 1. A value below the length of a status line (about 15 characters) makes every download count as failed; see "Mistakes people make".

### `slow/SlowResult.java`

```java
package io.plainround.seatwave.proxy.slow;

import java.time.Duration;
import java.util.Objects;
```

- Imports `Duration` and `Objects`.

```java
/**
 * What one slow-client run measured.
 *
 * @param completed slow clients that read a whole 200 response
 * @param failed slow clients that got anything else, or gave up
 * @param bodyBytes the bytes one complete download read, headers included
 * @param downloadP50 the median time a slow client needed for its download
 * @param downloadMax the longest download
 * @param workersBusyMax the most SeatWave workers busy at one reading
 * @param missedSamples readings of SeatWave's metrics that could not be taken
 * @param workers SeatWave's worker threads in all
 * @param workerSeconds the worker time SeatWave spent during the run, all workers together
 * @param probeP50 the median latency of the fast probe straight to SeatWave during the run
 * @param probeP99 the 99th percentile of the same
 * @param probeMax the slowest probe
 * @param probes how many probes got 200
 * @param probeFailures how many probes timed out, were refused or got another status
 */
public record SlowResult(
    int completed,
    int failed,
    long bodyBytes,
    Duration downloadP50,
    Duration downloadMax,
    int workersBusyMax,
    int missedSamples,
    int workers,
    double workerSeconds,
    Duration probeP50,
    Duration probeP99,
    Duration probeMax,
    int probes,
    int probeFailures) {
```

- The fourteen components. Group them in your head:
  - The slow clients: `completed`, `failed`, `bodyBytes`, `downloadP50`, `downloadMax`.
  - SeatWave's workers: `workersBusyMax`, `missedSamples`, `workers`, `workerSeconds`.
  - The fast probe: `probeP50`, `probeP99`, `probeMax`, `probes`, `probeFailures`.
- `bodyBytes` is documented as "the bytes one complete download read, headers included". Its name says body, but the number includes the headers. The README's 37,187 for the seat map is the 36,894-byte body plus 293 bytes of headers.
- `workerSeconds` is the worker time SeatWave spent during the run, all workers together: the sum over workers of the seconds each was busy.

```java
  /** Checks that nothing is missing. */
  public SlowResult {
    Objects.requireNonNull(downloadP50, "downloadP50");
    Objects.requireNonNull(downloadMax, "downloadMax");
    Objects.requireNonNull(probeP50, "probeP50");
    Objects.requireNonNull(probeP99, "probeP99");
    Objects.requireNonNull(probeMax, "probeMax");
  }
}
```

- The compact constructor checks the five `Duration` components are not null. The counts are plain numbers.

### `slow/SlowClients.java`

```java
package io.plainround.seatwave.proxy.slow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

- Imports: `java.io` streams, `java.net` sockets and addresses, `java.net.http` for the side requests, `java.util.concurrent` for threads and futures, `java.util.concurrent.atomic` for values shared safely between threads, `java.util.regex` for reading the metrics text.

```java
/**
 * Many slow clients at once, the way fans on a weak mobile link download a page: each opens a
 * connection with a small receive buffer, asks for one resource and reads it a few kilobytes at a
 * time. Meanwhile one fast probe asks SeatWave directly for {@code /health}, and a sampler reads
 * SeatWave's busy workers from its operations listener.
 *
 * <p>Straight to SeatWave, a slow client keeps a worker writing until the client has read nearly
 * everything; behind a buffering proxy the proxy reads the whole response at once and does the slow
 * writing itself. The run measures both through the same numbers. Thread-safe: every run uses only
 * its own threads and collections.
 */
public final class SlowClients {
```

- The class comment is the whole idea: "Straight to SeatWave, a slow client keeps a worker writing until the client has read nearly everything; behind a buffering proxy the proxy reads the whole response at once and does the slow writing itself."

```java
  private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] (\\d{3}) .*");
  private static final Pattern BUSY = Pattern.compile("(?m)^seatwave_workers_busy (\\d+)$");
  private static final Pattern WORKERS = Pattern.compile("(?m)^seatwave_workers (\\d+)$");
  private static final Pattern BUSY_SECONDS =
      Pattern.compile("(?m)^seatwave_worker_busy_seconds_total ([0-9.]+)$");
  private static final int OK = 200;
  private static final int HTTP_PORT = 80;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
  // The longest a single probe or metrics request may take.
  private static final Duration SIDE_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private SlowClients() {}
```

- `STATUS_LINE` reads the three-digit code from `HTTP/1.1 200 OK`; it needs a space after the code.
- The three metric patterns read lines of SeatWave's Prometheus-style text, such as `seatwave_workers_busy 3`. `(?m)` makes `^` and `$` match at each line. `seatwave_workers` is the pool size, `seatwave_workers_busy` the busy count right now, `seatwave_worker_busy_seconds_total` a counter of total busy seconds (digits and a dot).
- `OK = 200`; `HTTP_PORT = 80`; the two percentile shares; `SIDE_REQUEST_TIMEOUT = 30 s`, the longest a probe or a metrics request may take.
- The private constructor makes the class a bag of static methods.

```java
  /**
   * Runs the slow clients, the probe and the sampler, and waits for every client.
   *
   * @param settings the run
   * @return what was measured
   * @throws IOException if SeatWave's metrics cannot be read before or after the run
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static SlowResult run(SlowSettings settings) throws IOException, InterruptedException {
    Objects.requireNonNull(settings, "settings");
    try (HttpClient side =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(SIDE_REQUEST_TIMEOUT)
                .build();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
```

- `run(settings)` is the one public method. It declares `throws IOException, InterruptedException`.
- `try (HttpClient side = ...; ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())` opens an HTTP client for the side requests (probe and metrics) and a pool where each task gets its own virtual thread, and closes both at the end. Virtual threads are cheap enough for 500 or 5,000 blocking clients.

```java
      String before = metrics(side, settings.metrics());
      AtomicBoolean running = new AtomicBoolean(true);
      AtomicInteger busyMax = new AtomicInteger();
      Future<ProbeTally> probe = pool.submit(() -> probe(side, settings, running));
      Future<Integer> sampler = pool.submit(() -> sample(side, settings, running, busyMax));
      List<Future<Download>> downloads = new ArrayList<>(settings.clients());
      for (int clientIndex = 0; clientIndex < settings.clients(); clientIndex++) {
        downloads.add(pool.submit(() -> download(settings)));
      }
```

- `before` is SeatWave's metrics text before the load. The difference between the busy-seconds counter before and after is the run's worker time.
- `running` is a flag that stays true while the probe and sampler should go on. `busyMax` is the highest busy count seen. Both are atomic types because several threads touch them.
- The probe and the sampler are submitted as tasks. Then one task per slow client: `pool.submit(() -> download(settings))`. The lambda has no arguments and returns a `Download`.

```java
      // One deadline for the whole run, so waiting for each client in turn cannot add up.
      long deadlineNanos = System.nanoTime() + settings.timeout().toNanos();
      List<Download> finished = new ArrayList<>(settings.clients());
      for (Future<Download> download : downloads) {
        finished.add(awaitDownload(download, deadlineNanos));
      }
      running.set(false);
      ProbeTally probes = await(probe, settings.timeout());
      int missedSamples = await(sampler, settings.timeout());
      String after = metrics(side, settings.metrics());
      return result(finished, probes, new Busy(busyMax.get(), missedSamples), before, after);
    }
  }
```

- One deadline for the whole run: `System.nanoTime() + timeout`. The comment says "waiting for each client in turn cannot add up": without it, 500 waits of 120 seconds could add up to far longer.
- `awaitDownload` waits for each client until the deadline. Then `running.set(false)` tells the probe and sampler to stop; their results are collected with `await`, `metrics` is read again for `after`, and `result(...)` combines everything.

```java
  private static Download download(SlowSettings settings) {
    URI target = settings.target();
    long startNanos = System.nanoTime();
    try (Socket socket = new Socket()) {
      // Set before connecting, so the small window is the one the handshake announces.
      socket.setReceiveBufferSize(settings.receiveBufferBytes());
      int port = target.getPort() > 0 ? target.getPort() : HTTP_PORT;
      socket.connect(
          new InetSocketAddress(target.getHost(), port),
          Math.toIntExact(settings.timeout().toMillis()));
      socket.setSoTimeout(Math.toIntExact(settings.timeout().toMillis()));
```

- `download(settings)` is one slow client. It records its start time and opens a socket inside `try (Socket socket = new Socket())`, which closes it at the end.
- `socket.setReceiveBufferSize(...)` sets the small receive buffer. It comes before `connect`, because the size announced during the handshake is fixed then: the comment says so.
- The port is the address's port, or 80 if there is none.

```java
      String path =
          target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET "
                  + path
                  + " HTTP/1.1\r\nHost: "
                  + target.getHost()
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
```

- `socket.connect(address, timeout)` opens the connection, with the whole-run timeout in milliseconds; `setSoTimeout` limits how long one read may wait for data.
- The request is written by hand: `GET <path> HTTP/1.1`, a `Host:` header, `Connection: close`, and a blank line. It asks for no compression, so the bytes are the plain ones. `Connection: close` makes the server close after the answer, which ends the last read.

```java
      InputStream in = socket.getInputStream();
      byte[] buffer = new byte[settings.bytesPerTick()];
      long total = 0;
      StringBuilder head = new StringBuilder();
      int read;
      while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
        if (head.length() < buffer.length) {
          head.append(
              new String(buffer, 0, Math.min(read, buffer.length), StandardCharsets.ISO_8859_1));
        }
        total += read;
        if (read < buffer.length) {
          break;
        }
        Thread.sleep(settings.tick());
      }
      int status = status(head.toString());
      return new Download(status == OK, total, Duration.ofNanos(System.nanoTime() - startNanos));
```

- The read loop is the slow reader. `in.readNBytes(buffer, 0, buffer.length)` reads until the buffer is full (5,000 bytes) or the stream ends, and returns how many bytes it got. It returns 0 at the end of the stream.
- The first bytes read are kept in `head` (while `head.length() < buffer.length`, so only the first chunk) to read the status line. `new String(buffer, 0, ..., ISO_8859_1)` turns bytes into text.
- `total` counts all bytes, headers included.
- `if (read < buffer.length) break;` ends the loop: a short read means the stream ended.
- `Thread.sleep(settings.tick())` is the pause. It is the slowness. With 5,000 bytes and 100 ms, the client reads at about 50,000 bytes per second, a little less because the read itself takes time.
- After the loop, `status(head.toString())` reads the code. The result is complete (`status == OK`), the total, and the time since start. "Complete" means: status 200 and the connection reached its end. The code does not compare `total` with a `Content-Length` header.

```java
    } catch (IOException failed) {
      return new Download(false, 0, Duration.ofNanos(System.nanoTime() - startNanos));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Download(false, 0, Duration.ofNanos(System.nanoTime() - startNanos));
    }
  }
```

- An `IOException` (a refused connection, a timeout, a reset) makes a failed `Download` with zero bytes. An `InterruptedException` sets the interrupt flag again and also gives a failed download.

```java
  private static ProbeTally probe(HttpClient side, SlowSettings settings, AtomicBoolean running)
      throws InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(settings.fastProbe()).timeout(SIDE_REQUEST_TIMEOUT).GET().build();
    List<Duration> latencies = new ArrayList<>();
    int failures = 0;
    while (running.get()) {
      long sentNanos = System.nanoTime();
      try {
        int status = side.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status == OK) {
          latencies.add(Duration.ofNanos(System.nanoTime() - sentNanos));
        } else {
          failures++;
        }
      } catch (IOException failed) {
        // A probe that times out or is refused is what a fan behind the slow ones would see.
        failures++;
      }
      Thread.sleep(settings.sample());
    }
    return new ProbeTally(latencies, failures);
  }
```

- `probe` is the fast client. It builds a `GET` request once and loops while `running` is true: send, measure, record the latency if the status is 200 (else count a failure), and sleep one sample pause.
- `BodyHandlers.discarding()` throws the body away; only the status matters.
- `catch (IOException failed)` counts a timeout or a refusal as a failure: the comment says it is what a fan behind the slow ones would see.
- The probe waits for its answer before it sends the next, so while one request is stuck, no more requests are sent. A stall of nine seconds therefore adds one slow sample, not ninety. The README's direct-poster row shows 60 answers and one failure over a run of about 45 seconds, which is far fewer than the 450 requests a probe that did not wait would have sent.

```java
  private static Integer sample(
      HttpClient side, SlowSettings settings, AtomicBoolean running, AtomicInteger busyMax)
      throws InterruptedException {
    int missed = 0;
    while (running.get()) {
      try {
        Matcher busy = BUSY.matcher(metrics(side, settings.metrics()));
        if (busy.find()) {
          busyMax.accumulateAndGet(Integer.parseInt(busy.group(1)), Math::max);
        } else {
          missed++;
        }
      } catch (IOException unreadable) {
        // A missed reading is counted and reported, so a gap in the samples is never hidden.
        missed++;
      }
      Thread.sleep(settings.sample());
    }
    return missed;
  }
```

- `sample` is the sampler. While `running`, it fetches the metrics text and looks for the `seatwave_workers_busy` line. If found, `busyMax.accumulateAndGet(value, Math::max)` keeps the larger of the old and new values, atomically. `Math::max` is a method reference.
- A missing line or an `IOException` counts as a missed reading, and the comment says why: "a gap in the samples is never hidden". The result carries `missedSamples`, printed as "(0 readings missed)".
- It returns the number of missed readings.
- The sampler can only see the busy count at the instants it asks, every 100 ms. A worker that is busy for less than that may be missed. For slow downloads, which last seconds, that does not matter.

```java
  private static String metrics(HttpClient side, URI uri) throws IOException, InterruptedException {
    HttpResponse<String> response =
        side.send(
            HttpRequest.newBuilder(uri).timeout(SIDE_REQUEST_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != OK) {
      throw new IOException(uri + " answered " + response.statusCode());
    }
    return response.body();
  }
```

- `metrics(side, uri)` fetches a page as text (`BodyHandlers.ofString(UTF_8)`). Any status but 200 throws `IOException` naming the address and the status.

```java
  private static SlowResult result(
      List<Download> downloads, ProbeTally probes, Busy busy, String before, String after)
      throws IOException {
    List<Duration> times =
        downloads.stream().filter(Download::isComplete).map(Download::elapsed).sorted().toList();
    long bytes =
        downloads.stream().filter(Download::isComplete).mapToLong(Download::bytes).max().orElse(0);
    List<Duration> sortedProbes = new ArrayList<>(probes.latencies());
    Collections.sort(sortedProbes);
    return new SlowResult(
        times.size(),
        downloads.size() - times.size(),
        bytes,
        percentile(times, PERCENTILE_50),
        times.isEmpty() ? Duration.ZERO : times.getLast(),
        busy.max(),
        busy.missedSamples(),
        Integer.parseInt(number(WORKERS, after)),
        Double.parseDouble(number(BUSY_SECONDS, after))
            - Double.parseDouble(number(BUSY_SECONDS, before)),
        percentile(sortedProbes, PERCENTILE_50),
        percentile(sortedProbes, PERCENTILE_99),
        sortedProbes.isEmpty() ? Duration.ZERO : sortedProbes.getLast(),
        sortedProbes.size(),
        probes.failures());
  }
```

- `result(...)` builds the `SlowResult`. `downloads.stream().filter(Download::isComplete).map(Download::elapsed).sorted().toList()` keeps the complete downloads, takes their times, sorts them, and makes a list. `Download::isComplete` is a method reference. `mapToLong(Download::bytes).max().orElse(0)` is the largest byte count among the complete ones.
- `completed` is the number of complete downloads; `failed` is all downloads minus those.
- `percentile(times, 0.50)` is the median download time; `times.getLast()` is the longest.
- `Integer.parseInt(number(WORKERS, after))` is the pool size; `Double.parseDouble(...after) - Double.parseDouble(...before)` is the worker-seconds used during the run.
- The probe numbers come from the sorted probe latencies. With a handful of answers, the "p99" is just the maximum (see the nearest-rank rule in part 2 of the bench walkthrough).

```java
  private static String number(Pattern pattern, String metrics) throws IOException {
    Matcher matcher = pattern.matcher(metrics);
    if (!matcher.find()) {
      throw new IOException("SeatWave's metrics have no line for " + pattern.pattern());
    }
    return matcher.group(1);
  }
```

- `number(pattern, metrics)` finds the first match of a pattern in the metrics text and returns its group 1, or throws `IOException("SeatWave's metrics have no line for ...")`.

```java
  static Duration percentile(List<Duration> sorted, double share) {
    if (sorted.isEmpty()) {
      return Duration.ZERO;
    }
    int rank = (int) Math.ceil(share * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }

  static int status(String head) {
    Matcher matcher = STATUS_LINE.matcher(head.lines().findFirst().orElse(""));
    return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
  }
```

- `percentile` is the nearest-rank rule on durations. It has no `private` so the test can call it.
- `status(head)` takes the first line of the text (`head.lines().findFirst()`) and reads its code; 0 if it does not look like a status line. A head that is cut before the first line ends gives 0.

```java
  private static Download awaitDownload(Future<Download> download, long deadlineNanos)
      throws InterruptedException {
    long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
    try {
      return download.get(remainingNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException tooSlow) {
      download.cancel(true);
      return new Download(false, 0, Duration.ofNanos(remainingNanos));
    } catch (ExecutionException failed) {
      // download() catches its own failures, so this is a bug; it is counted as a failed client.
      return new Download(false, 0, Duration.ZERO);
    }
  }
```

- `awaitDownload` waits for one client until the run's shared deadline. `Math.max(0, deadline - now)` is the time left, never negative. On a timeout, it cancels the task and returns a failed download. `ExecutionException` means the task itself threw; the comment says `download()` catches its own failures, so this is a bug if it happens, and it is counted as a failed client.

```java
  private static <T> T await(Future<T> task, Duration timeout)
      throws InterruptedException, IOException {
    try {
      return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException failed) {
      task.cancel(true);
      throw new IOException("a side task of the run did not finish", failed);
    }
  }
```

- `await(task, timeout)` is a generic helper: `<T> T` says it returns whatever type the task produces. On a timeout or a failure it cancels the task and throws `IOException`. It is used for the probe and the sampler.

```java
  /** One slow client's download. */
  private record Download(boolean isComplete, long bytes, Duration elapsed) {}

  /** The fast probe's successful latencies and how many probes failed. */
  private record ProbeTally(List<Duration> latencies, int failures) {}

  /** The most workers seen busy at once, and the readings that could not be taken. */
  private record Busy(int max, int missedSamples) {}
```

- Three small private records: `Download` (complete or not, bytes, time), `ProbeTally` (successful latencies and the number of failures), `Busy` (the highest busy count and the missed readings).

### `slow/SlowClientsCli.java`

```java
package io.plainround.seatwave.proxy.slow;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Command-line entry point of the slow-client run, made to run inside the lab network (the {@code
 * tools} service of {@code compose.yaml}), so no port forwarder of Docker Desktop sits between the
 * slow clients and the server they test. Exit status 2 means bad usage or invalid settings; 1 means
 * SeatWave's metrics could not be read.
 */
public final class SlowClientsCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_RUN_FAILED = 1;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_KILOBYTE = 1_000.0;

  private SlowClientsCli() {}
```

- The exit statuses: 2 for bad usage or invalid settings, 1 if SeatWave's metrics cannot be read, 0 otherwise. The class comment says it is made to run inside the lab network.
- `NANOS_PER_SECOND`, `NANOS_PER_MILLI`, `BYTES_PER_KILOBYTE` (1,000.0) turn units for printing. "KB" here means 1,000 bytes.

```java
  /**
   * Runs the slow clients and prints what they measured.
   *
   * @param arguments the settings file
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static void main(String[] arguments) throws InterruptedException {
    System.exit(run(arguments, System.out, System.err));
  }
```

- `main` calls `run` and exits with its result, as in the bench.

```java
  /**
   * Runs the slow clients and returns the exit status.
   *
   * @param arguments the command line
   * @param out where the results are printed
   * @param err where problems are printed
   * @return 0, 1 or 2, as the class documentation says
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) throws InterruptedException {
    if (arguments.length != 1) {
      err.println("usage: slowClients <slow.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<SlowSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), SlowSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    try {
      print(loaded.get(), SlowClients.run(loaded.get()), out);
      return 0;
    } catch (IOException failed) {
      err.println("the run stopped: " + failed.getMessage());
      return EXIT_RUN_FAILED;
    }
  }
```

- `run(arguments, out, err)` needs exactly one argument. `SettingsFiles.load(path, SlowSettings::from, err)` reads and checks, printing the problems on failure.
- `SlowClients.run(...)` can throw `IOException` if the metrics cannot be read before or after the run, or a side task does not finish: that prints `the run stopped: ...` and returns 1.

```java
  static void print(SlowSettings settings, SlowResult result, PrintStream out) {
    out.printf(
        Locale.ROOT,
        "target: GET %s; %d slow clients at once, each reading %d bytes every %d ms (%.0f KB/s),"
            + " receive buffer %d bytes%n",
        settings.target(),
        settings.clients(),
        settings.bytesPerTick(),
        settings.tick().toMillis(),
        settings.bytesPerSecond() / BYTES_PER_KILOBYTE,
        settings.receiveBufferBytes());
    out.printf(
        Locale.ROOT,
        "slow clients: %d complete, %d failed, %d bytes each with headers; download p50 %.2f s,"
            + " max %.2f s%n",
        result.completed(),
        result.failed(),
        result.bodyBytes(),
        seconds(result.downloadP50()),
        seconds(result.downloadMax()));
```

- `print` writes four lines. The first describes the run: the target, how many clients, how many bytes every how many milliseconds, the speed in KB/s (`bytesPerSecond() / 1000`), and the receive buffer.
- The second reports the slow clients: complete and failed counts, the bytes each complete download read, headers included, the median time (`%.2f s`) and the longest.

```java
    out.printf(
        Locale.ROOT,
        "SeatWave workers: at most %d of %d busy at once (%d readings missed); %.2f worker-seconds"
            + " in all, %.4f s per slow client%n",
        result.workersBusyMax(),
        result.workers(),
        result.missedSamples(),
        result.workerSeconds(),
        result.workerSeconds() / Math.max(1, result.completed()));
    out.printf(
        Locale.ROOT,
        "fast probe straight to SeatWave during the run: %d answered, %d failed (30 s timeout);"
            + " answered p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
        result.probes(),
        result.probeFailures(),
        millis(result.probeP50()),
        millis(result.probeP99()),
        millis(result.probeMax()));
  }
```

- The third line reports SeatWave's workers: the most busy at once, out of how many, how many readings were missed, the worker-seconds in all, and per slow client (`workerSeconds / Math.max(1, completed)`; `Math.max(1, ...)` avoids dividing by zero).
- The fourth line reports the fast probe: how many answered, how many failed, and p50, p99 and max in milliseconds. The text "(30 s timeout)" is typed into the format string. It matches `SIDE_REQUEST_TIMEOUT` in `SlowClients`, but the two are not linked: changing one does not change the other.

```java
  private static double seconds(Duration duration) {
    return duration.toNanos() / NANOS_PER_SECOND;
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
```

- The two small converters from `Duration` to seconds and milliseconds.

## Try it yourself

All commands run in the repository folder. Numbers differ on every run and on every computer. The lab itself needs the Docker stack, which these notes did not run; the README's captured output is quoted above.

**1. Start SeatWave on this machine** (`./gradlew -q testCertificates`, then `./gradlew -q serve --args='config/backend-local.properties'` in a second terminal; the writer used a copy of the file with the ports 20980 to 20982, because other work on the machine used 20930 to 20932, so the writer's output below comes from the same program with 20981 and 20982 in the two addresses).

**2. Run slow clients against it.** The settings file only allows lab addresses, so this program builds the settings in code. Save it as `build/TrySlow.java`:

```text
import io.plainround.seatwave.proxy.slow.SlowClients;
import io.plainround.seatwave.proxy.slow.SlowSettings;
import java.net.URI;
import java.time.Duration;

public class TrySlow {
  public static void main(String[] args) throws Exception {
    SlowSettings settings =
        new SlowSettings(
            URI.create(args[1]),
            Integer.parseInt(args[0]),
            Integer.parseInt(args[2]),
            Duration.ofMillis(100),
            4096,
            URI.create("http://127.0.0.1:20931/health"),
            URI.create("http://127.0.0.1:20932/metrics"),
            Duration.ofMillis(100),
            Duration.ofSeconds(60));
    System.out.println(SlowClients.run(settings));
  }
}
```

```text
java -cp build/classes/java/main build/TrySlow.java 20 'http://127.0.0.1:20931/events/1/seats?section=A' 5000
```

The output (one line, wrapped here):

```text
SlowResult[completed=20, failed=0, bodyBytes=37187, downloadP50=PT0.768083583S, downloadMax=PT0.772629333S,
workersBusyMax=1, missedSamples=0, workers=64, workerSeconds=0.0071499999999993236,
probeP50=PT0.003859833S, probeP99=PT0.005380041S, probeMax=PT0.005380041S, probes=8, probeFailures=0]
```

Read it. Twenty clients each downloaded 37,187 bytes (the 36,894-byte seat map and the headers) in about 0.77 s: 8 reads of 5,000 bytes with 100 ms between them is 0.7 s of pauses. `PT0.768S` is how Java prints a duration of 0.768 seconds.

The surprise is `workersBusyMax=1` and `workerSeconds=0.007`. On this Mac, SeatWave on the loopback address finished writing the 37 KB at once, so no worker stayed busy. The kernel's loopback buffers took the whole answer, even with the client's receive buffer set to 4,096 bytes. The lab needs a network where the small buffer holds the sender back, and the README's numbers come from the Docker virtual machine's Linux kernel (64 of 64 workers busy). On the writer's Mac the same program with 100 clients for the 300 KB poster also showed only 1 busy worker, with downloads of 6.4 s. The writer did not look into why the Mac behaves this way; treat it as a fact about this experiment, not as an explanation.

**3. See a bad setting count every download as failed.** Ten bytes per tick is shorter than the status line `HTTP/1.1 200 OK` (15 characters), so the status cannot be read:

```text
java -cp build/classes/java/main build/TrySlow.java 2 'http://127.0.0.1:20931/health' 10
```

```text
SlowResult[completed=0, failed=2, bodyBytes=0, downloadP50=PT0S, downloadMax=PT0S, workersBusyMax=1, missedSamples=0, workers=64, workerSeconds=0.0044500000000002867, probeP50=PT0.002118375S, probeP99=PT0.007532708S, probeMax=PT0.007532708S, probes=18, probeFailures=0]
```

Both downloads got a `200` answer, and both were counted as failed.

**4. See an incomplete download counted as complete.** A tiny server that promises 100,000 bytes, sends 20, and closes (save as `build/trunc.py`; it stops itself after 15 seconds without a connection):

```text
import socket
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('127.0.0.1', 20983))
s.listen(5)
s.settimeout(15)
try:
    while True:
        c, _ = s.accept()
        c.recv(2000)
        c.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n" + b"x" * 20)
        c.close()
except Exception:
    pass
```

```text
python3 build/trunc.py &
java -cp build/classes/java/main build/TrySlow.java 2 'http://127.0.0.1:20983/x' 5000
```

```text
SlowResult[completed=2, failed=0, bodyBytes=63, downloadP50=PT0.004594959S, downloadMax=PT0.004725417S, workersBusyMax=0, ...
```

Two "complete" downloads of 63 bytes, though the server promised 100,000. (In this run the probe and the metrics also pointed at SeatWave, so the rest of the line is not the point.)

**5. Run the unit tests of this file.**

```text
./gradlew test --tests '*SlowClientsTest'
```

Stop SeatWave with Ctrl-C when you are done.

## Mistakes people make

- **Running the lab from the Mac.** Docker Desktop's port forwarder buffers on its own and hides what the proxy does. The lab runs inside the lab network.
- **A receive buffer that the system ignores.** The program sets `SO_RCVBUF` before `connect`, but the system may round it up or, as the Try above shows, still buffer a whole small answer. Check that `workersBusyMax` is high on the "direct" run before you trust a "buffered" run.
- **Too small a `bytesPerTick`.** Under about 15 bytes the status line is split across reads and every download counts as failed. Keep it at a few hundred bytes or more.
- **Calling a short download a success.** A download is complete when the status is 200 and the stream ended. A server that dies mid-answer and closes cleanly still passes. Compare the bytes with the other runs of the same target: the README's rows all show the same byte count.
- **Reading the probe's p99 as a tail.** With 8 or 60 answers, p99 is the maximum, and a stalled probe stops sending more requests while it waits.
- **Ignoring the missed readings.** `(0 readings missed)` tells you the busy numbers have no gaps. A larger number means the sampler was starved.

## Check yourself

1. Why does the program set the receive buffer before `connect`? (The size announced during the TCP handshake is fixed then; a later change is too late to limit the server's first burst.)
2. What does a buffering proxy change about worker time? (The proxy reads the whole answer from SeatWave at full speed, so SeatWave's worker is free after a short time. The proxy writes slowly to the client. The README shows 108.35 worker-seconds straight and 0.10 through the proxy for the seat map.)
3. Why is there an operations listener on another thread? (So `/metrics` answers when all 64 workers are busy; otherwise the sampler would see nothing exactly when it matters.)
4. A download reads 5,000 bytes, then sleeps 100 ms. How many bytes per second is that, and how long does a 37,187-byte answer take? (50,000 bytes per second; 8 reads and 7 or 8 pauses, about 0.7 to 0.8 s.)
5. What are the exit statuses, and which one means SeatWave's metrics could not be read? (0 success, 2 bad usage or invalid settings, 1 the run stopped because the metrics could not be read or a side task did not finish.)

## Further reading

- RFC 9293, Transmission Control Protocol, Section 3.8.6 (flow control and the receive window), https://www.rfc-editor.org/rfc/rfc9293#section-3.8.6
- Java SE 25 API: `java.net.Socket#setReceiveBufferSize`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Socket.html#setReceiveBufferSize(int)
- Java SE 25 API: `java.util.concurrent.atomic.AtomicInteger`, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/AtomicInteger.html
- JEP 444, virtual threads, https://openjdk.org/jeps/444
- NGINX documentation: `proxy_buffering`, https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering
