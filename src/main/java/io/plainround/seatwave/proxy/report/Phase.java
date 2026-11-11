package io.plainround.seatwave.proxy.report;

/** The five phases of one request, in the order they happen (Module 5, Section 6). */
public enum Phase {
  /** Turning the host name into an IP address (Module 6). */
  DNS("dns"),
  /** The TCP three-way handshake: one round trip. */
  CONNECT("connect"),
  /** The TLS handshake: one round trip for TLS 1.3, two for TLS 1.2; zero for plain HTTP. */
  TLS("tls"),
  /** From the end of the handshakes to the first byte of the response: one round trip + server. */
  FIRST_BYTE("first byte"),
  /** From the first byte of the response to the last. */
  TRANSFER("transfer");

  private final String label;

  Phase(String label) {
    this.label = label;
  }

  /**
   * Returns the name a report prints.
   *
   * @return for example {@code first byte}
   */
  public String label() {
    return label;
  }
}
