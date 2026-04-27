const state = {
  token: null,
  admin: null,
  activeMajor: "overview",
  activeTab: "login",
  majorLastTab: {},
  productPanel: "editor",
  selectedProductId: null,
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
  materialOverrideRows: [],
  selectedMaterialOverrideKey: "",
  materialAllowSet: new Set(),
  materialAllowReady: false,
  materialAllowPromise: null,
  marketTags: [],
  marketTagsReady: false,
  marketTagsPromise: null,
  marketTagConfig: null,
  marketLimitationConfig: null,
  marketTagEditingIndex: null,
  marketLimitationRuleEditingIndex: null,
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
  POTION_EFFECT: "SPLASH_POTION",
  GROUP_BUY_VOUCHER: "PAPER",
};
const DEFAULT_TEXTURE_FALLBACK_MATERIAL = "BUNDLE";

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
  productDisplayMaterial: document.getElementById("productDisplayMaterial"),
  productDisplayNameOverride: document.getElementById("productDisplayNameOverride"),
  productDisplayIconPath: document.getElementById("productDisplayIconPath"),
  productIconPreviewImage: document.getElementById("productIconPreviewImage"),
  productIconPreviewLabel: document.getElementById("productIconPreviewLabel"),
  productIconFile: document.getElementById("productIconFile"),
  productIconUploadBtn: document.getElementById("productIconUploadBtn"),
  productIconClearBtn: document.getElementById("productIconClearBtn"),
  productIconStatus: document.getElementById("productIconStatus"),
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
  currencyShopCoinName: document.getElementById("currencyShopCoinName"),
  currencyShopCoinShort: document.getElementById("currencyShopCoinShort"),
  currencyGameCoinName: document.getElementById("currencyGameCoinName"),
  currencyGameCoinShort: document.getElementById("currencyGameCoinShort"),
  currencySaveBtn: document.getElementById("currencySaveBtn"),
  currencyStatusView: document.getElementById("currencyStatusView"),
  runtimeDefaultLocale: document.getElementById("runtimeDefaultLocale"),
  runtimeTimeZone: document.getElementById("runtimeTimeZone"),
  runtimeSessionExpireHours: document.getElementById("runtimeSessionExpireHours"),
  runtimeBindRequestExpireMinutes: document.getElementById("runtimeBindRequestExpireMinutes"),
  runtimeAccessTokenLength: document.getElementById("runtimeAccessTokenLength"),
  runtimeDeliveryBatchSize: document.getElementById("runtimeDeliveryBatchSize"),
  runtimeDeliveryRetrySeconds: document.getElementById("runtimeDeliveryRetrySeconds"),
  runtimeOrderCooldownSeconds: document.getElementById("runtimeOrderCooldownSeconds"),
  runtimeAllowSharedClaimCommand: document.getElementById("runtimeAllowSharedClaimCommand"),
  runtimeRefundUndeliveredEnabled: document.getElementById("runtimeRefundUndeliveredEnabled"),
  runtimeWebshopSaveBtn: document.getElementById("runtimeWebshopSaveBtn"),
  runtimeWebshopStatusView: document.getElementById("runtimeWebshopStatusView"),
  runtimeMarketMaxActiveListings: document.getElementById("runtimeMarketMaxActiveListings"),
  runtimeMarketAutoRefreshThreshold: document.getElementById("runtimeMarketAutoRefreshThreshold"),
  runtimeMarketDefaultTransferBatchSize: document.getElementById("runtimeMarketDefaultTransferBatchSize"),
  runtimeMarketMaxTransferBatchSize: document.getElementById("runtimeMarketMaxTransferBatchSize"),
  runtimeMarketDefaultTransitStock: document.getElementById("runtimeMarketDefaultTransitStock"),
  runtimeMarketMaxTransitStock: document.getElementById("runtimeMarketMaxTransitStock"),
  runtimeMarketSaveBtn: document.getElementById("runtimeMarketSaveBtn"),
  runtimeMarketStatusView: document.getElementById("runtimeMarketStatusView"),
  marketTagRefreshBtn: document.getElementById("marketTagRefreshBtn"),
  marketTagMetaStatusView: document.getElementById("marketTagMetaStatusView"),
  marketTagMetaList: document.getElementById("marketTagMetaList"),
  marketLimitationRefreshBtn: document.getElementById("marketLimitationRefreshBtn"),
  marketLimitationSummaryView: document.getElementById("marketLimitationSummaryView"),
  marketTagConfigEditor: document.getElementById("marketTagConfigEditor"),
  marketLimitationConfigEditor: document.getElementById("marketLimitationConfigEditor"),
  marketTagVersionInput: document.getElementById("marketTagVersionInput"),
  marketTagDefaultTagSelect: document.getElementById("marketTagDefaultTagSelect"),
  marketTagAddRowBtn: document.getElementById("marketTagAddRowBtn"),
  marketTagConfigList: document.getElementById("marketTagConfigList"),
  marketLimitationDefaultDenySidesInput: document.getElementById("marketLimitationDefaultDenySidesInput"),
  marketLimitationDefaultDenyCurrenciesInput: document.getElementById("marketLimitationDefaultDenyCurrenciesInput"),
  marketLimitationDefaultAllowSidesInput: document.getElementById("marketLimitationDefaultAllowSidesInput"),
  marketLimitationDefaultAllowTradeModesInput: document.getElementById("marketLimitationDefaultAllowTradeModesInput"),
  marketLimitationDefaultAllowCurrenciesInput: document.getElementById("marketLimitationDefaultAllowCurrenciesInput"),
  marketLimitationDefaultAllowTagsInput: document.getElementById("marketLimitationDefaultAllowTagsInput"),
  marketLimitationDefaultCreateCostEnabled: document.getElementById("marketLimitationDefaultCreateCostEnabled"),
  marketLimitationDefaultCreateCostCurrency: document.getElementById("marketLimitationDefaultCreateCostCurrency"),
  marketLimitationDefaultCreateCostAmount: document.getElementById("marketLimitationDefaultCreateCostAmount"),
  marketLimitationAddRuleBtn: document.getElementById("marketLimitationAddRuleBtn"),
  marketLimitationRuleList: document.getElementById("marketLimitationRuleList"),
  marketTagJsonApplyBtn: document.getElementById("marketTagJsonApplyBtn"),
  marketLimitationJsonApplyBtn: document.getElementById("marketLimitationJsonApplyBtn"),
  marketTagEditDialog: document.getElementById("marketTagEditDialog"),
  marketTagEditCodeInput: document.getElementById("marketTagEditCodeInput"),
  marketTagEditDisplayNameInput: document.getElementById("marketTagEditDisplayNameInput"),
  marketTagEditEnabledSelect: document.getElementById("marketTagEditEnabledSelect"),
  marketTagEditPriorityInput: document.getElementById("marketTagEditPriorityInput"),
  marketTagEditMaterialInInput: document.getElementById("marketTagEditMaterialInInput"),
  marketTagEditNbtHasAnyInput: document.getElementById("marketTagEditNbtHasAnyInput"),
  marketTagEditCancelBtn: document.getElementById("marketTagEditCancelBtn"),
  marketTagEditSaveBtn: document.getElementById("marketTagEditSaveBtn"),
  marketLimitationRuleEditDialog: document.getElementById("marketLimitationRuleEditDialog"),
  marketRuleEditIdInput: document.getElementById("marketRuleEditIdInput"),
  marketRuleEditPriorityInput: document.getElementById("marketRuleEditPriorityInput"),
  marketRuleEditActionDenySelect: document.getElementById("marketRuleEditActionDenySelect"),
  marketRuleEditActionCodeInput: document.getElementById("marketRuleEditActionCodeInput"),
  marketRuleEditWhenSideInInput: document.getElementById("marketRuleEditWhenSideInInput"),
  marketRuleEditWhenMaterialInInput: document.getElementById("marketRuleEditWhenMaterialInInput"),
  marketRuleEditWhenNbtHasAnyInput: document.getElementById("marketRuleEditWhenNbtHasAnyInput"),
  marketRuleEditWhenLacksPermissionInput: document.getElementById("marketRuleEditWhenLacksPermissionInput"),
  marketRuleEditActionSideWhitelistInput: document.getElementById("marketRuleEditActionSideWhitelistInput"),
  marketRuleEditActionTradeModeWhitelistInput: document.getElementById("marketRuleEditActionTradeModeWhitelistInput"),
  marketRuleEditActionCurrencyWhitelistInput: document.getElementById("marketRuleEditActionCurrencyWhitelistInput"),
  marketRuleEditActionTagWhitelistInput: document.getElementById("marketRuleEditActionTagWhitelistInput"),
  marketRuleEditActionForcedTagInput: document.getElementById("marketRuleEditActionForcedTagInput"),
  marketRuleEditCreateCostEnabledSelect: document.getElementById("marketRuleEditCreateCostEnabledSelect"),
  marketRuleEditCreateCostCurrencyInput: document.getElementById("marketRuleEditCreateCostCurrencyInput"),
  marketRuleEditCreateCostAmountInput: document.getElementById("marketRuleEditCreateCostAmountInput"),
  marketRuleEditCancelBtn: document.getElementById("marketRuleEditCancelBtn"),
  marketRuleEditSaveBtn: document.getElementById("marketRuleEditSaveBtn"),
  marketTagConfigSaveBtn: document.getElementById("marketTagConfigSaveBtn"),
  marketLimitationConfigSaveBtn: document.getElementById("marketLimitationConfigSaveBtn"),
  marketTagConfigStatusView: document.getElementById("marketTagConfigStatusView"),
  marketLimitationConfigStatusView: document.getElementById("marketLimitationConfigStatusView"),
  runtimeCleanupIntervalMinutes: document.getElementById("runtimeCleanupIntervalMinutes"),
  runtimePendingBindRetentionHours: document.getElementById("runtimePendingBindRetentionHours"),
  runtimePendingPasswordRetentionHours: document.getElementById("runtimePendingPasswordRetentionHours"),
  runtimeBindRequestRetentionHours: document.getElementById("runtimeBindRequestRetentionHours"),
  runtimeRedeemCodeRetentionDays: document.getElementById("runtimeRedeemCodeRetentionDays"),
  runtimeMaintenanceSaveBtn: document.getElementById("runtimeMaintenanceSaveBtn"),
  runtimeMaintenanceStatusView: document.getElementById("runtimeMaintenanceStatusView"),
  runtimeLoggingEnabled: document.getElementById("runtimeLoggingEnabled"),
  runtimeLoggingLevel: document.getElementById("runtimeLoggingLevel"),
  runtimeLoggingDirectory: document.getElementById("runtimeLoggingDirectory"),
  runtimeLoggingMaxFileSizeMb: document.getElementById("runtimeLoggingMaxFileSizeMb"),
  runtimeLoggingMaxFiles: document.getElementById("runtimeLoggingMaxFiles"),
  runtimeLoggingRetentionDays: document.getElementById("runtimeLoggingRetentionDays"),
  runtimeLoggingSaveBtn: document.getElementById("runtimeLoggingSaveBtn"),
  runtimeLoggingStatusView: document.getElementById("runtimeLoggingStatusView"),
  runtimeBroadcastEnabled: document.getElementById("runtimeBroadcastEnabled"),
  runtimeBroadcastListingCreatedTemplate: document.getElementById("runtimeBroadcastListingCreatedTemplate"),
  runtimeBroadcastTradeSuccessTemplate: document.getElementById("runtimeBroadcastTradeSuccessTemplate"),
  runtimeBroadcastAuctionBidTemplate: document.getElementById("runtimeBroadcastAuctionBidTemplate"),
  runtimeBroadcastAuctionSealedBidTemplate: document.getElementById("runtimeBroadcastAuctionSealedBidTemplate"),
  runtimeBroadcastSaveBtn: document.getElementById("runtimeBroadcastSaveBtn"),
  runtimeBroadcastStatusView: document.getElementById("runtimeBroadcastStatusView"),
  visualGlobalCustomIconEnabled: document.getElementById("visualGlobalCustomIconEnabled"),
  visualGlobalCustomNameEnabled: document.getElementById("visualGlobalCustomNameEnabled"),
  visualOfficialProductCustomIconEnabled: document.getElementById("visualOfficialProductCustomIconEnabled"),
  visualOfficialProductCustomNameEnabled: document.getElementById("visualOfficialProductCustomNameEnabled"),
  visualOfficialProductUploadImageEnabled: document.getElementById("visualOfficialProductUploadImageEnabled"),
  visualMarketListingCustomIconEnabled: document.getElementById("visualMarketListingCustomIconEnabled"),
  visualMarketListingCustomNameEnabled: document.getElementById("visualMarketListingCustomNameEnabled"),
  visualMarketListingUploadImageEnabled: document.getElementById("visualMarketListingUploadImageEnabled"),
  visualIconPolicyMode: document.getElementById("visualIconPolicyMode"),
  visualNamePolicyMode: document.getElementById("visualNamePolicyMode"),
  visualSettingsSaveBtn: document.getElementById("visualSettingsSaveBtn"),
  visualSettingsStatusView: document.getElementById("visualSettingsStatusView"),
  materialOverrideKeyword: document.getElementById("materialOverrideKeyword"),
  materialOverrideRefreshBtn: document.getElementById("materialOverrideRefreshBtn"),
  materialOverrideMaterial: document.getElementById("materialOverrideMaterial"),
  materialOverrideDisplayName: document.getElementById("materialOverrideDisplayName"),
  materialOverrideIconFile: document.getElementById("materialOverrideIconFile"),
  materialOverrideUploadBtn: document.getElementById("materialOverrideUploadBtn"),
  materialOverrideSaveBtn: document.getElementById("materialOverrideSaveBtn"),
  materialOverrideDeleteBtn: document.getElementById("materialOverrideDeleteBtn"),
  materialOverrideClearBtn: document.getElementById("materialOverrideClearBtn"),
  materialOverrideStatusView: document.getElementById("materialOverrideStatusView"),
  materialOverrideList: document.getElementById("materialOverrideList"),
  materialOverridePreviewImage: document.getElementById("materialOverridePreviewImage"),
  materialOverridePreviewLabel: document.getElementById("materialOverridePreviewLabel"),
  materialCropDialog: document.getElementById("materialCropDialog"),
  materialCropCanvas: document.getElementById("materialCropCanvas"),
  materialCropZoom: document.getElementById("materialCropZoom"),
  materialCropZoomValue: document.getElementById("materialCropZoomValue"),
  materialCropResetBtn: document.getElementById("materialCropResetBtn"),
  materialCropCancelBtn: document.getElementById("materialCropCancelBtn"),
  materialCropApplyBtn: document.getElementById("materialCropApplyBtn"),

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
  userVisualIconPermission: document.getElementById("userVisualIconPermission"),
  userVisualNamePermission: document.getElementById("userVisualNamePermission"),
  userVisualUploadPermission: document.getElementById("userVisualUploadPermission"),
  userListingLimitOverride: document.getElementById("userListingLimitOverride"),
  userVisualPermissionSaveBtn: document.getElementById("userVisualPermissionSaveBtn"),
  userVisualPermissionStatus: document.getElementById("userVisualPermissionStatus"),
  userListingLimitStatus: document.getElementById("userListingLimitStatus"),
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

  adminSubTabs: document.getElementById("adminSubTabs"),
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

const MAJOR_TAB_CHILDREN = {
  overview: ["login", "audit"],
  commerce: ["products", "redeem", "orders"],
  market: ["market"],
  users: ["users", "admins"],
  system: ["economy"],
};

const TAB_MAJOR_MAP = Object.entries(MAJOR_TAB_CHILDREN).reduce((acc, [major, children]) => {
  children.forEach((child) => {
    acc[child] = major;
  });
  return acc;
}, {});

function tabDisplayName(tabName) {
  const labels = {
    login: "状态",
    audit: "审计",
    products: "官方商品",
    redeem: "兑换码",
    orders: "订单",
    market: "上架管理",
    users: "账号管理",
    admins: "管理员",
    economy: "经济与配置",
  };
  return labels[tabName] || tabName;
}

function triggerTabDataLoad(tabName) {
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

function renderSubTabs(majorTab) {
  if (!elements.adminSubTabs) {
    return;
  }
  const children = MAJOR_TAB_CHILDREN[majorTab] || [];
  elements.adminSubTabs.innerHTML = "";
  if (children.length <= 1) {
    elements.adminSubTabs.style.display = "none";
    return;
  }
  elements.adminSubTabs.style.display = "";
  children.forEach((leafTab) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "btn-segment";
    button.dataset.tabTarget = leafTab;
    setNodeText(button, tabDisplayName(leafTab));
    button.addEventListener("click", () => switchTab(leafTab));
    elements.adminSubTabs.appendChild(button);
  });
}

function setActiveMajor(majorTab) {
  state.activeMajor = MAJOR_TAB_CHILDREN[majorTab] ? majorTab : "overview";
  tabs.forEach((tab) => tab.classList.toggle("active", tab.dataset.majorTarget === state.activeMajor));
  renderSubTabs(state.activeMajor);
}

