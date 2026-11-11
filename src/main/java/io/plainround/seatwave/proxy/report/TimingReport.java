package io.plainround.seatwave.proxy.report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the phase table of the request timing report as plain text (Module 5's report).
 *
 * <p>The phase table takes checked curl samples, groups them by target and kind of connection, and
 * prints each group's median run. A group with too few runs is listed as refused, never printed as
 * a row. Thread-safe: it holds no state.
 */
public final class TimingReport {

  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double PERCENT = 100.0;
  private static final String PHASE_HEADER =
      "target            connection protocol  runs   dns ms connect ms   tls ms first byte ms"
          + " transfer ms   total ms   fastest  slowest  dominant";
  private static final String PHASE_ROW =
      "%-17s %-10s %-9s %4d %8.2f %10.2f %8.2f %13.2f %11.2f %10.2f %9.2f %8.2f  %s%n";

  private TimingReport() {}

  /**
   * Groups samples into rows, keeping the order in which each target first appears.
   *
   * @param samples checked samples
   * @return one row per target and kind with enough runs, and one line per refused group
   */
  public static Grouped group(List<CurlSample> samples) {
    Map<String, List<CurlSample>> groups = new LinkedHashMap<>();
    for (CurlSample sample : samples) {
      groups
          .computeIfAbsent(sample.target() + " " + sample.kind(), key -> new ArrayList<>())
          .add(sample);
    }
    List<TargetTiming> rows = new ArrayList<>();
    List<String> refused = new ArrayList<>();
    for (List<CurlSample> group : groups.values()) {
      CurlSample first = group.getFirst();
      if (group.size() < Runs.MINIMUM_RUNS) {
        refused.add(
            String.format(
                Locale.ROOT,
                "too few runs for %s over a %s connection: %d, and a row needs %d",
                first.target(),
                first.kind().name().toLowerCase(Locale.ROOT),
                group.size(),
                Runs.MINIMUM_RUNS));
        continue;
      }
      rows.add(new TargetTiming(first.target(), first.kind(), group));
    }
    return new Grouped(rows, refused);
  }

  /**
   * Writes the phase table, one sentence per row, and every refused line or group.
   *
   * @param samples checked samples
   * @param problems the lines that did not become samples
   * @return the text of the table
   */
  public static String renderPhases(List<CurlSample> samples, List<CurlProblem> problems) {
    Objects.requireNonNull(problems, "problems");
    Grouped grouped = group(samples);
    StringBuilder text = new StringBuilder(PHASE_HEADER).append(System.lineSeparator());
    for (TargetTiming row : grouped.rows()) {
      CurlSample median = row.medianRun();
      PhaseTimings phases = median.phases();
      text.append(
          String.format(
              Locale.ROOT,
              PHASE_ROW,
              row.target(),
              row.kind().name().toLowerCase(Locale.ROOT),
              median.protocol(),
              row.runs().size(),
              millis(phases.dns()),
              millis(phases.connect()),
              millis(phases.tls()),
              millis(phases.firstByte()),
              millis(phases.transfer()),
              millis(phases.total()),
              millis(row.fastest()),
              millis(row.slowest()),
              phases.dominant().label()));
    }
    text.append(System.lineSeparator());
    for (TargetTiming row : grouped.rows()) {
      text.append(sentence(row)).append(System.lineSeparator());
    }
    for (String refusedGroup : grouped.refused()) {
      text.append("refused: ").append(refusedGroup).append(System.lineSeparator());
    }
    for (CurlProblem problem : problems) {
      text.append("refused: ").append(problem.describe()).append(System.lineSeparator());
    }
    return text.toString();
  }

  private static String sentence(TargetTiming row) {
    PhaseTimings phases = row.medianRun().phases();
    Phase dominant = phases.dominant();
    double dominantMillis = millis(phases.of(dominant));
    double totalMillis = millis(phases.total());
    double share = totalMillis == 0 ? 0 : PERCENT * dominantMillis / totalMillis;
    return String.format(
        Locale.ROOT,
        "%s, %s connection: %s dominates the median run, %.2f of %.2f ms (%.0f percent).",
        row.target(),
        row.kind().name().toLowerCase(Locale.ROOT),
        dominant.label(),
        dominantMillis,
        totalMillis,
        share);
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }

  /**
   * The result of grouping.
   *
   * @param rows the rows with enough runs
   * @param refused one line per group with too few runs
   */
  public record Grouped(List<TargetTiming> rows, List<String> refused) {

    /** Copies both lists. */
    public Grouped {
      rows = List.copyOf(rows);
      refused = List.copyOf(refused);
    }
  }
}
