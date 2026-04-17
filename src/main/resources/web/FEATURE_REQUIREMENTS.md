# WebShopX Feature Requirements Blueprint

Generated from current sources in this directory:
- index.html
- admin.html
- help.html
- js/app.js
- js/admin.js
- js/help.js
- js/i18n.js

Important scan note:
- js/mdui-compat.js is not present in src/main/resources/web/js.

## 1. Runtime architecture

### 1.1 Page boot chain

- index.html loads:
  - config.js
  - js/i18n.js
  - js/app.js
- admin.html loads:
  - config.js
  - js/i18n.js
  - js/admin.js
- help.html loads:
  - config.js
  - vendor/marked.min.js
  - vendor/purify.min.js
  - vendor/katex.min.js
  - vendor/auto-render.min.js
  - js/help.js

### 1.2 Global config and storage keys

- Runtime config object: window.WEBSHOPX_CONFIG
  - apiBaseUrl
  - defaultLocale
  - docsManifest
  - helpDefaultDoc
- Browser storage keys:
  - webshopx_theme
  - webshopx_locale
  - webshopx_session
  - webshopx_market_hide_own
  - webshop_admin_token
  - webshopx_help_mode

### 1.3 Routing model

- User page route mapping (app.js):
  - /account -> auth tab
  - /b2c -> shop tab
  - /c2c -> market tab
  - /auction -> auction tab
- Admin page route mapping (admin.js):
  - /admin/login, /admin/products, /admin/market, /admin/orders, /admin/redeem, /admin/economy, /admin/users, /admin/admins, /admin/audit

## 2. Global state contracts

## 2.1 User state (js/app.js)

```js
{
  token: string | null,
  username: string | null,
  boundUuid: string | null,

  activeTab: "auth" | "wallet" | "shop" | "orders" | "market" | "auction" | "logs",
  marketMode: "public" | "stores" | "mine",
  marketTradeScope: "DIRECT" | "AUCTION",
  marketStore: { sellerKey: string | null, sellerName: string | null, sellerUuid: string | null },

  listings: Listing[],
  products: Product[],
  orders: Order[],

  orderPolicy: {
    cooldownSeconds: number,
    refundEnabled: boolean,
    refundUndeliveredEnabled: boolean,
    marketFeePercent: number,
    marketTaxPercent: number,
    marketSupplyAutoRefreshThreshold: number,
    sharedClaimAllowed: boolean
  },
  orderPolicyReady: boolean,

  materialNameMap: Record<string, string>,
  materialNameMapReady: boolean,
  materialNameMapPromise: Promise | null,

  marketAlgorithmGlossary: { dynamic: AlgorithmDef[], auction: AlgorithmDef[] },
  marketAlgorithmGlossaryReady: boolean,
  marketAlgorithmGlossaryPromise: Promise | null,

  hasLoadedProducts: boolean,
  hasLoadedMarket: boolean,
  hasLoadedOrders: boolean,

  theme: "light" | "dark",
  hideOwnMarketListings: boolean,

  realtime: {
    timer: number | null,
    busy: boolean,
    hasBootstrapped: boolean,
    orderDigest: Record<string, string>,
    listingDigest: Record<string, string>,
    wallet: { shopCoin: number, gameCoin: number } | null
  },

  walletBalance: { shopCoin: number, gameCoin: number },

  exchangeMetaLoaded: boolean,
  exchangeSettings: {
    shopToGame: { enabled: boolean, ratio: number },
    gameToShop: { enabled: boolean, ratio: number }
  },

  timeZone: string
}
```

## 2.2 Admin state (js/admin.js)

```js
{
  token: string | null,
  admin: AdminProfile | null,

  activeTab: "login" | "products" | "market" | "orders" | "redeem" | "economy" | "users" | "admins" | "audit",
  productPanel: "editor" | "list" | "voucher",

  selectedUser: User | null,
  products: Product[],
  userList: User[],

  adminManagers: AdminUser[],
  adminMeta: AdminMeta | null,
  selectedAdminManager: AdminUser | null,

  latestRedeemCode: string | null,
  theme: "light" | "dark",

  materialMap: Record<string, string>,
  materialLookup: Record<string, string>,
  materialMapReady: boolean,
  materialMapPromise: Promise | null,

  materialAllowSet: Set<string>,
  materialAllowReady: boolean,
  materialAllowPromise: Promise | null,

  marketAlgorithmGlossary: { dynamic: AlgorithmDef[], auction: AlgorithmDef[] },
  marketAlgorithmGlossaryReady: boolean,
  marketAlgorithmGlossaryPromise: Promise | null,

  autoSyncTimer: number | null,
  autoSyncBusy: boolean,
  realtime: { orderDigest: Record<string, string>, marketDigest: Record<string, string> },

  currencyMeta: {
    SHOP_COIN: { name: string, short: string },
    GAME_COIN: { name: string, short: string }
  },

  timeZone: string
}
```

