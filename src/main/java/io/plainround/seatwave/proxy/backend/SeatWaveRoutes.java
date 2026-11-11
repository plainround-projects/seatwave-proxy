package io.plainround.seatwave.proxy.backend;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.proxy.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SeatWave's routes in this lab: Module 7's version 1 paths that the proxy labs need, Module 1's
 * seat map, Module 8's long poll and event stream, and the static files.
 *
 * <p>Every reply says how it may be cached, because the proxy obeys it: static files are {@code
 * public, max-age=300}; a hold is {@code private}, so no shared cache may keep it; seat maps and
 * events are {@code no-cache}; writes and errors are {@code no-store}. Thread-safe: the ledger, the
 * key table and the metrics are, and nothing else changes after construction.
 */
final class SeatWaveRoutes {

  /** Module 7's contract limit on a request body. */
  static final int MAX_BODY_BYTES = 1_024;

  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveRoutes.class);
  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int BAD_REQUEST = 400;
  private static final int NOT_FOUND = 404;
  private static final int METHOD_NOT_ALLOWED = 405;
  private static final int CONFLICT = 409;
  private static final int TOO_LARGE = 413;
  private static final int UNSUPPORTED_MEDIA_TYPE = 415;
  private static final int UNPROCESSABLE = 422;
  private static final int UNAVAILABLE = 503;
  // Module 7's page limits: 1 to 500 items, 100 when the client does not say.
  private static final int MAX_LIMIT = 500;
  private static final int DEFAULT_LIMIT = 100;
  // Module 8's event stream asks the browser to reconnect after 1 second.
  private static final Duration STREAM_RETRY = Duration.ofSeconds(1);
  // Module 7: version 0 was deprecated on 11 November 2026 and stops answering on 11 May 2027.
  private static final Instant VERSION0_DEPRECATED_AT = Instant.parse("2026-11-11T00:00:00Z");
  private static final Instant VERSION0_SUNSET_AT = Instant.parse("2027-05-11T00:00:00Z");
  private static final Pattern EVENT_SEATS = Pattern.compile("/v1/events/([0-9]{1,18})/seats");
  private static final Pattern EVENT_CHANGES =
      Pattern.compile("/v1/events/([0-9]{1,18})/seat-changes(/stream)?");
  private static final Pattern HOLD_PATH =
      Pattern.compile("/v1/holds/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
  // Module 7: an Idempotency-Key is an RFC 9651 String, a quoted value of printable characters.
  private static final Pattern IDEMPOTENCY_KEY =
      Pattern.compile("\"[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,64}\"");
  private static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern POSITION = Pattern.compile("[A-Za-z0-9:_-]{1,40}");
  private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]{0,2}");
  private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final HoldLedger ledger;
  private final SeatCatalog catalog;
  private final HoldKeys keys;
  private final StaticFiles staticFiles;
  private final BackendMetrics metrics;
  private final BackendSettings.Timing timing;
  private final JsonCodec json;
  private final Problems problems;

  SeatWaveRoutes(
      HoldLedger ledger, BackendMetrics metrics, BackendSettings.Timing timing, JsonCodec json) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.timing = Objects.requireNonNull(timing, "timing");
    this.json = Objects.requireNonNull(json, "json");
    this.catalog = new SeatCatalog(ledger);
    this.keys = new HoldKeys();
    this.staticFiles = new StaticFiles();
    this.problems = new Problems(json);
  }

  /**
   * Answers one request.
   *
   * @param request the request, with its client already decided
   * @return the route's template, for the metrics, and the reply
   */
  Routed route(Request request) {
    String path = request.path();
    if (request.method().equals("POST")) {
      return path.equals("/v1/holds")
          ? new Routed("POST /v1/holds", hold(request))
          : new Routed("other", notAllowed(request, "GET"));
    }
    if (!request.method().equals("GET")) {
      return new Routed("other", notAllowed(request, path.equals("/v1/holds") ? "POST" : "GET"));
    }
    return switch (path) {
      case "/health" -> new Routed("GET /health", noQuery(request, this::health));
      case "/lab/client" -> new Routed("GET /lab/client", noQuery(request, this::clientView));
      case "/v1/events" -> new Routed("GET /v1/events", noQuery(request, this::events));
      case "/events/1/seats" -> new Routed("GET /events/1/seats", version0SeatMap(request));
      default -> routeByPattern(request);
    };
  }

  private Routed routeByPattern(Request request) {
    String path = request.path();
    if (path.startsWith("/static/")) {
      return new Routed("GET /static/{file}", staticFile(request));
    }
    Matcher seats = EVENT_SEATS.matcher(path);
    if (seats.matches()) {
      return new Routed("GET /v1/events/{eventId}/seats", seatPage(request, seats.group(1)));
    }
    Matcher changes = EVENT_CHANGES.matcher(path);
    if (changes.matches()) {
      boolean isStream = changes.group(2) != null;
      return isStream
          ? new Routed("GET /v1/events/{eventId}/seat-changes/stream", stream(request, changes))
          : new Routed("GET /v1/events/{eventId}/seat-changes", longPoll(request, changes));
    }
    Matcher hold = HOLD_PATH.matcher(path);
    if (hold.matches()) {
      return new Routed("GET /v1/holds/{holdId}", readHold(request, hold.group(1)));
    }
    return new Routed("other", notFound(request));
  }

  private Reply health(Request request) {
    return Reply.json(OK, "{\"status\":\"up\"}").withHeader("Cache-Control", "no-store");
  }

  private Reply clientView(Request request) {
    // A lab route outside /v1: it shows which client address the invariant let SeatWave believe.
    ClientDiagnostic view =
        new ClientDiagnostic(
            request.client().address().getHostAddress(),
            request.client().scheme(),
            request.client().source().name().toLowerCase(Locale.ROOT));
    return Reply.json(OK, json.write(view)).withHeader("Cache-Control", "no-store");
  }

  private Reply events(Request request) {
    EventView event =
        new EventView(SeatCatalog.EVENT_ID, "Arena Night", SeatCatalog.EVENT_STARTS_AT.toString());
    return Reply.json(OK, json.write(new EventPage(List.of(event))))
        .withHeader("Cache-Control", "no-cache");
  }

  private Reply version0SeatMap(Request request) {
    String query = request.rawQuery();
    String section = query.startsWith("section=") ? query.substring("section=".length()) : "";
    if (!SeatCatalog.isSection(section)) {
      return problems.of(
          BAD_REQUEST,
          "invalid-request",
          "The request does not match the contract",
          "the only parameter is section, one letter from A to T",
          request.correlationId());
    }
    String successor = "/v1/events/1/seats?section=" + section;
    return new Reply.Body(OK, "application/json", catalog.version0SeatMap(section), List.of())
        .withHeader("Cache-Control", "no-cache")
        .withHeader("Deprecation", "@" + VERSION0_DEPRECATED_AT.getEpochSecond())
        .withHeader(
            "Sunset",
            DateTimeFormatter.RFC_1123_DATE_TIME.format(VERSION0_SUNSET_AT.atZone(ZoneOffset.UTC)))
        .withHeader("Link", "<" + successor + ">; rel=\"successor-version\"");
  }

  private Reply seatPage(Request request, String eventText) {
    Result<Map<String, String>, String> parameters =
        parameters(request.rawQuery(), Set.of("section", "limit", "cursor"));
    List<String> errors = new ArrayList<>();
    Map<String, String> values = Map.of();
    switch (parameters) {
      case Result.Success<Map<String, String>, String>(Map<String, String> parsed) ->
          values = parsed;
      case Result.Error<Map<String, String>, String>(String reason) -> errors.add(reason);
    }
    Optional<String> section = Optional.ofNullable(values.get("section"));
    if (section.isPresent() && !SeatCatalog.isSection(section.get())) {
      errors.add("section must be one letter from A to T");
    }
    String limitText = values.getOrDefault("limit", Integer.toString(DEFAULT_LIMIT));
    boolean limitValid =
        LIMIT.matcher(limitText).matches() && Integer.parseInt(limitText) <= MAX_LIMIT;
    if (!limitValid) {
      errors.add("limit must be from 1 to " + MAX_LIMIT);
    }
    Optional<SeatCatalog.SeatPosition> after = Optional.empty();
    if (values.containsKey("cursor")) {
      after =
          CURSOR.matcher(values.get("cursor")).matches()
              ? SeatCatalog.decode(values.get("cursor"))
              : Optional.empty();
      if (after.isEmpty()) {
        errors.add("cursor is not one this server wrote");
      }
    }
    if (!errors.isEmpty()) {
      return invalid(request, errors);
    }
    if (Long.parseLong(eventText) != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    SeatCatalog.Page page = catalog.page(section, Integer.parseInt(limitText), after);
    Object view =
        page.isLast()
            ? new LastSeatPage(page.items())
            : new SeatPage(page.items(), page.nextCursor());
    return Reply.json(OK, json.write(view)).withHeader("Cache-Control", "no-cache");
  }

  private Reply hold(Request request) {
    if (request.bodyTooLarge()) {
      return problems.of(
          TOO_LARGE,
          "body-too-large",
          "The body is over 1,024 bytes",
          "send at most " + MAX_BODY_BYTES + " bytes",
          request.correlationId());
    }
    boolean isJson =
        request
            .header("content-type")
            .map(type -> type.startsWith("application/json"))
            .orElse(false);
    if (!isJson) {
      return problems.of(
          UNSUPPORTED_MEDIA_TYPE,
          "unsupported-media-type",
          "The body must be application/json",
          "send Content-Type: application/json",
          request.correlationId());
    }
    Optional<String> key =
        request.header("idempotency-key").filter(value -> IDEMPOTENCY_KEY.matcher(value).matches());
    if (key.isEmpty()) {
      return invalid(
          request, List.of("Idempotency-Key must be one quoted string of 1 to 64 characters"));
    }
    String body = new String(request.body(), StandardCharsets.UTF_8);
    String fingerprint = fingerprint(body);
    return switch (keys.claim(key.get(), fingerprint)) {
      case HoldKeys.Claim.Owner owner -> holdOnce(request, key.get(), fingerprint, body);
      case HoldKeys.Claim.Replay(Reply.Body stored) -> stored;
      case HoldKeys.Claim.InProgress running ->
          problems.of(
              CONFLICT,
              "request-in-progress",
              "A request with this Idempotency-Key is running",
              "retry after it finishes",
              request.correlationId());
      case HoldKeys.Claim.Reused reused ->
          problems.of(
              UNPROCESSABLE,
              "idempotency-key-reused",
              "This Idempotency-Key was used with another body",
              "use a new key for a new request",
              request.correlationId());
      case HoldKeys.Claim.Full full ->
          problems
              .of(
                  UNAVAILABLE,
                  "service-busy",
                  "SeatWave is at its limit; retry after Retry-After",
                  "the key table is full",
                  request.correlationId())
              .withHeader("Retry-After", "1");
    };
  }

  private Reply.Body holdOnce(Request request, String key, String fingerprint, String body) {
    Reply.Body reply =
        switch (json.read(body, HoldRequestBody.class)) {
          case Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed) ->
              holdSeat(request, parsed);
          case Result.Error<HoldRequestBody, JsonCodec.BindingProblem>(
                  JsonCodec.BindingProblem problem) ->
              invalid(request, List.of(problem.pointer() + " " + problem.detail()));
        };
    // Final outcomes are stored, so a retry with the same key gets the same answer (Module 7).
    keys.complete(key, fingerprint, reply);
    return reply;
  }

  private Reply.Body holdSeat(Request request, HoldRequestBody body) {
    List<String> errors = new ArrayList<>();
    if (body.eventId() == null || body.eventId() < 1) {
      errors.add("/eventId must be 1 or more");
    }
    if (body.seat() == null || !SeatCatalog.isSeat(body.seat())) {
      errors.add("/seat must be a section letter A to T, a hyphen and a number 1 to 1000");
    }
    if (body.customer() == null || !CUSTOMER.matcher(body.customer()).matches()) {
      errors.add("/customer must be 1 to 64 letters, digits, - or _");
    }
    if (!errors.isEmpty()) {
      return invalid(request, errors);
    }
    if (body.eventId() != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    Optional<Hold> hold = ledger.hold(body.eventId(), body.seat(), body.customer());
    if (hold.isEmpty()) {
      return problems.of(
          CONFLICT,
          "seat-unavailable",
          "The seat is held or sold",
          "seat " + body.seat() + " is not available",
          request.correlationId());
    }
    // A business event: logged with the correlation id and the client the invariant allowed.
    LOG.info(
        "held {} for 300 s; client {}", body.seat(), request.client().address().getHostAddress());
    return Reply.json(CREATED, json.write(HoldView.of(hold.get(), ledger.now())))
        .withHeader("Location", "/v1/holds/" + hold.get().holdId())
        .withHeader("Cache-Control", "no-store");
  }

  private Reply readHold(Request request, String holdId) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("this path takes no query parameters"));
    }
    return ledger
        .find(holdId)
        .<Reply>map(hold ->
            Reply.json(OK, json.write(HoldView.of(hold, ledger.now())))
                // private: one fan's hold; a shared cache such as the proxy must never keep it,
                // even with a lifetime (RFC 9111, Section 5.2.2.7).
                .withHeader("Cache-Control", "private, max-age=60"))
        .orElseGet(() -> notFound(request));
  }

  private Reply longPoll(Request request, Matcher changes) {
    Optional<String> after =
        request.rawQuery().startsWith("after=")
            ? Optional.of(request.rawQuery().substring("after=".length()))
                .filter(value -> POSITION.matcher(value).matches())
            : Optional.empty();
    if (after.isEmpty()) {
      return invalid(request, List.of("after must be a position the feed sent"));
    }
    if (Long.parseLong(changes.group(1)) != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    try {
      // The lab's feed never changes, so every long poll waits its whole hold, as Module 8's does
      // when nothing happens; this is the long-lived request a proxy timeout must leave alone.
      Thread.sleep(timing.longPollHold());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return problems
          .of(
              UNAVAILABLE,
              "service-busy",
              "SeatWave is at its limit; retry after Retry-After",
              "the server is stopping",
              request.correlationId())
          .withHeader("Retry-After", "1");
    }
    return Reply.json(OK, json.write(new ChangeBatch(List.of(), after.get())))
        .withHeader("Cache-Control", "no-store");
  }

  private Reply stream(Request request, Matcher changes) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("the lab's stream takes no query parameters"));
    }
    if (Long.parseLong(changes.group(1)) != SeatCatalog.EVENT_ID) {
      return notFound(request);
    }
    return new Reply.EventStream(
        STREAM_RETRY,
        timing.heartbeat(),
        timing.streamLifetime(),
        List.of(Map.entry("Cache-Control", "no-store")));
  }

  private Reply staticFile(Request request) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("a static file takes no query parameters"));
    }
    return staticFiles
        .find(request.path())
        .<Reply>map(file ->
            new Reply.Body(OK, file.contentType(), file.bytes(), List.of())
                .withHeader("Cache-Control", "public, max-age=" + StaticFiles.MAX_AGE_SECONDS))
        .orElseGet(() -> notFound(request));
  }

  private Reply noQuery(Request request, Function<Request, Reply> route) {
    return request.rawQuery().isEmpty()
        ? route.apply(request)
        : invalid(request, List.of("this path takes no query parameters"));
  }

  private Reply.Body invalid(Request request, List<String> errors) {
    return problems.of(
        BAD_REQUEST,
        "invalid-request",
        "The request does not match the contract",
        String.join("; ", errors),
        request.correlationId());
  }

  private Reply.Body notFound(Request request) {
    return problems.of(
        NOT_FOUND, "not-found", "No such resource", "no such resource", request.correlationId());
  }

  private Reply notAllowed(Request request, String allowed) {
    return problems
        .of(
            METHOD_NOT_ALLOWED,
            "method-not-allowed",
            "This path does not take this method",
            "use " + allowed,
            request.correlationId())
        .withHeader("Allow", allowed);
  }

  private static Result<Map<String, String>, String> parameters(String query, Set<String> names) {
    Map<String, String> values = new LinkedHashMap<>();
    if (query.isEmpty()) {
      return Result.success(values);
    }
    for (String pair : query.split("&", -1)) {
      int equalsAt = pair.indexOf('=');
      String name = equalsAt < 0 ? pair : pair.substring(0, equalsAt);
      if (!names.contains(name)) {
        return Result.error("unknown parameter " + name + "; allowed: section, limit, cursor");
      }
      if (equalsAt < 0 || values.put(name, pair.substring(equalsAt + 1)) != null) {
        return Result.error("parameter " + name + " must appear once, with a value");
      }
    }
    return Result.success(values);
  }

  private static String fingerprint(String body) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException missing) {
      // Every Java platform must provide SHA-256, so this is a broken runtime, not bad input.
      throw new IllegalStateException("the JDK has no SHA-256", missing);
    }
  }

  /** The reply of one route, with the route template the metrics count it under. */
  record Routed(String route, Reply reply) {}

  record ClientDiagnostic(String address, String scheme, String source) {}

  record EventView(long eventId, String name, String startsAt) {}

  record EventPage(List<EventView> items) {}

  record SeatPage(List<SeatCatalog.SeatItem> items, String nextCursor) {}

  record LastSeatPage(List<SeatCatalog.SeatItem> items) {}

  record ChangeBatch(List<String> items, String position) {}

  /** The body of POST /v1/holds as sent; boxed, so a missing field reaches the validation step. */
  record HoldRequestBody(Long eventId, String seat, String customer) {}

  record HoldView(String holdId, long eventId, String seat, String state, String expiresAt) {
    static HoldView of(Hold hold, Instant now) {
      return new HoldView(
          hold.holdId(),
          hold.eventId(),
          hold.seat(),
          hold.isActiveAt(now) ? "held" : "expired",
          hold.expiresAt().toString());
    }
  }
}
