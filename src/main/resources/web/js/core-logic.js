export const DEFAULT_CURRENCY_META = Object.freeze({
  SHOP_COIN: Object.freeze({ label: "Web Coin", short: "SC" }),
  GAME_COIN: Object.freeze({ label: "Game Coin", short: "GC" }),
});

export const DEFAULT_REMOTE_TEXTURE_BASES = Object.freeze([
  "https://mcasset.cloud/1.21/assets/minecraft/textures",
  "https://mcasset.cloud/1.20.6/assets/minecraft/textures",
]);

export const MATERIAL_TEXTURE_OVERRIDES = Object.freeze({
  MOSS_CARPET: Object.freeze(["moss_carpet"]),
  GRASS: Object.freeze(["short_grass", "grass"]),
  TALL_GRASS: Object.freeze(["tall_grass"]),
});

export const PRODUCT_TYPE_TEXTURE_MAP = Object.freeze({
  COMMAND: "COMMAND_BLOCK",
  POTION_EFFECT: "SPLASH_POTION",
  GROUP_BUY_VOUCHER: "PAPER",
});

export const DEFAULT_TEXTURE_FALLBACK_MATERIAL = "BUNDLE";

export const FALLBACK_MARKET_ALGORITHM_GLOSSARY = Object.freeze({
  dynamic: Object.freeze([
    Object.freeze({ id: "LINEAR_DEMAND_V1", label: "LINEAR_DEMAND_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "DIMINISHING_RETURN_V1", label: "DIMINISHING_RETURN_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "LOG_SMOOTH_V1", label: "LOG_SMOOTH_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "EXPONENTIAL_DEFENSE_V1", label: "EXPONENTIAL_DEFENSE_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "THRESHOLD_STEP_V1", label: "THRESHOLD_STEP_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "ELASTICITY_V1", label: "ELASTICITY_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "PANIC_BUYING_V1", label: "PANIC_BUYING_V1", params: Object.freeze([]) }),
  ]),
  auction: Object.freeze([
    Object.freeze({ id: "ENGLISH_AUCTION_V1", label: "ENGLISH_AUCTION_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "DUTCH_AUCTION_V1", label: "DUTCH_AUCTION_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "VICKREY_AUCTION_V1", label: "VICKREY_AUCTION_V1", params: Object.freeze([]) }),
    Object.freeze({ id: "CANDLE_AUCTION_V1", label: "CANDLE_AUCTION_V1", params: Object.freeze([]) }),
  ]),
});

export const PARAM_KEY_ALIAS_MAP = Object.freeze({
  threshold: Object.freeze(["thresholdK", "panicThreshold"]),
  eta: Object.freeze(["elasticity"]),
});

function hasOwn(obj, key) {
  return Object.prototype.hasOwnProperty.call(obj, key);
}

function safeNumber(value, fallback = 0) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : fallback;
}

export function normalizeApiBaseUrl(value) {
  let normalized = String(value || "").trim();
  while (normalized.endsWith("/")) {
    normalized = normalized.slice(0, -1);
  }
  return normalized;
}

export function resolveApiUrl(path, apiBaseUrl = "") {
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
  const normalizedBase = normalizeApiBaseUrl(apiBaseUrl);
  return normalizedBase ? `${normalizedBase}${text}` : text;
}

export function createIdempotencyKey() {
  const cryptoApi = typeof globalThis !== "undefined" ? globalThis.crypto : null;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function formatAmount(amount, locale = "zh-CN", maxFractionDigits = 2) {
  const value = Number(amount);
  if (!Number.isFinite(value)) {
    return "0";
  }
  return new Intl.NumberFormat(locale, { maximumFractionDigits: maxFractionDigits }).format(value);
}

export function formatCurrency(
  amount,
  currency,
  currencyMeta = DEFAULT_CURRENCY_META,
  locale = "zh-CN",
  maxFractionDigits = 2
) {
  const code = String(currency || "").trim().toUpperCase();
  const meta = currencyMeta[code] || { short: code || "--" };
  return `${meta.short} ${formatAmount(amount, locale, maxFractionDigits)}`;
}

export function formatRatioValue(value, locale = "zh-CN") {
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || numeric < 0) {
    return "0";
  }
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 6 }).format(numeric);
}

