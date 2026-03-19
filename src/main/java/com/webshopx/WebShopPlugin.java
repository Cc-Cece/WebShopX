package com.webshopx;

import io.papermc.lib.PaperLib;
import java.nio.file.Path;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main entry point for the WebShop plugin.
 */
public class WebShopPlugin extends JavaPlugin {
  private DatabaseType databaseType;
  private PluginSettings settings;
  private DatabaseManager databaseManager;
  private AuthService authService;
  private WalletService walletService;
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
  private BukkitTask deliveryTask;
  private BukkitTask maintenanceTask;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);
    saveDefaultConfig();

    try {
      databaseType = DatabaseType.detect(this);
      settings = PluginSettings.fromConfig(getConfig(), databaseType);
      pluginLogService = new PluginLogService(this);
      pluginLogService.apply(settings.loggingSettings());
      staticAssetInstaller = new StaticAssetInstaller(this);
      textureAssetManager = new TextureAssetManager(this);

      initializeDatabase();

      authService = new AuthService(databaseManager, this::settings);
      walletService = new WalletService(this, databaseManager, this::settings);
      redeemCodeService = new RedeemCodeService(databaseManager, walletService);
      productService = new ProductService(databaseManager);
      orderService = new OrderService(this, databaseManager, this::settings, productService, walletService);
      marketService = new MarketService(this, databaseManager, walletService, this::settings);
      marketGuiService = new MarketGuiService(marketService, this::settings);
      deliveryService = new DeliveryService(this, databaseManager, walletService, this::settings);
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
      getServer().getPluginManager().registerEvents(new MarketGuiListener(marketGuiService, marketService), this);
      startDeliveryLoop();
      startMaintenanceLoop();
      restartWebRuntime();

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
    reloadConfig();
    settings = PluginSettings.fromConfig(getConfig(), databaseType);
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
    restartWebRuntime();
  }

  private void registerCommands() {
    ShopCommand shopCommandHandler = new ShopCommand(
        this,
        authService,
        redeemCodeService,
        marketService,
        marketGuiService,
        deliveryService);
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

  private void restartWebRuntime() {
    if (embeddedWebServer != null) {
      embeddedWebServer.stop();
    }

    Path staticRoot = staticAssetInstaller.install(settings.embeddedWebSettings().staticRoot());
    textureAssetManager.ensureLocalTextureCacheAsync(staticRoot, resolveMinecraftVersion());
    if (settings.serverMode() == PluginSettings.ServerMode.EXTERNAL) {
      getLogger().info("server-mode=external, static files extracted to: " + staticRoot);
      return;
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
      databaseManager.ensureSchema();
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
