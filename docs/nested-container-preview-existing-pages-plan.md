# 潜影盒 / Bundle 预览：现有页面扩展计划书（供 Codex 执行）

> 目标分支：`Cc-Cece/WebShopX: feat/webshopx-inventory-management`
>
> 本文件只描述“已有前端页面如何接入潜影盒 / Bundle 预览”。
>
> 不新增 Mailbox 页面，不新增新的业务页面，不修改已有 V1/V2/V3 计划书。

---

# 1. 范围原则

本阶段只考虑当前网页中已经存在、且真实展示 ItemStack / ItemSnapshot 的页面。

第一版嵌套内容只支持：

```text
潜影盒
Bundle / 收纳袋
```

暂不考虑：

- 弩已装填弹药；
- 蜂箱 / 蜂巢蜜蜂信息；
- 普通箱子、木桶、漏斗、熔炉等正常生存下不会以“携带内容物 ItemStack”形式存在的物品；
- 饰纹陶罐；
- 为命令或数据包制造的特殊 container item 单独设计 UI。

整体仍应抽象为可复用的：

```text
ItemPreview
└─ NestedContentsPreview
```

不要为每个页面分别开发一套潜影盒逻辑。

---

# 2. 后端原则

后端原则上保持统一，不按页面生成不同格式的数据。

推荐统一模型概念：

```text
ItemView
├─ material
├─ displayName
├─ amount
├─ lore
├─ enchantments
├─ customModelData
└─ nestedContents
    ├─ type: SHULKER | BUNDLE
    ├─ capacity / occupancy
    └─ items[]
        └─ ItemView
```

关键区别只在数据来源：

## 2.1 我的背包

来源是当前真实背包 Snapshot：

```text
在线 → live inventory snapshot
离线 → playerdata snapshot
```

## 2.2 商城 / 市场 / 拍卖 / 订单等

必须来自已经保存的 ItemSnapshot / 商品快照 / 挂单快照 / 订单快照。

严禁为了预览容器内容重新读取卖家的当前背包。

例如：

```text
玩家市场挂单
↓
展示潜影盒内容
↓
读取挂单创建时保存的 ItemSnapshot   ✅

而不是：

玩家市场挂单
↓
重新读取卖家当前背包               ❌
```

订单页尤其必须展示“购买时冻结的物品版本”，不能展示商品当前的新版本。

---

# 3. 统一前端交互规则

不同页面不要各自创造新的交互方式，统一成以下四类。

## A. 背包页面

```text
Hover → 快速预览
选中物品 → 右侧详情完整预览
```

## B. 商城 / 市场 / 拍卖

```text
Hover → 快速预览
现有 Detail Dialog → 完整预览
```

## C. 我的上架 / 我的拍卖 / 我的订单

```text
Hover → 快速预览
现有展开行 → 完整预览
```

## D. 管理后台

```text
商品管理：Hover + 编辑/详情区域完整预览
市场监管：Hover 快速预览即可
```

不要为了容器内容新增大量“查看内容”“再打开详情”等重复按钮。

---

# 4. 快速预览组件

桌面端在物品图标 Hover 时显示轻量预览。

建议组件：

```text
NestedContentsQuickPreview
```

## 潜影盒

使用 9×3 / 27 格 Minecraft 风格布局。

示意：

```text
紫色潜影盒 · 18 / 27

[ ][ ][ ][ ][ ][ ][ ][ ][ ]
[ ][ ][ ][ ][ ][ ][ ][ ][ ]
[ ][ ][ ][ ][ ][ ][ ][ ][ ]
```

要求：

- 显示真实槽位位置；
- 显示内部物品图标；
- 数量 > 1 时显示数量；
- 空潜影盒明确显示“空”；
- Hover 建议有约 150–250 ms 延迟，避免鼠标扫过列表时频繁闪烁；
- 快速预览只读，不允许拖拽、移动或交易内部物品。

## Bundle

不要伪造 27 格槽位。

推荐紧凑内容布局：

```text
收纳袋 · 42 / 64

[铁锭×16] [火把×12] [苹果×4]
[橡木木板×10]
```

或使用紧凑图标网格。

移动端不能依赖 Hover，应通过点击物品进入现有详情区域后查看。

---

# 5. 现有页面接入范围

## 5.1 我的背包

已有真实“我的背包”页面作为首要实现位置。

推荐：

```text
背包格子里的潜影盒 / Bundle
↓ Hover
快速预览

点击选中
↓
现有右侧物品详情
↓
增加“容器内容”区域
```

