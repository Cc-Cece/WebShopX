# WebShopX V3 Crowdin 双仓库接入计划

> 状态：待实施  
> 日期：2026-07-28  
> 后端：`Cc-Cece/WebShopX@main`  
> 前端：`Cc-Cece/webshopx-vuetify-web@main`

## 1. 现状

WebShopX 已采用双仓库结构：

- `WebShopX` 维护 Minecraft 游戏内消息、Locale Center、语言包打包与发布；
- `webshopx-vuetify-web` 维护 Vue I18n Web 文案；
- 后端发布流程负责合并两端译文并生成统一语言包。

当前后端 `crowdin.yml` 仍按旧结构同时声明游戏 YAML 和 Web JSON，但 Web JSON 已迁移到前端仓库，因此旧配置不能继续使用。

现有可保留能力：

- 后端 `messages/messages.<locale>.yml` 加载与回退；
- 前端四个 namespace：`app`、`admin`、`help`、`market-algorithms`；
- 前端动态语言加载；
- Locale Center 安装和启用语言包；
- `tools/build_l10n_release.py` 生成 ZIP 与 manifest；
- 内置中英文 key、类型和 placeholder 校验。

## 2. 目标架构

使用一个新的 WebShopX V3 Crowdin 项目，统一源语言为 `en-US`。

```text
Crowdin
├── plugin/messages.yml
└── web
    ├── app.json
    ├── admin.json
    ├── help.json
    └── market-algorithms.json
```

资源权威关系：

| 资源 | 权威仓库 |
| --- | --- |
| 游戏消息 YAML | `WebShopX` |
| 四个 Web JSON | `webshopx-vuetify-web` |
| `en-US` | GitHub 源语言 |
| `zh-CN` | GitHub 内置译文 |
| 其他语言 | Crowdin 社区译文 |

Crowdin 不直接写入或自动合并两个仓库的 `main`。

## 3. 唯一实施阶段：重建并切换 Crowdin

### 3.1 清理翻译范围

- 审查后端 `messages.en-US.yml` 和 `messages.zh-CN.yml`；
- 保留玩家或服务器管理员可见消息；
- 将纯控制台诊断、开发日志和内部异常移出 Crowdin 翻译范围；
- 运行后端 YAML key、类型和 placeholder 校验；
- 运行前端 `pnpm i18n:scan`、`pnpm i18n:check`、`pnpm type-check` 和 `pnpm build`。

### 3.2 拆分两个仓库的 Crowdin 配置

后端 `crowdin.yml` 只声明游戏消息：

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

前端新增 `crowdin.yml`，只声明四个 Web namespace：

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

正式上传前必须检查配置和最终路径：

```bash
crowdin config lint
crowdin config sources --tree
crowdin config translations --tree
```

### 3.3 建立 Crowdin V3 项目

- 新建干净项目，不直接沿用旧文件树；
- 设置 `en-US` 为源语言；
- 上传两个仓库当前 `en-US`；
- 上传当前 `zh-CN` 作为已有翻译；
- 从旧项目导出并导入 Translation Memory、术语表和可复用译文；
- 旧项目改为只读，不再参与同步。

### 3.4 建立双仓库上传工作流

两个仓库各自增加源文件上传 workflow：

- 只在本仓库权威源文件变化时触发；
- 上传 `en-US` source；
- 可同步 GitHub 权威的 `zh-CN`；
- 不下载社区译文；
- 不直接提交或修改 `main`；
- Crowdin Action 或 CLI 使用固定稳定版本。

### 3.5 重构统一语言包发布工作流

保留后端作为发布编排器，但替换旧的手写 Crowdin API 下载逻辑：

```text
签出后端
→ 签出前端
→ 按两个 crowdin.yml 分别下载已批准译文
→ 校验 YAML 和 JSON
→ build_l10n_release.py
→ 生成 <locale>.zip 和 manifest.json
→ 发布到 GitHub Release/CDN
```

删除旧 workflow 中依赖历史 Crowdin ZIP 目录结构的：

- 手写 build 请求；
- 状态轮询；
- 整包下载；
- 按旧路径猜测和复制文件。

生产导出应只使用已批准译文；未翻译 key 保留源语言回退，不应因为少量未翻译内容省略整个 namespace 文件。

### 3.6 增加社区语言校验

Crowdin 导出语言必须校验：

- YAML/JSON 可解析；
- 不缺少或增加 source key；
- 叶子类型一致；
- `{placeholder}` 集合一致；
- 四个 Web namespace 齐全；
- locale 文件名合法；
- Minecraft 颜色与格式代码未损坏；
- 语言包目录满足 Locale Center 协议。

### 3.7 完整试运行

先选择一个社区语言，例如 `ja-JP`，验证：

```text
Crowdin 翻译与审批
→ 下载 1 个 YAML 和 4 个 JSON
→ 生成 ja-JP.zip
→ Locale Center 安装
→ 分别启用 Game 和 Web
→ 游戏客户端语言识别
→ 网页动态加载
→ 缺失 key 回退 en-US
```

试运行通过后，再启用定时发布和更多社区语言。

## 4. 验收标准

- Crowdin 中只有 1 个游戏源文件和 4 个 Web 源文件；
- 两个仓库只维护各自拥有的资源；
- `en-US`、`zh-CN` 与 GitHub 当前版本一致；
- Crowdin 不直接修改两个仓库的 `main`；
- 社区语言能够导出完整的 1 YAML + 4 JSON；
- 所有导出内容通过 key、类型、placeholder 和格式校验；
- 现有 `build_l10n_release.py` 能生成有效 ZIP 和 manifest；
- Locale Center 能安装、启用并提供该语言；
- 旧 Crowdin 项目只保留历史资产，不再同步。

## 5. 回滚

切换完成前关闭自动定时发布。若新链路失败：

1. 停止 Crowdin 上传和下载 workflow；
2. 恢复上一个稳定的语言包 Release 与 manifest；
3. 保留内置 `zh-CN` 和 `en-US`；
4. 修复后重新执行单语言完整试运行。
