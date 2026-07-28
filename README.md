# WebShopX

WebShopX 是面向 Paper、Purpur、Spigot 与 Folia 服务端的 Web 商城插件，将官方商城、玩家市场、钱包、订单与发货、充值和后台管理整合在同一套系统中。

当前开发版本：`dev-v3.0.0`

## 功能概览

- Vue 3 + Vuetify 玩家端与管理后台
- 官方商城：指令、物品、回收、药水效果、团购券等商品类型
- 玩家市场：出售、收购、供货箱、自动补货、标签、搜索与成交记录
- 固定价、拍卖和动态定价交易模式
- `ShopCoin` / `GameCoin` 双币钱包，可选通过 Vault 对接游戏经济
- 自动发货、邮箱、手动领取、退款、兑换码和充值订单
- 管理员角色、细粒度权限、审计日志与业务账本
- 中文、英文消息与 Web 本地化中心
- SQLite、MySQL、MariaDB，以及基于 Redis 的多服集群同步
- 内置 HTTP、外置静态站点和 WebShopX Relay 三种部署模式
- Paper/Purpur、Spigot 与 Folia 的分运行时构建产物

## 运行环境

| Minecraft 运行时 | Java | Folia 构建 |
|---|---:|---|
| `1.18.2+` | 17 | 不提供 |
| `1.20.6+`（默认） | 21 | 提供 |
| `26.1+` | 25 | 提供 |
| `26.2+` | 25 | 提供 |

数据库支持 `sqlite`、`mysql`、`mariadb`。Vault、WebShopX-Payments 与 YuPay 均为可选软依赖。

## 快速开始

1. 从 Release 或 CI 产物中选择与你的 Minecraft 版本和服务端类型匹配的 JAR。
2. 将 JAR 放入服务端的 `plugins/` 目录并启动一次。
3. 检查 `plugins/WebShopX/config.yml`；默认 SQLite 可直接用于单服体验，生产环境推荐 MySQL/MariaDB。
4. 再次启动服务端，玩家执行 `/ws password <新密码>` 设置网页密码。
5. `internal` 模式下访问 `http://服务器地址:8819/`；管理入口为 `http://服务器地址:8819/admin.html`。

首次启动会启用引导管理员：

```text
用户名：admin
密码：admin123456
```

首次登录后请立即创建正式管理员，并关闭或修改 `webshop.admin-bootstrap`。公网部署还应配置反向代理、TLS 与访问控制，不要直接暴露默认管理凭据。

## 部署模式

### 内置模式（internal）

插件同时提供 API 和内置网页，适合快速部署：

```yaml
webshop:
  server-mode: internal
  public-url: "https://shop.example.com"
  embedded-http:
    host: 0.0.0.0
    port: 8819
```

### 外置模式（external）

插件仅提供 API，前端静态文件交由 Nginx 或 CDN 托管。通过 `webshop.embedded-http.public-api-url` 指定完整 API 根地址（应包含 `/api`），跨域时同时配置 `cors`。

### Relay 模式（relay）

无需向公网开放 Minecraft 服务器端口。插件会主动连接 WebShopX Relay；`url` 留空时使用官方 Relay，非 Relay 模式可完全省略此配置段。

```yaml
webshop:
  server-mode: relay

relay:
  url: ""
  access-key: "wsx_user_xxxxxxxxx"
```

安装 ID 会自动生成。请先在 Relay 网页创建实例，再绑定控制台中显示的待绑定服务器。后台设置页支持查看状态和立即重连。

## 数据库与集群

默认配置使用 SQLite：

```yaml
database:
  type: sqlite
  sqlite-file: plugins/WebShopX/webshopx.db
  pool-size: 10

cluster:
  role: standalone
  server-id: standalone
```

SQLite 仅支持 `cluster.role=standalone`，适用于单服或轻量部署。多服集群请使用 MySQL/MariaDB，并启用 Redis：

```yaml
database:
  type: mariadb
  host: 127.0.0.1
  port: 3306
  schema: webshop
  username: webshop
  password: change_me

cluster:
  role: master # master | node
  server-id: lobby-1

redis:
  enabled: true
  host: 127.0.0.1
  port: 6379
```

业务设置主要保存在数据库中，并通过 Web 管理后台维护；升级时旧配置会自动迁移。请同时备份数据库和 `plugins/WebShopX/` 数据目录。

## 常用命令

