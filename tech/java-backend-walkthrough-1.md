# The `backend` package, part 1: the data, the rules and the settings (line by line)

This is part 1 of 2. The `backend` package is long (thirteen files, about 1,900 lines), so it is split. Part 1 reads the small files that hold data and rules. Part 2 (`java-backend-walkthrough-2.md`) reads the files that run the server: metrics, the command line, the server itself and the routes.

## What it is

SeatWave is the ticket-booking service of the course. In this repository it is a small program written in Java that listens for web requests and answers them. The `backend` package is that program. This file reads the first nine files of the package, one line at a time:

- `Hold.java`: one held seat.
- `HoldLedger.java`: the table of held seats, with the rule that a seat never has two owners.
- `HoldKeys.java`: the table of Idempotency-Keys, which makes a retried request safe.
- `Reply.java`: what a route answers.
- `Request.java`: one request, copied into a plain value.
- `Problems.java`: the one error shape.
- `StaticFiles.java`: the script and the poster image that SeatWave serves.
- `SeatCatalog.java`: the arena, 20 sections of 1,000 seats, and the bodies that describe them.
- `BackendSettings.java`: every number the server needs, checked at start.

A few words you need first.

- A **server** is a program that waits for other programs (clients) to send it requests and sends an answer back for each one. A **request** is a message such as "give me the seats of section A". The answer is a **response**, or in this code a **reply**.
- **HTTP** is the set of rules for these messages. A request has a **method** (`GET` reads, `POST` creates), a **path** (`/v1/holds`), optional **headers** (labelled extra facts, such as `Content-Type`), and sometimes a **body** (the data you send). A response has a **status** (a number: `200` fine, `404` not found, `409` conflict), headers and a body.
- **JSON** is a plain-text way to write data: `{"seat":"A-7","state":"held"}`. Curly braces hold named fields. Square brackets hold lists.
- A **port** is a number (0 to 65,535) that picks which program on a computer receives a connection. A server **binds** (claims) a port.
- A **thread** is one line of work inside a running program. A server uses many threads so that it can handle many requests at the same time.
- **In memory** means the data lives in the running program, not in a file or a database. When the program stops, the data is gone.

## Why this repository uses it

Module 9 puts a reverse proxy in front of SeatWave and measures what the proxy saves and costs. To measure that, the backend behind it must behave in known ways:

- A seat map of exactly 36,894 bytes (`/events/1/seats?section=A`) that shrinks well under gzip compression.
- Static files with a fixed cache lifetime of 300 seconds (a script and a 300,000-byte poster that does not shrink under gzip).
- A long poll that waits 25 seconds, so that a wrong proxy timeout can be shown.
- A pool of 64 worker threads, so that a slow client can be shown to tie up a worker, and buffering in the proxy can be shown to release it.
- Forwarded headers and a trusted-proxy list, for the module's one invariant: SeatWave believes a forwarded client address only from a trusted proxy.
- Holds with idempotency keys, so that the proxy's retry behavior can be reasoned about.

A real SeatWave would use a database. This lab keeps holds in memory because the module studies what happens in front of SeatWave, not behind it.

## Where it appears

| File | What it does |
|---|---|
| `backend/Hold.java` | One seat held by one customer until a moment. |
| `backend/HoldLedger.java` | The seats that are held; keeps the rule "one owner per seat". |
| `backend/HoldKeys.java` | The Idempotency-Key table of `POST /v1/holds`. |
| `backend/Reply.java` | The two kinds of answer: a whole body, or an event stream. |
| `backend/Request.java` | One request with its client already decided. |
| `backend/Problems.java` | Builds the error bodies (RFC 9457 problem details). |
| `backend/StaticFiles.java` | The two files under `/static/`. |
| `backend/SeatCatalog.java` | The arena, the seat pages and the paging cursor. |
| `backend/BackendSettings.java` | The checked settings of the server. |

The other four files (`BackendMetrics`, `BackendCli`, `BackendServer`, `SeatWaveRoutes`) are in part 2.

## How it works underneath

**One request, end to end.** A client opens a connection to a port. The server reads the request and copies it into a `Request` value (the `Request` file). A router (part 2) looks at the method and the path and calls one small method, the **route**. The route reads or changes the data (the ledger, the keys) and returns a `Reply`. The server writes the reply back. Every number in these files serves that path.

**State.** Two tables live in memory. The ledger maps a seat label such as `A-7` to its hold. The key table maps an Idempotency-Key to the work done for it. Many threads read and write them at once, so both use classes from `java.util.concurrent`, which are built for that.

**The rule that a seat never has two owners.** Imagine two fans pressing the same seat at the same moment. Two threads run the "hold this seat" code at the same time. If each one first asks "is the seat free?" and then writes "it is mine", both can see "free" before either writes, and both win. That bug is called a **race**. The ledger avoids it with `ConcurrentHashMap.compute`, which runs the check and the write for one key as a single step that no other thread can interleave.

**Idempotent means safe to repeat.** A client that does not get a reply (a network drop) does not know if the hold happened. It sends the same request again. Without protection, the seat could be held twice for one click. The client therefore sends an **Idempotency-Key**: a label it chose for this one intended action. The server remembers, per key, the first answer. The same key with the same body gets the first answer again. The same key with a different body is refused. The same key while the first request is still running gets "wait".

**Paging with a cursor.** A section has 1,000 seats, and all sections have 20,000. A reply with all of them would be huge. The client asks for a **page** of at most 500 and receives a **cursor**: a text token that says "the page ended at seat T". It sends the cursor back to get the next page. This server writes the cursor as `s1.` plus the seat label, encoded in Base64 (a way to write bytes as safe letters and digits). A client cannot make a useful cursor of its own: anything that does not decode to `s1.` and a real seat is refused.

**One error shape.** Every error uses the same JSON object, **problem details** (RFC 9457), with `type`, `title`, `status`, `detail` and `instance`. A client reads the `type` URL and the `status`, never the wording of `detail`.

## Walkthrough of this repository's use

### `backend/Hold.java`

A **package** is a named folder of related classes; the first line says this file belongs to `io.plainround.seatwave.proxy.backend`. An **import** makes a class from another package usable by its short name.

```java
package io.plainround.seatwave.proxy.backend;

import java.time.Instant;
import java.util.Objects;
```

`Instant` is a point on the time line in UTC (`java.time`). `Objects` is a helper class with null checks.

A **Javadoc comment** starts with `/**` and ends with `*/`. Tools turn it into documentation. The lines that start with `@param` describe one field each.

```java
/**
 * One seat held by one customer until a moment.
 *
 * @param holdId the hold's identifier, a random UUID
 * @param eventId the event, Module 1's event 1
 * @param seat the seat label, for example {@code A-7}
 * @param customer the customer's identifier as the client sent it
 * @param expiresAt when the hold ends, Module 1's 300 seconds after it began
 */
```

