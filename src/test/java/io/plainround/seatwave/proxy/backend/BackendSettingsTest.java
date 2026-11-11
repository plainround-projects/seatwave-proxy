package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class BackendSettingsTest {

  @Test
  void readsTheContainerSettingsOfTheRepository() throws IOException {
    Properties file = SettingsReader.load(Path.of("config/backend-container.properties"));

    BackendSettings settings =
        BackendSettings.from(new SettingsReader(file))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));

    assertThat(settings.plainPort()).isEqualTo(8080);
    assertThat(settings.workerThreads()).isEqualTo(64);
    assertThat(settings.backlog()).isEqualTo(1024);
    assertThat(settings.trustedProxies().addresses()).hasSize(2);
    assertThat(settings.timing().longPollHold()).isEqualTo(Duration.ofSeconds(25));
  }

  @Test
  void reportsEveryProblemIncludingTheRulesBetweenKeysTogether() throws IOException {
    Properties file = SettingsReader.load(Path.of("config/backend-local.properties"));
    file.setProperty("http.tlsPort", file.getProperty("http.plainPort"));
    file.setProperty("feed.streamLifetimeMillis", "10");
    file.setProperty("forwarded.trustedProxies", "999.1.1.1");
    file.setProperty("http.bindAddress", "300.1.1.1");
    file.remove("http.workerThreads");

    Result<BackendSettings, List<String>> result = BackendSettings.from(new SettingsReader(file));

    assertThat(result).isInstanceOf(Result.Error.class);
    List<String> problems = ((Result.Error<BackendSettings, List<String>>) result).error();
    assertThat(problems)
        .anyMatch(line -> line.equals("http.workerThreads is missing"))
        .anyMatch(
            line -> line.startsWith("http.plainPort, http.tlsPort and http.adminPort must differ"))
        .anyMatch(line -> line.startsWith("feed.streamLifetimeMillis must be longer"))
        .anyMatch(line ->
            line.startsWith("forwarded.trustedProxies: a trusted proxy must be an IP address"))
        .anyMatch(line -> line.equals("http.bindAddress must be an IP address, but was 300.1.1.1"));
  }

  @Test
  void reportsMissingStreamKeysOnceWithoutTheRuleBetweenThem() throws IOException {
    Properties file = SettingsReader.load(Path.of("config/backend-local.properties"));
    file.remove("feed.heartbeatMillis");
    file.remove("feed.streamLifetimeMillis");

    Result<BackendSettings, List<String>> result = BackendSettings.from(new SettingsReader(file));

    assertThat(((Result.Error<BackendSettings, List<String>>) result).error())
        .containsExactly("feed.heartbeatMillis is missing", "feed.streamLifetimeMillis is missing");
  }

  @Test
  void refusesAShortIpv4FormAsATrustedProxy() throws IOException {
    Properties file = SettingsReader.load(Path.of("config/backend-local.properties"));
    // 127.0.1 is the old BSD short form of 127.0.0.1: written by mistake, it must not trust anyone.
    file.setProperty("forwarded.trustedProxies", "127.0.1");

    Result<BackendSettings, List<String>> result = BackendSettings.from(new SettingsReader(file));

    assertThat(((Result.Error<BackendSettings, List<String>>) result).error())
        .containsExactly(
            "forwarded.trustedProxies: a trusted proxy must be an IP address, but was '127.0.1'");
  }

  @Test
  void refusesListenersThatShareAPortWhenBuiltDirectly() {
    BackendSettings.Timing timing = TestBackend.quickTiming();

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new BackendSettings(
                java.net.InetAddress.getLoopbackAddress(),
                20931,
                20930,
                20931,
                Path.of("x"),
                Path.of("y"),
                1,
                1,
                1,
                io.plainround.seatwave.proxy.forwarded.TrustedProxies.parse(List.of("127.0.0.1"))
                    .orOnErrorThrow(IllegalStateException::new),
                timing))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new BackendSettings.Timing(Duration.ofMillis(-1), Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
