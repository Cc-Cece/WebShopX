package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

class BroadcastService {
  private static final String TEMPLATE_PREFIX = "webshop.broadcast.templates.";

  private final JavaPlugin plugin;
  private final Supplier<PluginSettings> settingsSupplier;
  private final Gson gson;
  private final String instanceId;

  private final Object bridgeLock = new Object();
  private RedisBridge redisBridge;

  BroadcastService(JavaPlugin plugin, Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.settingsSupplier = settingsSupplier;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
    this.instanceId = UUID.randomUUID().toString();
  }

  void reload() {
    synchronized (bridgeLock) {
      if (redisBridge != null) {
        redisBridge.close();
        redisBridge = null;
      }
      if (!settingsSupplier.get().redisSettings().enabled()) {
        return;
      }
      String host = plugin.getConfig().getString("redis.host", "127.0.0.1");
      int port = plugin.getConfig().getInt("redis.port", 6379);
      String password = plugin.getConfig().getString("redis.password", "");
      String channel = plugin.getConfig().getString("redis.channel", "webshopx:market:broadcast");
      if (channel == null || channel.isBlank()) {
        plugin.getLogger().warning("Redis channel is empty, skip cross-server broadcast.");
        return;
      }
      try {
        redisBridge = new RedisBridge(host, port, password, channel, instanceId);
        plugin.getLogger().info("Market broadcast Redis bridge is enabled on " + host + ":" + port);
      } catch (Exception exception) {
        plugin.getLogger().warning("Failed to start Redis broadcast bridge: " + exception.getMessage());
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

  void broadcastTemplate(String templateKey, Map<String, ?> placeholders) {
    if (!isBroadcastEnabled()) {
      return;
    }
    String template = plugin.getConfig().getString(TEMPLATE_PREFIX + templateKey, "");
    if (template == null || template.isBlank()) {
      return;
    }
    String message = applyTemplate(template, placeholders);
    publishMessage(message);
  }

  void publishMessage(String rawMessage) {
    if (!isBroadcastEnabled()) {
      return;
    }
    String normalized = normalizeMessage(rawMessage);
    if (normalized.isBlank()) {
      return;
    }
    broadcastLocal(normalized);
    synchronized (bridgeLock) {
      if (redisBridge != null) {
        redisBridge.publish(normalized);
      }
    }
  }

  private boolean isBroadcastEnabled() {
    return plugin.getConfig().getBoolean("webshop.broadcast.enabled", true);
  }

  private String applyTemplate(String template, Map<String, ?> placeholders) {
    String rendered = template;
    if (placeholders != null && !placeholders.isEmpty()) {
      for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
        String token = "{" + entry.getKey() + "}";
        String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
        rendered = rendered.replace(token, value);
      }
    }
    return rendered;
  }

  private String normalizeMessage(String rawMessage) {
    if (rawMessage == null) {
      return "";
    }
    String text = rawMessage.trim();
    if (text.isBlank()) {
      return "";
    }
    return text.replace('&', '\u00a7');
  }

  private void broadcastLocal(String message) {
    Runnable job = () -> {
      for (Player player : Bukkit.getOnlinePlayers()) {
        player.sendMessage(message);
      }
    };
    if (Bukkit.isPrimaryThread()) {
      job.run();
    } else {
      Bukkit.getScheduler().runTask(plugin, job);
    }
  }

  private final class RedisBridge {
    private final String channel;
    private final String sourceId;
    private final AtomicBoolean running;
    private final DefaultJedisClientConfig clientConfig;

    private JedisPooled publisher;
    private JedisPubSub subscriber;
    private Thread subscriberThread;

    private RedisBridge(
        String host,
        int port,
        String password,
        String channel,
        String sourceId) {
      this.channel = channel;
      this.sourceId = sourceId;
      this.running = new AtomicBoolean(true);

      DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder();
      if (password != null && !password.isBlank()) {
        configBuilder.password(password);
      }
      this.clientConfig = configBuilder.build();

        this.publisher =
            new JedisPooled(
            new GenericObjectPoolConfig<Connection>(), new HostAndPort(host, port), clientConfig);
      this.subscriberThread = new Thread(() -> runSubscriber(host, port), "webshopx-redis-sub");
      this.subscriberThread.setDaemon(true);
      this.subscriberThread.start();
    }

    private void runSubscriber(String host, int port) {
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        try (Jedis jedis = new Jedis(host, port, clientConfig)) {
          subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String incomingChannel, String payload) {
              if (!channel.equals(incomingChannel) || payload == null || payload.isBlank()) {
                return;
              }
              try {
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                String from = json.has("source") && !json.get("source").isJsonNull()
                    ? json.get("source").getAsString()
                    : "";
                if (sourceId.equals(from)) {
                  return;
                }
                String message = json.has("message") && !json.get("message").isJsonNull()
                    ? json.get("message").getAsString()
                    : "";
                String normalized = normalizeMessage(message);
                if (!normalized.isBlank()) {
                  broadcastLocal(normalized);
                }
              } catch (Exception exception) {
                plugin.getLogger().warning("Failed to parse Redis broadcast payload: " + exception.getMessage());
              }
            }
          };
          jedis.subscribe(subscriber, channel);
        } catch (Exception exception) {
          if (!running.get()) {
            return;
          }
          plugin.getLogger().warning("Redis subscriber disconnected: " + exception.getMessage());
          try {
            Thread.sleep(2000L);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }

    private void publish(String message) {
      if (!running.get() || publisher == null) {
        return;
      }
      try {
        JsonObject payload = new JsonObject();
        payload.addProperty("source", sourceId);
        payload.addProperty("message", message);
        publisher.publish(channel, gson.toJson(payload));
      } catch (Exception exception) {
        plugin.getLogger().warning("Failed to publish Redis broadcast: " + exception.getMessage());
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
}
