# 支持与构建矩阵

## 1. 产品目标矩阵

| Loader | 目标范围 | 首个锚点 | 维护定位 |
|---|---|---|---|
| Paper/Purpur/Spigot | 保持当前项目声明 | 当前 `targetRuntime` 矩阵 | 既有稳定线 |
| Folia | 保持当前项目声明 | 当前 Folia 变体 | 既有稳定线 |
| Fabric | 1.18.2 至最新 | 1.18.2、1.20.1、现代版本 | 主 Mod 线 |
| Forge | 1.18.2–1.20.1 | 1.18.2、1.20.1 | 旧版兼容线 |
| NeoForge | 1.20.1 至最新 | 1.20.1、现代版本 | 新版主 Mod 线 |

“范围内每个 Minecraft 小版本都产物化”与“用锚点验证一个兼容区间”成本不同。M0 必须盘点真实用户版本和依赖可行性；M1 ADR 必须选择发行粒度。未经项目所有者批准，不得把目标范围缩小为仅锚点版本。

## 2. 版本族

默认按以下差异建立版本族，最终边界由 M0/M1 证据确认：

| 版本族 | 主要差异 | 典型风险 |
|---|---|---|
| legacy-1182 | Java 17、旧注册表/NBT、旧命令和事件 API | 库版本下限、映射差异 |
| bridge-1201 | Forge/NeoForge 分界期、仍以传统 NBT 为主 | 双 Loader 差异、生态分叉 |
| component-modern | 物品数据组件、更新后的注册表/网络 API | 物品转换和持久化变化 |
| future | 尚未冻结的新版本 | 构建插件、Java、映射和 API 未知 |

不得用版本字符串条件散布在业务层。差异只能位于平台模块、版本专用实现或集中兼容层。

## 3. Release 冻结表

每次准备 Release 时复制并填写：

| Platform | Minecraft | Loader/API | Mapping | Java | Gradle plugin | Artifact | 状态 |
|---|---|---|---|---:|---|---|---|
| Paper | 待填 | Paper API 待填 | N/A | 待填 | Shadow | 待填 | planned |
| Fabric | 1.18.2 | 待验证 | 待验证 | 17 | 待验证 | 待填 | planned |
| Fabric | 1.20.1 | 待验证 | 待验证 | 待验证 | 待验证 | 待填 | planned |
| Fabric | 最新冻结版 | 待验证 | 待验证 | 待验证 | 待验证 | 待填 | planned |
| Forge | 1.18.2 | 待验证 | 官方映射待验证 | 17 | ForgeGradle 待验证 | 待填 | planned |
| Forge | 1.20.1 | 待验证 | 官方映射待验证 | 待验证 | ForgeGradle 待验证 | 待填 | planned |
| NeoForge | 1.20.1 | 待验证 | 待验证 | 待验证 | ModDevGradle 待验证 | 待填 | planned |
| NeoForge | 最新冻结版 | 待验证 | 待验证 | 待验证 | ModDevGradle 待验证 | 待填 | planned |

只有经过依赖解析、编译、服务器启动 smoke、元数据检查和核心测试后，状态才能从 `planned` 变为 `verified`。

## 4. 功能支持状态

每个平台/版本使用统一状态：

- `full`：自动与手工验收全部通过；
- `degraded`：存在已批准的替代路径，不影响业务完成；
- `unsupported`：明确不支持，必须属于已批准范围外；
- `blocked`：目标内但尚未完成；
- `unverified`：有代码或产物但缺少当前证据。

禁止用“应该兼容”“理论可用”“1.x+”代替验证状态。

## 5. 兼容域

运行实例必须生成兼容域：

```text
platform + loader + minecraftVersion + itemCodecVersion + modpackFingerprint
```

- 无 Mod 的原版物品可由 codec 明确判定为跨域兼容；
- Mod 物品默认只能在相同或显式兼容的域领取；
- `modpackFingerprint` 必须由稳定、排序后的 Mod ID 与版本信息生成，并允许管理员配置兼容别名；
- 兼容别名不能绕过物品注册表和 codec 的实际验证。

## 6. 支持生命周期

- 新版本支持必须新建或更新 ADR，并先进入预发布矩阵；
- 移除版本至少提前一个正式版本公告，并由项目所有者批准；
- 安全修复优先覆盖所有仍受支持版本；
- 构建依赖失效不能静默删除发行线，应标记 `blocked` 并提供证据和替代方案。
