# AssertJ 3.27.7

## What it is

An **assertion** is a check inside a test: "this value must be that value". AssertJ is a library of assertions with a **fluent** style: you write `assertThat(actual)` and then chain method calls that read like a sentence, each returning the object so the next call can follow. When a check fails, AssertJ prints the expected and the actual value in a clear message.

## Why this repository uses it

Tests are the requirements here, so they should read like requirements. `assertThat(response.statusCode()).isEqualTo(200)` is easier to read than a hand-written `if` with a `throw`. In a test of a proxy and its backend the message matters, because a failure often shows a status code, a byte count, a header or a refusal text, and that value is the clue. The README lists AssertJ in its table of libraries for this reason: readable assertions with clear failure messages.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `gradle/libs.versions.toml` | `assertj = "3.27.7"` and `assertj-core`. |
| `build.gradle.kts` | `testImplementation(libs.assertj.core)`. |
| `gradle.lockfile` | Locks `assertj-core` and its dependency `byte-buddy` 1.18.3 (`dependency-locking.md`). |
| `src/test/java/**` | `assertThat` is imported in 26 files and `assertThatThrownBy` in 10 files. There are 304 `assertThat(` calls and 30 `assertThatThrownBy(` calls in all. |

## How it works underneath

1. `assertThat(x)` looks at the type of `x` and returns the matching **assertion object**: one for a `String`, one for a `List`, one for a `Duration`, one for a `boolean`, one for an `Optional`, one for any other object. Each has the methods that make sense for that type.
2. Each method (`isEqualTo`, `contains`, `hasSize`) checks the value. If the check holds, it returns the same assertion object, so another call can follow. If not, it throws an `AssertionError` with a message, and JUnit marks the test as failed (`junit.md`).
3. For exceptions, `assertThatThrownBy(() -> code)` runs the code in a try block, catches whatever is thrown and lets you check its type and message. The `() -> code` part is a **lambda**: a small unnamed function that is handed over and run later, inside AssertJ, so AssertJ can catch what it throws.
4. Because the checks are chained on one `assertThat`, several conditions on the same value come in one statement.
5. An assertion runs once, on the thread that calls it, at that moment. It does not wait. A test of a network program therefore waits in a different way: the call to the server blocks until the answer comes (`backend.get(...)`), or Awaitility polls for a state (`awaitility.md`), and only then does the assertion look at it.

## Walkthrough of this repository's use

The counts in this section come from `grep -rho` over `src/test` and count each method name; a few names, such as `contains`, also match Java's own `String.contains`, so I give no total for them.

### `isEqualTo` for numbers, text, durations and enums

```java
  @Test
  void servesModuleOnesSeatMapByteForByteWithTheDeprecationHeaders() {
    HttpResponse<String> response = backend.get(backend.plain("/events/1/seats?section=A"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .hasSize(36_894)
        .startsWith("[{\"seat\":\"A-1\",\"state\":\"available\"}");
    assertThat(response.headers().firstValue("cache-control")).hasValue("no-cache");
    assertThat(response.headers().firstValue("deprecation")).hasValue("@1794355200");
    assertThat(response.headers().firstValue("sunset")).hasValue("Tue, 11 May 2027 00:00:00 GMT");
    assertThat(response.headers().firstValue("server-timing").orElseThrow()).startsWith("app;dur=");
  }
```

`isEqualTo` compares with `equals`. It is the most used method here (137 calls). `assertThat(response.statusCode()).isEqualTo(200)` compares two whole numbers. For text it compares every character. For a `Duration` (`Duration.ofMillis(250)` is a span of 250 milliseconds) it compares the length of the span. An enum value such as `AddressSource.UNREADABLE` is compared the same way. Java **records** such as `ClientView` get a correct `equals` automatically, so two records with the same contents are equal, and `ClientAddressTest` can write `assertThat(view).isEqualTo(new ClientView(PROXY, "http", AddressSource.UNREADABLE));`.

In the test above, `.hasSize(36_894)` on the body text checks the length of the seat map: 36,894 characters, Module 1's size, byte for byte. `.startsWith(...)` checks the beginning. A **chain** puts two checks on the same body. `hasValue(...)` is for an `Optional`: `firstValue("cache-control")` is an `Optional<String>` because a header may be missing, and `hasValue("no-cache")` says it is present and equal. `orElseThrow()` takes the value out of an `Optional` and fails the test with an exception if it is missing.

### Lists: `containsExactly`, `isEmpty`

