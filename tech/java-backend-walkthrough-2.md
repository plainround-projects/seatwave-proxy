# The `backend` package, part 2: metrics, command line, server and routes (line by line)

This is part 2 of 2. Part 1 (`java-backend-walkthrough-1.md`) reads `Hold`, `HoldLedger`, `HoldKeys`, `Reply`, `Request`, `Problems`, `StaticFiles`, `SeatCatalog` and `BackendSettings`. This part reads the four files that run the server. The notes were split because the package is about 1,900 lines.

## What it is

The four files:

- `BackendMetrics.java`: SeatWave's own counters, written in the Prometheus text format.
- `BackendCli.java`: the command-line entry point. It reads the settings file, starts the server, and waits until the process is told to stop.
- `BackendServer.java`: the server. It opens three listeners (plain HTTP, HTTPS and an operations port), runs requests on a fixed pool of worker threads, and decides the client address before any route runs.
- `SeatWaveRoutes.java`: the routes. Each route is a small method that turns a `Request` into a `Reply`.

Terms used here:

- A **listener** is a port on which a server waits for connections.
- A **thread pool** is a fixed group of threads that take jobs from a queue. A **queue** is a waiting line: first in, first out.
- **TLS** is the encryption layer under HTTPS. **TLS termination** means the server (or the proxy) is the end of the encrypted connection.
- A **counter** is a number that only goes up. A **gauge** is a number that goes up and down (such as "workers busy now").
- **Metrics** are such numbers, exposed so that a tool can read them. The **Prometheus text format** is a plain list of lines `name{label="value"} number`.
- A **signal** (SIGTERM, or Ctrl-C) is how the operating system asks a program to stop.
- A **shutdown hook** is code the Java runtime runs when the process is stopping.

## Why this repository uses it

The module's labs need a backend that can be made to hurt in specific ways, and that tells the truth about it:

- The worker pool is fixed at 64 platform threads on purpose. A worker that is writing a big reply to a slow client is a worker no other request can use. The counter `seatwave_workers_busy` shows this, and the proxy's buffering lab shows the proxy freeing the worker.
- The operations listener has its own thread, so `/metrics` still answers when all 64 workers are busy.
- The queue is bounded. When it is full the request is refused and counted (`seatwave_rejected_queue_full_total`), not queued without end.
- The client address is decided once, in `BackendServer.request`, before any route runs. That is the module's invariant: SeatWave believes a forwarded address only from a trusted proxy.
- The routes carry the cache rules the proxy obeys: each reply says how it may be cached.

## Where it appears

| File | What it does |
|---|---|
| `backend/BackendMetrics.java` | The counters and the `/metrics` text. |
| `backend/BackendCli.java` | `main`: reads the settings file, starts the server, waits. Run by `./gradlew serve` and by the container. |
| `backend/BackendServer.java` | Three listeners, the worker pool, the request copy, the writers for bodies and streams. |
| `backend/SeatWaveRoutes.java` | Every route and every cache and error rule. |

## How it works underneath

**Start.** `main` reads the one argument (the settings file), checks it, builds the TLS context from the keystore files, and starts the server. It prints the three addresses. Then the main thread waits on a latch (a gate that opens once) that only the shutdown hook opens.

**Three listeners, one pool.** The plain and the HTTPS listeners both hand their requests to the same pool of 64 workers, so they compete for the same threads. The admin listener has a pool of one thread of its own.

**One request.** The listener accepts a connection and hands the request to a worker through the queue. The worker runs `handle`: it counts itself busy, copies the request (`request`), asks `SeatWaveRoutes.route` for a reply, writes it, closes the exchange, and counts itself free. If the client has gone away, the write fails with an `IOException`, which is logged at debug level and not treated as an error.

**What a full queue does.** `ThreadPoolExecutor` is given a bounded queue of 1,024 places (in the container settings). When 64 workers are busy and 1,024 requests wait, the next request is **rejected**: the pool's rejection handler counts it and throws an exception, and the JDK server closes that connection. The client sees a failed request (a closed connection), not a status code.

**Why platform threads.** Java also has virtual threads, which are cheap and nearly unlimited in number. With them a slow client would cost a little memory instead of a scarce thread, and the lab would have nothing to show. The Javadoc of `BackendServer` says so.

**Stop.** A stop signal makes the runtime start the shutdown hook, which closes the listeners and the pool, prints `SeatWave stopped` and opens the latch.

## Walkthrough of this repository's use

### `backend/BackendMetrics.java`

```java
package io.plainround.seatwave.proxy.backend;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
```

`LongAdder` is a counter built for many threads adding at once: it keeps several partial sums and adds them when you ask for the total. It is faster than an `AtomicLong` under heavy use. `TreeMap` is a table kept sorted by key.

```java
/**
 * SeatWave's own counters, written in the Prometheus text format at {@code GET /metrics}.
 *
 * <p>The labels are the route template and the status, never a raw path or a client address, so the
 * number of series stays small (the guidelines' rule 68). {@code seatwave_workers_busy} is the
 * number the buffering lab watches: how many of the worker threads are inside a request right now,
 * including the time spent writing the response to a slow client. Thread-safe: every field is a
 * concurrent counter.
 */
```

The labels of the request counter are the route template (`GET /health`) and the status, never a raw path or a client address. Raw paths and addresses are unbounded, and each distinct label value is a new series that a monitoring system must store; the comment cites the guidelines' rule 68 for this. `seatwave_workers_busy` is the number the buffering lab watches.

```java
final class BackendMetrics {

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
```

```java
  private final ConcurrentMap<String, LongAdder> requests = new ConcurrentHashMap<>();
  private final AtomicInteger busyWorkers = new AtomicInteger();
  private final AtomicInteger busyWorkersMax = new AtomicInteger();
  private final LongAdder busyNanos = new LongAdder();
  private final LongAdder responseBytes = new LongAdder();
  private final LongAdder forwardingIgnored = new LongAdder();
  private final LongAdder forwardingUnreadable = new LongAdder();
  private final LongAdder rejectedByQueue = new LongAdder();
  private final int workerThreads;

  BackendMetrics(int workerThreads) {
    this.workerThreads = workerThreads;
  }
```

The fields: `requests` maps the text `route|status` to its counter; `busyWorkers` is how many workers are inside a request now; `busyWorkersMax` is the highest that number has been; `busyNanos` is the total time workers spent inside requests, in nanoseconds (a billionth of a second); `responseBytes` is the total body bytes; the next two count forwarding headers that were ignored or unreadable; `rejectedByQueue` counts refused requests; `workerThreads` is the pool size, kept only to print it. The constructor stores the pool size.

```java
  void workerStarted() {
    int busy = busyWorkers.incrementAndGet();
    busyWorkersMax.accumulateAndGet(busy, Math::max);
  }
```

`workerStarted` adds one to the busy count. `accumulateAndGet(busy, Math::max)` keeps the larger of the old maximum and the new busy count, as one atomic step. `Math::max` is a **method reference**, a short form of a lambda.

```java
  void workerFinished(String route, int status, long bytes, long elapsedNanos) {
    busyWorkers.decrementAndGet();
    busyNanos.add(elapsedNanos);
    responseBytes.add(bytes);
    requests.computeIfAbsent(route + "|" + status, key -> new LongAdder()).increment();
  }
```

`workerFinished` takes one away, adds the elapsed time and the bytes, and counts the request under `route|status`. `computeIfAbsent(key, function)` creates the counter the first time a key is seen and returns the existing one afterwards; `increment()` adds one.

```java
  void forwardingIgnored() {
    forwardingIgnored.increment();
  }

  void forwardingUnreadable() {
    forwardingUnreadable.increment();
  }

  void rejectedByQueue() {
    rejectedByQueue.increment();
  }
```

Three tiny methods count a forwarding header that was ignored, one that was unreadable, and a request refused by the queue.

```java
  int busyWorkers() {
    return busyWorkers.get();
  }
```

`busyWorkers()` returns the current number. Nothing in the repository calls it (the tests read the number from the `/metrics` text instead), so it is unused code.

```java
  String render() {
    StringBuilder text = new StringBuilder();
    text.append("# TYPE seatwave_requests_total counter\n");
    Map<String, Long> sorted = new TreeMap<>();
    requests.forEach((key, count) -> sorted.put(key, count.sum()));
    sorted.forEach((key, count) -> {
      int bar = key.lastIndexOf('|');
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_requests_total{route=\"%s\",status=\"%s\"} %d%n",
              key.substring(0, bar),
              key.substring(bar + 1),
              count));
    });
```

