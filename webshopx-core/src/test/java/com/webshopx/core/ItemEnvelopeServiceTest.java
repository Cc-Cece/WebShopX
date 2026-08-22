package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ItemEnvelopeServiceTest {
  private static final CompatibilityDomain DOMAIN =
      new CompatibilityDomain("fabric", "fabric", "26.2", 1, "sha256:mods");

  @Test
  void roundTripsPayloadWithoutExposingMutableBytes() {
    ItemEnvelopeService service = new ItemEnvelopeService(
        Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC), Set.of("fabric-components"));
    byte[] payload = {1, 2, 3};
    ItemEnvelope envelope = service.create(
        "fabric-components", 1, DOMAIN, "minecraft:stone", 2, payload, Map.of("name", "Stone"));
    payload[0] = 99;
    assertArrayEquals(new byte[] {1, 2, 3}, envelope.payload());
    assertInstanceOf(PlatformResult.Success.class, service.validate(envelope, DOMAIN));
  }

  @Test
  void rejectsTamperingAndCrossDomainDelivery() {
    ItemEnvelopeService service = new ItemEnvelopeService(Clock.systemUTC(), Set.of("fabric-components"));
    ItemEnvelope valid = service.create(
        "fabric-components", 1, DOMAIN, "example:machine", 1, new byte[] {7}, Map.of());
    ItemEnvelope tampered = new ItemEnvelope(
        valid.schemaVersion(), valid.codec(), valid.codecVersion(), valid.compatibilityDomain(),
        valid.registryId(), valid.count(), valid.payloadEncoding(), new byte[] {8}, valid.payloadHash(),
        valid.summary(), valid.createdAt());
    assertInstanceOf(PlatformResult.Rejected.class, service.validate(tampered, DOMAIN));
    CompatibilityDomain other = new CompatibilityDomain("forge", "forge", "1.20.1", 1, "sha256:other");
    assertInstanceOf(PlatformResult.Rejected.class, service.validate(valid, other));
  }
}
