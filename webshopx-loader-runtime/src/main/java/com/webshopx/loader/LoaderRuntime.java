package com.webshopx.loader;

import com.webshopx.AdminAuditService;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.RedeemCodeService;
import com.webshopx.ServiceException;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedRuntimeConfigService;
import com.webshopx.WalletService;
import com.webshopx.core.RedisEventBridge;
import com.webshopx.core.SharedHttpApi;
import com.webshopx.core.WebShopXCoreRuntime;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.CapabilitySnapshot.Capability;
import com.webshopx.platform.CapabilitySnapshot.CapabilityState;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Loader-independent bootstrap. Loader-specific adapters can replace ports one at a time. */
public final class LoaderRuntime {
  private static volatile WebShopXCoreRuntime active;
  private static RuntimeInstanceGuard instanceGuard;
  private static SharedDatabaseRuntime databaseRuntime;
  private static LoaderLifecycle lifecycle;
  private static LoaderScheduler scheduler;
  private static NativePlayerDirectory playerDirectory;
  private static NativeItemCodec itemCodec;
  private static NativeInventoryGateway inventoryGateway;
  private static NativeSupplyInventoryGateway supplyInventoryGateway;
  private static NativeDeliveryCoordinator deliveries;
  private static RedisEventBridge redisEvents;
  private static SharedHttpApi httpApi;
  private static boolean nativeLifecycleInstalled;
  private static boolean shutdownHookInstalled;

  private LoaderRuntime() {}

  public static synchronized WebShopXCoreRuntime start(
      String loader, String minecraftVersion, String loaderVersion) {
    if (active != null && active.state() != WebShopXCoreRuntime.State.STOPPED) return active;
    instanceGuard =
        RuntimeInstanceGuard.acquire(
            Path.of(System.getProperty("webshopx.data-dir", "config/webshopx")), loader);
    installShutdownHook();
    PlatformPorts.Bundle bundle = minimalBundle(loader, minecraftVersion, loaderVersion);
    try {
      if (!isPluginPlatform(loader)) {
        databaseRuntime = SharedDatabaseRuntime.start(bundle.paths().data());
        SharedDatabaseRuntime auctionDatabase = databaseRuntime;
        scheduler.schedule(
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            () -> auctionDatabase.commerce().settleExpiredAuctions(20));
        deliveries =
            new NativeDeliveryCoordinator(
                databaseRuntime.commerce(),
                inventoryGateway,
                itemCodec,
                scheduler,
                bundle.identity().serverId());
        if (Boolean.parseBoolean(System.getProperty("webshopx.http.enabled", "true"))) {
          httpApi =
              new SharedHttpApi(
                  System.getProperty("webshopx.http.host", "127.0.0.1"),
                  Integer.getInteger("webshopx.http.port", 8123),
                  System.getProperty("webshopx.http.allowed-origin", ""),
                  databaseRuntime.authentication(),
                  databaseRuntime.wallet(),
                  databaseRuntime.commerce(),
                  databaseRuntime.marketEscrow(inventoryGateway),
                  databaseRuntime.content(),
                  databaseRuntime.promotions(),
                  databaseRuntime.redeemCodes(),
                  databaseRuntime.notifications(),
                  databaseRuntime.administration(),
                  databaseRuntime.audit(),
                  databaseRuntime.refundPolicies(),
                  databaseRuntime.runtimeConfig(),
                  bundle.identity(),
                  bundle.capabilities(),
                  supplyInventoryGateway);
          httpApi.start();
          System.out.printf(
              "[WebShopX] HTTP API listening on %s:%d%n",
              System.getProperty("webshopx.http.host", "127.0.0.1"), httpApi.port());
        }
      }
      active = new WebShopXCoreRuntime(bundle);
      active.start();
    } catch (RuntimeException failure) {
      if (databaseRuntime != null) databaseRuntime.close();
      databaseRuntime = null;
      deliveries = null;
      if (httpApi != null) httpApi.close();
      httpApi = null;
      instanceGuard.close();
      instanceGuard = null;
      throw failure;
    }
    RuntimeHealth.write(
        bundle.paths().data(), bundle.identity(), bundle.capabilities(), active.state());
    System.out.printf(
        "[WebShopX] ready loader=%s minecraft=%s loaderVersion=%s domain=%s%n",
        loader, minecraftVersion, loaderVersion, bundle.identity().modpackFingerprint());
    return active;
  }

  public static WebShopXCoreRuntime startDetected(String loader) {
    LoaderEnvironment.Version version = LoaderEnvironment.detect(loader);
    return start(loader, version.minecraft(), version.loader());
  }

