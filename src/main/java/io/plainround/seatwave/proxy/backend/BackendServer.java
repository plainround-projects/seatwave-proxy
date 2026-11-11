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

  private final HttpServer plain;
  private final HttpsServer tls;
  private final HttpServer admin;
  private final ExecutorService adminThread;
  private final ThreadPoolExecutor workers;
  private final BackendSettings settings;
  private final SeatWaveRoutes routes;
  private final BackendMetrics metrics;

  private BackendServer(BackendSettings settings, SSLContext tlsContext, Clock clock)
      throws IOException {
    this.settings = settings;
    this.metrics = new BackendMetrics(settings.workerThreads());
    this.routes =
        new SeatWaveRoutes(new HoldLedger(clock), metrics, settings.timing(), new JsonCodec());
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
    // One thread of its own: the operations listener answers even when every worker is busy.
    this.adminThread =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "seatwave-admin"));
    admin.setExecutor(adminThread);
    admin.createContext("/", this::handleAdmin);
    tls.setHttpsConfigurator(tlsOnlyVersion13(tlsContext));
    plain.setExecutor(workers);
    tls.setExecutor(workers);
    plain.createContext("/", exchange -> handle(exchange, "http"));
    tls.createContext("/", exchange -> handle(exchange, "https"));
    plain.start();
    tls.start();
    admin.start();
  }

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
    } catch (IOException clientGone) {
      // The client closed the connection while SeatWave was reading or writing; nothing to answer.
      LOG.debug("connection closed by the client: {}", clientGone.getMessage());
    } finally {
      metrics.workerFinished(route, status, bytes, System.nanoTime() - startNanos);
      MDC.clear();
    }
  }

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
    List<String> requestIds = headers.getOrDefault("x-request-id", List.of());
    String correlationId =
        isTrustedPeer
                && requestIds.size() == 1
                && REQUEST_ID.matcher(requestIds.getFirst()).matches()
            ? requestIds.getFirst()
            : UUID.randomUUID().toString().replace("-", "");
    byte[] body;
    boolean tooLarge;
    try (InputStream in = exchange.getRequestBody()) {
      // One byte more than the limit is read, so a body over the limit is noticed without reading
      // all of it.
      body = in.readNBytes(SeatWaveRoutes.MAX_BODY_BYTES + 1);
      tooLarge = body.length > SeatWaveRoutes.MAX_BODY_BYTES;
    }
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