A **record** is a short way to declare a class whose only job is to carry values. The compiler writes the constructor, the getters (`holdId()`, `eventId()`, `seat()`, `customer()` and `expiresAt()`), `equals`, `hashCode` and `toString` from the list in the brackets. The record is `public`, so any package may use it. A record is also **immutable**: once built, its fields cannot change, which is why many threads can share it safely.

```java
public record Hold(String holdId, long eventId, String seat, String customer, Instant expiresAt) {
```

A **compact constructor** (the `public Hold { ... }` form, with no brackets after the name) runs before the fields are set. It is the place to check the values. `Objects.requireNonNull(x, "name")` throws a `NullPointerException` with the name if `x` is `null`. There is no check for `eventId`: it is a `long`, a primitive number that cannot be `null`.

```java
  /** Checks that no field is missing. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seat, "seat");
    Objects.requireNonNull(customer, "customer");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
```

The method `isActiveAt` answers "does this hold still block the seat?". `now.isBefore(expiresAt)` is true until the expiry moment and false from that moment on. It takes the time as a parameter instead of reading the clock itself, so a test can pass any time it likes.

```java

  /**
   * Says whether the hold still blocks its seat.
   *
   * @param now the current time
   * @return true until the expiry moment
   */
  public boolean isActiveAt(Instant now) {
    return now.isBefore(expiresAt);
  }
}
```

### `backend/HoldLedger.java`

```java
package io.plainround.seatwave.proxy.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
```

`Clock` is the JDK's source of "now"; passing one in lets a test move time by hand. `Duration` is a length of time. `Optional<T>` is a box that holds either one value or nothing; a method that may find nothing returns an `Optional` instead of `null`, so the caller must decide what to do about "nothing". `UUID` makes a random unique identifier. `ConcurrentHashMap` is a key-value table that many threads can use at once; `ConcurrentMap` is its interface (the list of what it can do).

```java
/**
 * The seats that are held, in memory, with Module 1's rule that a seat never has two owners.
 *
 * <p>Module 1 enforced the rule with a conditional {@code UPDATE} in its database. This lab backend
 * keeps holds in memory, because Module 9 studies what happens in front of SeatWave; the rule is
 * kept by {@link ConcurrentMap#compute}, which runs one update per seat at a time, so two requests
 * for one seat cannot both win. Production code keeps the rule in the database, as Module 7 does.
 * Thread-safe.
 */
```

```java
final class HoldLedger {

  // Module 1's hold: 300 seconds.
  static final Duration HOLD_DURATION = Duration.ofSeconds(300);
```

`final class` means no other class may extend it. Its constructor and methods have no `public` word, so they are visible only inside the `backend` package (the default access): the ledger is an inside part of the server. `static final` is a constant shared by the whole class; the hold lasts 300 seconds, the figure from Module 1.

```java
  private final ConcurrentMap<String, Hold> holdsBySeat = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Hold> holdsById = new ConcurrentHashMap<>();
  private final Clock clock;
```

There are two tables of the same holds, each under a different key. `holdsBySeat` is the one that enforces the rule. `holdsById` lets `GET /v1/holds/{id}` find a hold by its identifier. `private final` fields are set once and used only inside this class.

```java
  HoldLedger(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }
```

The constructor stores the clock. `this.clock` is the field; `clock` on the right is the parameter.

```java
  /**
   * Holds a seat if nobody holds it now.
   *
   * @return the new hold, or empty if another active hold owns the seat
   */
  Optional<Hold> hold(long eventId, String seat, String customer) {
    Instant now = clock.instant();
    Hold candidate =
        new Hold(UUID.randomUUID().toString(), eventId, seat, customer, now.plus(HOLD_DURATION));
    // compute runs atomically per seat: the check and the write cannot be split by another thread.
```

`hold` reads the clock once, so every decision below uses one consistent "now". It builds a **candidate** hold first: a new random identifier, the event, the seat, the customer, and an expiry 300 seconds from now. Nothing is stored yet.

```java
    Hold owner =
        holdsBySeat.compute(
            seat,
            (label, existing) ->
                existing == null || !existing.isActiveAt(now) ? candidate : existing);
```

This is the heart of the file. `compute(seat, function)` calls the function with the seat label and the current value for that seat (`existing`, or `null` if none). The function returns the new value to store. The `->` is a **lambda**: a small unnamed function written inline. The function says: if there is no hold, or the existing hold has expired, store the candidate; otherwise keep the existing hold. `compute` runs this for one key atomically, so two threads asking for the same seat run one after the other, never together. The result `owner` is whatever is stored after the call.

```java
    if (owner != candidate) {
      return Optional.empty();
    }
    holdsById.put(candidate.holdId(), candidate);
    return Optional.of(candidate);
  }
```

`owner != candidate` compares the two objects by identity (are they the very same object?). If the stored hold is not our candidate, somebody else holds the seat, and the method returns `Optional.empty()`. Otherwise the candidate won: it is also written into `holdsById`, and returned inside an `Optional`. An expired hold stays in `holdsById` forever; a note at the end of this file lists that as a limit of this lab.

```java

  Optional<Hold> find(String holdId) {
    return Optional.ofNullable(holdsById.get(holdId));
  }

  Instant now() {
    return clock.instant();
  }

  boolean isHeld(String seat) {
    Hold hold = holdsBySeat.get(seat);
    return hold != null && hold.isActiveAt(clock.instant());
  }
}
```

`find` looks a hold up by identifier; `Optional.ofNullable` turns a missing value (`null`) into `Optional.empty()`. `now()` hands the clock's time to the routes, so they and the ledger use the same clock. `isHeld` says whether a seat is held right now: a hold exists and it is still active. The seat page uses it to write `held` or `available`.

### `backend/HoldKeys.java`

```java
package io.plainround.seatwave.proxy.backend;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
```

`AtomicInteger` is a whole number that many threads can add to safely, with no lost updates.

```java
/**
 * The Idempotency-Key table of {@code POST /v1/holds} (Module 7), kept in memory: the same key and
 * body get the first reply again; the same key with another body is refused.
 *
 * <p>Module 7 writes the key in the same database transaction as the hold. In memory, the claim
 * comes first and the reply is stored after the effect, so a second request with the key while the
 * first runs is told to wait, never given a second hold. The table is bounded: when full it refuses
 * new keys instead of growing without end. Thread-safe.
 */
```

```java
final class HoldKeys {

  /** The most keys kept; the lab never comes near it, and a flood of keys cannot fill memory. */
  static final int MAX_KEYS = 100_000;

  private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicInteger size = new AtomicInteger();
```

`MAX_KEYS` bounds the table: an attacker who sends a new key with every request cannot grow memory without end. `entries` maps the key text to its `Entry`. `size` counts the entries; it is separate because counting a `ConcurrentHashMap` is not a cheap exact operation under writes.

```java
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
```

`claim` can end in five ways. A **sealed interface** lists, in its own file, every class that may implement it. Here `Claim` has exactly five: `Owner` (you do the work), `Replay` (here is the stored reply), `InProgress` (another request has this key now), `Reused` (the key came with another body), `Full` (the table is full). Each is a record. `Owner`, `InProgress`, `Reused` and `Full` carry no data, so their bracket lists are empty and their bodies are empty (`{}`). `Replay` carries the stored `Reply.Body`. Because the list is closed, the compiler can check that a `switch` over a `Claim` handles every case.

