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
   * Returns the proxy's CPU time per thousand successful requests.
   *
   * @return milliseconds of CPU, or 0 when nothing succeeded
   */
  public double proxyCpuMillisPerThousand() {
    return perThousand(proxy.cpuMicros() / MICROS_PER_MILLI);
  }

  /**
   * Returns SeatWave's CPU time per thousand successful requests.
   *
   * @return milliseconds of CPU, or 0 when nothing succeeded
   */
  public double backendCpuMillisPerThousand() {
    return perThousand(backend.cpuMicros() / MICROS_PER_MILLI);
  }

  /**
   * Returns the connections SeatWave accepted per thousand successful requests.
   *
   * @return connections, or 0 when nothing succeeded
   */
  public double backendConnectionsPerThousand() {
    return perThousand(backend.passiveOpens());
  }

  private double perThousand(double amount) {
    return result.requests() == 0 ? 0 : amount * PER_THOUSAND / result.requests();
  }
}
