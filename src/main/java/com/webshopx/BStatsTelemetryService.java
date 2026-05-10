package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

class BStatsTelemetryService {
  private static final String EVENT_API_DOMAIN = "api_domain";
  private static final String EVENT_API_STATUS = "api_status";
  private static final String EVENT_API_LATENCY = "api_latency";
  private static final String EVENT_API_ERROR = "api_error";
  private static final String EVENT_IDEMPOTENCY = "idempotency";
  private static final String EVENT_LOCALE_USAGE = "locale_usage";
  private static final String EVENT_STARTUP_SUCCESS = "startup_success";
  private static final String EVENT_STARTUP_FAILURE = "startup_failure";
  private static final String META_STARTUP_LAST_RESULT = "bstats.startup.last_result";
  private static final String META_STARTUP_LAST_FAILURE = "bstats.startup.last_failure";
  private static final String META_STARTUP_LAST_ATTEMPT_AT = "bstats.startup.last_attempt_at";
  private static final String META_STARTUP_LAST_SUCCESS_AT = "bstats.startup.last_success_at";
  private static final int MAX_BUCKET_LENGTH = 96;

  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;

  BStatsTelemetryService(DatabaseManager databaseManager, Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
    ensureTelemetrySchema();
  }

  void markStartupAttempt() {
    try {
      upsertMeta(META_STARTUP_LAST_RESULT, "starting");
      upsertMeta(META_STARTUP_LAST_ATTEMPT_AT, Instant.now().toString());
    } catch (Exception exception) {
      // Intentionally swallow telemetry failures.
    }
  }

  void markStartupSuccess() {
    try {
      incrementDailyEvent(EVENT_STARTUP_SUCCESS, "success", 1L);
      upsertMeta(META_STARTUP_LAST_RESULT, "success");
      upsertMeta(META_STARTUP_LAST_SUCCESS_AT, Instant.now().toString());
    } catch (Exception exception) {
      // Intentionally swallow telemetry failures.
    }
  }

  void markStartupFailure(String reason) {
    String normalized = normalizeBucketKey(reason == null ? "unknown" : reason);
    try {
      incrementDailyEvent(EVENT_STARTUP_FAILURE, normalized, 1L);
      upsertMeta(META_STARTUP_LAST_RESULT, "failure");
      upsertMeta(META_STARTUP_LAST_FAILURE, normalized);
    } catch (Exception exception) {
      // Intentionally swallow telemetry failures.
    }
  }

  void recordApiRequest(
      String requestPath,
      int statusCode,
      String locale,
      long durationMillis,
      String errorCode,
      String responseState) {
    String domain = normalizeBucketKey(resolveApiDomain(requestPath));
    String statusBucket = normalizeBucketKey(resolveStatusBucket(statusCode));
    String latencyBucket = normalizeBucketKey(resolveLatencyBucket(durationMillis));
    String errorBucket = normalizeBucketKey(resolveApiErrorBucket(statusCode, errorCode));
    String normalizedLocale = normalizeLocale(locale);
    try {
      incrementDailyEvent(EVENT_API_DOMAIN, domain, 1L);
      incrementDailyEvent(EVENT_API_STATUS, statusBucket, 1L);
      incrementDailyEvent(EVENT_API_LATENCY, latencyBucket, 1L);
      incrementDailyEvent(EVENT_API_ERROR, errorBucket, 1L);
      incrementDailyEvent(EVENT_LOCALE_USAGE, normalizedLocale, 1L);
      if (responseState != null && !responseState.isBlank()) {
        if ("EXISTING".equalsIgnoreCase(responseState)) {
          incrementDailyEvent(EVENT_IDEMPOTENCY, "hit", 1L);
        } else if ("CREATED".equalsIgnoreCase(responseState)) {
          incrementDailyEvent(EVENT_IDEMPOTENCY, "miss", 1L);
        }
      }
    } catch (Exception exception) {
      // Intentionally swallow telemetry failures.
    }
  }

  void recordPlayerLocale(String locale) {
    try {
      incrementDailyEvent(EVENT_LOCALE_USAGE, normalizeLocale(locale), 1L);
    } catch (Exception exception) {
      // Intentionally swallow telemetry failures.
    }
  }

