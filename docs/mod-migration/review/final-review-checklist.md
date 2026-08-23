# 最终 Release PR 审核清单

> 对象：`mod-integration → main`。审核者只有意见权，无批准或操作权。
>
> 2026-08-23 审核结果：Release PR 不存在，M0–M9 统一审核为 `changes_required`。

- [ ] M0–M9 均有 merge commit 和唯一正式审核（只有统一审核，无 merge commit）
- [ ] 所有承诺 Loader/版本精确冻结并达到允许状态（`M1-R01`）
- [ ] Paper/Folia 全基线、Mod 全矩阵和混合集群绿色（`M3-R04`、`M8-R05`、`M9-R06`）
- [ ] 功能等价矩阵无未解释缺口（`M2-R02`、`M4-R03`）
- [ ] 数据 migration、插件↔Mod 切换、回滚和对账已演练（只有合成 SQLite fixture）
- [ ] ItemEnvelope、兼容域和 Modpack 变化文档完整（真实 Mod/嵌套语料未验证）
- [ ] Provider 缺失/故障、Relay/Redis 故障和未知结果可恢复（`M8-R05`、`M9-R07`）
- [ ] 无 P0/P1，无未获所有者接受的 P2/degraded（当前 9 个未关闭 finding）
- [ ] RC 产物、hash、SBOM、许可证、前端 commit 可追溯（`M9-R08`、`M9-R09`）
- [ ] 安装、升级、备份、诊断、回退与支持生命周期文档完整
- [ ] PR 只包含已审核进入 `mod-integration` 的内容（Release PR 不存在）
- [x] 审核者没有 approve/merge/close/rebase/改 base/tag/Release 操作

允许结论：`release_recommended`、`release_not_recommended`、`release_blocked`。
