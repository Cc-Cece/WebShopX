const state = {
  token: null,
  admin: null,
  activeTab: "login",
  productPanel: "editor",
  selectedUser: null,
  products: [],
  userList: [],
  adminManagers: [],
  adminMeta: null,
  selectedAdminManager: null,
  latestRedeemCode: null,
  theme: "light",
  materialMap: {},
  materialLookup: {},
  materialMapReady: false,
  materialMapPromise: null,
  materialAllowSet: new Set(),
  materialAllowReady: false,
  materialAllowPromise: null,
  marketAlgorithmGlossary: {
    dynamic: [],
    auction: [],
  },
  marketAlgorithmGlossaryReady: false,
  marketAlgorithmGlossaryPromise: null,
  autoSyncTimer: null,
  autoSyncBusy: false,
  realtime: {
    orderDigest: {},
    marketDigest: {},
  },
  currencyMeta: {
    SHOP_COIN: { name: "网页币", short: "SC" },
    GAME_COIN: { name: "游戏币", short: "GC" },
  },
  timeZone: "Asia/Shanghai",
};

const POTION_EFFECT_OPTIONS = [
  "speed",
  "slowness",
  "haste",
  "mining_fatigue",
  "strength",
  "instant_health",
  "instant_damage",
  "jump_boost",
  "nausea",
  "regeneration",
  "resistance",
  "fire_resistance",
  "water_breathing",
  "invisibility",
  "blindness",
  "night_vision",
  "hunger",
  "weakness",
  "poison",
  "wither",
  "health_boost",
  "absorption",
  "saturation",
  "glowing",
  "levitation",
  "luck",
  "unluck",
  "slow_falling",
  "conduit_power",
  "dolphins_grace",
  "bad_omen",
  "hero_of_the_village",
  "darkness",
];

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

const POTION_EFFECT_LABELS = {
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
  darkness: "黑暗",
};

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

const I18N = window.WebShopXI18n || null;
if (I18N) {
  I18N.preparePage("admin", { selectId: "adminLocaleSelect" });
}

const elements = {
  statusChip: document.getElementById("adminStatusChip"),
  adminThemeToggleBtn: document.getElementById("adminThemeToggleBtn"),
  adminIdentifier: document.getElementById("adminIdentifier"),
  adminPassword: document.getElementById("adminPassword"),
  adminLoginBtn: document.getElementById("adminLoginBtn"),
  adminLogoutBtn: document.getElementById("adminLogoutBtn"),
  adminLoginStatus: document.getElementById("adminLoginStatus"),
  adminProfileView: document.getElementById("adminProfileView"),

  redeemShopCoin: document.getElementById("redeemShopCoin"),
  redeemGameCoin: document.getElementById("redeemGameCoin"),
  redeemMaxUses: document.getElementById("redeemMaxUses"),
  redeemPerUserMaxUses: document.getElementById("redeemPerUserMaxUses"),
  redeemExpires: document.getElementById("redeemExpires"),
  redeemCustomCode: document.getElementById("redeemCustomCode"),
  redeemCreateBtn: document.getElementById("redeemCreateBtn"),
  redeemCopyBtn: document.getElementById("redeemCopyBtn"),
  redeemCreateResult: document.getElementById("redeemCreateResult"),
  redeemRefreshBtn: document.getElementById("redeemRefreshBtn"),
  redeemList: document.getElementById("redeemList"),

  productSku: document.getElementById("productSku"),
  productTitle: document.getElementById("productTitle"),
  productCurrency: document.getElementById("productCurrency"),
  productPrice: document.getElementById("productPrice"),
  productDynamicEnabled: document.getElementById("productDynamicEnabled"),
  productDynamicAlgorithm: document.getElementById("productDynamicAlgorithm"),
  productDynamicBasePrice: document.getElementById("productDynamicBasePrice"),
  productDynamicFloorPrice: document.getElementById("productDynamicFloorPrice"),
  productDynamicCapPrice: document.getElementById("productDynamicCapPrice"),
  productDynamicPriceStep: document.getElementById("productDynamicPriceStep"),
  productDynamicParamsJson: document.getElementById("productDynamicParamsJson"),
  productDynamicParamEditor: document.getElementById("productDynamicParamEditor"),
  productDynamicSummary: document.getElementById("productDynamicSummary"),
  productDynamicHelpBtn: document.getElementById("productDynamicHelpBtn"),
  productDynamicParamBasicTabBtn: document.getElementById("productDynamicParamBasicTabBtn"),
  productDynamicParamAdvancedTabBtn: document.getElementById("productDynamicParamAdvancedTabBtn"),
  productDynamicParamBasicPanel: document.getElementById("productDynamicParamBasicPanel"),
  productDynamicParamAdvancedPanel: document.getElementById("productDynamicParamAdvancedPanel"),
  productDynamicAdvancedDetails: document.getElementById("productDynamicAdvancedDetails"),
  productDynamicBasicParams: document.getElementById("productDynamicBasicParams"),
  productDynamicAdvancedParams: document.getElementById("productDynamicAdvancedParams"),
  productPublishAt: document.getElementById("productPublishAt"),
  productUnpublishAt: document.getElementById("productUnpublishAt"),
  productType: document.getElementById("productType"),
  productCommand: document.getElementById("productCommand"),
  productItemMaterial: document.getElementById("productItemMaterial"),
  productStockMode: document.getElementById("productStockMode"),
  productItemAmount: document.getElementById("productItemAmount"),
  productPerUserLimit: document.getElementById("productPerUserLimit"),
  productEffectType: document.getElementById("productEffectType"),
  productEffectSeconds: document.getElementById("productEffectSeconds"),
  productEffectAmplifier: document.getElementById("productEffectAmplifier"),
  productRemark: document.getElementById("productRemark"),
  productActive: document.getElementById("productActive"),
  productItemAmountSlider: document.getElementById("productItemAmountSlider"),
  productAmountPreview: document.getElementById("productAmountPreview"),
  productTotalPreview: document.getElementById("productTotalPreview"),
  productSaveBtn: document.getElementById("productSaveBtn"),
  productRefreshBtn: document.getElementById("productRefreshBtn"),
  productEditorTabBtn: document.getElementById("productEditorTabBtn"),
  productListTabBtn: document.getElementById("productListTabBtn"),
  productVoucherTabBtn: document.getElementById("productVoucherTabBtn"),
  productScheduleHint: document.getElementById("productScheduleHint"),
  productStatus: document.getElementById("productStatus"),
  productListStatus: document.getElementById("productListStatus"),
  productSearchKeyword: document.getElementById("productSearchKeyword"),
  productSearchType: document.getElementById("productSearchType"),
  productSearchActive: document.getElementById("productSearchActive"),
  productList: document.getElementById("productList"),
  groupBuyConsumeCode: document.getElementById("groupBuyConsumeCode"),
  groupBuyConsumeBtn: document.getElementById("groupBuyConsumeBtn"),
  groupBuyConsumeStatus: document.getElementById("groupBuyConsumeStatus"),

  orderStatus: document.getElementById("orderStatus"),
  orderUserId: document.getElementById("orderUserId"),
  orderNo: document.getElementById("orderNo"),
  orderUsername: document.getElementById("orderUsername"),
  orderCurrency: document.getElementById("orderCurrency"),
  orderProductType: document.getElementById("orderProductType"),
  orderKeyword: document.getElementById("orderKeyword"),
  orderRefreshBtn: document.getElementById("orderRefreshBtn"),
  orderStatusView: document.getElementById("orderStatusView"),
  adminOrderList: document.getElementById("adminOrderList"),

  exchangeShopToGameEnabled: document.getElementById("exchangeShopToGameEnabled"),
  exchangeShopToGameRatio: document.getElementById("exchangeShopToGameRatio"),
  exchangeGameToShopEnabled: document.getElementById("exchangeGameToShopEnabled"),
  exchangeGameToShopRatio: document.getElementById("exchangeGameToShopRatio"),
  exchangeSaveBtn: document.getElementById("exchangeSaveBtn"),
  exchangeStatusView: document.getElementById("exchangeStatusView"),

  marketFeePercent: document.getElementById("marketFeePercent"),
  marketTaxPercent: document.getElementById("marketTaxPercent"),
  marketEconomySaveBtn: document.getElementById("marketEconomySaveBtn"),
  marketEconomyStatusView: document.getElementById("marketEconomyStatusView"),
  vaultStatusView: document.getElementById("vaultStatusView"),
  leaderboardEnabled: document.getElementById("leaderboardEnabled"),
  leaderboardShowOnlineStatus: document.getElementById("leaderboardShowOnlineStatus"),
  leaderboardDefaultMetric: document.getElementById("leaderboardDefaultMetric"),
  leaderboardDefaultOrder: document.getElementById("leaderboardDefaultOrder"),
  leaderboardSaveBtn: document.getElementById("leaderboardSaveBtn"),
  leaderboardStatusView: document.getElementById("leaderboardStatusView"),

  marketStatus: document.getElementById("marketStatus"),
  marketSeller: document.getElementById("marketSeller"),
  marketBuyer: document.getElementById("marketBuyer"),
  marketMaterial: document.getElementById("marketMaterial"),
  marketCurrency: document.getElementById("marketCurrency"),
  marketKeyword: document.getElementById("marketKeyword"),
  marketRefreshBtn: document.getElementById("marketRefreshBtn"),
  marketStatusView: document.getElementById("marketStatusView"),
  adminMarketList: document.getElementById("adminMarketList"),
  materialSuggestList: document.getElementById("materialSuggestList"),
  potionEffectSuggestList: document.getElementById("potionEffectSuggestList"),

  userIdentifier: document.getElementById("userIdentifier"),
  userSearchBtn: document.getElementById("userSearchBtn"),
  userLookupStatus: document.getElementById("userLookupStatus"),
  userListKeyword: document.getElementById("userListKeyword"),
  userListHideInactiveToggle: document.getElementById("userListHideInactiveToggle"),
  userListRefreshBtn: document.getElementById("userListRefreshBtn"),
  userListStatus: document.getElementById("userListStatus"),
  userList: document.getElementById("userList"),
  userInfoBox: document.getElementById("userInfoBox"),
  userNewPassword: document.getElementById("userNewPassword"),
  userResetPasswordBtn: document.getElementById("userResetPasswordBtn"),
  userUnbindBtn: document.getElementById("userUnbindBtn"),
  userForceLogoutBtn: document.getElementById("userForceLogoutBtn"),
  walletCurrency: document.getElementById("walletCurrency"),
  walletDelta: document.getElementById("walletDelta"),
  walletReason: document.getElementById("walletReason"),
  walletAdjustBtn: document.getElementById("walletAdjustBtn"),
  userActionStatus: document.getElementById("userActionStatus"),

  adminManagerIdentifier: document.getElementById("adminManagerIdentifier"),
  adminManagerTemplate: document.getElementById("adminManagerTemplate"),
  adminManagerTemplateHint: document.getElementById("adminManagerTemplateHint"),
  adminManagerType: document.getElementById("adminManagerType"),
  adminPermissionGroups: document.getElementById("adminPermissionGroups"),
  adminManagerSaveBtn: document.getElementById("adminManagerSaveBtn"),
  adminManagerClearBtn: document.getElementById("adminManagerClearBtn"),
  adminManagerRefreshBtn: document.getElementById("adminManagerRefreshBtn"),
  adminManagerStatus: document.getElementById("adminManagerStatus"),
  adminManagerListStatus: document.getElementById("adminManagerListStatus"),
  adminManagerList: document.getElementById("adminManagerList"),

  auditRefreshBtn: document.getElementById("auditRefreshBtn"),
  auditList: document.getElementById("auditList"),

  snackbarHost: document.getElementById("snackbarHost"),
};
const tabs = Array.from(document.querySelectorAll(".top-tab"));
const panels = Array.from(document.querySelectorAll(".tab-panel"));

function localizeDisplayText(text) {
  return I18N ? I18N.localizeText(text) : text;
}

function setNodeText(node, text) {
  if (!node) {
    return;
  }
  node.textContent = localizeDisplayText(text);
}

function notify(message, tone = "info", durationMs = 3200) {
  if (!elements.snackbarHost) {
    return;
  }
  const normalized = ["info", "success", "warn", "error"].includes(tone) ? tone : "info";
  const node = document.createElement("div");
  node.className = `snackbar snackbar-${normalized}`;
  node.textContent = localizeDisplayText(message);
  elements.snackbarHost.appendChild(node);
  requestAnimationFrame(() => node.classList.add("show"));
  setTimeout(() => {
    node.classList.remove("show");
    setTimeout(() => node.remove(), 200);
  }, durationMs);
}

