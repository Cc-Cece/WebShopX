# HTTP API 契约

## 1. 通用约定

- 复用现有用户/admin session 认证、CORS、JSON 解析和 `ServiceException` 映射。
- JSON 字段使用 camelCase；稳定枚举使用大写代码。
- 金额均为 JSON 整数，不传格式化字符串或浮点。
- 时间为 ISO-8601 offset 字符串；输入无 offset 时按现有业务时区转换。
- 修改请求使用 `idempotencyKey` 或 `expectedVersion`。
- 分页优先 cursor，响应 `nextCursor` 可空。
- 错误至少返回：

```json
{
  "code": "THRESHOLD_NOT_MET",
  "message": "diagnostic fallback",
  "details": { "required": 100, "current": 80, "currency": "SHOP_COIN" },
  "requestId": "..."
}
```

前端用 `code + details` 映射 i18n；`message` 仅诊断回退。若现有统一错误格式字段略有不同，应保持兼容并扩展 details/requestId。

## 2. 购物车 API

### 2.1 `GET /api/cart`

响应：

```json
{
  "cartId": 12,
  "version": 7,
  "lines": [
    {
      "id": 31,
      "sourceType": "OFFICIAL_PRODUCT",
      "sourceId": 8,
      "quantity": 2,
      "deliveryMode": "CLAIM",
      "selected": true,
      "status": "AVAILABLE",
      "display": { "title": "...", "sellerName": null, "icon": {} },
      "observedPrice": { "currency": "SHOP_COIN", "amount": 200 },
      "reason": null
    }
  ],
  "summary": { "selectedCount": 1, "invalidCount": 0 }
}
```

`observedPrice` 是展示信息，不是可提交价格。

### 2.2 `POST /api/cart/lines/add`

```json
{
  "sourceType": "MARKET_LISTING",
  "sourceId": 91,
  "quantity": 1,
  "deliveryMode": "IMMEDIATE",
  "expectedCartVersion": 7,
  "idempotencyKey": "uuid"
}
```

返回最新购物车。不存在/不可购物车化返回稳定错误。

### 2.3 其他操作

- `POST /api/cart/lines/update`：`lineId, quantity?, selected?, deliveryMode?, expectedCartVersion`
- `POST /api/cart/lines/remove`：`lineId, expectedCartVersion, idempotencyKey`
- `POST /api/cart/clear`：`expectedCartVersion, idempotencyKey`
- `POST /api/cart/selection`：批量选择 line IDs

版本不符返回 HTTP 409 `CART_VERSION_CONFLICT`，details 携带最新版本；不要 last-write-wins。

## 3. 结算报价

### 3.1 `POST /api/checkout/quote`

```json
{
  "cartVersion": 8,
  "lineIds": [31, 32, 40],
  "selectionMode": "AUTO_BEST",
  "selectedUserCouponIds": [],
  "disabledRuleVersionIds": [],
  "clientContext": { "channel": "WEB" }
}
```

手动模式仍由服务端验证。响应：

```json
{
  "quoteId": "pq_...",
  "expiresAt": "2026-08-13T12:01:00Z",
  "cartVersion": 8,
  "selectionMode": "AUTO_BEST",
  "groups": [
    {
      "groupKey": "SHOP_COIN:MARKET:SELLER:42",
      "businessType": "MARKET_PURCHASE",
      "sellerUserId": 42,
      "currency": "SHOP_COIN",
      "baseAmount": 100,
      "sellerDiscount": 10,
      "platformDiscount": 5,
      "feeAmount": 4,
      "taxAmount": 0,
      "buyerTotal": 85,
      "sellerReceive": 86,
      "platformFunding": 5,
      "lines": []
    }
  ],
  "currencyTotals": [
    { "currency": "SHOP_COIN", "baseAmount": 300, "discountAmount": 35, "payableAmount": 265, "walletBalance": 500 }
  ],
  "appliedBenefits": [
    {
      "sourceType": "USER_COUPON",
      "sourceId": 701,
      "displayName": "...",
      "discountAmount": 20,
      "funding": [{ "type": "PLATFORM", "amount": 20 }],
      "expiresAt": "..."
    }
  ],
  "alternatives": [],
  "unavailableBenefits": [
    { "sourceType": "USER_COUPON", "sourceId": 702, "reasonCode": "THRESHOLD_NOT_MET", "reasonDetails": { "missingAmount": 20 } }
  ],
  "postOrderBenefits": [],
  "warnings": []
}
```

`lines` 必须包含逐层金额和优惠分摊；示例省略不代表可省字段。

### 3.2 `POST /api/checkout/submit`

