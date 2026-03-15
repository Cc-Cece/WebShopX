const state = {
  token: null,
  admin: null,
  activeTab: "login",
  selectedUser: null,
  currencyMeta: {
    SHOP_COIN: { name: "ShopCoin", short: "SC" },
    GAME_COIN: { name: "GameCoin", short: "GC" },
  },
};

const elements = {
  statusChip: document.getElementById("adminStatusChip"),
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
  redeemCreateResult: document.getElementById("redeemCreateResult"),
  redeemRefreshBtn: document.getElementById("redeemRefreshBtn"),
  redeemList: document.getElementById("redeemList"),

  productSku: document.getElementById("productSku"),
  productTitle: document.getElementById("productTitle"),
  productCurrency: document.getElementById("productCurrency"),
  productPrice: document.getElementById("productPrice"),
  productPublishAt: document.getElementById("productPublishAt"),
  productUnpublishAt: document.getElementById("productUnpublishAt"),
  productType: document.getElementById("productType"),
  productCommand: document.getElementById("productCommand"),
  productItemMaterial: document.getElementById("productItemMaterial"),
  productItemAmount: document.getElementById("productItemAmount"),
  productEffectType: document.getElementById("productEffectType"),
  productEffectSeconds: document.getElementById("productEffectSeconds"),
  productEffectAmplifier: document.getElementById("productEffectAmplifier"),
  productRemark: document.getElementById("productRemark"),
  productActive: document.getElementById("productActive"),
  productSaveBtn: document.getElementById("productSaveBtn"),
  productRefreshBtn: document.getElementById("productRefreshBtn"),
  productStatus: document.getElementById("productStatus"),
  productList: document.getElementById("productList"),
  groupBuyConsumeCode: document.getElementById("groupBuyConsumeCode"),
  groupBuyConsumeBtn: document.getElementById("groupBuyConsumeBtn"),
  groupBuyConsumeStatus: document.getElementById("groupBuyConsumeStatus"),

  orderStatus: document.getElementById("orderStatus"),
  orderUserId: document.getElementById("orderUserId"),
  orderNo: document.getElementById("orderNo"),
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

  marketStatus: document.getElementById("marketStatus"),
  marketRefreshBtn: document.getElementById("marketRefreshBtn"),
  marketStatusView: document.getElementById("marketStatusView"),
  adminMarketList: document.getElementById("adminMarketList"),

  userIdentifier: document.getElementById("userIdentifier"),
  userSearchBtn: document.getElementById("userSearchBtn"),
  userLookupStatus: document.getElementById("userLookupStatus"),
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

  auditRefreshBtn: document.getElementById("auditRefreshBtn"),
  auditList: document.getElementById("auditList"),

  snackbarHost: document.getElementById("snackbarHost"),
};

const tabs = Array.from(document.querySelectorAll(".top-tab"));
const panels = Array.from(document.querySelectorAll(".tab-panel"));

function notify(message, tone = "info", durationMs = 3200) {
  if (!elements.snackbarHost) {
    return;
  }
  const normalized = ["info", "success", "warn", "error"].includes(tone) ? tone : "info";
  const node = document.createElement("div");
  node.className = `snackbar snackbar-${normalized}`;
  node.textContent = message;
  elements.snackbarHost.appendChild(node);
  requestAnimationFrame(() => node.classList.add("show"));
  setTimeout(() => {
    node.classList.remove("show");
    setTimeout(() => node.remove(), 200);
  }, durationMs);
}

function setStatus(text, stateName) {
  elements.statusChip.textContent = text;
  elements.statusChip.dataset.state = stateName;
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

function switchTab(tabName) {
  state.activeTab = tabName;
  tabs.forEach((tab) => tab.classList.toggle("active", tab.dataset.tabTarget === tabName));
  panels.forEach((panel) => panel.classList.toggle("active", panel.dataset.tabPanel === tabName));
  if (tabName === "orders" && state.token) {
    loadAdminOrders();
  }
  if (tabName === "economy" && state.token) {
    loadEconomySettings();
  }
}

tabs.forEach((tab) => tab.addEventListener("click", () => switchTab(tab.dataset.tabTarget)));

async function apiAdmin(path, options = {}) {
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
    const payload = await fetch("/api/meta/currency", { method: "GET" }).then((res) => res.json());
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
    applyCurrencyMetaToUi();
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
  applyText("userCurrencyHint", `余额字段显示为：${shopName} / ${gameName}`);
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
  return `${meta.short} ${normalized.toLocaleString("zh-CN")}`;
}

function toLocalInput(value) {
  if (!value) {
    return "";
  }
  return String(value).slice(0, 16);
}

function renderAdminProfile() {
  if (!state.admin) {
    elements.adminProfileView.textContent = "未登录";
    return;
  }
  elements.adminProfileView.textContent = `账号：${state.admin.username} | 角色：${state.admin.role}`;
}

function setLoggedOut() {
  state.token = null;
  state.admin = null;
  sessionStorage.removeItem("webshop_admin_token");
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
  } catch (error) {
    setLoggedOut();
  }
}

