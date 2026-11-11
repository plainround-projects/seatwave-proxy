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

plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

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

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

/**
 * Makes throwaway TEST certificates with the JDK's keytool: one test certificate authority (CA) and
 * one server certificate it signs, for SeatWave's own HTTPS listener, the reverse proxy and the
 * sidecar. NGINX reads PEM files, so the server's key and chain are also written as PEM.
 *
 * Everything lands in build/tls, which git ignores, so no key or keystore is ever committed. The
 * keystore password is random per run and written to a file next to the keystore, so no password
 * appears in code, configuration or a process listing.
 */
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
        // EC P-256 keys: the curve every current TLS client supports, and a fast handshake.
        val ecKey = listOf("-keyalg", "EC", "-groupname", "secp256r1")
        keytool(
            listOf("-genkeypair", "-keystore", caStore, "-storetype", "PKCS12", "-storepass:file", passwordPath,
                "-alias", "ca", "-dname", "CN=SeatWave TEST CA - never trust outside this lab", "-validity", "30",
                "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign") + ecKey,
        )
        keytool(
            listOf("-exportcert", "-rfc", "-keystore", caStore, "-storepass:file", passwordPath, "-alias", "ca",
                "-file", directory.resolve("seatwave-test-ca.pem").absolutePath),
        )
        // One server certificate for every TLS endpoint of the lab: the names a client on this
        // machine uses (localhost, 127.0.0.1) and the container addresses the proxy uses to reach
        // the sidecar (10.209.9.20).
        keytool(
            listOf("-genkeypair", "-keystore", caStore, "-storepass:file", passwordPath, "-alias", "server",
                "-dname", "CN=SeatWave TEST server", "-signer", "ca", "-signerkeypass:file", passwordPath,
                "-validity", "30", "-ext", "san=dns:localhost,ip:127.0.0.1,ip:10.209.9.20,dns:backend") + ecKey,
        )
        val serverStore = directory.resolve("seatwave-server.p12")
        keytool(
            listOf("-importkeystore", "-srckeystore", caStore, "-srcstorepass:file", passwordPath, "-srcalias", "server",
                "-destkeystore", serverStore.absolutePath, "-deststoretype", "PKCS12",
                "-deststorepass:file", passwordPath, "-destalias", "seatwave"),
        )
        writePem(serverStore, password, directory)
        // The files the containers read are rw-r--r--: on a Linux host a bind mount keeps the host's
        // owner and mode, and the containers read them as other users (NGINX's master as root
        // without CAP_DAC_OVERRIDE, SeatWave as uid 10001), so owner-only files could not be read.
        // Docker Desktop on a Mac shows every shared file as owned by the user reading it, so there
        // rw------- works too. The CA's keystore, which holds the CA's private key, is read by no
        // container and stays rw-------. Nothing here is ever committed: .gitignore refuses these
        // file types, and the keys are TEST keys made again every day.
        directory.listFiles().orEmpty().forEach { file ->
            val mode = if (file.name == "seatwave-test-ca.p12") "rw-------" else "rw-r--r--"
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
        }
    }

    private fun writePem(serverStore: java.io.File, password: String, directory: java.io.File) {
        val store = KeyStore.getInstance("PKCS12")
        serverStore.inputStream().use { input -> store.load(input, password.toCharArray()) }
        val key = store.getKey("seatwave", password.toCharArray()) as PrivateKey
        val chain = store.getCertificateChain("seatwave")
        directory.resolve("server-key.pem").writeText(pem("PRIVATE KEY", key.encoded), Charsets.US_ASCII)
        directory.resolve("server-chain.pem")
            .writeText(chain.joinToString("") { certificate -> pem("CERTIFICATE", certificate.encoded) }, Charsets.US_ASCII)
    }

    private fun pem(label: String, der: ByteArray): String {
        // PEM is base64 in lines of 64 characters between two marker lines (RFC 7468).
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

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

val testCertificates = tasks.register<TestCertificates>("testCertificates") {
    group = "verification"
    description = "Makes the throwaway TEST certificate authority and server certificate in build/tls."
    keytool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { launcher -> launcher.metadata.installationPath.file("bin/keytool").asFile.absolutePath }
    day = LocalDate.now(ZoneOffset.UTC).toString()
    outputDirectory = layout.buildDirectory.dir("tls")
}

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

// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

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
