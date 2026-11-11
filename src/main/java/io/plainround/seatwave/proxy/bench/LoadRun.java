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

  private static final int FIRST_SUCCESS = 200;
  private static final int FIRST_NON_SUCCESS = 300;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
  private static final int HTTPS_PORT = 443;
  private static final int HTTP_PORT = 80;
  // Long enough for the slowest scenario's response; the workers are told to stop before this.
  private static final Duration JOIN_GRACE = Duration.ofSeconds(30);

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

  private HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(tls)
        .connectTimeout(connectTimeout)
        .build();
  }

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

  private Answer fresh(Scenario scenario, long deadlineNanos) throws IOException {
    String host = scenario.uri().getHost();
    boolean isHttps = "https".equals(scenario.uri().getScheme());
    int port =
        scenario.uri().getPort() > 0 ? scenario.uri().getPort() : isHttps ? HTTPS_PORT : HTTP_PORT;
    try (Socket socket = isHttps ? tls.getSocketFactory().createSocket() : new Socket()) {
      socket.connect(new InetSocketAddress(host, port), Math.toIntExact(connectTimeout.toMillis()));
      socket.setSoTimeout(Math.toIntExact(requestTimeout.toMillis()));
      if (socket instanceof SSLSocket secure) {
        SSLParameters parameters = secure.getSSLParameters();
        // The same name and address checks HttpClient makes: the certificate must be for this host.
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        secure.setSSLParameters(parameters);
        secure.startHandshake();
      }
      String path =
          scenario.uri().getRawPath()
              + (scenario.uri().getRawQuery() == null ? "" : "?" + scenario.uri().getRawQuery());
      String head =
          "GET "
              + path
              + " HTTP/1.1\r\nHost: "
              + host
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

  /** One response as a client saw it. */
  record Answer(int status, long wireBytes, long plainBytes, String cacheStatus) {}

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
}
