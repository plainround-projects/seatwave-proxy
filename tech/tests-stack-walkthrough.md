# Tests for the stack: `NginxConfigRulesTest` and `StackInvariantTest`

## What it is

A **test** is a small program that checks that another program behaves as promised and fails loudly if it does not. These two tests are written in Java with JUnit (the test framework, `junit.md`) and AssertJ (the library for readable checks, `assertj.md`). Both guard the same promise, the module's **invariant**, from two sides:

- `NginxConfigRulesTest` reads the configuration **files** (`nginx/`, `compose.yaml`, `Dockerfile`) as text and checks rules about them. It needs no Docker, so it runs in `./gradlew check`.
- `StackInvariantTest` talks to the **running** stack over HTTP and checks what the proxy and SeatWave really do. It needs the stack (`scripts/stack.sh test`) and is never part of `./gradlew check`.

The invariant, from `ARCHITECTURE.md`: SeatWave believes a forwarded client address only from the proxy; the edge overwrites the client address in every proxied location; the proxy never caches a `POST` or a `private` response, and never serves stale.

## Why this repository uses it

A rule that lives only in a comment ("always include the forwarded headers") breaks the first time someone adds a location and forgets. A test turns the rule into a build failure. The first test catches the mistake the moment the file is saved. The second proves that the running system really behaves as the files claim.

## Where it appears

| Path | What it does |
|---|---|
| `src/test/java/io/plainround/seatwave/proxy/nginx/NginxConfigRulesTest.java` | Six tests over the files. Run by `./gradlew test`. |
| `src/test/java/io/plainround/seatwave/proxy/stack/StackInvariantTest.java` | Six tests against the running stack. Run by `./gradlew stackTest`. |
| `build.gradle.kts` | `tasks.test` sets `seatwave.nginx.directory`, excludes the tag `stack`, and declares `nginx` as an input; `stackTest` runs only the tag `stack` (`gradle.md`). |
| `src/test/java/io/plainround/seatwave/proxy/backend/TestBackend.java` | A helper that builds the HTTP client trusting the TEST authority (`tests-backend-walkthrough.md`). |
| `scripts/stack.sh` | `test` starts the stack, runs `./gradlew stackTest`, and stops the stack (`shell-scripts-walkthrough.md`). |

## How it works underneath

1. **Gradle runs the tests.** `./gradlew test` compiles the test classes, starts a Java process with JUnit and runs every method marked `@Test`. A method passes if it returns normally and fails if it throws an error. An AssertJ check that is not true throws an `AssertionError`.
2. **A tag selects a group.** `@Tag("stack")` marks `StackInvariantTest`. `build.gradle.kts` runs `excludeTags("stack")` for `test` and `includeTags("stack")` for `stackTest`.
3. **A system property passes the folder.** The first test needs the path of the `nginx/` folder. Gradle gives it to the test process as a **system property** (a named setting inside the Java process) called `seatwave.nginx.directory`, and the test reads it with `System.getProperty(...)`. Using Gradle's path means the test works wherever Gradle was started.
4. **Reading configuration as text.** The first test never starts NGINX. It reads the files with `Files.readString` and looks for lines with regular expressions and `contains`. This is fast and needs no Docker, at the cost of checking text, not behavior. The second test closes that gap.
5. **The second test is an HTTP client.** It sends real requests to `https://127.0.0.1:20900` (PRODUCTION), to SeatWave's plain port `20911` and to its `/metrics` on `20912`, and compares answers and counters.

## Walkthrough of this repository's use

### `NginxConfigRulesTest`: imports, the class and its constants

```java
package io.plainround.seatwave.proxy.nginx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The proxy's half of the invariant, checked in the configuration files themselves, so a change
 * that breaks it fails the build without Docker: client addresses are overwritten, never appended;
 * every proxied location sends them; nothing makes the cache keep what SeatWave forbids; and every
 * port is published on 127.0.0.1 in Module 9's range. The stack tests then check the running proxy.
 */
class NginxConfigRulesTest {

  private static final Path NGINX = Path.of(System.getProperty("seatwave.nginx.directory"));
  private static final Pattern LISTEN = Pattern.compile("(?m)^\\s*listen (\\d+)");
  private static final Pattern PUBLISHED = Pattern.compile("\"127\\.0\\.0\\.1:(\\d+):(\\d+)\"");
  private static final Pattern ANY_PORT_LINE = Pattern.compile("(?m)^\\s+- \"([^\"]*:[^\"]*)\"$");
  private static final Pattern UPSTREAM = Pattern.compile("(?s)upstream ([a-z_]+) \\{([^}]*)\\}");
  private static final Pattern KEEPALIVE = Pattern.compile("(?m)^\\s*keepalive (\\d+);");
  private static final Pattern KEEPALIVE_TIMEOUT =
      Pattern.compile("(?m)^\\s*keepalive_timeout ([^;]+);");
  // An NGINX time: a number and a unit (ms, s, m, h); a number alone is seconds.
  private static final Pattern NGINX_TIME = Pattern.compile("(\\d+)(ms|s|m|h)?");
  // A Compose service starts at two spaces of indentation under "services:".
  private static final Pattern SERVICE = Pattern.compile("(?m)^  ([a-z]+):$");
  private static final Pattern SETTINGS_PORT =
      Pattern.compile("(?m)^http\\.(?:plain|tls|admin)Port=(\\d+)$");
  // The JDK's HTTP server closes an idle connection after 30 s (sun.net.httpserver.idleInterval).
  private static final Duration BACKEND_IDLE = Duration.ofSeconds(30);
  private static final int LOWEST_LAB_PORT = 20_900;
  private static final int HIGHEST_LAB_PORT = 20_999;
```

