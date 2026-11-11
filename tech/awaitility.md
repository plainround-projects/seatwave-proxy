# Awaitility 4.3.0 (waiting for a condition in a test)

## What it is

Some things a test checks happen **later**: a server has not yet noticed a request, a counter has not yet gone up. A test that checks too early fails for no good reason. A test that waits a fixed time with `Thread.sleep(2000)` is slow when the thing is quick and still fails when the machine is busy.

**Awaitility** is a small library that solves this. You say what must become true and for how long you are willing to wait. Awaitility checks the condition again and again, and returns the moment it is true. If the time runs out first, it throws an exception that says the condition was not fulfilled. The fixed `sleep` is replaced by a **poll**: ask, wait a short moment, ask again.

## Why this repository uses it

SeatWave's overload behavior is the thing under test: with one worker thread and a queue of one, a third request must be refused. The test needs the one worker to be busy **before** it sends the extra requests, and it needs the refusal to have **happened** before it checks the counter. Neither moment is known in advance. The test does not guess; it reads SeatWave's own metrics page until the right number appears, with a generous limit of 10 seconds that is never reached on a healthy run.

## Where it appears

| Path | What it does with Awaitility |
| --- | --- |
| `gradle/libs.versions.toml` | `awaitility = "4.3.0"` and the library entry (`toml-version-catalog.md`). |
| `build.gradle.kts` | `testImplementation(libs.awaitility)` (`gradle.md`). |
| `gradle.lockfile` | Locks `org.awaitility:awaitility:4.3.0` and its dependency `org.hamcrest:hamcrest:2.1` on both test classpaths (`dependency-locking.md`). |
| `src/test/java/.../backend/BackendServerTest.java` | The only user: two `await()` calls (lines 306 and 315 to 317). |

