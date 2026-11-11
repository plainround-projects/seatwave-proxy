# Java 25 (the language and the standard library)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`. A program called the **compiler** (`javac`) turns them into **bytecode**, a compact instruction format stored in `.class` files. A second program, the **Java Virtual Machine** (JVM), reads the bytecode and runs it. The JVM is the same on a Mac, a Windows PC and a Linux server, so the same bytecode runs on all of them.

Java is **statically typed**. Every variable, parameter and return value has a type, such as `int` (a whole number) or `String` (text). The compiler checks the types before the program runs. A mistake such as passing text where a number is expected stops the build instead of crashing later.

The **JDK** (Java Development Kit) is the download that has the compiler, the JVM and the **standard library**: ready-made classes such as `String`, `List`, `Duration` and `HttpClient`. This repository uses **JDK 25**, a long-term-support release. The machine used for these notes had Eclipse Temurin 25.0.2, and `java -version` printed `openjdk version "25.0.2" 2026-01-20 LTS`.

## Why this repository uses it

The repository is the hands-on project of Module 9 of a system design course. It builds a small ticket-booking backend (SeatWave) that runs on the JDK's own web server, and the tools that measure a reverse proxy in front of it: a load generator, an outage probe, slow clients, an access-log reader and a timing report. Java 25 gives what that work needs, with four small libraries and no framework:

- **A web server, an HTTP client and TLS in the JDK** (`com.sun.net.httpserver`, `java.net.http`, `javax.net.ssl`).
- **Threads and the concurrency tools** (`ThreadPoolExecutor`, atomic counters, `ConcurrentHashMap`) for a fixed pool of 64 workers, which is what the lab measures.
- **Records, enums and sealed interfaces**: small immutable data types in a few lines, and `switch` statements that the compiler proves complete.
- **A standard library that covers the rest**, so the project has four runtime libraries only: result4j, Jackson, SLF4J and Logback (`result4j.md`, `jackson.md`, `logging-slf4j-logback.md`).

## Where it appears

| Path | What it does with Java |
|---|---|
| `src/main/java/io/plainround/seatwave/proxy/backend/` | 13 files: SeatWave itself. See `java-backend-walkthrough.md`. |
| `.../forwarded/` | 5 files: the client-address rule. See `java-forwarded-walkthrough.md`. |
| `.../config/`, `.../json/`, `.../tls/` | 2, 1 and 1 files: the settings reader, the JSON codec and the TLS files. See `java-config-json-tls-walkthrough.md`. |
| `.../bench/` | 11 files: the load generator. See `java-bench-walkthrough.md`. |
| `.../slow/` | 4 files: the slow clients. See `java-slow-clients-walkthrough.md`. |
| `.../probe/` | 4 files: the outage probe. See `java-probe-walkthrough.md`. |
| `.../report/` and `.../logs/` | 10 and 3 files: Module 5's report and the access-log reader. See `java-report-logs-walkthrough.md`. |
| `src/test/java/io/plainround/seatwave/proxy/` | The tests, also Java (`tests-backend-walkthrough.md`, `tests-forwarded-config-tls-walkthrough.md`, `tests-tools-walkthrough.md`, `tests-stack-walkthrough.md`). |
| `build.gradle.kts` | Tells Gradle to compile with Java 25 and to treat every warning as an error (`gradle.md`). |

In all there are 54 source files under `src/main/java` (the counts above add up to 13 + 5 + 2 + 1 + 1 + 11 + 4 + 4 + 10 + 3 = 54). I ran `./gradlew -q compileJava` (it printed nothing, which means success) and found 93 class files under `build/classes/java/main/`. That is more than 54 because a nested class, a record or an enum inside a file gets its own class file.

## How it works underneath

**From text to a running program.** You write `Hold.java`. The compiler reads it and writes `Hold.class`. Gradle does this for you. To start a program, a JVM process starts, loads the class you name, and calls its `main` method. Each command of this repository has its own `main` (`BackendCli`, `BenchCli`, `ProbeCli`, `SlowClientsCli`, `LogStatsCli`, `ReportCli`), and each has a Gradle task (`serve`, `bench`, `probe`, `slowClients`, `logStats`, `report`).

**A process** is one running copy of a program, with its own memory. The JVM is one process. When `main` returns and no other thread is running, the process ends with **exit status** 0, which means success. A program may call `System.exit(n)` to end at once with another number. By convention a non-zero status means failure. This repository uses 2 for "the command was used incorrectly" (`EXIT_BAD_USAGE`) and 1 for a run that failed.

**Memory.** Two areas matter. The **stack** holds the method calls in progress with their local variables: each call adds a frame and each return removes one. Every thread has its own stack. The **heap** holds objects: `new Hold(...)` makes an object on the heap. A variable holds a **reference**, like an address that points to the object. The JVM frees objects that no variable can reach any more. This is **garbage collection**. You never free memory by hand.

