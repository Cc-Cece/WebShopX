# WebShopX 跨平台 Mod 重构执行入口

> 状态：项目所有者已于 2026-08-22 书面授权实施。任何涉及 `main` 的状态变化仍不授权 Agent 执行。

## 1. 目标

在保持现有 Paper/Purpur/Spigot/Folia 插件能力与发布方式稳定的前提下，新增服务端 Mod 发行线：

- Fabric：Minecraft 1.18.2 至项目确认的最新版本；
- Forge：Minecraft 1.18.2 至 1.20.1；
- NeoForge：Minecraft 1.20.1 至项目确认的最新版本；
- 插件与 Mod 在同一仓库同步维护，使用共享业务核心、平台适配器和自动化构建矩阵；
- 下一个正式 Mod Release 以与插件版业务功能等价为发布门槛，中间允许任意数量的任务分支、提交、PR 和预发布验证。

“最新版本”不是浮动构建输入。每个 Release 周期必须在 [02-support-matrix.md](02-support-matrix.md) 中冻结准确的 Minecraft、加载器、映射、Java 和构建插件版本。

## 2. 权威顺序

发生冲突时，按以下顺序解释：

1. 项目所有者对当前工作的明确书面决定；
2. [00-charter.md](00-charter.md) 的安全边界和人工权限；
3. 已接受的 ADR；
4. [08-acceptance.md](08-acceptance.md) 的功能与质量门禁；
5. 对应 `implementation/Mxx-*.md`；
6. 其他说明、建议和历史材料。

任何 Agent 不得用较低层文件覆盖较高层约束。

## 3. 首次接手的阅读顺序

新的程序员 Agent 必须依次阅读：

1. 本文件；
2. `00-charter.md` 至 `11-git-workflow.md`；
3. `implementation/execution-guide.md`、`change-authority.md`、`task-status.md`；
4. 当前 Mxx 任务文件；
5. 当前任务引用的 ADR、代码、测试及历史证据。

新的审核 Agent 必须依次阅读：

1. 本文件；
2. `00-charter.md`、`02-support-matrix.md`、`03-compatibility.md`、`08-acceptance.md`、`11-git-workflow.md`；
3. `review/review-charter.md`、`review/review-protocol.md`、`review/stage-review-focus.md`；
4. 被审核的 Mxx 文件、PR 差异、实现报告和测试证据。

## 4. 当前仓库事实

- 经 ADR-0001 与项目所有者决定，业务基线为最新正式 `origin/main@eca332b`，计划叠加提交为 `b7bfc1b`。
- 当前根工程是单项目 Gradle 构建，`settings.gradle` 仅声明 `WebShopX`。
- Paper 目标由 `targetRuntime` 选择：`1.18.2+`、`1.20.6+`、`26.1+`、`26.2+`。
- 根工程构建会同步相邻的 `webshopx-web` 前端；CI 也会检出独立前端仓库。
- 历史分支 `feat/fabric-neoforge-mod` 已被项目所有者判定过旧；M0 只保存拒绝复用证据，不从中采纳实现。
- 当前工作树中的同名目录属于被 Git 忽略的历史构建残留，不是当前分支的受控源码。
- 旧 `.docs/插件转Mod实施计划书.md` 与本计划目标不同，尤其 Forge 范围、全功能要求和 Git 审核治理不同；它只可作为调查线索，不是执行依据。

M0 必须重新验证所有事实。历史分支中的“完成”标签不自动继承。

## 5. 阶段流程

```text
M0 基线与历史原型审计
 → M1 多模块和构建骨架
 → M2 共享核心抽取与 Paper 等价
 → M3 平台契约与物品信封
 → M4 Fabric 全功能线
 → M5 Forge 全功能线
 → M6 NeoForge 全功能线
 → M7 现代版本与客户端增强决策
 → M8 跨平台数据、Relay、集群和生态适配
 → M9 发布矩阵、迁移、回滚与候选版本
 → 最终发布审核
 → 项目所有者自行决定是否合并 main
```

任务状态只记录在 [implementation/task-status.md](implementation/task-status.md)。文档中写了代码或测试，不等于任务完成；必须同时满足完成定义、证据要求和一次正式审核。

## 6. 关键角色

- 项目所有者：唯一可以批准或执行任何涉及 `main` 的合并、关闭、重开、变基、标签和正式发布操作的人。
- 程序员 Agent：在 `feature/Mxx-*` 分支实现、测试、提交并创建指向 `mod-integration` 的 PR。
- 审核 Agent：独立审核稳定的 Mxx PR；不为建议而建议，每个 Mxx 只做一次正式审核。
- 发布审核 Agent：对 `mod-integration → main` 只发布建议，不批准、不合并、不关闭、不修改 PR 状态。

具体权限见 [11-git-workflow.md](11-git-workflow.md)。

## 7. 完成的含义

单个 Mxx 完成需要：

- 任务文件中的交付物和测试全部完成；
- Paper 基线门禁通过；
- 实现报告、ADR 和可复现证据齐全；
- 唯一一次正式审核结论为 `accepted` 或 `accepted_with_notes`；
- PR 已按规则合并至 `mod-integration`；
- `task-status.md` 已记录 merge commit。

整个项目完成仍不代表可以进入 `main`。最终 Release PR 只能由项目所有者决定。
