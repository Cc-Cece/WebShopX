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
  private static final String MARKET_TAG_SCHEMA_V2_KEY = "market_tag_schema_v2";
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
    assertTableExists(connection, "market_tags");
    assertTableExists(connection, "market_listing_tags");
    assertTableExists(connection, "webshopx_recharge_order");
    assertTableExists(connection, "visual_packs");
    assertTableExists(connection, "official_item_snapshots");
    assertTableExists(connection, "product_item_snapshots");
    assertTableExists(connection, "commerce_carts");
    assertTableExists(connection, "promotion_campaigns");
    assertTableExists(connection, "coupon_templates");
    assertTableExists(connection, "membership_plans");
    assertTableExists(connection, "checkout_quotes");
    assertTableExists(connection, "checkout_orders");
    assertTableExists(connection, "checkout_discounts");
    assertTableExists(connection, "checkout_discount_allocations");
    assertTableExists(connection, "checkout_funding_shares");
    assertTableExists(connection, "checkout_line_payment_units");
    assertTableExists(connection, "commerce_refund_adjustments");
    assertTableExists(connection, "membership_codes");
    assertTableExists(connection, "membership_product_bindings");
    assertTableExists(connection, "benefit_grants");

    assertColumnExists(connection, "web_users", "auth_state");
    assertColumnExists(connection, "products", "snapshot_id");
    assertColumnExists(connection, "products", "inventory_mode");
    assertColumnExists(connection, "runtime_config", "version");
    assertColumnExists(connection, "orders", "claim_token");
    assertColumnExists(connection, "orders", "refund_allowed");
    assertColumnExists(connection, "products", "refund_policy");
    assertColumnExists(connection, "delivery_queue", "delivered_quantity");
    assertColumnExists(connection, "market_listings", "source_mode");
    assertColumnExists(connection, "market_listings", "supply_access_protected");
    assertColumnExists(connection, "market_listings", "trade_mode");
    assertColumnExists(connection, "market_item_deliveries", "delivered_quantity");
    assertColumnExists(connection, "mailbox_items", "delivered_quantity");
    assertColumnExists(connection, "webshopx_recharge_order", "provider_order_id");

    assertIndexExists(connection, "orders", "idx_orders_target_server");
    assertIndexExists(connection, "market_listings", "idx_market_listing_auction_due");
    assertIndexExists(connection, "market_listings", "idx_market_supply_location");
    assertIndexExists(connection, "market_listing_tags", "idx_market_listing_tags_tag");
    assertIndexExists(connection, "webshopx_recharge_order", "uniq_recharge_order_id");
    assertIndexExists(connection, "visual_packs", "idx_visual_packs_order");
  }

  private void migrateSchema(Connection connection) throws SQLException {
    migrateOfficialItemSnapshots(connection);
    addColumnIfMissing(connection, "products", "snapshot_id", "INTEGER NULL");
    addColumnIfMissing(
        connection, "products", "inventory_mode", "TEXT NOT NULL DEFAULT 'TEMPLATE'");
    execute(
        connection,
        """
        INSERT INTO product_item_snapshots (
          product_id, snapshot_id, version, item_hash, created_by
        )
        SELECT p.id, p.snapshot_id, 1, s.item_hash, NULL
        FROM products p
        JOIN official_item_snapshots s ON s.id = p.snapshot_id
        WHERE p.snapshot_id IS NOT NULL
          AND NOT EXISTS (
            SELECT 1 FROM product_item_snapshots v WHERE v.product_id = p.id
          )
        """);
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
      "supply_access_protected",
      "INTEGER NOT NULL DEFAULT 1");
    execute(
      connection,
      "CREATE INDEX IF NOT EXISTS idx_market_supply_location "
        + "ON market_listings (source_mode, status, supply_world, supply_x, supply_y, supply_z)");
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
    addColumnIfMissing(
      connection,
      "products",
      "dynamic_pricing_mode",
      "TEXT NOT NULL DEFAULT 'ORDER_FIXED'");
    addColumnIfMissing(
      connection, "products", "refund_policy", "TEXT NOT NULL DEFAULT 'INHERIT'");
    addColumnIfMissing(connection, "products", "refund_window_minutes", "INTEGER NULL");
    addColumnIfMissing(
      connection, "products", "partial_refund_policy", "TEXT NOT NULL DEFAULT 'INHERIT'");
    addColumnIfMissing(connection, "orders", "refund_allowed", "INTEGER NOT NULL DEFAULT 1");
    addColumnIfMissing(
      connection, "orders", "partial_refund_allowed", "INTEGER NOT NULL DEFAULT 1");
    addColumnIfMissing(connection, "orders", "refund_policy_json", "TEXT NULL");
    addColumnIfMissing(connection, "orders", "refunded_quantity", "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(connection, "orders", "refunded_amount", "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(
      connection, "market_trades", "refund_allowed", "INTEGER NOT NULL DEFAULT 1");
    addColumnIfMissing(
      connection, "market_trades", "partial_refund_allowed", "INTEGER NOT NULL DEFAULT 1");
    addColumnIfMissing(connection, "market_trades", "refund_policy_json", "TEXT NULL");
    addColumnIfMissing(
      connection, "market_trades", "refunded_quantity", "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(
      connection, "market_trades", "refunded_amount", "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(
      connection,
      "market_listings",
      "dynamic_pricing_mode",
      "TEXT NOT NULL DEFAULT 'ORDER_FIXED'");
    addColumnIfMissing(
      connection,
      "delivery_queue",
      "delivered_quantity",
      "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(
      connection,
      "market_item_deliveries",
      "delivered_quantity",
      "INTEGER NOT NULL DEFAULT 0");
    addColumnIfMissing(
      connection,
      "mailbox_items",
      "delivered_quantity",
      "INTEGER NOT NULL DEFAULT 0");
    resetMarketTagsV2IfNeeded(connection);
  }

  private void migrateOfficialItemSnapshots(Connection connection) throws SQLException {
    if (!columnExistsByPragma(connection, "official_item_snapshots", "id")) {
      execute(connection, "ALTER TABLE official_item_snapshots ADD COLUMN id INTEGER NULL");
    }
    // SQLite validates child foreign keys when the legacy table is updated.
    // The parent key therefore has to be unique before existing rows are backfilled.
    execute(
        connection,
        "CREATE UNIQUE INDEX IF NOT EXISTS uniq_official_item_snapshot_id "
            + "ON official_item_snapshots (id)");
    execute(connection, "UPDATE official_item_snapshots SET id = rowid WHERE id IS NULL");
    execute(
        connection,
        """
        CREATE TRIGGER IF NOT EXISTS trg_official_item_snapshot_assign_id
        AFTER INSERT ON official_item_snapshots
        FOR EACH ROW
        WHEN NEW.id IS NULL
        BEGIN
          UPDATE official_item_snapshots SET id = NEW.rowid WHERE rowid = NEW.rowid;
        END
        """);
  }

  private void resetMarketTagsV2IfNeeded(Connection connection) throws SQLException {
    if (metaValueExists(connection, MARKET_TAG_SCHEMA_V2_KEY)) {
      return;
    }
    execute(connection, "DELETE FROM market_listing_tags");
    execute(connection, "DELETE FROM market_tags");
    execute(connection, "DELETE FROM runtime_config WHERE config_key = 'market_tags'");
    execute(
        connection,
        "INSERT INTO market_tags (code, display_name, enabled, priority) "
            + "VALUES ('default', 'Default', 1, 2147483647)");
    execute(
        connection,
        "UPDATE market_listings SET tag_code = 'default', tag_version = 1");
    execute(
        connection,
        "INSERT INTO market_listing_tags (listing_id, tag_code, source, position) "
            + "SELECT id, 'default', 'SYSTEM', 0 FROM market_listings");
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO webshop_meta (meta_key, meta_value) VALUES (?, ?) "
            + "ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value")) {
      statement.setString(1, MARKET_TAG_SCHEMA_V2_KEY);
      statement.setString(2, "2");
      statement.executeUpdate();
    }
  }

  private boolean metaValueExists(Connection connection, String key) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT 1 FROM webshop_meta WHERE meta_key = ? LIMIT 1")) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
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