**The JIT compiler.** The JVM first interprets bytecode. It counts how often each method runs, and the **JIT compiler** (just-in-time compiler) turns the busy methods into native machine code while the program runs. A method is slow in its first runs and fast after. That is why the bench makes a warm-up run and throws it away (`config/bench.properties`, `run.warmupMillis=5000`).

**Threads.** A **thread** is one line of execution inside a process. A program may have several, and they run at the same time on different processor cores. A **blocking** call, such as reading from a socket, makes its thread wait until the data arrives. A **platform thread** is one that the operating system schedules; each costs memory and there are only so many. A **virtual thread** (Java 21) is a cheap thread managed by the JDK. This repository uses **platform threads on purpose** for SeatWave's 64 workers: a worker that is writing to a slow client is a worker no other request can use, which is the very cost that proxy buffering removes. With virtual threads the same slow client would cost memory and not a scarce thread (the class comment of `BackendServer` says so).

**The class path** is the list of places where the JVM looks for compiled classes and libraries (jar files, which are zip files of classes). Gradle builds the right class path for each task.

**Compile flags.** `build.gradle.kts` sets `options.release = 25` (compile for Java 25 and use only its library) and `-Xlint:all -Werror` (turn on every warning, and make any warning fail the build). So a warning cannot stay.

## Walkthrough of this repository's use

### The basic words

**Class.** A class is a blueprint for objects. It has **fields** (the data) and **methods** (the code). Everything in Java lives in a class. Records, enums and interfaces are special kinds of class.

**Package and import.** A **package** is a named group of classes, written with dots: `io.plainround.seatwave.proxy.forwarded`. The name matches the folder path under `src/main/java`. The first line of a file is `package ...;`. After it come the **imports**: each `import java.util.Objects;` lets the file write `Objects` instead of the full name `java.util.Objects`. Classes of the same package need no import.

```java
package io.plainround.seatwave.proxy.forwarded;

import java.net.InetAddress;
import java.util.Objects;

```

**Visibility and `static`, `final`.** `public` means any class may use it. `private` means only this class. A member with none of them is **package-private**: only classes of the same package may use it. `static` means it belongs to the class, not to an object: you call it as `Reply.json(...)`, with no object. `final` on a field or variable means it can be assigned once. `final` on a class means no one can extend it. A `static final` field is a **constant**.

```java
public final class BackendServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(BackendServer.class);
  // 0 lets stop() return at once; a lab server has no request worth waiting for at shutdown.
  private static final int STOP_DELAY_SECONDS = 0;
  private static final long SHUTDOWN_WAIT_SECONDS = 5;
  // sendResponseHeaders takes 0 for "length unknown", which makes the JDK server send chunks.
  private static final long CHUNKED = 0;
  private static final int STREAM_OK = 200;
  private static final int ADMIN_OK = 200;
  private static final int ADMIN_NOT_FOUND = 404;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  // NGINX's $request_id: 32 hexadecimal characters. Believed only from a trusted proxy.
  private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-f]{32}");
  private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
```

- `public final class BackendServer implements AutoCloseable` is a public class that nobody can extend. `implements AutoCloseable` promises a `close()` method, which lets a caller use the class in a try-with-resources block (see below).
- `private static final Logger LOG = ...` is a constant that only this class sees (`logging-slf4j-logback.md`).
- `private static final int STOP_DELAY_SECONDS = 0;` names a number so the code never has a bare `0` whose meaning must be guessed. The comment above it gives the reason.
- Numbers can contain underscores for readability: `1_000_000.0` is one million. `long` is a whole number of 64 bits, `int` one of 32 bits, `double` a number with a fraction. `5` is an `int`; `5L` would be a `long`.
- `Pattern.compile("[0-9a-f]{32}")` builds a **regular expression**, a pattern for text: here, exactly 32 characters from 0 to 9 and a to f. It is made once, in a constant, because compiling a pattern costs time.
- `": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)` turns text into bytes. `\n` is a line feed. `byte[]` is an **array**, a fixed-size row of values. Naming the character set, `UTF_8`, makes the result the same on every machine.

**Constructors, `this` and `new`.** A **constructor** has the class name and no return type. It runs when you write `new`. `this` is the object itself, as in `this.metrics = ...`. The constructor of `BackendServer` is `private`, so the only way to make one is the `public static BackendServer start(...)` method beside it (lines 134 to 137 of the file).

**Comments and Javadoc.** `//` starts a comment to the end of the line. `/** ... */` is a **Javadoc comment**: a documentation comment placed before a class or a method. Tags inside it begin with `@`: `@param` describes a parameter, `@return` the result, `@throws` an exception. Tools turn them into web pages. Here they are the main place a reader finds out what a class is for.

### The features this code uses

#### 1. Records

