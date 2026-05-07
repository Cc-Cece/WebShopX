package com.webshopx;

import com.tchristofferson.configupdater.ConfigUpdater;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.configuration.file.FileConfiguration;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main entry point for the WebShop plugin.
 */
public class WebShopPlugin extends JavaPlugin {
  private static final String MAIN_CONFIG_RESOURCE = "config.yml";
  private static final int BSTATS_PLUGIN_ID = 30746;

  private PluginSettings settings;
  private DatabaseManager databaseManager;
  private RuntimeConfigService runtimeConfigService;
  private PlayerPresenceService playerPresenceService;
  private ClusterEventBusService clusterEventBusService;
  private AuthService authService;
  private WalletService walletService;
  private MessageService messageService;
  private RedeemCodeService redeemCodeService;
  private ProductService productService;
  private OrderService orderService;
  private MarketService marketService;
  private MaterialVisualService materialVisualService;
  private VisualCustomizationService visualCustomizationService;
  private UserMarketSettingsService userMarketSettingsService;
  private NotificationService notificationService;
  private MailboxService mailboxService;
  private BroadcastService broadcastService;
  private MarketGuiService marketGuiService;
  private DeliveryService deliveryService;
  private AdminService adminService;
  private AdminAuditService adminAuditService;
  private LeaderboardService leaderboardService;
  private EmbeddedWebServer embeddedWebServer;
  private StaticAssetInstaller staticAssetInstaller;
  private TextureAssetManager textureAssetManager;
  private MaintenanceService maintenanceService;
  private PluginLogService pluginLogService;
  private Metrics metrics;
  private BukkitTask deliveryTask;
  private BukkitTask maintenanceTask;
  private BukkitTask marketCycleTask;

  @Override
  public void onEnable() {
    refreshMainConfig();

    try {
      settings = PluginSettings.fromConfig(getConfig());
      pluginLogService = new PluginLogService(this);
      pluginLogService.apply(settings.loggingSettings());
      staticAssetInstaller = new StaticAssetInstaller(this);
      textureAssetManager = new TextureAssetManager(this);
      messageService = new MessageService(this, this::settings);

      enforceDatabaseModeGuard();
      initializeDatabase();
      runtimeConfigService = new RuntimeConfigService(databaseManager);
      boolean migratedLegacyConfig = runtimeConfigService.bootstrapFromLegacyConfigIfNeeded(settings);
      if (migratedLegacyConfig || runtimeConfigService.isLegacyMigrationCompleted()) {
        pruneLegacyBusinessConfigAndBackup();
      }
      runtimeConfigService.ensureDefaults(settings);
      settings = runtimeConfigService.applyTo(settings);
      playerPresenceService = new PlayerPresenceService(databaseManager, this::settings);
      clusterEventBusService = new ClusterEventBusService(
          this,
          this::settings,
          this::handleClusterConfigRefreshEvent);

      authService = new AuthService(databaseManager, this::settings);
      walletService = new WalletService(this, databaseManager, this::settings);
      redeemCodeService = new RedeemCodeService(databaseManager, walletService);
      productService = new ProductService(databaseManager);
      orderService = new OrderService(
          this,
          databaseManager,
          this::settings,
          productService,
          walletService,
          playerPresenceService);
      notificationService = new NotificationService(databaseManager);
      mailboxService = new MailboxService(databaseManager);
      broadcastService = new BroadcastService(this, this::settings);
      broadcastService.reload();
      clusterEventBusService.reload();
      userMarketSettingsService = new UserMarketSettingsService(databaseManager);
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
          userMarketSettingsService);
      materialVisualService = new MaterialVisualService(databaseManager);
      visualCustomizationService = new VisualCustomizationService(databaseManager);
      marketGuiService = new MarketGuiService(marketService, this::settings, messageService);
      deliveryService = new DeliveryService(
          this,
          databaseManager,
          walletService,
          runtimeConfigService,
          this::settings,
          messageService,
          notificationService,
          mailboxService);
      adminService = new AdminService(databaseManager, authService, walletService);
      adminAuditService = new AdminAuditService(databaseManager);
          leaderboardService = new LeaderboardService(this, databaseManager);
      maintenanceService = new MaintenanceService(this, databaseManager, this::settings, pluginLogService);
      embeddedWebServer = new EmbeddedWebServer(
          this,
          this::settings,
          authService,
          walletService,
          redeemCodeService,
          productService,
          orderService,
          marketService,
          notificationService,
          adminService,
          adminAuditService,
          leaderboardService,
          materialVisualService,
          visualCustomizationService,
          userMarketSettingsService,
          runtimeConfigService,
          clusterEventBusService);

      // Products are managed via admin backend; no seed import from config.
      adminService.ensureBootstrapAdmin(settings.adminBootstrapSettings());

      registerCommands();
      getServer().getPluginManager().registerEvents(
          new PlayerJoinListener(this, deliveryService, playerPresenceService),
          this);
      getServer().getPluginManager().registerEvents(
          new PlayerQuitListener(this, playerPresenceService),
          this);
      getServer().getPluginManager().registerEvents(
          new MarketGuiListener(marketGuiService, marketService, messageService),
          this);
      synchronizeOnlinePresence();
      startDeliveryLoop();
      startMaintenanceLoop();
      startMarketCycleLoop();
      restartWebRuntime();
      initializeMetrics();

      getLogger().info(messageService.getConsole("console.enabled_success"));
    } catch (DefaultDatabaseConfigurationException exception) {
      getLogger().warning(messageService.getConsole("console.default_database_config"));
      getLogger().warning(messageService.getConsole("console.update_database_instructions"));
      getServer().getPluginManager().disablePlugin(this);
    } catch (Exception exception) {
      getLogger().log(Level.SEVERE, messageService.getConsole("console.failed_startup"), exception);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
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
    if (walletService != null) {
      walletService.refreshVaultHook();
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
      if (marketService != null) {
        marketService.refreshRuntimePolicies();
      }
      startMaintenanceLoop();
      startMarketCycleLoop();
      restartWebRuntime();
    } catch (Exception exception) {
      getLogger().log(Level.WARNING, messageService.getConsole("console.failed_reload_runtime_business_settings"), exception);
    }
  }

