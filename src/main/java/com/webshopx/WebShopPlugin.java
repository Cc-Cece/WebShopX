package com.webshopx;

import com.tchristofferson.configupdater.ConfigUpdater;
import com.webshopx.loader.LoaderRuntime;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.logging.Level;
import org.bukkit.configuration.file.FileConfiguration;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.MultiLineChart;
import org.bstats.charts.SingleLineChart;
import org.bstats.charts.SimplePie;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main entry point for the WebShop plugin.
 */
public class WebShopPlugin extends JavaPlugin {
  private static final String MAIN_CONFIG_RESOURCE = "config.yml";
  private static final int BSTATS_PLUGIN_ID = 30746;
  private static final String USER_WEB_ROOT = "web-user";
  private static final String USER_WEB_MIGRATION_MARKER = ".migration-v1.done";

  private PluginSettings settings;
  private DatabaseManager databaseManager;
  private RuntimeConfigService runtimeConfigService;
  private HomepageService homepageService;
  private PlayerPresenceService playerPresenceService;
  private ClusterEventBusService clusterEventBusService;
  private AuthService authService;
  private WalletService walletService;
  private RechargeService rechargeService;
  private YuPayBridge yuPayBridge;
  private WebShopXPaymentBridge paymentBridge;
  private MessageService messageService;
  private RedeemCodeService redeemCodeService;
  private ProductService productService;
  private OrderService orderService;
  private MarketService marketService;
  private MaterialVisualService materialVisualService;
  private VisualCustomizationService visualCustomizationService;
  private VisualPackService visualPackService;
  private UserMarketSettingsService userMarketSettingsService;
  private NotificationService notificationService;
  private MailboxService mailboxService;
  private MailboxGuiService mailboxGuiService;
  private BroadcastService broadcastService;
  private MarketGuiService marketGuiService;
  private DeliveryService deliveryService;
  private AdminService adminService;
  private AdminAuditService adminAuditService;
  private LeaderboardService leaderboardService;
  private InventoryReadSnapshotService inventoryReadSnapshotService;
  private InventoryLockManager inventoryLockManager;
  private OfflineInventoryFeatureService offlineInventoryFeatureService;
  private PlayerDataInventoryService playerDataInventoryService;
  private EmbeddedWebServer embeddedWebServer;
  private RelayConnectorService relayConnectorService;
  private RelaySetupService relaySetupService;
  private Path webStaticRoot;
  private Path webUserRoot;
  private StaticAssetInstaller staticAssetInstaller;
  private TextureAssetManager textureAssetManager;
  private MaintenanceService maintenanceService;
  private PluginLogService pluginLogService;
  private BusinessLedgerLogService businessLedgerLogService;
  private BStatsTelemetryService bStatsTelemetryService;
  private SchedulerBridge schedulerBridge;
  private Metrics metrics;
  private SchedulerBridge.TaskHandle deliveryTask;
  private SchedulerBridge.TaskHandle maintenanceTask;
  private SchedulerBridge.TaskHandle marketCycleTask;
  private volatile BStatsTelemetryService.Snapshot telemetrySnapshotCache;
  private volatile long telemetrySnapshotAtMillis;