A **record** is a class for plain data. You list the components once, and Java writes the constructor, the accessors (methods named like the components), `equals`, `hashCode` and `toString`. A record is immutable: its fields cannot change after it is made.

```java
public record Hold(String holdId, long eventId, String seat, String customer, Instant expiresAt) {

  /** Checks that no field is missing. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seat, "seat");
    Objects.requireNonNull(customer, "customer");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

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

- `public record Hold(String holdId, long eventId, String seat, String customer, Instant expiresAt)` declares five components. I ran `javap -p` on the compiled class: it is `public final class ... Hold extends java.lang.Record` with five `private final` fields, a constructor with the five parameters, the accessors `holdId()`, `eventId()`, `seat()`, `customer()` and `expiresAt()`, and `toString`, `hashCode` and `equals`.
- `public Hold { ... }` with no parameter list is the **compact constructor**: code that runs first, with the parameters in scope, before the fields are assigned. Here it checks that nothing is `null`. `Objects.requireNonNull(x, "x")` throws a `NullPointerException` with the name `x` if `x` is `null`.
- `isActiveAt` is an ordinary method added to the record. `now.isBefore(expiresAt)` compares two `Instant` values.

A compact constructor can also refuse a value or fix it up:

```java
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
```

`throw new IllegalArgumentException(...)` stops the construction with an exception, so a `ClientView` with the scheme `gopher` can never exist.

#### 2. Enums

An **enum** is a type with a fixed list of values.

```java
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

Each name (`PEER`, `FORWARDED`, `X_FORWARDED_FOR`, `UNREADABLE`) is the only instance of its kind. Compare enum values with `==` or `switch`; they are never created with `new`. An enum can have fields and a constructor too: `Phase` in the `report` package carries a text label for each value (`Phase(String label)`).

#### 3. Sealed interfaces

An **interface** names behavior without code. A **sealed** interface lists every type that may implement it. Then the compiler knows all the cases.

```java
public sealed interface CurlProblem {

  /**
   * Says what went wrong, for the report's list of refused lines.
   *
   * @return one line of text
   */
  String describe();

  /**
   * The line is not in the timing script's format.
   *
   * @param line the line, cut to its first 80 characters
   * @param reason what is missing or invalid
   */
  record Malformed(String line, String reason) implements CurlProblem {
    @Override
    public String describe() {
      return "malformed line (" + reason + "): " + line;
    }
  }

  /**
   * The line is well formed, but its numbers cannot be a timing.
   *
   * @param target the target's name
   * @param reason which rule the line breaks
   */
  record Rejected(String target, String reason) implements CurlProblem {
    @Override
    public String describe() {
      return "rejected run of " + target + ": " + reason;
    }
  }
}
```

- `public sealed interface CurlProblem` allows only the types declared inside it (a sealed interface may omit `permits` when the implementations sit in the same file). `String describe();` is an abstract method: every implementation must provide it.
- `record Malformed(String line, String reason) implements CurlProblem` is a record nested in the interface, and it implements the interface. `@Override` says "this method replaces one from the type I implement"; the compiler refuses it if there is none.
- I read the class file of `Reply`, the sealed interface of the backend: `javap -v` lists `PermittedSubclasses: Reply$Body, Reply$EventStream`. That list is what makes a `switch` over a `Reply` complete.

#### 4. `switch`: expressions, strings and patterns

An old `switch` was a statement with `case X:` and `break`. The modern forms use an arrow `->`, need no `break`, and can **produce a value**.

```java
    return switch (path) {
      case "/health" -> new Routed("GET /health", noQuery(request, this::health));
      case "/lab/client" -> new Routed("GET /lab/client", noQuery(request, this::clientView));
      case "/v1/events" -> new Routed("GET /v1/events", noQuery(request, this::events));
      case "/events/1/seats" -> new Routed("GET /events/1/seats", version0SeatMap(request));
      default -> routeByPattern(request);
    };
```

This is a `switch` **expression** over a `String`: it picks the first `case` whose text equals `path`, and the `default` arm catches all else. Each arm produces a `Routed` object, and `return switch ...;` returns it. `this::health` is a **method reference**, a short form of a lambda (feature 6).

A `switch` can also look at the **type** of a value, and take a record apart:

```java
    return switch (existing) {
      case Entry.Running running -> new Claim.InProgress();
      case Entry.Done done -> new Claim.Replay(done.reply());
    };
```

`case Entry.Running running ->` matches when `existing` is a `Running`, and binds it to the name `running`. Because `Entry` is sealed with exactly two records, the compiler checks that both are handled and no `default` is needed. If someone adds a third record, this `switch` stops compiling.

