# The `forwarded`, `config` and `tls` tests, line by line

## What it is

Three test files, 21 tests in all:

- `forwarded/ClientAddressTest.java` (15 tests): the client-address rule of `java-forwarded-walkthrough.md`.
- `config/SettingsReaderTest.java` (3 tests): the typed settings reader of `java-config-json-tls-walkthrough.md`.
- `tls/TlsFilesTest.java` (3 tests): the TLS context builders of the same file.

A **unit test** calls one small piece of code directly, with made-up inputs, and checks the answer. There is no server and no network in these tests (except that the TLS tests read the TEST certificates). They run in milliseconds. The libraries are JUnit (finds and runs `@Test` methods; see `junit.md`) and AssertJ (readable checks such as `assertThat(x).isEqualTo(y)`; see `assertj.md`).

## Why this repository uses it

The client-address rule is the module's security invariant, so its tests are the most careful ones in the repository: every branch of "trusted or not", "readable or not" has a case. The settings reader and the TLS builders are the entry points of the server; a mistake there stops it from starting, so the tests check the exact words of the messages a learner will see.

## Where it appears

| File | What it covers |
|---|---|
| `forwarded/ClientAddressTest.java` | Forged and trusted headers, hop order, `Forwarded` syntax, unreadable input, limits, scheme, trusted lists, the view's checks. |
| `config/SettingsReaderTest.java` | One problem per bad key, valid reads, a missing file. |
| `tls/TlsFilesTest.java` | Both contexts build from the TEST files; clear messages for a missing file, an empty file and a wrong password. |

## How it works underneath

**Naming.** A test method name is a sentence: `takesTheRightMostHopThatIsNotATrustedProxyNeverTheLeftMost`. If it fails, the name says what stopped being true.

**Shape.** Arrange (build the input), act (call the code), assert (compare). Most tests here are five to ten lines.

**What the constants are.** The tests use addresses from ranges reserved for documentation (`198.51.100.0/24`, `203.0.113.0/24`, `192.0.2.0/24`, RFC 5737), so no real machine is ever involved. `10.209.9.10` is the proxy's fixed address in the Compose network and `127.0.0.1` is the sidecar's.

**Where the TEST files come from.** Gradle's `testCertificates` task writes them into `build/tls` and passes the folder to the tests as the system property `seatwave.tls.directory`. `TestBackend.TLS` reads it.

## Walkthrough of this repository's use

### `forwarded/ClientAddressTest.java`

```java
package io.plainround.seatwave.proxy.forwarded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientAddressTest {

  private static final InetAddress PROXY = InetAddress.ofLiteral("10.209.9.10");
  private static final InetAddress SIDECAR = InetAddress.ofLiteral("127.0.0.1");
  private static final InetAddress STRANGER = InetAddress.ofLiteral("198.51.100.20");
  private static final TrustedProxies TRUSTED =
      TrustedProxies.parse(List.of("10.209.9.10", "127.0.0.1"))
          .orOnErrorThrow(IllegalStateException::new);
```

The class defines the cast of characters as constants: `PROXY` (`10.209.9.10`, the reverse proxy), `SIDECAR` (`127.0.0.1`), `STRANGER` (`198.51.100.20`, a machine nobody trusts) and `TRUSTED`, the list with the first two. `InetAddress.ofLiteral` parses an address without any lookup. `TrustedProxies.parse(...)` returns a `Result`; `orOnErrorThrow(IllegalStateException::new)` turns a parse error into an exception, which is what a test wants.

```java
  @Test
  void ignoresAForgedForwardedForFromAPeerThatIsNotTrusted() {
    ForwardedHeaders forged = xff(List.of("203.0.113.7"), List.of("https"));

    ClientView view = ClientAddress.resolve(STRANGER, forged, TRUSTED, "http");

    assertThat(view).isEqualTo(new ClientView(STRANGER, "http", AddressSource.PEER));
  }
```

