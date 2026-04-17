import {
  DEFAULT_CURRENCY_META,
  FALLBACK_MARKET_ALGORITHM_GLOSSARY,
  normalizeApiBaseUrl,
  resolveApiUrl,
  createIdempotencyKey,
  formatAmount,
  formatCurrency,
  formatDateTime,
  normalizeExchangeDirection,
  calculateExchangePreview,
  normalizeMaterialKey,
  getLocalizedMaterialName,
  resolveProductTextureMaterial,
  getTextureCandidates,
  buildFallbackTextureSvg,
  buildSvgDataUrl,
  resolveOfficialProductStock,
  buildOrderDigest,
  buildListingDigest,
  normalizeMarketAlgorithmGlossary,
  parseAlgorithmParamsJson,
  parseOptionalPositiveWhole,
  validateQuantity,
  validatePrice,
  validatePasswordLength,
  normalizeLocale,
  isChineseLocale,
  parseDateTimeValue,
  toDateTimeLocalValue,
} from "./core-logic.js";

const RUNTIME_CONFIG = window.WEBSHOPX_CONFIG || {};
const API_BASE_URL = normalizeApiBaseUrl(RUNTIME_CONFIG.apiBaseUrl || "");

const STORAGE_KEYS = Object.freeze({
  theme: "webshopx_theme",
  locale: "webshopx_locale",
  session: "webshopx_session",
  hideOwn: "webshopx_market_hide_own",
});

const I18N = window.WebShopXI18n || null;
if (I18N) {
  I18N.preparePage("app", { selectId: "localeSelect" });
}

const PATH_TO_TAB = Object.freeze({
  "/account": "auth",
  "/b2c": "shop",
  "/c2c": "market",
  "/auction": "auction",
});

const TAB_TO_PATH = Object.freeze({
  auth: "/account",
  shop: "/b2c",
  orders: "/account",
  market: "/c2c",
  auction: "/auction",
  logs: "/account",
});

const elements = {
  appProgress: document.getElementById("appProgress"),
  localeSelect: document.getElementById("localeSelect"),
  themeToggleBtn: document.getElementById("themeToggleBtn"),
  statusChip: document.getElementById("statusChip"),
  rail: document.getElementById("mainRail"),

  loginIdentifier: document.getElementById("loginIdentifier"),
  loginPassword: document.getElementById("loginPassword"),
  loginBtn: document.getElementById("loginBtn"),
  logoutBtn: document.getElementById("logoutBtn"),
  authView: document.getElementById("authView"),
  authCardLoginSection: document.getElementById("authCardLoginSection"),
  authCardProfileSection: document.getElementById("authCardProfileSection"),
  profileAvatar: document.getElementById("profileAvatar"),
  profileName: document.getElementById("profileName"),
  profileUuid: document.getElementById("profileUuid"),

  walletBtn: document.getElementById("walletBtn"),
  walletView: document.getElementById("walletView"),
  walletLedgerView: document.getElementById("walletLedgerView"),
  walletLedgerList: document.getElementById("walletLedgerList"),
  shopCoinValue: document.getElementById("shopCoinValue"),
  gameCoinValue: document.getElementById("gameCoinValue"),
  shopCoinLabel: document.getElementById("shopCoinLabel"),
  gameCoinLabel: document.getElementById("gameCoinLabel"),

  redeemCode: document.getElementById("redeemCode"),
  redeemBtn: document.getElementById("redeemBtn"),
  redeemView: document.getElementById("redeemView"),

  exchangeFrom: document.getElementById("exchangeFrom"),
  exchangeTo: document.getElementById("exchangeTo"),
  exchangeAmount: document.getElementById("exchangeAmount"),
  exchangeBtn: document.getElementById("exchangeBtn"),
  exchangeRateHint: document.getElementById("exchangeRateHint"),
  exchangeView: document.getElementById("exchangeView"),

  productsBtn: document.getElementById("productsBtn"),
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
  productList: document.getElementById("productList"),

  ordersBtn: document.getElementById("ordersBtn"),
  orderView: document.getElementById("orderView"),
  orderList: document.getElementById("orderList"),

  marketSectionTitle: document.getElementById("marketSectionTitle"),
  marketSectionDesc: document.getElementById("marketSectionDesc"),
  marketModePublicBtn: document.getElementById("marketModePublicBtn"),
  marketModeStoresBtn: document.getElementById("marketModeStoresBtn"),
  marketModeMineBtn: document.getElementById("marketModeMineBtn"),
  hideOwnMarketListings: document.getElementById("hideOwnMarketListings"),
  marketRefreshBtn: document.getElementById("marketRefreshBtn"),
  marketKeyword: document.getElementById("marketKeyword"),
  marketMaterial: document.getElementById("marketMaterial"),
  marketCurrency: document.getElementById("marketCurrency"),
  marketMinPrice: document.getElementById("marketMinPrice"),
  marketMaxPrice: document.getElementById("marketMaxPrice"),
  marketSort: document.getElementById("marketSort"),
  marketSortOrder: document.getElementById("marketSortOrder"),
  marketApplyBtn: document.getElementById("marketApplyBtn"),
  marketClearBtn: document.getElementById("marketClearBtn"),
  marketView: document.getElementById("marketView"),
  marketList: document.getElementById("marketList"),

  auctionRefreshBtn: document.getElementById("auctionRefreshBtn"),
  auctionKeyword: document.getElementById("auctionKeyword"),
  auctionMaterial: document.getElementById("auctionMaterial"),
  auctionCurrency: document.getElementById("auctionCurrency"),
  auctionMinPrice: document.getElementById("auctionMinPrice"),
  auctionMaxPrice: document.getElementById("auctionMaxPrice"),
  auctionSort: document.getElementById("auctionSort"),
  auctionSortOrder: document.getElementById("auctionSortOrder"),
  auctionApplyBtn: document.getElementById("auctionApplyBtn"),
  auctionClearBtn: document.getElementById("auctionClearBtn"),
  auctionView: document.getElementById("auctionView"),
  auctionList: document.getElementById("auctionList"),

  marketEditDialog: document.getElementById("marketEditDialog"),
  marketEditPrice: document.getElementById("marketEditPrice"),
  marketEditTradeMode: document.getElementById("marketEditTradeMode"),
  marketEditRemark: document.getElementById("marketEditRemark"),
  marketEditSupplyBatchSize: document.getElementById("marketEditSupplyBatchSize"),
  marketEditSupplyMaxStock: document.getElementById("marketEditSupplyMaxStock"),
  marketEditDynamicBase: document.getElementById("marketEditDynamicBase"),
  marketEditDynamicStep: document.getElementById("marketEditDynamicStep"),
  marketEditDynamicFloor: document.getElementById("marketEditDynamicFloor"),
  marketEditDynamicCap: document.getElementById("marketEditDynamicCap"),
  marketEditAuctionStart: document.getElementById("marketEditAuctionStart"),
  marketEditAuctionIncrement: document.getElementById("marketEditAuctionIncrement"),
  marketEditAuctionEndAt: document.getElementById("marketEditAuctionEndAt"),
  marketEditCancelBtn: document.getElementById("marketEditCancelBtn"),
  marketEditSaveBtn: document.getElementById("marketEditSaveBtn"),

  actionConfirmDialog: document.getElementById("actionConfirmDialog"),
  actionConfirmTitle: document.getElementById("actionConfirmTitle"),
  actionConfirmBody: document.getElementById("actionConfirmBody"),
  actionConfirmCancel: document.getElementById("actionConfirmCancel"),
  actionConfirmOk: document.getElementById("actionConfirmOk"),

  logBox: document.getElementById("logBox"),
};

const tabPanels = Array.from(document.querySelectorAll("[data-tab-panel]"));
const tabTargets = Array.from(document.querySelectorAll("[data-tab-target]"));

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
  theme: "light",
  currencyMeta: {
    SHOP_COIN: { ...DEFAULT_CURRENCY_META.SHOP_COIN },
    GAME_COIN: { ...DEFAULT_CURRENCY_META.GAME_COIN },
  },
  timeZone: "Asia/Shanghai",

  walletBalance: {
    shopCoin: 0,
    gameCoin: 0,
  },
  exchangeSettings: {
    shopToGame: { enabled: true, ratio: 1 },
    gameToShop: { enabled: false, ratio: 1 },
  },

  products: [],
  orders: [],
  orderPolicy: {
    refundEnabled: false,
    refundUndeliveredEnabled: false,
    sharedClaimAllowed: false,
  },
  listingsByScope: {
    DIRECT: [],
    AUCTION: [],
  },

  materialNameMap: {},
  marketAlgorithmGlossary: {
    dynamic: FALLBACK_MARKET_ALGORITHM_GLOSSARY.dynamic.slice(),
    auction: FALLBACK_MARKET_ALGORITHM_GLOSSARY.auction.slice(),
  },

  materialNameMapReady: false,
  marketAlgorithmGlossaryReady: false,
  materialNameMapPromise: null,
  marketAlgorithmGlossaryPromise: null,

  realtime: {
    timer: null,
    busy: false,
    orderDigest: {},
    listingDigest: {
      DIRECT: {},
      AUCTION: {},
    },
  },

  editingListing: null,
  hideOwnMarketListings: true,
  pendingRequests: 0,
};

const FALLBACK_TEXTURE_DATA_URL = buildSvgDataUrl(buildFallbackTextureSvg());
let actionConfirmResolver = null;

function localize(text) {
  if (!I18N || typeof I18N.localizeText !== "function") {
    return String(text || "");
  }
  return String(I18N.localizeText(String(text || "")) || "");
}

function getLocale() {
  if (I18N && typeof I18N.getLocale === "function") {
    return normalizeLocale(I18N.getLocale());
  }
  return normalizeLocale(controlValue(elements.localeSelect, RUNTIME_CONFIG.defaultLocale || "zh-CN"));
}

function controlValue(control, fallback = "") {
  if (!control) {
    return fallback;
  }
  if (control.value !== undefined && control.value !== null) {
    return String(control.value);
  }
  const attr = control.getAttribute("value");
  return attr === null ? fallback : String(attr);
}

function setControlValue(control, value) {
  if (!control) {
    return;
  }
  const text = value === undefined || value === null ? "" : String(value);
  control.value = text;
  if (text) {
    control.setAttribute("value", text);
  } else {
    control.removeAttribute("value");
  }
}

function controlChecked(control) {
  return Boolean(control && control.checked);
}

function setMetaText(target, text) {
  if (!target) {
    return;
  }
  target.textContent = localize(text);
}

function notify(message, kind = "info") {
  const text = localize(message);
  appendLog(text, kind);
  if (window.mdui && typeof window.mdui.snackbar === "function") {
    window.mdui.snackbar({ message: text, placement: "top" });
  }
}

function setGlobalBusy(busy) {
  if (!elements.appProgress) {
    return;
  }
  elements.appProgress.style.display = busy ? "block" : "none";
}

function beginNetworkRequest() {
  state.pendingRequests += 1;
  setGlobalBusy(true);
}

function endNetworkRequest() {
  state.pendingRequests = Math.max(0, state.pendingRequests - 1);
  setGlobalBusy(state.pendingRequests > 0);
}

function setFieldError(field, message) {
  if (!field) {
    return;
  }
  const hasError = Boolean(message);
  field.error = hasError;
  if (hasError) {
    field.setAttribute("error-message", String(message));
  } else {
    field.removeAttribute("error-message");
  }
}

function validateLoginInputs() {
  const identifier = controlValue(elements.loginIdentifier).trim();
  const password = controlValue(elements.loginPassword);
  const passwordOk = validatePasswordLength(password);

  setFieldError(elements.loginIdentifier, identifier ? "" : "请输入用户名");
  setFieldError(elements.loginPassword, passwordOk ? "" : "密码长度需为 8-64 位");

  if (elements.loginBtn) {
    elements.loginBtn.disabled = !identifier || !passwordOk;
  }

  return Boolean(identifier) && passwordOk;
}

function closeActionConfirm(result) {
  if (elements.actionConfirmDialog) {
    elements.actionConfirmDialog.open = false;
  }
  if (typeof actionConfirmResolver === "function") {
    const resolve = actionConfirmResolver;
    actionConfirmResolver = null;
    resolve(Boolean(result));
  }
}

function askActionConfirm(title, message) {
  if (!elements.actionConfirmDialog) {
    return Promise.resolve(true);
  }
  if (elements.actionConfirmTitle) {
    elements.actionConfirmTitle.textContent = localize(title);
  }
  if (elements.actionConfirmBody) {
    elements.actionConfirmBody.textContent = localize(message);
  }
  elements.actionConfirmDialog.open = true;
  return new Promise((resolve) => {
    actionConfirmResolver = resolve;
  });
}

