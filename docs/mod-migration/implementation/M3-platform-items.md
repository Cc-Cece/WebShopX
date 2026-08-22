# M3：平台契约、线程模型与物品信封

## 目标

冻结足以实现完整功能的 platform-api；完成 Paper adapter 和各 Loader 探针实现；建立无损、版本化、可拒绝的 ItemEnvelope。

## 任务

- 根据 M0 实际调用点定义 lifecycle、scheduler、player、inventory、item、command、permission、economy、messaging、event、paths、identity 端口；
- 为接口写阻塞、线程、事务、重试和错误语义；
- Paper adapter 通过公共 contract tests；
- 各 Loader 至少实现身份、调度、命令、在线玩家与基础物品 round-trip 探针；
- 固化 ItemEnvelope schema、codec 注册、兼容域和 capability snapshot；
- 生成各版本真实物品 fixture，覆盖嵌套、Mod 数据、损坏和缺失注册表；
- 设计在线/离线背包 compare-and-apply、玩家锁和未知结果恢复；
- 数据库新增 envelope/compatibility 元数据时完成全数据库 migration、校验和回滚演练。

## 关键测试

- 同域 `decode(encode(item))`；
- 原版允许集合跨 Paper/Fabric/Forge/NeoForge 转换；
- Mod 物品跨不兼容域拒绝且负载不变；
- 背包并发变化、上下线竞态、部分容量与重复 operation ID；
- 主线程断言、停服取消和异常恢复；
- 恶意深度/大小/hash/codec 输入。

## 验收

- Paper 所有物品/发货/GUI行为无回归；
- core 中无平台类型；
- contract tests 对 Paper 和 Loader 探针运行；
- 未实现能力显式 `Unavailable`，不返回伪成功；
- envelope migration 可对账、原始负载永不被摘要覆盖。

## 回退

新数据采用向前兼容列/表；旧 Paper 应可忽略或被版本门禁安全阻止。禁止需要删除新列才能回滚。
