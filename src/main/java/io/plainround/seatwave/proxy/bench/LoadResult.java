package io.plainround.seatwave.proxy.bench;

import java.time.Duration;
import java.util.Objects;

/**
 * What one load run measured.
 *
 * @param requests requests that got a 2xx answer
 * @param errors requests that failed or got anything else
 * @param elapsed how long the run lasted
 * @param p50 the median latency of the successful requests
 * @param p99 the 99th percentile latency of the successful requests
 * @param wireBytes body bytes received, as they crossed the wire (compressed if gzip was used)
 * @param plainBytes body bytes after decompression; equal to wireBytes when nothing was compressed
 * @param cacheHits responses whose {@code X-Cache-Status} was HIT
 * @param cacheLookups responses that carried an {@code X-Cache-Status} at all
 */
public record LoadResult(
    long requests,
    long errors,
    Duration elapsed,
    Duration p50,
    Duration p99,
    long wireBytes,
    long plainBytes,
    long cacheHits,
    long cacheLookups) {

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  /**
   * Checks the counts.
   *
   * @throws IllegalArgumentException if a count is negative or there were more hits than lookups
   */
  public LoadResult {
    Objects.requireNonNull(elapsed, "elapsed");
    Objects.requireNonNull(p50, "p50");
    Objects.requireNonNull(p99, "p99");
    if (requests < 0 || errors < 0 || wireBytes < 0 || plainBytes < 0 || cacheHits < 0) {
      throw new IllegalArgumentException("a count cannot be negative");
    }
    if (cacheHits > cacheLookups) {
      throw new IllegalArgumentException("more cache hits than lookups");
    }
  }

  /**
   * Returns successful requests per second.
   *
   * @return the throughput of the run
   */
  public double requestsPerSecond() {
    return elapsed.isZero() ? 0 : requests * NANOS_PER_SECOND / elapsed.toNanos();
  }
}
