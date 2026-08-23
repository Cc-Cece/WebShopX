# 领域模型与价格计算协议

## 1. 本文地位

本文定义金额、优惠、分账和退款的强制协议。任何页面、API 或服务都不能另写一套价格公式。执行者可以优化内部算法，但相同输入必须产生符合本文不变量的相同结果。

## 2. 全局不变量

1. 金额使用有符号 64 位整数存储，但业务输入必须非负并执行溢出检查。
2. 比例使用基点 `bps`；`10000` 表示 100%，不得使用浮点数。
3. 同币种金额才能求和、比较、凑门槛和抵扣。
4. 钱包币种 `SHOP_COIN/GAME_COIN` 与法币小额单位是不同金额空间，永不混算。
5. 任一商品行最终应付不得为负；默认不得低于适用最低实付。
6. 每项优惠实际金额等于其全部商品行分摊之和。
7. 订单总优惠等于各项即时优惠之和；返现和赠送权益不计入本次即时优惠。
8. 用户实付、卖家应收、平台费用、税费、平台补贴与卖家让利必须满足对应业务的资金守恒式。
9. 同一输入快照、规则版本、算法版本和选择产生确定结果，与数据库返回顺序无关。
10. 历史订单只读取冻结结果，不用当前活动重新计算。
11. 幂等重试返回首次成功结果，不重复扣款、用券、占预算、改库存或授予权益。
12. 累计退款不得超过原实付；全部可退款单位最终退款之和必须等于原实付。

## 3. 核心对象

| 对象 | 定义 |
|---|---|
| `Cart` | 登录用户的持久购物意向，不锁价、不锁库存 |
| `CartLine` | 一个来源对象、数量、选择配置和客户端观察版本 |
| `CheckoutQuote` | 短期有效、绑定用户和购物车输入的完整报价 |
| `CheckoutOrder` | 一次用户确认产生的父交易 |
| `OrderGroup` | 按币种、卖家、业务域和履约策略拆出的子订单 |
| `OrderLine` | 价格、优惠和退款的最小商品行 |
| `PriceRule` | 自动促销、券、会员权益或业务奖励的不可变版本 |
| `CouponTemplate` | 券的发行与使用规则 |
| `UserCoupon` | 归属于具体用户的一张券实例 |
| `Entitlement` | 会员、叠券槽位、白名单等用户资格 |
| `FundingShare` | 一项优惠由平台或卖家承担的金额 |
| `PriceSnapshot` | 冻结输入、候选、选择、计算轨迹和分摊的不可变记录 |

## 4. 交易与权益方向

统一引擎必须显式区分方向：

| 方向 | 业务 | 优惠含义 |
|---|---|---|
| `USER_PAYS` | 官方购买、市场直接购买、会员购买 | 降低用户应付或产生后置奖励 |
| `USER_RECEIVES` | 官方回收、履行市场收购单 | 提高用户所得或降低平台/收购方费用 |
| `EXTERNAL_PAY` | 法币充值 | 降低法币应付或提高到账币数量 |
| `BID_ESCROW` | 拍卖出价 | 不改变公开出价；仅在成交后处理费用或后置权益 |

一条规则必须声明允许的方向，禁止将“满 100 减 20”直接用于 `USER_RECEIVES` 造成用户少收钱。

## 5. 金额类型

### 5.1 钱包金额

```text
WalletMoney(currency = SHOP_COIN | GAME_COIN, amount >= 0)
```

币种最小单位就是整数 1。

### 5.2 法币金额

```text
FiatMoney(currency = ISO-4217 code, amountMinor >= 0)
```

必须沿用支付提供方支持的法币及小额单位。折扣后的 `amountMinor` 传给支付提供方，并在回调中严格核对。

### 5.3 比例和取整

```text
discount = roundHalfUp(basis × discountBps / 10000)
```

百分比先算该规则的作用域总额，再分摊到行。不得逐行先取整后相加，否则会因拆行改变结果。

## 6. 基础价格

### 6.1 官方商城

- 固定价商品：P0 为 `products.price × quantity`。
- 动态价商品：调用现有动态报价算法，保存首件、末件、平均、总额、需求分和下一价格；P0 使用精确总额，不用平均价乘数量反推。
- 零价商品继续受现有 `PRODUCT_ZERO_PRICE` 及新增价格保护约束。