function appendLog(text, kind = "info") {
  if (!elements.logBox) {
    return;
  }
  const row = document.createElement("div");
  row.style.padding = "8px";
  row.style.borderBottom = "1px solid var(--mdui-color-outline-variant)";
  row.textContent = `[${new Date().toLocaleTimeString()}][${kind.toUpperCase()}] ${text}`;
  elements.logBox.prepend(row);
}

function setStatusChip(text, loggedIn = false) {
  if (!elements.statusChip) {
    return;
  }
  elements.statusChip.textContent = localize(text);
  elements.statusChip.variant = loggedIn ? "assist" : "elevated";
}

function compareDigest(left, right) {
  const leftKeys = Object.keys(left || {});
  const rightKeys = Object.keys(right || {});
  if (leftKeys.length !== rightKeys.length) {
    return false;
  }
  for (const key of leftKeys) {
    if ((left || {})[key] !== (right || {})[key]) {
      return false;
    }
  }
  return true;
}

function setButtonActive(button, active) {
  if (!button) {
    return;
  }
  button.variant = active ? "filled" : "tonal";
}

function applyTheme(theme) {
  const normalized = theme === "dark" ? "dark" : "light";
  state.theme = normalized;
  document.documentElement.classList.remove("mdui-theme-light", "mdui-theme-dark");
  document.documentElement.classList.add(normalized === "dark" ? "mdui-theme-dark" : "mdui-theme-light");
  try {
    window.localStorage.setItem(STORAGE_KEYS.theme, normalized);
  } catch (error) {
    appendLog(`主题保存失败: ${error.message}`, "warn");
  }
  if (elements.themeToggleBtn) {
    elements.themeToggleBtn.textContent = normalized === "dark" ? "切换亮色" : "切换暗色";
  }
}

function getInitialTheme() {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEYS.theme);
    if (saved === "dark" || saved === "light") {
      return saved;
    }
  } catch (error) {
    appendLog(`读取主题失败: ${error.message}`, "warn");
  }
  if (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) {
    return "dark";
  }
  return "light";
}

function buildApiUrl(path) {
  return resolveApiUrl(path, API_BASE_URL);
}

