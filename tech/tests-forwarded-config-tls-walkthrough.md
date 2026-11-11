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
| `forwarded/ClientAddressTest.java` | Forged and trusted headers, hop order, `Forwarded` syntax, unreadable input, limits, scheme, IPv6 without brackets, short IPv4 forms, trusted lists, the view's checks. |
| `config/SettingsReaderTest.java` | One problem per bad key, valid reads, a missing file, deep and odd file paths, a control character, a broken escape. |
| `json/JsonCodecTest.java` | Every refusal of the JSON codec with its pointer and its words, and writing. |
| `tls/TlsFilesTest.java` | The two contexts complete a real handshake; clear messages for a missing file, an empty file and a wrong password. |

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

**Every hop trusted.** The sidecar sends `X-Forwarded-For: 10.209.9.10` with proto `https`. All hops are trusted proxies, so the first hop (`PROXY`) is the client, and the scheme is `https`. The same case with `Forwarded` is tested below.

```java
  @Test
  void refusesAnIpv6ForwardedNodeWithoutItsBrackets() {
    // RFC 7239 requires the brackets; cut at its first colon, 2001:db8::1 became 0.0.7.209.
    for (String bare : List.of("for=2001:db8::1", "for=\"2001:db8::1\"", "for=[1.2.3.4]")) {
      ForwardedHeaders headers = new ForwardedHeaders(List.of(bare), List.of(), List.of());

      ClientView view = ClientAddress.resolve(PROXY, headers, TRUSTED, "https");

      assertThat(view).as(bare).isEqualTo(new ClientView(PROXY, "https", AddressSource.UNREADABLE));
    }
  }

  @Test
  void refusesAnIpv4NodeWithMoreThanOnePort() {
    ForwardedHeaders headers =
        new ForwardedHeaders(List.of("for=\"203.0.113.7:4711:80\""), List.of(), List.of());

    ClientView view = ClientAddress.resolve(PROXY, headers, TRUSTED, "https");

    assertThat(view.source()).isEqualTo(AddressSource.UNREADABLE);
  }

  @Test
  void refusesTheShortIpv4FormsThatOfLiteralWouldAccept() {
    for (String shortForm : List.of("127.1", "2001", "10.209.2330")) {
      ClientView forwardedFor =
          ClientAddress.resolve(PROXY, xff(List.of(shortForm), List.of()), TRUSTED, "http");
      ClientView forwarded =
          ClientAddress.resolve(
              PROXY,
              new ForwardedHeaders(List.of("for=" + shortForm), List.of(), List.of()),
              TRUSTED,
              "http");

      assertThat(forwardedFor.source()).as(shortForm).isEqualTo(AddressSource.UNREADABLE);
      assertThat(forwarded.source()).as(shortForm).isEqualTo(AddressSource.UNREADABLE);
    }
    assertThat(TrustedProxies.parse(List.of("127.1")).isError()).isTrue();
  }

  @Test
  void readsAnIpv6AddressInXForwardedFor() {
    ClientView view =
        ClientAddress.resolve(
            PROXY, xff(List.of("2001:db8::17"), List.of("https")), TRUSTED, "http");

    assertThat(view)
        .isEqualTo(
            new ClientView(
                InetAddress.ofLiteral("2001:db8::17"), "https", AddressSource.X_FORWARDED_FOR));
  }

  @Test
  void takesTheFirstHopsProtoWhenEveryForwardedHopIsATrustedProxy() {
    ForwardedHeaders forwarded =
        new ForwardedHeaders(List.of("for=10.209.9.10;proto=https"), List.of(), List.of());

    ClientView view = ClientAddress.resolve(SIDECAR, forwarded, TRUSTED, "http");

    // The same answer X-Forwarded-For with X-Forwarded-Proto gives (the test above).
    assertThat(view).isEqualTo(new ClientView(PROXY, "https", AddressSource.FORWARDED));
  }

  @Test
  void believesNoProtoHeaderThatComesWithoutAnAddressHeader() {
    // NGINX always sends both; a proto header alone says nothing about the client, so the trusted
    // peer is the client and the listener's own scheme stands.
    ForwardedHeaders protoOnly = new ForwardedHeaders(List.of(), List.of(), List.of("https"));

    ClientView view = ClientAddress.resolve(PROXY, protoOnly, TRUSTED, "http");

    assertThat(view).isEqualTo(new ClientView(PROXY, "http", AddressSource.PEER));
  }

```

