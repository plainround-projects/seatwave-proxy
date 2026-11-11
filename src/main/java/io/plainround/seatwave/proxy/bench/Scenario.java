package io.plainround.seatwave.proxy.bench;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One way of reaching SeatWave in a load run.
 *
 * @param name a short name, for example {@code direct-tls}
 * @param uri the resource, on 127.0.0.1 or a container name of the lab network
 * @param acceptsGzip whether requests say {@code Accept-Encoding: gzip}
 * @param mode whether each client reuses its connection or opens one per request
 */
public record Scenario(String name, URI uri, boolean acceptsGzip, ConnectionMode mode) {

  /** A scenario name: lower-case letters, digits and hyphens, at most 24 characters. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,23}");

  /**
   * Creates the scenario.
   *
   * @throws IllegalArgumentException if the name is invalid or the URI is not http or https
   */
  public Scenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(mode, "mode");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("invalid scenario name: " + name);
    }
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
      throw new IllegalArgumentException("a scenario URI must be http or https: " + uri);
    }
  }
}