### 6.2 玩家市场直接出售

- P0 为挂单固定/动态报价得到的 `listingGross`。
- 现有手续费和税费不属于商品优惠，按第 14 节顺序计算。
- 购物车不锁市场数量；提交时锁挂单并重新报价。

### 6.3 市场收购与官方回收

- P0 是用户本应获得的回收/履约金额。
- 正向优惠表现为 `payoutBonus`，使用户所得增加。
- 若奖励由收购方玩家承担，必须在创建收购单时连同最大潜在奖励进入托管；否则只能由平台承担。

### 6.4 充值

- `fiatBaseAmount` 是支付提供方订单金额。
- `baseCoinAmount` 是原充值汇率应到账金额。
- 法币减免只改变 `fiatPayableAmount`；赠币只改变 `creditedCoinAmount`。
- 两者成本和退款处理分别记录，不能把赠币伪装成法币折扣。

### 6.5 拍卖

- 出价和排行榜始终使用公开竞价金额，不应用个人券或会员价。
- 成交结算可使用平台费用减免或成交后奖励，但不能追溯改变胜出条件。
- 卖家自设商品券不适用于拍卖，避免竞价前无法确定最终优惠。

## 7. 优惠类型

### 7.1 即时降价

- `FIXED_PRICE`：作用商品在指定价格层变为固定价。
- `DIRECT_REDUCTION`：固定减免。
- `PERCENT_OFF`：比例减免，必须有最大减免或全局明确上限。
- `AMOUNT_THRESHOLD_OFF`：满额减。
- `QUANTITY_THRESHOLD_OFF`：满件减/折。
- `TIERED_OFF`：多个金额或数量阶梯，默认取最高满足档。
- `EVERY_THRESHOLD_OFF`：每满 X 重复减 Y，必须有重复次数上限。
- `MULTI_BUY`：第 N 件折、买 N 件组合价。
- `FEE_REDUCTION`：只减少手续费或税费允许的部分。
- `FIAT_PAYMENT_REDUCTION`：只减少外部支付金额。

### 7.2 券

- `NORMAL_COUPON`
- `SELLER_COUPON`
- `PLATFORM_COUPON`
- `MEMBER_COUPON`
- `COMPENSATION_COUPON`
- `RECHARGE_COUPON`
- `PAYOUT_BONUS_COUPON`

券名是运营分类；实际计算仍由金额/比例/阶梯规则决定。

### 7.3 自动权益与后置奖励

- `MEMBER_PRICE`、`MEMBER_DISCOUNT`
- `PAYOUT_BONUS`
- `RECHARGE_COIN_BONUS`
- `CASHBACK`
- `COUPON_REWARD`
- `MEMBERSHIP_REWARD`
- `GIFT_REWARD`

后置奖励在订单达到配置状态时发放，例如 `PAID`、`DELIVERED` 或 `COMPLETED`。发放必须幂等；退款时按规则撤销、扣回或标记人工处理。

## 8. 价格层

统一顺序：

| 层 | 名称 | 内容 |
|---|---|---|
| P0 | 基础报价 | 固定/动态商品价、市场挂单价、回收基价、充值基价 |
| P1 | 单品身份价 | 会员价、定向价、限时固定价 |
| P2 | 单品/多件促销 | 直降、多件、数量阶梯 |
| P3 | 卖家订单促销 | 玩家卖家满减/满折，仅作用其子单 |
| P4 | 平台订单促销 | 官方/跨卖家平台满减、业务促销 |
| P5 | 优惠券 | 卖家券、平台券、会员券、补偿券 |
| P6 | 费用权益 | 手续费减免、税费允许的减免 |
| P7 | 外部支付优惠 | 充值法币优惠 |
| P8 | 后置权益 | 返币、赠券、会员、赠品；不改变即时应付 |

规则必须声明 `thresholdBasisLayer` 与 `discountBasisLayer`。默认门槛使用该规则执行前、同作用域当前金额；不能仅凭层号猜测。

## 9. 作用域

### 9.1 商品作用域

- 全部可销售官方商品；
- 官方商品 ID、SKU、商品集合、产品类型；
- 玩家卖家 ID、市场挂单 ID、市场标签、交易模式；
- 业务域：官方购买、市场购买、回收、收购履约、充值、团购、拍卖成交；
- 服务器、入口渠道；
- 包含集合与排除集合。

