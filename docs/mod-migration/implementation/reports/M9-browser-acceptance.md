# M9 浏览器验收记录

- 日期：2026-08-23（Asia/Shanghai）
- 候选：Fabric 1.18.2 / Loader 0.19.3 / Java 17
- HTTP 地址：`http://127.0.0.1:8123`
- 候选 JAR：`webshopx-fabric-1.18.2-dev-v3.0.0.jar`

## 已验证

- 当前候选 JAR 在真实 Fabric dedicated server 启动成功；
- `GET /` 返回 `200`，页面包含 `WebShopX` 与 `Server API is ready.`；
- `GET /health` 返回 `200` 与 `status=UP`；
- 健康响应报告 `minecraftVersion=1.18.2`、`loaderVersion=0.19.3`；
- landing 与 health 均返回 `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'`；
- `releaseReadiness --rerun-tasks --stacktrace` 的 71 个任务全部重新执行并通过。

## 环境堵塞

会话已发现两个 Chrome 扩展实例，但 Browser 组件在验收期间更新，活动控制服务与新组件版本
不匹配，连接在页面导航前失败。旧组件文件同时被缓存更新替换，当前会话无法安全恢复。按浏览器
控制规则，不使用其他浏览器后端伪造交互证据。

重启 Codex 后重新打开 Chrome 连接，访问 `/` 与 `/health`，检查可见内容、状态、安全响应头和
控制台错误，即可关闭此项；无需修改 WebShopX 代码或数据。
