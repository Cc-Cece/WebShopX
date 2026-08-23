# M1 实施报告

状态：实施完成，等待 M0–M9 统一审核。

## 交付

- 根 Paper 工程保持默认任务、full/slim/Folia/OS-native 变体；新增 `platform-api`、`core`、`testkit`、`loader-runtime` 与七个精确 Loader 发行单元。
- Loader 元数据相互隔离；构建校验入口、元数据、Paper/Bukkit 泄漏、产物名和精确版本。
- 七个发行单元均在真实 dedicated server 完成启动、`webshopx-health` 命令、版本身份、完整 SQLite schema 初始化、原生 ServerStarted/ServerStopping 事件桥、`READY → STOPPED` 健康快照和干净停止；每端日志均确认 `native lifecycle ... installed=true` 与 `native server lifecycle ready`。
- Fabric API 精确冻结为 0.77.0+1.18.2、0.92.6+1.20.1、0.158.0+26.2。

## M1 时点真实启动证据（2026-08-23，Asia/Shanghai）

下列 hash 固化 M1 门禁时点，不是 M9 最终候选；最终 hash 只读取
`build/reports/mod-migration/release-manifest.json`。

| 单元 | SHA-256 | 结果 |
|---|---|---|
| Fabric 1.18.2 | `694ddf37d057ebc3555901174ded05cc13f1d4eba97fedd7945ba866f77e1993` | pass |
| Fabric 1.20.1 | `ddfaf9a1fe3edbfbed6aa2d53709d5a65d6edf5745b85327c2402ee40b8a7883` | pass |
| Fabric 26.2 | `6f5da90a37f3a27d8373dffc7eea5fc3b28033cf6547e30d2301cc133b12d72e` | pass |
| Forge 1.18.2 | `96cfab2b6876287795fe69085ff80332b5ce8cbd71eb2a6438ca619fae9cbbd3` | pass |
| Forge 1.20.1 | `a3b2da5028db866475a22c112572131a92147ab1116debc39d282815df468b6c` | pass |
| NeoForge 1.20.1 | `01e0c13fb2c8447511cb9894b2cfd63d9b34feef35b1ea3e9a84da32c7c0ee20` | pass |
| NeoForge 26.2 | `e53213b30d95022a38f9aa4157d6e30469b5fdad5d42b6d5ec60f7c0aaa9e46a` | pass |

复现入口：`docs/mod-migration/tools/dedicated-server-smoke.ps1`。临时服务器和日志不进入 Git。

## 实施中修复的真实兼容问题

- FML 拒绝非法模块名和 `dev-v3.0.0` 元数据版本；
- Gson、SLF4J 被误嵌入时触发 Forge JPMS split-package；
- NeoForge 1.20.1 仍使用 legacy Forge FML 注解命名空间；
- Forge 缺少 `pack.mcmeta`；
- NeoForge 26.2 在 Windows/JDK 25 的外部 Log4j DebugFile/Netty native 环境噪声被精确白名单处理，其余 ERROR 仍阻断。