const THEME_STORAGE_KEY = "webshopx_theme";

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
  if (elements.adminThemeToggleBtn) {
    elements.adminThemeToggleBtn.textContent = I18N
      ? I18N.getThemeToggleLabel(normalized)
      : (normalized === "dark" ? "切换亮色" : "切换暗色");
  }
}

function toggleTheme() {
  applyTheme(state.theme === "dark" ? "light" : "dark");
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

function setStatus(text, stateName) {
  elements.statusChip.textContent = localizeDisplayText(text);
  elements.statusChip.dataset.state = stateName;
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

const ADMIN_TAB_PATH_MAP = {
  login: "/admin/login",
  products: "/admin/products",
  market: "/admin/market",
  orders: "/admin/orders",
  redeem: "/admin/redeem",
  economy: "/admin/economy",
  users: "/admin/users",
  admins: "/admin/admins",
  audit: "/admin/audit"
};

const ADMIN_PATH_TAB_MAP = {
  "/admin": "login",
  "/admin/login": "login",
  "/admin/products": "products",
  "/admin/market": "market",
  "/admin/orders": "orders",
  "/admin/redeem": "redeem",
  "/admin/economy": "economy",
  "/admin/users": "users",
  "/admin/admins": "admins",
  "/admin/audit": "audit"
};

function switchTab(tabName, skipHistory = false) {
  state.activeTab = tabName;
  tabs.forEach((tab) => tab.classList.toggle("active", tab.dataset.tabTarget === tabName));
  panels.forEach((panel) => panel.classList.toggle("active", panel.dataset.tabPanel === tabName));

  if (!skipHistory && ADMIN_TAB_PATH_MAP[tabName]) {
    const newPath = ADMIN_TAB_PATH_MAP[tabName];
    if (window.location.pathname !== newPath) {
      window.history.pushState({ tab: tabName }, "", newPath);
    }
  }
  if (tabName === "products" && state.token) {
    loadProducts();
  }
  if (tabName === "orders" && state.token) {
    loadAdminOrders();
  }
  if (tabName === "economy" && state.token) {
    loadEconomySettings();
  }
  if (tabName === "users" && state.token) {
    loadUserList();
  }
  if (tabName === "admins" && state.token) {
    loadAdminManagerData();
  }
}

tabs.forEach((tab) => tab.addEventListener("click", () => switchTab(tab.dataset.tabTarget)));

window.addEventListener("popstate", (event) => {
  if (event.state && event.state.tab) {
    switchTab(event.state.tab, true);
  } else {
    // Fallback if no state
    const path = window.location.pathname;
    const tabName = ADMIN_PATH_TAB_MAP[path] || "login";
    switchTab(tabName, true);
  }
});

// Initialize routing based on URL
window.addEventListener("load", () => {
  const path = window.location.pathname;
  const tabName = ADMIN_PATH_TAB_MAP[path] || "login";
  switchTab(tabName, true);
});

async function runAutoSyncTick() {
  if (!state.token || state.autoSyncBusy) {
    return;
  }
  state.autoSyncBusy = true;
  try {
    if (state.activeTab === "orders") {
      await loadAdminOrders();
    } else if (state.activeTab === "market") {
      await loadMarket();
    } else if (state.activeTab === "redeem") {
      await loadRedeemList();
    } else if (state.activeTab === "products") {
      await loadProducts();
    } else if (state.activeTab === "audit") {
      await loadAuditLogs();
    } else if (state.activeTab === "economy") {
      await loadEconomySettings();
    } else if (state.activeTab === "users") {
      await loadUserList();
    } else if (state.activeTab === "admins") {
      await loadAdminManagerList();
    }
  } catch (error) {
    // ignore transient auto-sync failures
  } finally {
    state.autoSyncBusy = false;
  }
}

function startAdminAutoSync() {
  stopAdminAutoSync();
  if (!state.token) {
    return;
  }
  runAutoSyncTick();
  state.autoSyncTimer = window.setInterval(() => {
    runAutoSyncTick();
  }, 10000);
}

function stopAdminAutoSync() {
  if (state.autoSyncTimer) {
    clearInterval(state.autoSyncTimer);
    state.autoSyncTimer = null;
  }
  state.autoSyncBusy = false;
}

async function apiAdmin(path, options = {}) {
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
    payload = { message: await response.text() };
  }
  if (!response.ok) {
    const error = new Error(payload.message || payload.error || `HTTP ${response.status}`);
    error.code = payload.error || "";
    throw error;
  }
  return payload;
}

async function loadCurrencyMeta() {
  try {
    const payload = await fetch(resolveApiUrl("/api/meta/currency"), { method: "GET" }).then((res) => res.json());
    if (payload && payload.shopCoin) {
      state.currencyMeta.SHOP_COIN = {
        name: payload.shopCoin.name || state.currencyMeta.SHOP_COIN.name,
        short: payload.shopCoin.short || state.currencyMeta.SHOP_COIN.short,
      };
    }
    if (payload && payload.gameCoin) {
      state.currencyMeta.GAME_COIN = {
        name: payload.gameCoin.name || state.currencyMeta.GAME_COIN.name,
        short: payload.gameCoin.short || state.currencyMeta.GAME_COIN.short,
      };
    }
    if (payload && payload.timeZone) {
      state.timeZone = String(payload.timeZone).trim() || state.timeZone;
    }
    applyCurrencyMetaToUi();
    updateProductScheduleHint();
  } catch (error) {
    // Ignore missing metadata.
  }
}

function currencyName(currency) {
  const meta = state.currencyMeta[currency] || { name: String(currency || "--") };
  return meta.name;
}

function applyCurrencyMetaToUi() {
  const shopName = currencyName("SHOP_COIN");
  const gameName = currencyName("GAME_COIN");

  const applyText = (id, text) => {
    const node = document.getElementById(id);
    if (node) {
      node.textContent = text;
    }
  };

  const updateSelect = (select) => {
    if (!select) {
      return;
    }
    Array.from(select.options || []).forEach((option) => {
      if (option.value === "SHOP_COIN") {
        option.textContent = shopName;
      }
      if (option.value === "GAME_COIN") {
        option.textContent = gameName;
      }
    });
  };

  updateSelect(elements.productCurrency);
  updateSelect(elements.orderCurrency);
  updateSelect(elements.marketCurrency);
  updateSelect(elements.walletCurrency);

  applyText("adminRedeemShopCoinLabel", shopName);
  applyText("adminRedeemGameCoinLabel", gameName);
  applyText("economyShopCoinName", shopName);
  applyText("economyGameCoinName", gameName);
  applyText("exchangeShopToGameNameA", shopName);
  applyText("exchangeShopToGameNameB", gameName);
  applyText("exchangeShopToGameNameC", shopName);
  applyText("exchangeShopToGameNameD", gameName);
  applyText("exchangeGameToShopNameA", gameName);
  applyText("exchangeGameToShopNameB", shopName);
  applyText("exchangeGameToShopNameC", gameName);
  applyText("exchangeGameToShopNameD", shopName);
  applyText("vaultGameCoinName", gameName);
}

function ensureAdmin() {
  if (!state.token) {
    throw new Error("请先登录管理员账号。");
  }
}

function formatCurrency(amount, currency) {
  const value = Number(amount || 0);
  const normalized = Number.isNaN(value) ? 0 : value;
  const meta = state.currencyMeta[currency] || { short: String(currency) };
  return `${meta.short} ${normalized.toLocaleString(I18N ? I18N.getIntlLocale() : "zh-CN")}`;
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

function collectDateTimeParts(timestamp, options) {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone: state.timeZone,
    hour12: false,
    ...options,
  });
  const formatted = {};
  formatter.formatToParts(new Date(timestamp)).forEach((part) => {
    if (part.type !== "literal") {
      formatted[part.type] = part.value;
    }
  });
  return formatted;
}

