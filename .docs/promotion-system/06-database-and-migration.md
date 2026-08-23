# 数据库与迁移契约

## 1. 适用原则

- SQLite 与 MySQL/MariaDB 的业务字段、约束语义和索引必须一致。
- SQLite schema 更新 `src/main/resources/db/sqlite/schema.sql` 和 `SqliteSchemaProvider` 验证/迁移。
- MySQL/MariaDB 更新 `MysqlSchemaProvider` 与 `SchemaManager` 兼容迁移。
- 核心业务不依赖数据库专属 JSON 查询；JSON 用于不可变配置/快照，常用过滤字段结构化。
- 所有金额 `BIGINT/INTEGER` 非负；比例 `INT` 基点；数量 `INT` 正数。
- 时间统一 UTC；枚举保存稳定大写代码。
- 每张业务表具有必要外键、唯一键和查询索引；SQLite 外键必须实际开启。

下述是逻辑契约。执行者可以合理拆表/合表或调整命名，但必须在本文记录映射，并保留全部语义、约束和查询能力。

## 2. 购物车

### 2.1 `commerce_carts`

| 列 | 约束/说明 |
|---|---|
| `id` | PK |
| `user_id` | FK `web_users`, UNIQUE |
| `version` | BIGINT NOT NULL DEFAULT 1 |
| `created_at`, `updated_at` | NOT NULL |

### 2.2 `commerce_cart_lines`

| 列 | 约束/说明 |
|---|---|
| `id` | PK |
| `cart_id` | FK cascade |
| `source_type` | `OFFICIAL_PRODUCT` / `MARKET_LISTING` |
| `source_id` | product/listing ID |
| `quantity` | > 0 |
| `delivery_mode` | nullable stable code |
| `selected` | boolean default true |
| `source_version` | 客户端最后观察版本/哈希，可空 |
| `metadata_json` | 仅保存稳定选择，不保存可信价格 |
| `created_at`, `updated_at` | NOT NULL |

唯一键：`(cart_id, source_type, source_id, delivery_mode)`；索引 `(cart_id, selected, id)`。

## 3. 商品集合

### 3.1 `promotion_collections`

`id`, `code UNIQUE`, `name`, `description`, `owner_type`, `owner_id`, `status`, `version`, `created_by`, `created_at`, `updated_at`。

`owner_type = PLATFORM | SELLER`。卖家集合只能含自己的挂单。

### 3.2 `promotion_collection_members`

`collection_id`, `member_type`, `member_id`, `include_mode`, `position`, `created_at`。

联合主键 `(collection_id, member_type, member_id, include_mode)`；`include_mode = INCLUDE | EXCLUDE`。发布规则时将集合版本/展开哈希冻结到 rule version。

## 4. 活动与不可变规则

### 4.1 `promotion_campaigns`

| 列 | 说明 |
|---|---|
| `id`, `code UNIQUE` | 身份 |
| `owner_type`, `owner_id` | `PLATFORM` 或 `SELLER/user_id` |
| `name`, `description` | 内部/展示信息 |
| `status` | `DRAFT/SCHEDULED/ACTIVE/PAUSED/ENDED/TERMINATED` |
| `start_at`, `end_at` | `[start,end)`，可空按类型校验 |
| `current_version_id` | 当前版本，可空避免创建循环；版本创建后更新 |
| `created_by`, `updated_by` | 平台管理员或卖家主体由 actor 字段解释 |
| `created_at`, `updated_at` | 时间 |

索引 `(owner_type, owner_id, status, start_at, end_at)` 和 `(status, start_at, end_at)`。

### 4.2 `promotion_rule_versions`

