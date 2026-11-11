package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
    assertThat(List.of(overflow, existing)).doesNotHaveDuplicates();
  }
}
