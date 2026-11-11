package io.plainround.seatwave.proxy.slow;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything the slow-client run needs, read from a settings file with no defaults.
 *
 * @param target what every slow client downloads, over plain HTTP
 * @param clients how many slow clients start at once
 * @param bytesPerTick how many bytes a client reads each tick
 * @param tick the pause between two reads: with bytesPerTick it sets the client's speed
 * @param receiveBufferBytes the client's socket receive buffer, kept small like a phone's
 * @param fastProbe a cheap request sent straight to SeatWave throughout, to see its queue
 * @param metrics SeatWave's operations listener, read throughout
 * @param sample the pause between two readings of the probe and the metrics
 * @param timeout the most a client may take before the run gives up on it
 */
public record SlowSettings(
    URI target,
    int clients,
    int bytesPerTick,
    Duration tick,
    int receiveBufferBytes,
    URI fastProbe,
    URI metrics,
    Duration sample,
    Duration timeout) {

  // The lab network's addresses only (10.209.9.0/24 in compose.yaml), plain HTTP.
  private static final Pattern LAB_URI =
      Pattern.compile("http://10\\.209\\.9\\.[0-9]{1,3}:[0-9]{2,5}/[A-Za-z0-9/._?=&-]*");
  private static final int MAX_CLIENTS = 5_000;
  private static final int MAX_BYTES = 1_000_000;
  private static final int MAX_MILLIS = 600_000;
  // Linux rounds a receive buffer up to its own minimum; below 1,024 bytes the number means little.
  private static final int MIN_RECEIVE_BUFFER = 1_024;
  private static final double MILLIS_PER_SECOND = 1_000.0;

  /** Checks that nothing is missing. */
  public SlowSettings {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(tick, "tick");
    Objects.requireNonNull(fastProbe, "fastProbe");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(sample, "sample");
    Objects.requireNonNull(timeout, "timeout");
  }

  /**
   * Returns how fast one client reads.
   *
   * @return bytes per second
   */
  public long bytesPerSecond() {
    return Math.round(bytesPerTick * (MILLIS_PER_SECOND / tick.toMillis()));
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<SlowSettings, List<String>> from(SettingsReader reader) {
    String target = reader.textMatching("slow.targetUri", LAB_URI);
    int clients = reader.intBetween("slow.clients", 1, MAX_CLIENTS);
    int bytesPerTick = reader.intBetween("slow.bytesPerTick", 1, MAX_BYTES);
    Duration tick = reader.millisBetween("slow.tickMillis", 1, MAX_MILLIS);
    int buffer = reader.intBetween("slow.receiveBufferBytes", MIN_RECEIVE_BUFFER, MAX_BYTES);
    String probe = reader.textMatching("slow.fastProbeUri", LAB_URI);
    String metrics = reader.textMatching("slow.metricsUri", LAB_URI);
    Duration sample = reader.millisBetween("slow.sampleMillis", 1, MAX_MILLIS);
    Duration timeout = reader.millisBetween("slow.timeoutMillis", 1, MAX_MILLIS);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new SlowSettings(
            URI.create(target),
            clients,
            bytesPerTick,
            tick,
            buffer,
            URI.create(probe),
            URI.create(metrics),
            sample,
            timeout));
  }
}
