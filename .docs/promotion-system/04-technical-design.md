# WebShopX 优惠系统技术设计

## 1. 当前系统基线

经仓库检查，优惠实施必须建立在以下事实之上：

- 官方商城下单由 `OrderService` 在数据库事务中完成；
- `ProductService.quoteOrderPrice(...)` 先计算固定或动态价格；
- 钱包通过带业务键的流水执行幂等增减；
- `orders.total_amount` 当前表示实际扣款总额；
- `order_items` 当前保存商品、数量和一个平均单位价；
- 官方订单当前只有一个商品行，但表结构允许后续扩展；
- 部分退款目前按订单实付总额与未交付数量比例计算；
- SQLite 和 MySQL 均为受支持数据库；
- `RedeemCodeService` 发放钱包货币，语义不是订单优惠券；
- 管理员具有角色、细粒度权限和审计日志基础。

因此，动态报价是优惠引擎的 P0 输入；优惠系统不能替代动态定价。现有退款比例算法在加入多层优惠后必须迁移到冻结分摊结果。

## 2. 组件边界

### 2.1 纯计算层

`PricingEngine`

- 输入不可变 `PricingContext`；
- 输出 `PricingResult`、候选原因和计算轨迹；
- 不访问数据库、不读系统当前时间、不生成随机数；
- 算法版本作为输出的一部分；
- 使用整数金额和显式取整模式。

`PromotionCombinationOptimizer`

- 根据候选、冲突图、槽位和护栏选择最优组合；
- 支持用户指定组合校验；
- 采用稳定排序和确定性裁决。

`DiscountAllocator`

- 按最大余数法进行商品行分摊；
- 验证总额守恒、行金额非负和最低实付。

### 2.2 应用服务层

| 服务 | 职责 |
|---|---|
| `PricingQuoteService` | 读取商品、用户、活动和券，调用纯计算层，保存短期报价 |
| `PromotionCatalogService` | 活动、规则版本、商品范围和状态查询 |
| `EligibilityService` | 用户标签、会员、首购、服务器和风控资格 |
| `CouponService` | 领取、发放、券码绑定、状态流转和事件日志 |
| `PromotionUsageService` | 次数、预算、库存的事务核销与冲回 |
| `OrderPromotionService` | 在下单事务内复核报价并冻结订单价格快照 |
| `PromotionRefundService` | 按订单分摊计算退款并执行返券/资格恢复策略 |
| `PromotionAdminService` | 草稿、审批、发布、模拟、暂停和报表 |

服务之间依赖接口，不让 HTTP 处理器直接拼接优惠 SQL 或价格公式。

### 2.3 集成位置

下单主流程调整为：

```text
读取幂等订单
  -> 锁定用户/商品
  -> 取得或复核报价
  -> 锁定券、活动预算、次数计数器
  -> 校验库存与购买限制
  -> 核销优惠与资格
  -> 按最终应付扣钱包
  -> 写订单、商品行、优惠分摊和价格快照
  -> 更新动态价格事件
  -> 创建交付任务
  -> 提交事务
```

所有写操作处于现有订单事务内。任何一步失败均回滚钱包、券、预算、库存、限购和订单。

## 3. 数据模型

以下为逻辑表。SQLite 使用 `INTEGER/TEXT/DATETIME`，MySQL 使用对应兼容类型；规则和快照 JSON 使用 `TEXT`，不得依赖某一数据库专属 JSON 函数完成核心业务。

### 3.1 商品集合

#### `product_collections`

- `id`, `code`, `name`, `active`
- `created_at`, `updated_at`

#### `product_collection_items`

- `collection_id`, `product_id`
- 联合主键；外键删除策略为 `RESTRICT`

商品集合是首期的品类替代物。活动发布时记录集合版本或展开后的商品范围快照，防止后续改集合改变历史订单解释。

### 3.2 活动与版本

#### `promotion_campaigns`

- `id`, `code`, `name`, `description`
- `status`, `start_at`, `end_at`, `priority`
- `current_version_id`
- `created_by`, `approved_by`, `created_at`, `updated_at`

#### `promotion_rule_versions`

- `id`, `campaign_id`, `version`
- `promotion_type`, `layer`, `stacking_slot`, `stacking_policy`
- `exclusive_group`, `priority`
- `rule_json`, `scope_json`, `eligibility_json`, `stacking_json`
- `budget_policy_json`, `refund_policy_json`, `display_json`
- `rules_hash`, `created_by`, `created_at`

已发布版本不可更新。`rules_hash` 使用规范化 JSON 的 SHA-256，订单快照保存该值。

### 3.3 用户资格

#### `user_entitlements`

- `id`, `user_id`, `entitlement_code`
- `source_type`, `source_ref`
- `starts_at`, `expires_at`, `status`
- `granted_by`, `reason`, `created_at`, `revoked_at`

