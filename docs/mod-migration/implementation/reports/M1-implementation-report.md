# M1 实施报告

状态：实施完成，等待 M0–M9 统一审核。

## 交付

- 根 Paper 工程保持默认任务、full/slim/Folia/OS-native 变体；新增 `platform-api`、`core`、`testkit`、`loader-runtime` 与七个精确 Loader 发行单元。
- Loader 元数据相互隔离；构建校验入口、元数据、Paper/Bukkit 泄漏、产物名和精确版本。
- 七个发行单元均在真实 dedicated server 完成启动、`webshopx-health` 命令、版本身份、完整 SQLite schema 初始化、`READY → STOPPED` 健康快照和干净停止。
- Fabric API 精确冻结为 0.77.0+1.18.2、0.92.6+1.20.1、0.158.0+26.2。

## 最终真实启动证据（2026-08-23，Asia/Shanghai）

| 单元 | SHA-256 | 结果 |
|---|---|---|
| Fabric 1.18.2 | `eb4f566a85698ef082102801b6ed316b12a96e870f8d40332f04e898be93b044` | pass |
| Fabric 1.20.1 | `2202af12eb0d53f69ed4f6eb8a3578f1a6fc80f4ba74dabcdf93da906fcaa555` | pass |
| Fabric 26.2 | `37d99da890c7fdc962b5f6b61c05fe4963a8d7225cacca12752bcb4904b888ba` | pass |
| Forge 1.18.2 | `0447a386100355e1285a72c6ccb123ff86131940545d683878613caa96686d0d` | pass |
| Forge 1.20.1 | `5aebfbd87476082f24ba329c9ea3edb56feae4edb8d1fe1eac30362cdd34a7d7` | pass |
| NeoForge 1.20.1 | `a6ae5495c9558a47ee8e7bc415db7b6e9e37086d5d88ce51fcd0a10f84588954` | pass |
| NeoForge 26.2 | `342e762cb9ae3a50a0ba14675e6e939d07c6a24e20a215e735bdd49e7c67f95d` | pass |

复现入口：`docs/mod-migration/tools/dedicated-server-smoke.ps1`。临时服务器和日志不进入 Git。

## 实施中修复的真实兼容问题

- FML 拒绝非法模块名和 `dev-v3.0.0` 元数据版本；
- Gson、SLF4J 被误嵌入时触发 Forge JPMS split-package；
- NeoForge 1.20.1 仍使用 legacy Forge FML 注解命名空间；
- Forge 缺少 `pack.mcmeta`；
- NeoForge 26.2 在 Windows/JDK 25 的外部 Log4j DebugFile/Netty native 环境噪声被精确白名单处理，其余 ERROR 仍阻断。
