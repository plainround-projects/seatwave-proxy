package io.plainround.seatwave.proxy.backend;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Module 1's arena: one event, 20 sections (A to T) of 1,000 seats, and the bodies that describe
 * them, in version 0's and version 1's shapes. Thread-safe: it reads the ledger, which is.
 */
final class SeatCatalog {

  /** Module 1's one event. */
  static final long EVENT_ID = 1;

  // Module 1 seeded 20 sections of 1,000 seats, labelled A-1 to T-1000.
  static final int SEATS_PER_SECTION = 1_000;
  static final String SECTIONS = "ABCDEFGHIJKLMNOPQRST";
  // Module 1 printed this start time in its sample output; Module 7 writes it in ISO-8601.
  static final Instant EVENT_STARTS_AT = Instant.ofEpochMilli(1_796_761_251_783L);

  private static final Pattern SECTION = Pattern.compile("[A-T]");
  // A seat label: a section letter, a hyphen and a number from 1 to 1000.
  private static final Pattern SEAT = Pattern.compile("([A-T])-([1-9][0-9]{0,3})");
  private static final String CURSOR_PREFIX = "s1.";
  private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();

  private final HoldLedger ledger;

  SeatCatalog(HoldLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  static boolean isSection(String text) {
    return SECTION.matcher(text).matches();
  }

  static boolean isSeat(String label) {
    Matcher matcher = SEAT.matcher(label);
    return matcher.matches() && Integer.parseInt(matcher.group(2)) <= SEATS_PER_SECTION;
  }

  /**
   * Returns version 0's seat map of one section, byte for byte Module 1's shape: 36,894 bytes when
   * every seat is available.
   */
  byte[] version0SeatMap(String section) {
    List<String> labels = new ArrayList<>(SEATS_PER_SECTION);
    for (int seatNumber = 1; seatNumber <= SEATS_PER_SECTION; seatNumber++) {
      labels.add(section + "-" + seatNumber);
    }
    // Module 1's database returned seats ordered by their text label: A-1, A-10, A-100, A-1000.
    Collections.sort(labels);
    StringBuilder json = new StringBuilder("[");
    for (String label : labels) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("{\"seat\":\"")
          .append(label)
          .append("\",\"state\":\"")
          .append(state(label))
          .append("\"}");
    }
    return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Returns one page of version 1's seats, in Module 7's order: section, then seat number.
   *
   * @param section one section, or empty for all
   * @param limit 1 to 500 seats
   * @param after the last seat of the previous page, or empty for the first page
   */
  Page page(Optional<String> section, int limit, Optional<SeatPosition> after) {
    List<SeatItem> items = new ArrayList<>(limit);
    int startSection = after.map(SeatPosition::sectionIndex).orElse(0);
    int startNumber = after.map(position -> position.number() + 1).orElse(1);
    SeatPosition last = new SeatPosition(0, 0);
    boolean hasMore = false;
    for (int sectionIndex = startSection; sectionIndex < SECTIONS.length(); sectionIndex++) {
      String letter = String.valueOf(SECTIONS.charAt(sectionIndex));
      if (section.isPresent() && !section.get().equals(letter)) {
        continue;
      }
      int firstNumber = sectionIndex == startSection ? startNumber : 1;
      for (int number = firstNumber; number <= SEATS_PER_SECTION; number++) {
        if (items.size() == limit) {
          hasMore = true;
          break;
        }
        String label = letter + "-" + number;
        items.add(new SeatItem(label, letter, state(label)));
        last = new SeatPosition(sectionIndex, number);
      }
      if (hasMore) {
        break;
      }
    }
    return new Page(items, hasMore ? encode(last) : "");
  }

  static Optional<SeatPosition> decode(String cursor) {
    try {
      String text = new String(CURSOR_DECODER.decode(cursor), StandardCharsets.UTF_8);
      if (!text.startsWith(CURSOR_PREFIX)) {
        return Optional.empty();
      }
      String label = text.substring(CURSOR_PREFIX.length());
      if (!isSeat(label)) {
        return Optional.empty();
      }
      Matcher matcher = SEAT.matcher(label);
      matcher.matches();
      return Optional.of(
          new SeatPosition(SECTIONS.indexOf(matcher.group(1)), Integer.parseInt(matcher.group(2))));
    } catch (IllegalArgumentException notBase64) {
      // A cursor the client changed is bad input, not a server failure.
      return Optional.empty();
    }
  }

  private static String encode(SeatPosition position) {
    String label = SECTIONS.charAt(position.sectionIndex()) + "-" + position.number();
    return CURSOR_ENCODER.encodeToString((CURSOR_PREFIX + label).getBytes(StandardCharsets.UTF_8));
  }

  private String state(String label) {
    return ledger.isHeld(label) ? "held" : "available";
  }

  /** A seat's place in the order: its section's index (A is 0) and its number. */
  record SeatPosition(int sectionIndex, int number) {}

  /** One seat as version 1 writes it. */
  record SeatItem(String seat, String section, String state) {}

  /** One page and the cursor of the next, which is empty on the last page. */
  record Page(List<SeatItem> items, String nextCursor) {
    Page {
      items = List.copyOf(items);
      Objects.requireNonNull(nextCursor, "nextCursor");
    }

    boolean isLast() {
      return nextCursor.isEmpty();
    }
  }
}
