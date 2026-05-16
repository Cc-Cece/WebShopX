package com.webshopx;

final class SqliteSqlProvider implements SqlProvider {
  @Override
  public DbType dbType() {
    return DbType.SQLITE;
  }

  @Override
  public String forUpdateClause() {
    return "";
  }

  @Override
  public String castAsText(String expression) {
    return "CAST(" + expression + " AS TEXT)";
  }

  @Override
  public String coalesce(String expression, String fallbackExpression) {
    return "COALESCE(" + expression + ", " + fallbackExpression + ")";
  }

  @Override
  public String currentTimestampMinusSecondsExpr() {
    return "datetime(CURRENT_TIMESTAMP, '-' || ? || ' seconds')";
  }

  @Override
  public String insertWalletIfMissingSql() {
    return """
        INSERT INTO wallets (user_id)
        VALUES (?)
        ON CONFLICT(user_id) DO NOTHING
        """;
  }

  @Override
  public String insertWalletLedgerIfAbsentSql() {
    return """
        INSERT INTO wallet_ledger (wallet_id, currency, delta, biz_type, biz_id)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(wallet_id, biz_type, biz_id) DO NOTHING
        """;
  }

  @Override
  public String upsertAdminAccessSql() {
    return """
        INSERT INTO web_admins (user_id, role, active, is_super_admin, permissions_json, template_key)
        VALUES (?, ?, TRUE, ?, ?, ?)
        ON CONFLICT(user_id) DO UPDATE SET
          role = excluded.role,
          active = TRUE,
          is_super_admin = excluded.is_super_admin,
          permissions_json = excluded.permissions_json,
          template_key = excluded.template_key
        """;
  }

  @Override
  public String upsertMaterialVisualSql() {
    return """
        INSERT INTO material_visual_overrides (material_key, display_name_override, icon_path, updated_by)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(material_key) DO UPDATE SET
          display_name_override = excluded.display_name_override,
          icon_path = excluded.icon_path,
          updated_by = excluded.updated_by,
          updated_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String upsertMarketTagSql() {
    return """
        INSERT INTO market_tags (code, display_name, enabled, priority)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(code) DO UPDATE SET
          display_name = excluded.display_name,
          enabled = excluded.enabled,
          priority = excluded.priority
        """;
  }

  @Override
  public String upsertProductUserUsageSql() {
    return """
        INSERT INTO product_user_usage (product_id, user_id, used_count)
        VALUES (?, ?, ?)
        ON CONFLICT(product_id, user_id) DO UPDATE SET
          used_count = product_user_usage.used_count + excluded.used_count,
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
        ON CONFLICT(sku) DO UPDATE SET
          title = excluded.title,
          currency = excluded.currency,
          price = excluded.price,
          product_type = excluded.product_type,
          command_template = excluded.command_template,
          active = TRUE
        """;
  }

  @Override
  public String upsertPlayerPresenceOnlineSql() {
    return """
        INSERT INTO player_presence (mc_uuid, username, server_id, online, updated_at)
        VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP)
        ON CONFLICT(mc_uuid) DO UPDATE SET
          username = excluded.username,
          server_id = excluded.server_id,
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
        ON CONFLICT(code) DO NOTHING
        """;
  }

  @Override
  public String upsertRedeemUsageSql() {
    return """
        INSERT INTO redeem_usage (code, user_id, use_count)
        VALUES (?, ?, 1)
        ON CONFLICT(code, user_id) DO UPDATE SET
          use_count = redeem_usage.use_count + 1,
          used_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String insertRuntimeConfigIfMissingSql() {
    return """
        INSERT INTO runtime_config (config_key, config_value, version)
        VALUES (?, ?, 1)
        ON CONFLICT(config_key) DO NOTHING
        """;
  }

  @Override
  public String upsertRuntimeConfigSql() {
    return """
        INSERT INTO runtime_config (config_key, config_value, version)
        VALUES (?, ?, 1)
        ON CONFLICT(config_key) DO UPDATE SET
          config_value = excluded.config_value,
          version = runtime_config.version + 1,
          updated_at = CURRENT_TIMESTAMP
        """;
  }

  @Override
  public String upsertWebshopMetaSql() {
    return """
        INSERT INTO webshop_meta (meta_key, meta_value)
        VALUES (?, ?)
        ON CONFLICT(meta_key) DO UPDATE SET
          meta_value = excluded.meta_value
        """;
  }

  @Override
  public String upsertUserMarketSettingsSql() {
    return """
        INSERT INTO user_market_settings (user_id, listing_limit_override)
        VALUES (?, ?)
        ON CONFLICT(user_id) DO UPDATE SET
          listing_limit_override = excluded.listing_limit_override,
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
        ON CONFLICT(user_id) DO UPDATE SET
          icon_permission = excluded.icon_permission,
          name_permission = excluded.name_permission,
          upload_permission = excluded.upload_permission,
          updated_at = CURRENT_TIMESTAMP
        """;
  }
}
