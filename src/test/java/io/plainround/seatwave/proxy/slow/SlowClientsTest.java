package io.plainround.seatwave.proxy.slow;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.BackendSettings;
import io.plainround.seatwave.proxy.backend.TestBackend;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlowClientsTest {

  @Test
  void countsEveryCompleteDownloadOfALargeFileWithItsHeadAndBody() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      SlowSettings settings = settings(backend, backend.plain("/static/poster-arena.bin"), 20_000);

      SlowResult result = SlowClients.run(settings);

      assertThat(result.completed()).isEqualTo(4);
      assertThat(result.failed()).isZero();
      // The 300,000-byte body and a head of a few hundred bytes.
      assertThat(result.responseBytes()).isBetween(300_001L, 301_000L);
      assertThat(result.workers()).isEqualTo(8);
      assertThat(result.workerSeconds()).isPositive();
      assertThat(settings.bytesPerSecond()).isEqualTo(20_000_000);
    }
  }

  @Test
  void seesTheWorkersThatSlowRequestsKeepBusy() throws Exception {
    // A long poll holds its worker for the whole hold, on any kernel; a slow reader of a large file
    // holds one only while the kernel's send buffer is full, which differs between machines.
    BackendSettings.Timing longHold =
        new BackendSettings.Timing(
            Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(350));
    try (TestBackend backend = TestBackend.start(List.of("192.0.2.1"), longHold, 8, 64)) {
      SlowSettings settings =
          settings(backend, backend.plain("/v1/events/1/seat-changes?after=1"), 1_000);

      SlowResult result = SlowClients.run(settings);

      assertThat(result.completed()).isEqualTo(4);
      assertThat(result.workersBusyMax()).isBetween(1, 8);
      assertThat(result.workerSeconds()).isGreaterThanOrEqualTo(4.0);
    }
  }

  @Test
  void readsTheStatusLineAcrossManySmallReads() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      // 10 bytes a read: the status line and the head arrive over many reads.
      SlowResult result = SlowClients.run(settings(backend, backend.plain("/health"), 10));

      assertThat(result.completed()).isEqualTo(4);
      assertThat(result.failed()).isZero();
    }
  }

  @Test
  void countsADownloadThatEndsBeforeItsContentLengthAsFailed() throws Exception {
    // Closed in reverse order: the server socket first, which ends the accepting task.
    try (TestBackend backend = TestBackend.untrusting();
        ExecutorService accepting = Executors.newVirtualThreadPerTaskExecutor();
        ServerSocket shortServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
      accepting.submit(() -> promiseMoreThanIsSent(shortServer));
      URI target = URI.create("http://127.0.0.1:" + shortServer.getLocalPort() + "/poster");

      SlowResult result = SlowClients.run(settings(backend, target, 1_000));

      assertThat(result.completed()).isZero();
      assertThat(result.failed()).isEqualTo(4);
    }
  }

  @Test
  void decidesACompleteDownloadByStatusAndContentLength() {
    String head = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n";

    assertThat(SlowClients.isComplete(head + "hello", head.length() + 5)).isTrue();
    assertThat(SlowClients.isComplete(head + "he", head.length() + 2)).isFalse();
    assertThat(SlowClients.isComplete("HTTP/1.1 200 OK\r\n\r\nhello", 24)).isFalse();
    assertThat(SlowClients.isComplete("HTTP/1.1 200 OK\r\nContent-Length: 5", 34)).isFalse();
  }

  @Test
  void countsADownloadThatIsNotAnOkResponseAsFailed() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      SlowSettings settings =
          new SlowSettings(
              backend.plain("/missing"),
              2,
              1_000,
              Duration.ofMillis(1),
              4_096,
              backend.plain("/health"),
              backend.admin("/metrics"),
              Duration.ofMillis(5),
              Duration.ofSeconds(30));

      SlowResult result = SlowClients.run(settings);

      assertThat(result.completed()).isZero();
      assertThat(result.failed()).isEqualTo(2);
    }
  }

  private static SlowSettings settings(TestBackend backend, URI target, int bytesPerTick) {
    return new SlowSettings(
        target,
        4,
        bytesPerTick,
        Duration.ofMillis(1),
        4_096,
        backend.plain("/health"),
        backend.admin("/metrics"),
        Duration.ofMillis(5),
        Duration.ofSeconds(30));
  }

  private static Void promiseMoreThanIsSent(ServerSocket server) throws IOException {
    // A server that promises 100,000 bytes, sends 20 and closes: every client must call it failed.
    while (!server.isClosed()) {
      try (Socket client = server.accept()) {
        client.getInputStream().read(new byte[1_024]);
        client
            .getOutputStream()
            .write(
                "HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n12345678901234567890"
                    .getBytes(StandardCharsets.US_ASCII));
      } catch (SocketException closed) {
        return null;
      }
    }
    return null;
  }

  @Test
  void readsTheStatusOfAResponseHead() {
    assertThat(SlowClients.status("HTTP/1.1 200 OK\r\nDate: x")).isEqualTo(200);
    assertThat(SlowClients.status("garbage")).isZero();
    assertThat(SlowClients.percentile(List.of(), 0.5)).isEqualTo(Duration.ZERO);
  }

  @Test
  void readsTheRepositorysSettingsAndRefusesAnAddressOutsideTheLabNetwork() throws Exception {
    for (String name : List.of("direct", "buffered", "unbuffered")) {
      SlowSettings.from(
              new SettingsReader(
                  SettingsReader.load(Path.of("config/slow-" + name + ".properties"))))
          .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));
    }
    var properties = SettingsReader.load(Path.of("config/slow-direct.properties"));
    properties.setProperty("slow.targetUri", "http://seatwave.example/");

    assertThat(SlowSettings.from(new SettingsReader(properties)).isError()).isTrue();
  }

  @Test
  void printsARunAndStopsWithStatusOneWhenTheMetricsCannotBeRead() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);
    PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
    int ran;
    int broken;
    try (TestBackend backend = TestBackend.untrusting()) {
      SlowSettings good = settings(backend, backend.plain("/health"), 1_000);
      SlowSettings noMetrics =
          new SlowSettings(
              good.target(),
              1,
              1_000,
              Duration.ofMillis(1),
              4_096,
              good.fastProbe(),
              backend.plain("/missing"),
              Duration.ofMillis(5),
              Duration.ofSeconds(30));

      ran = SlowClientsCli.runAndPrint(good, printer, errors);
      broken = SlowClientsCli.runAndPrint(noMetrics, printer, errors);
    }

    assertThat(ran).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("slow clients: 4 complete, 0 failed");
    assertThat(broken).isEqualTo(SlowClientsCli.EXIT_RUN_FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("the run stopped: ");
  }

  @Test
  void printsTheResultsAndRefusesBadUsage(@TempDir Path folder) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);
    SlowSettings settings =
        SlowSettings.from(
                new SettingsReader(SettingsReader.load(Path.of("config/slow-direct.properties"))))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));
    SlowResult result =
        new SlowResult(
            500,
            0,
            37_188,
            Duration.ofMillis(1_370),
            Duration.ofMillis(2_140),
            64,
            0,
            64,
            104.45,
            Duration.ofMillis(2),
            Duration.ofMillis(1_370),
            Duration.ofMillis(1_370),
            8,
            0);
    Path bad = Files.writeString(folder.resolve("bad.properties"), "", StandardCharsets.UTF_8);

    SlowClientsCli.print(settings, result, printer);
    int usage = SlowClientsCli.run(new String[0], printer, printer);
    int badFile = SlowClientsCli.run(new String[] {bad.toString()}, printer, printer);

    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s)")
        .contains("at most 64 of 64 busy at once (0 readings missed); 104.45 worker-seconds")
        .contains("slow.targetUri is missing");
    assertThat(usage).isEqualTo(SlowClientsCli.EXIT_BAD_USAGE);
    assertThat(badFile).isEqualTo(SlowClientsCli.EXIT_BAD_USAGE);
  }
}
