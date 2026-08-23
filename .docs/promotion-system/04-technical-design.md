# 架构与现有系统集成

## 1. 架构目标

新增统一商业核心，同时保留官方商城、玩家市场、回收、充值和交付的领域服务。统一层负责购物车、报价、优惠、会员、资金和父交易；领域服务负责商品可用性、动态价格、库存/托管和履约。

```text
HTTP / Vue
  -> CartApplicationService
  -> CheckoutApplicationService
       -> PricingContextAssembler
       -> PricingEngine (pure)
       -> CombinationOptimizer (pure)
       -> AllocationEngine (pure)
       -> OfficialCommerceAdapter
       -> MarketCommerceAdapter
       -> MembershipService
       -> CouponService / PromotionAccountService
       -> WalletService / RechargeService
       -> Fulfillment queues
```

## 2. 当前代码基线与改造点

| 现有组件 | 保留能力 | 需要改造 |
|---|---|---|
| `ProductService` | 商品查询、固定/动态报价、动态事件 | 暴露连接内锁定报价接口和版本信息 |
| `OrderService` | 官方单商品下单、库存、限购、交付、退款 | 变成官方领域适配器；新下单由 checkout 编排；保留旧接口兼容 |
| `MarketService` | 挂单、买卖、拍卖、动态价、费用、托管、交付、退款 | 提取连接内锁定/成交原语，禁止嵌套事务；接收冻结分账结果 |
| `WalletService` | 双币钱包、业务键幂等、Vault 接入 | 支持一次结算多币种批量变动和平台促销资金账本协调 |
| `RechargeService` | 支付单、提供方、回调到账 | 在创建支付单前接收冻结充值优惠快照；到账区分基础/奖励币 |
| `RedeemCodeService` | 兑换码发钱包币 | 保持兼容；新权益码使用独立通用 grant 模型 |
| `RefundPolicyService` | 官方/市场退款规则冻结 | 与统一价格快照协作，保留业务可退款性判断 |
| `DeliveryService`/邮箱 | 异步发货、领取、失败恢复 | 继续使用；加入 checkout line/group 关联 |
| `AdminService` | 管理员角色和细粒度权限 | 添加新权限枚举和元数据 |
| `AdminAuditService` | 管理操作审计 | 覆盖活动、发券、会员和紧急操作 |
| `EmbeddedWebServer` | HTTP 路由、认证、错误处理 | 路由到新应用服务；避免继续膨胀业务计算 |
| `SchemaManager`/providers | SQLite/MySQL schema 与迁移 | 按第 06 文档增加跨库一致 schema |
| `ClusterEventBusService` | 集群事件 | 发布规则/资格/预算/会员缓存失效事件 |

不得复制现有动态定价、物品序列化、钱包、限购、退款策略或交付实现。

## 3. 推荐包结构

```text
com.webshopx.commerce.cart
com.webshopx.commerce.checkout
com.webshopx.commerce.order
com.webshopx.promotion.domain
com.webshopx.promotion.pricing
com.webshopx.promotion.application
com.webshopx.promotion.persistence
com.webshopx.membership
com.webshopx.sellerpromotion
```

HTTP handler 可以继续位于 `EmbeddedWebServer`，但请求解析后只调用应用服务。若执行者判断拆分 router/handler 更安全，可以重构并补回归测试。

## 4. 纯计算核心

### 4.1 `PricingEngine`

输入 `PricingContext`，输出 `PricingResult`。不得：

- 访问数据库；
- 读取系统当前时间；
- 生成随机数；
- 调用 Minecraft/Bukkit API；
- 修改输入集合；
- 使用浮点金额。

### 4.2 输入

- 明确 UTC `evaluationTime` 和业务时区；
- 用户/UUID/服务器/渠道/会员/entitlement 快照；
- 已由领域 adapter 报出的不可变基础行；
- 不可变规则版本和用户券；
- 预算/次数的可用快照；
- 用户选择、最低实付和算法版本。

### 4.3 输出

