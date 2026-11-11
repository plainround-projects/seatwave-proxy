package io.plainround.seatwave.proxy.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * The static files SeatWave serves under {@code /static/}: the seat page's script and a poster.
 * Both are built once at start-up and never change while the process runs. Thread-safe: the map is
 * immutable.
 */
final class StaticFiles {

  /** How long a static file may be cached: 300 seconds, the module's fixed lifetime. */
  static final int MAX_AGE_SECONDS = 300;

  // A poster image is about 300 KB (Module 9, Section 7). Random bytes stand in for it: a JPEG is
  // already compressed, so, like random bytes, it does not shrink under gzip.
  private static final int POSTER_BYTES = 300_000;
  // A fixed seed, so every start serves the same poster and a cached copy stays valid.
  private static final long POSTER_SEED = 20_261_111L;

  private final Map<String, StaticFile> files;

  StaticFiles() {
    byte[] poster = new byte[POSTER_BYTES];
    new SplittableRandom(POSTER_SEED).nextBytes(poster);
    this.files =
        Map.of(
            "/static/seat-map.js",
            new StaticFile("text/javascript", resource("static/seat-map.js")),
            "/static/poster-arena.bin",
            new StaticFile("application/octet-stream", poster));
  }

  Optional<StaticFile> find(String path) {
    return Optional.ofNullable(files.get(path));
  }

  private static byte[] resource(String name) {
    try (InputStream in = StaticFiles.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("the resource " + name + " is not on the classpath");
      }
      return in.readAllBytes();
    } catch (IOException unreadable) {
      throw new UncheckedIOException("cannot read the resource " + name, unreadable);
    }
  }

  /** One file: its media type and its bytes. */
  record StaticFile(String contentType, byte[] bytes) {
    StaticFile {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
