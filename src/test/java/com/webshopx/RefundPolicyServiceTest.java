package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundPolicyServiceTest {
  @Test
  void productPolicyAndOrderSnapshotShouldRemainFrozen() throws Exception {
    Path dbFile = Files.createTempFile("webshopx-refund-policy-", ".db");
    Files.deleteIfExists(dbFile);
    Class.forName("org.sqlite.JDBC");
    try (Connection connection =
        DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
      applySchema(connection);
      seedOrder(connection);
    }
    DatabaseManager databaseManager = new DatabaseManager(
        null,
        new DatabaseSettings(
            DbType.SQLITE,
            "",
            0,
            "",
            "",
            "",
            false,
            false,
            "",
            1,
            dbFile.toString(),
            "WAL",
            "NORMAL",
            5000,
            3,
            List.of(10, 25, 50)));
    databaseManager.start();
    try {
      RefundPolicyService service = new RefundPolicyService(databaseManager);
      RefundPolicyService.Policy defaults = service.getPolicy();
      assertTrue(defaults.selfServiceEnabled());
      assertTrue(defaults.mailboxPendingRefundEnabled());
      assertEquals(10, defaults.fixedPriceWindowMinutes());
      assertEquals(3, defaults.dynamicPriceWindowMinutes());

      LocalDateTime createdAt = LocalDateTime.of(2026, 7, 28, 12, 0);
      databaseManager.inTransaction(connection -> {
        RefundPolicyService.ResolvedPolicy resolved =
            service.resolveProductPolicy(connection, 1L, false, "GIVE_ITEM");
        assertTrue(resolved.allowed());
        assertTrue(resolved.partialAllowed());
        assertEquals(10, resolved.windowMinutes());
        service.freezeOrderPolicy(connection, 1L, 1L, false, "GIVE_ITEM", createdAt);
        return null;
      });

      service.updatePolicy(new RefundPolicyService.Policy(false, false, null, null, false, 0));

      databaseManager.withConnection(connection -> {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT refund_allowed, partial_refund_allowed, refund_deadline "
                + "FROM orders WHERE id = 1")) {
          try (ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getBoolean("refund_allowed"));
            assertTrue(resultSet.getBoolean("partial_refund_allowed"));
            assertEquals(
                createdAt.plusMinutes(10),
                resultSet.getTimestamp("refund_deadline").toLocalDateTime());
          }
        }
        RefundPolicyService.ResolvedPolicy disabled =
            service.resolveProductPolicy(connection, 1L, false, "GIVE_ITEM");
        assertFalse(disabled.allowed());
        assertNull(disabled.windowMinutes());
        return null;
      });
    } finally {
      databaseManager.close();
      Files.deleteIfExists(dbFile);
    }
  }

  private static void seedOrder(Connection connection) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO web_users (id, username, password_hash, password_salt, bound_uuid) "
            + "VALUES (1, 'tester', 'hash', 'salt', "
            + "'00000000-0000-0000-0000-000000000001')")) {
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO products "
            + "(id, sku, title, currency, price, product_type, command_template) "
            + "VALUES (1, 'DIAMOND', 'Diamond', 'SHOP_COIN', 100, 'GIVE_ITEM', 'give')")) {
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO orders "
            + "(id, order_no, user_id, mc_uuid, currency, total_amount, status, idempotency_key) "
            + "VALUES (1, 'ORD-1', 1, '00000000-0000-0000-0000-000000000001', "
            + "'SHOP_COIN', 100, 'WAIT_CLAIM', 'buy-1')")) {
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO order_items (order_id, product_id, quantity, unit_price) "
            + "VALUES (1, 1, 1, 100)")) {
      statement.executeUpdate();
    }
  }

  private static void applySchema(Connection connection) throws Exception {
    try (InputStream stream =
        RefundPolicyServiceTest.class.getResourceAsStream("/db/sqlite/schema.sql")) {
      if (stream == null) {
        throw new IllegalStateException("Missing SQLite schema");
      }
      String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      for (String sql : splitStatements(script)) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
          statement.execute();
        }
      }
    }
  }

  private static List<String> splitStatements(String script) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean single = false;
    boolean doubleQuoted = false;
    for (int index = 0; index < script.length(); index++) {
      char value = script.charAt(index);
      if (value == '\'' && !doubleQuoted) {
        single = !single;
      } else if (value == '"' && !single) {
        doubleQuoted = !doubleQuoted;
      }
      if (value == ';' && !single && !doubleQuoted) {
        if (!current.toString().isBlank()) {
          statements.add(current.toString().trim());
        }
        current.setLength(0);
      } else {
        current.append(value);
      }
    }
    if (!current.toString().isBlank()) {
      statements.add(current.toString().trim());
    }
    return statements;
  }
}
