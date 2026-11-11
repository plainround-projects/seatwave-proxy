# The `forwarded` package, line by line: who is the client behind a proxy?

## What it is

The `forwarded` package (five files: `AddressSource.java`, `ClientView.java`, `ForwardedHeaders.java`, `TrustedProxies.java`, `ClientAddress.java`) answers one question for every request: **what is the address of the client, and did it use http or https?**

That is harder than it sounds once a proxy sits in front of the server. A **reverse proxy** receives the client's connection and opens a second connection to the server. The server then sees the proxy's address as the other end of its connection (the **peer**), not the client's. To tell the server who the client really was, the proxy adds a **forwarding header** to the request it passes on. Three are in use:

- `X-Forwarded-For`: a comma-separated list of client addresses, oldest hop first. Example: `203.0.113.7, 10.209.9.10`.
- `X-Forwarded-Proto`: `http` or `https`, how the client reached the proxy.
- `Forwarded`: the standard form of both (RFC 7239). Example: `for=203.0.113.7;proto=https`, or with quotes, as this repository's proxy writes it, `for="203.0.113.7";proto=https`. IPv6 addresses must be written in square brackets and quotes: `for="[2001:db8:cafe::17]:4711"`.

The trap: **any client can write these headers itself.** If SeatWave believed `X-Forwarded-For` from everybody, a client could say it is any address it likes, and defeat limits, logs and blocks that depend on the client address. This package is the rule that prevents that.

A few more terms. An **IP address** is a number that names a machine on a network: `203.0.113.7` (IPv4, four numbers) or `2001:db8::1` (IPv6, eight groups). A **hop** is one machine a request passed through. A **literal** is an address written as digits, as opposed to a **name** such as `fan.seatwave.example`, which needs a DNS lookup to turn into digits.

## Why this repository uses it