  private static boolean isPluginPlatform(String platform) {
    return "paper".equalsIgnoreCase(platform) || "folia".equalsIgnoreCase(platform);
  }

  /** Installs Fabric lifecycle/player callbacks before core bootstrap. */
  public static synchronized boolean prepareFabric() {
    prepareNativeState();
    nativeLifecycleInstalled = ReflectiveNativeLifecycle.installFabric();
    logNativeLifecycle("fabric", nativeLifecycleInstalled);
    return nativeLifecycleInstalled;
  }

  /** Installs Forge or NeoForge lifecycle/player callbacks before core bootstrap. */
  public static synchronized boolean prepareEventBus(String loader, String eventBusHolder) {
    prepareNativeState();
    nativeLifecycleInstalled = ReflectiveNativeLifecycle.installEventBus(eventBusHolder);
    logNativeLifecycle(loader, nativeLifecycleInstalled);
    return nativeLifecycleInstalled;
  }

  public static synchronized void stop() {
    if (lifecycle != null) lifecycle.fireStopping();
    if (active != null) {
      PlatformPorts.Bundle platform = active.platform();
      active.close();
      RuntimeHealth.write(
          platform.paths().data(), platform.identity(), platform.capabilities(), active.state());
    }
    active = null;
    if (httpApi != null) httpApi.close();
    httpApi = null;
    if (scheduler != null) scheduler.close();
    scheduler = null;
    if (databaseRuntime != null) databaseRuntime.close();
    databaseRuntime = null;
    lifecycle = null;
    if (playerDirectory != null) playerDirectory.clear();
    playerDirectory = null;
    itemCodec = null;
    inventoryGateway = null;
    supplyInventoryGateway = null;
    deliveries = null;
    if (redisEvents != null) redisEvents.close();
    redisEvents = null;
    nativeLifecycleInstalled = false;
    if (instanceGuard != null) instanceGuard.close();
    instanceGuard = null;
  }

  public static Optional<WebShopXCoreRuntime> active() {
    return Optional.ofNullable(active);
  }

  /** Platform-neutral authentication graph backed by the Loader-owned database. */
  public static Optional<AuthService> authentication() {
    SharedDatabaseRuntime current = databaseRuntime;
    return current == null ? Optional.empty() : Optional.of(current.authentication());
  }

  /** Shared idempotent wallet graph; external game-coin integration is capability-gated. */
  public static Optional<WalletService> wallet() {
    SharedDatabaseRuntime current = databaseRuntime;
    return current == null ? Optional.empty() : Optional.of(current.wallet());
  }

  public static Optional<RedeemCodeService> redeemCodes() {
    SharedDatabaseRuntime current = databaseRuntime;
    return current == null ? Optional.empty() : Optional.of(current.redeemCodes());
  }

  public static Optional<AdminService> administration() {
    SharedDatabaseRuntime current = databaseRuntime;
    return current == null ? Optional.empty() : Optional.of(current.administration());
  }

  public static Optional<AdminAuditService> audit() {
    SharedDatabaseRuntime current = databaseRuntime;
    return current == null ? Optional.empty() : Optional.of(current.audit());
  }

  public static Optional<SharedCommerceService> commerce() {
    SharedDatabaseRuntime current = databaseRuntime;
    return current == null ? Optional.empty() : Optional.of(current.commerce());
  }

  public static Optional<SharedRuntimeConfigService> runtimeConfig() {
    SharedDatabaseRuntime current = databaseRuntime;
    return current == null ? Optional.empty() : Optional.of(current.runtimeConfig());
  }

  public static String healthLine() {
    WebShopXCoreRuntime runtime = active;
    if (runtime == null) return "WebShopX state=STOPPED";
    PlatformIdentity identity = runtime.platform().identity();
    return "WebShopX state="
        + runtime.state()
        + " platform="
        + identity.platform()
        + " minecraft="
        + identity.minecraftVersion()
        + " loader="
        + identity.loaderVersion();
  }

  static String nativeItemProbe() {
    NativeItemCodec current = itemCodec;
    return current == null
        ? "WebShopX item-roundtrip=FAIL reason=codec_not_started"
        : current.probeRoundTrip();
  }

  static String nativeInventoryProbe(String playerId, NativeInventoryProbe.Mode mode) {
    NativeInventoryGateway inventories = inventoryGateway;
    NativeItemCodec items = itemCodec;
    WebShopXCoreRuntime runtime = active;
    if (inventories == null || items == null || runtime == null) {
      throw new IllegalStateException("native inventory is not started");
    }
    UUID id;
    try {
      id = UUID.fromString(playerId);
    } catch (IllegalArgumentException invalid) {
      throw new ServiceException("invalid_player", "Player UUID is invalid");
    }
    return NativeInventoryProbe.run(
        inventories, items, id, mode, runtime.platform().paths().data());
  }

