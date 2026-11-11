package io.plainround.seatwave.proxy.backend;

import io.plainround.seatwave.proxy.forwarded.TrustedProxies;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;

/**
 * A SeatWave backend on 127.0.0.1 with ports the system picks, for one test, and a client for it.
 * The TEST certificates come from the build's testCertificates task.
 */
public final class TestBackend implements AutoCloseable {

  /** The folder the build writes the TEST certificates to. */
  public static final Path TLS = Path.of(System.getProperty("seatwave.tls.directory"));

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final BackendServer server;
  private final HttpClient client;

  private TestBackend(BackendServer server, HttpClient client) {
    this.server = server;
    this.client = client;
  }

  /**
   * Starts a backend.
   *
   * @param trusted the trusted proxy addresses
   * @param timing the slow routes' durations
   * @param workers worker threads
   * @param queue queue capacity
   * @return the running backend
   */
  public static TestBackend start(
      List<String> trusted, BackendSettings.Timing timing, int workers, int queue) {
    try {
      BackendSettings settings =
          new BackendSettings(
              InetAddress.getLoopbackAddress(),
              0,
              0,
              0,
              TLS.resolve("seatwave-server.p12"),
              TLS.resolve("store.password"),
              128,
              workers,
              queue,
              TrustedProxies.parse(trusted).orOnErrorThrow(IllegalArgumentException::new),
              timing);
      BackendServer server =
          BackendServer.start(
              settings,
              TlsFiles.serverContext(settings.tlsKeystore(), settings.tlsPasswordFile()),
              Clock.systemUTC());
      return new TestBackend(server, client());
    } catch (IOException cannotStart) {
      throw new UncheckedIOException(cannotStart);
    }
  }

  /**
   * Starts a backend that trusts no peer of this machine, with quick slow routes.
   *
   * @return the running backend
   */
  public static TestBackend untrusting() {
    return start(List.of("192.0.2.1"), quickTiming(), 8, 64);
  }

  /**
   * Returns slow routes that finish quickly: a 200 ms long poll and a 100 ms heartbeat.
   *
   * @return the timing
   */
  public static BackendSettings.Timing quickTiming() {
    return new BackendSettings.Timing(
        Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofMillis(350));
  }

  /**
   * Returns a client that trusts the TEST certificate authority only.
   *
   * @return the client
   */
  public static HttpClient client() {
    try {
      SSLContext tls = TlsFiles.clientContext(TLS.resolve("seatwave-test-ca.pem"));
      return HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .sslContext(tls)
          .connectTimeout(TIMEOUT)
          .build();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * Returns a plain HTTP address on the backend.
   *
   * @param pathAndQuery for example {@code /health}
   * @return the address
   */
  public URI plain(String pathAndQuery) {
    return URI.create("http://127.0.0.1:" + server.plainPort() + pathAndQuery);
  }

  /**
   * Returns an HTTPS address on the backend.
   *
   * @param pathAndQuery for example {@code /health}
   * @return the address
   */
  public URI tls(String pathAndQuery) {
    return URI.create("https://127.0.0.1:" + server.tlsPort() + pathAndQuery);
  }

  /**
   * Returns an address on the operations listener.
   *
   * @param path for example {@code /metrics}
   * @return the address
   */
  public URI admin(String path) {
    return URI.create("http://127.0.0.1:" + server.adminPort() + path);
  }

  /**
   * Sends one request and reads the body as text.
   *
   * @param request the request
   * @return the response
   */
  public HttpResponse<String> send(HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", interrupted);
    }
  }

  /**
   * Sends a GET.
   *
   * @param uri the address
   * @return the response
   */
  public HttpResponse<String> get(URI uri) {
    return send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build());
  }

  /**
   * Returns SeatWave's metrics text.
   *
   * @return the text
   */
  public String metrics() {
    return get(admin("/metrics")).body();
  }

  /**
   * Returns the server itself.
   *
   * @return the server
   */
  public BackendServer server() {
    return server;
  }

  @Override
  public void close() {
    client.close();
    server.close();
  }
}
