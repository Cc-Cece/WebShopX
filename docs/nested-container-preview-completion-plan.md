# 潜影盒 / Bundle 全站预览功能：现状核对与完成计划

> 后端：`Cc-Cece/WebShopX: feat/webshopx-inventory-management`
>
> 前端：`Cc-Cece/webshopx-vuetify-web: feat/webshopx-inventory-management`
>
> 本文件基于上述两个指定分支的当前实际实现编写。
>
> 目标：**下一轮 Codex 执行后，将“潜影盒 + Bundle 内容预览”作为一个完整功能收尾，而不是继续新增半成品。**

---

# 1. 当前结论

核心功能已经不是“待从零开发”，而是进入 **收尾、统一和验证阶段**。

目前已经存在：

- 在线背包潜影盒 / Bundle 递归内容解析；
- 离线 playerdata 潜影盒 / Bundle 递归内容解析；
- Inventory API `containerItems`；
- ItemSnapshot `itemMetaJson.containerItems`；
- 背包 Hover 快速预览；
- 背包右侧详情预览；
- 单一详细 Dialog；
- 嵌套容器 breadcrumb；
- 商品 Snapshot Hover 预览组件；
- 多个现有页面已接入。

因此下一步不要重写后端容器模型，也不要重新设计第二套预览组件。

---

# 2. 前端接入现状核对

## 2.1 我的背包 `/inventory`

状态：**已完整接入，核心基准实现。**

现有能力：

- `/inventory` 路由已存在；
- `InventorySlot` 对潜影盒 / Bundle 使用 `open-on-hover` 快速预览；
- Hover 延迟约 200 ms；
- 右侧详情通过 `NestedContentsPreview` 展示容器内容；
- 潜影盒为固定 9×3 / 27 槽布局；
- Bundle 为紧凑内容列表；
- 有“展开查看”；
- 使用单一 Dialog；
- 嵌套容器通过 breadcrumb 在同一 Dialog 中导航；
- 不需要再新建背包页面或新的 Dialog 体系。

关键组件：

```text
src/components/inventory/InventorySlot.vue
src/components/inventory/NestedContentsPreview.vue
src/pages/inventory.vue
```

结论：**保留并作为其他页面交互的一致性基准。**

---

## 2.2 官方商城 `/shop`

状态：**已接入。**

当前：

- Grid 商品图标使用 `SnapshotItemIcon`；
- List 商品图标使用 `SnapshotItemIcon`；
- 因此容器商品支持 Hover 快速预览；
- 现有商品详情 Dialog 已加入 `SnapshotNestedContents mode="detail"`。

结论：核心交互已满足计划。

需要做的主要是实机验证、移动端验证和样式一致性检查。

---

## 2.3 玩家市场 `/market`

状态：**已接入。**

当前：

- Grid / List 图标均使用 `SnapshotItemIcon`；
- Hover 快速预览已具备；
- 商品详情 Dialog 已加入 `SnapshotNestedContents mode="detail"`。

结论：核心交互已满足计划。

---

## 2.4 拍卖 `/auction`

状态：**已接入。**

当前：

- Grid / List 图标均使用 `SnapshotItemIcon`；
- Hover 快速预览已具备；
- 拍卖详情 Dialog 已加入 `SnapshotNestedContents mode="detail"`。

结论：核心交互已满足计划。

---

## 2.5 我的上架 / 我的拍卖 `/listings`

状态：**已接入。**

当前“我的上架”：

- 表格商品图标使用 `SnapshotItemIcon`；
- 因此支持 Hover；
- 展开行顶部已加入 `SnapshotNestedContents mode="detail"`。

当前“我发起的拍卖”：

- 表格商品图标使用 `SnapshotItemIcon`；
- 支持 Hover；
- 展开行已加入 `SnapshotNestedContents mode="detail"`。

结论：符合“Hover + 已有展开行”的计划，不应新增额外 Dialog。

---

## 2.6 我的订单 `/orders`

状态：**已接入。**

当前：

- 订单表格商品图标使用 `SnapshotItemIcon`；
- 因此支持 Hover；
- 展开订单行已加入 `SnapshotNestedContents mode="detail"`；
- 使用订单自身的 `itemMetaJson`，方向正确，应显示购买时冻结的 Snapshot 内容，而不是重新读取当前商品或玩家背包。

结论：核心接入已经完成，重点验证历史 Snapshot 不漂移。

---

## 2.7 后台官方商品管理 `/admin/commerce`

