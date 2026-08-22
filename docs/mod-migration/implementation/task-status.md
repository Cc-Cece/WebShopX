# 唯一任务状态表

> 初始状态。只有对应 PR 合并到 `mod-integration` 后，任务才能标记 `completed`。旧 `.docs` 或历史分支中的状态不继承。

| Task | 状态 | 实施 PR | 审核结论 | Merge commit | 备注 |
|---|---|---|---|---|---|
| PLAN | in_review | 待创建 | 待项目所有者审核 | — | 当前文档计划 |
| M0 | pending | — | — | — | 等待计划获批与 `mod-integration` 建立 |
| M1 | pending | — | — | — | 依赖 M0 |
| M2 | pending | — | — | — | 依赖 M1 |
| M3 | pending | — | — | — | 依赖 M2 |
| M4 | pending | — | — | — | 依赖 M3 |
| M5 | pending | — | — | — | 依赖 M3；正式完成依赖共享契约冻结 |
| M6 | pending | — | — | — | 依赖 M3；正式完成依赖共享契约冻结 |
| M7 | pending | — | — | — | 依赖 M4/M6 |
| M8 | pending | — | — | — | 依赖 M4–M7 |
| M9 | pending | — | — | — | 依赖 M8 |
| FINAL | pending | — | — | — | 只产生发布建议；main 由所有者决定 |

状态只使用：`pending`、`in_progress`、`in_review`、`changes_required`、`blocked`、`completed`。

## 当前基线备注

- 计划分支：`docs/mod-migration-plan`；
- 计划起点：`fd64592`；
- 历史 Mod 原型：`feat/fabric-neoforge-mod`，仅供 M0 只读审计；
- 当前根 `settings.gradle` 未启用 Mod 子项目；
- 当前本地同名 Mod 目录是被忽略的历史输出，不得当作受控源码提交。
