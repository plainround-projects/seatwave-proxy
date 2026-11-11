package io.plainround.seatwave.proxy.probe;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything the probe needs, read from a settings file with no defaults.
 *
 * @param uri the resource to ask for, on 127.0.0.1 in the lab's port range
 * @param authorityPem the TEST certificate authority the client trusts
 * @param interval the time between two requests
 * @param duration how long the probe runs
 * @param connectTimeout the connect timeout of every request
 * @param requestTimeout the whole-request timeout of every request
 */
public record ProbeSettings(
    URI uri,
    Path authorityPem,
    Duration interval,
    Duration duration,
    Duration connectTimeout,
    Duration requestTimeout) {

  private static final Pattern LAB_URI =
      Pattern.compile("https?://127\\.0\\.0\\.1:209[0-9]{2}/[A-Za-z0-9/._?=&-]*");
  private static final int MAX_MILLIS = 600_000;

  /** Checks that nothing is missing. */
  public ProbeSettings {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(authorityPem, "authorityPem");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<ProbeSettings, List<String>> from(SettingsReader reader) {
    String uri = reader.textMatching("probe.uri", LAB_URI);
    String authority = reader.filePath("tls.authorityPem");
    Duration interval = reader.millisBetween("probe.intervalMillis", 1, MAX_MILLIS);
    Duration duration = reader.millisBetween("probe.durationMillis", 1, MAX_MILLIS);
    Duration connect = reader.millisBetween("client.connectTimeoutMillis", 1, MAX_MILLIS);
    Duration request = reader.millisBetween("client.requestTimeoutMillis", 1, MAX_MILLIS);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new ProbeSettings(
            URI.create(uri), Path.of(authority), interval, duration, connect, request));
  }
}
