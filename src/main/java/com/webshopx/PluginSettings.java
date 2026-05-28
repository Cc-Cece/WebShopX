package com.webshopx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.DateTimeException;
import java.time.ZoneId;
import com.webshopx.payment.api.PaymentMethod;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

record PluginSettings(
    ServerMode serverMode,
    ClusterSettings clusterSettings,
    String apiBaseUrl,
    PaymentSettings paymentSettings,
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
    MarketSupplySettings marketSupplySettings,
    LeaderboardSettings leaderboardSettings,
    CurrencyDisplaySettings currencyDisplaySettings,
    MaintenanceSettings maintenanceSettings,
    LoggingSettings loggingSettings,
    BusinessLedgerSettings businessLedgerSettings,
    BroadcastSettings broadcastSettings,
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
    ClusterRole clusterRole = ClusterRole.fromRaw(config.getString("cluster.role", "standalone"));
    String clusterServerId = normalizeServerId(config.getString("cluster.server-id"), clusterRole);
    ClusterSettings clusterSettings = new ClusterSettings(
        clusterRole,
        clusterServerId,
        Math.max(30, config.getInt("cluster.presence-ttl-seconds", 120)));

    boolean corsEnabled = config.getBoolean("webshop.embedded-http.cors.enabled", false);
    List<String> corsAllowedOrigins = config.getStringList("webshop.embedded-http.cors.allowed-origins");
    if (corsAllowedOrigins == null || corsAllowedOrigins.isEmpty()) {
      corsAllowedOrigins = List.of("*");
    }

    EmbeddedWebSettings webSettings = new EmbeddedWebSettings(
        config.getString("webshop.embedded-http.host", "0.0.0.0"),
        config.getInt("webshop.embedded-http.port", 8819),
        config.getString("webshop.embedded-http.static-root", "web"),
        corsEnabled,
        corsAllowedOrigins);

    DbType databaseType = DbType.fromRaw(config.getString("database.type", "sqlite"));
    DatabaseSettings databaseSettings = new DatabaseSettings(
        databaseType,
        config.getString("database.host", "127.0.0.1"),
        config.getInt("database.port", 3306),
        config.getString("database.schema", "webshop"),
        config.getString("database.username", "webshop"),
        config.getString("database.password", "change_me"),
        config.getBoolean("database.use-ssl", false),
        config.getBoolean("database.allow-public-key-retrieval", true),
        config.getString("database.server-rsa-public-key-file", ""),
        config.getInt("database.pool-size", 10),
        config.getString("database.sqlite-file", "plugins/WebShopX/webshopx.db"),
        config.getString("database.sqlite-journal-mode", "WAL"),
        config.getString("database.sqlite-synchronous", "NORMAL"),
        config.getInt("database.sqlite-busy-timeout-ms", 5_000),
        config.getInt("database.sqlite-max-retries", 5),
        normalizeRetryBackoff(config.getIntegerList("database.sqlite-retry-backoff-ms")));

    ExchangeDirection shopToGame = new ExchangeDirection(
        config.getBoolean("exchange.shopcoin-to-gamecoin.enabled", true),
        config.getDouble("exchange.shopcoin-to-gamecoin.ratio", 1.0));
    ExchangeDirection gameToShop = new ExchangeDirection(
        config.getBoolean("exchange.gamecoin-to-shopcoin.enabled", false),
        config.getDouble("exchange.gamecoin-to-shopcoin.ratio", 1.0));

    RedisSettings redisSettings = new RedisSettings(
        config.getBoolean("redis.enabled", false),
        config.getString("redis.host", "127.0.0.1"),
        config.getInt("redis.port", 6379),
        config.getString("redis.password", ""),
        firstNonBlank(
            config.getString("redis.broadcast-channel"),
            config.getString("redis.channel"),
            "webshopx:market:broadcast"),
        config.getString("redis.cluster-channel", "webshopx:cluster:event"));

    AdminBootstrapSettings adminBootstrapSettings = new AdminBootstrapSettings(
        config.getBoolean("webshop.admin-bootstrap.enabled", false),
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
    MarketSupplySettings marketSupplySettings = new MarketSupplySettings(
        config.getInt("webshop.market.supply.auto-refresh-threshold", 8),
        config.getInt("webshop.market.supply.default-transfer-batch-size", 64),
        config.getInt("webshop.market.supply.max-transfer-batch-size", 256),
        config.getInt("webshop.market.supply.default-transit-stock", 256),
        config.getInt("webshop.market.supply.max-transit-stock", 1024));
    LeaderboardSettings leaderboardSettings = new LeaderboardSettings(
      config.getBoolean("webshop.leaderboard.enabled", true),
      config.getBoolean("webshop.leaderboard.show-online-status", true),
      LeaderboardMetric.fromRaw(config.getString("webshop.leaderboard.default-metric", "GAME_COIN")),
      SortDirection.fromRaw(config.getString("webshop.leaderboard.default-order", "DESC")));
    InflationSettings inflationSettings = new InflationSettings(
        InflationMode.fromRaw(config.getString("economy.inflation-control.mode", "burn")),
        config.getLong("economy.inflation-control.treasury-user-id", 0L));

    MaintenanceSettings maintenanceSettings = new MaintenanceSettings(
        config.getInt("webshop.maintenance.cleanup-interval-minutes", 30),
        config.getInt("webshop.maintenance.pending-bind-retention-hours", 6),
        config.getInt("webshop.maintenance.pending-password-retention-hours", 6),
        config.getInt("webshop.maintenance.bind-request-retention-hours", 24),
        config.getInt("webshop.maintenance.redeem-code-retention-days", 7));

    LoggingSettings loggingSettings = new LoggingSettings(
        config.getBoolean("webshop.logging.enabled", true),
        LogLevel.fromRaw(config.getString("webshop.logging.level", "INFO")),
        config.getString("webshop.logging.directory", "logs"),
        config.getInt("webshop.logging.max-file-size-mb", 8),
        config.getInt("webshop.logging.max-files", 8),
        config.getInt("webshop.logging.retention-days", 14));
    BusinessLedgerSettings businessLedgerSettings = new BusinessLedgerSettings(
        config.getBoolean("webshop.business-ledger.enabled", true),
        config.getString("webshop.business-ledger.directory", "logs/business-ledger"),
        config.getInt("webshop.business-ledger.retention-days", 30));
    BroadcastSettings broadcastSettings = new BroadcastSettings(
        config.getBoolean("webshop.broadcast.enabled", true),
        readBroadcastTemplates(config.getConfigurationSection("webshop.broadcast.templates")));

    return new PluginSettings(
        mode,
        clusterSettings,
        normalizeApiBaseUrl(config.getString("webshop.api-base-url", "")),
        new PaymentSettings(
            normalizeProviderId(config.getString("payment.provider", "")),
            normalizePaymentCurrencies(config.getStringList("payment.recharge.currencies")),
            normalizePaymentMethods(config.getStringList("payment.recharge.methods"))),
        normalizeLocale(config.getString("webshop.default-locale", "zh-CN")),
        config.getInt("webshop.session-expire-hours", 72),
        config.getInt("webshop.bind-request-expire-minutes", 15),
        config.getInt("webshop.access-token-length", 48),
        config.getInt("webshop.delivery-batch-size", 20),
        config.getInt("webshop.delivery-retry-seconds", 30),
        config.getInt("webshop.order-cooldown-seconds", 15),
        config.getInt("webshop.recharge-order-expire-minutes", 15),
        config.getBoolean("webshop.allow-shared-claim-command", false),
        config.getBoolean("webshop.refund-undelivered-enabled", true),
        config.getBoolean("webshop.recycle.advanced-enabled", false),
        parseZoneId(config.getString("webshop.time-zone", "Asia/Shanghai")),
        config.getInt("webshop.market.max-active-listings", 10),
        marketSupplySettings,
        leaderboardSettings,
        currencyDisplaySettings,
        maintenanceSettings,
        loggingSettings,
        businessLedgerSettings,
        broadcastSettings,
        adminBootstrapSettings,
        webSettings,
        databaseSettings,
        new ExchangeSettings(shopToGame, gameToShop),
        new EconomySettings(marketEconomySettings, inflationSettings),
        redisSettings,
        readProductSeeds(config));
  }

  PluginSettings withBusinessSettings(
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
      MarketSupplySettings marketSupplySettings,
      ExchangeSettings exchangeSettings,
      EconomySettings economySettings,
      LeaderboardSettings leaderboardSettings,
      CurrencyDisplaySettings currencyDisplaySettings,
      MaintenanceSettings maintenanceSettings,
      LoggingSettings loggingSettings,
      BroadcastSettings broadcastSettings) {
    return new PluginSettings(
        serverMode,
        clusterSettings,
        apiBaseUrl,
        paymentSettings,
        defaultLocale,
        sessionExpireHours,
        bindRequestExpireMinutes,
        accessTokenLength,
        deliveryBatchSize,
        deliveryRetrySeconds,
        orderCooldownSeconds,
        rechargeOrderExpireMinutes,
        allowSharedClaimCommand,
        refundUndeliveredEnabled,
        advancedRecycleEnabled,
        timeZone,
        marketMaxActiveListings,
        marketSupplySettings,
        leaderboardSettings,
        currencyDisplaySettings,
        maintenanceSettings,
        loggingSettings,
        businessLedgerSettings,
        broadcastSettings,
        adminBootstrapSettings,
        embeddedWebSettings,
        databaseSettings,
        exchangeSettings,
        economySettings,
        redisSettings,
        productSeeds);
  }

  PluginSettings withPaymentSettings(PaymentSettings paymentSettings) {
    return new PluginSettings(
        serverMode,
        clusterSettings,
        apiBaseUrl,
        paymentSettings == null ? this.paymentSettings : paymentSettings,
        defaultLocale,
        sessionExpireHours,
        bindRequestExpireMinutes,
        accessTokenLength,
        deliveryBatchSize,
        deliveryRetrySeconds,
        orderCooldownSeconds,
        rechargeOrderExpireMinutes,
        allowSharedClaimCommand,
        refundUndeliveredEnabled,
        advancedRecycleEnabled,
        timeZone,
        marketMaxActiveListings,
        marketSupplySettings,
        leaderboardSettings,
        currencyDisplaySettings,
        maintenanceSettings,
        loggingSettings,
        businessLedgerSettings,
        broadcastSettings,
        adminBootstrapSettings,
        embeddedWebSettings,
        databaseSettings,
        exchangeSettings,
        economySettings,
        redisSettings,
        productSeeds);
  }

  private static String normalizeApiBaseUrl(String rawApiBaseUrl) {
    if (rawApiBaseUrl == null) {
      return "";
    }
    String normalized = rawApiBaseUrl.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static String normalizeProviderId(String rawProviderId) {
    if (rawProviderId == null || rawProviderId.isBlank()) {
      return "";
    }
    return rawProviderId.trim().toLowerCase(Locale.ROOT);
  }

  static List<String> normalizePaymentCurrencies(List<String> rawCurrencies) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (rawCurrencies != null) {
      for (String raw : rawCurrencies) {
        if (raw == null) {
          continue;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.matches("^[A-Z]{3,8}$")) {
          normalized.add(value);
        }
      }
    }
    if (normalized.isEmpty()) {
      normalized.add("CNY");
    }
    return Collections.unmodifiableList(new ArrayList<>(normalized));
  }

  static List<PaymentMethod> normalizePaymentMethods(List<String> rawMethods) {
    LinkedHashSet<PaymentMethod> normalized = new LinkedHashSet<>();
    if (rawMethods != null) {
      for (String raw : rawMethods) {
        PaymentMethod method = parsePaymentMethod(raw);
        if (method != null) {
          normalized.add(method);
        }
      }
    }
    if (normalized.isEmpty()) {
      normalized.add(PaymentMethod.ALIPAY);
    }
    return Collections.unmodifiableList(new ArrayList<>(normalized));
  }

  static PaymentMethod parsePaymentMethod(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return PaymentMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String normalizeLocale(String rawLocale) {
    if (rawLocale == null || rawLocale.isBlank()) {
      return "zh-CN";
    }
    String normalized = rawLocale.trim().replace('_', '-');
    if (normalized.equalsIgnoreCase("zh") || normalized.regionMatches(true, 0, "zh-", 0, 3)) {
      return "zh-CN";
    }
    if (normalized.equalsIgnoreCase("en") || normalized.regionMatches(true, 0, "en-", 0, 3)) {
      return "en-US";
    }
    return normalized;
  }

  private static ZoneId parseZoneId(String rawZoneId) {
    String normalized = rawZoneId == null || rawZoneId.isBlank() ? "Asia/Shanghai" : rawZoneId.trim();
    try {
      return ZoneId.of(normalized);
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException("Invalid webshop.time-zone: " + normalized, exception);
    }
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

  private static Map<String, String> readBroadcastTemplates(ConfigurationSection section) {
    Map<String, String> templates = new java.util.LinkedHashMap<>();
    if (section == null) {
      return templates;
    }
    for (String key : section.getKeys(false)) {
      String value = section.getString(key);
      if (value != null && !value.isBlank()) {
        templates.put(key, value);
      }
    }
    return templates;
  }

  private static String firstNonBlank(String first, String second, String fallback) {
    if (first != null && !first.isBlank()) {
      return first.trim();
    }
    if (second != null && !second.isBlank()) {
      return second.trim();
    }
    return fallback;
  }

  private static List<Integer> normalizeRetryBackoff(List<Integer> rawBackoff) {
    if (rawBackoff == null || rawBackoff.isEmpty()) {
      return List.of(10, 50, 100);
    }
    List<Integer> normalized = new ArrayList<>();
    for (Integer value : rawBackoff) {
      if (value == null || value < 0) {
        continue;
      }
      normalized.add(value);
    }
    if (normalized.isEmpty()) {
      return List.of(10, 50, 100);
    }
    return Collections.unmodifiableList(normalized);
  }

  private static String normalizeServerId(String raw, ClusterRole role) {
    if (raw != null && !raw.isBlank()) {
      return raw.trim();
    }
    return role.name().toLowerCase(Locale.ROOT);
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

  enum ClusterRole {
    STANDALONE,
    MASTER,
    NODE;

    static ClusterRole fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return STANDALONE;
      }
      String normalized = raw.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "master", "primary" -> MASTER;
        case "node", "slave", "worker" -> NODE;
        default -> STANDALONE;
      };
    }
  }

  record ClusterSettings(ClusterRole role, String serverId, int presenceTtlSeconds) {
    boolean shouldStartWebApi() {
      return role == ClusterRole.STANDALONE || role == ClusterRole.MASTER;
    }

    boolean allowUnassignedDeliveryExecution() {
      return role == ClusterRole.STANDALONE;
    }
  }

  record EmbeddedWebSettings(String host, int port, String staticRoot, boolean corsEnabled, List<String> corsAllowedOrigins) {
  }

  record PaymentSettings(
      String provider,
      List<String> rechargeCurrencies,
      List<PaymentMethod> rechargeMethods,
      List<RechargeRate> rechargeRates) {
    PaymentSettings(String provider, List<String> rechargeCurrencies, List<PaymentMethod> rechargeMethods) {
      this(provider, rechargeCurrencies, rechargeMethods, List.of());
    }

    PaymentSettings {
      provider = normalizeProviderId(provider);
      rechargeCurrencies = normalizePaymentCurrencies(rechargeCurrencies);
      rechargeMethods = normalizePaymentMethods(paymentMethodNames(rechargeMethods));
      rechargeRates = normalizeRechargeRates(rechargeRates, rechargeCurrencies, rechargeMethods);
    }

    boolean isCurrencyAllowed(String currency) {
      if (currency == null || currency.isBlank()) {
        return false;
      }
      return rechargeCurrencies.contains(currency.trim().toUpperCase(Locale.ROOT));
    }

    String primaryRechargeCurrency() {
      return rechargeCurrencies.isEmpty() ? "CNY" : rechargeCurrencies.get(0);
    }

    boolean isMethodAllowed(PaymentMethod method) {
      if (method == null) {
        PaymentMethod fallback = rechargeMethods.isEmpty() ? PaymentMethod.ALIPAY : rechargeMethods.get(0);
        return rechargeMethods.contains(fallback);
      }
      return rechargeMethods.contains(method);
    }

    RechargeRate rechargeRate(PaymentMethod method, String currency) {
      String normalizedCurrency = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
      if (method != null) {
        for (RechargeRate rate : rechargeRates) {
          if (rate.method() == method && rate.currency().equals(normalizedCurrency)) {
            return rate;
          }
        }
      }
      for (RechargeRate rate : rechargeRates) {
        if (rate.currency().equals(normalizedCurrency)) {
          return rate;
        }
      }
      return null;
    }

    private static List<String> paymentMethodNames(List<PaymentMethod> methods) {
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
  }

  record RechargeRate(PaymentMethod method, String currency, long coinsPerUnit) {
    RechargeRate {
      method = method == null ? PaymentMethod.ALIPAY : method;
      currency = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
      coinsPerUnit = Math.max(1L, coinsPerUnit);
    }
  }

  static List<RechargeRate> normalizeRechargeRates(
      List<RechargeRate> rawRates,
      List<String> currencies,
      List<PaymentMethod> methods) {
    Map<String, RechargeRate> normalized = new java.util.LinkedHashMap<>();
    if (rawRates != null) {
      for (RechargeRate raw : rawRates) {
        if (raw == null || raw.currency() == null || !raw.currency().matches("^[A-Z]{3,8}$")) {
          continue;
        }
        RechargeRate rate = new RechargeRate(raw.method(), raw.currency(), raw.coinsPerUnit());
        normalized.put(rate.method().name() + ":" + rate.currency(), rate);
      }
    }
    if (normalized.isEmpty()) {
      List<String> normalizedCurrencies = normalizePaymentCurrencies(currencies);
      List<PaymentMethod> normalizedMethods = normalizePaymentMethods(PaymentSettings.paymentMethodNames(methods));
      for (PaymentMethod method : normalizedMethods) {
        for (String currency : normalizedCurrencies) {
          RechargeRate rate = new RechargeRate(method, currency, 100L);
          normalized.put(rate.method().name() + ":" + rate.currency(), rate);
        }
      }
    }
    return Collections.unmodifiableList(new ArrayList<>(normalized.values()));
  }

  record AdminBootstrapSettings(boolean enabled, String username, String password, String role) {
  }

  record DatabaseSettings(
      DbType type,
      String host,
      int port,
      String schema,
      String username,
      String password,
      boolean useSsl,
      boolean allowPublicKeyRetrieval,
      String serverRsaPublicKeyFile,
      int poolSize,
      String sqliteFile,
      String sqliteJournalMode,
      String sqliteSynchronous,
      int sqliteBusyTimeoutMs,
      int sqliteMaxRetries,
      List<Integer> sqliteRetryBackoffMs) {

    String jdbcUrl() {
      return jdbcUrl(false);
    }

    String jdbcUrl(boolean forceAllowPublicKeyRetrieval) {
      if (type.isSqlite()) {
        return sqliteJdbcUrl();
      }
      return mysqlJdbcUrl(forceAllowPublicKeyRetrieval);
    }

    String mysqlJdbcUrl(boolean forceAllowPublicKeyRetrieval) {
      String sslParam = useSsl ? "true" : "false";
      boolean enablePublicKeyRetrieval =
          !useSsl && (forceAllowPublicKeyRetrieval || allowPublicKeyRetrieval);
      String rsaPublicKeyFile = trimmedServerRsaPublicKeyFile();
      StringBuilder url = new StringBuilder(
          String.format(
              Locale.ROOT,
              "jdbc:mariadb://%s:%d/%s?useSsl=%s&characterEncoding=utf8",
              host,
              port,
              schema,
              sslParam));
      if (enablePublicKeyRetrieval) {
        url.append("&allowPublicKeyRetrieval=true");
      }
      if (!rsaPublicKeyFile.isEmpty()) {
        url.append("&serverRsaPublicKeyFile=").append(encodeUrlComponent(rsaPublicKeyFile));
      }
      return url.toString();
    }

    String sqliteJdbcUrl() {
      return "jdbc:sqlite:" + normalizedSqliteFile();
    }

    String normalizedSqliteFile() {
      if (sqliteFile == null || sqliteFile.isBlank()) {
        return "plugins/WebShopX/webshopx.db";
      }
      return sqliteFile.trim();
    }

    String normalizedSqliteJournalMode() {
      if (sqliteJournalMode == null || sqliteJournalMode.isBlank()) {
        return "WAL";
      }
      String normalized = sqliteJournalMode.trim().toUpperCase(Locale.ROOT);
      if (!normalized.equals("DELETE")
          && !normalized.equals("TRUNCATE")
          && !normalized.equals("PERSIST")
          && !normalized.equals("MEMORY")
          && !normalized.equals("WAL")
          && !normalized.equals("OFF")) {
        return "WAL";
      }
      return normalized;
    }

    String normalizedSqliteSynchronous() {
      if (sqliteSynchronous == null || sqliteSynchronous.isBlank()) {
        return "NORMAL";
      }
      String normalized = sqliteSynchronous.trim().toUpperCase(Locale.ROOT);
      if (!normalized.equals("OFF")
          && !normalized.equals("NORMAL")
          && !normalized.equals("FULL")
          && !normalized.equals("EXTRA")) {
        return "NORMAL";
      }
      return normalized;
    }

    int normalizedSqliteBusyTimeoutMs() {
      return Math.max(0, sqliteBusyTimeoutMs);
    }

    int normalizedSqliteMaxRetries() {
      return Math.min(10, Math.max(0, sqliteMaxRetries));
    }

    List<Integer> normalizedSqliteRetryBackoffMs() {
      if (sqliteRetryBackoffMs == null || sqliteRetryBackoffMs.isEmpty()) {
        return List.of(10, 50, 100);
      }
      List<Integer> normalized = new ArrayList<>();
      for (Integer value : sqliteRetryBackoffMs) {
        if (value == null || value < 0) {
          continue;
        }
        normalized.add(value);
      }
      if (normalized.isEmpty()) {
        return List.of(10, 50, 100);
      }
      return Collections.unmodifiableList(normalized);
    }

    boolean canAutoRetryWithPublicKeyRetrieval() {
      if (!type.isMysqlFamily()) {
        return false;
      }
      return !useSsl && !allowPublicKeyRetrieval && trimmedServerRsaPublicKeyFile().isEmpty();
    }

    String trimmedServerRsaPublicKeyFile() {
      return serverRsaPublicKeyFile == null ? "" : serverRsaPublicKeyFile.trim();
    }

    private static String encodeUrlComponent(String value) {
      return String.format(
          Locale.ROOT,
          "%s",
          java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    boolean usesDefaultPlaceholders() {
      if (!type.isMysqlFamily()) {
        return false;
      }
      return "127.0.0.1".equals(host)
          && port == 3306
          && "webshop".equals(schema)
          && "webshop".equals(username)
          && "change_me".equals(password);
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

  record LoggingSettings(
      boolean enabled,
      LogLevel level,
      String directory,
      int maxFileSizeMb,
      int maxFiles,
      int retentionDays) {
  }

  record BusinessLedgerSettings(
      boolean enabled,
      String directory,
      int retentionDays) {
  }

  record BroadcastSettings(boolean enabled, Map<String, String> templates) {
    String template(String key) {
      if (templates == null || key == null) {
        return "";
      }
      return templates.getOrDefault(key, "");
    }
  }

  enum LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE;

    static LogLevel fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return INFO;
      }
      try {
        return LogLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        return INFO;
      }
    }
  }

  record EconomySettings(MarketEconomySettings marketSettings, InflationSettings inflationSettings) {
  }

  record MarketEconomySettings(double tradeFeePercent, double tradeTaxPercent) {
  }

  record MarketSupplySettings(
      int autoRefreshThreshold,
      int defaultTransferBatchSize,
      int maxTransferBatchSize,
      int defaultTransitStock,
      int maxTransitStock) {
  }

  record LeaderboardSettings(
      boolean enabled,
      boolean showOnlineStatus,
      LeaderboardMetric defaultMetric,
      SortDirection defaultOrder) {
  }

  enum LeaderboardMetric {
    GAME_COIN,
    SHOP_COIN,
    ONLINE_TIME;

    static LeaderboardMetric fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return GAME_COIN;
      }
      try {
        return LeaderboardMetric.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        return GAME_COIN;
      }
    }
  }

  enum SortDirection {
    ASC,
    DESC;

    static SortDirection fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return DESC;
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      if ("ASC".equals(normalized) || "ASCENDING".equals(normalized)) {
        return ASC;
      }
      return DESC;
    }
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

  record RedisSettings(
      boolean enabled,
      String host,
      int port,
      String password,
      String broadcastChannel,
      String clusterChannel) {
  }

  record ProductSeed(String sku, String title, CurrencyType currency, long price, String commandTemplate) {
  }
}