- 每币种/业务/卖家组汇总；
- 每行每层金额；
- 选择与未选择候选；
- 不可用原因和参数；
- 分摊、资金承担、费用、卖家应收和平台成本；
- 需要核销的券、资格、预算、次数和后置权益；
- 可规范化序列化的完整计算轨迹。

### 4.4 规范化与哈希

输入、规则和快照 JSON 使用固定字段顺序、稳定数组排序、UTF-8 和明确 null 处理，再计算 SHA-256。不要直接依赖 Gson map 的偶然顺序。哈希用于变化检测和审计，不替代数据库约束。

## 5. 领域适配器

### 5.1 官方适配器

职责：

- 读取商品、商品版本、类型和作用域元数据；
- 调用 `quoteOrderPrice` 形成 P0；
- 提交时锁商品、复核价格、限购和库存；
- 创建现有 `orders/order_items/delivery_queue/group_buy_vouchers`；
- 成功后应用动态价格事件。

新增会员商品类型时，交付任务改为 entitlement grant，不用命令模板模拟会员。

### 5.2 市场适配器

职责：

- 只允许可购物车化挂单形成正向行；
- 提交时按 listing ID 升序锁定、复核数量/价格/买家非卖家；
- 使用统一结果写 `market_trades` 的买家总额、卖家应收、费用和关联；
- 更新挂单库存/动态事件并入队交付；
- 暴露收购履约和拍卖成交的权益钩子。

现有 `buyListingInTransaction` 等大方法应提取内部原语，避免新 checkout 调用一个会再次打开事务的公开方法。

### 5.3 充值适配器

- 报价时读取充值路由、汇率、提供方能力和规则；
- 创建支付单时保存价格快照及应付小额单位；
- 回调核对提供方、商户单号、金额、币种和状态；
- 同一事务将基础币、奖励币分别入账并消费优惠；
- 取消/过期释放券与预算预留。

## 6. 应用服务

| 服务 | 职责 |
|---|---|
| `CartService` | 持久购物车 CRUD、版本、来源校验与摘要 |
| `CheckoutQuoteService` | 组装上下文、调用纯引擎、保存报价 |
| `CheckoutService` | 复核报价、锁资源、创建父/子订单和交付 |
| `PromotionService` | 活动草稿、版本、状态、作用域和查询 |
| `CouponService` | 模板、领取、发放、券码、预留、消费、返还 |
| `EligibilityService` | 派生/持久资格与并发计数 |
| `MembershipService` | 计划、权益包、授予、续期、升级、撤销和到期 |
| `PromotionAccountService` | 平台预算、次数、库存、预留、消费和冲回 |
| `SellerPromotionService` | 所有权检查、卖家模板和平台限制 |
| `CommerceRefundService` | 读取快照、协调领域退款、资金反冲和退券 |
| `PromotionAdminService` | 模拟、发布、暂停、报表和紧急控制 |

## 7. 报价生命周期

1. 读取购物车指定版本和用户上下文。
2. adapters 生成 P0 行，不加锁或只做短读。
3. 查询当前规则、券、会员、预算视图。
4. 纯引擎计算。
5. 保存短期报价（建议 60 秒；动态市场可 30 秒）。
6. 返回客户端。
7. 提交时读取报价并校验归属、状态、期限、cart/input hash。
8. 事务内按固定顺序加锁并重新组装关键上下文。
9. 若结果/规则哈希/最终应付变化，拒绝并返回新报价；不得自动扣款。
10. 完全一致才消费并创建订单。

普通报价默认不预留市场库存和平台预算，防止恶意占用。用户券可选择短预留，但首版实现如无需长支付等待，可在提交事务中直接消费。充值支付必须预留到支付单终态。

## 8. 结算事务

### 8.1 固定锁顺序

1. checkout 幂等记录；
2. cart/user/资格计数器；
3. 官方商品 ID 升序；
4. 市场 listing ID 升序；
5. 用户券 ID 升序；
6. 活动资金账户 ID 升序；
7. 钱包/平台资金账户；
8. 父订单、子单、快照和交付记录。

SQLite 使用现有事务/重试；MySQL 使用 provider 的 `FOR UPDATE`。条件更新始终检查影响行数。

### 8.2 操作顺序

