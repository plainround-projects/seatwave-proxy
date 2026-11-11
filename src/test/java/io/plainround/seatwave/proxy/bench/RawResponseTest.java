package io.plainround.seatwave.proxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class RawResponseTest {

  @Test
  void countsAChunkedGzipBodyAsWireBytesAndItsDecompressedSize() throws IOException {
    byte[] plain =
        "{\"seat\":\"A-1\",\"state\":\"available\"},".repeat(100).getBytes(StandardCharsets.UTF_8);
    byte[] compressed = gzip(plain);
    ByteArrayOutputStream response = new ByteArrayOutputStream();
    response.write(
        ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n"
                + "X-Cache-Status: MISS\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    int half = compressed.length / 2;
    chunk(response, compressed, 0, half);
    chunk(response, compressed, half, compressed.length - half);
    response.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

    LoadRun.Answer answer = RawResponse.parse(response.toByteArray());

    assertThat(answer).isEqualTo(new LoadRun.Answer(200, compressed.length, plain.length, "MISS"));
  }

  @Test
  void readsABodyThatEndsWithTheConnection() throws IOException {
    byte[] whole =
        "HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);

    assertThat(RawResponse.parse(whole)).isEqualTo(new LoadRun.Answer(404, 2, 2, ""));
  }

  @Test
  void refusesACutOffOrMalformedResponse() {
    assertThatThrownBy(
            () -> RawResponse.parse("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII)))
        .hasMessage("the response ended before its headers did");
    assertThatThrownBy(
            () -> RawResponse.parse("SPDY 200\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))
        .hasMessageContaining("not an HTTP/1.1 status line");
    assertThatThrownBy(() ->
            RawResponse.parse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"
                    .getBytes(StandardCharsets.US_ASCII)))
        .hasMessageContaining("not hexadecimal");
    assertThatThrownBy(() ->
            RawResponse.parse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nff\r\nab"
                    .getBytes(StandardCharsets.US_ASCII)))
        .hasMessage("a chunk is cut off");
  }

  private static void chunk(ByteArrayOutputStream out, byte[] data, int from, int length)
      throws IOException {
    out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(data, from, length);
    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] gzip(byte[] plain) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
      out.write(plain);
    }
    return bytes.toByteArray();
  }
}
