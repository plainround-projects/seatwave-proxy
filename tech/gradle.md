# Gradle 9.9.1 (build tool) and the Kotlin DSL

## What it is

Source code is text. A computer cannot run text until other programs have turned it into something runnable. A **build tool** is the program that does all those steps in the right order, from one command: it fetches the libraries the code needs, compiles the Java source files into **class files** (the form the Java Virtual Machine, the JVM, can run), runs the tests, checks the code style and measures the test coverage. **Gradle** is a build tool for Java. You tell it what you want (for example `check`) and it works out which smaller steps are needed.

A Gradle build is described in files written in a language. This repository uses the **Kotlin DSL**: the build file is a Kotlin script. "DSL" means domain-specific language, a small language made for one job. A file that ends in `.kts` is a Kotlin script, a Kotlin program that is run directly instead of being compiled first.

A **terminal** is a window where you type commands. The word after the program name, such as `check` in `./gradlew check`, is the name of a **task**. A task is one unit of work Gradle knows how to do.

## Why this repository uses it

The hands-on project of Module 9 must build and test with one command and the same result on every machine, and it must not need Docker for that. Gradle gives that: `./gradlew check` makes the test certificates, checks the formatting of the code, compiles it with every warning turned into an error, runs 119 tests and fails if the line coverage is below 80 percent. The build file also gives each of the six programs (the backend, the bench, the outage probe, the slow clients, the access-log reader and the report) its own task, so a learner types one short command instead of assembling a long Java command line. It keeps the tests that need the running Compose stack out of `check` (they have their own task, `stackTest`), so the gate takes seconds and needs no Docker. And two tasks prepare the Docker side without Docker: `testCertificates` creates throwaway TLS certificates with the JDK's `keytool`, so no key is ever committed, and `stackPrepare` collects the jars the container image copies.

## Where it appears

