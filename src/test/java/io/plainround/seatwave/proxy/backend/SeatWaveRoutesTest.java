package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.proxy.forwarded.AddressSource;
import io.plainround.seatwave.proxy.forwarded.ClientView;
import io.plainround.seatwave.proxy.json.JsonCodec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The routes' answers that cannot be reached over HTTP on demand: a key that is still running, a
 * full key table and a long poll interrupted by a stopping server.
 */
class SeatWaveRoutesTest {

  private static final String KEY = "\"key-1\"";
  private static final String BODY = "{\"eventId\":1,\"seat\":\"A-1\",\"customer\":\"fan-1\"}";
  private static final ClientView CLIENT =
      new ClientView(InetAddress.getLoopbackAddress(), "http", AddressSource.PEER);

  private final HoldKeys keys = new HoldKeys();
  private final SeatWaveRoutes routes =
      new SeatWaveRoutes(
          new HoldLedger(Clock.systemUTC()),
          keys,
          new BackendSettings.Timing(
              Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofMillis(350)),
          new JsonCodec());

  @Test
  void tellsASecondRequestWithARunningKeyToRetryLaterWithConflict() throws Exception {
    Request first = hold(BODY);
    // The routes fingerprint a body by its SHA-256; a wrong guess here would answer 422, not 409.
    keys.claim(KEY, sha256(first.body()));

    Reply.Body second = (Reply.Body) routes.route(first).reply();

    assertThat(second.status()).isEqualTo(409);
    assertThat(text(second)).contains("problems/request-in-progress");
  }

  @Test
  void refusesANewKeyWithServiceBusyWhenTheKeyTableIsFull() {
    for (int keyIndex = 0; keyIndex < HoldKeys.MAX_KEYS; keyIndex++) {
      keys.claim("\"k" + keyIndex + "\"", "fingerprint");
    }

    Reply.Body refused = (Reply.Body) routes.route(hold(BODY)).reply();

    assertThat(refused.status()).isEqualTo(503);
    assertThat(refused.headers()).contains(Map.entry("Retry-After", "1"));
    assertThat(text(refused)).contains("problems/service-busy", "the key table is full");
  }

  @Test
  void endsALongPollWithServiceBusyWhenTheServerInterruptsIt() throws Exception {
    AtomicReference<Thread> worker = new AtomicReference<>();
    Request poll =
        new Request(
            "GET",
            "/v1/events/1/seat-changes",
            "after=1:42",
            Map.of(),
            new byte[0],
            false,
            CLIENT,
            "c1");
    CompletableFuture<Reply> answer =
        CompletableFuture.supplyAsync(() -> {
          worker.set(Thread.currentThread());
          return routes.route(poll).reply();
        });
    // The poll waits 30 s; interrupting its thread is what BackendServer.close does.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> worker.get() != null && worker.get().getState() == Thread.State.TIMED_WAITING);

    worker.get().interrupt();
    Reply.Body stopped = (Reply.Body) answer.get();

    assertThat(stopped.status()).isEqualTo(503);
    assertThat(text(stopped)).contains("the server is stopping");
  }

  @Test
  void namesNoPointerWhenTheWholeBodyIsAtFault() {
    Reply.Body refused = (Reply.Body) routes.route(hold("[1]")).reply();

    assertThat(refused.status()).isEqualTo(400);
    assertThat(text(refused)).contains("\"detail\":\"the body must be a JSON object\"");
  }

  private static Request hold(String body) {
    return new Request(
        "POST",
        "/v1/holds",
        "",
        Map.of("content-type", List.of("application/json"), "idempotency-key", List.of(KEY)),
        body.getBytes(StandardCharsets.UTF_8),
        false,
        CLIENT,
        "c1");
  }

  private static String sha256(byte[] body) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
  }

  private static String text(Reply.Body body) {
    return new String(body.body(), StandardCharsets.UTF_8);
  }
}
