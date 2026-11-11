package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendCliTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @Test
  void printsTheUsageWithoutExactlyOneArgument() {
    Optional<BackendServer> server = BackendCli.start(new String[0], print(out), print(err));

    assertThat(server).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: serve <backend.properties>");
  }

  @Test
  void printsEveryProblemOfAnInvalidSettingsFile(@TempDir Path folder) throws IOException {
    Path file =
        Files.writeString(
            folder.resolve("bad.properties"), "http.plainPort=x\n", StandardCharsets.UTF_8);

    Optional<BackendServer> server =
        BackendCli.start(new String[] {file.toString()}, print(out), print(err));

    assertThat(server).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains(
            "problem(s):",
            "http.plainPort must be a whole number, but was x",
            "tls.keystore is missing");
  }

  @Test
  void refusesASettingsFileWithABrokenEscapeWithoutAStackTrace(@TempDir Path folder)
      throws IOException {
    Path file =
        Files.writeString(
            folder.resolve("broken.properties"),
            "http.bindAddress=\\u12\n",
            StandardCharsets.UTF_8);

    Optional<BackendServer> server =
        BackendCli.start(new String[] {file.toString()}, print(out), print(err));

    assertThat(server).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("is not a valid properties file: Malformed \\uxxxx encoding.");
  }

  @Test
  void saysWhichTlsFileIsMissing(@TempDir Path folder) throws IOException {
    String settings =
        Files.readString(Path.of("config/backend-local.properties"), StandardCharsets.UTF_8)
            .replace("build/tls/seatwave-server.p12", "build/none.p12")
            .replace("http.plainPort=20931", "http.plainPort=0")
            .replace("http.tlsPort=20930", "http.tlsPort=0")
            .replace("http.adminPort=20932", "http.adminPort=0");
    Path file =
        Files.writeString(folder.resolve("local.properties"), settings, StandardCharsets.UTF_8);

    Optional<BackendServer> server =
        BackendCli.start(new String[] {file.toString()}, print(out), print(err));

    assertThat(server).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("SeatWave cannot start: the file build/none.p12 does not exist");
  }

  @Test
  void startsAndPrintsItsListenersFromAValidFile(@TempDir Path folder) throws IOException {
    String settings =
        Files.readString(Path.of("config/backend-local.properties"), StandardCharsets.UTF_8)
            .replace("http.plainPort=20931", "http.plainPort=0")
            .replace("http.tlsPort=20930", "http.tlsPort=0")
            .replace("http.adminPort=20932", "http.adminPort=0")
            .replace("build/tls/", TestBackend.TLS + "/");
    Path file =
        Files.writeString(folder.resolve("local.properties"), settings, StandardCharsets.UTF_8);

    Optional<BackendServer> server =
        BackendCli.start(new String[] {file.toString()}, print(out), print(err));

    try {
      assertThat(server).isPresent();
      assertThat(out.toString(StandardCharsets.UTF_8))
          .contains(
              "SeatWave plain HTTP on 127.0.0.1:",
              "(TLSv1.3)",
              "64 worker threads, trusted proxies [127.0.0.1]");
    } finally {
      server.ifPresent(BackendServer::close);
    }
  }

  private static PrintStream print(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }
}
