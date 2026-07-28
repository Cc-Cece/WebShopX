# WebShopX V3 Crowdin 双仓库接入与迁移计划

> 状态：实施前计划书
>
> 编制日期：2026-07-28
>
> 后端仓库：`Cc-Cece/WebShopX`
>
> 前端仓库：`Cc-Cece/webshopx-vuetify-web`
>
> 目标分支：两个仓库均以 `main` 为当前接入基准

---

## 1. 背景

WebShopX 当前已经从旧版单仓库式资源组织演进为 V3 双仓库架构：

- `WebShopX` 负责 Minecraft 插件、游戏内消息、Locale Center、语言包打包与发布；
- `webshopx-vuetify-web` 负责 Vue 3/Vuetify Web UI，以及四个正式 Web i18n namespace；
- 后端构建和本地化发布流程会签出前端仓库，将两端资源组合成统一语言包。

现有 Crowdin 配置仍保留旧架构假设：后端仓库中的 `crowdin.yml` 同时声明游戏 YAML 和 Web JSON，但当前 Web JSON 已不再位于后端 `src/main/resources/web/i18n/`。因此，旧配置不能通过简单调整路径继续使用，必须按照 V3 的资源所有权、双仓库同步和语言包发布链路重新设计。

本计划中的“V3”指 WebShopX 当前项目架构版本，不等同于 Crowdin REST API、Crowdin CLI 或 GitHub Action 的版本号。实施时应使用当时稳定、受官方支持的 Crowdin CLI/GitHub Action版本，并固定版本或提交 SHA，避免把产品版本与第三方工具版本混为一谈。

---

## 2. 总体目标

建立一套清晰、可审计、可回滚的 Crowdin 本地化体系，使其满足以下目标：

1. 两个 GitHub 仓库分别维护自己拥有的源语言文件；
2. Crowdin 中以一个项目承载 WebShopX 的游戏端和 Web 端翻译；
3. `en-US` 作为统一源语言；
4. `zh-CN` 继续作为内置语言，由 GitHub 代码审查和现有校验器强约束；
5. 其他社区语言主要由 Crowdin 管理；
6. Crowdin 不直接修改两个仓库的 `main`；
7. 后端继续作为统一语言包的构建与发布编排器；
8. 所有 Crowdin 导出语言必须通过 key、类型、placeholder、文件结构和格式校验；
9. 语言包继续兼容现有 Locale Center 安装、启用和运行时加载机制；
10. 旧 Crowdin 项目或旧文件树只用于迁移 Translation Memory、术语表和历史翻译，不再作为 V3 同步源。

---

## 3. 非目标

本轮 Crowdin 接入不包含以下工作：

- 不替换 Vue I18n；
- 不引入另一套前端语言运行时；
- 不重构业务页面、路由、权限或 API；
- 不修改 Locale Center 的产品定位；
- 不将 Minecraft 材质名、附魔名重新并入普通 Web UI namespace；
- 不将开发日志和内部异常全面翻译；
- 不要求所有社区语言立即达到 100% 完成度；
- 不允许 Crowdin 自动合并任何翻译 PR；
- 不在本轮直接开放未经校验的生产定时发布。

---

## 4. 当前架构盘点

## 4.1 后端仓库 `Cc-Cece/WebShopX`

### 4.1.1 游戏内语言资源

当前游戏内消息源位于：

```text
src/main/resources/messages/messages.en-US.yml
src/main/resources/messages/messages.zh-CN.yml
```

主要承担：

- 玩家命令反馈；
- 游戏内 mailbox、claim、market 等提示；
- 玩家可见错误信息；
- 游戏内帮助内容；
- 其他由插件直接发送给玩家的消息。

`MessageService` 当前已支持：

- 从玩家客户端 locale 推导语言；
- 读取插件数据目录中的外部 `messages.<locale>.yml`；
- 在请求语言、`en-US` 和服务器默认语言之间回退；
- 使用 `{username}`、`{count}`、`{reason}` 等命名 placeholder。

### 4.1.2 后端语言校验

`MessageBundleYamlTest` 已检查内置 `en-US` 和 `zh-CN`：

- YAML 可解析；
- key tree 一致；
- 叶子类型一致；
- placeholder 集合一致。

该测试是内置语言的基础门禁，应保留并扩展为社区语言导出的参考标准。

### 4.1.3 Locale Center 与语言包协议

`LocaleCenterService` 当前接受统一语言包中的以下结构：

```text
messages/messages.<locale>.yml
web/i18n/app/<locale>.json
web/i18n/admin/<locale>.json
web/i18n/help/<locale>.json
web/i18n/market-algorithms/<locale>.json
```

它支持：

