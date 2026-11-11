package io.plainround.seatwave.proxy.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The seats that are held, in memory, with Module 1's rule that a seat never has two owners.
 *
 * <p>Module 1 enforced the rule with a conditional {@code UPDATE} in its database. This lab backend
 * keeps holds in memory, because Module 9 studies what happens in front of SeatWave; the rule is
 * kept by {@link ConcurrentMap#compute}, which runs one update per seat at a time, so two requests
 * for one seat cannot both win. Production code keeps the rule in the database, as Module 7 does.
 * Thread-safe.
 */
final class HoldLedger {

  // Module 1's hold: 300 seconds.
  static final Duration HOLD_DURATION = Duration.ofSeconds(300);

  private final ConcurrentMap<String, Hold> holdsBySeat = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Hold> holdsById = new ConcurrentHashMap<>();
  private final Clock clock;

  HoldLedger(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Holds a seat if nobody holds it now.
   *
   * @return the new hold, or empty if another active hold owns the seat
   */
  Optional<Hold> hold(long eventId, String seat, String customer) {
    Instant now = clock.instant();
    Hold candidate =
        new Hold(UUID.randomUUID().toString(), eventId, seat, customer, now.plus(HOLD_DURATION));
    // compute runs atomically per seat: the check and the write cannot be split by another thread.
    Hold owner =
        holdsBySeat.compute(
            seat,
            (label, existing) ->
                existing == null || !existing.isActiveAt(now) ? candidate : existing);
    if (owner != candidate) {
      return Optional.empty();
    }
    holdsById.put(candidate.holdId(), candidate);
    return Optional.of(candidate);
  }

  Optional<Hold> find(String holdId) {
    return Optional.ofNullable(holdsById.get(holdId));
  }

  Instant now() {
    return clock.instant();
  }

  boolean isHeld(String seat) {
    Hold hold = holdsBySeat.get(seat);
    return hold != null && hold.isActiveAt(clock.instant());
  }
}
