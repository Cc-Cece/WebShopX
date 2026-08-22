# M0：基线固化与历史原型审计

## 目标

建立后续所有回归的可信事实库；确认当前插件真实功能、构建和数据行为；只读评估 `feat/fabric-neoforge-mod`，不继承其完成声明。

## 允许修改

测试、测试夹具、基线脚本、CI 只读检查、`docs/mod-migration`。除为可测性所需的最小无行为重构外，不改生产逻辑。

## 必做调查

- 记录当前 commit、Java/Gradle/Node/pnpm、前端 ref、操作系统和依赖源；
- 清点源码、服务、命令、权限、事件、定时任务、API、Relay RPC、配置、资源、表/列/索引和 JAR 变体；
- 自动统计 Bukkit/Paper/Folia 耦合文件并按玩家、物品、GUI、命令、调度、配置、插件集成分类；
- 运行现有单元、静态、数据库和构建任务，区分真实失败、环境缺失和 flaky；
- 建立匿名数据库 fixture 及迁移前后对账方法；
- 记录关键 API golden contract，避免易变字段造成脆弱快照；
- 在真实 Paper/Folia 锚点执行启动、登录、下单、发货、重启 smoke；
- 用 `git show/diff` 审计历史 Mod 分支的模块、依赖、实现覆盖、测试和已过时点；
- 检查本地被忽略目录，证明不把生成物误提交。

## 交付物

- `implementation/reports/M0-implementation.md`；
- 机器可读功能/API/构建/数据库 inventory；
- `paperBaseline` 或等价聚合门禁；
- 性能基线；
- 历史原型采纳/拒绝清单，精确到 commit/组件；
- ADR-0001：实际基线与迁移起点；
- 填充支持矩阵第一版冻结候选。

## 验收

- 基线能在干净工作树复现；
- 每个现有外部能力都有 owner 和回归证据或明确测试缺口；
- 失败不得伪装为通过；
- 未修改生产行为；如必须修改，有 characterization test；
- Paper 产物 hash/内容差异得到解释。

## 回退

本阶段应只有测试/文档；可按独立提交回退。不得删除历史分支或本地目录。
