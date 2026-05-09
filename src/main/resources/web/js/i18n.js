(function initWebShopXI18n() {
  const RUNTIME_CONFIG = window.WEBSHOPX_CONFIG || {};
  const LOCALE_STORAGE_KEY = "webshopx_locale";
  const BASE_SUPPORTED_LOCALES = Object.freeze(["zh-CN", "en-US"]);
  const LOCALE_META_API_PATH = "/api/meta/locales";
  const DEFAULT_BUNDLE_FALLBACK_LOCALE = "en-US";
  const BASE_LOCALE_ENTRIES = Object.freeze([
    { locale: "zh-CN", name: "Simplified Chinese", nativeName: "简体中文", source: "built-in" },
    { locale: "en-US", name: "English", nativeName: "English", source: "built-in" },
  ]);
  const ATTRIBUTE_NAMES = ["placeholder", "title", "aria-label", "alt"];
  const SKIP_PARENTS = new Set(["CODE", "SCRIPT", "STYLE", "TEXTAREA"]);
  const BUNDLE_CACHE = new Map();
  let SERVER_LOCALE_META = null;
  let SERVER_LOCALE_ENTRIES = null;

  const EN_EXACT = Object.freeze({
    "WebShopX - MC网页商店系统": "WebShopX - Minecraft Web Shop",
    "WebShopX 仪表盘": "WebShopX Dashboard",
    "WebShopX 控制台": "WebShopX Control Panel",
    "切换暗色": "Dark mode",
    "切换亮色": "Light mode",
    "未登录": "Not signed in",
    "功能标签": "Navigation tabs",
    "管理功能": "Admin navigation",
    "账号登录": "Player Sign In",
    "管理员登录": "Admin Sign In",
    "钱包资产": "Wallet",
    "官方商城": "Official Shop",
    "官方商品": "Official Products",
    "玩家市场": "Player Market",
    "订单记录": "Orders",
    "订单": "Orders",
    "使用教程": "Guide",
    "操作日志": "Logs",
    "兑换码": "Redeem Codes",
    "经济设置": "Economy",
    "账号管理": "Accounts",
    "管理员": "Admins",
    "审计日志": "Audit Log",
    "登录前准备": "Before You Sign In",
    "设置完成后，直接使用你的 Minecraft 用户名和该密码登录网页即可。":
      "Once set, sign in on the website with your Minecraft username and that password.",
    "用户名": "Username",
    "密码": "Password",
    "输入 Minecraft 用户名": "Enter your Minecraft username",
    "输入游戏内设置的密码": "Enter the password you set in-game",
    "立即登录": "Sign In",
    "密码说明": "Password Notes",
    "当前登录玩家": "Current Player",
    "已完成身份认证，可直接访问钱包、商城和玩家市场。":
      "Authentication complete. You can now access your wallet, official shop, and player market.",
    "登录成功": "Sign-in Successful",
    "欢迎回来！": "Welcome back!",
    "玩家头像": "Player avatar",
    "昵称": "Name",
    "退出登录": "Sign Out",
    "网页币": "Web Coin",
    "游戏币": "Game Coin",
    "刷新余额": "Refresh Balance",
    "等待刷新余额": "Waiting for balance refresh",
    "兑换码入账": "Redeem Code Credit",
    "输入管理员发放的兑换码，资产会即时写入钱包。":
      "Enter a redeem code issued by an admin and the funds will be credited to your wallet immediately.",
    "安全提示": "Security Reminder",
    "兑换码通常一次性使用，请勿在公共频道或截图中暴露兑换码内容。":
      "Redeem codes are usually single-use. Do not expose them in public chat or screenshots.",
    "兑换码": "Redeem Code",
    "请输入兑换码": "Enter a redeem code",
    "立即兑换": "Redeem Now",
    "等待兑换操作": "Waiting for redeem action",
    "货币互换": "Currency Exchange",
    "从": "From",
    "到": "To",
    "数量": "Quantity",
    "执行兑换": "Convert",
    "等待加载记录": "Waiting to load history",
    "最近变动": "Recent Activity",
    "这里只显示网页系统相关的最近钱包流水，不包含 Vault 外部直接变动。":
      "Only WebShopX wallet activity is shown here. External Vault changes are not included.",
    "官方商城（B2C）": "Official Shop (B2C)",
    "由服务器官方维护的商品，可直接下单。":
      "Products maintained by the server staff. Orders can be placed directly.",
    "刷新商品": "Refresh Products",
    "关键词": "Keyword",
    "商品标题 / SKU / 备注 / 物品关键词": "Product title / SKU / remark / item keyword",
    "搜索": "Search",
    "清空": "Clear",
    "排序": "Sort",
    "默认排序": "Default",
    "价格从低到高": "Price: low to high",
    "价格从高到低": "Price: high to low",
    "标题 A-Z": "Title A-Z",
    "标题 Z-A": "Title Z-A",
    "库存从多到少": "Stock: high to low",
    "高级筛选": "Advanced Filters",
    "商品类型": "Product Type",
    "全部": "All",
    "指令": "Command",
    "出售物品": "Sell Item",
    "药水效果": "Potion Effect",
    "回收物品": "Recycle Item",
    "团购券": "Group-Buy Voucher",
    "团购券（线下核销）": "Group-Buy Voucher (manual redemption)",
    "币种": "Currency",
    "物品 ID": "Item ID",
    "例如 DIAMOND": "For example: DIAMOND",
    "最低价": "Min Price",
    "最高价": "Max Price",
    "应用筛选": "Apply Filters",
    "重置筛选": "Reset Filters",
    "查看个人购买与退款记录。": "Review your purchases and refunds.",
    "刷新订单": "Refresh Orders",
    "暂无订单": "No orders yet",
    "玩家市场（C2C）": "Player Market (C2C)",
    "物品/卖家关键词": "Item / seller keyword",
    "最新": "Newest",
    "最早": "Oldest",
    "数量从多到少": "Quantity: high to low",
    "数量从少到多": "Quantity: low to high",
    "市场在售": "Public Listings",
    "玩家店铺": "Player Stores",
    "我的上架": "My Listings",
    "隐藏本人商品": "Hide my listings",
    "刷新": "Refresh",
    "暂无市场数据": "No market data",
    "按顺序完成下面几个步骤即可开始使用。":
      "Follow these steps to get started.",
    "1. 注册与登录": "1. Register and Sign In",
    "2. 钱包与兑换": "2. Wallet and Exchange",
    "3. 官方商城购买": "3. Buy from the Official Shop",
    "4. 普通市场上架": "4. Manual Market Listing",
    "5. 供货箱上架": "5. Supply Listing",
    "6. 市场管理": "6. Manage Listings",
    "7. 订单与发货": "7. Orders and Delivery",
    "8. 注意事项": "8. Notes",
    "登录后先查看“钱包资产”。兑换码、货币互换、商城购买、市场交易都会在这里反映余额变化；“最近变动”只记录网页系统相关流水。":
      "After signing in, check Wallet first. Redeem codes, exchanges, shop purchases, and market trades all affect balances here. Recent Activity only records WebShopX wallet entries.",
    "在市场 GUI 选择供货箱上架，先右键绑定供货箱（支持大箱子、箱子、木桶、潜影盒），再放入 1 个模板物品，点击下一步后在聊天栏输入单价即可创建。单次提取量、中转上限、备注等补充设置请到网页修改。":
      "In the market GUI, choose the supply-listing mode, right-click to bind the container (double chest, chest, barrel, or shulker box), place one template item, then enter the unit price in chat after pressing Next. Adjust transfer size, transit stock cap, and remark on the website.",
    "“我的上架”可查看并管理自己的商品。普通上架和供货箱上架都支持暂停、恢复、下架；供货箱商品还支持手动刷新补货。价格、币种、备注与供货参数统一在网页端调整。":
      "\"My Listings\" lets you manage your own entries. Both manual and supply listings support pause, resume, and unlist. Supply listings also support manual refresh. Price, currency, remark, and supply settings are edited on the website.",
    "供货箱在上架期间会被市场保护，破坏前请先停止上架或解除绑定；若供货箱损坏或失效，系统会自动暂停对应供货商品。团购券商品购买后会生成兑换码，需由管理员核销。":
      "Supply containers are protected while listed. Stop the listing or remove the binding before breaking them. If a container is lost or invalid, the related supply listing is paused automatically. Group-buy vouchers generate redeem codes that must be consumed by an admin.",
    "排障建议": "Troubleshooting",
    "若遇到异常，请查看日志，必要时可带上日志向管理员或开发者求助。":
      "If something goes wrong, review the logs first. Share them with an admin or developer if needed.",
    "确认操作": "Confirm Action",
    "取消": "Cancel",
    "确认": "Confirm",
    "价格调整": "Price Update",
    "修改价格": "Change Price",
    "请输入新的上架价格。": "Enter a new listing price.",
    "当前价格": "Current Price",
    "新价格": "New Price",
    "当前管理员": "Current Admin",
    "请使用管理员账号进入后台。":
      "Sign in with an admin account to access the control panel.",
    "账号": "Account",
    "用户名或 UUID": "Username or UUID",
    "输入密码": "Enter password",
    "登录": "Sign In",
    "退出": "Sign Out",
    "等待登录": "Waiting to sign in",
    "安全提醒": "Security Reminder",
    "不要在公共环境暴露管理员凭据。":
      "Do not expose admin credentials in a public environment.",
    "显示当前会话的角色与权限。":
      "Shows the role and permissions of the current session.",
    "权限控制": "Permission Control",
    "不同管理员角色可访问不同模块，权限不足时会提示拒绝。":
      "Different admin roles can access different modules. Permission failures are reported explicitly.",
    "兑换码管理": "Redeem Code Management",
    "生成兑换码并查看最近记录。":
      "Generate redeem codes and review recent ones.",
    "最大使用次数": "Max Uses",
    "单账号最大使用次数": "Per-Account Max Uses",
    "有效期（分钟，可空）": "Validity (minutes, optional)",
    "留空=永久": "Leave blank = permanent",
    "自定义兑换码（可空）": "Custom redeem code (optional)",
    "可选自定义 code": "Optional custom code",
    "生成兑换码": "Create Redeem Code",
    "复制最新兑换码": "Copy Latest Code",
    "等待生成": "Waiting to create",
    "提示": "Tip",
    "兑换码默认一次性。若开启多次使用，请注意有效期与发放对象。":
      "Redeem codes are single-use by default. If you allow multiple uses, watch the validity period and recipients carefully.",
    "官方商品管理": "Official Product Management",
    "通过子标签拆分商品编辑、商品列表与团购券核销。":
      "Use the sub-tabs to switch between editing, product listing, and voucher consumption.",
    "官方商品管理子标签": "Official product sub-tabs",
    "新增/编辑商品": "Create / Edit Product",
    "商品列表": "Product List",
    "团购券核销": "Voucher Consumption",
    "基础信息": "Basic Info",
    "SKU（唯一标识）": "SKU (unique identifier)",
    "例如 vip_week": "For example: vip_week",
    "标题（玩家端展示）": "Title (player-facing)",
    "商品标题": "Product title",
    "价格": "Price",
    "交易币种": "Trade Currency",
    "上架时间": "Publish Time",
    "下架时间": "Unpublish Time",
    "商品上下架时间按配置时区处理。":
      "Publish and unpublish times follow the configured business time zone.",
    "商品信息": "Product Details",
    "类型": "Type",
    "启用": "Enabled",
    "停用": "Disabled",
    "命令模板（指令类）": "Command template (command type)",
    "例如 give %player% minecraft:diamond %amount%": "For example: give %player% minecraft:diamond %amount%",
    "物品材质（出售/回收）": "Item material (sell / recycle)",
    "例如 DIAMOND / 钻石": "For example: DIAMOND / Diamond",
    "药水效果（POTION）": "Potion effect (POTION)",
    "例如 speed": "For example: speed",
    "药水时长（秒）": "Potion duration (seconds)",
    "药水等级（0=I）": "Potion amplifier (0=I)",
    "商品备注（玩家端展示）": "Remark (player-facing)",
    "可填写用途说明、注意事项、团购兑换说明等":
      "Use this for usage notes, precautions, or voucher instructions.",
    "库存模式": "Stock Mode",
    "有限库存": "Finite Stock",
    "无限库存": "Unlimited Stock",
    "总库存": "Total Stock",
    "单玩家限购": "Per-Player Limit",
    "留空 = 不限购": "Leave blank = no limit",
    "库存预览": "Stock Preview",
    "当前状态": "Current Status",
    "库存货值预览": "Inventory Value Preview",
    "待计算": "Pending calculation",
    "保存商品": "Save Product",
    "等待操作": "Waiting for action",
    "商品检索": "Product Search",
    "标题 / SKU / 材质 / 备注": "Title / SKU / material / remark",
    "类型筛选": "Type Filter",
    "启用筛选": "Enable Filter",
    "等待加载商品列表": "Waiting to load product list",
    "核销团购兑换码": "Consume Group-Buy Voucher",
    "例如 GB-XXXXXXXXXXXX": "For example: GB-XXXXXXXXXXXX",
    "等待核销": "Waiting to consume",
    "订单列表": "Order List",
    "查看最近订单与状态。": "Inspect recent orders and their status.",
    "状态": "Status",
    "待发放": "Pending Delivery",
    "待领取": "Awaiting Claim",
    "已发放": "Delivered",
    "已退款": "Refunded",
    "失败": "Failed",
    "已回收": "Recycled",
    "用户ID": "User ID",
    "订单号": "Order No.",
    "用户昵称（模糊搜索）": "Username (fuzzy search)",
    "综合关键词": "Combined Keyword",
    "订单号 / SKU / 标题": "Order No. / SKU / title",
    "等待刷新": "Waiting to refresh",
    "兑换方向与比例": "Exchange Direction and Ratio",
    "开关": "Toggle",
    "比例": "Ratio",
    "保存兑换配置": "Save Exchange Config",
    "交易手续费与税率": "Trade Fee and Tax",
    "买家支付税率，卖家扣除手续费。":
      "The buyer pays tax, and the seller pays the platform fee.",
    "交易手续费（%）": "Trade Fee (%)",
    "交易税率（%）": "Trade Tax (%)",
    "保存手续费/税率": "Save Fee / Tax",
    "Vault 对接状态": "Vault Integration",
    "等待加载": "Waiting to load",
    "玩家市场管理": "Player Market Management",
    "查看全部上架并支持强制下架。":
      "View all listings and force-unlist when required.",
    "状态筛选": "Status Filter",
    "在售": "Active",
    "已售": "Sold",
    "已下架": "Unlisted",
    "卖家": "Seller",
    "卖家昵称（模糊）": "Seller name (fuzzy)",
    "买家": "Buyer",
    "买家昵称（模糊）": "Buyer name (fuzzy)",
    "材质": "Material",
    "材质英文或中文名": "Material name in English or Chinese",
    "账号查询": "Account Lookup",
    "输入昵称或 UUID 查询账号。":
      "Search for an account by username or UUID.",
    "用户名 / UUID": "Username / UUID",
    "例如 玩家名或 UUID": "For example: a username or UUID",
    "查询账号": "Lookup Account",
    "等待查询": "Waiting to search",
    "账号支持操作": "Account Support Actions",
    "对已查询的账号执行支持操作。":
      "Run support actions against the account you just looked up.",
    "新密码": "New Password",
    "8-64 位": "8-64 characters",
    "重置密码": "Reset Password",
    "解绑": "Unbind",
    "强制下线": "Force Logout",
    "调整币种": "Currency to Adjust",
    "调整数量（可负数）": "Amount Delta (can be negative)",
    "原因": "Reason",
    "例如 补偿/退款": "For example: compensation / refund",
    "调整余额": "Adjust Balance",
    "已注册用户列表": "Registered Users",
    "支持按关键词筛选，并可直接载入到操作区进行编辑。":
      "Filter by keyword and load entries directly into the editing panel.",
    "刷新列表": "Refresh List",
    "用户名 / UUID / 用户ID": "Username / UUID / User ID",
    "隐藏已停用、未绑定或无用卡片": "Hide disabled, unbound, or inactive cards",
    "等待加载列表": "Waiting to load list",
    "管理员编辑": "Admin Editor",
    "超级管理员可创建子级管理员，并精确分配权限。":
      "Super admins can create delegated admins and assign permissions precisely.",
    "清空表单": "Clear Form",
    "用户标识": "User Identifier",
    "模板": "Template",
    "自定义": "Custom",
    "快速模板": "Quick Templates",
    "先选择一个模板，再按需要微调权限。":
      "Pick a template first, then fine-tune the permissions as needed.",
    "管理员类型": "Admin Type",
    "子级管理员": "Delegated Admin",
    "超级管理员": "Super Admin",
    "保存管理员": "Save Admin",
    "管理员列表": "Admin List",
    "支持载入编辑、启用和禁用管理员。":
      "Load admins into the editor and enable or disable them.",
    "等待加载管理员列表": "Waiting to load admin list",
    "记录所有管理员操作。": "Records every admin action.",
    "刷新日志": "Refresh Audit Log",
    "暂无商品，请联系管理员在后台添加。 ": "No products available yet. Ask an admin to add some in the control panel.",
    "暂无最近变动记录。": "No recent wallet records.",
    "长期供应": "Always available",
    "单价": "Unit Price",
    "购买后生成团购兑换码，需由管理员核销": "Purchasing this item creates a voucher code that must be consumed by an admin.",
    "回收说明：系统将从背包扣除对应物品并入账。":
      "Recycle note: required items will be removed from your inventory and credited to your wallet.",
    "你已达到该商品的限购上限": "You have reached the purchase limit for this product.",
    "立即购买": "Buy Now",
    "立即回收": "Recycle Now",
    "当前没有可显示的市场上架。 ": "No market listings to display.",
    "自动补货": "Auto Refill",
    "公开市场": "Public Market",
    "编辑": "Edit",
    "强制刷新": "Force Refresh",
    "临时下架": "Pause Listing",
    "下架退回": "Unlist and Return",
    "恢复上架": "Resume Listing",
    "不可操作": "Unavailable",
    "不可购买": "Unavailable",
    "刷新补货": "Refresh Supply",
    "当前没有可显示的玩家店铺。": "No player stores to display.",
    "进入店铺": "Open Store",
    "返回店铺列表": "Back to Store List",
    "暂无订单记录。": "No orders yet.",
    "领取命令": "Claim Command",
    "复制命令": "Copy Command",
    "复制团购码": "Copy Voucher Code",
    "申请退款": "Request Refund",
    "没有可复制的内容。": "Nothing to copy.",
    "未绑定": "Not bound",
    "未知时间": "Unknown time",
    "未知物品": "Unknown item",
    "未知附魔": "Unknown enchantment",
    "未补货": "Never reloaded",
    "已结束": "Ended",
    "刚刚": "Just now",
    "保存修改": "Save Changes",
    "可同时修改价格和备注。": "You can change the price and remark at the same time.",
    "价格修改后立即生效，请谨慎操作。": "Price changes take effect immediately. Proceed carefully.",
    "价格必须是大于 0 的数字。": "Price must be a number greater than 0.",
    "总价": "Total",
    "无限": "Unlimited",
    "不限": "No limit",
    "暂无数据": "No data",
    "加载编辑": "Load into Editor",
    "载入编辑": "Load into Editor",
    "重置限购": "Reset Limit",
    "强制下架": "Force Unlist",
    "全选": "Select All",
    "下单中...": "Ordering...",
    "回收中...": "Recycling...",
    "退款中...": "Refunding...",
    "处理中...": "Processing...",
    "确认回收": "Confirm Recycle",
    "请确认以下回收信息，确认后将立即入账。":
      "Please review the recycle details below. Confirm to credit your wallet immediately.",
    "预计入账": "Estimated Credit",
    "确认后将按照以上金额入账。":
      "Your wallet will be credited with the amount above after confirmation.",
    "已取消回收。": "Recycle canceled.",
    "PENDING": "Pending Delivery",
    "WAIT_CLAIM": "Awaiting Claim",
    "DELIVERED": "Delivered",
    "REFUNDED": "Refunded",
    "FAILED": "Failed",
    "RECYCLED": "Recycled",
    "ACTIVE": "Active",
    "SOLD": "Sold",
    "UNLISTED": "Unlisted",
    "PAUSED": "Paused",
    "SUPPLY_EMPTY": "Needs Refill",
    "COMMAND": "Command",
    "GIVE_ITEM": "Sell Item",
    "POTION_EFFECT": "Potion Effect",
    "RECYCLE_ITEM": "Recycle Item",
    "GROUP_BUY_VOUCHER": "Group-Buy Voucher",
    "MARKET": "Player Market"
  });

  const EN_HTML = Object.freeze({
    app: {
      "#authEntryCard .md-tip-info p:nth-of-type(1)":
        "Before signing in, run <code>/webshopx password &lt;new-password&gt;</code> (or <code>/ws password &lt;new-password&gt;</code>) in-game to set your website password.",
      "#authEntryCard .md-tip-warn p":
        "If you have not created a website password yet, go back in-game and run <code>/webshopx password &lt;new-password&gt;</code> first.",
      "section[data-tab-panel='market'] .card-desc":
        "Run <code>/webshopx market</code> in-game to open the GUI. Price and remarks continue to be managed on the website.",
      "section[data-tab-panel='guide'] .md-tip:nth-of-type(1) p":
        "Run <code>/webshopx password &lt;new-password&gt;</code> (or <code>/ws password &lt;new-password&gt;</code>) in-game to create your web password, then come back and sign in with your Minecraft username.",
      "section[data-tab-panel='guide'] .md-tip:nth-of-type(3) p":
        "Choose a product in the official shop and confirm the order. If cooldown is enabled, delivery is delayed and refunds remain available during the cooldown window. For command products, admins can use <code>%amount%</code> in the command template to represent the purchase quantity.",
      "section[data-tab-panel='guide'] .md-tip:nth-of-type(4) p":
        "Run <code>/webshopx market</code> (or <code>/ws market</code>) in-game to open the market GUI, choose the manual listing flow, place the items in the chest UI, and enter the unit price in chat after clicking Next. The default currency is <code>GAME_COIN</code>; you can change it later on the website.",
      "section[data-tab-panel='guide'] .md-tip:nth-of-type(7) p":
        "Every purchase appears in the order history. If the player is offline, items are delivered automatically on the next login. When an order is waiting for claim, use <code>/ws claim</code> in-game."
    }
  });

  const EN_PREFIX_LABELS = Object.freeze({
    "卖家": "Seller",
    "店主": "Owner",
    "数量": "Quantity",
    "备注": "Remark",
    "发放时间": "Delivered At",
    "退款时间": "Refunded At",
    "团购兑换码": "Voucher Code",
    "团购兑换状态": "Voucher Status",
    "核销时间": "Consumed At",
    "最近补货": "Last Refill",
    "当前中转": "Transit Stock",
    "单次提取": "Transfer Batch",
    "累计提取": "Total Loaded",
    "累计售出": "Total Sold",
    "币种": "Currency",
    "下架": "Unpublish",
    "物品": "Item",
    "限购": "Purchase Limit",
    "当前在售": "Active Listings",
    "最近上架": "Latest Listing",
    "订单": "Order",
    "账号": "Account",
    "身份": "Role"
  });

  const EN_PATTERNS = [
    { pattern: /^市场在售：(\d+) 条$/, replace: (_, count) => `Public listings: ${count}` },
    { pattern: /^最近变动：(\d+) 条$/, replace: (_, count) => `Recent entries: ${count}` },
    { pattern: /^最近变动已刷新：(\d+) 条。$/, replace: (_, count) => `Recent entries refreshed: ${count}.` },
    {
      pattern: /^新订单：(.+)（(.+)）$/,
      replace: (_, orderNo, status) => `New order: ${orderNo} (${localizeText(status)})`,
    },
    { pattern: /^订单已发放：(.+)$/, replace: (_, orderNo) => `Order delivered: ${orderNo}` },
    {
      pattern: /^订单待领取：(.+)（可在游戏内 \/ws claim）$/,
      replace: (_, orderNo) => `Order awaiting claim: ${orderNo} (use /ws claim in-game)`,
    },
    { pattern: /^订单已退款：(.+)$/, replace: (_, orderNo) => `Order refunded: ${orderNo}` },
    {
      pattern: /^订单状态更新：(.+) -> (.+)$/,
      replace: (_, orderNo, status) => `Order updated: ${orderNo} -> ${localizeText(status)}`,
    },
    { pattern: /^上架 #(\d+) 已售出$/, replace: (_, id) => `Listing #${id} sold out` },
    {
      pattern: /^上架 #(\d+) 已下架，退回处理中$/,
      replace: (_, id) => `Listing #${id} unlisted, return queued`,
    },
    {
      pattern: /^上架 #(\d+) 发生部分售出：剩余 (\d+)$/,
      replace: (_, id, count) => `Listing #${id} partially sold, ${count} remaining`,
    },
    { pattern: /^购买商品 (.+)$/, replace: (_, id) => `Product purchase ${id}` },
    { pattern: /^订单退款 (.+)$/, replace: (_, id) => `Order refund ${id}` },
    { pattern: /^回收入账 (.+)$/, replace: (_, id) => `Recycle credit ${id}` },
    { pattern: /^总价：(.+)$/, replace: (_, total) => `Total: ${total}` },
    { pattern: /^修改价格 #(\d+)$/, replace: (_, id) => `Change Price #${id}` },
    { pattern: /^修改上架 #(\d+)$/, replace: (_, id) => `Edit Listing #${id}` },
    { pattern: /^下架：(.+)$/, replace: (_, value) => `Unpublish: ${value}` },
    { pattern: /^币种 (.+)$/, replace: (_, value) => `Currency ${value}` },
    { pattern: /^物品：(.+)$/, replace: (_, value) => `Item: ${value}` },
    { pattern: /^限购：每人 x(\d+)$/, replace: (_, count) => `Limit: x${count} per player` },
    { pattern: /^你还可购买：x(\d+)$/, replace: (_, count) => `Remaining personal limit: x${count}` },
    { pattern: /^卖家：(.+)$/, replace: (_, value) => `Seller: ${value}` },
    { pattern: /^店主：(.+)$/, replace: (_, value) => `Owner: ${value}` },
    { pattern: /^在售商品 (\d+) 件$/, replace: (_, count) => `${count} active listings` },
    { pattern: /^库存总量 x(\d+)$/, replace: (_, count) => `Total stock x${count}` },
    { pattern: /^起售价 (.+)$/, replace: (_, value) => `Starts at ${value}` },
    { pattern: /^最近上架：(.+)$/, replace: (_, value) => `Latest listing: ${value}` },
    { pattern: /^(.+)的小店$/, replace: (_, value) => `${value}'s Store` },
    { pattern: /^当前在售：(\d+) 件商品$/, replace: (_, count) => `${count} active products` },
    { pattern: /^数量：x(\d+)$/, replace: (_, count) => `Quantity: x${count}` },
    { pattern: /^团购兑换码：(.+)$/, replace: (_, value) => `Voucher code: ${value}` },
    { pattern: /^团购兑换状态：(.+)$/, replace: (_, value) => `Voucher status: ${localizeText(value)}` },
    { pattern: /^核销时间：(.+)$/, replace: (_, value) => `Consumed at: ${value}` },
    { pattern: /^(\d+) 秒$/, replace: (_, value) => `${value}s` },
    { pattern: /^(\d+) 分 (\d+) 秒$/, replace: (_, min, sec) => `${min}m ${sec}s` },
    { pattern: /^(\d+) 分钟前$/, replace: (_, value) => `${value} min ago` },
    { pattern: /^(\d+) 小时前$/, replace: (_, value) => `${value} hr ago` },
    { pattern: /^(\d+) 天前$/, replace: (_, value) => `${value} day${value === "1" ? "" : "s"} ago` },
    {
      pattern: /^账号：(.+) \| 身份：(.+) \| 拥有全部权限$/,
      replace: (_, account, role) => `Account: ${account} | Role: ${role} | Full permissions`,
    },
    {
      pattern: /^账号：(.+) \| 身份：(.+) \| 权限数：(\d+)( \| 可管理管理员)?$/,
      replace: (_, account, role, count, canManage) =>
        `Account: ${account} | Role: ${role} | Permissions: ${count}${canManage ? " | Can manage admins" : ""}`,
    },
    {
      pattern: /^商品上下架时间按时区 (.+) 解释与显示。$/,
      replace: (_, zone) => `Publish and unpublish times are interpreted and displayed in ${zone}.`,
    },
    { pattern: /^已加载 (\d+) 条订单$/, replace: (_, count) => `${count} orders loaded` },
    { pattern: /^订单 (.+)$/, replace: (_, orderNo) => `Order ${orderNo}` },
    { pattern: /^已加载 (\d+) 条$/, replace: (_, count) => `${count} rows loaded` },
    {
      pattern: /^已连接 Vault 经济：(.+)（(.+) 由 Vault 托管）$/,
      replace: (_, provider, currency) => `Vault connected: ${provider} (${currency} managed by Vault)`,
    },
    {
      pattern: /^未检测到 Vault 插件，(.+) 当前使用本地钱包。$/,
      replace: (_, currency) => `Vault plugin not detected. ${currency} is using the local wallet.`,
    },
    { pattern: /^已下架上架 (\d+)$/, replace: (_, id) => `Listing ${id} unlisted` }
  ];

  const POTION_EFFECT_LABELS_ZH = Object.freeze({
    speed: "速度",
    slowness: "缓慢",
    haste: "急迫",
    mining_fatigue: "挖掘疲劳",
    strength: "力量",
    instant_health: "瞬间治疗",
    instant_damage: "瞬间伤害",
    jump_boost: "跳跃提升",
    nausea: "反胃",
    regeneration: "生命恢复",
    resistance: "抗性提升",
    fire_resistance: "抗火",
    water_breathing: "水下呼吸",
    invisibility: "隐身",
    blindness: "失明",
    night_vision: "夜视",
    hunger: "饥饿",
    weakness: "虚弱",
    poison: "中毒",
    wither: "凋零",
    health_boost: "生命提升",
    absorption: "伤害吸收",
    saturation: "饱和",
    glowing: "发光",
    levitation: "漂浮",
    luck: "幸运",
    unluck: "霉运",
    slow_falling: "缓降",
    conduit_power: "潮涌能量",
    dolphins_grace: "海豚的恩惠",
    bad_omen: "不祥之兆",
    hero_of_the_village: "村庄英雄",
    darkness: "黑暗"
  });

  function canonicalizeLocaleTag(raw) {
    const value = String(raw || "").trim().replace(/_/g, "-");
    if (!value) {
      return "";
    }
    const segments = value.split("-").filter(Boolean);
    if (segments.length === 0) {
      return "";
    }
    const language = segments[0].toLowerCase();
    if (segments.length === 1) {
      return language;
    }
    const region = segments[1].length === 2 ? segments[1].toUpperCase() : segments[1].toLowerCase();
    const rest = segments.slice(2).map((part) => part.toLowerCase());
    return [language, region, ...rest].join("-");
  }

  function toLocaleEntry(raw, sourceHint = "runtime") {
    if (typeof raw === "string") {
      const locale = canonicalizeLocaleTag(raw);
      if (!locale) {
        return null;
      }
      return { locale, source: sourceHint, name: "", nativeName: "" };
    }
    if (!raw || typeof raw !== "object") {
      return null;
    }
    const locale = canonicalizeLocaleTag(raw.locale || raw.code || raw.tag);
    if (!locale) {
      return null;
    }
    return {
      locale,
      source: String(raw.source || sourceHint || "runtime"),
      name: String(raw.name || "").trim(),
      nativeName: String(raw.nativeName || raw.native || "").trim(),
    };
  }

  function mergeLocaleEntries(entries) {
    const map = new Map();
    entries.forEach((entry) => {
      const normalized = toLocaleEntry(entry, entry?.source || "runtime");
      if (!normalized) {
        return;
      }
      const prev = map.get(normalized.locale) || {};
      map.set(normalized.locale, {
        locale: normalized.locale,
        source: normalized.source || prev.source || "runtime",
        name: normalized.name || prev.name || normalized.locale,
        nativeName: normalized.nativeName || prev.nativeName || "",
      });
    });
    return Array.from(map.values());
  }

  function readServerLocaleMetaSync() {
    if (SERVER_LOCALE_META !== null) {
      return SERVER_LOCALE_META;
    }
    const payload = readJsonSync(LOCALE_META_API_PATH);
    if (payload && typeof payload === "object" && !Array.isArray(payload)) {
      SERVER_LOCALE_META = payload;
      return SERVER_LOCALE_META;
    }
    SERVER_LOCALE_META = {};
    return SERVER_LOCALE_META;
  }

  function readServerPublishedLocaleEntries() {
    if (Array.isArray(SERVER_LOCALE_ENTRIES)) {
      return SERVER_LOCALE_ENTRIES.slice();
    }
    const payload = readServerLocaleMetaSync();
    const locales = Array.isArray(payload?.locales) ? payload.locales : [];
    SERVER_LOCALE_ENTRIES = locales
      .map((item) => toLocaleEntry(item, "server"))
      .filter(Boolean);
    return SERVER_LOCALE_ENTRIES.slice();
  }

  function getSupportedLocaleEntries() {
    const runtimeLocalesRaw = Array.isArray(RUNTIME_CONFIG.supportedLocales) ? RUNTIME_CONFIG.supportedLocales : [];
    const runtimeEntries = runtimeLocalesRaw
      .map((item) => toLocaleEntry(item, "runtime"))
      .filter(Boolean);
    const fromServer = readServerPublishedLocaleEntries();
    const merged = mergeLocaleEntries([...BASE_LOCALE_ENTRIES, ...runtimeEntries, ...fromServer]);
    if (merged.length === 0) {
      return mergeLocaleEntries(BASE_LOCALE_ENTRIES.slice());
    }
    return merged;
  }

  function getSupportedLocales() {
    return getSupportedLocaleEntries().map((item) => item.locale);
  }

  function matchSupportedLocale(raw, options = {}) {
    const fallback = canonicalizeLocaleTag(options.fallback || "zh-CN") || "zh-CN";
    const supported = Array.isArray(options.supportedLocales) && options.supportedLocales.length > 0
      ? options.supportedLocales.map((item) => canonicalizeLocaleTag(item)).filter(Boolean)
      : getSupportedLocales();
    if (supported.length === 0) {
      return fallback;
    }

    const normalized = canonicalizeLocaleTag(raw);
    if (!normalized) {
      return supported.includes(fallback) ? fallback : supported[0];
    }

    if (normalized === "zh") {
      const zhLocale = supported.find((item) => item === "zh-CN") || supported.find((item) => item.startsWith("zh"));
      return zhLocale || (supported.includes(fallback) ? fallback : supported[0]);
    }
    if (normalized === "en") {
      const enLocale = supported.find((item) => item === "en-US") || supported.find((item) => item.startsWith("en"));
      return enLocale || (supported.includes(fallback) ? fallback : supported[0]);
    }

    if (supported.includes(normalized)) {
      return normalized;
    }

    const languagePrefix = normalized.split("-")[0];
    const sameLanguage = supported.find((item) => item === languagePrefix || item.startsWith(`${languagePrefix}-`));
    if (sameLanguage) {
      return sameLanguage;
    }

    return supported.includes(fallback) ? fallback : supported[0];
  }

  let currentLocale = resolveInitialLocale();

  function normalizeLocale(raw, options = {}) {
    return matchSupportedLocale(raw, options);
  }

  function resolveInitialLocale() {
    const supported = getSupportedLocales();
    try {
      const stored = window.localStorage.getItem(LOCALE_STORAGE_KEY);
      if (stored) {
        return normalizeLocale(stored, { supportedLocales: supported });
      }
    } catch (error) {
      // ignore storage issues
    }

    const browserLocales = Array.isArray(navigator.languages) && navigator.languages.length > 0
      ? navigator.languages
      : [navigator.language];
    for (const candidate of browserLocales) {
      const normalized = normalizeLocale(candidate, { supportedLocales: supported });
      if (supported.includes(normalized)) {
        return normalized;
      }
    }
    const serverDefault = canonicalizeLocaleTag(readServerLocaleMetaSync()?.defaultLocale || "");
    const preferredDefault = serverDefault || RUNTIME_CONFIG.defaultLocale || "zh-CN";
    return normalizeLocale(preferredDefault, { supportedLocales: supported });
  }

  function isChineseLocale(locale = currentLocale) {
    return normalizeLocale(locale) === "zh-CN";
  }

  function setDocumentLanguage() {
    document.documentElement.lang = currentLocale;
  }

  function getLocaleOptionLabel(locale) {
    const normalized = canonicalizeLocaleTag(locale);
    if (!normalized) {
      return "-";
    }
    const entries = getSupportedLocaleEntries();
    const entry = entries.find((item) => item.locale === normalized) || null;
    if (!entry) {
      return normalized;
    }
    const nativeName = String(entry.nativeName || "").trim();
    const name = String(entry.name || "").trim();
    if (nativeName && name && nativeName !== name) {
      return `${nativeName} (${name})`;
    }
    if (nativeName) {
      return nativeName;
    }
    if (name) {
      return name;
    }
    return normalized;
  }

  function getLocaleOptionLabelLegacy(locale) {
    const normalized = normalizeLocale(locale);
    if (isChineseLocale()) {
      return normalized === "zh-CN" ? "简体中文" : "English";
    }
    return normalized === "zh-CN" ? "Simplified Chinese" : "English";
  }

  function getLocale() {
    return currentLocale;
  }

  function getIntlLocale() {
    return currentLocale || "zh-CN";
  }

  function setLocale(nextLocale) {
    currentLocale = normalizeLocale(nextLocale);
    try {
      window.localStorage.setItem(LOCALE_STORAGE_KEY, currentLocale);
    } catch (error) {
      // ignore storage issues
    }
    setDocumentLanguage();
    return currentLocale;
  }

  function cloneJsonValue(value) {
    if (value === null || value === undefined) {
      return value;
    }
    try {
      return JSON.parse(JSON.stringify(value));
    } catch (error) {
      return value;
    }
  }

  function readJsonSync(path) {
    if (!path || typeof XMLHttpRequest !== "function") {
      return null;
    }
    try {
      const request = new XMLHttpRequest();
      request.open("GET", path, false);
      request.setRequestHeader("Cache-Control", "no-cache");
      request.send(null);
      if (request.status < 200 || request.status >= 300) {
        return null;
      }
      const text = String(request.responseText || "").trim();
      if (!text) {
        return null;
      }
      return JSON.parse(text);
    } catch (error) {
      return null;
    }
  }

  function buildBundleCandidates(namespace, locale, fallbackLocale) {
    const normalizedLocale = normalizeLocale(locale);
    const normalizedFallback = normalizeLocale(fallbackLocale || DEFAULT_BUNDLE_FALLBACK_LOCALE);
    const orderedLocales = [];
    const pushLocale = (value) => {
      const normalized = normalizeLocale(value);
      if (!normalized || orderedLocales.includes(normalized)) {
        return;
      }
      orderedLocales.push(normalized);
    };
    // Merge order: english base -> optional fallback -> requested locale override.
    pushLocale(DEFAULT_BUNDLE_FALLBACK_LOCALE);
    pushLocale(normalizedFallback);
    pushLocale(normalizedLocale);

    const rawCandidates = [];
    const basePaths = [
      `/i18n/${namespace}`,
      `i18n/${namespace}`,
      `./i18n/${namespace}`,
    ];
    basePaths.forEach((base) => {
      orderedLocales.forEach((tag) => {
        rawCandidates.push(`${base}/${tag}.json`);
      });
    });
    return rawCandidates;
  }

  function mergeBundleObject(baseValue, nextValue) {
    if (Array.isArray(baseValue) || Array.isArray(nextValue)) {
      return Array.isArray(nextValue) ? nextValue.slice() : nextValue;
    }
    const baseIsObject = baseValue && typeof baseValue === "object";
    const nextIsObject = nextValue && typeof nextValue === "object";
    if (!baseIsObject || !nextIsObject) {
      return nextValue;
    }
    const merged = { ...baseValue };
    Object.keys(nextValue).forEach((key) => {
      if (Object.prototype.hasOwnProperty.call(baseValue, key)) {
        merged[key] = mergeBundleObject(baseValue[key], nextValue[key]);
      } else {
        merged[key] = nextValue[key];
      }
    });
    return merged;
  }

  function loadBundleSync(namespace, options = {}) {
    const normalizedNamespace = String(namespace || "").trim().replace(/^\/+|\/+$/g, "");
    if (!normalizedNamespace) {
      return {};
    }
    const requestedLocale = normalizeLocale(options.locale || currentLocale);
    const fallbackLocale = normalizeLocale(options.fallbackLocale || DEFAULT_BUNDLE_FALLBACK_LOCALE);
    const candidates = Array.isArray(options.candidates) && options.candidates.length > 0
      ? options.candidates
      : buildBundleCandidates(normalizedNamespace, requestedLocale, fallbackLocale);
    const cacheKey = `${normalizedNamespace}::${requestedLocale}::${fallbackLocale}::${candidates.join("|")}`;
    if (BUNDLE_CACHE.has(cacheKey)) {
      return cloneJsonValue(BUNDLE_CACHE.get(cacheKey));
    }
    let merged = {};
    let loadedAny = false;
    for (const path of candidates) {
      const parsed = readJsonSync(path);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        merged = mergeBundleObject(merged, parsed) || merged;
        loadedAny = true;
      }
    }
    BUNDLE_CACHE.set(cacheKey, loadedAny ? merged : {});
    return cloneJsonValue(BUNDLE_CACHE.get(cacheKey));
  }

  function getBundleValue(bundle, path) {
    const text = String(path || "").trim();
    if (!text || !bundle || typeof bundle !== "object") {
      return undefined;
    }
    const parts = text.split(".").filter(Boolean);
    let current = bundle;
    for (const part of parts) {
      if (!current || typeof current !== "object" || !(part in current)) {
        return undefined;
      }
      current = current[part];
    }
    return current;
  }

  function applyKeyedTranslations(bundle) {
    if (!bundle || typeof bundle !== "object") {
      return;
    }
    const nodes = document.querySelectorAll("[data-i18n], [data-i18n-html], [data-i18n-placeholder], [data-i18n-title], [data-i18n-aria-label], [data-i18n-alt]");
    nodes.forEach((node) => {
      const textKey = node.getAttribute("data-i18n");
      if (textKey) {
        const value = getBundleValue(bundle, textKey);
        if (typeof value === "string") {
          node.textContent = value;
        }
      }

      const htmlKey = node.getAttribute("data-i18n-html");
      if (htmlKey) {
        const value = getBundleValue(bundle, htmlKey);
        if (typeof value === "string") {
          node.innerHTML = value;
        }
      }

      const placeholderKey = node.getAttribute("data-i18n-placeholder");
      if (placeholderKey) {
        const value = getBundleValue(bundle, placeholderKey);
        if (typeof value === "string") {
          node.setAttribute("placeholder", value);
        }
      }

      const titleKey = node.getAttribute("data-i18n-title");
      if (titleKey) {
        const value = getBundleValue(bundle, titleKey);
        if (typeof value === "string") {
          node.setAttribute("title", value);
        }
      }

      const ariaLabelKey = node.getAttribute("data-i18n-aria-label");
      if (ariaLabelKey) {
        const value = getBundleValue(bundle, ariaLabelKey);
        if (typeof value === "string") {
          node.setAttribute("aria-label", value);
        }
      }

      const altKey = node.getAttribute("data-i18n-alt");
      if (altKey) {
        const value = getBundleValue(bundle, altKey);
        if (typeof value === "string") {
          node.setAttribute("alt", value);
        }
      }
    });

    const titleNode = document.querySelector("title[data-i18n]");
    if (titleNode) {
      const key = titleNode.getAttribute("data-i18n");
      const value = getBundleValue(bundle, key);
      if (typeof value === "string") {
        document.title = value;
      }
    }
  }

  function shouldLoadMaterialMap(locale = currentLocale) {
    return isChineseLocale(locale);
  }

  function humanizeEnum(value) {
    return String(value || "")
      .replace(/^minecraft:/i, "")
      .replace(/[_-]+/g, " ")
      .trim()
      .toLowerCase()
      .split(" ")
      .filter(Boolean)
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(" ");
  }

  function getPotionEffectLabel(effect) {
    const key = String(effect || "").trim().toLowerCase();
    if (!key) {
      return "";
    }
    if (isChineseLocale()) {
      return POTION_EFFECT_LABELS_ZH[key] || humanizeEnum(key);
    }
    return humanizeEnum(key);
  }

  function localizeExact(text) {
    if (isChineseLocale()) {
      return text;
    }
    return EN_EXACT[text] || text;
  }

  function localizeWithPatterns(text) {
    if (isChineseLocale()) {
      return text;
    }
    for (const entry of EN_PATTERNS) {
      if (entry.pattern.test(text)) {
        return text.replace(entry.pattern, entry.replace);
      }
    }
    return text;
  }

  function localizeWithPrefix(text) {
    if (isChineseLocale()) {
      return text;
    }
    const match = /^([^：]+)：(.*)$/.exec(text);
    if (!match) {
      return text;
    }
    const translated = EN_PREFIX_LABELS[match[1]];
    if (!translated) {
      return text;
    }
    return `${translated}: ${match[2]}`;
  }

  function localizeText(value) {
    if (value === null || value === undefined) {
      return value;
    }
    const text = String(value);
    if (!text || isChineseLocale()) {
      return text;
    }
    const exact = localizeExact(text);
    if (exact !== text) {
      return exact;
    }
    const patterned = localizeWithPatterns(text);
    if (patterned !== text) {
      return patterned;
    }
    const prefixed = localizeWithPrefix(text);
    if (prefixed !== text) {
      return prefixed;
    }
    return text;
  }

  function translateNodeText(node) {
    const parent = node.parentElement;
    if (!parent || SKIP_PARENTS.has(parent.tagName)) {
      return;
    }
    const raw = node.nodeValue;
    const trimmed = raw == null ? "" : raw.trim();
    if (!trimmed) {
      return;
    }
    const translated = localizeText(trimmed);
    if (translated === trimmed) {
      return;
    }
    node.nodeValue = raw.replace(trimmed, translated);
  }

  function translateDocumentText() {
    document.title = localizeText(document.title);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      translateNodeText(walker.currentNode);
    }
  }

  function translateAttributes() {
    const elements = document.querySelectorAll("*");
    elements.forEach((element) => {
      ATTRIBUTE_NAMES.forEach((name) => {
        const raw = element.getAttribute(name);
        if (!raw) {
          return;
        }
        const translated = localizeText(raw);
        if (translated !== raw) {
          element.setAttribute(name, translated);
        }
      });
    });
  }

  function applyHtmlOverrides(pageName) {
    if (isChineseLocale()) {
      return;
    }
    const overrides = EN_HTML[pageName] || {};
    Object.entries(overrides).forEach(([selector, html]) => {
      const node = document.querySelector(selector);
      if (node) {
        node.innerHTML = html;
      }
    });
  }

  function populateLocaleSelect(selectId) {
    const select = document.getElementById(selectId);
    if (!select) {
      return;
    }
    const supportedEntries = getSupportedLocaleEntries();
    const supportedLocales = supportedEntries.map((item) => item.locale);
    select.innerHTML = "";
    supportedEntries.forEach((entry) => {
      const option = document.createElement("option");
      option.value = entry.locale;
      option.textContent = getLocaleOptionLabel(entry.locale);
      select.appendChild(option);
    });
    select.value = supportedLocales.includes(currentLocale)
      ? currentLocale
      : normalizeLocale(currentLocale, { supportedLocales });
    if (select.dataset.localeBound !== "1") {
      select.addEventListener("change", () => {
        setLocale(select.value);
        window.location.reload();
      });
      select.dataset.localeBound = "1";
    }
  }

  function preparePage(pageName, options = {}) {
    const bundleNamespace = String(options.namespace || pageName || "").trim();
    const bundle = bundleNamespace ? loadBundleSync(bundleNamespace) : {};
    applyKeyedTranslations(bundle);
    setDocumentLanguage();
    translateDocumentText();
    translateAttributes();
    applyHtmlOverrides(pageName);
    populateLocaleSelect(options.selectId || "localeSelect");
  }

  function refreshLocaleSelect(selectId) {
    SERVER_LOCALE_META = null;
    SERVER_LOCALE_ENTRIES = null;
    populateLocaleSelect(selectId || "localeSelect");
  }

  function getThemeToggleLabel(theme) {
    if (isChineseLocale()) {
      return theme === "dark" ? "切换亮色" : "切换暗色";
    }
    return theme === "dark" ? "Light mode" : "Dark mode";
  }

  window.WebShopXI18n = {
    getIntlLocale,
    getLocale,
    getLocaleOptionLabel,
    getSupportedLocaleEntries,
    getSupportedLocales,
    loadBundleSync,
    getPotionEffectLabel,
    getThemeToggleLabel,
    humanizeEnum,
    isChineseLocale,
    localizeText,
    normalizeLocale,
    preparePage,
    refreshLocaleSelect,
    setLocale,
    shouldLoadMaterialMap,
  };

  setDocumentLanguage();
})();
