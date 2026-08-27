package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.webshopx.core.RedisEventBridge;
import com.webshopx.core.WebShopXCoreRuntime;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.PlatformIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeHealthTest {
  @TempDir Path directory;

  @Test
  void writesSecretFreeRedisDiagnostics() throws Exception {
    var states = new EnumMap<CapabilitySnapshot.Capability, CapabilitySnapshot.CapabilityState>(
        CapabilitySnapshot.Capability.class);
    states.put(
        CapabilitySnapshot.Capability.REDIS,
        CapabilitySnapshot.CapabilityState.available("connected"));
    RuntimeHealth.write(
        directory,
        new PlatformIdentity("fabric", "fabric", "1.20.1", "test", "node-a", "hash"),
        new CapabilitySnapshot(Instant.now(), states),
        WebShopXCoreRuntime.State.READY,
        new RedisEventBridge.Diagnostics(true, false, 12, 2, 3, 1, 42, "JedisException"));

    String content = Files.readString(directory.resolve("health.json"));
    var redis = JsonParser.parseString(content).getAsJsonObject().getAsJsonObject("redis");
    assertFalse(redis.get("subscribed").getAsBoolean());
    assertEquals(2, redis.get("poisonEvents").getAsLong());
    assertEquals("JedisException", redis.get("lastFailureType").getAsString());
    assertTrue(content.contains("\"schemaVersion\": 1"));
    assertFalse(content.toLowerCase().contains("password"));
  }
}
