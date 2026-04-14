# WebShopX 用户手册 {#top}

> 面向玩家优先、服主次优先。先看“简洁版”就能用，想调参再看“详细说明”。

## 快速导航 {#quick-nav}

- 玩家上手：[#player-quick-start](#player-quick-start)
- 市场模式：[#market-modes](#market-modes)
- 动态算法：[#dynamic-algorithms](#dynamic-algorithms)
- 拍卖算法：[#market-auction](#market-auction)
- 服主调参：[#owner-tuning](#owner-tuning)
- 常见问题：[#faq](#faq)

## 玩家三分钟上手 {#player-quick-start}

### 1. 登录与账户准备 {#player-login}

1. 在游戏里设置密码：`/ws password 你的新密码`
2. 打开网页，输入 Minecraft 用户名和密码登录。
3. 登录后先确认钱包余额和当前服务器状态。

### 2. 两种货币看懂就行 {#player-currency}

- 网页币（SHOP_COIN）：网页商城和网页市场常用。
- 游戏币（GAME_COIN）：服务器经济体系常用。
- 汇率、换算和可用范围由服主配置，不同服务器可能不同。

### 3. 交易后如何收货 {#player-claim}

- 出现“待领取”时，在游戏执行：`/ws claim`
- 出现“已发放”代表交易已完成。
- 若背包空间不足，可能延迟发放或进入领取队列。

## 市场模式总览 {#market-modes}

### 一口价（DIRECT） {#direct-mode}

#### 简洁版（给普通玩家） {#direct-mode-simple}

- 标多少买多少，流程最直观。
- 适合日常稳定交易。

#### 详细说明（给服主） {#direct-mode-advanced}

- 成交价恒为挂牌价，不参与动态参数计算。
- 适合作为基准池，用于和动态/拍卖模式做行为对照。
- 建议：新服先用 DIRECT 跑一周，再逐步引入动态算法。

### 动态价格（DIRECT + Dynamic） {#dynamic-mode}

#### 简洁版（给普通玩家） {#dynamic-mode-simple}

- 买的人多，价格通常会上升。
- 交易降温后，价格可能回落或趋稳。
- 这是服主开启的市场策略，不是系统故障。

#### 详细说明（给服主） {#dynamic-mode-advanced}

动态价格一般可抽象为：

$$
P_t = \operatorname{clamp}(f(D_t, S_t, \theta),\ P_{\min},\ P_{\max})
$$

其中：

- \(P_t\)：时刻 \(t\) 的成交价
- \(D_t\)：需求强度（购买行为、频率、数量）
- \(S_t\)：供给状态（库存、补货节奏）
- \(\theta\)：算法参数集合
- \(\operatorname{clamp}\)：把结果限制在地板价与封顶价之间

推荐调参顺序：

1. 先定 \(P_{\min}\) 与 \(P_{\max}\)
2. 再调敏感度参数（斜率、弹性、阈值）
3. 最后调平滑参数，避免价格抖动

### 拍卖（AUCTION） {#auction-mode}

#### 简洁版（给普通玩家） {#auction-mode-simple}

- 拍卖会显示具体规则（英式、荷兰、密封、蜡烛）。
- 你只要关心：怎么出价、何时结束、结算价怎么算。

#### 详细说明（给服主） {#auction-mode-advanced}

- 拍卖参数建议分成两层：
  1. 玩法层：最小加价、结束时间、随机延迟
  2. 风控层：冻结上限、超时策略、流拍回收
- 建议先启用英式拍卖稳定流程，再引入密封和蜡烛。

## 动态价格算法（玩家版 + 服主版） {#dynamic-algorithms}

### 线性需求（LINEAR_DEMAND_V1） {#LINEAR_DEMAND_V1}

#### 简洁版（给普通玩家）

- 买得越多，价格按固定节奏上涨。
- 规则透明，预期稳定。

#### 详细说明（给服主）

$$
P_t = P_0 + k \cdot D_t
$$

- \(k\) 越大，涨价越快。
- 建议搭配地板价/封顶价：

$$
P_t = \operatorname{clamp}(P_0 + kD_t,\ P_{\min},\ P_{\max})
$$

### 边际递减（DIMINISHING_RETURN_V1） {#DIMINISHING_RETURN_V1}

#### 简洁版（给普通玩家）

- 前几次购买影响更明显，后面会变缓。
- 价格不容易一路暴涨。

#### 详细说明（给服主）

$$
P_t = P_0 + a \cdot \frac{D_t}{1 + bD_t}
$$

- \(a\) 控制最大抬升幅度。
- \(b\) 控制“变缓”速度，\(b\) 越大越早进入平缓区。

### 对数平滑（LOG_SMOOTH_V1） {#LOG_SMOOTH_V1}

#### 简洁版（给普通玩家）

- 小额高频交易时，价格变化更平滑。
- 体感上不容易突然跳价。

#### 详细说明（给服主）

$$
P_t = P_0 \cdot \left(1 + \alpha \ln(1 + D_t)\right)
$$

- \(\alpha\) 决定敏感度。
- 适合“活跃但不希望过度波动”的交易池。

### 指数防护（EXPONENTIAL_DEFENSE_V1） {#EXPONENTIAL_DEFENSE_V1}

#### 简洁版（给普通玩家）

- 大额扫货会让价格更快上升。
- 用来保护稀缺库存。

#### 详细说明（给服主）

$$
P_t = P_0 \cdot e^{\beta D_t}
$$

- \(\beta\) 控制指数增长强度。
- 强烈建议配置封顶价，防止短时极端抬价。

### 阈值分段（THRESHOLD_STEP_V1） {#THRESHOLD_STEP_V1}

#### 简洁版（给普通玩家）

- 没到阈值前变化小，超过后涨价明显。

#### 详细说明（给服主）

$$
P_t =
\begin{cases}
P_0 + k_1D_t, & D_t \le T \\
P_0 + k_1T + k_2(D_t - T), & D_t > T
\end{cases}
$$

- \(T\)：阈值
- \(k_2 > k_1\) 时会形成“分段加速”

### 弹性模型（ELASTICITY_V1） {#ELASTICITY_V1}

#### 简洁版（给普通玩家）

- 弹性大，价格更柔和；弹性小，价格更敏感。

#### 详细说明（给服主）

$$
P_t = P_0 \cdot \left(\frac{D_t + \varepsilon}{D_0 + \varepsilon}\right)^{\eta}
$$

- \(\eta\)：弹性指数
- \(\eta < 1\) 更平滑，\(\eta > 1\) 更激进

### 恐慌抢购（PANIC_BUYING_V1） {#PANIC_BUYING_V1}

#### 简洁版（给普通玩家）

- 超过“抢购线”后，价格会加速上涨。

#### 详细说明（给服主）

$$
P_t = P_0 + kD_t + \gamma \cdot \max(0, D_t - T)^2
$$

- \(T\)：恐慌阈值
- \(\gamma\)：超阈值后的加速强度

## 拍卖算法（玩家版 + 服主版） {#market-auction}

### 英式拍卖（ENGLISH_AUCTION_V1） {#ENGLISH_AUCTION_V1}

#### 简洁版（给普通玩家）

- 公开加价，最高出价者在截止时获胜。

#### 详细说明（给服主）

- 建议配置：最小加价 \(\Delta_{\min}\)、自动延长窗口、防狙击时间。
- 成交价：

$$
P_{\text{settle}} = \max_i(b_i)
$$

### 荷兰拍卖（DUTCH_AUCTION_V1） {#DUTCH_AUCTION_V1}

#### 简洁版（给普通玩家）

- 价格随时间下调，先买先得。

#### 详细说明（给服主）

$$
P(t) = \max\left(P_{\text{floor}},\ P_{\text{start}} - r \cdot \Delta t\right)
$$

- \(r\)：降价速率
- 建议设置最低保护价 \(P_{\text{floor}}\)

### 密封拍卖（VICKREY_AUCTION_V1） {#VICKREY_AUCTION_V1}

#### 简洁版（给普通玩家）

- 出价互相不可见，最高者赢，但按次高价结算。

#### 详细说明（给服主）

设所有有效出价降序为 \(b_{(1)} \ge b_{(2)} \ge ...\)：

$$
\text{winner} = \arg\max_i b_i,\qquad P_{\text{settle}} = b_{(2)}
$$

- 需要严谨处理并列、撤回和无效出价。
- 建议在 UI 明确“冻结金额不等于最终结算价”。

### 蜡烛拍卖（CANDLE_AUCTION_V1） {#CANDLE_AUCTION_V1}

#### 简洁版（给普通玩家）

- 表面有结束时间，但真实结束点在区间内随机。
- 不适合最后一秒压线。

#### 详细说明（给服主）

$$
T_{\text{real-end}} \sim \mathcal{U}(T_{\text{public-end}},\ T_{\text{public-end}} + \delta)
$$

- \(\delta\) 是随机延迟窗口。
- 窗口过短会失去机制意义，过长会影响体验。

## 服主调参与运营建议 {#owner-tuning}

### 推荐调参流程 {#owner-tuning-flow}

1. 选一个主要交易池做试点（不要全服一起改）。
2. 先稳定地板价/封顶价，再调算法敏感度。
3. 连续观察 3 到 7 天，记录成交量和玩家反馈。
4. 每次只改 1 到 2 个参数，避免难以归因。

### 参数改动安全线 {#owner-safety-line}

- 动态算法敏感度单次变更建议不超过 15%。
- 拍卖最小加价不宜过大，否则中小玩家参与感下降。
- 活动期间避免频繁改阈值，防止玩家预期崩塌。

### 回滚策略 {#owner-rollback}

- 升级前备份数据库与插件目录。
- 关键参数变更前导出旧值。
- 若出现异常波动，优先回滚敏感参数，再回滚模式。

## 常见问题 {#faq}

### 为什么我的价格和别人截图不一样？ {#faq-price-diff}

可能因为：

1. 你看到的是不同时间点的动态价格。
2. 对方看的币种不同。
3. 该商品正在拍卖，不是固定价交易。

### 出价后余额减少是 bug 吗？ {#faq-bid-freeze}

不是。竞价通常会先冻结金额，失去领先后会自动解冻/退款。

### 什么时候应该用单页模式？ {#faq-single-mode}

- 快速查看一个算法参数时：用单页模式 + `#锚点` 最快。
- 完整学习体系时：切回全文模式更方便通读。

## 文档自定义与更新策略 {#doc-custom-policy}

- 帮助系统支持自定义，不会粗暴覆盖服主修改。
- 插件会根据 MD5 判断文档是否是“未改动的官方版本”。
- 只有在“本地未改动”的情况下才自动升级到新内置文档。

---

如果你是玩家，先看“玩家三分钟上手”和“市场模式总览”。

如果你是服主，重点看“动态价格算法”“拍卖算法”“服主调参与运营建议”。
