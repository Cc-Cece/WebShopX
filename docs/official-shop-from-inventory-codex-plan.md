# “我的背包 → 官方商城”实施计划书（供 Codex 执行）

> 目标分支：`Cc-Cece/WebShopX: feat/webshopx-inventory-management`
>
> 本文件只描述后续实施方案，不包含功能代码。
>
> **重要：此前两个 `agent/official-shop-inventory-v1` 分支均视为废弃实验分支。不要 cherry-pick、不要复制其中实现、不要把其中的页面或兼容桥接逻辑当作正式方案。**

---

## 0. 项目目标

在现有“我的背包”功能基础上，允许拥有商品管理权限的管理员把自己背包中的真实 Minecraft 物品直接创建为“官方商城”商品。

目标使用场景主要是：

- 附魔物品；
- 带 Lore / 自定义名称的物品；
- CustomModelData / ItemModel 等自定义物品；
- 服务端插件能够完整表示和序列化的特殊物品；
- 在服务器运行时可被 Bukkit/Paper/Folia 正确表示的 Mod / 混合端物品。

核心原则：

> 官方商城继续保持“官方 SKU / 商品目录 / 库存 / 限购 / 定价 / 订单”的业务模型，只扩展它的“商品交付内容”能力；不要把官方商城改造成一个特殊的玩家市场账号。

---

# 1. 当前已有基础（必须优先复用）

`feat/webshopx-inventory-management` 已经具备较完整的真实背包基础设施，实施前先阅读现有代码，不要重新造一套。

重点确认并复用：

### 1.1 InventoryService

现有能力包括：

- Inventory Snapshot；
- `revision` 校验；
- slot 校验；
- item fingerprint 校验；
- `withdraw(...)`：从真实背包取出物品；
- `resolve(...)`：在不扣除物品的情况下，根据 `revision + slot + fingerprint` 读取并返回真实 `ItemStack`；
- PLAYER / ENDER_CHEST；
- Lore、附魔、自定义模型、容器内容等 ItemView 信息。

本功能 V1 **创建官方商品时应优先使用 `resolve(...)`，而不是 `withdraw(...)`**。

### 1.2 ItemSnapshotCodec

现有 `ItemSnapshotCodec` 已支持：

- Bukkit ItemStack 二进制序列化；
- `rawItemBlob`；
- `itemMetaJson`；
- SHA-256 `itemHash`；
- 反序列化恢复真实 ItemStack。

官方商城 Snapshot Item 必须直接复用这一套格式，不要另外设计第二种物品序列化协议。

### 1.3 Player Market / Mailbox 的真实物品交付

现有玩家市场已经存在真实 ItemStack 的：

- blob 存储；
- deserialize；
- 背包发放；
- 背包满时 Mailbox fallback；
- delivery retry / claim 等处理。

官方商城的 Snapshot Item 发货应抽取或复用这一层底层能力。

**不要让官方 Snapshot Item 通过 `/give material` 或普通 Material 重建。**

### 1.4 InventoryOperation / Revision / Offline PlayerData

当前分支已有：

- inventory operation 幂等记录；
- offline playerdata 读取/安全写入；
- UUID lock；
- recovery journal；
- offline inventory feature switch。

V1 的“加入官方商城”暂时不需要离线写 playerdata，但后续阶段可复用。

---

# 2. 前端 UX 的唯一正确基准

## 2.1 不允许新建第二套“我的背包”页面

当前用户实际使用的“我的背包”页面已经是完整的 Minecraft 风格背包 UI，具有：

- 背包 / 末影箱；
- 槽位 / 列表切换；
- 左侧真实 Minecraft 背包布局；
- 右侧当前选中物品详情；
- 操作数量；
- “默认交易操作”下拉菜单。

页面中当前可见操作类似：

```text
选择默认交易操作

创建玩家市场上架
出售给收购挂单
创建拍卖
```

Codex 在开始修改前，应先在前端仓库中通过这些可见文案定位**当前真正使用的 inventory 页面组件**：