排除优先。发布后的规则引用不可变作用域版本；商品集合后续修改不得改变历史规则版本。

### 9.2 订单层级

- `LINE`：单商品行；
- `SELLER_GROUP`：同一玩家卖家的可用商品；
- `BUSINESS_GROUP`：同业务域子订单；
- `CURRENCY_GROUP`：同币种全部行；
- `CHECKOUT`：父交易，但金额门槛仍必须在同币种内计算。

### 9.3 不转移原则

某项优惠只可分摊到其作用域内的行。某行达到最低实付后，剩余优惠可以转移给同一作用域其他可减行，不得转移到作用域外。

## 10. 用户资格

领取和使用分别验证：

- 注册时间、全局首单；
- 官方商品集合首购；
- 某玩家卖家首购；
- 历史完成订单、消费额或回流天数；
- 会员计划/等级及有效期；
- 管理员标签或白名单；
- 服务器、渠道；
- 用户 ID、绑定 MC UUID 或未来身份组；
- 风控状态；
- 领取、使用、预算和周期次数。

首单、首购、限次等资格必须在提交事务中锁定/条件消费。预览结果不是最终授权。

## 11. 叠加槽位与冲突

### 11.1 默认槽位

| 槽位 | 默认容量 | 作用层级 |
|---|---:|---|
| `ITEM_PRICE` | 每行 1 | P1 |
| `ITEM_PROMOTION` | 每行 1 | P2 |
| `SELLER_PROMOTION` | 每卖家组 1 | P3 |
| `PLATFORM_PROMOTION` | 每币种组 1 | P4 |
| `SELLER_COUPON` | 每行 1 | P5 |
| `PLATFORM_COUPON` | 每行 1 | P5 |
| `MEMBER_COUPON` | 每行 1，仅有资格时存在 | P5 |
| `COMPENSATION` | 每父交易 1 | P5 |
| `FEE_BENEFIT` | 每费用类型 1 | P6 |
| `RECHARGE_BENEFIT` | 每充值单 1 | P7/P8 |

卖家券和平台券默认可以跨层叠加；两张都作用同一行时分别占各自槽位。两张普通平台券仍不能因用户是会员而共同占用一个 `PLATFORM_COUPON` 槽位。

### 11.2 策略

- `EXCLUSIVE_CHECKOUT`：整次结算排斥其他即时优惠；仅特殊业务使用。
- `EXCLUSIVE_SCOPE`：覆盖行排斥其他即时优惠。
- `SAME_SLOT_SINGLE`：同槽择优。
- `DISJOINT_SCOPE`：同槽但作用行不重叠时可共存。
- `CROSS_LAYER`：允许与声明的其他槽位共存。
- `PRIVILEGED_SLOT`：具备指定 entitlement 才开放。
- `WHITELIST_ONLY`：仅与明确规则/槽位组合。

每条规则至少保存槽位、策略、作用域容量、兼容槽位、互斥组、所需资格和每单最大使用次数。

### 11.3 特殊叠券

特殊用户通过 entitlement 解锁额外槽位或白名单，例如：

```text
普通用户：PLATFORM_COUPON × 1
会员用户：PLATFORM_COUPON × 1 + MEMBER_COUPON × 1
补偿用户：上述槽位 + COMPENSATION × 1（仅指定补偿券）
```

禁止 `canStackEverything` 一类全局绕过。

## 12. 门槛与减免

规则字段：

- `thresholdType = NONE | AMOUNT | QUANTITY`
- `thresholdValue`
- `thresholdBasisLayer`
- `discountBasisLayer`
- `repeatMode = ONCE | HIGHEST_TIER | EVERY_FULL_THRESHOLD`
- `maxRepeatCount`
- `discountAmount` 或 `discountBps`
- `maxDiscountAmount`

固定减免：

```text
raw = discountAmount × repeats
actual = min(raw, maxDiscount, eligibleReducibleAmount)
```

比例减免：

```text
raw = roundHalfUp(discountBasis × discountBps / 10000)
actual = min(raw, maxDiscount, eligibleReducibleAmount)
```

`eligibleReducibleAmount` 已扣除商品、规则、卖家和全局最低实付保护。

