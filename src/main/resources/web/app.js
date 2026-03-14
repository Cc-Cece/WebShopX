const state = {
  token: null,
  username: null,
  boundUuid: null,
  activeTab: "auth",
  authMode: "login",
  marketMode: "public",
  listings: [],
  products: [],
  zhNameMap: {},
  zhNameMapReady: false,
  zhNameMapPromise: null,
  hasLoadedProducts: false,
  hasLoadedMarket: false,
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
    invalid_username: "请先输入合法昵称（3-32 位字母/数字/下划线）。",
    username_exists: "该昵称已被注册或绑定，请直接登录或更换昵称。",
    bad_request: "注册参数不完整，请检查昵称后重试。",
  },
  register_finish: {
    invalid_code: "绑定码无效，请重新获取绑定码。",
    wait_bind: "尚未完成游戏内绑定，请先在游戏执行 /shop bind <code>。",
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
};

const elements = {
  logBox: document.getElementById("logBox"),
  statusChip: document.getElementById("statusChip"),

  authEntryCard: document.getElementById("authEntryCard"),
  authProfileCard: document.getElementById("authProfileCard"),
  authModeLoginBtn: document.getElementById("authModeLoginBtn"),
  authModeRegisterBtn: document.getElementById("authModeRegisterBtn"),
  authLoginPanel: document.getElementById("authLoginPanel"),
  authRegisterPanel: document.getElementById("authRegisterPanel"),

  loginIdentifier: document.getElementById("loginIdentifier"),
  loginPassword: document.getElementById("loginPassword"),
  loginBtn: document.getElementById("loginBtn"),

  registerUsername: document.getElementById("registerUsername"),
  registerStartBtn: document.getElementById("registerStartBtn"),
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
  marketView: document.getElementById("marketView"),
  shopCoinValue: document.getElementById("shopCoinValue"),
  gameCoinValue: document.getElementById("gameCoinValue"),
  productList: document.getElementById("productList"),
  marketList: document.getElementById("marketList"),
  snackbarHost: document.getElementById("snackbarHost"),
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

  if (tabName === "shop" && !state.hasLoadedProducts) {
    loadProducts();
  }
  if (tabName === "market" && !state.hasLoadedMarket) {
    loadMarket("public");
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

function toMaterialKeyFromEnglish(english) {
  const base = normalizeMaterialKey(english);
  if (!base) {
    return [];
  }

  const aliases = new Set([base]);
  if (base.startsWith("BLOCK_OF_")) {
    aliases.add(`${base.slice("BLOCK_OF_".length)}_BLOCK`);
  }
  if (base.startsWith("MINECART_WITH_")) {
    aliases.add(`${base.slice("MINECART_WITH_".length)}_MINECART`);
  }
  if (base.endsWith("_WITH_CHEST")) {
    aliases.add(`CHEST_${base.slice(0, -"_WITH_CHEST".length)}`);
  }
  if (base.includes("LAPIS_LAZULI")) {
    aliases.add(base.replaceAll("LAPIS_LAZULI", "LAPIS"));
  }

  return Array.from(aliases);
}

function parseZhNameTable(text) {
  const map = {};
  const lines = text.split(/\r?\n/);
  const skipHeaders = new Set([
    "Minecraft中英文对照表",
    "方块",
    "物品",
    "实体",
    "环境",
    "魔咒",
  ]);

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || skipHeaders.has(line)) {
      continue;
    }

    const match = line.match(/^(.+?)\s+([\u4e00-\u9fff].+)$/);
    if (!match) {
      continue;
    }

    const english = match[1].trim();
    const chinese = match[2].trim();
    if (!english || !chinese) {
      continue;
    }

    for (const key of toMaterialKeyFromEnglish(english)) {
      if (!map[key]) {
        map[key] = chinese;
      }
    }
  }

  return map;
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
      log(`中文词库已加载（JSON）：${Object.keys(state.zhNameMap).length} 条。`);
    })
    .catch((error) => {
      log(`${error.message}，尝试 TXT 回退。`, "WARN");
      return fetch("/material_zh.txt")
        .then((response) => {
          if (!response.ok) {
            throw new Error(`TXT 词库加载失败: ${response.status}`);
          }
          return response.text();
        })
        .then((text) => {
          state.zhNameMap = parseZhNameTable(text);
          state.zhNameMapReady = true;
          log(`中文词库已加载（TXT）：${Object.keys(state.zhNameMap).length} 条。`);
        });
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

  const avatarKey = state.boundUuid || state.username;
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

  stopRegistrationPolling();
  clearRegistrationUi(false);
  updateAuthLayout();
}

function clearSession() {
  state.token = null;
  state.username = null;
  state.boundUuid = null;
  updateAuthLayout();
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
  if (resetStatus) {
    elements.registerStatusView.textContent = "状态：等待开始注册";
  }
  setRegisterFlowTip(
    "info",
    "注册步骤",
    "在游戏内执行 /shop bind <绑定码> 后，页面会自动检测绑定结果并提示下一步。",
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
      "请进入游戏执行 /shop bind <绑定码>。完成后页面会自动切换到密码设置。",
      "状态：等待游戏内执行 /shop bind"
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
  const username = elements.registerUsername.value.trim();
  if (!username) {
    setRegisterFlowTip(
      "warn",
      "缺少昵称",
      "请先输入注册昵称，再点击“获取绑定码”。",
      "状态：缺少注册昵称"
    );
    throw new Error("请先输入注册昵称（3-32 位字母数字下划线）。");
  }

  const payload = await api("/api/auth/register/start", {
    method: "POST",
    body: JSON.stringify({ username }),
  });

  state.registration.bindCode = payload.bindCode;
  state.registration.username = payload.username;
  state.registration.status = "WAITING_BIND";

  elements.registerBindCodeView.textContent = `绑定码：${payload.bindCode}`;
  setRegisterFlowTip(
    "info",
    "绑定码已生成",
    `请在 ${payload.expiresInMinutes} 分钟内回到游戏执行 /shop bind <绑定码>。页面会自动检测绑定结果。`,
    `状态：绑定码已生成（${payload.expiresInMinutes} 分钟内有效），请回游戏执行 /shop bind`
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
  switchTab("wallet");
}

function renderProducts(products) {
  elements.productList.innerHTML = "";

  if (!products || products.length === 0) {
    const empty = createEl("div", "empty-state", "暂无商品，请联系管理员配置 sample-products。 ");
    elements.productList.appendChild(empty);
    return;
  }

  for (const product of products) {
    const card = createEl("article", "product-card");

    const top = createEl("div", "product-top");
    const titleWrap = createEl("div");
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

    const actions = createEl("div", "product-actions");
    const qty = document.createElement("input");
    qty.className = "product-qty";
    qty.type = "number";
    qty.min = "1";
    qty.step = "1";
    qty.value = "1";

    const button = createEl("button", "product-buy-btn", "立即下单");
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

    const button = createEl("button", "market-action-btn");
    button.type = "button";
    button.dataset.listingId = String(listing.id);

    if (isActive) {
      if (isOwner) {
        button.textContent = "下架并退回";
        button.dataset.action = "unlist";
        button.classList.add("unlist");
      } else {
        button.textContent = "立即购买";
        button.dataset.action = "buy";
      }
    } else {
      button.textContent = "不可操作";
      button.disabled = true;
    }

    footer.appendChild(button);
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

    const query = mode === "mine"
      ? "/api/market/listings?mine=true&limit=120"
      : "/api/market/listings?limit=120";
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
  const summary = `订单 ${payload.orderNo} | 总额 ${formatCurrency(payload.totalAmount, payload.currency)} | ${payload.state}`;
  setMetaText(elements.orderView, summary, isExisting ? "warn" : "success");
  if (isExisting) {
    log(`下单请求去重，返回历史订单：${summary}`, "WARN");
    notify(`订单已存在，已返回历史订单 ${payload.orderNo}。`, "warn");
  } else {
    log(`下单成功：${summary}`, "SUCCESS");
    notify(`下单成功：${summary}`, "success");
  }

  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(`订单创建后刷新钱包失败：${refreshMessage}`, "WARN");
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
  const amountText = formatCurrency(payload.totalPrice, payload.currency);
  if (isExisting) {
    log(`市场购买请求去重：tradeId=${payload.tradeId}，listingId=${payload.listingId}`, "WARN");
    notify(`该交易已处理过，返回历史结果（交易号 ${payload.tradeId}）。`, "warn");
  } else {
    log(`购买成功：tradeId=${payload.tradeId}，listingId=${payload.listingId}`, "SUCCESS");
    notify(`购买成功，成交金额 ${amountText}。`, "success");
  }

  try {
    await refreshWallet();
  } catch (refreshError) {
    const refreshMessage = resolveErrorMessage(refreshError, "wallet_refresh");
    log(`购买后刷新钱包失败：${refreshMessage}`, "WARN");
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
    log("已生成绑定码，请回游戏执行 /shop bind。", "SUCCESS");
    notify("绑定码已生成，请回游戏执行 /shop bind。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "register_start");
    const code = String(error && error.code ? error.code : "").toLowerCase();
    if (code === "username_exists") {
      setRegisterFlowTip(
        "warn",
        "昵称已被绑定",
        "该昵称已被注册或已完成绑定，请直接登录或更换昵称后重试。",
        "状态：该昵称已被绑定"
      );
    } else {
      setRegisterFlowTip("warn", "获取绑定码失败", message, "状态：获取绑定码失败");
    }
    log(`注册初始化失败：${message}`, "ERROR");
    notify(`注册初始化失败：${message}`, "error");
  }
});

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
    setMetaText(
      elements.exchangeView,
      `兑换成功：${formatCurrency(amount, fromCurrency)} -> ${toCurrency}`,
      "success"
    );
    log(`货币兑换成功：${formatCurrency(amount, fromCurrency)} -> ${toCurrency}`, "SUCCESS");
    notify(`兑换成功：${formatCurrency(amount, fromCurrency)} -> ${toCurrency}`, "success");
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
document.getElementById("marketListBtn").addEventListener("click", () => {
  loadMarket("public", { announce: true });
});
document.getElementById("marketMineBtn").addEventListener("click", () => {
  loadMarket("mine", { announce: true });
});
document.getElementById("marketRefreshBtn").addEventListener("click", () => {
  loadMarket(state.marketMode || "public", { announce: true });
});

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
  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "下单中...";
  try {
    await createOrder(button.dataset.productId, quantity);
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
      await buyListing(listingId);
      return;
    }
    if (button.dataset.action === "unlist") {
      await unlistListing(listingId);
    }
  } catch (error) {
    const scene = button.dataset.action === "unlist" ? "market_unlist" : "market_buy";
    const message = resolveErrorMessage(error, scene);
    log(`市场操作失败：${message}`, "ERROR");
    notify(`市场操作失败：${message}`, "error");
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
});

setAuthMode("login");
updateAuthLayout();
clearRegistrationUi();
setMetaText(elements.redeemView, "等待兑换操作", "info");
setMetaText(elements.exchangeView, "等待兑换操作", "info");
setMetaText(elements.orderView, "暂无订单", "info");
setMetaText(elements.marketView, "暂无市场数据", "info");
ensureZhNameMap();
loadProducts();
loadMarket("public");
log("前端已启动，默认加载官方商品和市场在售列表。");
