# M9：完整 CI、迁移演练与 Release Candidate

## 目标

把所有承诺支持单元变为可重复验证的发布矩阵，完成部署、插件/Mod 切换、回滚和候选版本交付；不自行进入 `main`。

## CI 与产物

- 冻结精确 Loader/Minecraft/Java/映射/Gradle 版本；
- PR、Mxx、nightly、RC 四级工作流上线；
- 干净缓存编译全部 JAR，校验元数据、资源、服务文件、依赖隔离、许可证和 SHA-256；
- 执行 core、contract、数据库、真实 server smoke、混合集群、Web/API 和安全测试；
- 生成机器可读 readiness、SBOM、支持矩阵和未支持能力报告；
- 前端使用固定 commit 构建并完成关键浏览器路径。

## 演练

- 当前插件旧版本 → 新插件；
- 插件 → Fabric/Forge/NeoForge；
- Mod → 插件；
- 同 Loader 升级、legacy → modern；
- 失败 migration、Provider 缺失、Redis/Relay 故障、未知发货结果；
- 数据库快照/forward-fix 回滚及全量对账。

## 文档

交付管理员安装、升级、兼容域、Modpack 变化、Provider、备份、诊断、回滚和支持生命周期说明；玩家文档说明领取位置限制和可选客户端。

## Release readiness

- 所有承诺单元 `full`，或仅有项目所有者批准的 `degraded`；
- 无 P0/P1、无未批准 P2；
- Paper 与 Mod 全矩阵绿色，flaky 无数据关键项；
- 产物从受控 commit 可重现；
- 最终实现报告列出所有 PR、merge commit、ADR、测试、风险和 hash；
- `mod-integration → main` PR 准备完成。

## 权限

Agent 可以创建 Release PR、上传 CI 内部候选 artifact、发布最终审核意见。不得 approve/merge/close/rebase 该 PR，不得操作 `main`、正式 tag 或正式 Release。等待项目所有者决定。

## 回退

RC 不进入正式渠道。发现问题在新的 `feature/M9-*` 或对应修复分支处理，经规则审核后进入 `mod-integration`，再更新同一 Release PR 来源分支。
