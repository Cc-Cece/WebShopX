# Mod 生态集成策略

## 1. 分层

```text
WebShopX 业务
 → 稳定 Provider SPI
 → Loader 发现/生命周期桥
 → 具体经济、权限、领地、支付或物品 Mod 适配器
```

具体 Mod API 不得进入 core。适配器可独立模块化，以免所有用户承担可选依赖。

## 2. 首发必需能力

- 内置 ShopCoin 始终可用；
- GameCoin 可配置为 WebShopX 内部账本或外部 EconomyProvider；
- 原生 OP/权限等级与一个可插拔权限 Provider；
- 领地保护以事件/策略 SPI 表达，无 Provider 时采用明确默认策略；
- 支付保持现有 WebShopX Payment API 语义，YuPay/Bukkit 桥不影响 Mod 启动；
- Mod 物品通过注册表和 codec 工作，不要求为每个物品 Mod 单独适配。

## 3. Provider 能力

EconomyProvider 必须声明：精度/整数单位、负余额、离线账户、原子扣款、事务 ID、退款和查询能力。不能把不支持原子事务的 Provider 宣称为完整兼容。

PermissionProvider 必须声明：离线查询、上下文/世界、通配符、刷新时机和故障默认值。权限服务不可用时敏感操作 fail closed。

ProtectionProvider 必须声明：位置/维度、玩家身份、缓存时限和事件一致性。当前 WebShopX 主要是 Web/背包交易，只有实际触碰世界时才强制接入领地判断。

## 4. 选择具体生态的流程

M0 收集候选 Mod 的：目标版本、下载/用户需求、API 稳定性、许可、维护状态和测试可行性。M8 用 ADR 确认首批适配器。不得仅凭流行度硬编码依赖。

## 5. 降级

- Provider 缺失：相关能力在 capability API、后台和命令中显示不可用；
- Provider 启动失败：记录可操作诊断，不回退到另一货币导致语义变化；
- Provider 运行中失效：停止新交易，保留待处理操作并支持对账；
- 外部 Provider 返回未知结果：进入 reconciliation，不自动重复扣款。

## 6. 客户端附属 Mod

默认不需要 WebShopX 客户端。若 M7 证明自定义 GUI/预览有明确价值，可发布可选客户端模块：

- 服务端必须检测客户端能力；
- 无客户端仍可通过 Web、命令和标准容器完成同一业务；
- 网络包版本化、限流并验证权限；
- 客户端只负责展示和输入，业务校验仍在服务端。
