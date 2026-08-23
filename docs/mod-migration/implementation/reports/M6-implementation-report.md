# M6 实施报告：NeoForge 发行线

状态：实施完成，等待 M0–M9 统一审核。

NeoForge 1.20.1/47.1.106 使用 legacy Forge FML 命名空间，26.2/26.2.0.65 使用现代 NeoForge
元数据与 Java 25；这两个差异由独立发行单元而非运行时版本猜测处理。真实 dedicated server
验证 lifecycle、命令、HTTP、共享 schema、背包/发货和 native NBT/data-component round-trip。

现代单元同样执行 8 类注册表语料及 foreign domain/hash/codec 拒绝。Windows/JDK 25 的已知
外部 Log4j/Netty 环境噪声只在 smoke 脚本中精确过滤，其他 ERROR/exception 仍使门禁失败。