| Path | What it does with Gradle |
| --- | --- |
| `settings.gradle.kts` | Names the project and says where libraries may be downloaded from. |
| `build.gradle.kts` | The build itself: plugins, Java version, libraries, compiler flags, formatter, coverage, the certificate task, the test tasks, the stack tasks and the six program tasks. |
| `gradle/libs.versions.toml` | The version catalog: one place for every version (`toml-version-catalog.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | Pinned versions of every library, including the ones libraries bring along (`dependency-locking.md`). |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | The wrapper that downloads and starts the right Gradle (`gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`). |
| `scripts/stack.sh`, `scripts/spof.sh` | Call `./gradlew -q stackPrepare`, `./gradlew stackTest` and `./gradlew -q probe` (`shell-scripts-walkthrough.md`). |
| `Dockerfile` | Its comment says to build the jars first with `./gradlew stackPrepare` (`docker.md`). |
| `build/` and `.gradle/` | Folders Gradle creates while it works. Git ignores them (`git.md`). |

## How it works underneath

1. You run `./gradlew check`. The wrapper starts a **Gradle process**, a Java program that stays alive in the background for a while (the **daemon**) so the next command starts quickly.
2. Gradle reads `settings.gradle.kts` first. That file decides which projects are part of the build. Here there is one project.
3. Gradle then reads `build.gradle.kts`. Running the file does not build anything. It only **configures**: it creates tasks and tells each one what it depends on.
4. Gradle looks at the task you asked for and at everything it depends on, and builds a **task graph**: a list of tasks in a legal order, each after the tasks it needs.
5. It runs the tasks. For each task it checks whether the inputs (source files, settings) and outputs (class files, reports) are the same as last time. If yes, the task is marked `UP-TO-DATE` and skipped. This is why a second `./gradlew testCertificates` took 343 ms on this machine: it said `Task :testCertificates UP-TO-DATE`.
6. Everything it makes goes into the `build/` folder. Downloaded libraries are kept in a cache folder in your home directory (`~/.gradle`), shared by all projects.

A **plugin** is a package of tasks and settings that you switch on in one line. The `java` plugin adds the tasks that compile and test Java. A **dependency** is a library your code needs. A **configuration** is a named list of dependencies for one purpose, such as "what the tests need at run time". A **repository** is a server that stores libraries; Maven Central is the big public one.

## Walkthrough of this repository's use

### `settings.gradle.kts`

```kotlin
// One place decides where dependencies come from; a project that adds its own repository fails the build.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "seatwave-proxy"
```

- Line 1 is a comment: `//` starts a comment in Kotlin. It says that one place decides where libraries come from.
- `dependencyResolutionManagement { ... }` is a block. A block is a name followed by braces; everything inside configures that one topic. This block configures how Gradle **resolves** dependencies, which means finding the exact file for each library.
- `repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` makes the build fail if `build.gradle.kts` ever adds a repository of its own. All repositories must be declared here. This stops a project from quietly pulling libraries from an unreviewed server.
- `repositories { mavenCentral() }` lists the only allowed repository: Maven Central.
- `rootProject.name = "seatwave-proxy"` names the project. The name appears in reports, for example "Rule violated for bundle seatwave-proxy".

### `build.gradle.kts`, the imports

```kotlin
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.inject.Inject
```

A Kotlin script may start with `import` lines. An import makes a class from another package usable by its short name. These ten are used by the certificate task (the next big section): `ByteArrayOutputStream` collects the text `keytool` prints, `Files` and `PosixFilePermissions` set the file permissions, `KeyStore` and `PrivateKey` read the key back out of the key store to write it as PEM text, `SecureRandom` makes the random password, `LocalDate` and `ZoneOffset` give today's date in UTC, `Base64` turns bytes into printable text, and `javax.inject.Inject` marks the one value that Gradle must supply to the task class.

### The plugins block

```kotlin
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}
```

- `plugins { ... }` lists the plugins to switch on.
- `java` adds the Java tasks: `compileJava`, `test`, `jar` and the `check` task.
- `jacoco` adds the coverage tool (`jacoco.md`).
- `alias(libs.plugins.spotless)` switches on the Spotless formatter plugin. `alias` means "use the plugin described in the version catalog". `libs.plugins.spotless` points at the `spotless` line in the `[plugins]` section of `gradle/libs.versions.toml`, which holds the plugin id and its version (`toml-version-catalog.md`).

### Project coordinates

```kotlin
group = "io.plainround"
version = "1.0.0"
```

`group` and `version` are the project's own identity in the Maven style: a group, a name (from `settings.gradle.kts`) and a version. They matter in one visible place: `version = "1.0.0"` is part of the name of the jar, `seatwave-proxy-1.0.0.jar`, that `stackFiles` collects.

### The Java toolchain

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

A **toolchain** is the exact JDK (Java Development Kit) that builds the code. `languageVersion = JavaLanguageVersion.of(25)` says "use Java 25". Gradle looks for an installed JDK 25 on the machine and uses it for compiling and for running tests, even if the JDK that started Gradle is different. This build does not configure an automatic download of a JDK, so a JDK 25 must be installed (the README names Eclipse Temurin 25.0.2). The same toolchain also finds the `keytool` program that makes the test certificates (line 168 of the build file). The `java { toolchain { ... } }` nesting is a block inside a block: `java` is the extension that the `java` plugin added, and `toolchain` is one of its parts.

### Dependencies and configurations

```kotlin
dependencies {
    implementation(libs.result4j)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

- A name like `implementation`, `runtimeOnly`, `testImplementation` or `testRuntimeOnly` is a **configuration**. The `test` prefix means "only for the test code". `Implementation` means "needed to compile and run". `RuntimeOnly` means "needed only when running, not when compiling".
- `implementation(libs.result4j)` puts the result4j library on the **classpath** of the main code, both for compiling and for running. A classpath is the list of jar files and folders the JVM searches for classes. `libs.result4j` is a catalog entry: it expands to `com.github.sviperll:result4j:1.2.0` (`result4j.md`).
- `implementation(platform(libs.jackson.bom))` brings in a **BOM**, a bill of materials. A BOM is a small published file that lists the matching versions of many libraries of one family. `platform(...)` tells Gradle "use this list to decide versions". Jackson has several libraries (`jackson-databind`, `jackson-core`, `jackson-annotations`); the BOM says all of them are 2.23, so the catalog does not need a version for each.
- `implementation(libs.jackson.databind)` adds the JSON library (`jackson.md`). It has no version in the catalog; the BOM above supplies it.
- `implementation(libs.slf4j.api)` adds the logging front door that the code calls (`logging-slf4j-logback.md`).
- `runtimeOnly(libs.logback.classic)` adds the logging engine. It is `runtimeOnly` on purpose: no code names a Logback class, so the compiler never needs it, and SLF4J finds it when the program starts.
- `testImplementation(platform(libs.junit.bom))` loads the JUnit BOM for the tests, the same idea as the Jackson one.
- `testImplementation(libs.junit.jupiter)` adds the JUnit Jupiter bundle: the annotations and the engine that finds and runs the tests (`junit.md`). The BOM supplies 6.1.3.
- `testImplementation(libs.assertj.core)` adds AssertJ, the readable assertions (`assertj.md`).
- `testImplementation(libs.awaitility)` adds Awaitility, which waits until a condition becomes true (`awaitility.md`).
- `testRuntimeOnly(libs.junit.platform.launcher)` adds the JUnit Platform launcher, the piece Gradle calls to start the tests. Gradle 9 expects the project to declare it, and it is needed only when tests run, so it is `testRuntimeOnly`. Its version also comes from the BOM.

The servers, the HTTP client, TLS and the load generators are all the JDK's own classes (`java.net.http`, `com.sun.net.httpserver`, `javax.net.ssl`), so the main code needs only these four libraries: result4j, Jackson, SLF4J and Logback.

### Dependency locking

```kotlin
// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}
```

Libraries bring other libraries (their **transitive dependencies**). `lockAllConfigurations()` makes Gradle write the exact version of every library of every configuration into `gradle.lockfile`, and read that file on every later build. If the resolved versions would differ from the lock, the build fails. The whole story is in `dependency-locking.md`.

### The Java compiler flags

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
```

- `tasks.withType<JavaCompile>().configureEach { ... }` means: for every task of the type `JavaCompile` (there is one for main code and one for test code), run this block. `<JavaCompile>` is a Kotlin type argument, the type to select. `configureEach` applies the block to each one lazily, only when needed.
- `options.encoding = "UTF-8"` makes the compiler read source files as UTF-8 text, so the result does not depend on the machine's default.
- `options.release = 25` makes the compiler accept only Java 25 language features and use only Java 25 library classes.
- The comment on line 49 gives the reason for the next line: "Every warning is an error: a warning that is allowed to stay is a defect nobody owns."
- `options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))` passes two flags to `javac`, the Java compiler. `-Xlint:all` turns on every optional warning (raw types, unchecked casts, an auto-closeable resource that is never used, missing serial ids and more). `-Werror` turns each warning into an error, so the build stops. The flag also applies to the test code.
- I ran an experiment in a scratch copy: a new class with the raw types `java.util.List` and `java.util.ArrayList` made `compileJava` fail. The output had `warning: [rawtypes] found raw type: List`, then `error: warnings found and -Werror specified`, `1 error` and `2 warnings`.

### Spotless and the formatter

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}
```

Spotless is a Gradle plugin that checks and rewrites source files to one layout (`spotless-google-java-format.md`).
- `spotless { java { ... } }` configures Spotless for Java files.
- `googleJavaFormat(libs.versions.googleJavaFormat.get())` chooses the formatter. `libs.versions.googleJavaFormat` is the version entry `googleJavaFormat = "2.0.0"` from the catalog, and `.get()` turns it into the text `2.0.0`.
- `removeUnusedImports()` deletes `import` lines the file never uses.
- `endWithNewline()` makes every file end with one line break.
- Spotless adds the tasks `spotlessCheck` (fails if a file would change) and `spotlessApply` (changes the files). The `java` plugin's `check` task depends on `spotlessCheck`, which is how formatting becomes part of the gate.

### JaCoCo, the coverage tool

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`jacoco { toolVersion = ... }` fixes the JaCoCo version to the catalog entry `jacoco = "0.8.16"`. Without it, Gradle would pick a default version. `jacoco.md` explains what the tool measures.

### The certificate task

The next 108 lines of the build file are one Gradle task of its own: a Kotlin class `TestCertificates` (lines 65 to 163) and the lines that register and configure it (lines 165 to 172). They run `keytool`, the JDK's program for keys and certificates, to make a test certificate authority and one server certificate in `build/tls`. Because no other note owns this class, this section quotes every line and explains the certificate words first.

**The words.** When your program opens `https://something` it must answer two questions before it trusts the server: "Is this really the server I asked for?" and "Can anyone else read our messages?" **TLS** (Transport Layer Security) is the protocol that answers both. A **private key** is a secret number that only its owner knows. Whatever it signs, only it could have signed. It must never be shared, printed or committed to Git. A **public key** is the matching number that anyone may know. A **certificate** is a small file that says "this public key belongs to this name", with a start date, an end date and a signature. The name can be a host name (`localhost`) or an address (`127.0.0.1`), listed in a field called the **subject alternative name** (SAN). A **certificate authority** (CA) is a party that signs certificates. A client that trusts the CA trusts every certificate the CA signed, as long as the dates and the name fit. A **chain** is a server's certificate followed by the certificate of the CA that signed it. A **key store** is a file that holds private keys together with their certificate chains; here the format is **PKCS12**, and the files end in `.p12`. **PEM** is a text format: base64 text between a `BEGIN` line and an `END` line. **EC P-256** means elliptic-curve keys on the curve named `secp256r1`: short, fast and supported by every current TLS client.