`render` writes the text. The first line, `# TYPE seatwave_requests_total counter`, tells a reader the kind of metric. The counters are copied into a `TreeMap` so the lines come out sorted, which keeps the output stable. For each entry, `key.lastIndexOf('|')` finds the separator (the route text can contain spaces and braces but never a bar), and `String.format` writes `seatwave_requests_total{route="...",status="..."} 3`. `Locale.ROOT` makes the number formatting the same on every computer. `%s` is a text slot, `%d` a whole number, `%n` a line break.

```java
    line(text, "gauge", "seatwave_workers", workerThreads);
    line(text, "gauge", "seatwave_workers_busy", busyWorkers.get());
    line(text, "gauge", "seatwave_workers_busy_max", busyWorkersMax.get());
    text.append("# TYPE seatwave_worker_busy_seconds_total counter\n")
        .append(
            String.format(
                Locale.ROOT,
                "seatwave_worker_busy_seconds_total %.6f%n",
                busyNanos.sum() / NANOS_PER_SECOND));
    line(text, "counter", "seatwave_response_bytes_total", responseBytes.sum());
```

Three gauges (pool size, busy now, busiest so far), then the busy-time counter converted to seconds by dividing by 1,000,000,000. `%.6f` writes six decimals.

```java
    line(text, "counter", "seatwave_forwarding_headers_ignored_total", forwardingIgnored.sum());
    line(
        text,
        "counter",
        "seatwave_forwarding_headers_unreadable_total",
        forwardingUnreadable.sum());
    line(text, "counter", "seatwave_rejected_queue_full_total", rejectedByQueue.sum());
    return text.toString();
  }
```

Four more counters (bytes, headers ignored, headers unreadable, queue refusals), and the finished text.

```java

  private static void line(StringBuilder text, String type, String name, long value) {
    text.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    text.append(name).append(' ').append(value).append('\n');
  }
}
```

`line` writes one metric: a `# TYPE` line and then `name value`. A small oddity: `render` uses `%n` for the request lines and the busy-seconds line, which is the platform's line separator (a single `\n` on macOS and Linux, `\r\n` on Windows), but `line` always writes `\n`. On Windows the output would mix the two. Nothing in the lab runs the server on Windows, and this was not run there.

### `backend/BackendCli.java`

```java
package io.plainround.seatwave.proxy.backend;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
```

```java
/**
 * Command-line entry point of the SeatWave backend: reads the settings, starts the listeners and
 * runs until the process is told to stop (Ctrl-C, or {@code docker stop}'s SIGTERM), then closes
 * them. Exit status 2 means bad usage, an invalid settings file or a missing TLS file.
 */
public final class BackendCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;

  private BackendCli() {}
```

`public final class BackendCli` has a private constructor (`private BackendCli() {}`), so no one can make an instance: it is only a home for `main` and `start`. The exit status 2 is the shell convention for "used incorrectly".

```java
  /**
   * Runs the backend.
   *
   * @param arguments the settings file, for example {@code config/backend-container.properties}
   * @throws InterruptedException if the main thread is interrupted while it waits
   */
  public static void main(String[] arguments) throws InterruptedException {
    Optional<BackendServer> server = start(arguments, System.out, System.err);
    if (server.isEmpty()) {
      System.exit(EXIT_BAD_USAGE);
      return;
    }
```

`main` is where a Java program begins. `String[] arguments` holds the words typed after the program name. `start(...)` does the real work and returns an `Optional<BackendServer>`. If it is empty, the problem has already been printed, and `System.exit(2)` ends the process with status 2. The `return` after it is never reached in practice; it keeps the compiler and the reader sure that the code below does not run with an empty `Optional`.

```java
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              server.get().close();
              System.out.println("SeatWave stopped");
              stopped.countDown();
            }));
    stopped.await();
  }
```

A `CountDownLatch(1)` is a gate that opens when `countDown()` is called once. `Runtime.getRuntime().addShutdownHook(new Thread(...))` registers a thread that the runtime starts when the process is told to stop (Ctrl-C, or the SIGTERM that `docker stop` sends). That thread closes the server, prints `SeatWave stopped` and opens the gate. The main thread waits on the gate with `stopped.await()`, so `main` does not return while the server runs. The latch keeps `main` from returning while the server runs, and the hook opens it only after the server is closed. `() -> { ... }` is a lambda with no parameters.

```java
  /**
   * Checks the arguments and the settings and starts the listeners.
   *
   * @param arguments the command line
   * @param out where the listening addresses are printed
   * @param err where problems are printed
   * @return the running server, or empty after printing why it cannot start
   */
  static Optional<BackendServer> start(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 1) {
      err.println("usage: serve <backend.properties>");
      return Optional.empty();
    }
```

`start` takes the output stream and the error stream as parameters (instead of using `System.out` directly), so a test can capture what is printed.

```java
    Optional<BackendSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), BackendSettings::from, err);
    if (loaded.isEmpty()) {
      return Optional.empty();
    }
```

`SettingsFiles.load(path, BackendSettings::from, err)` reads the file, applies `BackendSettings.from` and prints every problem to `err` when there are any (see `java-config-json-tls-walkthrough.md`). An empty result ends `start` with an empty `Optional`.

```java
    BackendSettings settings = loaded.get();
    try {
      BackendServer server =
          BackendServer.start(
              settings,
              TlsFiles.serverContext(settings.tlsKeystore(), settings.tlsPasswordFile()),
              Clock.systemUTC());
      String address = settings.bindAddress().getHostAddress();
      out.println("SeatWave plain HTTP on " + address + ":" + server.plainPort());
      out.println("SeatWave HTTPS on " + address + ":" + server.tlsPort() + " (TLSv1.3)");
      out.println("SeatWave operations (metrics, health) on " + address + ":" + server.adminPort());
```

`TlsFiles.serverContext(...)` builds the TLS settings from the keystore file and the password file. `Clock.systemUTC()` is the real clock. `BackendServer.start` binds the ports. `getHostAddress()` turns the address into text. The program prints one line per listener. When a port was asked for as `0`, `server.plainPort()` returns the real port the system picked.

```java
      out.println(
          settings.workerThreads()
              + " worker threads, trusted proxies "
              + settings.trustedProxies().addresses().stream()
                  .map(each -> each.getHostAddress())
                  .sorted()
                  .toList());
      return Optional.of(server);
    } catch (IOException cannotStart) {
      err.println("SeatWave cannot start: " + cannotStart.getMessage());
      return Optional.empty();
    }
  }
```

The last line shows the worker count and the trusted proxies. A **stream** (`.stream()`) runs a chain of steps over a collection: `.map(each -> each.getHostAddress())` turns each address into text, `.sorted()` sorts them, and `.toList()` collects them. An `IOException` (a port already used, a missing keystore file) is caught, printed as `SeatWave cannot start: ...`, and the method returns an empty `Optional`.

### `backend/BackendServer.java`

```java
package io.plainround.seatwave.proxy.backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.plainround.seatwave.proxy.forwarded.AddressSource;
import io.plainround.seatwave.proxy.forwarded.ClientAddress;
import io.plainround.seatwave.proxy.forwarded.ClientView;
import io.plainround.seatwave.proxy.forwarded.ForwardedHeaders;
import io.plainround.seatwave.proxy.json.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
```

The imports show the building blocks. `com.sun.net.httpserver` is the JDK's own small HTTP server (module `jdk.httpserver`); `HttpServer` is a plain listener, `HttpsServer` an encrypted one, `HttpExchange` is one request and its response. `ThreadPoolExecutor` is the pool. `SLF4J` (`Logger`, `LoggerFactory`, `MDC`) is the logging interface: see `logging-slf4j-logback.md`. `MDC` is a per-thread table of values that every log line can print; here it holds the correlation id.

```java
/**
 * The SeatWave backend on the JDK's own HTTP server: a plain HTTP listener for the proxy and an
 * HTTPS listener for clients that come straight in (SIMPLE).
 *
 * <p>Requests run on a fixed pool of platform threads, Module 4's 64 workers, with a bounded wait
 * queue. Platform threads are chosen on purpose here: a worker that is writing to a slow client is
 * a worker no other request can use, which is the cost proxy buffering removes, and a fixed pool
 * makes that cost visible as {@code seatwave_workers_busy}. With virtual threads the same slow
 * client would cost memory instead of a scarce thread.
 *
 * <p>The other half of the module's invariant lives in {@link #handle}: the client address is
 * decided once, by {@link ClientAddress#resolve}, before any route runs, and routes receive only
 * the decided {@link ClientView}. Thread-safe: handlers share only thread-safe collaborators.
 */
```

