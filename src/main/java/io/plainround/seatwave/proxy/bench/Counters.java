package io.plainround.seatwave.proxy.bench;

/**
 * Counters read from inside one container: its CPU time from its control group, and the TCP
 * connections its network namespace opened, from the kernel's {@code /proc/net/snmp}.
 *
 * @param cpuMicros CPU time used by every process of the container, in microseconds
 * @param passiveOpens connections the container accepted
 * @param activeOpens connections the container opened to others
 */
public record Counters(long cpuMicros, long passiveOpens, long activeOpens) {

  /**
   * Checks that no counter is negative.
   *
   * @throws IllegalArgumentException if one is
   */
  public Counters {
    if (cpuMicros < 0 || passiveOpens < 0 || activeOpens < 0) {
      throw new IllegalArgumentException("a counter cannot be negative");
    }
  }

  /**
   * Returns how much each counter grew since an earlier reading.
   *
   * @param earlier the reading before the run
   * @return the growth, which is what one run cost
   * @throws IllegalArgumentException if a counter went down, which happens when the container
   *     started again between the two readings
   */
  public Counters since(Counters earlier) {
    return new Counters(
        Math.subtractExact(cpuMicros, earlier.cpuMicros),
        Math.subtractExact(passiveOpens, earlier.passiveOpens),
        Math.subtractExact(activeOpens, earlier.activeOpens));
  }
}
