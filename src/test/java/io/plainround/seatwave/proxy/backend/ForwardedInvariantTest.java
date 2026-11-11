package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The module's invariant, end to end over HTTP: a forged forwarding header sent straight to
 * SeatWave is ignored, and the same header from a trusted proxy is believed.
 */
class ForwardedInvariantTest {

  @Test
  void ignoresAForgedXForwardedForSentStraightToSeatWave() {
    try (TestBackend backend = TestBackend.untrusting()) {
      HttpResponse<String> view =
          backend.send(
              HttpRequest.newBuilder(backend.plain("/lab/client"))
                  .header("X-Forwarded-For", "203.0.113.7")
                  .header("X-Forwarded-Proto", "https")
                  .build());

      assertThat(view.body())
          .isEqualTo("{\"address\":\"127.0.0.1\",\"scheme\":\"http\",\"source\":\"peer\"}");
      assertThat(backend.metrics()).contains("seatwave_forwarding_headers_ignored_total 1");
    }
  }

  @Test
  void believesTheSameHeaderFromATrustedProxy() {
    try (TestBackend backend =
        TestBackend.start(List.of("127.0.0.1"), TestBackend.quickTiming(), 4, 16)) {
      HttpResponse<String> view =
          backend.send(
              HttpRequest.newBuilder(backend.plain("/lab/client"))
                  .header("X-Forwarded-For", "203.0.113.7")
                  .header("X-Forwarded-Proto", "https")
                  .build());

      assertThat(view.body())
          .isEqualTo(
              "{\"address\":\"203.0.113.7\",\"scheme\":\"https\",\"source\":\"x_forwarded_for\"}");
      assertThat(backend.metrics()).contains("seatwave_forwarding_headers_ignored_total 0");
    }
  }

  @Test
  void countsAnUnreadableHeaderFromATrustedProxyAndFallsBackToThePeer() {
    try (TestBackend backend =
        TestBackend.start(List.of("127.0.0.1"), TestBackend.quickTiming(), 4, 16)) {
      HttpResponse<String> view =
          backend.send(
              HttpRequest.newBuilder(backend.tls("/lab/client"))
                  .header("Forwarded", "for=unknown")
                  .build());

      assertThat(view.body())
          .isEqualTo("{\"address\":\"127.0.0.1\",\"scheme\":\"https\",\"source\":\"unreadable\"}");
      assertThat(backend.metrics()).contains("seatwave_forwarding_headers_unreadable_total 1");
    }
  }

  @Test
  void usesTheProxysRequestIdOnlyWhenTheProxyIsTrusted() {
    String requestId = "0123456789abcdef0123456789abcdef";
    try (TestBackend untrusting = TestBackend.untrusting();
        TestBackend trusting =
            TestBackend.start(List.of("127.0.0.1"), TestBackend.quickTiming(), 4, 16)) {
      HttpRequest.Builder request =
          HttpRequest.newBuilder().header("X-Request-Id", requestId).GET();

      String fromStranger =
          untrusting
              .send(
                  request
                      .uri(untrusting.plain("/v1/holds/00000000-0000-0000-0000-000000000000"))
                      .build())
              .body();
      String fromProxy =
          trusting
              .send(
                  request
                      .uri(trusting.plain("/v1/holds/00000000-0000-0000-0000-000000000000"))
                      .build())
              .body();

      assertThat(fromStranger).doesNotContain(requestId);
      assertThat(fromProxy).contains("\"instance\":\"urn:seatwave:request:" + requestId + "\"");
    }
  }
}
