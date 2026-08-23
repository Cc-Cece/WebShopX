# ADR-0006：首批 Loader Provider 与安全降级

- 状态：Accepted
- 日期：2026-08-23

## 决定

Loader RC 首批 Provider 是原生 vanilla operator 权限桥和 WebShopX 自有 ShopCoin 账本。
权限桥仅对本节点在线玩家提供 level 2/4 查询，离线、调度器不可用或反射失败均 fail closed。
它不宣称支持通配符、世界上下文或第三方细粒度节点。

Loader 不硬依赖 Vault、LuckPerms、YuPay 或领地 Mod。外部 Economy 与 Loader Payment 能力明确
标为 `UNSUPPORTED`；共享 Payment SPI 仍验证创建、重复回调和幂等入账。当前业务不修改世界，
因此不注册 Protection Provider。Paper 继续使用已有 Vault/YuPay/Bukkit 服务桥。

## 理由

M0 没有可证明的 Loader 第三方 Provider 版本/用户需求基线。把流行插件 API 猜测移植到七个
发行单元会制造虚假兼容。稳定 SPI、准确 capability 和安全缺失行为为以后增加适配器保留边界，
同时不改变内部账本、认证、商城和发货。

## 故障语义

- 可选 Provider 缺失不阻止启动或无关功能；
- 敏感权限查询失败返回 unavailable，不提升权限；
- 外部经济未安装时不自动切换货币；
- 支付重复通知由 provider order ID/业务订单幂等，未知结果进入对账而不重复入账；
- Provider 候选按优先级和稳定 ID 确定性选择，失败候选不被使用。