**Why this repository needs them.** SeatWave answers HTTPS itself (port 20910 in the "before" case). The reverse proxy answers HTTPS for clients (the lab's whole point is measuring TLS termination), and it also talks HTTPS to the sidecar. NGINX cannot read a `.p12` file; it reads PEM files. So the build makes one CA and one server certificate, and writes the server's key and chain both as a key store (for Java) and as PEM (for NGINX). **Why generate on every machine and never commit.** A key that is committed is public to everyone who can read the history, forever. A certificate also expires: a committed 30-day certificate would break on day 31.

```kotlin
/**
 * Makes throwaway TEST certificates with the JDK's keytool: one test certificate authority (CA) and
 * one server certificate it signs, for SeatWave's own HTTPS listener, the reverse proxy and the
 * sidecar. NGINX reads PEM files, so the server's key and chain are also written as PEM.
 *
 * Everything lands in build/tls, which git ignores, so no key or keystore is ever committed. The
 * keystore password is random per run and written to a file next to the keystore, so no password
 * appears in code, configuration or a process listing.
 */
```

This is a documentation comment (it starts with `/**`). It says what the class does: it makes one test CA and one server certificate it signs, "for SeatWave's own HTTPS listener, the reverse proxy and the sidecar", and because NGINX reads PEM files the key and chain are also written as PEM. Everything goes to `build/tls`, which Git ignores. The key store password is random per run and written to a file next to the key store, "so no password appears in code, configuration or a process listing". A **process listing** (the `ps` command) shows the arguments of running programs. If the password were passed on the command line, any user of the machine could read it there. The `-storepass:file` option of `keytool` reads the password from a file instead.

```kotlin
abstract class TestCertificates : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val keytool: Property<String>

    // The date is an input, so the certificates are made again on a new day and never expire while
    // a reader is still using a build folder from last month.
    @get:Input
    abstract val day: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

```

- `abstract class TestCertificates : DefaultTask()` declares a new kind of Gradle task. `DefaultTask` is Gradle's base class for tasks. `abstract` is required for Gradle task classes: Gradle writes the missing parts of the class itself.
- `@get:Inject abstract val execOperations: ExecOperations` asks Gradle to hand the task an object that can start operating system programs. `@get:Inject` is the label that means "Gradle supplies this". `ExecOperations` is Gradle's supported way to start `keytool`.
- `@get:Input abstract val keytool: Property<String>` is the path to the `keytool` program. A `Property<String>` is a text value that Gradle can fill in later. `@get:Input` marks it as something that decides whether the task is up to date.
- The two comment lines and `@get:Input abstract val day: Property<String>` explain the date input: "The date is an input, so the certificates are made again on a new day and never expire while a reader is still using a build folder from last month." The task does not read `day`. A changed input is enough to make Gradle run the task again.
- `@get:OutputDirectory abstract val outputDirectory: DirectoryProperty` is the folder the task writes to. Gradle watches it: if someone deletes it, the task runs again.

```kotlin
    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val passwordFile = directory.resolve("store.password")
        val passwordBytes = ByteArray(18)
        SecureRandom().nextBytes(passwordBytes)
        val password = Base64.getEncoder().encodeToString(passwordBytes)
        passwordFile.writeText(password, Charsets.UTF_8)
        val caStore = directory.resolve("seatwave-test-ca.p12").absolutePath
        val passwordPath = passwordFile.absolutePath
```

- `@TaskAction fun generate()` marks the function Gradle calls when the task runs. A **function** is a named block of steps; `fun` is how Kotlin declares one.
- `val directory = outputDirectory.get().asFile` turns the output property into an ordinary folder object. `val` means a value that is never changed.
- `directory.deleteRecursively()` removes the folder with everything inside, and `directory.mkdirs()` makes it again, empty. So no certificate of an older run can stay behind.
- `val passwordFile = directory.resolve("store.password")` is the path of the password file inside the folder.
- `val passwordBytes = ByteArray(18)` makes 18 empty bytes, and `SecureRandom().nextBytes(passwordBytes)` fills them with random bytes from the operating system's secure random source. 18 bytes are 144 random bits.
- `val password = Base64.getEncoder().encodeToString(passwordBytes)` writes those bytes as 24 printable characters (base64 turns every 3 bytes into 4 letters, digits or signs). `passwordFile.writeText(password, Charsets.UTF_8)` saves it, which is why `store.password` is 24 bytes long. This time the password text is kept in a variable, because `writePem` needs it later to open the key store from inside Kotlin.
- `val caStore = ...absolutePath` is the path of the CA's key store, and `val passwordPath = passwordFile.absolutePath` is the **path** of the password file, not the password. Every `keytool` call below receives the path.

```kotlin
        // EC P-256 keys: the curve every current TLS client supports, and a fast handshake.
        val ecKey = listOf("-keyalg", "EC", "-groupname", "secp256r1")
```

The comment says: "EC P-256 keys: the curve every current TLS client supports, and a fast handshake." The list `ecKey` holds the two `keytool` options that are repeated: `-keyalg EC` (elliptic-curve keys) and `-groupname secp256r1` (the curve).

```kotlin
        keytool(
            listOf("-genkeypair", "-keystore", caStore, "-storetype", "PKCS12", "-storepass:file", passwordPath,
                "-alias", "ca", "-dname", "CN=SeatWave TEST CA - never trust outside this lab", "-validity", "30",
                "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign") + ecKey,
        )
```

`keytool(listOf(...) + ecKey)` calls the helper function below with a list of arguments. The `+ ecKey` adds the two key options at the end. The options mean:

| Option | Meaning |
| --- | --- |
| `-genkeypair` | Make a new private and public key and a certificate for it. |
| `-keystore caStore` | Put it in this key store file; the file is made if it does not exist. |
| `-storetype PKCS12` | The file format of the key store. |
| `-storepass:file passwordPath` | The store password is in the file at this path. |
| `-alias ca` | The name of the entry inside the store. |
| `-dname "CN=SeatWave TEST CA - never trust outside this lab"` | The **distinguished name**: who the certificate is for. `CN` is the common name. The text is a warning to anyone who sees it. |
| `-validity 30` | The certificate is valid for 30 days from now. |
| `-ext bc:c=ca:true` | An **extension**: basic constraints, marked critical, `CA:true`. It says this certificate may sign other certificates. |
| `-ext ku:c=keyCertSign` | An extension: key usage, critical, only `keyCertSign` (signing certificates). |

Because no `-signer` is given, the certificate is **self-signed**: its issuer is itself. That is how a root CA looks.

```kotlin
        keytool(
            listOf("-exportcert", "-rfc", "-keystore", caStore, "-storepass:file", passwordPath, "-alias", "ca",
                "-file", directory.resolve("seatwave-test-ca.pem").absolutePath),
        )
```

`-exportcert` writes a certificate out of a store. `-rfc` asks for the PEM text form instead of binary. `-alias ca` picks the entry. `-file ...seatwave-test-ca.pem` is the target. This copies the **certificate only**. A certificate holds no secret, which is why the `.pem` can be handed to `curl` and to the Java clients as the one authority they trust (`config/bench.properties` and `config/probe.properties` name it with `tls.authorityPem`).

```kotlin
        // One server certificate for every TLS endpoint of the lab: the names a client on this
        // machine uses (localhost, 127.0.0.1) and the container addresses the proxy uses to reach
        // the sidecar (10.209.9.20).
        keytool(
            listOf("-genkeypair", "-keystore", caStore, "-storepass:file", passwordPath, "-alias", "server",
                "-dname", "CN=SeatWave TEST server", "-signer", "ca", "-signerkeypass:file", passwordPath,
                "-validity", "30", "-ext", "san=dns:localhost,ip:127.0.0.1,ip:10.209.9.20,dns:backend") + ecKey,
        )
```

The comment says one certificate serves every TLS endpoint of the lab, and names the names a client needs. `-alias server` makes the key pair under that alias in the CA's own store. `-dname "CN=SeatWave TEST server"` names it. `-signer ca` signs the new certificate with the key that has the alias `ca`, and `-signerkeypass:file passwordPath` says the CA key's password is in the file. `-validity 30` is 30 days. The `san` extension lists the four names the certificate is good for:

| SAN entry | Why it is there |
| --- | --- |
| `dns:localhost` | A client on this machine that connects to the name `localhost`. |
| `ip:127.0.0.1` | A client that connects to the loopback address, as every config file here does. |
| `ip:10.209.9.20` | The container address of SeatWave on the lab network, which is also the sidecar's address, because the sidecar shares SeatWave's network namespace. The proxy connects to the sidecar there. |
| `dns:backend` | The Compose service name of SeatWave. |

```kotlin
        val serverStore = directory.resolve("seatwave-server.p12")
        keytool(
            listOf("-importkeystore", "-srckeystore", caStore, "-srcstorepass:file", passwordPath, "-srcalias", "server",
                "-destkeystore", serverStore.absolutePath, "-deststoretype", "PKCS12",
                "-deststorepass:file", passwordPath, "-destalias", "seatwave"),
        )
        writePem(serverStore, password, directory)
```

The second `keytool` call, `-importkeystore`, copies one entry from the source store (`-srckeystore caStore`, `-srcstorepass:file`, `-srcalias server`) into a new store (`-destkeystore seatwave-server.p12`, `-deststoretype PKCS12`, `-deststorepass:file`, `-destalias seatwave`). The server's store therefore holds one key under the alias `seatwave`, with its chain of two certificates (the server certificate, then the CA). The server never gets the CA's private key. Then `writePem(serverStore, password, directory)` writes the same key and chain as PEM.

```kotlin
        // Readable by the containers' own users (NGINX and SeatWave run under other user ids), and
        // never committed: .gitignore refuses every one of these file types.
        directory.listFiles().orEmpty().forEach { file ->
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        }
```

The comment gives the reason: the containers' own users (NGINX and SeatWave) run under other user ids, so the files must be readable by them, and "never committed: .gitignore refuses every one of these file types". `directory.listFiles().orEmpty().forEach { file -> ... }` walks over every file in the folder (`orEmpty()` turns a missing list into an empty one). `Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))` sets each file's **permissions**. A permission string has three groups of three letters for owner, group and others: `r` read, `w` write, `-` not allowed. `rw-r--r--` lets the owner read and write and everyone else only read. It applies to the password file and to the PEM key as well, which is acceptable only because these are throwaway test files that exist on one developer machine.

```kotlin
    private fun writePem(serverStore: java.io.File, password: String, directory: java.io.File) {
        val store = KeyStore.getInstance("PKCS12")
        serverStore.inputStream().use { input -> store.load(input, password.toCharArray()) }
        val key = store.getKey("seatwave", password.toCharArray()) as PrivateKey
        val chain = store.getCertificateChain("seatwave")
        directory.resolve("server-key.pem").writeText(pem("PRIVATE KEY", key.encoded), Charsets.US_ASCII)
        directory.resolve("server-chain.pem")
            .writeText(chain.joinToString("") { certificate -> pem("CERTIFICATE", certificate.encoded) }, Charsets.US_ASCII)
    }
```

- `private fun writePem(...)` is a function that only this class can call.
- `val store = KeyStore.getInstance("PKCS12")` makes an empty Java key store object, and `serverStore.inputStream().use { input -> store.load(input, password.toCharArray()) }` loads the file into it. `use` closes the stream when the block ends, even after an error. `password.toCharArray()` turns the text into the character array that the Java API wants.
- `val key = store.getKey("seatwave", password.toCharArray()) as PrivateKey` fetches the private key by its alias. `as PrivateKey` is a cast: the method returns a general key and the code knows it is a private one.
- `val chain = store.getCertificateChain("seatwave")` fetches the chain of two certificates.
- `directory.resolve("server-key.pem").writeText(pem("PRIVATE KEY", key.encoded), Charsets.US_ASCII)` writes the key. `key.encoded` is the key as bytes in the PKCS#8 format, which is what the label `PRIVATE KEY` means.
- `directory.resolve("server-chain.pem")` followed by `.writeText(chain.joinToString("") { certificate -> pem("CERTIFICATE", certificate.encoded) }, Charsets.US_ASCII)` writes both certificates, one after the other, as one file. `joinToString("")` glues the two PEM texts together with nothing between them. I counted `BEGIN CERTIFICATE` lines in the result: 2.

```kotlin
    private fun pem(label: String, der: ByteArray): String {
        // PEM is base64 in lines of 64 characters between two marker lines (RFC 7468).
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }
```

- The comment says: "PEM is base64 in lines of 64 characters between two marker lines (RFC 7468)."
- `Base64.getMimeEncoder(64, "\n".toByteArray())` makes a base64 encoder that breaks the text into lines of 64 characters, with a line feed between them. `.encodeToString(der)` encodes the bytes. **DER** is the binary form of a key or certificate.
- The returned text uses a Kotlin **string template**: `$label` puts the variable into the text. The result is the line `-----BEGIN PRIVATE KEY-----`, the base64 body, and the line `-----END PRIVATE KEY-----`, each ending in `\n`. A check of `server-chain.pem` showed 18 lines of exactly 64 characters.

```kotlin
    private fun keytool(arguments: List<String>) {
        // keytool's chatter is kept and shown only if it fails, so a run's output stays readable.
        val chatter = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(listOf(keytool.get()) + arguments)
            standardOutput = chatter
            errorOutput = chatter
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException("keytool ${arguments.first()} failed: " + chatter.toString(Charsets.UTF_8))
        }
    }
}
```

- `private fun keytool(arguments: List<String>)` is a function that only this class can call. It takes the list of arguments.
- The comment: "keytool's chatter is kept and shown only if it fails, so a run's output stays readable." `val chatter = ByteArrayOutputStream()` is an in-memory buffer.
- `execOperations.exec { ... }` starts the program as a separate operating system process (a running program). Inside: `commandLine(listOf(keytool.get()) + arguments)` is the program path followed by the arguments; `standardOutput = chatter` and `errorOutput = chatter` send both output streams into the buffer; `isIgnoreExitValue = true` stops Gradle from throwing by itself on a non-zero **exit status** (0 means success, anything else means failure), because the next lines do it with a better message.
- `if (result.exitValue != 0) { throw GradleException(...) }` fails the build if `keytool` returned anything but 0. The message is `keytool <the first argument> failed: ` followed by what `keytool` printed.

On this machine the folder looked like this after the task (names, permissions and sizes only; sizes vary by a few bytes from run to run because the keys are random):

```
-rw-r--r--@ 1736 seatwave-server.p12
-rw-r--r--@ 2527 seatwave-test-ca.p12
-rw-r--r--@ 650 seatwave-test-ca.pem
-rw-r--r--@ 1303 server-chain.pem
-rw-r--r--@ 148 server-key.pem
-rw-r--r--@ 24 store.password
```

I read the server key store with `keytool -list -v` (certificate details only). It has `Alias name: seatwave`, `Entry type: PrivateKeyEntry`, `Certificate chain length: 2`, `Owner: CN=SeatWave TEST server`, `Issuer: CN=SeatWave TEST CA - never trust outside this lab`, a validity of 30 days, `Signature algorithm name: SHA384withECDSA`, `256-bit EC (secp256r1) key`, and the four names `DNSName: localhost`, `IPAddress: 127.0.0.1`, `IPAddress: 10.209.9.20` and `DNSName: backend`. The second certificate of the chain says `CA:true`.

```kotlin
val testCertificates = tasks.register<TestCertificates>("testCertificates") {
    group = "verification"
    description = "Makes the throwaway TEST certificate authority and server certificate in build/tls."
    keytool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { launcher -> launcher.metadata.installationPath.file("bin/keytool").asFile.absolutePath }
    day = LocalDate.now(ZoneOffset.UTC).toString()
    outputDirectory = layout.buildDirectory.dir("tls")
}
```

- `tasks.register<TestCertificates>("testCertificates") { ... }` creates a task of the class `TestCertificates` and names it `testCertificates`. That name is what you type: `./gradlew testCertificates`.
- `group = "verification"` and `description = ...` file the task in the `Verification tasks` list that `./gradlew tasks --group verification` prints, with the text `testCertificates - Makes the throwaway TEST certificate authority and server certificate in build/tls.`
- `keytool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }.map { launcher -> launcher.metadata.installationPath.file("bin/keytool").asFile.absolutePath }` finds the Java 25 toolchain, and `.map { ... }` turns it into the full path of the `keytool` program inside that JDK. So the certificates are made by the same JDK that compiles the code. `.map` keeps the value lazy: Gradle finds the JDK only when the task needs it.
- `day = LocalDate.now(ZoneOffset.UTC).toString()` is today's date in UTC, such as `2026-11-11`. A new day is a new input, so the certificates are made again and a reader never uses certificates that are weeks old.
- `outputDirectory = layout.buildDirectory.dir("tls")` is the **output**: the folder `build/tls`. If it is missing or changed, the task runs again.

### The test task

```kotlin
// The tests tagged "stack" need the running Compose stack; ./gradlew check never runs them, so a
// reader without Docker can still build and test everything else.
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

- The two comment lines say why the tagged tests are separate: "The tests tagged "stack" need the running Compose stack; ./gradlew check never runs them, so a reader without Docker can still build and test everything else."
- `tasks.test { ... }` configures the existing task named `test`.
- `useJUnitPlatform { excludeTags("stack") }` tells it to run tests through the JUnit Platform, and to skip every test marked `@Tag("stack")`. Without `useJUnitPlatform` Gradle would look for older JUnit 4 tests and find none.
- `dependsOn(testCertificates)` makes `test` wait for the certificate task. This is how a test never starts before `build/tls` exists.
- `systemProperty("seatwave.tls.directory", layout.buildDirectory.dir("tls").get().asFile.absolutePath)` is how the tests find the certificates. A **system property** is a named text value that Gradle gives to the test JVM, like a variable that every class can read. The value is an absolute path to `build/tls` of the checkout, so it does not matter which folder the test JVM starts in. `systemProperty("seatwave.nginx.directory", file("nginx").absolutePath)` does the same for the folder of NGINX configuration files, which some tests read and check (`tests-stack-walkthrough.md`).
- `inputs.dir("nginx")` tells Gradle that the `nginx/` folder is an input of the task: if a file in it changes, `test` is not up to date and runs again.
- `testLogging { events("passed", "failed", "skipped") }` prints one line per test result while the task runs, for example `BackendCliTest > saysWhichTlsFileIsMissing(Path) PASSED`. Without it, only failures show.
- `finalizedBy(tasks.jacocoTestReport)` means: after `test` finishes, always run `jacocoTestReport`, even if tests failed. That creates the coverage report in `build/reports/jacoco/test/html`.

There is no `maxParallelForks` and no `forkEvery`, so Gradle runs all test classes in one test JVM, one test after another.

### The stack test task

```kotlin
val stackTest = tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the running stack; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    systemProperty("seatwave.tls.directory", layout.buildDirectory.dir("tls").get().asFile.absolutePath)
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}
```

- `tasks.register<Test>("stackTest") { ... }` creates a second task of the type `Test`, so it must be told what a `test` task is told by the java plugin: `testClassesDirs = sourceSets.test.get().output.classesDirs` (where the compiled test classes are) and `classpath = sourceSets.test.get().runtimeClasspath` (the libraries they need). A **source set** is a named group of source folders; `test` is `src/test/java`.
- `useJUnitPlatform { includeTags("stack") }` is the mirror image of `excludeTags("stack")`: only the tests marked `@Tag("stack")` run.
- `systemProperty("seatwave.tls.directory", ...)` gives these tests the certificates, so they can trust the proxy's test authority.
- The comment explains `outputs.upToDateWhen { false }`: "The stack is outside Gradle's view, so these tests are never up to date." Gradle cannot see a running container, so it must never skip the task.
- `testLogging { ... }` is the same as above.
- The `group` and `description` lines file the task under `Verification tasks` with the text that tells how to use it: `scripts/stack.sh test` starts the stack, runs this task and stops the stack. This note did not run it, because it needs Docker.

### The XML coverage report

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

`tasks.jacocoTestReport { ... }` configures the task that writes the coverage report. `reports { xml.required = true }` switches on the **XML report**. By default the task writes only the HTML report, which people read in a browser. XML is a text format made of tags such as `<counter type="LINE" missed="174" covered="1937"/>`; a program can read it, so the exact numbers can be copied into the README without counting by eye. The file lands in `build/reports/jacoco/test/jacocoTestReport.xml`.

### The coverage rule wired into `check`

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

- `tasks.jacocoTestCoverageVerification` is the task that checks coverage against a rule. `dependsOn(tasks.test)` makes sure the tests have run, so there is coverage data to read.
- `violationRules { rule { limit { ... } } }` is the rule. A **limit** names a counter, here `counter = "LINE"`, and a bound. `minimum = "0.80".toBigDecimal()` means the covered share of lines must be at least 0.80. `.toBigDecimal()` turns the text into an exact decimal number, which the plugin requires. The comment explains that the untested remainder is the command-line entry points.
- `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }` adds the coverage check to `check`. By default `check` does not run it, so this line is what makes the floor a real gate. I proved it in a scratch copy: with the minimum raised to 0.95 the build failed with `Rule violated for bundle seatwave-proxy: lines covered ratio is 0.91, but expected minimum is 0.95`.
- `check` is the standard "is this project healthy" task. It runs the tests, `spotlessCheck` and the coverage verification. No program task of the next sections is a dependency of it.

### The stack tasks

```kotlin
// The jars the container image copies: this project's jar and its runtime libraries, in one folder.
val stackFiles = tasks.register<Sync>("stackFiles") {
    group = "build"
    description = "Collects the jar and its runtime libraries into build/stack/lib for the Dockerfile."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("stack/lib"))
}

