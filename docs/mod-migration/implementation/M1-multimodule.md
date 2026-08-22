# M1：多模块与多版本构建骨架

## 目标

建立不污染现有插件工具链的 Gradle 多模块结构，并为三个 Loader 的版本族生成最小可启动 JAR。

## 前置

M0 completed；支持版本候选、现有 Paper 构建和历史原型结论已固化。

## 任务

- 通过 ADR 选择模块布局、多版本策略、映射、Gradle 插件和版本目录；
- 将现有根插件迁入/映射为 `webshopx-paper` 时保持旧 task、产物名和默认命令兼容；也可暂时保留根项目作为 Paper 模块；
- 创建空的 core、platform-api、testkit 和各 Loader 锚点模块；
- 添加 Fabric/Forge/NeoForge 正确元数据和最小服务端入口；
- 入口只记录统一版本/平台、注册健康命令并干净停止；
- 隔离 Loader 依赖、映射与 Java toolchain；
- 建立统一版本信息和产物命名；
- 添加依赖锁定/校验、许可证清单和元数据测试；
- CI 加入构建探针，但不发布正式包。

## 禁止

- 从历史分支整体 merge；
- 为让所有版本编译而在 core 使用反射 Minecraft 类；
- 删除 Paper full/slim/Folia/OS-native 变体；
- 在一个 JAR 混装多个 Loader 元数据并宣称通用，除非 ADR 与真实启动测试证明可行。

## 验收

- Paper M0 门禁完全通过；
- 每个锚点 Loader JAR 在 dedicated server 启动、输出身份、执行健康命令并停止；
- JAR 不含其他 Loader 或 Paper API 意外依赖；
- 干净缓存构建可复现，产物列表与 hash 被记录；
- 所有目标范围若尚未编译，支持矩阵明确标记 planned/blocked，不能声称支持。

## 回退

多模块提交可整体撤销后恢复 M0 根构建；不得要求数据库回滚。
