package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** MySQL/MariaDB side of promotion schema v1. SQLite mirrors this in schema.sql. */
final class PromotionSchema {
  static final String CAPABILITY = "promotion_schema_v1";

  private PromotionSchema() {}

  static void installMysql(Connection connection) throws SQLException {
    for (String sql : MYSQL_TABLES) execute(connection, sql);
  }

  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC",
      justification = "Only static internal DDL is executed")
  private static void execute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  private static final List<String> MYSQL_TABLES = List.of(
      """
      CREATE TABLE IF NOT EXISTS commerce_carts (
        id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_commerce_cart_user (user_id),
        CONSTRAINT fk_commerce_cart_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS commerce_cart_lines (
        id BIGINT NOT NULL AUTO_INCREMENT, cart_id BIGINT NOT NULL, source_type VARCHAR(32) NOT NULL,
        source_id BIGINT NOT NULL, quantity INT NOT NULL, delivery_mode VARCHAR(32) NOT NULL DEFAULT '',
        selected BOOLEAN NOT NULL DEFAULT TRUE, source_version VARCHAR(96) NULL, metadata_json JSON NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_cart_source (cart_id, source_type, source_id, delivery_mode),
        KEY idx_cart_lines_selected (cart_id, selected, id),
        CONSTRAINT fk_cart_line_cart FOREIGN KEY (cart_id) REFERENCES commerce_carts(id) ON DELETE CASCADE
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_campaigns (
        id BIGINT NOT NULL AUTO_INCREMENT, code VARCHAR(96) NOT NULL, owner_type VARCHAR(16) NOT NULL,
        owner_id BIGINT NULL, name VARCHAR(255) NOT NULL, description TEXT NULL, status VARCHAR(24) NOT NULL,
        start_at DATETIME NULL, end_at DATETIME NULL, current_version_id BIGINT NULL,
        created_by BIGINT NULL, updated_by BIGINT NULL, version BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_promotion_campaign_code (code),
        KEY idx_promotion_campaign_owner (owner_type, owner_id, status, start_at, end_at),
        KEY idx_promotion_campaign_status (status, start_at, end_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_rule_versions (
        id BIGINT NOT NULL AUTO_INCREMENT, campaign_id BIGINT NOT NULL, version INT NOT NULL,
        rule_kind VARCHAR(40) NOT NULL, direction VARCHAR(24) NOT NULL, layer INT NOT NULL,
        stacking_slot VARCHAR(40) NOT NULL, stacking_policy VARCHAR(40) NOT NULL,
        exclusive_group VARCHAR(96) NULL, priority INT NOT NULL DEFAULT 0, max_per_order INT NOT NULL DEFAULT 1,
        threshold_type VARCHAR(16) NOT NULL, threshold_value BIGINT NOT NULL DEFAULT 0,
        threshold_basis_layer INT NOT NULL, discount_basis_layer INT NOT NULL,
        repeat_mode VARCHAR(32) NOT NULL, max_repeat_count INT NOT NULL DEFAULT 1,
        discount_amount BIGINT NOT NULL DEFAULT 0, discount_bps INT NOT NULL DEFAULT 0,
        max_discount_amount BIGINT NULL, funding_mode VARCHAR(16) NOT NULL,
        platform_share_bps INT NOT NULL DEFAULT 10000, min_payable_override BIGINT NULL,
        allow_zero_payable BOOLEAN NOT NULL DEFAULT FALSE, rule_json JSON NOT NULL, scope_json JSON NOT NULL,
        eligibility_json JSON NOT NULL, stacking_json JSON NOT NULL, refund_policy_json JSON NOT NULL,
        display_json JSON NOT NULL, rules_hash CHAR(64) NOT NULL, created_by BIGINT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_campaign_rule_version (campaign_id, version),
        KEY idx_promotion_rules_hash (rules_hash),
        CONSTRAINT fk_rule_campaign FOREIGN KEY (campaign_id) REFERENCES promotion_campaigns(id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_accounts (
        id BIGINT NOT NULL AUTO_INCREMENT, account_type VARCHAR(32) NOT NULL, owner_type VARCHAR(32) NOT NULL,
        owner_id BIGINT NOT NULL, currency_space VARCHAR(16) NOT NULL, currency VARCHAR(16) NOT NULL DEFAULT '',
        limit_amount BIGINT NULL, limit_count BIGINT NULL, reserved_amount BIGINT NOT NULL DEFAULT 0,
        reserved_count BIGINT NOT NULL DEFAULT 0, consumed_amount BIGINT NOT NULL DEFAULT 0,
        consumed_count BIGINT NOT NULL DEFAULT 0, released_amount BIGINT NOT NULL DEFAULT 0,
        released_count BIGINT NOT NULL DEFAULT 0, refunded_amount BIGINT NOT NULL DEFAULT 0,
        refunded_count BIGINT NOT NULL DEFAULT 0, period_key VARCHAR(32) NOT NULL DEFAULT 'ALL',
        version BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_promotion_account (account_type, owner_type, owner_id, currency_space, currency, period_key)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_account_ledger (
        id BIGINT NOT NULL AUTO_INCREMENT, account_id BIGINT NOT NULL, reserved_delta BIGINT NOT NULL DEFAULT 0,
        consumed_delta BIGINT NOT NULL DEFAULT 0, released_delta BIGINT NOT NULL DEFAULT 0,
        refunded_delta BIGINT NOT NULL DEFAULT 0, biz_type VARCHAR(32) NOT NULL, biz_id VARCHAR(128) NOT NULL,
        detail_json JSON NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_promotion_account_biz (account_id, biz_type, biz_id),
        CONSTRAINT fk_account_ledger_account FOREIGN KEY (account_id) REFERENCES promotion_accounts(id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS coupon_templates (
        id BIGINT NOT NULL AUTO_INCREMENT, code VARCHAR(96) NOT NULL, campaign_id BIGINT NOT NULL,
        rule_version_id BIGINT NOT NULL, owner_type VARCHAR(16) NOT NULL, owner_id BIGINT NULL,
        name VARCHAR(255) NOT NULL, claim_mode VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL,
        issue_limit BIGINT NULL, per_subject_claim_limit INT NOT NULL DEFAULT 1,
        per_subject_use_limit INT NOT NULL DEFAULT 1, validity_mode VARCHAR(24) NOT NULL,
        valid_from DATETIME NULL, valid_until DATETIME NULL, valid_duration_seconds BIGINT NULL,
        created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_coupon_template_code (code)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS user_coupons (
        id BIGINT NOT NULL AUTO_INCREMENT, serial_no VARCHAR(96) NOT NULL, template_id BIGINT NOT NULL,
        rule_version_id BIGINT NOT NULL, user_id BIGINT NOT NULL, status VARCHAR(24) NOT NULL,
        valid_from DATETIME NOT NULL, valid_until DATETIME NULL, source_type VARCHAR(24) NOT NULL,
        source_ref VARCHAR(128) NULL, reserved_quote_id VARCHAR(96) NULL, reserved_until DATETIME NULL,
        consumed_checkout_id BIGINT NULL, consumed_at DATETIME NULL, version BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_user_coupon_serial (serial_no),
        KEY idx_user_coupon_state (user_id, status, valid_until, id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS coupon_events (
        id BIGINT NOT NULL AUTO_INCREMENT, user_coupon_id BIGINT NOT NULL, event_type VARCHAR(24) NOT NULL,
        from_status VARCHAR(24) NULL, to_status VARCHAR(24) NOT NULL, biz_type VARCHAR(32) NOT NULL,
        biz_id VARCHAR(128) NOT NULL, actor_type VARCHAR(24) NOT NULL, actor_id VARCHAR(96) NULL,
        detail_json JSON NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_coupon_event_biz (user_coupon_id, event_type, biz_type, biz_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS membership_plans (
        id BIGINT NOT NULL AUTO_INCREMENT, code VARCHAR(96) NOT NULL, name VARCHAR(255) NOT NULL,
        description TEXT NULL, status VARCHAR(24) NOT NULL, current_version_id BIGINT NULL,
        created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_membership_plan_code (code)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS membership_plan_versions (
        id BIGINT NOT NULL AUTO_INCREMENT, plan_id BIGINT NOT NULL, version INT NOT NULL,
        level_code VARCHAR(40) NOT NULL, level_rank INT NOT NULL, duration_mode VARCHAR(24) NOT NULL,
        duration_value BIGINT NULL, renewal_mode VARCHAR(24) NOT NULL, upgrade_policy_json JSON NOT NULL,
        refund_policy_json JSON NOT NULL, benefits_json JSON NOT NULL, version_hash CHAR(64) NOT NULL,
        created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_plan_version_level (plan_id, version, level_code)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS user_memberships (
        id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, plan_id BIGINT NOT NULL,
        plan_version_id BIGINT NOT NULL, level_code VARCHAR(40) NOT NULL, status VARCHAR(24) NOT NULL,
        starts_at DATETIME NOT NULL, expires_at DATETIME NULL, source_type VARCHAR(24) NOT NULL,
        source_ref VARCHAR(128) NULL, grant_biz_key VARCHAR(160) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, revoked_at DATETIME NULL,
        PRIMARY KEY (id), UNIQUE KEY uniq_membership_grant_biz (grant_biz_key),
        KEY idx_user_membership_active (user_id, status, starts_at, expires_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS user_entitlements (
        id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, entitlement_code VARCHAR(64) NOT NULL,
        source_type VARCHAR(24) NOT NULL, source_ref VARCHAR(128) NULL, starts_at DATETIME NOT NULL,
        expires_at DATETIME NULL, status VARCHAR(24) NOT NULL, grant_biz_key VARCHAR(160) NOT NULL,
        granted_by BIGINT NULL, reason VARCHAR(500) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        revoked_at DATETIME NULL, PRIMARY KEY (id), UNIQUE KEY uniq_entitlement_grant_biz (grant_biz_key),
        KEY idx_user_entitlement_active (user_id, status, starts_at, expires_at, entitlement_code)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_quotes (
        id VARCHAR(96) NOT NULL, quote_type VARCHAR(24) NOT NULL DEFAULT 'CART', user_id BIGINT NOT NULL,
        cart_id BIGINT NULL, cart_version BIGINT NULL, selection_mode VARCHAR(24) NOT NULL,
        input_hash CHAR(64) NOT NULL, rules_hash CHAR(64) NOT NULL, algorithm_version VARCHAR(40) NOT NULL,
        currency_totals_json JSON NOT NULL, result_json LONGTEXT NOT NULL, explanation_json LONGTEXT NOT NULL,
        status VARCHAR(24) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        expires_at DATETIME NOT NULL, consumed_checkout_id BIGINT NULL,
        PRIMARY KEY (id), KEY idx_quote_user_state (user_id, status, expires_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_orders (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_no VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL,
        quote_id VARCHAR(96) NOT NULL, status VARCHAR(32) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
        input_hash CHAR(64) NOT NULL, algorithm_version VARCHAR(40) NOT NULL, rules_hash CHAR(64) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, completed_at DATETIME NULL,
        PRIMARY KEY (id), UNIQUE KEY uniq_checkout_no (checkout_no),
        UNIQUE KEY uniq_checkout_idempotency (user_id, idempotency_key)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_order_groups (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_id BIGINT NOT NULL, group_no VARCHAR(64) NOT NULL,
        business_type VARCHAR(32) NOT NULL, currency_space VARCHAR(16) NOT NULL, currency VARCHAR(16) NOT NULL,
        seller_user_id BIGINT NULL, delivery_mode VARCHAR(32) NULL, status VARCHAR(32) NOT NULL,
        base_amount BIGINT NOT NULL, seller_discount_amount BIGINT NOT NULL, platform_discount_amount BIGINT NOT NULL,
        fee_amount BIGINT NOT NULL, tax_amount BIGINT NOT NULL, buyer_total BIGINT NOT NULL,
        seller_receive BIGINT NOT NULL, platform_funding BIGINT NOT NULL, refunded_amount BIGINT NOT NULL DEFAULT 0,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_checkout_group_no (group_no), KEY idx_checkout_group (checkout_id, id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_order_lines (
        id BIGINT NOT NULL AUTO_INCREMENT, group_id BIGINT NOT NULL, source_type VARCHAR(32) NOT NULL,
        source_id BIGINT NOT NULL, source_version VARCHAR(96) NULL, quantity INT NOT NULL, currency VARCHAR(16) NOT NULL,
        base_amount BIGINT NOT NULL, seller_discount_amount BIGINT NOT NULL, platform_discount_amount BIGINT NOT NULL,
        benefit_offset_amount BIGINT NOT NULL, fee_amount BIGINT NOT NULL, tax_amount BIGINT NOT NULL,
        final_amount BIGINT NOT NULL, seller_receive BIGINT NOT NULL, platform_funding BIGINT NOT NULL,
        refunded_quantity INT NOT NULL DEFAULT 0, refunded_amount BIGINT NOT NULL DEFAULT 0,
        fulfillment_ref_type VARCHAR(32) NULL, fulfillment_ref_id BIGINT NULL, status VARCHAR(32) NOT NULL,
        snapshot_json LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), KEY idx_checkout_lines_group (group_id, id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_price_snapshots (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_id BIGINT NOT NULL, quote_id VARCHAR(96) NOT NULL,
        algorithm_version VARCHAR(40) NOT NULL, input_hash CHAR(64) NOT NULL, rules_hash CHAR(64) NOT NULL,
        snapshot_json LONGTEXT NOT NULL, snapshot_hash CHAR(64) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_checkout_snapshot (checkout_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS benefit_grants (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_id BIGINT NULL, order_line_id BIGINT NULL,
        rule_version_id BIGINT NULL, benefit_type VARCHAR(32) NOT NULL, benefit_ref VARCHAR(128) NULL,
        quantity BIGINT NULL, amount BIGINT NULL, currency VARCHAR(16) NULL, trigger_status VARCHAR(32) NOT NULL,
        status VARCHAR(32) NOT NULL, grant_biz_key VARCHAR(160) NOT NULL, granted_at DATETIME NULL,
        reversed_at DATETIME NULL, detail_json JSON NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_benefit_grant_biz (grant_biz_key)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS seller_promotion_policies (
        seller_user_id BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
        max_discount_bps INT NOT NULL DEFAULT 5000, min_receivable_bps INT NOT NULL DEFAULT 1000,
        max_active_campaigns INT NOT NULL DEFAULT 20, max_coupon_issue INT NOT NULL DEFAULT 10000,
        max_duration_days INT NOT NULL DEFAULT 90, allowed_types_json JSON NOT NULL,
        version BIGINT NOT NULL DEFAULT 1, updated_by BIGINT NULL,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (seller_user_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_rule_scopes (
        id BIGINT NOT NULL AUTO_INCREMENT, rule_version_id BIGINT NOT NULL,
        scope_type VARCHAR(32) NOT NULL, include_mode VARCHAR(16) NOT NULL DEFAULT 'INCLUDE',
        scope_value VARCHAR(255) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_rule_scope (rule_version_id, scope_type, include_mode, scope_value)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_rule_tiers (
        id BIGINT NOT NULL AUTO_INCREMENT, rule_version_id BIGINT NOT NULL, tier_order INT NOT NULL,
        threshold_value BIGINT NOT NULL, discount_amount BIGINT NOT NULL DEFAULT 0,
        discount_bps INT NOT NULL DEFAULT 0, max_discount_amount BIGINT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_rule_tier (rule_version_id, tier_order)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS coupon_codes (
        id BIGINT NOT NULL AUTO_INCREMENT, template_id BIGINT NOT NULL, code_hash CHAR(64) NOT NULL,
        status VARCHAR(24) NOT NULL, max_uses INT NOT NULL DEFAULT 1, used_count INT NOT NULL DEFAULT 0,
        valid_until DATETIME NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_coupon_code_hash (code_hash)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS membership_plan_benefits (
        id BIGINT NOT NULL AUTO_INCREMENT, plan_version_id BIGINT NOT NULL,
        benefit_code VARCHAR(64) NOT NULL, benefit_type VARCHAR(32) NOT NULL,
        benefit_config_json JSON NOT NULL, stacking_slot VARCHAR(40) NULL, priority INT NOT NULL DEFAULT 0,
        PRIMARY KEY (id), UNIQUE KEY uniq_plan_benefit (plan_version_id, benefit_code)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS membership_product_bindings (
        product_id BIGINT NOT NULL, plan_version_id BIGINT NOT NULL,
        grant_trigger_status VARCHAR(24) NOT NULL DEFAULT 'PAID', active BOOLEAN NOT NULL DEFAULT TRUE,
        created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (product_id), KEY idx_membership_binding_version (plan_version_id, active)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS membership_codes (
        id BIGINT NOT NULL AUTO_INCREMENT, code_hash CHAR(64) NOT NULL,
        plan_version_id BIGINT NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
        max_uses INT NOT NULL DEFAULT 1, used_count INT NOT NULL DEFAULT 0,
        valid_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, valid_until DATETIME NULL,
        created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_membership_code_hash (code_hash)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS membership_code_redemptions (
        id BIGINT NOT NULL AUTO_INCREMENT, membership_code_id BIGINT NOT NULL,
        user_id BIGINT NOT NULL, membership_id BIGINT NOT NULL, request_id VARCHAR(128) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_membership_redeem_request (user_id, request_id),
        UNIQUE KEY uniq_membership_code_user (membership_code_id, user_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS membership_events (
        id BIGINT NOT NULL AUTO_INCREMENT, membership_id BIGINT NOT NULL,
        event_type VARCHAR(24) NOT NULL, from_status VARCHAR(24) NULL, to_status VARCHAR(24) NOT NULL,
        biz_type VARCHAR(32) NOT NULL, biz_id VARCHAR(128) NOT NULL, actor_id BIGINT NULL,
        detail_json JSON NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_membership_event (membership_id, event_type, biz_type, biz_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_subject_usage (
        id BIGINT NOT NULL AUTO_INCREMENT, rule_version_id BIGINT NOT NULL,
        subject_type VARCHAR(24) NOT NULL, subject_id VARCHAR(128) NOT NULL, period_key VARCHAR(32) NOT NULL,
        used_count BIGINT NOT NULL DEFAULT 0, used_amount BIGINT NOT NULL DEFAULT 0,
        version BIGINT NOT NULL DEFAULT 1,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_rule_subject_period (rule_version_id, subject_type, subject_id, period_key)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_discounts (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_id BIGINT NOT NULL,
        rule_version_id BIGINT NULL, rule_id VARCHAR(96) NOT NULL, campaign_id BIGINT NULL,
        user_coupon_id BIGINT NULL, layer INT NOT NULL, stacking_slot VARCHAR(40) NOT NULL,
        discount_amount BIGINT NOT NULL, funding_mode VARCHAR(16) NOT NULL,
        platform_funding BIGINT NOT NULL DEFAULT 0, seller_funding BIGINT NOT NULL DEFAULT 0,
        application_json JSON NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), KEY idx_checkout_discounts_checkout (checkout_id, id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_discount_allocations (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_discount_id BIGINT NOT NULL,
        checkout_line_id BIGINT NOT NULL, allocated_amount BIGINT NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_discount_line (checkout_discount_id, checkout_line_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_funding_shares (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_line_id BIGINT NOT NULL,
        checkout_discount_id BIGINT NULL, funder_type VARCHAR(16) NOT NULL, funder_id BIGINT NULL,
        amount BIGINT NOT NULL, currency VARCHAR(16) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), KEY idx_funding_line (checkout_line_id, id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS checkout_line_payment_units (
        id BIGINT NOT NULL AUTO_INCREMENT, checkout_line_id BIGINT NOT NULL, unit_index INT NOT NULL,
        base_amount BIGINT NOT NULL, discount_amount BIGINT NOT NULL, final_amount BIGINT NOT NULL,
        refunded BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_checkout_line_unit (checkout_line_id, unit_index)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS commerce_refund_adjustments (
        id BIGINT NOT NULL AUTO_INCREMENT, refund_request_id BIGINT NULL,
        checkout_line_id BIGINT NOT NULL, adjustment_type VARCHAR(32) NOT NULL,
        funder_type VARCHAR(16) NULL, amount BIGINT NOT NULL, currency VARCHAR(16) NOT NULL,
        detail_json JSON NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id), KEY idx_refund_adjustment_line (checkout_line_id, id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_collections (
        id BIGINT NOT NULL AUTO_INCREMENT, code VARCHAR(96) NOT NULL, name VARCHAR(255) NOT NULL,
        status VARCHAR(24) NOT NULL, display_json JSON NOT NULL, created_by BIGINT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id), UNIQUE KEY uniq_promotion_collection_code (code)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """,
      """
      CREATE TABLE IF NOT EXISTS promotion_collection_items (
        collection_id BIGINT NOT NULL, source_type VARCHAR(32) NOT NULL, source_id BIGINT NOT NULL,
        sort_order INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (collection_id, source_type, source_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """
  );
}
