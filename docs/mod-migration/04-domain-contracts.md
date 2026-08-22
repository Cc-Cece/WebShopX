# 领域与平台契约规范

## 1. 契约设计规则

- core DTO 只使用 JDK 类型、项目值对象和稳定枚举。
- 平台对象不得越过 adapter 边界；玩家用 UUID，物品用不可变 `ItemEnvelope`。
- 金额为整数最小单位并携带 `CurrencyType`。
- 每个写操作携带 `operationId`/幂等键、调用主体和审计上下文。
- 结果使用封闭状态类型表达，异常只用于不可恢复的基础设施失败。
- 接口注释必须声明线程、阻塞、事务、幂等和重试约束。

## 2. 核心用例

正式版必须有平台无关入口覆盖：

- 身份认证、密码、会话和管理员授权；
- 钱包查询/调整、充值、支付查询和回调；
- 官方商品、库存、购物车、结算、优惠、订单和退款；
- 玩家市场创建、购买、收购、拍卖、补货、下架和日志；
- 邮箱、领取、离线发货、通知与补单；
- 兑换码、会员、排行榜、主题、语言和素材；
- 配置、维护、审计、业务账本、Relay 与集群事件。

M0 生成现有服务/API/命令清单，M2 为每个用例标注 core、platform 或 legacy owner，禁止遗漏后以“Mod 不适用”默认处理。

## 3. 建议结果模型

```text
Success<T>
Rejected(errorCode, localizedMessageKey, retryable=false)
Unavailable(capability, reason, retryAfter)
Conflict(operationId, currentState)
UnknownOutcome(operationId, reconciliationRequired=true)
```

错误码跨平台一致；平台错误转换为稳定错误码并保留内部诊断 cause，外部响应不得泄露堆栈或秘密。

## 4. 调度契约

调度器至少区分：

- `runGlobal`：平台允许的全局服务器线程；
- `runForPlayer`：安全访问该玩家/区域的线程；
- `runAsync`：仅执行不触碰游戏对象的阻塞工作；
- `schedule`：延迟/周期任务，可取消并在停服时排空；
- `isOnRequiredThread`：只用于断言，不用于业务分支。

Folia 与 Mod 端实现可以不同，但业务调用者不应知道平台名称。

## 5. 背包操作契约

背包写操作采用三段式：

1. 在正确线程读取版本化快照和容量；
2. 使用期望版本执行 compare-and-apply；
3. 返回实际插入/移除的信封和新版本，业务层据此确认。

禁止“先查容量、异步等待、再无条件写入”。离线数据访问必须加玩家级锁、检测在线状态转换，并使用平台官方/可靠存档机制。

## 6. 能力发现

平台在 ready 前发布 `CapabilitySnapshot`，至少包含经济、权限、离线背包、标准容器 GUI、客户端增强、Mod 物品 codec、Relay、Redis 和支付 Provider 状态。Web、命令和业务规则读取该快照做显式降级。

## 7. 契约测试

每个端口有公共 contract test suite。各平台 adapter 必须运行同一测试集；Mock 只验证业务单元测试，不能替代真实服务器 smoke 和原生物品 round-trip。
