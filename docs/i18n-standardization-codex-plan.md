# WebShopX i18n 完善与标准化实施计划书（供 Codex 执行）

> 后端目标仓库：`Cc-Cece/WebShopX`
>
> 后端目标分支：`feat/webshopx-inventory-management`
>
> 前端目标仓库：`Cc-Cece/webshopx-vuetify-web`
>
> 前端目标分支：`feat/webshopx-inventory-management`
>
> 本文件只描述实施方案、迁移边界与验收标准，不直接包含业务功能实现。

---

## 0. 总目标

本任务不是简单“补齐中英文翻译”，而是对 WebShopX 后端插件与 Vue/Vuetify 前端进行一次完整的 i18n 工程化收口。

最终目标：

> **先建立标准 → 全量迁移 → 清理历史不规范 key → 建立自动检查，确保以后新增功能很难再次引入不规范 i18n。**

本轮需要同时解决四类问题：

1. 用户可见文字仍存在硬编码；
2. 历史语言 key 存在 `kxxx`、无语义命名、过度扁平或层级混乱等问题；
3. 当前前端语言资源存在 JSON 与 `src/plugins/i18n.ts` 内联文案并存的混合架构；
4. Crowdin、前端 loader、后端 Locale Center、同步脚本、发布 workflow 对 namespace 的定义存在不一致，缺少真正统一的规范和自动化约束。

完成后，i18n 应成为项目基础设施，而不是靠开发者记忆维护的约定。

---

# 1. 本轮实施原则

## 1.1 不改变业务行为

本任务只做国际化体系重构和文本标准化。

除修复明显的 i18n bug 外，不得借此修改：

- 页面布局；
- 路由；
- 权限；
- 市场/商城逻辑；
- 背包行为；
- API 业务协议；
- 数据库结构；
- 现有玩家资产、订单、商品和通知业务语义。

UI 文案调整只能是：

- 翻译修正；
- 术语统一；
- 可读性修正；
- key 重构后的等价迁移。

不要把本任务演化成页面重写。

## 1.2 两个仓库视为同一个产品

Codex 必须把以下两个目标分支作为同一套系统处理：

```text
Cc-Cece/WebShopX
└─ feat/webshopx-inventory-management

Cc-Cece/webshopx-vuetify-web
└─ feat/webshopx-inventory-management
```

不要以任意仓库的 `main` 分支作为当前实现基准。

所有判断优先以这两个目标分支现状为准。

## 1.3 不保留永久兼容垃圾

历史 key 可以在迁移过程中短暂保留映射，但完成前应删除：

- 旧 `kxxx` key；
- 重复 key；
- 旧路径 alias；
- 已无调用的翻译；
- `legacy*` 形式的长期兼容拼接。

除非确有外部语言包兼容要求，否则不应形成“双轨 key”。

---

# 2. 目标 i18n 架构

## 2.1 Web 前端唯一语言资源目录

正式 Web UI 语言资源统一放置于：

```text
src/i18n/
├─ app/
│  ├─ en-US.json
│  └─ zh-CN.json
├─ admin/
│  ├─ en-US.json
│  └─ zh-CN.json
├─ help/
│  ├─ en-US.json
│  └─ zh-CN.json
└─ market-algorithms/
   ├─ en-US.json
   └─ zh-CN.json
```

当前阶段正式 namespace 固定为：

```text
app
admin
help
market-algorithms
```

Vue runtime 中：

```text
market-algorithms → marketAlgorithms
```

其余名称保持一致。

### 明确废弃

`materials` 不再作为 Web UI i18n namespace。

Minecraft 物品/附魔名称应继续走当前 Minecraft language resource / material locale 体系，不重新塞回普通 UI JSON。

## 2.2 `src/plugins/i18n.ts` 只负责启动配置

完成后 `src/plugins/i18n.ts` 不应继续保存任何大段中英文正文。

允许存在：

- `createI18n(...)`；
- 默认 locale/fallback 配置；
- 从 JSON 构建 bundled messages 的纯结构逻辑；
- 必要类型定义。

不允许存在：

```ts
const helpZh = { ...大量中文... }
const helpEn = { ...大量英文... }
const baseZh = { ... }
const baseEn = { ... }
```

所有用户可见翻译正文必须进入 JSON。