- `选择默认交易操作`
- `创建玩家市场上架`
- `出售给收购挂单`
- `创建拍卖`

不要假定前端 `main` 一定是正确实现来源；先找到与当前线上/截图 UI 对应的代码，再修改。

## 2.2 正确入口位置

管理员选择物品后，现有下拉菜单应扩展为：

```text
选择默认交易操作

创建玩家市场上架
出售给收购挂单
创建拍卖
----------------
加入官方商城
```

要求：

- 普通玩家完全看不到“加入官方商城”；
- 只有拥有官方商品管理权限的管理员可见；
- 不增加新的 `/inventory` 页面；
- 不改变现有三个玩家市场操作的行为；
- 不破坏现有 Minecraft 背包布局；
- 点击后打开“创建官方商品”对话框/抽屉即可。

---

# 3. 业务语义（V1 必须固定）

## 3.1 V1 = 模板复制模式

管理员从背包创建官方商品时：

> **源物品不扣除。**

所选 ItemStack 只是官方商品模板。

例如管理员手里有一把：

```text
钻石剑
锋利 V
耐久 III
自定义 Lore
CustomModelData = 123
```

点击“加入官方商城”后，背包中的这把剑仍然存在；官方商城保存它的精确 Snapshot。

## 3.2 库存 = 逻辑库存

V1 支持：

- 无限库存；
- 有限逻辑库存。

例如库存设置为 100，表示官方商城允许售出 100 件模板副本，**不表示管理员实际存入了 100 件实体物品。**

“实体入库 / 实体补货”放在阶段二。

## 3.3 管理员 V1 必须在线

V1 创建 Snapshot 商品时要求：

- Web 账号已绑定 Minecraft UUID；
- 对应管理员玩家当前在线；
- 使用 live Inventory Snapshot；
- 通过 `revision + slot + fingerprint` 再次校验。

原因：V1 先保证功能正确和边界清晰，不把离线 playerdata 读取复杂度混入商品模板捕获。

---

# 4. 正式架构原则

## 4.1 不采用废弃实验分支中的“内部 console command 兼容桥”作为正式设计

正式实现应直接扩展官方商城 fulfillment / delivery 能力。

推荐方向：

```text
Product
  ├─ COMMAND
  ├─ GIVE_ITEM
  ├─ GIVE_CUSTOM_ITEM
  ├─ POTION_EFFECT
  └─ SNAPSHOT_ITEM   ← 新增
```

或者在现有 ProductType 结构允许的情况下，命名为：

```text
GIVE_SNAPSHOT_ITEM
```

名称以当前代码风格为准，但语义必须明确：

> 此类型的交付内容是一个不可变 ItemStack Snapshot，而不是 Material 或控制台命令。

## 4.2 Product 与 Snapshot 分离

建议新增独立 Snapshot 表，而不是把大块二进制 blob 直接塞进 `products` 主表。

建议：

```sql
official_item_snapshots
-----------------------
id
item_hash              UNIQUE
item_blob              BLOB NOT NULL
item_meta_json         TEXT NOT NULL
item_material          VARCHAR(...)
created_at
```

产品关联方式可二选一：

### 方案 A（推荐）

`products` 增加：

```text
snapshot_id nullable FK
```

### 方案 B

`products` 增加：

```text
item_hash nullable
```

如果项目当前数据库迁移体系不方便 FK，可使用 `item_hash`。

要求：

- Snapshot 内容不可原地覆盖；
- 相同 hash 可去重；
- 修改商品模板时创建/关联新的 Snapshot；
- 旧订单必须仍能拿到购买当时版本。

---

# 5. 阶段一：完成可投入测试的正式 V1

## 阶段一目标

完成：

```text
现有“我的背包”
    ↓
管理员下拉菜单：加入官方商城
    ↓
服务端重新 resolve 真实 ItemStack
    ↓
保存不可变 Snapshot
    ↓
创建 SNAPSHOT_ITEM 官方商品
    ↓
玩家在官方商城购买
    ↓
订单冻结购买时 Snapshot
    ↓
真实 ItemStack 发货 / Mailbox fallback
```