```java
  public static <T> Optional<T> load(
      Path file, Function<SettingsReader, Result<T, List<String>>> reading, PrintStream err) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(reading, "reading");
    Objects.requireNonNull(err, "err");
    try {
      return switch (reading.apply(new SettingsReader(SettingsReader.load(file)))) {
        case Result.Success<T, List<String>>(T checked) -> Optional.of(checked);
        case Result.Error<T, List<String>>(List<String> problems) -> {
          err.println("the settings in " + file + " have " + problems.size() + " problem(s):");
          problems.forEach(problem -> err.println("  " + problem));
          yield Optional.empty();
        }
      };
    } catch (IOException unreadable) {
      err.println(unreadable.getMessage());
      return Optional.empty();
    }
  }
```

- `case Result.Success<T, List<String>>(T checked) ->` is a **record pattern**: it checks the type and takes the record apart into its component `checked` in one step (`result4j.md`).
- A `switch` arm may be a block `{ ... }`. The keyword `yield` gives the value of the whole expression from inside the block: `yield Optional.empty();`.
- `case ... -> Optional.of(checked);` returns a value without a block.
- `return switch (...) { ... };` returns the value the `switch` produced.

A `switch` **statement** with arrows does something in each arm and gives no value:

```java
  private void handle(HttpExchange exchange, String scheme) {
    long startNanos = System.nanoTime();
    metrics.workerStarted();
    String route = "other";
    int status = 0;
    long bytes = 0;
    try (exchange) {
      Request request = request(exchange, scheme);
      MDC.put("correlationId", request.correlationId());
      SeatWaveRoutes.Routed routed = routes.route(request);
      route = routed.route();
      switch (routed.reply()) {
        case Reply.Body body -> {
          status = body.status();
          bytes = body.length();
          writeBody(exchange, body, startNanos);
        }
        case Reply.EventStream stream -> {
          status = STREAM_OK;
          bytes = writeStream(exchange, stream);
        }
      }
    } catch (IOException clientGone) {
      // The client closed the connection while SeatWave was reading or writing; nothing to answer.
      LOG.debug("connection closed by the client: {}", clientGone.getMessage());
    } finally {
      metrics.workerFinished(route, status, bytes, System.nanoTime() - startNanos);
      MDC.clear();
    }
  }
```

This is the request handler of SeatWave: `switch (routed.reply())` has two arms, one for each kind of `Reply`. Features 9 and 10 below explain the `try`, `catch` and `finally` around it.

#### 5. Generics

**Generics** let one class or method work for many types while the compiler still checks them. `List<String>` is a list that holds only text. The type in angle brackets is a **type argument**.

The method in `SettingsFiles` above is generic: `public static <T> Optional<T> load(Path file, Function<SettingsReader, Result<T, List<String>>> reading, PrintStream err)`. `<T>` before the return type declares a **type parameter**: `T` stands for "the settings type" and is decided by the caller. The same method loads a `BackendSettings`, a `BenchSettings`, a `ProbeSettings` or a `SlowSettings`, with no cast. `Function<SettingsReader, Result<T, List<String>>>` is the type of a function that takes a reader and returns a result.

`JsonCodec.read` is another: `public <T> Result<T, BindingProblem> read(String json, Class<T> type)`. A `Class<T>` is a handle for a type, passed as `HoldRequestBody.class`; the compiler then knows that the result is a `HoldRequestBody`.

#### 6. Lambdas and method references

A **lambda** is a short anonymous function: `(parameters) -> body`. A **method reference** such as `Math::max` or `this::health` is a lambda that only calls a method.

```java
    this.adminThread =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "seatwave-admin"));
    admin.setExecutor(adminThread);
    admin.createContext("/", this::handleAdmin);
    tls.setHttpsConfigurator(tlsOnlyVersion13(tlsContext));
    plain.setExecutor(workers);
    tls.setExecutor(workers);
    plain.createContext("/", exchange -> handle(exchange, "http"));
    tls.createContext("/", exchange -> handle(exchange, "https"));
```

- `task -> new Thread(task, "seatwave-admin")` is a lambda with one parameter. `Executors.newSingleThreadExecutor(...)` asks for a **thread factory**, a function that makes a thread for a task; the lambda is that function, and it gives the thread a name.
- `exchange -> handle(exchange, "http")` is a lambda that the HTTP server calls for every request.
- `this::handleAdmin` is the same idea as `exchange -> handleAdmin(exchange)`.

In `BackendMetrics`: `busyWorkersMax.accumulateAndGet(busy, Math::max)` passes `Math::max` as the function that combines the old and the new value.

#### 7. `Optional` and streams

An `Optional<T>` is a box that holds a value or nothing. It makes "there may be no result" visible in the type, where `null` hides it.

