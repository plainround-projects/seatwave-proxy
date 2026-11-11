# result4j 1.2.0 (a library for results that are values)

## What it is

result4j is a small Java library. Its main type is `Result<R, E>`. A **result type** is an object that stands for "either this worked and here is the value, or it failed and here is the reason". `R` is the type of the good value and `E` the type of the reason. A `Result` is exactly one of two things: a **success** that holds an `R`, or an **error** that holds an `E`.

The Java standard library has no such type. `Optional` says "a value or nothing" but cannot say why there is nothing. An exception says why, but it jumps out of the normal flow of the program. A `Result` fills the gap: the failure is an ordinary value that a method returns, and the caller must look at it.

The library is `com.github.sviperll:result4j`, version 1.2.0, licensed Apache-2.0 (its POM says so).

## Why this repository uses it

A settings file, a request body, a line of `curl` output, a line of the proxy's access log and a printout of a container's counters are all produced by something outside the program. A person can write `http.workerThreads=0`. A client can send `{"seat": }`. A failed curl writes `http=` with nothing after it. These are **expected failures**: the program should list them, count them and answer them, not die with a stack trace. One run must report every problem, not stop at the first.

Exceptions are kept for programmer errors: a null argument, a record built with a value its constructor forbids. The line is: if a person, a client or a failed tool can cause it, it is a `Result`; if only a bug can cause it, it is an exception.

The dependency is declared in `gradle/libs.versions.toml`, used in `build.gradle.kts` and pinned in `gradle.lockfile`:

```toml
result4j = "1.2.0"
```

```toml
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
```

```kotlin
    implementation(libs.result4j)
```

