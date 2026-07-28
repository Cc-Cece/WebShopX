# Claim、Mailbox 与退款策略统一设计

> 实现状态：已完成（2026-07-28）
>
> 实现分支：`feat/claim-mailbox-refund-policy`

已落地范围包括统一 Mailbox 聚合与 Web 页面、`/ws mailbox` 入口、`/ws claim`
兼容别名、平台托管边界、全局及商品级退款策略、可选退款期限、订单策略快照、
部分退款、动态价格退款反向事件、退款幂等与崩溃对账，以及管理员系统配置页面。
旧 Claim Token 仅作为兼容数据保留，不再出现在新的待领取通知主流程中。

> 状态：设计结论稿  
> 适用仓库：`Cc-Cece/WebShopX`、`Cc-Cece/webshopx-vuetify-web`  
> 目标：统一 Claim 与 Mailbox 的用户心智，并重新定义交付、退款、部分退款和动态价格退款规则。

## 1. 核心结论

WebShopX 应在用户层面将 Claim 合并进 Mailbox：

- 用户只需要理解“立即发放”和“稍后从 Mailbox 领取”。
- `/ws mailbox` 成为统一的待领取入口。
- `/ws claim` 暂时保留为兼容别名，并在后续版本逐步废弃。
- 旧 `WAIT_CLAIM`、命令型待领取、效果型待领取和实体物品 Mailbox 由统一的 Mailbox 聚合层展示。
- Web 页面新增“我的邮箱 / Mailbox”。

最重要的业务原则是：

> Mailbox 是 WebShopX 的平台托管区，而不是最终交付区。

因此，进入 Mailbox 本身不表示商品已经最终发货，也不应自动失去退款资格。

## 2. 资产控制权与最终交付

### 2.1 平台托管状态

以下状态属于 `PLATFORM_CUSTODY`：

- `PENDING`
- `MAILBOX_PENDING`
- `WAITING_COMMAND`
- `WAITING_EFFECT`
- 兼容期内的 `WAIT_CLAIM`

在这些状态下，资产仍完全由 WebShopX 控制。玩家尚不能使用、转移、出售或消耗该资产，系统仍可取消、回收、退款和恢复库存。

### 2.2 玩家控制状态

以下行为发生成功后，资产进入 `PLAYER_CUSTODY`：

- 实体物品实际进入玩家背包；
- 命令成功执行；
- 权限、VIP、称号等权益成功授予；
- PotionEffect 或其他效果成功应用；
- 第三方插件奖励成功发放。

此时才算最终交付：

```text
PLATFORM_CUSTODY
        ↓
PLAYER_CUSTODY
```

普通玩家自助退款原则上不得突破该边界。已经交付的资产如需给玩家退款或补偿，应使用管理员人工补偿流程，而不是普通退款。

## 3. Claim 与 Mailbox 的统一方式

### 3.1 用户层

旧用户心智：

```text
Immediate
Claim
Mailbox
```

新用户心智：

```text
立即发放
稍后从 Mailbox 领取
```

建议调整：

- 商城购买选项由“即时到账 / 手动领取”改为“立即发放 / 存入邮箱”或“立即领取 / 稍后领取”。
- `/ws mailbox` 打开统一 Mailbox GUI。
- `/ws mailbox collect` 执行领取。
- `/ws claim` 在兼容期内转发到统一领取服务。
- Claim Token 逐步降级为旧数据和旧命令兼容机制。

### 3.2 后端层

不应把所有待领取内容简单塞入当前只支持 `ItemStack` 的 `mailbox_items`。

建议新增统一聚合层：

```text
MailboxCenterService / MailboxFacade
    ├── mailbox_items
    ├── delivery_queue WAIT_CLAIM
    ├── command delivery
    ├── effect delivery
    ├── market item delivery
    └── legacy claim data
```

统一返回类似以下 DTO：

```text
MailboxEntry
- id
- type
- sourceType
- sourceRef
- title
- icon / material
- quantity
- deliveredQuantity
- refundableQuantity
- status
- reason
- createdAt
- collectible
- refundable
- refundReason
- refundDeadline
```

前端不需要知道数据来自哪张表。

## 4. 退款总原则

最终退款资格应按以下条件判断：

```text
是否可退款
=
资产仍未最终交付
AND
退款政策允许
AND
仍存在可退款数量
AND
（未设置退款期限 OR 当前仍在退款期限内）
```

更具体地说：

```text
PLATFORM_CUSTODY
+
POLICY_ALLOWED
+
REFUNDABLE_QUANTITY > 0
+
TIME_ALLOWED
```

退款边界由资产控制权决定，而不是由 Claim、Mailbox 或页面名称决定。

## 5. Mailbox 未领取商品退款

