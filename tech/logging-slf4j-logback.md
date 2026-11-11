# Logging with SLF4J 2.0.20 and Logback 1.6.6

## What it is

A **log** is a text record that a program writes about what it is doing: "held seat A-1", "a trusted proxy sent headers that cannot be read". One line per event, with the time. Logs are how a person finds out afterwards what a running server did.

Two libraries cooperate here. **SLF4J** (Simple Logging Facade for Java) is a **facade**: a small front door with the calls the code uses (`LOG.info(...)`) and no ability to write anything. **Logback** is the **engine** behind the door: it decides which lines are kept, how each line looks and where it goes. The code talks only to SLF4J. If the team wanted another engine, no line of code would change.

Four ideas are needed:
- A **logger** is an object with a name, usually the name of the class that owns it. The code asks SLF4J for one: `LoggerFactory.getLogger(BackendServer.class)`.
- A **level** says how important a line is. The five levels from least to most important are `TRACE`, `DEBUG`, `INFO`, `WARN` and `ERROR`. Logback keeps a line only if its level is at least the configured level.
- An **appender** is a destination: the console, a file, a network socket.
- The **MDC** (mapped diagnostic context) is a small map of text values that belongs to the current thread. A pattern can print a value from it on every line. The code puts a request's id there, and every line written while that request runs carries the id.

## Why this repository uses it

The module is about a proxy in front of a server, and a question the proxy raises is "which line in the proxy's log and which line in SeatWave's log belong to the same request?" NGINX gives each request an id (`$request_id`) and passes it on in the header `X-Request-ID`. SeatWave puts that id in the MDC, so every SeatWave log line of a request shows the same id as the proxy's access-log line (`nginx-config-walkthrough-1.md`). The comment at the top of `logback.xml` states this goal: "so a proxy log line and a SeatWave log line can be joined."

A second reason is that the logs go to standard error, so a command's report on standard output stays clean (`java-bench-walkthrough-2.md` for `BenchCli`, `java-report-logs-walkthrough-1.md` for `ReportCli` and `java-report-logs-walkthrough-2.md` for `LogStatsCli`).

## Where it appears

| Path | What it does with logging |
| --- | --- |
| `gradle/libs.versions.toml` | `slf4j = "2.0.20"` and `logback = "1.6.6"`, with the entries `slf4j-api` and `logback-classic` (`toml-version-catalog.md`). |
| `build.gradle.kts` | `implementation(libs.slf4j.api)` and `runtimeOnly(libs.logback.classic)` (`gradle.md`). |
| `gradle.lockfile` | Locks `slf4j-api` on all four classpaths, `logback-classic` and `logback-core` on the two run classpaths (`dependency-locking.md`). |
| `src/main/resources/logback.xml` | Logback's configuration: one console appender, one pattern, root level `INFO`. |
| `src/main/java/.../backend/BackendServer.java` | Creates a logger, puts and clears the MDC value, logs at `WARN` and `DEBUG`. |
| `src/main/java/.../backend/SeatWaveRoutes.java` | Creates a logger and logs one `INFO` line per hold. |

Only these two classes use logging. I searched `src` for `slf4j`, `LoggerFactory` and `MDC` and found no other file.

## How it works underneath

1. The first time the code calls `LoggerFactory.getLogger(...)`, SLF4J looks for an engine. SLF4J 2.x uses the JDK's **service loader**: it asks the JVM for every class listed in a file named `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` on the classpath. The Logback jar has such a file, and it names `ch.qos.logback.classic.spi.LogbackServiceProvider` (I read it with `unzip -p`). With no engine on the classpath, SLF4J would print a warning and drop every line.
2. Logback then looks for its configuration: a file named `logback.xml` on the classpath. This repository's file is in `src/main/resources`, which Gradle copies into the jar and onto the classpath of the tests. If Logback finds no file, it uses a built-in default that writes every level from `DEBUG` up to standard output, with a different pattern.
3. A call such as `LOG.info("held {} for {} s", seat, 300)` first checks the level. The `{}` markers are replaced by the arguments only if the line will be kept, so a dropped `DEBUG` line costs almost nothing.
4. A kept line goes to the appender. The appender's **encoder** turns the event into text with the **pattern**, and the appender writes it.
5. The MDC is stored per thread. A server that reuses threads (this one uses a pool of 64) must clear the MDC at the end of each request, or the next request on that thread would show the old id.

## Walkthrough of this repository's use

### `src/main/resources/logback.xml`

```xml
<configuration>
  <!-- One line per business event with its correlation id: NGINX's $request_id when the request
       came through the trusted proxy, so a proxy log line and a SeatWave log line can be joined. -->
  <!-- Log lines go to standard error, so a tool's report on standard output stays clean. -->
  <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="console"/>
  </root>
</configuration>
```

