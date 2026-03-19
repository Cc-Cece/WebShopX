package com.webshopx;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

final class TimeSupport {
  private TimeSupport() {
  }

  static LocalDateTime utcNow() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  static LocalDateTime businessLocalToUtc(LocalDateTime businessDateTime, ZoneId businessZone) {
    if (businessDateTime == null) {
      return null;
    }
    return businessDateTime.atZone(businessZone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  static OffsetDateTime utcToBusinessOffset(LocalDateTime utcDateTime, ZoneId businessZone) {
    if (utcDateTime == null) {
      return null;
    }
    return utcDateTime.atZone(ZoneOffset.UTC).withZoneSameInstant(businessZone).toOffsetDateTime();
  }

  static String formatBusinessIsoOffset(LocalDateTime utcDateTime, ZoneId businessZone) {
    OffsetDateTime businessDateTime = utcToBusinessOffset(utcDateTime, businessZone);
    return businessDateTime == null ? null : businessDateTime.toString();
  }

  static LocalDateTime parseClientDateTimeToUtc(String raw, ZoneId businessZone) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(raw.trim()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    } catch (DateTimeParseException ignored) {
      return businessLocalToUtc(LocalDateTime.parse(raw.trim()), businessZone);
    }
  }
}
