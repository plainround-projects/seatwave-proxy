# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and 119 of them run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks and Awaitility (`awaitility.md`) the waiting. This module is about a proxy, so many tests start a real SeatWave on the loopback address and talk to it; others read the NGINX configuration files and the Compose file and check the rules that the module states. JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform` in two tasks (`test` excludes the tag `stack`, `stackTest` includes it). |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 26 test classes with 125 `@Test` methods (119 run by `check`, 6 tagged `stack`), and 1 helper class that holds no tests (`TestBackend`). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test`. Because `useJUnitPlatform { excludeTags("stack") }` is set, a class or method tagged `stack` is left out of the plan.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the method, and throws the instance away. State cannot leak from one test to the next through an ordinary field.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 25 here, because the class tagged `stack` did not run), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
6. The classes and methods are **package-private** (no `public`). JUnit 5 and 6 allow that, which keeps tests out of the public surface. The helper `TestBackend` is the exception on purpose: it is `public` because tests in the `backend`, `bench`, `probe`, `slow`, `stack` and `tls` packages all use it.

JUnit does not promise an order between tests, and no test here depends on one. By default JUnit runs the tests one after the other on one thread, and Gradle uses one test JVM, so two tests never overlap in time.

## Walkthrough of this repository's use

### `@Test`: one behavior, one sentence

```java
  @Test
  void acceptsPhasesThatSumToTheTotal() {
    PhaseTimings timings = new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(50));

    assertThat(timings.of(Phase.TRANSFER)).isEqualTo(TEN);
  }

  @Test
  void refusesPhasesThatDoNotSumToTheTotal() {
    Result<PhaseTimings, String> refused =
        PhaseTimings.of(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52));

    assertThat(((Result.Error<PhaseTimings, String>) refused).error())
        .isEqualTo("the phases sum to 50000000 ns, but the total is 52000000 ns");
    assertThatThrownBy(() -> new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52)))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

`@Test` marks the method as a test. The name is a sentence about behavior, so a failure reads like a broken requirement: "refuses phases that do not sum to the total". The body has three steps that most tests here follow: **arrange** (build the input), **act** (call the code), **assert** (check the outcome). In the first test the first statement arranges and acts (`new PhaseTimings(...)`) and the second asserts. In the second, the first statement acts, and the two `assertThat` calls check that the result is an error with the exact message and that the constructor, which is stricter, throws.

Many tests that talk to a server declare `throws Exception`. That is allowed: JUnit calls the method and treats any exception it throws as an error. It saves the test from catching checked exceptions such as `IOException` or `InterruptedException` that would be noise in a test.

### `@TempDir`: a folder for one test

```java
  @Test
  void saysThatAMissingFileDoesNotExist(@TempDir Path folder) {
    Path missing = folder.resolve("absent.properties");

    assertThatThrownBy(() -> SettingsReader.load(missing))
        .isInstanceOf(IOException.class)
        .hasMessage("the settings file " + missing + " does not exist");
  }