---

## 5.1 后端：管理员专用创建接口

建议新增独立接口：

```http
POST /api/admin/products/from-inventory
```

不要把该行为塞进：

```text
/api/inventory/list
```

原因：

- `/api/inventory/list` 是玩家市场“取走并上架”；
- 官方商城是管理员“读取模板并创建 SKU”；
- 两者权限、语义和事务完全不同。

### 请求只允许传可信定位信息 + 商品字段

示例：

```json
{
  "inventory": "PLAYER",
  "slot": 12,
  "containerSlot": null,
  "revision": "...",
  "fingerprint": "...",

  "sku": "",
  "title": "",
  "remark": "",
  "currency": "SHOP_COIN",
  "price": 100,
  "stockMode": "UNLIMITED",
  "stock": null,
  "perUserLimit": null,
  "active": true
}
```

### 严禁浏览器提交

```text
rawItemBlob
itemMetaJson
NBT
itemHash（作为权威来源）
```

浏览器不是物品真实性来源。

### 服务端流程

```text
1. authenticate session
2. requireAdmin(PRODUCT_MANAGE)
3. 验证管理员绑定 UUID
4. 验证管理员在线
5. InventoryService.resolve(...)
6. clone ItemStack，amount 规范化为 1
7. ItemSnapshotCodec.serialize(...)
8. 保存/复用 official_item_snapshots
9. 创建 SNAPSHOT_ITEM Product
10. 记录 admin audit
11. 返回 Product + Snapshot 摘要
```

注意：

- 如果同 SKU 已存在，明确选择“禁止覆盖”或“作为编辑模板”中的一种行为；
- 默认建议创建接口遇到重复 SKU 返回冲突，由管理后台商品编辑流程负责修改，避免无意覆盖。

---

## 5.2 后端：官方 ProductType

扩展 `ProductService.ProductType`。

需要逐项检查：

- 管理员商品参数校验；
- ProductView；
- 数据库读写；
- admin product API；
- 官方商品列表 API；
- 下单校验；
- stock / per-user-limit；
- 动态定价是否应允许。

V1 建议：

- 价格、币种、库存、限购：完全沿用普通官方商品；
- 动态定价：如果当前 GIVE_ITEM 可用，则 SNAPSHOT_ITEM 也可复用；
- Snapshot Item 本身只决定 fulfillment，不改变商品商业规则。

---

## 5.3 后端：订单必须冻结购买时 Snapshot

这是 V1 的关键一致性要求。

场景：

```text
10:00 SKU=A，对应 Snapshot V1
10:01 玩家购买
10:05 管理员把 SKU=A 编辑为 Snapshot V2
10:10 玩家领取订单
```

玩家必须拿到 **V1**。

不能在发货时重新查询：

```text
product.snapshot_id
```

而应在下单成功时就把 Snapshot 身份/内容冻结到 delivery task。

推荐：

### delivery_queue 增加

```text
payload_blob BLOB NULL
snapshot_hash VARCHAR(...) NULL
```

或者使用现有 DeliveryTaskSpec 支持不可变 `snapshotId/hash`，前提是 Snapshot 永远不删除且不会修改。

更稳妥的做法：

> 下单时直接把 `item_blob` 复制到订单 delivery payload。

这样订单完全自包含。

---

## 5.4 后端：抽取共享 ItemStack Delivery

不要复制玩家市场里的发货代码。

建议形成统一底层服务，例如：

```text
ItemStackDeliveryService
```

负责：

```text
ItemStack blob
   ↓ deserialize
ItemStack
   ↓
检查目标玩家状态
   ↓
尝试完整放入 Inventory
   ↓
成功 → delivered
失败/空间不足 → Mailbox
```

由：

- Player Market；
- Official SNAPSHOT_ITEM；
- Mailbox claim；

共同复用。

如果重构范围太大，阶段一允许先抽取最小共享方法，但不要使用 console command 绕过 DeliveryService。

---