tasks.register("stackPrepare") {
    group = "build"
    description = "Prepares everything scripts/stack.sh needs: the jars and the TEST certificates."
    dependsOn(stackFiles, testCertificates)
}
```

- The comment says what `stackFiles` is for: "The jars the container image copies: this project's jar and its runtime libraries, in one folder."
- `tasks.register<Sync>("stackFiles")` creates a task of the type `Sync`. A `Sync` task copies files into a folder and **deletes** anything else in that folder, so the folder always matches the sources exactly.
- `from(tasks.jar)` adds the project's own jar, built by the `jar` task, and `from(configurations.runtimeClasspath)` adds every library that the main code needs at run time. `into(layout.buildDirectory.dir("stack/lib"))` is the target, `build/stack/lib`. I ran it and the folder held eight jars: `jackson-annotations-2.23.jar`, `jackson-core-2.23.0.jar`, `jackson-databind-2.23.0.jar`, `logback-classic-1.6.6.jar`, `logback-core-1.6.6.jar`, `result4j-1.2.0.jar`, `seatwave-proxy-1.0.0.jar` and `slf4j-api-2.0.20.jar`. The Dockerfile's `-cp /app/lib/*` takes every jar in that folder.
- `tasks.register("stackPrepare") { ... dependsOn(stackFiles, testCertificates) }` creates a task with no work of its own. It only depends on the two tasks above, so `./gradlew stackPrepare` means "the jars and the certificates". `scripts/stack.sh` calls it before `docker compose up`.

### The six program tasks

```kotlin
// One command per example: each entry point gets its own task, so a reader never assembles a classpath.
val entryPoints = mapOf(
    "serve" to "io.plainround.seatwave.proxy.backend.BackendCli",
    "bench" to "io.plainround.seatwave.proxy.bench.BenchCli",
    "probe" to "io.plainround.seatwave.proxy.probe.ProbeCli",
    "slowClients" to "io.plainround.seatwave.proxy.slow.SlowClientsCli",
    "logStats" to "io.plainround.seatwave.proxy.logs.LogStatsCli",
    "report" to "io.plainround.seatwave.proxy.report.ReportCli",
)

entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        dependsOn(testCertificates)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
        // Paths in --args are read from the project folder, wherever Gradle was started.
        workingDir = rootDir
        // The same switch the container sets: TCP_NODELAY on the JDK server's connections.
        if (taskName == "serve") {
            jvmArgs("-Dsun.net.httpserver.nodelay=true")
        }
    }
}
```

- The comment on line 245 says what the block is for: "One command per example: each entry point gets its own task, so a reader never assembles a classpath."
- `val entryPoints = mapOf(...)` creates a read-only map in Kotlin: each pair is a task name and the full name of a class that has a `main` method (an **entry point**, the place where a Java program starts). `"a" to "b"` builds one pair. The six pairs are `serve` (`BackendCli`, SeatWave itself), `bench` (`BenchCli`, the load generator), `probe` (`ProbeCli`, the outage probe), `slowClients` (`SlowClientsCli`, Lab 3), `logStats` (`LogStatsCli`, the access-log reader) and `report` (`ReportCli`, Module 5's per-phase table).
- `entryPoints.forEach { (taskName, entryPoint) -> ... }` runs the block once per pair. The round brackets `(taskName, entryPoint)` take the pair apart into its two parts.
- `tasks.register<JavaExec>(taskName) { ... }` creates a new task of the type `JavaExec`, which runs a Java program. `register` creates it lazily.
- `group = "application"` files the task in the `application` group in the `./gradlew tasks` list. `description = "Runs $entryPoint."` is the text shown next to it; `$entryPoint` inserts the variable into the text.
- `dependsOn(testCertificates)` makes every program task run the certificate task first. The servers and the clients need the key store and the CA file in `build/tls`.
- `classpath = sourceSets.main.get().runtimeClasspath` gives the program the compiled main classes plus the libraries it needs. `main` is `src/main/java`. Because `JavaExec` depends on the classpath, Gradle compiles the code first.
- `mainClass = entryPoint` names the class to start.
- The comment "Paths in --args are read from the project folder, wherever Gradle was started." and `workingDir = rootDir`: a **working directory** is the folder a program treats as "here": a relative path such as `config/bench.properties` is looked for from it. `rootDir` is the folder that holds `settings.gradle.kts`.
- `if (taskName == "serve") { jvmArgs("-Dsun.net.httpserver.nodelay=true") }` adds one option, for the `serve` task only. The comment says "The same switch the container sets: TCP_NODELAY on the JDK server's connections." `-D` sets a system property. TCP normally holds back small pieces of data for a moment to send them together (an algorithm named after Nagle); `TCP_NODELAY` turns that off so a small response leaves at once. The Dockerfile's `ENTRYPOINT` passes the same `-Dsun.net.httpserver.nodelay=true`, so a measurement on the machine and in the container behaves the same way.

**Why the measuring runs are their own tasks and not part of `check`.** A bench run takes minutes and its numbers depend on the machine and on a running stack, so a test could fail on a busy laptop without any bug. A table of medians is not a pass-or-fail check anyway: a person reads it. So the tests check that the machinery is right (the parsers, the settings, the client-address rule, the server) and finish in seconds, and the six tasks make the real measurements when asked. The cost: the `main` methods are not covered by tests, which is why the coverage floor is 0.80 and not higher (`jacoco.md`).

### The commands you type

- `./gradlew check` runs the full gate. `./` means "the file `gradlew` in this folder".
- `./gradlew spotlessApply` rewrites source files into the formatter's layout. Run it before `check` if `spotlessCheck` complains.
- `-q` (`--quiet`) hides Gradle's own messages, so only the program's output shows.
- `--args='...'` passes the text after it to the program's `main` method as arguments. Gradle splits the text at spaces into separate arguments. `./gradlew -q bench --args='config/bench.properties'` therefore gives `BenchCli` one argument: the settings file.
- `--offline` makes Gradle use only what is in its cache and touch no network. I used it for every command in these notes.
- When a program calls `System.exit` with a non-zero value, Gradle reports `Execution failed for task ':serve'` (the name of the task) and exits with 1 itself.

## Try it yourself

All commands below ran in a scratch copy of this repository on 11 November 2026 (Temurin 25.0.2, Gradle 9.9.1). Timings differ on every run.

1. List the tasks that run the programs:

```
./gradlew --offline -q tasks --group application
```

   The list starts with `Application tasks` and has these six lines, in alphabetical order: `bench`, `logStats`, `probe`, `report`, `serve` and `slowClients`, each followed by ` - Runs io.plainround.seatwave.proxy.<package>.<Class>.`, for example `serve - Runs io.plainround.seatwave.proxy.backend.BackendCli.`

2. Run the whole gate:

```
./gradlew --offline clean check
```

   It prints one `PASSED` line per test (119 in all, with `--console=plain`), then `> Task :jacocoTestCoverageVerification`, `> Task :spotlessJavaCheck` and ends with `BUILD SUCCESSFUL in 10s` and `10 actionable tasks: 10 executed`.

3. Make only the certificates, twice:

```
./gradlew --offline testCertificates
./gradlew --offline testCertificates
```

   The first prints `> Task :testCertificates`, `BUILD SUCCESSFUL in 1s` and `1 actionable task: 1 executed`. The second prints `1 actionable task: 1 up-to-date`. (Do not open the files in `build/tls`: they hold private keys. `ls -l build/tls` shows the names and sizes.)

4. Collect the jars for the container image:

```
./gradlew --offline stackPrepare
ls build/stack/lib
```

   Expected: `> Task :stackFiles`, `> Task :testCertificates`, `> Task :stackPrepare`, `BUILD SUCCESSFUL`, and the eight jars listed above.

5. Start SeatWave from its task on ports the system picks, ask it for `/health`, and stop it. First copy `config/backend-local.properties` to a folder outside `config/` and set the three ports to `0`, then:

```
./gradlew --offline -q serve --args='<your copy>'
```

   Expected: four lines, `SeatWave plain HTTP on 127.0.0.1:<port>`, `SeatWave HTTPS on 127.0.0.1:<port> (TLSv1.3)`, `SeatWave operations (metrics, health) on 127.0.0.1:<port>` and `64 worker threads, trusted proxies [127.0.0.1]`. In a second terminal, `curl -s -i http://127.0.0.1:<plain port>/health` printed `HTTP/1.1 200 OK` and the body `{"status":"up"}`, and `curl --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:<tls port>/health` returned status 200 with a verified certificate. Stop the server with Ctrl+C.

6. Break the coverage floor on purpose, in a scratch copy only. Change `"0.80"` to `"0.95"` in `build.gradle.kts` and run `./gradlew --offline check -q`. The output contains `Rule violated for bundle seatwave-proxy: lines covered ratio is 0.91, but expected minimum is 0.95` and `Execution failed for task ':jacocoTestCoverageVerification'`, and `BUILD FAILED`.

## Mistakes people make

- **Running `gradle` instead of `./gradlew`.** The installed Gradle may be a different version. The wrapper always starts 9.9.1 (`gradle-wrapper.md`).
- **Editing `gradle.lockfile` by hand.** The file says manual edits can break the build. Regenerate it (`dependency-locking.md`).
- **Adding a repository in `build.gradle.kts`.** `FAIL_ON_PROJECT_REPOS` in the settings file stops it on purpose.
- **Ignoring a compiler warning.** `-Werror` makes that impossible here.
- **Thinking `test` is enough.** `./gradlew test` skips the formatter check and the coverage floor. Only `check` runs all three.
- **Expecting `check` to prove the proxy works.** The tests tagged `stack` are left out of `check`. They run only through `stackTest` against a running stack.
- **Running a test class from an editor without Gradle.** The tests need the system properties `seatwave.tls.directory` and `seatwave.nginx.directory`; only the Gradle `test` task sets them, and only after `testCertificates` has made the files.
- **Working in a folder with a very long path.** The settings readers accept key store paths of at most 200 characters, and the `test` task passes an absolute path. In a folder whose `build/tls/seatwave-server.p12` path is 218 characters long, two tests failed (`BackendCliTest` and `BenchCliTest`) until the property was a short relative path. Keep the project in a short path (`java-properties.md`).
- **Looking for the XML report in the wrong place.** It is `build/reports/jacoco/test/jacocoTestReport.xml`, written only because of the `xml.required = true` line.
- **Committing anything from `build/tls`.** The folder is ignored on purpose (`git.md`).
- **Forgetting that the build file runs.** It is a program. Typing mistakes show up as Kotlin errors, not as friendly messages.

## Check yourself

1. *What is a task?* One named unit of work, such as `compileJava`, `test` or `check`. Gradle runs a task only after the tasks it depends on.
2. *What does `testImplementation(platform(libs.junit.bom))` do?* It loads a list of matching versions for the JUnit family, so the individual JUnit libraries need no version of their own.
3. *Why does `-Werror` matter?* It turns every compiler warning into an error, so no warning can stay unnoticed.
4. *Which line makes the coverage floor part of `check`?* `dependsOn(tasks.jacocoTestCoverageVerification)` inside `tasks.check`.
5. *Why is there both `excludeTags("stack")` and `includeTags("stack")`?* `test` (part of `check`) skips the tests that need the running stack, and `stackTest` runs only those.

## Further reading

- Gradle 9.9.1 user guide: <https://docs.gradle.org/9.9.1/userguide/userguide.html>
- Build file basics: <https://docs.gradle.org/9.9.1/userguide/part1_gradle_init.html>
- Java toolchains: <https://docs.gradle.org/9.9.1/userguide/toolchains.html>
- Kotlin DSL primer: <https://docs.gradle.org/9.9.1/userguide/kotlin_dsl.html>
- Custom task classes and up-to-date checks: <https://docs.gradle.org/9.9.1/userguide/custom_tasks.html>
- The Java plugin and its `check` task: <https://docs.gradle.org/9.9.1/userguide/java_plugin.html>
- The JaCoCo plugin and its reports: <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
- `keytool` for JDK 25: <https://docs.oracle.com/en/java/javase/25/docs/specs/man/keytool.html>
- Java Secure Socket Extension (JSSE) reference: <https://docs.oracle.com/en/java/javase/25/security/java-secure-socket-extension-jsse-reference-guide.html>
- PEM text encoding, RFC 7468: <https://www.rfc-editor.org/rfc/rfc7468>
