# 最终 Release PR 审核清单

> 对象：`mod-integration → main`。审核者只有意见权，无批准或操作权。

- [ ] M0–M9 均有 merge commit 和唯一正式审核
- [ ] 所有承诺 Loader/版本精确冻结并达到允许状态
- [ ] Paper/Folia 全基线、Mod 全矩阵和混合集群绿色
- [ ] 功能等价矩阵无未解释缺口
- [ ] 数据 migration、插件↔Mod 切换、回滚和对账已演练
- [ ] ItemEnvelope、兼容域和 Modpack 变化文档完整
- [ ] Provider 缺失/故障、Relay/Redis 故障和未知结果可恢复
- [ ] 无 P0/P1，无未获所有者接受的 P2/degraded
- [ ] RC 产物、hash、SBOM、许可证、前端 commit 可追溯
- [ ] 安装、升级、备份、诊断、回退与支持生命周期文档完整
- [ ] PR 只包含已审核进入 `mod-integration` 的内容
- [ ] 审核者没有 approve/merge/close/rebase/改 base/tag/Release 操作

允许结论：`release_recommended`、`release_not_recommended`、`release_blocked`。