**Forged `X-Forwarded-For`.** A stranger sends `X-Forwarded-For: 203.0.113.7` and `X-Forwarded-Proto: https`. The result must equal `ClientView(STRANGER, "http", PEER)`: the stranger's own address, the listener's scheme, and the source `PEER`. Record equality compares all three fields. The helper `xff` at the end of the file builds the headers.

```java
  @Test
  void ignoresAForgedForwardedHeaderFromAPeerThatIsNotTrusted() {
    ForwardedHeaders forged =
        new ForwardedHeaders(List.of("for=203.0.113.7;proto=https"), List.of(), List.of());

    ClientView view = ClientAddress.resolve(STRANGER, forged, TRUSTED, "https");

    assertThat(view.address()).isEqualTo(STRANGER);
    assertThat(view.source()).isEqualTo(AddressSource.PEER);
  }
```

**Forged `Forwarded`.** The same for the standard header, with the listener scheme `https`: the address is the stranger's and the source is `PEER`.

```java
  @Test
  void believesTheProxysXForwardedForAndProto() {
    ClientView view =
        ClientAddress.resolve(
            PROXY, xff(List.of("203.0.113.7"), List.of("https")), TRUSTED, "http");

    assertThat(view)
        .isEqualTo(
            new ClientView(
                InetAddress.ofLiteral("203.0.113.7"), "https", AddressSource.X_FORWARDED_FOR));
  }
```

**Believed from the proxy.** The proxy's `X-Forwarded-For` and proto are used: the address `203.0.113.7`, the scheme `https`, the source `X_FORWARDED_FOR`.

```java
  @Test
  void takesTheRightMostHopThatIsNotATrustedProxyNeverTheLeftMost() {
    // A client wrote 192.0.2.66 itself; a proxy that appends would keep it on the left.
    ForwardedHeaders appended = xff(List.of("192.0.2.66, 203.0.113.7, 10.209.9.10"), List.of());

    ClientView view = ClientAddress.resolve(SIDECAR, appended, TRUSTED, "http");

    assertThat(view.address()).isEqualTo(InetAddress.ofLiteral("203.0.113.7"));
    assertThat(view.scheme()).isEqualTo("http");
  }
```

**Right-most untrusted hop.** The header `192.0.2.66, 203.0.113.7, 10.209.9.10` arrives from the sidecar. The comment explains the first entry: "A client wrote 192.0.2.66 itself; a proxy that appends would keep it on the left." The client is `203.0.113.7`, and the scheme stays `http`.

```java
  @Test
  void readsEveryLineOfARepeatedHeaderInOrder() {
    ForwardedHeaders twoLines = xff(List.of("192.0.2.66", "203.0.113.7"), List.of());

    ClientView view = ClientAddress.resolve(PROXY, twoLines, TRUSTED, "http");

    assertThat(view.address()).isEqualTo(InetAddress.ofLiteral("203.0.113.7"));
  }
```

**Several lines of one header.** A header sent on two lines (`192.0.2.66`, then `203.0.113.7`) is read as one chain in order; the last line's address is the client.

```java
  @Test
  void readsTheStandardForwardedHeaderWithAQuotedIpv6AddressAndAPort() {
    ForwardedHeaders forwarded =
        new ForwardedHeaders(
            List.of("for=\"[2001:db8:cafe::17]:4711\";proto=https;by=10.209.9.10"),
            List.of("198.51.100.1"),
            List.of());

    ClientView view = ClientAddress.resolve(PROXY, forwarded, TRUSTED, "http");

    assertThat(view)
        .isEqualTo(
            new ClientView(
                InetAddress.ofLiteral("2001:db8:cafe::17"), "https", AddressSource.FORWARDED));
  }
```

**Standard syntax.** `for="[2001:db8:cafe::17]:4711";proto=https;by=10.209.9.10`: a quoted IPv6 address in brackets, with a port, then a protocol and a `by` field. The test also supplies an `X-Forwarded-For` of `198.51.100.1` to prove `Forwarded` wins. The expected view is the IPv6 address, `https` and `FORWARDED`.

