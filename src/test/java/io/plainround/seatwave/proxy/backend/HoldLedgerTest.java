package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class HoldLedgerTest {

  private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

  @Test
  void letsExactlyOneOfManyConcurrentRequestsHoldOneSeat() throws Exception {
    HoldLedger ledger = new HoldLedger(Clock.fixed(NOW, ZoneOffset.UTC));
    int racers = 100;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Optional<Hold>>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int racer = 0; racer < racers; racer++) {
        String customer = "fan-" + racer;
        results.add(
            pool.submit(() -> {
              start.await();
              return ledger.hold(1, "A-7", customer);
            }));
      }
      start.countDown();
      long winners = 0;
      for (Future<Optional<Hold>> result : results) {
        winners += result.get().isPresent() ? 1 : 0;
      }

      assertThat(winners).isEqualTo(1);
    }
  }

  @Test
  void letsAnotherCustomerHoldTheSeatOnceTheFirstHoldExpired() {
    MovableClock clock = new MovableClock(NOW);
    HoldLedger ledger = new HoldLedger(clock);
    Hold first = ledger.hold(1, "B-2", "fan-1").orElseThrow();

    clock.advance(HoldLedger.HOLD_DURATION.minusSeconds(1));
    Optional<Hold> tooEarly = ledger.hold(1, "B-2", "fan-2");
    clock.advance(Duration.ofSeconds(1));
    Optional<Hold> afterExpiry = ledger.hold(1, "B-2", "fan-2");

    assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    assertThat(tooEarly).isEmpty();
    assertThat(afterExpiry).isPresent();
    assertThat(ledger.isHeld("B-2")).isTrue();
    assertThat(ledger.find(first.holdId())).contains(first);
  }

  /** A clock a test moves forward by hand; used by one thread. */
  private static final class MovableClock extends Clock {
    private Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration step) {
      now = now.plus(step);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