function formatDateTime(value) {
  const timestamp = parseDateTimeValue(value);
  if (Number.isNaN(timestamp)) {
    return "未知时间";
  }
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

function toLocalInput(value) {
  if (!value) {
    return "";
  }
  const timestamp = parseDateTimeValue(value);
  if (Number.isNaN(timestamp)) {
    return "";
  }
  const parts = collectDateTimeParts(timestamp, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

function updateProductScheduleHint() {
  if (!elements.productScheduleHint) {
    return;
  }
  setNodeText(elements.productScheduleHint, `商品上下架时间按时区 ${state.timeZone} 解释与显示。`);
}

function renderAdminProfile() {
  if (!state.admin) {
    setNodeText(elements.adminProfileView, "未登录");
    return;
  }
  const roleLabel = state.admin.isSuperAdmin ? "SUPER_ADMIN" : (state.admin.role || "CUSTOM");
  const permissionCount = Array.isArray(state.admin.permissions) ? state.admin.permissions.length : 0;
  const suffix = state.admin.isSuperAdmin
    ? "拥有全部权限"
    : `权限数：${permissionCount}${state.admin.canManageAdmins ? " | 可管理管理员" : ""}`;
  setNodeText(elements.adminProfileView, `账号：${state.admin.username} | 身份：${roleLabel} | ${suffix}`);
}

function setLoggedOut() {
  state.token = null;
  state.admin = null;
  state.adminManagers = [];
  state.adminMeta = null;
  state.selectedAdminManager = null;
  state.realtime.orderDigest = {};
  state.realtime.marketDigest = {};
  sessionStorage.removeItem("webshop_admin_token");
  stopAdminAutoSync();
  setStatus("未登录", "offline");
  renderAdminProfile();
  setMetaText(elements.adminLoginStatus, "已退出登录", "info");
}

async function loginAdmin() {
  const identifier = elements.adminIdentifier.value.trim();
  const password = elements.adminPassword.value.trim();
  if (!identifier || !password) {
    throw new Error("请输入管理员账号和密码。");
  }
  const payload = await apiAdmin("/api/admin/auth/login", {
    method: "POST",
    body: JSON.stringify({ identifier, password }),
  });
  state.token = payload.sessionToken;
  state.admin = payload.admin;
  sessionStorage.setItem("webshop_admin_token", state.token);
  setStatus(`已登录：${state.admin.username}`, "online");
  renderAdminProfile();
  setMetaText(elements.adminLoginStatus, "登录成功", "success");
  if (state.activeTab === "admins") {
    await loadAdminManagerData();
  }
  startAdminAutoSync();
}

async function loadAdminProfile() {
  if (!state.token) {
    return;
  }
  try {
    const payload = await apiAdmin("/api/admin/auth/me", { method: "GET" });
    state.admin = payload;
    setStatus(`已登录：${state.admin.username}`, "online");
    renderAdminProfile();
    if (state.activeTab === "admins") {
      await loadAdminManagerData();
    }
    startAdminAutoSync();
  } catch (error) {
    setLoggedOut();
  }
}

function renderList(container, rows) {
  container.innerHTML = "";
  if (!rows || rows.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    setNodeText(empty, "暂无数据");
    container.appendChild(empty);
    return;
  }
  rows.forEach((row) => container.appendChild(row));
}

function renderKeyValueCard(title, items, actions = []) {
  const card = document.createElement("div");
  card.className = "admin-card";
  const header = document.createElement("div");
  header.className = "admin-row";
  const h = document.createElement("strong");
  setNodeText(h, title);
  header.appendChild(h);
  if (actions.length > 0) {
    const actionWrap = document.createElement("div");
    actionWrap.className = "admin-actions";
    actions.forEach((action) => actionWrap.appendChild(action));
    header.appendChild(actionWrap);
  }
  card.appendChild(header);
  items.forEach((item) => {
    const row = document.createElement("div");
    row.className = "admin-row";
    const label = document.createElement("span");
    label.className = "admin-key";
    setNodeText(label, item.label);
    const value = document.createElement("span");
    value.className = "admin-value";
    setNodeText(value, item.value);
    row.appendChild(label);
    row.appendChild(value);
    card.appendChild(row);
  });
  return card;
}

const productPanels = Array.from(document.querySelectorAll("[data-product-panel]"));

function setProductPanel(panelName) {
  const normalized = ["editor", "list", "voucher"].includes(panelName) ? panelName : "editor";
  state.productPanel = normalized;
  if (elements.productEditorTabBtn) {
    elements.productEditorTabBtn.classList.toggle("active", normalized === "editor");
  }
  if (elements.productListTabBtn) {
    elements.productListTabBtn.classList.toggle("active", normalized === "list");
  }
  if (elements.productVoucherTabBtn) {
    elements.productVoucherTabBtn.classList.toggle("active", normalized === "voucher");
  }
  productPanels.forEach((panel) => {
    panel.classList.toggle("active", panel.dataset.productPanel === normalized);
  });
}

function syncProductAmountSlider(source = "input") {
  const isUnlimited = elements.productStockMode?.value === "UNLIMITED";
  const slider = elements.productItemAmountSlider;
  const input = elements.productItemAmount;
  if (input) {
    input.disabled = isUnlimited;
    input.placeholder = isUnlimited ? "无限库存" : "";
  }
  if (slider) {
    slider.disabled = isUnlimited;
  }
  if (isUnlimited) {
    if (input && String(input.value || "").trim()) {
      input.dataset.lastFiniteValue = input.value;
      input.value = "";
    }
    if (elements.productAmountPreview) {
      setNodeText(elements.productAmountPreview, "无限");
    }
    if (elements.productTotalPreview) {
      setNodeText(elements.productTotalPreview, "不限");
    }
    return;
  }
  if (input && !String(input.value || "").trim()) {
    input.value = input.dataset.lastFiniteValue || (slider ? slider.value : "64") || "64";
  }
  const inputValue = Number(input?.value || 1);
  const sliderValue = Number(slider?.value || 1);
  const rawValue = source === "slider" ? sliderValue : inputValue;
  const normalized = Math.max(1, Math.floor(Number.isFinite(rawValue) ? rawValue : 1));
  if (slider && normalized > Number(slider.max || 256)) {
    slider.max = String(normalized);
  }
  if (input) {
    input.value = String(normalized);
    input.dataset.lastFiniteValue = String(normalized);
  }
  if (slider) {
    slider.value = String(normalized);
  }
  if (elements.productAmountPreview) {
    setNodeText(elements.productAmountPreview, `x${normalized}`);
  }
  const currency = elements.productCurrency?.value || "SHOP_COIN";
  const unitPrice = Number(elements.productPrice?.value || 0);
  const total = Math.max(0, Math.floor(Number.isFinite(unitPrice) ? unitPrice : 0)) * normalized;
  if (elements.productTotalPreview) {
    setNodeText(elements.productTotalPreview, formatCurrency(total, currency));
  }
}

function localizeOrderStatusOptions() {
  if (!elements.orderStatus) {
    return;
  }
  const labels = {
    "": "全部",
    PENDING: "待发放",
    WAIT_CLAIM: "待领取",
    DELIVERED: "已发放",
    REFUNDED: "已退款",
    FAILED: "失败",
    RECYCLED: "已回收",
  };
  Array.from(elements.orderStatus.options).forEach((option) => {
    const value = String(option.value || "").trim().toUpperCase();
    if (Object.prototype.hasOwnProperty.call(labels, value)) {
      setNodeText(option, labels[value]);
    }
  });
}

function buildAdminOrderDigest(orders) {
  const digest = {};
  (orders || []).forEach((order) => {
    const key = String(order.orderNo || "");
    if (!key) {
      return;
    }
    digest[key] = [
      String(order.status || ""),
      String(order.refundedAt || ""),
      String(order.deliveredAt || ""),
      String(order.groupBuyVoucherStatus || ""),
    ].join("|");
  });
  return digest;
}

function notifyAdminOrderTransitions(previousDigest, orders) {
  const previous = previousDigest || {};
  if (Object.keys(previous).length === 0) {
    return;
  }
  const changes = [];
  (orders || []).forEach((order) => {
    const orderNo = String(order.orderNo || "");
    if (!orderNo) {
      return;
    }
    const current = [
      String(order.status || ""),
      String(order.refundedAt || ""),
      String(order.deliveredAt || ""),
      String(order.groupBuyVoucherStatus || ""),
    ].join("|");
    const old = previous[orderNo];
    if (!old || old === current) {
      return;
    }
    const status = String(order.status || "").toUpperCase();
    if (status === "DELIVERED") {
      changes.push(`订单状态变更：${orderNo} 已发放`);
    } else if (status === "WAIT_CLAIM") {
      changes.push(`订单状态变更：${orderNo} 待领取`);
    } else if (status === "REFUNDED") {
      changes.push(`订单状态变更：${orderNo} 已退款`);
    } else if (status === "PENDING") {
      changes.push(`订单状态变更：${orderNo} 待发放`);
    } else {
      changes.push(`订单状态变更：${orderNo} -> ${status || "UNKNOWN"}`);
    }
  });
  changes.slice(0, 3).forEach((message) => notify(message, "info"));
}

function buildAdminMarketDigest(listings) {
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

function notifyAdminMarketTransitions(previousDigest, listings) {
  const previous = previousDigest || {};
  if (Object.keys(previous).length === 0) {
    return;
  }
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
    if (!old || old === current) {
      return;
    }
    const oldParts = old.split("|");
    const oldQty = Number(oldParts[1] || listing.quantity || 0);
    const newQty = Number(listing.quantity || 0);
    const status = String(listing.status || "").toUpperCase();
    if (status === "SOLD") {
      changes.push(`上架 #${listing.id} 已售出`);
      return;
    }
    if (status === "UNLISTED") {
      changes.push(`上架 #${listing.id} 已下架`);
      return;
    }
    if (Number.isFinite(oldQty) && Number.isFinite(newQty) && newQty < oldQty) {
      changes.push(`上架 #${listing.id} 发生部分成交，剩余 ${newQty}`);
    }
  });
  changes.slice(0, 3).forEach((message) => notify(message, "info"));
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
  return I18N
    ? I18N.humanizeEnum(materialKey)
    : String(materialKey || "")
      .toLowerCase()
      .split("_")
      .filter(Boolean)
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(" ");
}

function getLocalizedMaterialName(material) {
  const key = normalizeMaterialKey(material);
  const aliasKey = aliasMaterialKey(key);
  if (!key) {
    return localizeDisplayText("未知物品");
  }
  return state.materialMap[key] || state.materialMap[aliasKey] || humanizeMaterial(aliasKey || key);
}

function buildMaterialLookup() {
  const lookup = {};
  const allow = state.materialAllowSet;
  Object.entries(state.materialMap || {}).forEach(([key, zhName]) => {
    const normalizedKey = normalizeMaterialKey(key);
    const aliasKey = aliasMaterialKey(normalizedKey);
    if (
      allow.size > 0
      && !allow.has(normalizedKey)
      && !allow.has(aliasKey)
    ) {
      return;
    }
    const normalizedZh = String(zhName || "").trim().toLowerCase();
    const candidates = [normalizedKey, aliasKey].filter(Boolean);
    candidates.forEach((candidate) => {
      lookup[candidate.toLowerCase()] = candidate;
      lookup[humanizeMaterial(candidate).toLowerCase()] = candidate;
    });
    if (normalizedZh && candidates.length > 0) {
      lookup[normalizedZh] = candidates[0];
    }
  });
  allow.forEach((candidate) => {
    lookup[candidate.toLowerCase()] = candidate;
    lookup[humanizeMaterial(candidate).toLowerCase()] = candidate;
  });
  state.materialLookup = lookup;
}

function resolveMaterialInput(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return "";
  }
  const normalizedKey = normalizeMaterialKey(text);
  const aliasKey = aliasMaterialKey(normalizedKey);
  let candidate = "";
  if (normalizedKey && state.materialMap[normalizedKey]) {
    candidate = normalizedKey;
  } else if (aliasKey && state.materialMap[aliasKey]) {
    candidate = aliasKey;
  } else if (normalizedKey && state.materialLookup[normalizedKey.toLowerCase()]) {
    candidate = state.materialLookup[normalizedKey.toLowerCase()];
  } else if (aliasKey && state.materialLookup[aliasKey.toLowerCase()]) {
    candidate = state.materialLookup[aliasKey.toLowerCase()];
  } else {
    candidate = state.materialLookup[text.toLowerCase()] || aliasKey || normalizedKey;
  }

  if (state.materialAllowSet.size === 0) {
    return candidate;
  }
  if (candidate && state.materialAllowSet.has(candidate)) {
    return candidate;
  }
  const candidateAlias = aliasMaterialKey(candidate);
  if (candidateAlias && state.materialAllowSet.has(candidateAlias)) {
    return candidateAlias;
  }
  return "";
}

function populateMaterialSuggest() {
  if (!elements.materialSuggestList) {
    return;
  }
  elements.materialSuggestList.innerHTML = "";
  const allow = state.materialAllowSet;
  const keys = allow.size > 0
    ? Array.from(allow)
    : Object.keys(state.materialMap || {}).map((key) => normalizeMaterialKey(key));
  keys
    .filter(Boolean)
    .sort((a, b) => String(a).localeCompare(String(b), I18N ? I18N.getIntlLocale() : "en"))
    .forEach((key) => {
      const zhName = state.materialMap[key] || state.materialMap[aliasMaterialKey(key)] || key;
      const option = document.createElement("option");
      const label = !zhName || zhName === key ? key : `${zhName} (${key})`;
      option.value = key;
      option.label = label;
      setNodeText(option, label);
      elements.materialSuggestList.appendChild(option);
  });
}

function populatePotionEffectSuggest() {
  if (!elements.potionEffectSuggestList) {
    return;
  }
  elements.potionEffectSuggestList.innerHTML = "";
  POTION_EFFECT_OPTIONS.forEach((effect) => {
    const option = document.createElement("option");
    option.value = effect;
    const localizedEffect = I18N ? I18N.getPotionEffectLabel(effect) : (POTION_EFFECT_LABELS[effect] || effect);
    const label = localizedEffect ? `${localizedEffect} (${effect})` : effect;
    option.label = label;
    setNodeText(option, label);
    elements.potionEffectSuggestList.appendChild(option);
  });
}

async function ensureMaterialAllowList() {
  if (state.materialAllowReady) {
    return;
  }
  if (state.materialAllowPromise) {
    await state.materialAllowPromise;
    return;
  }
  state.materialAllowPromise = fetch(resolveApiUrl("/api/meta/materials"))
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
      state.materialAllowSet = allow;
      state.materialAllowReady = true;
    })
    .catch(() => {
      state.materialAllowSet = new Set();
      state.materialAllowReady = true;
    });
  await state.materialAllowPromise;
}

async function ensureMaterialMap() {
  if (state.materialMapReady) {
    return;
  }
  if (state.materialMapPromise) {
    await state.materialMapPromise;
    return;
  }
  state.materialMapPromise = ensureMaterialAllowList()
    .then(() => {
      if (!I18N || !I18N.shouldLoadMaterialMap()) {
        return {};
      }
      return fetch(`i18n/materials/${I18N.getLocale()}.json`).then((response) => {
        if (!response.ok) {
          throw new Error(`material map load failed: ${response.status}`);
        }
        return response.json();
      });
    })
    .then((json) => {
      state.materialMap = json || {};
      state.materialMapReady = true;
      buildMaterialLookup();
      populateMaterialSuggest();
    })
    .catch(() => {
      state.materialMap = {};
      state.materialLookup = {};
      state.materialMapReady = true;
    });
  await state.materialMapPromise;
}

let productDynamicBasicParamEntries = [];
let productDynamicAdvancedParamEntries = [];

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
    `i18n/market-algorithms/${locale}.json`,
    "i18n/market-algorithms/zh-CN.json",
    "i18n/market-algorithms/en-US.json",
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
        return;
      } catch (error) {
        continue;
      }
    }

    state.marketAlgorithmGlossary = normalizeMarketAlgorithmGlossary({});
    state.marketAlgorithmGlossaryReady = true;
  })();

  await state.marketAlgorithmGlossaryPromise;
}