```text
读取已存在幂等结果
-> 复核 cart/quote/input hash
-> 锁定所有来源与资格资源
-> 重算并比较报价
-> 验证钱包余额
-> 消费券/资格/预算/次数
-> 批量扣用户钱包并记平台资金
-> 创建父订单、组、行、快照、分摊
-> 创建官方 orders 与 market_trades 投影
-> 更新库存/挂单/动态价格/限购
-> 创建交付或 entitlement 任务
-> 标记报价使用、移除购物车行
-> 提交
```

异常全部回滚。事务提交后再发集群失效事件和非关键通知；事件失败不能回滚已提交订单，必须可重试。

## 9. 多钱包币种扣款

同一数据库事务分别对 SHOP_COIN/GAME_COIN 扣款。应先锁单个钱包行，再验证两个余额，然后批量写 ledger。每币种使用唯一业务 ID，例如 `checkout:{checkoutNo}:{currency}`。

若 Vault 作为 GAME_COIN 外部来源无法与数据库事务原子提交，执行者必须审计现有 `WalletService` 语义并实现可靠补偿/预留协议；不得假设其天然原子。发现无法保证全购物车原子性时属于重大阻塞，应记录并请求确认可接受的补偿策略。

## 10. 平台促销资金账户

建立独立业务账本，不要求是普通用户钱包：

- `limitAmount`：预算，可空表示无限；
- `reserved/consumed/released/refunded`；
- `currency`；
- 每日/总周期子账户；
- 唯一业务键。

平台补贴消费必须与订单事务一致。报表中的平台资金守恒由账户账本验证。

## 11. 卖家优惠成本

卖家优惠通常通过降低 `seller_receive` 实现，不提前扣卖家钱包。必须：

- 下单前校验平台最低应收；
- 冻结卖家让利；
- 结算/退款使用冻结值；
- 卖家报表可查询；
- 避免卖家把商品改价后改变已生成报价。

若是卖家承担的收购奖励，则因付款方向不同，必须使用现有 escrow 扩展预留最大负债。

## 12. 幂等

| 操作 | 业务唯一键 |
|---|---|
| 购物车修改 | `userId + mutationId`（必要时） |
| 结算 | `userId + idempotencyKey`，另存 input hash |
| 领券 | `userId + templateId + claimRequestId` |
| 批量发券 | `batchId + userId + templateId` |
| 券消费 | `userCouponId + checkoutOrderId` |
| 预算账本 | `accountId + bizType + bizId` |
| 会员授予 | `membershipPlanId + subject + sourceType + sourceRef` |
| 退款 | 复用现有 refund request key 并关联 line units |
| 后置奖励 | `benefitGrantId + triggerType + triggerId` |

同键不同输入必须返回 `IDEMPOTENCY_CONFLICT`，不能返回旧结果伪装成功。

## 13. 并发策略

- 券/预算/次数采用条件更新或行锁，禁止先查后写。
- 市场库存使用锁定 listing 的当前数量。
- 官方库存/限购沿用并加强现有事务锁。
- 首购资格以计数器预留/消费，两个并发结算只能一个成功。
- 会员购买同一来源重复回调只授予一次。
- 活动版本发布与报价通过版本/hash 检测。
- 测试必须覆盖 SQLite busy retry 和 MySQL deadlock retry。

## 14. 会员到期与任务

- 查询有效性以当前 UTC 与实例时间为准，不能依赖定时任务准时运行。
- 定时任务只负责状态物化、通知和周期券发放。
- 周期权益使用 `periodKey` 幂等。
- 服务器停机后重启要补发未执行周期任务，但不重复。

## 15. 缓存与集群

可缓存活动目录、商品集合、会员计划和展示元数据；不可只靠缓存核销券/预算。

事件类型建议：

- `PROMOTION_RULE_CHANGED`
- `PROMOTION_STATE_CHANGED`
- `COUPON_TEMPLATE_CHANGED`
- `MEMBERSHIP_PLAN_CHANGED`
- `USER_ENTITLEMENT_CHANGED`
- `SELLER_PROMOTION_CHANGED`
- `PROMOTION_ACCOUNT_EXHAUSTED`

