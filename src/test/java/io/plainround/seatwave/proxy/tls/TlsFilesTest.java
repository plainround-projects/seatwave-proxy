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
