package io.plainround.seatwave.proxy.backend;

import io.plainround.seatwave.proxy.forwarded.ClientView;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One request, copied out of the HTTP server's own types once, so no route sees a transport type.
 *
 * @param method the method, for example {@code GET}
 * @param path the raw path, for example {@code /v1/events/1/seats}
 * @param rawQuery the raw query without the question mark, or an empty string
 * @param headers every header, names in lower case
 * @param body the body, at most {@link SeatWaveRoutes#MAX_BODY_BYTES} bytes
 * @param bodyTooLarge true if the client sent more than the limit
 * @param client what SeatWave believes about the client, decided before any route runs
 * @param correlationId the identifier of this request in log lines and problem bodies
 */
public record Request(
    String method,
    String path,
    String rawQuery,
    Map<String, List<String>> headers,
    byte[] body,
    boolean bodyTooLarge,
    ClientView client,
    String correlationId) {

  /** Copies the headers and the body. */
  public Request {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rawQuery, "rawQuery");
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(correlationId, "correlationId");
    headers = Map.copyOf(headers);
    body = Objects.requireNonNull(body, "body").clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  /**
   * Returns the only value of a header.
   *
   * @param name the header name, in any case
   * @return the value, or empty if the header is absent or sent more than once
   */
  public Optional<String> header(String name) {
    List<String> values = headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
  }
}
