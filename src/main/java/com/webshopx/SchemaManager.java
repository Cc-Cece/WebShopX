package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

class SchemaManager {

  void ensureSchema(DatabaseManager databaseManager) {
    databaseManager.withConnection(this::createTables);
  }

  private Void createTables(Connection connection) throws SQLException {
    createWebUsers(connection);
    migrateWebUsers(connection);
    createWebAdmins(connection);
    createAdminAuditLogs(connection);
    createWebSessions(connection);
    createBindRequests(connection);
    createWallets(connection);
    createWalletLedger(connection);
    createRedeemCodes(connection);
    createRedeemUsage(connection);
    migrateRedeemCodes(connection);
    migrateRedeemUsage(connection);
    createProducts(connection);
    migrateProducts(connection);
    createOrders(connection);
    migrateOrders(connection);
    createOrderItems(connection);
    createDeliveryQueue(connection);
    migrateDeliveryQueue(connection);
    createMarketListings(connection);
    migrateMarketListings(connection);
    createMarketTrades(connection);
    migrateMarketTrades(connection);
    createMarketItemDeliveries(connection);
    migrateMarketItemDeliveries(connection);
    createGroupBuyVouchers(connection);
    return null;
  }

  private void createWebUsers(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS web_users (
          id BIGINT NOT NULL AUTO_INCREMENT,
          username VARCHAR(32) NOT NULL,
          password_hash VARCHAR(255) NOT NULL,
          password_salt VARCHAR(255) NOT NULL,
          auth_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
          bound_uuid CHAR(36) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_username (username),
          UNIQUE KEY uniq_bound_uuid (bound_uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateWebUsers(Connection connection) throws SQLException {
    if (!columnExists(connection, "web_users", "auth_state")) {
      String alterSql = "ALTER TABLE web_users "
          + "ADD COLUMN auth_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' "
          + "AFTER password_salt";
      execute(connection, alterSql);
    }
    String fillSql = "UPDATE web_users SET auth_state = 'ACTIVE' "
        + "WHERE auth_state IS NULL OR auth_state = ''";
    execute(connection, fillSql);
  }

  private void createWebSessions(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS web_sessions (
          token VARCHAR(96) NOT NULL,
          user_id BIGINT NOT NULL,
          expires_at DATETIME NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (token),
          KEY idx_web_sessions_user_id (user_id),
          KEY idx_web_sessions_expires_at (expires_at),
          CONSTRAINT fk_web_sessions_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createWebAdmins(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS web_admins (
          user_id BIGINT NOT NULL,
          role VARCHAR(32) NOT NULL,
          active BOOLEAN NOT NULL DEFAULT TRUE,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (user_id),
          KEY idx_web_admins_role_active (role, active),
          CONSTRAINT fk_web_admins_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createAdminAuditLogs(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS admin_audit_logs (
          id BIGINT NOT NULL AUTO_INCREMENT,
          admin_user_id BIGINT NOT NULL,
          admin_role VARCHAR(32) NOT NULL,
          action VARCHAR(64) NOT NULL,
          target_type VARCHAR(32) NULL,
          target_id VARCHAR(96) NULL,
          detail_json JSON NULL,
          source_ip VARCHAR(64) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          KEY idx_admin_audit_admin_time (admin_user_id, created_at),
          KEY idx_admin_audit_action_time (action, created_at),
          CONSTRAINT fk_admin_audit_admin_user
            FOREIGN KEY (admin_user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createBindRequests(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS bind_requests (
          bind_code VARCHAR(16) NOT NULL,
          user_id BIGINT NOT NULL,
          expires_at DATETIME NOT NULL,
          used BOOLEAN NOT NULL DEFAULT FALSE,
          used_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (bind_code),
          KEY idx_bind_requests_user_id (user_id),
          CONSTRAINT fk_bind_requests_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createWallets(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS wallets (
          id BIGINT NOT NULL AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          shop_coin BIGINT NOT NULL DEFAULT 0,
          game_coin BIGINT NOT NULL DEFAULT 0,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_wallet_user_id (user_id),
          CONSTRAINT fk_wallets_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createWalletLedger(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS wallet_ledger (
          id BIGINT NOT NULL AUTO_INCREMENT,
          wallet_id BIGINT NOT NULL,
          currency VARCHAR(16) NOT NULL,
          delta BIGINT NOT NULL,
          biz_type VARCHAR(32) NOT NULL,
          biz_id VARCHAR(96) NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_wallet_biz (wallet_id, biz_type, biz_id),
          KEY idx_wallet_ledger_wallet_id (wallet_id),
          CONSTRAINT fk_wallet_ledger_wallet_id
            FOREIGN KEY (wallet_id) REFERENCES wallets(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createRedeemCodes(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS redeem_codes (
          code VARCHAR(32) NOT NULL,
          shop_coin BIGINT NOT NULL DEFAULT 0,
          game_coin BIGINT NOT NULL DEFAULT 0,
          max_uses INT NOT NULL DEFAULT 1,
          per_user_max_uses INT NOT NULL DEFAULT 1,
          used_count INT NOT NULL DEFAULT 0,
          expires_at DATETIME NULL,
          active BOOLEAN NOT NULL DEFAULT TRUE,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (code)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createRedeemUsage(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS redeem_usage (
          id BIGINT NOT NULL AUTO_INCREMENT,
          code VARCHAR(32) NOT NULL,
          user_id BIGINT NOT NULL,
          use_count INT NOT NULL DEFAULT 0,
          used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_redeem_usage (code, user_id),
          CONSTRAINT fk_redeem_usage_code
            FOREIGN KEY (code) REFERENCES redeem_codes(code) ON DELETE CASCADE,
          CONSTRAINT fk_redeem_usage_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateRedeemCodes(Connection connection) throws SQLException {
    if (!columnExists(connection, "redeem_codes", "per_user_max_uses")) {
      execute(
          connection,
          "ALTER TABLE redeem_codes ADD COLUMN per_user_max_uses INT NOT NULL DEFAULT 1 AFTER max_uses");
    }
    execute(
        connection,
        "UPDATE redeem_codes SET per_user_max_uses = 1 WHERE per_user_max_uses IS NULL OR per_user_max_uses < 1");
  }

  private void migrateRedeemUsage(Connection connection) throws SQLException {
    if (!columnExists(connection, "redeem_usage", "use_count")) {
      execute(
          connection,
          "ALTER TABLE redeem_usage ADD COLUMN use_count INT NOT NULL DEFAULT 0 AFTER user_id");
    }
    execute(
        connection,
        "UPDATE redeem_usage SET use_count = 1 WHERE use_count IS NULL OR use_count < 1");
  }

  private void createProducts(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS products (
          id BIGINT NOT NULL AUTO_INCREMENT,
          sku VARCHAR(64) NOT NULL,
          title VARCHAR(128) NOT NULL,
          remark TEXT NULL,
          currency VARCHAR(16) NOT NULL,
          price BIGINT NOT NULL,
          product_type VARCHAR(24) NOT NULL DEFAULT 'COMMAND',
          command_template TEXT NOT NULL,
          item_material VARCHAR(64) NULL,
          item_amount INT NULL,
          effect_type VARCHAR(64) NULL,
          effect_seconds INT NULL,
          effect_amplifier INT NULL,
          publish_at DATETIME NULL,
          unpublish_at DATETIME NULL,
          active BOOLEAN NOT NULL DEFAULT TRUE,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_products_sku (sku)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateProducts(Connection connection) throws SQLException {
    if (!columnExists(connection, "products", "product_type")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN product_type VARCHAR(24) NOT NULL DEFAULT 'COMMAND' AFTER price");
    }
    if (!columnExists(connection, "products", "remark")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN remark TEXT NULL AFTER title");
    }
    if (!columnExists(connection, "products", "item_material")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN item_material VARCHAR(64) NULL AFTER command_template");
    }
    if (!columnExists(connection, "products", "item_amount")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN item_amount INT NULL AFTER item_material");
    }
    if (!columnExists(connection, "products", "effect_type")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN effect_type VARCHAR(64) NULL AFTER item_amount");
    }
    if (!columnExists(connection, "products", "effect_seconds")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN effect_seconds INT NULL AFTER effect_type");
    }
    if (!columnExists(connection, "products", "effect_amplifier")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN effect_amplifier INT NULL AFTER effect_seconds");
    }
    if (!columnExists(connection, "products", "publish_at")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN publish_at DATETIME NULL AFTER effect_amplifier");
    }
    if (!columnExists(connection, "products", "unpublish_at")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN unpublish_at DATETIME NULL AFTER publish_at");
    }
    execute(
        connection,
        "UPDATE products SET product_type = 'COMMAND' "
            + "WHERE product_type IS NULL OR product_type = ''");
  }

  private void createOrders(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS orders (
          id BIGINT NOT NULL AUTO_INCREMENT,
          order_no VARCHAR(48) NOT NULL,
          user_id BIGINT NOT NULL,
          mc_uuid CHAR(36) NOT NULL,
          currency VARCHAR(16) NOT NULL,
          total_amount BIGINT NOT NULL,
          status VARCHAR(24) NOT NULL,
          idempotency_key VARCHAR(96) NOT NULL,
          claim_token VARCHAR(64) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          refund_deadline DATETIME NULL,
          delivered_at DATETIME NULL,
          refunded_at DATETIME NULL,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_orders_order_no (order_no),
          UNIQUE KEY uniq_orders_idempotency (user_id, idempotency_key),
          UNIQUE KEY uniq_orders_claim_token (claim_token),
          KEY idx_orders_user_id (user_id),
          CONSTRAINT fk_orders_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateOrders(Connection connection) throws SQLException {
    if (!columnExists(connection, "orders", "refund_deadline")) {
      execute(
          connection,
          "ALTER TABLE orders ADD COLUMN refund_deadline DATETIME NULL AFTER created_at");
    }
    if (!columnExists(connection, "orders", "refunded_at")) {
      execute(
          connection,
          "ALTER TABLE orders ADD COLUMN refunded_at DATETIME NULL AFTER delivered_at");
    }
    if (!columnExists(connection, "orders", "claim_token")) {
      execute(
          connection,
          "ALTER TABLE orders ADD COLUMN claim_token VARCHAR(64) NULL AFTER idempotency_key");
    }
    if (!indexExists(connection, "orders", "uniq_orders_claim_token")) {
      execute(
          connection,
          "ALTER TABLE orders ADD UNIQUE KEY uniq_orders_claim_token (claim_token)");
    }
  }

  private void createOrderItems(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS order_items (
          id BIGINT NOT NULL AUTO_INCREMENT,
          order_id BIGINT NOT NULL,
          product_id BIGINT NOT NULL,
          quantity INT NOT NULL,
          unit_price BIGINT NOT NULL,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_order_product (order_id, product_id),
          CONSTRAINT fk_order_items_order_id
            FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
          CONSTRAINT fk_order_items_product_id
            FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createDeliveryQueue(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS delivery_queue (
          id BIGINT NOT NULL AUTO_INCREMENT,
          order_id BIGINT NOT NULL,
          item_id BIGINT NOT NULL,
          mc_uuid CHAR(36) NOT NULL,
          command_text TEXT NOT NULL,
          delivery_kind VARCHAR(24) NOT NULL DEFAULT 'COMMAND',
          payload_json JSON NULL,
          manual_claim BOOLEAN NOT NULL DEFAULT FALSE,
          quantity INT NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
          retry_count INT NOT NULL DEFAULT 0,
          last_error VARCHAR(255) NULL,
          next_retry_at DATETIME NOT NULL,
          delivered_at DATETIME NULL,
          claimed_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_delivery_order_item (order_id, item_id),
          KEY idx_delivery_due (status, next_retry_at),
          KEY idx_delivery_claim (mc_uuid, status, created_at),
          CONSTRAINT fk_delivery_order_id
            FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
          CONSTRAINT fk_delivery_item_id
            FOREIGN KEY (item_id) REFERENCES order_items(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateDeliveryQueue(Connection connection) throws SQLException {
    if (!columnExists(connection, "delivery_queue", "delivery_kind")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD COLUMN delivery_kind VARCHAR(24) NOT NULL DEFAULT 'COMMAND' AFTER command_text");
    }
    if (!columnExists(connection, "delivery_queue", "payload_json")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD COLUMN payload_json JSON NULL AFTER delivery_kind");
    }
    if (!columnExists(connection, "delivery_queue", "manual_claim")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD COLUMN manual_claim BOOLEAN NOT NULL DEFAULT FALSE AFTER payload_json");
    }
    if (!columnExists(connection, "delivery_queue", "claimed_at")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD COLUMN claimed_at DATETIME NULL AFTER delivered_at");
    }
    if (!indexExists(connection, "delivery_queue", "idx_delivery_claim")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD INDEX idx_delivery_claim (mc_uuid, status, created_at)");
    }
  }

  private void createMarketListings(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS market_listings (
          id BIGINT NOT NULL AUTO_INCREMENT,
          seller_user_id BIGINT NOT NULL,
          buyer_user_id BIGINT NULL,
          seller_uuid CHAR(36) NOT NULL,
          buyer_uuid CHAR(36) NULL,
          currency VARCHAR(16) NOT NULL,
          price BIGINT NOT NULL,
          quantity INT NOT NULL,
          quantity_total INT NOT NULL DEFAULT 0,
          item_material VARCHAR(64) NOT NULL,
          raw_item_blob LONGBLOB NOT NULL,
          item_meta_json JSON NOT NULL,
          remark TEXT NULL,
          item_hash VARCHAR(64) NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          sold_at DATETIME NULL,
          unlisted_at DATETIME NULL,
          PRIMARY KEY (id),
          KEY idx_market_listing_status (status, created_at),
          KEY idx_market_listing_seller (seller_user_id, status),
          CONSTRAINT fk_market_listing_seller
            FOREIGN KEY (seller_user_id) REFERENCES web_users(id) ON DELETE CASCADE,
          CONSTRAINT fk_market_listing_buyer
            FOREIGN KEY (buyer_user_id) REFERENCES web_users(id) ON DELETE SET NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateMarketListings(Connection connection) throws SQLException {
    if (!columnExists(connection, "market_listings", "remark")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN remark TEXT NULL AFTER item_meta_json");
    }
    if (!columnExists(connection, "market_listings", "quantity_total")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN quantity_total INT NOT NULL DEFAULT 0 AFTER quantity");
    }
    execute(
        connection,
        "UPDATE market_listings SET quantity_total = quantity "
            + "WHERE quantity_total IS NULL OR quantity_total <= 0");
    execute(
        connection,
        "UPDATE market_listings SET quantity = 0 WHERE quantity < 0");
  }

  private void createMarketTrades(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS market_trades (
          id BIGINT NOT NULL AUTO_INCREMENT,
          listing_id BIGINT NOT NULL,
          buyer_user_id BIGINT NOT NULL,
          seller_user_id BIGINT NOT NULL,
          currency VARCHAR(16) NOT NULL,
          unit_price BIGINT NOT NULL DEFAULT 0,
          quantity INT NOT NULL DEFAULT 1,
          total_price BIGINT NOT NULL,
          buyer_total BIGINT NOT NULL DEFAULT 0,
          seller_receive BIGINT NOT NULL DEFAULT 0,
          fee_amount BIGINT NOT NULL DEFAULT 0,
          tax_amount BIGINT NOT NULL DEFAULT 0,
          idempotency_key VARCHAR(96) NOT NULL,
          claim_token VARCHAR(64) NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
          refund_deadline DATETIME NULL,
          refunded_at DATETIME NULL,
          settled_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_market_trade_idempotency (buyer_user_id, idempotency_key),
          UNIQUE KEY uniq_market_trade_claim_token (claim_token),
          KEY idx_market_trade_listing_time (listing_id, created_at),
          KEY idx_market_trade_buyer (buyer_user_id, created_at),
          CONSTRAINT fk_market_trade_listing
            FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
          CONSTRAINT fk_market_trade_buyer
            FOREIGN KEY (buyer_user_id) REFERENCES web_users(id) ON DELETE CASCADE,
          CONSTRAINT fk_market_trade_seller
            FOREIGN KEY (seller_user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateMarketTrades(Connection connection) throws SQLException {
    if (!columnExists(connection, "market_trades", "unit_price")) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN unit_price BIGINT NOT NULL DEFAULT 0 AFTER currency");
    }
    if (!columnExists(connection, "market_trades", "quantity")) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN quantity INT NOT NULL DEFAULT 1 AFTER unit_price");
    }

    boolean buyerTotalExists = columnExists(connection, "market_trades", "buyer_total");
    if (!buyerTotalExists) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN buyer_total BIGINT NOT NULL DEFAULT 0 AFTER total_price");
      buyerTotalExists = true;
    }
    boolean sellerReceiveExists = columnExists(connection, "market_trades", "seller_receive");
    if (!sellerReceiveExists) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN seller_receive BIGINT NOT NULL DEFAULT 0 AFTER buyer_total");
      sellerReceiveExists = true;
    }
    boolean feeExists = columnExists(connection, "market_trades", "fee_amount");
    if (!feeExists) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN fee_amount BIGINT NOT NULL DEFAULT 0 AFTER seller_receive");
      feeExists = true;
    }
    boolean taxExists = columnExists(connection, "market_trades", "tax_amount");
    if (!taxExists) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN tax_amount BIGINT NOT NULL DEFAULT 0 AFTER fee_amount");
      taxExists = true;
    }

    if (buyerTotalExists) {
      execute(connection, "UPDATE market_trades SET buyer_total = total_price "
          + "WHERE buyer_total = 0");
    }
    if (sellerReceiveExists) {
      execute(connection, "UPDATE market_trades SET seller_receive = total_price "
          + "WHERE seller_receive = 0");
    }
    if (feeExists) {
      execute(connection, "UPDATE market_trades SET fee_amount = 0 WHERE fee_amount IS NULL");
    }
    if (taxExists) {
      execute(connection, "UPDATE market_trades SET tax_amount = 0 WHERE tax_amount IS NULL");
    }

    execute(
        connection,
        "UPDATE market_trades SET quantity = 1 WHERE quantity IS NULL OR quantity <= 0");
    execute(
        connection,
        "UPDATE market_trades SET unit_price = CASE "
            + "WHEN quantity > 0 THEN FLOOR(total_price / quantity) "
            + "ELSE total_price END "
            + "WHERE unit_price IS NULL OR unit_price <= 0");

    boolean statusAdded = false;
    if (!columnExists(connection, "market_trades", "status")) {
      execute(
          connection,
          "ALTER TABLE market_trades "
              + "ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER idempotency_key");
      statusAdded = true;
    }
    if (!columnExists(connection, "market_trades", "refund_deadline")) {
      execute(
          connection,
          "ALTER TABLE market_trades "
              + "ADD COLUMN refund_deadline DATETIME NULL AFTER status");
    }
    if (!columnExists(connection, "market_trades", "refunded_at")) {
      execute(
          connection,
          "ALTER TABLE market_trades "
              + "ADD COLUMN refunded_at DATETIME NULL AFTER refund_deadline");
    }
    if (!columnExists(connection, "market_trades", "settled_at")) {
      execute(
          connection,
          "ALTER TABLE market_trades "
              + "ADD COLUMN settled_at DATETIME NULL AFTER refunded_at");
    }
    if (!columnExists(connection, "market_trades", "claim_token")) {
      execute(
          connection,
          "ALTER TABLE market_trades "
              + "ADD COLUMN claim_token VARCHAR(64) NULL AFTER idempotency_key");
    }
    if (!indexExists(connection, "market_trades", "uniq_market_trade_claim_token")) {
      execute(
          connection,
          "ALTER TABLE market_trades "
              + "ADD UNIQUE KEY uniq_market_trade_claim_token (claim_token)");
    }
    if (statusAdded) {
      execute(connection, "UPDATE market_trades SET status = 'DELIVERED'");
    } else {
      execute(
          connection,
          "UPDATE market_trades SET status = 'DELIVERED' "
              + "WHERE status IS NULL OR status = ''");
    }
    execute(
        connection,
        "UPDATE market_trades SET settled_at = created_at "
            + "WHERE settled_at IS NULL AND status = 'DELIVERED'");

    dropIndexIfExists(connection, "market_trades", "uniq_market_trade_listing");
    if (!indexExists(connection, "market_trades", "idx_market_trade_listing_time")) {
      execute(
          connection,
          "ALTER TABLE market_trades "
              + "ADD INDEX idx_market_trade_listing_time (listing_id, created_at)");
    }
  }

  private void createMarketItemDeliveries(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS market_item_deliveries (
          id BIGINT NOT NULL AUTO_INCREMENT,
          listing_id BIGINT NOT NULL,
          trade_id BIGINT NULL,
          target_user_id BIGINT NOT NULL,
          target_uuid CHAR(36) NOT NULL,
          item_blob LONGBLOB NOT NULL,
          quantity INT NOT NULL,
          delivery_type VARCHAR(16) NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
          retry_count INT NOT NULL DEFAULT 0,
          last_error VARCHAR(255) NULL,
          next_retry_at DATETIME NOT NULL,
          delivered_at DATETIME NULL,
          claimed_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          KEY idx_market_delivery_trade_type (trade_id, delivery_type),
          KEY idx_market_delivery_listing_type (listing_id, delivery_type),
          KEY idx_market_delivery_due (status, next_retry_at),
          KEY idx_market_delivery_claim (target_uuid, status, created_at),
          CONSTRAINT fk_market_delivery_listing
            FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
          CONSTRAINT fk_market_delivery_trade
            FOREIGN KEY (trade_id) REFERENCES market_trades(id) ON DELETE SET NULL,
          CONSTRAINT fk_market_delivery_user
            FOREIGN KEY (target_user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateMarketItemDeliveries(Connection connection) throws SQLException {
    if (!columnExists(connection, "market_item_deliveries", "trade_id")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD COLUMN trade_id BIGINT NULL AFTER listing_id");
    }
    if (!columnExists(connection, "market_item_deliveries", "claimed_at")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD COLUMN claimed_at DATETIME NULL AFTER delivered_at");
    }

    dropIndexIfExists(connection, "market_item_deliveries", "uniq_market_delivery");

    if (!indexExists(connection, "market_item_deliveries", "idx_market_delivery_trade_type")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD INDEX idx_market_delivery_trade_type (trade_id, delivery_type)");
    }
    if (!indexExists(connection, "market_item_deliveries", "idx_market_delivery_listing_type")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD INDEX idx_market_delivery_listing_type (listing_id, delivery_type)");
    }
    if (!indexExists(connection, "market_item_deliveries", "idx_market_delivery_claim")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD INDEX idx_market_delivery_claim (target_uuid, status, created_at)");
    }

    execute(
        connection,
        "UPDATE market_item_deliveries md "
            + "JOIN market_trades mt ON mt.listing_id = md.listing_id "
            + "SET md.trade_id = mt.id "
            + "WHERE md.delivery_type = 'SALE' AND md.trade_id IS NULL");
  }

  private void createGroupBuyVouchers(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS group_buy_vouchers (
          id BIGINT NOT NULL AUTO_INCREMENT,
          code VARCHAR(40) NOT NULL,
          order_id BIGINT NOT NULL,
          user_id BIGINT NOT NULL,
          product_id BIGINT NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'ISSUED',
          consumed_by_admin_id BIGINT NULL,
          consumed_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_group_buy_voucher_code (code),
          UNIQUE KEY uniq_group_buy_voucher_order (order_id),
          KEY idx_group_buy_voucher_status_time (status, created_at),
          CONSTRAINT fk_group_buy_voucher_order
            FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
          CONSTRAINT fk_group_buy_voucher_user
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE,
          CONSTRAINT fk_group_buy_voucher_product
            FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
          CONSTRAINT fk_group_buy_voucher_admin
            FOREIGN KEY (consumed_by_admin_id) REFERENCES web_users(id) ON DELETE SET NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  private boolean columnExists(Connection connection, String tableName, String columnName)
      throws SQLException {
    String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tableName);
      statement.setString(2, columnName);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return false;
        }
        return resultSet.getInt(1) > 0;
      }
    }
  }

  private boolean indexExists(Connection connection, String tableName, String indexName)
      throws SQLException {
    String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS "
        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tableName);
      statement.setString(2, indexName);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return false;
        }
        return resultSet.getInt(1) > 0;
      }
    }
  }

  private void dropIndexIfExists(Connection connection, String tableName, String indexName)
      throws SQLException {
    if (!indexExists(connection, tableName, indexName)) {
      return;
    }
    execute(connection, "ALTER TABLE " + tableName + " DROP INDEX " + indexName);
  }
}
