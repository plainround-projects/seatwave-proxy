package io.plainround.seatwave.proxy.slow;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Command-line entry point of the slow-client run, made to run inside the lab network (the {@code
 * tools} service of {@code compose.yaml}), so no port forwarder of Docker Desktop sits between the
 * slow clients and the server they test. Exit status 2 means bad usage or invalid settings; 1 means
 * SeatWave's metrics could not be read.
 */
public final class SlowClientsCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_RUN_FAILED = 1;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_KILOBYTE = 1_000.0;

  private SlowClientsCli() {}

  /**
   * Runs the slow clients and prints what they measured.
   *
   * @param arguments the settings file
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static void main(String[] arguments) throws InterruptedException {
    System.exit(run(arguments, System.out, System.err));
  }

  /**
   * Runs the slow clients and returns the exit status.
   *
   * @param arguments the command line
   * @param out where the results are printed
   * @param err where problems are printed
   * @return 0, 1 or 2, as the class documentation says
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) throws InterruptedException {
    if (arguments.length != 1) {
      err.println("usage: slowClients <slow.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<SlowSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), SlowSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    try {
      print(loaded.get(), SlowClients.run(loaded.get()), out);
      return 0;
    } catch (IOException failed) {
      err.println("the run stopped: " + failed.getMessage());
      return EXIT_RUN_FAILED;
    }
  }

  static void print(SlowSettings settings, SlowResult result, PrintStream out) {
    out.printf(
        Locale.ROOT,
        "target: GET %s; %d slow clients at once, each reading %d bytes every %d ms (%.0f KB/s),"
            + " receive buffer %d bytes%n",
        settings.target(),
        settings.clients(),
        settings.bytesPerTick(),
        settings.tick().toMillis(),
        settings.bytesPerSecond() / BYTES_PER_KILOBYTE,
        settings.receiveBufferBytes());
    out.printf(
        Locale.ROOT,
        "slow clients: %d complete, %d failed, %d bytes each with headers; download p50 %.2f s,"
            + " max %.2f s%n",
        result.completed(),
        result.failed(),
        result.bodyBytes(),
        seconds(result.downloadP50()),
        seconds(result.downloadMax()));
    out.printf(
        Locale.ROOT,
        "SeatWave workers: at most %d of %d busy at once (%d readings missed); %.2f worker-seconds"
            + " in all, %.4f s per slow client%n",
        result.workersBusyMax(),
        result.workers(),
        result.missedSamples(),
        result.workerSeconds(),
        result.workerSeconds() / Math.max(1, result.completed()));
    out.printf(
        Locale.ROOT,
        "fast probe straight to SeatWave during the run: %d answered, %d failed (30 s timeout);"
            + " answered p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
        result.probes(),
        result.probeFailures(),
        millis(result.probeP50()),
        millis(result.probeP99()),
        millis(result.probeMax()));
  }

  private static double seconds(Duration duration) {
    return duration.toNanos() / NANOS_PER_SECOND;
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
