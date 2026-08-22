# M0 Paper/Folia 真实服务器 smoke 摘要

- 日期：2026-08-23（Asia/Shanghai）
- Java：Temurin/Oracle-compatible JDK 21 runtime
- 插件业务基线：`origin/main@eca332b`
- 前端：`webshopx-web origin/main@6a54139`，干净 detached worktree
- 参数：`-Xms512M -Xmx1G`，offline mode，动态 Minecraft 端口，测试世界，视距/模拟距离 2

## Paper

- Server：Paper 1.20.6 build 151（stable）
- Server SHA-256：`4b011f5adb5f6c72007686a223174fce82f31aeb4b34faf4652abc840b47e640`
- Plugin：`WebShopX-dev-v3.0.0-full-1.20.6+.jar`
- Plugin SHA-256：`3ea312172c0b09510f336a46e2efdeba876496d50617ad1a41b3859408d26068`
- 结果：退出码 0；`Done (11.892s)`；WebShopX enable、SQLite/Hikari、HTTP `0.0.0.0:8819` 成功；console `stop` 后 WebShopX disable 与连接池 shutdown；无 ERROR/SEVERE。

## Folia

- Server：Folia 1.20.6 build 6（alpha；该版本无 stable build）
- Server SHA-256：`a30625d8824b03aae64898b001b46bdc4424b0e5caee1a370af7b444d8ec361a`
- Plugin：`WebShopX-dev-v3.0.0-full-folia-1.19.4+.jar`
- Plugin SHA-256：`c95192cedabdea4411cb6f3191c741dd06e25862b3a26efbb2b56a55dea30ccc`
- 结果：退出码 0；`Done (7.036s)`；WebShopX enable、SQLite/Hikari、HTTP 成功；console `stop` 后 WebShopX disable 与连接池 shutdown；无 ERROR/SEVERE。

## 负向检查

普通 Paper full JAR 安装到 Folia 时被服务器明确拒绝：`not marked as supporting Folia`。换用专用 Folia 元数据产物后成功。该拒绝证明两个发行变体没有通过错误元数据混装来绕过加载器检查。

## 未覆盖

本 smoke 没有真实客户端，不能证明登录、权限、命令补全、下单、物品发货、离线背包与重启恢复；这些仍是 M0/M2 的阻塞证据项。