```java
  @Test
  void readsAnIpv4ForwardedNodeWithAPort() {
    ForwardedHeaders forwarded =
        new ForwardedHeaders(List.of("for=\"203.0.113.7:4711\""), List.of(), List.of());

    ClientView view = ClientAddress.resolve(PROXY, forwarded, TRUSTED, "https");

    assertThat(view.address()).isEqualTo(InetAddress.ofLiteral("203.0.113.7"));
    assertThat(view.scheme()).isEqualTo("https");
  }
```

**IPv4 with a port.** `for="203.0.113.7:4711"` gives `203.0.113.7`. There is no `proto`, so the scheme is the listener's (`https`).

```java
  @Test
  void fallsBackToThePeerWhenATrustedProxySendsAHopThatIsNotAnAddress() {
    for (String unreadable : List.of("unknown", "fan.seatwave.example", "", "203.0.113.7, x")) {
      ClientView view =
          ClientAddress.resolve(PROXY, xff(List.of(unreadable), List.of()), TRUSTED, "http");

      assertThat(view).isEqualTo(new ClientView(PROXY, "http", AddressSource.UNREADABLE));
    }
  }
```

**Hops that are not addresses.** A `for` loop runs the same check over four bad `X-Forwarded-For` values: `unknown`, a host name, an empty string, and a list whose second item is `x`. Each must give exactly `ClientView(PROXY, "http", UNREADABLE)`. The host name case also proves that nothing is looked up.

```java
  @Test
  void fallsBackToThePeerWhenAForwardedElementIsMalformed() {
    for (String malformed :
        List.of(
            "for", "proto=https", "for=203.0.113.7;proto=gopher", "=x", "for=\"[2001:db8::1\"")) {
      ForwardedHeaders headers = new ForwardedHeaders(List.of(malformed), List.of(), List.of());

      ClientView view = ClientAddress.resolve(PROXY, headers, TRUSTED, "https");

      assertThat(view.source()).as(malformed).isEqualTo(AddressSource.UNREADABLE);
      assertThat(view.address()).isEqualTo(PROXY);
    }
  }
```

**Malformed `Forwarded` elements.** Five bad headers: `for` (no value), `proto=https` (no address), `for=203.0.113.7;proto=gopher` (a scheme that is not http or https), `=x` (no name) and `for="[2001:db8::1"` (an unclosed bracket). Each must be `UNREADABLE` with the proxy's address. `as(malformed)` puts the failing input in the failure message.

```java
  @Test
  void refusesAChainLongerThanSixteenHopsOrALineLongerThanTheLimit() {
    String seventeenHops = String.join(", ", java.util.Collections.nCopies(17, "203.0.113.7"));
    String longLine = "203.0.113.7" + " ".repeat(ClientAddress.MAX_HEADER_CHARACTERS);

    assertThat(
            ClientAddress.resolve(PROXY, xff(List.of(seventeenHops), List.of()), TRUSTED, "http")
                .source())
        .isEqualTo(AddressSource.UNREADABLE);
    assertThat(
            ClientAddress.resolve(PROXY, xff(List.of(longLine), List.of()), TRUSTED, "http")
                .source())
        .isEqualTo(AddressSource.UNREADABLE);
    ForwardedHeaders longForwarded =
        new ForwardedHeaders(List.of("for=" + longLine), List.of(), List.of());
    assertThat(ClientAddress.resolve(PROXY, longForwarded, TRUSTED, "http").source())
        .isEqualTo(AddressSource.UNREADABLE);
  }
```

**Limits.** A chain of 17 copies of one address (over the 16-hop limit), a line of 11 characters plus 1,024 spaces (over the line limit), and the same long line inside a `Forwarded` header are all `UNREADABLE`. `Collections.nCopies(17, "203.0.113.7")` repeats a value; `String.join(", ", ...)` glues them with commas; `" ".repeat(n)` makes spaces.

