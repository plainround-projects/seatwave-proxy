# The `config`, `json` and `tls` packages, line by line

## What it is

Three small packages that the backend stands on:

- `config` (`SettingsReader.java`, `SettingsFiles.java`): reads a settings file and reports **every** mistake in it, with no defaults.
- `json` (`JsonCodec.java`): turns JSON text into a Java record and a record into JSON text, strictly. A body either matches the record exactly or is refused with a reason that names the field.
- `tls` (`TlsFiles.java`): builds the TLS settings from files: the server's key store for SeatWave, and the test certificate authority for clients.

Terms you need.

- A **settings file** is a text file of `key=value` lines (a `.properties` file; see `java-properties.md`). The program reads it when it starts.
- **JSON** is a plain-text way to write data with named fields (`{"seat":"A-7"}`). To **bind** JSON to a record means to fill a record's fields from it. **Jackson** is the library that does it (see `jackson.md`).
- A **JSON pointer** (RFC 6901) names a place inside a JSON document: `/eventId` is the field `eventId` at the top. The empty pointer is the whole document.
- **TLS** is the encryption layer under HTTPS. A **certificate** is a signed statement "this public key belongs to this name". A **certificate authority** (CA) is the party whose signature makes a certificate believed. A **key store** is a file that holds private keys and certificates, protected by a password. **PKCS12** is the file format. **PEM** is a text format for a certificate (`-----BEGIN CERTIFICATE-----`). A **TEST** certificate is one made on the learner's own machine by `./gradlew testCertificates`; no real client should ever trust it.

## Why this repository uses it

- **`config`**: the module's rule is that no number lives in code. Every timeout, port and pool size comes from a file, and a missing key is an error, not a silent default. Reporting all problems in one run saves a learner from fixing one error per attempt.
- **`json`**: the `POST /v1/holds` body is input from outside. Module 7's contract says unknown fields, wrong types and numbers out of range are errors. A strict codec makes the contract real, and stops "mass assignment" (a client setting a field it should not).
- **`tls`**: SeatWave answers HTTPS itself on one port (the "SIMPLE" case), and the tests talk to it with a client that trusts only the TEST CA.

## Where it appears

| File | What it does |
|---|---|
| `config/SettingsReader.java` | Typed reads from a loaded `Properties`; collects problems. |
| `config/SettingsFiles.java` | Loads a file, applies a reader function, prints problems. |
| `json/JsonCodec.java` | Strict read into a record, and write to text. |
| `tls/TlsFiles.java` | `serverContext` (key store to context) and `clientContext` (CA file to context). |

Used by `backend/BackendSettings.java` (reads with `SettingsReader`), `backend/BackendCli.java` (loads with `SettingsFiles`, builds the TLS context), `backend/SeatWaveRoutes.java` and `backend/Problems.java` (use `JsonCodec`), and the tests (`TestBackend` builds both TLS contexts).

## How it works underneath

**Settings.** The file is read into a `java.util.Properties` object: a table from key text to value text. `SettingsReader` wraps it and offers typed reads (`intBetween`, `millisBetween`, `textMatching`, `listMatching`). If a key is missing or wrong, the method writes a line into an internal list of problems and returns a harmless stand-in value, so the next read can run. After all reads, the caller looks at the list: empty means every value is trustworthy.

**JSON.** Jackson normally reads leniently: it ignores unknown fields, turns the text `"1"` into the number 1, accepts `1.5` for an integer by cutting it, and lets a repeated field overwrite the first. `JsonCodec` turns each of those off, one by one, so that the body must match the record exactly.

**TLS.** To serve HTTPS, a server needs a private key and a certificate chain (loaded from the key store), packaged as an `SSLContext`. To check a server, a client needs the list of authorities it trusts, also an `SSLContext`. The default list is the operating system's public authorities; the tests replace it with just the TEST CA so that nothing else is trusted.

## Walkthrough of this repository's use

### `config/SettingsReader.java`

```java
package io.plainround.seatwave.proxy.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
```