function renderList(container, rows) {
  container.innerHTML = "";
  if (!rows || rows.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "暂无数据";
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
  h.textContent = title;
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
    label.textContent = item.label;
    const value = document.createElement("span");
    value.className = "admin-value";
    value.textContent = item.value;
    row.appendChild(label);
    row.appendChild(value);
    card.appendChild(row);
  });
  return card;
}

function localizeOrderStatusOptions() {
  if (!elements.orderStatus) {
    return;
  }
  const labels = {
    "": "全部",
    PENDING: "待发放",
    DELIVERED: "已发放",
    REFUNDED: "已退款",
    FAILED: "失败",
    RECYCLED: "已回收",
  };
  Array.from(elements.orderStatus.options).forEach((option) => {
    const value = String(option.value || "").trim().toUpperCase();
    if (Object.prototype.hasOwnProperty.call(labels, value)) {
      option.textContent = labels[value];
    }
  });
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
  setProductFieldVisible(elements.productCommand, commandVisible);
  setProductFieldVisible(elements.productItemMaterial, itemVisible);
  setProductFieldVisible(elements.productItemAmount, itemVisible);
  setProductFieldVisible(elements.productEffectType, effectVisible);
  setProductFieldVisible(elements.productEffectSeconds, effectVisible);
  setProductFieldVisible(elements.productEffectAmplifier, effectVisible);
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
    itemMaterial: elements.productItemMaterial.value.trim(),
    itemAmount: Number(elements.productItemAmount.value || 0),
    effectType: elements.productEffectType.value.trim(),
    effectSeconds: Number(elements.productEffectSeconds.value || 0),
    effectAmplifier: Number(elements.productEffectAmplifier.value || 0),
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

async function loadProducts() {
  ensureAdmin();
  const payload = await apiAdmin("/api/admin/products/list?includeInactive=true&limit=300", {
    method: "GET",
  });
  const rows = payload.products.map((product) => {
    const editBtn = document.createElement("button");
    editBtn.className = "btn-tonal";
    editBtn.textContent = "加载编辑";
    editBtn.addEventListener("click", () => {
      elements.productSku.value = product.sku;
      elements.productTitle.value = product.title;
      if (elements.productRemark) {
        elements.productRemark.value = product.remark || "";
      }
      elements.productCurrency.value = product.currency;
      elements.productPrice.value = product.price;
      elements.productPublishAt.value = toLocalInput(product.publishAt);
      elements.productUnpublishAt.value = toLocalInput(product.unpublishAt);
      elements.productType.value = product.productType;
      elements.productCommand.value = product.commandTemplate || "";
      elements.productItemMaterial.value = product.itemMaterial || "";
      elements.productItemAmount.value = product.itemAmount || 1;
      elements.productEffectType.value = product.effectType || "";
      elements.productEffectSeconds.value = product.effectSeconds || 30;
      elements.productEffectAmplifier.value = product.effectAmplifier || 0;
      elements.productActive.value = product.active ? "true" : "false";
      updateProductTypeFieldsVisibility(product.productType);
      setMetaText(elements.productStatus, `已加载 ${product.sku} 进入编辑`, "info");
    });
    const toggleBtn = document.createElement("button");
    toggleBtn.textContent = product.active ? "停用" : "启用";
    toggleBtn.addEventListener("click", async () => {
      await apiAdmin("/api/admin/products/active", {
        method: "POST",
        body: JSON.stringify({ productId: product.id, active: !product.active }),
      });
      notify(`商品 ${product.sku} 已${product.active ? "停用" : "启用"}`, "success");
      await loadProducts();
    });
    return renderKeyValueCard(
      `${product.title} (${product.sku})`,
      [
        { label: "ID", value: product.id },
        { label: "类型", value: product.productType },
        { label: "币种/价格", value: `${currencyName(product.currency)} / ${formatCurrency(product.price, product.currency)}` },
        { label: "备注", value: product.remark || "-" },
        { label: "上架时间", value: product.publishAt || "立即" },
        { label: "下架时间", value: product.unpublishAt || "不自动下架" },
        { label: "启用", value: product.active ? "是" : "否" },
      ],
      [editBtn, toggleBtn]
    );
  });
  renderList(elements.productList, rows);
}

async function loadAdminOrders() {
  ensureAdmin();
  const params = new URLSearchParams();
  const status = elements.orderStatus.value.trim();
  const userId = elements.orderUserId.value.trim();
  const orderNo = elements.orderNo.value.trim();
  if (status) {
    params.set("status", status);
  }
  if (userId) {
    params.set("userId", userId);
  }
  if (orderNo) {
    params.set("orderNo", orderNo);
  }
  params.set("limit", "200");
  const payload = await apiAdmin(`/api/admin/orders/list?${params.toString()}`, { method: "GET" });
  setMetaText(elements.orderStatusView, `已加载 ${payload.orders.length} 条订单`, "info");
  const rows = payload.orders.map((order) =>
    renderKeyValueCard(
      `订单 ${order.orderNo}`,
      [
        { label: "用户", value: `${order.username || "-"} (#${order.userId})` },
        { label: "UUID", value: order.boundUuid || order.mcUuid || "-" },
        { label: "状态", value: order.status },
        { label: "金额", value: formatCurrency(order.totalAmount, order.currency) },
        { label: "商品", value: `${order.productTitle || "-"} (${order.sku || "-"})` },
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

  setMetaText(elements.exchangeStatusView, "已加载兑换配置", "info");
  setMetaText(elements.marketEconomyStatusView, "已加载手续费/税率配置", "info");
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

async function loadMarket() {
  ensureAdmin();
  const status = elements.marketStatus.value.trim();
  const query = status ? `?status=${encodeURIComponent(status)}&limit=200` : "?limit=200";
  const payload = await apiAdmin(`/api/admin/market/listings${query}`, { method: "GET" });
  setMetaText(elements.marketStatusView, `已加载 ${payload.listings.length} 条`, "info");
  const rows = payload.listings.map((listing) => {
    const actions = [];
    if (listing.status === "ACTIVE") {
      const unlistBtn = document.createElement("button");
      unlistBtn.className = "btn-tonal";
      unlistBtn.textContent = "强制下架";
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
        { label: "物品", value: `${listing.itemMaterial} x${listing.quantity}` },
        { label: "备注", value: listing.remark || "-" },
        { label: "价格", value: formatCurrency(listing.price, listing.currency) },
        { label: "状态", value: listing.status },
      ],
      actions
    );
  });
  renderList(elements.adminMarketList, rows);
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
  state.selectedUser = payload;
  setMetaText(elements.userLookupStatus, `已查询：${payload.username}`, "success");
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

if (elements.groupBuyConsumeBtn) {
  elements.groupBuyConsumeBtn.addEventListener("click", async () => {
    try {
      await consumeGroupBuyVoucher();
    } catch (error) {
      setMetaText(elements.groupBuyConsumeStatus, `核销失败：${error.message}`, "error");
      notify(`核销失败：${error.message}`, "error");
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

elements.userSearchBtn.addEventListener("click", async () => {
  try {
    await lookupUser();
  } catch (error) {
    setMetaText(elements.userLookupStatus, `查询失败：${error.message}`, "error");
    notify(`查询失败：${error.message}`, "error");
  }
});

elements.userResetPasswordBtn.addEventListener("click", async () => {
  try {
    await resetPassword();
  } catch (error) {
    setMetaText(elements.userActionStatus, `重置失败：${error.message}`, "error");
    notify(`重置失败：${error.message}`, "error");
  }
});

elements.userUnbindBtn.addEventListener("click", async () => {
  try {
    await unbindUser();
  } catch (error) {
    setMetaText(elements.userActionStatus, `解绑失败：${error.message}`, "error");
    notify(`解绑失败：${error.message}`, "error");
  }
});

elements.userForceLogoutBtn.addEventListener("click", async () => {
  try {
    await forceLogoutUser();
  } catch (error) {
    setMetaText(elements.userActionStatus, `强制下线失败：${error.message}`, "error");
    notify(`强制下线失败：${error.message}`, "error");
  }
});

elements.walletAdjustBtn.addEventListener("click", async () => {
  try {
    await adjustWallet();
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

const savedToken = sessionStorage.getItem("webshop_admin_token");
if (savedToken) {
  state.token = savedToken;
  loadAdminProfile();
}

localizeOrderStatusOptions();
if (elements.productType) {
  elements.productType.addEventListener("change", () => {
    updateProductTypeFieldsVisibility(elements.productType.value);
  });
}
updateProductTypeFieldsVisibility(elements.productType ? elements.productType.value : "COMMAND");

applyCurrencyMetaToUi();
loadCurrencyMeta();
setMetaText(elements.adminLoginStatus, "等待登录", "info");
if (elements.groupBuyConsumeStatus) {
  setMetaText(elements.groupBuyConsumeStatus, "等待核销", "info");
}
renderAdminProfile();


