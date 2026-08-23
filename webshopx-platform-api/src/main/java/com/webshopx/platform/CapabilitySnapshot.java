package com.webshopx.platform;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record CapabilitySnapshot(Instant capturedAt, Map<Capability, CapabilityState> states) {
  public CapabilitySnapshot {
    states = Collections.unmodifiableMap(new EnumMap<>(states));
  }

  public CapabilityState state(Capability capability) {
    return states.getOrDefault(capability, CapabilityState.unsupported("not declared"));
  }

  public enum Capability {
    ECONOMY, PERMISSION, OFFLINE_INVENTORY, STANDARD_GUI, CLIENT_ENHANCEMENT,
    MOD_ITEM_CODEC, DATABASE, HTTP_API, RELAY, REDIS, PAYMENT_PROVIDER
  }

  public record CapabilityState(Status status, String detail) {
    public static CapabilityState available(String detail) {
      return new CapabilityState(Status.AVAILABLE, detail);
    }

    public static CapabilityState unavailable(String detail) {
      return new CapabilityState(Status.UNAVAILABLE, detail);
    }

    public static CapabilityState unsupported(String detail) {
      return new CapabilityState(Status.UNSUPPORTED, detail);
    }
  }

  public enum Status { AVAILABLE, UNAVAILABLE, UNSUPPORTED }
}