保留“Mailbox 未领取商品允许退款”这一策略。

推荐默认值：

```text
refundMailboxPendingEnabled = true
```

该设置表示 Mailbox 中尚未领取的资产可以参与退款判断，但并不表示一定可退。最终仍需同时满足：

- 商品退款政策允许；
- 时间条件满足；
- 存在未交付数量；
- 如已部分交付，则部分退款策略允许。

管理员也可以关闭 Mailbox 未领取退款。此时 Mailbox 仍然属于平台托管，但服务器运营政策不允许玩家自助退款。

## 6. 退款期限为可选项

退款期限不是强制条件。

### 6.1 有期限

例如固定价格商品设置为 10 分钟：

```text
Mailbox 未领取 + 5 分钟  → 可退款
Mailbox 未领取 + 20 分钟 → 已过期，不可退款
Mailbox 已领取 + 1 分钟  → 已交付，不可退款
```

### 6.2 无期限

当退款期限为空或为 `null` 时，表示不限制时间：

```text
refundWindowMinutes = null
```

此时，只要资产仍在平台托管且其他政策允许，无论经过多久都可以退款。

需要明确：

> 无期限只表示没有时间截止点，不表示最终交付后仍可退款。

### 6.3 前端设置方式

后台不建议只依赖空输入框表达语义，推荐使用：

```text
退款期限
[ 10 ] 分钟
☐ 不限制退款时间
```

勾选“不限制退款时间”后禁用数字输入框，后端保存 `null`。

## 7. 推荐默认值

建议提供安全默认值，但允许管理员修改：

```text
玩家自助退款：开启
Mailbox 未领取商品允许退款：开启
固定价格商品退款期限：10 分钟
动态价格商品退款期限：3 分钟
全局部分退款：开启
每位玩家每日自助退款次数：5
商品级退款策略：INHERIT
商品级部分退款策略：INHERIT
```

固定价格和动态价格退款期限都允许设置为无限制。

这些数值是默认运营策略，不应写死为不可修改的底层规则。

## 8. 动态价格商品退款

### 8.1 默认更短的原因

动态价格商品允许较长时间退款时，玩家可能：

```text
买入
↓
观察后续价格
↓
价格上涨 → 领取
价格下跌 → 退款
```

这相当于获得免费选择权，因此建议动态价格默认退款期限短于固定价格商品。

但 WebShopX 不应替管理员强制决定经营策略。管理员可以设置更长时间或无限期，只需在后台显示风险提示：

> 动态价格商品使用较长或无限制退款窗口，可能使玩家根据后续价格变化选择领取或退款，从而影响动态定价机制。

### 8.2 动态价格退款事件

退款不能直接把价格恢复到购买前，因为期间可能发生其他交易。

正确模型是追加反向事件：

```text
BUY +10
BUY +5
REFUND -4
BUY +2
```

动态定价系统应按实际退款数量产生 `REFUND` 事件，以逆转该笔购买带来的需求压力。

如果只恢复库存和钱包，却不修正动态需求数据，玩家可能通过大量购买再退款来人为推高价格。

### 8.3 退款金额

退款金额必须基于订单实际成交金额，而不是退款时的当前价格。

对于按边际价格逐件变化的订单，第一版推荐按订单实际支付金额比例计算：

```text
可退款金额
=
订单项实际支付金额
×
可退款数量 / 总购买数量
```

最后一次退款吸收整数舍入误差。

未来如需严格逐件追踪边际成交价，可再增加价格段表，但不作为第一版必要条件。

## 9. 退款策略快照

管理员修改退款配置后，不应改变已经成交订单的退款承诺。

例如：

```text
12:00 下单，当时退款期限为 10 分钟
→ refund_deadline = 12:10

12:02 管理员把全局期限改成 2 分钟
→ 旧订单仍然在 12:10 到期
```

反向修改也一样，旧订单不会自动延长。

因此：

> 全局退款策略只是创建新订单时的模板，订单创建后应冻结退款结果。

第一版订单至少应保存：

```text
refund_allowed
refund_deadline
```

语义如下：

```text
refund_allowed = true
refund_deadline = NULL
→ 允许退款，且时间无限制

refund_allowed = true
refund_deadline = timestamp
→ 允许退款，且有截止时间

refund_allowed = false
refund_deadline = NULL
→ 不允许退款
```

`refund_deadline = NULL` 不得同时表示“无限制”和“禁止退款”，必须由独立字段或明确的策略快照区分。

## 10. 全局退款策略

建议新增独立的运行时配置键：

```text
refund_policy
```

不要继续把所有退款字段塞进 `webshop_runtime`。

示例：

