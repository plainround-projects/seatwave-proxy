package io.plainround.seatwave.proxy.probe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a probe's outcomes into spans of equal results, so an outage reads as three lines (before,
 * during, after) instead of hundreds. Thread-safe: it holds no state.
 */
public final class Timeline {

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private Timeline() {}

  /**
   * Groups consecutive outcomes with the same result.
   *
   * @param outcomes in the order they were sent
   * @return one span per run of equal results
   */
  public static List<Span> spans(List<Outcome> outcomes) {
    Objects.requireNonNull(outcomes, "outcomes");
    List<Span> spans = new ArrayList<>();
    List<Outcome> current = new ArrayList<>();
    for (Outcome outcome : outcomes) {
      if (!current.isEmpty() && !current.getFirst().result().equals(outcome.result())) {
        spans.add(new Span(current));
        current = new ArrayList<>();
      }
      current.add(outcome);
    }
    if (!current.isEmpty()) {
      spans.add(new Span(current));
    }
    return List.copyOf(spans);
  }

  /**
   * Returns how long the clients were without service: from the first failed request to the first
   * success after the last failed one.
   *
   * @param outcomes in the order they were sent
   * @return the outage, or empty if nothing failed or service never came back
   */
  public static Optional<Duration> outage(List<Outcome> outcomes) {
    int firstFailure = -1;
    int lastFailure = -1;
    for (int index = 0; index < outcomes.size(); index++) {
      if (!outcomes.get(index).isSuccess()) {
        firstFailure = firstFailure < 0 ? index : firstFailure;
        lastFailure = index;
      }
    }
    if (firstFailure < 0 || lastFailure == outcomes.size() - 1) {
      return Optional.empty();
    }
    Duration start = outcomes.get(firstFailure).sentAt();
    return Optional.of(outcomes.get(lastFailure + 1).sentAt().minus(start));
  }

  /**
   * Writes the spans and the outage as text.
   *
   * @param outcomes in the order they were sent
   * @return one line per span, and one line for the outage
   */
  public static String render(List<Outcome> outcomes) {
    StringBuilder text = new StringBuilder();
    for (Span span : spans(outcomes)) {
      text.append(
          String.format(
              Locale.ROOT,
              "%7.3f s to %7.3f s  %-22s x %4d  latency p50 %8.2f ms, max %8.2f ms%n",
              seconds(span.first().sentAt()),
              seconds(span.last().sentAt()),
              span.first().result(),
              span.outcomes().size(),
              millis(span.medianLatency()),
              millis(span.maxLatency())));
    }
    text.append(outageLine(outcomes)).append(System.lineSeparator());
    return text.toString();
  }

  private static String outageLine(List<Outcome> outcomes) {
    Optional<Duration> outage = outage(outcomes);
    if (outage.isPresent()) {
      return String.format(
          Locale.ROOT,
          "outage: %.3f s from the first failed request to the first success after the last",
          seconds(outage.get()));
    }
    // No ended outage has two causes, and the reader must be told which one it was.
    Optional<Outcome> firstFailure =
        outcomes.stream().filter(each -> !each.isSuccess()).findFirst();
    return firstFailure
        .map(failure ->
            String.format(
                Locale.ROOT,
                "outage: from %.3f s to the end of the probe, with no success after it",
                seconds(failure.sentAt())))
        .orElse("outage: none, every request succeeded");
  }

  private static double seconds(Duration duration) {
    return duration.toNanos() / NANOS_PER_SECOND;
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }

  /**
   * Consecutive outcomes with one result.
   *
   * @param outcomes at least one, all with the same result
   */
  public record Span(List<Outcome> outcomes) {

    /**
     * Copies and checks the outcomes.
     *
     * @throws IllegalArgumentException if the list is empty or mixes results
     */
    public Span {
      outcomes = List.copyOf(outcomes);
      if (outcomes.isEmpty()) {
        throw new IllegalArgumentException("a span needs at least one outcome");
      }
      String result = outcomes.getFirst().result();
      if (outcomes.stream().anyMatch(outcome -> !outcome.result().equals(result))) {
        throw new IllegalArgumentException("a span holds one result only");
      }
    }

    Outcome first() {
      return outcomes.getFirst();
    }

    Outcome last() {
      return outcomes.getLast();
    }

    Duration medianLatency() {
      List<Duration> sorted = outcomes.stream().map(Outcome::latency).sorted().toList();
      return sorted.get((sorted.size() - 1) / 2);
    }

    Duration maxLatency() {
      return outcomes.stream().map(Outcome::latency).max(Duration::compareTo).orElseThrow();
    }
  }
}
