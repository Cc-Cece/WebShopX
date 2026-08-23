package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformPorts.PlatformEvent;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "webshopx.redis.integration", matches = "true")
class RedisEventBridgeIntegrationTest {
  @Test void publishesVersionedDomainEventBetweenNodesAndIgnoresPoisonMessages() throws Exception {
    int port = Integer.getInteger("webshopx.redis.port", 26379);
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<PlatformEvent> observed = new AtomicReference<>();
    try (RedisEventBridge subscriber = new RedisEventBridge(
             "127.0.0.1", port, "", "webshopx:integration", event -> {
               observed.set(event);
               received.countDown();
             });
         RedisEventBridge publisher = new RedisEventBridge(
             "127.0.0.1", port, "", "webshopx:integration", ignored -> { })) {
      PlatformEvent event = new PlatformEvent("event-1", "ORDER_CREATED", 3, "forge-a",
          new CompatibilityDomain("forge", "forge", "1.20.1", 1, "sha256:mods"),
          1_777_000_000_000L, "{\"order\":1}");
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      do {
        publisher.publish(event);
        if (received.await(100, TimeUnit.MILLISECONDS)) break;
      } while (System.nanoTime() < deadline);
      assertTrue(received.await(1, TimeUnit.SECONDS));
      assertEquals(event, observed.get());
    }
  }
}
