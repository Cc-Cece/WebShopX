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
  private PluginSettings settings;
  private DatabaseManager databaseManager;
  private AuthService authService;
  private WalletService walletService;
  private BindingService bindingService;
  private RedeemCodeService redeemCodeService;
  private ProductService productService;
  private OrderService orderService;
  private MarketService marketService;
  private DeliveryService deliveryService;
  private EmbeddedWebServer embeddedWebServer;
  private StaticAssetInstaller staticAssetInstaller;
  private BukkitTask deliveryTask;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);
    saveDefaultConfig();

    try {
      settings = PluginSettings.fromConfig(getConfig());
      staticAssetInstaller = new StaticAssetInstaller(this);

      databaseManager = new DatabaseManager(this, settings.databaseSettings());
      databaseManager.start();
      new SchemaManager().ensureSchema(databaseManager);

      authService = new AuthService(databaseManager, this::settings);
      walletService = new WalletService(databaseManager, this::settings);
      bindingService = new BindingService(databaseManager, this::settings);
      redeemCodeService = new RedeemCodeService(databaseManager, walletService);
      productService = new ProductService(databaseManager);
      orderService = new OrderService(databaseManager, productService, walletService);
      marketService = new MarketService(databaseManager, walletService);
      deliveryService = new DeliveryService(this, databaseManager, this::settings);
      embeddedWebServer = new EmbeddedWebServer(
          this,
          this::settings,
          authService,
          bindingService,
          walletService,
          redeemCodeService,
          productService,
          orderService,
          marketService);

      productService.upsertSeeds(settings.productSeeds());
      if (settings.redisSettings().enabled()) {
        getLogger().warning("Redis is enabled in config but currently optional and not wired in V1.");
      }

      registerCommands();
      getServer().getPluginManager().registerEvents(
          new PlayerJoinListener(this, deliveryService),
          this);
      startDeliveryLoop();
      restartWebRuntime();

      getLogger().info("WebShopPlugin enabled successfully.");
    } catch (Exception exception) {
      getLogger().log(Level.SEVERE, "WebShopPlugin failed to start", exception);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (deliveryTask != null) {
      deliveryTask.cancel();
      deliveryTask = null;
    }
    if (embeddedWebServer != null) {
      embeddedWebServer.stop();
    }
    if (databaseManager != null) {
      databaseManager.close();
    }
  }

  void reloadRuntimeConfig() {
    reloadConfig();
    settings = PluginSettings.fromConfig(getConfig());
    productService.upsertSeeds(settings.productSeeds());
    restartWebRuntime();
  }

  private void registerCommands() {
    ShopCommand shopCommandHandler = new ShopCommand(this, bindingService, redeemCodeService);
    PluginCommand shopCommand = getCommand("shop");
    if (shopCommand == null) {
      throw new IllegalStateException("Command 'shop' is not defined in plugin.yml");
    }
    shopCommand.setExecutor(shopCommandHandler);
    shopCommand.setTabCompleter(shopCommandHandler);

    PluginCommand marketCommand = getCommand("market");
    if (marketCommand == null) {
      throw new IllegalStateException("Command 'market' is not defined in plugin.yml");
    }
    marketCommand.setExecutor(new MarketCommand(marketService));
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

  private void restartWebRuntime() {
    if (embeddedWebServer != null) {
      embeddedWebServer.stop();
    }

    Path staticRoot = staticAssetInstaller.install(settings.embeddedWebSettings().staticRoot());
    if (settings.webMode() == PluginSettings.WebMode.NGINX_ONLY) {
      getLogger().info("web.mode=nginx_only, static files extracted to: " + staticRoot);
      return;
    }

    try {
      embeddedWebServer.start(staticRoot);
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to start embedded HTTP server", exception);
    }
  }

  private PluginSettings settings() {
    return settings;
  }
}
