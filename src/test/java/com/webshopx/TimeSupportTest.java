package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TimeSupportTest {

  @Test
  void businessLocalDateTimeShouldConvertToUtc() {
    LocalDateTime business = LocalDateTime.of(2026, 3, 20, 20, 0);
    LocalDateTime utc = TimeSupport.businessLocalToUtc(business, ZoneId.of("Asia/Shanghai"));

    assertEquals(LocalDateTime.of(2026, 3, 20, 12, 0), utc);
  }

  @Test
  void clientLocalDateTimeShouldBeParsedUsingBusinessTimeZone() {
    LocalDateTime utc = TimeSupport.parseClientDateTimeToUtc("2026-03-20T20:00", ZoneId.of("Asia/Shanghai"));

    assertEquals(LocalDateTime.of(2026, 3, 20, 12, 0), utc);
  }

  @Test
  void explicitOffsetShouldBePreservedWhenParsing() {
    LocalDateTime utc = TimeSupport.parseClientDateTimeToUtc("2026-03-20T20:00:00+08:00", ZoneId.of("UTC"));

    assertEquals(LocalDateTime.of(2026, 3, 20, 12, 0), utc);
  }

  @Test
  void utcDateTimeShouldFormatInBusinessTimeZone() {
    String formatted = TimeSupport.formatBusinessIsoOffset(
        LocalDateTime.of(2026, 3, 20, 12, 0),
        ZoneId.of("Asia/Shanghai"));

    assertEquals("2026-03-20T20:00+08:00", formatted);
  }
}
