# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers one question: when the tests ran, which lines of the program were executed at least once? A line that no test ever ran is a line whose behavior nobody has checked. **JaCoCo** (Java Code Coverage) is the tool that measures it for Java. It does not tell you whether the tests are good, only which code they touched.

JaCoCo works with a **Java agent**: a small jar that the Java Virtual Machine (JVM) loads before the program starts. The agent watches classes as they are loaded and records which parts run.

## Why this repository uses it

The build has a measured floor: line coverage must be at least 0.80, and the build fails below it. That keeps the tests honest as the code grows. The README reports "line coverage 91.8 percent (1,937 of 2,111 lines)" for 119 tests, and I measured the same numbers on 11 November 2026.

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`jacoco`), pins its version, wires the report after the tests, sets the 0.80 rule and adds it to `check`. It also switches on the XML report. |
| `gradle/libs.versions.toml` | `jacoco = "0.8.16"`. |
| `gradle.lockfile` | Locks `jacocoAgent` and `jacocoAnt` (`dependency-locking.md`). |
| `build/jacoco/test.exec` | The raw recording of one test run (a binary file). |
| `build/reports/jacoco/test/html/index.html` | The readable report. |
| `build/reports/jacoco/test/jacocoTestReport.xml` | The same numbers as XML, written because of `xml.required = true`. |

## How it works underneath

1. `./gradlew test` starts a new JVM for the tests and passes it the option `-javaagent:` with the JaCoCo agent jar (the `jacocoAgent` configuration). Gradle does this by itself once the `jacoco` plugin is on.
2. As each class loads, the agent inserts small recording instructions into its bytecode, in memory only. The files on disk do not change.
3. When the JVM ends, the agent writes everything it recorded to `build/jacoco/test.exec`.
4. The task `jacocoTestReport` (`finalizedBy` on `test`, so it always runs afterwards) reads `test.exec` plus the compiled classes in `build/classes/java/main` and writes the HTML report and, here, the XML report. It uses the `jacocoAnt` configuration.
5. The task `jacocoTestCoverageVerification` reads the same data and applies the rules. If a rule is violated, the task fails and so does `check`.

JaCoCo counts several things. The four that matter here:
- **Instructions**: single bytecode instructions. The most fine-grained number.
- **Branches**: each outcome of an `if`, a `switch` or a `&&`. A branch is covered when both outcomes were taken.
- **Lines**: a source line counts as covered if at least one of its instructions ran. This is the counter the build's rule uses.
- **Methods** and **classes**: counted as covered if any part of them ran.

Only code under `src/main/java` is measured. The test classes themselves are not. The tests tagged `stack` do not run in `./gradlew check`, so they add nothing to these numbers either.

## Walkthrough of this repository's use

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`toolVersion = libs.versions.jacoco.get()` fixes JaCoCo at 0.8.16. Without it, Gradle would use its own default.

```kotlin
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    dependsOn(testCertificates)
    systemProperty("seatwave.tls.directory", layout.buildDirectory.dir("tls").get().asFile.absolutePath)
    systemProperty("seatwave.nginx.directory", file("nginx").absolutePath)
    inputs.dir("nginx")
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}
```

`finalizedBy(tasks.jacocoTestReport)` is what writes the report after every test run. The other lines of this block are explained in `gradle.md`.

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

The task `jacocoTestReport` writes the HTML report by default. `xml.required = true` adds an XML file, `build/reports/jacoco/test/jacocoTestReport.xml`. The comment above the block in the build file says why: the XML gives the exact covered and missed line counts that the README quotes. Near its end the XML holds one element per counter: `<counter type="LINE" missed="174" covered="1937"/>` (the last such element is the total of the whole project; the ones before it belong to single packages, classes and methods).

```kotlin
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

`counter = "LINE"` picks the line counter. `minimum = "0.80".toBigDecimal()` is the lowest accepted ratio of covered lines to all lines. The comment above it names the untested remainder: the command-line entry points. `tasks.check { dependsOn(...) }` makes the rule part of `check`. When I raised the minimum to 0.95 in a scratch copy, the build failed with `Rule violated for bundle seatwave-proxy: lines covered ratio is 0.91, but expected minimum is 0.95`.

### The real numbers (Temurin 25.0.2, 119 tests)

I ran `./gradlew clean check` in a scratch copy and read `build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report. The last row of the HTML table, "Total", reads:

| Counter | Missed | Total | Covered |
| --- | --- | --- | --- |
| Instructions | 726 | 10,820 | 93 percent (10,094 covered) |
| Branches | 112 | 748 | 85 percent (636 covered) |
| Lines | 174 | 2,111 | 1,937 lines, 91.8 percent |
| Methods | 12 | 336 | 324 |
| Classes | 0 | 89 | 89 |
| Complexity | 116 | 716 | 600 |

The rule needs 0.80 of 2,111 lines, which is 1,688.8, so at least 1,689 covered lines. The build has 1,937. **The margin is 248 lines.**

Per package, the missed lines (from the XML report, class by class):

