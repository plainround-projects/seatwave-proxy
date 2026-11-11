package io.plainround.seatwave.proxy.report;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses one line that {@code scripts/curl-timings.sh} writes with {@code curl -w}.
 *
 * <p>The line holds curl's cumulative clock readings, each in seconds from the start of the
 * transfer: {@code dns=} (time_namelookup), {@code connect=} (time_connect), {@code tls=}
 * (time_appconnect), {@code ttfb=} (time_starttransfer) and {@code total=} (time_total). A phase is
 * the difference between two readings, and curl writes 0 for a step that did not happen, such as
 * the TLS handshake of plain HTTP or both handshakes of a reused connection. Thread-safe: it holds
 * no state.
 */
public final class CurlLine {

  private static final List<String> REQUIRED_KEYS =
      List.of("dns", "connect", "tls", "ttfb", "total", "http", "code", "size", "connects");
  // The Server-Timing header can hold spaces, so it is the last field and takes the rest.
  private static final String SERVER_TIMING_FIELD = " server=";
  private static final int SHOWN_LINE_CHARACTERS = 80;
  private static final int NANOS_DIGITS = 9;
  private static final int FIRST_SUCCESS_STATUS = 200;
  private static final int LAST_SUCCESS_STATUS = 299;

  private CurlLine() {}

  /**
   * Parses one line.
   *
   * @param line for example {@code example-com dns=0.002 connect=0.020 tls=0.045 ttfb=0.071
   *     total=0.072 http=2 code=200 size=528 connects=1 server=}
   * @return the checked sample, or why the line is malformed or cannot be a timing
   */
  public static Result<CurlSample, CurlProblem> parse(String line) {
    Objects.requireNonNull(line, "line");
    int serverAt = line.indexOf(SERVER_TIMING_FIELD);
    if (serverAt < 0) {
      return malformed(line, "no server= field");
    }
    String serverTiming = line.substring(serverAt + SERVER_TIMING_FIELD.length()).strip();
    String[] tokens = line.substring(0, serverAt).strip().split(" +");
    String target = tokens[0];
    if (!CurlSample.TARGET_NAME.matcher(target).matches()) {
      return malformed(line, "invalid target name");
    }
    Map<String, String> fields = new HashMap<>();
    for (int tokenIndex = 1; tokenIndex < tokens.length; tokenIndex++) {
      String[] pair = tokens[tokenIndex].split("=", 2);
      if (pair.length != 2 || fields.put(pair[0], pair[1]) != null) {
        return malformed(line, "invalid or repeated field " + tokens[tokenIndex]);
      }
    }
    for (String key : REQUIRED_KEYS) {
      if (!fields.containsKey(key)) {
        return malformed(line, "no " + key + "= field");
      }
    }
    try {
      return sample(target, fields, serverTiming);
    } catch (NumberFormatException | ArithmeticException notANumber) {
      // A number that does not parse is bad input, so it is a returned problem, not a crash.
      return malformed(line, "a number is invalid");
    }
  }

  private static Result<CurlSample, CurlProblem> sample(
      String target, Map<String, String> fields, String serverTiming) {
    String protocol =
        switch (fields.get("http")) {
          case "1.1" -> "HTTP/1.1";
          case "2" -> "HTTP/2";
          case "3" -> "HTTP/3";
          default -> "";
        };
    if (protocol.isEmpty()) {
      return Result.error(new CurlProblem.Rejected(target, "no HTTP version: the request failed"));
    }
    int status = Integer.parseInt(fields.get("code"));
    if (status < FIRST_SUCCESS_STATUS || status > LAST_SUCCESS_STATUS) {
      return Result.error(new CurlProblem.Rejected(target, "status " + status + " is not 2xx"));
    }
    long sizeBytes = Long.parseLong(fields.get("size"));
    if (sizeBytes < 0) {
      return Result.error(new CurlProblem.Rejected(target, "the size is negative"));
    }
    Duration dnsEnd = seconds(fields.get("dns"));
    Duration connectReading = seconds(fields.get("connect"));
    Duration tlsReading = seconds(fields.get("tls"));
    // A reading of 0 means the step did not happen, so its phase ends where the last one ended.
    Duration connectEnd = connectReading.isZero() ? dnsEnd : connectReading;
    Duration tlsEnd = tlsReading.isZero() ? connectEnd : tlsReading;
    Duration firstByteEnd = seconds(fields.get("ttfb"));
    Duration total = seconds(fields.get("total"));
    Result<PhaseTimings, String> phases =
        PhaseTimings.of(
            dnsEnd,
            connectEnd.minus(dnsEnd),
            tlsEnd.minus(connectEnd),
            firstByteEnd.minus(tlsEnd),
            total.minus(firstByteEnd),
            total);
    ConnectionKind kind =
        Integer.parseInt(fields.get("connects")) == 0 ? ConnectionKind.REUSED : ConnectionKind.NEW;
    return switch (phases) {
      case Result.Success<PhaseTimings, String>(PhaseTimings checked) ->
          Result.success(
              new CurlSample(target, protocol, kind, status, sizeBytes, checked, serverTiming));
      case Result.Error<PhaseTimings, String>(String reason) ->
          Result.error(new CurlProblem.Rejected(target, reason));
    };
  }

  private static Duration seconds(String text) {
    // BigDecimal keeps curl's microseconds exact; a double could round 0.000001 away.
    long nanos = new BigDecimal(text).movePointRight(NANOS_DIGITS).longValueExact();
    return Duration.ofNanos(nanos);
  }

  private static Result<CurlSample, CurlProblem> malformed(String line, String reason) {
    String shown =
        line.length() > SHOWN_LINE_CHARACTERS ? line.substring(0, SHOWN_LINE_CHARACTERS) : line;
    return Result.error(new CurlProblem.Malformed(shown, reason));
  }
}
