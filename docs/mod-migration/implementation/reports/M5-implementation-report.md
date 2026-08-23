# M5 实施报告：Forge 发行线

状态：实施完成，等待 M0–M9 统一审核。

Forge 1.18.2/40.3.12 与 1.20.1/47.4.23 使用各自精确元数据、Java 17 和官方服务端启动参数。
两个真实 dedicated server 均完成共享 runtime、原生事件/调度/玩家/命令/权限、在线/离线背包、
原生 NBT codec、HTTP/数据库/发货、代表性物品语料和安全拒绝 smoke。打包门禁拒绝 Fabric/
NeoForge 元数据、Paper 类和 JPMS split package。

客户端增强、外部 Loader economy/payment 和跨域 native item 转换不在承诺内，capability 明确
声明；其缺失不影响内部账本与其他功能。
