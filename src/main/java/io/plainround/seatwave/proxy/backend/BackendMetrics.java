package io.plainround.seatwave.proxy.backend;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * SeatWave's own counters, written in the Prometheus text format at {@code GET /metrics}.
 *
 * <p>The labels are the route template and the status, never a raw path or a client address, so the
 * number of series stays small (the guidelines' rule 68). {@code seatwave_workers_busy} is the
 * number the buffering lab watches: how many of the worker threads are inside a request right now,
 * including the time spent writing the response to a slow client. Thread-safe: every field is a
 * concurrent counter.
 */
final class BackendMetrics {

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  private final ConcurrentMap<String, LongAdder> requests = new ConcurrentHashMap<>();
  private final AtomicInteger busyWorkers = new AtomicInteger();
  private final AtomicInteger busyWorkersMax = new AtomicInteger();
  private final LongAdder busyNanos = new LongAdder();
  private final LongAdder responseBytes = new LongAdder();
  private final LongAdder forwardingIgnored = new LongAdder();
  private final LongAdder forwardingUnreadable = new LongAdder();
  private final LongAdder rejectedByQueue = new LongAdder();
  private final int workerThreads;

  BackendMetrics(int workerThreads) {
    this.workerThreads = workerThreads;
  }

  void workerStarted() {
    int busy = busyWorkers.incrementAndGet();
    busyWorkersMax.accumulateAndGet(busy, Math::max);
  }

  void workerFinished(String route, int status, long bytes, long elapsedNanos) {
    busyWorkers.decrementAndGet();
    busyNanos.add(elapsedNanos);
    responseBytes.add(bytes);
    requests.computeIfAbsent(route + "|" + status, key -> new LongAdder()).increment();
  }

  void forwardingIgnored() {
    forwardingIgnored.increment();
  }

  void forwardingUnreadable() {
    forwardingUnreadable.increment();
  }

  void rejectedByQueue() {
    rejectedByQueue.increment();
  }

  int busyWorkers() {
    return busyWorkers.get();
  }

  String render() {
    StringBuilder text = new StringBuilder();
    text.append("# TYPE seatwave_requests_total counter\n");
    Map<String, Long> sorted = new TreeMap<>();
    requests.forEach((key, count) -> sorted.put(key, count.sum()));
    sorted.forEach((key, count) -> {
      int bar = key.lastIndexOf('|');
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_requests_total{route=\"%s\",status=\"%s\"} %d%n",
              key.substring(0, bar),
              key.substring(bar + 1),
              count));
    });
    line(text, "gauge", "seatwave_workers", workerThreads);
    line(text, "gauge", "seatwave_workers_busy", busyWorkers.get());
    line(text, "gauge", "seatwave_workers_busy_max", busyWorkersMax.get());
    text.append("# TYPE seatwave_worker_busy_seconds_total counter\n")
        .append(
            String.format(
                Locale.ROOT,
                "seatwave_worker_busy_seconds_total %.6f%n",
                busyNanos.sum() / NANOS_PER_SECOND));
    line(text, "counter", "seatwave_response_bytes_total", responseBytes.sum());
    line(text, "counter", "seatwave_forwarding_headers_ignored_total", forwardingIgnored.sum());
    line(
        text,
        "counter",
        "seatwave_forwarding_headers_unreadable_total",
        forwardingUnreadable.sum());
    line(text, "counter", "seatwave_rejected_queue_full_total", rejectedByQueue.sum());
    return text.toString();
  }

  private static void line(StringBuilder text, String type, String name, long value) {
    text.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    text.append(name).append(' ').append(value).append('\n');
  }
}
