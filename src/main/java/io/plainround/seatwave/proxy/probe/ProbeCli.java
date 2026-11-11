package io.plainround.seatwave.proxy.probe;

import io.plainround.seatwave.proxy.config.SettingsFiles;
import io.plainround.seatwave.proxy.tls.TlsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Command-line entry point of the probe: one client sends a request at a fixed interval and records
 * how each one ended, while a script stops and starts a container. The output is the timeline of
 * what a fan saw. Exit status 2 means bad usage or an invalid settings file.
 */
public final class ProbeCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;

  private ProbeCli() {}

  /**
   * Runs the probe.
   *
   * @param arguments the settings file, for example {@code config/probe.properties}
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static void main(String[] arguments) throws InterruptedException {
    System.exit(run(arguments, System.out, System.err));
  }

  /**
   * Runs the probe and returns the exit status.
   *
   * @param arguments the command line
   * @param out where the timeline is printed
   * @param err where problems are printed
   * @return 0, or 2 for bad usage
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) throws InterruptedException {
    if (arguments.length != 1) {
      err.println("usage: probe <probe.properties>");
      return EXIT_BAD_USAGE;
    }
    Optional<ProbeSettings> loaded =
        SettingsFiles.load(Path.of(arguments[0]), ProbeSettings::from, err);
    if (loaded.isEmpty()) {
      return EXIT_BAD_USAGE;
    }
    ProbeSettings settings = loaded.get();
    HttpClient client;
    try {
      client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .sslContext(TlsFiles.clientContext(settings.authorityPem()))
              .connectTimeout(settings.connectTimeout())
              .build();
    } catch (IOException unreadable) {
      err.println(unreadable.getMessage());
      return EXIT_BAD_USAGE;
    }
    List<Outcome> outcomes;
    try (client) {
      outcomes = probe(client, settings);
    }
    out.printf(
        Locale.ROOT,
        "probe: GET %s every %d ms for %d s, one client, HTTP/1.1 keep-alive%n",
        settings.uri(),
        settings.interval().toMillis(),
        settings.duration().toSeconds());
    out.print(Timeline.render(outcomes));
    return 0;
  }

  /**
   * Sends one request per interval until the duration is over.
   *
   * @param client the one client, which keeps its connection open between requests
   * @param settings the target, the interval and the timeouts
   * @return every outcome, in the order sent
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  static List<Outcome> probe(HttpClient client, ProbeSettings settings)
      throws InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(settings.uri()).timeout(settings.requestTimeout()).GET().build();
    List<Outcome> outcomes = new ArrayList<>();
    long startNanos = System.nanoTime();
    long endNanos = startNanos + settings.duration().toNanos();
    for (long nextNanos = startNanos;
        nextNanos < endNanos;
        nextNanos += settings.interval().toNanos()) {
      long waitNanos = nextNanos - System.nanoTime();
      if (waitNanos > 0) {
        Thread.sleep(Duration.ofNanos(waitNanos));
      }
      long sentNanos = System.nanoTime();
      String result = send(client, request);
      outcomes.add(
          new Outcome(
              Duration.ofNanos(sentNanos - startNanos),
              result,
              Duration.ofNanos(System.nanoTime() - sentNanos)));
    }
    return outcomes;
  }

  private static String send(HttpClient client, HttpRequest request) throws InterruptedException {
    try {
      return Integer.toString(
          client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    } catch (HttpConnectTimeoutException slowConnect) {
      return "connect timeout";
    } catch (HttpTimeoutException slowAnswer) {
      return "request timeout";
    } catch (ConnectException refused) {
      return "connection refused";
    } catch (IOException broken) {
      // Any other failure: the connection closed or reset under the request.
      return "connection lost";
    }
  }
}
