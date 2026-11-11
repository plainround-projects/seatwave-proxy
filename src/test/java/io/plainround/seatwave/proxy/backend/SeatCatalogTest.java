package io.plainround.seatwave.proxy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SeatCatalogTest {

  private final SeatCatalog catalog = new SeatCatalog(new HoldLedger(Clock.systemUTC()));

  @Test
  void acceptsOnlyTheArenasSeatLabels() {
    assertThat(SeatCatalog.isSeat("A-1")).isTrue();
    assertThat(SeatCatalog.isSeat("T-1000")).isTrue();
    assertThat(SeatCatalog.isSeat("A-1001")).isFalse();
    assertThat(SeatCatalog.isSeat("A-0")).isFalse();
    assertThat(SeatCatalog.isSeat("U-1")).isFalse();
    assertThat(SeatCatalog.isSeat("A-01")).isFalse();
  }

  @Test
  void refusesACursorThisServerDidNotWrite() {
    assertThat(SeatCatalog.decode("czEuQS0xMDAw")).contains(new SeatCatalog.SeatPosition(0, 1000));
    assertThat(SeatCatalog.decode("czIuQS0x")).isEmpty(); // s2.A-1: another prefix
    assertThat(SeatCatalog.decode("czEuQS0xMDAx")).isEmpty(); // s1.A-1001: no such seat
    assertThat(SeatCatalog.decode("!!!")).isEmpty();
  }

  @Test
  void writesTheLastPageWithoutANextCursor() {
    SeatCatalog.Page page =
        catalog.page(Optional.of("T"), 500, Optional.of(new SeatCatalog.SeatPosition(19, 500)));

    assertThat(page.items()).hasSize(500);
    assertThat(page.items().getLast().seat()).isEqualTo("T-1000");
    assertThat(page.isLast()).isTrue();
  }

  @Test
  void writesVersionZerosSeatMapInModuleOnesTextOrder() {
    String map = new String(catalog.version0SeatMap("A"), java.nio.charset.StandardCharsets.UTF_8);

    assertThat(map).hasSize(36_894);
    assertThat(map).startsWith("[{\"seat\":\"A-1\",\"state\":\"available\"},{\"seat\":\"A-10\"");
  }
}
