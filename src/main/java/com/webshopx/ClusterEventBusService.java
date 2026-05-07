package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

class ClusterEventBusService {
  private static final String EVENT_TYPE_CONFIG_REFRESH = "CONFIG_REFRESH";

  private final JavaPlugin plugin;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ConfigRefreshListener configRefreshListener;
  private final Gson gson;

  private final Object bridgeLock = new Object();
  private RedisBridge redisBridge;

  ClusterEventBusService(
      JavaPlugin plugin,
      Supplier<PluginSettings> settingsSupplier,
      ConfigRefreshListener configRefreshListener) {
    this.plugin = plugin;
    this.settingsSupplier = settingsSupplier;
    this.configRefreshListener = configRefreshListener;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  void reload() {
    synchronized (bridgeLock) {
      if (redisBridge != null) {
        redisBridge.close();
        redisBridge = null;
      }
      PluginSettings settings = settingsSupplier.get();
      PluginSettings.RedisSettings redisSettings = settings.redisSettings();
      if (!redisSettings.enabled()) {
        return;
      }
      String channel = redisSettings.clusterChannel();
      MessageService ms = new MessageService(plugin, settingsSupplier);
      if (channel == null || channel.isBlank()) {
        plugin.getLogger().warning(ms.getConsole("console.cluster_channel_empty"));
        return;
      }
      String serverId = settings.clusterSettings().serverId();
      try {
        redisBridge = new RedisBridge(
            redisSettings.host(),
            redisSettings.port(),
            redisSettings.password(),
            channel,
            serverId);
        plugin.getLogger().info(ms.format(settings.defaultLocale(), "console.cluster_event_bus_enabled", MapUtils.mapOf("channel", channel)));
      } catch (Exception exception) {
        plugin.getLogger().warning(ms.format(settings.defaultLocale(), "console.failed_publish_cluster_refresh", MapUtils.mapOf("reason", exception.getMessage())));
      }
    }
  }

  void shutdown() {
    synchronized (bridgeLock) {
      if (redisBridge != null) {
        redisBridge.close();
        redisBridge = null;
      }
    }
  }

  void publishConfigRefresh(long version) {
    synchronized (bridgeLock) {
      if (redisBridge == null) {
        return;
      }
      redisBridge.publishConfigRefresh(version);
    }
  }

  private final class RedisBridge {
    private final String host;
    private final int port;
    private final String channel;
    private final String sourceServerId;
    private final DefaultJedisClientConfig clientConfig;
    private final AtomicBoolean running;

    private JedisPooled publisher;
    private JedisPubSub subscriber;
    private Thread subscriberThread;

    private RedisBridge(
        String host,
        int port,
        String password,
        String channel,
        String sourceServerId) {
      this.host = host;
      this.port = port;
      this.channel = channel;
      this.sourceServerId = sourceServerId;
      this.running = new AtomicBoolean(true);

      DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder();
      if (password != null && !password.isBlank()) {
        configBuilder.password(password);
      }
      this.clientConfig = configBuilder.build();

      this.publisher =
          new JedisPooled(
              new GenericObjectPoolConfig<Connection>(),
              new HostAndPort(host, port),
              clientConfig);

      this.subscriberThread = new Thread(this::runSubscriber, "webshopx-cluster-redis-sub");
      this.subscriberThread.setDaemon(true);
      this.subscriberThread.start();
    }

    private void runSubscriber() {
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        try (Jedis jedis = new Jedis(host, port, clientConfig)) {
          subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String incomingChannel, String payload) {
              if (!channel.equals(incomingChannel) || payload == null || payload.isBlank()) {
                return;
              }
              handleIncoming(payload);
            }
          };
          jedis.subscribe(subscriber, channel);
          } catch (Exception exception) {
          if (!running.get()) {
            return;
          }
          MessageService ms = new MessageService(plugin, settingsSupplier);
          plugin.getLogger().warning(ms.format(settingsSupplier.get().defaultLocale(), "console.cluster_subscriber_disconnected", MapUtils.mapOf("reason", exception.getMessage())));
          try {
            Thread.sleep(2000L);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }

    private void handleIncoming(String payload) {
      try {
        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        String source = root.has("source") && !root.get("source").isJsonNull()
            ? root.get("source").getAsString()
            : "";
        if (sourceServerId != null && !sourceServerId.isBlank() && sourceServerId.equals(source)) {
          return;
        }
        String type = root.has("type") && !root.get("type").isJsonNull()
            ? root.get("type").getAsString()
            : "";
        if (!EVENT_TYPE_CONFIG_REFRESH.equalsIgnoreCase(type)) {
          return;
        }
        long version = root.has("version") && !root.get("version").isJsonNull()
            ? root.get("version").getAsLong()
            : 0L;
        configRefreshListener.onConfigRefresh(source, version);
      } catch (Exception exception) {
        MessageService ms = new MessageService(plugin, settingsSupplier);
        plugin.getLogger().warning(ms.format(settingsSupplier.get().defaultLocale(), "console.invalid_cluster_event_payload", MapUtils.mapOf("reason", exception.getMessage())));
      }
    }

    private void publishConfigRefresh(long version) {
      if (!running.get() || publisher == null) {
        return;
      }
      try {
        JsonObject payload = new JsonObject();
        payload.addProperty("source", sourceServerId);
        payload.addProperty("type", EVENT_TYPE_CONFIG_REFRESH);
        payload.addProperty("version", Math.max(0L, version));
        publisher.publish(channel, gson.toJson(payload));
      } catch (Exception exception) {
        MessageService ms = new MessageService(plugin, settingsSupplier);
        plugin.getLogger().warning(ms.format(settingsSupplier.get().defaultLocale(), "console.failed_publish_cluster_refresh", MapUtils.mapOf("reason", exception.getMessage())));
      }
    }

    private void close() {
      running.set(false);
      try {
        if (subscriber != null) {
          subscriber.unsubscribe();
        }
      } catch (Exception ignored) {
      }
      if (subscriberThread != null) {
        subscriberThread.interrupt();
        try {
          subscriberThread.join(1500L);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
      if (publisher != null) {
        try {
          publisher.close();
        } catch (Exception ignored) {
        }
        publisher = null;
      }
      subscriber = null;
      subscriberThread = null;
    }
  }

  @FunctionalInterface
  interface ConfigRefreshListener {
    void onConfigRefresh(String sourceServerId, long version);
  }
}