```java
public final class BackendServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(BackendServer.class);
  // 0 lets stop() return at once; a lab server has no request worth waiting for at shutdown.
  private static final int STOP_DELAY_SECONDS = 0;
  private static final long SHUTDOWN_WAIT_SECONDS = 5;
  // sendResponseHeaders takes 0 for "length unknown", which makes the JDK server send chunks.
  private static final long CHUNKED = 0;
  private static final int STREAM_OK = 200;
  private static final int ADMIN_OK = 200;
  private static final int ADMIN_NOT_FOUND = 404;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  // NGINX's $request_id: 32 hexadecimal characters. Believed only from a trusted proxy.
  private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-f]{32}");
  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
```

`BackendServer implements AutoCloseable`, so it can be used in a try-with-resources block and closed automatically. `LOG` is the logger of this class. The constants: `STOP_DELAY_SECONDS = 0` makes a listener stop at once (a lab server has no request worth waiting for). `CHUNKED = 0` is the value the JDK server reads as "the length is unknown", which makes it send the body in **chunks**, the HTTP way to send a body of unknown length. `REQUEST_ID` matches exactly 32 lowercase hexadecimal characters, which is the form of NGINX's `$request_id`; it is believed only when the peer is a trusted proxy.

`HEARTBEAT` is the text `: heartbeat` and two line breaks. In the event-stream format a line that starts with a colon is a **comment**: the browser ignores it, but the bytes cross every idle timer on the path.

```java
  private final HttpServer plain;
  private final HttpsServer tls;
  private final HttpServer admin;
  private final ExecutorService adminThread;
  private final ThreadPoolExecutor workers;
  private final BackendSettings settings;
  private final SeatWaveRoutes routes;
  private final BackendMetrics metrics;
```

The fields: three listeners, the thread that serves the admin listener, the worker pool, the settings, the routes and the metrics.

```java
  private BackendServer(BackendSettings settings, SSLContext tlsContext, Clock clock)
      throws IOException {
    this.settings = settings;
    this.metrics = new BackendMetrics(settings.workerThreads());
    this.routes =
        new SeatWaveRoutes(new HoldLedger(clock), metrics, settings.timing(), new JsonCodec());
```

The constructor is private; `start` (below) is the public way in. It stores the settings, makes the metrics (with the pool size), and makes the routes with a new `HoldLedger`, the metrics, the timing and a new `JsonCodec`. The `throws IOException` says it can fail while binding ports.

```java
    AtomicInteger workerNumber = new AtomicInteger();
    this.workers =
        new ThreadPoolExecutor(
            settings.workerThreads(),
            settings.workerThreads(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(settings.queueCapacity()),
            task -> new Thread(task, "seatwave-worker-" + workerNumber.incrementAndGet()),
            (task, pool) -> {
              // A full queue is refused, never grown: the JDK server then closes that connection.
              metrics.rejectedByQueue();
              throw new RejectedExecutionException("the request queue is full");
            });
```

The pool. `new ThreadPoolExecutor(core, max, keepAlive, unit, queue, threadFactory, rejectionHandler)`:

- The first two numbers are both `settings.workerThreads()`: the pool always has exactly that many threads (core equals max).
- `0, TimeUnit.MILLISECONDS` is the keep-alive time for extra threads, which do not exist.
- `new ArrayBlockingQueue<>(settings.queueCapacity())` is the waiting line, with a fixed size.
- `task -> new Thread(task, "seatwave-worker-" + workerNumber.incrementAndGet())` is the **thread factory**: each new thread is named `seatwave-worker-1`, then `seatwave-worker-2`, then the next number, so a log or a thread dump is readable.
- The last argument is the **rejection handler**, a lambda called when the pool and the queue are both full. It counts the refusal and throws `RejectedExecutionException`. The comment says what follows: the JDK server then closes that connection.

```java
    this.plain =
        HttpServer.create(
            new InetSocketAddress(settings.bindAddress(), settings.plainPort()),
            settings.backlog());
    this.tls =
        HttpsServer.create(
            new InetSocketAddress(settings.bindAddress(), settings.tlsPort()), settings.backlog());
    this.admin =
        HttpServer.create(
            new InetSocketAddress(settings.bindAddress(), settings.adminPort()),
            settings.backlog());
```

`HttpServer.create(address, backlog)` creates a listener on the bind address and port from the settings. `InetSocketAddress` is an address and a port. The plain and the admin listeners are `HttpServer`; the TLS listener is `HttpsServer`. A port of `0` lets the system choose.

```java
    // One thread of its own: the operations listener answers even when every worker is busy.
    this.adminThread =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "seatwave-admin"));
    admin.setExecutor(adminThread);
    admin.createContext("/", this::handleAdmin);
```

The admin listener gets an executor with exactly one thread, named `seatwave-admin`, and one context: `createContext("/", this::handleAdmin)` says "every path below `/` goes to `handleAdmin`". `this::handleAdmin` is a method reference.

```java
    tls.setHttpsConfigurator(tlsOnlyVersion13(tlsContext));
    plain.setExecutor(workers);
    tls.setExecutor(workers);
    plain.createContext("/", exchange -> handle(exchange, "http"));
    tls.createContext("/", exchange -> handle(exchange, "https"));
    plain.start();
    tls.start();
    admin.start();
  }
```

`setHttpsConfigurator(tlsOnlyVersion13(tlsContext))` turns on TLS 1.3 only (below). Both main listeners use the same pool (`setExecutor(workers)`). Their contexts call `handle` with the scheme `"http"` or `"https"`, which is how the server knows how the peer reached it. `start()` then opens each listener.

```java
  /**
   * Starts both listeners.
   *
   * @param settings the checked settings
   * @param tlsContext the server's TEST key and certificate chain
   * @param clock the clock holds expire by
   * @return the running server
   * @throws IOException if a port cannot be bound
   */
  public static BackendServer start(BackendSettings settings, SSLContext tlsContext, Clock clock)
      throws IOException {
    return new BackendServer(settings, tlsContext, clock);
  }
```

`start` is the public factory: it hides the constructor.

```java
  /**
   * Returns the plain HTTP listener's port.
   *
   * @return the port, useful when the settings asked for any free port
   */
  public int plainPort() {
    return plain.getAddress().getPort();
  }

  /**
   * Returns the HTTPS listener's port.
   *
   * @return the port
   */
  public int tlsPort() {
    return tls.getAddress().getPort();
  }

  /**
   * Returns the operations listener's port.
   *
   * @return the port
   */
  public int adminPort() {
    return admin.getAddress().getPort();
  }
```

Three getters for the real ports (useful when a port of 0 was asked for).

```java
  /** Stops the listeners and the workers, waiting a few seconds for running requests. */
  @Override
  public void close() {
    plain.stop(STOP_DELAY_SECONDS);
    tls.stop(STOP_DELAY_SECONDS);
    admin.stop(STOP_DELAY_SECONDS);
    adminThread.shutdownNow();
    workers.shutdownNow();
    try {
      if (!workers.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
        LOG.warn("workers still running after {} s", SHUTDOWN_WAIT_SECONDS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
```

`close` stops the three listeners with a delay of 0, shuts down the admin thread and the workers with `shutdownNow()` (which interrupts running tasks), then waits up to five seconds for the workers to end and logs a warning if they have not. `InterruptedException` is thrown if this thread is interrupted while waiting; the code restores the interrupt flag with `Thread.currentThread().interrupt()` so callers can still see it. Note that the Javadoc says it waits "for running requests", but `shutdownNow()` interrupts them first, so a long poll or a stream that is running ends at once instead of finishing. The words are looser than the behavior.

```java
  private void handle(HttpExchange exchange, String scheme) {
    long startNanos = System.nanoTime();
    metrics.workerStarted();
    String route = "other";
    int status = 0;
    long bytes = 0;
    try (exchange) {
      Request request = request(exchange, scheme);
      MDC.put("correlationId", request.correlationId());
      SeatWaveRoutes.Routed routed = routes.route(request);
      route = routed.route();
```