function updateSubTabActiveState(activeLeafTab) {
  if (!elements.adminSubTabs) {
    return;
  }
  const buttons = Array.from(elements.adminSubTabs.querySelectorAll("[data-tab-target]"));
  buttons.forEach((button) => {
    const isActive = button.dataset.tabTarget === activeLeafTab;
    button.classList.toggle("is-active", isActive);
    button.classList.toggle("active", isActive);
  });
}

function switchTab(tabName, skipHistory = false) {
  const leafTab = TAB_MAJOR_MAP[tabName] ? tabName : "login";
  const majorTab = TAB_MAJOR_MAP[leafTab] || "overview";
  if (state.activeMajor !== majorTab) {
    setActiveMajor(majorTab);
  } else {
    renderSubTabs(majorTab);
  }

  state.activeTab = leafTab;
  state.majorLastTab[majorTab] = leafTab;
  updateSubTabActiveState(leafTab);
  panels.forEach((panel) => panel.classList.toggle("active", panel.dataset.tabPanel === leafTab));

  if (!skipHistory && ADMIN_TAB_PATH_MAP[leafTab]) {
    const newPath = ADMIN_TAB_PATH_MAP[leafTab];
    if (window.location.pathname !== newPath) {
      window.history.pushState({ tab: leafTab }, "", newPath);
    }
  }
  triggerTabDataLoad(leafTab);
}

function switchMajorTab(majorTab, skipHistory = false) {
  const normalizedMajor = MAJOR_TAB_CHILDREN[majorTab] ? majorTab : "overview";
  setActiveMajor(normalizedMajor);
  const children = MAJOR_TAB_CHILDREN[normalizedMajor] || [];
  const preferred = state.majorLastTab[normalizedMajor];
  const nextLeafTab = preferred && children.includes(preferred) ? preferred : (children[0] || "login");
  switchTab(nextLeafTab, skipHistory);
}

tabs.forEach((tab) => tab.addEventListener("click", () => switchMajorTab(tab.dataset.majorTarget)));

window.addEventListener("popstate", (event) => {
  if (event.state && event.state.tab) {
    switchTab(event.state.tab, true);
  } else {
    const path = window.location.pathname;
    const tabName = ADMIN_PATH_TAB_MAP[path] || "login";
    switchTab(tabName, true);
  }
});

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

async function apiAdminUpload(path, file) {
  const headers = {};
  if (state.token) {
    headers.Authorization = `Bearer ${state.token}`;
  }
  const contentType = String(file?.type || "").trim();
  if (contentType) {
    headers["Content-Type"] = contentType;
  } else {
    headers["Content-Type"] = "application/octet-stream";
  }
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
    throw error;
  }
  return payload;
}