```java
/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a learner sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
```

The Javadoc states the contract. There are no defaults. When a key is invalid the reader records the problem and returns a stand-in; "the caller must check `problems()` before using any value". It is not thread-safe: one thread builds one settings record.

```java
public final class SettingsReader {

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();

  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }
```

The reader keeps the `Properties` and an `ArrayList` of problem lines. The constructor refuses `null`.

```java
  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file does not exist or cannot be read; the message says which
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (NoSuchFileException missing) {
      throw new IOException("the settings file " + path + " does not exist", missing);
    }
    return properties;
  }
```

`load` is a static method that reads a file. `Files.newBufferedReader(path, StandardCharsets.UTF_8)` opens it as UTF-8 text; the `try (Reader reader = ...)` form closes the reader on every path. `properties.load(reader)` parses the lines. If the file does not exist, `NoSuchFileException` (a kind of `IOException`) is caught and replaced with an `IOException` whose message names the file, `the settings file ... does not exist`, keeping the original as the cause. `Properties.load` also throws `IllegalArgumentException` for a malformed `\u` escape; that case is not caught anywhere (see `SettingsFiles` below).

```java
  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }
```

`intBetween(key, minimum, maximum)` reads a whole number. `text(key)` fetches the text (and records "is missing" if the key is absent or blank); if so, the stand-in is the minimum. `Integer.parseInt` converts the text. A value outside the range records `key must be from 1 to 10, but was 99` (formatted with `String.format`, using `Locale.ROOT` so digits are the same everywhere). Text that is not a number throws `NumberFormatException`, which the code catches and turns into `key must be a whole number, but was seven`. The comment gives the reason: a typo is an expected failure of a settings file, so it becomes a problem line, not a crash.

```java

  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code Millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }
```

`millisBetween` reads the number with `intBetween` and wraps it in a `Duration`. By convention such keys end in `Millis`.

```java

  /**
   * Reads a text value that must match a pattern.
   *
   * @param key the key
   * @param allowed the pattern the whole value must match
   * @return the trimmed value, or an empty string if the key is missing or does not match
   */
  public String textMatching(String key, Pattern allowed) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return text;
    }
    if (!allowed.matcher(text).matches()) {
      problems.add(key + " must match " + allowed.pattern() + ", but was " + text);
      return "";
    }
    return text;
  }
```

`textMatching` reads a text that must match a regular expression (a pattern for text). `allowed.matcher(text).matches()` is true only if the whole text fits. A mismatch records `key must match <pattern>, but was <text>` and returns an empty string.

```java

  /**
   * Reads a comma-separated list of text items, each matching a pattern.
   *
   * @param key the key
   * @param allowed the pattern every item must match
   * @return the trimmed items in file order, or an empty list if the key is missing or invalid
   */
  public List<String> listMatching(String key, Pattern allowed) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : text.split(",", -1)) {
      String trimmed = item.strip();
      if (!allowed.matcher(trimmed).matches()) {
        problems.add(
            key + " has the item '" + trimmed + "', which must match " + allowed.pattern());
        return List.of();
      }
      items.add(trimmed);
    }
    return List.copyOf(items);
  }
```

`listMatching` reads a comma-separated list. An empty value gives an empty list (and the problem "is missing" from `text`). Each item is trimmed with `strip()` and tested against the pattern; the first bad item records `key has the item 'X', which must match <pattern>` and returns an empty list. The good case returns an immutable copy in file order.

```java

  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }
```

`text` is the one place a missing key is detected. `properties.getProperty(key)` returns `null` for an absent key; a value that is only spaces counts as missing too. The value is trimmed.

```java

  /**
   * Records a problem that involves more than one key, so it is reported with all the others.
   *
   * @param problem the sentence that names the keys and what is wrong
   */
  public void addProblem(String problem) {
    problems.add(Objects.requireNonNull(problem, "problem"));
  }

  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
}
```

`addProblem` lets a caller record a problem that involves more than one key (for example "these three ports must differ"). `problems()` returns an immutable copy so the caller cannot change the list.

