# ADR-0005：RC 物品兼容域与跨版本转换边界

- 状态：Accepted
- 日期：2026-08-23

## 决定

RC 只对“平台、Loader、Minecraft 精确版本、codec 版本、Modpack 指纹全部一致”的原生
`ItemEnvelope` 执行解码和发货。Fabric、Forge、NeoForge、legacy NBT 与 modern data
component 之间不做字段猜测或隐式转换；跨域任务保持可恢复状态并返回
`ITEM_DOMAIN_INCOMPATIBLE`。

原版物品未来可在独立 canonical codec 和逐类 fixture 都通过后加入白名单。本次允许列表为空，
因此不会把未经证明的转换宣传为兼容。

## 证据

- 七个冻结服务端单元均由原生注册表创建并 round-trip：普通方块、耐久物品、附魔书、药水、
  成书、地图、潜影盒和 Bundle；
- 每个单元同时验证兼容域、payload hash 和未知 codec 的 fail-closed 行为；
- core 测试覆盖嵌套二进制 envelope、深度、元素数、解压后大小和最大 4 MiB 负载；
- 发货只在 envelope 域匹配的节点竞争租约，失败不会替换为空气或默认物品。

## 后果

同域 Mod 物品的原生负载被无损保存；缺少 Mod/注册表项时保留任务供管理员恢复。跨 Loader、
跨 Minecraft 版本和 Modpack 变化后的领取会被明确拒绝。新增 canonical vanilla codec 必须使用
新的 codec 标识、转换 fixture 和 ADR，不能放宽本决定中的原生 codec。
