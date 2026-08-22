package com.webshopx.loader;

import com.webshopx.core.WebShopXCoreRuntime;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.CapabilitySnapshot.Capability;
import com.webshopx.platform.CapabilitySnapshot.CapabilityState;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** Loader-independent bootstrap. Loader-specific adapters can replace ports one at a time. */
public final class LoaderRuntime {
  private static volatile WebShopXCoreRuntime active;
  private static RuntimeInstanceGuard instanceGuard;

  private LoaderRuntime() { }

  public static synchronized WebShopXCoreRuntime start(
      String loader, String minecraftVersion, String loaderVersion) {
    if (active != null && active.state() != WebShopXCoreRuntime.State.STOPPED) return active;
    instanceGuard = RuntimeInstanceGuard.acquire(
        Path.of(System.getProperty("webshopx.data-dir", "config/webshopx")), loader);
    PlatformPorts.Bundle bundle = minimalBundle(loader, minecraftVersion, loaderVersion);
    active = new WebShopXCoreRuntime(bundle);
    active.start();
    System.out.printf("[WebShopX] ready loader=%s minecraft=%s loaderVersion=%s domain=%s%n",
        loader, minecraftVersion, loaderVersion, bundle.identity().modpackFingerprint());
    return active;
  }

  public static synchronized void stop() {
    if (active != null) active.close();
    active = null;
    if (instanceGuard != null) instanceGuard.close();
    instanceGuard = null;
  }

  public static Optional<WebShopXCoreRuntime> active() {
    return Optional.ofNullable(active);
  }

  static PlatformPorts.Bundle minimalBundle(String loader, String minecraft, String loaderVersion) {
    String fingerprint = CompatibilityDomain.fingerprint(List.of(
        new CompatibilityDomain.ModIdentity("webshopx", "dev-v3.0.0")));
    PlatformIdentity identity = new PlatformIdentity(
        loader, loader, minecraft, loaderVersion,
        System.getProperty("webshopx.server-id", "standalone"), fingerprint);
    EnumMap<Capability, CapabilityState> states = new EnumMap<>(Capability.class);
    for (Capability capability : Capability.values()) {
      states.put(capability, CapabilityState.unavailable("adapter not installed"));
    }
    states.put(Capability.RELAY, CapabilityState.available("core event contract"));
    CapabilitySnapshot capabilities = new CapabilitySnapshot(Instant.now(), states);
    ImmediateLifecycle lifecycle = new ImmediateLifecycle();
    PlatformPorts.Scheduler scheduler = new DirectScheduler();
    PlatformPorts.PlayerDirectory players = new PlatformPorts.PlayerDirectory() {
      public CompletionStage<Optional<PlatformPorts.PlayerSnapshot>> find(UUID id) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      public CompletionStage<Optional<PlatformPorts.PlayerSnapshot>> find(String name) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      public CompletionStage<List<PlatformPorts.PlayerSnapshot>> onlinePlayers() {
        return CompletableFuture.completedFuture(List.of());
      }
    };
    PlatformPorts.InventoryGateway inventories = new PlatformPorts.InventoryGateway() {
      public CompletionStage<PlatformResult<com.webshopx.platform.InventoryTypes.InventorySnapshot>> snapshot(
          UUID id, boolean offline) {
        return CompletableFuture.completedFuture(new PlatformResult.Unavailable<>(
            "inventory", "native inventory adapter is unavailable", Duration.ZERO));
      }
      public CompletionStage<PlatformResult<com.webshopx.platform.InventoryTypes.InventoryMutationResult>> compareAndApply(
          com.webshopx.platform.InventoryTypes.InventoryMutation mutation) {
        return CompletableFuture.completedFuture(new PlatformResult.Unavailable<>(
            "inventory", "native inventory adapter is unavailable", Duration.ZERO));
      }
    };
    PlatformPorts.CommandGateway commands = command ->
        new PlatformResult.Unavailable<>("commands", "native command adapter is unavailable", Duration.ZERO);
    PlatformPorts.PermissionProvider permissions = (id, permission, context) ->
        CompletableFuture.completedFuture(new PlatformResult.Unavailable<>(
            "permission", "permission provider is unavailable", Duration.ZERO));
    PlatformPorts.EconomyProvider economy = new UnavailableEconomy();
    PlatformPorts.MessagingGateway messaging = new PlatformPorts.MessagingGateway() {
      public PlatformResult<Void> send(UUID id, PlatformPorts.Message message) {
        return new PlatformResult.Unavailable<>("messaging", "messaging adapter is unavailable", Duration.ZERO);
      }
      public PlatformResult<Void> broadcast(PlatformPorts.Message message) {
        return new PlatformResult.Unavailable<>("messaging", "messaging adapter is unavailable", Duration.ZERO);
      }
    };
    PlatformPorts.EventPublisher events = event -> PlatformResult.success(null);
    Path base = Path.of(System.getProperty("webshopx.data-dir", "config/webshopx"));
    return new PlatformPorts.Bundle(lifecycle, scheduler, players, inventories, commands,
        permissions, economy, messaging, events,
        new PlatformPorts.Paths(base, base, base.resolve("web"), base.resolve("uploads"), base.resolve("logs")),
        identity, capabilities);
  }

  private static final class ImmediateLifecycle implements PlatformPorts.Lifecycle {
    private final List<Runnable> stopping = new CopyOnWriteArrayList<>();
    public void onReady(Runnable listener) { listener.run(); }
    public void onStopping(Runnable listener) { stopping.add(listener); }
  }

  private static final class DirectScheduler implements PlatformPorts.Scheduler {
    public CompletionStage<Void> runGlobal(Runnable action) { return run(action); }
    public CompletionStage<Void> runForPlayer(UUID id, Runnable action) { return run(action); }
    public CompletionStage<Void> runAsync(Runnable action) { return CompletableFuture.runAsync(action); }
    public PlatformPorts.ScheduledHandle schedule(Duration delay, Duration period, Runnable action) {
      return () -> { };
    }
    public boolean isOnRequiredThread(PlatformPorts.ThreadScope scope, UUID id) { return true; }
    private CompletionStage<Void> run(Runnable action) {
      try { action.run(); return CompletableFuture.completedFuture(null); }
      catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
  }

  private static final class UnavailableEconomy implements PlatformPorts.EconomyProvider {
    private static final PlatformPorts.EconomyCapabilities CAPS =
        new PlatformPorts.EconomyCapabilities(0, false, false, false, false, false);
    public PlatformPorts.EconomyCapabilities capabilities() { return CAPS; }
    public CompletionStage<PlatformResult<java.math.BigInteger>> balance(UUID id, String currency) { return unavailable(); }
    public CompletionStage<PlatformResult<java.math.BigInteger>> debit(UUID id, String currency, java.math.BigInteger amount, String op) { return unavailable(); }
    public CompletionStage<PlatformResult<java.math.BigInteger>> credit(UUID id, String currency, java.math.BigInteger amount, String op) { return unavailable(); }
    private CompletionStage<PlatformResult<java.math.BigInteger>> unavailable() {
      return CompletableFuture.completedFuture(new PlatformResult.Unavailable<>(
          "economy", "economy provider is unavailable", Duration.ZERO));
    }
  }
}