- 安装 ZIP 语言包；
- 将游戏消息写入插件数据目录；
- 将 Web JSON 写入用户 Web 目录；
- 分别控制 Web 和 Game 是否启用；
- 通过 API 暴露可用语言和 Web messages；
- 保留 `zh-CN`、`en-US` 为 built-in locale。

这套运行时协议已经适合 Crowdin 社区语言，不应为接入 Crowdin而推倒重做。

### 4.1.4 本地化打包脚本

`tools/build_l10n_release.py` 已能：

- 收集后端 `messages.<locale>.yml`；
- 收集前端四个 namespace 下的 `<locale>.json`；
- 按 locale 生成 ZIP；
- 生成 `manifest.json`；
- 记录语言包大小、SHA-256、Web namespace 和下载 URL。

该脚本应继续作为统一语言包输出的权威实现。

### 4.1.5 旧 Crowdin 配置问题

当前后端 `crowdin.yml` 仍声明：

```text
/messages/messages.en-US.yml
/web/i18n/app/en-US.json
/web/i18n/admin/en-US.json
/web/i18n/help/en-US.json
/web/i18n/market-algorithms/en-US.json
```

但后四个 Web source 已不存在于后端仓库对应路径。

因此当前配置存在以下问题：

1. 把后端仓库错误地当作全部翻译源的宿主；
2. 与 V3 双仓库资源所有权冲突；
3. 与前端 `src/i18n/` 的权威目录脱节；
4. 容易将旧版 Crowdin 文件树继续带入 V3；
5. 后续上传源文件时可能删除、覆盖或错配 Crowdin 中的 Web 文件。

### 4.1.6 旧 localization workflow 问题

当前 `.github/workflows/localization-release.yml` 使用手写 `curl`：

- 请求 Crowdin translation build；
- 轮询 build 状态；
- 下载完整 ZIP；
- 假设旧目录结构；
- 再手工复制、重命名并归一化文件。

主要风险：

- 强依赖 Crowdin 内部文件树和导出 ZIP 的历史形态；
- 双仓库配置无法独立验证；
- Crowdin 文件树变化后容易静默复制错误文件；
- YAML、JSON 的导出职责混杂在一个自定义 shell 块中；
- 维护成本高于标准 Crowdin CLI；
- 难以在本地复现 CI 行为。

该下载层应重做，但语言包打包、manifest 和发布能力可以保留。

### 4.1.7 翻译范围边界问题

当前游戏 YAML 中仍可能包含 `console.*` 一类开发者诊断或控制台日志。

根据项目既定原则：

- 玩家和管理员可见界面做多语言；
- 开发日志、调试日志、内部异常和 CI 输出保持英文。

因此正式重新上传 Crowdin 前，应审查游戏 YAML：

- 保留真正面向玩家或服务器管理员的交互消息；
- 将纯开发诊断移回英文代码或专用内部日志资源；
- 避免社区译者翻译无实际用户价值的日志内容。

---

## 4.2 前端仓库 `Cc-Cece/webshopx-vuetify-web`

### 4.2.1 Web 翻译权威目录

当前唯一正式 Web UI 语言资源目录为：

```text
src/i18n/
├── app/
│   ├── en-US.json
│   └── zh-CN.json
├── admin/
│   ├── en-US.json
│   └── zh-CN.json
├── help/
│   ├── en-US.json
│   └── zh-CN.json
└── market-algorithms/
    ├── en-US.json
    └── zh-CN.json
```

正式 namespace 固定为：

```text
app
admin
help
market-algorithms
```

Vue runtime 中：

```text
market-algorithms -> marketAlgorithms
```

### 4.2.2 Vue I18n 初始化

`src/plugins/i18n.ts` 当前只负责：

- 加载内置 `zh-CN`、`en-US`；
- 组装四个 runtime namespace；
- 设置默认语言和 fallback；
- 初始化 Vue I18n。

Crowdin 接入不得向该文件重新塞入翻译正文，也不得引入平行翻译存储。

### 4.2.3 动态语言加载

`src/i18n/localeManager.ts` 当前支持：

- 通过 `import.meta.glob` 发现构建时包含的附加语言；
- 从 Locale Center API 获取可用语言；
- 从静态 manifest 获取语言；
- 动态加载远程 message tree；
- 统一 canonicalize locale；
- 在加载失败时回退到 `en-US`。

这意味着社区语言可以有两种交付方式：

1. 构建时直接进入前端仓库；
2. 通过语言包和 Locale Center 在运行时加载。

V3 初期推荐第二种，避免把大量社区语言长期提交到前端 `main`。

### 4.2.4 当前前端校验

