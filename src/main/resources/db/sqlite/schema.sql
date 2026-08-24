CREATE TABLE IF NOT EXISTS web_users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  auth_state TEXT NOT NULL DEFAULT 'ACTIVE',
  bound_uuid TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_username ON web_users (username);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_bound_uuid ON web_users (bound_uuid);

CREATE TABLE IF NOT EXISTS web_sessions (
  token TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token),
  CONSTRAINT fk_web_sessions_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_web_sessions_user_id ON web_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_web_sessions_expires_at ON web_sessions (expires_at);

CREATE TABLE IF NOT EXISTS web_admins (
  user_id INTEGER NOT NULL,
  role TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  is_super_admin INTEGER NOT NULL DEFAULT 0,
  permissions_json TEXT NULL,
  template_key TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_web_admins_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_web_admins_role_active ON web_admins (role, active);

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
  CONSTRAINT fk_admin_audit_admin_user FOREIGN KEY (admin_user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_admin_audit_admin_time ON admin_audit_logs (admin_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_admin_audit_action_time ON admin_audit_logs (action, created_at);

CREATE TABLE IF NOT EXISTS bind_requests (
  bind_code TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  expires_at DATETIME NOT NULL,
  used INTEGER NOT NULL DEFAULT 0,
  used_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (bind_code),
  CONSTRAINT fk_bind_requests_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_bind_requests_user_id ON bind_requests (user_id);

CREATE TABLE IF NOT EXISTS player_presence (
  mc_uuid TEXT NOT NULL,
  username TEXT NOT NULL,
  server_id TEXT NOT NULL,
  online INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (mc_uuid)
);
CREATE INDEX IF NOT EXISTS idx_player_presence_server_online ON player_presence (server_id, online, updated_at);
CREATE INDEX IF NOT EXISTS idx_player_presence_online_updated ON player_presence (online, updated_at);

CREATE TABLE IF NOT EXISTS wallets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  shop_coin INTEGER NOT NULL DEFAULT 0,
  game_coin INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_wallets_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_wallet_user_id ON wallets (user_id);

CREATE TABLE IF NOT EXISTS wallet_ledger (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  wallet_id INTEGER NOT NULL,
  currency TEXT NOT NULL,
  delta INTEGER NOT NULL,
  biz_type TEXT NOT NULL,
  biz_id TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_wallet_ledger_wallet_id FOREIGN KEY (wallet_id) REFERENCES wallets(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_wallet_biz ON wallet_ledger (wallet_id, biz_type, biz_id);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_wallet_id ON wallet_ledger (wallet_id);

CREATE TABLE IF NOT EXISTS webshopx_recharge_order (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  player_uuid TEXT NULL,
  amount_minor INTEGER NOT NULL,
  currency TEXT NOT NULL,
  coin_amount INTEGER NOT NULL,
  status TEXT NOT NULL,
  provider TEXT NULL,
  provider_order_id TEXT NULL,
  pay_url TEXT NULL,
  qr_code_url TEXT NULL,
  expire_time DATETIME NULL,
  paid_time DATETIME NULL,
  credited_time DATETIME NULL,
  metadata TEXT NULL,
  error_code TEXT NULL,
  error_message TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_recharge_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_recharge_order_id ON webshopx_recharge_order (order_id);
CREATE INDEX IF NOT EXISTS idx_recharge_user_id ON webshopx_recharge_order (user_id);
CREATE INDEX IF NOT EXISTS idx_recharge_player_uuid ON webshopx_recharge_order (player_uuid);
CREATE INDEX IF NOT EXISTS idx_recharge_provider_order_id ON webshopx_recharge_order (provider_order_id);
CREATE INDEX IF NOT EXISTS idx_recharge_status ON webshopx_recharge_order (status);

CREATE TABLE IF NOT EXISTS redeem_codes (
  code TEXT NOT NULL,
  shop_coin INTEGER NOT NULL DEFAULT 0,
  game_coin INTEGER NOT NULL DEFAULT 0,
  max_uses INTEGER NOT NULL DEFAULT 1,
  per_user_max_uses INTEGER NOT NULL DEFAULT 1,
  used_count INTEGER NOT NULL DEFAULT 0,
  expires_at DATETIME NULL,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (code)
);

CREATE TABLE IF NOT EXISTS redeem_usage (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  use_count INTEGER NOT NULL DEFAULT 0,
  used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_redeem_usage_code FOREIGN KEY (code) REFERENCES redeem_codes(code) ON DELETE CASCADE,
  CONSTRAINT fk_redeem_usage_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_redeem_usage ON redeem_usage (code, user_id);

CREATE TABLE IF NOT EXISTS webshop_meta (
  meta_key TEXT NOT NULL,
  meta_value TEXT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (meta_key)
);

CREATE TABLE IF NOT EXISTS runtime_config (
  config_key TEXT NOT NULL,
  config_value TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (config_key)
);

CREATE TABLE IF NOT EXISTS user_visual_permissions (
  user_id INTEGER NOT NULL,
  icon_permission TEXT NOT NULL DEFAULT 'INHERIT',
  name_permission TEXT NOT NULL DEFAULT 'INHERIT',
  upload_permission TEXT NOT NULL DEFAULT 'INHERIT',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_user_visual_permissions_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_market_settings (
  user_id INTEGER NOT NULL,
  listing_limit_override INTEGER NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_user_market_settings_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS material_visual_overrides (
  material_key TEXT NOT NULL,
  display_name_override TEXT NULL,
  icon_path TEXT NULL,
  updated_by TEXT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (material_key)
);
CREATE INDEX IF NOT EXISTS idx_material_visual_updated ON material_visual_overrides (updated_at);

CREATE TABLE IF NOT EXISTS official_item_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_hash TEXT NOT NULL,
  item_blob BLOB NOT NULL,
  item_meta_json TEXT NOT NULL,
  item_material TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_official_item_snapshot_hash
  ON official_item_snapshots (item_hash);

CREATE TABLE IF NOT EXISTS product_item_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  product_id INTEGER NOT NULL,
  snapshot_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  item_hash TEXT NOT NULL,
  created_by INTEGER NULL,
  active_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_product_snapshot_product FOREIGN KEY (product_id) REFERENCES products(id),
  CONSTRAINT fk_product_snapshot_blob FOREIGN KEY (snapshot_id) REFERENCES official_item_snapshots(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_product_item_snapshot_version
  ON product_item_snapshots (product_id, version);

CREATE TABLE IF NOT EXISTS products (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sku TEXT NOT NULL,
  title TEXT NOT NULL,
  remark TEXT NULL,
  currency TEXT NOT NULL,
  price INTEGER NOT NULL,
  product_type TEXT NOT NULL DEFAULT 'COMMAND',
  command_template TEXT NOT NULL,
  item_material TEXT NULL,
  display_name_override TEXT NULL,
  display_material TEXT NULL,
  display_icon_path TEXT NULL,
  item_amount INTEGER NULL,
  stock_remaining INTEGER NULL,
  per_user_limit INTEGER NULL,
  effect_type TEXT NULL,
  effect_seconds INTEGER NULL,
  effect_amplifier INTEGER NULL,
  snapshot_id INTEGER NULL,
  inventory_mode TEXT NOT NULL DEFAULT 'TEMPLATE',
  dynamic_pricing_enabled INTEGER NOT NULL DEFAULT 0,
  dynamic_algorithm TEXT NOT NULL DEFAULT 'LINEAR_DEMAND_V1',
  dynamic_pricing_mode TEXT NOT NULL DEFAULT 'ORDER_FIXED',
  dynamic_params_json TEXT NULL,
  dynamic_base_price INTEGER NULL,
  dynamic_floor_price INTEGER NULL,
  dynamic_cap_price INTEGER NULL,
  dynamic_price_step INTEGER NULL,
  dynamic_demand_score INTEGER NOT NULL DEFAULT 0,
  refund_policy TEXT NOT NULL DEFAULT 'INHERIT',
  refund_window_minutes INTEGER NULL,
  partial_refund_policy TEXT NOT NULL DEFAULT 'INHERIT',
  publish_at DATETIME NULL,
  unpublish_at DATETIME NULL,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_product_snapshot_id FOREIGN KEY (snapshot_id) REFERENCES official_item_snapshots(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_products_sku ON products (sku);

CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_no TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  mc_uuid TEXT NOT NULL,
  currency TEXT NOT NULL,
  total_amount INTEGER NOT NULL,
  status TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  target_server_id TEXT NULL,
  claim_token TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  refund_deadline DATETIME NULL,
  refund_allowed INTEGER NOT NULL DEFAULT 1,
  partial_refund_allowed INTEGER NOT NULL DEFAULT 1,
  refund_policy_json TEXT NULL,
  refunded_quantity INTEGER NOT NULL DEFAULT 0,
  refunded_amount INTEGER NOT NULL DEFAULT 0,
  delivered_at DATETIME NULL,
  refunded_at DATETIME NULL,
  CONSTRAINT fk_orders_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_orders_order_no ON orders (order_no);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_orders_idempotency ON orders (user_id, idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_orders_claim_token ON orders (claim_token);
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders (user_id);
CREATE INDEX IF NOT EXISTS idx_orders_target_server ON orders (target_server_id, status, created_at);

CREATE TABLE IF NOT EXISTS refund_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  order_ref TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PROCESSING',
  refund_amount INTEGER NOT NULL DEFAULT 0,
  refund_quantity INTEGER NOT NULL DEFAULT 0,
  error_code TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  CONSTRAINT fk_refund_request_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_refund_request_key
  ON refund_requests (user_id, idempotency_key);

CREATE TABLE IF NOT EXISTS order_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL,
  product_id INTEGER NOT NULL,
  quantity INTEGER NOT NULL,
  unit_price INTEGER NOT NULL,
  CONSTRAINT fk_order_items_order_id FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_order_items_product_id FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_order_product ON order_items (order_id, product_id);

CREATE TABLE IF NOT EXISTS delivery_queue (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL,
  item_id INTEGER NOT NULL,
  mc_uuid TEXT NOT NULL,
  target_server_id TEXT NULL,
  command_text TEXT NOT NULL,
  delivery_kind TEXT NOT NULL DEFAULT 'COMMAND',
  payload_json TEXT NULL,
  manual_claim INTEGER NOT NULL DEFAULT 0,
  quantity INTEGER NOT NULL,
  delivered_quantity INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'PENDING',
  retry_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  next_retry_at DATETIME NOT NULL,
  delivered_at DATETIME NULL,
  claimed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_delivery_order_id FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_delivery_item_id FOREIGN KEY (item_id) REFERENCES order_items(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_delivery_order_item ON delivery_queue (order_id, item_id);
CREATE INDEX IF NOT EXISTS idx_delivery_due ON delivery_queue (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_delivery_target_due ON delivery_queue (target_server_id, status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_delivery_claim ON delivery_queue (mc_uuid, status, created_at);

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
  display_name_override TEXT NULL,
  display_material TEXT NULL,
  display_icon_path TEXT NULL,
  raw_item_blob BLOB NOT NULL,
  item_meta_json TEXT NOT NULL,
  remark TEXT NULL,
  item_hash TEXT NOT NULL,
  tag_code TEXT NOT NULL DEFAULT 'default',
  tag_version INTEGER NOT NULL DEFAULT 1,
  escrow_total INTEGER NOT NULL DEFAULT 0,
  escrow_remaining INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sold_at DATETIME NULL,
  unlisted_at DATETIME NULL,
  paused_at DATETIME NULL,
  source_mode TEXT NOT NULL DEFAULT 'MANUAL',
  supply_world TEXT NULL,
  supply_x INTEGER NULL,
  supply_y INTEGER NULL,
  supply_z INTEGER NULL,
  supply_batch_size INTEGER NULL,
  supply_max_stock INTEGER NULL,
  supply_access_protected INTEGER NOT NULL DEFAULT 1,
  supply_loaded_total INTEGER NOT NULL DEFAULT 0,
  supply_sold_total INTEGER NOT NULL DEFAULT 0,
  supply_last_loaded_amount INTEGER NULL,
  supply_last_loaded_at DATETIME NULL,
  trade_mode TEXT NOT NULL DEFAULT 'DIRECT',
  market_side TEXT NOT NULL DEFAULT 'SELL',
  dynamic_pricing_enabled INTEGER NOT NULL DEFAULT 0,
  dynamic_algorithm TEXT NOT NULL DEFAULT 'LINEAR_DEMAND_V1',
  dynamic_pricing_mode TEXT NOT NULL DEFAULT 'ORDER_FIXED',
  dynamic_base_price INTEGER NULL,
  dynamic_floor_price INTEGER NULL,
  dynamic_cap_price INTEGER NULL,
  dynamic_price_step INTEGER NULL,
  dynamic_demand_score INTEGER NOT NULL DEFAULT 0,
  dynamic_params_json TEXT NULL,
  auction_algorithm TEXT NOT NULL DEFAULT 'ENGLISH_AUCTION_V1',
  auction_start_price INTEGER NULL,
  auction_min_increment INTEGER NULL,
  auction_started_at DATETIME NULL,
  auction_public_end_at DATETIME NULL,
  auction_params_json TEXT NULL,
  auction_end_at DATETIME NULL,
  auction_highest_bid INTEGER NULL,
  auction_highest_bidder_user_id INTEGER NULL,
  auction_highest_bidder_uuid TEXT NULL,
  auction_highest_bid_id INTEGER NULL,
  auction_last_bid_at DATETIME NULL,
  CONSTRAINT fk_market_listing_seller FOREIGN KEY (seller_user_id) REFERENCES web_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_market_listing_buyer FOREIGN KEY (buyer_user_id) REFERENCES web_users(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_market_listing_status ON market_listings (status, created_at);
CREATE INDEX IF NOT EXISTS idx_market_listing_seller ON market_listings (seller_user_id, status);
CREATE INDEX IF NOT EXISTS idx_market_listing_side_status_created ON market_listings (market_side, status, id);
CREATE INDEX IF NOT EXISTS idx_market_listing_side_tag_status ON market_listings (market_side, tag_code, status, id);
CREATE INDEX IF NOT EXISTS idx_market_listing_auction_due ON market_listings (trade_mode, status, auction_end_at);
CREATE INDEX IF NOT EXISTS idx_market_supply_location ON market_listings (source_mode, status, supply_world, supply_x, supply_y, supply_z);

CREATE TABLE IF NOT EXISTS market_tags (
  code TEXT NOT NULL,
  display_name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  priority INTEGER NOT NULL DEFAULT 1000,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (code)
);
CREATE INDEX IF NOT EXISTS idx_market_tags_enabled_priority ON market_tags (enabled, priority, code);

CREATE TABLE IF NOT EXISTS market_listing_tags (
  listing_id INTEGER NOT NULL,
  tag_code TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'MANUAL',
  position INTEGER NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (listing_id, tag_code),
  CONSTRAINT fk_market_listing_tags_listing
    FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
  CONSTRAINT fk_market_listing_tags_tag
    FOREIGN KEY (tag_code) REFERENCES market_tags(code) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_market_listing_tags_tag
  ON market_listing_tags (tag_code, listing_id);

CREATE TABLE IF NOT EXISTS market_bids (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  listing_id INTEGER NOT NULL,
  bidder_user_id INTEGER NOT NULL,
  bidder_uuid TEXT NOT NULL,
  bid_amount INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'LEADING',
  idempotency_key TEXT NOT NULL,
  outbid_at DATETIME NULL,
  refunded_at DATETIME NULL,
  settled_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_market_bid_listing FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
  CONSTRAINT fk_market_bid_bidder FOREIGN KEY (bidder_user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_market_bid_idempotency ON market_bids (bidder_user_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_market_bid_listing_status ON market_bids (listing_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_market_bid_bidder ON market_bids (bidder_user_id, created_at);

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
  claim_token TEXT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  refund_deadline DATETIME NULL,
  refund_allowed INTEGER NOT NULL DEFAULT 1,
  partial_refund_allowed INTEGER NOT NULL DEFAULT 1,
  refund_policy_json TEXT NULL,
  refunded_quantity INTEGER NOT NULL DEFAULT 0,
  refunded_amount INTEGER NOT NULL DEFAULT 0,
  refunded_at DATETIME NULL,
  settled_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_market_trade_listing FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
  CONSTRAINT fk_market_trade_buyer FOREIGN KEY (buyer_user_id) REFERENCES web_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_market_trade_seller FOREIGN KEY (seller_user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_market_trade_idempotency ON market_trades (buyer_user_id, idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_market_trade_claim_token ON market_trades (claim_token);
CREATE INDEX IF NOT EXISTS idx_market_trade_listing_time ON market_trades (listing_id, created_at);
CREATE INDEX IF NOT EXISTS idx_market_trade_buyer ON market_trades (buyer_user_id, created_at);

CREATE TABLE IF NOT EXISTS market_item_deliveries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  listing_id INTEGER NOT NULL,
  trade_id INTEGER NULL,
  target_user_id INTEGER NOT NULL,
  target_uuid TEXT NOT NULL,
  target_server_id TEXT NULL,
  item_blob BLOB NOT NULL,
  quantity INTEGER NOT NULL,
  delivered_quantity INTEGER NOT NULL DEFAULT 0,
  delivery_type TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  retry_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  next_retry_at DATETIME NOT NULL,
  delivered_at DATETIME NULL,
  claimed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_market_delivery_listing FOREIGN KEY (listing_id) REFERENCES market_listings(id) ON DELETE CASCADE,
  CONSTRAINT fk_market_delivery_trade FOREIGN KEY (trade_id) REFERENCES market_trades(id) ON DELETE SET NULL,
  CONSTRAINT fk_market_delivery_user FOREIGN KEY (target_user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_market_delivery_trade_type ON market_item_deliveries (trade_id, delivery_type);
CREATE INDEX IF NOT EXISTS idx_market_delivery_listing_type ON market_item_deliveries (listing_id, delivery_type);
CREATE INDEX IF NOT EXISTS idx_market_delivery_due ON market_item_deliveries (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_market_delivery_target_due ON market_item_deliveries (target_server_id, status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_market_delivery_claim ON market_item_deliveries (target_uuid, status, created_at);

CREATE TABLE IF NOT EXISTS group_buy_vouchers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL,
  order_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  product_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'ISSUED',
  consumed_by_admin_id INTEGER NULL,
  consumed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_group_buy_voucher_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_group_buy_voucher_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_group_buy_voucher_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
  CONSTRAINT fk_group_buy_voucher_admin FOREIGN KEY (consumed_by_admin_id) REFERENCES web_users(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_group_buy_voucher_code ON group_buy_vouchers (code);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_group_buy_voucher_order ON group_buy_vouchers (order_id);
CREATE INDEX IF NOT EXISTS idx_group_buy_voucher_status_time ON group_buy_vouchers (status, created_at);

CREATE TABLE IF NOT EXISTS notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  type TEXT NOT NULL DEFAULT 'GENERAL',
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  data_json TEXT NULL,
  is_read INTEGER NOT NULL DEFAULT 0,
  read_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_notifications_user_read ON notifications (user_id, is_read, created_at);

CREATE TABLE IF NOT EXISTS mailbox_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  target_uuid TEXT NOT NULL,
  source_type TEXT NOT NULL DEFAULT 'DELIVERY',
  source_ref TEXT NULL,
  item_blob BLOB NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 1,
  delivered_quantity INTEGER NOT NULL DEFAULT 0,
  reason TEXT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  last_error TEXT NULL,
  claimed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_mailbox_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_mailbox_target_status_time ON mailbox_items (target_uuid, status, created_at);
CREATE INDEX IF NOT EXISTS idx_mailbox_user_status_time ON mailbox_items (user_id, status, created_at);

CREATE TABLE IF NOT EXISTS product_user_usage (
  product_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  used_count INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (product_id, user_id),
  CONSTRAINT fk_product_user_usage_product_id FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
  CONSTRAINT fk_product_user_usage_user_id FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_product_user_usage_user ON product_user_usage (user_id);

CREATE TABLE IF NOT EXISTS inventory_operations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  idempotency_key TEXT NOT NULL,
  action TEXT NOT NULL,
  state TEXT NOT NULL,
  slot_index INTEGER NOT NULL,
  container_slot INTEGER NULL,
  item_fingerprint TEXT NOT NULL,
  quantity INTEGER NOT NULL,
  reference_id INTEGER NULL,
  result_json TEXT NULL,
  error_code TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_inventory_operation_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_inventory_operation_key
  ON inventory_operations (user_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_inventory_operation_user_time
  ON inventory_operations (user_id, created_at);

CREATE TABLE IF NOT EXISTS inventory_read_snapshots (
  player_uuid TEXT NOT NULL,
  inventory_source TEXT NOT NULL,
  snapshot_json TEXT NOT NULL,
  captured_epoch_ms INTEGER NOT NULL,
  captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (player_uuid, inventory_source)
);
CREATE INDEX IF NOT EXISTS idx_inventory_read_snapshot_captured
  ON inventory_read_snapshots (captured_epoch_ms);

CREATE TABLE IF NOT EXISTS visual_packs (
  pack_id TEXT PRIMARY KEY,
  pack_name TEXT NOT NULL,
  version_id TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 0,
  sort_order INTEGER NOT NULL DEFAULT 0,
  icons_enabled INTEGER NOT NULL DEFAULT 1,
  translations_enabled INTEGER NOT NULL DEFAULT 0,
  manifest_json TEXT NOT NULL,
  file_size INTEGER NOT NULL DEFAULT 0,
  entry_count INTEGER NOT NULL DEFAULT 0,
  uploaded_by TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_visual_packs_order
  ON visual_packs (enabled, sort_order);

CREATE TABLE IF NOT EXISTS shared_binary_assets (
  asset_path TEXT PRIMARY KEY,
  mime_type TEXT NOT NULL,
  content_blob BLOB NOT NULL,
  sha256 TEXT NOT NULL,
  owner TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_shared_binary_asset_hash
  ON shared_binary_assets (sha256);

-- Promotion, membership, cart and checkout schema capability v1.
CREATE TABLE IF NOT EXISTS commerce_carts (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL UNIQUE,
  version INTEGER NOT NULL DEFAULT 1, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS commerce_cart_lines (
  id INTEGER PRIMARY KEY AUTOINCREMENT, cart_id INTEGER NOT NULL, source_type TEXT NOT NULL,
  source_id INTEGER NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0),
  delivery_mode TEXT NOT NULL DEFAULT '', selected INTEGER NOT NULL DEFAULT 1,
  source_version TEXT NULL, metadata_json TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (cart_id, source_type, source_id, delivery_mode),
  FOREIGN KEY (cart_id) REFERENCES commerce_carts(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_cart_lines_selected ON commerce_cart_lines (cart_id, selected, id);

CREATE TABLE IF NOT EXISTS promotion_campaigns (
  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, owner_type TEXT NOT NULL,
  owner_id INTEGER NULL, name TEXT NOT NULL, description TEXT NULL, status TEXT NOT NULL,
  start_at DATETIME NULL, end_at DATETIME NULL, current_version_id INTEGER NULL,
  created_by INTEGER NULL, updated_by INTEGER NULL, version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_promotion_campaign_owner
  ON promotion_campaigns (owner_type, owner_id, status, start_at, end_at);
CREATE INDEX IF NOT EXISTS idx_promotion_campaign_status
  ON promotion_campaigns (status, start_at, end_at);
CREATE TABLE IF NOT EXISTS promotion_rule_versions (
  id INTEGER PRIMARY KEY AUTOINCREMENT, campaign_id INTEGER NOT NULL, version INTEGER NOT NULL,
  rule_kind TEXT NOT NULL, direction TEXT NOT NULL, layer INTEGER NOT NULL,
  stacking_slot TEXT NOT NULL, stacking_policy TEXT NOT NULL, exclusive_group TEXT NULL,
  priority INTEGER NOT NULL DEFAULT 0, max_per_order INTEGER NOT NULL DEFAULT 1,
  threshold_type TEXT NOT NULL, threshold_value INTEGER NOT NULL DEFAULT 0,
  threshold_basis_layer INTEGER NOT NULL, discount_basis_layer INTEGER NOT NULL,
  repeat_mode TEXT NOT NULL, max_repeat_count INTEGER NOT NULL DEFAULT 1,
  discount_amount INTEGER NOT NULL DEFAULT 0, discount_bps INTEGER NOT NULL DEFAULT 0,
  max_discount_amount INTEGER NULL, funding_mode TEXT NOT NULL,
  platform_share_bps INTEGER NOT NULL DEFAULT 10000, min_payable_override INTEGER NULL,
  allow_zero_payable INTEGER NOT NULL DEFAULT 0, rule_json TEXT NOT NULL, scope_json TEXT NOT NULL,
  eligibility_json TEXT NOT NULL, stacking_json TEXT NOT NULL, refund_policy_json TEXT NOT NULL,
  display_json TEXT NOT NULL, rules_hash TEXT NOT NULL, created_by INTEGER NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (campaign_id, version), FOREIGN KEY (campaign_id) REFERENCES promotion_campaigns(id)
);
CREATE INDEX IF NOT EXISTS idx_promotion_rules_hash ON promotion_rule_versions (rules_hash);

CREATE TABLE IF NOT EXISTS promotion_accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT, account_type TEXT NOT NULL, owner_type TEXT NOT NULL,
  owner_id INTEGER NOT NULL, currency_space TEXT NOT NULL, currency TEXT NOT NULL DEFAULT '',
  limit_amount INTEGER NULL, limit_count INTEGER NULL, reserved_amount INTEGER NOT NULL DEFAULT 0,
  reserved_count INTEGER NOT NULL DEFAULT 0, consumed_amount INTEGER NOT NULL DEFAULT 0,
  consumed_count INTEGER NOT NULL DEFAULT 0, released_amount INTEGER NOT NULL DEFAULT 0,
  released_count INTEGER NOT NULL DEFAULT 0, refunded_amount INTEGER NOT NULL DEFAULT 0,
  refunded_count INTEGER NOT NULL DEFAULT 0, period_key TEXT NOT NULL DEFAULT 'ALL',
  version INTEGER NOT NULL DEFAULT 1, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (account_type, owner_type, owner_id, currency_space, currency, period_key)
);
CREATE TABLE IF NOT EXISTS promotion_account_ledger (
  id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL,
  reserved_delta INTEGER NOT NULL DEFAULT 0, consumed_delta INTEGER NOT NULL DEFAULT 0,
  released_delta INTEGER NOT NULL DEFAULT 0, refunded_delta INTEGER NOT NULL DEFAULT 0,
  biz_type TEXT NOT NULL, biz_id TEXT NOT NULL, detail_json TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (account_id, biz_type, biz_id),
  FOREIGN KEY (account_id) REFERENCES promotion_accounts(id)
);

CREATE TABLE IF NOT EXISTS coupon_templates (
  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, campaign_id INTEGER NOT NULL,
  rule_version_id INTEGER NOT NULL, owner_type TEXT NOT NULL, owner_id INTEGER NULL,
  name TEXT NOT NULL, claim_mode TEXT NOT NULL, status TEXT NOT NULL, issue_limit INTEGER NULL,
  per_subject_claim_limit INTEGER NOT NULL DEFAULT 1, per_subject_use_limit INTEGER NOT NULL DEFAULT 1,
  validity_mode TEXT NOT NULL, valid_from DATETIME NULL, valid_until DATETIME NULL,
  valid_duration_seconds INTEGER NULL, created_by INTEGER NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS user_coupons (
  id INTEGER PRIMARY KEY AUTOINCREMENT, serial_no TEXT NOT NULL UNIQUE, template_id INTEGER NOT NULL,
  rule_version_id INTEGER NOT NULL, user_id INTEGER NOT NULL, status TEXT NOT NULL,
  valid_from DATETIME NOT NULL, valid_until DATETIME NULL, source_type TEXT NOT NULL,
  source_ref TEXT NULL, reserved_quote_id TEXT NULL, reserved_until DATETIME NULL,
  consumed_checkout_id INTEGER NULL, consumed_at DATETIME NULL, version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_coupon_state ON user_coupons (user_id, status, valid_until, id);
CREATE TABLE IF NOT EXISTS coupon_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_coupon_id INTEGER NOT NULL, event_type TEXT NOT NULL,
  from_status TEXT NULL, to_status TEXT NOT NULL, biz_type TEXT NOT NULL, biz_id TEXT NOT NULL,
  actor_type TEXT NOT NULL, actor_id TEXT NULL, detail_json TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_coupon_id, event_type, biz_type, biz_id)
);

CREATE TABLE IF NOT EXISTS membership_plans (
  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  description TEXT NULL, status TEXT NOT NULL, current_version_id INTEGER NULL,
  created_by INTEGER NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS membership_plan_versions (
  id INTEGER PRIMARY KEY AUTOINCREMENT, plan_id INTEGER NOT NULL, version INTEGER NOT NULL,
  level_code TEXT NOT NULL, level_rank INTEGER NOT NULL, duration_mode TEXT NOT NULL,
  duration_value INTEGER NULL, renewal_mode TEXT NOT NULL, upgrade_policy_json TEXT NOT NULL,
  refund_policy_json TEXT NOT NULL, benefits_json TEXT NOT NULL, version_hash TEXT NOT NULL,
  created_by INTEGER NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (plan_id, version, level_code)
);
CREATE TABLE IF NOT EXISTS user_memberships (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, plan_id INTEGER NOT NULL,
  plan_version_id INTEGER NOT NULL, level_code TEXT NOT NULL, status TEXT NOT NULL,
  starts_at DATETIME NOT NULL, expires_at DATETIME NULL, source_type TEXT NOT NULL,
  source_ref TEXT NULL, grant_biz_key TEXT NOT NULL UNIQUE, version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, revoked_at DATETIME NULL
);
CREATE INDEX IF NOT EXISTS idx_user_membership_active
  ON user_memberships (user_id, status, starts_at, expires_at);
CREATE TABLE IF NOT EXISTS user_entitlements (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, entitlement_code TEXT NOT NULL,
  source_type TEXT NOT NULL, source_ref TEXT NULL, starts_at DATETIME NOT NULL,
  expires_at DATETIME NULL, status TEXT NOT NULL, grant_biz_key TEXT NOT NULL UNIQUE,
  granted_by INTEGER NULL, reason TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at DATETIME NULL
);
CREATE INDEX IF NOT EXISTS idx_user_entitlement_active
  ON user_entitlements (user_id, status, starts_at, expires_at, entitlement_code);

CREATE TABLE IF NOT EXISTS checkout_quotes (
  id TEXT PRIMARY KEY, quote_type TEXT NOT NULL DEFAULT 'CART', user_id INTEGER NOT NULL,
  cart_id INTEGER NULL, cart_version INTEGER NULL, selection_mode TEXT NOT NULL,
  input_hash TEXT NOT NULL, rules_hash TEXT NOT NULL, algorithm_version TEXT NOT NULL,
  currency_totals_json TEXT NOT NULL, result_json TEXT NOT NULL, explanation_json TEXT NOT NULL,
  status TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL, consumed_checkout_id INTEGER NULL
);
CREATE INDEX IF NOT EXISTS idx_quote_user_state ON checkout_quotes (user_id, status, expires_at);
CREATE TABLE IF NOT EXISTS checkout_orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_no TEXT NOT NULL UNIQUE, user_id INTEGER NOT NULL,
  quote_id TEXT NOT NULL, status TEXT NOT NULL, idempotency_key TEXT NOT NULL,
  input_hash TEXT NOT NULL, algorithm_version TEXT NOT NULL, rules_hash TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at DATETIME NULL,
  UNIQUE (user_id, idempotency_key)
);
CREATE TABLE IF NOT EXISTS checkout_order_groups (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_id INTEGER NOT NULL, group_no TEXT NOT NULL UNIQUE,
  business_type TEXT NOT NULL, currency_space TEXT NOT NULL, currency TEXT NOT NULL,
  seller_user_id INTEGER NULL, delivery_mode TEXT NULL, status TEXT NOT NULL,
  base_amount INTEGER NOT NULL, seller_discount_amount INTEGER NOT NULL,
  platform_discount_amount INTEGER NOT NULL, fee_amount INTEGER NOT NULL, tax_amount INTEGER NOT NULL,
  buyer_total INTEGER NOT NULL, seller_receive INTEGER NOT NULL, platform_funding INTEGER NOT NULL,
  refunded_amount INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_checkout_group ON checkout_order_groups (checkout_id, id);
CREATE TABLE IF NOT EXISTS checkout_order_lines (
  id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL, source_type TEXT NOT NULL,
  source_id INTEGER NOT NULL, source_version TEXT NULL, quantity INTEGER NOT NULL, currency TEXT NOT NULL,
  base_amount INTEGER NOT NULL, seller_discount_amount INTEGER NOT NULL,
  platform_discount_amount INTEGER NOT NULL, benefit_offset_amount INTEGER NOT NULL,
  fee_amount INTEGER NOT NULL, tax_amount INTEGER NOT NULL, final_amount INTEGER NOT NULL,
  seller_receive INTEGER NOT NULL, platform_funding INTEGER NOT NULL,
  refunded_quantity INTEGER NOT NULL DEFAULT 0, refunded_amount INTEGER NOT NULL DEFAULT 0,
  fulfillment_ref_type TEXT NULL, fulfillment_ref_id INTEGER NULL, status TEXT NOT NULL,
  snapshot_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_checkout_lines_group ON checkout_order_lines (group_id, id);
CREATE TABLE IF NOT EXISTS checkout_price_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_id INTEGER NOT NULL UNIQUE, quote_id TEXT NOT NULL,
  algorithm_version TEXT NOT NULL, input_hash TEXT NOT NULL, rules_hash TEXT NOT NULL,
  snapshot_json TEXT NOT NULL, snapshot_hash TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS benefit_grants (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_id INTEGER NULL, order_line_id INTEGER NULL,
  rule_version_id INTEGER NULL, benefit_type TEXT NOT NULL, benefit_ref TEXT NULL,
  quantity INTEGER NULL, amount INTEGER NULL, currency TEXT NULL, trigger_status TEXT NOT NULL,
  status TEXT NOT NULL, grant_biz_key TEXT NOT NULL UNIQUE, granted_at DATETIME NULL,
  reversed_at DATETIME NULL, detail_json TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS seller_promotion_policies (
  seller_user_id INTEGER PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 1,
  max_discount_bps INTEGER NOT NULL DEFAULT 5000, min_receivable_bps INTEGER NOT NULL DEFAULT 1000,
  max_active_campaigns INTEGER NOT NULL DEFAULT 20, max_coupon_issue INTEGER NOT NULL DEFAULT 10000,
  max_duration_days INTEGER NOT NULL DEFAULT 90, allowed_types_json TEXT NOT NULL DEFAULT '[]',
  version INTEGER NOT NULL DEFAULT 1, updated_by INTEGER NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS promotion_rule_scopes (
  id INTEGER PRIMARY KEY AUTOINCREMENT, rule_version_id INTEGER NOT NULL,
  scope_type TEXT NOT NULL, include_mode TEXT NOT NULL DEFAULT 'INCLUDE', scope_value TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (rule_version_id, scope_type, include_mode, scope_value)
);
CREATE TABLE IF NOT EXISTS promotion_rule_tiers (
  id INTEGER PRIMARY KEY AUTOINCREMENT, rule_version_id INTEGER NOT NULL,
  tier_order INTEGER NOT NULL, threshold_value INTEGER NOT NULL,
  discount_amount INTEGER NOT NULL DEFAULT 0, discount_bps INTEGER NOT NULL DEFAULT 0,
  max_discount_amount INTEGER NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (rule_version_id, tier_order)
);
CREATE TABLE IF NOT EXISTS coupon_codes (
  id INTEGER PRIMARY KEY AUTOINCREMENT, template_id INTEGER NOT NULL, code_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL, max_uses INTEGER NOT NULL DEFAULT 1, used_count INTEGER NOT NULL DEFAULT 0,
  valid_until DATETIME NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS membership_plan_benefits (
  id INTEGER PRIMARY KEY AUTOINCREMENT, plan_version_id INTEGER NOT NULL,
  benefit_code TEXT NOT NULL, benefit_type TEXT NOT NULL, benefit_config_json TEXT NOT NULL,
  stacking_slot TEXT NULL, priority INTEGER NOT NULL DEFAULT 0,
  UNIQUE (plan_version_id, benefit_code)
);
CREATE TABLE IF NOT EXISTS membership_product_bindings (
  product_id INTEGER PRIMARY KEY, plan_version_id INTEGER NOT NULL,
  grant_trigger_status TEXT NOT NULL DEFAULT 'PAID', active INTEGER NOT NULL DEFAULT 1,
  created_by INTEGER NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS membership_codes (
  id INTEGER PRIMARY KEY AUTOINCREMENT, code_hash TEXT NOT NULL UNIQUE,
  plan_version_id INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE',
  max_uses INTEGER NOT NULL DEFAULT 1, used_count INTEGER NOT NULL DEFAULT 0,
  valid_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, valid_until DATETIME NULL,
  created_by INTEGER NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS membership_code_redemptions (
  id INTEGER PRIMARY KEY AUTOINCREMENT, membership_code_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL, membership_id INTEGER NOT NULL, request_id TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, request_id), UNIQUE (membership_code_id, user_id)
);
CREATE TABLE IF NOT EXISTS membership_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, membership_id INTEGER NOT NULL, event_type TEXT NOT NULL,
  from_status TEXT NULL, to_status TEXT NOT NULL, biz_type TEXT NOT NULL, biz_id TEXT NOT NULL,
  actor_id INTEGER NULL, detail_json TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (membership_id, event_type, biz_type, biz_id)
);
CREATE TABLE IF NOT EXISTS promotion_subject_usage (
  id INTEGER PRIMARY KEY AUTOINCREMENT, rule_version_id INTEGER NOT NULL,
  subject_type TEXT NOT NULL, subject_id TEXT NOT NULL, period_key TEXT NOT NULL,
  used_count INTEGER NOT NULL DEFAULT 0, used_amount INTEGER NOT NULL DEFAULT 0,
  version INTEGER NOT NULL DEFAULT 1, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (rule_version_id, subject_type, subject_id, period_key)
);
CREATE TABLE IF NOT EXISTS checkout_discounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_id INTEGER NOT NULL,
  rule_version_id INTEGER NULL, rule_id TEXT NOT NULL, campaign_id INTEGER NULL,
  user_coupon_id INTEGER NULL, layer INTEGER NOT NULL, stacking_slot TEXT NOT NULL,
  discount_amount INTEGER NOT NULL, funding_mode TEXT NOT NULL,
  platform_funding INTEGER NOT NULL DEFAULT 0, seller_funding INTEGER NOT NULL DEFAULT 0,
  application_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_checkout_discounts_checkout ON checkout_discounts (checkout_id, id);
CREATE TABLE IF NOT EXISTS checkout_discount_allocations (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_discount_id INTEGER NOT NULL,
  checkout_line_id INTEGER NOT NULL, allocated_amount INTEGER NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (checkout_discount_id, checkout_line_id)
);
CREATE TABLE IF NOT EXISTS checkout_funding_shares (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_line_id INTEGER NOT NULL,
  checkout_discount_id INTEGER NULL, funder_type TEXT NOT NULL, funder_id INTEGER NULL,
  amount INTEGER NOT NULL, currency TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS checkout_line_payment_units (
  id INTEGER PRIMARY KEY AUTOINCREMENT, checkout_line_id INTEGER NOT NULL,
  unit_index INTEGER NOT NULL, base_amount INTEGER NOT NULL, discount_amount INTEGER NOT NULL,
  final_amount INTEGER NOT NULL, refunded INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (checkout_line_id, unit_index)
);
CREATE TABLE IF NOT EXISTS commerce_refund_adjustments (
  id INTEGER PRIMARY KEY AUTOINCREMENT, refund_request_id INTEGER NULL,
  checkout_line_id INTEGER NOT NULL, adjustment_type TEXT NOT NULL,
  funder_type TEXT NULL, amount INTEGER NOT NULL, currency TEXT NOT NULL,
  detail_json TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS promotion_collections (
  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  status TEXT NOT NULL, display_json TEXT NOT NULL, created_by INTEGER NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS promotion_collection_items (
  collection_id INTEGER NOT NULL, source_type TEXT NOT NULL, source_id INTEGER NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (collection_id, source_type, source_id)
);
