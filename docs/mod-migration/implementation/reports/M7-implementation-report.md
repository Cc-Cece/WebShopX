# M7 实施报告：modern 物品与 server-only 路径

状态：实施完成，等待 M0–M9 统一审核。

## 结论

Fabric/NeoForge 26.2 与 legacy 单元共享有界 `ItemEnvelope` 契约，但使用各版本原生 NBT/data
component codec。ADR-0005 将 RC 的 native 允许范围冻结为精确同域；ADR-0004 冻结 server-only
交互，不发布客户端 JAR，也不把任何业务判断放到客户端。

## 实机验证

2026-08-23 在七个真实 dedicated server 上使用最终模块 JAR 执行：首次启动、schema 初始化、
`webshopx-health`、`webshopx-item-roundtrip`、STOPPED health 与干净进程停止。每个服务器从自己的
原生注册表生成 8 类代表物品并完成 encode/decode，同时拒绝 foreign domain、损坏 hash 和未知
codec。最终候选 hash 由 M9 的 `generateModReleaseManifest` 统一生成，避免在源码报告中保存过期
hash。

## 限制即契约

跨 Loader/版本转换并非降级的“部分支持”，而是未进入 RC 允许列表的显式不支持行为。第三方
Mod 物品只在注册表存在且 compatibility domain 精确一致时解码；缺失 Mod 保留 envelope 和发货
任务。网页、命令和标准服务端容器是无客户端完整路径。