- **IPv6 without brackets.** Three nodes RFC 7239 does not allow, each from the trusted proxy: `for=2001:db8::1` and the same in quotes (an IPv6 address without its brackets), and `for=[1.2.3.4]` (brackets around an IPv4 address). Each must give the peer and `UNREADABLE`. The comment records what the first version did with the first one: it cut it at its first colon and read `2001` as `0.0.7.209`. `.as(bare)` labels each check with the input, so a failure names it.
- **Two ports.** `203.0.113.7:4711:80` has two colons; no real node looks like that, so it is `UNREADABLE`.
- **Short IPv4 forms.** `127.1`, `2001` and `10.209.2330` are forms `InetAddress.ofLiteral` would accept (as `127.0.0.1`, `0.0.7.209` and `10.209.9.26`), in `X-Forwarded-For` and in `Forwarded` alike. All must be `UNREADABLE`, and `127.1` is refused as a trusted proxy too.
- **IPv6 in `X-Forwarded-For`.** That header writes an IPv6 address plainly, with no brackets, and NGINX writes it that way; `2001:db8::17` must be read, with the scheme `https`.
- **Every `Forwarded` hop trusted.** `for=10.209.9.10;proto=https` from the sidecar: the client is the first hop, and its `proto` is believed. The comment points at the `X-Forwarded-For` test above, which gives the same answer. Version 1.0.0 returned `http` here.
- **A proto header alone.** `X-Forwarded-Proto: https` with no address header says nothing about the client, so the trusted proxy is the client and the listener's own `http` stands, with the source `PEER`. The comment explains why that is the right answer: NGINX always sends both.

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

Every gap that the first version's notes listed here (unbracketed IPv6 in `Forwarded`, short IPv4 forms, IPv6 in `X-Forwarded-For`, `proto` in `Forwarded` with all hops trusted, a proto header alone) now has a test above.

### `config/SettingsReaderTest.java`

```java
package io.plainround.seatwave.proxy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

  @Test
  void acceptsAFilePathInADeepFolderWithSpacesAndPlusSigns() {
    String deepPath = "/checkouts/My Projects+Labs/" + "nested-folder/".repeat(20) + "ca.pem";
    Properties properties = new Properties();
    properties.setProperty("tls.authorityPem", deepPath);
    SettingsReader reader = new SettingsReader(properties);

    String path = reader.filePath("tls.authorityPem");

    assertThat(deepPath).hasSizeGreaterThan(220);
    assertThat(path).isEqualTo(deepPath);
    assertThat(reader.problems()).isEmpty();
  }

  @Test
  void refusesAFilePathWithAControlCharacter() {
    Properties properties = new Properties();
    properties.setProperty("tls.authorityPem", "build/tls/ca\t.pem");
    SettingsReader reader = new SettingsReader(properties);

    String path = reader.filePath("tls.authorityPem");

    assertThat(path).isEmpty();
    assertThat(reader.problems())
        .singleElement()
        .asString()
        .startsWith("tls.authorityPem must match");
  }

  @Test
  void reportsABrokenEscapeAsAnUnreadableSettingsFile(@TempDir Path folder) throws IOException {
    Path broken =
        Files.writeString(
            folder.resolve("broken.properties"),
            "http.bindAddress=\\u12\n",
            StandardCharsets.UTF_8);

    assertThatThrownBy(() -> SettingsReader.load(broken))
        .isInstanceOf(IOException.class)
        .hasMessage(
            "the settings file "
                + broken
                + " is not a valid properties file: Malformed \\uxxxx encoding.");
  }
}
```

**Missing file.** `@TempDir` gives the test an empty folder that JUnit deletes afterwards. Loading a file that is not there must throw `IOException` with the message `the settings file <path> does not exist`.

