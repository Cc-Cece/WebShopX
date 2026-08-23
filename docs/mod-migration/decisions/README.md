# 架构决策记录

ADR 保存会影响多个任务或后续 Agent 判断的决定。编号单调递增，不删除已接受 ADR；新决定通过 `Superseded by ADR-xxxx` 替代。

状态：`Proposed`、`Accepted`、`Rejected`、`Superseded`。Agent 可在授权范围内接受工程 ADR；涉及 `change-authority.md` 第 3 节的决定只能由项目所有者接受。

最低 ADR 计划：

- ADR-0000：项目范围与治理；
- ADR-0001：M0 基线与历史原型处置；
- ADR-0002：Gradle 模块、多版本和映射策略；
- ADR-0003：平台端口、线程与生命周期；
- ADR-0004：ItemEnvelope 与兼容域；
- ADR-0005：数据库共用与滚动升级；
- ADR-0006：Provider 生态；
- ADR-0007：客户端增强是否实施；
- ADR-0008：CI/发行矩阵与支持生命周期。

当前实际编号以文件名为准：ADR-0004 为 server-only 客户端决定，ADR-0005 为物品兼容域，
ADR-0006 为首批 Provider。早期计划名称不作为已接受决定。
