# M2：共享核心抽取与 Paper 等价

## 目标

把业务规则、数据库、HTTP/Relay 公共逻辑从 Bukkit 生命周期中分离，同时让 Paper 通过 adapter 使用共享核心且外部行为不变。

## 迁移顺序

1. 值对象、错误码、时间/金额/JSON 工具；
2. schema、SQL provider、数据库和运行时配置；
3. 认证、钱包、商品、订单、促销、兑换、会员；
4. 市场、邮箱、通知、审计和维护；
5. HTTP/API 与 Relay 路由的公共编排；
6. 仍依赖玩家/物品的逻辑只抽用例，具体操作留在 Paper adapter。

每批先写 characterization/contract test，再移动代码。禁止大规模复制后长期双写。

## 交付物

- core/platform-api 依赖检查，禁止平台包；
- Paper bootstrap/adapter，将现有 `WebShopPlugin` 缩为组合根；
- 公共服务图与明确生命周期；
- SQLite/MySQL/MariaDB 测试；
- API、错误码、配置和 schema 兼容报告；
- 服务清单中每项 owner 更新为 core、paper 或待 M3 的 platform port。

## 验收

- M0 Paper 全门禁与关键真实 smoke 通过；
- 同一 fixture 下迁移前后余额、流水、订单、库存和响应契约一致；
- core 独立测试不加载 Bukkit/Minecraft 类；
- 无双启动、重复定时器、重复 HTTP/Relay listener；
- 所有平台相关调用都有待 M3 明确化的边界，不能隐藏在通用 utility。

## 回退

按迁移批次可逆；schema 变化原则上不应在本阶段发生，如必要须单独 ADR/migration。
