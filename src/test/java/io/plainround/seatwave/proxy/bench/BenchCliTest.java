package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchCliTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @Test
  void printsTheUsageAndAnySettingsProblemWithExitStatusTwo(@TempDir Path folder) throws Exception {
    Path bad =
        Files.writeString(
            folder.resolve("bad.properties"), "run.clients=0\n", StandardCharsets.UTF_8);
    ContainerCounters docker = new ContainerCounters("docker");

    int noArguments = BenchCli.run(new String[0], docker, print(out), print(err));
    int badFile = BenchCli.run(new String[] {bad.toString()}, docker, print(out), print(err));

    assertThat(noArguments).isEqualTo(BenchCli.EXIT_BAD_USAGE);
    assertThat(badFile).isEqualTo(BenchCli.EXIT_BAD_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("usage: bench", "run.clients must be from 1 to 2000, but was 0");
  }

  @Test
  void stopsWithExitStatusOneWhenTheContainersCannotBeRead(@TempDir Path folder) throws Exception {
    Path settings = quickSettings(folder);
    ContainerCounters missingDocker = new ContainerCounters(folder.resolve("no-docker").toString());

    int status =
        BenchCli.run(new String[] {settings.toString()}, missingDocker, print(out), print(err));

    assertThat(status).isEqualTo(BenchCli.EXIT_STACK_FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("the bench stopped:");
    assertThat(out.toString(StandardCharsets.UTF_8)).contains(BenchReport.HEADER);
  }

  private static Path quickSettings(Path folder) throws IOException {
    String text =
        Files.readString(Path.of("config/bench.properties"), StandardCharsets.UTF_8)
            .replace("run.warmupMillis=5000", "run.warmupMillis=0")
            .replace(
                "tls.authorityPem=build/tls/",
                "tls.authorityPem=" + io.plainround.seatwave.proxy.backend.TestBackend.TLS + "/");
    return Files.writeString(folder.resolve("bench.properties"), text, StandardCharsets.UTF_8);
  }

  private static PrintStream print(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }
}
