package io.plainround.seatwave.proxy.slow;

import java.time.Duration;
import java.util.Objects;

/**
 * What one slow-client run measured.
 *
 * @param completed slow clients that read a whole 200 response
 * @param failed slow clients that got anything else, or gave up
 * @param bodyBytes the bytes one complete download read, headers included
 * @param downloadP50 the median time a slow client needed for its download
 * @param downloadMax the longest download
 * @param workersBusyMax the most SeatWave workers busy at one reading
 * @param missedSamples readings of SeatWave's metrics that could not be taken
 * @param workers SeatWave's worker threads in all
 * @param workerSeconds the worker time SeatWave spent during the run, all workers together
 * @param probeP50 the median latency of the fast probe straight to SeatWave during the run
 * @param probeP99 the 99th percentile of the same
 * @param probeMax the slowest probe
 * @param probes how many probes got 200
 * @param probeFailures how many probes timed out, were refused or got another status
 */
public record SlowResult(
    int completed,
    int failed,
    long bodyBytes,
    Duration downloadP50,
    Duration downloadMax,
    int workersBusyMax,
    int missedSamples,
    int workers,
    double workerSeconds,
    Duration probeP50,
    Duration probeP99,
    Duration probeMax,
    int probes,
    int probeFailures) {

  /** Checks that nothing is missing. */
  public SlowResult {
    Objects.requireNonNull(downloadP50, "downloadP50");
    Objects.requireNonNull(downloadMax, "downloadMax");
    Objects.requireNonNull(probeP50, "probeP50");
    Objects.requireNonNull(probeP99, "probeP99");
    Objects.requireNonNull(probeMax, "probeMax");
  }
}
