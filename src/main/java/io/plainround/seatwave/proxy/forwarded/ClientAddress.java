package io.plainround.seatwave.proxy.forwarded;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides a request's client address and scheme from its peer and its forwarding headers.
 *
 * <p>This is half of the module's one invariant: <b>SeatWave believes a forwarded client address
 * only when the connection comes from a trusted proxy</b>. A client that talks to SeatWave directly
 * can write any {@code X-Forwarded-For} it likes; from an untrusted peer every forwarding header is
 * ignored, so a forged one changes nothing. The server calls this once per request, before any
 * route runs, so no route can read a forwarded header on its own.
 *
 * <p>From a trusted peer the hops are read right to left, skipping hops that are themselves trusted
 * proxies; the first other hop is the client. The left-most hop is never taken on trust, because a
 * client can put anything there and a proxy that appends keeps it. A trusted peer whose headers
 * cannot be read is answered with the peer itself and {@link AddressSource#UNREADABLE}: failing
 * safe means not guessing. Thread-safe: it holds no state.
 */
public final class ClientAddress {

  /** The most hops read from one request; a longer chain is not a real path, it is an attack. */
  public static final int MAX_HOPS = 16;

  /** The longest header line read; RFC 7239 values for 16 hops fit easily. */
  public static final int MAX_HEADER_CHARACTERS = 1_024;

  private ClientAddress() {}

  /**
   * Resolves the client of one request.
   *
   * @param peer the address of the connection's other end
   * @param headers the forwarding headers as received
   * @param trusted the peers whose forwarding headers are believed
   * @param listenerScheme {@code http} or {@code https}: how the peer reached SeatWave
   * @return what SeatWave believes about the client
   */
  public static ClientView resolve(
      InetAddress peer, ForwardedHeaders headers, TrustedProxies trusted, String listenerScheme) {
    Objects.requireNonNull(peer, "peer");
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(trusted, "trusted");
    Objects.requireNonNull(listenerScheme, "listenerScheme");
    if (!trusted.isTrusted(peer)) {
      return new ClientView(peer, listenerScheme, AddressSource.PEER);
    }
    if (!headers.forwarded().isEmpty()) {
      return forwardedHops(headers.forwarded())
          .flatMap(hops -> fromHops(hops, trusted, listenerScheme, AddressSource.FORWARDED))
          .orElse(new ClientView(peer, listenerScheme, AddressSource.UNREADABLE));
    }
    if (!headers.forwardedFor().isEmpty()) {
      boolean protoUnreadable =
          !headers.forwardedProto().isEmpty() && forwardedProto(headers.forwardedProto()).isEmpty();
      String scheme = forwardedProto(headers.forwardedProto()).orElse(listenerScheme);
      Optional<ClientView> view =
          protoUnreadable
              ? Optional.empty()
              : forwardedForHops(headers.forwardedFor())
                  .map(addresses -> addresses.stream().map(each -> new Hop(each, scheme)).toList())
                  .flatMap(hops -> fromHops(hops, trusted, scheme, AddressSource.X_FORWARDED_FOR));
      return view.orElse(new ClientView(peer, listenerScheme, AddressSource.UNREADABLE));
    }
    // A trusted proxy that sends no forwarding header is making its own request, such as a health
    // check, so the proxy is the client.
    return new ClientView(peer, listenerScheme, AddressSource.PEER);
  }

  private static Optional<ClientView> fromHops(
      List<Hop> hops, TrustedProxies trusted, String defaultScheme, AddressSource source) {
    if (hops.isEmpty() || hops.size() > MAX_HOPS) {
      return Optional.empty();
    }
    for (int hopIndex = hops.size() - 1; hopIndex >= 0; hopIndex--) {
      Hop hop = hops.get(hopIndex);
      if (!trusted.isTrusted(hop.address())) {
        String scheme = hop.scheme().isEmpty() ? defaultScheme : hop.scheme();
        return Optional.of(new ClientView(hop.address(), scheme, source));
      }
    }
    // Every hop is a trusted proxy: the request started inside the trusted path.
    return Optional.of(new ClientView(hops.getFirst().address(), defaultScheme, source));
  }

  private static Optional<List<Hop>> forwardedHops(List<String> lines) {
    List<Hop> hops = new ArrayList<>();
    for (String line : lines) {
      if (line.length() > MAX_HEADER_CHARACTERS) {
        return Optional.empty();
      }
      for (String element : line.split(",", -1)) {
        Optional<Hop> hop = forwardedElement(element);
        if (hop.isEmpty()) {
          // An unreadable hop between the client and a trusted proxy breaks the chain of trust.
          return Optional.empty();
        }
        hops.add(hop.get());
      }
    }
    return Optional.of(List.copyOf(hops));
  }

  private static Optional<Hop> forwardedElement(String element) {
    // RFC 7239 Section 4: pairs separated by ";", each token=value, names case-insensitive.
    Optional<InetAddress> address = Optional.empty();
    String scheme = "";
    for (String pair : element.split(";", -1)) {
      int equalsAt = pair.indexOf('=');
      if (equalsAt <= 0) {
        return Optional.empty();
      }
      String name = pair.substring(0, equalsAt).strip().toLowerCase(Locale.ROOT);
      String value = unquote(pair.substring(equalsAt + 1).strip());
      switch (name) {
        case "for" -> address = nodeAddress(value);
        case "proto" -> {
          Optional<String> parsed = scheme(value);
          if (parsed.isEmpty()) {
            return Optional.empty();
          }
          scheme = parsed.get();
        }
        default -> {
          // "by" and "host" are allowed by RFC 7239 and not needed to find the client.
        }
      }
    }
    String hopScheme = scheme;
    return address.map(each -> new Hop(each, hopScheme));
  }

  private static Optional<List<InetAddress>> forwardedForHops(List<String> lines) {
    List<InetAddress> hops = new ArrayList<>();
    for (String line : lines) {
      if (line.length() > MAX_HEADER_CHARACTERS) {
        return Optional.empty();
      }
      for (String item : line.split(",", -1)) {
        Optional<InetAddress> address = literal(item.strip());
        if (address.isEmpty()) {
          return Optional.empty();
        }
        hops.add(address.get());
      }
    }
    return Optional.of(List.copyOf(hops));
  }

  private static Optional<String> forwardedProto(List<String> lines) {
    // Exactly one value is believed; two lines mean two writers, and neither is believed.
    return lines.size() == 1 ? scheme(lines.getFirst().strip()) : Optional.empty();
  }

  private static Optional<String> scheme(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.equals("http") || lower.equals("https") ? Optional.of(lower) : Optional.empty();
  }

  private static Optional<InetAddress> nodeAddress(String node) {
    // RFC 7239 Section 6: an IPv6 node is in brackets, and any node may carry a port after a colon.
    if (node.startsWith("[")) {
      int closeAt = node.indexOf(']');
      return closeAt < 0 ? Optional.empty() : literal(node.substring(1, closeAt));
    }
    int colonAt = node.indexOf(':');
    return literal(colonAt < 0 ? node : node.substring(0, colonAt));
  }

  private static Optional<InetAddress> literal(String text) {
    try {
      // ofLiteral parses an address and never looks a name up, so "unknown" or a host name is
      // refused instead of sending a DNS query an attacker chose.
      return Optional.of(InetAddress.ofLiteral(text));
    } catch (IllegalArgumentException notAnAddress) {
      return Optional.empty();
    }
  }

  private static String unquote(String value) {
    return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
        ? value.substring(1, value.length() - 1)
        : value;
  }

  /** One hop of a forwarding chain; an empty scheme means the hop did not say. */
  private record Hop(InetAddress address, String scheme) {}
}
