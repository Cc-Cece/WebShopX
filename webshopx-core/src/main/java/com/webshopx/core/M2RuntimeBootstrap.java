package com.webshopx.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shared M2 runtime bootstrap for Fabric/NeoForge with minimal business API loop.
 */
public final class M2RuntimeBootstrap {

  private static final Gson GSON = new Gson();
  private static final Object SQLITE_DRIVER_LOCK = new Object();
  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final int DEFAULT_PORT = 18081;
  private static final String DEFAULT_SQLITE_PATH = "data/webshopx-m2.sqlite";
  private static final String STATIC_WEB_ROOT = "web/";
  private static final double DEFAULT_SHOP_TO_GAME_RATIO = 1.0d;
  private static final double DEFAULT_GAME_TO_SHOP_RATIO = 1.0d;
  private static final List<String> DEFAULT_MATERIAL_ALLOW_LIST = List.of(
      "DIAMOND",
      "DIAMOND_SWORD",
      "IRON_INGOT",
      "GOLD_INGOT",
      "EMERALD",
      "NETHERITE_INGOT",
      "COBBLESTONE",
      "OAK_LOG",
      "OAK_PLANKS",
      "STONE",
      "DIRT",
      "SAND",
      "GRAVEL",
      "REDSTONE",
      "LAPIS_LAZULI",
      "BOW",
      "ARROW",
      "BREAD",
      "COOKED_BEEF",
      "POTION");
  private static volatile boolean SQLITE_DRIVER_READY = false;

  private M2RuntimeBootstrap() {
  }

  public static RuntimeHandle start(String runtimeId, String version, Path workingDirectory)
      throws IOException, SQLException {
    return start(runtimeId, version, workingDirectory, ignored -> {
    });
  }