## 13. 最优组合

### 13.1 候选过滤

先验证时间、状态、方向、币种、作用域、资格、持有关系、次数、库存和预算。失败候选保留原因，但不进入组合搜索。

### 13.2 合法组合

用冲突图/约束模型表达槽位、互斥、独占、资格、预算和最低实付。执行者可使用枚举、分支定界或其他确定性方法，但不能使用随机近似导致同输入不同结果。

### 13.3 优化目标

按顺序比较：

1. 用户即时应付最小；
2. 用户将获得的确定性后置权益价值最大（只在可比较且管理员配置价值时使用）；
3. 更早过期的用户券优先；
4. 用户明确选择优先；
5. 使用券数量更少；
6. 规则类型、版本 ID、用户券 ID 稳定升序。

卖家收入或平台成本不能覆盖第一目标偷偷让用户多付；若平台要控制补贴，应通过预算/适用范围让组合不合法。

### 13.4 手动选择

- 默认 `AUTO_BEST`。
- 用户可以固定某些券或关闭标记为 `userToggleable` 的优惠。
- 指定组合非法时返回原因和新建议，不静默换成另一组合。
- 用户提交的是用户券 ID/规则选择，不提交任何可信金额。

## 14. 市场分账公式

对每个玩家卖家组、每种币种分别计算。

定义：

```text
listingGross       = 挂单基础成交额
sellerDiscount     = 卖家承担的优惠
platformDiscount   = 平台承担的优惠
sharedSellerPart   = 共同优惠中的卖家部分
sharedPlatformPart = 共同优惠中的平台部分
merchantBasis      = listingGross - sellerDiscount - sharedSellerPart
fee                = feePolicy(merchantBasis)
tax                = taxPolicy(merchantBasis)
buyerMerchandise   = merchantBasis - platformDiscount - sharedPlatformPart
buyerTotal         = buyerMerchandise + tax - allowedBuyerFeeBenefit
sellerReceive      = merchantBasis - fee
platformFunding    = platformDiscount + sharedPlatformPart + allowedBuyerFeeBenefit
platformRevenue    = fee + tax - platformFunding
```

约束：

- `merchantBasis >= sellerMinimumReceivableBasis >= 0`；
- `buyerMerchandise >= applicableMinPayable`；
- 平台优惠不降低 `sellerReceive`；
- 卖家优惠不消耗平台预算；
- 共同承担金额分别进入卖家和平台账本；
- 费用策略必须冻结版本，不能退款时重新读取当前费率。

封闭钱包中的平台补贴必须进入平台促销资金账本。它可以是系统预算账户而非普通玩家钱包，但必须有借贷方向和业务键，不能无痕增发。

## 15. 官方商城公式

```text
officialBase       = P0 总额
officialDiscount   = 所有即时平台优惠
buyerMerchandise   = officialBase - officialDiscount
buyerTotal         = buyerMerchandise + applicableFees
platformFunding    = officialDiscount
```

官方商品没有玩家卖家应收，但仍记录平台优惠成本，便于预算与报表。

## 16. 回收/收购公式

```text
basePayout       = 基础回收或收购成交额
platformBonus    = 平台承担加成
requesterBonus   = 收购方承担加成（必须有托管）
userReceives     = basePayout + platformBonus + requesterBonus - applicableFee
requesterCost    = basePayout + requesterBonus
platformFunding  = platformBonus
```

用户不能通过同一批物品同时参与相互冲突的回收奖励。奖励作用于实际验收数量，不能只按用户声明数量。

## 17. 充值公式

```text
fiatBaseAmount       = 原应付法币小额单位
fiatDiscount         = 法币即时优惠
fiatPayableAmount    = fiatBaseAmount - fiatDiscount
baseCoinAmount       = 原应到账商城币
bonusCoinAmount      = 赠币/会员加成
creditedCoinAmount   = baseCoinAmount + bonusCoinAmount
```

支付回调必须核对 `fiatPayableAmount` 和法币币种。到账钱包流水至少区分本金币与奖励币，便于退款时按政策回收奖励。若支付提供方不支持部分退款，本功能不得伪造成功退款。

## 18. 分摊算法

每项优惠使用最大余数法：

