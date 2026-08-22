# 测试与 CI 设计

## 1. 测试层次

1. core 单元测试：业务规则、金额、幂等、状态机、codec 策略；
2. contract tests：每个平台端口共享同一套行为测试；
3. 数据库测试：SQLite + Testcontainers MySQL/MariaDB，迁移与并发；
4. adapter 测试：Loader 生命周期、命令、权限、调度和原生物品；
5. dedicated-server smoke：真实 JAR 启动、操作、重启、停止；
6. 混合集群测试：Paper 与 Mod 节点共享 MySQL/Redis/Relay；
7. Web/API 契约与浏览器关键路径；
8. Release 产物、SBOM/许可证、hash 和干净环境复现。

## 2. CI 分层

- PR fast：格式/静态检查、core、受影响模块、Paper 基线、元数据检查；
- Mxx gate：该阶段全部版本族、数据库、server smoke、契约测试；
- nightly：完整 Loader × Minecraft × Java 矩阵、混合集群、长时重连和性能趋势；
- release candidate：干净缓存全构建、全部门禁、迁移/回滚、签名和产物清单。

## 3. 矩阵元数据

CI 不应复制大量 YAML。把冻结版本、Java、Gradle task、JAR 期望名和 smoke 镜像存入机器可读矩阵，由工作流生成 job。矩阵修改必须经过 ADR/支持矩阵同步检查。

## 4. 证据要求

每个 job 上传：测试报告、启动日志、环境清单、JAR hash、失败诊断和（适用时）数据库对账。日志必须脱敏。不能以 `-x test` 的包装任务作为完成证据。

## 5. 本地命令约定

M0 确认后在此维护准确命令。最低目标：

```text
./gradlew paperBaseline
./gradlew coreContractTest
./gradlew modMatrixAssemble
./gradlew modServerSmoke
./gradlew mixedClusterTest
./gradlew releaseReadiness
```

名称可以经 ADR 调整，但必须有单一聚合门禁，且 Windows/CI 路径可执行。

## 6. 外部前端

前端仓库的 ref 必须固定到与后端 PR 对应的 commit/分支。后端 CI 不得默认拿前端 `main` 证明兼容。涉及 Web 契约的 Mxx 应在两个仓库分别建立 PR，并在实现报告中互链。

## 7. Flaky 管理

失败测试不得直接重跑至绿色后忽略。重跑用于诊断，必须记录首次失败；确认 flaky 后建立问题、负责人和期限。数据安全、幂等、发货与迁移测试不得隔离后继续 Release。
