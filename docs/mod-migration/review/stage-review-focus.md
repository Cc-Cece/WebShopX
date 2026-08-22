# 各阶段审核重点

本文件补充通用清单，不扩大任务范围。审核者仍只执行一次正式审核。

## M0

- 基线来自当前分支真实执行，而非旧文档声明；
- 功能、命令、API、schema、构建产物清单没有明显遗漏；
- `feat/fabric-neoforge-mod` 仅只读评估，每个复用候选有 commit 证据；
- characterization/golden 测试没有固化秘密、随机时间或脆弱字段；
- 未改变生产行为。

## M1

- Loader/映射/Java/Gradle 版本可合法解析并固定；
- Paper 原 task、产物和默认构建行为兼容；
- 每个锚点 JAR 在真实 dedicated server 启停；
- core 没有平台依赖，Loader 依赖没有相互泄漏；
- 历史原型不是未经审计的整体合并。

## M2

- 迁移前有行为测试，迁移后 Paper 使用共享 core；
- 没有长期复制两套业务规则或双重注册服务/任务；
- API、schema、错误码、金额和幂等保持一致；
- core 独立运行不触发 Bukkit/Minecraft 类加载；
- 数据库与关键交易对账通过。

## M3

- 每个端口线程、阻塞、错误、重试和生命周期语义完整；
- Paper adapter 运行公共 contract suite；
- envelope 包含来源、codec、hash、原始负载和兼容域；
- 真实物品 fixture 覆盖嵌套、Mod 数据、缺失 Mod 和恶意负载；
- 在线/离线竞态、部分插入和未知结果不会复制或丢失物品。

## M4 Fabric

- 支持表中每个 Fabric 单元有精确构建与启动证据；
- Fabric 原生物品、线程、事件和离线存档实现真实；
- 全业务矩阵通过，不以 HTTP 501 或占位命令计完成；
- 无客户端增强时功能仍完整；
- Paper 同时通过。

## M5 Forge

- 1.18.2–1.20.1 的覆盖声明与真实验证粒度一致；
- Forge 事件/生命周期没有重复处理，Java/库下限正确；
- Forge capability/注册表数据无损，缺失 Mod fail closed；
- 未混入 NeoForge/Fabric 元数据或 API；
- 不因成本把目标内版本标为可选。

## M6 NeoForge

- 1.20.1 与 modern 边界有证据，不混淆 Forge；
- 传统 NBT 与数据组件 codec 均有真实 fixture；
- latest 使用精确冻结版本和干净构建；
- modern Loader 生命周期、物品、离线数据和 Provider 完整；
- 既有 Loader/Paper 不回归。

## M7

- legacy/modern 允许转换是白名单且经过 round-trip；
- 禁止转换保留原负载和可恢复状态；
- 客户端方案基于探针和 ADR，不是偏好；
- 客户端只展示/输入，服务端仍做全部验证；
- 无客户端路径完成同一业务。

## M8

- 混合节点执行真实并发、重复、乱序、断线与崩溃测试；
- 发货租约、operation ID、reconciliation 防重复；
- 不兼容物品只能由兼容节点领取且不会永久丢失；
- Provider 能力声明与实际原子性/故障行为一致；
- 旧 API/Relay 契约和滚动升级门禁正确。

## M9

- CI 覆盖全部承诺单元，不只锚点或 happy path；
- RC 从固定 commit、前端 commit 和干净缓存可重现；
- 插件↔各 Mod 的迁移/回滚使用生产近似匿名快照演练；
- readiness 与实际测试一致，无隐藏 skip/flaky；
- 仅创建 `mod-integration → main` PR 和发布意见，没有任何 main 状态操作。

## FINAL

- 检查 M0–M9 的 merge commit 与正式报告连续且可追溯；
- 检查 Release PR 没有夹带未经过阶段审核的新代码；
- 对完整用户旅程和跨平台故障恢复抽样复验；
- 只输出允许的发布意见，然后停止等待项目所有者。