function getAlgorithmCatalog(type) {
  const key = type === "auction" ? "auction" : "dynamic";
  const fallback = key === "auction"
    ? FALLBACK_MARKET_ALGORITHM_GLOSSARY.auction
    : FALLBACK_MARKET_ALGORITHM_GLOSSARY.dynamic;
  const catalog = state.marketAlgorithmGlossary?.[key];
  return Array.isArray(catalog) && catalog.length > 0 ? catalog : fallback;
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

function parseAlgorithmParamsJsonStrict(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return {};
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error("算法参数 JSON 格式无效，请输入对象格式。");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("算法参数 JSON 必须是对象格式，例如 {\"k\": 1.2}。");
  }
  return parsed;
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
  if (!host) {
    return entries;
  }
  const advancedOnly = Boolean(options.advancedOnly);
  const emptyMessage = options.emptyMessage !== undefined ? options.emptyMessage : "当前算法无额外参数。";
  const filteredSchemas = Array.isArray(paramSchemas)
    ? paramSchemas.filter((schema) => Boolean(schema?.advanced) === advancedOnly)
    : [];
  host.innerHTML = "";
  if (filteredSchemas.length === 0) {
    if (emptyMessage) {
      const hint = document.createElement("p");
      hint.className = "field-hint";
      setNodeText(hint, emptyMessage);
      host.appendChild(hint);
    }
    return entries;
  }

  for (const schema of filteredSchemas) {
    const field = document.createElement("label");
    field.className = "field dialog-select-field";

    const title = document.createElement("span");
    const requiredSuffix = schema.required ? " *" : "";
    setNodeText(title, `${schema.label}${requiredSuffix}`);
    field.appendChild(title);

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
      const desc = document.createElement("p");
      desc.className = "field-hint";
      setNodeText(desc, schema.description);
      host.appendChild(desc);
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

function stripKnownDynamicParamKeys(paramPayload) {
  const payload = paramPayload && typeof paramPayload === "object" ? { ...paramPayload } : {};
  const blockedKeys = new Set();
  const catalog = getAlgorithmCatalog("dynamic");
  (catalog || []).forEach((definition) => {
    (definition.params || []).forEach((schema) => {
      blockedKeys.add(schema.key);
      const aliases = PARAM_KEY_ALIAS_MAP[schema.key];
      if (Array.isArray(aliases)) {
        aliases.forEach((alias) => blockedKeys.add(alias));
      }
    });
  });
  if (blockedKeys.size === 0) {
    return payload;
  }
  Object.keys(payload).forEach((key) => {
    if (blockedKeys.has(key)) {
      delete payload[key];
    }
  });
  return payload;
}

function buildProductDynamicParamsJson() {
  const rawJson = String(elements.productDynamicParamsJson?.value || "").trim();
  const basePayload = parseAlgorithmParamsJsonStrict(rawJson);
  const entries = productDynamicBasicParamEntries.concat(productDynamicAdvancedParamEntries);
  const paramPayload = collectAlgorithmParamValues(entries);
  if (paramPayload === undefined) {
    throw new Error("动态算法参数无效，请检查必填项与数值范围。");
  }
  const merged = stripKnownDynamicParamKeys(basePayload);
  Object.entries(paramPayload).forEach(([key, value]) => {
    merged[key] = value;
  });
  return Object.keys(merged).length > 0 ? JSON.stringify(merged) : null;
}

function switchProductDynamicParamTab(target) {
  const showAdvanced = target === "advanced";
  if (elements.productDynamicParamBasicTabBtn) {
    elements.productDynamicParamBasicTabBtn.classList.toggle("is-active", !showAdvanced);
  }
  if (elements.productDynamicParamAdvancedTabBtn) {
    elements.productDynamicParamAdvancedTabBtn.classList.toggle("is-active", showAdvanced);
  }
  if (elements.productDynamicParamBasicPanel) {
    elements.productDynamicParamBasicPanel.style.display = showAdvanced ? "none" : "grid";
  }
  if (elements.productDynamicParamAdvancedPanel) {
    elements.productDynamicParamAdvancedPanel.style.display = showAdvanced ? "grid" : "none";
  }
  if (showAdvanced && elements.productDynamicAdvancedDetails) {
    elements.productDynamicAdvancedDetails.open = true;
  }
}

function renderProductDynamicParamEditors() {
  const algorithm = String(elements.productDynamicAlgorithm?.value || "").trim().toUpperCase();
  const definition = getAlgorithmDefinition("dynamic", algorithm);
  const paramValues = parseAlgorithmParamsJson(elements.productDynamicParamsJson?.value);
  productDynamicBasicParamEntries = renderAlgorithmParamEditors(
    elements.productDynamicBasicParams,
    definition?.params || [],
    paramValues,
    { advancedOnly: false, emptyMessage: "" }
  );
  productDynamicAdvancedParamEntries = renderAlgorithmParamEditors(
    elements.productDynamicAdvancedParams,
    definition?.params || [],
    paramValues,
    { advancedOnly: true, emptyMessage: "当前算法暂无高级参数。" }
  );
  if (elements.productDynamicSummary) {
    setNodeText(elements.productDynamicSummary, definition?.summary || "当前算法暂无额外说明。");
  }
}

function populateProductDynamicAlgorithmSelect() {
  if (!elements.productDynamicAlgorithm) {
    return;
  }
  const catalog = getAlgorithmCatalog("dynamic");
  if (!Array.isArray(catalog) || catalog.length === 0) {
    return;
  }
  const current = String(elements.productDynamicAlgorithm.value || "").trim().toUpperCase();
  elements.productDynamicAlgorithm.innerHTML = "";

  catalog.forEach((item) => {
    const option = document.createElement("option");
    option.value = item.id;
    option.textContent = item.label || item.id;
    elements.productDynamicAlgorithm.appendChild(option);
  });

  const matched = catalog.some((item) => String(item.id || "").toUpperCase() === current);
  if (!matched && current) {
    const customOption = document.createElement("option");
    customOption.value = current;
    customOption.textContent = current;
    elements.productDynamicAlgorithm.appendChild(customOption);
  }

  elements.productDynamicAlgorithm.value = matched
    ? current
    : (current || catalog[0].id);
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
  window.open(`help.html?${query.toString()}#${encodeURIComponent(anchor)}`, "_blank", "noopener");
}

function setProductFieldVisible(inputElement, visible) {
  if (!inputElement) {
    return;
  }
  const field = inputElement.closest(".field");
  if (!field) {
    return;
  }
  field.classList.toggle("hidden", !visible);
}

function updateProductTypeFieldsVisibility(typeRaw) {
  const type = String(typeRaw || elements.productType.value || "COMMAND")
    .trim()
    .toUpperCase();
  const commandVisible = type === "COMMAND";
  const itemVisible = type === "GIVE_ITEM" || type === "RECYCLE_ITEM";
  const effectVisible = type === "POTION_EFFECT";
  const dynamicVisible = itemVisible;
  setProductFieldVisible(elements.productCommand, commandVisible);
  setProductFieldVisible(elements.productItemMaterial, itemVisible);
  setProductFieldVisible(elements.productItemAmount, true);
  setProductFieldVisible(elements.productEffectType, effectVisible);
  setProductFieldVisible(elements.productEffectSeconds, effectVisible);
  setProductFieldVisible(elements.productEffectAmplifier, effectVisible);
  setProductFieldVisible(elements.productDynamicEnabled, dynamicVisible);
  setProductFieldVisible(elements.productDynamicAlgorithm, dynamicVisible);
  setProductFieldVisible(elements.productDynamicBasePrice, dynamicVisible);
  setProductFieldVisible(elements.productDynamicFloorPrice, dynamicVisible);
  setProductFieldVisible(elements.productDynamicCapPrice, dynamicVisible);
  setProductFieldVisible(elements.productDynamicPriceStep, dynamicVisible);
  setProductFieldVisible(elements.productDynamicParamEditor, dynamicVisible);
  setProductFieldVisible(elements.productDynamicParamsJson, dynamicVisible);
  if (!dynamicVisible && elements.productDynamicEnabled) {
    elements.productDynamicEnabled.value = "false";
  }
}

function parseOptionalPositiveWhole(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return null;
  }
  const parsed = Number(text);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return null;
  }
  return Math.floor(parsed);
}

async function createRedeemCode() {
  ensureAdmin();
  const shopCoin = Number(elements.redeemShopCoin.value || 0);
  const gameCoin = Number(elements.redeemGameCoin.value || 0);
  const maxUses = Number(elements.redeemMaxUses.value || 1);
  const perUserMaxUses = Number(elements.redeemPerUserMaxUses.value || 1);
  const expiresInMinutes = elements.redeemExpires.value.trim();
  const customCode = elements.redeemCustomCode.value.trim();
  const body = {
    shopCoin,
    gameCoin,
    maxUses,
    perUserMaxUses,
  };
  if (expiresInMinutes) {
    body.expiresInMinutes = Number(expiresInMinutes);
  }
  if (customCode) {
    body.customCode = customCode;
  }
  const payload = await apiAdmin("/api/admin/redeem/create", {
    method: "POST",
    body: JSON.stringify(body),
  });
  state.latestRedeemCode = payload.code || null;
  setMetaText(elements.redeemCreateResult, `兑换码已生成：${payload.code}`, "success");
  notify(`兑换码生成成功：${payload.code}`, "success");
  await loadRedeemList();
}

async function loadRedeemList() {
  ensureAdmin();
  const payload = await apiAdmin("/api/admin/redeem/list?limit=200", { method: "GET" });
  const rows = payload.codes.map((code) =>
    renderKeyValueCard(
      code.code,
      [
        { label: currencyName("SHOP_COIN"), value: code.shopCoin },
        { label: currencyName("GAME_COIN"), value: code.gameCoin },
        { label: "已用 / 总次数", value: `${code.usedCount}/${code.maxUses}` },
        { label: "单账号上限", value: code.perUserMaxUses || 1 },
        { label: "有效期", value: code.expiresAt || "永久" },
        { label: "状态", value: code.active ? "启用" : "停用" },
      ]
    )
  );
  renderList(elements.redeemList, rows);
}

function getProductInput() {
  const isUnlimited = elements.productStockMode?.value === "UNLIMITED";
  const rawItemAmount = String(elements.productItemAmount.value || "").trim();
  const parsedItemAmount = rawItemAmount ? Number(rawItemAmount) : null;
  const rawPerUserLimit = String(elements.productPerUserLimit?.value || "").trim();
  const parsedPerUserLimit = rawPerUserLimit ? Number(rawPerUserLimit) : null;
  const dynamicEnabled = String(elements.productDynamicEnabled?.value || "false") === "true";
  const dynamicParamsJson = dynamicEnabled ? buildProductDynamicParamsJson() : null;
  return {
    sku: elements.productSku.value.trim(),
    title: elements.productTitle.value.trim(),
    remark: elements.productRemark ? elements.productRemark.value.trim() : "",
    currency: elements.productCurrency.value.trim(),
    price: Number(elements.productPrice.value || 0),
    publishAt: elements.productPublishAt.value ? elements.productPublishAt.value : null,
    unpublishAt: elements.productUnpublishAt.value ? elements.productUnpublishAt.value : null,
    productType: elements.productType.value.trim(),
    commandTemplate: elements.productCommand.value.trim(),
    itemMaterial: resolveMaterialInput(elements.productItemMaterial.value),
    itemAmount: isUnlimited
      ? null
      :
      parsedItemAmount && Number.isFinite(parsedItemAmount) && parsedItemAmount > 0
        ? Math.floor(parsedItemAmount)
        : null,
    perUserLimit:
      parsedPerUserLimit && Number.isFinite(parsedPerUserLimit) && parsedPerUserLimit > 0
        ? Math.floor(parsedPerUserLimit)
        : null,
    effectType: elements.productEffectType.value.trim(),
    effectSeconds: Number(elements.productEffectSeconds.value || 0),
    effectAmplifier: Number(elements.productEffectAmplifier.value || 0),
    dynamicPricingEnabled: dynamicEnabled,
    dynamicAlgorithm: String(elements.productDynamicAlgorithm?.value || "LINEAR_DEMAND_V1").trim().toUpperCase(),
    dynamicParamsJson,
    dynamicBasePrice: parseOptionalPositiveWhole(elements.productDynamicBasePrice?.value),
    dynamicFloorPrice: parseOptionalPositiveWhole(elements.productDynamicFloorPrice?.value),
    dynamicCapPrice: parseOptionalPositiveWhole(elements.productDynamicCapPrice?.value),
    dynamicPriceStep: parseOptionalPositiveWhole(elements.productDynamicPriceStep?.value),
    active: elements.productActive.value === "true",
  };
}

async function saveProduct() {
  ensureAdmin();
  const input = getProductInput();
  const payload = await apiAdmin("/api/admin/products/upsert", {
    method: "POST",
    body: JSON.stringify(input),
  });
  setMetaText(elements.productStatus, `商品已保存：${payload.sku}`, "success");
  notify(`商品已保存：${payload.sku}`, "success");
  await loadProducts();
}