唯一性按业务来源定义，避免同一会员周期重复授予。系统派生的首购资格不必全部物化；可以查询计数器，但手动和会员资格必须落库。

#### `eligibility_counters`

- `subject_type`, `subject_key`, `counter_scope`, `scope_ref`
- `successful_count`, `reserved_count`, `version`, `updated_at`

用于首单、集合首购和周期次数的并发控制。主键覆盖主体与作用域。

### 3.4 券模板、券码与用户券

#### `coupon_templates`

- `id`, `code`, `name`, `campaign_id`, `rule_version_id`
- `claim_mode`, `status`
- `issue_limit`, `per_subject_claim_limit`, `per_subject_use_limit`
- `validity_mode`, `valid_from`, `valid_until`, `valid_duration_seconds`
- `created_by`, `created_at`, `updated_at`

#### `coupon_codes`

- `id`, `template_id`, `code_hash`, `code_suffix`
- `max_bindings`, `bound_count`, `status`, `expires_at`, `created_at`

券码只存加盐哈希和末尾展示字符；后台创建时只返回一次明文。公共活动码允许 `max_bindings > 1`，但每次绑定仍产生独立用户券。

#### `user_coupons`

- `id`, `template_id`, `rule_version_id`, `user_id`
- `serial_no`, `status`
- `valid_from`, `valid_until`
- `source_type`, `source_ref`
- `reserved_quote_id`, `reserved_until`
- `consumed_order_id`, `consumed_at`
- `version`, `created_at`, `updated_at`

索引至少覆盖 `(user_id, status, valid_until)`、`serial_no`、`consumed_order_id`。`version` 用于乐观并发；消费时仍使用行锁或条件更新。

#### `coupon_events`

- `id`, `user_coupon_id`, `event_type`
- `from_status`, `to_status`
- `biz_type`, `biz_id`, `detail_json`, `created_at`

事件只追加，不更新。

### 3.5 预算、库存和使用量

#### `promotion_accounts`

- `id`, `account_type`, `owner_type`, `owner_id`
- `limit_amount`, `reserved_amount`, `consumed_amount`, `released_amount`
- `limit_count`, `reserved_count`, `consumed_count`
- `version`, `updated_at`

`account_type` 区分活动预算、发行库存、核销次数和每日预算。无限值使用 `NULL`，不使用任意大整数。

#### `promotion_account_ledger`

- `id`, `account_id`, `delta_reserved_amount`, `delta_consumed_amount`
- `delta_reserved_count`, `delta_consumed_count`
- `biz_type`, `biz_id`, `created_at`

`(account_id, biz_type, biz_id)` 唯一，保证冲回与重试幂等。

#### `promotion_subject_usage`

- `rule_version_id`, `subject_type`, `subject_key`
- `period_key`, `scope_ref`
- `reserved_count`, `consumed_count`, `discount_amount`, `updated_at`

联合主键覆盖规则、主体、周期和范围。

### 3.6 报价

#### `pricing_quotes`

- `id`（随机不可猜测标识）
- `user_id`, `currency`, `selection_mode`
- `input_hash`, `rules_hash`, `algorithm_version`
- `result_json`, `explanation_json`
- `created_at`, `expires_at`, `status`

报价默认不预留共享预算和普通活动库存，以免恶意占用。用户选择的券可在短时间内选择性预留；首期钱包即时下单可以不启用长时预留。

报价有效期建议 30–120 秒；动态定价商品取更短值。提交时必须复核商品版本、规则版本、券状态和预算，不能只信任报价 JSON。

### 3.7 订单价格

扩展 `orders`：

- `base_amount`：P0 商品总额；
- `promotion_discount_amount`：P1–P3 减免；
- `coupon_discount_amount`：P4 减免；
- `benefit_offset_amount`：P5 抵扣；
- `total_amount`：保留为最终实际扣款；
- `pricing_algorithm_version`。

扩展 `order_items`：

- `base_unit_price`：动态/固定报价后的单位价；
- `base_amount`；
- `discount_amount`；
- `final_amount`；
- 现有 `unit_price` 在迁移后明确定义为基础单位价，禁止表示折后平均价。

#### `order_pricing_snapshots`

- `id`, `order_id`, `quote_id`
- `algorithm_version`, `rules_hash`, `snapshot_json`, `snapshot_hash`
- `created_at`

`order_id` 唯一，一单一个不可变快照。订单先插入，再在同一事务插入快照，避免订单与快照形成循环外键。

#### `order_discounts`

- `id`, `order_id`, `source_type`, `source_id`
- `rule_version_id`, `layer`, `stacking_slot`
- `discount_type`, `discount_amount`
- `threshold_basis_amount`, `calculation_basis_amount`
- `display_name`, `detail_json`, `created_at`

