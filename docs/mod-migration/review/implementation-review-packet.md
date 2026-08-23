# M0–M9 统一审核包

本文件只提供实施事实与审核入口，不替审核 Agent 填写结论。

## 审核起点

- 业务 base：`origin/main@eca332bb8bbf723767b854cf8334c8cb1ddbe4cd`；
- 实施分支：`feature/M0-main`；
- 历史原型：只读拒绝复用，见 ADR-0001；
- 阶段状态：M0–M9 均为 `in_review`，FINAL 保持 `pending`；
- 正式发行：未执行。

## 提交链

| 阶段 | 主要实施提交 |
|---|---|
| M0 | `b076e7b` |
| M1 | `6d8dba0`, `92eabe0` |
| M2 | `c61a4b2`, `4d3aaee`, `79638c3`, `bf6fcd9`, `7416a53`, `a52200e` |
| M3 | `1d9ce7d`, `0c9c6a8`, `0f1f74d`, `45afc9f`, `ddbf5ae` |
| M4–M6 | `2a630a0`, `c79b25d`, `288a0a1`, `cc2c67b`, `fcb2beb` |
| M7 | `db30eec` |
| M8 | `207d09a`, `db30eec` |
| M9 | 以本报告所在最终提交为准 |

项目所有者明确要求 M0–M9 完整实施后统一调起审核，因此没有伪造逐阶段审核/merge commit。
审核 Agent 应依据 ADR-0002 决定统一提交链能否满足治理要求，并在正式 review report 中记录结论。

## 一键复现

```text
./gradlew releaseReadiness --stacktrace
```

外部真实服务门禁见 `.github/workflows/mod-migration.yml`；本机复现参数见 M8/M9 实施报告。
重点产物是 `release-manifest.json`、`mod-sbom.spdx.json`、`readiness.json`、性能 JSON、JUnit 报告
和七个 server smoke evidence。

## 优先审核风险

1. compatibility domain fail-closed 是否满足运营节点规划；
2. 离线 playerdata 原子替换/恢复标记与真实备份策略；
3. MariaDB transaction retry、delivery unknown/reconciliation 是否可能重复发货；
4. Loader capability 对可选 Provider 的 unsupported/unavailable 区分；
5. 当前会话无 in-app browser 导致的单一浏览器证据堵塞是否已在审核环境关闭；
6. support matrix 只宣传七个精确组合，不外推版本。

审核结论填写在 `final-review-report.md`，正式 checklist 保持由独立审核 Agent 操作。