```java

  Claim claim(String key, String fingerprint) {
```

```java
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(fingerprint, "fingerprint");
```

Both arguments must exist. The **fingerprint** is a short summary of the body (part 2 shows it is a SHA-256 digest): two bodies with the same fingerprint are the same body for this purpose.

```java
    Entry existing = entries.get(key);
    if (existing == null) {
```

Look the key up. `null` means it has never been seen.

```java
      if (size.incrementAndGet() > MAX_KEYS) {
        size.decrementAndGet();
        return new Claim.Full();
      }
      existing = entries.putIfAbsent(key, new Entry.Running(fingerprint));
```

A new key: first count it. `incrementAndGet()` adds one and returns the new total. If that total is over `MAX_KEYS`, undo the add (`decrementAndGet()`) and answer `Full`. Otherwise try `putIfAbsent(key, new Entry.Running(...))`: store a "running" entry only if the key is still absent, and return the previous value (`null` if there was none).

```java
      if (existing == null) {
        return new Claim.Owner();
      }
      // Another request claimed the key between the get and the put; this one did not.
      size.decrementAndGet();
    }
```

If `putIfAbsent` returned `null`, this thread stored the entry and now owns the key: `Owner`. If it returned something, another thread claimed the key between the `get` and the `put` (a race, the comment says so). Then this thread gives its count back and carries on as if it had found the entry at the start.

```java
    if (!existing.fingerprint().equals(fingerprint)) {
      return new Claim.Reused();
    }
```

An entry exists. If its fingerprint differs from the one just sent, the key is being reused with another body: `Reused`.

```java
    return switch (existing) {
      case Entry.Running running -> new Claim.InProgress();
      case Entry.Done done -> new Claim.Replay(done.reply());
    };
  }
```

A **switch expression** picks a value by looking at the type of `existing`. This is **pattern matching**: `case Entry.Running running ->` matches when the entry is a `Running`, and gives it the name `running` (unused here, but the syntax requires a name). A running entry means `InProgress`. A `Done` entry means `Replay` with its stored reply. There is no `default` branch, because `Entry` is sealed with only these two kinds; the compiler checks the cases are complete.

```java

  void complete(String key, String fingerprint, Reply.Body reply) {
    entries.put(key, new Entry.Done(fingerprint, Objects.requireNonNull(reply, "reply")));
  }
```

`complete` is called after the work is done. `entries.put` replaces the `Running` entry with a `Done` entry that holds the reply. `size` is not touched, because the key was already counted.

```java

  /** One key: the fingerprint of its body and, once the work is done, the reply. */
  private sealed interface Entry {
    String fingerprint();

    /** Claimed; the work has not finished. */
    record Running(String fingerprint) implements Entry {}

    /** Finished, with the reply to send again. */
    record Done(String fingerprint, Reply.Body reply) implements Entry {}
  }
}
```

`Entry` is the private, sealed interface for the two states of a key: claimed and working, or finished with a reply. Every entry knows its fingerprint. Because it is `private`, nothing outside `HoldKeys` can see it.

A limit worth knowing: if the code that holds a key throws an unexpected error before `complete` is called, the entry stays `Running` and later requests with that key get `InProgress` for as long as the process lives. The routes (part 2) run only code that does not throw for the inputs they accept, so this does not happen in the lab.

### `backend/Reply.java`

```java
package io.plainround.seatwave.proxy.backend;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
```

`ArrayList` is a growable list. `List` and `Map` are the interfaces for lists and key-value tables. `Map.Entry` is one key-value pair; here it holds a header name and its value.

```java
/**
 * What a route answers: a whole body, or an event stream that the server writes over time.
 * Immutable: every {@code with} method returns a new reply.
 */
public sealed interface Reply {
```

`sealed interface Reply` says a reply is one of the kinds listed in this file, and nothing else. The comment says it is immutable: it never changes after it is built, and the `with` method makes a new one.

```java
  /**
   * Returns the headers to send, in the order they were added.
   *
   * @return name and value pairs
   */
  List<Map.Entry<String, String>> headers();
```

Every reply has a list of headers, in the order they were added. An **interface** method with no body is a promise that each kind of reply will provide it. A record that has a component named `headers` provides it automatically.

```java
  /**
   * Returns a JSON reply.
   *
   * @param status the HTTP status
   * @param json the body
   * @return the reply, with {@code application/json}
   */
  static Body json(int status, String json) {
    return new Body(status, "application/json", json.getBytes(StandardCharsets.UTF_8), List.of());
  }
```

`static` on an interface method means you call it as `Reply.json(...)` without an object. It builds a `Body` whose type is `application/json` (the **media type**, which tells the client what the bytes are) and turns the JSON text into bytes with `getBytes(StandardCharsets.UTF_8)`. UTF-8 is the usual way to turn text into bytes. `List.of()` is an empty fixed list: no extra headers.

```java

  /**
   * A reply whose whole body is known before the first byte is sent.
   *
   * @param status the HTTP status
   * @param contentType the media type of the body
   * @param body the bytes, copied on the way in and out
   * @param headers further headers, in order
   */
  record Body(int status, String contentType, byte[] body, List<Map.Entry<String, String>> headers)
      implements Reply {
```

`Body` is the first kind: the whole body is known before the first byte is sent. It is a record with four components, and `implements Reply` (it supplies the `headers` method through its `headers` component). `byte[]` is an array of bytes.

```java
    /** Copies the body and the headers. */
    public Body {
      Objects.requireNonNull(contentType, "contentType");
      body = Objects.requireNonNull(body, "body").clone();
      headers = List.copyOf(headers);
    }
```

The compact constructor makes defensive copies. Arrays are mutable, so a caller who kept the array could change the body later, and the record would no longer be immutable. `body.clone()` copies the array, and `List.copyOf(headers)` copies the list into one that cannot change. `contentType` must not be `null`.

```java
    @Override
    public byte[] body() {
      return body.clone();
    }
```

A record's own getter would return the stored array. This replaces it with one that returns a copy, for the same reason. `@Override` tells the compiler "this replaces a method that exists", so a typing mistake in the name is an error.

```java
    /**
     * Returns the size of the body without copying it.
     *
     * @return the body's length in bytes
     */
    public int length() {
      return body.length;
    }
```

`length()` returns the number of bytes without copying them: the server needs the length for the `Content-Length` header and for the metrics.

```java
    /**
     * Adds one header.
     *
     * @param name the header name
     * @param value the header value
     * @return a new reply with the header last
     */
    public Body withHeader(String name, String value) {
      List<Map.Entry<String, String>> more = new ArrayList<>(headers);
      more.add(Map.entry(name, value));
      return new Body(status, contentType, body, more);
    }
  }
```

`withHeader` builds a new `Body` that has all the old headers plus one more. It copies the headers into a new `ArrayList`, appends `Map.entry(name, value)`, and builds a new record. It passes the field `body` (not the method `body()`), so the array is cloned once, in the constructor.

