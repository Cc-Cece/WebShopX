# M2 实施报告：共享业务核心与 Paper 等价

状态：实施完成，等待 M0–M9 统一审核。

数据库配置/连接池、SQL dialect、SQLite/MySQL/MariaDB schema、认证/会话、钱包/幂等流水、
兑换码、管理员/RBAC/审计、presence、商城/市场/充值/支付回调和发货状态图已由
`webshopx-core` 持有。Paper 与 Loader 消费同一来源文件，`verifyPlatformIsolation` 阻止 core
重新引入 Bukkit/Minecraft 类。

SQLite 真实测试覆盖成功、权限拒绝、余额不足、重复请求、并发和回滚；MariaDB 双节点测试覆盖
共享余额/订单、单库存竞争和 deadlock 重试。`paperBaseline` 保持原 Paper 产物与既有测试，新的
Loader 组合不进入 Paper runtime classpath。