现有 `scripts/check-i18n.mjs` 和 `scripts/i18n-source-scan.mjs` 已覆盖：

- 四个 namespace；
- 内置 `en-US`、`zh-CN`；
- JSON 解析；
- key tree；
- 叶子类型；
- placeholder；
- 静态引用；
- 动态 key allowlist；
- 非语义 key；
- 未使用 key；
- Vue/TS/JS 硬编码 UI 文本。

但当前校验器的 locale 列表固定为：

```text
en-US
zh-CN
```

所以 Crowdin 导出的 `ja-JP`、`de-DE` 等社区语言不会自动接受同等级别的 tree 和 placeholder 检查。

需要增加专门的“导出语言校验模式”，但不应简单把所有社区语言加入内置语言列表，否则会把未计划内置的语言打进默认 Web 构建。

### 4.2.5 当前缺失项

前端仓库目前缺少独立的：

- `crowdin.yml`；
- source upload workflow；
- translation download/validation workflow；
- 社区语言导出校验器。

这导致 Web 翻译仍然间接依赖后端的旧 Crowdin 配置。

---

## 5. 目标资源所有权

两个仓库必须明确分工，不允许同一 source 由两个仓库同时上传。

| 资源 | GitHub 权威仓库 | Crowdin 类型 | 运行时用途 |
| --- | --- | --- | --- |
| `messages.en-US.yml` | `WebShopX` | source | 游戏内消息 |
| `messages.zh-CN.yml` | `WebShopX` | built-in translation | 游戏内中文 |
| `app/en-US.json` | 前端 | source | Web 主应用 |
| `admin/en-US.json` | 前端 | source | 管理后台 |
| `help/en-US.json` | 前端 | source | 帮助内容 |
| `market-algorithms/en-US.json` | 前端 | source | 市场算法 |
| 四个 `zh-CN.json` | 前端 | built-in translation | Web 中文 |
| 其他语言译文 | Crowdin | community translation | 语言包 / 动态加载 |

核心原则：

> 后端只上传游戏 source，前端只上传 Web source，后端发布工作流负责将两边译文重新组合。

---

## 6. 目标 Crowdin 项目结构

推荐新建一个干净的 Crowdin 项目或清空旧项目中的 V2 文件树后重新建立 V3 文件结构。

目标文件树：

```text
/plugin/
└── messages.yml

/web/
├── app.json
├── admin.json
├── help.json
└── market-algorithms.json
```

仅保留五个 source 文件。

这样可以获得以下好处：

- 译者看到的是产品逻辑结构，而不是 GitHub 仓库内部路径；
- source 文件名稳定，不受本地 `en-US` 文件名影响；
- 后续可以按 `/plugin` 和 `/web` 分配标签、负责人和审核流程；
- 旧仓库目录不会继续污染 V3 项目；
- 两个仓库的 `dest` 可明确指向同一个 Crowdin 文件树。

---

## 7. 源语言、内置语言与社区语言策略

## 7.1 源语言

统一使用：

```text
en-US
```

理由：

- 当前 Vue fallback 为 `en-US`；
- 当前后端消息 fallback 为 `en-US`；
- 旧 Crowdin 配置已以英文文件为 source；
- 英文更适合作为多语种翻译的中间源；
- 项目现有 key 和开发规范以英文语义为基础。

## 7.2 内置语言

内置语言继续为：

```text
en-US
zh-CN
```

两个内置语言必须在同一功能变更中同时更新，并通过现有 GitHub 校验。

## 7.3 社区语言

除 `en-US`、`zh-CN` 外：

- 翻译主要在 Crowdin 完成；
- 由 Reviewer/Proofreader 审核；
- 通过发布工作流下载；
- 生成独立语言包；
- 通过 Locale Center 安装或发布；
- 默认不提交回两个仓库的 `main`。

## 7.4 `zh-CN` 的同步策略

V3 初期采用：

```text
en-US：GitHub 是 source 权威
zh-CN：GitHub 是 built-in translation 权威
其他语言：Crowdin 是 translation 权威
```

首次初始化时，可以把现有 `zh-CN` 上传到 Crowdin作为已有译文，但日常同步应避免 Crowdin 自动反向覆盖 GitHub 中的 `zh-CN`。

推荐做法：

- source upload workflow 可选择上传 `zh-CN`；
- 仅在显式手动任务中上传 `zh-CN`；
- 下载社区语言时排除 `zh-CN`；
- 内置中文仍通过正常开发 PR 修改。

---

## 8. 新 Crowdin 配置设计

## 8.1 后端 `WebShopX/crowdin.yml`

目标配置只保留游戏内 YAML：