I searched `src` for `awaitility` and `await()`: nothing else uses it. Other tests wait by blocking on a real reply with a timeout (`HttpClient`'s own timeout, `CompletableFuture.join()`) and need no polling.

## How it works underneath

1. `await()` is a static method (imported with `import static org.awaitility.Awaitility.await;`). It returns a **condition factory** that holds the settings of this wait.
2. `.atMost(duration)` sets the longest wait. If you leave it out, the default is 10 seconds.
3. `.until(callable)` takes a function that returns `true` or `false`. A **lambda** (`() -> ...`) is a short anonymous function with no arguments. Awaitility calls it on a background thread, first after a short delay, then at a fixed interval, until it returns `true` or the time is up.
4. The defaults are a first check after 100 milliseconds and then one check every 100 milliseconds. I measured it with the 4.3.0 jar: a condition that was true at once returned after 112 ms; a condition that became true on its fourth call returned after 434 ms (checks at about 100, 200, 300 and 400 ms). Timings differ on every run.
5. If the time runs out, it throws `org.awaitility.core.ConditionTimeoutException`. I got the message `Condition with Lambda expression in A was not fulfilled within 350 milliseconds.` for a condition that was always false and a limit of 350 ms.
6. Awaitility depends on **Hamcrest** (a library of matchers), which is why `org.hamcrest:hamcrest:2.1` is in the lock file. This repository does not use Hamcrest itself.

## Walkthrough of this repository's use

### The imports and the constant

```java
import static org.awaitility.Awaitility.await;
```

An `import static` makes a static method usable by its short name, so the test writes `await()` instead of `Awaitility.await()`.

```java
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
```

One limit for every wait in the class: `Duration.ofSeconds(10)`, a `Duration` object of ten seconds. It is a ceiling, not an expected time.

### The test

```java
  @Test
  void refusesARequestWhenEveryWorkerIsBusyAndTheQueueIsFull() {
    try (TestBackend tiny =
        TestBackend.start(
            List.of("192.0.2.1"),
            new BackendSettings.Timing(
                Duration.ofSeconds(3), Duration.ofMillis(100), Duration.ofMillis(350)),
            1,
            1)) {
      CompletableFuture<HttpResponse<String>> holding =
          CompletableFuture.supplyAsync(
              () -> tiny.get(tiny.plain("/v1/events/1/seat-changes?after=1")));
      await().atMost(TIMEOUT).until(() -> tiny.metrics().contains("seatwave_workers_busy 1"));
      // The single worker is held for 3 s: one more request waits in the queue, the next is
      // refused.
      CompletableFuture<Void> more =
          CompletableFuture.allOf(
              CompletableFuture.runAsync(() -> tryGet(tiny, "/health")),
              CompletableFuture.runAsync(() -> tryGet(tiny, "/health")),
              CompletableFuture.runAsync(() -> tryGet(tiny, "/health")));

      await()
          .atMost(TIMEOUT)
          .until(() -> !tiny.metrics().contains("seatwave_rejected_queue_full_total 0"));

      assertThat(holding.join().statusCode()).isEqualTo(200);
      more.join();
      // A refusal closes one connection; the server itself keeps serving.
      assertThat(tiny.get(tiny.plain("/health")).statusCode()).isEqualTo(200);
    }
  }
```

- `try (TestBackend tiny = TestBackend.start(...)) { ... }` is a **try-with-resources**: the test backend is closed at the end, whatever happens. The arguments build a SeatWave with `List.of("192.0.2.1")` as the trusted proxies, slow-route timings of 3 s, 100 ms and 350 ms, **one** worker thread and a queue capacity of **one**. `192.0.2.1` is an address from the range reserved for documentation, so it is never the test's own address.
- `CompletableFuture<HttpResponse<String>> holding = CompletableFuture.supplyAsync(() -> tiny.get(tiny.plain("/v1/events/1/seat-changes?after=1")));` starts a request on another thread and returns at once with a **future**, a handle for a result that is not ready yet. The request is a long poll: with nothing to report, SeatWave holds it for the configured 3 seconds, so it keeps the single worker busy.
- `await().atMost(TIMEOUT).until(() -> tiny.metrics().contains("seatwave_workers_busy 1"));` is the first wait. It reads SeatWave's metrics text (`tiny.metrics()`) every 100 ms until the text contains `seatwave_workers_busy 1`, which means the one worker is now running the long poll. Only then does the test go on. The metrics come from the operations listener, which has its own thread and answers even when the worker is busy (`java-backend-walkthrough-2.md`).
- The comment on lines 307 and 308 says what the next block does: "The single worker is held for 3 s: one more request waits in the queue, the next is refused." Three `CompletableFuture.runAsync(() -> tryGet(tiny, "/health"))` calls send three requests at once from other threads. One takes the queue's only place and two are refused. `CompletableFuture.allOf(...)` joins the three into one future.
- `await().atMost(TIMEOUT).until(() -> !tiny.metrics().contains("seatwave_rejected_queue_full_total 0"));` is the second wait, split over three lines. It waits until the refusal counter is **no longer** `0`, that is, until at least one request was refused. The `!` means "not".
- `assertThat(holding.join().statusCode()).isEqualTo(200);` waits for the long poll to finish (`join` blocks until the future has a value) and checks that it was answered normally. `more.join();` waits for the three small requests. The last assertion proves that a refusal closes one connection but the server keeps serving: a fresh `/health` returns 200.

### The helper that tolerates a refusal

```java
  private static void tryGet(TestBackend tiny, String path) {
    try {
      tiny.get(tiny.plain(path));
    } catch (java.io.UncheckedIOException refused) {
      // The refused request's connection is closed by the server: that is the behavior under test.
      assertThat(refused).hasCauseInstanceOf(java.io.IOException.class);
    }
  }
```

A refused request is not an error of the test: the server closes the connection and the client sees an `IOException` wrapped in an `UncheckedIOException`. The helper accepts exactly that and checks the cause with AssertJ (`assertj.md`). Any other exception passes through and fails the test.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Find the only user:

```
grep -rn "await()" src
```

   Expected: lines 306 and 315 of `BackendServerTest.java` (and `HoldLedgerTest.java` line 33 and `BackendCli.java` line 44, which match the text `await()` but call `CountDownLatch.await()`, a different method with the same name).

2. Run just that test class:

```
./gradlew --offline test --tests '*BackendServerTest' --console=plain
```

   Expected: one `PASSED` line per test, including `BackendServerTest > refusesARequestWhenEveryWorkerIsBusyAndTheQueueIsFull() PASSED`, and `BUILD SUCCESSFUL`.

3. Measure the polling yourself. Save this as `A.java` in an empty folder:

```
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class A {
  public static void main(String[] args) {
    long t0 = System.nanoTime();
    await().atMost(Duration.ofSeconds(5)).until(() -> true);
    System.out.println("already true: " + (System.nanoTime() - t0) / 1_000_000 + " ms");

    AtomicInteger calls = new AtomicInteger();
    long t1 = System.nanoTime();
    await().atMost(Duration.ofSeconds(5)).until(() -> calls.incrementAndGet() >= 4);
    System.out.println(
        "true on call 4: " + calls.get() + " calls, " + (System.nanoTime() - t1) / 1_000_000 + " ms");

    try {
      await().atMost(Duration.ofMillis(350)).until(() -> false);
    } catch (Exception e) {
      System.out.println(e.getClass().getName());
      System.out.println(e.getMessage());
    }
  }
}
```

   Run it with the two jars on the class path:

```
CP=$(find ~/.gradle -name 'awaitility-4.3.0.jar'|head -1):$(find ~/.gradle -name 'hamcrest-2.1.jar'|head -1)
java -cp "$CP" A.java
```

   Expected, four lines: `already true: 112 ms`, `true on call 4: 4 calls, 434 ms`, `org.awaitility.core.ConditionTimeoutException` and `Condition with Lambda expression in A was not fulfilled within 350 milliseconds.` The two times differ on every run, but stay near 100 ms and 400 ms.

## Mistakes people make

- **Using `Thread.sleep` to wait for something.** It is either too short (a flaky test) or too long (a slow test). Poll for the real condition.
- **A wait with no upper limit.** A condition that never becomes true would hang the build. `atMost` (and the default of 10 seconds) prevents it.
- **Waiting on a condition that was true before the action.** The wait returns at once and proves nothing. Here the first wait looks for `seatwave_workers_busy 1`, which cannot be true until the long poll is running.
- **Polling something expensive.** The default interval is 100 ms. The metrics page is cheap, so it is fine here.
- **Reading a `ConditionTimeoutException` as a bug in Awaitility.** It says the system did not reach the state in time; look at the system.
- **Confusing `await()` with `CountDownLatch.await()`.** The names match; the libraries do not.

## Check yourself

1. *What does `await().atMost(TIMEOUT).until(...)` do?* It calls the condition every 100 ms until it returns `true` or ten seconds pass.
2. *What happens when the time runs out?* It throws `ConditionTimeoutException`.
3. *Why does the test wait for `seatwave_workers_busy 1` before sending more requests?* The extra requests must arrive while the one worker is busy; otherwise they would simply be served.
4. *Why is waiting better than `Thread.sleep(2000)`?* It returns as soon as the condition is true, and it does not fail on a slow machine until the limit.
5. *Where does the Hamcrest jar in the lock file come from?* Awaitility needs it; no code of this repository imports it.

## Further reading

- Awaitility project and usage guide (4.x): <https://github.com/awaitility/awaitility/wiki/Usage>
- Awaitility on Maven Central at the pinned version: <https://central.sonatype.com/artifact/org.awaitility/awaitility/4.3.0>
- `java.util.concurrent.CompletableFuture` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html>