export function calculatePercentAmount(baseAmount, percent) {
  const normalizedBase = Math.max(0, safeNumber(baseAmount, 0));
  const normalizedPercent = Math.max(0, Math.min(100, safeNumber(percent, 0)));
  const raw = normalizedBase * normalizedPercent / 100;
  if (!Number.isFinite(raw) || raw <= 0) {
    return 0;
  }
  return Math.min(Math.floor(raw), normalizedBase);
}

export function normalizeExchangeDirection(rawDirection, fallbackDirection = { enabled: false, ratio: 1.0 }) {
  const source = rawDirection && typeof rawDirection === "object" ? rawDirection : {};
  const fallback = fallbackDirection && typeof fallbackDirection === "object"
    ? fallbackDirection
    : { enabled: false, ratio: 1.0 };

  const enabled = hasOwn(source, "enabled") ? Boolean(source.enabled) : Boolean(fallback.enabled);
  const ratioRaw = Number(source.ratio);
  const ratio = Number.isFinite(ratioRaw) && ratioRaw >= 0
    ? ratioRaw
    : safeNumber(fallback.ratio, 0);

  return {
    enabled,
    ratio: Number.isFinite(ratio) ? ratio : 0,
  };
}

export function resolveExchangeDirectionSettings(fromCurrency, toCurrency, exchangeSettings) {
  const from = String(fromCurrency || "").trim().toUpperCase();
  const to = String(toCurrency || "").trim().toUpperCase();
  const settings = exchangeSettings && typeof exchangeSettings === "object"
    ? exchangeSettings
    : {};

  if (from === "SHOP_COIN" && to === "GAME_COIN") {
    return settings.shopToGame || null;
  }
  if (from === "GAME_COIN" && to === "SHOP_COIN") {
    return settings.gameToShop || null;
  }
  return null;
}

export function calculateExchangePreview({
  fromCurrency,
  toCurrency,
  amount,
  exchangeSettings,
  walletBalance = {},
}) {
  const numericAmount = Number(amount);
  if (!Number.isFinite(numericAmount) || numericAmount <= 0) {
    return { ok: false, reason: "invalid_amount" };
  }

  if (String(fromCurrency || "").toUpperCase() === String(toCurrency || "").toUpperCase()) {
    return { ok: false, reason: "same_currency" };
  }

  const direction = resolveExchangeDirectionSettings(fromCurrency, toCurrency, exchangeSettings);
  if (!direction) {
    return { ok: false, reason: "invalid_direction" };
  }

  const normalizedDirection = normalizeExchangeDirection(direction);
  if (!normalizedDirection.enabled) {
    return { ok: false, reason: "direction_disabled" };
  }

  const convertedAmount = Math.floor(numericAmount * normalizedDirection.ratio);
  if (!Number.isFinite(convertedAmount) || convertedAmount <= 0) {
    return { ok: false, reason: "invalid_ratio" };
  }

  const from = String(fromCurrency || "").toUpperCase();
  const to = String(toCurrency || "").toUpperCase();
  const fromBalance = safeNumber(walletBalance[from === "GAME_COIN" ? "gameCoin" : "shopCoin"], 0);
  const toBalance = safeNumber(walletBalance[to === "GAME_COIN" ? "gameCoin" : "shopCoin"], 0);
  const fromRemaining = fromBalance - numericAmount;
  const toRemaining = toBalance + convertedAmount;

  return {
    ok: true,
    ratio: normalizedDirection.ratio,
    fromBalance,
    toBalance,
    fromRemaining,
    toRemaining,
    convertedAmount,
    insufficientFunds: fromRemaining < 0,
  };
}