```java
  Optional<Hold> hold(long eventId, String seat, String customer) {
    Instant now = clock.instant();
    Hold candidate =
        new Hold(UUID.randomUUID().toString(), eventId, seat, customer, now.plus(HOLD_DURATION));
    // compute runs atomically per seat: the check and the write cannot be split by another thread.
    Hold owner =
        holdsBySeat.compute(
            seat,
            (label, existing) ->
                existing == null || !existing.isActiveAt(now) ? candidate : existing);
    if (owner != candidate) {
      return Optional.empty();
    }
    holdsById.put(candidate.holdId(), candidate);
    return Optional.of(candidate);
  }

  Optional<Hold> find(String holdId) {
    return Optional.ofNullable(holdsById.get(holdId));
  }
```

- `Optional<Hold> hold(...)` returns the new hold, or `Optional.empty()` when another customer holds the seat. `Optional.of(x)` wraps a value; `Optional.ofNullable(x)` wraps a value that may be `null`.
- The caller checks with `isPresent()` / `isEmpty()`, or takes the value out with `get()` after checking, `orElseThrow()`, or `orElse(...)`. In the tests, AssertJ's `assertThat(optional).hasValue(...)` checks one (`assertj.md`).

A **stream** is a pipeline of steps over a collection: make a stream, change or filter its items, then collect the result.

```java
  private static String row(int port, List<AccessLine> group) {
    List<AccessLine> asked = group.stream().filter(AccessLine::askedUpstream).toList();
    long serverErrors =
        asked.stream().filter(line -> line.upstreamStatus() >= FIRST_SERVER_ERROR).count();
    List<AccessLine> lookups =
        group.stream().filter(line -> !line.cacheStatus().isEmpty()).toList();
    long hits = lookups.stream().filter(line -> line.cacheStatus().equals("HIT")).count();
    List<AccessLine> staticLookups =
        lookups.stream().filter(line -> line.uri().startsWith(STATIC_PREFIX)).toList();
    long staticHits =
        staticLookups.stream().filter(line -> line.cacheStatus().equals("HIT")).count();
    List<Duration> added = asked.stream().map(AccessLine::proxyAdded).sorted().toList();
```

- `group.stream()` starts a pipeline over a list of access-log lines.
- `.filter(AccessLine::askedUpstream)` keeps only the items for which the method returns true. `AccessLine::askedUpstream` is a method reference.
- `.toList()` ends the pipeline and returns an unchangeable list. `.count()` ends it with the number of items, a `long`.
- `.map(AccessLine::proxyAdded)` turns each item into another value (here a `Duration`), and `.sorted()` puts them in order. The result is a sorted list of the time the proxy added to each request.
- A lambda such as `line -> line.cacheStatus().equals("HIT")` is the test inside a `filter`.

Other streams in the code use `anyMatch` (is there at least one item that fits?), `reduce(Duration.ZERO, Duration::plus)` (fold a list into one value, here the sum of the phases of a request, in `PhaseTimings`), and `Collectors.joining`, which joins text pieces. `JsonCodec.pointerOf` ends with one:

```java
  private static String pointerOf(JsonMappingException failure) {
    // RFC 6901 pointer: "/field" for each step of the path Jackson was reading.
    return failure.getPath().stream()
        .map(step ->
            step.getFieldName() == null
                ? String.valueOf(step.getIndex())
                : escaped(step.getFieldName()))
        .collect(Collectors.joining("/", "/", ""));
  }
```

`failure.getPath().stream()` starts the pipeline over the path steps. `.map(step -> ...)` turns each step into text; the `? :` is the **conditional operator**, "if the field name is null then the index else the escaped name". `.collect(Collectors.joining("/", "/", ""))` joins the pieces with a `/` between them, a `/` before and nothing after. For `eventId` it makes `/eventId`.

#### 8. Immutable collections

`List.of("a", "b")`, `Map.of(...)` and `Set.of(...)` make collections that cannot be changed. `List.copyOf(x)` makes an unchangeable copy. Several classes use them to stay immutable: the compact constructor of `Reply.Body` does `headers = List.copyOf(headers);` so a caller cannot change the list after handing it over, and `body = Objects.requireNonNull(body, "body").clone();` copies the byte array for the same reason. Mutable collections exist too (`new ArrayList<>()`, `new HashMap<>()`); this code uses them for local work inside a method.

```java
  record Body(int status, String contentType, byte[] body, List<Map.Entry<String, String>> headers)
      implements Reply {

    /** Copies the body and the headers. */
    public Body {
      Objects.requireNonNull(contentType, "contentType");
      body = Objects.requireNonNull(body, "body").clone();
      headers = List.copyOf(headers);
    }
```

`Map.Entry<String, String>` is one pair of a map; the headers are a list of pairs because the order matters. `List.of()` in `Reply.json` is an empty unchangeable list. The **diamond** `<>`, as in `new ArrayList<>()` or `new HashMap<>()` elsewhere in the code, lets the compiler fill in the type argument from the left-hand side.

#### 9. Exceptions