```json
{
  "selfServiceEnabled": true,
  "mailboxPendingRefundEnabled": true,
  "fixedPriceWindowMinutes": 10,
  "dynamicPriceWindowMinutes": 3,
  "partialRefundEnabled": true,
  "maxSelfServiceRefundsPerDay": 5
}
```

其中两个时间字段允许为 `null`，表示无限制。

建议后台位于商业管理或订单管理下，而不是混入普通技术运行参数。

## 11. 商品级退款策略

单个商品应允许覆盖全局退款策略。

### 11.1 商品退款策略

```text
INHERIT
CUSTOM
DISABLED
```

含义：

- `INHERIT`：继承全局策略；
- `CUSTOM`：使用商品自己的退款期限；
- `DISABLED`：禁止玩家自助退款。

后台表现：

```text
退款策略
● 继承全局
○ 自定义
○ 禁止玩家自助退款
```

当选择 `CUSTOM` 时，管理员可以填写分钟数或选择“不限制退款时间”。

### 11.2 商品级部分退款策略

建议加入，并使用三态而不是布尔值：

```text
INHERIT
ALLOW
DENY
```

后台表现：

```text
部分退款
● 继承全局
○ 允许
○ 禁止
```

最终判断：

```text
商品 = ALLOW   → 允许部分退款
商品 = DENY    → 禁止部分退款
商品 = INHERIT → 使用全局设置
```

## 12. 部分退款定义

### 12.1 可拆分商品

例如：

```text
Diamond ×64
```

玩家领取 20 件，Mailbox 剩余 44 件：

```text
已交付 20 → 不可退款
未交付 44 → 可按策略退款
```

### 12.2 不可拆分商品

例如：

- VIP 30 天；
- 称号；
- 权限组；
- 一次性解锁；
- 复合礼包；
- 不能可靠拆分和回收的命令型权益。

这类商品通常应禁止部分退款。

### 12.3 禁止部分退款的准确语义

例如：

```text
Diamond ×64
partialRefundPolicy = DENY
```

玩家领取 1 件后，虽然 Mailbox 仍剩 63 件，但玩家不能对剩余 63 件发起自助退款。

因此 `DENY` 的含义是：

> 一旦该订单项任何部分完成最终交付，剩余未交付部分也不再支持玩家自助退款。

它不是简单地禁止玩家选择退款数量。

### 12.4 商品类型默认建议

可根据商品类型提供安全默认值：

| 商品类型 | 推荐默认部分退款 |
|---|---|
| 普通 `GIVE_ITEM` | 允许 |
| 可逐件追踪的自定义物品 | 允许 |
| 多数量兑换券 | 允许 |
| `COMMAND` | 禁止 |
| `POTION_EFFECT` | 禁止 |
| VIP、权限、称号 | 禁止 |
| 复合礼包 | 禁止 |

商品级默认仍建议保存为 `INHERIT`，由全局和商品类型默认逻辑共同解析。

## 13. 部分退款结算

部分退款时应按未交付数量处理：

```text
refundQuantity = totalQuantity - deliveredQuantity - alreadyRefundedQuantity
```

退款事务应同步完成：

- 取消对应 Mailbox 剩余资产；
- 返还实际退款金额；
- 恢复商品库存；
- 恢复个人购买额度；
- 追加动态价格 `REFUND` 事件；
- 更新订单项和订单退款状态；
- 记录审计日志。

## 14. 退款事务与并发安全

退款必须在事务中完成：

```text
LOCK
↓
重新验证退款资格
↓
取消 Mailbox / 待领取资产
↓
计算退款数量和退款金额
↓
返还钱包
↓
恢复库存
↓
恢复个人限购额度
↓
写入动态价格 REFUND 事件
↓
更新退款状态
↓
COMMIT
```

任何一步失败都必须 `ROLLBACK`。

需要防止以下竞态：

- 玩家同时点击领取和退款；
- 网页和游戏端同时领取；
- 同一退款请求重复提交；
- Mailbox 写入成功但源发货任务状态更新失败；
- 退款成功但 Mailbox 资产未取消。

建议：

- 使用订单项或发货任务级锁；
- 所有退款接口支持幂等键；
- Mailbox 条目增加稳定的源任务标识；
- 建立 `UNIQUE(source_type, source_delivery_id)`；
- 将“写入 Mailbox”和“更新源任务状态”尽可能放入同一事务。

## 15. 推荐退款资格检查顺序

后端收到退款请求后应重新检查：

1. 当前用户是否拥有该订单；
2. 订单是否已取消、已退款或正在退款；
3. 全局自助退款是否开启；
4. Mailbox 未领取退款是否开启；
5. 商品退款策略是否允许；
6. 是否仍存在平台托管资产；
7. `refund_deadline` 是否已过期；若为 `null` 则跳过；
8. 是否存在可退款数量；
9. 如已部分交付，部分退款策略是否允许；
10. 是否存在并发领取或退款操作；
11. 锁定记录并在事务中执行退款。

