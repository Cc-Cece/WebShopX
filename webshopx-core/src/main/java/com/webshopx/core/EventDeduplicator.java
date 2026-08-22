package com.webshopx.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded process-local relay deduplication; durable consumers also persist event IDs. */
public final class EventDeduplicator {
  private final Clock clock;
  private final long retentionMillis;
  private final Map<String, Long> seen = new ConcurrentHashMap<>();

  public EventDeduplicator(Clock clock, Duration retention) {
    if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("retention");
    this.clock = clock;
    this.retentionMillis = retention.toMillis();
  }

  public boolean firstObservation(String eventId) {
    if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId");
    long now = clock.millis();
    seen.entrySet().removeIf(entry -> entry.getValue() <= now - retentionMillis);
    return seen.putIfAbsent(eventId, now) == null;
  }
}