## 2.3 `localeManager` 是语言加载入口

`src/i18n/localeManager.ts` 继续承担：

- bundled locale 发现；
- API locale 发现；
- static manifest locale 发现；
- locale canonicalization；
- fallback；
- runtime 动态加载。

但最终必须保证：

> bundled、API 安装语言包、static manifest 三种来源加载后得到完全相同的 message tree 结构。

不得让内置语言与外部语言走两套不同 schema。

---

# 3. 各层国际化职责边界

## 3.1 Vue / TypeScript

以下均属于 Web UI i18n：

- 页面标题；
- 按钮；
- 标签；
- 表单 placeholder；
- tooltip；
- dialog；
- snackbar/toast；
- empty state；
- 用户可见错误提示；
- 状态标签；
- aria-label；
- 有语义的图片 alt；
- 用户可见帮助说明；
- 前端生成的确认文本。

统一通过：

```ts
t('...')
```

或模板中的等价 Vue I18n 调用。

## 3.2 Java 后端游戏消息

以下必须进入：

```text
src/main/resources/messages/messages.en-US.yml
src/main/resources/messages/messages.zh-CN.yml
```

包括：

- Minecraft 玩家聊天提示；
- 命令反馈；
- 玩家可见错误；
- Bukkit/Paper/Folia 玩家通知；
- 游戏内 mailbox / market / delivery 文本；
- 用户可见的后端通知标题/正文（在当前数据模型下仍由后端生成的部分）。

## 3.3 API 错误

Web 前端需要展示的 API 错误，应逐步以稳定机器码为主：

```json
{
  "code": "inventory_revision_conflict",
  "params": {}
}
```

前端根据 `code` 翻译。

本轮原则：

- 已存在稳定 `code` 的接口，不要再直接依赖英文 `message` 显示；
- 新增/迁移用户可见错误时优先 `code + params`；
- 后端 `message` 可作为日志/兼容诊断文本；
- 不要求本轮为了 i18n 全面重构所有 API payload 或数据库。

## 3.4 日志不做多语言

以下保持英文，不进入 i18n：

- server console；
- debug log；
- exception 诊断；
- 开发者日志；
- CI 输出；
- 内部不可见状态说明。

目标：

> 用户界面多语言，开发日志统一英文。

不要机械地把所有 Java 字符串移进 message bundle。

---

# 4. 标准 key 设计

## 4.1 基本格式

统一采用：

```text
namespace.domain.section.semanticName
```

例如：

```text
app.common.actions.cancel
app.common.actions.confirm
app.navigation.inventory
app.inventory.title
app.inventory.actions.createListing
app.inventory.errors.loadFailed
app.market.empty.direct
admin.navigation.system
admin.localeCenter.title
admin.localeCenter.actions.saveDefault
admin.inventory.offlineWrite.title
help.page.title
help.diagnostics.api.title
marketAlgorithms.fixedPrice.name
```

## 4.2 key 命名规则

必须：

- 使用英文；
- 使用 camelCase；
- 描述语义，而不是当前英文句子；
- 层级清晰；
- 同概念复用；
- 页面级 key 不超过合理深度。

禁止：

```text
k001
k245
text1
msg2
labelA
button3
someText
foo
newTitle
page.title2
```

禁止用英文正文作为 key：

```text
app.ClickHereToContinue
```

## 4.3 消除当前历史结构型 key

现有诸如：

```text
uiText.page.*
uiText.templates.*
uiText.initMeta.*
```

需要逐项判断并迁移为有业务语义的结构。

例如：

```text
app.uiText.page.navInventory
```

应优先迁移为：

```text
app.navigation.inventory
```

而不是简单把旧结构整体改名。

## 4.4 common 复用边界

仅真正跨模块一致的文案放入：

```text
app.common.*
admin.common.*
```

例如：

```text
actions.cancel
actions.confirm
actions.save
states.loading
states.empty
labels.unknown
```

不要为了减少 key 数量，把语义不同但中文碰巧一样的内容合并。

## 4.5 枚举文本

API/business enum 必须保持机器值不翻译：

```text
SHOP_COIN
GAME_COIN
ALIPAY
WECHAT
PUBLISHED
PLAYER
ENDER_CHEST
```

显示层使用独立 label key。

## 4.6 placeholder