async function requestApi(path, { method = "GET", body, auth = true } = {}) {
  beginNetworkRequest();
  const headers = {};
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (auth && state.token) {
    headers.Authorization = `Bearer ${state.token}`;
  }
  try {
    const response = await fetch(buildApiUrl(path), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    const contentType = response.headers.get("content-type") || "";
    const payload = contentType.includes("application/json")
      ? await response.json().catch(() => null)
      : await response.text().catch(() => "");

    if (!response.ok) {
      const error = new Error(
        payload && typeof payload === "object" && payload.message
          ? payload.message
          : `${method} ${path} failed with ${response.status}`
      );
      error.code = payload && typeof payload === "object" ? String(payload.error || payload.code || "") : "";
      error.status = response.status;
      error.payload = payload;
      throw error;
    }
    return payload;
  } finally {
    endNetworkRequest();
  }
}

function withErrorHint(error, fallback) {
  const code = error && error.code ? String(error.code) : "";
  if (!code) {
    return fallback;
  }
  return `${fallback} (${code})`;
}

function iconSvg(iconKey) {
  switch (iconKey) {
    case "coins":
      return "<svg viewBox='0 0 24 24' fill='currentColor' aria-hidden='true'><path d='M12 4C7.58 4 4 5.79 4 8s3.58 4 8 4 8-1.79 8-4-3.58-4-8-4Zm8 6c0 2.21-3.58 4-8 4s-8-1.79-8-4v3c0 2.21 3.58 4 8 4s8-1.79 8-4Zm0 5c0 2.21-3.58 4-8 4s-8-1.79-8-4v3c0 2.21 3.58 4 8 4s8-1.79 8-4Z'/></svg>";
    case "cart":
      return "<svg viewBox='0 0 24 24' fill='currentColor' aria-hidden='true'><path d='M7 18a2 2 0 1 0 2 2 2 2 0 0 0-2-2Zm10 0a2 2 0 1 0 2 2 2 2 0 0 0-2-2ZM7.17 14h9.92a2 2 0 0 0 1.94-1.5L21 5H6.21l-.43-2H2v2h2.2l2.27 10.59A2 2 0 0 0 8.42 17H19v-2H8.42Z'/></svg>";
    case "market":
      return "<svg viewBox='0 0 24 24' fill='currentColor' aria-hidden='true'><path d='M4 4h16v2H4zm0 4h16v12H4zm4 4v4h2v-4zm4-2v6h2v-6zm4-3v9h2V9z'/></svg>";
    case "hammer":
      return "<svg viewBox='0 0 24 24' fill='currentColor' aria-hidden='true'><path d='m2 21 7-7 1.5 1.5-7 7zm9.71-8.29L8.29 9.29 12.58 5l3.41 3.41zm1.41-8.49L15 2.34 21.66 9l-1.88 1.88z'/></svg>";
    default:
      return "<svg viewBox='0 0 24 24' fill='currentColor' aria-hidden='true'><circle cx='12' cy='12' r='8'/></svg>";
  }
}

function createIconNode(iconKey) {
  const span = document.createElement("span");
  span.className = "wsx-inline-icon";
  span.innerHTML = iconSvg(iconKey);
  return span;
}

function pickActiveTabFromPath() {
  const pathname = String(window.location.pathname || "").trim().toLowerCase();
  const candidate = Object.entries(PATH_TO_TAB).find(([path]) => pathname.endsWith(path.toLowerCase()));
  return candidate ? candidate[1] : "auth";
}

function syncRoute(tab) {
  const path = TAB_TO_PATH[tab];
  if (!path) {
    return;
  }
  try {
    const current = String(window.location.pathname || "");
    if (!current.endsWith(path)) {
      window.history.replaceState(null, "", path);
    }
  } catch (error) {
    appendLog(`同步路由失败: ${error.message}`, "warn");
  }
}

function activateTab(tab, syncHistory = true) {
  const allowed = new Set(["auth", "shop", "orders", "market", "auction", "logs"]);
  const normalized = allowed.has(tab) ? tab : "auth";
  state.activeTab = normalized;
  state.marketTradeScope = normalized === "auction" ? "AUCTION" : "DIRECT";

  tabPanels.forEach((panel) => {
    panel.classList.toggle("is-active", panel.dataset.tabPanel === normalized);
  });

  tabTargets.forEach((button) => {
    const active = button.dataset.tabTarget === normalized;
    if (button.tagName.toLowerCase().startsWith("mdui-button")) {
      setButtonActive(button, active);
    }
    button.classList.toggle("active", active);
  });

  if (elements.rail) {
    setControlValue(elements.rail, normalized);
  }

  if (syncHistory) {
    syncRoute(normalized);
  }

  if (normalized === "shop") {
    void loadProducts();
  } else if (normalized === "orders") {
    void loadOrders();
  } else if (normalized === "market") {
    void loadMarketListings("DIRECT");
  } else if (normalized === "auction") {
    void loadMarketListings("AUCTION");
  }
}

function updateAuthView() {
  const loggedIn = Boolean(state.token);
  setStatusChip(loggedIn ? `已登录: ${state.username || "玩家"}` : "未登录", loggedIn);
  
  if (elements.authCardLoginSection && elements.authCardProfileSection) {
    if (loggedIn) {
      elements.authCardLoginSection.style.display = "none";
      elements.authCardProfileSection.style.display = "";
    } else {
      elements.authCardLoginSection.style.display = "";
      elements.authCardProfileSection.style.display = "none";
    }
  }
  
  // Control wallet cards visibility
  document.querySelectorAll('[data-wallet]').forEach(card => {
    card.style.display = loggedIn ? "" : "none";
  });
  
  if (elements.profileName) {
    elements.profileName.textContent = state.username || "-";
  }
  if (elements.profileUuid) {
    elements.profileUuid.textContent = state.boundUuid || "-";
  }
  if (elements.authView) {
    elements.authView.textContent = loggedIn
      ? `欢迎回来，${state.username || "玩家"}。`
      : "请先登录后访问钱包、商城和市场功能。";
  }

  if (elements.profileAvatar) {
    const username = String(state.username || "").trim();
    if (username) {
      elements.profileAvatar.src = `https://nmsr.nickac.dev/face/${encodeURIComponent(username)}`;
      elements.profileAvatar.alt = `${username} 头像`;
      elements.profileAvatar.onerror = () => {
        elements.profileAvatar.src = FALLBACK_TEXTURE_DATA_URL;
      };
    } else {
      elements.profileAvatar.src = FALLBACK_TEXTURE_DATA_URL;
      elements.profileAvatar.alt = "默认头像";
    }
  }
}

async function ensureMaterialNameMap() {
  if (state.materialNameMapReady) {
    return state.materialNameMap;
  }
  if (state.materialNameMapPromise) {
    return state.materialNameMapPromise;
  }

  state.materialNameMapPromise = (async () => {
    const locale = normalizeLocale(getLocale());
    const candidates = [];
    if (isChineseLocale(locale)) {
      candidates.push("material_zh.json");
    }
    candidates.push(`i18n/materials/${locale}.json`);
    candidates.push("i18n/materials/zh-CN.json");

    let materialNameMap = {};
    for (const path of candidates) {
      try {
        const response = await fetch(path, { cache: "no-cache" });
        if (!response.ok) {
          continue;
        }
        const payload = await response.json();
        if (payload && typeof payload === "object" && !Array.isArray(payload)) {
          materialNameMap = payload;
          break;
        }
      } catch (error) {
        appendLog(`材质字典读取失败: ${path} (${error.message})`, "warn");
      }
    }

    state.materialNameMap = materialNameMap;
    state.materialNameMapReady = true;
    return materialNameMap;
  })();

  try {
    return await state.materialNameMapPromise;
  } finally {
    state.materialNameMapPromise = null;
  }
}

async function ensureMarketAlgorithmGlossary() {
  if (state.marketAlgorithmGlossaryReady) {
    return state.marketAlgorithmGlossary;
  }
  if (state.marketAlgorithmGlossaryPromise) {
    return state.marketAlgorithmGlossaryPromise;
  }

  state.marketAlgorithmGlossaryPromise = (async () => {
    const locale = normalizeLocale(getLocale());
    const candidates = [
      `i18n/market-algorithms/${locale}.json`,
      "i18n/market-algorithms/zh-CN.json",
    ];

    let loaded = null;
    for (const path of candidates) {
      try {
        const response = await fetch(path, { cache: "no-cache" });
        if (!response.ok) {
          continue;
        }
        const payload = await response.json();
        loaded = normalizeMarketAlgorithmGlossary(payload, FALLBACK_MARKET_ALGORITHM_GLOSSARY);
        break;
      } catch (error) {
        appendLog(`算法字典读取失败: ${path} (${error.message})`, "warn");
      }
    }

    state.marketAlgorithmGlossary = loaded || normalizeMarketAlgorithmGlossary(null, FALLBACK_MARKET_ALGORITHM_GLOSSARY);
    state.marketAlgorithmGlossaryReady = true;
    return state.marketAlgorithmGlossary;
  })();

  try {
    return await state.marketAlgorithmGlossaryPromise;
  } finally {
    state.marketAlgorithmGlossaryPromise = null;
  }
}

function setCurrencyMetaFromPayload(payload) {
  const shopCoin = payload && payload.shopCoin ? payload.shopCoin : {};
  const gameCoin = payload && payload.gameCoin ? payload.gameCoin : {};

  state.currencyMeta = {
    SHOP_COIN: {
      label: String(shopCoin.name || shopCoin.label || DEFAULT_CURRENCY_META.SHOP_COIN.label || "Web Coin"),
      short: String(shopCoin.short || DEFAULT_CURRENCY_META.SHOP_COIN.short || "SC"),
    },
    GAME_COIN: {
      label: String(gameCoin.name || gameCoin.label || DEFAULT_CURRENCY_META.GAME_COIN.label || "Game Coin"),
      short: String(gameCoin.short || DEFAULT_CURRENCY_META.GAME_COIN.short || "GC"),
    },
  };

  // Update currency labels in UI
  if (elements.shopCoinLabel) {
    elements.shopCoinLabel.textContent = state.currencyMeta.SHOP_COIN.label;
  }
  if (elements.gameCoinLabel) {
    elements.gameCoinLabel.textContent = state.currencyMeta.GAME_COIN.label;
  }
}

function currencyMetaForCore() {
  return {
    SHOP_COIN: { short: state.currencyMeta.SHOP_COIN.short },
    GAME_COIN: { short: state.currencyMeta.GAME_COIN.short },
  };
}

async function loadCurrencyMeta() {
  try {
    const payload = await requestApi("/api/meta/currency", { method: "GET", auth: false });
    setCurrencyMetaFromPayload(payload || {});
    if (payload && payload.exchange) {
      state.exchangeSettings = {
        shopToGame: normalizeExchangeDirection(payload.exchange.shopToGame, state.exchangeSettings.shopToGame),
        gameToShop: normalizeExchangeDirection(payload.exchange.gameToShop, state.exchangeSettings.gameToShop),
      };
    }
    state.timeZone = String(payload && payload.timeZone ? payload.timeZone : state.timeZone || "Asia/Shanghai");
    updateExchangePreview();
  } catch (error) {
    appendLog(withErrorHint(error, "读取货币元数据失败"), "warn");
  }
}

function setSessionFromPayload(payload) {
  state.token = String(payload && (payload.sessionToken || payload.token || "")).trim() || null;
  const user = payload && payload.user ? payload.user : payload;
  state.username = String(user && (user.username || user.name || "")).trim() || null;
  state.boundUuid = String(user && (user.boundUuid || user.uuid || "")).trim() || null;

  try {
    if (state.token) {
      window.localStorage.setItem(STORAGE_KEYS.session, state.token);
    }
  } catch (error) {
    appendLog(`保存会话失败: ${error.message}`, "warn");
  }
  updateAuthView();
}

function clearSession() {
  state.token = null;
  state.username = null;
  state.boundUuid = null;
  state.orders = [];
  state.realtime.orderDigest = {};
  state.realtime.listingDigest.DIRECT = {};
  state.realtime.listingDigest.AUCTION = {};
  try {
    window.localStorage.removeItem(STORAGE_KEYS.session);
  } catch (error) {
    appendLog(`清理会话失败: ${error.message}`, "warn");
  }
  updateAuthView();
  renderOrders();
}

async function login() {
  const valid = validateLoginInputs();
  const identifier = controlValue(elements.loginIdentifier).trim();
  const password = controlValue(elements.loginPassword).trim();

  if (!valid) {
    notify("请输入合法的用户名和密码", "warn");
    return;
  }

  try {
    const payload = await requestApi("/api/auth/login", {
      method: "POST",
      auth: false,
      body: { identifier, password },
    });
    setSessionFromPayload(payload || {});
    notify("登录成功", "success");
    await Promise.all([
      loadCurrencyMeta(),
      loadWallet(),
      loadWalletLedger(),
      loadProducts(),
      loadOrders(),
      loadOrdersPolicy(),
    ]);
    if (state.activeTab === "market" || state.activeTab === "auction") {
      await loadMarketListings(state.activeTab === "auction" ? "AUCTION" : "DIRECT");
    }
  } catch (error) {
    notify(withErrorHint(error, "登录失败"), "error");
  }
}

async function logout() {
  try {
    if (state.token) {
      await requestApi("/api/auth/logout", {
        method: "POST",
        body: {},
      });
    }
  } catch (error) {
    appendLog(withErrorHint(error, "退出登录接口调用失败"), "warn");
  }
  clearSession();
  notify("已退出登录", "info");
}

function requireAuth() {
  if (!state.token) {
    notify("请先登录", "warn");
    activateTab("auth");
    return false;
  }
  return true;
}

function updateWalletView(walletPayload) {
  const shopCoin = Number(walletPayload && walletPayload.shopCoin);
  const gameCoin = Number(walletPayload && walletPayload.gameCoin);

  state.walletBalance.shopCoin = Number.isFinite(shopCoin) ? shopCoin : 0;
  state.walletBalance.gameCoin = Number.isFinite(gameCoin) ? gameCoin : 0;

  if (walletPayload && walletPayload.exchange) {
    state.exchangeSettings = {
      shopToGame: normalizeExchangeDirection(walletPayload.exchange.shopToGame, state.exchangeSettings.shopToGame),
      gameToShop: normalizeExchangeDirection(walletPayload.exchange.gameToShop, state.exchangeSettings.gameToShop),
    };
  }

  if (elements.shopCoinValue) {
    elements.shopCoinValue.textContent = formatAmount(state.walletBalance.shopCoin, getLocale());
  }
  if (elements.gameCoinValue) {
    elements.gameCoinValue.textContent = formatAmount(state.walletBalance.gameCoin, getLocale());
  }

  setMetaText(
    elements.walletView,
    `钱包已更新: ${state.currencyMeta.SHOP_COIN.short} ${formatAmount(state.walletBalance.shopCoin, getLocale())} / ${state.currencyMeta.GAME_COIN.short} ${formatAmount(state.walletBalance.gameCoin, getLocale())}`
  );
  updateExchangePreview();
}

async function loadWallet() {
  if (!requireAuth()) {
    return;
  }
  try {
    const payload = await requestApi("/api/wallet", { method: "GET" });
    updateWalletView(payload || {});
  } catch (error) {
    setMetaText(elements.walletView, withErrorHint(error, "刷新余额失败"));
  }
}

async function loadWalletLedger() {
  if (!requireAuth()) {
    return;
  }
  try {
    const payload = await requestApi("/api/wallet/ledger?limit=20", { method: "GET" });
    const entries = Array.isArray(payload && payload.entries) ? payload.entries : [];
    if (entries.length === 0) {
      setMetaText(elements.walletLedgerView, "暂无最近变动记录。");
      if (elements.walletLedgerList) {
        elements.walletLedgerList.innerHTML = "";
      }
      return;
    }

    setMetaText(elements.walletLedgerView, `最近 ${entries.length} 条记录`);
    if (!elements.walletLedgerList) {
      return;
    }
    elements.walletLedgerList.innerHTML = "";
    entries.forEach((entry) => {
      const item = document.createElement("mdui-list-item");
      const delta = Number(entry && entry.delta);
      const signed = Number.isFinite(delta) && delta > 0 ? `+${delta}` : `${delta || 0}`;
      const currency = String(entry && entry.currency ? entry.currency : "SHOP_COIN").toUpperCase();
      const when = formatDateTime(entry && entry.createdAt, {
        locale: getLocale(),
        timeZone: state.timeZone,
      });
      item.headline = `${entry && entry.bizType ? entry.bizType : "WALLET"} ${signed} ${currency}`;
      item.description = `${entry && entry.bizId ? entry.bizId : "-"} | ${when}`;
      elements.walletLedgerList.appendChild(item);
    });
  } catch (error) {
    setMetaText(elements.walletLedgerView, withErrorHint(error, "读取钱包流水失败"));
  }
}

function updateExchangePreview() {
  const fromCurrency = controlValue(elements.exchangeFrom, "SHOP_COIN");
  const toCurrency = controlValue(elements.exchangeTo, "GAME_COIN");
  const amount = controlValue(elements.exchangeAmount, "1");

  const preview = calculateExchangePreview({
    fromCurrency,
    toCurrency,
    amount,
    exchangeSettings: state.exchangeSettings,
    walletBalance: state.walletBalance,
  });

  if (!preview.ok) {
    const reasonMap = {
      invalid_amount: "兑换数量必须大于 0",
      same_currency: "兑换方向不能相同",
      invalid_direction: "无效兑换方向",
      direction_disabled: "该兑换方向未开启",
      invalid_ratio: "兑换比例导致结果为 0",
    };
    setMetaText(elements.exchangeRateHint, reasonMap[preview.reason] || "无法预估兑换结果");
    return;
  }

  const fromLabel = state.currencyMeta[fromCurrency]?.short || fromCurrency;
  const toLabel = state.currencyMeta[toCurrency]?.short || toCurrency;
  const content = `比例 ${preview.ratio}，预估到账 ${toLabel} ${formatAmount(preview.convertedAmount, getLocale())}，兑换后余额 ${fromLabel} ${formatAmount(preview.fromRemaining, getLocale())}`;
  setMetaText(elements.exchangeRateHint, content);
}

async function executeExchange() {
  if (!requireAuth()) {
    return;
  }
  const fromCurrency = controlValue(elements.exchangeFrom, "SHOP_COIN");
  const toCurrency = controlValue(elements.exchangeTo, "GAME_COIN");
  const amount = Number(controlValue(elements.exchangeAmount, "0"));

  const preview = calculateExchangePreview({
    fromCurrency,
    toCurrency,
    amount,
    exchangeSettings: state.exchangeSettings,
    walletBalance: state.walletBalance,
  });

  if (!preview.ok) {
    setMetaText(elements.exchangeView, "参数非法，无法兑换");
    return;
  }

  const confirmed = await askActionConfirm(
    "确认兑换",
    `确认将 ${formatAmount(amount, getLocale())} ${state.currencyMeta[fromCurrency]?.short || fromCurrency} 兑换为 ${formatAmount(preview.convertedAmount, getLocale())} ${state.currencyMeta[toCurrency]?.short || toCurrency} 吗？`
  );
  if (!confirmed) {
    notify("已取消兑换", "info");
    return;
  }

  try {
    await requestApi("/api/wallet/exchange", {
      method: "POST",
      body: {
        fromCurrency,
        toCurrency,
        amount,
        idempotencyKey: createIdempotencyKey(),
      },
    });
    setMetaText(elements.exchangeView, "兑换成功");
    await loadWallet();
    await loadWalletLedger();
  } catch (error) {
    setMetaText(elements.exchangeView, withErrorHint(error, "兑换失败"));
  }
}

async function redeemCode() {
  if (!requireAuth()) {
    return;
  }
  const code = controlValue(elements.redeemCode).trim();
  if (!code) {
    setMetaText(elements.redeemView, "请输入兑换码");
    return;
  }

  try {
    const payload = await requestApi("/api/redeem/use", {
      method: "POST",
      body: { code },
    });
    const status = String(payload && payload.status ? payload.status : "SUCCESS");
    setMetaText(elements.redeemView, `兑换结果: ${status}`);
    await loadWallet();
    await loadWalletLedger();
  } catch (error) {
    setMetaText(elements.redeemView, withErrorHint(error, "兑换失败"));
  }
}

async function loadOrdersPolicy() {
  if (!state.token) {
    return;
  }
  try {
    const payload = await requestApi("/api/orders/policy", { method: "GET" });
    state.orderPolicy = {
      refundEnabled: Boolean(payload && payload.refundEnabled),
      refundUndeliveredEnabled: Boolean(payload && payload.refundUndeliveredEnabled),
      sharedClaimAllowed: Boolean(payload && payload.sharedClaimAllowed),
    };
  } catch (error) {
    appendLog(withErrorHint(error, "订单策略读取失败"), "warn");
  }
}

function parseOptionalPositiveNumber(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return null;
  }
  const numeric = Number(text);
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return null;
  }
  return numeric;
}

function productFilters() {
  return {
    keyword: controlValue(elements.productKeyword).trim().toLowerCase(),
    sort: controlValue(elements.productSort, "default"),
    type: controlValue(elements.productFilterType, "").trim().toUpperCase(),
    currency: controlValue(elements.productFilterCurrency, "").trim().toUpperCase(),
    material: normalizeMaterialKey(controlValue(elements.productFilterMaterial, "")),
    minPrice: parseOptionalPositiveNumber(controlValue(elements.productMinPrice, "")),
    maxPrice: parseOptionalPositiveNumber(controlValue(elements.productMaxPrice, "")),
  };
}

function filterAndSortProducts(products) {
  const filters = productFilters();
  const list = (products || []).filter((product) => {
    const title = String(product && product.title ? product.title : "");
    const sku = String(product && product.sku ? product.sku : "");
    const remark = String(product && product.remark ? product.remark : "");
    const material = normalizeMaterialKey(product && product.itemMaterial ? product.itemMaterial : "");
    const searchable = `${title} ${sku} ${remark} ${material}`.toLowerCase();

    if (filters.keyword && !searchable.includes(filters.keyword)) {
      return false;
    }
    if (filters.type && String(product && product.productType ? product.productType : "").toUpperCase() !== filters.type) {
      return false;
    }
    if (filters.currency && String(product && product.currency ? product.currency : "").toUpperCase() !== filters.currency) {
      return false;
    }
    if (filters.material && material && material !== filters.material) {
      return false;
    }

    const price = Number(product && product.price);
    if (filters.minPrice !== null && Number.isFinite(price) && price < filters.minPrice) {
      return false;
    }
    if (filters.maxPrice !== null && Number.isFinite(price) && price > filters.maxPrice) {
      return false;
    }

    return true;
  });

  const sorter = filters.sort;
  list.sort((left, right) => {
    const leftPrice = Number(left && left.price);
    const rightPrice = Number(right && right.price);
    const leftTitle = String(left && left.title ? left.title : "");
    const rightTitle = String(right && right.title ? right.title : "");

    switch (sorter) {
      case "price_asc":
        return (Number.isFinite(leftPrice) ? leftPrice : 0) - (Number.isFinite(rightPrice) ? rightPrice : 0);
      case "price_desc":
        return (Number.isFinite(rightPrice) ? rightPrice : 0) - (Number.isFinite(leftPrice) ? leftPrice : 0);
      case "title_asc":
        return leftTitle.localeCompare(rightTitle, "en", { sensitivity: "base" });
      case "title_desc":
        return rightTitle.localeCompare(leftTitle, "en", { sensitivity: "base" });
      default:
        return 0;
    }
  });

  return list;
}