| 列 | 说明 |
|---|---|
| `id`, `campaign_id`, `version` | `(campaign_id, version)` UNIQUE |
| `rule_kind` | 即时优惠/券/后置奖励等 |
| `direction` | `USER_PAYS/USER_RECEIVES/EXTERNAL_PAY/BID_ESCROW` |
| `layer`, `stacking_slot`, `stacking_policy` | 计算关系 |
| `exclusive_group`, `priority`, `max_per_order` | 冲突/排序 |
| `threshold_type`, `threshold_value` | 门槛结构化字段 |
| `threshold_basis_layer`, `discount_basis_layer` | 价格层 |
| `repeat_mode`, `max_repeat_count` | 重复方式 |
| `discount_amount`, `discount_bps`, `max_discount_amount` | 三者按 rule kind 校验 |
| `funding_mode`, `platform_share_bps` | `PLATFORM/SELLER/SHARED` |
| `min_payable_override`, `allow_zero_payable` | 价格保护 |
| `rule_json`, `scope_json`, `eligibility_json`, `stacking_json` | 完整不可变配置 |
| `refund_policy_json`, `display_json` | 售后/展示 |
| `rules_hash` | SHA-256 UNIQUE 或索引 |
| `created_by`, `created_at` | 审计 |

发布后禁止 UPDATE，状态在 campaign；修订 INSERT 新版本。

### 4.3 `promotion_rule_scopes`

用于高效查候选：`rule_version_id`, `scope_type`, `scope_ref`, `include_mode`。联合主键；索引 `(scope_type, scope_ref, include_mode, rule_version_id)`。

`scope_ref` 对全局可使用固定 `*`，对 ID 使用规范字符串。JSON 仍是完整真相，绑定表是检索投影，二者创建时事务内一致。

### 4.4 `promotion_rule_tiers`

`id`, `rule_version_id`, `position`, `threshold_value`, `discount_amount`, `discount_bps`, `max_discount_amount`。

唯一 `(rule_version_id, position)`；阶梯严格按 threshold 升序验证。

## 5. 平台预算、库存和次数

### 5.1 `promotion_accounts`

| 列 | 说明 |
|---|---|
| `id` | PK |
| `account_type` | `BUDGET/ISSUE_STOCK/USE_COUNT/DAILY_BUDGET/ESCROW_BONUS` |
| `owner_type`, `owner_id` | rule/template/campaign/seller |
| `currency_space` | `WALLET/FIAT/COUNT` |
| `currency` | 可空（COUNT） |
| `limit_amount`, `limit_count` | NULL 表示无限 |
| `reserved_amount/count` | >= 0 |
| `consumed_amount/count` | >= 0 |
| `released_amount/count`, `refunded_amount/count` | >= 0 |
| `period_key` | 总周期固定 `ALL`，每日为日期 |
| `version`, `updated_at` | 并发 |

唯一 `(account_type, owner_type, owner_id, currency_space, currency, period_key)`。

### 5.2 `promotion_account_ledger`

`id`, `account_id`, 各 reserved/consumed/released/refunded delta，`biz_type`, `biz_id`, `detail_json`, `created_at`。

唯一 `(account_id, biz_type, biz_id)`；所有 delta 可正负但事务后账户不可非法。账本不可更新/删除。

### 5.3 `promotion_subject_usage`

`rule_version_id`, `subject_type`, `subject_key`, `period_key`, `scope_ref`, `reserved_count`, `consumed_count`, `discount_amount`, `version`, `updated_at`。

联合主键覆盖前五列，用于用户、UUID、卖家或身份组限次。

## 6. 优惠券

### 6.1 `coupon_templates`

`id`, `code UNIQUE`, `campaign_id`, `rule_version_id`, `owner_type`, `owner_id`, `name`, `claim_mode`, `status`, `issue_limit`, `per_subject_claim_limit`, `per_subject_use_limit`, `validity_mode`, `valid_from`, `valid_until`, `valid_duration_seconds`, `created_by`, `created_at`, `updated_at`。

模板引用不可变规则版本；修改优惠规则应创建新模板版本或新模板，不能让已领取券含义漂移。

### 6.2 `coupon_codes`

`id`, `template_id`, `code_hash`, `code_suffix`, `max_bindings`, `bound_count`, `status`, `valid_from`, `expires_at`, `created_by`, `created_at`。

`code_hash UNIQUE`。使用 HMAC-SHA-256（服务端密钥）或带 pepper 的安全哈希；大小写/连字符规范化必须固定。

### 6.3 `user_coupons`

| 列 | 说明 |
|---|---|
| `id`, `serial_no UNIQUE` | 实例 |
| `template_id`, `rule_version_id`, `user_id` | FK |
| `status` | 生命周期状态 |
| `valid_from`, `valid_until` | 实例有效期 |
| `source_type`, `source_ref` | 领取/会员/批量/奖励来源 |
| `reserved_quote_id`, `reserved_until` | 可空 |
| `consumed_checkout_id`, `consumed_at` | 可空 |
| `version`, `created_at`, `updated_at` | 并发/时间 |