#### `order_discount_allocations`

- `order_discount_id`, `order_item_id`
- `allocated_amount`, `refunded_amount`
- 联合主键

#### `order_item_payment_units`

为精确支持多数量部分交付/退款，建议保存压缩后的单位实付分布：

- `order_item_id`, `unit_index_from`, `unit_index_to`
- `base_unit_amount`, `discount_unit_amount`, `final_unit_amount`

同金额连续单位可合并成区间。当前单行同价场景通常只有一到两个区间，用于承载取整余数。

### 3.8 退款与返券

#### `promotion_refund_events`

- `id`, `order_id`, `refund_request_id`
- `event_type`, `source_type`, `source_id`
- `amount`, `quantity`, `action_status`
- `idempotency_key`, `detail_json`, `created_at`

唯一约束覆盖业务幂等键。退款金额仍进入现有钱包流水，返券和预算冲回进入各自事件/账本。

## 4. 报价输入输出

### 4.1 `PricingContext`

- 明确的 `now` 和业务时区；
- 用户、UUID、服务器、渠道和资格快照；
- 商品行：商品 ID、SKU、类型、数量、币种、P0 报价和版本；
- 活动规则版本；
- 用户券实例；
- 预算/库存可用快照；
- 用户选择和全局价格护栏。

### 4.2 `PricingResult`

- 各金额汇总；
- 商品行每层金额；
- 选中优惠、分摊和消费资源；
- 可用未选优惠及预估效果；
- 不可用优惠和原因；
- 规则哈希、输入哈希、算法版本和过期时间。

JSON 只作为 API/快照载体。核心金额字段仍需结构化列，便于约束、报表和退款。

## 5. API 设计

沿用 `/api` 路径风格，错误使用稳定业务码。

### 5.1 用户端

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/promotions/product?productId=` | 商品优惠和预估价 |
| `GET` | `/api/coupons/mine` | 我的券列表 |
| `POST` | `/api/coupons/claim` | 领取模板券 |
| `POST` | `/api/coupons/bind-code` | 券码绑定账户 |
| `POST` | `/api/pricing/quote` | 生成最优或指定组合报价 |
| `POST` | `/api/orders` | 扩展现有下单接口，增加 `quoteId` 和所选券 ID |
| `GET` | `/api/orders/{orderNo}/pricing` | 订单价格明细 |

报价请求只提交商品、数量和用户选择，不接受客户端提交最终优惠金额。

### 5.2 管理端

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET/POST` | `/api/admin/promotions` | 列表/创建草稿 |
| `GET/PUT` | `/api/admin/promotions/{id}` | 查看/编辑草稿 |
| `POST` | `/api/admin/promotions/{id}/submit` | 提交审批 |
| `POST` | `/api/admin/promotions/{id}/approve` | 审批 |
| `POST` | `/api/admin/promotions/{id}/publish` | 发布 |
| `POST` | `/api/admin/promotions/{id}/pause` | 暂停 |
| `POST` | `/api/admin/promotions/simulate` | 无副作用模拟 |
| `POST` | `/api/admin/coupons/grant` | 定向发券 |
| `POST` | `/api/admin/coupons/batch-grant/preview` | 批量预览去重 |
| `POST` | `/api/admin/coupons/batch-grant/commit` | 确认批量发放 |
| `GET` | `/api/admin/promotions/report` | 运营与预算报表 |

创建、更新和发布请求均携带幂等键或版本号。并发编辑使用 `If-Match`/版本字段，冲突返回 `409`。

## 6. 下单事务与锁顺序

为降低 MySQL 死锁并兼容 SQLite，所有路径采用固定顺序：

1. 幂等订单/退款请求；
2. 用户及资格计数器；
3. 商品 ID 升序；
4. 用户券 ID 升序；
5. 活动/预算账户 ID 升序；
6. 钱包；
7. 订单及交付记录。

SQLite 缺少细粒度 `FOR UPDATE` 时依赖现有事务和重试机制；条件更新必须检查影响行数。关键核销建议使用：

```text
UPDATE ...
SET consumed = consumed + ?
WHERE id = ?
  AND consumed + reserved + ? <= configured_limit
```

影响行数为 0 即资源不足或并发冲突，重新报价或返回稳定错误，不允许超发。

## 7. 幂等设计

- 下单沿用 `(user_id, idempotency_key)` 唯一约束；同键但输入哈希不同返回冲突。
- 领券使用 `(user_id, template_id, claim_request_id)` 唯一业务键。
- 用户券消费以 `consumed_order_id` 唯一绑定。
- 预算账本以账户与业务键唯一。
- 返券以用户券与退款业务键唯一。
- 报价重复提交在首次订单成功后返回原订单及原价格快照。

绝不能通过“查询后插入”单独保证次数限制；必须配合唯一约束、行锁或条件更新。

