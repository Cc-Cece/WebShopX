package com.webshopx;

import com.tchristofferson.configupdater.ConfigUpdater;
import java.io.File;
import java.io.IOException;
import io.papermc.lib.PaperLib;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.command.PluginCommand;
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
  private AuthService authService;
  private WalletService walletService;
  private MessageService messageService;
  private RedeemCodeService redeemCodeService;
  private ProductService productService;
  private OrderService orderService;
  private MarketService marketService;
  private MarketGuiService marketGuiService;
  private DeliveryService deliveryService;
  private AdminService adminService;
  private AdminAuditService adminAuditService;
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
    PaperLib.suggestPaper(this);
    refreshMainConfig();

    try {
      settings = PluginSettings.fromConfig(getConfig());
      pluginLogService = new PluginLogService(this);
      pluginLogService.apply(settings.loggingSettings());
      staticAssetInstaller = new StaticAssetInstaller(this);
      textureAssetManager = new TextureAssetManager(this);
      messageService = new MessageService(this, this::settings);

      initializeDatabase();

      authService = new AuthService(databaseManager, this::settings);
      walletService = new WalletService(this, databaseManager, this::settings);
      redeemCodeService = new RedeemCodeService(databaseManager, walletService);
      productService = new ProductService(databaseManager);
      orderService = new OrderService(this, databaseManager, this::settings, productService, walletService);
      marketService = new MarketService(this, databaseManager, walletService, this::settings, messageService);
      marketGuiService = new MarketGuiService(marketService, this::settings, messageService);
      deliveryService = new DeliveryService(this, databaseManager, walletService, this::settings, messageService);
      adminService = new AdminService(databaseManager, authService, walletService);
      adminAuditService = new AdminAuditService(databaseManager);
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
          adminService,
          adminAuditService);

      // Products are managed via admin backend; no seed import from config.
      adminService.ensureBootstrapAdmin(settings.adminBootstrapSettings());
      if (settings.redisSettings().enabled()) {
        getLogger().warning("Redis is enabled in config but currently optional and not wired in V1.");
      }

      registerCommands();
      getServer().getPluginManager().registerEvents(
          new PlayerJoinListener(this, deliveryService),
          this);
      getServer().getPluginManager().registerEvents(
          new MarketGuiListener(marketGuiService, marketService, messageService),
          this);
      startDeliveryLoop();
      startMaintenanceLoop();
      startMarketCycleLoop();
      restartWebRuntime();
      initializeMetrics();

      getLogger().info("WebShopX enabled successfully.");
    } catch (DefaultDatabaseConfigurationException exception) {
      getLogger().warning("WebShopX detected the default database configuration in config.yml.");
      getLogger().warning("Please update the database connection settings and start the server again.");
      getServer().getPluginManager().disablePlugin(this);
    } catch (Exception exception) {
      getLogger().log(Level.SEVERE, "WebShopX failed to start", exception);
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
    if (databaseManager != null) {
      databaseManager.close();
    }
    if (pluginLogService != null) {
      pluginLogService.close();
    }
  }

  void reloadRuntimeConfig() {
    refreshMainConfig();
    settings = PluginSettings.fromConfig(getConfig());
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
    startMaintenanceLoop();
    startMarketCycleLoop();
    restartWebRuntime();
  }

  private void refreshMainConfig() {
    saveDefaultConfig();
    File configFile = new File(getDataFolder(), MAIN_CONFIG_RESOURCE);
    try {
      ConfigUpdater.update(this, MAIN_CONFIG_RESOURCE, configFile);
    } catch (IOException exception) {
      getLogger().log(Level.WARNING, "Failed to update config.yml via Config-Updater.", exception);
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
        messageService);
    PluginCommand rootCommand = getCommand("webshopx");
    if (rootCommand == null) {
      throw new IllegalStateException("Command 'webshopx' is not defined in plugin.yml");
    }
    rootCommand.setExecutor(shopCommandHandler);
    rootCommand.setTabCompleter(shopCommandHandler);
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
              getLogger().warning("Official dynamic price cycle failed: " + exception.getMessage());
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

    Path staticRoot = staticAssetInstaller.install(settings.embeddedWebSettings().staticRoot(), settings);
    textureAssetManager.ensureLocalTextureCacheAsync(staticRoot, resolveMinecraftVersion());
    if (settings.serverMode() == PluginSettings.ServerMode.EXTERNAL) {
      getLogger().info("server-mode=external, static files extracted to: " + staticRoot);
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
      new SchemaManager().ensureSchema(databaseManager, settings);
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

  private void initializeMetrics() {
    try {
      metrics = new Metrics(this, BSTATS_PLUGIN_ID);
      metrics.addCustomChart(new SimplePie("server_mode", () -> settings.serverMode().name().toLowerCase(Locale.ROOT)));
      metrics.addCustomChart(new SimplePie("default_locale", settings::defaultLocale));
      getLogger().info("bStats metrics enabled.");
    } catch (Exception exception) {
      getLogger().log(Level.WARNING, "Failed to initialize bStats metrics.", exception);
    }
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
