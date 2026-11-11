package io.plainround.seatwave.proxy.logs;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point of the log statistics: reads a copy of the proxy's access log and prints
 * one row per listening port. Exit status 2 means bad usage or an unreadable file; 1 means some
 * lines could not be read, and they are counted.
 */
public final class LogStatsCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  static final int EXIT_UNREADABLE_LINES = 1;

  private LogStatsCli() {}

  /**
   * Prints the statistics.
   *
   * @param arguments the log file
   */
  public static void main(String[] arguments) {
    System.exit(run(arguments, System.out, System.err));
  }

  /**
   * Prints the statistics and returns the exit status.
   *
   * @param arguments the command line
   * @param out where the table is printed
   * @param err where problems are printed
   * @return 0, 1 or 2, as the class documentation says
   */
  static int run(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 1) {
      err.println("usage: logStats <access.log>");
      return EXIT_BAD_USAGE;
    }
    List<AccessLine> lines = new ArrayList<>();
    int unreadable = 0;
    try {
      for (String text : Files.readAllLines(Path.of(arguments[0]), StandardCharsets.UTF_8)) {
        switch (AccessLine.parse(text)) {
          case Result.Success<AccessLine, String>(AccessLine line) -> lines.add(line);
          case Result.Error<AccessLine, String>(String reason) -> unreadable++;
        }
      }
    } catch (IOException missing) {
      err.println("cannot read the log " + arguments[0] + ": " + missing.getMessage());
      return EXIT_BAD_USAGE;
    }
    out.print(LogStats.render(lines));
    if (unreadable > 0) {
      out.println(unreadable + " line(s) could not be read");
      return EXIT_UNREADABLE_LINES;
    }
    return 0;
  }
}