```java

  /**
   * A server-sent event stream (Module 8): a reconnect delay, then a heartbeat comment after each
   * silent interval, until the stream's lifetime ends.
   *
   * @param retry the reconnect delay sent to the browser
   * @param heartbeat the silence after which a heartbeat is sent
   * @param lifetime when the stream ends
   * @param headers further headers, in order
   */
  record EventStream(
      Duration retry,
      Duration heartbeat,
      Duration lifetime,
      List<Map.Entry<String, String>> headers)
      implements Reply {
```

`EventStream` is the second kind: a **server-sent event** stream (Module 8). The response never has a known length. The server writes a first line telling the browser how long to wait before reconnecting (`retry`), then after each silent interval it writes a small comment line (the **heartbeat**, so that no idle timer on the path decides the connection is dead), until the stream's `lifetime` is over. A `Duration` is a length of time.

```java

    /** Copies the headers. */
    public EventStream {
      Objects.requireNonNull(retry, "retry");
      Objects.requireNonNull(heartbeat, "heartbeat");
      Objects.requireNonNull(lifetime, "lifetime");
      headers = List.copyOf(headers);
    }
  }
}
```

The constructor refuses `null` durations and copies the headers.

### `backend/Request.java`

```java
package io.plainround.seatwave.proxy.backend;

import io.plainround.seatwave.proxy.forwarded.ClientView;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
```

```java
/**
 * One request, copied out of the HTTP server's own types once, so no route sees a transport type.
 *
 * @param method the method, for example {@code GET}
 * @param path the raw path, for example {@code /v1/events/1/seats}
 * @param rawQuery the raw query without the question mark, or an empty string
 * @param headers every header, names in lower case
 * @param body the body, at most {@link SeatWaveRoutes#MAX_BODY_BYTES} bytes
 * @param bodyTooLarge true if the client sent more than the limit
 * @param client what SeatWave believes about the client, decided before any route runs
 * @param correlationId the identifier of this request in log lines and problem bodies
 */
```

```java
public record Request(
    String method,
    String path,
    String rawQuery,
    Map<String, List<String>> headers,
    byte[] body,
    boolean bodyTooLarge,
    ClientView client,
    String correlationId) {
```

`Request` carries everything a route may know. It is copied once out of the JDK server's own type (`HttpExchange`), "so no route sees a transport type": the routes can then be tested without a network. `Map<String, List<String>>` is a table from a header name to the list of values it was sent with (a header may appear more than once). `ClientView` comes from the `forwarded` package: what SeatWave believes about the client, decided before any route runs. `correlationId` is the label printed on every log line of this request, so that one request can be followed.

```java
  /** Copies the headers and the body. */
  public Request {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rawQuery, "rawQuery");
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(correlationId, "correlationId");
    headers = Map.copyOf(headers);
    body = Objects.requireNonNull(body, "body").clone();
  }
```

The compact constructor checks that the text fields exist, copies the header table with `Map.copyOf` (immutable), and clones the body. `body = ...` assigns to the record's field.

```java
  @Override
  public byte[] body() {
    return body.clone();
  }
```

```java
  /**
   * Returns the only value of a header.
   *
   * @param name the header name, in any case
   * @return the value, or empty if the header is absent or sent more than once
   */
  public Optional<String> header(String name) {
    List<String> values = headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
  }
}
```

`header(name)` returns the value of a header only if it was sent exactly once. It lower-cases the name (header names are case-insensitive; the server stored them in lower case) with `Locale.ROOT`, which makes the conversion the same on every computer. `getOrDefault(key, List.of())` gives an empty list when the header is absent. `values.size() == 1 ? ... : ...` is the **conditional operator**: if the test is true use the first value, else the second. `getFirst()` is the first item of a list. A header sent twice returns "empty": two writers are not believed, which matters for `Content-Type` and `Idempotency-Key`.

### `backend/Problems.java`

```java
package io.plainround.seatwave.proxy.backend;

import io.plainround.seatwave.proxy.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
```

```java
/**
 * The one error shape of SeatWave version 1: problem details (RFC 9457), with the {@code type} URIs
 * of Module 7, so a client acts on the type and the status and never on the wording. Thread-safe:
 * the codec is.
 */
final class Problems {
```

The comment names two standards. RFC 9457 defines the problem-details JSON object. The `type` URLs sit under `seatwave.example`, a domain reserved for examples (RFC 2606), so they never point at a real website. The class is thread-safe because its only field, the JSON codec, is.

```java
  // Documentation names under the reserved .example domain (RFC 2606), as Module 7 uses them.
  private static final String TYPE_BASE = "https://seatwave.example/problems/";

  private final JsonCodec json;

  Problems(JsonCodec json) {
    this.json = Objects.requireNonNull(json, "json");
  }
```

`TYPE_BASE` is the shared start of every type URL. The constructor stores the codec used to write JSON.

```java
  Reply.Body of(int status, String slug, String title, String detail, String correlationId) {
    ProblemBody problem =
        new ProblemBody(
            TYPE_BASE + slug, title, status, detail, "urn:seatwave:request:" + correlationId);
    return new Reply.Body(
        status,
        "application/problem+json",
        json.write(problem).getBytes(StandardCharsets.UTF_8),
        List.of(Map.entry("Cache-Control", "no-store")));
  }
```

`of` builds a complete error reply. It makes a `ProblemBody` with the type URL (`TYPE_BASE + slug`), a short `title`, the numeric `status`, a `detail` sentence, and an `instance` such as `urn:seatwave:request:<id>` that names this one failed request, so a person reading a log and a client reading the body can match them. `json.write(problem)` turns the record into JSON text, which becomes bytes in UTF-8. The reply's media type is `application/problem+json`, and `Cache-Control: no-store` tells every cache never to keep an error.

```java
  /** The JSON body; record components are written in this order. */
  record ProblemBody(String type, String title, int status, String detail, String instance) {}
}
```

`ProblemBody` is a record that exists only to be written as JSON. The comment says the fields are written in this order, so the output always starts with `type`.

### `backend/StaticFiles.java`

```java
package io.plainround.seatwave.proxy.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
```

```java
/**
 * The static files SeatWave serves under {@code /static/}: the seat page's script and a poster.
 * Both are built once at start-up and never change while the process runs. Thread-safe: the map is
 * immutable.
 */
final class StaticFiles {

  /** How long a static file may be cached: 300 seconds, the module's fixed lifetime. */
  static final int MAX_AGE_SECONDS = 300;
```

A **static file** is one whose bytes do not change from request to request (unlike a seat map). The proxy can keep a copy. `MAX_AGE_SECONDS` is 300: the value the server puts in `Cache-Control: public, max-age=300`.

```java
  // A poster image is about 300 KB (Module 9, Section 7). Random bytes stand in for it: a JPEG is
  // already compressed, so, like random bytes, it does not shrink under gzip.
  private static final int POSTER_BYTES = 300_000;
  // A fixed seed, so every start serves the same poster and a cached copy stays valid.
  private static final long POSTER_SEED = 20_261_111L;

  private final Map<String, StaticFile> files;
```