```yaml
project_id_env: CROWDIN_PROJECT_ID
api_token_env: CROWDIN_PERSONAL_TOKEN
base_path: .
preserve_hierarchy: true

files:
  - source: /src/main/resources/messages/messages.en-US.yml
    dest: /plugin/messages.yml
    translation: /src/main/resources/messages/messages.%locale%.yml
```

要求：

- 删除所有旧 `/web/i18n/...` 条目；
- 不把 token 明文写入仓库；
- `dest` 固定 Crowdin 内部文件路径；
- `%locale%` 保持 BCP 47 风格文件名，例如 `ja-JP`；
- 正式启用前必须执行 config lint 和 tree 检查。

## 8.2 前端 `webshopx-vuetify-web/crowdin.yml`

新增：

```yaml
project_id_env: CROWDIN_PROJECT_ID
api_token_env: CROWDIN_PERSONAL_TOKEN
base_path: .
preserve_hierarchy: true

files:
  - source: /src/i18n/app/en-US.json
    dest: /web/app.json
    translation: /src/i18n/app/%locale%.json

  - source: /src/i18n/admin/en-US.json
    dest: /web/admin.json
    translation: /src/i18n/admin/%locale%.json

  - source: /src/i18n/help/en-US.json
    dest: /web/help.json
    translation: /src/i18n/help/%locale%.json

  - source: /src/i18n/market-algorithms/en-US.json
    dest: /web/market-algorithms.json
    translation: /src/i18n/market-algorithms/%locale%.json
```

要求：

- 四个 namespace 分别声明；
- 不使用广泛 glob 把未知目录上传；
- 不上传 `materials` namespace；
- 不上传 `src/plugins/i18n.ts`；
- 不将 `zh-CN` 声明为 source；
- 保持 `market-algorithms` 的文件系统名称。

## 8.3 配置验证命令

两个仓库都必须能够运行：

```bash
crowdin config lint
crowdin config sources --tree
crowdin config translations --tree
```

此外，在正式上传前运行：

```bash
crowdin upload sources --dryrun --tree
crowdin upload translations --dryrun --tree
crowdin download translations --dryrun --tree
```

验收重点：

- Crowdin 中只会生成五个 source；
- 本地 translation path 与预期一致；
- 后端不会匹配任何 Web JSON；
- 前端不会匹配任何游戏 YAML；
- locale 文件名不会被转换为不兼容格式。

官方参考：

- https://crowdin.github.io/crowdin-cli/configuration
- https://crowdin.github.io/crowdin-cli/commands/crowdin-config
- https://crowdin.github.io/crowdin-cli/commands/crowdin-upload
- https://crowdin.github.io/crowdin-cli/commands/crowdin-download

---

## 9. GitHub Actions 设计

## 9.1 总原则

Crowdin 自动化拆为三个独立职责：

1. 后端上传游戏 source；
2. 前端上传 Web source；
3. 后端编排下载、校验、合并和发布。

禁止将上传和生产发布混成一个不透明 workflow。

## 9.2 后端 source upload workflow

建议新增或重构为：

```text
.github/workflows/crowdin-upload-game.yml
```

触发条件：

```text
push 到 main，且以下文件变化：
- src/main/resources/messages/messages.en-US.yml
- crowdin.yml
- workflow 自身

workflow_dispatch
```

步骤：

1. checkout；
2. 安装或调用固定版本 Crowdin CLI/Action；
3. `crowdin config lint`；
4. `crowdin config sources --tree`；
5. 上传 source；
6. 可选地只在手动输入开启时上传 `zh-CN`；
7. 输出 Crowdin 文件树摘要。

默认：

- 不下载译文；
- 不提交文件；
- 不创建 PR；
- 不写 `main`。

## 9.3 前端 source upload workflow

建议新增：

```text
.github/workflows/crowdin-upload-web.yml
```

触发条件：

```text
push 到 main，且以下文件变化：
- src/i18n/app/en-US.json
- src/i18n/admin/en-US.json
- src/i18n/help/en-US.json
- src/i18n/market-algorithms/en-US.json
- crowdin.yml
- workflow 自身

workflow_dispatch
```

上传前必须先运行：

```bash
pnpm install --frozen-lockfile
pnpm run i18n:scan
pnpm run i18n:check
pnpm run type-check
```

然后：

- lint Crowdin config；
- 上传四个 source；
- 可选手动上传 `zh-CN`；
- 不下载社区语言；
- 不自动创建 PR。

## 9.4 统一 localization release workflow

后端继续维护：

```text
.github/workflows/localization-release.yml
```

但重构为以下步骤：