## 8. 退款集成

现有按 `total_amount × refund_quantity / total_quantity` 的算法只适合单行均价。迁移后：

1. 根据未交付/可退款单位读取 `order_item_payment_units`；
2. 累加这些单位冻结的 `final_unit_amount`；
3. 更新各优惠分摊的 `refunded_amount`；
4. 按模板策略决定是否返券、恢复预算或恢复首购资格；
5. 最后一次全退吸收历史取整余数；
6. 钱包退款、库存恢复、动态价格事件、返券和预算账本处于同一事务。

若历史订单没有价格快照，则继续走旧算法；不得为历史订单伪造优惠明细。

## 9. 缓存与集群

- 活动目录可缓存，但键必须包含规则版本；
- 用户券和预算不得只依赖本地缓存判断可用性；
- 发布、暂停、预算耗尽和资格变更通过现有集群事件总线广播失效事件；
- 缓存失效失败只影响性能，数据库复核必须保证正确性；
- 时间判断使用项目统一业务时区和数据库事务时点，避免节点时钟差异。

## 10. 安全与风控

- 券码只存哈希，接口限速并避免通过错误信息枚举有效码；
- 用户只能操作属于自己的用户券；
- 管理 API 执行新增权限校验和审计；
- 批量发放限制文件大小、用户数和重复记录；
- 报价 ID 使用高熵随机值，且绑定用户与输入哈希；
- 所有 JSON 配置使用严格 schema、深度和长度限制；
- 金额运算使用 `Math.*Exact` 或显式溢出检查；
- 管理员不能直接把用户券改为 `AVAILABLE`，必须执行带原因的补偿/返还命令；
- 异常领取、连续报价和券码尝试进入结构化安全日志。

## 11. 可观测性

指标至少包括：

- 报价耗时、候选数量、组合搜索节点数；
- 报价成功/失效/重算次数；
- 领券、核销、返券冲突次数；
- 预算条件更新失败次数；
- 优惠计算守恒校验失败；
- 下单事务重试与死锁；
- 特殊叠券订单数量和金额；
- 规则原因码分布。

日志关联字段：`requestId`, `quoteId`, `orderNo`, `userId`, `ruleVersionId`, `userCouponId`。不得在日志中记录券码明文或密码类信息。

## 12. 性能目标

建议初始目标：

- 单商品、20 个候选优惠的报价 P95 小于 100 ms（不含网络）；
- 结算事务 P95 小于 250 ms；
- 每次报价候选上限可配置，默认 50；
- 组合搜索设确定性节点上限，达到上限时使用可证明合法的保守组合并记录降级指标；
- 不因报表查询锁定下单核心表，报表使用汇总表或离线聚合。

降级不能产生更高扣款：若无法确认用户已看到的报价仍有效，应拒绝提交并要求重算。

## 13. 数据迁移

### 13.1 兼容迁移

1. 新增表和可空列，不改变旧订单语义。
2. 历史订单回填：`base_amount = total_amount`、各优惠金额为 0，不创建价格快照记录。
3. 新代码双读：有快照走新退款；无快照走旧退款。
4. 功能开关关闭时，新列仍按无优惠结果写入，验证稳定性。
5. 开启只读报价和后台模拟。
6. 小范围用户/商品启用真实优惠。
7. 全量后再考虑把关键新列改为非空。

### 13.2 Schema 管理

- 同时更新 SQLite 和 MySQL schema provider；
- 新增官方快照迁移测试、schema script 测试和业务 SQL smoke test；
- 迁移脚本必须可重复执行；
- 大表加索引按数据库能力设计在线或分阶段迁移；
- 回滚版本必须能忽略新表，但已优惠订单不能交给不理解价格快照的旧版本退款。

因此优惠功能启用后，应用降级版本需要设置最低兼容 schema/应用版本保护。

## 14. 功能开关

- `promotion.schema-write-enabled`
- `promotion.quote-enabled`
- `promotion.user-ui-enabled`
- `promotion.checkout-enabled`
- `promotion.privileged-stacking-enabled`
- `promotion.admin-enabled`

紧急开关关闭新优惠时：

- 已生成但未提交的报价失效；
- 已完成订单仍按快照退款；
- 已领取券保持状态；
- 自动活动停止匹配；
- 管理员仍可只读查询和审计。

## 15. 推荐代码包结构

现有代码多位于 `com.webshopx`，本功能规模较大，建议新建：

```text
com.webshopx.promotion.domain
com.webshopx.promotion.pricing
com.webshopx.promotion.application
com.webshopx.promotion.persistence
com.webshopx.promotion.web
com.webshopx.promotion.admin
```

`OrderService` 只编排下单，不承载优惠规则。共享的数据库、钱包、商品和审计能力通过现有服务接口接入。