async function resetProductLimit(product) {
  ensureAdmin();
  if (!product || !product.id) {
    throw new Error("商品信息无效，无法重置限购。");
  }
  const confirmed = window.confirm(`确认清空商品 ${product.sku} 的所有玩家限购记录吗？`);
  if (!confirmed) {
    return;
  }
  const payload = await apiAdmin("/api/admin/products/reset-limit", {
    method: "POST",
    body: JSON.stringify({ productId: product.id }),
  });
  notify(`商品 ${payload.sku} 的限购记录已重置，清理 ${payload.resetCount} 条。`, "success");
  setMetaText(elements.productListStatus, `已重置 ${payload.sku} 的限购记录`, "success");
  await loadProducts();
}

async function consumeGroupBuyVoucher() {
  ensureAdmin();
  const code = elements.groupBuyConsumeCode ? elements.groupBuyConsumeCode.value.trim() : "";
  if (!code) {
    throw new Error("请输入要核销的团购兑换码。");
  }
  const payload = await apiAdmin("/api/admin/group-buy/consume", {
    method: "POST",
    body: JSON.stringify({ code }),
  });
  const consumedAt = payload.consumedAt || "刚刚";
  setMetaText(
    elements.groupBuyConsumeStatus,
    `核销成功：${payload.code} | 订单 ${payload.orderNo} | 用户 ${payload.username} | 时间 ${consumedAt}`,
    "success"
  );
  notify(`团购兑换码已核销：${payload.code}`, "success");
  if (elements.groupBuyConsumeCode) {
    elements.groupBuyConsumeCode.value = "";
  }
}

function resolveAdminErrorMessage(error) {
  const code = String(error?.code || "").trim().toLowerCase();
  if (code === "voucher_refunded") {
    return "该团购券已退款失效，无法核销。";
  }
  if (code === "voucher_unavailable") {
    return "该团购券已核销，无法重复核销。";
  }
  return error?.message || "操作失败";
}

function renderProducts() {
  const keyword = String(elements.productSearchKeyword?.value || "").trim().toLowerCase();
  const type = String(elements.productSearchType?.value || "").trim().toUpperCase();
  const active = String(elements.productSearchActive?.value || "").trim();
  const filtered = (state.products || []).filter((product) => {
    if (type && String(product.productType || "").toUpperCase() !== type) {
      return false;
    }
    if (active && String(Boolean(product.active)) !== active) {
      return false;
    }
    if (!keyword) {
      return true;
    }
    const materialName = getLocalizedMaterialName(product.itemMaterial || "");
    const haystack = [
      product.title || "",
      product.sku || "",
      product.itemMaterial || "",
      materialName,
      product.remark || "",
      product.productType || "",
    ]
      .join(" ")
      .toLowerCase();
    return haystack.includes(keyword);
  });

  const rows = filtered.map((product) => {
    const editBtn = document.createElement("button");
    editBtn.className = "btn-tonal";
    setNodeText(editBtn, "加载编辑");
    editBtn.addEventListener("click", () => {
      elements.productSku.value = product.sku;
      elements.productTitle.value = product.title;
      if (elements.productRemark) {
        elements.productRemark.value = product.remark || "";
      }
      elements.productCurrency.value = product.currency;
      elements.productPrice.value = product.price;
      if (elements.productDynamicEnabled) {
        elements.productDynamicEnabled.value = product.dynamicPricingEnabled ? "true" : "false";
      }
      if (elements.productDynamicAlgorithm) {
        const dynamicAlgorithm = String(product.dynamicAlgorithm || "LINEAR_DEMAND_V1").trim().toUpperCase();
        const matched = Array.from(elements.productDynamicAlgorithm.options || []).some(
          (option) => String(option.value || "").trim().toUpperCase() === dynamicAlgorithm
        );
        if (!matched && dynamicAlgorithm) {
          const customOption = document.createElement("option");
          customOption.value = dynamicAlgorithm;
          customOption.textContent = getAlgorithmLabel("dynamic", dynamicAlgorithm);
          elements.productDynamicAlgorithm.appendChild(customOption);
        }
        elements.productDynamicAlgorithm.value = dynamicAlgorithm;
      }
      if (elements.productDynamicBasePrice) {
        elements.productDynamicBasePrice.value = product.dynamicBasePrice ?? "";
      }
      if (elements.productDynamicFloorPrice) {
        elements.productDynamicFloorPrice.value = product.dynamicFloorPrice ?? "";
      }
      if (elements.productDynamicCapPrice) {
        elements.productDynamicCapPrice.value = product.dynamicCapPrice ?? "";
      }
      if (elements.productDynamicPriceStep) {
        elements.productDynamicPriceStep.value = product.dynamicPriceStep ?? "";
      }
      if (elements.productDynamicParamsJson) {
        elements.productDynamicParamsJson.value = product.dynamicParamsJson || "";
      }
      elements.productPublishAt.value = toLocalInput(product.publishAt);
      elements.productUnpublishAt.value = toLocalInput(product.unpublishAt);
      elements.productType.value = product.productType;
      elements.productCommand.value = product.commandTemplate || "";
      elements.productItemMaterial.value = product.itemMaterial || "";
      if (elements.productStockMode) {
        elements.productStockMode.value = product.itemAmount == null ? "UNLIMITED" : "FINITE";
      }
      elements.productItemAmount.value = product.itemAmount || 64;
      if (elements.productPerUserLimit) {
        elements.productPerUserLimit.value = product.perUserLimit || "";
      }
      elements.productEffectType.value = product.effectType || "";
      elements.productEffectSeconds.value = product.effectSeconds || 30;
      elements.productEffectAmplifier.value = product.effectAmplifier || 0;
      elements.productActive.value = product.active ? "true" : "false";
      renderProductDynamicParamEditors();
      updateProductTypeFieldsVisibility(product.productType);
      syncProductAmountSlider("input");
      setProductPanel("editor");
      setMetaText(elements.productStatus, `已加载 ${product.sku} 进入编辑`, "info");
    });
    const toggleBtn = document.createElement("button");
    setNodeText(toggleBtn, product.active ? "停用" : "启用");
    toggleBtn.addEventListener("click", async () => {
      await apiAdmin("/api/admin/products/active", {
        method: "POST",
        body: JSON.stringify({ productId: product.id, active: !product.active }),
      });
      notify(`商品 ${product.sku} 已${product.active ? "停用" : "启用"}`, "success");
      await loadProducts();
    });
    const actions = [editBtn, toggleBtn];
    if (product.perUserLimit != null) {
      const resetLimitBtn = document.createElement("button");
      resetLimitBtn.className = "btn-tonal";
      setNodeText(resetLimitBtn, "重置限购");
      resetLimitBtn.addEventListener("click", async () => {
        try {
          await resetProductLimit(product);
        } catch (error) {
          notify(`重置限购失败：${resolveAdminErrorMessage(error)}`, "error");
        }
      });
      actions.push(resetLimitBtn);
    }
    return renderKeyValueCard(
      `${product.title} (${product.sku})`,
      [
        { label: "ID", value: product.id },
        { label: "类型", value: product.productType },
        { label: "材质", value: product.itemMaterial ? `${product.itemMaterial} (${getLocalizedMaterialName(product.itemMaterial)})` : "-" },
        { label: "总库存", value: product.itemAmount ? `x${product.itemAmount}` : "长期供应" },
        { label: "剩余库存", value: product.stockRemaining != null ? `x${product.stockRemaining}` : "长期供应" },
        { label: "单玩家限购", value: product.perUserLimit != null ? `x${product.perUserLimit}` : "不限购" },
        { label: "币种/价格", value: `${currencyName(product.currency)} / ${formatCurrency(product.price, product.currency)}` },
        { label: "动态价格", value: product.dynamicPricingEnabled ? "启用" : "关闭" },
        { label: "动态算法", value: product.dynamicPricingEnabled ? getAlgorithmLabel("dynamic", product.dynamicAlgorithm || "-") : "-" },
        { label: "热度分数", value: product.dynamicPricingEnabled ? Number(product.dynamicDemandScore || 0) : "-" },
        { label: "备注", value: product.remark || "-" },
        { label: "上架时间", value: product.publishAt ? formatDateTime(product.publishAt) : "立即" },
        { label: "下架时间", value: product.unpublishAt ? formatDateTime(product.unpublishAt) : "不自动下架" },
        { label: "启用", value: product.active ? "是" : "否" },
      ],
      actions
    );
  });
  renderList(elements.productList, rows);
  setMetaText(elements.productListStatus, `列表结果：${filtered.length} 个商品`, "info");
}

async function loadProducts() {
  ensureAdmin();
  await Promise.all([ensureMaterialMap(), ensureMarketAlgorithmGlossary()]);
  populateProductDynamicAlgorithmSelect();
  const payload = await apiAdmin("/api/admin/products/list?includeInactive=true&limit=300", {
    method: "GET",
  });
  state.products = payload.products || [];
  renderProducts();
  syncProductAmountSlider("input");
}

async function loadAdminOrders() {
  ensureAdmin();
  await ensureMaterialMap();
  const params = new URLSearchParams();
  const status = elements.orderStatus.value.trim();
  const userId = elements.orderUserId.value.trim();
  const orderNo = elements.orderNo.value.trim();
  const username = elements.orderUsername.value.trim();
  const currency = elements.orderCurrency.value.trim();
  const productType = elements.orderProductType.value.trim();
  const keyword = elements.orderKeyword.value.trim();
  if (status) {
    params.set("status", status);
  }
  if (userId) {
    params.set("userId", userId);
  }
  if (orderNo) {
    params.set("orderNo", orderNo);
  }
  if (username) {
    params.set("username", username);
  }
  if (currency) {
    params.set("currency", currency);
  }
  if (productType) {
    params.set("productType", productType);
  }
  if (keyword) {
    params.set("keyword", keyword);
  }
  params.set("limit", "200");
  const payload = await apiAdmin(`/api/admin/orders/list?${params.toString()}`, { method: "GET" });
  const orders = payload.orders || [];
  notifyAdminOrderTransitions(state.realtime.orderDigest, orders);
  state.realtime.orderDigest = buildAdminOrderDigest(orders);
  setMetaText(elements.orderStatusView, `已加载 ${orders.length} 条订单`, "info");
  const rows = orders.map((order) =>
    renderKeyValueCard(
      `订单 ${order.orderNo}`,
      [
        { label: "用户", value: `${order.username || "-"} (#${order.userId})` },
        { label: "UUID", value: order.boundUuid || order.mcUuid || "-" },
        { label: "状态", value: order.status },
        { label: "金额", value: formatCurrency(order.totalAmount, order.currency) },
        { label: "商品", value: `${order.productTitle || "-"} (${order.sku || "-"})` },
        { label: "类型", value: order.productType || "-" },
        { label: "材质", value: order.itemMaterial ? `${order.itemMaterial} (${getLocalizedMaterialName(order.itemMaterial)})` : "-" },
        { label: "备注", value: order.productRemark || "-" },
        { label: "数量", value: `x${order.quantity}` },
        { label: "团购码", value: order.groupBuyVoucherCode || "-" },
        { label: "团购码状态", value: order.groupBuyVoucherStatus || "-" },
        { label: "时间", value: order.createdAt },
      ]
    )
  );
  renderList(elements.adminOrderList, rows);
}