### `config/SettingsFiles.java`

```java
package io.plainround.seatwave.proxy.config;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
```

```java
/**
 * Loads one settings file into a checked settings record for a command-line entry point, and prints
 * every problem when it cannot. Thread-safe: it holds no state.
 */
public final class SettingsFiles {

  private SettingsFiles() {}
```

A final class with a private constructor, holding one static method.

```java
  /**
   * Loads and checks a settings file.
   *
   * @param file the file
   * @param reading turns a reader into the settings, or into its problems
   * @param err where problems are printed
   * @param <T> the settings type
   * @return the settings, or empty after printing why the file cannot be used
   */
  public static <T> Optional<T> load(
      Path file, Function<SettingsReader, Result<T, List<String>>> reading, PrintStream err) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(reading, "reading");
    Objects.requireNonNull(err, "err");
```

`load` is **generic**: `<T>` stands for "whatever settings type the caller wants". The parameter `Function<SettingsReader, Result<T, List<String>>> reading` is a function that takes a reader and returns either the settings or the list of problems; callers pass `BackendSettings::from`. The Javadoc lines `@param <T>` document the type parameter.

```java
    try {
      return switch (reading.apply(new SettingsReader(SettingsReader.load(file)))) {
        case Result.Success<T, List<String>>(T checked) -> Optional.of(checked);
        case Result.Error<T, List<String>>(List<String> problems) -> {
          err.println("the settings in " + file + " have " + problems.size() + " problem(s):");
          problems.forEach(problem -> err.println("  " + problem));
          yield Optional.empty();
        }
      };
```

`reading.apply(new SettingsReader(SettingsReader.load(file)))` loads the file and runs the function. The `switch` has two cases for the `Result`, using record patterns. On success it returns the settings in an `Optional`. On error it prints one header line (`the settings in <file> have N problem(s):`) and one indented line per problem, then `yield Optional.empty()`. **`yield`** is how a block inside a switch expression hands back its value (the way `return` does for a method).

```java
    } catch (IOException unreadable) {
      err.println(unreadable.getMessage());
      return Optional.empty();
    }
  }
}
```

An `IOException` (a missing or unreadable file) is caught: the message is printed and the result is empty. Only `IOException` is caught. A malformed `\u` escape in the file makes `Properties.load` throw `IllegalArgumentException`, which passes through here uncaught. A run of `SettingsFiles.load` on a file with the line `http.bindAddress=\u12` threw `java.lang.IllegalArgumentException: Malformed \uxxxx encoding.` instead of printing a problem line, and `BackendCli`'s promise of exit status 2 for an invalid settings file would not hold for that file.

### `json/JsonCodec.java`

```java
package io.plainround.seatwave.proxy.json;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.stream.Collectors;
```

The imports are Jackson's: `ObjectMapper` is the object that reads and writes JSON; `JsonParser` is the low-level token reader; `JsonToken` is one piece (`START_OBJECT` is the opening brace); the `DeserializationFeature` and `MapperFeature` enums are switches; the `...Exception` classes are the failures it can report.

```java
/**
 * JSON in and out, bound only to known records. Unknown fields, nulls for numbers, a value of the
 * wrong type, a number out of range, trailing data and duplicate fields are refused, so a body
 * either matches a record exactly or is rejected with a reason that names the field.
 *
 * <p>Thread-safe: the mapper is configured once in the constructor and only read afterwards, which
 * is the use Jackson documents as safe across threads.
 */
public final class JsonCodec {

  // RFC 6901: the empty pointer is the whole document.
  private static final String WHOLE_BODY = "";
```

The Javadoc lists the refusals: unknown fields, `null` for numbers, a value of the wrong type, a number out of range, trailing data and duplicate fields. It also says the codec is thread-safe: the mapper is configured once in the constructor and only read afterwards.