## 2.3 Help state (js/help.js)

```js
{
  docs: DocEntry[],
  doc: DocEntry | null,
  mode: "single" | "full",
  lang: string,

  headings: HeadingEntry[],
  sections: SectionEntry[],

  sectionMap: Map<string, SectionEntry>,
  headingToSection: Map<string, string>,
  validIds: Set<string>,
  idMapUpper: Map<string, string>,

  activeId: string | null,
  currentSectionId: string | null,

  searchIndex: SearchEntry[],
  scrollRaf: number,
  searchTimer: number
}
```

## 3. API blueprint (paths, payloads, response fields)

Legend:
- Request body fields are listed from frontend call sites.
- Response fields are only those consumed by frontend.
- Error codes are from frontend code maps (where available).

## 3.1 User-facing APIs (app.js)

| Endpoint | Method | Request fields | Response fields consumed | Typical error codes mapped |
|---|---|---|---|---|
| /api/auth/login | POST | identifier, password | sessionToken, user.username, user.boundUuid, username, boundUuid | invalid_credentials, not_bound, invalid_identifier, invalid_password |
| /api/auth/logout | POST | {} | success by HTTP status | auth_required, auth_invalid |
| /api/wallet | GET | - | shopCoin, gameCoin, username, boundUuid, exchange | auth_required, wallet_missing, bad_request |
| /api/wallet/ledger?limit=20 | GET | - | entries[].bizType, bizId, delta, currency, createdAt | auth_required |
| /api/wallet/exchange | POST | fromCurrency, toCurrency, amount, idempotencyKey | wallet fields for refresh (shopCoin, gameCoin, exchange) | invalid_exchange, invalid_amount, exchange_disabled, invalid_ratio, insufficient_funds |
| /api/redeem/use | POST | code | status, shopCoin, gameCoin | invalid_code, expired, out_of_stock, already_used, user_limit_reached |
| /api/products | GET | - | products[] (id, sku, title, price, currency, productType, remark, itemMaterial, perUserLimit, personalLimitRemaining, stockRemaining, dynamic*) | not_found |
| /api/orders/policy | GET | - | cooldownSeconds, refundEnabled, refundUndeliveredEnabled, marketFeePercent, marketTaxPercent, marketSupplyAutoRefreshThreshold, sharedClaimAllowed | - |
| /api/orders/list?limit=50 | GET | - | orders[] + cooldownSeconds/refund flags | auth_required, not_found |
| /api/orders/list?limit=30 | GET | - | orders[] (for realtime digest) | auth_required |
| /api/orders | POST | productId, quantity, deliveryMode, idempotencyKey | orderNo, totalAmount, currency, orderStatus/state, groupBuyVoucherCode | invalid_quantity, product_missing, product_user_limit_reached, player_offline, insufficient_item, invalid_product_type, invalid_product, sync_timeout, sync_interrupted |
| /api/orders/refund | POST | orderNo | orderNo, wallet fields (via updateWalletView) | refund_disabled, refund_expired, refund_not_allowed, already_refunded, order_missing, voucher_consumed |
| /api/market/listings?... | GET | mine, limit, keyword, material, currency, minPrice, maxPrice, sort, order | listings[] (id, seller*, itemMaterial, quantity, quantityTotal, price, currency, sourceMode, status, tradeMode, remark, createdAt, dynamic*, auction*, supply*) | not_found |
| /api/market/buy | POST | listingId, buyQuantity, deliveryMode, idempotencyKey | tradeId, listingId, quantity, buyerTotal/totalPrice, currency, feeAmount, taxAmount, cooldownSeconds, refundDeadline, orderStatus/state | invalid_listing, listing_missing, listing_unavailable, auction_only_bid, invalid_trade, invalid_idempotency, invalid_quantity, insufficient_quantity |
| /api/market/bid | POST | listingId, bidAmount, idempotencyKey | bidId, listingId, bidAmount, currentHighestBid, currency, sealedBid, minimumRequiredBid, state | invalid_listing, listing_missing, listing_unavailable, invalid_trade_mode, invalid_bid, bid_too_low, auction_closed, idempotency_conflict |
| /api/market/unlist | POST | listingId | listingId | invalid_listing, listing_missing, listing_unavailable, forbidden |
| /api/market/supply/refresh | POST | listingId | listingId, loadedAmount, currentStock, maxStock, loadedTotal | invalid_listing, listing_missing, listing_unavailable, forbidden, supply_missing, supply_empty |
| /api/market/pause | POST | listingId | listingId | auth_required, forbidden |
| /api/market/resume | POST | listingId | listingId | auth_required, forbidden |
| /api/market/price | POST | listingId, price | listingId, price, currency | invalid_price, invalid_trade_mode, invalid_dynamic_*, invalid_auction_*, listing_empty |
| /api/market/remark | POST | listingId, remark | listingId, remark | invalid_remark, forbidden |
| /api/market/settings | POST | listingId, price, currency, remark, supplyBatchSize, supplyMaxStock, tradeMode, dynamicPricingEnabled, dynamicAlgorithm, dynamicParamsJson, dynamicBasePrice, dynamicFloorPrice, dynamicCapPrice, dynamicPriceStep, auctionAlgorithm, auctionParamsJson, auctionStartPrice, auctionMinIncrement, auctionEndAt | listing update acknowledgement fields (price, tradeMode, etc.) | same validation family as market_price + ownership checks |
| /api/meta/currency | GET | - | shopCoin{name,short}, gameCoin{name,short}, exchange, timeZone | - |