An **exception** is an object that is thrown when something goes wrong. Throwing stops the current method and unwinds the stack until some `catch` block handles it. There are two families. A **checked** exception such as `IOException` must be declared with `throws` in the method signature or caught; the compiler enforces it. An **unchecked** exception (`IllegalArgumentException`, `NullPointerException`, `UncheckedIOException`) needs no declaration.

This repository draws a line (`result4j.md`): a **bug** (a null argument, a record built with a forbidden value) throws an unchecked exception; a **failure a person or a client can cause** (a bad settings file, a bad request body) is a `Result`. `SettingsReader.load` throws a checked `IOException` for a missing file, with a message that names the file:

```java
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (NoSuchFileException missing) {
      throw new IOException("the settings file " + path + " does not exist", missing);
    }
    return properties;
  }
```

- `throws IOException` in the signature tells callers they must handle it.
- `try (Reader reader = ...) { ... } catch (NoSuchFileException missing) { ... }` is a try-with-resources (feature 10) with a `catch`. `NoSuchFileException` is a more specific kind of `IOException`; it is caught and **wrapped** in a new `IOException` that carries a clear message and, as a second argument, the original exception as the **cause**.

`finally` is a block that runs after the `try` block whether it succeeded or threw. The handler quoted in feature 4 uses it to count a finished request and to clear the logging context, on every path.

#### 10. try-with-resources

A **resource** is something that must be closed: a file, a network connection, a process. The `try (...)` form closes it for you at the end of the block, even when an exception is thrown.

```java
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
```

`try (OutputStream out = exchange.getResponseBody()) { out.write(body); }` writes the answer and closes the stream. `try (exchange)` in the handler of feature 4 does the same for the whole exchange. Any class that implements `AutoCloseable` can be used this way, including `BackendServer` itself, which is why the tests write `try (TestBackend tiny = TestBackend.start(...)) { ... }`.

#### 11. Strings and formatting

A `String` is immutable text. `+` joins strings: `"the scheme must be http or https, but was " + scheme`. Useful methods in this code: `strip()` removes surrounding spaces, `startsWith`, `contains`, `toLowerCase(Locale.ROOT)`, `isBlank()`, `replace`, `split`. `String.format` fills a template: `%.1f` prints a number with one decimal.

```java
    BenchRow row = median(runs);
    LoadResult result = row.result();
    double perResponse = result.requests() == 0 ? 0 : 1.0 / result.requests();
    String hitShare =
        result.cacheLookups() == 0
            ? "-"
            : String.format(
                Locale.ROOT, "%.1f", PERCENT * result.cacheHits() / result.cacheLookups());
```

`Locale.ROOT` is passed on purpose. Without it, `String.format` uses the computer's language settings, and on a machine set to German the decimal point would print as a comma. `Locale.ROOT` fixes the format on every machine, which matters for a table whose numbers are copied into a document. `cond ? a : b` is the conditional operator again. `ROW` is a constant template with many `%` markers. The same `toLowerCase(Locale.ROOT)` appears in `BackendServer.request` (line 245) for header names.

#### 12. Numbers

Whole numbers: `int` (32 bits, up to about 2.1 billion), `long` (64 bits). Fractions: `double`. Division of two whole numbers cuts the fraction: `7 / 2` is `3`. Arithmetic on `int` that gets too large **wraps around** silently, so the code uses `long` for byte counts and times (`long startNanos`, `long bytes`). The `LongAdder` and `AtomicInteger` classes (feature 17) are counters that many threads can bump at once.

#### 13. Time: `Duration`, `Instant`, `Clock` and `System.nanoTime`

A `Duration` is a length of time (`Duration.ofSeconds(300)`). An `Instant` is a point on the time line, in UTC. A `Clock` produces the current `Instant` (`clock.instant()`). `HoldLedger` receives a `Clock` in its constructor, so a test can give it a fixed clock and make a hold expire without waiting 300 seconds. `System.nanoTime()` returns a number of nanoseconds from an unspecified start; it is only good for **measuring a span** (subtract two readings) and never for the time of day. `BackendServer.handle` uses it for the `Server-Timing` value of a response.

#### 14. Regular expressions

A `Pattern` is a compiled regular expression. `pattern.matcher(text).matches()` is true if the **whole** text fits. The settings classes use patterns to say what a key may contain, for example `Pattern.compile("[A-Za-z0-9_./-]{1,200}")` for a file path: 1 to 200 characters from letters, digits and the signs `_ . / -`. `[abc]` means one of these characters; `{1,200}` means 1 to 200 times; `|` means "or"; `\\.` (in a Java string) is a literal dot.

#### 15. Files and paths

A `Path` names a file or folder. `Path.of("config/bench.properties")` is relative to the folder the program starts in; `folder.resolve("x.pem")` appends a name. `Files` reads and writes: `Files.newBufferedReader(path, StandardCharsets.UTF_8)` in the feature-9 example, `Files.readString`, `Files.writeString`. Every read names the character set, so the result does not depend on the machine.

