package io.plainround.seatwave.proxy.backend;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a route answers: a whole body, or an event stream that the server writes over time.
 * Immutable: every {@code with} method returns a new reply.
 */
public sealed interface Reply {

  /**
   * Returns the headers to send, in the order they were added.
   *
   * @return name and value pairs
   */
  List<Map.Entry<String, String>> headers();

  /**
   * Returns a JSON reply.
   *
   * @param status the HTTP status
   * @param json the body
   * @return the reply, with {@code application/json}
   */
  static Body json(int status, String json) {
    return new Body(status, "application/json", json.getBytes(StandardCharsets.UTF_8), List.of());
  }

  /**
   * A reply whose whole body is known before the first byte is sent.
   *
   * @param status the HTTP status
   * @param contentType the media type of the body
   * @param body the bytes, copied on the way in and out
   * @param headers further headers, in order
   */
  record Body(int status, String contentType, byte[] body, List<Map.Entry<String, String>> headers)
      implements Reply {

    /** Copies the body and the headers. */
    public Body {
      Objects.requireNonNull(contentType, "contentType");
      body = Objects.requireNonNull(body, "body").clone();
      headers = List.copyOf(headers);
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    /**
     * Returns the size of the body without copying it.
     *
     * @return the body's length in bytes
     */
    public int length() {
      return body.length;
    }

    /**
     * Adds one header.
     *
     * @param name the header name
     * @param value the header value
     * @return a new reply with the header last
     */
    public Body withHeader(String name, String value) {
      List<Map.Entry<String, String>> more = new ArrayList<>(headers);
      more.add(Map.entry(name, value));
      return new Body(status, contentType, body, more);
    }
  }

  /**
   * A server-sent event stream (Module 8): a reconnect delay, then a heartbeat comment after each
   * silent interval, until the stream's lifetime ends.
   *
   * @param retry the reconnect delay sent to the browser
   * @param heartbeat the silence after which a heartbeat is sent
   * @param lifetime when the stream ends
   * @param headers further headers, in order
   */
  record EventStream(
      Duration retry,
      Duration heartbeat,
      Duration lifetime,
      List<Map.Entry<String, String>> headers)
      implements Reply {

    /** Copies the headers. */
    public EventStream {
      Objects.requireNonNull(retry, "retry");
      Objects.requireNonNull(heartbeat, "heartbeat");
      Objects.requireNonNull(lifetime, "lifetime");
      headers = List.copyOf(headers);
    }
  }
}