状态：**已接入。**

当前：

- 桌面商品表格使用 `SnapshotItemIcon`；
- 移动端商品卡片使用 `SnapshotItemIcon`；
- 因此均具备 Hover（桌面）或现有 Snapshot 图标能力；
- 商品新增 / 编辑 Dialog 已加入 `SnapshotNestedContents mode="detail"`。

结论：符合“列表快速预览 + 编辑界面完整预览”的计划。

移动端没有 Hover 是正常行为，不应强制模拟桌面 Hover。

---

## 2.8 后台市场监管 `/admin/market`

状态：**已按轻量方案接入。**

当前：

- 在售挂单物品图标已经改为 `SnapshotItemIcon`；
- 因此桌面 Hover 可快速查看容器内容；
- 当前没有额外完整详情预览。

这与既定产品决策一致：后台市场监管主要用于监管和强制下架，**Hover 快速预览即可，不新增详情 Dialog。**

---

# 3. 公共前端组件现状

当前已经形成合理的组件层：

```text
NestedContentsPreview.vue
├─ 潜影盒 9×3
├─ Bundle 紧凑列表
├─ quick / detail / dialog
└─ 嵌套标识

SnapshotNestedContents.vue
├─ 从 itemMetaJson 转为统一 ItemView
├─ Hover Menu
├─ breadcrumb
└─ 嵌套 Item 详情

SnapshotItemIcon.vue
└─ McIcon + SnapshotNestedContents quick wrapper
```

这套方向正确。

下一步应该**复用并收敛**，不得再为 shop / market / auction 等分别创建页面专属的容器解析器。

---

# 4. 后端现状

后端已经具备完成本功能所需的主要数据能力：

```text
InventoryService
→ online ItemView.containerItems

PlayerDataInventoryService
→ offline ItemView.containerItems

InventorySnapshotJsonCodec
→ API containerItems

ItemSnapshotCodec
→ itemMetaJson.containerItems
```

潜影盒和 Bundle 均可递归表示，当前存在深度 / 子项数量安全限制。

下一步后端工作原则：

> **不要重新设计容器预览数据结构；重点验证从 ItemStack → Snapshot → DB → API → 前端过程中 containerItems 不丢失。**

---

# 5. 已确认问题：官方商城“实体库存入库”确实仍然存在

这不是误判，当前前端分支中确实存在可见且可提交的实体库存模式。

`inventory.vue` 创建官方商品 Dialog 目前有：

```text
库存来源：
- 官方模板复制 / TEMPLATE
- 实体库存入库 / PHYSICAL
```

选择 `PHYSICAL` 后还会显示：

```text
实际入库数量
```

并显示提示：

> 实体库存模式会从当前背包实际扣除指定数量，创建失败时自动回滚。

更重要的是，它不是纯 UI 死代码。

提交时前端实际发送：

```text
inventoryMode: PHYSICAL
depositQuantity: ...
```

成功 Toast 也存在：

> “实体库存已入库”

后端当前分支同时仍注册了与 Physical stock 有关的能力，例如：

```text
/api/admin/products/physical-withdraw
```

因此当前代码与后来确定的产品方向存在明确冲突。

## 最终产品决策

WebShopX 当前官方商城统一采用：

```text
真实 ItemStack
→ immutable Snapshot 模板
→ 官方商城逻辑库存（无限 / 有限）
```

**不需要官方实体库存入库。**

有限销售数量由官方商城逻辑库存解决，例如：

```text
模板 Snapshot ×1
官方逻辑库存 = 10
→ 最多销售 10 件
```

因此下一轮必须清除这个旧实验设计，避免 Codex 继续把 Physical stock 当正式需求维护。

---

# 6. 下一步：一次性完整完成该功能

以下任务应作为一个完整收尾批次执行，不建议拆成多个长期版本。

## Step 1：清除官方商城实体库存入口和业务路径

前端：

- 删除“库存来源”中的 `PHYSICAL / 实体库存入库`；
- 删除 `depositQuantity` UI；
- 删除实体库存说明和成功提示；
- 创建官方商品只保留 Snapshot 模板语义；
- 不再从浏览器发送 `inventoryMode=PHYSICAL` / `depositQuantity`。

后端：

- `/api/admin/products/from-inventory` 正式只接受模板 Snapshot 创建语义；
- 不允许通过手工 API 请求恢复 `PHYSICAL` 扣物路径；
- 若 Physical 专用 endpoint / service / schema 已无其他正式用途，清理或明确废弃；
- 不影响玩家市场真实物品扣除逻辑，二者业务必须分离。