function attachTextureCandidates(img, candidates) {
  const list = Array.isArray(candidates) && candidates.length > 0 ? candidates : [FALLBACK_TEXTURE_DATA_URL];
  let index = 0;
  img.src = list[index];
  img.onerror = () => {
    index += 1;
    if (index < list.length) {
      img.src = list[index];
    }
  };
}

function formatRelativeTimeLabel(timestamp) {
  const value = new Date(timestamp || 0).getTime();
  if (!Number.isFinite(value) || value <= 0) {
    return "刚刚";
  }
  const diff = Math.max(0, Date.now() - value);
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  const month = 30 * day;
  const year = 365 * day;

  if (diff < minute) {
    return "刚刚";
  }
  if (diff < hour) {
    return `${Math.floor(diff / minute)} 分钟前`;
  }
  if (diff < day) {
    return `${Math.floor(diff / hour)} 小时前`;
  }
  if (diff < month) {
    return `${Math.floor(diff / day)} 天前`;
  }
  if (diff < year) {
    return `${Math.floor(diff / month)} 个月前`;
  }
  return `${Math.floor(diff / year)} 年前`;
}

function bindTradeQuantityControls(rangeInput, qtyField, minValue, maxValue, onChange) {
  const min = Math.max(1, Number(minValue) || 1);
  const max = Math.max(min, Number(maxValue) || min);

  const updateValue = (raw) => {
    const parsed = Number(raw);
    const safe = Number.isFinite(parsed) ? parsed : min;
    const value = Math.max(min, Math.min(max, Math.floor(safe)));
    rangeInput.value = String(value);
    qtyField.value = String(value);
    if (typeof qtyField.setAttribute === "function") {
      qtyField.setAttribute("value", String(value));
    }
    if (typeof onChange === "function") {
      onChange(value);
    }
  };

  rangeInput.addEventListener("input", () => updateValue(rangeInput.value));
  qtyField.addEventListener("input", () => updateValue(qtyField.value));
  qtyField.addEventListener("change", () => updateValue(qtyField.value));
  updateValue(qtyField.value || min);
}

function renderProductList() {
  if (!elements.productList) {
    return;
  }

  const products = filterAndSortProducts(state.products);
  elements.productList.innerHTML = "";

  if (products.length === 0) {
    const empty = document.createElement("mdui-card");
    empty.className = "wsx-card";
    empty.textContent = localize("暂无商品，请联系管理员在后台添加。");
    elements.productList.appendChild(empty);
    return;
  }

  const locale = getLocale();
  products.forEach((product) => {
    const productId = String(product && (product.id || product.sku || "")).trim();
    const qtyFieldId = `product-qty-${productId}`;
    const card = document.createElement("mdui-card");
    card.className = "wsx-card wsx-trade-card";

    const stockInfo = resolveOfficialProductStock(product);
    const hasStock = !stockInfo.hasTrackedStock || stockInfo.remainingStock > 0;

    const headerRow = document.createElement("div");
    headerRow.className = "wsx-trade-head";
    const badge = document.createElement("span");
    badge.className = "wsx-trade-badge";
    badge.textContent = hasStock ? "在售" : "售罄";
    const time = document.createElement("span");
    time.className = "wsx-trade-time";
    time.textContent = formatRelativeTimeLabel(product && (product.updatedAt || product.createdAt));
    headerRow.append(badge, time);
    card.appendChild(headerRow);

    const heroRow = document.createElement("div");
    heroRow.className = "wsx-trade-hero";

    const textureMaterial = resolveProductTextureMaterial(product);
    const localizedMaterial = getLocalizedMaterialName(textureMaterial, state.materialNameMap, locale);
    const texture = document.createElement("img");
    texture.className = "wsx-product-media";
    texture.alt = localizedMaterial;
    attachTextureCandidates(
      texture,
      getTextureCandidates(textureMaterial, {
        localTextureBase: "/textures",
        fallbackDataUrl: FALLBACK_TEXTURE_DATA_URL,
      })
    );
    heroRow.appendChild(texture);

    const info = document.createElement("div");
    info.className = "wsx-trade-info";
    const heading = document.createElement("h3");
    heading.className = "wsx-trade-title";
    heading.textContent = String(product && product.title ? product.title : productId || "未命名商品");
    const sellerLine = document.createElement("p");
    sellerLine.className = "wsx-trade-seller";
    sellerLine.textContent = "卖家: 官方商城";
    const metaLine = document.createElement("p");
    metaLine.className = "wsx-muted";
    metaLine.textContent = `${String(product && product.productType ? product.productType : "UNKNOWN")} | ${localizedMaterial}`;
    info.append(heading, sellerLine, metaLine);
    heroRow.appendChild(info);
    card.appendChild(heroRow);

    const progressWrap = document.createElement("div");
    progressWrap.className = "wsx-trade-progress-row";
    const progress = document.createElement("div");
    progress.className = "wsx-trade-progress";
    const progressInner = document.createElement("span");
    const stockRatio = stockInfo.hasTrackedStock && stockInfo.totalStock > 0
      ? Math.max(0, Math.min(100, Math.round((stockInfo.remainingStock / stockInfo.totalStock) * 100)))
      : 100;
    progressInner.style.width = `${stockRatio}%`;
    progress.appendChild(progressInner);
    const remain = document.createElement("span");
    remain.className = "wsx-trade-remain";
    remain.textContent = stockInfo.hasTrackedStock
      ? `剩余${stockInfo.remainingStock}`
      : "长期供应";
    progressWrap.append(progress, remain);
    card.appendChild(progressWrap);

    const priceRow = document.createElement("div");
    priceRow.className = "wsx-trade-price-row";
    const priceLabel = document.createElement("span");
    priceLabel.textContent = "单价";
    const priceValue = document.createElement("strong");
    priceValue.textContent = formatCurrency(product && product.price, product && product.currency, currencyMetaForCore(), locale);
    priceRow.append(priceLabel, priceValue);
    card.appendChild(priceRow);

    const qtyField = document.createElement("mdui-text-field");
    qtyField.id = qtyFieldId;
    qtyField.label = "数量";
    qtyField.className = "wsx-trade-qty-field";
    qtyField.value = "1";
    qtyField.type = "number";
    qtyField.min = "1";
    const maxQuantity = Math.max(1, Math.min(64, stockInfo.maxQuantity || 64));
    qtyField.max = String(maxQuantity);

    const range = document.createElement("input");
    range.type = "range";
    range.className = "wsx-trade-slider";
    range.min = "1";
    range.max = String(maxQuantity);
    range.value = "1";

    const qtyRow = document.createElement("div");
    qtyRow.className = "wsx-trade-qty-row";
    qtyRow.append(range, qtyField);
    card.appendChild(qtyRow);

    const totalLine = document.createElement("div");
    totalLine.className = "wsx-trade-total";
    card.appendChild(totalLine);

    bindTradeQuantityControls(range, qtyField, 1, maxQuantity, (quantity) => {
      const total = Math.max(0, Math.floor(Number(product && product.price ? product.price : 0) * quantity));
      totalLine.textContent = `总价: ${formatCurrency(total, product && product.currency, currencyMetaForCore(), locale)}`;
    });

    const actionRow = document.createElement("div");
    actionRow.className = "wsx-trade-actions";

    const actionBtn = document.createElement("mdui-button");
    const type = String(product && product.productType ? product.productType : "").toUpperCase();
    actionBtn.variant = "filled";
    actionBtn.className = "wsx-trade-primary-btn";
    actionBtn.textContent = hasStock ? (type === "RECYCLE_ITEM" ? "立即回收" : "立即购买") : "已售罄";
    actionBtn.disabled = !hasStock;
    actionBtn.dataset.action = "buy-product";
    actionBtn.dataset.productId = productId;
    actionBtn.dataset.qtyFieldId = qtyFieldId;
    actionRow.appendChild(actionBtn);

    card.appendChild(actionRow);

    if (product && product.remark) {
      const remark = document.createElement("p");
      remark.className = "wsx-muted";
      remark.textContent = String(product.remark);
      card.appendChild(remark);
    }

    elements.productList.appendChild(card);
  });
}

async function loadProducts() {
  try {
    const payload = await requestApi("/api/products", {
      method: "GET",
      auth: false,
    });
    state.products = Array.isArray(payload && payload.products) ? payload.products : [];
    renderProductList();
  } catch (error) {
    appendLog(withErrorHint(error, "加载商品失败"), "error");
    if (elements.productList) {
      elements.productList.innerHTML = "";
    }
  }
}

function getProductById(productId) {
  return state.products.find((product) => String(product && product.id) === String(productId));
}

async function buyProduct(productId, qtyText) {
  if (!requireAuth()) {
    return;
  }

  const product = getProductById(productId);
  if (!product) {
    notify("商品不存在或已下架", "warn");
    return;
  }

  const quantity = Number(qtyText);
  const stockInfo = resolveOfficialProductStock(product);
  const maxQuantity = Math.max(1, Math.min(64, stockInfo.maxQuantity || 64));
  if (!validateQuantity(quantity, 1, maxQuantity)) {
    notify(`购买数量需在 1-${maxQuantity} 范围内`, "warn");
    return;
  }

  const total = Math.max(0, Math.floor(Number(product && product.price ? product.price : 0) * quantity));
  const isRecycle = String(product && product.productType ? product.productType : "").toUpperCase() === "RECYCLE_ITEM";
  const confirmed = await askActionConfirm(
    isRecycle ? "确认回收" : "确认下单",
    `${String(product && product.title ? product.title : "未命名商品")} x${quantity}，预计金额 ${formatCurrency(total, product && product.currency, currencyMetaForCore(), getLocale())}`
  );
  if (!confirmed) {
    notify("已取消操作", "info");
    return;
  }

  try {
    await requestApi("/api/orders", {
      method: "POST",
      body: {
        productId: product.id,
        quantity,
        deliveryMode: "AUTO",
        idempotencyKey: createIdempotencyKey(),
      },
    });
    notify("下单成功", "success");
    await Promise.all([loadOrders(), loadWallet(), loadProducts()]);
    activateTab("orders");
  } catch (error) {
    notify(withErrorHint(error, "下单失败"), "error");
  }
}

function canRefundOrder(order) {
  if (typeof (order && order.canRefund) === "boolean") {
    return Boolean(order.canRefund);
  }
  const status = String(order && (order.orderStatus || order.status || order.state || "")).toUpperCase();
  if (status === "REFUNDED" || status === "DELIVERED" || status === "FAILED") {
    return false;
  }
  return Boolean(state.orderPolicy.refundEnabled || state.orderPolicy.refundUndeliveredEnabled);
}

