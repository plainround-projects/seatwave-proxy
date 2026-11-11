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

/**
 * Builds TLS contexts from the TEST files that {@code ./gradlew testCertificates} writes into
 * {@code build/tls}: the server's keystore for SeatWave, and the TEST certificate authority for the
 * clients, which trust nothing else. Thread-safe: it holds no state.
 */
public final class TlsFiles {

  private TlsFiles() {}

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
      try {
        store.load(in, password);
      } catch (IOException wrongPassword) {
        // KeyStore.load reports a wrong password as an IOException with the JDK's own wording; the
        // message names both files, so a reader knows which pair does not match.
        throw new IOException(
            "the keystore "
                + keystore
                + " cannot be opened with the password in "
                + passwordFile
                + ": "
                + wrongPassword.getMessage(),
            wrongPassword);
      }
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

  private static InputStream open(Path file) throws IOException {
    try {
      return Files.newInputStream(file);
    } catch (NoSuchFileException missing) {
      throw new IOException(
          "the file " + file + " does not exist; run ./gradlew testCertificates", missing);
    }
  }
}
