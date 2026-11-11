package io.plainround.seatwave.proxy.bench;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Command-line entry point of the bench: runs every scenario of a settings file against the running
 * stack and prints one row per scenario. It reads each container's CPU time and TCP counters before
 * and after every run with {@code docker exec}. Exit status 2 means bad usage or an invalid
 * settings file; 1 means the stack could not be reached or read.
 */
public final class BenchCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_STACK_FAILED = 1;
  private static final double MILLIS_PER_SECOND = 1_000.0;

  private BenchCli() {}

  /**
   * Runs the bench.
   *
   * @param arguments the settings file, for example {@code config/bench.properties}
   * @throws InterruptedException if the thread is interrupted during a run
   */
  public static void main(String[] arguments) throws InterruptedException {
    System.exit(run(arguments, new ContainerCounters("docker"), System.out, System.err));
  }

  /**
   * Runs the bench and returns the exit status.
   *
   * @param arguments the command line
   * @param counters reads each container's counters
   * @param out where the table is printed
   * @param err where problems are printed
   * @return 0, 1 or 2, as the class documentation says
   * @throws InterruptedException if the thread is interrupted during a run
   */
  static int run(String[] arguments, ContainerCounters counters, PrintStream out, PrintStream err)
      throws InterruptedException {
    if (arguments.length != 1) {
      err.println("usage: bench <bench.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<BenchSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), BenchSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    return bench(loaded.get(), counters, out, err);
  }

  /**
   * Runs every scenario of checked settings and prints the table.
   *
   * @param settings the checked settings
   * @param counters reads each container's counters
   * @param out where the table is printed
   * @param err where problems are printed
   * @return 0, or 1 if the stack could not be reached or read
   * @throws InterruptedException if the thread is interrupted during a run
   */
  static int bench(
      BenchSettings settings, ContainerCounters counters, PrintStream out, PrintStream err)
      throws InterruptedException {
    try {
      LoadRun runner =
          new LoadRun(
              TlsFiles.clientContext(settings.authorityPem()),
              settings.connectTimeout(),
              settings.requestTimeout());
      out.printf(
          Locale.ROOT,
          "Java %s on %s %s; %d clients over HTTP/1.1; warm-up %.0f s, then %d runs of %.0f s each;"
              + " each row is the median run by rps%n%n",
          Runtime.version(),
          System.getProperty("os.name"),
          System.getProperty("os.arch"),
          settings.clients(),
          settings.warmup().toMillis() / MILLIS_PER_SECOND,
          settings.repeats(),
          settings.measure().toMillis() / MILLIS_PER_SECOND);
      out.println(BenchReport.HEADER);
      for (Scenario scenario : settings.scenarios()) {
        out.print(BenchReport.render(measure(scenario, settings, runner, counters)));
      }
      return 0;
    } catch (IOException stackFailed) {
      err.println("the bench stopped: " + stackFailed.getMessage());
      return EXIT_STACK_FAILED;
    }
  }

  private static List<BenchRow> measure(
      Scenario scenario, BenchSettings settings, LoadRun runner, ContainerCounters counters)
      throws IOException, InterruptedException {
    if (!settings.warmup().isZero()) {
      runner.run(scenario, settings.warmup(), settings.clients());
    }
    List<BenchRow> runs = new ArrayList<>();
    for (int repeat = 0; repeat < settings.repeats(); repeat++) {
      Counters proxyBefore = counters.read(settings.proxyContainer());
      Counters backendBefore = counters.read(settings.backendContainer());
      LoadResult result = runner.run(scenario, settings.measure(), settings.clients());
      Counters proxyAfter = counters.read(settings.proxyContainer());
      Counters backendAfter = counters.read(settings.backendContainer());
      runs.add(
          new BenchRow(
              scenario,
              result,
              growth(settings.proxyContainer(), proxyBefore, proxyAfter),
              growth(settings.backendContainer(), backendBefore, backendAfter)));
    }
    return runs;
  }

  private static Counters growth(String container, Counters before, Counters after)
      throws IOException {
    try {
      return after.since(before);
    } catch (IllegalArgumentException wentDown) {
      // A counter that went down means the container started again during the run, so the run
      // measured nothing; that is a failure of the stack, reported like one, not a stack trace.
      throw new IOException(
          "the counters of "
              + container
              + " went down during a run, so the container restarted; run the bench again",
          wentDown);
    }
  }
}