function renderOrders() {
  if (!elements.orderList) {
    return;
  }

  elements.orderList.innerHTML = "";
  if (!Array.isArray(state.orders) || state.orders.length === 0) {
    setMetaText(elements.orderView, "暂无订单");
    return;
  }

  setMetaText(elements.orderView, `共 ${state.orders.length} 条订单`);
  const locale = getLocale();
  state.orders.forEach((order) => {
    const card = document.createElement("mdui-card");
    card.className = "wsx-card";

    const title = document.createElement("div");
    title.className = "wsx-card-title";
    const icon = createIconNode("coins");
    const heading = document.createElement("h3");
    const orderNo = String(order && order.orderNo ? order.orderNo : "-");
    heading.textContent = `订单 ${orderNo}`;
    title.append(icon, heading);
    card.appendChild(title);

    const status = String(order && (order.orderStatus || order.status || order.state || "UNKNOWN")).toUpperCase();
    const amountText = formatCurrency(
      order && order.totalAmount,
      order && order.currency,
      currencyMetaForCore(),
      locale
    );
    const summary = document.createElement("p");
    summary.className = "wsx-muted";
    summary.textContent = `${status} | ${amountText} | 数量 ${order && order.quantity !== undefined ? order.quantity : "-"}`;
    card.appendChild(summary);

    const time = document.createElement("p");
    time.className = "wsx-muted";
    time.textContent = `创建时间: ${formatDateTime(order && (order.createdAt || order.createTime), {
      locale,
      timeZone: state.timeZone,
    })}`;
    card.appendChild(time);

    const actionRow = document.createElement("div");
    actionRow.className = "wsx-action-row";

    if (canRefundOrder(order)) {
      const refundBtn = document.createElement("mdui-button");
      refundBtn.variant = "tonal";
      refundBtn.textContent = "申请退款";
      refundBtn.dataset.action = "order-refund";
      refundBtn.dataset.orderNo = orderNo;
      actionRow.appendChild(refundBtn);
    }

    const voucherCode = String(order && (order.groupBuyVoucherCode || order.voucherCode || "")).trim();
    if (voucherCode) {
      const copyVoucherBtn = document.createElement("mdui-button");
      copyVoucherBtn.variant = "outlined";
      copyVoucherBtn.textContent = "复制团购码";
      copyVoucherBtn.dataset.action = "copy-text";
      copyVoucherBtn.dataset.text = voucherCode;
      actionRow.appendChild(copyVoucherBtn);
    }

    const claimCommand = String(order && (order.claimCommand || order.sharedClaimCommand || "")).trim();
    if (claimCommand && status === "WAIT_CLAIM") {
      const copyCmdBtn = document.createElement("mdui-button");
      copyCmdBtn.variant = "outlined";
      copyCmdBtn.textContent = "复制领取命令";
      copyCmdBtn.dataset.action = "copy-text";
      copyCmdBtn.dataset.text = claimCommand;
      actionRow.appendChild(copyCmdBtn);
      if (state.orderPolicy.sharedClaimAllowed) {
        const hint = document.createElement("span");
        hint.className = "wsx-muted";
        hint.textContent = "此订单支持共享领取";
        actionRow.appendChild(hint);
      }
    }

    if (actionRow.childElementCount > 0) {
      card.appendChild(actionRow);
    }
    elements.orderList.appendChild(card);
  });
}

async function loadOrders(limit = 50) {
  if (!requireAuth()) {
    return;
  }
  try {
    const payload = await requestApi(`/api/orders/list?limit=${Math.max(1, Number(limit) || 50)}`, {
      method: "GET",
    });
    const orders = Array.isArray(payload && payload.orders) ? payload.orders : [];
    state.orders = orders;
    renderOrders();
  } catch (error) {
    setMetaText(elements.orderView, withErrorHint(error, "加载订单失败"));
  }
}

async function refundOrder(orderNo) {
  if (!requireAuth()) {
    return;
  }
  const confirmed = await askActionConfirm("确认退款", `确认申请订单 ${orderNo} 的退款吗？`);
  if (!confirmed) {
    notify("已取消退款", "info");
    return;
  }
  try {
    await requestApi("/api/orders/refund", {
      method: "POST",
      body: { orderNo },
    });
    notify("退款请求已提交", "success");
    await Promise.all([loadOrders(), loadWallet()]);
  } catch (error) {
    notify(withErrorHint(error, "退款失败"), "error");
  }
}

function marketFilterControls(scope) {
  if (scope === "AUCTION") {
    return {
      keyword: elements.auctionKeyword,
      material: elements.auctionMaterial,
      currency: elements.auctionCurrency,
      minPrice: elements.auctionMinPrice,
      maxPrice: elements.auctionMaxPrice,
      sort: elements.auctionSort,
      order: elements.auctionSortOrder,
      container: elements.auctionList,
      view: elements.auctionView,
    };
  }
  return {
    keyword: elements.marketKeyword,
    material: elements.marketMaterial,
    currency: elements.marketCurrency,
    minPrice: elements.marketMinPrice,
    maxPrice: elements.marketMaxPrice,
    sort: elements.marketSort,
    order: elements.marketSortOrder,
    container: elements.marketList,
    view: elements.marketView,
  };
}

function resetMarketFilters(scope) {
  const controls = marketFilterControls(scope);
  setControlValue(controls.keyword, "");
  setControlValue(controls.material, "");
  setControlValue(controls.currency, "");
  setControlValue(controls.minPrice, "");
  setControlValue(controls.maxPrice, "");
  setControlValue(controls.sort, "createdAt");
  setControlValue(controls.order, "desc");
}

function renderMarketHeader() {
  if (!elements.marketSectionTitle || !elements.marketSectionDesc) {
    return;
  }
  if (state.marketMode === "stores") {
    if (state.marketStore.sellerName) {
      elements.marketSectionTitle.textContent = `玩家店铺: ${state.marketStore.sellerName}`;
      elements.marketSectionDesc.textContent = "当前展示单个店铺上架，可返回店铺列表。";
    } else {
      elements.marketSectionTitle.textContent = "玩家店铺（按卖家聚合）";
      elements.marketSectionDesc.textContent = "选择店铺后进入该卖家上架列表。";
    }
  } else if (state.marketMode === "mine") {
    elements.marketSectionTitle.textContent = "我的上架";
    elements.marketSectionDesc.textContent = "可编辑价格、备注和交易模式。";
  } else {
    elements.marketSectionTitle.textContent = "玩家市场（C2C）";
    elements.marketSectionDesc.textContent = "交易模式: 直购";
  }
}

function renderMarketModeButtons() {
  setButtonActive(elements.marketModePublicBtn, state.marketMode === "public");
  setButtonActive(elements.marketModeStoresBtn, state.marketMode === "stores");
  setButtonActive(elements.marketModeMineBtn, state.marketMode === "mine");
  if (elements.hideOwnMarketListings) {
    elements.hideOwnMarketListings.checked = Boolean(state.hideOwnMarketListings);
    elements.hideOwnMarketListings.disabled = state.marketMode !== "public";
  }
  renderMarketHeader();
}

function buildMarketQuery(scope) {
  const controls = marketFilterControls(scope);
  const params = new URLSearchParams();
  params.set("limit", "80");

  const keyword = controlValue(controls.keyword).trim();
  const material = normalizeMaterialKey(controlValue(controls.material).trim());
  const currency = controlValue(controls.currency).trim().toUpperCase();
  const minPrice = parseOptionalPositiveNumber(controlValue(controls.minPrice));
  const maxPrice = parseOptionalPositiveNumber(controlValue(controls.maxPrice));
  const sort = controlValue(controls.sort, "createdAt").trim();
  const order = controlValue(controls.order, "desc").trim();

  if (keyword) {
    params.set("keyword", keyword);
  }
  if (material) {
    params.set("material", material);
  }
  if (currency) {
    params.set("currency", currency);
  }
  if (minPrice !== null) {
    params.set("minPrice", String(minPrice));
  }
  if (maxPrice !== null) {
    params.set("maxPrice", String(maxPrice));
  }
  if (sort) {
    params.set("sort", sort);
  }
  if (order) {
    params.set("order", order);
  }

  params.set("tradeMode", scope);

  if (scope === "DIRECT") {
    if (state.marketMode === "mine") {
      params.set("mine", "true");
    } else {
      params.set("mine", "false");
    }
    if (state.marketMode === "stores" && state.marketStore.sellerUuid) {
      params.set("seller", state.marketStore.sellerUuid);
    }
  }
  return params;
}

function listingLocalizedMaterial(listing) {
  const material = normalizeMaterialKey(listing && listing.itemMaterial ? listing.itemMaterial : "");
  return getLocalizedMaterialName(material, state.materialNameMap, getLocale());
}

function listingTextureMaterial(listing) {
  const material = normalizeMaterialKey(listing && listing.itemMaterial ? listing.itemMaterial : "");
  if (material) {
    return material;
  }
  return resolveProductTextureMaterial({ productType: "GIVE_ITEM", itemMaterial: "BUNDLE" });
}

function renderStoreCards(listings, container) {
  const map = new Map();
  listings.forEach((listing) => {
    const sellerKey = String(listing && (listing.sellerUuid || listing.sellerKey || listing.sellerName || "")).trim();
    if (!sellerKey) {
      return;
    }
    if (!map.has(sellerKey)) {
      map.set(sellerKey, {
        sellerKey,
        sellerUuid: String(listing && listing.sellerUuid ? listing.sellerUuid : "").trim() || null,
        sellerName: String(listing && listing.sellerName ? listing.sellerName : sellerKey),
        listings: [],
      });
    }
    map.get(sellerKey).listings.push(listing);
  });

  const stores = Array.from(map.values());
  stores.sort((left, right) => right.listings.length - left.listings.length);

  if (stores.length === 0) {
    const empty = document.createElement("mdui-card");
    empty.className = "wsx-card";
    empty.textContent = "暂无玩家店铺数据。";
    container.appendChild(empty);
    return;
  }

  stores.forEach((store) => {
    const card = document.createElement("mdui-card");
    card.className = "wsx-card wsx-trade-card";

    const headerRow = document.createElement("div");
    headerRow.className = "wsx-trade-head";
    const badge = document.createElement("span");
    badge.className = "wsx-trade-badge";
    badge.textContent = "店铺";
    const time = document.createElement("span");
    time.className = "wsx-trade-time";
    time.textContent = "玩家市场";
    headerRow.append(badge, time);
    card.appendChild(headerRow);

    const heroRow = document.createElement("div");
    heroRow.className = "wsx-trade-hero";

    const avatar = document.createElement("img");
    avatar.className = "wsx-product-media";
    avatar.alt = store.sellerName;
    const avatarSeed = String(store.sellerName || store.sellerKey || "store").trim();
    avatar.src = `https://nmsr.nickac.dev/face/${encodeURIComponent(avatarSeed)}`;
    avatar.onerror = () => {
      avatar.src = FALLBACK_TEXTURE_DATA_URL;
    };
    heroRow.appendChild(avatar);

    const info = document.createElement("div");
    info.className = "wsx-trade-info";
    const heading = document.createElement("h3");
    heading.className = "wsx-trade-title";
    heading.textContent = `${store.sellerName} 的店铺`;
    const seller = document.createElement("p");
    seller.className = "wsx-trade-seller";
    seller.textContent = `卖家: ${store.sellerName}`;
    info.append(heading, seller);
    heroRow.appendChild(info);
    card.appendChild(heroRow);

    const progressWrap = document.createElement("div");
    progressWrap.className = "wsx-trade-progress-row";
    const progress = document.createElement("div");
    progress.className = "wsx-trade-progress";
    const progressInner = document.createElement("span");
    progressInner.style.width = "100%";
    progress.appendChild(progressInner);
    const remain = document.createElement("span");
    remain.className = "wsx-trade-remain";
    remain.textContent = `在售${store.listings.length}`;
    progressWrap.append(progress, remain);
    card.appendChild(progressWrap);

    const priceRow = document.createElement("div");
    priceRow.className = "wsx-trade-price-row";
    const priceLabel = document.createElement("span");
    priceLabel.textContent = "商品数";
    const priceValue = document.createElement("strong");
    priceValue.textContent = String(store.listings.length);
    priceRow.append(priceLabel, priceValue);
    card.appendChild(priceRow);

    const btnRow = document.createElement("div");
    btnRow.className = "wsx-trade-actions";
    const openBtn = document.createElement("mdui-button");
    openBtn.variant = "filled";
    openBtn.className = "wsx-trade-primary-btn";
    openBtn.textContent = "进入店铺";
    openBtn.dataset.action = "market-open-store";
    openBtn.dataset.sellerKey = store.sellerKey;
    openBtn.dataset.sellerUuid = store.sellerUuid || "";
    openBtn.dataset.sellerName = store.sellerName;
    btnRow.appendChild(openBtn);
    card.appendChild(btnRow);

    container.appendChild(card);
  });

  if (state.marketStore.sellerName) {
    const backCard = document.createElement("mdui-card");
    backCard.className = "wsx-card";
    const title = document.createElement("h3");
    title.textContent = `当前店铺: ${state.marketStore.sellerName}`;
    const backBtn = document.createElement("mdui-button");
    backBtn.variant = "tonal";
    backBtn.textContent = "返回店铺列表";
    backBtn.dataset.action = "market-back-stores";
    backCard.append(title, backBtn);
    container.prepend(backCard);
  }
}

