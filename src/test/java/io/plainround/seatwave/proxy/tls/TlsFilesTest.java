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