  @Override
  public void onEnable() {
    LoaderRuntime.start("paper", getServer().getMinecraftVersion(), getServer().getVersion());
    refreshMainConfig();
    schedulerBridge = SchedulerBridge.create(this);
    relaySetupService = new RelaySetupService(this, schedulerBridge);

    try {
      settings = PluginSettings.fromConfig(getConfig());
      pluginLogService = new PluginLogService(this);
      pluginLogService.apply(settings.loggingSettings());
      businessLedgerLogService = new BusinessLedgerLogService(this);
      businessLedgerLogService.apply(settings.businessLedgerSettings());
      staticAssetInstaller = new StaticAssetInstaller(this);
      textureAssetManager = new TextureAssetManager(this, schedulerBridge);
      messageService = new MessageService(this, this::settings);
      getLogger().info("Scheduler runtime detected: " + schedulerBridge.runtimeName());

      enforceDatabaseModeGuard();
      initializeDatabase();
      runtimeConfigService = new RuntimeConfigService(databaseManager);
      inventoryLockManager = new InventoryLockManager();
      offlineInventoryFeatureService =
          new OfflineInventoryFeatureService(this, runtimeConfigService);
      playerDataInventoryService = new PlayerDataInventoryService(
          this, inventoryLockManager, new ItemSnapshotCodec());
      playerDataInventoryService.recoverPending(
          new InventoryOperationService(databaseManager));
      inventoryReadSnapshotService = new InventoryReadSnapshotService(
          databaseManager,
          new com.google.gson.GsonBuilder().disableHtmlEscaping().create(),
          this);
      boolean migratedLegacyConfig = runtimeConfigService.bootstrapFromLegacyConfigIfNeeded(settings);
      if (migratedLegacyConfig || runtimeConfigService.isLegacyMigrationCompleted()) {
        pruneLegacyBusinessConfigAndBackup();
      }
      runtimeConfigService.ensureDefaults(settings);
      homepageService = new HomepageService(databaseManager);
      settings = runtimeConfigService.applyTo(settings);
      try {
        bStatsTelemetryService = new BStatsTelemetryService(databaseManager, this::settings);
        bStatsTelemetryService.markStartupAttempt();
      } catch (Exception exception) {
        getLogger().log(Level.WARNING, "Failed to initialize bStats telemetry service; continuing without telemetry.", exception);
        bStatsTelemetryService = null;
      }
      playerPresenceService = new PlayerPresenceService(databaseManager, this::settings);
      clusterEventBusService = new ClusterEventBusService(
          this,
          this::settings,
          this::handleClusterConfigRefreshEvent);

      authService = new AuthService(databaseManager, this::settings);
      walletService = new WalletService(this, databaseManager, this::settings, businessLedgerLogService);
      yuPayBridge = new YuPayBridge(this);
      paymentBridge = new WebShopXPaymentBridge(this, yuPayBridge, this::settings);
      rechargeService = new RechargeService(databaseManager, walletService, paymentBridge, this::settings);
      redeemCodeService = new RedeemCodeService(databaseManager, walletService);
      productService = new ProductService(databaseManager);
      ProductService.SnapshotMetadataRepairResult snapshotRepair =
          productService.repairLegacyContainerSnapshotMetadata();
      if (snapshotRepair.repaired() > 0) {
        getLogger().info(
            "Rebuilt container preview metadata for "
                + snapshotRepair.repaired() + " official item snapshot(s).");
      }
      if (snapshotRepair.failed() > 0) {
        getLogger().warning(
            "Could not rebuild container preview metadata for "
                + snapshotRepair.failed() + " of "
                + snapshotRepair.candidates() + " official item snapshot(s).");
      }
      orderService = new OrderService(
          databaseManager,
          this::settings,
          productService,
          walletService,
          playerPresenceService,
          schedulerBridge);
      notificationService = new NotificationService(databaseManager);
      mailboxService = new MailboxService(databaseManager);
      mailboxGuiService = new MailboxGuiService(mailboxService, messageService);
      broadcastService = new BroadcastService(this, this::settings, schedulerBridge);
      broadcastService.reload();
      clusterEventBusService.reload();
      userMarketSettingsService = new UserMarketSettingsService(databaseManager, schedulerBridge);
      marketService = new MarketService(
          this,
          databaseManager,
          walletService,
          runtimeConfigService,
          this::settings,
          messageService,
          notificationService,
          broadcastService,
          playerPresenceService,
          userMarketSettingsService,
          schedulerBridge);
      materialVisualService = new MaterialVisualService(databaseManager);
      visualCustomizationService = new VisualCustomizationService(databaseManager);
      visualPackService = new VisualPackService(
          databaseManager,
          this,
          new com.google.gson.GsonBuilder().disableHtmlEscaping().create());
      marketGuiService = new MarketGuiService(marketService, this::settings, messageService);
      deliveryService = new DeliveryService(
          this,
          databaseManager,
          walletService,
          runtimeConfigService,
          this::settings,
          messageService,
          notificationService,
          mailboxService,
          schedulerBridge);
      adminService = new AdminService(databaseManager, authService, walletService);
      adminAuditService = new AdminAuditService(databaseManager);
          leaderboardService = new LeaderboardService(this, databaseManager, schedulerBridge);
      maintenanceService = new MaintenanceService(this, databaseManager, this::settings, pluginLogService);
      embeddedWebServer = new EmbeddedWebServer(
          this,
          schedulerBridge,
          databaseManager,
          this::settings,
          authService,
          walletService,
          rechargeService,
          redeemCodeService,
          productService,
          orderService,
          deliveryService,
          marketService,
          notificationService,
          adminService,
          adminAuditService,
          leaderboardService,
          materialVisualService,
          visualCustomizationService,
          visualPackService,
          userMarketSettingsService,
          runtimeConfigService,
          homepageService,
          clusterEventBusService,
          bStatsTelemetryService,
          inventoryReadSnapshotService,
          offlineInventoryFeatureService,
          playerDataInventoryService);

      // Products are managed via admin backend; no seed import from config.
      adminService.ensureBootstrapAdmin(settings.adminBootstrapSettings());

      registerCommands();
      registerPaymentListener();
      getServer().getPluginManager().registerEvents(
          new PlayerJoinListener(
              this,
              deliveryService,
              playerPresenceService,
              messageService,
              bStatsTelemetryService,
              schedulerBridge),
          this);
      getServer().getPluginManager().registerEvents(
          new PlayerQuitListener(
              this,
              playerPresenceService,
              schedulerBridge,
              inventoryReadSnapshotService),
          this);
      getServer().getPluginManager().registerEvents(
          new MarketGuiListener(marketGuiService, marketService, messageService, schedulerBridge),
          this);
      getServer().getPluginManager().registerEvents(
          new PlayerDataGate(inventoryLockManager), this);
      getServer().getPluginManager().registerEvents(mailboxGuiService, this);
      synchronizeOnlinePresence();
      startDeliveryLoop();
      startMaintenanceLoop();
      startMarketCycleLoop();
      restartWebRuntime();
      restartRelayConnector();
      if (bStatsTelemetryService != null) {
        bStatsTelemetryService.markStartupSuccess();
      }
      initializeMetrics();

      getLogger().info(messageService.getConsole("console.enabled_success"));
    } catch (DefaultDatabaseConfigurationException exception) {
      if (bStatsTelemetryService != null) {
        bStatsTelemetryService.markStartupFailure("default_database_configuration");
      }
      getLogger().warning(messageService.getConsole("console.default_database_config"));
      getLogger().warning(messageService.getConsole("console.update_database_instructions"));
      getServer().getPluginManager().disablePlugin(this);
    } catch (Exception exception) {
      if (bStatsTelemetryService != null) {
        bStatsTelemetryService.markStartupFailure("on_enable_exception");
      }
      getLogger().log(Level.SEVERE, messageService.getConsole("console.failed_startup"), exception);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (rechargeService != null) {
      rechargeService.unregisterPaymentListener();
    }
    if (deliveryTask != null) {
      deliveryTask.cancel();
      deliveryTask = null;
    }
    if (maintenanceTask != null) {
      maintenanceTask.cancel();
      maintenanceTask = null;
    }
    if (marketCycleTask != null) {
      marketCycleTask.cancel();
      marketCycleTask = null;
    }
    stopRelayConnector();
    if (embeddedWebServer != null) {
      embeddedWebServer.stop();
    }
    if (broadcastService != null) {
      broadcastService.shutdown();
    }
    if (clusterEventBusService != null) {
      clusterEventBusService.shutdown();
    }
    if (playerPresenceService != null && settings != null) {
      try {
        playerPresenceService.markServerOffline(settings.clusterSettings().serverId());
      } catch (Exception exception) {
        getLogger().warning(messageService.formatConsole("console.failed_mark_local_offline", MapUtils.mapOf("reason", exception.getMessage())));
      }
    }
    if (databaseManager != null) {
      databaseManager.close();
    }
    if (pluginLogService != null) {
      pluginLogService.close();
    }
    if (businessLedgerLogService != null) {
      businessLedgerLogService.close();
    }
    LoaderRuntime.stop();
  }

  void reloadRuntimeConfig() {
    refreshMainConfig();
    PluginSettings fileSettings = PluginSettings.fromConfig(getConfig());
    if (runtimeConfigService != null) {
      if (runtimeConfigService.isLegacyMigrationCompleted()) {
        pruneLegacyBusinessConfigAndBackup();
        fileSettings = PluginSettings.fromConfig(getConfig());
      }
      runtimeConfigService.ensureDefaults(fileSettings);
      fileSettings = runtimeConfigService.applyTo(fileSettings);
    }
    settings = fileSettings;
    if (pluginLogService != null) {
      pluginLogService.apply(settings.loggingSettings());
    }
    if (businessLedgerLogService != null) {
      businessLedgerLogService.apply(settings.businessLedgerSettings());
    }
    if (walletService != null) {
      walletService.refreshVaultHook();
    }
    if (rechargeService != null) {
      registerPaymentListener();
    }
    // Products are managed via admin backend; no seed import from config.
    if (adminService != null) {
      adminService.ensureBootstrapAdmin(settings.adminBootstrapSettings());
    }
    if (broadcastService != null) {
      broadcastService.reload();
    }
    if (clusterEventBusService != null) {
      clusterEventBusService.reload();
    }
    if (marketService != null) {
      marketService.refreshRuntimePolicies();
    }
    synchronizeOnlinePresence();
    startMaintenanceLoop();
    startMarketCycleLoop();
    restartWebRuntime();
    restartRelayConnector();
  }

  void reloadRuntimeBusinessSettings() {
    if (runtimeConfigService == null || settings == null) {
      return;
    }
    try {
      settings = runtimeConfigService.applyTo(settings);
      if (pluginLogService != null) {
        pluginLogService.apply(settings.loggingSettings());
      }
      if (businessLedgerLogService != null) {
        businessLedgerLogService.apply(settings.businessLedgerSettings());
      }
      if (marketService != null) {
        marketService.refreshRuntimePolicies();
      }
      startMaintenanceLoop();
      startMarketCycleLoop();
      // Database-backed business settings are consumed through the live settings
      // supplier. Restarting the HTTP server or Relay connector here can terminate
      // the request that triggered this refresh after its data was already saved.
      // Deployment changes still use reloadRuntimeConfig(), which performs the
      // required web and connector restart.
    } catch (Exception exception) {
      getLogger().log(Level.WARNING, messageService.getConsole("console.failed_reload_runtime_business_settings"), exception);
    }
  }

  private void handleClusterConfigRefreshEvent(String sourceServerId, long version) {
    schedulerBridge.runGlobal(() -> {
      getLogger().info(messageService.formatConsole(
          "console.cluster_refresh_received",
          MapUtils.mapOf("source", sourceServerId, "version", version)));
      reloadRuntimeBusinessSettings();
    });
  }

  private void refreshMainConfig() {
    saveDefaultConfig();
    File configFile = new File(getDataFolder(), MAIN_CONFIG_RESOURCE);
    try {
      ConfigUpdater.update(this, MAIN_CONFIG_RESOURCE, configFile);
    } catch (IOException exception) {
      getLogger().log(Level.WARNING, messageService.getConsole("console.failed_update_main_config"), exception);
    }
    reloadConfig();
  }

  private void registerCommands() {
    ShopCommand shopCommandHandler = new ShopCommand(
        this,
        authService,
        rechargeService,
        adminService,
        marketService,
        marketGuiService,
        deliveryService,
        mailboxService,
        mailboxGuiService,
        runtimeConfigService,
        messageService,
        schedulerBridge,
        this::settings);
    PluginCommand rootCommand = getCommand("webshopx");
    if (rootCommand == null) {
      throw new IllegalStateException("Command 'webshopx' is not defined in plugin.yml");
    }
    rootCommand.setExecutor(shopCommandHandler);
    rootCommand.setTabCompleter(shopCommandHandler);
  }

  private void registerPaymentListener() {
    if (rechargeService == null) {
      return;
    }
    try {
      rechargeService.unregisterPaymentListener();
      rechargeService.registerPaymentListener();
      if (rechargeService.isPaymentAvailable()) {
        String providerId = paymentBridge == null
            ? "unknown"
            : paymentBridge.activeProviderId().orElse("unknown");
        getLogger().info("Payment provider detected; recharge listener registered: " + providerId);
      } else {
        getLogger().info("Payment provider is not available; recharge entry points will report unavailable.");
      }
    } catch (Exception exception) {
      getLogger().log(Level.WARNING, "Failed to register payment listener.", exception);
    }
  }

  private void synchronizeOnlinePresence() {
    if (playerPresenceService == null) {
      return;
    }
    for (Player player : getServer().getOnlinePlayers()) {
      try {
        playerPresenceService.markOnline(player.getUniqueId(), player.getName());
      } catch (Exception exception) {
        getLogger().warning(messageService.formatConsole("console.failed_sync_online_presence", MapUtils.mapOf("player", player.getName(), "reason", exception.getMessage())));
      }
    }
  }

  private void pruneLegacyBusinessConfigAndBackup() {
    File configFile = new File(getDataFolder(), MAIN_CONFIG_RESOURCE);
    if (!configFile.exists()) {
      return;
    }

    File backupFile = new File(getDataFolder(), "config.legacy.bak.yml");
    if (!backupFile.exists()) {
      try {
        Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        getLogger().info(messageService.formatConsole("console.legacy_backup_created", MapUtils.mapOf("name", backupFile.getName())));
      } catch (IOException exception) {
        getLogger().warning(messageService.formatConsole("console.failed_create_legacy_backup", MapUtils.mapOf("reason", exception.getMessage())));
      }
    }

    FileConfiguration config = getConfig();
    boolean changed = false;
    for (String path : minimalConfigRemovalPaths()) {
      changed |= clearPath(config, path);
    }

    if (!changed) {
      return;
    }
    saveConfig();
    reloadConfig();
    getLogger().info(messageService.getConsole("console.legacy_runtime_migrated"));
  }

  private String[] minimalConfigRemovalPaths() {
    return new String[] {
        // Keep startup/environment settings in config.yml, but move business/runtime
        // settings into DB once migration is completed.
        "webshop.default-locale",
        "webshop.session-expire-hours",
        "webshop.bind-request-expire-minutes",
        "webshop.access-token-length",
        "webshop.time-zone",
        "webshop.delivery-batch-size",
        "webshop.delivery-retry-seconds",
        "webshop.order-cooldown-seconds",
        "webshop.allow-shared-claim-command",
        "webshop.refund-undelivered-enabled",
        "webshop.market",
        "webshop.leaderboard",
        "webshop.broadcast",
        "webshop.maintenance",
        "webshop.logging",
        "exchange",
        "currency",
        "economy.market",
        "economy.inflation-control",
        "sample-products"
    };
  }

  private boolean clearPath(FileConfiguration config, String path) {
    if (!config.contains(path)) {
      return false;
    }
    config.set(path, null);
    return true;
  }

  private void startDeliveryLoop() {
    if (deliveryTask != null) {
      deliveryTask.cancel();
    }
    deliveryTask = schedulerBridge.runGlobalTimer(() -> deliveryService.processDueDeliveries(null), 40L, 100L);
  }

  private void startMaintenanceLoop() {
    if (maintenanceTask != null) {
      maintenanceTask.cancel();
      maintenanceTask = null;
    }
    if (maintenanceService == null) {
      return;
    }
    int intervalMinutes = settings.maintenanceSettings().cleanupIntervalMinutes();
    if (intervalMinutes <= 0) {
      return;
    }
    long intervalTicks = Math.max(20L, intervalMinutes * 1200L);
    maintenanceTask = schedulerBridge.runAsyncTimer(maintenanceService::runCleanup, 200L, intervalTicks);
  }

  private void startMarketCycleLoop() {
    if (marketCycleTask != null) {
      marketCycleTask.cancel();
      marketCycleTask = null;
    }
    if (marketService == null) {
      return;
    }
    marketCycleTask = schedulerBridge.runAsyncTimer(
        () -> {
          marketService.processMarketCycles();
          if (productService != null) {
            try {
              productService.processDynamicPriceCycles();
            } catch (Exception exception) {
              getLogger().warning(messageService.formatConsole("console.official_dynamic_price_cycle_failed", MapUtils.mapOf("reason", exception.getMessage())));
            }
          }
        },
        200L,
        6000L);
  }

  private void restartWebRuntime() {
    if (embeddedWebServer != null) {
      embeddedWebServer.stop();
    }
    if (!settings.clusterSettings().shouldStartWebApi()) {
      webStaticRoot = null;
      webUserRoot = null;
      getLogger().info(messageService.formatConsole(
          "console.cluster_web_api_disabled_on_role",
          MapUtils.mapOf("role", settings.clusterSettings().role().name().toLowerCase(Locale.ROOT))));
      return;
    }

    webStaticRoot = staticAssetInstaller.install(settings.embeddedWebSettings().staticRoot(), settings);
    webUserRoot = prepareUserWebRoot(webStaticRoot);
    textureAssetManager.ensureLocalTextureCacheAsync(webStaticRoot, resolveMinecraftVersion());
    if (settings.serverMode() == PluginSettings.ServerMode.EXTERNAL) {
      getLogger().info(messageService.formatConsole("console.server_mode_external", MapUtils.mapOf("path", webStaticRoot)));
    }

    try {
      embeddedWebServer.start(webStaticRoot, webUserRoot);
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to start embedded HTTP server", exception);
    }
  }

  RelaySetupService relaySetupService() {
    return relaySetupService;
  }

  void saveRelayAccessKey(String accessKey) {
    if (accessKey == null || accessKey.isBlank()) {
      throw new IllegalArgumentException("Relay access key is empty");
    }
    getConfig().set("relay.access-key", accessKey.trim());
    saveConfig();
    reloadRuntimeConfig();
  }

  void switchServerMode(String mode) {
    String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    if (!normalized.equals("relay") && !normalized.equals("internal") && !normalized.equals("external")) {
      throw new IllegalArgumentException("Unsupported server mode");
    }
    if (normalized.equals("relay") && settings.relaySettings().accessKey().isBlank()) {
      throw new IllegalStateException("Relay access key is not configured");
    }
    getConfig().set("webshop.server-mode", normalized);
    saveConfig();
    reloadRuntimeConfig();
  }

  private void restartRelayConnector() {
    stopRelayConnector();
    if (settings == null || settings.deploymentMode() != PluginSettings.DeploymentMode.RELAY) {
      return;
    }
    if (webStaticRoot == null || webUserRoot == null) {
      getLogger().warning("Relay is enabled, but web assets are not initialized; connector will stay offline.");
      return;
    }

    RelayRpcRouter rpcRouter = new RelayRpcRouter(
        this,
        this::settings,
        this::relayStatus,
        authService,
        productService,
        orderService,
        adminService,
        adminAuditService,
        visualCustomizationService,
        visualPackService,
        webStaticRoot,
        webUserRoot,
        new RelayLocalHttpBridge(
            settings.embeddedWebSettings(),
            settings.relaySettings().rpcTimeoutSeconds()));
    relayConnectorService = new RelayConnectorService(
        this,
        settings.relaySettings(),
        rpcRouter);
    relayConnectorService.start();
  }

  private void stopRelayConnector() {
    if (relayConnectorService == null) {
      return;
    }
    try {
      relayConnectorService.close();
    } catch (Exception exception) {
      getLogger().log(Level.WARNING, "Failed to stop relay connector.", exception);
    } finally {
      relayConnectorService = null;
    }
  }

  private RelayStatus relayStatus() {
    if (relayConnectorService != null) {
      return relayConnectorService.status();
    }
    return RelayStatus.disabled(settings == null ? null : settings.relaySettings());
  }

  private Path prepareUserWebRoot(Path staticRoot) {
    Path userWebRoot = getDataFolder().toPath().resolve(USER_WEB_ROOT).normalize();
    try {
      Files.createDirectories(userWebRoot);
      migrateLegacyUserWebAssets(staticRoot, userWebRoot);
      return userWebRoot;
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to prepare user web root", exception);
    }
  }

  private void migrateLegacyUserWebAssets(Path staticRoot, Path userWebRoot) throws IOException {
    Path marker = userWebRoot.resolve(USER_WEB_MIGRATION_MARKER).normalize();
    if (!marker.startsWith(userWebRoot) || Files.exists(marker)) {
      return;
    }

    copyTreeIfPresent(staticRoot.resolve("themes"), userWebRoot.resolve("themes"));
    copyTreeIfPresent(staticRoot.resolve("i18n"), userWebRoot.resolve("i18n"));
    copyTreeIfPresent(staticRoot.resolve("uploads"), userWebRoot.resolve("uploads"));

    Files.writeString(marker, "ok", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private void copyTreeIfPresent(Path sourceRoot, Path targetRoot) throws IOException {
    if (sourceRoot == null || targetRoot == null || !Files.isDirectory(sourceRoot)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(sourceRoot)) {
      for (Path source : (Iterable<Path>) stream::iterator) {
        Path relative = sourceRoot.relativize(source);
        Path target = targetRoot.resolve(relative).normalize();
        if (!target.startsWith(targetRoot)) {
          continue;
        }
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
          continue;
        }
        if (!Files.isRegularFile(source) || Files.exists(target)) {
          continue;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
      }
    }
  }

  private void initializeDatabase() {
    databaseManager = new DatabaseManager(getLogger(), settings.databaseSettings());
    try {
      databaseManager.start();
      SchemaProvider.forType(settings.databaseSettings().type())
          .ensureSchema(databaseManager, settings.timeZone());
    } catch (Exception exception) {
      if (settings.databaseSettings().usesDefaultPlaceholders()) {
        if (databaseManager != null) {
          databaseManager.close();
          databaseManager = null;
        }
        throw new DefaultDatabaseConfigurationException(exception);
      }
      throw exception;
    }
  }

  private void enforceDatabaseModeGuard() {
    if (!settings.databaseSettings().type().isSqlite()) {
      return;
    }
    if (settings.clusterSettings().role() == PluginSettings.ClusterRole.STANDALONE) {
      return;
    }
    throw new IllegalStateException(
        "SQLite only supports cluster.role=standalone. Current role is "
            + settings.clusterSettings().role().name().toLowerCase(Locale.ROOT));
  }

  private void initializeMetrics() {
    try {
      metrics = new Metrics(this, BSTATS_PLUGIN_ID);
      metrics.addCustomChart(new SimplePie("server_mode", () -> settings.serverMode().name().toLowerCase(Locale.ROOT)));
      metrics.addCustomChart(new SimplePie("deployment_mode", () -> settings.deploymentMode().configValue()));
      metrics.addCustomChart(
          new SimplePie("cluster_role", () -> settings.clusterSettings().role().name().toLowerCase(Locale.ROOT)));
      metrics.addCustomChart(new SimplePie("default_locale", settings::defaultLocale));
      metrics.addCustomChart(new SimplePie("database_type", () -> snapshotOrFallback().databaseType()));
      metrics.addCustomChart(new SimplePie("cluster_enabled", () -> snapshotOrFallback().clusterEnabled()));
      metrics.addCustomChart(new SimplePie("cluster_node_scale_bucket", () -> snapshotOrFallback().clusterNodeScaleBucket()));
      metrics.addCustomChart(new SimplePie("web_management_enabled", () -> snapshotOrFallback().webManagementEnabled()));
      metrics.addCustomChart(
          new SimplePie("embedded_web_server_enabled", () -> snapshotOrFallback().embeddedWebServerEnabled()));

      metrics.addCustomChart(new SingleLineChart("official_shop_product_count", () -> snapshotOrFallback().officialProductCount()));
      metrics.addCustomChart(new AdvancedPie("official_shop_product_types", () -> snapshotOrFallback().officialProductTypeCounts()));

      metrics.addCustomChart(
          new SingleLineChart("player_market_sell_listing_count", () -> snapshotOrFallback().playerMarketSellCount()));
      metrics.addCustomChart(
          new SingleLineChart("player_market_recycle_listing_count", () -> snapshotOrFallback().playerMarketRecycleCount()));
      metrics.addCustomChart(
          new SingleLineChart("player_market_seller_count", () -> snapshotOrFallback().playerMarketSellerCount()));
      metrics.addCustomChart(new SingleLineChart("auction_listing_count", () -> snapshotOrFallback().auctionListingCount()));

      metrics.addCustomChart(new SingleLineChart("trade_volume_30d", () -> snapshotOrFallback().tradeVolume30d()));
      metrics.addCustomChart(new AdvancedPie("trade_volume_breakdown_30d", () -> snapshotOrFallback().tradeVolumeBreakdown30d()));
      metrics.addCustomChart(new MultiLineChart("order_funnel", () -> snapshotOrFallback().orderFunnel()));
      metrics.addCustomChart(new MultiLineChart("market_liquidity_24h", () -> snapshotOrFallback().marketLiquidity()));
      metrics.addCustomChart(new AdvancedPie("economy_flow_30d", () -> snapshotOrFallback().economyFlow30d()));
      metrics.addCustomChart(new SimplePie("economy_net_direction_30d", () -> snapshotOrFallback().economyNetDirection30d()));
      metrics.addCustomChart(new SingleLineChart("economy_net_delta_abs_30d", () -> snapshotOrFallback().economyNetDeltaAbs30d()));
      metrics.addCustomChart(new AdvancedPie("retry_distribution", () -> snapshotOrFallback().retryDistribution()));
      metrics.addCustomChart(new AdvancedPie("backlog_distribution", () -> snapshotOrFallback().backlogDistribution()));
      metrics.addCustomChart(new SingleLineChart("idempotency_hit_rate_7d", () -> snapshotOrFallback().idempotencyHitRate7d()));
      metrics.addCustomChart(new AdvancedPie("idempotency_details_7d", () -> snapshotOrFallback().idempotencyDetails7d()));
      metrics.addCustomChart(new AdvancedPie("shopcoin_distribution", () -> snapshotOrFallback().shopCoinDistribution()));
      metrics.addCustomChart(new AdvancedPie("gamecoin_distribution", () -> snapshotOrFallback().gameCoinDistribution()));
      metrics.addCustomChart(new AdvancedPie("total_economy_distribution", () -> snapshotOrFallback().totalEconomyDistribution()));
      metrics.addCustomChart(new SingleLineChart("shopcoin_total_stock", () -> snapshotOrFallback().totalShopCoin()));
      metrics.addCustomChart(new SingleLineChart("gamecoin_total_stock", () -> snapshotOrFallback().totalGameCoin()));

      metrics.addCustomChart(new SimplePie("vault_hooked", () -> snapshotOrFallback().vaultHooked()));
      metrics.addCustomChart(new SimplePie("vault_provider_type", () -> snapshotOrFallback().vaultProviderType()));

      metrics.addCustomChart(new AdvancedPie("active_locales_7d", () -> snapshotOrFallback().localeUsage7d()));
      metrics.addCustomChart(new MultiLineChart("user_retention_signals", () -> snapshotOrFallback().retentionSignals()));

      metrics.addCustomChart(new SimplePie("startup_last_result", () -> snapshotOrFallback().startupLastResult()));
      metrics.addCustomChart(new SimplePie("startup_last_failure_signal", () -> snapshotOrFallback().startupLastFailure()));
      metrics.addCustomChart(new MultiLineChart("startup_signals_7d", () -> snapshotOrFallback().startupSignals7d()));

      metrics.addCustomChart(new SingleLineChart("api_request_total_7d", () -> snapshotOrFallback().apiRequestTotal7d()));
      metrics.addCustomChart(new AdvancedPie("api_usage_7d", () -> snapshotOrFallback().apiUsage7d()));
      metrics.addCustomChart(new AdvancedPie("api_latency_buckets_7d", () -> snapshotOrFallback().apiLatencyBuckets7d()));
      metrics.addCustomChart(new SimplePie("api_latency_p50_bucket_7d", () -> snapshotOrFallback().apiLatencyP50Bucket7d()));
      metrics.addCustomChart(new SimplePie("api_latency_p95_bucket_7d", () -> snapshotOrFallback().apiLatencyP95Bucket7d()));
      metrics.addCustomChart(new SingleLineChart("api_latency_p50_estimate_ms_7d", () -> snapshotOrFallback().apiLatencyP50EstimateMs7d()));
      metrics.addCustomChart(new SingleLineChart("api_latency_p95_estimate_ms_7d", () -> snapshotOrFallback().apiLatencyP95EstimateMs7d()));
      metrics.addCustomChart(new AdvancedPie("api_error_distribution_7d", () -> snapshotOrFallback().apiErrorDistribution7d()));

      metrics.addCustomChart(new SingleLineChart("active_admin_count", () -> snapshotOrFallback().activeAdminCount()));
      metrics.addCustomChart(new AdvancedPie("admin_role_distribution", () -> snapshotOrFallback().adminRoleDistribution()));
      metrics.addCustomChart(
          new AdvancedPie("admin_permission_distribution", () -> snapshotOrFallback().adminPermissionDistribution()));
      getLogger().info(messageService.getConsole("console.bstats_enabled"));
    } catch (Throwable exception) {
      if (isMissingBStats(exception) || isRelocationGuard(exception)) {
        getLogger().info("bStats is unavailable in this build variant; metrics will be skipped.");
        return;
      }
      getLogger().log(Level.WARNING, messageService.getConsole("console.failed_init_bstats"), exception);
    }
  }

  private boolean isMissingBStats(Throwable throwable) {
    return throwable instanceof NoClassDefFoundError
        || throwable instanceof ClassNotFoundException
        || containsTypeOrMessage(throwable, "org.bstats");
  }

  private boolean isRelocationGuard(Throwable throwable) {
    return containsTypeOrMessage(throwable, "has not been relocated correctly");
  }

  private boolean containsTypeOrMessage(Throwable throwable, String needle) {
    String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
    Throwable current = throwable;
    while (current != null) {
      String type = current.getClass().getName().toLowerCase(Locale.ROOT);
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
      if (type.contains(normalizedNeedle) || message.contains(normalizedNeedle)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private PluginSettings settings() {
    return settings;
  }

  private BStatsTelemetryService.Snapshot snapshotOrFallback() {
    if (bStatsTelemetryService == null) {
      return fallbackSnapshot();
    }
    long now = System.currentTimeMillis();
    BStatsTelemetryService.Snapshot cached = telemetrySnapshotCache;
    if (cached != null && now - telemetrySnapshotAtMillis <= 60_000L) {
      return cached;
    }
    synchronized (this) {
      cached = telemetrySnapshotCache;
      if (cached != null && now - telemetrySnapshotAtMillis <= 60_000L) {
        return cached;
      }
      try {
        BStatsTelemetryService.Snapshot snapshot = bStatsTelemetryService.captureSnapshot(walletService);
        telemetrySnapshotCache = snapshot;
        telemetrySnapshotAtMillis = now;
        return snapshot;
      } catch (Exception exception) {
        getLogger().log(Level.FINE, "Failed to capture bStats telemetry snapshot; using fallback values.", exception);
        return fallbackSnapshot();
      }
    }
  }

  private BStatsTelemetryService.Snapshot fallbackSnapshot() {
    return new BStatsTelemetryService.Snapshot(
        settings.databaseSettings().type().name().toLowerCase(Locale.ROOT),
        settings.clusterSettings().role() == PluginSettings.ClusterRole.STANDALONE ? "disabled" : "enabled",
        "1",
        settings.clusterSettings().shouldStartWebApi() ? "enabled" : "disabled",
        settings.serverMode() == PluginSettings.ServerMode.INTERNAL ? "enabled" : "disabled",
        0,
        Map.of("no_data", 1),
        0,
        0,
        0,
        0,
        0,
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        "unknown",
        0,
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        0,
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        0,
        0,
        "unknown",
        "unknown",
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        0,
        Map.of("no_data", 1),
        Map.of("no_data", 1),
        "unknown",
        "unknown",
        0,
        0,
        Map.of("no_data", 1),
        "unknown",
        "none",
        0,
        Map.of("no_data", 1),
        Map.of("no_data", 1));
  }

  private String resolveMinecraftVersion() {
    String version = getServer().getMinecraftVersion();
    if (version != null && !version.isBlank()) {
      return version;
    }
    String bukkitVersion = getServer().getBukkitVersion();
    if (bukkitVersion == null || bukkitVersion.isBlank()) {
      return "1.21.10";
    }
    int separator = bukkitVersion.indexOf('-');
    if (separator <= 0) {
      return bukkitVersion;
    }
    return bukkitVersion.substring(0, separator);
  }

  private static final class DefaultDatabaseConfigurationException extends RuntimeException {
    DefaultDatabaseConfigurationException(Throwable cause) {
      super(cause);
    }
  }
}
