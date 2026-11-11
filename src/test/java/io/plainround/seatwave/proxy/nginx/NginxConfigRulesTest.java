package io.plainround.seatwave.proxy.nginx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The proxy's half of the invariant, checked in the configuration files themselves, so a change
 * that breaks it fails the build without Docker: client addresses are overwritten, never appended;
 * every proxied location sends them; nothing makes the cache keep what SeatWave forbids; and every
 * port is published on 127.0.0.1 in Module 9's range. The stack tests then check the running proxy.
 */
class NginxConfigRulesTest {

  private static final Path NGINX = Path.of(System.getProperty("seatwave.nginx.directory"));
  private static final Pattern LISTEN = Pattern.compile("(?m)^\\s*listen (\\d+)");
  private static final Pattern PUBLISHED = Pattern.compile("\"127\\.0\\.0\\.1:(\\d+):(\\d+)\"");
  private static final Pattern ANY_PORT_LINE = Pattern.compile("(?m)^\\s+- \"([^\"]*:[^\"]*)\"$");
  private static final Pattern UPSTREAM = Pattern.compile("(?s)upstream ([a-z_]+) \\{([^}]*)\\}");
  private static final Pattern KEEPALIVE = Pattern.compile("(?m)^\\s*keepalive (\\d+);");
  private static final Pattern KEEPALIVE_TIMEOUT =
      Pattern.compile("(?m)^\\s*keepalive_timeout ([^;]+);");
  // An NGINX time: a number and a unit (ms, s, m, h); a number alone is seconds.
  private static final Pattern NGINX_TIME = Pattern.compile("(\\d+)(ms|s|m|h)?");
  // A Compose service starts at two spaces of indentation under "services:".
  private static final Pattern SERVICE = Pattern.compile("(?m)^  ([a-z]+):$");
  private static final Pattern SETTINGS_PORT =
      Pattern.compile("(?m)^http\\.(?:plain|tls|admin)Port=(\\d+)$");
  // The JDK's HTTP server closes an idle connection after 30 s (sun.net.httpserver.idleInterval).
  private static final Duration BACKEND_IDLE = Duration.ofSeconds(30);
  private static final int LOWEST_LAB_PORT = 20_900;
  private static final int HIGHEST_LAB_PORT = 20_999;

  @Test
  void everyProxiedLocationOfTheEdgeSendsTheForwardingHeaders() throws IOException {
    for (Path file : edgeFiles()) {
      for (String location : locations(read(file))) {
        if (location.contains("proxy_pass")) {
          assertThat(location)
              .as("a location in " + file.getFileName())
              .contains("include seatwave/proxy-headers.conf;");
        }
      }
    }
  }

  @Test
  void overwritesTheClientAddressInsteadOfAppendingToIt() throws IOException {
    String headers = read(NGINX.resolve("seatwave/proxy-headers.conf"));

    assertThat(headers)
        .contains("proxy_set_header X-Forwarded-For $remote_addr;")
        .contains("proxy_set_header Forwarded \"for=\\\"$forwarded_node\\\";proto=$scheme\";");
    // RFC 7239: an IPv6 node goes in brackets, and only the map in nginx.conf adds them.
    assertThat(read(NGINX.resolve("nginx.conf")))
        .containsPattern(
            "(?s)map \\$remote_addr \\$forwarded_node \\{\\s*~:\\s+\"\\[\\$remote_addr\\]\";");
    for (Path file : edgeFiles()) {
      assertThat(read(file)).as(file.toString()).doesNotContain("$proxy_add_x_forwarded_for");
    }
  }

  @Test
  void letsNothingOverrideWhatSeatWaveSaysAboutCaching() throws IOException {
    for (Path file : allFiles()) {
      String text = read(file);
      assertThat(text).as(file.toString()).doesNotContain("proxy_ignore_headers");
      // Fail safe: an expired entry is never served when SeatWave fails.
      assertThat(text).as(file.toString()).doesNotContain("proxy_cache_use_stale");
      assertThat(text).as(file.toString()).doesNotContainPattern("proxy_cache_methods[^;]*POST");
    }
    assertThat(read(NGINX.resolve("seatwave/production.conf")))
        .contains("proxy_cache_methods GET HEAD;")
        .contains("proxy_no_cache $http_authorization;");
  }

  @Test
  void closesIdleUpstreamConnectionsBeforeSeatWaveDoes() throws IOException {
    int reusingUpstreams = 0;
    for (Path file : allFiles()) {
      Matcher upstream = UPSTREAM.matcher(read(file).replaceAll("(?m)#.*$", ""));
      while (upstream.find()) {
        String body = upstream.group(2);
        Matcher keepalive = KEEPALIVE.matcher(body);
        // Since NGINX 1.29.7 an upstream keeps idle connections unless it says keepalive 0.
        boolean reuses = !keepalive.find() || Integer.parseInt(keepalive.group(1)) > 0;
        if (!reuses) {
          continue;
        }
        reusingUpstreams++;
        Matcher timeout = KEEPALIVE_TIMEOUT.matcher(body);
        // Without the directive NGINX keeps an idle connection 60 s, longer than SeatWave's 30 s.
        assertThat(timeout.find())
            .as("keepalive_timeout in upstream " + upstream.group(1))
            .isTrue();
        assertThat(nginxTime(timeout.group(1).strip()))
            .as("keepalive_timeout of upstream " + upstream.group(1))
            .isLessThan(BACKEND_IDLE);
        assertThat(timeout.find())
            .as("a second keepalive_timeout in " + upstream.group(1))
            .isFalse();
      }
    }

    assertThat(reusingUpstreams).isPositive();
  }

