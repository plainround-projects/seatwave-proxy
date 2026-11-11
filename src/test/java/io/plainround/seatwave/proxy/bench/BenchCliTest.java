package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in for docker is a /bin/sh script")
  void printsOneRowPerScenarioFromARunAgainstSeatWave(@TempDir Path folder) throws Exception {
    ContainerCounters steady =
        new ContainerCounters(
            fakeDocker(folder, "echo 'usage_usec 5000'; echo \"$TCP\"").toString());
    int status;
    try (TestBackend backend = TestBackend.untrusting()) {
      status = BenchCli.bench(settings(backend), steady, print(out), print(err));
    }

    assertThat(status).isZero();
    assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(BenchReport.HEADER, "health ", "  runs (rps): ");
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in for docker is a /bin/sh script")
  void stopsWithExitStatusOneWhenAContainerRestartsDuringARun(@TempDir Path folder)
      throws Exception {
    // Each reading is lower than the one before, as if the container had started again.
    String goingDown =
        "used=$(cat \"$0.count\" 2>/dev/null || echo 900000); echo $((used - 1000)) > \"$0.count\";"
            + " echo \"usage_usec $used\"; echo \"$TCP\"";
    ContainerCounters restarting = new ContainerCounters(fakeDocker(folder, goingDown).toString());
    int status;
    try (TestBackend backend = TestBackend.untrusting()) {
      status = BenchCli.bench(settings(backend), restarting, print(out), print(err));
    }

    assertThat(status).isEqualTo(BenchCli.EXIT_STACK_FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .startsWith("the bench stopped: the counters of seatwave-proxy-proxy-1 went down");
  }

  private static BenchSettings settings(TestBackend backend) {
    return new BenchSettings(
        TestBackend.TLS.resolve("seatwave-test-ca.pem"),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ZERO,
        Duration.ofMillis(100),
        3,
        2,
        "seatwave-proxy-proxy-1",
        "seatwave-proxy-backend-1",
        List.of(new Scenario("health", backend.plain("/health"), false, ConnectionMode.REUSE)));
  }

  private static Path fakeDocker(Path folder, String body) throws IOException {
    Path script = folder.resolve("docker");
    String tcp = "TCP='Tcp: ActiveOpens PassiveOpens\nTcp: 4 8'; TCP=$(printf \"$TCP\")";
    Files.writeString(script, "#!/bin/sh\n" + tcp + "\n" + body + "\n", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }

  private static Path quickSettings(Path folder) throws IOException {
    String original = Files.readString(Path.of("config/bench.properties"), StandardCharsets.UTF_8);
    // Each replaced text must be there: if the file changed, a silent no-op would leave a 5 s
    // warm-up or the relative CA path in a test that is meant to be quick.
    assertThat(original).contains("run.warmupMillis=5000", "tls.authorityPem=build/tls/");
    String text =
        original
            .replace("run.warmupMillis=5000", "run.warmupMillis=0")
            .replace("tls.authorityPem=build/tls/", "tls.authorityPem=" + TestBackend.TLS + "/");
    return Files.writeString(folder.resolve("bench.properties"), text, StandardCharsets.UTF_8);
  }

  private static PrintStream print(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }
}