The poster stands in for a 300 KB image. A real photograph (JPEG) is already compressed, so gzip cannot shrink it much; random bytes behave the same way. `SplittableRandom` is a quick random-number generator; it is given a fixed **seed** (starting number), so it writes the same bytes on every start, and a copy cached by the proxy stays valid after a restart. `files` is the table from path to file.

```java
  StaticFiles() {
    byte[] poster = new byte[POSTER_BYTES];
    new SplittableRandom(POSTER_SEED).nextBytes(poster);
    this.files =
        Map.of(
            "/static/seat-map.js",
            new StaticFile("text/javascript", resource("static/seat-map.js")),
            "/static/poster-arena.bin",
            new StaticFile("application/octet-stream", poster));
  }
```

The constructor fills `poster` with the random bytes (`nextBytes`). `Map.of(k1, v1, k2, v2)` builds a fixed table of two entries: `/static/seat-map.js` is read from the classpath, and `/static/poster-arena.bin` is the random bytes.

```java
  Optional<StaticFile> find(String path) {
    return Optional.ofNullable(files.get(path));
  }
```

```java
  private static byte[] resource(String name) {
    try (InputStream in = StaticFiles.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("the resource " + name + " is not on the classpath");
      }
      return in.readAllBytes();
    } catch (IOException unreadable) {
      throw new UncheckedIOException("cannot read the resource " + name, unreadable);
    }
  }
```

`resource` reads a file that was packed into the program's jar (the **classpath**, the places Java looks for classes and resources). The `try (InputStream in = ...)` form is **try-with-resources**: it closes the stream on every path. `getResourceAsStream` returns `null` when the file is missing, which is a build error, so it throws `IllegalStateException`. `readAllBytes()` reads to the end. An `IOException` (a read failure) is wrapped in `UncheckedIOException`, an unchecked kind, because this is start-up code that cannot recover.

```java
  /** One file: its media type and its bytes. */
  record StaticFile(String contentType, byte[] bytes) {
    StaticFile {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
```

`StaticFile` pairs the media type with the bytes and copies the array in and out, like `Reply.Body`. The constructor here is a compact constructor with no `public` and no `Objects` check; the array is never `null` in this code.

### `backend/SeatCatalog.java`

```java
package io.plainround.seatwave.proxy.backend;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

```java
/**
 * Module 1's arena: one event, 20 sections (A to T) of 1,000 seats, and the bodies that describe
 * them, in version 0's and version 1's shapes. Thread-safe: it reads the ledger, which is.
 */
final class SeatCatalog {

  /** Module 1's one event. */
  static final long EVENT_ID = 1;
```

The class describes Module 1's arena: one event, 20 sections `A` to `T`, 1,000 seats each. It produces two shapes of body: version 0 (Module 1's old API, being retired) and version 1 (Module 7's paged API).

```java
  // Module 1 seeded 20 sections of 1,000 seats, labelled A-1 to T-1000.
  static final int SEATS_PER_SECTION = 1_000;
  static final String SECTIONS = "ABCDEFGHIJKLMNOPQRST";
  // Module 1 printed this start time in its sample output; Module 7 writes it in ISO-8601.
  static final Instant EVENT_STARTS_AT = Instant.ofEpochMilli(1_796_761_251_783L);
```

`SECTIONS` is the string of the 20 letters. `EVENT_STARTS_AT` is a point in time written as milliseconds since 1 January 1970 UTC (1,796,761,251,783 ms is 2026-12-08T20:20:51.783Z, which the test checks).

```java
  private static final Pattern SECTION = Pattern.compile("[A-T]");
  // A seat label: a section letter, a hyphen and a number from 1 to 1000.
  private static final Pattern SEAT = Pattern.compile("([A-T])-([1-9][0-9]{0,3})");
  private static final String CURSOR_PREFIX = "s1.";
  private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();
```

A **regular expression** (`Pattern`) is a compact description of allowed text. `[A-T]` is one letter from A to T. `([A-T])-([1-9][0-9]{0,3})` is a letter, a hyphen, then a number with no leading zero and one to four digits; the brackets `( )` make **groups** the code reads back later. The number can reach 9999 here, so `isSeat` also checks the upper limit of 1000. `CURSOR_PREFIX` is `s1.`: a version mark in front of the cursor. The Base64 encoder uses the URL-safe alphabet (`-` and `_` instead of `+` and `/`) with no `=` padding, so a cursor can sit in a URL unchanged.

```java
  private final HoldLedger ledger;

  SeatCatalog(HoldLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }
```

```java
  static boolean isSection(String text) {
    return SECTION.matcher(text).matches();
  }

  static boolean isSeat(String label) {
    Matcher matcher = SEAT.matcher(label);
    return matcher.matches() && Integer.parseInt(matcher.group(2)) <= SEATS_PER_SECTION;
  }
```

`isSection` and `isSeat` are validators. `matcher(text).matches()` is true only if the whole text fits the pattern. For a seat, the number is read with `Integer.parseInt(matcher.group(2))`, the second group, and must be at most 1000.

```java
  /**
   * Returns version 0's seat map of one section, byte for byte Module 1's shape: 36,894 bytes when
   * every seat is available.
   */
  byte[] version0SeatMap(String section) {
```

```java
    List<String> labels = new ArrayList<>(SEATS_PER_SECTION);
    for (int seatNumber = 1; seatNumber <= SEATS_PER_SECTION; seatNumber++) {
      labels.add(section + "-" + seatNumber);
    }
    // Module 1's database returned seats ordered by their text label: A-1, A-10, A-100, A-1000.
    Collections.sort(labels);
```

Version 0 returns one section as a JSON list. The code makes the 1,000 labels `A-1` to `A-1000`, then `Collections.sort(labels)` sorts them as **text**, so `A-1` is followed by `A-10`, `A-100`, `A-1000`, `A-101`, and not by `A-2`. That is the order Module 1's database returned, and the 36,894 bytes depend on it only through the count; the test checks the start of the text.

```java
    StringBuilder json = new StringBuilder("[");
    for (String label : labels) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("{\"seat\":\"")
          .append(label)
          .append("\",\"state\":\"")
          .append(state(label))
          .append("\"}");
    }
    return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
  }