注意：不要误删玩家市场的 `withdraw`、离线玩家市场写入、回滚等已有功能。

---

## Step 2：统一预览组件行为

以 `NestedContentsPreview` / `SnapshotNestedContents` 为唯一正式实现。

检查并统一：

- 潜影盒：固定 27 槽；
- Bundle：紧凑列表，不伪造 27 槽；
- 空潜影盒明确显示“空”；
- 空 Bundle 明确显示“空”；
- 数量角标正确；
- 自定义名称 / Lore / Enchantment / CustomModelData / ItemModel 能随内部 Item 展示；
- 嵌套容器继续使用同一详细视图和 breadcrumb；
- 不允许 Dialog 套 Dialog 无限叠层；
- 移动端不能依赖 Hover 才能获得关键信息。

---

## Step 3：逐页回归 8 个现有位置

必须逐页验证：

```text
/inventory
/shop
/market
/auction
/listings（我的上架）
/listings（我的拍卖）
/orders
/admin/commerce
/admin/market
```

说明：`/listings` 是一个页面两个业务 Tab，因此功能点统计时可视为两个场景。

验证目标：

### Inventory
- Hover 快速预览；
- 右侧完整预览；
- 展开 Dialog；
- 嵌套导航；
- 在线 / 离线 Snapshot 均一致。

### Shop / Market / Auction
- Grid Hover；
- List Hover；
- 当前已有 Detail Dialog 内完整预览。

### Listings
- 表格 Hover；
- 展开行完整预览；
- 直购与拍卖两 Tab 均覆盖。

### Orders
- 表格 Hover；
- 展开行完整预览；
- 必须显示订单冻结 Snapshot。

### Admin Commerce
- 桌面表格 Hover；
- 编辑 / 查看商品时完整预览；
- 移动端可通过进入编辑/详情获得完整信息。

### Admin Market
- 桌面 Hover 快速预览即可；
- 不新增多余详情窗口。

---

## Step 4：完整数据链路验证

至少准备以下真实测试物品：

### A. 空潜影盒

```text
Shulker
└─ empty
```

### B. 普通潜影盒

```text
Shulker
├─ Diamond Sword
├─ Golden Apple ×32
└─ Enchanted Book
```

### C. Bundle

```text
Bundle
├─ Iron Ingot ×16
├─ Torch ×12
└─ Apple ×4
```

### D. 嵌套结构

在当前 Minecraft / Bukkit API 合法允许的范围内准备实际可生成的嵌套容器测试数据，并确认：

```text
outer
→ Snapshot
→ DB
→ API
→ frontend
```

每一级内容保持一致。

重点核查：

- material；
- amount；
- slot；
- name；
- lore；
- enchantments；
- customModelData；
- itemModel；
- nested containerItems。

---

## Step 5：生命周期场景验证

不能只测背包页面。

至少跑完整业务链：

### 玩家市场

```text
背包潜影盒
→ 创建玩家出售单
→ /market 预览
→ /listings 预览
→ 买家购买
→ 订单 / 交付结果保持同一 Snapshot
```

### 拍卖

```text
背包潜影盒
→ 创建拍卖
→ /auction 预览
→ /listings 我的拍卖预览
→ 成交
→ Snapshot 内容不改变
```

### 官方商城

```text
管理员背包潜影盒
→ 加入官方商城（模板复制）
→ 原物品不扣除
→ /shop 预览
→ /admin/commerce 预览
→ 玩家购买
→ /orders 显示购买时 Snapshot
```

### 离线官方上架

若 V3 已启用：

```text
管理员离线
→ PLAYERDATA 只读 Snapshot
→ 加入官方商城
→ playerdata 零修改
→ 商品预览与在线创建结果一致
```

---

# 7. 必须检查的潜在缺陷

## 7.1 Bundle 容量显示

当前前端自行根据 `maxStackSize` 和内部 Item 估算 Bundle 容量。

Codex 应对照当前服务器目标 Minecraft/Paper 版本实际规则验证，避免网页容量与游戏内 Bundle 容量不一致。

若无法稳定复现原版规则，宁可显示：

```text
内容 N 种
```

也不要展示错误的 `x / 64`。

---

## 7.2 空容器识别

必须确认 `containerItems: []` 仍然能让：

- 空潜影盒开启预览；
- 空 Bundle 开启预览；
- 明确显示“空”。