潜影盒：显示完整 9×3。

Bundle：显示内容列表 / 紧凑图标布局。

不要新建第二套 inventory 页面。

---

## 5.2 官方商城 `/shop`

当前页面已经有：

```text
Grid 商品卡片
List 商品列表
ItemAttributeSummary
详情 Dialog
ItemAttributes
```

接入方式：

```text
商品图标 Hover
→ NestedContentsQuickPreview

点击商品详情
→ 继续使用当前 Detail Dialog
→ 在 ItemAttributes 附近增加 NestedContentsPreview
```

不要为了潜影盒再打开第二层 Dialog。

如果商品是 Bundle，同样使用该区域显示 Bundle 内容。

---

## 5.3 玩家市场 `/market`

页面已经有 Grid / List、商品图标、ItemAttributeSummary 和详情 Dialog。

接入方式与官方商城保持一致：

```text
商品图标 Hover
→ 快速预览

现有 Detail Dialog
→ 完整预览
```

这里必须重点保证：

> 买家看到的是挂单创建时保存的真实容器内容，而不是卖家当前背包状态。

---

## 5.4 拍卖 `/auction`

页面已有 Grid / List、商品图标、ItemAttributeSummary、详情 Dialog。

接入方式：

```text
拍卖物品图标 Hover
→ 快速预览

现有拍卖详情 Dialog
→ 完整容器内容
```

潜影盒内部内容是拍卖标的价值的一部分，必须在出价前可查看。

---

## 5.5 我的上架 / 我的拍卖 `/listings`

这是一个页面，内部已有两个 Tab：

```text
我的上架
我的拍卖
```

现有结构是：

```text
表格
↓
展开行
↓
详细属性 / NBT 信息
```

因此不要新增 Dialog。

推荐：

```text
表格物品图标 Hover
→ 快速预览

展开当前行
→ 在现有详细属性 / NBT 区域增加“容器内容”
```

“我的拍卖”Tab 使用相同原则。

---

## 5.6 我的订单 `/orders`

当前订单页面已有：

```text
订单表格
商品图标
展开行详情
```

接入方式：

```text
订单物品图标 Hover
→ 快速预览

展开订单
→ 显示完整容器内容
```

特别要求：

> 订单页面必须展示该订单购买时冻结的 ItemSnapshot。

例如商品在玩家购买后被管理员更新：

```text
10:00 商品 = 潜影盒 Snapshot V1
10:01 玩家下单
10:05 商品更新为 Snapshot V2
```

订单详情必须继续显示 V1。

---

## 5.7 后台官方商品管理 `/admin/commerce`

当前已有：

```text
商品管理表格
商品图标
编辑商品入口
移动端商品卡片
```

推荐：

### 桌面表格 / 移动卡片

```text
商品图标 Hover（桌面）
→ 快速预览
```

移动端不依赖 Hover。

### 编辑 / 查看商品

在现有编辑区域中增加只读完整预览：

```text
商品模板
↓
潜影盒 9×3
或
Bundle 内容
```

目的：让管理员能确认该 SKU 当前绑定的具体 Snapshot 内容。

如果以后实现 Snapshot 版本历史，该预览组件应继续复用。

---

## 5.8 后台市场监管 `/admin/market`

当前页面已有实时在售挂单监控表格及物品图标。

该页面以监管和强制下架为主，因此本阶段只做：

```text
物品图标 Hover
→ 快速预览
```

暂不为这里增加大型详情 Dialog 或展开容器内容区域。

---

# 6. 明确不纳入本计划的页面

本计划不包含不存在的页面。

特别是：

```text
Mailbox 页面   ❌ 当前前端不存在
```

虽然后端可能存在 mailbox / delivery fallback 能力，但本计划不因此新增 `/mailbox` 页面，也不为尚未存在的页面设计容器预览。

以下页面本阶段也不需要强行接入：

- `/leaderboard`；
- `/notifications`；
- `/logs`；
- `/help`；
- `/about`；
- `/account`；
- `/admin/users`；
- `/admin/system`；
- `/admin/homepage`；
- 其他不直接展示可交易 ItemStack / ItemSnapshot 的页面。

---

# 7. 组件复用建议

不要复制八份模板代码。

建议最少抽象：

```text
NestedContentsPreview
├─ mode="quick"
├─ mode="detail"
├─ item / itemMeta
└─ readOnly=true
```

内部再根据类型：

```text
SHULKER
→ ShulkerGridPreview

BUNDLE
→ BundleContentsPreview
```

另外可考虑：

```text
ItemPreviewActivator
```

