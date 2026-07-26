# 官方商城 × 我的背包：当前进度与后续计划

> 状态文档：用于记录 `agent/official-shop-inventory-v1` 当前实现边界，以及后续两个阶段的推进顺序。
>
> 后端仓库：`Cc-Cece/WebShopX`
>
> 后端实现分支：`agent/official-shop-inventory-v1`（基于 `feat/webshopx-inventory-management`）
>
> 前端仓库：`Cc-Cece/webshopx-vuetify-web`
>
> 前端实现分支：`agent/official-shop-inventory-v1`（基于 `main`）

## 1. 当前进度

### 1.1 已完成：从“我的背包”创建官方商城商品

当前 V1 已完成核心闭环：

`我的背包 → 选择真实 ItemStack → 管理员填写商品信息 → 创建官方商城商品 → 玩家购买 → 按创建时 Snapshot 原样发货`

主要实现内容：

- 前端新增“我的背包”入口与 `/inventory` 页面。
- 支持查看玩家背包和末影箱的 Inventory Snapshot。
- 管理员拥有 `PRODUCT_MANAGE` 权限时，可从选中物品直接创建官方商品。
- 创建商品采用“模板复制”语义，**不会扣除管理员当前持有的物品**。
- 浏览器只提交 `slot + revision + fingerprint + inventory source` 等定位信息，不上传权威 ItemStack/NBT 数据。
- 后端重新从 Minecraft 服务端读取并校验真实 ItemStack。
- 物品通过现有 `ItemSnapshotCodec` 序列化，保存不可变 `item_hash`、原始 ItemStack blob 和 metadata JSON。
- Snapshot 商品继续复用现有官方商城的 SKU、价格、币种、逻辑库存、个人限购、动态定价、订单和 Delivery Queue 体系。
- 下单时 Snapshot hash 会被固化到订单发货命令中，因此之后修改同一 SKU 的商品，不会改变旧订单实际购买的物品版本。
- Snapshot 发货使用 WebShopX 内部 console-only 命令恢复真实 ItemStack。
- 玩家背包无法完整容纳购买数量时，当前实现会将该批物品转入 Mailbox，避免直接掉落或丢失。
- 三种插件描述文件均已注册内部 Snapshot 发货命令。

### 1.2 已完成：安全边界

当前已明确并实现以下安全原则：

- 浏览器不是物品真实性来源。
- 商品创建必须通过服务端校验 Inventory Revision 与 Item Fingerprint。
- 普通玩家不能执行 Snapshot 内部发货命令。
- V1 创建官方商品只允许已绑定 Minecraft UUID 的管理员账号使用。
- V1 Snapshot 捕获要求管理员本人在线，避免离线 playerdata 写入参与商品模板创建流程。
- Snapshot 以 SHA-256 hash 作为不可变版本标识。
- 官方商城商品与玩家市场 Listing 继续保持业务层分离，没有把官方商城改造成“服务器玩家市场账号”。

### 1.3 已完成：前端交互

“我的背包”页面目前支持：

- 玩家背包 / 末影箱切换；
- 在线 / 离线 Snapshot 状态提示；
- 格子、物品名、Material、数量、Lore、附魔等基本信息展示；
- 管理员选择物品后直接进入“加入官方商城”；
- 创建时设置商品名称、SKU、售价、币种、备注、库存模式、库存上限、个人限购和启用状态；
- 明确提示 Snapshot 模板模式不会扣除源物品。

### 1.4 验证状态

已完成：

- 后端 Java 21 / `1.20.6+`：`compileJava` 通过。
- 前端 Node 22：`npm run type-check` 通过。
- 前端：`npm run build-only` 通过，包含最终“我的背包”侧边栏入口。

已知但与本功能无直接关系的问题：

- 当前仓库完整测试套件中仍存在原有 `MaterialMappingTest.materialZhMapShouldContainResolvableMaterialKeys` 失败；本次 V1 未修改该测试及材质本地化数据。

### 1.5 当前尚未完成 / 明确边界

以下内容暂不视为 V1 已完成：

