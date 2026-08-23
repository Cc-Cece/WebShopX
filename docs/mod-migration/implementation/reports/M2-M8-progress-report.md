# M2–M8 实施进度报告

> 本文件是事实记录，不是完成声明。只有满足各阶段验收并进入统一审核的任务才可标记 `in_review`。

## M2

已把数据库配置、连接池、SQL dialect/provider、完整 SQLite/MySQL schema migration、支付 SPI、值对象，以及首页、通知、退款、可视化、库存编排等无平台服务移入 `webshopx-core`；根 Paper 从 core 消费同一 class，禁止双写。`verifyPlatformIsolation` 与 `paperBaseline` 通过。

尚未完成：认证、钱包、订单、市场、HTTP/Relay 编排仍有 Bukkit 依赖，未达到完整 Paper/Mod 业务等价。

## M3

已交付封闭 `PlatformResult`、完整端口边界、能力快照、兼容域、4 MiB 有界不可变 `ItemEnvelope`、hash/domain/codec 拒绝、opaque native codec、幂等 compare-and-apply 参考背包、部分容量、版本冲突、operation ID 去重、legacy/modern allowlist 和 contract tests。Loader runtime 已接入 Fabric/Forge/NeoForge 原生 ServerStarted/ServerStopping 与玩家连接事件；全局/玩家任务通过原生 `MinecraftServer` executor 分派，未绑定时明确失败，异步周期任务可取消且停服关闭。Fabric 1.20.1 与 Forge 1.20.1 已在真实专服证明事件桥安装、ServerStarted 后 READY、健康命令和 ServerStopping 后 STOPPED。

尚未完成：玩家桥仍需七单元真实登录/离线验证；在线/离线背包、消息、权限与真实物品 serialization adapter 尚未完成。

## M4–M6

Fabric、Forge、NeoForge 的精确入口、元数据、Java 下限、Fabric API、完整 schema、健康命令和进程停止已在七个真实服务端通过。新增原生生命周期/调度桥已在 Fabric 1.20.1 与 Forge 1.20.1 真实服务端复验；支持矩阵仍只标 `startup_verified`。

尚未完成：新增事件桥的其余五个冻结单元复验，以及各 Loader 的库存/消息/权限/GUI/原生 item adapter 和插件版完整业务矩阵。不得将当前产物宣传为 full。

## M7

ADR-0004 选择 server-only 基线；客户端增强延后且不影响服务端路径。`ItemCompatibilityPolicy` 对原版 allowlist、显式 portable 标志、嵌套深度和 Mod 域执行 fail-closed 决策。

尚未完成：真实 legacy NBT 与 modern data component fixture/conversion。

## M8

已交付 event ID 去重、JDBC durable inbox、schema rolling-upgrade gate、delivery lease/unknown outcome 状态机、兼容域路由和 Provider 确定性选择测试。

尚未完成：真实 MySQL/MariaDB + Redis/Relay 混合集群、跨节点玩家会话和首批原生 Provider integration。
