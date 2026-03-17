const state = {
  token: null,
  username: null,
  boundUuid: null,
  activeTab: "auth",
  marketMode: "public",
  marketStore: {
    sellerKey: null,
    sellerName: null,
    sellerUuid: null,
  },
  listings: [],
  products: [],
  orders: [],
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
  zhNameMap: {},
  zhNameMapReady: false,
  zhNameMapPromise: null,
  hasLoadedProducts: false,
  hasLoadedMarket: false,
  hasLoadedOrders: false,
  theme: "light",
  hideOwnMarketListings: true,
  realtime: {
    timer: null,
    busy: false,
    hasBootstrapped: false,
    orderDigest: {},
    listingDigest: {},
    wallet: null,
  },
  walletBalance: {
    shopCoin: 0,
    gameCoin: 0,
  },
};

const CURRENCY_META = {
  SHOP_COIN: {
    label: "网页币",
    short: "SC",
  },
  GAME_COIN: {
    label: "游戏币",
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

const PRODUCT_TYPE_TEXTURE_MAP = {
  COMMAND: "COMMAND_BLOCK",
  POTION_EFFECT: "POTION",
  GROUP_BUY_VOUCHER: "PAPER",
};

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

const ERROR_TIPS_COMMON = {
  auth_required: "请先登录后再操作。",
  auth_invalid: "登录状态已失效，请重新登录。",
  invalid_credentials: "账号或密码错误，请检查后重试。",
  not_bound: "当前账号未完成游戏内密码设置，请先在游戏内执行 /webshopx password <新密码>。",
  insufficient_funds: "余额不足，请先充值或兑换后再试。",
  internal_error: "服务器内部错误，请稍后重试。",
  method_not_allowed: "请求方式错误，请刷新页面后重试。",
  wallet_missing: "钱包不存在，请联系管理员检查数据。",
  user_missing: "账号数据不存在，请联系管理员处理。",
};

const ERROR_TIPS_BY_SCENE = {
  login: {
    invalid_identifier: "请输入用户名。",
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
    invalid_trade: "不能购买自己上架的物品。",
    invalid_idempotency: "请求参数异常，请刷新后重试。",
    invalid_quantity: "购买数量需在 1-64 之间。",
    insufficient_quantity: "当前上架可购买数量不足，请刷新后重试。",
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
  WAIT_CLAIM: { label: "待领取", tone: "pending" },
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
  authLoginPanel: document.getElementById("authLoginPanel"),

  loginIdentifier: document.getElementById("loginIdentifier"),
  loginPassword: document.getElementById("loginPassword"),
  loginBtn: document.getElementById("loginBtn"),

  profileAvatar: document.getElementById("profileAvatar"),
  profileName: document.getElementById("profileName"),
  profileUuid: document.getElementById("profileUuid"),
  logoutBtn: document.getElementById("logoutBtn"),

  walletView: document.getElementById("walletView"),
  walletLedgerView: document.getElementById("walletLedgerView"),
  walletLedgerList: document.getElementById("walletLedgerList"),
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
  marketStoreBtn: document.getElementById("marketStoreBtn"),
  marketHideOwnToggle: document.getElementById("marketHideOwnToggle"),
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
const MARKET_HIDE_OWN_STORAGE_KEY = "webshopx_market_hide_own";

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
let confirmSubmitHandler = null;

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
  confirmSubmitHandler = null;
  details.filter(Boolean).forEach((line) => {
    elements.confirmDetails.appendChild(createEl("div", "", line));
  });
  elements.confirmOkBtn.disabled = false;
  elements.confirmOkBtn.textContent = confirmText;
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

  elements.confirmTitle.textContent = title;
  elements.confirmMessage.textContent = message;
  elements.confirmDetails.innerHTML = "";
  confirmSubmitHandler = null;
  details.filter(Boolean).forEach((line) => {
    elements.confirmDetails.appendChild(createEl("div", "", line));
  });

  const field = createEl("label", "field dialog-select-field");
  field.appendChild(createEl("span", "", "领取方式"));
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
  field.appendChild(select);
  elements.confirmDetails.appendChild(field);

  if (summary) {
    const summaryCard = createEl("div", "checkout-summary");
    summaryCard.appendChild(createEl("p", "checkout-kicker", "结算摘要"));
    const rows = [];
    rows.push(["小计", formatCurrency(summary.subtotal, summary.currency)]);
    if (Number(summary.taxAmount || 0) > 0) {
      rows.push([summary.taxLabel || "税额（买家承担）", formatCurrency(summary.taxAmount, summary.currency)]);
    }
    rows.push(["最终扣款", formatCurrency(summary.finalAmount, summary.currency), "negative"]);
    const hasCurrentBalance = Number.isFinite(summary.currentBalance);
    const hasRemainingBalance = Number.isFinite(summary.remainingBalance);
    const isInsufficient = hasRemainingBalance && summary.remainingBalance < 0;
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

    const note = createEl(
      "p",
      isInsufficient ? "checkout-warning negative" : "checkout-warning",
      isInsufficient
        ? `余额不足，还差 ${formatCurrency(Math.abs(summary.remainingBalance), summary.currency)}。`
        : "确认后将按照以上金额结算。"
    );
    elements.confirmDetails.appendChild(note);
    elements.confirmOkBtn.disabled = isInsufficient;
  } else {
    elements.confirmOkBtn.disabled = false;
  }

  confirmSubmitHandler = () => closeConfirmDialog(select.value);
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
  confirmSubmitHandler = null;
  if (elements.confirmOkBtn) {
    elements.confirmOkBtn.disabled = false;
  }
  if (confirmResolver) {
    confirmResolver(result);
    confirmResolver = null;
  }
}

function openListingEditDialog({
  listingId,
  currentPrice,
  currency,
  currentRemark,
  sourceMode,
  currentSupplyBatchSize,
  currentSupplyMaxStock,
}) {
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
    const isSupplyFallback = String(sourceMode || "").toUpperCase() === "SUPPLY";
    const rawBatch = isSupplyFallback
      ? window.prompt("请输入单次提取量", String(currentSupplyBatchSize || 1))
      : null;
    if (isSupplyFallback && rawBatch === null) {
      return Promise.resolve(null);
    }
    const rawMax = isSupplyFallback
      ? window.prompt("请输入中转上限", String(currentSupplyMaxStock || 1))
      : null;
    if (isSupplyFallback && rawMax === null) {
      return Promise.resolve(null);
    }
    return Promise.resolve({
      price: Math.floor(Number(rawPrice)),
      currency: String(rawCurrency || "GAME_COIN").trim().toUpperCase(),
      remark: rawRemark.trim() || null,
      supplyBatchSize: isSupplyFallback ? Math.floor(Number(rawBatch)) : null,
      supplyMaxStock: isSupplyFallback ? Math.floor(Number(rawMax)) : null,
    });
  }
  if (confirmResolver) {
    confirmResolver(null);
    confirmResolver = null;
  }

  elements.confirmTitle.textContent = `修改上架 #${listingId}`;
  elements.confirmMessage.textContent = "可同时修改价格和备注。";
  elements.confirmDetails.innerHTML = "";

  const priceField = createEl("label", "field dialog-select-field");
  priceField.appendChild(createEl("span", "", "新价格"));
  const priceInput = document.createElement("input");
  priceInput.type = "number";
  priceInput.min = "1";
  priceInput.step = "1";
  priceInput.value = String(currentPrice || "");
  priceField.appendChild(priceInput);
  elements.confirmDetails.appendChild(priceField);

  const currencyField = createEl("label", "field dialog-select-field");
  currencyField.appendChild(createEl("span", "", "币种"));
  const currencySelect = document.createElement("select");
  ["SHOP_COIN", "GAME_COIN"].forEach((value) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = (CURRENCY_META[value] || { label: value }).label;
    currencySelect.appendChild(option);
  });
  currencySelect.value = currency || "GAME_COIN";
  currencyField.appendChild(currencySelect);
  elements.confirmDetails.appendChild(currencyField);

  const remarkField = createEl("label", "field dialog-select-field");
  remarkField.appendChild(createEl("span", "", "备注"));
  const remarkInput = document.createElement("textarea");
  remarkInput.rows = 3;
  remarkInput.value = currentRemark || "";
  remarkField.appendChild(remarkInput);
  elements.confirmDetails.appendChild(remarkField);

  const isSupply = String(sourceMode || "").toUpperCase() === "SUPPLY";
  let supplyBatchInput = null;
  let supplyMaxInput = null;
  if (isSupply) {
    const batchField = createEl("label", "field dialog-select-field");
    batchField.appendChild(createEl("span", "", "单次提取量"));
    supplyBatchInput = document.createElement("input");
    supplyBatchInput.type = "number";
    supplyBatchInput.min = "1";
    supplyBatchInput.step = "1";
    supplyBatchInput.value = String(currentSupplyBatchSize || "");
    batchField.appendChild(supplyBatchInput);
    elements.confirmDetails.appendChild(batchField);

    const maxField = createEl("label", "field dialog-select-field");
    maxField.appendChild(createEl("span", "", "中转上限"));
    supplyMaxInput = document.createElement("input");
    supplyMaxInput.type = "number";
    supplyMaxInput.min = "1";
    supplyMaxInput.step = "1";
    supplyMaxInput.value = String(currentSupplyMaxStock || "");
    maxField.appendChild(supplyMaxInput);
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
    closeConfirmDialog({
      price: Math.floor(price),
      currency: currencySelect.value,
      remark: remarkInput.value.trim() || null,
      supplyBatchSize: supplyBatchInput ? Math.floor(batchSize) : null,
      supplyMaxStock: supplyMaxInput ? Math.floor(maxStock) : null,
    });
  };
  elements.confirmOkBtn.disabled = false;
  elements.confirmOkBtn.textContent = "保存修改";
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
      refreshWallet().then(() => loadWalletLedger()).catch((error) => {
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
  return {
    totalStock,
    remainingStock,
    hasTrackedStock,
    maxQuantity: hasTrackedStock
      ? Math.max(0, Math.floor(remainingStock))
      : Math.max(1, Math.floor(Number(product?.itemAmount || 64))),
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
  if (key === "COMMAND") return "指令";
  if (key === "GIVE_ITEM") return "出售物品";
  if (key === "POTION_EFFECT") return "药水效果";
  if (key === "RECYCLE_ITEM") return "回收物品";
  if (key === "GROUP_BUY_VOUCHER") return "团购券";
  return key || "未知类型";
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

function humanizeLedgerType(bizType, bizId) {
  const normalized = String(bizType || "").toUpperCase();
  if (normalized === "ORDER_DEBIT") return `购买商品 ${bizId || ""}`.trim();
  if (normalized === "ORDER_REFUND") return `订单退款 ${bizId || ""}`.trim();
  if (normalized === "RECYCLE_CREDIT") return `回收入账 ${bizId || ""}`.trim();
  if (normalized === "EXCHANGE_OUT") return "货币兑换转出";
  if (normalized === "EXCHANGE_IN") return "货币兑换转入";
  if (normalized === "MARKET_BUY") return "市场购买";
  if (normalized === "MARKET_SELL") return "市场售出";
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
  return materialKey
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function getLocalizedMaterialName(material) {
  const key = normalizeMaterialKey(material);
  const aliasKey = aliasMaterialKey(key);
  if (!key) {
    return "未知物品";
  }
  return state.zhNameMap[key] || state.zhNameMap[aliasKey] || humanizeMaterial(aliasKey || key);
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

function getTextureCandidates(material) {
  const names = buildTextureAliases(material);
  if (names.length === 0) {
    return [getFallbackTexture()];
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

  candidates.push(getFallbackTexture());
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

function formatListingStatus(status) {
  const normalized = String(status || "").toUpperCase();
  switch (normalized) {
    case "ACTIVE":
      return "在售";
    case "SUPPLY_EMPTY":
      return "待补货";
    case "PAUSED":
      return "已停用";
    case "UNLISTED":
      return "已退回";
    case "SOLD":
      return "已售";
    default:
      return normalized || "--";
  }
}

function formatDateTime(isoText) {
  const timestamp = Date.parse(isoText);
  if (Number.isNaN(timestamp)) {
    return "未知时间";
  }
  return new Date(timestamp).toLocaleString("zh-CN", { hour12: false });
}

function formatSupplyLoadedAt(isoText) {
  if (!isoText) {
    return "未补货";
  }
  return formatDateTime(isoText);
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

  const sync = (source) => {
    const rawValue = Number(source.value || 1);
    const clamped = Math.min(normalizedMax, Math.max(1, Math.floor(Number.isFinite(rawValue) ? rawValue : 1)));
    numberInput.value = String(clamped);
    rangeInput.value = String(clamped);
    total.textContent = `总价：${formatCurrency(unitPrice * clamped, currency)}`;
  };

  numberInput.addEventListener("input", () => sync(numberInput));
  rangeInput.addEventListener("input", () => sync(rangeInput));
  sync(numberInput);

  return {
    wrap,
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
    label.textContent = textBuilder(normalizedCurrent, normalizedTotal);
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
  if (elements.authLoginPanel) {
    elements.authLoginPanel.classList.add("active");
  }
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
    elements.profileAvatar.src = getFallbackAvatar();
    return;
  }

  elements.profileAvatar.src = `${AVATAR_BASE}${encodeURIComponent(avatarKey)}`;
  elements.profileAvatar.onerror = () => {
    elements.profileAvatar.onerror = null;
    elements.profileAvatar.src = getFallbackAvatar();
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

  updateAuthLayout();
  state.hasLoadedOrders = false;
  startRealtimeSync();
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
  renderWalletLedger([]);
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
    const [walletPayload, ordersPayload, listingsPayload] = await Promise.all([
      api("/api/wallet", { method: "GET" }),
      api("/api/orders/list?limit=30", { method: "GET" }),
      api("/api/market/listings?mine=true&limit=80", { method: "GET" }),
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
    }

    state.realtime.wallet = {
      shopCoin: Number(walletPayload.shopCoin || 0),
      gameCoin: Number(walletPayload.gameCoin || 0),
    };
    state.realtime.orderDigest = buildOrderDigest(ordersPayload.orders || []);
    state.realtime.listingDigest = buildListingDigest(listingsPayload.listings || []);
    state.realtime.hasBootstrapped = true;

    if (state.activeTab === "wallet") {
      updateWalletView(walletPayload);
    }
    if (state.activeTab === "orders") {
      state.orders = ordersPayload.orders || [];
      renderOrders(state.orders);
    }
    if (state.activeTab === "market" && state.marketMode === "mine") {
      state.listings = listingsPayload.listings || [];
      renderListings(state.listings);
      setMetaText(elements.marketView, `我的上架：${state.listings.length} 条`, "info");
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
  state.walletBalance.shopCoin = Number(payload.shopCoin || 0);
  state.walletBalance.gameCoin = Number(payload.gameCoin || 0);
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

function renderProducts(products) {
  elements.productList.innerHTML = "";

  if (!products || products.length === 0) {
    const empty = createEl("div", "empty-state", "暂无商品，请联系管理员在后台添加。 ");
    elements.productList.appendChild(empty);
    return;
  }

  for (const product of products) {
    const card = createEl("article", "product-card market-card official-card");
    const isGroupBuyVoucher = String(product.productType || "").toUpperCase() === "GROUP_BUY_VOUCHER";
    const stock = resolveOfficialProductStock(product);
    const isSoldOut = stock.hasTrackedStock && stock.maxQuantity <= 0;
    const top = createEl("div", "market-top");
    top.appendChild(createEl("span", "market-chip official", productTypeLabel(product.productType)));
    top.appendChild(createEl("span", "market-time", product.unpublishAt ? `下架：${formatDateTime(product.unpublishAt)}` : "长期供应"));
    card.appendChild(top);

    const main = createEl("div", "market-main");
    const icon = createEl("div", "market-icon");
    icon.appendChild(buildTextureImage(resolveProductTextureMaterial(product), product.title));
    main.appendChild(icon);

    const detail = createEl("div");
    detail.appendChild(createEl("h3", "market-title", product.title));
    detail.appendChild(createEl("p", "market-sub", `币种 ${(CURRENCY_META[product.currency] || { label: product.currency }).label}`));
    if (product.itemMaterial) {
      detail.appendChild(createEl("p", "market-sub", `物品：${getLocalizedMaterialName(product.itemMaterial)}`));
    }
    if (product.remark) {
      detail.appendChild(createEl("p", "market-remark", product.remark));
    }
    main.appendChild(detail);
    card.appendChild(main);

    const stockProgress = stock.hasTrackedStock
      ? createProgressIndicator(
          stock.remainingStock,
          stock.totalStock,
          (current, total) => `剩余 x${current} / 总量 x${total}`
        )
      : createProgressIndicator(1, 1, () => "库存：长期供应");
    card.appendChild(stockProgress.wrap);

    const priceRow = createEl("div", "market-price-row");
    priceRow.appendChild(createEl("p", "market-price-label", "单价"));
    priceRow.appendChild(createEl("p", "market-price", formatCurrency(product.price, product.currency)));
    card.appendChild(priceRow);

    const footer = createEl("div", "market-footer");
    if (isGroupBuyVoucher) {
      footer.appendChild(createEl("p", "market-sub", "购买后生成团购兑换码，需由管理员核销"));
    }

    const actions = createEl("div", "market-actions-row");
    if (isSoldOut) {
      const soldOutBtn = createEl("button", "market-action-btn", "已售罄");
      soldOutBtn.type = "button";
      soldOutBtn.disabled = true;
      actions.appendChild(soldOutBtn);
    } else {
      const quantitySelector = createQuantitySelector({
        max: stock.maxQuantity,
        unitPrice: Number(product.price || 0),
        currency: product.currency,
        totalClassName: "market-total",
      });

      const buyBtn = createEl("button", "market-action-btn product-buy-btn", "立即购买");
      buyBtn.type = "button";
      buyBtn.dataset.action = "buy-product";
      buyBtn.dataset.productId = String(product.id);
      buyBtn.dataset.maxQuantity = String(stock.maxQuantity);
      const buyWrap = createEl("div", "market-buy-wrap");
      buyWrap.appendChild(quantitySelector.wrap);
      buyWrap.appendChild(buyBtn);
      actions.appendChild(buyWrap);
    }
    footer.appendChild(actions);

    card.appendChild(footer);
    elements.productList.appendChild(card);
  }
}

function renderListings(listings, container = elements.marketList) {
  container.innerHTML = "";
  const visibleListings = container === elements.marketList
    && state.marketMode === "public"
    && state.hideOwnMarketListings
    && state.username
    ? (listings || []).filter((listing) => listing.sellerName !== state.username)
    : (listings || []);

  if (!visibleListings || visibleListings.length === 0) {
    const empty = createEl("div", "empty-state", "当前没有可显示的市场上架。 ");
    container.appendChild(empty);
    return;
  }

  for (const listing of visibleListings) {
    const meta = parseMeta(listing.itemMetaJson);
    const isOwner = !!state.username && listing.sellerName === state.username;
    const normalizedStatus = String(listing.status || "").toUpperCase();
    const isSupply = String(listing.sourceMode || "").toUpperCase() === "SUPPLY";
    const displayStatus = isSupply && Number(listing.quantity || 0) <= 0 && normalizedStatus === "ACTIVE"
      ? "SUPPLY_EMPTY"
      : normalizedStatus;
    const isActive = normalizedStatus === "ACTIVE" && Number(listing.quantity || 0) > 0;
    const isPaused = normalizedStatus === "PAUSED";

    const displayName = stripColorCodes(meta.displayName || "");
    const localizedName = displayName || getLocalizedMaterialName(listing.itemMaterial);

    const card = createEl("article", "market-card");

    const top = createEl("div", "market-top");
    const statusClass = displayStatus === "ACTIVE" ? "sale" : displayStatus === "SUPPLY_EMPTY" || isPaused ? "paused" : "inactive";
    top.appendChild(createEl(
      "span",
      `market-chip ${statusClass}`,
      formatListingStatus(displayStatus)
    ));
    if (isSupply) {
      top.appendChild(createEl("span", "market-chip official", "自动补货"));
    }
    top.appendChild(createEl("span", "market-time", formatAge(listing.createdAt)));
    card.appendChild(top);

    const main = createEl("div", "market-main");
    const icon = createEl("div", "market-icon");
    icon.appendChild(buildTextureImage(listing.itemMaterial, localizedName));
    main.appendChild(icon);

    const detail = createEl("div");
    detail.appendChild(createEl("h3", "market-title", localizedName));
    detail.appendChild(createEl("p", "market-code", String(listing.itemMaterial)));
    const quantityTotal = Number(listing.quantityTotal || listing.quantity || 0);
    if (listing.remark) {
      detail.appendChild(createEl("p", "market-remark", listing.remark));
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
    main.appendChild(detail);
    card.appendChild(main);

    const stockProgress = createProgressIndicator(
      Number(listing.quantity || 0),
      quantityTotal > 0 ? quantityTotal : Math.max(1, Number(listing.quantity || 0)),
      (current, total) => `剩余 x${current} / 总量 x${total}`
    );
    card.appendChild(stockProgress.wrap);

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
      actions.appendChild(editBtn);

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
      const quantitySelector = createQuantitySelector({
        max: listing.quantity || 1,
        unitPrice: Number(listing.price || 0),
        currency: listing.currency,
        totalClassName: "market-total",
      });

      const buyBtn = createEl("button", "market-action-btn", "立即购买");
      buyBtn.type = "button";
      buyBtn.dataset.action = "buy";
      buyBtn.dataset.listingId = String(listing.id);
      buyBtn.dataset.currency = listing.currency;
      buyBtn.dataset.unitPrice = String(listing.price);
      buyBtn.dataset.maxQuantity = String(Math.max(1, Number(listing.quantity || 1)));
      const buyWrap = createEl("div", "market-buy-wrap");
      buyWrap.appendChild(quantitySelector.wrap);
      buyWrap.appendChild(buyBtn);
      actions.appendChild(buyWrap);
    } else {
      const disabledBtn = createEl("button", "market-action-btn", "不可购买");
      disabledBtn.disabled = true;
      actions.appendChild(disabledBtn);
    }
    if (isSupply && state.token && Number(listing.quantity || 0) <= 0 && normalizedStatus !== "UNLISTED" && normalizedStatus !== "SOLD") {
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
  elements.marketList.innerHTML = "";
  const activeListings = (listings || []).filter((listing) => listing.status === "ACTIVE");
  if (activeListings.length === 0) {
    elements.marketList.appendChild(createEl("div", "empty-state", "当前没有可显示的玩家店铺。"));
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
    .sort((a, b) => a[1].sellerName.localeCompare(b[1].sellerName, "zh-CN"))
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

      const button = createEl("button", "market-action-btn", "进入店铺");
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
  const sellerListings = (listings || []).filter((listing) => createStoreKey(listing) === key);
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

    state.marketMode = normalizedMode;
    state.listings = payload.listings || [];
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
        setMetaText(elements.marketView, `玩家店铺：${storeCount} 家`, "info");
      }
    } else {
      renderListings(state.listings);
      const label = normalizedMode === "mine" ? "我的上架" : "市场在售";
      const visibleCount = normalizedMode === "public" && state.hideOwnMarketListings && state.username
        ? state.listings.filter((listing) => listing.sellerName !== state.username).length
        : state.listings.length;
      setMetaText(elements.marketView, `${label}：${visibleCount} 条`, "info");
    }

    const label = normalizedMode === "mine"
      ? "我的上架"
      : normalizedMode === "stores"
        ? "玩家店铺"
        : "市场在售";
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
    setMetaText(elements.orderView, `加载订单失败：${message}`, "error");
    log(`加载订单失败：${message}`, "ERROR");
    if (announce) {
      notify(`加载订单失败：${message}`, "error");
    }
  }
}

async function confirmPurchase(product, quantity) {
  const qty = Number(quantity || 1);
  const subtotalAmount = Number(product.price || 0) * qty;
  const total = formatCurrency(subtotalAmount, product.currency);
  const cooldown = Number(state.orderPolicy.cooldownSeconds || 0);
  const allowClaim = String(product.productType || "").toUpperCase() !== "GROUP_BUY_VOUCHER";
  const currentBalance = getWalletBalanceForCurrency(product.currency);
  const details = [
    `商品：${product.title}`,
    `SKU：${product.sku}`,
    `数量：x${qty}`,
  ];
  if (product.remark) {
    details.push(`备注：${product.remark}`);
  }
  if (String(product.productType || "").toUpperCase() === "GROUP_BUY_VOUCHER") {
    details.push("该商品会生成团购兑换码，需由管理员在后台核销。");
  }
  if (state.orderPolicy.refundUndeliveredEnabled) {
    details.push("退款说明：未发放前可退款。");
  } else if (cooldown > 0) {
    details.push(`冷静期：${cooldown} 秒（仅冷静期内可退款）`);
  } else {
    details.push("退款说明：当前不支持未发放退款。");
  }
  return openDeliveryConfirmDialog({
    title: "确认下单",
    message: "请确认以下订单信息，确认后将立即扣除余额。",
    details,
    confirmText: "确认下单",
    initialValue: defaultDeliveryModeForProduct(product),
    allowClaim,
    summary: {
      currency: product.currency,
      subtotal: subtotalAmount,
      taxAmount: 0,
      feeAmount: 0,
      taxLabel: "税额（买家承担）",
      feeLabel: "手续费（卖家承担）",
      finalAmount: subtotalAmount,
      currentBalance,
      remainingBalance: currentBalance - subtotalAmount,
    },
  });
}

async function confirmMarketBuy(listing, buyQuantity) {
  await ensureZhNameMap();
  const meta = parseMeta(listing.itemMetaJson);
  const displayName = stripColorCodes(meta.displayName || "");
  const localizedName = displayName || getLocalizedMaterialName(listing.itemMaterial);
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

async function createOrder(productId, quantity, deliveryMode, productTitle) {
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
    log(`下单请求去重，返回历史订单：${summary}`, "WARN");
    notify(`订单已存在：${payload.orderNo}，${itemText}，总额 ${formatCurrency(payload.totalAmount, payload.currency)}。`, "warn");
  } else {
    log(`下单成功：${summary}`, "SUCCESS");
    const claimTip = orderStatus === "WAIT_CLAIM" ? "，请在游戏内使用 /ws claim 领取。" : "";
    notify(`下单成功：${summary}${claimTip}`, "success");
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

async function updateListing(listingId, price, currency, remark, supplyBatchSize, supplyMaxStock) {
  ensureToken();
  const payload = await api("/api/market/settings", {
    method: "POST",
    body: JSON.stringify({
      listingId,
      price,
      currency,
      remark,
      supplyBatchSize,
      supplyMaxStock,
    }),
  });
  log(`修改成功：listingId=${listingId} price=${payload.price} currency=${payload.currency}`, "SUCCESS");
  notify("修改成功：价格、币种与补充设置已更新。", "success");
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
    switchTab("wallet");
    log("登录成功。", "SUCCESS");
    notify("登录成功。", "success");
  } catch (error) {
    const message = resolveErrorMessage(error, "login");
    log(`登录失败：${message}`, "ERROR");
    notify(`登录失败：${message}`, "error");
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
    await loadWalletLedger();
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
    const toMeta = CURRENCY_META[toCurrency] || { label: toCurrency };
    const deltaText = summarizeWalletDelta(payload, previousWallet);
    const balanceText = formatWalletInline(payload.shopCoin, payload.gameCoin);
    const successText = `兑换成功：${formatCurrency(amount, fromCurrency)} -> ${toMeta.label}${deltaText ? `，余额变动 ${deltaText}` : ""}。当前余额：${balanceText}。`;
    setMetaText(
      elements.exchangeView,
      successText,
      "success"
    );
    log(successText, "SUCCESS");
    notify(successText, "success");
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
    notify("已取消下单。", "info");
    return;
  }

  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "下单中...";
  try {
    await createOrder(productId, qtyValue, deliveryMode, product.title);
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
      const confirmed = listing ? await confirmMarketBuy(listing, buyQty) : await openConfirmDialog({
        title: "确认购买",
        message: "确认后将立即扣除余额。",
        details: [`上架ID：${listingId}`, `数量：x${buyQty}`],
        confirmText: "确认购买",
      });
      if (!confirmed) {
        notify("已取消购买。", "info");
        return;
      }
      const selectedMode = typeof confirmed === "string" ? confirmed : "IMMEDIATE";
      await buyListing(listingId, buyQty, selectedMode);
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
      });
      if (!result) {
        notify("已取消修改。", "info");
        return;
      }
      await updateListing(
        listingId,
        result.price,
        result.currency,
        result.remark,
        result.supplyBatchSize,
        result.supplyMaxStock
      );
    }
  } catch (error) {
    const scene = button.dataset.action === "unlist"
      ? "market_unlist"
      : button.dataset.action === "refreshSupply"
        ? "market_supply"
      : button.dataset.action === "edit"
        ? "market_price"
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
state.hideOwnMarketListings = getInitialHideOwnMarketListings();
if (elements.marketHideOwnToggle) {
  elements.marketHideOwnToggle.checked = state.hideOwnMarketListings;
  elements.marketHideOwnToggle.addEventListener("change", () => {
    state.hideOwnMarketListings = !!elements.marketHideOwnToggle.checked;
    window.localStorage.setItem(MARKET_HIDE_OWN_STORAGE_KEY, state.hideOwnMarketListings ? "1" : "0");
    if (state.marketMode === "public") {
      renderListings(state.listings);
      const visibleCount = state.hideOwnMarketListings && state.username
        ? state.listings.filter((listing) => listing.sellerName !== state.username).length
        : state.listings.length;
      setMetaText(elements.marketView, `市场在售：${visibleCount} 条`, "info");
    }
  });
}
setAuthMode("login");
updateAuthLayout();
setMetaText(elements.walletView, "等待刷新余额", "info");
setMetaText(elements.walletLedgerView, "等待加载记录", "info");
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
