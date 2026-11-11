package io.plainround.seatwave.proxy.backend;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line entry point of the SeatWave backend: reads the settings, starts the listeners and
 * runs until the process is told to stop (Ctrl-C, or {@code docker stop}'s SIGTERM), then closes
 * them. Exit status 2 means bad usage, an invalid settings file or a missing TLS file.
 */
public final class BackendCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;

  private BackendCli() {}

  /**
   * Runs the backend.
   *
   * @param arguments the settings file, for example {@code config/backend-container.properties}
   * @throws InterruptedException if the main thread is interrupted while it waits
   */
  public static void main(String[] arguments) throws InterruptedException {
    Optional<BackendServer> server = start(arguments, System.out, System.err);
    if (server.isEmpty()) {
      System.exit(EXIT_BAD_USAGE);
      return;
    }
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              server.get().close();
              System.out.println("SeatWave stopped");
              stopped.countDown();
            }));
    stopped.await();
  }

  /**
   * Checks the arguments and the settings and starts the listeners.
   *
   * @param arguments the command line
   * @param out where the listening addresses are printed
   * @param err where problems are printed
   * @return the running server, or empty after printing why it cannot start
   */
  static Optional<BackendServer> start(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 1) {
      err.println("usage: serve <backend.properties>");
      return Optional.empty();
    }
    Optional<BackendSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), BackendSettings::from, err);
    if (loaded.isEmpty()) {
      return Optional.empty();
    }
    BackendSettings settings = loaded.get();
    try {
      BackendServer server =
          BackendServer.start(
              settings,
              TlsFiles.serverContext(settings.tlsKeystore(), settings.tlsPasswordFile()),
              Clock.systemUTC());
      String address = settings.bindAddress().getHostAddress();
      out.println("SeatWave plain HTTP on " + address + ":" + server.plainPort());
      out.println("SeatWave HTTPS on " + address + ":" + server.tlsPort() + " (TLSv1.3)");
      out.println("SeatWave operations (metrics, health) on " + address + ":" + server.adminPort());
      out.println(
          settings.workerThreads()
              + " worker threads, trusted proxies "
              + settings.trustedProxies().addresses().stream()
                  .map(each -> each.getHostAddress())
                  .sorted()
                  .toList());
      return Optional.of(server);
    } catch (IOException cannotStart) {
      err.println("SeatWave cannot start: " + cannotStart.getMessage());
      return Optional.empty();
    }
  }
}