## 5.5 后端：官方商品列表返回 Snapshot 元数据

官方商城商品接口需要让前端知道这是 Snapshot 商品，并展示其真实属性。

建议响应增加：

```json
{
  "productType": "SNAPSHOT_ITEM",
  "itemMaterial": "DIAMOND_SWORD",
  "itemHash": "...",
  "itemMetaJson": "..."
}
```

这样现有商城 ItemAttributes / Tooltip 体系可以展示：

- display name；
- lore；
- enchantments；
- stored enchantments；
- durability；
- unbreakable；
- customModelData；
- repair cost；
- 其他 ItemSnapshotCodec 已记录信息。

---

## 5.6 前端：在现有操作下拉菜单中接入

只修改当前真实 inventory 页面。

管理员看到：

```text
创建玩家市场上架
出售给收购挂单
创建拍卖
────────────
加入官方商城
```

普通玩家：

```text
创建玩家市场上架
出售给收购挂单
创建拍卖
```

### 点击“加入官方商城”后

打开商品创建对话框。

推荐字段：

```text
物品预览（只读）
商品名称
SKU（可自动生成）
备注
售价
币种
库存模式：无限 / 有限逻辑库存
库存数量（有限库存时）
个人限购
立即上架
```

对话框必须明确提示：

> 此操作会复制当前物品作为官方商城模板，不会扣除背包中的原物品。

建议自动预填：

- title：物品 display name，没有则使用本地化 material name；
- SKU：可自动生成 `inv-<short hash>`，但最终 hash 必须由后端生成；前端可留空让后端生成；
- display material：当前 material。

---

## 5.7 阶段一测试要求

### 单元 / 集成测试

至少覆盖：

1. revision 过期 → 创建失败；
2. fingerprint 不匹配 → 创建失败；
3. 普通玩家调用 admin API → 403/拒绝；
4. 无 PRODUCT_MANAGE 管理员 → 拒绝；
5. 管理员离线 → V1 拒绝；
6. 源物品创建后数量完全不变；
7. Snapshot hash 相同可安全去重；
8. 普通 Material Item round-trip；
9. 自定义名称 + Lore round-trip；
10. Enchantment round-trip；
11. CustomModelData round-trip；
12. 商品修改 Snapshot 后，旧订单仍收到旧 Snapshot；
13. 背包满 → Mailbox；
14. Mailbox claim 后 ItemStack 不丢 metadata；
15. 有限库存正确扣减；
16. per-user-limit 正常；
17. 并发购买最后库存正确；
18. 发货失败不会静默标记成功。

### 真实服务器 E2E

必须至少在当前主要运行时实际测试：

```text
管理员进入服务器
→ Web 登录
→ 我的背包
→ 选择附魔物品
→ 下拉菜单“加入官方商城”
→ 创建商品
→ 官方商城查看属性
→ 另一个玩家购买
→ 实际收到完全相同 ItemStack
```

再测试：

```text
背包满 → Mailbox → 领取
```

### Paper / Folia

如果项目同时支持 Paper 与 Folia，至少做：

- 编译；
- 插件启动；
- 玩家线程/区域线程安全；
- 实际发货。

---

## 5.8 阶段一完成标准

只有同时满足以下条件，才能认为 V1 完成：

- [ ] 没有新建第二套背包页面；
- [ ] 管理员入口已正确嵌入现有交易操作下拉菜单；
- [ ] 普通玩家看不到该入口；
- [ ] 创建官方商品不扣源物品；
- [ ] 浏览器不能伪造 ItemStack；
- [ ] ProductType 有正式 Snapshot fulfillment；
- [ ] 下单时冻结 Snapshot；
- [ ] 真正按 ItemStack blob 发货；
- [ ] 背包满安全进入 Mailbox；
- [ ] 商城可以显示 Snapshot 主要属性；
- [ ] 编译 / 类型检查通过；
- [ ] 核心自动测试通过；
- [ ] 完成真实服务器 E2E。

---

# 6. 阶段二：V2 架构升级与增强能力