`handle` is the one method every request on the plain and TLS listeners goes through. It records the start time (`System.nanoTime()` is a high-resolution stopwatch, good for measuring durations), counts the worker as busy, and sets default values for the route name, status and byte count so that the `finally` block always has something to report. `try (exchange)` closes the exchange when the block ends, even after an error. `request(exchange, scheme)` copies the request. `MDC.put("correlationId", ...)` makes the id appear on every log line this thread writes from now on.

```java
      switch (routed.reply()) {
        case Reply.Body body -> {
          status = body.status();
          bytes = body.length();
          writeBody(exchange, body, startNanos);
        }
        case Reply.EventStream stream -> {
          status = STREAM_OK;
          bytes = writeStream(exchange, stream);
        }
      }
```

`routes.route(request)` returns a `Routed` (route template and reply). The `switch` over `routed.reply()` has two cases because `Reply` is sealed: a whole `Body` (record the status and the byte count, then `writeBody`) or an `EventStream` (the status is 200, and `writeStream` returns the bytes it wrote). There is no default; the compiler knows these are all the kinds.

```java
    } catch (IOException clientGone) {
      // The client closed the connection while SeatWave was reading or writing; nothing to answer.
      LOG.debug("connection closed by the client: {}", clientGone.getMessage());
    } finally {
      metrics.workerFinished(route, status, bytes, System.nanoTime() - startNanos);
      MDC.clear();
    }
  }
```

If the client closed the connection while SeatWave was reading or writing, an `IOException` is thrown; it is logged at debug level only. The `finally` block runs on every path: it tells the metrics that the worker is finished, with the route, the status, the bytes and the elapsed nanoseconds, and clears the logging context. Any other kind of exception is not caught here; it would pass to the JDK server, and the metrics would still be updated, with status 0.

```java
  private void handleAdmin(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getRawPath();
      boolean isGet = exchange.getRequestMethod().equals("GET");
      byte[] body;
      int status;
      String type = "text/plain; version=0.0.4; charset=utf-8";
      if (isGet && path.equals("/metrics")) {
        status = ADMIN_OK;
        body = metrics.render().getBytes(StandardCharsets.UTF_8);
      } else if (isGet && path.equals("/health")) {
        status = ADMIN_OK;
        type = "application/json";
        body = "{\"status\":\"up\"}".getBytes(StandardCharsets.UTF_8);
      } else {
        status = ADMIN_NOT_FOUND;
        body = "only GET /metrics and GET /health\n".getBytes(StandardCharsets.UTF_8);
      }
      exchange.getResponseHeaders().set("Content-Type", type);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }
```

`handleAdmin` answers the operations listener. `getRequestURI().getRawPath()` is the path as sent. `GET /metrics` returns the metrics text (media type `text/plain; version=0.0.4`, the Prometheus type). `GET /health` returns `{"status":"up"}`. Anything else is a 404 with a plain explanation. All replies carry `Cache-Control: no-store`. `sendResponseHeaders(status, length)` sends the status line and headers; giving the body's length makes it a fixed-length reply. `getResponseBody()` is the stream to write the body into, closed by the inner try-with-resources.

```java
  private Request request(HttpExchange exchange, String scheme) throws IOException {
    Map<String, List<String>> headers = new HashMap<>();
    exchange
        .getRequestHeaders()
        .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
    InetAddress peer = exchange.getRemoteAddress().getAddress();
    ForwardedHeaders forwarding =
        new ForwardedHeaders(
            headers.getOrDefault("forwarded", List.of()),
            headers.getOrDefault("x-forwarded-for", List.of()),
            headers.getOrDefault("x-forwarded-proto", List.of()));
```

`request` copies the exchange into a `Request`. The header names are lower-cased into a plain `HashMap`, with each list of values copied. `exchange.getRemoteAddress().getAddress()` is the **peer**: the other end of the connection, as the network saw it (not what a header claims). `ForwardedHeaders` takes the three lists of lines for `Forwarded`, `X-Forwarded-For` and `X-Forwarded-Proto`.

```java
    ClientView client = ClientAddress.resolve(peer, forwarding, settings.trustedProxies(), scheme);
    boolean isTrustedPeer = settings.trustedProxies().isTrusted(peer);
    if (!isTrustedPeer && !forwarding.equals(ForwardedHeaders.none())) {
      // The forged-header case of the invariant: counted, and the headers are not used.
      metrics.forwardingIgnored();
    }
    if (client.source() == AddressSource.UNREADABLE) {
      metrics.forwardingUnreadable();
      LOG.warn("a trusted proxy sent forwarding headers that cannot be read; using its address");
    }
```

`ClientAddress.resolve(peer, forwarding, trustedProxies, scheme)` decides the client once (see `java-forwarded-walkthrough.md`). Then the invariant's bookkeeping. If the peer is not a trusted proxy and still sent forwarding headers, a forged-header case: `forwardingIgnored()` counts it and the headers are not used. If the result is `UNREADABLE`, a trusted proxy sent headers that could not be read: it is counted and a warning is logged. The warning line is written before the `MDC.put` in `handle`, so it carries no correlation id (`[none]` in the log pattern).

```java
    List<String> requestIds = headers.getOrDefault("x-request-id", List.of());
    String correlationId =
        isTrustedPeer
                && requestIds.size() == 1
                && REQUEST_ID.matcher(requestIds.getFirst()).matches()
            ? requestIds.getFirst()
            : UUID.randomUUID().toString().replace("-", "");
```

The correlation id: if the peer is a trusted proxy, and it sent exactly one `X-Request-Id`, and the value is 32 hex characters, it is used, so a proxy log line and a SeatWave log line can be matched. Otherwise a random UUID without hyphens is made. A client talking directly cannot choose the id.

```java
    byte[] body;
    boolean tooLarge;
    try (InputStream in = exchange.getRequestBody()) {
      // One byte more than the limit is read, so a body over the limit is noticed without reading
      // all of it.
      body = in.readNBytes(SeatWaveRoutes.MAX_BODY_BYTES + 1);
      tooLarge = body.length > SeatWaveRoutes.MAX_BODY_BYTES;
    }
```

The body is read with `readNBytes(MAX_BODY_BYTES + 1)`: one byte more than the limit of 1,024, so a body over the limit is noticed without reading all of it. `tooLarge` says whether the extra byte arrived.

```java
    URI uri = exchange.getRequestURI();
    return new Request(
        exchange.getRequestMethod(),
        uri.getRawPath(),
        uri.getRawQuery() == null ? "" : uri.getRawQuery(),
        headers,
        tooLarge ? new byte[0] : body,
        tooLarge,
        client,
        correlationId);
  }
```

`getRawQuery()` is `null` when there is no query, so it is replaced with an empty string. The request is built; an oversized body is replaced with an empty array and flagged.

```java
  private static void writeBody(HttpExchange exchange, Reply.Body body, long startNanos)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", body.contentType());
    body.headers()
        .forEach(header -> exchange.getResponseHeaders().add(header.getKey(), header.getValue()));
    // The handler's own time, so a client can tell server time from network and proxy time.
    double serverMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    exchange
        .getResponseHeaders()
        .set("Server-Timing", String.format(Locale.ROOT, "app;dur=%.2f", serverMillis));
    exchange.sendResponseHeaders(body.status(), body.length());
    try (OutputStream out = exchange.getResponseBody()) {
      // This write is what a slow client stretches: it returns only when the kernel has taken
      // every byte, so the worker stays busy until then.
      out.write(body.body());
    }
  }
```

`writeBody` sets `Content-Type`, adds the reply's own headers, and sets `Server-Timing: app;dur=<milliseconds>`: the time the handler has used so far, so a client can tell server time from network and proxy time. `String.format(Locale.ROOT, "app;dur=%.2f", ...)` writes two decimals. `sendResponseHeaders(status, length)` starts the reply, and `out.write(body.body())` writes the bytes. The comment explains why this matters for the module: `write` returns only when the kernel has taken every byte, so a slow client keeps the worker busy until then.

```java
  private static long writeStream(HttpExchange exchange, Reply.EventStream stream)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    stream
        .headers()
        .forEach(header -> exchange.getResponseHeaders().add(header.getKey(), header.getValue()));
    exchange.sendResponseHeaders(STREAM_OK, CHUNKED);
    long written = 0;
    long endNanos = System.nanoTime() + stream.lifetime().toNanos();
    try (OutputStream out = exchange.getResponseBody()) {
      byte[] retry =
          ("retry: " + stream.retry().toMillis() + "\n\n").getBytes(StandardCharsets.UTF_8);
      out.write(retry);
      out.flush();
      written += retry.length;
      while (System.nanoTime() + stream.heartbeat().toNanos() <= endNanos) {
        Thread.sleep(stream.heartbeat());
        out.write(HEARTBEAT);
        // Flushed at once: the heartbeat exists to make bytes cross every idle timer on the path.
        out.flush();
        written += HEARTBEAT.length;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return written;
  }
```