索引 `(user_id, status, valid_until, id)`、`(template_id, user_id)`、`consumed_checkout_id`。

### 6.4 `coupon_events`

`id`, `user_coupon_id`, `event_type`, `from_status`, `to_status`, `biz_type`, `biz_id`, `actor_type`, `actor_id`, `detail_json`, `created_at`。

唯一 `(user_coupon_id, event_type, biz_type, biz_id)`；只追加。

## 7. 会员与资格

### 7.1 `membership_plans`

`id`, `code UNIQUE`, `name`, `description`, `status`, `current_version_id`, `created_by`, `created_at`, `updated_at`。

### 7.2 `membership_plan_versions`

`id`, `plan_id`, `version`, `level_code`, `level_rank`, `duration_mode`, `duration_value`, `renewal_mode`, `upgrade_policy_json`, `refund_policy_json`, `benefits_json`, `version_hash`, `created_by`, `created_at`。

唯一 `(plan_id, version, level_code)`。已发布不可变。

### 7.3 `membership_benefit_bindings`

`plan_version_id`, `benefit_type`, `benefit_ref`, `grant_period`, `quantity`, `config_json`。

`benefit_ref` 指 rule version、coupon template 或 entitlement code；联合唯一。

### 7.4 `user_memberships`

| 列 | 说明 |
|---|---|
| `id` | PK |
| `user_id`, `plan_id`, `plan_version_id` | FK |
| `level_code`, `status` | `PENDING/ACTIVE/EXPIRED/REVOKED/CANCELLED` |
| `starts_at`, `expires_at` | 永久可空 expires |
| `source_type`, `source_ref` | PURCHASE/CODE/ADMIN 等 |
| `grant_biz_key` | UNIQUE，授予幂等 |
| `version`, `created_at`, `updated_at`, `revoked_at` | 时间 |

索引 `(user_id, status, starts_at, expires_at)`、`(plan_id, user_id)`。

### 7.5 `membership_events`

与 coupon events 类似，记录 `GRANTED/ACTIVATED/RENEWED/UPGRADED/EXPIRED/REVOKED/RESTORED`，唯一业务键，只追加。

### 7.6 `user_entitlements`

`id`, `user_id`, `entitlement_code`, `source_type`, `source_ref`, `starts_at`, `expires_at`, `status`, `grant_biz_key UNIQUE`, `granted_by`, `reason`, `created_at`, `revoked_at`。

用于 `MEMBER_STACKING`、活动白名单等。会员派生 entitlement 可以查询计算或物化，但必须可追溯到 membership。

### 7.7 `eligibility_counters`

`subject_type`, `subject_key`, `counter_scope`, `scope_ref`, `reserved_count`, `successful_count`, `version`, `updated_at`。联合主键，用于首单/首购。

## 8. 报价

### 8.1 `checkout_quotes`

`id` 使用高熵字符串主键；列：`user_id`, `cart_id`, `cart_version`, `selection_mode`, `input_hash`, `rules_hash`, `algorithm_version`, `currency_totals_json`, `result_json`, `explanation_json`, `status`, `created_at`, `expires_at`, `consumed_checkout_id`。

索引 `(user_id, status, expires_at)`；过期报价可定期删除，但被订单引用的报价摘要必须由订单快照完整保留。

充值/回收独立报价可共用表并增加 `quote_type`、`source_ref`，或拆专表；API 语义保持一致。

## 9. 父交易、组、行与快照

### 9.1 `checkout_orders`

| 列 | 说明 |
|---|---|
| `id`, `checkout_no UNIQUE` | 父交易 |
| `user_id`, `quote_id` | 归属 |
| `status` | `PROCESSING/PLACED/PARTIALLY_FULFILLED/FULFILLED/PARTIALLY_REFUNDED/REFUNDED/FAILED` |
| `idempotency_key`, `input_hash` | `(user_id,idempotency_key)` UNIQUE |
| `algorithm_version`, `rules_hash` | 冻结 |
| `created_at`, `updated_at`, `completed_at` | 时间 |

