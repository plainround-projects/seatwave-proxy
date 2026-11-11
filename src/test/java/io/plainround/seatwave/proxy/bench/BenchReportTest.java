package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenchReportTest {

  private static final Scenario SCENARIO =
      new Scenario(
          "production", URI.create("https://127.0.0.1:20900/health"), true, ConnectionMode.REUSE);

  @Test
  void printsTheMedianRunByThroughputWithItsOwnCountersAndEveryRunsThroughput() {
    List<BenchRow> runs =
        List.of(row(1_000, 2_000_000), row(3_000, 6_000_000), row(2_000, 3_000_000));

    String text = BenchReport.render(runs);

    assertThat(BenchReport.median(runs).result().requests()).isEqualTo(2_000);
    assertThat(text)
        .startsWith("production")
        .contains("1500.0") // the proxy's 3,000,000 us of CPU over 2,000 requests, per thousand
        .contains("50.0") // a hit share of 50 percent
        .contains("runs (rps): 1000 (1000 in 1.0 s), 3000 (3000 in 1.0 s), 2000 (2000 in 1.0 s)");
  }

  @Test
  void refusesFewerThanThreeRunsAndMoreHitsThanLookups() {
    assertThatThrownBy(() -> BenchReport.median(List.of(row(1, 1), row(2, 2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new LoadResult(1, 0, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 1, 1, 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static BenchRow row(long requests, long proxyCpuMicros) {
    LoadResult result =
        new LoadResult(
            requests,
            0,
            Duration.ofSeconds(1),
            Duration.ofMillis(1),
            Duration.ofMillis(2),
            requests * 2_453,
            requests * 36_894,
            requests / 2,
            requests);
    return new BenchRow(
        SCENARIO, result, new Counters(proxyCpuMicros, 8, 4), new Counters(1_000, 4, 0));
  }
}
