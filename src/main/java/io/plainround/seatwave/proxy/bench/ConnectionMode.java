package io.plainround.seatwave.proxy.bench;

/** How a load run uses connections. */
public enum ConnectionMode {
  /** Every client keeps one connection open and sends request after request on it. */
  REUSE,
  /**
   * Every request opens a new connection and closes it: with HTTPS each request pays a full TLS
   * handshake, which is how the run counts handshakes per second.
   */
  NEW
}