This is "half of the module's one invariant" (the Javadoc's words): **SeatWave believes a forwarded client address only when the connection comes from a trusted proxy.** The module's labs prove it from both sides. A request sent straight to SeatWave with a forged `X-Forwarded-For` is ignored and counted. The same header through the proxy is believed, because the proxy overwrites whatever the client sent with the real address.

The server (`BackendServer.request`, in `java-backend-walkthrough-2.md`) calls `ClientAddress.resolve` exactly once per request, before any route runs. A route receives only the finished `ClientView`; it cannot read a forwarded header on its own. That is the second half of the invariant.

## Where it appears

| File | What it does |
|---|---|
| `forwarded/AddressSource.java` | The four ways the address was learned. |
| `forwarded/ClientView.java` | The answer: address, scheme, source. |
| `forwarded/ForwardedHeaders.java` | The three headers exactly as received. |
| `forwarded/TrustedProxies.java` | The set of peers whose headers are believed. |
| `forwarded/ClientAddress.java` | The rule itself. |

Used by `backend/BackendServer.java` (calls `resolve`), `backend/BackendSettings.java` (builds the trusted set from `forwarded.trustedProxies`), `backend/Request.java` (carries the view) and `backend/SeatWaveRoutes.java` (`GET /lab/client` shows it).

## How it works underneath

The rule, in order:

1. **Is the peer a trusted proxy?** If not, ignore every forwarding header. The client is the peer, the source is `PEER`, and the scheme is the one of the listener (`http` for the plain port, `https` for the TLS port).
2. If the peer is trusted and sent a `Forwarded` header, read that and ignore the other two.
3. Otherwise, if it sent `X-Forwarded-For`, read it, with `X-Forwarded-Proto` for the scheme.
4. If it sent none, the proxy is making its own request (a health check, say). The proxy itself is the client.
5. A trusted proxy whose headers cannot be read gets the source `UNREADABLE`: the answer is the peer itself. Failing safe means not guessing.

**Reading the hops right to left.** The header lists the chain of machines the request passed through. Each proxy appends the address of the machine it received the request from. So the right end is the most recent, and only the right end was written by a machine we trust. The code walks from the right and skips hops that are themselves trusted proxies. The first hop that is not a trusted proxy is the client. The left-most entry is never taken on trust, because a client can write anything there and a proxy that appends keeps it.

Example with trusted `10.209.9.10` and `127.0.0.1`, peer `127.0.0.1`, header `192.0.2.66, 203.0.113.7, 10.209.9.10`: the right-most `10.209.9.10` is trusted, skip it. `203.0.113.7` is not trusted: that is the client. The left-most `192.0.2.66` (written by the client) is ignored.

**Limits.** At most 16 hops (`MAX_HOPS`) and at most 1,024 characters per header line (`MAX_HEADER_CHARACTERS`). A longer chain is not a real path; it is an attack, so it is `UNREADABLE`.

**No name lookups, no guessing.** Every address is parsed as a literal with `InetAddress.ofLiteral`, which never asks DNS. A name in a header is refused instead of causing a lookup that an attacker chose. The text must also be an address in its exact form: IPv4 as four decimal numbers joined by dots (a **dotted quad**, such as `203.0.113.7`), and IPv6 in brackets inside `Forwarded`. Anything that could be read as two different addresses is refused, never guessed.

## Walkthrough of this repository's use

### `forwarded/AddressSource.java`

An **enum** is a type with a fixed list of named values.

```java
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
```

`PEER`: the connection's other end, the request did not come from a trusted proxy (or the proxy sent no header). `FORWARDED`: the `Forwarded` header. `X_FORWARDED_FOR`: the other header. `UNREADABLE`: a trusted proxy sent something unreadable and SeatWave fell back to the peer. A `/**` comment before each value is Javadoc for that value. The lab route prints the name in lower case (`x_forwarded_for`).

### `forwarded/ClientView.java`

```java
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
```

`ClientView` is the answer: the client's address (`InetAddress`), the scheme as the client used it, and the source.

```java
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
```

The compact constructor checks that nothing is `null` and that the scheme is exactly `http` or `https`. `!scheme.equals("http") && !scheme.equals("https")` is true for anything else, and the constructor throws `IllegalArgumentException` with a message that includes the bad value. A `ClientView` with a scheme such as `ftp` can therefore never exist.

### `forwarded/ForwardedHeaders.java`

```java
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
```

This record holds the three headers "exactly as received, every line of each, before anything is believed". A header can be sent on several lines, so each is a `List<String>`. The adapter in `BackendServer` fills it, so the resolver never sees the JDK server's own types and is easy to test.

```java

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
```

The compact constructor copies the three lists with `List.copyOf` (immutable copies). `none()` returns a request that carries no forwarding headers; `BackendServer` compares with it (`forwarding.equals(ForwardedHeaders.none())`, which a record supports by comparing its fields) to see whether a request carried any.

### `forwarded/TrustedProxies.java`

```java
package io.plainround.seatwave.proxy.forwarded;

import com.github.sviperll.result4j.Result;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
```

```java
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
```

The set of addresses whose headers are believed: the reverse proxy and, in the sidecar lab, the sidecar on the loopback address. The list holds addresses, never names, "so deciding whether to trust a peer needs no DNS lookup that an attacker could influence". A `Set` is a collection with no repeats.

```java
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
```

The constructor copies the set (`Set.copyOf`) and refuses an empty one: a trusted list with nobody on it would silently mean "trust no one", which is a configuration mistake.

```java
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
```

`parse` turns the text from the settings file into the record. It loops over the items and asks `ClientAddress.literal(literal)` (below) for each: an `Optional` that is empty for a name, any other text, or a short IPv4 form. An empty one is turned into an error value (`Result.error`, see `result4j.md`) that quotes the bad item. The comment gives the reason for using the same rule as the headers: `127.0.1` in the settings file must be refused, not read as `127.0.0.1`, or a typo would trust an address nobody wrote. `address.get()` takes the value out once `isEmpty()` has said there is one. `LinkedHashSet` keeps the order of insertion. An empty list is an error too. The returned type is `Result<TrustedProxies, String>`: either the record or a reason.

```java
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
```

`isTrusted` is a set lookup. `InetAddress.equals` compares the address bytes, so `10.209.9.10` written two ways is still one address.

### `forwarded/ClientAddress.java`

```java
package io.plainround.seatwave.proxy.forwarded;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
```

```java
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
```

The class comment is the module's invariant in words. The class is `final` with a private constructor: it is only a home for static methods, and it holds no state, so it is thread-safe.

```java

  /** The most hops read from one request; a longer chain is not a real path, it is an attack. */
  public static final int MAX_HOPS = 16;

  /** The longest header line read; RFC 7239 values for 16 hops fit easily. */
  public static final int MAX_HEADER_CHARACTERS = 1_024;

  // An IPv4 address as RFC 7239 and NGINX write it: four decimal parts. InetAddress.ofLiteral also
  // takes the short forms of old BSD systems, so "127.1" or "2001" would quietly become another
  // address (127.0.0.1, 0.0.7.209) instead of being refused.
  private static final Pattern DOTTED_QUAD = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

  private ClientAddress() {}
```

The two limits, public so the tests can use them. `DOTTED_QUAD` is the shape an IPv4 address must have: `[0-9]{1,3}` is one to three digits, and `(\\.[0-9]{1,3}){3}` is a dot and one to three digits, three times. The comment says why the shape is checked before `ofLiteral`: the JDK also accepts the **short forms** of old BSD systems, where `127.1` means `127.0.0.1` and a single number such as `2001` means the address with that value (`0.0.7.209`). Nothing in this lab writes such a form, so one in a header is a mistake or a trick, and is refused.

```java
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
```

`resolve` takes the peer, the headers, the trusted set and the listener's scheme. The four `requireNonNull` lines refuse `null`. The first real decision: `if (!trusted.isTrusted(peer))` return a `ClientView` with the peer, the listener scheme and the source `PEER`. This is the line that makes a forged header useless.

```java
    if (!headers.forwarded().isEmpty()) {
      return forwardedHops(headers.forwarded())
          .flatMap(hops -> fromHops(hops, trusted, listenerScheme, AddressSource.FORWARDED))
          .orElse(new ClientView(peer, listenerScheme, AddressSource.UNREADABLE));
    }
```

The peer is trusted. If a `Forwarded` header was sent, `forwardedHops(...)` parses its lines into a list of hops, or nothing if any part is unreadable. `.flatMap(hops -> fromHops(...))` then picks the client from the hops. Both steps return an `Optional`; `flatMap` runs the second only if the first produced a value, and passes along an empty result. `.orElse(new ClientView(peer, listenerScheme, UNREADABLE))` is the fail-safe: if anything failed, answer with the peer and say so. When `Forwarded` is present, `X-Forwarded-For` and `X-Forwarded-Proto` are not looked at at all.

```java
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
```

The `X-Forwarded-For` branch. `protoUnreadable` is true when an `X-Forwarded-Proto` header was sent but `forwardedProto` could not read it (it wants exactly one valid value). The scheme is that value, or the listener scheme if no proto header was sent. If the proto is unreadable the whole result is empty, so the request is `UNREADABLE`: a header we cannot read is not half-believed. Otherwise `forwardedForHops` parses the addresses, each address becomes a `Hop` with the same scheme (`.map(each -> new Hop(each, scheme))`, with `.toList()` collecting them), and `fromHops` picks the client.

```java
    // A trusted proxy that sends no forwarding header is making its own request, such as a health
    // check, so the proxy is the client.
    return new ClientView(peer, listenerScheme, AddressSource.PEER);
  }
```

A trusted proxy that sends no forwarding header is making its own request, so the proxy is the client, with the source `PEER`.

```java
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
    // Every hop is a trusted proxy: the request started inside the trusted path, at the first hop,
    // and the scheme is the one that hop was reached with, when the header says it.
    Hop first = hops.getFirst();
    String scheme = first.scheme().isEmpty() ? defaultScheme : first.scheme();
    return Optional.of(new ClientView(first.address(), scheme, source));
  }
```

`fromHops` implements "right to left, skip trusted". An empty chain or one longer than 16 hops gives nothing. The `for` loop starts at the last index and counts down. For each hop: if it is not a trusted proxy, it is the client; the scheme is the hop's own if it said one, else the default; return at once. If the loop ends, every hop was a trusted proxy: the request started inside the trusted path, and the first hop is taken, with that hop's own scheme if it said one, else the default. The first version always used `defaultScheme` here, which for the `Forwarded` header is the listener's scheme: `Forwarded: for=10.209.9.10;proto=https` from a trusted proxy to the plain port was read as `http`, while the same chain in `X-Forwarded-For` plus `X-Forwarded-Proto: https` was read as `https`. Now both give `https`, and `ClientAddressTest.takesTheFirstHopsProtoWhenEveryForwardedHopIsATrustedProxy` checks it.

```java
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
```

`forwardedHops` parses the `Forwarded` header lines into hops. A line over 1,024 characters makes the whole header unreadable. Each line is cut at commas (`split(",", -1)`; the `-1` keeps empty pieces, so a trailing comma is an error rather than ignored), and each piece is parsed by `forwardedElement`. One bad element makes everything unreadable: "an unreadable hop between the client and a trusted proxy breaks the chain of trust". The result is an immutable list.

```java
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
```

`forwardedElement` parses one element such as `for=203.0.113.7;proto=https;by=10.209.9.10`. It splits at `;` into pairs. A pair must contain `=` with something before it (`equalsAt <= 0` is refused, which catches `for` with no value and `=x`). The name is trimmed and lower-cased (names are case-insensitive), and the value is trimmed and unquoted. A `switch` on the name: `for` sets the address with `nodeAddress`; `proto` must be `http` or `https` (anything else makes the element unreadable); `by` and `host` are allowed by the standard and not needed, so they are accepted and ignored. The element is returned as a `Hop` only if an address was found. An element with no `for` (for example `proto=https` alone) gives nothing, so it is unreadable. The copy `hopScheme` exists because a lambda may use only variables that never change after they are set.

```java
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
```

`forwardedForHops` does the same for `X-Forwarded-For`: line limit, split at commas, strip spaces, each item must be an address literal.

```java
  private static Optional<String> forwardedProto(List<String> lines) {
    // Exactly one value is believed; two lines mean two writers, and neither is believed.
    return lines.size() == 1 ? scheme(lines.getFirst().strip()) : Optional.empty();
  }

  private static Optional<String> scheme(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.equals("http") || lower.equals("https") ? Optional.of(lower) : Optional.empty();
  }
```

`forwardedProto` believes exactly one line: "two lines mean two writers, and neither is believed". `scheme` lower-cases and accepts only `http` or `https`.

```java
  private static Optional<InetAddress> nodeAddress(String node) {
    // RFC 7239 Section 6: an IPv6 node is in brackets, and any node may carry a port after a colon.
    if (node.startsWith("[")) {
      int closeAt = node.indexOf(']');
      if (closeAt < 0 || !node.substring(1, closeAt).contains(":")) {
        return Optional.empty();
      }
      String rest = node.substring(closeAt + 1);
      return rest.isEmpty() || rest.startsWith(":")
          ? literal(node.substring(1, closeAt))
          : Optional.empty();
    }
    int colonAt = node.indexOf(':');
    if (colonAt != node.lastIndexOf(':')) {
      // An IPv6 address without its brackets: the address and a port cannot be told apart, so the
      // node is refused instead of being cut at its first colon.
      return Optional.empty();
    }
    String address = colonAt < 0 ? node : node.substring(0, colonAt);
    return address.contains(":") ? Optional.empty() : literal(address);
  }
```

`nodeAddress` reads the value of `for` (RFC 7239 calls it a **node**: an address, maybe followed by `:` and a port).

- A node in square brackets must be an IPv6 address. What is between `[` and `]` must contain a colon (`[1.2.3.4]` is refused), and after `]` there may be nothing or a `:` and a port; anything else is refused. A missing `]` gives nothing.
- A node without brackets may contain at most one colon, the one before a port. `colonAt != node.lastIndexOf(':')` means there are two or more, so this is an IPv6 address without its brackets (or a broken node such as `203.0.113.7:4711:80`), and the node is refused. The text before the one colon, or the whole node, must then be an IPv4 address.

The first version cut every unbracketed node at its first colon. An IPv6 address written without brackets, `for=2001:db8::1`, became `2001`, which `ofLiteral` read as the IPv4 address `0.0.7.209`: a wrong client with the source `FORWARDED`, instead of `UNREADABLE`. The proxy's own header made it possible, because it wrote `for=$remote_addr` without quotes or brackets; it now writes `for="$forwarded_node"`, with brackets for IPv6 (`nginx-config-walkthrough-1.md`). Both ends are fixed, so either fix alone would stop the wrong address.

```java
  /**
   * Parses an IP address literal strictly: IPv4 only as four decimal parts, IPv6 as RFC 4291 writes
   * it, and never a name.
   *
   * @param text the literal
   * @return the address, or empty if the text is not one
   */
  static Optional<InetAddress> literal(String text) {
    if (!text.contains(":") && !DOTTED_QUAD.matcher(text).matches()) {
      return Optional.empty();
    }
    try {
      // ofLiteral parses an address and never looks a name up, so "unknown" or a host name is
      // refused instead of sending a DNS query an attacker chose.
      return Optional.of(InetAddress.ofLiteral(text));
    } catch (IllegalArgumentException notAnAddress) {
      return Optional.empty();
    }
  }
```

`literal` is the strict rule for one address, used for the headers and, through `TrustedProxies.parse`, for the settings file; that is why it lost its `private` and is visible to the whole package. Text without a colon must be a dotted quad, or the answer is empty at once. Then `InetAddress.ofLiteral` parses it; it "never looks a name up", so `unknown` or a host name gives `IllegalArgumentException`, caught and turned into an empty `Optional`. An IPv6 address is written as RFC 4291 describes (the standard for IPv6 addresses), with colons, and needs no extra check.

```java
  private static String unquote(String value) {
    return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
        ? value.substring(1, value.length() - 1)
        : value;
  }

  /** One hop of a forwarding chain; an empty scheme means the hop did not say. */
  private record Hop(InetAddress address, String scheme) {}
}
```

`unquote` removes one pair of double quotes around a value of two or more characters. `Hop` is a private record: an address and a scheme, where an empty scheme means the hop did not say.

## Try it yourself

Start SeatWave on this machine as in `java-backend-walkthrough-1.md` (it trusts `127.0.0.1`, which is where curl comes from). Every output below was printed by a real run; timings are not involved.

**1. A trusted peer: the header is believed.**

```
curl -s -H 'X-Forwarded-For: 203.0.113.7' http://127.0.0.1:20931/lab/client; echo
```

```
{"address":"203.0.113.7","scheme":"http","source":"x_forwarded_for"}
```

**2. Add the proto header.**

```
curl -s -H 'X-Forwarded-For: 127.0.0.1' -H 'X-Forwarded-Proto: https' http://127.0.0.1:20931/lab/client; echo
```

```
{"address":"127.0.0.1","scheme":"https","source":"x_forwarded_for"}
```

**3. `Forwarded` wins over `X-Forwarded-For`.**

```
curl -s -H 'Forwarded: for=203.0.113.7;proto=https' -H 'X-Forwarded-For: 198.51.100.1' http://127.0.0.1:20931/lab/client; echo
```

```
{"address":"203.0.113.7","scheme":"https","source":"forwarded"}
```

**4. The quirk of "every hop trusted" with `Forwarded`.**

```
curl -s -H 'Forwarded: for=127.0.0.1;proto=https' http://127.0.0.1:20931/lab/client; echo
```

```
{"address":"127.0.0.1","scheme":"https","source":"forwarded"}
```

The scheme is the `https` the header said. (Version 1.0.0 printed `http` here, the quirk described above.)

**5. An address that would have to be guessed.**

```
curl -s -H 'Forwarded: for=2001:db8::1' http://127.0.0.1:20931/lab/client; echo
curl -s -H 'X-Forwarded-For: 127.1' http://127.0.0.1:20931/lab/client; echo
curl -s -H 'Forwarded: for="[2001:db8::17]:4711";proto=https' http://127.0.0.1:20931/lab/client; echo
```

```
{"address":"127.0.0.1","scheme":"http","source":"unreadable"}
{"address":"127.0.0.1","scheme":"http","source":"unreadable"}
{"address":"2001:db8:0:0:0:0:0:17","scheme":"https","source":"forwarded"}
```

The IPv6 address without brackets and the short IPv4 form are both refused: the client is the trusted peer itself, with the source `unreadable`, and SeatWave's `seatwave_forwarding_headers_unreadable_total` counter grew by two. The bracketed address is read, and Java writes it in its long form.

**6. Run the unit tests.**

```
./gradlew test --tests '*ClientAddressTest' --tests '*ForwardedInvariantTest'
```

They pass. To see an untrusted peer ignored, change `forwarded.trustedProxies` in a copy of `config/backend-local.properties` to `192.0.2.1`, start the server with it, and repeat command 1: it prints `{"address":"127.0.0.1","scheme":"http","source":"peer"}`. That variant was covered by the test `ignoresAForgedXForwardedForSentStraightToSeatWave`; it was not run by hand here.

## Mistakes people make

- **Believing the left-most address.** A client can write it. Read from the right and stop at the first hop that is not yours.
- **Believing a header from anyone.** The trusted-peer check comes first, in the first lines of `resolve`.
- **Resolving names.** A name in a header would make the server query DNS for an address the attacker chose. Only literals are parsed.
- **Guessing on bad input.** An unreadable header from a trusted proxy is a configuration error to fix. The code answers with the proxy's own address, labels it `UNREADABLE`, and the server counts it.
- **Unlimited chains.** The 16-hop and 1,024-character limits bound the work an attacker can cause.
- **Two writers.** A scheme header sent on two lines is not believed.
- **IPv6 without brackets.** Write `for="[2001:db8::1]"`. Without brackets a reader cannot tell the address from a port; this code refuses such a node, and the first version read a different address (see above).
- **Accepting every form a parser accepts.** `InetAddress.ofLiteral` takes `127.1` as `127.0.0.1`. A security rule checks the exact shape first, so a form nobody meant is refused.

## Check yourself

1. A client connects straight to SeatWave and sends `X-Forwarded-For: 203.0.113.7`. What does `resolve` return? Answer: the client's real peer address, the listener's scheme, and the source `PEER`. The header is ignored and counted by the server.
2. With trusted `10.209.9.10` and header `192.0.2.66, 203.0.113.7, 10.209.9.10` from a trusted peer, who is the client? Answer: `203.0.113.7`. The right-most hop is trusted and skipped; the next one is not trusted, so it is the client.
3. What does `UNREADABLE` mean, and what address does the request get? Answer: a trusted proxy sent a forwarding header that could not be read (not an address, too long, more than 16 hops, an invalid proto). The request gets the proxy's own address.
4. Why does `parse` refuse the name `proxy.seatwave.example`? Answer: deciding trust must not need DNS, which an attacker could influence; only IP literals are accepted.
5. Which header wins if both `Forwarded` and `X-Forwarded-For` are sent by a trusted proxy? Answer: `Forwarded`; the other two headers are not read.

## Further reading

- RFC 7239, Forwarded HTTP Extension: https://www.rfc-editor.org/rfc/rfc7239
- Java SE 25 API, `java.net.InetAddress.ofLiteral`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html
- Java SE 25 API, `java.util.Optional` (`flatMap`, `orElse`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html