缓存失效丢失只允许造成短期额外数据库读取，不允许造成错误成交。

## 16. 时间

- 数据库存 UTC `DATETIME/TIMESTAMP`，业务输入由 `TimeSupport` 转换。
- API 输出 ISO-8601 offset 时间。
- 活动区间推荐 `[startAt, endAt)`。
- 价格计算显式传入 `evaluationTime`。
- 结算用事务开始时的服务端 UTC，不信任客户端时间。

## 17. 安全

- 券码只存带盐/密钥的不可逆哈希和展示后缀；生成时明文只返回一次。
- 资源归属在服务端验证；不能靠前端隐藏按钮。
- JSON 规则使用严格 schema、枚举、深度、数组和字符串上限。
- 金额使用 `Math.addExact/multiplyExact` 等溢出保护。
- 报价 ID 高熵、绑定用户、输入和期限。
- 券码领取、报价、模拟和批量发放限速。
- 日志不记录密码、session token、支付密钥或券码明文。
- 管理员补偿、零元订单、特殊叠券和会员手工变更审计。

## 18. 可观测性

指标：

- 报价 P50/P95/P99、候选数、搜索节点数、降级数；
- 报价失效原因；
- 预算/券/首购并发冲突；
- checkout 重试/死锁/回滚；
- 资金守恒或分摊校验失败；
- 特殊叠券、零元订单、平台补贴和卖家让利；
- 会员授予/撤销/周期任务；
- 充值优惠预留超时；
- 退款和人工处理队列。

日志关联：`requestId`, `quoteId`, `checkoutNo`, `groupNo`, `orderNo/tradeId`, `userId`, `sellerId`, `ruleVersionId`, `userCouponId`。

## 19. 性能目标

- 常规 20 行、50 候选报价：服务端 P95 < 200ms（不含网络）。
- 结算事务 P95 < 500ms，外部 Vault 除外但需独立指标。
- 候选和搜索节点有配置上限；达到上限不得产生非法组合。
- 规则查询批量化，禁止逐购物车行 N+1。
- 报表不在结算事务中聚合大表；使用事件/汇总表。

如保守降级无法保证用户最低价，返回“暂时无法计算优惠”而不是多扣已确认报价。

## 20. 兼容入口

现有 `/api/orders` 单商品下单和 `/api/market/buy` 应保留一段兼容期，但内部构造临时购物车/单行 checkout 调用新核心。旧请求没有 quoteId 时可自动报价后立即提交，只能在最终金额未变化且保持旧行为时使用；新前端必须走显式报价。

现有订单号、市场交易 ID、邮箱来源和领取命令继续工作。父 checkout 增加新展示，不破坏旧链接。

## 21. 上线开关与紧急止损

开关用于安全部署和回滚，不代表允许交付永久残缺的产品。建议接入现有运行时配置机制：

- `commerce.schema-write-enabled`：仅控制新表写入，用于迁移验证；
- `commerce.cart-enabled`：购物车入口；
- `promotion.quote-enabled`：新报价引擎；
- `commerce.checkout-enabled`：新统一结算；
- `promotion.seller-enabled`：卖家促销创建与生效；
- `membership.enabled`：会员获取与权益生效；
- `promotion.recharge-enabled`：充值优惠；
- `promotion.emergency-stop`：全局紧急停止新增优惠承诺。

开关默认值和依赖必须明确：`checkout` 依赖 schema、cart 和 quote；卖家促销、会员、充值优惠依赖 quote。生产启用顺序按依赖从底向上，关闭顺序反向。

紧急停止时：

1. 禁止新报价、领券、建活动和新会员购买；
2. 未提交报价立即失效并返回稳定原因码；
3. 已成功提交的订单、已创建的法币支付、退款和补偿继续履约；
4. 不自动删除券、活动、会员或账本数据；
5. 记录操作者、原因、时间和影响范围，并发出集群失效事件；
6. 恢复后重新评估资格，不复活过期报价。

普通功能开关变更需要配置审计；全局紧急停止需要 `PROMOTION_EMERGENCY_STOP`。任何回滚都不能回滚数据库中已承诺的资金、券或权益事实。
