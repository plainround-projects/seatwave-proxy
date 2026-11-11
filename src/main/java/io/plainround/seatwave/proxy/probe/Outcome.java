package io.plainround.seatwave.proxy.probe;

import java.time.Duration;
import java.util.Objects;

/**
 * One probe request: when it was sent, how it ended and how long it took.
 *
 * @param sentAt the time since the probe started
 * @param result the status code, such as {@code 200} or {@code 502}, or the kind of failure, such
 *     as {@code connection refused}
 * @param latency how long the request took
 */
public record Outcome(Duration sentAt, String result, Duration latency) {

  /** Checks that nothing is missing or negative. */
  public Outcome {
    Objects.requireNonNull(sentAt, "sentAt");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(latency, "latency");
    if (sentAt.isNegative() || latency.isNegative() || result.isBlank()) {
      throw new IllegalArgumentException("an outcome needs a time, a result and a latency");
    }
  }

  /**
   * Says whether the client got what it asked for.
   *
   * @return true for 200
   */
  public boolean isSuccess() {
    return result.equals("200");
  }
}
