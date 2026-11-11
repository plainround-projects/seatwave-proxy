package io.plainround.seatwave.proxy.nginx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
  private static final Pattern KEEPALIVE_TIMEOUT =
      Pattern.compile("(?s)upstream [a-z_]+ \\{[^}]*?keepalive_timeout (\\d+)s;");
  // The JDK's HTTP server closes an idle connection after 30 s (sun.net.httpserver.idleInterval).
  private static final int BACKEND_IDLE_SECONDS = 30;
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
        .contains("proxy_set_header Forwarded \"for=$remote_addr;proto=$scheme\";");
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
    List<Integer> timeouts = new ArrayList<>();
    for (Path file : allFiles()) {
      Matcher matcher = KEEPALIVE_TIMEOUT.matcher(read(file));
      while (matcher.find()) {
        timeouts.add(Integer.parseInt(matcher.group(1)));
      }
    }

    assertThat(timeouts).isNotEmpty().allMatch(seconds -> seconds < BACKEND_IDLE_SECONDS);
  }

  @Test
  void publishesEveryProxyPortOnTheLoopbackAddressInTheModulesRange() throws IOException {
    String compose = read(Path.of("compose.yaml"));
    Matcher published = PUBLISHED.matcher(compose);
    Set<Integer> containerPorts = new java.util.HashSet<>();
    List<Integer> hostPorts = new ArrayList<>();
    while (published.find()) {
      hostPorts.add(Integer.parseInt(published.group(1)));
      containerPorts.add(Integer.parseInt(published.group(2)));
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
    assertThat(containerPorts).containsAll(listened);
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
