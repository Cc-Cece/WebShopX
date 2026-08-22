# M0 实现报告：main 基线与历史原型审计

- 基线业务 commit：`eca332bb8bbf723767b854cf8334c8cb1ddbe4cd`（`origin/main`）
- 计划叠加 commit：`b7bfc1ba452f6d9a83936a75a340942a1d3f075b`
- 实施分支：`feature/M0-main`
- 环境：Windows 10.0.19045，Gradle 9.3.1，JDK 17/21/25 可用，Node 24.11.1，pnpm 10.26.1

## 结果先行

迁移基线已改为最新正式 `origin/main`，旧 Mod 原型全部拒绝复用。新增可重复的 `paperBaseline` 聚合任务和机器可读 inventory；现有 Paper 单元/SQLite 测试、基线产物内容检查、干净前端构建以及真实 Paper/Folia 启停 smoke 通过。M0 仍不能宣称正式完成：玩家登录/下单/发货交易 smoke、MySQL Testcontainers 实例和完整性能压测证据尚未在本机形成，且独立正式审核尚未执行。

## 范围与清单

机器清单位于 `M0-inventory.json`，由 `docs/mod-migration/tools/baseline-inventory.ps1` 生成。当前盘点为：

- 114 个生产 Java 文件、20 个测试 Java 文件、43 个 `*Service` 类；
- 50 个包含 Bukkit/Paper、物品/背包、命令、事件、调度或经济 Provider 耦合的文件；
- 1 个根命令、4 个公开权限、136 个可静态识别的 HTTP 路径、13 个 Relay 方法；
- SQLite 基线含 36 张表和 66 个显式索引；
- 现有目标 runtime 为 `1.18.2+`、`1.20.6+`、`26.1+`、`26.2+`，根构建含 10 个 JAR 类任务。

清单中的 API 路径来自静态注册点，仅是回归 owner 清单，不代表每条路径已有 golden response。动态路径、查询参数和鉴权组合仍需在 M2/M9 契约套件中覆盖。

## 历史原型结论

审计对象：`feat/fabric-neoforge-mod`，分叉点 `a2e842c`，历史提交 `7aa4599..3b57145`。

| 候选 | 决定 | 证据与理由 |
|---|---|---|
| 多模块目录名 | 拒绝复制；M1 重新设计 | 仅有 Fabric/NeoForge，没有 Forge；版本和构建模型不满足当前矩阵 |
| `M2RuntimeBootstrap` | 拒绝 | 单文件膨胀至约 5,600 行，复制旧 HTTP/业务实现，违背共享当前业务核心与可审查边界 |
| Fabric/NeoForge 入口 | 拒绝 | 只依赖 Loader/FML 壳层，历史 smoke 未启动真实 Minecraft dedicated server |
| 四个早期 Gateway | 拒绝签名复用 | 缺少 inventory、item codec、lifecycle、paths、identity、capability 与封闭错误模型 |
| 历史测试 | 拒绝作为验收证据 | 主要运行独立 Java bootstrap，不满足原生物品 round-trip、离线发货和真实 Loader smoke |
| 历史业务类移动 | 拒绝 | 基于旧 main，整体差异会删除当前 API、支付、库存、Relay 和其他正式能力 |

项目所有者的书面决定和完整理由已固化为 ADR-0001。

## 验证记录

| 命令 | 结果 | 证据 |
|---|---|---|
| `.\gradlew.bat test --console=plain --stacktrace` | 通过 | 20 个测试类；Gradle 退出码 0 |
| `.\gradlew.bat paperBaseline --console=plain` | 通过 | 校验 `plugin.yml`、`config.yml`、SQLite schema、`WebShopPlugin.class` |
| `baseline-inventory.ps1` | 通过 | 生成 inventory、环境、前端状态、schema/API/耦合与 SHA-256 |
| `shadowJar -PfrontendDir=<clean-worktree> -PtargetRuntime=1.20.6+ --rerun-tasks` | 通过 | 前端 `origin/main@6a54139`，full JAR `3ea312…d26068` |
| Paper 1.20.6 build 151 + full JAR | 通过 | 11.892 秒 ready；WebShopX enable/HTTP/SQLite 成功；console stop 后 disable/Hikari shutdown |
| Folia 1.20.6 build 6 + full-Folia JAR | 通过 | 7.036 秒 ready；WebShopX enable/HTTP/SQLite 成功；console stop 后 disable/Hikari shutdown |

基线 plain JAR SHA-256：`77574545b2efa47cfa10b07777a70dbec3aec6640ebf7a3f4487ee79406d338c`（以最终 inventory 同步生成）。该 JAR 故意不包含运行时依赖，用于代码/元数据基线，不是发布产物。干净前端 full JAR SHA-256 为 `3ea312172c0b09510f336a46e2efdeba876496d50617ad1a41b3859408d26068`；Folia full JAR 为 `c95192cedabdea4411cb6f3191c741dd06e25862b3a26efbb2b56a55dea30ccc`。

## 数据与对账

已有 `SqliteSchemaScriptTest`、`SqliteBusinessSqlSmokeTest`、`SqliteConcurrencyRetryTest`、`SqliteOfficialSnapshotMigrationTest`、`MySqlBusinessSqlSmokeTest` 分别覆盖 schema、业务 SQL、SQLite 并发重试、官方快照迁移和 MySQL 路径。M9 对账必须在匿名生产近似快照上比较表行数、钱包余额、流水、订单状态和 ItemEnvelope hash；当前 schema 尚无 ItemEnvelope 字段，因此不得声称迁移已完成。

## 性能基线

本机冷执行 `paperBaseline` 约 17 秒，增量执行约 1 秒，plain JAR 约 9.8 MiB。Paper 1.20.6 首次 ready 11.892 秒，Folia 1.20.6 复用初始化后的 ready 7.036 秒；均使用 512 MiB–1 GiB JVM、视距/模拟距离 2。空闲内存、tick、API 延迟、批量发货、Redis 重连与 codec 指标尚未采集；这些是 M0 正式审核前的证据缺口，不以估算替代。

## 已知缺口与回退

- 相邻 `webshopx-web` 含 3 个用户未提交文件；未覆盖这些修改。干净构建改用独立 worktree `origin/main@6a54139`，并修复 Gradle 未把显式 `frontendDir` 计入任务输入的问题。
- Paper/Folia 启停、数据库初始化和 HTTP 启动已验证，但没有真实玩家登录、下单、发货和重启恢复 smoke 证据。
- 本机无 Docker；`MySqlBusinessSqlSmokeTest` 明确 skipped，不能把通用 `test` 绿色解释为 MySQL 已验证。
- Paper 1.20.6 日志将调度器报告为 `folia`，原因是 Paper 包含可反射获取的区域调度 API；当前启停无异常，但 M2 平台契约必须消除按类存在性判断产品身份的歧义。
- 回退只需撤销 M0 的 Gradle task、脚本、inventory、ADR 和报告；未改生产逻辑、schema、API 或配置。