#### 16. Anonymous classes and `@Override`

An **anonymous class** defines and instantiates a class in one place, without a name.

```java
  private static HttpsConfigurator tlsOnlyVersion13(SSLContext context) {
    return new HttpsConfigurator(context) {
      @Override
      public void configure(HttpsParameters parameters) {
        SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
        // TLS 1.3 only, as in Module 5's lab: one round trip, and an older offer is refused.
        ssl.setProtocols(new String[] {"TLSv1.3"});
        parameters.setSSLParameters(ssl);
      }
    };
  }
}
```

`new HttpsConfigurator(context) { ... }` makes a subclass of `HttpsConfigurator` on the spot and overrides one method, `configure`, to allow only TLS 1.3. `@Override` asks the compiler to check that the method really replaces one of the parent. `getSSLContext()` is a method inherited from the parent class.

#### 17. Threads, executors and the small concurrency tools

A **thread pool** (an `ExecutorService`) keeps a fixed number of threads and a queue of tasks.

```java
    AtomicInteger workerNumber = new AtomicInteger();
    this.workers =
        new ThreadPoolExecutor(
            settings.workerThreads(),
            settings.workerThreads(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(settings.queueCapacity()),
            task -> new Thread(task, "seatwave-worker-" + workerNumber.incrementAndGet()),
            (task, pool) -> {
              // A full queue is refused, never grown: the JDK server then closes that connection.
              metrics.rejectedByQueue();
              throw new RejectedExecutionException("the request queue is full");
            });
```

- `ThreadPoolExecutor(core, max, keepAlive, unit, queue, factory, handler)` takes seven arguments. Here the core and the maximum are both `settings.workerThreads()` (64), so the pool never grows or shrinks. `0, TimeUnit.MILLISECONDS` is the keep-alive for extra threads, which do not exist.
- `new ArrayBlockingQueue<>(settings.queueCapacity())` is the **bounded queue** in front of the workers: when all 64 workers are busy, requests wait in it, and when it is full the next one is refused.
- `task -> new Thread(task, "seatwave-worker-" + workerNumber.incrementAndGet())` names each thread `seatwave-worker-1`, `seatwave-worker-2` and then the next numbers. `AtomicInteger` is an integer that can be changed by several threads without a lock: `incrementAndGet()` adds one and returns the new value as one indivisible step.
- The last argument is the **rejection handler**, a lambda with two parameters. When the queue is full it counts the refusal in the metrics and throws `RejectedExecutionException`, so the JDK server closes that connection. A full queue is refused and never grown.

**Counters and maps shared by threads.** `LongAdder` (in `BackendMetrics`) is a counter built for many writers: `increment()`, `add(n)`, `sum()`. `ConcurrentHashMap` is a map that many threads may use at once. `HoldLedger` relies on one of its methods for the module's central rule:

```java
  private final ConcurrentMap<String, Hold> holdsBySeat = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Hold> holdsById = new ConcurrentHashMap<>();
  private final Clock clock;
```

`holdsBySeat.compute(seat, (label, existing) -> ...)`, quoted in feature 7, runs the lambda **atomically for that key**: no other thread can change the seat's entry between the check and the write, so two requests for one seat cannot both win.

**Starting and stopping.** The main thread of the backend waits for a signal:

```java
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              server.get().close();
              System.out.println("SeatWave stopped");
              stopped.countDown();
            }));
    stopped.await();
  }
```

- `CountDownLatch stopped = new CountDownLatch(1);` is a gate with a count of 1. `stopped.await()` blocks the main thread until the count reaches 0.
- `Runtime.getRuntime().addShutdownHook(new Thread(() -> { ... }))` registers a thread that the JVM starts when the process is asked to end (Ctrl+C, `kill`). It closes the server, prints `SeatWave stopped` and calls `stopped.countDown()`, which opens the gate and lets `main` finish.
- `throws InterruptedException` appears because `await()` can be interrupted. Code that catches it should restore the flag with `Thread.currentThread().interrupt()`, as `BackendServer.close` does.

**A child process.** `ContainerCounters.read` starts the `docker` program with `new ProcessBuilder(command).redirectErrorStream(true).start()`, waits with a time limit (`process.waitFor(...)`), reads its output, and destroys it in a `finally` block. This note and the whole set did not run it, because it needs Docker.

#### 18. Javadoc

Almost every class and method in `src/main` has a Javadoc comment with `@param`, `@return` and `@throws`, and a `@param` line for each component of a record. They are the first place to read what a class is for and which rules it keeps. The comment of `Hold` quoted above is a typical one.

#### 19. The static helpers used everywhere

