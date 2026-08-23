package com.webshopx.loader;

import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Vanilla operator-level permission bridge; third-party permission nodes remain capability-gated. */
final class NativePermissionProvider implements PlatformPorts.PermissionProvider {
  private final NativePlayerDirectory players;
  private final LoaderScheduler scheduler;

  NativePermissionProvider(NativePlayerDirectory players, LoaderScheduler scheduler) {
    this.players = players;
    this.scheduler = scheduler;
  }

  @Override
  public CompletionStage<PlatformResult<Boolean>> hasPermission(
      UUID playerId, String permission, String context) {
    if (permission == null || permission.isBlank()) {
      return CompletableFuture.completedFuture(PlatformResult.success(true));
    }
    CompletableFuture<PlatformResult<Boolean>> result = new CompletableFuture<>();
    Runnable query = () -> {
      try {
        var allowed = players.hasPermissionLevel(playerId, requiredLevel(permission));
        result.complete(allowed.<PlatformResult<Boolean>>map(PlatformResult::success)
            .orElseGet(() -> new PlatformResult.Unavailable<>(
                "permission", "player is not online on this server", Duration.ZERO)));
      } catch (RuntimeException failure) {
        result.complete(new PlatformResult.Unavailable<>(
            "permission", "native permission query failed", Duration.ofSeconds(1)));
      }
    };
    if (scheduler.isOnRequiredThread(PlatformPorts.ThreadScope.PLAYER, playerId)) {
      query.run();
    } else {
      scheduler.runForPlayer(playerId, query).toCompletableFuture()
          .orTimeout(5, TimeUnit.SECONDS)
          .exceptionally(failure -> {
            result.complete(new PlatformResult.Unavailable<>(
                "permission", "server executor is unavailable", Duration.ofSeconds(1)));
            return null;
          });
    }
    return result;
  }

  private static int requiredLevel(String permission) {
    String normalized = permission.trim().toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("super") || normalized.contains("root")) return 4;
    return 2;
  }
}
