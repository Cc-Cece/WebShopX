# M8：混合集群、Relay 与生态适配

## 目标

证明 Paper、Fabric、Forge、NeoForge 能安全共享业务数据库和 Redis/Relay，同时按兼容域路由物品；交付首批可维护 Provider 适配器。

## 混合集群场景

- 多节点同时创建/购买/领取同一业务对象；
- Paper 创建、Mod 读取；Mod 创建、Paper 读取；
- 原版兼容物品跨域领取；Mod 物品被错误节点拒绝后在正确节点领取；
- Relay/Redis 重连、重复、延迟、乱序和节点失联；
- 发货节点获得租约后崩溃、结果未知、人工/自动对账；
- 滚动升级中旧 schema 客户端与新客户端并存门禁；
- 同一玩家跨节点上线、离线数据锁与会话冲突。

## 路由与一致性

- 所有写事件携带 event ID、schema version、server ID、compatibility domain 和时间；
- consumer 去重并容忍重复/乱序；
- 物品任务只有兼容节点可竞争租约；
- 未知结果不重新扣款，发货依赖 reconciliation；
- SQLite 保持 standalone，集群只用已支持的 MySQL/MariaDB + Redis 配置。

## 生态适配

- 根据 M0 数据和 [06-ecosystem.md](../06-ecosystem.md) ADR 选择首批经济、权限和必要保护 Provider；
- 为 Provider 能力、故障、离线账户、原子性和版本兼容写 contract/integration tests；
- YuPay/Payment SPI 在有/无 Provider、重复回调和查询未知结果下验证；
- 文档列出已测试版本，不宣传未验证生态。

## 验收

- 混合集群测试无重复扣款/发货、无不可恢复丢失；
- 不兼容物品可恢复且有用户/管理员诊断；
- Provider 故障 fail closed，不影响无关功能；
- API/Relay 旧客户端契约通过；
- 长时重连/故障注入测试达到 M0/M9 确定预算；
- Paper 和所有 Loader 单节点门禁仍通过。
