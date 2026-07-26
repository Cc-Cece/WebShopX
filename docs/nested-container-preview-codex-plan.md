# 潜影盒 / Bundle 嵌套内容预览实施计划书（供 Codex 执行）

> 目标分支：`Cc-Cece/WebShopX: feat/webshopx-inventory-management`
>
> 本文件只描述后续实现方案，不修改现有 V1/V2/V3 计划书。
>
> 实施时必须优先复用当前真实“我的背包”页面及既有 ItemStack / Inventory Snapshot 数据流，不新建第二套 inventory 页面。

---

# 1. 功能目标

在 WebShopX 的“我的背包”页面中，为正常生存模式下真正具有“随 ItemStack 携带内部物品”语义的物品提供只读内容预览。

第一版只正式支持：

```text
可携带容器内容预览
├─ 潜影盒 Shulker Box
│   └─ 固定 27 槽内容预览
│
└─ 收纳袋 Bundle
    └─ 非固定槽位的紧凑内容预览
```

核心目的：

- 玩家无需把潜影盒放置到游戏世界中即可在网页查看内容；
- 玩家可以快速区分多个潜影盒中分别装了什么；
- Bundle 可以直接查看内部物品与数量；
- 内部 ItemStack 继续复用现有物品图标、名称、数量、Lore、附魔等展示能力；
- 整个预览功能只读，不允许通过网页拖动、移动、取出或修改内部物品。

---

# 2. 明确范围

## 2.1 必须支持

### 潜影盒

支持所有正常潜影盒变体，包括原色及各染色版本。

要求按“具有潜影盒语义 / 对应 ItemStack 内部容器数据”统一识别，不要为 16 个颜色分别复制业务逻辑。

UI 必须保留 9 × 3 的 27 槽 Minecraft 风格布局。

### Bundle

支持 Bundle 内部物品预览。

Bundle 不应伪装成 9 × 3 固定容器；应使用紧凑图标网格或列表，并展示容量 / 内容数量等适合 Bundle 的信息。

---

## 2.2 第一版明确不考虑

以下内容不纳入本计划：

- 弩的已装填弹药；
- 蜂箱 / 蜂巢中的蜜蜂；
- 普通箱子；
- 木桶；
- 漏斗；
- 发射器 / 投掷器；
- 熔炉 / 高炉 / 烟熏炉；
- 饰纹陶罐；
- 仅通过命令、数据包或非常规方式制造的奇异 container item；
- 其他正常生存模式中不会作为“随身可携带容器”长期存在的特殊数据。

不要为了理论上的 Item Component 完整覆盖而扩大第一版范围。

---

# 3. 前端交互方案

采用三级信息密度，但始终只保留一个“深度预览层”：

```text
背包格子
   ↓
Hover 快速预览
   ↓
Click 后右侧详情内嵌预览
   ↓
需要时“展开查看”进入单一详细 Dialog
```

重点：

> Hover 是快速判断；右侧详情是主要交互；Dialog 只负责深度查看。

---

# 4. 一级：鼠标 Hover 快速预览

## 4.1 潜影盒

鼠标悬停在潜影盒 ItemStack 上时，显示紧凑 Tooltip / Popover：

```text
紫色潜影盒
物品槽：18 / 27

[ ][ ][ ][ ][ ][ ][ ][ ][ ]
[ ][ ][ ][ ][ ][ ][ ][ ][ ]
[ ][ ][ ][ ][ ][ ][ ][ ][ ]
```

每格只需优先显示：

- 物品图标；
- 数量；
- 空槽；
- 必要时简短物品名 Tooltip。

要求：

- 纯只读；
- 鼠标移走自动关闭；
- 建议增加约 150–250 ms Hover 延迟，避免快速划过背包时频繁闪烁；
- 空潜影盒明确显示“空”；
- 不在 Hover 中展示完整 Lore / 附魔长文本，避免 Tooltip 过大。

## 4.2 Bundle

Hover 使用紧凑内容预览，不模拟固定槽位：

```text
收纳袋
容量：42 / 64

[铁锭×16] [火把×12] [苹果×4]
[橡木木板×10]
```

可以使用紧凑图标网格；如内容很多，可只显示前若干项，并提示：

```text
另有 6 种物品…
```

Hover 的目标是“快速判断里面有什么”，不是完整浏览器。

---

# 5. 二级：当前物品右侧详情内嵌预览

当前真实“我的背包”页面已经存在：

```text
左侧：Minecraft 风格背包
右侧：当前选中物品详情
```

必须在当前右侧详情区域继续扩展，不额外制造新的主页面。

## 5.1 潜影盒

选中潜影盒后，在原有物品属性下方增加：

```text
容器内容                              18 / 27
────────────────────────────────────

[ ][ ][ ][ ][ ][ ][ ][ ][ ]
[ ][ ][ ][ ][ ][ ][ ][ ][ ]
[ ][ ][ ][ ][ ][ ][ ][ ][ ]

                         [ 展开查看 ]
```

