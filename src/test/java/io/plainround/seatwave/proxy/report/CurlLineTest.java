package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CurlLineTest {

  static final String NEW_HTTPS =
      "seatwave-https dns=0.000080 connect=0.000247 tls=0.005957 ttfb=0.007095 total=0.008013"
          + " http=1.1 code=200 size=36894 connects=1 server=app;dur=0.26";
  static final String REUSED_HTTPS =
      "seatwave-https dns=0.000010 connect=0.000000 tls=0.000000 ttfb=0.000776 total=0.001610"
          + " http=1.1 code=200 size=36894 connects=0 server=app;dur=0.25";

  @Test
  void turnsCumulativeReadingsIntoFivePhases() {
    CurlSample sample = parsed(NEW_HTTPS);

    PhaseTimings phases = sample.phases();
    assertThat(phases.dns()).isEqualTo(Duration.ofNanos(80_000));
    assertThat(phases.connect()).isEqualTo(Duration.ofNanos(167_000));
    assertThat(phases.tls()).isEqualTo(Duration.ofNanos(5_710_000));
    assertThat(phases.firstByte()).isEqualTo(Duration.ofNanos(1_138_000));
    assertThat(phases.transfer()).isEqualTo(Duration.ofNanos(918_000));
    assertThat(phases.total()).isEqualTo(Duration.ofNanos(8_013_000));
    assertThat(phases.dominant()).isEqualTo(Phase.TLS);
    assertThat(sample.kind()).isEqualTo(ConnectionKind.NEW);
    assertThat(sample.protocol()).isEqualTo("HTTP/1.1");
    assertThat(sample.serverTiming()).isEqualTo("app;dur=0.26");
  }

  @Test
  void readsZeroHandshakeReadingsOfAReusedConnectionAsSkippedPhases() {
    CurlSample sample = parsed(REUSED_HTTPS);

    assertThat(sample.kind()).isEqualTo(ConnectionKind.REUSED);
    assertThat(sample.phases().connect()).isZero();
    assertThat(sample.phases().tls()).isZero();
    assertThat(sample.phases().firstByte()).isEqualTo(Duration.ofNanos(766_000));
  }

  @Test
  void keepsAServerTimingHeaderWithSpaces() {
    String line =
        "example-com dns=0.002 connect=0.020 tls=0.045 ttfb=0.071 total=0.072 http=2 code=200"
            + " size=528 connects=1 server=cdn-cache; desc=HIT, edge; dur=1";

    assertThat(parsed(line).serverTiming()).isEqualTo("cdn-cache; desc=HIT, edge; dur=1");
    assertThat(parsed(line).protocol()).isEqualTo("HTTP/2");
  }

  @Test
  void rejectsReadingsThatAreOutOfOrder() {
    // The first byte cannot come before the TLS handshake ended: a phase would be negative.
    String line = NEW_HTTPS.replace("ttfb=0.007095", "ttfb=0.001000");

    assertThat(problem(line)).isInstanceOf(CurlProblem.Rejected.class);
    assertThat(problem(line).describe()).contains("a phase is negative");
  }

  @Test
  void rejectsAFailedOrUnsuccessfulRequest() {
    assertThat(problem(NEW_HTTPS.replace("code=200", "code=404")).describe())
        .contains("status 404 is not 2xx");
    assertThat(problem(NEW_HTTPS.replace("http=1.1", "http=0")).describe())
        .contains("the request failed");
    assertThat(problem(NEW_HTTPS.replace("size=36894", "size=-1")).describe())
        .contains("the size is negative");
  }

  @Test
  void refusesMalformedLines() {
    assertThat(problem(NEW_HTTPS.replace(" server=app;dur=0.26", "")).describe())
        .contains("no server= field");
    assertThat(problem(NEW_HTTPS.replace("code=200", "code200")).describe())
        .contains("invalid or repeated field code200");
    assertThat(problem(NEW_HTTPS.replace("seatwave-https", "SeatWave")).describe())
        .contains("invalid target name");
    assertThat(problem(NEW_HTTPS.replace(" connects=1", "")).describe())
        .contains("no connects= field");
    assertThat(problem(NEW_HTTPS.replace("total=0.008013", "total=fast")).describe())
        .contains("a number is invalid");
    assertThat(problem(NEW_HTTPS.replace("dns=0.000080", "dns=0.000080 dns=1")).describe())
        .contains("invalid or repeated field dns=1");
  }

  @Test
  void cutsALongMalformedLineInTheReport() {
    String longLine = "x".repeat(200);

    assertThat(problem(longLine).describe()).hasSizeLessThan(140);
  }

  static CurlSample parsed(String line) {
    return switch (CurlLine.parse(line)) {
      case Result.Success<CurlSample, CurlProblem>(CurlSample sample) -> sample;
      case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) ->
          throw new AssertionError(problem.describe());
    };
  }

  private static CurlProblem problem(String line) {
    return switch (CurlLine.parse(line)) {
      case Result.Success<CurlSample, CurlProblem>(CurlSample sample) ->
          throw new AssertionError("parsed, but should not: " + sample);
      case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) -> problem;
    };
  }
}
