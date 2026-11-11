package io.plainround.seatwave.proxy.report;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One request as curl measured it: one line of the timing script's output, checked.
 *
 * @param target the target's short name, for example {@code example-com}
 * @param protocol the HTTP version curl used, for example {@code HTTP/2}
 * @param kind whether curl opened a new connection for this request
 * @param status the HTTP status code, always 2xx
 * @param sizeBytes the response body's size in bytes
 * @param phases the five phases and the total
 * @param serverTiming the response's {@code Server-Timing} header, or an empty string
 */
public record CurlSample(
    String target,
    String protocol,
    ConnectionKind kind,
    int status,
    long sizeBytes,
    PhaseTimings phases,
    String serverTiming) {

  /** A target name: lower-case letters, digits and hyphens, at most 40 characters. */
  public static final Pattern TARGET_NAME = Pattern.compile("[a-z0-9-]{1,40}");

  private static final int FIRST_SUCCESS_STATUS = 200;
  private static final int FIRST_NON_SUCCESS_STATUS = 300;

  /**
   * Creates the sample.
   *
   * @throws IllegalArgumentException if the name, the status or the size is invalid
   */
  public CurlSample {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(protocol, "protocol");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(phases, "phases");
    Objects.requireNonNull(serverTiming, "serverTiming");
    if (!TARGET_NAME.matcher(target).matches()) {
      throw new IllegalArgumentException("invalid target name: " + target);
    }
    if (status < FIRST_SUCCESS_STATUS || status >= FIRST_NON_SUCCESS_STATUS) {
      throw new IllegalArgumentException("only a 2xx response is a timing, but was " + status);
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("the size cannot be negative");
    }
  }
}
