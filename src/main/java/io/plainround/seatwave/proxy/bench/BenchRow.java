package io.plainround.seatwave.proxy.bench;

import java.util.Objects;

/**
 * One measured run of one scenario: what the clients saw and what it cost each container.
 *
 * @param scenario the scenario
 * @param result what the clients measured
 * @param proxy how much the proxy's counters grew during the run
 * @param backend how much SeatWave's counters grew during the run
 */
public record BenchRow(Scenario scenario, LoadResult result, Counters proxy, Counters backend) {

  private static final double PER_THOUSAND = 1_000.0;
  private static final double MICROS_PER_MILLI = 1_000.0;

  /** Checks that nothing is missing. */
  public BenchRow {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(proxy, "proxy");
    Objects.requireNonNull(backend, "backend");
  }

  /**
   * Returns the proxy's CPU time per thousand requests sent.
   *
   * @return milliseconds of CPU, or 0 when nothing was sent
   */
  public double proxyCpuMillisPerThousand() {
    return perThousand(proxy.cpuMicros() / MICROS_PER_MILLI);
  }

  /**
   * Returns SeatWave's CPU time per thousand requests sent.
   *
   * @return milliseconds of CPU, or 0 when nothing was sent
   */
  public double backendCpuMillisPerThousand() {
    return perThousand(backend.cpuMicros() / MICROS_PER_MILLI);
  }

  private double perThousand(double amount) {
    // Every request sent, successful or not: a container spends CPU on a failed request too, so
    // dividing by the successes alone would charge the failures' CPU to them.
    long sent = Math.addExact(result.requests(), result.errors());
    return sent == 0 ? 0 : amount * PER_THOUSAND / sent;
  }
}
