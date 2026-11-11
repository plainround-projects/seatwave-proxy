package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimingReportTest {

  @Test
  void reportsTheMedianRunWhosePhasesStillSumToItsTotal() {
    List<CurlSample> runs =
        List.of(run("0.008013"), run("0.040919"), run("0.006390"), run("0.007500"));

    TargetTiming row = new TargetTiming("seatwave-https", ConnectionKind.NEW, runs);

    // Four runs: the lower of the two middle ones, a run that was really measured.
    assertThat(row.medianRun().phases().total()).isEqualTo(Duration.ofNanos(7_500_000));
    assertThat(row.fastest()).isEqualTo(Duration.ofNanos(6_390_000));
    assertThat(row.slowest()).isEqualTo(Duration.ofNanos(40_919_000));
  }

  @Test
  void refusesARowWithTooFewRunsOrARunOfAnotherTarget() {
    assertThatThrownBy(
            () -> new TargetTiming("seatwave-https", ConnectionKind.NEW, List.of(run("0.008013"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a timing needs at least 3 runs, but had 1");
    List<CurlSample> mixed = List.of(run("0.008013"), run("0.008013"), run("0.008013"));
    assertThatThrownBy(() -> new TargetTiming("seatwave-https", ConnectionKind.REUSED, mixed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void printsEveryRowWithItsSentenceAndListsRefusedGroupsAndLines() {
    List<CurlSample> samples =
        List.of(
            run("0.008013"),
            run("0.006390"),
            run("0.007500"),
            CurlLineTest.parsed(CurlLineTest.REUSED_HTTPS));
    List<CurlProblem> problems = List.of(new CurlProblem.Rejected("example-com", "status 404"));

    String table = TimingReport.renderPhases(samples, problems);

    assertThat(table)
        .contains("seatwave-https    new        HTTP/1.1     3")
        .contains("seatwave-https, new connection: tls dominates the median run")
        .contains("refused: too few runs for seatwave-https over a reused connection: 1")
        .contains("refused: rejected run of example-com: status 404");
  }

  @Test
  void refusesASampleThatIsNotA2xxTiming() {
    PhaseTimings phases = CurlLineTest.parsed(CurlLineTest.NEW_HTTPS).phases();

    assertThatThrownBy(
            () -> new CurlSample("x", "HTTP/1.1", ConnectionKind.NEW, 500, 1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CurlSample("Bad Name", "HTTP/1.1", ConnectionKind.NEW, 200, 1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CurlSample("x", "HTTP/1.1", ConnectionKind.NEW, 200, -1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CurlSample run(String totalSeconds) {
    return CurlLineTest.parsed(
        CurlLineTest.NEW_HTTPS
            .replace("total=0.008013", "total=" + totalSeconds)
            .replace("ttfb=0.007095", "ttfb=0.006000"));
  }
}