  private static synchronized void installShutdownHook() {
    if (shutdownHookInstalled) return;
    Runtime.getRuntime().addShutdownHook(new Thread(LoaderRuntime::stop, "webshopx-shutdown"));
    shutdownHookInstalled = true;
  }

  static PlatformPorts.Bundle minimalBundle(String loader, String minecraft, String loaderVersion) {
    String fingerprint =
        CompatibilityDomain.fingerprint(
            List.of(new CompatibilityDomain.ModIdentity("webshopx", "dev-v3.0.0")));
    PlatformIdentity identity =
        new PlatformIdentity(
            loader,
            loader,
            minecraft,
            loaderVersion,
            System.getProperty("webshopx.server-id", "standalone"),
            fingerprint);
    EnumMap<Capability, CapabilityState> states = new EnumMap<>(Capability.class);
    for (Capability capability : Capability.values()) {
      states.put(capability, CapabilityState.unavailable("adapter not installed"));
    }
    states.put(Capability.RELAY, CapabilityState.available("core event contract"));
    states.put(
        Capability.MOD_ITEM_CODEC,
        CapabilityState.available("lossless opaque native payload envelope"));
    states.put(
        Capability.OFFLINE_INVENTORY,
        CapabilityState.available("atomic playerdata NBT compare-and-apply"));
    states.put(
        Capability.PERMISSION,
        CapabilityState.available("vanilla operator levels; online queries only"));
    states.put(
        Capability.ECONOMY,
        CapabilityState.unsupported("internal ShopCoin ledger only; no external economy adapter"));
    states.put(
        Capability.PAYMENT_PROVIDER,
        CapabilityState.unsupported("no Loader payment provider registered"));
    states.put(
        Capability.CLIENT_ENHANCEMENT,
        CapabilityState.unsupported("server-only release; use HTTP and commands"));
    states.put(
        Capability.DATABASE,
        CapabilityState.available(
            System.getProperty("webshopx.database.type", "sqlite") + " database configured"));
    states.put(
        Capability.HTTP_API,
        Boolean.parseBoolean(System.getProperty("webshopx.http.enabled", "true"))
            ? CapabilityState.available("embedded authenticated HTTP API")
            : CapabilityState.unavailable("disabled by configuration"));
    if (lifecycle == null) lifecycle = new LoaderLifecycle();
    if (scheduler == null) scheduler = new LoaderScheduler();
    if (playerDirectory == null) playerDirectory = new NativePlayerDirectory(identity.serverId());
    if (!nativeLifecycleInstalled) lifecycle.fireReady();
    PlatformPorts.PlayerDirectory players = playerDirectory;
    PlatformPorts.CommandGateway commands =
        command ->
            new PlatformResult.Unavailable<>(
                "commands", "native command adapter is unavailable", Duration.ZERO);
    PlatformPorts.PermissionProvider permissions =
        new NativePermissionProvider(playerDirectory, scheduler);
    PlatformPorts.EconomyProvider economy = new UnavailableEconomy();
    PlatformPorts.MessagingGateway messaging =
        new NativeMessagingGateway(playerDirectory, scheduler);
    PlatformPorts.EventPublisher events = event -> PlatformResult.success(null);
    if (Boolean.getBoolean("webshopx.redis.enabled")) {
      try {
        redisEvents =
            new RedisEventBridge(
                System.getProperty("webshopx.redis.host", "127.0.0.1"),
                Integer.getInteger("webshopx.redis.port", 6379),
                System.getProperty("webshopx.redis.password", ""),
                System.getProperty("webshopx.redis.channel", "webshopx:events"),
                incoming ->
                    System.out.printf(
                        "[WebShopX] relay event id=%s type=%s source=%s%n",
                        incoming.id(), incoming.type(), incoming.serverId()));
        events = redisEvents;
        states.put(Capability.REDIS, CapabilityState.available("Redis pub/sub connected"));
      } catch (RuntimeException failure) {
        states.put(
            Capability.REDIS,
            CapabilityState.unavailable(
                "Redis connection failed: " + failure.getClass().getSimpleName()));
      }
    }
    CapabilitySnapshot capabilities = new CapabilitySnapshot(Instant.now(), states);
    Path base = Path.of(System.getProperty("webshopx.data-dir", "config/webshopx"));
    NativeItemCodec items =
        new NativeItemCodec(identity, scheduler::nativeServer, Clock.systemUTC());
    itemCodec = items;
    NativeInventoryGateway inventories =
        new NativeInventoryGateway(playerDirectory, scheduler, items, identity, base);
    inventoryGateway = inventories;
    supplyInventoryGateway =
        new NativeSupplyInventoryGateway(scheduler, playerDirectory, items, identity);
    return new PlatformPorts.Bundle(
        lifecycle,
        scheduler,
        players,
        inventories,
        items,
        commands,
        permissions,
        economy,
        messaging,
        events,
        new PlatformPorts.Paths(
            base, base, base.resolve("web"), base.resolve("uploads"), base.resolve("logs")),
        identity,
        capabilities);
  }