建议返回明确错误码：

```text
REFUND_DISABLED
PRODUCT_NOT_REFUNDABLE
MAILBOX_REFUND_DISABLED
REFUND_EXPIRED
ALREADY_DELIVERED
PARTIAL_REFUND_NOT_ALLOWED
NO_REFUNDABLE_QUANTITY
REFUND_IN_PROGRESS
ALREADY_REFUNDED
```

## 16. 状态模型建议

长期建议拆分订单状态、交付状态和退款状态，不再让 `orders.status` 承担所有语义。

### 16.1 Order Status

```text
PAID
PARTIALLY_REFUNDED
REFUNDED
CANCELLED
```

### 16.2 Delivery Status

```text
PENDING
MAILBOX
PARTIAL
DELIVERED
FAILED
CANCELLED
```

### 16.3 Refund Eligibility

可动态计算：

```text
AVAILABLE
PARTIAL
EXPIRED
DISABLED
DELIVERED
REFUNDED
```

示例：

```text
Order: PAID
Delivery: MAILBOX
Refund: AVAILABLE
```

退款时间到期后可以变为：

```text
Order: PAID
Delivery: MAILBOX
Refund: EXPIRED
```

商品仍可领取，但不再可退款，二者不冲突。

## 17. 管理员退款与补偿

应区分：

### 17.1 Admin Refund

用于资产仍可完整回收的情况：

```text
撤销资产
+
退款
+
恢复库存和额度
```

### 17.2 Admin Compensation

用于商品已经交付，但运营决定补偿玩家的情况。

例如返还 ShopCoin，应记录为：

```text
ADMIN_COMPENSATION
```

而不是伪装成普通 `REFUND`，避免账务和审计语义失真。

## 18. Web 页面设计

建议新增：

```text
/mailbox
```

用户菜单建议包含：

```text
我的订单
我的邮箱
我的上架
```

Mailbox 条目至少展示：

- 商品或权益名称；
- 数量；
- 来源类型；
- 订单号或市场引用；
- 创建时间；
- 当前交付状态；
- 可领取数量；
- 可退款数量；
- 退款截止时间或“无限制”；
- 不可退款原因。

示例：

```text
Diamond ×64
状态：待领取
退款：可退款 · 剩余 08:42
```

```text
Diamond ×64
状态：待领取
退款：退款期限已结束
```

```text
礼包 ×1
状态：部分领取
退款：该商品不支持部分退款
```

第一阶段 Web Mailbox 建议支持：

- 列表查看；
- 未领取数量和状态展示；
- 退款资格展示；
- 发起退款。

第一阶段不建议立即加入网页直接领取。当前实际领取依赖 Bukkit `Player` 和在线背包写入，虽然项目已有离线 playerdata 能力，但不应在第一次 Mailbox 重构中同时耦合两个高风险系统。

## 19. 推荐 API

第一阶段建议：

```text
GET  /api/mailbox/list
GET  /api/mailbox/count
POST /api/mailbox/{entryId}/refund
```

退款接口必须：

- 重新计算资格；
- 支持幂等；
- 返回明确状态和错误码；
- 不信任前端提交的退款金额、退款数量或资格状态。

后续如实现 Web 领取，再单独设计领取接口和离线写入安全策略。

## 20. 兼容与迁移

建议分阶段实施。

### 阶段 1：建立统一语义

- 增加 Mailbox 聚合查询；
- Web 增加 Mailbox 页面；
- Claim 继续可用；
- 新增退款策略和商品级策略；
- 明确 Mailbox 不等于最终交付。

### 阶段 2：统一入口

- 商城购买选项改为“立即发放 / 存入邮箱”；
- `/ws claim` 转发到 Mailbox；
- 订单页不再把 Claim Token 作为主交互；
- 通知文案统一为 Mailbox 待领取。

### 阶段 3：清理旧机制

- 迁移旧 `WAIT_CLAIM` 用户体验；
- 逐步移除 Claim Token 暴露；
- 评估是否保留共享 Claim 功能；
- 如需赠送，应设计独立 Gift 功能，而不是继续依赖可分享 Claim Token。

## 21. 最终业务定义

> WebShopX 将 Mailbox 视为平台托管区，而不是最终交付区。尚未实际交付给玩家的 Mailbox 内容默认允许退款；管理员可以分别为固定价格和动态价格商品设置退款期限，期限为空时表示不限制时间，并可通过全局策略和商品级策略控制是否允许退款及部分退款。一旦商品、命令、权限或效果实际进入玩家控制范围，普通退款资格终止。

最终设计原则：

> 退款的核心边界是资产控制权，而不是 Claim、Mailbox 或页面状态。