  @Test
  void readsNginxTimesInEveryUnitTheConfigurationMayUse() {
    assertThat(nginxTime("20s")).isEqualTo(Duration.ofSeconds(20));
    assertThat(nginxTime("20")).isEqualTo(Duration.ofSeconds(20));
    assertThat(nginxTime("1m")).isEqualTo(Duration.ofMinutes(1));
    assertThat(nginxTime("2500ms")).isEqualTo(Duration.ofMillis(2_500));
    assertThat(nginxTime("1h")).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void publishesEveryProxyPortOnTheLoopbackAddressInTheModulesRange() throws IOException {
    String compose = read(Path.of("compose.yaml"));
    Matcher published = PUBLISHED.matcher(compose);
    List<Integer> hostPorts = new ArrayList<>();
    while (published.find()) {
      hostPorts.add(Integer.parseInt(published.group(1)));
    }
    Set<Integer> listened =
        edgeFiles().stream()
            .flatMap(file -> LISTEN.matcher(read(file)).results().map(match -> match.group(1)))
            .map(Integer::parseInt)
            .collect(Collectors.toSet());

    assertThat(hostPorts)
        .isNotEmpty()
        .allMatch(port -> port >= LOWEST_LAB_PORT && port <= HIGHEST_LAB_PORT);
    assertThat(hostPorts).doesNotHaveDuplicates();
    assertThat(ANY_PORT_LINE.matcher(compose).results().map(match -> match.group(1)))
        .allMatch(line -> line.startsWith("127.0.0.1:"));
    // Per service: every port the proxy listens on is published, and nothing else of the proxy.
    assertThat(containerPorts(service(compose, "proxy"))).isEqualTo(listened);
  }

  @Test
  void publishesExactlyTheBackendPortsItsContainerSettingsListenOn() throws IOException {
    String compose = read(Path.of("compose.yaml"));
    Set<Integer> listened =
        SETTINGS_PORT
            .matcher(read(Path.of("config/backend-container.properties")))
            .results()
            .map(match -> Integer.parseInt(match.group(1)))
            .collect(Collectors.toSet());

    assertThat(listened).hasSize(3);
    assertThat(containerPorts(service(compose, "backend"))).isEqualTo(listened);
  }

  @Test
  void pinsEveryImageByDigestAndGrantsNoPrivilege() throws IOException {
    String compose = read(Path.of("compose.yaml"));
    String dockerfile = read(Path.of("Dockerfile"));

    String settings = compose.replaceAll("(?m)#.*$", "");
    assertThat(settings)
        .doesNotContain("privileged:", "network_mode: host", "docker.sock", "pid: host");
    assertThat(settings.lines().filter(line -> line.contains(" nginx:")))
        .allMatch(line -> line.contains("@sha256:"));
    assertThat(dockerfile.lines().filter(line -> line.startsWith("FROM ")))
        .hasSize(2)
        .allMatch(line -> line.contains("@sha256:"));
    assertThat(dockerfile).contains("USER 10001");
  }

  private static String service(String compose, String name) {
    List<MatchResult> services = SERVICE.matcher(compose).results().toList();
    for (int index = 0; index < services.size(); index++) {
      if (services.get(index).group(1).equals(name)) {
        int end = index + 1 < services.size() ? services.get(index + 1).start() : compose.length();
        return compose.substring(services.get(index).end(), end);
      }
    }
    throw new IllegalStateException("compose.yaml has no service " + name);
  }

  private static Set<Integer> containerPorts(String service) {
    return PUBLISHED
        .matcher(service)
        .results()
        .map(match -> Integer.parseInt(match.group(2)))
        .collect(Collectors.toSet());
  }

  private static Duration nginxTime(String text) {
    Matcher time = NGINX_TIME.matcher(text);
    if (!time.matches()) {
      throw new IllegalArgumentException("not an NGINX time this test reads: " + text);
    }
    long amount = Long.parseLong(time.group(1));
    return switch (time.group(2) == null ? "s" : time.group(2)) {
      case "ms" -> Duration.ofMillis(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      default -> Duration.ofSeconds(amount);
    };
  }

  private static List<Path> edgeFiles() throws IOException {
    try (Stream<Path> files = Files.walk(NGINX)) {
      return files
          .filter(file -> file.toString().endsWith(".conf"))
          .filter(file -> !file.toString().contains("sidecar"))
          .sorted()
          .toList();
    }
  }

  private static List<Path> allFiles() throws IOException {
    try (Stream<Path> files = Files.walk(NGINX)) {
      return files.filter(file -> file.toString().endsWith(".conf")).sorted().toList();
    }
  }

  private static List<String> locations(String file) {
    // Comments are dropped first, so a sentence that mentions a location is not parsed as one.
    String text = file.replaceAll("(?m)#.*$", "");
    List<String> blocks = new ArrayList<>();
    int from = 0;
    while ((from = text.indexOf("location ", from)) >= 0) {
      int open = text.indexOf('{', from);
      int depth = 0;
      int index = open;
      do {
        char character = text.charAt(index);
        depth += character == '{' ? 1 : character == '}' ? -1 : 0;
        index++;
      } while (depth > 0 && index < text.length());
      blocks.add(text.substring(from, index));
      from = index;
    }
    return blocks;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new java.io.UncheckedIOException("cannot read " + file, unreadable);
    }
  }
}