```java
  @Test
  void believesNoSchemeWhenTheProtoHeaderIsInvalidOrSentTwice() {
    ClientView invalid =
        ClientAddress.resolve(
            PROXY, xff(List.of("203.0.113.7"), List.of("gopher")), TRUSTED, "http");
    ClientView twice =
        ClientAddress.resolve(
            PROXY, xff(List.of("203.0.113.7"), List.of("https", "http")), TRUSTED, "http");

    assertThat(invalid.source()).isEqualTo(AddressSource.UNREADABLE);
    assertThat(twice.source()).isEqualTo(AddressSource.UNREADABLE);
  }
```

**Scheme headers.** An `X-Forwarded-Proto` of `gopher` makes the request `UNREADABLE`; so does a proto sent on two lines (`https` and `http`).

```java
  @Test
  void treatsATrustedProxyWithoutForwardingHeadersAsTheClient() {
    ClientView view = ClientAddress.resolve(PROXY, ForwardedHeaders.none(), TRUSTED, "http");

    assertThat(view).isEqualTo(new ClientView(PROXY, "http", AddressSource.PEER));
  }
```

**No headers at all** from a trusted proxy: the proxy is the client, the source is `PEER`.

```java
  @Test
  void takesTheFirstHopWhenEveryHopIsATrustedProxy() {
    ClientView view =
        ClientAddress.resolve(
            SIDECAR, xff(List.of("10.209.9.10"), List.of("https")), TRUSTED, "http");

    assertThat(view.address()).isEqualTo(PROXY);
    assertThat(view.scheme()).isEqualTo("https");
  }
```

**Every hop trusted.** The sidecar sends `X-Forwarded-For: 10.209.9.10` with proto `https`. All hops are trusted proxies, so the first hop (`PROXY`) is the client, and the scheme is `https`. Only the `X-Forwarded-For` path is tested; the same case with `Forwarded` is not, and that path loses the `proto` (see `java-forwarded-walkthrough.md`).

