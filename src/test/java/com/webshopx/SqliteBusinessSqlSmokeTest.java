package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqliteBusinessSqlSmokeTest {
  @Test
  void sqliteCriticalUpsertAndTimeSqlShouldExecute() throws Exception {
    Path dbFile = Files.createTempFile("webshopx-sqlite-smoke-", ".db");
    Files.deleteIfExists(dbFile);
    Class.forName("org.sqlite.JDBC");

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
      applySchema(connection, readResource("/db/sqlite/schema.sql"));
      seedUser(connection, 1L);

      upsertRuntimeConfig(connection);
      upsertWalletAndLedger(connection);
      upsertUserMarketSettings(connection);
      upsertUserVisualPermissions(connection);
      upsertPlayerPresence(connection);
      upsertRedeemUsage(connection);
      upsertProductUsage(connection);
      upsertMarketTag(connection);
      verifyDateSubEquivalentQuery(connection);
    } finally {
      Files.deleteIfExists(dbFile);
    }
  }

  private static void upsertRuntimeConfig(Connection connection) throws Exception {
    String sql = """
        INSERT INTO runtime_config (config_key, config_value, version)
        VALUES (?, ?, 1)
        ON CONFLICT(config_key) DO UPDATE SET
          config_value = excluded.config_value,
          version = runtime_config.version + 1,
          updated_at = CURRENT_TIMESTAMP
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "market_runtime");
      statement.setString(2, "{\"max\":10}");
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "market_runtime");
      statement.setString(2, "{\"max\":11}");
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT config_value, version FROM runtime_config WHERE config_key = ?")) {
      statement.setString(1, "market_runtime");
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals("{\"max\":11}", resultSet.getString("config_value"));
        assertEquals(2L, resultSet.getLong("version"));
      }
    }
  }

  private static void upsertWalletAndLedger(Connection connection) throws Exception {
    String walletSql = """
        INSERT INTO wallets (user_id)
        VALUES (?)
        ON CONFLICT(user_id) DO NOTHING
        """;
    try (PreparedStatement statement = connection.prepareStatement(walletSql)) {
      statement.setLong(1, 1L);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(walletSql)) {
      statement.setLong(1, 1L);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT COUNT(*) FROM wallets WHERE user_id = ?")) {
      statement.setLong(1, 1L);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals(1L, resultSet.getLong(1));
      }
    }

    long walletId;
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT id FROM wallets WHERE user_id = ? LIMIT 1")) {
      statement.setLong(1, 1L);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        walletId = resultSet.getLong("id");
      }
    }

    String ledgerSql = """
        INSERT INTO wallet_ledger (wallet_id, currency, delta, biz_type, biz_id)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(wallet_id, biz_type, biz_id) DO NOTHING
        """;
    try (PreparedStatement statement = connection.prepareStatement(ledgerSql)) {
      statement.setLong(1, walletId);
      statement.setString(2, "SHOP_COIN");
      statement.setLong(3, 100L);
      statement.setString(4, "TEST");
      statement.setString(5, "biz-1");
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(ledgerSql)) {
      statement.setLong(1, walletId);
      statement.setString(2, "SHOP_COIN");
      statement.setLong(3, 100L);
      statement.setString(4, "TEST");
      statement.setString(5, "biz-1");
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT COUNT(*) FROM wallet_ledger WHERE wallet_id = ?")) {
      statement.setLong(1, walletId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals(1L, resultSet.getLong(1));
      }
    }
  }

  private static void upsertUserMarketSettings(Connection connection) throws Exception {
    String sql = """
        INSERT INTO user_market_settings (user_id, listing_limit_override)
        VALUES (?, ?)
        ON CONFLICT(user_id) DO UPDATE SET
          listing_limit_override = excluded.listing_limit_override,
          updated_at = CURRENT_TIMESTAMP
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, 1L);
      statement.setInt(2, 50);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, 1L);
      statement.setInt(2, 60);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT listing_limit_override FROM user_market_settings WHERE user_id = 1")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals(60, resultSet.getInt("listing_limit_override"));
      }
    }
  }

  private static void upsertUserVisualPermissions(Connection connection) throws Exception {
    String sql = """
        INSERT INTO user_visual_permissions (user_id, icon_permission, name_permission, upload_permission)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(user_id) DO UPDATE SET
          icon_permission = excluded.icon_permission,
          name_permission = excluded.name_permission,
          upload_permission = excluded.upload_permission,
          updated_at = CURRENT_TIMESTAMP
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, 1L);
      statement.setString(2, "ALLOW");
      statement.setString(3, "INHERIT");
      statement.setString(4, "DENY");
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, 1L);
      statement.setString(2, "DENY");
      statement.setString(3, "ALLOW");
      statement.setString(4, "ALLOW");
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT icon_permission, name_permission FROM user_visual_permissions WHERE user_id = 1")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals("DENY", resultSet.getString("icon_permission"));
        assertEquals("ALLOW", resultSet.getString("name_permission"));
      }
    }
  }

  private static void upsertPlayerPresence(Connection connection) throws Exception {
    String sql = """
        INSERT INTO player_presence (mc_uuid, username, server_id, online, updated_at)
        VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP)
        ON CONFLICT(mc_uuid) DO UPDATE SET
          username = excluded.username,
          server_id = excluded.server_id,
          online = TRUE,
          updated_at = CURRENT_TIMESTAMP
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "00000000-0000-0000-0000-000000000001");
      statement.setString(2, "PlayerA");
      statement.setString(3, "s1");
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "00000000-0000-0000-0000-000000000001");
      statement.setString(2, "PlayerB");
      statement.setString(3, "s2");
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT username, server_id FROM player_presence WHERE mc_uuid = ?")) {
      statement.setString(1, "00000000-0000-0000-0000-000000000001");
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals("PlayerB", resultSet.getString("username"));
        assertEquals("s2", resultSet.getString("server_id"));
      }
    }
  }

  private static void upsertRedeemUsage(Connection connection) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO redeem_codes (code, shop_coin, game_coin, max_uses, per_user_max_uses, used_count, active) VALUES ('R1', 0, 0, 10, 10, 0, 1)")) {
      statement.executeUpdate();
    }
    String sql = """
        INSERT INTO redeem_usage (code, user_id, use_count)
        VALUES (?, ?, 1)
        ON CONFLICT(code, user_id) DO UPDATE SET
          use_count = redeem_usage.use_count + 1,
          used_at = CURRENT_TIMESTAMP
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "R1");
      statement.setLong(2, 1L);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "R1");
      statement.setLong(2, 1L);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT use_count FROM redeem_usage WHERE code = 'R1' AND user_id = 1")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("use_count"));
      }
    }
  }

  private static void upsertProductUsage(Connection connection) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO products (sku, title, currency, price, product_type, command_template, active) VALUES ('p1', 'P1', 'SHOP_COIN', 1, 'COMMAND', 'say hi', 1)")) {
      statement.executeUpdate();
    }
    long productId;
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT id FROM products WHERE sku = 'p1' LIMIT 1");
         ResultSet resultSet = statement.executeQuery()) {
      assertTrue(resultSet.next());
      productId = resultSet.getLong("id");
    }

    String sql = """
        INSERT INTO product_user_usage (product_id, user_id, used_count)
        VALUES (?, ?, ?)
        ON CONFLICT(product_id, user_id) DO UPDATE SET
          used_count = product_user_usage.used_count + excluded.used_count,
          updated_at = CURRENT_TIMESTAMP
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      statement.setLong(2, 1L);
      statement.setInt(3, 2);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      statement.setLong(2, 1L);
      statement.setInt(3, 3);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement(
                 "SELECT used_count FROM product_user_usage WHERE product_id = ? AND user_id = 1")) {
      statement.setLong(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals(5, resultSet.getInt("used_count"));
      }
    }
  }

  private static void upsertMarketTag(Connection connection) throws Exception {
    String sql = """
        INSERT INTO market_tags (code, display_name, enabled, priority)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(code) DO UPDATE SET
          display_name = excluded.display_name,
          enabled = excluded.enabled,
          priority = excluded.priority
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "default");
      statement.setString(2, "Default");
      statement.setBoolean(3, true);
      statement.setInt(4, 100);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "default");
      statement.setString(2, "Default-2");
      statement.setBoolean(3, false);
      statement.setInt(4, 90);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT display_name, enabled FROM market_tags WHERE code = 'default'");
         ResultSet resultSet = statement.executeQuery()) {
      assertTrue(resultSet.next());
      assertEquals("Default-2", resultSet.getString("display_name"));
      assertEquals(false, resultSet.getBoolean("enabled"));
    }
  }

  private static void verifyDateSubEquivalentQuery(Connection connection) throws Exception {
    String sql =
        "SELECT server_id FROM player_presence WHERE mc_uuid = ? AND updated_at >= datetime(CURRENT_TIMESTAMP, '-' || ? || ' seconds') LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "00000000-0000-0000-0000-000000000001");
      statement.setInt(2, 120);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        assertEquals("s2", resultSet.getString("server_id"));
      }
    }
  }

  private static void seedUser(Connection connection, long userId) throws Exception {
    String sql =
        "INSERT INTO web_users (id, username, password_hash, password_salt, auth_state, bound_uuid) VALUES (?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, "tester");
      statement.setString(3, "hash");
      statement.setString(4, "salt");
      statement.setString(5, "ACTIVE");
      statement.setString(6, "00000000-0000-0000-0000-000000000001");
      statement.executeUpdate();
    }
  }

  private static void applySchema(Connection connection, String script) throws Exception {
    for (String statement : splitStatements(script)) {
      try (PreparedStatement preparedStatement = connection.prepareStatement(statement)) {
        preparedStatement.execute();
      }
    }
  }

  private static String readResource(String path) throws Exception {
    try (InputStream stream = SqliteBusinessSqlSmokeTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("Missing resource: " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> splitStatements(String script) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    for (int i = 0; i < script.length(); i++) {
      char ch = script.charAt(i);
      if (ch == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
        current.append(ch);
        continue;
      }
      if (ch == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
        current.append(ch);
        continue;
      }
      if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
        String statement = current.toString().trim();
        if (!statement.isBlank()) {
          statements.add(statement);
        }
        current.setLength(0);
        continue;
      }
      current.append(ch);
    }
    String tail = current.toString().trim();
    if (!tail.isBlank()) {
      statements.add(tail);
    }
    return statements;
  }
}