- 尚未在真实 Paper/Folia 服务器环境执行完整 E2E 测试。
- 尚未验证附魔物品、Potion、CustomModelData、插件自定义物品以及混合端 Mod Item 的完整 round-trip 兼容矩阵。
- 官方商城 `/api/products` 尚未统一回传 Snapshot 的 `itemMetaJson`，因此商城已有的 ItemAttributes 展示能力还没有完整接上 Snapshot 商品。
- 当前前端未开放潜影盒等容器内部子物品的直接 Snapshot 商品创建入口。
- 当前库存为“逻辑库存”，不代表管理员实际存入了对应数量的实体物品。
- 当前不支持离线管理员直接把 playerdata 中的物品创建为官方商城模板。

---

# 2. 后续阶段一：V1 收口、真实服务器验证与可发布化

## 阶段目标

不继续扩大业务功能，优先把当前 V1 从“代码闭环”推进到“真实服务器上可稳定使用”。

## 2.1 必做：完整 E2E 测试

至少验证以下主链路：

1. 管理员在线登录服务器与 WebShopX。
2. 打开“我的背包”，刷新在线 Snapshot。
3. 选择普通物品创建官方商品。
4. 玩家购买商品并正常收到物品。
5. 使用附魔、Lore、改名、CustomModelData 等物品重复测试。
6. 修改同一 SKU 的 Snapshot 后，验证旧订单仍领取旧 Snapshot。
7. 玩家背包空间不足时验证 Mailbox fallback。
8. 商品有限库存、个人限购、停用/重新启用等现有商城逻辑不受 Snapshot 商品影响。
9. Paper 与 Folia 至少各完成一轮关键路径验证。

## 2.2 必做：Snapshot 商品展示补齐

将 Snapshot metadata 正式接入官方商城产品 JSON：

- `fulfillmentType = ITEM_SNAPSHOT`
- `itemHash`
- `itemMetaJson`

目标是直接复用当前前端已经存在的 `ItemAttributes / ItemAttributeSummary`，让玩家在购买前能看到附魔、Lore、耐久、自定义模型等信息。

注意：metadata 只用于展示，真实发货仍必须以 Snapshot blob 为准。

## 2.3 必做：异常与幂等测试

重点检查：

- 重复执行同一 Delivery Task 是否可能重复发物品；
- 发货过程中服务器关闭 / 插件重载后的恢复行为；
- Mailbox 入库成功但订单状态更新失败的边界；
- Snapshot 数据缺失或反序列化失败时，订单是否正确进入失败/待处理状态；
- 创建商品过程中 Inventory Revision 改变时是否稳定拒绝；
- 商品创建成功但浏览器响应中断时，重复提交是否产生不可控副作用。

## 2.4 必做：权限与审计确认

确认是否继续使用：

- `PRODUCT_MANAGE`

还是正式增加更细权限：

- `PRODUCT_IMPORT_INVENTORY`

推荐阶段一结束前增加独立权限，使“编辑普通官方商品”和“从真实玩家背包捕获物品模板”可以分开授权。

同时检查 Admin Audit Log 至少能追踪：

- 操作管理员；
- SKU；
- Snapshot hash；
- 源 Inventory；
- Slot；
- 创建时间；
- 是否保留源物品。

## 2.5 必做：兼容性测试

建立最小兼容矩阵：

- 原版普通物品；
- 附魔物品；
- 改名 + Lore；
- Potion / Enchanted Book；
- CustomModelData；
- 常用 Bukkit/Paper 插件自定义物品；
- 若项目目标包含 Mohist/Arclight 等混合端，则额外测试真实 Mod Item。

对 Mod Item 不预设“必然支持”，以 `serialize → deserialize → fingerprint / 行为验证` 结果决定是否允许发布。

## 2.6 阶段一完成标准

满足以下条件后，V1 可视为正式完成：

- 核心 E2E 主链路在目标服务器运行通过；
- Snapshot 商品购买前可展示主要 Item metadata；
- 普通官方商品、玩家市场和现有订单功能无明显回归；
- 发货失败、Mailbox fallback、重复执行等关键异常路径明确；
- 权限和审计边界确定；
- 至少形成一份可重复执行的人工测试清单。

---

# 3. 后续阶段二：正式 Snapshot Fulfillment 架构与实体库存能力

## 阶段目标

在 V1 稳定之后，不再继续依赖 `GIVE_CUSTOM_ITEM + 内部命令` 作为长期兼容桥，而是把“真实 ItemStack 商品”提升为官方商城的一等能力。

## 3.1 重构 ProductType 与 FulfillmentType

推荐逐步从当前混合 ProductType 模型拆分为：

### Trade / Business Type

例如：

- PURCHASE
- RECYCLE
- VOUCHER

