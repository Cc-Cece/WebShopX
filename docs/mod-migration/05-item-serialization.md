# 物品序列化与兼容域

## 1. 目标

物品存储必须可恢复、可判定兼容、可升级并防止复制。网页检索所需的规范化字段不能取代原生负载。

## 2. ItemEnvelope

建议逻辑结构：

```json
{
  "schemaVersion": 1,
  "codec": "fabric-components",
  "codecVersion": 1,
  "platform": "fabric",
  "minecraftVersion": "<exact>",
  "loaderVersion": "<exact>",
  "modpackFingerprint": "sha256:<hex>",
  "registryId": "namespace:item",
  "count": 1,
  "payloadEncoding": "base64",
  "payload": "<lossless native representation>",
  "payloadHash": "sha256:<hex>",
  "summary": {},
  "createdAt": "<UTC instant>"
}
```

实际字段由 ADR 固化。必须保留：精确来源、codec、原生负载、hash、数量和兼容判定所需信息。

## 3. 编解码要求

- `decode(encode(item))` 在同一兼容域内保持语义等价；
- 保留附魔、名称、Lore、属性、损耗、药水、书本、旗帜、地图、实体数据、容器嵌套及 Mod 自定义组件；
- 嵌套深度、解压大小、元素数量和 payload 大小设安全上限；
- hash 校验失败、未知 codec、缺失注册表项或缺失 Mod 时拒绝发放并保留数据；
- 物品摘要仅用于展示/搜索，不参与恢复原物品；
- codec 升级采用新版本写入，旧版本读取器保留至迁移验证完成。

## 4. 跨平台规则

- 默认只允许同兼容域无损领取。
- 原版物品可以通过 canonical vanilla codec 跨平台，但必须逐类 round-trip 测试；复杂容器/实体数据不自动视为原版可互换。
- Bukkit 序列化、传统 NBT、Fabric/Forge 原生格式和现代数据组件不得通过字段猜测互转。
- 管理员配置兼容别名只能放宽 Modpack 身份匹配；codec 仍须验证注册表与负载。
- 转换失败后保持原订单/邮箱状态，禁止发空气、替代物或默认物品。

## 5. 安全与幂等

- 反序列化前验证长度、hash、codec 白名单和版本；
- 禁止反序列化任意 Java 对象；
- 发货记录绑定 envelope hash、operation ID、目标玩家和目标服务器；
- 部分插入必须准确记录 remainder，不得整批重试；
- 容器嵌套循环或恶意负载必须拒绝并形成审计事件。

## 6. 测试语料

每个支持版本至少保存以下合法生成的 fixture：普通物品、耐久物品、附魔、改名/Lore、药水、书、地图、潜影盒、Bundle、嵌套容器、最大合法负载、Mod 物品、缺失 Mod 物品、旧 codec、损坏 hash 和超限负载。

Fixture 应由对应游戏版本测试环境生成，记录来源和生成器；不得手写无法证明有效的 NBT 代替全部真实样本。