  static synchronized void nativeServerStarted(Object server) {
    if (scheduler == null || lifecycle == null || active == null) return;
    try {
      scheduler.bind(server);
      lifecycle.fireReady();
      PlatformPorts.Bundle platform = active.platform();
      RuntimeHealth.write(
          platform.paths().data(), platform.identity(), platform.capabilities(), active.state());
      System.out.println("[WebShopX] native server lifecycle ready");
    } catch (RuntimeException failure) {
      System.err.printf("[WebShopX] native server binding failed: %s%n", failure);
    }
  }

  static void nativeServerStopping() {
    stop();
  }

  static void nativePlayerJoined(Object eventOrHandler) {
    NativePlayerDirectory current = playerDirectory;
    if (current == null) return;
    current
        .joined(eventOrHandler)
        .ifPresent(
            player -> {
              SharedDatabaseRuntime database = databaseRuntime;
              if (database != null) database.presence().markOnline(player.id(), player.name());
              NativeDeliveryCoordinator coordinator = deliveries;
              if (coordinator != null) coordinator.deliverPending(player.id());
            });
  }

  static void nativePlayerDisconnected(Object eventOrHandler) {
    NativePlayerDirectory current = playerDirectory;
    if (current == null) return;
    Optional<PlatformPorts.PlayerSnapshot> identity = current.disconnectIdentity(eventOrHandler);
    if (identity.isEmpty()) {
      System.err.println("[WebShopX] native player disconnect identity unavailable");
      return;
    }
    Runnable completeDisconnect = () -> {
      PlatformPorts.PlayerSnapshot player = current.completeDisconnect(identity.orElseThrow());
      SharedDatabaseRuntime database = databaseRuntime;
      if (database != null) database.presence().markOffline(player.id());
    };
    LoaderScheduler currentScheduler = scheduler;
    if (currentScheduler == null || currentScheduler.nativeServer() == null) {
      completeDisconnect.run();
      return;
    }
    currentScheduler.deferGlobal(completeDisconnect).whenComplete((ignored, failure) -> {
      if (failure != null) {
        System.err.printf("[WebShopX] deferred player disconnect failed type=%s%n",
            failure.getClass().getSimpleName());
      }
    });
  }

  static boolean nativeSupplyAccessDenied(
      Object player, Object level, Object position, NativeSupplyProtection.Action action) {
    SharedDatabaseRuntime database = databaseRuntime;
    if (database == null) return true;
    return NativeSupplyProtection.denied(database.commerce(), player, level, position, action);
  }

  private static void prepareNativeState() {
    if (active != null)
      throw new IllegalStateException("native lifecycle must be prepared before runtime start");
    if (lifecycle == null) lifecycle = new LoaderLifecycle();
    if (scheduler == null) scheduler = new LoaderScheduler();
    if (playerDirectory == null) {
      playerDirectory =
          new NativePlayerDirectory(System.getProperty("webshopx.server-id", "standalone"));
    }
  }

  private static void logNativeLifecycle(String loader, boolean installed) {
    System.out.printf("[WebShopX] native lifecycle loader=%s installed=%s%n", loader, installed);
  }

  private static final class UnavailableEconomy implements PlatformPorts.EconomyProvider {
    private static final PlatformPorts.EconomyCapabilities CAPS =
        new PlatformPorts.EconomyCapabilities(0, false, false, false, false, false);

    public PlatformPorts.EconomyCapabilities capabilities() {
      return CAPS;
    }

    public CompletionStage<PlatformResult<java.math.BigInteger>> balance(UUID id, String currency) {
      return unavailable();
    }

    public CompletionStage<PlatformResult<java.math.BigInteger>> debit(
        UUID id, String currency, java.math.BigInteger amount, String op) {
      return unavailable();
    }

    public CompletionStage<PlatformResult<java.math.BigInteger>> credit(
        UUID id, String currency, java.math.BigInteger amount, String op) {
      return unavailable();
    }

    private CompletionStage<PlatformResult<java.math.BigInteger>> unavailable() {
      return CompletableFuture.completedFuture(
          new PlatformResult.Unavailable<>(
              "economy", "economy provider is unavailable", Duration.ZERO));
    }
  }
}