统一：

```text
{username}
{count}
{price}
{currency}
{reason}
{message}
```

要求同一 key 在不同 locale 中 placeholder 集合完全一致。

例如：

```json
"loadFailed": "Load failed: {message}"
```

与：

```json
"loadFailed": "加载失败：{message}"
```

必须都包含 `{message}`。

禁止无意义的：

```text
{0}
{1}
{x}
```

除非第三方 API 强制要求。

---

# 5. 术语标准化

Codex 在迁移 key 时同时建立项目术语一致性。

至少检查：

- WebShopX；
- ShopCoin / Web Coin 等当前不同显示方式；
- Game Coin；
- Official Shop；
- Player Market；
- Auction House；
- Listing；
- Buy Order；
- Inventory / Backpack；
- Ender Chest；
- Mailbox；
- Locale / Language；
- Visual Pack；
- Material；
- Snapshot；
- Offline inventory；
- Recovery / rollback 等管理员术语。

原则：

1. 同一业务概念在同一语言中保持同一称呼；
2. 不随意修改已有产品名；
3. 不把内部技术名直接暴露给普通用户；
4. 管理员页面可以比玩家页面更技术化；
5. 英文采用自然 UI 英语，不做逐字直译；
6. 中文采用简洁的软件界面语言。

如果发现现有术语存在业务含义冲突，应优先保持当前产品语义，并在提交说明中列出，不擅自重命名核心货币或业务概念。

---

# 6. 当前已确认的架构不一致

实施时必须优先修复以下已知问题。

## 6.1 Help 仍内联在 `i18n.ts`

当前：

```text
src/plugins/i18n.ts
```

仍包含 `helpZh` / `helpEn`。

目标：

```text
src/i18n/help/zh-CN.json
src/i18n/help/en-US.json
```

并从 `i18n.ts` 删除正文。

## 6.2 `sync-web-frontend.ps1` namespace 不完整

当前同步脚本只复制：

```text
app
admin
market-algorithms
```

需要加入：

```text
help
```

否则后端 `LocaleCenterService` 即使支持 help，也无法读取内置 help JSON。

## 6.3 Localization Release workflow namespace 过时

当前 workflow 校验集合仍存在：

```text
materials
```

并缺少：

```text
help
```

必须统一为正式 namespace：

```text
app
admin
help
market-algorithms
```

## 6.4 Crowdin / Runtime / Build 必须一致

最终以下位置对 namespace 的定义必须一致：

```text
WebShopX/crowdin.yml
WebShopX/tools/sync-web-frontend.ps1
WebShopX/tools/build_l10n_release.py（如有硬编码）
WebShopX/.github/workflows/localization-release.yml
WebShopX/src/main/java/com/webshopx/LocaleCenterService.java
webshopx-vuetify-web/src/i18n/localeManager.ts
webshopx-vuetify-web/LOCALE-PACKAGES.md
webshopx-vuetify-web/scripts/check-i18n.mjs
```

不得再出现某一处支持 `help`、另一处不知道 `help` 的情况。

---

# 7. 阶段一：建立基线清单

正式修改前先扫描两个仓库。

Codex 应生成临时审计结果，至少分类统计：

## 7.1 前端硬编码

扫描：

```text
src/**/*.vue
src/**/*.ts
src/**/*.js
```

识别：

- template text node；
- `label="..."`；
- `title="..."`；
- `placeholder="..."`；
- `aria-label="..."`；
- `alt="..."`；
- snackbar/toast；
- confirm 文本；
- 用户可见 TS string。

分类：

```text
A. 必须 i18n
B. 开发日志，无需 i18n
C. 标识符/协议值，无需 i18n
D. 第三方/Minecraft 固有值，无需普通 UI i18n
```

不要看到中文或英文字符串就全部替换。

## 7.2 后端硬编码

重点扫描：

```text
src/main/java/com/webshopx/**/*.java
```

尤其当前分支新增/大改：

```text
EmbeddedWebServer.java
InventoryService.java
PlayerDataInventoryService.java
InventoryOperationService.java
OfflineInventoryFeatureService.java
MailboxService.java
MarketService.java
OrderService.java
ProductService.java
VisualPackService.java
RuntimeConfigService.java
```

区分：

