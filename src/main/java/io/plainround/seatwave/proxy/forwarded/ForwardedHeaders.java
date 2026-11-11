package io.plainround.seatwave.proxy.forwarded;

import java.util.List;

/**
 * The forwarding headers of one request exactly as received, every line of each, before anything is
 * believed. An adapter copies them out of the HTTP server's own type once, so the resolver never
 * sees a transport type.
 *
 * @param forwarded the lines of {@code Forwarded} (RFC 7239)
 * @param forwardedFor the lines of {@code X-Forwarded-For}
 * @param forwardedProto the lines of {@code X-Forwarded-Proto}
 */
public record ForwardedHeaders(
    List<String> forwarded, List<String> forwardedFor, List<String> forwardedProto) {

  /** Copies the three lists. */
  public ForwardedHeaders {
    forwarded = List.copyOf(forwarded);
    forwardedFor = List.copyOf(forwardedFor);
    forwardedProto = List.copyOf(forwardedProto);
  }

  /**
   * Returns a request that carries no forwarding headers.
   *
   * @return the empty headers
   */
  public static ForwardedHeaders none() {
    return new ForwardedHeaders(List.of(), List.of(), List.of());
  }
}