内部物品格可以继续使用现有 ItemStack 图标组件。

用户 Hover 内部 ItemStack 时，可显示现有物品 Tooltip，例如：

- display name；
- 数量；
- Lore；
- enchantments；
- durability 等当前已有信息。

## 5.2 Bundle

选中 Bundle 后，右侧建议显示：

```text
收纳袋
容量：42 / 64

内容
────────────────────────
[图标] 铁锭          ×16
[图标] 火把          ×12
[图标] 苹果           ×4
[图标] 橡木木板       ×10

                 [ 展开查看 ]
```

也允许采用紧凑图标网格，但不要展示不存在的固定槽位编号。

---

# 6. 三级：单一详细 Dialog

“展开查看”后可以打开一个较大的详细预览 Dialog。

潜影盒示例：

```text
┌────────────────────────────────────────────┐
│ 紫色潜影盒                                  │
│                                            │
│ [ ][ ][ ][ ][ ][ ][ ][ ][ ]               │
│ [ ][ ][ ][ ][ ][ ][ ][ ][ ]               │
│ [ ][ ][ ][ ][ ][ ][ ][ ][ ]               │
│                                            │
│ 当前选中：锋利 V 钻石剑                     │
│ Lore / Enchantments / 其他已有 Item 属性   │
└────────────────────────────────────────────┘
```

要求：

- 仍然只读；
- 不允许拖拽；
- 不允许取出；
- 不允许修改数量；
- 点击内部物品可以在同一个 Dialog 内显示其详细属性；
- 不因为查看内部物品再弹第二层 Dialog。

Dialog 不是第一版必须阻塞项。如果实现成本较大，可先完成：

```text
Hover 快速预览 + 右侧详情内嵌预览
```

再补“展开查看”。

---

# 7. 嵌套内容：禁止 Dialog 套 Dialog

必须提前支持可扩展的嵌套预览模型。

例如 Bundle 中存在另一个 Bundle：

```text
收纳袋
├─ 铁锭 ×12
├─ 苹果 ×5
└─ 收纳袋
    ├─ 火把 ×16
    └─ 面包 ×3
```

如果用户在详细 Dialog 中点击内部 Bundle：

不要：

```text
Dialog
  ↓
再弹一个 Dialog
  ↓
再弹一个 Dialog
```

应该在同一个 Dialog 内切换当前浏览节点，并使用 breadcrumb：

```text
紫色潜影盒  >  收纳袋  >  收纳袋
```

点击 breadcrumb 返回上一级。

原则：

> 页面中任意时刻最多只有一个 Container Preview Dialog。

右侧详情区域也可以使用相同的内容树 / navigation state，避免为 Dialog 和详情面板维护两套解析逻辑。

---

# 8. 组件设计建议

不要把核心实现命名为只适用于潜影盒的：

```text
ShulkerBoxPreview
```

推荐抽象成类似：

```text
NestedContentsPreview
或
ContainerContentsPreview
```

内部根据内容类型切换 renderer：

```text
SHULKER
  → 27-slot grid renderer

BUNDLE
  → compact/list renderer
```

建议拆分概念：

```text
NestedContentsPreview
├─ ShulkerContentsGrid
├─ BundleContentsPreview
├─ ItemStackCell
├─ ItemStackTooltip
└─ PreviewBreadcrumb
```

具体文件名以当前前端已有组件风格为准，不强制使用上述命名。

---

# 9. 数据层原则

Codex 实现前必须先检查当前 `feat/webshopx-inventory-management` 已有的 Inventory Snapshot / ItemSnapshot 数据结构。

优先复用已有：

- containerItems；
- containerSlot；
- ItemStack metadata；
- displayName；
- material；
- amount；
- lore；
- enchantments；
- fingerprint；
- 当前物品图标解析能力。

如果已有 API 已经返回潜影盒内部 ItemStack，不要新建重复接口。

如果当前 API 只返回浅层摘要，再按最小范围补充嵌套内容字段。

浏览器只负责展示，不应自行解析或信任原始 NBT。

---

# 10. 在线 / 离线一致性

该预览能力应同时适用于：

```text
在线 live inventory snapshot
离线 playerdata inventory snapshot
```

只要现有库存系统已经能够安全返回内部 ItemStack，前端展示应尽量共用同一套结构。

预览本身不执行任何写操作，因此：

- 不修改玩家库存；
- 不触发 withdraw；
- 不触发离线 playerdata safeWrite；
- 不影响现有 revision；
- 不改变市场上架或官方商城逻辑。

如果离线 playerdata 对某种嵌套数据无法可靠解析，应明确显示“不支持预览”，不要返回错误内容。

---

# 11. 与玩家市场 / 官方商城的关系

本功能首先是一个“背包查看能力”，不要和交易动作耦合。

同一个潜影盒预览组件未来应能复用于：

