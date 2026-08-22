# Git、分支、PR 与权限

## 1. 分支模型

```text
main
└─ mod-integration
   ├─ feature/M0-baseline
   ├─ feature/M1-multimodule
   ├─ feature/Mxx-<bounded-topic>
   └─ review/Mxx-<evidence-only>
```

`main` 是人工控制的正式线；`mod-integration` 是 Agent 可管理的类主分支；所有实施必须在新分支完成。

## 2. 禁止直接提交

- Agent 不得直接 push/commit 到 `main` 或 `mod-integration`。
- Agent 不得强推共享分支、删除远端保护分支或改写已审核历史。
- 文档计划分支也必须通过 PR 进入目标分支。

## 3. Mxx 工作流

1. 从最新 `mod-integration` 创建 `feature/Mxx-*`；
2. 开始前确认工作区与基线，保留他人修改；
3. 使用小而可审查的 Conventional Commit；
4. 完成代码、测试、ADR、实现报告和 task-status 候选更新；
5. push 并创建 `feature/Mxx-* → mod-integration` PR；
6. 冻结审核范围，交由独立审核者正式审核一次；
7. `changes_required` 在同一 PR 追加修复 commit；审核者只闭环原发现；
8. 结论为 `accepted`/`accepted_with_notes` 且 CI 通过后，Agent 可按仓库策略合并至 `mod-integration`；
9. 记录 PR、merge commit 和结论。

审核 Agent默认不合并其自己审核的业务 PR；可由程序员/协调 Agent 在审核结论后执行，以保持职责清晰。

## 4. 审核材料分支

审核者通常使用 PR review 和评论保存结论。如必须把大型复现、审计报告或测试 fixture 入库，使用 `review/Mxx-*` 分支创建只含审核材料的 PR 到 `mod-integration`。审核者不得借此修改业务实现。

## 5. 涉及 main 的绝对限制

对任何以 `main` 为目标的 PR，所有 Agent 仅可：

- 创建 PR；
- 更新来源分支 `mod-integration`；
- 运行只读检查；
- 发布文字审核意见和证据。

所有 Agent 不得：

- approve、merge、close、reopen、convert、rebase 或改变 base；
- push 到 `main`；
- 修改 branch protection；
- 创建、移动或删除正式 tag；
- 创建、发布、编辑或删除正式 Release；
- 用管理员权限绕过检查。

最终审核词汇限定为 `release_recommended`、`release_not_recommended`、`release_blocked`。这些都不是批准。只有项目所有者可以决定与执行进入 `main` 的任何动作。

## 6. 提交与 PR 内容

提交不得混入无关格式化、生成物、秘密或本地缓存。PR 必须列出范围、非范围、风险、迁移、测试命令/结果、产物、ADR、已知限制、Paper 回归证据和回退方式。

## 7. 冲突与更新

功能分支需要同步 `mod-integration` 时优先普通 merge；是否 rebase 由该 PR 的所有者在尚未审核前决定。正式审核开始后不得改写 commit；新变化用追加提交。冲突解决后重新运行受影响门禁，但不自动增加第二次完整审核。

## 8. 紧急情况

Agent 可以在新分支准备 hotfix 和证据，但涉及 `main` 的合并/标签仍只由项目所有者执行。紧急不扩大 Agent 权限。
