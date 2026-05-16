package com.webshopx;

final class MysqlSqlProvider implements SqlProvider {
  private final DbType dbType;

  MysqlSqlProvider(DbType dbType) {
    this.dbType = dbType == null ? DbType.MYSQL : dbType;
  }

  @Override
  public DbType dbType() {
    return dbType;
  }

  @Override
  public String forUpdateClause() {
    return " FOR UPDATE";
  }

  @Override
  public String castAsText(String expression) {
    return "CAST(" + expression + " AS CHAR)";
  }

  @Override
  public String coalesce(String expression, String fallbackExpression) {
    return "COALESCE(" + expression + ", " + fallbackExpression + ")";
  }

  @Override
  public String currentTimestampMinusSecondsExpr() {
    return "DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? SECOND)";
  }

  @Override
  public String insertWalletIfMissingSql() {
    return """
        INSERT INTO wallets (user_id)
        VALUES (?)
        ON DUPLICATE KEY UPDATE user_id = user_id
        """;
  }

  @Override
  public String insertWalletLedgerIfAbsentSql() {
    return """
        INSERT INTO wallet_ledger (wallet_id, currency, delta, biz_type, biz_id)
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE id = id
        """;
  }

  @Override
  public String upsertAdminAccessSql() {
    return """
        INSERT INTO web_admins (user_id, role, active, is_super_admin, permissions_json, template_key)
        VALUES (?, ?, TRUE, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          role = VALUES(role),
          active = TRUE,
          is_super_admin = VALUES(is_super_admin),
          permissions_json = VALUES(permissions_json),
          template_key = VALUES(template_key)
        """;
  }

  @Override
  public String upsertMaterialVisualSql() {
    return """
        INSERT INTO material_visual_overrides (material_key, display_name_override, icon_path, updated_by)
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          display_name_override = VALUES(display_name_override),
          icon_path = VALUES(icon_path),
          updated_by = VALUES(updated_by),
          updated_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String upsertMarketTagSql() {
    return """
        INSERT INTO market_tags (code, display_name, enabled, priority)
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          display_name = VALUES(display_name),
          enabled = VALUES(enabled),
          priority = VALUES(priority)
        """;
  }

  @Override
  public String upsertProductUserUsageSql() {
    return """
        INSERT INTO product_user_usage (product_id, user_id, used_count)
        VALUES (?, ?, ?)
        ON DUPLICATE KEY UPDATE
          used_count = used_count + VALUES(used_count),
          updated_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String upsertProductSeedSql() {
    return """
        INSERT INTO products (
          sku, title, currency, price, product_type, command_template, active
        )
        VALUES (?, ?, ?, ?, 'COMMAND', ?, TRUE)
        ON DUPLICATE KEY UPDATE
          title = VALUES(title),
          currency = VALUES(currency),
          price = VALUES(price),
          product_type = VALUES(product_type),
          command_template = VALUES(command_template),
          active = TRUE
        """;
  }

  @Override
  public String upsertPlayerPresenceOnlineSql() {
    return """
        INSERT INTO player_presence (mc_uuid, username, server_id, online, updated_at)
        VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE
          username = VALUES(username),
          server_id = VALUES(server_id),
          online = TRUE,
          updated_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String insertRedeemCodeIfAbsentSql() {
    return """
        INSERT INTO redeem_codes (
          code, shop_coin, game_coin, max_uses, per_user_max_uses, expires_at, active
        )
        VALUES (?, ?, ?, ?, ?, ?, TRUE)
        ON DUPLICATE KEY UPDATE code = code
        """;
  }

  @Override
  public String upsertRedeemUsageSql() {
    return """
        INSERT INTO redeem_usage (code, user_id, use_count)
        VALUES (?, ?, 1)
        ON DUPLICATE KEY UPDATE
          use_count = use_count + 1,
          used_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String insertRuntimeConfigIfMissingSql() {
    return """
        INSERT INTO runtime_config (config_key, config_value, version)
        VALUES (?, ?, 1)
        ON DUPLICATE KEY UPDATE config_key = config_key
        """;
  }

  @Override
  public String upsertRuntimeConfigSql() {
    return """
        INSERT INTO runtime_config (config_key, config_value, version)
        VALUES (?, ?, 1)
        ON DUPLICATE KEY UPDATE
          config_value = VALUES(config_value),
          version = version + 1,
          updated_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String upsertWebshopMetaSql() {
    return """
        INSERT INTO webshop_meta (meta_key, meta_value)
        VALUES (?, ?)
        ON DUPLICATE KEY UPDATE
          meta_value = VALUES(meta_value)
        """;
  }

  @Override
  public String upsertUserMarketSettingsSql() {
    return """
        INSERT INTO user_market_settings (user_id, listing_limit_override)
        VALUES (?, ?)
        ON DUPLICATE KEY UPDATE
          listing_limit_override = VALUES(listing_limit_override),
          updated_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String upsertVisualSettingsSql() {
    return upsertRuntimeConfigSql();
  }

  @Override
  public String upsertUserVisualPermissionSql() {
    return """
        INSERT INTO user_visual_permissions (user_id, icon_permission, name_permission, upload_permission)
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          icon_permission = VALUES(icon_permission),
          name_permission = VALUES(name_permission),
          upload_permission = VALUES(upload_permission),
          updated_at = CURRENT_TIMESTAMP
        """;
  }
}
