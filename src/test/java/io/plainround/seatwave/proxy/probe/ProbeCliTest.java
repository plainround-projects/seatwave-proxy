package io.plainround.seatwave.proxy.probe;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeCliTest {

  @Test
  void recordsOneOutcomePerIntervalAndTellsSuccessFromFailure() throws Exception {
    try (TestBackend backend = TestBackend.untrusting()) {
      ProbeSettings settings =
          new ProbeSettings(
              backend.plain("/health"),
              TestBackend.TLS.resolve("seatwave-test-ca.pem"),
              Duration.ofMillis(20),
              Duration.ofMillis(200),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1));
      ProbeSettings refused =
          new ProbeSettings(
              URI.create("http://127.0.0.1:1/health"),
              settings.authorityPem(),
              Duration.ofMillis(20),
              Duration.ofMillis(60),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1));

      List<Outcome> ok = ProbeCli.probe(TestBackend.client(), settings);
      List<Outcome> down = ProbeCli.probe(TestBackend.client(), refused);

      assertThat(ok).hasSizeBetween(5, 11).allMatch(Outcome::isSuccess);
      assertThat(down)
          .isNotEmpty()
          .allMatch(outcome -> outcome.result().equals("connection refused"));
    }
  }

  @Test
  void refusesAnAddressOutsideTheLabAndBadUsage(@TempDir Path folder) throws Exception {
    Path file =
        Files.writeString(
            folder.resolve("probe.properties"),
            Files.readString(Path.of("config/probe.properties"), StandardCharsets.UTF_8)
                .replace("https://127.0.0.1:20900/health", "https://seatwave.example/health"),
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
