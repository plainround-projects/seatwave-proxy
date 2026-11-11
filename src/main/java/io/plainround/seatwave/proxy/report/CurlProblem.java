package io.plainround.seatwave.proxy.report;

/** Why one line of curl output did not become a {@link CurlSample}. */
public sealed interface CurlProblem {

  /**
   * Says what went wrong, for the report's list of refused lines.
   *
   * @return one line of text
   */
  String describe();

  /**
   * The line is not in the timing script's format.
   *
   * @param line the line, cut to its first 80 characters
   * @param reason what is missing or invalid
   */
  record Malformed(String line, String reason) implements CurlProblem {
    @Override
    public String describe() {
      return "malformed line (" + reason + "): " + line;
    }
  }

  /**
   * The line is well formed, but its numbers cannot be a timing.
   *
   * @param target the target's name
   * @param reason which rule the line breaks
   */
  record Rejected(String target, String reason) implements CurlProblem {
    @Override
    public String describe() {
      return "rejected run of " + target + ": " + reason;
    }
  }
}