function renderMarketListings(scope) {
  const controls = marketFilterControls(scope);
  if (!controls.container) {
    return;
  }

  controls.container.innerHTML = "";
  const locale = getLocale();
  const listings = Array.isArray(state.listingsByScope[scope]) ? state.listingsByScope[scope] : [];

  if (scope === "DIRECT" && state.marketMode === "stores" && !state.marketStore.sellerKey) {
    renderStoreCards(listings, controls.container);
    setMetaText(controls.view, `店铺数 ${new Set(listings.map((item) => item.sellerUuid || item.sellerName || "")).size}`);
    return;
  }

  const visible = scope === "DIRECT" && state.marketMode === "stores" && state.marketStore.sellerKey
    ? listings.filter((listing) => {
      const uuid = String(listing && listing.sellerUuid ? listing.sellerUuid : "");
      const key = String(listing && (listing.sellerKey || listing.sellerName || ""));
      return uuid === state.marketStore.sellerUuid || key === state.marketStore.sellerKey;
    })
    : listings;

  if (visible.length === 0) {
    const empty = document.createElement("mdui-card");
    empty.className = "wsx-card";
    empty.textContent = scope === "AUCTION" ? "暂无拍卖数据" : "暂无市场数据";
    controls.container.appendChild(empty);
    setMetaText(controls.view, scope === "AUCTION" ? "暂无拍卖数据" : "暂无市场数据");
    return;
  }

  setMetaText(controls.view, `共 ${visible.length} 条上架`);

  visible.forEach((listing) => {
    const listingId = String(listing && listing.id ? listing.id : "");
    const card = document.createElement("mdui-card");
    card.className = "wsx-card wsx-trade-card";

    const seller = String(listing && listing.sellerName ? listing.sellerName : "未知卖家");
    const quantityTotal = Number(listing && listing.quantityTotal !== undefined
      ? listing.quantityTotal
      : (listing && listing.quantity !== undefined ? listing.quantity : 0));
    const quantityAvailable = Number(listing && listing.quantity !== undefined
      ? listing.quantity
      : quantityTotal);

    const headerRow = document.createElement("div");
    headerRow.className = "wsx-trade-head";
    const badge = document.createElement("span");
    badge.className = "wsx-trade-badge";
    const status = String(listing && listing.status ? listing.status : "ACTIVE").toUpperCase();
    badge.textContent = scope === "AUCTION" ? "拍卖" : (status === "ACTIVE" ? "在售" : status);
    const time = document.createElement("span");
    time.className = "wsx-trade-time";
    time.textContent = formatRelativeTimeLabel(listing && listing.createdAt);
    headerRow.append(badge, time);
    card.appendChild(headerRow);

    const heroRow = document.createElement("div");
    heroRow.className = "wsx-trade-hero";

    const media = document.createElement("img");
    media.className = "wsx-product-media";
    media.alt = listingLocalizedMaterial(listing);
    attachTextureCandidates(
      media,
      getTextureCandidates(listingTextureMaterial(listing), {
        localTextureBase: "/textures",
        fallbackDataUrl: FALLBACK_TEXTURE_DATA_URL,
      })
    );
    heroRow.appendChild(media);

    const detail = document.createElement("div");
    detail.className = "wsx-trade-info";
    const heading = document.createElement("h3");
    heading.className = "wsx-trade-title";
    heading.textContent = `${listingLocalizedMaterial(listing)}${quantityTotal > 0 ? ` x${quantityTotal}` : ""}`;
    const sellerLine = document.createElement("p");
    sellerLine.className = "wsx-trade-seller";
    sellerLine.textContent = `卖家: ${seller}`;
    detail.append(heading, sellerLine);
    heroRow.appendChild(detail);
    card.appendChild(heroRow);

    const progressWrap = document.createElement("div");
    progressWrap.className = "wsx-trade-progress-row";
    const progress = document.createElement("div");
    progress.className = "wsx-trade-progress";
    const progressInner = document.createElement("span");
    const ratio = quantityTotal > 0
      ? Math.max(0, Math.min(100, Math.round((quantityAvailable / quantityTotal) * 100)))
      : 100;
    progressInner.style.width = `${ratio}%`;
    progress.appendChild(progressInner);
    const remain = document.createElement("span");
    remain.className = "wsx-trade-remain";
    remain.textContent = scope === "AUCTION"
      ? `余量${Math.max(0, quantityAvailable)}`
      : `剩余${Math.max(0, quantityAvailable)}`;
    progressWrap.append(progress, remain);
    card.appendChild(progressWrap);

    const unitPrice = Number(listing && (listing.price !== undefined ? listing.price : listing.currentHighestBid)) || 0;
    const priceRow = document.createElement("div");
    priceRow.className = "wsx-trade-price-row";
    const priceLabel = document.createElement("span");
    priceLabel.textContent = scope === "AUCTION" ? "当前价" : "单价";
    const priceValue = document.createElement("strong");
    priceValue.textContent = formatCurrency(
      unitPrice,
      listing && listing.currency,
      currencyMetaForCore(),
      locale
    );
    priceRow.append(priceLabel, priceValue);
    card.appendChild(priceRow);

    const dynamicParams = parseAlgorithmParamsJson(listing && listing.dynamicParamsJson);
    const dynamicKeys = Object.keys(dynamicParams || {});
    if (dynamicKeys.length > 0) {
      const dynamic = document.createElement("p");
      dynamic.className = "wsx-muted";
      dynamic.textContent = `动态参数: ${dynamicKeys.slice(0, 5).join(", ")}`;
      card.appendChild(dynamic);
    }

    const actionRow = document.createElement("div");
    actionRow.className = "wsx-trade-actions";

    const isMine = String(listing && listing.sellerUuid ? listing.sellerUuid : "") === String(state.boundUuid || "")
      || String(listing && listing.sellerName ? listing.sellerName : "").toLowerCase() === String(state.username || "").toLowerCase();

    if (scope === "DIRECT" && !isMine && state.marketMode !== "mine") {
      const maxQuantity = Math.max(1, Math.min(64, Number(listing && listing.quantity ? listing.quantity : 64)));
      const range = document.createElement("input");
      range.type = "range";
      range.className = "wsx-trade-slider";
      range.min = "1";
      range.max = String(maxQuantity);
      range.value = "1";

      const qtyField = document.createElement("mdui-text-field");
      qtyField.id = `listing-buy-${listingId}`;
      qtyField.label = "购买数量";
      qtyField.className = "wsx-trade-qty-field";
      qtyField.type = "number";
      qtyField.min = "1";
      qtyField.max = String(maxQuantity);
      qtyField.value = "1";

      const qtyRow = document.createElement("div");
      qtyRow.className = "wsx-trade-qty-row";
      qtyRow.append(range, qtyField);
      actionRow.appendChild(qtyRow);

      const totalLine = document.createElement("div");
      totalLine.className = "wsx-trade-total";
      actionRow.appendChild(totalLine);

      bindTradeQuantityControls(range, qtyField, 1, maxQuantity, (quantity) => {
        const total = Math.max(0, Math.floor(unitPrice * quantity));
        totalLine.textContent = `总价: ${formatCurrency(total, listing && listing.currency, currencyMetaForCore(), locale)}`;
      });

      const canBuy = quantityAvailable > 0;

      const buyBtn = document.createElement("mdui-button");
      buyBtn.variant = "filled";
      buyBtn.className = "wsx-trade-primary-btn";
      buyBtn.textContent = canBuy ? "立即购买" : "暂不可购";
      buyBtn.disabled = !canBuy;
      buyBtn.dataset.action = "market-buy";
      buyBtn.dataset.listingId = listingId;
      buyBtn.dataset.qtyFieldId = qtyField.id;
      actionRow.appendChild(buyBtn);
    }

    if (scope === "AUCTION" && !isMine) {
      const bidField = document.createElement("mdui-text-field");
      bidField.id = `listing-bid-${listingId}`;
      bidField.label = "出价";
      bidField.className = "wsx-trade-qty-field";
      bidField.type = "number";
      bidField.min = "1";
      bidField.value = String(
        Number(listing && (listing.minimumRequiredBid || listing.currentHighestBid || listing.price || 1)) || 1
      );
      actionRow.appendChild(bidField);

      const bidBtn = document.createElement("mdui-button");
      bidBtn.variant = "filled";
      bidBtn.className = "wsx-trade-primary-btn";
      bidBtn.textContent = "提交出价";
      bidBtn.dataset.action = "market-bid";
      bidBtn.dataset.listingId = listingId;
      bidBtn.dataset.bidFieldId = bidField.id;
      actionRow.appendChild(bidBtn);
    }

    if (isMine || state.marketMode === "mine") {
      actionRow.classList.add("wsx-trade-admin-actions");
      const editBtn = document.createElement("mdui-button");
      editBtn.variant = "tonal";
      editBtn.textContent = "编辑";
      editBtn.dataset.action = "market-edit";
      editBtn.dataset.listingId = listingId;
      actionRow.appendChild(editBtn);

      const pauseBtn = document.createElement("mdui-button");
      pauseBtn.variant = "outlined";
      pauseBtn.textContent = "临时下架";
      pauseBtn.dataset.action = "market-pause";
      pauseBtn.dataset.listingId = listingId;
      actionRow.appendChild(pauseBtn);

      const resumeBtn = document.createElement("mdui-button");
      resumeBtn.variant = "outlined";
      resumeBtn.textContent = "恢复上架";
      resumeBtn.dataset.action = "market-resume";
      resumeBtn.dataset.listingId = listingId;
      actionRow.appendChild(resumeBtn);

      const unlistBtn = document.createElement("mdui-button");
      unlistBtn.variant = "outlined";
      unlistBtn.textContent = "下架退回";
      unlistBtn.dataset.action = "market-unlist";
      unlistBtn.dataset.listingId = listingId;
      actionRow.appendChild(unlistBtn);

      const refreshBtn = document.createElement("mdui-button");
      refreshBtn.variant = "outlined";
      refreshBtn.textContent = "刷新补货";
      refreshBtn.dataset.action = "market-supply-refresh";
      refreshBtn.dataset.listingId = listingId;
      actionRow.appendChild(refreshBtn);
    }

    if (actionRow.childElementCount > 0) {
      card.appendChild(actionRow);
    }

    controls.container.appendChild(card);
  });

  if (state.marketMode === "stores" && state.marketStore.sellerKey) {
    const topCard = document.createElement("mdui-card");
    topCard.className = "wsx-card";
    const backButton = document.createElement("mdui-button");
    backButton.variant = "tonal";
    backButton.textContent = "返回店铺列表";
    backButton.dataset.action = "market-back-stores";
    topCard.appendChild(backButton);
    controls.container.prepend(topCard);
  }
}

function postFilterListings(rawListings, scope) {
  let listings = Array.isArray(rawListings) ? rawListings.slice() : [];

  if (scope === "DIRECT" && state.marketMode === "public" && state.hideOwnMarketListings) {
    listings = listings.filter((listing) => {
      const sellerUuid = String(listing && listing.sellerUuid ? listing.sellerUuid : "");
      const sellerName = String(listing && listing.sellerName ? listing.sellerName : "").toLowerCase();
      if (state.boundUuid && sellerUuid && sellerUuid === state.boundUuid) {
        return false;
      }
      if (state.username && sellerName && sellerName === String(state.username).toLowerCase()) {
        return false;
      }
      return true;
    });
  }

  return listings;
}

async function loadMarketListings(scope = "DIRECT") {
  if (!requireAuth()) {
    return;
  }

  const normalizedScope = scope === "AUCTION" ? "AUCTION" : "DIRECT";
  const controls = marketFilterControls(normalizedScope);
  const query = buildMarketQuery(normalizedScope);

  try {
    const payload = await requestApi(`/api/market/listings?${query.toString()}`, { method: "GET" });
    const listings = postFilterListings(payload && payload.listings, normalizedScope);
    state.listingsByScope[normalizedScope] = listings;
    renderMarketListings(normalizedScope);
  } catch (error) {
    setMetaText(controls.view, withErrorHint(error, "加载市场数据失败"));
  }
}

function getListingById(listingId) {
  return [...state.listingsByScope.DIRECT, ...state.listingsByScope.AUCTION]
    .find((item) => String(item && item.id) === String(listingId));
}

