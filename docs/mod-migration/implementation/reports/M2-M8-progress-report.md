# M2–M8 实施进度报告

> 本文件是事实记录，不是完成声明。只有满足各阶段验收并进入统一审核的任务才可标记 `in_review`。

## M2

已把数据库配置、连接池、SQL dialect/provider、完整 SQLite/MySQL schema migration、支付 SPI、值对象，以及认证/会话、钱包/幂等账本/兑换、兑换码、管理员/RBAC/审计、跨节点玩家在线状态、首页、通知、退款、可视化、库存编排等无平台服务移入 `webshopx-core`；根 Paper 从 core 消费同一 class，禁止双写。Vault 发现和交易已收敛为 Paper-only `VaultGameCoinProvider`，共享钱包通过 `GameCoinProvider` 端口运行。Loader 数据库运行时现已实际组装认证、钱包、兑换码、管理员、审计与在线状态业务图。真实 SQLite 测试覆盖密码替换撤销会话、过期会话、账本去重、原子兑换、余额不足、兑换码用户限额、超级管理员权限/审计与节点离线清理。`verifyPlatformIsolation`、`paperBaseline` 和 `releaseReadiness` 通过。

尚未完成：订单、市场、充值、HTTP/Relay 编排仍有 Bukkit 或 Paper 组合根依赖，未达到完整 Paper/Mod 业务等价。

## M3

已交付封闭 `PlatformResult`、完整端口边界、能力快照、兼容域、4 MiB 有界不可变 `ItemEnvelope`、hash/domain/codec 拒绝、opaque native codec、幂等 compare-and-apply 参考背包、部分容量、版本冲突、operation ID 去重、legacy/modern allowlist 和 contract tests。Loader runtime 已接入 Fabric/Forge/NeoForge 原生 ServerStarted/ServerStopping 与玩家连接事件；全局/玩家任务通过原生 `MinecraftServer` executor 分派，未绑定时明确失败，异步周期任务可取消且停服关闭。原生命令现提供健康、密码设置、余额查询与兑换码路径；命令身份只接受命令源的直接 `ServerPlayer` 实体，控制台对象图中的 profile cache 不得冒充玩家。Fabric 1.20.1 真实专服已验证业务命令注册及控制台 `player_only` 拒绝。

原生玩家桥还会保留在线 `ServerPlayer` 句柄，并通过原生 text component 工厂和服务端线程发送单播/广播；离线玩家与反射失败返回明确 `Unavailable`。原生权限桥在玩家线程读取 vanilla operator level（普通管理节点 level 2、root/super 节点 level 4），未安装第三方权限 Provider 时不伪造节点支持。测试覆盖加入、消息、权限级别、退出后拒绝。

尚未完成：玩家桥仍需七单元真实登录/离线、消息与权限验证；在线/离线背包、第三方细粒度权限与真实物品 serialization adapter 尚未完成。

## M4–M6

Fabric、Forge、NeoForge 的精确入口、元数据、Java 下限、Fabric API、完整 schema、健康命令、原生生命周期/调度桥和进程停止已在七个真实服务端复验；支持矩阵仍只标 `startup_verified`。

尚未完成：各 Loader 的真实玩家登录、消息与业务命令成功路径 fixture、库存/权限/GUI/原生 item adapter 和插件版完整业务矩阵。不得将当前产物宣传为 full。

## M7

ADR-0004 选择 server-only 基线；客户端增强延后且不影响服务端路径。`ItemCompatibilityPolicy` 对原版 allowlist、显式 portable 标志、嵌套深度和 Mod 域执行 fail-closed 决策。

尚未完成：真实 legacy NBT 与 modern data component fixture/conversion。

## M8

已交付 event ID 去重、JDBC durable inbox、schema rolling-upgrade gate、delivery lease/unknown outcome 状态机、兼容域路由和 Provider 确定性选择测试；`PlayerPresenceService` 已脱离 `PluginSettings`，Loader 原生玩家事件会写入/清理节点在线状态，停服会批量清除此节点残留在线标记。

尚未完成：真实 MySQL/MariaDB + Redis/Relay 混合集群、跨节点玩家会话和首批原生 Provider integration。
