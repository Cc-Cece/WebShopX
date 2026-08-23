# M0–M9 统一正式审核报告

- Reviewer：Codex 独立审核 Agent
- 日期：2026-08-23（Asia/Shanghai）
- 审核对象：本地 `feature/M0-main`
- Base：`mod-integration@b7bfc1ba452f6d9a83936a75a340942a1d3f075b`
- 冻结 Head：`c88ee7dd2503f756c593ad635798e011a8c29041`
- 结论：`changes_required`

本报告是 ADR-0002 允许的 M0–M9 统一正式审核。它不是 `main` 批准，也不授权任何
Agent 合并、关闭、变基、打标签或发布。

## 审核边界与入口状态

正式审核固定到 `c88ee7d`。审核开始时工作区仍有实现方未提交的 `build.gradle`、
`M9-implementation-report.md`、`task-status.md` 和未跟踪的 `M9-browser-acceptance.md`。这些变化
不属于冻结 Head，本报告只将其作为未冻结证据输入，不将其当作已审代码。

远程仓库只有 `main@eca332b`；没有远程 `feature/M0-main`、`mod-integration`、实施 PR、
Release PR 或对应 CI checks。因此本次能审核本地提交链，但不能证明 PR/CI/合并治理已完成。

## 独立复现

| 检查 | 结果 |
|---|---|
| `.\gradlew.bat releaseReadiness --rerun-tasks --stacktrace` | `BUILD SUCCESSFUL`，50 s，71/71 task 执行 |
| 本地 JUnit XML | 0 failure/error；MariaDB、MySQL、Redis 和 Redis reconnect 共 4 个外部服务测试被条件跳过 |
| Fabric 1.18.2 真实 dedicated server | 当前候选 JAR 启动，`127.0.0.1:8123` 可用，审核进程已停止 |
| Chrome 交互首页 | 通过；标题 `WebShopX`，可见 `Server API is ready.`，无 console warning/error |
| `/health` HTTP | 200；`status=UP`，Fabric 1.18.2/Loader 0.19.3，CSP 正确 |
| Chrome 顶层导航 `/health` | 未通过；被客户端扩展拦截，未冒充为可见页面证据 |
| `gh pr list --state all --head feature/M0-main` | 空集 |
| `git ls-remote --heads origin feature/M0-main mod-integration main` | 仅返回 `main@eca332b` |

当次生成物 SHA-256：

- `release-manifest.json`：`df5eef333c781bfb54605b8e44bfc3c0f8c6946c0fa90281fdb664b4129fe5f9`
- `mod-sbom.spdx.json`：`07ce04f0313c27e302dae9618811273769f88340e0073cd58d2e323537db298d`
- `readiness.json`：`d64bedb8dd2fe14abba5e9b9a10b68e81fb6e9cfc6de7ca5ad6809c63d0e01a5`

## 阶段结论

| Mxx | 结论 | 关联 finding |
|---|---|---|
| M0 | `accepted` | 基线清单、Paper/Folia 历史 smoke 和原型拒绝证据可追溯 |
| M1 | `changes_required` | `M1-R01`, `M9-R06` |
| M2 | `changes_required` | `M2-R02`, `M4-R03` |
| M3 | `changes_required` | `M3-R04` |
| M4 | `changes_required` | `M4-R03`, `M3-R04` |
| M5 | `changes_required` | `M4-R03`, `M3-R04` |
| M6 | `changes_required` | `M4-R03`, `M3-R04` |
| M7 | `changes_required` | `M3-R04` |
| M8 | `changes_required` | `M8-R05` |
| M9 | `changes_required` | `M9-R06`, `M9-R07`, `M9-R08`, `M9-R09` |

## Findings

### M1-R01 — P1 — 未经所有者批准将目标版本范围缩为七个锚点

- 违反：`02-support-matrix.md` 第 1 节、`change-authority.md` 第 3 节。
- 证据：ADR-0003 只冻结 7 个精确锚点，并明言锚点不证明中间版本；
  `support-matrix.json` 也只宣传这 7 行。ADR-0003 未记录项目所有者对缩小范围的书面批准。