```

`@TempDir` on a method parameter asks JUnit to create a new empty temporary folder, pass it in as `folder`, and delete it, with everything inside, after the test. This test asks for a file that does not exist inside that folder and checks the message of the `IOException`. I searched the test sources and found `@TempDir` 15 times.

### `@BeforeEach` and `@AfterEach`: a fresh server for every test

```java
  @BeforeEach
  void start() throws Exception {
    backend = TestBackend.untrusting();
    runner =
        new LoadRun(
            TlsFiles.clientContext(TestBackend.TLS.resolve("seatwave-test-ca.pem")),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    backend.close();
  }
```

`@BeforeEach` marks a method that runs before every test of the class, on the new instance. `@AfterEach` marks one that runs after every test, even after a failed one. `LoadRunTest` starts a real backend and builds the load generator before each test and closes the backend after it, so each test starts from a clean server with zero counters. `BackendServerTest` does the same with a backend that trusts no proxy. These are the only two classes that use the pair (each has one `@BeforeEach` and one `@AfterEach`).

### `@Tag`, `@BeforeAll` and `@AfterAll`: the tests that need the stack

```java
/**
 * The module's invariant against the running stack (scripts/stack.sh test): SeatWave trusts a
 * forwarded client address only from the proxy, and the proxy never caches a POST or a response
 * marked private, while it does cache a static file. Never part of ./gradlew check.
 */
@Tag("stack")
class StackInvariantTest {

  private static final String PROXY = "https://127.0.0.1:20900";
  private static final String SEATWAVE_DIRECT = "http://127.0.0.1:20911";
  private static final String METRICS = "http://127.0.0.1:20912/metrics";
  private static final String FORGED = "203.0.113.7";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static HttpClient client;

  @BeforeAll
  static void connect() {
    client = TestBackend.client();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }
```

- `@Tag("stack")` on the class labels it. The build file uses the label in two places (`gradle.md`): `test` calls `excludeTags("stack")`, so `./gradlew check` never needs Docker, and `stackTest` calls `includeTags("stack")`, so only these six tests run against the running stack.
- `@BeforeAll` marks a method that runs once before the first test of the class. `@AfterAll` marks one that runs once after the last. Both must be `static`, because no test instance exists yet when they run (JUnit creates a new instance for each test, as step 3 says). The class keeps one HTTP client in a `static` field, makes it once and closes it once. This is the only class that uses these two annotations.
- The six tests of this class were not run for these notes: they need the Compose stack, and no Docker was started (`tests-stack-walkthrough.md`).

### What this repository does not use

There is no `@ParameterizedTest`, `@ValueSource`, `@EnumSource`, `@Nested`, `@DisplayName`, `@Timeout`, `@Disabled` or `@ExtendWith`. I searched the test sources for each and found none. Where several inputs need one check, a test lists the checks one after another. Each test builds what it needs with ordinary helper methods. That keeps each test readable from top to bottom. A helper class holds no `@Test`, so JUnit ignores it.

There is also no `@Timeout`. A test that talks to a server never relies on JUnit to cut it off. Each wait carries its own bound instead: an `HttpClient` request timeout, a `Duration` limit in `await().atMost(TIMEOUT)`, or a timeout inside the call. A bound inside the call gives a clear error, where an outside timeout would only say that the test took too long.

### Static imports and the assertion style

All tests start with lines such as `import static org.assertj.core.api.Assertions.assertThat;`. A **static import** lets you write `assertThat(...)` instead of `Assertions.assertThat(...)`. The checks come from AssertJ, not from JUnit's own `Assertions` class (`assertj.md`).

### Where tests run from

Gradle runs the tests with the project folder as the working directory. That is why `BenchCliTest`, `BackendCliTest` and `SlowClientsTest` can read `config/bench.properties`, `config/backend-local.properties` and the slow-client settings by those relative paths (`java-properties.md`). The tests that need a certificate read the folder from the system property `seatwave.tls.directory`, and the tests that check NGINX read `seatwave.nginx.directory`; only the Gradle `test` task sets them (`gradle.md`).

### The build wiring

`testImplementation(libs.junit.jupiter)` makes the annotations and the engine available. `testRuntimeOnly(libs.junit.platform.launcher)` adds the launcher. `useJUnitPlatform` tells the `test` task to use the Platform. The BOM `platform(libs.junit.bom)` fixes every JUnit library at 6.1.3 (`gradle.md`).

## Try it yourself

All ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run all tests and count the results:

```
./gradlew --offline cleanTest test --console=plain | grep -c PASSED
```

   Expected: `119`. (`cleanTest` forces the tests to run again instead of being skipped as up to date.)

2. Run one class:

```
./gradlew --offline cleanTest test --tests '*PhaseTimingsTest' --console=plain
```

   Expected: five lines ending in `PASSED`, then `BUILD SUCCESSFUL in 1s` and `7 actionable tasks: 3 executed, 4 up-to-date`.

3. Run the methods of one class that match a pattern and count them:

```
./gradlew --offline cleanTest test --tests '*ClientAddressTest.*' --console=plain | grep -c PASSED
```

   Expected: `15`.

4. Count the annotations at the start of a line:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Expected: `1 @AfterAll`, `2 @AfterEach`, `1 @BeforeAll`, `2 @BeforeEach`, `4 @Override`, `1 @Tag` and `125 @Test`. (`@TempDir` sits inside a method signature, so this pattern does not show it; `grep -rn "@TempDir" src/test | wc -l` finds the 15 uses.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 25 files has a `tests="..."` count after a full run. They add up to 119. The largest are `BackendServerTest` with 20 and `ClientAddressTest` with 15.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own input and starts its own server where it needs one, on a port the operating system picks.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*report*'` also matches the package name `report`, so it runs every test in that package. Use the full class name when you want one class.
- **Running a test outside Gradle.** `System.getProperty("seatwave.tls.directory")` is `null` unless the Gradle `test` task sets it.
- **Starting a server and never stopping it.** A server left running after a test holds its port. The tests close every server in an `@AfterEach` method or a `try` block that closes it automatically.
- **Assuming `check` ran every test.** Six tests are tagged `stack` and run only through `stackTest`.

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, passes it as the parameter, and deletes it afterwards.
4. *Why must a `@BeforeAll` method be `static`?* It runs before any instance of the test class exists.
5. *How do the 125 `@Test` methods become 119 results in `check`?* The six methods of `StackInvariantTest` carry the tag `stack`, and `test` excludes it.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Tagging and filtering tests: <https://docs.junit.org/6.1.3/writing-tests/tagging-and-filtering.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>
