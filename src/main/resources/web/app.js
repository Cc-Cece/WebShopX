const state = {
  token: null,
  username: null,
  boundUuid: null,
  activeTab: "auth",
  authMode: "login",
  marketMode: "public",
  listings: [],
  products: [],
  orders: [],
  orderPolicy: {
    cooldownSeconds: 0,
    refundEnabled: false,
  },
  orderPolicyReady: false,
  zhNameMap: {},
  zhNameMapReady: false,
  zhNameMapPromise: null,
  hasLoadedProducts: false,
  hasLoadedMarket: false,
  hasLoadedOrders: false,
  theme: "light",
  registration: {
    bindCode: null,
    username: null,
    boundUuid: null,
    status: "IDLE",
    pollTimer: null,
  },
};

const CURRENCY_META = {
  SHOP_COIN: {
    label: "ShopCoin",
    short: "SC",
  },
  GAME_COIN: {
    label: "GameCoin",
    short: "GC",
  },
};

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

const FALLBACK_TEXTURE =
  "data:image/svg+xml;utf8,"
  + encodeURIComponent(
    "<svg xmlns='http://www.w3.org/2000/svg' width='96' height='96'>"
      + "<rect width='96' height='96' fill='#eef2fb'/>"
      + "<rect x='10' y='10' width='76' height='76' fill='#d4dceb'/>"
      + "<text x='48' y='57' text-anchor='middle' font-size='36' fill='#60708b'>?</text>"
    + "</svg>");

const FALLBACK_AVATAR =
  "data:image/svg+xml;utf8,"
  + encodeURIComponent(
    "<svg xmlns='http://www.w3.org/2000/svg' width='96' height='96'>"
      + "<rect width='96' height='96' fill='#edf2fb'/>"
      + "<circle cx='48' cy='35' r='18' fill='#bcc9de'/>"
      + "<rect x='22' y='58' width='52' height='24' rx='10' fill='#bcc9de'/>"
    + "</svg>");

const AVATAR_BASE = "https://nmsr.nickac.dev/face/";

const ERROR_TIPS_COMMON = {
  auth_required: "请先登录后再操作。",
  auth_invalid: "登录状态已失效，请重新登录。",
  invalid_credentials: "账号或密码错误，请检查后重试。",
  not_bound: "当前账号还未绑定 Minecraft 角色。",
  insufficient_funds: "余额不足，请先充值或兑换后再试。",
  internal_error: "服务器内部错误，请稍后重试。",
  method_not_allowed: "请求方式错误，请刷新页面后重试。",
  wallet_missing: "钱包不存在，请联系管理员检查数据。",
  user_missing: "账号数据不存在，请联系管理员处理。",
};

const ERROR_TIPS_BY_SCENE = {
  register_start: {
    invalid_username: "注册流程无需输入昵称，请直接获取绑定码。",
    username_exists: "该 MC 名称已被注册或绑定，请直接登录。",
    bad_request: "注册参数不完整，请刷新页面后重试。",
  },
  register_finish: {
    invalid_code: "绑定码无效，请重新获取绑定码。",
    wait_bind: "尚未完成游戏内绑定，请先在游戏执行 /webshopx bind <code>（或 /ws bind <code>）。",
    already_completed: "该绑定码已完成注册，请直接登录。",
    invalid_password: "密码长度需为 8-64 位。",
    invalid_state: "注册流程状态异常，请重新开始注册。",
  },
  login: {
    invalid_identifier: "请输入 UUID 或用户名。",
    invalid_password: "密码长度需为 8-64 位。",
  },
  order_create: {
    invalid_quantity: "购买数量需在 1-64 之间。",
    product_missing: "该商品已下架或不可购买。",
    not_bound: "账号未绑定游戏角色，无法下单。",
    idempotency_too_long: "请求参数异常，请刷新后重试。",
    player_offline: "回收类商品需玩家在线且背包满足回收条件。",
    insufficient_item: "回收失败：背包物品不足。",
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
    refund_disabled: "当前订单未开启冷静期，无法退款。",
    refund_expired: "冷静期已结束，无法退款。",
    refund_not_allowed: "当前订单状态不支持退款。",
    already_refunded: "该订单已退款。",
    order_missing: "未找到该订单，请刷新后再试。",
  },
  market_buy: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架已下架或已售出。",
    invalid_trade: "不能购买自己上架的物品。",
    invalid_idempotency: "请求参数异常，请刷新后重试。",
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
    forbidden: "仅上架者本人可以改价。",
    invalid_price: "价格必须大于 0。",
  },
  market_remark: {
    invalid_listing: "上架 ID 无效，请刷新列表后重试。",
    listing_missing: "该上架不存在，可能已被移除。",
    listing_unavailable: "该上架已下架或已售出。",
    forbidden: "仅上架者本人可以编辑备注。",
    invalid_remark: "备注长度超出限制。",
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
};

const REDEEM_STATUS_TIPS = {
  SUCCESS: { tone: "success", text: "兑换成功，资产已入账。" },
  INVALID_CODE: { tone: "warn", text: "兑换失败：兑换码无效。" },
  EXPIRED: { tone: "warn", text: "兑换失败：兑换码已过期。" },
  OUT_OF_STOCK: { tone: "warn", text: "兑换失败：兑换码已领完。" },
  ALREADY_USED: { tone: "warn", text: "兑换失败：你已使用过该兑换码。" },
  USER_LIMIT_REACHED: { tone: "warn", text: "兑换失败：你已达到该兑换码的个人使用上限。" },
};

const ORDER_STATUS_LABELS = {
  PENDING: { label: "待发放", tone: "pending" },
  DELIVERED: { label: "已发放", tone: "delivered" },
  REFUNDED: { label: "已退款", tone: "refunded" },
  FAILED: { label: "失败", tone: "failed" },
  RECYCLED: { label: "已回收", tone: "delivered" },
};

