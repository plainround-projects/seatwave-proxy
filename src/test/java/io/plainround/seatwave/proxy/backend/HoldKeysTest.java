package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class HoldKeysTest {

  private static final Reply.Body CREATED = Reply.json(201, "{\"holdId\":\"x\"}");

  @Test
  void givesTheFirstClaimTheWorkAndTellsASecondOneToWait() {
    HoldKeys keys = new HoldKeys();

    HoldKeys.Claim first = keys.claim("\"k\"", "fingerprint-1");
    HoldKeys.Claim second = keys.claim("\"k\"", "fingerprint-1");

    assertThat(first).isInstanceOf(HoldKeys.Claim.Owner.class);
    assertThat(second).isInstanceOf(HoldKeys.Claim.InProgress.class);
  }

  @Test
  void replaysTheStoredReplyAndRefusesTheKeyWithAnotherBody() {
    HoldKeys keys = new HoldKeys();
    keys.claim("\"k\"", "fingerprint-1");
    keys.complete("\"k\"", "fingerprint-1", CREATED);

    HoldKeys.Claim replay = keys.claim("\"k\"", "fingerprint-1");
    HoldKeys.Claim reused = keys.claim("\"k\"", "fingerprint-2");

    assertThat(replay).isEqualTo(new HoldKeys.Claim.Replay(CREATED));
    assertThat(reused).isInstanceOf(HoldKeys.Claim.Reused.class);
  }

  @Test
  void refusesANewKeyOnceTheTableIsFull() {
    HoldKeys keys = new HoldKeys();
    for (int keyIndex = 0; keyIndex < HoldKeys.MAX_KEYS; keyIndex++) {
      keys.claim("\"k" + keyIndex + "\"", "fingerprint");
    }

    HoldKeys.Claim overflow = keys.claim("\"one-more\"", "fingerprint");
    HoldKeys.Claim existing = keys.claim("\"k0\"", "fingerprint");

    assertThat(overflow).isInstanceOf(HoldKeys.Claim.Full.class);
    assertThat(existing).isInstanceOf(HoldKeys.Claim.InProgress.class);
  }

  @Test
  void givesOneOfManyRacingClaimsOfOneKeyTheWorkAndKeepsTheLosersOutOfTheCount() throws Exception {
    HoldKeys keys = new HoldKeys();
    int racers = 100;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HoldKeys.Claim>> claims = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int racer = 0; racer < racers; racer++) {
        claims.add(
            pool.submit(() -> {
              start.await();
              return keys.claim("\"raced\"", "fingerprint");
            }));
      }
      start.countDown();
      List<HoldKeys.Claim> outcomes = new ArrayList<>();
      for (Future<HoldKeys.Claim> claim : claims) {
        outcomes.add(claim.get());
      }

      assertThat(outcomes).filteredOn(HoldKeys.Claim.Owner.class::isInstance).hasSize(1);
      assertThat(outcomes)
          .filteredOn(HoldKeys.Claim.InProgress.class::isInstance)
          .hasSize(racers - 1);
    }
    // The race took one place in the table. A loser that kept a place would make it full early.
    for (int keyIndex = 1; keyIndex < HoldKeys.MAX_KEYS; keyIndex++) {
      assertThat(keys.claim("\"k" + keyIndex + "\"", "fingerprint"))
          .isInstanceOf(HoldKeys.Claim.Owner.class);
    }
    assertThat(keys.claim("\"over\"", "fingerprint")).isInstanceOf(HoldKeys.Claim.Full.class);
  }
}