function fileNameWithExtension(name, ext) {
  const base = String(name || "material-icon")
    .replace(/[\\/:*?"<>|]+/g, "_")
    .replace(/\.[^./\\]+$/, "")
    .trim() || "material-icon";
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
  materialCropState.sourceFileName = input.name || "material-icon.png";
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
    if (event.key !== "Escape") {
      return;
    }
    if (isMaterialCropDialogOpen()) {
      closeMaterialCropDialog(null);
      return;
    }
    if (isMarketTagEditDialogOpen()) {
      closeMarketTagEditDialog();
      return;
    }
    if (isMarketLimitationRuleEditDialogOpen()) {
      closeMarketLimitationRuleEditDialog();
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

  if (elements.leaderboardDefaultMetric) {
    Array.from(elements.leaderboardDefaultMetric.options || []).forEach((option) => {
      if (option.value === "SHOP_COIN") {
        option.textContent = shopName;
      }
      if (option.value === "GAME_COIN") {
        option.textContent = gameName;
      }
    });
  }

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

function normalizeMarketTagCode(tagCode) {
  return String(tagCode || "").trim().toLowerCase();
}

function renderMarketTagMetaCard(tag) {
  const card = document.createElement("div");
  card.className = "admin-card";
  const title = document.createElement("strong");
  setNodeText(title, `${tag.displayName || tag.code} (${tag.code})`);
  card.appendChild(title);

  const tags = document.createElement("div");
  tags.className = "admin-tag-row";
  tags.appendChild(createTag(tag.enabled ? "启用" : "停用", tag.enabled ? "success" : "muted"));
  tags.appendChild(createTag(`优先级 ${tag.priority}`, "info"));
  tags.appendChild(createTag(`出售单 ${tag.activeSellCount}`, "accent"));
  tags.appendChild(createTag(`收购单 ${tag.activeBuyCount}`, "neutral"));
  card.appendChild(tags);
  return card;
}

function renderMarketTagMetaList() {
  if (!elements.marketTagMetaList) {
    return;
  }
  const rows = (state.marketTags || []).map((tag) => renderMarketTagMetaCard(tag));
  renderList(elements.marketTagMetaList, rows);
}

async function loadMarketTagMeta(options = {}) {
  const announce = !!options.announce;
  const response = await fetch(resolveApiUrl("/api/meta/market-tags"), { method: "GET" });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  const payload = await response.json();
  const rows = Array.isArray(payload?.tags) ? payload.tags : [];
  state.marketTags = rows
    .map((item) => ({
      code: normalizeMarketTagCode(item?.code),
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
  renderMarketTagMetaList();
  setMetaText(elements.marketTagMetaStatusView, `已加载 ${state.marketTags.length} 个标签`, "info");
  if (announce) {
    notify(`标签已刷新：${state.marketTags.length} 个。`, "success");
  }
}

function formatJsonForEditor(value) {
  try {
    return JSON.stringify(value || {}, null, 2);
  } catch (error) {
    return "{}";
  }
}

function parseJsonObjectFromEditor(raw, label) {
  const text = String(raw || "").trim();
  if (!text) {
    throw new Error(`${label}不能为空。`);
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`${label}不是有效的 JSON。`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`${label}必须是 JSON 对象。`);
  }
  return parsed;
}

function pickObjectValue(root, ...keys) {
  if (!root || typeof root !== "object") {
    return {};
  }
  for (const key of keys) {
    const value = root[key];
    if (value && typeof value === "object" && !Array.isArray(value)) {
      return value;
    }
  }
  return {};
}

function pickArrayValue(root, ...keys) {
  if (!root || typeof root !== "object") {
    return [];
  }
  for (const key of keys) {
    const value = root[key];
    if (Array.isArray(value)) {
      return value;
    }
  }
  return [];
}

function normalizeTagCodeValue(raw) {
  const lower = String(raw || "").trim().toLowerCase();
  if (!lower) {
    return "";
  }
  return lower
    .replace(/[^a-z0-9_-]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function parseTokenInput(raw, options = {}) {
  const text = String(raw || "")
    .replace(/[，；;|]/g, ",")
    .trim();
  if (!text) {
    return [];
  }
  const upper = !!options.upper;
  const lower = !!options.lower;
  const unique = new Set();
  text
    .split(",")
    .map((item) => item.trim())
    .filter((item) => !!item)
    .forEach((item) => {
      let normalized = item;
      if (upper) {
        normalized = normalized.toUpperCase();
      } else if (lower) {
        normalized = normalized.toLowerCase();
      }
      if (normalized) {
        unique.add(normalized);
      }
    });
  return Array.from(unique);
}

function joinTokenList(values) {
  if (!Array.isArray(values) || values.length === 0) {
    return "";
  }
  return values
    .map((item) => String(item || "").trim())
    .filter((item) => !!item)
    .join(", ");
}

function normalizeMarketTagConfig(rawConfig) {
  const root = rawConfig && typeof rawConfig === "object" ? rawConfig : {};
  const rawTags = Array.isArray(root.tags) ? root.tags : [];
  const tags = rawTags
    .map((row, index) => {
      const match = pickObjectValue(row, "match");
      const code = normalizeTagCodeValue(row?.code || `tag_${index + 1}`);
      if (!code) {
        return null;
      }
      return {
        code,
        displayName: String(row?.displayName || row?.["display-name"] || code).trim() || code,
        enabled: row?.enabled !== false,
        priority: Number(row?.priority ?? (index + 1) * 100) || (index + 1) * 100,
        materialIn: parseTokenInput(joinTokenList(pickArrayValue(match, "materialIn", "material-in")), { upper: true }),
        nbtHasAny: parseTokenInput(joinTokenList(pickArrayValue(match, "nbtHasAny", "nbt-has-any"))),
      };
    })
    .filter((row) => !!row);

  if (tags.length === 0) {
    tags.push({
      code: "default",
      displayName: "Other",
      enabled: true,
      priority: 9999,
      materialIn: [],
      nbtHasAny: [],
    });
  }

  let defaultTag = normalizeTagCodeValue(root.defaultTag || root["default-tag"]);
  if (!defaultTag || !tags.some((row) => row.code === defaultTag)) {
    defaultTag = tags[0].code;
  }

  const tagVersion = Math.max(1, Number(root.tagVersion || root["tag-version"] || root.version || 1) || 1);
  return { tagVersion, defaultTag, tags };
}

function normalizeMarketLimitationRule(rawRule, index) {
  const when = pickObjectValue(rawRule, "when");
  const item = pickObjectValue(when, "item");
  const player = pickObjectValue(when, "player");
  const action = pickObjectValue(rawRule, "action");
  const createCost = pickObjectValue(action, "createCost", "create-cost");
  return {
    id: String(rawRule?.id || `rule_${index + 1}`).trim() || `rule_${index + 1}`,
    priority: Number(rawRule?.priority ?? ((index + 1) * 100)) || ((index + 1) * 100),
    whenSideIn: parseTokenInput(joinTokenList(pickArrayValue(when, "sideIn", "side-in")), { upper: true }),
    whenItemMaterialIn: parseTokenInput(
      joinTokenList(pickArrayValue(item, "materialIn", "material-in")),
      { upper: true }
    ),
    whenItemNbtHasAny: parseTokenInput(joinTokenList(pickArrayValue(item, "nbtHasAny", "nbt-has-any"))),
    whenPlayerLacksPermission: parseTokenInput(
      joinTokenList(pickArrayValue(player, "lacksPermission", "lacks-permission"))
    ),
    actionDeny: action?.deny === true,
    actionCode: String(action?.code || "").trim(),
    actionSideWhitelist: parseTokenInput(
      joinTokenList(pickArrayValue(action, "sideWhitelist", "side-whitelist")),
      { upper: true }
    ),
    actionTradeModeWhitelist: parseTokenInput(
      joinTokenList(pickArrayValue(action, "tradeModeWhitelist", "trade-mode-whitelist")),
      { upper: true }
    ),
    actionCurrencyWhitelist: parseTokenInput(
      joinTokenList(pickArrayValue(action, "currencyWhitelist", "currency-whitelist")),
      { upper: true }
    ),
    actionTagWhitelist: parseTokenInput(
      joinTokenList(pickArrayValue(action, "tagWhitelist", "tag-whitelist")),
      { lower: true }
    ).map((item) => normalizeTagCodeValue(item)).filter((item) => !!item),
    actionForcedTag: normalizeTagCodeValue(action?.forcedTag || action?.["forced-tag"] || ""),
    actionCreateCostEnabled: createCost?.enabled === true,
    actionCreateCostCurrency: String(createCost?.currency || "INHERIT").trim().toUpperCase() || "INHERIT",
    actionCreateCostAmount: Math.max(0, Number(createCost?.amount ?? 0) || 0),
  };
}

function normalizeMarketLimitationConfig(rawConfig) {
  const root = rawConfig && typeof rawConfig === "object" ? rawConfig : {};
  const defaults = pickObjectValue(root, "default");
  const deny = pickObjectValue(defaults, "deny");
  const allow = pickObjectValue(defaults, "allow");
  const createCost = pickObjectValue(defaults, "createCost", "create-cost");
  const rules = Array.isArray(root.rules) ? root.rules.map((row, index) => normalizeMarketLimitationRule(row, index)) : [];
  return {
    defaultDenySides: parseTokenInput(joinTokenList(pickArrayValue(deny, "marketSides", "market-sides")), { upper: true }),
    defaultDenyCurrencies: parseTokenInput(joinTokenList(pickArrayValue(deny, "currencies")), { upper: true }),
    defaultAllowSides: parseTokenInput(joinTokenList(pickArrayValue(allow, "marketSides", "market-sides")), { upper: true }),
    defaultAllowTradeModes: parseTokenInput(joinTokenList(pickArrayValue(allow, "tradeModes", "trade-modes")), { upper: true }),
    defaultAllowCurrencies: parseTokenInput(joinTokenList(pickArrayValue(allow, "currencies")), { upper: true }),
    defaultAllowTags: parseTokenInput(joinTokenList(pickArrayValue(allow, "tags")), { lower: true })
      .map((item) => normalizeTagCodeValue(item))
      .filter((item) => !!item),
    defaultCreateCostEnabled: createCost?.enabled === true,
    defaultCreateCostCurrency: String(createCost?.currency || "INHERIT").trim().toUpperCase() || "INHERIT",
    defaultCreateCostAmount: Math.max(0, Number(createCost?.amount ?? 0) || 0),
    rules,
  };
}

function syncMarketPolicyJsonEditors() {
  if (elements.marketTagConfigEditor) {
    elements.marketTagConfigEditor.value = formatJsonForEditor(buildMarketTagConfigPayloadFromState());
  }
  if (elements.marketLimitationConfigEditor) {
    elements.marketLimitationConfigEditor.value = formatJsonForEditor(buildMarketLimitationConfigPayloadFromState());
  }
}

function refreshMarketTagDefaultSelect() {
  if (!elements.marketTagDefaultTagSelect) {
    return;
  }
  const previous = normalizeTagCodeValue(elements.marketTagDefaultTagSelect.value || state.marketTagConfig?.defaultTag || "");
  const tags = Array.isArray(state.marketTagConfig?.tags) ? state.marketTagConfig.tags : [];
  const options = tags
    .map((tag) => ({
      code: normalizeTagCodeValue(tag?.code || ""),
      displayName: String(tag?.displayName || tag?.code || "").trim(),
    }))
    .filter((tag) => !!tag.code);

  elements.marketTagDefaultTagSelect.innerHTML = "";
  options.forEach((tag) => {
    const option = document.createElement("option");
    option.value = tag.code;
    setNodeText(option, `${tag.displayName || tag.code} (${tag.code})`);
    elements.marketTagDefaultTagSelect.appendChild(option);
  });

  const selected = options.some((tag) => tag.code === previous)
    ? previous
    : (options[0]?.code || "default");
  elements.marketTagDefaultTagSelect.value = selected;
  if (!state.marketTagConfig || typeof state.marketTagConfig !== "object") {
    state.marketTagConfig = normalizeMarketTagConfig({});
  }
  state.marketTagConfig.defaultTag = selected;
}

function renderMarketTagConfigList() {
  if (!elements.marketTagConfigList) {
    return;
  }
  if (!state.marketTagConfig || typeof state.marketTagConfig !== "object") {
    state.marketTagConfig = normalizeMarketTagConfig({});
  }
  const tags = Array.isArray(state.marketTagConfig.tags) ? state.marketTagConfig.tags : [];
  const rows = tags.map((tag, index) => {
    const card = document.createElement("div");
    card.className = "admin-card";
    const header = document.createElement("div");
    header.className = "admin-row";
    const title = document.createElement("strong");
    setNodeText(title, `${tag.displayName || tag.code || `标签 #${index + 1}`} (${tag.code || "-"})`);
    header.appendChild(title);
    const actionWrap = document.createElement("div");
    actionWrap.className = "admin-actions";
    const editBtn = document.createElement("button");
    editBtn.type = "button";
    editBtn.className = "btn-tonal";
    setNodeText(editBtn, "编辑");
    editBtn.addEventListener("click", () => {
      openMarketTagEditDialog(index);
    });
    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "btn-tonal";
    setNodeText(deleteBtn, "删除");
    deleteBtn.addEventListener("click", () => {
      state.marketTagConfig.tags = state.marketTagConfig.tags.filter((_, rowIndex) => rowIndex !== index);
      if (state.marketTagConfig.tags.length === 0) {
        state.marketTagConfig = normalizeMarketTagConfig({});
      }
      renderMarketTagConfigList();
      syncMarketPolicyJsonEditors();
    });
    actionWrap.appendChild(editBtn);
    actionWrap.appendChild(deleteBtn);
    header.appendChild(actionWrap);
    card.appendChild(header);

    const tagsRow = document.createElement("div");
    tagsRow.className = "admin-tag-row";
    tagsRow.appendChild(createTag(tag.enabled === false ? "停用" : "启用", tag.enabled === false ? "muted" : "success"));
    tagsRow.appendChild(createTag(`优先级 ${Number(tag.priority || 0)}`, "info"));
    tagsRow.appendChild(createTag(`材质 ${Array.isArray(tag.materialIn) ? tag.materialIn.length : 0}`, "neutral"));
    tagsRow.appendChild(createTag(`NBT ${Array.isArray(tag.nbtHasAny) ? tag.nbtHasAny.length : 0}`, "accent"));
    if (state.marketTagConfig.defaultTag === tag.code) {
      tagsRow.appendChild(createTag("默认标签", "warn"));
    }
    card.appendChild(tagsRow);

    const summary = document.createElement("p");
    summary.className = "admin-card-subtitle";
    const materialPreview = joinTokenList((tag.materialIn || []).slice(0, 4));
    const nbtPreview = joinTokenList((tag.nbtHasAny || []).slice(0, 4));
    setNodeText(
      summary,
      `材质: ${materialPreview || "不限"}${(tag.materialIn || []).length > 4 ? " ..." : ""} | NBT: ${nbtPreview || "不限"}${(tag.nbtHasAny || []).length > 4 ? " ..." : ""}`
    );
    card.appendChild(summary);
    return card;
  });
  renderList(elements.marketTagConfigList, rows);

  if (elements.marketTagVersionInput) {
    elements.marketTagVersionInput.value = String(Math.max(1, Number(state.marketTagConfig.tagVersion || 1)));
  }
  refreshMarketTagDefaultSelect();
}

function renderMarketLimitationRuleList() {
  if (!elements.marketLimitationRuleList) {
    return;
  }
  if (!state.marketLimitationConfig || typeof state.marketLimitationConfig !== "object") {
    state.marketLimitationConfig = normalizeMarketLimitationConfig({});
  }
  const rules = Array.isArray(state.marketLimitationConfig.rules) ? state.marketLimitationConfig.rules : [];
  const rows = rules.map((rule, index) => {
    const card = document.createElement("div");
    card.className = "admin-card";
    const header = document.createElement("div");
    header.className = "admin-row";
    const title = document.createElement("strong");
    setNodeText(title, `规则 #${index + 1} · ${rule.id || "未命名"}`);
    header.appendChild(title);
    const actionWrap = document.createElement("div");
    actionWrap.className = "admin-actions";
    const editBtn = document.createElement("button");
    editBtn.type = "button";
    editBtn.className = "btn-tonal";
    setNodeText(editBtn, "编辑");
    editBtn.addEventListener("click", () => {
      openMarketLimitationRuleEditDialog(index);
    });
    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "btn-tonal";
    setNodeText(deleteBtn, "删除");
    deleteBtn.addEventListener("click", () => {
      state.marketLimitationConfig.rules = state.marketLimitationConfig.rules.filter((_, rowIndex) => rowIndex !== index);
      renderMarketLimitationRuleList();
      syncMarketPolicyJsonEditors();
      setMetaText(
        elements.marketLimitationSummaryView,
        buildMarketLimitationSummary(buildMarketLimitationConfigPayloadFromState()),
        "info"
      );
    });
    actionWrap.appendChild(editBtn);
    actionWrap.appendChild(deleteBtn);
    header.appendChild(actionWrap);
    card.appendChild(header);

    const tagsRow = document.createElement("div");
    tagsRow.className = "admin-tag-row";
    tagsRow.appendChild(createTag(`优先级 ${Number(rule.priority || 0)}`, "info"));
    tagsRow.appendChild(createTag(rule.actionDeny ? "拒绝规则" : "限制规则", rule.actionDeny ? "error" : "accent"));
    tagsRow.appendChild(createTag(`方向条件 ${Array.isArray(rule.whenSideIn) ? rule.whenSideIn.length : 0}`, "neutral"));
    tagsRow.appendChild(createTag(`币种限制 ${Array.isArray(rule.actionCurrencyWhitelist) ? rule.actionCurrencyWhitelist.length : 0}`, "warn"));
    card.appendChild(tagsRow);

    const summary = document.createElement("p");
    summary.className = "admin-card-subtitle";
    const whenText = joinTokenList(rule.whenSideIn || []) || "不限";
    const tradeText = joinTokenList(rule.actionTradeModeWhitelist || []) || "不限";
    const currencyText = joinTokenList(rule.actionCurrencyWhitelist || []) || "不限";
    const denyCode = rule.actionCode ? ` | code=${rule.actionCode}` : "";
    setNodeText(summary, `触发方向: ${whenText} | 交易模式: ${tradeText} | 币种: ${currencyText}${denyCode}`);
    card.appendChild(summary);
    return card;
  });
  renderList(elements.marketLimitationRuleList, rows);
}

function openMarketTagEditDialog(index) {
  if (!elements.marketTagEditDialog || !Array.isArray(state.marketTagConfig?.tags)) {
    return;
  }
  const tag = state.marketTagConfig.tags[index];
  if (!tag) {
    return;
  }
  state.marketTagEditingIndex = index;
  if (elements.marketTagEditCodeInput) {
    elements.marketTagEditCodeInput.value = tag.code || "";
  }
  if (elements.marketTagEditDisplayNameInput) {
    elements.marketTagEditDisplayNameInput.value = tag.displayName || "";
  }
  if (elements.marketTagEditEnabledSelect) {
    elements.marketTagEditEnabledSelect.value = tag.enabled === false ? "false" : "true";
  }
  if (elements.marketTagEditPriorityInput) {
    elements.marketTagEditPriorityInput.value = String(Number(tag.priority || 0));
  }
  if (elements.marketTagEditMaterialInInput) {
    elements.marketTagEditMaterialInInput.value = joinTokenList(tag.materialIn || []);
  }
  if (elements.marketTagEditNbtHasAnyInput) {
    elements.marketTagEditNbtHasAnyInput.value = joinTokenList(tag.nbtHasAny || []);
  }
  elements.marketTagEditDialog.classList.add("show");
  elements.marketTagEditDialog.setAttribute("aria-hidden", "false");
}

function closeMarketTagEditDialog() {
  state.marketTagEditingIndex = null;
  if (!elements.marketTagEditDialog) {
    return;
  }
  elements.marketTagEditDialog.classList.remove("show");
  elements.marketTagEditDialog.setAttribute("aria-hidden", "true");
}

function isMarketTagEditDialogOpen() {
  return Boolean(elements.marketTagEditDialog?.classList.contains("show"));
}

function saveMarketTagEditDialog() {
  const index = state.marketTagEditingIndex;
  if (!Array.isArray(state.marketTagConfig?.tags) || !Number.isInteger(index) || index < 0 || index >= state.marketTagConfig.tags.length) {
    throw new Error("当前没有可保存的标签。");
  }
  const source = state.marketTagConfig.tags[index];
  const updated = {
    ...source,
    code: normalizeTagCodeValue(elements.marketTagEditCodeInput?.value || source.code || ""),
    displayName: String(elements.marketTagEditDisplayNameInput?.value || source.displayName || "").trim(),
    enabled: elements.marketTagEditEnabledSelect?.value !== "false",
    priority: Number(elements.marketTagEditPriorityInput?.value || source.priority || 0),
    materialIn: parseTokenInput(elements.marketTagEditMaterialInInput?.value || "", { upper: true }),
    nbtHasAny: parseTokenInput(elements.marketTagEditNbtHasAnyInput?.value || ""),
  };
  if (!updated.code) {
    throw new Error("标签 code 不能为空。");
  }
  if (!updated.displayName) {
    updated.displayName = updated.code;
  }
  const duplicated = state.marketTagConfig.tags.some((row, rowIndex) => rowIndex !== index && normalizeTagCodeValue(row.code) === updated.code);
  if (duplicated) {
    throw new Error(`标签 code 重复：${updated.code}`);
  }
  state.marketTagConfig.tags[index] = updated;
  if (state.marketTagConfig.defaultTag && normalizeTagCodeValue(source.code) === normalizeTagCodeValue(state.marketTagConfig.defaultTag)) {
    state.marketTagConfig.defaultTag = updated.code;
  }
  renderMarketTagConfigList();
  syncMarketPolicyJsonEditors();
  closeMarketTagEditDialog();
}

function openMarketLimitationRuleEditDialog(index) {
  if (!elements.marketLimitationRuleEditDialog || !Array.isArray(state.marketLimitationConfig?.rules)) {
    return;
  }
  const rule = state.marketLimitationConfig.rules[index];
  if (!rule) {
    return;
  }
  state.marketLimitationRuleEditingIndex = index;
  if (elements.marketRuleEditIdInput) {
    elements.marketRuleEditIdInput.value = rule.id || "";
  }
  if (elements.marketRuleEditPriorityInput) {
    elements.marketRuleEditPriorityInput.value = String(Number(rule.priority || 0));
  }
  if (elements.marketRuleEditActionDenySelect) {
    elements.marketRuleEditActionDenySelect.value = rule.actionDeny ? "true" : "false";
  }
  if (elements.marketRuleEditActionCodeInput) {
    elements.marketRuleEditActionCodeInput.value = rule.actionCode || "";
  }
  if (elements.marketRuleEditWhenSideInInput) {
    elements.marketRuleEditWhenSideInInput.value = joinTokenList(rule.whenSideIn || []);
  }
  if (elements.marketRuleEditWhenMaterialInInput) {
    elements.marketRuleEditWhenMaterialInInput.value = joinTokenList(rule.whenItemMaterialIn || []);
  }
  if (elements.marketRuleEditWhenNbtHasAnyInput) {
    elements.marketRuleEditWhenNbtHasAnyInput.value = joinTokenList(rule.whenItemNbtHasAny || []);
  }
  if (elements.marketRuleEditWhenLacksPermissionInput) {
    elements.marketRuleEditWhenLacksPermissionInput.value = joinTokenList(rule.whenPlayerLacksPermission || []);
  }
  if (elements.marketRuleEditActionSideWhitelistInput) {
    elements.marketRuleEditActionSideWhitelistInput.value = joinTokenList(rule.actionSideWhitelist || []);
  }
  if (elements.marketRuleEditActionTradeModeWhitelistInput) {
    elements.marketRuleEditActionTradeModeWhitelistInput.value = joinTokenList(rule.actionTradeModeWhitelist || []);
  }
  if (elements.marketRuleEditActionCurrencyWhitelistInput) {
    elements.marketRuleEditActionCurrencyWhitelistInput.value = joinTokenList(rule.actionCurrencyWhitelist || []);
  }
  if (elements.marketRuleEditActionTagWhitelistInput) {
    elements.marketRuleEditActionTagWhitelistInput.value = joinTokenList(rule.actionTagWhitelist || []);
  }
  if (elements.marketRuleEditActionForcedTagInput) {
    elements.marketRuleEditActionForcedTagInput.value = rule.actionForcedTag || "";
  }
  if (elements.marketRuleEditCreateCostEnabledSelect) {
    elements.marketRuleEditCreateCostEnabledSelect.value = rule.actionCreateCostEnabled ? "true" : "false";
  }
  if (elements.marketRuleEditCreateCostCurrencyInput) {
    elements.marketRuleEditCreateCostCurrencyInput.value = rule.actionCreateCostCurrency || "INHERIT";
  }
  if (elements.marketRuleEditCreateCostAmountInput) {
    elements.marketRuleEditCreateCostAmountInput.value = String(Math.max(0, Number(rule.actionCreateCostAmount || 0)));
  }
  elements.marketLimitationRuleEditDialog.classList.add("show");
  elements.marketLimitationRuleEditDialog.setAttribute("aria-hidden", "false");
}

function closeMarketLimitationRuleEditDialog() {
  state.marketLimitationRuleEditingIndex = null;
  if (!elements.marketLimitationRuleEditDialog) {
    return;
  }
  elements.marketLimitationRuleEditDialog.classList.remove("show");
  elements.marketLimitationRuleEditDialog.setAttribute("aria-hidden", "true");
}

function isMarketLimitationRuleEditDialogOpen() {
  return Boolean(elements.marketLimitationRuleEditDialog?.classList.contains("show"));
}

function saveMarketLimitationRuleEditDialog() {
  const index = state.marketLimitationRuleEditingIndex;
  if (!Array.isArray(state.marketLimitationConfig?.rules) || !Number.isInteger(index) || index < 0 || index >= state.marketLimitationConfig.rules.length) {
    throw new Error("当前没有可保存的规则。");
  }
  const updated = {
    ...state.marketLimitationConfig.rules[index],
    id: String(elements.marketRuleEditIdInput?.value || "").trim(),
    priority: Number(elements.marketRuleEditPriorityInput?.value || 0),
    whenSideIn: parseTokenInput(elements.marketRuleEditWhenSideInInput?.value || "", { upper: true }),
    whenItemMaterialIn: parseTokenInput(elements.marketRuleEditWhenMaterialInInput?.value || "", { upper: true }),
    whenItemNbtHasAny: parseTokenInput(elements.marketRuleEditWhenNbtHasAnyInput?.value || ""),
    whenPlayerLacksPermission: parseTokenInput(elements.marketRuleEditWhenLacksPermissionInput?.value || ""),
    actionDeny: elements.marketRuleEditActionDenySelect?.value === "true",
    actionCode: String(elements.marketRuleEditActionCodeInput?.value || "").trim(),
    actionSideWhitelist: parseTokenInput(elements.marketRuleEditActionSideWhitelistInput?.value || "", { upper: true }),
    actionTradeModeWhitelist: parseTokenInput(elements.marketRuleEditActionTradeModeWhitelistInput?.value || "", { upper: true }),
    actionCurrencyWhitelist: parseTokenInput(elements.marketRuleEditActionCurrencyWhitelistInput?.value || "", { upper: true }),
    actionTagWhitelist: parseTokenInput(elements.marketRuleEditActionTagWhitelistInput?.value || "", { lower: true })
      .map((item) => normalizeTagCodeValue(item))
      .filter((item) => !!item),
    actionForcedTag: normalizeTagCodeValue(elements.marketRuleEditActionForcedTagInput?.value || ""),
    actionCreateCostEnabled: elements.marketRuleEditCreateCostEnabledSelect?.value === "true",
    actionCreateCostCurrency: String(elements.marketRuleEditCreateCostCurrencyInput?.value || "INHERIT").trim().toUpperCase() || "INHERIT",
    actionCreateCostAmount: Math.max(0, Number(elements.marketRuleEditCreateCostAmountInput?.value || 0)),
  };
  if (!updated.id) {
    throw new Error("规则 ID 不能为空。");
  }
  const duplicated = state.marketLimitationConfig.rules.some((rule, rowIndex) => rowIndex !== index && String(rule.id || "").trim() === updated.id);
  if (duplicated) {
    throw new Error(`规则 ID 重复：${updated.id}`);
  }
  state.marketLimitationConfig.rules[index] = updated;
  renderMarketLimitationRuleList();
  syncMarketPolicyJsonEditors();
  setMetaText(
    elements.marketLimitationSummaryView,
    buildMarketLimitationSummary(buildMarketLimitationConfigPayloadFromState()),
    "info"
  );
  closeMarketLimitationRuleEditDialog();
}

function renderMarketLimitationDefaults() {
  if (!state.marketLimitationConfig || typeof state.marketLimitationConfig !== "object") {
    state.marketLimitationConfig = normalizeMarketLimitationConfig({});
  }
  const config = state.marketLimitationConfig;
  if (elements.marketLimitationDefaultDenySidesInput) {
    elements.marketLimitationDefaultDenySidesInput.value = joinTokenList(config.defaultDenySides || []);
  }
  if (elements.marketLimitationDefaultDenyCurrenciesInput) {
    elements.marketLimitationDefaultDenyCurrenciesInput.value = joinTokenList(config.defaultDenyCurrencies || []);
  }
  if (elements.marketLimitationDefaultAllowSidesInput) {
    elements.marketLimitationDefaultAllowSidesInput.value = joinTokenList(config.defaultAllowSides || []);
  }
  if (elements.marketLimitationDefaultAllowTradeModesInput) {
    elements.marketLimitationDefaultAllowTradeModesInput.value = joinTokenList(config.defaultAllowTradeModes || []);
  }
  if (elements.marketLimitationDefaultAllowCurrenciesInput) {
    elements.marketLimitationDefaultAllowCurrenciesInput.value = joinTokenList(config.defaultAllowCurrencies || []);
  }
  if (elements.marketLimitationDefaultAllowTagsInput) {
    elements.marketLimitationDefaultAllowTagsInput.value = joinTokenList(config.defaultAllowTags || []);
  }
  if (elements.marketLimitationDefaultCreateCostEnabled) {
    elements.marketLimitationDefaultCreateCostEnabled.value = config.defaultCreateCostEnabled ? "true" : "false";
  }
  if (elements.marketLimitationDefaultCreateCostCurrency) {
    elements.marketLimitationDefaultCreateCostCurrency.value = config.defaultCreateCostCurrency || "INHERIT";
  }
  if (elements.marketLimitationDefaultCreateCostAmount) {
    elements.marketLimitationDefaultCreateCostAmount.value = String(Math.max(0, Number(config.defaultCreateCostAmount || 0)));
  }
}

function buildMarketTagConfigPayloadFromState() {
  const normalized = normalizeMarketTagConfig(state.marketTagConfig || {});
  return {
    tagVersion: normalized.tagVersion,
    defaultTag: normalized.defaultTag,
    tags: normalized.tags.map((tag) => ({
      code: normalizeTagCodeValue(tag.code),
      displayName: String(tag.displayName || tag.code).trim() || normalizeTagCodeValue(tag.code),
      enabled: tag.enabled !== false,
      priority: Number(tag.priority || 0),
      match: {
        materialIn: parseTokenInput(joinTokenList(tag.materialIn), { upper: true }),
        nbtHasAny: parseTokenInput(joinTokenList(tag.nbtHasAny)),
      },
    })),
  };
}

function buildMarketLimitationConfigPayloadFromState() {
  const normalized = normalizeMarketLimitationConfig(state.marketLimitationConfig || {});
  return {
    default: {
      deny: {
        marketSides: normalized.defaultDenySides,
        currencies: normalized.defaultDenyCurrencies,
      },
      allow: {
        marketSides: normalized.defaultAllowSides,
        tradeModes: normalized.defaultAllowTradeModes,
        currencies: normalized.defaultAllowCurrencies,
        tags: normalized.defaultAllowTags,
      },
      createCost: {
        enabled: normalized.defaultCreateCostEnabled,
        currency: normalized.defaultCreateCostCurrency || "INHERIT",
        amount: Math.max(0, Number(normalized.defaultCreateCostAmount || 0)),
      },
    },
    rules: normalized.rules.map((rule) => {
      const row = {
        id: String(rule.id || "").trim(),
        priority: Number(rule.priority || 0),
        when: {},
        action: {
          deny: rule.actionDeny === true,
        },
      };
      if (rule.whenSideIn.length > 0) {
        row.when.sideIn = rule.whenSideIn;
      }
      if (rule.whenItemMaterialIn.length > 0 || rule.whenItemNbtHasAny.length > 0) {
        row.when.item = {};
        if (rule.whenItemMaterialIn.length > 0) {
          row.when.item.materialIn = rule.whenItemMaterialIn;
        }
        if (rule.whenItemNbtHasAny.length > 0) {
          row.when.item.nbtHasAny = rule.whenItemNbtHasAny;
        }
      }
      if (rule.whenPlayerLacksPermission.length > 0) {
        row.when.player = { lacksPermission: rule.whenPlayerLacksPermission };
      }
      if (rule.actionCode) {
        row.action.code = rule.actionCode;
      }
      if (rule.actionSideWhitelist.length > 0) {
        row.action.sideWhitelist = rule.actionSideWhitelist;
      }
      if (rule.actionTradeModeWhitelist.length > 0) {
        row.action.tradeModeWhitelist = rule.actionTradeModeWhitelist;
      }
      if (rule.actionCurrencyWhitelist.length > 0) {
        row.action.currencyWhitelist = rule.actionCurrencyWhitelist;
      }
      if (rule.actionTagWhitelist.length > 0) {
        row.action.tagWhitelist = rule.actionTagWhitelist;
      }
      if (rule.actionForcedTag) {
        row.action.forcedTag = rule.actionForcedTag;
      }
      if (rule.actionCreateCostEnabled || Number(rule.actionCreateCostAmount || 0) > 0 || rule.actionCreateCostCurrency !== "INHERIT") {
        row.action.createCost = {
          enabled: rule.actionCreateCostEnabled,
          currency: rule.actionCreateCostCurrency || "INHERIT",
          amount: Math.max(0, Number(rule.actionCreateCostAmount || 0)),
        };
      }
      return row;
    }),
  };
}

function collectMarketTagConfigFromVisual() {
  if (elements.marketTagVersionInput) {
    state.marketTagConfig.tagVersion = Math.max(1, Number(elements.marketTagVersionInput.value || 1) || 1);
  }
  if (elements.marketTagDefaultTagSelect) {
    state.marketTagConfig.defaultTag = normalizeTagCodeValue(elements.marketTagDefaultTagSelect.value || "");
  }
  const payload = buildMarketTagConfigPayloadFromState();
  const seen = new Set();
  payload.tags.forEach((tag) => {
    if (!tag.code) {
      throw new Error("标签 code 不能为空。");
    }
    if (seen.has(tag.code)) {
      throw new Error(`标签 code 重复：${tag.code}`);
    }
    seen.add(tag.code);
  });
  if (!payload.defaultTag || !seen.has(payload.defaultTag)) {
    payload.defaultTag = payload.tags[0]?.code || "default";
  }
  return payload;
}

function collectMarketLimitationConfigFromVisual() {
  if (!state.marketLimitationConfig || typeof state.marketLimitationConfig !== "object") {
    state.marketLimitationConfig = normalizeMarketLimitationConfig({});
  }
  updateMarketLimitationDefaultsStateFromInputs();
  const payload = buildMarketLimitationConfigPayloadFromState();
  const seenRuleIds = new Set();
  payload.rules.forEach((rule, index) => {
    if (!rule.id) {
      throw new Error(`第 ${index + 1} 条规则缺少 ID。`);
    }
    if (seenRuleIds.has(rule.id)) {
      throw new Error(`规则 ID 重复：${rule.id}`);
    }
    seenRuleIds.add(rule.id);
  });
  return payload;
}

function updateMarketLimitationDefaultsStateFromInputs() {
  if (!state.marketLimitationConfig || typeof state.marketLimitationConfig !== "object") {
    state.marketLimitationConfig = normalizeMarketLimitationConfig({});
  }
  state.marketLimitationConfig.defaultDenySides = parseTokenInput(
    elements.marketLimitationDefaultDenySidesInput?.value || "",
    { upper: true }
  );
  state.marketLimitationConfig.defaultDenyCurrencies = parseTokenInput(
    elements.marketLimitationDefaultDenyCurrenciesInput?.value || "",
    { upper: true }
  );
  state.marketLimitationConfig.defaultAllowSides = parseTokenInput(
    elements.marketLimitationDefaultAllowSidesInput?.value || "",
    { upper: true }
  );
  state.marketLimitationConfig.defaultAllowTradeModes = parseTokenInput(
    elements.marketLimitationDefaultAllowTradeModesInput?.value || "",
    { upper: true }
  );
  state.marketLimitationConfig.defaultAllowCurrencies = parseTokenInput(
    elements.marketLimitationDefaultAllowCurrenciesInput?.value || "",
    { upper: true }
  );
  state.marketLimitationConfig.defaultAllowTags = parseTokenInput(
    elements.marketLimitationDefaultAllowTagsInput?.value || "",
    { lower: true }
  ).map((item) => normalizeTagCodeValue(item)).filter((item) => !!item);
  state.marketLimitationConfig.defaultCreateCostEnabled = elements.marketLimitationDefaultCreateCostEnabled?.value === "true";
  state.marketLimitationConfig.defaultCreateCostCurrency = String(
    elements.marketLimitationDefaultCreateCostCurrency?.value || "INHERIT"
  ).trim().toUpperCase() || "INHERIT";
  state.marketLimitationConfig.defaultCreateCostAmount = Math.max(
    0,
    Number(elements.marketLimitationDefaultCreateCostAmount?.value || 0) || 0
  );
}

function addMarketTagConfigRow() {
  if (!state.marketTagConfig || typeof state.marketTagConfig !== "object") {
    state.marketTagConfig = normalizeMarketTagConfig({});
  }
  if (!Array.isArray(state.marketTagConfig.tags)) {
    state.marketTagConfig.tags = [];
  }
  const nextIndex = state.marketTagConfig.tags.length + 1;
  state.marketTagConfig.tags.push({
    code: `tag_${nextIndex}`,
    displayName: `Tag ${nextIndex}`,
    enabled: true,
    priority: nextIndex * 100,
    materialIn: [],
    nbtHasAny: [],
  });
  renderMarketTagConfigList();
  syncMarketPolicyJsonEditors();
  openMarketTagEditDialog(state.marketTagConfig.tags.length - 1);
}

function addMarketLimitationRule() {
  if (!state.marketLimitationConfig || typeof state.marketLimitationConfig !== "object") {
    state.marketLimitationConfig = normalizeMarketLimitationConfig({});
  }
  if (!Array.isArray(state.marketLimitationConfig.rules)) {
    state.marketLimitationConfig.rules = [];
  }
  const nextIndex = state.marketLimitationConfig.rules.length + 1;
  state.marketLimitationConfig.rules.push(normalizeMarketLimitationRule({
    id: `rule_${nextIndex}_${Date.now().toString().slice(-5)}`,
    priority: nextIndex * 100,
    when: {},
    action: { deny: false },
  }, nextIndex - 1));
  renderMarketLimitationRuleList();
  syncMarketPolicyJsonEditors();
  setMetaText(
    elements.marketLimitationSummaryView,
    buildMarketLimitationSummary(buildMarketLimitationConfigPayloadFromState()),
    "info"
  );
  openMarketLimitationRuleEditDialog(state.marketLimitationConfig.rules.length - 1);
}

function applyMarketTagJsonToVisual() {
  const config = parseJsonObjectFromEditor(elements.marketTagConfigEditor?.value, "标签规则 JSON");
  state.marketTagConfig = normalizeMarketTagConfig(config);
  renderMarketTagConfigList();
  syncMarketPolicyJsonEditors();
  setMetaText(elements.marketTagConfigStatusView, "已应用标签 JSON 到可视化表单", "success");
}

function applyMarketLimitationJsonToVisual() {
  const config = parseJsonObjectFromEditor(elements.marketLimitationConfigEditor?.value, "上架限制 JSON");
  state.marketLimitationConfig = normalizeMarketLimitationConfig(config);
  renderMarketLimitationDefaults();
  renderMarketLimitationRuleList();
  syncMarketPolicyJsonEditors();
  setMetaText(
    elements.marketLimitationSummaryView,
    buildMarketLimitationSummary(buildMarketLimitationConfigPayloadFromState()),
    "info"
  );
  setMetaText(elements.marketLimitationConfigStatusView, "已应用限制 JSON 到可视化表单", "success");
}

function buildMarketLimitationSummary(config) {
  const defaultLimit = Number(elements.runtimeMarketMaxActiveListings?.value || 10);
  const normalized = normalizeMarketLimitationConfig(config || {});
  const rules = Array.isArray(normalized.rules) ? normalized.rules.length : 0;
  const sideList = normalized.defaultAllowSides || [];
  const sideText = sideList.length > 0 ? sideList.join("/") : "SELL/BUY";
  return `默认最大上架数：${defaultLimit}；限制规则：${rules} 条；默认允许方向：${sideText}。`;
}

async function loadMarketPolicyConfigs(seed = {}) {
  ensureAdmin();
  let marketTagsConfig = seed?.marketTagsConfig;
  let marketLimitationConfig = seed?.marketLimitationConfig;

  if (!marketTagsConfig) {
    const payload = await apiAdmin("/api/admin/market/tags-config", { method: "GET" });
    marketTagsConfig = payload?.config || {};
  }
  if (!marketLimitationConfig) {
    const payload = await apiAdmin("/api/admin/market/limitation-config", { method: "GET" });
    marketLimitationConfig = payload?.config || {};
  }

  state.marketTagConfig = normalizeMarketTagConfig(marketTagsConfig || {});
  state.marketLimitationConfig = normalizeMarketLimitationConfig(marketLimitationConfig || {});

  renderMarketTagConfigList();
  renderMarketLimitationDefaults();
  renderMarketLimitationRuleList();
  syncMarketPolicyJsonEditors();

  const tagCount = Array.isArray(state.marketTagConfig?.tags) ? state.marketTagConfig.tags.length : 0;
  const ruleCount = Array.isArray(state.marketLimitationConfig?.rules) ? state.marketLimitationConfig.rules.length : 0;

  setMetaText(elements.marketTagConfigStatusView, `已加载标签规则（${tagCount} 项）`, "info");
  setMetaText(elements.marketLimitationConfigStatusView, `已加载上架限制（${ruleCount} 条规则）`, "info");
  setMetaText(
    elements.marketLimitationSummaryView,
    buildMarketLimitationSummary(buildMarketLimitationConfigPayloadFromState()),
    "info"
  );
}

async function saveMarketTagConfig() {
  ensureAdmin();
  const config = collectMarketTagConfigFromVisual();
  state.marketTagConfig = normalizeMarketTagConfig(config);
  syncMarketPolicyJsonEditors();
  await apiAdmin("/api/admin/market/tags-config", {
    method: "POST",
    body: JSON.stringify({ config }),
  });
  setMetaText(elements.marketTagConfigStatusView, "标签规则已保存", "success");
  notify("标签规则已保存", "success");
  await Promise.all([loadMarketPolicyConfigs(), loadMarketTagMeta()]);
}

async function saveMarketLimitationConfig() {
  ensureAdmin();
  const config = collectMarketLimitationConfigFromVisual();
  state.marketLimitationConfig = normalizeMarketLimitationConfig(config);
  syncMarketPolicyJsonEditors();
  await apiAdmin("/api/admin/market/limitation-config", {
    method: "POST",
    body: JSON.stringify({ config }),
  });
  setMetaText(elements.marketLimitationConfigStatusView, "上架限制已保存", "success");
  notify("上架限制已保存", "success");
  setMetaText(elements.marketLimitationSummaryView, buildMarketLimitationSummary(config), "info");
  await loadMarketPolicyConfigs();
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

function buildOrderStatusLabel(status) {
  const normalized = String(status || "").trim().toUpperCase();
  const labels = {
    PENDING: "待发放",
    WAIT_CLAIM: "待领取",
    DELIVERED: "已发放",
    REFUNDED: "已退款",
    FAILED: "失败",
    RECYCLED: "已回收",
  };
  return labels[normalized] || (normalized || "-");
}

function buildOrderStatusClass(status) {
  const normalized = String(status || "").trim().toUpperCase();
  if (normalized === "DELIVERED") {
    return "delivered";
  }
  if (normalized === "WAIT_CLAIM" || normalized === "PENDING") {
    return "pending";
  }
  if (normalized === "REFUNDED" || normalized === "FAILED") {
    return "error";
  }
  if (normalized === "RECYCLED") {
    return "muted";
  }
  return "default";
}

function createPlayerAvatarNode(name) {
  const displayName = String(name || "").trim() || "玩家";
  const wrap = document.createElement("div");
  wrap.className = "admin-player-avatar";

  const fallback = document.createElement("span");
  fallback.className = "admin-player-avatar-fallback";
  setNodeText(fallback, displayName.slice(0, 1).toUpperCase());
  wrap.appendChild(fallback);

  const image = document.createElement("img");
  image.alt = displayName;
  image.loading = "lazy";
  image.decoding = "async";
  image.src = `https://nmsr.nickac.dev/face/${encodeURIComponent(displayName)}`;
  image.addEventListener("load", () => {
    wrap.classList.add("is-ready");
  });
  image.addEventListener("error", () => {
    image.remove();
  });
  wrap.appendChild(image);
  return wrap;
}

function createInfoItem(labelText, valueText, muted = false) {
  const row = document.createElement("div");
  row.className = "admin-info-item";
  if (muted) {
    row.classList.add("is-muted");
  }
  const label = document.createElement("span");
  label.className = "admin-info-label";
  setNodeText(label, labelText);
  const value = document.createElement("strong");
  value.className = "admin-info-value";
  setNodeText(value, valueText);
  row.appendChild(label);
  row.appendChild(value);
  return row;
}

function createTag(text, tone = "neutral") {
  const tag = document.createElement("span");
  tag.className = `admin-tag ${tone}`;
  setNodeText(tag, text);
  return tag;
}

function renderOrderAdminCard(order) {
  const card = document.createElement("div");
  card.className = "admin-card admin-order-card-v2";

  const header = document.createElement("div");
  header.className = "admin-card-hero";

  const identity = document.createElement("div");
  identity.className = "admin-card-identity";
  identity.appendChild(createPlayerAvatarNode(order.username || order.boundUuid || order.mcUuid || "玩家"));

  const identityText = document.createElement("div");
  identityText.className = "admin-card-identity-text";
  const title = document.createElement("strong");
  title.className = "admin-card-title";
  setNodeText(title, order.username ? `${order.username} (#${order.userId || "-"})` : `用户 #${order.userId || "-"}`);
  const sub = document.createElement("p");
  sub.className = "admin-card-subtitle";
  setNodeText(sub, `订单 ${order.orderNo || "-"} · ${order.createdAt || "-"}`);
  identityText.appendChild(title);
  identityText.appendChild(sub);
  identity.appendChild(identityText);
  header.appendChild(identity);

  const status = document.createElement("span");
  status.className = `admin-order-status-v2 ${buildOrderStatusClass(order.status)}`;
  setNodeText(status, buildOrderStatusLabel(order.status));
  header.appendChild(status);
  card.appendChild(header);

  const primary = document.createElement("div");
  primary.className = "admin-order-primary";
  const amount = document.createElement("strong");
  amount.className = "admin-order-amount";
  setNodeText(amount, formatCurrency(order.totalAmount, order.currency));
  primary.appendChild(amount);
  const product = document.createElement("p");
  product.className = "admin-order-product";
  const materialName = order.itemMaterial
    ? `${order.itemMaterial} (${getLocalizedMaterialName(order.itemMaterial)})`
    : "无材质";
  setNodeText(
    product,
    `${order.productTitle || "-"} (${order.sku || "-"}) · x${order.quantity || 0} · ${materialName}`
  );
  primary.appendChild(product);
  card.appendChild(primary);

  const tags = document.createElement("div");
  tags.className = "admin-tag-row";
  tags.appendChild(createTag(currencyName(order.currency || "SHOP_COIN"), "accent"));
  tags.appendChild(createTag(order.productType || "-", "neutral"));
  if (order.groupBuyVoucherStatus) {
    tags.appendChild(createTag(`团购券 ${order.groupBuyVoucherStatus}`, "info"));
  }
  card.appendChild(tags);

  const infoGrid = document.createElement("div");
  infoGrid.className = "admin-info-grid";
  infoGrid.appendChild(createInfoItem("UUID", order.boundUuid || order.mcUuid || "-", true));
  infoGrid.appendChild(createInfoItem("备注", order.productRemark || "-", true));
  if (order.groupBuyVoucherCode) {
    infoGrid.appendChild(createInfoItem("团购码", order.groupBuyVoucherCode, true));
  }
  card.appendChild(infoGrid);
  return card;
}

function renderProductAdminCard(product, actions = []) {
  const card = document.createElement("div");
  card.className = "admin-card admin-product-card-v2";
  const baseMaterial = resolveAdminProductTextureMaterial(product);
  const visual = resolveDisplayVisual(
    baseMaterial,
    product.displayNameOverride,
    product.displayMaterial,
    product.displayIconPath,
    "",
    { category: "official" }
  );
  const visualLabel = visual.title || getLocalizedMaterialName(visual.material || baseMaterial || "");

  const header = document.createElement("div");
  header.className = "admin-card-hero";

  const identity = document.createElement("div");
  identity.className = "admin-card-identity";

  const materialTile = document.createElement("div");
  materialTile.className = "admin-material-tile";
  materialTile.appendChild(
    buildTextureImage(visual.material || baseMaterial, visualLabel, {
      forceIconPath: visual.forceIconPath,
      includeMaterialOverride: visual.includeMaterialOverride,
    })
  );
  identity.appendChild(materialTile);

  const identityText = document.createElement("div");
  identityText.className = "admin-card-identity-text";
  const title = document.createElement("strong");
  title.className = "admin-card-title";
  setNodeText(title, product.title || product.sku || "未命名商品");
  const sub = document.createElement("p");
  sub.className = "admin-card-subtitle";
  setNodeText(sub, `SKU ${product.sku || "-"} · ID ${product.id || "-"}`);
  identityText.appendChild(title);
  identityText.appendChild(sub);
  identity.appendChild(identityText);
  header.appendChild(identity);

  const status = document.createElement("span");
  status.className = `admin-order-status-v2 ${product.active ? "delivered" : "muted"}`;
  setNodeText(status, product.active ? "启用中" : "已停用");
  header.appendChild(status);
  card.appendChild(header);

  const primary = document.createElement("div");
  primary.className = "admin-order-primary";
  const amount = document.createElement("strong");
  amount.className = "admin-order-amount";
  setNodeText(amount, formatCurrency(product.price, product.currency));
  primary.appendChild(amount);
  const desc = document.createElement("p");
  desc.className = "admin-order-product";
  setNodeText(desc, product.remark || "暂无备注");
  primary.appendChild(desc);
  card.appendChild(primary);

  const tags = document.createElement("div");
  tags.className = "admin-tag-row";
  tags.appendChild(createTag(currencyName(product.currency), "accent"));
  tags.appendChild(createTag(product.productType || "-", "neutral"));
  tags.appendChild(
    createTag(
      (visual.material || product.itemMaterial)
        ? `${visual.material || baseMaterial} (${visualLabel})`
        : "无材质",
      "info"
    )
  );
  if (product.displayNameOverride || product.displayMaterial || product.displayIconPath) {
    tags.appendChild(createTag("含显示覆盖", "neutral"));
  }
  if (product.dynamicPricingEnabled) {
    tags.appendChild(createTag(getAlgorithmLabel("dynamic", product.dynamicAlgorithm || "-"), "success"));
  }
  card.appendChild(tags);

  const infoGrid = document.createElement("div");
  infoGrid.className = "admin-info-grid";
  infoGrid.appendChild(
    createInfoItem("库存", product.itemAmount != null ? `x${product.itemAmount}` : "长期供应")
  );
  infoGrid.appendChild(
    createInfoItem("剩余", product.stockRemaining != null ? `x${product.stockRemaining}` : "长期供应")
  );
  infoGrid.appendChild(
    createInfoItem("单人限购", product.perUserLimit != null ? `x${product.perUserLimit}` : "不限购", true)
  );
  infoGrid.appendChild(
    createInfoItem("上下架", `${product.publishAt ? formatDateTime(product.publishAt) : "立即"} / ${product.unpublishAt ? formatDateTime(product.unpublishAt) : "不下架"}`, true)
  );
  card.appendChild(infoGrid);

  if (actions.length > 0) {
    const actionWrap = document.createElement("div");
    actionWrap.className = "admin-actions";
    actions.forEach((action) => actionWrap.appendChild(action));
    card.appendChild(actionWrap);
  }
  return card;
}

function buildMarketStatusLabel(status) {
  const normalized = String(status || "").trim().toUpperCase();
  const labels = {
    ACTIVE: "在售",
    SOLD: "已售",
    UNLISTED: "已下架",
    SUPPLY_EMPTY: "库存不足",
  };
  return labels[normalized] || (normalized || "-");
}

function buildMarketStatusClass(status) {
  const normalized = String(status || "").trim().toUpperCase();
  if (normalized === "ACTIVE") {
    return "delivered";
  }
  if (normalized === "SOLD") {
    return "pending";
  }
  if (normalized === "UNLISTED") {
    return "muted";
  }
  return "default";
}

function renderAdminMarketCard(listing, actions = []) {
  const card = document.createElement("div");
  card.className = "admin-card admin-market-card-v2";

  const header = document.createElement("div");
  header.className = "admin-card-hero";

  const identity = document.createElement("div");
  identity.className = "admin-card-identity";
  const iconWrap = document.createElement("div");
  iconWrap.className = "market-icon";
  const visual = resolveDisplayVisual(
    listing.itemMaterial,
    listing.displayNameOverride,
    listing.displayMaterial,
    listing.displayIconPath,
    "",
    { category: "market" }
  );
  const materialLabel = visual.title || getLocalizedMaterialName(listing.itemMaterial || "");
  iconWrap.appendChild(
    buildTextureImage(visual.material, materialLabel, {
      forceIconPath: visual.forceIconPath,
      includeMaterialOverride: visual.includeMaterialOverride,
    })
  );
  identity.appendChild(iconWrap);

  const identityText = document.createElement("div");
  identityText.className = "admin-card-identity-text";
  const title = document.createElement("strong");
  title.className = "admin-card-title";
  setNodeText(title, `${materialLabel} x${Number(listing.quantity || 0)}`);
  const sub = document.createElement("p");
  sub.className = "admin-card-subtitle";
  setNodeText(sub, `上架 #${listing.id || "-"} · ${listing.createdAt || "-"}`);
  identityText.appendChild(title);
  identityText.appendChild(sub);
  identity.appendChild(identityText);
  header.appendChild(identity);

  const status = document.createElement("span");
  status.className = `admin-order-status-v2 ${buildMarketStatusClass(listing.status)}`;
  setNodeText(status, buildMarketStatusLabel(listing.status));
  header.appendChild(status);
  card.appendChild(header);

  const primary = document.createElement("div");
  primary.className = "admin-order-primary";
  const amount = document.createElement("strong");
  amount.className = "admin-order-amount";
  setNodeText(amount, formatCurrency(listing.price, listing.currency));
  primary.appendChild(amount);
  const tradeLine = document.createElement("p");
  tradeLine.className = "admin-order-product";
  setNodeText(
    tradeLine,
    `卖家 ${listing.sellerName || "-"} · 买家 ${listing.buyerName || "未成交"}`
  );
  primary.appendChild(tradeLine);
  card.appendChild(primary);

  const tags = document.createElement("div");
  tags.className = "admin-tag-row";
  tags.appendChild(createTag(currencyName(listing.currency || "SHOP_COIN"), "accent"));
  tags.appendChild(
    createTag(
      `${visual.material || listing.itemMaterial || "-"} (${materialLabel})`,
      "info"
    )
  );
  if (listing.displayNameOverride || listing.displayMaterial || listing.displayIconPath) {
    tags.appendChild(createTag("含显示覆盖", "neutral"));
  }
  if (listing.remark) {
    tags.appendChild(createTag("含备注", "neutral"));
  }
  card.appendChild(tags);

  const infoGrid = document.createElement("div");
  infoGrid.className = "admin-info-grid";
  infoGrid.appendChild(createInfoItem("卖家 UUID", listing.sellerUuid || "-", true));
  infoGrid.appendChild(createInfoItem("买家 UUID", listing.buyerUuid || "-", true));
  infoGrid.appendChild(createInfoItem("成交时间", listing.soldAt || "-", true));
  infoGrid.appendChild(createInfoItem("下架时间", listing.unlistedAt || "-", true));
  if (listing.remark) {
    infoGrid.appendChild(createInfoItem("备注", listing.remark, true));
  }
  card.appendChild(infoGrid);

  if (actions.length > 0) {
    const actionWrap = document.createElement("div");
    actionWrap.className = "admin-actions";
    actions.forEach((action) => actionWrap.appendChild(action));
    card.appendChild(actionWrap);
  }
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

function resolveDisplayVisual(baseMaterial, displayNameOverride, displayMaterial, displayIconPath, fallbackName = "", options = {}) {
  const policy = normalizeVisualPolicy(state.visualPolicy || {});
  const category = options.category === "official" ? "official" : "market";
  const categoryIconEnabled = category === "official"
    ? policy.officialProductCustomIconEnabled !== false
    : policy.marketListingCustomIconEnabled !== false;
  const categoryNameEnabled = category === "official"
    ? policy.officialProductCustomNameEnabled !== false
    : policy.marketListingCustomNameEnabled !== false;
  const baseKey = normalizeMaterialKey(baseMaterial);
  const globalVisual = getMaterialVisualOverride(baseKey);
  const customName = String(displayNameOverride || "").trim();
  const customMaterial = normalizeMaterialKey(displayMaterial);
  const customIconPath = String(displayIconPath || "").trim();
  const resolvedMaterial = customMaterial || baseKey || DEFAULT_TEXTURE_FALLBACK_MATERIAL;
  const fallback = String(fallbackName || "").trim();

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

function buildTextureAliases(material) {
  const key = normalizeMaterialKey(material);
  const aliasKey = aliasMaterialKey(key);
  const aliases = new Set();
  if (key) {
    aliases.add(key.toLowerCase());
  }
  if (aliasKey) {
    aliases.add(aliasKey.toLowerCase());
  }
  if (key.startsWith("LEGACY_")) {
    aliases.add(key.slice("LEGACY_".length).toLowerCase());
  }
  const override = MATERIAL_TEXTURE_OVERRIDES[key];
  if (Array.isArray(override)) {
    override.forEach((value) => aliases.add(String(value).toLowerCase()));
  }
  return Array.from(aliases);
}

function getFallbackTextureCandidates() {
  const names = buildTextureAliases(DEFAULT_TEXTURE_FALLBACK_MATERIAL);
  const candidates = [];
  names.forEach((textureName) => {
    candidates.push(`${LOCAL_TEXTURE_BASE}/item/${textureName}.png`);
    candidates.push(`${LOCAL_TEXTURE_BASE}/block/${textureName}.png`);
  });
  REMOTE_TEXTURE_BASES.forEach((base) => {
    names.forEach((textureName) => {
      candidates.push(`${base}/item/${textureName}.png`);
      candidates.push(`${base}/block/${textureName}.png`);
    });
  });
  candidates.push(getFallbackTexture());
  return candidates;
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
  names.forEach((textureName) => {
    candidates.push(`${LOCAL_TEXTURE_BASE}/item/${textureName}.png`);
    candidates.push(`${LOCAL_TEXTURE_BASE}/block/${textureName}.png`);
  });
  REMOTE_TEXTURE_BASES.forEach((base) => {
    names.forEach((textureName) => {
      candidates.push(`${base}/item/${textureName}.png`);
      candidates.push(`${base}/block/${textureName}.png`);
    });
  });
  getFallbackTextureCandidates().forEach((candidate) => {
    if (!candidates.includes(candidate)) {
      candidates.push(candidate);
    }
  });
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
  return state.materialMap[key] || state.materialMap[aliasKey] || humanizeMaterial(aliasKey || key);
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
      const rows = Array.isArray(json?.overrides) ? json.overrides : [];
      rows.forEach((item) => {
        const key = normalizeMaterialKey(item?.materialKey);
        if (!key) {
          return;
        }
        map[key] = {
          displayNameOverride: String(item?.displayNameOverride || "").trim() || null,
          iconPath: String(item?.iconPath || "").trim() || null,
        };
      });
      state.materialVisualMap = map;
      applyVisualPolicy(json?.policy || {});
      state.materialVisualMapReady = true;
    })
    .catch(() => {
      state.materialVisualMap = {};
      applyVisualPolicy({});
      state.materialVisualMapReady = true;
    });
  await state.materialVisualMapPromise;
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

function resolveMaterialInputLoose(raw) {
  const resolved = resolveMaterialInput(raw);
  if (resolved) {
    return resolved;
  }
  return normalizeMaterialKey(raw);
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
  state.materialMapPromise = Promise.all([
    ensureMaterialAllowList()
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
      }),
    ensureMaterialVisualMap(),
  ])
    .then(([json]) => {
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
  setProductFieldVisible(elements.productDisplayMaterial, itemVisible);
  setProductFieldVisible(elements.productDisplayNameOverride, itemVisible);
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

function resolveAdminProductTextureMaterial(product) {
  const itemMaterial = normalizeMaterialKey(product?.itemMaterial || "");
  if (itemMaterial) {
    return itemMaterial;
  }
  const productType = String(product?.productType || "").trim().toUpperCase();
  return PRODUCT_TYPE_TEXTURE_MAP[productType] || DEFAULT_TEXTURE_FALLBACK_MATERIAL;
}

function updateProductIconPreview() {
  if (!elements.productIconPreviewImage || !elements.productIconPreviewLabel) {
    return;
  }
  const draft = {
    itemMaterial: resolveMaterialInputLoose(elements.productItemMaterial?.value || ""),
    productType: String(elements.productType?.value || "COMMAND").trim().toUpperCase(),
    displayNameOverride: String(elements.productDisplayNameOverride?.value || "").trim() || null,
    displayMaterial: resolveMaterialInputLoose(elements.productDisplayMaterial?.value || ""),
    displayIconPath: String(elements.productDisplayIconPath?.value || "").trim() || null,
    title: String(elements.productTitle?.value || "").trim() || String(elements.productSku?.value || "").trim() || "未命名商品",
  };
  const baseMaterial = resolveAdminProductTextureMaterial(draft);
  const visual = resolveDisplayVisual(
    baseMaterial,
    draft.displayNameOverride,
    draft.displayMaterial,
    draft.displayIconPath,
    draft.title,
    { category: "official" }
  );
  const previewLabel = visual.title || draft.title || "未命名商品";
  setNodeText(
    elements.productIconPreviewLabel,
    `${previewLabel}${state.selectedProductId ? ` (#${state.selectedProductId})` : "（未保存）"}`
  );
  elements.productIconPreviewImage.src = resolveMaterialIconUrl(visual.forceIconPath)
    || getTextureCandidates(visual.material || baseMaterial, {
      forceIconPath: visual.forceIconPath,
      includeMaterialOverride: visual.includeMaterialOverride,
    })[0]
    || getFallbackTexture();
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
    displayNameOverride: String(elements.productDisplayNameOverride?.value || "").trim() || null,
    displayMaterial: resolveMaterialInputLoose(elements.productDisplayMaterial?.value || ""),
    displayIconPath: String(elements.productDisplayIconPath?.value || "").trim() || null,
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
  state.selectedProductId = Number(payload.id || 0) || state.selectedProductId;
  if (elements.productDisplayIconPath) {
    elements.productDisplayIconPath.value = payload.displayIconPath || "";
  }
  updateProductIconPreview();
  setMetaText(
    elements.productIconStatus,
    state.selectedProductId
      ? `商品已保存，可继续为 #${state.selectedProductId} 上传独立图标。`
      : "商品已保存。",
    "info"
  );
  setMetaText(elements.productStatus, `商品已保存：${payload.sku}`, "success");
  notify(`商品已保存：${payload.sku}`, "success");
  await loadProducts();
}

async function uploadProductIcon() {
  ensureAdmin();
  if (!state.selectedProductId) {
    throw new Error("请先保存商品，再上传独立图标。");
  }
  const file = elements.productIconFile?.files?.[0];
  if (!file) {
    throw new Error("请先选择图标文件。");
  }
  const croppedFile = await cropImageFileToSquarePng(file, 128);
  if (!croppedFile) {
    setMetaText(elements.productIconStatus, "已取消裁剪与上传", "info");
    return;
  }
  const query = new URLSearchParams({
    productId: String(state.selectedProductId),
    filename: croppedFile?.name || `product-${state.selectedProductId}.png`,
  });
  const payload = await apiAdminUpload(`/api/admin/products/icon?${query.toString()}`, croppedFile);
  state.selectedProductId = Number(payload.id || state.selectedProductId) || state.selectedProductId;
  if (elements.productDisplayIconPath) {
    elements.productDisplayIconPath.value = payload.displayIconPath || "";
  }
  updateProductIconPreview();
  setMetaText(elements.productIconStatus, `商品 #${state.selectedProductId} 图标上传成功`, "success");
  notify("商品图标上传成功", "success");
  await loadProducts();
}

async function clearProductIcon() {
  ensureAdmin();
  if (!state.selectedProductId) {
    if (elements.productDisplayIconPath) {
      elements.productDisplayIconPath.value = "";
    }
    updateProductIconPreview();
    setMetaText(elements.productIconStatus, "当前商品尚未保存，已清空本地预览。", "info");
    return;
  }
  if (!window.confirm(`确认清除商品 #${state.selectedProductId} 的自定义图标吗？`)) {
    return;
  }
  if (elements.productDisplayIconPath) {
    elements.productDisplayIconPath.value = "";
  }
  await saveProduct();
  setMetaText(elements.productIconStatus, `商品 #${state.selectedProductId} 自定义图标已清除`, "success");
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
    const visual = resolveDisplayVisual(
      product.itemMaterial,
      product.displayNameOverride,
      product.displayMaterial,
      product.displayIconPath,
      "",
      { category: "official" }
    );
    const materialName = visual.title || getLocalizedMaterialName(visual.material || product.itemMaterial || "");
    const haystack = [
      product.title || "",
      product.sku || "",
      product.itemMaterial || "",
      product.displayMaterial || "",
      product.displayNameOverride || "",
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
      state.selectedProductId = Number(product.id || 0) || null;
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
      if (elements.productDisplayMaterial) {
        elements.productDisplayMaterial.value = product.displayMaterial || "";
      }
      if (elements.productDisplayNameOverride) {
        elements.productDisplayNameOverride.value = product.displayNameOverride || "";
      }
      if (elements.productDisplayIconPath) {
        elements.productDisplayIconPath.value = product.displayIconPath || "";
      }
      if (elements.productIconFile) {
        elements.productIconFile.value = "";
      }
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
      updateProductIconPreview();
      setMetaText(elements.productIconStatus, `已绑定到商品 #${product.id}`, "info");
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
    return renderProductAdminCard(product, actions);
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
  updateProductIconPreview();
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
  const rows = orders.map((order) => renderOrderAdminCard(order));
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

  const currency = payload.currency || {};
  const shopCoinName = String(currency.shopCoinName || state.currencyMeta.SHOP_COIN?.name || "ShopCoin").trim();
  const shopCoinShort = String(currency.shopCoinShort || state.currencyMeta.SHOP_COIN?.short || "SC").trim();
  const gameCoinName = String(currency.gameCoinName || state.currencyMeta.GAME_COIN?.name || "GameCoin").trim();
  const gameCoinShort = String(currency.gameCoinShort || state.currencyMeta.GAME_COIN?.short || "GC").trim();
  state.currencyMeta.SHOP_COIN = { name: shopCoinName || "ShopCoin", short: shopCoinShort || "SC" };
  state.currencyMeta.GAME_COIN = { name: gameCoinName || "GameCoin", short: gameCoinShort || "GC" };
  if (elements.currencyShopCoinName) {
    elements.currencyShopCoinName.value = state.currencyMeta.SHOP_COIN.name;
  }
  if (elements.currencyShopCoinShort) {
    elements.currencyShopCoinShort.value = state.currencyMeta.SHOP_COIN.short;
  }
  if (elements.currencyGameCoinName) {
    elements.currencyGameCoinName.value = state.currencyMeta.GAME_COIN.name;
  }
  if (elements.currencyGameCoinShort) {
    elements.currencyGameCoinShort.value = state.currencyMeta.GAME_COIN.short;
  }
  applyCurrencyMetaToUi();

  const vault = payload.vault || {};
  const gameCoinLabel = currencyName("GAME_COIN");
  if (elements.vaultStatusView) {
    const provider = vault.provider || "未提供";
    if (vault.hooked) {
      setMetaText(
        elements.vaultStatusView,
        `已连接 Vault 经济：${provider}（${gameCoinLabel} 由 Vault 托管）`,
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
        `未检测到 Vault 插件，${gameCoinLabel} 当前使用本地钱包。`,
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

  const webshopRuntime = payload.webshopRuntime || {};
  if (elements.runtimeDefaultLocale) {
    elements.runtimeDefaultLocale.value = String(webshopRuntime.defaultLocale || "zh-CN");
  }
  if (elements.runtimeTimeZone) {
    elements.runtimeTimeZone.value = String(webshopRuntime.timeZone || "Asia/Shanghai");
  }
  if (elements.runtimeSessionExpireHours) {
    elements.runtimeSessionExpireHours.value = String(webshopRuntime.sessionExpireHours ?? 72);
  }
  if (elements.runtimeBindRequestExpireMinutes) {
    elements.runtimeBindRequestExpireMinutes.value = String(webshopRuntime.bindRequestExpireMinutes ?? 15);
  }
  if (elements.runtimeAccessTokenLength) {
    elements.runtimeAccessTokenLength.value = String(webshopRuntime.accessTokenLength ?? 48);
  }
  if (elements.runtimeDeliveryBatchSize) {
    elements.runtimeDeliveryBatchSize.value = String(webshopRuntime.deliveryBatchSize ?? 20);
  }
  if (elements.runtimeDeliveryRetrySeconds) {
    elements.runtimeDeliveryRetrySeconds.value = String(webshopRuntime.deliveryRetrySeconds ?? 30);
  }
  if (elements.runtimeOrderCooldownSeconds) {
    elements.runtimeOrderCooldownSeconds.value = String(webshopRuntime.orderCooldownSeconds ?? 15);
  }
  if (elements.runtimeAllowSharedClaimCommand) {
    elements.runtimeAllowSharedClaimCommand.value = String(!!webshopRuntime.allowSharedClaimCommand);
  }
  if (elements.runtimeRefundUndeliveredEnabled) {
    elements.runtimeRefundUndeliveredEnabled.value = String(webshopRuntime.refundUndeliveredEnabled !== false);
  }

  const marketRuntime = payload.marketRuntime || {};
  const marketSupply = marketRuntime.supply || {};
  if (elements.runtimeMarketMaxActiveListings) {
    elements.runtimeMarketMaxActiveListings.value = String(marketRuntime.marketMaxActiveListings ?? 10);
  }
  if (elements.runtimeMarketAutoRefreshThreshold) {
    elements.runtimeMarketAutoRefreshThreshold.value = String(marketSupply.autoRefreshThreshold ?? 8);
  }
  if (elements.runtimeMarketDefaultTransferBatchSize) {
    elements.runtimeMarketDefaultTransferBatchSize.value = String(marketSupply.defaultTransferBatchSize ?? 64);
  }
  if (elements.runtimeMarketMaxTransferBatchSize) {
    elements.runtimeMarketMaxTransferBatchSize.value = String(marketSupply.maxTransferBatchSize ?? 256);
  }
  if (elements.runtimeMarketDefaultTransitStock) {
    elements.runtimeMarketDefaultTransitStock.value = String(marketSupply.defaultTransitStock ?? 256);
  }
  if (elements.runtimeMarketMaxTransitStock) {
    elements.runtimeMarketMaxTransitStock.value = String(marketSupply.maxTransitStock ?? 1024);
  }

  const maintenance = payload.maintenance || {};
  if (elements.runtimeCleanupIntervalMinutes) {
    elements.runtimeCleanupIntervalMinutes.value = String(maintenance.cleanupIntervalMinutes ?? 30);
  }
  if (elements.runtimePendingBindRetentionHours) {
    elements.runtimePendingBindRetentionHours.value = String(maintenance.pendingBindRetentionHours ?? 6);
  }
  if (elements.runtimePendingPasswordRetentionHours) {
    elements.runtimePendingPasswordRetentionHours.value = String(maintenance.pendingPasswordRetentionHours ?? 6);
  }
  if (elements.runtimeBindRequestRetentionHours) {
    elements.runtimeBindRequestRetentionHours.value = String(maintenance.bindRequestRetentionHours ?? 24);
  }
  if (elements.runtimeRedeemCodeRetentionDays) {
    elements.runtimeRedeemCodeRetentionDays.value = String(maintenance.redeemCodeRetentionDays ?? 7);
  }

  const logging = payload.logging || {};
  if (elements.runtimeLoggingEnabled) {
    elements.runtimeLoggingEnabled.value = String(logging.enabled !== false);
  }
  if (elements.runtimeLoggingLevel) {
    elements.runtimeLoggingLevel.value = String(logging.level || "INFO").toUpperCase();
  }
  if (elements.runtimeLoggingDirectory) {
    elements.runtimeLoggingDirectory.value = String(logging.directory || "logs");
  }
  if (elements.runtimeLoggingMaxFileSizeMb) {
    elements.runtimeLoggingMaxFileSizeMb.value = String(logging.maxFileSizeMb ?? 8);
  }
  if (elements.runtimeLoggingMaxFiles) {
    elements.runtimeLoggingMaxFiles.value = String(logging.maxFiles ?? 8);
  }
  if (elements.runtimeLoggingRetentionDays) {
    elements.runtimeLoggingRetentionDays.value = String(logging.retentionDays ?? 14);
  }

  const broadcast = payload.broadcast || {};
  const templates = broadcast.templates || {};
  if (elements.runtimeBroadcastEnabled) {
    elements.runtimeBroadcastEnabled.value = String(broadcast.enabled !== false);
  }
  if (elements.runtimeBroadcastListingCreatedTemplate) {
    elements.runtimeBroadcastListingCreatedTemplate.value = String(templates["listing-created"] || "");
  }
  if (elements.runtimeBroadcastTradeSuccessTemplate) {
    elements.runtimeBroadcastTradeSuccessTemplate.value = String(templates["trade-success"] || "");
  }
  if (elements.runtimeBroadcastAuctionBidTemplate) {
    elements.runtimeBroadcastAuctionBidTemplate.value = String(templates["auction-bid"] || "");
  }
  if (elements.runtimeBroadcastAuctionSealedBidTemplate) {
    elements.runtimeBroadcastAuctionSealedBidTemplate.value = String(templates["auction-sealed-bid"] || "");
  }

  const visual = normalizeVisualPolicy(payload.visual || {});
  state.visualPolicy = visual;
  if (elements.visualGlobalCustomIconEnabled) {
    elements.visualGlobalCustomIconEnabled.value = String(visual.globalCustomIconEnabled !== false);
  }
  if (elements.visualGlobalCustomNameEnabled) {
    elements.visualGlobalCustomNameEnabled.value = String(visual.globalCustomNameEnabled !== false);
  }
  if (elements.visualOfficialProductCustomIconEnabled) {
    elements.visualOfficialProductCustomIconEnabled.value = String(visual.officialProductCustomIconEnabled !== false);
  }
  if (elements.visualOfficialProductCustomNameEnabled) {
    elements.visualOfficialProductCustomNameEnabled.value = String(visual.officialProductCustomNameEnabled !== false);
  }
  if (elements.visualOfficialProductUploadImageEnabled) {
    elements.visualOfficialProductUploadImageEnabled.value = String(visual.officialProductUploadImageEnabled !== false);
  }
  if (elements.visualMarketListingCustomIconEnabled) {
    elements.visualMarketListingCustomIconEnabled.value = String(visual.marketListingCustomIconEnabled !== false);
  }
  if (elements.visualMarketListingCustomNameEnabled) {
    elements.visualMarketListingCustomNameEnabled.value = String(visual.marketListingCustomNameEnabled !== false);
  }
  if (elements.visualMarketListingUploadImageEnabled) {
    elements.visualMarketListingUploadImageEnabled.value = String(visual.marketListingUploadImageEnabled !== false);
  }
  if (elements.visualIconPolicyMode) {
    elements.visualIconPolicyMode.value = String(visual.iconPolicyMode || "SOFT").toUpperCase();
  }
  if (elements.visualNamePolicyMode) {
    elements.visualNamePolicyMode.value = String(visual.namePolicyMode || "SOFT").toUpperCase();
  }

  try {
    await loadMaterialOverrideList();
  } catch (error) {
    setMetaText(elements.materialOverrideStatusView, `材质映射加载失败：${error.message}`, "error");
  }
  try {
    await loadMarketTagMeta();
  } catch (error) {
    setMetaText(elements.marketTagMetaStatusView, `标签加载失败：${error.message}`, "error");
  }
  try {
    await loadMarketPolicyConfigs({
      marketTagsConfig: payload.marketTagsConfig || null,
      marketLimitationConfig: payload.marketLimitationConfig || null,
    });
  } catch (error) {
    setMetaText(elements.marketTagConfigStatusView, `标签规则加载失败：${error.message}`, "error");
    setMetaText(elements.marketLimitationConfigStatusView, `上架限制加载失败：${error.message}`, "error");
  }

  setMetaText(elements.exchangeStatusView, "已加载兑换配置", "info");
  setMetaText(elements.marketEconomyStatusView, "已加载手续费/税率配置", "info");
  setMetaText(elements.leaderboardStatusView, "已加载排行榜配置", "info");
  setMetaText(elements.currencyStatusView, "已加载币种展示配置", "info");
  setMetaText(elements.runtimeWebshopStatusView, "已加载站点运行参数", "info");
  setMetaText(elements.runtimeMarketStatusView, "已加载市场运行参数", "info");
  setMetaText(elements.runtimeMaintenanceStatusView, "已加载维护参数", "info");
  setMetaText(elements.runtimeLoggingStatusView, "已加载日志设置", "info");
  setMetaText(elements.runtimeBroadcastStatusView, "已加载广播设置", "info");
  setMetaText(elements.visualSettingsStatusView, "已加载视觉策略", "info");
}

function normalizeMaterialOverrideRow(raw) {
  const materialKey = normalizeMaterialKey(raw?.materialKey);
  if (!materialKey) {
    return null;
  }
  return {
    materialKey,
    displayNameOverride: String(raw?.displayNameOverride || "").trim() || null,
    iconPath: String(raw?.iconPath || "").trim() || null,
    updatedBy: String(raw?.updatedBy || "").trim() || null,
    updatedAt: raw?.updatedAt || null,
  };
}

function upsertMaterialVisualInState(row) {
  const normalized = normalizeMaterialOverrideRow(row);
  if (!normalized) {
    return null;
  }
  state.materialVisualMap[normalized.materialKey] = {
    displayNameOverride: normalized.displayNameOverride,
    iconPath: normalized.iconPath,
  };
  return normalized;
}

function removeMaterialVisualFromState(materialKey) {
  const key = normalizeMaterialKey(materialKey);
  if (!key) {
    return;
  }
  delete state.materialVisualMap[key];
}

function setMaterialOverridePreview(materialKey, displayNameOverride, iconPath) {
  if (!elements.materialOverridePreviewImage || !elements.materialOverridePreviewLabel) {
    return;
  }
  const key = normalizeMaterialKey(materialKey);
  const fallbackLabel = key ? getLocalizedMaterialName(key) : localizeDisplayText("未选择材质");
  const labelText = String(displayNameOverride || "").trim() || fallbackLabel;
  setNodeText(elements.materialOverridePreviewLabel, `${labelText}${key ? ` (${key})` : ""}`);

  if (!key) {
    elements.materialOverridePreviewImage.src = getFallbackTexture();
    return;
  }

  const iconUrl = resolveMaterialIconUrl(iconPath || "");
  if (iconUrl) {
    elements.materialOverridePreviewImage.src = iconUrl;
    elements.materialOverridePreviewImage.onerror = () => {
      elements.materialOverridePreviewImage.src = getTextureCandidates(key)[0] || getFallbackTexture();
    };
    return;
  }
  elements.materialOverridePreviewImage.src = getTextureCandidates(key)[0] || getFallbackTexture();
}

function populateMaterialOverrideForm(row) {
  const normalized = normalizeMaterialOverrideRow(row);
  state.selectedMaterialOverrideKey = normalized?.materialKey || "";
  if (elements.materialOverrideMaterial) {
    elements.materialOverrideMaterial.value = normalized?.materialKey || "";
  }
  if (elements.materialOverrideDisplayName) {
    elements.materialOverrideDisplayName.value = normalized?.displayNameOverride || "";
  }
  if (elements.materialOverrideIconFile) {
    elements.materialOverrideIconFile.value = "";
  }
  if (elements.materialOverrideDeleteBtn) {
    elements.materialOverrideDeleteBtn.disabled = !normalized;
  }
  setMaterialOverridePreview(
    normalized?.materialKey || "",
    normalized?.displayNameOverride || "",
    normalized?.iconPath || ""
  );
}

function renderMaterialOverrideCard(row) {
  const normalized = normalizeMaterialOverrideRow(row);
  if (!normalized) {
    return null;
  }
  const card = document.createElement("div");
  card.className = "admin-card material-override-card";

  const header = document.createElement("div");
  header.className = "admin-row";
  const left = document.createElement("div");
  left.className = "material-override-card-head";
  const icon = document.createElement("img");
  icon.className = "material-override-icon";
  icon.alt = normalized.materialKey;
  icon.loading = "lazy";
  icon.decoding = "async";
  icon.src = resolveMaterialIconUrl(normalized.iconPath) || getTextureCandidates(normalized.materialKey)[0] || getFallbackTexture();
  icon.addEventListener("error", () => {
    icon.src = getTextureCandidates(normalized.materialKey)[0] || getFallbackTexture();
  });
  left.appendChild(icon);
  const titleWrap = document.createElement("div");
  const title = document.createElement("strong");
  setNodeText(title, normalized.displayNameOverride || getLocalizedMaterialName(normalized.materialKey));
  const sub = document.createElement("p");
  sub.className = "meta";
  setNodeText(sub, normalized.materialKey);
  titleWrap.appendChild(title);
  titleWrap.appendChild(sub);
  left.appendChild(titleWrap);
  header.appendChild(left);

  const actionWrap = document.createElement("div");
  actionWrap.className = "admin-actions";
  const editBtn = document.createElement("button");
  editBtn.className = "btn-tonal";
  setNodeText(editBtn, "编辑");
  editBtn.addEventListener("click", () => {
    populateMaterialOverrideForm(normalized);
  });
  const removeBtn = document.createElement("button");
  removeBtn.className = "btn-tonal";
  setNodeText(removeBtn, "删除");
  removeBtn.addEventListener("click", async () => {
    try {
      await deleteMaterialOverride(normalized.materialKey);
    } catch (error) {
      setMetaText(elements.materialOverrideStatusView, `删除失败：${error.message}`, "error");
      notify(`删除失败：${error.message}`, "error");
    }
  });
  actionWrap.appendChild(editBtn);
  actionWrap.appendChild(removeBtn);
  header.appendChild(actionWrap);
  card.appendChild(header);

  const rows = [
    { label: "显示名称", value: normalized.displayNameOverride || "跟随默认翻译" },
    { label: "图标来源", value: normalized.iconPath || "跟随原版材质" },
    { label: "更新人", value: normalized.updatedBy || "-" },
    { label: "更新时间", value: normalized.updatedAt || "-" },
  ];
  rows.forEach((item) => {
    const rowNode = document.createElement("div");
    rowNode.className = "admin-row";
    const key = document.createElement("span");
    key.className = "admin-key";
    setNodeText(key, item.label);
    const value = document.createElement("span");
    value.className = "admin-value";
    setNodeText(value, item.value);
    rowNode.appendChild(key);
    rowNode.appendChild(value);
    card.appendChild(rowNode);
  });

  return card;
}

async function loadMaterialOverrideList() {
  ensureAdmin();
  const keyword = String(elements.materialOverrideKeyword?.value || "").trim();
  const query = new URLSearchParams({ limit: "400" });
  if (keyword) {
    query.set("keyword", keyword);
  }
  const payload = await apiAdmin(`/api/admin/material-overrides/list?${query.toString()}`, { method: "GET" });
  const rows = (payload.overrides || [])
    .map((item) => normalizeMaterialOverrideRow(item))
    .filter(Boolean);
  state.materialOverrideRows = rows;
  state.materialVisualMap = {};
  rows.forEach((item) => upsertMaterialVisualInState(item));
  state.materialVisualMapReady = true;
  if (elements.materialOverrideList) {
    const cards = rows
      .map((item) => renderMaterialOverrideCard(item))
      .filter(Boolean);
    renderList(elements.materialOverrideList, cards);
  }
  setMetaText(elements.materialOverrideStatusView, `已加载 ${rows.length} 条材质映射`, "info");

  if (state.selectedMaterialOverrideKey) {
    const selected = rows.find((item) => item.materialKey === state.selectedMaterialOverrideKey) || null;
    if (selected) {
      populateMaterialOverrideForm(selected);
    } else {
      populateMaterialOverrideForm(null);
    }
  }
}

async function saveMaterialOverride() {
  ensureAdmin();
  const materialKey = resolveMaterialInputLoose(elements.materialOverrideMaterial?.value || "");
  if (!materialKey) {
    throw new Error("请先输入材质名称。");
  }
  const displayNameOverride = String(elements.materialOverrideDisplayName?.value || "").trim() || null;
  let iconPath = null;
  const selected = state.materialOverrideRows.find((item) => item.materialKey === materialKey);
  if (selected?.iconPath) {
    iconPath = selected.iconPath;
  } else if (state.selectedMaterialOverrideKey) {
    const selectedByCurrent = state.materialOverrideRows.find(
      (item) => item.materialKey === state.selectedMaterialOverrideKey
    );
    if (selectedByCurrent?.materialKey === materialKey && selectedByCurrent.iconPath) {
      iconPath = selectedByCurrent.iconPath;
    }
  }
  if (!displayNameOverride && !iconPath) {
    throw new Error("显示名称和图标不能同时为空。");
  }
  const payload = await apiAdmin("/api/admin/material-overrides/upsert", {
    method: "POST",
    body: JSON.stringify({
      materialKey,
      displayNameOverride,
      iconPath,
    }),
  });
  const row = upsertMaterialVisualInState(payload);
  state.materialVisualMapReady = true;
  state.selectedMaterialOverrideKey = row?.materialKey || materialKey;
  setMetaText(elements.materialOverrideStatusView, "材质映射已保存", "success");
  notify("材质映射已保存", "success");
  await loadMaterialOverrideList();
}

async function uploadMaterialOverrideIcon() {
  ensureAdmin();
  const materialKey = resolveMaterialInputLoose(elements.materialOverrideMaterial?.value || "");
  if (!materialKey) {
    throw new Error("请先输入材质名称。");
  }
  const file = elements.materialOverrideIconFile?.files?.[0];
  if (!file) {
    throw new Error("请先选择图标文件。");
  }
  const croppedFile = await cropImageFileToSquarePng(file, 128);
  if (!croppedFile) {
    setMetaText(elements.materialOverrideStatusView, "已取消裁剪与上传", "info");
    return;
  }
  const query = new URLSearchParams({
    material: materialKey,
    filename: croppedFile?.name || `${materialKey}.png`,
  });
  const payload = await apiAdminUpload(`/api/admin/material-overrides/icon?${query.toString()}`, croppedFile);
  const row = upsertMaterialVisualInState(payload);
  state.materialVisualMapReady = true;
  state.selectedMaterialOverrideKey = row?.materialKey || materialKey;
  setMetaText(elements.materialOverrideStatusView, "材质图标上传成功", "success");
  notify("材质图标上传成功", "success");
  await loadMaterialOverrideList();
  const latest = state.materialOverrideRows.find((item) => item.materialKey === materialKey) || row || null;
  populateMaterialOverrideForm(latest);
}

async function deleteMaterialOverride(materialKeyFromAction = "") {
  ensureAdmin();
  const materialKey = normalizeMaterialKey(materialKeyFromAction || elements.materialOverrideMaterial?.value || "");
  if (!materialKey) {
    throw new Error("请先选择要删除的材质映射。");
  }
  await apiAdmin("/api/admin/material-overrides/delete", {
    method: "POST",
    body: JSON.stringify({ materialKey }),
  });
  removeMaterialVisualFromState(materialKey);
  state.selectedMaterialOverrideKey = "";
  populateMaterialOverrideForm(null);
  setMetaText(elements.materialOverrideStatusView, `已删除材质映射：${materialKey}`, "success");
  notify(`已删除材质映射：${materialKey}`, "success");
  await loadMaterialOverrideList();
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

async function saveCurrencyDisplaySettings() {
  ensureAdmin();
  const shopCoinName = String(elements.currencyShopCoinName?.value || "").trim();
  const shopCoinShort = String(elements.currencyShopCoinShort?.value || "").trim();
  const gameCoinName = String(elements.currencyGameCoinName?.value || "").trim();
  const gameCoinShort = String(elements.currencyGameCoinShort?.value || "").trim();
  if (!shopCoinName || !shopCoinShort || !gameCoinName || !gameCoinShort) {
    throw new Error("币种名称与简称不能为空。");
  }
  await apiAdmin("/api/admin/economy/currency", {
    method: "POST",
    body: JSON.stringify({
      shopCoinName,
      shopCoinShort,
      gameCoinName,
      gameCoinShort,
    }),
  });
  state.currencyMeta.SHOP_COIN = { name: shopCoinName, short: shopCoinShort };
  state.currencyMeta.GAME_COIN = { name: gameCoinName, short: gameCoinShort };
  applyCurrencyMetaToUi();
  setMetaText(elements.currencyStatusView, "币种展示配置已保存", "success");
  notify("币种展示配置已保存", "success");
}

async function saveWebshopRuntimeSettings() {
  ensureAdmin();
  await apiAdmin("/api/admin/system/webshop", {
    method: "POST",
    body: JSON.stringify({
      defaultLocale: String(elements.runtimeDefaultLocale?.value || "zh-CN"),
      timeZone: String(elements.runtimeTimeZone?.value || "Asia/Shanghai").trim(),
      sessionExpireHours: Number(elements.runtimeSessionExpireHours?.value || 72),
      bindRequestExpireMinutes: Number(elements.runtimeBindRequestExpireMinutes?.value || 15),
      accessTokenLength: Number(elements.runtimeAccessTokenLength?.value || 48),
      deliveryBatchSize: Number(elements.runtimeDeliveryBatchSize?.value || 20),
      deliveryRetrySeconds: Number(elements.runtimeDeliveryRetrySeconds?.value || 30),
      orderCooldownSeconds: Number(elements.runtimeOrderCooldownSeconds?.value || 15),
      allowSharedClaimCommand: elements.runtimeAllowSharedClaimCommand?.value === "true",
      refundUndeliveredEnabled: elements.runtimeRefundUndeliveredEnabled?.value === "true",
    }),
  });
  setMetaText(elements.runtimeWebshopStatusView, "站点运行参数已保存", "success");
  notify("站点运行参数已保存", "success");
}

async function saveMarketRuntimeSettings() {
  ensureAdmin();
  await apiAdmin("/api/admin/system/market", {
    method: "POST",
    body: JSON.stringify({
      marketMaxActiveListings: Number(elements.runtimeMarketMaxActiveListings?.value || 10),
      autoRefreshThreshold: Number(elements.runtimeMarketAutoRefreshThreshold?.value || 8),
      defaultTransferBatchSize: Number(elements.runtimeMarketDefaultTransferBatchSize?.value || 64),
      maxTransferBatchSize: Number(elements.runtimeMarketMaxTransferBatchSize?.value || 256),
      defaultTransitStock: Number(elements.runtimeMarketDefaultTransitStock?.value || 256),
      maxTransitStock: Number(elements.runtimeMarketMaxTransitStock?.value || 1024),
    }),
  });
  setMetaText(elements.runtimeMarketStatusView, "市场运行参数已保存", "success");
  notify("市场运行参数已保存", "success");
}

async function saveMaintenanceSettings() {
  ensureAdmin();
  await apiAdmin("/api/admin/system/maintenance", {
    method: "POST",
    body: JSON.stringify({
      cleanupIntervalMinutes: Number(elements.runtimeCleanupIntervalMinutes?.value || 30),
      pendingBindRetentionHours: Number(elements.runtimePendingBindRetentionHours?.value || 6),
      pendingPasswordRetentionHours: Number(elements.runtimePendingPasswordRetentionHours?.value || 6),
      bindRequestRetentionHours: Number(elements.runtimeBindRequestRetentionHours?.value || 24),
      redeemCodeRetentionDays: Number(elements.runtimeRedeemCodeRetentionDays?.value || 7),
    }),
  });
  setMetaText(elements.runtimeMaintenanceStatusView, "维护参数已保存", "success");
  notify("维护参数已保存", "success");
}

async function saveLoggingSettings() {
  ensureAdmin();
  await apiAdmin("/api/admin/system/logging", {
    method: "POST",
    body: JSON.stringify({
      enabled: elements.runtimeLoggingEnabled?.value === "true",
      level: String(elements.runtimeLoggingLevel?.value || "INFO").toUpperCase(),
      directory: String(elements.runtimeLoggingDirectory?.value || "logs").trim(),
      maxFileSizeMb: Number(elements.runtimeLoggingMaxFileSizeMb?.value || 8),
      maxFiles: Number(elements.runtimeLoggingMaxFiles?.value || 8),
      retentionDays: Number(elements.runtimeLoggingRetentionDays?.value || 14),
    }),
  });
  setMetaText(elements.runtimeLoggingStatusView, "日志设置已保存", "success");
  notify("日志设置已保存", "success");
}

async function saveBroadcastSettings() {
  ensureAdmin();
  await apiAdmin("/api/admin/system/broadcast", {
    method: "POST",
    body: JSON.stringify({
      enabled: elements.runtimeBroadcastEnabled?.value === "true",
      listingCreatedTemplate: String(elements.runtimeBroadcastListingCreatedTemplate?.value || ""),
      tradeSuccessTemplate: String(elements.runtimeBroadcastTradeSuccessTemplate?.value || ""),
      auctionBidTemplate: String(elements.runtimeBroadcastAuctionBidTemplate?.value || ""),
      auctionSealedBidTemplate: String(elements.runtimeBroadcastAuctionSealedBidTemplate?.value || ""),
    }),
  });
  setMetaText(elements.runtimeBroadcastStatusView, "广播设置已保存", "success");
  notify("广播设置已保存", "success");
}

async function saveVisualSettings() {
  ensureAdmin();
  const payload = await apiAdmin("/api/admin/visual/settings", {
    method: "POST",
    body: JSON.stringify({
      globalCustomIconEnabled: elements.visualGlobalCustomIconEnabled?.value === "true",
      globalCustomNameEnabled: elements.visualGlobalCustomNameEnabled?.value === "true",
      officialProductCustomIconEnabled: elements.visualOfficialProductCustomIconEnabled?.value !== "false",
      officialProductCustomNameEnabled: elements.visualOfficialProductCustomNameEnabled?.value !== "false",
      officialProductUploadImageEnabled: elements.visualOfficialProductUploadImageEnabled?.value !== "false",
      marketListingCustomIconEnabled: elements.visualMarketListingCustomIconEnabled?.value !== "false",
      marketListingCustomNameEnabled: elements.visualMarketListingCustomNameEnabled?.value !== "false",
      marketListingUploadImageEnabled: elements.visualMarketListingUploadImageEnabled?.value !== "false",
      iconPolicyMode: String(elements.visualIconPolicyMode?.value || "SOFT").toUpperCase(),
      namePolicyMode: String(elements.visualNamePolicyMode?.value || "SOFT").toUpperCase(),
    }),
  });
  state.visualPolicy = normalizeVisualPolicy(payload.visual || {});
  setMetaText(elements.visualSettingsStatusView, "视觉策略已保存", "success");
  notify("视觉策略已保存", "success");
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
    return renderAdminMarketCard(listing, actions);
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
  loadSelectedUserVisualPermission().catch((error) => {
    setMetaText(elements.userVisualPermissionStatus, `读取视觉权限失败：${error.message}`, "error");
  });
}

function formatListingLimitSource(source) {
  const normalized = String(source || "").trim().toUpperCase();
  if (normalized === "USER_OVERRIDE") {
    return "用户覆盖";
  }
  if (normalized === "PERMISSION_NODE") {
    return "权限节点";
  }
  return "全局默认";
}

async function loadSelectedUserVisualPermission() {
  if (!state.selectedUser || !state.selectedUser.id) {
    if (elements.userVisualIconPermission) {
      elements.userVisualIconPermission.value = "INHERIT";
    }
    if (elements.userVisualNamePermission) {
      elements.userVisualNamePermission.value = "INHERIT";
    }
    if (elements.userVisualUploadPermission) {
      elements.userVisualUploadPermission.value = "INHERIT";
    }
    if (elements.userListingLimitOverride) {
      elements.userListingLimitOverride.value = "";
    }
    setMetaText(elements.userVisualPermissionStatus, "请先选择用户", "info");
    setMetaText(elements.userListingLimitStatus, "请先选择用户", "info");
    return;
  }
  const payload = await apiAdmin(`/api/admin/users/visual-permission?userId=${encodeURIComponent(state.selectedUser.id)}`, {
    method: "GET",
  });
  if (elements.userVisualIconPermission) {
    elements.userVisualIconPermission.value = String(payload.iconPermission || "INHERIT").toUpperCase();
  }
  if (elements.userVisualNamePermission) {
    elements.userVisualNamePermission.value = String(payload.namePermission || "INHERIT").toUpperCase();
  }
  if (elements.userVisualUploadPermission) {
    elements.userVisualUploadPermission.value = String(payload.uploadPermission || "INHERIT").toUpperCase();
  }
  if (elements.userListingLimitOverride) {
    elements.userListingLimitOverride.value = payload.listingLimitOverride == null ? "" : String(payload.listingLimitOverride);
  }
  const iconAllowed = payload.customIconAllowed ? "允许" : "禁止";
  const nameAllowed = payload.customNameAllowed ? "允许" : "禁止";
  const uploadAllowed = payload.customUploadAllowed ? "允许" : "禁止";
  setMetaText(
    elements.userVisualPermissionStatus,
    `已加载：图标${iconAllowed} / 名称${nameAllowed} / 上传${uploadAllowed}`,
    "info"
  );
  setMetaText(
    elements.userListingLimitStatus,
    `当前生效上限 ${payload.listingLimitEffective || "-"}，来源：${formatListingLimitSource(payload.listingLimitSource)}`
      + (payload.permissionLimit ? `，权限节点 ${payload.permissionLimit}` : "")
      + `，全局默认 ${payload.globalDefaultLimit || "-"}`,
    "info"
  );
}

async function saveSelectedUserVisualPermission() {
  ensureAdmin();
  const userId = requireSelectedUser();
  const payload = await apiAdmin("/api/admin/users/visual-permission", {
    method: "POST",
    body: JSON.stringify({
      userId,
      iconPermission: String(elements.userVisualIconPermission?.value || "INHERIT").toUpperCase(),
      namePermission: String(elements.userVisualNamePermission?.value || "INHERIT").toUpperCase(),
      uploadPermission: String(elements.userVisualUploadPermission?.value || "INHERIT").toUpperCase(),
      listingLimitOverride: String(elements.userListingLimitOverride?.value || "").trim()
        ? Number(elements.userListingLimitOverride.value)
        : null,
    }),
  });
  const iconAllowed = payload.customIconAllowed ? "允许" : "禁止";
  const nameAllowed = payload.customNameAllowed ? "允许" : "禁止";
  const uploadAllowed = payload.customUploadAllowed ? "允许" : "禁止";
  setMetaText(
    elements.userVisualPermissionStatus,
    `保存成功：图标${iconAllowed} / 名称${nameAllowed} / 上传${uploadAllowed}`,
    "success"
  );
  setMetaText(
    elements.userListingLimitStatus,
    `当前生效上限 ${payload.listingLimitEffective || "-"}，来源：${formatListingLimitSource(payload.listingLimitSource)}`,
    "success"
  );
  notify("用户视觉权限已保存", "success");
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
if (elements.productIconUploadBtn) {
  elements.productIconUploadBtn.addEventListener("click", async () => {
    try {
      await uploadProductIcon();
    } catch (error) {
      setMetaText(elements.productIconStatus, `上传失败：${error.message}`, "error");
      notify(`上传失败：${error.message}`, "error");
    }
  });
}
if (elements.productIconClearBtn) {
  elements.productIconClearBtn.addEventListener("click", async () => {
    try {
      await clearProductIcon();
    } catch (error) {
      setMetaText(elements.productIconStatus, `清除失败：${error.message}`, "error");
      notify(`清除失败：${error.message}`, "error");
    }
  });
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

if (elements.leaderboardSaveBtn) {
  elements.leaderboardSaveBtn.addEventListener("click", async () => {
    try {
      await saveLeaderboardSettings();
    } catch (error) {
      setMetaText(elements.leaderboardStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.currencySaveBtn) {
  elements.currencySaveBtn.addEventListener("click", async () => {
    try {
      await saveCurrencyDisplaySettings();
    } catch (error) {
      setMetaText(elements.currencyStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.runtimeWebshopSaveBtn) {
  elements.runtimeWebshopSaveBtn.addEventListener("click", async () => {
    try {
      await saveWebshopRuntimeSettings();
    } catch (error) {
      setMetaText(elements.runtimeWebshopStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.runtimeMarketSaveBtn) {
  elements.runtimeMarketSaveBtn.addEventListener("click", async () => {
    try {
      await saveMarketRuntimeSettings();
    } catch (error) {
      setMetaText(elements.runtimeMarketStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.marketTagRefreshBtn) {
  elements.marketTagRefreshBtn.addEventListener("click", async () => {
    try {
      await loadMarketTagMeta({ announce: true });
    } catch (error) {
      setMetaText(elements.marketTagMetaStatusView, `标签加载失败：${error.message}`, "error");
      notify(`标签加载失败：${error.message}`, "error");
    }
  });
}

if (elements.marketLimitationRefreshBtn) {
  elements.marketLimitationRefreshBtn.addEventListener("click", async () => {
    try {
      await loadMarketPolicyConfigs({ marketTagsConfig: state.marketTagConfig || {} });
      notify("上架限制已刷新。", "success");
    } catch (error) {
      setMetaText(elements.marketLimitationConfigStatusView, `上架限制加载失败：${error.message}`, "error");
      notify(`上架限制加载失败：${error.message}`, "error");
    }
  });
}

if (elements.marketTagAddRowBtn) {
  elements.marketTagAddRowBtn.addEventListener("click", () => {
    addMarketTagConfigRow();
  });
}

if (elements.marketLimitationAddRuleBtn) {
  elements.marketLimitationAddRuleBtn.addEventListener("click", () => {
    addMarketLimitationRule();
  });
}

if (elements.marketTagEditCancelBtn) {
  elements.marketTagEditCancelBtn.addEventListener("click", () => {
    closeMarketTagEditDialog();
  });
}

if (elements.marketTagEditSaveBtn) {
  elements.marketTagEditSaveBtn.addEventListener("click", () => {
    try {
      saveMarketTagEditDialog();
      notify("标签已更新，请记得点击“保存标签规则”提交到数据库。", "success");
    } catch (error) {
      notify(`标签保存失败：${error.message}`, "error");
    }
  });
}

if (elements.marketTagEditDialog) {
  elements.marketTagEditDialog.addEventListener("click", (event) => {
    if (event.target === elements.marketTagEditDialog) {
      closeMarketTagEditDialog();
    }
  });
}

if (elements.marketRuleEditCancelBtn) {
  elements.marketRuleEditCancelBtn.addEventListener("click", () => {
    closeMarketLimitationRuleEditDialog();
  });
}

if (elements.marketRuleEditSaveBtn) {
  elements.marketRuleEditSaveBtn.addEventListener("click", () => {
    try {
      saveMarketLimitationRuleEditDialog();
      notify("限制规则已更新，请记得点击“保存上架限制”提交到数据库。", "success");
    } catch (error) {
      notify(`规则保存失败：${error.message}`, "error");
    }
  });
}

if (elements.marketLimitationRuleEditDialog) {
  elements.marketLimitationRuleEditDialog.addEventListener("click", (event) => {
    if (event.target === elements.marketLimitationRuleEditDialog) {
      closeMarketLimitationRuleEditDialog();
    }
  });
}

if (elements.marketTagVersionInput) {
  elements.marketTagVersionInput.addEventListener("input", () => {
    if (!state.marketTagConfig || typeof state.marketTagConfig !== "object") {
      state.marketTagConfig = normalizeMarketTagConfig({});
    }
    state.marketTagConfig.tagVersion = Math.max(1, Number(elements.marketTagVersionInput.value || 1) || 1);
    syncMarketPolicyJsonEditors();
  });
}

if (elements.marketTagDefaultTagSelect) {
  elements.marketTagDefaultTagSelect.addEventListener("change", () => {
    if (!state.marketTagConfig || typeof state.marketTagConfig !== "object") {
      state.marketTagConfig = normalizeMarketTagConfig({});
    }
    state.marketTagConfig.defaultTag = normalizeTagCodeValue(elements.marketTagDefaultTagSelect.value || "");
    syncMarketPolicyJsonEditors();
  });
}

[
  elements.marketLimitationDefaultDenySidesInput,
  elements.marketLimitationDefaultDenyCurrenciesInput,
  elements.marketLimitationDefaultAllowSidesInput,
  elements.marketLimitationDefaultAllowTradeModesInput,
  elements.marketLimitationDefaultAllowCurrenciesInput,
  elements.marketLimitationDefaultAllowTagsInput,
  elements.marketLimitationDefaultCreateCostEnabled,
  elements.marketLimitationDefaultCreateCostCurrency,
  elements.marketLimitationDefaultCreateCostAmount,
].filter((node) => !!node).forEach((node) => {
  const eventName = node.tagName === "SELECT" ? "change" : "input";
  node.addEventListener(eventName, () => {
    updateMarketLimitationDefaultsStateFromInputs();
    syncMarketPolicyJsonEditors();
    setMetaText(
      elements.marketLimitationSummaryView,
      buildMarketLimitationSummary(buildMarketLimitationConfigPayloadFromState()),
      "info"
    );
  });
});

if (elements.marketTagJsonApplyBtn) {
  elements.marketTagJsonApplyBtn.addEventListener("click", () => {
    try {
      applyMarketTagJsonToVisual();
      notify("标签 JSON 已应用到可视化表单", "success");
    } catch (error) {
      setMetaText(elements.marketTagConfigStatusView, `应用失败：${error.message}`, "error");
      notify(`应用失败：${error.message}`, "error");
    }
  });
}

if (elements.marketLimitationJsonApplyBtn) {
  elements.marketLimitationJsonApplyBtn.addEventListener("click", () => {
    try {
      applyMarketLimitationJsonToVisual();
      notify("上架限制 JSON 已应用到可视化表单", "success");
    } catch (error) {
      setMetaText(elements.marketLimitationConfigStatusView, `应用失败：${error.message}`, "error");
      notify(`应用失败：${error.message}`, "error");
    }
  });
}

if (elements.marketTagConfigSaveBtn) {
  elements.marketTagConfigSaveBtn.addEventListener("click", async () => {
    try {
      await saveMarketTagConfig();
    } catch (error) {
      setMetaText(elements.marketTagConfigStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.marketLimitationConfigSaveBtn) {
  elements.marketLimitationConfigSaveBtn.addEventListener("click", async () => {
    try {
      await saveMarketLimitationConfig();
    } catch (error) {
      setMetaText(elements.marketLimitationConfigStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.runtimeMaintenanceSaveBtn) {
  elements.runtimeMaintenanceSaveBtn.addEventListener("click", async () => {
    try {
      await saveMaintenanceSettings();
    } catch (error) {
      setMetaText(elements.runtimeMaintenanceStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.runtimeLoggingSaveBtn) {
  elements.runtimeLoggingSaveBtn.addEventListener("click", async () => {
    try {
      await saveLoggingSettings();
    } catch (error) {
      setMetaText(elements.runtimeLoggingStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.runtimeBroadcastSaveBtn) {
  elements.runtimeBroadcastSaveBtn.addEventListener("click", async () => {
    try {
      await saveBroadcastSettings();
    } catch (error) {
      setMetaText(elements.runtimeBroadcastStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.visualSettingsSaveBtn) {
  elements.visualSettingsSaveBtn.addEventListener("click", async () => {
    try {
      await saveVisualSettings();
    } catch (error) {
      setMetaText(elements.visualSettingsStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.materialOverrideRefreshBtn) {
  elements.materialOverrideRefreshBtn.addEventListener("click", async () => {
    try {
      await loadMaterialOverrideList();
      notify("材质映射列表已刷新", "success");
    } catch (error) {
      setMetaText(elements.materialOverrideStatusView, `加载失败：${error.message}`, "error");
      notify(`加载失败：${error.message}`, "error");
    }
  });
}

if (elements.materialOverrideSaveBtn) {
  elements.materialOverrideSaveBtn.addEventListener("click", async () => {
    try {
      await saveMaterialOverride();
    } catch (error) {
      setMetaText(elements.materialOverrideStatusView, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

if (elements.materialOverrideUploadBtn) {
  elements.materialOverrideUploadBtn.addEventListener("click", async () => {
    try {
      await uploadMaterialOverrideIcon();
    } catch (error) {
      setMetaText(elements.materialOverrideStatusView, `上传失败：${error.message}`, "error");
      notify(`上传失败：${error.message}`, "error");
    }
  });
}

if (elements.materialOverrideDeleteBtn) {
  elements.materialOverrideDeleteBtn.addEventListener("click", async () => {
    try {
      await deleteMaterialOverride();
    } catch (error) {
      setMetaText(elements.materialOverrideStatusView, `删除失败：${error.message}`, "error");
      notify(`删除失败：${error.message}`, "error");
    }
  });
}

if (elements.materialOverrideClearBtn) {
  elements.materialOverrideClearBtn.addEventListener("click", () => {
    populateMaterialOverrideForm(null);
    setMetaText(elements.materialOverrideStatusView, "已清空编辑器", "info");
  });
}

if (elements.materialOverrideKeyword) {
  elements.materialOverrideKeyword.addEventListener("keydown", async (event) => {
    if (event.key !== "Enter" || !state.token) {
      return;
    }
    await loadMaterialOverrideList();
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

if (elements.userVisualPermissionSaveBtn) {
  elements.userVisualPermissionSaveBtn.addEventListener("click", async () => {
    try {
      await saveSelectedUserVisualPermission();
    } catch (error) {
      setMetaText(elements.userVisualPermissionStatus, `保存失败：${error.message}`, "error");
      notify(`保存失败：${error.message}`, "error");
    }
  });
}

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
    updateProductIconPreview();
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
    updateProductIconPreview();
  });
}
if (elements.productDisplayMaterial) {
  elements.productDisplayMaterial.addEventListener("blur", () => {
    const raw = String(elements.productDisplayMaterial.value || "").trim();
    if (!raw) {
      return;
    }
    const resolved = resolveMaterialInputLoose(raw);
    if (resolved) {
      elements.productDisplayMaterial.value = resolved;
      if (!resolveMaterialInput(raw)) {
        notify("展示材质未在基础材质表中识别，已按输入规范化后保留。", "warn");
      }
    } else {
      notify("展示材质格式无效，请检查输入。", "warn");
    }
    updateProductIconPreview();
  });
}
if (elements.productDisplayNameOverride) {
  elements.productDisplayNameOverride.addEventListener("input", () => updateProductIconPreview());
}
if (elements.productTitle) {
  elements.productTitle.addEventListener("input", () => updateProductIconPreview());
}
if (elements.productSku) {
  elements.productSku.addEventListener("input", () => updateProductIconPreview());
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
if (elements.materialOverrideMaterial) {
  elements.materialOverrideMaterial.addEventListener("blur", () => {
    const raw = String(elements.materialOverrideMaterial.value || "").trim();
    if (!raw) {
      return;
    }
    const resolved = resolveMaterialInputLoose(raw);
    if (resolved) {
      elements.materialOverrideMaterial.value = resolved;
      const existing = state.materialOverrideRows.find((item) => item.materialKey === resolved) || null;
      if (existing) {
        populateMaterialOverrideForm(existing);
      } else {
        setMaterialOverridePreview(resolved, elements.materialOverrideDisplayName?.value || "", "");
      }
      if (!resolveMaterialInput(raw)) {
        notify("材质未在基础材质表中识别，仍可保存（适用于模组/混合服）。", "warn");
      }
    } else {
      notify("材质格式无效，请检查输入。", "warn");
    }
  });
}
if (elements.materialOverrideDisplayName) {
  elements.materialOverrideDisplayName.addEventListener("input", () => {
    const materialKey = resolveMaterialInput(elements.materialOverrideMaterial?.value || "");
    const existing = state.materialOverrideRows.find((item) => item.materialKey === materialKey) || null;
    setMaterialOverridePreview(
      materialKey,
      elements.materialOverrideDisplayName.value,
      existing?.iconPath || ""
    );
  });
}
if (elements.materialOverrideIconFile) {
  elements.materialOverrideIconFile.addEventListener("change", () => {
    const file = elements.materialOverrideIconFile.files?.[0];
    if (!file || !elements.materialOverridePreviewImage) {
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === "string") {
        elements.materialOverridePreviewImage.src = reader.result;
      }
    };
    reader.readAsDataURL(file);
  });
}
updateProductTypeFieldsVisibility(elements.productType ? elements.productType.value : "COMMAND");
setProductPanel("editor");
syncProductAmountSlider("input");
switchProductDynamicParamTab("basic");
renderProductDynamicParamEditors();
populateMaterialOverrideForm(null);
initializeMaterialCropDialog();

applyCurrencyMetaToUi();
loadCurrencyMeta();
ensureMaterialMap();
ensureMarketAlgorithmGlossary()
  .then(() => {
    populateProductDynamicAlgorithmSelect();
    renderProductDynamicParamEditors();
    updateProductIconPreview();
  })
  .catch(() => {
    populateProductDynamicAlgorithmSelect();
    renderProductDynamicParamEditors();
    updateProductIconPreview();
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
if (elements.currencyStatusView) {
  setMetaText(elements.currencyStatusView, "等待操作", "info");
}
if (elements.runtimeWebshopStatusView) {
  setMetaText(elements.runtimeWebshopStatusView, "等待操作", "info");
}
if (elements.runtimeMarketStatusView) {
  setMetaText(elements.runtimeMarketStatusView, "等待操作", "info");
}
if (elements.marketTagMetaStatusView) {
  setMetaText(elements.marketTagMetaStatusView, "等待加载标签", "info");
}
if (elements.marketLimitationSummaryView) {
  setMetaText(elements.marketLimitationSummaryView, "等待加载限制摘要", "info");
}
if (elements.marketTagConfigStatusView) {
  setMetaText(elements.marketTagConfigStatusView, "等待加载标签规则", "info");
}
if (elements.marketLimitationConfigStatusView) {
  setMetaText(elements.marketLimitationConfigStatusView, "等待加载上架限制", "info");
}
if (elements.runtimeMaintenanceStatusView) {
  setMetaText(elements.runtimeMaintenanceStatusView, "等待操作", "info");
}
if (elements.runtimeLoggingStatusView) {
  setMetaText(elements.runtimeLoggingStatusView, "等待操作", "info");
}
if (elements.runtimeBroadcastStatusView) {
  setMetaText(elements.runtimeBroadcastStatusView, "等待操作", "info");
}
if (elements.materialOverrideStatusView) {
  setMetaText(elements.materialOverrideStatusView, "等待加载材质映射", "info");
}
renderAdminProfile();
populateAdminForm(null);