`writeStream` writes an event stream. It sets the type `text/event-stream`, adds headers, and sends `200` with the unknown length (`CHUNKED`). It computes when the stream must end (`endNanos`), writes `retry: 1000` and a blank line, and flushes (`flush()` pushes buffered bytes onto the connection now). Then, while at least one heartbeat interval fits before the end, it sleeps one heartbeat (`Thread.sleep(Duration)`), writes the heartbeat and flushes it at once. It returns the number of bytes written. If the thread is interrupted while sleeping (shutdown), the flag is restored and the stream ends.

```java
  private static HttpsConfigurator tlsOnlyVersion13(SSLContext context) {
    return new HttpsConfigurator(context) {
      @Override
      public void configure(HttpsParameters parameters) {
        SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
        // TLS 1.3 only, as in Module 5's lab: one round trip, and an older offer is refused.
        ssl.setProtocols(new String[] {"TLSv1.3"});
        parameters.setSSLParameters(ssl);
      }
    };
  }
}
```

`tlsOnlyVersion13` returns an `HttpsConfigurator` that overrides `configure` (an **anonymous class**: a class with no name, written inline). It takes the context's default parameters, replaces the allowed protocols with `TLSv1.3` only, and gives them to the listener. A client that offers only an older version is refused. TLS 1.3 needs one round trip to set up, as Module 5's lab used.

### `backend/SeatWaveRoutes.java`

```java
package io.plainround.seatwave.proxy.backend;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

```java
/**
 * SeatWave's routes in this lab: Module 7's version 1 paths that the proxy labs need, Module 1's
 * seat map, Module 8's long poll and event stream, and the static files.
 *
 * <p>Every reply says how it may be cached, because the proxy obeys it: static files are {@code
 * public, max-age=300}; a hold is {@code private}, so no shared cache may keep it; seat maps and
 * events are {@code no-cache}; writes and errors are {@code no-store}. Thread-safe: the ledger, the
 * key table and the metrics are, and nothing else changes after construction.
 */
final class SeatWaveRoutes {

  /** Module 7's contract limit on a request body. */
  static final int MAX_BODY_BYTES = 1_024;
```

The class lists SeatWave's routes in this lab. Every reply says how it may be cached, because the proxy obeys it: static files `public, max-age=300`; a hold `private`; seat maps and events `no-cache`; writes and errors `no-store`. `no-cache` means "a cache may keep it but must ask the origin before using it"; `no-store` means "do not keep it at all"; `private` means "only the user's own browser may keep it, not a shared cache such as the proxy".

```java
  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveRoutes.class);
  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int BAD_REQUEST = 400;
  private static final int NOT_FOUND = 404;
  private static final int METHOD_NOT_ALLOWED = 405;
  private static final int CONFLICT = 409;
  private static final int TOO_LARGE = 413;
  private static final int UNSUPPORTED_MEDIA_TYPE = 415;
  private static final int UNPROCESSABLE = 422;
  private static final int UNAVAILABLE = 503;
  // Module 7's page limits: 1 to 500 items, 100 when the client does not say.
  private static final int MAX_LIMIT = 500;
  private static final int DEFAULT_LIMIT = 100;
  // Module 8's event stream asks the browser to reconnect after 1 second.
  private static final Duration STREAM_RETRY = Duration.ofSeconds(1);
```

The constants are the status codes by name, Module 7's page limits (1 to 500, 100 if not given), and the reconnect delay of the stream (1 second).

```java
  // Module 7: version 0 was deprecated on 11 November 2026 and stops answering on 11 May 2027.
  private static final Instant VERSION0_DEPRECATED_AT = Instant.parse("2026-11-11T00:00:00Z");
  private static final Instant VERSION0_SUNSET_AT = Instant.parse("2027-05-11T00:00:00Z");
  private static final Pattern EVENT_SEATS = Pattern.compile("/v1/events/([0-9]{1,18})/seats");
  private static final Pattern EVENT_CHANGES =
      Pattern.compile("/v1/events/([0-9]{1,18})/seat-changes(/stream)?");
  private static final Pattern HOLD_PATH =
      Pattern.compile("/v1/holds/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
  // Module 7: an Idempotency-Key is an RFC 9651 String, a quoted value of printable characters.
  private static final Pattern IDEMPOTENCY_KEY =
      Pattern.compile("\"[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,64}\"");
  private static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern POSITION = Pattern.compile("[A-Za-z0-9:_-]{1,40}");
  private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]{0,2}");
  private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,64}");
```

Version 0 was deprecated on 11 November 2026 and stops on 11 May 2027. The patterns match the shapes of the paths: `/v1/events/<number>/seats`, `/v1/events/<number>/seat-changes` with an optional `/stream`, and `/v1/holds/<uuid>` where the UUID is lowercase hex in groups of 8-4-4-4-12. `IDEMPOTENCY_KEY` is a quote, 1 to 64 printable ASCII characters (`\x21`, `\x23-\x5B`, `\x5D-\x7E`: everything from `!` to `~` except the quote and the backslash), and a closing quote: the form of an RFC 9651 string. `CUSTOMER`, `POSITION`, `LIMIT` and `CURSOR` limit the characters of those values. `LIMIT` is a number from 1 to 999 with no leading zero (the code then checks it is at most 500).

```java
  private final HoldLedger ledger;
  private final SeatCatalog catalog;
  private final HoldKeys keys;
  private final StaticFiles staticFiles;
  private final BackendMetrics metrics;
  private final BackendSettings.Timing timing;
  private final JsonCodec json;
  private final Problems problems;

  SeatWaveRoutes(
      HoldLedger ledger, BackendMetrics metrics, BackendSettings.Timing timing, JsonCodec json) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.timing = Objects.requireNonNull(timing, "timing");
    this.json = Objects.requireNonNull(json, "json");
    this.catalog = new SeatCatalog(ledger);
    this.keys = new HoldKeys();
    this.staticFiles = new StaticFiles();
    this.problems = new Problems(json);
  }
```

The constructor stores the collaborators and makes the catalog, the key table, the static files and the problem builder. `Objects.requireNonNull` guards each argument.

```java
  /**
   * Answers one request.
   *
   * @param request the request, with its client already decided
   * @return the route's template, for the metrics, and the reply
   */
  Routed route(Request request) {
    String path = request.path();
    if (request.method().equals("POST")) {
      return path.equals("/v1/holds")
          ? new Routed("POST /v1/holds", hold(request))
          : new Routed("other", notAllowed(request, "GET"));
    }
    if (!request.method().equals("GET")) {
      return new Routed("other", notAllowed(request, path.equals("/v1/holds") ? "POST" : "GET"));
    }
    return switch (path) {
      case "/health" -> new Routed("GET /health", noQuery(request, this::health));
      case "/lab/client" -> new Routed("GET /lab/client", noQuery(request, this::clientView));
      case "/v1/events" -> new Routed("GET /v1/events", noQuery(request, this::events));
      case "/events/1/seats" -> new Routed("GET /events/1/seats", version0SeatMap(request));
      default -> routeByPattern(request);
    };
  }
```

`route` is the router. A `POST` is accepted only for `/v1/holds`; any other path with `POST` gets 405 with `Allow: GET`. A method other than `GET` and `POST` gets 405, with `Allow: POST` for `/v1/holds` and `Allow: GET` for every other path. For a `GET`, a `switch` expression on the path picks a route for the four fixed paths; the rest go to `routeByPattern`. Each branch returns a `Routed` pair: the route's template (the metrics label) and the reply. The template, not the real path, is the label, so `/v1/holds/<any uuid>` is always counted as `GET /v1/holds/{holdId}`. Note that a `POST` to an unknown path gets 405 rather than 404, and `HEAD` and `OPTIONS` get 405 too; those are simplifications of this lab.

```java
  private Routed routeByPattern(Request request) {
    String path = request.path();
    if (path.startsWith("/static/")) {
      return new Routed("GET /static/{file}", staticFile(request));
    }
    Matcher seats = EVENT_SEATS.matcher(path);
    if (seats.matches()) {
      return new Routed("GET /v1/events/{eventId}/seats", seatPage(request, seats.group(1)));
    }
    Matcher changes = EVENT_CHANGES.matcher(path);
    if (changes.matches()) {
      boolean isStream = changes.group(2) != null;
      return isStream
          ? new Routed("GET /v1/events/{eventId}/seat-changes/stream", stream(request, changes))
          : new Routed("GET /v1/events/{eventId}/seat-changes", longPoll(request, changes));
    }
    Matcher hold = HOLD_PATH.matcher(path);
    if (hold.matches()) {
      return new Routed("GET /v1/holds/{holdId}", readHold(request, hold.group(1)));
    }
    return new Routed("other", notFound(request));
  }