  private void handleClusterConfigRefreshEvent(String sourceServerId, long version) {
    getServer().getScheduler().runTask(this, () -> {
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
        redeemCodeService,
        marketService,
        marketGuiService,
        deliveryService,
        mailboxService,
        messageService);
    PluginCommand rootCommand = getCommand("webshopx");
    if (rootCommand == null) {
      throw new IllegalStateException("Command 'webshopx' is not defined in plugin.yml");
    }
    rootCommand.setExecutor(shopCommandHandler);
    rootCommand.setTabCompleter(shopCommandHandler);
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
    deliveryTask = getServer().getScheduler().runTaskTimer(
        this,
        () -> deliveryService.processDueDeliveries(null),
        40L,
        100L);
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
    maintenanceTask = getServer().getScheduler().runTaskTimerAsynchronously(
        this,
        maintenanceService::runCleanup,
        200L,
        intervalTicks);
  }

  private void startMarketCycleLoop() {
    if (marketCycleTask != null) {
      marketCycleTask.cancel();
      marketCycleTask = null;
    }
    if (marketService == null) {
      return;
    }
    marketCycleTask = getServer().getScheduler().runTaskTimerAsynchronously(
        this,
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
      getLogger().info(messageService.formatConsole(
          "console.cluster_web_api_disabled_on_role",
          MapUtils.mapOf("role", settings.clusterSettings().role().name().toLowerCase(Locale.ROOT))));
      return;
    }

    Path staticRoot = staticAssetInstaller.install(settings.embeddedWebSettings().staticRoot(), settings);
    textureAssetManager.ensureLocalTextureCacheAsync(staticRoot, resolveMinecraftVersion());
    if (settings.serverMode() == PluginSettings.ServerMode.EXTERNAL) {
      getLogger().info(messageService.formatConsole("console.server_mode_external", MapUtils.mapOf("path", staticRoot)));
    }

    try {
      embeddedWebServer.start(staticRoot);
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to start embedded HTTP server", exception);
    }
  }

  private void initializeDatabase() {
    databaseManager = new DatabaseManager(this, settings.databaseSettings());
    try {
      databaseManager.start();
      SchemaProvider.forType(settings.databaseSettings().type()).ensureSchema(databaseManager, settings);
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
      metrics.addCustomChart(
          new SimplePie("cluster_role", () -> settings.clusterSettings().role().name().toLowerCase(Locale.ROOT)));
      metrics.addCustomChart(new SimplePie("default_locale", settings::defaultLocale));
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