async function loadEconomySettings() {
  ensureAdmin();
  const payload = await apiAdmin("/api/admin/economy/settings", { method: "GET" });
  const exchange = payload.exchange || {};
  const shopToGame = exchange.shopToGame || {};
  const gameToShop = exchange.gameToShop || {};

  if (elements.exchangeShopToGameEnabled) {
    elements.exchangeShopToGameEnabled.value = String(!!shopToGame.enabled);
  }
  if (elements.exchangeShopToGameRatio) {
    elements.exchangeShopToGameRatio.value = String(shopToGame.ratio ?? 1.0);
  }
  if (elements.exchangeGameToShopEnabled) {
    elements.exchangeGameToShopEnabled.value = String(!!gameToShop.enabled);
  }
  if (elements.exchangeGameToShopRatio) {
    elements.exchangeGameToShopRatio.value = String(gameToShop.ratio ?? 1.0);
  }

  const market = payload.market || {};
  if (elements.marketFeePercent) {
    elements.marketFeePercent.value = String(market.tradeFeePercent ?? 0.0);
  }
  if (elements.marketTaxPercent) {
    elements.marketTaxPercent.value = String(market.tradeTaxPercent ?? 0.0);
  }

  const vault = payload.vault || {};
  const gameCoinName = currencyName("GAME_COIN");
  if (elements.vaultStatusView) {
    const provider = vault.provider || "未提供";
    if (vault.hooked) {
      setMetaText(
        elements.vaultStatusView,
        `已连接 Vault 经济：${provider}（${gameCoinName} 由 Vault 托管）`,
        "success"
      );
    } else if (vault.vaultPluginPresent) {
      setMetaText(
        elements.vaultStatusView,
        "检测到 Vault 插件，但未找到可用经济提供者。",
        "warn"
      );
    } else {
      setMetaText(
        elements.vaultStatusView,
        `未检测到 Vault 插件，${gameCoinName} 当前使用本地钱包。`,
        "warn"
      );
    }
  }

  const leaderboard = payload.leaderboard || {};
  if (elements.leaderboardEnabled) {
    elements.leaderboardEnabled.value = String(leaderboard.enabled !== false);
  }
  if (elements.leaderboardShowOnlineStatus) {
    elements.leaderboardShowOnlineStatus.value = String(leaderboard.showOnlineStatus !== false);
  }
  if (elements.leaderboardDefaultMetric) {
    elements.leaderboardDefaultMetric.value = String(leaderboard.defaultMetric || "GAME_COIN").toUpperCase();
  }
  if (elements.leaderboardDefaultOrder) {
    elements.leaderboardDefaultOrder.value = String(leaderboard.defaultOrder || "DESC").toUpperCase();
  }

  setMetaText(elements.exchangeStatusView, "已加载兑换配置", "info");
  setMetaText(elements.marketEconomyStatusView, "已加载手续费/税率配置", "info");
  setMetaText(elements.leaderboardStatusView, "已加载排行榜配置", "info");
}

async function saveExchangeSettings() {
  ensureAdmin();
  const shopEnabled = elements.exchangeShopToGameEnabled.value === "true";
  const shopRatio = Number(elements.exchangeShopToGameRatio.value || 0);
  const gameEnabled = elements.exchangeGameToShopEnabled.value === "true";
  const gameRatio = Number(elements.exchangeGameToShopRatio.value || 0);

  await apiAdmin("/api/admin/economy/exchange", {
    method: "POST",
    body: JSON.stringify({
      shopToGameEnabled: shopEnabled,
      shopToGameRatio: shopRatio,
      gameToShopEnabled: gameEnabled,
      gameToShopRatio: gameRatio,
    }),
  });
  setMetaText(elements.exchangeStatusView, "兑换配置已保存", "success");
  notify("兑换配置已保存", "success");
}

async function saveMarketEconomySettings() {
  ensureAdmin();
  const fee = Number(elements.marketFeePercent.value || 0);
  const tax = Number(elements.marketTaxPercent.value || 0);
  await apiAdmin("/api/admin/economy/market", {
    method: "POST",
    body: JSON.stringify({
      tradeFeePercent: fee,
      tradeTaxPercent: tax,
    }),
  });
  setMetaText(elements.marketEconomyStatusView, "手续费/税率已保存", "success");
  notify("手续费/税率已保存", "success");
}

async function saveLeaderboardSettings() {
  ensureAdmin();
  const enabled = elements.leaderboardEnabled?.value === "true";
  const showOnlineStatus = elements.leaderboardShowOnlineStatus?.value === "true";
  const defaultMetric = String(elements.leaderboardDefaultMetric?.value || "GAME_COIN").toUpperCase();
  const defaultOrder = String(elements.leaderboardDefaultOrder?.value || "DESC").toUpperCase();
  await apiAdmin("/api/admin/economy/leaderboard", {
    method: "POST",
    body: JSON.stringify({
      enabled,
      showOnlineStatus,
      defaultMetric,
      defaultOrder,
    }),
  });
  setMetaText(elements.leaderboardStatusView, "排行榜配置已保存", "success");
  notify("排行榜配置已保存", "success");
}

async function loadMarket() {
  ensureAdmin();
  await ensureMaterialMap();
  const params = new URLSearchParams();
  const status = elements.marketStatus.value.trim();
  const seller = elements.marketSeller.value.trim();
  const buyer = elements.marketBuyer.value.trim();
  const material = resolveMaterialInput(elements.marketMaterial.value);
  const currency = elements.marketCurrency.value.trim();
  const keyword = elements.marketKeyword.value.trim();
  if (status) {
    params.set("status", status);
  }
  if (seller) {
    params.set("seller", seller);
  }
  if (buyer) {
    params.set("buyer", buyer);
  }
  if (material) {
    params.set("material", material);
  }
  if (currency) {
    params.set("currency", currency);
  }
  if (keyword) {
    params.set("keyword", keyword);
  }
  params.set("limit", "200");
  const payload = await apiAdmin(`/api/admin/market/listings?${params.toString()}`, { method: "GET" });
  const listings = payload.listings || [];
  notifyAdminMarketTransitions(state.realtime.marketDigest, listings);
  state.realtime.marketDigest = buildAdminMarketDigest(listings);
  setMetaText(elements.marketStatusView, `已加载 ${listings.length} 条`, "info");
  const rows = listings.map((listing) => {
    const actions = [];
    if (listing.status === "ACTIVE") {
      const unlistBtn = document.createElement("button");
      unlistBtn.className = "btn-tonal";
      setNodeText(unlistBtn, "强制下架");
      unlistBtn.addEventListener("click", async () => {
        await apiAdmin("/api/admin/market/unlist", {
          method: "POST",
          body: JSON.stringify({ listingId: listing.id }),
        });
        notify(`已下架上架 ${listing.id}`, "success");
        await loadMarket();
      });
      actions.push(unlistBtn);
    }
    return renderKeyValueCard(
      `上架 #${listing.id}`,
      [
        { label: "卖家", value: `${listing.sellerName} (${listing.sellerUuid})` },
        { label: "买家", value: listing.buyerName ? `${listing.buyerName}` : "-" },
        { label: "物品", value: `${listing.itemMaterial} (${getLocalizedMaterialName(listing.itemMaterial)}) x${listing.quantity}` },
        { label: "备注", value: listing.remark || "-" },
        { label: "价格", value: formatCurrency(listing.price, listing.currency) },
        { label: "状态", value: listing.status },
      ],
      actions
    );
  });
  renderList(elements.adminMarketList, rows);
}

function renderSelectedUser() {
  if (!state.selectedUser) {
    renderList(elements.userInfoBox, []);
    return;
  }
  const payload = state.selectedUser;
  const info = renderKeyValueCard(
    `${payload.username}`,
    [
      { label: "用户ID", value: payload.id },
      { label: "UUID", value: payload.boundUuid || "未绑定" },
      { label: "状态", value: payload.authState },
      { label: currencyName("SHOP_COIN"), value: payload.shopCoin },
      { label: currencyName("GAME_COIN"), value: payload.gameCoin },
    ]
  );
  renderList(elements.userInfoBox, [info]);
}

function applySelectedUser(payload, sourceLabel = "查询") {
  state.selectedUser = payload;
  setMetaText(elements.userLookupStatus, `${sourceLabel}：${payload.username}`, "success");
  renderSelectedUser();
}

async function lookupUser() {
  ensureAdmin();
  const identifier = elements.userIdentifier.value.trim();
  if (!identifier) {
    throw new Error("请输入要查询的用户名或 UUID。");
  }
  const payload = await apiAdmin(`/api/admin/users/lookup?identifier=${encodeURIComponent(identifier)}`, {
    method: "GET",
  });
  applySelectedUser(payload, "已查询");
}

async function loadUserList(options = {}) {
  ensureAdmin();
  const keyword = String(elements.userListKeyword?.value || "").trim();
  const hideNoisyCards = elements.userListHideInactiveToggle?.checked !== false;
  const limit = options.limit || 120;
  const query = new URLSearchParams({ limit: String(limit) });
  if (keyword) {
    query.set("keyword", keyword);
  }
  const payload = await apiAdmin(`/api/admin/users/list?${query.toString()}`, { method: "GET" });
  state.userList = payload.users || [];
  const visibleUsers = hideNoisyCards
    ? state.userList.filter((user) => {
        const authState = String(user.authState || "").toUpperCase();
        const isInactive = authState !== "ACTIVE";
        const isUnbound = !user.boundUuid;
        const isUseless = !user.boundUuid
          && Number(user.shopCoin || 0) === 0
          && Number(user.gameCoin || 0) === 0;
        return !(isInactive || isUnbound || isUseless);
      })
    : state.userList;
  const rows = visibleUsers.map((user) => {
    const loadBtn = document.createElement("button");
    loadBtn.className = "btn-tonal";
    setNodeText(loadBtn, "载入编辑");
    loadBtn.addEventListener("click", () => {
      if (elements.userIdentifier) {
        elements.userIdentifier.value = user.username;
      }
      applySelectedUser(user, "已载入");
    });

    const logoutBtn = document.createElement("button");
    setNodeText(logoutBtn, "强制下线");
    logoutBtn.addEventListener("click", async () => {
      await apiAdmin("/api/admin/users/logout", {
        method: "POST",
        body: JSON.stringify({ userId: user.id }),
      });
      notify(`已强制下线：${user.username}`, "success");
      setMetaText(elements.userActionStatus, `已强制下线：${user.username}`, "success");
      await loadUserList({ limit });
      if (state.selectedUser && Number(state.selectedUser.id) === Number(user.id)) {
        await lookupUserByIdentifier(user.username);
      }
    });

    const unbindBtn = document.createElement("button");
    unbindBtn.className = "btn-tonal";
    setNodeText(unbindBtn, "解绑");
    unbindBtn.disabled = !user.boundUuid;
    unbindBtn.addEventListener("click", async () => {
      await apiAdmin("/api/admin/users/unbind", {
        method: "POST",
        body: JSON.stringify({ userId: user.id }),
      });
      notify(`已解绑：${user.username}`, "success");
      await loadUserList({ limit });
      if (state.selectedUser && Number(state.selectedUser.id) === Number(user.id)) {
        await lookupUserByIdentifier(user.username);
      }
    });

    return renderKeyValueCard(
      `${user.username} (#${user.id})`,
      [
        { label: "UUID", value: user.boundUuid || "未绑定" },
        { label: "状态", value: user.authState },
        { label: currencyName("SHOP_COIN"), value: formatCurrency(user.shopCoin, "SHOP_COIN") },
        { label: currencyName("GAME_COIN"), value: formatCurrency(user.gameCoin, "GAME_COIN") },
        { label: "注册时间", value: user.createdAt },
      ],
      [loadBtn, logoutBtn, unbindBtn]
    );
  });
  renderList(elements.userList, rows);
  const hiddenCount = Math.max(0, state.userList.length - visibleUsers.length);
  const suffix = hideNoisyCards && hiddenCount > 0 ? `，已隐藏 ${hiddenCount} 个` : "";
  setMetaText(elements.userListStatus, `已显示 ${visibleUsers.length} / ${state.userList.length} 个用户${suffix}`, "info");
}

async function lookupUserByIdentifier(identifier) {
  const payload = await apiAdmin(`/api/admin/users/lookup?identifier=${encodeURIComponent(identifier)}`, {
    method: "GET",
  });
  applySelectedUser(payload, "已载入");
  return payload;
}

function requireSelectedUser() {
  if (!state.selectedUser) {
    throw new Error("请先查询账号。");
  }
  return state.selectedUser.id;
}

async function resetPassword() {
  ensureAdmin();
  const userId = requireSelectedUser();
  const newPassword = elements.userNewPassword.value.trim();
  if (!newPassword) {
    throw new Error("请输入新密码。");
  }
  await apiAdmin("/api/admin/users/reset-password", {
    method: "POST",
    body: JSON.stringify({ userId, newPassword }),
  });
  setMetaText(elements.userActionStatus, "密码已重置并强制下线", "success");
  notify("密码重置成功", "success");
}

async function unbindUser() {
  ensureAdmin();
  const userId = requireSelectedUser();
  await apiAdmin("/api/admin/users/unbind", {
    method: "POST",
    body: JSON.stringify({ userId }),
  });
  setMetaText(elements.userActionStatus, "已解绑玩家 UUID", "success");
  notify("解绑成功", "success");
  await lookupUser();
}

async function forceLogoutUser() {
  ensureAdmin();
  const userId = requireSelectedUser();
  await apiAdmin("/api/admin/users/logout", {
    method: "POST",
    body: JSON.stringify({ userId }),
  });
  setMetaText(elements.userActionStatus, "已强制下线", "success");
  notify("已强制下线", "success");
}

