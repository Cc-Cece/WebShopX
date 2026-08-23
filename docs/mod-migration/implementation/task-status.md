# 唯一任务状态表

> 初始状态。只有对应 PR 合并到 `mod-integration` 后，任务才能标记 `completed`。旧 `.docs` 或历史分支中的状态不继承。

| Task | 状态 | 实施 PR | 审核结论 | Merge commit | 备注 |
|---|---|---|---|---|---|
| PLAN | completed | 当前对话书面批准 | 项目所有者已授权实施 | `b7bfc1b` | 2026-08-22；迁移 base 改为最新 origin/main |
| M0 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | 基线、清单、Paper/Folia 真实 smoke 与干净前端门禁已完成 |
| M1 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | 七个精确单元真实启动、健康命令、schema、停止均通过 |
| M2 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | 共享业务核心、Paper 隔离、SQLite/MariaDB 契约完成 |
| M3 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | 全端口、原生物品、在线/离线背包与幂等发货完成 |
| M4 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | Fabric 三个精确单元为 `full`，真实功能/物品 smoke 通过 |
| M5 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | Forge 两个精确单元为 `full`，真实功能/物品 smoke 通过 |
| M6 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | NeoForge 两个精确单元为 `full`，含 modern data-component smoke |
| M7 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | server-only 与同域 native codec 决策、七单元语料完成 |
| M8 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | MariaDB 双节点、Redis 重启、Provider/capability 完成 |
| M9 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | CI/RC/迁移/回滚/性能/安全完成；内置浏览器实例不可用，见最终报告 |
| FINAL | pending | — | — | — | 只产生发布建议；main 由所有者决定 |

状态只使用：`pending`、`in_progress`、`in_review`、`changes_required`、`blocked`、`completed`。

## 当前基线备注

- 受控集成基线：本地 `mod-integration`；
- 业务起点：`origin/main@eca332b`；计划叠加：`b7bfc1b`；
- 历史 Mod 原型：`feat/fabric-neoforge-mod`，仅供 M0 只读审计；
- 当前根 `settings.gradle` 已启用七个精确 Mod 子项目与共享模块；
- 工作区外 `.tmp-webshopx-*` 仅为真实 smoke 临时环境，不进入受控源码。
