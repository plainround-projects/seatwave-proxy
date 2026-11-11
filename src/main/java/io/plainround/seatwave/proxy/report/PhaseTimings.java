package io.plainround.seatwave.proxy.report;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The five phases of one request and its total, as measured by one run.
 *
 * <p>The invariant of the timing report lives here, where no code path can go around it: no phase
 * is negative, and the five phases add up to the total within {@link #SUM_TOLERANCE}. A value that
 * breaks either rule cannot be created, so no report row can hold it.
 *
 * @param dns the DNS phase
 * @param connect the TCP connect phase
 * @param tls the TLS handshake phase, zero for plain HTTP or a reused connection
 * @param firstByte from the end of the handshakes to the first response byte
 * @param transfer from the first response byte to the last
 * @param total the whole request, as the timing tool reported it
 */
public record PhaseTimings(
    Duration dns,
    Duration connect,
    Duration tls,
    Duration firstByte,
    Duration transfer,
    Duration total) {

  /**
   * How far the sum of the phases may be from the total. Curl reports each clock reading to the
   * microsecond, so a clean run sums to its total within a few microseconds; a gap of a millisecond
   * means the readings are not from one clean transfer.
   */
  public static final Duration SUM_TOLERANCE = Duration.ofMillis(1);

  /**
   * Creates the timings.
   *
   * @throws IllegalArgumentException if a phase is negative or the phases do not sum to the total
   */
  public PhaseTimings {
    Optional<String> problem = problemWith(dns, connect, tls, firstByte, transfer, total);
    if (problem.isPresent()) {
      throw new IllegalArgumentException(problem.get());
    }
  }

  /**
   * Checks the timings and creates them, or says what is wrong.
   *
   * @param dns the DNS phase
   * @param connect the TCP connect phase
   * @param tls the TLS phase
   * @param firstByte the first-byte phase
   * @param transfer the transfer phase
   * @param total the total
   * @return the timings, or the reason they break the invariant
   */
  public static Result<PhaseTimings, String> of(
      Duration dns,
      Duration connect,
      Duration tls,
      Duration firstByte,
      Duration transfer,
      Duration total) {
    Optional<String> problem = problemWith(dns, connect, tls, firstByte, transfer, total);
    if (problem.isPresent()) {
      return Result.error(problem.get());
    }
    return Result.success(new PhaseTimings(dns, connect, tls, firstByte, transfer, total));
  }

  /**
   * Returns one phase.
   *
   * @param phase the phase
   * @return its duration
   */
  public Duration of(Phase phase) {
    return switch (phase) {
      case DNS -> dns;
      case CONNECT -> connect;
      case TLS -> tls;
      case FIRST_BYTE -> firstByte;
      case TRANSFER -> transfer;
    };
  }

  /**
   * Returns the longest phase: the one to look at first.
   *
   * @return the dominant phase; on a tie, the earlier one
   */
  public Phase dominant() {
    Phase longest = Phase.DNS;
    for (Phase phase : Phase.values()) {
      // Strictly longer only, so a tie keeps the earlier phase.
      if (of(phase).compareTo(of(longest)) > 0) {
        longest = phase;
      }
    }
    return longest;
  }

  private static Optional<String> problemWith(
      Duration dns,
      Duration connect,
      Duration tls,
      Duration firstByte,
      Duration transfer,
      Duration total) {
    Objects.requireNonNull(dns, "dns");
    Objects.requireNonNull(connect, "connect");
    Objects.requireNonNull(tls, "tls");
    Objects.requireNonNull(firstByte, "firstByte");
    Objects.requireNonNull(transfer, "transfer");
    Objects.requireNonNull(total, "total");
    List<Duration> phases = List.of(dns, connect, tls, firstByte, transfer);
    if (phases.stream().anyMatch(Duration::isNegative) || total.isNegative()) {
      return Optional.of("a phase is negative: the clock readings are out of order");
    }
    Duration sum = phases.stream().reduce(Duration.ZERO, Duration::plus);
    if (sum.minus(total).abs().compareTo(SUM_TOLERANCE) > 0) {
      return Optional.of(
          "the phases sum to "
              + sum.toNanos()
              + " ns, but the total is "
              + total.toNanos()
              + " ns");
    }
    return Optional.empty();
  }
}
