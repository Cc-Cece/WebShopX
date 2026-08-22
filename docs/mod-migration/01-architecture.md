# 目标架构

## 1. 依赖方向

```text
webshopx-domain          纯业务规则、值对象、错误码
       ↑
webshopx-application     用例编排、事务、幂等、端口定义
       ↑
webshopx-infrastructure  SQL、HTTP/Relay、配置、序列化公共实现
       ↑
webshopx-platform-api    玩家/物品/调度/命令/权限/经济/生命周期契约
       ↑
paper | fabric-* | forge-* | neoforge-*
```

实际 Gradle 模块可合并前三层为 `webshopx-core`，但包依赖必须维持上述方向。平台模块依赖 core；core 不得依赖 Bukkit、Paper、Fabric、Forge、NeoForge、Minecraft 类或加载器注解。

## 2. 推荐模块

```text
WebShopX/
├─ webshopx-core/
├─ webshopx-platform-api/
├─ webshopx-paper/
├─ webshopx-fabric-1182/
├─ webshopx-fabric-1201/
├─ webshopx-fabric-modern/
├─ webshopx-forge-1182/
├─ webshopx-forge-1201/
├─ webshopx-neoforge-1201/
├─ webshopx-neoforge-modern/
├─ webshopx-testkit/
└─ webshopx-client-enhancement/   # 仅在 ADR 批准后建立
```

这只是默认布局。M1 可通过 ADR 选择 Gradle source set、预处理器、Stonecutter/Architectury 类工具或不同版本族划分，但必须满足：依赖清晰、产物可定位、版本隔离、Paper 构建不受 Mod 工具链污染。

## 3. 平台端口

最低端口集合：

- `PlatformLifecycle`：启动、就绪、停止、重载顺序；
- `PlatformScheduler`：全局、玩家/区域、同步、异步、延迟和关闭取消；
- `PlayerDirectory`：在线查询、UUID/名称解析、在线状态和平台上下文；
- `InventoryGateway`：快照、容量校验、原子添加/移除、离线数据访问；
- `ItemCodec`：原生物品与版本化信封互转、兼容性判定；
- `CommandGateway`：命令、补全、调用者、权限和本地化反馈；
- `PermissionProvider`：原生权限、OP 和外部提供器；
- `EconomyProvider`：余额、扣款、入账、能力探测和事务标识；
- `MessagingGateway`：文本、可点击消息、通知、广播；
- `PlatformEventPublisher`：加入、退出、背包变化和服务器生命周期事件；
- `PlatformPaths`：配置、数据、资源、上传和日志目录；
- `PlatformIdentity`：平台、加载器、游戏版本、Mod 集合指纹和服务器 ID。

接口签名在 M3 根据现有调用链落地。所有返回值必须表达“不支持”“暂不可用”“对象不存在”“版本不兼容”，禁止用 `null` 混合这些状态。

## 4. 生命周期

统一顺序：

1. 读取启动必要配置；
2. 识别平台和兼容域；
3. 初始化日志与数据源；
4. 执行 schema 校验/向前迁移；
5. 建立 core 服务图；
6. 注册命令、事件、Provider 与 HTTP/Relay；
7. 标记 ready 后才接受写请求；
8. 停止时先拒绝新写入，再排空任务、停止 Relay/HTTP、关闭数据源。

任何阶段失败都应停止该实例，输出可定位错误；不得在数据库未就绪时开放部分写接口。

## 5. 线程与事务

- SQL、Redis、HTTP、压缩和大型序列化不得阻塞 Minecraft 主线程/服务器线程。
- 玩家、世界、背包和注册表对象只能在平台允许的线程访问；core 只接收不可变快照或通过端口调度。
- 发货采用“业务预留 → 平台执行 → 结果确认/可重试补偿”，数据库锁不能跨越不可控的平台调用长期持有。
- 所有重试使用稳定幂等键；超时不能自动推断失败并重复发货。

## 6. Web 与 Relay

`EmbeddedWebServer` 和 Relay RPC 的路由/DTO 应迁入公共基础设施，涉及玩家或物品的调用通过 application port。平台入口只提供依赖和生命周期，不复制整套 HTTP 路由。

外部前端仍可独立构建；各发行 JAR 的资源布局必须一致。前端同步失败应有明确的开发/CI指引，不能偷偷打包过期资源。

## 7. 历史原型治理

`feat/fabric-neoforge-mod` 只读审计流程：

1. 比较其基线与当前代码，列出可复用提交和已过时假设；
2. 对每项候选代码检查许可、依赖版本、线程、数据、测试和功能覆盖；
3. 以小提交重做或 cherry-pick，禁止整分支合并；
4. 每个复用项在 M0 报告中记录来源 commit、采用理由和重新验证结果；
5. 旧计划中的完成状态不得写入新 `task-status.md`。