const elements = {
  logBox: document.getElementById("logBox"),
  statusChip: document.getElementById("statusChip"),
  themeToggleBtn: document.getElementById("themeToggleBtn"),

  authEntryCard: document.getElementById("authEntryCard"),
  authProfileCard: document.getElementById("authProfileCard"),
  authModeLoginBtn: document.getElementById("authModeLoginBtn"),
  authModeRegisterBtn: document.getElementById("authModeRegisterBtn"),
  authLoginPanel: document.getElementById("authLoginPanel"),
  authRegisterPanel: document.getElementById("authRegisterPanel"),

  loginIdentifier: document.getElementById("loginIdentifier"),
  loginPassword: document.getElementById("loginPassword"),
  loginBtn: document.getElementById("loginBtn"),

  registerStartBtn: document.getElementById("registerStartBtn"),
  registerBindCopyBtn: document.getElementById("registerBindCopyBtn"),
  registerBindCodeView: document.getElementById("registerBindCodeView"),
  registerStatusView: document.getElementById("registerStatusView"),
  registerFlowTip: document.getElementById("registerFlowTip"),
  registerPasswordStage: document.getElementById("registerPasswordStage"),
  registerPassword: document.getElementById("registerPassword"),
  registerPasswordConfirm: document.getElementById("registerPasswordConfirm"),
  registerFinishBtn: document.getElementById("registerFinishBtn"),

  profileAvatar: document.getElementById("profileAvatar"),
  profileName: document.getElementById("profileName"),
  profileUuid: document.getElementById("profileUuid"),
  logoutBtn: document.getElementById("logoutBtn"),

  walletView: document.getElementById("walletView"),
  redeemView: document.getElementById("redeemView"),
  exchangeView: document.getElementById("exchangeView"),
  orderView: document.getElementById("orderView"),
  ordersBtn: document.getElementById("ordersBtn"),
  orderList: document.getElementById("orderList"),
  marketView: document.getElementById("marketView"),
  shopCoinValue: document.getElementById("shopCoinValue"),
  gameCoinValue: document.getElementById("gameCoinValue"),
  productList: document.getElementById("productList"),
  marketList: document.getElementById("marketList"),
  marketKeyword: document.getElementById("marketKeyword"),
  marketMaterial: document.getElementById("marketMaterial"),
  marketCurrency: document.getElementById("marketCurrency"),
  marketMinPrice: document.getElementById("marketMinPrice"),
  marketMaxPrice: document.getElementById("marketMaxPrice"),
  marketSort: document.getElementById("marketSort"),
  marketSearchBtn: document.getElementById("marketSearchBtn"),
  marketKeywordClearBtn: document.getElementById("marketKeywordClearBtn"),
  marketApplyBtn: document.getElementById("marketApplyBtn"),
  marketClearBtn: document.getElementById("marketClearBtn"),
  snackbarHost: document.getElementById("snackbarHost"),
  confirmDialog: document.getElementById("confirmDialog"),
  confirmTitle: document.getElementById("confirmTitle"),
  confirmMessage: document.getElementById("confirmMessage"),
  confirmDetails: document.getElementById("confirmDetails"),
  confirmCancelBtn: document.getElementById("confirmCancelBtn"),
  confirmOkBtn: document.getElementById("confirmOkBtn"),
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
};

const tabs = Array.from(document.querySelectorAll(".top-tab"));
const panels = Array.from(document.querySelectorAll(".tab-panel"));

function log(message, level = "INFO") {
  const now = new Date();
  const prefix = `${now.toLocaleString("zh-CN", { hour12: false })} [${level}]`;
  const line = `${prefix} ${message}`;
  elements.logBox.textContent = `${line}\n${elements.logBox.textContent}`.slice(0, 30000);
}

function setMetaText(element, text, tone = "info") {
  if (!element) {
    return;
  }
  element.textContent = text;
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
const SESSION_STORAGE_KEY = "webshopx_session";

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
  document.body.dataset.theme = normalized;
  window.localStorage.setItem(THEME_STORAGE_KEY, normalized);
  if (elements.themeToggleBtn) {
    elements.themeToggleBtn.textContent = normalized === "dark" ? "切换亮色" : "切换暗色";
  }
}

function toggleTheme() {
  applyTheme(state.theme === "dark" ? "light" : "dark");
}

