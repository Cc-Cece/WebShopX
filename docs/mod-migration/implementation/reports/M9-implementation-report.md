# M9 实施报告：CI、迁移/回滚与候选包

状态：实施完成并进入统一审核；一个外部浏览器证据项明确堵塞。

## 发布门禁

`releaseReadiness` 聚合 Paper baseline、core/platform contracts、Loader runtime tests、性能预算、
七单元构建/入口/元数据、support matrix、release manifest、SPDX SBOM 与机器 readiness。manifest
和 SBOM 使用 commit timestamp，避免墙钟时间破坏同 commit 重现性。

GitHub Actions 分为 Paper regression、contracts/artifacts、MariaDB/Redis、七单元 dedicated-server
matrix 和 CodeQL。候选依赖由 Anchore/Grype 以 high severity 阻断；CodeQL 使用当前受支持 v4。
服务端 job 从 Fabric、Forge、NeoForge 官方 Maven/Meta 端点准备精确版本，执行 health、native item
corpus、HTTP startup 和 clean stop 并上传脱敏日志。

## 迁移、回滚与性能

`MigrationRollbackRehearsalTest` 创建生产形态钱包/流水/订单/发货/市场 envelope，重复 migration，
恢复冷备份，再逐项对账用户数、余额、流水、订单/发货状态和 payload hash。MariaDB 双连接池和
Redis stop/restart 测试覆盖集群并发、未知结果与恢复。

`performanceBudget` 的保守阻断值为：SQLite/schema 启动 <5 s、购买 ≥20/s、4 KiB envelope
round-trip 平均 ≤2 ms、HTTP health p95 ≤250 ms、编码 ≤8 KiB、测试进程 heap ≤256 MiB，且业务
基准不调度任何 server-thread blocking I/O。每次运行的实际数值写入
`webshopx-core/build/reports/mod-migration/performance-budget.json`。

## 明确堵塞

2026-08-23 首次连接时没有浏览器实例。随后已连接两个 Chrome 扩展实例，并在真实 Fabric 1.18.2
服务端上使用当前候选 JAR 启动 HTTP API：`GET /` 返回 `200` 和 “Server API is ready.”，
`GET /health` 返回 `200`、`status=UP`、`minecraftVersion=1.18.2`、`loaderVersion=0.19.3`。
但 Browser 组件在会话中更新后，活动控制服务与新组件版本不匹配，导航前即拒绝连接；旧组件文件
也已被缓存更新替换。因此交互浏览器关键路径仍不能标为通过，且禁止改用无关浏览器后端冒充。

补偿证据是上述真实 dedicated-server HTTP 响应，以及 `SharedHttpApiTest` 的真实 socket 流程
（landing/health、登录、Bearer、钱包、商品、幂等下单、发货、logout、精确 CORS、CSP、
未授权/恶意 origin/malformed/64 KiB 上限）和七服务端 HTTP 启动/停止。重启 Codex 使浏览器
控制服务与已安装组件同步后，只需重跑 `/` 与 `/health` 即可关闭该环境堵塞；无需代码或数据变更。

## 发布边界

七个 support-matrix 精确组合标为 `full`。server-only、同 compatibility domain native item、
内部 ShopCoin 和 vanilla OP 权限是已接受边界；可选客户端、跨域 native 转换及外部 Loader
economy/payment adapter 明确 unsupported，不被隐藏成 unavailable。候选未发布、未打 tag、未
合并 main，正式决定仍属于项目所有者。