金额按币种在 group/summary 表，不在父表放一个含混 total。

### 9.2 `checkout_order_groups`

`id`, `checkout_id`, `group_no UNIQUE`, `business_type`, `currency_space`, `currency`, `seller_user_id`, `delivery_mode`, `status`, `base_amount`, `seller_discount_amount`, `platform_discount_amount`, `fee_amount`, `tax_amount`, `buyer_total`, `seller_receive`, `platform_funding`, `refunded_amount`, `created_at`, `updated_at`。

索引 `(checkout_id, id)`、`(seller_user_id, created_at)`。

### 9.3 `checkout_order_lines`

`id`, `group_id`, `source_type`, `source_id`, `source_version`, `quantity`, `currency`, `base_amount`, `seller_discount_amount`, `platform_discount_amount`, `benefit_offset_amount`, `fee_amount`, `tax_amount`, `final_amount`, `seller_receive`, `platform_funding`, `refunded_quantity`, `refunded_amount`, `fulfillment_ref_type`, `fulfillment_ref_id`, `status`, `snapshot_json`, `created_at`。

索引 `(group_id,id)`、`(source_type,source_id,created_at)`、履约引用。

### 9.4 `checkout_price_snapshots`

`id`, `checkout_id UNIQUE`, `quote_id`, `algorithm_version`, `input_hash`, `rules_hash`, `snapshot_json`, `snapshot_hash`, `created_at`。不可更新。

### 9.5 `checkout_discounts`

`id`, `checkout_id`, `rule_version_id`, `user_coupon_id`, `source_type`, `source_ref`, `layer`, `stacking_slot`, `funding_mode`, `discount_amount`, `threshold_basis_amount`, `calculation_basis_amount`, `display_name`, `detail_json`, `created_at`。

### 9.6 `checkout_discount_allocations`

`discount_id`, `order_line_id`, `allocated_amount`, `refunded_amount`，联合主键。两金额非负且 refunded <= allocated。

### 9.7 `checkout_funding_shares`

`id`, `discount_id`, `order_line_id`, `funder_type`, `funder_id`, `currency_space`, `currency`, `amount`, `refunded_amount`, `account_id`, `created_at`。

索引 funder 和 checkout（可通过 discount join）；`funder_type = PLATFORM | SELLER`。

### 9.8 `checkout_line_payment_units`

压缩区间：`id`, `order_line_id`, `unit_from`, `unit_to`, `base_unit_amount`, `discount_unit_amount`, `final_unit_amount`, `seller_receive_unit`, `platform_funding_unit`。

约束区间不重叠、覆盖 1..quantity。若实现者采用基础值+余数方案，可替代此表，但退款必须 O(区间数) 可重放。

## 10. 后置权益

### 10.1 `benefit_grants`

`id`, `checkout_id`, `order_line_id`, `rule_version_id`, `benefit_type`, `benefit_ref`, `quantity/amount`, `currency`, `trigger_status`, `status`, `grant_biz_key UNIQUE`, `granted_at`, `reversed_at`, `detail_json`, `created_at`。

状态：`PENDING/GRANTED/REVERSED/REVIEW_REQUIRED/CANCELLED`。

## 11. 退款协调

### 11.1 `commerce_refund_adjustments`

`id`, `refund_request_id`, `checkout_id`, `order_line_id`, `adjustment_type`, `source_type`, `source_id`, `amount`, `quantity`, `status`, `idempotency_key UNIQUE`, `detail_json`, `created_at`, `completed_at`。

类型包括钱包退款、卖家应收冲回、平台预算冲回、券返还、会员撤销、奖励撤销。

现有 `refund_requests` 可以增加 `checkout_id/order_line_id`，或新表关联现有请求；不能失去旧订单兼容。

## 12. 卖家限制

### 12.1 `seller_promotion_policies`

`seller_user_id PK`, `enabled`, `max_discount_bps`, `min_receivable_bps`, `max_active_campaigns`, `max_coupon_issue`, `max_duration_days`, `allowed_types_json`, `updated_by`, `updated_at`。

没有个体覆盖时读取平台默认运行时配置。管理员限制市场时可同步 `enabled=false` 或在资格检查中读取现有市场状态。