**File paths.** `filePath` must accept a path of more than 220 characters with a space and a `+` in it (`/checkouts/My Projects+Labs/` and twenty nested folders), and record no problem: that is the shape of a real checkout. A tab inside a path is a control character, so `filePath` returns an empty text and records exactly one problem (`singleElement()` fails unless the list holds one item; `asString()` lets the next check treat it as text).

**A broken escape.** A file holding `http.bindAddress=\u12` must make `load` throw an `IOException` with the exact message `the settings file <path> is not a valid properties file: Malformed \uxxxx encoding.`, not the unchecked `IllegalArgumentException` that `Properties.load` itself throws. In the Java text of the test, `\\u12` is a backslash and `u12`, and `\\uxxxx` the same in the expected message.

Still not tested: a blank value (`key=   `); a list with an empty item (`a,,b`); and `SettingsFiles` itself, which is covered only through `BackendCliTest`.

### `json/JsonCodecTest.java`

This class tests the JSON codec by itself, without a server: every reason the codec gives for refusing a body, with the JSON pointer and the words a client reads. It has its own small record, `Ticket`, with one number and one text, the two kinds of field the codec checks.

```java
package io.plainround.seatwave.proxy.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

  private final JsonCodec json = new JsonCodec();

  @Test
  void bindsABodyThatMatchesTheRecordExactly() {
    Result<Ticket, JsonCodec.BindingProblem> read =
        json.read("{\"count\":2,\"name\":\"arena\"}", Ticket.class);

    assertThat(read.discardError()).contains(new Ticket(2, "arena"));
  }

```

- `json` is one codec for the class's tests. JUnit makes a new object of the class for each test, but a codec holds no state anyway.
- The first test binds a body that matches `Ticket` exactly. `discardError()` turns the `Result` into an `Optional`, which must hold `new Ticket(2, "arena")`.

```java
  @Test
  void refusesAnEmptyBodyAndABodyThatIsNotAnObject() {
    assertThat(problem("")).isEqualTo(whole("the body is empty; send a JSON object"));
    assertThat(problem("[1]")).isEqualTo(whole("the body must be a JSON object"));
  }

  @Test
  void refusesDataAfterTheObjectWhetherOrNotItIsJson() {
    assertThat(problem("{\"count\":2,\"name\":\"a\"} {}"))
        .isEqualTo(whole("the body has data after the JSON object"));
    assertThat(problem("{\"count\":2,\"name\":\"a\"} x"))
        .isEqualTo(whole("the body has data after the JSON object"));
  }

  @Test
  void namesAFieldThatAppearsTwice() {
    assertThat(problem("{\"count\":1,\"count\":2,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "appears more than once"));
  }

```

- An empty body and a body that is not an object (`[1]`) are refused as a whole: the pointer is empty (`whole`, below).
- Data after the object is refused with the same words whether it is JSON (`{}`) or not (`x`). The second case is the one the first version called "not valid JSON".
- A field that appears twice is named: the pointer `/count` and `appears more than once`.

```java
  @Test
  void namesAnUnknownFieldAWrongTypeANullNumberAndANumberOutOfRange() {
    assertThat(problem("{\"count\":1,\"name\":\"a\",\"a/b\":1}"))
        .isEqualTo(new JsonCodec.BindingProblem("/a~1b", "is not a field of this request"));
    assertThat(problem("{\"count\":\"1\",\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "has the wrong type"));
    assertThat(problem("{\"count\":1,\"name\":5}"))
        .isEqualTo(new JsonCodec.BindingProblem("/name", "has the wrong type"));
    assertThat(problem("{\"count\":null,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "has the wrong type"));
    assertThat(problem("{\"count\":99999999999999999999,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "is out of range for this field"));
  }

  @Test
  void callsMalformedTextInvalidJson() {
    assertThat(problem("{\"count\":")).isEqualTo(whole("the body is not valid JSON"));
    assertThat(problem("{count:1}")).isEqualTo(whole("the body is not valid JSON"));
  }

  @Test
  void writesARecordAndRefusesAValueThatIsNotWritable() {
    assertThat(json.write(new Ticket(3, "x"))).isEqualTo("{\"count\":3,\"name\":\"x\"}");
    assertThatThrownBy(() -> json.write(new Object()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not writable as JSON");
  }
```

