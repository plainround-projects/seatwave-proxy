# Technology notes for seatwave-proxy

These notes teach every technology this repository uses, from zero, through this repository's own files. They assume you have never seen a terminal, a proxy, Docker or Java. Each file follows the same nine headings: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of this repository's use, try it yourself, mistakes people make, check yourself, and further reading. This index is the one file without them.

The repository is the hands-on project of Module 9, "Proxies", of a system design course. It serves a ticket-booking case called SeatWave. It puts NGINX 1.30.6 in front of a small SeatWave backend and measures, before and after, what the proxy saves (handshake CPU, bytes, worker threads, repeated static files) and what it costs (one more hop, one more part that can fail). The stack runs in Docker Compose with every port on 127.0.0.1; `./gradlew check` needs no Docker. The code is Java 25 with Gradle, four shell scripts and the NGINX configuration.

## The files

**The language and the libraries**

| File | Role |
|---|---|
| `java.md` | Java 25 from zero: class, package, constructor, record, enum, sealed interface, `switch` with patterns, generics, lambdas, streams, `Optional`, immutable collections, exceptions, try-with-resources, `Locale`, `Duration` and `Clock`, regular expressions, threads and the small concurrency tools, Javadoc, and the minimum Java version of each feature. |
| `result4j.md` | The result4j 1.2.0 library: a `Result` that is either a value or an error, every place it is used, and why expected failures are values here and exceptions are kept for bugs. |
| `jackson.md` | Jackson 2.23.0: JSON text into records and back, and the strict settings of `JsonCodec` that refuse a typo, a wrong type, a duplicate field and a null number. |
| `logging-slf4j-logback.md` | SLF4J and Logback: `logback.xml` line by line, the pattern, the correlation id in the MDC that ties a SeatWave log line to a proxy log line. |

**The data formats**

| File | Role |
|---|---|
| `java-properties.md` | The `.properties` format and all ten settings files under `config/`, key by key, plus the targets file for `curl`. |
| `web-page.md` | The static web page SeatWave serves from `src/main/resources/static/`. |

**The proxy and the stack**

| File | Role |
|---|---|
| `reverse-proxy-concepts.md` | What a reverse proxy is and does: TLS termination, compression, caching, buffering, connection reuse, forwarded headers, timeouts. |
| `nginx.md` | NGINX: the server, its processes and its configuration language. |
| `nginx-config-walkthrough-1.md` | The files under `nginx/`, part 1: the main file and the shared pieces. |
| `nginx-config-walkthrough-2.md` | The files under `nginx/`, part 2: the lab variants, the status page and the sidecar. |
| `docker.md` | Docker: the `Dockerfile` and `.dockerignore`, line by line. |
| `docker-compose.md` | `compose.yaml`, key by key. |
| `shell-scripts-walkthrough.md` | The scripts under `scripts/`: the stack script, the outage script and the two timing scripts. |

**The code walkthroughs**

| File | Role |
|---|---|
| `java-backend-walkthrough-1.md` | The `backend` package, part 1: the data, the rules and the settings (holds, the ledger, idempotency keys, the settings). |
| `java-backend-walkthrough-2.md` | The `backend` package, part 2: metrics, the command line, the server and the routes. |
| `java-forwarded-walkthrough.md` | The `forwarded` package: the client-address rule. |
| `java-config-json-tls-walkthrough.md` | The `config`, `json` and `tls` packages: the settings reader, the JSON codec and the TLS files. |
| `java-bench-walkthrough-1.md` | The `bench` package, part 1: settings, value types and the report table. |
| `java-bench-walkthrough-2.md` | The `bench` package, part 2: sending requests, reading the answers, reading the container counters. |
| `java-slow-clients-walkthrough.md` | The `slow` package: the slow-client lab. |
| `java-probe-walkthrough.md` | The `probe` package: the outage probe. |
| `java-report-logs-walkthrough-1.md` | The `report` package: Module 5's `curl -w` report. |
| `java-report-logs-walkthrough-2.md` | The `logs` package: the access-log reader. |
| `measuring-a-proxy.md` | How the module measures a proxy: what the bench, the probe, the slow clients and the log reader each measure, and how to read the results. |

**The build tool and its files**

| File | Role |
|---|---|
| `gradle.md` | Gradle and `build.gradle.kts`: the plugins, the compiler flags, the test certificate task, the test and stack tasks, the coverage rule and the six program tasks (`serve`, `bench`, `probe`, `slowClients`, `logStats`, `report`). |
| `gradle-wrapper.md` | The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) that downloads the right Gradle. |
| `posix-shell.md` | The `gradlew` script, a POSIX shell script. |
| `windows-batch.md` | The `gradlew.bat` script, a Windows batch file. |
| `toml-version-catalog.md` | `gradle/libs.versions.toml`, the one place for dependency versions. |
| `dependency-locking.md` | `gradle.lockfile` and `settings-gradle.lockfile`, which pin exact versions of 30 jars. |
| `spotless-google-java-format.md` | The formatter that the build enforces. |
| `jacoco.md` | The line-coverage tool and its 80 percent floor; the real coverage is 95.5 percent. |

