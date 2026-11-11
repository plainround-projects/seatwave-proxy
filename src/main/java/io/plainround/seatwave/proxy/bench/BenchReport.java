package io.plainround.seatwave.proxy.bench;

import io.plainround.seatwave.proxy.report.Runs;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes the bench table: one row per scenario, the median of its runs by requests per second, with
 * every run's throughput listed so the spread stays visible. A row whose run had many failures, or
 * whose runs differ widely, gets a warning line under it, because its median is not a steady rate.
 * Thread-safe: it holds no state.
 */
public final class BenchReport {

  /** The table's header line. */
  public static final String HEADER =
      "scenario              rps   p50 ms   p99 ms  errors  wire B/resp  plain B/resp  hit %"
          + "  proxy CPU ms/1k  SeatWave CPU ms/1k  proxy accepted  proxy opened  SeatWave accepted";

  private static final String ROW =
      "%-17s %8.0f %8.2f %8.2f %7d %12.0f %13.0f %6s %16.1f %19.1f %15d %13d %18d%n";
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double PERCENT = 100.0;
  // More than 1 percent of failed requests, or a fastest run more than twice the slowest, and the
  // row is not a steady measurement: it is shown, with a warning, never quietly as a median.
  private static final double ERROR_SHARE_LIMIT = 0.01;
  private static final double SPREAD_LIMIT = 2.0;

  private BenchReport() {}

  /**
   * Picks the median run of one scenario by throughput.
   *
   * @param runs the measured runs, at least {@link Runs#MINIMUM_RUNS}
   * @return the median run itself, so every number in its row comes from one real run
   */
  public static BenchRow median(List<BenchRow> runs) {
    return Runs.medianBy(runs, Comparator.comparingDouble(row -> row.result().requestsPerSecond()));
  }

  /**
   * Writes one scenario's row and the throughput of each of its runs.
   *
   * @param runs the measured runs of one scenario
   * @return two lines of text
   */
  public static String render(List<BenchRow> runs) {
    BenchRow row = median(runs);
    LoadResult result = row.result();
    double perResponse = result.requests() == 0 ? 0 : 1.0 / result.requests();
    String hitShare =
        result.cacheLookups() == 0
            ? "-"
            : String.format(
                Locale.ROOT, "%.1f", PERCENT * result.cacheHits() / result.cacheLookups());
    String line =
        String.format(
            Locale.ROOT,
            ROW,
            row.scenario().name(),
            result.requestsPerSecond(),
            millis(result.p50()),
            millis(result.p99()),
            result.errors(),
            result.wireBytes() * perResponse,
            result.plainBytes() * perResponse,
            hitShare,
            row.proxyCpuMillisPerThousand(),
            row.backendCpuMillisPerThousand(),
            row.proxy().passiveOpens(),
            row.proxy().activeOpens(),
            row.backend().passiveOpens());
    String spread =
        runs.stream()
            .map(each ->
                String.format(
                    Locale.ROOT,
                    "%.0f (%d in %.1f s)",
                    each.result().requestsPerSecond(),
                    each.result().requests(),
                    each.result().elapsed().toNanos() / NANOS_PER_SECOND))
            .collect(Collectors.joining(", ", "  runs (rps): ", System.lineSeparator()));
    return line + spread + warnings(runs, result);
  }

  private static String warnings(List<BenchRow> runs, LoadResult median) {
    StringBuilder text = new StringBuilder();
    long sent = median.requests() + median.errors();
    double errorShare = sent == 0 ? 0 : (double) median.errors() / sent;
    if (errorShare > ERROR_SHARE_LIMIT) {
      text.append(
          String.format(
              Locale.ROOT,
              "  warning: %.0f percent of the requests of this run failed; the other columns describe"
                  + " a failing path%n",
              PERCENT * errorShare));
    }
    double slowest =
        runs.stream().mapToDouble(each -> each.result().requestsPerSecond()).min().orElse(0);
    double fastest =
        runs.stream().mapToDouble(each -> each.result().requestsPerSecond()).max().orElse(0);
    if (fastest > slowest * SPREAD_LIMIT) {
      text.append(
          String.format(
              Locale.ROOT,
              "  warning: the runs differ by more than %.0f times (%.0f to %.0f rps), so the median"
                  + " is not a steady rate%n",
              SPREAD_LIMIT,
              slowest,
              fastest));
    }
    return text.toString();
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
