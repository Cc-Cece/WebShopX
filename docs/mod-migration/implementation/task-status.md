# 唯一任务状态表

> 初始状态。只有对应 PR 合并到 `mod-integration` 后，任务才能标记 `completed`。旧 `.docs` 或历史分支中的状态不继承。

| Task | 状态 | 实施 PR | 审核结论 | Merge commit | 备注 |
|---|---|---|---|---|---|
| PLAN | completed | 当前对话书面批准 | 项目所有者已授权实施 | `b7bfc1b` | 2026-08-22；迁移 base 改为最新 origin/main |
| M0 | in_review | 本地 `feature/M0-main` | `accepted`；见统一审核报告 | — | 尚未合并到 `mod-integration`，因此不标记 completed |
| M1 | changes_required | 本地 `feature/M0-main` | `M1-R01`, `M9-R06` | — | 版本范围与 PR/CI 治理未关闭 |
| M2 | changes_required | 本地 `feature/M0-main` | `M2-R02`, `M4-R03` | — | Paper/Loader 未共享完整业务与 HTTP 契约 |
| M3 | changes_required | 本地 `feature/M0-main` | `M3-R04` | — | 真实 Mod/嵌套物品和玩家发货证据不足 |
| M4 | changes_required | 本地 `feature/M0-main` | `M4-R03`, `M3-R04` | — | Fabric 功能等价和真实物品/发货门禁未通过 |
| M5 | changes_required | 本地 `feature/M0-main` | `M4-R03`, `M3-R04` | — | Forge 功能等价和真实物品/发货门禁未通过 |
| M6 | changes_required | 本地 `feature/M0-main` | `M4-R03`, `M3-R04` | — | NeoForge 功能等价和真实物品/发货门禁未通过 |
| M7 | changes_required | 本地 `feature/M0-main` | `M3-R04` | — | modern 真实 data-component/嵌套语料未关闭 |
| M8 | changes_required | 本地 `feature/M0-main` | `M8-R05` | — | 真实 Paper + Mod 混合集群及崩溃恢复未验证 |
| M9 | changes_required | 本地 `feature/M0-main` | `M9-R06`–`M9-R09` | — | PR/CI、readiness 追溯、SBOM 与浏览器证据未关闭 |
| FINAL | blocked | — | `release_blocked` | — | Release PR 不存在；等待所有 findings 闭环 |

状态只使用：`pending`、`in_progress`、`in_review`、`changes_required`、`blocked`、`completed`。

## 当前基线备注

- 受控集成基线：本地 `mod-integration`；
- 业务起点：`origin/main@eca332b`；计划叠加：`b7bfc1b`；
- 历史 Mod 原型：`feat/fabric-neoforge-mod`，仅供 M0 只读审计；
- 当前根 `settings.gradle` 已启用七个精确 Mod 子项目与共享模块；
- 工作区外 `.tmp-webshopx-*` 仅为真实 smoke 临时环境，不进入受控源码。