```

A `StringBuilder` builds text without making a new string for each piece. The list starts with `[`. For every label after the first (`json.length() > 1` means something was already added) a comma is added. Then it appends `{"seat":"A-1","state":"available"}` piece by piece; the `\"` is a quote character inside a Java string. `state(label)` says `held` or `available`. The last piece is `]`, and the whole text becomes UTF-8 bytes.

```java
  /**
   * Returns one page of version 1's seats, in Module 7's order: section, then seat number.
   *
   * @param section one section, or empty for all
   * @param limit 1 to 500 seats
   * @param after the last seat of the previous page, or empty for the first page
   */
  Page page(Optional<String> section, int limit, Optional<SeatPosition> after) {
```

```java
    List<SeatItem> items = new ArrayList<>(limit);
    int startSection = after.map(SeatPosition::sectionIndex).orElse(0);
    int startNumber = after.map(position -> position.number() + 1).orElse(1);
    SeatPosition last = new SeatPosition(0, 0);
    boolean hasMore = false;
```

Version 1 returns one **page**. `section` may be empty (all sections). `after` is where the previous page stopped. `startSection` and `startNumber` are the place to begin: section index 0 and number 1 for a first page; otherwise the section index of the cursor and the number after it (`position.number() + 1`). `last` remembers the last seat added, which is where the next cursor will point. `hasMore` becomes true if more seats exist beyond this page.

```java
    for (int sectionIndex = startSection; sectionIndex < SECTIONS.length(); sectionIndex++) {
      String letter = String.valueOf(SECTIONS.charAt(sectionIndex));
      if (section.isPresent() && !section.get().equals(letter)) {
        continue;
      }
      int firstNumber = sectionIndex == startSection ? startNumber : 1;
```

The outer loop walks the sections from the start section to the last. `SECTIONS.charAt(i)` is the letter. If the caller asked for one section and this is another, `continue` skips it. The first section starts at `startNumber`; every later section starts at 1.

```java
      for (int number = firstNumber; number <= SEATS_PER_SECTION; number++) {
        if (items.size() == limit) {
          hasMore = true;
          break;
        }
        String label = letter + "-" + number;
        items.add(new SeatItem(label, letter, state(label)));
        last = new SeatPosition(sectionIndex, number);
      }
```

The inner loop walks the seat numbers. Before adding a seat it checks whether the page is already full (`items.size() == limit`). If it is, there is at least one more seat, so `hasMore` is true and the loop stops. Otherwise the seat is added with its label, section letter and state, and `last` is updated.

```java
      if (hasMore) {
        break;
      }
    }
    return new Page(items, hasMore ? encode(last) : "");
  }
```

Once `hasMore` is set, the outer loop stops too. The page carries a cursor only if more seats exist, so the last page has `nextCursor` empty.

```java
  static Optional<SeatPosition> decode(String cursor) {
    try {
      String text = new String(CURSOR_DECODER.decode(cursor), StandardCharsets.UTF_8);
      if (!text.startsWith(CURSOR_PREFIX)) {
        return Optional.empty();
      }
      String label = text.substring(CURSOR_PREFIX.length());
      if (!isSeat(label)) {
        return Optional.empty();
      }
      Matcher matcher = SEAT.matcher(label);
      matcher.matches();
      return Optional.of(
          new SeatPosition(SECTIONS.indexOf(matcher.group(1)), Integer.parseInt(matcher.group(2))));
    } catch (IllegalArgumentException notBase64) {
      // A cursor the client changed is bad input, not a server failure.
      return Optional.empty();
    }
  }
```

`decode` turns a cursor back into a position, or `Optional.empty()` for any cursor this server did not write. The steps: Base64-decode, make text, check the `s1.` prefix, cut the prefix off, check the rest is a real seat label, then read the letter and number (`SECTIONS.indexOf(letter)` gives the section index). `matcher.matches();` is called only to fill the groups; its result is known to be true because `isSeat` just passed. The `catch` handles `IllegalArgumentException`, which the Base64 decoder throws for text that is not Base64: a changed cursor is bad input from a client, not a server failure, so the answer is "empty".

```java
  private static String encode(SeatPosition position) {
    String label = SECTIONS.charAt(position.sectionIndex()) + "-" + position.number();
    return CURSOR_ENCODER.encodeToString((CURSOR_PREFIX + label).getBytes(StandardCharsets.UTF_8));
  }

  private String state(String label) {
    return ledger.isHeld(label) ? "held" : "available";
  }
```

`encode` is the reverse: build `s1.` plus the label, take its UTF-8 bytes, and Base64-encode them. `state` asks the ledger whether the seat is held.

```java
  /** A seat's place in the order: its section's index (A is 0) and its number. */
  record SeatPosition(int sectionIndex, int number) {}

  /** One seat as version 1 writes it. */
  record SeatItem(String seat, String section, String state) {}

  /** One page and the cursor of the next, which is empty on the last page. */
  record Page(List<SeatItem> items, String nextCursor) {
    Page {
      items = List.copyOf(items);
      Objects.requireNonNull(nextCursor, "nextCursor");
    }

    boolean isLast() {
      return nextCursor.isEmpty();
    }
  }
}
```

Three small records. `SeatPosition` is a place in the order. `SeatItem` is one seat as version 1 writes it. `Page` holds the items and the cursor of the next page; its constructor copies the list, and `isLast()` is true when the cursor is empty.

### `backend/BackendSettings.java`

Every number the server needs comes from a settings file (a `.properties` file, see `java-properties.md`), and there are no defaults in the code. A missing key is an error that is reported, never a silent fallback.

```java
package io.plainround.seatwave.proxy.backend;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import io.plainround.seatwave.proxy.forwarded.TrustedProxies;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
```

`Result` comes from the result4j library (see `result4j.md`). A `Result<S, E>` is either a success holding an `S` or an error holding an `E`, so a method can report an expected failure as a value without throwing an exception.

```java
/**
 * Everything the SeatWave backend needs, read from a settings file with no defaults.
 *
 * @param bindAddress the address both listeners bind to: 0.0.0.0 inside a container, so the proxy
 *     can reach it on the container network, or 127.0.0.1 on a reader's own machine
 * @param plainPort the plain HTTP listener, the one the proxy talks to
 * @param tlsPort the HTTPS listener: SIMPLE, SeatWave answering browsers with its own TLS
 * @param adminPort the operations listener for {@code /metrics} and {@code /health}, on its own
 *     thread, so the numbers can still be read while every worker is busy
 * @param tlsKeystore the PKCS12 file with the server's TEST key and certificate chain
 * @param tlsPasswordFile the file that holds the keystore's password
 * @param backlog connections the kernel may hold for each listener before SeatWave accepts them
 * @param workerThreads the platform threads that run requests, Module 4's 64
 * @param queueCapacity requests that may wait for a worker; one more is refused
 * @param trustedProxies the peers whose forwarding headers are believed
 * @param timing how long the slow routes take
 */
```

The Javadoc explains each setting. Three ports exist: plain HTTP (the proxy talks to this one), HTTPS (clients that come straight in), and an **admin** port for `/metrics` and `/health`, served by its own thread so that the numbers can be read while every worker is busy. The **backlog** is how many connections the operating system may hold waiting for the server to accept them. The **queue capacity** is how many requests may wait for a free worker.

```java
public record BackendSettings(
    InetAddress bindAddress,
    int plainPort,
    int tlsPort,
    int adminPort,
    Path tlsKeystore,
    Path tlsPasswordFile,
    int backlog,
    int workerThreads,
    int queueCapacity,
    TrustedProxies trustedProxies,
    Timing timing) {
```

The record has twelve components. `InetAddress` is an IP address; `Path` is a file path; `TrustedProxies` is the set of peers whose forwarded headers are believed (the `forwarded` package); `Timing` is a nested record defined at the end.

```java
  // An IP address literal; a host name would need DNS before the server could start.
  private static final Pattern ADDRESS = Pattern.compile("[0-9.]{7,15}|[0-9a-fA-F:]{2,39}");
  private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_./-]{1,200}");
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_WORKERS = 1_024;
  // Linux caps a listen backlog at net.core.somaxconn, 4,096 in the lab's containers.
  private static final int MAX_BACKLOG = 65_535;
  private static final int MAX_QUEUE = 100_000;
  private static final int MAX_WAIT_MILLIS = 600_000;
```

Constants. `ADDRESS` allows either an IPv4 shape (digits and dots, 7 to 15 characters) or an IPv6 shape (hex digits and colons). The comment says why: a host name would need a DNS lookup before the server could start. `FILE` allows letters, digits, `_`, `.`, `/` and `-` up to 200 characters: no spaces and no other symbols. The others are the highest port (65,535) and the highest allowed values for workers (1,024), backlog (65,535, the comment notes the operating system's own cap), queue (100,000) and wait times (600,000 ms). Note that `FILE` limits the length of a keystore path to 200 characters; a long absolute path breaks start-up with a clear message (see "Mistakes people make").

```java
  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if the two listeners share a port
   */
  public BackendSettings {
    Objects.requireNonNull(bindAddress, "bindAddress");
    Objects.requireNonNull(tlsKeystore, "tlsKeystore");
    Objects.requireNonNull(tlsPasswordFile, "tlsPasswordFile");
    Objects.requireNonNull(trustedProxies, "trustedProxies");
    Objects.requireNonNull(timing, "timing");
    if (sharePort(plainPort, tlsPort, adminPort)) {
      throw new IllegalArgumentException("the three listeners need three different ports");
    }
  }
```

The compact constructor refuses `null` for the five object fields and then calls `sharePort`. If two listeners use the same port, the constructor throws `IllegalArgumentException`, so no settings value with a clash can exist. The settings file path reports the same problem more gently (see `from` below).

```java
  /**
   * Reads the settings, reporting every missing or invalid key and every broken rule between keys
   * together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<BackendSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
```

`from` is the checked way to build settings. It takes a `SettingsReader` (see `java-config-json-tls-walkthrough.md`) and returns either the settings or a list of text lines, one per problem. The idea is that a person who made three mistakes in a file sees all three in one run.

```java
    String bind = reader.textMatching("http.bindAddress", ADDRESS);
    int plainPort = reader.intBetween("http.plainPort", 0, HIGHEST_PORT);
    int tlsPort = reader.intBetween("http.tlsPort", 0, HIGHEST_PORT);
    int adminPort = reader.intBetween("http.adminPort", 0, HIGHEST_PORT);
    String keystore = reader.textMatching("tls.keystore", FILE);
    String passwordFile = reader.textMatching("tls.passwordFile", FILE);
    int backlog = reader.intBetween("http.backlog", 1, MAX_BACKLOG);
    int workers = reader.intBetween("http.workerThreads", 1, MAX_WORKERS);
    int queue = reader.intBetween("http.queueCapacity", 1, MAX_QUEUE);
    List<String> trusted = reader.listMatching("forwarded.trustedProxies", ADDRESS);
    Duration longPoll = reader.millisBetween("feed.longPollHoldMillis", 1, MAX_WAIT_MILLIS);
    Duration heartbeat = reader.millisBetween("feed.heartbeatMillis", 1, MAX_WAIT_MILLIS);
    Duration lifetime = reader.millisBetween("feed.streamLifetimeMillis", 1, MAX_WAIT_MILLIS);
```

Each line reads one key with its rule. `reader.intBetween("http.plainPort", 0, 65535)` reads a whole number from 0 to 65,535 (0 means "any free port"). `textMatching` checks a text against a pattern. `listMatching` reads a comma-separated list. `millisBetween` reads a number of milliseconds and returns a `Duration`. If a key is missing or wrong, the reader records a problem and returns a harmless stand-in value, so the code can keep going and find more problems.

```java
    if (sharePort(plainPort, tlsPort, adminPort)) {
      reader.addProblem(
          "http.plainPort, http.tlsPort and http.adminPort must differ (0 means any free port)");
    }
    if (lifetime.compareTo(heartbeat) <= 0) {
      reader.addProblem(
          "feed.streamLifetimeMillis must be longer than feed.heartbeatMillis, or a stream ends"
              + " before its first heartbeat");
    }
```

Two rules between keys, checked after the individual keys: the three ports must differ (unless they are 0), and the stream lifetime must be longer than the heartbeat, otherwise the stream would end before its first heartbeat. `reader.addProblem` adds a line to the same list.

```java
    Optional<InetAddress> address = bind.isEmpty() ? Optional.empty() : literal(reader, bind);
    Optional<TrustedProxies> proxies = Optional.empty();
    if (!trusted.isEmpty()) {
      switch (TrustedProxies.parse(trusted)) {
        case Result.Success<TrustedProxies, String>(TrustedProxies parsed) ->
            proxies = Optional.of(parsed);
        case Result.Error<TrustedProxies, String>(String reason) ->
            reader.addProblem("forwarded.trustedProxies: " + reason);
      }
    }
    if (!reader.problems().isEmpty() || address.isEmpty() || proxies.isEmpty()) {
```

The bind address is turned into an `InetAddress` by the helper `literal` (below), which records a problem if the text is not an IP address. The trusted proxies are parsed only if the list is not empty. `TrustedProxies.parse` returns a `Result`, and the `switch` has two cases: `Result.Success<TrustedProxies, String>(TrustedProxies parsed)` matches a success and takes its value out (this is a **record pattern**: the switch looks inside the record), and `Result.Error<...>(String reason)` matches an error. On success `proxies` is set; on error the reason is added as a problem, with the key name in front.

```java
      return Result.error(reader.problems());
    }
    return Result.success(
        new BackendSettings(
            address.get(),
            plainPort,
            tlsPort,
            adminPort,
            Path.of(keystore),
            Path.of(passwordFile),
            backlog,
            workers,
            queue,
            proxies.get(),
            new Timing(longPoll, heartbeat, lifetime)));
  }
```

If any problem exists, or the address or the proxies could not be made, the method returns `Result.error(reader.problems())`. Otherwise it builds the settings: `Path.of(text)` makes a path, and `new Timing(...)` groups the three durations. `address.get()` and `proxies.get()` are safe here because the line above checked they are present.

```java
  private static boolean sharePort(int plainPort, int tlsPort, int adminPort) {
    // 0 asks the system for any free port, so two zeros never collide.
    return (plainPort != 0 && (plainPort == tlsPort || plainPort == adminPort))
        || (tlsPort != 0 && tlsPort == adminPort);
  }
```

`sharePort` says whether two listeners clash. Zero is excluded: "0 asks the system for any free port, so two zeros never collide." The expression reads: plain clashes with tls or admin, or tls clashes with admin.

```java
  private static Optional<InetAddress> literal(SettingsReader reader, String text) {
    try {
      return Optional.of(InetAddress.ofLiteral(text));
    } catch (IllegalArgumentException notAnAddress) {
      reader.addProblem("http.bindAddress must be an IP address, but was " + text);
      return Optional.empty();
    }
  }
```

`InetAddress.ofLiteral(text)` parses an IP address and never looks up a name, so there is no network traffic. If the text is not an address it throws `IllegalArgumentException`; the code records the problem and returns an empty `Optional`.

```java
  /**
   * How long the slow routes take.
   *
   * @param longPollHold how long a long poll waits when nothing changes, Module 8's 25 seconds
   * @param heartbeat how long an event stream stays silent before a heartbeat, Module 8's 15 s
   * @param streamLifetime when the lab's event stream ends by itself, so no lab leaves it open
   */
  public record Timing(Duration longPollHold, Duration heartbeat, Duration streamLifetime) {

    /** Checks that no duration is negative. */
    public Timing {
      for (Duration duration : List.of(longPollHold, heartbeat, streamLifetime)) {
        if (Objects.requireNonNull(duration, "duration").isNegative()) {
          throw new IllegalArgumentException("a duration cannot be negative: " + duration);
        }
      }
    }
  }
}
```

`Timing` is a nested record: how long the long poll waits, how long the event stream stays silent before a heartbeat, and when the stream ends. Its constructor checks every duration with a `for` loop over `List.of(...)`: none may be `null` or negative. `Duration.isNegative()` is true below zero.

## Try it yourself

Every command below was run in a copy of this repository. Timings differ on every run. Docker is not needed.

**1. Start SeatWave on this machine and ask it for its health.**

```
./gradlew testCertificates
./gradlew -q serve --args='config/backend-local.properties'
```

The first command writes the TEST certificates into `build/tls`. The second printed:

```
SeatWave plain HTTP on 127.0.0.1:20931
SeatWave HTTPS on 127.0.0.1:20930 (TLSv1.3)
SeatWave operations (metrics, health) on 127.0.0.1:20932
64 worker threads, trusted proxies [127.0.0.1]
```

In another terminal:

```
curl -s -i http://127.0.0.1:20931/health
```

```
HTTP/1.1 200 OK
Date: Wed, 11 Nov 2026 05:42:15 GMT
Content-type: application/json
Server-timing: app;dur=4.73
Content-length: 15
Cache-control: no-store

{"status":"up"}
```

**2. Read a page of seats and follow the cursor.**

```
curl -s 'http://127.0.0.1:20931/v1/events/1/seats?section=B&limit=2'
```

```
{"items":[{"seat":"B-1","section":"B","state":"available"},{"seat":"B-2","section":"B","state":"available"}],"nextCursor":"czEuQi0y"}
```

`czEuQi0y` is Base64 for `s1.B-2`. Send it back as `&cursor=czEuQi0y` and the next page starts at `B-3`.

**3. Hold a seat, repeat the request, then change the body.**

```
curl -s -i -X POST http://127.0.0.1:20931/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "demo-1"' -d '{"eventId":1,"seat":"A-7","customer":"fan-1"}'
```

The first call printed `HTTP/1.1 201 Created`, a `Location: /v1/holds/<uuid>` header and a body like `{"holdId":"...","eventId":1,"seat":"A-7","state":"held","expiresAt":"..."}`. The same command again printed `201` with the same holdId (the stored reply). The same key with `"seat":"A-8"` printed a 422 problem whose `type` ends in `idempotency-key-reused`. A different key (`demo-2`) and customer for `A-7` printed a 409 problem whose `type` ends in `seat-unavailable`.

**4. Read the counters.**

```
curl -s http://127.0.0.1:20932/metrics | grep -v '^#'
```

After the calls above it printed lines such as `seatwave_requests_total{route="POST /v1/holds",status="201"} 2` and `seatwave_workers 64`. Stop the server with Ctrl-C in its terminal; it prints `SeatWave stopped`.

**5. Run the unit tests of this part.**

```
./gradlew test --tests '*HoldLedgerTest' --tests '*HoldKeysTest' --tests '*SeatCatalogTest' --tests '*BackendSettingsTest'
```

They pass. `tests-backend-walkthrough.md` explains them.

## Mistakes people make

- **Check then write as two steps.** "If the seat is free, hold it" as a read followed by a write lets two threads both win. `HoldLedger` uses `compute`, and `HoldKeys` uses `putIfAbsent`, each as one step.
- **Mutable values shared between threads.** An array in a record can be changed by whoever kept it. `Reply.Body`, `Request` and `StaticFile` copy their arrays in and out.
- **Believing a header sent twice.** `Request.header` returns nothing if the header appeared more than once.
- **Unbounded tables.** A table keyed by something the client chooses must have a limit. `HoldKeys.MAX_KEYS` is 100,000. The ledger is bounded only by the 20,000 seat labels, but its second table (`holdsById`) keeps expired holds for the life of the process; that is fine for a lab and wrong for production.
- **Storing the error with the key.** This server stores every final answer for an Idempotency-Key, including a 400 or a 409. A client that fixes its body must use a new key, or it receives 422 "key reused".
- **A long file path in the settings.** `FILE` allows 200 characters. A keystore path longer than that is refused at start-up. This makes `BackendCliTest.startsAndPrintsItsListenersFromAValidFile` fail when the repository is cloned into a deep folder (see `tests-backend-walkthrough.md`).

## Check yourself

1. Why does `HoldLedger.hold` build a candidate hold before it touches the table? Answer: so the work of making the identifier and the expiry is done outside `compute`, and the function inside `compute` only decides which hold to keep. It is also how the method later tells who won: `owner != candidate`.
2. What would go wrong with `if (!holdsBySeat.containsKey(seat)) holdsBySeat.put(seat, candidate)`? Answer: two threads can both see no key and both put; the second `put` replaces the first, and both callers believe they hold the seat.
3. What do the five `Claim` cases mean, and which one does a retry of a finished request get? Answer: `Owner` (do the work), `Replay` (stored reply), `InProgress` (another request has the key), `Reused` (key with another body), `Full` (table full). A retry of a finished request with the same body gets `Replay`.
4. Why is the seat map sorted as text? Answer: Module 1's database returned seats in text order, and version 0 must stay byte-compatible: `A-1`, `A-10`, `A-100`, `A-1000`, `A-101`.
5. What does a client learn from the cursor `czEuQi0y`, and can it make its own? Answer: it is Base64 for `s1.B-2`, meaning "after seat B-2". A made-up cursor is accepted only if it decodes to `s1.` and a real seat; anything else gets 400.

## Further reading

- Java SE 25 API, `java.util.concurrent.ConcurrentHashMap` (`compute`, `putIfAbsent`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html
- Java Language Specification 25, records, sealed classes, switch patterns: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- RFC 9111, HTTP Caching (the `Cache-Control` values used here): https://www.rfc-editor.org/rfc/rfc9111
- RFC 4648, Base64 and the URL-safe alphabet: https://www.rfc-editor.org/rfc/rfc4648
