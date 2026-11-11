package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.proxy.backend.TestBackend;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadRunTest {

  private static final Duration RUN = Duration.ofMillis(300);
  private static final long SEAT_MAP_BYTES = 36_894;

  private TestBackend backend;
  private LoadRun runner;

  @BeforeEach
  void start() throws Exception {
    backend = TestBackend.untrusting();
    runner =
        new LoadRun(
            TlsFiles.clientContext(TestBackend.TLS.resolve("seatwave-test-ca.pem")),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    backend.close();
  }

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

  @Test
  void countsCacheLookupsAndHitsFromTheCacheStatusHeader() throws Exception {
    try (CacheStand cache = new CacheStand()) {
      LoadResult result =
          runner.run(new Scenario("cached", cache.uri(), false, ConnectionMode.REUSE), RUN, 1);

      assertThat(result.cacheLookups()).isEqualTo(result.requests()).isPositive();
      // The stand-in answers HIT and MISS in turn, from HIT: half the lookups, rounded up, hit.
      assertThat(result.cacheHits()).isEqualTo((result.requests() + 1) / 2);
    }
  }

  @Test
  void countsARefusedConnectionAsAnErrorAndKeepsGoing() throws Exception {
    URI closed;
    try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = URI.create("http://127.0.0.1:" + gone.getLocalPort() + "/health");
    }

    LoadResult reused =
        runner.run(new Scenario("refused", closed, false, ConnectionMode.REUSE), RUN, 1);
    LoadResult fresh =
        runner.run(new Scenario("refused", closed, false, ConnectionMode.NEW), RUN, 1);

    assertThat(reused.requests()).isZero();
    assertThat(reused.errors()).isGreaterThan(1);
    assertThat(fresh.requests()).isZero();
    assertThat(fresh.errors()).isGreaterThan(1);
  }

  @Test
  void sendsTheSameHostHeaderWithThePortInBothModes() throws Exception {
    try (CacheStand stand = new CacheStand()) {
      runner.run(new Scenario("host-reuse", stand.uri(), false, ConnectionMode.REUSE), RUN, 1);
      runner.run(new Scenario("host-new", stand.uri(), false, ConnectionMode.NEW), RUN, 1);

      assertThat(stand.hosts()).containsExactly("127.0.0.1:" + stand.uri().getPort());
    }
  }

  @Test
  void takesPercentilesByTheNearestRankRule() {
    List<Long> sorted = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

    assertThat(LoadRun.percentile(sorted, 0.50)).isEqualTo(Duration.ofNanos(5));
    assertThat(LoadRun.percentile(sorted, 0.99)).isEqualTo(Duration.ofNanos(10));
    assertThat(LoadRun.percentile(List.of(), 0.99)).isEqualTo(Duration.ZERO);
  }

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

  /**
   * A server that answers every request with a small body and an {@code X-Cache-Status} of HIT and
   * MISS in turn, the header the proxy adds, and remembers every Host header it was sent.
   */
  private static final class CacheStand implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger answered = new AtomicInteger();
    private final Set<String> hosts = ConcurrentHashMap.newKeySet();

    CacheStand() throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              hosts.add(exchange.getRequestHeaders().getFirst("Host"));
              boolean isHit = answered.getAndIncrement() % 2 == 0;
              byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("X-Cache-Status", isHit ? "HIT" : "MISS");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            }
          });
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/static/x");
    }

    Set<String> hosts() {
      return Set.copyOf(hosts);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
