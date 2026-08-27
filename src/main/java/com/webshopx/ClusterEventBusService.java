package com.webshopx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webshopx.core.RedisEventBridge;
import com.webshopx.core.JdbcEventInbox;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformPorts.PlatformEvent;
import com.webshopx.platform.PlatformResult;
import java.util.UUID;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper adapter for the shared, versioned Redis platform-event contract. */
class ClusterEventBusService {
  private static final String EVENT_TYPE_CONFIG_REFRESH = "CONFIG_REFRESH";
  private static final int EVENT_SCHEMA_VERSION = 1;

  private final JavaPlugin plugin;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ConfigRefreshListener configRefreshListener;
  private final JdbcEventInbox eventInbox;
  private final AtomicLong highestConfigVersion = new AtomicLong();
  private final Object bridgeLock = new Object();
  private RedisEventBridge redisBridge;

  ClusterEventBusService(
      JavaPlugin plugin,
      DatabaseManager database,
      Supplier<PluginSettings> settingsSupplier,
      ConfigRefreshListener configRefreshListener) {
    this.plugin = plugin;
    this.settingsSupplier = settingsSupplier;
    this.configRefreshListener = configRefreshListener;
    this.eventInbox = new JdbcEventInbox(database::getConnection, Clock.systemUTC());
    try {
      eventInbox.initialize();
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot initialize durable cluster event inbox", failure);
    }
  }

  void reload() {
    synchronized (bridgeLock) {
      closeBridge();
      PluginSettings settings = settingsSupplier.get();
      PluginSettings.RedisSettings redis = settings.redisSettings();
      if (!redis.enabled()) return;
      String channel = redis.clusterChannel();
      MessageService messages = new MessageService(plugin, settingsSupplier);
      if (channel == null || channel.isBlank()) {
        plugin.getLogger().warning(messages.getConsole("console.cluster_channel_empty"));
        return;
      }
      try {
        redisBridge = new RedisEventBridge(
            redis.host(), redis.port(), redis.password(), channel, this::handleIncoming);
        plugin.getLogger().info(messages.format(
            settings.defaultLocale(), "console.cluster_event_bus_enabled",
            MapUtils.mapOf("channel", channel)));
      } catch (RuntimeException failure) {
        plugin.getLogger().warning(messages.format(
            settings.defaultLocale(), "console.failed_publish_cluster_refresh",
            MapUtils.mapOf("reason", failure.getClass().getSimpleName())));
      }
    }
  }

  void shutdown() {
    synchronized (bridgeLock) {
      closeBridge();
    }
  }

  void publishConfigRefresh(long version) {
    synchronized (bridgeLock) {
      if (redisBridge == null) return;
      PluginSettings settings = settingsSupplier.get();
      JsonObject payload = new JsonObject();
      long normalizedVersion = Math.max(0L, version);
      highestConfigVersion.accumulateAndGet(normalizedVersion, Math::max);
      payload.addProperty("version", normalizedVersion);
      PlatformEvent event = new PlatformEvent(
          UUID.randomUUID().toString(),
          EVENT_TYPE_CONFIG_REFRESH,
          EVENT_SCHEMA_VERSION,
          settings.clusterSettings().serverId(),
          compatibilityDomain(),
          System.currentTimeMillis(),
          payload.toString());
      PlatformResult<Void> result = redisBridge.publish(event);
      if (!(result instanceof PlatformResult.Success<Void>)) {
        MessageService messages = new MessageService(plugin, settingsSupplier);
        plugin.getLogger().warning(messages.format(
            settings.defaultLocale(), "console.failed_publish_cluster_refresh",
            MapUtils.mapOf("reason", result.getClass().getSimpleName())));
      }
    }
  }

  RedisEventBridge.Diagnostics diagnostics() {
    synchronized (bridgeLock) {
      return redisBridge == null ? null : redisBridge.diagnostics();
    }
  }

  private void handleIncoming(PlatformEvent event) {
    PluginSettings settings = settingsSupplier.get();
    if (settings.clusterSettings().serverId().equals(event.serverId())) return;
    if (event.schemaVersion() != EVENT_SCHEMA_VERSION
        || !EVENT_TYPE_CONFIG_REFRESH.equals(event.type())) return;
    try {
      if (!eventInbox.admit(event)) return;
      JsonObject payload = JsonParser.parseString(event.payloadJson()).getAsJsonObject();
      long version = payload.has("version") && !payload.get("version").isJsonNull()
          ? payload.get("version").getAsLong() : 0L;
      long previous = highestConfigVersion.getAndAccumulate(version, Math::max);
      if (version <= previous) return;
      configRefreshListener.onConfigRefresh(event.serverId(), version);
    } catch (SQLException databaseFailure) {
      reportPoison(databaseFailure);
      throw new IllegalStateException("Cannot admit cluster event", databaseFailure);
    } catch (RuntimeException poison) {
      reportPoison(poison);
      throw poison;
    }
  }

  private void reportPoison(Exception poison) {
    PluginSettings settings = settingsSupplier.get();
    MessageService messages = new MessageService(plugin, settingsSupplier);
    plugin.getLogger().warning(messages.format(
        settings.defaultLocale(), "console.invalid_cluster_event_payload",
        MapUtils.mapOf("reason", poison.getClass().getSimpleName())));
  }

  private CompatibilityDomain compatibilityDomain() {
    String minecraft;
    try {
      minecraft = Bukkit.getMinecraftVersion();
    } catch (NoSuchMethodError unsupported) {
      minecraft = Bukkit.getBukkitVersion();
    }
    return new CompatibilityDomain(
        "paper", "paper", minecraft, 1, "paper:" + plugin.getDescription().getVersion());
  }

  private void closeBridge() {
    if (redisBridge == null) return;
    redisBridge.close();
    redisBridge = null;
  }

  @FunctionalInterface
  interface ConfigRefreshListener {
    void onConfigRefresh(String sourceServerId, long version);
  }
}
