package io.plainround.seatwave.proxy.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogStatsTest {

  static final String MISS =
      "time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=200 rt=0.004 urt=0.001 uct=0.000"
          + " cache=MISS bytes=300473 id=cdcc client=10.209.9.1 method=GET uri=/static/poster-arena.bin";
  static final String HIT =
      "time=2026-11-11T04:22:47+00:00 port=8443 status=200 us=- rt=0.000 urt=- uct=- cache=HIT"
          + " bytes=300463 id=ea17 client=10.209.9.1 method=GET uri=/static/poster-arena.bin";
  static final String BAD_GATEWAY =
      "time=2026-11-11T04:22:47+00:00 port=8447 status=502 us=502, 502 rt=0.003 urt=0.001, 0.001"
          + " uct=- cache=- bytes=335 id=23dd client=10.209.9.1 method=GET uri=/lab/client";

  @Test
  void readsALineAndSplitsTheProxysTimeFromSeatWaves() {
    AccessLine line = AccessLine.parse(MISS).discardError().orElseThrow();

    assertThat(line.port()).isEqualTo(8443);
    assertThat(line.cacheStatus()).isEqualTo("MISS");
    assertThat(line.proxyAdded()).isEqualTo(Duration.ofMillis(3));
    assertThat(AccessLine.parse(HIT).discardError().orElseThrow().askedUpstream()).isFalse();
    assertThat(AccessLine.parse(BAD_GATEWAY).discardError().orElseThrow().upstreamTime())
        .isEqualTo(Duration.ofMillis(2));
  }

  @Test
  void refusesALineWithoutItsFields() {
    assertThat(AccessLine.parse("port=8443 status=200").isError()).isTrue();
    assertThat(AccessLine.parse("port=8443 uri=/").isError()).isTrue();
    assertThat(AccessLine.parse(MISS.replace("rt=0.004", "rt=fast")).isError()).isTrue();
  }

  @Test
  void writesHitRatioUpstreamErrorsAndProxyAddedTimePerPort() {
    List<AccessLine> lines =
        List.of(MISS, HIT, BAD_GATEWAY).stream()
            .map(text -> AccessLine.parse(text).discardError().orElseThrow())
            .toList();

    String table = LogStats.render(lines);

    assertThat(table)
        .contains(
            "8443          2            1            0.00              2   50.0          50.0                3.00")
        .contains(
            "8447          1            1          100.00              0      -             -                1.00");
  }

  @Test
  void printsTheTableAndCountsUnreadableLines(@TempDir Path folder) throws Exception {
    Path log =
        Files.writeString(
            folder.resolve("access.log"), MISS + "\n" + "garbage\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);

    int status = LogStatsCli.run(new String[] {log.toString()}, printer, printer);
    int missing =
        LogStatsCli.run(new String[] {folder.resolve("none.log").toString()}, printer, printer);
    int usage = LogStatsCli.run(new String[0], printer, printer);

    assertThat(status).isEqualTo(LogStatsCli.EXIT_UNREADABLE_LINES);
    assertThat(missing).isEqualTo(LogStatsCli.EXIT_BAD_USAGE);
    assertThat(usage).isEqualTo(LogStatsCli.EXIT_BAD_USAGE);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(LogStats.HEADER, "1 line(s) could not be read");
  }
}