阶段二必须建立在阶段一已经稳定可用之后，不要提前混入 V1。

---

## 6.1 ProductType 与 FulfillmentType 解耦

当前官方商品 ProductType 同时承担“商品业务类型”和“怎么发货”的职责。

当 Snapshot Item 成熟后，建议逐步拆为：

```text
Product
├─ Trade / Product semantics
│   ├─ SALE
│   ├─ RECYCLE
│   ├─ GROUP_BUY
│   └─ ...
│
└─ Fulfillment
    ├─ COMMAND
    ├─ MATERIAL_ITEM
    ├─ ITEM_SNAPSHOT
    ├─ POTION_EFFECT
    └─ ...
```

目的：以后增加新的商品模式时不再爆炸式增加 ProductType。

---

## 6.2 Snapshot 版本历史

V2 增加正式版本能力：

```text
product_item_snapshots
----------------------
id
product_id
version
item_hash
item_blob
item_meta_json
created_by
created_at
active_from
```

实现：

- 查看商品历史版本；
- 管理员回滚；
- 审计“谁在什么时候把官方商品换成了什么物品”；
- 老订单永久引用购买版本。

---

## 6.3 实体库存 / 入库模式

在模板复制之外增加可选模式：

```text
库存来源：
○ 官方模板复制
○ 实体库存入库
```

实体入库模式：

```text
管理员选择物品 x32
    ↓
真正 withdraw 32
    ↓
进入官方仓库存储
    ↓
stock = 32
    ↓
玩家每购买一件，实体库存 -1
```

此模式应复用当前分支已经具备的：

- InventoryService.withdraw；
- offline PlayerData safe write；
- UUID lock；
- revision/fingerprint；
- rollback；
- recovery journal。

需要额外设计：

- deposit transaction；
- restock；
- cancel / withdraw stock；
- 插件崩溃恢复；
- 商品删除后的实体库存返还。

不要在阶段一实现。

---

## 6.4 离线管理员 Snapshot 捕获

V2 可支持：

> 管理员不在线，也能从最后真实 playerdata 中选择物品并创建官方模板。

模板复制模式只需要 **read-only resolve**，不需要写 playerdata。

建议为 `PlayerDataInventoryService` 增加只读 resolve：

```text
offlineResolve(...)
```

要求仍然校验：

- revision；
- slot；
- fingerprint。

实体入库模式才需要 offline withdraw + safeWrite。

---

## 6.5 容器内部物品

当前 InventoryService 已有 `containerSlot` 能力时，V2 前端可正式支持：

```text
潜影盒
  ↓ 展开
内部 slot
  ↓
加入官方商城
```

必须保证：

- 外层容器 revision；
- child fingerprint；
- child slot；
- UI 明确显示物品来源；
- 不因容器移动造成错误捕获。

---

## 6.6 Mod / 混合端兼容矩阵

不要承诺“支持所有 Mod Item”。

正式表述：

> WebShopX 能保留服务器当前运行时能够完整转换为 Bukkit ItemStack 并通过 ItemSnapshotCodec 序列化/反序列化的数据。

建议加入 publish-time round-trip validation：

```text
serialize
→ deserialize
→ validate type/meta
→ optionally serialize again and compare fingerprint/hash
```

测试运行时至少按项目实际支持范围覆盖：

- Paper；
- Folia；
- 如果项目声称支持：Mohist / Arclight / 其他混合端。

发现不能完整 round-trip 的物品，应在“加入官方商城”时明确拒绝，不允许创建一个未来无法发货的商品。

---

# 7. Codex 推荐执行顺序

严格按下面顺序推进，不要一次性大改：