```text
玩家可见消息 → i18n
Web API 用户错误 → code + 前端翻译优先
日志/异常诊断 → 英文保留
数据库/机器值 → 不翻译
```

## 7.3 历史 key

统计：

- `kxxx`；
- 数字型 key；
- `text*` / `msg*` 等无语义 key；
- `uiText.page.*` 等历史扁平结构；
- 重复语义 key；
- 已无调用 key；
- 只在 zh 或 en 存在的 key；
- placeholder 不一致 key。

不要直接边扫描边大规模替换。

先得到完整迁移范围，再开始按模块迁移。

---

# 8. 阶段二：架构收口

优先完成基础结构，避免后续迁移返工。

执行：

1. 新建完整 `help/en-US.json` 与 `help/zh-CN.json`；
2. 将 `helpZh/helpEn` 完整迁出 `i18n.ts`；
3. 将 `baseZh/baseEn` 中真正属于 UI 的内容迁入 JSON；
4. 清除 `legacyAppZh/legacyAppEn` 这种历史命名；
5. 让 `i18n.ts` 只负责配置/组装；
6. 确认 `localeManager` 能加载全部四个 namespace；
7. 修复 WebShopX 同步脚本；
8. 修复 localization release workflow；
9. 检查 Crowdin、语言包打包器、Locale Center、文档声明一致性。

架构收口完成前，不建议开始全仓 key 大改。

---

# 9. 阶段三：前端硬编码全量迁移

按模块逐步迁移，而不是全仓一次性字符串替换。

建议顺序：

```text
1. App / navigation / settings
2. inventory 及其 components
3. shop / orders / listings
4. market / auction
5. account / notifications / leaderboard
6. admin overview / commerce / market / users
7. admin system / visual packs / locale center
8. help
9. 公共 components / utils
```

每完成一个模块：

- 同时补 `en-US`；
- 同时补 `zh-CN`；
- 同时规范 key；
- 同时删除被替代的旧 key；
- 运行 i18n check。

不要先把所有硬编码抽成新的垃圾 key，最后再统一命名。

正确方式：

> **抽离时直接进入最终规范 key。**

---

# 10. 阶段四：历史 key 标准化

对已有调用做语义级迁移。

## 10.1 迁移映射

Codex 应在工作过程中维护临时映射，例如：

```text
app.uiText.page.navInventory
→ app.navigation.inventory

app.uiText.page.confirmCancelBtn
→ app.common.actions.cancel

admin.uiText.page.localeManagerTitle
→ admin.localeCenter.title
```

映射文件可以是临时工作文件，不要求长期保留。

## 10.2 不允许简单机械改前缀

例如：

```text
uiText.page.xxx
```

不能整体替换为：

```text
page.xxx
```

必须根据真实语义进入对应 domain。

## 10.3 删除废弃 key

完成调用迁移后：

- 删除旧 key；
- 不保留重复 alias；
- 检查无调用 key；
- 重新比较 locale tree。

---

# 11. 阶段五：后端消息标准化

## 11.1 YAML key 规范

后端 message bundle 继续使用语义层级：

```text
command.*
chat.*
notify.*
error.*
```

可按业务继续细分：

```text
command.market.*
command.mailbox.*
chat.delivery.*
notify.market.*
notify.delivery.*
```

不得引入：

```text
message123
text5
k001
```

## 11.2 slot / inventory 等机器语义与显示语义分离

例如后端内部：

```text
ENDER_CHEST
HOTBAR
MAIN_INVENTORY
OFFHAND
ARMOR
```

应保持稳定机器标识。

需要显示给用户时再通过语言层翻译。

不要把中文/英文显示字符串作为 API 稳定标识。

## 11.3 API `ServiceException`

对于前端会直接展示的场景：

优先确保 `code` 可稳定映射到前端翻译。

例如：

```text
bad_request
not_found
inventory_revision_conflict
inventory_slot_changed
feature_disabled
player_online
player_offline
```

不要依赖：

```text
"Locale not found"
"Built-in locale cannot be removed"
```

作为前端业务判断依据。

内部英文 message 可以保留作为诊断 fallback。

---

# 12. 阶段六：重构自动 i18n 检查器

当前前端已有：

```bash
pnpm i18n:check
```

对应：

```text
scripts/check-i18n.mjs
```