1. checkout `WebShopX`；
2. checkout `webshopx-vuetify-web` 到固定目录；
3. 安装当前稳定 Crowdin CLI；
4. 使用后端 `crowdin.yml` 下载游戏译文；
5. 使用前端 `crowdin.yml` 下载 Web 译文；
6. 下载时排除内置 `zh-CN`，避免覆盖 GitHub 权威中文；
7. 仅导出已批准译文；
8. 不跳过整个未完全翻译的文件；
9. 校验所有下载结果；
10. 调用 `tools/build_l10n_release.py`；
11. 校验 ZIP 与 manifest；
12. 发布 GitHub Release/CDN 资源；
13. 输出语言包清单和失败原因。

## 9.5 下载参数

生产发布推荐语义：

```text
export only approved: true
skip untranslated strings: false
skip untranslated files: false
```

这样：

- 只采用已批准的社区译文；
- 未批准或未翻译 key 回填英文 source；
- 不会因为一个 namespace 未翻译完而完全缺失该文件；
- 每个发布语言保持完整结构。

实施时对应 CLI 参数应基于当前稳定 CLI 文档确认。

## 9.6 不再保留的实现

重构完成后应删除旧 workflow 中的以下自定义逻辑：

- 手写 Crowdin REST build 请求；
- 手写 build status 轮询；
- 手写 download URL 提取；
- 依赖旧 ZIP 目录层级的 `find`；
- 根据 `en-US` 原文件名猜测目标 locale；
- 把后端 Web 旧目录当作规范化中转目录。

---

## 10. 社区语言导出校验

## 10.1 校验器定位

新增一个不改变内置语言打包逻辑的独立校验器，例如：

```text
WebShopX/tools/validate_crowdin_exports.py
```

由后端 release workflow 调用，同时检查：

- 后端下载目录；
- 前端下载目录；
- locale 完整性；
- 最终语言包输入。

## 10.2 YAML 校验

对每个 `messages.<locale>.yml`：

- 可被 Bukkit `YamlConfiguration` 兼容解析；
- key 不得超出 `messages.en-US.yml`；
- source 中的所有叶子 key 必须存在；
- 叶子类型一致；
- 字符串 placeholder 集合一致；
- 字符串列表类型和列表结构一致；
- 不允许 Crowdin 将 YAML 对象误导出为字符串；
- 检查 Minecraft legacy color code 未被破坏；
- 检查命令、权限节点、URL、ID 等不可翻译 token。

## 10.3 Web JSON 校验

对每个 locale 的四个 JSON：

- JSON 可解析；
- root 是 object；
- 四个 namespace 均存在；
- 不允许额外 namespace；
- key tree 与对应 `en-US` 一致；
- 叶子类型一致；
- placeholder 集合一致；
- 不允许新增未知 key；
- 不允许缺失动态 required key；
- `market-algorithms` 输出目录名保持原样；
- 运行时 message tree 映射后与内置语言一致。

## 10.4 locale 校验

- 文件名必须能 canonicalize；
- 禁止 `_` 与 `-` 混用导致同一语言重复；
- 禁止空 locale；
- 禁止一个语言只存在游戏文件或只存在部分 Web namespace 后仍被标记为完整包；
- manifest locale 必须与 ZIP 文件名一致；
- ZIP 内所有文件必须属于同一 locale。

## 10.5 完整度策略

允许两类语言包：

### 完整语言包

```text
1 个游戏 YAML
4 个 Web JSON
```

可同时启用 Game 和 Web。

### 单端语言包

仅当产品明确支持时允许：

- 只有游戏 YAML；或
- 只有四个 Web JSON。

若保留单端语言包，manifest 必须准确标记：

```json
{
  "messages": true,
  "webNamespaces": []
}
```

Locale Center 默认应只开放实际存在的一端，不得伪装为完整语言。

V3 首次接入建议优先要求完整语言包，减少测试矩阵。

---

## 11. Crowdin 项目配置建议

## 11.1 角色

建议设置：

- Owner：项目所有者；
- Manager：负责集成和语言管理；
- Translator：普通社区翻译者；
- Proofreader：审核并批准译文。

生产导出只读取 approved translation。

## 11.2 文件负责人

建议按目录分配：

```text
/plugin：熟悉 Minecraft、命令和游戏语境的审核者
/web：熟悉 WebShopX UI 和管理后台的审核者
```

## 11.3 标签

可为 source strings 添加标签：

```text
game
web
admin
help
market
placeholder-sensitive
minecraft-formatting
```

标签不是 key namespace 的替代品，只用于 Crowdin 管理、筛选和 AI/TM 上下文。

## 11.4 术语表

至少建立以下术语：

- WebShopX；
- Minecraft；
- mailbox；
- claim；
- listing；
- market；
- auction；
- shop coin；
- game coin；
- inventory；
- Ender Chest；
- Hotbar；
- Offhand；
- locale；
- language pack；
- server；
- player；
- administrator。

