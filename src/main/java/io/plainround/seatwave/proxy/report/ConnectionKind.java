package io.plainround.seatwave.proxy.report;

/** Whether a request opened its own connection or reused one that was already open. */
public enum ConnectionKind {
  /** A new connection: the request paid for the TCP handshake and, over HTTPS, the TLS one. */
  NEW,
  /** A reused (keep-alive) connection: the handshakes were already paid. */
  REUSED
}
