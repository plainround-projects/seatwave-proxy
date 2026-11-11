package io.plainround.seatwave.proxy.forwarded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientAddressTest {

  private static final InetAddress PROXY = InetAddress.ofLiteral("10.209.9.10");
  private static final InetAddress SIDECAR = InetAddress.ofLiteral("127.0.0.1");
  private static final InetAddress STRANGER = InetAddress.ofLiteral("198.51.100.20");
  private static final TrustedProxies TRUSTED =
      TrustedProxies.parse(List.of("10.209.9.10", "127.0.0.1"))
          .orOnErrorThrow(IllegalStateException::new);

  @Test
  void ignoresAForgedForwardedForFromAPeerThatIsNotTrusted() {
    ForwardedHeaders forged = xff(List.of("203.0.113.7"), List.of("https"));

    ClientView view = ClientAddress.resolve(STRANGER, forged, TRUSTED, "http");

    assertThat(view).isEqualTo(new ClientView(STRANGER, "http", AddressSource.PEER));
  }

  @Test
  void ignoresAForgedForwardedHeaderFromAPeerThatIsNotTrusted() {
    ForwardedHeaders forged =
        new ForwardedHeaders(List.of("for=203.0.113.7;proto=https"), List.of(), List.of());

    ClientView view = ClientAddress.resolve(STRANGER, forged, TRUSTED, "https");

    assertThat(view.address()).isEqualTo(STRANGER);
    assertThat(view.source()).isEqualTo(AddressSource.PEER);
  }

  @Test
  void believesTheProxysXForwardedForAndProto() {
    ClientView view =
        ClientAddress.resolve(
            PROXY, xff(List.of("203.0.113.7"), List.of("https")), TRUSTED, "http");

    assertThat(view)
        .isEqualTo(
            new ClientView(
                InetAddress.ofLiteral("203.0.113.7"), "https", AddressSource.X_FORWARDED_FOR));
  }

  @Test
  void takesTheRightMostHopThatIsNotATrustedProxyNeverTheLeftMost() {
    // A client wrote 192.0.2.66 itself; a proxy that appends would keep it on the left.
    ForwardedHeaders appended = xff(List.of("192.0.2.66, 203.0.113.7, 10.209.9.10"), List.of());

    ClientView view = ClientAddress.resolve(SIDECAR, appended, TRUSTED, "http");

    assertThat(view.address()).isEqualTo(InetAddress.ofLiteral("203.0.113.7"));
    assertThat(view.scheme()).isEqualTo("http");
  }

  @Test
  void readsEveryLineOfARepeatedHeaderInOrder() {
    ForwardedHeaders twoLines = xff(List.of("192.0.2.66", "203.0.113.7"), List.of());

    ClientView view = ClientAddress.resolve(PROXY, twoLines, TRUSTED, "http");

    assertThat(view.address()).isEqualTo(InetAddress.ofLiteral("203.0.113.7"));
  }

  @Test
  void readsTheStandardForwardedHeaderWithAQuotedIpv6AddressAndAPort() {
    ForwardedHeaders forwarded =
        new ForwardedHeaders(
            List.of("for=\"[2001:db8:cafe::17]:4711\";proto=https;by=10.209.9.10"),
            List.of("198.51.100.1"),
            List.of());

    ClientView view = ClientAddress.resolve(PROXY, forwarded, TRUSTED, "http");

    assertThat(view)
        .isEqualTo(
            new ClientView(
                InetAddress.ofLiteral("2001:db8:cafe::17"), "https", AddressSource.FORWARDED));
  }

  @Test
  void readsAnIpv4ForwardedNodeWithAPort() {
    ForwardedHeaders forwarded =
        new ForwardedHeaders(List.of("for=\"203.0.113.7:4711\""), List.of(), List.of());

    ClientView view = ClientAddress.resolve(PROXY, forwarded, TRUSTED, "https");

    assertThat(view.address()).isEqualTo(InetAddress.ofLiteral("203.0.113.7"));
    assertThat(view.scheme()).isEqualTo("https");
  }

  @Test
  void fallsBackToThePeerWhenATrustedProxySendsAHopThatIsNotAnAddress() {
    for (String unreadable : List.of("unknown", "fan.seatwave.example", "", "203.0.113.7, x")) {
      ClientView view =
          ClientAddress.resolve(PROXY, xff(List.of(unreadable), List.of()), TRUSTED, "http");

      assertThat(view).isEqualTo(new ClientView(PROXY, "http", AddressSource.UNREADABLE));
    }
  }

  @Test
  void fallsBackToThePeerWhenAForwardedElementIsMalformed() {
    for (String malformed :
        List.of(
            "for", "proto=https", "for=203.0.113.7;proto=gopher", "=x", "for=\"[2001:db8::1\"")) {
      ForwardedHeaders headers = new ForwardedHeaders(List.of(malformed), List.of(), List.of());

      ClientView view = ClientAddress.resolve(PROXY, headers, TRUSTED, "https");

      assertThat(view.source()).as(malformed).isEqualTo(AddressSource.UNREADABLE);
      assertThat(view.address()).isEqualTo(PROXY);
    }
  }

  @Test
  void refusesAChainLongerThanSixteenHopsOrALineLongerThanTheLimit() {
    String seventeenHops = String.join(", ", java.util.Collections.nCopies(17, "203.0.113.7"));
    String longLine = "203.0.113.7" + " ".repeat(ClientAddress.MAX_HEADER_CHARACTERS);

    assertThat(
            ClientAddress.resolve(PROXY, xff(List.of(seventeenHops), List.of()), TRUSTED, "http")
                .source())
        .isEqualTo(AddressSource.UNREADABLE);
    assertThat(
            ClientAddress.resolve(PROXY, xff(List.of(longLine), List.of()), TRUSTED, "http")
                .source())
        .isEqualTo(AddressSource.UNREADABLE);
    ForwardedHeaders longForwarded =
        new ForwardedHeaders(List.of("for=" + longLine), List.of(), List.of());
    assertThat(ClientAddress.resolve(PROXY, longForwarded, TRUSTED, "http").source())
        .isEqualTo(AddressSource.UNREADABLE);
  }

  @Test
  void believesNoSchemeWhenTheProtoHeaderIsInvalidOrSentTwice() {
    ClientView invalid =
        ClientAddress.resolve(
            PROXY, xff(List.of("203.0.113.7"), List.of("gopher")), TRUSTED, "http");
    ClientView twice =
        ClientAddress.resolve(
            PROXY, xff(List.of("203.0.113.7"), List.of("https", "http")), TRUSTED, "http");

    assertThat(invalid.source()).isEqualTo(AddressSource.UNREADABLE);
    assertThat(twice.source()).isEqualTo(AddressSource.UNREADABLE);
  }

  @Test
  void treatsATrustedProxyWithoutForwardingHeadersAsTheClient() {
    ClientView view = ClientAddress.resolve(PROXY, ForwardedHeaders.none(), TRUSTED, "http");

    assertThat(view).isEqualTo(new ClientView(PROXY, "http", AddressSource.PEER));
  }

  @Test
  void takesTheFirstHopWhenEveryHopIsATrustedProxy() {
    ClientView view =
        ClientAddress.resolve(
            SIDECAR, xff(List.of("10.209.9.10"), List.of("https")), TRUSTED, "http");

    assertThat(view.address()).isEqualTo(PROXY);
    assertThat(view.scheme()).isEqualTo("https");
  }

  @Test
  void refusesAnIpv6ForwardedNodeWithoutItsBrackets() {
    // RFC 7239 requires the brackets; cut at its first colon, 2001:db8::1 became 0.0.7.209.
    for (String bare : List.of("for=2001:db8::1", "for=\"2001:db8::1\"", "for=[1.2.3.4]")) {
      ForwardedHeaders headers = new ForwardedHeaders(List.of(bare), List.of(), List.of());

      ClientView view = ClientAddress.resolve(PROXY, headers, TRUSTED, "https");

      assertThat(view).as(bare).isEqualTo(new ClientView(PROXY, "https", AddressSource.UNREADABLE));
    }
  }

  @Test
  void refusesAnIpv4NodeWithMoreThanOnePort() {
    ForwardedHeaders headers =
        new ForwardedHeaders(List.of("for=\"203.0.113.7:4711:80\""), List.of(), List.of());

    ClientView view = ClientAddress.resolve(PROXY, headers, TRUSTED, "https");

    assertThat(view.source()).isEqualTo(AddressSource.UNREADABLE);
  }

  @Test
  void refusesTheShortIpv4FormsThatOfLiteralWouldAccept() {
    for (String shortForm : List.of("127.1", "2001", "10.209.2330")) {
      ClientView forwardedFor =
          ClientAddress.resolve(PROXY, xff(List.of(shortForm), List.of()), TRUSTED, "http");
      ClientView forwarded =
          ClientAddress.resolve(
              PROXY,
              new ForwardedHeaders(List.of("for=" + shortForm), List.of(), List.of()),
              TRUSTED,
              "http");

      assertThat(forwardedFor.source()).as(shortForm).isEqualTo(AddressSource.UNREADABLE);
      assertThat(forwarded.source()).as(shortForm).isEqualTo(AddressSource.UNREADABLE);
    }
    assertThat(TrustedProxies.parse(List.of("127.1")).isError()).isTrue();
  }

  @Test
  void readsAnIpv6AddressInXForwardedFor() {
    ClientView view =
        ClientAddress.resolve(
            PROXY, xff(List.of("2001:db8::17"), List.of("https")), TRUSTED, "http");

    assertThat(view)
        .isEqualTo(
            new ClientView(
                InetAddress.ofLiteral("2001:db8::17"), "https", AddressSource.X_FORWARDED_FOR));
  }

  @Test
  void takesTheFirstHopsProtoWhenEveryForwardedHopIsATrustedProxy() {
    ForwardedHeaders forwarded =
        new ForwardedHeaders(List.of("for=10.209.9.10;proto=https"), List.of(), List.of());

    ClientView view = ClientAddress.resolve(SIDECAR, forwarded, TRUSTED, "http");

    // The same answer X-Forwarded-For with X-Forwarded-Proto gives (the test above).
    assertThat(view).isEqualTo(new ClientView(PROXY, "https", AddressSource.FORWARDED));
  }

  @Test
  void believesNoProtoHeaderThatComesWithoutAnAddressHeader() {
    // NGINX always sends both; a proto header alone says nothing about the client, so the trusted
    // peer is the client and the listener's own scheme stands.
    ForwardedHeaders protoOnly = new ForwardedHeaders(List.of(), List.of(), List.of("https"));

    ClientView view = ClientAddress.resolve(PROXY, protoOnly, TRUSTED, "http");

    assertThat(view).isEqualTo(new ClientView(PROXY, "http", AddressSource.PEER));
  }

  @Test
  void refusesTrustedProxiesThatAreNamesOrEmpty() {
    assertThat(TrustedProxies.parse(List.of("proxy.seatwave.example")).isError()).isTrue();
    assertThat(TrustedProxies.parse(List.of()).isError()).isTrue();
    assertThatThrownBy(() -> new TrustedProxies(java.util.Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAViewWithASchemeOtherThanHttpOrHttps() {
    assertThatThrownBy(() -> new ClientView(PROXY, "ftp", AddressSource.PEER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ftp");
  }

  private static ForwardedHeaders xff(List<String> forwardedFor, List<String> proto) {
    return new ForwardedHeaders(List.of(), forwardedFor, proto);
  }
}