- `<configuration>` is the root element of a Logback file. XML is text made of **elements**: a start tag `<name>`, content, and an end tag `</name>`. An element with no content can be written in one piece, with a `/` before the `>`, as in `<appender-ref ref="console"/>`.
- Lines 2 to 4 are two XML comments (`<!-- ... -->`). The first says what each line is for: "One line per business event with its correlation id: NGINX's $request_id when the request came through the trusted proxy, so a proxy log line and a SeatWave log line can be joined." A **correlation id** is an identifier that ties together the records of one request in different systems. The second says: "Log lines go to standard error, so a tool's report on standard output stays clean."
- `<appender name="console" class="ch.qos.logback.core.ConsoleAppender">` defines an appender called `console`, of the Logback class `ConsoleAppender`.
- `<target>System.err</target>` makes the appender write to **standard error**, the second output stream of a program (standard output is the first). A command-line tool prints its result on standard output and its log on standard error, so `> result.txt` captures only the result.
- `<encoder>` holds the settings that turn an event into text, and `<pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>` is the layout. Each `%` item means:

| Item | Meaning |
| --- | --- |
| `%d{HH:mm:ss.SSS}` | The time of the event, hours, minutes, seconds and milliseconds, for example `05:58:46.107`. |
| `%-5level` | The level, padded on the right to five characters, so `INFO` becomes `INFO ` and the columns line up. |
| `[%X{correlationId:-none}]` | The MDC value named `correlationId` between square brackets. The `:-none` after the name is a default: when the MDC has no such value, print `none`. |
| `%logger{0}` | The logger name shortened to its last part. The `0` means "no package at all", so `io.plainround.seatwave.proxy.backend.SeatWaveRoutes` prints as `SeatWaveRoutes`. |
| ` - ` | A literal separator. |
| `%msg` | The message, after the `{}` markers are filled in. |
| `%n` | A line break. |

- `<root level="INFO">` configures the **root logger**, the ancestor of every logger. Its level is `INFO`: lines at `INFO`, `WARN` and `ERROR` are kept, and `DEBUG` and `TRACE` are dropped.
- `<appender-ref ref="console"/>` connects the root logger to the appender named `console`. Without this line, nothing would be written anywhere.
- `</configuration>` ends the file.

### The loggers and the MDC in `BackendServer`

```java
  private static final Logger LOG = LoggerFactory.getLogger(BackendServer.class);
```

`private static final` means one logger per class, made once. Passing the class gives the logger the class's full name, which `%logger{0}` shortens. These are the imports above it: `org.slf4j.Logger`, `org.slf4j.LoggerFactory` and `org.slf4j.MDC`.

```java
      MDC.put("correlationId", request.correlationId());
```

Inside the request handler, after the request is read and its client address decided, the id goes into the MDC under the key the pattern asks for, `correlationId`. The id is NGINX's `$request_id` (32 hexadecimal characters) when the peer is a trusted proxy and the header is present. For any other peer SeatWave makes its own id, so a client cannot choose what appears in the log (`java-forwarded-walkthrough.md`).

```java
      MDC.clear();
```

This is in the `finally` block of the handler, so it runs after success, after an error and after a client that hung up. It is the guard against the stale id described above.

```java
      LOG.debug("connection closed by the client: {}", clientGone.getMessage());
```

A client that closes the connection while SeatWave is reading or writing is normal, not an error. The line is `DEBUG`, so with the root level at `INFO` it is not written. Nothing else in the file reaches it.

```java
      LOG.warn("a trusted proxy sent forwarding headers that cannot be read; using its address");
```

A `WARN` line is kept. It marks the case where a trusted proxy sent a forwarding header that could not be parsed; SeatWave falls back to the proxy's own address and counts the event.

```java
      LOG.warn("workers still running after {} s", SHUTDOWN_WAIT_SECONDS);
```

At shutdown, if requests are still running after five seconds, this line says so. The `{}` is filled with the constant.

### The business event in `SeatWaveRoutes`

```java
    LOG.info(
        "held {} for 300 s; client {}", body.seat(), request.client().address().getHostAddress());
```

One `INFO` line per successful hold: the seat, and the client address that the client-address rule decided. A log line like this is called a **business event**: it records something the business cares about, not a technical detail.

### What it looks like

I started SeatWave from `./gradlew serve` on ports the system chose, sent one `POST /v1/holds` with the header `X-Request-ID: 0123456789abcdef0123456789abcdef`, and read standard error. The peer was `127.0.0.1`, which the config trusts, so the id was believed:

```
05:58:46.107 INFO  [0123456789abcdef0123456789abcdef] SeatWaveRoutes - held A-1 for 300 s; client 127.0.0.1
```

Reading it from the left: the time, `INFO ` padded, the correlation id in brackets, the short logger name, the separator, and the message. A second request without the header got an id that SeatWave made itself, and it appeared in the error body as `urn:seatwave:request:<id>`; that request logged nothing at `INFO`, because it was refused before a hold was made.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Check that nothing but two classes uses the logging API:

```
grep -rln "LoggerFactory\|MDC" src
```

   Expected: `src/main/java/io/plainround/seatwave/proxy/backend/BackendServer.java` and `src/main/java/io/plainround/seatwave/proxy/backend/SeatWaveRoutes.java`.

2. See the engine registration inside the Logback jar:

```
unzip -p "$(find ~/.gradle -name 'logback-classic-1.6.6.jar' | head -1)" META-INF/services/org.slf4j.spi.SLF4JServiceProvider
```

   Expected: `ch.qos.logback.classic.spi.LogbackServiceProvider`.

3. Watch the pattern work with a tiny program. Save this as `L.java` in an empty folder:

```
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class L {
  private static final Logger LOG = LoggerFactory.getLogger(L.class);

  public static void main(String[] args) {
    LOG.debug("a debug line");
    LOG.info("no id yet, {} seats", 3);
    MDC.put("correlationId", "abc123");
    LOG.info("held {} for {} s", "A-1", 300);
    LOG.warn("a warning");
    MDC.clear();
    LOG.info("after clear");
  }
}
```

   Copy `src/main/resources/logback.xml` into a folder named `cfg` beside it, then run it with the folder first on the classpath and the three jars from Gradle's cache after it:

```
CP=$(find ~/.gradle -name 'slf4j-api-2.0.20.jar'|head -1):$(find ~/.gradle -name 'logback-classic-1.6.6.jar'|head -1):$(find ~/.gradle -name 'logback-core-1.6.6.jar'|head -1)
java -cp "cfg:$CP" L.java 2>err.txt; cat err.txt
```

   Expected: nothing on standard output, and in `err.txt` four lines such as `06:00:08.598 INFO  [none] L - no id yet, 3 seats`, `06:00:08.600 INFO  [abc123] L - held A-1 for 300 s`, `06:00:08.600 WARN  [abc123] L - a warning` and `06:00:08.600 INFO  [none] L - after clear`. The times differ on every run. There is no `DEBUG` line, because the root level is `INFO`. (Keep the `cfg` folder first. I ran the same command twice more on 11 November 2026, with Temurin 25.0.2, the same three jars and the same folder: with `cfg:` first it printed the lines above; with `:cfg` last it stopped at once with `java.lang.NoClassDefFoundError: ch/qos/logback/core/joran/spi/JoranException`, although that class is in `logback-core-1.6.6.jar`, which was on the class path both times. I did not find the cause; the observation is only that the order of the entries matters for this single-file `java L.java` run.)

4. Run the same program without the configuration file:

```
java -cp "$CP" L.java 2>/dev/null
```

   Expected: five lines on standard output in Logback's default layout, such as `06:00:08.895 [main] DEBUG L -- a debug line`, now including the `DEBUG` line and the thread name, and without the correlation id.

5. Start SeatWave and make it log a hold, as in the walkthrough: run `./gradlew --offline -q serve --args='<your copy of backend-local.properties with the three ports set to 0>'`, take the plain port from the first line it prints, and send

```
curl -s -X POST http://127.0.0.1:<port>/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "k-0001"' -H 'X-Request-ID: 0123456789abcdef0123456789abcdef' -d '{"eventId":1,"seat":"A-1","customer":"Sam"}'
```

   Expected: a `201 Created` answer with a hold id, and on SeatWave's standard error the `held A-1 for 300 s; client 127.0.0.1` line with your id in brackets. Stop the server with Ctrl+C.

## Mistakes people make

- **Logging with `System.out.println`.** It has no level, no time and no switch to turn it off, and it mixes with a tool's real output. Here the log goes to standard error through a logger.
- **Building the message with `+`.** `"held " + seat` makes the text even when the line is dropped. The `{}` form makes it only when the line is kept.
- **Forgetting to clear the MDC.** On a reused thread the next request would show the previous id. The `finally` block with `MDC.clear()` prevents it.
- **Trusting a client's request id.** The id is believed only from a trusted proxy, so a client cannot write what it likes into the log (a log-forging attack).
- **Logging a secret.** The lines here carry a seat, an address and an id, never the key store password or a customer name.
- **Putting the engine on the compile classpath.** Logback is `runtimeOnly`, so the code cannot depend on an engine class by accident.
- **Expecting `DEBUG` lines.** The root level is `INFO`, so the `DEBUG` line in `BackendServer` is silent unless you change `logback.xml`.

## Check yourself

1. *What is the difference between SLF4J and Logback?* SLF4J is the front door the code calls; Logback is the engine that decides what is kept, how it looks and where it goes.
2. *Why does the pattern print `[none]` for some lines?* `%X{correlationId:-none}` prints the MDC value, and `none` when the thread has no `correlationId`.
3. *Why does the appender write to `System.err`?* So a tool's result on standard output stays clean.
4. *Why does the handler call `MDC.clear()` in a `finally` block?* The MDC belongs to the thread, threads are reused, and the id must not leak into the next request, even after an error.
5. *Which lines does the root level `INFO` drop?* `DEBUG` and `TRACE`.

## Further reading

- SLF4J 2.0 manual: <https://www.slf4j.org/manual.html>
- Logback 1.6 manual, configuration: <https://logback.qos.ch/manual/configuration.html>
- Logback layouts (the pattern items): <https://logback.qos.ch/manual/layouts.html>
- Logback MDC: <https://logback.qos.ch/manual/mdc.html>
- Request ids in NGINX (`$request_id`): <https://nginx.org/en/docs/http/ngx_http_core_module.html#var_request_id>