每个术语注明：

- 是否翻译；
- 推荐中文；
- 禁止译法；
- 所属上下文；
- 大小写要求。

## 11.5 上下文和截图

优先为容易歧义的 Web 文案增加：

- 页面路径；
- component 名；
- 功能说明；
- placeholder 含义；
- 截图或 UI context。

禁止让 AI 或社区译者仅根据孤立英文短语猜测业务含义。

---

## 12. V2 到 V3 迁移方案

## Phase 0：冻结旧同步

1. 关闭旧 Crowdin 定时下载；
2. 禁止旧 workflow 继续向生产语言包写入；
3. 记录旧项目 ID、文件树和目标语言；
4. 导出旧 Translation Memory；
5. 导出旧 glossary；
6. 备份旧译文 ZIP；
7. 将旧 Crowdin 项目标记为只读或 archived。

验收：旧系统不再自动影响 GitHub 和语言包发布。

## Phase 1：清理 GitHub 源资源

后端：

- 审核 `messages.en-US.yml`、`messages.zh-CN.yml`；
- 移出纯内部 `console.*` 日志；
- 校验 key、类型、placeholder；
- 检查命令和 Minecraft 格式码。

前端：

- 运行 `pnpm run i18n:scan`；
- 运行 `pnpm run i18n:check`；
- 运行 `pnpm run type-check`；
- 运行 `pnpm run build`；
- 确认四个 namespace 无历史废弃 key。

验收：五个英文 source 可作为稳定 V3 baseline。

## Phase 2：创建 V3 Crowdin 文件树

1. 创建新项目，或清理旧项目后建立新根目录；
2. 设置 source language 为 `en-US`；
3. 配置目标语言；
4. 创建 `/plugin` 与 `/web`；
5. 两个仓库分别 dry-run；
6. 上传五个英文 source；
7. 检查 Crowdin 中没有重复文件；
8. 检查文件 ID 和路径稳定。

验收：Crowdin 中只存在计划内五个 V3 source。

## Phase 3：导入内置中文

1. 使用两个仓库当前 `zh-CN` 作为已有翻译；
2. 分别上传到对应 source；
3. 不覆盖英文 source；
4. 人工检查 placeholder；
5. 将现有中文标记为 approved；
6. 禁止日常 download 任务反向覆盖 GitHub 中文。

验收：Crowdin 中 `zh-CN` 与 GitHub 当前内置中文一致。

## Phase 4：迁移历史翻译资产

1. 导入旧 TM；
2. 导入 glossary；
3. 使用 TM 对新 source 预翻译；
4. 只自动批准 perfect match；
5. 对结构重构、key 改名、语义变化的内容人工审核；
6. 不直接用旧 V2 文件覆盖 V3 source 或译文。

验收：可复用旧翻译，但不继承旧文件树和错误映射。

## Phase 5：建立 source upload workflows

1. 后端上线游戏 source upload；
2. 前端上线 Web source upload；
3. 配置同一 Crowdin project ID；
4. 配置最小权限 token；
5. 运行手动测试；
6. 验证两个 workflow 不互相删除文件。

验收：任一仓库修改自己的英文 source 后，Crowdin 只更新对应文件。

## Phase 6：重构 localization release

1. 引入标准 Crowdin CLI 下载；
2. 删除手写 API build/download；
3. 后端下载游戏译文；
4. 前端 checkout 下载 Web 译文；
5. 排除内置中文；
6. 运行社区语言校验；
7. 使用现有打包脚本输出 ZIP 和 manifest；
8. 只通过 `workflow_dispatch` 试运行。

验收：下载结果不依赖旧 Crowdin ZIP 层级。

## Phase 7：单语言试点

优先选择一个测试语言，例如：

```text
ja-JP
```

测试完整链路：

1. Crowdin 翻译并批准；
2. 导出 1 个 YAML + 4 个 JSON；
3. 校验；
4. 生成 `ja-JP.zip`；
5. 生成 manifest；
6. 上传语言包；
7. Locale Center 安装；
8. 分别启用 Game 与 Web；
9. 验证 Minecraft 客户端 locale；
10. 验证网页语言选择；
11. 验证 API 动态加载；
12. 验证缺失 key 回退英文；
13. 验证管理员页面、玩家页面和帮助页面。

验收：试点语言端到端通过。

## Phase 8：恢复生产自动化

1. 保留手动发布入口；
2. 观察至少一个完整发布周期；
3. 再启用 schedule；
4. schedule 只发布 approved translation；
5. 失败时不得覆盖上一版 Release；
6. 记录发布版本、Crowdin build/commit 和 SHA-256。

