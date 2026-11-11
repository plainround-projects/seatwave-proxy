package io.plainround.seatwave.proxy.slow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Many slow clients at once, the way fans on a weak mobile link download a page: each opens a
 * connection with a small receive buffer, asks for one resource and reads it a few kilobytes at a
 * time. Meanwhile one fast probe asks SeatWave directly for {@code /health}, and a sampler reads
 * SeatWave's busy workers from its operations listener.
 *
 * <p>Straight to SeatWave, a slow client keeps a worker writing until the client has read nearly
 * everything; behind a buffering proxy the proxy reads the whole response at once and does the slow
 * writing itself. The run measures both through the same numbers. Thread-safe: every run uses only
 * its own threads and collections.
 */
public final class SlowClients {

  private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] (\\d{3}) .*");
  private static final Pattern CONTENT_LENGTH =
      Pattern.compile("(?im)^content-length:[ \\t]*(\\d{1,18})[ \\t]*\\r?$");
  private static final String HEAD_END = "\r\n\r\n";
  // The most bytes of response head kept: NGINX's and the JDK server's heads are far smaller, and a
  // head that does not end by then is not one this lab produces.
  private static final int MAX_HEAD_BYTES = 16_384;
  private static final Pattern BUSY = Pattern.compile("(?m)^seatwave_workers_busy (\\d+)$");
  private static final Pattern WORKERS = Pattern.compile("(?m)^seatwave_workers (\\d+)$");
  private static final Pattern BUSY_SECONDS =
      Pattern.compile("(?m)^seatwave_worker_busy_seconds_total ([0-9.]+)$");
  private static final int OK = 200;
  private static final int HTTP_PORT = 80;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
  // The longest a single probe or metrics request may take.
  private static final Duration SIDE_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private SlowClients() {}

  /**
   * Runs the slow clients, the probe and the sampler, and waits for every client.
   *
   * @param settings the run
   * @return what was measured
   * @throws IOException if SeatWave's metrics cannot be read before or after the run
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static SlowResult run(SlowSettings settings) throws IOException, InterruptedException {
    Objects.requireNonNull(settings, "settings");
    try (HttpClient side =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(SIDE_REQUEST_TIMEOUT)
                .build();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      String before = metrics(side, settings.metrics());
      AtomicBoolean running = new AtomicBoolean(true);
      AtomicInteger busyMax = new AtomicInteger();
      Future<ProbeTally> probe = pool.submit(() -> probe(side, settings, running));
      Future<Integer> sampler = pool.submit(() -> sample(side, settings, running, busyMax));
      List<Future<Download>> downloads = new ArrayList<>(settings.clients());
      for (int clientIndex = 0; clientIndex < settings.clients(); clientIndex++) {
        downloads.add(pool.submit(() -> download(settings)));
      }
      // One deadline for the whole run, so waiting for each client in turn cannot add up.
      long deadlineNanos = System.nanoTime() + settings.timeout().toNanos();
      List<Download> finished = new ArrayList<>(settings.clients());
      for (Future<Download> download : downloads) {
        finished.add(awaitDownload(download, deadlineNanos));
      }
      running.set(false);
      ProbeTally probes = await(probe, settings.timeout());
      int missedSamples = await(sampler, settings.timeout());
      String after = metrics(side, settings.metrics());
      return result(finished, probes, new Busy(busyMax.get(), missedSamples), before, after);
    }
  }

  private static Download download(SlowSettings settings) {
    URI target = settings.target();
    long startNanos = System.nanoTime();
    try (Socket socket = new Socket()) {
      // Set before connecting, so the small window is the one the handshake announces.
      socket.setReceiveBufferSize(settings.receiveBufferBytes());
      int port = target.getPort() > 0 ? target.getPort() : HTTP_PORT;
      socket.connect(
          new InetSocketAddress(target.getHost(), port),
          Math.toIntExact(settings.timeout().toMillis()));
      socket.setSoTimeout(Math.toIntExact(settings.timeout().toMillis()));
      String path =
          target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET "
                  + path
                  + " HTTP/1.1\r\nHost: "
                  + target.getHost()
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      InputStream in = socket.getInputStream();
      byte[] buffer = new byte[settings.bytesPerTick()];
      long total = 0;
      // The head is collected across reads, so a client that reads a few bytes per tick still
      // sees the whole status line and Content-Length.
      StringBuilder head = new StringBuilder();
      int read;
      while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
        if (head.length() < MAX_HEAD_BYTES) {
          head.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
        }
        total += read;
        if (read < buffer.length) {
          break;
        }
        Thread.sleep(settings.tick());
      }
      return new Download(
          isComplete(head.toString(), total),
          total,
          Duration.ofNanos(System.nanoTime() - startNanos));
    } catch (IOException failed) {
      return new Download(false, 0, Duration.ofNanos(System.nanoTime() - startNanos));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Download(false, 0, Duration.ofNanos(System.nanoTime() - startNanos));
    }
  }

  private static ProbeTally probe(HttpClient side, SlowSettings settings, AtomicBoolean running)
      throws InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(settings.fastProbe()).timeout(SIDE_REQUEST_TIMEOUT).GET().build();
    List<Duration> latencies = new ArrayList<>();
    int failures = 0;
    while (running.get()) {
      long sentNanos = System.nanoTime();
      try {
        int status = side.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status == OK) {
          latencies.add(Duration.ofNanos(System.nanoTime() - sentNanos));
        } else {
          failures++;
        }
      } catch (IOException failed) {
        // A probe that times out or is refused is what a fan behind the slow ones would see.
        failures++;
      }
      Thread.sleep(settings.sample());
    }
    return new ProbeTally(latencies, failures);
  }

  private static Integer sample(
      HttpClient side, SlowSettings settings, AtomicBoolean running, AtomicInteger busyMax)
      throws InterruptedException {
    int missed = 0;
    while (running.get()) {
      try {
        Matcher busy = BUSY.matcher(metrics(side, settings.metrics()));
        if (busy.find()) {
          busyMax.accumulateAndGet(Integer.parseInt(busy.group(1)), Math::max);
        } else {
          missed++;
        }
      } catch (IOException unreadable) {
        // A missed reading is counted and reported, so a gap in the samples is never hidden.
        missed++;
      }
      Thread.sleep(settings.sample());
    }
    return missed;
  }

  private static String metrics(HttpClient side, URI uri) throws IOException, InterruptedException {
    HttpResponse<String> response =
        side.send(
            HttpRequest.newBuilder(uri).timeout(SIDE_REQUEST_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != OK) {
      throw new IOException(uri + " answered " + response.statusCode());
    }
    return response.body();
  }

  private static SlowResult result(
      List<Download> downloads, ProbeTally probes, Busy busy, String before, String after)
      throws IOException {
    List<Duration> times =
        downloads.stream().filter(Download::isComplete).map(Download::elapsed).sorted().toList();
    long bytes =
        downloads.stream().filter(Download::isComplete).mapToLong(Download::bytes).max().orElse(0);
    List<Duration> sortedProbes = new ArrayList<>(probes.latencies());
    Collections.sort(sortedProbes);
    return new SlowResult(
        times.size(),
        downloads.size() - times.size(),
        bytes,
        percentile(times, PERCENTILE_50),
        times.isEmpty() ? Duration.ZERO : times.getLast(),
        busy.max(),
        busy.missedSamples(),
        Integer.parseInt(number(WORKERS, after)),
        Double.parseDouble(number(BUSY_SECONDS, after))
            - Double.parseDouble(number(BUSY_SECONDS, before)),
        percentile(sortedProbes, PERCENTILE_50),
        percentile(sortedProbes, PERCENTILE_99),
        sortedProbes.isEmpty() ? Duration.ZERO : sortedProbes.getLast(),
        sortedProbes.size(),
        probes.failures());
  }

  private static String number(Pattern pattern, String metrics) throws IOException {
    Matcher matcher = pattern.matcher(metrics);
    if (!matcher.find()) {
      throw new IOException("SeatWave's metrics have no line for " + pattern.pattern());
    }
    return matcher.group(1);
  }

  static Duration percentile(List<Duration> sorted, double share) {
    if (sorted.isEmpty()) {
      return Duration.ZERO;
    }
    int rank = (int) Math.ceil(share * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }

  /**
   * Says whether a download read a whole 200 response: the status is 200, the head ended, and the
   * body after it is exactly as long as Content-Length promised. A server that closes early, or a
   * reply without a length (which this lab never sends), is not a complete download.
   *
   * @param head the first bytes of the response, at least the whole head
   * @param total every byte read, head included
   * @return true for a complete 200 response
   */
  static boolean isComplete(String head, long total) {
    int headEnd = head.indexOf(HEAD_END);
    if (status(head) != OK || headEnd < 0) {
      return false;
    }
    Matcher length = CONTENT_LENGTH.matcher(head.substring(0, headEnd + HEAD_END.length()));
    long bodyBytes = total - headEnd - HEAD_END.length();
    return length.find() && Long.parseLong(length.group(1)) == bodyBytes;
  }

  static int status(String head) {
    Matcher matcher = STATUS_LINE.matcher(head.lines().findFirst().orElse(""));
    return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private static Download awaitDownload(Future<Download> download, long deadlineNanos)
      throws InterruptedException {
    long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
    try {
      return download.get(remainingNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException tooSlow) {
      download.cancel(true);
      return new Download(false, 0, Duration.ofNanos(remainingNanos));
    } catch (ExecutionException failed) {
      // download() catches its own failures, so this is a bug; it is counted as a failed client.
      return new Download(false, 0, Duration.ZERO);
    }
  }

  private static <T> T await(Future<T> task, Duration timeout)
      throws InterruptedException, IOException {
    try {
      return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException failed) {
      task.cancel(true);
      throw new IOException("a side task of the run did not finish", failed);
    }
  }

  /** One slow client's download. */
  private record Download(boolean isComplete, long bytes, Duration elapsed) {}

  /** The fast probe's successful latencies and how many probes failed. */
  private record ProbeTally(List<Duration> latencies, int failures) {}

  /** The most workers seen busy at once, and the readings that could not be taken. */
  private record Busy(int max, int missedSamples) {}
}