```java
  @Test
  void refusesTrustedProxiesThatAreNamesOrEmpty() {
    assertThat(TrustedProxies.parse(List.of("proxy.seatwave.example")).isError()).isTrue();
    assertThat(TrustedProxies.parse(List.of()).isError()).isTrue();
    assertThatThrownBy(() -> new TrustedProxies(java.util.Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Trusted lists.** A name is refused, an empty list is refused, and building `TrustedProxies` from an empty set throws `IllegalArgumentException`. `isError()` is the `Result` method that says whether it holds an error.

```java
  @Test
  void refusesAViewWithASchemeOtherThanHttpOrHttps() {
    assertThatThrownBy(() -> new ClientView(PROXY, "ftp", AddressSource.PEER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ftp");
  }
```

**The view's own check.** `new ClientView(PROXY, "ftp", PEER)` throws `IllegalArgumentException` and the message contains `ftp`.

```java
  private static ForwardedHeaders xff(List<String> forwardedFor, List<String> proto) {
    return new ForwardedHeaders(List.of(), forwardedFor, proto);
  }
}
```

`xff` builds `ForwardedHeaders` with only `X-Forwarded-For` and `X-Forwarded-Proto` lines.

What these tests do not cover: unbracketed IPv6 in `Forwarded` (`for=2001:db8::1` is read as `0.0.7.209`), short IPv4 forms such as `127.1`, an IPv6 address in `X-Forwarded-For`, `proto` in `Forwarded` combined with all hops trusted, and a proto header sent without `X-Forwarded-For` or `Forwarded`.

### `config/SettingsReaderTest.java`

```java
package io.plainround.seatwave.proxy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final Pattern WORD = Pattern.compile("[a-z]+");
```

`WORD` is a pattern that allows only lowercase letters. `assertThatThrownBy` (a static import) checks that code throws.

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
```

**One problem per bad key.** The test builds a `Properties` with four bad values and one missing key. It calls `intBetween("count", 1, 10)` on `seven`, `intBetween("range", 1, 10)` on `99`, `textMatching("word", WORD)` on `Upper`, `listMatching("list", WORD)` on `one, Two`, `text("absent")`, and `addProblem(...)`. Then `containsExactly` demands the six problem lines, in this order and with these exact words. The order shows that problems are recorded as found.

```java
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

**Valid values.** `waitMillis=250` becomes `Duration.ofMillis(250)`; `names=alpha, beta` becomes the two items with the space stripped; and no problem is recorded.

```java
  @Test
  void saysThatAMissingFileDoesNotExist(@TempDir Path folder) {
    Path missing = folder.resolve("absent.properties");

    assertThatThrownBy(() -> SettingsReader.load(missing))
        .isInstanceOf(IOException.class)
        .hasMessage("the settings file " + missing + " does not exist");
  }
}
```

**Missing file.** `@TempDir` gives the test an empty folder that JUnit deletes afterwards. Loading a file that is not there must throw `IOException` with the message `the settings file <path> does not exist`.

Not tested: the malformed `\u` escape that escapes `SettingsFiles.load` as an `IllegalArgumentException`; a blank value (`key=   `); a list with an empty item (`a,,b`); and `SettingsFiles` itself, which is covered only through `BackendCliTest`.

### `tls/TlsFilesTest.java`

```java
package io.plainround.seatwave.proxy.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.proxy.backend.TestBackend;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsFilesTest {
```

```java
  @Test
  void buildsBothSidesFromTheTestFiles() throws IOException {
    assertThat(
            TlsFiles.serverContext(
                    TestBackend.TLS.resolve("seatwave-server.p12"),
                    TestBackend.TLS.resolve("store.password"))
                .getProtocol())
        .isEqualTo("TLS");
    assertThat(
            TlsFiles.clientContext(TestBackend.TLS.resolve("seatwave-test-ca.pem")).getProtocol())
        .isEqualTo("TLS");
  }
```

**Both sides build.** The server context is made from `seatwave-server.p12` and `store.password`; the client context from `seatwave-test-ca.pem`. The only assertion is that `getProtocol()` is `TLS`. That is a weak check: `SSLContext.getInstance("TLS")` returns that name whatever the files contain. What really proves the contexts work is `BackendServerTest.answersOverTlsVersionOnePointThreeWithTheTestCertificate`, which completes a handshake.

```java
  @Test
  void namesTheMissingFileAndTheTaskThatMakesIt(@TempDir Path folder) {
    Path missing = folder.resolve("absent.pem");

    assertThatThrownBy(() -> TlsFiles.clientContext(missing))
        .isInstanceOf(IOException.class)
        .hasMessage("the file " + missing + " does not exist; run ./gradlew testCertificates");
    assertThatThrownBy(() -> TlsFiles.serverContext(missing, folder.resolve("absent.password")))
        .hasMessageContaining("the password file")
        .hasMessageContaining("run ./gradlew testCertificates");
  }
```

**Clear messages.** For a client file that is missing, the message must be exactly `the file <path> does not exist; run ./gradlew testCertificates`. For a server call with both files missing, the message must mention `the password file` and the same advice; the password is read first, so that is the error the learner sees.

```java
  @Test
  void refusesAFileThatHoldsNoCertificateOrAWrongPassword(@TempDir Path folder) throws IOException {
    Path empty = Files.writeString(folder.resolve("empty.pem"), "", StandardCharsets.UTF_8);
    Path wrongPassword =
        Files.writeString(folder.resolve("wrong.password"), "not-it", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> TlsFiles.clientContext(empty)).isInstanceOf(IOException.class);
    assertThatThrownBy(() ->
            TlsFiles.serverContext(TestBackend.TLS.resolve("seatwave-server.p12"), wrongPassword))
        .isInstanceOf(IOException.class);
  }
}
```

**Bad contents.** An empty `.pem` file gives an `IOException` (no certificate), and a correct key store with a wrong password (`not-it`) gives an `IOException` too. Neither test checks the message, so a change of wording would pass.

## Try it yourself

Run in a copy of the repository. The tests passed. Timings differ on every run.

**1. Run only these tests.**

```
./gradlew test --tests '*ClientAddressTest' --tests '*SettingsReaderTest' --tests '*TlsFilesTest'
```

**2. Break the rule and watch a test fail.** In a scratch copy (never in your real clone), change the line `if (!trusted.isTrusted(peer)) {` in `ClientAddress.resolve` to `if (false) {` and run command 1. It printed:

```
ClientAddressTest > ignoresAForgedForwardedForFromAPeerThatIsNotTrusted() FAILED
ClientAddressTest > ignoresAForgedForwardedHeaderFromAPeerThatIsNotTrusted() FAILED
15 tests completed, 2 failed
```

Put the line back afterwards. These two tests are the guard of the invariant.

**3. See a message the tests pin down.** Move `build/tls` aside (in a scratch copy) and start the backend directly:

```
java -cp <classes and library jars> io.plainround.seatwave.proxy.backend.BackendCli config/backend-local.properties
```

It printed `SeatWave cannot start: the password file build/tls/store.password does not exist; run ./gradlew testCertificates`. Move the folder back afterwards.

**4. Read the report.** `build/reports/tests/test/index.html` after a run lists each test by its sentence-name.

**5. Check the totals.** The three files hold 15 + 3 + 3 = 21 tests (counted with `grep -c '^  @Test'` on each file).

## Mistakes people make

- **Testing only the happy path of a security rule.** The invariant has more refusal cases than acceptance cases, and the tests reflect that.
- **Using real addresses or names in tests.** A host name in a test would trigger a DNS lookup. The tests use reserved documentation ranges and literals.
- **Asserting a name and not behavior.** `getProtocol()` equal to `TLS` hardly tests anything.
- **Asserting `isInstanceOf(IOException.class)` without the message.** The wording is part of the experience of a learner; the better tests (`namesTheMissingFileAndTheTaskThatMakesIt`) check it.
- **Skipping the cases around a rule.** The gaps listed above are the ones where a real defect was found by running the code.

## Check yourself

1. In `takesTheRightMostHopThatIsNotATrustedProxyNeverTheLeftMost`, why is `192.0.2.66` not the client? Answer: it is the left-most entry, which the client itself can write; the code reads from the right and stops at the first hop that is not a trusted proxy.
2. What does a stranger's forged header produce? Answer: the stranger's own address, the listener's scheme and the source `PEER`.
3. Why do the tests use `203.0.113.7` and `198.51.100.20`? Answer: they are reserved for documentation (RFC 5737), so no real machine can answer.
4. What does `containsExactly` demand in `collectsOneProblemPerMissingOrInvalidKey`? Answer: exactly those six lines, in that order.
5. Which test proves that the TLS contexts really work, and why is `TlsFilesTest` alone not enough? Answer: `BackendServerTest.answersOverTlsVersionOnePointThreeWithTheTestCertificate`, because it completes a handshake. `TlsFilesTest` mostly checks that building does not throw and that messages are clear.

## Further reading

- JUnit 6 User Guide (6.1.3 is pinned): https://docs.junit.org/current/user-guide/
- AssertJ Core documentation (3.27.7): https://assertj.github.io/doc/
- RFC 5737, IPv4 Address Blocks Reserved for Documentation: https://www.rfc-editor.org/rfc/rfc5737
- RFC 7239, Forwarded HTTP Extension: https://www.rfc-editor.org/rfc/rfc7239
- Java SE 25 API, `javax.net.ssl.SSLContext`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLContext.html