### Fulfillment Type

例如：

- COMMAND
- MATERIAL
- ITEM_SNAPSHOT
- POTION
- 其他未来类型

这样可以让“商品是什么业务”与“买完之后怎么交付”解耦。

V1 当前使用 `GIVE_CUSTOM_ITEM` 只是兼容现有订单系统的过渡实现，阶段二应正式迁移为 `ITEM_SNAPSHOT` Fulfillment。

## 3.2 正式 Snapshot 版本表

推荐将 Snapshot 从简单 hash 存储提升为明确的商品版本模型，例如：

`product_item_snapshots`

至少记录：

- id
- product_id
- version
- raw_item_blob
- item_meta_json
- item_hash
- minecraft_version
- server_id / compatibility_group
- snapshot_codec_version
- created_at

订单项或 Delivery Task 明确引用不可变 `snapshot_id`。

目标：

- 商品当前版本可以更新；
- 历史订单永久绑定购买时版本；
- 后续可以做 Snapshot 迁移和版本兼容检查；
- 不需要长期从 command template 中解析 hash。

## 3.3 实体库存模式

在“无限模板 / 逻辑有限库存”之外增加第三种库存语义：

- Unlimited Template
- Logical Finite Stock
- Physical Inventory Stock

### Physical Inventory Stock

管理员选择物品并指定入库数量后：

- 服务端锁定玩家 Inventory；
- 校验 Revision / Fingerprint；
- 从玩家真实背包扣除对应数量；
- 增加官方商城实体库存；
- 失败时完整回滚。

“补货”同样从背包直接存入。

这一阶段可以复用现有离线 inventory-management 已完成的：

- UUID 公平锁；
- Revision 校验；
- 登录闸门；
- 写入前后在线状态检查；
- playerdata 临时文件校验；
- 备份与原子替换；
- 崩溃恢复日志；
- 幂等处理。

## 3.4 离线管理员捕获 / 入库

模板模式可进一步支持：

- 管理员离线时，从受信 playerdata Snapshot 创建商品模板。

实体库存模式则必须走完整 playerdata 安全写入事务。

这部分应严格复用现有 inventory-management 安全边界，而不是增加第二套离线 NBT 写入逻辑。

## 3.5 容器内部物品支持

阶段二可正式开放：

- 潜影盒内部物品；
- 其他可读取容器 ItemStack 子槽位。

前端需要明确展示：

`外层 Slot → Container Slot → Item`

同时继续使用 Revision + Fingerprint 防止 UI 展示状态和真实 Inventory 状态不一致。

## 3.6 多服务器与版本兼容

Snapshot 商品生命周期通常比玩家市场 Listing 更长，因此需要增加兼容信息：

- 创建 Snapshot 的 Minecraft 版本；
- Bukkit/Paper API / codec version；
- 来源 serverId；
- compatibility group；
- 是否允许跨服务器发货。

对于不同 Minecraft 大版本或不同混合端，不应默认认为 Bukkit 序列化永远兼容。

建议在发布或迁移商品时执行：

`deserialize → serialize → integrity validation`

必要时阻止不兼容 Snapshot 上架。

## 3.7 阶段二完成标准

阶段二完成后，官方商城应具备真正独立、长期可维护的 ItemStack 商品体系：

- `ITEM_SNAPSHOT` 成为正式 Fulfillment 类型；
- Product 不再依赖隐藏 command template 表示 Snapshot 发货；
- 商品 Snapshot 有正式版本历史；
- 订单直接绑定不可变 Snapshot version；
- 支持模板库存、逻辑库存和实体库存；
- 可安全补货；
- 可选择性支持离线管理员操作；
- 多服务器 / 多版本兼容边界明确；
- 玩家市场与官方商城共享底层 ItemSnapshot 基础设施，但业务模型继续独立。

---

# 4. 推荐推进顺序

当前不建议立即进入阶段二的大重构。

推荐顺序：

**当前 V1 → 阶段一真实服务器验证与收口 → 确认生产可用 → 再进入阶段二架构升级。**

原因是当前 V1 已经能够验证最关键的产品假设：

> “管理员是否真的需要、并且能够稳定地把背包中的复杂真实物品直接作为官方商城商品出售。”

只有这个闭环在真实服务器中稳定运行后，再投入 Snapshot Fulfillment、实体库存、离线写入和版本体系等较大的架构改造，风险最低。