```java
  @Test
  void collectsOneProblemPerMissingOrInvalidKey() {
    Properties properties = new Properties();
    properties.setProperty("count", "seven");
    properties.setProperty("range", "99");
    properties.setProperty("word", "Upper");
    properties.setProperty("list", "one, Two");
    SettingsReader reader = new SettingsReader(properties);

    reader.intBetween("count", 1, 10);
    reader.intBetween("range", 1, 10);
    reader.textMatching("word", WORD);
    reader.listMatching("list", WORD);
    reader.text("absent");
    reader.addProblem("a and b must differ");

    assertThat(reader.problems())
        .containsExactly(
            "count must be a whole number, but was seven",
            "range must be from 1 to 10, but was 99",
            "word must match [a-z]+, but was Upper",
            "list has the item 'Two', which must match [a-z]+",
            "absent is missing",
            "a and b must differ");
  }

  @Test
  void readsValidValues() {
    Properties properties = new Properties();
    properties.setProperty("waitMillis", "250");
    properties.setProperty("names", "alpha, beta");
    SettingsReader reader = new SettingsReader(properties);

    assertThat(reader.millisBetween("waitMillis", 1, 1_000)).isEqualTo(Duration.ofMillis(250));
    assertThat(reader.listMatching("names", WORD)).containsExactly("alpha", "beta");
    assertThat(reader.problems()).isEmpty();
  }
```

- `containsExactly(...)` on a list checks the items **and their order**: the reader must report its six problems in exactly this order, one per missing or invalid key. That order is part of the promise "one run lists every problem", so the test pins it.
- `isEmpty()` checks that a list has no item. `assertThat(reader.problems()).isEmpty()` is how the second test says "no problem was found".
- The number `1_000` is a Java number with an underscore for readability; the underscore changes nothing.

### Text: `contains`, `doesNotContain`, `startsWith`, `as`

```java
  @Test
  void everyProxiedLocationOfTheEdgeSendsTheForwardingHeaders() throws IOException {
    for (Path file : edgeFiles()) {
      for (String location : locations(read(file))) {
        if (location.contains("proxy_pass")) {
          assertThat(location)
              .as("a location in " + file.getFileName())
              .contains("include seatwave/proxy-headers.conf;");
        }
      }
    }
  }

  @Test
  void overwritesTheClientAddressInsteadOfAppendingToIt() throws IOException {
    String headers = read(NGINX.resolve("seatwave/proxy-headers.conf"));

    assertThat(headers)
        .contains("proxy_set_header X-Forwarded-For $remote_addr;")
        .contains("proxy_set_header Forwarded \"for=$remote_addr;proto=$scheme\";");
    for (Path file : edgeFiles()) {
      assertThat(read(file)).as(file.toString()).doesNotContain("$proxy_add_x_forwarded_for");
    }
  }
```

- `contains(...)` on text checks that a piece occurs somewhere; with several arguments it checks all of them. `doesNotContain(...)` checks the opposite. The first test reads the proxy's configuration as text and checks rules about it (`tests-stack-walkthrough.md`).
- `.as("a location in " + file.getFileName())` gives the check a **label**. When it fails, the message starts with the label, so a failure in a loop over many files says which file failed. `ClientAddressTest` does the same with `as(malformed)`, where the label is the bad header value.

### Numbers and comparisons

```java

  @Test
  void reusesConnectionsAndCountsEveryByteOfEverySuccessfulRequest() throws Exception {
    Scenario scenario =
        new Scenario(
            "reuse", backend.tls("/events/1/seats?section=A"), false, ConnectionMode.REUSE);

    LoadResult result = runner.run(scenario, RUN, 2);

    assertThat(result.requests()).isPositive();
    assertThat(result.errors()).isZero();
    assertThat(result.wireBytes()).isEqualTo(result.requests() * SEAT_MAP_BYTES);
    assertThat(result.plainBytes()).isEqualTo(result.wireBytes());
    assertThat(result.p99()).isGreaterThanOrEqualTo(result.p50());
    assertThat(result.requestsPerSecond()).isPositive();
  }
```

`isPositive()` means greater than zero, `isZero()` means zero, `isGreaterThanOrEqualTo(...)` compares two values (here two `Duration`s: the 99th percentile cannot be below the median). The line `isEqualTo(result.requests() * SEAT_MAP_BYTES)` checks an arithmetic invariant: the bytes counted on the wire are exactly the number of requests times the 36,894 bytes of the seat map.

### Exceptions: `assertThatThrownBy`