- Line 1, `package io.plainround.seatwave.proxy.nginx;`, says which package (folder of classes) this file belongs to. It matches the folder path `.../proxy/nginx/`.
- Line 3, `import static org.assertj.core.api.Assertions.assertThat;`, brings in the one AssertJ method used everywhere. `static` lets the code write `assertThat(...)` instead of `Assertions.assertThat(...)`.
- Lines 5 to 18 are the other imports: `IOException` (the error for a file problem), `StandardCharsets` (UTF-8), `Files` and `Path` (reading files), `Duration` (a length of time), the collections `ArrayList`, `List` and `Set`, `MatchResult`, `Matcher` and `Pattern` (regular expressions), `Collectors` and `Stream` (stream processing) and `@Test` (JUnit's marker for a test method).
- Lines 20 to 25 are a **Javadoc** comment (it starts with `/**`). It says what the class checks: client addresses are overwritten, never appended; every proxied location sends them; nothing makes the cache keep what SeatWave forbids; and every port is published on `127.0.0.1` in Module 9's range. "The stack tests then check the running proxy."
- Line 26, `class NginxConfigRulesTest {`: the class. It has no `public` keyword because JUnit finds it anyway.
- Line 28: `private static final Path NGINX = Path.of(System.getProperty("seatwave.nginx.directory"));`. `private` means only this class sees it; `static` means one per class, not per test object; `final` means it never changes. A `Path` is a file location. It reads the property that Gradle set.
- Line 29: `LISTEN = Pattern.compile("(?m)^\\s*listen (\\d+)")`. A **regular expression** (a pattern language for text), compiled once. In Java source a backslash is doubled, so `\\s` is the pattern `\s` (a white-space character) and `\\d` is `\d` (a digit). `(?m)` means "multi-line": `^` matches at the start of every line. The pattern finds a line that starts with optional white space and then `listen `, and captures the digits (`(\d+)`) that follow, the port number.
- Line 30: `PUBLISHED`: the text `"127.0.0.1:` then two captured numbers separated by a colon, then `"`: a published port in `compose.yaml` such as `"127.0.0.1:20900:8443"`. The first capture is the host port and the second the container port.
- Line 31: `ANY_PORT_LINE`: a line that is a list item (`- `) with a quoted string that contains a colon: any port mapping at all, whatever its address. `[^"]*` means "any characters except a quote".
- Line 32: `UPSTREAM`: `(?s)upstream ([a-z_]+) \{([^}]*)\}`. It finds the word `upstream`, captures its name, and captures the whole body between `{` and the closing `}` (`[^}]*`: any text without a closing brace; `(?s)` lets it run over several lines).
- Line 33: `KEEPALIVE`: a line `keepalive <number>;` and the number. Lines 34 and 35: `KEEPALIVE_TIMEOUT`: a line `keepalive_timeout <value>;`, capturing the whole value with any unit (`[^;]+`, everything up to the semicolon).
- Lines 36 and 37: a comment and `NGINX_TIME`: a number and an optional unit `ms`, `s`, `m` or `h`. NGINX reads a number without a unit as seconds.
- Lines 38 and 39: a comment and `SERVICE`: a line of exactly two spaces, a lower-case name and a colon. In `compose.yaml` that is the start of a service, such as `  proxy:`.
- Lines 40 and 41: `SETTINGS_PORT`: a line `http.plainPort=`, `http.tlsPort=` or `http.adminPort=` with its number, in SeatWave's container settings. `(?:...)` is a group that captures nothing.
- Lines 42 and 43: a comment and `BACKEND_IDLE = Duration.ofSeconds(30)`: the JDK's HTTP server closes an idle connection after 30 s (`sun.net.httpserver.idleInterval`). Lines 44 and 45 are the lowest and highest lab ports. The underscore in a number such as `20_900` is only for reading.

### Test 1: every proxying location sends the forwarding headers

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
```

- Line 47: `@Test` marks the next method (line 48) as a test. The name reads as a sentence. `throws IOException` says the method may fail with a file error; JUnit counts that as a failed test.
- Line 49: `for (Path file : edgeFiles())` loops over the edge proxy's `.conf` files (helper below; it leaves out the sidecar's).
- Line 50: for each file, `locations(read(file))` returns the text of every `location` block in it.
- Line 51: `if (location.contains("proxy_pass"))`: only blocks that proxy matter. The named locations in `errors.conf` just `return`, so they are skipped.
- Lines 52 to 54: `assertThat(location).as("a location in " + file.getFileName()).contains("include seatwave/proxy-headers.conf;");`. `as(...)` is a label for the failure message. `contains` fails unless the block has that line. If someone adds a proxying location and forgets the include, the build fails and the message names the file. I tested it: I deleted the include from the port 20902 server in a copy and the test failed at line 54.

### Test 2: the client address is overwritten, never appended

```java
  @Test
  void overwritesTheClientAddressInsteadOfAppendingToIt() throws IOException {
    String headers = read(NGINX.resolve("seatwave/proxy-headers.conf"));

    assertThat(headers)
        .contains("proxy_set_header X-Forwarded-For $remote_addr;")
        .contains("proxy_set_header Forwarded \"for=\\\"$forwarded_node\\\";proto=$scheme\";");
    // RFC 7239: an IPv6 node goes in brackets, and only the map in nginx.conf adds them.
    assertThat(read(NGINX.resolve("nginx.conf")))
        .containsPattern(
            "(?s)map \\$remote_addr \\$forwarded_node \\{\\s*~:\\s+\"\\[\\$remote_addr\\]\";");
    for (Path file : edgeFiles()) {
      assertThat(read(file)).as(file.toString()).doesNotContain("$proxy_add_x_forwarded_for");
    }
  }
```

- Line 62 reads `seatwave/proxy-headers.conf`. Lines 64 to 66: two exact lines must be present: `proxy_set_header X-Forwarded-For $remote_addr;` and the standard `Forwarded` header with its quoted node, `for=\"$forwarded_node\"`. Chained `.contains(...)` calls check one after the other. In the Java string `\"` is a quote inside the text, and `\\\"` is a backslash and a quote, the way the NGINX file writes a quote inside its own quoted string.
- Lines 67 to 70: a comment, and the check that `nginx.conf` holds the `map` that turns an IPv6 address into `[address]` (RFC 7239's brackets). `containsPattern` tests a regular expression; in it `\\$` is a literal dollar sign, `\\{` and `\\[` literal brackets, and `\\s*` any white space, so the check does not depend on the exact spacing. The review pass deleted the brackets from the map in a copy: this test failed.
- Lines 71 to 73: for **every** edge file, `doesNotContain("$proxy_add_x_forwarded_for")`. That NGINX variable appends the client's own header to the proxy's value, so a forged address would survive. Adding it anywhere fails the build. (I added it to `status.conf` in a copy of version 1.0.0: the test failed.)

### Test 3: nothing overrides what SeatWave says about caching

```java
  @Test
  void letsNothingOverrideWhatSeatWaveSaysAboutCaching() throws IOException {
    for (Path file : allFiles()) {
      String text = read(file);
      assertThat(text).as(file.toString()).doesNotContain("proxy_ignore_headers");
      // Fail safe: an expired entry is never served when SeatWave fails.
      assertThat(text).as(file.toString()).doesNotContain("proxy_cache_use_stale");
      assertThat(text).as(file.toString()).doesNotContainPattern("proxy_cache_methods[^;]*POST");
    }
    assertThat(read(NGINX.resolve("seatwave/production.conf")))
        .contains("proxy_cache_methods GET HEAD;")
        .contains("proxy_no_cache $http_authorization;");
  }
```

- Line 78 loops over **all** `.conf` files, the sidecar's too. Lines 80 to 83: three `doesNotContain` checks. `proxy_ignore_headers` would let the proxy ignore SeatWave's `Cache-Control`. `proxy_cache_use_stale` would serve an expired copy when SeatWave fails (line 81 is the comment "Fail safe"). `doesNotContainPattern("proxy_cache_methods[^;]*POST")` uses a regular expression: the directive `proxy_cache_methods`, any text without a semicolon, then `POST`. I added `proxy_cache_use_stale` to a copy: the test failed at that check.
- Lines 85 to 87: `production.conf` must contain `proxy_cache_methods GET HEAD;` and `proxy_no_cache $http_authorization;`: the two positive rules.

### Test 4: the proxy closes idle upstream connections first

```java
  @Test
  void closesIdleUpstreamConnectionsBeforeSeatWaveDoes() throws IOException {
    int reusingUpstreams = 0;
    for (Path file : allFiles()) {
      Matcher upstream = UPSTREAM.matcher(read(file).replaceAll("(?m)#.*$", ""));
      while (upstream.find()) {
        String body = upstream.group(2);
        Matcher keepalive = KEEPALIVE.matcher(body);
        // Since NGINX 1.29.7 an upstream keeps idle connections unless it says keepalive 0.
        boolean reuses = !keepalive.find() || Integer.parseInt(keepalive.group(1)) > 0;
        if (!reuses) {
          continue;
        }
        reusingUpstreams++;
        Matcher timeout = KEEPALIVE_TIMEOUT.matcher(body);
        // Without the directive NGINX keeps an idle connection 60 s, longer than SeatWave's 30 s.
        assertThat(timeout.find())
            .as("keepalive_timeout in upstream " + upstream.group(1))
            .isTrue();
        assertThat(nginxTime(timeout.group(1).strip()))
            .as("keepalive_timeout of upstream " + upstream.group(1))
            .isLessThan(BACKEND_IDLE);
        assertThat(timeout.find())
            .as("a second keepalive_timeout in " + upstream.group(1))
            .isFalse();
      }
    }

    assertThat(reusingUpstreams).isPositive();
  }

  @Test
  void readsNginxTimesInEveryUnitTheConfigurationMayUse() {
    assertThat(nginxTime("20s")).isEqualTo(Duration.ofSeconds(20));
    assertThat(nginxTime("20")).isEqualTo(Duration.ofSeconds(20));
    assertThat(nginxTime("1m")).isEqualTo(Duration.ofMinutes(1));
    assertThat(nginxTime("2500ms")).isEqualTo(Duration.ofMillis(2_500));
    assertThat(nginxTime("1h")).isEqualTo(Duration.ofHours(1));
  }
```

- Line 92 counts the upstreams that keep idle connections. Lines 93 to 95: for each file, with its comments removed first (`replaceAll("(?m)#.*$", "")`, so a comment that mentions a directive is not read as one), `UPSTREAM` finds every `upstream` block; `upstream.group(2)` is its body.
- Lines 97 to 102: does this upstream keep idle connections? The comment says when it does: since NGINX 1.29.7, always, unless it says `keepalive 0`. So `reuses` is true when there is no `keepalive` line at all, or one with a number above 0, and `seatwave_no_reuse` (`keepalive 0`) is skipped with `continue`.
- Lines 104 to 114: every upstream that reuses must have exactly one `keepalive_timeout`. The comment says why a missing one is a failure: NGINX's default is 60 s, longer than SeatWave's 30. `nginxTime(...)` (below) turns the value, in any unit, into a `Duration`, which must be less than `BACKEND_IDLE`. A second `keepalive_timeout` in the same block fails too. `.as(...)` names the upstream in a failure.
- Line 118: at least one upstream reuses, so the test cannot pass because it found nothing.
- The first version's pattern needed the unit `s` and checked only the first timeout of each block, so `keepalive_timeout 1m;` (60 s) passed. The review pass changed the timeout to `1m`, then to `40000ms`, then removed it, each in a copy: this test failed every time.
- Lines 121 to 128: `readsNginxTimesInEveryUnitTheConfigurationMayUse` checks `nginxTime` itself: `20s` and `20` are 20 seconds, `1m` one minute, `2500ms` 2,500 milliseconds and `1h` one hour.

### Test 5: every published port is on 127.0.0.1, in the range, and has a matching listener

```java
  @Test
  void publishesEveryProxyPortOnTheLoopbackAddressInTheModulesRange() throws IOException {
    String compose = read(Path.of("compose.yaml"));
    Matcher published = PUBLISHED.matcher(compose);
    List<Integer> hostPorts = new ArrayList<>();
    while (published.find()) {
      hostPorts.add(Integer.parseInt(published.group(1)));
    }
    Set<Integer> listened =
        edgeFiles().stream()
            .flatMap(file -> LISTEN.matcher(read(file)).results().map(match -> match.group(1)))
            .map(Integer::parseInt)
            .collect(Collectors.toSet());

    assertThat(hostPorts)
        .isNotEmpty()
        .allMatch(port -> port >= LOWEST_LAB_PORT && port <= HIGHEST_LAB_PORT);
    assertThat(hostPorts).doesNotHaveDuplicates();
    assertThat(ANY_PORT_LINE.matcher(compose).results().map(match -> match.group(1)))
        .allMatch(line -> line.startsWith("127.0.0.1:"));
    // Per service: every port the proxy listens on is published, and nothing else of the proxy.
    assertThat(containerPorts(service(compose, "proxy"))).isEqualTo(listened);
  }

  @Test
  void publishesExactlyTheBackendPortsItsContainerSettingsListenOn() throws IOException {
    String compose = read(Path.of("compose.yaml"));
    Set<Integer> listened =
        SETTINGS_PORT
            .matcher(read(Path.of("config/backend-container.properties")))
            .results()
            .map(match -> Integer.parseInt(match.group(1)))
            .collect(Collectors.toSet());

    assertThat(listened).hasSize(3);
    assertThat(containerPorts(service(compose, "backend"))).isEqualTo(listened);
  }
```

- Line 132 reads `compose.yaml` (path relative to the project folder, where Gradle runs the tests).
- Lines 133 to 137: `published.find()` loops over every `"127.0.0.1:host:container"` in the file, and `hostPorts` collects the host numbers.
- Lines 138 to 142: the ports NGINX listens on, read from the edge files: a **stream** pipeline. `edgeFiles().stream()` turns the list into a stream; `.flatMap(file -> LISTEN.matcher(read(file)).results().map(match -> match.group(1)))` turns each file into the stream of its listen ports (as text); `.map(Integer::parseInt)` turns text into numbers (`Integer::parseInt` is a short way to write the lambda); `.collect(Collectors.toSet())` gathers them in a **set**, a collection with no duplicates.
- Lines 144 to 146: the host ports are not empty and all between 20,900 and 20,999. Line 147: `doesNotHaveDuplicates()` (no port published twice).
- Lines 148 and 149: every port mapping line of the file, however it is written (`ANY_PORT_LINE`), must start with `127.0.0.1:`. This is the check that catches `"20910:8443"` (listening on all addresses). I tested it in a copy and it failed.
- Lines 150 and 151: a comment, and the check per service. `service(compose, "proxy")` is the text of the proxy's own service in `compose.yaml`, and `containerPorts(...)` the container ports it publishes (both helpers below). That set must **equal** the set of ports NGINX listens on: every listener is published, and nothing else. The first version put the container ports of every service into one set and asked only `containsAll`, so deleting the proxy's `"127.0.0.1:20902:8080"` still passed, because the backend publishes a container port 8080 too. The review pass deleted that line in a copy: this test now fails.
- Lines 154 to 166: `publishesExactlyTheBackendPortsItsContainerSettingsListenOn` does the same for SeatWave. Its listeners are not in an NGINX file but in `config/backend-container.properties` (`http.plainPort=8080`, `http.tlsPort=8443`, `http.adminPort=8081`). The test reads the three with `SETTINGS_PORT`, requires exactly three (`hasSize(3)`), and requires the backend's published container ports to be exactly those.

### Test 6: images are pinned, nothing is privileged

```java
  @Test
  void pinsEveryImageByDigestAndGrantsNoPrivilege() throws IOException {
    String compose = read(Path.of("compose.yaml"));
    String dockerfile = read(Path.of("Dockerfile"));

    String settings = compose.replaceAll("(?m)#.*$", "");
    assertThat(settings)
        .doesNotContain("privileged:", "network_mode: host", "docker.sock", "pid: host");
    assertThat(settings.lines().filter(line -> line.contains(" nginx:")))
        .allMatch(line -> line.contains("@sha256:"));
    assertThat(dockerfile.lines().filter(line -> line.startsWith("FROM ")))
        .hasSize(2)
        .allMatch(line -> line.contains("@sha256:"));
    assertThat(dockerfile).contains("USER 10001");
  }
```

- Lines 170 and 171 read `compose.yaml` and the `Dockerfile`.
- Line 173: `compose.replaceAll("(?m)#.*$", "")` removes every comment (from `#` to the end of the line), so a comment that says "no privileged" cannot trigger a failure. Lines 174 and 175: the settings must contain none of `privileged:`, `network_mode: host`, `docker.sock` and `pid: host`. These four are the usual ways a container gets too much power.
- Lines 176 and 177: every line that has ` nginx:` (an image reference) must contain `@sha256:`, the digest.
- Lines 178 to 180: the lines of the `Dockerfile` that start with `FROM ` must number exactly two (`hasSize(2)`) and each must have a digest.
- Line 181: the `Dockerfile` contains `USER 10001`. I changed it to `USER root` in a copy: the test failed at that line.

### The helper methods

```java
  private static String service(String compose, String name) {
    List<MatchResult> services = SERVICE.matcher(compose).results().toList();
    for (int index = 0; index < services.size(); index++) {
      if (services.get(index).group(1).equals(name)) {
        int end = index + 1 < services.size() ? services.get(index + 1).start() : compose.length();
        return compose.substring(services.get(index).end(), end);
      }
    }
    throw new IllegalStateException("compose.yaml has no service " + name);
  }

  private static Set<Integer> containerPorts(String service) {
    return PUBLISHED
        .matcher(service)
        .results()
        .map(match -> Integer.parseInt(match.group(2)))
        .collect(Collectors.toSet());
  }

  private static Duration nginxTime(String text) {
    Matcher time = NGINX_TIME.matcher(text);
    if (!time.matches()) {
      throw new IllegalArgumentException("not an NGINX time this test reads: " + text);
    }
    long amount = Long.parseLong(time.group(1));
    return switch (time.group(2) == null ? "s" : time.group(2)) {
      case "ms" -> Duration.ofMillis(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      default -> Duration.ofSeconds(amount);
    };
  }
```

- Lines 184 to 193: `service(compose, name)` cuts out one service of `compose.yaml`. `SERVICE.matcher(compose).results().toList()` lists every line that starts a service, with its position. For the wanted name, the text runs from the end of its line to the start of the next service, or to the end of the file for the last one. `start()` and `end()` are the positions where a match begins and ends. A name that is not there is a bug in the test, so it throws.
- Lines 195 to 201: `containerPorts(service)` is the set of container ports (`group(2)` of `PUBLISHED`) published in that text.
- Lines 203 to 215: `nginxTime(text)` reads an NGINX time. `matches()` requires the whole text to fit `NGINX_TIME`; anything else is not a value this test knows, and it throws instead of guessing. `group(2)` is the unit, `null` when there is none, which NGINX reads as seconds. The `switch` turns the number into a `Duration` of the right unit.

```java
  private static List<Path> edgeFiles() throws IOException {
    try (Stream<Path> files = Files.walk(NGINX)) {
      return files
          .filter(file -> file.toString().endsWith(".conf"))
          .filter(file -> !file.toString().contains("sidecar"))
          .sorted()
          .toList();
    }
  }

  private static List<Path> allFiles() throws IOException {
    try (Stream<Path> files = Files.walk(NGINX)) {
      return files.filter(file -> file.toString().endsWith(".conf")).sorted().toList();
    }
  }
```

- Lines 217 to 225: `edgeFiles()`. `private static` means a helper only this class uses, without needing a test object. `Files.walk(NGINX)` lists every file under the folder. It is opened in a `try (...)` block (a **try-with-resources**), which closes the stream even if something fails. The pipeline keeps names ending in `.conf`, drops any path that contains `sidecar`, sorts them (so failures come in the same order each time) and gives back an unchangeable list (`toList()`).
- Lines 227 to 231: `allFiles()` is the same without dropping the sidecar.

```java
  private static List<String> locations(String file) {
    // Comments are dropped first, so a sentence that mentions a location is not parsed as one.
    String text = file.replaceAll("(?m)#.*$", "");
    List<String> blocks = new ArrayList<>();
    int from = 0;
    while ((from = text.indexOf("location ", from)) >= 0) {
      int open = text.indexOf('{', from);
      int depth = 0;
      int index = open;
      do {
        char character = text.charAt(index);
        depth += character == '{' ? 1 : character == '}' ? -1 : 0;
        index++;
      } while (depth > 0 && index < text.length());
      blocks.add(text.substring(from, index));
      from = index;
    }
    return blocks;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new java.io.UncheckedIOException("cannot read " + file, unreadable);
    }
  }
}
```

- Lines 233 to 251: `locations(String file)` cuts a file's text into location blocks. Line 234 is a comment, and line 235 removes the comments from the text first, so a sentence that mentions a location in a comment is not parsed as one. Line 236 makes the result list; line 237, `int from = 0;`, is where to search from.
- Line 238: `while ((from = text.indexOf("location ", from)) >= 0)` finds the next word `location ` at or after `from` and stores its position in `from`; `indexOf` returns -1 when there is none, which ends the loop.
- Lines 239 to 246: find the block's end by counting braces. `open` is the position of the first `{`. `depth` counts open braces. The `do { ... } while (...)` loop looks at one character at a time: `depth += character == '{' ? 1 : character == '}' ? -1 : 0;` adds 1 for `{`, subtracts 1 for `}`, and 0 for anything else (a chained `? :`, a short `if`). It stops when `depth` is back to 0, or at the end of the text.
- Line 247: `blocks.add(text.substring(from, index));` saves the text from `location ` to the closing brace. Line 248 moves on. Line 250 returns the list.
- Lines 253 to 259: `read(Path file)` hides the file-reading error: `Files.readString(file, UTF_8)`, and on an `IOException` it throws an `UncheckedIOException` with the file name in the message. The helper exists because a lambda (as in test 5, line 188) cannot throw the checked `IOException`.

### `StackInvariantTest`: imports, the class and the constants

```java
package io.plainround.seatwave.proxy.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.proxy.backend.TestBackend;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
  private static final int SEATS_PER_SECTION = 1_000;
  private static final int CONFLICT = 409;
  private static final int MAX_SEAT_ATTEMPTS = 50;
  // A random first seat, so two runs within one hold's 300 s rarely start on the same seat.
  private static final AtomicInteger NEXT_SEAT =
      new AtomicInteger(new SplittableRandom().nextInt(SEATS_PER_SECTION));
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

- Line 5 imports `TestBackend`, the helper from the backend tests. Lines 6 to 23 import the JDK's HTTP client classes (`HttpClient`, `HttpRequest`, `HttpResponse`, `URI`), `UUID` (random identifiers), `Duration`, `List` and `Optional`, `SplittableRandom` (a random number source) and `AtomicInteger` (a counter that is safe across threads), the regular-expression classes, and four JUnit markers.
- Lines 25 to 29: the Javadoc. The invariant against the running stack (`scripts/stack.sh test`): SeatWave trusts a forwarded client address only from the proxy; the proxy never caches a `POST` or a response marked private, while it does cache a static file. "Never part of `./gradlew check`."
- Line 30, `@Tag("stack")`: the tag that `build.gradle.kts` uses to include or exclude this class.
- Lines 33 to 37: constants. `PROXY` is PRODUCTION; `SEATWAVE_DIRECT` is SeatWave's plain port, published on 20911; `METRICS` is SeatWave's operations port 20912; `FORGED` is the address the tests pretend to be, `203.0.113.7` (a range reserved for documentation, so it can never be a real client); `TIMEOUT` is 10 seconds per request.
- Lines 38 to 43: the seats the holds use. `SEATS_PER_SECTION` is 1,000, `CONFLICT` is the status 409 SeatWave answers for a seat that is taken, and `MAX_SEAT_ATTEMPTS` (50) bounds how many seats one hold tries. `NEXT_SEAT` is the next seat number, a counter shared by the whole class. The comment says why it starts at a random place (`new SplittableRandom().nextInt(1_000)`, from 0 to 999): holds last 300 s, so a second run against the same running stack within that time should not begin on the seats the first one took.
- Line 44: `private static HttpClient client;` is set once for the whole class.
- Lines 46 to 49: `@BeforeAll static void connect()` runs once before the first test and gets the client from `TestBackend.client()` (an HTTP client that trusts the TEST certificate authority). Lines 51 to 54: `@AfterAll static void disconnect()` closes it after the last test.

### Tests 1 and 2: the forged header

```java
  @Test
  void ignoresAForgedHeaderSentStraightToSeatWave() throws Exception {
    HttpResponse<String> view = get(SEATWAVE_DIRECT + "/lab/client", "X-Forwarded-For", FORGED);

    assertThat(view.body()).doesNotContain(FORGED).contains("\"source\":\"peer\"");
  }

  @Test
  void replacesAForgedHeaderWithTheAddressTheProxySaw() throws Exception {
    HttpResponse<String> view = get(PROXY + "/lab/client", "X-Forwarded-For", FORGED);

    assertThat(view.body())
        .doesNotContain(FORGED)
        .contains("\"scheme\":\"https\"", "\"source\":\"forwarded\"");
  }
```

- Test 1, lines 56 to 61: send `GET /lab/client` straight to SeatWave with `X-Forwarded-For: 203.0.113.7`. `/lab/client` answers with the address SeatWave decided the client has. The body must not contain the forged address and must contain `"source":"peer"`: SeatWave ignored the header because the peer is not a trusted proxy. `throws Exception` is a short way to allow any error.
- Test 2, lines 63 to 70: the same request through the proxy. The body again must not contain the forged address, and must contain `"scheme":"https"` and `"source":"forwarded"`. The proxy replaced the header with the address it saw, and SeatWave, trusting the proxy, used it. Passing two strings to `contains` checks both.
- I ran both against the live stack: both passed.

### Tests 3 and 4: no `POST` and no private response from the cache

```java
  @Test
  void neverAnswersAPostFromTheCache() throws Exception {
    long before = count("POST /v1/holds", "201");

    HttpResponse<String> first = hold("A");
    HttpResponse<String> second = hold("A");

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(201);
    assertThat(first.body()).isNotEqualTo(second.body());
    for (HttpResponse<String> answer : List.of(first, second)) {
      assertThat(answer.headers().firstValue("x-cache-status")).isNotEqualTo(Optional.of("HIT"));
    }
    assertThat(count("POST /v1/holds", "201")).isEqualTo(before + 2);
  }

  @Test
  void neverAnswersAPrivateResponseFromTheCache() throws Exception {
    String location = hold("B").headers().firstValue("location").orElseThrow();
    long before = count("GET /v1/holds/{holdId}", "200");

    HttpResponse<String> first = get(PROXY + location);
    HttpResponse<String> second = get(PROXY + location);

    for (HttpResponse<String> answer : List.of(first, second)) {
      assertThat(answer.headers().firstValue("x-cache-status")).isNotEqualTo(Optional.of("HIT"));
    }
    assertThat(first.body()).isEqualTo(second.body());
    assertThat(count("GET /v1/holds/{holdId}", "200")).isEqualTo(before + 2);
  }
```

- Test 3, lines 72 to 86: `long before = count("POST /v1/holds", "201");` reads how many successful hold creations SeatWave has counted (helper `count` below). Two holds are created through the proxy in section A (`hold("A")`, which picks a free seat). Both must answer `201`, with different bodies (two different holds), and neither answer may carry `x-cache-status: HIT` (the loop checks both; the first version checked only the first). The real proof is the last line: SeatWave's counter must have gone up by exactly 2, so both `POST`s reached SeatWave and none came from a cache. `isNotEqualTo(Optional.of("HIT"))` compares the whole `Optional`, so a missing header passes too.
- Test 4, lines 88 to 101: creates a hold in section B and reads its `Location` header (the address of the new hold). Then it reads that URL twice through the proxy. A hold's `GET` is marked `private` by SeatWave. Neither answer may carry `x-cache-status: HIT`, the two bodies must be equal, and SeatWave's counter for `GET /v1/holds/{holdId}` must have gone up by 2: both requests reached SeatWave.

### Tests 5 and 6: the static file and the body limit

```java
  @Test
  void answersAStaticFileFromTheCacheOnceItHoldsIt() throws Exception {
    get(PROXY + "/static/seat-map.js");
    long before = count("GET /static/{file}", "200");

    HttpResponse<String> cached = get(PROXY + "/static/seat-map.js");

    assertThat(cached.headers().firstValue("x-cache-status")).hasValue("HIT");
    assertThat(count("GET /static/{file}", "200")).isEqualTo(before);
  }

  @Test
  void refusesABodyOverItsLimitWithProblemDetailsAtTheProxyOrAtSeatWave() throws Exception {
    HttpResponse<String> overProxyLimit = post("{\"x\":\"" + "a".repeat(5_000) + "\"}");
    HttpResponse<String> overSeatWaveLimit = post("{\"x\":\"" + "a".repeat(2_000) + "\"}");

    assertThat(overProxyLimit.statusCode()).isEqualTo(413);
    assertThat(overProxyLimit.body()).contains("The body is over the proxy limit");
    assertThat(overSeatWaveLimit.statusCode()).isEqualTo(413);
    assertThat(overSeatWaveLimit.body()).contains("The body is over 1,024 bytes");
  }
```

- Test 5, lines 103 to 112: one `GET` of `/static/seat-map.js` fills the cache (its result is not used). The counter of SeatWave's `GET /static/{file}` is read. A second `GET` must carry `x-cache-status: HIT` (`hasValue("HIT")`), and the counter must be unchanged (`isEqualTo(before)`): the proxy answered without asking SeatWave.
- Test 6, lines 114 to 123: two bodies. `"a".repeat(5_000)` makes 5,000 letters, over the proxy's 4 KB limit; `"a".repeat(2_000)` makes 2,000, under the proxy's 4 KB limit but over SeatWave's 1,024. The first must give `413` with the proxy's own text `The body is over the proxy limit`; the second must give `413` with SeatWave's text `The body is over 1,024 bytes`. So the two layers refuse in their own voice.

### The helper methods

```java
  private static HttpResponse<String> hold(String section)
      throws IOException, InterruptedException {
    // Holds last 300 s and the stack may still hold seats from an earlier run, so a taken seat
    // (409) moves on to the next number instead of failing the test; the seats of one run never
    // repeat, because the counter only grows.
    HttpResponse<String> answer = null;
    for (int attempt = 0; attempt < MAX_SEAT_ATTEMPTS; attempt++) {
      String seat = section + "-" + (1 + NEXT_SEAT.getAndIncrement() % SEATS_PER_SECTION);
      answer = post("{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"stack-test\"}");
      if (answer.statusCode() != CONFLICT) {
        return answer;
      }
    }
    return answer;
  }

  private static HttpResponse<String> post(String body) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(PROXY + "/v1/holds"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
```

- Lines 125 to 139: `hold(String section)` holds a free seat of a section. Each attempt takes the next number from `NEXT_SEAT` (`getAndIncrement()` returns it and adds one, safely across threads), turns it into a seat from 1 to 1,000 (`1 + number % 1_000`), builds the JSON body by joining strings (`{"eventId":1,"seat":"A-17","customer":"stack-test"}`) and sends it with `post`. A `409` means the seat is held, most likely by an earlier run within its 300 s, so the loop moves on to the next seat, at most 50 times; any other answer is returned. The comment gives the reasons, and why the seats of one run never repeat: the counter only grows. The first version drew a random seat for every hold, so two holds of one run could pick the same seat (about once in a thousand runs) and the second would fail.
- Lines 141 to 150: `post(String body)` sends the `POST` to `/v1/holds` through the proxy. `HttpRequest.newBuilder(URI.create(...))` starts a request; `.timeout(TIMEOUT)`; `.header("Content-Type", "application/json")`; `.header("Idempotency-Key", ...)` with a fresh random `UUID` in quotes (SeatWave's API wants a key so a retry can be recognized); `.POST(HttpRequest.BodyPublishers.ofString(body))`; `.build()`. `client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))` sends it and waits, reading the answer as text. `throws IOException, InterruptedException` are the two things `send` may throw.

```java
  private static HttpResponse<String> get(String uri, String... headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).timeout(TIMEOUT).GET();
    if (headers.length > 0) {
      request.headers(headers);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static long count(String route, String status) throws IOException, InterruptedException {
    String metrics = get(METRICS).body();
    Matcher matcher =
        Pattern.compile(
                "seatwave_requests_total\\{route=\""
                    + Pattern.quote(route)
                    + "\",status=\""
                    + status
                    + "\"} (\\d+)")
            .matcher(metrics);
    return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
  }
}
```

- Lines 152 to 159: `get(String uri, String... headers)`. The three dots make it a **varargs** parameter: callers may pass any number of extra strings, collected into an array. It builds a `GET` request; `if (headers.length > 0)` adds them with `request.headers(headers)`, which expects names and values in pairs (`"X-Forwarded-For", FORGED`).
- Lines 161 to 172: `count(String route, String status)` reads SeatWave's `/metrics` text and finds the counter line `seatwave_requests_total{route="...",status="..."} 17`. It builds the pattern from pieces; `Pattern.quote(route)` makes the route match literally (the characters `{`, `}` and `/` in `GET /v1/holds/{holdId}` would otherwise be pattern commands). `matcher.find() ? Long.parseLong(matcher.group(1)) : 0` returns the number if the line exists and 0 if not: a counter that was never used is missing from the output. The counters are compared before and after, so the absolute value does not matter.
(Version 1.0.0 had a sixth helper, `seat()`, that drew a random number from 1 to 1,000 for each hold although its comment promised a number "that this run has not used"; `hold` and `NEXT_SEAT` replaced it.)

## Try it yourself

I ran these in a copy of the repository on 11 November 2026 (Eclipse Temurin 25.0.2, Gradle 9.9.1). Timings differ on every run.

1. Run the file-rules test alone (no Docker needed):

```
./gradlew test --tests '*NginxConfigRulesTest'
```

Output with version 1.0.1 (the lines for the eight tests; the order may differ):

```
NginxConfigRulesTest > publishesExactlyTheBackendPortsItsContainerSettingsListenOn() PASSED
NginxConfigRulesTest > letsNothingOverrideWhatSeatWaveSaysAboutCaching() PASSED
NginxConfigRulesTest > everyProxiedLocationOfTheEdgeSendsTheForwardingHeaders() PASSED
NginxConfigRulesTest > readsNginxTimesInEveryUnitTheConfigurationMayUse() PASSED
NginxConfigRulesTest > overwritesTheClientAddressInsteadOfAppendingToIt() PASSED
NginxConfigRulesTest > closesIdleUpstreamConnectionsBeforeSeatWaveDoes() PASSED
NginxConfigRulesTest > publishesEveryProxyPortOnTheLoopbackAddressInTheModulesRange() PASSED
NginxConfigRulesTest > pinsEveryImageByDigestAndGrantsNoPrivilege() PASSED
```

2. Break a rule on purpose, in a **copy** of the repository (never in your real checkout), and watch the test fail. In the copy, publish a port on every address, then run the test:

```
sed -i.bak 's/"127.0.0.1:20910:8443"/"20910:8443"/' compose.yaml
./gradlew test --rerun --tests '*NginxConfigRulesTest' 2>&1 | grep -E 'FAILED|AssertionError'
mv compose.yaml.bak compose.yaml
```

Output with version 1.0.1:

```
NginxConfigRulesTest > publishesExactlyTheBackendPortsItsContainerSettingsListenOn() FAILED
NginxConfigRulesTest > publishesEveryProxyPortOnTheLoopbackAddressInTheModulesRange() FAILED
    java.lang.AssertionError at NginxConfigRulesTest.java:149
```

Two tests fail: line 149 finds the mapping that is not on `127.0.0.1`, and the backend's published ports no longer include 8443 in the form `PUBLISHED` reads. `--rerun` makes Gradle run the test task even though it thinks nothing changed: the task declares the `nginx/` folder as an input, but not `compose.yaml`, so without `--rerun` the second command prints nothing at all, because Gradle reports the task as `UP-TO-DATE` and runs no test. I also ran, on version 1.0.0, five other changes the same way (a `$proxy_add_x_forwarded_for`, a missing include, `keepalive_timeout 40s`, `proxy_cache_use_stale`, and `USER root`); each made exactly the matching test fail.

3. Start the stack and run the stack tests (`scripts/stack.sh test --run --yes` does both and stops the stack afterwards). I started the stack by hand in my copy, then ran the tests:

```
./gradlew stackTest 2>&1 | grep -E 'PASSED|FAILED|BUILD'
```

Output (version 1.0.0; the review pass ran the six tests of version 1.0.1 against the stack too, and all passed):

```
StackInvariantTest > answersAStaticFileFromTheCacheOnceItHoldsIt() PASSED
StackInvariantTest > replacesAForgedHeaderWithTheAddressTheProxySaw() PASSED
StackInvariantTest > neverAnswersAPrivateResponseFromTheCache() PASSED
StackInvariantTest > neverAnswersAPostFromTheCache() PASSED
StackInvariantTest > refusesABodyOverItsLimitWithProblemDetailsAtTheProxyOrAtSeatWave() PASSED
StackInvariantTest > ignoresAForgedHeaderSentStraightToSeatWave() PASSED
BUILD SUCCESSFUL in 1s
```

4. Check that the stack tests are not part of the normal check: run `./gradlew test` and look for `StackInvariantTest` in the output. It is not there (the tag is excluded).

A note on a limit met in version 1.0.0: two other tests in the repository (`BackendCliTest` and `BenchCliTest`) failed in a scratch copy whose folder path was longer than 200 characters, because a settings check accepted at most 200 characters for a file path. Version 1.0.1 accepts any path without control characters, and both pass in such a copy.

## Mistakes people make

- **Testing text instead of behavior, and believing it.** `NginxConfigRulesTest` checks what the files say, not what NGINX does. This repository pairs it with `StackInvariantTest` for that reason. Neither is enough alone.
- **A check that can pass by finding nothing.** A loop over zero matches passes trivially. Test 4 asserts `isNotEmpty()` first.
- **Writing a check that is wider than its message.** Version 1.0.0 merged the backend's and the proxy's published ports into one set and asked `containsAll`, so it could not tell whose port was missing, and missed one. Test 5 now compares each service's ports with that service's own listeners, with `isEqualTo`.
- **Relying on a unit in a pattern.** Version 1.0.0's Test 4 understood only seconds, so `1m` passed unchecked. Read the value with every unit the configuration language allows, as `nginxTime` does, and fail on one you cannot read.
- **Random test data without a guarantee.** A random pick can repeat. `hold` starts at a random seat but then counts upward, and moves on when a seat is taken.
- **A build input that is not declared.** The test task reads `compose.yaml`, `Dockerfile` and `config/` but declares only `nginx/` as an input, so after a change to one of the others Gradle can skip the tests as `UP-TO-DATE`. Use `--rerun` (or `clean`) after such a change.
- **Counting on a counter you did not read first.** Tests 3, 4 and 5 read the counter **before** and compare the **difference**, so earlier traffic cannot disturb them.
- **Running the stack tests in the normal build.** Without the tag they would fail whenever Docker is off.
- **Editing the real files to try a failure.** Do it in a copy.

## Check yourself

1. Why does `NginxConfigRulesTest` need no Docker?
   *It never starts NGINX. It reads `nginx/`, `compose.yaml` and the `Dockerfile` as text and checks the text with regular expressions and `contains`.*
2. What does `@Tag("stack")` do?
   *It marks the class so Gradle excludes it from `test` (and `check`) and includes it in `stackTest`.*
3. Why does test 3 compare SeatWave's counter before and after, rather than only checking a header?
   *A header can be missing or wrong. A counter that rises by exactly 2 shows that both `POST`s really reached SeatWave and none was answered from the cache.*
4. Why does `locations` remove comments before looking for `location `?
   *A comment that mentions the word would otherwise be cut out as a location block and checked as one.*
5. What is the limit of test 4 (`closesIdleUpstreamConnectionsBeforeSeatWaveDoes`)?
   *The pattern accepts only a number followed by `s`. A timeout written in minutes or milliseconds is not matched, so it is not checked.*

## Further reading

- JUnit 6 User Guide (tests, tags, lifecycle methods): https://docs.junit.org/current/user-guide/
- AssertJ core documentation: https://assertj.github.io/doc/
- `java.util.regex.Pattern` (Java 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html
- `java.nio.file.Files` (Java 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html
- `java.net.http` package: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
- Gradle test filtering and tags: https://docs.gradle.org/9.9.1/userguide/java_testing.html
