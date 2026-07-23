package com.webshopx;

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

class SqliteSchemaScriptTest {
  @Test
  void schemaScriptShouldCreateExpectedTablesAndIndexes() throws Exception {
    Path dbFile = Files.createTempFile("webshopx-schema-", ".db");
    Files.deleteIfExists(dbFile);
    Class.forName("org.sqlite.JDBC");

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
      String script = readResource("/db/sqlite/schema.sql");
      for (String statement : splitStatements(script)) {
        try (PreparedStatement preparedStatement = connection.prepareStatement(statement)) {
          preparedStatement.execute();
        }
      }

      assertTrue(tableExists(connection, "web_users"));
      assertTrue(tableExists(connection, "runtime_config"));
      assertTrue(tableExists(connection, "market_listings"));
      assertTrue(tableExists(connection, "market_tags"));
      assertTrue(tableExists(connection, "market_listing_tags"));
      assertTrue(tableExists(connection, "visual_packs"));
      assertTrue(columnExists(connection, "runtime_config", "version"));
      assertTrue(columnExists(connection, "market_listings", "auction_algorithm"));
      assertTrue(columnExists(connection, "market_listings", "supply_access_protected"));
      assertTrue(indexExists(connection, "orders", "idx_orders_target_server"));
      assertTrue(indexExists(connection, "market_listings", "idx_market_listing_auction_due"));
      assertTrue(indexExists(connection, "market_listings", "idx_market_supply_location"));
      assertTrue(indexExists(connection, "market_listing_tags", "idx_market_listing_tags_tag"));
      assertTrue(indexExists(connection, "visual_packs", "idx_visual_packs_order"));
    } finally {
      Files.deleteIfExists(dbFile);
    }
  }

  private static String readResource(String path) throws Exception {
    try (InputStream stream = SqliteSchemaScriptTest.class.getResourceAsStream(path)) {
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

  private static boolean tableExists(Connection connection, String tableName) throws Exception {
    String sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private static boolean columnExists(Connection connection, String tableName, String columnName)
      throws Exception {
    String sql = "PRAGMA table_info(" + tableName + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String current = resultSet.getString("name");
        if (columnName.equalsIgnoreCase(current)) {
          return true;
        }
      }
      return false;
    }
  }

  private static boolean indexExists(Connection connection, String tableName, String indexName)
      throws Exception {
    String sql = "PRAGMA index_list(" + tableName + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String current = resultSet.getString("name");
        if (indexName.equalsIgnoreCase(current)) {
          return true;
        }
      }
      return false;
    }
  }
}