| Package | Missed lines | Total lines | Where |
| --- | --- | --- | --- |
| `backend` | 41 | 745 | `SeatWaveRoutes` 16, `BackendCli` 13 (its `main`, lines 31 to 45), `BackendServer` 7, `StaticFiles` 3, `HoldKeys` 1, `BackendMetrics` 1. |
| `bench` | 37 | 379 | `BenchCli` 14, `LoadRun` 10, `ContainerCounters` 5, `LoadRun$Tally` 3, and one each in `BenchSettings`, `BenchReport`, `BenchRow`, `LoadResult` and `RawResponse`. |
| `slow` | 28 | 219 | `SlowClients` 21, `SlowClientsCli` 7. |
| `probe` | 28 | 138 | `ProbeCli` 26, `ProbeSettings` 2. |
| `json` | 22 | 54 | `JsonCodec` 22: the refusal branches of `read` (see below). |
| `report` | 6 | 251 | `ReportCli` 4, `Runs` 1, `CurlProblem` 1. |
| `logs` | 5 | 101 | `LogStats` 2, `LogStatsCli` 3. |
| `tls` | 4 | 38 | `TlsFiles` 4. |
| `config` | 3 | 68 | `SettingsFiles` 3. |
| `forwarded` | 0 | 118 | Every line ran. |

The six classes that hold a `main` method are `BackendCli`, `BenchCli`, `LogStatsCli`, `ProbeCli`, `ReportCli` and `SlowClientsCli`. Their missed lines add up to 67 of the 174 (13 + 14 + 3 + 26 + 4 + 7), and the build file's comment on the rule names them: "the command-line entry points are the untested remainder". Most of those classes also have a `run` or `start` method that the tests call directly for the bad-usage and bad-file paths; the lines that run a real measurement against the stack, and the one-line `main`, are not reached. The `Classes` row shows 0 missed of 89: every class ran at least in part.

`JsonCodec` is different. Its 22 missed lines (lines 74 to 103 and 117 to 129 of the source) are the branches that refuse a bad request body: an empty body, a body that is not an object, data after the object, an unknown field, a wrong type, a number out of range and malformed JSON. I read the XML and the source to confirm it: the one success path of `read` ran, and each `return Result.error(...)` and each `catch` did not. The other missed lines are mostly error paths too, for example in `SlowClients` and `SeatWaveRoutes`; I did not read all of them one by one.

### Reading the report

Open `index.html`. Each row is a package; click it for its classes, click a class for its methods, click a method for the source with colors: green lines ran, red lines never ran, yellow lines ran only partly (a branch outcome was never taken).

## Try it yourself

All commands ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run the gate and the report:

```
./gradlew clean check
```

   Expected: 119 `PASSED` lines (with `--console=plain`), `> Task :jacocoTestCoverageVerification`, `BUILD SUCCESSFUL in 10s` and `10 actionable tasks: 10 executed`.

2. Print the totals from the HTML report:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  726 of 10,820  93%  112 of 748  85%  116  716  174  2,111  12  336  0  89`.

3. Print the total line counter from the XML report:

```
grep -o '<counter type="LINE"[^>]*>' build/reports/jacoco/test/jacocoTestReport.xml | tail -1
```

   Expected: `<counter type="LINE" missed="174" covered="1937"/>`.

4. Print the totals of the `probe` package:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/io.plainround.seatwave.proxy.probe/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  92 of 631  85%  5 of 42  88%  6  42  28  138  1  21  0  5`. The `probe` package has 138 lines, 28 of them missed, and no missed class.

5. See the recording and the report folders:

```
ls build/jacoco build/reports/jacoco/test build/reports/jacoco/test/html
```

   Expected: `test.exec`; `html` and `jacocoTestReport.xml`; and `index.html`, `jacoco-sessions.html`, `jacoco-resources` and one folder per package (`io.plainround.seatwave.proxy.backend`, `.bench`, `.config`, `.forwarded`, `.json`, `.logs`, `.probe`, `.report`, `.slow` and `.tls`).

6. Open the report in your browser (on a Mac): `open build/reports/jacoco/test/html/index.html`.

## Mistakes people make

- **Treating 80 percent as a goal.** Coverage shows which lines never ran. A line that ran can still be unchecked. The tests here also assert results.
- **Chasing 100 percent for `main` methods.** The build documents them as the untested remainder instead.
- **Running only `./gradlew test` and expecting the rule to be checked.** The rule runs in `jacocoTestCoverageVerification`, which `check` runs.
- **Reading an old report.** The report is rewritten after each `test` run; run the tests first.
- **Forgetting the margin.** There are 248 lines of headroom now, but every untested line uses some of it, so new code still needs tests in the same change.
- **Deleting a test to make a number look right.** The check then fails for the right reason.
- **Reading a high total as proof that every rule is tested.** A whole package can hide behind it: here the refusal branches of `JsonCodec` are red while the project total is 91.8 percent.

## Check yourself

1. *What does the line counter measure?* The share of source lines in which at least one instruction ran during the tests.
2. *Which task checks the rule and which writes the reports?* `jacocoTestCoverageVerification` checks; `jacocoTestReport` writes the HTML and the XML.
3. *Why is coverage 91.8 percent and not 100?* The `main` methods of the command-line classes are never called by a test (67 of the 174 missed lines), and the rest are error paths that a test would have to break something on purpose to reach.
4. *What makes the rule part of `check`?* `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }`.
5. *How many lines of headroom are there?* 248: 1,937 covered against 1,689 required.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/>
- JaCoCo counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- Gradle JaCoCo plugin (9.9.1): <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