1. 取该规则作用域内仍可减的行。
2. 默认权重为该规则计算层输入行金额；规则可以声明数量权重，但必须版本化。
3. 计算精确份额并向下取整。
4. 剩余最小单位按小数余数降序分配。
5. 余数相同按稳定 `orderLineId` 升序。
6. 达到行最低实付后，把溢出继续分给其他可减行。
7. 若无行可承载，实际优惠截断并记录 `MIN_PAYABLE_GUARD`。

卖家/平台共同承担先得到优惠总额，再按资金比例拆成 funding share；两个维度都必须守恒。

## 19. 数量单位分摊

部分交付要求把行最终金额进一步分到单位：

1. 先将 `lineFinalAmount / quantity` 向下取整为基础单位实付。
2. 余数按单位序号从小到大各加 1。
3. 保存压缩区间或可重放的基础值、余数和单位排序。
4. 退款按实际未交付/退回的单位序号求和。

不能只保存折后平均单位价并用四舍五入反推。

## 20. 报价与价格快照

报价至少冻结：

- 用户、MC UUID、服务器、渠道和时间；
- 购物车行来源、数量、版本及 P0 动态报价细节；
- 规则/券/会员资格版本；
- 所有候选及可用/不可用原因；
- 选择模式和最终组合；
- 每层输入输出、每项优惠、每行分摊与 funding share；
- 费用/税费策略版本；
- 各币种总额、卖家应收、平台资金和后置权益；
- 输入哈希、规则哈希、算法版本、创建和过期时间。

下单快照是报价在事务内复核后的最终版本。报价 JSON 不可信，服务端必须从数据库复核关键资源。

## 21. 取消、退款、退券与撤销权益

### 21.1 支付退款

- 只退冻结的可退款单位实付。
- 平台与卖家资金按原 funding share 反向冲回。
- 费用/税费是否退按冻结策略执行。
- 最后一次全退吸收全部取整余数。

### 21.2 券返还策略

- `NEVER`
- `CANCEL_BEFORE_FULFILLMENT`
- `FULL_REFUND_ONLY`
- `ANY_REFUND_ONCE`
- `MANUAL_REVIEW`

有效期：

- `KEEP_ORIGINAL_EXPIRY`（默认）；
- `EXTEND_FIXED_DURATION`；
- `ISSUE_COMPENSATION`。

退款金额和返券是独立结果。部分退款默认不返整券。

### 21.3 会员与后置权益

- 会员商品退款策略可为“不撤销”“未使用则撤销”“按剩余期限撤销”“人工处理”。
- 自动授予/撤销必须幂等且记录原因。
- 已消费的奖励币不足以扣回时，不允许钱包变成非法负数；按规则拒绝退款、扣除退款金额或转人工处理，并明确展示。

## 22. 稳定原因码

至少实现：

- 状态：`NOT_STARTED`, `EXPIRED`, `DISABLED`, `PAUSED`
- 范围：`BUSINESS_MISMATCH`, `CURRENCY_MISMATCH`, `SCOPE_MISMATCH`, `CHANNEL_MISMATCH`
- 资格：`USER_NOT_ELIGIBLE`, `FIRST_ORDER_REQUIRED`, `MEMBERSHIP_REQUIRED`, `ENTITLEMENT_REQUIRED`
- 券：`NOT_OWNED`, `ALREADY_USED`, `COUPON_RESERVED`, `USER_LIMIT_REACHED`
- 资源：`BUDGET_EXHAUSTED`, `STOCK_EXHAUSTED`, `LISTING_UNAVAILABLE`
- 规则：`THRESHOLD_NOT_MET`, `QUANTITY_NOT_MET`, `STACKING_CONFLICT`, `EXCLUSIVE_PROMOTION`, `SLOT_CAPACITY_REACHED`
- 价格：`MIN_PAYABLE_GUARD`, `SELLER_MINIMUM_GUARD`, `MAX_DISCOUNT_REACHED`, `PRICE_CHANGED`
- 报价：`QUOTE_EXPIRED`, `QUOTE_INPUT_CHANGED`, `RULE_VERSION_CHANGED`
- 交易：`IDEMPOTENCY_CONFLICT`, `PARTIAL_CHECKOUT_NOT_ALLOWED`, `REFUND_REVIEW_REQUIRED`

API 返回原因码和结构化参数；前端通过 i18n 生成完整句子。