## 13. 现有表扩展

### 13.1 `products`

- 新增 `pricing_version BIGINT NOT NULL DEFAULT 1`（任何影响 P0/适用范围的修改递增）；
- 新增正式 `MEMBERSHIP` 产品类型所需 metadata/reference，优先结构化 `membership_plan_version_id`，不要藏在命令模板。

### 13.2 `market_listings`

- `pricing_version`；
- 可选 `seller_store_status` 不必重复，如能从 seller policy 推导；
- 收购方奖励最大托管字段（若支持 requester-funded bonus）。

### 13.3 `orders`

- `checkout_id`, `checkout_group_id`, `checkout_line_id` 可空且索引；
- `base_amount`, `promotion_discount_amount`, `coupon_discount_amount`, `benefit_offset_amount`；
- `total_amount` 继续表示最终实际钱包扣款；
- `pricing_algorithm_version`。

### 13.4 `order_items`

- `base_unit_price`, `base_amount`, `discount_amount`, `final_amount`；
- 旧 `unit_price` 明确定义并迁移；新代码不能用折后平均价代替精确分摊。

### 13.5 `market_trades`

- checkout 三个关联列；
- `listing_gross`, `seller_discount`, `platform_discount`, `platform_funding`；
- 保留 `buyer_total/seller_receive/fee_amount/tax_amount`，由统一结果写入。

### 13.6 `webshopx_recharge_order`

- `quote_id`, `pricing_snapshot_json/hash`；
- `base_amount_minor`, `discount_amount_minor`, `amount_minor`（实际支付）；
- `base_coin_amount`, `bonus_coin_amount`, `coin_amount`（实际到账）；
- 优惠预留释放状态。

### 13.7 `web_admins` 与审计

无需 schema 变化即可通过 `permissions_json` 存新枚举。审计 target type 增加 promotion、coupon、membership、seller_promotion、promotion_account。

## 14. 数据约束

应用层和数据库共同保证：

- `end_at > start_at`；
- 百分比 0..10000（除加成规则可按显式上限）；
- `platform_share_bps` 0..10000；
- 计数和金额非负；
- 规则 owner 与 funding 合法；卖家规则不能 PLATFORM/其他卖家承担；
- 用户券 owner/template/rule version 一致；
- group/line/discount/allocation 汇总守恒；
- 退款累计不超过冻结金额；
- 一个 quote 最多消费成一个 checkout；
- 一个 checkout line 最多关联一个主要履约投影。

SQLite 旧版本若 CHECK 约束迁移困难，必须在写路径与 schema 测试中等价保证。

## 15. 迁移顺序

这是技术检查点，不是裁剪产品范围：

1. 新增所有新表和可空关联列；不改变旧下单。
2. 历史订单回填：基础金额=原实际金额，优惠为 0，不伪造快照。
3. 新代码对无优惠单写完整 checkout/快照，验证双写守恒。
4. 旧单商品入口内部切到统一 checkout；保持 API 兼容。
5. 开启购物车和多商品结算。
6. 开启平台/卖家促销、会员和全域适配。
7. 所有历史兼容观察期通过后，才考虑关键列非空化或删除临时代码。

## 16. 迁移测试

- 新 SQLite 从空库建表；
- 官方历史 SQLite 快照升级；
- MySQL/MariaDB 空库和历史 schema 升级；
- 迁移重复运行无副作用；
- 旧订单/市场交易/充值订单查询与退款；
- 新订单在旧字段视图中仍可展示；
- 索引和外键存在性；
- 大表回填可中断/恢复；
- 混合应用版本防护。

优惠结算启用后，旧应用不能安全处理新价格快照退款。启动时使用 schema capability/application minimum version 阻止危险降级；不要依赖运维人员记住。

## 17. 数据保留

- 活动版本、订单快照、资金账本、优惠分摊、会员/券事件和审计不可因活动删除而删除。
- 购物车失效行可按用户操作删除；空购物车保留或清理均可。
- 未消费过期报价可按配置清理；订单引用的信息必须已复制到快照。
- 券码明文从不落库；哈希按安全保留策略处理。
- 报表汇总可重建，不是财务真相；账本和快照是事实来源。