统一处理：

- Hover 延迟；
- Tooltip / Popover 定位；
- 移动端禁用 Hover；
- 空容器状态；
- loading / malformed snapshot 状态。

各页面只负责提供正确的 ItemSnapshot 数据。

---

# 8. 嵌套内容与单一详情窗口原则

如果 Bundle 中还存在可预览 Bundle，不允许出现：

```text
Dialog
└─ Dialog
   └─ Dialog
```

详细预览始终只允许一个交互层。

如果未来支持多级嵌套，使用同一个预览区域切换内容，并显示 breadcrumb：

```text
紫色潜影盒 > 收纳袋 > 收纳袋
```

点击 breadcrumb 返回上一级。

快速 Hover 预览可以只展示第一层，不要求在小浮层中无限递归。

---

# 9. 数据一致性要求

所有页面展示必须满足：

## 背包

```text
显示当前 Snapshot 的内容
```

## 玩家市场 / 拍卖

```text
显示挂单 / 拍卖创建时保存的 ItemSnapshot
```

## 官方商城

```text
显示当前官方商品绑定的 Snapshot
```

## 我的订单

```text
显示下单时冻结的 Snapshot
```

## 后台管理

```text
显示被管理记录自身绑定的 Snapshot
```

任何页面都不得为了渲染预览，临时通过玩家 UUID + slot 去查当前背包。

---

# 10. Codex 推荐实施顺序

```text
Step 1
确认 nested-container-preview-codex-plan.md 中潜影盒 / Bundle 底层数据结构

Step 2
抽取统一 NestedContentsPreview / QuickPreview 前端组件

Step 3
先完成“我的背包”
Hover + 右侧详情

Step 4
接入官方商城 /shop
Hover + 现有 Detail Dialog

Step 5
接入玩家市场 /market
Hover + 现有 Detail Dialog

Step 6
接入拍卖 /auction
Hover + 现有 Detail Dialog

Step 7
接入 /listings
Hover + 现有展开行

Step 8
接入 /orders
Hover + 现有展开行
并确认订单使用购买时 Snapshot

Step 9
接入 /admin/commerce
Hover + 编辑/查看区域完整预览

Step 10
接入 /admin/market
仅 Hover 快速预览

Step 11
桌面 / 移动端回归测试

Step 12
确认没有新建 Mailbox 或其他无关页面
```

---

# 11. 验收标准

- [ ] 只覆盖当前真实存在且展示 ItemStack / ItemSnapshot 的页面；
- [ ] 没有新建 Mailbox 页面；
- [ ] 我的背包支持潜影盒 / Bundle Hover 和右侧详情；
- [ ] 官方商城支持 Hover + 原有 Detail Dialog 完整预览；
- [ ] 玩家市场支持 Hover + 原有 Detail Dialog 完整预览；
- [ ] 拍卖支持 Hover + 原有 Detail Dialog 完整预览；
- [ ] 我的上架 / 我的拍卖复用现有展开行；
- [ ] 我的订单复用现有展开行，并展示购买时冻结 Snapshot；
- [ ] 后台商品管理支持快速预览和完整预览；
- [ ] 后台市场监管只增加轻量 Hover；
- [ ] 没有出现 Dialog 套 Dialog；
- [ ] 移动端不依赖 Hover 才能查看容器内容；
- [ ] 所有预览均为只读；
- [ ] 各页面复用统一 NestedContentsPreview，而不是重复实现；
- [ ] 市场 / 拍卖 / 订单不会重新读取玩家当前背包作为预览来源。

---

# 12. 最终页面覆盖表

| 页面 | 快速预览 | 完整预览位置 |
|---|---|---|
| 我的背包 | Hover | 右侧物品详情 |
| 官方商城 `/shop` | Hover | 现有 Detail Dialog |
| 玩家市场 `/market` | Hover | 现有 Detail Dialog |
| 拍卖 `/auction` | Hover | 现有 Detail Dialog |
| 我的上架 / 我的拍卖 `/listings` | Hover | 现有展开行 |
| 我的订单 `/orders` | Hover | 现有展开行 |
| 后台商品管理 `/admin/commerce` | Hover | 现有编辑/详情区域 |
| 后台市场监管 `/admin/market` | Hover | 本阶段不增加完整预览 |

核心原则：

> **扩展现有页面，不新增页面；统一后端 ItemSnapshot / ItemView，统一前端 NestedContentsPreview，只根据现有页面结构选择 Hover、右侧详情、Detail Dialog 或展开行作为承载位置。**