  Snapshot captureSnapshot(WalletService walletService) {
    return databaseManager.withConnection(connection -> {
      LocalDateTimeWindow window = LocalDateTimeWindow.ofNowUtcDays(7);
      LocalDateTimeWindow window30 = LocalDateTimeWindow.ofNowUtcDays(30);
      LocalDateTimeWindow clusterWindow = LocalDateTimeWindow.ofNowUtcSeconds(
          Math.max(30, settingsSupplier.get().clusterSettings().presenceTtlSeconds()));
      PluginSettings settings = settingsSupplier.get();

      int onlineNodeCount = readOnlineNodeCount(connection, clusterWindow.cutoff());
      int officialProductCount = readActiveOfficialProductCount(connection, window.nowTimestamp());
      Map<String, Integer> officialProductTypes = readActiveOfficialProductTypeCounts(connection, window.nowTimestamp());
      MarketCounts marketCounts = readMarketCounts(connection);
      int sellerCount = readActiveMarketSellerCount(connection);
      int auctionCount = readActiveAuctionCount(connection);
      TradeVolume tradeVolume = readTradeVolume(connection, window30.cutoff());
      OrderFunnel orderFunnel = readOrderFunnel(connection);
      MarketLiquidity marketLiquidity = readMarketLiquidity(connection, window.nowTimestamp());
      EconomyHealth economyHealth = readEconomyHealth(connection, window30.cutoff());
      RetryBacklog retryBacklog = readRetryBacklog(connection);
      WalletService.GameCoinIntegrationStatus gameCoinStatus = walletService.getGameCoinIntegrationStatus();
      Map<String, Integer> localeUsage = readDailyEventCounts(connection, EVENT_LOCALE_USAGE, 7);
      RetentionSignals retentionSignals = readRetentionSignals(connection, window.cutoff(), window30.cutoff());
      Map<String, Integer> startupSignals = readStartupSignalCounts(connection, 7);
      Map<String, Integer> apiUsage = readDailyEventCounts(connection, EVENT_API_DOMAIN, 7);
      int apiTotal = readDailyEventTotal(connection, EVENT_API_DOMAIN, 7);
      Map<String, Integer> apiLatencyBuckets = readDailyEventCounts(connection, EVENT_API_LATENCY, 7);
      LatencyPercentile latencyPercentile = resolveLatencyPercentile(apiLatencyBuckets);
      Map<String, Integer> apiErrorDistribution = filterErrorOnly(readDailyEventCounts(connection, EVENT_API_ERROR, 7));
      IdempotencyStats idempotencyStats = readIdempotencyStats(connection, 7);
      EconomyDistribution economyDistribution = readEconomyDistribution(connection);
      String startupLastResult = readMeta(connection, META_STARTUP_LAST_RESULT, "unknown");
      String startupLastFailure = readMeta(connection, META_STARTUP_LAST_FAILURE, "none");
      AdminDistribution adminDistribution = readAdminDistribution(connection);

      return new Snapshot(
          settings.databaseSettings().type().name().toLowerCase(Locale.ROOT),
          settings.clusterSettings().role() == PluginSettings.ClusterRole.STANDALONE ? "disabled" : "enabled",
          nodeScaleBucket(onlineNodeCount, settings.clusterSettings().role()),
          settings.clusterSettings().shouldStartWebApi() ? "enabled" : "disabled",
          settings.serverMode() == PluginSettings.ServerMode.INTERNAL && settings.clusterSettings().shouldStartWebApi()
              ? "enabled"
              : "disabled",
          officialProductCount,
          withNoDataFallback(officialProductTypes, "no_data"),
          marketCounts.sellCount(),
          marketCounts.buyCount(),
          sellerCount,
          auctionCount,
          tradeVolume.totalCount(),
          withNoDataFallback(tradeVolume.breakdown(), "no_data"),
          withNoDataFallback(orderFunnel.toMap(), "no_data"),
          withNoDataFallback(marketLiquidity.toMap(), "no_data"),
          withNoDataFallback(economyHealth.flowMap(), "no_data"),
          economyHealth.netDirection(),
          economyHealth.netDeltaAbs(),
          withNoDataFallback(retryBacklog.retryBucketMap(), "no_data"),
          withNoDataFallback(retryBacklog.backlogMap(), "no_data"),
          idempotencyStats.hitRatePercent(),
          withNoDataFallback(idempotencyStats.toMap(), "no_data"),
          withNoDataFallback(economyDistribution.shopCoinDistribution(), "no_data"),
          withNoDataFallback(economyDistribution.gameCoinDistribution(), "no_data"),
          withNoDataFallback(economyDistribution.totalEconomyDistribution(), "no_data"),
          economyDistribution.totalShopCoin(),
          economyDistribution.totalGameCoin(),
          gameCoinStatus.hooked() ? "enabled" : "disabled",
          resolveVaultProviderType(gameCoinStatus),
          withNoDataFallback(localeUsage, "no_data"),
          retentionSignals.toMap(),
          withNoDataFallback(startupSignals, "no_data"),
          apiTotal,
          withNoDataFallback(apiUsage, "no_data"),
          withNoDataFallback(apiLatencyBuckets, "no_data"),
          latencyPercentile.p50Bucket(),
          latencyPercentile.p95Bucket(),
          latencyPercentile.p50EstimateMs(),
          latencyPercentile.p95EstimateMs(),
          withNoDataFallback(apiErrorDistribution, "no_data"),
          startupLastResult,
          startupLastFailure,
          adminDistribution.activeAdminCount(),
          withNoDataFallback(adminDistribution.roleDistribution(), "no_data"),
          withNoDataFallback(adminDistribution.permissionDistribution(), "no_data"));
    });
  }

