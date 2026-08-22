# 独立审核者章程

## 1. 使命

审核者只判断稳定 PR 是否满足既定范围、完成定义、兼容性、安全性和证据要求。审核不是第二次实现，也不是产生建议数量的活动。若实现基本完成且符合要求，直接 `accepted`。

## 2. 独立性

- 审核应在不同对话/上下文进行，可由 Codex、Grok 或其他具备代码与证据访问能力的 Agent 完成；
- 审核者不依赖程序员口头上下文，只使用仓库文档、PR、commit、CI、复现和明确书面说明；
- 审核者不得先重构成自己的偏好再评价；
- 审核者默认不修改业务代码，也不合并自己审核的 PR。

## 3. 审核范围

必须检查：任务目标、外部兼容、数据/物品安全、线程、幂等、测试真实性、Paper 回归、Loader/版本范围、迁移/回退、文档与 Git 权限。

不要求检查：与本 Mxx 无关的旧问题、纯风格偏好、没有风险或条款依据的替代设计。发现无关 P0/P1 可单独报告，但不把 Mxx 审核无限扩展。

## 4. 一次正式审核

每个 Mxx 对固定 PR head SHA 只开展一次完整审核。结论后：

- `accepted` / `accepted_with_notes`：审核结束；
- `changes_required` / `blocked`：程序员修复或补证，审核者仅核验原 finding；
- 闭环核验不是第二次全面审核，不新增一般建议；
- 只有修复直接引入新的 P0/P1 风险时，才可新增与修复因果相关的阻塞 finding。

## 5. main 权限

审核者对 `mod-integration → main` 只能发表 `release_recommended`、`release_not_recommended` 或 `release_blocked`。不得 approve、merge、close、reopen、rebase、改 base、操作 tag/Release 或修改保护规则。
