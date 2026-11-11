package io.plainround.seatwave.proxy.report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The one rule for turning repeated runs into a reported number: at least {@link #MINIMUM_RUNS}
 * runs, and the median, never the mean or a single run. Thread-safe: it holds no state.
 */
public final class Runs {

  /**
   * The fewest runs a reported timing may come from. With three, one slow or one fast outlier
   * cannot become the reported value; fewer is a sample, not a measurement.
   */
  public static final int MINIMUM_RUNS = 3;

  private Runs() {}

  /**
   * Returns the median run by a key: the middle one, or the lower of the two middle ones.
   *
   * @param runs the runs, at least {@link #MINIMUM_RUNS}
   * @param order the key to sort by, for example the total time
   * @param <T> the run type
   * @return the median run itself, so every field of the result belongs to one real run
   * @throws IllegalArgumentException if there are fewer than {@link #MINIMUM_RUNS} runs
   */
  public static <T> T medianBy(List<T> runs, Comparator<T> order) {
    Objects.requireNonNull(order, "order");
    requireEnough(runs);
    List<T> sorted = new ArrayList<>(runs);
    sorted.sort(order);
    // The lower middle: for an even count it is a value that was really measured, not an average.
    return sorted.get((sorted.size() - 1) / 2);
  }

  /**
   * Returns the median of durations, by the same rule.
   *
   * @param durations at least {@link #MINIMUM_RUNS} durations
   * @return the median duration
   */
  public static Duration median(List<Duration> durations) {
    return medianBy(durations, Comparator.naturalOrder());
  }

  /**
   * Refuses too few runs.
   *
   * @param runs the runs
   * @throws IllegalArgumentException if there are fewer than {@link #MINIMUM_RUNS}
   */
  public static void requireEnough(List<?> runs) {
    Objects.requireNonNull(runs, "runs");
    if (runs.size() < MINIMUM_RUNS) {
      throw new IllegalArgumentException(
          "a timing needs at least " + MINIMUM_RUNS + " runs, but had " + runs.size());
    }
  }
}
