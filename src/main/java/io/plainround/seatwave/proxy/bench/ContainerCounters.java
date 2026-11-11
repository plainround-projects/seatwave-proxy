package io.plainround.seatwave.proxy.bench;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@link Counters} from a running container with {@code docker exec}, which needs nothing
 * installed in the image beyond {@code cat}: both the NGINX image and SeatWave's have it.
 *
 * <p>The command is a fixed list of arguments, never a shell line, and the container name is
 * checked against an allow-list first, so no input can change what runs. Thread-safe: it holds only
 * immutable fields.
 */
public final class ContainerCounters {

  /** A Compose container name: lower-case letters, digits and hyphens. */
  public static final Pattern CONTAINER_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

  // A reading is a few hundred bytes; docker exec answers in well under this on a working engine.
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
  private static final Pattern CPU_USAGE = Pattern.compile("(?m)^usage_usec (\\d+)$");
  private static final Pattern TCP_LINES =
      Pattern.compile("(?m)^Tcp: ([A-Za-z ]+)\\nTcp: ([-0-9 ]+)$");

  private final List<String> commandPrefix;

  /**
   * Creates the reader.
   *
   * @param dockerCommand the docker executable, for example {@code docker}
   */
  public ContainerCounters(String dockerCommand) {
    this.commandPrefix = List.of(Objects.requireNonNull(dockerCommand, "dockerCommand"), "exec");
  }

  /**
   * Reads one container's counters now.
   *
   * @param container the container's name, for example {@code seatwave-proxy-proxy-1}
   * @return the counters
   * @throws IOException if docker fails, takes too long or prints something unreadable
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public Counters read(String container) throws IOException, InterruptedException {
    if (!CONTAINER_NAME.matcher(container).matches()) {
      throw new IllegalArgumentException("not a container name: " + container);
    }
    List<String> command =
        List.of(
            commandPrefix.get(0),
            commandPrefix.get(1),
            container,
            "cat",
            "/sys/fs/cgroup/cpu.stat",
            "/proc/net/snmp");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException("docker exec " + container + " did not answer in " + COMMAND_TIMEOUT);
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IOException("docker exec " + container + " failed: " + output.strip());
      }
      return switch (parse(output)) {
        case Result.Success<Counters, String>(Counters counters) -> counters;
        case Result.Error<Counters, String>(String reason) ->
            throw new IOException("cannot read the counters of " + container + ": " + reason);
      };
    } finally {
      // Released on every path, including the timeout.
      process.destroyForcibly();
    }
  }

  /**
   * Parses the output of {@code cat /sys/fs/cgroup/cpu.stat /proc/net/snmp}.
   *
   * @param output the two files, one after the other
   * @return the counters, or what was missing
   */
  public static Result<Counters, String> parse(String output) {
    Objects.requireNonNull(output, "output");
    Matcher cpu = CPU_USAGE.matcher(output);
    if (!cpu.find()) {
      return Result.error("no usage_usec line: the control group's cpu.stat was not read");
    }
    Matcher tcp = TCP_LINES.matcher(output);
    if (!tcp.find()) {
      return Result.error("no Tcp lines: /proc/net/snmp was not read");
    }
    List<String> names = List.of(tcp.group(1).strip().split(" +"));
    List<String> numbers = List.of(tcp.group(2).strip().split(" +"));
    int active = names.indexOf("ActiveOpens");
    int passive = names.indexOf("PassiveOpens");
    if (active < 0 || passive < 0 || names.size() != numbers.size()) {
      return Result.error("the Tcp lines have no ActiveOpens or PassiveOpens column");
    }
    try {
      return Result.success(
          new Counters(
              Long.parseLong(cpu.group(1)),
              Long.parseLong(numbers.get(passive)),
              Long.parseLong(numbers.get(active))));
    } catch (NumberFormatException notANumber) {
      return Result.error("a Tcp counter is not a number");
    }
  }
}