  private void ensureTelemetrySchema() {
    databaseManager.withConnection(connection -> {
      String sql;
      if (databaseManager.dbType().isSqlite()) {
        sql = """
            CREATE TABLE IF NOT EXISTS bstats_telemetry_daily (
              event_date TEXT NOT NULL,
              event_type TEXT NOT NULL,
              bucket_key TEXT NOT NULL,
              event_count INTEGER NOT NULL DEFAULT 0,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (event_date, event_type, bucket_key)
            )
            """;
      } else {
        sql = """
            CREATE TABLE IF NOT EXISTS bstats_telemetry_daily (
              event_date DATE NOT NULL,
              event_type VARCHAR(48) NOT NULL,
              bucket_key VARCHAR(96) NOT NULL,
              event_count BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (event_date, event_type, bucket_key),
              KEY idx_bstats_telemetry_type_date (event_type, event_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
      }
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.execute();
      }
      return null;
    });
  }

  private void incrementDailyEvent(String eventType, String bucketKey, long delta) {
    if (delta == 0L) {
      return;
    }
    LocalDate utcToday = LocalDate.now(java.time.ZoneOffset.UTC);
    databaseManager.inTransaction(connection -> {
      updateDailyEvent(connection, utcToday, eventType, bucketKey, delta);
      return null;
    });
  }

  private void updateDailyEvent(
      Connection connection,
      LocalDate date,
      String eventType,
      String bucketKey,
      long delta) throws SQLException {
    String updateSql = """
        UPDATE bstats_telemetry_daily
        SET event_count = event_count + ?, updated_at = CURRENT_TIMESTAMP
        WHERE event_date = ? AND event_type = ? AND bucket_key = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      statement.setLong(1, delta);
      bindEventDate(statement, 2, date);
      statement.setString(3, eventType);
      statement.setString(4, bucketKey);
      int affected = statement.executeUpdate();
      if (affected > 0) {
        return;
      }
    }

    String insertSql = """
        INSERT INTO bstats_telemetry_daily (event_date, event_type, bucket_key, event_count)
        VALUES (?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
      bindEventDate(statement, 1, date);
      statement.setString(2, eventType);
      statement.setString(3, bucketKey);
      statement.setLong(4, delta);
      statement.executeUpdate();
    } catch (SQLException exception) {
      updateDailyEventRetry(connection, date, eventType, bucketKey, delta, exception);
    }
  }

  private void updateDailyEventRetry(
      Connection connection,
      LocalDate date,
      String eventType,
      String bucketKey,
      long delta,
      SQLException original) throws SQLException {
    String retrySql = """
        UPDATE bstats_telemetry_daily
        SET event_count = event_count + ?, updated_at = CURRENT_TIMESTAMP
        WHERE event_date = ? AND event_type = ? AND bucket_key = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(retrySql)) {
      statement.setLong(1, delta);
      bindEventDate(statement, 2, date);
      statement.setString(3, eventType);
      statement.setString(4, bucketKey);
      if (statement.executeUpdate() == 0) {
        throw original;
      }
    }
  }

  private void bindEventDate(PreparedStatement statement, int index, LocalDate date) throws SQLException {
    if (databaseManager.dbType().isSqlite()) {
      statement.setString(index, date.toString());
      return;
    }
    statement.setDate(index, Date.valueOf(date));
  }

  private int readOnlineNodeCount(Connection connection, Timestamp cutoff) throws SQLException {
    String sql = """
        SELECT COUNT(DISTINCT server_id)
        FROM player_presence
        WHERE online = TRUE
          AND updated_at >= ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, cutoff);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
      }
    }
  }

  private int readActiveOfficialProductCount(Connection connection, Timestamp nowUtc) throws SQLException {
    String sql = """
        SELECT COUNT(*)
        FROM products
        WHERE active = TRUE
          AND (publish_at IS NULL OR publish_at <= ?)
          AND (unpublish_at IS NULL OR unpublish_at > ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, nowUtc);
      statement.setTimestamp(2, nowUtc);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
      }
    }
  }

  private Map<String, Integer> readActiveOfficialProductTypeCounts(Connection connection, Timestamp nowUtc)
      throws SQLException {
    String sql = """
        SELECT product_type, COUNT(*) AS cnt
        FROM products
        WHERE active = TRUE
          AND (publish_at IS NULL OR publish_at <= ?)
          AND (unpublish_at IS NULL OR unpublish_at > ?)
        GROUP BY product_type
        """;
    Map<String, Integer> result = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, nowUtc);
      statement.setTimestamp(2, nowUtc);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String type = resultSet.getString("product_type");
          int count = Math.max(0, resultSet.getInt("cnt"));
          if (type != null && !type.isBlank() && count > 0) {
            result.put(type.trim().toUpperCase(Locale.ROOT), count);
          }
        }
      }
    }
    return result;
  }

  private MarketCounts readMarketCounts(Connection connection) throws SQLException {
    String sql = """
        SELECT market_side, COUNT(*) AS cnt
        FROM market_listings
        WHERE (
          (status = 'ACTIVE' AND (quantity > 0 OR source_mode = 'SUPPLY'))
          OR (source_mode = 'SUPPLY' AND status = 'PAUSED' AND quantity = 0)
        )
        GROUP BY market_side
        """;
    int sell = 0;
    int buy = 0;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String side = resultSet.getString("market_side");
        int count = Math.max(0, resultSet.getInt("cnt"));
        if ("BUY".equalsIgnoreCase(side)) {
          buy += count;
        } else {
          sell += count;
        }
      }
    }
    return new MarketCounts(sell, buy);
  }

  private int readActiveMarketSellerCount(Connection connection) throws SQLException {
    String sql = """
        SELECT COUNT(DISTINCT seller_user_id)
        FROM market_listings
        WHERE (
          (status = 'ACTIVE' AND (quantity > 0 OR source_mode = 'SUPPLY'))
          OR (source_mode = 'SUPPLY' AND status = 'PAUSED' AND quantity = 0)
        )
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
    }
  }

  private int readActiveAuctionCount(Connection connection) throws SQLException {
    String sql = """
        SELECT COUNT(*)
        FROM market_listings
        WHERE trade_mode = 'AUCTION' AND status = 'ACTIVE'
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
    }
  }

  private TradeVolume readTradeVolume(Connection connection, Timestamp cutoff30d) throws SQLException {
    int orderCount = readCountWithCutoff(connection, "SELECT COUNT(*) FROM orders WHERE created_at >= ?", cutoff30d);
    int marketTradeCount =
        readCountWithCutoff(connection, "SELECT COUNT(*) FROM market_trades WHERE created_at >= ?", cutoff30d);
    Map<String, Integer> breakdown = new LinkedHashMap<>();
    if (orderCount > 0) {
      breakdown.put("official_shop_orders_30d", orderCount);
    }
    if (marketTradeCount > 0) {
      breakdown.put("player_market_trades_30d", marketTradeCount);
    }
    return new TradeVolume(orderCount + marketTradeCount, breakdown);
  }

  private OrderFunnel readOrderFunnel(Connection connection) throws SQLException {
    int placed = readCountSimple(connection, "SELECT COUNT(*) FROM orders");
    int pending = readCountSimple(connection, "SELECT COUNT(*) FROM orders WHERE status = 'PENDING'");
    int waitClaim = readCountSimple(connection, "SELECT COUNT(*) FROM orders WHERE status = 'WAIT_CLAIM'");
    int delivered = readCountSimple(connection, "SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'");
    int refunded = readCountSimple(connection, "SELECT COUNT(*) FROM orders WHERE status = 'REFUNDED'");
    int claimedItems = readCountSimple(
        connection,
        "SELECT COUNT(*) FROM delivery_queue WHERE status = 'DELIVERED' AND claimed_at IS NOT NULL");
    return new OrderFunnel(placed, pending, delivered, claimedItems, refunded, waitClaim);
  }

  private MarketLiquidity readMarketLiquidity(Connection connection, Timestamp nowUtc) throws SQLException {
    Timestamp cutoff24h = Timestamp.from(nowUtc.toInstant().minus(24L, ChronoUnit.HOURS));
    String sql = """
        SELECT created_at, sold_at, status
        FROM market_listings
        WHERE market_side = 'SELL'
          AND created_at >= ?
        """;
    int created24h = 0;
    int sold24h = 0;
    long soldDurationMillis = 0L;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, cutoff24h);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          created24h++;
          String status = resultSet.getString("status");
          Timestamp createdAt = resultSet.getTimestamp("created_at");
          Timestamp soldAt = resultSet.getTimestamp("sold_at");
          if (!"SOLD".equalsIgnoreCase(status) || createdAt == null || soldAt == null) {
            continue;
          }
          long duration = soldAt.getTime() - createdAt.getTime();
          if (duration < 0L || duration > 24L * 60L * 60L * 1000L) {
            continue;
          }
          sold24h++;
          soldDurationMillis += duration;
        }
      }
    }
    int sellThroughRate = created24h <= 0 ? 0 : (int) Math.min(100L, Math.round((sold24h * 100.0D) / created24h));
    int avgMinutes = sold24h <= 0 ? 0 : safeToInt(Math.round((soldDurationMillis / 60000.0D) / sold24h));
    return new MarketLiquidity(created24h, sold24h, sellThroughRate, avgMinutes);
  }

  private EconomyHealth readEconomyHealth(Connection connection, Timestamp cutoff30d) throws SQLException {
    String sql = """
        SELECT currency, delta
        FROM wallet_ledger
        WHERE created_at >= ?
        """;
    long shopMinted = 0L;
    long shopBurned = 0L;
    long gameMinted = 0L;
    long gameBurned = 0L;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, cutoff30d);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String currency = resultSet.getString("currency");
          long delta = resultSet.getLong("delta");
          if ("SHOP_COIN".equalsIgnoreCase(currency)) {
            if (delta >= 0) {
              shopMinted += delta;
            } else {
              shopBurned += Math.abs(delta);
            }
          } else if ("GAME_COIN".equalsIgnoreCase(currency)) {
            if (delta >= 0) {
              gameMinted += delta;
            } else {
              gameBurned += Math.abs(delta);
            }
          }
        }
      }
    }
    long net = (shopMinted - shopBurned) + (gameMinted - gameBurned);
    String netDirection = net > 0L ? "inflationary" : net < 0L ? "deflationary" : "balanced";
    return new EconomyHealth(
        safeToInt(shopMinted),
        safeToInt(shopBurned),
        safeToInt(gameMinted),
        safeToInt(gameBurned),
        netDirection,
        safeToInt(Math.abs(net)));
  }

  private RetryBacklog readRetryBacklog(Connection connection) throws SQLException {
    RetryAccumulator accumulator = new RetryAccumulator();
    readRetryRows(
        connection,
        "SELECT retry_count, status FROM delivery_queue WHERE status IN ('PENDING', 'WAIT_CLAIM')",
        accumulator);
    readRetryRows(
        connection,
        "SELECT retry_count, status FROM market_item_deliveries WHERE status IN ('PENDING', 'WAIT_CLAIM')",
        accumulator);
    return accumulator.toRetryBacklog();
  }

  private void readRetryRows(Connection connection, String sql, RetryAccumulator accumulator) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        int retryCount = Math.max(0, resultSet.getInt("retry_count"));
        String status = resultSet.getString("status");
        accumulator.record(retryCount, status);
      }
    }
  }

  private IdempotencyStats readIdempotencyStats(Connection connection, int days) throws SQLException {
    int hit = readDailyEventTotalByBucketPrefix(connection, EVENT_IDEMPOTENCY, days, "hit");
    int miss = readDailyEventTotalByBucketPrefix(connection, EVENT_IDEMPOTENCY, days, "miss");
    int total = hit + miss;
    int hitRate = total <= 0 ? 0 : (int) Math.min(100L, Math.round((hit * 100.0D) / total));
    return new IdempotencyStats(hit, miss, hitRate);
  }

  private EconomyDistribution readEconomyDistribution(Connection connection) throws SQLException {
    String sql = "SELECT shop_coin, game_coin FROM wallets";
    Map<String, Integer> shopDist = new LinkedHashMap<>();
    Map<String, Integer> gameDist = new LinkedHashMap<>();
    Map<String, Integer> totalDist = new LinkedHashMap<>();
    long totalShop = 0L;
    long totalGame = 0L;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        long shop = Math.max(0L, resultSet.getLong("shop_coin"));
        long game = Math.max(0L, resultSet.getLong("game_coin"));
        long total = shop + game;
        totalShop += shop;
        totalGame += game;
        shopDist.merge(balanceBucket(shop), 1, Integer::sum);
        gameDist.merge(balanceBucket(game), 1, Integer::sum);
        totalDist.merge(balanceBucket(total), 1, Integer::sum);
      }
    }
    return new EconomyDistribution(
        shopDist,
        gameDist,
        totalDist,
        safeToInt(totalShop),
        safeToInt(totalGame));
  }

  private int readCountWithCutoff(Connection connection, String sql, Timestamp cutoff) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, cutoff);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
      }
    }
  }

  private Map<String, Integer> readDailyEventCounts(Connection connection, String eventType, int days)
      throws SQLException {
    LocalDate since = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(Math.max(0, days - 1L));
    String sql = """
        SELECT bucket_key, SUM(event_count) AS total
        FROM bstats_telemetry_daily
        WHERE event_type = ? AND event_date >= ?
        GROUP BY bucket_key
        ORDER BY total DESC
        """;
    Map<String, Integer> result = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, eventType);
      bindEventDate(statement, 2, since);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String bucket = resultSet.getString("bucket_key");
          int total = safeToInt(resultSet.getLong("total"));
          if (bucket == null || bucket.isBlank() || total <= 0) {
            continue;
          }
          result.put(bucket, total);
        }
      }
    }
    return result;
  }

  private int readDailyEventTotal(Connection connection, String eventType, int days) throws SQLException {
    LocalDate since = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(Math.max(0, days - 1L));
    String sql = """
        SELECT COALESCE(SUM(event_count), 0) AS total
        FROM bstats_telemetry_daily
        WHERE event_type = ? AND event_date >= ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, eventType);
      bindEventDate(statement, 2, since);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? safeToInt(resultSet.getLong("total")) : 0;
      }
    }
  }

  private Map<String, Integer> readStartupSignalCounts(Connection connection, int days) throws SQLException {
    Map<String, Integer> result = new LinkedHashMap<>();
    int success = readDailyEventTotalByBucketPrefix(connection, EVENT_STARTUP_SUCCESS, days, "success");
    if (success > 0) {
      result.put("success", success);
    }
    Map<String, Integer> failureBuckets = readDailyEventCounts(connection, EVENT_STARTUP_FAILURE, days);
    int failureTotal = 0;
    for (Map.Entry<String, Integer> entry : failureBuckets.entrySet()) {
      if ("attempted".equals(entry.getKey())) {
        continue;
      }
      failureTotal += entry.getValue();
      result.put("failure:" + entry.getKey(), entry.getValue());
    }
    if (failureTotal > 0) {
      result.put("failure_total", failureTotal);
    }
    return result;
  }

  private int readDailyEventTotalByBucketPrefix(
      Connection connection,
      String eventType,
      int days,
      String bucketPrefix) throws SQLException {
    LocalDate since = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(Math.max(0, days - 1L));
    String sql = """
        SELECT COALESCE(SUM(event_count), 0) AS total
        FROM bstats_telemetry_daily
        WHERE event_type = ?
          AND event_date >= ?
          AND bucket_key = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, eventType);
      bindEventDate(statement, 2, since);
      statement.setString(3, bucketPrefix);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? safeToInt(resultSet.getLong("total")) : 0;
      }
    }
  }

  private RetentionSignals readRetentionSignals(Connection connection, Timestamp cutoff7d, Timestamp cutoff30d)
      throws SQLException {
    int totalUsers = readCountSimple(connection, "SELECT COUNT(*) FROM web_users");
    int newUsers7d = readCountWithCutoff(connection, "SELECT COUNT(*) FROM web_users WHERE created_at >= ?", cutoff7d);
    int activeUsers7d = readActiveUsersSince(connection, cutoff7d);
    int longTermActiveUsers30d = readLongTermActiveUsers(connection, cutoff30d);
    int silentDropoffUsers = readSilentDropoffUsers(connection, cutoff7d);
    return new RetentionSignals(totalUsers, newUsers7d, activeUsers7d, longTermActiveUsers30d, silentDropoffUsers);
  }

  private int readActiveUsersSince(Connection connection, Timestamp cutoff) throws SQLException {
    Set<Long> ids = new java.util.HashSet<>();
    addUserIdsSince(connection, ids, "SELECT DISTINCT user_id FROM web_sessions WHERE created_at >= ?", cutoff);
    addUserIdsSince(connection, ids, "SELECT DISTINCT user_id FROM orders WHERE created_at >= ?", cutoff);
    addUserIdsSince(connection, ids, "SELECT DISTINCT seller_user_id FROM market_listings WHERE created_at >= ?", cutoff);
    addUserIdsSince(connection, ids, "SELECT DISTINCT buyer_user_id FROM market_trades WHERE created_at >= ?", cutoff);
    addUserIdsSince(connection, ids, "SELECT DISTINCT seller_user_id FROM market_trades WHERE created_at >= ?", cutoff);
    addUserIdsSince(
        connection,
        ids,
        """
        SELECT DISTINCT u.id
        FROM web_users u
        JOIN player_presence p ON p.mc_uuid = u.bound_uuid
        WHERE p.updated_at >= ?
        """,
        cutoff);
    return ids.size();
  }

  private int readLongTermActiveUsers(Connection connection, Timestamp cutoff30d) throws SQLException {
    String sql = """
        SELECT COUNT(*)
        FROM web_users u
        WHERE u.created_at < ?
          AND (
            EXISTS (SELECT 1 FROM web_sessions s WHERE s.user_id = u.id AND s.created_at >= ?)
            OR EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.created_at >= ?)
            OR EXISTS (SELECT 1 FROM market_listings ml WHERE ml.seller_user_id = u.id AND ml.created_at >= ?)
            OR EXISTS (
              SELECT 1 FROM market_trades mt
              WHERE (mt.seller_user_id = u.id OR mt.buyer_user_id = u.id) AND mt.created_at >= ?
            )
          )
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, cutoff30d);
      statement.setTimestamp(2, cutoff30d);
      statement.setTimestamp(3, cutoff30d);
      statement.setTimestamp(4, cutoff30d);
      statement.setTimestamp(5, cutoff30d);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
      }
    }
  }

  private int readSilentDropoffUsers(Connection connection, Timestamp cutoff7d) throws SQLException {
    String sql = """
        SELECT COUNT(*)
        FROM web_users u
        WHERE u.created_at < ?
          AND NOT EXISTS (SELECT 1 FROM web_sessions s WHERE s.user_id = u.id AND s.created_at >= ?)
          AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.created_at >= ?)
          AND NOT EXISTS (SELECT 1 FROM market_listings ml WHERE ml.seller_user_id = u.id AND ml.created_at >= ?)
          AND NOT EXISTS (
            SELECT 1 FROM market_trades mt
            WHERE (mt.seller_user_id = u.id OR mt.buyer_user_id = u.id) AND mt.created_at >= ?
          )
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, cutoff7d);
      statement.setTimestamp(2, cutoff7d);
      statement.setTimestamp(3, cutoff7d);
      statement.setTimestamp(4, cutoff7d);
      statement.setTimestamp(5, cutoff7d);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
      }
    }
  }

  private void addUserIdsSince(Connection connection, Set<Long> target, String sql, Timestamp cutoff)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, cutoff);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          long id = resultSet.getLong(1);
          if (id > 0) {
            target.add(id);
          }
        }
      }
    }
  }

  private int readCountSimple(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next() ? Math.max(0, resultSet.getInt(1)) : 0;
    }
  }

  private String readMeta(Connection connection, String key, String fallback) throws SQLException {
    String sql = "SELECT meta_value FROM webshop_meta WHERE meta_key = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return fallback;
        }
        String value = resultSet.getString("meta_value");
        return value == null || value.isBlank() ? fallback : value.trim();
      }
    }
  }

  private void upsertMeta(String key, String value) {
    databaseManager.inTransaction(connection -> {
      String sql = databaseManager.sqlProvider().upsertWebshopMetaSql();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, key);
        statement.setString(2, value);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private AdminDistribution readAdminDistribution(Connection connection) throws SQLException {
    String sql = """
        SELECT active, role, is_super_admin, permissions_json, template_key
        FROM web_admins
        """;
    Map<String, Integer> roleDistribution = new LinkedHashMap<>();
    Map<String, Integer> permissionDistribution = new LinkedHashMap<>();
    int activeCount = 0;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        boolean active = resultSet.getBoolean("active");
        if (!active) {
          continue;
        }
        activeCount++;
        boolean superAdmin = resultSet.getBoolean("is_super_admin");
        String roleRaw = resultSet.getString("role");
        String templateKey = resultSet.getString("template_key");
        String roleBucket = resolveAdminRoleBucket(superAdmin, roleRaw, templateKey);
        roleDistribution.merge(roleBucket, 1, Integer::sum);

        Set<AdminPermission> permissions = resolvePermissions(superAdmin, roleRaw, resultSet.getString("permissions_json"));
        for (AdminPermission permission : permissions) {
          permissionDistribution.merge(permission.name(), 1, Integer::sum);
        }
      }
    }
    return new AdminDistribution(activeCount, roleDistribution, permissionDistribution);
  }

  private Set<AdminPermission> resolvePermissions(boolean superAdmin, String roleRaw, String permissionsJson) {
    if (superAdmin) {
      return EnumSet.allOf(AdminPermission.class);
    }
    if (permissionsJson == null || permissionsJson.isBlank()) {
      return AdminRole.fromRaw(roleRaw).permissions();
    }
    EnumSet<AdminPermission> permissions = EnumSet.noneOf(AdminPermission.class);
    try {
      JsonArray array = JsonParser.parseString(permissionsJson).getAsJsonArray();
      for (JsonElement element : array) {
        if (element == null || element.isJsonNull()) {
          continue;
        }
        try {
          permissions.add(AdminPermission.valueOf(element.getAsString().trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
          // Ignore unknown permission values from legacy rows.
        }
      }
    } catch (Exception exception) {
      return AdminRole.fromRaw(roleRaw).permissions();
    }
    return permissions.isEmpty() ? AdminRole.fromRaw(roleRaw).permissions() : permissions;
  }

  private String resolveAdminRoleBucket(boolean superAdmin, String roleRaw, String templateKey) {
    if (superAdmin) {
      return "SUPER_ADMIN";
    }
    if (templateKey != null && !templateKey.isBlank()) {
      return templateKey.trim().toUpperCase(Locale.ROOT);
    }
    if (roleRaw != null && !roleRaw.isBlank()) {
      return roleRaw.trim().toUpperCase(Locale.ROOT);
    }
    return "CUSTOM";
  }

  private String resolveVaultProviderType(WalletService.GameCoinIntegrationStatus status) {
    if (status == null) {
      return "unknown";
    }
    if (!status.hooked()) {
      if (status.vaultPluginPresent()) {
        return "vault_present_not_hooked";
      }
      return "internal_wallet";
    }
    String provider = status.provider();
    if (provider == null || provider.isBlank()) {
      return "vault:unknown";
    }
    return normalizeBucketKey("vault:" + provider.trim().toLowerCase(Locale.ROOT));
  }

  private String resolveApiDomain(String requestPath) {
    if (requestPath == null || requestPath.isBlank()) {
      return "unknown";
    }
    String normalized = requestPath.trim().toLowerCase(Locale.ROOT);
    if (!normalized.startsWith("/api/")) {
      return "non_api";
    }
    if (normalized.startsWith("/api/admin/")) {
      return "admin";
    }
    if (normalized.startsWith("/api/market/")) {
      return "market";
    }
    if (normalized.startsWith("/api/orders")) {
      return "orders";
    }
    if (normalized.startsWith("/api/wallet")) {
      return "wallet";
    }
    if (normalized.startsWith("/api/auth")) {
      return "auth";
    }
    if (normalized.startsWith("/api/meta")) {
      return "meta";
    }
    if (normalized.startsWith("/api/leaderboard")) {
      return "leaderboard";
    }
    if (normalized.startsWith("/api/notifications")) {
      return "notifications";
    }
    if (normalized.startsWith("/api/redeem")) {
      return "redeem";
    }
    return "other_api";
  }

  private String resolveStatusBucket(int statusCode) {
    if (statusCode < 100 || statusCode > 599) {
      return "unknown";
    }
    return (statusCode / 100) + "xx";
  }

  private String resolveLatencyBucket(long durationMillis) {
    long ms = Math.max(0L, durationMillis);
    if (ms < 50L) {
      return "lt50ms";
    }
    if (ms < 200L) {
      return "50to199ms";
    }
    if (ms < 500L) {
      return "200to499ms";
    }
    if (ms < 1000L) {
      return "500to999ms";
    }
    return "ge1000ms";
  }

  private String resolveApiErrorBucket(int statusCode, String errorCode) {
    if (statusCode < 400) {
      return "ok";
    }
    if (statusCode >= 500) {
      return "5xx_internal";
    }
    String normalized = normalizeBucketKey(errorCode);
    if (normalized.startsWith("auth_")) {
      return "4xx_auth";
    }
    if (normalized.startsWith("invalid_")
        || normalized.startsWith("bad_")
        || normalized.contains("missing")
        || normalized.contains("malformed")) {
      return "4xx_params";
    }
    if (normalized.contains("idempotency")) {
      return "4xx_idempotency";
    }
    if (normalized.startsWith("forbidden")
        || normalized.startsWith("not_found")
        || normalized.startsWith("feature_disabled")
        || normalized.startsWith("insufficient")
        || normalized.startsWith("cooldown")
        || normalized.startsWith("conflict")) {
      return "4xx_business";
    }
    return "4xx_other";
  }

  private LatencyPercentile resolveLatencyPercentile(Map<String, Integer> latencyBuckets) {
    if (latencyBuckets == null || latencyBuckets.isEmpty()) {
      return new LatencyPercentile("unknown", "unknown", 0, 0);
    }
    int total = 0;
    for (Integer value : latencyBuckets.values()) {
      if (value != null && value > 0) {
        total += value;
      }
    }
    if (total <= 0) {
      return new LatencyPercentile("unknown", "unknown", 0, 0);
    }
    int targetP50 = (int) Math.ceil(total * 0.50D);
    int targetP95 = (int) Math.ceil(total * 0.95D);
    int cumulative = 0;
    String p50 = "unknown";
    String p95 = "unknown";
    int p50Ms = 0;
    int p95Ms = 0;
    for (String bucket : latencyBucketOrder()) {
      int count = latencyBuckets.getOrDefault(bucket, 0);
      if (count <= 0) {
        continue;
      }
      cumulative += count;
      if ("unknown".equals(p50) && cumulative >= targetP50) {
        p50 = bucket;
        p50Ms = latencyBucketEstimateMs(bucket);
      }
      if ("unknown".equals(p95) && cumulative >= targetP95) {
        p95 = bucket;
        p95Ms = latencyBucketEstimateMs(bucket);
      }
    }
    return new LatencyPercentile(p50, p95, p50Ms, p95Ms);
  }

  private String[] latencyBucketOrder() {
    return new String[] {"lt50ms", "50to199ms", "200to499ms", "500to999ms", "ge1000ms"};
  }

  private int latencyBucketEstimateMs(String bucket) {
    return switch (bucket) {
      case "lt50ms" -> 25;
      case "50to199ms" -> 125;
      case "200to499ms" -> 350;
      case "500to999ms" -> 750;
      case "ge1000ms" -> 1000;
      default -> 0;
    };
  }

  private String balanceBucket(long balance) {
    long normalized = Math.max(0L, balance);
    if (normalized == 0L) {
      return "0";
    }
    if (normalized < 1_000L) {
      return "1-999";
    }
    if (normalized < 10_000L) {
      return "1k-9k";
    }
    if (normalized < 100_000L) {
      return "10k-99k";
    }
    if (normalized < 1_000_000L) {
      return "100k-999k";
    }
    return "1m+";
  }

  private String normalizeBucketKey(String raw) {
    String normalized = String.valueOf(raw == null ? "" : raw)
        .trim()
        .toLowerCase(Locale.ROOT)
        .replace(' ', '_');
    if (normalized.isBlank()) {
      return "unknown";
    }
    if (normalized.length() <= MAX_BUCKET_LENGTH) {
      return normalized;
    }
    return normalized.substring(0, MAX_BUCKET_LENGTH);
  }

  private String normalizeLocale(String raw) {
    String normalized = String.valueOf(raw == null ? "" : raw).trim().replace('_', '-');
    if (normalized.isBlank()) {
      return "unknown";
    }
    if (normalized.equalsIgnoreCase("zh") || normalized.regionMatches(true, 0, "zh-", 0, 3)) {
      return "zh-CN";
    }
    if (normalized.equalsIgnoreCase("en") || normalized.regionMatches(true, 0, "en-", 0, 3)) {
      return "en-US";
    }
    String[] segments = normalized.split("-");
    if (segments.length == 0 || segments[0].isBlank()) {
      return "unknown";
    }
    String language = segments[0].toLowerCase(Locale.ROOT);
    if (segments.length == 1) {
      return language;
    }
    String region = segments[1].length() == 2
        ? segments[1].toUpperCase(Locale.ROOT)
        : segments[1].toLowerCase(Locale.ROOT);
    if (segments.length == 2) {
      return language + "-" + region;
    }
    StringBuilder builder = new StringBuilder(language).append('-').append(region);
    for (int i = 2; i < segments.length; i++) {
      String part = segments[i].trim();
      if (!part.isEmpty()) {
        builder.append('-').append(part.toLowerCase(Locale.ROOT));
      }
    }
    return builder.toString();
  }

  private Map<String, Integer> withNoDataFallback(Map<String, Integer> source, String fallbackKey) {
    if (source != null && !source.isEmpty()) {
      return source;
    }
    Map<String, Integer> fallback = new LinkedHashMap<>();
    fallback.put(fallbackKey, 1);
    return fallback;
  }

  private Map<String, Integer> filterErrorOnly(Map<String, Integer> raw) {
    Map<String, Integer> filtered = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return filtered;
    }
    for (Map.Entry<String, Integer> entry : raw.entrySet()) {
      String key = entry.getKey();
      Integer value = entry.getValue();
      if (key == null || key.isBlank() || value == null || value <= 0) {
        continue;
      }
      if ("ok".equalsIgnoreCase(key)) {
        continue;
      }
      filtered.put(key, value);
    }
    return filtered;
  }

  private String nodeScaleBucket(int observedOnlineNodes, PluginSettings.ClusterRole role) {
    int effective = observedOnlineNodes;
    if (role == PluginSettings.ClusterRole.STANDALONE) {
      effective = Math.max(1, observedOnlineNodes);
    }
    if (effective <= 1) {
      return "1";
    }
    if (effective <= 3) {
      return "2-3";
    }
    return "4+";
  }

  private int safeToInt(long value) {
    if (value <= 0L) {
      return 0;
    }
    if (value >= Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) value;
  }

  record Snapshot(
      String databaseType,
      String clusterEnabled,
      String clusterNodeScaleBucket,
      String webManagementEnabled,
      String embeddedWebServerEnabled,
      int officialProductCount,
      Map<String, Integer> officialProductTypeCounts,
      int playerMarketSellCount,
      int playerMarketRecycleCount,
      int playerMarketSellerCount,
      int auctionListingCount,
      int tradeVolume30d,
      Map<String, Integer> tradeVolumeBreakdown30d,
      Map<String, Integer> orderFunnel,
      Map<String, Integer> marketLiquidity,
      Map<String, Integer> economyFlow30d,
      String economyNetDirection30d,
      int economyNetDeltaAbs30d,
      Map<String, Integer> retryDistribution,
      Map<String, Integer> backlogDistribution,
      int idempotencyHitRate7d,
      Map<String, Integer> idempotencyDetails7d,
      Map<String, Integer> shopCoinDistribution,
      Map<String, Integer> gameCoinDistribution,
      Map<String, Integer> totalEconomyDistribution,
      int totalShopCoin,
      int totalGameCoin,
      String vaultHooked,
      String vaultProviderType,
      Map<String, Integer> localeUsage7d,
      Map<String, Integer> retentionSignals,
      Map<String, Integer> startupSignals7d,
      int apiRequestTotal7d,
      Map<String, Integer> apiUsage7d,
      Map<String, Integer> apiLatencyBuckets7d,
      String apiLatencyP50Bucket7d,
      String apiLatencyP95Bucket7d,
      int apiLatencyP50EstimateMs7d,
      int apiLatencyP95EstimateMs7d,
      Map<String, Integer> apiErrorDistribution7d,
      String startupLastResult,
      String startupLastFailure,
      int activeAdminCount,
      Map<String, Integer> adminRoleDistribution,
      Map<String, Integer> adminPermissionDistribution) {
  }

  private record MarketCounts(int sellCount, int buyCount) {
  }

  private record TradeVolume(int totalCount, Map<String, Integer> breakdown) {
  }

  private record OrderFunnel(
      int placed,
      int pending,
      int delivered,
      int claimed,
      int refunded,
      int waitClaim) {
    Map<String, Integer> toMap() {
      Map<String, Integer> values = new LinkedHashMap<>();
      values.put("placed_total", Math.max(0, placed));
      values.put("pending_delivery", Math.max(0, pending));
      values.put("wait_claim", Math.max(0, waitClaim));
      values.put("delivered", Math.max(0, delivered));
      values.put("claimed_items", Math.max(0, claimed));
      values.put("refunded", Math.max(0, refunded));
      return values;
    }
  }

  private record MarketLiquidity(
      int created24h,
      int soldWithin24h,
      int sellThroughRatePct,
      int avgTimeToSaleMinutes) {
    Map<String, Integer> toMap() {
      Map<String, Integer> values = new LinkedHashMap<>();
      values.put("created_24h", Math.max(0, created24h));
      values.put("sold_within_24h", Math.max(0, soldWithin24h));
      values.put("sell_through_rate_pct", Math.max(0, sellThroughRatePct));
      values.put("avg_time_to_sale_min", Math.max(0, avgTimeToSaleMinutes));
      return values;
    }
  }

  private record EconomyHealth(
      int shopMinted30d,
      int shopBurned30d,
      int gameMinted30d,
      int gameBurned30d,
      String netDirection,
      int netDeltaAbs) {
    Map<String, Integer> flowMap() {
      Map<String, Integer> values = new LinkedHashMap<>();
      values.put("shop_minted_30d", Math.max(0, shopMinted30d));
      values.put("shop_burned_30d", Math.max(0, shopBurned30d));
      values.put("game_minted_30d", Math.max(0, gameMinted30d));
      values.put("game_burned_30d", Math.max(0, gameBurned30d));
      return values;
    }
  }

  private record RetryBacklog(Map<String, Integer> retryBucketMap, Map<String, Integer> backlogMap) {
  }

  private static final class RetryAccumulator {
    private int retry0;
    private int retry1to2;
    private int retry3to5;
    private int retry6plus;
    private int backlogPending;
    private int backlogWaitClaim;

    void record(int retryCount, String status) {
      if ("WAIT_CLAIM".equalsIgnoreCase(status)) {
        backlogWaitClaim++;
      } else {
        backlogPending++;
      }
      if (retryCount <= 0) {
        retry0++;
      } else if (retryCount <= 2) {
        retry1to2++;
      } else if (retryCount <= 5) {
        retry3to5++;
      } else {
        retry6plus++;
      }
    }

    RetryBacklog toRetryBacklog() {
      Map<String, Integer> retry = new LinkedHashMap<>();
      retry.put("retry_0", retry0);
      retry.put("retry_1_2", retry1to2);
      retry.put("retry_3_5", retry3to5);
      retry.put("retry_6_plus", retry6plus);
      Map<String, Integer> backlog = new LinkedHashMap<>();
      backlog.put("pending", backlogPending);
      backlog.put("wait_claim", backlogWaitClaim);
      backlog.put("total", backlogPending + backlogWaitClaim);
      return new RetryBacklog(retry, backlog);
    }
  }

  private record IdempotencyStats(int hit, int miss, int hitRatePercent) {
    Map<String, Integer> toMap() {
      Map<String, Integer> values = new LinkedHashMap<>();
      values.put("hit", Math.max(0, hit));
      values.put("miss", Math.max(0, miss));
      values.put("total", Math.max(0, hit + miss));
      values.put("hit_rate_pct", Math.max(0, hitRatePercent));
      return values;
    }
  }

  private record EconomyDistribution(
      Map<String, Integer> shopCoinDistribution,
      Map<String, Integer> gameCoinDistribution,
      Map<String, Integer> totalEconomyDistribution,
      int totalShopCoin,
      int totalGameCoin) {
  }

  private record LatencyPercentile(
      String p50Bucket,
      String p95Bucket,
      int p50EstimateMs,
      int p95EstimateMs) {
  }

  private record AdminDistribution(
      int activeAdminCount,
      Map<String, Integer> roleDistribution,
      Map<String, Integer> permissionDistribution) {
  }

  private record RetentionSignals(
      int totalUsers,
      int newUsers7d,
      int activeUsers7d,
      int longTermActiveUsers30d,
      int silentDropoffUsers7d) {
    Map<String, Integer> toMap() {
      Map<String, Integer> values = new LinkedHashMap<>();
      values.put("total_users", Math.max(0, totalUsers));
      values.put("new_users_7d", Math.max(0, newUsers7d));
      values.put("active_users_7d", Math.max(0, activeUsers7d));
      values.put("long_term_active_users_30d", Math.max(0, longTermActiveUsers30d));
      values.put("silent_dropoff_users_7d", Math.max(0, silentDropoffUsers7d));
      return values;
    }
  }

  private record LocalDateTimeWindow(Timestamp cutoff, Timestamp nowTimestamp) {
    static LocalDateTimeWindow ofNowUtcDays(long days) {
      Instant now = Instant.now();
      return new LocalDateTimeWindow(
          Timestamp.from(now.minus(Math.max(0L, days), ChronoUnit.DAYS)),
          Timestamp.from(now));
    }

    static LocalDateTimeWindow ofNowUtcSeconds(long seconds) {
      Instant now = Instant.now();
      return new LocalDateTimeWindow(
          Timestamp.from(now.minus(Math.max(0L, seconds), ChronoUnit.SECONDS)),
          Timestamp.from(now));
    }
  }
}
