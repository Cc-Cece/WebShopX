package com.webshopx.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared M2 runtime bootstrap for Fabric/NeoForge with minimal business API loop.
 */
public final class M2RuntimeBootstrap {

  private static final Gson GSON = new Gson();
  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final int DEFAULT_PORT = 18081;
  private static final String DEFAULT_SQLITE_PATH = "data/webshopx-m2.sqlite";

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
        return value > 0 && value <= 65535 ? value : DEFAULT_PORT;
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

    private ApiHandler(RuntimeState state) {
      this.state = state;
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

      if ("/health".equals(path) && "GET".equals(method)) {
        handleHealth(exchange);
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
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(bytes);
      }
    }

    private void writeError(HttpExchange exchange, int status, String code, String message) throws IOException {
      JsonObject payload = new JsonObject();
      payload.addProperty("error", code);
      payload.addProperty("message", message == null ? code : message);
      writeJson(exchange, status, payload);
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
