package io.plainround.seatwave.proxy.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import io.plainround.seatwave.proxy.report.Runs;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything a bench run needs, read from a settings file with no defaults.
 *
 * @param authorityPem the TEST certificate authority the clients trust
 * @param connectTimeout the connect timeout of every request
 * @param requestTimeout the whole-request timeout of every request
 * @param warmup a run made first and thrown away, so class loading, the JIT compilers and the
 *     proxy's first connections do not land in a measured run
 * @param measure how long each measured run lasts
 * @param repeats measured runs per scenario, at least {@link Runs#MINIMUM_RUNS}
 * @param clients how many clients send at once
 * @param proxyContainer the proxy's container, whose CPU and connections are read
 * @param backendContainer SeatWave's container, likewise
 * @param scenarios the scenarios, in the order they run
 */
public record BenchSettings(
    Path authorityPem,
    Duration connectTimeout,
    Duration requestTimeout,
    Duration warmup,
    Duration measure,
    int repeats,
    int clients,
    String proxyContainer,
    String backendContainer,
    List<Scenario> scenarios) {

  // Only the lab's own addresses: this machine's loopback address, where the stack publishes its
  // ports, and nothing else, so a typo cannot send load to another host.
  private static final Pattern LAB_URI =
      Pattern.compile("https?://127\\.0\\.0\\.1:209[0-9]{2}/[A-Za-z0-9/._?=&-]*");
  private static final Pattern YES_NO = Pattern.compile("yes|no");
  private static final Pattern MODE = Pattern.compile("reuse|new");
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MAX_RUN_MILLIS = 600_000;
  private static final int MAX_REPEATS = 50;
  private static final int MAX_CLIENTS = 2_000;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if there are too few repeats, no clients or no scenario
   */
  public BenchSettings {
    Objects.requireNonNull(authorityPem, "authorityPem");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(warmup, "warmup");
    Objects.requireNonNull(measure, "measure");
    Objects.requireNonNull(proxyContainer, "proxyContainer");
    Objects.requireNonNull(backendContainer, "backendContainer");
    scenarios = List.copyOf(scenarios);
    if (repeats < Runs.MINIMUM_RUNS || clients < 1 || scenarios.isEmpty()) {
      throw new IllegalArgumentException("too few repeats, no clients or no scenario");
    }
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<BenchSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String authority = reader.filePath("tls.authorityPem");
    Duration connect = reader.millisBetween("client.connectTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration request = reader.millisBetween("client.requestTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration warmup = reader.millisBetween("run.warmupMillis", 0, MAX_RUN_MILLIS);
    Duration measure = reader.millisBetween("run.measureMillis", 1, MAX_RUN_MILLIS);
    int repeats = reader.intBetween("run.repeats", Runs.MINIMUM_RUNS, MAX_REPEATS);
    int clients = reader.intBetween("run.clients", 1, MAX_CLIENTS);
    String proxy = reader.textMatching("counters.proxyContainer", ContainerCounters.CONTAINER_NAME);
    String backend =
        reader.textMatching("counters.backendContainer", ContainerCounters.CONTAINER_NAME);
    List<Scenario> scenarios = new ArrayList<>();
    for (String name : reader.listMatching("scenario.names", Scenario.NAME)) {
      String prefix = "scenario." + name + ".";
      String uri = reader.textMatching(prefix + "uri", LAB_URI);
      String gzip = reader.textMatching(prefix + "gzip", YES_NO);
      String mode = reader.textMatching(prefix + "connection", MODE);
      if (!uri.isEmpty() && !gzip.isEmpty() && !mode.isEmpty()) {
        scenarios.add(
            new Scenario(
                name,
                URI.create(uri),
                gzip.equals("yes"),
                mode.equals("reuse") ? ConnectionMode.REUSE : ConnectionMode.NEW));
      }
    }
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new BenchSettings(
            Path.of(authority),
            connect,
            request,
            warmup,
            measure,
            repeats,
            clients,
            proxy,
            backend,
            scenarios));
  }
}
