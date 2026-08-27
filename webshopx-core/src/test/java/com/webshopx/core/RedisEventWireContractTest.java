package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformPorts.PlatformEvent;
import org.junit.jupiter.api.Test;

class RedisEventWireContractTest {
  @Test
  void rejectsNullMalformedAndOversizedWireEventsBeforeAdmission() {
    assertThrows(IllegalArgumentException.class, () -> RedisEventBridge.decodeWirePayload(null));
    assertThrows(IllegalArgumentException.class, () -> RedisEventBridge.decodeWirePayload("null"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RedisEventBridge.decodeWirePayload("{" + "x".repeat(400_000) + "}"));
  }

  @Test
  void decodesTheBoundedVersionedEnvelope() {
    PlatformEvent source = new PlatformEvent(
        "event-a", "CONFIG_REFRESH", 1, "paper-a",
        new CompatibilityDomain("paper", "paper", "1.20.1", 1, "paper:test"),
        100, "{\"version\":2}");
    assertEquals(source, RedisEventBridge.decodeWirePayload(RedisEventBridge.encodeWirePayload(source)));
  }
}