```java
  private final ObjectMapper mapper =
      JsonMapper.builder()
          // A field the contract does not list is an error (additionalProperties: false), never
          // ignored: ignoring it would let a typo pass silently, and is how mass assignment starts.
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          // "1" and 1.5 are not the integer the contract asks for: no silent conversion.
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          // The switch above covers numbers and booleans only; 5 or true is not a string either.
          .withCoercionConfig(
              LogicalType.Textual,
              textual ->
                  textual
                      .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
```

The mapper is built once, as a field, by a **builder** (`JsonMapper.builder()...build()`, a chain of calls that set options and then make the object).

- `FAIL_ON_UNKNOWN_PROPERTIES` enabled: a field the record does not list is an error ("additionalProperties: false" in Module 7's contract), because ignoring it would let a typo pass silently.
- `FAIL_ON_NULL_FOR_PRIMITIVES` enabled: `null` for a `long` or `int` is an error.
- `ALLOW_COERCION_OF_SCALARS` disabled: the text `"1"` is not accepted where a number is wanted.
- `ACCEPT_FLOAT_AS_INT` disabled: `1.5` is not cut down to 1.
- `withCoercionConfig(LogicalType.Textual, ...)`: the two switches above cover numbers and booleans being read into numbers; this one covers the other direction, a number or a boolean being read into a text field. Integer, float and boolean input shapes are set to `Fail`, so `5` or `true` is not a string either.
- `STRICT_DUPLICATE_DETECTION` enabled: a field that appears twice is an error instead of the last one winning.

```java
  /**
   * Reads a body into a record.
   *
   * @param json the body
   * @param type the record it must match
   * @param <T> the record type
   * @return the record, or a problem that names the JSON pointer of the field at fault
   */
  public <T> Result<T, BindingProblem> read(String json, Class<T> type) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(type, "type");
```

`read` is generic: `<T>` is the record type, and the caller passes the class (`HoldRequestBody.class`). It returns a `Result<T, BindingProblem>`: the record, or a problem naming where the body is wrong.

```java
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
      if (parser.nextToken() != null) {
        return Result.error(
            new BindingProblem(WHOLE_BODY, "the body has data after the JSON object"));
      }
      return Result.success(value);
```

The parser is driven by hand so that three cases get their own message. `parser.nextToken()` reads the first token. `null` means the text was empty: the message is "the body is empty; send a JSON object". If the first token is not `{` the message is "the body must be a JSON object". `mapper.readValue(parser, type)` then binds the object to the record. After it, `parser.nextToken()` must be `null` (nothing left); if there is more, the message is "the body has data after the JSON object". The `try (JsonParser parser = ...)` closes the parser. `WHOLE_BODY` is the empty JSON pointer.

```java
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
    } catch (JacksonException malformed) {
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (IOException notReadable) {
      // Every parse failure is a JacksonException; reading from a string has no other I/O to fail.
      throw new UncheckedIOException("reading a string failed", notReadable);
    }
```

The `catch` blocks run from the most specific failure to the most general; Java requires a subclass before its parent.

- `UnrecognizedPropertyException`: a field not in the record. The pointer is `/` plus the escaped field name, and the detail is "is not a field of this request".
- `MismatchedInputException`: a value of the wrong type. The pointer comes from `pointerOf`, the detail is "has the wrong type".
- `JsonMappingException`: other binding failures. A number that is valid JSON but too big for the field (`99999999999999999999` for a `long`) arrives here with an `InputCoercionException` as its cause, and the detail is "is out of range for this field". Anything else is "the body is not valid JSON".
- `JacksonException`: any other parse failure, such as `{"eventId":`: "the body is not valid JSON".
- `IOException`: cannot happen when reading from a string, so it becomes an unchecked exception.

A body with the same field twice is reported as "the body is not valid JSON", as a local run confirmed. The text is not accurate: the JSON is well formed, and the refused thing is the repeated field. The Javadoc promises a reason "that names the field"; for a duplicate it does not.

```java

  /**
   * Writes a value as JSON.
   *
   * @param value a record, a list or a map of them
   * @return the JSON text
   * @throws IllegalArgumentException if the value cannot be written, which is a programming error
   */
  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException notWritable) {
      throw new IllegalArgumentException("not writable as JSON", notWritable);
    }
  }
```

`write` turns a record, a list or a map into JSON text with `writeValueAsString`. A failure to write is a programming error, so it becomes `IllegalArgumentException`. Records are written with their fields in declaration order.

```java

  private static String pointerOf(JsonMappingException failure) {
    // RFC 6901 pointer: "/field" for each step of the path Jackson was reading.
    return failure.getPath().stream()
        .map(step ->
            step.getFieldName() == null
                ? String.valueOf(step.getIndex())
                : escaped(step.getFieldName()))
        .collect(Collectors.joining("/", "/", ""));
  }

  private static String escaped(String fieldName) {
    // RFC 6901: "~" and "/" inside a name are written "~0" and "~1", in this order.
    return fieldName.replace("~", "~0").replace("/", "~1");
  }
```

`pointerOf` builds the pointer from the path Jackson was reading: a field name for an object step, an index for a list step, joined with `/` and starting with `/`. `escaped` applies RFC 6901's rule: `~` becomes `~0` and `/` becomes `~1`, in this order.

```java
  /**
   * Why a body did not bind.
   *
   * @param pointer the JSON pointer of the field at fault, or an empty string for the whole body
   * @param detail what is wrong, in words a client developer can act on
   */
  public record BindingProblem(String pointer, String detail) {

    /** Checks the fields. */
    public BindingProblem {
      Objects.requireNonNull(pointer, "pointer");
      Objects.requireNonNull(detail, "detail");
    }
  }
}
```

`BindingProblem` is the pointer and the detail. `SeatWaveRoutes.holdOnce` joins them with a space, so for a whole-body problem (empty pointer) the final text starts with a space.

### `tls/TlsFiles.java`

```java
package io.plainround.seatwave.proxy.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
```

```java
/**
 * Builds TLS contexts from the TEST files that {@code ./gradlew testCertificates} writes into
 * {@code build/tls}: the server's keystore for SeatWave, and the TEST certificate authority for the
 * clients, which trust nothing else. Thread-safe: it holds no state.
 */
public final class TlsFiles {

  private TlsFiles() {}
```

`TlsFiles` builds contexts from the files `./gradlew testCertificates` writes into `build/tls`. It is a final class with a private constructor.

```java
  /**
   * Builds the server side from a PKCS12 keystore.
   *
   * @param keystore the keystore with the server key and its chain
   * @param passwordFile the file holding the keystore's password
   * @return a context that presents the server's certificate
   * @throws IOException if a file is missing or unreadable, or the keystore cannot be used
   */
  public static SSLContext serverContext(Path keystore, Path passwordFile) throws IOException {
    Objects.requireNonNull(keystore, "keystore");
    char[] password = password(passwordFile);
    try (InputStream in = open(keystore)) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(in, password);
      KeyManagerFactory keys =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(store, password);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys.getKeyManagers(), null, null);
      return context;
    } catch (GeneralSecurityException unusable) {
      throw new IOException("the keystore " + keystore + " cannot be used", unusable);
    }
  }
```

`serverContext(keystore, passwordFile)`:

1. `password(passwordFile)` reads the password (below) as a `char[]`.
2. `open(keystore)` opens the key store file.
3. `KeyStore.getInstance("PKCS12")` makes an empty key store object of that format, and `store.load(in, password)` fills it from the file. A wrong password fails here.
4. `KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())` makes the helper that finds keys, and `keys.init(store, password)` points it at the store. A **key manager** is the part of TLS that picks the private key and the certificate chain to present.
5. `SSLContext.getInstance("TLS")` makes a context, and `context.init(keyManagers, null, null)` sets it up: the two `null`s mean "use the default trust list and the default random-number source".

Any `GeneralSecurityException` (a bad key store, a wrong password) is wrapped in an `IOException` that names the key store, so callers handle one kind of failure.

```java
  /**
   * Builds the client side: trust only the TEST certificate authority.
   *
   * @param authorityPem the TEST CA's certificate as PEM
   * @return a context whose clients refuse any server the TEST CA did not sign
   * @throws IOException if the file is missing or not a certificate
   */
  public static SSLContext clientContext(Path authorityPem) throws IOException {
    Objects.requireNonNull(authorityPem, "authorityPem");
    try (InputStream in = open(authorityPem)) {
      Collection<? extends Certificate> authorities =
          CertificateFactory.getInstance("X.509").generateCertificates(in);
      if (authorities.isEmpty()) {
        throw new IOException("the file " + authorityPem + " holds no certificate");
      }
      KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
      trusted.load(null, null);
      int index = 0;
      for (Certificate authority : authorities) {
        trusted.setCertificateEntry("seatwave-test-ca-" + index++, authority);
      }
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(trusted);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException unusable) {
      throw new IOException("the file " + authorityPem + " is not a usable certificate", unusable);
    }
  }
```

`clientContext(authorityPem)` builds the other side. `CertificateFactory.getInstance("X.509").generateCertificates(in)` reads every certificate in the PEM file (X.509 is the certificate standard). If there are none, the error is "the file ... holds no certificate". `KeyStore.getInstance(KeyStore.getDefaultType())` with `trusted.load(null, null)` makes an empty in-memory store. Each authority is added with `setCertificateEntry`, under the alias `seatwave-test-ca-0`, and `seatwave-test-ca-1` for a second certificate (`index++` uses the number and then adds one). `TrustManagerFactory` turns the store into **trust managers**, the part of TLS that decides whether a server's certificate is believed; the context is built with only those, so the client trusts nothing but the TEST CA.

```java
  private static char[] password(Path passwordFile) throws IOException {
    Objects.requireNonNull(passwordFile, "passwordFile");
    try {
      return Files.readString(passwordFile, StandardCharsets.UTF_8).strip().toCharArray();
    } catch (NoSuchFileException missing) {
      throw new IOException(
          "the password file " + passwordFile + " does not exist; run ./gradlew testCertificates",
          missing);
    }
  }
```

`password` reads the whole file as UTF-8 text, removes the line break at the end with `strip()`, and returns the characters as a `char[]` (the form the key store APIs take). A missing file becomes an `IOException` that names the file and tells the reader what to run: `the password file ... does not exist; run ./gradlew testCertificates`.

```java
  private static InputStream open(Path file) throws IOException {
    try {
      return Files.newInputStream(file);
    } catch (NoSuchFileException missing) {
      throw new IOException(
          "the file " + file + " does not exist; run ./gradlew testCertificates", missing);
    }
  }
}
```

`open` does the same for any other file, with the message `the file ... does not exist; run ./gradlew testCertificates`.

## Try it yourself

All outputs below are from real runs in a copy of the repository. Timings differ on every run.

**1. Make the TEST certificates.**

```
./gradlew testCertificates
ls build/tls
```

The listing contained `seatwave-server.p12`, `seatwave-test-ca.p12`, `seatwave-test-ca.pem`, `server-chain.pem`, `server-key.pem` and `store.password`. (The key files and the password are secrets even though they are for a test; do not print them.)

**2. See every settings problem at once.**

```
printf 'http.plainPort=x\n' > bad.properties
./gradlew -q serve --args='bad.properties'
```

It printed (the first lines; the rest of the missing keys follow the same pattern):

```
the settings in bad.properties have 14 problem(s):
  http.bindAddress is missing
  http.plainPort must be a whole number, but was x
  http.tlsPort is missing
  http.adminPort is missing
  tls.keystore is missing
  ...
  feed.streamLifetimeMillis is missing
  feed.streamLifetimeMillis must be longer than feed.heartbeatMillis, or a stream ends before its first heartbeat
```

and Gradle reported `finished with non-zero exit value 2`. The last line is a side effect: with both feed keys missing, the reader returned the stand-in 1 ms for each, and the rule "lifetime must be longer than heartbeat" fired on those stand-ins. It is a true statement about the stand-ins and noise for the person reading. Delete the file afterwards with `rm bad.properties`.

**3. Ask the server for a body the codec refuses.** Start the server as in `java-backend-walkthrough-1.md` and send these bodies to `POST /v1/holds` with a fresh `Idempotency-Key` each time (the `detail` text printed was):

| Body | `detail` |
|---|---|
| (empty) | ` the body is empty; send a JSON object` |
| `[1]` | ` the body must be a JSON object` |
| `{"eventId":1,"seat":"A-1","customer":"x"} {}` | ` the body has data after the JSON object` |
| `{"eventId":"1","seat":"A-7","customer":"x"}` | `/eventId has the wrong type` |
| `{"eventId":99999999999999999999,"seat":"A-1","customer":"x"}` | `/eventId is out of range for this field` |
| `{"eventId":1,"eventId":2,...}` | ` the body is not valid JSON` |
| `{"eventId":` | ` the body is not valid JSON` |

**4. Talk to the HTTPS port with the TEST CA.**

```
curl -s -o /dev/null -w '%{http_code}\n' --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20930/health
```

It printed `200`. Without `--cacert` curl refuses the certificate.

**5. Run the unit tests.**

```
./gradlew test --tests '*SettingsReaderTest' --tests '*TlsFilesTest' --tests '*BackendSettingsTest'
```

They pass. Note that the `json` package has no test file of its own; only the unknown-field message is checked, through `BackendServerTest`.

## Mistakes people make

- **Defaults hidden in code.** A forgotten key then runs with a value nobody chose. Here a missing key is reported.
- **Stopping at the first problem.** One error per run is slow. The reader collects them all.
- **Lenient JSON.** Silent conversions (`"1"` to 1, `1.5` to 1, a repeated field, an ignored unknown field) hide client bugs and open mass assignment. Each is switched off, with a comment that says why.
- **Catching too little.** `SettingsFiles` catches `IOException` only; a malformed `\u` escape escapes it.
- **A password in a command line or a log.** The password is read from a file, kept as a `char[]`, and never printed.
- **Trusting the system's authorities in a test.** The test client trusts only the TEST CA, so a test cannot pass by accident against a real certificate.
- **Long paths.** `BackendSettings` limits file paths to 200 characters (see `java-backend-walkthrough-1.md`), so a deep clone folder breaks the test that builds a settings file from the build folder's absolute path.

## Check yourself

1. What does `SettingsReader.intBetween` return for a missing key, and how does the caller learn something is wrong? Answer: the minimum, and `problems()` holds a line `key is missing`; the caller must check `problems()` before using any value.
2. Why is `FAIL_ON_UNKNOWN_PROPERTIES` on? Answer: so a typo or a field the contract does not list is an error rather than ignored, which also stops mass assignment.
3. What is the JSON pointer of `eventId` at the top of a body, and what is the pointer of the whole body? Answer: `/eventId`; the empty string.
4. What do the two `null` arguments of `context.init(keys.getKeyManagers(), null, null)` mean? Answer: use the default trust managers and the default source of random numbers.
5. Which file does `clientContext` read and what does the client then trust? Answer: a PEM file of the TEST CA; the client trusts only the certificates in that file.

## Further reading

- Java SE 25 API, `java.util.Properties`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- Jackson 2.x databind, project documentation: https://github.com/FasterXML/jackson-databind
- RFC 6901, JSON Pointer: https://www.rfc-editor.org/rfc/rfc6901
- RFC 8259, The JSON Data Interchange Format: https://www.rfc-editor.org/rfc/rfc8259
- Java SE 25 API, `javax.net.ssl.SSLContext`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLContext.html
- Java Security Standard Algorithm Names, Java SE 25 (PKCS12, X.509, TLS): https://docs.oracle.com/en/java/javase/25/docs/specs/security/standard-names.html
- RFC 8446, TLS 1.3: https://www.rfc-editor.org/rfc/rfc8446