```java
  @Test
  void refusesFewerThanThreeRunsAndMoreHitsThanLookups() {
    assertThatThrownBy(() -> BenchReport.median(List.of(row(1, 1), row(2, 2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new LoadResult(1, 0, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 1, 1, 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

`assertThatThrownBy(() -> BenchReport.median(...))` runs the call, catches what it throws and `isInstanceOf(IllegalArgumentException.class)` checks the type. If nothing is thrown, the check fails: a test that expects a refusal cannot pass by accident. `hasMessage("...")`, `hasMessageContaining(...)` and `hasCauseInstanceOf(...)` check the message and the cause. `isInstanceOf` is the second most used check after `isEqualTo` (34 calls).

### Results that are errors: a cast

```java
  @Test
  void reportsEveryProblemIncludingTheRulesBetweenKeysTogether() throws IOException {
    Properties file = SettingsReader.load(Path.of("config/backend-local.properties"));
    file.setProperty("http.tlsPort", file.getProperty("http.plainPort"));
    file.setProperty("feed.streamLifetimeMillis", "10");
    file.setProperty("forwarded.trustedProxies", "999.1.1.1");
    file.setProperty("http.bindAddress", "300.1.1.1");
    file.remove("http.workerThreads");

    Result<BackendSettings, List<String>> result = BackendSettings.from(new SettingsReader(file));

    assertThat(result).isInstanceOf(Result.Error.class);
    List<String> problems = ((Result.Error<BackendSettings, List<String>>) result).error();
    assertThat(problems)
        .anyMatch(line -> line.equals("http.workerThreads is missing"))
        .anyMatch(
            line -> line.startsWith("http.plainPort, http.tlsPort and http.adminPort must differ"))
        .anyMatch(line -> line.startsWith("feed.streamLifetimeMillis must be longer"))
        .anyMatch(line ->
            line.startsWith("forwarded.trustedProxies: a trusted proxy must be an IP address"))
```

A `Result` (`result4j.md`) has no `getError` method, so the test first checks `isInstanceOf(Result.Error.class)` and then **casts** it: `((Result.Error<BackendSettings, List<String>>) result).error()` tells the compiler "I have just checked this is an Error; give me its error". `anyMatch(line -> ...)` checks that at least one item of the list satisfies a condition written as a lambda; four of them chained check that one run reported four different problems. This test reads the real `config/backend-local.properties`, damages five keys in memory and checks every message (`java-properties.md`).

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Count the assertions:

```
grep -rho "assertThat(" src/test | wc -l
grep -rho "assertThatThrownBy(" src/test | wc -l
```

   Expected: `304` and `30`.

2. Count the files that import each:

```
grep -rl "assertThat;" src/test | wc -l
grep -rl "assertThatThrownBy;" src/test | wc -l
```

   Expected: `26` and `10`.

3. Run one test class that is full of assertions:

```
./gradlew --offline cleanTest test --tests '*SettingsReaderTest' --console=plain
```

   Expected: three `PASSED` lines and `BUILD SUCCESSFUL`.

4. See an AssertJ failure message. In a **scratch copy**, change `"count must be a whole number, but was seven"` in `SettingsReaderTest.java` to `"count must be a number"`, then run the command of step 3. Expected: `SettingsReaderTest > collectsOneProblemPerMissingOrInvalidKey() FAILED` on the console, and the full message in `build/test-results/test/TEST-io.plainround.seatwave.proxy.config.SettingsReaderTest.xml`. It begins `Expecting actual:` and lists the six actual problems, then `to contain exactly (and in same order):` and the six expected ones, then `but some elements were not found:` followed by `["count must be a number"]` and `and others were not expected:` followed by `["count must be a whole number, but was seven"]`. Put the file back afterwards.

5. Count how many tests have a label:

```
grep -rn "\.as(" src/test | wc -l
```

   Expected: `6`.

## Mistakes people make

- **A test without an assertion.** It passes even if the code is wrong.
- **Comparing the wrong things.** `isEqualTo` on two different types compares nothing useful; check the type of what `assertThat` receives.
- **A loop without a label.** If a loop of 50 checks fails, you want to know which turn. `as(...)` solves it.
- **Forgetting that `containsExactly` checks order.** Use `containsExactlyInAnyOrder` when order does not matter.
- **Asserting before the thing has happened.** An assertion does not wait. Block on the answer, or poll with Awaitility.
- **Catching an exception by hand** in a `try` block and forgetting `fail()` for the case where nothing is thrown. `assertThatThrownBy` handles both.

## Check yourself

1. *What does `assertThat(x)` return?* An assertion object for the type of `x`, with the checks that fit it.
2. *What does a failed assertion throw?* An `AssertionError`, which JUnit reports as a failed test.
3. *Why does `assertThatThrownBy` take a lambda?* So AssertJ can run the code itself and catch what it throws.
4. *What does `as(...)` do?* It adds a label to the failure message.
5. *Why is `containsExactly` used for the problems of the settings reader?* The order of the problems is part of what the reader promises.

## Further reading

- AssertJ Core documentation (3.27.x): <https://assertj.github.io/doc/>
- AssertJ Javadoc for `Assertions`: <https://javadoc.io/doc/org.assertj/assertj-core/3.27.7/org/assertj/core/api/Assertions.html>