- `Objects.requireNonNull(x, "x")`: throws if `x` is `null`.
- `List.of(...)`, `Map.of(...)`, `List.copyOf(...)`: immutable collections.
- `Optional.of`, `Optional.empty`, `Optional.ofNullable`.
- `Duration.ofMillis`, `Duration.ofSeconds`; `Instant.now`; `Clock.systemUTC()`.
- `StandardCharsets.UTF_8`: the character set named in every conversion between text and bytes.
- `UUID.randomUUID()`: a random identifier, used for hold ids and request ids.

### The minimum Java version of each feature

| Feature | First in Java |
|---|---|
| `List.of`, `Map.of`, `Set.of`, `List.copyOf` | 9 / 10 |
| Lambdas, `Optional`, streams, `java.time` | 8 |
| `try-with-resources` on an existing variable (`try (exchange)`) | 9 |
| `switch` expressions with `->` and `yield` | 14 |
| Records | 16 |
| `instanceof` patterns (`socket instanceof SSLSocket secure` in `LoadRun`) | 16 |
| Sealed interfaces | 17 |
| Pattern matching for `switch`, record patterns | 21 |
| Virtual threads (not used for the workers here) | 21 |
| `InetAddress.ofLiteral` (used by `TrustedProxies`) | 22 |

The project compiles with `--release 25`, so all of these are available.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026 (Temurin 25.0.2). Timings differ on every run.

1. See the Java version and compile the main code:

```
java -version
./gradlew --offline -q compileJava
```

   Expected: `openjdk version "25.0.2" 2026-01-20 LTS` on the first line, and no output from Gradle.

2. Count the class files and the source files:

```
find build/classes/java/main -name '*.class' | wc -l
git ls-files 'src/main/java/*.java' | wc -l
```

   Expected: `93` and `54`.

3. Look inside a record:

```
javap -p -cp build/classes/java/main io.plainround.seatwave.proxy.backend.Hold
```

   Expected: `public final class ...Hold extends java.lang.Record`, five `private final` fields, the constructor, `isActiveAt`, `toString`, `hashCode`, `equals` and five accessors.

4. See the class-file version:

```
javap -v -cp build/classes/java/main io.plainround.seatwave.proxy.backend.Hold | grep 'major version'
```

   Expected: `major version: 69`, which is Java 25.

5. See that a sealed interface lists its subtypes:

```
javap -v -cp build/classes/java/main io.plainround.seatwave.proxy.backend.Reply | grep -A3 PermittedSubclasses
```

   Expected: `PermittedSubclasses:` followed by `io/plainround/seatwave/proxy/backend/Reply$Body` and `io/plainround/seatwave/proxy/backend/Reply$EventStream`.

## Mistakes people make

- **Comparing text with `==`.** It compares references, not letters. Use `.equals(...)`; the code does (`scheme.equals("http")`).
- **Forgetting that `int` wraps.** Use `long` for byte counts and nanoseconds.
- **Catching `Exception` or ignoring it.** The code catches the specific type it can handle.
- **Using `null` for "no result".** `Optional` or a `Result` says it in the type.
- **A mutable object shared between threads without protection.** Records and `List.copyOf` copy; `ConcurrentHashMap.compute` and `LongAdder` are the tools that are safe.
- **Leaving a resource open.** try-with-resources closes it.
- **Using the default `Locale` in output.** `Locale.ROOT` keeps a table the same on every machine.
- **Assuming virtual threads are always better.** The backend uses platform threads on purpose, so the lab can show the cost of a busy worker.

## Check yourself

1. *What does the compact constructor of a record do?* It runs first, with the parameters in scope, to check or adjust the values before the fields are assigned.
2. *Why does a `switch` over a sealed interface need no `default`?* The compiler knows every permitted type, so it can check that all are handled.
3. *What is the difference between a checked and an unchecked exception?* A checked one (`IOException`) must be declared with `throws` or caught; an unchecked one (`IllegalArgumentException`) need not be.
4. *Why does `HoldLedger` use `ConcurrentHashMap.compute`?* It runs the check and the write for one seat as one step, so two requests for one seat cannot both win.
5. *Why are the workers platform threads?* A worker busy with a slow client must cost a scarce thread, which is the effect the lab measures and proxy buffering removes.

## Further reading

- The Java Language Specification, Java SE 25: <https://docs.oracle.com/javase/specs/jls/se25/html/index.html>
- JDK 25 API documentation: <https://docs.oracle.com/en/java/javase/25/docs/api/index.html>
- Records (JEP 395): <https://openjdk.org/jeps/395>
- Sealed classes (JEP 409): <https://openjdk.org/jeps/409>
- Pattern matching for `switch` (JEP 441) and record patterns (JEP 440): <https://openjdk.org/jeps/441>, <https://openjdk.org/jeps/440>
- `ThreadPoolExecutor` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html>
- Virtual threads (JEP 444): <https://openjdk.org/jeps/444>