```text
Step 1
阅读 feat/webshopx-inventory-management 当前实现
确认 InventoryService / ItemSnapshotCodec / Market Delivery / Product / Order / Delivery 数据流

Step 2
定位当前真实前端“我的背包”页面
确认截图所示下拉操作代码位置

Step 3
设计并实现 official_item_snapshots + SNAPSHOT_ITEM

Step 4
实现 POST /api/admin/products/from-inventory
只使用服务端 resolve 的真实 ItemStack

Step 5
扩展 OrderService：购买时冻结 Snapshot

Step 6
扩展/抽取 DeliveryService：真实 ItemStack Delivery + Mailbox

Step 7
官方 products API 返回 Snapshot metadata

Step 8
现有背包下拉菜单增加管理员“加入官方商城”
不新建页面

Step 9
创建商品对话框

Step 10
自动测试 + build

Step 11
真实 Paper/Folia E2E

Step 12
只修复阶段一问题，不提前实现阶段二
```

---

# 8. Codex 修改前重点检查文件

后端至少检查：

```text
src/main/java/com/webshopx/InventoryService.java
src/main/java/com/webshopx/ItemSnapshotCodec.java
src/main/java/com/webshopx/PlayerDataInventoryService.java
src/main/java/com/webshopx/InventoryOperationService.java
src/main/java/com/webshopx/ProductService.java
src/main/java/com/webshopx/OrderService.java
src/main/java/com/webshopx/DeliveryService.java
src/main/java/com/webshopx/MailboxService.java
src/main/java/com/webshopx/EmbeddedWebServer.java
src/main/java/com/webshopx/DatabaseManager.java
```

以及当前数据库 schema / migration 相关代码。

前端不要凭文件名猜测。

先在 `Cc-Cece/webshopx-vuetify-web` 当前实际开发代码中搜索：

```text
选择默认交易操作
创建玩家市场上架
出售给收购挂单
创建拍卖
```

找到与当前截图完全一致的页面后再修改。

---

# 9. 明确禁止事项

Codex 不应：

- ❌ 从废弃 `agent/official-shop-inventory-v1` 分支 cherry-pick；
- ❌ 新建另一套“我的背包”页面；
- ❌ 用 Material 名称重新构造 Snapshot 商品；
- ❌ 让浏览器提交权威 NBT / blob；
- ❌ 用 console command 作为正式 Snapshot Delivery 架构；
- ❌ 把官方商品塞进 `market_listings`；
- ❌ 把官方商城设计成“服务器管理员卖家账号”；
- ❌ 创建官方商品时默认扣掉管理员源物品；
- ❌ 在 V1 混入实体库存、离线写入、版本回滚等 V2 范围；
- ❌ 未做真实服务器 E2E 就宣称功能完成；
- ❌ 宣称无条件兼容所有 Mod Item。

---

# 10. 最终目标结构

阶段一完成后，目标数据流应为：

```text
                      现有“我的背包”
                            │
                 slot / revision / fingerprint
                            │
                 ┌──────────┴──────────┐
                 │                     │
              普通玩家               管理员
                 │                     │
        玩家市场 / 收购 / 拍卖       加入官方商城
                                       │
                                   resolve()
                                       │
                              ItemSnapshotCodec
                                       │
                              immutable Snapshot
                                       │
                               SNAPSHOT_ITEM
                                       │
                                     下单
                                       │
                              冻结购买时 Snapshot
                                       │
                              ItemStack Delivery
                                       │
                         ┌─────────────┴─────────────┐
                         │                           │
                     Inventory                    Mailbox
```

阶段二再在这一稳定基础上增加：

```text
Fulfillment 解耦
Snapshot 版本历史
实体库存入库 / 补货
离线管理员捕获
容器内部物品
更严格的 Mod runtime round-trip 验证
```

---

# 11. 两阶段最终定义

## 阶段一：正式 V1

关键词：

> **复用现有背包 UI + 管理员入口 + 服务端可信 Snapshot + 官方商城正式 Snapshot fulfillment + 订单冻结 + 原样发货 + Mailbox + E2E。**

阶段一的目标是“安全、正确、可上线测试”，不是追求所有高级能力。

## 阶段二：V2

关键词：

> **架构解耦 + Snapshot 版本化 + 实体库存 + 离线操作 + 容器内部物品 + Mod/混合端兼容增强。**

只有阶段一在真实服务器验证稳定后，才进入阶段二。