async function copyTextToClipboard(text) {
  const value = String(text || "").trim();
  if (!value) {
    throw new Error("没有可复制的内容。");
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

function openConfirmDialog({ title, message, details = [], confirmText = "确认" }) {
  if (!elements.confirmDialog) {
    return Promise.resolve(window.confirm(`${title}\n${message}`));
  }
  if (confirmResolver) {
    confirmResolver(false);
    confirmResolver = null;
  }

  elements.confirmTitle.textContent = title;
  elements.confirmMessage.textContent = message;
  elements.confirmDetails.innerHTML = "";
  details.filter(Boolean).forEach((line) => {
    elements.confirmDetails.appendChild(createEl("div", "", line));
  });
  elements.confirmOkBtn.textContent = confirmText;
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
  if (confirmResolver) {
    confirmResolver(result);
    confirmResolver = null;
  }
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
  elements.priceDialogTitle.textContent = `修改价格 #${listingId}`;
  elements.priceDialogHint.textContent = "价格修改后立即生效，请谨慎操作。";
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
    elements.priceDialogError.textContent = "价格必须是大于 0 的数字。";
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

function setRegisterFlowTip(type, title, detail, statusText) {
  if (statusText) {
    elements.registerStatusView.textContent = statusText;
  }
  if (!elements.registerFlowTip) {
    return;
  }
  const normalized = ["info", "success", "warn", "error"].includes(type) ? type : "info";
  const cssTone = normalized === "error" ? "warn" : normalized;
  elements.registerFlowTip.className = `md-tip md-tip-${cssTone}`;
  elements.registerFlowTip.innerHTML =
    `<strong>${escapeHtml(title)}</strong><p>${escapeHtml(detail)}</p>`;
}

function setStatus(text, stateName) {
  elements.statusChip.textContent = text;
  elements.statusChip.dataset.state = stateName;
}

function switchTab(tabName) {
  state.activeTab = tabName;

  tabs.forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.tabTarget === tabName);
  });

  panels.forEach((panel) => {
    panel.classList.toggle("active", panel.dataset.tabPanel === tabName);
  });

  if (tabName === "wallet") {
    if (state.token) {
      refreshWallet().catch((error) => {
        const message = resolveErrorMessage(error, "wallet_refresh");
        setMetaText(elements.walletView, `刷新钱包失败：${message}`, "error");
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
  if (tabName === "market") {
    loadMarket(state.marketMode || "public");
  }
}

tabs.forEach((tab) => {
  tab.addEventListener("click", () => switchTab(tab.dataset.tabTarget));
});

function createIdempotencyKey() {
  if (window.crypto && typeof window.crypto.randomUUID === "function") {
    return window.crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const formatNumber = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 });

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
  updateSelect(document.getElementById("exchangeFrom"));
  updateSelect(document.getElementById("exchangeTo"));
  updateSelect(document.getElementById("marketCurrency"));

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
}

async function loadCurrencyMeta() {
  try {
    const payload = await api("/api/meta/currency", { method: "GET" });
    applyCurrencyMeta(payload);
  } catch (error) {
    // Ignore if metadata endpoint is unavailable.
  }
}

function formatWalletInline(shopCoin, gameCoin) {
  return `${formatCurrency(shopCoin, "SHOP_COIN")} | ${formatCurrency(gameCoin, "GAME_COIN")}`;
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

function humanizeMaterial(materialKey) {
  return materialKey
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function getLocalizedMaterialName(material) {
  const key = normalizeMaterialKey(material);
  if (!key) {
    return "未知物品";
  }
  return state.zhNameMap[key] || humanizeMaterial(key);
}

async function ensureZhNameMap() {
  if (state.zhNameMapReady) {
    return;
  }
  if (state.zhNameMapPromise) {
    await state.zhNameMapPromise;
    return;
  }

  state.zhNameMapPromise = fetch("/material_zh.json")
    .then((response) => {
      if (!response.ok) {
        throw new Error(`JSON 词库加载失败: ${response.status}`);
      }
      return response.json();
    })
    .then((json) => {
      state.zhNameMap = json || {};
      state.zhNameMapReady = true;
      log(`中文词库已加载：${Object.keys(state.zhNameMap).length} 条。`);
    })
    .catch((error) => {
      state.zhNameMap = {};
      state.zhNameMapReady = true;
      log(`中文词库不可用，将使用英文名：${error.message}`, "WARN");
    });

  await state.zhNameMapPromise;
}

function buildTextureAliases(material) {
  const key = normalizeMaterialKey(material);
  const aliases = new Set();
  if (!key) {
    return [];
  }

  aliases.add(key.toLowerCase());
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

function getTextureCandidates(material) {
  const names = buildTextureAliases(material);
  if (names.length === 0) {
    return [FALLBACK_TEXTURE];
  }

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

  candidates.push(FALLBACK_TEXTURE);
  return candidates;
}

function buildTextureImage(material, altText) {
  const img = document.createElement("img");
  img.className = "market-icon-image";
  img.alt = altText;
  img.loading = "lazy";
  img.decoding = "async";

  const candidates = getTextureCandidates(material);
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

function formatAge(isoText) {
  const timestamp = Date.parse(isoText);
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

function formatDateTime(isoText) {
  const timestamp = Date.parse(isoText);
  if (Number.isNaN(timestamp)) {
    return "未知时间";
  }
  return new Date(timestamp).toLocaleString("zh-CN", { hour12: false });
}

function formatCountdown(deadlineIso) {
  const deadline = Date.parse(deadlineIso);
  if (Number.isNaN(deadline)) {
    return null;
  }
  const diff = Math.max(0, deadline - Date.now());
  const seconds = Math.ceil(diff / 1000);
  if (seconds <= 0) {
    return "已结束";
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
  return ORDER_STATUS_LABELS[key] || { label: key || "未知", tone: "pending" };
}

function enchantLabel(key) {
  const name = String(key || "")
    .replace(/^minecraft:/, "")
    .replaceAll("_", " ");
  return name
    .split(" ")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function createEl(tag, className, text) {
  const el = document.createElement(tag);
  if (className) {
    el.className = className;
  }
  if (text !== undefined && text !== null) {
    el.textContent = text;
  }
  return el;
}

function setMarketButtons(mode) {
  document.getElementById("marketListBtn").classList.toggle("active", mode === "public");
  document.getElementById("marketMineBtn").classList.toggle("active", mode === "mine");
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

  const response = await fetch(path, { ...options, headers });
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

function ensureToken() {
  if (!state.token) {
    switchTab("auth");
    throw new Error("请先登录后再操作。");
  }
}

function setAuthMode(mode) {
  state.authMode = mode === "register" ? "register" : "login";

  elements.authModeLoginBtn.classList.toggle("active", state.authMode === "login");
  elements.authModeRegisterBtn.classList.toggle("active", state.authMode === "register");

  elements.authLoginPanel.classList.toggle("active", state.authMode === "login");
  elements.authRegisterPanel.classList.toggle("active", state.authMode === "register");
}

function updateAuthLayout() {
  const loggedIn = !!state.token;
  elements.authEntryCard.classList.toggle("hidden", loggedIn);
  elements.authProfileCard.classList.toggle("hidden", !loggedIn);

  if (!loggedIn) {
    setStatus("未登录", "offline");
    return;
  }

  setStatus(`已登录：${state.username || "-"}`, "online");
  renderProfile();
}

function renderProfile() {
  elements.profileName.textContent = state.username || "-";
  elements.profileUuid.textContent = state.boundUuid || "未绑定";

  const avatarKey = state.username || state.boundUuid;
  if (!avatarKey) {
    elements.profileAvatar.src = FALLBACK_AVATAR;
    return;
  }

  elements.profileAvatar.src = `${AVATAR_BASE}${encodeURIComponent(avatarKey)}`;
  elements.profileAvatar.onerror = () => {
    elements.profileAvatar.onerror = null;
    elements.profileAvatar.src = FALLBACK_AVATAR;
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

  // 保存会话到本地存储
  const sessionData = {
    token: state.token,
    username: state.username,
    boundUuid: state.boundUuid,
  };
  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(sessionData));

  stopRegistrationPolling();
  clearRegistrationUi(false);
  updateAuthLayout();
  state.hasLoadedOrders = false;
}

function clearSession() {
  state.token = null;
  state.username = null;
  state.boundUuid = null;
  state.orders = [];
  state.hasLoadedOrders = false;
  // 移除本地存储的会话
  window.localStorage.removeItem(SESSION_STORAGE_KEY);
  renderOrders(state.orders);
  updateAuthLayout();
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
    log("会话已恢复。", "SUCCESS");
  } catch (error) {
    // token无效，清除存储
    state.token = null;
    window.localStorage.removeItem(SESSION_STORAGE_KEY);
    log("会话恢复失败，已清除。", "WARN");
  }
}

function updateWalletView(payload) {
  if (Object.prototype.hasOwnProperty.call(payload, "username") && payload.username) {
    state.username = payload.username;
  }
  if (Object.prototype.hasOwnProperty.call(payload, "boundUuid")) {
    state.boundUuid = payload.boundUuid || null;
  }

  elements.shopCoinValue.textContent = formatAmount(payload.shopCoin || 0);
  elements.gameCoinValue.textContent = formatAmount(payload.gameCoin || 0);
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

function clearRegistrationUi(resetStatus = true) {
  state.registration.bindCode = null;
  state.registration.username = null;
  state.registration.boundUuid = null;
  state.registration.status = "IDLE";

  elements.registerBindCodeView.textContent = "绑定码：-";
  if (elements.registerBindCopyBtn) {
    elements.registerBindCopyBtn.disabled = true;
  }
  if (resetStatus) {
    elements.registerStatusView.textContent = "状态：等待开始注册";
  }
  setRegisterFlowTip(
    "info",
    "注册步骤",
    "在游戏内执行 /webshopx bind <绑定码>（或 /ws bind <绑定码>）后，页面会自动检测绑定结果并提示下一步。",
    resetStatus ? "状态：等待开始注册" : undefined
  );
  elements.registerPasswordStage.classList.add("hidden");
  elements.registerPassword.value = "";
  elements.registerPasswordConfirm.value = "";
}

function stopRegistrationPolling() {
  if (state.registration.pollTimer) {
    clearInterval(state.registration.pollTimer);
    state.registration.pollTimer = null;
  }
}

function startRegistrationPolling() {
  stopRegistrationPolling();
  state.registration.pollTimer = window.setInterval(() => {
    queryRegistrationStatus(false).catch(() => {
      // Ignore transient polling errors.
    });
  }, 2000);
}

function setRegistrationStatusText(status, username, boundUuid) {
  if (status === "WAITING_BIND") {
    setRegisterFlowTip(
      "info",
      "等待绑定",
      "请进入游戏执行 /webshopx bind <绑定码>（可简写 /ws bind <绑定码>）。完成后页面会自动切换到密码设置。",
      "状态：等待游戏内执行 /webshopx bind"
    );
  } else if (status === "NEED_PASSWORD") {
    const suffix = boundUuid ? `，已绑定 UUID ${boundUuid}` : "";
    setRegisterFlowTip(
      "success",
      "绑定成功",
      "游戏身份验证已完成。请立即设置密码并提交，完成后会自动登录当前账号。",
      `状态：绑定成功，请设置密码${suffix}`
    );
  } else if (status === "COMPLETED") {
    const nameText = username ? `（${username}）` : "";
    setRegisterFlowTip(
      "warn",
      "流程已结束",
      "该绑定码已完成注册或已使用，请切换到“登录”模式继续使用账号。",
      `状态：该绑定码已完成注册${nameText}，请直接登录`
    );
  } else if (status === "EXPIRED") {
    setRegisterFlowTip(
      "warn",
      "绑定码过期",
      "绑定码超过有效期。请点击“获取绑定码”生成新的绑定码后重新绑定。",
      "状态：绑定码已过期，请重新获取"
    );
  } else if (status === "INVALID_CODE") {
    setRegisterFlowTip(
      "warn",
      "绑定码无效",
      "当前绑定码不可用，可能已失效或输入错误。请重新发起注册流程。",
      "状态：绑定码无效"
    );
  } else {
    setRegisterFlowTip(
      "warn",
      "状态异常",
      "请重新获取绑定码并重试；如问题持续，请查看日志并联系管理员。",
      "状态：未知"
    );
  }
}

async function startRegistration() {
  const payload = await api("/api/auth/register/start", {
    method: "POST",
    body: JSON.stringify({}),
  });

  state.registration.bindCode = payload.bindCode;
  state.registration.username = null;
  state.registration.status = "WAITING_BIND";

  elements.registerBindCodeView.textContent = `绑定码：${payload.bindCode}`;
  if (elements.registerBindCopyBtn) {
    elements.registerBindCopyBtn.disabled = false;
  }
  setRegisterFlowTip(
    "info",
    "绑定码已生成",
    `请在 ${payload.expiresInMinutes} 分钟内回到游戏执行 /webshopx bind <绑定码>（或 /ws bind <绑定码>）。页面会自动检测绑定结果。`,
    `状态：绑定码已生成（${payload.expiresInMinutes} 分钟内有效），请回游戏执行 /webshopx bind`
  );
  elements.registerPasswordStage.classList.add("hidden");

  startRegistrationPolling();
  await queryRegistrationStatus(false);
}

async function queryRegistrationStatus(manual = true) {
  const bindCode = state.registration.bindCode;
  if (!bindCode) {
    if (manual) {
      throw new Error("请先获取绑定码。");
    }
    return;
  }

  const payload = await api(
    `/api/auth/register/status?bindCode=${encodeURIComponent(bindCode)}`,
    { method: "GET" }
  );

  const status = String(payload.status || "UNKNOWN");
  const previousStatus = state.registration.status;

  state.registration.status = status;
  state.registration.username = payload.username || state.registration.username;
  state.registration.boundUuid = payload.boundUuid || null;

  setRegistrationStatusText(status, payload.username, payload.boundUuid);

  if (status === "NEED_PASSWORD") {
    elements.registerPasswordStage.classList.remove("hidden");
  } else {
    elements.registerPasswordStage.classList.add("hidden");
  }

  if (status === "COMPLETED" || status === "EXPIRED" || status === "INVALID_CODE") {
    stopRegistrationPolling();
  }

  if (previousStatus !== status) {
    log(`注册流程状态更新：${status}`);
  }
}

async function finishRegistration() {
  const bindCode = state.registration.bindCode;
  if (!bindCode) {
    throw new Error("缺少绑定码，请重新开始注册。");
  }

  const password = elements.registerPassword.value.trim();
  const confirm = elements.registerPasswordConfirm.value.trim();
  if (!password) {
    throw new Error("请输入密码。");
  }
  if (password !== confirm) {
    throw new Error("两次密码输入不一致。");
  }

  const payload = await api("/api/auth/register/finish", {
    method: "POST",
    body: JSON.stringify({
      bindCode,
      password,
    }),
  });

  setSession(payload);
  await refreshWallet();
  await loadOrders();
  switchTab("wallet");
}

function renderProducts(products) {
  elements.productList.innerHTML = "";

  if (!products || products.length === 0) {
    const empty = createEl("div", "empty-state", "暂无商品，请联系管理员在后台添加。 ");
    elements.productList.appendChild(empty);
    return;
  }

  for (const product of products) {
    const card = createEl("article", "product-card");

    const top = createEl("div", "product-top");
    const titleWrap = createEl("div", "order-title-wrap");
    titleWrap.appendChild(createEl("h3", "product-title", product.title));
    titleWrap.appendChild(createEl("p", "product-sku", `SKU: ${product.sku} | ID: ${product.id}`));
    top.appendChild(titleWrap);

    const currencyChip = createEl(
      "span",
      "currency-chip",
      (CURRENCY_META[product.currency] || { label: product.currency }).label
    );
    top.appendChild(currencyChip);
    card.appendChild(top);

    card.appendChild(createEl("p", "product-price", formatCurrency(product.price, product.currency)));
    if (product.remark) {
      card.appendChild(createEl("p", "product-remark", product.remark));
    }
    if (String(product.productType || "").toUpperCase() === "GROUP_BUY_VOUCHER") {
      card.appendChild(createEl("p", "product-sku", "该商品购买后将生成团购兑换码，需交由管理员核销。"));
    }
    if (product.unpublishAt) {
      card.appendChild(createEl("p", "product-sku", `下架时间：${formatDateTime(product.unpublishAt)}`));
    }

    const actions = createEl("div", "product-actions");
    const qty = document.createElement("input");
    qty.className = "product-qty";
    qty.type = "number";
    qty.min = "1";
    qty.step = "1";
    qty.value = "1";

    const isGroupBuyVoucher = String(product.productType || "").toUpperCase() === "GROUP_BUY_VOUCHER";
    const button = createEl("button", "product-buy-btn", isGroupBuyVoucher ? "购买兑换码" : "立即下单");
    button.type = "button";
    button.dataset.productId = String(product.id);

    actions.appendChild(qty);
    actions.appendChild(button);
    card.appendChild(actions);

    elements.productList.appendChild(card);
  }
}

function renderListings(listings) {
  elements.marketList.innerHTML = "";

  if (!listings || listings.length === 0) {
    const empty = createEl("div", "empty-state", "当前没有可显示的市场上架。 ");
    elements.marketList.appendChild(empty);
    return;
  }

  for (const listing of listings) {
    const meta = parseMeta(listing.itemMetaJson);
    const isOwner = !!state.username && listing.sellerName === state.username;
    const isActive = listing.status === "ACTIVE";

    const displayName = stripColorCodes(meta.displayName || "");
    const localizedName = displayName || getLocalizedMaterialName(listing.itemMaterial);

    const card = createEl("article", "market-card");

    const top = createEl("div", "market-top");
    top.appendChild(createEl(
      "span",
      `market-chip ${isActive ? "sale" : "inactive"}`,
      isActive ? "在售" : listing.status
    ));
    top.appendChild(createEl("span", "market-time", formatAge(listing.createdAt)));
    card.appendChild(top);

    const main = createEl("div", "market-main");
    const icon = createEl("div", "market-icon");
    icon.appendChild(buildTextureImage(listing.itemMaterial, localizedName));
    main.appendChild(icon);

    const detail = createEl("div");
    detail.appendChild(createEl("h3", "market-title", localizedName));
    detail.appendChild(createEl("p", "market-code", String(listing.itemMaterial)));
    detail.appendChild(createEl("p", "market-sub", `数量 x${listing.quantity}`));
    if (listing.remark) {
      detail.appendChild(createEl("p", "market-remark", listing.remark));
    }
    main.appendChild(detail);
    card.appendChild(main);

    if (meta.enchants && Object.keys(meta.enchants).length > 0) {
      const enchants = createEl("div", "market-enchants");
      Object.entries(meta.enchants).forEach(([key, level]) => {
        enchants.appendChild(createEl("span", "market-enchant", `${enchantLabel(key)} ${level}`));
      });
      card.appendChild(enchants);
    }

    const priceRow = createEl("div", "market-price-row");
    priceRow.appendChild(createEl("p", "market-price-label", "价格"));
    priceRow.appendChild(createEl("p", "market-price", formatCurrency(listing.price, listing.currency)));
    card.appendChild(priceRow);

    const footer = createEl("div", "market-footer");
    const seller = createEl("div", "market-seller");
    seller.appendChild(createEl("span", "", `卖家：${listing.sellerName}`));
    seller.appendChild(createEl("span", "", isOwner ? "我的上架" : "公开市场"));
    footer.appendChild(seller);

    const actions = createEl("div", "market-actions-row");
    if (isActive) {
      if (isOwner) {
        const remarkBtn = createEl("button", "market-action-btn btn-tonal", "备注");
        remarkBtn.type = "button";
        remarkBtn.dataset.action = "remark";
        remarkBtn.dataset.listingId = String(listing.id);
        remarkBtn.dataset.currentRemark = listing.remark || "";

        const editBtn = createEl("button", "market-action-btn btn-tonal", "改价");
        editBtn.type = "button";
        editBtn.dataset.action = "price";
        editBtn.dataset.listingId = String(listing.id);
        editBtn.dataset.currentPrice = String(listing.price);
        editBtn.dataset.currency = listing.currency;

        const unlistBtn = createEl("button", "market-action-btn unlist", "下架并退回");
        unlistBtn.type = "button";
        unlistBtn.dataset.action = "unlist";
        unlistBtn.dataset.listingId = String(listing.id);
        actions.appendChild(remarkBtn);
        actions.appendChild(editBtn);
        actions.appendChild(unlistBtn);
      } else {
        const buyBtn = createEl("button", "market-action-btn", "立即购买");
        buyBtn.type = "button";
        buyBtn.dataset.action = "buy";
        buyBtn.dataset.listingId = String(listing.id);
        actions.appendChild(buyBtn);
      }
    } else {
      const disabledBtn = createEl("button", "market-action-btn", "不可操作");
      disabledBtn.disabled = true;
      actions.appendChild(disabledBtn);
    }

    footer.appendChild(actions);
    card.appendChild(footer);
    elements.marketList.appendChild(card);
  }
}

async function loadProducts(options = {}) {
  const announce = !!options.announce;
  try {
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
    log(`加载商品失败：${message}`, "ERROR");
    if (announce) {
      notify(`加载商品失败：${message}`, "error");
    }
  }
}

async function loadMarket(mode, options = {}) {
  const announce = !!options.announce;
  try {
    await ensureZhNameMap();
    if (mode === "mine") {
      ensureToken();
    }

    const params = new URLSearchParams();
    params.set("limit", "120");
    if (mode === "mine") {
      params.set("mine", "true");
    }
    const keyword = elements.marketKeyword ? elements.marketKeyword.value.trim() : "";
    const materialInput = elements.marketMaterial ? elements.marketMaterial.value.trim() : "";
    const material = materialInput ? normalizeMaterialKey(materialInput) : "";
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

    state.marketMode = mode;
    state.listings = payload.listings || [];
    state.hasLoadedMarket = true;

    setMarketButtons(mode);
    renderListings(state.listings);

    const label = mode === "mine" ? "我的上架" : "市场在售";
    setMetaText(elements.marketView, `${label}：${state.listings.length} 条`, "info");
    log(`${label}已加载：${state.listings.length} 条。`);
    if (announce) {
      notify(`${label}已刷新：${state.listings.length} 条。`, "info");
    }
  } catch (error) {
    const message = resolveErrorMessage(error, "market_load");
    setMetaText(elements.marketView, `加载市场失败：${message}`, "error");
    log(`加载市场失败：${message}`, "ERROR");
    if (announce) {
      notify(`加载市场失败：${message}`, "error");
    }
  }
}

async function loadOrderPolicy() {
  try {
    const payload = await api("/api/orders/policy", { method: "GET" });
    state.orderPolicy.cooldownSeconds = Number(payload.cooldownSeconds || 0);
    state.orderPolicy.refundEnabled = !!payload.refundEnabled;
    state.orderPolicyReady = true;
  } catch (error) {
    state.orderPolicy.cooldownSeconds = 0;
    state.orderPolicy.refundEnabled = false;
    state.orderPolicyReady = true;
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
    const createdLabel = isMarket ? "成交时间" : "下单时间";
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
        meta.appendChild(createEl("div", "", `冷静期剩余：${remain}`));
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

async function loadOrders(options = {}) {
  const announce = !!options.announce;
  try {
    ensureToken();
    await ensureZhNameMap();
    const payload = await api("/api/orders/list?limit=50", { method: "GET" });
    state.orders = payload.orders || [];
    renderOrders(state.orders);
    state.hasLoadedOrders = true;
    const cooldownSeconds = Number(payload.cooldownSeconds || 0);
    if (Number.isFinite(cooldownSeconds)) {
      state.orderPolicy.cooldownSeconds = cooldownSeconds;
      state.orderPolicy.refundEnabled = cooldownSeconds > 0;
    }
    log(`订单记录已加载：${state.orders.length} 条。`);
    if (announce) {
      notify(`订单记录已刷新：${state.orders.length} 条。`, "info");
    }
  } catch (error) {
    const message = resolveErrorMessage(error, "orders_load");
    setMetaText(elements.orderView, `加载订单失败：${message}`, "error");
    log(`加载订单失败：${message}`, "ERROR");
    if (announce) {
      notify(`加载订单失败：${message}`, "error");
    }
  }
}

async function confirmPurchase(product, quantity) {
  const qty = Number(quantity || 1);
  const total = formatCurrency(product.price * qty, product.currency);
  const cooldown = Number(state.orderPolicy.cooldownSeconds || 0);
  const details = [
    `商品：${product.title}`,
    `数量：x${qty}`,
    `总额：${total}`,
  ];
  if (product.remark) {
    details.push(`备注：${product.remark}`);
  }
  if (String(product.productType || "").toUpperCase() === "GROUP_BUY_VOUCHER") {
    details.push("该商品会生成团购兑换码，需由管理员在后台核销。");
  }
  if (cooldown > 0) {
    details.push(`冷静期：${cooldown} 秒（可在冷静期内退款）`);
  } else {
    details.push("冷静期：未开启");
  }
  return openConfirmDialog({
    title: "确认下单",
    message: "请确认以下订单信息，确认后将立即扣除余额。",
    details,
    confirmText: "确认下单",
  });
}

async function confirmMarketBuy(listing) {
  await ensureZhNameMap();
  const meta = parseMeta(listing.itemMetaJson);
  const displayName = stripColorCodes(meta.displayName || "");
  const localizedName = displayName || getLocalizedMaterialName(listing.itemMaterial);
  const cooldown = Number(state.orderPolicy.cooldownSeconds || 0);
  const details = [
    `物品：${localizedName}`,
    `数量：x${listing.quantity}`,
    `价格：${formatCurrency(listing.price, listing.currency)}`,
    `卖家：${listing.sellerName}`,
    "手续费/税率以服务器配置为准",
  ];
  if (listing.remark) {
    details.push(`备注：${listing.remark}`);
  }
  if (cooldown > 0) {
    details.push(`冷静期：${cooldown} 秒（冷静期内可退款）`);
  } else {
    details.push("冷静期：未开启");
  }
  return openConfirmDialog({
    title: "确认购买",
    message: "请确认以下交易信息，确认后将立即扣除余额。",
    details,
    confirmText: "确认购买",
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
}

async function createOrder(productId, quantity) {
  ensureToken();

  const pid = Number(productId);
  const qty = Number(quantity || 1);
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
      idempotencyKey: createIdempotencyKey(),
    }),
  });

  const isExisting = String(payload.state || "").toUpperCase() === "EXISTING";
  const orderStatus = payload.orderStatus || payload.state;
  const groupBuyVoucherCode = payload.groupBuyVoucherCode || null;
  const summary = `订单 ${payload.orderNo} | 总额 ${formatCurrency(payload.totalAmount, payload.currency)} | ${orderStatus}`;
  setMetaText(elements.orderView, summary, isExisting ? "warn" : "success");
  if (isExisting) {
    log(`下单请求去重，返回历史订单：${summary}`, "WARN");
    notify(`订单已存在，已返回历史订单 ${payload.orderNo}。`, "warn");
  } else {
    log(`下单成功：${summary}`, "SUCCESS");
    notify(`下单成功：${summary}`, "success");
  }
  if (groupBuyVoucherCode) {
    notify(`已生成团购兑换码：${groupBuyVoucherCode}`, "success", 5200);
    log(`团购兑换码已生成：${groupBuyVoucherCode}`, "SUCCESS");
  }

  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(`订单创建后刷新钱包失败：${refreshMessage}`, "WARN");
  }
  try {
    await loadOrders();
  } catch (orderError) {
    const orderMessage = resolveErrorMessage(orderError, "orders_load");
    log(`订单创建后加载订单失败：${orderMessage}`, "WARN");
  }
  return payload;
}

async function buyListing(listingId) {
  ensureToken();
  const payload = await api("/api/market/buy", {
    method: "POST",
    body: JSON.stringify({
      listingId,
      idempotencyKey: createIdempotencyKey(),
    }),
  });
  const isExisting = String(payload.state || "").toUpperCase() === "EXISTING";
  const paidAmount = payload.buyerTotal !== undefined ? payload.buyerTotal : payload.totalPrice;
  const amountText = formatCurrency(paidAmount, payload.currency);
  const cooldownSeconds = Number(payload.cooldownSeconds || state.orderPolicy.cooldownSeconds || 0);
  if (Number.isFinite(cooldownSeconds)) {
    state.orderPolicy.cooldownSeconds = cooldownSeconds;
    state.orderPolicy.refundEnabled = cooldownSeconds > 0;
  }
  const statusText = payload.orderStatus || "PENDING";
  const refundDeadlineText = payload.refundDeadline ? `，退款截止 ${formatDateTime(payload.refundDeadline)}` : "";
  if (isExisting) {
    log(`市场购买请求去重：tradeId=${payload.tradeId}，listingId=${payload.listingId}`, "WARN");
    notify(`该交易已处理过，返回历史结果（交易号 ${payload.tradeId}）。`, "warn");
  } else {
    log(`购买成功：tradeId=${payload.tradeId}，listingId=${payload.listingId}`, "SUCCESS");
    const feeAmount = Number(payload.feeAmount || 0) + Number(payload.taxAmount || 0);
    if (feeAmount > 0) {
      notify(
        `购买成功，实付 ${amountText}（含手续费/税收 ${formatCurrency(feeAmount, payload.currency)}），状态 ${statusText}${refundDeadlineText}。`,
        "success"
      );
    } else {
      notify(`购买成功，成交金额 ${amountText}，状态 ${statusText}${refundDeadlineText}。`, "success");
    }
  }

  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(`购买后刷新钱包失败：${refreshMessage}`, "WARN");
  }
  await loadMarket(state.marketMode);
  if (state.token) {
    await loadOrders();
  }
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

elements.authModeLoginBtn.addEventListener("click", () => setAuthMode("login"));
elements.authModeRegisterBtn.addEventListener("click", () => setAuthMode("register"));

elements.loginBtn.addEventListener("click", async () => {
  try {
    const identifier = elements.loginIdentifier.value.trim();
    const password = elements.loginPassword.value.trim();
    if (!identifier) {
      throw new Error("请输入 UUID 或用户名。");
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
    await loadOrders();
    switchTab("wallet");
    log("登录成功。", "SUCCESS");
    notify("登录成功。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "login");
    log(`登录失败：${message}`, "ERROR");
    notify(`登录失败：${message}`, "error");
  }
});

elements.registerStartBtn.addEventListener("click", async () => {
  try {
    await startRegistration();
    log("已生成绑定码，请回游戏执行 /webshopx bind（或 /ws bind）。", "SUCCESS");
    notify("绑定码已生成，请回游戏执行 /webshopx bind（或 /ws bind）。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "register_start");
    setRegisterFlowTip("warn", "获取绑定码失败", message, "状态：获取绑定码失败");
    log(`注册初始化失败：${message}`, "ERROR");
    notify(`注册初始化失败：${message}`, "error");
  }
});

if (elements.registerBindCopyBtn) {
  elements.registerBindCopyBtn.addEventListener("click", async () => {
    try {
      await copyTextToClipboard(state.registration.bindCode || "");
      notify("绑定码已复制到剪贴板。", "success");
    } catch (error) {
      notify(error.message || "复制失败，请手动复制。", "error");
    }
  });
}

elements.registerFinishBtn.addEventListener("click", async () => {
  try {
    await finishRegistration();
    log("注册完成并已登录。", "SUCCESS");
    notify("注册完成并已自动登录。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "register_finish");
    setRegisterFlowTip("warn", "注册未完成", message, "状态：注册未完成");
    log(`完成注册失败：${message}`, "ERROR");
    notify(`完成注册失败：${message}`, "error");
  }
});

elements.logoutBtn.addEventListener("click", async () => {
  try {
    ensureToken();
    await api("/api/auth/logout", {
      method: "POST",
      body: JSON.stringify({}),
    });
    clearSession();
    switchTab("auth");
    log("已退出登录。", "SUCCESS");
    notify("已退出登录。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "logout");
    log(`退出登录失败：${message}`, "ERROR");
    notify(`退出登录失败：${message}`, "error");
  }
});

document.getElementById("walletBtn").addEventListener("click", async () => {
  try {
    await refreshWallet();
    log("钱包余额已刷新。", "SUCCESS");
    notify("钱包余额已刷新。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "wallet_refresh");
    log(`刷新钱包失败：${message}`, "ERROR");
    notify(`刷新钱包失败：${message}`, "error");
  }
});

document.getElementById("redeemBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const code = document.getElementById("redeemCode").value.trim();
    if (!code) {
      throw new Error("请输入兑换码后再提交。");
    }
    const payload = await api("/api/redeem/use", {
      method: "POST",
      body: JSON.stringify({ code }),
    });

    updateWalletView(payload);
    const tip = redeemStatusTip(payload.status);
    setMetaText(elements.redeemView, tip.text, tip.tone);
    log(`兑换结果：${tip.text}`, tip.tone === "success" ? "SUCCESS" : "WARN");
    notify(tip.text, tip.tone);
  } catch (error) {
    const message = resolveErrorMessage(error, "redeem");
    setMetaText(elements.redeemView, `兑换失败：${message}`, "error");
    log(`兑换失败：${message}`, "ERROR");
    notify(`兑换失败：${message}`, "error");
  }
});

document.getElementById("exchangeBtn").addEventListener("click", async () => {
  try {
    ensureToken();

    const fromCurrency = document.getElementById("exchangeFrom").value.trim();
    const toCurrency = document.getElementById("exchangeTo").value.trim();
    const amount = Number(document.getElementById("exchangeAmount").value.trim());

    if (!Number.isFinite(amount) || amount <= 0) {
      throw new Error("兑换数量必须大于 0。");
    }
    if (fromCurrency === toCurrency) {
      throw new Error("兑换方向不能相同。");
    }

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
    const toMeta = CURRENCY_META[toCurrency] || { label: toCurrency };
    setMetaText(
      elements.exchangeView,
      `兑换成功：${formatCurrency(amount, fromCurrency)} -> ${toMeta.label}`,
      "success"
    );
    log(`货币兑换成功：${formatCurrency(amount, fromCurrency)} -> ${toMeta.label}`, "SUCCESS");
    notify(`兑换成功：${formatCurrency(amount, fromCurrency)} -> ${toMeta.label}`, "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "exchange");
    setMetaText(elements.exchangeView, `兑换失败：${message}`, "error");
    log(`兑换失败：${message}`, "ERROR");
    notify(`兑换失败：${message}`, "error");
  }
});

document.getElementById("productsBtn").addEventListener("click", () => {
  loadProducts({ announce: true });
});
if (elements.ordersBtn) {
  elements.ordersBtn.addEventListener("click", () => {
    loadOrders({ announce: true });
  });
}
document.getElementById("marketListBtn").addEventListener("click", () => {
  loadMarket("public", { announce: true });
});
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
if (elements.marketApplyBtn) {
  elements.marketApplyBtn.addEventListener("click", () => {
    loadMarket(state.marketMode || "public", { announce: true });
  });
}
if (elements.marketClearBtn) {
  elements.marketClearBtn.addEventListener("click", () => {
    if (elements.marketKeyword) elements.marketKeyword.value = "";
    if (elements.marketMaterial) elements.marketMaterial.value = "";
    if (elements.marketCurrency) elements.marketCurrency.value = "";
    if (elements.marketMinPrice) elements.marketMinPrice.value = "";
    if (elements.marketMaxPrice) elements.marketMaxPrice.value = "";
    if (elements.marketSort) elements.marketSort.value = "created_desc";
    loadMarket(state.marketMode || "public", { announce: true });
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

  const confirmed = await confirmPurchase(product, quantity);
  if (!confirmed) {
    notify("已取消下单。", "info");
    return;
  }

  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "下单中...";
  try {
    await createOrder(productId, quantity);
  } catch (error) {
    const message = resolveErrorMessage(error, "order_create");
    setMetaText(elements.orderView, `下单失败：${message}`, "error");
    log(`下单失败：${message}`, "ERROR");
    notify(`下单失败：${message}`, "error");
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
    button.textContent = "退款中...";
    try {
      await refundOrder(orderNo);
    } catch (error) {
      const message = resolveErrorMessage(error, "order_refund");
      log(`退款失败：${message}`, "ERROR");
      notify(`退款失败：${message}`, "error");
    } finally {
      button.disabled = false;
      button.textContent = "申请退款";
    }
  });
}

elements.marketList.addEventListener("click", async (event) => {
  const button = event.target.closest(".market-action-btn");
  if (!button || button.disabled) {
    return;
  }

  const listingId = Number(button.dataset.listingId);
  if (!Number.isFinite(listingId) || listingId <= 0) {
    log("市场操作失败：上架 ID 无效。", "ERROR");
    notify("市场操作失败：上架 ID 无效。", "error");
    return;
  }

  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "处理中...";
  try {
    if (button.dataset.action === "buy") {
      const listing = state.listings.find((item) => Number(item.id) === listingId);
      const confirmed = listing ? await confirmMarketBuy(listing) : await openConfirmDialog({
        title: "确认购买",
        message: "确认后将立即扣除余额。",
        details: [`上架ID：${listingId}`],
        confirmText: "确认购买",
      });
      if (!confirmed) {
        notify("已取消购买。", "info");
        return;
      }
      await buyListing(listingId);
      return;
    }
    if (button.dataset.action === "unlist") {
      await unlistListing(listingId);
      return;
    }
    if (button.dataset.action === "price") {
      const currentPrice = Number(button.dataset.currentPrice || 0);
      const currency = button.dataset.currency || "SHOP_COIN";
      const newPrice = await openPriceDialog({
        listingId,
        currentPrice,
        currency,
      });
      if (newPrice === null) {
        notify("已取消改价。", "info");
        return;
      }
      await updateListingPrice(listingId, newPrice);
      return;
    }
    if (button.dataset.action === "remark") {
      const currentRemark = button.dataset.currentRemark || "";
      const raw = window.prompt("请输入备注（留空将清空备注）", currentRemark);
      if (raw === null) {
        notify("已取消备注编辑。", "info");
        return;
      }
      const remark = raw.trim();
      await updateListingRemark(listingId, remark ? remark : null);
    }
  } catch (error) {
    const scene = button.dataset.action === "unlist"
      ? "market_unlist"
      : button.dataset.action === "price"
        ? "market_price"
        : button.dataset.action === "remark"
          ? "market_remark"
        : "market_buy";
    const message = resolveErrorMessage(error, scene);
    log(`市场操作失败：${message}`, "ERROR");
    notify(`市场操作失败：${message}`, "error");
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
});

if (elements.confirmCancelBtn) {
  elements.confirmCancelBtn.addEventListener("click", () => closeConfirmDialog(false));
}
if (elements.confirmOkBtn) {
  elements.confirmOkBtn.addEventListener("click", () => closeConfirmDialog(true));
}
if (elements.confirmDialog) {
  elements.confirmDialog.addEventListener("click", (event) => {
    if (event.target === elements.confirmDialog) {
      closeConfirmDialog(false);
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

applyTheme(getInitialTheme());
setAuthMode("login");
updateAuthLayout();
clearRegistrationUi();
setMetaText(elements.walletView, "等待刷新余额", "info");
setMetaText(elements.redeemView, "等待兑换操作", "info");
setMetaText(elements.exchangeView, "等待兑换操作", "info");
setMetaText(elements.orderView, "暂无订单", "info");
setMetaText(elements.marketView, "暂无市场数据", "info");
ensureZhNameMap();
loadOrderPolicy();
loadCurrencyMeta().finally(() => {
  loadProducts();
  loadMarket("public");
});
// 尝试恢复会话
restoreSession();