```json
{
  "quoteId": "pq_...",
  "cartVersion": 8,
  "idempotencyKey": "uuid"
}
```

成功 HTTP 200/201：

```json
{
  "state": "CREATED",
  "checkoutNo": "CHK-...",
  "status": "PLACED",
  "groups": [
    { "groupNo": "CHG-...", "businessType": "OFFICIAL_PURCHASE", "legacyOrderNo": "ODR-...", "status": "PENDING" },
    { "groupNo": "CHG-...", "businessType": "MARKET_PURCHASE", "marketTradeIds": [123], "status": "PENDING" }
  ],
  "currencyTotals": [],
  "pricing": {}
}
```

幂等重试 `state=EXISTING`。报价变化 HTTP 409：

```json
{
  "code": "PRICE_CHANGED",
  "details": { "replacementQuote": { "quoteId": "pq_new", "...": "full quote" }, "changes": [] }
}
```

不能自动提交 replacement。

## 4. 父订单

- `GET /api/checkouts/list?limit=&cursor=`：父交易列表，兼容订单页。
- `GET /api/checkouts/detail?checkoutNo=`：组、行、价格、交付、退款、券/会员处理。
- `GET /api/checkouts/pricing?checkoutNo=`：完整用户可见价格解释，不返回内部敏感配置。
- `POST /api/checkouts/refund-preview`：line/quantity 预览。
- `POST /api/checkouts/refund`：`checkoutNo, requestedUnits/lines, idempotencyKey`，内部协调现有退款。

旧 `/api/orders/list/refund` 和市场退款入口保持兼容，并能解析 checkout 关联。

## 5. 用户优惠券

### 5.1 列表

`GET /api/coupons/mine?status=AVAILABLE&limit=50&cursor=`

每项包含 `id, serialNo, templateCode, ownerType, ownerDisplayName, title, benefitSummary, scopeSummary, stackingSummary, validFrom, validUntil, status, primaryReason, actionTarget`。

### 5.2 领取/绑定

- `POST /api/coupons/claim`：`templateId, idempotencyKey`
- `POST /api/coupons/bind-code`：`code, idempotencyKey`

码错误统一 `INVALID_OR_UNAVAILABLE_CODE`，避免区分不存在/已耗尽/归属限制导致枚举。

### 5.3 商品优惠

`GET /api/promotions/for-source?sourceType=&sourceId=&quantity=` 返回公开活动、可领取券和当前用户预计价格摘要。不能代替 checkout quote。

## 6. 会员 API

- `GET /api/membership/plans`：可见计划、等级、购买商品、兑换支持和权益摘要。
- `GET /api/membership/me`：当前/未来/过期 membership 和 entitlement。
- `POST /api/membership/redeem`：`code, idempotencyKey`。
- 购买不设独立支付 API：会员商品进入官方购物车，由 checkout 购买。

`membership/me` 返回来源、开始/到期、等级、权益及下一次周期券时间；不泄露管理员备注。

## 7. 回收与收购报价扩展

现有官方回收和市场 `sell-to-buy`/inventory fulfill 报价响应增加：

- `basePayout`
- `platformBonus`
- `requesterBonus`
- `feeAmount`
- `userReceives`
- `appliedBenefits/unavailableBenefits`
- `quoteId/expiresAt`

提交接受 `quoteId + idempotencyKey`，价格变化不静默。

## 8. 充值优惠 API

### 8.1 `POST /api/recharge/quote`

输入：目标基础到账币或法币金额、法币币种、支付方法/代码、可选券。输出：

- `quoteId/expiresAt`
- `fiatBaseAmountMinor`
- `fiatDiscountAmountMinor`
- `fiatPayableAmountMinor`
- `baseCoinAmount`
- `bonusCoinAmount`
- `creditedCoinAmount`
- 优惠、返还/退款提示。

### 8.2 扩展 `/api/recharge/create`

新前端提交 `quoteId`。服务端不接受客户端自行声明折后金额或赠币。旧请求兼容路径只产生无优惠报价。

充值 status 响应增加冻结价格明细和奖励处理状态。

## 9. 玩家卖家促销 API

所有 `/api/seller/promotions/*` 使用普通用户认证并在服务端检查 seller ownership。

- `GET /api/seller/promotions/list`
- `GET /api/seller/promotions/meta`：平台允许类型、上限和卖家当前资格。
- `POST /api/seller/promotions/simulate`
- `POST /api/seller/promotions/create`
- `POST /api/seller/promotions/update-draft`
- `POST /api/seller/promotions/publish`
- `POST /api/seller/promotions/pause`
- `POST /api/seller/promotions/resume`
- `POST /api/seller/promotions/end`
- `GET /api/seller/promotions/report`
- `POST /api/seller/coupons/grant`（若平台策略允许定向发放）

