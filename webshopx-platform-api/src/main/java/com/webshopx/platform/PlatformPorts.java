package com.webshopx.platform;

import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Stable platform boundary. Methods returning CompletionStage may block internally only on a
 * platform async executor. Game objects never cross this boundary.
 */
public final class PlatformPorts {
  private PlatformPorts() { }

  public interface Lifecycle {
    void onReady(Runnable listener);
    void onStopping(Runnable listener);
  }

  public interface Scheduler {
    CompletionStage<Void> runGlobal(Runnable action);
    CompletionStage<Void> runForPlayer(UUID playerId, Runnable action);
    CompletionStage<Void> runAsync(Runnable action);
    ScheduledHandle schedule(Duration delay, Duration period, Runnable action);
    boolean isOnRequiredThread(ThreadScope scope, UUID playerId);
  }

  public interface ScheduledHandle { void cancel(); }
  public enum ThreadScope { GLOBAL, PLAYER, ASYNC }

  public interface PlayerDirectory {
    CompletionStage<Optional<PlayerSnapshot>> find(UUID playerId);
    CompletionStage<Optional<PlayerSnapshot>> find(String playerName);
    CompletionStage<List<PlayerSnapshot>> onlinePlayers();
  }

  public record PlayerSnapshot(UUID id, String name, boolean online, String serverId, Locale locale) { }

  public interface InventoryGateway {
    CompletionStage<PlatformResult<InventorySnapshot>> snapshot(UUID playerId, boolean allowOffline);
    CompletionStage<PlatformResult<InventoryMutationResult>> compareAndApply(InventoryMutation mutation);
    default CompletionStage<PlatformResult<InventoryMutationResult>> operationResult(
        String operationId) {
      return java.util.concurrent.CompletableFuture.completedFuture(
          new PlatformResult.UnknownOutcome<>(operationId, true));
    }
  }

  public interface ItemCodec<N> {
    String id();
    int version();
    PlatformResult<ItemEnvelope> encode(N nativeItem, PlatformIdentity identity);
    PlatformResult<N> decode(ItemEnvelope envelope, CompatibilityDomain targetDomain);
  }

  public interface CommandGateway {
    PlatformResult<Void> register(CommandSpec command);
  }

  public record CommandSpec(String name, String permission, Consumer<CommandInvocation> handler) { }
  public record CommandInvocation(UUID playerId, String sourceName, List<String> arguments) { }

  public interface PermissionProvider {
    CompletionStage<PlatformResult<Boolean>> hasPermission(UUID playerId, String permission, String context);
  }

  public interface EconomyProvider {
    EconomyCapabilities capabilities();
    CompletionStage<PlatformResult<BigInteger>> balance(UUID playerId, String currency);
    CompletionStage<PlatformResult<BigInteger>> debit(
        UUID playerId, String currency, BigInteger amount, String operationId);
    CompletionStage<PlatformResult<BigInteger>> credit(
        UUID playerId, String currency, BigInteger amount, String operationId);
  }

  public record EconomyCapabilities(
      int scale, boolean negativeBalance, boolean offlineAccounts, boolean atomicDebit,
      boolean transactionIds, boolean refunds) { }

  public interface MessagingGateway {
    PlatformResult<Void> send(UUID playerId, Message message);
    PlatformResult<Void> broadcast(Message message);
  }

  public record Message(String key, List<String> arguments, String actionUrl) {
    public Message { arguments = List.copyOf(arguments); }
  }

  public interface EventPublisher {
    PlatformResult<Void> publish(PlatformEvent event);
  }

  public record PlatformEvent(String id, String type, int schemaVersion, String serverId,
                              CompatibilityDomain domain, long occurredAtEpochMillis,
                              String payloadJson) { }

  public record Paths(Path config, Path data, Path resources, Path uploads, Path logs) { }

  public record Bundle(
      Lifecycle lifecycle,
      Scheduler scheduler,
      PlayerDirectory players,
      InventoryGateway inventories,
      ItemCodec<?> items,
      CommandGateway commands,
      PermissionProvider permissions,
      EconomyProvider economy,
      MessagingGateway messaging,
      EventPublisher events,
      Paths paths,
      PlatformIdentity identity,
      CapabilitySnapshot capabilities) { }
}