async function buyListing(listingId, qtyText) {
  if (!requireAuth()) {
    return;
  }
  const listing = getListingById(listingId);
  if (!listing) {
    notify("上架不存在", "warn");
    return;
  }

  const maxQuantity = Math.max(1, Math.min(64, Number(listing && listing.quantity ? listing.quantity : 64)));
  const quantity = Number(qtyText);
  if (!validateQuantity(quantity, 1, maxQuantity)) {
    notify(`购买数量需在 1-${maxQuantity} 范围内`, "warn");
    return;
  }

  const total = Math.max(0, Math.floor(Number(listing && listing.price ? listing.price : 0) * quantity));
  const confirmed = await askActionConfirm(
    "确认购买",
    `确认购买上架 #${listingId} x${quantity}，预计金额 ${formatCurrency(total, listing && listing.currency, currencyMetaForCore(), getLocale())} 吗？`
  );
  if (!confirmed) {
    notify("已取消购买", "info");
    return;
  }

  try {
    await requestApi("/api/market/buy", {
      method: "POST",
      body: {
        listingId,
        buyQuantity: quantity,
        deliveryMode: "AUTO",
        idempotencyKey: createIdempotencyKey(),
      },
    });
    notify("购买成功", "success");
    await Promise.all([loadWallet(), loadOrders(), loadMarketListings("DIRECT")]);
  } catch (error) {
    notify(withErrorHint(error, "购买失败"), "error");
  }
}

async function bidListing(listingId, bidText) {
  if (!requireAuth()) {
    return;
  }
  const listing = getListingById(listingId);
  if (!listing) {
    notify("上架不存在", "warn");
    return;
  }

  const bidAmount = Number(bidText);
  const minIncrement = Number(listing && (listing.auctionMinIncrement || listing.minimumIncrement || 1));
  const currentBid = Number(listing && (listing.currentHighestBid || listing.price || listing.auctionStartPrice || 0));
  const minimumRequired = Number(listing && listing.minimumRequiredBid);
  const baseline = Number.isFinite(minimumRequired) && minimumRequired > 0
    ? minimumRequired
    : (Number.isFinite(currentBid) ? currentBid + (Number.isFinite(minIncrement) ? minIncrement : 1) : 1);

  if (!Number.isFinite(bidAmount) || bidAmount < baseline) {
    notify(`出价必须大于等于 ${baseline}`, "warn");
    return;
  }

  const confirmed = await askActionConfirm(
    "确认出价",
    `确认对上架 #${listingId} 出价 ${formatCurrency(bidAmount, listing && listing.currency, currencyMetaForCore(), getLocale())} 吗？`
  );
  if (!confirmed) {
    notify("已取消出价", "info");
    return;
  }

  try {
    await requestApi("/api/market/bid", {
      method: "POST",
      body: {
        listingId,
        bidAmount,
        idempotencyKey: createIdempotencyKey(),
      },
    });
    notify("出价已提交", "success");
    await Promise.all([loadWallet(), loadOrders(), loadMarketListings("AUCTION")]);
  } catch (error) {
    notify(withErrorHint(error, "出价失败"), "error");
  }
}

function openMarketEditDialog(listingId) {
  const listing = getListingById(listingId);
  if (!listing) {
    notify("找不到上架记录", "warn");
    return;
  }

  state.editingListing = listing;
  setControlValue(elements.marketEditPrice, listing.price || "");
  setControlValue(elements.marketEditTradeMode, listing.tradeMode || "DIRECT");
  setControlValue(elements.marketEditRemark, listing.remark || "");

  setControlValue(elements.marketEditSupplyBatchSize, listing.supplyBatchSize || "");
  setControlValue(elements.marketEditSupplyMaxStock, listing.supplyMaxStock || "");
  setControlValue(elements.marketEditDynamicBase, listing.dynamicBasePrice || "");
  setControlValue(elements.marketEditDynamicStep, listing.dynamicPriceStep || "");
  setControlValue(elements.marketEditDynamicFloor, listing.dynamicFloorPrice || "");
  setControlValue(elements.marketEditDynamicCap, listing.dynamicCapPrice || "");
  setControlValue(elements.marketEditAuctionStart, listing.auctionStartPrice || "");
  setControlValue(elements.marketEditAuctionIncrement, listing.auctionMinIncrement || "");
  setControlValue(elements.marketEditAuctionEndAt, toDateTimeLocalValue(listing.auctionEndAt, { timeZone: state.timeZone }));

  if (elements.marketEditDialog) {
    elements.marketEditDialog.open = true;
  }
}

function closeMarketEditDialog() {
  if (elements.marketEditDialog) {
    elements.marketEditDialog.open = false;
  }
  state.editingListing = null;
}

function validateMarketEditPayload(payload, tradeMode) {
  if (!validatePrice(payload.price)) {
    return "价格必须大于 0";
  }

  if (payload.supplyBatchSizeRaw && payload.supplyBatchSize === null) {
    return "供货批量必须为正整数";
  }
  if (payload.supplyMaxStockRaw && payload.supplyMaxStock === null) {
    return "供货上限必须为正整数";
  }

  if (payload.dynamicBaseRaw && payload.dynamicBasePrice === null) {
    return "动态基准价必须大于 0";
  }
  if (payload.dynamicStepRaw && payload.dynamicPriceStep === null) {
    return "动态步长必须大于 0";
  }
  if (payload.dynamicFloorRaw && payload.dynamicFloorPrice === null) {
    return "动态最低价必须大于 0";
  }
  if (payload.dynamicCapRaw && payload.dynamicCapPrice === null) {
    return "动态最高价必须大于 0";
  }

  if (payload.dynamicFloorPrice !== null
    && payload.dynamicCapPrice !== null
    && payload.dynamicFloorPrice > payload.dynamicCapPrice) {
    return "动态最低价不能高于动态最高价";
  }

  if (tradeMode === "AUCTION") {
    if (!payload.auctionStartPrice || payload.auctionStartPrice <= 0) {
      return "拍卖模式下起拍价必须大于 0";
    }
    if (!payload.auctionMinIncrement || payload.auctionMinIncrement <= 0) {
      return "拍卖模式下最小加价必须大于 0";
    }
    if (!payload.auctionEndAt) {
      return "拍卖模式下必须提供结束时间";
    }
    const endTimestamp = parseDateTimeValue(payload.auctionEndAt, state.timeZone);
    if (!Number.isFinite(endTimestamp) || endTimestamp <= Date.now()) {
      return "拍卖结束时间必须晚于当前时间";
    }
  }

  return "";
}

async function saveMarketEdit() {
  if (!requireAuth()) {
    return;
  }
  if (!state.editingListing) {
    notify("没有可编辑的上架记录", "warn");
    return;
  }

  const tradeMode = controlValue(elements.marketEditTradeMode, "DIRECT").toUpperCase() === "AUCTION"
    ? "AUCTION"
    : "DIRECT";

  const payload = {
    listingId: state.editingListing.id,
    price: Number(controlValue(elements.marketEditPrice, "0")),
    currency: state.editingListing.currency,
    remark: controlValue(elements.marketEditRemark).trim(),
    tradeMode,

    supplyBatchSizeRaw: controlValue(elements.marketEditSupplyBatchSize),
    supplyMaxStockRaw: controlValue(elements.marketEditSupplyMaxStock),
    dynamicBaseRaw: controlValue(elements.marketEditDynamicBase),
    dynamicStepRaw: controlValue(elements.marketEditDynamicStep),
    dynamicFloorRaw: controlValue(elements.marketEditDynamicFloor),
    dynamicCapRaw: controlValue(elements.marketEditDynamicCap),

    supplyBatchSize: parseOptionalPositiveWhole(controlValue(elements.marketEditSupplyBatchSize)),
    supplyMaxStock: parseOptionalPositiveWhole(controlValue(elements.marketEditSupplyMaxStock)),
    dynamicBasePrice: parseOptionalPositiveNumber(controlValue(elements.marketEditDynamicBase)),
    dynamicPriceStep: parseOptionalPositiveNumber(controlValue(elements.marketEditDynamicStep)),
    dynamicFloorPrice: parseOptionalPositiveNumber(controlValue(elements.marketEditDynamicFloor)),
    dynamicCapPrice: parseOptionalPositiveNumber(controlValue(elements.marketEditDynamicCap)),

    auctionStartPrice: parseOptionalPositiveNumber(controlValue(elements.marketEditAuctionStart)),
    auctionMinIncrement: parseOptionalPositiveNumber(controlValue(elements.marketEditAuctionIncrement)),
    auctionEndAt: controlValue(elements.marketEditAuctionEndAt).trim(),
  };

  const validationMessage = validateMarketEditPayload(payload, tradeMode);
  if (validationMessage) {
    notify(validationMessage, "warn");
    return;
  }

  const requestPayload = {
    listingId: payload.listingId,
    price: payload.price,
    currency: payload.currency,
    remark: payload.remark,
    tradeMode,

    dynamicPricingEnabled: Boolean(state.editingListing.dynamicPricingEnabled),
    dynamicAlgorithm: state.editingListing.dynamicAlgorithm,
    dynamicParamsJson: state.editingListing.dynamicParamsJson,

    auctionAlgorithm: state.editingListing.auctionAlgorithm,
    auctionParamsJson: state.editingListing.auctionParamsJson,

    supplyBatchSize: payload.supplyBatchSize,
    supplyMaxStock: payload.supplyMaxStock,
    dynamicBasePrice: payload.dynamicBasePrice,
    dynamicFloorPrice: payload.dynamicFloorPrice,
    dynamicCapPrice: payload.dynamicCapPrice,
    dynamicPriceStep: payload.dynamicPriceStep,
    auctionStartPrice: payload.auctionStartPrice,
    auctionMinIncrement: payload.auctionMinIncrement,
    auctionEndAt: payload.auctionEndAt,
  };

  Object.keys(requestPayload).forEach((key) => {
    const value = requestPayload[key];
    if (value === "" || value === null || value === undefined) {
      delete requestPayload[key];
    }
  });

  try {
    await requestApi("/api/market/settings", {
      method: "POST",
      body: requestPayload,
    });
    notify("上架设置已更新", "success");
    closeMarketEditDialog();
    await loadMarketListings(state.activeTab === "auction" ? "AUCTION" : "DIRECT");
  } catch (error) {
    notify(withErrorHint(error, "保存上架失败"), "error");
  }
}

async function runListingAction(path, listingId, successMessage, confirmMessage = "") {
  if (!requireAuth()) {
    return;
  }
  if (confirmMessage) {
    const confirmed = await askActionConfirm("确认操作", confirmMessage);
    if (!confirmed) {
      notify("已取消操作", "info");
      return;
    }
  }
  try {
    await requestApi(path, {
      method: "POST",
      body: { listingId },
    });
    notify(successMessage, "success");
    await loadMarketListings(state.activeTab === "auction" ? "AUCTION" : "DIRECT");
  } catch (error) {
    notify(withErrorHint(error, "操作失败"), "error");
  }
}

async function copyText(text) {
  const value = String(text || "").trim();
  if (!value) {
    notify("没有可复制的内容", "warn");
    return;
  }
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(value);
    } else {
      const area = document.createElement("textarea");
      area.value = value;
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      document.execCommand("copy");
      area.remove();
    }
    notify("已复制到剪贴板", "success");
  } catch (error) {
    notify("复制失败", "error");
  }
}

async function refreshRealtime() {
  if (!state.token || state.realtime.busy) {
    return;
  }
  state.realtime.busy = true;
  try {
    const [walletPayload, ordersPayload] = await Promise.all([
      requestApi("/api/wallet", { method: "GET" }),
      requestApi("/api/orders/list?limit=30", { method: "GET" }),
    ]);

    if (walletPayload) {
      updateWalletView(walletPayload);
    }

    const nextOrders = Array.isArray(ordersPayload && ordersPayload.orders) ? ordersPayload.orders : [];
    const digest = buildOrderDigest(nextOrders);
    if (!compareDigest(state.realtime.orderDigest, digest)) {
      state.realtime.orderDigest = digest;
      state.orders = nextOrders;
      renderOrders();
      appendLog("检测到订单状态变化", "info");
    }

    if (state.activeTab === "market" || state.activeTab === "auction") {
      const scope = state.activeTab === "auction" ? "AUCTION" : "DIRECT";
      const query = buildMarketQuery(scope);
      const marketPayload = await requestApi(`/api/market/listings?${query.toString()}`, { method: "GET" });
      const nextListings = postFilterListings(marketPayload && marketPayload.listings, scope);
      const listingDigest = buildListingDigest(nextListings);
      if (!compareDigest(state.realtime.listingDigest[scope], listingDigest)) {
        state.realtime.listingDigest[scope] = listingDigest;
        state.listingsByScope[scope] = nextListings;
        renderMarketListings(scope);
        appendLog("检测到市场数据变化", "info");
      }
    }
  } catch (error) {
    appendLog(withErrorHint(error, "实时刷新失败"), "warn");
  } finally {
    state.realtime.busy = false;
  }
}

