# M4 实施报告：Fabric 发行线

状态：实施完成，等待 M0–M9 统一审核。

Fabric 1.18.2、1.20.1、26.2 三个精确发行单元冻结 Loader/Fabric API/Java/元数据。每个单元均
在真实 Fabric dedicated server 完成 lifecycle、schema、HTTP、健康/业务命令、原生注册表物品
创建与 round-trip、兼容拒绝和干净停止。共享认证、钱包、商城、市场、发货、在线/离线背包、
Redis/数据库及管理员 API 由同一 Loader runtime 组装；不要求客户端 Mod。

支持状态为 `full` 只覆盖 support-matrix 中三个精确组合，不外推相邻 Minecraft 小版本。
最终 JAR hash 以 M9 release manifest 为唯一来源。
