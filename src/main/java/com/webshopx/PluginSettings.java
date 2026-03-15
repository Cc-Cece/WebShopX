package com.webshopx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

record PluginSettings(
    ServerMode serverMode,
    int sessionExpireHours,
    int bindRequestExpireMinutes,
    int accessTokenLength,
    int deliveryBatchSize,
    int deliveryRetrySeconds,
    int orderCooldownSeconds,
    int marketMaxActiveListings,
    CurrencyDisplaySettings currencyDisplaySettings,
    MaintenanceSettings maintenanceSettings,
    AdminBootstrapSettings adminBootstrapSettings,
    EmbeddedWebSettings embeddedWebSettings,
    DatabaseSettings databaseSettings,
    ExchangeSettings exchangeSettings,
    EconomySettings economySettings,
    RedisSettings redisSettings,
    List<ProductSeed> productSeeds) {

  static PluginSettings fromConfig(FileConfiguration config) {
    String rawMode = config.getString("webshop.server-mode", "internal");
    ServerMode mode = ServerMode.fromRaw(rawMode);

    EmbeddedWebSettings webSettings = new EmbeddedWebSettings(
        config.getString("webshop.embedded-http.host", "0.0.0.0"),
        config.getInt("webshop.embedded-http.port", 8819),
        config.getString("webshop.embedded-http.static-root", "web"));

    DatabaseSettings databaseSettings = new DatabaseSettings(
        config.getString("database.host", "127.0.0.1"),
        config.getInt("database.port", 3306),
        config.getString("database.schema", "webshop"),
        config.getString("database.username", "webshop"),
        config.getString("database.password", "change_me"),
        config.getBoolean("database.use-ssl", false),
        config.getInt("database.pool-size", 10));

    ExchangeDirection shopToGame = new ExchangeDirection(
        config.getBoolean("exchange.shopcoin-to-gamecoin.enabled", true),
        config.getDouble("exchange.shopcoin-to-gamecoin.ratio", 1.0));
    ExchangeDirection gameToShop = new ExchangeDirection(
        config.getBoolean("exchange.gamecoin-to-shopcoin.enabled", false),
        config.getDouble("exchange.gamecoin-to-shopcoin.ratio", 1.0));

    RedisSettings redisSettings = new RedisSettings(
        config.getBoolean("redis.enabled", false),
        config.getString("redis.host", "127.0.0.1"),
        config.getInt("redis.port", 6379));

    AdminBootstrapSettings adminBootstrapSettings = new AdminBootstrapSettings(
        config.getBoolean("webshop.admin-bootstrap.enabled", true),
        config.getString("webshop.admin-bootstrap.username", "admin"),
        config.getString("webshop.admin-bootstrap.password", "admin123456"),
        config.getString("webshop.admin-bootstrap.role", "SUPER_ADMIN"));

    CurrencyDisplaySettings currencyDisplaySettings = new CurrencyDisplaySettings(
        config.getString("currency.shopcoin.name", "ShopCoin"),
        config.getString("currency.shopcoin.short", "SC"),
        config.getString("currency.gamecoin.name", "GameCoin"),
        config.getString("currency.gamecoin.short", "GC"));

    MarketEconomySettings marketEconomySettings = new MarketEconomySettings(
        config.getDouble("economy.market.trade-fee-percent", 0.0),
        config.getDouble("economy.market.trade-tax-percent", 0.0));
    InflationSettings inflationSettings = new InflationSettings(
        InflationMode.fromRaw(config.getString("economy.inflation-control.mode", "burn")),
        config.getLong("economy.inflation-control.treasury-user-id", 0L));

    MaintenanceSettings maintenanceSettings = new MaintenanceSettings(
        config.getInt("webshop.maintenance.cleanup-interval-minutes", 30),
        config.getInt("webshop.maintenance.pending-bind-retention-hours", 6),
        config.getInt("webshop.maintenance.pending-password-retention-hours", 6),
        config.getInt("webshop.maintenance.bind-request-retention-hours", 24),
        config.getInt("webshop.maintenance.redeem-code-retention-days", 7));

    return new PluginSettings(
        mode,
        config.getInt("webshop.session-expire-hours", 72),
        config.getInt("webshop.bind-request-expire-minutes", 15),
        config.getInt("webshop.access-token-length", 48),
        config.getInt("webshop.delivery-batch-size", 20),
        config.getInt("webshop.delivery-retry-seconds", 30),
        config.getInt("webshop.order-cooldown-seconds", 15),
        config.getInt("webshop.market.max-active-listings", 10),
        currencyDisplaySettings,
        maintenanceSettings,
        adminBootstrapSettings,
        webSettings,
        databaseSettings,
        new ExchangeSettings(shopToGame, gameToShop),
        new EconomySettings(marketEconomySettings, inflationSettings),
        redisSettings,
        readProductSeeds(config));
  }

  private static List<ProductSeed> readProductSeeds(FileConfiguration config) {
    List<ProductSeed> seeds = new ArrayList<>();
    for (Map<?, ?> rawProduct : config.getMapList("sample-products")) {
      Object skuValue = rawProduct.get("sku");
      Object titleValue = rawProduct.get("title");
      Object currencyValue = rawProduct.get("currency");
      Object priceValue = rawProduct.get("price");
      Object commandValue = rawProduct.get("command-template");
      if (skuValue == null || titleValue == null || priceValue == null || commandValue == null) {
        continue;
      }
      long price = parseLong(priceValue, 0L);
      if (price <= 0) {
        continue;
      }
      seeds.add(new ProductSeed(
          skuValue.toString(),
          titleValue.toString(),
          CurrencyType.fromConfig(stringValue(currencyValue, "SHOP_COIN")),
          price,
          commandValue.toString()));
    }
    return seeds;
  }

  private static long parseLong(Object value, long fallback) {
    if (value instanceof Number numberValue) {
      return numberValue.longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }

  private static String stringValue(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    return value.toString();
  }

  enum ServerMode {
    INTERNAL,
    EXTERNAL;

    static ServerMode fromRaw(String raw) {
      String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
      if (normalized.equals("external")
          || normalized.equals("nginx_only")
          || normalized.equals("nginx")
          || normalized.equals("reverse_proxy")) {
        return EXTERNAL;
      }
      if (normalized.equals("internal")
          || normalized.equals("embedded_http")
          || normalized.equals("embedded")
          || normalized.equals("builtin")) {
        return INTERNAL;
      }
      return INTERNAL;
    }
  }

  record EmbeddedWebSettings(String host, int port, String staticRoot) {
  }

  record AdminBootstrapSettings(boolean enabled, String username, String password, String role) {
  }

  record DatabaseSettings(
      String host,
      int port,
      String schema,
      String username,
      String password,
      boolean useSsl,
      int poolSize) {

    String jdbcUrl() {
      String sslParam = useSsl ? "true" : "false";
      return String.format(
          Locale.ROOT,
          "jdbc:mariadb://%s:%d/%s?useSsl=%s&characterEncoding=utf8",
          host,
          port,
          schema,
          sslParam);
    }
  }

  record ExchangeSettings(ExchangeDirection shopToGame, ExchangeDirection gameToShop) {

    ExchangeDirection direction(CurrencyType from, CurrencyType to) {
      if (from == CurrencyType.SHOP_COIN && to == CurrencyType.GAME_COIN) {
        return shopToGame;
      }
      if (from == CurrencyType.GAME_COIN && to == CurrencyType.SHOP_COIN) {
        return gameToShop;
      }
      return new ExchangeDirection(false, 0.0D);
    }
  }

  record ExchangeDirection(boolean enabled, double ratio) {
  }

  record CurrencyDisplaySettings(
      String shopCoinName,
      String shopCoinShort,
      String gameCoinName,
      String gameCoinShort) {
  }

  record MaintenanceSettings(
      int cleanupIntervalMinutes,
      int pendingBindRetentionHours,
      int pendingPasswordRetentionHours,
      int bindRequestRetentionHours,
      int redeemCodeRetentionDays) {
  }

  record EconomySettings(MarketEconomySettings marketSettings, InflationSettings inflationSettings) {
  }

  record MarketEconomySettings(double tradeFeePercent, double tradeTaxPercent) {
  }

  record InflationSettings(InflationMode mode, long treasuryUserId) {
  }

  enum InflationMode {
    BURN,
    TREASURY;

    static InflationMode fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return BURN;
      }
      try {
        return InflationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        return BURN;
      }
    }
  }

  record RedisSettings(boolean enabled, String host, int port) {
  }

  record ProductSeed(String sku, String title, CurrencyType currency, long price, String commandTemplate) {
  }
}