## 3.2 Admin APIs (admin.js)

| Endpoint | Method | Request fields | Response fields consumed | Typical error codes mapped |
|---|---|---|---|---|
| /api/admin/auth/login | POST | identifier, password | sessionToken, admin | invalid_credentials |
| /api/admin/auth/me | GET | - | admin profile (username, role, permissions, canManageAdmins, isSuperAdmin) | auth_required, auth_invalid |
| /api/admin/auth/logout | POST | {} | success by HTTP status | auth_required |
| /api/admin/redeem/create | POST | shopCoin, gameCoin, maxUses, perUserMaxUses, expiresInMinutes?, customCode? | code, expiresAt | auth_required |
| /api/admin/redeem/list?limit=200 | GET | - | codes[] | auth_required |
| /api/admin/products/upsert | POST | sku, title, remark, currency, price, publishAt, unpublishAt, productType, commandTemplate, itemMaterial, itemAmount, perUserLimit, effectType, effectSeconds, effectAmplifier, dynamicPricingEnabled, dynamicAlgorithm, dynamicParamsJson, dynamicBasePrice, dynamicFloorPrice, dynamicCapPrice, dynamicPriceStep, active | sku | validation errors for product schema |
| /api/admin/products/active | POST | productId, active | updated product state | auth_required |
| /api/admin/products/reset-limit | POST | productId | sku, resetCount | auth_required |
| /api/admin/products/list?includeInactive=true&limit=300 | GET | - | products[] complete admin view | auth_required |
| /api/admin/group-buy/consume | POST | code | code, orderNo, username, consumedAt | voucher_refunded, voucher_unavailable |
| /api/admin/orders/list?... | GET | status, userId, orderNo, username, currency, productType, keyword, limit | orders[] with user/product/amount/status/time fields | auth_required |
| /api/admin/economy/settings | GET | - | exchange.{shopToGame,gameToShop}, market.{tradeFeePercent,tradeTaxPercent}, vault flags/provider | auth_required |
| /api/admin/economy/exchange | POST | shopToGameEnabled, shopToGameRatio, gameToShopEnabled, gameToShopRatio | saved exchange config | auth_required |
| /api/admin/economy/market | POST | tradeFeePercent, tradeTaxPercent | saved market config | auth_required |
| /api/admin/market/listings?... | GET | status, seller, buyer, material, currency, keyword, limit | listings[] | auth_required |
| /api/admin/market/unlist | POST | listingId | success | auth_required |
| /api/admin/users/lookup?identifier=... | GET | identifier (query) | user profile (id, username, boundUuid, authState, shopCoin, gameCoin, timestamps) | not_found |
| /api/admin/users/list?... | GET | keyword, limit | users[] | auth_required |
| /api/admin/users/reset-password | POST | userId, newPassword | success | user_missing |
| /api/admin/users/unbind | POST | userId | success | user_missing |
| /api/admin/users/logout | POST | userId | success | user_missing |
| /api/admin/users/wallet-adjust | POST | userId, currency, delta, reason | updated balances | user_missing |
| /api/admin/admin-users/meta | GET | - | templates, groups and permission metadata | auth_required |
| /api/admin/admin-users/list | GET | - | admins[] | auth_required |
| /api/admin/admin-users/upsert | POST | identifier, isSuperAdmin, templateKey, permissions[] | saved admin user | auth_required |
| /api/admin/admin-users/active | POST | userId, active | saved active state | auth_required |
| /api/admin/audit/list?limit=200 | GET | - | logs[] | auth_required |
| /api/meta/materials | GET | - | materials[] allow-list for material normalization | - |
| /api/meta/currency | GET | - | currency names/short/timeZone | - |