- **Unknown, wrong, null, too big.** An unknown field named `a/b` gives the pointer `/a~1b`: in a JSON pointer a `/` inside a name is written `~1` (RFC 6901). A number written as text, a text written as a number, a `null` for the primitive `int` and a number too big for an `int` each name `/count` or `/name` with their reason.
- **Malformed text.** An object cut off after `"count":` and an object with an unquoted name, `{count:1}`, are both "not valid JSON".
- **Writing.** A record is written with its fields in declaration order. A plain `new Object()` has no fields Jackson can write, so `write` throws `IllegalArgumentException` with `not writable as JSON`: a programming error, not a client's.

```java
  private JsonCodec.BindingProblem problem(String body) {
    return switch (json.read(body, Ticket.class)) {
      case Result.Success<Ticket, JsonCodec.BindingProblem>(Ticket bound) ->
          throw new AssertionError("bound " + bound + " from " + body);
      case Result.Error<Ticket, JsonCodec.BindingProblem>(JsonCodec.BindingProblem refused) ->
          refused;
    };
  }

  private static JsonCodec.BindingProblem whole(String detail) {
    return new JsonCodec.BindingProblem("", detail);
  }

  /** A record with one primitive and one text field, the two shapes the codec checks. */
  record Ticket(int count, String name) {}
}
```

- `problem(body)` reads a body that must be refused and returns the problem. The `switch` over the `Result` has two cases: a success fails the test at once with an `AssertionError` that says what was bound from which body, and an error is returned.
- `whole(detail)` is a problem of the whole body: the empty pointer and the detail.
- `Ticket` is package-private and nested, which is enough for Jackson to bind it.

### `tls/TlsFilesTest.java`

```java
package io.plainround.seatwave.proxy.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.proxy.backend.TestBackend;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsFilesTest {
```

```java
  @Test
  void buildsTwoSidesThatCompleteAHandshakeWithTheTestCertificate() throws Exception {
    SSLContext server =
        TlsFiles.serverContext(
            TestBackend.TLS.resolve("seatwave-server.p12"),
            TestBackend.TLS.resolve("store.password"));
    SSLContext client = TlsFiles.clientContext(TestBackend.TLS.resolve("seatwave-test-ca.pem"));

    try (SSLServerSocket listener =
            (SSLServerSocket)
                server
                    .getServerSocketFactory()
                    .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ExecutorService accepting = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> serverSide =
          accepting.submit(() -> {
            try (SSLSocket accepted = (SSLSocket) listener.accept()) {
              accepted.startHandshake();
              return accepted.getSession().getProtocol();
            }
          });
      try (SSLSocket connected =
          (SSLSocket)
              client
                  .getSocketFactory()
                  .createSocket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
        connected.startHandshake();
        X509Certificate presented =
            (X509Certificate) connected.getSession().getPeerCertificates()[0];

        // The client trusts the server only because the TEST CA signed it, and sees its name.
        assertThat(presented.getSubjectX500Principal().getName())
            .isEqualTo("CN=SeatWave TEST server");
        assertThat(serverSide.get()).isEqualTo(connected.getSession().getProtocol());
      }
    }
  }
```

