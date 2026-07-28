# WebShopX i18n 标准化交付说明

## 交付范围

- Web 前端正式 namespace 收口为 `app`、`admin`、`help`、`market-algorithms`。
- `help` 正文已迁入 JSON，`src/plugins/i18n.ts` 仅负责资源组装、locale 选择和 Vue I18n 初始化。
- bundled、Locale Center API、static manifest、同步脚本、Crowdin 和 localization release 使用相同 namespace 与目录结构。
- 玩家端、管理员端、库存、商城、订单、挂单、市场、拍卖、账户、主页编辑器、视觉包和公共组件中的用户可见文本已迁入语言资源。
- `uiText.page.*`、`kxxx`、长期 alias、空 `help.uiText`、废弃 key 和 `materials` UI namespace 已清理。
- 库存槽位和市场来源等 API 展示值改为稳定机器标识，由前端负责本地化；后端诊断信息继续使用英文。

## Key 与资源约束

- Key 使用 `namespace.domain.section.semanticName` 结构和 camelCase 语义名称。
- 两个内置 locale 的 key、叶子类型和命名 placeholder 必须完全一致。
- 动态 key 仅允许使用检查器中声明的有限业务前缀。
- Minecraft 标识、枚举、URL、协议值和作者可编辑的主页初始内容不作为普通 UI 文案迁移；保留项必须在 allowlist 中附带原因。

## 自动化防回退

- 前端 `scripts/i18n-source-scan.mjs` 使用 Vue 编译器和 TypeScript AST 扫描 Vue/TS/JS，输出规则、文件、行、列和原文。
- 前端扫描排除 `src/i18n`、构建目录和依赖目录，并通过 `scripts/i18n-source-allowlist.json` 管理少量有理由的例外。
- `pnpm i18n:check` 阻断硬编码 Han 文本、缺失/额外 key、类型差异、placeholder 差异、不规范 key、缺失静态引用和废弃 key；GitHub Pages CI 在构建前执行该门禁。
- 后端 `tools/check_i18n_sources.py` 扫描生产 Java 字符串，输出精确位置；`checkI18nSources` 已接入 Gradle `check`。`简体中文` 作为 locale 原生名称是唯一 allowlist 项。
- 后端测试继续校验 YAML key、类型和 placeholder parity。

## 最终验证

| 验收项 | 结果 |
| --- | --- |
| `pnpm run i18n:check` | 通过：4 namespace、2 locale、0 unused、0 template/script/AST 扫描命中 |
| `pnpm run type-check` | 通过 |
| `pnpm run build` | 通过 |
| `python tools/check_i18n_sources.py` | 通过：0 命中 |
| `gradlew test` | 通过 |
| `gradlew build` | 通过（包含前端同步、前端构建和源码 i18n 扫描） |
| `tools/sync-web-frontend.ps1` | 通过，四个 namespace 已进入 generated web resources |
| `build_l10n_release.py` | 通过，生成 en-US/zh-CN manifest 与 locale zip |

## 已知限制

- 当前 Codex 会话的 in-app browser runtime 返回空浏览器列表，因此无法在该运行环境中留下真实浏览器点击矩阵证据。编译、资源树、加载链路和发布包验证均已完成；发布前仍建议在具备浏览器运行时的 CI/人工环境执行计划第 22 节的登录、语言切换、第三方 locale 与玩家/管理员页面点击矩阵。
- `src/types/homepage.ts` 是管理员可编辑主页的示例业务数据，未迁入 UI 语言包；未路由的 `src/pages/index.vue`、`src/pages/admin.vue` 和 `*-demo.vue` 作为开发 fixture 保留，并由带理由 allowlist 约束。