```

`routeByPattern` handles the paths that contain variable parts. A path under `/static/` goes to the static route. Then each pattern is tried in turn with `matcher(path).matches()`; the first group (`group(1)`) is the event number or the hold id. For `seat-changes`, `group(2)` is the optional `/stream` part: `null` means the long poll, otherwise the stream. Anything else is `other` with a 404.

```java
  private Reply health(Request request) {
    return Reply.json(OK, "{\"status\":\"up\"}").withHeader("Cache-Control", "no-store");
  }

  private Reply clientView(Request request) {
    // A lab route outside /v1: it shows which client address the invariant let SeatWave believe.
    ClientDiagnostic view =
        new ClientDiagnostic(
            request.client().address().getHostAddress(),
            request.client().scheme(),
            request.client().source().name().toLowerCase(Locale.ROOT));
    return Reply.json(OK, json.write(view)).withHeader("Cache-Control", "no-store");
  }

  private Reply events(Request request) {
    EventView event =
        new EventView(SeatCatalog.EVENT_ID, "Arena Night", SeatCatalog.EVENT_STARTS_AT.toString());
    return Reply.json(OK, json.write(new EventPage(List.of(event))))
        .withHeader("Cache-Control", "no-cache");
  }
```

`health` returns `{"status":"up"}` with `no-store`. `clientView` is a lab route outside `/v1`: it shows the address, scheme and source (`peer`, `forwarded`, `x_forwarded_for` or `unreadable`) that the invariant allowed SeatWave to believe; this is what the README's session and the stack tests read. `events` lists the one event, with `no-cache`. `SeatCatalog.EVENT_STARTS_AT.toString()` writes the instant in ISO-8601. The small records at the end of the file (`ClientDiagnostic`, `EventView`, `EventPage`) exist only to be written as JSON.

```java
  private Reply version0SeatMap(Request request) {
    String query = request.rawQuery();
    String section = query.startsWith("section=") ? query.substring("section=".length()) : "";
    if (!SeatCatalog.isSection(section)) {
      return problems.of(
          BAD_REQUEST,
          "invalid-request",
          "The request does not match the contract",
          "the only parameter is section, one letter from A to T",
          request.correlationId());
    }
    String successor = "/v1/events/1/seats?section=" + section;
    return new Reply.Body(OK, "application/json", catalog.version0SeatMap(section), List.of())
        .withHeader("Cache-Control", "no-cache")
        .withHeader("Deprecation", "@" + VERSION0_DEPRECATED_AT.getEpochSecond())
        .withHeader(
            "Sunset",
            DateTimeFormatter.RFC_1123_DATE_TIME.format(VERSION0_SUNSET_AT.atZone(ZoneOffset.UTC)))
        .withHeader("Link", "<" + successor + ">; rel=\"successor-version\"");
  }
```

The version 0 seat map. The query must be exactly `section=<letter>`; otherwise 400. On success the reply carries Module 7's deprecation signals: `Deprecation: @<seconds>` (the date it was deprecated, as seconds since 1970), `Sunset: <date>` (the date it stops, in the HTTP date format `Tue, 11 May 2027 00:00:00 GMT`) and `Link: <...>; rel="successor-version"` pointing at version 1.

```java
  private Reply seatPage(Request request, String eventText) {
    Result<Map<String, String>, String> parameters =
        parameters(request.rawQuery(), Set.of("section", "limit", "cursor"));
    List<String> errors = new ArrayList<>();
    Map<String, String> values = Map.of();
    switch (parameters) {
      case Result.Success<Map<String, String>, String>(Map<String, String> parsed) ->
          values = parsed;
      case Result.Error<Map<String, String>, String>(String reason) -> errors.add(reason);
    }
    Optional<String> section = Optional.ofNullable(values.get("section"));
    if (section.isPresent() && !SeatCatalog.isSection(section.get())) {
      errors.add("section must be one letter from A to T");
    }
    String limitText = values.getOrDefault("limit", Integer.toString(DEFAULT_LIMIT));
    boolean limitValid =
        LIMIT.matcher(limitText).matches() && Integer.parseInt(limitText) <= MAX_LIMIT;
    if (!limitValid) {
      errors.add("limit must be from 1 to " + MAX_LIMIT);
    }
```

The version 1 seat page. `parameters(...)` (below) splits the query and returns a `Result`. The `switch` takes the parsed map on success or adds the reason to `errors` on failure. The method collects **every** problem, then answers once, so a client sees all its mistakes in one reply. `section` is checked, then `limit`: default 100, digits only, at most 500.

```java
    Optional<SeatCatalog.SeatPosition> after = Optional.empty();
    if (values.containsKey("cursor")) {
      after =
          CURSOR.matcher(values.get("cursor")).matches()
              ? SeatCatalog.decode(values.get("cursor"))
              : Optional.empty();
      if (after.isEmpty()) {
        errors.add("cursor is not one this server wrote");
      }
    }
    if (!errors.isEmpty()) {
      return invalid(request, errors);
    }
    if (Long.parseLong(eventText) != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    SeatCatalog.Page page = catalog.page(section, Integer.parseInt(limitText), after);
    Object view =
        page.isLast()
            ? new LastSeatPage(page.items())
            : new SeatPage(page.items(), page.nextCursor());
    return Reply.json(OK, json.write(view)).withHeader("Cache-Control", "no-cache");
  }
```

The cursor: it must match the allowed characters and decode (see `SeatCatalog.decode`); otherwise the error is "cursor is not one this server wrote". If there are errors, a 400 follows. Only then is the event checked (404 for any event other than 1). The page is built; the last page is written without a `nextCursor` field (`LastSeatPage`), others with it (`SeatPage`). The reply is `no-cache`.

```java
  private Reply hold(Request request) {
    if (request.bodyTooLarge()) {
      return problems.of(
          TOO_LARGE,
          "body-too-large",
          "The body is over 1,024 bytes",
          "send at most " + MAX_BODY_BYTES + " bytes",
          request.correlationId());
    }
    boolean isJson =
        request
            .header("content-type")
            .map(type -> type.startsWith("application/json"))
            .orElse(false);
    if (!isJson) {
      return problems.of(
          UNSUPPORTED_MEDIA_TYPE,
          "unsupported-media-type",
          "The body must be application/json",
          "send Content-Type: application/json",
          request.correlationId());
    }
```

`hold` handles `POST /v1/holds`. In order: a body over the limit gives 413; a `Content-Type` that does not start with `application/json` gives 415; then the key. `request.header("content-type")` returns nothing if the header was sent twice. Be careful with the test `startsWith("application/json")`: it also accepts `application/jsonx`. A short run on a local copy confirmed that a hold sent with `Content-Type: application/jsonx` is accepted with 201.

```java
    Optional<String> key =
        request.header("idempotency-key").filter(value -> IDEMPOTENCY_KEY.matcher(value).matches());
    if (key.isEmpty()) {
      return invalid(
          request, List.of("Idempotency-Key must be one quoted string of 1 to 64 characters"));
    }
    String body = new String(request.body(), StandardCharsets.UTF_8);
    String fingerprint = fingerprint(body);
```

The `Idempotency-Key` header must exist exactly once and match the pattern; otherwise 400. The body is turned into text, and its **fingerprint** (a SHA-256 digest, see `fingerprint` below) is computed.

```java
    return switch (keys.claim(key.get(), fingerprint)) {
      case HoldKeys.Claim.Owner owner -> holdOnce(request, key.get(), fingerprint, body);
      case HoldKeys.Claim.Replay(Reply.Body stored) -> stored;
      case HoldKeys.Claim.InProgress running ->
          problems.of(
              CONFLICT,
              "request-in-progress",
              "A request with this Idempotency-Key is running",
              "retry after it finishes",
              request.correlationId());
      case HoldKeys.Claim.Reused reused ->
          problems.of(
              UNPROCESSABLE,
              "idempotency-key-reused",
              "This Idempotency-Key was used with another body",
              "use a new key for a new request",
              request.correlationId());
      case HoldKeys.Claim.Full full ->
          problems
              .of(
                  UNAVAILABLE,
                  "service-busy",
                  "SeatWave is at its limit; retry after Retry-After",
                  "the key table is full",
                  request.correlationId())
              .withHeader("Retry-After", "1");
    };
```

`keys.claim(...)` returns one of five outcomes, and the `switch` answers each. `Owner`: do the work (`holdOnce`). `Replay(Reply.Body stored)`: send the stored reply again. `InProgress`: 409 "request-in-progress". `Reused`: 422 "idempotency-key-reused". `Full`: 503 "service-busy" with `Retry-After: 1`. The names after the types (`owner`, `running`, `reused`, `full`) are required by the syntax and unused.

```java
  private Reply.Body holdOnce(Request request, String key, String fingerprint, String body) {
    Reply.Body reply =
        switch (json.read(body, HoldRequestBody.class)) {
          case Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed) ->
              holdSeat(request, parsed);
          case Result.Error<HoldRequestBody, JsonCodec.BindingProblem>(
                  JsonCodec.BindingProblem problem) ->
              invalid(request, List.of(problem.pointer() + " " + problem.detail()));
        };
    // Final outcomes are stored, so a retry with the same key gets the same answer (Module 7).
    keys.complete(key, fingerprint, reply);
    return reply;
  }
```

`holdOnce` binds the body to the `HoldRequestBody` record through the JSON codec. On success `holdSeat` runs; on a binding problem the answer is a 400 whose detail is the JSON pointer of the field and the codec's words. In both cases the reply is stored with `keys.complete(...)`, so a retry gets the same answer. For a whole-body problem the pointer is the empty string, so the detail starts with a space: `" the body is empty; send a JSON object"` (confirmed by a local run).

```java
  private Reply.Body holdSeat(Request request, HoldRequestBody body) {
    List<String> errors = new ArrayList<>();
    if (body.eventId() == null || body.eventId() < 1) {
      errors.add("/eventId must be 1 or more");
    }
    if (body.seat() == null || !SeatCatalog.isSeat(body.seat())) {
      errors.add("/seat must be a section letter A to T, a hyphen and a number 1 to 1000");
    }
    if (body.customer() == null || !CUSTOMER.matcher(body.customer()).matches()) {
      errors.add("/customer must be 1 to 64 letters, digits, - or _");
    }
    if (!errors.isEmpty()) {
      return invalid(request, errors);
    }
    if (body.eventId() != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    Optional<Hold> hold = ledger.hold(body.eventId(), body.seat(), body.customer());
    if (hold.isEmpty()) {
      return problems.of(
          CONFLICT,
          "seat-unavailable",
          "The seat is held or sold",
          "seat " + body.seat() + " is not available",
          request.correlationId());
    }
    // A business event: logged with the correlation id and the client the invariant allowed.
    LOG.info(
        "held {} for 300 s; client {}", body.seat(), request.client().address().getHostAddress());
    return Reply.json(CREATED, json.write(HoldView.of(hold.get(), ledger.now())))
        .withHeader("Location", "/v1/holds/" + hold.get().holdId())
        .withHeader("Cache-Control", "no-store");
  }
```

`holdSeat` validates the fields (all errors together): `eventId` must be 1 or more, `seat` a real label, `customer` 1 to 64 letters, digits, `-` or `_`. The field types are boxed (`Long`, not `long`) on purpose so that a missing field is `null` and reaches this check instead of being turned into 0. Then a 404 for an event other than 1, and the ledger call. If the ledger returns nothing, the seat is held: 409 "seat-unavailable". Otherwise the hold is logged (`LOG.info` with `{}` placeholders that SLF4J fills in) and answered with `201 Created`, a `Location` header and `Cache-Control: no-store`. The log line says "300 s" as fixed text; it does not read `HoldLedger.HOLD_DURATION`.

```java
  private Reply readHold(Request request, String holdId) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("this path takes no query parameters"));
    }
    return ledger
        .find(holdId)
        .<Reply>map(hold ->
            Reply.json(OK, json.write(HoldView.of(hold, ledger.now())))
                // private: one fan's hold; a shared cache such as the proxy must never keep it,
                // even with a lifetime (RFC 9111, Section 5.2.2.7).
                .withHeader("Cache-Control", "private, max-age=60"))
        .orElseGet(() -> notFound(request));
  }
