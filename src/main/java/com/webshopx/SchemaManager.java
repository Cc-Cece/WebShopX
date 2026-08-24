package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;

class SchemaManager {
  private static final String PRODUCT_SCHEDULE_UTC_MIGRATION_KEY = "product_schedule_utc_v1";
  private static final String MARKET_TAG_SCHEMA_V2_KEY = "market_tag_schema_v2";
  private static final SqlProvider MYSQL_SQL_PROVIDER = SqlProvider.forType(DbType.MYSQL);

  void ensureSchema(DatabaseManager databaseManager, ZoneId timeZone) {
    databaseManager.withConnection(connection -> createTables(connection, timeZone));
  }

  private Void createTables(Connection connection, ZoneId timeZone) throws SQLException {
    createWebUsers(connection);
    migrateWebUsers(connection);
    createWebAdmins(connection);
    migrateWebAdmins(connection);
    createAdminAuditLogs(connection);
    createWebSessions(connection);
    createBindRequests(connection);
    createPlayerPresence(connection);
    createWallets(connection);
    createWalletLedger(connection);
    createRechargeOrders(connection);
    createRedeemCodes(connection);
    createRedeemUsage(connection);
    migrateRedeemCodes(connection);
    migrateRedeemUsage(connection);
    createSchemaMeta(connection);
    createRuntimeConfig(connection);
    createUserVisualPermissions(connection);
    migrateUserVisualPermissions(connection);
    createUserMarketSettings(connection);
    migrateUserMarketSettings(connection);
    createMaterialVisualOverrides(connection);
    migrateMaterialVisualOverrides(connection);
    createVisualPacks(connection);
    createSharedBinaryAssets(connection);
    createProducts(connection);
    migrateProducts(connection);
    createProductItemSnapshots(connection);
    createProductUserUsage(connection);
    migrateProductUserUsage(connection);
    migrateLegacyProductScheduleToUtc(connection, timeZone);
    createOrders(connection);
    migrateOrders(connection);
    createRefundRequests(connection);
    createOrderItems(connection);
    createDeliveryQueue(connection);
    migrateDeliveryQueue(connection);
    createMarketListings(connection);
    migrateMarketListings(connection);
    createMarketSupplyJournal(connection);
    createMarketTags(connection);
    migrateMarketTags(connection);
    createMarketListingTags(connection);
    resetMarketTagsV2IfNeeded(connection);
    createMarketTrades(connection);
    createInventoryOperations(connection);
    createInventoryReadSnapshots(connection);
    migrateMarketTrades(connection);
    createMarketBids(connection);
    migrateMarketBids(connection);
    createMarketItemDeliveries(connection);
    migrateMarketItemDeliveries(connection);
    createGroupBuyVouchers(connection);
    createMailboxItems(connection);
    migrateMailboxItems(connection);
    createNotifications(connection);
    migrateNotifications(connection);
    PromotionSchema.installMysql(connection);
    return null;
  }

