package io.plainround.seatwave.proxy.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineTest {

  private static final List<Outcome> OUTAGE =
      List.of(
          outcome(0, "200", 3),
          outcome(100, "200", 4),
          outcome(200, "connection refused", 1),
          outcome(300, "connection refused", 2),
          outcome(400, "200", 9));

  @Test
  void groupsEqualResultsIntoSpansInOrder() {
    List<Timeline.Span> spans = Timeline.spans(OUTAGE);

    assertThat(spans).hasSize(3);
    assertThat(spans.get(1).outcomes()).hasSize(2).allMatch(each -> !each.isSuccess());
  }

  @Test
  void measuresTheOutageFromTheFirstFailureToTheFirstSuccessAfterTheLast() {
    assertThat(Timeline.outage(OUTAGE)).contains(Duration.ofMillis(200));
    assertThat(Timeline.outage(List.of(outcome(0, "200", 1)))).isEmpty();
    assertThat(Timeline.outage(List.of(outcome(0, "200", 1), outcome(100, "502", 1)))).isEmpty();
  }

  @Test
  void writesOneLinePerSpanAndTheOutage() {
    String text = Timeline.render(OUTAGE);

    assertThat(text)
        .contains(
            "  0.000 s to   0.100 s  200                    x    2  latency p50     3.00 ms, max     4.00 ms")
        .contains("connection refused     x    2")
        .endsWith(
            "outage: 0.200 s from the first failed request to the first success after the last"
                + System.lineSeparator());
  }

  @Test
  void tellsAProbeWithoutFailuresFromOneThatEndedInAnOutage() {
    String clean = Timeline.render(List.of(outcome(0, "200", 1), outcome(100, "200", 1)));
    String stillDown =
        Timeline.render(
            List.of(outcome(0, "connection refused", 1), outcome(100, "connection refused", 1)));

    assertThat(clean).endsWith("outage: none, every request succeeded" + System.lineSeparator());
    assertThat(stillDown)
        .endsWith(
            "outage: from 0.000 s to the end of the probe, with no success after it"
                + System.lineSeparator());
  }

  @Test
  void refusesAnEmptyOrMixedSpanAndAnOutcomeWithoutAResult() {
    assertThatThrownBy(() -> new Timeline.Span(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Timeline.Span(OUTAGE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> outcome(0, " ", 1)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Outcome outcome(long sentAtMillis, String result, long latencyMillis) {
    return new Outcome(Duration.ofMillis(sentAtMillis), result, Duration.ofMillis(latencyMillis));
  }
}
