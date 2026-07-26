# V3：离线上架实施计划书

> 目标分支：`Cc-Cece/WebShopX: feat/webshopx-inventory-management`
>
> 本文件是独立的 V3 计划书，不修改现有 `docs/official-shop-from-inventory-codex-plan.md`。
>
> **执行顺序以本文件为准：从现在起，“管理员离线时从背包创建官方商城商品”统一视为 V3。旧计划书中若曾把离线 Snapshot 捕获列入 V2，只视为早期规划，不作为后续实现阶段的最终排序。**

---

# 1. V3 目标

在 V1/V2 已稳定的前提下，让管理员即使没有进入 Minecraft 服务器，也能在 Web 端基于其真实离线 `playerdata` 背包内容创建官方商城商品。

V3 的核心体验：

```text
管理员离线
    ↓
Web 打开“我的背包”
    ↓
读取真实 playerdata Snapshot
    ↓
选择物品
    ↓
默认交易操作 → 加入官方商城
    ↓
服务端再次校验离线 revision / slot / fingerprint
    ↓
生成不可变 ItemStack Snapshot
    ↓
创建官方商城商品
```

V3 不新建第二套背包页面，继续复用现有“我的背包”UI 和现有管理员“加入官方商城”入口。

---

# 2. V3 分为两个层级

## 2.1 V3-A：离线模板上架（V3 必做）

这是 V3 的首要功能，也是推荐优先完成的离线上架模式。

语义与 V1 在线模板模式完全一致：

> **只复制物品作为官方商品模板，不从管理员背包中扣除物品。**

因此该流程对 `playerdata` 应保持 **只读**：

```text
offline playerdata
    ↓ read only
resolve exact ItemStack
    ↓
ItemSnapshotCodec.serialize(...)
    ↓
Official SNAPSHOT_ITEM Product
```

不得为了模板上架去写回 `.dat` 文件。

### V3-A 的价值

- 管理员不需要为了上架一个附魔物品专门登录游戏；
- 非常适合官方商城长期运营；
- 相比离线扣物，风险明显更低；
- 当前 `feat/webshopx-inventory-management` 已有离线 playerdata 读取、revision、fingerprint、UUID lock 等基础，可直接复用。

---

## 2.2 V3-B：离线实体入库（可选扩展）

只有当 V2 已经正式完成“实体库存 / 官方仓库 / 补货 / 退库”体系后，才考虑 V3-B。

语义：

```text
管理员离线
    ↓
选择 playerdata 中物品 x32
    ↓
真正从离线 playerdata withdraw 32
    ↓
安全写回
    ↓
进入官方实体库存
    ↓
stock = 32
```

这一部分风险和复杂度远高于 V3-A，必须复用并严格遵守现有：

- `PlayerDataInventoryService.withdraw(...)`；
- UUID 级锁；
- 登录闸门；
- 写入前后在线状态复查；
- temp write + verify；
- backup；
- atomic replace；
- rollback；
- recovery journal；
- idempotency。

**V3-A 完成不依赖 V3-B。V3-B 不应阻塞离线模板上架发布。**

---

# 3. V3-A 正式后端设计

## 3.1 为 PlayerDataInventoryService 增加只读 resolve

推荐增加类似：

```java
offlineResolve(...)
```

或与当前代码风格一致的只读方法。

职责：

```text
1. 获取 UUID lock
2. 确认玩家当前离线
3. 读取 world/playerdata/<uuid>.dat
4. 生成当前离线 inventory revision
5. 校验 expected revision
6. 校验 slot / containerSlot
7. 校验 fingerprint
8. 转换为 Bukkit ItemStack
9. 验证 ItemSnapshotCodec 可正常 serialize
10. 再次确认玩家未在过程中登录
11. 返回 ItemStack clone
12. 不写 playerdata
```

关键要求：

- 不调用 `safeWrite()`；
- 不创建 playerdata recovery journal；
- 不修改 Count / Slot / NBT；
- 不因模板上架改变管理员实际背包。

---

## 3.2 不信任前端“离线状态”

前端可以展示：

```text
玩家离线 / PLAYERDATA Snapshot
```

但后端不能根据浏览器传来的：

```text
offline=true
snapshotSource=PLAYERDATA
```

直接决定读取离线文件。

后端必须根据服务器实时状态自行判断：

```text
玩家在线 → live InventoryService.resolve(...)
玩家离线 → PlayerDataInventoryService.offlineResolve(...)
```

或者使用独立的离线接口，但同样必须再次检查真实在线状态。

---

## 3.3 接口建议

优先继续复用正式 V1 的管理员接口：

