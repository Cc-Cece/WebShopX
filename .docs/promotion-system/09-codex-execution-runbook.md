# Codex 执行手册

## 1. 任务定义

完整实现本目录规格，并保持后端 `WebShopX` 与相邻前端 `webshopx-web` 可构建、可迁移、可测试、可部署。不要在实现一部分后把剩余内容留成没有执行路径的“未来规划”。

## 2. 开始前

1. 确认当前分支、工作树和两个仓库状态，保护用户已有改动。
2. 阅读本目录全部文件，以及后端 `.ai/AGENT.md`、前端 `AGENTS.md`。
3. 检查本规格引用的类、表、API 和页面是否因后续提交发生变化。
4. 建立任务计划和需求追踪表；每个产品域必须有后端、前端、迁移、测试和文档项。
5. 先运行当前基线测试/构建，记录已有失败，避免把历史问题归因于本功能。
6. 不主动覆盖或重置用户的无关改动。

## 3. 文档维护协议

执行中允许直接更新本目录，规则如下：

- 技术细节优化、命名变化、索引、类拆分和补充测试：直接更新并实施。
- 发现文档内部冲突：按 README 的规范优先级解决，并记录 ADR/变更说明。
- 会改变用户流程、全域范围、卖家收入、平台成本、会员语义、退款、权限或优先级：暂停并请求项目所有者确认。
- 每个偏离需记录：原方案、实际方案、原因、兼容影响、测试证据。
- 完成时文档描述实际实现，不保留已废弃方案让下一个维护者猜测。

建议新增 `implementation-decisions.md` 或 ADR 文件记录非显然决策；简单修正可直接改对应章节。

## 4. 实施检查点

以下是依赖顺序，不是产品裁剪阶段。每个检查点都应保持主分支可测试。

### C0：基线与骨架

- 后端/前端基线测试；
- 新包、DTO、稳定枚举、金额值对象；
- 功能 capability 与配置开关；
- 文档追踪表。

退出条件：没有业务变化，构建通过。

### C1：数据库与纯计算核心

- 新 schema、provider、迁移和测试；
- `PricingContext/Result`；
- 层、门槛、槽位、优化、最大余数和 funding；
- 确定性/属性测试；
- 规范 JSON/hash。

退出条件：全部计算算例无需数据库即可通过；SQLite/MySQL schema 一致。

### C2：活动、券、资格与会员底座

- 活动/版本/作用域；
- 预算、次数和账本；
- 用户券/券码/事件；
- 会员计划/实例/entitlement/周期任务；
- admin 权限和审计；
- 无副作用模拟器服务。

退出条件：可通过服务测试创建、发布、发券、授予会员并模拟。

### C3：购物车与统一报价

- 持久购物车、版本和来源 adapter；
- 官方/市场 P0 行；
- checkout quote API；
- 前端购物车、到手价和结算预览；
- 报价变化和手动换券。

退出条件：官方+市场多行可准确报价，但尚未允许生产提交也可以。

### C4：统一提交与分账

- checkout 原子事务；
- 官方 orders/market trades 投影；
- 钱包、平台资金、卖家让利；
- 库存、限购、动态事件、交付；
- 父订单 UI；
- 旧单商品 API 适配。

退出条件：多商品、多卖家、双币结算并发测试通过。

### C5：平台/卖家管理界面

- 平台活动、券、集合、人群、资金和模拟器；
- 卖家模板、限制、报告；
- 管理员暂停和紧急停止；
- 用户券中心。

退出条件：三种角色可完成规范内操作且权限隔离。

### C6：会员产品与全域 adapter

- 会员购买、兑换、定向、续期/升级/撤销、周期权益；
- 回收/市场收购奖励；
- 团购独占/例外；
- 拍卖成交费用/后置权益；
- 充值法币优惠/赠币和回调预留。

退出条件：每个业务域的方向与算例通过，前端入口完整。

### C7：售后、核对与兼容

- 单位金额、部分退款和 funding 反冲；
- 退券、会员/奖励撤销、人工处理；
- 历史订单双读；
- 核对工具、报表、通知；
- 集群事件/缓存失效。

退出条件：全退守恒、历史退款、并发退款和故障注入通过。

### C8：全故事验收

- 完整测试矩阵；
- 桌面/移动浏览器；
- SQLite/MySQL/集群；
- 构建、静态检查、i18n；
- 紧急停止/恢复与升级/降级保护；
- 文档最终同步。

退出条件：README 完成定义全部可用证据证明。

## 5. 后端实现注意