**The tests**

| File | Role |
|---|---|
| `junit.md` | JUnit: how the test engine finds and runs the tests, and the `stack` tag that keeps Docker out of `check`. |
| `assertj.md` | AssertJ: the fluent assertions used in the tests. |
| `awaitility.md` | Awaitility: waiting for a condition, used once, to test overload. |
| `tests-backend-walkthrough.md` | The tests of the `backend` package, line by line. |
| `tests-forwarded-config-tls-walkthrough.md` | The tests of the `forwarded`, `config`, `json` and `tls` packages. |
| `tests-tools-walkthrough-1.md` | The tests of the tools, part 1: the bench and the slow clients. |
| `tests-tools-walkthrough-2.md` | The tests of the tools, part 2: the probe, the log reader and the curl report. |
| `tests-stack-walkthrough.md` | The tests that read the NGINX and Compose files, and the tagged tests that run against the live stack. |

**Everything else**

| File | Role |
|---|---|
| `editorconfig.md` | `.editorconfig`, the editor settings. |
| `git.md` | Git: `.gitignore` and `.gitattributes`. |
| `gitleaks.md` | Gitleaks: the secret scanner that stands behind `.gitignore`; no file of the repository runs it. |

Five long walkthroughs are split into two files each (`-1` and `-2`), and every note names the part it means.

## Reading order

1. **The language first.** `java.md`. Do not skip it: every walkthrough relies on it. Then `result4j.md`, because many methods return a `Result`.
2. **The idea.** `reverse-proxy-concepts.md`, then `measuring-a-proxy.md`.
3. **The data.** `java-properties.md`, `jackson.md`, `logging-slf4j-logback.md`.
4. **The proxy and the stack.** `nginx.md`, `nginx-config-walkthrough-1.md`, `nginx-config-walkthrough-2.md`, `docker.md`, `docker-compose.md`, `shell-scripts-walkthrough.md`.
5. **The code walkthroughs, in the order the data travels.** `java-config-json-tls-walkthrough.md`, `java-forwarded-walkthrough.md`, `java-backend-walkthrough-1.md` and `-2`, then the tools: `java-bench-walkthrough-1.md` and `-2`, `java-probe-walkthrough.md`, `java-slow-clients-walkthrough.md`, `java-report-logs-walkthrough-1.md` and `-2`. The page itself is in `web-page.md`.
6. **The build tool.** `gradle.md`, `gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`, `toml-version-catalog.md`, `dependency-locking.md`, `spotless-google-java-format.md`, `jacoco.md`.
7. **The tests.** `junit.md`, `assertj.md`, `awaitility.md`, then the `tests-*-walkthrough` files (the tools part is in two files).
8. **The rest.** `editorconfig.md`, `git.md`, `gitleaks.md`.

## If you have ten minutes

1. `reverse-proxy-concepts.md`: read "How it works underneath", for what each proxy feature saves and what it costs.
2. `measuring-a-proxy.md`: read "How it works underneath", for how the module turns a run into a number you can trust.
3. `java.md`: read "How it works underneath" and the sections on records and sealed interfaces, so that the code walkthroughs make sense.

## How to run what the notes describe

The notes of the build files and the libraries were checked on 11 November 2026 with Java 25.0.2 and Gradle through `./gradlew`, on a Mac with an ARM processor (`aarch64`), in a copy of the repository. Timings, ports chosen by the system and random values differ on every run and every machine: compare the shape, not the digits. The commands that need no Docker are:

```text
./gradlew check
./gradlew testCertificates
./gradlew -q serve --args='config/backend-local.properties'
```

`check` runs 166 tests with a line coverage of 95.5 percent (2,108 of 2,208 lines in the final run of version 1.0.1, the run the project's README quotes). `testCertificates` makes the throwaway test certificates in `build/tls`. `serve` starts SeatWave on 127.0.0.1 on ports 20930 to 20932 and runs until you stop it. The stack, the bench, the probe and the slow-client lab need Docker; the notes that describe them say whether their commands were run.

One warning from version 1.0.0 no longer applies: its settings readers accepted a key store path of at most 200 characters, so a checkout in a deep folder, or in a folder with a space, failed two tests. Version 1.0.1 accepts any path without control characters (`java-config-json-tls-walkthrough.md`).

## How these notes were checked

Each walkthrough quotes the source line by line, copied by a script and not retyped. The command `python3 tools/techcov.py <report.json> seatwave-proxy`, run from the folder that holds all the repositories, counts the non-blank, non-comment lines of every source, script and configuration file and checks that each appears in these notes. The notes of the build files, the settings files and `logback.xml` quote every counted line of those files. Everything stated about Gradle, JUnit, AssertJ, Awaitility, Jackson, SLF4J, Logback, result4j and Gitleaks was checked on this machine, by running the command, by running a small program against the real jar, by reading a class file with `javap`, or by reading the jar's own files.
