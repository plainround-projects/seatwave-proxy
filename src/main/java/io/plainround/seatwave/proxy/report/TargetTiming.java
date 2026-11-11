package io.plainround.seatwave.proxy.report;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One row of the timing report: every run of one target over one kind of connection.
 *
 * <p>The row reports its median run, chosen by total time, and that run's own phases. Because the
 * row's numbers all come from one real run, its phases still sum to its total; medians taken phase
 * by phase would not. A row cannot exist with fewer than {@link Runs#MINIMUM_RUNS} runs.
 *
 * @param target the target's name
 * @param kind new or reused connections
 * @param runs the runs, at least {@link Runs#MINIMUM_RUNS}, all of this target and kind
 */
public record TargetTiming(String target, ConnectionKind kind, List<CurlSample> runs) {

  /**
   * Creates the row.
   *
   * @throws IllegalArgumentException if there are too few runs, or a run belongs to another row
   */
  public TargetTiming {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(kind, "kind");
    runs = List.copyOf(runs);
    Runs.requireEnough(runs);
    for (CurlSample run : runs) {
      if (!run.target().equals(target) || run.kind() != kind) {
        throw new IllegalArgumentException("a run of " + run.target() + " is not in this row");
      }
    }
  }

  /**
   * Returns the median run by total time.
   *
   * @return the run whose phases the report prints
   */
  public CurlSample medianRun() {
    return Runs.medianBy(runs, Comparator.comparing(run -> run.phases().total()));
  }

  /**
   * Returns the shortest total of all runs.
   *
   * @return the fastest run's total
   */
  public Duration fastest() {
    return runs.stream()
        .map(run -> run.phases().total())
        .min(Comparator.naturalOrder())
        .orElseThrow();
  }

  /**
   * Returns the longest total of all runs.
   *
   * @return the slowest run's total
   */
  public Duration slowest() {
    return runs.stream()
        .map(run -> run.phases().total())
        .max(Comparator.naturalOrder())
        .orElseThrow();
  }
}
