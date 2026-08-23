# 唯一任务状态表

> 初始状态。只有对应 PR 合并到 `mod-integration` 后，任务才能标记 `completed`。旧 `.docs` 或历史分支中的状态不继承。

| Task | 状态 | 实施 PR | 审核结论 | Merge commit | 备注 |
|---|---|---|---|---|---|
| PLAN | completed | 当前对话书面批准 | 项目所有者已授权实施 | `b7bfc1b` | 2026-08-22；迁移 base 改为最新 origin/main |
| M0 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | 基线、清单、Paper/Folia 真实 smoke 与干净前端门禁已完成 |
| M1 | in_review | 本地 `feature/M0-main` | 等待统一审核 | — | 七个精确单元真实启动、健康命令、schema、停止均通过 |
| M2 | in_progress | 本地 `feature/M0-main` | — | — | schema、认证、钱包、兑换、管理员、审计、在线状态等已共享；订单/市场/充值/API 编排待迁移 |
| M3 | in_progress | 本地 `feature/M0-main` | — | — | 契约/envelope、原生生命周期/调度/玩家/消息/op 权限/业务命令已完成；背包与原生 item codec 待补 |
| M4 | in_progress | 本地 `feature/M0-main` | — | — | Fabric 三锚点原生生命周期 startup_verified；完整玩家/物品/业务验收待补 |
| M5 | in_progress | 本地 `feature/M0-main` | — | — | Forge 两锚点原生生命周期 startup_verified；完整玩家/物品/业务验收待补 |
| M6 | in_progress | 本地 `feature/M0-main` | — | — | NeoForge 两锚点原生生命周期 startup_verified；完整玩家/物品/业务验收待补 |
| M7 | in_progress | 本地 `feature/M0-main` | — | — | server-only ADR 与转换策略完成；真实 fixture 待补 |
| M8 | in_progress | 本地 `feature/M0-main` | — | — | 一致性原语完成；真实混合集群/Provider 待补 |
| M9 | in_progress | 本地 `feature/M0-main` | — | — | manifest/SBOM/CI 门禁完成；依赖 M4–M8 的 RC 门禁待补 |
| FINAL | pending | — | — | — | 只产生发布建议；main 由所有者决定 |

状态只使用：`pending`、`in_progress`、`in_review`、`changes_required`、`blocked`、`completed`。

## 当前基线备注

- 受控集成基线：本地 `mod-integration`；
- 业务起点：`origin/main@eca332b`；计划叠加：`b7bfc1b`；
- 历史 Mod 原型：`feat/fabric-neoforge-mod`，仅供 M0 只读审计；
- 当前根 `settings.gradle` 已启用七个精确 Mod 子项目与共享模块；
- 工作区外 `.tmp-webshopx-*` 仅为真实 smoke 临时环境，不进入受控源码。