function startRealtime() {
  if (state.realtime.timer) {
    window.clearInterval(state.realtime.timer);
    state.realtime.timer = null;
  }
  state.realtime.timer = window.setInterval(() => {
    void refreshRealtime();
  }, 8000);
}

function stopRealtime() {
  if (state.realtime.timer) {
    window.clearInterval(state.realtime.timer);
    state.realtime.timer = null;
  }
}

function bindCoreEvents() {
  if (elements.themeToggleBtn) {
    elements.themeToggleBtn.addEventListener("click", () => {
      applyTheme(state.theme === "dark" ? "light" : "dark");
    });
  }

  if (elements.localeSelect) {
    elements.localeSelect.addEventListener("change", async () => {
      const locale = normalizeLocale(controlValue(elements.localeSelect, "zh-CN"));
      try {
        window.localStorage.setItem(STORAGE_KEYS.locale, locale);
      } catch (error) {
        appendLog(`保存语言失败: ${error.message}`, "warn");
      }
      if (I18N && typeof I18N.setLocale === "function") {
        I18N.setLocale(locale);
      }
      state.materialNameMapReady = false;
      state.marketAlgorithmGlossaryReady = false;
      await Promise.all([ensureMaterialNameMap(), ensureMarketAlgorithmGlossary()]);
      renderProductList();
      renderOrders();
      renderMarketListings("DIRECT");
      renderMarketListings("AUCTION");
    });
  }

  if (elements.loginBtn) {
    elements.loginBtn.addEventListener("click", () => void login());
  }
  if (elements.loginIdentifier) {
    elements.loginIdentifier.addEventListener("input", validateLoginInputs);
    elements.loginIdentifier.addEventListener("change", validateLoginInputs);
  }
  if (elements.loginPassword) {
    elements.loginPassword.addEventListener("input", validateLoginInputs);
    elements.loginPassword.addEventListener("change", validateLoginInputs);
  }
  if (elements.logoutBtn) {
    elements.logoutBtn.addEventListener("click", () => void logout());
  }
  if (elements.walletBtn) {
    elements.walletBtn.addEventListener("click", () => void Promise.all([loadWallet(), loadWalletLedger()]));
  }
  if (elements.redeemBtn) {
    elements.redeemBtn.addEventListener("click", () => void redeemCode());
  }
  if (elements.exchangeBtn) {
    elements.exchangeBtn.addEventListener("click", () => void executeExchange());
  }
  if (elements.exchangeAmount) {
    elements.exchangeAmount.addEventListener("input", updateExchangePreview);
  }
  if (elements.exchangeFrom) {
    elements.exchangeFrom.addEventListener("change", updateExchangePreview);
  }
  if (elements.exchangeTo) {
    elements.exchangeTo.addEventListener("change", updateExchangePreview);
  }

  if (elements.productsBtn) {
    elements.productsBtn.addEventListener("click", () => void loadProducts());
  }
  if (elements.productSearchBtn) {
    elements.productSearchBtn.addEventListener("click", renderProductList);
  }
  if (elements.productApplyBtn) {
    elements.productApplyBtn.addEventListener("click", renderProductList);
  }
  if (elements.productKeywordClearBtn) {
    elements.productKeywordClearBtn.addEventListener("click", () => {
      setControlValue(elements.productKeyword, "");
      renderProductList();
    });
  }
  if (elements.productClearBtn) {
    elements.productClearBtn.addEventListener("click", () => {
      setControlValue(elements.productKeyword, "");
      setControlValue(elements.productSort, "default");
      setControlValue(elements.productFilterType, "");
      setControlValue(elements.productFilterCurrency, "");
      setControlValue(elements.productFilterMaterial, "");
      setControlValue(elements.productMinPrice, "");
      setControlValue(elements.productMaxPrice, "");
      renderProductList();
    });
  }

  if (elements.ordersBtn) {
    elements.ordersBtn.addEventListener("click", () => void loadOrders());
  }

  if (elements.marketRefreshBtn) {
    elements.marketRefreshBtn.addEventListener("click", () => void loadMarketListings("DIRECT"));
  }
  if (elements.marketApplyBtn) {
    elements.marketApplyBtn.addEventListener("click", () => void loadMarketListings("DIRECT"));
  }
  if (elements.marketClearBtn) {
    elements.marketClearBtn.addEventListener("click", () => {
      resetMarketFilters("DIRECT");
      void loadMarketListings("DIRECT");
    });
  }
  if (elements.auctionRefreshBtn) {
    elements.auctionRefreshBtn.addEventListener("click", () => void loadMarketListings("AUCTION"));
  }
  if (elements.auctionApplyBtn) {
    elements.auctionApplyBtn.addEventListener("click", () => void loadMarketListings("AUCTION"));
  }
  if (elements.auctionClearBtn) {
    elements.auctionClearBtn.addEventListener("click", () => {
      resetMarketFilters("AUCTION");
      void loadMarketListings("AUCTION");
    });
  }

  [
    [elements.marketKeyword, "DIRECT"],
    [elements.marketMaterial, "DIRECT"],
    [elements.marketMinPrice, "DIRECT"],
    [elements.marketMaxPrice, "DIRECT"],
    [elements.auctionKeyword, "AUCTION"],
    [elements.auctionMaterial, "AUCTION"],
    [elements.auctionMinPrice, "AUCTION"],
    [elements.auctionMaxPrice, "AUCTION"],
  ].forEach(([control, scope]) => {
    if (!control) {
      return;
    }
    control.addEventListener("keydown", (event) => {
      if (event.key !== "Enter") {
        return;
      }
      event.preventDefault();
      void loadMarketListings(scope);
    });
  });

  if (elements.marketModePublicBtn) {
    elements.marketModePublicBtn.addEventListener("click", () => {
      state.marketMode = "public";
      state.marketStore = { sellerKey: null, sellerName: null, sellerUuid: null };
      renderMarketModeButtons();
      void loadMarketListings("DIRECT");
    });
  }
  if (elements.marketModeStoresBtn) {
    elements.marketModeStoresBtn.addEventListener("click", () => {
      state.marketMode = "stores";
      state.marketStore = { sellerKey: null, sellerName: null, sellerUuid: null };
      renderMarketModeButtons();
      void loadMarketListings("DIRECT");
    });
  }
  if (elements.marketModeMineBtn) {
    elements.marketModeMineBtn.addEventListener("click", () => {
      state.marketMode = "mine";
      state.marketStore = { sellerKey: null, sellerName: null, sellerUuid: null };
      renderMarketModeButtons();
      void loadMarketListings("DIRECT");
    });
  }

  if (elements.hideOwnMarketListings) {
    elements.hideOwnMarketListings.addEventListener("change", () => {
      state.hideOwnMarketListings = controlChecked(elements.hideOwnMarketListings);
      try {
        window.localStorage.setItem(STORAGE_KEYS.hideOwn, state.hideOwnMarketListings ? "1" : "0");
      } catch (error) {
        appendLog(`保存市场过滤偏好失败: ${error.message}`, "warn");
      }
      if (state.marketMode === "public") {
        void loadMarketListings("DIRECT");
      }
    });
  }

  if (elements.marketEditCancelBtn) {
    elements.marketEditCancelBtn.addEventListener("click", closeMarketEditDialog);
  }
  if (elements.marketEditSaveBtn) {
    elements.marketEditSaveBtn.addEventListener("click", () => void saveMarketEdit());
  }

  if (elements.actionConfirmCancel) {
    elements.actionConfirmCancel.addEventListener("click", () => closeActionConfirm(false));
  }
  if (elements.actionConfirmOk) {
    elements.actionConfirmOk.addEventListener("click", () => closeActionConfirm(true));
  }
  if (elements.actionConfirmDialog) {
    ["close", "closed"].forEach((eventName) => {
      elements.actionConfirmDialog.addEventListener(eventName, () => {
        if (typeof actionConfirmResolver === "function") {
          closeActionConfirm(false);
        }
      });
    });
  }

  document.addEventListener("click", (event) => {
    const path = event.composedPath();
    const clickable = path.find((node) => node && node.dataset && (node.dataset.tabTarget || node.dataset.action));
    if (!clickable) {
      return;
    }

    if (clickable.dataset.tabTarget) {
      activateTab(clickable.dataset.tabTarget);
      return;
    }

    const action = clickable.dataset.action;
    if (!action) {
      return;
    }

    if (action === "buy-product") {
      void buyProduct(clickable.dataset.productId, controlValue(document.getElementById(clickable.dataset.qtyFieldId)));
      return;
    }

    if (action === "order-refund") {
      void refundOrder(clickable.dataset.orderNo);
      return;
    }

    if (action === "copy-text") {
      void copyText(clickable.dataset.text);
      return;
    }

    if (action === "market-buy") {
      void buyListing(clickable.dataset.listingId, controlValue(document.getElementById(clickable.dataset.qtyFieldId)));
      return;
    }

    if (action === "market-bid") {
      void bidListing(clickable.dataset.listingId, controlValue(document.getElementById(clickable.dataset.bidFieldId)));
      return;
    }

    if (action === "market-edit") {
      openMarketEditDialog(clickable.dataset.listingId);
      return;
    }

    if (action === "market-pause") {
      void runListingAction(
        "/api/market/pause",
        clickable.dataset.listingId,
        "已暂停上架",
        `确认暂停上架 #${clickable.dataset.listingId || "-"} 吗？`
      );
      return;
    }

    if (action === "market-resume") {
      void runListingAction(
        "/api/market/resume",
        clickable.dataset.listingId,
        "已恢复上架",
        `确认恢复上架 #${clickable.dataset.listingId || "-"} 吗？`
      );
      return;
    }

    if (action === "market-unlist") {
      void runListingAction(
        "/api/market/unlist",
        clickable.dataset.listingId,
        "已下架",
        `确认下架上架 #${clickable.dataset.listingId || "-"} 吗？`
      );
      return;
    }

    if (action === "market-supply-refresh") {
      void runListingAction("/api/market/supply/refresh", clickable.dataset.listingId, "补货刷新成功");
      return;
    }

    if (action === "market-open-store") {
      state.marketStore = {
        sellerKey: clickable.dataset.sellerKey || null,
        sellerName: clickable.dataset.sellerName || null,
        sellerUuid: clickable.dataset.sellerUuid || null,
      };
      renderMarketModeButtons();
      renderMarketListings("DIRECT");
      return;
    }

    if (action === "market-back-stores") {
      state.marketStore = { sellerKey: null, sellerName: null, sellerUuid: null };
      renderMarketModeButtons();
      renderMarketListings("DIRECT");
    }
  });
}

function restoreSessionAndPreferences() {
  try {
    const token = window.localStorage.getItem(STORAGE_KEYS.session);
    if (token) {
      state.token = token;
    }
  } catch (error) {
    appendLog(`读取会话失败: ${error.message}`, "warn");
  }

  try {
    const hideOwn = window.localStorage.getItem(STORAGE_KEYS.hideOwn);
    state.hideOwnMarketListings = hideOwn !== "0";
  } catch (error) {
    state.hideOwnMarketListings = true;
  }
}

async function bootstrap() {
  applyTheme(getInitialTheme());
  restoreSessionAndPreferences();

  renderMarketModeButtons();
  updateAuthView();
  bindCoreEvents();
  validateLoginInputs();
  setGlobalBusy(false);

  await Promise.all([
    ensureMaterialNameMap(),
    ensureMarketAlgorithmGlossary(),
    loadCurrencyMeta(),
  ]);

  const initialTab = pickActiveTabFromPath();
  activateTab(initialTab, false);

  if (state.token) {
    try {
      const walletPayload = await requestApi("/api/wallet", { method: "GET" });
      updateWalletView(walletPayload || {});
      state.username = String(walletPayload && walletPayload.username ? walletPayload.username : state.username || "").trim() || state.username;
      state.boundUuid = String(walletPayload && walletPayload.boundUuid ? walletPayload.boundUuid : state.boundUuid || "").trim() || state.boundUuid;
      updateAuthView();
      await Promise.all([
        loadWalletLedger(),
        loadProducts(),
        loadOrders(),
        loadOrdersPolicy(),
      ]);
      if (initialTab === "market") {
        await loadMarketListings("DIRECT");
      } else if (initialTab === "auction") {
        await loadMarketListings("AUCTION");
      }
    } catch (error) {
      appendLog(withErrorHint(error, "会话已失效，已回退到未登录状态"), "warn");
      clearSession();
    }
  } else {
    await loadProducts();
  }

  startRealtime();
}

window.addEventListener("beforeunload", () => {
  stopRealtime();
});

void bootstrap();
