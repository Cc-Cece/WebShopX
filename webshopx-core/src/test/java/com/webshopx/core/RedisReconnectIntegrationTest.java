package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformPorts.PlatformEvent;
import com.webshopx.platform.PlatformResult;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Jedis;

/** Real-process failure injection for subscriber and pooled publisher reconnection. */
@EnabledIfSystemProperty(named = "webshopx.redis.reconnect.integration", matches = "true")
class RedisReconnectIntegrationTest {
  private static final CompatibilityDomain DOMAIN =
      new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:integration");

  @Test void reconnectsAfterRedisProcessIsKilledAndRestarted() throws Exception {
    int port = Integer.getInteger("webshopx.redis.reconnect.port", 26380);
    Process redis = startRedis(port);
    try {
      awaitPort(port, true, Duration.ofSeconds(10));
      CountDownLatch first = new CountDownLatch(1);
      CountDownLatch recovered = new CountDownLatch(1);
      try (RedisEventBridge bridge = new RedisEventBridge(
          "127.0.0.1", port, "", "webshopx:reconnect", event -> {
            if ("before".equals(event.id())) first.countDown();
            if ("after".equals(event.id())) recovered.countDown();
          })) {
        publishUntilObserved(bridge, event("before"), first, Duration.ofSeconds(5));
        stopRedis(port);
        assertTrue(redis.waitFor(10, TimeUnit.SECONDS), "Redis process did not stop");
        awaitPort(port, false, Duration.ofSeconds(10));
        assertInstanceOf(PlatformResult.UnknownOutcome.class, bridge.publish(event("outage")));

        redis = startRedis(port);
        awaitPort(port, true, Duration.ofSeconds(10));
        publishUntilObserved(bridge, event("after"), recovered, Duration.ofSeconds(15));
      }
    } finally {
      redis.destroyForcibly();
      redis.waitFor(10, TimeUnit.SECONDS);
    }
  }

  private static PlatformEvent event(String id) {
    return new PlatformEvent(id, "INTEGRATION", 1, "node-a", DOMAIN,
        System.currentTimeMillis(), "{\"id\":\"" + id + "\"}");
  }

  private static void publishUntilObserved(RedisEventBridge bridge, PlatformEvent event,
      CountDownLatch observed, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      bridge.publish(event);
      if (observed.await(100, TimeUnit.MILLISECONDS)) return;
    } while (System.nanoTime() < deadline);
    assertTrue(observed.await(100, TimeUnit.MILLISECONDS), "Event was not observed: " + event.id());
  }

  private static Process startRedis(int port) throws Exception {
    String configured = System.getProperty("webshopx.redis.command", "redis-server");
    List<String> command = new ArrayList<>(Arrays.asList(configured.split("\\|")));
    command.addAll(List.of("--port", Integer.toString(port), "--save", "", "--appendonly", "no"));
    return new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
  }

  private static void stopRedis(int port) {
    try (Jedis client = new Jedis("127.0.0.1", port)) {
      client.shutdown();
    } catch (RuntimeException expectedDisconnect) {
      // SHUTDOWN closes the connection before a response on supported Redis versions.
    }
  }

  private static void awaitPort(int port, boolean expectedOpen, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      boolean open;
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
        open = true;
      } catch (Exception unavailable) {
        open = false;
      }
      if (open == expectedOpen) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Redis port " + port + " expected open=" + expectedOpen);
  }
}
