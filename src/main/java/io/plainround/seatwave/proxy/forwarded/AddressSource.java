package io.plainround.seatwave.proxy.forwarded;

/** Where SeatWave learned a request's client address. */
public enum AddressSource {
  /** The connection's other end: the request did not come from a trusted proxy. */
  PEER,
  /** The {@code Forwarded} header (RFC 7239) set by a trusted proxy. */
  FORWARDED,
  /** The {@code X-Forwarded-For} header set by a trusted proxy. */
  X_FORWARDED_FOR,
  /**
   * A trusted proxy sent a forwarded header SeatWave could not read, so it fell back to the peer
   * rather than guess: a configuration error to fix, never a client to believe.
   */
  UNREADABLE
}
