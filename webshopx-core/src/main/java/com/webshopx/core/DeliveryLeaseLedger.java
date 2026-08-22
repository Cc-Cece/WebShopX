package com.webshopx.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit delivery state machine preventing duplicate grants and unsafe retry after ambiguity. */
public final class DeliveryLeaseLedger {
  private final Clock clock;
  private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();

  public DeliveryLeaseLedger(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

  public Delivery create(String operationId) {
    Delivery created = new Delivery(operationId, State.PENDING, null, 0, null);
    Delivery existing = deliveries.putIfAbsent(operationId, created);
    return existing == null ? created : existing;
  }

  public synchronized Delivery lease(String operationId, String serverId, Duration duration) {
    Delivery current = require(operationId);
    long now = clock.millis();
    if (current.state == State.DELIVERED || current.state == State.UNKNOWN
        || (current.state == State.LEASED && current.leaseUntil > now)) return current;
    Delivery leased = new Delivery(operationId, State.LEASED, serverId,
        Math.addExact(now, duration.toMillis()), null);
    deliveries.put(operationId, leased);
    return leased;
  }

  public synchronized Delivery delivered(String operationId, String serverId, String receipt) {
    Delivery current = require(operationId);
    if (current.state == State.DELIVERED) return current;
    if (current.state != State.LEASED || !Objects.equals(current.serverId, serverId)) {
      throw new IllegalStateException("delivery lease is not owned by " + serverId);
    }
    Delivery delivered = new Delivery(operationId, State.DELIVERED, serverId, 0, receipt);
    deliveries.put(operationId, delivered);
    return delivered;
  }

  public synchronized Delivery unknown(String operationId, String serverId) {
    Delivery current = require(operationId);
    if (current.state != State.LEASED || !Objects.equals(current.serverId, serverId)) {
      throw new IllegalStateException("delivery lease is not owned by " + serverId);
    }
    Delivery unknown = new Delivery(operationId, State.UNKNOWN, serverId, 0, null);
    deliveries.put(operationId, unknown);
    return unknown;
  }

  private Delivery require(String operationId) {
    Delivery value = deliveries.get(operationId);
    if (value == null) throw new IllegalArgumentException("unknown operationId");
    return value;
  }

  public enum State { PENDING, LEASED, DELIVERED, UNKNOWN }
  public record Delivery(String operationId, State state, String serverId,
                         long leaseUntil, String receipt) {
    public Delivery {
      if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId");
    }
  }
}
