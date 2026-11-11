package io.plainround.seatwave.proxy.forwarded;

import java.net.InetAddress;
import java.util.Objects;

/**
 * What SeatWave believes about the client of one request.
 *
 * @param address the client's address: the original client when a trusted proxy said so, the
 *     connection's peer otherwise
 * @param scheme {@code http} or {@code https}, as the client used it
 * @param source where the address came from
 */
public record ClientView(InetAddress address, String scheme, AddressSource source) {

  /**
   * Creates the view.
   *
   * @throws IllegalArgumentException if the scheme is not http or https
   */
  public ClientView {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(scheme, "scheme");
    Objects.requireNonNull(source, "source");
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IllegalArgumentException("the scheme must be http or https, but was " + scheme);
    }
  }
}
