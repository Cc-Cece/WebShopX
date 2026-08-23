package com.webshopx.loader;

import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Server-thread-safe messaging adapter backed by tracked native ServerPlayer instances. */
final class NativeMessagingGateway implements PlatformPorts.MessagingGateway {
  private final NativePlayerDirectory players;
  private final LoaderScheduler scheduler;

  NativeMessagingGateway(NativePlayerDirectory players, LoaderScheduler scheduler) {
    this.players = players;
    this.scheduler = scheduler;
  }

  @Override public PlatformResult<Void> send(UUID playerId, PlatformPorts.Message message) {
    String text = render(message);
    try {
      boolean delivered;
      if (scheduler.isOnRequiredThread(PlatformPorts.ThreadScope.PLAYER, playerId)) {
        delivered = players.sendText(playerId, text);
      } else {
        var result = new java.util.concurrent.atomic.AtomicBoolean();
        scheduler.runForPlayer(playerId, () -> result.set(players.sendText(playerId, text)))
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        delivered = result.get();
      }
      return delivered ? PlatformResult.success(null) : new PlatformResult.Unavailable<>(
          "messaging", "player is not online on this server", Duration.ZERO);
    } catch (Exception failure) {
      return new PlatformResult.Unavailable<>(
          "messaging", "native message delivery failed: " + failure.getClass().getSimpleName(),
          Duration.ofSeconds(1));
    }
  }

  @Override public PlatformResult<Void> broadcast(PlatformPorts.Message message) {
    for (PlatformPorts.PlayerSnapshot player : players.onlinePlayers().toCompletableFuture().join()) {
      PlatformResult<Void> result = send(player.id(), message);
      if (!(result instanceof PlatformResult.Success<Void>)) return result;
    }
    return PlatformResult.success(null);
  }

  private static String render(PlatformPorts.Message message) {
    if (message.arguments().isEmpty()) return message.key();
    return message.key() + " " + String.join(" ", message.arguments());
  }
}
