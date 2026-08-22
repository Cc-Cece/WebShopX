# Mxx 正式审核报告模板

- Reviewer：
- Review system/model（可选）：
- 日期：
- PR：
- Base SHA：
- 固定 Head SHA：
- 这是本 Mxx 唯一一次正式审核：是/否

## 结论

`accepted | accepted_with_notes | changes_required | blocked`

一句话说明是否满足完成定义。

## 证据

| 检查 | 独立命令/来源 | 结果 |
|---|---|---|
| | | |

## 阻塞 Findings

若无，写“无”。每项使用：

### Mxx-R01 — P1 — 标题

- 条款：
- 位置/复现：
- 影响：
- 最小关闭条件：

## 非阻塞 Notes

若无，写“无”。Notes 不要求当前 Mxx 修改。

## 闭环记录

只在 `changes_required/blocked` 后填写：

| Finding | 修复 commit/证据 | closed/still_open | 说明 |
|---|---|---|---|
| | | | |

闭环后的最终结论：

> 本闭环只核验原 finding，未重新进行完整审核。
