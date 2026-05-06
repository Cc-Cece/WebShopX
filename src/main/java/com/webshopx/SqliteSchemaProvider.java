package com.webshopx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class SqliteSchemaProvider implements SchemaProvider {
  private static final String SCHEMA_RESOURCE = "/db/sqlite/schema.sql";
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

  @Override
  public void ensureSchema(DatabaseManager databaseManager, PluginSettings settings) {
    databaseManager.inTransaction(connection -> {
      executeSchemaScript(connection);
      migrateSchema(connection);
      verifySchemaByPragma(connection);
      return null;
    });
  }

  private void executeSchemaScript(Connection connection) throws SQLException {
    String script = loadSchemaScript();
    for (String statement : splitStatements(script)) {
      if (statement == null || statement.isBlank()) {
        continue;
      }
      execute(connection, statement);
    }
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  private String loadSchemaScript() {
    try (InputStream stream = SqliteSchemaProvider.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("SQLite schema resource is missing: " + SCHEMA_RESOURCE);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to load SQLite schema resource: " + SCHEMA_RESOURCE, exception);
    }
  }

  private List<String> splitStatements(String script) {
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

  private void verifySchemaByPragma(Connection connection) throws SQLException {
    assertTableExists(connection, "web_users");
    assertTableExists(connection, "runtime_config");
    assertTableExists(connection, "orders");
    assertTableExists(connection, "market_listings");

    assertColumnExists(connection, "web_users", "auth_state");
    assertColumnExists(connection, "runtime_config", "version");
    assertColumnExists(connection, "orders", "claim_token");
    assertColumnExists(connection, "market_listings", "source_mode");
    assertColumnExists(connection, "market_listings", "trade_mode");

    assertIndexExists(connection, "orders", "idx_orders_target_server");
    assertIndexExists(connection, "market_listings", "idx_market_listing_auction_due");
  }

  private void migrateSchema(Connection connection) throws SQLException {
    addColumnIfMissing(
      connection,
      "market_listings",
      "source_mode",
      "TEXT NOT NULL DEFAULT 'MANUAL'");
    addColumnIfMissing(connection, "market_listings", "supply_world", "TEXT NULL");
    addColumnIfMissing(connection, "market_listings", "supply_x", "INTEGER NULL");
    addColumnIfMissing(connection, "market_listings", "supply_y", "INTEGER NULL");
    addColumnIfMissing(connection, "market_listings", "supply_z", "INTEGER NULL");
    addColumnIfMissing(
      connection,
      "market_listings",
      "supply_batch_size",
      "INTEGER NULL");
    addColumnIfMissing(
      connection,
      "market_listings",
      "supply_max_stock",
      "INTEGER NULL");
    addColumnIfMissing(
      connection,
      "market_listings",
      "supply_loaded_total",
      "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(
      connection,
      "market_listings",
      "supply_sold_total",
      "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(
      connection,
      "market_listings",
      "supply_last_loaded_amount",
      "INTEGER NULL");
    addColumnIfMissing(
      connection,
      "market_listings",
      "supply_last_loaded_at",
      "DATETIME NULL");
  }

    private void addColumnIfMissing(
      Connection connection, String tableName, String columnName, String columnDefinition)
      throws SQLException {
    if (columnExistsByPragma(connection, tableName, columnName)) {
      return;
    }
    execute(
      connection,
      "ALTER TABLE " + requireSafeIdentifier(tableName)
        + " ADD COLUMN " + requireSafeIdentifier(columnName)
        + " " + columnDefinition);
    }

  private void assertTableExists(Connection connection, String tableName) throws SQLException {
    String sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return;
        }
      }
    }
    throw new IllegalStateException("SQLite schema validation failed: missing table " + tableName);
  }

  private void assertColumnExists(Connection connection, String tableName, String columnName)
      throws SQLException {
    if (columnExistsByPragma(connection, tableName, columnName)) {
      return;
    }
    throw new IllegalStateException(
        "SQLite schema validation failed: missing column "
            + tableName
            + "."
            + columnName);
  }

  private void assertIndexExists(Connection connection, String tableName, String indexName)
      throws SQLException {
    if (indexExistsByPragma(connection, tableName, indexName)) {
      return;
    }
    throw new IllegalStateException(
        "SQLite schema validation failed: missing index "
            + indexName
            + " on "
            + tableName);
  }

  private boolean columnExistsByPragma(Connection connection, String tableName, String columnName)
      throws SQLException {
    String normalizedTableName = requireSafeIdentifier(tableName);
    String sql = "PRAGMA table_info(" + normalizedTableName + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String currentName = resultSet.getString("name");
        if (columnName.equalsIgnoreCase(currentName)) {
          return true;
        }
      }
      return false;
    }
  }

  private boolean indexExistsByPragma(Connection connection, String tableName, String indexName)
      throws SQLException {
    String normalizedTableName = requireSafeIdentifier(tableName);
    String sql = "PRAGMA index_list(" + normalizedTableName + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String currentName = resultSet.getString("name");
        if (indexName.equalsIgnoreCase(currentName)) {
          return true;
        }
      }
      return false;
    }
  }

  private String requireSafeIdentifier(String name) {
    if (name != null && SAFE_IDENTIFIER.matcher(name).matches()) {
      return name;
    }
    throw new IllegalArgumentException("Unsafe identifier for PRAGMA query: " + name);
  }
}