async function adjustWallet() {
  ensureAdmin();
  const userId = requireSelectedUser();
  const currency = elements.walletCurrency.value.trim();
  const delta = Number(elements.walletDelta.value || 0);
  const reason = elements.walletReason.value.trim();
  if (!delta) {
    throw new Error("调整数量不能为 0。");
  }
  const payload = await apiAdmin("/api/admin/users/wallet-adjust", {
    method: "POST",
    body: JSON.stringify({ userId, currency, delta, reason }),
  });
  setMetaText(
    elements.userActionStatus,
    `余额已更新：${formatCurrency(payload.shopCoin, "SHOP_COIN")} | ${formatCurrency(payload.gameCoin, "GAME_COIN")}`,
    "success"
  );
  notify("余额调整成功", "success");
  await lookupUser();
}

function selectedAdminPermissions() {
  if (!elements.adminPermissionGroups) {
    return [];
  }
  return Array.from(
    elements.adminPermissionGroups.querySelectorAll('input[type="checkbox"][data-permission-code]:checked')
  ).map((node) => node.dataset.permissionCode);
}

function updateAdminPermissionUi() {
  const isSuper = elements.adminManagerType && elements.adminManagerType.value === "super";
  const checkboxes = elements.adminPermissionGroups
    ? elements.adminPermissionGroups.querySelectorAll('input[type="checkbox"][data-permission-code]')
    : [];
  checkboxes.forEach((node) => {
    node.disabled = isSuper;
  });
  if (elements.adminManagerTemplate) {
    elements.adminManagerTemplate.disabled = isSuper;
  }
  if (elements.adminManagerTemplateHint) {
    setNodeText(elements.adminManagerTemplateHint, isSuper
      ? "超级管理员自动拥有全部权限，不需要单独勾选。"
      : "先选择一个模板，再按需要微调权限。");
  }
}

function renderAdminPermissionGroups() {
  if (!elements.adminPermissionGroups) {
    return;
  }
  elements.adminPermissionGroups.innerHTML = "";
  const groups = state.adminMeta?.groups || [];
  groups.forEach((group) => {
    const card = document.createElement("section");
    card.className = "admin-permission-group";

    const header = document.createElement("div");
    header.className = "admin-permission-head";
    const titleWrap = document.createElement("div");
    const title = document.createElement("h3");
    setNodeText(title, group.label);
    const desc = document.createElement("p");
    setNodeText(desc, "按分类勾选权限，可使用模板后再微调。");
    titleWrap.appendChild(title);
    titleWrap.appendChild(desc);

    const actions = document.createElement("div");
    actions.className = "admin-permission-actions";
    const selectAllBtn = document.createElement("button");
    selectAllBtn.className = "btn-tonal";
    selectAllBtn.type = "button";
    setNodeText(selectAllBtn, "全选");
    selectAllBtn.addEventListener("click", () => {
      card.querySelectorAll('input[type="checkbox"][data-permission-code]').forEach((node) => {
        node.checked = true;
      });
    });
    const clearBtn = document.createElement("button");
    clearBtn.className = "btn-tonal";
    clearBtn.type = "button";
    setNodeText(clearBtn, "清空");
    clearBtn.addEventListener("click", () => {
      card.querySelectorAll('input[type="checkbox"][data-permission-code]').forEach((node) => {
        node.checked = false;
      });
    });
    actions.appendChild(selectAllBtn);
    actions.appendChild(clearBtn);
    header.appendChild(titleWrap);
    header.appendChild(actions);
    card.appendChild(header);

    const list = document.createElement("div");
    list.className = "admin-permission-list";
    (group.permissions || []).forEach((permission) => {
      const item = document.createElement("label");
      item.className = "admin-permission-item";
      const top = document.createElement("div");
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.dataset.permissionCode = permission.code;
      top.appendChild(checkbox);
      const strong = document.createElement("strong");
      setNodeText(strong, permission.label);
      top.appendChild(strong);
      const description = document.createElement("span");
      setNodeText(description, permission.description || permission.code);
      item.appendChild(top);
      item.appendChild(description);
      list.appendChild(item);
    });
    card.appendChild(list);
    elements.adminPermissionGroups.appendChild(card);
  });
  updateAdminPermissionUi();
}

function populateAdminTemplates() {
  if (!elements.adminManagerTemplate) {
    return;
  }
  const current = elements.adminManagerTemplate.value;
  elements.adminManagerTemplate.innerHTML = "";
  const empty = document.createElement("option");
  empty.value = "";
  setNodeText(empty, "自定义");
  elements.adminManagerTemplate.appendChild(empty);
  (state.adminMeta?.templates || []).forEach((template) => {
    const option = document.createElement("option");
    option.value = template.key;
    setNodeText(option, template.label);
    elements.adminManagerTemplate.appendChild(option);
  });
  elements.adminManagerTemplate.value = current || "";
}

function setAdminPermissions(permissionCodes = []) {
  const selected = new Set(permissionCodes || []);
  if (!elements.adminPermissionGroups) {
    return;
  }
  elements.adminPermissionGroups.querySelectorAll('input[type="checkbox"][data-permission-code]').forEach((node) => {
    node.checked = selected.has(node.dataset.permissionCode);
  });
}

function applyAdminTemplate(templateKey) {
  const template = (state.adminMeta?.templates || []).find((item) => item.key === templateKey);
  if (!template) {
    setAdminPermissions([]);
    if (elements.adminManagerType) {
      elements.adminManagerType.value = "custom";
    }
    updateAdminPermissionUi();
    return;
  }
  if (elements.adminManagerType) {
    elements.adminManagerType.value = template.superAdmin ? "super" : "custom";
  }
  setAdminPermissions(template.permissions || []);
  updateAdminPermissionUi();
}

function populateAdminForm(admin = null) {
  state.selectedAdminManager = admin;
  if (elements.adminManagerIdentifier) {
    elements.adminManagerIdentifier.value = admin ? admin.username : "";
  }
  if (elements.adminManagerTemplate) {
    elements.adminManagerTemplate.value = admin?.templateKey || "";
  }
  if (elements.adminManagerType) {
    elements.adminManagerType.value = admin?.isSuperAdmin ? "super" : "custom";
  }
  setAdminPermissions(admin?.permissions || []);
  if (!admin && elements.adminManagerTemplate) {
    elements.adminManagerTemplate.value = "";
  }
  updateAdminPermissionUi();
  setMetaText(
    elements.adminManagerStatus,
    admin ? `已载入管理员：${admin.username}` : "等待操作",
    admin ? "info" : "info"
  );
}

function renderAdminManagerList() {
  const rows = (state.adminManagers || []).map((admin) => {
    const editBtn = document.createElement("button");
    editBtn.className = "btn-tonal";
    setNodeText(editBtn, "载入编辑");
    editBtn.addEventListener("click", () => populateAdminForm(admin));

    const toggleBtn = document.createElement("button");
    setNodeText(toggleBtn, admin.active ? "禁用" : "启用");
    toggleBtn.addEventListener("click", async () => {
      await apiAdmin("/api/admin/admin-users/active", {
        method: "POST",
        body: JSON.stringify({ userId: admin.userId, active: !admin.active }),
      });
      notify(`管理员${!admin.active ? "已启用" : "已禁用"}：${admin.username}`, "success");
      await loadAdminManagerList();
    });

    const permissionsText = (admin.permissions || []).join(", ") || "无权限";
    return renderKeyValueCard(
      `${admin.username} (#${admin.userId})`,
      [
        { label: "身份", value: admin.isSuperAdmin ? "SUPER_ADMIN" : (admin.role || "CUSTOM") },
        { label: "状态", value: admin.active ? "启用" : "停用" },
        { label: "模板", value: admin.templateKey || "-" },
        { label: "UUID", value: admin.boundUuid || "未绑定" },
        { label: "权限", value: permissionsText },
        { label: "更新时间", value: admin.updatedAt || "-" },
      ],
      [editBtn, toggleBtn]
    );
  });
  renderList(elements.adminManagerList, rows);
}

async function loadAdminManagerMeta() {
  ensureAdmin();
  if (!state.admin?.canManageAdmins) {
    return;
  }
  state.adminMeta = await apiAdmin("/api/admin/admin-users/meta", { method: "GET" });
  populateAdminTemplates();
  renderAdminPermissionGroups();
}

async function loadAdminManagerList() {
  ensureAdmin();
  if (!state.admin?.canManageAdmins) {
    setMetaText(elements.adminManagerListStatus, "当前账号没有管理员管理权限", "warn");
    renderList(elements.adminManagerList, []);
    return;
  }
  const payload = await apiAdmin("/api/admin/admin-users/list", { method: "GET" });
  state.adminManagers = payload.admins || [];
  renderAdminManagerList();
  setMetaText(elements.adminManagerListStatus, `已加载 ${state.adminManagers.length} 个管理员`, "info");
}

async function loadAdminManagerData() {
  ensureAdmin();
  if (!state.admin?.canManageAdmins) {
    setMetaText(elements.adminManagerStatus, "当前账号不是超级管理员，无法管理管理员。", "warn");
    setMetaText(elements.adminManagerListStatus, "当前账号没有管理员管理权限", "warn");
    renderList(elements.adminManagerList, []);
    return;
  }
  if (!state.adminMeta) {
    await loadAdminManagerMeta();
  }
  await loadAdminManagerList();
}

async function saveAdminManager() {
  ensureAdmin();
  if (!state.admin?.canManageAdmins) {
    throw new Error("当前账号不是超级管理员。");
  }
  const identifier = String(elements.adminManagerIdentifier?.value || "").trim();
  if (!identifier) {
    throw new Error("请输入要授权的用户标识。");
  }
  const isSuperAdmin = elements.adminManagerType?.value === "super";
  const permissions = isSuperAdmin ? [] : selectedAdminPermissions();
  if (!isSuperAdmin && permissions.length === 0) {
    throw new Error("请至少勾选一个权限。");
  }
  const payload = await apiAdmin("/api/admin/admin-users/upsert", {
    method: "POST",
    body: JSON.stringify({
      identifier,
      isSuperAdmin,
      templateKey: elements.adminManagerTemplate?.value || null,
      permissions,
    }),
  });
  notify(`管理员已保存：${payload.username}`, "success");
  setMetaText(elements.adminManagerStatus, `已保存管理员：${payload.username}`, "success");
  await loadAdminManagerList();
  populateAdminForm(payload);
}

async function loadAuditLogs() {
  ensureAdmin();
  const payload = await apiAdmin("/api/admin/audit/list?limit=200", { method: "GET" });
  const rows = payload.logs.map((log) =>
    renderKeyValueCard(
      `${log.action} (#${log.id})`,
      [
        { label: "管理员", value: `${log.adminUsername} (${log.adminRole})` },
        { label: "目标", value: `${log.targetType || "-"} ${log.targetId || ""}`.trim() },
        { label: "时间", value: log.createdAt },
        { label: "IP", value: log.sourceIp || "-" },
      ]
    )
  );
  renderList(elements.auditList, rows);
}

elements.adminLoginBtn.addEventListener("click", async () => {
  try {
    await loginAdmin();
    notify("登录成功", "success");
  } catch (error) {
    setMetaText(elements.adminLoginStatus, `登录失败：${error.message}`, "error");
    notify(`登录失败：${error.message}`, "error");
  }
});

elements.adminLogoutBtn.addEventListener("click", async () => {
  try {
    ensureAdmin();
    await apiAdmin("/api/admin/auth/logout", { method: "POST", body: JSON.stringify({}) });
    setLoggedOut();
    notify("已退出登录", "success");
  } catch (error) {
    notify(`退出失败：${error.message}`, "error");
  }
});

elements.redeemCreateBtn.addEventListener("click", async () => {
  try {
    await createRedeemCode();
  } catch (error) {
    setMetaText(elements.redeemCreateResult, `生成失败：${error.message}`, "error");
    notify(`生成失败：${error.message}`, "error");
  }
});

if (elements.redeemCopyBtn) {
  elements.redeemCopyBtn.addEventListener("click", async () => {
    try {
      await copyTextToClipboard(state.latestRedeemCode || "");
      notify("兑换码已复制到剪贴板。", "success");
    } catch (error) {
      notify(error.message || "复制失败，请手动复制。", "error");
    }
  });
}

