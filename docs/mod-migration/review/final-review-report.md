# 最终发布审核报告

- Reviewer：Codex 独立审核 Agent
- 日期：2026-08-23（Asia/Shanghai）
- PR：`mod-integration → main`（不存在）
- Base SHA：`eca332b690f324d3c5fd4c7e39d202ae538262cb`（远程 `main`）
- Head SHA：—（远程 `mod-integration` 不存在）
- 本地被审实现 Head：`c88ee7dd2503f756c593ad635798e011a8c29041`
- RC manifest：`build/reports/mod-migration/release-manifest.json`，SHA-256
  `df5eef333c781bfb54605b8e44bfc3c0f8c6946c0fa90281fdb664b4129fe5f9`

## 发布意见

`release_blocked`

无 Release PR/远程集成分支/CI 证据，且 M0–M9 统一正式审核存在未关闭 P1/P2。
完整 findings 和闭环条件见 [reports/M0-M9-consolidated-review.md](reports/M0-M9-consolidated-review.md)。

该意见不是批准，也不授权任何 Agent 合并、关闭、变基、打标签或发布。

## 阶段与证据汇总

| Mxx | Merge commit | 正式审核 | 关键证据/结果 |
|---|---|---|---|
| M0 | — | `accepted` | `b076e7b`；Paper/Folia 基线 |
| M1 | — | `changes_required` | `6d8dba0`, `92eabe0`；`M1-R01` |
| M2 | — | `changes_required` | `c61a4b2`…`a52200e`；`M2-R02`, `M4-R03` |
| M3 | — | `changes_required` | `1d9ce7d`…`ddbf5ae`；`M3-R04` |
| M4 | — | `changes_required` | Loader 提交链；`M4-R03`, `M3-R04` |
| M5 | — | `changes_required` | Loader 提交链；`M4-R03`, `M3-R04` |
| M6 | — | `changes_required` | Loader 提交链；`M4-R03`, `M3-R04` |
| M7 | — | `changes_required` | `db30eec`；`M3-R04` |
| M8 | — | `changes_required` | `207d09a`, `db30eec`；`M8-R05` |
| M9 | — | `changes_required` | `e96cb77`…`c88ee7d`；`M9-R06`–`M9-R09` |

ADR-0002 只推迟阶段审核，不豁免 PR、CI、merge commit 和最终 Release PR 门禁。

## Release 风险

- 七个 `full` 行未实现插件功能矩阵的多个领域，发布会导致市场、支付、退款、邮箱/领取、配套功能和管理流程不可用。
- 真实 server smoke 未覆盖 Mod/带数据/嵌套物品、玩家发货和混合集群崩溃恢复，仍存在物品丢失/复制风险。
- 本地 readiness 不消费外部门禁，并且由 dirty tree 生成却只记录 HEAD，追溯性不足。
- 版本范围收缩未有所有者书面决定，SBOM 也未列出内嵌依赖与许可。

## 完整性结论

- 功能等价：不通过。
- Paper 回归：本地基线通过，但与 Loader 共享契约不通过。
- Loader/版本矩阵：7 个锚点可构建；完整承诺范围不通过。
- 数据与物品：单元/合成契约有证据；真实 Mod 物品与发货路径不通过。
- 集群与外部集成：不通过。
- 迁移与回滚：合成 SQLite fixture 通过；生产近似匿名快照和平台切换不通过。
- 安全与供应链：本地 API 负向测试通过；远程扫描/CodeQL 无证据，SBOM 不完整。

## 人工决定

当前没有可供所有者合并的 Release PR。等待实现方闭环统一审核 findings，然后由项目所有者
决定是否批准、合并或放弃后续 Release PR。审核 Agent 不执行任何 `main` 状态操作。
