const state = {
  token: null,
  username: null,
  boundUuid: null,
  activeTab: "auth",
  marketMode: "public",
  marketTradeScope: "DIRECT",
  marketStore: {
    sellerKey: null,
    sellerName: null,
    sellerUuid: null,
  },
  listings: [],
  products: [],
  orders: [],
  notifications: [],
  unreadNotificationCount: 0,
  orderPolicy: {
    cooldownSeconds: 0,
    refundEnabled: false,
    refundUndeliveredEnabled: false,
    marketFeePercent: 0,
    marketTaxPercent: 0,
    marketSupplyAutoRefreshThreshold: 8,
    sharedClaimAllowed: false,
  },
  orderPolicyReady: false,
  materialNameMap: {},
  materialNameMapReady: false,
  materialNameMapPromise: null,
  marketMaterialAllowSet: new Set(),
  marketMaterialLookup: {},
  marketMaterialAllowReady: false,
  marketMaterialAllowPromise: null,
  materialVisualMap: {},
  materialVisualMapReady: false,
  materialVisualMapPromise: null,
  visualPolicy: {
    globalCustomIconEnabled: true,
    globalCustomNameEnabled: true,
    officialProductCustomIconEnabled: true,
    officialProductCustomNameEnabled: true,
    officialProductUploadImageEnabled: true,
    marketListingCustomIconEnabled: true,
    marketListingCustomNameEnabled: true,
    marketListingUploadImageEnabled: true,
    iconPolicyMode: "SOFT",
    namePolicyMode: "SOFT",
  },
  visualPermission: {
    customIconAllowed: true,
    customNameAllowed: true,
    customUploadAllowed: true,
  },
  marketAlgorithmGlossary: {
    dynamic: [],
    auction: [],
  },
  marketAlgorithmGlossaryReady: false,
  marketAlgorithmGlossaryPromise: null,
  marketTags: [],
  marketTagsReady: false,
  marketTagsPromise: null,
  hasLoadedProducts: false,
  hasLoadedMarket: false,
  hasLoadedOrders: false,
  hasLoadedNotifications: false,
  theme: "light",
  themePackage: "default",
  hideOwnMarketListings: true,
  themeCenter: {
    defaultTheme: "default",
    themes: [],
  },
  realtime: {
    timer: null,
    busy: false,
    hasBootstrapped: false,
    orderDigest: {},
    listingDigest: {},
    wallet: null,
    notificationUnread: null,
  },
  walletBalance: {
    shopCoin: 0,
    gameCoin: 0,
  },
  exchangeMetaLoaded: false,
  exchangeSettings: {
    shopToGame: { enabled: true, ratio: 1.0 },
    gameToShop: { enabled: false, ratio: 1.0 },
  },
  recharge: {
    currentOrderId: null,
    currentPayment: null,
    comboUnavailable: false,
    currencies: ["CNY"],
    methods: ["ALIPAY"],
    rates: [{ method: "ALIPAY", currency: "CNY", coinsPerUnit: 100 }],
    provider: null,
    statusPollTimer: null,
    statusPollBusy: false,
    expireTimer: null,
  },
  leaderboard: {
    enabled: true,
    showOnlineStatusEnabled: true,
    defaultMetric: "GAME_COIN",
    defaultOrder: "DESC",
    timer: null,
    focusTimer: null,
    busy: false,
    previousRanks: {},
    myRank: null,
  },
  timeZone: "Asia/Shanghai",
};

const I18N = window.WebShopXI18n || null;
if (I18N) {
  I18N.preparePage("app", { selectId: "localeSelect" });
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function mergeLocaleValue(fallbackValue, localeValue) {
  if (Array.isArray(fallbackValue)) {
    return Array.isArray(localeValue) ? localeValue.slice() : fallbackValue.slice();
  }
  if (!isPlainObject(fallbackValue)) {
    return localeValue === undefined ? fallbackValue : localeValue;
  }
  const result = { ...fallbackValue };
  if (!isPlainObject(localeValue)) {
    return result;
  }
  Object.keys(localeValue).forEach((key) => {
    result[key] = mergeLocaleValue(fallbackValue[key], localeValue[key]);
  });
  return result;
}

function loadAppLocaleBundle() {
  if (!I18N || typeof I18N.loadBundleSync !== "function") {
    return {};
  }
  return I18N.loadBundleSync("app");
}

const APP_LOCALE_BUNDLE = loadAppLocaleBundle();

const FALLBACK_CURRENCY_META = {
  SHOP_COIN: {
    label: "网页币",
    short: "SC",
  },
  GAME_COIN: {
    label: "游戏币",
    short: "GC",
  },
};

const FALLBACK_APP_UI_TEXT = Object.freeze({
  themeToggleLight: "切换亮色",
  themeToggleDark: "切换暗色",
  marketModeLabel: Object.freeze({
    auction: Object.freeze({
      public: "拍卖在售",
      stores: "拍卖店铺",
      mine: "我的拍卖",
    }),
    direct: Object.freeze({
      public: "市场在售",
      stores: "玩家店铺",
      mine: "我的上架",
    }),
  }),
  marketEmptyState: Object.freeze({
    auction: Object.freeze({
      listing: "当前没有可显示的拍卖上架。",
      store: "当前没有可显示的拍卖店铺。",
    }),
    direct: Object.freeze({
      listing: "当前没有可显示的市场上架。",
      store: "当前没有可显示的玩家店铺。",
    }),
  }),
  marketSection: Object.freeze({
    auction: Object.freeze({
      title: "拍卖行（C2C）",
      desc: "仅显示拍卖模式上架，可直接出价或买断。其他商品请前往玩家市场。",
      listBtn: "拍卖在售",
      storeBtn: "拍卖店铺",
      mineBtn: "我的拍卖",
      createBuyTitle: "拍卖页不支持创建收购单，请切换到玩家市场。",
    }),
    direct: Object.freeze({
      title: "玩家市场（C2C）",
      desc: "玩家自行上架的商品。出售单需在游戏内创建；收购单可在网页直接发布。",
      listBtn: "市场在售",
      storeBtn: "玩家店铺",
      mineBtn: "我的上架",
      createBuyTitle: "",
    }),
  }),
  initMeta: Object.freeze({
    walletView: "等待刷新余额",
    walletLedgerView: "等待加载记录",
    rechargeView: "等待充值操作",
    redeemView: "等待兑换操作",
    exchangeRateHint: "等待加载兑换比例",
    exchangeView: "等待兑换操作",
    orderView: "暂无订单",
    notificationsView: "暂无通知",
    marketView: "暂无市场数据",
    leaderboardView: "等待加载榜单",
    leaderboardMyRank: "我的名次：-",
  }),
  notificationTypeLabels: Object.freeze({
    GENERAL: "通知",
    SYSTEM_ANNOUNCEMENT: "系统公告",
    MARKET_LISTED: "上架提醒",
    MARKET_TRADE: "市场成交",
    AUCTION_BID: "竞拍提醒",
    AUCTION_OUTBID: "竞拍超价",
    AUCTION_SETTLEMENT: "拍卖结算",
    MARKET_BUY_ORDER_REFUND_ESCROW: "托管退款",
    DELIVERY_WAIT_CLAIM: "待领取提醒",
    MAILBOX_PENDING: "信箱待领取",
  }),
  templates: Object.freeze({
    loadFailed: "Load failed: {message}",
    saveFailed: "Save failed: {message}",
    loginFailed: "Login failed: {message}",
    logoutFailed: "Logout failed: {message}",
    walletRefreshFailed: "Wallet refresh failed: {message}",
    productsLoadFailed: "Product load failed: {message}",
    marketLoadFailed: "Market load failed: {message}",
    notificationsLoadFailed: "Notification load failed: {message}",
    ordersLoadFailed: "Order load failed: {message}",
    redeemFailed: "Redeem failed: {message}",
    rechargeNoOrder: "No recharge order to check yet.",
    rechargeCreating: "Creating payment order...",
    rechargeCreated: "Order {orderId} created. Complete payment, then check status.",
    rechargeStatus: "Order {orderId}: {status}, credit {coinAmount} ShopCoin",
    rechargePaidDetected: "Payment confirmed for order {orderId}. Credited {coinAmount} ShopCoin.",
    rechargeOrderClosed: "Order {orderId}: {status}.",
    rechargeFailed: "Recharge failed: {message}",
    rechargeStatusFailed: "Status check failed: {message}",
    rechargeNoPayLink: "Payment provider did not return a payment link.",
    rechargeCancelNoOrder: "No recharge order to cancel.",
    rechargeCancelSuccess: "Order {orderId} has been cancelled.",
    rechargeCancelFailed: "Cancel failed: {message}",
    exchangeFailed: "Exchange failed: {message}",
    operationFailed: "Operation failed: {message}",
    markReadFailed: "Mark read failed: {message}",
    markAllReadFailed: "Mark all read failed: {message}",
    buyOrderCreateFailed: "Buy order publish failed: {message}",
    refundFailed: "Refund failed: {message}",
    marketActionFailed: "Market operation failed: {message}",
    iconProcessFailed: "Icon processing failed: {message}",
    leaderboardLoadFailed: "Leaderboard load failed: {message}",
    marketMaterialAllowLoadFailed: "Market material allow-list load failed: {message}",
    marketTagsLoadFailed: "Market tags load failed: {message}",
    notificationUnreadRefreshFailed: "Notification unread refresh failed: {message}",
    postOrderWalletRefreshFailed: "Wallet refresh failed after order creation: {message}",
    postOrderOrdersLoadFailed: "Order reload failed after order creation: {message}",
    postOrderProductsRefreshFailed: "Product refresh failed after order creation: {message}",
    postBuyOrderWalletRefreshFailed: "Wallet refresh failed after buy-order publish: {message}",
    postMarketBuyWalletRefreshFailed: "Wallet refresh failed after purchase: {message}",
    postSellToBuyWalletRefreshFailed: "Wallet refresh failed after delivery: {message}",
    postBidWalletRefreshFailed: "Wallet refresh failed after bidding: {message}",
    postLoginProductsRefreshFailed: "Product refresh failed after login: {message}",
    postLogoutProductsRefreshFailed: "Product refresh failed after logout: {message}",
    marketActionInvalidListingId: "Market operation failed: invalid listing ID.",
  }),
});

const FALLBACK_MARKET_ALGORITHM_GLOSSARY = Object.freeze({
  dynamic: [
    { id: "LINEAR_DEMAND_V1", label: "LINEAR_DEMAND_V1", params: [] },
    { id: "DIMINISHING_RETURN_V1", label: "DIMINISHING_RETURN_V1", params: [] },
    { id: "LOG_SMOOTH_V1", label: "LOG_SMOOTH_V1", params: [] },
    { id: "EXPONENTIAL_DEFENSE_V1", label: "EXPONENTIAL_DEFENSE_V1", params: [] },
    { id: "THRESHOLD_STEP_V1", label: "THRESHOLD_STEP_V1", params: [] },
    { id: "ELASTICITY_V1", label: "ELASTICITY_V1", params: [] },
    { id: "PANIC_BUYING_V1", label: "PANIC_BUYING_V1", params: [] },
  ],
  auction: [
    { id: "ENGLISH_AUCTION_V1", label: "ENGLISH_AUCTION_V1", params: [] },
    { id: "DUTCH_AUCTION_V1", label: "DUTCH_AUCTION_V1", params: [] },
    { id: "VICKREY_AUCTION_V1", label: "VICKREY_AUCTION_V1", params: [] },
    { id: "CANDLE_AUCTION_V1", label: "CANDLE_AUCTION_V1", params: [] },
  ],
});

const PARAM_KEY_ALIAS_MAP = Object.freeze({
  threshold: ["thresholdK", "panicThreshold"],
  eta: ["elasticity"],
});

const LOCAL_TEXTURE_BASE = "/textures";
const REMOTE_TEXTURE_BASES = [
  "https://mcasset.cloud/1.21/assets/minecraft/textures",
  "https://mcasset.cloud/1.20.6/assets/minecraft/textures",
];

const MATERIAL_TEXTURE_OVERRIDES = {
  MOSS_CARPET: ["moss_carpet"],
  GRASS: ["short_grass", "grass"],
  TALL_GRASS: ["tall_grass"],
};

const PRODUCT_TYPE_TEXTURE_MAP = {
  COMMAND: "COMMAND_BLOCK",
  GIVE_CUSTOM_ITEM: "CHEST",
  POTION_EFFECT: "SPLASH_POTION",
  RECYCLE_COMMAND_ITEM: "HOPPER",
  RECYCLE_CUSTOM_ITEM: "HOPPER",
  GROUP_BUY_VOUCHER: "PAPER",
};

const DEFAULT_TEXTURE_FALLBACK_MATERIAL = "BUNDLE";
const RUNTIME_CONFIG = window.WEBSHOPX_CONFIG || {};
const API_BASE_URL = normalizeApiBaseUrl(RUNTIME_CONFIG.apiBaseUrl || "");

function normalizeApiBaseUrl(value) {
  let normalized = String(value || "").trim();
  while (normalized.endsWith("/")) {
    normalized = normalized.slice(0, -1);
  }
  return normalized;
}

function resolveApiUrl(path) {
  const text = String(path || "").trim();
  if (!text) {
    return text;
  }
  if (/^[a-z]+:\/\//i.test(text) || text.startsWith("//")) {
    return text;
  }
  if (!text.startsWith("/")) {
    return text;
  }
  return API_BASE_URL ? `${API_BASE_URL}${text}` : text;
}

function readThemeColor(tokenName, fallback) {
  const rootStyles = window.getComputedStyle(document.documentElement);
  const value = rootStyles.getPropertyValue(tokenName).trim();
  return value || fallback;
}

function buildSvgDataUrl(svg) {
  return "data:image/svg+xml;utf8," + encodeURIComponent(svg);
}

function getFallbackTexture() {
  const background = readThemeColor("--md-sys-color-surface-container-low", "rgb(243 243 250)");
  const panel = readThemeColor("--md-sys-color-surface-container-high", "rgb(231 232 238)");
  const text = readThemeColor("--md-sys-color-on-surface-variant", "rgb(68 71 78)");
  return buildSvgDataUrl(
    "<svg xmlns='http://www.w3.org/2000/svg' width='96' height='96'>"
      + `<rect width='96' height='96' fill='${background}'/>`
      + `<rect x='10' y='10' width='76' height='76' fill='${panel}'/>`
      + `<text x='48' y='57' text-anchor='middle' font-size='36' fill='${text}'>?</text>`
    + "</svg>"
  );
}

function getFallbackAvatar() {
  const background = readThemeColor("--md-sys-color-surface-container-low", "rgb(243 243 250)");
  const accent = readThemeColor("--md-sys-color-secondary-container", "rgb(218 226 249)");
  return buildSvgDataUrl(
    "<svg xmlns='http://www.w3.org/2000/svg' width='96' height='96'>"
      + `<rect width='96' height='96' fill='${background}'/>`
      + `<circle cx='48' cy='35' r='18' fill='${accent}'/>`
      + `<rect x='22' y='58' width='52' height='24' rx='10' fill='${accent}'/>`
    + "</svg>"
  );
}

function resolveProductTextureMaterial(product) {
  const itemMaterial = String(product?.itemMaterial || "").trim().toUpperCase();
  if (itemMaterial) {
    return itemMaterial;
  }
  const productType = String(product?.productType || "").trim().toUpperCase();
  return PRODUCT_TYPE_TEXTURE_MAP[productType] || "BUNDLE";
}

const AVATAR_BASE = "https://nmsr.nickac.dev/face/";

const FALLBACK_ERROR_TIPS_COMMON = {
  auth_required: "请先登录后再操作。",
  auth_invalid: "登录状态已失效，请重新登录。",
  invalid_credentials: "账号或密码错误，请检查后重试。",
  not_bound: "当前账号未完成游戏内密码设置，请先在游戏内执行 /webshopx password <新密码>。",
  insufficient_funds: "余额不足，请先充值或兑换后再试。",
  internal_error: "服务器内部错误，请稍后重试。",
  method_not_allowed: "请求方式错误，请刷新页面后重试。",
  wallet_missing: "钱包不存在，请联系管理员检查数据。",
  user_missing: "账号数据不存在，请联系管理员处理。",
  feature_disabled: "该功能当前已被管理员关闭。",
  payment_unavailable: "支付服务当前不可用，请联系管理员检查支付插件。",
  payment_provider_not_found: "未找到配置的支付服务，请联系管理员检查配置。",
  payment_api_error: "支付服务返回错误，请稍后重试。",
  payment_create_failed: "支付订单创建失败，请稍后重试。",
  payment_query_failed: "支付订单查询失败，请稍后重试。",
  METHOD_UNSUPPORTED: "当前支付方式不可用，请选择其它支付方式。",
  UNSUPPORTED_RECHARGE_RATE: "当前支付方式与币种没有配置 ShopCoin 比例。",
  ORDER_NOT_FOUND: "未找到充值订单。",
  INVALID_STATUS: "当前订单状态不允许该操作。",
  invalid_market_side: "上架方向无效，只支持 SELL 或 BUY。",
  invalid_tag: "分类标签无效或不允许。",
  tag_disabled: "该分类标签已停用。",
};

const FALLBACK_ERROR_TIPS_BY_SCENE = {
  login: {
    invalid_identifier: "请输入用户名。",
    invalid_password: "密码长度需为 8-64 位。",
  },
  order_create: {
    invalid_quantity: "购买数量需在 1-64 之间。",
    product_missing: "该商品已下架或不可购买。",
    product_user_limit_reached: "该商品已达到你的限购上限，请等待管理员重置或退款后再试。",
    not_bound: "账号未绑定游戏角色，无法下单。",
    idempotency_too_long: "请求参数异常，请刷新后重试。",
    player_offline: "回收类商品需玩家在线且背包满足回收条件。",
    insufficient_item: "回收失败：背包物品不足。",
    recycle_command_failed: "回收失败：回收指令执行失败。",
    invalid_product_type: "商品类型配置有误，请联系管理员。",
    invalid_product: "商品配置有误，请联系管理员。",
    sync_timeout: "回收操作超时，请稍后再试。",
    sync_interrupted: "回收操作被中断，请稍后再试。",
  },
  orders_load: {
    auth_required: "请先登录后查看订单。",
    not_found: "订单接口不可用，请稍后重试。",
  },
  order_refund: {
    refund_disabled: "当前服务器未开启“未发放可退款”，且该订单不在可退款窗口内。",
    refund_expired: "冷静期已结束，无法退款。",
    refund_not_allowed: "当前订单状态不支持退款。",
    already_refunded: "该订单已退款。",
    order_missing: "未找到该订单，请刷新后再试。",
    voucher_consumed: "该团购券已核销，无法退款。",
  },
  market_buy: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架已下架或已售出。",
    auction_only_bid: "该上架为拍卖模式，请使用出价竞拍。",
    buy_order_not_active: "该收购单当前不可成交，请刷新后重试。",
    buy_requires_direct_mode: "收购单仅支持一口价模式。",
    invalid_trade: "不能购买自己上架的物品。",
    invalid_idempotency: "请求参数异常，请刷新后重试。",
    invalid_quantity: "购买数量需在 1-64 之间。",
    insufficient_quantity: "当前上架可购买数量不足，请刷新后重试。",
  },
  market_sell_to_buy: {
    invalid_listing: "收购单 ID 无效，请刷新列表后重试。",
    listing_missing: "该收购单不存在，可能已被移除。",
    buy_order_not_active: "该收购单当前不可交货。",
    cannot_fulfill_own_buy_order: "不能向自己的收购单交货。",
    fulfill_item_not_match: "交货物品与收购单模板不匹配。",
    invalid_quantity: "交货数量需在 1-64 之间。",
    insufficient_quantity: "收购单剩余数量不足，请刷新后重试。",
    buy_escrow_insufficient: "收购单冻结金额不足，请稍后重试。",
  },
  market_bid: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架当前不可竞拍。",
    invalid_trade_mode: "该上架不是拍卖模式。",
    invalid_bid: "出价金额必须大于 0，且不能竞拍自己的上架。",
    bid_too_low: "出价低于当前最低有效竞价。",
    auction_closed: "拍卖已结束，无法继续出价。",
    idempotency_conflict: "请求参数冲突，请刷新后重试。",
  },
  market_unlist: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架已下架或已售出。",
    forbidden: "仅上架者本人可以执行下架。",
  },
  market_price: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架已下架或已售出。",
    forbidden: "当前无权修改该上架，可能因非本人或自定义策略受限。",
    invalid_price: "价格必须大于 0。",
    invalid_trade_mode: "交易模式无效，仅支持 DIRECT 或 AUCTION。",
    limitation_item_forbidden: "当前物品不满足上架规则。",
    limitation_currency_not_allowed: "该币种不允许用于当前上架。",
    limitation_trade_mode_not_allowed: "该交易模式不允许用于当前上架。",
    limitation_side_not_allowed: "该上架方向不允许执行当前操作。",
    invalid_tag: "分类标签无效或不允许。",
    tag_disabled: "该分类标签已停用。",
    buy_requires_fixed_price: "收购单创建后不能改价或开启动态定价。",
    buy_requires_direct_mode: "收购单仅支持 DIRECT 模式。",
    buy_requires_manual_source: "收购单仅支持手动来源。",
    invalid_dynamic_base: "动态基准价必须大于 0。",
    invalid_dynamic_floor: "动态地板价必须大于 0。",
    invalid_dynamic_cap: "动态封顶价必须大于 0。",
    invalid_dynamic_step: "动态波动系数必须大于 0。",
    invalid_dynamic_range: "动态地板价不能高于封顶价。",
    invalid_auction_start: "起拍价必须大于 0。",
    invalid_auction_increment: "最小加价幅度必须大于 0。",
    invalid_auction_end: "拍卖结束时间必须晚于当前时间。",
    listing_empty: "拍卖模式需要有可售库存。",
  },
  market_remark: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架已下架或已售出。",
    forbidden: "仅上架者本人可以编辑备注。",
    invalid_remark: "备注长度超出限制。",
  },
  market_supply: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架当前不可刷新。",
    forbidden: "仅上架者本人可以刷新供货。",
    supply_missing: "当前未找到有效供货箱，请回到游戏内检查绑定。",
    supply_empty: "供货箱中没有匹配的库存。",
  },
  wallet_refresh: {
    bad_request: "请求参数异常，请重新登录后再试。",
  },
  exchange: {
    invalid_exchange: "兑换方向无效，请重新选择。",
    invalid_amount: "兑换数量必须大于 0。",
    exchange_disabled: "当前服务器未开放该兑换方向。",
    invalid_ratio: "兑换比例导致结果为 0，请增大兑换数量。",
  },
  redeem: {
    invalid_code: "兑换码格式不正确，请检查后重试。",
  },
  products_load: {
    not_found: "商品接口不可用，请稍后重试。",
  },
  market_load: {
    not_found: "市场接口不可用，请稍后重试。",
  },
  market_create: {
    invalid_market_side: "上架方向无效，只支持 SELL 或 BUY。",
    buy_requires_direct_mode: "收购单仅支持 DIRECT 模式。",
    buy_requires_manual_source: "收购单仅支持手动来源。",
    buy_requires_fixed_price: "收购单不支持动态定价或拍卖。",
    invalid_quantity: "数量需在 1-64 之间。",
    invalid_tag: "分类标签无效或不允许。",
    tag_disabled: "该分类标签已停用。",
    limitation_item_forbidden: "当前物品不满足上架规则。",
    limitation_currency_not_allowed: "该币种不允许用于当前上架。",
    limitation_trade_mode_not_allowed: "该交易模式不允许用于当前上架。",
    limitation_side_not_allowed: "该上架方向不允许执行当前操作。",
  },
  notifications_load: {
    not_found: "通知接口不可用，请稍后重试。",
  },
  notifications_mark_read: {
    bad_request: "通知参数有误，请刷新后重试。",
    not_found: "通知不存在或已被删除。",
  },
};

const FALLBACK_REDEEM_STATUS_TIPS = {
  SUCCESS: { tone: "success", text: "兑换成功，资产已入账。" },
  INVALID_CODE: { tone: "warn", text: "兑换失败：兑换码无效。" },
  EXPIRED: { tone: "warn", text: "兑换失败：兑换码已过期。" },
  OUT_OF_STOCK: { tone: "warn", text: "兑换失败：兑换码已领完。" },
  ALREADY_USED: { tone: "warn", text: "兑换失败：你已使用过该兑换码。" },
  USER_LIMIT_REACHED: { tone: "warn", text: "兑换失败：你已达到该兑换码的个人使用上限。" },
};

const FALLBACK_ORDER_STATUS_LABELS = {
  PENDING: { label: "待发放", tone: "pending" },
  WAIT_CLAIM: { label: "待领取", tone: "pending" },
  DELIVERED: { label: "已发放", tone: "delivered" },
  REFUNDED: { label: "已退款", tone: "refunded" },
  FAILED: { label: "失败", tone: "failed" },
  RECYCLED: { label: "已回收", tone: "delivered" },
};

const FALLBACK_ENCHANTMENT_LABELS = {
  protection: "保护",
  fire_protection: "火焰保护",
  feather_falling: "摔落保护",
  blast_protection: "爆炸保护",
  projectile_protection: "弹射物保护",
  respiration: "水下呼吸",
  aqua_affinity: "水下速掘",
  thorns: "荆棘",
  depth_strider: "深海探索者",
  frost_walker: "冰霜行者",
  binding_curse: "绑定诅咒",
  soul_speed: "灵魂疾行",
  swift_sneak: "迅捷潜行",
  sharpness: "锋利",
  smite: "亡灵杀手",
  bane_of_arthropods: "节肢杀手",
  knockback: "击退",
  fire_aspect: "火焰附加",
  looting: "抢夺",
  sweeping_edge: "横扫之刃",
  efficiency: "效率",
  silk_touch: "精准采集",
  unbreaking: "耐久",
  fortune: "时运",
  power: "力量",
  punch: "冲击",
  flame: "火矢",
  infinity: "无限",
  luck_of_the_sea: "海之眷顾",
  lure: "饵钓",
  loyalty: "忠诚",
  impaling: "穿刺",
  riptide: "激流",
  channeling: "引雷",
  multishot: "多重射击",
  quick_charge: "快速装填",
  piercing: "穿透",
  mending: "经验修补",
  vanishing_curse: "消失诅咒",
  density: "致密",
  breach: "破甲",
  wind_burst: "风爆",
};

const CURRENCY_META = mergeLocaleValue(FALLBACK_CURRENCY_META, APP_LOCALE_BUNDLE.currencyMeta);
const APP_UI_TEXT = Object.freeze(mergeLocaleValue(FALLBACK_APP_UI_TEXT, APP_LOCALE_BUNDLE.uiText));
const APP_TEXT_TEMPLATES = Object.freeze(APP_UI_TEXT.templates || {});
const APP_PHRASE_MAP = Object.freeze(APP_UI_TEXT.autoPhrase || {});
const ERROR_TIPS_COMMON = mergeLocaleValue(FALLBACK_ERROR_TIPS_COMMON, APP_LOCALE_BUNDLE.errorTipsCommon);
const ERROR_TIPS_BY_SCENE = mergeLocaleValue(FALLBACK_ERROR_TIPS_BY_SCENE, APP_LOCALE_BUNDLE.errorTipsByScene);
const REDEEM_STATUS_TIPS = mergeLocaleValue(FALLBACK_REDEEM_STATUS_TIPS, APP_LOCALE_BUNDLE.redeemStatusTips);
const ORDER_STATUS_LABELS = mergeLocaleValue(FALLBACK_ORDER_STATUS_LABELS, APP_LOCALE_BUNDLE.orderStatusLabels);
const ENCHANTMENT_LABELS = mergeLocaleValue(FALLBACK_ENCHANTMENT_LABELS, APP_LOCALE_BUNDLE.enchantmentLabels);

function formatAppTemplate(key, params = {}) {
  const template = APP_TEXT_TEMPLATES[key];
  if (!template) {
    return "";
  }
  return String(template).replace(/\{(\w+)\}/g, (_, token) => {
    const value = params[token];
    return value == null ? "" : String(value);
  });
}

function getAppPageText(key, fallback = "") {
  return String(APP_UI_TEXT.page?.[key] || fallback);
}

const elements = {
  logBox: document.getElementById("logBox"),
  statusChip: document.getElementById("statusChip"),
  headerControls: document.getElementById("headerControls"),
  headerMoreBtn: document.getElementById("headerMoreBtn"),
  headerMoreMenu: document.getElementById("headerMoreMenu"),
  headerAccountBackBtn: document.getElementById("headerAccountBackBtn"),
  themeToggleBtn: document.getElementById("themeToggleBtn"),
  themeSelect: document.getElementById("themeSelect"),

  authEntryCard: document.getElementById("authEntryCard"),
  authProfileCard: document.getElementById("authProfileCard"),
  authFunctionsCard: document.getElementById("authFunctionsCard"),
  authLoginPanel: document.getElementById("authLoginPanel"),

  loginIdentifier: document.getElementById("loginIdentifier"),
  loginPassword: document.getElementById("loginPassword"),
  loginBtn: document.getElementById("loginBtn"),

  profileAvatar: document.getElementById("profileAvatar"),
  profileName: document.getElementById("profileName"),
  profileUuid: document.getElementById("profileUuid"),
  logoutBtn: document.getElementById("logoutBtn"),
  accountHelpBtns: Array.from(document.querySelectorAll("#accountHelpBtn")),

  walletView: document.getElementById("walletView"),
  walletLedgerView: document.getElementById("walletLedgerView"),
  walletLedgerList: document.getElementById("walletLedgerList"),
  rechargeAmount: document.getElementById("rechargeAmount"),
  rechargeAmountLabel: document.getElementById("rechargeAmountLabel"),
  rechargePaymentCurrency: document.getElementById("rechargePaymentCurrency"),
  rechargePaymentMethod: document.getElementById("rechargePaymentMethod"),
  rechargeCoinPreview: document.getElementById("rechargeCoinPreview"),
  rechargeBtn: document.getElementById("rechargeBtn"),
  rechargeStatusBtn: document.getElementById("rechargeStatusBtn"),
  rechargePayLink: document.getElementById("rechargePayLink"),
  rechargeView: document.getElementById("rechargeView"),
  rechargePaymentDialog: document.getElementById("rechargePaymentDialog"),
  rechargePaymentDialogOrder: document.getElementById("rechargePaymentDialogOrder"),
  rechargePaymentDialogAmount: document.getElementById("rechargePaymentDialogAmount"),
  rechargePaymentDialogExpireAt: document.getElementById("rechargePaymentDialogExpireAt"),
  rechargePaymentDialogExpireCountdown: document.getElementById("rechargePaymentDialogExpireCountdown"),
  rechargePaymentDialogStatus: document.getElementById("rechargePaymentDialogStatus"),
  rechargeQrPanel: document.getElementById("rechargeQrPanel"),
  rechargeQrImage: document.getElementById("rechargeQrImage"),
  rechargeQrEmpty: document.getElementById("rechargeQrEmpty"),
  rechargePaymentSuccess: document.getElementById("rechargePaymentSuccess"),
  rechargePaymentDialogClose: document.getElementById("rechargePaymentDialogClose"),
  rechargePaymentDialogCancel: document.getElementById("rechargePaymentDialogCancel"),
  rechargePaymentDialogOpen: document.getElementById("rechargePaymentDialogOpen"),
  redeemView: document.getElementById("redeemView"),
  exchangeRateHint: document.getElementById("exchangeRateHint"),
  exchangeView: document.getElementById("exchangeView"),
  exchangeFrom: document.getElementById("exchangeFrom"),
  exchangeTo: document.getElementById("exchangeTo"),
  exchangeAmount: document.getElementById("exchangeAmount"),
  orderView: document.getElementById("orderView"),
  ordersBtn: document.getElementById("ordersBtn"),
  orderList: document.getElementById("orderList"),
  notificationsView: document.getElementById("notificationsView"),
  notificationsList: document.getElementById("notificationsList"),
  notificationsRefreshBtn: document.getElementById("notificationsRefreshBtn"),
  notificationsMarkAllBtn: document.getElementById("notificationsMarkAllBtn"),
  notificationsBadge: document.getElementById("notificationsBadge"),
  marketView: document.getElementById("marketView"),
  shopCoinValue: document.getElementById("shopCoinValue"),
  gameCoinValue: document.getElementById("gameCoinValue"),
  productList: document.getElementById("productList"),
  productKeyword: document.getElementById("productKeyword"),
  productSearchBtn: document.getElementById("productSearchBtn"),
  productKeywordClearBtn: document.getElementById("productKeywordClearBtn"),
  productSort: document.getElementById("productSort"),
  productFilterType: document.getElementById("productFilterType"),
  productFilterCurrency: document.getElementById("productFilterCurrency"),
  productFilterMaterial: document.getElementById("productFilterMaterial"),
  productMinPrice: document.getElementById("productMinPrice"),
  productMaxPrice: document.getElementById("productMaxPrice"),
  productApplyBtn: document.getElementById("productApplyBtn"),
  productClearBtn: document.getElementById("productClearBtn"),
  marketList: document.getElementById("marketList"),
  marketKeyword: document.getElementById("marketKeyword"),
  marketSide: document.getElementById("marketSide"),
  marketTag: document.getElementById("marketTag"),
  marketMaterial: document.getElementById("marketMaterial"),
  marketCurrency: document.getElementById("marketCurrency"),
  marketMinPrice: document.getElementById("marketMinPrice"),
  marketMaxPrice: document.getElementById("marketMaxPrice"),
  marketSort: document.getElementById("marketSort"),
  marketSearchBtn: document.getElementById("marketSearchBtn"),
  marketKeywordClearBtn: document.getElementById("marketKeywordClearBtn"),
  marketApplyBtn: document.getElementById("marketApplyBtn"),
  marketClearBtn: document.getElementById("marketClearBtn"),
  marketStoreBtn: document.getElementById("marketStoreBtn"),
  marketCreateBuyBtn: document.getElementById("marketCreateBuyBtn"),
  marketHideOwnToggle: document.getElementById("marketHideOwnToggle"),
  marketSectionTitle: document.getElementById("marketSectionTitle"),
  marketSectionDesc: document.getElementById("marketSectionDesc"),
  leaderboardTabBtn: document.getElementById("leaderboardTabBtn"),
  leaderboardPanel: document.getElementById("leaderboardPanel"),
  leaderboardMetric: document.getElementById("leaderboardMetric"),
  leaderboardOrder: document.getElementById("leaderboardOrder"),
  leaderboardRange: document.getElementById("leaderboardRange"),
  leaderboardShowOnlineToggle: document.getElementById("leaderboardShowOnlineToggle"),
  leaderboardRefreshBtn: document.getElementById("leaderboardRefreshBtn"),
  leaderboardMyRankBtn: document.getElementById("leaderboardMyRankBtn"),
  leaderboardView: document.getElementById("leaderboardView"),
  leaderboardMyRankView: document.getElementById("leaderboardMyRankView"),
  leaderboardList: document.getElementById("leaderboardList"),
  snackbarHost: document.getElementById("snackbarHost"),
  confirmDialog: document.getElementById("confirmDialog"),
  confirmTitle: document.getElementById("confirmTitle"),
  confirmMessage: document.getElementById("confirmMessage"),
  confirmDetails: document.getElementById("confirmDetails"),
  confirmCancelBtn: document.getElementById("confirmCancelBtn"),
  confirmOkBtn: document.getElementById("confirmOkBtn"),
  marketParamDialog: document.getElementById("marketParamDialog"),
  marketParamTitle: document.getElementById("marketParamTitle"),
  marketParamHint: document.getElementById("marketParamHint"),
  marketParamDetails: document.getElementById("marketParamDetails"),
  marketParamCancelBtn: document.getElementById("marketParamCancelBtn"),
  marketParamSaveBtn: document.getElementById("marketParamSaveBtn"),
  priceDialog: document.getElementById("priceDialog"),
  priceDialogTitle: document.getElementById("priceDialogTitle"),
  priceDialogHint: document.getElementById("priceDialogHint"),
  priceDialogBadge: document.getElementById("priceDialogBadge"),
  priceDialogCurrent: document.getElementById("priceDialogCurrent"),
  priceDialogCurrency: document.getElementById("priceDialogCurrency"),
  priceDialogPrefix: document.getElementById("priceDialogPrefix"),
  priceDialogInput: document.getElementById("priceDialogInput"),
  priceDialogError: document.getElementById("priceDialogError"),
  priceDialogCancel: document.getElementById("priceDialogCancel"),
  priceDialogConfirm: document.getElementById("priceDialogConfirm"),
  materialCropDialog: document.getElementById("materialCropDialog"),
  materialCropCanvas: document.getElementById("materialCropCanvas"),
  materialCropZoom: document.getElementById("materialCropZoom"),
  materialCropZoomValue: document.getElementById("materialCropZoomValue"),
  materialCropResetBtn: document.getElementById("materialCropResetBtn"),
  materialCropCancelBtn: document.getElementById("materialCropCancelBtn"),
  materialCropApplyBtn: document.getElementById("materialCropApplyBtn"),
  materialSuggestList: document.getElementById("materialSuggestList"),
};

const tabs = Array.from(document.querySelectorAll(".top-tab"));
const panels = Array.from(document.querySelectorAll(".tab-panel"));
const accountEntryButtons = Array.from(document.querySelectorAll("[data-account-tab]"));
const accountBackButtons = Array.from(document.querySelectorAll("[data-account-back]"));
const ACCOUNT_CHILD_TABS = new Set(["wallet", "orders", "notifications", "logs"]);

function getMarketTradeScopeByTab(tabName) {
  return tabName === "auction" ? "AUCTION" : "DIRECT";
}

function isAuctionScope() {
  return String(state.marketTradeScope || "DIRECT").toUpperCase() === "AUCTION";
}

function normalizeListingTradeMode(listing) {
  return String(listing?.tradeMode || "DIRECT").toUpperCase();
}

function normalizeListingSide(listing) {
  return String(listing?.side || "SELL").toUpperCase();
}

function formatListingSide(side) {
  return String(side || "SELL").toUpperCase() === "BUY" ? "收购单" : "出售单";
}

function formatListingTopStatus(status, side) {
  const normalizedStatus = String(status || "").toUpperCase();
  const normalizedSide = String(side || "SELL").toUpperCase();
  if (normalizedStatus === "ACTIVE") {
    return normalizedSide === "BUY"
      ? localizeDisplayText("回收")
      : localizeDisplayText("在售");
  }
  if (normalizedStatus === "PAUSED") {
    return localizeDisplayText("暂停");
  }
  if (normalizedStatus === "UNLISTED") {
    return localizeDisplayText("下架");
  }
  return formatListingStatus(normalizedStatus);
}

function normalizeTagCode(tag) {
  return String(tag || "").trim().toLowerCase();
}

function getMarketTagMeta(tagCode) {
  const normalizedTag = normalizeTagCode(tagCode);
  if (!normalizedTag) {
    return null;
  }
  return (state.marketTags || []).find((tag) => normalizeTagCode(tag.code) === normalizedTag) || null;
}

function getMarketTagDisplayName(tagCode) {
  const normalizedTag = normalizeTagCode(tagCode);
  if (!normalizedTag) {
    return "未分类";
  }
  const meta = getMarketTagMeta(normalizedTag);
  if (meta && meta.displayName) {
    return meta.displayName;
  }
  return normalizedTag;
}

function filterListingsByTradeScope(listings, tradeScope) {
  const normalizedScope = String(tradeScope || "DIRECT").toUpperCase();
  return (listings || []).filter((listing) => {
    const tradeMode = normalizeListingTradeMode(listing);
    if (normalizedScope === "AUCTION") {
      return tradeMode === "AUCTION";
    }
    return tradeMode !== "AUCTION";
  });
}

function getMarketModeLabel(mode) {
  const normalizedMode = mode === "stores" ? "stores" : (mode === "mine" ? "mine" : "public");
  const modeMap = isAuctionScope()
    ? APP_UI_TEXT.marketModeLabel.auction
    : APP_UI_TEXT.marketModeLabel.direct;
  return modeMap[normalizedMode] || modeMap.public;
}

function getMarketEmptyStateText(kind = "listing") {
  const key = kind === "store" ? "store" : "listing";
  const textMap = isAuctionScope()
    ? APP_UI_TEXT.marketEmptyState.auction
    : APP_UI_TEXT.marketEmptyState.direct;
  return textMap[key] || textMap.listing;
}

function updateMarketSectionContext() {
  if (!elements.marketSectionTitle || !elements.marketSectionDesc) {
    return;
  }

  const sectionText = isAuctionScope()
    ? APP_UI_TEXT.marketSection.auction
    : APP_UI_TEXT.marketSection.direct;

  setNodeText(elements.marketSectionTitle, sectionText.title);
  setNodeText(elements.marketSectionDesc, sectionText.desc);
  setNodeText(document.getElementById("marketListBtn"), sectionText.listBtn);
  setNodeText(document.getElementById("marketMineBtn"), sectionText.mineBtn);
  if (elements.marketStoreBtn) {
    setNodeText(elements.marketStoreBtn, sectionText.storeBtn);
  }

  if (isAuctionScope()) {
    if (elements.marketCreateBuyBtn) {
      elements.marketCreateBuyBtn.disabled = true;
      elements.marketCreateBuyBtn.title = sectionText.createBuyTitle;
    }
    return;
  }

  if (elements.marketCreateBuyBtn) {
    elements.marketCreateBuyBtn.disabled = false;
    elements.marketCreateBuyBtn.title = sectionText.createBuyTitle;
  }
}

function applyAppPhraseMap(text) {
  if (!text || !APP_PHRASE_MAP || typeof APP_PHRASE_MAP !== "object") {
    return text;
  }
  const entries = Object.entries(APP_PHRASE_MAP)
    .filter(([from, to]) => typeof from === "string" && from.length > 0 && typeof to === "string")
    .sort((left, right) => right[0].length - left[0].length);
  if (entries.length === 0) {
    return text;
  }
  let result = String(text);
  entries.forEach(([from, to]) => {
    if (!from || from === to) {
      return;
    }
    result = result.split(from).join(to);
  });
  return result;
}

function localizeDisplayText(text) {
  const localized = I18N ? I18N.localizeText(text) : text;
  if (!I18N || (typeof I18N.isChineseLocale === "function" && I18N.isChineseLocale())) {
    return localized;
  }
  return applyAppPhraseMap(localized);
}

function setNodeText(node, text) {
  if (!node) {
    return;
  }
  node.textContent = localizeDisplayText(text);
}

function log(message, level = "INFO") {
  const now = new Date();
  const prefix = `${now.toLocaleString(I18N ? I18N.getIntlLocale() : "zh-CN", { hour12: false })} [${level}]`;
  const line = `${prefix} ${localizeDisplayText(message)}`;
  elements.logBox.textContent = `${line}\n${elements.logBox.textContent}`.slice(0, 30000);
}

function setMetaText(element, text, tone = "info") {
  if (!element) {
    return;
  }
  element.textContent = localizeDisplayText(text);
  element.classList.remove("meta-info", "meta-success", "meta-warn", "meta-error");
  const normalized = ["info", "success", "warn", "error"].includes(tone) ? tone : "info";
  element.classList.add(`meta-${normalized}`);
}

function notify(message, tone = "info", durationMs = 3200) {
  if (!elements.snackbarHost) {
    return;
  }
  const normalized = ["info", "success", "warn", "error"].includes(tone) ? tone : "info";
  const node = createEl("div", `snackbar snackbar-${normalized}`, message);
  elements.snackbarHost.appendChild(node);

  window.requestAnimationFrame(() => {
    node.classList.add("show");
  });

  window.setTimeout(() => {
    node.classList.remove("show");
    window.setTimeout(() => {
      node.remove();
    }, 200);
  }, durationMs);
}

const THEME_STORAGE_KEY = "webshopx_theme";
const THEME_PACKAGE_STORAGE_KEY = "webshopx_theme_package";
const THEME_LIGHT_OVERRIDE_LINK_ID = "themeOverrideLight";
const THEME_DARK_OVERRIDE_LINK_ID = "themeOverrideDark";
const SESSION_STORAGE_KEY = "webshopx_session";
const MARKET_HIDE_OWN_STORAGE_KEY = "webshopx_market_hide_own";
const THEME_CENTER_DEFAULTS = Object.freeze({
  defaultTheme: "default",
  themes: [
    { themeId: "default", name: "Default", source: "built-in", version: "builtin-1" },
  ],
});

function getInitialTheme() {
  const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
  if (saved === "dark" || saved === "light") {
    return saved;
  }
  if (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) {
    return "dark";
  }
  return "light";
}

function applyTheme(theme) {
  const normalized = theme === "dark" ? "dark" : "light";
  state.theme = normalized;
  document.documentElement.classList.remove("light", "dark");
  document.documentElement.classList.add(normalized);
  window.localStorage.setItem(THEME_STORAGE_KEY, normalized);
  if (elements.themeToggleBtn) {
    elements.themeToggleBtn.textContent = I18N
      ? I18N.getThemeToggleLabel(normalized)
      : (normalized === "dark" ? APP_UI_TEXT.themeToggleLight : APP_UI_TEXT.themeToggleDark);
  }
}

function toggleTheme() {
  applyTheme(state.theme === "dark" ? "light" : "dark");
}

function setupHeaderOverflowMenu() {
  const controls = elements.headerControls;
  const moreBtn = elements.headerMoreBtn;
  const moreMenu = elements.headerMoreMenu;
  if (!controls || !moreBtn || !moreMenu) {
    return;
  }
  const candidates = Array.from(controls.querySelectorAll("[data-header-overflow-item='1']"));
  if (candidates.length === 0) {
    return;
  }

  const mobileQuery = window.matchMedia("(max-width: 860px)");
  let layoutRaf = 0;

  const closeMenu = () => {
    controls.dataset.overflowOpen = "false";
    moreBtn.setAttribute("aria-expanded", "false");
    moreMenu.classList.add("hidden");
  };

  const openMenu = () => {
    if (moreMenu.childElementCount <= 0 || moreBtn.classList.contains("hidden")) {
      closeMenu();
      return;
    }
    controls.dataset.overflowOpen = "true";
    moreBtn.setAttribute("aria-expanded", "true");
    moreMenu.classList.remove("hidden");
  };

  const moveAllBack = () => {
    candidates.forEach((item) => {
      if (item.parentElement !== controls) {
        controls.insertBefore(item, moreBtn);
      }
    });
  };

  const scheduleLayout = () => {
    if (layoutRaf) {
      return;
    }
    layoutRaf = window.requestAnimationFrame(() => {
      layoutRaf = 0;
      relayout();
    });
  };

  controls.refreshOverflowMenuLayout = scheduleLayout;

  const relayout = () => {
    const isMobile = mobileQuery.matches;
    closeMenu();
    moveAllBack();

    if (!isMobile) {
      moreBtn.classList.add("hidden");
      return;
    }

    const visibleCandidates = candidates.filter((item) => !item.classList.contains("hidden"));
    visibleCandidates.forEach((item) => {
      moreMenu.appendChild(item);
    });

    if (visibleCandidates.length <= 0) {
      moreBtn.classList.add("hidden");
      closeMenu();
      return;
    }
    moreBtn.classList.remove("hidden");
    closeMenu();
  };

  moreBtn.addEventListener("click", (event) => {
    event.stopPropagation();
    if (controls.dataset.overflowOpen === "true") {
      closeMenu();
      return;
    }
    openMenu();
  });

  document.addEventListener("click", (event) => {
    if (!controls.contains(event.target)) {
      closeMenu();
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeMenu();
    }
  });

  if (typeof ResizeObserver === "function") {
    const observer = new ResizeObserver(() => scheduleLayout());
    observer.observe(controls);
  } else {
    window.addEventListener("resize", scheduleLayout);
  }

  if (typeof mobileQuery.addEventListener === "function") {
    mobileQuery.addEventListener("change", scheduleLayout);
  } else if (typeof mobileQuery.addListener === "function") {
    mobileQuery.addListener(scheduleLayout);
  }

  scheduleLayout();
}

function cloneThemeCenterDefaults() {
  return JSON.parse(JSON.stringify(THEME_CENTER_DEFAULTS));
}

function normalizeThemePackageId(raw) {
  const themeId = String(raw || "").trim().toLowerCase().replaceAll(" ", "-");
  if (!themeId) {
    return "";
  }
  if (!/^[a-z0-9][a-z0-9._-]{0,47}$/.test(themeId)) {
    return "";
  }
  return themeId;
}

function normalizeThemePackageRecord(raw) {
  const themeId = normalizeThemePackageId(raw?.themeId || raw?.id || raw?.key || raw?.theme);
  if (!themeId) {
    return null;
  }
  return {
    themeId,
    name: String(raw?.name || themeId).trim() || themeId,
    source: String(raw?.source || "upload").trim().toLowerCase(),
    version: String(raw?.version || "").trim(),
  };
}

function applyThemeCenterState(rawState) {
  const fallback = cloneThemeCenterDefaults();
  const source = rawState && typeof rawState === "object" ? rawState : fallback;
  const themes = Array.isArray(source.themes)
    ? source.themes.map((item) => normalizeThemePackageRecord(item)).filter(Boolean)
    : fallback.themes.map((item) => normalizeThemePackageRecord(item)).filter(Boolean);
  state.themeCenter = {
    defaultTheme: normalizeThemePackageId(source.defaultTheme) || fallback.defaultTheme,
    themes,
  };
}

function loadThemeCenterState() {
  applyThemeCenterState(cloneThemeCenterDefaults());
}

function renderThemeSelect() {
  if (!elements.themeSelect) {
    return;
  }
  const select = elements.themeSelect;
  select.innerHTML = "";
  const themes = Array.isArray(state.themeCenter.themes) ? state.themeCenter.themes : [];
  const sorted = themes.slice().sort((left, right) => left.themeId.localeCompare(right.themeId));
  if (sorted.length === 0) {
    const option = document.createElement("option");
    option.value = "default";
    option.textContent = "Default";
    select.appendChild(option);
    return;
  }
  sorted.forEach((item) => {
    const option = document.createElement("option");
    const label = item.name && item.name !== item.themeId ? `${item.name} (${item.themeId})` : item.name;
    option.value = item.themeId;
    option.textContent = label || item.themeId;
    select.appendChild(option);
  });
}

function getSavedThemePackage() {
  try {
    return String(window.localStorage.getItem(THEME_PACKAGE_STORAGE_KEY) || "").trim();
  } catch (error) {
    return "";
  }
}

function ensureThemeOverrideLink(id) {
  let link = document.getElementById(id);
  if (!link) {
    link = document.createElement("link");
    link.rel = "stylesheet";
    link.id = id;
    document.head.appendChild(link);
  }
  return link;
}

function removeThemeOverrideLinks() {
  const lightLink = document.getElementById(THEME_LIGHT_OVERRIDE_LINK_ID);
  if (lightLink) {
    lightLink.remove();
  }
  const darkLink = document.getElementById(THEME_DARK_OVERRIDE_LINK_ID);
  if (darkLink) {
    darkLink.remove();
  }
}

function updateThemeOverrideLinks(themeId) {
  const lightLink = ensureThemeOverrideLink(THEME_LIGHT_OVERRIDE_LINK_ID);
  const darkLink = ensureThemeOverrideLink(THEME_DARK_OVERRIDE_LINK_ID);
  const handleError = () => {
    if (state.themePackage === themeId) {
      applyThemePackage("default");
    }
  };
  lightLink.onerror = handleError;
  darkLink.onerror = handleError;
  lightLink.href = `/themes/${themeId}/light.css`;
  darkLink.href = `/themes/${themeId}/dark.css`;
}

function applyThemePackage(themeId, options = {}) {
  const normalized = normalizeThemePackageId(themeId) || "default";
  state.themePackage = normalized;
  if (!options.skipStorage) {
    try {
      window.localStorage.setItem(THEME_PACKAGE_STORAGE_KEY, normalized);
    } catch (error) {
      // ignore storage issues
    }
  }
  if (normalized === "default") {
    removeThemeOverrideLinks();
  } else {
    updateThemeOverrideLinks(normalized);
  }
  if (elements.themeSelect) {
    elements.themeSelect.value = normalized;
  }
}

function syncThemePackageSelection() {
  const available = new Set((state.themeCenter.themes || []).map((item) => item.themeId));
  let next = normalizeThemePackageId(getSavedThemePackage());
  if (!next || !available.has(next)) {
    next = normalizeThemePackageId(state.themeCenter.defaultTheme) || "default";
  }
  applyThemePackage(next);
}

async function loadThemeCenterStateFromServer() {
  try {
    const payload = await api("/api/meta/themes", { method: "GET" });
    applyThemeCenterState(payload);
  } catch (error) {
    loadThemeCenterState();
  }
  renderThemeSelect();
  syncThemePackageSelection();
}

async function copyTextToClipboard(text) {
  const value = String(text || "").trim();
  if (!value) {
    throw new Error(localizeDisplayText("没有可复制的内容。"));
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const tmp = document.createElement("textarea");
  tmp.value = value;
  tmp.style.position = "fixed";
  tmp.style.opacity = "0";
  document.body.appendChild(tmp);
  tmp.select();
  document.execCommand("copy");
  tmp.remove();
}

let confirmResolver = null;
let confirmSubmitHandler = null;

function openConfirmDialog({ title, message, details = [], confirmText = "确认" }) {
  if (!elements.confirmDialog) {
    return Promise.resolve(window.confirm(`${title}\n${message}`));
  }
  if (confirmResolver) {
    confirmResolver(false);
    confirmResolver = null;
  }

  setNodeText(elements.confirmTitle, title);
  setNodeText(elements.confirmMessage, message);
  elements.confirmDetails.innerHTML = "";
  confirmSubmitHandler = null;
  details.filter(Boolean).forEach((line) => {
    elements.confirmDetails.appendChild(createEl("div", "", line));
  });
  elements.confirmOkBtn.disabled = false;
  setNodeText(elements.confirmOkBtn, confirmText);
  elements.confirmDialog.classList.add("show");
  elements.confirmDialog.setAttribute("aria-hidden", "false");

  return new Promise((resolve) => {
    confirmResolver = resolve;
  });
}

function openDeliveryConfirmDialog({
  title,
  message,
  details = [],
  confirmText = "确认",
  initialValue = "IMMEDIATE",
  allowClaim = true,
  summary,
}) {
  if (!elements.confirmDialog) {
    const fallback = window.confirm(`${title}\n${message}`);
    if (!fallback) {
      return Promise.resolve(null);
    }
    return Promise.resolve(initialValue);
  }
  if (confirmResolver) {
    confirmResolver(false);
    confirmResolver = null;
  }

  setNodeText(elements.confirmTitle, title);
  setNodeText(elements.confirmMessage, message);
  elements.confirmDetails.innerHTML = "";
  confirmSubmitHandler = null;
  details.filter(Boolean).forEach((line) => {
    elements.confirmDetails.appendChild(createEl("div", "", line));
  });

  const select = document.createElement("select");
  select.innerHTML = `
    <option value="IMMEDIATE">即时到账</option>
    <option value="CLAIM">手动领取（/ws claim）</option>
  `;
  select.value = allowClaim ? initialValue : "IMMEDIATE";
  if (!allowClaim) {
    select.value = "IMMEDIATE";
    select.disabled = true;
  }
  const field = createDialogSelectField("领取方式", select);
  elements.confirmDetails.appendChild(field);

  if (summary) {
    const summaryCard = createEl("div", "checkout-summary");
    summaryCard.appendChild(createEl("p", "checkout-kicker", "结算摘要"));
    const rows = [];
    const isCredit = !!summary.isCredit;
    const finalLabel = summary.finalLabel || (isCredit ? "预计入账" : "最终扣款");
    rows.push(["小计", formatCurrency(summary.subtotal, summary.currency)]);
    if (Number(summary.taxAmount || 0) > 0) {
      rows.push([summary.taxLabel || "税额（买家承担）", formatCurrency(summary.taxAmount, summary.currency)]);
    }
    rows.push([
      finalLabel,
      formatCurrency(summary.finalAmount, summary.currency),
      isCredit ? "balance-positive" : "negative",
    ]);
    const hasCurrentBalance = Number.isFinite(summary.currentBalance);
    const hasRemainingBalance = Number.isFinite(summary.remainingBalance);
    const isInsufficient = !isCredit && hasRemainingBalance && summary.remainingBalance < 0;
    if (hasCurrentBalance && hasRemainingBalance) {
      rows.push([
        "余额变化",
        `${formatCurrency(summary.currentBalance, summary.currency)} → ${formatCurrency(summary.remainingBalance, summary.currency)}`,
        isInsufficient ? "balance-negative" : "balance-positive",
      ]);
    } else if (hasRemainingBalance) {
      rows.push([
        isInsufficient ? "余额不足" : "结算后余额",
        formatCurrency(summary.remainingBalance, summary.currency),
        isInsufficient ? "balance-negative" : "balance-positive",
      ]);
    }
    rows.forEach(([label, value, tone]) => {
      const row = createEl("div", "checkout-row");
      row.appendChild(createEl("span", "", label));
      const valueNode = createEl("strong", "checkout-value", value);
      row.appendChild(valueNode);
      if (tone === "emphasis") {
        row.classList.add("emphasis");
      }
      if (tone === "negative") {
        row.classList.add("negative");
      }
      if (tone === "balance-positive") {
        valueNode.classList.add("checkout-pill", "checkout-pill-positive");
      }
      if (tone === "balance-negative") {
        valueNode.classList.add("checkout-pill", "checkout-pill-negative");
      }
      summaryCard.appendChild(row);
    });
    elements.confirmDetails.appendChild(summaryCard);

    const noteText = summary.noteText || (isInsufficient
      ? `余额不足，还差 ${formatCurrency(Math.abs(summary.remainingBalance), summary.currency)}。`
      : (isCredit ? "确认后将按照以上金额入账。" : "确认后将按照以上金额结算。"));
    const note = createEl(
      "p",
      isInsufficient ? "checkout-warning negative" : "checkout-warning",
      noteText
    );
    elements.confirmDetails.appendChild(note);
    elements.confirmOkBtn.disabled = isInsufficient;
  } else {
    elements.confirmOkBtn.disabled = false;
  }

  confirmSubmitHandler = () => closeConfirmDialog(select.value);
  setNodeText(elements.confirmOkBtn, confirmText);
  elements.confirmDialog.classList.add("show");
  elements.confirmDialog.setAttribute("aria-hidden", "false");

  return new Promise((resolve) => {
    confirmResolver = resolve;
  });
}

function openExchangeConfirmDialog({
  fromCurrency,
  toCurrency,
  amount,
  convertedAmount,
  ratio,
  fromBalance,
  toBalance,
}) {
  if (!elements.confirmDialog) {
    const fromMeta = CURRENCY_META[fromCurrency] || { label: fromCurrency };
    const toMeta = CURRENCY_META[toCurrency] || { label: toCurrency };
    return Promise.resolve(
      window.confirm(
        `确认兑换\n${fromMeta.label} -> ${toMeta.label}\n${formatCurrency(amount, fromCurrency)} -> ${formatCurrency(convertedAmount, toCurrency)}`
      )
    );
  }
  if (confirmResolver) {
    confirmResolver(false);
    confirmResolver = null;
  }

  const fromMeta = CURRENCY_META[fromCurrency] || { label: fromCurrency, short: fromCurrency };
  const toMeta = CURRENCY_META[toCurrency] || { label: toCurrency, short: toCurrency };
  const fromRemaining = Number(fromBalance || 0) - Number(amount || 0);
  const toRemaining = Number(toBalance || 0) + Number(convertedAmount || 0);
  const isInsufficient = fromRemaining < 0;

  setNodeText(elements.confirmTitle, "确认兑换");
  setNodeText(elements.confirmMessage, "请确认本次兑换信息，确认后将立即结算。");
  elements.confirmDetails.innerHTML = "";
  confirmSubmitHandler = null;

  const summaryCard = createEl("div", "checkout-summary");
  summaryCard.appendChild(createEl("p", "checkout-kicker", "结算摘要"));
  const rows = [
    ["兑换方向", `${fromMeta.label} → ${toMeta.label}`],
    ["当前比例", `1 ${fromMeta.short} = ${formatRatioValue(ratio)} ${toMeta.short}`],
    ["扣除", formatCurrency(amount, fromCurrency), "negative"],
    ["预计入账", formatCurrency(convertedAmount, toCurrency), "balance-positive"],
    [`结算后${fromMeta.label}余额`, formatCurrency(fromRemaining, fromCurrency), isInsufficient ? "balance-negative" : "balance-positive"],
    [`结算后${toMeta.label}余额`, formatCurrency(toRemaining, toCurrency), "balance-positive"],
  ];

  rows.forEach(([label, value, tone]) => {
    const row = createEl("div", "checkout-row");
    row.appendChild(createEl("span", "", label));
    const valueNode = createEl("strong", "checkout-value", value);
    row.appendChild(valueNode);
    if (tone === "negative") {
      row.classList.add("negative");
    }
    if (tone === "balance-positive") {
      valueNode.classList.add("checkout-pill", "checkout-pill-positive");
    }
    if (tone === "balance-negative") {
      valueNode.classList.add("checkout-pill", "checkout-pill-negative");
    }
    summaryCard.appendChild(row);
  });
  elements.confirmDetails.appendChild(summaryCard);

  const noteText = isInsufficient
    ? `余额不足，还差 ${formatCurrency(Math.abs(fromRemaining), fromCurrency)}。`
    : "确认后将按以上信息完成兑换。";
  const note = createEl("p", isInsufficient ? "checkout-warning negative" : "checkout-warning", noteText);
  elements.confirmDetails.appendChild(note);

  elements.confirmOkBtn.disabled = isInsufficient;
  confirmSubmitHandler = () => closeConfirmDialog(true);
  setNodeText(elements.confirmOkBtn, "确认兑换");
  elements.confirmDialog.classList.add("show");
  elements.confirmDialog.setAttribute("aria-hidden", "false");

  return new Promise((resolve) => {
    confirmResolver = resolve;
  });
}

function closeConfirmDialog(result) {
  if (!elements.confirmDialog) {
    return;
  }
  elements.confirmDialog.classList.remove("show");
  elements.confirmDialog.setAttribute("aria-hidden", "true");
  confirmSubmitHandler = null;
  if (elements.confirmOkBtn) {
    elements.confirmOkBtn.disabled = false;
  }
  if (confirmResolver) {
    confirmResolver(result);
    confirmResolver = null;
  }
}

let marketParamResolver = null;
let marketParamSubmitHandler = null;

function closeMarketParamDialog(result) {
  if (!elements.marketParamDialog) {
    return;
  }
  elements.marketParamDialog.classList.remove("show");
  elements.marketParamDialog.setAttribute("aria-hidden", "true");
  marketParamSubmitHandler = null;
  if (marketParamResolver) {
    marketParamResolver(result);
    marketParamResolver = null;
  }
}

function openMarketParamDialog({ title, hint, confirmText, setupForm, resolveValue }) {
  if (!elements.marketParamDialog || !elements.marketParamDetails) {
    return Promise.resolve(null);
  }

  if (marketParamResolver) {
    marketParamResolver(null);
    marketParamResolver = null;
  }

  setNodeText(elements.marketParamTitle, title || "参数设置");
  setNodeText(elements.marketParamHint, hint || "请设置参数后保存。");
  elements.marketParamDetails.innerHTML = "";
  const context = setupForm(elements.marketParamDetails);

  marketParamSubmitHandler = () => {
    try {
      const value = resolveValue(context);
      if (value === undefined) {
        return;
      }
      closeMarketParamDialog(value);
    } catch (error) {
      notify(error?.message || "参数校验失败，请检查输入后重试。", "error");
    }
  };

  setNodeText(elements.marketParamSaveBtn, confirmText || "保存参数");
  elements.marketParamDialog.classList.add("show");
  elements.marketParamDialog.setAttribute("aria-hidden", "false");

  return new Promise((resolve) => {
    marketParamResolver = resolve;
  });
}

function buildParamTabContainers(host) {
  const tabRow = createEl("div", "dialog-param-tabs");
  const basicTabBtn = createEl("button", "dialog-param-tab is-active", "基础参数");
  basicTabBtn.type = "button";
  const advancedTabBtn = createEl("button", "dialog-param-tab", "高级参数");
  advancedTabBtn.type = "button";
  tabRow.appendChild(basicTabBtn);
  tabRow.appendChild(advancedTabBtn);
  host.appendChild(tabRow);

  const basicPanel = createEl("div", "dialog-param-panel");
  const advancedPanel = createEl("div", "dialog-param-panel");
  advancedPanel.style.display = "none";

  const advancedDetails = createEl("details", "dialog-advanced-details");
  advancedDetails.open = true;
  const advancedSummary = createEl("summary", "", "高级参数（查看文档 | 谨慎修改）");
  advancedDetails.appendChild(advancedSummary);
  advancedDetails.appendChild(createEl("p", "field-hint", "留空将自动回退到默认值。"));
  const advancedParamHost = createEl("div", "dialog-algo-params");
  advancedDetails.appendChild(advancedParamHost);
  advancedPanel.appendChild(advancedDetails);

  host.appendChild(basicPanel);
  host.appendChild(advancedPanel);

  const switchTab = (target) => {
    const showAdvanced = target === "advanced";
    basicTabBtn.classList.toggle("is-active", !showAdvanced);
    advancedTabBtn.classList.toggle("is-active", showAdvanced);
    basicPanel.style.display = showAdvanced ? "none" : "grid";
    advancedPanel.style.display = showAdvanced ? "grid" : "none";
  };

  basicTabBtn.addEventListener("click", () => switchTab("basic"));
  advancedTabBtn.addEventListener("click", () => switchTab("advanced"));

  return { basicPanel, advancedParamHost, advancedDetails, switchTab };
}

async function openDynamicParamDialog(state, fallbackBasePrice) {
  await ensureMarketAlgorithmGlossary();
  const catalog = getAlgorithmCatalog("dynamic");
  const fallbackAlgorithm = catalog[0]?.id || "LINEAR_DEMAND_V1";
  return openMarketParamDialog({
    title: "动态定价参数",
    hint: "参数与说明来自外部配置文件，修改后可扩展新算法。",
    confirmText: "保存动态参数",
    setupForm: (host) => {
      const tabLayout = buildParamTabContainers(host);
      const basicHost = tabLayout.basicPanel;

      const algorithmRow = createEl("div", "dialog-inline-config");
      const algorithmSelect = document.createElement("select");
      for (const item of catalog) {
        const option = document.createElement("option");
        option.value = item.id;
        option.textContent = item.label || item.id;
        algorithmSelect.appendChild(option);
      }
      algorithmSelect.value = String(state.dynamicAlgorithm || fallbackAlgorithm).toUpperCase();
      if (!algorithmSelect.value) {
        algorithmSelect.value = fallbackAlgorithm;
      }
      const algorithmField = createDialogSelectField("选择动态定价算法", algorithmSelect);
      algorithmRow.appendChild(algorithmField);
      const helpBtn = createEl("button", "btn-tonal", "文档");
      helpBtn.type = "button";
      helpBtn.title = "查看算法帮助";
      helpBtn.addEventListener("click", () => openAlgorithmHelpPage("dynamic", algorithmSelect.value));
      algorithmRow.appendChild(helpBtn);
      basicHost.appendChild(algorithmRow);

      const summary = createEl("p", "field-hint", "");
      basicHost.appendChild(summary);

      const baseInput = document.createElement("input");
      baseInput.type = "number";
      baseInput.min = "1";
      baseInput.step = "1";
      const baseValue = state.dynamicBasePrice ?? fallbackBasePrice;
      baseInput.value = Number.isFinite(Number(baseValue)) ? String(baseValue) : "";
      const baseField = createDialogSelectField("动态基准价", baseInput);
      basicHost.appendChild(baseField);

      const floorInput = document.createElement("input");
      floorInput.type = "number";
      floorInput.min = "1";
      floorInput.step = "1";
      const normalizedFloorValue = Number(state.dynamicFloorPrice);
      floorInput.value = Number.isFinite(normalizedFloorValue) && normalizedFloorValue > 0
        ? String(Math.floor(normalizedFloorValue))
        : "";
      const floorField = createDialogSelectField("地板价（可选）", floorInput);
      basicHost.appendChild(floorField);

      const capInput = document.createElement("input");
      capInput.type = "number";
      capInput.min = "1";
      capInput.step = "1";
      const normalizedCapValue = Number(state.dynamicCapPrice);
      capInput.value = Number.isFinite(normalizedCapValue) && normalizedCapValue > 0
        ? String(Math.floor(normalizedCapValue))
        : "";
      const capField = createDialogSelectField("封顶价（可选）", capInput);
      basicHost.appendChild(capField);

      const stepInput = document.createElement("input");
      stepInput.type = "number";
      stepInput.min = "1";
      stepInput.step = "1";
      stepInput.value = Number.isFinite(Number(state.dynamicPriceStep)) ? String(state.dynamicPriceStep) : "1";
      const stepField = createDialogSelectField("价格波动系数（步长）", stepInput);
      basicHost.appendChild(stepField);

      const paramHost = createEl("div", "dialog-algo-params");
      basicHost.appendChild(paramHost);
      const advancedParamHost = tabLayout.advancedParamHost;

      const initialParamValues = parseAlgorithmParamsJson(state.dynamicParamsJson);
      let basicParamEntries = [];
      let advancedParamEntries = [];
      const renderParams = () => {
        const definition = getAlgorithmDefinition("dynamic", algorithmSelect.value);
        summary.textContent = definition?.summary || "";
        const values = definition && String(definition.id || "").toUpperCase() === String(state.dynamicAlgorithm || "").toUpperCase()
          ? initialParamValues
          : {};
        basicParamEntries = renderAlgorithmParamEditors(
          paramHost,
          definition?.params || [],
          values,
          { advancedOnly: false, emptyMessage: "" }
        );
        advancedParamEntries = renderAlgorithmParamEditors(
          advancedParamHost,
          definition?.params || [],
          values,
          { advancedOnly: true, emptyMessage: "当前算法暂无高级参数。" }
        );
      };
      algorithmSelect.addEventListener("change", renderParams);
      renderParams();

      return {
        algorithmSelect,
        baseInput,
        floorInput,
        capInput,
        stepInput,
        getParamEntries: () => basicParamEntries.concat(advancedParamEntries),
      };
    },
    resolveValue: ({ algorithmSelect, baseInput, floorInput, capInput, stepInput, getParamEntries }) => {
      const base = Number(baseInput.value.trim());
      if (!Number.isFinite(base) || base <= 0) {
        baseInput.focus();
        return undefined;
      }
      const floorRaw = floorInput.value.trim();
      const capRaw = capInput.value.trim();
      const step = Number(stepInput.value.trim());
      if (!Number.isFinite(step) || step <= 0) {
        stepInput.focus();
        return undefined;
      }

      let floor = null;
      let cap = null;
      if (floorRaw) {
        floor = Number(floorRaw);
        if (!Number.isFinite(floor)) {
          floorInput.focus();
          return undefined;
        }
        if (floor <= 0) {
          floor = null;
        }
      }
      if (capRaw) {
        cap = Number(capRaw);
        if (!Number.isFinite(cap)) {
          capInput.focus();
          return undefined;
        }
        if (cap <= 0) {
          cap = null;
        }
      }
      if (floor !== null && cap !== null && floor > cap) {
        floorInput.focus();
        return undefined;
      }

      const paramPayload = collectAlgorithmParamValues(getParamEntries());
      if (paramPayload === undefined) {
        return undefined;
      }

      return {
        dynamicAlgorithm: algorithmSelect.value,
        dynamicParamsJson: Object.keys(paramPayload).length > 0 ? JSON.stringify(paramPayload) : null,
        dynamicBasePrice: Math.floor(base),
        dynamicFloorPrice: floor === null ? null : Math.floor(floor),
        dynamicCapPrice: cap === null ? null : Math.floor(cap),
        dynamicPriceStep: Math.floor(step),
      };
    },
  });
}

async function openAuctionParamDialog(state, fallbackPrice) {
  await ensureMarketAlgorithmGlossary();
  const catalog = getAlgorithmCatalog("auction");
  const fallbackAlgorithm = catalog[0]?.id || "ENGLISH_AUCTION_V1";
  return openMarketParamDialog({
    title: "拍卖竞价参数",
    hint: "根据算法类型展示对应参数；帮助页可查看详细说明。",
    confirmText: "保存拍卖参数",
    setupForm: (host) => {
      const tabLayout = buildParamTabContainers(host);
      const basicHost = tabLayout.basicPanel;

      const algorithmRow = createEl("div", "dialog-inline-config");
      const algorithmSelect = document.createElement("select");
      for (const item of catalog) {
        const option = document.createElement("option");
        option.value = item.id;
        option.textContent = item.label || item.id;
        algorithmSelect.appendChild(option);
      }
      algorithmSelect.value = String(state.auctionAlgorithm || fallbackAlgorithm).toUpperCase();
      if (!algorithmSelect.value) {
        algorithmSelect.value = fallbackAlgorithm;
      }
      const algorithmField = createDialogSelectField("选择拍卖竞价算法", algorithmSelect);
      algorithmRow.appendChild(algorithmField);
      const helpBtn = createEl("button", "btn-tonal", "文档");
      helpBtn.type = "button";
      helpBtn.title = "查看算法帮助";
      helpBtn.addEventListener("click", () => openAlgorithmHelpPage("auction", algorithmSelect.value));
      algorithmRow.appendChild(helpBtn);
      basicHost.appendChild(algorithmRow);

      const summary = createEl("p", "field-hint", "");
      basicHost.appendChild(summary);

      const startInput = document.createElement("input");
      startInput.type = "number";
      startInput.min = "1";
      startInput.step = "1";
      const startValue = state.auctionStartPrice ?? fallbackPrice;
      startInput.value = Number.isFinite(Number(startValue)) ? String(startValue) : "";
      const startField = createDialogSelectField("起拍价", startInput);
      basicHost.appendChild(startField);

      const incrementInput = document.createElement("input");
      incrementInput.type = "number";
      incrementInput.min = "1";
      incrementInput.step = "1";
      incrementInput.value = Number.isFinite(Number(state.auctionMinIncrement))
        ? String(state.auctionMinIncrement)
        : "1";
      const incrementField = createDialogSelectField("最小加价幅度", incrementInput);
      basicHost.appendChild(incrementField);

      const endInput = document.createElement("input");
      endInput.type = "datetime-local";
      endInput.value = toDateTimeLocalValue(state.auctionEndAt);
      const endField = createDialogSelectField("拍卖结束时间", endInput);
      basicHost.appendChild(endField);

      const paramHost = createEl("div", "dialog-algo-params");
      basicHost.appendChild(paramHost);
      const advancedParamHost = tabLayout.advancedParamHost;

      const initialParamValues = parseAlgorithmParamsJson(state.auctionParamsJson);
      let basicParamEntries = [];
      let advancedParamEntries = [];
      let currentDefinition = null;
      const renderByAlgorithm = () => {
        currentDefinition = getAlgorithmDefinition("auction", algorithmSelect.value);
        summary.textContent = currentDefinition?.summary || "";
        incrementField.style.display = currentDefinition?.requiresMinIncrement ? "grid" : "none";
        endField.style.display = currentDefinition?.requiresEndAt ? "grid" : "none";
        const values = currentDefinition && String(currentDefinition.id || "").toUpperCase() === String(state.auctionAlgorithm || "").toUpperCase()
          ? initialParamValues
          : {};
        basicParamEntries = renderAlgorithmParamEditors(
          paramHost,
          currentDefinition?.params || [],
          values,
          { advancedOnly: false, emptyMessage: "" }
        );
        advancedParamEntries = renderAlgorithmParamEditors(
          advancedParamHost,
          currentDefinition?.params || [],
          values,
          { advancedOnly: true, emptyMessage: "当前算法暂无高级参数。" }
        );
      };
      algorithmSelect.addEventListener("change", renderByAlgorithm);
      renderByAlgorithm();

      return {
        algorithmSelect,
        startInput,
        incrementInput,
        endInput,
        getCurrentDefinition: () => currentDefinition,
        getParamEntries: () => basicParamEntries.concat(advancedParamEntries),
      };
    },
    resolveValue: ({ algorithmSelect, startInput, incrementInput, endInput, getCurrentDefinition, getParamEntries }) => {
      const start = Number(startInput.value.trim());
      if (!Number.isFinite(start) || start <= 0) {
        startInput.focus();
        return undefined;
      }

      const definition = getCurrentDefinition() || getAlgorithmDefinition("auction", algorithmSelect.value);
      let incrementValue = null;
      if (definition?.requiresMinIncrement) {
        const increment = Number(incrementInput.value.trim());
        if (!Number.isFinite(increment) || increment <= 0) {
          incrementInput.focus();
          return undefined;
        }
        incrementValue = Math.floor(increment);
      }

      let auctionEndAt = null;
      if (definition?.requiresEndAt) {
        const endRaw = endInput.value.trim();
        if (!endRaw) {
          endInput.focus();
          return undefined;
        }
        const endDate = new Date(endRaw);
        if (!Number.isFinite(endDate.getTime())) {
          endInput.focus();
          return undefined;
        }
        auctionEndAt = endDate.toISOString();
      }

      const paramPayload = collectAlgorithmParamValues(getParamEntries());
      if (paramPayload === undefined) {
        return undefined;
      }

      return {
        auctionAlgorithm: algorithmSelect.value,
        auctionParamsJson: Object.keys(paramPayload).length > 0 ? JSON.stringify(paramPayload) : null,
        auctionStartPrice: Math.floor(start),
        auctionMinIncrement: incrementValue,
        auctionEndAt,
      };
    },
  });
}

async function openListingVisualDialog({
  currentItemMaterial,
  currentFallbackTitle,
  currentDisplayNameOverride,
  currentDisplayMaterial,
  currentDisplayIconPath,
  pendingDisplayIconFile,
  pendingDisplayIconPreviewUrl,
  visualPermission,
}) {
  const originalDisplayIconPath = String(currentDisplayIconPath || "").trim() || null;
  const normalizedPermission = {
    customIconAllowed: visualPermission?.customIconAllowed !== false,
    customNameAllowed: visualPermission?.customNameAllowed !== false,
    customUploadAllowed: visualPermission?.customUploadAllowed !== false,
  };
  return openMarketParamDialog({
    title: "展示设置",
    hint: "(仅对当前设置商品生效)展示材质：输入物品id可以使用该物品的图标; 展示图标：上传图片后会覆盖材质图标进行展示，支持png/jpg/jpeg/webp/gif等常见格式，建议尺寸不超过512x512像素。",
    confirmText: "保存展示设置",
    setupForm: (host) => {
      const dialogDraft = {
        displayIconPath: String(currentDisplayIconPath || "").trim() || null,
        pendingDisplayIconFile: pendingDisplayIconFile || null,
        pendingDisplayIconPreviewUrl: String(pendingDisplayIconPreviewUrl || ""),
      };

      const displayNameInput = document.createElement("input");
      displayNameInput.type = "text";
      displayNameInput.maxLength = 128;
      displayNameInput.placeholder = "留空则跟随默认展示名称";
      displayNameInput.value = currentDisplayNameOverride || "";
      displayNameInput.disabled = !normalizedPermission.customNameAllowed;
      const displayNameField = createDialogSelectField("展示名称", displayNameInput);
      if (!normalizedPermission.customNameAllowed) {
        displayNameField.appendChild(createEl("p", "field-hint", "当前账户没有修改展示名称的权限。"));
      }
      host.appendChild(displayNameField);

      const displayMaterialInput = document.createElement("input");
      displayMaterialInput.type = "text";
      displayMaterialInput.maxLength = 64;
      displayMaterialInput.placeholder = "如 DIAMOND_SWORD，留空则跟随原材质";
      displayMaterialInput.value = currentDisplayMaterial || "";
      displayMaterialInput.disabled = !normalizedPermission.customIconAllowed;
      displayMaterialInput.addEventListener("blur", () => {
        if (displayMaterialInput.disabled) {
          return;
        }
        const normalized = normalizeMaterialKey(displayMaterialInput.value || "");
        if (normalized) {
          displayMaterialInput.value = normalized;
          return;
        }
        if (String(displayMaterialInput.value || "").trim()) {
          notify("展示材质未识别，将按输入值规范化后提交。", "warn");
          displayMaterialInput.value = normalizeMaterialKey(String(displayMaterialInput.value || "").trim());
        }
      });
      const displayMaterialField = createDialogSelectField("展示材质", displayMaterialInput);
      if (!normalizedPermission.customIconAllowed) {
        displayMaterialField.appendChild(createEl("p", "field-hint", "当前账户没有修改展示材质或展示图标的权限。"));
      }
      host.appendChild(displayMaterialField);

      const iconField = createEl("div", "dialog-select-field");
      iconField.appendChild(createEl("span", "dialog-select-label", "展示图标"));
      const iconPreviewWrap = createEl("div", "material-override-preview");
      const iconPreviewImage = document.createElement("img");
      iconPreviewImage.alt = "商品图标预览";
      iconPreviewImage.src = getFallbackTexture();
      const iconPreviewText = document.createElement("div");
      const iconPreviewMeta = createEl("p", "meta", "当前图标预览");
      const iconPreviewLabel = document.createElement("strong");
      const iconStatus = createEl("p", "meta", "当前跟随材质图标。");
      iconPreviewText.appendChild(iconPreviewMeta);
      iconPreviewText.appendChild(iconPreviewLabel);
      iconPreviewText.appendChild(iconStatus);
      iconPreviewWrap.appendChild(iconPreviewImage);
      iconPreviewWrap.appendChild(iconPreviewText);
      iconField.appendChild(iconPreviewWrap);

      const iconFileInput = document.createElement("input");
      iconFileInput.type = "file";
      iconFileInput.accept = ".png,.webp,.jpg,.jpeg,.gif,image/*";
      iconFileInput.disabled =
          !normalizedPermission.customIconAllowed || !normalizedPermission.customUploadAllowed;
      iconField.appendChild(iconFileInput);

      const iconActionRow = createEl("div", "actions compact-actions");
      const iconUploadBtn = createEl("button", "btn-tonal", "上传图片");
      iconUploadBtn.type = "button";
      iconUploadBtn.disabled =
          !normalizedPermission.customIconAllowed || !normalizedPermission.customUploadAllowed;
      const iconClearBtn = createEl("button", "btn-tonal", "清除自定义图标");
      iconClearBtn.type = "button";
      iconClearBtn.disabled = !normalizedPermission.customIconAllowed;
      iconActionRow.appendChild(iconUploadBtn);
      iconActionRow.appendChild(iconClearBtn);
      iconField.appendChild(iconActionRow);
      if (!normalizedPermission.customIconAllowed) {
        iconField.appendChild(createEl("p", "field-hint", "当前账户没有修改展示图标的权限。"));
      } else if (!normalizedPermission.customUploadAllowed) {
        iconField.appendChild(createEl("p", "field-hint", "当前账户可以沿用或清除已有图标，但没有上传新图片的权限。"));
      } else {
        iconField.appendChild(createEl("p", "field-hint", "上传图片会先进入待保存状态，回到主窗口保存修改后才会真正生效。"));
      }
      host.appendChild(iconField);

      const resolveListingPreviewVisual = () => {
        const baseMaterial = normalizeMaterialKey(currentItemMaterial || "") || DEFAULT_TEXTURE_FALLBACK_MATERIAL;
        const fallbackTitle = String(currentFallbackTitle || "").trim()
          || String(currentDisplayNameOverride || "").trim()
          || getLocalizedMaterialName(baseMaterial, { includeGlobalOverride: false });
        return resolveDisplayVisual(
          baseMaterial,
          displayNameInput.value,
          displayMaterialInput.value,
          dialogDraft.displayIconPath,
          fallbackTitle,
          { category: "market" }
        );
      };

      const updateListingIconPreview = (message, tone = null) => {
        const visual = resolveListingPreviewVisual();
        const previewTitle = visual.title || String(currentFallbackTitle || "").trim() || "未命名商品";
        setNodeText(iconPreviewLabel, previewTitle);
        if (dialogDraft.pendingDisplayIconPreviewUrl) {
          iconPreviewImage.src = dialogDraft.pendingDisplayIconPreviewUrl;
        } else {
          iconPreviewImage.src = resolveMaterialIconUrl(visual.forceIconPath)
            || getTextureCandidates(visual.material || DEFAULT_TEXTURE_FALLBACK_MATERIAL, {
              forceIconPath: visual.forceIconPath,
              includeMaterialOverride: visual.includeMaterialOverride,
            })[0]
            || getFallbackTexture();
        }

        if (message) {
          setMetaText(iconStatus, message, tone || "info");
          return;
        }
        if (dialogDraft.pendingDisplayIconPreviewUrl) {
          setMetaText(iconStatus, "已选择新的自定义图片，回到主窗口保存修改后生效。", "success");
          return;
        }
        if (dialogDraft.displayIconPath) {
          setMetaText(iconStatus, "当前使用已保存的自定义图片。", "info");
          return;
        }
        if (originalDisplayIconPath) {
          setMetaText(iconStatus, "回到主窗口保存修改后将移除当前自定义图标。", "warn");
          return;
        }
        if (normalizeMaterialKey(displayMaterialInput.value || "")) {
          setMetaText(iconStatus, "当前跟随展示材质的图标。", "info");
          return;
        }
        setMetaText(iconStatus, "当前跟随原始材质图标。", "info");
      };

      iconUploadBtn.addEventListener("click", async () => {
        if (iconUploadBtn.disabled) {
          return;
        }
        try {
          iconUploadBtn.disabled = true;
          const file = iconFileInput.files?.[0];
          if (!file) {
            throw new Error("请先选择图标文件。");
          }
          const croppedFile = await cropImageFileToSquarePng(file, 128);
          if (!croppedFile) {
            updateListingIconPreview("已取消裁剪与上传。", "info");
            return;
          }
          dialogDraft.pendingDisplayIconFile = croppedFile;
          dialogDraft.pendingDisplayIconPreviewUrl = await readFileAsDataUrl(croppedFile);
          iconFileInput.value = "";
          updateListingIconPreview("新的自定义图片已加入待保存队列。", "success");
        } catch (error) {
          const message = resolveErrorMessage(error, "market_icon_upload");
          updateListingIconPreview(formatAppTemplate("iconProcessFailed", { message }), "error");
          notify(formatAppTemplate("iconProcessFailed", { message }), "error");
        } finally {
          iconUploadBtn.disabled = false;
        }
      });
      iconClearBtn.addEventListener("click", () => {
        if (iconClearBtn.disabled) {
          return;
        }
        dialogDraft.pendingDisplayIconFile = null;
        dialogDraft.pendingDisplayIconPreviewUrl = "";
        dialogDraft.displayIconPath = null;
        iconFileInput.value = "";
        updateListingIconPreview();
      });
      displayNameInput.addEventListener("input", () => updateListingIconPreview());
      displayMaterialInput.addEventListener("input", () => updateListingIconPreview());
      updateListingIconPreview();

      return { displayNameInput, displayMaterialInput, dialogDraft };
    },
    resolveValue: (context) => ({
      displayNameOverride: context.displayNameInput.value.trim() || null,
      displayMaterial: normalizeMaterialKey(context.displayMaterialInput.value || "") || null,
      displayIconPath: context.dialogDraft.displayIconPath,
      pendingDisplayIconFile: context.dialogDraft.pendingDisplayIconFile,
      pendingDisplayIconPreviewUrl: context.dialogDraft.pendingDisplayIconPreviewUrl,
    }),
  });
}

async function openListingEditDialog({
  listingId,
  currentPrice,
  currency,
  currentRemark,
  sourceMode,
  currentSupplyBatchSize,
  currentSupplyMaxStock,
  tradeMode,
  currentDynamicPricingEnabled,
  currentDynamicAlgorithm,
  currentDynamicParamsJson,
  currentDynamicBasePrice,
  currentDynamicFloorPrice,
  currentDynamicCapPrice,
  currentDynamicPriceStep,
  currentAuctionAlgorithm,
  currentAuctionParamsJson,
  currentAuctionStartPrice,
  currentAuctionMinIncrement,
  currentAuctionEndAt,
  currentDisplayNameOverride,
  currentDisplayMaterial,
  currentDisplayIconPath,
  currentItemMaterial,
  currentFallbackTitle,
}) {
  await ensureMarketAlgorithmGlossary();
  const dynamicCatalog = getAlgorithmCatalog("dynamic");
  const auctionCatalog = getAlgorithmCatalog("auction");
  const defaultDynamicAlgorithm = dynamicCatalog[0]?.id || "LINEAR_DEMAND_V1";
  const defaultAuctionAlgorithm = auctionCatalog[0]?.id || "ENGLISH_AUCTION_V1";
  const normalizedTradeMode = String(tradeMode || "DIRECT").toUpperCase();
  const isSupplyListing = String(sourceMode || "").toUpperCase() === "SUPPLY";
  const initialMode = normalizedTradeMode === "AUCTION"
    ? "AUCTION"
    : (currentDynamicPricingEnabled ? "DIRECT_DYNAMIC" : "DIRECT_STATIC");

  if (!elements.confirmDialog) {
    const rawPrice = window.prompt("请输入新的价格", String(currentPrice));
    if (rawPrice === null) {
      return Promise.resolve(null);
    }
    const rawCurrency = window.prompt("请输入币种（SHOP_COIN / GAME_COIN）", String(currency || "GAME_COIN"));
    if (rawCurrency === null) {
      return Promise.resolve(null);
    }
    const rawRemark = window.prompt("请输入备注（留空清空）", currentRemark || "");
    if (rawRemark === null) {
      return Promise.resolve(null);
    }
    const rawDisplayName = window.prompt("请输入展示名称（留空则跟随默认）", currentDisplayNameOverride || "");
    if (rawDisplayName === null) {
      return Promise.resolve(null);
    }
    const rawDisplayMaterial = window.prompt("请输入展示材质（留空则跟随原材质）", currentDisplayMaterial || "");
    if (rawDisplayMaterial === null) {
      return Promise.resolve(null);
    }
    const rawDisplayIconPath = window.prompt("请输入展示图标路径（留空则跟随材质）", currentDisplayIconPath || "");
    if (rawDisplayIconPath === null) {
      return Promise.resolve(null);
    }
    return Promise.resolve({
      price: Math.floor(Number(rawPrice)),
      currency: String(rawCurrency || "GAME_COIN").trim().toUpperCase(),
      remark: rawRemark.trim() || null,
      displayNameOverride: rawDisplayName.trim() || null,
      displayMaterial: normalizeMaterialKey(rawDisplayMaterial) || null,
      displayIconPath: String(rawDisplayIconPath || "").trim() || null,
      pendingDisplayIconFile: null,
      supplyBatchSize: null,
      supplyMaxStock: null,
      tradeMode: "DIRECT",
      dynamicPricingEnabled: false,
      dynamicAlgorithm: String(currentDynamicAlgorithm || defaultDynamicAlgorithm).toUpperCase(),
      dynamicParamsJson: currentDynamicParamsJson || null,
      dynamicBasePrice: null,
      dynamicFloorPrice: null,
      dynamicCapPrice: null,
      dynamicPriceStep: null,
      auctionAlgorithm: String(currentAuctionAlgorithm || defaultAuctionAlgorithm).toUpperCase(),
      auctionParamsJson: currentAuctionParamsJson || null,
      auctionStartPrice: null,
      auctionMinIncrement: null,
      auctionEndAt: null,
    });
  }

  if (confirmResolver) {
    confirmResolver(null);
    confirmResolver = null;
  }

  const draft = {
    mode: isSupplyListing && initialMode === "AUCTION" ? "DIRECT_STATIC" : initialMode,
    dynamicAlgorithm: String(currentDynamicAlgorithm || defaultDynamicAlgorithm).toUpperCase(),
    dynamicParamsJson: currentDynamicParamsJson || null,
    auctionAlgorithm: String(currentAuctionAlgorithm || defaultAuctionAlgorithm).toUpperCase(),
    auctionParamsJson: currentAuctionParamsJson || null,
    displayNameOverride: String(currentDisplayNameOverride || "").trim() || null,
    displayMaterial: String(currentDisplayMaterial || "").trim() || null,
    displayIconPath: String(currentDisplayIconPath || "").trim() || null,
    pendingDisplayIconFile: null,
    pendingDisplayIconPreviewUrl: "",
    dynamicBasePrice: Number.isFinite(Number(currentDynamicBasePrice)) ? Math.floor(Number(currentDynamicBasePrice)) : Math.floor(Number(currentPrice || 1)),
    dynamicFloorPrice: Number.isFinite(Number(currentDynamicFloorPrice)) && Number(currentDynamicFloorPrice) > 0
      ? Math.floor(Number(currentDynamicFloorPrice))
      : null,
    dynamicCapPrice: Number.isFinite(Number(currentDynamicCapPrice)) && Number(currentDynamicCapPrice) > 0
      ? Math.floor(Number(currentDynamicCapPrice))
      : null,
    dynamicPriceStep: Number.isFinite(Number(currentDynamicPriceStep)) ? Math.floor(Number(currentDynamicPriceStep)) : 1,
    auctionStartPrice: Number.isFinite(Number(currentAuctionStartPrice)) ? Math.floor(Number(currentAuctionStartPrice)) : Math.floor(Number(currentPrice || 1)),
    auctionMinIncrement: Number.isFinite(Number(currentAuctionMinIncrement)) ? Math.floor(Number(currentAuctionMinIncrement)) : 1,
    auctionEndAt: currentAuctionEndAt || null,
  };

  setNodeText(elements.confirmTitle, `修改上架 #${listingId}`);
  setNodeText(elements.confirmMessage, "先选择交易模式，再按需进入参数设置。");
  elements.confirmDetails.innerHTML = "";

  const priceInput = document.createElement("input");
  priceInput.type = "number";
  priceInput.min = "1";
  priceInput.step = "1";
  priceInput.value = String(currentPrice || "");
  const priceField = createDialogSelectField("新价格", priceInput);
  elements.confirmDetails.appendChild(priceField);

  const currencySelect = document.createElement("select");
  ["SHOP_COIN", "GAME_COIN"].forEach((value) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = (CURRENCY_META[value] || { label: value }).label;
    currencySelect.appendChild(option);
  });
  currencySelect.value = currency || "GAME_COIN";
  const currencyField = createDialogSelectField("币种", currencySelect);
  elements.confirmDetails.appendChild(currencyField);

  const remarkInput = document.createElement("textarea");
  remarkInput.rows = 3;
  remarkInput.value = currentRemark || "";
  const remarkField = createDialogSelectField("备注", remarkInput);
  elements.confirmDetails.appendChild(remarkField);

  const iconField = createEl("div", "dialog-select-field");
  iconField.appendChild(createEl("span", "dialog-select-label", "展示设置（仅前端显示）"));
  const iconConfigBtn = createEl("button", "btn-tonal", "编辑展示设置");
  iconConfigBtn.type = "button";
  iconField.appendChild(iconConfigBtn);
  elements.confirmDetails.appendChild(iconField);
  iconConfigBtn.addEventListener("click", async () => {
    const value = await openListingVisualDialog({
      currentItemMaterial,
      currentFallbackTitle,
      currentDisplayNameOverride: draft.displayNameOverride,
      currentDisplayMaterial: draft.displayMaterial,
      currentDisplayIconPath: draft.displayIconPath,
      pendingDisplayIconFile: draft.pendingDisplayIconFile,
      pendingDisplayIconPreviewUrl: draft.pendingDisplayIconPreviewUrl,
      visualPermission: state.visualPermission,
    });
    if (!value) {
      return;
    }
    draft.displayNameOverride = value.displayNameOverride;
    draft.displayMaterial = value.displayMaterial;
    draft.displayIconPath = value.displayIconPath;
    draft.pendingDisplayIconFile = value.pendingDisplayIconFile;
    draft.pendingDisplayIconPreviewUrl = value.pendingDisplayIconPreviewUrl;
  });

  const modeSelect = document.createElement("select");
  [
    { value: "DIRECT_STATIC", label: "一口价" },
    { value: "DIRECT_DYNAMIC", label: "动态价格" },
    { value: "AUCTION", label: "拍卖竞价" },
  ].forEach((item) => {
    const option = document.createElement("option");
    option.value = item.value;
    option.textContent = item.label;
    if (isSupplyListing && item.value === "AUCTION") {
      option.disabled = true;
    }
    modeSelect.appendChild(option);
  });
  modeSelect.value = draft.mode;
  const modeField = createDialogSelectField("交易模式", modeSelect);
  elements.confirmDetails.appendChild(modeField);

  const dynamicConfigRow = createEl("div", "inline-action dialog-inline-config");
  const dynamicAlgoSelect = document.createElement("select");
  dynamicCatalog.forEach((item) => {
    const option = document.createElement("option");
    option.value = item.id;
    option.textContent = item.label || item.id;
    dynamicAlgoSelect.appendChild(option);
  });
  if (!dynamicCatalog.some((item) => item.id === draft.dynamicAlgorithm)) {
    draft.dynamicAlgorithm = defaultDynamicAlgorithm;
  }
  dynamicAlgoSelect.value = draft.dynamicAlgorithm;
  const dynamicAlgoField = createDialogSelectField("选择动态定价算法", dynamicAlgoSelect);
  const dynamicParamBtn = createEl("button", "btn-tonal", "参数设置");
  dynamicParamBtn.type = "button";
  dynamicConfigRow.appendChild(dynamicAlgoField);
  dynamicConfigRow.appendChild(dynamicParamBtn);
  elements.confirmDetails.appendChild(dynamicConfigRow);

  const auctionConfigRow = createEl("div", "inline-action dialog-inline-config");
  const auctionAlgoSelect = document.createElement("select");
  auctionCatalog.forEach((item) => {
    const option = document.createElement("option");
    option.value = item.id;
    option.textContent = item.label || item.id;
    auctionAlgoSelect.appendChild(option);
  });
  if (!auctionCatalog.some((item) => item.id === draft.auctionAlgorithm)) {
    draft.auctionAlgorithm = defaultAuctionAlgorithm;
  }
  auctionAlgoSelect.value = draft.auctionAlgorithm;
  const auctionAlgoField = createDialogSelectField("选择拍卖竞价算法", auctionAlgoSelect);
  const auctionParamBtn = createEl("button", "btn-tonal", "参数设置");
  auctionParamBtn.type = "button";
  auctionConfigRow.appendChild(auctionAlgoField);
  auctionConfigRow.appendChild(auctionParamBtn);
  elements.confirmDetails.appendChild(auctionConfigRow);

  dynamicAlgoSelect.addEventListener("change", () => {
    draft.dynamicAlgorithm = dynamicAlgoSelect.value;
  });
  auctionAlgoSelect.addEventListener("change", () => {
    draft.auctionAlgorithm = auctionAlgoSelect.value;
  });

  dynamicParamBtn.addEventListener("click", async () => {
    const value = await openDynamicParamDialog(draft, Number(priceInput.value || currentPrice || 1));
    if (!value) {
      return;
    }
    draft.dynamicAlgorithm = value.dynamicAlgorithm;
    draft.dynamicParamsJson = value.dynamicParamsJson;
    draft.dynamicBasePrice = value.dynamicBasePrice;
    draft.dynamicFloorPrice = value.dynamicFloorPrice;
    draft.dynamicCapPrice = value.dynamicCapPrice;
    draft.dynamicPriceStep = value.dynamicPriceStep;
    dynamicAlgoSelect.value = draft.dynamicAlgorithm;
  });

  auctionParamBtn.addEventListener("click", async () => {
    const value = await openAuctionParamDialog(draft, Number(priceInput.value || currentPrice || 1));
    if (!value) {
      return;
    }
    draft.auctionAlgorithm = value.auctionAlgorithm;
    draft.auctionParamsJson = value.auctionParamsJson;
    draft.auctionStartPrice = value.auctionStartPrice;
    draft.auctionMinIncrement = value.auctionMinIncrement;
    draft.auctionEndAt = value.auctionEndAt;
    auctionAlgoSelect.value = draft.auctionAlgorithm;
  });

  const refreshModeRows = () => {
    draft.mode = modeSelect.value;
    dynamicConfigRow.style.display = draft.mode === "DIRECT_DYNAMIC" ? "grid" : "none";
    auctionConfigRow.style.display = draft.mode === "AUCTION" ? "grid" : "none";
  };
  modeSelect.addEventListener("change", refreshModeRows);
  refreshModeRows();

  const isSupply = isSupplyListing;
  let supplyBatchInput = null;
  let supplyMaxInput = null;
  if (isSupply) {
    supplyBatchInput = document.createElement("input");
    supplyBatchInput.type = "number";
    supplyBatchInput.min = "1";
    supplyBatchInput.step = "1";
    supplyBatchInput.value = String(currentSupplyBatchSize || "");
    const batchField = createDialogSelectField("单次提取量", supplyBatchInput);
    elements.confirmDetails.appendChild(batchField);

    supplyMaxInput = document.createElement("input");
    supplyMaxInput.type = "number";
    supplyMaxInput.min = "1";
    supplyMaxInput.step = "1";
    supplyMaxInput.value = String(currentSupplyMaxStock || "");
    const maxField = createDialogSelectField("中转上限", supplyMaxInput);
    elements.confirmDetails.appendChild(maxField);
  }

  confirmSubmitHandler = () => {
    const price = Number(priceInput.value.trim());
    if (!Number.isFinite(price) || price <= 0) {
      priceInput.focus();
      return;
    }
    const batchSize = supplyBatchInput ? Number(supplyBatchInput.value.trim()) : null;
    const maxStock = supplyMaxInput ? Number(supplyMaxInput.value.trim()) : null;
    if (supplyBatchInput && (!Number.isFinite(batchSize) || batchSize <= 0)) {
      supplyBatchInput.focus();
      return;
    }
    if (supplyMaxInput && (!Number.isFinite(maxStock) || maxStock <= 0)) {
      supplyMaxInput.focus();
      return;
    }

    let mappedTradeMode = "DIRECT";
    let dynamicPricingEnabled = false;
    let dynamicAlgorithm = null;
    let dynamicParamsJson = null;
    let dynamicBasePrice = null;
    let dynamicFloorPrice = null;
    let dynamicCapPrice = null;
    let dynamicPriceStep = null;
    let auctionAlgorithm = null;
    let auctionParamsJson = null;
    let auctionStartPrice = null;
    let auctionMinIncrement = null;
    let auctionEndAt = null;

    if (draft.mode === "DIRECT_DYNAMIC") {
      dynamicPricingEnabled = true;
      dynamicAlgorithm = String(draft.dynamicAlgorithm || defaultDynamicAlgorithm).toUpperCase();
      const dynamicDefinition = getAlgorithmDefinition("dynamic", dynamicAlgorithm);
      const dynamicParamPayload = parseAlgorithmParamsJson(draft.dynamicParamsJson);
      if (dynamicDefinition?.params) {
        for (const schema of dynamicDefinition.params) {
          if (
            !Object.prototype.hasOwnProperty.call(dynamicParamPayload, schema.key)
            && schema.defaultValue !== undefined
            && schema.defaultValue !== null
          ) {
            dynamicParamPayload[schema.key] = schema.defaultValue;
          }
        }
      }
      dynamicParamsJson = Object.keys(dynamicParamPayload).length > 0 ? JSON.stringify(dynamicParamPayload) : null;
      const base = Number(draft.dynamicBasePrice ?? price);
      const step = Number(draft.dynamicPriceStep ?? 1);
      if (!Number.isFinite(base) || base <= 0) {
        notify("动态定价参数无效：基准价必须大于 0。", "warn");
        return;
      }
      if (!Number.isFinite(step) || step <= 0) {
        notify("动态定价参数无效：波动系数必须大于 0。", "warn");
        return;
      }
      const floor = draft.dynamicFloorPrice == null ? null : Number(draft.dynamicFloorPrice);
      const cap = draft.dynamicCapPrice == null ? null : Number(draft.dynamicCapPrice);
      if (floor != null && (!Number.isFinite(floor) || floor <= 0)) {
        notify("动态定价参数无效：地板价必须大于 0。", "warn");
        return;
      }
      if (cap != null && (!Number.isFinite(cap) || cap <= 0)) {
        notify("动态定价参数无效：封顶价必须大于 0。", "warn");
        return;
      }
      if (floor != null && cap != null && floor > cap) {
        notify("动态定价参数无效：地板价不能高于封顶价。", "warn");
        return;
      }
      dynamicBasePrice = Math.floor(base);
      dynamicFloorPrice = floor == null ? null : Math.floor(floor);
      dynamicCapPrice = cap == null ? null : Math.floor(cap);
      dynamicPriceStep = Math.floor(step);
    } else if (draft.mode === "AUCTION") {
      mappedTradeMode = "AUCTION";
      auctionAlgorithm = String(draft.auctionAlgorithm || defaultAuctionAlgorithm).toUpperCase();
      const auctionParamPayload = parseAlgorithmParamsJson(draft.auctionParamsJson);
      const start = Number(draft.auctionStartPrice ?? price);
      if (!Number.isFinite(start) || start <= 0) {
        notify("拍卖参数无效：起拍价必须大于 0。", "warn");
        return;
      }

      const definition = getAlgorithmDefinition("auction", auctionAlgorithm);
      if (definition?.params) {
        for (const schema of definition.params) {
          if (
            !Object.prototype.hasOwnProperty.call(auctionParamPayload, schema.key)
            && schema.defaultValue !== undefined
            && schema.defaultValue !== null
          ) {
            auctionParamPayload[schema.key] = schema.defaultValue;
          }
        }
      }
      auctionParamsJson = Object.keys(auctionParamPayload).length > 0 ? JSON.stringify(auctionParamPayload) : null;
      if (definition?.requiresMinIncrement) {
        const increment = Number(draft.auctionMinIncrement ?? 1);
        if (!Number.isFinite(increment) || increment <= 0) {
          notify("拍卖参数无效：最小加价幅度必须大于 0。", "warn");
          return;
        }
        auctionMinIncrement = Math.floor(increment);
      }
      if (definition?.requiresEndAt) {
        if (!draft.auctionEndAt) {
          notify("拍卖参数无效：请设置结束时间。", "warn");
          return;
        }
        const endTimestamp = Date.parse(draft.auctionEndAt);
        if (!Number.isFinite(endTimestamp)) {
          notify("拍卖参数无效：结束时间格式不正确。", "warn");
          return;
        }
        auctionEndAt = new Date(endTimestamp).toISOString();
      }
      auctionStartPrice = Math.floor(start);
    }

    closeConfirmDialog({
      price: Math.floor(price),
      currency: currencySelect.value,
      remark: remarkInput.value.trim() || null,
      displayNameOverride: draft.displayNameOverride,
      displayMaterial: normalizeMaterialKey(draft.displayMaterial || "") || null,
      displayIconPath: draft.displayIconPath,
      pendingDisplayIconFile: draft.pendingDisplayIconFile,
      supplyBatchSize: supplyBatchInput ? Math.floor(batchSize) : null,
      supplyMaxStock: supplyMaxInput ? Math.floor(maxStock) : null,
      tradeMode: mappedTradeMode,
      dynamicPricingEnabled,
      dynamicAlgorithm,
      dynamicParamsJson,
      dynamicBasePrice,
      dynamicFloorPrice,
      dynamicCapPrice,
      dynamicPriceStep,
      auctionAlgorithm,
      auctionParamsJson,
      auctionStartPrice,
      auctionMinIncrement,
      auctionEndAt,
    });
  };

  elements.confirmOkBtn.disabled = false;
  setNodeText(elements.confirmOkBtn, "保存修改");
  elements.confirmDialog.classList.add("show");
  elements.confirmDialog.setAttribute("aria-hidden", "false");
  priceInput.focus();

  return new Promise((resolve) => {
    confirmResolver = resolve;
  });
}

let priceResolver = null;

function openPriceDialog({ listingId, currentPrice, currency }) {
  if (!elements.priceDialog) {
    const raw = window.prompt(
      `请输入新的价格（当前 ${formatCurrency(currentPrice, currency)}）`,
      String(currentPrice)
    );
    if (raw === null) {
      return Promise.resolve(null);
    }
    const parsed = Number(String(raw).trim());
    if (!Number.isFinite(parsed) || parsed <= 0) {
      return Promise.resolve(null);
    }
    return Promise.resolve(Math.floor(parsed));
  }

  if (priceResolver) {
    priceResolver(null);
    priceResolver = null;
  }

  const currencyMeta = CURRENCY_META[currency] || { short: String(currency || "--") };
  setNodeText(elements.priceDialogTitle, `修改价格 #${listingId}`);
  setNodeText(elements.priceDialogHint, "价格修改后立即生效，请谨慎操作。");
  if (elements.priceDialogBadge) {
    elements.priceDialogBadge.textContent = `#${listingId}`;
  }
  if (elements.priceDialogCurrent) {
    elements.priceDialogCurrent.textContent = formatCurrency(currentPrice, currency);
  }
  if (elements.priceDialogCurrency) {
    elements.priceDialogCurrency.textContent = currencyMeta.short;
  }
  if (elements.priceDialogPrefix) {
    elements.priceDialogPrefix.textContent = currencyMeta.short;
  }
  elements.priceDialogInput.value = String(currentPrice || "");
  elements.priceDialogError.textContent = "";
  elements.priceDialog.classList.add("show");
  elements.priceDialog.setAttribute("aria-hidden", "false");
  elements.priceDialogInput.focus();

  return new Promise((resolve) => {
    priceResolver = resolve;
  });
}

function closePriceDialog(result) {
  if (!elements.priceDialog) {
    return;
  }
  elements.priceDialog.classList.remove("show");
  elements.priceDialog.setAttribute("aria-hidden", "true");
  if (priceResolver) {
    priceResolver(result);
    priceResolver = null;
  }
}

function submitPriceDialog() {
  const raw = elements.priceDialogInput.value.trim();
  const price = Number(raw);
  if (!Number.isFinite(price) || price <= 0) {
    setNodeText(elements.priceDialogError, "价格必须是大于 0 的数字。");
    return;
  }
  elements.priceDialogError.textContent = "";
  closePriceDialog(Math.floor(price));
}

function escapeHtml(raw) {
  return String(raw || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function setStatus(text, stateName) {
  elements.statusChip.textContent = localizeDisplayText(text);
  elements.statusChip.dataset.state = stateName;
}

function updateAccountBackButtonVisibility() {
  if (!elements.headerAccountBackBtn) {
    return;
  }
  const shouldShow = !!state.token && ACCOUNT_CHILD_TABS.has(state.activeTab);
  elements.headerAccountBackBtn.classList.toggle("hidden", !shouldShow);
  if (
    elements.headerControls
    && typeof elements.headerControls.refreshOverflowMenuLayout === "function"
  ) {
    elements.headerControls.refreshOverflowMenuLayout();
  }
}

function updateNotificationBadge() {
  if (!elements.notificationsBadge) {
    return;
  }
  const unreadCount = Math.max(0, Number(state.unreadNotificationCount || 0));
  const shouldShow = !!state.token && unreadCount > 0;
  elements.notificationsBadge.textContent = unreadCount > 99 ? "99+" : String(unreadCount);
  elements.notificationsBadge.classList.toggle("hidden", !shouldShow);
}

const PATH_TAB_MAP = {
  "/": "auth",
  "/account": "auth",
  "/b2c": "shop",
  "/c2c": "market",
  "/auction": "auction",
  "/leaderboard": "leaderboard"
};

const TAB_PATH_MAP = {
  "auth": "/account",
  "shop": "/b2c",
  "market": "/c2c",
  "auction": "/auction",
  "leaderboard": "/leaderboard"
};

function switchTab(tabName, skipHistory = false) {
  const panelTab = tabName === "auction" ? "market" : tabName;
  const activeTopTab = ACCOUNT_CHILD_TABS.has(tabName) ? "auth" : tabName;

  state.activeTab = tabName;

  if (!skipHistory && TAB_PATH_MAP[activeTopTab]) {
    const newPath = TAB_PATH_MAP[activeTopTab];
    if (window.location.pathname !== newPath) {
      window.history.pushState({ tab: tabName }, "", newPath);
    }
  }

  tabs.forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.tabTarget === activeTopTab);
  });

  panels.forEach((panel) => {
    panel.classList.toggle("active", panel.dataset.tabPanel === panelTab);
  });
  updateAccountBackButtonVisibility();

  if (tabName === "wallet") {
    if (state.token) {
      refreshWallet().then(() => loadWalletLedger()).catch((error) => {
        const message = resolveErrorMessage(error, "wallet_refresh");
        setMetaText(elements.walletView, formatAppTemplate("walletRefreshFailed", { message }), "error");
      });
    } else {
      setMetaText(elements.walletView, "请先登录后查看钱包。", "warn");
    }
  }
  if (tabName === "shop") {
    loadProducts();
  }
  if (tabName === "orders") {
    if (state.token) {
      loadOrders();
    } else {
      setMetaText(elements.orderView, "请先登录后查看订单。", "warn");
    }
  }
  if (tabName === "notifications") {
    if (state.token) {
      loadNotifications();
    } else {
      setMetaText(elements.notificationsView, "请先登录后查看通知。", "warn");
    }
  }
  if (tabName === "market" || tabName === "auction") {
    state.marketTradeScope = getMarketTradeScopeByTab(tabName);
    updateMarketSectionContext();
    loadMarket("public");
  }
  if (tabName === "leaderboard") {
    loadLeaderboardConfig().then(() => loadLeaderboard()).catch((error) => {
      const message = resolveErrorMessage(error, "leaderboard");
      setMetaText(elements.leaderboardView, formatAppTemplate("leaderboardLoadFailed", { message }), "error");
    });
    startLeaderboardRealtime();
  } else {
    stopLeaderboardRealtime();
  }
}

tabs.forEach((tab) => {
  tab.addEventListener("click", () => switchTab(tab.dataset.tabTarget));
});

accountEntryButtons.forEach((button) => {
  button.addEventListener("click", () => {
    const targetTab = button.dataset.accountTab;
    if (!targetTab) {
      return;
    }
    if (!state.token) {
      switchTab("auth");
      notify("请先登录后访问账户功能。", "warn");
      return;
    }
    switchTab(targetTab);
  });
});

accountBackButtons.forEach((button) => {
  button.addEventListener("click", () => switchTab("auth"));
});

if (elements.accountHelpBtns.length > 0) {
  elements.accountHelpBtns.forEach((button) => {
    button.addEventListener("click", () => {
      window.location.href = "/help.html";
    });
  });
}

window.addEventListener("popstate", (event) => {
  if (event.state && event.state.tab) {
    switchTab(event.state.tab, true);
  } else {
    // Fallback if no state
    const path = window.location.pathname;
    const tabName = PATH_TAB_MAP[path] || "auth";
    switchTab(tabName, true);
  }
});

// Initialize routing based on URL
window.addEventListener("load", () => {
  const path = window.location.pathname;
  const tabName = PATH_TAB_MAP[path] || "auth";
  switchTab(tabName, true);
});

async function loadLeaderboardConfig() {
  const payload = await fetch(resolveApiUrl("/api/leaderboard/config"), { method: "GET" }).then((res) => res.json());
  const config = payload.leaderboard || {};
  state.leaderboard.enabled = config.enabled !== false;
  state.leaderboard.showOnlineStatusEnabled = config.showOnlineStatus !== false;
  state.leaderboard.defaultMetric = String(config.defaultMetric || "GAME_COIN").toUpperCase();
  state.leaderboard.defaultOrder = String(config.defaultOrder || "DESC").toUpperCase();

  if (elements.leaderboardTabBtn) {
    elements.leaderboardTabBtn.classList.toggle("hidden", !state.leaderboard.enabled);
  }
  if (elements.leaderboardPanel) {
    elements.leaderboardPanel.classList.toggle("hidden", !state.leaderboard.enabled);
  }

  if (!state.leaderboard.enabled) {
    if (state.activeTab === "leaderboard") {
      switchTab("auth");
    }
    return;
  }

  if (elements.leaderboardMetric) {
    elements.leaderboardMetric.value = state.leaderboard.defaultMetric;
  }
  if (elements.leaderboardOrder) {
    elements.leaderboardOrder.value = state.leaderboard.defaultOrder;
  }
  if (elements.leaderboardShowOnlineToggle) {
    elements.leaderboardShowOnlineToggle.checked = state.leaderboard.showOnlineStatusEnabled;
    elements.leaderboardShowOnlineToggle.disabled = !state.leaderboard.showOnlineStatusEnabled;
  }
}

function formatOnlineMinutes(minutes) {
  const value = Math.max(0, Number(minutes || 0));
  const hours = Math.floor(value / 60);
  const remain = value % 60;
  return `${hours}h ${remain}m`;
}

function leaderboardScoreText(entry, metric) {
  if (metric === "SHOP_COIN") {
    return formatCurrency(entry.shopCoin, "SHOP_COIN");
  }
  if (metric === "ONLINE_TIME") {
    return formatOnlineMinutes(entry.onlineTimeMinutes);
  }
  return formatCurrency(entry.gameCoin, "GAME_COIN");
}

function leaderboardMetricLabel(metric) {
  if (metric === "SHOP_COIN") {
    return CURRENCY_META.SHOP_COIN.label || "SHOP_COIN";
  }
  if (metric === "ONLINE_TIME") {
    return "在线时长";
  }
  return CURRENCY_META.GAME_COIN.label || "GAME_COIN";
}

function leaderboardTrendInfo(userKey, rank) {
  const previous = state.leaderboard.previousRanks[userKey];
  if (!previous) {
    return { text: "NEW", toneClass: "is-trend-new" };
  }
  if (rank < previous) {
    return { text: `↑ ${previous - rank}`, toneClass: "is-trend-up" };
  }
  if (rank > previous) {
    return { text: `↓ ${rank - previous}`, toneClass: "is-trend-down" };
  }
  return { text: "持平", toneClass: "is-trend-stable" };
}

function buildLeaderboardAvatarImage(username) {
  const normalizedName = String(username || "").trim();
  const img = document.createElement("img");
  img.className = "leaderboard-player-avatar";
  img.alt = `${normalizedName || "player"} avatar`;
  img.loading = "lazy";
  img.decoding = "async";
  img.referrerPolicy = "no-referrer";
  img.src = normalizedName
    ? `https://nmsr.nickac.dev/face/${encodeURIComponent(normalizedName)}`
    : getFallbackAvatar();
  img.addEventListener("error", () => {
    img.src = getFallbackAvatar();
  }, { once: true });
  return img;
}

function renderLeaderboard(payload) {
  const rows = Array.isArray(payload.entries) ? payload.entries : [];
  const metric = String(payload.metric || state.leaderboard.defaultMetric || "GAME_COIN").toUpperCase();
  const metricLabel = leaderboardMetricLabel(metric);
  const myRank = payload.myRank ?? null;
  const showOnlineRealtime = !!(elements.leaderboardShowOnlineToggle && elements.leaderboardShowOnlineToggle.checked);
  const shouldRenderOnlineTimeChip = metric === "ONLINE_TIME" || showOnlineRealtime;
  const nextRanks = {};
  elements.leaderboardList.innerHTML = "";

  state.leaderboard.myRank = myRank;
  const rankText = myRank ? `我的名次：#${myRank}` : "我的名次：未上榜";
  setNodeText(elements.leaderboardMyRankView, rankText);

  if (!rows.length) {
    state.leaderboard.previousRanks = nextRanks;
    setMetaText(elements.leaderboardView, "当前没有可显示的排行榜数据。", "warn");
    return;
  }

  rows.forEach((entry, index) => {
    const rank = Number(entry.rank || index + 1);
    const username = String(entry.username || `玩家${rank}`);
    const userKey = entry.userId == null ? `anonymous-${rank}-${username}` : String(entry.userId);
    const trend = leaderboardTrendInfo(userKey, rank);
    nextRanks[userKey] = rank;

    const card = createEl("article", "market-card leaderboard-card");
    card.dataset.userId = userKey;
    card.dataset.rank = String(rank);
    if (myRank && rank === Number(myRank)) {
      card.classList.add("is-me");
    }
    if (rank >= 1 && rank <= 3) {
      card.classList.add(`podium-${rank}`);
    }

    const top = createEl("div", "leaderboard-card-top");

    const rankBox = createEl("div", "leaderboard-rank");
    rankBox.appendChild(createEl("strong", "", `#${rank}`));
    if (rank === 1) {
      rankBox.appendChild(createEl("span", "leaderboard-medal", "冠军"));
    } else if (rank === 2) {
      rankBox.appendChild(createEl("span", "leaderboard-medal", "亚军"));
    } else if (rank === 3) {
      rankBox.appendChild(createEl("span", "leaderboard-medal", "季军"));
    }
    top.appendChild(rankBox);

    const player = createEl("div", "leaderboard-player");
    const avatar = buildLeaderboardAvatarImage(username);
    player.appendChild(avatar);
    const playerText = createEl("div", "leaderboard-player-text");
    playerText.appendChild(createEl("h3", "leaderboard-player-name", username));
    playerText.appendChild(createEl("p", "leaderboard-player-sub", `榜单名次 第 ${rank} 名`));
    player.appendChild(playerText);
    top.appendChild(player);

    const chipRow = createEl("div", "leaderboard-chip-row");
    chipRow.appendChild(createEl("span", `leaderboard-chip ${trend.toneClass}`, `趋势 ${trend.text}`));
    if (showOnlineRealtime) {
      chipRow.appendChild(
        createEl("span", `leaderboard-chip ${entry.online ? "is-online" : "is-offline"}`, entry.online ? "在线" : "离线")
      );
    }
    if (myRank && rank === Number(myRank)) {
      chipRow.appendChild(createEl("span", "leaderboard-chip is-me-chip", "我的位置"));
    }
    top.appendChild(chipRow);

    card.appendChild(top);

    const scoreRow = createEl("div", "leaderboard-score-row");
    scoreRow.appendChild(createEl("span", "leaderboard-score-label", `${metricLabel} 当前值`));
    scoreRow.appendChild(createEl("strong", "leaderboard-score-value", leaderboardScoreText(entry, metric)));

    const metricChips = createEl("div", "leaderboard-metric-chips");
    const chips = [
      ["GAME_COIN", CURRENCY_META.GAME_COIN.label || "GAME_COIN", formatCurrency(entry.gameCoin, "GAME_COIN")],
      ["SHOP_COIN", CURRENCY_META.SHOP_COIN.label || "SHOP_COIN", formatCurrency(entry.shopCoin, "SHOP_COIN")],
    ];
    if (shouldRenderOnlineTimeChip) {
      chips.push(["ONLINE_TIME", "在线时长", formatOnlineMinutes(entry.onlineTimeMinutes)]);
    }
    chips.forEach(([type, label, value]) => {
      const chip = createEl(
        "span",
        `leaderboard-metric-chip${type === metric ? " is-active" : ""}`,
        `${label} ${value}`
      );
      metricChips.appendChild(chip);
    });

    const statsRow = createEl("div", "leaderboard-stats-row");
    statsRow.appendChild(scoreRow);
    statsRow.appendChild(metricChips);
    card.appendChild(statsRow);

    elements.leaderboardList.appendChild(card);
  });

  state.leaderboard.previousRanks = nextRanks;

  let statusText = `已加载 ${rows.length} / ${payload.total || rows.length} 条`;
  if (payload.requestedRange && payload.effectiveRange && payload.requestedRange !== payload.effectiveRange) {
    statusText += "（当前维度仅支持总榜）";
  }
  const refreshedAt = new Date().toLocaleTimeString(I18N ? I18N.getIntlLocale() : "zh-CN", { hour12: false });
  statusText += ` · 刷新于 ${refreshedAt}`;
  setMetaText(elements.leaderboardView, statusText, "info");
}

async function loadLeaderboard() {
  if (!state.leaderboard.enabled || state.leaderboard.busy) {
    return;
  }
  state.leaderboard.busy = true;
  try {
    const metric = elements.leaderboardMetric?.value || state.leaderboard.defaultMetric || "GAME_COIN";
    const order = elements.leaderboardOrder?.value || state.leaderboard.defaultOrder || "DESC";
    const range = elements.leaderboardRange?.value || "TOTAL";
    const showOnline = elements.leaderboardShowOnlineToggle?.checked !== false;
    const query = new URLSearchParams({
      metric,
      order,
      range,
      showOnline: String(showOnline),
      limit: "100",
    });
    const payload = await api(`/api/leaderboard/list?${query.toString()}`, { method: "GET" });
    renderLeaderboard(payload);
  } finally {
    state.leaderboard.busy = false;
  }
}

function locateMyLeaderboardRank() {
  if (!state.leaderboard.myRank) {
    notify("当前未找到你的榜单名次。", "warn");
    return;
  }
  if (!elements.leaderboardList || !elements.leaderboardList.querySelector("[data-rank]")) {
    notify("请先加载榜单后再定位。", "warn");
    return;
  }
  const target = elements.leaderboardList.querySelector(`[data-rank="${state.leaderboard.myRank}"]`);
  if (!target) {
    notify("当前分页未包含你的名次。", "warn");
    return;
  }
  if (state.leaderboard.focusTimer) {
    clearTimeout(state.leaderboard.focusTimer);
    state.leaderboard.focusTimer = null;
  }
  target.classList.remove("is-focus");
  void target.offsetWidth;
  target.classList.add("is-focus");
  state.leaderboard.focusTimer = window.setTimeout(() => {
    target.classList.remove("is-focus");
    state.leaderboard.focusTimer = null;
  }, 1500);
  target.scrollIntoView({ behavior: "smooth", block: "center" });
  notify(`已定位到 #${state.leaderboard.myRank}`, "success");
}

function startLeaderboardRealtime() {
  stopLeaderboardRealtime();
  if (!state.leaderboard.enabled) {
    return;
  }
  state.leaderboard.timer = window.setInterval(() => {
    if (state.activeTab !== "leaderboard") {
      return;
    }
    loadLeaderboard().catch(() => {
      // ignore transient leaderboard refresh failures
    });
  }, 10000);
}

function stopLeaderboardRealtime() {
  if (state.leaderboard.timer) {
    clearInterval(state.leaderboard.timer);
    state.leaderboard.timer = null;
  }
}

function createIdempotencyKey() {
  if (window.crypto && typeof window.crypto.randomUUID === "function") {
    return window.crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const formatNumber = new Intl.NumberFormat(I18N ? I18N.getIntlLocale() : "zh-CN", { maximumFractionDigits: 2 });
const formatRatioNumber = new Intl.NumberFormat(I18N ? I18N.getIntlLocale() : "zh-CN", { maximumFractionDigits: 6 });

function formatAmount(amount) {
  const value = Number(amount);
  if (Number.isNaN(value)) {
    return "0";
  }
  return formatNumber.format(value);
}

function formatCurrency(amount, currency) {
  const meta = CURRENCY_META[currency] || { short: String(currency || "--") };
  return `${meta.short} ${formatAmount(amount)}`;
}

function formatRatioValue(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) {
    return "0";
  }
  return formatRatioNumber.format(number);
}

function normalizeExchangeDirection(rawDirection, fallbackDirection) {
  const source = rawDirection || {};
  const fallback = fallbackDirection || { enabled: false, ratio: 1.0 };
  const enabled = Object.prototype.hasOwnProperty.call(source, "enabled")
    ? !!source.enabled
    : !!fallback.enabled;
  const ratioRaw = Number(source.ratio);
  const ratio = Number.isFinite(ratioRaw) && ratioRaw >= 0 ? ratioRaw : Number(fallback.ratio || 0);
  return {
    enabled,
    ratio: Number.isFinite(ratio) ? ratio : 0,
  };
}

function resolveExchangeDirectionSettings(fromCurrency, toCurrency) {
  if (fromCurrency === "SHOP_COIN" && toCurrency === "GAME_COIN") {
    return state.exchangeSettings.shopToGame;
  }
  if (fromCurrency === "GAME_COIN" && toCurrency === "SHOP_COIN") {
    return state.exchangeSettings.gameToShop;
  }
  return null;
}

function updateExchangeRateHint() {
  if (!elements.exchangeRateHint || !elements.exchangeFrom || !elements.exchangeTo) {
    return;
  }
  const fromCurrency = String(elements.exchangeFrom.value || "").trim();
  const toCurrency = String(elements.exchangeTo.value || "").trim();
  if (!fromCurrency || !toCurrency || fromCurrency === toCurrency) {
    setMetaText(elements.exchangeRateHint, "请选择有效的兑换方向。", "warn");
    return;
  }

  const direction = resolveExchangeDirectionSettings(fromCurrency, toCurrency);
  if (!direction) {
    setMetaText(elements.exchangeRateHint, "请选择有效的兑换方向。", "warn");
    return;
  }

  const fromMeta = CURRENCY_META[fromCurrency] || { short: fromCurrency };
  const toMeta = CURRENCY_META[toCurrency] || { short: toCurrency };
  const ratioText = `1 ${fromMeta.short} = ${formatRatioValue(direction.ratio)} ${toMeta.short}`;
  if (!direction.enabled) {
    setMetaText(elements.exchangeRateHint, `当前方向已关闭：${ratioText}`, "warn");
    return;
  }
  setMetaText(elements.exchangeRateHint, `当前比例：${ratioText}`, "info");
}

function applyExchangeMeta(meta) {
  if (!meta || typeof meta !== "object") {
    return;
  }
  state.exchangeSettings.shopToGame = normalizeExchangeDirection(
    meta.shopToGame,
    state.exchangeSettings.shopToGame
  );
  state.exchangeSettings.gameToShop = normalizeExchangeDirection(
    meta.gameToShop,
    state.exchangeSettings.gameToShop
  );
  state.exchangeMetaLoaded = true;
  updateExchangeRateHint();
}

function calculatePercentAmount(baseAmount, percent) {
  const normalizedBase = Math.max(0, Number(baseAmount || 0));
  const normalizedPercent = Math.max(0, Math.min(100, Number(percent || 0)));
  const raw = normalizedBase * normalizedPercent / 100;
  if (!Number.isFinite(raw) || raw <= 0) {
    return 0;
  }
  return Math.min(Math.floor(raw), normalizedBase);
}

function getWalletBalanceForCurrency(currency) {
  if (currency === "GAME_COIN") {
    return Number(state.walletBalance.gameCoin || 0);
  }
  return Number(state.walletBalance.shopCoin || 0);
}

function defaultDeliveryModeForProduct(product) {
  const type = String(product?.productType || "").toUpperCase();
  if (type === "COMMAND" || type === "POTION_EFFECT") {
    return "CLAIM";
  }
  return "IMMEDIATE";
}

function getInitialHideOwnMarketListings() {
  const saved = window.localStorage.getItem(MARKET_HIDE_OWN_STORAGE_KEY);
  if (saved === "0") {
    return false;
  }
  return true;
}

function resolveOfficialProductStock(product) {
  const totalStock = Number(product?.itemAmount);
  const remainingStock = Number(product?.stockRemaining);
  const hasTrackedStock = Number.isFinite(totalStock) && Number.isFinite(remainingStock);
  const stockMaxQuantity = hasTrackedStock
    ? Math.max(0, Math.floor(remainingStock))
    : Math.max(1, Math.floor(Number(product?.itemAmount || 64)));
  const perUserLimitRaw = Number(product?.perUserLimit);
  const hasPerUserLimit = Number.isFinite(perUserLimitRaw) && perUserLimitRaw > 0;
  const perUserLimit = hasPerUserLimit ? Math.floor(perUserLimitRaw) : null;
  const personalRemainingRaw = Number(product?.personalLimitRemaining);
  const hasPersonalLimitRemaining = hasPerUserLimit && Number.isFinite(personalRemainingRaw);
  const personalLimitRemaining = hasPersonalLimitRemaining
    ? Math.max(0, Math.floor(personalRemainingRaw))
    : null;
  const maxQuantity = hasPersonalLimitRemaining
    ? Math.min(stockMaxQuantity, personalLimitRemaining)
    : stockMaxQuantity;
  return {
    totalStock,
    remainingStock,
    hasTrackedStock,
    stockMaxQuantity,
    hasPerUserLimit,
    perUserLimit,
    personalLimitRemaining,
    hasPersonalLimitRemaining,
    isPersonalLimitReached: hasPersonalLimitRemaining && personalLimitRemaining <= 0,
    maxQuantity,
  };
}

function deliveryModeLabel(mode) {
  const key = String(mode || "").toUpperCase();
  if (key === "CLAIM") {
    return "手动领取";
  }
  return "即时到账";
}

function productTypeLabel(type) {
  const key = String(type || "").toUpperCase();
  if (key === "COMMAND") return getAppPageText("productTypeCommand", "指令");
  if (key === "GIVE_ITEM") return getAppPageText("productTypeGiveItem", "出售物品");
  if (key === "GIVE_CUSTOM_ITEM") return getAppPageText("productTypeGiveCustom", "出售自定义物品");
  if (key === "POTION_EFFECT") return getAppPageText("productTypePotionEffect", "药水效果");
  if (key === "RECYCLE_ITEM") return getAppPageText("productTypeRecycleItem", "回收物品");
  if (key === "RECYCLE_COMMAND_ITEM") return getAppPageText("productTypeRecycleCommand", "回收指令");
  if (key === "RECYCLE_CUSTOM_ITEM") return getAppPageText("productTypeRecycleCustom", "回收自定义物品");
  if (key === "GROUP_BUY_VOUCHER") return getAppPageText("productTypeGroupBuyVoucher", "团购券");
  return key || getAppPageText("unknownTypeLabel", "未知类型");
}

function isRecycleProductType(type) {
  const key = String(type || "").toUpperCase();
  return key === "RECYCLE_ITEM"
    || key === "RECYCLE_COMMAND_ITEM"
    || key === "RECYCLE_CUSTOM_ITEM";
}

function buildOrderDigest(orders) {
  const digest = {};
  (orders || []).forEach((order) => {
    const key = String(order.orderNo || "");
    if (!key) {
      return;
    }
    digest[key] = [
      String(order.status || ""),
      String(order.deliveredAt || ""),
      String(order.refundedAt || ""),
      String(order.groupBuyVoucherStatus || ""),
    ].join("|");
  });
  return digest;
}

function buildListingDigest(listings) {
  const digest = {};
  (listings || []).forEach((listing) => {
    const key = String(listing.id || "");
    if (!key) {
      return;
    }
    digest[key] = [
      String(listing.status || ""),
      String(listing.quantity || ""),
      String(listing.buyerName || ""),
      String(listing.soldAt || ""),
      String(listing.unlistedAt || ""),
    ].join("|");
  });
  return digest;
}

function notifyOrderTransitions(previousDigest, orders) {
  const previous = previousDigest || {};
  const changes = [];
  (orders || []).forEach((order) => {
    const orderNo = String(order.orderNo || "");
    if (!orderNo) {
      return;
    }
    const current = [
      String(order.status || ""),
      String(order.deliveredAt || ""),
      String(order.refundedAt || ""),
      String(order.groupBuyVoucherStatus || ""),
    ].join("|");
    const old = previous[orderNo];
    if (!old) {
      changes.push(`新订单：${orderNo}（${ORDER_STATUS_LABELS[String(order.status || "").toUpperCase()]?.label || order.status || "状态未知"}）`);
      return;
    }
    if (old === current) {
      return;
    }
    const status = String(order.status || "").toUpperCase();
    if (status === "DELIVERED") {
      changes.push(`订单已发放：${orderNo}`);
    } else if (status === "WAIT_CLAIM") {
      changes.push(`订单待领取：${orderNo}（可在游戏内 /ws claim）`);
    } else if (status === "REFUNDED") {
      changes.push(`订单已退款：${orderNo}`);
    } else {
      changes.push(`订单状态更新：${orderNo} -> ${status || "UNKNOWN"}`);
    }
  });
  changes.slice(0, 3).forEach((message) => notify(message, "info"));
}

function notifyListingTransitions(previousDigest, listings) {
  const previous = previousDigest || {};
  const changes = [];
  (listings || []).forEach((listing) => {
    const key = String(listing.id || "");
    if (!key) {
      return;
    }
    const current = [
      String(listing.status || ""),
      String(listing.quantity || ""),
      String(listing.buyerName || ""),
      String(listing.soldAt || ""),
      String(listing.unlistedAt || ""),
    ].join("|");
    const old = previous[key];
    if (!old) {
      return;
    }
    if (old === current) {
      return;
    }
    const oldParts = old.split("|");
    const newStatus = String(listing.status || "").toUpperCase();
    const oldQty = Number(oldParts[1] || listing.quantity || 0);
    const newQty = Number(listing.quantity || 0);
    if (newStatus === "SOLD") {
      changes.push(`上架 #${listing.id} 已售出`);
      return;
    }
    if (newStatus === "UNLISTED") {
      changes.push(`上架 #${listing.id} 已下架，退回处理中`);
      return;
    }
    if (Number.isFinite(oldQty) && Number.isFinite(newQty) && newQty < oldQty) {
      changes.push(`上架 #${listing.id} 发生部分售出：剩余 ${newQty}`);
    }
  });
  changes.slice(0, 3).forEach((message) => notify(message, "info"));
}

function applyCurrencyMeta(meta) {
  if (!meta) {
    return;
  }
  if (meta.shopCoin) {
    CURRENCY_META.SHOP_COIN.label = meta.shopCoin.name || CURRENCY_META.SHOP_COIN.label;
    CURRENCY_META.SHOP_COIN.short = meta.shopCoin.short || CURRENCY_META.SHOP_COIN.short;
  }
  if (meta.gameCoin) {
    CURRENCY_META.GAME_COIN.label = meta.gameCoin.name || CURRENCY_META.GAME_COIN.label;
    CURRENCY_META.GAME_COIN.short = meta.gameCoin.short || CURRENCY_META.GAME_COIN.short;
  }

  const updateSelect = (select) => {
    if (!select) {
      return;
    }
    const options = Array.from(select.options || []);
    options.forEach((option) => {
      if (option.value === "SHOP_COIN") {
        option.textContent = CURRENCY_META.SHOP_COIN.label;
      }
      if (option.value === "GAME_COIN") {
        option.textContent = CURRENCY_META.GAME_COIN.label;
      }
    });
  };
  updateSelect(elements.exchangeFrom);
  updateSelect(elements.exchangeTo);
  updateSelect(elements.marketCurrency);
  updateSelect(elements.leaderboardMetric);

  const applyText = (id, text) => {
    const node = document.getElementById(id);
    if (node) {
      node.textContent = text;
    }
  };

  applyText("walletShopLabel", CURRENCY_META.SHOP_COIN.label);
  applyText("walletGameLabel", CURRENCY_META.GAME_COIN.label);
  applyText("walletDescShopCoin", CURRENCY_META.SHOP_COIN.label);
  applyText("walletDescGameCoin", CURRENCY_META.GAME_COIN.label);
  applyText("exchangeDescShopCoin", CURRENCY_META.SHOP_COIN.label);
  applyText("exchangeDescGameCoin", CURRENCY_META.GAME_COIN.label);

  applyExchangeMeta(meta.exchange);
  applyRechargePaymentMeta(meta.payment, meta.paymentProvider);
  updateExchangeRateHint();

  if (state.activeTab === "leaderboard" && state.leaderboard.enabled) {
    loadLeaderboard().catch(() => {
      // ignore transient refresh failures while syncing currency labels
    });
  }
}

async function loadCurrencyMeta() {
  try {
    const payload = await api("/api/meta/currency", { method: "GET" });
    applyCurrencyMeta(payload);
    if (payload && payload.timeZone) {
      state.timeZone = String(payload.timeZone).trim() || state.timeZone;
    }
    return true;
  } catch (error) {
    // Ignore if metadata endpoint is unavailable.
    return false;
  }
}

function formatWalletInline(shopCoin, gameCoin) {
  return `${formatCurrency(shopCoin, "SHOP_COIN")} | ${formatCurrency(gameCoin, "GAME_COIN")}`;
}

function humanizeLedgerType(bizType, bizId) {
  const normalized = String(bizType || "").toUpperCase();
  if (normalized === "ORDER_DEBIT") return `购买商品 ${bizId || ""}`.trim();
  if (normalized === "ORDER_REFUND") return `订单退款 ${bizId || ""}`.trim();
  if (normalized === "RECYCLE_CREDIT") return `回收入账 ${bizId || ""}`.trim();
  if (normalized === "EXCHANGE_OUT") return "货币兑换转出";
  if (normalized === "EXCHANGE_IN") return "货币兑换转入";
  if (normalized === "MARKET_BUY") return "市场购买";
  if (normalized === "MARKET_SELL") return "市场售出";
  if (normalized === "MARKET_BID_HOLD") return "拍卖出价冻结";
  if (normalized === "MARKET_BID_REFUND") return "拍卖退回";
  if (normalized === "REDEEM") return "兑换码入账";
  if (normalized === "ADMIN_ADJUST") return "管理员调整";
  return normalized || "未知变动";
}

function renderWalletLedger(entries) {
  if (!elements.walletLedgerList) {
    return;
  }
  elements.walletLedgerList.innerHTML = "";
  if (!entries || entries.length === 0) {
    elements.walletLedgerList.appendChild(createEl("div", "empty-state", "暂无最近变动记录。"));
    return;
  }
  for (const entry of entries) {
    const item = createEl("article", "wallet-ledger-item");
    const top = createEl("div", "wallet-ledger-top");
    const currencyLabel = (CURRENCY_META[entry.currency] || { label: entry.currency }).label;
    top.appendChild(createEl("strong", "", humanizeLedgerType(entry.bizType, entry.bizId)));
    const amount = createEl(
      "span",
      `wallet-ledger-amount ${Number(entry.delta || 0) >= 0 ? "positive" : "negative"}`,
      formatCurrency(entry.delta, entry.currency)
    );
    top.appendChild(amount);
    item.appendChild(top);
    item.appendChild(
      createEl(
        "p",
        "wallet-ledger-meta",
        `${formatDateTime(entry.createdAt)} | ${currencyLabel} | ${entry.bizId || "-"}`
      )
    );
    elements.walletLedgerList.appendChild(item);
  }
}

async function loadWalletLedger(options = {}) {
  const announce = !!options.announce;
  ensureToken();
  const payload = await api("/api/wallet/ledger?limit=20", { method: "GET" });
  renderWalletLedger(payload.entries || []);
  setMetaText(elements.walletLedgerView, `最近变动：${(payload.entries || []).length} 条`, "info");
  if (announce) {
    notify(`最近变动已刷新：${(payload.entries || []).length} 条。`, "info");
  }
}

function normalizeRechargeCurrency(value) {
  const normalized = String(value || "").trim().toUpperCase();
  return /^[A-Z]{3,8}$/.test(normalized) ? normalized : "";
}

function normalizeRechargeMethod(value) {
  const normalized = String(value || "").trim().toUpperCase().replace(/-/g, "_");
  return ["ALIPAY", "WECHAT", "PAYPAL", "MERCADOPAGO", "STRIPE", "CUSTOM"].includes(normalized) ? normalized : "";
}

function paymentMethodLabel(method) {
  const labels = APP_UI_TEXT.paymentMethodLabels || {};
  return labels[method] || method;
}

function currentRechargeCurrency() {
  const selected = normalizeRechargeCurrency(elements.rechargePaymentCurrency?.value);
  return selected || state.recharge.currencies[0] || "CNY";
}

function updateRechargeAmountLabel() {
  if (!elements.rechargeAmountLabel) {
    return;
  }
  const base = getAppPageText("rechargeAmountLabel") || "Payment Amount";
  elements.rechargeAmountLabel.textContent = `${base} (${currentRechargeCurrency()})`;
}

function applyRechargePaymentMeta(settings, provider) {
  const configuredCurrencies = Array.isArray(settings?.currencies) ? settings.currencies : [];
  const configuredMethods = Array.isArray(settings?.methods) ? settings.methods : [];
  const configuredRates = Array.isArray(settings?.rates) ? settings.rates : [];
  const providerCurrencies = Array.isArray(provider?.supportedCurrencies) ? provider.supportedCurrencies : [];
  const providerMethods = Array.isArray(provider?.supportedMethods) ? provider.supportedMethods : [];
  const providerAvailable = provider?.available !== false;
  const currencyAllow = new Set(providerCurrencies.map(normalizeRechargeCurrency).filter(Boolean));
  const methodAllow = new Set(providerMethods.map(normalizeRechargeMethod).filter(Boolean));

  let currencies = configuredCurrencies.map(normalizeRechargeCurrency).filter(Boolean);
  if (providerAvailable && currencyAllow.size > 0) {
    currencies = currencies.filter((currency) => currencyAllow.has(currency));
  }
  if (currencies.length === 0) {
    currencies = (providerCurrencies.length ? providerCurrencies : ["CNY"])
      .map(normalizeRechargeCurrency)
      .filter(Boolean);
  }
  if (currencies.length === 0) {
    currencies = ["CNY"];
  }

  let methods = configuredMethods.map(normalizeRechargeMethod).filter(Boolean);
  if (providerAvailable && methodAllow.size > 0) {
    methods = methods.filter((method) => methodAllow.has(method));
  }
  if (methods.length === 0) {
    methods = (providerMethods.length ? providerMethods : ["ALIPAY"])
      .map(normalizeRechargeMethod)
      .filter(Boolean);
  }
  if (methods.length === 0) {
    methods = ["ALIPAY"];
  }

  state.recharge.currencies = Array.from(new Set(currencies));
  state.recharge.methods = Array.from(new Set(methods));
  state.recharge.rates = configuredRates.map(normalizeRechargeRate).filter(Boolean);
  if (state.recharge.rates.length === 0) {
    state.recharge.rates = state.recharge.methods.flatMap((method) =>
      state.recharge.currencies.map((currency) => ({ method, currency, coinsPerUnit: 100 }))
    );
  }
  state.recharge.provider = provider || null;
  renderRechargeSelectors();
  updateRechargeAmountLabel();
}

function renderRechargeSelectors() {
  const render = (select, values, labeler) => {
    if (!select) {
      return;
    }
    const previous = select.value;
    select.replaceChildren();
    values.forEach((value) => {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = labeler(value);
      select.appendChild(option);
    });
    if (values.includes(previous)) {
      select.value = previous;
    }
  };
  render(elements.rechargePaymentCurrency, state.recharge.currencies, (currency) => currency);
  render(elements.rechargePaymentMethod, state.recharge.methods, paymentMethodLabel);
  updateRechargeAmountLabel();
  updateRechargeCoinPreview();
}

function parseRechargeAmountMinor() {
  const raw = String(elements.rechargeAmount?.value || "").trim();
  if (!/^[0-9]+(\.[0-9]{1,2})?$/.test(raw)) {
    throw new Error("Enter a valid amount with at most 2 decimal places.");
  }
  const [majorRaw, minorRaw = ""] = raw.split(".");
  const major = Number(majorRaw);
  const minor = Number((minorRaw + "00").slice(0, 2));
  const amountMinor = major * 100 + minor;
  if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0) {
    throw new Error("Recharge amount must be positive.");
  }
  return amountMinor;
}

function updateRechargeCoinPreview() {
  if (!elements.rechargeCoinPreview) {
    return;
  }
  const comboAvailable = updateRechargeComboAvailabilityState();
  if (!comboAvailable) {
    return;
  }
  try {
    const amountMinor = parseRechargeAmountMinor();
    const coinAmount = calculateRechargeCoinAmount(amountMinor, currentRechargeCurrency(), currentRechargeMethod());
    elements.rechargeCoinPreview.value = `${coinAmount} ShopCoin`;
  } catch (error) {
    elements.rechargeCoinPreview.value = "-";
  }
}

function normalizeRechargeRate(rate) {
  if (!rate || typeof rate !== "object") {
    return null;
  }
  const method = normalizeRechargeMethod(rate.method);
  const currency = normalizeRechargeCurrency(rate.currency);
  const coinsPerUnit = Number(rate.coinsPerUnit);
  if (!method || !currency || !Number.isSafeInteger(coinsPerUnit) || coinsPerUnit <= 0) {
    return null;
  }
  return { method, currency, coinsPerUnit };
}

function currentRechargeMethod() {
  return normalizeRechargeMethod(elements.rechargePaymentMethod?.value) || state.recharge.methods[0] || "ALIPAY";
}

function resolveRechargeRate(currency, method) {
  const normalizedCurrency = normalizeRechargeCurrency(currency);
  const normalizedMethod = normalizeRechargeMethod(method) || state.recharge.methods[0] || "ALIPAY";
  return state.recharge.rates.find((rate) =>
    rate.method === normalizedMethod && rate.currency === normalizedCurrency
  ) || state.recharge.rates.find((rate) =>
    rate.currency === normalizedCurrency
  ) || null;
}

function hasRechargeRateForCombo(currency, method) {
  const normalizedCurrency = normalizeRechargeCurrency(currency);
  const normalizedMethod = normalizeRechargeMethod(method);
  if (!normalizedCurrency || !normalizedMethod) {
    return false;
  }
  return state.recharge.rates.some((rate) => rate.currency === normalizedCurrency && rate.method === normalizedMethod);
}

function updateRechargeComboAvailabilityState() {
  const currency = currentRechargeCurrency();
  const method = currentRechargeMethod();
  const available = hasRechargeRateForCombo(currency, method);
  const creditField = elements.rechargeCoinPreview?.closest(".field");
  state.recharge.comboUnavailable = !available;

  if (creditField) {
    creditField.classList.toggle("hidden", !available);
  }
  if (elements.rechargeBtn) {
    elements.rechargeBtn.disabled = !available;
    elements.rechargeBtn.setAttribute("aria-disabled", available ? "false" : "true");
  }
  if (!available) {
    if (elements.rechargeCoinPreview) {
      elements.rechargeCoinPreview.value = getAppPageText("rechargeCreditUnavailable", "-");
    }
    setMetaText(
      elements.rechargeView,
      getAppPageText("rechargeComboUnavailableTip", APP_UI_TEXT.initMeta.rechargeView || ""),
      "info"
    );
    return false;
  }
  return true;
}

function calculateRechargeCoinAmount(amountMinor, currency, method) {
  const rate = resolveRechargeRate(currency, method);
  if (!rate) {
    throw new Error("Recharge rate is not configured.");
  }
  const coinAmount = Math.round((Number(amountMinor) * rate.coinsPerUnit) / 100);
  if (!Number.isSafeInteger(coinAmount) || coinAmount <= 0) {
    throw new Error("Recharge coin amount is invalid.");
  }
  return coinAmount;
}

function setRechargePayLink(url) {
  if (!elements.rechargePayLink) {
    return;
  }
  if (!url) {
    elements.rechargePayLink.classList.add("hidden");
    return;
  }
  elements.rechargePayLink.classList.remove("hidden");
}

function formatPaymentAmountMinor(amountMinor, currency) {
  const value = Number(amountMinor || 0) / 100;
  return `${String(currency || "").toUpperCase()} ${value.toFixed(2)}`;
}

function setRechargePaymentDialogVisible(visible) {
  if (!elements.rechargePaymentDialog) {
    return;
  }
  elements.rechargePaymentDialog.classList.toggle("show", visible);
  elements.rechargePaymentDialog.setAttribute("aria-hidden", visible ? "false" : "true");
  if (!visible) {
    stopRechargeExpireCountdown();
  }
}

function normalizeRechargeStatus(status) {
  return String(status || "").trim().toUpperCase();
}

function isRechargeTerminalStatus(status) {
  return ["PAID", "FAILED", "EXPIRED", "CLOSED"].includes(normalizeRechargeStatus(status));
}

function stopRechargeStatusPolling() {
  if (state.recharge.statusPollTimer) {
    window.clearInterval(state.recharge.statusPollTimer);
    state.recharge.statusPollTimer = null;
  }
  state.recharge.statusPollBusy = false;
}

function startRechargeStatusPolling() {
  if (!state.recharge.currentOrderId || state.recharge.statusPollTimer) {
    return;
  }
  state.recharge.statusPollTimer = window.setInterval(() => {
    refreshRechargeStatus({ silent: true, fromPoll: true }).catch((error) => {
      const message = resolveErrorMessage(error, "operation");
      log(formatAppTemplate("rechargeStatusFailed", { message }), "WARN");
    });
  }, 3000);
}

function setRechargePaymentActionsEnabled(enabled) {
  if (elements.rechargePaymentDialogOpen) {
    elements.rechargePaymentDialogOpen.disabled = !enabled;
    elements.rechargePaymentDialogOpen.classList.toggle("hidden", !enabled);
  }
  if (elements.rechargePaymentDialogCancel) {
    elements.rechargePaymentDialogCancel.disabled = !enabled;
    elements.rechargePaymentDialogCancel.classList.toggle("hidden", !enabled);
  }
}

function setRechargeExpireVisible(visible) {
  if (elements.rechargePaymentDialogExpireAt?.parentElement) {
    elements.rechargePaymentDialogExpireAt.parentElement.classList.toggle("hidden", !visible);
  }
  if (elements.rechargePaymentDialogExpireCountdown?.parentElement) {
    elements.rechargePaymentDialogExpireCountdown.parentElement.classList.toggle("hidden", !visible);
  }
}

function stopRechargeExpireCountdown() {
  if (state.recharge.expireTimer) {
    window.clearInterval(state.recharge.expireTimer);
    state.recharge.expireTimer = null;
  }
}

function updateRechargeExpireCountdown() {
  if (!elements.rechargePaymentDialogExpireAt || !elements.rechargePaymentDialogExpireCountdown) {
    return;
  }
  const expireTime = state.recharge.currentPayment?.expireTime;
  if (!expireTime) {
    elements.rechargePaymentDialogExpireAt.textContent = "-";
    elements.rechargePaymentDialogExpireCountdown.textContent = "-";
    return;
  }
  elements.rechargePaymentDialogExpireAt.textContent = formatDateTime(expireTime);
  const remaining = formatCountdown(expireTime);
  elements.rechargePaymentDialogExpireCountdown.textContent = remaining || "-";
}

function startRechargeExpireCountdown() {
  stopRechargeExpireCountdown();
  if (!elements.rechargePaymentDialog?.classList.contains("show")) {
    return;
  }
  updateRechargeExpireCountdown();
  if (!state.recharge.currentPayment?.expireTime) {
    return;
  }
  state.recharge.expireTimer = window.setInterval(updateRechargeExpireCountdown, 1000);
}

function setRechargeQrVisible(visible, qrCodeUrl = "") {
  if (visible && qrCodeUrl && elements.rechargeQrImage && elements.rechargeQrPanel) {
    elements.rechargeQrImage.src = qrCodeUrl;
    elements.rechargeQrPanel.classList.remove("hidden");
    elements.rechargeQrEmpty?.classList.add("hidden");
    return;
  }
  if (elements.rechargeQrImage) {
    elements.rechargeQrImage.removeAttribute("src");
  }
  elements.rechargeQrPanel?.classList.add("hidden");
  elements.rechargeQrEmpty?.classList.toggle("hidden", !visible);
}

function setRechargePaymentSuccessVisible(visible) {
  elements.rechargePaymentSuccess?.classList.toggle("hidden", !visible);
  if (visible) {
    setRechargeQrVisible(false);
  }
}

function updateRechargePaymentDialogStatus(status, payload = {}) {
  const normalizedStatus = normalizeRechargeStatus(status || payload.status);
  if (!elements.rechargePaymentDialogStatus) {
    return;
  }
  setRechargeExpireVisible(!["PAID", "FAILED", "EXPIRED", "CLOSED"].includes(normalizedStatus));
  if (normalizedStatus === "PAID") {
    stopRechargeExpireCountdown();
    setRechargePaymentSuccessVisible(true);
    setMetaText(
      elements.rechargePaymentDialogStatus,
      formatAppTemplate("rechargePaidDetected", {
        orderId: payload.orderId || state.recharge.currentOrderId || "-",
        coinAmount: payload.coinAmount || "-",
      }),
      "success"
    );
    return;
  }
  if (["FAILED", "EXPIRED", "CLOSED"].includes(normalizedStatus)) {
    stopRechargeExpireCountdown();
    setMetaText(
      elements.rechargePaymentDialogStatus,
      formatAppTemplate("rechargeOrderClosed", {
        orderId: payload.orderId || state.recharge.currentOrderId || "-",
        status: normalizedStatus,
      }),
      "warn"
    );
    return;
  }
  setMetaText(
    elements.rechargePaymentDialogStatus,
    getAppPageText("rechargePaymentDialogStatusPending") || "Waiting for payment confirmation",
    "info"
  );
}

function showRechargePaymentDialog(payment) {
  const data = payment || state.recharge.currentPayment;
  if (!data || !data.payUrl) {
    notify(formatAppTemplate("rechargeNoPayLink"), "warn");
    return;
  }
  state.recharge.currentPayment = data;
  if (elements.rechargePaymentDialogOrder) {
    elements.rechargePaymentDialogOrder.textContent = data.orderId || "-";
  }
  if (elements.rechargePaymentDialogAmount) {
    elements.rechargePaymentDialogAmount.textContent = formatPaymentAmountMinor(data.amountMinor, data.currency);
  }
  updateRechargeExpireCountdown();
  const qrCodeUrl = String(data.qrCodeUrl || "").trim();
  setRechargePaymentSuccessVisible(false);
  setRechargeQrVisible(true, qrCodeUrl);
  setRechargePaymentActionsEnabled(true);
  updateRechargePaymentDialogStatus(data.status || "PAYING", data);
  setRechargePaymentDialogVisible(true);
  startRechargeExpireCountdown();
  startRechargeStatusPolling();
}

function openCurrentRechargePaymentPage() {
  const url = state.recharge.currentPayment?.payUrl;
  if (!url) {
    notify(formatAppTemplate("rechargeNoPayLink"), "warn");
    return;
  }
  window.open(url, "_blank", "noopener,noreferrer");
}

async function cancelCurrentRechargeOrder() {
  ensureToken();
  const orderId = state.recharge.currentOrderId || state.recharge.currentPayment?.orderId;
  if (!orderId) {
    notify(formatAppTemplate("rechargeCancelNoOrder"), "warn");
    return;
  }
  const payload = await api("/api/recharge/cancel", {
    method: "POST",
    body: JSON.stringify({ orderId }),
  });
  stopRechargeStatusPolling();
  state.recharge.currentPayment = null;
  state.recharge.currentOrderId = null;
  setRechargePayLink(null);
  setRechargePaymentDialogVisible(false);
  const message = formatAppTemplate("rechargeCancelSuccess", { orderId: payload.orderId || orderId });
  setMetaText(elements.rechargeView, message, "success");
  notify(message, "success");
}

async function refreshRechargeStatus(options = {}) {
  ensureToken();
  if (!state.recharge.currentOrderId) {
    if (!options.silent) {
      setMetaText(elements.rechargeView, formatAppTemplate("rechargeNoOrder"), "warn");
    }
    return;
  }
  if (options.fromPoll && state.recharge.statusPollBusy) {
    return;
  }
  state.recharge.statusPollBusy = true;
  try {
    const payload = await api(
      `/api/recharge/status?orderId=${encodeURIComponent(state.recharge.currentOrderId)}`,
      { method: "GET" }
    );
    const status = normalizeRechargeStatus(payload.status);
    const terminal = isRechargeTerminalStatus(status);
    if (payload.payUrl && !terminal) {
      state.recharge.currentPayment = {
        orderId: payload.orderId,
        payUrl: payload.payUrl,
        qrCodeUrl: payload.qrCodeUrl,
        amountMinor: payload.amountMinor,
        currency: payload.currency,
        expireTime: payload.expireTime || state.recharge.currentPayment?.expireTime,
        status,
      };
      setRechargePayLink(payload.payUrl);
      if (elements.rechargePaymentDialog?.classList.contains("show")) {
        showRechargePaymentDialog(state.recharge.currentPayment);
      }
    }
    if (terminal) {
      stopRechargeStatusPolling();
      setRechargePayLink(null);
      setRechargePaymentActionsEnabled(false);
      setRechargeQrVisible(false);
      updateRechargePaymentDialogStatus(status, payload);
      if (state.recharge.currentPayment) {
        state.recharge.currentPayment = {
          ...state.recharge.currentPayment,
          payUrl: null,
          qrCodeUrl: null,
          status,
        };
      }
    } else {
      updateRechargePaymentDialogStatus(status || "PAYING", payload);
    }
    setMetaText(
      elements.rechargeView,
      formatAppTemplate("rechargeStatus", {
        orderId: payload.orderId,
        status: payload.status,
        coinAmount: payload.coinAmount,
      }),
      status === "PAID" ? "success" : "info"
    );
    if (status === "PAID") {
      notify(formatAppTemplate("rechargePaidDetected", {
        orderId: payload.orderId,
        coinAmount: payload.coinAmount,
      }), "success");
      await refreshWallet();
      await loadWalletLedger();
    }
    return payload;
  } finally {
    state.recharge.statusPollBusy = false;
  }
}

function summarizeWalletDelta(nextWallet, previousWallet) {
  if (!nextWallet || !previousWallet) {
    return "";
  }
  const parts = [];
  const deltaShop = Number(nextWallet.shopCoin || 0) - Number(previousWallet.shopCoin || 0);
  const deltaGame = Number(nextWallet.gameCoin || 0) - Number(previousWallet.gameCoin || 0);
  if (deltaShop !== 0) {
    parts.push(formatCurrency(deltaShop, "SHOP_COIN"));
  }
  if (deltaGame !== 0) {
    parts.push(formatCurrency(deltaGame, "GAME_COIN"));
  }
  return parts.join(" / ");
}

function parseMeta(raw) {
  if (!raw) {
    return {};
  }
  if (typeof raw === "object") {
    return raw;
  }
  try {
    return JSON.parse(raw);
  } catch (error) {
    return {};
  }
}

function stripColorCodes(text) {
  return String(text || "").replace(/§[0-9A-FK-OR]/gi, "");
}

function normalizeMaterialKey(text) {
  return String(text || "")
    .toUpperCase()
    .replace(/^MINECRAFT:/, "")
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function aliasMaterialKey(text) {
  const key = normalizeMaterialKey(text);
  if (!key) {
    return "";
  }
  if (key.startsWith("BLOCK_OF_") && key.length > "BLOCK_OF_".length) {
    return `${key.slice("BLOCK_OF_".length)}_BLOCK`;
  }
  return key;
}

function humanizeMaterial(materialKey) {
  return I18N ? I18N.humanizeEnum(materialKey) : materialKey
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function getMaterialVisualOverride(material) {
  const key = normalizeMaterialKey(material);
  const aliasKey = aliasMaterialKey(key);
  if (!key) {
    return null;
  }
  return state.materialVisualMap[key] || state.materialVisualMap[aliasKey] || null;
}

function normalizeVisualPolicy(raw) {
  const iconMode = String(raw?.iconPolicyMode || "SOFT").trim().toUpperCase() === "HARD" ? "HARD" : "SOFT";
  const nameMode = String(raw?.namePolicyMode || "SOFT").trim().toUpperCase() === "HARD" ? "HARD" : "SOFT";
  return {
    globalCustomIconEnabled: raw?.globalCustomIconEnabled !== false,
    globalCustomNameEnabled: raw?.globalCustomNameEnabled !== false,
    officialProductCustomIconEnabled: raw?.officialProductCustomIconEnabled !== false,
    officialProductCustomNameEnabled: raw?.officialProductCustomNameEnabled !== false,
    officialProductUploadImageEnabled: raw?.officialProductUploadImageEnabled !== false,
    marketListingCustomIconEnabled: raw?.marketListingCustomIconEnabled !== false,
    marketListingCustomNameEnabled: raw?.marketListingCustomNameEnabled !== false,
    marketListingUploadImageEnabled: raw?.marketListingUploadImageEnabled !== false,
    iconPolicyMode: iconMode,
    namePolicyMode: nameMode,
  };
}

function applyVisualPolicy(raw) {
  state.visualPolicy = normalizeVisualPolicy(raw || state.visualPolicy || {});
}

function resolveMaterialIconUrl(path) {
  const text = String(path || "").trim();
  if (!text) {
    return "";
  }
  if (/^[a-z]+:\/\//i.test(text) || text.startsWith("//")) {
    return text;
  }
  if (text.startsWith("/")) {
    return resolveApiUrl(text);
  }
  return resolveApiUrl(`/${text}`);
}

function getLocalizedMaterialName(material, options = {}) {
  const key = normalizeMaterialKey(material);
  const aliasKey = aliasMaterialKey(key);
  if (!key) {
    return localizeDisplayText("未知物品");
  }
  const includeGlobalOverride = options.includeGlobalOverride !== false
    && normalizeVisualPolicy(state.visualPolicy || {}).globalCustomNameEnabled !== false;
  const visual = includeGlobalOverride ? getMaterialVisualOverride(key) : null;
  if (visual && visual.displayNameOverride) {
    return String(visual.displayNameOverride);
  }
  return state.materialNameMap[key] || state.materialNameMap[aliasKey] || humanizeMaterial(aliasKey || key);
}

function resolveDisplayVisual(baseMaterial, displayNameOverride, displayMaterial, displayIconPath, fallbackName = "", options = {}) {
  const policy = normalizeVisualPolicy(state.visualPolicy || {});
  const category = options.category === "official" ? "official" : "market";
  const categoryIconEnabled = category === "official"
    ? policy.officialProductCustomIconEnabled !== false
    : policy.marketListingCustomIconEnabled !== false;
  const categoryNameEnabled = category === "official"
    ? policy.officialProductCustomNameEnabled !== false
    : policy.marketListingCustomNameEnabled !== false;
  const baseKey = normalizeMaterialKey(baseMaterial) || DEFAULT_TEXTURE_FALLBACK_MATERIAL;
  const globalVisual = getMaterialVisualOverride(baseKey);
  const customName = String(displayNameOverride || "").trim();
  const customMaterial = normalizeMaterialKey(displayMaterial);
  const customIconPath = String(displayIconPath || "").trim();
  const fallback = String(fallbackName || "").trim();
  const resolvedMaterial = customMaterial || baseKey || DEFAULT_TEXTURE_FALLBACK_MATERIAL;

  let forceIconPath = "";
  if (policy.globalCustomIconEnabled && policy.iconPolicyMode === "HARD" && globalVisual?.iconPath) {
    forceIconPath = String(globalVisual.iconPath);
  } else if (categoryIconEnabled && customIconPath) {
    forceIconPath = customIconPath;
  } else if (!customMaterial && policy.globalCustomIconEnabled && policy.iconPolicyMode === "SOFT" && globalVisual?.iconPath) {
    forceIconPath = String(globalVisual.iconPath);
  }

  let resolvedName = "";
  if (policy.globalCustomNameEnabled && policy.namePolicyMode === "HARD" && globalVisual?.displayNameOverride) {
    resolvedName = String(globalVisual.displayNameOverride);
  } else if (categoryNameEnabled && customName) {
    resolvedName = customName;
  } else if (fallback) {
    resolvedName = fallback;
  } else if (!customMaterial && policy.globalCustomNameEnabled && policy.namePolicyMode === "SOFT" && globalVisual?.displayNameOverride) {
    resolvedName = String(globalVisual.displayNameOverride);
  } else {
    resolvedName = getLocalizedMaterialName(resolvedMaterial, { includeGlobalOverride: false });
  }

  return {
    material: resolvedMaterial,
    title: resolvedName || getLocalizedMaterialName(resolvedMaterial, { includeGlobalOverride: false }),
    forceIconPath,
    includeMaterialOverride: policy.globalCustomIconEnabled !== false,
  };
}

function resolveListingDisplayVisual(listing, metaDisplayName = "") {
  return resolveDisplayVisual(
    listing?.itemMaterial,
    listing?.displayNameOverride,
    listing?.displayMaterial,
    listing?.displayIconPath,
    metaDisplayName,
    { category: "market" }
  );
}

function resolveProductDisplayVisual(product) {
  const baseMaterial = resolveProductTextureMaterial(product);
  return resolveDisplayVisual(
    baseMaterial,
    product?.displayNameOverride,
    product?.displayMaterial,
    product?.displayIconPath,
    product?.title || "",
    { category: "official" }
  );
}

async function ensureMaterialVisualMap() {
  if (state.materialVisualMapReady) {
    return;
  }
  if (state.materialVisualMapPromise) {
    await state.materialVisualMapPromise;
    return;
  }
  state.materialVisualMapPromise = fetch(resolveApiUrl("/api/meta/material-overrides"))
    .then((response) => {
      if (!response.ok) {
        throw new Error(`material visual map load failed: ${response.status}`);
      }
      return response.json();
    })
    .then((json) => {
      const map = {};
      const overrides = Array.isArray(json?.overrides) ? json.overrides : [];
      overrides.forEach((item) => {
        const materialKey = normalizeMaterialKey(item?.materialKey);
        if (!materialKey) {
          return;
        }
        map[materialKey] = {
          displayNameOverride: String(item?.displayNameOverride || "").trim() || null,
          iconPath: String(item?.iconPath || "").trim() || null,
        };
      });
      state.materialVisualMap = map;
      applyVisualPolicy(json?.policy || {});
      state.materialVisualMapReady = true;
      log(`Material visual overrides loaded: ${Object.keys(map).length}`);
    })
    .catch((error) => {
      state.materialVisualMap = {};
      applyVisualPolicy({});
      state.materialVisualMapReady = true;
      log(`Material visual overrides unavailable: ${error.message}`, "WARN");
    });

  await state.materialVisualMapPromise;
}

async function ensureMaterialNameMap() {
  if (state.materialNameMapReady) {
    return;
  }
  if (state.materialNameMapPromise) {
    await state.materialNameMapPromise;
    return;
  }
  if (!I18N || !I18N.shouldLoadMaterialMap()) {
    state.materialNameMap = {};
    state.materialNameMapReady = true;
    await ensureMaterialVisualMap();
    return;
  }
  state.materialNameMapPromise = Promise.all([
    fetch(`/i18n/materials/${I18N.getLocale()}.json`)
      .then((response) => {
        if (!response.ok) {
          throw new Error(`material glossary load failed: ${response.status}`);
        }
        return response.json();
      })
      .then((json) => {
        state.materialNameMap = json || {};
        state.materialNameMapReady = true;
        log(`Material glossary loaded: ${Object.keys(state.materialNameMap).length}`);
      })
      .catch((error) => {
        state.materialNameMap = {};
        state.materialNameMapReady = true;
        log(`Material glossary unavailable: ${error.message}`, "WARN");
      }),
    ensureMaterialVisualMap(),
  ]);

  await state.materialNameMapPromise;
}

function populateMarketMaterialSuggest() {
  if (!elements.materialSuggestList) {
    return;
  }
  elements.materialSuggestList.innerHTML = "";
  state.marketMaterialLookup = {};
  const keys = Array.from(state.marketMaterialAllowSet || []);
  keys
    .filter(Boolean)
    .sort((a, b) => String(a).localeCompare(String(b), I18N ? I18N.getIntlLocale() : "zh-CN"))
    .forEach((key) => {
      const option = document.createElement("option");
      const localizedName = getLocalizedMaterialName(key, { includeGlobalOverride: false });
      const label = localizedName && localizedName !== key ? `${localizedName} (${key})` : key;
      option.value = key;
      option.label = label;
      setNodeText(option, label);
      elements.materialSuggestList.appendChild(option);
      state.marketMaterialLookup[String(key).toLowerCase()] = key;
      state.marketMaterialLookup[humanizeMaterial(key).toLowerCase()] = key;
      if (localizedName && localizedName !== key) {
        state.marketMaterialLookup[String(localizedName).toLowerCase()] = key;
      }
      state.marketMaterialLookup[String(label).toLowerCase()] = key;
    });
}

async function ensureMarketMaterialAllowList() {
  if (state.marketMaterialAllowReady) {
    return;
  }
  if (state.marketMaterialAllowPromise) {
    await state.marketMaterialAllowPromise;
    return;
  }
  state.marketMaterialAllowPromise = fetch(resolveApiUrl("/api/meta/materials"))
    .then((response) => {
      if (!response.ok) {
        throw new Error(`material allow list load failed: ${response.status}`);
      }
      return response.json();
    })
    .then((json) => {
      const list = Array.isArray(json?.materials) ? json.materials : [];
      const allow = new Set();
      list.forEach((item) => {
        const normalized = normalizeMaterialKey(item);
        if (normalized) {
          allow.add(normalized);
        }
      });
      state.marketMaterialAllowSet = allow;
      state.marketMaterialAllowReady = true;
      populateMarketMaterialSuggest();
    })
    .catch((error) => {
      state.marketMaterialAllowSet = new Set();
      state.marketMaterialAllowReady = true;
      log(formatAppTemplate("marketMaterialAllowLoadFailed", { message: error.message }), "WARN");
    });
  await state.marketMaterialAllowPromise;
}

function resolveMarketAllowedMaterial(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return "";
  }
  const withKeyMatch = text.match(/\(([A-Z0-9_:-]+)\)\s*$/i);
  const candidateFromSuffix = withKeyMatch ? normalizeMaterialKey(withKeyMatch[1]) : "";
  const normalized = candidateFromSuffix || normalizeMaterialKey(text);
  if (!normalized) {
    const mapped = state.marketMaterialLookup[String(text).toLowerCase()] || "";
    if (mapped) {
      if (state.marketMaterialAllowSet.size <= 0 || state.marketMaterialAllowSet.has(mapped)) {
        return mapped;
      }
      const mappedAlias = aliasMaterialKey(mapped);
      if (mappedAlias && state.marketMaterialAllowSet.has(mappedAlias)) {
        return mappedAlias;
      }
    }
    return "";
  }
  if (state.marketMaterialAllowSet.size <= 0) {
    return normalized;
  }
  if (state.marketMaterialAllowSet.has(normalized)) {
    return normalized;
  }
  const alias = aliasMaterialKey(normalized);
  if (alias && state.marketMaterialAllowSet.has(alias)) {
    return alias;
  }
  return "";
}

function populateMarketTagSelect(selectNode, includeAllOption) {
  if (!selectNode) {
    return;
  }
  const previous = String(selectNode.value || "");
  selectNode.innerHTML = "";
  if (includeAllOption) {
    const allOption = document.createElement("option");
    allOption.value = "";
    allOption.textContent = "全部分类";
    selectNode.appendChild(allOption);
  }
  (state.marketTags || []).forEach((tag) => {
    if (!tag || !tag.code) {
      return;
    }
    const option = document.createElement("option");
    option.value = String(tag.code);
    option.textContent = `${tag.displayName || tag.code} (${tag.code})`;
    selectNode.appendChild(option);
  });
  const hasPrevious = previous && Array.from(selectNode.options).some((option) => option.value === previous);
  selectNode.value = hasPrevious ? previous : "";
}

async function ensureMarketTagsMeta() {
  if (state.marketTagsReady) {
    return;
  }
  if (state.marketTagsPromise) {
    await state.marketTagsPromise;
    return;
  }
  state.marketTagsPromise = fetch(resolveApiUrl("/api/meta/market-tags"))
    .then((response) => {
      if (!response.ok) {
        throw new Error(`market tags load failed: ${response.status}`);
      }
      return response.json();
    })
    .then((json) => {
      const rows = Array.isArray(json?.tags) ? json.tags : [];
      state.marketTags = rows
        .map((item) => ({
          code: normalizeTagCode(item?.code),
          displayName: String(item?.displayName || item?.code || "").trim(),
          enabled: item?.enabled !== false,
          priority: Number(item?.priority || 9999),
          activeSellCount: Number(item?.activeSellCount || item?.activeCount?.SELL || 0),
          activeBuyCount: Number(item?.activeBuyCount || item?.activeCount?.BUY || 0),
        }))
        .filter((item) => !!item.code)
        .sort((left, right) => {
          const leftPriority = Number.isFinite(left.priority) ? left.priority : 9999;
          const rightPriority = Number.isFinite(right.priority) ? right.priority : 9999;
          if (leftPriority !== rightPriority) {
            return leftPriority - rightPriority;
          }
          return left.code.localeCompare(right.code, I18N ? I18N.getIntlLocale() : "zh-CN");
        });
      state.marketTagsReady = true;
      populateMarketTagSelect(elements.marketTag, true);
      log(`市场标签已加载：${state.marketTags.length} 个。`);
    })
    .catch((error) => {
      state.marketTags = [];
      state.marketTagsReady = true;
      populateMarketTagSelect(elements.marketTag, true);
      log(formatAppTemplate("marketTagsLoadFailed", { message: error.message }), "WARN");
    });

  await state.marketTagsPromise;
}

function normalizeAlgorithmParamSchema(raw, index) {
  const key = String(raw?.key || `param_${index}`).trim();
  if (!key) {
    return null;
  }
  const type = String(raw?.type || "number").trim().toLowerCase();
  const tier = String(raw?.tier || raw?.group || "").trim().toLowerCase();
  return {
    key,
    label: String(raw?.label || key),
    type: type === "text" ? "text" : "number",
    advanced: Boolean(raw?.advanced) || tier === "advanced",
    required: Boolean(raw?.required),
    min: Number.isFinite(Number(raw?.min)) ? Number(raw.min) : null,
    max: Number.isFinite(Number(raw?.max)) ? Number(raw.max) : null,
    step: Number.isFinite(Number(raw?.step)) ? Number(raw.step) : null,
    defaultValue: raw?.default,
    description: String(raw?.description || "").trim(),
  };
}

function normalizeAlgorithmDefinition(raw, index) {
  const id = String(raw?.id || "").trim().toUpperCase();
  if (!id) {
    return null;
  }
  const params = Array.isArray(raw?.params)
    ? raw.params
      .map((param, paramIndex) => normalizeAlgorithmParamSchema(param, paramIndex))
      .filter(Boolean)
    : [];
  return {
    id,
    label: String(raw?.label || id),
    summary: String(raw?.summary || "").trim(),
    helpSlug: String(raw?.helpSlug || id.toLowerCase()),
    requiresMinIncrement: Boolean(raw?.requiresMinIncrement),
    requiresEndAt: Boolean(raw?.requiresEndAt),
    params,
    sortOrder: Number.isFinite(Number(raw?.sortOrder)) ? Number(raw.sortOrder) : index,
  };
}

function normalizeMarketAlgorithmGlossary(raw) {
  const dynamic = Array.isArray(raw?.dynamic)
    ? raw.dynamic.map((item, index) => normalizeAlgorithmDefinition(item, index)).filter(Boolean)
    : [];
  const auction = Array.isArray(raw?.auction)
    ? raw.auction.map((item, index) => normalizeAlgorithmDefinition(item, index)).filter(Boolean)
    : [];
  const normalized = {
    dynamic: dynamic.length > 0 ? dynamic : FALLBACK_MARKET_ALGORITHM_GLOSSARY.dynamic,
    auction: auction.length > 0 ? auction : FALLBACK_MARKET_ALGORITHM_GLOSSARY.auction,
  };
  normalized.dynamic = normalized.dynamic
    .slice()
    .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0));
  normalized.auction = normalized.auction
    .slice()
    .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0));
  return normalized;
}

async function ensureMarketAlgorithmGlossary() {
  if (state.marketAlgorithmGlossaryReady) {
    return;
  }
  if (state.marketAlgorithmGlossaryPromise) {
    await state.marketAlgorithmGlossaryPromise;
    return;
  }

  const locale = I18N ? I18N.getLocale() : "zh-CN";
  const candidates = [
    `/i18n/market-algorithms/${locale}.json`,
    "/i18n/market-algorithms/zh-CN.json",
    "/i18n/market-algorithms/en-US.json",
  ];

  state.marketAlgorithmGlossaryPromise = (async () => {
    for (const path of candidates) {
      try {
        const response = await fetch(path, { cache: "no-cache" });
        if (!response.ok) {
          continue;
        }
        const json = await response.json();
        state.marketAlgorithmGlossary = normalizeMarketAlgorithmGlossary(json || {});
        state.marketAlgorithmGlossaryReady = true;
        log(
          `Market algorithm glossary loaded: dynamic=${state.marketAlgorithmGlossary.dynamic.length}, auction=${state.marketAlgorithmGlossary.auction.length}`,
          "SUCCESS"
        );
        return;
      } catch (error) {
        continue;
      }
    }

    state.marketAlgorithmGlossary = normalizeMarketAlgorithmGlossary({});
    state.marketAlgorithmGlossaryReady = true;
    log("Market algorithm glossary unavailable, fallback catalog enabled.", "WARN");
  })();

  await state.marketAlgorithmGlossaryPromise;
}

function getAlgorithmCatalog(type) {
  const key = type === "auction" ? "auction" : "dynamic";
  const catalog = state.marketAlgorithmGlossary?.[key];
  return Array.isArray(catalog) ? catalog : [];
}

function getAlgorithmDefinition(type, algorithmId) {
  const normalizedId = String(algorithmId || "").trim().toUpperCase();
  if (!normalizedId) {
    return null;
  }
  const catalog = getAlgorithmCatalog(type);
  return catalog.find((item) => String(item.id || "").toUpperCase() === normalizedId) || null;
}

function getAlgorithmLabel(type, algorithmId) {
  const definition = getAlgorithmDefinition(type, algorithmId);
  if (!definition) {
    return String(algorithmId || "").trim() || "--";
  }
  return definition.label || definition.id;
}

function parseAlgorithmParamsJson(raw) {
  if (!raw) {
    return {};
  }
  if (typeof raw === "object") {
    return raw && !Array.isArray(raw) ? raw : {};
  }
  try {
    const parsed = JSON.parse(String(raw));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch (error) {
    return {};
  }
}

function resolveAlgorithmParamInitialValue(paramValues, schemaKey) {
  if (!paramValues || typeof paramValues !== "object") {
    return undefined;
  }
  if (Object.prototype.hasOwnProperty.call(paramValues, schemaKey)) {
    return paramValues[schemaKey];
  }
  const aliases = PARAM_KEY_ALIAS_MAP[schemaKey];
  if (!Array.isArray(aliases)) {
    return undefined;
  }
  for (const alias of aliases) {
    if (Object.prototype.hasOwnProperty.call(paramValues, alias)) {
      return paramValues[alias];
    }
  }
  return undefined;
}

function renderAlgorithmParamEditors(host, paramSchemas, paramValues, options = {}) {
  const entries = [];
  const advancedOnly = Boolean(options.advancedOnly);
  const emptyMessage = options.emptyMessage !== undefined ? options.emptyMessage : "当前算法无额外参数。";
  const filteredSchemas = Array.isArray(paramSchemas)
    ? paramSchemas.filter((schema) => Boolean(schema?.advanced) === advancedOnly)
    : [];
  host.innerHTML = "";
  if (filteredSchemas.length === 0) {
    if (emptyMessage) {
      host.appendChild(createEl("p", "field-hint", emptyMessage));
    }
    return entries;
  }

  for (const schema of filteredSchemas) {
    const field = createEl("div", "dialog-select-field");
    const requiredSuffix = schema.required ? " *" : "";
    field.appendChild(createEl("span", "dialog-select-label", `${schema.label}${requiredSuffix}`));
    const input = document.createElement("input");
    input.type = schema.type === "text" ? "text" : "number";
    if (schema.type === "number") {
      if (schema.min !== null) {
        input.min = String(schema.min);
      }
      if (schema.max !== null) {
        input.max = String(schema.max);
      }
      if (schema.step !== null) {
        input.step = String(schema.step);
      } else {
        input.step = "1";
      }
    }
    const mappedValue = resolveAlgorithmParamInitialValue(paramValues, schema.key);
    const initialValue = mappedValue !== undefined ? mappedValue : schema.defaultValue;
    if (initialValue !== undefined && initialValue !== null) {
      input.value = String(initialValue);
    }
    field.appendChild(input);
    host.appendChild(field);
    if (schema.description) {
      host.appendChild(createEl("p", "field-hint", schema.description));
    }
    entries.push({ schema, input });
  }
  return entries;
}

function collectAlgorithmParamValues(entries) {
  const payload = {};
  for (const entry of entries) {
    const rawValue = String(entry.input.value || "").trim();
    const fallbackValue = entry.schema.defaultValue === undefined || entry.schema.defaultValue === null
      ? ""
      : String(entry.schema.defaultValue).trim();
    const effectiveValue = rawValue || fallbackValue;
    if (!effectiveValue) {
      if (entry.schema.required) {
        entry.input.focus();
        return undefined;
      }
      continue;
    }
    if (entry.schema.type === "number") {
      const numericValue = Number(effectiveValue);
      if (!Number.isFinite(numericValue)) {
        entry.input.focus();
        return undefined;
      }
      if (entry.schema.min !== null && numericValue < entry.schema.min) {
        entry.input.focus();
        return undefined;
      }
      if (entry.schema.max !== null && numericValue > entry.schema.max) {
        entry.input.focus();
        return undefined;
      }
      payload[entry.schema.key] = Number.isInteger(numericValue)
        ? Math.trunc(numericValue)
        : numericValue;
    } else {
      payload[entry.schema.key] = effectiveValue;
    }
  }
  return payload;
}

function openAlgorithmHelpPage(category, algorithmId) {
  const normalizedCategory = category === "auction" ? "auction" : "dynamic";
  const locale = I18N ? I18N.getLocale() : "zh-CN";
  const query = new URLSearchParams();
  query.set("mode", "single");
  query.set("doc", "manual");
  query.set("lang", locale);
  query.set("category", normalizedCategory);
  const normalizedAlgorithm = String(algorithmId || "").trim();
  if (normalizedAlgorithm) {
    query.set("algorithm", normalizedAlgorithm);
  }
  query.set("locale", locale);
  const fallbackAnchor = normalizedCategory === "auction" ? "market-auction" : "dynamic-algorithms";
  const anchor = normalizedAlgorithm || fallbackAnchor;
  window.open(`/help.html?${query.toString()}#${encodeURIComponent(anchor)}`, "_blank", "noopener");
}

function buildTextureAliases(material) {
  const key = normalizeMaterialKey(material);
  const aliasKey = aliasMaterialKey(key);
  const aliases = new Set();
  if (!key) {
    return [];
  }

  aliases.add(key.toLowerCase());
  if (aliasKey) {
    aliases.add(aliasKey.toLowerCase());
  }
  if (key.startsWith("LEGACY_")) {
    aliases.add(key.slice("LEGACY_".length).toLowerCase());
  }

  const override = MATERIAL_TEXTURE_OVERRIDES[key];
  if (Array.isArray(override)) {
    for (const value of override) {
      aliases.add(String(value).toLowerCase());
    }
  }

  return Array.from(aliases);
}

function getTextureCandidates(material, options = {}) {
  const includeMaterialOverride = options.includeMaterialOverride !== false;
  const visual = includeMaterialOverride ? getMaterialVisualOverride(material) : null;
  const overrideIconUrl = resolveMaterialIconUrl(options.forceIconPath || visual?.iconPath || "");
  const names = buildTextureAliases(material);
  if (names.length === 0) {
    const fallback = getFallbackTextureCandidates();
    if (overrideIconUrl && !fallback.includes(overrideIconUrl)) {
      fallback.unshift(overrideIconUrl);
    }
    return fallback;
  }

  const candidates = [];
  if (overrideIconUrl) {
    candidates.push(overrideIconUrl);
  }
  for (const textureName of names) {
    candidates.push(`${LOCAL_TEXTURE_BASE}/item/${textureName}.png`);
    candidates.push(`${LOCAL_TEXTURE_BASE}/block/${textureName}.png`);
  }

  for (const base of REMOTE_TEXTURE_BASES) {
    for (const textureName of names) {
      candidates.push(`${base}/item/${textureName}.png`);
      candidates.push(`${base}/block/${textureName}.png`);
    }
  }

  const fallbackCandidates = getFallbackTextureCandidates();
  fallbackCandidates.forEach((candidate) => {
    if (!candidates.includes(candidate)) {
      candidates.push(candidate);
    }
  });
  return candidates;
}

function getFallbackTextureCandidates() {
  const names = buildTextureAliases(DEFAULT_TEXTURE_FALLBACK_MATERIAL);
  const candidates = [];
  for (const textureName of names) {
    candidates.push(`${LOCAL_TEXTURE_BASE}/item/${textureName}.png`);
    candidates.push(`${LOCAL_TEXTURE_BASE}/block/${textureName}.png`);
  }
  for (const base of REMOTE_TEXTURE_BASES) {
    for (const textureName of names) {
      candidates.push(`${base}/item/${textureName}.png`);
      candidates.push(`${base}/block/${textureName}.png`);
    }
  }
  candidates.push(getFallbackTexture());
  return candidates;
}

function buildTextureImage(material, altText, options = {}) {
  const img = document.createElement("img");
  img.className = "market-icon-image";
  img.alt = altText;
  img.loading = "lazy";
  img.decoding = "async";

  const candidates = getTextureCandidates(material, options);
  let index = 0;
  img.src = candidates[index];

  img.addEventListener("error", () => {
    index += 1;
    if (index < candidates.length) {
      img.src = candidates[index];
    }
  });

  return img;
}

function hasExplicitTimeZone(value) {
  return /(?:Z|[+\-]\d{2}:\d{2})$/i.test(String(value || "").trim());
}

function parseLocalDateTimeParts(value) {
  const match = String(value || "")
    .trim()
    .match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/);
  if (!match) {
    return null;
  }
  return {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3]),
    hour: Number(match[4]),
    minute: Number(match[5]),
    second: Number(match[6] || 0),
    millisecond: Number((match[7] || "0").padEnd(3, "0")),
  };
}

function getTimeZoneOffsetMinutes(timestamp, timeZone) {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
  const formatted = {};
  formatter.formatToParts(new Date(timestamp)).forEach((part) => {
    if (part.type !== "literal") {
      formatted[part.type] = part.value;
    }
  });
  const asUtc = Date.UTC(
    Number(formatted.year),
    Number(formatted.month) - 1,
    Number(formatted.day),
    Number(formatted.hour),
    Number(formatted.minute),
    Number(formatted.second),
    0
  );
  return Math.round((asUtc - timestamp) / 60000);
}

function parseDateTimeValue(value) {
  const text = String(value || "").trim();
  if (!text) {
    return Number.NaN;
  }
  if (hasExplicitTimeZone(text)) {
    return Date.parse(text);
  }
  const localParts = parseLocalDateTimeParts(text);
  if (!localParts) {
    return Date.parse(text);
  }
  const utcGuess = Date.UTC(
    localParts.year,
    localParts.month - 1,
    localParts.day,
    localParts.hour,
    localParts.minute,
    localParts.second,
    localParts.millisecond
  );
  const initialOffset = getTimeZoneOffsetMinutes(utcGuess, state.timeZone);
  let timestamp = utcGuess - initialOffset * 60000;
  const resolvedOffset = getTimeZoneOffsetMinutes(timestamp, state.timeZone);
  if (resolvedOffset !== initialOffset) {
    timestamp = utcGuess - resolvedOffset * 60000;
  }
  return timestamp;
}

function toDateTimeLocalValue(value) {
  const timestamp = parseDateTimeValue(value);
  if (Number.isNaN(timestamp)) {
    return "";
  }
  const date = new Date(timestamp);
  const localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return localDate.toISOString().slice(0, 16);
}

function formatInBusinessTimeZone(timestamp) {
  return new Intl.DateTimeFormat(I18N ? I18N.getIntlLocale() : "zh-CN", {
    timeZone: state.timeZone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(timestamp));
}

function formatAge(isoText) {
  const timestamp = parseDateTimeValue(isoText);
  if (Number.isNaN(timestamp)) {
    return "未知时间";
  }

  const diffMs = Date.now() - timestamp;
  const minutes = Math.max(0, Math.floor(diffMs / 60000));
  if (minutes < 1) {
    return "刚刚";
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`;
  }

  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours} 小时前`;
  }

  const days = Math.floor(hours / 24);
  return `${days} 天前`;
}

function formatListingStatus(status) {
  const normalized = String(status || "").toUpperCase();
  switch (normalized) {
    case "ACTIVE":
      return localizeDisplayText("在售");
    case "SUPPLY_EMPTY":
      return localizeDisplayText("待补货");
    case "PAUSED":
      return localizeDisplayText("已停用");
    case "UNLISTED":
      return localizeDisplayText("已退回");
    case "SOLD":
      return localizeDisplayText("已售");
    default:
      return localizeDisplayText(normalized || "--");
  }
}

function formatDateTime(isoText) {
  const timestamp = parseDateTimeValue(isoText);
  if (Number.isNaN(timestamp)) {
    return localizeDisplayText("未知时间");
  }
  return formatInBusinessTimeZone(timestamp);
}

function formatSupplyLoadedAt(isoText) {
  if (!isoText) {
    return localizeDisplayText("未补货");
  }
  return formatDateTime(isoText);
}

function formatCountdown(deadlineIso) {
  const deadline = parseDateTimeValue(deadlineIso);
  if (Number.isNaN(deadline)) {
    return null;
  }
  const diff = Math.max(0, deadline - Date.now());
  const seconds = Math.ceil(diff / 1000);
  if (seconds <= 0) {
    return localizeDisplayText("已结束");
  }
  if (seconds < 60) {
    return `${seconds} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  const remain = seconds % 60;
  return `${minutes} 分 ${remain} 秒`;
}

function orderStatusMeta(status) {
  const key = String(status || "").toUpperCase();
  const meta = ORDER_STATUS_LABELS[key] || { label: key || "未知", tone: "pending" };
  return { ...meta, label: localizeDisplayText(meta.label) };
}

function enchantLabel(key) {
  const normalized = String(key || "").replace(/^minecraft:/, "").trim().toLowerCase();
  if (!normalized) {
    return localizeDisplayText("未知附魔");
  }
  if (I18N && !I18N.isChineseLocale()) {
    return I18N.humanizeEnum(normalized);
  }
  if (ENCHANTMENT_LABELS[normalized]) {
    return ENCHANTMENT_LABELS[normalized];
  }
  return normalized
    .replaceAll("_", " ")
    .split(" ")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function formatEnchantLevel(level) {
  const normalized = Math.max(1, Math.floor(Number(level || 0)));
  const numerals = [
    [10, "X"],
    [9, "IX"],
    [8, "VIII"],
    [7, "VII"],
    [6, "VI"],
    [5, "V"],
    [4, "IV"],
    [3, "III"],
    [2, "II"],
    [1, "I"],
  ];
  let remaining = normalized;
  let output = "";
  while (remaining > 0) {
    const entry = numerals.find(([value]) => remaining >= value);
    if (!entry) {
      break;
    }
    output += entry[1];
    remaining -= entry[0];
  }
  return output || String(normalized);
}

function collectEnchantEntries(meta) {
  const regular = meta && meta.enchants && typeof meta.enchants === "object" ? meta.enchants : {};
  const stored = meta && meta.storedEnchants && typeof meta.storedEnchants === "object" ? meta.storedEnchants : {};
  const merged = new Map();
  Object.entries(regular).forEach(([key, level]) => {
    merged.set(key, Number(level || 0));
  });
  Object.entries(stored).forEach(([key, level]) => {
    const next = Number(level || 0);
    const current = merged.get(key);
    merged.set(key, current == null ? next : Math.max(current, next));
  });
  return Array.from(merged.entries())
    .filter(([, level]) => Number.isFinite(level) && level > 0)
    .sort((a, b) => b[1] - a[1] || enchantLabel(a[0]).localeCompare(enchantLabel(b[0]), I18N ? I18N.getIntlLocale() : "zh-CN"));
}

function createEl(tag, className, text) {
  const el = document.createElement(tag);
  if (className) {
    el.className = className;
  }
  if (text !== undefined && text !== null) {
    el.textContent = localizeDisplayText(text);
  }
  return el;
}

function createDialogSelectField(labelText, control) {
  const field = createEl("div", "dialog-select-field");
  field.appendChild(createEl("span", "dialog-select-label", labelText));
  field.appendChild(control);
  return field;
}

function createStoreKey(listing) {
  return `${listing.sellerUuid || ""}::${listing.sellerName || ""}`;
}

function resetStoreDetail() {
  state.marketStore.sellerKey = null;
  state.marketStore.sellerName = null;
  state.marketStore.sellerUuid = null;
}

function buildAvatarImage(seed, altText) {
  const img = document.createElement("img");
  img.className = "store-avatar";
  img.alt = altText;
  img.loading = "lazy";
  img.decoding = "async";
  img.src = `${AVATAR_BASE}${encodeURIComponent(seed || "")}`;
  img.addEventListener("error", () => {
    img.src = getFallbackAvatar();
  }, { once: true });
  return img;
}

function createQuantitySelector({
  max,
  unitPrice,
  currency,
  totalClassName = "quantity-total",
}) {
  const normalizedMax = Math.max(1, Math.floor(Number(max || 1)));
  const wrap = createEl("div", "quantity-selector");
  const inputs = createEl("div", "quantity-inputs");

  const numberInput = document.createElement("input");
  numberInput.className = "product-qty";
  numberInput.type = "number";
  numberInput.min = "1";
  numberInput.max = String(normalizedMax);
  numberInput.step = "1";
  numberInput.value = "1";

  const rangeInput = document.createElement("input");
  rangeInput.className = "range-input quantity-range";
  rangeInput.type = "range";
  rangeInput.min = "1";
  rangeInput.max = String(normalizedMax);
  rangeInput.step = "1";
  rangeInput.value = "1";

  inputs.appendChild(numberInput);
  inputs.appendChild(rangeInput);
  wrap.appendChild(inputs);

  const total = createEl("p", totalClassName, `总价：${formatCurrency(unitPrice, currency)}`);
  wrap.appendChild(total);

  const sync = (rawValue) => {
    const parsed = Number(rawValue);
    const normalized = Number.isFinite(parsed) ? Math.floor(parsed) : 1;
    const clamped = Math.min(normalizedMax, Math.max(1, normalized));
    numberInput.value = String(clamped);
    rangeInput.value = String(clamped);
    setNodeText(total, `总价：${formatCurrency(unitPrice * clamped, currency)}`);
    return clamped;
  };

  // Focus-select avoids the "1" default blocking direct overwrite on mobile/desktop keyboards.
  numberInput.addEventListener("focus", () => {
    numberInput.select();
  });
  numberInput.addEventListener("input", () => {
    if (numberInput.value === "") {
      return;
    }
    const parsed = Number(numberInput.value);
    if (!Number.isFinite(parsed)) {
      return;
    }
    const clamped = Math.min(normalizedMax, Math.max(1, Math.floor(parsed)));
    rangeInput.value = String(clamped);
    setNodeText(total, `总价：${formatCurrency(unitPrice * clamped, currency)}`);
  });
  numberInput.addEventListener("blur", () => {
    sync(numberInput.value || 1);
  });
  rangeInput.addEventListener("input", () => {
    sync(rangeInput.value);
  });
  sync(1);

  return {
    wrap,
    inputs,
    numberInput,
    rangeInput,
    total,
  };
}

function createProgressIndicator(initialCurrent, initialTotal, textBuilder) {
  const wrap = createEl("div", "progress-info");
  const track = createEl("div", "progress-track");
  const fill = createEl("div", "progress-fill");
  track.appendChild(fill);
  const label = createEl("p", "progress-text", "");
  wrap.appendChild(track);
  wrap.appendChild(label);

  const update = (current, total) => {
    const normalizedTotal = Math.max(1, Number(total || 1));
    const normalizedCurrent = Math.max(0, Math.min(normalizedTotal, Number(current || 0)));
    fill.style.width = `${(normalizedCurrent / normalizedTotal) * 100}%`;
    label.textContent = localizeDisplayText(textBuilder(normalizedCurrent, normalizedTotal));
  };

  update(initialCurrent, initialTotal);
  return { wrap, update };
}

function setMarketButtons(mode) {
  document.getElementById("marketListBtn").classList.toggle("active", mode === "public");
  if (elements.marketStoreBtn) {
    elements.marketStoreBtn.classList.toggle("active", mode === "stores");
  }
  document.getElementById("marketMineBtn").classList.toggle("active", mode === "mine");
  if (elements.marketHideOwnToggle) {
    elements.marketHideOwnToggle.closest(".market-toggle")?.classList.toggle("hidden", mode !== "public");
  }
}

function resolveErrorMessage(error, scene) {
  const code = String(error && error.code ? error.code : "")
    .trim()
    .toLowerCase();
  const raw = String(error && error.message ? error.message : "").trim();
  if (/failed to fetch|networkerror/i.test(raw)) {
    return "无法连接到商城服务，请确认服务器和内置 Web 已正常运行。";
  }

  const scoped = ERROR_TIPS_BY_SCENE[scene];
  if (scoped && code && scoped[code]) {
    return scoped[code];
  }
  if (code && ERROR_TIPS_COMMON[code]) {
    return ERROR_TIPS_COMMON[code];
  }
  if (raw) {
    return raw;
  }
  return "操作失败，请稍后重试。";
}

function redeemStatusTip(status) {
  const key = String(status || "UNKNOWN").toUpperCase();
  if (REDEEM_STATUS_TIPS[key]) {
    return REDEEM_STATUS_TIPS[key];
  }
  return {
    tone: "warn",
    text: `兑换状态未知：${key}`,
  };
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body !== undefined && !headers["Content-Type"]) {
    headers["Content-Type"] = "application/json";
  }
  if (state.token) {
    headers.Authorization = `Bearer ${state.token}`;
  }

  const response = await fetch(resolveApiUrl(path), { ...options, headers });
  const contentType = response.headers.get("content-type") || "";
  let payload;

  if (contentType.includes("application/json")) {
    payload = await response.json();
  } else {
    const text = await response.text();
    payload = { message: text || `HTTP ${response.status}` };
  }

  if (!response.ok) {
    const error = new Error(payload.message || payload.error || `HTTP ${response.status}`);
    error.code = payload.error || "";
    error.status = response.status;
    error.endpoint = path;
    throw error;
  }

  return payload;
}

async function apiUpload(path, file) {
  const headers = {};
  if (state.token) {
    headers.Authorization = `Bearer ${state.token}`;
  }
  const contentType = String(file?.type || "").trim();
  headers["Content-Type"] = contentType || "application/octet-stream";
  const response = await fetch(resolveApiUrl(path), {
    method: "POST",
    headers,
    body: file,
  });
  const responseType = response.headers.get("content-type") || "";
  const payload = responseType.includes("application/json")
    ? await response.json()
    : { message: await response.text() };
  if (!response.ok) {
    const error = new Error(payload.message || payload.error || `HTTP ${response.status}`);
    error.code = payload.error || "";
    error.status = response.status;
    error.endpoint = path;
    throw error;
  }
  return payload;
}

function fileNameWithExtension(name, ext) {
  const base = String(name || "custom-icon")
    .replace(/[\\/:*?"<>|]+/g, "_")
    .replace(/\.[^./\\]+$/, "")
    .trim() || "custom-icon";
  return `${base}.${ext}`;
}

const materialCropState = {
  initialized: false,
  image: null,
  sourceFileName: "",
  exportSize: 128,
  scale: 1,
  minScale: 1,
  maxScale: 8,
  offsetX: 0,
  offsetY: 0,
  activePointers: new Map(),
  dragActive: false,
  dragPointerId: null,
  dragStartX: 0,
  dragStartY: 0,
  dragOriginOffsetX: 0,
  dragOriginOffsetY: 0,
  pinchStartDistance: 0,
  pinchStartScale: 1,
  resolver: null,
};

function isMaterialCropDialogOpen() {
  return Boolean(elements.materialCropDialog?.classList.contains("show"));
}

async function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.readAsDataURL(file);
  });
}

async function loadImageFromDataUrl(dataUrl) {
  return new Promise((resolve, reject) => {
    const node = new Image();
    node.onload = () => resolve(node);
    node.onerror = () => reject(new Error("解析图片失败"));
    node.src = dataUrl;
  });
}

async function cropImageFileToSquarePngAuto(file, size = 128) {
  const input = file;
  if (!input || !String(input.type || "").startsWith("image/")) {
    return input;
  }
  const dataUrl = await readFileAsDataUrl(input);
  const image = await loadImageFromDataUrl(dataUrl);
  const width = Number(image.width || 0);
  const height = Number(image.height || 0);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new Error("图片尺寸无效");
  }
  const side = Math.min(width, height);
  const sx = Math.floor((width - side) / 2);
  const sy = Math.floor((height - side) / 2);
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    throw new Error("无法创建图片画布");
  }
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(image, sx, sy, side, side, 0, 0, size, size);
  const blob = await new Promise((resolve) => {
    canvas.toBlob(resolve, "image/png", 1.0);
  });
  if (!blob) {
    throw new Error("图片裁剪失败");
  }
  return new File([blob], fileNameWithExtension(input.name, "png"), { type: "image/png" });
}

function getMaterialCropCanvasContext() {
  const canvas = elements.materialCropCanvas;
  if (!canvas) {
    return null;
  }
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    return null;
  }
  return { canvas, ctx, width: canvas.width, height: canvas.height };
}

function getMaterialCropFrameRect(metrics) {
  const base = Math.min(metrics.width, metrics.height);
  const padding = Math.max(16, Math.round(base * 0.1));
  const size = Math.max(120, base - padding * 2);
  const x = Math.round((metrics.width - size) / 2);
  const y = Math.round((metrics.height - size) / 2);
  return { x, y, size };
}

function getPointerDistance(pointerA, pointerB) {
  if (!pointerA || !pointerB) {
    return 0;
  }
  const dx = pointerA.clientX - pointerB.clientX;
  const dy = pointerA.clientY - pointerB.clientY;
  return Math.sqrt(dx * dx + dy * dy);
}

function getCanvasRelativePoint(canvas, clientX, clientY) {
  const rect = canvas.getBoundingClientRect();
  const ratioX = canvas.width / Math.max(rect.width, 1);
  const ratioY = canvas.height / Math.max(rect.height, 1);
  return {
    x: (clientX - rect.left) * ratioX,
    y: (clientY - rect.top) * ratioY,
  };
}

function clampMaterialCropOffsets() {
  const metrics = getMaterialCropCanvasContext();
  if (!metrics || !materialCropState.image) {
    return;
  }
  const frame = getMaterialCropFrameRect(metrics);
  const drawWidth = materialCropState.image.width * materialCropState.scale;
  const drawHeight = materialCropState.image.height * materialCropState.scale;
  const minX = frame.x + frame.size - drawWidth;
  const maxX = frame.x;
  const minY = frame.y + frame.size - drawHeight;
  const maxY = frame.y;
  materialCropState.offsetX = Math.max(minX, Math.min(maxX, materialCropState.offsetX));
  materialCropState.offsetY = Math.max(minY, Math.min(maxY, materialCropState.offsetY));
}

function updateMaterialCropZoomUi() {
  if (!elements.materialCropZoom || !elements.materialCropZoomValue) {
    return;
  }
  const ratio = Math.max(
    1,
    Math.min(8, materialCropState.scale / Math.max(materialCropState.minScale, Number.EPSILON))
  );
  elements.materialCropZoom.value = String(Math.round(ratio * 100));
  setNodeText(elements.materialCropZoomValue, `${Math.round(ratio * 100)}%`);
}

function renderMaterialCropCanvas() {
  const metrics = getMaterialCropCanvasContext();
  if (!metrics) {
    return;
  }
  const { ctx, width, height } = metrics;
  const frame = getMaterialCropFrameRect(metrics);
  ctx.clearRect(0, 0, width, height);

  const checkerSize = 16;
  for (let y = 0; y < height; y += checkerSize) {
    for (let x = 0; x < width; x += checkerSize) {
      const odd = (Math.floor(x / checkerSize) + Math.floor(y / checkerSize)) % 2 === 1;
      ctx.fillStyle = odd ? "rgba(255,255,255,0.06)" : "rgba(0,0,0,0.04)";
      ctx.fillRect(x, y, checkerSize, checkerSize);
    }
  }

  if (materialCropState.image) {
    const drawWidth = materialCropState.image.width * materialCropState.scale;
    const drawHeight = materialCropState.image.height * materialCropState.scale;
    ctx.drawImage(
      materialCropState.image,
      materialCropState.offsetX,
      materialCropState.offsetY,
      drawWidth,
      drawHeight
    );
  }

  ctx.fillStyle = "rgba(0, 0, 0, 0.36)";
  ctx.fillRect(0, 0, width, frame.y);
  ctx.fillRect(0, frame.y, frame.x, frame.size);
  ctx.fillRect(frame.x + frame.size, frame.y, width - frame.x - frame.size, frame.size);
  ctx.fillRect(0, frame.y + frame.size, width, height - frame.y - frame.size);

  ctx.strokeStyle = "rgba(255,255,255,0.75)";
  ctx.lineWidth = 2;
  ctx.strokeRect(frame.x + 1, frame.y + 1, frame.size - 2, frame.size - 2);
  ctx.lineWidth = 1;
  ctx.strokeStyle = "rgba(255,255,255,0.28)";
  ctx.beginPath();
  ctx.moveTo(frame.x + frame.size / 3, frame.y);
  ctx.lineTo(frame.x + frame.size / 3, frame.y + frame.size);
  ctx.moveTo(frame.x + (frame.size * 2) / 3, frame.y);
  ctx.lineTo(frame.x + (frame.size * 2) / 3, frame.y + frame.size);
  ctx.moveTo(frame.x, frame.y + frame.size / 3);
  ctx.lineTo(frame.x + frame.size, frame.y + frame.size / 3);
  ctx.moveTo(frame.x, frame.y + (frame.size * 2) / 3);
  ctx.lineTo(frame.x + frame.size, frame.y + (frame.size * 2) / 3);
  ctx.stroke();
}

function setMaterialCropScale(nextScale, anchorX, anchorY) {
  const metrics = getMaterialCropCanvasContext();
  if (!metrics || !materialCropState.image) {
    return;
  }
  const frame = getMaterialCropFrameRect(metrics);
  const safeAnchorX = Number.isFinite(anchorX) ? anchorX : frame.x + frame.size / 2;
  const safeAnchorY = Number.isFinite(anchorY) ? anchorY : frame.y + frame.size / 2;
  const previousScale = Math.max(materialCropState.scale, Number.EPSILON);
  const normalizedScale = Math.max(
    materialCropState.minScale,
    Math.min(materialCropState.maxScale, nextScale)
  );
  const imageX = (safeAnchorX - materialCropState.offsetX) / previousScale;
  const imageY = (safeAnchorY - materialCropState.offsetY) / previousScale;
  materialCropState.scale = normalizedScale;
  materialCropState.offsetX = safeAnchorX - imageX * normalizedScale;
  materialCropState.offsetY = safeAnchorY - imageY * normalizedScale;
  clampMaterialCropOffsets();
  updateMaterialCropZoomUi();
  renderMaterialCropCanvas();
}

function resetMaterialCropViewport() {
  const metrics = getMaterialCropCanvasContext();
  if (!metrics || !materialCropState.image) {
    return;
  }
  const frame = getMaterialCropFrameRect(metrics);
  const imageWidth = Number(materialCropState.image.width || 0);
  const imageHeight = Number(materialCropState.image.height || 0);
  if (!Number.isFinite(imageWidth) || !Number.isFinite(imageHeight) || imageWidth <= 0 || imageHeight <= 0) {
    throw new Error("图片尺寸无效");
  }
  const minScale = Math.max(frame.size / imageWidth, frame.size / imageHeight);
  materialCropState.minScale = minScale;
  materialCropState.maxScale = minScale * 8;
  materialCropState.scale = minScale;
  materialCropState.offsetX = frame.x + (frame.size - imageWidth * minScale) / 2;
  materialCropState.offsetY = frame.y + (frame.size - imageHeight * minScale) / 2;
  clampMaterialCropOffsets();
  updateMaterialCropZoomUi();
  renderMaterialCropCanvas();
}

function closeMaterialCropDialog(resultFile = null) {
  if (elements.materialCropDialog) {
    elements.materialCropDialog.classList.remove("show");
    elements.materialCropDialog.setAttribute("aria-hidden", "true");
  }
  materialCropState.activePointers.clear();
  materialCropState.dragActive = false;
  materialCropState.dragPointerId = null;
  materialCropState.pinchStartDistance = 0;
  materialCropState.pinchStartScale = materialCropState.scale || 1;
  materialCropState.image = null;
  const resolver = materialCropState.resolver;
  materialCropState.resolver = null;
  if (typeof resolver === "function") {
    resolver(resultFile);
  }
}

async function exportMaterialCropAsPngFile() {
  const metrics = getMaterialCropCanvasContext();
  if (!metrics || !materialCropState.image) {
    throw new Error("裁剪器未准备好");
  }
  const frame = getMaterialCropFrameRect(metrics);
  const outputSize = Math.max(32, Math.min(1024, Number(materialCropState.exportSize || 128)));
  const sourceX = (frame.x - materialCropState.offsetX) / materialCropState.scale;
  const sourceY = (frame.y - materialCropState.offsetY) / materialCropState.scale;
  const sourceW = frame.size / materialCropState.scale;
  const sourceH = frame.size / materialCropState.scale;
  const maxSourceX = Math.max(0, materialCropState.image.width - sourceW);
  const maxSourceY = Math.max(0, materialCropState.image.height - sourceH);
  const safeSourceX = Math.max(0, Math.min(maxSourceX, sourceX));
  const safeSourceY = Math.max(0, Math.min(maxSourceY, sourceY));

  const outputCanvas = document.createElement("canvas");
  outputCanvas.width = outputSize;
  outputCanvas.height = outputSize;
  const outputCtx = outputCanvas.getContext("2d");
  if (!outputCtx) {
    throw new Error("无法创建输出画布");
  }
  outputCtx.imageSmoothingEnabled = true;
  outputCtx.imageSmoothingQuality = "high";
  outputCtx.drawImage(
    materialCropState.image,
    safeSourceX,
    safeSourceY,
    sourceW,
    sourceH,
    0,
    0,
    outputSize,
    outputSize
  );
  const blob = await new Promise((resolve) => {
    outputCanvas.toBlob(resolve, "image/png", 1.0);
  });
  if (!blob) {
    throw new Error("图片裁剪失败");
  }
  return new File([blob], fileNameWithExtension(materialCropState.sourceFileName, "png"), { type: "image/png" });
}

async function openMaterialCropDialog(file, exportSize = 128) {
  if (!elements.materialCropDialog || !elements.materialCropCanvas) {
    return cropImageFileToSquarePngAuto(file, exportSize);
  }
  if (typeof materialCropState.resolver === "function") {
    materialCropState.resolver(null);
    materialCropState.resolver = null;
  }
  const input = file;
  if (!input || !String(input.type || "").startsWith("image/")) {
    return input;
  }
  const dataUrl = await readFileAsDataUrl(input);
  const image = await loadImageFromDataUrl(dataUrl);
  materialCropState.activePointers.clear();
  materialCropState.dragActive = false;
  materialCropState.dragPointerId = null;
  materialCropState.pinchStartDistance = 0;
  materialCropState.image = image;
  materialCropState.sourceFileName = input.name || "custom-icon.png";
  materialCropState.exportSize = exportSize;
  resetMaterialCropViewport();
  elements.materialCropDialog.classList.add("show");
  elements.materialCropDialog.setAttribute("aria-hidden", "false");
  return new Promise((resolve) => {
    materialCropState.resolver = resolve;
  });
}

function initializeMaterialCropDialog() {
  if (materialCropState.initialized) {
    return;
  }
  if (!elements.materialCropDialog || !elements.materialCropCanvas) {
    materialCropState.initialized = true;
    return;
  }
  materialCropState.initialized = true;

  if (elements.materialCropZoom) {
    elements.materialCropZoom.addEventListener("input", () => {
      if (!materialCropState.image) {
        return;
      }
      const ratio = Math.max(1, Number(elements.materialCropZoom.value || 100) / 100);
      setMaterialCropScale(materialCropState.minScale * ratio);
    });
  }

  if (elements.materialCropResetBtn) {
    elements.materialCropResetBtn.addEventListener("click", () => {
      if (!materialCropState.image) {
        return;
      }
      resetMaterialCropViewport();
    });
  }

  if (elements.materialCropCancelBtn) {
    elements.materialCropCancelBtn.addEventListener("click", () => {
      closeMaterialCropDialog(null);
    });
  }

  if (elements.materialCropApplyBtn) {
    elements.materialCropApplyBtn.addEventListener("click", async () => {
      try {
        const file = await exportMaterialCropAsPngFile();
        closeMaterialCropDialog(file);
      } catch (error) {
        notify(error.message || "裁剪失败，请重试。", "error");
      }
    });
  }

  elements.materialCropDialog.addEventListener("click", (event) => {
    if (event.target === elements.materialCropDialog) {
      closeMaterialCropDialog(null);
    }
  });

  const canvas = elements.materialCropCanvas;
  canvas.style.touchAction = "none";
  canvas.addEventListener("pointerdown", (event) => {
    if (!materialCropState.image) {
      return;
    }
    materialCropState.activePointers.set(event.pointerId, {
      clientX: event.clientX,
      clientY: event.clientY,
    });
    canvas.setPointerCapture(event.pointerId);
    if (materialCropState.activePointers.size === 1) {
      materialCropState.dragActive = true;
      materialCropState.dragPointerId = event.pointerId;
      materialCropState.dragStartX = event.clientX;
      materialCropState.dragStartY = event.clientY;
      materialCropState.dragOriginOffsetX = materialCropState.offsetX;
      materialCropState.dragOriginOffsetY = materialCropState.offsetY;
    } else if (materialCropState.activePointers.size >= 2) {
      const pointers = Array.from(materialCropState.activePointers.values());
      materialCropState.dragActive = false;
      materialCropState.dragPointerId = null;
      materialCropState.pinchStartDistance = getPointerDistance(pointers[0], pointers[1]) || 1;
      materialCropState.pinchStartScale = materialCropState.scale;
    }
    event.preventDefault();
  });
  canvas.addEventListener("pointermove", (event) => {
    if (!materialCropState.image) {
      return;
    }
    if (materialCropState.activePointers.has(event.pointerId)) {
      materialCropState.activePointers.set(event.pointerId, {
        clientX: event.clientX,
        clientY: event.clientY,
      });
    }
    if (materialCropState.activePointers.size >= 2) {
      const pointers = Array.from(materialCropState.activePointers.values());
      const currentDistance = getPointerDistance(pointers[0], pointers[1]);
      const safeBaseDistance = Math.max(materialCropState.pinchStartDistance || 1, 1);
      const nextScale = materialCropState.pinchStartScale * (currentDistance / safeBaseDistance);
      const centerClientX = (pointers[0].clientX + pointers[1].clientX) / 2;
      const centerClientY = (pointers[0].clientY + pointers[1].clientY) / 2;
      const center = getCanvasRelativePoint(canvas, centerClientX, centerClientY);
      setMaterialCropScale(nextScale, center.x, center.y);
    } else if (materialCropState.dragActive && materialCropState.dragPointerId === event.pointerId) {
      const deltaX = event.clientX - materialCropState.dragStartX;
      const deltaY = event.clientY - materialCropState.dragStartY;
      materialCropState.offsetX = materialCropState.dragOriginOffsetX + deltaX;
      materialCropState.offsetY = materialCropState.dragOriginOffsetY + deltaY;
      clampMaterialCropOffsets();
      renderMaterialCropCanvas();
    }
    event.preventDefault();
  });
  const handlePointerEnd = (event) => {
    materialCropState.activePointers.delete(event.pointerId);
    if (materialCropState.activePointers.size === 0) {
      materialCropState.dragActive = false;
      materialCropState.dragPointerId = null;
      materialCropState.pinchStartDistance = 0;
      materialCropState.pinchStartScale = materialCropState.scale;
      return;
    }
    if (materialCropState.activePointers.size === 1) {
      const [remainingId, remainingPointer] = Array.from(materialCropState.activePointers.entries())[0];
      materialCropState.dragActive = true;
      materialCropState.dragPointerId = remainingId;
      materialCropState.dragStartX = remainingPointer.clientX;
      materialCropState.dragStartY = remainingPointer.clientY;
      materialCropState.dragOriginOffsetX = materialCropState.offsetX;
      materialCropState.dragOriginOffsetY = materialCropState.offsetY;
      materialCropState.pinchStartDistance = 0;
      materialCropState.pinchStartScale = materialCropState.scale;
      return;
    }
    const pointers = Array.from(materialCropState.activePointers.values());
    materialCropState.dragActive = false;
    materialCropState.dragPointerId = null;
    materialCropState.pinchStartDistance = getPointerDistance(pointers[0], pointers[1]) || 1;
    materialCropState.pinchStartScale = materialCropState.scale;
  };
  canvas.addEventListener("pointerup", handlePointerEnd);
  canvas.addEventListener("pointercancel", handlePointerEnd);
  canvas.addEventListener("wheel", (event) => {
    if (!materialCropState.image) {
      return;
    }
    const point = getCanvasRelativePoint(canvas, event.clientX, event.clientY);
    const scaleFactor = event.deltaY < 0 ? 1.07 : 0.93;
    setMaterialCropScale(materialCropState.scale * scaleFactor, point.x, point.y);
    event.preventDefault();
  }, { passive: false });

  window.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && isMaterialCropDialogOpen()) {
      closeMaterialCropDialog(null);
    }
  });
}

async function cropImageFileToSquarePng(file, size = 128) {
  initializeMaterialCropDialog();
  if (!elements.materialCropDialog || !elements.materialCropCanvas) {
    return cropImageFileToSquarePngAuto(file, size);
  }
  return openMaterialCropDialog(file, size);
}

function ensureToken() {
  if (!state.token) {
    switchTab("auth");
    throw new Error("请先登录后再操作。");
  }
}

function setAuthMode(mode) {
  if (elements.authLoginPanel) {
    elements.authLoginPanel.classList.add("active");
  }
}

function updateAuthLayout() {
  const loggedIn = !!state.token;
  elements.authEntryCard.classList.toggle("hidden", loggedIn);
  elements.authProfileCard.classList.toggle("hidden", !loggedIn);
  if (elements.authFunctionsCard) {
    elements.authFunctionsCard.classList.toggle("hidden", !loggedIn);
  }

  if (!loggedIn) {
    setStatus("未登录", "offline");
    updateNotificationBadge();
    updateAccountBackButtonVisibility();
    return;
  }

  setStatus(`已登录：${state.username || "-"}`, "online");
  updateNotificationBadge();
  updateAccountBackButtonVisibility();
  renderProfile();
}

function renderProfile() {
  elements.profileName.textContent = state.username || "-";
  setNodeText(elements.profileUuid, state.boundUuid || "未绑定");

  const avatarKey = state.username || state.boundUuid;
  if (!avatarKey) {
    elements.profileAvatar.src = getFallbackAvatar();
    return;
  }

  elements.profileAvatar.src = `${AVATAR_BASE}${encodeURIComponent(avatarKey)}`;
  elements.profileAvatar.onerror = () => {
    elements.profileAvatar.onerror = null;
    elements.profileAvatar.src = getFallbackAvatar();
  };
}

function applyUserVisualPermission(raw) {
  const permission = raw || {};
  state.visualPermission = {
    customIconAllowed: permission.customIconAllowed !== false,
    customNameAllowed: permission.customNameAllowed !== false,
    customUploadAllowed: permission.customUploadAllowed !== false,
  };
}

function setSession(payload) {
  state.token = payload.sessionToken;
  const user = payload.user || {};
  state.username = user.username || payload.username || state.username;
  if (Object.prototype.hasOwnProperty.call(user, "boundUuid")) {
    state.boundUuid = user.boundUuid || null;
  } else if (Object.prototype.hasOwnProperty.call(payload, "boundUuid")) {
    state.boundUuid = payload.boundUuid || null;
  }
  applyUserVisualPermission(user.visualPermission || payload.visualPermission || {});

  // 保存会话到本地存储
  const sessionData = {
    token: state.token,
    username: state.username,
    boundUuid: state.boundUuid,
  };
  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(sessionData));

  updateAuthLayout();
  state.hasLoadedOrders = false;
  state.hasLoadedNotifications = false;
  startRealtimeSync();
  refreshNotificationUnreadCount().catch(() => {
    // ignore unread refresh errors, realtime loop will retry
  });
}

function clearSession() {
  stopRechargeStatusPolling();
  state.token = null;
  state.username = null;
  state.boundUuid = null;
  state.recharge.currentOrderId = null;
  state.recharge.currentPayment = null;
  state.orders = [];
  state.notifications = [];
  state.hasLoadedOrders = false;
  state.hasLoadedNotifications = false;
  state.unreadNotificationCount = 0;
  state.visualPermission = {
    customIconAllowed: true,
    customNameAllowed: true,
    customUploadAllowed: true,
  };
  // 移除本地存储的会话
  window.localStorage.removeItem(SESSION_STORAGE_KEY);
  renderOrders(state.orders);
  renderNotifications(state.notifications);
  renderWalletLedger([]);
  setRechargePayLink(null);
  setRechargePaymentDialogVisible(false);
  updateAuthLayout();
  stopRealtimeSync();
}

async function restoreSession() {
  try {
    const saved = window.localStorage.getItem(SESSION_STORAGE_KEY);
    if (!saved) {
      return;
    }
    const sessionData = JSON.parse(saved);
    if (!sessionData.token) {
      return;
    }
    // 临时设置token来验证
    const originalToken = state.token;
    state.token = sessionData.token;
    // 尝试刷新钱包来验证token
    await refreshWallet();
    // 如果成功，恢复完整会话
    state.username = sessionData.username;
    state.boundUuid = sessionData.boundUuid;
    updateAuthLayout();
    await loadOrders();
    await refreshNotificationUnreadCount();
    startRealtimeSync();
    log("会话已恢复。", "SUCCESS");
  } catch (error) {
    // token无效，清除存储
    state.token = null;
    window.localStorage.removeItem(SESSION_STORAGE_KEY);
    stopRealtimeSync();
    log("会话恢复失败，已清除。", "WARN");
  }
}

async function pollRealtimeSync() {
  if (!state.token || state.realtime.busy) {
    return;
  }
  state.realtime.busy = true;
  try {
    const [walletPayload, ordersPayload, listingsPayload, notificationPayload] = await Promise.all([
      api("/api/wallet", { method: "GET" }),
      api("/api/orders/list?limit=30", { method: "GET" }),
      api("/api/market/listings?mine=true&limit=80", { method: "GET" }),
      api("/api/notifications/unread-count", { method: "GET" }),
    ]);

    if (state.realtime.hasBootstrapped) {
      const previousWallet = state.realtime.wallet;
      if (previousWallet) {
        const deltaShop = Number(walletPayload.shopCoin || 0) - Number(previousWallet.shopCoin || 0);
        const deltaGame = Number(walletPayload.gameCoin || 0) - Number(previousWallet.gameCoin || 0);
        if (deltaShop !== 0 || deltaGame !== 0) {
          const parts = [];
          if (deltaShop !== 0) {
            parts.push(formatCurrency(deltaShop, "SHOP_COIN"));
          }
          if (deltaGame !== 0) {
            parts.push(formatCurrency(deltaGame, "GAME_COIN"));
          }
          notify(`余额变动：${parts.join(" / ")}`, deltaShop + deltaGame >= 0 ? "success" : "warn");
        }
      }
      notifyOrderTransitions(state.realtime.orderDigest, ordersPayload.orders || []);
      notifyListingTransitions(state.realtime.listingDigest, listingsPayload.listings || []);
      const previousUnread = Number(state.realtime.notificationUnread || 0);
      const currentUnread = Math.max(0, Number(notificationPayload.unreadCount || 0));
      if (currentUnread > previousUnread) {
        const delta = currentUnread - previousUnread;
        notify(`你有 ${delta} 条新通知。`, "info");
      }
    }

    state.realtime.wallet = {
      shopCoin: Number(walletPayload.shopCoin || 0),
      gameCoin: Number(walletPayload.gameCoin || 0),
    };
    state.realtime.orderDigest = buildOrderDigest(ordersPayload.orders || []);
    state.realtime.listingDigest = buildListingDigest(listingsPayload.listings || []);
    state.realtime.notificationUnread = Math.max(0, Number(notificationPayload.unreadCount || 0));
    state.unreadNotificationCount = state.realtime.notificationUnread;
    updateNotificationBadge();
    state.realtime.hasBootstrapped = true;

    if (state.activeTab === "wallet") {
      updateWalletView(walletPayload);
    }
    if (state.activeTab === "orders") {
      state.orders = ordersPayload.orders || [];
      renderOrders(state.orders);
    }
    if (state.activeTab === "notifications") {
      loadNotifications({ silent: true }).catch(() => {
        // ignore transient notification refresh failures
      });
    }
    if ((state.activeTab === "market" || state.activeTab === "auction") && state.marketMode === "mine") {
      state.listings = filterListingsByTradeScope(listingsPayload.listings || [], state.marketTradeScope);
      renderListings(state.listings);
      setMetaText(elements.marketView, `${getMarketModeLabel("mine")}：${state.listings.length} 条`, "info");
    }
  } catch (error) {
    if (String(error?.message || "").toLowerCase().includes("auth")) {
      clearSession();
      switchTab("auth");
    }
  } finally {
    state.realtime.busy = false;
  }
}

function startRealtimeSync() {
  stopRealtimeSync();
  if (!state.token) {
    return;
  }
  state.realtime.busy = false;
  state.realtime.hasBootstrapped = false;
  state.realtime.orderDigest = {};
  state.realtime.listingDigest = {};
  state.realtime.wallet = null;
  state.realtime.notificationUnread = null;
  pollRealtimeSync().catch(() => {
    // ignore first poll errors, next tick will retry
  });
  state.realtime.timer = window.setInterval(() => {
    pollRealtimeSync().catch(() => {
      // ignore transient realtime sync failures
    });
  }, 8000);
}

function stopRealtimeSync() {
  if (state.realtime.timer) {
    clearInterval(state.realtime.timer);
    state.realtime.timer = null;
  }
  state.realtime.busy = false;
  state.realtime.hasBootstrapped = false;
  state.realtime.orderDigest = {};
  state.realtime.listingDigest = {};
  state.realtime.wallet = null;
  state.realtime.notificationUnread = null;
}

function updateWalletView(payload) {
  if (Object.prototype.hasOwnProperty.call(payload, "username") && payload.username) {
    state.username = payload.username;
  }
  if (Object.prototype.hasOwnProperty.call(payload, "boundUuid")) {
    state.boundUuid = payload.boundUuid || null;
  }
  if (Object.prototype.hasOwnProperty.call(payload, "visualPermission")) {
    applyUserVisualPermission(payload.visualPermission || {});
  }

  elements.shopCoinValue.textContent = formatAmount(payload.shopCoin || 0);
  elements.gameCoinValue.textContent = formatAmount(payload.gameCoin || 0);
  state.walletBalance.shopCoin = Number(payload.shopCoin || 0);
  state.walletBalance.gameCoin = Number(payload.gameCoin || 0);
  applyExchangeMeta(payload.exchange);
  setMetaText(
    elements.walletView,
    formatWalletInline(payload.shopCoin || 0, payload.gameCoin || 0),
    "info"
  );
  if (state.token) {
    renderProfile();
  }
}

async function refreshWallet() {
  ensureToken();
  const payload = await api("/api/wallet", { method: "GET" });
  updateWalletView(payload);
  return payload;
}

function resolveOfficialProductUnitPrice(product) {
  return Number(product?.price || 0);
}

function renderProducts(products) {
  const filteredProducts = filterProducts(products);
  elements.productList.innerHTML = "";

  if (!filteredProducts || filteredProducts.length === 0) {
    const empty = createEl("div", "empty-state", "暂无商品，请联系管理员在后台添加。 ");
    elements.productList.appendChild(empty);
    return;
  }

  for (const product of filteredProducts) {
    const card = createEl("article", "product-card official-card");
    const visual = resolveProductDisplayVisual(product);
    const productTitle = visual.title || product.title || product.sku || "未知商品";
    const isGroupBuyVoucher = String(product.productType || "").toUpperCase() === "GROUP_BUY_VOUCHER";
    const isRecycleItem = isRecycleProductType(product.productType);
    const dynamicEnabled = !!product.dynamicPricingEnabled;
    const unitPrice = resolveOfficialProductUnitPrice(product);
    const stock = resolveOfficialProductStock(product);
    const isSoldOut = stock.maxQuantity <= 0 && (stock.hasTrackedStock || stock.isPersonalLimitReached);

    const infoRow = createEl("div", "product-info-row");
    const icon = createEl("div", "product-icon");
    icon.appendChild(buildTextureImage(visual.material, productTitle, {
      forceIconPath: visual.forceIconPath,
      includeMaterialOverride: visual.includeMaterialOverride,
    }));
    infoRow.appendChild(icon);

    const infoMain = createEl("div", "product-info-main");
    const title = createEl("h3", "product-title", productTitle);
    title.title = productTitle;
    infoMain.appendChild(title);

    const currencyLabel = (CURRENCY_META[product.currency] || { label: product.currency }).label;
    const metaParts = [productTypeLabel(product.productType), `币种 ${currencyLabel}`];
    if (dynamicEnabled) {
      metaParts.push("动态价格");
    }
    if (stock.hasPerUserLimit) {
      metaParts.push(`限购 x${stock.perUserLimit}`);
      if (stock.hasPersonalLimitRemaining) {
        metaParts.push(`可购 x${stock.personalLimitRemaining}`);
      }
    }

    const remarkParts = [];
    
    if (metaParts.length > 0) {
      remarkParts.push(metaParts.join(" · "));
    }
    infoMain.appendChild(createEl("p", "product-remark", remarkParts.join(" ")));
    infoRow.appendChild(infoMain);
    card.appendChild(infoRow);

    const stockProgress = stock.hasTrackedStock
      ? createProgressIndicator(
          stock.remainingStock,
          stock.totalStock,
          (current, total) => `剩余 ${current}`
        )
      : createProgressIndicator(1, 1, () => "长期供应");
    stockProgress.wrap.classList.add("product-stock-row");
    card.appendChild(stockProgress.wrap);

    const quantitySelector = createQuantitySelector({
      max: stock.maxQuantity,
      unitPrice,
      currency: product.currency,
      totalClassName: "product-total",
    });
    quantitySelector.inputs.classList.add("product-quantity-row");
    quantitySelector.inputs.appendChild(quantitySelector.rangeInput);
    quantitySelector.inputs.appendChild(quantitySelector.numberInput);

    if (isSoldOut) {
      quantitySelector.numberInput.disabled = true;
      quantitySelector.rangeInput.disabled = true;
    }
    card.appendChild(quantitySelector.inputs);

    const actionRow = createEl("div", "product-action-row");
    if (stock.isPersonalLimitReached) {
      actionRow.appendChild(createEl("p", "product-state-tip", "你已达到该商品的限购上限"));
    } else if (isGroupBuyVoucher) {
      actionRow.appendChild(createEl("p", "product-state-tip", "购买后生成团购兑换码，需由管理员核销"));
    }
    actionRow.appendChild(quantitySelector.total);

    const buyBtn = createEl(
      "button",
      "product-buy-btn",
      isSoldOut ? (stock.isPersonalLimitReached ? "已达限购" : "已售罄") : (isRecycleItem ? "立即回收" : "立即购买")
    );
    buyBtn.type = "button";
    buyBtn.dataset.action = "buy-product";
    buyBtn.dataset.productId = String(product.id);
    buyBtn.dataset.maxQuantity = String(stock.maxQuantity);
    buyBtn.disabled = isSoldOut;
    actionRow.appendChild(buyBtn);

    card.appendChild(actionRow);
    elements.productList.appendChild(card);
  }
}

function filterProducts(products) {
  const source = Array.isArray(products) ? [...products] : [];
  const keyword = String(elements.productKeyword?.value || "").trim().toLowerCase();
  const type = String(elements.productFilterType?.value || "").trim().toUpperCase();
  const currency = String(elements.productFilterCurrency?.value || "").trim().toUpperCase();
  const materialInput = String(elements.productFilterMaterial?.value || "").trim();
  const material = materialInput ? normalizeMaterialKey(materialInput) : "";
  const minPrice = Number(elements.productMinPrice?.value || "");
  const maxPrice = Number(elements.productMaxPrice?.value || "");
  const sortValue = String(elements.productSort?.value || "default").trim();

  const filtered = source.filter((product) => {
    if (type && String(product.productType || "").toUpperCase() !== type) {
      return false;
    }
    if (currency && String(product.currency || "").toUpperCase() !== currency) {
      return false;
    }
    if (material && normalizeMaterialKey(product.itemMaterial || "") !== material) {
      return false;
    }
    const price = Number(product.price || 0);
    if (Number.isFinite(minPrice) && elements.productMinPrice?.value && price < minPrice) {
      return false;
    }
    if (Number.isFinite(maxPrice) && elements.productMaxPrice?.value && price > maxPrice) {
      return false;
    }
    if (!keyword) {
      return true;
    }
    const visualTitle = resolveProductDisplayVisual(product).title || product.title || product.sku || "";
    const haystack = [
      visualTitle,
      product.sku,
      product.remark,
      product.itemMaterial,
      product.displayMaterial,
      product.displayNameOverride,
      getLocalizedMaterialName(product.itemMaterial),
      product.displayMaterial ? getLocalizedMaterialName(product.displayMaterial, { includeGlobalOverride: false }) : "",
      productTypeLabel(product.productType),
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();
    return haystack.includes(keyword);
  });

  filtered.sort((left, right) => {
    switch (sortValue) {
      case "price_asc":
        return Number(left.price || 0) - Number(right.price || 0);
      case "price_desc":
        return Number(right.price || 0) - Number(left.price || 0);
      case "title_asc":
        return String(left.title || "").localeCompare(String(right.title || ""), I18N ? I18N.getIntlLocale() : "zh-CN");
      case "title_desc":
        return String(right.title || "").localeCompare(String(left.title || ""), I18N ? I18N.getIntlLocale() : "zh-CN");
      case "stock_desc":
        return resolveOfficialProductStock(right).maxQuantity - resolveOfficialProductStock(left).maxQuantity;
      default:
        return 0;
    }
  });
  return filtered;
}

function shouldHideListingInPublic(listing) {
  if (state.marketMode !== "public") {
    return false;
  }
  if (!state.hideOwnMarketListings || !state.username) {
    return false;
  }
  if (String(listing?.sellerName || "") !== String(state.username || "")) {
    return false;
  }
  // Keep BUY listings visible so creators can see and track their own buy orders in public market.
  return normalizeListingSide(listing) !== "BUY";
}

function renderListings(listings, container = elements.marketList) {
  if (container === elements.marketList) {
    container.classList.remove("storefront-grid");
  }
  container.innerHTML = "";
  const scopedListings = filterListingsByTradeScope(listings, state.marketTradeScope);
  const visibleListings = container === elements.marketList
    ? scopedListings.filter((listing) => !shouldHideListingInPublic(listing))
    : scopedListings;

  if (!visibleListings || visibleListings.length === 0) {
    const empty = createEl("div", "empty-state", getMarketEmptyStateText("listing"));
    container.appendChild(empty);
    return;
  }

  for (const listing of visibleListings) {
    const meta = parseMeta(listing.itemMetaJson);
    const isOwner = !!state.username && listing.sellerName === state.username;
    const marketSide = normalizeListingSide(listing);
    const isBuySide = marketSide === "BUY";
    const normalizedStatus = String(listing.status || "").toUpperCase();
    const isSupply = String(listing.sourceMode || "").toUpperCase() === "SUPPLY";
    const tradeMode = String(listing.tradeMode || "DIRECT").toUpperCase();
    const isAuction = tradeMode === "AUCTION";
    const auctionAlgorithm = String(listing.auctionAlgorithm || "ENGLISH_AUCTION_V1").toUpperCase();
    const isDutchAuction = isAuction && auctionAlgorithm === "DUTCH_AUCTION_V1";
    const isVickreyAuction = isAuction && auctionAlgorithm === "VICKREY_AUCTION_V1";
    const displayStatus = isSupply && Number(listing.quantity || 0) <= 0 && normalizedStatus === "ACTIVE"
      ? "SUPPLY_EMPTY"
      : normalizedStatus;
    const isActive = normalizedStatus === "ACTIVE" && Number(listing.quantity || 0) > 0;
    const isPaused = normalizedStatus === "PAUSED";

    const displayName = stripColorCodes(meta.displayName || "");
    const listingVisual = resolveListingDisplayVisual(listing, displayName);
    const localizedName = listingVisual.title;
    const quantityTotal = Number(listing.quantityTotal || listing.quantity || 0);

    if (!isOwner && !isAuction) {
      const card = createEl("article", "market-card");

      const top = createEl("div", "market-top");
      const statusClass = displayStatus === "ACTIVE" ? "sale" : displayStatus === "SUPPLY_EMPTY" || isPaused ? "paused" : "inactive";
      top.appendChild(createEl(
        "span",
        `market-chip ${statusClass}`,
        formatListingTopStatus(displayStatus, marketSide)
      ));
      if (listing.tag) {
        top.appendChild(createEl("span", "market-chip", getMarketTagDisplayName(listing.tag)));
      }
      if (isSupply) {
        top.appendChild(createEl("span", "market-chip official", "自动补货"));
      }
      top.appendChild(createEl("span", "market-time", formatAge(listing.createdAt)));
      card.appendChild(top);

      const infoRow = createEl("div", "product-info-row");
      const icon = createEl("div", "product-icon");
      icon.appendChild(buildTextureImage(listingVisual.material, localizedName, {
        forceIconPath: listingVisual.forceIconPath,
        includeMaterialOverride: listingVisual.includeMaterialOverride,
      }));
      infoRow.appendChild(icon);

      const infoMain = createEl("div", "product-info-main");
      const title = createEl("h3", "product-title", localizedName);
      title.title = localizedName;
      infoMain.appendChild(title);
      const compactRemarkParts = [];
      if (listing.remark) {
        compactRemarkParts.push(String(listing.remark));
      }
      compactRemarkParts.push(`${isBuySide ? "发布者" : "卖家"}：${listing.sellerName}`);
      infoMain.appendChild(createEl("p", "product-remark", compactRemarkParts.join(" ")));
      infoRow.appendChild(infoMain);
      card.appendChild(infoRow);

      const stockProgress = createProgressIndicator(
        Number(listing.quantity || 0),
        quantityTotal > 0 ? quantityTotal : Math.max(1, Number(listing.quantity || 0)),
        (current, total) => `剩余${current}`
      );
      stockProgress.wrap.classList.add("product-stock-row");
      card.appendChild(stockProgress.wrap);

      const priceRow = createEl("div", "market-price-row");
      priceRow.appendChild(createEl("p", "market-price-label", "单价"));
      priceRow.appendChild(createEl("p", "market-price", formatCurrency(Number(listing.price || 0), listing.currency)));
      card.appendChild(priceRow);

      const quantitySelector = createQuantitySelector({
        max: Math.max(1, Number(listing.quantity || 1)),
        unitPrice: Number(listing.price || 0),
        currency: listing.currency,
        totalClassName: "product-total",
      });
      quantitySelector.inputs.classList.add("product-quantity-row");
      quantitySelector.inputs.appendChild(quantitySelector.rangeInput);
      quantitySelector.inputs.appendChild(quantitySelector.numberInput);
      if (!isActive) {
        quantitySelector.numberInput.disabled = true;
        quantitySelector.rangeInput.disabled = true;
      }
      card.appendChild(quantitySelector.inputs);

      const actionRow = createEl("div", "product-action-row");
      if (!isActive) {
        actionRow.appendChild(createEl("p", "product-state-tip", `状态：${formatListingStatus(displayStatus)}`));
      }
      actionRow.appendChild(quantitySelector.total);

      const buyBtn = createEl(
        "button",
        "market-action-btn product-buy-btn",
        isActive ? (isBuySide ? "卖给收购单" : "立即购买") : "不可购买"
      );
      buyBtn.type = "button";
      buyBtn.disabled = !isActive;
      buyBtn.dataset.action = isBuySide ? "sellToBuy" : "buy";
      buyBtn.dataset.listingId = String(listing.id);
      buyBtn.dataset.currency = listing.currency;
      buyBtn.dataset.unitPrice = String(listing.price);
      buyBtn.dataset.maxQuantity = String(Math.max(1, Number(listing.quantity || 1)));
      actionRow.appendChild(buyBtn);

      card.appendChild(actionRow);
      container.appendChild(card);
      continue;
    }

    const card = createEl("article", "market-card");

    const top = createEl("div", "market-top");
    const statusClass = displayStatus === "ACTIVE" ? "sale" : displayStatus === "SUPPLY_EMPTY" || isPaused ? "paused" : "inactive";
    top.appendChild(createEl(
      "span",
      `market-chip ${statusClass}`,
      formatListingTopStatus(displayStatus, marketSide)
    ));
    if (listing.tag) {
      top.appendChild(createEl("span", "market-chip", getMarketTagDisplayName(listing.tag)));
    }
    if (isSupply) {
      top.appendChild(createEl("span", "market-chip official", "自动补货"));
    }
    if (isAuction) {
      top.appendChild(createEl("span", "market-chip", "拍卖"));
    }
    top.appendChild(createEl("span", "market-time", formatAge(listing.createdAt)));
    card.appendChild(top);

    const main = createEl("div", "market-main");
    const icon = createEl("div", "market-icon");
    icon.appendChild(buildTextureImage(listingVisual.material, localizedName, {
      forceIconPath: listingVisual.forceIconPath,
      includeMaterialOverride: listingVisual.includeMaterialOverride,
    }));
    main.appendChild(icon);

    const detail = createEl("div", "market-detail");
    const title = createEl("h3", "market-title", localizedName);
    title.title = localizedName;
    detail.appendChild(title);
    const code = createEl("p", "market-code", String(listing.itemMaterial));
    code.title = String(listing.itemMaterial);
    detail.appendChild(code);
    if (listing.remark) {
      detail.appendChild(createEl("p", "market-remark", listing.remark));
    }
    if (listing.tag) {
      detail.appendChild(createEl("p", "market-sub", `分类：${getMarketTagDisplayName(listing.tag)} (${listing.tag})`));
    }
    if (isBuySide) {
      detail.appendChild(
        createEl(
          "p",
          "market-sub",
          `冻结金额：${formatCurrency(Number(listing.escrowRemaining || 0), listing.currency)} / ${formatCurrency(Number(listing.escrowTotal || 0), listing.currency)}`
        )
      );
    }
    if (isSupply) {
      if (isOwner) {
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `供货模式：单次提取 x${Number(listing.supplyBatchSize || 0)} / 中转上限 x${Number(listing.supplyMaxStock || quantityTotal || 0)}`
          )
        );
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `累计提取 x${Number(listing.supplyLoadedTotal || 0)} / 累计售出 x${Number(listing.supplySoldTotal || 0)}`
          )
        );
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `最近补货：${formatSupplyLoadedAt(listing.supplyLastLoadedAt)}`
          )
        );
      } else {
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `供货模式：累计售出 x${Number(listing.supplySoldTotal || 0)}`
          )
        );
      }
    }
    if (isAuction) {
      const auctionParams = parseAlgorithmParamsJson(listing.auctionParamsJson);
      detail.appendChild(
        createEl(
          "p",
          "market-sub",
          `拍卖算法：${getAlgorithmLabel("auction", auctionAlgorithm)}`
        )
      );
      if (isDutchAuction) {
        const floorPrice = Number(auctionParams.floorPrice || 0);
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `当前买断价：${formatCurrency(Number(listing.price || listing.auctionStartPrice || 0), listing.currency)}`
          )
        );
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `底价：${floorPrice > 0 ? formatCurrency(floorPrice, listing.currency) : "--"}`
          )
        );
      } else if (isVickreyAuction) {
        const reservePrice = Number(auctionParams.reservePrice || 0);
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `起拍价：${formatCurrency(Number(listing.auctionStartPrice || listing.price || 0), listing.currency)}`
          )
        );
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `保留价：${reservePrice > 0 ? formatCurrency(reservePrice, listing.currency) : "未设置"}`
          )
        );
      } else {
        const hasHighestBid = Number(listing.auctionHighestBid || 0) > 0;
        const currentBidAmount = hasHighestBid
          ? Number(listing.auctionHighestBid || 0)
          : Number(listing.auctionStartPrice || listing.price || 0);
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `当前${hasHighestBid ? "最高出价" : "起拍价"}：${formatCurrency(currentBidAmount, listing.currency)}`
          )
        );
        detail.appendChild(
          createEl(
            "p",
            "market-sub",
            `最小加价：${formatCurrency(Math.max(1, Number(listing.auctionMinIncrement || 1)), listing.currency)}`
          )
        );
      }
      detail.appendChild(
        createEl(
          "p",
          "market-sub",
          `结束时间：${listing.auctionEndAt ? formatDateTime(listing.auctionEndAt) : "未设置"}`
        )
      );
    } else if (listing.dynamicPricingEnabled) {
      const floorText = listing.dynamicFloorPrice ? formatCurrency(listing.dynamicFloorPrice, listing.currency) : "--";
      const capText = listing.dynamicCapPrice ? formatCurrency(listing.dynamicCapPrice, listing.currency) : "--";
      detail.appendChild(
        createEl(
          "p",
          "market-sub",
          `动态算法：${getAlgorithmLabel("dynamic", listing.dynamicAlgorithm || "LINEAR_DEMAND_V1")}`
        )
      );
      detail.appendChild(
        createEl(
          "p",
          "market-sub",
          `动态定价：需求分 ${Number(listing.dynamicDemandScore || 0)}，波动步长 ${Number(listing.dynamicPriceStep || 1)}`
        )
      );
      detail.appendChild(
        createEl(
          "p",
          "market-sub",
          `地板价 ${floorText} / 封顶价 ${capText}`
        )
      );
    }
    main.appendChild(detail);
    card.appendChild(main);

    const stockProgress = createProgressIndicator(
      Number(listing.quantity || 0),
      quantityTotal > 0 ? quantityTotal : Math.max(1, Number(listing.quantity || 0)),
      (current, total) => `剩余 ${current}`
    );
    card.appendChild(stockProgress.wrap);

    const enchantEntries = collectEnchantEntries(meta);
    if (enchantEntries.length > 0) {
      const enchants = createEl("div", "market-enchants");
      enchantEntries.forEach(([key, level]) => {
        enchants.appendChild(
          createEl("span", "market-enchant", `${enchantLabel(key)} ${formatEnchantLevel(level)}`)
        );
      });
      card.appendChild(enchants);
    }

    const priceRow = createEl("div", "market-price-row");
    const priceLabel = isAuction ? (isDutchAuction ? "当前买断价" : "当前竞价") : "价格";
    const displayPrice = isAuction
      ? (isDutchAuction
        ? Number(listing.price || listing.auctionStartPrice || 0)
        : (Number(listing.auctionHighestBid || 0) > 0
          ? Number(listing.auctionHighestBid || 0)
          : Number(listing.auctionStartPrice || listing.price || 0)))
      : Number(listing.price || 0);
    priceRow.appendChild(createEl("p", "market-price-label", priceLabel));
    priceRow.appendChild(createEl("p", "market-price", formatCurrency(displayPrice, listing.currency)));
    card.appendChild(priceRow);

    const footer = createEl("div", "market-footer");
    const seller = createEl("div", "market-seller");
    seller.appendChild(createEl("span", "", `${isBuySide ? "发布者" : "卖家"}：${listing.sellerName}`));
    seller.appendChild(createEl("span", "", isOwner ? "我的上架" : "公开市场"));
    footer.appendChild(seller);

    const actions = createEl("div", "market-actions-row");
    if (isOwner) {
      const editBtn = createEl("button", "market-action-btn btn-tonal", "编辑");
      editBtn.type = "button";
      editBtn.dataset.action = "edit";
      editBtn.dataset.listingId = String(listing.id);
      editBtn.dataset.currentPrice = String(listing.price);
      editBtn.dataset.currency = listing.currency;
      editBtn.dataset.currentRemark = listing.remark || "";
      editBtn.dataset.sourceMode = listing.sourceMode || "MANUAL";
      editBtn.dataset.currentSupplyBatchSize = String(listing.supplyBatchSize || "");
      editBtn.dataset.currentSupplyMaxStock = String(listing.supplyMaxStock || "");
      editBtn.dataset.tradeMode = listing.tradeMode || "DIRECT";
      editBtn.dataset.dynamicPricingEnabled = listing.dynamicPricingEnabled ? "1" : "0";
      editBtn.dataset.dynamicAlgorithm = listing.dynamicAlgorithm || "LINEAR_DEMAND_V1";
      editBtn.dataset.dynamicParamsJson = listing.dynamicParamsJson || "";
      editBtn.dataset.dynamicBasePrice = String(listing.dynamicBasePrice || "");
      editBtn.dataset.dynamicFloorPrice = String(listing.dynamicFloorPrice || "");
      editBtn.dataset.dynamicCapPrice = String(listing.dynamicCapPrice || "");
      editBtn.dataset.dynamicPriceStep = String(listing.dynamicPriceStep || "");
      editBtn.dataset.auctionAlgorithm = listing.auctionAlgorithm || "ENGLISH_AUCTION_V1";
      editBtn.dataset.auctionParamsJson = listing.auctionParamsJson || "";
      editBtn.dataset.auctionStartPrice = String(listing.auctionStartPrice || "");
      editBtn.dataset.auctionMinIncrement = String(listing.auctionMinIncrement || "");
      editBtn.dataset.auctionEndAt = listing.auctionEndAt || "";
      editBtn.dataset.currentDisplayNameOverride = listing.displayNameOverride || "";
      editBtn.dataset.currentDisplayMaterial = listing.displayMaterial || "";
      editBtn.dataset.currentDisplayIconPath = listing.displayIconPath || "";
      editBtn.dataset.currentItemMaterial = listing.itemMaterial || "";
      editBtn.dataset.currentFallbackTitle = displayName || "";
      actions.appendChild(editBtn);

      if (isSupply && normalizedStatus !== "UNLISTED" && normalizedStatus !== "SOLD") {
        const refreshBtn = createEl("button", "market-action-btn sale", "强制刷新");
        refreshBtn.type = "button";
        refreshBtn.dataset.action = "refreshSupply";
        refreshBtn.dataset.listingId = String(listing.id);
        actions.appendChild(refreshBtn);
      }

      if (isActive) {
        const pauseBtn = createEl("button", "market-action-btn", "临时下架");
        pauseBtn.type = "button";
        pauseBtn.dataset.action = "pause";
        pauseBtn.dataset.listingId = String(listing.id);

        const unlistBtn = createEl("button", "market-action-btn unlist", "下架退回");
        unlistBtn.type = "button";
        unlistBtn.dataset.action = "unlist";
        unlistBtn.dataset.listingId = String(listing.id);

        actions.appendChild(pauseBtn);
        actions.appendChild(unlistBtn);
      } else if (isPaused) {
        const resumeBtn = createEl("button", "market-action-btn sale", "恢复上架");
        resumeBtn.type = "button";
        resumeBtn.dataset.action = "resume";
        resumeBtn.dataset.listingId = String(listing.id);

        const unlistBtn = createEl("button", "market-action-btn unlist", "下架退回");
        unlistBtn.type = "button";
        unlistBtn.dataset.action = "unlist";
        unlistBtn.dataset.listingId = String(listing.id);

        actions.appendChild(resumeBtn);
        actions.appendChild(unlistBtn);
      } else {
        const disabledBtn = createEl("button", "market-action-btn", "不可操作");
        disabledBtn.type = "button";
        disabledBtn.disabled = true;
        actions.appendChild(disabledBtn);
      }
    } else if (isActive) {
      if (isAuction) {
        if (isDutchAuction) {
          const buyBtn = createEl("button", "market-action-btn sale", "立即买断");
          buyBtn.type = "button";
          buyBtn.dataset.action = "buy";
          buyBtn.dataset.listingId = String(listing.id);
          buyBtn.dataset.currency = listing.currency;
          buyBtn.dataset.unitPrice = String(Number(listing.price || listing.auctionStartPrice || 0));
          buyBtn.dataset.maxQuantity = "1";
          const buyWrap = createEl("div", "market-buy-wrap");
          buyWrap.appendChild(buyBtn);
          actions.appendChild(buyWrap);
        } else {
          const hasHighestBid = Number(listing.auctionHighestBid || 0) > 0;
          const openingBid = Math.max(1, Number(listing.auctionStartPrice || listing.price || 0));
          const minIncrement = Math.max(1, Number(listing.auctionMinIncrement || 1));
          const minBid = isVickreyAuction
            ? openingBid
            : (hasHighestBid ? Number(listing.auctionHighestBid || 0) + minIncrement : openingBid);
          const bidWrap = createEl("div", "market-buy-wrap");
          const bidInput = document.createElement("input");
          bidInput.type = "number";
          bidInput.className = "product-qty market-bid-input";
          bidInput.min = String(minBid);
          bidInput.step = "1";
          bidInput.value = String(minBid);
          bidWrap.appendChild(bidInput);

          const bidBtn = createEl("button", "market-action-btn sale", isVickreyAuction ? "提交密封出价" : "出价竞拍");
          bidBtn.type = "button";
          bidBtn.dataset.action = "bid";
          bidBtn.dataset.listingId = String(listing.id);
          bidBtn.dataset.currency = listing.currency;
          bidBtn.dataset.minBid = String(minBid);
          bidBtn.dataset.currentBid = String(hasHighestBid ? Number(listing.auctionHighestBid || 0) : openingBid);
          bidBtn.dataset.minIncrement = String(isVickreyAuction ? 0 : minIncrement);
          bidBtn.dataset.sealedBid = isVickreyAuction ? "1" : "0";
          bidWrap.appendChild(bidBtn);
          actions.appendChild(bidWrap);
        }
      } else {
        const quantitySelector = createQuantitySelector({
          max: listing.quantity || 1,
          unitPrice: Number(listing.price || 0),
          currency: listing.currency,
          totalClassName: "market-total",
        });

        const buyBtn = createEl("button", "market-action-btn", isBuySide ? "卖给收购单" : "立即购买");
        buyBtn.type = "button";
        buyBtn.dataset.action = isBuySide ? "sellToBuy" : "buy";
        buyBtn.dataset.listingId = String(listing.id);
        buyBtn.dataset.currency = listing.currency;
        buyBtn.dataset.unitPrice = String(listing.price);
        buyBtn.dataset.maxQuantity = String(Math.max(1, Number(listing.quantity || 1)));
        const buyWrap = createEl("div", "market-buy-wrap");
        buyWrap.appendChild(quantitySelector.wrap);
        buyWrap.appendChild(buyBtn);
        actions.appendChild(buyWrap);
      }
    } else {
      const disabledBtn = createEl("button", "market-action-btn", isAuction ? "拍卖已结束" : "不可购买");
      disabledBtn.disabled = true;
      actions.appendChild(disabledBtn);
    }
    if (!isOwner && isSupply && state.token && Number(listing.quantity || 0) <= 0 && normalizedStatus !== "UNLISTED" && normalizedStatus !== "SOLD") {
      const refreshBtn = createEl("button", "market-action-btn sale", "刷新补货");
      refreshBtn.type = "button";
      refreshBtn.dataset.action = "refreshSupply";
      refreshBtn.dataset.listingId = String(listing.id);
      actions.appendChild(refreshBtn);
    }
    footer.appendChild(actions);

    card.appendChild(footer);
    container.appendChild(card);
  }
}

function renderStorefronts(listings) {
  elements.marketList.classList.add("storefront-grid");
  elements.marketList.innerHTML = "";
  const activeListings = filterListingsByTradeScope(listings, state.marketTradeScope)
    .filter((listing) => listing.status === "ACTIVE");
  if (activeListings.length === 0) {
    elements.marketList.appendChild(createEl("div", "empty-state", getMarketEmptyStateText("store")));
    return;
  }

  const storeMap = new Map();
  activeListings.forEach((listing) => {
    const key = createStoreKey(listing);
    const current = storeMap.get(key) || {
      sellerName: listing.sellerName,
      sellerUuid: listing.sellerUuid,
      count: 0,
      totalQuantity: 0,
      minPrice: Number.POSITIVE_INFINITY,
      minCurrency: listing.currency,
      latestAt: listing.createdAt,
    };
    current.count += 1;
    current.totalQuantity += Number(listing.quantity || 0);
    const currentPrice = Number(listing.price || 0);
    if (currentPrice <= current.minPrice) {
      current.minPrice = currentPrice;
      current.minCurrency = listing.currency;
    }
    if (String(listing.createdAt || "") > String(current.latestAt || "")) {
      current.latestAt = listing.createdAt;
    }
    storeMap.set(key, current);
  });

  Array.from(storeMap.entries())
    .sort((a, b) => a[1].sellerName.localeCompare(b[1].sellerName, I18N ? I18N.getIntlLocale() : "zh-CN"))
    .forEach(([key, store]) => {
      const card = createEl("article", "store-card");
      const head = createEl("div", "store-head");
      head.appendChild(buildAvatarImage(store.sellerName || store.sellerUuid, `${store.sellerName} 头像`));
      const text = createEl("div", "store-head-text");
      text.appendChild(createEl("h3", "store-title", `${store.sellerName}的小店`));
      text.appendChild(createEl("p", "store-subtitle", `店主：${store.sellerName}`));
      head.appendChild(text);
      card.appendChild(head);

      const stats = createEl("div", "store-stats");
      stats.appendChild(createEl("div", "store-stat", `在售商品 ${store.count} 件`));
      stats.appendChild(createEl("div", "store-stat", `库存总量 x${store.totalQuantity}`));
      if (Number.isFinite(store.minPrice)) {
        stats.appendChild(createEl("div", "store-stat", `起售价 ${formatCurrency(store.minPrice, store.minCurrency)}`));
      }
      card.appendChild(stats);
      card.appendChild(createEl("p", "store-subtitle", `最近上架：${formatAge(store.latestAt)}`));

      const button = createEl("button", "btn-tonal", "进入店铺");
      button.type = "button";
      button.addEventListener("click", () => {
        state.marketStore.sellerKey = key;
        state.marketStore.sellerName = store.sellerName;
        state.marketStore.sellerUuid = store.sellerUuid;
        renderSelectedStore(listings);
        setMetaText(elements.marketView, `${store.sellerName} 的店铺：${store.count} 条`, "info");
      });
      card.appendChild(button);
      elements.marketList.appendChild(card);
    });
}

function renderSelectedStore(listings) {
  const key = state.marketStore.sellerKey;
  if (!key) {
    renderStorefronts(listings);
    return;
  }
  elements.marketList.classList.remove("storefront-grid");
  const sellerListings = filterListingsByTradeScope(listings, state.marketTradeScope)
    .filter((listing) => createStoreKey(listing) === key);
  elements.marketList.innerHTML = "";

  const header = createEl("article", "store-detail-card");
  const top = createEl("div", "store-head");
  top.appendChild(
    buildAvatarImage(
      state.marketStore.sellerName || state.marketStore.sellerUuid,
      `${state.marketStore.sellerName} 头像`
    )
  );
  const text = createEl("div", "store-head-text");
  text.appendChild(createEl("h3", "store-title", `${state.marketStore.sellerName}的小店`));
  text.appendChild(createEl("p", "store-subtitle", `店主：${state.marketStore.sellerName}`));
  top.appendChild(text);
  header.appendChild(top);
  header.appendChild(createEl("p", "store-subtitle", `当前在售：${sellerListings.length} 件商品`));

  const backBtn = createEl("button", "btn-tonal", "返回店铺列表");
  backBtn.type = "button";
  backBtn.addEventListener("click", () => {
    resetStoreDetail();
    renderStorefronts(listings);
  });
  header.appendChild(backBtn);
  elements.marketList.appendChild(header);
  const listingsHost = createEl("div", "store-listings");
  elements.marketList.appendChild(listingsHost);
  renderListings(sellerListings, listingsHost);
}

async function loadProducts(options = {}) {
  const announce = !!options.announce;
  try {
    await ensureMaterialNameMap();
    const payload = await api("/api/products", { method: "GET" });
    state.products = payload.products || [];
    renderProducts(state.products);
    state.hasLoadedProducts = true;
    log(`官方商品已加载：${state.products.length} 个。`);
    if (announce) {
      notify(`官方商品已刷新，共 ${state.products.length} 个。`, "info");
    }
  } catch (error) {
    const message = resolveErrorMessage(error, "products_load");
    log(formatAppTemplate("productsLoadFailed", { message }), "ERROR");
    if (announce) {
      notify(formatAppTemplate("productsLoadFailed", { message }), "error");
    }
  }
}

async function loadMarket(mode, options = {}) {
  const announce = !!options.announce;
  try {
    await ensureMaterialNameMap();
    await ensureMarketAlgorithmGlossary();
    await ensureMarketTagsMeta();
    const normalizedMode = mode === "stores" ? "stores" : (mode === "mine" ? "mine" : "public");
    if (normalizedMode === "mine") {
      ensureToken();
    }

    const params = new URLSearchParams();
    params.set("limit", "120");
    if (normalizedMode === "mine") {
      params.set("mine", "true");
    }
    const keyword = elements.marketKeyword ? elements.marketKeyword.value.trim() : "";
    const materialInput = elements.marketMaterial ? elements.marketMaterial.value.trim() : "";
    const material = materialInput ? normalizeMaterialKey(materialInput) : "";
    const side = elements.marketSide ? String(elements.marketSide.value || "").trim().toUpperCase() : "";
    const tag = elements.marketTag ? normalizeTagCode(elements.marketTag.value) : "";
    const currency = elements.marketCurrency ? elements.marketCurrency.value.trim() : "";
    const minPrice = elements.marketMinPrice ? elements.marketMinPrice.value.trim() : "";
    const maxPrice = elements.marketMaxPrice ? elements.marketMaxPrice.value.trim() : "";
    const sortValue = elements.marketSort ? elements.marketSort.value.trim() : "";

    if (keyword) {
      params.set("keyword", keyword);
    }
    if (material) {
      params.set("material", material);
    }
    if (side === "SELL" || side === "BUY") {
      params.set("side", side);
    }
    if (tag) {
      params.set("tag", tag);
    }
    if (currency) {
      params.set("currency", currency);
    }
    if (minPrice) {
      params.set("minPrice", minPrice);
    }
    if (maxPrice) {
      params.set("maxPrice", maxPrice);
    }
    if (sortValue) {
      const [sortKey, sortOrder] = sortValue.split("_");
      if (sortKey) {
        params.set("sort", sortKey);
      }
      if (sortOrder) {
        params.set("order", sortOrder);
      }
    }

    const query = `/api/market/listings?${params.toString()}`;
    const payload = await api(query, { method: "GET" });
    const scopedListings = filterListingsByTradeScope(payload.listings || [], state.marketTradeScope);

    state.marketMode = normalizedMode;
    state.listings = scopedListings;
    state.hasLoadedMarket = true;

    if (normalizedMode !== "stores") {
      resetStoreDetail();
    }
    setMarketButtons(normalizedMode);
    if (normalizedMode === "stores") {
      if (state.marketStore.sellerKey) {
        renderSelectedStore(state.listings);
        setMetaText(elements.marketView, `${state.marketStore.sellerName} 的店铺：${state.listings.filter((listing) => createStoreKey(listing) === state.marketStore.sellerKey).length} 条`, "info");
      } else {
        renderStorefronts(state.listings);
        const storeCount = new Set(state.listings.filter((listing) => listing.status === "ACTIVE").map(createStoreKey)).size;
        setMetaText(elements.marketView, `${getMarketModeLabel("stores")}：${storeCount} 家`, "info");
      }
    } else {
      renderListings(state.listings);
      const label = getMarketModeLabel(normalizedMode);
      const visibleCount = normalizedMode === "public"
        ? state.listings.filter((listing) => !shouldHideListingInPublic(listing)).length
        : state.listings.length;
      setMetaText(elements.marketView, `${label}：${visibleCount} 条`, "info");
    }

    const label = getMarketModeLabel(normalizedMode);
    const displayCount = normalizedMode === "stores"
      ? new Set(state.listings.filter((listing) => listing.status === "ACTIVE").map(createStoreKey)).size
      : state.listings.length;
    const displayUnit = normalizedMode === "stores" ? "家" : "条";
    log(`${label}已加载：${displayCount} ${displayUnit}。`);
    if (announce) {
      notify(`${label}已刷新：${displayCount} ${displayUnit}。`, "info");
    }
  } catch (error) {
    const message = resolveErrorMessage(error, "market_load");
    setMetaText(elements.marketView, formatAppTemplate("marketLoadFailed", { message }), "error");
    log(formatAppTemplate("marketLoadFailed", { message }), "ERROR");
    if (announce) {
      notify(formatAppTemplate("marketLoadFailed", { message }), "error");
    }
  }
}

async function loadOrderPolicy() {
  try {
    const payload = await api("/api/orders/policy", { method: "GET" });
    state.orderPolicy.cooldownSeconds = Number(payload.cooldownSeconds || 0);
    state.orderPolicy.refundEnabled = !!payload.refundEnabled;
    state.orderPolicy.refundUndeliveredEnabled = !!payload.refundUndeliveredEnabled;
    state.orderPolicy.marketFeePercent = Number(payload.marketFeePercent || 0);
    state.orderPolicy.marketTaxPercent = Number(payload.marketTaxPercent || 0);
    state.orderPolicy.marketSupplyAutoRefreshThreshold = Number(payload.marketSupplyAutoRefreshThreshold || 8);
    state.orderPolicy.sharedClaimAllowed = !!payload.sharedClaimAllowed;
    state.orderPolicyReady = true;
  } catch (error) {
    state.orderPolicy.cooldownSeconds = 0;
    state.orderPolicy.refundEnabled = false;
    state.orderPolicy.refundUndeliveredEnabled = false;
    state.orderPolicy.marketFeePercent = 0;
    state.orderPolicy.marketTaxPercent = 0;
    state.orderPolicy.marketSupplyAutoRefreshThreshold = 8;
    state.orderPolicy.sharedClaimAllowed = false;
    state.orderPolicyReady = true;
  }
  if (state.hasLoadedOrders) {
    renderOrders(state.orders);
  }
}

function renderOrders(orders) {
  if (!elements.orderList) {
    return;
  }
  elements.orderList.innerHTML = "";

  if (!orders || orders.length === 0) {
    const empty = createEl("div", "empty-state", "暂无订单记录。");
    elements.orderList.appendChild(empty);
    setMetaText(elements.orderView, "暂无订单", "info");
    return;
  }

  for (const order of orders) {
    const statusMeta = orderStatusMeta(order.status);
    const card = createEl("article", "order-card");
    const header = createEl("div", "order-header");

    const isMarket = String(order.productType || "").toUpperCase() === "MARKET";
    const sourceLabel = isMarket ? "玩家市场" : "官方商城";
    const codeLabel = isMarket ? "交易号" : "订单号";
    const skuLabel = isMarket ? "上架ID" : "SKU";
    let title = order.productTitle || "";
    if (isMarket && order.itemMaterial) {
      title = getLocalizedMaterialName(order.itemMaterial);
    }
    if (!title) {
      title = "未知商品";
    }

    const titleWrap = createEl("div");
    titleWrap.appendChild(createEl("h3", "order-title", title));
    titleWrap.appendChild(
      createEl(
        "p",
        "order-sub",
        `${codeLabel} ${order.orderNo} | ${skuLabel} ${order.sku || "--"} | 来源 ${sourceLabel}`
      )
    );
    header.appendChild(titleWrap);

    const statusChip = createEl("span", `order-status ${statusMeta.tone}`, statusMeta.label);
    header.appendChild(statusChip);
    card.appendChild(header);

    card.appendChild(createEl("div", "order-price", formatCurrency(order.totalAmount, order.currency)));

    const meta = createEl("div", "order-meta");
    meta.appendChild(createEl("div", "", `数量：x${order.quantity}`));
    if (order.productRemark) {
      meta.appendChild(createEl("div", "", `备注：${order.productRemark}`));
    }
    const createdLabel = isMarket ? "交易时间" : "下单时间";
    meta.appendChild(createEl("div", "", `${createdLabel}：${formatDateTime(order.createdAt)}`));
    if (order.deliveredAt) {
      meta.appendChild(createEl("div", "", `发放时间：${formatDateTime(order.deliveredAt)}`));
    }
    if (order.refundedAt) {
      meta.appendChild(createEl("div", "", `退款时间：${formatDateTime(order.refundedAt)}`));
    }
    if (order.refundDeadline) {
      const remain = formatCountdown(order.refundDeadline);
      if (remain) {
        // meta.appendChild(createEl("div", "", `冷静期剩余：${remain}`));
      }
    }
    if (order.groupBuyVoucherCode) {
      const voucherStatus = order.groupBuyVoucherStatus || "ISSUED";
      meta.appendChild(createEl("div", "", `团购兑换码：${order.groupBuyVoucherCode}`));
      meta.appendChild(createEl("div", "", `团购兑换状态：${voucherStatus}`));
      if (order.groupBuyVoucherConsumedAt) {
        meta.appendChild(createEl("div", "", `核销时间：${formatDateTime(order.groupBuyVoucherConsumedAt)}`));
      }
    }
    card.appendChild(meta);

    const isWaitClaim = String(order.status || "").toUpperCase() === "WAIT_CLAIM";
    if (isWaitClaim && order.claimToken) {
      const claimBox = createEl("div", "claim-command");
      const command = `/ws claim ${order.claimToken}`;
      const label = createEl("div", "claim-command__label", "领取命令");
      const commandNode = createEl("code", "claim-command__code", command);
      const copyBtn = createEl("button", "btn-tonal", "复制命令");
      copyBtn.type = "button";
      copyBtn.dataset.action = "copyClaim";
      copyBtn.dataset.command = command;
      label.appendChild(commandNode);
      claimBox.appendChild(label);
      claimBox.appendChild(copyBtn);
      const hintText = state.orderPolicy.sharedClaimAllowed
        ? "已允许非本人在游戏内输入该命令代领，请谨慎分享。"
        : "命令仅限订单本人输入生效，分享给他人也无法领取。";
      claimBox.appendChild(createEl("p", "claim-command__hint", hintText));
      card.appendChild(claimBox);
    }



    let actions = null;
    if (order.groupBuyVoucherCode) {
      actions = createEl("div", "order-actions");
      const copyVoucherBtn = createEl("button", "btn-tonal", "复制团购码");
      copyVoucherBtn.type = "button";
      copyVoucherBtn.dataset.action = "copyVoucher";
      copyVoucherBtn.dataset.code = order.groupBuyVoucherCode;
      actions.appendChild(copyVoucherBtn);
    }
    if (order.canRefund) {
      if (!actions) {
        actions = createEl("div", "order-actions");
      }
      const refundBtn = createEl("button", "btn-tonal", "申请退款");
      refundBtn.type = "button";
      refundBtn.dataset.action = "refund";
      refundBtn.dataset.orderNo = order.orderNo;
      refundBtn.dataset.currency = order.currency;
      refundBtn.dataset.amount = order.totalAmount;
      actions.appendChild(refundBtn);
    }
    if (actions) {
      card.appendChild(actions);
    }

    elements.orderList.appendChild(card);
  }

  setMetaText(elements.orderView, `订单记录：${orders.length} 条`, "info");
}

function notificationTypeLabel(type) {
  const key = String(type || "GENERAL").toUpperCase();
  const labels = APP_UI_TEXT.notificationTypeLabels || {};
  if (labels[key]) {
    return localizeDisplayText(labels[key]);
  }
  if (I18N && typeof I18N.humanizeEnum === "function") {
    return I18N.humanizeEnum(key.toLowerCase());
  }
  return key;
}

function renderNotifications(notifications) {
  if (!elements.notificationsList) {
    return;
  }
  elements.notificationsList.innerHTML = "";
  if (!notifications || notifications.length === 0) {
    const empty = createEl("div", "notification-card", "当前没有通知。");
    elements.notificationsList.appendChild(empty);
    return;
  }

  for (const row of notifications) {
    const isRead = !!row.isRead;
    const card = createEl("article", `notification-card${isRead ? "" : " is-unread"}`);

    const head = createEl("div", "notification-head");
    const headMain = createEl("div", "notification-head-main");
    headMain.appendChild(createEl("h3", "notification-title", row.title || "系统通知"));
    headMain.appendChild(createEl("span", "notification-type", notificationTypeLabel(row.type)));
    head.appendChild(headMain);
    head.appendChild(createEl("p", "notification-time", formatDateTime(row.createdAt)));
    card.appendChild(head);

    card.appendChild(createEl("p", "notification-content", row.content || "-"));

    const actions = createEl("div", "notification-actions");
    actions.appendChild(
      createEl("span", `notification-state ${isRead ? "read" : "unread"}`, isRead ? "已读" : "未读")
    );
    if (!isRead) {
      const markBtn = createEl("button", "btn-tonal", "标记已读");
      markBtn.type = "button";
      markBtn.dataset.action = "markRead";
      markBtn.dataset.notificationId = String(row.id || "");
      actions.appendChild(markBtn);
    }
    card.appendChild(actions);
    elements.notificationsList.appendChild(card);
  }
}

async function refreshNotificationUnreadCount(options = {}) {
  const silent = !!options.silent;
  if (!state.token) {
    state.unreadNotificationCount = 0;
    updateNotificationBadge();
    return;
  }
  try {
    const payload = await api("/api/notifications/unread-count", { method: "GET" });
    state.unreadNotificationCount = Math.max(0, Number(payload.unreadCount || 0));
    updateNotificationBadge();
  } catch (error) {
    if (!silent) {
      const message = resolveErrorMessage(error, "notifications_load");
      log(formatAppTemplate("notificationUnreadRefreshFailed", { message }), "WARN");
    }
  }
}

async function loadNotifications(options = {}) {
  const announce = !!options.announce;
  const silent = !!options.silent;
  try {
    ensureToken();
    const payload = await api("/api/notifications/list?limit=50", { method: "GET" });
    state.notifications = payload.notifications || [];
    state.hasLoadedNotifications = true;
    const unreadFromResponse = Number(payload.unreadCount);
    if (Number.isFinite(unreadFromResponse)) {
      state.unreadNotificationCount = Math.max(0, unreadFromResponse);
    } else {
      state.unreadNotificationCount = state.notifications.filter((item) => !item.isRead).length;
    }
    updateNotificationBadge();
    renderNotifications(state.notifications);
    setMetaText(elements.notificationsView, `通知中心：${state.notifications.length} 条`, "info");
    if (announce && !silent) {
      notify(`通知已刷新：${state.notifications.length} 条。`, "info");
    }
  } catch (error) {
    const message = resolveErrorMessage(error, "notifications_load");
    setMetaText(elements.notificationsView, formatAppTemplate("notificationsLoadFailed", { message }), "error");
    if (announce && !silent) {
      notify(formatAppTemplate("notificationsLoadFailed", { message }), "error");
    }
    if (!silent) {
      log(formatAppTemplate("notificationsLoadFailed", { message }), "ERROR");
    }
  }
}

async function markNotificationRead(notificationId) {
  ensureToken();
  const targetId = Number(notificationId || 0);
  if (!Number.isFinite(targetId) || targetId <= 0) {
    throw new Error("通知 ID 无效。");
  }
  const payload = await api("/api/notifications/mark-read", {
    method: "POST",
    body: JSON.stringify({
      id: targetId,
    }),
  });
  state.notifications = state.notifications.map((item) => (
    Number(item.id) === targetId
      ? { ...item, isRead: true, readAt: item.readAt || new Date().toISOString() }
      : item
  ));
  if (Object.prototype.hasOwnProperty.call(payload, "unreadCount")) {
    state.unreadNotificationCount = Math.max(0, Number(payload.unreadCount || 0));
  } else {
    state.unreadNotificationCount = state.notifications.filter((item) => !item.isRead).length;
  }
  updateNotificationBadge();
  renderNotifications(state.notifications);
  setMetaText(elements.notificationsView, `通知中心：${state.notifications.length} 条`, "info");
}

async function markAllNotificationsRead() {
  ensureToken();
  const payload = await api("/api/notifications/mark-read", {
    method: "POST",
    body: JSON.stringify({
      all: true,
    }),
  });
  state.notifications = state.notifications.map((item) => ({
    ...item,
    isRead: true,
    readAt: item.readAt || new Date().toISOString(),
  }));
  if (Object.prototype.hasOwnProperty.call(payload, "unreadCount")) {
    state.unreadNotificationCount = Math.max(0, Number(payload.unreadCount || 0));
  } else {
    state.unreadNotificationCount = 0;
  }
  updateNotificationBadge();
  renderNotifications(state.notifications);
  setMetaText(elements.notificationsView, `通知中心：${state.notifications.length} 条`, "info");
}

async function loadOrders(options = {}) {
  const announce = !!options.announce;
  try {
    ensureToken();
    await ensureMaterialNameMap();
    const payload = await api("/api/orders/list?limit=50", { method: "GET" });
    state.orders = payload.orders || [];
    renderOrders(state.orders);
    state.hasLoadedOrders = true;
    const cooldownSeconds = Number(payload.cooldownSeconds || 0);
    if (Number.isFinite(cooldownSeconds)) {
      state.orderPolicy.cooldownSeconds = cooldownSeconds;
      state.orderPolicy.refundUndeliveredEnabled = !!payload.refundUndeliveredEnabled;
      state.orderPolicy.refundEnabled =
        state.orderPolicy.refundUndeliveredEnabled || cooldownSeconds > 0;
      if (Object.prototype.hasOwnProperty.call(payload, "sharedClaimAllowed")) {
        state.orderPolicy.sharedClaimAllowed = !!payload.sharedClaimAllowed;
      }
    }
    log(`订单记录已加载：${state.orders.length} 条。`);
    if (announce) {
      notify(`订单记录已刷新：${state.orders.length} 条。`, "info");
    }
  } catch (error) {
    const message = resolveErrorMessage(error, "orders_load");
    setMetaText(elements.orderView, formatAppTemplate("ordersLoadFailed", { message }), "error");
    log(formatAppTemplate("ordersLoadFailed", { message }), "ERROR");
    if (announce) {
      notify(formatAppTemplate("ordersLoadFailed", { message }), "error");
    }
  }
}

async function confirmPurchase(product, quantity) {
  const productVisual = resolveProductDisplayVisual(product);
  const displayTitle = productVisual.title || product.title || product.sku || "未知商品";
  const qty = Number(quantity || 1);
  const productType = String(product.productType || "").toUpperCase();
  const isRecycle = isRecycleProductType(productType);
  const unitPrice = resolveOfficialProductUnitPrice(product);
  const subtotalAmount = unitPrice * qty;
  const cooldown = Number(state.orderPolicy.cooldownSeconds || 0);
  const allowClaim = !isRecycle && productType !== "GROUP_BUY_VOUCHER";
  const currentBalance = getWalletBalanceForCurrency(product.currency);
  const details = [
    `商品：${displayTitle}`,
    `SKU：${product.sku}`,
    `数量：x${qty}`,
    `${isRecycle ? "回收单价" : "单价"}：${formatCurrency(unitPrice, product.currency)}`,
  ];
  const perUserLimit = Number(product?.perUserLimit);
  const personalRemaining = Number(product?.personalLimitRemaining);
  if (Number.isFinite(perUserLimit) && perUserLimit > 0) {
    details.push(`单人限购：x${Math.floor(perUserLimit)}`);
    if (Number.isFinite(personalRemaining)) {
      details.push(`当前可购买剩余：x${Math.max(0, Math.floor(personalRemaining))}`);
    }
  }
  if (product.remark) {
    details.push(`备注：${product.remark}`);
  }
  if (productType === "GROUP_BUY_VOUCHER") {
    details.push("该商品会生成团购兑换码，需由管理员在后台核销。");
  }
  if (isRecycle) {
    details.push("回收说明：系统将从背包扣除对应物品并入账。");
    details.push("退款说明：回收订单即时结算，不支持退款。");
  } else if (state.orderPolicy.refundUndeliveredEnabled) {
    details.push("退款说明：未发放前可退款。");
  } else if (cooldown > 0) {
    details.push(`冷静期：${cooldown} 秒（仅冷静期内可退款）`);
  } else {
    details.push("退款说明：当前不支持未发放退款。");
  }
  return openDeliveryConfirmDialog({
    title: isRecycle ? "确认回收" : "确认下单",
    message: isRecycle
      ? "请确认以下回收信息，确认后将立即入账。"
      : "请确认以下订单信息，确认后将立即扣除余额。",
    details,
    confirmText: isRecycle ? "确认回收" : "确认下单",
    initialValue: defaultDeliveryModeForProduct(product),
    allowClaim,
    summary: {
      currency: product.currency,
      subtotal: subtotalAmount,
      taxAmount: 0,
      feeAmount: 0,
      taxLabel: "税额（买家承担）",
      feeLabel: "手续费（卖家承担）",
      finalLabel: isRecycle ? "预计入账" : "最终扣款",
      finalAmount: subtotalAmount,
      currentBalance,
      remainingBalance: isRecycle ? currentBalance + subtotalAmount : currentBalance - subtotalAmount,
      isCredit: isRecycle,
      noteText: isRecycle ? "确认后将按照以上金额入账。" : undefined,
    },
  });
}

async function confirmMarketBuy(listing, buyQuantity) {
  await ensureMaterialNameMap();
  const meta = parseMeta(listing.itemMetaJson);
  const displayName = stripColorCodes(meta.displayName || "");
  const listingVisual = resolveListingDisplayVisual(listing, displayName);
  const localizedName = listingVisual.title;
  const qty = Number(buyQuantity || 1);
  const cooldown = Number(state.orderPolicy.cooldownSeconds || 0);
  const subtotalAmount = Number(listing.price || 0) * qty;
  const taxAmount = calculatePercentAmount(subtotalAmount, state.orderPolicy.marketTaxPercent);
  const feeAmount = calculatePercentAmount(subtotalAmount, state.orderPolicy.marketFeePercent);
  const finalAmount = subtotalAmount + taxAmount;
  const currentBalance = getWalletBalanceForCurrency(listing.currency);
  const details = [
    `物品：${localizedName}`,
    `数量：x${qty}`,
    `单价：${formatCurrency(listing.price, listing.currency)}`,
    `卖家：${listing.sellerName}`,
  ];
  if (listing.remark) {
    details.push(`备注：${listing.remark}`);
  }
  if (state.orderPolicy.refundUndeliveredEnabled) {
    details.push("退款说明：未发放前可退款。");
  } else if (cooldown > 0) {
    details.push(`冷静期：${cooldown} 秒（仅冷静期内可退款）`);
  } else {
    details.push("退款说明：当前不支持未发放退款。");
  }
  return openDeliveryConfirmDialog({
    title: "确认购买",
    message: "请确认以下交易信息，确认后将立即扣除余额。",
    details,
    confirmText: "确认购买",
    initialValue: "IMMEDIATE",
    allowClaim: true,
    summary: {
      currency: listing.currency,
      subtotal: subtotalAmount,
      taxAmount,
      feeAmount,
      taxLabel: `税额（买家承担，${state.orderPolicy.marketTaxPercent}%）`,
      feeLabel: `手续费（卖家承担，${state.orderPolicy.marketFeePercent}%）`,
      finalAmount,
      currentBalance,
      remainingBalance: currentBalance - finalAmount,
    },
  });
}

async function confirmSellToBuy(listing, sellQuantity) {
  await ensureMaterialNameMap();
  const meta = parseMeta(listing.itemMetaJson);
  const displayName = stripColorCodes(meta.displayName || "");
  const listingVisual = resolveListingDisplayVisual(listing, displayName);
  const localizedName = listingVisual.title;
  const qty = Number(sellQuantity || 1);
  const subtotalAmount = Number(listing.price || 0) * qty;
  const taxAmount = calculatePercentAmount(subtotalAmount, state.orderPolicy.marketTaxPercent);
  const feeAmount = calculatePercentAmount(subtotalAmount, state.orderPolicy.marketFeePercent);
  const sellerReceive = Math.max(0, subtotalAmount - feeAmount);
  const details = [
    `物品：${localizedName}`,
    `数量：x${qty}`,
    `收购单价：${formatCurrency(listing.price, listing.currency)}`,
    `发布者：${listing.sellerName}`,
    "你交货后会直接获得货款，物品会进入对方收货队列。",
  ];
  if (listing.remark) {
    details.push(`备注：${listing.remark}`);
  }
  return openDeliveryConfirmDialog({
    title: "确认卖给收购单",
    message: "请确认交货信息，确认后将立即从你背包扣除对应物品。",
    details,
    confirmText: "确认交货",
    initialValue: "IMMEDIATE",
    allowClaim: true,
    summary: {
      currency: listing.currency,
      subtotal: subtotalAmount,
      taxAmount,
      feeAmount,
      taxLabel: `税额（买家承担，${state.orderPolicy.marketTaxPercent}%）`,
      feeLabel: `手续费（卖家承担，${state.orderPolicy.marketFeePercent}%）`,
      finalLabel: "预计入账",
      finalAmount: sellerReceive,
      isCredit: true,
      noteText: "本次成交会扣减收购单冻结金额，剩余冻结金额会在收购单结束后退回发布者。",
    },
  });
}

async function confirmMarketBid(listing, bidAmount) {
  await ensureMaterialNameMap();
  const meta = parseMeta(listing.itemMetaJson);
  const displayName = stripColorCodes(meta.displayName || "");
  const listingVisual = resolveListingDisplayVisual(listing, displayName);
  const localizedName = listingVisual.title;
  const auctionAlgorithm = String(listing.auctionAlgorithm || "ENGLISH_AUCTION_V1").toUpperCase();
  const isSealedBid = auctionAlgorithm === "VICKREY_AUCTION_V1";
  const minIncrement = Math.max(1, Number(listing.auctionMinIncrement || 1));
  const hasHighestBid = Number(listing.auctionHighestBid || 0) > 0;
  const currentBid = Math.max(1, Number(listing.auctionStartPrice || listing.price || 0));
  const visibleBid = hasHighestBid ? Number(listing.auctionHighestBid || 0) : currentBid;
  const requiredBid = isSealedBid
    ? currentBid
    : (hasHighestBid ? visibleBid + minIncrement : Math.max(1, currentBid));
  const currentBalance = getWalletBalanceForCurrency(listing.currency);
  const details = [
    `物品：${localizedName}`,
    `卖家：${listing.sellerName}`,
    `算法：${getAlgorithmLabel("auction", auctionAlgorithm)}`,
    `起拍价：${formatCurrency(currentBid, listing.currency)}`,
  ];
  if (!isSealedBid) {
    details.push(`当前${hasHighestBid ? "最高出价" : "可出价"}：${formatCurrency(visibleBid, listing.currency)}`);
    details.push(`最小加价幅度：${formatCurrency(minIncrement, listing.currency)}`);
  }
  details.push(`本次出价：${formatCurrency(bidAmount, listing.currency)}`);
  details.push(`最低有效出价：${formatCurrency(requiredBid, listing.currency)}`);
  details.push(`当前余额：${formatCurrency(currentBalance, listing.currency)}`);
  details.push(`预计出价后余额：${formatCurrency(currentBalance - bidAmount, listing.currency)}`);
  details.push(`拍卖截止：${listing.auctionEndAt ? formatDateTime(listing.auctionEndAt) : "未设置"}`);

  return openConfirmDialog({
    title: "确认竞拍出价",
    message: isSealedBid
      ? "此算法为密封出价，其他玩家与前端不会显示你的出价金额。"
      : "出价后将先冻结该金额，若被超价系统会自动退回。",
    details,
    confirmText: isSealedBid ? "提交密封出价" : "确认出价",
  });
}

async function refundOrder(orderNo) {
  ensureToken();
  const payload = await api("/api/orders/refund", {
    method: "POST",
    body: JSON.stringify({ orderNo }),
  });
  updateWalletView(payload);
  notify(`退款成功：${payload.orderNo}`, "success");
  await loadOrders();
  await loadProducts();
}

async function createOrder(productId, quantity, deliveryMode, productTitle, productType) {
  ensureToken();

  const pid = Number(productId);
  const qty = Number(quantity || 1);
  const isRecycle = isRecycleProductType(productType);
  const actionVerb = isRecycle ? "回收" : "下单";
  const actionSuccess = isRecycle ? "回收成功" : "下单成功";
  if (!Number.isFinite(pid) || pid <= 0) {
    throw new Error("商品 ID 无效。");
  }
  if (!Number.isFinite(qty) || qty <= 0) {
    throw new Error("购买数量无效。");
  }

  const payload = await api("/api/orders", {
    method: "POST",
    body: JSON.stringify({
      productId: pid,
      quantity: qty,
      deliveryMode: String(deliveryMode || "").toUpperCase() || undefined,
      idempotencyKey: createIdempotencyKey(),
    }),
  });

  const isExisting = String(payload.state || "").toUpperCase() === "EXISTING";
  const orderStatus = payload.orderStatus || payload.state;
  const groupBuyVoucherCode = payload.groupBuyVoucherCode || null;
  const itemText = productTitle ? `${productTitle} x${qty}` : `数量 x${qty}`;
  const summary = `订单 ${payload.orderNo} | ${itemText} | 总额 ${formatCurrency(payload.totalAmount, payload.currency)} | ${orderStatus}`;
  setMetaText(elements.orderView, summary, isExisting ? "warn" : "success");
  if (isExisting) {
    log(`${actionVerb}请求去重，返回历史订单：${summary}`, "WARN");
    notify(`订单已存在：${payload.orderNo}，${itemText}，总额 ${formatCurrency(payload.totalAmount, payload.currency)}。`, "warn");
  } else {
    log(`${actionSuccess}：${summary}`, "SUCCESS");
    const claimTip = orderStatus === "WAIT_CLAIM" ? "，请在游戏内使用 /ws claim 领取。" : "";
    notify(`${actionSuccess}：${summary}${claimTip}`, "success");
  }
  if (groupBuyVoucherCode) {
    notify(`已生成团购兑换码：${groupBuyVoucherCode}`, "success", 5200);
    log(`团购兑换码已生成：${groupBuyVoucherCode}`, "SUCCESS");
  }

  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(formatAppTemplate("postOrderWalletRefreshFailed", { message: refreshMessage }), "WARN");
  }
  try {
    await loadOrders();
  } catch (orderError) {
    const orderMessage = resolveErrorMessage(orderError, "orders_load");
    log(formatAppTemplate("postOrderOrdersLoadFailed", { message: orderMessage }), "WARN");
  }
  try {
    await loadProducts();
  } catch (productError) {
    const productMessage = resolveErrorMessage(productError, "products_load");
    log(formatAppTemplate("postOrderProductsRefreshFailed", { message: productMessage }), "WARN");
  }
  return payload;
}

async function openCreateBuyListingDialog() {
  await ensureMaterialNameMap();
  await ensureMarketMaterialAllowList();
  await ensureMarketTagsMeta();
  const preferredCurrency = String(elements.marketCurrency?.value || "GAME_COIN").trim().toUpperCase();
  return openMarketParamDialog({
    title: "发布收购单",
    hint: "在网页直接发布收购单。出售单仍需在游戏内创建。",
    confirmText: "发布收购单",
    setupForm: (container) => {
      container.innerHTML = "";

      const materialField = createEl("label", "field");
      const materialInput = document.createElement("input");
      materialInput.id = "marketCreateBuyMaterialInput";
      materialInput.placeholder = "请从预选材质中选择";
      materialInput.setAttribute("list", "materialSuggestList");
      materialInput.autocomplete = "off";
      materialInput.addEventListener("blur", () => {
        const raw = String(materialInput.value || "").trim();
        if (!raw) {
          return;
        }
        const resolved = resolveMarketAllowedMaterial(raw);
        if (resolved) {
          materialInput.value = resolved;
          return;
        }
        notify("该材质不在可选列表内，请从预选建议中选择。", "warn");
      });
      materialField.appendChild(materialInput);
      materialField.appendChild(createEl("span", "field-label", "收购物品"));
      container.appendChild(materialField);

      const row = createEl("div", "field-grid three-col field-grid-smart");

      const priceField = createEl("label", "field");
      const priceInput = document.createElement("input");
      priceInput.id = "marketCreateBuyPriceInput";
      priceInput.type = "number";
      priceInput.min = "1";
      priceInput.step = "1";
      priceInput.value = "1";
      priceField.appendChild(priceInput);
      priceField.appendChild(createEl("span", "field-label", "单价"));
      row.appendChild(priceField);

      const quantityField = createEl("label", "field");
      const quantityInput = document.createElement("input");
      quantityInput.id = "marketCreateBuyQuantityInput";
      quantityInput.type = "number";
      quantityInput.min = "1";
      quantityInput.max = "64";
      quantityInput.step = "1";
      quantityInput.value = "1";
      quantityField.appendChild(quantityInput);
      quantityField.appendChild(createEl("span", "field-label", "数量"));
      row.appendChild(quantityField);

      const currencyField = createEl("label", "field field-select");
      const currencySelect = document.createElement("select");
      currencySelect.id = "marketCreateBuyCurrencySelect";
      ["SHOP_COIN", "GAME_COIN"].forEach((currencyKey) => {
        const option = document.createElement("option");
        option.value = currencyKey;
        option.textContent = (CURRENCY_META[currencyKey] || { label: currencyKey }).label;
        currencySelect.appendChild(option);
      });
      currencySelect.value = preferredCurrency === "SHOP_COIN" || preferredCurrency === "GAME_COIN"
        ? preferredCurrency
        : "GAME_COIN";
      currencyField.appendChild(currencySelect);
      currencyField.appendChild(createEl("span", "field-label", "币种"));
      row.appendChild(currencyField);

      container.appendChild(row);

      const tagField = createEl("label", "field field-select");
      const tagSelect = document.createElement("select");
      tagSelect.id = "marketCreateBuyTagSelect";
      tagSelect.innerHTML = "<option value=\"\">自动分类</option>";
      (state.marketTags || [])
        .filter((tag) => tag.enabled !== false)
        .forEach((tag) => {
          const option = document.createElement("option");
          option.value = tag.code;
          option.textContent = `${tag.displayName || tag.code} (${tag.code})`;
          tagSelect.appendChild(option);
        });
      tagField.appendChild(tagSelect);
      tagField.appendChild(createEl("span", "field-label", "分类标签（可选）"));
      container.appendChild(tagField);

      const tip = createEl("p", "meta", "提示：发布后其他玩家可直接“卖给收购单”，你的冻结金额会随成交递减。");
      container.appendChild(tip);
      materialInput.focus();
      return container;
    },
    resolveValue: (container) => {
      const materialInput = container.querySelector("#marketCreateBuyMaterialInput");
      const priceInput = container.querySelector("#marketCreateBuyPriceInput");
      const quantityInput = container.querySelector("#marketCreateBuyQuantityInput");
      const currencySelect = container.querySelector("#marketCreateBuyCurrencySelect");
      const tagSelect = container.querySelector("#marketCreateBuyTagSelect");

      const itemMaterial = resolveMarketAllowedMaterial(materialInput ? materialInput.value : "");
      const price = Number(priceInput ? priceInput.value : 0);
      const quantity = Number(quantityInput ? quantityInput.value : 0);
      const currency = String(currencySelect ? currencySelect.value : "SHOP_COIN").trim().toUpperCase();
      const tag = normalizeTagCode(tagSelect ? tagSelect.value : "");

      if (!itemMaterial) {
        notify("请从预选材质中选择一个有效物品。", "warn");
        return undefined;
      }
      if (!Number.isFinite(price) || price <= 0) {
        notify("单价必须大于 0。", "warn");
        return undefined;
      }
      if (!Number.isFinite(quantity) || quantity <= 0 || quantity > 64) {
        notify("数量需在 1-64 之间。", "warn");
        return undefined;
      }
      if (currency !== "SHOP_COIN" && currency !== "GAME_COIN") {
        notify("币种无效，请重新选择。", "warn");
        return undefined;
      }
      return {
        itemMaterial,
        price: Math.floor(price),
        quantity: Math.floor(quantity),
        currency,
        tag: tag || null,
      };
    },
  });
}

async function confirmCreateBuyListing(params) {
  const materialName = getLocalizedMaterialName(params.itemMaterial, { includeGlobalOverride: false });
  const subtotal = Math.max(0, Number(params.price || 0) * Number(params.quantity || 0));
  const taxAmount = calculatePercentAmount(subtotal, state.orderPolicy.marketTaxPercent);
  const escrowAmount = subtotal + taxAmount;
  const details = [
    `收购物品：${materialName} (${params.itemMaterial})`,
    `数量：x${params.quantity}`,
    `单价：${formatCurrency(params.price, params.currency)}`,
    `小计：${formatCurrency(subtotal, params.currency)}`,
    `税额（买家承担，${state.orderPolicy.marketTaxPercent}%）：${formatCurrency(taxAmount, params.currency)}`,
    `预计冻结：${formatCurrency(escrowAmount, params.currency)}`,
    `分类：${params.tag ? `${getMarketTagDisplayName(params.tag)} (${params.tag})` : "自动分类"}`,
  ];
  return openConfirmDialog({
    title: "确认发布收购单",
    message: "确认后会立即创建收购单并冻结对应金额。",
    details,
    confirmText: "确认发布",
  });
}

async function createBuyListing(params) {
  ensureToken();
  const payload = await api("/api/market/listings/create", {
    method: "POST",
    body: JSON.stringify({
      side: "BUY",
      tradeMode: "DIRECT",
      itemMaterial: params.itemMaterial,
      price: params.price,
      quantity: params.quantity,
      currency: params.currency,
      tag: params.tag || undefined,
    }),
  });
  const priceText = formatCurrency(payload.price || params.price, payload.currency || params.currency);
  const quantity = Number(payload.quantity || params.quantity || 0);
  const tagCode = normalizeTagCode(payload.tag || params.tag);
  const tagText = tagCode ? `，分类 ${getMarketTagDisplayName(tagCode)}` : "";
  log(`收购单发布成功：listingId=${payload.listingId} material=${payload.material || params.itemMaterial} qty=${quantity}`, "SUCCESS");
  notify(`收购单发布成功：#${payload.listingId}，${params.itemMaterial} x${quantity}，单价 ${priceText}${tagText}。`, "success");
  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(formatAppTemplate("postBuyOrderWalletRefreshFailed", { message: refreshMessage }), "WARN");
  }
  await loadMarket(state.marketMode || "public");
  return payload;
}

async function buyListing(listingId, buyQuantity, deliveryMode) {
  ensureToken();
  const qty = Number(buyQuantity || 1);
  if (!Number.isFinite(qty) || qty <= 0 || qty > 64) {
    throw new Error("购买数量需在 1-64 之间。");
  }
  const payload = await api("/api/market/buy", {
    method: "POST",
    body: JSON.stringify({
      listingId,
      buyQuantity: qty,
      deliveryMode: String(deliveryMode || "IMMEDIATE").toUpperCase(),
      idempotencyKey: createIdempotencyKey(),
    }),
  });
  const isExisting = String(payload.state || "").toUpperCase() === "EXISTING";
  const paidAmount = payload.buyerTotal !== undefined ? payload.buyerTotal : payload.totalPrice;
  const amountText = formatCurrency(paidAmount, payload.currency);
  const cooldownSeconds = Number(payload.cooldownSeconds || state.orderPolicy.cooldownSeconds || 0);
  if (Number.isFinite(cooldownSeconds)) {
    state.orderPolicy.cooldownSeconds = cooldownSeconds;
    state.orderPolicy.refundEnabled =
      state.orderPolicy.refundUndeliveredEnabled || cooldownSeconds > 0;
  }
  const statusText = payload.orderStatus || "PENDING";
  const refundDeadlineText = payload.refundDeadline ? `，退款截止 ${formatDateTime(payload.refundDeadline)}` : "";
  if (isExisting) {
    log(`市场购买请求去重：tradeId=${payload.tradeId}，listingId=${payload.listingId}`, "WARN");
    notify(`该交易已处理过，返回历史结果（交易号 ${payload.tradeId}）。`, "warn");
  } else {
    log(
      `购买成功：tradeId=${payload.tradeId}，listingId=${payload.listingId}，qty=${payload.quantity || qty}`,
      "SUCCESS"
    );
    const feeAmount = Number(payload.feeAmount || 0) + Number(payload.taxAmount || 0);
    if (feeAmount > 0) {
      const claimTip = statusText === "WAIT_CLAIM" ? " 请在游戏内使用 /ws claim 领取。" : "";
      notify(
        `购买成功，数量 x${payload.quantity || qty}，实付 ${amountText}（含手续费/税收 ${formatCurrency(feeAmount, payload.currency)}），状态 ${statusText}${refundDeadlineText}。${claimTip}`,
        "success"
      );
    } else {
      const claimTip = statusText === "WAIT_CLAIM" ? " 请在游戏内使用 /ws claim 领取。" : "";
      notify(
        `购买成功，数量 x${payload.quantity || qty}，成交金额 ${amountText}，状态 ${statusText}${refundDeadlineText}。${claimTip}`,
        "success"
      );
    }
  }

  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(formatAppTemplate("postMarketBuyWalletRefreshFailed", { message: refreshMessage }), "WARN");
  }
  await loadMarket(state.marketMode);
  if (state.token) {
    await loadOrders();
  }
}

async function sellToBuyListing(listingId, sellQuantity, deliveryMode) {
  ensureToken();
  const qty = Number(sellQuantity || 1);
  if (!Number.isFinite(qty) || qty <= 0 || qty > 64) {
    throw new Error("交货数量需在 1-64 之间。");
  }
  const payload = await api("/api/market/sell-to-buy", {
    method: "POST",
    body: JSON.stringify({
      listingId,
      sellQuantity: qty,
      deliveryMode: String(deliveryMode || "IMMEDIATE").toUpperCase(),
      idempotencyKey: createIdempotencyKey(),
    }),
  });
  const isExisting = String(payload.state || "").toUpperCase() === "EXISTING";
  const receiveAmount = payload.sellerReceive !== undefined ? payload.sellerReceive : payload.totalPrice;
  const amountText = formatCurrency(receiveAmount, payload.currency);
  const statusText = payload.orderStatus || "PENDING";
  if (isExisting) {
    log(`收购单交货请求去重：tradeId=${payload.tradeId}，listingId=${payload.listingId}`, "WARN");
    notify(`该交货请求已处理过，返回历史结果（交易号 ${payload.tradeId}）。`, "warn");
  } else {
    log(
      `收购单交货成功：tradeId=${payload.tradeId}，listingId=${payload.listingId}，qty=${payload.quantity || qty}`,
      "SUCCESS"
    );
    const claimTip = statusText === "WAIT_CLAIM" ? " 对方需在游戏内使用 /ws claim 领取。" : "";
    notify(
      `交货成功，数量 x${payload.quantity || qty}，预计入账 ${amountText}，状态 ${statusText}。${claimTip}`,
      "success"
    );
  }

  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(formatAppTemplate("postSellToBuyWalletRefreshFailed", { message: refreshMessage }), "WARN");
  }
  await loadMarket(state.marketMode);
  if (state.token) {
    await loadOrders();
  }
}

async function bidListing(listingId, bidAmount) {
  ensureToken();
  const amount = Number(bidAmount || 0);
  if (!Number.isFinite(amount) || amount <= 0) {
    throw new Error("出价金额必须大于 0。 ");
  }
  const payload = await api("/api/market/bid", {
    method: "POST",
    body: JSON.stringify({
      listingId,
      bidAmount: Math.floor(amount),
      idempotencyKey: createIdempotencyKey(),
    }),
  });
  const isExisting = String(payload.state || "").toUpperCase() === "EXISTING";
  const isSealedBid = Boolean(payload.sealedBid);
  if (isExisting) {
    notify(`已识别为重复请求，沿用历史出价（#${payload.bidId}）。`, "warn");
  } else {
    if (isSealedBid) {
      const requiredHint = payload.minimumRequiredBid
        ? `，最低门槛 ${formatCurrency(payload.minimumRequiredBid, payload.currency)}`
        : "";
      notify(
        `密封出价已提交：${formatCurrency(payload.bidAmount, payload.currency)}${requiredHint}。`,
        "success"
      );
    } else {
      notify(
        `出价成功：${formatCurrency(payload.bidAmount, payload.currency)}，当前最高价 ${formatCurrency(payload.currentHighestBid, payload.currency)}。`,
        "success"
      );
    }
  }
  log(
    `竞拍出价完成：listingId=${payload.listingId} bidId=${payload.bidId} amount=${payload.bidAmount}`,
    isExisting ? "WARN" : "SUCCESS"
  );
  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(formatAppTemplate("postBidWalletRefreshFailed", { message: refreshMessage }), "WARN");
  }
  await loadMarket(state.marketMode);
}

async function unlistListing(listingId) {
  ensureToken();
  const payload = await api("/api/market/unlist", {
    method: "POST",
    body: JSON.stringify({ listingId }),
  });
  log(`下架成功：listingId=${payload.listingId}`, "SUCCESS");
  notify(`下架成功：上架 ${payload.listingId} 已加入退回队列。`, "success");
  await loadMarket(state.marketMode);
}

async function refreshSupplyListing(listingId, options = {}) {
  ensureToken();
  const payload = await api("/api/market/supply/refresh", {
    method: "POST",
    body: JSON.stringify({ listingId }),
  });
  if (!options.silent) {
    if (Number(payload.loadedAmount || 0) > 0) {
      notify(
        `补货完成：本次提取 x${payload.loadedAmount}，当前中转 x${payload.currentStock} / x${payload.maxStock}，累计提取 x${payload.loadedTotal}。`,
        "success"
      );
      log(`供货刷新成功：listingId=${payload.listingId} loaded=${payload.loadedAmount}`, "SUCCESS");
    } else {
      const isFull = Number(payload.currentStock || 0) >= Number(payload.maxStock || 0);
      notify(
        isFull
          ? `中转库存已满：当前中转 x${payload.currentStock} / x${payload.maxStock}，累计提取 x${payload.loadedTotal}。`
          : `未检测到可补货库存：当前中转 x${payload.currentStock} / x${payload.maxStock}，累计提取 x${payload.loadedTotal}。`,
        isFull ? "info" : "warn"
      );
      log(`供货刷新未补货：listingId=${payload.listingId} current=${payload.currentStock}`, "WARN");
    }
  }
  await loadMarket(state.marketMode);
  return payload;
}

async function pauseListing(listingId) {
  ensureToken();
  const payload = await api("/api/market/pause", {
    method: "POST",
    body: JSON.stringify({ listingId }),
  });
  log(`暂停上架：listingId=${payload.listingId}`, "INFO");
  notify("已暂时停用该上架，物品保留在市场后台，可随时重新上架。", "info");
  await loadMarket(state.marketMode);
}

async function resumeListing(listingId) {
  ensureToken();
  const payload = await api("/api/market/resume", {
    method: "POST",
    body: JSON.stringify({ listingId }),
  });
  log(`重新上架：listingId=${payload.listingId}`, "SUCCESS");
  notify("上架已恢复，其他玩家可以再次购买。", "success");
  await loadMarket(state.marketMode);
}

async function updateListingPrice(listingId, price) {
  ensureToken();
  const payload = await api("/api/market/price", {
    method: "POST",
    body: JSON.stringify({ listingId, price }),
  });
  log(`改价成功：listingId=${payload.listingId} price=${payload.price}`, "SUCCESS");
  notify(`改价成功：新价格 ${formatCurrency(payload.price, payload.currency)}。`, "success");
  await loadMarket(state.marketMode);
}

async function updateListingRemark(listingId, remark) {
  ensureToken();
  const payload = await api("/api/market/remark", {
    method: "POST",
    body: JSON.stringify({ listingId, remark }),
  });
  const remarkText = payload.remark ? payload.remark : "（空）";
  log(`备注更新成功：listingId=${payload.listingId}`, "SUCCESS");
  notify(`备注已更新：${remarkText}`, "success");
  await loadMarket(state.marketMode);
}

async function updateListing(
  listingId,
  price,
  currency,
  remark,
  displayNameOverride,
  displayMaterial,
  displayIconPath,
  supplyBatchSize,
  supplyMaxStock,
  tradeMode,
  dynamicPricingEnabled,
  dynamicAlgorithm,
  dynamicParamsJson,
  dynamicBasePrice,
  dynamicFloorPrice,
  dynamicCapPrice,
  dynamicPriceStep,
  auctionAlgorithm,
  auctionParamsJson,
  auctionStartPrice,
  auctionMinIncrement,
  auctionEndAt
) {
  ensureToken();
  const payload = await api("/api/market/settings", {
    method: "POST",
    body: JSON.stringify({
      listingId,
      price,
      currency,
      remark,
      displayNameOverride,
      displayMaterial,
      displayIconPath,
      supplyBatchSize,
      supplyMaxStock,
      tradeMode,
      dynamicPricingEnabled,
      dynamicAlgorithm,
      dynamicParamsJson,
      dynamicBasePrice,
      dynamicFloorPrice,
      dynamicCapPrice,
      dynamicPriceStep,
      auctionAlgorithm,
      auctionParamsJson,
      auctionStartPrice,
      auctionMinIncrement,
      auctionEndAt,
    }),
  });
  log(`修改成功：listingId=${listingId} price=${payload.price} mode=${payload.tradeMode}`, "SUCCESS");
  notify("修改成功：上架参数已更新。", "success");
  await loadMarket(state.marketMode);
}

if (elements.loginBtn) {
  elements.loginBtn.addEventListener("click", async () => {
  try {
    const identifier = elements.loginIdentifier.value.trim();
    const password = elements.loginPassword.value.trim();
    if (!identifier) {
      throw new Error("请输入用户名。");
    }
    if (!password) {
      throw new Error("请输入密码。");
    }
    const payload = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ identifier, password }),
    });

    setSession(payload);
    await refreshWallet();
    await loadWalletLedger();
    await loadOrders();
    await loadNotifications({ silent: true });
    try {
      await loadProducts();
    } catch (productError) {
      const productMessage = resolveErrorMessage(productError, "products_load");
      log(formatAppTemplate("postLoginProductsRefreshFailed", { message: productMessage }), "WARN");
    }
    log("登录成功。", "SUCCESS");
    notify("登录成功。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "login");
    log(formatAppTemplate("loginFailed", { message }), "ERROR");
    notify(formatAppTemplate("loginFailed", { message }), "error");
  }
  });
}

elements.logoutBtn.addEventListener("click", async () => {
  try {
    ensureToken();
    await api("/api/auth/logout", {
      method: "POST",
      body: JSON.stringify({}),
    });
    clearSession();
    try {
      await loadProducts();
    } catch (productError) {
      const productMessage = resolveErrorMessage(productError, "products_load");
      log(formatAppTemplate("postLogoutProductsRefreshFailed", { message: productMessage }), "WARN");
    }
    switchTab("auth");
    log("已退出登录。", "SUCCESS");
    notify("已退出登录。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "logout");
    log(formatAppTemplate("logoutFailed", { message }), "ERROR");
    notify(formatAppTemplate("logoutFailed", { message }), "error");
  }
});

document.getElementById("walletBtn").addEventListener("click", async () => {
  try {
    await refreshWallet();
    await loadWalletLedger();
    log("钱包余额已刷新。", "SUCCESS");
    notify("钱包余额已刷新。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "wallet_refresh");
    log(formatAppTemplate("walletRefreshFailed", { message }), "ERROR");
    notify(formatAppTemplate("walletRefreshFailed", { message }), "error");
  }
});

if (elements.rechargeAmount) {
  elements.rechargeAmount.addEventListener("input", updateRechargeCoinPreview);
}
if (elements.rechargePaymentCurrency) {
  elements.rechargePaymentCurrency.addEventListener("change", () => {
    updateRechargeAmountLabel();
    updateRechargeCoinPreview();
  });
}
if (elements.rechargePaymentMethod) {
  elements.rechargePaymentMethod.addEventListener("change", updateRechargeCoinPreview);
}

if (elements.rechargePayLink) {
  elements.rechargePayLink.addEventListener("click", () => {
    showRechargePaymentDialog();
  });
}

if (elements.rechargePaymentDialogClose) {
  elements.rechargePaymentDialogClose.addEventListener("click", () => {
    setRechargePaymentDialogVisible(false);
  });
}

if (elements.rechargePaymentDialogOpen) {
  elements.rechargePaymentDialogOpen.addEventListener("click", openCurrentRechargePaymentPage);
}

if (elements.rechargePaymentDialogCancel) {
  elements.rechargePaymentDialogCancel.addEventListener("click", async () => {
    try {
      await cancelCurrentRechargeOrder();
    } catch (error) {
      const message = resolveErrorMessage(error, "operation");
      notify(formatAppTemplate("rechargeCancelFailed", { message }), "error");
    }
  });
}

if (elements.rechargePaymentDialog) {
  elements.rechargePaymentDialog.addEventListener("click", (event) => {
    if (event.target === elements.rechargePaymentDialog) {
      setRechargePaymentDialogVisible(false);
    }
  });
}

if (elements.rechargeBtn) {
  elements.rechargeBtn.addEventListener("click", async () => {
    try {
      ensureToken();
      const amountMinor = parseRechargeAmountMinor();
      const currency = currentRechargeCurrency();
      const paymentMethod = currentRechargeMethod();
      if (!hasRechargeRateForCombo(currency, paymentMethod)) {
        setMetaText(
          elements.rechargeView,
          getAppPageText("rechargeComboUnavailableTip", APP_UI_TEXT.initMeta.rechargeView || ""),
          "info"
        );
        return;
      }
      setMetaText(elements.rechargeView, formatAppTemplate("rechargeCreating"), "info");
      setRechargePayLink(null);
      state.recharge.currentPayment = null;
      const payload = await api("/api/recharge/create", {
        method: "POST",
        body: JSON.stringify({
          amountMinor,
          currency,
          paymentMethod,
          locale: I18N ? I18N.getLocale() : "zh-CN",
        }),
      });
      state.recharge.currentOrderId = payload.orderId;
      state.recharge.currentPayment = {
        orderId: payload.orderId,
        payUrl: payload.payUrl,
        qrCodeUrl: payload.qrCodeUrl,
        amountMinor,
        currency,
        expireTime: payload.expireTime,
        status: "PAYING",
      };
      setRechargePayLink(payload.payUrl);
      setMetaText(
        elements.rechargeView,
        formatAppTemplate("rechargeCreated", { orderId: payload.orderId }),
        "success"
      );
      showRechargePaymentDialog(state.recharge.currentPayment);
    } catch (error) {
      const message = resolveErrorMessage(error, "operation");
      const detail = formatAppTemplate("rechargeFailed", { message });
      setMetaText(elements.rechargeView, detail, "error");
      notify(detail, "error");
    }
  });
}

if (elements.rechargeStatusBtn) {
  elements.rechargeStatusBtn.addEventListener("click", async () => {
    try {
      await refreshRechargeStatus();
    } catch (error) {
      const message = resolveErrorMessage(error, "operation");
      setMetaText(
        elements.rechargeView,
        formatAppTemplate("rechargeStatusFailed", { message }),
        "error"
      );
    }
  });
}

document.getElementById("redeemBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const code = document.getElementById("redeemCode").value.trim();
    if (!code) {
      throw new Error("请输入兑换码后再提交。");
    }
    const previousWallet = {
      shopCoin: state.walletBalance.shopCoin,
      gameCoin: state.walletBalance.gameCoin,
    };
    const payload = await api("/api/redeem/use", {
      method: "POST",
      body: JSON.stringify({ code }),
    });

    updateWalletView(payload);
    await loadWalletLedger();
    const tip = redeemStatusTip(payload.status);
    const deltaText = summarizeWalletDelta(payload, previousWallet);
    const balanceText = formatWalletInline(payload.shopCoin, payload.gameCoin);
    const detailText = tip.tone === "success"
      ? `${tip.text}${deltaText ? ` 本次入账：${deltaText}。` : " "}当前余额：${balanceText}。`
      : tip.text;
    setMetaText(elements.redeemView, detailText, tip.tone);
    log(`兑换结果：${detailText}`, tip.tone === "success" ? "SUCCESS" : "WARN");
    notify(detailText, tip.tone);
  } catch (error) {
    const message = resolveErrorMessage(error, "redeem");
    setMetaText(elements.redeemView, formatAppTemplate("redeemFailed", { message }), "error");
    log(formatAppTemplate("redeemFailed", { message }), "ERROR");
    notify(formatAppTemplate("redeemFailed", { message }), "error");
  }
});

document.getElementById("exchangeBtn").addEventListener("click", async () => {
  try {
    ensureToken();

    const fromCurrency = String(elements.exchangeFrom?.value || "").trim();
    const toCurrency = String(elements.exchangeTo?.value || "").trim();
    const amount = Number(String(elements.exchangeAmount?.value || "").trim());

    if (!Number.isFinite(amount) || amount <= 0) {
      throw new Error("兑换数量必须大于 0。");
    }
    if (fromCurrency === toCurrency) {
      throw new Error("兑换方向不能相同。");
    }

    if (!state.exchangeMetaLoaded) {
      await loadCurrencyMeta();
    }
    const direction = resolveExchangeDirectionSettings(fromCurrency, toCurrency);
    if (!direction) {
      throw new Error("兑换方向无效，请重新选择。");
    }
    if (!direction.enabled) {
      throw new Error("当前服务器未开放该兑换方向。");
    }

    const convertedAmount = Math.floor(amount * Number(direction.ratio || 0));
    if (!Number.isFinite(convertedAmount) || convertedAmount <= 0) {
      throw new Error("兑换比例导致结果为 0，请增大兑换数量。");
    }

    const fromBalance = getWalletBalanceForCurrency(fromCurrency);
    const toBalance = getWalletBalanceForCurrency(toCurrency);
    const confirmed = await openExchangeConfirmDialog({
      fromCurrency,
      toCurrency,
      amount,
      convertedAmount,
      ratio: direction.ratio,
      fromBalance,
      toBalance,
    });
    if (!confirmed) {
      setMetaText(elements.exchangeView, "已取消兑换。", "warn");
      return;
    }

    const previousWallet = {
      shopCoin: state.walletBalance.shopCoin,
      gameCoin: state.walletBalance.gameCoin,
    };
    const payload = await api("/api/wallet/exchange", {
      method: "POST",
      body: JSON.stringify({
        fromCurrency,
        toCurrency,
        amount,
        idempotencyKey: createIdempotencyKey(),
      }),
    });

    updateWalletView(payload);
    await loadWalletLedger();
    const deltaText = summarizeWalletDelta(payload, previousWallet);
    const balanceText = formatWalletInline(payload.shopCoin, payload.gameCoin);
    const successText = `兑换成功：${formatCurrency(amount, fromCurrency)} -> ${formatCurrency(convertedAmount, toCurrency)}${deltaText ? `，余额变动 ${deltaText}` : ""}。当前余额：${balanceText}。`;
    setMetaText(
      elements.exchangeView,
      successText,
      "success"
    );
    log(successText, "SUCCESS");
    notify(successText, "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "exchange");
    setMetaText(elements.exchangeView, formatAppTemplate("exchangeFailed", { message }), "error");
    log(formatAppTemplate("exchangeFailed", { message }), "ERROR");
    notify(formatAppTemplate("exchangeFailed", { message }), "error");
  }
});

if (elements.exchangeFrom) {
  elements.exchangeFrom.addEventListener("change", updateExchangeRateHint);
}
if (elements.exchangeTo) {
  elements.exchangeTo.addEventListener("change", updateExchangeRateHint);
}

document.getElementById("productsBtn").addEventListener("click", () => {
  loadProducts({ announce: true });
});
if (elements.productSearchBtn) {
  elements.productSearchBtn.addEventListener("click", () => renderProducts(state.products));
}
if (elements.productKeywordClearBtn) {
  elements.productKeywordClearBtn.addEventListener("click", () => {
    if (elements.productKeyword) {
      elements.productKeyword.value = "";
      elements.productKeyword.focus();
    }
    renderProducts(state.products);
  });
}
if (elements.productApplyBtn) {
  elements.productApplyBtn.addEventListener("click", () => renderProducts(state.products));
}
if (elements.productClearBtn) {
  elements.productClearBtn.addEventListener("click", () => {
    if (elements.productKeyword) elements.productKeyword.value = "";
    if (elements.productSort) elements.productSort.value = "default";
    if (elements.productFilterType) elements.productFilterType.value = "";
    if (elements.productFilterCurrency) elements.productFilterCurrency.value = "";
    if (elements.productFilterMaterial) elements.productFilterMaterial.value = "";
    if (elements.productMinPrice) elements.productMinPrice.value = "";
    if (elements.productMaxPrice) elements.productMaxPrice.value = "";
    renderProducts(state.products);
  });
}
[
  elements.productSort,
  elements.productFilterType,
  elements.productFilterCurrency,
].filter(Boolean).forEach((node) => {
  node.addEventListener("change", () => renderProducts(state.products));
});
[
  elements.productKeyword,
  elements.productFilterMaterial,
  elements.productMinPrice,
  elements.productMaxPrice,
].filter(Boolean).forEach((node) => {
  node.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      renderProducts(state.products);
    }
  });
});
if (elements.ordersBtn) {
  elements.ordersBtn.addEventListener("click", () => {
    loadOrders({ announce: true });
  });
}
if (elements.notificationsRefreshBtn) {
  elements.notificationsRefreshBtn.addEventListener("click", () => {
    loadNotifications({ announce: true });
  });
}
if (elements.notificationsMarkAllBtn) {
  elements.notificationsMarkAllBtn.addEventListener("click", async () => {
    try {
      if (state.unreadNotificationCount <= 0) {
        notify("当前没有未读通知。", "info");
        return;
      }
      await markAllNotificationsRead();
      notify("已全部标记为已读。", "success");
    } catch (error) {
      const message = resolveErrorMessage(error, "notifications_mark_read");
      notify(formatAppTemplate("markAllReadFailed", { message }), "error");
      log(formatAppTemplate("markAllReadFailed", { message }), "ERROR");
    }
  });
}
if (elements.notificationsList) {
  elements.notificationsList.addEventListener("click", async (event) => {
    const button = event.target.closest("button[data-action='markRead']");
    if (!button || button.disabled) {
      return;
    }
    const notificationId = Number(button.dataset.notificationId || 0);
    if (!Number.isFinite(notificationId) || notificationId <= 0) {
      return;
    }
    button.disabled = true;
    try {
      await markNotificationRead(notificationId);
    } catch (error) {
      const message = resolveErrorMessage(error, "notifications_mark_read");
      notify(formatAppTemplate("markReadFailed", { message }), "error");
      log(formatAppTemplate("markReadFailed", { message }), "ERROR");
    } finally {
      button.disabled = false;
    }
  });
}
document.getElementById("marketListBtn").addEventListener("click", () => {
  loadMarket("public", { announce: true });
});
if (elements.marketStoreBtn) {
  elements.marketStoreBtn.addEventListener("click", () => {
    loadMarket("stores", { announce: true });
  });
}
document.getElementById("marketMineBtn").addEventListener("click", () => {
  loadMarket("mine", { announce: true });
});
document.getElementById("marketRefreshBtn").addEventListener("click", () => {
  loadMarket(state.marketMode || "public", { announce: true });
});
if (elements.marketSearchBtn) {
  elements.marketSearchBtn.addEventListener("click", () => {
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketKeywordClearBtn) {
  elements.marketKeywordClearBtn.addEventListener("click", () => {
    if (elements.marketKeyword) {
      elements.marketKeyword.value = "";
      elements.marketKeyword.focus();
    }
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketKeyword) {
  elements.marketKeyword.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      loadMarket(state.marketMode || "public", { announce: true });
    }
  });
}
if (elements.marketSort) {
  elements.marketSort.addEventListener("change", () => {
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketSide) {
  elements.marketSide.addEventListener("change", () => {
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketTag) {
  elements.marketTag.addEventListener("change", () => {
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketApplyBtn) {
  elements.marketApplyBtn.addEventListener("click", () => {
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketClearBtn) {
  elements.marketClearBtn.addEventListener("click", () => {
    if (elements.marketKeyword) elements.marketKeyword.value = "";
    if (elements.marketSide) elements.marketSide.value = "";
    if (elements.marketTag) elements.marketTag.value = "";
    if (elements.marketMaterial) elements.marketMaterial.value = "";
    if (elements.marketCurrency) elements.marketCurrency.value = "";
    if (elements.marketMinPrice) elements.marketMinPrice.value = "";
    if (elements.marketMaxPrice) elements.marketMaxPrice.value = "";
    if (elements.marketSort) elements.marketSort.value = "created_desc";
    if (elements.marketHideOwnToggle) {
      elements.marketHideOwnToggle.checked = true;
      state.hideOwnMarketListings = true;
      window.localStorage.setItem(MARKET_HIDE_OWN_STORAGE_KEY, "1");
    }
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketCreateBuyBtn) {
  elements.marketCreateBuyBtn.addEventListener("click", async () => {
    try {
      ensureToken();
      const params = await openCreateBuyListingDialog();
      if (!params) {
        notify("已取消发布收购单。", "info");
        return;
      }
      const confirmed = await confirmCreateBuyListing(params);
      if (!confirmed) {
        notify("已取消发布收购单。", "info");
        return;
      }
      elements.marketCreateBuyBtn.disabled = true;
      setNodeText(elements.marketCreateBuyBtn, "发布中...");
      await createBuyListing(params);
    } catch (error) {
      const message = resolveErrorMessage(error, "market_create");
      notify(formatAppTemplate("buyOrderCreateFailed", { message }), "error");
      log(formatAppTemplate("buyOrderCreateFailed", { message }), "ERROR");
    } finally {
      elements.marketCreateBuyBtn.disabled = false;
      setNodeText(elements.marketCreateBuyBtn, "发布收购单");
    }
  });
}

if (elements.leaderboardRefreshBtn) {
  elements.leaderboardRefreshBtn.addEventListener("click", () => {
    loadLeaderboard().then(() => {
      notify("排行榜已刷新。", "success");
    }).catch((error) => {
      const message = resolveErrorMessage(error, "leaderboard");
      notify(formatAppTemplate("loadFailed", { message }), "error");
    });
  });
}

if (elements.leaderboardMyRankBtn) {
  elements.leaderboardMyRankBtn.addEventListener("click", () => {
    locateMyLeaderboardRank();
  });
}

[
  elements.leaderboardMetric,
  elements.leaderboardOrder,
  elements.leaderboardRange,
].filter(Boolean).forEach((node) => {
  node.addEventListener("change", () => {
    loadLeaderboard().catch(() => {
      // handled by next manual refresh
    });
  });
});

if (elements.leaderboardShowOnlineToggle) {
  elements.leaderboardShowOnlineToggle.addEventListener("change", () => {
    loadLeaderboard().catch(() => {
      // handled by next manual refresh
    });
  });
}

elements.productList.addEventListener("click", async (event) => {
  const button = event.target.closest(".product-buy-btn");
  if (!button) {
    return;
  }

  const card = button.closest(".product-card");
  if (!card) {
    return;
  }

  const qtyInput = card.querySelector(".product-qty");
  const quantity = qtyInput ? qtyInput.value : "1";
  const productId = Number(button.dataset.productId);
  const product = state.products.find((item) => Number(item.id) === productId);
  if (!product) {
    notify("商品信息异常，请刷新商品列表。", "warn");
    return;
  }
  const isRecycle = isRecycleProductType(product.productType);
  const stock = resolveOfficialProductStock(product);
  const maxQuantity = stock.maxQuantity;
  if (maxQuantity <= 0) {
    notify("该商品已售罄，请刷新后查看。", "warn");
    return;
  }
  const qtyValue = Number(quantity || 1);
  if (!Number.isFinite(qtyValue) || qtyValue < 1 || qtyValue > maxQuantity) {
    notify(`购买数量需在 1-${maxQuantity} 之间。`, "warn");
    return;
  }

  ensureToken();
  const deliveryMode = await confirmPurchase(product, qtyValue);
  if (!deliveryMode) {
    notify(isRecycle ? "已取消回收。" : "已取消下单。", "info");
    return;
  }

  const originalText = button.textContent;
  button.disabled = true;
  setNodeText(button, isRecycle ? "回收中..." : "下单中...");
  try {
    const productDisplayTitle = resolveProductDisplayVisual(product).title || product.title;
    await createOrder(productId, qtyValue, deliveryMode, productDisplayTitle, product.productType);
  } catch (error) {
    const message = resolveErrorMessage(error, "order_create");
    const failPrefix = isRecycle ? "回收失败" : "下单失败";
    setMetaText(elements.orderView, `${failPrefix}：${message}`, "error");
    log(`${failPrefix}：${message}`, "ERROR");
    notify(`${failPrefix}：${message}`, "error");
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
});

if (elements.orderList) {
  elements.orderList.addEventListener("click", async (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) {
      return;
    }
    if (button.dataset.action === "copyVoucher") {
      try {
        await copyTextToClipboard(button.dataset.code || "");
        notify("团购兑换码已复制。", "success");
      } catch (error) {
        notify(error.message || "复制失败，请手动复制。", "error");
      }
      return;
    }
    if (button.dataset.action === "copyClaim") {
      try {
        await copyTextToClipboard(button.dataset.command || "");
        notify("领取命令已复制，可以在游戏内直接粘贴。", "success");
      } catch (error) {
        notify(error.message || "复制失败，请手动复制领取命令。", "error");
      }
      return;
    }
    if (button.dataset.action !== "refund") {
      return;
    }
    const orderNo = button.dataset.orderNo;
    const amount = Number(button.dataset.amount || 0);
    const currency = button.dataset.currency || "SHOP_COIN";
    const confirmed = await openConfirmDialog({
      title: "确认退款",
      message: "确认后将撤销发放并退回余额。",
      details: [
        `订单号：${orderNo}`,
        `退款金额：${formatCurrency(amount, currency)}`,
      ],
      confirmText: "确认退款",
    });
    if (!confirmed) {
      notify("已取消退款操作。", "info");
      return;
    }
    button.disabled = true;
    setNodeText(button, "退款中...");
    try {
      await refundOrder(orderNo);
    } catch (error) {
      const message = resolveErrorMessage(error, "order_refund");
      log(formatAppTemplate("refundFailed", { message }), "ERROR");
      notify(formatAppTemplate("refundFailed", { message }), "error");
    } finally {
      button.disabled = false;
      setNodeText(button, "申请退款");
    }
  });
}

elements.marketList.addEventListener("click", async (event) => {
  const button = event.target.closest(".market-action-btn");
  if (!button || button.disabled) {
    return;
  }
  if (!button.dataset.action) {
    return;
  }

  const listingId = Number(button.dataset.listingId);
  if (!Number.isFinite(listingId) || listingId <= 0) {
    log(formatAppTemplate("marketActionInvalidListingId"), "ERROR");
    notify(formatAppTemplate("marketActionInvalidListingId"), "error");
    return;
  }

  const originalText = button.textContent;
  button.disabled = true;
  setNodeText(button, "处理中...");
  try {
    if (button.dataset.action === "buy" || button.dataset.action === "sellToBuy") {
      let listing = state.listings.find((item) => Number(item.id) === listingId);
      const card = button.closest(".market-card");
      const qtyInput = card ? card.querySelector(".product-qty") : null;
      const rawQty = qtyInput ? qtyInput.value : "1";
      const buyQty = Number(rawQty || 1);
      const maxQty = Number(button.dataset.maxQuantity || 64);
      if (!Number.isFinite(buyQty) || buyQty <= 0 || buyQty > Math.max(1, maxQty)) {
        throw new Error(`购买数量需在 1-${Math.max(1, maxQty)} 之间。`);
      }
      ensureToken();
      const isSupply = String(listing?.sourceMode || "").toUpperCase() === "SUPPLY";
      const currentQty = Number(listing?.quantity || 0);
      const threshold = Math.max(0, Number(state.orderPolicy.marketSupplyAutoRefreshThreshold || 0));
      if (isSupply && currentQty > 0 && currentQty < threshold) {
        await refreshSupplyListing(listingId, { silent: true });
        listing = state.listings.find((item) => Number(item.id) === listingId) || listing;
      }
      const isSellToBuy = button.dataset.action === "sellToBuy"
        || normalizeListingSide(listing) === "BUY";
      const confirmed = listing
        ? (isSellToBuy
            ? await confirmSellToBuy(listing, buyQty)
            : await confirmMarketBuy(listing, buyQty))
        : await openConfirmDialog({
            title: isSellToBuy ? "确认卖给收购单" : "确认购买",
            message: isSellToBuy ? "确认后将立即扣除交货物品。" : "确认后将立即扣除余额。",
            details: [`上架ID：${listingId}`, `数量：x${buyQty}`],
            confirmText: isSellToBuy ? "确认交货" : "确认购买",
          });
      if (!confirmed) {
        notify(isSellToBuy ? "已取消交货。" : "已取消购买。", "info");
        return;
      }
      const selectedMode = typeof confirmed === "string" ? confirmed : "IMMEDIATE";
      if (isSellToBuy) {
        await sellToBuyListing(listingId, buyQty, selectedMode);
      } else {
        await buyListing(listingId, buyQty, selectedMode);
      }
      return;
    }
    if (button.dataset.action === "bid") {
      const listing = state.listings.find((item) => Number(item.id) === listingId);
      const card = button.closest(".market-card");
      const bidInput = card ? card.querySelector(".market-bid-input") : null;
      const bidAmount = bidInput ? Number(bidInput.value || 0) : 0;
      const minBid = Number(button.dataset.minBid || 1);
      if (!Number.isFinite(bidAmount) || bidAmount < Math.max(1, minBid)) {
        throw new Error(`出价必须大于等于 ${formatCurrency(Math.max(1, minBid), button.dataset.currency || "GAME_COIN")}。`);
      }
      ensureToken();
      const confirmed = listing
        ? await confirmMarketBid(listing, bidAmount)
        : await openConfirmDialog({
          title: "确认竞拍出价",
          message: "确认后将冻结本次出价金额。",
          details: [`上架ID：${listingId}`, `出价：${formatCurrency(bidAmount, button.dataset.currency || "GAME_COIN")}`],
          confirmText: "确认出价",
        });
      if (!confirmed) {
        notify("已取消出价。", "info");
        return;
      }
      await bidListing(listingId, bidAmount);
      return;
    }
    if (button.dataset.action === "unlist") {
      await unlistListing(listingId);
      return;
    }
    if (button.dataset.action === "refreshSupply") {
      await refreshSupplyListing(listingId);
      return;
    }
    if (button.dataset.action === "pause") {
      await pauseListing(listingId);
      return;
    }
    if (button.dataset.action === "resume") {
      await resumeListing(listingId);
      return;
    }
    if (button.dataset.action === "edit") {
      const currentPrice = Number(button.dataset.currentPrice || 0);
      const currency = button.dataset.currency || "SHOP_COIN";
      const currentRemark = button.dataset.currentRemark || "";
      const result = await openListingEditDialog({
        listingId,
        currentPrice,
        currency,
        currentRemark,
        sourceMode: button.dataset.sourceMode || "MANUAL",
        currentSupplyBatchSize: Number(button.dataset.currentSupplyBatchSize || 0) || null,
        currentSupplyMaxStock: Number(button.dataset.currentSupplyMaxStock || 0) || null,
        tradeMode: button.dataset.tradeMode || "DIRECT",
        currentDynamicPricingEnabled: button.dataset.dynamicPricingEnabled === "1",
        currentDynamicAlgorithm: button.dataset.dynamicAlgorithm || "LINEAR_DEMAND_V1",
        currentDynamicParamsJson: button.dataset.dynamicParamsJson || null,
        currentDynamicBasePrice: Number(button.dataset.dynamicBasePrice || 0) || null,
        currentDynamicFloorPrice: Number(button.dataset.dynamicFloorPrice || 0) || null,
        currentDynamicCapPrice: Number(button.dataset.dynamicCapPrice || 0) || null,
        currentDynamicPriceStep: Number(button.dataset.dynamicPriceStep || 0) || null,
        currentAuctionAlgorithm: button.dataset.auctionAlgorithm || "ENGLISH_AUCTION_V1",
        currentAuctionParamsJson: button.dataset.auctionParamsJson || null,
        currentAuctionStartPrice: Number(button.dataset.auctionStartPrice || 0) || null,
        currentAuctionMinIncrement: Number(button.dataset.auctionMinIncrement || 0) || null,
        currentAuctionEndAt: button.dataset.auctionEndAt || null,
        currentDisplayNameOverride: button.dataset.currentDisplayNameOverride || "",
        currentDisplayMaterial: button.dataset.currentDisplayMaterial || "",
        currentDisplayIconPath: button.dataset.currentDisplayIconPath || "",
        currentItemMaterial: button.dataset.currentItemMaterial || "",
        currentFallbackTitle: button.dataset.currentFallbackTitle || "",
      });
      if (!result) {
        notify("已取消修改。", "info");
        return;
      }
      let displayIconPath = result.displayIconPath || null;
      if (result.pendingDisplayIconFile) {
        const query = new URLSearchParams({
          listingId: String(listingId),
          filename: result.pendingDisplayIconFile.name || `listing-${listingId}.png`,
        });
        const uploadPayload = await apiUpload(`/api/market/icon/upload?${query.toString()}`, result.pendingDisplayIconFile);
        displayIconPath = uploadPayload.displayIconPath || null;
      }
      await updateListing(
        listingId,
        result.price,
        result.currency,
        result.remark,
        result.displayNameOverride,
        result.displayMaterial,
        displayIconPath,
        result.supplyBatchSize,
        result.supplyMaxStock,
        result.tradeMode,
        result.dynamicPricingEnabled,
        result.dynamicAlgorithm,
        result.dynamicParamsJson,
        result.dynamicBasePrice,
        result.dynamicFloorPrice,
        result.dynamicCapPrice,
        result.dynamicPriceStep,
        result.auctionAlgorithm,
        result.auctionParamsJson,
        result.auctionStartPrice,
        result.auctionMinIncrement,
        result.auctionEndAt
      );
    }
  } catch (error) {
    const scene = button.dataset.action === "unlist"
      ? "market_unlist"
      : button.dataset.action === "refreshSupply"
        ? "market_supply"
      : button.dataset.action === "bid"
        ? "market_bid"
      : button.dataset.action === "sellToBuy"
        ? "market_sell_to_buy"
      : button.dataset.action === "edit"
        ? "market_price"
        : "market_buy";
    const message = resolveErrorMessage(error, scene);
    log(formatAppTemplate("marketActionFailed", { message }), "ERROR");
    notify(formatAppTemplate("marketActionFailed", { message }), "error");
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
});

if (elements.confirmCancelBtn) {
  elements.confirmCancelBtn.addEventListener("click", () => closeConfirmDialog(false));
}
if (elements.confirmOkBtn) {
  elements.confirmOkBtn.addEventListener("click", () => {
    if (confirmSubmitHandler) {
      confirmSubmitHandler();
      return;
    }
    closeConfirmDialog(true);
  });
}
if (elements.confirmDialog) {
  elements.confirmDialog.addEventListener("click", (event) => {
    if (event.target === elements.confirmDialog) {
      closeConfirmDialog(false);
    }
  });
}

if (elements.marketParamCancelBtn) {
  elements.marketParamCancelBtn.addEventListener("click", () => closeMarketParamDialog(null));
}
if (elements.marketParamSaveBtn) {
  elements.marketParamSaveBtn.addEventListener("click", () => {
    if (marketParamSubmitHandler) {
      marketParamSubmitHandler();
      return;
    }
    closeMarketParamDialog(null);
  });
}
if (elements.marketParamDialog) {
  elements.marketParamDialog.addEventListener("click", (event) => {
    if (event.target === elements.marketParamDialog) {
      closeMarketParamDialog(null);
    }
  });
}

if (elements.priceDialogCancel) {
  elements.priceDialogCancel.addEventListener("click", () => closePriceDialog(null));
}
if (elements.priceDialogConfirm) {
  elements.priceDialogConfirm.addEventListener("click", submitPriceDialog);
}
if (elements.priceDialogInput) {
  elements.priceDialogInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      submitPriceDialog();
    }
  });
}
if (elements.priceDialog) {
  elements.priceDialog.addEventListener("click", (event) => {
    if (event.target === elements.priceDialog) {
      closePriceDialog(null);
    }
  });
}

if (elements.themeToggleBtn) {
  elements.themeToggleBtn.addEventListener("click", toggleTheme);
}
if (elements.themeSelect) {
  elements.themeSelect.addEventListener("change", () => {
    applyThemePackage(elements.themeSelect.value);
  });
}

setupHeaderOverflowMenu();
applyTheme(getInitialTheme());
applyThemePackage(getSavedThemePackage(), { skipStorage: true });
loadThemeCenterStateFromServer().catch(() => {
  // ignore theme center bootstrap errors
});
state.hideOwnMarketListings = getInitialHideOwnMarketListings();
if (elements.marketHideOwnToggle) {
  elements.marketHideOwnToggle.checked = state.hideOwnMarketListings;
  elements.marketHideOwnToggle.addEventListener("change", () => {
    state.hideOwnMarketListings = !!elements.marketHideOwnToggle.checked;
    window.localStorage.setItem(MARKET_HIDE_OWN_STORAGE_KEY, state.hideOwnMarketListings ? "1" : "0");
    if (state.marketMode === "public") {
      renderListings(state.listings);
      const visibleCount = state.listings.filter((listing) => !shouldHideListingInPublic(listing)).length;
      setMetaText(elements.marketView, `${getMarketModeLabel("public")}：${visibleCount} 条`, "info");
    }
  });
}
setAuthMode("login");
updateAuthLayout();
updateMarketSectionContext();
setMetaText(elements.walletView, APP_UI_TEXT.initMeta.walletView, "info");
setMetaText(elements.walletLedgerView, APP_UI_TEXT.initMeta.walletLedgerView, "info");
setMetaText(elements.rechargeView, APP_UI_TEXT.initMeta.rechargeView, "info");
renderRechargeSelectors();
updateRechargeCoinPreview();
setMetaText(elements.redeemView, APP_UI_TEXT.initMeta.redeemView, "info");
setMetaText(elements.exchangeRateHint, APP_UI_TEXT.initMeta.exchangeRateHint, "info");
setMetaText(elements.exchangeView, APP_UI_TEXT.initMeta.exchangeView, "info");
setMetaText(elements.orderView, APP_UI_TEXT.initMeta.orderView, "info");
setMetaText(elements.notificationsView, APP_UI_TEXT.initMeta.notificationsView, "info");
setMetaText(elements.marketView, APP_UI_TEXT.initMeta.marketView, "info");
if (elements.leaderboardView) {
  setMetaText(elements.leaderboardView, APP_UI_TEXT.initMeta.leaderboardView, "info");
}
if (elements.leaderboardMyRankView) {
  setNodeText(elements.leaderboardMyRankView, APP_UI_TEXT.initMeta.leaderboardMyRank);
}
renderNotifications(state.notifications);
updateNotificationBadge();
ensureMaterialNameMap();
ensureMarketMaterialAllowList();
ensureMarketTagsMeta();
loadOrderPolicy();
loadLeaderboardConfig().catch(() => {
  // ignore config bootstrap errors
});
loadCurrencyMeta().finally(() => {
  loadProducts();
  loadMarket("public");
});
// 尝试恢复会话
restoreSession();
