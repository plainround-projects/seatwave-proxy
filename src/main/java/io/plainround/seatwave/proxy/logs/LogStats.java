package io.plainround.seatwave.proxy.logs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Turns access log lines into the proxy's operations numbers, per listening port: requests, the
 * share SeatWave answered with a 5xx, the cache hit ratio over every lookup and over static files
 * alone, and the time the proxy itself added at p50 and p99. Thread-safe: it holds no state.
 */
public final class LogStats {

  /** The table's header line. */
  public static final String HEADER =
      "port   requests  to SeatWave  SeatWave 5xx %  cache lookups  hit %  static hit %"
          + "  proxy-added p50 ms  proxy-added p99 ms";

  private static final int FIRST_SERVER_ERROR = 500;
  private static final String STATIC_PREFIX = "/static/";
  private static final double PERCENT = 100.0;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;

  private LogStats() {}

  /**
   * Writes one row per port.
   *
   * @param lines the parsed lines
   * @return the table
   */
  public static String render(List<AccessLine> lines) {
    Objects.requireNonNull(lines, "lines");
    Map<Integer, List<AccessLine>> byPort = new TreeMap<>();
    for (AccessLine line : lines) {
      byPort.computeIfAbsent(line.port(), port -> new ArrayList<>()).add(line);
    }
    StringBuilder text = new StringBuilder(HEADER).append(System.lineSeparator());
    byPort.forEach((port, group) -> text.append(row(port, group)));
    return text.toString();
  }

  private static String row(int port, List<AccessLine> group) {
    List<AccessLine> asked = group.stream().filter(AccessLine::askedUpstream).toList();
    long serverErrors =
        asked.stream().filter(line -> line.upstreamStatus() >= FIRST_SERVER_ERROR).count();
    List<AccessLine> lookups =
        group.stream().filter(line -> !line.cacheStatus().isEmpty()).toList();
    long hits = lookups.stream().filter(line -> line.cacheStatus().equals("HIT")).count();
    List<AccessLine> staticLookups =
        lookups.stream().filter(line -> line.uri().startsWith(STATIC_PREFIX)).toList();
    long staticHits =
        staticLookups.stream().filter(line -> line.cacheStatus().equals("HIT")).count();
    List<Duration> added = asked.stream().map(AccessLine::proxyAdded).sorted().toList();
    return String.format(
        Locale.ROOT,
        "%-6d %8d %12d %15s %14d %6s %13s %19.2f %19.2f%n",
        port,
        group.size(),
        asked.size(),
        asked.isEmpty()
            ? "-"
            : String.format(Locale.ROOT, "%.2f", PERCENT * serverErrors / asked.size()),
        lookups.size(),
        share(hits, lookups.size()),
        share(staticHits, staticLookups.size()),
        millis(percentile(added, PERCENTILE_50)),
        millis(percentile(added, PERCENTILE_99)));
  }

  private static String share(long part, long whole) {
    return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f", PERCENT * part / whole);
  }

  static Duration percentile(List<Duration> sorted, double share) {
    if (sorted.isEmpty()) {
      return Duration.ZERO;
    }
    int rank = (int) Math.ceil(share * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
