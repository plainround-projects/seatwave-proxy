package io.plainround.seatwave.proxy.logs;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One line of the proxy's access log, in the {@code seatwave} format of {@code nginx/nginx.conf}:
 * {@code key=value} pairs separated by spaces, with the request's URI last.
 *
 * @param port the proxy's listening port, which names the lab variant
 * @param status the status the proxy sent the client
 * @param upstreamStatus the status SeatWave sent, or 0 when SeatWave was not asked (a cache hit)
 * @param requestTime from the first byte of the request to the last byte of the response
 * @param upstreamTime SeatWave's part of it, or {@link Duration#ZERO} when SeatWave was not asked
 * @param cacheStatus NGINX's {@code $upstream_cache_status}: {@code MISS}, {@code BYPASS}, {@code
 *     EXPIRED}, {@code STALE}, {@code UPDATING}, {@code REVALIDATED} or {@code HIT}, or an empty
 *     string when the location has no cache
 * @param askedUpstream whether the proxy sent the request on to SeatWave
 * @param uri the request's path
 */
public record AccessLine(
    int port,
    int status,
    int upstreamStatus,
    Duration requestTime,
    Duration upstreamTime,
    String cacheStatus,
    boolean askedUpstream,
    String uri) {

  private static final List<String> REQUIRED =
      List.of("port", "status", "us", "rt", "urt", "cache");
  private static final String URI_FIELD = " uri=";
  private static final String NONE = "-";
  private static final int NANOS_DIGITS = 9;

  /** Checks that nothing is missing. */
  public AccessLine {
    Objects.requireNonNull(requestTime, "requestTime");
    Objects.requireNonNull(upstreamTime, "upstreamTime");
    Objects.requireNonNull(cacheStatus, "cacheStatus");
    Objects.requireNonNull(uri, "uri");
  }

  /**
   * Returns the time the proxy itself added: the whole request less SeatWave's part.
   *
   * @return the difference, never below zero
   */
  public Duration proxyAdded() {
    Duration added = requestTime.minus(upstreamTime);
    return added.isNegative() ? Duration.ZERO : added;
  }

  /**
   * Parses one line.
   *
   * @param line for example {@code port=8443 status=200 us=200 rt=0.002 urt=0.001 cache=MISS
   *     uri=/static/seat-map.js}
   * @return the line, or why it cannot be read
   */
  public static Result<AccessLine, String> parse(String line) {
    Objects.requireNonNull(line, "line");
    int uriAt = line.indexOf(URI_FIELD);
    if (uriAt < 0) {
      return Result.error("no uri= field");
    }
    Map<String, String> fields = new HashMap<>();
    // NGINX lists one value per upstream attempt as "502, 200", and per internal redirect with
    // " : "; both are joined to "502,200" first, so every field is one token.
    String joined = line.substring(0, uriAt).replace(", ", ",").replace(" : ", ",");
    for (String token : joined.strip().split(" +")) {
      String[] pair = token.split("=", 2);
      if (pair.length == 2) {
        fields.put(pair[0], pair[1]);
      }
    }
    for (String key : REQUIRED) {
      if (!fields.containsKey(key)) {
        return Result.error("no " + key + "= field");
      }
    }
    try {
      boolean asked = !fields.get("urt").equals(NONE);
      return Result.success(
          new AccessLine(
              Integer.parseInt(fields.get("port")),
              Integer.parseInt(fields.get("status")),
              asked ? lastNumber(fields.get("us")) : 0,
              seconds(fields.get("rt")),
              asked ? summedSeconds(fields.get("urt")) : Duration.ZERO,
              fields.get("cache").equals(NONE) ? "" : fields.get("cache"),
              asked,
              line.substring(uriAt + URI_FIELD.length()).strip()));
    } catch (NumberFormatException | ArithmeticException notANumber) {
      return Result.error("a number is invalid");
    }
  }

  private static int lastNumber(String text) {
    // After a retry NGINX lists one status per attempt, "502, 200": the last one is what was sent.
    String[] parts = text.split(",");
    return parts[parts.length - 1].equals(NONE) ? 0 : Integer.parseInt(parts[parts.length - 1]);
  }

  private static Duration summedSeconds(String text) {
    Duration sum = Duration.ZERO;
    for (String part : text.split(",")) {
      if (!part.equals(NONE)) {
        sum = sum.plus(seconds(part));
      }
    }
    return sum;
  }

  private static Duration seconds(String text) {
    // NGINX writes these times with millisecond resolution, "0.001"; BigDecimal keeps them exact.
    return Duration.ofNanos(new BigDecimal(text).movePointRight(NANOS_DIGITS).longValueExact());
  }
}
