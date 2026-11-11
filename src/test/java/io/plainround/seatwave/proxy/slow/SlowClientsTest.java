package io.plainround.seatwave.proxy.slow;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlowClientsTest {

  @Test
  void keepsSeatWaveWorkersBusyWhileSlowClientsReadAndCountsEveryDownload() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      SlowSettings settings =
          new SlowSettings(
              backend.plain("/static/poster-arena.bin"),
              4,
              20_000,
              Duration.ofMillis(5),
              4_096,
              backend.plain("/health"),
              backend.admin("/metrics"),
              Duration.ofMillis(5),
              Duration.ofSeconds(30));

      SlowResult result = SlowClients.run(settings);

      assertThat(result.completed()).isEqualTo(4);
      assertThat(result.failed()).isZero();
      assertThat(result.bodyBytes()).isGreaterThan(300_000);
      assertThat(result.workers()).isEqualTo(8);
      assertThat(result.workerSeconds()).isPositive();
      assertThat(settings.bytesPerSecond()).isEqualTo(4_000_000);
    }
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
