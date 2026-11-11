package io.plainround.seatwave.proxy.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportCliTest {

  @Test
  void printsTheTableOfAFileOfCurlLines(@TempDir Path folder) throws Exception {
    Path lines =
        Files.write(
            folder.resolve("timings.txt"),
            Collections.nCopies(3, CurlLineTest.NEW_HTTPS),
            StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);

    int status = ReportCli.run(new String[] {lines.toString()}, printer, printer);

    assertThat(status).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("seatwave-https    new        HTTP/1.1     3");
  }

  @Test
  void stopsWithStatusOneAndNamesALineItRefused(@TempDir Path folder) throws Exception {
    Path lines =
        Files.write(
            folder.resolve("timings.txt"),
            java.util.List.of(
                CurlLineTest.NEW_HTTPS,
                CurlLineTest.NEW_HTTPS,
                CurlLineTest.NEW_HTTPS,
                "seatwave-https dns=not-a-number"),
            StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);

    int status = ReportCli.run(new String[] {lines.toString()}, printer, printer);

    assertThat(status).isEqualTo(ReportCli.EXIT_REFUSED_LINES);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("seatwave-https    new        HTTP/1.1     3")
        .contains("dns=not-a-number");
  }

  @Test
  void refusesBadUsageAMissingFileAndTooFewRuns(@TempDir Path folder) throws Exception {
    Path one =
        Files.writeString(
            folder.resolve("one.txt"),
            "# a comment\n" + CurlLineTest.NEW_HTTPS + "\n",
            StandardCharsets.UTF_8);
    PrintStream printer =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    assertThat(ReportCli.run(new String[0], printer, printer)).isEqualTo(ReportCli.EXIT_BAD_USAGE);
    assertThat(ReportCli.run(new String[] {folder.resolve("none").toString()}, printer, printer))
        .isEqualTo(ReportCli.EXIT_BAD_USAGE);
    assertThat(ReportCli.run(new String[] {one.toString()}, printer, printer))
        .isEqualTo(ReportCli.EXIT_REFUSED_LINES);
  }
}
