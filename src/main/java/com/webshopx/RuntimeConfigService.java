package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webshopx.payment.api.PaymentMethod;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.UUID;

class RuntimeConfigService {
  private static final String EMPTY_JSON_OBJECT = "{}";
  private static final String META_LEGACY_MIGRATED = "runtime_config_migrated_v1";
  private static final String KEY_EXCHANGE = "exchange";
  private static final String KEY_MARKET_ECONOMY = "market_economy";
  private static final String KEY_LEADERBOARD = "leaderboard";
  private static final String KEY_CURRENCY_DISPLAY = "currency_display";
  private static final String KEY_WEBSHOP_RUNTIME = "webshop_runtime";
  private static final String KEY_HOME_LINK = "home_link";
  private static final String KEY_MARKET_RUNTIME = "market_runtime";
  private static final String KEY_MARKET_TAGS = "market_tags";
  private static final String KEY_MARKET_LIMITATION = "market_limitation";
  private static final String KEY_AUCTION_DISPLAY = "auction_display";
  private static final String KEY_MAINTENANCE = "maintenance";
  private static final String KEY_LOGGING = "logging";
  private static final String KEY_BROADCAST = "broadcast";
  private static final String KEY_NOTIFICATION = "notification";
  private static final String KEY_PAYMENT_RECHARGE = "payment_recharge";

  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final Gson gson;

  RuntimeConfigService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  boolean bootstrapFromLegacyConfigIfNeeded(PluginSettings settings) {
    return databaseManager.inTransaction(connection -> {
      if (isLegacyMigrationCompleted(connection)) {
        return false;
      }
      upsertConfig(connection, KEY_EXCHANGE, serializeExchange(settings.exchangeSettings()));
      upsertConfig(
          connection,
          KEY_MARKET_ECONOMY,
          serializeMarketEconomy(settings.economySettings()));
      upsertConfig(
          connection,
          KEY_LEADERBOARD,
          serializeLeaderboard(settings.leaderboardSettings()));
      upsertConfig(
          connection,
          KEY_CURRENCY_DISPLAY,
          serializeCurrencyDisplay(settings.currencyDisplaySettings()));
      upsertConfig(connection, KEY_WEBSHOP_RUNTIME, serializeWebshopRuntime(settings));
      upsertConfig(connection, KEY_HOME_LINK, serializeHomeLink(defaultHomeUrl(settings)));
      upsertConfig(connection, KEY_MARKET_RUNTIME, serializeMarketRuntime(settings));
      upsertConfig(connection, KEY_MARKET_TAGS, EMPTY_JSON_OBJECT);
      upsertConfig(connection, KEY_MARKET_LIMITATION, EMPTY_JSON_OBJECT);
      upsertConfig(connection, KEY_MAINTENANCE, serializeMaintenance(settings.maintenanceSettings()));
      upsertConfig(connection, KEY_LOGGING, serializeLogging(settings.loggingSettings()));
      upsertConfig(connection, KEY_BROADCAST, serializeBroadcast(settings.broadcastSettings()));
      upsertConfig(connection, KEY_NOTIFICATION, serializeDefaultNotificationConfig());
      upsertConfig(connection, KEY_PAYMENT_RECHARGE, serializePaymentRecharge(settings.paymentSettings()));
      writeMetaValue(connection, META_LEGACY_MIGRATED, "1");
      return true;
    });
  }

  boolean isLegacyMigrationCompleted() {
    return databaseManager.withConnection(this::isLegacyMigrationCompleted);
  }

  void ensureDefaults(PluginSettings settings) {
    databaseManager.inTransaction(connection -> {
      insertIfMissing(connection, KEY_EXCHANGE, serializeExchange(settings.exchangeSettings()));
      insertIfMissing(
          connection,
          KEY_MARKET_ECONOMY,
          serializeMarketEconomy(settings.economySettings()));
      upgradeMarketEconomyConfigIfNeeded(connection, settings.economySettings());
      insertIfMissing(
          connection,
          KEY_LEADERBOARD,
          serializeLeaderboard(settings.leaderboardSettings()));
      insertIfMissing(
          connection,
          KEY_CURRENCY_DISPLAY,
          serializeCurrencyDisplay(settings.currencyDisplaySettings()));
      insertIfMissing(connection, KEY_WEBSHOP_RUNTIME, serializeWebshopRuntime(settings));
      insertIfMissing(connection, KEY_HOME_LINK, serializeHomeLink(defaultHomeUrl(settings)));
      insertIfMissing(connection, KEY_MARKET_RUNTIME, serializeMarketRuntime(settings));
      insertIfMissing(connection, KEY_MARKET_TAGS, EMPTY_JSON_OBJECT);
      insertIfMissing(connection, KEY_MARKET_LIMITATION, EMPTY_JSON_OBJECT);
      insertIfMissing(connection, KEY_AUCTION_DISPLAY, defaultAuctionDisplayConfig());
      insertIfMissing(connection, KEY_MAINTENANCE, serializeMaintenance(settings.maintenanceSettings()));
      insertIfMissing(connection, KEY_LOGGING, serializeLogging(settings.loggingSettings()));
      insertIfMissing(connection, KEY_BROADCAST, serializeBroadcast(settings.broadcastSettings()));
      insertIfMissing(connection, KEY_NOTIFICATION, serializeDefaultNotificationConfig());
      insertIfMissing(connection, KEY_PAYMENT_RECHARGE, serializePaymentRecharge(settings.paymentSettings()));
      return null;
    });
  }

  private void upgradeMarketEconomyConfigIfNeeded(
      Connection connection,
      PluginSettings.EconomySettings fallback) throws SQLException {
    ConfigDocument current = readConfigObject(connection, KEY_MARKET_ECONOMY, serializeMarketEconomy(fallback));
    JsonObject root = current.config();
    boolean hasInflationMode = root.has("inflationMode") && !root.get("inflationMode").isJsonNull();
    boolean hasTreasuryUserId = root.has("treasuryUserId") && !root.get("treasuryUserId").isJsonNull();
    if (hasInflationMode && hasTreasuryUserId) {
      return;
    }

    PluginSettings.MarketEconomySettings fallbackMarket = fallback.marketSettings();
    PluginSettings.InflationSettings fallbackInflation = fallback.inflationSettings();
    PluginSettings.MarketEconomySettings marketSettings = new PluginSettings.MarketEconomySettings(
        readDouble(root, "tradeFeePercent", fallbackMarket.tradeFeePercent()),
        readDouble(root, "tradeTaxPercent", fallbackMarket.tradeTaxPercent()));
    PluginSettings.InflationSettings inflationSettings = new PluginSettings.InflationSettings(
        PluginSettings.InflationMode.fromRaw(readString(root, "inflationMode", fallbackInflation.mode().name())),
        Math.max(0L, readLong(root, "treasuryUserId", fallbackInflation.treasuryUserId())));
    PluginSettings.EconomySettings merged = new PluginSettings.EconomySettings(marketSettings, inflationSettings);
    updateConfig(connection, KEY_MARKET_ECONOMY, serializeMarketEconomy(merged));
  }