创建请求使用模板代码：

```json
{
  "template": "SELLER_AMOUNT_OFF",
  "name": "...",
  "listingIds": [91, 92],
  "startAt": "...",
  "endAt": "...",
  "thresholdAmount": 100,
  "discountAmount": 10,
  "budgetAmount": 500,
  "perUserUseLimit": 1,
  "expectedPolicyVersion": 3,
  "idempotencyKey": "uuid"
}
```

响应同时返回 `buyerExample` 和 `sellerCostExample`。

## 10. 管理 API：活动与规则

需要 `PROMOTION_VIEW/MANAGE`：

- `GET /api/admin/promotions/list`
- `GET /api/admin/promotions/detail?id=`
- `GET /api/admin/promotions/meta`：枚举、业务域、槽位、模板、限制。
- `POST /api/admin/promotions/create`
- `POST /api/admin/promotions/update-draft`
- `POST /api/admin/promotions/simulate`
- `POST /api/admin/promotions/publish`
- `POST /api/admin/promotions/pause|resume|end`
- `GET /api/admin/promotions/report`

`publish` 接受 `campaignId, expectedVersion, idempotencyKey`；返回不可变 `ruleVersionId/rulesHash`。已发布规则编辑返回 `PUBLISHED_VERSION_IMMUTABLE` 并提示创建修订。

## 11. 管理 API：集合、券、人群

- 集合：`/api/admin/promotion-collections/list|upsert|delete|members`
- 券模板：`/api/admin/coupon-templates/list|create|update-draft|publish|pause`
- 券码：`/api/admin/coupon-codes/create|list|revoke`，创建明文只返回一次。
- 发券：`/api/admin/coupons/grant`
- 批量：`/api/admin/coupons/batch/preview` 与 `/commit`，commit 引用不可篡改 preview token/hash。
- entitlement：`/api/admin/entitlements/list|grant|revoke`
- 卖家限制：`/api/admin/seller-promotions/policy|get|update|pause-all`

批量预览返回目标数、重复数、不存在数、预计发行库存/预算影响；commit 使用 idempotencyKey。

## 12. 管理 API：会员

需要 `MEMBERSHIP_VIEW/MANAGE/GRANT`：

- `/api/admin/membership/plans/list|detail|create|update-draft|publish|pause`
- `/api/admin/membership/benefits/simulate`
- `/api/admin/membership/users/list`
- `/api/admin/membership/grant`
- `/api/admin/membership/renew`
- `/api/admin/membership/upgrade`
- `/api/admin/membership/revoke`
- `/api/admin/membership/codes/create|list|revoke`
- `/api/admin/membership/report`

手工变更请求必须含 `reason`；响应返回 membership 和审计 ID。

## 13. 管理 API：资金与审计

- `GET /api/admin/promotion-finance/accounts`
- `GET /api/admin/promotion-finance/ledger`
- `GET /api/admin/promotion-finance/reconciliation?checkoutNo=`
- `POST /api/admin/promotions/emergency-stop`

资金详情需要 `PROMOTION_FINANCE_VIEW`；紧急停止需要对应权限或超级管理员。

## 14. 模拟 API

平台模拟可以指定用户 ID/时间/来源，但必须：

- 无任何核销、预留、钱包、库存、会员或日志副作用（管理审计“运行模拟”可有）；
- 明确标识 `simulation=true`；
- 使用与生产相同纯计算引擎；
- 返回资金守恒检查；
- 不允许模拟另一个用户后拿结果直接提交订单。

## 15. HTTP 状态

| 状态 | 用途 |
|---|---|
| 200/201 | 查询/成功创建 |
| 400 | 结构、枚举、金额或业务规则输入非法 |
| 401 | 未登录 |
| 403 | 权限/归属不足 |
| 404 | 对当前主体不可见资源 |
| 409 | 版本、幂等、报价、库存、预算、状态冲突 |
| 422 | 可理解但不满足业务条件（项目若现有统一用 400，可保持并记录） |
| 429 | 限速 |
| 500/503 | 内部错误/暂不可计算；不得部分扣款 |

## 16. 兼容与版本

- 现有 API 保留并内部调用新核心。
- 新响应可以添加字段；不能改变旧字段含义而不迁移前端。
- 对 breaking change 使用明确 API capability/version 元数据，而不是根据字段猜测。
- `/api/meta/version` 增加 `commerceCapabilities`，包括 `cart`, `checkoutV2`, `promotions`, `memberships`, `sellerPromotions`, `pricingSnapshotV2`。