主命令为 `/webshopx`，别名 `/ws`。

| 命令 | 说明 |
|---|---|
| `/ws home` | 在聊天栏显示可点击的服务器主页链接；地址在“管理员配置 → 系统配置”中配置 |
| `/ws help` | 查看帮助 |
| `/ws password <新密码>` | 创建或重置网页登录密码 |
| `/ws market [gui]` | 打开市场 GUI |
| `/ws market sell <price> [amount] [currency]` | 出售手中物品 |
| `/ws market logs [count]` | 查看最近成交记录 |
| `/ws claim [all\|ODR-...\|MKT-...\|CLM-...\|MCL-...]` | 领取待发货内容 |
| `/ws mailbox claim` | 领取邮箱内容 |
| `/ws recharge <amount>` | 创建充值订单 |
| `/ws reload` | 重载配置和网页资源（管理员） |
| `/ws redeem create <shop> <game> [max] [perUserMax] [minutes] [code]` | 创建兑换码（管理员） |
| `/ws gamecoin <player> <delta>` | 调整 GameCoin（管理员） |
| `/ws shopcoin <player> <delta>` | 调整 ShopCoin（管理员） |

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `webshop.use` | 所有人 | 普通玩家功能 |
| `webshop.admin` | OP | 管理命令与后台操作 |
| `webshop.market.auction` | OP | 在限制规则要求时使用拍卖 |
| `webshop.market.limitation.bypass` | OP | 绕过市场创建/编辑限制 |

## 从源码构建

插件构建会自动调用相邻的 `webshopx-web` 前端工程。目录应如下：

```text
workspace/
├─ WebShopX/
└─ webshopx-web/
```

准备 JDK、Node.js 与 pnpm，先安装前端依赖：

```bash
cd webshopx-web
pnpm install
cd ../WebShopX
```

构建默认 `1.20.6+` 的全部适用变体：

```bash
./gradlew build
```

Windows 使用 `gradlew.bat`。可用 `targetRuntime` 选择目标运行时：

```bash
./gradlew packagePluginVariants -PtargetRuntime=1.18.2+
./gradlew packagePluginVariants -PtargetRuntime=1.20.6+
./gradlew packagePluginVariants -PtargetRuntime=26.1+
./gradlew packagePluginVariants -PtargetRuntime=26.2+
```

常用任务：

| Gradle 任务 | 产物/用途 |
|---|---|
| `shadowJar` | 包含运行依赖的完整 JAR |
| `slimJar` | 不包含运行依赖的精简 JAR |
| `fullLinuxJar` / `fullWinJar` | 仅保留对应平台 SQLite 原生库 |
| `fullFoliaJar` / `foliaSlimJar` | Folia 元数据变体 |
| `packagePluginVariants` | 构建目标运行时的全部适用变体 |
| `obfuscatedJar` | 混淆后的完整 JAR |
| `releaseObfuscated` | 生成稳定文件名的混淆发布包 |

产物位于 `build/libs/`，文件名包含版本、类型和运行时，例如：

```text
WebShopX-dev-v3.0.0-full-1.20.6+.jar
WebShopX-dev-v3.0.0-full-folia-1.19.4+.jar
WebShopX-dev-v3.0.0-full-linux-26.2+.jar
```

可覆盖版本号并生成快照：

```bash
./gradlew shadowJar -Pver=3.0.0
./gradlew shadowJar -Pver=3.0.0 -Psnapshot=true
```

若只需运行后端测试并跳过依赖前端资源的测试：

```bash
./gradlew test -PskipFrontendResourceTests=true
```

## 项目结构

```text
src/main/java/com/webshopx/       插件、HTTP API 与业务服务
src/main/resources/config.yml     最小启动配置
src/main/resources/messages/      游戏内消息资源
src/main/resources/db/            数据库结构
src/test/java/com/webshopx/       自动化测试
tools/sync-web-frontend.ps1       Vue 前端构建与资源同步
tools/i18n-validate.ps1           本地化校验
```

## 贡献与许可

欢迎提交 Issue 和 Pull Request。提交前请阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。

本项目自首次附带《[WebShopX 使用、研究与分发有限许可](./LICENSE)》的版本起，不再以开源许可证发布。中文许可证是唯一正式且具有解释优先权的许可文本。

此前已经按照 GPL-3.0 合法发布的版本继续适用其发布时的许可证，新许可证不追溯撤销此前已经有效授予的权利。
