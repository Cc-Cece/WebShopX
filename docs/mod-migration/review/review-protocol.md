# Mxx 正式审核协议

## 1. 接受审核

审核开始前确认：

- PR base 是 `mod-integration`，head 是 `feature/Mxx-*`；
- 程序员声明固定 head SHA；
- 实现报告、任务文件、ADR、CI 和 Paper 证据可访问；
- PR 不处于持续改动状态；
- 该 Mxx 尚无正式审核记录。

缺少以上条件返回 `blocked`，但仍计为本次正式审核启动；补齐后只完成缺失部分和必要检查。

## 2. 审核方法

1. 读权威文档与当前任务；
2. 比较 base...head 全部 diff、commit 和生成/依赖变化；
3. 把每个完成定义映射到代码和证据；
4. 独立复现高风险或代表性测试，不能只相信 PR 描述；
5. 检查 Paper 回归、数据 migration、物品 codec、线程/幂等和回退；
6. 检查范围外变化、秘密、缓存和 Git 权限；
7. 使用标准结论和 finding 格式发布一次报告。

## 3. Finding 格式

每个阻塞 finding 必须包含：

- ID：`Mxx-Rnn`；
- 严重度：P0/P1/P2；
- 违反的具体条款/完成定义；
- 文件与行、commit、测试或可复现路径；
- 实际影响，不作无证据推测；
- 最小关闭条件，不强制审核者偏好的实现。

P3 或可选改进放 `notes`，不得作为 `changes_required`。没有实质 finding 时不应为了显得完整而新增 notes。

## 4. 结论

- `accepted`：无阻塞 finding，完成定义满足；
- `accepted_with_notes`：无阻塞 finding，有少量非强制后续记录；
- `changes_required`：存在可在当前 PR 修复的 P0/P1/未批准 P2 或明确完成缺口；
- `blocked`：缺少环境、证据、前置决定，无法形成可靠判断。

## 5. 闭环

程序员在同一 PR 追加 commit 并逐项回应。审核者只验证 finding 的关闭条件，写 `closed` 或 `still_open`。所有 finding closed 后，原结论转换为 `accepted` 或 `accepted_with_notes`；报告保留原发现和修复 commit。不得重新扫描全部代码寻找一般新问题。

## 6. 证据保存

正式报告优先保存为 PR review；同时将结论、固定 SHA、finding 和闭环摘要归档到 `review/reports/Mxx-review.md` 或由 PR 永久链接，并在 `task-status.md` 引用。
