package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class SqliteSchemaManager implements DatabaseSchemaManager {
  @Override
  public void ensureSchema(DatabaseManager databaseManager) {
    databaseManager.withConnection(this::createTables);
  }

  private Void createTables(Connection connection) throws SQLException {
    execute(connection, "PRAGMA foreign_keys = ON");
    createWebUsers(connection);
    createWebAdmins(connection);
    createAdminAuditLogs(connection);
    createWebSessions(connection);
    createBindRequests(connection);
    createWallets(connection);
    createWalletLedger(connection);
    createRedeemCodes(connection);
    createRedeemUsage(connection);
    createProducts(connection);
    createOrders(connection);
    createOrderItems(connection);
    createDeliveryQueue(connection);
    createMarketListings(connection);
    createMarketTrades(connection);
    createMarketItemDeliveries(connection);
    createGroupBuyVouchers(connection);
    createUpdatedAtTriggers(connection);
    return null;
  }

  private void createWebUsers(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS web_users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          username TEXT NOT NULL,
          password_hash TEXT NOT NULL,
          password_salt TEXT NOT NULL,
          auth_state TEXT NOT NULL DEFAULT 'ACTIVE',
          bound_uuid TEXT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE (username),
          UNIQUE (bound_uuid)
        )
        """);
  }

  private void createWebAdmins(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS web_admins (
          user_id INTEGER PRIMARY KEY,
          role TEXT NOT NULL,
          active INTEGER NOT NULL DEFAULT 1,
          is_super_admin INTEGER NOT NULL DEFAULT 0,
          permissions_json TEXT NULL,
          template_key TEXT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_web_admins_role_active ON web_admins (role, active)");
  }

  private void createAdminAuditLogs(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS admin_audit_logs (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          admin_user_id INTEGER NOT NULL,
          admin_role TEXT NOT NULL,
          action TEXT NOT NULL,
          target_type TEXT NULL,
          target_id TEXT NULL,
          detail_json TEXT NULL,
          source_ip TEXT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (admin_user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_admin_audit_admin_time "
            + "ON admin_audit_logs (admin_user_id, created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_admin_audit_action_time "
            + "ON admin_audit_logs (action, created_at)");
  }

  private void createWebSessions(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS web_sessions (
          token TEXT PRIMARY KEY,
          user_id INTEGER NOT NULL,
          expires_at DATETIME NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_web_sessions_user_id ON web_sessions (user_id)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_web_sessions_expires_at ON web_sessions (expires_at)");
  }

  private void createBindRequests(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS bind_requests (
          bind_code TEXT PRIMARY KEY,
          user_id INTEGER NOT NULL,
          expires_at DATETIME NOT NULL,
          used INTEGER NOT NULL DEFAULT 0,
          used_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_bind_requests_user_id ON bind_requests (user_id)");
  }

  private void createWallets(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS wallets (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL UNIQUE,
          shop_coin INTEGER NOT NULL DEFAULT 0,
          game_coin INTEGER NOT NULL DEFAULT 0,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
  }

  private void createWalletLedger(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS wallet_ledger (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          wallet_id INTEGER NOT NULL,
          currency TEXT NOT NULL,
          delta INTEGER NOT NULL,
          biz_type TEXT NOT NULL,
          biz_id TEXT NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE (wallet_id, biz_type, biz_id),
          FOREIGN KEY (wallet_id) REFERENCES wallets(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_wallet_ledger_wallet_id ON wallet_ledger (wallet_id)");
  }

  private void createRedeemCodes(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS redeem_codes (
          code TEXT PRIMARY KEY,
          shop_coin INTEGER NOT NULL DEFAULT 0,
          game_coin INTEGER NOT NULL DEFAULT 0,
          max_uses INTEGER NOT NULL DEFAULT 1,
          per_user_max_uses INTEGER NOT NULL DEFAULT 1,
          used_count INTEGER NOT NULL DEFAULT 0,
          expires_at DATETIME NULL,
          active INTEGER NOT NULL DEFAULT 1,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """);
  }

  private void createRedeemUsage(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS redeem_usage (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          code TEXT NOT NULL,
          user_id INTEGER NOT NULL,
          use_count INTEGER NOT NULL DEFAULT 0,
          used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE (code, user_id),
          FOREIGN KEY (code) REFERENCES redeem_codes(code) ON DELETE CASCADE,
          FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
  }

  private void createProducts(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS products (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          sku TEXT NOT NULL UNIQUE,
          title TEXT NOT NULL,
          remark TEXT NULL,
          currency TEXT NOT NULL,
          price INTEGER NOT NULL,
          product_type TEXT NOT NULL DEFAULT 'COMMAND',
          command_template TEXT NOT NULL,
          item_material TEXT NULL,
          item_amount INTEGER NULL,
          stock_remaining INTEGER NULL,
          effect_type TEXT NULL,
          effect_seconds INTEGER NULL,
          effect_amplifier INTEGER NULL,
          publish_at DATETIME NULL,
          unpublish_at DATETIME NULL,
          active INTEGER NOT NULL DEFAULT 1,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """);
  }

  private void createOrders(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS orders (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          order_no TEXT NOT NULL UNIQUE,
          user_id INTEGER NOT NULL,
          mc_uuid TEXT NOT NULL,
          currency TEXT NOT NULL,
          total_amount INTEGER NOT NULL,
          status TEXT NOT NULL,
          idempotency_key TEXT NOT NULL,
          claim_token TEXT NULL UNIQUE,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          refund_deadline DATETIME NULL,
          delivered_at DATETIME NULL,
          refunded_at DATETIME NULL,
          UNIQUE (user_id, idempotency_key),
          FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
    execute(connection, "CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders (user_id)");
  }

  private void createOrderItems(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS order_items (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          order_id INTEGER NOT NULL,
          product_id INTEGER NOT NULL,
          quantity INTEGER NOT NULL,
          unit_price INTEGER NOT NULL,
          UNIQUE (order_id, product_id),
          FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
          FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT
        )
        """);
  }

  private void createDeliveryQueue(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS delivery_queue (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          order_id INTEGER NOT NULL,
          item_id INTEGER NOT NULL,
          mc_uuid TEXT NOT NULL,
          command_text TEXT NOT NULL,
          delivery_kind TEXT NOT NULL DEFAULT 'COMMAND',
          payload_json TEXT NULL,
          manual_claim INTEGER NOT NULL DEFAULT 0,
          quantity INTEGER NOT NULL,
          status TEXT NOT NULL DEFAULT 'PENDING',
          retry_count INTEGER NOT NULL DEFAULT 0,
          last_error TEXT NULL,
          next_retry_at DATETIME NOT NULL,
          delivered_at DATETIME NULL,
          claimed_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE (order_id, item_id),
          FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
          FOREIGN KEY (item_id) REFERENCES order_items(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_delivery_due ON delivery_queue (status, next_retry_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_delivery_claim "
            + "ON delivery_queue (mc_uuid, status, created_at)");
  }

  private void createMarketListings(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS market_listings (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          seller_user_id INTEGER NOT NULL,
          buyer_user_id INTEGER NULL,
          seller_uuid TEXT NOT NULL,
          buyer_uuid TEXT NULL,
          currency TEXT NOT NULL,
          price INTEGER NOT NULL,
          quantity INTEGER NOT NULL,
          quantity_total INTEGER NOT NULL DEFAULT 0,
          item_material TEXT NOT NULL,
          raw_item_blob BLOB NOT NULL,
          item_meta_json TEXT NOT NULL,
          remark TEXT NULL,
          item_hash TEXT NOT NULL,
          source_mode TEXT NOT NULL DEFAULT 'MANUAL',
          supply_world TEXT NULL,
          supply_x INTEGER NULL,
          supply_y INTEGER NULL,
          supply_z INTEGER NULL,
          supply_batch_size INTEGER NULL,
          supply_max_stock INTEGER NULL,
          supply_loaded_total INTEGER NOT NULL DEFAULT 0,
          supply_sold_total INTEGER NOT NULL DEFAULT 0,
          supply_last_loaded_amount INTEGER NULL,
          supply_last_loaded_at DATETIME NULL,
          status TEXT NOT NULL DEFAULT 'ACTIVE',
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          sold_at DATETIME NULL,
          unlisted_at DATETIME NULL,
          paused_at DATETIME NULL,
          FOREIGN KEY (seller_user_id) REFERENCES web_users(id) ON DELETE CASCADE,
          FOREIGN KEY (buyer_user_id) REFERENCES web_users(id) ON DELETE SET NULL
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_listing_status "
            + "ON market_listings (status, created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_listing_seller "
            + "ON market_listings (seller_user_id, status)");
  }

  private void createMarketTrades(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS market_trades (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          listing_id INTEGER NOT NULL,
          buyer_user_id INTEGER NOT NULL,
          seller_user_id INTEGER NOT NULL,
          currency TEXT NOT NULL,
          unit_price INTEGER NOT NULL DEFAULT 0,
          quantity INTEGER NOT NULL DEFAULT 1,
          total_price INTEGER NOT NULL,
          buyer_total INTEGER NOT NULL DEFAULT 0,
          seller_receive INTEGER NOT NULL DEFAULT 0,
          fee_amount INTEGER NOT NULL DEFAULT 0,
          tax_amount INTEGER NOT NULL DEFAULT 0,
          idempotency_key TEXT NOT NULL,
          claim_token TEXT NULL UNIQUE,
          status TEXT NOT NULL DEFAULT 'PENDING',
          refund_deadline DATETIME NULL,
          refunded_at DATETIME NULL,
          settled_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE (buyer_user_id, idempotency_key),
          FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
          FOREIGN KEY (buyer_user_id) REFERENCES web_users(id) ON DELETE CASCADE,
          FOREIGN KEY (seller_user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_trade_listing_time "
            + "ON market_trades (listing_id, created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_trade_buyer "
            + "ON market_trades (buyer_user_id, created_at)");
  }

  private void createMarketItemDeliveries(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS market_item_deliveries (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          listing_id INTEGER NOT NULL,
          trade_id INTEGER NULL,
          target_user_id INTEGER NOT NULL,
          target_uuid TEXT NOT NULL,
          item_blob BLOB NOT NULL,
          quantity INTEGER NOT NULL,
          delivery_type TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'PENDING',
          retry_count INTEGER NOT NULL DEFAULT 0,
          last_error TEXT NULL,
          next_retry_at DATETIME NOT NULL,
          delivered_at DATETIME NULL,
          claimed_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
          FOREIGN KEY (trade_id) REFERENCES market_trades(id) ON DELETE SET NULL,
          FOREIGN KEY (target_user_id) REFERENCES web_users(id) ON DELETE CASCADE
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_delivery_trade_type "
            + "ON market_item_deliveries (trade_id, delivery_type)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_delivery_listing_type "
            + "ON market_item_deliveries (listing_id, delivery_type)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_delivery_due "
            + "ON market_item_deliveries (status, next_retry_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_market_delivery_claim "
            + "ON market_item_deliveries (target_uuid, status, created_at)");
  }

  private void createGroupBuyVouchers(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TABLE IF NOT EXISTS group_buy_vouchers (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          code TEXT NOT NULL UNIQUE,
          order_id INTEGER NOT NULL UNIQUE,
          user_id INTEGER NOT NULL,
          product_id INTEGER NOT NULL,
          status TEXT NOT NULL DEFAULT 'ISSUED',
          consumed_by_admin_id INTEGER NULL,
          consumed_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
          FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE,
          FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
          FOREIGN KEY (consumed_by_admin_id) REFERENCES web_users(id) ON DELETE SET NULL
        )
        """);
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_group_buy_voucher_status_time "
            + "ON group_buy_vouchers (status, created_at)");
  }

  private void createUpdatedAtTriggers(Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TRIGGER IF NOT EXISTS trg_web_admins_updated_at
        AFTER UPDATE ON web_admins
        FOR EACH ROW
        WHEN NEW.updated_at = OLD.updated_at
        BEGIN
          UPDATE web_admins
          SET updated_at = CURRENT_TIMESTAMP
          WHERE rowid = NEW.rowid;
        END
        """);
    execute(
        connection,
        """
        CREATE TRIGGER IF NOT EXISTS trg_wallets_updated_at
        AFTER UPDATE ON wallets
        FOR EACH ROW
        WHEN NEW.updated_at = OLD.updated_at
        BEGIN
          UPDATE wallets
          SET updated_at = CURRENT_TIMESTAMP
          WHERE rowid = NEW.rowid;
        END
        """);
    execute(
        connection,
        """
        CREATE TRIGGER IF NOT EXISTS trg_products_updated_at
        AFTER UPDATE ON products
        FOR EACH ROW
        WHEN NEW.updated_at = OLD.updated_at
        BEGIN
          UPDATE products
          SET updated_at = CURRENT_TIMESTAMP
          WHERE rowid = NEW.rowid;
        END
        """);
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Schema DDL is defined by the plugin and not influenced by user input")
  private void execute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }
}
