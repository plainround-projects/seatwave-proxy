package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ContainerCountersTest {

  // What `cat /sys/fs/cgroup/cpu.stat /proc/net/snmp` printed in the lab's proxy container.
  static final String SAMPLE =
      """
      usage_usec 1834567
      user_usec 1200000
      system_usec 634567
      Ip: Forwarding DefaultTTL
      Ip: 1 64
      Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab
      Tcp: 1 200 120000 -1 42 314 0 3 7
      Udp: InDatagrams NoPorts
      Udp: 0 0
      """;

  @Test
  void readsCpuTimeAndTheConnectionsOpenedAndAccepted() {
    Result<Counters, String> parsed = ContainerCounters.parse(SAMPLE);

    assertThat(parsed.discardError()).contains(new Counters(1_834_567, 314, 42));
  }

  @Test
  void saysWhichFileWasNotRead() {
    assertThat(ContainerCounters.parse("Tcp: ActiveOpens\nTcp: 1\n").isError()).isTrue();
    assertThat(ContainerCounters.parse("usage_usec 1\n").isError()).isTrue();
    assertThat(ContainerCounters.parse("usage_usec 1\nTcp: Other Columns\nTcp: 1 2\n").isError())
        .isTrue();
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in for docker is a /bin/sh script")
  void runsTheCommandAsAListAndParsesWhatItPrints(@TempDir Path folder) throws Exception {
    Path fakeDocker = script(folder, "cat <<'END'\n" + SAMPLE + "END");

    Counters counters = new ContainerCounters(fakeDocker.toString()).read("seatwave-proxy-proxy-1");

    assertThat(counters).isEqualTo(new Counters(1_834_567, 314, 42));
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in for docker is a /bin/sh script")
  void reportsAFailedCommandAndRefusesANameThatIsNotAContainerName(@TempDir Path folder)
      throws Exception {
    Path failingDocker = script(folder, "echo 'No such container' >&2; exit 1");
    ContainerCounters counters = new ContainerCounters(failingDocker.toString());

    assertThatThrownBy(() -> counters.read("seatwave-proxy-proxy-1"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("No such container");
    assertThatThrownBy(() -> counters.read("bad name; rm -rf"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void subtractsAnEarlierReadingAndRefusesNegativeCounters() {
    Counters later = new Counters(3_000, 20, 10);

    assertThat(later.since(new Counters(1_000, 5, 4))).isEqualTo(new Counters(2_000, 15, 6));
    assertThatThrownBy(() -> new Counters(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Path script(Path folder, String body) throws IOException {
    Path script = folder.resolve("docker");
    Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }
}