```

`readHold` answers `GET /v1/holds/{id}`: no query is allowed; an unknown id is a 404; a known hold is written with `Cache-Control: private, max-age=60`. The comment says why: it is one fan's hold, so a shared cache such as the proxy must never keep it, even with a lifetime. `.<Reply>map(...)` names the type argument so the lambda's `Body` result fits `Optional<Reply>`. `orElseGet(() -> notFound(request))` builds the 404 only if the `Optional` is empty. `HoldView.of` writes the state as `held` or `expired` by the clock, so an expired hold is still readable.

```java
  private Reply longPoll(Request request, Matcher changes) {
    Optional<String> after =
        request.rawQuery().startsWith("after=")
            ? Optional.of(request.rawQuery().substring("after=".length()))
                .filter(value -> POSITION.matcher(value).matches())
            : Optional.empty();
    if (after.isEmpty()) {
      return invalid(request, List.of("after must be a position the feed sent"));
    }
    if (Long.parseLong(changes.group(1)) != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    try {
      // The lab's feed never changes, so every long poll waits its whole hold, as Module 8's does
      // when nothing happens; this is the long-lived request a proxy timeout must leave alone.
      Thread.sleep(timing.longPollHold());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return problems
          .of(
              UNAVAILABLE,
              "service-busy",
              "SeatWave is at its limit; retry after Retry-After",
              "the server is stopping",
              request.correlationId())
          .withHeader("Retry-After", "1");
    }
    return Reply.json(OK, json.write(new ChangeBatch(List.of(), after.get())))
        .withHeader("Cache-Control", "no-store");
  }
```

The long poll. The query must be `after=<position>` with an allowed value; otherwise 400. The event must be 1. Then the route **sleeps** for the configured time (25 seconds in the container). In this lab the feed never changes, so every long poll waits its whole time, as a real one does when nothing happens; this is the long-lived request that a proxy timeout must leave alone. If the thread is interrupted (the server is stopping), the interrupt flag is restored and a 503 with `Retry-After` is returned. After the wait, the reply is an empty batch and the same position, with `no-store`. While it sleeps, the long poll holds one worker thread.

```java
  private Reply stream(Request request, Matcher changes) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("the lab's stream takes no query parameters"));
    }
    if (Long.parseLong(changes.group(1)) != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    return new Reply.EventStream(
        STREAM_RETRY,
        timing.heartbeat(),
        timing.streamLifetime(),
        List.of(Map.entry("Cache-Control", "no-store")));
  }
```

The event stream route. No query allowed, event must be 1. It returns an `EventStream` reply with the 1-second reconnect delay and the heartbeat and lifetime from the settings; `BackendServer.writeStream` does the writing.

```java
  private Reply staticFile(Request request) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("a static file takes no query parameters"));
    }
    return staticFiles
        .find(request.path())
        .<Reply>map(file ->
            new Reply.Body(OK, file.contentType(), file.bytes(), List.of())
                .withHeader("Cache-Control", "public, max-age=" + StaticFiles.MAX_AGE_SECONDS))
        .orElseGet(() -> notFound(request));
  }
```

A static file: no query allowed (so `?v=2` is a 400, which the test checks). If the path is known, the reply carries its media type and bytes and `Cache-Control: public, max-age=300`. Otherwise 404.

```java
  private Reply noQuery(Request request, Function<Request, Reply> route) {
    return request.rawQuery().isEmpty()
        ? route.apply(request)
        : invalid(request, List.of("this path takes no query parameters"));
  }

  private Reply.Body invalid(Request request, List<String> errors) {
    return problems.of(
        BAD_REQUEST,
        "invalid-request",
        "The request does not match the contract",
        String.join("; ", errors),
        request.correlationId());
  }
```

`noQuery` runs a route only if the query is empty; `Function<Request, Reply>` is the type of a function that takes a request and returns a reply, which is why `this::health` can be passed. `invalid` builds the 400 problem and joins the error messages with `; `.

```java
  private Reply.Body notFound(Request request) {
    return problems.of(
        NOT_FOUND, "not-found", "No such resource", "no such resource", request.correlationId());
  }

  private Reply notAllowed(Request request, String allowed) {
    return problems
        .of(
            METHOD_NOT_ALLOWED,
            "method-not-allowed",
            "This path does not take this method",
            "use " + allowed,
            request.correlationId())
        .withHeader("Allow", allowed);
  }