验收：定时发布可重复、可审计、可回滚。

---

## 13. Secrets、Variables 与权限

建议两个仓库配置：

### Secrets

```text
CROWDIN_PERSONAL_TOKEN
```

### Variables 或 Secrets

```text
CROWDIN_PROJECT_ID
```

### 后端跨仓库读取

```text
WEBSHOPX_WEB_READ_TOKEN
```

要求：

- Crowdin token 使用专用账号或专用 token；
- 只授予目标项目所需权限；
- 不使用个人长期全局高权限 token；
- 不在日志中输出 token；
- fork PR 不允许访问 secrets；
- source upload 仅在受信任的 `main` push 或手动任务运行；
- release workflow 的 `contents: write` 仅用于发布资源；
- 前端 checkout token 只读。

---

## 14. 分支与 PR 策略

## 14.1 Crowdin 不直写 main

禁止：

```text
Crowdin -> main
```

允许的模式：

```text
Crowdin -> 临时下载目录 -> 校验 -> 语言包 Release
```

内置语言需要回写仓库时：

```text
Crowdin -> l10n 分支 -> PR -> CI -> 人工合并
```

## 14.2 社区语言默认不进入主仓库

社区语言数量增加后，把所有译文提交到两个主仓库会产生：

- 高频噪声 PR；
- 巨大 diff；
- 合并冲突；
- 版本发布与翻译发布耦合；
- 构建产物膨胀。

因此默认通过 Release/Locale Center 分发更符合当前 V3 架构。

---

## 15. 发布与版本策略

## 15.1 语言包版本

继续使用独立于插件版本的 l10n 版本，例如：

```text
l10n-2026.07.28-01
```

manifest 应记录：

- schemaVersion；
- version；
- generatedAt；
- defaultLocale；
- locale；
- name；
- nativeName；
- packageUrl；
- sha256；
- size；
- messages 是否存在；
- webNamespaces。

## 15.2 不覆盖最后可用版本

新发布前必须完成：

- Crowdin 下载；
- 结构校验；
- ZIP 校验；
- hash 校验；
- manifest 校验。

任一步失败：

- workflow 失败；
- 不覆盖旧 Release；
- 不更新 stable manifest；
- 不让 Locale Center 获取半成品。

## 15.3 可追溯性

建议在 workflow summary 中记录：

- 后端 commit SHA；
- 前端 commit SHA；
- Crowdin project ID；
- Crowdin branch（如使用）；
- 导出模式；
- 导出语言；
- 每个 ZIP 的 SHA-256；
- 发布 tag。

---

## 16. 回滚方案

## 16.1 source upload 回滚

若新配置上传路径错误：

1. 立即禁用两个 upload workflow；
2. 不运行 `--delete-obsolete`；
3. 恢复 Crowdin 备份；
4. 修正 `dest`；
5. dry-run 验证；
6. 再进行手动上传。

初次接入阶段禁止自动删除 obsolete source。

## 16.2 release workflow 回滚

若新下载流程失败：

- 恢复旧 workflow 文件；
- 但旧 workflow 只允许手动运行；
- 继续使用上一个稳定语言包 Release；
- Locale Center 不改变已安装语言。

## 16.3 翻译内容回滚

- 每次语言包使用不可变 tag；
- 不覆盖同名 ZIP；
- 保留上一版本 manifest；
- Locale Center 可重新安装上一版本语言包。

---

## 17. 风险清单

| 风险 | 影响 | 缓解措施 |
| --- | --- | --- |
| 两仓库同时删除 Crowdin 文件 | source 丢失 | 固定 `dest`，初期禁用 delete obsolete |
| `%locale%` 与应用 locale 不一致 | 无法加载 | dry-run、canonicalize、单语言试点 |
| Crowdin 覆盖 `zh-CN` | 内置中文回退 | 下载时排除中文，中文仍走开发 PR |
| 未批准译文进入生产 | 质量问题 | production 仅导出 approved |
| 跳过未完成文件 | namespace 缺失 | 不启用 skip untranslated files |
| YAML 格式码被译坏 | Minecraft 显示异常 | 增加格式码与 placeholder 校验 |
| Web JSON 少 key | 页面显示 key 或英文混杂 | 与 `en-US` 全树校验 |
| 旧 TM 错配新语义 | 错译 | 仅自动批准 perfect match，人工审核 |
| 社区语言写入 main 造成噪声 | 维护成本高 | 默认 Release/Locale Center 分发 |
| 自定义 API 下载再次绑定结构 | 后续升级困难 | 使用标准 CLI/config 下载 |
| console 日志进入 Crowdin | 无效翻译工作 | 上传前清理翻译范围 |
| 私有仓库 token 权限过高 | 安全风险 | 专用最小权限 token |

