package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.core.CompatibilityRouter.Node;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClusterSafetyTest {
  private static final CompatibilityDomain DOMAIN =
      new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:one");

  @Test void routesOnlyToExactNativeDomain() {
    byte[] payload = "item".getBytes(StandardCharsets.UTF_8);
    ItemEnvelope envelope = new ItemEnvelope(1, "fabric-nbt", 1, DOMAIN, "minecraft:stone", 1,
        ItemEnvelope.PayloadEncoding.RAW, payload, ItemEnvelopeService.sha256(payload), Map.of(), Instant.EPOCH);
    CompatibilityDomain foreign = new CompatibilityDomain("forge", "forge", "1.20.1", 1, "sha256:one");
    PlatformResult<Node> result = new CompatibilityRouter().route(envelope, List.of(
        new Node("foreign", foreign, true, 0), new Node("busy", DOMAIN, true, 5),
        new Node("ready", DOMAIN, true, 1)));
    assertInstanceOf(PlatformResult.Success.class, result);
    Node selected = ((PlatformResult.Success<Node>) result).value();
    assertEquals("ready", selected.serverId());
  }

  @Test void ambiguousDeliveryCannotBeAutomaticallyReLeased() {
    DeliveryLeaseLedger ledger = new DeliveryLeaseLedger(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    ledger.create("op-1");
    ledger.lease("op-1", "node-a", Duration.ofSeconds(30));
    assertEquals(DeliveryLeaseLedger.State.UNKNOWN, ledger.unknown("op-1", "node-a").state());
    assertEquals(DeliveryLeaseLedger.State.UNKNOWN,
        ledger.lease("op-1", "node-b", Duration.ofSeconds(30)).state());
    assertThrows(IllegalStateException.class, () -> ledger.delivered("op-1", "node-b", "receipt"));
  }

  @Test void relayEventsAreDeduplicated() {
    EventDeduplicator dedupe = new EventDeduplicator(
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMinutes(10));
    assertTrue(dedupe.firstObservation("evt-1"));
    assertFalse(dedupe.firstObservation("evt-1"));
  }
}