```

`notFound` and `notAllowed` build the 404 and the 405 problems. The 405 carries an `Allow` header naming the right method.

```java
  private static Result<Map<String, String>, String> parameters(String query, Set<String> names) {
    Map<String, String> values = new LinkedHashMap<>();
    if (query.isEmpty()) {
      return Result.success(values);
    }
    for (String pair : query.split("&", -1)) {
      int equalsAt = pair.indexOf('=');
      String name = equalsAt < 0 ? pair : pair.substring(0, equalsAt);
      if (!names.contains(name)) {
        return Result.error("unknown parameter " + name + "; allowed: section, limit, cursor");
      }
      if (equalsAt < 0 || values.put(name, pair.substring(equalsAt + 1)) != null) {
        return Result.error("parameter " + name + " must appear once, with a value");
      }
    }
    return Result.success(values);
  }
```

`parameters` splits a query string into a map, strictly. It splits on `&` (the `-1` keeps trailing empty pieces, so `a=1&` is an error). For each pair it finds the `=`. An unknown name is refused with the list of allowed names. A name without `=` or repeated is refused: `values.put(name, value)` returns the previous value, so a non-null result means a repeat. The map is a `LinkedHashMap`, which keeps the order of insertion.

```java
  private static String fingerprint(String body) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException missing) {
      // Every Java platform must provide SHA-256, so this is a broken runtime, not bad input.
      throw new IllegalStateException("the JDK has no SHA-256", missing);
    }
  }
```

`fingerprint` hashes the body with SHA-256 (`MessageDigest`) and writes the 32 bytes as 64 hex characters (`HexFormat`). Two different bodies have different fingerprints in practice, and the table stores only the short fingerprint. `NoSuchAlgorithmException` cannot happen on a real Java platform, which must provide SHA-256, so the code turns it into an `IllegalStateException`.

```java
  /** The reply of one route, with the route template the metrics count it under. */
  record Routed(String route, Reply reply) {}

  record ClientDiagnostic(String address, String scheme, String source) {}

  record EventView(long eventId, String name, String startsAt) {}

  record EventPage(List<EventView> items) {}

  record SeatPage(List<SeatCatalog.SeatItem> items, String nextCursor) {}

  record LastSeatPage(List<SeatCatalog.SeatItem> items) {}

  record ChangeBatch(List<String> items, String position) {}

  /** The body of POST /v1/holds as sent; boxed, so a missing field reaches the validation step. */
  record HoldRequestBody(Long eventId, String seat, String customer) {}

  record HoldView(String holdId, long eventId, String seat, String state, String expiresAt) {
    static HoldView of(Hold hold, Instant now) {
      return new HoldView(
          hold.holdId(),
          hold.eventId(),
          hold.seat(),
          hold.isActiveAt(now) ? "held" : "expired",
          hold.expiresAt().toString());
    }
  }
}
```

The records at the end. `Routed` pairs the route template with the reply. The body records (`ClientDiagnostic`, `EventView`, `EventPage`, `SeatPage`, `LastSeatPage`, `ChangeBatch`) are written to JSON field by field in the order they are declared. `HoldRequestBody` is the one record read from JSON; its fields are boxed so a missing field arrives as `null` and the validation step reports it. `HoldView.of` builds the hold body: the state is `held` while the hold is active and `expired` after.

## Try it yourself

Start SeatWave as in part 1, then try these. Timings differ on every run.

**1. See the invariant.**

```
curl -s -H 'X-Forwarded-For: 203.0.113.7' http://127.0.0.1:20931/lab/client; echo
```

The local settings trust `127.0.0.1`, which is where curl comes from, so the header is believed:

```
{"address":"203.0.113.7","scheme":"http","source":"x_forwarded_for"}
```

With `forwarded.trustedProxies` changed to an address that is not yours, the same command prints `{"address":"127.0.0.1","scheme":"http","source":"peer"}` and `seatwave_forwarding_headers_ignored_total` becomes 1. The test `ForwardedInvariantTest` does exactly this.

**2. Try the whole-body error messages.**

```
curl -s -X POST http://127.0.0.1:20931/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "e1"' -d ''
```

The `detail` was `" the body is empty; send a JSON object"` (with the leading space). A body `[1]` printed `" the body must be a JSON object"`, `{"eventId":"1",...}` printed `"/eventId has the wrong type"`, and a body with `"eventId"` twice printed `" the body is not valid JSON"`.

**3. Read the metrics for the routes you just used.**

```
curl -s http://127.0.0.1:20932/metrics | grep -v '^#'
```

It printed one `seatwave_requests_total{route="...",status="..."}` line per route and status, then `seatwave_workers 64`, `seatwave_workers_busy 0`, `seatwave_workers_busy_max 1`, a small `seatwave_worker_busy_seconds_total`, and the three forwarding and queue counters.

**4. Watch a long poll hold a worker.**

```
curl -s 'http://127.0.0.1:20931/v1/events/1/seat-changes?after=1:42'
```

It waits 25 seconds, then prints `{"items":[],"position":"1:42"}`. The unit test `refusesARequestWhenEveryWorkerIsBusyAndTheQueueIsFull` waits until `/metrics` shows `seatwave_workers_busy 1` while a long poll runs. The 25-second wait itself was not timed here; the unit test of the long poll uses a 200 ms wait.

**5. Run the server tests.**

```
./gradlew test --tests '*BackendServerTest' --tests '*ForwardedInvariantTest'
```

They passed. `tests-backend-walkthrough.md` explains them.

## Mistakes people make

- **Forgetting that a long poll holds a worker.** 64 long polls fill the pool. The lab uses it on purpose.
- **Closing the response before the metrics.** In `handle`, `workerFinished` runs after the exchange is closed. A client that sends its next request immediately could read `/metrics` just before the worker has counted the previous request. The window is a few microseconds and the tests do not hit it, but it is a real ordering.
- **Trusting `X-Request-Id` from anyone.** The server uses it only from a trusted proxy and only in the 32-hex form.
- **Letting a header-sniffing check be too loose.** `startsWith("application/json")` also accepts `application/jsonx`.
- **High-cardinality labels.** Metrics labelled by raw path or client address grow without limit. This code labels by route template.
- **Reading a body without a limit.** `readNBytes(MAX_BODY_BYTES + 1)` bounds the memory a client can make the server use.
- **Swallowing `InterruptedException`.** Both `longPoll` and `writeStream` restore the interrupt flag, so a stopping server stops.

## Check yourself

1. How many threads serve the plain and TLS listeners together, and what happens to request number 64 + 1,024 + 1? Answer: 64 workers. The queue holds 1,024. The next request is rejected: the handler counts it and throws, and the JDK server closes that connection.
2. Why does the admin listener have its own thread? Answer: so `/metrics` and `/health` answer even when all 64 workers are busy.
3. When does the server believe `X-Request-Id`? Answer: only when the peer is a trusted proxy, the header appears once, and it is 32 lowercase hex characters.
4. A client sends the same `POST /v1/holds` twice with the same key and body. What does the second one get, and where does the server get it from? Answer: the same status and body as the first. The key table (`HoldKeys`) stored the reply after the first request finished.
5. Which cache header does `GET /v1/holds/{id}` carry, and why not `public`? Answer: `private, max-age=60`. It is one customer's hold, so a shared cache such as the proxy must not keep it.

## Further reading

- Java SE 25 API, `com.sun.net.httpserver` (module `jdk.httpserver`): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- Java SE 25 API, `java.util.concurrent.ThreadPoolExecutor`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
- RFC 9110, HTTP Semantics (methods, status codes, `Allow`): https://www.rfc-editor.org/rfc/rfc9110
- RFC 9111, HTTP Caching (`no-cache`, `no-store`, `private`): https://www.rfc-editor.org/rfc/rfc9111
- RFC 8594, The Sunset HTTP header field: https://www.rfc-editor.org/rfc/rfc8594
- RFC 9651, Structured Field Values for HTTP (the Idempotency-Key string form): https://www.rfc-editor.org/rfc/rfc9651
- Prometheus, exposition formats: https://prometheus.io/docs/instrumenting/exposition_formats/
- SLF4J 2.0 manual: https://www.slf4j.org/manual.html
