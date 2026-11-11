package io.plainround.seatwave.proxy.probe;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeCliTest {

  @Test
  void recordsOneOutcomePerIntervalAndTellsSuccessFromFailure() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      // 200 ms at one request every 20 ms: the schedule sends exactly 10, however long each takes.
      ProbeSettings settings = settings(backend.plain("/health"), Duration.ofMillis(200));

      List<Outcome> ok = ProbeCli.probe(TestBackend.client(), settings);
      List<Outcome> down =
          ProbeCli.probe(TestBackend.client(), settings(closedPort("http"), Duration.ofMillis(60)));

      assertThat(ok).hasSize(10).allMatch(Outcome::isSuccess);
      assertThat(down)
          .hasSize(3)
          .allMatch(outcome -> outcome.result().equals("connection refused"));
    }
  }

  @Test
  void namesAnAnswerThatIsNotTlsAndATlsHandshakeWithNoAnswer() throws Exception {
    // Closed in reverse order: the server sockets first, which ends the accepting tasks.
    try (ExecutorService accepting = Executors.newVirtualThreadPerTaskExecutor();
        ServerSocket plainText = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        ServerSocket silent = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
      // A server that answers at once, but in plain HTTP, not with a TLS handshake.
      accepting.submit(() -> answerInPlainText(plainText));
      URI wrongProtocol = URI.create("https://127.0.0.1:" + plainText.getLocalPort() + "/health");
      // A server that accepts the connection and never says a word.
      accepting.submit(() -> holdOpen(silent));
      URI mute = URI.create("https://127.0.0.1:" + silent.getLocalPort() + "/health");

      List<Outcome> wrongPort =
          ProbeCli.probe(TestBackend.client(), settings(wrongProtocol, Duration.ofMillis(20)));
      List<Outcome> noAnswer =
          ProbeCli.probe(quickConnectClient(), settings(mute, Duration.ofMillis(20)));

      assertThat(wrongPort).extracting(Outcome::result).containsOnly("TLS failed");
      assertThat(noAnswer).extracting(Outcome::result).containsOnly("connect or TLS timeout");
    }
  }

  @Test
  void printsTheTimelineOfAShortProbeWithItsLengthInMilliseconds() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);
    int status;
    try (TestBackend backend = TestBackend.untrusting()) {
      ProbeSettings settings =
          new ProbeSettings(
              backend.plain("/health"),
              TestBackend.TLS.resolve("seatwave-test-ca.pem"),
              Duration.ofMillis(100),
              Duration.ofMillis(500),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1));
      status = ProbeCli.probeAndPrint(settings, printer, printer);
    }

    assertThat(status).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("every 100 ms for 500 ms, one client")
        .contains("200                    x    5")
        .contains("outage: none, every request succeeded");
  }

  private static ProbeSettings settings(URI uri, Duration duration) {
    return new ProbeSettings(
        uri,
        TestBackend.TLS.resolve("seatwave-test-ca.pem"),
        Duration.ofMillis(20),
        duration,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }

  private static URI closedPort(String scheme) throws IOException {
    // A port this machine handed out and took back: nothing listens on it now.
    try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return URI.create(scheme + "://127.0.0.1:" + gone.getLocalPort() + "/health");
    }
  }

  private static HttpClient quickConnectClient() throws IOException {
    // A connect timeout of 200 ms, so the silent server's handshake gives up quickly.
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(TlsFiles.clientContext(TestBackend.TLS.resolve("seatwave-test-ca.pem")))
        .connectTimeout(Duration.ofMillis(200))
        .build();
  }

  private static Void answerInPlainText(ServerSocket server) throws IOException {
    while (!server.isClosed()) {
      try (Socket client = server.accept()) {
        client
            .getOutputStream()
            .write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      } catch (SocketException closed) {
        return null;
      }
    }
    return null;
  }

  private static Void holdOpen(ServerSocket server) throws IOException, InterruptedException {
    List<Socket> held = new ArrayList<>();
    try {
      while (!server.isClosed()) {
        held.add(server.accept());
      }
    } catch (SocketException closed) {
      // The test closed the server: the run is over.
    } finally {
      for (Socket socket : held) {
        socket.close();
      }
    }
    return null;
  }

  @Test
  void refusesAnAddressOutsideTheLabAndBadUsage(@TempDir Path folder) throws Exception {
    String original = Files.readString(Path.of("config/probe.properties"), StandardCharsets.UTF_8);
    // The text replaced below must be there, or the test would check the repository's own file.
    assertThat(original).contains("https://127.0.0.1:20900/health");
    Path file =
        Files.writeString(
            folder.resolve("probe.properties"),
            original.replace("https://127.0.0.1:20900/health", "https://seatwave.example/health"),
            StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
    PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    assertThat(ProbeCli.run(new String[] {file.toString()}, out, errors))
        .isEqualTo(ProbeCli.EXIT_BAD_USAGE);
    assertThat(ProbeCli.run(new String[0], out, errors)).isEqualTo(ProbeCli.EXIT_BAD_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("probe.uri must match", "usage: probe");
  }
}
