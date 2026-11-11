package io.plainround.seatwave.proxy.backend;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Idempotency-Key table of {@code POST /v1/holds} (Module 7), kept in memory: the same key and
 * body get the first reply again; the same key with another body is refused.
 *
 * <p>Module 7 writes the key in the same database transaction as the hold. In memory, the claim
 * comes first and the reply is stored after the effect, so a second request with the key while the
 * first runs is told to wait, never given a second hold. The table is bounded: when full it refuses
 * new keys instead of growing without end. Thread-safe.
 */
final class HoldKeys {

  /** The most keys kept; the lab never comes near it, and a flood of keys cannot fill memory. */
  static final int MAX_KEYS = 100_000;

  private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicInteger size = new AtomicInteger();

  /** The outcome of claiming a key. */
  sealed interface Claim {
    /** The caller owns the key and must do the work, then call {@link #complete}. */
    record Owner() implements Claim {}

    /** The key finished before with this body: send the stored reply again. */
    record Replay(Reply.Body reply) implements Claim {}

    /** The key is being worked on by another request right now. */
    record InProgress() implements Claim {}

    /** The key was used with another body. */
    record Reused() implements Claim {}

    /** The table is full. */
    record Full() implements Claim {}
  }

  Claim claim(String key, String fingerprint) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Entry existing = entries.get(key);
    if (existing == null) {
      if (size.incrementAndGet() > MAX_KEYS) {
        size.decrementAndGet();
        return new Claim.Full();
      }
      existing = entries.putIfAbsent(key, new Entry.Running(fingerprint));
      if (existing == null) {
        return new Claim.Owner();
      }
      // Another request claimed the key between the get and the put; this one did not.
      size.decrementAndGet();
    }
    if (!existing.fingerprint().equals(fingerprint)) {
      return new Claim.Reused();
    }
    return switch (existing) {
      case Entry.Running running -> new Claim.InProgress();
      case Entry.Done done -> new Claim.Replay(done.reply());
    };
  }

  void complete(String key, String fingerprint, Reply.Body reply) {
    entries.put(key, new Entry.Done(fingerprint, Objects.requireNonNull(reply, "reply")));
  }

  /** One key: the fingerprint of its body and, once the work is done, the reply. */
  private sealed interface Entry {
    String fingerprint();

    /** Claimed; the work has not finished. */
    record Running(String fingerprint) implements Entry {}

    /** Finished, with the reply to send again. */
    record Done(String fingerprint, Reply.Body reply) implements Entry {}
  }
}
