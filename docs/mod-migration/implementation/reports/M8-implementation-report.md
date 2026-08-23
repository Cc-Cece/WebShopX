# M8 实施报告：MariaDB、Redis、混合集群与 Provider

状态：实施完成，等待 M0–M9 统一审核。

## 数据与集群

- 两个独立连接池连接真实 MariaDB 10.11，共享 schema、用户、钱包、订单和发货；相同 operation
  ID 幂等，两个节点并发抢购单库存时仅一笔成功且失败方不扣款；
- MySQL/MariaDB transaction 对 deadlock/serialization failure 有有界重试；
- Redis 7 真实 pub/sub 验证 version、server ID、compatibility domain 和 poison event 隔离；
- 故障注入测试关闭并重启真实 Redis 进程，断线 publish 返回 `UnknownOutcome`，subscriber 和
  pooled publisher 在 15 秒预算内恢复；
- durable inbox、event ID 去重、乱序容忍、delivery lease/unknown outcome、schema 滚动升级门禁
  和节点 presence 清理均有自动测试。

SQLite 仍仅用于 standalone；集群配置只允许 MySQL/MariaDB，并通过 Redis 传播无状态通知。业务
正确性依赖数据库事务和幂等键，不依赖 pub/sub 恰好一次。

## Provider

ADR-0006 选择 vanilla OP 在线权限桥、自有 ShopCoin 和共享 Payment SPI；未验证的第三方 Loader
Provider 明确 unsupported。capability API 区分 available、unavailable 与 unsupported。测试覆盖
确定性选择、缺失候选、调度/离线权限失败、支付创建/重复回调和无重复入账。

## 本地复现

真实服务测试使用 system property 显式启用，普通单元测试不会静默连接开发者服务：

```text
./gradlew :webshopx-core:test -Dwebshopx.mariadb.integration=true ...
./gradlew :webshopx-core:test -Dwebshopx.redis.integration=true ...
./gradlew :webshopx-core:test -Dwebshopx.redis.reconnect.integration=true ...
```

CI 的 integration job 使用独立 MariaDB/Redis service 和临时 Redis 故障注入端口运行同一测试。