本轮应升级为正式质量门。

## 12.1 必须检查

### A. JSON 可解析

所有 locale JSON 必须：

- UTF-8；
- root 为 object；
- 文件名 locale 合法。

### B. namespace 完整

内置：

```text
en-US
zh-CN
```

必须都拥有：

```text
app
admin
help
market-algorithms
```

### C. key tree 一致

`en-US` 与 `zh-CN`：

- key 集合完全一致；
- leaf 类型一致；
- 不允许一边 string、一边 object。

### D. placeholder 一致

例如：

```text
{message}
{count}
{username}
```

集合必须完全相同。

### E. 静态 key 有效

扫描：

```ts
t('...')
$t('...')
te('...')
translate('...')
```

确保静态引用存在。

### F. 无效/废弃 key

输出未被任何静态调用使用的 leaf key。

这里先区分：

- error；
- warning；
- dynamic-key allowlist。

不要因为动态 enum key 导致大量误删。

### G. 用户可见硬编码

建立启发式 scanner，至少识别：

- Vue template 明显中文文本；
- 常见用户可见属性中的英文/中文硬编码；
- snackbar/dialog/confirm 中明显正文。

必须提供 allowlist / ignore 注释机制，避免：

- `WebShopX`；
- Minecraft ID；
- URL；
- enum；
- CSS class；
- console log；
- test fixture；

被误报。

## 12.2 禁止继续解析 `i18n.ts` 中正文

当前检查器存在从 `src/plugins/i18n.ts` 抽取对象字面量的历史逻辑。

架构收口后应删除。

检查器应直接读取 JSON，不再通过 `Function(...)` 解析 TS 中翻译对象。

---

# 13. 后端自动校验

至少新增/增强以下测试。

## 13.1 YAML parse

保留并增强：

```text
MessageBundleYamlTest
```

保证 `messages.en-US.yml` / `messages.zh-CN.yml` 均可解析。

## 13.2 YAML key tree parity

新增测试：

- en/zh key 集合一致；
- list/string 类型一致；
- 不允许一边缺失。

## 13.3 placeholder parity

例如：

```text
{reason}
{count}
{token}
```

必须一致。

## 13.4 namespace contract

建议增加一个轻量测试或共享常量约束，至少验证 Web runtime 所允许 namespace 与构建/发布预期一致。

避免再次出现：

```text
LocaleCenter = help
sync script = no help
workflow = materials
```

这种漂移。

---

# 14. Crowdin 与外部语言包标准

## 14.1 Source locale

继续以：

```text
en-US
```

作为 Crowdin source 文件。

`zh-CN` 仍为内置正式语言，但不改变现有 Crowdin source 约定。

## 14.2 Web language package layout

统一：

```text
web/i18n/app/<locale>.json
web/i18n/admin/<locale>.json
web/i18n/help/<locale>.json
web/i18n/market-algorithms/<locale>.json
```

游戏消息：

```text
messages/messages.<locale>.yml
```

## 14.3 外部 locale fallback

外部 locale 允许只覆盖部分文案时，需要明确策略。

推荐：

```text
requested locale
→ en-US
```

但语言包结构校验仍应优先鼓励完整 key tree。

内置 `en-US` / `zh-CN` 必须 100% 完整。

## 14.4 Locale package schema

打包、安装、API 聚合和前端 loader 必须共用相同逻辑 schema：

```json
{
  "app": {},
  "admin": {},
  "help": {},
  "marketAlgorithms": {}
}
```

---

# 15. 动态 key 规则

允许必要的动态 key，例如：

```ts
t(`app.status.${status}`)
```

但必须满足：

1. 动态值来自受控 enum；
2. 对应完整 key 可被静态定义/验证；
3. 检查器存在明确 allowlist 或 enum 展开规则；
4. 不允许任意 API 字符串直接拼成翻译 key。

优先方式：

```ts
const statusKey = STATUS_I18N_KEYS[status]
t(statusKey)
```

而不是无限制字符串拼接。

---

# 16. 文案质量要求

Codex 不得只保证“有翻译”。

还要检查：

- 英文大小写风格；
- 按钮是否简洁；
- 中英文标点；
- 重复句式；
- 中英文含义是否一致；
- placeholder 前后语义；
- 同一业务术语一致。

例如不要出现同一界面同时混用：

