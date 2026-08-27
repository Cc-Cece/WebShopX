package com.webshopx.core;

import com.google.gson.Gson;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

/** Reconnecting Redis pub/sub transport for the versioned platform event contract. */
public final class RedisEventBridge implements PlatformPorts.EventPublisher, AutoCloseable {
  private static final int MAX_WIRE_LENGTH = PlatformPorts.PlatformEvent.MAX_PAYLOAD_LENGTH
      + 128 * 1024;
  private static final Gson WIRE_GSON = new Gson();
  private final String channel;
  private final Consumer<PlatformPorts.PlatformEvent> consumer;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong receivedEvents = new AtomicLong();
  private final AtomicLong poisonEvents = new AtomicLong();
  private final AtomicLong reconnects = new AtomicLong();
  private final AtomicLong unknownPublishes = new AtomicLong();
  private final HostAndPort endpoint;
  private final DefaultJedisClientConfig clientConfig;
  private final JedisPooled publisher;
  private final Thread subscriberThread;
  private volatile JedisPubSub subscription;
  private volatile long lastFailureEpochMillis;
  private volatile String lastFailureType = "";

  public RedisEventBridge(String host, int port, String password, String channel,
      Consumer<PlatformPorts.PlatformEvent> consumer) {
    if (host == null || host.isBlank() || port < 1 || port > 65_535
        || channel == null || channel.isBlank()) throw new IllegalArgumentException("Redis endpoint");
    this.channel = channel;
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    endpoint = new HostAndPort(host, port);
    DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(2_000).socketTimeoutMillis(2_000);
    if (password != null && !password.isBlank()) builder.password(password);
    clientConfig = builder.build();
    publisher = new JedisPooled(endpoint, clientConfig);
    publisher.ping();
    subscriberThread = new Thread(this::subscribe, "webshopx-redis-events");
    subscriberThread.setDaemon(true);
    subscriberThread.start();
  }

  @Override
  public PlatformResult<Void> publish(PlatformPorts.PlatformEvent event) {
    if (!running.get()) return new PlatformResult.Unavailable<>(
        "redis", "Redis event bridge is stopped", Duration.ZERO);
    try {
      publisher.publish(channel, encodeWirePayload(event));
      return PlatformResult.success(null);
    } catch (RuntimeException failure) {
      unknownPublishes.incrementAndGet();
      recordFailure(failure);
      return new PlatformResult.UnknownOutcome<>(event.id(), true);
    }
  }

  private void subscribe() {
    long retryMillis = 100;
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try (Jedis jedis = new Jedis(endpoint, clientConfig)) {
        JedisPubSub current = new JedisPubSub() {
          @Override public void onMessage(String incoming, String payload) {
            if (!channel.equals(incoming) || payload == null) return;
            receivedEvents.incrementAndGet();
            try {
              consumer.accept(decodeWirePayload(payload));
            } catch (RuntimeException poison) {
              poisonEvents.incrementAndGet();
              recordFailure(poison);
              // Poison events are isolated; diagnostics expose their occurrence without payloads.
            }
          }
        };
        subscription = current;
        retryMillis = 100;
        jedis.subscribe(current, channel);
      } catch (RuntimeException failure) {
        if (!running.get()) break;
        reconnects.incrementAndGet();
        recordFailure(failure);
        try {
          Thread.sleep(retryMillis);
          retryMillis = Math.min(5_000, retryMillis * 2);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      } finally {
        subscription = null;
      }
    }
  }

  /** Secret-free operational counters suitable for health and structured diagnostics. */
  public Diagnostics diagnostics() {
    return new Diagnostics(
        running.get(),
        subscription != null,
        receivedEvents.get(),
        poisonEvents.get(),
        reconnects.get(),
        unknownPublishes.get(),
        lastFailureEpochMillis,
        lastFailureType);
  }

  private void recordFailure(RuntimeException failure) {
    lastFailureEpochMillis = System.currentTimeMillis();
    lastFailureType = failure.getClass().getSimpleName();
  }

  static PlatformPorts.PlatformEvent decodeWirePayload(String payload) {
    if (payload == null || payload.length() > MAX_WIRE_LENGTH) {
      throw new IllegalArgumentException("Redis event envelope is oversized or missing");
    }
    WireEvent decoded = WIRE_GSON.fromJson(payload, WireEvent.class);
    if (decoded == null) throw new IllegalArgumentException("Redis event envelope is null");
    return decoded.toPlatformEvent();
  }

  static String encodeWirePayload(PlatformPorts.PlatformEvent event) {
    return WIRE_GSON.toJson(WireEvent.from(Objects.requireNonNull(event, "event")));
  }

  @Override public void close() {
    if (!running.compareAndSet(true, false)) return;
    JedisPubSub current = subscription;
    if (current != null) {
      try { current.unsubscribe(); } catch (RuntimeException ignored) { }
    }
    publisher.close();
    subscriberThread.interrupt();
    try {
      subscriberThread.join(2_000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private record WireEvent(String id, String type, int schemaVersion, String serverId,
                           String platform, String loader, String minecraftVersion,
                           int itemCodecVersion, String modpackFingerprint,
                           long occurredAtEpochMillis, String payloadJson) {
    static WireEvent from(PlatformPorts.PlatformEvent event) {
      CompatibilityDomain domain = event.domain();
      return new WireEvent(event.id(), event.type(), event.schemaVersion(), event.serverId(),
          domain.platform(), domain.loader(), domain.minecraftVersion(), domain.itemCodecVersion(),
          domain.modpackFingerprint(), event.occurredAtEpochMillis(), event.payloadJson());
    }
    PlatformPorts.PlatformEvent toPlatformEvent() {
      return new PlatformPorts.PlatformEvent(id, type, schemaVersion, serverId,
          new CompatibilityDomain(platform, loader, minecraftVersion, itemCodecVersion,
              modpackFingerprint), occurredAtEpochMillis, payloadJson);
    }
  }

  public record Diagnostics(
      boolean running,
      boolean subscribed,
      long receivedEvents,
      long poisonEvents,
      long reconnects,
      long unknownPublishes,
      long lastFailureEpochMillis,
      String lastFailureType) { }
}
