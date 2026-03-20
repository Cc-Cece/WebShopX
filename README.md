# WebShopX

WebShopX 是一个面向 `Paper / Spigot` 服务器的 Web 商店插件，把官方商城、玩家市场、钱包、订单流转和后台管理整合进同一套系统。

`WebShopX` is a web-first commerce plugin for `Paper / Spigot`, combining B2C shop, C2C player market, wallet, order flow, and admin tools in one project.

## 项目概览

- 官方商城和玩家市场共用一套账号、钱包、订单和发货逻辑
- 内置玩家端网页和管理后台，不依赖额外的前端工程
- 支持 `ShopCoin / GameCoin` 双币体系
- 可选接入 `Vault`，让 `GameCoin` 对接现有经济插件
- 支持自动发货、手动领取、退款、兑换码、团购券
- 支持内置 HTTP 模式，也支持把前端静态资源托管到 `Nginx / CDN`

## 主要功能

### 玩家端

- 使用 Minecraft 用户名和网页密码登录
- 查看余额、最近钱包流水、订单记录
- 使用兑换码入账
- 进行 `ShopCoin / GameCoin` 双向兑换
- 浏览并购买官方商品
- 浏览玩家市场、玩家店铺和自己的上架
- 修改自己上架的价格、备注、状态
- 购买后自动发货；失败时转为 `/ws claim` 手动领取

### 官方商城

- 支持多种商品类型
  - 指令商品
  - 物品商品
  - 回收商品
  - 药水效果
  - 团购券
- 支持上下架时间、库存、限购和备注
- 支持后台直接管理商品与活动状态

### 玩家市场

- 普通上架
- 供货箱上架
- 自动补货
- 市场筛选、排序、搜索
- 卖家成交记录查询
- 市场手续费 / 税率配置

### 管理后台

- 管理员登录与会话鉴权
- 商品、订单、兑换码、市场、用户统一管理
- 用户支持能力
  - 重置密码
  - 解绑账号
  - 强制下线
  - 调整钱包余额
- 审计日志
- 细粒度管理员权限与模板

## 运行环境

- `Java 21`
- `Paper 1.20.6+` 或兼容的 `Spigot`
- `MariaDB / MySQL`
- `Vault` 可选

## 快速开始

1. 构建或下载插件 JAR。
2. 将插件放入服务器的 `plugins/` 目录。
3. 启动一次服务器，生成默认配置。
4. 编辑 `plugins/WebShopX/config.yml`，至少修改数据库连接信息。
5. 重启服务器。
6. 玩家在游戏内执行 `/ws password <新密码>` 创建或重置网页登录密码。
7. 打开玩家端 `http://你的地址:8819/`。
8. 打开管理端 `http://你的地址:8819/admin.html`。

## 首次部署注意事项

- 默认数据库占位配置为 `webshop / change_me`。如果保持默认值，插件会拒绝启动。
- 默认启用了管理员引导账号：
  - 用户名：`admin`
  - 密码：`admin123456`
- 公开环境部署前，务必修改或关闭 `webshop.admin-bootstrap`。
- 默认内置 HTTP 监听 `0.0.0.0:8819`，公网部署时建议配合反向代理、TLS 和访问控制。

## Web 运行模式

`config.yml` 中的 `webshop.server-mode` 支持两种模式：

- `internal`
  - 插件同时提供 API 和静态网页
  - 适合快速部署和本地测试
- `external`
  - 插件仅提供 API
  - 静态资源会导出到 `plugins/WebShopX/web/`
  - 适合前端交给 `Nginx`、面板或 CDN 托管

如果使用 `external` 模式，可通过 `webshop.api-base-url` 指定前端请求的 API 地址。

## 常用命令

| 命令 | 说明 |
|---|---|
| `/webshopx help` | 查看帮助 |
| `/webshopx password <新密码>` | 在游戏内创建或重置网页登录密码 |
| `/webshopx market [gui]` | 打开市场 GUI |
| `/webshopx market sell <price> [amount] [currency]` | 兼容旧式上架命令 |
| `/webshopx market logs [count]` | 查看自己的最近成交记录 |
| `/webshopx claim [all\|ODR-...\|MKT-...\|CLM-...\|MCL-...]` | 领取待发货内容 |
| `/webshopx reload` | 重载配置与内置网页 |
| `/webshopx redeem create <shop> <game> [max] [perUserMax] [minutes] [code]` | 创建兑换码 |

别名：`/ws`

## 权限节点

| 权限 | 说明 |
|---|---|
| `webshop.use` | 普通玩家使用权限 |
| `webshop.admin` | 后台与管理命令权限 |

## 构建

日常开发直接运行：

```bash
./gradlew build
```

常用构建任务：

```bash
./gradlew shadowJar
./gradlew obfuscatedJar
./gradlew releaseObfuscated
```

构建产物位于 `build/libs/`：

- `WebShopX-<version>.jar`
- `WebShopX-<version>-obf.jar`
- `WebShopX-release-obf.jar`

也支持通过 Gradle 属性覆盖版本号：

```bash
./gradlew shadowJar -Pver=1.0.6
./gradlew shadowJar -Psnapshot=true
./gradlew shadowJar obfuscatedJar -Pver=1.0.6 -Psnapshot=true
```

## 配置要点

- `database.*`
  - 数据库连接配置，必须改成真实值
- `webshop.embedded-http.*`
  - 内置 HTTP 服务监听地址、端口和静态资源目录
- `webshop.admin-bootstrap.*`
  - 首次管理员账号引导配置
- `exchange.*`
  - 双币兑换开关和汇率
- `economy.market.*`
  - 玩家市场手续费和税率
- `webshop.market.supply.*`
  - 自动补货和供货箱相关参数
- `currency.*`
  - 两种货币的名称和缩写

## 项目结构

```text
src/main/java/com/webshopx/   核心插件逻辑、HTTP API、业务服务
src/main/resources/web/       内置玩家端与管理端页面
src/main/resources/config.yml 默认配置
src/main/resources/plugin.yml Bukkit / Paper 插件元数据
src/test/java/com/webshopx/   单元测试
```

## 当前状态

这个仓库不是纯概念原型，当前版本已经包含可运行的完整链路：

- 玩家登录与绑定
- 官方商城
- 玩家市场
- 钱包与兑换
- 订单、退款、发货与领取
- 管理后台

目前也有一些明确边界：

- UI 和默认文案以中文为主
- `Redis` 配置项已预留，但当前版本尚未接入业务流程
- 更适合中小到中大型中文服直接落地，再按自身业务继续扩展

## 开发与贡献

欢迎提交 Issue 和 Pull Request。贡献流程见 [CONTRIBUTING.md](./CONTRIBUTING.md)。

## 许可证

本项目基于 [GPL-3.0](./LICENSE) 许可证开源。
