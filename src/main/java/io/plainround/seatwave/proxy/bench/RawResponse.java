package io.plainround.seatwave.proxy.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Reads one whole HTTP/1.1 response that a {@code Connection: close} request received: the status,
 * the body as it crossed the wire, and its size after decompression.
 *
 * <p>The body is either delimited by the end of the connection or sent in chunks (RFC 9112, Section
 * 7.1), which NGINX uses when it compresses on the fly and so does not know the length in advance;
 * the chunk framing is removed before bytes are counted. Thread-safe: it holds no state.
 */
final class RawResponse {

  private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] (\\d{3})( .*)?");
  private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
  private static final int HEX = 16;

  private RawResponse() {}

  static LoadRun.Answer parse(byte[] whole) throws IOException {
    int headerEnd = indexOf(whole, HEADER_END);
    if (headerEnd < 0) {
      throw new IOException("the response ended before its headers did");
    }
    String[] lines = new String(whole, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n", -1);
    Matcher status = STATUS_LINE.matcher(lines[0]);
    if (!status.matches()) {
      throw new IOException("not an HTTP/1.1 status line: " + lines[0]);
    }
    boolean isChunked = false;
    boolean isGzip = false;
    String cacheStatus = "";
    for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
      String line = lines[lineIndex].toLowerCase(Locale.ROOT);
      isChunked |= line.startsWith("transfer-encoding:") && line.contains("chunked");
      isGzip |= line.startsWith("content-encoding:") && line.contains("gzip");
      if (line.startsWith("x-cache-status:")) {
        cacheStatus = lines[lineIndex].substring("x-cache-status:".length()).strip();
      }
    }
    int bodyStart = headerEnd + HEADER_END.length;
    byte[] body =
        isChunked ? unchunk(whole, bodyStart) : Arrays.copyOfRange(whole, bodyStart, whole.length);
    long plain = isGzip ? gunzippedLength(body) : body.length;
    return new LoadRun.Answer(Integer.parseInt(status.group(1)), body.length, plain, cacheStatus);
  }

  private static byte[] unchunk(byte[] whole, int start) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    int position = start;
    while (true) {
      int lineEnd = indexOf(whole, "\r\n".getBytes(StandardCharsets.US_ASCII), position);
      if (lineEnd < 0) {
        throw new IOException("a chunk size line is cut off");
      }
      String sizeText = new String(whole, position, lineEnd - position, StandardCharsets.US_ASCII);
      int semicolon = sizeText.indexOf(';');
      int size;
      try {
        size =
            Integer.parseInt(
                (semicolon < 0 ? sizeText : sizeText.substring(0, semicolon)).strip(), HEX);
      } catch (NumberFormatException notHex) {
        throw new IOException("a chunk size is not hexadecimal: " + sizeText, notHex);
      }
      position = lineEnd + 2;
      if (size == 0) {
        return body.toByteArray();
      }
      if (position + size > whole.length) {
        throw new IOException("a chunk is cut off");
      }
      body.write(whole, position, size);
      position += size + 2;
    }
  }

  static long gunzippedLength(byte[] compressed) throws IOException {
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      return in.transferTo(OutputStream.nullOutputStream());
    }
  }

  private static int indexOf(byte[] data, byte[] pattern) {
    return indexOf(data, pattern, 0);
  }

  private static int indexOf(byte[] data, byte[] pattern, int from) {
    for (int start = from; start <= data.length - pattern.length; start++) {
      boolean isMatch = true;
      for (int offset = 0; offset < pattern.length && isMatch; offset++) {
        isMatch = data[start + offset] == pattern[offset];
      }
      if (isMatch) {
        return start;
      }
    }
    return -1;
  }
}