```text
refresh
Refresh
refresh Balance
```

需要统一 UI 风格。

但不要为了语言风格大规模改变业务含义。

---

# 17. 特殊内容规则

以下不应被普通 i18n scanner 误判：

## 17.1 Minecraft 内容

例如：

```text
minecraft:diamond_sword
minecraft:stone
```

为机器标识。

Minecraft 物品本地化继续使用专用材质语言体系。

## 17.2 品牌/平台名

例如：

```text
WebShopX
Minecraft
GitHub
Modrinth
PayPal
Stripe
Alipay
```

不要求翻译。

## 17.3 命令

例如：

```text
/webshopx
/ws
```

命令本身不翻译，但外围说明翻译。

## 17.4 HTML

语言 JSON 中已有少量 HTML 文本。

本轮：

- 可以保留确有需要的受控 HTML；
- 优先减少不必要 HTML；
- 不改变 `warnHtmlMessage` 安全策略前先检查现有渲染方式；
- 不将用户输入拼入不安全 HTML。

---

# 18. 建议实施顺序

严格按以下顺序执行。

## P0：架构一致性

1. 确认两个目标分支；
2. 建立四 namespace 标准；
3. 创建 `help` JSON；
4. 清空 `i18n.ts` 内联正文；
5. 修同步脚本；
6. 修 localization workflow；
7. 修所有 namespace 声明漂移；
8. 确保 bundled/API/static 三条加载链路结构一致。

## P1：现有硬编码迁移

1. 前端全量扫描；
2. inventory 新功能优先；
3. admin-system / locale center；
4. 其它页面；
5. Java 玩家可见文本；
6. API 用户错误映射。

## P2：历史 key 清洗

1. `kxxx`；
2. 无语义 key；
3. `uiText.page` 等历史结构；
4. 重复 key；
5. 无调用 key；
6. placeholder 不一致。

## P3：自动化防回退

1. 重构 `check-i18n.mjs`；
2. YAML parity test；
3. placeholder test；
4. hardcode scanner；
5. dynamic key allowlist；
6. CI 接入。

## P4：最终语言质量审查

1. 中英文术语；
2. 文案自然度；
3. UI 大小写；
4. 标点；
5. 页面切换语言实测。

---

# 19. 不允许的实施方式

Codex 不得：

### 19.1 批量创建垃圾 key

例如把所有文本直接变成：

```text
app.k001
app.k002
app.k003
```

这是本任务明确要消除的问题。

### 19.2 只处理新增 inventory 页面

本次目标是目标分支整体 i18n 标准化，不是只修本次功能。

### 19.3 只处理中文硬编码

英文硬编码同样属于问题。

英文 UI 也必须由语言资源控制。

### 19.4 把日志全部国际化

开发日志统一英文即可。

### 19.5 引入第二套 i18n 框架

继续使用：

```text
vue-i18n
```

不新增另一套翻译库。

### 19.6 用 fallback 掩盖内置语言缺 key

`en-US` / `zh-CN` 两个 built-in locale 必须完整。

fallback 是运行时容错，不是维护手段。

### 19.7 长期保留旧 key alias

迁移完成后清理。

---

# 20. Codex 实施过程要求

## 20.1 先审计后修改

开始前先读：

```text
WebShopX/crowdin.yml
WebShopX/tools/sync-web-frontend.ps1
WebShopX/.github/workflows/localization-release.yml
WebShopX/src/main/java/com/webshopx/LocaleCenterService.java
WebShopX/src/main/resources/messages/

webshopx-vuetify-web/src/plugins/i18n.ts
webshopx-vuetify-web/src/i18n/localeManager.ts
webshopx-vuetify-web/scripts/check-i18n.mjs
webshopx-vuetify-web/LOCALE-PACKAGES.md
webshopx-vuetify-web/src/i18n/**
```

并扫描真实 `$t()/t()` 调用。

## 20.2 小步提交

建议拆分：

```text
1. i18n architecture normalization
2. frontend app/inventory migration
3. admin migration
4. backend message normalization
5. key cleanup
6. validation tooling
7. final wording cleanup
```

不要一个提交混入大量无关格式化。

## 20.3 不做无关代码格式化

避免：

- 全文件 prettier 造成巨大 diff；
- Java 全局 import 重排；
- unrelated rename；
- CSS 重排。