## 3.3 Help/document data APIs (help.js)

Not /api endpoints, but required content endpoints:

- docs/index.json (or runtimeConfig.docsManifest)
  - expected: { documents: DocEntry[] }
- docs/*.md
  - markdown content rendered by marked -> DOMPurify -> KaTeX pass

## 4. i18n loading and translation mechanism

Source: js/i18n.js

## 4.1 Locale lifecycle

- Supported locales: zh-CN, en-US
- Initial locale priority:
  1. localStorage[webshopx_locale]
  2. navigator.languages / navigator.language
  3. WEBSHOPX_CONFIG.defaultLocale
  4. zh-CN fallback
- setLocale writes localStorage and updates document.documentElement.lang
- Page reload is used after locale select change

## 4.2 Translation layers

English translation uses layered fallback:

1. Exact map lookup (EN_EXACT)
2. Pattern transforms (EN_PATTERNS regex)
3. Prefix label transform (EN_PREFIX_LABELS for "X: Y" texts)
4. Return original source text

Chinese mode behaves as source-of-truth and bypasses translation maps.

## 4.3 DOM translation pipeline

preparePage(pageName, {selectId}) executes:
- translateDocumentText() on all text nodes (excluding CODE/SCRIPT/STYLE/TEXTAREA parents)
- translateAttributes() for placeholder/title/aria-label/alt
- applyHtmlOverrides(pageName) for selector-based HTML replacements
- populateLocaleSelect(selectId)

## 4.4 i18n utility exports available to pages

window.WebShopXI18n:
- getIntlLocale
- getLocale
- setLocale
- normalizeLocale
- isChineseLocale
- shouldLoadMaterialMap
- localizeText
- humanizeEnum
- getPotionEffectLabel
- getThemeToggleLabel
- preparePage

## 5. Required UI fields and functional constraints

## 5.1 index.html (user portal)

### Header + tabs
- Required visible controls:
  - statusChip
  - localeSelect
  - themeToggleBtn
  - top tabs: auth, shop, market, auction

### Auth panel requirements
- Required fields:
  - loginIdentifier
  - loginPassword
  - loginBtn
  - logoutBtn
  - profileName, profileUuid, profileAvatar after login
- Constraints:
  - login identifier and password cannot be empty on submit
  - session token persisted to localStorage as webshopx_session
  - logout clears token, user identity, order cache and realtime polling

### Wallet panel requirements
- Required fields:
  - shopCoinValue, gameCoinValue
  - walletLedgerList with recent entries
  - redeemCode input + redeemBtn
  - exchangeFrom, exchangeTo, exchangeAmount, exchangeBtn
  - exchangeRateHint and exchangeView feedback
- Constraints:
  - exchange amount must be > 0
  - exchange from/to must differ
  - direction must be enabled by exchange settings
  - converted amount uses floor(amount * ratio) and must be > 0

### Official shop panel requirements
- Required fields:
  - product list cards with title, type, currency/price, quantity controls, CTA
  - search/sort/advanced filter controls
- Constraints:
  - purchase quantity must be within stock and personal limit
  - recycle and buy share order endpoint but different confirmation copy
  - idempotency key sent for each order creation

### Orders panel requirements
- Required fields:
  - order cards include orderNo, SKU/listing id, source label, amount, quantity, status, timestamps
  - optional actions: refund, copy voucher, copy claim command
- Constraints:
  - refund action requires backend canRefund flag
  - WAIT_CLAIM status shows claim command and shared-claim hint by policy

### Market/auction panel requirements
- Required fields:
  - list/stores/mine mode buttons
  - filters (keyword/material/currency/price/sort)
  - card fields: seller, item, quantity, status, price, mode-specific dynamic/auction/supply info
- Constraints:
  - hideOwn toggle only affects public mode
  - buy quantity bounds enforced (1..max, hard guard <= 64 in buyListing)
  - bid must satisfy min bid/increment logic
  - listing edit validates:
    - price > 0
    - supplyBatchSize/supplyMaxStock > 0 (supply mode)
    - dynamic base/step > 0
    - dynamic floor/cap > 0 if provided and floor <= cap
    - auction start/increment > 0, endAt valid when required

## 5.2 admin.html (control panel)

### Login tab
- Required fields:
  - adminIdentifier, adminPassword, adminLoginBtn, adminLogoutBtn
  - adminProfileView and adminStatusChip
- Constraints:
  - both credentials required
  - token stored in sessionStorage as webshop_admin_token

### Redeem tab
- Required fields:
  - redeemShopCoin, redeemGameCoin, redeemMaxUses, redeemPerUserMaxUses, redeemExpires, redeemCustomCode
  - redeemList rendering + copy latest code action
- Constraints:
  - relies on backend numeric validation and code uniqueness checks

### Products tab
- Required fields:
  - editor fields: SKU/title/currency/price/type/command/material/effect/stock/perUserLimit/publish windows/remark
  - dynamic pricing controls and param editors
  - product list panel with load/edit and active toggle
  - voucher consume panel for group buy codes
- Constraints:
  - material inputs normalized against allow-list + i18n map
  - dynamic param JSON must parse as object
  - dynamic visual-editor values merged with JSON payload
  - stock mode controls finite vs unlimited itemAmount behavior

### Orders tab
- Required fields:
  - filters: status, userId, orderNo, username, currency, productType, keyword
  - cards with user/status/amount/product/material/remark/time

### Economy tab
- Required fields:
  - exchange direction enable flags and ratios
  - trade fee and tax percent
  - vault status panel
- Constraints:
  - ratios and percentages must be numeric (backend-enforced bounds)

### Market tab
- Required fields:
  - filters: status, seller, buyer, material, currency, keyword
  - listing cards and force-unlist action for active rows

### Users tab
- Required fields:
  - lookup input and result card
  - support actions: reset password, unbind, force logout, wallet adjust
  - user list with keyword and hide inactive toggle
- Constraints:
  - selected user required before support actions
  - wallet delta cannot be zero

### Admins tab
- Required fields:
  - identifier/template/type + permission groups with checkboxes
  - manager list with edit and active toggle
- Constraints:
  - requires admin.canManageAdmins
  - super mode disables granular checkbox editing
  - non-super save requires at least one permission

### Audit tab
- Required fields:
  - audit list entries with action/admin/target/time/ip

## 5.3 help.html (docs center)

- Required fields:
  - doc select, search input/results, TOC links, mode toggle, theme toggle, reload, home
  - content render area + metadata row
- Constraints:
  - mode is single/full, persisted in localStorage
  - markdown links to *.md rewritten into help routes
  - hash ids sanitized and validated before navigation
  - search is local index over headings + section text

## 6. Core business invariants extracted from code

- All protected API operations depend on token presence and Authorization Bearer header.
- User order and market purchase/bid operations are idempotency-protected.
- Realtime polling cadence:
  - user app: every 8s
  - admin app: every 10s
- Market trade scope is tab-bound:
  - market tab => DIRECT scope
  - auction tab => AUCTION scope
- Time rendering/parsing is business-time-zone aware via state.timeZone (default Asia/Shanghai).
- Currency display uses dynamic metadata from /api/meta/currency.
- Material naming uses locale map + alias normalization + fallback humanization.
- Dynamic and auction algorithm catalogs are loaded from i18n/market-algorithms/*.json with hardcoded fallback catalog.

## 7. Non-DOM logic extraction targets (implemented into js/core-logic.js)

The following categories are pure and safe to reuse:

- API/base URL normalization
- Idempotency key generation
- Currency/ratio/percent calculations
- Exchange direction normalization and preview calculation
- Material normalization and texture candidate generation
- Product stock/limit calculations
- Digest builders for realtime transition tracking
- Time-zone aware parsing and formatting helpers
- Algorithm glossary normalization and parameter parsing/validation helpers
- Help-doc entry parsing and hash sanitization
- i18n dictionary fallback localizer (exact/pattern/prefix)