  RuntimeSnapshot loadSnapshot(PluginSettings defaults) {
    return databaseManager.withConnection(connection -> loadSnapshot(connection, defaults));
  }

  PluginSettings applyTo(PluginSettings defaults) {
    RuntimeSnapshot snapshot = loadSnapshot(defaults);
    return defaults.withPaymentSettings(snapshot.paymentSettings()).withBusinessSettings(
        snapshot.defaultLocale(),
        snapshot.sessionExpireHours(),
        snapshot.bindRequestExpireMinutes(),
        snapshot.accessTokenLength(),
        snapshot.deliveryBatchSize(),
        snapshot.deliveryRetrySeconds(),
        snapshot.orderCooldownSeconds(),
        snapshot.rechargeOrderExpireMinutes(),
        snapshot.allowSharedClaimCommand(),
        snapshot.refundUndeliveredEnabled(),
        snapshot.advancedRecycleEnabled(),
        snapshot.timeZone(),
        snapshot.marketMaxActiveListings(),
        snapshot.marketSupplySettings(),
        snapshot.exchangeSettings(),
        snapshot.economySettings(),
        snapshot.leaderboardSettings(),
        snapshot.currencyDisplaySettings(),
        snapshot.maintenanceSettings(),
        snapshot.loggingSettings(),
        snapshot.broadcastSettings());
  }

