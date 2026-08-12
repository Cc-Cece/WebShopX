# 状态机、端到端算例与追踪矩阵

## 1. 目的

本文消除仅靠文字容易产生的歧义。实现者应把状态转换和算例直接转换为自动测试。示例金额均为最小整数单位。

## 2. 状态机

### 2.1 活动

```text
DRAFT ──publish future──> SCHEDULED ──time──> ACTIVE ──time──> ENDED
  │                           │                  │
  ├──end────────────────────> TERMINATED <──────┤
  └──publish now────────────> ACTIVE <──resume── PAUSED
                                          │
                                          └──end──> TERMINATED
```

允许：草稿编辑；发布后建新版本；ACTIVE/SCHEDULED 暂停；PAUSED 恢复或结束。禁止物理删除已被引用版本。

### 2.2 用户券

```text
AVAILABLE ──reserve──> RESERVED ──checkout/payment success──> CONSUMED
    │                      │
    ├──expire──> EXPIRED   ├──release/fail──> RELEASED ──reactivate──> AVAILABLE
    └──revoke──> REVOKED   └──timeout───────> AVAILABLE (if still valid)

CONSUMED ──eligible return──> RETURNED ──reactivate──> AVAILABLE/EXPIRED
CONSUMED ──not returnable───> CLOSED
```

`RELEASED/RETURNED` 可作为事件而实例回到 AVAILABLE；若选择把它们作为持久状态，需要原子后续转换。API 状态必须稳定。

### 2.3 报价

```text
ACTIVE ──submit success──> CONSUMED
   ├──time──> EXPIRED
   ├──cart/rule/resource change──> INVALIDATED
   └──user discard──> CANCELLED
```

一个报价最多关联一个 checkout。幂等重试通过 checkout 返回，而不是再次消费 quote。

### 2.4 父交易

```text
PROCESSING ──transaction commit──> PLACED
PLACED ──some delivered──> PARTIALLY_FULFILLED ──all delivered──> FULFILLED
PLACED/PARTIALLY_FULFILLED ──some refund──> PARTIALLY_REFUNDED
任何可退款状态 ──all refundable value refunded──> REFUNDED
PROCESSING ──rollback──> FAILED (可不持久化，只保留幂等失败记录)
```

父状态由组/行投影，不应由 UI 自行猜测。

### 2.5 会员

```text
PENDING ──trigger reached──> ACTIVE ──time──> EXPIRED
ACTIVE ──renew──> ACTIVE(new expiry/version event)
ACTIVE ──upgrade──> ACTIVE(new level/version event)
PENDING/ACTIVE ──refund/cancel──> CANCELLED
ACTIVE ──admin/policy──> REVOKED
REVOKED ──authorized restore──> ACTIVE (if validity allows)
```

### 2.6 后置权益

```text
PENDING ──trigger──> GRANTED ──refund policy──> REVERSED
   ├──order cancelled──> CANCELLED
   └──cannot safely grant/reverse──> REVIEW_REQUIRED
```

## 3. 算例 A：官方多商品、普通券互斥

购物车同币种：A=120，B=80。

- 平台满 200 减 20（P4 自动）；
- 普通平台券 C1：满 150 减 30，覆盖 A+B；
- 普通平台券 C2：无门槛减 25，覆盖 A；
- 普通用户，无会员槽。

计算：

1. P0=200。
2. P4 满减=20，按 120:80 分摊 12/8，当前 A=108/B=72。
3. C1/C2 都占 `PLATFORM_COUPON` 且在 A 重叠，不能共用。
4. 若 C1 门槛按 P0，它可减30；若按当前层输入 180，它也可减30。
5. C1 组合应付150；C2 组合应付155，自动选 C1。
6. 总优惠50，平台 funding=50。

预期解释：C2 可用但未选，原因 `LESS_BENEFICIAL_COMBINATION`（可新增展示原因，不是不可用）。

## 4. 算例 B：不同商品券共同使用

A=100，B=100；C-A 只覆盖 A 减10，C-B 只覆盖 B 减20，二者同槽但 `DISJOINT_SCOPE`。

结果：两券共同使用，总优惠30，应付170。若作用域后来扩大造成重叠，只影响新规则版本。

## 5. 算例 C：会员特殊叠券

商品 A=200：

- 普通平台券减30，占 `PLATFORM_COUPON`；
- 会员专享券减20，占 `MEMBER_COUPON`，需 `MEMBER_STACKING`；
- 最低实付1。

普通用户：只减30，应付170；会员券原因 `ENTITLEMENT_REQUIRED`。

有效会员：减50，应付150。会员身份不能再叠第二张普通平台券。

## 6. 算例 D：多卖家与平台补贴

卖家 S1 商品 A=100，卖家优惠10；卖家 S2 商品 B=200，无卖家优惠。平台跨卖家满300减30，门槛按 P0。

P3 后：A=90/B=200。P4 平台优惠30按规则 P0 权重100:200分摊10/20（若规则指定当前层则90:200，结果不同；必须冻结 basis）。

假设各卖家费用分别按卖家优惠后的 merchant basis 的 5%，HALF_UP：

- S1 merchantBasis=90，fee=5，买家商品实付80，sellerReceive=85，platformFunding=10；
- S2 merchantBasis=200，fee=10，买家商品实付180，sellerReceive=190，platformFunding=20；
- 用户总付260；卖家总收275；平台收到费用15并投入补贴30，净资金影响 -15（忽略税）。