不能因为数组为空就把它误判为普通物品。

---

## 7.3 Hover 与点击事件冲突

Grid/List 商品自身通常有点击打开详情的行为。

需要确认：

- Hover Menu 不会阻断商品点击；
- Hover 中查看内部物品不会误触购买 / 打开外层详情；
- 离开 Menu 后正常关闭；
- 快速划过多个商品不会残留多个浮层。

---

## 7.4 Dialog 层级

当前 `SnapshotNestedContents` 在内部 Item 详情时会使用 `AppDialog`。

当它本身位于 `/shop`、`/market`、`/auction` 的 Detail Dialog 内时，要重点验证：

- 不出现不可关闭的 Dialog 套层；
- ESC / 遮罩关闭逻辑正确；
- z-index 正确；
- 移动端滚动不锁死。

若实测体验出现“Dialog 套 Dialog”，应将内部 Item 详情改为当前详情区域切换 / 内嵌 drill-down，而不是继续叠加 Dialog。

产品原则仍然是：

> **同一条查看链路最多维持一个主详细预览层。**

---

# 8. 测试要求

## 前端

至少：

```text
npm run type-check
npm run build-only
```

并补充针对 Snapshot normalization / containerItems 的单元测试（项目测试框架允许时）。

## 后端

至少覆盖：

- Shulker ItemSnapshot metadata；
- Bundle ItemSnapshot metadata；
- 空容器 metadata；
- nested metadata；
- deserialize / validateRoundTrip 后内容保持；
- online Inventory Snapshot；
- offline playerdata Snapshot；
- `/api/products` / market / auction / orders / admin API 透传 `itemMetaJson` 不丢失；
- 官方商品 from-inventory 不再接受 PHYSICAL 模式。

## 实机 E2E

Paper / Folia 目标环境至少各完成一轮核心流程，若项目正式只支持其中一个则以实际支持矩阵为准。

---

# 9. 明确不做

本轮不要扩展到：

- 弩已装填弹药；
- 蜂箱 / 蜂巢蜜蜂信息；
- 普通箱子 / 木桶内容；
- 饰纹陶罐；
- 新建 Web Mailbox 页面；
- 从 Bundle 内部直接进行网页交易；
- 新增第二套 Inventory 页面；
- 新增页面专属的容器预览实现。

---

# 10. 完成标准

只有同时满足以下条件，才可将此功能标记为完成：

- [ ] 潜影盒 Hover 预览稳定；
- [ ] Bundle Hover 预览稳定；
- [ ] 潜影盒 27 槽显示正确；
- [ ] Bundle 显示逻辑与目标 MC 版本一致；
- [ ] 空容器能够正确预览；
- [ ] 嵌套查看可返回上一级；
- [ ] `/inventory` 完整通过；
- [ ] `/shop` Grid/List/Detail 通过；
- [ ] `/market` Grid/List/Detail 通过；
- [ ] `/auction` Grid/List/Detail 通过；
- [ ] `/listings` 直购 Tab 通过；
- [ ] `/listings` 拍卖 Tab 通过；
- [ ] `/orders` Hover + 展开行通过；
- [ ] `/admin/commerce` 列表 + 编辑 Dialog 通过；
- [ ] `/admin/market` Hover 通过；
- [ ] ItemStack → Snapshot → DB → API → UI 内容无丢失；
- [ ] 订单显示购买时冻结的容器 Snapshot；
- [ ] 在线和离线背包预览一致；
- [ ] 官方商城创建商品只保留 TEMPLATE Snapshot 语义；
- [ ] “实体库存入库 / PHYSICAL / depositQuantity” 不再存在正式用户路径；
- [ ] 创建官方商品不会扣除管理员源物品；
- [ ] type-check / build / 后端测试通过；
- [ ] 实机 E2E 通过。

---

# 11. Codex 执行原则

执行时先审查当前两个 `feat/webshopx-inventory-management` 分支，不要按旧计划书假定功能尚未实现。

优先顺序：

```text
1. 删除 Physical 官方库存旧路径
2. 审查并修复已有预览组件
3. 核对全部既有页面接入
4. 核对后端 DTO / API 透传
5. 补测试
6. Paper/Folia E2E
7. 只在发现真实缺口时新增代码
```

禁止：

- 重造 `/inventory`；
- 复制旧 `agent/official-shop-inventory-v1` 实验实现；
- 为每个页面创建一套新的容器预览；
- 恢复官方实体库存入库作为正式功能。
