package io.plainround.seatwave.proxy.forwarded;

import com.github.sviperll.result4j.Result;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The addresses whose forwarded headers SeatWave believes: the reverse proxy and, in the sidecar
 * lab, the sidecar on the loopback address. Every other peer's forwarded headers are ignored.
 *
 * <p>The list holds addresses, never names, so deciding whether to trust a peer needs no DNS lookup
 * that an attacker could influence. Immutable and thread-safe.
 *
 * @param addresses the trusted peer addresses, at least one
 */
public record TrustedProxies(Set<InetAddress> addresses) {

  /**
   * Creates the list.
   *
   * @throws IllegalArgumentException if the list is empty
   */
  public TrustedProxies {
    addresses = Set.copyOf(addresses);
    if (addresses.isEmpty()) {
      throw new IllegalArgumentException("at least one trusted proxy address is needed");
    }
  }

  /**
   * Parses IP address literals.
   *
   * @param literals for example {@code 10.209.9.10} and {@code 127.0.0.1}
   * @return the list, or why a literal is not an IP address
   */
  public static Result<TrustedProxies, String> parse(List<String> literals) {
    Objects.requireNonNull(literals, "literals");
    Set<InetAddress> parsed = new LinkedHashSet<>();
    for (String literal : literals) {
      // The same strict rule as the forwarded hops: never a name, never DNS, and no short IPv4
      // form that would trust another address than the one written.
      Optional<InetAddress> address = ClientAddress.literal(literal);
      if (address.isEmpty()) {
        return Result.error("a trusted proxy must be an IP address, but was '" + literal + "'");
      }
      parsed.add(address.get());
    }
    if (parsed.isEmpty()) {
      return Result.error("at least one trusted proxy address is needed");
    }
    return Result.success(new TrustedProxies(parsed));
  }

  /**
   * Says whether forwarded headers from this peer are believed.
   *
   * @param peer the address of the connection's other end
   * @return true if the peer is on the list
   */
  public boolean isTrusted(InetAddress peer) {
    return addresses.contains(Objects.requireNonNull(peer, "peer"));
  }
}