```text
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`implementation` means the library is needed to compile and to run the main code. The lock file shows it on all four class paths.

## Where it appears

| File (under `src/main/java/io/plainround/seatwave/proxy/`) | What it does with `Result` |
|---|---|
| `config/SettingsReader.java` | Nothing: it does **not** use `Result`. It collects problem lines in a list that callers turn into a `Result`. |
| `config/SettingsFiles.java` | `load` takes a function that returns `Result<T, List<String>>` and switches over it: on an error it prints every problem. |
| `backend/BackendSettings.java` | `from` returns `Result<BackendSettings, List<String>>`. |
| `bench/BenchSettings.java` | `from` returns `Result<BenchSettings, List<String>>`. |
| `probe/ProbeSettings.java` | `from` returns `Result<ProbeSettings, List<String>>`. |
| `slow/SlowSettings.java` | `from` returns `Result<SlowSettings, List<String>>`. |
| `forwarded/TrustedProxies.java` | `parse` returns `Result<TrustedProxies, String>`: the list, or why a literal is not an IP address. |
| `json/JsonCodec.java` | `read` returns `Result<T, BindingProblem>`: the request record, or which field is wrong and why. |
| `backend/SeatWaveRoutes.java` | `parameters` returns `Result<Map<String, String>, String>` for a query string, and `holdOnce` switches over `json.read`. |
| `bench/ContainerCounters.java` | `parse` returns `Result<Counters, String>` for the text read from a container. |
| `logs/AccessLine.java` | `parse` returns `Result<AccessLine, String>` for one line of the proxy's access log. |
| `report/CurlLine.java` | `parse` returns `Result<CurlSample, CurlProblem>`; `sample` and `malformed` build the results and examine one. |
| `report/PhaseTimings.java` | `of` returns `Result<PhaseTimings, String>`. |

Fourteen main files import the library: the twelve in the table that have `Result` in their code (every row except `SettingsReader`), plus `report/ReportCli.java` and `logs/LogStatsCli.java`, which switch over the results of the parsers. I counted them with the command in "Try it yourself".

The tests use it too: five test files import the type, and a few more call its methods on a returned value without importing it.

## How it works underneath

`Result<R, E>` is a **sealed interface** with two permitted types, both **records**. I ran `javap` on the 1.2.0 jar and confirmed it: `Result$Success` is a `final` class that extends `java.lang.Record` and implements `Result<R, E>`, and `Result$Error` is the same. A **sealed** interface lists every type allowed to implement it. That is what lets the compiler check a `switch`: with two possible cases, a `switch` that handles both needs no `default`.

- `Result.Success<R, E>` is a record with one component, `result`. Its `toString` prints `Success[result=7]`.
- `Result.Error<R, E>` is a record with one component, `error`. Its `toString` prints `Error[error=[q must be a whole number]]`.

Both have the same two type parameters. A `Success` never uses `E`, and an `Error` never uses `R`. That is why a pattern is written `Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed)`: both type parameters are spelled out.

Everything is in memory. A `Result` is an ordinary object on the heap. There is no thread, no network and no file. Making one costs one small object.

**The calls this repository uses in its main code:**

- `Result.success(value)` makes a `Success`.
- `Result.error(reason)` makes an `Error`.
- A `switch` over the `Result` with the record patterns `Result.Success<...>(value)` and `Result.Error<...>(error)`.

**The calls its tests use:** `isError()` (true for an error), `discardError()` (an `Optional` of the value, empty for an error), `orOnErrorThrow(function)` (the value, or throw the exception the function builds from the error), and the `error()` accessor of a `Result.Error` after a cast.

The library offers more. I listed its public methods with `javap -public` on the jar: `isError`, `map`, `flatMap`, `andThen`, `mapError`, `discardError`, `recoverError`, `throwError`, `orOnErrorThrow`, `ifSuccess`, `peekError`, `peekSuccess`, `fromOptional`, `fromOptionalResult`, and the function builders `mapping`, `flatMapping` and `errorMapping`. The main code uses none of them. I searched `src/main` for each name and found no call.

I ran a small program with the 1.2.0 jar on the class path. It parses `42` and `x` and returns a `Result<Integer, List<String>>`:

```text
got 42
problems [x must be a whole number]
Success[result=7] | Error[error=[q must be a whole number]]
true
Success[result=14]
true
```

The last three lines: `equals` compares by content (a parsed 7 equals `Result.success(7)`, because records compare by their components), `map` applies a function to a success (7 became 14), and `isError()` is true for the bad one.

**Java version.** The class files in the 1.2.0 jar have major version 65, which is Java 21 (`javap -v` printed `major version: 65`). So the library needs Java 21 or newer, and this repository's `switch` with record patterns also needs Java 21. The project builds with `--release 25`.

## Walkthrough of this repository's use

### Building a result from a list of problems: `BackendSettings.from`

```java
   * Reads the settings, reporting every missing or invalid key and every broken rule between keys
   * together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<BackendSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String bind = reader.textMatching("http.bindAddress", ADDRESS);
    int plainPort = reader.intBetween("http.plainPort", 0, HIGHEST_PORT);
    int tlsPort = reader.intBetween("http.tlsPort", 0, HIGHEST_PORT);
    int adminPort = reader.intBetween("http.adminPort", 0, HIGHEST_PORT);
    String keystore = reader.filePath("tls.keystore");
    String passwordFile = reader.filePath("tls.passwordFile");
    int backlog = reader.intBetween("http.backlog", 1, MAX_BACKLOG);
    int workers = reader.intBetween("http.workerThreads", 1, MAX_WORKERS);
    int queue = reader.intBetween("http.queueCapacity", 1, MAX_QUEUE);
    List<String> trusted = reader.listMatching("forwarded.trustedProxies", ADDRESS);
    Duration longPoll = reader.millisBetween("feed.longPollHoldMillis", 1, MAX_WAIT_MILLIS);
    int problemsBeforeStream = reader.problems().size();
    Duration heartbeat = reader.millisBetween("feed.heartbeatMillis", 1, MAX_WAIT_MILLIS);
    Duration lifetime = reader.millisBetween("feed.streamLifetimeMillis", 1, MAX_WAIT_MILLIS);
    // The rule between the two keys is checked only when both were read, so a missing key gives
    // one problem line, not a second one about the stand-in values.
    boolean streamKeysValid = reader.problems().size() == problemsBeforeStream;
    if (sharePort(plainPort, tlsPort, adminPort)) {
      reader.addProblem(
          "http.plainPort, http.tlsPort and http.adminPort must differ (0 means any free port)");
    }
    if (streamKeysValid && lifetime.compareTo(heartbeat) <= 0) {
      reader.addProblem(
          "feed.streamLifetimeMillis must be longer than feed.heartbeatMillis, or a stream ends"
              + " before its first heartbeat");
    }
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
      return Result.error(reader.problems());
    }
    return Result.success(
        new BackendSettings(
```

The method reads about fifteen keys. Each `reader.xxx(...)` call records a problem in the reader if the key is missing or out of range, and returns a harmless stand-in value. Nothing is thrown. Two cross-checks add their own problem lines (the three ports must differ; the stream must outlive its heartbeat). The second is checked only when both stream keys were read: `problemsBeforeStream` counts the problems before the two reads, and `streamKeysValid` is true only if the count did not grow. Without that, two missing keys would both be stood in by 1 ms, and the rule "lifetime must be longer than heartbeat" would add a third, false line about values nobody wrote. The `trusted` list goes through `TrustedProxies.parse`, which returns a `Result` itself, and its error is added as one more problem. The last `if` is the hinge: if the reader holds any problem, `Result.error(reader.problems())` returns all of them in one list, before any record is built from stand-in values. Otherwise `Result.success(new BackendSettings(...))` returns the checked record. That is why the program can print every problem in one run (`java-properties.md`).

### Building a result from one reason: `TrustedProxies.parse`

```java
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

This method stops at the first wrong literal, because it has a single `String` for a reason. `ClientAddress.literal` returns an `Optional`: an address, or empty for text that is not one. It never asks DNS, and it refuses the short IPv4 forms (`127.0.1` for `127.0.0.1`) that `InetAddress.ofLiteral` alone would accept, so a typo in the list cannot trust another address than the one written (`java-forwarded-walkthrough.md`). Three exits: a wrong literal returns `Result.error` with the text that names it; an empty list returns `Result.error("at least one trusted proxy address is needed")`; everything else returns `Result.success`.

### Building a result from the query string: `parameters`

```java
  private static Result<Map<String, String>, String> parameters(String query, Set<String> names) {
    Map<String, String> values = new LinkedHashMap<>();
    if (query.isEmpty()) {
      return Result.success(values);
    }
    for (String pair : query.split("&", -1)) {
      int equalsAt = pair.indexOf('=');
      String name = equalsAt < 0 ? pair : pair.substring(0, equalsAt);
      if (!names.contains(name)) {
        return Result.error("unknown parameter " + name + "; allowed: section, limit, cursor");
      }
      if (equalsAt < 0 || values.put(name, pair.substring(equalsAt + 1)) != null) {
        return Result.error("parameter " + name + " must appear once, with a value");
      }
    }
    return Result.success(values);
  }
```

The result type here is `Result<Map<String, String>, String>`. An empty query is a success with an empty map. Each pair is split at the first `=`. A name that is not in `names` is an error, `unknown parameter <name>; allowed: section, limit, cursor`. A pair with no `=`, or a name that appears twice (`values.put` returns the old value, which is not `null`), is an error. Otherwise the map of values is a success.

### Examining a result: `switch` with record patterns

```java
    Result<Map<String, String>, String> parameters =
        parameters(request.rawQuery(), Set.of("section", "limit", "cursor"));
    List<String> errors = new ArrayList<>();
    Map<String, String> values = Map.of();
    switch (parameters) {
      case Result.Success<Map<String, String>, String>(Map<String, String> parsed) ->
          values = parsed;
      case Result.Error<Map<String, String>, String>(String reason) -> errors.add(reason);
    }
```

- This `switch` is a **statement**: each arm does something instead of producing a value. On success it keeps the parsed map in `values`. On error it adds the reason to the list of errors. The caller later answers with a problem document listing every error at once.

```java
    Reply.Body reply =
        switch (json.read(body, HoldRequestBody.class)) {
          case Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed) ->
              holdSeat(request, parsed);
          case Result.Error<HoldRequestBody, JsonCodec.BindingProblem>(
                  JsonCodec.BindingProblem problem) ->
              invalid(
                  request,
                  List.of(
                      problem.pointer().isEmpty()
                          ? problem.detail()
                          : problem.pointer() + " " + problem.detail()));
        };
    // Final outcomes are stored, so a retry with the same key gets the same answer (Module 7).
```

- Here the `switch` is an **expression**: it produces the value of `reply`. Each case unpacks the record: `Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed)` binds the good value, and `Result.Error<...>(JsonCodec.BindingProblem problem)` binds the problem. A success books the seat. An error becomes an invalid-request answer that joins the field's JSON pointer and the detail, such as `/eventId has the wrong type`. When the whole body is at fault the pointer is empty, and the `? :` (the conditional operator: the value after `?` if the test is true, after `:` if not) uses the detail alone, so the text does not start with a space. The compiler checks that both cases are present. If a later version of the library added a third case, this `switch` would stop compiling, and the author would have to handle it.

### A result with many error exits: `JsonCodec.read`

```java
  public <T> Result<T, BindingProblem> read(String json, Class<T> type) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(type, "type");
    // The parser is driven by hand, so an empty body, a body that is not an object and data after
    // the object each get their own message instead of one generic type error.
    try (JsonParser parser = mapper.createParser(json)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        return Result.error(
            new BindingProblem(WHOLE_BODY, "the body is empty; send a JSON object"));
      }
      if (first != JsonToken.START_OBJECT) {
        return Result.error(new BindingProblem(WHOLE_BODY, "the body must be a JSON object"));
      }
      T value = mapper.readValue(parser, type);
      return hasMore(parser)
          ? Result.error(new BindingProblem(WHOLE_BODY, "the body has data after the JSON object"))
          : Result.success(value);
    } catch (UnrecognizedPropertyException unknownField) {
      return Result.error(
          new BindingProblem(
              "/" + escaped(unknownField.getPropertyName()), "is not a field of this request"));
    } catch (MismatchedInputException wrongType) {
      return Result.error(new BindingProblem(pointerOf(wrongType), "has the wrong type"));
    } catch (JsonMappingException unbound) {
      // 99999999999999999999 is valid JSON; it only does not fit the field's type.
      if (unbound.getCause() instanceof InputCoercionException) {
        return Result.error(
            new BindingProblem(pointerOf(unbound), "is out of range for this field"));
      }
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (StreamReadException malformed) {
      if (malformed.getOriginalMessage().startsWith(DUPLICATE_MESSAGE)
          && malformed.getProcessor() != null) {
        // The parser stopped on the repeated name, so its context is the path of that field.
        String pointer = malformed.getProcessor().getParsingContext().pathAsPointer().toString();
        return Result.error(new BindingProblem(pointer, "appears more than once"));
      }
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (JacksonException malformed) {
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (IOException notReadable) {
      // Every parse failure is a JacksonException; reading from a string has no other I/O to fail.
      throw new UncheckedIOException("reading a string failed", notReadable);
    }
  }
```

`read` has one success line, `: Result.success(value);`, and ten error exits, each with a `BindingProblem` that says which field and what is wrong: an empty body, a body that is not an object, data after the object, an unknown field, a wrong type, a number out of range, a field that appears twice and three kinds of malformed JSON. Only a failure that cannot be the client's fault (`IOException` while reading a `String`) is thrown, as an `UncheckedIOException`. This is the line the repository draws between a `Result` and an exception (`jackson.md` explains the Jackson side).

### How the tests use a result

```java
  void readsTheBenchSettingsOfTheRepository() throws Exception {
    Properties file = SettingsReader.load(Path.of("config/bench.properties"));

    BenchSettings settings =
        BenchSettings.from(new SettingsReader(file))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));
```

`orOnErrorThrow` returns the value if the `Result` is a success, and otherwise throws the exception that the function builds from the error. Here the exception is an `IllegalStateException` whose message is the list of problems, so a damaged `config/bench.properties` fails the test with every problem named.

```java

  @Test
  void readsCpuTimeAndTheConnectionsOpenedAndAccepted() {
    Result<Counters, String> parsed = ContainerCounters.parse(SAMPLE);

    assertThat(parsed.discardError()).contains(new Counters(1_834_567, 314, 42));
  }

  @Test
  void saysWhichFileWasNotRead() {
    assertThat(ContainerCounters.parse("Tcp: ActiveOpens\nTcp: 1\n").isError()).isTrue();
    assertThat(ContainerCounters.parse("usage_usec 1\n").isError()).isTrue();
    assertThat(ContainerCounters.parse("usage_usec 1\nTcp: Other Columns\nTcp: 1 2\n").isError())
        .isTrue();
```

`discardError()` turns a `Result` into an `Optional`, so AssertJ's `contains` can compare the value. `isError()` checks the failure cases in one line each.

## Try it yourself

All commands ran in a scratch copy of the repository on 11 November 2026.

**1. See that the library is a runtime dependency of the main code.**

```text
./gradlew --offline -q dependencies --configuration runtimeClasspath | grep result4j
```

```text
+--- com.github.sviperll:result4j:1.2.0
+--- com.github.sviperll:result4j:{strictly 1.2.0} -> 1.2.0 (c)
```

The `strictly` line is the lock file's constraint: the version may not change.

**2. List the methods of the type, from the jar.** Find the jar in Gradle's cache and run `javap`:

```text
javap -public -cp "$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1)" com.github.sviperll.result4j.Result | head -12
```

You should see `errorMapping`, `mapping`, `flatMapping`, `success`, `error`, `fromOptionalResult`, `fromOptional`, `isError`, `andThen` and `map` listed.

**3. Watch an expected failure come back as a value, not a crash.** Break a settings file as in `java-properties.md`, and run:

```text
./gradlew --offline -q serve --args='../cfgtest/bad.properties'
```

```text
the settings in ../cfgtest/bad.properties have 3 problem(s):
  http.workerThreads must be from 1 to 1024, but was 0
  feed.heartbeatMillis is missing
  http.plainPort, http.tlsPort and http.adminPort must differ (0 means any free port)
```

No stack trace from the program: it took the `Error` arm of the `switch` in `SettingsFiles.load` and exited with status 2. Gradle then adds its own failure report.

**4. Run the demo program.** Save the program from "How it works underneath" (the one that parses `42` and `x`) as `R4j.java` and run `java -cp <the jar> R4j.java`. It prints the six lines above.

**5. Count the files that import the library:**

```text
grep -rl "import com.github.sviperll.result4j.Result;" src/main | wc -l
```

## Mistakes people make

- **Using a `Result` for a bug.** A null argument or a record built with a forbidden value is a programmer error. `Objects.requireNonNull` and the constructors throw, as they should.
- **Using an exception for a typo in a settings file.** The program would stop at the first bad key. Here, every bad key is listed in one run.
- **Reading the value of an error.** The caller must look at the case first. The sealed `switch` forces it.
- **Using the stand-in values.** `SettingsReader` returns a stand-in for a bad key. The code returns `Result.error(reader.problems())` before it builds anything from them.
- **A `default` arm that hides a new case.** None of the switches here has a `default`, so a new case stops the build.
- **Mixing up the two type parameters.** The pattern needs both: `Result.Success<R, E>(R value)`.

## Check yourself

1. What are the two cases of a `Result`, and what does each hold? (`Success` holds the value `R`; `Error` holds the reason `E`.)
2. Why do the `switch`es over a `Result` in this repository have no `default` arm? (The interface is sealed with two permitted records, so the compiler knows both cases are handled. A new case would stop the build.)
3. When does this repository throw an exception instead of returning an error? (For programmer errors: a null argument, or a record built with a value its constructor forbids; and for a failure that a client cannot cause, such as an I/O error while reading a `String`.)
4. In `BackendSettings.from`, why is `Result.error(reader.problems())` returned before the record is built? (The stand-in values of bad keys are harmless only because the record is never made from them.)
5. What error type does `TrustedProxies.parse` use, and why not a list? (A single `String`: it reports the first literal that is wrong, or that none was given.)

## Further reading

Official documentation:

- The result4j project and its README, at the pinned version 1.2.0: https://github.com/sviperll/result4j
- Maven Central page of the artifact: https://central.sonatype.com/artifact/com.github.sviperll/result4j/1.2.0
- Sealed classes (JEP 409): https://openjdk.org/jeps/409
- Records (JEP 395): https://openjdk.org/jeps/395
- Pattern matching for `switch` (JEP 441) and record patterns (JEP 440): https://openjdk.org/jeps/441 and https://openjdk.org/jeps/440