使 diff 聚焦 i18n。

---

# 21. 验收命令

前端至少：

```bash
pnpm install
pnpm i18n:check
pnpm type-check
pnpm build
```

如项目正常脚本为：

```bash
pnpm run i18n:check
pnpm run type-check
pnpm run build
```

以 `package.json` 实际定义为准。

后端至少：

Windows：

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Unix：

```bash
./gradlew test
./gradlew build
```

此外必须执行一次前后端集成同步流程，确认：

```text
WebShopX tools/sync-web-frontend.ps1
```

能够正确携带：

```text
app
admin
help
market-algorithms
```

进入 generated web resources。

---

# 22. 功能验收矩阵

至少人工/浏览器验证：

## 22.1 普通玩家

```text
登录
账户
设置
语言切换
官方商城
玩家市场
拍卖
我的背包
订单
通知
排行榜
帮助
```

## 22.2 管理员

```text
Overview
Commerce
Market
Users
System
Visual Packs
Locale Center
Inventory related admin actions
```

## 22.3 语言行为

测试：

```text
首次访问
浏览器 zh
浏览器 en
LocalStorage 已选择 zh-CN
LocalStorage 已选择 en-US
服务端 default locale
API locale discovery 失败
动态安装第三语言
第三语言缺少部分 key
切换回 built-in locale
刷新页面
```

## 22.4 游戏内

测试主要命令：

```text
/ws help
/ws password
/ws market
/ws mailbox
/ws claim
管理员命令
错误路径
```

确认中英文语言包 placeholder 正常。

---

# 23. 最终验收标准

只有满足以下全部条件，任务才算完成。

## 架构

- [ ] `src/plugins/i18n.ts` 不再保存实际翻译正文；
- [ ] 正式 Web namespace 仅为 `app/admin/help/market-algorithms`；
- [ ] `materials` 不再作为普通 UI locale namespace；
- [ ] help 正式进入 JSON；
- [ ] bundled/API/static message tree 一致；
- [ ] Crowdin、Locale Center、sync、release workflow namespace 一致。

## 资源

- [ ] `en-US` / `zh-CN` key tree 完全一致；
- [ ] placeholder 完全一致；
- [ ] 无 `kxxx` 等无语义 key；
- [ ] 无明显重复 key；
- [ ] 无明显废弃 key；
- [ ] 核心术语统一。

## 前端

- [ ] 用户可见硬编码清理完成；
- [ ] inventory 新页面完整 i18n；
- [ ] admin-system 新功能完整 i18n；
- [ ] aria/placeholder/dialog/snackbar 等纳入 i18n；
- [ ] 不依赖 API 英文 message 作为主要用户文案。

## 后端

- [ ] 玩家可见消息进入 YAML；
- [ ] console/debug 保持英文；
- [ ] YAML en/zh key tree 一致；
- [ ] YAML placeholder 一致；
- [ ] API 机器 code 与用户文案职责分离。

## 自动化

- [ ] `pnpm i18n:check` 通过；
- [ ] `pnpm type-check` 通过；
- [ ] `pnpm build` 通过；
- [ ] Gradle tests 通过；
- [ ] Gradle build 通过；
- [ ] locale release validation 通过；
- [ ] sync-web-frontend 正确复制全部 namespace；
- [ ] 后续新增明显硬编码能够被检查器发现。

---

# 24. 最终交付说明要求

Codex 完成后必须给出简洁总结，至少包括：

```text
1. 新 i18n 架构
2. 迁移了哪些模块
3. 删除了哪些历史结构
4. key 规范
5. 硬编码扫描结果
6. namespace 最终定义
7. 自动检查器新增能力
8. 前端验证结果
9. 后端验证结果
10. 仍存在的已知限制（如有）
```

如果存在因动态 key、第三方字符串、Minecraft 标识符而保留的硬编码，应明确说明原因，而不是宣称“0 hardcoded strings”但实际只是 scanner 没发现。

---

# 25. 一句话执行原则

> **把 WebShopX 的国际化从“项目里存在一些翻译文件”，升级为“资源来源唯一、key 有统一语义、前后端职责明确、第三方语言包结构固定、CI 能主动阻止 i18n 回退”的正式工程体系。**