- 影响：Fabric 1.18.2–latest、Forge 1.18.2–1.20.1 和 NeoForge 1.20.1–latest 的产品承诺未被完成。
- 最小关闭条件：为范围内版本补齐精确产物/真实 server 证据或 ADR-backed 兼容区间；
  若仍只发布 7 个锚点，需项目所有者明确批准缩小范围。

### M2-R02 — P1 — Paper 与 Loader 未使用同一完整业务/路由编排

- 违反：`M2-core-paper.md` 的共享核心、Paper adapter、HTTP/API/Relay 公共编排与无双写验收。
- 证据：Paper 仍由 `EmbeddedWebServer` 注册 M0 盘点的 136 个 HTTP 路径；Loader 另用
  `SharedHttpApi` 的 10 个 API 路径。`platformContractTest` 只运行一个 `InMemoryInventoryGateway`，
  没有 Paper adapter 或任一真实 Loader adapter 参数化套件。
- 影响：Paper 与 Mod 可以在同一 schema 上产生不同 API、错误码和业务语义，现有门禁无法阻止分叉。
- 最小关闭条件：让 Paper 与 Loader 消费同一用例/路由契约，并对 Paper 和每个 Loader adapter
  运行同一 contract suite。

### M4-R03 — P1 — Mod 发行物不具备章程要求的插件业务功能等价

- 违反：`00-charter.md` 第 2 节、`08-acceptance.md` 第 2/4/7 节、M4–M6 全功能验收。
- 证据：Loader `SharedHttpApi` 只提供登录/会话、钱包查询、商品列表、下单、待发货查询以及
  两个管理写入路径（`SharedHttpApi.java:93-145`）；原生命令只有 health/item probe/balance/
  password/redeem/shop/buy（`ReflectiveHealthCommand.java:99-166`）。市场出售/收购/拍卖、退款、邮箱领取、
  支付创建/查询/回调、会员、排行榜、通知、主题/语言、完整管理后台、静态前端和标准 GUI
  没有 Loader 可发现路径。
- 影响：支持表的 `full` 与真实产品能力不符，用户无法在 Mod 服务端完成多个必验业务。
- 最小关闭条件：按 `08-acceptance.md` 逐域提供完整、可发现的 Web/命令/标准 GUI 路径，
  为每域补成功、拒绝、权限、重试、幂等和恢复证据；或由所有者明确批准改变功能范围。

### M3-R04 — P1 — 真实 Loader 物品与发货 smoke 未覆盖所宣称的语料和玩家路径

- 违反：`08-acceptance.md` 第 4 节，M3–M7 的 Mod 物品、嵌套容器、在线/离线发货和竞态验收。
- 证据：CI dedicated-server job 只执行 `webshopx-health` 和 `webshopx-item-probe`
  （`.github/workflows/mod-migration.yml:191-192`），没有登录玩家、真实背包发货或重启恢复交易。
  `NativeItemCodec.probeCorpus` 只新建 8 个空白原版 registry stack（`NativeItemCodec.java:192-203`）；
  没有第三方 Mod registry ID，也没有真实附魔、药水效果、书页、地图数据、潜影盒内容或嵌套容器。
- 影响：空白原版 stack 通过不能证明 Mod NBT/data component 无损，也不能证明发货不丢失/复制。
- 最小关闭条件：对每个支持单元安装受控 fixture Mod，在真实服务端创建并 round-trip 带数据及
  嵌套物品；用真实玩家或可等价自动化客户端覆盖在线/离线发货、背包满、竞态、重复 operation ID
  和重启恢复。

### M8-R05 — P1 — 混合集群与未知发货结果未在真实跨平台节点上验证

- 违反：`M8-cluster-ecosystem.md` 混合集群场景及验收，`08-acceptance.md` Relay/集群门禁。
- 证据：`MariaDbCommerceIntegrationTest` 只在同一 JVM 创建两个 `DatabaseManager`/
  `SharedCommerceService`；`ClusterSafetyTest` 是内存 router/ledger/deduplicator 测试。现有证据没有启动
  Paper + Fabric/Forge/NeoForge 节点，也没有在获得发货租约后崩溃并恢复真实背包操作。
