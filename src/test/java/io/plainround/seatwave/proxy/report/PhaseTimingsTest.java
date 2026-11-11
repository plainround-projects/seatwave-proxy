package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PhaseTimingsTest {

  private static final Duration TEN = Duration.ofMillis(10);
  private static final Duration ZERO = Duration.ZERO;

  @Test
  void acceptsPhasesThatSumToTheTotal() {
    PhaseTimings timings = new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(50));

    assertThat(timings.of(Phase.TRANSFER)).isEqualTo(TEN);
  }

  @Test
  void refusesPhasesThatDoNotSumToTheTotal() {
    Result<PhaseTimings, String> refused =
        PhaseTimings.of(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52));

    assertThat(((Result.Error<PhaseTimings, String>) refused).error())
        .isEqualTo("the phases sum to 50000000 ns, but the total is 52000000 ns");
    assertThatThrownBy(() -> new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allowsAGapUpToTheTolerance() {
    Duration total = Duration.ofMillis(50).plus(PhaseTimings.SUM_TOLERANCE);

    assertThat(PhaseTimings.of(TEN, TEN, TEN, TEN, TEN, total)).isInstanceOf(Result.Success.class);
  }

  @Test
  void refusesANegativePhaseEvenWhenTheSumIsRight() {
    Duration minusTen = TEN.negated();

    // 10 - 10 + 10 + 10 + 0 = 20, so the total is right and only the negative phase is wrong.
    assertThat(PhaseTimings.of(TEN, minusTen, TEN, TEN, ZERO, TEN.plus(TEN)))
        .isInstanceOf(Result.Error.class);
  }

  @Test
  void namesTheLongestPhaseAndKeepsTheEarlierOneOnATie() {
    PhaseTimings tie = new PhaseTimings(ZERO, TEN, TEN, ZERO, ZERO, Duration.ofMillis(20));
    PhaseTimings allZero = new PhaseTimings(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);

    assertThat(tie.dominant()).isEqualTo(Phase.CONNECT);
    assertThat(allZero.dominant()).isEqualTo(Phase.DNS);
    assertThat(Phase.FIRST_BYTE.label()).isEqualTo("first byte");
  }
}