- 我的背包；
- 玩家市场商品详情；
- 拍卖详情；
- 官方商城 Snapshot 商品详情；
- Mailbox 中的 ItemStack；
- 其他展示真实 ItemStack 的页面。

但第一版优先把“我的背包”体验做完整，不要求一次性铺到所有页面。

---

# 12. 移动端 / 无 Hover 设备

Hover 只能作为增强体验，不能成为唯一入口。

桌面端：

```text
Hover → 快速预览
Click → 右侧详情
```

移动端 / 触摸设备：

```text
Tap → 当前物品详情
      → 查看容器内容
```

因此必须保证没有 Hover 时仍能完整查看内容。

不要设计只能依赖 `mouseenter` 才能访问的信息。

---

# 13. 性能要求

背包页面可能同时存在多个装满物品的潜影盒。

不要在所有格子初始化时渲染完整隐藏的 27 格 DOM + 全部深层 Tooltip。

建议：

- 背包主列表只保存已有 snapshot 数据；
- Hover 时按需创建快速预览；
- 右侧只渲染当前选中容器；
- Dialog 只渲染当前打开节点；
- 复杂 Lore / metadata Tooltip 按需渲染；
- 避免因为鼠标快速移动重复做昂贵的数据转换。

若内部内容已经由 API 返回，无需每次 Hover 再请求后端。

---

# 14. 边界情况

至少考虑：

1. 空潜影盒；
2. 装满 27 格潜影盒；
3. 只占部分槽位；
4. 同一种物品不同数量；
5. 带 Lore / enchantment 的内部 ItemStack；
6. 内部为 Bundle；
7. Bundle 为空；
8. Bundle 内容较多；
9. Bundle 内嵌 Bundle；
10. 内部 ItemStack 缺少可显示名称；
11. 图标资源缺失；
12. 在线 Snapshot 与离线 Snapshot 展示一致；
13. 旧版/不支持的嵌套数据不会导致整个 inventory 页面报错；
14. 手机端无 Hover 仍能访问完整内容。

对于异常 ItemStack：

> 单个子物品预览失败时，应降级显示 material / 未知物品占位，不应导致整个潜影盒无法查看。

---

# 15. Codex 推荐执行顺序

```text
Step 1
定位当前实际使用的“我的背包”前端组件
不要凭文件名猜测

Step 2
审查后端 Inventory Snapshot / ItemSnapshot 数据结构
确认当前是否已经返回 containerItems / Bundle contents

Step 3
定义统一 NestedContents 数据模型
只覆盖 Shulker + Bundle

Step 4
实现通用只读 NestedContentsPreview

Step 5
实现 Shulker 9×3 renderer

Step 6
实现 Bundle compact/list renderer

Step 7
接入当前物品右侧详情
这是主要入口

Step 8
实现桌面 Hover 快速预览
保证 Hover 不是唯一入口

Step 9
如工作量合适，实现单一详细 Dialog + breadcrumb
若范围过大可作为后续小步骤，不阻塞基础版本

Step 10
验证在线 / 离线 Inventory Snapshot

Step 11
验证移动端 / 触摸设备

Step 12
类型检查、构建、真实页面 E2E
```

---

# 16. 验收标准

基础版本完成必须满足：

- [ ] 所有正常潜影盒颜色均能统一识别；
- [ ] 潜影盒显示真实 9 × 3 / 27 槽内容；
- [ ] 空槽和物品数量正确；
- [ ] Bundle 能查看真实内部内容；
- [ ] Bundle 不使用伪造的 27 槽布局；
- [ ] Hover 可快速预览；
- [ ] 无 Hover 时仍可通过选中物品完整查看；
- [ ] 当前物品右侧详情已集成容器内容；
- [ ] 内部 ItemStack 能复用现有 Tooltip / 属性显示；
- [ ] 整个功能严格只读；
- [ ] 不影响现有玩家市场、拍卖、官方商城操作；
- [ ] 在线 / 离线 Snapshot 均不会因容器预览报错；
- [ ] 移动端可用；
- [ ] type-check / build 通过。

增强版本可再满足：

- [ ] 单一详细 Dialog；
- [ ] Bundle 嵌套浏览；
- [ ] breadcrumb 返回上级；
- [ ] 不存在 Dialog 套 Dialog。

---

# 17. 最终 UX 定义

最终推荐交互固定为：

```text
普通 ItemStack
└─ 保持现有行为

潜影盒
├─ Hover → 9×3 快速内容预览
├─ Click → 右侧详情中的 27 槽完整预览
└─ 展开查看 → 单一详细 Dialog（可后补）

Bundle
├─ Hover → 紧凑内容预览
├─ Click → 右侧详情中的内容列表 / 图标网格
└─ 展开查看 → 同一个详细 Dialog（可后补）
```

核心原则：

> **第一版只解决正常生存玩家真正会携带的可移动容器：潜影盒和 Bundle。Hover 用于快速找东西，右侧详情用于主要浏览，Dialog 只用于深度查看；所有预览只读。**