```http
POST /api/admin/products/from-inventory
```

请求仍只允许：

```text
inventory
slot
containerSlot
revision
fingerprint
+ 官方商品字段
```

浏览器依旧不得上传：

```text
rawItemBlob
NBT
itemMetaJson
itemHash（作为权威值）
```

服务端逻辑从 V1 的：

```text
必须在线 → live resolve
```

升级为：

```text
在线 → live resolve
离线 → offline resolve
```

保证前端无需维护两套“加入官方商城”业务流程。

---

# 4. Feature Switch 与权限

尽管 V3-A 是只读操作，仍建议增加独立开关，不要默认随着普通离线背包能力自动开启。

建议配置概念：

```text
offlineOfficialShopCaptureEnabled = false
```

默认关闭，由服务器管理员主动启用。

权限建议：

基础权限仍要求：

```text
PRODUCT_MANAGE
```

并推荐增加更窄的离线能力权限，例如：

```text
PRODUCT_OFFLINE_INVENTORY_IMPORT
```

最终权限结构建议：

```text
在线加入官方商城：PRODUCT_MANAGE
离线加入官方商城：PRODUCT_MANAGE + PRODUCT_OFFLINE_INVENTORY_IMPORT
```

这样服务器可以允许某些商品管理员在线导入，但不授予其读取离线 playerdata 的能力。

---

# 5. 登录竞争与一致性

离线上架最大的特殊风险不是写文件，而是：

> 管理员在 Web 操作过程中突然登录 Minecraft。

因此即使 V3-A 只读，也必须防止“基于已经过期的离线文件创建商品”。

推荐流程：

```text
UUID lock
  ↓
assertOffline
  ↓
read playerdata
  ↓
revision + fingerprint validation
  ↓
construct ItemStack
  ↓
assertOffline again
  ↓
return snapshot source item
```

如果中间检测到登录：

```text
409 player_state_changed
```

前端提示：

> 玩家状态已变化，请刷新背包后重试。

不要自动从 offline snapshot 静默切换到 live snapshot 后继续创建，因为两份 revision 的语义不同。

---

# 6. 前端 UX

继续使用当前真实“我的背包”页面。

## 在线管理员

行为保持 V1：

```text
创建玩家市场上架
出售给收购挂单
创建拍卖
────────────
加入官方商城
```

## 离线管理员

当满足：

```text
离线真实 PLAYERDATA Snapshot 可用
+ V3 feature switch 开启
+ 拥有离线上架权限
```

同样显示：

```text
加入官方商城
```

点击后仍然打开与 V1 相同的“创建官方商品”对话框。

区别仅在顶部状态提示，例如：

> 当前物品来自离线 playerdata。模板创建不会修改或扣除离线背包中的物品。

如果 V3 功能关闭或权限不足：

- 不要显示可点击入口；
- 可以通过 Tooltip / 提示解释“离线上架未启用”；
- 不影响已有离线背包查看、玩家市场离线操作等功能。

---

# 7. Snapshot 与 Mod Item 校验

V3 从 playerdata 读取的物品最终仍必须进入正式 `ItemSnapshotCodec` 流程。

建议在发布前执行：

```text
NBT/playerdata
   ↓
toItemStack
   ↓
serialize
   ↓
deserialize
   ↓
validate
```

对于 Bukkit/Paper/Folia 当前运行时无法完整恢复的特殊数据，应直接拒绝创建官方商品，而不是保存一个未来无法发货的 Snapshot。

建议错误：

```text
offline_item_not_supported
```

提示管理员：

> 该离线物品无法在当前服务器运行时完整恢复，暂不允许加入官方商城。请登录服务器后再次尝试，或检查对应 Mod/插件兼容性。

不要承诺所有 Mod / 混合端物品均可离线上架。

---

# 8. 审计要求

所有 V3 离线上架操作必须记录管理员审计。

至少记录：

```text
admin user id
admin username
bound Minecraft UUID
operation = OFFLINE_PRODUCT_IMPORT
inventory source
slot
containerSlot
item material
item hash
product SKU
product id
source = PLAYERDATA
created_at
client IP
```

V3-B 如果未来实施实体扣除，还必须额外记录：

```text
withdraw quantity
before revision
after revision
backup/recovery reference
rollback status
```

---

# 9. V3-A 测试清单

至少覆盖：