  long updateExchange(PluginSettings.ExchangeSettings exchangeSettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_EXCHANGE, serializeExchange(exchangeSettings)));
  }

  long updateMarketEconomy(PluginSettings.EconomySettings economySettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_MARKET_ECONOMY, serializeMarketEconomy(economySettings)));
  }

  long updateLeaderboard(PluginSettings.LeaderboardSettings leaderboardSettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_LEADERBOARD, serializeLeaderboard(leaderboardSettings)));
  }

  long updateCurrencyDisplay(PluginSettings.CurrencyDisplaySettings currencyDisplaySettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_CURRENCY_DISPLAY, serializeCurrencyDisplay(currencyDisplaySettings)));
  }

  long updateWebshopRuntime(RuntimeSettingsUpdate update) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_WEBSHOP_RUNTIME, serializeWebshopRuntime(update)));
  }

  String homeUrl(PluginSettings settings) {
    return databaseManager.withConnection(connection -> {
      ConfigDocument document = readConfigObject(
          connection,
          KEY_HOME_LINK,
          serializeHomeLink(defaultHomeUrl(settings)));
      return readString(document.config(), "homeUrl", defaultHomeUrl(settings)).trim();
    });
  }

  long updateHomeUrl(String homeUrl) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_HOME_LINK, serializeHomeLink(homeUrl)));
  }

  private String serializeHomeLink(String homeUrl) {
    JsonObject root = new JsonObject();
    root.addProperty("homeUrl", homeUrl == null ? "" : homeUrl.trim());
    return gson.toJson(root);
  }

  private String defaultHomeUrl(PluginSettings settings) {
    String publicUrl = settings.embeddedWebSettings().publicUrl();
    return publicUrl == null || publicUrl.isBlank()
        ? ""
        : publicUrl.replaceAll("/+$", "") + "/home";
  }

  long updateMarketRuntime(int marketMaxActiveListings, PluginSettings.MarketSupplySettings marketSupplySettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_MARKET_RUNTIME, serializeMarketRuntime(marketMaxActiveListings, marketSupplySettings)));
  }

  ConfigDocument readMarketTagsConfig() {
    return databaseManager.withConnection(connection -> {
      ConfigDocument document = readConfigObject(connection, KEY_MARKET_TAGS, EMPTY_JSON_OBJECT);
      return new ConfigDocument(normalizeMarketTagsConfig(document.config()), document.version());
    });
  }

  ConfigDocument readMarketLimitationConfig() {
    return databaseManager.withConnection(connection ->
        readConfigObject(connection, KEY_MARKET_LIMITATION, EMPTY_JSON_OBJECT));
  }

  ConfigDocument readAuctionDisplayConfig() {
    return databaseManager.withConnection(connection ->
        readConfigObject(connection, KEY_AUCTION_DISPLAY, defaultAuctionDisplayConfig()));
  }

  long updateAuctionDisplayConfig(int chartPoints, int timelineEntries) {
    JsonObject config = new JsonObject();
    config.addProperty("chartPoints", Math.max(1, Math.min(100, chartPoints)));
    config.addProperty("timelineEntries", Math.max(1, Math.min(100, timelineEntries)));
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_AUCTION_DISPLAY, gson.toJson(config)));
  }

  private String defaultAuctionDisplayConfig() {
    JsonObject config = new JsonObject();
    config.addProperty("chartPoints", 10);
    config.addProperty("timelineEntries", 5);
    return gson.toJson(config);
  }

  long updateMarketTagsConfig(JsonObject config) {
    JsonObject normalized = normalizeMarketTagsConfig(config);
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_MARKET_TAGS, gson.toJson(normalized)));
  }

  private JsonObject normalizeMarketTagsConfig(JsonObject source) {
    JsonObject normalized = source == null ? new JsonObject() : copyJsonObject(source);
    int maxTags = normalized.has("maxTagsPerItem")
        ? normalized.get("maxTagsPerItem").getAsInt()
        : 3;
    normalized.addProperty("maxTagsPerItem", Math.max(1, Math.min(10, maxTags)));
    if (!normalized.has("playersCanSelectTags")) {
      normalized.addProperty("playersCanSelectTags", true);
    }
    normalized.addProperty("defaultTag", "default");

    JsonArray input = normalized.has("tags") && normalized.get("tags").isJsonArray()
        ? normalized.getAsJsonArray("tags")
        : new JsonArray();
    JsonArray tags = new JsonArray();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    JsonObject configuredFallback = null;
    for (JsonElement element : input) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject row = copyJsonObject(element.getAsJsonObject());
      String code = MarketTagCodes.normalize(
          row.has("code") ? row.get("code").getAsString() : null);
      if ("default".equals(code)) {
        configuredFallback = row;
        continue;
      }
      if (code == null || !seen.add(code)) {
        continue;
      }
      row.addProperty("code", code);
      row.addProperty("key", code);
      if (!row.has("id") || row.get("id").getAsString().isBlank()) {
        row.addProperty("id", stableTagId(code));
      }
      if (!row.has("displayName")) {
        row.addProperty("displayName", code);
      }
      if (!row.has("enabled")) {
        row.addProperty("enabled", true);
      }
      tags.add(row);
    }

    JsonObject fallback = configuredFallback == null ? new JsonObject() : configuredFallback;
    fallback.addProperty("id", stableTagId("default"));
    fallback.addProperty("code", "default");
    fallback.addProperty("key", "default");
    if (!fallback.has("displayName")) {
      fallback.addProperty("displayName", "Default");
    }
    if (!fallback.has("description")) {
      fallback.addProperty("description", "Fallback tag used when no other tag matches");
    }
    if (!fallback.has("color")) {
      fallback.addProperty("color", "grey");
    }
    fallback.addProperty("enabled", true);
    fallback.addProperty("system", true);
    fallback.addProperty("priority", Integer.MAX_VALUE);
    fallback.add("match", new JsonObject());
    tags.add(fallback);
    normalized.add("tags", tags);
    return normalized;
  }

  private String stableTagId(String key) {
    return UUID.nameUUIDFromBytes(("webshopx:market-tag:" + key).getBytes(StandardCharsets.UTF_8)).toString();
  }

  long updateMarketLimitationConfig(JsonObject config) {
    JsonObject normalized = config == null ? new JsonObject() : copyJsonObject(config);
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_MARKET_LIMITATION, gson.toJson(normalized)));
  }

  long updateMaintenance(PluginSettings.MaintenanceSettings maintenanceSettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_MAINTENANCE, serializeMaintenance(maintenanceSettings)));
  }

  long updateLogging(PluginSettings.LoggingSettings loggingSettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_LOGGING, serializeLogging(loggingSettings)));
  }

  long updateBroadcast(PluginSettings.BroadcastSettings broadcastSettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_BROADCAST, serializeBroadcast(broadcastSettings)));
  }

  NotificationSettings readNotificationSettings() {
    return databaseManager.withConnection(connection -> {
      ConfigDocument document = readConfigObject(connection, KEY_NOTIFICATION, serializeDefaultNotificationConfig());
      return parseNotificationSettings(document.config());
    });
  }

  long updateNotificationSettings(NotificationSettings notificationSettings) {
    NotificationSettings normalized = notificationSettings == null
        ? NotificationSettings.defaults()
        : notificationSettings.normalized();
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_NOTIFICATION, serializeNotification(normalized)));
  }

  long updatePaymentRecharge(PluginSettings.PaymentSettings paymentSettings) {
    return databaseManager.inTransaction(connection ->
        updateConfig(connection, KEY_PAYMENT_RECHARGE, serializePaymentRecharge(paymentSettings)));
  }

  private RuntimeSnapshot loadSnapshot(Connection connection, PluginSettings defaults) throws SQLException {
    Map<String, ConfigRow> rows = readConfigRows(connection);

    PluginSettings.ExchangeSettings exchangeSettings = parseExchange(
        rows.get(KEY_EXCHANGE),
        defaults.exchangeSettings());
    PluginSettings.EconomySettings economySettings = parseMarketEconomy(
        rows.get(KEY_MARKET_ECONOMY),
        defaults.economySettings());
    PluginSettings.LeaderboardSettings leaderboardSettings = parseLeaderboard(
        rows.get(KEY_LEADERBOARD),
        defaults.leaderboardSettings());
    PluginSettings.CurrencyDisplaySettings currencyDisplaySettings = parseCurrencyDisplay(
        rows.get(KEY_CURRENCY_DISPLAY),
        defaults.currencyDisplaySettings());
    RuntimeSettingsUpdate webshopRuntime = parseWebshopRuntime(rows.get(KEY_WEBSHOP_RUNTIME), defaults);
    MarketRuntimeSnapshot marketRuntime = parseMarketRuntime(rows.get(KEY_MARKET_RUNTIME), defaults);
    PluginSettings.MaintenanceSettings maintenanceSettings = parseMaintenance(
        rows.get(KEY_MAINTENANCE),
        defaults.maintenanceSettings());
    PluginSettings.LoggingSettings loggingSettings = parseLogging(
        rows.get(KEY_LOGGING),
        defaults.loggingSettings());
    PluginSettings.BroadcastSettings broadcastSettings = parseBroadcast(
        rows.get(KEY_BROADCAST),
        defaults.broadcastSettings());
    PluginSettings.PaymentSettings paymentSettings = parsePaymentRecharge(
        rows.get(KEY_PAYMENT_RECHARGE),
        defaults.paymentSettings());

    long maxVersion = 0L;
    for (ConfigRow row : rows.values()) {
      if (row.version() > maxVersion) {
        maxVersion = row.version();
      }
    }

    return new RuntimeSnapshot(
        webshopRuntime.defaultLocale(),
        webshopRuntime.sessionExpireHours(),
        webshopRuntime.bindRequestExpireMinutes(),
        webshopRuntime.accessTokenLength(),
        webshopRuntime.deliveryBatchSize(),
        webshopRuntime.deliveryRetrySeconds(),
        webshopRuntime.orderCooldownSeconds(),
        webshopRuntime.rechargeOrderExpireMinutes(),
        webshopRuntime.allowSharedClaimCommand(),
        webshopRuntime.refundUndeliveredEnabled(),
        webshopRuntime.advancedRecycleEnabled(),
        webshopRuntime.timeZone(),
        marketRuntime.marketMaxActiveListings(),
        marketRuntime.marketSupplySettings(),
        exchangeSettings,
        economySettings,
        leaderboardSettings,
        currencyDisplaySettings,
        maintenanceSettings,
        loggingSettings,
        broadcastSettings,
        paymentSettings,
        maxVersion);
  }

  private boolean isLegacyMigrationCompleted(Connection connection) throws SQLException {
    String value = readMetaValue(connection, META_LEGACY_MIGRATED);
    return value != null && !value.isBlank();
  }

  private void insertIfMissing(Connection connection, String key, String jsonValue) throws SQLException {
    String sql = sqlProvider.insertRuntimeConfigIfMissingSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      statement.setString(2, jsonValue);
      statement.executeUpdate();
    }
  }

  private void upsertConfig(Connection connection, String key, String jsonValue) throws SQLException {
    String sql = runtimeConfigUpsertSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      statement.setString(2, jsonValue);
      statement.executeUpdate();
    }
  }

  private String readMetaValue(Connection connection, String key) throws SQLException {
    String sql = "SELECT meta_value FROM webshop_meta WHERE meta_key = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return resultSet.getString("meta_value");
      }
    }
  }

  private void writeMetaValue(Connection connection, String key, String value) throws SQLException {
    String sql = sqlProvider.upsertWebshopMetaSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      statement.setString(2, value);
      statement.executeUpdate();
    }
  }


  private long updateConfig(Connection connection, String key, String jsonValue) throws SQLException {
    String sql = runtimeConfigUpsertSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      statement.setString(2, jsonValue);
      statement.executeUpdate();
    }

    String readSql = "SELECT version FROM runtime_config WHERE config_key = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(readSql)) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return 0L;
        }
        return resultSet.getLong("version");
      }
    }
  }

  private String runtimeConfigUpsertSql() {
    return sqlProvider.upsertRuntimeConfigSql();
  }

  private ConfigDocument readConfigObject(Connection connection, String key, String fallbackJson) throws SQLException {
    String sql = """
        SELECT config_value, version
        FROM runtime_config
        WHERE config_key = ?
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return new ConfigDocument(parseConfigObject(fallbackJson), 0L);
        }
        String rawValue = resultSet.getString("config_value");
        long version = resultSet.getLong("version");
        return new ConfigDocument(parseConfigObject(rawValue == null ? fallbackJson : rawValue), version);
      }
    }
  }

  private Map<String, ConfigRow> readConfigRows(Connection connection) throws SQLException {
    String sql = """
        SELECT config_key, config_value, version
        FROM runtime_config
        WHERE config_key IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, KEY_EXCHANGE);
      statement.setString(2, KEY_MARKET_ECONOMY);
      statement.setString(3, KEY_LEADERBOARD);
      statement.setString(4, KEY_CURRENCY_DISPLAY);
      statement.setString(5, KEY_WEBSHOP_RUNTIME);
      statement.setString(6, KEY_MARKET_RUNTIME);
      statement.setString(7, KEY_MARKET_TAGS);
      statement.setString(8, KEY_MARKET_LIMITATION);
      statement.setString(9, KEY_MAINTENANCE);
      statement.setString(10, KEY_LOGGING);
      statement.setString(11, KEY_BROADCAST);
      statement.setString(12, KEY_PAYMENT_RECHARGE);
      try (ResultSet resultSet = statement.executeQuery()) {
        Map<String, ConfigRow> rows = new HashMap<>();
        while (resultSet.next()) {
          rows.put(
              resultSet.getString("config_key"),
              new ConfigRow(
                  resultSet.getString("config_value"),
                  resultSet.getLong("version")));
        }
        return rows;
      }
    }
  }

  private String serializeExchange(PluginSettings.ExchangeSettings settings) {
    JsonObject root = new JsonObject();

    JsonObject shopToGame = new JsonObject();
    shopToGame.addProperty("enabled", settings.shopToGame().enabled());
    shopToGame.addProperty("ratio", settings.shopToGame().ratio());

    JsonObject gameToShop = new JsonObject();
    gameToShop.addProperty("enabled", settings.gameToShop().enabled());
    gameToShop.addProperty("ratio", settings.gameToShop().ratio());

    root.add("shopToGame", shopToGame);
    root.add("gameToShop", gameToShop);
    return gson.toJson(root);
  }

  private PluginSettings.ExchangeSettings parseExchange(
      ConfigRow row,
      PluginSettings.ExchangeSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      JsonObject shopToGame = root.has("shopToGame") && root.get("shopToGame").isJsonObject()
          ? root.getAsJsonObject("shopToGame")
          : null;
      JsonObject gameToShop = root.has("gameToShop") && root.get("gameToShop").isJsonObject()
          ? root.getAsJsonObject("gameToShop")
          : null;
      PluginSettings.ExchangeDirection fallbackShopToGame = fallback.shopToGame();
      PluginSettings.ExchangeDirection fallbackGameToShop = fallback.gameToShop();
      PluginSettings.ExchangeDirection parsedShopToGame = new PluginSettings.ExchangeDirection(
          readBoolean(shopToGame, "enabled", fallbackShopToGame.enabled()),
          readDouble(shopToGame, "ratio", fallbackShopToGame.ratio()));
      PluginSettings.ExchangeDirection parsedGameToShop = new PluginSettings.ExchangeDirection(
          readBoolean(gameToShop, "enabled", fallbackGameToShop.enabled()),
          readDouble(gameToShop, "ratio", fallbackGameToShop.ratio()));
      return new PluginSettings.ExchangeSettings(parsedShopToGame, parsedGameToShop);
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String serializeMarketEconomy(PluginSettings.EconomySettings settings) {
    JsonObject root = new JsonObject();
    PluginSettings.MarketEconomySettings marketSettings = settings.marketSettings();
    root.addProperty("tradeFeePercent", marketSettings.tradeFeePercent());
    root.addProperty("tradeTaxPercent", marketSettings.tradeTaxPercent());
    PluginSettings.InflationSettings inflationSettings = settings.inflationSettings();
    root.addProperty("inflationMode", inflationSettings.mode().name());
    root.addProperty("treasuryUserId", Math.max(0L, inflationSettings.treasuryUserId()));
    return gson.toJson(root);
  }

  private PluginSettings.EconomySettings parseMarketEconomy(
      ConfigRow row,
      PluginSettings.EconomySettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      PluginSettings.MarketEconomySettings fallbackMarket = fallback.marketSettings();
      PluginSettings.InflationSettings fallbackInflation = fallback.inflationSettings();
      PluginSettings.MarketEconomySettings parsedMarket = new PluginSettings.MarketEconomySettings(
          readDouble(root, "tradeFeePercent", fallbackMarket.tradeFeePercent()),
          readDouble(root, "tradeTaxPercent", fallbackMarket.tradeTaxPercent()));
      PluginSettings.InflationSettings parsedInflation = new PluginSettings.InflationSettings(
          PluginSettings.InflationMode.fromRaw(
              readString(root, "inflationMode", fallbackInflation.mode().name())),
          Math.max(0L, readLong(root, "treasuryUserId", fallbackInflation.treasuryUserId())));
      return new PluginSettings.EconomySettings(parsedMarket, parsedInflation);
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String serializeLeaderboard(PluginSettings.LeaderboardSettings settings) {
    JsonObject root = new JsonObject();
    root.addProperty("enabled", settings.enabled());
    root.addProperty("showOnlineStatus", settings.showOnlineStatus());
    root.addProperty("defaultMetric", settings.defaultMetric().name());
    root.addProperty("defaultOrder", settings.defaultOrder().name());
    return gson.toJson(root);
  }

  private PluginSettings.LeaderboardSettings parseLeaderboard(
      ConfigRow row,
      PluginSettings.LeaderboardSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      return new PluginSettings.LeaderboardSettings(
          readBoolean(root, "enabled", fallback.enabled()),
          readBoolean(root, "showOnlineStatus", fallback.showOnlineStatus()),
          PluginSettings.LeaderboardMetric.fromRaw(readString(root, "defaultMetric", fallback.defaultMetric().name())),
          PluginSettings.SortDirection.fromRaw(readString(root, "defaultOrder", fallback.defaultOrder().name())));
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String serializeCurrencyDisplay(PluginSettings.CurrencyDisplaySettings settings) {
    JsonObject root = new JsonObject();
    root.addProperty("shopCoinName", settings.shopCoinName());
    root.addProperty("shopCoinShort", settings.shopCoinShort());
    root.addProperty("gameCoinName", settings.gameCoinName());
    root.addProperty("gameCoinShort", settings.gameCoinShort());
    return gson.toJson(root);
  }

  private PluginSettings.CurrencyDisplaySettings parseCurrencyDisplay(
      ConfigRow row,
      PluginSettings.CurrencyDisplaySettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      return new PluginSettings.CurrencyDisplaySettings(
          readString(root, "shopCoinName", fallback.shopCoinName()),
          readString(root, "shopCoinShort", fallback.shopCoinShort()),
          readString(root, "gameCoinName", fallback.gameCoinName()),
          readString(root, "gameCoinShort", fallback.gameCoinShort()));
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String serializePaymentRecharge(PluginSettings.PaymentSettings settings) {
    JsonObject root = new JsonObject();
    JsonArray currencies = new JsonArray();
    for (String currency : settings.rechargeCurrencies()) {
      currencies.add(currency);
    }
    JsonArray methods = new JsonArray();
    for (PaymentMethod method : settings.rechargeMethods()) {
      methods.add(method.name());
    }
    JsonArray rates = new JsonArray();
    for (PluginSettings.RechargeRate rate : settings.rechargeRates()) {
      JsonObject rateJson = new JsonObject();
      rateJson.addProperty("providerId", rate.providerId());
      rateJson.addProperty("method", rate.method().name());
      rateJson.addProperty("currency", rate.currency());
      rateJson.addProperty("coinsPerUnit", rate.coinsPerUnit());
      rates.add(rateJson);
    }
    root.add("currencies", currencies);
    root.add("methods", methods);
    root.add("rates", rates);
    return gson.toJson(root);
  }

  private PluginSettings.PaymentSettings parsePaymentRecharge(
      ConfigRow row,
      PluginSettings.PaymentSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      String legacyProvider = readString(root, "provider", fallback.provider());
      return new PluginSettings.PaymentSettings(
          legacyProvider,
          readStringArray(root, "currencies", fallback.rechargeCurrencies()),
          PluginSettings.normalizePaymentMethods(readStringArray(root, "methods", paymentMethodNames(fallback.rechargeMethods()))),
          readRechargeRates(root, legacyProvider));
    } catch (Exception exception) {
      return fallback;
    }
  }

  private List<PluginSettings.RechargeRate> readRechargeRates(JsonObject jsonObject, String legacyProvider) {
    if (jsonObject == null || !jsonObject.has("rates") || jsonObject.get("rates").isJsonNull()) {
      return List.of();
    }
    JsonElement value = jsonObject.get("rates");
    if (!value.isJsonArray()) {
      return List.of();
    }
    List<PluginSettings.RechargeRate> result = new ArrayList<>();
    for (JsonElement element : value.getAsJsonArray()) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject item = element.getAsJsonObject();
      String providerId = readString(item, "providerId", legacyProvider);
      PaymentMethod method = PluginSettings.parsePaymentMethod(readString(item, "method", "ALIPAY"));
      String currency = readString(item, "currency", "");
      long coinsPerUnit = readLong(item, "coinsPerUnit", 0L);
      if (method != null && coinsPerUnit > 0L) {
        result.add(new PluginSettings.RechargeRate(providerId, method, currency, coinsPerUnit));
      }
    }
    return result;
  }

  private String serializeWebshopRuntime(PluginSettings settings) {
    RuntimeSettingsUpdate update = new RuntimeSettingsUpdate(
        settings.defaultLocale(),
        settings.sessionExpireHours(),
        settings.bindRequestExpireMinutes(),
        settings.accessTokenLength(),
        settings.deliveryBatchSize(),
        settings.deliveryRetrySeconds(),
        settings.orderCooldownSeconds(),
        settings.rechargeOrderExpireMinutes(),
        settings.allowSharedClaimCommand(),
        settings.refundUndeliveredEnabled(),
        settings.advancedRecycleEnabled(),
        settings.timeZone());
    return serializeWebshopRuntime(update);
  }

  private String serializeWebshopRuntime(RuntimeSettingsUpdate update) {
    JsonObject root = new JsonObject();
    root.addProperty("defaultLocale", update.defaultLocale());
    root.addProperty("sessionExpireHours", update.sessionExpireHours());
    root.addProperty("bindRequestExpireMinutes", update.bindRequestExpireMinutes());
    root.addProperty("accessTokenLength", update.accessTokenLength());
    root.addProperty("deliveryBatchSize", update.deliveryBatchSize());
    root.addProperty("deliveryRetrySeconds", update.deliveryRetrySeconds());
    root.addProperty("orderCooldownSeconds", update.orderCooldownSeconds());
    root.addProperty("rechargeOrderExpireMinutes", update.rechargeOrderExpireMinutes());
    root.addProperty("allowSharedClaimCommand", update.allowSharedClaimCommand());
    root.addProperty("refundUndeliveredEnabled", update.refundUndeliveredEnabled());
    root.addProperty("advancedRecycleEnabled", update.advancedRecycleEnabled());
    root.addProperty("timeZone", update.timeZone().getId());
    return gson.toJson(root);
  }

  private RuntimeSettingsUpdate parseWebshopRuntime(ConfigRow row, PluginSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return new RuntimeSettingsUpdate(
          fallback.defaultLocale(),
          fallback.sessionExpireHours(),
          fallback.bindRequestExpireMinutes(),
          fallback.accessTokenLength(),
          fallback.deliveryBatchSize(),
          fallback.deliveryRetrySeconds(),
          fallback.orderCooldownSeconds(),
          fallback.rechargeOrderExpireMinutes(),
          fallback.allowSharedClaimCommand(),
          fallback.refundUndeliveredEnabled(),
          fallback.advancedRecycleEnabled(),
          fallback.timeZone());
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      return new RuntimeSettingsUpdate(
          readString(root, "defaultLocale", fallback.defaultLocale()),
          readInt(root, "sessionExpireHours", fallback.sessionExpireHours()),
          readInt(root, "bindRequestExpireMinutes", fallback.bindRequestExpireMinutes()),
          readInt(root, "accessTokenLength", fallback.accessTokenLength()),
          readInt(root, "deliveryBatchSize", fallback.deliveryBatchSize()),
          readInt(root, "deliveryRetrySeconds", fallback.deliveryRetrySeconds()),
          readInt(root, "orderCooldownSeconds", fallback.orderCooldownSeconds()),
          readInt(root, "rechargeOrderExpireMinutes", fallback.rechargeOrderExpireMinutes()),
          readBoolean(root, "allowSharedClaimCommand", fallback.allowSharedClaimCommand()),
          readBoolean(root, "refundUndeliveredEnabled", fallback.refundUndeliveredEnabled()),
          readBoolean(root, "advancedRecycleEnabled", fallback.advancedRecycleEnabled()),
          readZoneId(root, "timeZone", fallback.timeZone()));
    } catch (Exception exception) {
      return new RuntimeSettingsUpdate(
          fallback.defaultLocale(),
          fallback.sessionExpireHours(),
          fallback.bindRequestExpireMinutes(),
          fallback.accessTokenLength(),
          fallback.deliveryBatchSize(),
          fallback.deliveryRetrySeconds(),
          fallback.orderCooldownSeconds(),
          fallback.rechargeOrderExpireMinutes(),
          fallback.allowSharedClaimCommand(),
          fallback.refundUndeliveredEnabled(),
          fallback.advancedRecycleEnabled(),
          fallback.timeZone());
    }
  }

  private String serializeMarketRuntime(PluginSettings settings) {
    return serializeMarketRuntime(settings.marketMaxActiveListings(), settings.marketSupplySettings());
  }

  private String serializeMarketRuntime(int marketMaxActiveListings, PluginSettings.MarketSupplySettings marketSupplySettings) {
    JsonObject root = new JsonObject();
    root.addProperty("marketMaxActiveListings", marketMaxActiveListings);
    JsonObject supply = new JsonObject();
    supply.addProperty("autoRefreshThreshold", marketSupplySettings.autoRefreshThreshold());
    supply.addProperty("defaultTransferBatchSize", marketSupplySettings.defaultTransferBatchSize());
    supply.addProperty("maxTransferBatchSize", marketSupplySettings.maxTransferBatchSize());
    supply.addProperty("defaultTransitStock", marketSupplySettings.defaultTransitStock());
    supply.addProperty("maxTransitStock", marketSupplySettings.maxTransitStock());
    root.add("supply", supply);
    return gson.toJson(root);
  }

  private MarketRuntimeSnapshot parseMarketRuntime(ConfigRow row, PluginSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return new MarketRuntimeSnapshot(fallback.marketMaxActiveListings(), fallback.marketSupplySettings());
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      JsonObject supply = root.has("supply") && root.get("supply").isJsonObject()
          ? root.getAsJsonObject("supply")
          : null;
      PluginSettings.MarketSupplySettings fallbackSupply = fallback.marketSupplySettings();
      return new MarketRuntimeSnapshot(
          readInt(root, "marketMaxActiveListings", fallback.marketMaxActiveListings()),
          new PluginSettings.MarketSupplySettings(
              readInt(supply, "autoRefreshThreshold", fallbackSupply.autoRefreshThreshold()),
              readInt(supply, "defaultTransferBatchSize", fallbackSupply.defaultTransferBatchSize()),
              readInt(supply, "maxTransferBatchSize", fallbackSupply.maxTransferBatchSize()),
              readInt(supply, "defaultTransitStock", fallbackSupply.defaultTransitStock()),
              readInt(supply, "maxTransitStock", fallbackSupply.maxTransitStock())));
    } catch (Exception exception) {
      return new MarketRuntimeSnapshot(fallback.marketMaxActiveListings(), fallback.marketSupplySettings());
    }
  }

  private String serializeMaintenance(PluginSettings.MaintenanceSettings settings) {
    JsonObject root = new JsonObject();
    root.addProperty("cleanupIntervalMinutes", settings.cleanupIntervalMinutes());
    root.addProperty("pendingBindRetentionHours", settings.pendingBindRetentionHours());
    root.addProperty("pendingPasswordRetentionHours", settings.pendingPasswordRetentionHours());
    root.addProperty("bindRequestRetentionHours", settings.bindRequestRetentionHours());
    root.addProperty("redeemCodeRetentionDays", settings.redeemCodeRetentionDays());
    return gson.toJson(root);
  }

  private PluginSettings.MaintenanceSettings parseMaintenance(
      ConfigRow row,
      PluginSettings.MaintenanceSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      return new PluginSettings.MaintenanceSettings(
          readInt(root, "cleanupIntervalMinutes", fallback.cleanupIntervalMinutes()),
          readInt(root, "pendingBindRetentionHours", fallback.pendingBindRetentionHours()),
          readInt(root, "pendingPasswordRetentionHours", fallback.pendingPasswordRetentionHours()),
          readInt(root, "bindRequestRetentionHours", fallback.bindRequestRetentionHours()),
          readInt(root, "redeemCodeRetentionDays", fallback.redeemCodeRetentionDays()));
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String serializeLogging(PluginSettings.LoggingSettings settings) {
    JsonObject root = new JsonObject();
    root.addProperty("enabled", settings.enabled());
    root.addProperty("level", settings.level().name());
    root.addProperty("directory", settings.directory());
    root.addProperty("maxFileSizeMb", settings.maxFileSizeMb());
    root.addProperty("maxFiles", settings.maxFiles());
    root.addProperty("retentionDays", settings.retentionDays());
    return gson.toJson(root);
  }

  private PluginSettings.LoggingSettings parseLogging(
      ConfigRow row,
      PluginSettings.LoggingSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      return new PluginSettings.LoggingSettings(
          readBoolean(root, "enabled", fallback.enabled()),
          PluginSettings.LogLevel.fromRaw(readString(root, "level", fallback.level().name())),
          readString(root, "directory", fallback.directory()),
          readInt(root, "maxFileSizeMb", fallback.maxFileSizeMb()),
          readInt(root, "maxFiles", fallback.maxFiles()),
          readInt(root, "retentionDays", fallback.retentionDays()));
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String serializeBroadcast(PluginSettings.BroadcastSettings settings) {
    JsonObject root = new JsonObject();
    root.addProperty("enabled", settings.enabled());
    JsonObject templates = new JsonObject();
    for (Map.Entry<String, String> entry : settings.templates().entrySet()) {
      templates.addProperty(entry.getKey(), entry.getValue());
    }
    root.add("templates", templates);
    return gson.toJson(root);
  }

  private PluginSettings.BroadcastSettings parseBroadcast(
      ConfigRow row,
      PluginSettings.BroadcastSettings fallback) {
    if (row == null || row.configValue() == null || row.configValue().isBlank()) {
      return fallback;
    }
    try {
      JsonObject root = JsonParser.parseString(row.configValue()).getAsJsonObject();
      Map<String, String> templates = new LinkedHashMap<>();
      JsonObject templateObject = root.has("templates") && root.get("templates").isJsonObject()
          ? root.getAsJsonObject("templates")
          : null;
      if (templateObject != null) {
        for (Map.Entry<String, JsonElement> entry : templateObject.entrySet()) {
          if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
            templates.put(entry.getKey(), entry.getValue().getAsString());
          }
        }
      }
      if (templates.isEmpty()) {
        templates.putAll(fallback.templates());
      }
      return new PluginSettings.BroadcastSettings(
          readBoolean(root, "enabled", fallback.enabled()),
          templates);
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String serializeDefaultNotificationConfig() {
    return serializeNotification(NotificationSettings.defaults());
  }

  private String serializeNotification(NotificationSettings settings) {
    NotificationSettings normalized = settings == null ? NotificationSettings.defaults() : settings.normalized();
    JsonObject root = new JsonObject();
    root.addProperty("marketEventsEnabled", normalized.marketEventsEnabled());
    root.addProperty("deliveryMailboxEventsEnabled", normalized.deliveryMailboxEventsEnabled());
    JsonObject templates = new JsonObject();
    for (Map.Entry<String, String> entry : normalized.templates().entrySet()) {
      templates.addProperty(entry.getKey(), entry.getValue());
    }
    root.add("templates", templates);
    return gson.toJson(root);
  }

  private NotificationSettings parseNotificationSettings(JsonObject root) {
    NotificationSettings fallback = NotificationSettings.defaults();
    if (root == null) {
      return fallback;
    }
    Map<String, String> templates = new LinkedHashMap<>(fallback.templates());
    JsonObject templateObject = root.has("templates") && root.get("templates").isJsonObject()
        ? root.getAsJsonObject("templates")
        : null;
    if (templateObject != null) {
      Map<String, String> legacyDefaults = NotificationSettings.legacyDefaultTemplateMap();
      for (Map.Entry<String, JsonElement> entry : templateObject.entrySet()) {
        if (entry.getValue() == null || entry.getValue().isJsonNull()) {
          continue;
        }
        String value = entry.getValue().getAsString();
        if (value != null && !value.isBlank()) {
          String normalized = value.trim();
          String legacyValue = legacyDefaults.get(entry.getKey());
          if (legacyValue != null && legacyValue.equals(normalized)) {
            continue;
          }
          templates.put(entry.getKey(), normalized);
        }
      }
    }
    return new NotificationSettings(
        readBoolean(root, "marketEventsEnabled", fallback.marketEventsEnabled()),
        readBoolean(root, "deliveryMailboxEventsEnabled", fallback.deliveryMailboxEventsEnabled()),
        templates).normalized();
  }

  private JsonObject parseConfigObject(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return new JsonObject();
    }
    try {
      JsonElement parsed = JsonParser.parseString(rawJson);
      if (parsed != null && parsed.isJsonObject()) {
        return parsed.getAsJsonObject();
      }
    } catch (Exception ignored) {
      // Return empty object for malformed runtime rows.
    }
    return new JsonObject();
  }

  private JsonObject copyJsonObject(JsonObject source) {
    if (source == null) {
      return new JsonObject();
    }
    try {
      JsonElement parsed = JsonParser.parseString(gson.toJson(source));
      if (parsed != null && parsed.isJsonObject()) {
        return parsed.getAsJsonObject();
      }
    } catch (Exception ignored) {
      // Fall through to empty object.
    }
    return new JsonObject();
  }

  private boolean readBoolean(JsonObject jsonObject, String field, boolean fallback) {
    if (jsonObject == null || !jsonObject.has(field) || jsonObject.get(field).isJsonNull()) {
      return fallback;
    }
    try {
      return jsonObject.get(field).getAsBoolean();
    } catch (Exception exception) {
      return fallback;
    }
  }

  private double readDouble(JsonObject jsonObject, String field, double fallback) {
    if (jsonObject == null || !jsonObject.has(field) || jsonObject.get(field).isJsonNull()) {
      return fallback;
    }
    try {
      return jsonObject.get(field).getAsDouble();
    } catch (Exception exception) {
      return fallback;
    }
  }

  private int readInt(JsonObject jsonObject, String field, int fallback) {
    if (jsonObject == null || !jsonObject.has(field) || jsonObject.get(field).isJsonNull()) {
      return fallback;
    }
    try {
      return jsonObject.get(field).getAsInt();
    } catch (Exception exception) {
      return fallback;
    }
  }

  private long readLong(JsonObject jsonObject, String field, long fallback) {
    if (jsonObject == null || !jsonObject.has(field) || jsonObject.get(field).isJsonNull()) {
      return fallback;
    }
    try {
      return jsonObject.get(field).getAsLong();
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String readString(JsonObject jsonObject, String field, String fallback) {
    if (jsonObject == null || !jsonObject.has(field) || jsonObject.get(field).isJsonNull()) {
      return fallback;
    }
    try {
      String value = jsonObject.get(field).getAsString();
      return value == null || value.isBlank() ? fallback : value;
    } catch (Exception exception) {
      return fallback;
    }
  }

  private List<String> readStringArray(JsonObject jsonObject, String field, List<String> fallback) {
    if (jsonObject == null || !jsonObject.has(field) || jsonObject.get(field).isJsonNull()) {
      return fallback;
    }
    JsonElement value = jsonObject.get(field);
    if (!value.isJsonArray()) {
      return fallback;
    }
    List<String> result = new ArrayList<>();
    for (JsonElement element : value.getAsJsonArray()) {
      if (element == null || element.isJsonNull()) {
        continue;
      }
      try {
        result.add(element.getAsString());
      } catch (Exception ignored) {
        // Skip malformed array values.
      }
    }
    return result.isEmpty() ? fallback : result;
  }

  private List<String> paymentMethodNames(List<PaymentMethod> methods) {
    if (methods == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (PaymentMethod method : methods) {
      if (method != null) {
        names.add(method.name());
      }
    }
    return names;
  }

  private ZoneId readZoneId(JsonObject jsonObject, String field, ZoneId fallback) {
    String raw = readString(jsonObject, field, fallback.getId());
    try {
      return ZoneId.of(raw);
    } catch (Exception exception) {
      return fallback;
    }
  }

  record NotificationSettings(
      boolean marketEventsEnabled,
      boolean deliveryMailboxEventsEnabled,
      Map<String, String> templates) {
    static NotificationSettings defaults() {
      return new NotificationSettings(true, true, defaultTemplateMap());
    }

    static Map<String, String> defaultTemplateMap() {
      Map<String, String> templates = new LinkedHashMap<>();
      templates.put("market_listed", "");
      templates.put("market_trade", "");
      templates.put("auction_bid_self", "");
      templates.put("auction_bid_seller", "");
      templates.put("auction_outbid", "");
      templates.put("auction_settlement", "");
      templates.put("market_buy_escrow_refund", "");
      templates.put("delivery_wait_claim_order", "");
      templates.put("delivery_wait_claim_market", "");
      templates.put("mailbox_pending", "");
      return templates;
    }

    static Map<String, String> legacyDefaultTemplateMap() {
      Map<String, String> templates = new LinkedHashMap<>();
      templates.put("market_listed", "Your listing #{listingId} is published: {item} x{quantity}, unit price {priceText}.");
      templates.put("market_trade", "Listing #{listingId} sold: {item} x{quantity}, total {totalText}.");
      templates.put("auction_bid_self", "Your bid on auction #{listingId} succeeded: {bidAmountText}.");
      templates.put("auction_bid_seller", "Auction #{listingId} received a new bid from {bidderName}.");
      templates.put("auction_outbid", "Your leading bid on auction #{listingId} has been outbid.");
      templates.put("auction_settlement", "{message}");
      templates.put("market_buy_escrow_refund", "Buy order #{listingId} escrow was refunded: {amountText}.");
      templates.put("delivery_wait_claim_order", "Auto delivery for order {token} failed. Run /ws claim {token} in-game. Reason: {reason}");
      templates.put("delivery_wait_claim_market", "Auto delivery for market item failed. Run /ws claim {token} in-game. Reason: {reason}");
      templates.put("mailbox_pending", "Inventory was unavailable during auto delivery, item moved to mailbox. Run /ws mailbox in-game to view it. Source: {sourceType} {sourceRef}");
      return templates;
    }

    NotificationSettings normalized() {
      Map<String, String> normalizedTemplates = new LinkedHashMap<>(defaultTemplateMap());
      if (templates != null) {
        for (Map.Entry<String, String> entry : templates.entrySet()) {
          String key = entry.getKey();
          String value = entry.getValue();
          if (key == null || key.isBlank() || value == null || value.isBlank()) {
            continue;
          }
          normalizedTemplates.put(key.trim(), value.trim());
        }
      }
      return new NotificationSettings(
          marketEventsEnabled,
          deliveryMailboxEventsEnabled,
          normalizedTemplates);
    }

    String template(String key) {
      if (key == null || key.isBlank()) {
        return "";
      }
      return normalized().templates().getOrDefault(key, "");
    }
  }

  record RuntimeSnapshot(
      String defaultLocale,
      int sessionExpireHours,
      int bindRequestExpireMinutes,
      int accessTokenLength,
      int deliveryBatchSize,
      int deliveryRetrySeconds,
      int orderCooldownSeconds,
      int rechargeOrderExpireMinutes,
      boolean allowSharedClaimCommand,
      boolean refundUndeliveredEnabled,
      boolean advancedRecycleEnabled,
      ZoneId timeZone,
      int marketMaxActiveListings,
      PluginSettings.MarketSupplySettings marketSupplySettings,
      PluginSettings.ExchangeSettings exchangeSettings,
      PluginSettings.EconomySettings economySettings,
      PluginSettings.LeaderboardSettings leaderboardSettings,
      PluginSettings.CurrencyDisplaySettings currencyDisplaySettings,
      PluginSettings.MaintenanceSettings maintenanceSettings,
      PluginSettings.LoggingSettings loggingSettings,
      PluginSettings.BroadcastSettings broadcastSettings,
      PluginSettings.PaymentSettings paymentSettings,
      long maxVersion) {
  }

  record RuntimeSettingsUpdate(
      String defaultLocale,
      int sessionExpireHours,
      int bindRequestExpireMinutes,
      int accessTokenLength,
      int deliveryBatchSize,
      int deliveryRetrySeconds,
      int orderCooldownSeconds,
      int rechargeOrderExpireMinutes,
      boolean allowSharedClaimCommand,
      boolean refundUndeliveredEnabled,
      boolean advancedRecycleEnabled,
      ZoneId timeZone) {
  }

  private record MarketRuntimeSnapshot(
      int marketMaxActiveListings,
      PluginSettings.MarketSupplySettings marketSupplySettings) {
  }

  record ConfigDocument(JsonObject config, long version) {
  }

  private record ConfigRow(String configValue, long version) {
  }
}