  public static RuntimeHandle start(String runtimeId, String version, Path workingDirectory, Consumer<String> logger)
      throws IOException, SQLException {
    Objects.requireNonNull(runtimeId, "runtimeId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    Objects.requireNonNull(logger, "logger");

    Path runtimeRoot = workingDirectory.resolve(runtimeId);
    Files.createDirectories(runtimeRoot);

    RuntimeConfig config = RuntimeConfig.load(runtimeRoot.resolve("m2-runtime.properties"));
    Path sqlitePath = runtimeRoot.resolve(config.sqlitePath()).normalize();
    initializeSchema(sqlitePath);
    writeRuntimeProbe(sqlitePath, runtimeId, version);

    RuntimeState state = new RuntimeState(runtimeId, version, sqlitePath, config.adminToken());
    RuntimeServer server = RuntimeServer.start(config.host(), config.port(), state);

    logger.accept("[WebShopX/M2] runtime=" + runtimeId
        + " version=" + version
        + " endpoint=" + server.endpoint()
        + " sqlite=" + sqlitePath.toAbsolutePath());

    return new RuntimeHandle(runtimeId, version, runtimeRoot, sqlitePath, server);
  }

  private static void initializeSchema(Path sqlitePath) throws IOException, SQLException {
    Path parent = sqlitePath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    ensureSqliteDriver();
    String jdbcUrl = "jdbc:sqlite:" + sqlitePath.toAbsolutePath();
    try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys=ON");
        statement.execute("""
            CREATE TABLE IF NOT EXISTS m2_runtime_probe (
              runtime_id TEXT PRIMARY KEY,
              version TEXT NOT NULL,
              boot_at TEXT NOT NULL
            )
            """);
        statement.execute("""
            CREATE TABLE IF NOT EXISTS m2_users (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              username TEXT NOT NULL UNIQUE,
              created_at TEXT NOT NULL
            )
            """);
        statement.execute("""
            CREATE TABLE IF NOT EXISTS m2_wallets (
              user_id INTEGER PRIMARY KEY,
              shop_coin INTEGER NOT NULL DEFAULT 0,
              game_coin INTEGER NOT NULL DEFAULT 0,
              updated_at TEXT NOT NULL,
              FOREIGN KEY (user_id) REFERENCES m2_users(id) ON DELETE CASCADE
            )
            """);
        statement.execute("""
            CREATE TABLE IF NOT EXISTS m2_wallet_ledger (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              user_id INTEGER NOT NULL,
              currency TEXT NOT NULL,
              delta INTEGER NOT NULL,
              biz_type TEXT NOT NULL,
              biz_id TEXT NOT NULL,
              created_at TEXT NOT NULL,
              UNIQUE(user_id, currency, biz_type, biz_id),
              FOREIGN KEY (user_id) REFERENCES m2_users(id) ON DELETE CASCADE
            )
            """);
        statement.execute("""
            CREATE TABLE IF NOT EXISTS m2_products (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              sku TEXT NOT NULL UNIQUE,
              title TEXT NOT NULL,
              currency TEXT NOT NULL,
              price INTEGER NOT NULL,
              active INTEGER NOT NULL DEFAULT 1,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """);
        statement.execute("""
            CREATE TABLE IF NOT EXISTS m2_orders (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              order_no TEXT NOT NULL UNIQUE,
              user_id INTEGER NOT NULL,
              product_id INTEGER NOT NULL,
              quantity INTEGER NOT NULL,
              total_amount INTEGER NOT NULL,
              currency TEXT NOT NULL,
              status TEXT NOT NULL,
              idempotency_key TEXT NOT NULL UNIQUE,
              created_at TEXT NOT NULL,
              FOREIGN KEY (user_id) REFERENCES m2_users(id),
              FOREIGN KEY (product_id) REFERENCES m2_products(id)
            )
            """);
      }
    }
  }

  private static void writeRuntimeProbe(Path sqlitePath, String runtimeId, String version) throws SQLException {
    ensureSqliteDriver();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath())) {
      try (PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO m2_runtime_probe (runtime_id, version, boot_at)
          VALUES (?, ?, ?)
          ON CONFLICT(runtime_id) DO UPDATE SET
            version = excluded.version,
            boot_at = excluded.boot_at
          """)) {
        statement.setString(1, runtimeId);
        statement.setString(2, version);
        statement.setString(3, Instant.now().toString());
        statement.executeUpdate();
      }
    }
  }

  private static void ensureSqliteDriver() throws SQLException {
    if (SQLITE_DRIVER_READY) {
      return;
    }
    synchronized (SQLITE_DRIVER_LOCK) {
      if (SQLITE_DRIVER_READY) {
        return;
      }
      try {
        Class.forName("org.sqlite.JDBC");
        SQLITE_DRIVER_READY = true;
      } catch (ClassNotFoundException exception) {
        throw new SQLException("SQLite JDBC driver is not available on runtime classpath", exception);
      }
    }
  }

  public record RuntimeHandle(
      String runtimeId,
      String version,
      Path runtimeRoot,
      Path sqlitePath,
      RuntimeServer server) implements AutoCloseable {

    public String endpoint() {
      return server.endpoint();
    }

    @Override
    public void close() {
      server.close();
    }
  }

  private record RuntimeConfig(String host, int port, String sqlitePath, String adminToken) {

    private static RuntimeConfig load(Path configPath) throws IOException {
      Properties properties = new Properties();
      if (Files.exists(configPath)) {
        try (var input = Files.newInputStream(configPath)) {
          properties.load(input);
        }
      } else {
        properties.setProperty("http.host", DEFAULT_HOST);
        properties.setProperty("http.port", Integer.toString(DEFAULT_PORT));
        properties.setProperty("sqlite.path", DEFAULT_SQLITE_PATH);
        properties.setProperty("admin.token", "");
        Path parent = configPath.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        try (var output = Files.newOutputStream(configPath)) {
          properties.store(output, "WebShopX M2 runtime configuration");
        }
      }
      String host = read(properties, "http.host", DEFAULT_HOST);
      int port = parsePort(read(properties, "http.port", Integer.toString(DEFAULT_PORT)));
      String sqlitePath = read(properties, "sqlite.path", DEFAULT_SQLITE_PATH);
      String adminToken = read(properties, "admin.token", "");
      return new RuntimeConfig(host, port, sqlitePath, adminToken);
    }

    private static int parsePort(String raw) {
      try {
        int value = Integer.parseInt(raw.trim());
        return value >= 0 && value <= 65535 ? value : DEFAULT_PORT;
      } catch (Exception ignored) {
        return DEFAULT_PORT;
      }
    }

    private static String read(Properties properties, String key, String fallback) {
      String value = properties.getProperty(key, fallback);
      if (value == null) {
        return fallback;
      }
      String trimmed = value.trim();
      return trimmed.isEmpty() ? fallback : trimmed;
    }
  }

  private record RuntimeState(String runtimeId, String version, Path sqlitePath, String adminToken) {

    private Connection connection() throws SQLException {
      ensureSqliteDriver();
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath());
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys=ON");
      }
      return connection;
    }

    private boolean adminAuthRequired() {
      return adminToken != null && !adminToken.isBlank();
    }
  }

  private static final class RuntimeServer implements AutoCloseable {

    private final HttpServer server;
    private final String endpoint;

    private RuntimeServer(HttpServer server, String endpoint) {
      this.server = server;
      this.endpoint = endpoint;
    }

    private static RuntimeServer start(String host, int port, RuntimeState state) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
      ApiHandler handler = new ApiHandler(state);
      server.createContext("/", handler);
      server.createContext("/health", handler);
      server.createContext("/api/m2", handler);
      server.start();
      String endpoint = "http://" + host + ":" + server.getAddress().getPort();
      return new RuntimeServer(server, endpoint);
    }

    private String endpoint() {
      return endpoint;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class ApiHandler implements HttpHandler {

    private final RuntimeState state;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, AdminSession> adminSessions = new ConcurrentHashMap<>();
    private final Map<Long, UserVisualPermission> userVisualPermissions = new ConcurrentHashMap<>();
    private final Map<Long, String> userAuthStates = new ConcurrentHashMap<>();
    private final Map<Long, String> userBoundUuidOverrides = new ConcurrentHashMap<>();
    private final Map<Long, AdminManagerRecord> adminManagers = new ConcurrentHashMap<>();
    private final List<AuditLogRecord> auditLogs = new CopyOnWriteArrayList<>();
    private final Map<Long, String> marketListingStatusOverrides = new ConcurrentHashMap<>();
    private final Map<Long, String> marketListingUnlistedAt = new ConcurrentHashMap<>();
    private final Map<Long, ProductAdminMeta> productAdminMeta = new ConcurrentHashMap<>();
    private final Map<String, MaterialOverrideRecord> materialOverrides = new ConcurrentHashMap<>();
    private final List<LocaleCenterRecord> localeCenterRecords = new CopyOnWriteArrayList<>();
    private final List<ThemeCenterRecord> themeCenterRecords = new CopyOnWriteArrayList<>();
    private final Map<String, JsonObject> rechargeOrders = new ConcurrentHashMap<>();
    private final Map<String, Long> redeemUsageCounters = new ConcurrentHashMap<>();
    private final Map<Long, JsonObject> compatMarketListings = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> marketTradesByIdempotency = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> marketBidsByIdempotency = new ConcurrentHashMap<>();
    private final Map<String, byte[]> uploadedAssets = new ConcurrentHashMap<>();
    private final Map<String, String> uploadedAssetContentTypes = new ConcurrentHashMap<>();
    private volatile String localeCenterDefaultLocale = "zh-CN";
    private volatile String themeCenterDefaultTheme = "default";
    private volatile String localeCenterLastSyncAt = Instant.now().toString();
    private volatile String themeCenterLastSyncAt = Instant.now().toString();
    private volatile JsonObject marketTagsConfig = createDefaultMarketTagsConfig();
    private volatile JsonObject marketLimitationConfig = createDefaultMarketLimitationConfig();
    private volatile JsonObject economyRuntimeSettings = createDefaultEconomyRuntimeSettings();
    private volatile long auditSequence = 0L;
    private volatile long redeemSequence = 0L;
    private volatile long rechargeSequence = 0L;
    private volatile long marketListingSequence = 0L;
    private volatile long marketTradeSequence = 0L;
    private volatile long marketBidSequence = 0L;
    private final List<RedeemCodeRecord> redeemCodes = new CopyOnWriteArrayList<>();

    private ApiHandler(RuntimeState state) {
      this.state = state;
      initializeLocaleThemeDefaults();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        route(exchange);
      } catch (ServiceException exception) {
        writeError(exchange, exception.status(), exception.code(), exception.getMessage());
      } catch (Exception exception) {
        writeError(exchange, 500, "internal_error", exception.getMessage() == null ? "internal error" : exception.getMessage());
      }
    }

    private void route(HttpExchange exchange) throws Exception {
      String method = exchange.getRequestMethod();
      URI uri = exchange.getRequestURI();
      String path = uri.getPath();

      if ("/config.js".equals(path) && "GET".equals(method)) {
        handleRuntimeConfigJs(exchange);
        return;
      }
      if ("/health".equals(path) && "GET".equals(method)) {
        handleHealth(exchange);
        return;
      }
      if ("GET".equals(method) && tryHandleStaticWeb(exchange, path)) {
        return;
      }
      if ("/api/admin/auth/login".equals(path) && "POST".equals(method)) {
        handleAdminLogin(exchange);
        return;
      }
      if ("/api/admin/auth/me".equals(path) && "GET".equals(method)) {
        handleAdminAuthMe(exchange);
        return;
      }
      if ("/api/admin/auth/logout".equals(path) && "POST".equals(method)) {
        handleAdminLogout(exchange);
        return;
      }
      if ("/api/admin/products/list".equals(path) && "GET".equals(method)) {
        handleAdminProductsList(exchange);
        return;
      }
      if ("/api/admin/orders/list".equals(path) && "GET".equals(method)) {
        handleAdminOrdersList(exchange);
        return;
      }
      if ("/api/admin/economy/settings".equals(path) && "GET".equals(method)) {
        handleAdminEconomySettings(exchange);
        return;
      }
      if ("/api/admin/locales".equals(path) && "GET".equals(method)) {
        handleAdminLocales(exchange);
        return;
      }
      if ("/api/admin/themes".equals(path) && "GET".equals(method)) {
        handleAdminThemes(exchange);
        return;
      }
      if ("/api/admin/l10n/manifest".equals(path) && "GET".equals(method)) {
        handleAdminL10nManifest(exchange);
        return;
      }
      if ("/api/admin/locales/upload".equals(path) && "POST".equals(method)) {
        handleAdminLocalesUpload(exchange);
        return;
      }
      if ("/api/admin/locales/sync-manifest".equals(path) && "POST".equals(method)) {
        handleAdminLocalesSyncManifest(exchange);
        return;
      }
      if ("/api/admin/locales/default".equals(path) && "POST".equals(method)) {
        handleAdminLocalesDefault(exchange);
        return;
      }
      if ("/api/admin/locales/action".equals(path) && "POST".equals(method)) {
        handleAdminLocalesAction(exchange);
        return;
      }
      if ("/api/admin/themes/upload".equals(path) && "POST".equals(method)) {
        handleAdminThemesUpload(exchange);
        return;
      }
      if ("/api/admin/themes/sync-manifest".equals(path) && "POST".equals(method)) {
        handleAdminThemesSyncManifest(exchange);
        return;
      }
      if ("/api/admin/themes/default".equals(path) && "POST".equals(method)) {
        handleAdminThemesDefault(exchange);
        return;
      }
      if ("/api/admin/themes/action".equals(path) && "POST".equals(method)) {
        handleAdminThemesAction(exchange);
        return;
      }
      if ("/api/admin/products/upsert".equals(path) && "POST".equals(method)) {
        handleAdminProductsUpsert(exchange);
        return;
      }
      if ("/api/admin/products/icon".equals(path) && "POST".equals(method)) {
        handleAdminProductsIcon(exchange);
        return;
      }
      if ("/api/admin/products/reset-limit".equals(path) && "POST".equals(method)) {
        handleAdminProductsResetLimit(exchange);
        return;
      }
      if ("/api/admin/products/active".equals(path) && "POST".equals(method)) {
        handleAdminProductsActive(exchange);
        return;
      }
      if ("/api/admin/group-buy/consume".equals(path) && "POST".equals(method)) {
        handleAdminGroupBuyConsume(exchange);
        return;
      }
      if ("/api/admin/redeem/list".equals(path) && "GET".equals(method)) {
        handleAdminRedeemList(exchange);
        return;
      }
      if ("/api/admin/redeem/create".equals(path) && "POST".equals(method)) {
        handleAdminRedeemCreate(exchange);
        return;
      }
      if ("/api/admin/market/tags-config".equals(path) && "GET".equals(method)) {
        handleAdminMarketTagsConfigGet(exchange);
        return;
      }
      if ("/api/admin/market/tags-config".equals(path) && "POST".equals(method)) {
        handleAdminMarketTagsConfigPost(exchange);
        return;
      }
      if ("/api/admin/market/limitation-config".equals(path) && "GET".equals(method)) {
        handleAdminMarketLimitationConfigGet(exchange);
        return;
      }
      if ("/api/admin/market/limitation-config".equals(path) && "POST".equals(method)) {
        handleAdminMarketLimitationConfigPost(exchange);
        return;
      }
      if ("/api/admin/market/listings".equals(path) && "GET".equals(method)) {
        handleAdminMarketListings(exchange);
        return;
      }
      if ("/api/admin/market/unlist".equals(path) && "POST".equals(method)) {
        handleAdminMarketUnlist(exchange);
        return;
      }
      if ("/api/admin/users/list".equals(path) && "GET".equals(method)) {
        handleAdminUsersList(exchange);
        return;
      }
      if ("/api/admin/users/lookup".equals(path) && "GET".equals(method)) {
        handleAdminUserLookup(exchange);
        return;
      }
      if ("/api/admin/users/visual-permission".equals(path) && "GET".equals(method)) {
        handleAdminUserVisualPermissionGet(exchange);
        return;
      }
      if ("/api/admin/users/visual-permission".equals(path) && "POST".equals(method)) {
        handleAdminUserVisualPermissionPost(exchange);
        return;
      }
      if ("/api/admin/users/logout".equals(path) && "POST".equals(method)) {
        handleAdminUserLogout(exchange);
        return;
      }
      if ("/api/admin/users/unbind".equals(path) && "POST".equals(method)) {
        handleAdminUserUnbind(exchange);
        return;
      }
      if ("/api/admin/users/reset-password".equals(path) && "POST".equals(method)) {
        handleAdminUserResetPassword(exchange);
        return;
      }
      if ("/api/admin/users/wallet-adjust".equals(path) && "POST".equals(method)) {
        handleAdminUserWalletAdjust(exchange);
        return;
      }
      if ("/api/admin/material-overrides/list".equals(path) && "GET".equals(method)) {
        handleAdminMaterialOverridesList(exchange);
        return;
      }
      if ("/api/admin/material-overrides/upsert".equals(path) && "POST".equals(method)) {
        handleAdminMaterialOverridesUpsert(exchange);
        return;
      }
      if ("/api/admin/material-overrides/icon".equals(path) && "POST".equals(method)) {
        handleAdminMaterialOverridesIcon(exchange);
        return;
      }
      if ("/api/admin/material-overrides/delete".equals(path) && "POST".equals(method)) {
        handleAdminMaterialOverridesDelete(exchange);
        return;
      }
      if ("/api/admin/economy/exchange".equals(path) && "POST".equals(method)) {
        handleAdminEconomyExchange(exchange);
        return;
      }
      if ("/api/admin/economy/market".equals(path) && "POST".equals(method)) {
        handleAdminEconomyMarket(exchange);
        return;
      }
      if ("/api/admin/economy/leaderboard".equals(path) && "POST".equals(method)) {
        handleAdminEconomyLeaderboard(exchange);
        return;
      }
      if ("/api/admin/economy/currency".equals(path) && "POST".equals(method)) {
        handleAdminEconomyCurrency(exchange);
        return;
      }
      if ("/api/admin/system/webshop".equals(path) && "POST".equals(method)) {
        handleAdminSystemWebshop(exchange);
        return;
      }
      if ("/api/admin/system/market".equals(path) && "POST".equals(method)) {
        handleAdminSystemMarket(exchange);
        return;
      }
      if ("/api/admin/system/maintenance".equals(path) && "POST".equals(method)) {
        handleAdminSystemMaintenance(exchange);
        return;
      }
      if ("/api/admin/system/logging".equals(path) && "POST".equals(method)) {
        handleAdminSystemLogging(exchange);
        return;
      }
      if ("/api/admin/system/notification".equals(path) && "POST".equals(method)) {
        handleAdminSystemNotification(exchange);
        return;
      }
      if ("/api/admin/notifications/announce".equals(path) && "POST".equals(method)) {
        handleAdminNotificationsAnnounce(exchange);
        return;
      }
      if ("/api/admin/system/broadcast".equals(path) && "POST".equals(method)) {
        handleAdminSystemBroadcast(exchange);
        return;
      }
      if ("/api/admin/visual/settings".equals(path) && "POST".equals(method)) {
        handleAdminVisualSettings(exchange);
        return;
      }
      if ("/api/admin/admin-users/meta".equals(path) && "GET".equals(method)) {
        handleAdminManagersMeta(exchange);
        return;
      }
      if ("/api/admin/admin-users/list".equals(path) && "GET".equals(method)) {
        handleAdminManagersList(exchange);
        return;
      }
      if ("/api/admin/admin-users/upsert".equals(path) && "POST".equals(method)) {
        handleAdminManagersUpsert(exchange);
        return;
      }
      if ("/api/admin/admin-users/active".equals(path) && "POST".equals(method)) {
        handleAdminManagersActive(exchange);
        return;
      }
      if ("/api/admin/audit/list".equals(path) && "GET".equals(method)) {
        handleAdminAuditList(exchange);
        return;
      }
      if ("/api/auth/login".equals(path) && "POST".equals(method)) {
        handleCompatLogin(exchange);
        return;
      }
      if ("/api/auth/me".equals(path) && "GET".equals(method)) {
        handleCompatAuthMe(exchange);
        return;
      }
      if ("/api/auth/logout".equals(path) && "POST".equals(method)) {
        handleCompatLogout(exchange);
        return;
      }
      if ("/api/wallet".equals(path) && "GET".equals(method)) {
        handleCompatWallet(exchange);
        return;
      }
      if ("/api/wallet/ledger".equals(path) && "GET".equals(method)) {
        handleCompatWalletLedger(exchange);
        return;
      }
      if ("/api/wallet/exchange".equals(path) && "POST".equals(method)) {
        handleCompatWalletExchange(exchange);
        return;
      }
      if ("/api/products".equals(path) && "GET".equals(method)) {
        handleCompatProducts(exchange);
        return;
      }
      if ("/api/orders".equals(path) && "POST".equals(method)) {
        handleCompatCreateOrder(exchange);
        return;
      }
      if ("/api/orders/list".equals(path) && "GET".equals(method)) {
        handleCompatOrdersList(exchange);
        return;
      }
      if ("/api/orders/refund".equals(path) && "POST".equals(method)) {
        handleCompatOrderRefund(exchange);
        return;
      }
      if ("/api/orders/policy".equals(path) && "GET".equals(method)) {
        handleCompatOrderPolicy(exchange);
        return;
      }
      if ("/api/notifications/unread-count".equals(path) && "GET".equals(method)) {
        handleCompatNotificationsUnread(exchange);
        return;
      }
      if ("/api/notifications/list".equals(path) && "GET".equals(method)) {
        handleCompatNotificationsList(exchange);
        return;
      }
      if ("/api/notifications/mark-read".equals(path) && "POST".equals(method)) {
        handleCompatNotificationsMarkRead(exchange);
        return;
      }
      if ("/api/meta/currency".equals(path) && "GET".equals(method)) {
        handleCompatCurrencyMeta(exchange);
        return;
      }
      if ("/api/meta/locales".equals(path) && "GET".equals(method)) {
        handleCompatLocalesMeta(exchange);
        return;
      }
      if ("/api/meta/themes".equals(path) && "GET".equals(method)) {
        handleCompatThemesMeta(exchange);
        return;
      }
      if ("/api/meta/materials".equals(path) && "GET".equals(method)) {
        handleCompatMaterialMeta(exchange);
        return;
      }
      if ("/api/meta/material-overrides".equals(path) && "GET".equals(method)) {
        handleCompatMaterialOverridesMeta(exchange);
        return;
      }
      if ("/api/meta/market-tags".equals(path) && "GET".equals(method)) {
        handleCompatMarketTagsMeta(exchange);
        return;
      }
      if ("/api/market/listings".equals(path) && "GET".equals(method)) {
        handleCompatMarketListings(exchange);
        return;
      }
      if ("/api/market/listings/create".equals(path) && "POST".equals(method)) {
        handleCompatMarketCreateListing(exchange);
        return;
      }
      if ("/api/market/buy".equals(path) && "POST".equals(method)) {
        handleCompatMarketBuy(exchange);
        return;
      }
      if ("/api/market/sell-to-buy".equals(path) && "POST".equals(method)) {
        handleCompatMarketSellToBuy(exchange);
        return;
      }
      if ("/api/market/bid".equals(path) && "POST".equals(method)) {
        handleCompatMarketBid(exchange);
        return;
      }
      if ("/api/market/unlist".equals(path) && "POST".equals(method)) {
        handleCompatMarketUnlist(exchange);
        return;
      }
      if ("/api/market/supply/refresh".equals(path) && "POST".equals(method)) {
        handleCompatMarketSupplyRefresh(exchange);
        return;
      }
      if ("/api/market/pause".equals(path) && "POST".equals(method)) {
        handleCompatMarketPause(exchange);
        return;
      }
      if ("/api/market/resume".equals(path) && "POST".equals(method)) {
        handleCompatMarketResume(exchange);
        return;
      }
      if ("/api/market/price".equals(path) && "POST".equals(method)) {
        handleCompatMarketPrice(exchange);
        return;
      }
      if ("/api/market/remark".equals(path) && "POST".equals(method)) {
        handleCompatMarketRemark(exchange);
        return;
      }
      if ("/api/market/settings".equals(path) && "POST".equals(method)) {
        handleCompatMarketSettings(exchange);
        return;
      }
      if ("/api/market/icon/upload".equals(path) && "POST".equals(method)) {
        handleCompatMarketIconUpload(exchange);
        return;
      }
      if ("/api/recharge/create".equals(path) && "POST".equals(method)) {
        handleCompatRechargeCreate(exchange);
        return;
      }
      if ("/api/recharge/status".equals(path) && "GET".equals(method)) {
        handleCompatRechargeStatus(exchange);
        return;
      }
      if ("/api/redeem/use".equals(path) && "POST".equals(method)) {
        handleCompatRedeemUse(exchange);
        return;
      }
      if ("/api/leaderboard/config".equals(path) && "GET".equals(method)) {
        handleCompatLeaderboardConfig(exchange);
        return;
      }
      if ("/api/leaderboard/list".equals(path) && "GET".equals(method)) {
        handleCompatLeaderboardList(exchange);
        return;
      }
      if ("/api/m2/runtime".equals(path) && "GET".equals(method)) {
        handleRuntime(exchange);
        return;
      }
      if ("/api/m2/users/register".equals(path) && "POST".equals(method)) {
        handleRegisterUser(exchange);
        return;
      }
      if (path.startsWith("/api/m2/users/") && "GET".equals(method)) {
        handleGetUser(exchange, path.substring("/api/m2/users/".length()));
        return;
      }
      if (path.startsWith("/api/m2/wallet/") && "GET".equals(method)) {
        handleGetWallet(exchange, path.substring("/api/m2/wallet/".length()));
        return;
      }
      if ("/api/m2/wallet/adjust".equals(path) && "POST".equals(method)) {
        handleAdjustWallet(exchange);
        return;
      }
      if ("/api/m2/products".equals(path) && "GET".equals(method)) {
        handleListProducts(exchange);
        return;
      }
      if ("/api/m2/products".equals(path) && "POST".equals(method)) {
        handleUpsertProduct(exchange);
        return;
      }
      if ("/api/m2/orders".equals(path) && "POST".equals(method)) {
        handlePlaceOrder(exchange);
        return;
      }
      if ("/api/m2/orders".equals(path) && "GET".equals(method)) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        handleListOrders(exchange, query.get("userId"));
        return;
      }
      if (path.startsWith("/api/")) {
        writeError(exchange, 501, "mod_endpoint_not_implemented",
            "route is not available in current Mod runtime: " + path);
        return;
      }
      writeError(exchange, 404, "not_found", "endpoint not found");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("runtime", state.runtimeId());
      response.addProperty("version", state.version());
      response.addProperty("sqlite", state.sqlitePath().toAbsolutePath().toString());
      writeJson(exchange, 200, response);
    }

    private void handleRuntime(HttpExchange exchange) throws IOException {
      JsonObject response = new JsonObject();
      response.addProperty("runtime", state.runtimeId());
      response.addProperty("version", state.version());
      response.addProperty("adminAuthRequired", state.adminAuthRequired());
      writeJson(exchange, 200, response);
    }

    private void handleRuntimeConfigJs(HttpExchange exchange) throws IOException {
      String script = """
          window.WEBSHOPX_CONFIG = Object.freeze({
            runtime: %s,
            version: %s,
            apiBaseUrl: "",
            defaultLocale: "zh-CN",
            docsManifest: "docs/index.json"
          });
          """.formatted(
          GSON.toJson(state.runtimeId()),
          GSON.toJson(state.version()));
      writeText(exchange, 200, "application/javascript; charset=utf-8", script);
    }

    private boolean tryHandleStaticWeb(HttpExchange exchange, String rawPath) throws IOException {
      String path = normalizePath(rawPath);
      String resourcePath = switch (path) {
        case "/", "/index", "/index.html" -> STATIC_WEB_ROOT + "index.html";
        case "/admin", "/admin.html" -> STATIC_WEB_ROOT + "admin.html";
        case "/help", "/help.html" -> STATIC_WEB_ROOT + "help.html";
        default -> null;
      };

      if (resourcePath != null) {
        writeClasspathResource(exchange, resourcePath);
        return true;
      }

      if (path.startsWith("/web/")) {
        writeClasspathResource(exchange, STATIC_WEB_ROOT + path.substring("/web/".length()));
        return true;
      }

      if (path.startsWith("/uploads/")) {
        writeUploadedAsset(exchange, path.substring(1));
        return true;
      }

      if (path.startsWith("/themes/")) {
        writeThemeResource(exchange, path);
        return true;
      }

      if (path.startsWith("/i18n/")) {
        String uploadedPath = path.substring(1);
        if (writeUploadedAssetIfPresent(exchange, uploadedPath)) {
          return true;
        }
      }

      if (path.startsWith("/css/")
          || path.startsWith("/js/")
          || path.startsWith("/vendor/")
          || path.startsWith("/docs/")
          || path.startsWith("/i18n/")
          || "/material_zh.json".equals(path)) {
        writeClasspathResource(exchange, STATIC_WEB_ROOT + path.substring(1));
        return true;
      }
      return false;
    }

    private void writeUploadedAsset(HttpExchange exchange, String resourcePath) throws IOException {
      String normalized = resourcePath == null ? "" : resourcePath.replace('\\', '/');
      if (normalized.isBlank() || normalized.contains("..")) {
        throw new ServiceException(400, "bad_path", "invalid uploaded resource path");
      }
      byte[] content = uploadedAssets.get(normalized);
      if (content == null || content.length == 0) {
        throw new ServiceException(404, "not_found", "uploaded resource not found");
      }
      String contentType = uploadedAssetContentTypes.getOrDefault(normalized, "application/octet-stream");
      writeBytes(exchange, 200, contentType, content);
    }

    private boolean writeUploadedAssetIfPresent(HttpExchange exchange, String resourcePath) throws IOException {
      String normalized = resourcePath == null ? "" : resourcePath.replace('\\', '/');
      if (normalized.isBlank() || normalized.contains("..")) {
        return false;
      }
      byte[] content = uploadedAssets.get(normalized);
      if (content == null || content.length == 0) {
        return false;
      }
      String contentType = uploadedAssetContentTypes.getOrDefault(normalized, "application/octet-stream");
      writeBytes(exchange, 200, contentType, content);
      return true;
    }

    private void writeThemeResource(HttpExchange exchange, String path) throws IOException {
      String normalizedPath = normalizePath(path);
      String[] segments = normalizedPath.split("/");
      if (segments.length < 4) {
        throw new ServiceException(404, "not_found", "theme resource not found");
      }
      String themeId = normalizeThemeId(segments[2]);
      String variant = segments[3].toLowerCase();
      if (!"light.css".equals(variant) && !"dark.css".equals(variant)) {
        throw new ServiceException(404, "not_found", "theme resource not found");
      }
      String uploadedPath = "themes/" + themeId + "/" + variant;
      if (writeUploadedAssetIfPresent(exchange, uploadedPath)) {
        return;
      }
      writeClasspathResource(exchange, STATIC_WEB_ROOT + "css/" + variant);
    }

    private String normalizePath(String rawPath) {
      if (rawPath == null || rawPath.isBlank()) {
        return "/";
      }
      String normalized = rawPath.trim().replace('\\', '/');
      while (normalized.contains("//")) {
        normalized = normalized.replace("//", "/");
      }
      if (!normalized.startsWith("/")) {
        normalized = "/" + normalized;
      }
      return normalized;
    }

    private void writeClasspathResource(HttpExchange exchange, String resourcePath) throws IOException {
      String normalized = resourcePath.replace('\\', '/');
      if (normalized.contains("..")) {
        throw new ServiceException(400, "bad_path", "invalid resource path");
      }
      try (InputStream inputStream = M2RuntimeBootstrap.class.getClassLoader().getResourceAsStream(normalized)) {
        if (inputStream == null) {
          throw new ServiceException(404, "not_found", "resource not found");
        }
        byte[] bytes = inputStream.readAllBytes();
        writeBytes(exchange, 200, contentType(normalized), bytes);
      }
    }

    private String contentType(String resourcePath) {
      String lower = resourcePath.toLowerCase();
      if (lower.endsWith(".html")) {
        return "text/html; charset=utf-8";
      }
      if (lower.endsWith(".css")) {
        return "text/css; charset=utf-8";
      }
      if (lower.endsWith(".js")) {
        return "application/javascript; charset=utf-8";
      }
      if (lower.endsWith(".json")) {
        return "application/json; charset=utf-8";
      }
      if (lower.endsWith(".md")) {
        return "text/markdown; charset=utf-8";
      }
      if (lower.endsWith(".svg")) {
        return "image/svg+xml";
      }
      if (lower.endsWith(".png")) {
        return "image/png";
      }
      if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
        return "image/jpeg";
      }
      if (lower.endsWith(".gif")) {
        return "image/gif";
      }
      if (lower.endsWith(".webp")) {
        return "image/webp";
      }
      return "application/octet-stream";
    }

    private void handleCompatLogin(HttpExchange exchange) throws Exception {
      JsonObject payload = requireJsonBody(exchange);
      String identifier = optionalString(payload, "identifier");
      if (identifier == null || identifier.isBlank()) {
        identifier = optionalString(payload, "username");
      }
      if (identifier == null || identifier.isBlank()) {
        throw new ServiceException(400, "invalid_identifier", "identifier is required");
      }
      readNonBlank(payload, "password", "invalid_password", "password is required");

      String username = identifier.trim();
      try (Connection connection = state.connection()) {
        long userId = findUserIdByUsername(connection, username);
        if (userId <= 0) {
          try (PreparedStatement insert = connection.prepareStatement("""
              INSERT INTO m2_users (username, created_at)
              VALUES (?, ?)
              """)) {
            insert.setString(1, username);
            insert.setString(2, Instant.now().toString());
            insert.executeUpdate();
          }
          userId = findUserIdByUsername(connection, username);
        }
        if (userId <= 0) {
          throw new ServiceException(500, "user_lookup_failed", "failed to initialize user");
        }
        ensureWallet(connection, userId);
        String sessionToken = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sessionToken, userId);
        userAuthStates.put(userId, "ACTIVE");

        JsonObject response = new JsonObject();
        response.addProperty("sessionToken", sessionToken);
        response.addProperty("username", username);
        response.addProperty("boundUuid", (String) null);
        response.add("visualPermission", buildVisualPermissionJson());
        response.add("user", buildCompatUserJson(userId, username));
        writeJson(exchange, 200, response);
      }
    }

    private void handleCompatAuthMe(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject response = buildCompatUserJson(authUser.userId(), authUser.username());
      writeJson(exchange, 200, response);
    }

    private void handleCompatLogout(HttpExchange exchange) throws IOException {
      String sessionToken = readBearerToken(exchange);
      if (sessionToken == null || sessionToken.isBlank()) {
        throw new ServiceException(401, "auth_required", "Missing session token");
      }
      Long removed = sessions.remove(sessionToken);
      if (removed == null) {
        throw new ServiceException(401, "auth_invalid", "Session token is invalid or expired");
      }
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      writeJson(exchange, 200, response);
    }

    private void handleCompatWallet(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      try (Connection connection = state.connection()) {
        ensureWallet(connection, authUser.userId());
        JsonObject walletJson = buildCompatWalletJson(connection, authUser.userId(), authUser.username());
        writeJson(exchange, 200, walletJson);
      }
    }

    private void handleCompatWalletLedger(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      int limit = parseLimit(query.get("limit"), 20, 1, 200);
      try (Connection connection = state.connection()) {
        JsonArray entries = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT currency, delta, biz_type, biz_id, created_at
            FROM m2_wallet_ledger
            WHERE user_id = ?
            ORDER BY id DESC
            LIMIT ?
            """)) {
          statement.setLong(1, authUser.userId());
          statement.setInt(2, limit);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              JsonObject entry = new JsonObject();
              entry.addProperty("currency", resultSet.getString("currency"));
              entry.addProperty("delta", resultSet.getLong("delta"));
              entry.addProperty("bizType", resultSet.getString("biz_type"));
              entry.addProperty("bizId", resultSet.getString("biz_id"));
              entry.addProperty("createdAt", resultSet.getString("created_at"));
              entries.add(entry);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("entries", entries);
        writeJson(exchange, 200, response);
      }
    }

    private void handleCompatWalletExchange(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String fromCurrency = normalizeCurrency(readNonBlank(payload, "fromCurrency", "invalid_currency", "fromCurrency is required"));
      String toCurrency = normalizeCurrency(readNonBlank(payload, "toCurrency", "invalid_currency", "toCurrency is required"));
      if (fromCurrency.equals(toCurrency)) {
        throw new ServiceException(400, "invalid_currency", "exchange direction cannot be the same");
      }
      long amount = readPositiveLong(payload, "amount", "invalid_amount", "amount must be positive");
      String idempotencyKey = optionalString(payload, "idempotencyKey");
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        idempotencyKey = UUID.randomUUID().toString().replace("-", "");
      }

      double ratio;
      boolean enabled;
      if ("SHOP_COIN".equals(fromCurrency) && "GAME_COIN".equals(toCurrency)) {
        ratio = DEFAULT_SHOP_TO_GAME_RATIO;
        enabled = true;
      } else if ("GAME_COIN".equals(fromCurrency) && "SHOP_COIN".equals(toCurrency)) {
        ratio = DEFAULT_GAME_TO_SHOP_RATIO;
        enabled = false;
      } else {
        throw new ServiceException(400, "invalid_currency", "unsupported exchange direction");
      }
      if (!enabled) {
        throw new ServiceException(409, "feature_disabled", "exchange direction is disabled");
      }
      long convertedAmount = (long) Math.floor(amount * ratio);
      if (convertedAmount <= 0) {
        throw new ServiceException(400, "invalid_amount", "converted amount must be positive");
      }

      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          ensureWallet(connection, authUser.userId());
          boolean debitApplied = insertLedgerEntry(
              connection,
              authUser.userId(),
              fromCurrency,
              -amount,
              "EXCHANGE_OUT",
              idempotencyKey);
          boolean creditApplied = insertLedgerEntry(
              connection,
              authUser.userId(),
              toCurrency,
              convertedAmount,
              "EXCHANGE_IN",
              idempotencyKey);
          if (debitApplied != creditApplied) {
            throw new ServiceException(409, "idempotency_conflict", "exchange idempotency state mismatch");
          }

          if (debitApplied) {
            long fromBalance = readCurrencyBalanceForUpdate(connection, authUser.userId(), fromCurrency);
            long nextFrom = Math.addExact(fromBalance, -amount);
            if (nextFrom < 0) {
              throw new ServiceException(409, "insufficient_funds", "insufficient wallet balance");
            }
            updateCurrencyBalance(connection, authUser.userId(), fromCurrency, nextFrom);
            long toBalance = readCurrencyBalanceForUpdate(connection, authUser.userId(), toCurrency);
            long nextTo = Math.addExact(toBalance, convertedAmount);
            updateCurrencyBalance(connection, authUser.userId(), toCurrency, nextTo);
          }

          connection.commit();
          JsonObject response = buildCompatWalletJson(connection, authUser.userId(), authUser.username());
          writeJson(exchange, 200, response);
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }
    }

    private void handleCompatProducts(HttpExchange exchange) throws Exception {
      try (Connection connection = state.connection()) {
        JsonArray products = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sku, title, currency, price, active, created_at, updated_at
            FROM m2_products
            WHERE active = 1
            ORDER BY id ASC
            """)) {
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              JsonObject product = new JsonObject();
              product.addProperty("id", resultSet.getLong("id"));
              product.addProperty("sku", resultSet.getString("sku"));
              product.addProperty("title", resultSet.getString("title"));
              product.addProperty("currency", resultSet.getString("currency"));
              product.addProperty("price", resultSet.getLong("price"));
              product.addProperty("productType", "COMMAND");
              product.addProperty("itemMaterial", "DIAMOND");
              product.addProperty("itemAmount", 64);
              product.addProperty("stockRemaining", 64);
              product.addProperty("dynamicPricingEnabled", false);
              product.addProperty("createdAt", resultSet.getString("created_at"));
              product.addProperty("updatedAt", resultSet.getString("updated_at"));
              products.add(product);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("products", products);
        writeJson(exchange, 200, response);
      }
    }

    private void handleCompatCreateOrder(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long productId = readPositiveLong(payload, "productId", "invalid_product", "product id must be positive");
      int quantity = (int) readPositiveLong(payload, "quantity", "invalid_quantity", "quantity must be positive");
      if (quantity > 10_000) {
        throw new ServiceException(400, "invalid_quantity", "quantity is too large");
      }
      String idempotencyKey = optionalString(payload, "idempotencyKey");
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        idempotencyKey = UUID.randomUUID().toString().replace("-", "");
      }

      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          requireUser(connection, authUser.userId());
          ensureWallet(connection, authUser.userId());

          ExistingOrder existing = readExistingOrder(connection, idempotencyKey);
          if (existing != null) {
            connection.commit();
            JsonObject response = new JsonObject();
            response.addProperty("state", "EXISTING");
            response.addProperty("orderNo", existing.orderNo());
            response.addProperty("totalAmount", existing.totalAmount());
            response.addProperty("currency", existing.currency());
            response.addProperty("orderStatus", compatOrderStatus(existing.status()));
            response.addProperty("status", compatOrderStatus(existing.status()));
            writeJson(exchange, 200, response);
            return;
          }

          ProductRow product = readActiveProductForUpdate(connection, productId);
          long totalAmount = Math.multiplyExact(product.price(), quantity);
          long balance = readCurrencyBalanceForUpdate(connection, authUser.userId(), product.currency());
          long next = Math.addExact(balance, -totalAmount);
          if (next < 0) {
            throw new ServiceException(409, "insufficient_funds", "insufficient wallet balance");
          }

          updateCurrencyBalance(connection, authUser.userId(), product.currency(), next);
          boolean debitApplied = insertLedgerEntry(
              connection,
              authUser.userId(),
              product.currency(),
              -totalAmount,
              "ORDER_DEBIT",
              idempotencyKey + ":DEBIT");
          if (!debitApplied) {
            throw new ServiceException(409, "idempotency_conflict", "duplicate debit request");
          }

          String orderNo = "M2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
          try (PreparedStatement insert = connection.prepareStatement("""
              INSERT INTO m2_orders (
                order_no,
                user_id,
                product_id,
                quantity,
                total_amount,
                currency,
                status,
                idempotency_key,
                created_at
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)) {
            insert.setString(1, orderNo);
            insert.setLong(2, authUser.userId());
            insert.setLong(3, productId);
            insert.setInt(4, quantity);
            insert.setLong(5, totalAmount);
            insert.setString(6, product.currency());
            insert.setString(7, "DELIVERED");
            insert.setString(8, idempotencyKey);
            insert.setString(9, Instant.now().toString());
            insert.executeUpdate();
          }

          connection.commit();
          JsonObject response = new JsonObject();
          response.addProperty("state", "CREATED");
          response.addProperty("orderNo", orderNo);
          response.addProperty("currency", product.currency());
          response.addProperty("totalAmount", totalAmount);
          response.addProperty("orderStatus", "DELIVERED");
          response.addProperty("status", "DELIVERED");
          response.addProperty("balance", next);
          writeJson(exchange, 201, response);
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }
    }

    private void handleCompatOrdersList(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      int limit = parseLimit(query.get("limit"), 50, 1, 200);
      try (Connection connection = state.connection()) {
        JsonArray orders = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT o.order_no, o.product_id, p.sku, p.title, o.quantity, o.total_amount, o.currency, o.status, o.created_at
            FROM m2_orders o
            JOIN m2_products p ON p.id = o.product_id
            WHERE o.user_id = ?
            ORDER BY o.id DESC
            LIMIT ?
            """)) {
          statement.setLong(1, authUser.userId());
          statement.setInt(2, limit);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              JsonObject row = new JsonObject();
              row.addProperty("orderNo", resultSet.getString("order_no"));
              row.addProperty("productId", resultSet.getLong("product_id"));
              row.addProperty("sku", resultSet.getString("sku"));
              row.addProperty("productTitle", resultSet.getString("title"));
              row.addProperty("productType", "COMMAND");
              row.addProperty("quantity", resultSet.getInt("quantity"));
              row.addProperty("totalAmount", resultSet.getLong("total_amount"));
              row.addProperty("currency", resultSet.getString("currency"));
              row.addProperty("status", compatOrderStatus(resultSet.getString("status")));
              row.addProperty("createdAt", resultSet.getString("created_at"));
              String compatStatus = compatOrderStatus(resultSet.getString("status"));
              boolean refundUndeliveredEnabled =
                  !getOrCreateObject(economyRuntimeSettings, "webshopRuntime").has("refundUndeliveredEnabled")
                      || getOrCreateObject(economyRuntimeSettings, "webshopRuntime").get("refundUndeliveredEnabled").getAsBoolean();
              row.addProperty("canRefund", refundUndeliveredEnabled && !"REFUNDED".equals(compatStatus));
              orders.add(row);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("orders", orders);
        response.addProperty("cooldownSeconds", 0);
        response.addProperty("refundUndeliveredEnabled", false);
        response.addProperty("sharedClaimAllowed", false);
        writeJson(exchange, 200, response);
      }
    }

    private void handleCompatOrderPolicy(HttpExchange exchange) throws IOException {
      JsonObject webshopRuntime = getOrCreateObject(economyRuntimeSettings, "webshopRuntime");
      JsonObject marketRuntime = getOrCreateObject(economyRuntimeSettings, "marketRuntime");
      JsonObject supply = getOrCreateObject(marketRuntime, "supply");
      JsonObject response = new JsonObject();
      response.addProperty("cooldownSeconds", webshopRuntime.has("orderCooldownSeconds") ? webshopRuntime.get("orderCooldownSeconds").getAsInt() : 0);
      boolean refundUndeliveredEnabled =
          !webshopRuntime.has("refundUndeliveredEnabled") || webshopRuntime.get("refundUndeliveredEnabled").getAsBoolean();
      response.addProperty("refundEnabled", refundUndeliveredEnabled);
      response.addProperty("refundUndeliveredEnabled", refundUndeliveredEnabled);
      response.addProperty("marketFeePercent", marketRuntime.has("marketFeePercent") ? marketRuntime.get("marketFeePercent").getAsInt() : 0);
      response.addProperty("marketTaxPercent", marketRuntime.has("marketTaxPercent") ? marketRuntime.get("marketTaxPercent").getAsInt() : 0);
      response.addProperty("marketSupplyAutoRefreshThreshold",
          supply.has("autoRefreshThreshold") ? supply.get("autoRefreshThreshold").getAsInt() : 8);
      response.addProperty("sharedClaimAllowed",
          webshopRuntime.has("allowSharedClaimCommand") && webshopRuntime.get("allowSharedClaimCommand").getAsBoolean());
      writeJson(exchange, 200, response);
    }

    private void handleCompatNotificationsUnread(HttpExchange exchange) throws Exception {
      requireCompatAuth(exchange);
      JsonObject response = new JsonObject();
      response.addProperty("unreadCount", 0);
      writeJson(exchange, 200, response);
    }

    private void handleCompatNotificationsList(HttpExchange exchange) throws Exception {
      requireCompatAuth(exchange);
      JsonObject response = new JsonObject();
      response.add("notifications", new JsonArray());
      response.addProperty("unreadCount", 0);
      writeJson(exchange, 200, response);
    }

    private void handleCompatNotificationsMarkRead(HttpExchange exchange) throws Exception {
      requireCompatAuth(exchange);
      JsonObject response = new JsonObject();
      response.addProperty("unreadCount", 0);
      writeJson(exchange, 200, response);
    }

    private void handleCompatCurrencyMeta(HttpExchange exchange) throws IOException {
      JsonObject currency = getOrCreateObject(economyRuntimeSettings, "currency");
      JsonObject response = new JsonObject();
      JsonObject shopCoin = new JsonObject();
      shopCoin.addProperty("name", currency.has("shopCoinName") ? currency.get("shopCoinName").getAsString() : "ShopCoin");
      shopCoin.addProperty("short", currency.has("shopCoinShort") ? currency.get("shopCoinShort").getAsString() : "SC");
      JsonObject gameCoin = new JsonObject();
      gameCoin.addProperty("name", currency.has("gameCoinName") ? currency.get("gameCoinName").getAsString() : "GameCoin");
      gameCoin.addProperty("short", currency.has("gameCoinShort") ? currency.get("gameCoinShort").getAsString() : "GC");
      response.add("shopCoin", shopCoin);
      response.add("gameCoin", gameCoin);
      response.add("exchange", buildExchangeMetaJson());
      JsonObject runtime = getOrCreateObject(economyRuntimeSettings, "webshopRuntime");
      response.addProperty("timeZone", runtime.has("timeZone") ? runtime.get("timeZone").getAsString() : "Asia/Shanghai");
      writeJson(exchange, 200, response);
    }

    private void handleCompatMaterialMeta(HttpExchange exchange) throws IOException {
      JsonArray materials = new JsonArray();
      for (String material : DEFAULT_MATERIAL_ALLOW_LIST) {
        materials.add(material);
      }
      JsonObject response = new JsonObject();
      response.add("materials", materials);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMaterialOverridesMeta(HttpExchange exchange) throws IOException {
      JsonObject response = new JsonObject();
      JsonArray overrides = new JsonArray();
      for (MaterialOverrideRecord record : materialOverrides.values()) {
        overrides.add(materialOverrideToJson(record));
      }
      response.add("overrides", overrides);
      JsonObject visual = getOrCreateObject(economyRuntimeSettings, "visual");
      JsonObject policy = cloneJsonObject(visual);
      response.add("policy", policy);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketTagsMeta(HttpExchange exchange) throws IOException {
      JsonObject response = new JsonObject();
      JsonArray tags = new JsonArray();
      JsonArray configured = marketTagsConfig.has("tags") && marketTagsConfig.get("tags").isJsonArray()
          ? marketTagsConfig.getAsJsonArray("tags")
          : new JsonArray();
      for (JsonElement element : configured) {
        if (element != null && element.isJsonObject()) {
          tags.add(cloneJsonObject(element.getAsJsonObject()));
        }
      }
      response.add("tags", tags);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketListings(HttpExchange exchange) throws Exception {
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      int limit = parseLimit(query.get("limit"), 200, 1, 500);
      boolean mineOnly = Boolean.parseBoolean(String.valueOf(query.getOrDefault("mine", "false")));
      String statusFilter = normalizeOptionalText(query.get("status"));
      String sellerFilter = normalizeOptionalText(query.get("seller"));
      String keywordFilter = normalizeOptionalText(query.get("keyword"));
      CompatAuthUser authUser = null;
      if (mineOnly) {
        authUser = resolveCompatAuthOptional(exchange);
      }

      List<JsonObject> rows = new ArrayList<>();
      for (JsonObject listing : compatMarketListings.values()) {
        JsonObject row = cloneJsonObject(listing);
        String status = row.has("status") ? row.get("status").getAsString() : "ACTIVE";
        String sellerName = row.has("sellerName") ? row.get("sellerName").getAsString() : "";
        String itemMaterial = row.has("itemMaterial") ? row.get("itemMaterial").getAsString() : "";
        String remark = row.has("remark") ? row.get("remark").getAsString() : "";
        if (mineOnly) {
          if (authUser == null || !authUser.username().equalsIgnoreCase(sellerName)) {
            continue;
          }
        }
        if (statusFilter != null && !status.equalsIgnoreCase(statusFilter)) {
          continue;
        }
        if (sellerFilter != null && !sellerName.toLowerCase().contains(sellerFilter.toLowerCase())) {
          continue;
        }
        if (keywordFilter != null) {
          String haystack = (sellerName + " " + itemMaterial + " " + remark + " "
              + (row.has("id") ? row.get("id").getAsLong() : 0L)).toLowerCase();
          if (!haystack.contains(keywordFilter.toLowerCase())) {
            continue;
          }
        }
        rows.add(row);
      }
      rows.sort((left, right) -> Long.compare(
          right.has("id") ? right.get("id").getAsLong() : 0L,
          left.has("id") ? left.get("id").getAsLong() : 0L));
      JsonArray listings = new JsonArray();
      for (int i = 0; i < rows.size() && i < limit; i++) {
        listings.add(rows.get(i));
      }
      JsonObject response = new JsonObject();
      response.add("listings", listings);
      writeJson(exchange, 200, response);
    }

    private void handleCompatOrderRefund(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String orderNo = readNonBlank(payload, "orderNo", "invalid_order_no", "orderNo is required");
      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          String status;
          long totalAmount;
          String currency;
          try (PreparedStatement statement = connection.prepareStatement("""
              SELECT status, total_amount, currency
              FROM m2_orders
              WHERE order_no = ? AND user_id = ?
              LIMIT 1
              """)) {
            statement.setString(1, orderNo);
            statement.setLong(2, authUser.userId());
            try (ResultSet resultSet = statement.executeQuery()) {
              if (!resultSet.next()) {
                throw new ServiceException(404, "order_missing", "order not found");
              }
              status = compatOrderStatus(resultSet.getString("status"));
              totalAmount = resultSet.getLong("total_amount");
              currency = normalizeCurrency(resultSet.getString("currency"));
            }
          }

          if (!"REFUNDED".equals(status)) {
            boolean credited = insertLedgerEntry(
                connection,
                authUser.userId(),
                currency,
                totalAmount,
                "ORDER_REFUND",
                orderNo);
            if (credited) {
              long balance = readCurrencyBalanceForUpdate(connection, authUser.userId(), currency);
              updateCurrencyBalance(connection, authUser.userId(), currency, Math.addExact(balance, totalAmount));
            }
            try (PreparedStatement update = connection.prepareStatement(
                "UPDATE m2_orders SET status = ? WHERE order_no = ? AND user_id = ?")) {
              update.setString(1, "REFUNDED");
              update.setString(2, orderNo);
              update.setLong(3, authUser.userId());
              update.executeUpdate();
            }
          }
          connection.commit();
          JsonObject response = buildCompatWalletJson(connection, authUser.userId(), authUser.username());
          response.addProperty("orderNo", orderNo);
          response.addProperty("status", "REFUNDED");
          writeJson(exchange, 200, response);
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }
    }

    private void handleCompatLocalesMeta(HttpExchange exchange) throws IOException {
      JsonObject stateJson = buildLocaleCenterStateJson();
      JsonObject response = new JsonObject();
      response.addProperty("defaultLocale", stateJson.has("defaultLocale") ? stateJson.get("defaultLocale").getAsString() : "zh-CN");
      response.add("locales", stateJson.has("locales") && stateJson.get("locales").isJsonArray()
          ? stateJson.getAsJsonArray("locales")
          : new JsonArray());
      writeJson(exchange, 200, response);
    }

    private void handleCompatThemesMeta(HttpExchange exchange) throws IOException {
      JsonObject stateJson = buildThemeCenterStateJson();
      JsonObject response = new JsonObject();
      response.addProperty("defaultTheme", stateJson.has("defaultTheme") ? stateJson.get("defaultTheme").getAsString() : "default");
      response.add("themes", stateJson.has("themes") && stateJson.get("themes").isJsonArray()
          ? stateJson.getAsJsonArray("themes")
          : new JsonArray());
      writeJson(exchange, 200, response);
    }

    private void handleCompatRechargeCreate(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long amountMinor = readPositiveLong(payload, "amountMinor", "invalid_amount", "amountMinor must be positive");
      long coinAmount = payload.has("coinAmount") && !payload.get("coinAmount").isJsonNull()
          ? Math.max(1L, payload.get("coinAmount").getAsLong())
          : amountMinor;
      String orderId = "M2-RCG-" + Long.toString(++rechargeSequence, 36).toUpperCase();
      String now = Instant.now().toString();
      JsonObject record = new JsonObject();
      record.addProperty("orderId", orderId);
      record.addProperty("userId", authUser.userId());
      record.addProperty("amountMinor", amountMinor);
      record.addProperty("coinAmount", coinAmount);
      record.addProperty("status", "PENDING");
      record.addProperty("createdAt", now);
      record.addProperty("credited", false);
      rechargeOrders.put(orderId, record);

      JsonObject response = new JsonObject();
      response.addProperty("orderId", orderId);
      response.addProperty("status", "PENDING");
      response.addProperty("coinAmount", coinAmount);
      response.addProperty("payUrl", "/help.html#recharge");
      writeJson(exchange, 200, response);
    }

    private void handleCompatRechargeStatus(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String orderId = normalizeOptionalText(query.get("orderId"));
      if (orderId == null) {
        throw new ServiceException(400, "invalid_order_id", "orderId is required");
      }
      JsonObject record = rechargeOrders.get(orderId);
      if (record == null || !record.has("userId") || record.get("userId").getAsLong() != authUser.userId()) {
        throw new ServiceException(404, "order_missing", "recharge order not found");
      }

      String status = record.has("status") ? record.get("status").getAsString() : "PENDING";
      boolean credited = record.has("credited") && record.get("credited").getAsBoolean();
      if (!credited && "PENDING".equalsIgnoreCase(status)) {
        long createdAtMillis = Instant.parse(record.get("createdAt").getAsString()).toEpochMilli();
        if (System.currentTimeMillis() - createdAtMillis >= 1500L) {
          long coinAmount = record.get("coinAmount").getAsLong();
          try (Connection connection = state.connection()) {
            connection.setAutoCommit(false);
            try {
              ensureWallet(connection, authUser.userId());
              boolean inserted = insertLedgerEntry(
                  connection,
                  authUser.userId(),
                  "SHOP_COIN",
                  coinAmount,
                  "RECHARGE",
                  orderId);
              if (inserted) {
                long balance = readCurrencyBalanceForUpdate(connection, authUser.userId(), "SHOP_COIN");
                updateCurrencyBalance(connection, authUser.userId(), "SHOP_COIN", Math.addExact(balance, coinAmount));
              }
              connection.commit();
            } catch (Exception exception) {
              connection.rollback();
              throw exception;
            } finally {
              connection.setAutoCommit(true);
            }
          }
          record.addProperty("status", "PAID");
          record.addProperty("credited", true);
          status = "PAID";
        }
      }

      JsonObject response = new JsonObject();
      response.addProperty("orderId", orderId);
      response.addProperty("status", status.toUpperCase());
      response.addProperty("coinAmount", record.get("coinAmount").getAsLong());
      writeJson(exchange, 200, response);
    }

    private void handleCompatRedeemUse(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String code = readNonBlank(payload, "code", "invalid_code", "code is required").toUpperCase();
      RedeemCodeRecord target = null;
      int targetIndex = -1;
      for (int i = 0; i < redeemCodes.size(); i++) {
        RedeemCodeRecord record = redeemCodes.get(i);
        if (record.code().equalsIgnoreCase(code)) {
          target = record;
          targetIndex = i;
          break;
        }
      }
      if (target == null) {
        JsonObject response = buildCompatWalletResponse(authUser);
        response.addProperty("status", "INVALID_CODE");
        writeJson(exchange, 200, response);
        return;
      }
      if (!target.active()) {
        JsonObject response = buildCompatWalletResponse(authUser);
        response.addProperty("status", "OUT_OF_STOCK");
        writeJson(exchange, 200, response);
        return;
      }
      if (target.expiresAt() != null) {
        try {
          if (Instant.parse(target.expiresAt()).isBefore(Instant.now())) {
            JsonObject response = buildCompatWalletResponse(authUser);
            response.addProperty("status", "EXPIRED");
            writeJson(exchange, 200, response);
            return;
          }
        } catch (Exception ignored) {
          // ignore malformed time
        }
      }
      if (target.usedCount() >= target.maxUses()) {
        JsonObject response = buildCompatWalletResponse(authUser);
        response.addProperty("status", "OUT_OF_STOCK");
        writeJson(exchange, 200, response);
        return;
      }
      String usageKey = authUser.userId() + ":" + target.code().toUpperCase();
      long usedByUser = redeemUsageCounters.getOrDefault(usageKey, 0L);
      if (usedByUser >= target.perUserMaxUses()) {
        JsonObject response = buildCompatWalletResponse(authUser);
        response.addProperty("status", target.perUserMaxUses() <= 1 ? "ALREADY_USED" : "USER_LIMIT_REACHED");
        writeJson(exchange, 200, response);
        return;
      }

      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          ensureWallet(connection, authUser.userId());
          if (target.shopCoin() != 0) {
            boolean inserted = insertLedgerEntry(
                connection,
                authUser.userId(),
                "SHOP_COIN",
                target.shopCoin(),
                "REDEEM",
                target.code());
            if (inserted) {
              long balance = readCurrencyBalanceForUpdate(connection, authUser.userId(), "SHOP_COIN");
              updateCurrencyBalance(connection, authUser.userId(), "SHOP_COIN", Math.addExact(balance, target.shopCoin()));
            }
          }
          if (target.gameCoin() != 0) {
            boolean inserted = insertLedgerEntry(
                connection,
                authUser.userId(),
                "GAME_COIN",
                target.gameCoin(),
                "REDEEM",
                target.code());
            if (inserted) {
              long balance = readCurrencyBalanceForUpdate(connection, authUser.userId(), "GAME_COIN");
              updateCurrencyBalance(connection, authUser.userId(), "GAME_COIN", Math.addExact(balance, target.gameCoin()));
            }
          }
          connection.commit();
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }

      redeemUsageCounters.put(usageKey, usedByUser + 1L);
      if (targetIndex >= 0) {
        redeemCodes.set(targetIndex, new RedeemCodeRecord(
            target.code(),
            target.shopCoin(),
            target.gameCoin(),
            target.usedCount() + 1L,
            target.maxUses(),
            target.perUserMaxUses(),
            target.expiresAt(),
            true));
      }
      JsonObject response = buildCompatWalletResponse(authUser);
      response.addProperty("status", "SUCCESS");
      writeJson(exchange, 200, response);
    }

    private void handleCompatLeaderboardConfig(HttpExchange exchange) throws IOException {
      JsonObject settings = getOrCreateObject(economyRuntimeSettings, "leaderboard");
      JsonObject leaderboard = new JsonObject();
      leaderboard.addProperty("enabled", !settings.has("enabled") || settings.get("enabled").getAsBoolean());
      leaderboard.addProperty("showOnlineStatus", !settings.has("showOnlineStatus") || settings.get("showOnlineStatus").getAsBoolean());
      leaderboard.addProperty("defaultMetric", settings.has("defaultMetric") ? settings.get("defaultMetric").getAsString() : "GAME_COIN");
      leaderboard.addProperty("defaultOrder", settings.has("defaultOrder") ? settings.get("defaultOrder").getAsString() : "DESC");
      JsonObject response = new JsonObject();
      response.add("leaderboard", leaderboard);
      writeJson(exchange, 200, response);
    }

    private void handleCompatLeaderboardList(HttpExchange exchange) throws Exception {
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String metric = normalizeOptionalText(query.get("metric"));
      if (metric == null) {
        metric = "GAME_COIN";
      }
      metric = metric.toUpperCase();
      String order = normalizeOptionalText(query.get("order"));
      if (order == null) {
        order = "DESC";
      }
      order = order.toUpperCase();
      final String metricKey = metric;
      final String orderKey = order;
      int limit = parseLimit(query.get("limit"), 100, 1, 200);
      CompatAuthUser authUser = resolveCompatAuthOptional(exchange);

      List<JsonObject> entries = new ArrayList<>();
      try (Connection connection = state.connection();
           PreparedStatement statement = connection.prepareStatement("""
               SELECT u.id, u.username, IFNULL(w.shop_coin, 0) AS shop_coin, IFNULL(w.game_coin, 0) AS game_coin
               FROM m2_users u
               LEFT JOIN m2_wallets w ON w.user_id = u.id
               """)) {
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            long userId = resultSet.getLong("id");
            long shopCoin = resultSet.getLong("shop_coin");
            long gameCoin = resultSet.getLong("game_coin");
            JsonObject row = new JsonObject();
            row.addProperty("userId", userId);
            row.addProperty("username", resultSet.getString("username"));
            row.addProperty("shopCoin", shopCoin);
            row.addProperty("gameCoin", gameCoin);
            row.addProperty("onlineTimeMinutes", Math.max(0L, userId * 30L));
            row.addProperty("online", userId % 2 == 1);
            entries.add(row);
          }
        }
      }
      entries.sort((left, right) -> {
        long leftValue = leaderboardMetricValue(left, metricKey);
        long rightValue = leaderboardMetricValue(right, metricKey);
        int cmp = Long.compare(leftValue, rightValue);
        if (!"ASC".equals(orderKey)) {
          cmp = -cmp;
        }
        if (cmp != 0) {
          return cmp;
        }
        return Long.compare(
            left.has("userId") ? left.get("userId").getAsLong() : 0L,
            right.has("userId") ? right.get("userId").getAsLong() : 0L);
      });

      JsonArray resultRows = new JsonArray();
      Long myRank = null;
      for (int i = 0; i < entries.size(); i++) {
        JsonObject source = entries.get(i);
        JsonObject row = cloneJsonObject(source);
        long rank = i + 1L;
        row.addProperty("rank", rank);
        if (authUser != null && source.has("userId") && source.get("userId").getAsLong() == authUser.userId()) {
          myRank = rank;
        }
        if (resultRows.size() < limit) {
          resultRows.add(row);
        }
      }

      JsonObject response = new JsonObject();
      response.add("entries", resultRows);
      response.addProperty("metric", metric);
      response.addProperty("order", order);
      response.addProperty("total", entries.size());
      response.addProperty("requestedRange", normalizeOptionalText(query.get("range")) == null ? "TOTAL" : query.get("range"));
      response.addProperty("effectiveRange", "TOTAL");
      if (myRank != null) {
        response.addProperty("myRank", myRank);
      } else {
        response.add("myRank", null);
      }
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketCreateListing(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String side = normalizeMarketSide(optionalString(payload, "side"));
      String tradeMode = normalizeTradeMode(optionalString(payload, "tradeMode"));
      String itemMaterial = normalizeMaterialKey(optionalString(payload, "itemMaterial"));
      if (itemMaterial == null) {
        itemMaterial = "DIAMOND";
      }
      long price = readPositiveLong(payload, "price", "invalid_price", "price must be positive");
      int quantity = (int) readPositiveLong(payload, "quantity", "invalid_quantity", "quantity must be positive");
      String currency = normalizeCurrency(readNonBlank(payload, "currency", "invalid_currency", "currency is required"));
      String tag = normalizeOptionalText(optionalString(payload, "tag"));
      long listingId = ++marketListingSequence;
      String createdAt = Instant.now().toString();
      JsonObject listing = createMarketListingJson(
          listingId,
          authUser.userId(),
          authUser.username(),
          side,
          tradeMode,
          itemMaterial,
          price,
          quantity,
          currency,
          tag,
          createdAt);
      compatMarketListings.put(listingId, listing);
      JsonObject response = cloneJsonObject(listing);
      response.addProperty("listingId", listingId);
      response.addProperty("material", itemMaterial);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketBuy(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      int buyQuantity = (int) readPositiveLong(payload, "buyQuantity", "invalid_quantity", "buyQuantity must be positive");
      String idempotencyKey = optionalString(payload, "idempotencyKey");
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        idempotencyKey = UUID.randomUUID().toString().replace("-", "");
      }
      String idemKey = "BUY:" + authUser.userId() + ":" + listingId + ":" + idempotencyKey;
      if (marketTradesByIdempotency.containsKey(idemKey)) {
        JsonObject existing = cloneJsonObject(marketTradesByIdempotency.get(idemKey));
        existing.addProperty("state", "EXISTING");
        writeJson(exchange, 200, existing);
        return;
      }

      JsonObject listing = requireMarketListing(listingId);
      if (!"SELL".equalsIgnoreCase(optionalString(listing, "marketSide"))) {
        throw new ServiceException(409, "invalid_market_side", "listing side is not SELL");
      }
      if (!"ACTIVE".equalsIgnoreCase(optionalString(listing, "status"))) {
        throw new ServiceException(409, "listing_unavailable", "listing is not active");
      }
      int remaining = listing.has("quantity") ? listing.get("quantity").getAsInt() : 0;
      if (remaining < buyQuantity) {
        throw new ServiceException(409, "insufficient_stock", "listing stock is not enough");
      }

      long unitPrice = listing.get("price").getAsLong();
      String currency = normalizeCurrency(listing.get("currency").getAsString());
      long subtotal = Math.multiplyExact(unitPrice, buyQuantity);
      long feeAmount = calculatePercent(subtotal, marketFeePercent());
      long taxAmount = calculatePercent(subtotal, marketTaxPercent());
      long buyerTotal = Math.addExact(subtotal, taxAmount);
      long sellerReceive = Math.max(0L, subtotal - feeAmount);

      long sellerUserId = listing.has("sellerUserId") ? listing.get("sellerUserId").getAsLong() : -1L;
      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          ensureWallet(connection, authUser.userId());
          long buyerBalance = readCurrencyBalanceForUpdate(connection, authUser.userId(), currency);
          long buyerNext = Math.addExact(buyerBalance, -buyerTotal);
          if (buyerNext < 0) {
            throw new ServiceException(409, "insufficient_balance", "insufficient wallet balance");
          }
          boolean buyerApplied = insertLedgerEntry(connection, authUser.userId(), currency, -buyerTotal, "MARKET_BUY", idemKey);
          if (buyerApplied) {
            updateCurrencyBalance(connection, authUser.userId(), currency, buyerNext);
          }

          if (sellerUserId > 0 && sellerUserId != authUser.userId()) {
            ensureWallet(connection, sellerUserId);
            boolean sellerApplied = insertLedgerEntry(connection, sellerUserId, currency, sellerReceive, "MARKET_SELL", idemKey);
            if (sellerApplied) {
              long sellerBalance = readCurrencyBalanceForUpdate(connection, sellerUserId, currency);
              updateCurrencyBalance(connection, sellerUserId, currency, Math.addExact(sellerBalance, sellerReceive));
            }
          }
          connection.commit();
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }

      int nextQuantity = Math.max(0, remaining - buyQuantity);
      listing.addProperty("quantity", nextQuantity);
      if (nextQuantity <= 0) {
        listing.addProperty("status", "SOLD");
        listing.addProperty("soldAt", Instant.now().toString());
        listing.addProperty("buyerName", authUser.username());
        listing.addProperty("buyerUuid", boundUuidForUser(authUser.userId()));
      }
      compatMarketListings.put(listingId, listing);

      JsonObject response = new JsonObject();
      long tradeId = ++marketTradeSequence;
      response.addProperty("state", "CREATED");
      response.addProperty("tradeId", "M2-T-" + tradeId);
      response.addProperty("listingId", listingId);
      response.addProperty("quantity", buyQuantity);
      response.addProperty("currency", currency);
      response.addProperty("totalPrice", subtotal);
      response.addProperty("buyerTotal", buyerTotal);
      response.addProperty("sellerReceive", sellerReceive);
      response.addProperty("feeAmount", feeAmount);
      response.addProperty("taxAmount", taxAmount);
      response.addProperty("orderStatus", "DELIVERED");
      response.addProperty("cooldownSeconds", 0);
      marketTradesByIdempotency.put(idemKey, cloneJsonObject(response));
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketSellToBuy(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      int sellQuantity = (int) readPositiveLong(payload, "sellQuantity", "invalid_quantity", "sellQuantity must be positive");
      String idempotencyKey = optionalString(payload, "idempotencyKey");
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        idempotencyKey = UUID.randomUUID().toString().replace("-", "");
      }
      String idemKey = "SELL_TO_BUY:" + authUser.userId() + ":" + listingId + ":" + idempotencyKey;
      if (marketTradesByIdempotency.containsKey(idemKey)) {
        JsonObject existing = cloneJsonObject(marketTradesByIdempotency.get(idemKey));
        existing.addProperty("state", "EXISTING");
        writeJson(exchange, 200, existing);
        return;
      }

      JsonObject listing = requireMarketListing(listingId);
      if (!"BUY".equalsIgnoreCase(optionalString(listing, "marketSide"))) {
        throw new ServiceException(409, "invalid_market_side", "listing side is not BUY");
      }
      if (!"ACTIVE".equalsIgnoreCase(optionalString(listing, "status"))) {
        throw new ServiceException(409, "listing_unavailable", "listing is not active");
      }
      int remaining = listing.has("quantity") ? listing.get("quantity").getAsInt() : 0;
      if (remaining < sellQuantity) {
        throw new ServiceException(409, "insufficient_stock", "listing demand is not enough");
      }

      long unitPrice = listing.get("price").getAsLong();
      String currency = normalizeCurrency(listing.get("currency").getAsString());
      long subtotal = Math.multiplyExact(unitPrice, sellQuantity);
      long feeAmount = calculatePercent(subtotal, marketFeePercent());
      long taxAmount = calculatePercent(subtotal, marketTaxPercent());
      long sellerReceive = Math.max(0L, subtotal - feeAmount);
      long escrowNeed = Math.addExact(subtotal, taxAmount);
      long escrowRemaining = listing.has("escrowRemaining") ? listing.get("escrowRemaining").getAsLong() : 0L;
      if (escrowRemaining < escrowNeed) {
        throw new ServiceException(409, "insufficient_escrow", "listing escrow is not enough");
      }

      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          ensureWallet(connection, authUser.userId());
          boolean sellerApplied = insertLedgerEntry(connection, authUser.userId(), currency, sellerReceive, "MARKET_SELL", idemKey);
          if (sellerApplied) {
            long sellerBalance = readCurrencyBalanceForUpdate(connection, authUser.userId(), currency);
            updateCurrencyBalance(connection, authUser.userId(), currency, Math.addExact(sellerBalance, sellerReceive));
          }
          connection.commit();
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }

      int nextQuantity = Math.max(0, remaining - sellQuantity);
      listing.addProperty("quantity", nextQuantity);
      listing.addProperty("escrowRemaining", escrowRemaining - escrowNeed);
      if (nextQuantity <= 0) {
        listing.addProperty("status", "SOLD");
        listing.addProperty("soldAt", Instant.now().toString());
      }
      compatMarketListings.put(listingId, listing);

      JsonObject response = new JsonObject();
      long tradeId = ++marketTradeSequence;
      response.addProperty("state", "CREATED");
      response.addProperty("tradeId", "M2-T-" + tradeId);
      response.addProperty("listingId", listingId);
      response.addProperty("quantity", sellQuantity);
      response.addProperty("currency", currency);
      response.addProperty("totalPrice", subtotal);
      response.addProperty("sellerReceive", sellerReceive);
      response.addProperty("feeAmount", feeAmount);
      response.addProperty("taxAmount", taxAmount);
      response.addProperty("orderStatus", "DELIVERED");
      marketTradesByIdempotency.put(idemKey, cloneJsonObject(response));
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketBid(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      long bidAmount = readPositiveLong(payload, "bidAmount", "invalid_bid_amount", "bidAmount must be positive");
      String idempotencyKey = optionalString(payload, "idempotencyKey");
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        idempotencyKey = UUID.randomUUID().toString().replace("-", "");
      }
      String idemKey = "BID:" + authUser.userId() + ":" + listingId + ":" + idempotencyKey;
      if (marketBidsByIdempotency.containsKey(idemKey)) {
        JsonObject existing = cloneJsonObject(marketBidsByIdempotency.get(idemKey));
        existing.addProperty("state", "EXISTING");
        writeJson(exchange, 200, existing);
        return;
      }

      JsonObject listing = requireMarketListing(listingId);
      String tradeMode = optionalString(listing, "tradeMode");
      if (!"AUCTION".equalsIgnoreCase(tradeMode)) {
        throw new ServiceException(409, "auction_only_bid", "listing is not auction mode");
      }
      long openingBid = listing.has("auctionStartPrice") ? Math.max(1L, listing.get("auctionStartPrice").getAsLong()) : Math.max(1L, listing.get("price").getAsLong());
      long currentHighest = listing.has("auctionHighestBid") && !listing.get("auctionHighestBid").isJsonNull()
          ? Math.max(0L, listing.get("auctionHighestBid").getAsLong())
          : 0L;
      long minIncrement = listing.has("auctionMinIncrement") ? Math.max(1L, listing.get("auctionMinIncrement").getAsLong()) : 1L;
      long minimumRequiredBid = currentHighest > 0 ? currentHighest + minIncrement : openingBid;
      if (bidAmount < minimumRequiredBid) {
        throw new ServiceException(409, "bid_too_low", "bid amount is below minimum required bid");
      }
      String currency = normalizeCurrency(listing.get("currency").getAsString());
      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          ensureWallet(connection, authUser.userId());
          long bidderBalance = readCurrencyBalanceForUpdate(connection, authUser.userId(), currency);
          long bidderNext = Math.addExact(bidderBalance, -bidAmount);
          if (bidderNext < 0) {
            throw new ServiceException(409, "insufficient_balance", "insufficient wallet balance");
          }
          boolean holdApplied = insertLedgerEntry(connection, authUser.userId(), currency, -bidAmount, "MARKET_BID_HOLD", idemKey);
          if (holdApplied) {
            updateCurrencyBalance(connection, authUser.userId(), currency, bidderNext);
          }

          if (currentHighest > 0 && listing.has("auctionHighestBidderUserId")) {
            long previousUserId = listing.get("auctionHighestBidderUserId").getAsLong();
            if (previousUserId > 0 && previousUserId != authUser.userId()) {
              ensureWallet(connection, previousUserId);
              boolean refundApplied = insertLedgerEntry(connection, previousUserId, currency, currentHighest, "MARKET_BID_REFUND", idemKey);
              if (refundApplied) {
                long previousBalance = readCurrencyBalanceForUpdate(connection, previousUserId, currency);
                updateCurrencyBalance(connection, previousUserId, currency, Math.addExact(previousBalance, currentHighest));
              }
            }
          }
          connection.commit();
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }

      listing.addProperty("auctionHighestBid", bidAmount);
      listing.addProperty("auctionHighestBidderUserId", authUser.userId());
      listing.addProperty("auctionHighestBidderUuid", boundUuidForUser(authUser.userId()));
      listing.addProperty("auctionLastBidAt", Instant.now().toString());
      compatMarketListings.put(listingId, listing);

      JsonObject response = new JsonObject();
      long bidId = ++marketBidSequence;
      response.addProperty("state", "CREATED");
      response.addProperty("listingId", listingId);
      response.addProperty("bidId", "M2-BID-" + bidId);
      response.addProperty("bidAmount", bidAmount);
      response.addProperty("currency", currency);
      response.addProperty("currentHighestBid", bidAmount);
      response.addProperty("minimumRequiredBid", minimumRequiredBid);
      response.addProperty("sealedBid", "VICKREY_AUCTION_V1".equalsIgnoreCase(optionalString(listing, "auctionAlgorithm")));
      marketBidsByIdempotency.put(idemKey, cloneJsonObject(response));
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketUnlist(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      listing.addProperty("status", "UNLISTED");
      listing.addProperty("unlistedAt", Instant.now().toString());
      compatMarketListings.put(listingId, listing);
      JsonObject response = okResponse();
      response.addProperty("listingId", listingId);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketSupplyRefresh(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      int maxStock = listing.has("supplyMaxStock") ? Math.max(0, listing.get("supplyMaxStock").getAsInt()) : 0;
      int batchSize = listing.has("supplyBatchSize") ? Math.max(0, listing.get("supplyBatchSize").getAsInt()) : 0;
      int currentStock = listing.has("quantity") ? Math.max(0, listing.get("quantity").getAsInt()) : 0;
      int loadedAmount = 0;
      if (maxStock > 0 && batchSize > 0 && currentStock < maxStock) {
        loadedAmount = Math.min(batchSize, maxStock - currentStock);
      }
      listing.addProperty("quantity", currentStock + loadedAmount);
      listing.addProperty("supplyLastLoadedAmount", loadedAmount);
      listing.addProperty("supplyLastLoadedAt", Instant.now().toString());
      listing.addProperty("supplyLoadedTotal",
          Math.max(0L, (listing.has("supplyLoadedTotal") ? listing.get("supplyLoadedTotal").getAsLong() : 0L) + loadedAmount));
      compatMarketListings.put(listingId, listing);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", listingId);
      response.addProperty("loadedAmount", loadedAmount);
      response.addProperty("currentStock", listing.get("quantity").getAsInt());
      response.addProperty("maxStock", maxStock <= 0 ? listing.get("quantity").getAsInt() : maxStock);
      response.addProperty("loadedTotal", listing.get("supplyLoadedTotal").getAsLong());
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketPause(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      listing.addProperty("status", "PAUSED");
      listing.addProperty("pausedAt", Instant.now().toString());
      compatMarketListings.put(listingId, listing);
      JsonObject response = okResponse();
      response.addProperty("listingId", listingId);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketResume(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      listing.addProperty("status", "ACTIVE");
      compatMarketListings.put(listingId, listing);
      JsonObject response = okResponse();
      response.addProperty("listingId", listingId);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketPrice(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      long price = readPositiveLong(payload, "price", "invalid_price", "price must be positive");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      listing.addProperty("price", price);
      compatMarketListings.put(listingId, listing);
      JsonObject response = okResponse();
      response.addProperty("listingId", listingId);
      response.addProperty("price", price);
      response.addProperty("currency", listing.get("currency").getAsString());
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketRemark(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      String remark = optionalString(payload, "remark");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      listing.addProperty("remark", remark == null ? "" : remark);
      compatMarketListings.put(listingId, listing);
      JsonObject response = okResponse();
      response.addProperty("listingId", listingId);
      response.addProperty("remark", remark == null ? "" : remark);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketSettings(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      copyJsonProperties(payload, listing, List.of(
          "price",
          "currency",
          "remark",
          "displayNameOverride",
          "displayMaterial",
          "displayIconPath",
          "supplyBatchSize",
          "supplyMaxStock",
          "tradeMode",
          "dynamicPricingEnabled",
          "dynamicAlgorithm",
          "dynamicParamsJson",
          "dynamicBasePrice",
          "dynamicFloorPrice",
          "dynamicCapPrice",
          "dynamicPriceStep",
          "auctionAlgorithm",
          "auctionParamsJson",
          "auctionStartPrice",
          "auctionMinIncrement",
          "auctionEndAt"));
      listing.addProperty("id", listingId);
      compatMarketListings.put(listingId, listing);
      JsonObject response = cloneJsonObject(listing);
      response.addProperty("listingId", listingId);
      writeJson(exchange, 200, response);
    }

    private void handleCompatMarketIconUpload(HttpExchange exchange) throws Exception {
      CompatAuthUser authUser = requireCompatAuth(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      long listingId = parsePositiveLong(query.get("listingId"), "invalid_listing_id", "listingId is required");
      JsonObject listing = requireOwnedMarketListing(listingId, authUser);
      String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      if (contentType == null || contentType.isBlank()) {
        contentType = "application/octet-stream";
      }
      byte[] bytes = exchange.getRequestBody().readAllBytes();
      String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
      listing.addProperty("displayIconPath", dataUrl);
      compatMarketListings.put(listingId, listing);
      JsonObject response = okResponse();
      response.addProperty("listingId", listingId);
      response.addProperty("displayIconPath", dataUrl);
      writeJson(exchange, 200, response);
    }

    private void handleAdminLogin(HttpExchange exchange) throws Exception {
      JsonObject payload = requireJsonBody(exchange);
      String identifier = optionalString(payload, "identifier");
      String password = optionalString(payload, "password");
      if (identifier == null || identifier.isBlank()) {
        throw new ServiceException(400, "invalid_identifier", "identifier is required");
      }
      if (password == null || password.isBlank()) {
        throw new ServiceException(400, "invalid_password", "password is required");
      }
      if (state.adminAuthRequired() && !password.equals(state.adminToken())) {
        throw new ServiceException(401, "auth_invalid", "invalid admin credentials");
      }

      String username = identifier.trim();
      String sessionToken = UUID.randomUUID().toString().replace("-", "");
      AdminSession adminSession = new AdminSession(sessionToken, username, true, true, Instant.now().toString());
      adminSessions.put(sessionToken, adminSession);
      adminManagers.putIfAbsent(1L, new AdminManagerRecord(
          1L,
          username,
          boundUuidForUser(1L),
          true,
          "SUPER_ADMIN",
          List.of("*"),
          "super",
          true,
          Instant.now().toString()));

      JsonObject response = new JsonObject();
      response.addProperty("sessionToken", sessionToken);
      response.add("admin", buildAdminProfileJson(adminSession));
      writeJson(exchange, 200, response);
    }

    private void handleAdminAuthMe(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      writeJson(exchange, 200, buildAdminProfileJson(adminSession));
    }

    private void handleAdminLogout(HttpExchange exchange) throws IOException {
      String sessionToken = readBearerToken(exchange);
      if (sessionToken == null || sessionToken.isBlank()) {
        throw new ServiceException(401, "auth_required", "Missing admin session token");
      }
      AdminSession removed = adminSessions.remove(sessionToken);
      if (removed == null) {
        throw new ServiceException(401, "auth_invalid", "admin session token is invalid or expired");
      }
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      writeJson(exchange, 200, response);
    }

    private void handleAdminProductsList(HttpExchange exchange) throws Exception {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      boolean includeInactive = Boolean.parseBoolean(String.valueOf(query.getOrDefault("includeInactive", "false")));
      int limit = parseLimit(query.get("limit"), 200, 1, 500);
      String sql = includeInactive
          ? """
            SELECT id, sku, title, currency, price, active, created_at, updated_at
            FROM m2_products
            ORDER BY id DESC
            LIMIT ?
            """
          : """
            SELECT id, sku, title, currency, price, active, created_at, updated_at
            FROM m2_products
            WHERE active = 1
            ORDER BY id DESC
            LIMIT ?
            """;

      try (Connection connection = state.connection()) {
        JsonArray products = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
          statement.setInt(1, limit);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              long id = resultSet.getLong("id");
              ProductAdminMeta meta = productAdminMeta.getOrDefault(id, ProductAdminMeta.defaults());
              JsonObject product = new JsonObject();
              product.addProperty("id", id);
              product.addProperty("sku", resultSet.getString("sku"));
              product.addProperty("title", resultSet.getString("title"));
              product.addProperty("currency", resultSet.getString("currency"));
              product.addProperty("price", resultSet.getLong("price"));
              product.addProperty("active", resultSet.getInt("active") == 1);
              product.addProperty("remark", meta.remark());
              product.addProperty("productType", meta.productType());
              product.addProperty("itemMaterial", meta.itemMaterial());
              product.addProperty("displayMaterial", meta.displayMaterial());
              product.addProperty("displayNameOverride", meta.displayNameOverride());
              product.addProperty("displayIconPath", meta.displayIconPath());
              product.addProperty("itemAmount", meta.itemAmount());
              product.addProperty("stockRemaining", meta.itemAmount());
              product.addProperty("perUserLimit", meta.perUserLimit());
              product.addProperty("commandTemplate", meta.commandTemplate());
              product.addProperty("dynamicPricingEnabled", meta.dynamicPricingEnabled());
              product.addProperty("dynamicAlgorithm", meta.dynamicAlgorithm());
              product.addProperty("dynamicBasePrice", meta.dynamicBasePrice());
              product.addProperty("dynamicFloorPrice", meta.dynamicFloorPrice());
              product.addProperty("dynamicCapPrice", meta.dynamicCapPrice());
              product.addProperty("dynamicPriceStep", meta.dynamicPriceStep());
              product.addProperty("dynamicParamsJson", meta.dynamicParamsJson());
              product.addProperty("publishAt", meta.publishAt());
              product.addProperty("unpublishAt", meta.unpublishAt());
              product.addProperty("createdAt", resultSet.getString("created_at"));
              product.addProperty("updatedAt", resultSet.getString("updated_at"));
              products.add(product);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("products", products);
        writeJson(exchange, 200, response);
      }
    }

    private void handleAdminOrdersList(HttpExchange exchange) throws Exception {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      int limit = parseLimit(query.get("limit"), 200, 1, 500);
      Long userIdFilter = null;
      String rawUserId = query.get("userId");
      if (rawUserId != null && !rawUserId.isBlank()) {
        userIdFilter = parsePositiveLong(rawUserId, "invalid_user", "user id must be positive");
      }
      String statusFilter = normalizeOrderFilterStatus(query.get("status"));
      String orderNoFilter = normalizeOptionalText(query.get("orderNo"));
      String currencyFilter = normalizeOptionalText(query.get("currency"));
      String keywordFilter = normalizeOptionalText(query.get("keyword"));

      StringBuilder sql = new StringBuilder("""
          SELECT o.id,
                 o.order_no,
                 o.user_id,
                 u.username,
                 o.product_id,
                 p.sku,
                 p.title,
                 o.quantity,
                 o.total_amount,
                 o.currency,
                 o.status,
                 o.created_at
          FROM m2_orders o
          JOIN m2_users u ON u.id = o.user_id
          JOIN m2_products p ON p.id = o.product_id
          WHERE 1 = 1
          """);
      List<Object> params = new ArrayList<>();
      if (userIdFilter != null) {
        sql.append(" AND o.user_id = ?");
        params.add(userIdFilter);
      }
      if (statusFilter != null) {
        sql.append(" AND UPPER(o.status) = ?");
        params.add(statusFilter);
      }
      if (orderNoFilter != null) {
        sql.append(" AND o.order_no = ?");
        params.add(orderNoFilter);
      }
      if (currencyFilter != null) {
        sql.append(" AND UPPER(o.currency) = ?");
        params.add(currencyFilter.toUpperCase());
      }
      if (keywordFilter != null) {
        sql.append(" AND (o.order_no LIKE ? OR u.username LIKE ? OR p.sku LIKE ? OR p.title LIKE ?)");
        String like = "%" + keywordFilter + "%";
        params.add(like);
        params.add(like);
        params.add(like);
        params.add(like);
      }
      sql.append(" ORDER BY o.id DESC LIMIT ?");
      params.add(limit);

      try (Connection connection = state.connection()) {
        JsonArray orders = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
          for (int index = 0; index < params.size(); index++) {
            Object value = params.get(index);
            if (value instanceof Long longValue) {
              statement.setLong(index + 1, longValue);
            } else if (value instanceof Integer intValue) {
              statement.setInt(index + 1, intValue);
            } else {
              statement.setString(index + 1, String.valueOf(value));
            }
          }
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              JsonObject row = new JsonObject();
              row.addProperty("orderNo", resultSet.getString("order_no"));
              row.addProperty("userId", resultSet.getLong("user_id"));
              row.addProperty("username", resultSet.getString("username"));
              row.addProperty("boundUuid", (String) null);
              row.addProperty("mcUuid", (String) null);
              row.addProperty("productId", resultSet.getLong("product_id"));
              row.addProperty("sku", resultSet.getString("sku"));
              row.addProperty("productTitle", resultSet.getString("title"));
              row.addProperty("productRemark", "");
              row.addProperty("productType", "COMMAND");
              row.addProperty("itemMaterial", "DIAMOND");
              row.addProperty("quantity", resultSet.getInt("quantity"));
              row.addProperty("totalAmount", resultSet.getLong("total_amount"));
              row.addProperty("currency", resultSet.getString("currency"));
              row.addProperty("status", compatOrderStatus(resultSet.getString("status")));
              row.addProperty("createdAt", resultSet.getString("created_at"));
              orders.add(row);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("orders", orders);
        writeJson(exchange, 200, response);
      }
    }

    private void handleAdminEconomySettings(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      writeJson(exchange, 200, cloneJsonObject(economyRuntimeSettings));
    }

    private void handleAdminLocales(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      writeJson(exchange, 200, buildLocaleCenterStateJson());
    }

    private void handleAdminThemes(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      writeJson(exchange, 200, buildThemeCenterStateJson());
    }

    private void handleAdminL10nManifest(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      JsonObject payload = new JsonObject();
      payload.addProperty("version", "m2-dev");
      payload.addProperty("generatedAt", Instant.now().toString());
      JsonArray locales = new JsonArray();
      for (LocaleCenterRecord record : localeCenterRecords) {
        JsonObject row = new JsonObject();
        row.addProperty("locale", record.locale());
        row.addProperty("name", record.name());
        row.addProperty("nativeName", record.nativeName());
        row.addProperty("version", record.version());
        locales.add(row);
      }
      JsonArray themes = new JsonArray();
      for (ThemeCenterRecord record : themeCenterRecords) {
        JsonObject row = new JsonObject();
        row.addProperty("themeId", record.themeId());
        row.addProperty("name", record.name());
        row.addProperty("version", record.version());
        themes.add(row);
      }
      payload.add("locales", locales);
      payload.add("themes", themes);
      writeJson(exchange, 200, payload);
    }

    private void handleAdminLocalesUpload(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String fileName = readNonBlank(payload, "fileName", "invalid_file_name", "fileName is required");
      String locale = inferLocaleFromFileName(fileName, "zh-CN");
      String contentBase64 = optionalString(payload, "contentBase64");
      int importedAssets = importLocalePackageAssets(contentBase64, locale);
      upsertLocaleRecord(new LocaleCenterRecord(
          locale,
          locale,
          locale,
          "upload",
          "uploaded-" + Instant.now().toEpochMilli(),
          "published",
          true,
          true,
          false,
          Instant.now().toString()));
      localeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_LOCALE_UPLOAD", "LOCALE", locale, null);
      JsonObject response = new JsonObject();
      response.add("state", buildLocaleCenterStateJson());
      JsonArray changed = new JsonArray();
      changed.add(locale);
      response.add("changed", changed);
      response.addProperty("importedAssets", importedAssets);
      writeJson(exchange, 200, response);
    }

    private void handleAdminLocalesSyncManifest(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonArray locales = payload.has("locales") && payload.get("locales").isJsonArray()
          ? payload.getAsJsonArray("locales")
          : new JsonArray();
      int succeeded = 0;
      for (JsonElement element : locales) {
        if (element == null || element.isJsonNull()) {
          continue;
        }
        String locale = normalizeOptionalText(element.getAsString());
        if (locale == null) {
          continue;
        }
        upsertLocaleRecord(new LocaleCenterRecord(
            locale,
            locale,
            locale,
            "github",
            "manifest-" + Instant.now().toEpochMilli(),
            "published",
            true,
            true,
            false,
            Instant.now().toString()));
        succeeded++;
      }
      localeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_LOCALE_MANIFEST_SYNC", "LOCALE", Integer.toString(succeeded), null);
      JsonObject response = new JsonObject();
      response.add("state", buildLocaleCenterStateJson());
      response.addProperty("succeeded", succeeded);
      response.addProperty("failed", 0);
      writeJson(exchange, 200, response);
    }

    private void handleAdminLocalesDefault(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String defaultLocale = readNonBlank(payload, "defaultLocale", "invalid_locale", "defaultLocale is required");
      localeCenterDefaultLocale = defaultLocale;
      localeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_LOCALE_DEFAULT_SET", "LOCALE", defaultLocale, null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("defaultLocale", localeCenterDefaultLocale);
      writeJson(exchange, 200, response);
    }

    private void handleAdminLocalesAction(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String locale = readNonBlank(payload, "locale", "invalid_locale", "locale is required");
      String action = readNonBlank(payload, "action", "invalid_action", "action is required");
      LocaleCenterRecord existing = findLocaleRecord(locale);
      if (existing == null) {
        throw new ServiceException(404, "locale_missing", "locale not found");
      }
      LocaleCenterRecord next = switch (action) {
        case "toggleWeb" -> existing.withWebEnabled(!existing.webEnabled());
        case "toggleGame" -> existing.withGameEnabled(!existing.gameEnabled());
        case "remove" -> null;
        default -> throw new ServiceException(400, "invalid_action", "unsupported locale action");
      };
      if (next == null) {
        if (existing.builtIn()) {
          throw new ServiceException(409, "built_in_locale", "built-in locale cannot be removed");
        }
        localeCenterRecords.removeIf(item -> item.locale().equalsIgnoreCase(locale));
        removeUploadedLocaleAssets(locale);
      } else {
        upsertLocaleRecord(next.withUpdatedAt(Instant.now().toString()));
      }
      localeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_LOCALE_ACTION", "LOCALE", locale + ":" + action, null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("state", buildLocaleCenterStateJson());
      writeJson(exchange, 200, response);
    }

    private void handleAdminThemesUpload(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String fileName = readNonBlank(payload, "fileName", "invalid_file_name", "fileName is required");
      String themeId = inferThemeIdFromFileName(fileName, "default");
      String contentBase64 = optionalString(payload, "contentBase64");
      int importedAssets = importThemePackageAssets(contentBase64, themeId);
      upsertThemeRecord(new ThemeCenterRecord(
          themeId,
          themeId,
          "upload",
          "uploaded-" + Instant.now().toEpochMilli(),
          "published",
          true,
          false,
          Instant.now().toString()));
      themeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_THEME_UPLOAD", "THEME", themeId, null);
      JsonObject response = new JsonObject();
      response.add("state", buildThemeCenterStateJson());
      JsonArray changed = new JsonArray();
      changed.add(themeId);
      response.add("changed", changed);
      response.addProperty("importedAssets", importedAssets);
      writeJson(exchange, 200, response);
    }

    private void handleAdminThemesSyncManifest(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonArray themes = payload.has("themes") && payload.get("themes").isJsonArray()
          ? payload.getAsJsonArray("themes")
          : new JsonArray();
      int succeeded = 0;
      for (JsonElement element : themes) {
        if (element == null || element.isJsonNull()) {
          continue;
        }
        String themeId = normalizeOptionalText(element.getAsString());
        if (themeId == null) {
          continue;
        }
        upsertThemeRecord(new ThemeCenterRecord(
            themeId,
            themeId,
            "github",
            "manifest-" + Instant.now().toEpochMilli(),
            "published",
            true,
            false,
            Instant.now().toString()));
        succeeded++;
      }
      themeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_THEME_MANIFEST_SYNC", "THEME", Integer.toString(succeeded), null);
      JsonObject response = new JsonObject();
      response.add("state", buildThemeCenterStateJson());
      response.addProperty("succeeded", succeeded);
      response.addProperty("failed", 0);
      writeJson(exchange, 200, response);
    }

    private void handleAdminThemesDefault(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String defaultTheme = readNonBlank(payload, "defaultTheme", "invalid_theme_id", "defaultTheme is required");
      themeCenterDefaultTheme = defaultTheme;
      themeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_THEME_DEFAULT_SET", "THEME", defaultTheme, null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("defaultTheme", themeCenterDefaultTheme);
      writeJson(exchange, 200, response);
    }

    private void handleAdminThemesAction(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String themeId = readNonBlank(payload, "themeId", "invalid_theme_id", "themeId is required");
      String action = readNonBlank(payload, "action", "invalid_action", "action is required");
      ThemeCenterRecord existing = findThemeRecord(themeId);
      if (existing == null) {
        throw new ServiceException(404, "theme_missing", "theme not found");
      }
      ThemeCenterRecord next = switch (action) {
        case "toggleWeb" -> existing.withWebEnabled(!existing.webEnabled());
        case "remove" -> null;
        default -> throw new ServiceException(400, "invalid_action", "unsupported theme action");
      };
      if (next == null) {
        if (existing.builtIn()) {
          throw new ServiceException(409, "built_in_theme", "built-in theme cannot be removed");
        }
        themeCenterRecords.removeIf(item -> item.themeId().equalsIgnoreCase(themeId));
        removeUploadedThemeAssets(themeId);
      } else {
        upsertThemeRecord(next.withUpdatedAt(Instant.now().toString()));
      }
      themeCenterLastSyncAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_THEME_ACTION", "THEME", themeId + ":" + action, null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("state", buildThemeCenterStateJson());
      writeJson(exchange, 200, response);
    }

    private void handleAdminProductsUpsert(HttpExchange exchange) throws Exception {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String sku = readNonBlank(payload, "sku", "invalid_sku", "sku is required");
      String title = readNonBlank(payload, "title", "invalid_title", "title is required");
      String currency = normalizeCurrency(readNonBlank(payload, "currency", "invalid_currency", "currency is required"));
      long price = Math.max(0L, readLong(payload, "price", "invalid_price", "price is required"));
      boolean active = !payload.has("active") || payload.get("active").getAsBoolean();

      long productId;
      String now = Instant.now().toString();
      try (Connection connection = state.connection()) {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO m2_products (sku, title, currency, price, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sku) DO UPDATE SET
              title = excluded.title,
              currency = excluded.currency,
              price = excluded.price,
              active = excluded.active,
              updated_at = excluded.updated_at
            """)) {
          statement.setString(1, sku);
          statement.setString(2, title);
          statement.setString(3, currency);
          statement.setLong(4, price);
          statement.setInt(5, active ? 1 : 0);
          statement.setString(6, now);
          statement.setString(7, now);
          statement.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT id FROM m2_products WHERE sku = ? LIMIT 1")) {
          query.setString(1, sku);
          try (ResultSet resultSet = query.executeQuery()) {
            if (!resultSet.next()) {
              throw new ServiceException(500, "product_lookup_failed", "failed to read saved product");
            }
            productId = resultSet.getLong("id");
          }
        }
      }

      ProductAdminMeta existingMeta = productAdminMeta.getOrDefault(productId, ProductAdminMeta.defaults());
      ProductAdminMeta nextMeta = ProductAdminMeta.fromPayload(payload, existingMeta, now);
      productAdminMeta.put(productId, nextMeta);
      recordAudit(adminSession, "ADMIN_PRODUCT_UPSERT", "PRODUCT", Long.toString(productId), sku);

      JsonObject response = new JsonObject();
      response.addProperty("id", productId);
      response.addProperty("sku", sku);
      response.addProperty("title", title);
      response.addProperty("currency", currency);
      response.addProperty("price", price);
      response.addProperty("active", active);
      response.addProperty("displayIconPath", nextMeta.displayIconPath());
      writeJson(exchange, 200, response);
    }

    private void handleAdminProductsIcon(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      long productId = parsePositiveLong(query.get("productId"), "invalid_product_id", "productId is required");
      String fileName = normalizeOptionalText(query.get("filename"));
      if (fileName == null) {
        fileName = "product-" + productId + ".png";
      }
      ProductAdminMeta existing = productAdminMeta.getOrDefault(productId, ProductAdminMeta.defaults());
      String iconPath = "uploads/products/" + productId + "/" + fileName;
      byte[] iconBytes = exchange.getRequestBody().readAllBytes();
      if (iconBytes.length > 0) {
        uploadedAssets.put(iconPath, iconBytes);
        String contentType = normalizeOptionalText(exchange.getRequestHeaders().getFirst("Content-Type"));
        uploadedAssetContentTypes.put(iconPath, contentType == null ? "application/octet-stream" : contentType);
      }
      ProductAdminMeta next = existing.withDisplayIconPath(iconPath).withUpdatedAt(Instant.now().toString());
      productAdminMeta.put(productId, next);
      recordAudit(adminSession, "ADMIN_PRODUCT_ICON_UPLOAD", "PRODUCT", Long.toString(productId), iconPath);
      JsonObject response = new JsonObject();
      response.addProperty("id", productId);
      response.addProperty("displayIconPath", iconPath);
      writeJson(exchange, 200, response);
    }

    private void handleAdminProductsResetLimit(HttpExchange exchange) throws Exception {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long productId = readPositiveLong(payload, "productId", "invalid_product_id", "productId must be positive");
      String sku = "UNKNOWN";
      try (Connection connection = state.connection();
           PreparedStatement query = connection.prepareStatement("SELECT sku FROM m2_products WHERE id = ? LIMIT 1")) {
        query.setLong(1, productId);
        try (ResultSet resultSet = query.executeQuery()) {
          if (resultSet.next()) {
            sku = resultSet.getString("sku");
          }
        }
      }
      recordAudit(adminSession, "ADMIN_PRODUCT_RESET_LIMIT", "PRODUCT", Long.toString(productId), sku);
      JsonObject response = new JsonObject();
      response.addProperty("sku", sku);
      response.addProperty("resetCount", 0);
      writeJson(exchange, 200, response);
    }

    private void handleAdminProductsActive(HttpExchange exchange) throws Exception {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long productId = readPositiveLong(payload, "productId", "invalid_product_id", "productId must be positive");
      boolean active = !payload.has("active") || payload.get("active").getAsBoolean();
      try (Connection connection = state.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE m2_products SET active = ?, updated_at = ? WHERE id = ?")) {
        statement.setInt(1, active ? 1 : 0);
        statement.setString(2, Instant.now().toString());
        statement.setLong(3, productId);
        int affected = statement.executeUpdate();
        if (affected <= 0) {
          throw new ServiceException(404, "product_missing", "product not found");
        }
      }
      recordAudit(adminSession, "ADMIN_PRODUCT_ACTIVE", "PRODUCT", Long.toString(productId), Boolean.toString(active));
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("productId", productId);
      response.addProperty("active", active);
      writeJson(exchange, 200, response);
    }

    private void handleAdminGroupBuyConsume(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String code = readNonBlank(payload, "code", "invalid_code", "code is required");
      String consumedAt = Instant.now().toString();
      recordAudit(adminSession, "ADMIN_GROUP_BUY_CONSUME", "GROUP_BUY_CODE", code, null);
      JsonObject response = new JsonObject();
      response.addProperty("code", code);
      response.addProperty("orderNo", "M2-GB-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase());
      response.addProperty("username", adminSession.username());
      response.addProperty("consumedAt", consumedAt);
      writeJson(exchange, 200, response);
    }

    private void handleAdminRedeemList(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      JsonArray codes = new JsonArray();
      for (RedeemCodeRecord record : redeemCodes) {
        JsonObject row = new JsonObject();
        row.addProperty("code", record.code());
        row.addProperty("shopCoin", record.shopCoin());
        row.addProperty("gameCoin", record.gameCoin());
        row.addProperty("usedCount", record.usedCount());
        row.addProperty("maxUses", record.maxUses());
        row.addProperty("perUserMaxUses", record.perUserMaxUses());
        row.addProperty("expiresAt", record.expiresAt());
        row.addProperty("active", record.active());
        codes.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("codes", codes);
      writeJson(exchange, 200, response);
    }

    private void handleAdminRedeemCreate(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long shopCoin = Math.max(0L, readLong(payload, "shopCoin", "invalid_amount", "shopCoin is required"));
      long gameCoin = Math.max(0L, readLong(payload, "gameCoin", "invalid_amount", "gameCoin is required"));
      long maxUses = Math.max(1L, readLong(payload, "maxUses", "invalid_max_uses", "maxUses is required"));
      long perUserMaxUses = Math.max(1L, readLong(payload, "perUserMaxUses", "invalid_max_uses", "perUserMaxUses is required"));
      String customCode = normalizeOptionalText(optionalString(payload, "customCode"));
      String code = customCode != null ? customCode : "M2R-" + Long.toString(++redeemSequence, 36).toUpperCase();
      String expiresAt = normalizeOptionalText(optionalString(payload, "expiresAt"));
      redeemCodes.add(new RedeemCodeRecord(code, shopCoin, gameCoin, 0L, maxUses, perUserMaxUses, expiresAt, true));
      recordAudit(adminSession, "ADMIN_REDEEM_CREATE", "REDEEM_CODE", code, null);

      JsonObject response = new JsonObject();
      response.addProperty("code", code);
      response.addProperty("shopCoin", shopCoin);
      response.addProperty("gameCoin", gameCoin);
      response.addProperty("maxUses", maxUses);
      response.addProperty("perUserMaxUses", perUserMaxUses);
      response.addProperty("expiresAt", expiresAt);
      response.addProperty("active", true);
      writeJson(exchange, 200, response);
    }

    private void handleAdminMarketTagsConfigGet(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      JsonObject response = new JsonObject();
      response.add("config", cloneJsonObject(marketTagsConfig));
      writeJson(exchange, 200, response);
    }

    private void handleAdminMarketTagsConfigPost(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject config = payload.has("config") && payload.get("config").isJsonObject()
          ? payload.getAsJsonObject("config")
          : new JsonObject();
      marketTagsConfig = cloneJsonObject(config);
      economyRuntimeSettings.add("marketTagsConfig", cloneJsonObject(marketTagsConfig));
      recordAudit(adminSession, "ADMIN_MARKET_TAGS_CONFIG_SAVE", "SYSTEM", "market-tags-config", null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("config", cloneJsonObject(marketTagsConfig));
      writeJson(exchange, 200, response);
    }

    private void handleAdminMarketLimitationConfigGet(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      JsonObject response = new JsonObject();
      response.add("config", cloneJsonObject(marketLimitationConfig));
      writeJson(exchange, 200, response);
    }

    private void handleAdminMarketLimitationConfigPost(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject config = payload.has("config") && payload.get("config").isJsonObject()
          ? payload.getAsJsonObject("config")
          : new JsonObject();
      marketLimitationConfig = cloneJsonObject(config);
      economyRuntimeSettings.add("marketLimitationConfig", cloneJsonObject(marketLimitationConfig));
      recordAudit(adminSession, "ADMIN_MARKET_LIMITATION_CONFIG_SAVE", "SYSTEM", "market-limitation-config", null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("config", cloneJsonObject(marketLimitationConfig));
      writeJson(exchange, 200, response);
    }

    private void handleAdminMarketListings(HttpExchange exchange) throws Exception {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      int limit = parseLimit(query.get("limit"), 200, 1, 500);
      String statusFilter = normalizeOptionalText(query.get("status"));
      String currencyFilter = normalizeOptionalText(query.get("currency"));
      String keywordFilter = normalizeOptionalText(query.get("keyword"));

      try (Connection connection = state.connection()) {
        StringBuilder sql = new StringBuilder("""
            SELECT o.id, o.created_at, o.status, o.currency, o.total_amount, o.quantity,
                   u.username, p.sku, p.title
            FROM m2_orders o
            JOIN m2_users u ON u.id = o.user_id
            JOIN m2_products p ON p.id = o.product_id
            ORDER BY o.id DESC
            LIMIT ?
            """);
        JsonArray listings = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
          statement.setInt(1, limit);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              long listingId = resultSet.getLong("id");
              String listingStatus = marketListingStatusOverrides.getOrDefault(listingId, "SOLD");
              if (statusFilter != null && !listingStatus.equalsIgnoreCase(statusFilter)) {
                continue;
              }
              String currency = resultSet.getString("currency");
              if (currencyFilter != null && !currency.equalsIgnoreCase(currencyFilter)) {
                continue;
              }
              String sellerName = resultSet.getString("username");
              String sku = resultSet.getString("sku");
              String title = resultSet.getString("title");
              if (keywordFilter != null) {
                String haystack = (sellerName + " " + sku + " " + title + " " + listingId).toLowerCase();
                if (!haystack.contains(keywordFilter.toLowerCase())) {
                  continue;
                }
              }
              JsonObject row = new JsonObject();
              row.addProperty("id", listingId);
              row.addProperty("status", listingStatus);
              row.addProperty("createdAt", resultSet.getString("created_at"));
              row.addProperty("soldAt", "SOLD".equals(listingStatus) ? resultSet.getString("created_at") : nullString());
              row.addProperty("unlistedAt", marketListingUnlistedAt.getOrDefault(listingId, nullString()));
              row.addProperty("currency", currency);
              row.addProperty("price", resultSet.getLong("total_amount"));
              row.addProperty("quantity", resultSet.getInt("quantity"));
              row.addProperty("sellerName", sellerName);
              row.addProperty("sellerUuid", boundUuidForUser(findUserIdByUsername(connection, sellerName)));
              row.addProperty("buyerName", "SOLD".equals(listingStatus) ? sellerName : nullString());
              row.addProperty("buyerUuid", "SOLD".equals(listingStatus) ? boundUuidForUser(findUserIdByUsername(connection, sellerName)) : nullString());
              row.addProperty("itemMaterial", "DIAMOND");
              row.addProperty("displayNameOverride", title);
              row.addProperty("displayMaterial", "DIAMOND");
              row.addProperty("displayIconPath", (String) null);
              row.addProperty("remark", "");
              listings.add(row);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("listings", listings);
        writeJson(exchange, 200, response);
      }
    }

    private void handleAdminMarketUnlist(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long listingId = readPositiveLong(payload, "listingId", "invalid_listing_id", "listingId must be positive");
      marketListingStatusOverrides.put(listingId, "UNLISTED");
      marketListingUnlistedAt.put(listingId, Instant.now().toString());
      recordAudit(adminSession, "ADMIN_MARKET_UNLIST", "MARKET_LISTING", Long.toString(listingId), null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("listingId", listingId);
      writeJson(exchange, 200, response);
    }

    private void handleAdminUsersList(HttpExchange exchange) throws Exception {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      int limit = parseLimit(query.get("limit"), 120, 1, 500);
      String keyword = normalizeOptionalText(query.get("keyword"));
      try (Connection connection = state.connection()) {
        JsonArray users = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT u.id, u.username, u.created_at, w.shop_coin, w.game_coin
            FROM m2_users u
            LEFT JOIN m2_wallets w ON w.user_id = u.id
            ORDER BY u.id DESC
            LIMIT ?
            """)) {
          statement.setInt(1, limit);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              long userId = resultSet.getLong("id");
              String username = resultSet.getString("username");
              if (keyword != null) {
                String haystack = (username + " " + userId).toLowerCase();
                if (!haystack.contains(keyword.toLowerCase())) {
                  continue;
                }
              }
              JsonObject row = new JsonObject();
              row.addProperty("id", userId);
              row.addProperty("username", username);
              row.addProperty("boundUuid", boundUuidForUser(userId));
              row.addProperty("authState", userAuthStates.getOrDefault(userId, "ACTIVE"));
              row.addProperty("shopCoin", resultSet.getLong("shop_coin"));
              row.addProperty("gameCoin", resultSet.getLong("game_coin"));
              row.addProperty("createdAt", resultSet.getString("created_at"));
              users.add(row);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("users", users);
        writeJson(exchange, 200, response);
      }
    }

    private void handleAdminUserLookup(HttpExchange exchange) throws Exception {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String identifier = normalizeOptionalText(query.get("identifier"));
      if (identifier == null) {
        throw new ServiceException(400, "invalid_identifier", "identifier is required");
      }
      try (Connection connection = state.connection()) {
        LookupUserRow row = lookupUserByIdentifier(connection, identifier);
        if (row == null) {
          throw new ServiceException(404, "user_missing", "user not found");
        }
        JsonObject response = buildAdminUserJson(connection, row.userId(), row.username(), row.createdAt());
        writeJson(exchange, 200, response);
      }
    }

    private void handleAdminUserVisualPermissionGet(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      long userId = parsePositiveLong(query.get("userId"), "invalid_user_id", "userId must be positive");
      UserVisualPermission permission = userVisualPermissions.computeIfAbsent(userId, ignored -> UserVisualPermission.defaults());
      JsonObject response = buildUserVisualPermissionJson(permission);
      writeJson(exchange, 200, response);
    }

    private void handleAdminUserVisualPermissionPost(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user_id", "userId must be positive");
      UserVisualPermission next = UserVisualPermission.fromPayload(payload);
      userVisualPermissions.put(userId, next);
      recordAudit(adminSession, "ADMIN_USER_VISUAL_PERMISSION_SAVE", "USER", Long.toString(userId), null);
      JsonObject response = buildUserVisualPermissionJson(next);
      writeJson(exchange, 200, response);
    }

    private void handleAdminUserLogout(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user_id", "userId must be positive");
      userAuthStates.put(userId, "OFFLINE");
      recordAudit(adminSession, "ADMIN_USER_LOGOUT", "USER", Long.toString(userId), null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      writeJson(exchange, 200, response);
    }

    private void handleAdminUserUnbind(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user_id", "userId must be positive");
      userBoundUuidOverrides.put(userId, "");
      recordAudit(adminSession, "ADMIN_USER_UNBIND", "USER", Long.toString(userId), null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      writeJson(exchange, 200, response);
    }

    private void handleAdminUserResetPassword(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user_id", "userId must be positive");
      readNonBlank(payload, "newPassword", "invalid_password", "newPassword is required");
      recordAudit(adminSession, "ADMIN_USER_RESET_PASSWORD", "USER", Long.toString(userId), null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      writeJson(exchange, 200, response);
    }

    private void handleAdminUserWalletAdjust(HttpExchange exchange) throws Exception {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user_id", "userId must be positive");
      String currency = normalizeCurrency(readNonBlank(payload, "currency", "invalid_currency", "currency is required"));
      long delta = readLong(payload, "delta", "invalid_delta", "delta is required");
      if (delta == 0) {
        throw new ServiceException(400, "invalid_delta", "delta cannot be zero");
      }
      String bizId = "admin-wallet-adjust-" + UUID.randomUUID().toString().replace("-", "");
      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          requireUser(connection, userId);
          ensureWallet(connection, userId);
          insertLedgerEntry(connection, userId, currency, delta, "ADMIN_ADJUST", bizId);
          long balance = readCurrencyBalanceForUpdate(connection, userId, currency);
          long next = Math.addExact(balance, delta);
          if (next < 0) {
            throw new ServiceException(409, "insufficient_balance", "insufficient wallet balance");
          }
          updateCurrencyBalance(connection, userId, currency, next);
          connection.commit();
          recordAudit(adminSession, "ADMIN_USER_WALLET_ADJUST", "USER", Long.toString(userId), currency + ":" + delta);
          JsonObject user = buildAdminUserJson(connection, userId, findUsernameByUserId(connection, userId), null);
          writeJson(exchange, 200, user);
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }
    }

    private void handleAdminMaterialOverridesList(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String keyword = normalizeOptionalText(query.get("keyword"));
      JsonArray overrides = new JsonArray();
      for (MaterialOverrideRecord record : materialOverrides.values()) {
        if (keyword != null) {
          String haystack = (record.materialKey() + " " + record.displayNameOverride() + " " + record.iconPath()).toLowerCase();
          if (!haystack.contains(keyword.toLowerCase())) {
            continue;
          }
        }
        overrides.add(materialOverrideToJson(record));
      }
      JsonObject response = new JsonObject();
      response.add("overrides", overrides);
      writeJson(exchange, 200, response);
    }

    private void handleAdminMaterialOverridesUpsert(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String materialKey = normalizeMaterialKey(readNonBlank(payload, "materialKey", "invalid_material", "materialKey is required"));
      if (materialKey == null || materialKey.isBlank()) {
        throw new ServiceException(400, "invalid_material", "materialKey is invalid");
      }
      String displayNameOverride = normalizeOptionalText(optionalString(payload, "displayNameOverride"));
      String iconPath = normalizeOptionalText(optionalString(payload, "iconPath"));
      MaterialOverrideRecord existing = materialOverrides.get(materialKey);
      String now = Instant.now().toString();
      MaterialOverrideRecord next = new MaterialOverrideRecord(
          materialKey,
          displayNameOverride,
          iconPath != null ? iconPath : existing == null ? null : existing.iconPath(),
          adminSession.username(),
          now);
      materialOverrides.put(materialKey, next);
      recordAudit(adminSession, "ADMIN_MATERIAL_OVERRIDE_UPSERT", "MATERIAL", materialKey, null);
      writeJson(exchange, 200, materialOverrideToJson(next));
    }

    private void handleAdminMaterialOverridesIcon(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String materialKey = normalizeMaterialKey(query.get("material"));
      if (materialKey == null || materialKey.isBlank()) {
        throw new ServiceException(400, "invalid_material", "material query is required");
      }
      String fileName = normalizeOptionalText(query.get("filename"));
      if (fileName == null) {
        fileName = materialKey + ".png";
      }
      MaterialOverrideRecord existing = materialOverrides.get(materialKey);
      String iconPath = "uploads/materials/" + materialKey + "/" + fileName;
      byte[] iconBytes = exchange.getRequestBody().readAllBytes();
      if (iconBytes.length > 0) {
        uploadedAssets.put(iconPath, iconBytes);
        String contentType = normalizeOptionalText(exchange.getRequestHeaders().getFirst("Content-Type"));
        uploadedAssetContentTypes.put(iconPath, contentType == null ? "application/octet-stream" : contentType);
      }
      MaterialOverrideRecord next = new MaterialOverrideRecord(
          materialKey,
          existing == null ? null : existing.displayNameOverride(),
          iconPath,
          adminSession.username(),
          Instant.now().toString());
      materialOverrides.put(materialKey, next);
      recordAudit(adminSession, "ADMIN_MATERIAL_OVERRIDE_ICON", "MATERIAL", materialKey, iconPath);
      writeJson(exchange, 200, materialOverrideToJson(next));
    }

    private void handleAdminMaterialOverridesDelete(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String materialKey = normalizeMaterialKey(readNonBlank(payload, "materialKey", "invalid_material", "materialKey is required"));
      if (materialKey == null || materialKey.isBlank()) {
        throw new ServiceException(400, "invalid_material", "materialKey is invalid");
      }
      materialOverrides.remove(materialKey);
      recordAudit(adminSession, "ADMIN_MATERIAL_OVERRIDE_DELETE", "MATERIAL", materialKey, null);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("materialKey", materialKey);
      writeJson(exchange, 200, response);
    }

    private void handleAdminEconomyExchange(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject exchangeConfig = getOrCreateObject(economyRuntimeSettings, "exchange");
      JsonObject shopToGame = getOrCreateObject(exchangeConfig, "shopToGame");
      JsonObject gameToShop = getOrCreateObject(exchangeConfig, "gameToShop");
      shopToGame.addProperty("enabled", payload.has("shopToGameEnabled") && payload.get("shopToGameEnabled").getAsBoolean());
      shopToGame.addProperty("ratio", payload.has("shopToGameRatio") ? payload.get("shopToGameRatio").getAsDouble() : DEFAULT_SHOP_TO_GAME_RATIO);
      gameToShop.addProperty("enabled", payload.has("gameToShopEnabled") && payload.get("gameToShopEnabled").getAsBoolean());
      gameToShop.addProperty("ratio", payload.has("gameToShopRatio") ? payload.get("gameToShopRatio").getAsDouble() : DEFAULT_GAME_TO_SHOP_RATIO);
      recordAudit(adminSession, "ADMIN_ECONOMY_EXCHANGE_SAVE", "SYSTEM", "exchange", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminEconomyMarket(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject market = getOrCreateObject(economyRuntimeSettings, "market");
      market.addProperty("tradeFeePercent", payload.has("tradeFeePercent") ? payload.get("tradeFeePercent").getAsDouble() : 0.0d);
      market.addProperty("tradeTaxPercent", payload.has("tradeTaxPercent") ? payload.get("tradeTaxPercent").getAsDouble() : 0.0d);
      JsonObject inflation = getOrCreateObject(economyRuntimeSettings, "inflation");
      inflation.addProperty("mode", payload.has("inflationMode") ? payload.get("inflationMode").getAsString() : "BURN");
      inflation.addProperty("treasuryUserId", payload.has("inflationTreasuryUserId") ? payload.get("inflationTreasuryUserId").getAsLong() : 0L);
      recordAudit(adminSession, "ADMIN_ECONOMY_MARKET_SAVE", "SYSTEM", "market", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminEconomyLeaderboard(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject leaderboard = getOrCreateObject(economyRuntimeSettings, "leaderboard");
      leaderboard.addProperty("enabled", !payload.has("enabled") || payload.get("enabled").getAsBoolean());
      leaderboard.addProperty("showOnlineStatus", !payload.has("showOnlineStatus") || payload.get("showOnlineStatus").getAsBoolean());
      leaderboard.addProperty("defaultMetric", payload.has("defaultMetric") ? payload.get("defaultMetric").getAsString() : "GAME_COIN");
      leaderboard.addProperty("defaultOrder", payload.has("defaultOrder") ? payload.get("defaultOrder").getAsString() : "DESC");
      recordAudit(adminSession, "ADMIN_ECONOMY_LEADERBOARD_SAVE", "SYSTEM", "leaderboard", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminEconomyCurrency(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject currency = getOrCreateObject(economyRuntimeSettings, "currency");
      currency.addProperty("shopCoinName", readNonBlank(payload, "shopCoinName", "invalid_currency_name", "shopCoinName is required"));
      currency.addProperty("shopCoinShort", readNonBlank(payload, "shopCoinShort", "invalid_currency_name", "shopCoinShort is required"));
      currency.addProperty("gameCoinName", readNonBlank(payload, "gameCoinName", "invalid_currency_name", "gameCoinName is required"));
      currency.addProperty("gameCoinShort", readNonBlank(payload, "gameCoinShort", "invalid_currency_name", "gameCoinShort is required"));
      recordAudit(adminSession, "ADMIN_ECONOMY_CURRENCY_SAVE", "SYSTEM", "currency", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminSystemWebshop(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject runtime = getOrCreateObject(economyRuntimeSettings, "webshopRuntime");
      copyJsonProperties(payload, runtime, List.of(
          "defaultLocale",
          "timeZone",
          "sessionExpireHours",
          "bindRequestExpireMinutes",
          "accessTokenLength",
          "deliveryBatchSize",
          "deliveryRetrySeconds",
          "orderCooldownSeconds",
          "allowSharedClaimCommand",
          "refundUndeliveredEnabled"));
      recordAudit(adminSession, "ADMIN_SYSTEM_WEBSHOP_SAVE", "SYSTEM", "webshop", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminSystemMarket(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject marketRuntime = getOrCreateObject(economyRuntimeSettings, "marketRuntime");
      marketRuntime.addProperty("marketMaxActiveListings", payload.has("marketMaxActiveListings") ? payload.get("marketMaxActiveListings").getAsInt() : 10);
      JsonObject supply = getOrCreateObject(marketRuntime, "supply");
      supply.addProperty("autoRefreshThreshold", payload.has("autoRefreshThreshold") ? payload.get("autoRefreshThreshold").getAsInt() : 8);
      supply.addProperty("defaultTransferBatchSize", payload.has("defaultTransferBatchSize") ? payload.get("defaultTransferBatchSize").getAsInt() : 64);
      supply.addProperty("maxTransferBatchSize", payload.has("maxTransferBatchSize") ? payload.get("maxTransferBatchSize").getAsInt() : 256);
      supply.addProperty("defaultTransitStock", payload.has("defaultTransitStock") ? payload.get("defaultTransitStock").getAsInt() : 256);
      supply.addProperty("maxTransitStock", payload.has("maxTransitStock") ? payload.get("maxTransitStock").getAsInt() : 1024);
      recordAudit(adminSession, "ADMIN_SYSTEM_MARKET_SAVE", "SYSTEM", "market-runtime", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminSystemMaintenance(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject maintenance = getOrCreateObject(economyRuntimeSettings, "maintenance");
      copyJsonProperties(payload, maintenance, List.of(
          "cleanupIntervalMinutes",
          "pendingBindRetentionHours",
          "pendingPasswordRetentionHours",
          "bindRequestRetentionHours",
          "redeemCodeRetentionDays"));
      recordAudit(adminSession, "ADMIN_SYSTEM_MAINTENANCE_SAVE", "SYSTEM", "maintenance", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminSystemLogging(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject logging = getOrCreateObject(economyRuntimeSettings, "logging");
      copyJsonProperties(payload, logging, List.of(
          "enabled",
          "level",
          "directory",
          "maxFileSizeMb",
          "maxFiles",
          "retentionDays"));
      recordAudit(adminSession, "ADMIN_SYSTEM_LOGGING_SAVE", "SYSTEM", "logging", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminSystemNotification(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject notification = getOrCreateObject(economyRuntimeSettings, "notification");
      copyJsonProperties(payload, notification, List.of(
          "marketEventsEnabled",
          "deliveryMailboxEventsEnabled"));
      if (payload.has("templates") && payload.get("templates").isJsonObject()) {
        notification.add("templates", cloneJsonObject(payload.getAsJsonObject("templates")));
      }
      recordAudit(adminSession, "ADMIN_SYSTEM_NOTIFICATION_SAVE", "SYSTEM", "notification", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminNotificationsAnnounce(HttpExchange exchange) throws Exception {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String title = readNonBlank(payload, "title", "invalid_title", "title is required");
      String content = readNonBlank(payload, "content", "invalid_content", "content is required");
      int delivered = 0;
      try (Connection connection = state.connection();
           PreparedStatement statement = connection.prepareStatement("SELECT COUNT(1) FROM m2_users")) {
        try (ResultSet resultSet = statement.executeQuery()) {
          delivered = resultSet.next() ? resultSet.getInt(1) : 0;
        }
      }
      recordAudit(adminSession, "ADMIN_NOTIFICATION_ANNOUNCE", "ANNOUNCEMENT", title, content);
      JsonObject response = new JsonObject();
      response.addProperty("delivered", delivered);
      writeJson(exchange, 200, response);
    }

    private void handleAdminSystemBroadcast(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject broadcast = getOrCreateObject(economyRuntimeSettings, "broadcast");
      broadcast.addProperty("enabled", !payload.has("enabled") || payload.get("enabled").getAsBoolean());
      JsonObject templates = getOrCreateObject(broadcast, "templates");
      templates.addProperty("listing-created", optionalString(payload, "listingCreatedTemplate") == null ? "" : optionalString(payload, "listingCreatedTemplate"));
      templates.addProperty("trade-success", optionalString(payload, "tradeSuccessTemplate") == null ? "" : optionalString(payload, "tradeSuccessTemplate"));
      templates.addProperty("auction-bid", optionalString(payload, "auctionBidTemplate") == null ? "" : optionalString(payload, "auctionBidTemplate"));
      templates.addProperty("auction-sealed-bid", optionalString(payload, "auctionSealedBidTemplate") == null ? "" : optionalString(payload, "auctionSealedBidTemplate"));
      recordAudit(adminSession, "ADMIN_SYSTEM_BROADCAST_SAVE", "SYSTEM", "broadcast", null);
      writeJson(exchange, 200, okResponse());
    }

    private void handleAdminVisualSettings(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      JsonObject visual = getOrCreateObject(economyRuntimeSettings, "visual");
      copyJsonProperties(payload, visual, List.of(
          "globalCustomIconEnabled",
          "globalCustomNameEnabled",
          "officialProductCustomIconEnabled",
          "officialProductCustomNameEnabled",
          "officialProductUploadImageEnabled",
          "marketListingCustomIconEnabled",
          "marketListingCustomNameEnabled",
          "marketListingUploadImageEnabled",
          "iconPolicyMode",
          "namePolicyMode"));
      recordAudit(adminSession, "ADMIN_VISUAL_SETTINGS_SAVE", "SYSTEM", "visual", null);
      JsonObject response = new JsonObject();
      response.add("visual", cloneJsonObject(visual));
      writeJson(exchange, 200, response);
    }

    private void handleAdminManagersMeta(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      JsonObject response = new JsonObject();
      response.add("groups", buildAdminPermissionGroupsJson());
      response.add("templates", buildAdminPermissionTemplatesJson());
      writeJson(exchange, 200, response);
    }

    private void handleAdminManagersList(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      JsonArray admins = new JsonArray();
      for (AdminManagerRecord record : adminManagers.values()) {
        admins.add(buildAdminManagerJson(record));
      }
      JsonObject response = new JsonObject();
      response.add("admins", admins);
      writeJson(exchange, 200, response);
    }

    private void handleAdminManagersUpsert(HttpExchange exchange) throws Exception {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String identifier = readNonBlank(payload, "identifier", "invalid_identifier", "identifier is required");
      boolean isSuperAdmin = payload.has("isSuperAdmin") && payload.get("isSuperAdmin").getAsBoolean();
      String templateKey = normalizeOptionalText(optionalString(payload, "templateKey"));
      JsonArray permissionsRaw = payload.has("permissions") && payload.get("permissions").isJsonArray()
          ? payload.getAsJsonArray("permissions")
          : new JsonArray();
      List<String> permissions = new ArrayList<>();
      for (JsonElement element : permissionsRaw) {
        if (element == null || element.isJsonNull()) {
          continue;
        }
        String value = normalizeOptionalText(element.getAsString());
        if (value != null) {
          permissions.add(value);
        }
      }
      try (Connection connection = state.connection()) {
        LookupUserRow row = lookupUserByIdentifier(connection, identifier);
        if (row == null) {
          String username = identifier;
          try (PreparedStatement insert = connection.prepareStatement("""
              INSERT INTO m2_users (username, created_at)
              VALUES (?, ?)
              """)) {
            insert.setString(1, username);
            insert.setString(2, Instant.now().toString());
            insert.executeUpdate();
          }
          row = lookupUserByIdentifier(connection, identifier);
        }
        if (row == null) {
          throw new ServiceException(500, "admin_user_create_failed", "failed to resolve admin user");
        }
        AdminManagerRecord record = new AdminManagerRecord(
            row.userId(),
            row.username(),
            boundUuidForUser(row.userId()),
            isSuperAdmin,
            isSuperAdmin ? "SUPER_ADMIN" : "CUSTOM",
            isSuperAdmin ? List.of("*") : permissions,
            templateKey,
            true,
            Instant.now().toString());
        adminManagers.put(row.userId(), record);
        recordAudit(adminSession, "ADMIN_MANAGER_UPSERT", "ADMIN_USER", Long.toString(row.userId()), row.username());
        writeJson(exchange, 200, buildAdminManagerJson(record));
      }
    }

    private void handleAdminManagersActive(HttpExchange exchange) throws IOException {
      AdminSession adminSession = requireAdminSession(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user_id", "userId must be positive");
      boolean active = !payload.has("active") || payload.get("active").getAsBoolean();
      AdminManagerRecord existing = adminManagers.get(userId);
      if (existing == null) {
        throw new ServiceException(404, "admin_manager_missing", "admin manager not found");
      }
      AdminManagerRecord updated = new AdminManagerRecord(
          existing.userId(),
          existing.username(),
          existing.boundUuid(),
          existing.superAdmin(),
          existing.role(),
          existing.permissions(),
          existing.templateKey(),
          active,
          Instant.now().toString());
      adminManagers.put(userId, updated);
      recordAudit(adminSession, "ADMIN_MANAGER_ACTIVE", "ADMIN_USER", Long.toString(userId), Boolean.toString(active));
      writeJson(exchange, 200, buildAdminManagerJson(updated));
    }

    private void handleAdminAuditList(HttpExchange exchange) throws IOException {
      requireAdminSession(exchange);
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      int limit = parseLimit(query.get("limit"), 200, 1, 500);
      JsonArray logs = new JsonArray();
      int size = auditLogs.size();
      for (int index = size - 1; index >= 0 && logs.size() < limit; index--) {
        logs.add(buildAuditLogJson(auditLogs.get(index)));
      }
      JsonObject response = new JsonObject();
      response.add("logs", logs);
      writeJson(exchange, 200, response);
    }

    private void initializeLocaleThemeDefaults() {
      if (localeCenterRecords.isEmpty()) {
        String now = Instant.now().toString();
        localeCenterRecords.add(new LocaleCenterRecord(
            "zh-CN", "Chinese", "Chinese", "built-in", "m2-dev", "published", true, true, true, now));
        localeCenterRecords.add(new LocaleCenterRecord(
            "en-US", "English", "English", "built-in", "m2-dev", "published", true, true, true, now));
      }
      if (themeCenterRecords.isEmpty()) {
        themeCenterRecords.add(new ThemeCenterRecord(
            "default", "Default", "built-in", "m2-dev", "published", true, true, Instant.now().toString()));
      }
    }

    private JsonObject buildLocaleCenterStateJson() {
      JsonObject response = new JsonObject();
      response.addProperty("defaultLocale", localeCenterDefaultLocale);
      response.addProperty("lastSyncAt", localeCenterLastSyncAt);
      JsonArray locales = new JsonArray();
      for (LocaleCenterRecord record : localeCenterRecords) {
        JsonObject row = new JsonObject();
        row.addProperty("locale", record.locale());
        row.addProperty("name", record.name());
        row.addProperty("nativeName", record.nativeName());
        row.addProperty("source", record.source());
        row.addProperty("version", record.version());
        row.addProperty("status", record.status());
        row.addProperty("webEnabled", record.webEnabled());
        row.addProperty("gameEnabled", record.gameEnabled());
        row.addProperty("builtIn", record.builtIn());
        row.addProperty("updatedAt", record.updatedAt());
        locales.add(row);
      }
      response.add("locales", locales);
      return response;
    }

    private JsonObject buildThemeCenterStateJson() {
      JsonObject response = new JsonObject();
      response.addProperty("defaultTheme", themeCenterDefaultTheme);
      response.addProperty("lastSyncAt", themeCenterLastSyncAt);
      JsonArray themes = new JsonArray();
      for (ThemeCenterRecord record : themeCenterRecords) {
        JsonObject row = new JsonObject();
        row.addProperty("themeId", record.themeId());
        row.addProperty("name", record.name());
        row.addProperty("source", record.source());
        row.addProperty("version", record.version());
        row.addProperty("status", record.status());
        row.addProperty("webEnabled", record.webEnabled());
        row.addProperty("builtIn", record.builtIn());
        row.addProperty("updatedAt", record.updatedAt());
        themes.add(row);
      }
      response.add("themes", themes);
      return response;
    }

    private void upsertLocaleRecord(LocaleCenterRecord record) {
      localeCenterRecords.removeIf(item -> item.locale().equalsIgnoreCase(record.locale()));
      localeCenterRecords.add(record);
    }

    private LocaleCenterRecord findLocaleRecord(String locale) {
      if (locale == null) {
        return null;
      }
      for (LocaleCenterRecord record : localeCenterRecords) {
        if (record.locale().equalsIgnoreCase(locale)) {
          return record;
        }
      }
      return null;
    }

    private void upsertThemeRecord(ThemeCenterRecord record) {
      themeCenterRecords.removeIf(item -> item.themeId().equalsIgnoreCase(record.themeId()));
      themeCenterRecords.add(record);
    }

    private ThemeCenterRecord findThemeRecord(String themeId) {
      if (themeId == null) {
        return null;
      }
      for (ThemeCenterRecord record : themeCenterRecords) {
        if (record.themeId().equalsIgnoreCase(themeId)) {
          return record;
        }
      }
      return null;
    }

    private String inferLocaleFromFileName(String fileName, String fallback) {
      String raw = fileName == null ? "" : fileName.trim();
      if (raw.isBlank()) {
        return fallback;
      }
      String name = raw.replace('\\', '/');
      int slash = name.lastIndexOf('/');
      if (slash >= 0) {
        name = name.substring(slash + 1);
      }
      int dot = name.indexOf('.');
      if (dot > 0) {
        name = name.substring(0, dot);
      }
      String normalized = name.replace('_', '-').trim();
      return normalized.isBlank() ? fallback : normalized;
    }

    private String inferThemeIdFromFileName(String fileName, String fallback) {
      String raw = fileName == null ? "" : fileName.trim().toLowerCase();
      if (raw.isBlank()) {
        return fallback;
      }
      String name = raw.replace('\\', '/');
      int slash = name.lastIndexOf('/');
      if (slash >= 0) {
        name = name.substring(slash + 1);
      }
      int dot = name.indexOf('.');
      if (dot > 0) {
        name = name.substring(0, dot);
      }
      String normalized = name.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
      if (normalized.isBlank()) {
        return fallback;
      }
      return normalized;
    }

    private String normalizeThemeId(String raw) {
      String normalized = raw == null ? "" : raw.trim().toLowerCase();
      if (normalized.isBlank()) {
        return "default";
      }
      normalized = normalized.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
      return normalized.isBlank() ? "default" : normalized;
    }

    private String normalizeMaterialKey(String raw) {
      if (raw == null) {
        return null;
      }
      String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
      if (normalized.isBlank()) {
        return null;
      }
      return normalized;
    }

    private JsonObject materialOverrideToJson(MaterialOverrideRecord record) {
      JsonObject row = new JsonObject();
      row.addProperty("materialKey", record.materialKey());
      row.addProperty("displayNameOverride", record.displayNameOverride());
      row.addProperty("iconPath", record.iconPath());
      row.addProperty("updatedBy", record.updatedBy());
      row.addProperty("updatedAt", record.updatedAt());
      return row;
    }

    private JsonObject getOrCreateObject(JsonObject root, String key) {
      if (root.has(key) && root.get(key).isJsonObject()) {
        return root.getAsJsonObject(key);
      }
      JsonObject created = new JsonObject();
      root.add(key, created);
      return created;
    }

    private int importLocalePackageAssets(String contentBase64, String fallbackLocale) {
      String normalizedBase64 = normalizeOptionalText(contentBase64);
      if (normalizedBase64 == null) {
        return 0;
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(normalizedBase64);
      } catch (Exception ignored) {
        return 0;
      }
      int imported = 0;
      try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(decoded))) {
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
          if (entry.isDirectory()) {
            continue;
          }
          String entryName = entry.getName().replace('\\', '/');
          if (entryName.contains("..")) {
            continue;
          }
          String lowered = entryName.toLowerCase();
          if (!lowered.endsWith(".json")) {
            continue;
          }
          String assetPath = null;
          if (entryName.startsWith("web/i18n/")) {
            assetPath = entryName.substring("web/".length());
          } else if (entryName.startsWith("i18n/")) {
            assetPath = entryName;
          }
          if (assetPath == null) {
            continue;
          }
          byte[] bytes = readZipEntryBytes(zipInputStream);
          registerUploadedAsset(assetPath, bytes, "application/json; charset=utf-8");
          imported++;
        }
      } catch (Exception ignored) {
        imported = 0;
      }
      if (imported == 0) {
        // Fallback for plain JSON payloads: store as app namespace for inferred locale.
        String locale = inferLocaleFromFileName(fallbackLocale, fallbackLocale);
        if (locale == null || locale.isBlank()) {
          locale = "zh-CN";
        }
        String assetPath = "i18n/app/" + locale + ".json";
        registerUploadedAsset(assetPath, decoded, "application/json; charset=utf-8");
        imported = 1;
      }
      return imported;
    }

    private int importThemePackageAssets(String contentBase64, String defaultThemeId) {
      String normalizedBase64 = normalizeOptionalText(contentBase64);
      if (normalizedBase64 == null) {
        return 0;
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(normalizedBase64);
      } catch (Exception ignored) {
        return 0;
      }
      int imported = 0;
      try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(decoded))) {
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
          if (entry.isDirectory()) {
            continue;
          }
          String entryName = entry.getName().replace('\\', '/');
          if (entryName.contains("..")) {
            continue;
          }
          String lowered = entryName.toLowerCase();
          if (!lowered.endsWith("light.css") && !lowered.endsWith("dark.css")) {
            continue;
          }
          String variant = lowered.endsWith("light.css") ? "light.css" : "dark.css";
          String themeId = normalizeThemeId(defaultThemeId);
          String[] segments = entryName.split("/");
          if (segments.length >= 3 && "themes".equalsIgnoreCase(segments[0])) {
            themeId = normalizeThemeId(segments[1]);
          } else if (segments.length >= 2 && !segments[segments.length - 2].isBlank()) {
            themeId = normalizeThemeId(segments[segments.length - 2]);
          }
          byte[] bytes = readZipEntryBytes(zipInputStream);
          registerUploadedAsset("themes/" + themeId + "/" + variant, bytes, "text/css; charset=utf-8");
          imported++;
        }
      } catch (Exception ignored) {
        imported = 0;
      }
      if (imported == 0) {
        // Fallback for plain CSS uploads: treat payload as light theme css.
        String themeId = normalizeThemeId(defaultThemeId);
        registerUploadedAsset("themes/" + themeId + "/light.css", decoded, "text/css; charset=utf-8");
        imported = 1;
      }
      return imported;
    }

    private byte[] readZipEntryBytes(InputStream inputStream) throws IOException {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = inputStream.read(buffer)) > 0) {
        outputStream.write(buffer, 0, read);
      }
      return outputStream.toByteArray();
    }

    private void registerUploadedAsset(String path, byte[] bytes, String contentType) {
      if (path == null || bytes == null || bytes.length == 0) {
        return;
      }
      String normalized = path.replace('\\', '/');
      if (normalized.isBlank() || normalized.contains("..")) {
        return;
      }
      uploadedAssets.put(normalized, bytes);
      uploadedAssetContentTypes.put(normalized, contentType == null ? contentType(normalized) : contentType);
    }

    private void removeUploadedLocaleAssets(String locale) {
      String normalized = normalizeOptionalText(locale);
      if (normalized == null) {
        return;
      }
      List<String> keys = new ArrayList<>(uploadedAssets.keySet());
      String appKey = "i18n/app/" + normalized + ".json";
      String adminKey = "i18n/admin/" + normalized + ".json";
      for (String key : keys) {
        if (key.equalsIgnoreCase(appKey) || key.equalsIgnoreCase(adminKey)) {
          uploadedAssets.remove(key);
          uploadedAssetContentTypes.remove(key);
        }
      }
    }

    private void removeUploadedThemeAssets(String themeId) {
      String normalizedThemeId = normalizeThemeId(themeId);
      List<String> keys = new ArrayList<>(uploadedAssets.keySet());
      String prefix = "themes/" + normalizedThemeId + "/";
      for (String key : keys) {
        if (key.toLowerCase().startsWith(prefix.toLowerCase())) {
          uploadedAssets.remove(key);
          uploadedAssetContentTypes.remove(key);
        }
      }
    }

    private void copyJsonProperties(JsonObject source, JsonObject target, List<String> keys) {
      for (String key : keys) {
        if (source.has(key)) {
          target.add(key, source.get(key));
        }
      }
    }

    private JsonObject okResponse() {
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      return response;
    }

    private String normalizeOrderFilterStatus(String rawStatus) {
      String normalized = normalizeOptionalText(rawStatus);
      if (normalized == null) {
        return null;
      }
      return normalized.toUpperCase();
    }

    private String normalizeOptionalText(String raw) {
      if (raw == null) {
        return null;
      }
      String trimmed = raw.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }

    private JsonObject buildAdminUserJson(Connection connection, long userId, String username, String fallbackCreatedAt)
        throws SQLException {
      ensureWallet(connection, userId);
      JsonObject user = new JsonObject();
      user.addProperty("id", userId);
      user.addProperty("username", username);
      user.addProperty("boundUuid", boundUuidForUser(userId));
      user.addProperty("authState", userAuthStates.getOrDefault(userId, "ACTIVE"));
      user.addProperty("createdAt", fallbackCreatedAt != null ? fallbackCreatedAt : Instant.now().toString());
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT shop_coin, game_coin FROM m2_wallets WHERE user_id = ? LIMIT 1")) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            user.addProperty("shopCoin", resultSet.getLong("shop_coin"));
            user.addProperty("gameCoin", resultSet.getLong("game_coin"));
          } else {
            user.addProperty("shopCoin", 0L);
            user.addProperty("gameCoin", 0L);
          }
        }
      }
      return user;
    }

    private LookupUserRow lookupUserByIdentifier(Connection connection, String identifier) throws SQLException {
      String normalized = normalizeOptionalText(identifier);
      if (normalized == null) {
        return null;
      }
      if (normalized.chars().allMatch(Character::isDigit)) {
        long userId = Long.parseLong(normalized);
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, username, created_at FROM m2_users WHERE id = ? LIMIT 1")) {
          statement.setLong(1, userId);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
              return new LookupUserRow(resultSet.getLong("id"), resultSet.getString("username"), resultSet.getString("created_at"));
            }
          }
        }
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id, username, created_at FROM m2_users WHERE username = ? LIMIT 1")) {
        statement.setString(1, normalized);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            return new LookupUserRow(resultSet.getLong("id"), resultSet.getString("username"), resultSet.getString("created_at"));
          }
        }
      }
      return null;
    }

    private JsonObject buildUserVisualPermissionJson(UserVisualPermission permission) {
      JsonObject response = new JsonObject();
      response.addProperty("iconPermission", permission.iconPermission());
      response.addProperty("namePermission", permission.namePermission());
      response.addProperty("uploadPermission", permission.uploadPermission());
      response.addProperty("listingLimitOverride", permission.listingLimitOverride());
      response.addProperty("listingLimitEffective", permission.listingLimitOverride() != null ? permission.listingLimitOverride() : 10);
      response.addProperty("listingLimitSource", permission.listingLimitOverride() != null ? "USER_OVERRIDE" : "GLOBAL_DEFAULT");
      response.addProperty("permissionLimit", (String) null);
      response.addProperty("globalDefaultLimit", 10);
      response.addProperty("customIconAllowed", permission.isIconAllowed());
      response.addProperty("customNameAllowed", permission.isNameAllowed());
      response.addProperty("customUploadAllowed", permission.isUploadAllowed());
      return response;
    }

    private JsonObject buildAdminManagerJson(AdminManagerRecord record) {
      JsonObject response = new JsonObject();
      response.addProperty("userId", record.userId());
      response.addProperty("username", record.username());
      response.addProperty("boundUuid", record.boundUuid());
      response.addProperty("isSuperAdmin", record.superAdmin());
      response.addProperty("role", record.role());
      response.addProperty("templateKey", record.templateKey());
      response.addProperty("active", record.active());
      response.addProperty("updatedAt", record.updatedAt());
      JsonArray permissions = new JsonArray();
      for (String permission : record.permissions()) {
        permissions.add(permission);
      }
      response.add("permissions", permissions);
      return response;
    }

    private JsonArray buildAdminPermissionGroupsJson() {
      JsonArray groups = new JsonArray();
      groups.add(buildPermissionGroup("orders", "Orders & Products", List.of(
          permissionNode("orders.read", "View orders", "View order list and status"),
          permissionNode("products.read", "View products", "View product catalog and pricing"))));
      groups.add(buildPermissionGroup("users", "Users & Wallet", List.of(
          permissionNode("users.read", "View users", "View user list and profile"),
          permissionNode("wallet.adjust", "Adjust wallet", "Apply wallet balance adjustments"))));
      groups.add(buildPermissionGroup("system", "System Admin", List.of(
          permissionNode("admins.manage", "Manage admins", "Manage admin accounts"),
          permissionNode("audit.read", "View audit", "View admin audit logs"))));
      return groups;
    }

    private JsonObject buildPermissionGroup(String key, String label, List<JsonObject> permissions) {
      JsonObject group = new JsonObject();
      group.addProperty("key", key);
      group.addProperty("label", label);
      JsonArray permissionArray = new JsonArray();
      for (JsonObject permission : permissions) {
        permissionArray.add(permission);
      }
      group.add("permissions", permissionArray);
      return group;
    }

    private JsonObject permissionNode(String code, String label, String description) {
      JsonObject node = new JsonObject();
      node.addProperty("code", code);
      node.addProperty("label", label);
      node.addProperty("description", description);
      return node;
    }

    private JsonArray buildAdminPermissionTemplatesJson() {
      JsonArray templates = new JsonArray();
      templates.add(permissionTemplate("readonly", "Read Only", false, List.of("orders.read", "products.read", "users.read", "audit.read")));
      templates.add(permissionTemplate("ops", "Operations", false, List.of("orders.read", "products.read", "users.read", "wallet.adjust", "audit.read")));
      templates.add(permissionTemplate("super", "Super Admin", true, List.of("*")));
      return templates;
    }

    private JsonObject permissionTemplate(String key, String label, boolean superAdmin, List<String> permissions) {
      JsonObject template = new JsonObject();
      template.addProperty("key", key);
      template.addProperty("label", label);
      template.addProperty("superAdmin", superAdmin);
      JsonArray permissionArray = new JsonArray();
      for (String permission : permissions) {
        permissionArray.add(permission);
      }
      template.add("permissions", permissionArray);
      return template;
    }

    private JsonObject buildAuditLogJson(AuditLogRecord log) {
      JsonObject row = new JsonObject();
      row.addProperty("id", log.id());
      row.addProperty("action", log.action());
      row.addProperty("adminUsername", log.adminUsername());
      row.addProperty("adminRole", log.adminRole());
      row.addProperty("targetType", log.targetType());
      row.addProperty("targetId", log.targetId());
      row.addProperty("sourceIp", log.sourceIp());
      row.addProperty("createdAt", log.createdAt());
      return row;
    }

    private void recordAudit(AdminSession adminSession, String action, String targetType, String targetId, String sourceIp) {
      long id = ++auditSequence;
      auditLogs.add(new AuditLogRecord(
          id,
          action,
          adminSession.username(),
          adminSession.superAdmin() ? "SUPER_ADMIN" : "CUSTOM",
          targetType,
          targetId,
          sourceIp == null ? "-" : sourceIp,
          Instant.now().toString()));
    }

    private JsonObject cloneJsonObject(JsonObject source) {
      if (source == null) {
        return new JsonObject();
      }
      return GSON.fromJson(GSON.toJson(source), JsonObject.class);
    }

    private JsonObject createDefaultEconomyRuntimeSettings() {
      JsonObject settings = new JsonObject();
      JsonObject exchange = new JsonObject();
      JsonObject shopToGame = new JsonObject();
      shopToGame.addProperty("enabled", true);
      shopToGame.addProperty("ratio", DEFAULT_SHOP_TO_GAME_RATIO);
      JsonObject gameToShop = new JsonObject();
      gameToShop.addProperty("enabled", false);
      gameToShop.addProperty("ratio", DEFAULT_GAME_TO_SHOP_RATIO);
      exchange.add("shopToGame", shopToGame);
      exchange.add("gameToShop", gameToShop);
      settings.add("exchange", exchange);

      JsonObject market = new JsonObject();
      market.addProperty("tradeFeePercent", 0.0d);
      market.addProperty("tradeTaxPercent", 0.0d);
      settings.add("market", market);

      JsonObject inflation = new JsonObject();
      inflation.addProperty("mode", "BURN");
      inflation.addProperty("treasuryUserId", 0);
      settings.add("inflation", inflation);

      JsonObject currency = new JsonObject();
      currency.addProperty("shopCoinName", "ShopCoin");
      currency.addProperty("shopCoinShort", "SC");
      currency.addProperty("gameCoinName", "GameCoin");
      currency.addProperty("gameCoinShort", "GC");
      settings.add("currency", currency);

      JsonObject vault = new JsonObject();
      vault.addProperty("hooked", false);
      vault.addProperty("vaultPluginPresent", false);
      vault.addProperty("provider", "");
      settings.add("vault", vault);

      JsonObject deployment = new JsonObject();
      deployment.addProperty("databaseType", "SQLITE");
      deployment.addProperty("clusterRole", "STANDALONE");
      deployment.addProperty("redisEnabled", false);
      deployment.addProperty("clusterCapable", false);
      deployment.addProperty("singleServerMode", true);
      deployment.addProperty("clusterSyncEnabled", false);
      deployment.addProperty("sqliteSingleServerOnly", true);
      settings.add("deployment", deployment);

      JsonObject leaderboard = new JsonObject();
      leaderboard.addProperty("enabled", true);
      leaderboard.addProperty("showOnlineStatus", true);
      leaderboard.addProperty("defaultMetric", "GAME_COIN");
      leaderboard.addProperty("defaultOrder", "DESC");
      settings.add("leaderboard", leaderboard);

      JsonObject webshopRuntime = new JsonObject();
      webshopRuntime.addProperty("defaultLocale", "zh-CN");
      webshopRuntime.addProperty("timeZone", "Asia/Shanghai");
      webshopRuntime.addProperty("sessionExpireHours", 72);
      webshopRuntime.addProperty("bindRequestExpireMinutes", 15);
      webshopRuntime.addProperty("accessTokenLength", 48);
      webshopRuntime.addProperty("deliveryBatchSize", 20);
      webshopRuntime.addProperty("deliveryRetrySeconds", 30);
      webshopRuntime.addProperty("orderCooldownSeconds", 15);
      webshopRuntime.addProperty("allowSharedClaimCommand", false);
      webshopRuntime.addProperty("refundUndeliveredEnabled", true);
      settings.add("webshopRuntime", webshopRuntime);

      JsonObject marketRuntime = new JsonObject();
      marketRuntime.addProperty("marketMaxActiveListings", 10);
      JsonObject supply = new JsonObject();
      supply.addProperty("autoRefreshThreshold", 8);
      supply.addProperty("defaultTransferBatchSize", 64);
      supply.addProperty("maxTransferBatchSize", 256);
      supply.addProperty("defaultTransitStock", 256);
      supply.addProperty("maxTransitStock", 1024);
      marketRuntime.add("supply", supply);
      settings.add("marketRuntime", marketRuntime);

      JsonObject maintenance = new JsonObject();
      maintenance.addProperty("cleanupIntervalMinutes", 30);
      maintenance.addProperty("pendingBindRetentionHours", 6);
      maintenance.addProperty("pendingPasswordRetentionHours", 6);
      maintenance.addProperty("bindRequestRetentionHours", 24);
      maintenance.addProperty("redeemCodeRetentionDays", 7);
      settings.add("maintenance", maintenance);

      JsonObject logging = new JsonObject();
      logging.addProperty("enabled", true);
      logging.addProperty("level", "INFO");
      logging.addProperty("directory", "logs");
      logging.addProperty("maxFileSizeMb", 8);
      logging.addProperty("maxFiles", 8);
      logging.addProperty("retentionDays", 14);
      settings.add("logging", logging);

      JsonObject broadcast = new JsonObject();
      broadcast.addProperty("enabled", true);
      JsonObject broadcastTemplates = new JsonObject();
      broadcastTemplates.addProperty("listing-created", "");
      broadcastTemplates.addProperty("trade-success", "");
      broadcastTemplates.addProperty("auction-bid", "");
      broadcastTemplates.addProperty("auction-sealed-bid", "");
      broadcast.add("templates", broadcastTemplates);
      settings.add("broadcast", broadcast);

      JsonObject notification = new JsonObject();
      notification.addProperty("marketEventsEnabled", true);
      notification.addProperty("deliveryMailboxEventsEnabled", true);
      notification.add("templates", new JsonObject());
      settings.add("notification", notification);

      JsonObject visual = new JsonObject();
      visual.addProperty("globalCustomIconEnabled", true);
      visual.addProperty("globalCustomNameEnabled", true);
      visual.addProperty("officialProductCustomIconEnabled", true);
      visual.addProperty("officialProductCustomNameEnabled", true);
      visual.addProperty("officialProductUploadImageEnabled", true);
      visual.addProperty("marketListingCustomIconEnabled", true);
      visual.addProperty("marketListingCustomNameEnabled", true);
      visual.addProperty("marketListingUploadImageEnabled", true);
      visual.addProperty("iconPolicyMode", "SOFT");
      visual.addProperty("namePolicyMode", "SOFT");
      settings.add("visual", visual);

      settings.add("marketTagsConfig", cloneJsonObject(marketTagsConfig));
      settings.add("marketLimitationConfig", cloneJsonObject(marketLimitationConfig));
      return settings;
    }

    private JsonObject createDefaultMarketTagsConfig() {
      JsonObject config = new JsonObject();
      config.add("tags", new JsonArray());
      return config;
    }

    private JsonObject createDefaultMarketLimitationConfig() {
      JsonObject config = new JsonObject();
      config.add("rules", new JsonArray());
      JsonArray sides = new JsonArray();
      sides.add("SELL");
      sides.add("BUY");
      config.add("defaultAllowSides", sides);
      return config;
    }

    private String fakeBoundUuidFromUserId(long userId) {
      if (userId <= 0) {
        return null;
      }
      return UUID.nameUUIDFromBytes(("webshopx-user-" + userId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String boundUuidForUser(long userId) {
      if (userId <= 0) {
        return null;
      }
      if (userBoundUuidOverrides.containsKey(userId)) {
        String override = userBoundUuidOverrides.get(userId);
        if (override == null || override.isBlank()) {
          return null;
        }
        return override;
      }
      return fakeBoundUuidFromUserId(userId);
    }

    private String nullString() {
      return null;
    }

    private JsonObject buildCompatUserJson(long userId, String username) {
      JsonObject user = new JsonObject();
      user.addProperty("id", userId);
      user.addProperty("username", username);
      user.addProperty("boundUuid", (String) null);
      user.add("visualPermission", buildVisualPermissionJson());
      return user;
    }

    private JsonObject buildVisualPermissionJson() {
      JsonObject visualPermission = new JsonObject();
      visualPermission.addProperty("customIconAllowed", true);
      visualPermission.addProperty("customNameAllowed", true);
      visualPermission.addProperty("customUploadAllowed", true);
      return visualPermission;
    }

    private JsonObject buildAdminProfileJson(AdminSession adminSession) {
      JsonObject admin = new JsonObject();
      admin.addProperty("id", adminSession.username());
      admin.addProperty("username", adminSession.username());
      admin.addProperty("isSuperAdmin", adminSession.superAdmin());
      admin.addProperty("role", adminSession.superAdmin() ? "SUPER_ADMIN" : "CUSTOM");
      admin.addProperty("canManageAdmins", adminSession.canManageAdmins());
      admin.addProperty("createdAt", adminSession.createdAt());
      admin.add("permissions", buildAdminPermissionsJson(adminSession.superAdmin()));
      return admin;
    }

    private JsonArray buildAdminPermissionsJson(boolean superAdmin) {
      JsonArray permissions = new JsonArray();
      if (!superAdmin) {
        return permissions;
      }
      permissions.add("orders.read");
      permissions.add("products.read");
      permissions.add("economy.read");
      permissions.add("users.read");
      permissions.add("admins.manage");
      permissions.add("*");
      return permissions;
    }

    private JsonObject buildExchangeMetaJson() {
      JsonObject exchange = getOrCreateObject(economyRuntimeSettings, "exchange");
      return cloneJsonObject(exchange);
    }

    private CompatAuthUser resolveCompatAuthOptional(HttpExchange exchange) throws Exception {
      String sessionToken = readBearerToken(exchange);
      if (sessionToken == null || sessionToken.isBlank()) {
        return null;
      }
      Long userId = sessions.get(sessionToken);
      if (userId == null || userId <= 0) {
        return null;
      }
      try (Connection connection = state.connection()) {
        String username = findUsernameByUserId(connection, userId);
        if (username == null || username.isBlank()) {
          return null;
        }
        return new CompatAuthUser(userId, username, sessionToken);
      }
    }

    private JsonObject buildCompatWalletResponse(CompatAuthUser authUser) throws Exception {
      try (Connection connection = state.connection()) {
        return buildCompatWalletJson(connection, authUser.userId(), authUser.username());
      }
    }

    private long leaderboardMetricValue(JsonObject row, String metric) {
      String key = metric == null ? "GAME_COIN" : metric.toUpperCase();
      if ("SHOP_COIN".equals(key)) {
        return row.has("shopCoin") ? row.get("shopCoin").getAsLong() : 0L;
      }
      if ("ONLINE_TIME".equals(key)) {
        return row.has("onlineTimeMinutes") ? row.get("onlineTimeMinutes").getAsLong() : 0L;
      }
      return row.has("gameCoin") ? row.get("gameCoin").getAsLong() : 0L;
    }

    private String normalizeMarketSide(String raw) {
      String value = raw == null ? "SELL" : raw.trim().toUpperCase();
      if (!"SELL".equals(value) && !"BUY".equals(value)) {
        throw new ServiceException(400, "invalid_market_side", "market side must be SELL or BUY");
      }
      return value;
    }

    private String normalizeTradeMode(String raw) {
      String value = raw == null ? "DIRECT" : raw.trim().toUpperCase();
      if (!"DIRECT".equals(value) && !"AUCTION".equals(value)) {
        throw new ServiceException(400, "invalid_trade_mode", "trade mode must be DIRECT or AUCTION");
      }
      return value;
    }

    private JsonObject createMarketListingJson(
        long listingId,
        long sellerUserId,
        String sellerName,
        String side,
        String tradeMode,
        String itemMaterial,
        long price,
        int quantity,
        String currency,
        String tag,
        String createdAt) {
      JsonObject listing = new JsonObject();
      listing.addProperty("id", listingId);
      listing.addProperty("listingId", listingId);
      listing.addProperty("sellerUserId", sellerUserId);
      listing.addProperty("sellerName", sellerName);
      listing.addProperty("sellerUuid", boundUuidForUser(sellerUserId));
      listing.addProperty("buyerName", (String) null);
      listing.addProperty("buyerUuid", (String) null);
      listing.addProperty("currency", currency);
      listing.addProperty("price", price);
      listing.addProperty("quantity", quantity);
      listing.addProperty("quantityTotal", quantity);
      listing.addProperty("itemMaterial", itemMaterial);
      listing.addProperty("displayNameOverride", "");
      listing.addProperty("displayMaterial", itemMaterial);
      listing.addProperty("displayIconPath", (String) null);
      listing.addProperty("remark", "");
      listing.addProperty("itemMetaJson", "{}");
      listing.addProperty("status", "ACTIVE");
      listing.addProperty("createdAt", createdAt);
      listing.addProperty("soldAt", (String) null);
      listing.addProperty("unlistedAt", (String) null);
      listing.addProperty("pausedAt", (String) null);
      listing.addProperty("sourceMode", "MANUAL");
      listing.addProperty("tradeMode", tradeMode);
      listing.addProperty("marketSide", side);
      listing.addProperty("tag", tag == null ? "default" : tag);
      listing.addProperty("dynamicPricingEnabled", false);
      listing.addProperty("dynamicAlgorithm", "LINEAR_DEMAND_V1");
      listing.addProperty("dynamicBasePrice", price);
      listing.addProperty("dynamicFloorPrice", 0);
      listing.addProperty("dynamicCapPrice", 0);
      listing.addProperty("dynamicPriceStep", 1);
      listing.addProperty("dynamicDemandScore", 0);
      listing.addProperty("dynamicParamsJson", "{}");
      listing.addProperty("auctionAlgorithm", "ENGLISH_AUCTION_V1");
      listing.addProperty("auctionStartPrice", price);
      listing.addProperty("auctionMinIncrement", 1);
      listing.addProperty("auctionParamsJson", "{}");
      listing.addProperty("auctionEndAt", (String) null);
      listing.addProperty("auctionHighestBid", 0);
      listing.addProperty("supplyBatchSize", 8);
      listing.addProperty("supplyMaxStock", quantity);
      listing.addProperty("supplyLoadedTotal", 0);
      listing.addProperty("supplySoldTotal", 0);
      listing.addProperty("supplyLastLoadedAmount", 0);
      listing.addProperty("supplyLastLoadedAt", (String) null);
      long escrowTotal = "BUY".equals(side)
          ? Math.addExact(Math.multiplyExact(price, quantity), calculatePercent(Math.multiplyExact(price, quantity), marketTaxPercent()))
          : 0L;
      listing.addProperty("escrowTotal", escrowTotal);
      listing.addProperty("escrowRemaining", escrowTotal);
      return listing;
    }

    private JsonObject requireMarketListing(long listingId) {
      JsonObject listing = compatMarketListings.get(listingId);
      if (listing == null) {
        throw new ServiceException(404, "listing_missing", "listing not found");
      }
      return listing;
    }

    private JsonObject requireOwnedMarketListing(long listingId, CompatAuthUser authUser) {
      JsonObject listing = requireMarketListing(listingId);
      long ownerUserId = listing.has("sellerUserId") ? listing.get("sellerUserId").getAsLong() : -1L;
      if (ownerUserId != authUser.userId()) {
        throw new ServiceException(403, "forbidden", "you are not owner of this listing");
      }
      return listing;
    }

    private long calculatePercent(long base, int percent) {
      if (base <= 0 || percent <= 0) {
        return 0L;
      }
      return (base * percent) / 100L;
    }

    private int marketFeePercent() {
      JsonObject marketRuntime = getOrCreateObject(economyRuntimeSettings, "marketRuntime");
      return marketRuntime.has("marketFeePercent") ? Math.max(0, marketRuntime.get("marketFeePercent").getAsInt()) : 0;
    }

    private int marketTaxPercent() {
      JsonObject marketRuntime = getOrCreateObject(economyRuntimeSettings, "marketRuntime");
      return marketRuntime.has("marketTaxPercent") ? Math.max(0, marketRuntime.get("marketTaxPercent").getAsInt()) : 0;
    }

    private JsonObject buildCompatWalletJson(Connection connection, long userId, String username) throws SQLException {
      ensureWallet(connection, userId);
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT shop_coin, game_coin FROM m2_wallets WHERE user_id = ?")) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new ServiceException(500, "wallet_missing", "wallet not found");
          }
          JsonObject response = new JsonObject();
          response.addProperty("username", username);
          response.addProperty("shopCoin", resultSet.getLong("shop_coin"));
          response.addProperty("gameCoin", resultSet.getLong("game_coin"));
          response.addProperty("boundUuid", (String) null);
          response.add("visualPermission", buildVisualPermissionJson());
          response.add("exchange", buildExchangeMetaJson());
          return response;
        }
      }
    }

    private CompatAuthUser requireCompatAuth(HttpExchange exchange) throws Exception {
      String sessionToken = readBearerToken(exchange);
      if (sessionToken == null || sessionToken.isBlank()) {
        throw new ServiceException(401, "auth_required", "Missing session token");
      }
      Long userId = sessions.get(sessionToken);
      if (userId == null || userId <= 0) {
        throw new ServiceException(401, "auth_invalid", "Session token is invalid or expired");
      }
      try (Connection connection = state.connection()) {
        String username = findUsernameByUserId(connection, userId);
        if (username == null || username.isBlank()) {
          sessions.remove(sessionToken);
          throw new ServiceException(401, "auth_invalid", "Session token is invalid or expired");
        }
        return new CompatAuthUser(userId, username, sessionToken);
      }
    }

    private AdminSession requireAdminSession(HttpExchange exchange) {
      String sessionToken = readBearerToken(exchange);
      if (sessionToken == null || sessionToken.isBlank()) {
        throw new ServiceException(401, "auth_required", "Missing admin session token");
      }
      AdminSession adminSession = adminSessions.get(sessionToken);
      if (adminSession == null) {
        throw new ServiceException(401, "auth_invalid", "admin session token is invalid or expired");
      }
      return adminSession;
    }

    private String readBearerToken(HttpExchange exchange) {
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      if (authorization == null) {
        return null;
      }
      String trimmed = authorization.trim();
      if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
        String token = trimmed.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
      }
      return trimmed.isEmpty() ? null : trimmed;
    }

    private long findUserIdByUsername(Connection connection, String username) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id FROM m2_users WHERE username = ? LIMIT 1")) {
        statement.setString(1, username);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return -1L;
          }
          return resultSet.getLong("id");
        }
      }
    }

    private String findUsernameByUserId(Connection connection, long userId) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT username FROM m2_users WHERE id = ? LIMIT 1")) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return resultSet.getString("username");
        }
      }
    }

    private int parseLimit(String raw, int fallback, int min, int max) {
      if (raw == null || raw.isBlank()) {
        return fallback;
      }
      try {
        int value = Integer.parseInt(raw.trim());
        if (value < min) {
          return min;
        }
        if (value > max) {
          return max;
        }
        return value;
      } catch (Exception ignored) {
        return fallback;
      }
    }

    private String compatOrderStatus(String rawStatus) {
      String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase();
      return switch (normalized) {
        case "PAID" -> "DELIVERED";
        case "SUCCESS" -> "DELIVERED";
        case "CREATED" -> "PENDING";
        default -> normalized.isBlank() ? "PENDING" : normalized;
      };
    }

    private String optionalString(JsonObject payload, String key) {
      if (payload == null || key == null || !payload.has(key) || payload.get(key).isJsonNull()) {
        return null;
      }
      try {
        String value = payload.get(key).getAsString();
        return value == null ? null : value.trim();
      } catch (Exception ignored) {
        return null;
      }
    }

    private void handleRegisterUser(HttpExchange exchange) throws Exception {
      JsonObject payload = requireJsonBody(exchange);
      String username = readNonBlank(payload, "username", "invalid_username", "username is required");
      try (Connection connection = state.connection()) {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO m2_users (username, created_at)
            VALUES (?, ?)
            """)) {
          statement.setString(1, username);
          statement.setString(2, Instant.now().toString());
          statement.executeUpdate();
        } catch (SQLException exception) {
          if (isConstraintViolation(exception)) {
            throw new ServiceException(409, "username_exists", "username already exists");
          }
          throw exception;
        }
        long userId;
        try (PreparedStatement query = connection.prepareStatement("SELECT id FROM m2_users WHERE username = ?")) {
          query.setString(1, username);
          try (ResultSet resultSet = query.executeQuery()) {
            if (!resultSet.next()) {
              throw new ServiceException(500, "user_lookup_failed", "failed to read created user");
            }
            userId = resultSet.getLong("id");
          }
        }
        ensureWallet(connection, userId);
        JsonObject response = new JsonObject();
        response.addProperty("id", userId);
        response.addProperty("username", username);
        writeJson(exchange, 201, response);
      }
    }

    private void handleGetUser(HttpExchange exchange, String rawId) throws Exception {
      long userId = parsePositiveLong(rawId, "invalid_user", "user id must be positive");
      try (Connection connection = state.connection()) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, username, created_at FROM m2_users WHERE id = ?")) {
          statement.setLong(1, userId);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              throw new ServiceException(404, "user_missing", "user not found");
            }
            JsonObject response = new JsonObject();
            response.addProperty("id", resultSet.getLong("id"));
            response.addProperty("username", resultSet.getString("username"));
            response.addProperty("createdAt", resultSet.getString("created_at"));
            writeJson(exchange, 200, response);
          }
        }
      }
    }

    private void handleGetWallet(HttpExchange exchange, String rawId) throws Exception {
      long userId = parsePositiveLong(rawId, "invalid_user", "user id must be positive");
      try (Connection connection = state.connection()) {
        requireUser(connection, userId);
        ensureWallet(connection, userId);
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT shop_coin, game_coin, updated_at FROM m2_wallets WHERE user_id = ?")) {
          statement.setLong(1, userId);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              throw new ServiceException(500, "wallet_missing", "wallet not found");
            }
            JsonObject response = new JsonObject();
            response.addProperty("userId", userId);
            response.addProperty("shopCoin", resultSet.getLong("shop_coin"));
            response.addProperty("gameCoin", resultSet.getLong("game_coin"));
            response.addProperty("updatedAt", resultSet.getString("updated_at"));
            writeJson(exchange, 200, response);
          }
        }
      }
    }

    private void handleAdjustWallet(HttpExchange exchange) throws Exception {
      requireAdmin(exchange);
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user", "user id must be positive");
      String currency = normalizeCurrency(readNonBlank(payload, "currency", "invalid_currency", "currency is required"));
      long delta = readLong(payload, "delta", "invalid_amount", "delta is required");
      if (delta == 0) {
        throw new ServiceException(400, "invalid_amount", "delta cannot be zero");
      }
      String bizType = readNonBlank(payload, "bizType", "invalid_biz", "bizType is required");
      String bizId = readNonBlank(payload, "bizId", "invalid_biz", "bizId is required");
      boolean enforceBalance = delta < 0;

      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          requireUser(connection, userId);
          ensureWallet(connection, userId);
          boolean applied = insertLedgerEntry(connection, userId, currency, delta, bizType, bizId);
          long balance = readCurrencyBalanceForUpdate(connection, userId, currency);
          long next = balance;
          if (applied) {
            next = Math.addExact(balance, delta);
            if (enforceBalance && next < 0) {
              throw new ServiceException(409, "insufficient_balance", "insufficient wallet balance");
            }
            updateCurrencyBalance(connection, userId, currency, next);
          }
          connection.commit();
          JsonObject response = new JsonObject();
          response.addProperty("userId", userId);
          response.addProperty("currency", currency);
          response.addProperty("balance", next);
          response.addProperty("delta", applied ? delta : 0);
          response.addProperty("applied", applied);
          writeJson(exchange, 200, response);
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }
    }

    private void handleListProducts(HttpExchange exchange) throws Exception {
      try (Connection connection = state.connection()) {
        JsonArray items = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, sku, title, currency, price, active, created_at, updated_at FROM m2_products ORDER BY id ASC")) {
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              JsonObject row = new JsonObject();
              row.addProperty("id", resultSet.getLong("id"));
              row.addProperty("sku", resultSet.getString("sku"));
              row.addProperty("title", resultSet.getString("title"));
              row.addProperty("currency", resultSet.getString("currency"));
              row.addProperty("price", resultSet.getLong("price"));
              row.addProperty("active", resultSet.getInt("active") == 1);
              row.addProperty("createdAt", resultSet.getString("created_at"));
              row.addProperty("updatedAt", resultSet.getString("updated_at"));
              items.add(row);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.add("items", items);
        writeJson(exchange, 200, response);
      }
    }

    private void handleUpsertProduct(HttpExchange exchange) throws Exception {
      requireAdmin(exchange);
      JsonObject payload = requireJsonBody(exchange);
      String sku = readNonBlank(payload, "sku", "invalid_sku", "sku is required");
      String title = readNonBlank(payload, "title", "invalid_title", "title is required");
      String currency = normalizeCurrency(readNonBlank(payload, "currency", "invalid_currency", "currency is required"));
      long price = readLong(payload, "price", "invalid_price", "price is required");
      if (price < 0) {
        throw new ServiceException(400, "invalid_price", "price cannot be negative");
      }
      boolean active = !payload.has("active") || payload.get("active").getAsBoolean();
      String now = Instant.now().toString();

      try (Connection connection = state.connection()) {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO m2_products (sku, title, currency, price, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sku) DO UPDATE SET
              title = excluded.title,
              currency = excluded.currency,
              price = excluded.price,
              active = excluded.active,
              updated_at = excluded.updated_at
            """)) {
          statement.setString(1, sku);
          statement.setString(2, title);
          statement.setString(3, currency);
          statement.setLong(4, price);
          statement.setInt(5, active ? 1 : 0);
          statement.setString(6, now);
          statement.setString(7, now);
          statement.executeUpdate();
        }

        JsonObject response = new JsonObject();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id, sku, title, currency, price, active, created_at, updated_at FROM m2_products WHERE sku = ?")) {
          query.setString(1, sku);
          try (ResultSet resultSet = query.executeQuery()) {
            if (!resultSet.next()) {
              throw new ServiceException(500, "product_lookup_failed", "failed to read saved product");
            }
            response.addProperty("id", resultSet.getLong("id"));
            response.addProperty("sku", resultSet.getString("sku"));
            response.addProperty("title", resultSet.getString("title"));
            response.addProperty("currency", resultSet.getString("currency"));
            response.addProperty("price", resultSet.getLong("price"));
            response.addProperty("active", resultSet.getInt("active") == 1);
            response.addProperty("createdAt", resultSet.getString("created_at"));
            response.addProperty("updatedAt", resultSet.getString("updated_at"));
          }
        }
        writeJson(exchange, 200, response);
      }
    }

    private void handlePlaceOrder(HttpExchange exchange) throws Exception {
      JsonObject payload = requireJsonBody(exchange);
      long userId = readPositiveLong(payload, "userId", "invalid_user", "user id must be positive");
      long productId = readPositiveLong(payload, "productId", "invalid_product", "product id must be positive");
      int quantity = (int) readPositiveLong(payload, "quantity", "invalid_quantity", "quantity must be positive");
      if (quantity > 10_000) {
        throw new ServiceException(400, "invalid_quantity", "quantity is too large");
      }
      String idempotencyKey = readNonBlank(payload, "idempotencyKey", "invalid_idempotency", "idempotencyKey is required");

      try (Connection connection = state.connection()) {
        connection.setAutoCommit(false);
        try {
          requireUser(connection, userId);
          ensureWallet(connection, userId);

          ExistingOrder existing = readExistingOrder(connection, idempotencyKey);
          if (existing != null) {
            connection.commit();
            JsonObject response = new JsonObject();
            response.addProperty("state", "EXISTING");
            response.addProperty("orderNo", existing.orderNo());
            response.addProperty("totalAmount", existing.totalAmount());
            response.addProperty("currency", existing.currency());
            response.addProperty("status", existing.status());
            writeJson(exchange, 200, response);
            return;
          }

          ProductRow product = readActiveProductForUpdate(connection, productId);
          long totalAmount = Math.multiplyExact(product.price(), quantity);
          long balance = readCurrencyBalanceForUpdate(connection, userId, product.currency());
          long next = Math.addExact(balance, -totalAmount);
          if (next < 0) {
            throw new ServiceException(409, "insufficient_balance", "insufficient wallet balance");
          }

          updateCurrencyBalance(connection, userId, product.currency(), next);
          boolean debitApplied = insertLedgerEntry(
              connection,
              userId,
              product.currency(),
              -totalAmount,
              "ORDER_DEBIT",
              idempotencyKey + ":DEBIT");
          if (!debitApplied) {
            throw new ServiceException(409, "idempotency_conflict", "duplicate debit request");
          }

          String orderNo = "M2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
          try (PreparedStatement insert = connection.prepareStatement("""
              INSERT INTO m2_orders (
                order_no,
                user_id,
                product_id,
                quantity,
                total_amount,
                currency,
                status,
                idempotency_key,
                created_at
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)) {
            insert.setString(1, orderNo);
            insert.setLong(2, userId);
            insert.setLong(3, productId);
            insert.setInt(4, quantity);
            insert.setLong(5, totalAmount);
            insert.setString(6, product.currency());
            insert.setString(7, "PAID");
            insert.setString(8, idempotencyKey);
            insert.setString(9, Instant.now().toString());
            insert.executeUpdate();
          }

          connection.commit();
          JsonObject response = new JsonObject();
          response.addProperty("state", "CREATED");
          response.addProperty("orderNo", orderNo);
          response.addProperty("currency", product.currency());
          response.addProperty("totalAmount", totalAmount);
          response.addProperty("status", "PAID");
          response.addProperty("balance", next);
          writeJson(exchange, 201, response);
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(true);
        }
      }
    }

    private void handleListOrders(HttpExchange exchange, String rawUserId) throws Exception {
      long userId = parsePositiveLong(rawUserId, "invalid_user", "userId query parameter is required");
      try (Connection connection = state.connection()) {
        requireUser(connection, userId);
        JsonArray items = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT o.order_no, o.product_id, p.sku, p.title, o.quantity, o.total_amount, o.currency, o.status, o.created_at
            FROM m2_orders o
            JOIN m2_products p ON p.id = o.product_id
            WHERE o.user_id = ?
            ORDER BY o.id DESC
            """)) {
          statement.setLong(1, userId);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              JsonObject row = new JsonObject();
              row.addProperty("orderNo", resultSet.getString("order_no"));
              row.addProperty("productId", resultSet.getLong("product_id"));
              row.addProperty("sku", resultSet.getString("sku"));
              row.addProperty("title", resultSet.getString("title"));
              row.addProperty("quantity", resultSet.getInt("quantity"));
              row.addProperty("totalAmount", resultSet.getLong("total_amount"));
              row.addProperty("currency", resultSet.getString("currency"));
              row.addProperty("status", resultSet.getString("status"));
              row.addProperty("createdAt", resultSet.getString("created_at"));
              items.add(row);
            }
          }
        }
        JsonObject response = new JsonObject();
        response.addProperty("userId", userId);
        response.add("items", items);
        writeJson(exchange, 200, response);
      }
    }

    private void requireAdmin(HttpExchange exchange) {
      if (!state.adminAuthRequired()) {
        return;
      }
      String token = exchange.getRequestHeaders().getFirst("X-WebShopX-Admin-Token");
      if (token == null || !token.equals(state.adminToken())) {
        throw new ServiceException(403, "forbidden", "admin token is required");
      }
    }

    private void requireUser(Connection connection, long userId) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM m2_users WHERE id = ?")) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new ServiceException(404, "user_missing", "user not found");
          }
        }
      }
    }

    private void ensureWallet(Connection connection, long userId) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO m2_wallets (user_id, shop_coin, game_coin, updated_at)
          VALUES (?, 0, 0, ?)
          ON CONFLICT(user_id) DO NOTHING
          """)) {
        statement.setLong(1, userId);
        statement.setString(2, Instant.now().toString());
        statement.executeUpdate();
      }
    }

    private long readCurrencyBalanceForUpdate(Connection connection, long userId, String currency) throws SQLException {
      String column = switch (currency) {
        case "SHOP_COIN" -> "shop_coin";
        case "GAME_COIN" -> "game_coin";
        default -> throw new ServiceException(400, "invalid_currency", "unsupported currency");
      };
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT " + column + " FROM m2_wallets WHERE user_id = ?")) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new ServiceException(500, "wallet_missing", "wallet not found");
          }
          return resultSet.getLong(1);
        }
      }
    }

    private void updateCurrencyBalance(Connection connection, long userId, String currency, long next) throws SQLException {
      String sql = switch (currency) {
        case "SHOP_COIN" -> "UPDATE m2_wallets SET shop_coin = ?, updated_at = ? WHERE user_id = ?";
        case "GAME_COIN" -> "UPDATE m2_wallets SET game_coin = ?, updated_at = ? WHERE user_id = ?";
        default -> throw new ServiceException(400, "invalid_currency", "unsupported currency");
      };
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, next);
        statement.setString(2, Instant.now().toString());
        statement.setLong(3, userId);
        statement.executeUpdate();
      }
    }

    private boolean insertLedgerEntry(
        Connection connection,
        long userId,
        String currency,
        long delta,
        String bizType,
        String bizId) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO m2_wallet_ledger (user_id, currency, delta, biz_type, biz_id, created_at)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT(user_id, currency, biz_type, biz_id) DO NOTHING
          """)) {
        statement.setLong(1, userId);
        statement.setString(2, currency);
        statement.setLong(3, delta);
        statement.setString(4, bizType);
        statement.setString(5, bizId);
        statement.setString(6, Instant.now().toString());
        return statement.executeUpdate() > 0;
      }
    }

    private ExistingOrder readExistingOrder(Connection connection, String idempotencyKey) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT order_no, total_amount, currency, status
          FROM m2_orders
          WHERE idempotency_key = ?
          LIMIT 1
          """)) {
        statement.setString(1, idempotencyKey);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return new ExistingOrder(
              resultSet.getString("order_no"),
              resultSet.getLong("total_amount"),
              resultSet.getString("currency"),
              resultSet.getString("status"));
        }
      }
    }

    private ProductRow readActiveProductForUpdate(Connection connection, long productId) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT id, sku, title, currency, price, active
          FROM m2_products
          WHERE id = ?
          LIMIT 1
          """)) {
        statement.setLong(1, productId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new ServiceException(404, "product_missing", "product not found");
          }
          if (resultSet.getInt("active") != 1) {
            throw new ServiceException(409, "product_inactive", "product is inactive");
          }
          return new ProductRow(
              resultSet.getLong("id"),
              resultSet.getString("sku"),
              resultSet.getString("title"),
              normalizeCurrency(resultSet.getString("currency")),
              resultSet.getLong("price"));
        }
      }
    }

    private JsonObject requireJsonBody(HttpExchange exchange) throws IOException {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (body.isBlank()) {
        throw new ServiceException(400, "bad_request", "request body is required");
      }
      try {
        JsonElement element = GSON.fromJson(body, JsonElement.class);
        if (element == null || !element.isJsonObject()) {
          throw new ServiceException(400, "bad_request", "request body must be a JSON object");
        }
        return element.getAsJsonObject();
      } catch (JsonParseException exception) {
        throw new ServiceException(400, "bad_json", "request body is not valid JSON");
      }
    }

    private long readLong(JsonObject payload, String key, String code, String message) {
      if (!payload.has(key) || payload.get(key).isJsonNull()) {
        throw new ServiceException(400, code, message);
      }
      try {
        return payload.get(key).getAsLong();
      } catch (Exception exception) {
        throw new ServiceException(400, code, message);
      }
    }

    private long readPositiveLong(JsonObject payload, String key, String code, String message) {
      long value = readLong(payload, key, code, message);
      if (value <= 0) {
        throw new ServiceException(400, code, message);
      }
      return value;
    }

    private long parsePositiveLong(String raw, String code, String message) {
      if (raw == null || raw.isBlank()) {
        throw new ServiceException(400, code, message);
      }
      try {
        long value = Long.parseLong(raw.trim());
        if (value <= 0) {
          throw new ServiceException(400, code, message);
        }
        return value;
      } catch (NumberFormatException exception) {
        throw new ServiceException(400, code, message);
      }
    }

    private String readNonBlank(JsonObject payload, String key, String code, String message) {
      if (!payload.has(key) || payload.get(key).isJsonNull()) {
        throw new ServiceException(400, code, message);
      }
      String value = payload.get(key).getAsString();
      if (value == null || value.isBlank()) {
        throw new ServiceException(400, code, message);
      }
      return value.trim();
    }

    private String normalizeCurrency(String raw) {
      if (raw == null) {
        throw new ServiceException(400, "invalid_currency", "currency is required");
      }
      String normalized = raw.trim().toUpperCase();
      if (!"SHOP_COIN".equals(normalized) && !"GAME_COIN".equals(normalized)) {
        throw new ServiceException(400, "invalid_currency", "currency must be SHOP_COIN or GAME_COIN");
      }
      return normalized;
    }

    private void writeJson(HttpExchange exchange, int status, JsonObject payload) throws IOException {
      byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
      writeBytes(exchange, status, "application/json; charset=utf-8", bytes);
    }

    private void writeError(HttpExchange exchange, int status, String code, String message) throws IOException {
      JsonObject payload = new JsonObject();
      payload.addProperty("error", code);
      payload.addProperty("message", message == null ? code : message);
      writeJson(exchange, status, payload);
    }

    private void writeText(HttpExchange exchange, int status, String contentType, String value) throws IOException {
      writeBytes(exchange, status, contentType, value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(bytes);
      }
    }

    private Map<String, String> parseQuery(String rawQuery) {
      Map<String, String> result = new HashMap<>();
      if (rawQuery == null || rawQuery.isBlank()) {
        return result;
      }
      String[] pairs = rawQuery.split("&");
      for (String pair : pairs) {
        if (pair == null || pair.isBlank()) {
          continue;
        }
        String[] parts = pair.split("=", 2);
        String key = urlDecode(parts[0]);
        String value = parts.length > 1 ? urlDecode(parts[1]) : "";
        result.put(key, value);
      }
      return result;
    }

    private String urlDecode(String raw) {
      return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private boolean isConstraintViolation(SQLException exception) {
      String message = exception.getMessage();
      if (message == null) {
        return false;
      }
      String lower = message.toLowerCase();
      return lower.contains("unique") || lower.contains("constraint");
    }
  }

  private record ProductRow(long id, String sku, String title, String currency, long price) {
  }

  private record ExistingOrder(String orderNo, long totalAmount, String currency, String status) {
  }

  private record CompatAuthUser(long userId, String username, String token) {
  }

  private record LookupUserRow(long userId, String username, String createdAt) {
  }

  private record UserVisualPermission(
      String iconPermission,
      String namePermission,
      String uploadPermission,
      Integer listingLimitOverride) {

    private static UserVisualPermission defaults() {
      return new UserVisualPermission("INHERIT", "INHERIT", "INHERIT", null);
    }

    private static UserVisualPermission fromPayload(JsonObject payload) {
      String icon = normalizePermission(payload, "iconPermission");
      String name = normalizePermission(payload, "namePermission");
      String upload = normalizePermission(payload, "uploadPermission");
      Integer listingLimitOverride = null;
      if (payload != null && payload.has("listingLimitOverride") && !payload.get("listingLimitOverride").isJsonNull()) {
        try {
          int parsed = payload.get("listingLimitOverride").getAsInt();
          if (parsed > 0) {
            listingLimitOverride = parsed;
          }
        } catch (Exception ignored) {
          listingLimitOverride = null;
        }
      }
      return new UserVisualPermission(icon, name, upload, listingLimitOverride);
    }

    private static String normalizePermission(JsonObject payload, String key) {
      if (payload == null || key == null || !payload.has(key) || payload.get(key).isJsonNull()) {
        return "INHERIT";
      }
      String raw = payload.get(key).getAsString();
      String normalized = raw == null ? "" : raw.trim().toUpperCase();
      if ("ALLOW".equals(normalized) || "DENY".equals(normalized)) {
        return normalized;
      }
      return "INHERIT";
    }

    private boolean isIconAllowed() {
      return !"DENY".equals(iconPermission);
    }

    private boolean isNameAllowed() {
      return !"DENY".equals(namePermission);
    }

    private boolean isUploadAllowed() {
      return !"DENY".equals(uploadPermission);
    }
  }

  private record ProductAdminMeta(
      String remark,
      String productType,
      String itemMaterial,
      String displayMaterial,
      String displayNameOverride,
      String displayIconPath,
      Integer itemAmount,
      Integer perUserLimit,
      String commandTemplate,
      boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      Integer dynamicBasePrice,
      Integer dynamicFloorPrice,
      Integer dynamicCapPrice,
      Integer dynamicPriceStep,
      String dynamicParamsJson,
      String publishAt,
      String unpublishAt,
      String updatedAt) {

    private static ProductAdminMeta defaults() {
      return new ProductAdminMeta(
          "",
          "COMMAND",
          "DIAMOND",
          null,
          null,
          null,
          64,
          null,
          "",
          false,
          "LINEAR_DEMAND_V1",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          Instant.now().toString());
    }

    private static ProductAdminMeta fromPayload(JsonObject payload, ProductAdminMeta fallback, String updatedAt) {
      ProductAdminMeta base = fallback == null ? defaults() : fallback;
      return new ProductAdminMeta(
          optional(payload, "remark", base.remark()),
          optional(payload, "productType", base.productType()),
          optional(payload, "itemMaterial", base.itemMaterial()),
          optionalNullable(payload, "displayMaterial", base.displayMaterial()),
          optionalNullable(payload, "displayNameOverride", base.displayNameOverride()),
          optionalNullable(payload, "displayIconPath", base.displayIconPath()),
          optionalInteger(payload, "itemAmount", base.itemAmount()),
          optionalInteger(payload, "perUserLimit", base.perUserLimit()),
          optional(payload, "commandTemplate", base.commandTemplate()),
          optionalBoolean(payload, "dynamicPricingEnabled", base.dynamicPricingEnabled()),
          optional(payload, "dynamicAlgorithm", base.dynamicAlgorithm()),
          optionalInteger(payload, "dynamicBasePrice", base.dynamicBasePrice()),
          optionalInteger(payload, "dynamicFloorPrice", base.dynamicFloorPrice()),
          optionalInteger(payload, "dynamicCapPrice", base.dynamicCapPrice()),
          optionalInteger(payload, "dynamicPriceStep", base.dynamicPriceStep()),
          optionalNullable(payload, "dynamicParamsJson", base.dynamicParamsJson()),
          optionalNullable(payload, "publishAt", base.publishAt()),
          optionalNullable(payload, "unpublishAt", base.unpublishAt()),
          updatedAt);
    }

    private ProductAdminMeta withDisplayIconPath(String nextIconPath) {
      return new ProductAdminMeta(
          remark,
          productType,
          itemMaterial,
          displayMaterial,
          displayNameOverride,
          nextIconPath,
          itemAmount,
          perUserLimit,
          commandTemplate,
          dynamicPricingEnabled,
          dynamicAlgorithm,
          dynamicBasePrice,
          dynamicFloorPrice,
          dynamicCapPrice,
          dynamicPriceStep,
          dynamicParamsJson,
          publishAt,
          unpublishAt,
          Instant.now().toString());
    }

    private ProductAdminMeta withUpdatedAt(String nextUpdatedAt) {
      return new ProductAdminMeta(
          remark,
          productType,
          itemMaterial,
          displayMaterial,
          displayNameOverride,
          displayIconPath,
          itemAmount,
          perUserLimit,
          commandTemplate,
          dynamicPricingEnabled,
          dynamicAlgorithm,
          dynamicBasePrice,
          dynamicFloorPrice,
          dynamicCapPrice,
          dynamicPriceStep,
          dynamicParamsJson,
          publishAt,
          unpublishAt,
          nextUpdatedAt);
    }

    private static String optional(JsonObject payload, String key, String fallback) {
      if (payload == null || key == null || !payload.has(key) || payload.get(key).isJsonNull()) {
        return fallback;
      }
      try {
        String value = payload.get(key).getAsString();
        return value == null ? fallback : value;
      } catch (Exception ignored) {
        return fallback;
      }
    }

    private static String optionalNullable(JsonObject payload, String key, String fallback) {
      if (payload == null || key == null || !payload.has(key) || payload.get(key).isJsonNull()) {
        return fallback;
      }
      try {
        String value = payload.get(key).getAsString();
        if (value == null || value.isBlank()) {
          return null;
        }
        return value.trim();
      } catch (Exception ignored) {
        return fallback;
      }
    }

    private static Integer optionalInteger(JsonObject payload, String key, Integer fallback) {
      if (payload == null || key == null || !payload.has(key) || payload.get(key).isJsonNull()) {
        return fallback;
      }
      try {
        return payload.get(key).getAsInt();
      } catch (Exception ignored) {
        return fallback;
      }
    }

    private static boolean optionalBoolean(JsonObject payload, String key, boolean fallback) {
      if (payload == null || key == null || !payload.has(key) || payload.get(key).isJsonNull()) {
        return fallback;
      }
      try {
        return payload.get(key).getAsBoolean();
      } catch (Exception ignored) {
        return fallback;
      }
    }
  }

  private record MaterialOverrideRecord(
      String materialKey,
      String displayNameOverride,
      String iconPath,
      String updatedBy,
      String updatedAt) {
  }

  private record LocaleCenterRecord(
      String locale,
      String name,
      String nativeName,
      String source,
      String version,
      String status,
      boolean webEnabled,
      boolean gameEnabled,
      boolean builtIn,
      String updatedAt) {

    private LocaleCenterRecord withWebEnabled(boolean enabled) {
      return new LocaleCenterRecord(locale, name, nativeName, source, version, status, enabled, gameEnabled, builtIn, updatedAt);
    }

    private LocaleCenterRecord withGameEnabled(boolean enabled) {
      return new LocaleCenterRecord(locale, name, nativeName, source, version, status, webEnabled, enabled, builtIn, updatedAt);
    }

    private LocaleCenterRecord withUpdatedAt(String nextUpdatedAt) {
      return new LocaleCenterRecord(locale, name, nativeName, source, version, status, webEnabled, gameEnabled, builtIn, nextUpdatedAt);
    }
  }

  private record ThemeCenterRecord(
      String themeId,
      String name,
      String source,
      String version,
      String status,
      boolean webEnabled,
      boolean builtIn,
      String updatedAt) {

    private ThemeCenterRecord withWebEnabled(boolean enabled) {
      return new ThemeCenterRecord(themeId, name, source, version, status, enabled, builtIn, updatedAt);
    }

    private ThemeCenterRecord withUpdatedAt(String nextUpdatedAt) {
      return new ThemeCenterRecord(themeId, name, source, version, status, webEnabled, builtIn, nextUpdatedAt);
    }
  }

  private record AdminManagerRecord(
      long userId,
      String username,
      String boundUuid,
      boolean superAdmin,
      String role,
      List<String> permissions,
      String templateKey,
      boolean active,
      String updatedAt) {
  }

  private record AuditLogRecord(
      long id,
      String action,
      String adminUsername,
      String adminRole,
      String targetType,
      String targetId,
      String sourceIp,
      String createdAt) {
  }

  private record RedeemCodeRecord(
      String code,
      long shopCoin,
      long gameCoin,
      long usedCount,
      long maxUses,
      long perUserMaxUses,
      String expiresAt,
      boolean active) {
  }

  private record AdminSession(
      String token,
      String username,
      boolean superAdmin,
      boolean canManageAdmins,
      String createdAt) {
  }

  private static final class ServiceException extends RuntimeException {

    private final int status;
    private final String code;

    private ServiceException(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    private int status() {
      return status;
    }

    private String code() {
      return code;
    }
  }
}
