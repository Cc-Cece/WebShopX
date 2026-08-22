# M6：NeoForge 全功能发行线

## 目标

为 NeoForge 1.20.1 至冻结最新版本建立主要现代 Mod 发行线，并与 Fabric/Paper 保持业务契约一致。

## 任务重点

- 精确确认 1.20.1 入口与现代 NeoForge 构建/映射边界；
- 实现生命周期、事件、命令、权限、线程、玩家和资源路径；
- 分别支持传统 NBT 与现代数据组件的无损物品 codec；
- 完成在线/离线发货、标准 GUI/替代路径和 Provider SPI；
- 完成 Web/API、Relay、数据库、Redis、管理后台和所有后台任务；
- 验证 modern Java toolchain、模块化依赖和 shaded 库冲突；
- 对 Loader 版本升级建立自动元数据与启动验证。

## 版本覆盖

至少验证 1.20.1、数据组件边界后的代表版本和 Release 冻结最新版本。任何未被实际启动测试覆盖的版本标记 `unverified`，不得放入正式支持表。

## 验收

- 所有正式功能矩阵通过，包括 Mod 物品、嵌套容器、离线发货与混合集群；
- 传统/现代 codec 都有真实 fixture 与迁移测试；
- 无 WebShopX 客户端模块时核心功能完整；
- 不误依赖 Fabric、Forge 或 Bukkit；
- Paper/Fabric/Forge 已完成能力不回归；
- latest 被精确冻结，不使用无法复现的动态依赖作为 Release 输入。

## 回退

NeoForge 版本族模块可独立禁用；不能通过降级共享数据格式破坏现代物品负载。