- 优先提取可在调用者 transaction connection 内运行的领域原语。
- 禁止新 checkout 里调用会自己开启事务的 `OrderService.placeOrder`/`MarketService.buyListing` 造成嵌套事务。
- 保留现有交付、邮件、claim token、动态算法、物品 snapshot、退款政策能力。
- 对 `EmbeddedWebServer` 的新增应考虑拆 handler，避免一个类承担全部规则。
- SQL 同时覆盖 SQLite 和 MySQL；不得在业务字符串里依赖一个 dialect 的 UPSERT 而不经 provider。
- 每个跨表聚合提交前运行守恒断言；失败应回滚并产生高优先级日志。
- 所有任务和回调可重入。

## 6. 前端实现注意

- 使用 TypeScript，不在 Vue 页面散落 `any`。
- 所有金额来自后端报价，浏览器计算只用于视觉临时反馈且必须以服务端模拟替换。
- 新文案一次同时加中英资源；不先硬编码再“以后国际化”。
- 新 enum 的动态 key 同步 i18n 检查脚本。
- 大页面按组件/组合式函数拆分；完成多个 TSX/Vue 组件后执行适用的 React/Vue质量规范（若技能仅适用于 React则遵循本仓库 Vue 规范）。
- 开发服务器启动后做真实浏览器验证。

## 7. 测试组织建议

后端至少增加：

```text
PricingEngineTest
PromotionCombinationOptimizerTest
DiscountAllocationPropertyTest
FundingReconciliationTest
CartServiceTest
CheckoutQuoteServiceTest
CheckoutConcurrencyTest
CouponLifecycleTest
MembershipServiceTest
SellerPromotionAuthorizationTest
PromotionRefundServiceTest
PromotionSqliteSchemaTest
PromotionMySqlBusinessSqlSmokeTest
PromotionMigrationTest
```

对现有 `OrderService/MarketService/RechargeService/RefundPolicyService` 增加回归测试。前端如果现有没有测试框架，不强行引入庞大体系；至少完成类型/i18n/build 与浏览器全故事验证，并可引入轻量测试以覆盖纯 utils/composables。

## 8. 验证命令

后端 Windows 基础：

```text
gradlew.bat test -PskipFrontendResourceTests=true
gradlew.bat check -PskipFrontendResourceTests=true
```

根据 Java/Minecraft 目标运行必要构建，至少默认 runtime；最终发布前评估 `packagePluginVariants` 的成本并覆盖受影响变体。不要为通过测试提高既有 warning baseline。

前端：

```text
pnpm run i18n:scan
pnpm run i18n:check
pnpm run type-check
pnpm run build
```

如命令随仓库变化，以最新仓库脚本为准并更新本文。

## 9. 手动全故事

至少使用两个普通用户（其中一名玩家卖家）、一个会员用户、一个普通管理员和一个超级管理员：

1. 卖家上架两件商品并创建卖家优惠。
2. 管理员创建跨卖家平台券和会员券。
3. 普通用户把官方/两个卖家商品加入双币购物车，自动最低价结算。
4. 会员用户获得额外合法叠券但不能叠任意普通券。
5. 同一市场商品并发购买只成功合法数量。
6. 部分交付后退款，核对用户、卖家、平台、券。
7. 购买会员、兑换会员、管理员授予/撤销。
8. 回收加成、收购奖励、拍卖费用权益。
9. 充值优惠创建/成功/取消/重复回调。
10. 紧急停止后新优惠不可用，历史退款正常。

## 10. 变更与提交

- 分逻辑提交；不要把生成产物、日志或本地数据库提交。
- `.docs/` 在当前后端仓库被忽略，更新本目录需要 `git add -f`。
- 前端是相邻独立仓库，分别检查状态和提交；不要假设单仓提交覆盖前端。
- 不修改或删除不相关用户变更。
- 每个提交前检查 diff、空白、敏感数据、i18n 和测试。

## 11. 阻塞处理

以下情况先穷尽只读调查和安全替代，再请求确认：

- Vault 外部余额无法满足原子多币种结算；
- 支付提供方不支持文档要求的退款/金额行为；
- 现有市场/交付模型无法保持父交易全原子且需要改为部分成功；
- 产品方向需要缩减或改变；
- 用户已有改动与关键实现重叠且无法安全合并；
- 数据迁移会不可逆影响生产历史数据。

普通技术困难、需要重构或测试较多不是停止理由。

## 12. 最终交付报告

必须说明：

- 两个仓库的分支/提交；
- 完成的用户、卖家、管理员功能；
- schema 版本和迁移策略；
- 关键设计偏离及原因；
- 测试/构建命令和结果；
- 浏览器、SQLite/MySQL/集群验证；
- 未解决风险（若存在）；
- 如何启用、紧急停用和恢复；
- 文档入口。

不要仅报告“代码已写”；交付证据必须对应第 05 文档的完成定义。