1. 管理员离线 + feature enabled + 权限正确 → 可创建 Snapshot 商品；
2. 创建后离线 playerdata 完全不发生变化；
3. revision 过期 → 拒绝；
4. fingerprint 不匹配 → 拒绝；
5. slot 已变化 → 拒绝；
6. 操作过程中管理员登录 → 拒绝并要求刷新；
7. 管理员已经在线 → 自动走 live resolve，而不是读取 playerdata；
8. feature switch 关闭 → 离线上架不可用；
9. 只有 PRODUCT_MANAGE、没有 offline import 权限 → 拒绝；
10. 普通玩家 → 拒绝；
11. Enchantment / Lore / CustomModelData 离线 round-trip；
12. 无法转换的特殊 Item/NBT → 明确拒绝；
13. 相同 Snapshot hash 去重正常；
14. 离线创建的商品购买后发货与在线创建完全一致；
15. 老订单仍冻结购买时 Snapshot；
16. Mailbox fallback 正常；
17. Paper / Folia 的玩家状态判断和线程模型正确。

真实 E2E 至少测试：

```text
管理员退出服务器
→ Web 刷新“我的背包”
→ 确认 PLAYERDATA Snapshot
→ 选择附魔/自定义物品
→ 加入官方商城
→ 确认管理员 playerdata 未变化
→ 另一个玩家购买
→ 实际收到完全一致的 ItemStack
```

再测试竞争场景：

```text
Web 已打开离线背包
→ 管理员此时登录游戏
→ Web 使用旧 revision 点击加入官方商城
→ 服务端必须拒绝
→ 刷新后改走在线 Snapshot
```

---

# 10. V3-B 额外要求（仅实体库存存在时）

如果 V2 已经拥有正式实体库存，V3-B 才允许实现离线实体入库。

必须把以下动作视为一个事务式流程：

```text
validate offline revision
→ offline withdraw
→ persist official stock/deposit
→ verify both sides
→ commit
```

任一步失败必须：

```text
rollback playerdata
或 rollback official stock
```

并支持插件崩溃后的 recovery。

特别禁止：

- 先扣 playerdata，再异步创建商品且没有 journal；
- 商品创建失败但不返还物品；
- 玩家登录后继续写离线 `.dat`；
- 同一 UUID 多个 Web 请求并行修改 playerdata；
- 用普通模板 Snapshot 数量冒充实体库存数量。

---

# 11. Codex 推荐实施顺序

```text
Step 1
确认 V1/V2 的正式 Snapshot Product / Delivery 已稳定

Step 2
审查 PlayerDataInventoryService 当前 read / withdraw / lock / online guard

Step 3
实现只读 offlineResolve
不得写 playerdata

Step 4
为管理员官方商品接口增加 live/offline source routing

Step 5
增加 V3 feature switch + offline import permission

Step 6
接入现有“我的背包”管理员操作入口
不得新建页面

Step 7
增加 audit

Step 8
增加 unit/integration tests
重点测试 login race

Step 9
Paper / Folia 实机 E2E

Step 10
V3-A 验收

Step 11（可选）
若实体库存体系已存在，再设计 V3-B offline physical deposit
```

---

# 12. V3 完成标准

## V3-A 完成

- [ ] 管理员离线时能看到真实 playerdata 背包；
- [ ] 可从现有背包交易菜单“加入官方商城”；
- [ ] 不新建第二套 inventory 页面；
- [ ] playerdata 全程只读；
- [ ] 创建商品不扣源物品；
- [ ] revision / slot / fingerprint 全部服务端验证；
- [ ] 登录竞争被正确拒绝；
- [ ] 浏览器不能伪造 ItemStack；
- [ ] 特殊物品进行 round-trip validation；
- [ ] 离线创建后的购买/发货与在线 Snapshot 商品完全一致；
- [ ] feature switch、权限、audit 完整；
- [ ] Paper/Folia E2E 通过。

## V3-B 完成（可选）

- [ ] 支持离线实体物品真正入库；
- [ ] playerdata 安全写入；
- [ ] UUID lock / login gate 完整；
- [ ] rollback / recovery journal 完整；
- [ ] 商品失败时不会吞物；
- [ ] 崩溃恢复验证通过；
- [ ] 并发与登录竞争测试通过。

---

# 13. 最终阶段定位

后续功能阶段建议固定为：

```text
V1
在线管理员：模板 Snapshot 上架

V2
官方商城 Snapshot / Fulfillment / 实体库存等正式架构增强

V3
离线上架
├─ V3-A：离线模板 Snapshot 上架（必做、只读）
└─ V3-B：离线实体入库（可选，依赖 V2 实体库存）
```

V3 的首要原则：

> **先把“离线读取并复制模板”做好，再考虑“离线扣物并入库”。不要因为已有 offline playerdata 写能力，就把高风险写入提前混入 V3-A。**
