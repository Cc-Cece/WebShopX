# Mod 管理员安装、升级与回滚

## 安装

只从 `support-matrix.json` 选择完全匹配的平台、Minecraft、Loader 和 Java 的 JAR。Fabric 还需
矩阵中的精确 Fabric API。把 JAR 放入 `mods/`，首次启动前设置唯一 `webshopx.server-id`；单节点
默认 SQLite，集群使用 MariaDB/MySQL 和 Redis。不要在同一实例同时安装 Paper 插件与 Mod。

HTTP 默认绑定 `127.0.0.1:8123`。对外发布时使用反向代理/TLS，设置精确 allowed origin，不直接
暴露游戏主机端口。管理员 bootstrap 密码只通过受保护的启动配置注入，首次登录后轮换并清除。

## 升级与 Modpack 变化

1. 进入维护状态并停止写流量/发货 worker；
2. 停止节点，备份数据库、`config/webshopx`、当前 JAR 和反向代理配置并记录 hash；
3. 在副本重复运行 schema migration 和 `MigrationRollbackRehearsalTest` 对账；
4. 按测试节点、单个非关键节点、部分节点、全节点灰度；
5. 检查 health、登录、余额/流水、订单/发货、Redis 与 tick，再开放写入。

Modpack 或 Minecraft/Loader 变化会产生新 compatibility domain。旧 envelope 不重写，只能回到
原域节点领取，或等待经过 fixture/ADR 的新 canonical converter。

## 故障与回滚

余额/流水不符、重复发货、物品损坏、migration 失败、权限绕过或持续 tick 回退立即停止扩大。
先停止新交易，等待/标记 processing 操作为 unknown 并保存诊断；再停止新版本。二进制可读旧
schema 时优先旧二进制/forward-fix。只有确认迁移不可逆且得到所有者授权，才在无写入条件下恢复
数据库快照。恢复后对账用户、钱包、流水、订单状态、delivery operation ID 和 envelope hash。

诊断资料包括 `health.json`、服务端日志、平台/Minecraft/Loader/Java、Modpack 指纹、server ID、
数据库类型和 release manifest hash；不得包含密码、token 或完整玩家隐私数据。