- 影响：跨平台路由、节点下线、重复/乱序事件和发货 unknown reconciliation 的实际组合仍未验证。
- 最小关闭条件：启动至少一个 Paper 和一个 Mod 真实节点，共享 MariaDB/Redis，复现 M8 列出的
  跨节点业务、兼容域路由、断线/乱序/崩溃/恢复，并对账金额、流水、发货和 envelope hash。

### M9-R06 — P1 — 无可审 PR、远程集成分支、merge commit 和 CI 结果

- 违反：`review-protocol.md` 审核入口，`11-git-workflow.md` Mxx 流程，`M9-release.md` Release readiness。
- 证据：`gh pr list` 对 `feature/M0-main` 返回空集；远程只有 `main@eca332b`；本地
  `mod-integration` 仍停在计划提交 `b7bfc1b`。
- 影响：无法将本地证据绑定到不可变 PR head，也无法核验 Linux CI、MariaDB/Redis、七 server matrix、
  Anchore/Grype 和 CodeQL 是否真实执行。
- 最小关闭条件：冻结并 push 实现 Head，建立指向 `mod-integration` 的可追溯 PR，运行并保存全部
  required checks；关闭其他 findings 后按治理规则合并到 `mod-integration`。

### M9-R07 — P1 — `releaseReadiness` 可在关键外部门禁缺失时成功

- 违反：`M9-release.md` 完整 CI/readiness 要求，`stage-review-focus.md` M9 的“无隐藏 skip”。
- 证据：当次 `releaseReadiness` 成功，但外部 MariaDB/MySQL/Redis 测试有 4 个 skipped，且该 task
  不执行 dedicated-server matrix、依赖/许可扫描或 CodeQL。`verifySupportMatrix` 只校验七个 JSON `full` 行
  与七个构建模块名一致，`generateModReadiness` 不消费外部证据。
- 影响：机器门禁会对尚未完成的候选给出成功退出码和 `full` 矩阵，运营者可能误发。
- 最小关闭条件：让 RC readiness 验证带 commit/SHA/环境的 required-check 证据，对 skipped/missing/stale
  默认 fail closed；未满足时不得将支持行标为 `full`。

### M9-R08 — P1 — 候选证据未冻结且 source commit 标识不完整

- 违反：`review-protocol.md` 固定 head 与稳定 PR 条件，`08-acceptance.md` 证据需求。
- 证据：审核入口工作区含未提交 `build.gradle` 变更；本次生成的 `readiness.json` 会受该变更影响，
  却仍将 `sourceCommit` 写为 `c88ee7d`，也没有 dirty-tree 指纹。
- 影响：同一 `sourceCommit` 可生成不同 readiness，报告无法精确追溯到生成输入。
- 最小关闭条件：提交或撤回实现方未冻结变更，在干净工作树/固定 PR head 重建；
  生成器对 dirty tree 失败或写入可验证 diff 指纹。

### M9-R09 — P2 — SBOM 与交互浏览器证据未达发布门禁

- 违反：`M9-release.md` 依赖/许可/SBOM 及前端浏览器路径要求。
- 证据：`generateModSbom` 只为 7 个最终 JAR 写 package，且 `filesAnalyzed=false`；没有列出
  JAR 内嵌的 HikariCP/Jedis/SQLite/MariaDB/Gson 等组件及其版本/许可。本次 Chrome 首页通过，
  但 `/health` 顶层导航被扩展拦截；只有 HTTP 回应，没有可见页面证据。
- 影响：依赖/许可追溯不完整，实现方自行声明的浏览器门禁仍未全部关闭。
- 最小关闭条件：生成覆盖内嵌/运行时组件、版本、hash 和许可的 SBOM，并在 CI 保存扫描结果；
  在不拦截该路径的审核浏览器中补齐 `/health` 可见内容与 console 证据。

## 闭环规则

实现方应在同一可追溯 PR 追加修复和证据。后续审核只验证上述 finding 的最小关闭条件，
不重新执行第二次全面审核；除非修复直接引入新的 P0/P1 风险。