export function parseMeta(raw) {
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

export function stripColorCodes(text) {
  return String(text || "").replace(/\u00a7[0-9A-FK-OR]/gi, "");
}

export function humanizeEnum(value) {
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

export function normalizeMaterialKey(text) {
  return String(text || "")
    .toUpperCase()
    .replace(/^MINECRAFT:/, "")
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

export function aliasMaterialKey(text) {
  const key = normalizeMaterialKey(text);
  if (!key) {
    return "";
  }
  if (key.startsWith("BLOCK_OF_") && key.length > "BLOCK_OF_".length) {
    return `${key.slice("BLOCK_OF_".length)}_BLOCK`;
  }
  return key;
}

export function humanizeMaterial(materialKey) {
  return humanizeEnum(materialKey);
}

export function getLocalizedMaterialName(material, materialNameMap = {}, locale = "zh-CN") {
  const key = normalizeMaterialKey(material);
  const aliasKey = aliasMaterialKey(key);
  if (!key) {
    return locale.toLowerCase().startsWith("zh") ? "UNKNOWN_ITEM" : "Unknown Item";
  }
  return materialNameMap[key] || materialNameMap[aliasKey] || humanizeMaterial(aliasKey || key);
}

export function resolveProductTextureMaterial(
  product,
  productTypeMap = PRODUCT_TYPE_TEXTURE_MAP,
  fallbackMaterial = DEFAULT_TEXTURE_FALLBACK_MATERIAL
) {
  const itemMaterial = String(product && product.itemMaterial ? product.itemMaterial : "")
    .trim()
    .toUpperCase();
  if (itemMaterial) {
    return itemMaterial;
  }
  const productType = String(product && product.productType ? product.productType : "")
    .trim()
    .toUpperCase();
  return productTypeMap[productType] || fallbackMaterial;
}

export function buildSvgDataUrl(svg) {
  return `data:image/svg+xml;utf8,${encodeURIComponent(String(svg || ""))}`;
}

export function buildFallbackTextureSvg({
  background = "rgb(243 243 250)",
  panel = "rgb(231 232 238)",
  text = "rgb(68 71 78)",
} = {}) {
  return [
    "<svg xmlns='http://www.w3.org/2000/svg' width='96' height='96'>",
    `<rect width='96' height='96' fill='${background}'/>`,
    `<rect x='10' y='10' width='76' height='76' fill='${panel}'/>`,
    `<text x='48' y='57' text-anchor='middle' font-size='36' fill='${text}'>?</text>`,
    "</svg>",
  ].join("");
}

export function buildTextureAliases(material, overrides = MATERIAL_TEXTURE_OVERRIDES) {
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

  const override = overrides[key];
  if (Array.isArray(override)) {
    override.forEach((item) => aliases.add(String(item).toLowerCase()));
  }

  return Array.from(aliases);
}

export function getFallbackTextureCandidates({
  localTextureBase = "/textures",
  remoteTextureBases = DEFAULT_REMOTE_TEXTURE_BASES,
  fallbackMaterial = DEFAULT_TEXTURE_FALLBACK_MATERIAL,
  fallbackDataUrl,
} = {}) {
  const names = buildTextureAliases(fallbackMaterial);
  const candidates = [];

  names.forEach((textureName) => {
    candidates.push(`${localTextureBase}/item/${textureName}.png`);
    candidates.push(`${localTextureBase}/block/${textureName}.png`);
  });

  const bases = Array.isArray(remoteTextureBases) ? remoteTextureBases : [];
  bases.forEach((base) => {
    names.forEach((textureName) => {
      candidates.push(`${base}/item/${textureName}.png`);
      candidates.push(`${base}/block/${textureName}.png`);
    });
  });

  if (fallbackDataUrl) {
    candidates.push(fallbackDataUrl);
  }

  return candidates;
}

export function getTextureCandidates(material, options = {}) {
  const {
    localTextureBase = "/textures",
    remoteTextureBases = DEFAULT_REMOTE_TEXTURE_BASES,
    fallbackMaterial = DEFAULT_TEXTURE_FALLBACK_MATERIAL,
    fallbackDataUrl,
  } = options;

  const names = buildTextureAliases(material);
  if (names.length === 0) {
    return getFallbackTextureCandidates({
      localTextureBase,
      remoteTextureBases,
      fallbackMaterial,
      fallbackDataUrl,
    });
  }

  const candidates = [];

  names.forEach((textureName) => {
    candidates.push(`${localTextureBase}/item/${textureName}.png`);
    candidates.push(`${localTextureBase}/block/${textureName}.png`);
  });

  const bases = Array.isArray(remoteTextureBases) ? remoteTextureBases : [];
  bases.forEach((base) => {
    names.forEach((textureName) => {
      candidates.push(`${base}/item/${textureName}.png`);
      candidates.push(`${base}/block/${textureName}.png`);
    });
  });

  const fallbackCandidates = getFallbackTextureCandidates({
    localTextureBase,
    remoteTextureBases,
    fallbackMaterial,
    fallbackDataUrl,
  });

  fallbackCandidates.forEach((candidate) => {
    if (!candidates.includes(candidate)) {
      candidates.push(candidate);
    }
  });

  return candidates;
}

export function hasExplicitTimeZone(value) {
  return /(?:Z|[+\-]\d{2}:\d{2})$/i.test(String(value || "").trim());
}

export function parseLocalDateTimeParts(value) {
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

export function getTimeZoneOffsetMinutes(timestamp, timeZone) {
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

export function parseDateTimeValue(value, timeZone = "Asia/Shanghai") {
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

  const initialOffset = getTimeZoneOffsetMinutes(utcGuess, timeZone);
  let timestamp = utcGuess - initialOffset * 60000;
  const resolvedOffset = getTimeZoneOffsetMinutes(timestamp, timeZone);
  if (resolvedOffset !== initialOffset) {
    timestamp = utcGuess - resolvedOffset * 60000;
  }
  return timestamp;
}

export function collectDateTimeParts(timestamp, {
  locale = "en-CA",
  timeZone = "Asia/Shanghai",
  options = {},
} = {}) {
  const formatter = new Intl.DateTimeFormat(locale, {
    timeZone,
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

export function formatDateTime(value, {
  locale = "zh-CN",
  timeZone = "Asia/Shanghai",
} = {}) {
  const timestamp = parseDateTimeValue(value, timeZone);
  if (Number.isNaN(timestamp)) {
    return "UNKNOWN_TIME";
  }

  return new Intl.DateTimeFormat(locale, {
    timeZone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(timestamp));
}

export function toDateTimeLocalValue(value, {
  timeZone = "Asia/Shanghai",
} = {}) {
  const timestamp = parseDateTimeValue(value, timeZone);
  if (Number.isNaN(timestamp)) {
    return "";
  }

  const parts = collectDateTimeParts(timestamp, {
    locale: "en-CA",
    timeZone,
    options: {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    },
  });

  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

export function resolveOfficialProductStock(product) {
  const totalStock = Number(product && product.itemAmount);
  const remainingStock = Number(product && product.stockRemaining);
  const hasTrackedStock = Number.isFinite(totalStock) && Number.isFinite(remainingStock);

  const stockMaxQuantity = hasTrackedStock
    ? Math.max(0, Math.floor(remainingStock))
    : Math.max(1, Math.floor(Number(product && product.itemAmount ? product.itemAmount : 64)));

  const perUserLimitRaw = Number(product && product.perUserLimit);
  const hasPerUserLimit = Number.isFinite(perUserLimitRaw) && perUserLimitRaw > 0;
  const perUserLimit = hasPerUserLimit ? Math.floor(perUserLimitRaw) : null;

  const personalRemainingRaw = Number(product && product.personalLimitRemaining);
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

export function buildDigest(items, {
  keyField,
  fields,
} = {}) {
  const keyName = String(keyField || "");
  const digestFields = Array.isArray(fields) ? fields : [];
  const digest = {};

  (items || []).forEach((item) => {
    const key = String(item && keyName ? item[keyName] : "");
    if (!key) {
      return;
    }

    digest[key] = digestFields
      .map((field) => String(item && field ? item[field] : ""))
      .join("|");
  });

  return digest;
}

export function buildOrderDigest(orders) {
  return buildDigest(orders, {
    keyField: "orderNo",
    fields: ["status", "deliveredAt", "refundedAt", "groupBuyVoucherStatus"],
  });
}

export function buildListingDigest(listings) {
  return buildDigest(listings, {
    keyField: "id",
    fields: ["status", "quantity", "buyerName", "soldAt", "unlistedAt"],
  });
}

export function buildAdminOrderDigest(orders) {
  return buildDigest(orders, {
    keyField: "orderNo",
    fields: ["status", "refundedAt", "deliveredAt", "groupBuyVoucherStatus"],
  });
}

export function buildAdminMarketDigest(listings) {
  return buildDigest(listings, {
    keyField: "id",
    fields: ["status", "quantity", "buyerName", "soldAt", "unlistedAt"],
  });
}

export function normalizeAlgorithmParamSchema(raw, index) {
  const key = String(raw && raw.key ? raw.key : `param_${index}`).trim();
  if (!key) {
    return null;
  }

  const type = String(raw && raw.type ? raw.type : "number").trim().toLowerCase();
  const tier = String(raw && (raw.tier || raw.group) ? (raw.tier || raw.group) : "")
    .trim()
    .toLowerCase();

  return {
    key,
    label: String(raw && raw.label ? raw.label : key),
    type: type === "text" ? "text" : "number",
    advanced: Boolean(raw && raw.advanced) || tier === "advanced",
    required: Boolean(raw && raw.required),
    min: Number.isFinite(Number(raw && raw.min)) ? Number(raw.min) : null,
    max: Number.isFinite(Number(raw && raw.max)) ? Number(raw.max) : null,
    step: Number.isFinite(Number(raw && raw.step)) ? Number(raw.step) : null,
    defaultValue: raw ? raw.default : undefined,
    description: String(raw && raw.description ? raw.description : "").trim(),
  };
}

export function normalizeAlgorithmDefinition(raw, index) {
  const id = String(raw && raw.id ? raw.id : "").trim().toUpperCase();
  if (!id) {
    return null;
  }

  const params = Array.isArray(raw && raw.params)
    ? raw.params
      .map((param, paramIndex) => normalizeAlgorithmParamSchema(param, paramIndex))
      .filter(Boolean)
    : [];

  return {
    id,
    label: String(raw && raw.label ? raw.label : id),
    summary: String(raw && raw.summary ? raw.summary : "").trim(),
    helpSlug: String(raw && raw.helpSlug ? raw.helpSlug : id.toLowerCase()),
    requiresMinIncrement: Boolean(raw && raw.requiresMinIncrement),
    requiresEndAt: Boolean(raw && raw.requiresEndAt),
    params,
    sortOrder: Number.isFinite(Number(raw && raw.sortOrder)) ? Number(raw.sortOrder) : index,
  };
}

export function normalizeMarketAlgorithmGlossary(raw, fallbackGlossary = FALLBACK_MARKET_ALGORITHM_GLOSSARY) {
  const dynamic = Array.isArray(raw && raw.dynamic)
    ? raw.dynamic.map((item, index) => normalizeAlgorithmDefinition(item, index)).filter(Boolean)
    : [];
  const auction = Array.isArray(raw && raw.auction)
    ? raw.auction.map((item, index) => normalizeAlgorithmDefinition(item, index)).filter(Boolean)
    : [];

  const normalized = {
    dynamic: dynamic.length > 0 ? dynamic : [...(fallbackGlossary.dynamic || [])],
    auction: auction.length > 0 ? auction : [...(fallbackGlossary.auction || [])],
  };

  normalized.dynamic = normalized.dynamic
    .slice()
    .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0));
  normalized.auction = normalized.auction
    .slice()
    .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0));

  return normalized;
}

export function getAlgorithmCatalog(glossary, type) {
  const key = type === "auction" ? "auction" : "dynamic";
  const catalog = glossary && typeof glossary === "object" ? glossary[key] : null;
  return Array.isArray(catalog) ? catalog : [];
}

export function getAlgorithmDefinition(glossary, type, algorithmId) {
  const normalizedId = String(algorithmId || "").trim().toUpperCase();
  if (!normalizedId) {
    return null;
  }
  const catalog = getAlgorithmCatalog(glossary, type);
  return catalog.find((item) => String(item && item.id ? item.id : "").toUpperCase() === normalizedId) || null;
}

export function parseAlgorithmParamsJson(raw) {
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

export function parseAlgorithmParamsJsonStrict(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return {};
  }

  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error("Algorithm param JSON is invalid");
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("Algorithm param JSON must be an object");
  }

  return parsed;
}

export function resolveAlgorithmParamInitialValue(
  paramValues,
  schemaKey,
  aliasMap = PARAM_KEY_ALIAS_MAP
) {
  if (!paramValues || typeof paramValues !== "object") {
    return undefined;
  }
  if (hasOwn(paramValues, schemaKey)) {
    return paramValues[schemaKey];
  }

  const aliases = aliasMap[schemaKey];
  if (!Array.isArray(aliases)) {
    return undefined;
  }

  for (const alias of aliases) {
    if (hasOwn(paramValues, alias)) {
      return paramValues[alias];
    }
  }

  return undefined;
}

export function collectAlgorithmParamValues(entries) {
  const payload = {};

  for (const entry of entries || []) {
    const schema = entry && entry.schema ? entry.schema : {};
    const rawValue = String(entry && entry.value !== undefined && entry.value !== null ? entry.value : "").trim();
    const fallbackValue = schema.defaultValue === undefined || schema.defaultValue === null
      ? ""
      : String(schema.defaultValue).trim();
    const effectiveValue = rawValue || fallbackValue;

    if (!effectiveValue) {
      if (schema.required) {
        return undefined;
      }
      continue;
    }

    if (schema.type === "number") {
      const numericValue = Number(effectiveValue);
      if (!Number.isFinite(numericValue)) {
        return undefined;
      }
      if (schema.min !== null && schema.min !== undefined && numericValue < schema.min) {
        return undefined;
      }
      if (schema.max !== null && schema.max !== undefined && numericValue > schema.max) {
        return undefined;
      }
      payload[schema.key] = Number.isInteger(numericValue)
        ? Math.trunc(numericValue)
        : numericValue;
    } else {
      payload[schema.key] = effectiveValue;
    }
  }

  return payload;
}

export function stripKnownDynamicParamKeys(paramPayload, dynamicCatalog, aliasMap = PARAM_KEY_ALIAS_MAP) {
  const payload = paramPayload && typeof paramPayload === "object"
    ? { ...paramPayload }
    : {};

  const blockedKeys = new Set();
  (dynamicCatalog || []).forEach((definition) => {
    (definition && Array.isArray(definition.params) ? definition.params : []).forEach((schema) => {
      blockedKeys.add(schema.key);
      const aliases = aliasMap[schema.key];
      if (Array.isArray(aliases)) {
        aliases.forEach((alias) => blockedKeys.add(alias));
      }
    });
  });

  Object.keys(payload).forEach((key) => {
    if (blockedKeys.has(key)) {
      delete payload[key];
    }
  });

  return payload;
}

export function mergeDynamicParamPayload(rawJsonPayload, editorParamPayload, dynamicCatalog, aliasMap = PARAM_KEY_ALIAS_MAP) {
  const basePayload = rawJsonPayload && typeof rawJsonPayload === "object"
    ? rawJsonPayload
    : {};
  const editorPayload = editorParamPayload && typeof editorParamPayload === "object"
    ? editorParamPayload
    : {};

  const merged = stripKnownDynamicParamKeys(basePayload, dynamicCatalog, aliasMap);
  Object.entries(editorPayload).forEach(([key, value]) => {
    merged[key] = value;
  });

  return merged;
}

export function parseOptionalPositiveWhole(raw) {
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

export function validateQuantity(quantity, min = 1, max = 64) {
  const value = Number(quantity);
  if (!Number.isFinite(value)) {
    return false;
  }
  return value >= min && value <= max;
}

export function validatePrice(price) {
  const value = Number(price);
  return Number.isFinite(value) && value > 0;
}

export function validatePasswordLength(password, min = 8, max = 64) {
  const length = String(password || "").length;
  return length >= min && length <= max;
}

export function parseDocFromFile(fileName) {
  const normalized = String(fileName || "").trim();
  const noExt = normalized.replace(/\.md$/i, "");
  const match = noExt.match(/^(.*)\.([A-Za-z]{2}(?:-[A-Za-z]{2})?)$/);

  if (!match) {
    return {
      id: noExt,
      key: noExt,
      locale: "",
      file: normalized,
    };
  }

  return {
    id: noExt,
    key: match[1],
    locale: match[2],
    file: normalized,
  };
}

export function normalizeDocEntry(raw) {
  const parsed = parseDocFromFile(raw && (raw.file || raw.path || raw.id) ? (raw.file || raw.path || raw.id) : "");

  const file = String(raw && raw.file ? raw.file : parsed.file).trim();
  const id = String(raw && raw.id ? raw.id : parsed.id).trim();

  return {
    id,
    key: String(raw && raw.key ? raw.key : parsed.key).trim(),
    locale: String(raw && raw.locale ? raw.locale : parsed.locale).trim(),
    title: String(raw && raw.title ? raw.title : "").trim() || id,
    file,
    path: `docs/${file}`,
  };
}

export function sanitizeHashId(raw) {
  let decoded = "";
  try {
    decoded = decodeURIComponent(String(raw || "").replace(/^#/, "")).trim();
  } catch (error) {
    return "";
  }

  if (!decoded || decoded.length > 120) {
    return "";
  }

  if (!/^[A-Za-z0-9._\-\u4e00-\u9fa5]+$/.test(decoded)) {
    return "";
  }

  return decoded;
}

export function createHeadingId(text, usedIds = new Set(), explicitId = "") {
  let base = String(explicitId || text || "").trim();
  if (!base) {
    base = "section";
  }

  base = base
    .replace(/\s+/g, "-")
    .replace(/[!"#$%&'()*+,./:;<=>?@[\\\]^`{|}~\uFF0C\u3002\uFF01\uFF1F\uFF1B\uFF1A\u201C\u201D\u2018\u2019\u3001]/g, "")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "");

  if (!base) {
    base = "section";
  }

  if (!usedIds.has(base)) {
    usedIds.add(base);
    return base;
  }

  let suffix = 2;
  while (usedIds.has(`${base}-${suffix}`)) {
    suffix += 1;
  }
  const id = `${base}-${suffix}`;
  usedIds.add(id);
  return id;
}

export function normalizeLocale(raw, supportedLocales = ["zh-CN", "en-US"], fallback = "zh-CN") {
  const value = String(raw || "").trim().replace(/_/g, "-");
  if (!value) {
    return fallback;
  }

  if (value.toLowerCase() === "zh" || value.toLowerCase().startsWith("zh-")) {
    return "zh-CN";
  }

  if (value.toLowerCase() === "en" || value.toLowerCase().startsWith("en-")) {
    return "en-US";
  }

  return supportedLocales.includes(value) ? value : fallback;
}

export function isChineseLocale(locale) {
  return normalizeLocale(locale) === "zh-CN";
}

export function shouldLoadMaterialMap(locale) {
  return isChineseLocale(locale);
}

export function localizeWithPrefix(text, prefixLabels = {}) {
  const input = String(text || "");
  const match = /^([^\uFF1A:]+)[\uFF1A:](.*)$/.exec(input);
  if (!match) {
    return input;
  }

  const translated = prefixLabels[match[1]];
  if (!translated) {
    return input;
  }

  return `${translated}: ${match[2]}`;
}

export function localizeWithPatterns(text, patternRules = []) {
  const input = String(text || "");
  for (const rule of patternRules) {
    if (!rule || !(rule.pattern instanceof RegExp)) {
      continue;
    }

    rule.pattern.lastIndex = 0;
    if (!rule.pattern.test(input)) {
      continue;
    }

    rule.pattern.lastIndex = 0;
    return input.replace(rule.pattern, rule.replace);
  }
  return input;
}

export function createTextLocalizer({
  locale,
  exactMap = {},
  patternRules = [],
  prefixLabels = {},
} = {}) {
  const currentLocale = normalizeLocale(locale);

  return function localizeText(value) {
    if (value === null || value === undefined) {
      return value;
    }

    const text = String(value);
    if (!text || isChineseLocale(currentLocale)) {
      return text;
    }

    if (hasOwn(exactMap, text)) {
      return exactMap[text];
    }

    const patterned = localizeWithPatterns(text, patternRules);
    if (patterned !== text) {
      return patterned;
    }

    const prefixed = localizeWithPrefix(text, prefixLabels);
    if (prefixed !== text) {
      return prefixed;
    }

    return text;
  };
}

export default {
  DEFAULT_CURRENCY_META,
  DEFAULT_REMOTE_TEXTURE_BASES,
  MATERIAL_TEXTURE_OVERRIDES,
  PRODUCT_TYPE_TEXTURE_MAP,
  DEFAULT_TEXTURE_FALLBACK_MATERIAL,
  FALLBACK_MARKET_ALGORITHM_GLOSSARY,
  PARAM_KEY_ALIAS_MAP,

  normalizeApiBaseUrl,
  resolveApiUrl,
  createIdempotencyKey,

  formatAmount,
  formatCurrency,
  formatRatioValue,
  calculatePercentAmount,
  normalizeExchangeDirection,
  resolveExchangeDirectionSettings,
  calculateExchangePreview,

  parseMeta,
  stripColorCodes,
  humanizeEnum,
  normalizeMaterialKey,
  aliasMaterialKey,
  humanizeMaterial,
  getLocalizedMaterialName,
  resolveProductTextureMaterial,

  buildSvgDataUrl,
  buildFallbackTextureSvg,
  buildTextureAliases,
  getFallbackTextureCandidates,
  getTextureCandidates,

  hasExplicitTimeZone,
  parseLocalDateTimeParts,
  getTimeZoneOffsetMinutes,
  parseDateTimeValue,
  collectDateTimeParts,
  formatDateTime,
  toDateTimeLocalValue,

  resolveOfficialProductStock,
  buildDigest,
  buildOrderDigest,
  buildListingDigest,
  buildAdminOrderDigest,
  buildAdminMarketDigest,

  normalizeAlgorithmParamSchema,
  normalizeAlgorithmDefinition,
  normalizeMarketAlgorithmGlossary,
  getAlgorithmCatalog,
  getAlgorithmDefinition,
  parseAlgorithmParamsJson,
  parseAlgorithmParamsJsonStrict,
  resolveAlgorithmParamInitialValue,
  collectAlgorithmParamValues,
  stripKnownDynamicParamKeys,
  mergeDynamicParamPayload,

  parseOptionalPositiveWhole,
  validateQuantity,
  validatePrice,
  validatePasswordLength,

  parseDocFromFile,
  normalizeDocEntry,
  sanitizeHashId,
  createHeadingId,

  normalizeLocale,
  isChineseLocale,
  shouldLoadMaterialMap,
  localizeWithPrefix,
  localizeWithPatterns,
  createTextLocalizer,
};