守恒需由 funding ledger 表达，不能用“用户付260却卖家收275”判断错误，因为平台补贴15净流入交易。

## 7. 算例 E：共同承担

商品100，优惠20，平台/卖家各50%。

- sellerShare=10，platformShare=10；
- merchantBasis=90；
- buyerMerchandise=80；
- fee 若为 merchantBasis 的4%=4；
- sellerReceive=86；
- platformFunding=10。

奇数优惠21按 funding 最大余数/稳定规则拆分，例如平台10、卖家11（具体余数优先须在规则/算法版本固定）。

## 8. 算例 F：双币购物车

- 官方 A=100 SHOP_COIN，平台券减10；
- 市场 B=50 GAME_COIN，会员券减5；
- 用户余额分别 100 和 40。

报价：SHOP 应付90足够；GAME 应付45不足。提交整体失败，不扣 SHOP、不消费任何券。用户可取消选择 B 后重新报价结算 A。

## 9. 算例 G：市场数量并发

挂单剩余3。用户 U1 报价买2，U2 报价买2。两个报价都可生成；提交时按锁顺序：先提交者买2，后提交者复核剩1，返回 replacement/insufficient，不扣款、不用券。

## 10. 算例 H：部分退款与单位余数

3 件基础总额100，优惠1，最终99。

单位实付可分为33/33/33（基础单位可能34/33/33，优惠落在第一单位；快照保存）。交付第一件、后两件未交付，则退款66。全部三件最终退回累计99，不能用 `round(99×2/3)` 后再重复导致偏差。

## 11. 算例 I：平台券部分退款

A=100、B=100，平台券减30，分摊15/15，实付各85。只退 A：退85；平台 funding 冲回15。券默认不返。之后退 B：再退85，累计170；若策略 `FULL_REFUND_ONLY`，此时返券或发补偿，不能再退款30现金。

## 12. 算例 J：会员购买循环

用户无会员，把 30 天会员商品（100）与普通商品（100）一起结算。该会员商品交付后才授予 `MEMBER_STACKING`；本单报价不能使用新会员券。下次报价可用。

已有会员续费用户可以使用配置允许的续费优惠，因为资格在下单前存在。

## 13. 算例 K：会员退款

购买30天会员100，策略“未使用权益可撤销并退款”。授予后用户未使用任何会员价格/券，退款撤销 membership 并退100。

若用户已用会员券，策略要求人工处理：退款预览返回 `REFUND_REVIEW_REQUIRED`，不得先退100后无法撤销权益。

## 14. 算例 L：官方回收奖励

回收10件，基础每件5，总50；会员加成10%=5；平台活动每满5件额外2，重复2次=4。用户应收59，平台奖励 funding=9。实际只验收8件则必须按8件重算，不得仍付59。

## 15. 算例 M：玩家收购自费奖励

收购方发布10件、每件10，并设置每件奖励2；创建时托管至少120（另加现有费用需求）。卖家履约3件得36，托管同步减少36。奖励规则修改不得使现有托管不足；不足则停止新履约。

## 16. 算例 N：拍卖

U1 普通用户出价100，U2会员出价95。会员权益不得把95视为更高；U1胜出100。若 U1 有成交手续费减免2，则成交公开价仍100，只在最终费用/平台 funding 记录2。

## 17. 算例 O：充值

原支付10000分，基础到账1000商城币；法币券减1000分；会员赠币10%=100。

- 支付提供方订单9000分；
- 成功后基础到账1000、奖励100，合计1100；
- 账本分别记录法币补贴1000分和奖励币100；
- 创建失败释放券/法币预算；重复回调不重复到账。

## 18. 算例 P：报价变化

旧报价应付100，提交时市场价格变为110但新券让应付105。返回 replacement quote 和 `+5` 差异，不能扣105。用户再次确认才提交。

若新应付95，也返回 replacement 和 `-5`，保持用户明确确认与快照一致。

## 19. 需求追踪矩阵

| 需求 | 产品 | 计算 | 数据 | API | UI | 验收 |
|---|---|---|---|---|---|---|
| 用户自动最低价 | 03 §1/4 | 02 §13 | quote/snapshot | 07 §3 | 08 §6 | STK/OPT/QTE |
| 多商品多卖家 | 03 §3/4 | 02 §9/14 | cart/checkout | 07 §2-4 | 08 §4-6 | CRT/SET |
| 平台/卖家分账 | 03 §9 | 02 §14 | funding shares | quote/finance | seller/admin | SET/RFD |
| 特殊叠券 | 03 §6/10 | 02 §11 | entitlement | quote/membership | checkout | STK |
| 会员三种获取 | 03 §10 | 02 §10/21 | membership | 07 §6/12 | 08 §9/11 | MEM |
| 全域权益 | 03 §11-13 | 02 §4/15-17 | adapter refs | 07 §7-8 | 08 §13 | DOM/RCH |
| 最低实付/零元 | 00 §10、03 §15 | 02 §12 | rule fields | admin publish | risk UI | CAL/ADM |
| 退款退券 | 03 §17 | 02 §18-21 | units/adjustments | refund APIs | order UI | RFD |
| 权限审计 | 03 §16 | — | admin/audit | admin APIs | gate | ADM |
| 可独立执行 | README | 全文 | 06 | 07 | 08 | 05/09 |

实现过程中如新增重大需求，必须补充此矩阵，确保不是只实现某一层。
