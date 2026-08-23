# M9 实施进度报告

已实现：精确构建矩阵、Paper/core/platform/Loader 门禁、元数据隔离、真实 smoke 工具、机器可读 release manifest、SPDX 2.3 SBOM、支持矩阵一致性检查和 CI artifact 上传。

本地门禁：

```text
./gradlew paperBaseline
./gradlew coreContractTest platformContractTest
./gradlew modMatrixAssemble verifyModMetadata modEntrypointSmoke
./gradlew verifySupportMatrix generateModReleaseManifest generateModSbom
```

尚未完成：M4–M8 全功能矩阵、真实集群/迁移/回滚演练、浏览器关键路径、安全扫描和 RC readiness。因此 `releaseReadiness` 当前只是“本地可复现门禁集合”，不是正式发布批准。