  private void createVisualPacks(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS visual_packs (
          pack_id VARCHAR(128) PRIMARY KEY,
          pack_name VARCHAR(255) NOT NULL,
          version_id VARCHAR(96) NOT NULL,
          enabled BOOLEAN NOT NULL DEFAULT FALSE,
          sort_order INT NOT NULL DEFAULT 0,
          icons_enabled BOOLEAN NOT NULL DEFAULT TRUE,
          translations_enabled BOOLEAN NOT NULL DEFAULT FALSE,
          manifest_json LONGTEXT NOT NULL,
          file_size BIGINT NOT NULL DEFAULT 0,
          entry_count INT NOT NULL DEFAULT 0,
          uploaded_by VARCHAR(64),
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """;
    try (java.sql.Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      try {
        statement.executeUpdate(
            "CREATE INDEX idx_visual_packs_order ON visual_packs(enabled, sort_order)");
      } catch (SQLException ignored) {
        // Index already exists (portable across SQLite/MySQL).
      }
    }
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
          is_super_admin BOOLEAN NOT NULL DEFAULT FALSE,
          permissions_json JSON NULL,
          template_key VARCHAR(32) NULL,
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

  private void migrateWebAdmins(Connection connection) throws SQLException {
    if (!columnExists(connection, "web_admins", "is_super_admin")) {
      execute(
          connection,
          "ALTER TABLE web_admins "
              + "ADD COLUMN is_super_admin BOOLEAN NOT NULL DEFAULT FALSE AFTER active");
    }
    if (!columnExists(connection, "web_admins", "permissions_json")) {
      execute(
          connection,
          "ALTER TABLE web_admins "
              + "ADD COLUMN permissions_json JSON NULL AFTER is_super_admin");
    }
    if (!columnExists(connection, "web_admins", "template_key")) {
      execute(
          connection,
          "ALTER TABLE web_admins "
              + "ADD COLUMN template_key VARCHAR(32) NULL AFTER permissions_json");
    }
    execute(
        connection,
        "UPDATE web_admins SET is_super_admin = TRUE "
            + "WHERE role = 'SUPER_ADMIN'");
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

  private void createPlayerPresence(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS player_presence (
          mc_uuid CHAR(36) NOT NULL,
          username VARCHAR(32) NOT NULL,
          server_id VARCHAR(64) NOT NULL,
          online BOOLEAN NOT NULL DEFAULT FALSE,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (mc_uuid),
          KEY idx_player_presence_server_online (server_id, online, updated_at),
          KEY idx_player_presence_online_updated (online, updated_at)
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

  private void createRechargeOrders(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS webshopx_recharge_order (
          id BIGINT NOT NULL AUTO_INCREMENT,
          order_id VARCHAR(48) NOT NULL,
          user_id BIGINT NOT NULL,
          player_uuid CHAR(36) NULL,
          amount_minor BIGINT NOT NULL,
          currency VARCHAR(8) NOT NULL,
          coin_amount BIGINT NOT NULL,
          status VARCHAR(24) NOT NULL,
          provider VARCHAR(32) NULL,
          provider_order_id VARCHAR(96) NULL,
          pay_url TEXT NULL,
          qr_code_url TEXT NULL,
          expire_time DATETIME NULL,
          paid_time DATETIME NULL,
          credited_time DATETIME NULL,
          metadata JSON NULL,
          error_code VARCHAR(64) NULL,
          error_message VARCHAR(255) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_recharge_order_id (order_id),
          KEY idx_recharge_user_id (user_id),
          KEY idx_recharge_player_uuid (player_uuid),
          KEY idx_recharge_provider_order_id (provider_order_id),
          KEY idx_recharge_status (status),
          CONSTRAINT fk_recharge_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
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

  private void createSchemaMeta(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS webshop_meta (
          meta_key VARCHAR(64) NOT NULL,
          meta_value VARCHAR(255) NULL,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (meta_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createRuntimeConfig(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS runtime_config (
          config_key VARCHAR(64) NOT NULL,
          config_value LONGTEXT NOT NULL,
          version BIGINT NOT NULL DEFAULT 1,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (config_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createUserVisualPermissions(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS user_visual_permissions (
          user_id BIGINT NOT NULL,
          icon_permission VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
          name_permission VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
          upload_permission VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (user_id),
          CONSTRAINT fk_user_visual_permissions_user
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateUserVisualPermissions(Connection connection) throws SQLException {
    if (!columnExists(connection, "user_visual_permissions", "icon_permission")) {
      execute(
          connection,
          "ALTER TABLE user_visual_permissions "
              + "ADD COLUMN icon_permission VARCHAR(16) NOT NULL DEFAULT 'INHERIT' AFTER user_id");
    }
    if (!columnExists(connection, "user_visual_permissions", "name_permission")) {
      execute(
          connection,
          "ALTER TABLE user_visual_permissions "
              + "ADD COLUMN name_permission VARCHAR(16) NOT NULL DEFAULT 'INHERIT' AFTER icon_permission");
    }
    if (!columnExists(connection, "user_visual_permissions", "upload_permission")) {
      execute(
          connection,
          "ALTER TABLE user_visual_permissions "
              + "ADD COLUMN upload_permission VARCHAR(16) NOT NULL DEFAULT 'INHERIT' AFTER name_permission");
    }
    execute(
        connection,
        "UPDATE user_visual_permissions SET icon_permission = 'INHERIT' "
            + "WHERE icon_permission IS NULL OR icon_permission = ''");
    execute(
        connection,
        "UPDATE user_visual_permissions SET name_permission = 'INHERIT' "
            + "WHERE name_permission IS NULL OR name_permission = ''");
    execute(
        connection,
        "UPDATE user_visual_permissions SET upload_permission = 'INHERIT' "
            + "WHERE upload_permission IS NULL OR upload_permission = ''");
  }

  private void createUserMarketSettings(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS user_market_settings (
          user_id BIGINT NOT NULL,
          listing_limit_override INT NULL,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (user_id),
          CONSTRAINT fk_user_market_settings_user
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateUserMarketSettings(Connection connection) throws SQLException {
    if (!columnExists(connection, "user_market_settings", "listing_limit_override")) {
      execute(
          connection,
          "ALTER TABLE user_market_settings "
              + "ADD COLUMN listing_limit_override INT NULL AFTER user_id");
    }
    execute(
        connection,
        "UPDATE user_market_settings SET listing_limit_override = NULL "
            + "WHERE listing_limit_override IS NOT NULL AND listing_limit_override <= 0");
  }

  private void createMaterialVisualOverrides(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS material_visual_overrides (
          material_key VARCHAR(64) NOT NULL,
          display_name_override VARCHAR(128) NULL,
          icon_path VARCHAR(255) NULL,
          updated_by VARCHAR(64) NULL,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (material_key),
          KEY idx_material_visual_updated (updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateMaterialVisualOverrides(Connection connection) throws SQLException {
    if (!columnExists(connection, "material_visual_overrides", "display_name_override")) {
      execute(
          connection,
          "ALTER TABLE material_visual_overrides "
              + "ADD COLUMN display_name_override VARCHAR(128) NULL AFTER material_key");
    }
    if (!columnExists(connection, "material_visual_overrides", "icon_path")) {
      if (columnExists(connection, "material_visual_overrides", "icon_file_name")) {
        execute(
            connection,
            "ALTER TABLE material_visual_overrides "
                + "CHANGE COLUMN icon_file_name icon_path VARCHAR(255) NULL");
      } else {
        execute(
            connection,
            "ALTER TABLE material_visual_overrides "
                + "ADD COLUMN icon_path VARCHAR(255) NULL AFTER display_name_override");
      }
    }
    if (!columnExists(connection, "material_visual_overrides", "updated_by")) {
      execute(
          connection,
          "ALTER TABLE material_visual_overrides "
              + "ADD COLUMN updated_by VARCHAR(64) NULL AFTER icon_path");
    }
    if (!indexExists(connection, "material_visual_overrides", "idx_material_visual_updated")) {
      execute(
          connection,
          "ALTER TABLE material_visual_overrides "
              + "ADD INDEX idx_material_visual_updated (updated_at)");
    }
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
    execute(connection, """
        CREATE TABLE IF NOT EXISTS official_item_snapshots (
          id BIGINT NOT NULL AUTO_INCREMENT,
          item_hash VARCHAR(96) NOT NULL,
          item_blob LONGBLOB NOT NULL,
          item_meta_json LONGTEXT NOT NULL,
          item_material VARCHAR(64) NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_official_item_snapshot_hash (item_hash)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    widenHashColumn(connection, "official_item_snapshots");
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
          display_name_override VARCHAR(128) NULL,
          display_material VARCHAR(64) NULL,
          display_icon_path VARCHAR(255) NULL,
          item_amount INT NULL,
          stock_remaining INT NULL,
          per_user_limit INT NULL,
          effect_type VARCHAR(64) NULL,
          effect_seconds INT NULL,
          effect_amplifier INT NULL,
          snapshot_id BIGINT NULL,
          inventory_mode VARCHAR(16) NOT NULL DEFAULT 'TEMPLATE',
          dynamic_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
          dynamic_algorithm VARCHAR(64) NOT NULL DEFAULT 'LINEAR_DEMAND_V1',
          dynamic_pricing_mode VARCHAR(32) NOT NULL DEFAULT 'ORDER_FIXED',
          dynamic_params_json JSON NULL,
          dynamic_base_price BIGINT NULL,
          dynamic_floor_price BIGINT NULL,
          dynamic_cap_price BIGINT NULL,
          dynamic_price_step BIGINT NULL,
          dynamic_demand_score BIGINT NOT NULL DEFAULT 0,
          refund_policy VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
          refund_window_minutes INT NULL,
          partial_refund_policy VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
          publish_at DATETIME NULL,
          unpublish_at DATETIME NULL,
          active BOOLEAN NOT NULL DEFAULT TRUE,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_products_sku (sku),
          KEY idx_products_snapshot_id (snapshot_id),
          CONSTRAINT fk_products_snapshot_id FOREIGN KEY (snapshot_id)
            REFERENCES official_item_snapshots(id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateProducts(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE IF NOT EXISTS official_item_snapshots (
          id BIGINT NOT NULL AUTO_INCREMENT,
          item_hash VARCHAR(96) NOT NULL,
          item_blob LONGBLOB NOT NULL,
          item_meta_json LONGTEXT NOT NULL,
          item_material VARCHAR(64) NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_official_item_snapshot_hash (item_hash)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    if (!columnExists(connection, "products", "snapshot_id")) {
      execute(connection, "ALTER TABLE products ADD COLUMN snapshot_id BIGINT NULL");
      execute(connection, "ALTER TABLE products ADD KEY idx_products_snapshot_id (snapshot_id)");
    }
    if (!columnExists(connection, "products", "inventory_mode")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN inventory_mode VARCHAR(16) NOT NULL DEFAULT 'TEMPLATE'");
    }
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
    if (!columnExists(connection, "products", "display_name_override")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN display_name_override VARCHAR(128) NULL AFTER item_material");
    }
    if (!columnExists(connection, "products", "display_material")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN display_material VARCHAR(64) NULL AFTER display_name_override");
    }
    if (!columnExists(connection, "products", "display_icon_path")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN display_icon_path VARCHAR(255) NULL AFTER display_material");
    }
    if (!columnExists(connection, "products", "item_amount")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN item_amount INT NULL AFTER item_material");
    }
    if (!columnExists(connection, "products", "stock_remaining")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN stock_remaining INT NULL AFTER item_amount");
      execute(
          connection,
          "UPDATE products SET stock_remaining = item_amount WHERE item_amount IS NOT NULL");
    }
    if (!columnExists(connection, "products", "per_user_limit")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN per_user_limit INT NULL AFTER stock_remaining");
    }
    execute(
        connection,
        "UPDATE products SET per_user_limit = NULL WHERE per_user_limit IS NOT NULL AND per_user_limit <= 0");
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
    if (!columnExists(connection, "products", "dynamic_pricing_enabled")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER effect_amplifier");
    }
    if (!columnExists(connection, "products", "dynamic_algorithm")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_algorithm VARCHAR(64) NOT NULL DEFAULT 'LINEAR_DEMAND_V1' "
              + "AFTER dynamic_pricing_enabled");
    }
    if (!columnExists(connection, "products", "dynamic_params_json")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_params_json JSON NULL AFTER dynamic_algorithm");
    }
    if (!columnExists(connection, "products", "dynamic_pricing_mode")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_pricing_mode VARCHAR(32) NOT NULL DEFAULT 'ORDER_FIXED' AFTER dynamic_algorithm");
    }
    if (!columnExists(connection, "products", "dynamic_base_price")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_base_price BIGINT NULL AFTER dynamic_params_json");
    }
    if (!columnExists(connection, "products", "dynamic_floor_price")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_floor_price BIGINT NULL AFTER dynamic_base_price");
    }
    if (!columnExists(connection, "products", "dynamic_cap_price")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_cap_price BIGINT NULL AFTER dynamic_floor_price");
    }
    if (!columnExists(connection, "products", "dynamic_price_step")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_price_step BIGINT NULL AFTER dynamic_cap_price");
    }
    if (!columnExists(connection, "products", "dynamic_demand_score")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN dynamic_demand_score BIGINT NOT NULL DEFAULT 0 AFTER dynamic_price_step");
    }
    if (!columnExists(connection, "products", "publish_at")) {
      execute(
          connection,
          "ALTER TABLE products "
              + "ADD COLUMN publish_at DATETIME NULL AFTER effect_amplifier");
    }
    if (!columnExists(connection, "products", "refund_policy")) {
      execute(connection, "ALTER TABLE products "
          + "ADD COLUMN refund_policy VARCHAR(16) NOT NULL DEFAULT 'INHERIT' AFTER dynamic_demand_score");
    }
    if (!columnExists(connection, "products", "refund_window_minutes")) {
      execute(connection, "ALTER TABLE products "
          + "ADD COLUMN refund_window_minutes INT NULL AFTER refund_policy");
    }
    if (!columnExists(connection, "products", "partial_refund_policy")) {
      execute(connection, "ALTER TABLE products "
          + "ADD COLUMN partial_refund_policy VARCHAR(16) NOT NULL DEFAULT 'INHERIT' "
          + "AFTER refund_window_minutes");
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
    execute(
      connection,
      "UPDATE products SET dynamic_algorithm = 'LINEAR_DEMAND_V1' "
        + "WHERE dynamic_algorithm IS NULL OR dynamic_algorithm = ''");
    execute(
      connection,
      "UPDATE products SET dynamic_pricing_mode = 'ORDER_FIXED' "
        + "WHERE dynamic_pricing_mode IS NULL OR dynamic_pricing_mode = ''");
    execute(
      connection,
      "UPDATE products SET dynamic_demand_score = 0 "
        + "WHERE dynamic_demand_score IS NULL OR dynamic_demand_score < 0");
    execute(
      connection,
      "UPDATE products SET dynamic_base_price = NULL "
        + "WHERE dynamic_base_price IS NOT NULL AND dynamic_base_price <= 0");
    execute(
      connection,
      "UPDATE products SET dynamic_floor_price = NULL "
        + "WHERE dynamic_floor_price IS NOT NULL AND dynamic_floor_price <= 0");
    execute(
      connection,
      "UPDATE products SET dynamic_cap_price = NULL "
        + "WHERE dynamic_cap_price IS NOT NULL AND dynamic_cap_price <= 0");
    execute(
      connection,
      "UPDATE products SET dynamic_price_step = NULL "
        + "WHERE dynamic_price_step IS NOT NULL AND dynamic_price_step <= 0");
    execute(
      connection,
      "UPDATE products SET dynamic_cap_price = dynamic_floor_price "
        + "WHERE dynamic_floor_price IS NOT NULL "
        + "AND dynamic_cap_price IS NOT NULL "
        + "AND dynamic_cap_price < dynamic_floor_price");
    execute(
      connection,
      "UPDATE products SET dynamic_base_price = price "
        + "WHERE dynamic_pricing_enabled = TRUE "
        + "AND dynamic_base_price IS NULL "
        + "AND price > 0");
    execute(
      connection,
      "UPDATE products SET dynamic_pricing_enabled = FALSE "
        + "WHERE dynamic_pricing_enabled = TRUE "
        + "AND (COALESCE(dynamic_base_price, 0) <= 0)");
  }

  private void createProductItemSnapshots(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE IF NOT EXISTS product_item_snapshots (
          id BIGINT NOT NULL AUTO_INCREMENT,
          product_id BIGINT NOT NULL,
          snapshot_id BIGINT NOT NULL,
          version INT NOT NULL,
          item_hash VARCHAR(96) NOT NULL,
          created_by BIGINT NULL,
          active_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_product_item_snapshot_version (product_id, version),
          KEY idx_product_item_snapshot_blob (snapshot_id),
          CONSTRAINT fk_product_item_snapshot_product FOREIGN KEY (product_id)
            REFERENCES products(id),
          CONSTRAINT fk_product_item_snapshot_blob FOREIGN KEY (snapshot_id)
            REFERENCES official_item_snapshots(id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    widenHashColumn(connection, "product_item_snapshots");
    execute(connection, """
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
          target_server_id VARCHAR(64) NULL,
          claim_token VARCHAR(64) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          refund_deadline DATETIME NULL,
          refund_allowed BOOLEAN NOT NULL DEFAULT TRUE,
          partial_refund_allowed BOOLEAN NOT NULL DEFAULT TRUE,
          refund_policy_json JSON NULL,
          refunded_quantity INT NOT NULL DEFAULT 0,
          refunded_amount BIGINT NOT NULL DEFAULT 0,
          delivered_at DATETIME NULL,
          refunded_at DATETIME NULL,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_orders_order_no (order_no),
          UNIQUE KEY uniq_orders_idempotency (user_id, idempotency_key),
          UNIQUE KEY uniq_orders_claim_token (claim_token),
          KEY idx_orders_user_id (user_id),
          KEY idx_orders_target_server (target_server_id, status, created_at),
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
    if (!columnExists(connection, "orders", "refund_allowed")) {
      execute(connection, "ALTER TABLE orders "
          + "ADD COLUMN refund_allowed BOOLEAN NOT NULL DEFAULT TRUE AFTER refund_deadline");
    }
    if (!columnExists(connection, "orders", "partial_refund_allowed")) {
      execute(connection, "ALTER TABLE orders "
          + "ADD COLUMN partial_refund_allowed BOOLEAN NOT NULL DEFAULT TRUE AFTER refund_allowed");
    }
    if (!columnExists(connection, "orders", "refund_policy_json")) {
      execute(connection, "ALTER TABLE orders "
          + "ADD COLUMN refund_policy_json JSON NULL AFTER partial_refund_allowed");
    }
    if (!columnExists(connection, "orders", "refunded_quantity")) {
      execute(connection, "ALTER TABLE orders "
          + "ADD COLUMN refunded_quantity INT NOT NULL DEFAULT 0 AFTER refund_policy_json");
    }
    if (!columnExists(connection, "orders", "refunded_amount")) {
      execute(connection, "ALTER TABLE orders "
          + "ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0 AFTER refunded_quantity");
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
    if (!columnExists(connection, "orders", "target_server_id")) {
      execute(
          connection,
          "ALTER TABLE orders ADD COLUMN target_server_id VARCHAR(64) NULL AFTER idempotency_key");
    }
    if (!indexExists(connection, "orders", "uniq_orders_claim_token")) {
      execute(
          connection,
          "ALTER TABLE orders ADD UNIQUE KEY uniq_orders_claim_token (claim_token)");
    }
    if (!indexExists(connection, "orders", "idx_orders_target_server")) {
      execute(
          connection,
          "ALTER TABLE orders ADD INDEX idx_orders_target_server (target_server_id, status, created_at)");
    }
  }

  private void createRefundRequests(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE IF NOT EXISTS refund_requests (
          id BIGINT NOT NULL AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          order_ref VARCHAR(64) NOT NULL,
          idempotency_key VARCHAR(96) NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
          refund_amount BIGINT NOT NULL DEFAULT 0,
          refund_quantity INT NOT NULL DEFAULT 0,
          error_code VARCHAR(64) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          completed_at DATETIME NULL,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_refund_request_key (user_id, idempotency_key),
          CONSTRAINT fk_refund_request_user
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
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
          target_server_id VARCHAR(64) NULL,
          command_text TEXT NOT NULL,
          delivery_kind VARCHAR(24) NOT NULL DEFAULT 'COMMAND',
          payload_json JSON NULL,
          manual_claim BOOLEAN NOT NULL DEFAULT FALSE,
          quantity INT NOT NULL,
          delivered_quantity INT NOT NULL DEFAULT 0,
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
          KEY idx_delivery_target_due (target_server_id, status, next_retry_at),
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
    if (!columnExists(connection, "delivery_queue", "target_server_id")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD COLUMN target_server_id VARCHAR(64) NULL AFTER mc_uuid");
    }
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
    if (!columnExists(connection, "delivery_queue", "delivered_quantity")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD COLUMN delivered_quantity INT NOT NULL DEFAULT 0 AFTER quantity");
    }
    if (!indexExists(connection, "delivery_queue", "idx_delivery_claim")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD INDEX idx_delivery_claim (mc_uuid, status, created_at)");
    }
    if (!indexExists(connection, "delivery_queue", "idx_delivery_target_due")) {
      execute(
          connection,
          "ALTER TABLE delivery_queue "
              + "ADD INDEX idx_delivery_target_due (target_server_id, status, next_retry_at)");
    }
    execute(
        connection,
        "UPDATE orders o "
            + "SET status = 'WAIT_CLAIM' "
            + "WHERE o.status = 'PENDING' "
            + "AND EXISTS ("
            + "  SELECT 1 FROM delivery_queue dq "
            + "  WHERE dq.order_id = o.id AND dq.status = 'WAIT_CLAIM'"
            + ")");
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
          display_name_override VARCHAR(128) NULL,
          display_material VARCHAR(64) NULL,
          display_icon_path VARCHAR(255) NULL,
          raw_item_blob LONGBLOB NOT NULL,
          item_meta_json JSON NOT NULL,
          remark TEXT NULL,
          item_hash VARCHAR(96) NOT NULL,
          tag_code VARCHAR(64) NOT NULL DEFAULT 'default',
          tag_version INT NOT NULL DEFAULT 1,
          escrow_total BIGINT NOT NULL DEFAULT 0,
          escrow_remaining BIGINT NOT NULL DEFAULT 0,
          status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          sold_at DATETIME NULL,
          unlisted_at DATETIME NULL,
          paused_at DATETIME NULL,
          trade_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
          market_side VARCHAR(8) NOT NULL DEFAULT 'SELL',
          dynamic_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
          dynamic_algorithm VARCHAR(64) NOT NULL DEFAULT 'LINEAR_DEMAND_V1',
          dynamic_pricing_mode VARCHAR(32) NOT NULL DEFAULT 'ORDER_FIXED',
          dynamic_base_price BIGINT NULL,
          dynamic_floor_price BIGINT NULL,
          dynamic_cap_price BIGINT NULL,
          dynamic_price_step BIGINT NULL,
          dynamic_demand_score BIGINT NOT NULL DEFAULT 0,
          dynamic_params_json JSON NULL,
          auction_algorithm VARCHAR(64) NOT NULL DEFAULT 'ENGLISH_AUCTION_V1',
          auction_start_price BIGINT NULL,
          auction_min_increment BIGINT NULL,
          auction_started_at DATETIME NULL,
          auction_public_end_at DATETIME NULL,
          auction_params_json JSON NULL,
          auction_end_at DATETIME NULL,
          auction_highest_bid BIGINT NULL,
          auction_highest_bidder_user_id BIGINT NULL,
          auction_highest_bidder_uuid CHAR(36) NULL,
          auction_highest_bid_id BIGINT NULL,
          auction_last_bid_at DATETIME NULL,
          PRIMARY KEY (id),
          KEY idx_market_listing_status (status, created_at),
          KEY idx_market_listing_seller (seller_user_id, status),
          KEY idx_market_listing_side_status_created (market_side, status, id),
          KEY idx_market_listing_side_tag_status (market_side, tag_code, status, id),
          KEY idx_market_listing_auction_due (trade_mode, status, auction_end_at),
          CONSTRAINT fk_market_listing_seller
            FOREIGN KEY (seller_user_id) REFERENCES web_users(id) ON DELETE CASCADE,
          CONSTRAINT fk_market_listing_buyer
            FOREIGN KEY (buyer_user_id) REFERENCES web_users(id) ON DELETE SET NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateMarketListings(Connection connection) throws SQLException {
    widenHashColumn(connection, "market_listings");
    if (!columnExists(connection, "market_listings", "remark")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN remark TEXT NULL AFTER item_meta_json");
    }
    if (!columnExists(connection, "market_listings", "display_name_override")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN display_name_override VARCHAR(128) NULL AFTER item_material");
    }
    if (!columnExists(connection, "market_listings", "display_material")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN display_material VARCHAR(64) NULL AFTER display_name_override");
    }
    if (!columnExists(connection, "market_listings", "display_icon_path")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN display_icon_path VARCHAR(255) NULL AFTER display_material");
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
    if (!columnExists(connection, "market_listings", "paused_at")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN paused_at DATETIME NULL AFTER unlisted_at");
    }
    if (!columnExists(connection, "market_listings", "source_mode")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN source_mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER item_hash");
    }
    if (!columnExists(connection, "market_listings", "supply_world")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_world VARCHAR(64) NULL AFTER source_mode");
    }
    if (!columnExists(connection, "market_listings", "supply_x")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_x INT NULL AFTER supply_world");
    }
    if (!columnExists(connection, "market_listings", "supply_y")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_y INT NULL AFTER supply_x");
    }
    if (!columnExists(connection, "market_listings", "supply_z")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_z INT NULL AFTER supply_y");
    }
    if (!columnExists(connection, "market_listings", "supply_batch_size")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_batch_size INT NULL AFTER supply_z");
    }
    if (!columnExists(connection, "market_listings", "supply_max_stock")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_max_stock INT NULL AFTER supply_batch_size");
    }
    if (!columnExists(connection, "market_listings", "supply_access_protected")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_access_protected BOOLEAN NOT NULL DEFAULT TRUE AFTER supply_max_stock");
    }
    if (!indexExists(connection, "market_listings", "idx_market_supply_location")) {
      execute(
          connection,
          "ALTER TABLE market_listings ADD INDEX idx_market_supply_location "
              + "(source_mode, status, supply_world, supply_x, supply_y, supply_z)");
    }
    if (!columnExists(connection, "market_listings", "supply_loaded_total")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_loaded_total BIGINT NOT NULL DEFAULT 0 AFTER supply_max_stock");
    }
    if (!columnExists(connection, "market_listings", "supply_sold_total")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_sold_total BIGINT NOT NULL DEFAULT 0 AFTER supply_loaded_total");
    }
    if (!columnExists(connection, "market_listings", "supply_last_loaded_amount")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_last_loaded_amount INT NULL AFTER supply_sold_total");
    }
    if (!columnExists(connection, "market_listings", "supply_last_loaded_at")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN supply_last_loaded_at DATETIME NULL AFTER supply_last_loaded_amount");
    }
    if (!columnExists(connection, "market_listings", "trade_mode")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN trade_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT' AFTER paused_at");
    }
    if (!columnExists(connection, "market_listings", "market_side")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN market_side VARCHAR(8) NOT NULL DEFAULT 'SELL' AFTER trade_mode");
    }
    if (!columnExists(connection, "market_listings", "dynamic_pricing_enabled")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER market_side");
    }
    if (!columnExists(connection, "market_listings", "dynamic_algorithm")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_algorithm VARCHAR(64) NOT NULL DEFAULT 'LINEAR_DEMAND_V1' "
          + "AFTER dynamic_pricing_enabled");
    }
    if (!columnExists(connection, "market_listings", "dynamic_base_price")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_base_price BIGINT NULL AFTER dynamic_algorithm");
    }
    if (!columnExists(connection, "market_listings", "dynamic_pricing_mode")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_pricing_mode VARCHAR(32) NOT NULL DEFAULT 'ORDER_FIXED' AFTER dynamic_algorithm");
    }
    if (!columnExists(connection, "market_listings", "dynamic_floor_price")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_floor_price BIGINT NULL AFTER dynamic_base_price");
    }
    if (!columnExists(connection, "market_listings", "dynamic_cap_price")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_cap_price BIGINT NULL AFTER dynamic_floor_price");
    }
    if (!columnExists(connection, "market_listings", "dynamic_price_step")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_price_step BIGINT NULL AFTER dynamic_cap_price");
    }
    if (!columnExists(connection, "market_listings", "dynamic_demand_score")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_demand_score BIGINT NOT NULL DEFAULT 0 AFTER dynamic_price_step");
    }
    if (!columnExists(connection, "market_listings", "dynamic_params_json")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN dynamic_params_json JSON NULL AFTER dynamic_demand_score");
    }
    if (!columnExists(connection, "market_listings", "auction_algorithm")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_algorithm VARCHAR(64) NOT NULL DEFAULT 'ENGLISH_AUCTION_V1' "
          + "AFTER dynamic_params_json");
    }
    if (!columnExists(connection, "market_listings", "auction_start_price")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_start_price BIGINT NULL AFTER auction_algorithm");
    }
    if (!columnExists(connection, "market_listings", "auction_min_increment")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_min_increment BIGINT NULL AFTER auction_start_price");
    }
    if (!columnExists(connection, "market_listings", "auction_started_at")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_started_at DATETIME NULL AFTER auction_min_increment");
    }
    if (!columnExists(connection, "market_listings", "auction_public_end_at")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_public_end_at DATETIME NULL AFTER auction_started_at");
    }
    if (!columnExists(connection, "market_listings", "auction_params_json")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_params_json JSON NULL AFTER auction_public_end_at");
    }
    if (!columnExists(connection, "market_listings", "auction_end_at")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_end_at DATETIME NULL AFTER auction_params_json");
    }
    if (!columnExists(connection, "market_listings", "auction_highest_bid")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_highest_bid BIGINT NULL AFTER auction_end_at");
    }
    if (!columnExists(connection, "market_listings", "auction_highest_bidder_user_id")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_highest_bidder_user_id BIGINT NULL AFTER auction_highest_bid");
    }
    if (!columnExists(connection, "market_listings", "auction_highest_bidder_uuid")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_highest_bidder_uuid CHAR(36) NULL AFTER auction_highest_bidder_user_id");
    }
    if (!columnExists(connection, "market_listings", "auction_highest_bid_id")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_highest_bid_id BIGINT NULL AFTER auction_highest_bidder_uuid");
    }
    if (!columnExists(connection, "market_listings", "auction_last_bid_at")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD COLUMN auction_last_bid_at DATETIME NULL AFTER auction_highest_bid_id");
    }
    if (!columnExists(connection, "market_listings", "tag_code")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN tag_code VARCHAR(64) NOT NULL DEFAULT 'default' AFTER item_hash");
    }
    if (!columnExists(connection, "market_listings", "tag_version")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN tag_version INT NOT NULL DEFAULT 1 AFTER tag_code");
    }
    if (!columnExists(connection, "market_listings", "escrow_total")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN escrow_total BIGINT NOT NULL DEFAULT 0 AFTER quantity_total");
    }
    if (!columnExists(connection, "market_listings", "escrow_remaining")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD COLUMN escrow_remaining BIGINT NOT NULL DEFAULT 0 AFTER escrow_total");
    }
    if (!indexExists(connection, "market_listings", "idx_market_listing_auction_due")) {
      execute(
        connection,
        "ALTER TABLE market_listings "
          + "ADD INDEX idx_market_listing_auction_due (trade_mode, status, auction_end_at)");
    }
    if (!indexExists(connection, "market_listings", "idx_market_listing_side_status_created")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD INDEX idx_market_listing_side_status_created (market_side, status, id)");
    }
    if (!indexExists(connection, "market_listings", "idx_market_listing_side_tag_status")) {
      execute(
          connection,
          "ALTER TABLE market_listings "
              + "ADD INDEX idx_market_listing_side_tag_status (market_side, tag_code, status, id)");
    }
    execute(
        connection,
        "UPDATE market_listings SET source_mode = 'MANUAL' "
            + "WHERE source_mode IS NULL OR source_mode = ''");
    execute(
      connection,
      "UPDATE market_listings SET trade_mode = 'DIRECT' "
        + "WHERE trade_mode IS NULL OR trade_mode = ''");
    execute(
        connection,
        "UPDATE market_listings SET market_side = 'SELL' "
            + "WHERE market_side IS NULL OR market_side = ''");
    execute(
        connection,
        "UPDATE market_listings SET tag_code = 'default' "
            + "WHERE tag_code IS NULL OR tag_code = ''");
    execute(
        connection,
        "UPDATE market_listings SET tag_version = 1 "
            + "WHERE tag_version IS NULL OR tag_version <= 0");
    execute(
        connection,
        "UPDATE market_listings SET escrow_total = 0 WHERE escrow_total IS NULL OR escrow_total < 0");
    execute(
        connection,
        "UPDATE market_listings SET escrow_remaining = 0 "
            + "WHERE escrow_remaining IS NULL OR escrow_remaining < 0");
    execute(
      connection,
      "UPDATE market_listings SET dynamic_base_price = price "
        + "WHERE dynamic_base_price IS NULL OR dynamic_base_price <= 0");
    execute(
      connection,
      "UPDATE market_listings SET dynamic_algorithm = 'LINEAR_DEMAND_V1' "
        + "WHERE dynamic_algorithm IS NULL OR dynamic_algorithm = ''");
    execute(
      connection,
      "UPDATE market_listings SET dynamic_pricing_mode = 'ORDER_FIXED' "
        + "WHERE dynamic_pricing_mode IS NULL OR dynamic_pricing_mode = ''");
    execute(
      connection,
      "UPDATE market_listings SET dynamic_price_step = 1 "
        + "WHERE dynamic_pricing_enabled = TRUE "
        + "AND (dynamic_price_step IS NULL OR dynamic_price_step <= 0)");
    execute(
      connection,
      "UPDATE market_listings SET dynamic_demand_score = 0 "
        + "WHERE dynamic_demand_score IS NULL OR dynamic_demand_score < 0");
    execute(
      connection,
      "UPDATE market_listings SET auction_algorithm = 'ENGLISH_AUCTION_V1' "
        + "WHERE auction_algorithm IS NULL OR auction_algorithm = ''");
    execute(
      connection,
      "UPDATE market_listings SET auction_started_at = created_at "
        + "WHERE trade_mode = 'AUCTION' AND auction_started_at IS NULL");
    execute(
      connection,
      "UPDATE market_listings SET auction_public_end_at = auction_end_at "
        + "WHERE trade_mode = 'AUCTION' AND auction_end_at IS NOT NULL AND auction_public_end_at IS NULL");
    execute(
      connection,
      "UPDATE market_listings SET auction_min_increment = 1 "
        + "WHERE trade_mode = 'AUCTION' "
        + "AND (auction_min_increment IS NULL OR auction_min_increment <= 0)");
    execute(
        connection,
        "UPDATE market_listings SET supply_loaded_total = 0 WHERE supply_loaded_total IS NULL");
    execute(
        connection,
        "UPDATE market_listings SET supply_sold_total = 0 WHERE supply_sold_total IS NULL");
  }

  private void createMarketTags(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS market_tags (
          code VARCHAR(64) NOT NULL,
          display_name VARCHAR(128) NOT NULL,
          enabled BOOLEAN NOT NULL DEFAULT TRUE,
          priority INT NOT NULL DEFAULT 1000,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (code),
          KEY idx_market_tags_enabled_priority (enabled, priority, code)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateMarketTags(Connection connection) throws SQLException {
    if (!columnExists(connection, "market_tags", "display_name")) {
      execute(
          connection,
          "ALTER TABLE market_tags "
              + "ADD COLUMN display_name VARCHAR(128) NOT NULL AFTER code");
    }
    if (!columnExists(connection, "market_tags", "enabled")) {
      execute(
          connection,
          "ALTER TABLE market_tags "
              + "ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER display_name");
    }
    if (!columnExists(connection, "market_tags", "priority")) {
      execute(
          connection,
          "ALTER TABLE market_tags "
              + "ADD COLUMN priority INT NOT NULL DEFAULT 1000 AFTER enabled");
    }
    if (!columnExists(connection, "market_tags", "created_at")) {
      execute(
          connection,
          "ALTER TABLE market_tags "
              + "ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER priority");
    }
    if (!columnExists(connection, "market_tags", "updated_at")) {
      execute(
          connection,
          "ALTER TABLE market_tags "
              + "ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP "
              + "ON UPDATE CURRENT_TIMESTAMP AFTER created_at");
    }
    if (!indexExists(connection, "market_tags", "idx_market_tags_enabled_priority")) {
      execute(
          connection,
          "ALTER TABLE market_tags "
              + "ADD INDEX idx_market_tags_enabled_priority (enabled, priority, code)");
    }
  }

  private void createMarketListingTags(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS market_listing_tags (
          listing_id BIGINT NOT NULL,
          tag_code VARCHAR(64) NOT NULL,
          source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
          position INT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (listing_id, tag_code),
          KEY idx_market_listing_tags_tag (tag_code, listing_id),
          CONSTRAINT fk_market_listing_tags_listing
            FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
          CONSTRAINT fk_market_listing_tags_tag
            FOREIGN KEY (tag_code) REFERENCES market_tags(code) ON DELETE RESTRICT
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
    execute(
        connection,
        """
        INSERT INTO market_tags (code, display_name, enabled, priority)
        SELECT DISTINCT ml.tag_code, ml.tag_code, TRUE, 1000
        FROM market_listings ml
        WHERE ml.tag_code IS NOT NULL
          AND ml.tag_code <> ''
          AND NOT EXISTS (
            SELECT 1 FROM market_tags mt WHERE mt.code = ml.tag_code
          )
        """);
    execute(
        connection,
        """
        INSERT INTO market_listing_tags (listing_id, tag_code, source, position)
        SELECT ml.id, ml.tag_code, 'SYSTEM', 0
        FROM market_listings ml
        WHERE ml.tag_code IS NOT NULL
          AND ml.tag_code <> ''
          AND NOT EXISTS (
            SELECT 1 FROM market_listing_tags mlt WHERE mlt.listing_id = ml.id
          )
        """);
  }

  private void resetMarketTagsV2IfNeeded(Connection connection) throws SQLException {
    if (readMetaValue(connection, MARKET_TAG_SCHEMA_V2_KEY) != null) {
      return;
    }
    execute(connection, "DELETE FROM market_listing_tags");
    execute(connection, "DELETE FROM market_tags");
    execute(connection, "DELETE FROM runtime_config WHERE config_key = 'market_tags'");
    execute(
        connection,
        "INSERT INTO market_tags (code, display_name, enabled, priority) "
            + "VALUES ('default', 'Default', TRUE, 2147483647)");
    execute(
        connection,
        "UPDATE market_listings SET tag_code = 'default', tag_version = 1");
    execute(
        connection,
        "INSERT INTO market_listing_tags (listing_id, tag_code, source, position) "
            + "SELECT id, 'default', 'SYSTEM', 0 FROM market_listings");
    writeMetaValue(connection, MARKET_TAG_SCHEMA_V2_KEY, "2");
  }

  private void createMarketBids(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS market_bids (
          id BIGINT NOT NULL AUTO_INCREMENT,
          listing_id BIGINT NOT NULL,
          bidder_user_id BIGINT NOT NULL,
          bidder_uuid CHAR(36) NOT NULL,
          bid_amount BIGINT NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'LEADING',
          idempotency_key VARCHAR(96) NOT NULL,
          outbid_at DATETIME NULL,
          refunded_at DATETIME NULL,
          settled_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_market_bid_idempotency (bidder_user_id, idempotency_key),
          KEY idx_market_bid_listing_status (listing_id, status, created_at),
          KEY idx_market_bid_bidder (bidder_user_id, created_at),
          CONSTRAINT fk_market_bid_listing
            FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
          CONSTRAINT fk_market_bid_bidder
            FOREIGN KEY (bidder_user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createInventoryOperations(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE IF NOT EXISTS inventory_operations (
          id BIGINT NOT NULL AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          idempotency_key VARCHAR(96) NOT NULL,
          action VARCHAR(24) NOT NULL,
          state VARCHAR(24) NOT NULL,
          slot_index INT NOT NULL,
          container_slot INT NULL,
          item_fingerprint VARCHAR(128) NOT NULL,
          quantity INT NOT NULL,
          reference_id BIGINT NULL,
          result_json TEXT NULL,
          error_code VARCHAR(64) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uniq_inventory_operation_key (user_id, idempotency_key),
          KEY idx_inventory_operation_user_time (user_id, created_at),
          CONSTRAINT fk_inventory_operation_user
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
  }

  private void createInventoryReadSnapshots(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE IF NOT EXISTS inventory_read_snapshots (
          player_uuid CHAR(36) NOT NULL,
          inventory_source VARCHAR(24) NOT NULL,
          snapshot_json LONGTEXT NOT NULL,
          captured_epoch_ms BIGINT NOT NULL,
          captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (player_uuid, inventory_source),
          KEY idx_inventory_read_snapshot_captured (captured_epoch_ms)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
  }

  private void migrateMarketBids(Connection connection) throws SQLException {
    if (!columnExists(connection, "market_bids", "bidder_uuid")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD COLUMN bidder_uuid CHAR(36) NOT NULL AFTER bidder_user_id");
    }
    if (!columnExists(connection, "market_bids", "status")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'LEADING' AFTER bid_amount");
    }
    if (!columnExists(connection, "market_bids", "idempotency_key")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD COLUMN idempotency_key VARCHAR(96) NOT NULL AFTER status");
    }
    if (!columnExists(connection, "market_bids", "outbid_at")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD COLUMN outbid_at DATETIME NULL AFTER idempotency_key");
    }
    if (!columnExists(connection, "market_bids", "refunded_at")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD COLUMN refunded_at DATETIME NULL AFTER outbid_at");
    }
    if (!columnExists(connection, "market_bids", "settled_at")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD COLUMN settled_at DATETIME NULL AFTER refunded_at");
    }
    if (!indexExists(connection, "market_bids", "uniq_market_bid_idempotency")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD UNIQUE KEY uniq_market_bid_idempotency (bidder_user_id, idempotency_key)");
    }
    if (!indexExists(connection, "market_bids", "idx_market_bid_listing_status")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD INDEX idx_market_bid_listing_status (listing_id, status, created_at)");
    }
    if (!indexExists(connection, "market_bids", "idx_market_bid_bidder")) {
      execute(
          connection,
          "ALTER TABLE market_bids "
              + "ADD INDEX idx_market_bid_bidder (bidder_user_id, created_at)");
    }
    execute(
        connection,
        "UPDATE market_bids SET status = 'LEADING' "
            + "WHERE status IS NULL OR status = ''");
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
          refund_allowed BOOLEAN NOT NULL DEFAULT TRUE,
          partial_refund_allowed BOOLEAN NOT NULL DEFAULT TRUE,
          refund_policy_json JSON NULL,
          refunded_quantity INT NOT NULL DEFAULT 0,
          refunded_amount BIGINT NOT NULL DEFAULT 0,
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
    if (!columnExists(connection, "market_trades", "refund_allowed")) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN refund_allowed BOOLEAN NOT NULL DEFAULT TRUE AFTER refund_deadline");
    }
    if (!columnExists(connection, "market_trades", "partial_refund_allowed")) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN partial_refund_allowed BOOLEAN NOT NULL DEFAULT TRUE AFTER refund_allowed");
    }
    if (!columnExists(connection, "market_trades", "refund_policy_json")) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN refund_policy_json JSON NULL AFTER partial_refund_allowed");
    }
    if (!columnExists(connection, "market_trades", "refunded_quantity")) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN refunded_quantity INT NOT NULL DEFAULT 0 AFTER refund_policy_json");
    }
    if (!columnExists(connection, "market_trades", "refunded_amount")) {
      execute(connection, "ALTER TABLE market_trades "
          + "ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0 AFTER refunded_quantity");
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
          target_server_id VARCHAR(64) NULL,
          item_blob LONGBLOB NOT NULL,
          quantity INT NOT NULL,
          delivered_quantity INT NOT NULL DEFAULT 0,
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
          KEY idx_market_delivery_target_due (target_server_id, status, next_retry_at),
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
    if (!columnExists(connection, "market_item_deliveries", "target_server_id")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD COLUMN target_server_id VARCHAR(64) NULL AFTER target_uuid");
    }
    if (!columnExists(connection, "market_item_deliveries", "claimed_at")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD COLUMN claimed_at DATETIME NULL AFTER delivered_at");
    }
    if (!columnExists(connection, "market_item_deliveries", "delivered_quantity")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD COLUMN delivered_quantity INT NOT NULL DEFAULT 0 AFTER quantity");
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
    if (!indexExists(connection, "market_item_deliveries", "idx_market_delivery_target_due")) {
      execute(
          connection,
          "ALTER TABLE market_item_deliveries "
              + "ADD INDEX idx_market_delivery_target_due (target_server_id, status, next_retry_at)");
    }

    execute(
        connection,
        "UPDATE market_item_deliveries md "
            + "JOIN market_trades mt ON mt.listing_id = md.listing_id "
            + "SET md.trade_id = mt.id "
            + "WHERE md.delivery_type = 'SALE' AND md.trade_id IS NULL");
    execute(
        connection,
        "UPDATE market_trades mt "
            + "SET status = 'WAIT_CLAIM' "
            + "WHERE mt.status = 'PENDING' "
            + "AND EXISTS ("
            + "  SELECT 1 FROM market_item_deliveries md "
            + "  WHERE md.trade_id = mt.id AND md.status = 'WAIT_CLAIM'"
            + ")");
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

  private void createNotifications(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS notifications (
          id BIGINT NOT NULL AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          type VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
          title VARCHAR(128) NOT NULL,
          content TEXT NOT NULL,
          data_json JSON NULL,
          is_read BOOLEAN NOT NULL DEFAULT FALSE,
          read_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          KEY idx_notifications_user_created (user_id, created_at),
          KEY idx_notifications_user_read (user_id, is_read, created_at),
          CONSTRAINT fk_notifications_user
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void createMailboxItems(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS mailbox_items (
          id BIGINT NOT NULL AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          target_uuid CHAR(36) NOT NULL,
          source_type VARCHAR(24) NOT NULL DEFAULT 'DELIVERY',
          source_ref VARCHAR(64) NULL,
          item_blob LONGBLOB NOT NULL,
          quantity INT NOT NULL DEFAULT 1,
          delivered_quantity INT NOT NULL DEFAULT 0,
          reason VARCHAR(255) NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
          last_error VARCHAR(255) NULL,
          claimed_at DATETIME NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          KEY idx_mailbox_target_status_time (target_uuid, status, created_at),
          KEY idx_mailbox_user_status_time (user_id, status, created_at),
          CONSTRAINT fk_mailbox_user
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateMailboxItems(Connection connection) throws SQLException {
    if (!columnExists(connection, "mailbox_items", "source_type")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'DELIVERY' AFTER target_uuid");
    }
    if (!columnExists(connection, "mailbox_items", "source_ref")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN source_ref VARCHAR(64) NULL AFTER source_type");
    }
    if (!columnExists(connection, "mailbox_items", "item_blob")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN item_blob LONGBLOB NOT NULL AFTER source_ref");
    }
    if (!columnExists(connection, "mailbox_items", "quantity")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN quantity INT NOT NULL DEFAULT 1 AFTER item_blob");
    }
    if (!columnExists(connection, "mailbox_items", "reason")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN reason VARCHAR(255) NULL AFTER quantity");
    }
    if (!columnExists(connection, "mailbox_items", "delivered_quantity")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN delivered_quantity INT NOT NULL DEFAULT 0 AFTER quantity");
    }
    if (!columnExists(connection, "mailbox_items", "status")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER reason");
    }
    if (!columnExists(connection, "mailbox_items", "last_error")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN last_error VARCHAR(255) NULL AFTER status");
    }
    if (!columnExists(connection, "mailbox_items", "claimed_at")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD COLUMN claimed_at DATETIME NULL AFTER last_error");
    }
    if (!indexExists(connection, "mailbox_items", "idx_mailbox_target_status_time")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD INDEX idx_mailbox_target_status_time (target_uuid, status, created_at)");
    }
    if (!indexExists(connection, "mailbox_items", "idx_mailbox_user_status_time")) {
      execute(
          connection,
          "ALTER TABLE mailbox_items "
              + "ADD INDEX idx_mailbox_user_status_time (user_id, status, created_at)");
    }
  }

  private void migrateNotifications(Connection connection) throws SQLException {
    if (!columnExists(connection, "notifications", "data_json")) {
      execute(
          connection,
          "ALTER TABLE notifications "
              + "ADD COLUMN data_json JSON NULL AFTER content");
    }
    if (!columnExists(connection, "notifications", "is_read")) {
      execute(
          connection,
          "ALTER TABLE notifications "
              + "ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE AFTER data_json");
    }
    if (!columnExists(connection, "notifications", "read_at")) {
      execute(
          connection,
          "ALTER TABLE notifications "
              + "ADD COLUMN read_at DATETIME NULL AFTER is_read");
    }
    if (!indexExists(connection, "notifications", "idx_notifications_user_created")) {
      execute(
          connection,
          "ALTER TABLE notifications "
              + "ADD INDEX idx_notifications_user_created (user_id, created_at)");
    }
    if (!indexExists(connection, "notifications", "idx_notifications_user_read")) {
      execute(
          connection,
          "ALTER TABLE notifications "
              + "ADD INDEX idx_notifications_user_read (user_id, is_read, created_at)");
    }
  }

  private void createProductUserUsage(Connection connection) throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS product_user_usage (
          product_id BIGINT NOT NULL,
          user_id BIGINT NOT NULL,
          used_count INT NOT NULL DEFAULT 0,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (product_id, user_id),
          KEY idx_product_user_usage_user (user_id),
          CONSTRAINT fk_product_user_usage_product_id
            FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
          CONSTRAINT fk_product_user_usage_user_id
            FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;
    execute(connection, sql);
  }

  private void migrateProductUserUsage(Connection connection) throws SQLException {
    execute(
        connection,
        "DELETE FROM product_user_usage WHERE used_count <= 0");
  }

  private void migrateLegacyProductScheduleToUtc(Connection connection, java.time.ZoneId businessZone)
      throws SQLException {
    if (readMetaValue(connection, PRODUCT_SCHEDULE_UTC_MIGRATION_KEY) != null) {
      return;
    }

    String selectSql = """
        SELECT id, publish_at, unpublish_at
        FROM products
        WHERE publish_at IS NOT NULL OR unpublish_at IS NOT NULL
        """;
    String updateSql = """
        UPDATE products
        SET publish_at = ?, unpublish_at = ?
        WHERE id = ?
        """;

    try (PreparedStatement select = connection.prepareStatement(selectSql);
         ResultSet resultSet = select.executeQuery();
         PreparedStatement update = connection.prepareStatement(updateSql)) {
      while (resultSet.next()) {
        long id = resultSet.getLong("id");
        LocalDateTime publishAt = resultSet.getObject("publish_at", LocalDateTime.class);
        LocalDateTime unpublishAt = resultSet.getObject("unpublish_at", LocalDateTime.class);
        LocalDateTime publishAtUtc = TimeSupport.businessLocalToUtc(publishAt, businessZone);
        LocalDateTime unpublishAtUtc = TimeSupport.businessLocalToUtc(unpublishAt, businessZone);
        update.setObject(1, publishAtUtc);
        update.setObject(2, unpublishAtUtc);
        update.setLong(3, id);
        update.addBatch();
      }
      update.executeBatch();
    }

    writeMetaValue(connection, PRODUCT_SCHEDULE_UTC_MIGRATION_KEY, businessZone.getId());
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Schema migrations execute only internal DDL strings defined in this class")
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

  private void createSharedBinaryAssets(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE IF NOT EXISTS shared_binary_assets (
          asset_path VARCHAR(512) NOT NULL,
          mime_type VARCHAR(80) NOT NULL,
          content_blob LONGBLOB NOT NULL,
          sha256 VARCHAR(64) NOT NULL,
          owner VARCHAR(128) NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (asset_path),
          KEY idx_shared_binary_asset_hash (sha256)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_binary_assets' "
            + "AND COLUMN_NAME='asset_path'"); ResultSet result = statement.executeQuery()) {
      if (result.next() && result.getLong(1) < 512L) {
        execute(connection,
            "ALTER TABLE shared_binary_assets MODIFY COLUMN asset_path VARCHAR(512) NOT NULL");
      }
    }
  }

  private void createMarketSupplyJournal(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE IF NOT EXISTS market_supply_operations (
          operation_id VARCHAR(128) PRIMARY KEY,
          listing_id BIGINT NOT NULL,
          requested_by BIGINT NOT NULL,
          state VARCHAR(16) NOT NULL,
          expected_version VARCHAR(32) NOT NULL,
          expected_hash VARCHAR(96) NOT NULL,
          requested_quantity INT NOT NULL,
          removed_quantity INT NULL,
          result_json LONGTEXT NULL,
          error_message VARCHAR(500) NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_market_supply_operation_listing (listing_id, state)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    execute(connection, """
        CREATE TABLE IF NOT EXISTS market_supply_operation_evidence (
          operation_id VARCHAR(128) PRIMARY KEY,
          expected_item_quantity INT NOT NULL,
          observed_version VARCHAR(32) NULL,
          observed_item_quantity INT NULL,
          resolution VARCHAR(32) NULL,
          resolved_by BIGINT NULL,
          resolved_at TIMESTAMP NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    execute(connection, """
        CREATE TABLE IF NOT EXISTS market_supply_leases (
          listing_id BIGINT PRIMARY KEY,
          operation_id VARCHAR(128) NOT NULL,
          owner_server VARCHAR(128) NOT NULL,
          lease_until TIMESTAMP NOT NULL,
          KEY idx_market_supply_lease_expiry (lease_until)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
  }

  private void widenHashColumn(Connection connection, String tableName) throws SQLException {
    String sql = "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'item_hash'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tableName);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getLong(1) >= 96L) {
          return;
        }
      }
    }
    execute(connection, "ALTER TABLE " + tableName
        + " MODIFY COLUMN item_hash VARCHAR(96) NOT NULL");
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

  private String readMetaValue(Connection connection, String key) throws SQLException {
    String sql = "SELECT meta_value FROM webshop_meta WHERE meta_key = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return resultSet.getString("meta_value");
      }
    }
  }

  private void writeMetaValue(Connection connection, String key, String value) throws SQLException {
    String sql = MYSQL_SQL_PROVIDER.upsertWebshopMetaSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      statement.setString(2, value);
      statement.executeUpdate();
    }
  }
}
