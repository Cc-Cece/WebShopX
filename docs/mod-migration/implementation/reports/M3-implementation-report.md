# M3 实施报告：平台端口、线程、物品与背包

状态：实施完成，等待 M0–M9 统一审核。

交付封闭 `PlatformResult`、lifecycle/scheduler/player/inventory/item/command/permission/economy/
messaging/event/paths/identity 端口和 capability snapshot。Loader 原生桥绑定真实服务器线程、玩家
实体与事件；离线或线程不可用返回明确结果，不用 profile cache 冒充玩家。

物品使用 4 MiB 有界、hash 校验、codec/version/domain 明确的不可变 envelope。在线背包使用
版本化 compare-and-apply，离线 playerdata 使用锁、临时文件、fsync/原子替换和 recovery marker；
operation ID 防止重复应用。测试覆盖容量、部分插入、版本冲突、嵌套/超限/损坏数据和在线/离线
竞争。