elements.redeemRefreshBtn.addEventListener("click", async () => {
  try {
    await loadRedeemList();
    notify("兑换码列表已刷新", "success");
  } catch (error) {
    notify(`加载失败：${error.message}`, "error");
  }
});

elements.productSaveBtn.addEventListener("click", async () => {
  try {
    await saveProduct();
  } catch (error) {
    setMetaText(elements.productStatus, `保存失败：${error.message}`, "error");
    notify(`保存失败：${error.message}`, "error");
  }
});

if (elements.productEditorTabBtn) {
  elements.productEditorTabBtn.addEventListener("click", () => setProductPanel("editor"));
}
if (elements.productListTabBtn) {
  elements.productListTabBtn.addEventListener("click", () => setProductPanel("list"));
}
if (elements.productVoucherTabBtn) {
  elements.productVoucherTabBtn.addEventListener("click", () => setProductPanel("voucher"));
}
if (elements.productItemAmount) {
  elements.productItemAmount.addEventListener("input", () => syncProductAmountSlider("input"));
}
if (elements.productStockMode) {
  elements.productStockMode.addEventListener("change", () => syncProductAmountSlider("input"));
}
if (elements.productItemAmountSlider) {
  elements.productItemAmountSlider.addEventListener("input", () => syncProductAmountSlider("slider"));
}
if (elements.productPrice) {
  elements.productPrice.addEventListener("input", () => syncProductAmountSlider("input"));
}
if (elements.productCurrency) {
  elements.productCurrency.addEventListener("change", () => syncProductAmountSlider("input"));
}

if (elements.groupBuyConsumeBtn) {
  elements.groupBuyConsumeBtn.addEventListener("click", async () => {
    try {
      await consumeGroupBuyVoucher();
    } catch (error) {
      const message = resolveAdminErrorMessage(error);
      setMetaText(elements.groupBuyConsumeStatus, `核销失败：${message}`, "error");
      notify(`核销失败：${message}`, "error");
    }
  });
}

elements.productRefreshBtn.addEventListener("click", async () => {
  try {
    await loadProducts();
    notify("商品列表已刷新", "success");
  } catch (error) {
    notify(`加载失败：${error.message}`, "error");
  }
});

if (elements.orderRefreshBtn) {
  elements.orderRefreshBtn.addEventListener("click", async () => {
    try {
      await loadAdminOrders();
      notify("订单列表已刷新", "success");
    } catch (error) {
      setMetaText(elements.orderStatusView, `加载失败：${error.message}`, "error");
      notify(`加载失败：${error.message}`, "error");
    }
  });
}

if (elements.exchangeSaveBtn) {
  elements.exchangeSaveBtn.addEventListener("click", async () => {
    try {
      await saveExchangeSettings();
    } catch (error) {
      setMetaText(elements.exchangeStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.marketEconomySaveBtn) {
  elements.marketEconomySaveBtn.addEventListener("click", async () => {
    try {
      await saveMarketEconomySettings();
    } catch (error) {
      setMetaText(elements.marketEconomyStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

elements.marketRefreshBtn.addEventListener("click", async () => {
  try {
    await loadMarket();
    notify("市场列表已刷新", "success");
  } catch (error) {
    notify(`加载失败：${error.message}`, "error");
  }
});

const productFilterInputs = [
  elements.productSearchKeyword,
  elements.productSearchType,
  elements.productSearchActive,
].filter(Boolean);
productFilterInputs.forEach((node) => {
  node.addEventListener("input", () => renderProducts());
  node.addEventListener("change", () => renderProducts());
});

const orderFilterInputs = [
  elements.orderStatus,
  elements.orderUserId,
  elements.orderNo,
  elements.orderUsername,
  elements.orderCurrency,
  elements.orderProductType,
  elements.orderKeyword,
].filter(Boolean);
orderFilterInputs.forEach((node) => {
  node.addEventListener("change", async () => {
    if (!state.token) {
      return;
    }
    await loadAdminOrders();
  });
  node.addEventListener("keydown", async (event) => {
    if (event.key !== "Enter" || !state.token) {
      return;
    }
    await loadAdminOrders();
  });
});

const marketFilterInputs = [
  elements.marketStatus,
  elements.marketSeller,
  elements.marketBuyer,
  elements.marketMaterial,
  elements.marketCurrency,
  elements.marketKeyword,
].filter(Boolean);
marketFilterInputs.forEach((node) => {
  node.addEventListener("change", async () => {
    if (!state.token) {
      return;
    }
    await loadMarket();
  });
  node.addEventListener("keydown", async (event) => {
    if (event.key !== "Enter" || !state.token) {
      return;
    }
    await loadMarket();
  });
});

elements.userSearchBtn.addEventListener("click", async () => {
  try {
    await lookupUser();
  } catch (error) {
    setMetaText(elements.userLookupStatus, `查询失败：${error.message}`, "error");
    notify(`查询失败：${error.message}`, "error");
  }
});

if (elements.userListRefreshBtn) {
  elements.userListRefreshBtn.addEventListener("click", async () => {
    try {
      await loadUserList();
      notify("用户列表已刷新", "success");
    } catch (error) {
      setMetaText(elements.userListStatus, `加载失败：${error.message}`, "error");
      notify(`加载失败：${error.message}`, "error");
    }
  });
}
if (elements.userListKeyword) {
  elements.userListKeyword.addEventListener("keydown", async (event) => {
    if (event.key !== "Enter" || !state.token) {
      return;
    }
    await loadUserList();
  });
}
if (elements.userListHideInactiveToggle) {
  elements.userListHideInactiveToggle.addEventListener("change", async () => {
    if (!state.token) {
      return;
    }
    await loadUserList();
  });
}

elements.userResetPasswordBtn.addEventListener("click", async () => {
  try {
    await resetPassword();
    await loadUserList();
  } catch (error) {
    setMetaText(elements.userActionStatus, `重置失败：${error.message}`, "error");
    notify(`重置失败：${error.message}`, "error");
  }
});

elements.userUnbindBtn.addEventListener("click", async () => {
  try {
    await unbindUser();
    await loadUserList();
  } catch (error) {
    setMetaText(elements.userActionStatus, `解绑失败：${error.message}`, "error");
    notify(`解绑失败：${error.message}`, "error");
  }
});

elements.userForceLogoutBtn.addEventListener("click", async () => {
  try {
    await forceLogoutUser();
    await loadUserList();
  } catch (error) {
    setMetaText(elements.userActionStatus, `强制下线失败：${error.message}`, "error");
    notify(`强制下线失败：${error.message}`, "error");
  }
});

if (elements.adminManagerTemplate) {
  elements.adminManagerTemplate.addEventListener("change", () => {
    applyAdminTemplate(elements.adminManagerTemplate.value);
  });
}

if (elements.adminManagerType) {
  elements.adminManagerType.addEventListener("change", () => {
    if (elements.adminManagerType.value === "super") {
      setAdminPermissions(
        (state.adminMeta?.templates || []).find((item) => item.superAdmin)?.permissions || []
      );
    }
    updateAdminPermissionUi();
  });
}

if (elements.adminManagerSaveBtn) {
  elements.adminManagerSaveBtn.addEventListener("click", async () => {
    try {
      await saveAdminManager();
    } catch (error) {
      setMetaText(elements.adminManagerStatus, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.adminManagerClearBtn) {
  elements.adminManagerClearBtn.addEventListener("click", () => {
    populateAdminForm(null);
    notify("管理员表单已清空", "success");
  });
}

if (elements.adminManagerRefreshBtn) {
  elements.adminManagerRefreshBtn.addEventListener("click", async () => {
    try {
      await loadAdminManagerData();
      notify("管理员列表已刷新", "success");
    } catch (error) {
      setMetaText(elements.adminManagerListStatus, `加载失败：${error.message}`, "error");
      notify(`加载失败：${error.message}`, "error");
    }
  });
}

elements.walletAdjustBtn.addEventListener("click", async () => {
  try {
    await adjustWallet();
    await loadUserList();
  } catch (error) {
    setMetaText(elements.userActionStatus, `调整失败：${error.message}`, "error");
    notify(`调整失败：${error.message}`, "error");
  }
});

elements.auditRefreshBtn.addEventListener("click", async () => {
  try {
    await loadAuditLogs();
    notify("审计日志已刷新", "success");
  } catch (error) {
    notify(`加载失败：${error.message}`, "error");
  }
});

if (elements.adminThemeToggleBtn) {
  elements.adminThemeToggleBtn.addEventListener("click", toggleTheme);
}

const savedToken = sessionStorage.getItem("webshop_admin_token");
if (savedToken) {
  state.token = savedToken;
  loadAdminProfile();
}

applyTheme(getInitialTheme());
localizeOrderStatusOptions();
if (elements.productType) {
  elements.productType.addEventListener("change", () => {
    updateProductTypeFieldsVisibility(elements.productType.value);
  });
}
if (elements.productDynamicEnabled) {
  elements.productDynamicEnabled.addEventListener("change", () => {
    updateProductTypeFieldsVisibility(elements.productType ? elements.productType.value : "COMMAND");
  });
}
if (elements.productDynamicAlgorithm) {
  elements.productDynamicAlgorithm.addEventListener("change", () => {
    renderProductDynamicParamEditors();
  });
}
if (elements.productDynamicParamsJson) {
  elements.productDynamicParamsJson.addEventListener("blur", () => {
    const raw = String(elements.productDynamicParamsJson.value || "").trim();
    if (raw) {
      try {
        parseAlgorithmParamsJsonStrict(raw);
      } catch (error) {
        notify(error.message || "算法参数 JSON 格式无效。", "warn");
        return;
      }
    }
    renderProductDynamicParamEditors();
  });
}
if (elements.productDynamicHelpBtn) {
  elements.productDynamicHelpBtn.addEventListener("click", () => {
    openAlgorithmHelpPage("dynamic", elements.productDynamicAlgorithm?.value || "");
  });
}
if (elements.productDynamicParamBasicTabBtn) {
  elements.productDynamicParamBasicTabBtn.addEventListener("click", () => {
    switchProductDynamicParamTab("basic");
  });
}
if (elements.productDynamicParamAdvancedTabBtn) {
  elements.productDynamicParamAdvancedTabBtn.addEventListener("click", () => {
    switchProductDynamicParamTab("advanced");
  });
}
if (elements.productItemMaterial) {
  elements.productItemMaterial.addEventListener("blur", () => {
    const resolved = resolveMaterialInput(elements.productItemMaterial.value);
    if (resolved) {
      elements.productItemMaterial.value = resolved;
    } else if (String(elements.productItemMaterial.value || "").trim()) {
      notify("材质无效，请从建议列表选择或输入正确英文材质名。", "warn");
    }
  });
}
if (elements.marketMaterial) {
  elements.marketMaterial.addEventListener("blur", () => {
    const resolved = resolveMaterialInput(elements.marketMaterial.value);
    if (resolved) {
      elements.marketMaterial.value = resolved;
    } else if (String(elements.marketMaterial.value || "").trim()) {
      notify("材质筛选无效，请输入可识别的物品材质。", "warn");
    }
  });
}
updateProductTypeFieldsVisibility(elements.productType ? elements.productType.value : "COMMAND");
setProductPanel("editor");
syncProductAmountSlider("input");
switchProductDynamicParamTab("basic");
renderProductDynamicParamEditors();

applyCurrencyMetaToUi();
loadCurrencyMeta();
ensureMaterialMap();
ensureMarketAlgorithmGlossary()
  .then(() => {
    populateProductDynamicAlgorithmSelect();
    renderProductDynamicParamEditors();
  })
  .catch(() => {
    populateProductDynamicAlgorithmSelect();
    renderProductDynamicParamEditors();
  });
populatePotionEffectSuggest();
setMetaText(elements.adminLoginStatus, "等待登录", "info");
if (elements.productListStatus) {
  setMetaText(elements.productListStatus, "等待加载商品列表", "info");
}
if (elements.groupBuyConsumeStatus) {
  setMetaText(elements.groupBuyConsumeStatus, "等待核销", "info");
}
if (elements.userListStatus) {
  setMetaText(elements.userListStatus, "等待加载列表", "info");
}
if (elements.adminManagerStatus) {
  setMetaText(elements.adminManagerStatus, "等待操作", "info");
}
if (elements.adminManagerListStatus) {
  setMetaText(elements.adminManagerListStatus, "等待加载管理员列表", "info");
}
renderAdminProfile();
populateAdminForm(null);
