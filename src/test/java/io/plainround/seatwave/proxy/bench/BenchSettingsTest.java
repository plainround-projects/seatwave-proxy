package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.config.SettingsReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class BenchSettingsTest {

  @Test
  void readsTheBenchSettingsOfTheRepository() throws Exception {
    Properties file = SettingsReader.load(Path.of("config/bench.properties"));

    BenchSettings settings =
        BenchSettings.from(new SettingsReader(file))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));

    assertThat(settings.repeats()).isGreaterThanOrEqualTo(3);
    assertThat(settings.scenarios()).isNotEmpty();
    assertThat(settings.scenarios())
        .allMatch(scenario -> scenario.uri().getHost().equals("127.0.0.1"));
  }

  @Test
  void refusesATargetOutsideTheLabAndReportsEveryProblem() throws Exception {
    Properties file = SettingsReader.load(Path.of("config/bench.properties"));
    String first = file.getProperty("scenario.names").split(",")[0].strip();
    file.setProperty("scenario." + first + ".uri", "https://seatwave.example/health");
    file.setProperty("run.repeats", "2");
    file.setProperty("counters.proxyContainer", "Proxy Container");

    Result<BenchSettings, List<String>> result = BenchSettings.from(new SettingsReader(file));

    assertThat(result.isError()).isTrue();
    assertThat(((Result.Error<BenchSettings, List<String>>) result).error())
        .anyMatch(line -> line.startsWith("scenario." + first + ".uri must match"))
        .anyMatch(line -> line.startsWith("run.repeats must be from 3"))
        .anyMatch(line -> line.startsWith("counters.proxyContainer must match"));
  }
}
