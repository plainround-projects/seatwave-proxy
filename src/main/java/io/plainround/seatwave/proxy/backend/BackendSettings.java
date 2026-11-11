package io.plainround.seatwave.proxy.backend;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import io.plainround.seatwave.proxy.forwarded.TrustedProxies;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything the SeatWave backend needs, read from a settings file with no defaults.
 *
 * @param bindAddress the address both listeners bind to: 0.0.0.0 inside a container, so the proxy
 *     can reach it on the container network, or 127.0.0.1 on a reader's own machine
 * @param plainPort the plain HTTP listener, the one the proxy talks to
 * @param tlsPort the HTTPS listener: SIMPLE, SeatWave answering browsers with its own TLS
 * @param adminPort the operations listener for {@code /metrics} and {@code /health}, on its own
 *     thread, so the numbers can still be read while every worker is busy
 * @param tlsKeystore the PKCS12 file with the server's TEST key and certificate chain
 * @param tlsPasswordFile the file that holds the keystore's password
 * @param backlog connections the kernel may hold for each listener before SeatWave accepts them
 * @param workerThreads the platform threads that run requests, Module 4's 64
 * @param queueCapacity requests that may wait for a worker; one more is refused
 * @param trustedProxies the peers whose forwarding headers are believed
 * @param timing how long the slow routes take
 */
public record BackendSettings(
    InetAddress bindAddress,
    int plainPort,
    int tlsPort,
    int adminPort,
    Path tlsKeystore,
    Path tlsPasswordFile,
    int backlog,
    int workerThreads,
    int queueCapacity,
    TrustedProxies trustedProxies,
    Timing timing) {

  // An IP address literal; a host name would need DNS before the server could start.
  private static final Pattern ADDRESS = Pattern.compile("[0-9.]{7,15}|[0-9a-fA-F:]{2,39}");
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_WORKERS = 1_024;
  // Linux caps a listen backlog at net.core.somaxconn, 4,096 in the lab's containers.
  private static final int MAX_BACKLOG = 65_535;
  private static final int MAX_QUEUE = 100_000;
  private static final int MAX_WAIT_MILLIS = 600_000;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if the two listeners share a port
   */
  public BackendSettings {
    Objects.requireNonNull(bindAddress, "bindAddress");
    Objects.requireNonNull(tlsKeystore, "tlsKeystore");
    Objects.requireNonNull(tlsPasswordFile, "tlsPasswordFile");
    Objects.requireNonNull(trustedProxies, "trustedProxies");
    Objects.requireNonNull(timing, "timing");
    if (sharePort(plainPort, tlsPort, adminPort)) {
      throw new IllegalArgumentException("the three listeners need three different ports");
    }
  }

  /**
   * Reads the settings, reporting every missing or invalid key and every broken rule between keys
   * together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<BackendSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String bind = reader.textMatching("http.bindAddress", ADDRESS);
    int plainPort = reader.intBetween("http.plainPort", 0, HIGHEST_PORT);
    int tlsPort = reader.intBetween("http.tlsPort", 0, HIGHEST_PORT);
    int adminPort = reader.intBetween("http.adminPort", 0, HIGHEST_PORT);
    String keystore = reader.filePath("tls.keystore");
    String passwordFile = reader.filePath("tls.passwordFile");
    int backlog = reader.intBetween("http.backlog", 1, MAX_BACKLOG);
    int workers = reader.intBetween("http.workerThreads", 1, MAX_WORKERS);
    int queue = reader.intBetween("http.queueCapacity", 1, MAX_QUEUE);
    List<String> trusted = reader.listMatching("forwarded.trustedProxies", ADDRESS);
    Duration longPoll = reader.millisBetween("feed.longPollHoldMillis", 1, MAX_WAIT_MILLIS);
    int problemsBeforeStream = reader.problems().size();
    Duration heartbeat = reader.millisBetween("feed.heartbeatMillis", 1, MAX_WAIT_MILLIS);
    Duration lifetime = reader.millisBetween("feed.streamLifetimeMillis", 1, MAX_WAIT_MILLIS);
    // The rule between the two keys is checked only when both were read, so a missing key gives
    // one problem line, not a second one about the stand-in values.
    boolean streamKeysValid = reader.problems().size() == problemsBeforeStream;
    if (sharePort(plainPort, tlsPort, adminPort)) {
      reader.addProblem(
          "http.plainPort, http.tlsPort and http.adminPort must differ (0 means any free port)");
    }
    if (streamKeysValid && lifetime.compareTo(heartbeat) <= 0) {
      reader.addProblem(
          "feed.streamLifetimeMillis must be longer than feed.heartbeatMillis, or a stream ends"
              + " before its first heartbeat");
    }
    Optional<InetAddress> address = bind.isEmpty() ? Optional.empty() : literal(reader, bind);
    Optional<TrustedProxies> proxies = Optional.empty();
    if (!trusted.isEmpty()) {
      switch (TrustedProxies.parse(trusted)) {
        case Result.Success<TrustedProxies, String>(TrustedProxies parsed) ->
            proxies = Optional.of(parsed);
        case Result.Error<TrustedProxies, String>(String reason) ->
            reader.addProblem("forwarded.trustedProxies: " + reason);
      }
    }
    if (!reader.problems().isEmpty() || address.isEmpty() || proxies.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new BackendSettings(
            address.get(),
            plainPort,
            tlsPort,
            adminPort,
            Path.of(keystore),
            Path.of(passwordFile),
            backlog,
            workers,
            queue,
            proxies.get(),
            new Timing(longPoll, heartbeat, lifetime)));
  }

  private static boolean sharePort(int plainPort, int tlsPort, int adminPort) {
    // 0 asks the system for any free port, so two zeros never collide.
    return (plainPort != 0 && (plainPort == tlsPort || plainPort == adminPort))
        || (tlsPort != 0 && tlsPort == adminPort);
  }

  private static Optional<InetAddress> literal(SettingsReader reader, String text) {
    try {
      return Optional.of(InetAddress.ofLiteral(text));
    } catch (IllegalArgumentException notAnAddress) {
      reader.addProblem("http.bindAddress must be an IP address, but was " + text);
      return Optional.empty();
    }
  }

  /**
   * How long the slow routes take.
   *
   * @param longPollHold how long a long poll waits when nothing changes, Module 8's 25 seconds
   * @param heartbeat how long an event stream stays silent before a heartbeat, Module 8's 15 s
   * @param streamLifetime when the lab's event stream ends by itself, so no lab leaves it open
   */
  public record Timing(Duration longPollHold, Duration heartbeat, Duration streamLifetime) {

    /** Checks that no duration is negative. */
    public Timing {
      for (Duration duration : List.of(longPollHold, heartbeat, streamLifetime)) {
        if (Objects.requireNonNull(duration, "duration").isNegative()) {
          throw new IllegalArgumentException("a duration cannot be negative: " + duration);
        }
      }
    }
  }
}