---

## 18. 实施任务拆分

## P0：必须完成后才能启用 Crowdin V3

- [ ] 冻结旧 Crowdin 定时同步；
- [ ] 备份旧 TM、glossary 和译文；
- [ ] 清理后端翻译范围；
- [ ] 后端 `crowdin.yml` 删除 Web 条目；
- [ ] 前端新增独立 `crowdin.yml`；
- [ ] 两个配置通过 lint/tree/dry-run；
- [ ] Crowdin 建立五个 V3 source；
- [ ] 两仓库 source upload workflow 上线；
- [ ] release workflow 改为标准 CLI 下载；
- [ ] 社区语言校验器上线；
- [ ] `zh-CN` 不被下载流程覆盖；
- [ ] 单一测试语言端到端通过。

## P1：生产发布前建议完成

- [ ] Crowdin glossary；
- [ ] 文件标签；
- [ ] proofreader 审核流程；
- [ ] workflow summary 和 SHA 追踪；
- [ ] 语言完整度报告；
- [ ] Locale Center 安装/启用自动化测试；
- [ ] Web 玩家端与管理员端点击测试；
- [ ] Minecraft 客户端 locale 测试矩阵。

## P2：后续增强

- [ ] 自动上传 UI 截图；
- [ ] 自动同步 source string context；
- [ ] 语言贡献者排行榜或致谢；
- [ ] 翻译完成度徽章；
- [ ] 语言包签名；
- [ ] stable/beta 两套语言包频道；
- [ ] Crowdin webhook 触发按需构建；
- [ ] 伪本地化测试；
- [ ] RTL 语言布局验证。

---

## 19. 验收标准

只有同时满足以下条件，才能视为 Crowdin V3 接入完成：

1. Crowdin 中只有五个计划内 source 文件；
2. 后端只能上传游戏 YAML；
3. 前端只能上传四个 Web JSON；
4. 两仓库修改英文 source 后不会互相覆盖或删除文件；
5. `en-US`、`zh-CN` 继续通过现有内置语言校验；
6. Crowdin 导出语言通过 YAML/JSON 全树校验；
7. placeholder、类型和格式码全部一致；
8. 每个完整语言包包含 1 YAML + 4 JSON；
9. `build_l10n_release.py` 能生成 ZIP 和 manifest；
10. Locale Center 能安装并识别测试语言；
11. 游戏端能根据玩家 locale 使用测试语言；
12. Web 端能通过 API 或 static manifest 动态加载测试语言；
13. 缺失或未批准字符串正确回退到 `en-US`；
14. Crowdin 不直接写入 `main`；
15. 新工作流不再依赖旧 Crowdin ZIP 目录结构；
16. 任一步失败不会覆盖上一版可用语言包；
17. 旧 V2 Crowdin 项目不再参与自动同步。

---

## 20. 建议实施顺序

推荐严格按照以下顺序执行：

```text
1. 冻结旧工作流
2. 清理五个 source
3. 重写两个 crowdin.yml
4. 本地 lint/tree/dry-run
5. 建立 Crowdin V3 文件树
6. 首次上传 source
7. 上传当前 zh-CN
8. 导入 TM 和 glossary
9. 上线两个 source upload workflow
10. 编写社区语言校验器
11. 重构 localization release workflow
12. ja-JP 单语言试点
13. Locale Center 端到端验收
14. 恢复手动生产发布
15. 观察后再恢复 schedule
```

不要先打开定时同步，再补校验和回滚。

---

## 21. 预期最终架构

```text
                     Crowdin: WebShopX V3
                  source language: en-US
                              │
             ┌────────────────┴────────────────┐
             │                                 │
Cc-Cece/WebShopX                    Cc-Cece/webshopx-vuetify-web
messages.en-US.yml                  app/en-US.json
                                    admin/en-US.json
                                    help/en-US.json
                                    market-algorithms/en-US.json
             │                                 │
             └────────────────┬────────────────┘
                              │
             WebShopX localization-release workflow
                              │
                 下载 approved community translations
                              │
              YAML / JSON / key / placeholder validation
                              │
                  tools/build_l10n_release.py
                              │
                    <locale>.zip + manifest.json
                              │
              GitHub Release / CDN / Locale Center
                              │
                Minecraft Game + Vue Web runtime
```

该架构保持：

- GitHub 是源代码和内置语言的权威；
- Crowdin 是社区翻译协作平台；
- CI 是翻译质量门禁；
- Release 是社区语言分发渠道；
- Locale Center 是最终安装与启用入口。
