package com.webshopx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

record PluginSettings(
    WebMode webMode,
    int sessionExpireHours,
    int bindRequestExpireMinutes,
    int accessTokenLength,
    int deliveryBatchSize,
    int deliveryRetrySeconds,
    EmbeddedWebSettings embeddedWebSettings,
    DatabaseSettings databaseSettings,
    ExchangeSettings exchangeSettings,
    RedisSettings redisSettings,
    List<ProductSeed> productSeeds) {

  static PluginSettings fromConfig(FileConfiguration config) {
    String rawMode = config.getString("webshop.web-mode", "embedded_http");
    WebMode mode = WebMode.fromRaw(rawMode);

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

    return new PluginSettings(
        mode,
        config.getInt("webshop.session-expire-hours", 72),
        config.getInt("webshop.bind-request-expire-minutes", 15),
        config.getInt("webshop.access-token-length", 48),
        config.getInt("webshop.delivery-batch-size", 20),
        config.getInt("webshop.delivery-retry-seconds", 30),
        webSettings,
        databaseSettings,
        new ExchangeSettings(shopToGame, gameToShop),
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

  enum WebMode {
    EMBEDDED,
    NGINX_ONLY;

    static WebMode fromRaw(String raw) {
      String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
      if (normalized.equals("nginx_only")) {
        return NGINX_ONLY;
      }
      return EMBEDDED;
    }
  }

  record EmbeddedWebSettings(String host, int port, String staticRoot) {
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

  record RedisSettings(boolean enabled, String host, int port) {
  }

  record ProductSeed(String sku, String title, CurrencyType currency, long price, String commandTemplate) {
  }
}