**The two sides complete a handshake.** The server context is made from `seatwave-server.p12` and `store.password`; the client context from `seatwave-test-ca.pem`. The server side listens on a free port of the loopback address (`createServerSocket(0, 1, ...)`: port 0, a queue of one connection) and, on a virtual thread, accepts one connection and finishes its TLS handshake. The client side connects and finishes its own. Only a client that trusts the TEST CA can complete it against the server's TEST certificate, so a passing handshake proves both contexts were built from the right files. The client then reads the certificate the server presented (`getPeerCertificates()[0]`, cast to `X509Certificate`, the standard certificate type) and checks its name, `CN=SeatWave TEST server`, and both sides must agree on the protocol (`TLSv1.3`). The first version only checked that `getProtocol()` was `TLS`, which `SSLContext.getInstance("TLS")` returns whatever the files contain.

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

    Path keystore = TestBackend.TLS.resolve("seatwave-server.p12");

    assertThatThrownBy(() -> TlsFiles.clientContext(empty))
        .isInstanceOf(IOException.class)
        .hasMessage("the file " + empty + " holds no certificate");
    assertThatThrownBy(() -> TlsFiles.serverContext(keystore, wrongPassword))
        .isInstanceOf(IOException.class)
        .hasMessageStartingWith(
            "the keystore " + keystore + " cannot be opened with the password in " + wrongPassword);
  }
}
```

**Bad contents.** An empty `.pem` file gives an `IOException` with exactly `the file <path> holds no certificate`. A correct key store with a wrong password (`not-it`) gives an `IOException` whose message starts with `the keystore <path> cannot be opened with the password in <path>`; the JDK's own words, `keystore password was incorrect`, follow. Both messages are now checked, so a change of wording fails the test.

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
30 tests completed, 2 failed
```

Put the line back afterwards. These two tests are the guard of the invariant.

**3. See a message the tests pin down.** Move `build/tls` aside (in a scratch copy) and start the backend directly:

```
java -cp <classes and library jars> io.plainround.seatwave.proxy.backend.BackendCli config/backend-local.properties
```

It printed `SeatWave cannot start: the password file build/tls/store.password does not exist; run ./gradlew testCertificates`. Move the folder back afterwards.

**4. Read the report.** `build/reports/tests/test/index.html` after a run lists each test by its sentence-name.

**5. Check the totals.** The four files hold 21 + 6 + 3 + 7 = 37 tests (counted with `grep -c '^  @Test'` on each file of version 1.0.1). `JsonCodecTest` is not part of command 1; run it with `--tests '*JsonCodecTest'`.

## Mistakes people make

- **Testing only the happy path of a security rule.** The invariant has more refusal cases than acceptance cases, and the tests reflect that.
- **Using real addresses or names in tests.** A host name in a test would trigger a DNS lookup. The tests use reserved documentation ranges and literals.
- **Asserting a name and not behavior.** `getProtocol()` equal to `TLS` hardly tests anything; a real handshake does.
- **Asserting `isInstanceOf(IOException.class)` without the message.** The wording is part of the experience of a learner; the better tests (`namesTheMissingFileAndTheTaskThatMakesIt`) check it.
- **Skipping the cases around a rule.** The gaps listed above are the ones where a real defect was found by running the code.

## Check yourself

1. In `takesTheRightMostHopThatIsNotATrustedProxyNeverTheLeftMost`, why is `192.0.2.66` not the client? Answer: it is the left-most entry, which the client itself can write; the code reads from the right and stops at the first hop that is not a trusted proxy.
2. What does a stranger's forged header produce? Answer: the stranger's own address, the listener's scheme and the source `PEER`.
3. Why do the tests use `203.0.113.7` and `198.51.100.20`? Answer: they are reserved for documentation (RFC 5737), so no real machine can answer.
4. What does `containsExactly` demand in `collectsOneProblemPerMissingOrInvalidKey`? Answer: exactly those six lines, in that order.
5. Which test proves that the TLS contexts really work? Answer: `TlsFilesTest.buildsTwoSidesThatCompleteAHandshakeWithTheTestCertificate`, because it completes a handshake between the two contexts and checks the certificate's name; `BackendServerTest.answersOverTlsVersionOnePointThreeWithTheTestCertificate` does the same through the server.

## Further reading

- JUnit 6 User Guide (6.1.3 is pinned): https://docs.junit.org/current/user-guide/
- AssertJ Core documentation (3.27.7): https://assertj.github.io/doc/
- RFC 5737, IPv4 Address Blocks Reserved for Documentation: https://www.rfc-editor.org/rfc/rfc5737
- RFC 7239, Forwarded HTTP Extension: https://www.rfc-editor.org/rfc/rfc7239
- Java SE 25 API, `javax.net.ssl.SSLContext`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLContext.html
