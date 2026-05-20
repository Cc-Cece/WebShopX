package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webshopx.payment.api.PaymentMethod;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

class EmbeddedWebServer {
  private static final String REQUEST_START_NANOS_ATTR = "webshopx.request.startNanos";
  private final JavaPlugin plugin;
  private final SchedulerBridge schedulerBridge;
  private final Supplier<PluginSettings> settingsSupplier;
  private final AuthService authService;
  private final WalletService walletService;
  private final RechargeService rechargeService;
  private final RedeemCodeService redeemCodeService;
  private final ProductService productService;
  private final OrderService orderService;
  private final MarketService marketService;
  private final NotificationService notificationService;
  private final AdminService adminService;
  private final AdminAuditService adminAuditService;
  private final LeaderboardService leaderboardService;
  private final MaterialVisualService materialVisualService;
  private final VisualCustomizationService visualCustomizationService;
  private final UserMarketSettingsService userMarketSettingsService;
  private final RuntimeConfigService runtimeConfigService;
  private final ClusterEventBusService clusterEventBusService;
  private final BStatsTelemetryService bStatsTelemetryService;
  private final Gson gson;
  private static final int MATERIAL_ICON_MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
  private static final int REMOTE_LOCALE_PACKAGE_MAX_BYTES = 20 * 1024 * 1024;
  private static final int REMOTE_THEME_PACKAGE_MAX_BYTES = 12 * 1024 * 1024;
  private static final int REMOTE_FETCH_MAX_ATTEMPTS = 4;
  private static final long REMOTE_FETCH_RETRY_BASE_DELAY_MS = 1200L;
  private static final List<String> GITHUB_PROXY_PREFIXES = List.of(
      "https://edgeone.gh-proxy.com/",
      "https://hk.gh-proxy.com/",
      "https://gh-proxy.com/",
      "https://gh.llkk.cc/");
  private static final String GITHUB_PROXY_MODE_AUTO = "auto";
  private static final String GITHUB_PROXY_MODE_ON = "on";
  private static final String GITHUB_PROXY_MODE_OFF = "off";
  private static final Set<String> MATERIAL_ICON_ALLOWED_EXTENSIONS =
      Set.of("png", "webp", "jpg", "jpeg", "gif");
  private static final int REMOTE_MANIFEST_MAX_BYTES = 1024 * 1024;
  private static final Set<String> MANIFEST_HOST_ALLOWLIST = Set.of(
      "github.com",
      "githubusercontent.com",
      "objects.githubusercontent.com",
      "release-assets.githubusercontent.com",
      "edgeone.gh-proxy.com",
      "hk.gh-proxy.com",
      "gh-proxy.com",
      "gh.llkk.cc",
      "cdn.jsdelivr.net");

  private final HttpClient httpClient;
  private final LocaleCenterService localeCenterService;
  private final ThemeCenterService themeCenterService;

  private HttpServer server;
  private ExecutorService executorService;
  private Path staticRoot;
  private Path webUserRoot;

  EmbeddedWebServer(
      JavaPlugin plugin,
      SchedulerBridge schedulerBridge,
      Supplier<PluginSettings> settingsSupplier,
      AuthService authService,
      WalletService walletService,
      RechargeService rechargeService,
      RedeemCodeService redeemCodeService,
      ProductService productService,
      OrderService orderService,
      MarketService marketService,
      NotificationService notificationService,
      AdminService adminService,
      AdminAuditService adminAuditService,
      LeaderboardService leaderboardService,
      MaterialVisualService materialVisualService,
      VisualCustomizationService visualCustomizationService,
      UserMarketSettingsService userMarketSettingsService,
      RuntimeConfigService runtimeConfigService,
      ClusterEventBusService clusterEventBusService,
      BStatsTelemetryService bStatsTelemetryService) {
    this.plugin = plugin;
    this.schedulerBridge = schedulerBridge;
    this.settingsSupplier = settingsSupplier;
    this.authService = authService;
    this.walletService = walletService;
    this.rechargeService = rechargeService;
    this.redeemCodeService = redeemCodeService;
    this.productService = productService;
    this.orderService = orderService;
    this.marketService = marketService;
    this.notificationService = notificationService;
    this.adminService = adminService;
    this.adminAuditService = adminAuditService;
    this.leaderboardService = leaderboardService;
    this.materialVisualService = materialVisualService;
    this.visualCustomizationService = visualCustomizationService;
    this.userMarketSettingsService = userMarketSettingsService;
    this.runtimeConfigService = runtimeConfigService;
    this.clusterEventBusService = clusterEventBusService;
    this.bStatsTelemetryService = bStatsTelemetryService;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
    this.httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(10))
      .build();
    this.localeCenterService = new LocaleCenterService(plugin, () -> this.webUserRoot);
    this.themeCenterService = new ThemeCenterService(plugin, () -> this.webUserRoot);
  }

  void start(Path staticRoot, Path webUserRoot) throws IOException {
    stop();
    this.staticRoot = staticRoot.toAbsolutePath().normalize();
    this.webUserRoot = webUserRoot.toAbsolutePath().normalize();
    PluginSettings.EmbeddedWebSettings webSettings = settingsSupplier.get().embeddedWebSettings();
    PluginSettings.ServerMode serverMode = settingsSupplier.get().serverMode();

    InetSocketAddress address = new InetSocketAddress(webSettings.host(), webSettings.port());
    server = HttpServer.create(address, 0);
    executorService = Executors.newFixedThreadPool(8);
    server.setExecutor(executorService);

    server.createContext("/health", this::handleHealth);
    server.createContext("/api/auth/login", this::handleLogin);
    server.createContext("/api/auth/me", this::handleAuthMe);
    server.createContext("/api/auth/logout", this::handleLogout);
    server.createContext("/api/wallet", this::handleWallet);
    server.createContext("/api/wallet/ledger", this::handleWalletLedger);
    server.createContext("/api/wallet/exchange", this::handleExchange);
    server.createContext("/api/recharge/create", this::handleRechargeCreate);
    server.createContext("/api/recharge/cancel", this::handleRechargeCancel);
    server.createContext("/api/recharge/status", this::handleRechargeStatus);
    server.createContext("/api/redeem/use", this::handleRedeemUse);
    server.createContext("/api/products", this::handleProducts);
    server.createContext("/api/orders", this::handleOrders);
    server.createContext("/api/orders/list", this::handleOrdersList);
    server.createContext("/api/orders/refund", this::handleOrdersRefund);
    server.createContext("/api/orders/policy", this::handleOrdersPolicy);
    server.createContext("/api/notifications/list", this::handleNotificationsList);
    server.createContext("/api/notifications/unread-count", this::handleNotificationsUnreadCount);
    server.createContext("/api/notifications/mark-read", this::handleNotificationsMarkRead);
    server.createContext("/api/meta/currency", this::handleCurrencyMeta);
    server.createContext("/api/meta/materials", this::handleMaterialMeta);
    server.createContext("/api/meta/material-overrides", this::handleMaterialOverrideMeta);
    server.createContext("/api/meta/market-tags", this::handleMarketTagsMeta);
    server.createContext("/api/meta/locales", this::handleMetaLocales);
    server.createContext("/api/meta/themes", this::handleMetaThemes);
    server.createContext("/api/leaderboard/config", this::handleLeaderboardConfig);
    server.createContext("/api/leaderboard/list", this::handleLeaderboardList);
    server.createContext("/api/market/listings", this::handleMarketListings);
    server.createContext("/api/market/listings/create", this::handleMarketListingsCreate);
    server.createContext("/api/market/buy", this::handleMarketBuy);
    server.createContext("/api/market/sell-to-buy", this::handleMarketSellToBuy);
    server.createContext("/api/market/bid", this::handleMarketBid);
    server.createContext("/api/market/unlist", this::handleMarketUnlist);
    server.createContext("/api/market/pause", this::handleMarketPause);
    server.createContext("/api/market/resume", this::handleMarketResume);
    server.createContext("/api/market/price", this::handleMarketPrice);
    server.createContext("/api/market/remark", this::handleMarketRemark);
    server.createContext("/api/market/settings", this::handleMarketSettings);
    server.createContext("/api/market/icon/upload", this::handleMarketIconUpload);
    server.createContext("/api/market/supply/refresh", this::handleMarketSupplyRefresh);
    server.createContext("/api/admin/auth/login", this::handleAdminLogin);
    server.createContext("/api/admin/auth/me", this::handleAdminMe);
    server.createContext("/api/admin/auth/logout", this::handleAdminLogout);
    server.createContext("/api/admin/l10n/manifest", this::handleAdminL10nManifest);
    server.createContext("/api/admin/locales", this::handleAdminLocalesList);
    server.createContext("/api/admin/locales/default", this::handleAdminLocalesDefault);
    server.createContext("/api/admin/locales/action", this::handleAdminLocalesAction);
    server.createContext("/api/admin/locales/upload", this::handleAdminLocalesUpload);
    server.createContext("/api/admin/locales/sync-manifest", this::handleAdminLocalesSyncManifest);
    server.createContext("/api/admin/themes", this::handleAdminThemesList);
    server.createContext("/api/admin/themes/default", this::handleAdminThemesDefault);
    server.createContext("/api/admin/themes/action", this::handleAdminThemesAction);
    server.createContext("/api/admin/themes/upload", this::handleAdminThemesUpload);
    server.createContext("/api/admin/themes/sync-manifest", this::handleAdminThemesSyncManifest);
    server.createContext("/api/admin/redeem/create", this::handleAdminRedeemCreate);
    server.createContext("/api/admin/redeem/list", this::handleAdminRedeemList);
    server.createContext("/api/admin/products/list", this::handleAdminProductsList);
    server.createContext("/api/admin/products/upsert", this::handleAdminProductsUpsert);
    server.createContext("/api/admin/products/icon", this::handleAdminProductIconUpload);
    server.createContext("/api/admin/products/active", this::handleAdminProductsActive);
    server.createContext("/api/admin/products/reset-limit", this::handleAdminProductsResetLimit);
    server.createContext("/api/admin/group-buy/consume", this::handleAdminGroupBuyConsume);
    server.createContext("/api/admin/orders/list", this::handleAdminOrdersList);
    server.createContext("/api/admin/economy/settings", this::handleAdminEconomySettings);
    server.createContext("/api/admin/economy/exchange", this::handleAdminExchangeUpdate);
    server.createContext("/api/admin/economy/market", this::handleAdminMarketEconomyUpdate);
    server.createContext("/api/admin/economy/leaderboard", this::handleAdminLeaderboardSettingsUpdate);
    server.createContext("/api/admin/economy/currency", this::handleAdminCurrencyDisplayUpdate);
    server.createContext("/api/admin/economy/recharge-payment", this::handleAdminRechargePaymentUpdate);
    server.createContext("/api/admin/market/tags-config", this::handleAdminMarketTagsConfig);
    server.createContext("/api/admin/market/limitation-config", this::handleAdminMarketLimitationConfig);
    server.createContext("/api/admin/system/webshop", this::handleAdminWebshopRuntimeUpdate);
    server.createContext("/api/admin/system/market", this::handleAdminMarketRuntimeUpdate);
    server.createContext("/api/admin/system/maintenance", this::handleAdminMaintenanceSettingsUpdate);
    server.createContext("/api/admin/system/logging", this::handleAdminLoggingSettingsUpdate);
    server.createContext("/api/admin/system/broadcast", this::handleAdminBroadcastSettingsUpdate);
    server.createContext("/api/admin/system/notification", this::handleAdminNotificationSettingsUpdate);
    server.createContext("/api/admin/visual/settings", this::handleAdminVisualSettingsUpdate);
    server.createContext("/api/admin/material-overrides/list", this::handleAdminMaterialOverridesList);
    server.createContext("/api/admin/material-overrides/upsert", this::handleAdminMaterialOverridesUpsert);
    server.createContext("/api/admin/material-overrides/delete", this::handleAdminMaterialOverridesDelete);
    server.createContext("/api/admin/material-overrides/icon", this::handleAdminMaterialOverrideIconUpload);
    server.createContext("/api/admin/market/listings", this::handleAdminMarketListings);
    server.createContext("/api/admin/market/unlist", this::handleAdminMarketUnlist);
    server.createContext("/api/admin/users/lookup", this::handleAdminUserLookup);
    server.createContext("/api/admin/users/list", this::handleAdminUsersList);
    server.createContext("/api/admin/users/reset-password", this::handleAdminResetPassword);
    server.createContext("/api/admin/users/unbind", this::handleAdminUnbind);
    server.createContext("/api/admin/users/logout", this::handleAdminForceLogout);
    server.createContext("/api/admin/users/wallet-adjust", this::handleAdminWalletAdjust);
    server.createContext("/api/admin/users/visual-permission", this::handleAdminUserVisualPermission);
    server.createContext("/api/admin/audit/list", this::handleAdminAuditList);
    server.createContext("/api/admin/notifications/announce", this::handleAdminNotificationAnnounce);
    server.createContext("/api/admin/admin-users/meta", this::handleAdminUsersMeta);
    server.createContext("/api/admin/admin-users/list", this::handleAdminAdminUsersList);
    server.createContext("/api/admin/admin-users/upsert", this::handleAdminAdminUsersUpsert);
    server.createContext("/api/admin/admin-users/active", this::handleAdminAdminUsersActive);

    // Only serve static files in INTERNAL mode
    if (serverMode == PluginSettings.ServerMode.INTERNAL) {
      server.createContext("/", this::handleStatic);
    }

    server.start();
    MessageService ms = new MessageService(plugin, settingsSupplier);
    plugin.getLogger().info(ms.formatConsole("console.embedded_http_started", Map.of(
      "host", webSettings.host(), "port", webSettings.port(), "mode", serverMode.name())));
  }

  void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executorService != null) {
      executorService.shutdownNow();
      executorService = null;
    }
    staticRoot = null;
    webUserRoot = null;
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    JsonObject response = new JsonObject();
    response.addProperty("status", "ok");
    response.addProperty("time", LocalDateTime.now().toString());
    sendJson(exchange, 200, response);
  }

  private void handleLogin(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      String identifier = getOptionalString(payload, "identifier")
          .or(() -> getOptionalString(payload, "username"))
          .orElseThrow(
              () -> new ServiceException("bad_request", "Missing field: identifier"));
      String password = getString(payload, "password");
      AuthService.AuthResult result = authService.login(identifier, password);
      JsonObject response = sessionResponse(result);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAuthMe(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      JsonObject response = userResponse(user);
      sendJson(exchange, 200, response);
    });
  }

  private void handleLogout(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      String token = readHeaderToken(exchange);
      if (token == null) {
        throw new ServiceException("auth_required", "Missing session token");
      }
      authService.findUserBySession(token)
          .orElseThrow(() -> new ServiceException("auth_invalid", "Session token is invalid or expired"));
      authService.logout(token);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleWallet(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      WalletService.WalletBalance balance = walletService.getBalance(user.id());
      JsonObject response = new JsonObject();
      response.addProperty("username", user.username());
      response.addProperty("shopCoin", balance.shopCoin());
      response.addProperty("gameCoin", balance.gameCoin());
      response.addProperty("boundUuid", user.boundUuid() == null ? null : user.boundUuid().toString());
      response.add("visualPermission", resolveUserVisualPermissionJson(user));
      response.add("exchange", buildExchangeMetaJson());
      sendJson(exchange, 200, response);
    });
  }

  private void handleWalletLedger(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 20);
      List<WalletService.LedgerEntry> entries = walletService.listRecentLedger(user.id(), limit);
      JsonArray rows = new JsonArray();
      for (WalletService.LedgerEntry entry : entries) {
        JsonObject row = new JsonObject();
        row.addProperty("currency", entry.currency().name());
        row.addProperty("delta", entry.delta());
        row.addProperty("bizType", entry.bizType());
        row.addProperty("bizId", entry.bizId());
        row.addProperty("createdAt", entry.createdAt().toString());
        rows.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("entries", rows);
      sendJson(exchange, 200, response);
    });
  }

  private void handleRechargeCreate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long amountMinor = payload.has("amountMinor")
          ? getLong(payload, "amountMinor", 0L)
          : rechargeService.yuanToAmountMinor(getString(payload, "amount"));
      long coinAmount = payload.has("coinAmount")
          ? getLong(payload, "coinAmount", 0L)
          : rechargeService.amountToCoinAmount(amountMinor);
      String currency = settingsSupplier.get().paymentSettings().primaryRechargeCurrency();
      PaymentMethod preferredMethod = parsePaymentMethod(
          getOptionalString(payload, "paymentMethod")
              .or(() -> getOptionalString(payload, "preferredMethod"))
              .orElse("AUTO"));
      String methodCode = getOptionalString(payload, "methodCode").orElse(null);
      RechargeService.RechargeCreateResult result = rechargeService.createRechargeOrder(
          new RechargeService.RechargeCreateRequest(
              user.id(),
              user.boundUuid(),
              amountMinor,
              currency,
              coinAmount,
              preferredMethod,
              methodCode,
              "WEB"));
      JsonObject response = rechargeCreateResultJson(result);
      sendJson(exchange, result.success() ? 200 : 400, response);
    });
  }

  private void handleRechargeCancel(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      String orderId = getString(payload, "orderId");
      RechargeService.RechargeCancelResult result = rechargeService.cancelRechargeOrder(user.id(), orderId);
      JsonObject response = new JsonObject();
      response.addProperty("success", result.success());
      response.addProperty("orderId", result.orderId());
      response.addProperty("status", result.status());
      addNullableString(response, "code", result.code());
      addNullableString(response, "message", result.message());
      sendJson(exchange, result.success() ? 200 : 400, response);
    });
  }

  private void handleRechargeStatus(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      String orderId = parseQuery(exchange).get("orderId");
      RechargeService.RechargeOrder order = rechargeService.findOwnedOrder(user.id(), orderId);
      if (order == null) {
        throw new ServiceException("ORDER_NOT_FOUND", "Recharge order not found");
      }
      sendJson(exchange, 200, rechargeOrderJson(order));
    });
  }

  private void handleExchange(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      CurrencyType from = CurrencyType.fromConfig(getString(payload, "fromCurrency"));
      CurrencyType to = CurrencyType.fromConfig(getString(payload, "toCurrency"));
      long amount = getLong(payload, "amount", 0L);
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      WalletService.WalletBalance balance = walletService.exchange(user.id(), from, to, amount,
          idempotencyKey);
      JsonObject response = new JsonObject();
      response.addProperty("shopCoin", balance.shopCoin());
      response.addProperty("gameCoin", balance.gameCoin());
      sendJson(exchange, 200, response);
    });
  }

  private void handleRedeemUse(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      String code = getString(payload, "code");
      RedeemCodeService.RedeemResult result = redeemCodeService.redeem(user.id(), code);
      JsonObject response = new JsonObject();
      response.addProperty("status", result.status().name());
      response.addProperty("shopCoin", result.balance().shopCoin());
      response.addProperty("gameCoin", result.balance().gameCoin());
      sendJson(exchange, 200, response);
    });
  }

  private void handleProducts(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      Optional<AuthService.AuthUser> user = findOptionalAuth(exchange);
      List<ProductService.ProductView> products = user.isPresent()
          ? productService.listActiveProductsForUser(user.get().id())
          : productService.listActiveProducts();
      JsonArray array = new JsonArray();
      for (ProductService.ProductView product : products) {
        JsonObject item = new JsonObject();
        addProductJson(item, product, true);
        array.add(item);
      }
      JsonObject response = new JsonObject();
      response.add("products", array);
      sendJson(exchange, 200, response);
    });
  }

  private void handleOrders(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long productId = getLong(payload, "productId", -1L);
      int quantity = (int) getLong(payload, "quantity", 1L);
      String deliveryMode = getOptionalString(payload, "deliveryMode").orElse(null);
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      OrderService.OrderPlacementResult result = orderService.placeOrder(
          user.id(),
          productId,
          quantity,
          idempotencyKey,
          deliveryMode);
      JsonObject response = new JsonObject();
      response.addProperty("state", result.state().name());
      response.addProperty("orderNo", result.orderNo());
      response.addProperty("currency", result.currency().name());
      response.addProperty("totalAmount", result.totalAmount());
      response.addProperty("orderStatus", result.orderStatus());
      response.addProperty("cooldownSeconds", result.cooldownSeconds());
      if (result.refundDeadline() == null) {
        response.add("refundDeadline", JsonNull.INSTANCE);
      } else {
        response.addProperty("refundDeadline", result.refundDeadline().toString());
      }
      if (result.groupBuyVoucherCode() == null) {
        response.add("groupBuyVoucherCode", JsonNull.INSTANCE);
      } else {
        response.addProperty("groupBuyVoucherCode", result.groupBuyVoucherCode());
      }
      if (result.groupBuyVoucherStatus() == null) {
        response.add("groupBuyVoucherStatus", JsonNull.INSTANCE);
      } else {
        response.addProperty("groupBuyVoucherStatus", result.groupBuyVoucherStatus());
      }
      if (result.groupBuyVoucherConsumedAt() == null) {
        response.add("groupBuyVoucherConsumedAt", JsonNull.INSTANCE);
      } else {
        response.addProperty("groupBuyVoucherConsumedAt", result.groupBuyVoucherConsumedAt().toString());
      }
      sendJson(exchange, 200, response);
    });
  }

  private void handleOrdersList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 30);
      Long cursor = parseLong(query.get("cursor"));
      List<OrderService.OrderView> orders = orderService.listOrdersForUser(user.id(), limit, cursor);
      JsonArray array = new JsonArray();
      LocalDateTime now = LocalDateTime.now();
      for (OrderService.OrderView order : orders) {
        JsonObject row = new JsonObject();
        row.addProperty("id", order.id());
        row.addProperty("orderNo", order.orderNo());
        row.addProperty("status", order.status());
        row.addProperty("currency", order.currency().name());
        row.addProperty("totalAmount", order.totalAmount());
        row.addProperty("createdAt", order.createdAt().toString());
        if (order.mcUuid() == null) {
          row.add("mcUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("mcUuid", order.mcUuid().toString());
        }
        if (order.deliveredAt() == null) {
          row.add("deliveredAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("deliveredAt", order.deliveredAt().toString());
        }
        if (order.refundedAt() == null) {
          row.add("refundedAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("refundedAt", order.refundedAt().toString());
        }
        if (order.refundDeadline() == null) {
          row.add("refundDeadline", JsonNull.INSTANCE);
        } else {
          row.addProperty("refundDeadline", order.refundDeadline().toString());
        }
        row.addProperty("sku", order.productSku());
        row.addProperty("productTitle", order.productTitle());
        if (order.productRemark() == null) {
          row.add("productRemark", JsonNull.INSTANCE);
        } else {
          row.addProperty("productRemark", order.productRemark());
        }
        row.addProperty("productType", order.productType());
        if (order.itemMaterial() == null) {
          row.add("itemMaterial", JsonNull.INSTANCE);
        } else {
          row.addProperty("itemMaterial", order.itemMaterial());
        }
        if (order.itemAmount() == null) {
          row.add("itemAmount", JsonNull.INSTANCE);
        } else {
          row.addProperty("itemAmount", order.itemAmount());
        }
        if (order.effectType() == null) {
          row.add("effectType", JsonNull.INSTANCE);
        } else {
          row.addProperty("effectType", order.effectType());
        }
        if (order.effectSeconds() == null) {
          row.add("effectSeconds", JsonNull.INSTANCE);
        } else {
          row.addProperty("effectSeconds", order.effectSeconds());
        }
        if (order.effectAmplifier() == null) {
          row.add("effectAmplifier", JsonNull.INSTANCE);
        } else {
          row.addProperty("effectAmplifier", order.effectAmplifier());
        }
        row.addProperty("quantity", order.quantity());
        row.addProperty("unitPrice", order.unitPrice());
        if (order.groupBuyVoucherCode() == null) {
          row.add("groupBuyVoucherCode", JsonNull.INSTANCE);
        } else {
          row.addProperty("groupBuyVoucherCode", order.groupBuyVoucherCode());
        }
        if (order.groupBuyVoucherStatus() == null) {
          row.add("groupBuyVoucherStatus", JsonNull.INSTANCE);
        } else {
          row.addProperty("groupBuyVoucherStatus", order.groupBuyVoucherStatus());
        }
        if (order.groupBuyVoucherConsumedAt() == null) {
          row.add("groupBuyVoucherConsumedAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("groupBuyVoucherConsumedAt", order.groupBuyVoucherConsumedAt().toString());
        }
        if (order.claimToken() == null) {
          row.add("claimToken", JsonNull.INSTANCE);
        } else {
          row.addProperty("claimToken", order.claimToken());
        }

        boolean canRefund = canRefund(order, now);
        row.addProperty("canRefund", canRefund);
        array.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("orders", array);
      response.addProperty("cooldownSeconds", settingsSupplier.get().orderCooldownSeconds());
      response.addProperty("refundUndeliveredEnabled", settingsSupplier.get().refundUndeliveredEnabled());
      response.addProperty("sharedClaimAllowed", settingsSupplier.get().allowSharedClaimCommand());
      sendJson(exchange, 200, response);
    });
  }

  private void handleOrdersRefund(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      String orderNo = getString(payload, "orderNo");
      OrderService.RefundResult result = orderService.refundOrder(user.id(), orderNo);
      JsonObject response = new JsonObject();
      response.addProperty("orderNo", result.orderNo());
      response.addProperty("shopCoin", result.balance().shopCoin());
      response.addProperty("gameCoin", result.balance().gameCoin());
      sendJson(exchange, 200, response);
    });
  }

  private void handleOrdersPolicy(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      int cooldownSeconds = settingsSupplier.get().orderCooldownSeconds();
      boolean refundUndeliveredEnabled = settingsSupplier.get().refundUndeliveredEnabled();
      PluginSettings.MarketEconomySettings marketSettings = settingsSupplier.get().economySettings().marketSettings();
      JsonObject response = new JsonObject();
      response.addProperty("cooldownSeconds", Math.max(0, cooldownSeconds));
      response.addProperty("refundEnabled", refundUndeliveredEnabled || cooldownSeconds > 0);
      response.addProperty("refundUndeliveredEnabled", refundUndeliveredEnabled);
      response.addProperty("marketFeePercent", marketSettings.tradeFeePercent());
      response.addProperty("marketTaxPercent", marketSettings.tradeTaxPercent());
      response.addProperty(
          "marketSupplyAutoRefreshThreshold",
          Math.max(0, settingsSupplier.get().marketSupplySettings().autoRefreshThreshold()));
      response.addProperty("sharedClaimAllowed", settingsSupplier.get().allowSharedClaimCommand());
      sendJson(exchange, 200, response);
    });
  }

  private void handleNotificationsList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 30);
      Long cursor = parseLong(query.get("cursor"));
      boolean unreadOnly = parseBoolean(query.get("unreadOnly"));
      List<NotificationService.NotificationView> notifications =
          notificationService.listForUser(user.id(), limit, cursor, unreadOnly);

      JsonArray rows = new JsonArray();
      Long nextCursor = null;
      for (NotificationService.NotificationView notification : notifications) {
        JsonObject row = new JsonObject();
        row.addProperty("id", notification.id());
        row.addProperty("type", notification.type());
        row.addProperty("title", notification.title());
        row.addProperty("content", notification.content());
        row.addProperty("isRead", notification.read());
        row.addProperty("createdAt", notification.createdAt().toString());
        if (notification.readAt() == null) {
          row.add("readAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("readAt", notification.readAt().toString());
        }
        if (notification.dataJson() == null || notification.dataJson().isBlank()) {
          row.add("data", JsonNull.INSTANCE);
        } else {
          try {
            row.add("data", JsonParser.parseString(notification.dataJson()));
          } catch (Exception ignored) {
            row.addProperty("dataRaw", notification.dataJson());
            row.add("data", JsonNull.INSTANCE);
          }
        }
        rows.add(row);
        nextCursor = notification.id();
      }

      JsonObject response = new JsonObject();
      response.add("notifications", rows);
      if (nextCursor == null) {
        response.add("nextCursor", JsonNull.INSTANCE);
      } else {
        response.addProperty("nextCursor", nextCursor);
      }
      response.addProperty("unreadCount", notificationService.countUnread(user.id()));
      sendJson(exchange, 200, response);
    });
  }

  private void handleNotificationsUnreadCount(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      JsonObject response = new JsonObject();
      response.addProperty("unreadCount", notificationService.countUnread(user.id()));
      sendJson(exchange, 200, response);
    });
  }

  private void handleNotificationsMarkRead(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      boolean markAll = payload.has("all")
          && !payload.get("all").isJsonNull()
          && payload.get("all").getAsBoolean();
      int updated;
      if (markAll) {
        updated = notificationService.markAllRead(user.id());
      } else {
        long notificationId = getLong(payload, "id", -1L);
        if (notificationId <= 0L) {
          throw new ServiceException("bad_request", "Missing field: id");
        }
        updated = notificationService.markRead(user.id(), notificationId);
      }
      JsonObject response = new JsonObject();
      response.addProperty("updated", updated);
      response.addProperty("unreadCount", notificationService.countUnread(user.id()));
      sendJson(exchange, 200, response);
    });
  }

  private void handleCurrencyMeta(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      PluginSettings.CurrencyDisplaySettings currency = settingsSupplier.get().currencyDisplaySettings();
      JsonObject shop = new JsonObject();
      shop.addProperty("name", currency.shopCoinName());
      shop.addProperty("short", currency.shopCoinShort());
      JsonObject game = new JsonObject();
      game.addProperty("name", currency.gameCoinName());
      game.addProperty("short", currency.gameCoinShort());
      JsonObject response = new JsonObject();
      response.add("shopCoin", shop);
      response.add("gameCoin", game);
      response.add("exchange", buildExchangeMetaJson());
      response.add("payment", rechargePaymentSettingsJson(settingsSupplier.get().paymentSettings()));
      response.add("paymentProvider", paymentProviderInfoJson(rechargeService.paymentProviderInfo()));
      response.addProperty("timeZone", settingsSupplier.get().timeZone().getId());
      sendJson(exchange, 200, response);
    });
  }

  private JsonObject buildExchangeMetaJson() {
    PluginSettings.ExchangeSettings settings = settingsSupplier.get().exchangeSettings();
    JsonObject shopToGame = new JsonObject();
    shopToGame.addProperty("enabled", settings.shopToGame().enabled());
    shopToGame.addProperty("ratio", settings.shopToGame().ratio());
    JsonObject gameToShop = new JsonObject();
    gameToShop.addProperty("enabled", settings.gameToShop().enabled());
    gameToShop.addProperty("ratio", settings.gameToShop().ratio());

    JsonObject exchange = new JsonObject();
    exchange.add("shopToGame", shopToGame);
    exchange.add("gameToShop", gameToShop);
    return exchange;
  }

  private void handleMaterialMeta(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonArray materials = new JsonArray();
      for (Material material : Material.values()) {
        if (material == Material.AIR || material.isLegacy()) {
          continue;
        }
        materials.add(material.name());
      }
      JsonObject response = new JsonObject();
      response.add("materials", materials);
      sendJson(exchange, 200, response);
    });
  }

  private void handleMaterialOverrideMeta(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject response = new JsonObject();
      response.add("overrides", materialOverrideListJson(materialVisualService.listAll()));
      response.add("policy", visualSettingsJson(visualCustomizationService.readSettings()));
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketTagsMeta(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      List<MarketTagService.TagMeta> tags = marketService.listMarketTagsMeta();
      JsonArray rows = new JsonArray();
      for (MarketTagService.TagMeta tag : tags) {
        JsonObject row = new JsonObject();
        row.addProperty("code", tag.code());
        row.addProperty("displayName", tag.displayName());
        row.addProperty("enabled", tag.enabled());
        row.addProperty("priority", tag.priority());
        JsonObject activeCount = new JsonObject();
        activeCount.addProperty("SELL", tag.activeSellCount());
        activeCount.addProperty("BUY", tag.activeBuyCount());
        row.add("activeCount", activeCount);
        row.addProperty("activeSellCount", tag.activeSellCount());
        row.addProperty("activeBuyCount", tag.activeBuyCount());
        rows.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("tags", rows);
      sendJson(exchange, 200, response);
    });
  }

  private void handleLeaderboardConfig(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject response = new JsonObject();
      response.add("leaderboard", leaderboardSettingsJson(settingsSupplier.get()));
      sendJson(exchange, 200, response);
    });
  }

  private void handleLeaderboardList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      PluginSettings settings = settingsSupplier.get();
      PluginSettings.LeaderboardSettings leaderboardSettings = settings.leaderboardSettings();
      if (!leaderboardSettings.enabled()) {
        throw new ServiceException("feature_disabled", "Leaderboard page is disabled");
      }

      Map<String, String> query = parseQuery(exchange);
      String metric = query.get("metric");
      String order = query.get("order");
      String range = query.get("range");
      int limit = parseInt(query.get("limit"), 100);
      boolean includeOnline = leaderboardSettings.showOnlineStatus();
      if (query.containsKey("showOnline")) {
        includeOnline = parseBoolean(query.get("showOnline"));
      }

      Long viewerUserId = findOptionalAuth(exchange).map(AuthService.AuthUser::id).orElse(null);
      LeaderboardService.LeaderboardResult result = leaderboardService.list(
          metric,
          order,
          range,
          limit,
          viewerUserId,
          includeOnline,
          leaderboardSettings);

      JsonArray rows = new JsonArray();
      for (LeaderboardService.LeaderboardEntry entry : result.entries()) {
        JsonObject row = new JsonObject();
        row.addProperty("rank", entry.rank());
        row.addProperty("userId", entry.userId());
        row.addProperty("username", entry.username());
        if (entry.boundUuid() == null) {
          row.add("boundUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("boundUuid", entry.boundUuid().toString());
        }
        row.addProperty("shopCoin", entry.shopCoin());
        row.addProperty("gameCoin", entry.gameCoin());
        row.addProperty("onlineTimeMinutes", entry.onlineTimeMinutes());
        row.addProperty("score", entry.score());
        row.addProperty("online", entry.online());
        rows.add(row);
      }

      JsonObject response = new JsonObject();
      response.add("leaderboard", leaderboardSettingsJson(settings));
      response.addProperty("metric", result.metric().name());
      response.addProperty("order", result.order().name());
      response.addProperty("requestedRange", result.requestedRange().name());
      response.addProperty("effectiveRange", result.effectiveRange().name());
      response.addProperty("total", result.total());
      if (result.myRank() == null) {
        response.add("myRank", JsonNull.INSTANCE);
      } else {
        response.addProperty("myRank", result.myRank());
      }
      response.add("entries", rows);
      sendJson(exchange, 200, response);
    });
  }

  private JsonObject leaderboardSettingsJson(PluginSettings settings) {
    PluginSettings.LeaderboardSettings leaderboard = settings.leaderboardSettings();
    JsonObject json = new JsonObject();
    json.addProperty("enabled", leaderboard.enabled());
    json.addProperty("showOnlineStatus", leaderboard.showOnlineStatus());
    json.addProperty("defaultMetric", leaderboard.defaultMetric().name());
    json.addProperty("defaultOrder", leaderboard.defaultOrder().name());
    return json;
  }

  private JsonObject webshopRuntimeJson(PluginSettings settings) {
    JsonObject json = new JsonObject();
    json.addProperty("defaultLocale", settings.defaultLocale());
    json.addProperty("sessionExpireHours", settings.sessionExpireHours());
    json.addProperty("bindRequestExpireMinutes", settings.bindRequestExpireMinutes());
    json.addProperty("accessTokenLength", settings.accessTokenLength());
    json.addProperty("deliveryBatchSize", settings.deliveryBatchSize());
    json.addProperty("deliveryRetrySeconds", settings.deliveryRetrySeconds());
    json.addProperty("orderCooldownSeconds", settings.orderCooldownSeconds());
    json.addProperty("allowSharedClaimCommand", settings.allowSharedClaimCommand());
    json.addProperty("refundUndeliveredEnabled", settings.refundUndeliveredEnabled());
    json.addProperty("timeZone", settings.timeZone().getId());
    return json;
  }

  private JsonObject marketRuntimeJson(PluginSettings settings) {
    JsonObject json = new JsonObject();
    json.addProperty("marketMaxActiveListings", settings.marketMaxActiveListings());
    PluginSettings.MarketSupplySettings supply = settings.marketSupplySettings();
    JsonObject supplyJson = new JsonObject();
    supplyJson.addProperty("autoRefreshThreshold", supply.autoRefreshThreshold());
    supplyJson.addProperty("defaultTransferBatchSize", supply.defaultTransferBatchSize());
    supplyJson.addProperty("maxTransferBatchSize", supply.maxTransferBatchSize());
    supplyJson.addProperty("defaultTransitStock", supply.defaultTransitStock());
    supplyJson.addProperty("maxTransitStock", supply.maxTransitStock());
    json.add("supply", supplyJson);
    return json;
  }

  private JsonObject maintenanceSettingsJson(PluginSettings.MaintenanceSettings settings) {
    JsonObject json = new JsonObject();
    json.addProperty("cleanupIntervalMinutes", settings.cleanupIntervalMinutes());
    json.addProperty("pendingBindRetentionHours", settings.pendingBindRetentionHours());
    json.addProperty("pendingPasswordRetentionHours", settings.pendingPasswordRetentionHours());
    json.addProperty("bindRequestRetentionHours", settings.bindRequestRetentionHours());
    json.addProperty("redeemCodeRetentionDays", settings.redeemCodeRetentionDays());
    return json;
  }

  private JsonObject loggingSettingsJson(PluginSettings.LoggingSettings settings) {
    JsonObject json = new JsonObject();
    json.addProperty("enabled", settings.enabled());
    json.addProperty("level", settings.level().name());
    json.addProperty("directory", settings.directory());
    json.addProperty("maxFileSizeMb", settings.maxFileSizeMb());
    json.addProperty("maxFiles", settings.maxFiles());
    json.addProperty("retentionDays", settings.retentionDays());
    return json;
  }

  private JsonObject broadcastSettingsJson(PluginSettings.BroadcastSettings settings) {
    JsonObject json = new JsonObject();
    json.addProperty("enabled", settings.enabled());
    JsonObject templates = new JsonObject();
    for (Map.Entry<String, String> entry : settings.templates().entrySet()) {
      templates.addProperty(entry.getKey(), entry.getValue());
    }
    json.add("templates", templates);
    return json;
  }

  private JsonObject deploymentModeJson(PluginSettings settings) {
    PluginSettings.DatabaseSettings databaseSettings = settings.databaseSettings();
    PluginSettings.ClusterSettings clusterSettings = settings.clusterSettings();
    PluginSettings.RedisSettings redisSettings = settings.redisSettings();
    DbType databaseType = databaseSettings.type();
    boolean sqliteSingleServerOnly = databaseType.isSqlite();
    boolean clusterCapable = !sqliteSingleServerOnly;
    boolean singleServerMode = clusterSettings.role() == PluginSettings.ClusterRole.STANDALONE;
    boolean clusterSyncEnabled = clusterCapable && !singleServerMode && redisSettings.enabled();

    JsonObject json = new JsonObject();
    json.addProperty("databaseType", databaseType.name());
    json.addProperty("clusterRole", clusterSettings.role().name());
    json.addProperty("redisEnabled", redisSettings.enabled());
    json.addProperty("clusterCapable", clusterCapable);
    json.addProperty("singleServerMode", singleServerMode);
    json.addProperty("clusterSyncEnabled", clusterSyncEnabled);
    json.addProperty("sqliteSingleServerOnly", sqliteSingleServerOnly);
    return json;
  }

  private JsonObject notificationSettingsJson(RuntimeConfigService.NotificationSettings settings) {
    RuntimeConfigService.NotificationSettings normalized = settings == null
        ? RuntimeConfigService.NotificationSettings.defaults()
        : settings.normalized();
    JsonObject json = new JsonObject();
    json.addProperty("marketEventsEnabled", normalized.marketEventsEnabled());
    json.addProperty("deliveryMailboxEventsEnabled", normalized.deliveryMailboxEventsEnabled());
    JsonObject templates = new JsonObject();
    for (Map.Entry<String, String> entry : normalized.templates().entrySet()) {
      templates.addProperty(entry.getKey(), entry.getValue());
    }
    json.add("templates", templates);
    return json;
  }

  private JsonObject visualSettingsJson(VisualCustomizationService.VisualSettings settings) {
    VisualCustomizationService.VisualSettings normalized = settings == null
        ? VisualCustomizationService.VisualSettings.defaults()
        : settings;
    JsonObject json = new JsonObject();
    json.addProperty("globalCustomIconEnabled", normalized.globalCustomIconEnabled());
    json.addProperty("globalCustomNameEnabled", normalized.globalCustomNameEnabled());
    json.addProperty("officialProductCustomIconEnabled", normalized.officialProductCustomIconEnabled());
    json.addProperty("officialProductCustomNameEnabled", normalized.officialProductCustomNameEnabled());
    json.addProperty("officialProductUploadImageEnabled", normalized.officialProductUploadImageEnabled());
    json.addProperty("marketListingCustomIconEnabled", normalized.marketListingCustomIconEnabled());
    json.addProperty("marketListingCustomNameEnabled", normalized.marketListingCustomNameEnabled());
    json.addProperty("marketListingUploadImageEnabled", normalized.marketListingUploadImageEnabled());
    json.addProperty("iconPolicyMode", normalized.iconPolicyMode().name());
    json.addProperty("namePolicyMode", normalized.namePolicyMode().name());
    return json;
  }

  private JsonObject userVisualPermissionJson(VisualCustomizationService.ResolvedPermission resolved) {
    JsonObject json = new JsonObject();
    json.addProperty("userId", resolved.userId());
    json.addProperty("iconPermission", resolved.iconPermission().name());
    json.addProperty("namePermission", resolved.namePermission().name());
    json.addProperty("uploadPermission", resolved.uploadPermission().name());
    json.addProperty("customIconAllowed", resolved.customIconAllowed());
    json.addProperty("customNameAllowed", resolved.customNameAllowed());
    json.addProperty("customUploadAllowed", resolved.customUploadAllowed());
    json.add("settings", visualSettingsJson(resolved.settings()));
    return json;
  }

  private void addListingLimitJson(
      JsonObject json,
      UserMarketSettingsService.ResolvedListingLimit resolved) {
    if (json == null || resolved == null) {
      return;
    }
    json.addProperty("listingLimitEffective", resolved.effectiveLimit());
    json.addProperty("listingLimitSource", resolved.source().name());
    json.addProperty("playerOnline", resolved.playerOnline());
    json.addProperty("globalDefaultLimit", resolved.globalDefaultLimit());
    if (resolved.listingLimitOverride() == null) {
      json.add("listingLimitOverride", JsonNull.INSTANCE);
    } else {
      json.addProperty("listingLimitOverride", resolved.listingLimitOverride());
    }
    if (resolved.permissionLimit() == null) {
      json.add("permissionLimit", JsonNull.INSTANCE);
    } else {
      json.addProperty("permissionLimit", resolved.permissionLimit());
    }
  }

  private void handleMarketListings(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 100);
      boolean mineOnly = parseBoolean(query.get("mine"));
      String sort = query.get("sort");
      String order = query.get("order");
      boolean ascending = order != null && order.equalsIgnoreCase("asc");
      CurrencyType currency = null;
      String currencyRaw = query.get("currency");
      if (currencyRaw != null && !currencyRaw.isBlank()) {
        currency = CurrencyType.fromConfig(currencyRaw);
      }
      Long minPrice = parseLong(query.get("minPrice"));
      Long maxPrice = parseLong(query.get("maxPrice"));
      String material = query.get("material");
      String keyword = query.get("keyword");
      MarketSide side = null;
      String sideRaw = query.get("side");
      if (sideRaw != null && !sideRaw.isBlank()) {
        side = MarketSide.fromRaw(sideRaw);
      }
      String tag = null;
      String tagRaw = query.containsKey("tag") ? query.get("tag") : null;
      if (tagRaw != null && !tagRaw.isBlank()) {
        tag = tagRaw.trim().toLowerCase(Locale.ROOT);
      }
      List<String> tags = null;
      String tagsRaw = query.containsKey("tags[]") ? query.get("tags[]") : query.get("tags");
      if (tagsRaw != null && !tagsRaw.isBlank()) {
        tags = Arrays.stream(tagsRaw.split(","))
            .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
      }

      Long sellerUserId = null;
      boolean activeOnly = !mineOnly;
      if (mineOnly) {
        AuthService.AuthUser user = requireAuth(exchange, null);
        sellerUserId = user.id();
      }

      MarketService.ListingQuery listingQuery = new MarketService.ListingQuery(
          sellerUserId,
          activeOnly,
          sort,
          ascending,
          currency,
          minPrice,
          maxPrice,
          material == null ? null : material.trim().toUpperCase(Locale.ROOT),
          keyword == null ? null : keyword.trim(),
          side,
          tag,
          tags,
          limit);
      List<MarketService.ListingView> listings = marketService.listListings(listingQuery);

      JsonArray rows = new JsonArray();
      for (MarketService.ListingView listing : listings) {
        JsonObject row = new JsonObject();
        row.addProperty("id", listing.id());
        row.addProperty("sellerUserId", listing.sellerUserId());
        row.addProperty("sellerName", listing.sellerName());
        row.addProperty("sellerUuid", listing.sellerUuid().toString());
        row.addProperty("currency", listing.currency().name());
        row.addProperty("price", listing.price());
        row.addProperty("quantity", listing.quantity());
        row.addProperty("quantityTotal", listing.quantityTotal());
        row.addProperty("side", listing.side().name());
        if (listing.tag() == null) {
          row.add("tag", JsonNull.INSTANCE);
        } else {
          row.addProperty("tag", listing.tag());
        }
        row.addProperty("tagVersion", listing.tagVersion());
        row.addProperty("escrowTotal", listing.escrowTotal());
        row.addProperty("escrowRemaining", listing.escrowRemaining());
        row.addProperty("itemMaterial", listing.itemMaterial());
        if (listing.displayNameOverride() == null) {
          row.add("displayNameOverride", JsonNull.INSTANCE);
        } else {
          row.addProperty("displayNameOverride", listing.displayNameOverride());
        }
        if (listing.displayMaterial() == null) {
          row.add("displayMaterial", JsonNull.INSTANCE);
        } else {
          row.addProperty("displayMaterial", listing.displayMaterial());
        }
        if (listing.displayIconPath() == null) {
          row.add("displayIconPath", JsonNull.INSTANCE);
        } else {
          row.addProperty("displayIconPath", listing.displayIconPath());
        }
        row.addProperty("itemMetaJson", listing.itemMetaJson());
        if (listing.remark() == null) {
          row.add("remark", JsonNull.INSTANCE);
        } else {
          row.addProperty("remark", listing.remark());
        }
        row.addProperty("status", listing.status());
        row.addProperty("createdAt", listing.createdAt().toString());
        row.addProperty("sourceMode", listing.sourceMode().name());
        row.addProperty("tradeMode", listing.tradeMode().name());
        row.addProperty("dynamicPricingEnabled", listing.dynamicPricingEnabled());
        row.addProperty("dynamicAlgorithm", listing.dynamicAlgorithm());
        if (listing.dynamicParamsJson() == null) {
          row.add("dynamicParamsJson", JsonNull.INSTANCE);
        } else {
          row.addProperty("dynamicParamsJson", listing.dynamicParamsJson());
        }
        if (listing.dynamicBasePrice() == null) {
          row.add("dynamicBasePrice", JsonNull.INSTANCE);
        } else {
          row.addProperty("dynamicBasePrice", listing.dynamicBasePrice());
        }
        if (listing.dynamicFloorPrice() == null) {
          row.add("dynamicFloorPrice", JsonNull.INSTANCE);
        } else {
          row.addProperty("dynamicFloorPrice", listing.dynamicFloorPrice());
        }
        if (listing.dynamicCapPrice() == null) {
          row.add("dynamicCapPrice", JsonNull.INSTANCE);
        } else {
          row.addProperty("dynamicCapPrice", listing.dynamicCapPrice());
        }
        if (listing.dynamicPriceStep() == null) {
          row.add("dynamicPriceStep", JsonNull.INSTANCE);
        } else {
          row.addProperty("dynamicPriceStep", listing.dynamicPriceStep());
        }
        row.addProperty("dynamicDemandScore", listing.dynamicDemandScore());
        row.addProperty("auctionAlgorithm", listing.auctionAlgorithm());
        if (listing.auctionParamsJson() == null) {
          row.add("auctionParamsJson", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionParamsJson", listing.auctionParamsJson());
        }
        if (listing.auctionStartPrice() == null) {
          row.add("auctionStartPrice", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionStartPrice", listing.auctionStartPrice());
        }
        if (listing.auctionMinIncrement() == null) {
          row.add("auctionMinIncrement", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionMinIncrement", listing.auctionMinIncrement());
        }
        if (listing.auctionEndAt() == null) {
          row.add("auctionEndAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "auctionEndAt", listing.auctionEndAt());
        }
        if (listing.auctionStartedAt() == null) {
          row.add("auctionStartedAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "auctionStartedAt", listing.auctionStartedAt());
        }
        if (listing.auctionPublicEndAt() == null) {
          row.add("auctionPublicEndAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "auctionPublicEndAt", listing.auctionPublicEndAt());
        }
        if (listing.auctionHighestBid() == null) {
          row.add("auctionHighestBid", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBid", listing.auctionHighestBid());
        }
        if (listing.auctionHighestBidderUserId() == null) {
          row.add("auctionHighestBidderUserId", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBidderUserId", listing.auctionHighestBidderUserId());
        }
        if (listing.auctionHighestBidderUuid() == null) {
          row.add("auctionHighestBidderUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBidderUuid", listing.auctionHighestBidderUuid().toString());
        }
        if (listing.auctionHighestBidId() == null) {
          row.add("auctionHighestBidId", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBidId", listing.auctionHighestBidId());
        }
        if (listing.auctionLastBidAt() == null) {
          row.add("auctionLastBidAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "auctionLastBidAt", listing.auctionLastBidAt());
        }
        if (listing.supplyBatchSize() == null) {
          row.add("supplyBatchSize", JsonNull.INSTANCE);
        } else {
          row.addProperty("supplyBatchSize", listing.supplyBatchSize());
        }
        if (listing.supplyMaxStock() == null) {
          row.add("supplyMaxStock", JsonNull.INSTANCE);
        } else {
          row.addProperty("supplyMaxStock", listing.supplyMaxStock());
        }
        row.addProperty("supplyLoadedTotal", listing.supplyLoadedTotal());
        row.addProperty("supplySoldTotal", listing.supplySoldTotal());
        if (listing.supplyLastLoadedAmount() == null) {
          row.add("supplyLastLoadedAmount", JsonNull.INSTANCE);
        } else {
          row.addProperty("supplyLastLoadedAmount", listing.supplyLastLoadedAmount());
        }
        if (listing.supplyLastLoadedAt() == null) {
          row.add("supplyLastLoadedAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("supplyLastLoadedAt", listing.supplyLastLoadedAt().toString());
        }
        rows.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("listings", rows);
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketListingsCreate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      MarketSide side = MarketSide.fromRaw(getOptionalString(payload, "side").orElse("SELL"));
      CurrencyType currency = payload.has("currency")
          ? CurrencyType.fromConfig(getString(payload, "currency"))
          : CurrencyType.GAME_COIN;
      long price = getLong(payload, "price", 0L);
      int quantity = payload.has("quantity")
          ? (int) getLong(payload, "quantity", 1L)
          : (int) getLong(payload, "amount", 1L);
      String tag = getOptionalString(payload, "tag").orElse(null);
      String tradeMode = getOptionalString(payload, "tradeMode")
          .orElse("DIRECT")
          .trim()
          .toUpperCase(Locale.ROOT);
      if (!tradeMode.equals("DIRECT") && !tradeMode.equals("AUCTION")) {
        throw new ServiceException("invalid_trade_mode", "Trade mode must be DIRECT or AUCTION");
      }

      MarketService.ListingCreateResult result;
      if (side == MarketSide.BUY) {
        if (!tradeMode.equals("DIRECT")) {
          throw new ServiceException("buy_requires_direct_mode", "BUY listings only support DIRECT trade mode");
        }
        String itemMaterial = getString(payload, "itemMaterial");
        result = marketService.createBuyListing(
            user.id(),
            itemMaterial,
            price,
            quantity,
            currency,
            tag);
      } else {
        if (!tradeMode.equals("DIRECT")) {
          throw new ServiceException("invalid_trade_mode", "SELL listing creation currently supports DIRECT only");
        }
        result = awaitPlayerTask(
            user.boundUuid(),
            player -> marketService.createListingFromPlayer(player, price, quantity, currency, tag));
      }

      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("material", result.material());
      response.addProperty("quantity", result.quantity());
      response.addProperty("currency", result.currency().name());
      response.addProperty("price", result.price());
      response.addProperty("side", result.side().name());
      if (result.tag() == null) {
        response.add("tag", JsonNull.INSTANCE);
      } else {
        response.addProperty("tag", result.tag());
      }
      response.addProperty("escrowTotal", result.escrowTotal());
      response.addProperty("escrowRemaining", result.escrowRemaining());
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketBuy(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      int buyQuantity = (int) getLong(payload, "buyQuantity", 1L);
      String deliveryMode = getOptionalString(payload, "deliveryMode").orElse(null);
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      MarketService.TradeResult result =
          marketService.buyListing(user.id(), listingId, buyQuantity, idempotencyKey, deliveryMode);
      sendJson(exchange, 200, toTradeResultJson(result));
    });
  }

  private void handleMarketSellToBuy(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      int sellQuantity = payload.has("sellQuantity")
          ? (int) getLong(payload, "sellQuantity", 1L)
          : payload.has("fulfillQuantity")
              ? (int) getLong(payload, "fulfillQuantity", 1L)
              : (int) getLong(payload, "quantity", 1L);
      String deliveryMode = getOptionalString(payload, "deliveryMode").orElse(null);
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      MarketService.TradeResult result = marketService.fulfillBuyOrder(
          user.id(),
          listingId,
          sellQuantity,
          idempotencyKey,
          deliveryMode);
      sendJson(exchange, 200, toTradeResultJson(result));
    });
  }

  private JsonObject toTradeResultJson(MarketService.TradeResult result) {
    JsonObject response = new JsonObject();
    response.addProperty("state", result.state().name());
    response.addProperty("tradeId", result.tradeId());
    response.addProperty("listingId", result.listingId());
    response.addProperty("currency", result.currency().name());
    response.addProperty("unitPrice", result.unitPrice());
    response.addProperty("quantity", result.quantity());
    response.addProperty("totalPrice", result.totalPrice());
    response.addProperty("buyerTotal", result.buyerTotal());
    response.addProperty("sellerReceive", result.sellerReceive());
    response.addProperty("feeAmount", result.feeAmount());
    response.addProperty("taxAmount", result.taxAmount());
    response.addProperty("orderStatus", result.orderStatus());
    response.addProperty("cooldownSeconds", result.cooldownSeconds());
    if (result.refundDeadline() == null) {
      response.add("refundDeadline", JsonNull.INSTANCE);
    } else {
      response.addProperty("refundDeadline", result.refundDeadline().toString());
    }
    return response;
  }

  private void handleMarketBid(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      long bidAmount = getLong(payload, "bidAmount", 0L);
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      MarketService.BidResult result = marketService.placeBid(user.id(), listingId, bidAmount, idempotencyKey);
      JsonObject response = new JsonObject();
      response.addProperty("state", result.state().name());
      response.addProperty("bidId", result.bidId());
      response.addProperty("listingId", result.listingId());
      response.addProperty("currency", result.currency().name());
      response.addProperty("bidAmount", result.bidAmount());
      response.addProperty("currentHighestBid", result.currentHighestBid());
      response.addProperty("auctionAlgorithm", result.auctionAlgorithm());
      response.addProperty("sealedBid", result.sealedBid());
      if (result.previousHighestBid() == null) {
        response.add("previousHighestBid", JsonNull.INSTANCE);
      } else {
        response.addProperty("previousHighestBid", result.previousHighestBid());
      }
      if (result.previousHighestBidderUserId() == null) {
        response.add("previousHighestBidderUserId", JsonNull.INSTANCE);
      } else {
        response.addProperty("previousHighestBidderUserId", result.previousHighestBidderUserId());
      }
      if (result.auctionEndAt() == null) {
        response.add("auctionEndAt", JsonNull.INSTANCE);
      } else {
        addBusinessDateTime(response, "auctionEndAt", result.auctionEndAt());
      }
      if (result.minimumRequiredBid() == null) {
        response.add("minimumRequiredBid", JsonNull.INSTANCE);
      } else {
        response.addProperty("minimumRequiredBid", result.minimumRequiredBid());
      }
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketUnlist(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      MarketService.UnlistResult result = marketService.unlist(user.id(), listingId);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("currency", result.currency().name());
      response.addProperty("price", result.price());
      response.addProperty("quantity", result.quantity());
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketPause(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      MarketService.ListingStatusResult result = marketService.pause(user.id(), listingId);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("status", result.status());
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketResume(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      MarketService.ListingStatusResult result = marketService.resume(user.id(), listingId);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("status", result.status());
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketPrice(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      long price = getLong(payload, "price", 0L);
      MarketService.ListingPriceUpdateResult result =
          marketService.updateListingPrice(user.id(), listingId, price);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("currency", result.currency().name());
      response.addProperty("price", result.price());
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketRemark(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      String remark = getOptionalString(payload, "remark").orElse(null);
      MarketService.ListingRemarkUpdateResult result =
          marketService.updateListingRemark(user.id(), listingId, remark);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      if (result.remark() == null) {
        response.add("remark", JsonNull.INSTANCE);
      } else {
        response.addProperty("remark", result.remark());
      }
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketSettings(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      long price = getLong(payload, "price", 0L);
      CurrencyType currency = CurrencyType.fromConfig(getString(payload, "currency"));
      String tag = payload.has("tag")
          ? getOptionalString(payload, "tag").orElse(null)
          : null;
      String remark = getOptionalString(payload, "remark").orElse(null);
      String displayNameOverride = payload.has("displayNameOverride")
          ? getOptionalString(payload, "displayNameOverride").orElse(null)
          : null;
      String displayMaterial = payload.has("displayMaterial")
          ? getOptionalString(payload, "displayMaterial").orElse(null)
          : null;
      String displayIconPath = payload.has("displayIconPath")
          ? getOptionalString(payload, "displayIconPath").orElse(null)
          : null;
      Integer supplyBatchSize = payload.has("supplyBatchSize") && !payload.get("supplyBatchSize").isJsonNull()
          ? (int) getLong(payload, "supplyBatchSize", 0L)
          : null;
      Integer supplyMaxStock = payload.has("supplyMaxStock") && !payload.get("supplyMaxStock").isJsonNull()
          ? (int) getLong(payload, "supplyMaxStock", 0L)
          : null;
        String tradeMode = getOptionalString(payload, "tradeMode").orElse(null);
        Boolean dynamicPricingEnabled = payload.has("dynamicPricingEnabled")
          && !payload.get("dynamicPricingEnabled").isJsonNull()
          ? payload.get("dynamicPricingEnabled").getAsBoolean()
          : null;
        String dynamicAlgorithm = getOptionalString(payload, "dynamicAlgorithm").orElse(null);
        String dynamicParamsJson = null;
        if (payload.has("dynamicParamsJson") && !payload.get("dynamicParamsJson").isJsonNull()) {
          JsonElement dynamicParamsElement = payload.get("dynamicParamsJson");
          dynamicParamsJson = dynamicParamsElement.isJsonPrimitive() && dynamicParamsElement.getAsJsonPrimitive().isString()
              ? dynamicParamsElement.getAsString()
              : dynamicParamsElement.toString();
        }
        Long dynamicBasePrice = payload.has("dynamicBasePrice") && !payload.get("dynamicBasePrice").isJsonNull()
          ? getLong(payload, "dynamicBasePrice", 0L)
          : null;
        Long dynamicFloorPrice = payload.has("dynamicFloorPrice") && !payload.get("dynamicFloorPrice").isJsonNull()
          ? getLong(payload, "dynamicFloorPrice", 0L)
          : null;
        Long dynamicCapPrice = payload.has("dynamicCapPrice") && !payload.get("dynamicCapPrice").isJsonNull()
          ? getLong(payload, "dynamicCapPrice", 0L)
          : null;
        Long dynamicPriceStep = payload.has("dynamicPriceStep") && !payload.get("dynamicPriceStep").isJsonNull()
          ? getLong(payload, "dynamicPriceStep", 0L)
          : null;
        String auctionAlgorithm = getOptionalString(payload, "auctionAlgorithm").orElse(null);
        String auctionParamsJson = null;
        if (payload.has("auctionParamsJson") && !payload.get("auctionParamsJson").isJsonNull()) {
          JsonElement auctionParamsElement = payload.get("auctionParamsJson");
          auctionParamsJson = auctionParamsElement.isJsonPrimitive() && auctionParamsElement.getAsJsonPrimitive().isString()
              ? auctionParamsElement.getAsString()
              : auctionParamsElement.toString();
        }
        Long auctionStartPrice = payload.has("auctionStartPrice") && !payload.get("auctionStartPrice").isJsonNull()
          ? getLong(payload, "auctionStartPrice", 0L)
          : null;
        Long auctionMinIncrement = payload.has("auctionMinIncrement") && !payload.get("auctionMinIncrement").isJsonNull()
          ? getLong(payload, "auctionMinIncrement", 0L)
          : null;
        LocalDateTime auctionEndAt = getOptionalDateTime(payload, "auctionEndAt");

      boolean wantsCustomName = displayNameOverride != null && !displayNameOverride.isBlank();
      boolean wantsCustomIcon = (displayMaterial != null && !displayMaterial.isBlank())
          || (displayIconPath != null && !displayIconPath.isBlank());
      if (wantsCustomName || wantsCustomIcon) {
        VisualCustomizationService.ResolvedPermission permission =
            visualCustomizationService.resolvePermission(user.id());
        if (wantsCustomName && !permission.customNameAllowed()) {
          throw new ServiceException(
              "forbidden",
              "Custom listing name is disabled by current visual customization policy");
        }
        if (wantsCustomIcon && !permission.customIconAllowed()) {
          throw new ServiceException(
              "forbidden",
              "Custom listing icon is disabled by current visual customization policy");
        }
      }

      MarketService.ListingSettingsUpdateResult result = marketService.updateListingSettings(
          user.id(),
          listingId,
          price,
          currency,
          tag,
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
          auctionEndAt);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("currency", result.currency().name());
      response.addProperty("price", result.price());
      response.addProperty("side", result.side().name());
      if (result.tag() == null) {
        response.add("tag", JsonNull.INSTANCE);
      } else {
        response.addProperty("tag", result.tag());
      }
      response.addProperty("tagVersion", result.tagVersion());
      response.addProperty("sourceMode", result.sourceMode().name());
        response.addProperty("tradeMode", result.tradeMode().name());
      response.addProperty("quantityTotal", result.quantityTotal());
        response.addProperty("dynamicPricingEnabled", result.dynamicPricingEnabled());
        response.addProperty("dynamicAlgorithm", result.dynamicAlgorithm());
        response.addProperty("dynamicDemandScore", result.dynamicDemandScore());
      if (result.dynamicParamsJson() == null) {
        response.add("dynamicParamsJson", JsonNull.INSTANCE);
      } else {
        response.addProperty("dynamicParamsJson", result.dynamicParamsJson());
      }
      response.addProperty("auctionAlgorithm", result.auctionAlgorithm());
      if (result.auctionParamsJson() == null) {
        response.add("auctionParamsJson", JsonNull.INSTANCE);
      } else {
        response.addProperty("auctionParamsJson", result.auctionParamsJson());
      }
      if (result.remark() == null) {
        response.add("remark", JsonNull.INSTANCE);
      } else {
        response.addProperty("remark", result.remark());
      }
      if (result.displayNameOverride() == null) {
        response.add("displayNameOverride", JsonNull.INSTANCE);
      } else {
        response.addProperty("displayNameOverride", result.displayNameOverride());
      }
      if (result.displayMaterial() == null) {
        response.add("displayMaterial", JsonNull.INSTANCE);
      } else {
        response.addProperty("displayMaterial", result.displayMaterial());
      }
      if (result.displayIconPath() == null) {
        response.add("displayIconPath", JsonNull.INSTANCE);
      } else {
        response.addProperty("displayIconPath", result.displayIconPath());
      }
      if (result.supplyBatchSize() == null) {
        response.add("supplyBatchSize", JsonNull.INSTANCE);
      } else {
        response.addProperty("supplyBatchSize", result.supplyBatchSize());
      }
      if (result.supplyMaxStock() == null) {
        response.add("supplyMaxStock", JsonNull.INSTANCE);
      } else {
        response.addProperty("supplyMaxStock", result.supplyMaxStock());
      }
      if (result.dynamicBasePrice() == null) {
        response.add("dynamicBasePrice", JsonNull.INSTANCE);
      } else {
        response.addProperty("dynamicBasePrice", result.dynamicBasePrice());
      }
      if (result.dynamicFloorPrice() == null) {
        response.add("dynamicFloorPrice", JsonNull.INSTANCE);
      } else {
        response.addProperty("dynamicFloorPrice", result.dynamicFloorPrice());
      }
      if (result.dynamicCapPrice() == null) {
        response.add("dynamicCapPrice", JsonNull.INSTANCE);
      } else {
        response.addProperty("dynamicCapPrice", result.dynamicCapPrice());
      }
      if (result.dynamicPriceStep() == null) {
        response.add("dynamicPriceStep", JsonNull.INSTANCE);
      } else {
        response.addProperty("dynamicPriceStep", result.dynamicPriceStep());
      }
      if (result.auctionStartPrice() == null) {
        response.add("auctionStartPrice", JsonNull.INSTANCE);
      } else {
        response.addProperty("auctionStartPrice", result.auctionStartPrice());
      }
      if (result.auctionMinIncrement() == null) {
        response.add("auctionMinIncrement", JsonNull.INSTANCE);
      } else {
        response.addProperty("auctionMinIncrement", result.auctionMinIncrement());
      }
      if (result.auctionEndAt() == null) {
        response.add("auctionEndAt", JsonNull.INSTANCE);
      } else {
        addBusinessDateTime(response, "auctionEndAt", result.auctionEndAt());
      }
      if (result.auctionStartedAt() == null) {
        response.add("auctionStartedAt", JsonNull.INSTANCE);
      } else {
        addBusinessDateTime(response, "auctionStartedAt", result.auctionStartedAt());
      }
      if (result.auctionPublicEndAt() == null) {
        response.add("auctionPublicEndAt", JsonNull.INSTANCE);
      } else {
        addBusinessDateTime(response, "auctionPublicEndAt", result.auctionPublicEndAt());
      }
      if (result.auctionHighestBid() == null) {
        response.add("auctionHighestBid", JsonNull.INSTANCE);
      } else {
        response.addProperty("auctionHighestBid", result.auctionHighestBid());
      }
      if (result.auctionHighestBidderUserId() == null) {
        response.add("auctionHighestBidderUserId", JsonNull.INSTANCE);
      } else {
        response.addProperty("auctionHighestBidderUserId", result.auctionHighestBidderUserId());
      }
      if (result.auctionHighestBidId() == null) {
        response.add("auctionHighestBidId", JsonNull.INSTANCE);
      } else {
        response.addProperty("auctionHighestBidId", result.auctionHighestBidId());
      }
      if (result.auctionLastBidAt() == null) {
        response.add("auctionLastBidAt", JsonNull.INSTANCE);
      } else {
        addBusinessDateTime(response, "auctionLastBidAt", result.auctionLastBidAt());
      }
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketIconUpload(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      VisualCustomizationService.ResolvedPermission permission =
          visualCustomizationService.resolvePermission(user.id());
      if (!permission.customIconAllowed()) {
        throw new ServiceException("forbidden", "Custom listing icon is disabled by current visual customization policy");
      }
      if (!permission.customUploadAllowed()) {
        throw new ServiceException("forbidden", "Custom listing icon upload is disabled by current visual customization policy");
      }

      Map<String, String> query = parseQuery(exchange);
      Long listingIdRaw = parseLong(query.get("listingId"));
      long listingId = listingIdRaw == null ? -1L : listingIdRaw;
      if (listingId <= 0L) {
        throw new ServiceException("bad_request", "Missing or invalid listingId");
      }

      String ext = resolveIconUploadExtension(
          query.get("filename"),
          exchange.getRequestHeaders().getFirst("X-File-Name"),
          exchange.getRequestHeaders().getFirst("Content-Type"));
      byte[] content = readRequestBodyWithLimit(exchange, MATERIAL_ICON_MAX_UPLOAD_BYTES);
      if (content.length == 0) {
        throw new ServiceException("bad_request", "Empty file content");
      }

      Path iconRoot = resolveListingIconRoot();
      String fileName = "listing-"
          + listingId
          + "-"
          + System.currentTimeMillis()
          + "-"
          + UUID.randomUUID().toString().substring(0, 8)
          + "."
          + ext;
      Path output = iconRoot.resolve(fileName).normalize();
      if (!output.startsWith(iconRoot)) {
        throw new ServiceException("bad_request", "Invalid upload target");
      }
      Files.write(
          output,
          content,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);

      String iconPath = "/uploads/listing-icons/" + fileName;
      MarketService.ListingVisualUpdateResult saved =
          marketService.updateListingDisplayIconPath(user.id(), listingId, iconPath);
      if (saved.previousDisplayIconPath() != null
          && !saved.previousDisplayIconPath().isBlank()
          && !saved.previousDisplayIconPath().equals(saved.displayIconPath())) {
        deleteManagedListingIcon(saved.previousDisplayIconPath());
      }

      JsonObject response = new JsonObject();
      response.addProperty("listingId", saved.listingId());
      if (saved.displayNameOverride() == null) {
        response.add("displayNameOverride", JsonNull.INSTANCE);
      } else {
        response.addProperty("displayNameOverride", saved.displayNameOverride());
      }
      if (saved.displayMaterial() == null) {
        response.add("displayMaterial", JsonNull.INSTANCE);
      } else {
        response.addProperty("displayMaterial", saved.displayMaterial());
      }
      if (saved.displayIconPath() == null) {
        response.add("displayIconPath", JsonNull.INSTANCE);
      } else {
        response.addProperty("displayIconPath", saved.displayIconPath());
      }
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketSupplyRefresh(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAuth(exchange, payload);
      long listingId = getLong(payload, "listingId", -1L);
      MarketService.SupplyRefreshResult result = marketService.refreshSupplyListing(listingId);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("loadedAmount", result.loadedAmount());
      response.addProperty("currentStock", result.currentStock());
      response.addProperty("maxStock", result.maxStock());
      response.addProperty("loadedTotal", result.loadedTotal());
      response.addProperty("soldTotal", result.soldTotal());
      response.addProperty("status", result.status());
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminLogin(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      String identifier = getOptionalString(payload, "identifier")
          .or(() -> getOptionalString(payload, "username"))
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: identifier"));
      String password = getString(payload, "password");
      AdminService.AdminLoginResult result = adminService.login(identifier, password);
      AuthService.AuthResult auth = result.authResult();
      JsonObject response = sessionResponse(auth);
      response.add("admin", adminProfileJson(result.admin()));
      sendJson(exchange, 200, response);

      adminAuditService.log(result.admin(), "ADMIN_LOGIN", "admin", auth.user().username(), null, clientIp(exchange));
    });
  }

  private void handleAdminMe(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      AdminService.AdminUser admin = adminService.getAdminUser(user);
      sendJson(exchange, 200, adminProfileJson(admin));
    });
  }

  private void handleAdminLogout(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      String token = readHeaderToken(exchange);
      if (token == null) {
        throw new ServiceException("auth_required", "Missing session token");
      }
      AuthService.AuthUser user = authService.findUserBySession(token)
          .orElseThrow(() -> new ServiceException("auth_invalid", "Session token is invalid or expired"));
      AdminService.AdminUser admin = adminService.getAdminUser(user);
      authService.logout(token);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);

      adminAuditService.log(admin, "ADMIN_LOGOUT", "admin", user.username(), null, clientIp(exchange));
    });
  }

  private void handleAdminL10nManifest(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      String rawUrl = query.get("url");
      if (rawUrl == null || rawUrl.isBlank()) {
        throw new ServiceException("bad_request", "Missing field: url");
      }
      URI manifestUri = parseManifestUri(rawUrl);
      RemoteFetchOptions remoteFetchOptions = resolveRemoteFetchOptions(manifestUri, query.get("githubProxy"), query.get("githubProxyPrefix"));
      JsonObject manifest = fetchRemoteManifest(manifestUri, remoteFetchOptions);
      sendJson(exchange, 200, manifest);
    });
  }

  private void handleMetaLocales(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject state = localeCenterService.listState();
      JsonObject response = new JsonObject();
      response.addProperty("defaultLocale", getOptionalString(state, "defaultLocale").orElse("zh-CN"));
      response.add("locales", localeCenterService.listPublicWebLocales());
      sendJson(exchange, 200, response);
    });
  }

  private void handleMetaThemes(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject state = themeCenterService.listState();
      JsonObject response = new JsonObject();
      response.addProperty("defaultTheme", getOptionalString(state, "defaultTheme").orElse("default"));
      response.add("themes", themeCenterService.listPublicWebThemes());
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminLocalesList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      sendJson(exchange, 200, localeCenterService.listState());
    });
  }

  private void handleAdminLocalesDefault(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String defaultLocale = getOptionalString(payload, "defaultLocale")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: defaultLocale"));
      JsonObject state = localeCenterService.updateDefaultLocale(defaultLocale);
      JsonObject response = new JsonObject();
      response.add("state", state);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminLocalesAction(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String locale = getOptionalString(payload, "locale")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: locale"));
      String action = getOptionalString(payload, "action")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: action"));
      JsonObject state = localeCenterService.applyLocaleAction(locale, action);
      JsonObject response = new JsonObject();
      response.add("state", state);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminLocalesUpload(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String fileName = getOptionalString(payload, "fileName").orElse("locale-pack.zip");
      String contentBase64 = getString(payload, "contentBase64");
      LocaleCenterService.InstallOutcome outcome = localeCenterService.installBase64Package(
          fileName,
          contentBase64,
          LocaleCenterService.installOptionsFromJson(payload));

      JsonArray changed = new JsonArray();
      for (JsonObject row : outcome.changed()) {
        changed.add(row);
      }

      JsonObject response = new JsonObject();
      response.add("state", outcome.state());
      response.add("changed", changed);
      response.addProperty("fileCount", outcome.fileCount());
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminLocalesSyncManifest(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);

      String rawUrl = getOptionalString(payload, "url")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: url"));
      URI manifestUri = parseManifestUri(rawUrl);
      RemoteFetchOptions remoteFetchOptions = resolveRemoteFetchOptions(
          manifestUri,
          getOptionalString(payload, "githubProxy").orElse(""),
          getOptionalString(payload, "githubProxyPrefix").orElse(""));
      JsonObject manifest = fetchRemoteManifest(manifestUri, remoteFetchOptions);
      List<ManifestLocalePackage> entries = parseManifestLocalePackages(manifest);
      if (entries.isEmpty()) {
        throw new ServiceException("bad_request", "Manifest contains no locale package entries");
      }

      Set<String> requestedLocales = new java.util.LinkedHashSet<>();
      for (String rawLocale : getStringArray(payload, "locales")) {
        String locale = canonicalizeLocaleTag(rawLocale);
        if (!locale.isBlank()) {
          requestedLocales.add(locale);
        }
      }

      JsonArray results = new JsonArray();
      int succeeded = 0;
      int failed = 0;
      String manifestVersion = getOptionalString(manifest, "version").orElse("");

      for (ManifestLocalePackage entry : entries) {
        if (!requestedLocales.isEmpty() && !requestedLocales.contains(entry.locale())) {
          continue;
        }

        JsonObject row = new JsonObject();
        row.addProperty("locale", entry.locale());
        row.addProperty("version", entry.version());
        row.addProperty("packageUrl", entry.packageUrl());
        try {
          URI packageUri = parseManifestUri(entry.packageUrl());
          byte[] packageBytes = fetchRemoteBinary(
              packageUri,
              "locale package",
              REMOTE_LOCALE_PACKAGE_MAX_BYTES,
              remoteFetchOptions);
          LocaleCenterService.InstallOutcome outcome = localeCenterService.installZipPackage(
              packageBytes,
              new LocaleCenterService.InstallOptions("github", entry.version(), entry.name(), entry.nativeName()));
          JsonArray changed = new JsonArray();
          for (JsonObject changedRow : outcome.changed()) {
            String changedLocale = getOptionalString(changedRow, "locale").orElse("");
            if (!changedLocale.isBlank()) {
              changed.add(changedLocale);
            }
          }
          row.addProperty("result", "ok");
          row.add("changedLocales", changed);
          row.addProperty("fileCount", outcome.fileCount());
          succeeded += 1;
        } catch (Exception exception) {
          row.addProperty("result", "failed");
          row.addProperty("message", exception.getMessage() == null ? "unknown error" : exception.getMessage());
          failed += 1;
        }
        results.add(row);
      }

      if (results.size() <= 0) {
        throw new ServiceException("bad_request", "No locale matched the requested filter");
      }

      JsonObject response = new JsonObject();
      response.addProperty("manifestVersion", manifestVersion);
      response.addProperty("total", results.size());
      response.addProperty("succeeded", succeeded);
      response.addProperty("failed", failed);
      response.add("results", results);
      response.add("state", localeCenterService.listState());
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminThemesList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      sendJson(exchange, 200, themeCenterService.listState());
    });
  }

  private void handleAdminThemesDefault(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String defaultTheme = getOptionalString(payload, "defaultTheme")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: defaultTheme"));
      JsonObject state = themeCenterService.updateDefaultTheme(defaultTheme);
      JsonObject response = new JsonObject();
      response.add("state", state);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminThemesAction(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String themeId = getOptionalString(payload, "themeId")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: themeId"));
      String action = getOptionalString(payload, "action")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: action"));
      JsonObject state = themeCenterService.applyThemeAction(themeId, action);
      JsonObject response = new JsonObject();
      response.add("state", state);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminThemesUpload(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String fileName = getOptionalString(payload, "fileName").orElse("theme-pack.zip");
      String contentBase64 = getString(payload, "contentBase64");
      ThemeCenterService.InstallOutcome outcome = themeCenterService.installBase64Package(
          fileName,
          contentBase64,
          ThemeCenterService.installOptionsFromJson(payload));

      JsonArray changed = new JsonArray();
      for (JsonObject row : outcome.changed()) {
        changed.add(row);
      }

      JsonObject response = new JsonObject();
      response.add("state", outcome.state());
      response.add("changed", changed);
      response.addProperty("fileCount", outcome.fileCount());
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminThemesSyncManifest(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);

      String rawUrl = getOptionalString(payload, "url")
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: url"));
      URI manifestUri = parseManifestUri(rawUrl);
      RemoteFetchOptions remoteFetchOptions = resolveRemoteFetchOptions(
          manifestUri,
          getOptionalString(payload, "githubProxy").orElse(""),
          getOptionalString(payload, "githubProxyPrefix").orElse(""));
      JsonObject manifest = fetchRemoteManifest(manifestUri, remoteFetchOptions);
      List<ManifestThemePackage> entries = parseManifestThemePackages(manifest);
      if (entries.isEmpty()) {
        throw new ServiceException("bad_request", "Manifest contains no theme package entries");
      }

      Set<String> requestedThemes = new java.util.LinkedHashSet<>();
      for (String rawTheme : getStringArray(payload, "themes")) {
        String themeId = normalizeThemeId(rawTheme);
        if (!themeId.isBlank()) {
          requestedThemes.add(themeId);
        }
      }

      JsonArray results = new JsonArray();
      int succeeded = 0;
      int failed = 0;
      String manifestVersion = getOptionalString(manifest, "version").orElse("");

      for (ManifestThemePackage entry : entries) {
        if (!requestedThemes.isEmpty() && !requestedThemes.contains(entry.themeId())) {
          continue;
        }

        JsonObject row = new JsonObject();
        row.addProperty("themeId", entry.themeId());
        row.addProperty("version", entry.version());
        row.addProperty("packageUrl", entry.packageUrl());
        try {
          URI packageUri = parseManifestUri(entry.packageUrl());
          byte[] packageBytes = fetchRemoteResourceWithRetry(
              packageUri,
              "theme package",
              "*/*",
              "WebShopX-Theme-Sync",
              Duration.ofSeconds(30),
              REMOTE_THEME_PACKAGE_MAX_BYTES,
              remoteFetchOptions);
          ThemeCenterService.InstallOutcome outcome = themeCenterService.installZipPackage(
              packageBytes,
              new ThemeCenterService.InstallOptions("github", entry.version(), entry.name(), entry.themeId()));
          JsonArray changed = new JsonArray();
          for (JsonObject changedRow : outcome.changed()) {
            String changedTheme = getOptionalString(changedRow, "themeId").orElse("");
            if (!changedTheme.isBlank()) {
              changed.add(changedTheme);
            }
          }
          row.addProperty("result", "ok");
          row.add("changedThemes", changed);
          row.addProperty("fileCount", outcome.fileCount());
          succeeded += 1;
        } catch (Exception exception) {
          row.addProperty("result", "failed");
          row.addProperty("message", exception.getMessage() == null ? "unknown error" : exception.getMessage());
          failed += 1;
        }
        results.add(row);
      }

      if (results.size() <= 0) {
        throw new ServiceException("bad_request", "No theme matched the requested filter");
      }

      JsonObject response = new JsonObject();
      response.addProperty("manifestVersion", manifestVersion);
      response.addProperty("total", results.size());
      response.addProperty("succeeded", succeeded);
      response.addProperty("failed", failed);
      response.add("results", results);
      response.add("state", themeCenterService.listState());
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminRedeemCreate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.REDEEM_MANAGE);
      long shopCoin = getLong(payload, "shopCoin", 0L);
      long gameCoin = getLong(payload, "gameCoin", 0L);
      int maxUses = (int) getLong(payload, "maxUses", 1L);
      int perUserMaxUses = (int) getLong(payload, "perUserMaxUses", 1L);
      Integer expiresInMinutes = payload.has("expiresInMinutes") ? (int) getLong(payload, "expiresInMinutes", 0L) : null;
      String customCode = getOptionalString(payload, "customCode").orElse(null);
      String code = redeemCodeService.createCode(
          shopCoin,
          gameCoin,
          maxUses,
          perUserMaxUses,
          expiresInMinutes,
          customCode);
      JsonObject response = new JsonObject();
      response.addProperty("code", code);
      response.addProperty("shopCoin", shopCoin);
      response.addProperty("gameCoin", gameCoin);
      response.addProperty("maxUses", maxUses);
      response.addProperty("perUserMaxUses", perUserMaxUses);
      response.addProperty("expiresInMinutes", expiresInMinutes);
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("code", code);
      detail.addProperty("shopCoin", shopCoin);
      detail.addProperty("gameCoin", gameCoin);
      detail.addProperty("maxUses", maxUses);
      detail.addProperty("perUserMaxUses", perUserMaxUses);
      if (expiresInMinutes != null) {
        detail.addProperty("expiresInMinutes", expiresInMinutes);
      }
      adminAuditService.log(admin, "REDEEM_CREATE", "redeem_code", code, detail, clientIp(exchange));
    });
  }

  private void handleAdminRedeemList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.REDEEM_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 200);
      List<RedeemCodeService.RedeemCodeView> codes = redeemCodeService.listCodes(limit);
      JsonArray array = new JsonArray();
      for (RedeemCodeService.RedeemCodeView code : codes) {
        JsonObject row = new JsonObject();
        row.addProperty("code", code.code());
        row.addProperty("shopCoin", code.shopCoin());
        row.addProperty("gameCoin", code.gameCoin());
        row.addProperty("maxUses", code.maxUses());
        row.addProperty("perUserMaxUses", code.perUserMaxUses());
        row.addProperty("usedCount", code.usedCount());
        row.addProperty("active", code.active());
        if (code.expiresAt() == null) {
          row.add("expiresAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("expiresAt", code.expiresAt().toString());
        }
        if (code.createdAt() == null) {
          row.add("createdAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("createdAt", code.createdAt().toString());
        }
        array.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("codes", array);
      sendJson(exchange, 200, response);
      adminAuditService.log(admin, "REDEEM_LIST", "redeem_code", null, null, clientIp(exchange));
    });
  }

  private void handleAdminProductsList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.PRODUCT_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      boolean includeInactive = parseBoolean(query.get("includeInactive"));
      int limit = parseInt(query.get("limit"), 200);
      List<ProductService.ProductView> products = productService.listProducts(includeInactive, limit);
      JsonArray array = new JsonArray();
      for (ProductService.ProductView product : products) {
        JsonObject row = new JsonObject();
        addProductJson(row, product, false);
        row.addProperty("commandTemplate", product.commandTemplate());
        row.addProperty("active", product.active());
        array.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("products", array);
      sendJson(exchange, 200, response);
      adminAuditService.log(admin, "PRODUCT_LIST", "product", null, null, clientIp(exchange));
    });
  }

  private void handleAdminProductsUpsert(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.PRODUCT_MANAGE);
      boolean allowZeroPrice = admin.allows(AdminPermission.PRODUCT_ZERO_PRICE);
      Boolean dynamicPricingEnabled = payload.has("dynamicPricingEnabled")
        && !payload.get("dynamicPricingEnabled").isJsonNull()
        ? payload.get("dynamicPricingEnabled").getAsBoolean()
        : null;
      String dynamicAlgorithm = getOptionalString(payload, "dynamicAlgorithm").orElse(null);
      String dynamicParamsJson = null;
      if (payload.has("dynamicParamsJson") && !payload.get("dynamicParamsJson").isJsonNull()) {
      JsonElement dynamicParamsElement = payload.get("dynamicParamsJson");
      dynamicParamsJson = dynamicParamsElement.isJsonPrimitive()
        && dynamicParamsElement.getAsJsonPrimitive().isString()
        ? dynamicParamsElement.getAsString()
        : dynamicParamsElement.toString();
      }
      Long dynamicBasePrice = payload.has("dynamicBasePrice") && !payload.get("dynamicBasePrice").isJsonNull()
        ? getLong(payload, "dynamicBasePrice", 0L)
        : null;
      Long dynamicFloorPrice = payload.has("dynamicFloorPrice") && !payload.get("dynamicFloorPrice").isJsonNull()
        ? getLong(payload, "dynamicFloorPrice", 0L)
        : null;
      Long dynamicCapPrice = payload.has("dynamicCapPrice") && !payload.get("dynamicCapPrice").isJsonNull()
        ? getLong(payload, "dynamicCapPrice", 0L)
        : null;
      Long dynamicPriceStep = payload.has("dynamicPriceStep") && !payload.get("dynamicPriceStep").isJsonNull()
        ? getLong(payload, "dynamicPriceStep", 0L)
        : null;
      ProductService.AdminProductInput input = new ProductService.AdminProductInput(
          getString(payload, "sku"),
          getString(payload, "title"),
          getOptionalString(payload, "remark").orElse(null),
          CurrencyType.fromConfig(getString(payload, "currency")),
          getLong(payload, "price", 0L),
          getOptionalString(payload, "productType").orElse("COMMAND"),
          getOptionalString(payload, "commandTemplate").orElse(""),
          getOptionalString(payload, "itemMaterial").orElse(null),
          getOptionalString(payload, "displayNameOverride").orElse(null),
          getOptionalString(payload, "displayMaterial").orElse(null),
          getOptionalString(payload, "displayIconPath").orElse(null),
          payload.has("itemAmount") && !payload.get("itemAmount").isJsonNull()
              ? (int) getLong(payload, "itemAmount", 0L)
              : null,
          payload.has("perUserLimit") && !payload.get("perUserLimit").isJsonNull()
              ? (int) getLong(payload, "perUserLimit", 0L)
              : null,
          getOptionalString(payload, "effectType").orElse(null),
          payload.has("effectSeconds") && !payload.get("effectSeconds").isJsonNull()
              ? (int) getLong(payload, "effectSeconds", 0L)
              : null,
          payload.has("effectAmplifier") && !payload.get("effectAmplifier").isJsonNull()
              ? (int) getLong(payload, "effectAmplifier", 0L)
              : null,
            dynamicPricingEnabled,
            dynamicAlgorithm,
            dynamicParamsJson,
            dynamicBasePrice,
            dynamicFloorPrice,
            dynamicCapPrice,
            dynamicPriceStep,
          getOptionalDateTime(payload, "publishAt"),
          getOptionalDateTime(payload, "unpublishAt"),
          payload.has("active") ? payload.get("active").getAsBoolean() : true);
      ProductService.ProductView product = productService.upsertProduct(input, allowZeroPrice);
      JsonObject response = new JsonObject();
      addProductJson(response, product, false);
      response.addProperty("active", product.active());
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("sku", product.sku());
      detail.addProperty("productType", product.productType().name());
      detail.addProperty("active", product.active());
      adminAuditService.log(admin, "PRODUCT_UPSERT", "product", product.sku(), detail, clientIp(exchange));
    });
  }

  private void handleAdminProductIconUpload(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.PRODUCT_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      Long productIdRaw = parseLong(query.get("productId"));
      long productId = productIdRaw == null ? -1L : productIdRaw;
      if (productId <= 0L) {
        throw new ServiceException("bad_request", "Missing or invalid productId");
      }

      String ext = resolveIconUploadExtension(
          query.get("filename"),
          exchange.getRequestHeaders().getFirst("X-File-Name"),
          exchange.getRequestHeaders().getFirst("Content-Type"));
      byte[] content = readRequestBodyWithLimit(exchange, MATERIAL_ICON_MAX_UPLOAD_BYTES);
      if (content.length == 0) {
        throw new ServiceException("bad_request", "Empty file content");
      }

      ProductService.ProductView existing = productService.readProductView(productId);
      Path iconRoot = resolveProductIconRoot();
      String fileName = "product-"
          + productId
          + "-"
          + System.currentTimeMillis()
          + "-"
          + UUID.randomUUID().toString().substring(0, 8)
          + "."
          + ext;
      Path output = iconRoot.resolve(fileName).normalize();
      if (!output.startsWith(iconRoot)) {
        throw new ServiceException("bad_request", "Invalid upload target");
      }
      Files.write(
          output,
          content,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);

      String iconPath = "/uploads/product-icons/" + fileName;
      ProductService.ProductView saved = productService.updateDisplayIconPath(productId, iconPath);
      if (existing.displayIconPath() != null
          && !existing.displayIconPath().isBlank()
          && !existing.displayIconPath().equals(saved.displayIconPath())) {
        deleteManagedProductIcon(existing.displayIconPath());
      }

      JsonObject response = new JsonObject();
      addProductJson(response, saved, false);
      response.addProperty("active", saved.active());
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminProductsActive(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.PRODUCT_MANAGE);
      long productId = getLong(payload, "productId", -1L);
      boolean active = payload.has("active") && payload.get("active").getAsBoolean();
      ProductService.ProductView product = productService.setProductActive(productId, active);
      JsonObject response = new JsonObject();
      response.addProperty("id", product.id());
      response.addProperty("active", product.active());
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("active", active);
      adminAuditService.log(admin, "PRODUCT_ACTIVE", "product", String.valueOf(product.id()), detail, clientIp(exchange));
    });
  }

  private void handleAdminProductsResetLimit(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.PRODUCT_MANAGE);
      long productId = getLong(payload, "productId", -1L);
      ProductService.ProductView product = productService.readProductView(productId);
      int resetCount = orderService.resetProductUserLimitUsage(productId);

      JsonObject response = new JsonObject();
      response.addProperty("productId", product.id());
      response.addProperty("sku", product.sku());
      response.addProperty("resetCount", resetCount);
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("sku", product.sku());
      detail.addProperty("resetCount", resetCount);
      adminAuditService.log(admin, "PRODUCT_LIMIT_RESET", "product", product.sku(), detail, clientIp(exchange));
    });
  }

  private void handleAdminGroupBuyConsume(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.PRODUCT_MANAGE);
      String code = getString(payload, "code");
      OrderService.GroupBuyVoucherConsumeResult result =
          orderService.consumeGroupBuyVoucher(admin.userId(), code);

      JsonObject response = new JsonObject();
      response.addProperty("code", result.code());
      response.addProperty("status", result.status());
      response.addProperty("orderNo", result.orderNo());
      response.addProperty("userId", result.userId());
      response.addProperty("username", result.username());
      response.addProperty("productSku", result.productSku());
      response.addProperty("productTitle", result.productTitle());
      if (result.consumedAt() == null) {
        response.add("consumedAt", JsonNull.INSTANCE);
      } else {
        response.addProperty("consumedAt", result.consumedAt().toString());
      }
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("code", result.code());
      detail.addProperty("orderNo", result.orderNo());
      detail.addProperty("userId", result.userId());
      adminAuditService.log(admin, "GROUP_BUY_CONSUME", "group_buy_voucher", result.code(), detail, clientIp(exchange));
    });
  }

  private void handleAdminEconomySettings(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      PluginSettings settings = settingsSupplier.get();

      JsonObject shopToGame = new JsonObject();
      shopToGame.addProperty("enabled", settings.exchangeSettings().shopToGame().enabled());
      shopToGame.addProperty("ratio", settings.exchangeSettings().shopToGame().ratio());
      JsonObject gameToShop = new JsonObject();
      gameToShop.addProperty("enabled", settings.exchangeSettings().gameToShop().enabled());
      gameToShop.addProperty("ratio", settings.exchangeSettings().gameToShop().ratio());
      JsonObject exchangeJson = new JsonObject();
      exchangeJson.add("shopToGame", shopToGame);
      exchangeJson.add("gameToShop", gameToShop);

      PluginSettings.MarketEconomySettings market = settings.economySettings().marketSettings();
      JsonObject marketJson = new JsonObject();
      marketJson.addProperty("tradeFeePercent", market.tradeFeePercent());
      marketJson.addProperty("tradeTaxPercent", market.tradeTaxPercent());
      PluginSettings.InflationSettings inflation = settings.economySettings().inflationSettings();
      JsonObject inflationJson = new JsonObject();
      inflationJson.addProperty("mode", inflation.mode().name());
      inflationJson.addProperty("treasuryUserId", inflation.treasuryUserId());

      PluginSettings.CurrencyDisplaySettings currency = settings.currencyDisplaySettings();
      JsonObject currencyJson = new JsonObject();
      currencyJson.addProperty("shopCoinName", currency.shopCoinName());
      currencyJson.addProperty("shopCoinShort", currency.shopCoinShort());
      currencyJson.addProperty("gameCoinName", currency.gameCoinName());
      currencyJson.addProperty("gameCoinShort", currency.gameCoinShort());

      WalletService.GameCoinIntegrationStatus integration = walletService.getGameCoinIntegrationStatus();
      JsonObject vaultJson = new JsonObject();
      vaultJson.addProperty("vaultPluginPresent", integration.vaultPluginPresent());
      vaultJson.addProperty("hooked", integration.hooked());
      if (integration.provider() == null || integration.provider().isBlank()) {
        vaultJson.add("provider", JsonNull.INSTANCE);
      } else {
        vaultJson.addProperty("provider", integration.provider());
      }
      vaultJson.addProperty("gameCoinBackedByVault", integration.gameCoinBackedByVault());

      JsonObject response = new JsonObject();
      response.add("exchange", exchangeJson);
      response.add("market", marketJson);
      response.add("inflation", inflationJson);
      response.add("currency", currencyJson);
      response.add("vault", vaultJson);
      response.add("deployment", deploymentModeJson(settings));
      response.add("marketTagsConfig", runtimeConfigService.readMarketTagsConfig().config());
      response.add("marketLimitationConfig", runtimeConfigService.readMarketLimitationConfig().config());
      response.add("leaderboard", leaderboardSettingsJson(settings));
      response.add("webshopRuntime", webshopRuntimeJson(settings));
      response.add("rechargePayment", rechargePaymentSettingsJson(settings.paymentSettings()));
      response.add("paymentProvider", paymentProviderInfoJson(rechargeService.paymentProviderInfo()));
      response.add("marketRuntime", marketRuntimeJson(settings));
      response.add("maintenance", maintenanceSettingsJson(settings.maintenanceSettings()));
      response.add("logging", loggingSettingsJson(settings.loggingSettings()));
      response.add("broadcast", broadcastSettingsJson(settings.broadcastSettings()));
      response.add("notification", notificationSettingsJson(runtimeConfigService.readNotificationSettings()));
      response.add("visual", visualSettingsJson(visualCustomizationService.readSettings()));
      sendJson(exchange, 200, response);

      adminAuditService.log(admin, "ECONOMY_READ", "economy", null, null, clientIp(exchange));
    });
  }

  private void handleAdminExchangeUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);

      boolean shopEnabled = getBoolean(payload, "shopToGameEnabled");
      double shopRatio = Math.max(0.0, getDouble(payload, "shopToGameRatio"));
      boolean gameEnabled = getBoolean(payload, "gameToShopEnabled");
      double gameRatio = Math.max(0.0, getDouble(payload, "gameToShopRatio"));
      PluginSettings.ExchangeSettings exchangeSettings = new PluginSettings.ExchangeSettings(
          new PluginSettings.ExchangeDirection(shopEnabled, shopRatio),
          new PluginSettings.ExchangeDirection(gameEnabled, gameRatio));
      long version = runtimeConfigService.updateExchange(exchangeSettings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("shopToGameEnabled", shopEnabled);
      detail.addProperty("shopToGameRatio", shopRatio);
      detail.addProperty("gameToShopEnabled", gameEnabled);
      detail.addProperty("gameToShopRatio", gameRatio);
      adminAuditService.log(admin, "EXCHANGE_UPDATE", "exchange", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminMarketEconomyUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      double fee = clampPercent(getDouble(payload, "tradeFeePercent"));
      double tax = clampPercent(getDouble(payload, "tradeTaxPercent"));
      PluginSettings.InflationMode inflationMode = PluginSettings.InflationMode.fromRaw(getString(payload, "inflationMode"));
      long treasuryUserId = Math.max(0L, getLong(payload, "inflationTreasuryUserId", 0L));
      if (inflationMode == PluginSettings.InflationMode.TREASURY && treasuryUserId <= 0L) {
        throw new ServiceException("bad_request", "inflationTreasuryUserId is required when inflationMode=TREASURY");
      }
      PluginSettings.MarketEconomySettings marketEconomySettings =
          new PluginSettings.MarketEconomySettings(fee, tax);
      PluginSettings.InflationSettings inflationSettings =
          new PluginSettings.InflationSettings(inflationMode, treasuryUserId);
      PluginSettings.EconomySettings economySettings =
          new PluginSettings.EconomySettings(marketEconomySettings, inflationSettings);
      long version = runtimeConfigService.updateMarketEconomy(economySettings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("tradeFeePercent", fee);
      detail.addProperty("tradeTaxPercent", tax);
      detail.addProperty("inflationMode", inflationMode.name());
      detail.addProperty("inflationTreasuryUserId", treasuryUserId);
      adminAuditService.log(admin, "MARKET_ECONOMY_UPDATE", "market", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminLeaderboardSettingsUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);

      boolean enabled = getBoolean(payload, "enabled");
      boolean showOnlineStatus = getBoolean(payload, "showOnlineStatus");
      String defaultMetric = getString(payload, "defaultMetric");
      String defaultOrder = getString(payload, "defaultOrder");

      PluginSettings.LeaderboardMetric metric = PluginSettings.LeaderboardMetric.fromRaw(defaultMetric);
      PluginSettings.SortDirection order = PluginSettings.SortDirection.fromRaw(defaultOrder);
      PluginSettings.LeaderboardSettings leaderboardSettings = new PluginSettings.LeaderboardSettings(
          enabled,
          showOnlineStatus,
          metric,
          order);
      long version = runtimeConfigService.updateLeaderboard(leaderboardSettings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("enabled", enabled);
      detail.addProperty("showOnlineStatus", showOnlineStatus);
      detail.addProperty("defaultMetric", defaultMetric);
      detail.addProperty("defaultOrder", defaultOrder);
      adminAuditService.log(admin, "LEADERBOARD_UPDATE", "leaderboard", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminCurrencyDisplayUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);

      String shopCoinName = getString(payload, "shopCoinName");
      String shopCoinShort = getString(payload, "shopCoinShort");
      String gameCoinName = getString(payload, "gameCoinName");
      String gameCoinShort = getString(payload, "gameCoinShort");

      String normalizedShopCoinName = normalizeCurrencyField(shopCoinName, "shopCoinName", 24);
      String normalizedShopCoinShort = normalizeCurrencyField(shopCoinShort, "shopCoinShort", 12);
      String normalizedGameCoinName = normalizeCurrencyField(gameCoinName, "gameCoinName", 24);
      String normalizedGameCoinShort = normalizeCurrencyField(gameCoinShort, "gameCoinShort", 12);

      PluginSettings.CurrencyDisplaySettings currencyDisplaySettings = new PluginSettings.CurrencyDisplaySettings(
          normalizedShopCoinName,
          normalizedShopCoinShort,
          normalizedGameCoinName,
          normalizedGameCoinShort);
      long version = runtimeConfigService.updateCurrencyDisplay(currencyDisplaySettings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("shopCoinName", normalizedShopCoinName);
      detail.addProperty("shopCoinShort", normalizedShopCoinShort);
      detail.addProperty("gameCoinName", normalizedGameCoinName);
      detail.addProperty("gameCoinShort", normalizedGameCoinShort);
      adminAuditService.log(admin, "CURRENCY_DISPLAY_UPDATE", "currency", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminRechargePaymentUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      PluginSettings.PaymentSettings current = settingsSupplier.get().paymentSettings();
      List<String> currencies = getStringArray(payload, "currencies");
      if (!currencies.isEmpty()) {
        currencies = List.of(currencies.get(0));
      }
      PluginSettings.PaymentSettings paymentSettings = new PluginSettings.PaymentSettings(
          current.provider(),
          currencies,
          PluginSettings.normalizePaymentMethods(getStringArray(payload, "methods")));
      long version = runtimeConfigService.updatePaymentRecharge(paymentSettings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.add("currencies", stringArrayJson(paymentSettings.rechargeCurrencies()));
      detail.add("methods", paymentMethodArrayJson(paymentSettings.rechargeMethods()));
      adminAuditService.log(admin, "RECHARGE_PAYMENT_UPDATE", "payment", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("rechargePayment", rechargePaymentSettingsJson(paymentSettings));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminMarketTagsConfig(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    String method = exchange.getRequestMethod();
    if (method.equalsIgnoreCase("GET")) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
        RuntimeConfigService.ConfigDocument config = runtimeConfigService.readMarketTagsConfig();
        JsonObject response = new JsonObject();
        response.add("config", config.config());
        response.addProperty("version", config.version());
        sendJson(exchange, 200, response);
      });
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      JsonObject config = payload.has("config") && payload.get("config").isJsonObject()
          ? payload.getAsJsonObject("config")
          : null;
      if (config == null) {
        throw new ServiceException("bad_request", "Missing field: config");
      }
      long version = runtimeConfigService.updateMarketTagsConfig(config);
      marketService.refreshRuntimePolicies();
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("version", version);
      detail.addProperty("tagCount", countJsonArray(config, "tags"));
      adminAuditService.log(admin, "MARKET_TAGS_CONFIG_UPDATE", "market_tags", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("version", version);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminMarketLimitationConfig(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    String method = exchange.getRequestMethod();
    if (method.equalsIgnoreCase("GET")) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
        RuntimeConfigService.ConfigDocument config = runtimeConfigService.readMarketLimitationConfig();
        JsonObject response = new JsonObject();
        response.add("config", config.config());
        response.addProperty("version", config.version());
        sendJson(exchange, 200, response);
      });
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      JsonObject config = payload.has("config") && payload.get("config").isJsonObject()
          ? payload.getAsJsonObject("config")
          : null;
      if (config == null) {
        throw new ServiceException("bad_request", "Missing field: config");
      }
      long version = runtimeConfigService.updateMarketLimitationConfig(config);
      marketService.refreshRuntimePolicies();
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("version", version);
      detail.addProperty("ruleCount", countJsonArray(config, "rules"));
      adminAuditService.log(admin, "MARKET_LIMITATION_CONFIG_UPDATE", "market_limitation", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("version", version);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminWebshopRuntimeUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);

      String defaultLocale = readLocaleField(getString(payload, "defaultLocale"), "defaultLocale");
      int sessionExpireHours = clampInt(getLong(payload, "sessionExpireHours", 72L), 1, 24 * 365, "sessionExpireHours");
      int bindRequestExpireMinutes = clampInt(
          getLong(payload, "bindRequestExpireMinutes", 15L), 1, 24 * 60 * 30, "bindRequestExpireMinutes");
      int accessTokenLength = clampInt(getLong(payload, "accessTokenLength", 48L), 16, 256, "accessTokenLength");
      int deliveryBatchSize = clampInt(getLong(payload, "deliveryBatchSize", 20L), 1, 1000, "deliveryBatchSize");
      int deliveryRetrySeconds = clampInt(getLong(payload, "deliveryRetrySeconds", 30L), 5, 86400, "deliveryRetrySeconds");
      int orderCooldownSeconds = clampInt(getLong(payload, "orderCooldownSeconds", 15L), 0, 86400, "orderCooldownSeconds");
      boolean allowSharedClaimCommand = getBoolean(payload, "allowSharedClaimCommand");
      boolean refundUndeliveredEnabled = getBoolean(payload, "refundUndeliveredEnabled");
      ZoneId timeZone = readTimeZoneField(getString(payload, "timeZone"), "timeZone");

      RuntimeConfigService.RuntimeSettingsUpdate update = new RuntimeConfigService.RuntimeSettingsUpdate(
          defaultLocale,
          sessionExpireHours,
          bindRequestExpireMinutes,
          accessTokenLength,
          deliveryBatchSize,
          deliveryRetrySeconds,
          orderCooldownSeconds,
          allowSharedClaimCommand,
          refundUndeliveredEnabled,
          timeZone);
      long version = runtimeConfigService.updateWebshopRuntime(update);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("defaultLocale", defaultLocale);
      detail.addProperty("timeZone", timeZone.getId());
      detail.addProperty("deliveryBatchSize", deliveryBatchSize);
      detail.addProperty("deliveryRetrySeconds", deliveryRetrySeconds);
      detail.addProperty("orderCooldownSeconds", orderCooldownSeconds);
      adminAuditService.log(admin, "WEBSHOP_RUNTIME_UPDATE", "webshop_runtime", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminMarketRuntimeUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      int marketMaxActiveListings = clampInt(
          getLong(payload, "marketMaxActiveListings", 10L), 1, 1000, "marketMaxActiveListings");
      PluginSettings.MarketSupplySettings supplySettings = new PluginSettings.MarketSupplySettings(
          clampInt(getLong(payload, "autoRefreshThreshold", 8L), 0, 4096, "autoRefreshThreshold"),
          clampInt(getLong(payload, "defaultTransferBatchSize", 64L), 1, 4096, "defaultTransferBatchSize"),
          clampInt(getLong(payload, "maxTransferBatchSize", 256L), 1, 4096, "maxTransferBatchSize"),
          clampInt(getLong(payload, "defaultTransitStock", 256L), 1, 65535, "defaultTransitStock"),
          clampInt(getLong(payload, "maxTransitStock", 1024L), 1, 65535, "maxTransitStock"));
      long version = runtimeConfigService.updateMarketRuntime(marketMaxActiveListings, supplySettings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("marketMaxActiveListings", marketMaxActiveListings);
      adminAuditService.log(admin, "MARKET_RUNTIME_UPDATE", "market_runtime", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminMaintenanceSettingsUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      PluginSettings.MaintenanceSettings maintenanceSettings = new PluginSettings.MaintenanceSettings(
          clampInt(getLong(payload, "cleanupIntervalMinutes", 30L), 0, 10080, "cleanupIntervalMinutes"),
          clampInt(getLong(payload, "pendingBindRetentionHours", 6L), 1, 24 * 365, "pendingBindRetentionHours"),
          clampInt(getLong(payload, "pendingPasswordRetentionHours", 6L), 1, 24 * 365, "pendingPasswordRetentionHours"),
          clampInt(getLong(payload, "bindRequestRetentionHours", 24L), 1, 24 * 365, "bindRequestRetentionHours"),
          clampInt(getLong(payload, "redeemCodeRetentionDays", 7L), 1, 3650, "redeemCodeRetentionDays"));
      long version = runtimeConfigService.updateMaintenance(maintenanceSettings);
      publishRuntimeConfigRefresh(version);

      adminAuditService.log(admin, "MAINTENANCE_UPDATE", "maintenance", null, maintenanceSettingsJson(maintenanceSettings), clientIp(exchange));
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminLoggingSettingsUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      PluginSettings.LoggingSettings loggingSettings = new PluginSettings.LoggingSettings(
          getBoolean(payload, "enabled"),
          PluginSettings.LogLevel.fromRaw(getString(payload, "level")),
          normalizeLogDirectory(getString(payload, "directory")),
          clampInt(getLong(payload, "maxFileSizeMb", 8L), 1, 1024, "maxFileSizeMb"),
          clampInt(getLong(payload, "maxFiles", 8L), 1, 128, "maxFiles"),
          clampInt(getLong(payload, "retentionDays", 14L), 0, 3650, "retentionDays"));
      long version = runtimeConfigService.updateLogging(loggingSettings);
      publishRuntimeConfigRefresh(version);

      adminAuditService.log(admin, "LOGGING_UPDATE", "logging", null, loggingSettingsJson(loggingSettings), clientIp(exchange));
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminBroadcastSettingsUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      Map<String, String> templates = new LinkedHashMap<>();
      templates.put("listing-created", getString(payload, "listingCreatedTemplate"));
      templates.put("trade-success", getString(payload, "tradeSuccessTemplate"));
      templates.put("auction-bid", getString(payload, "auctionBidTemplate"));
      templates.put("auction-sealed-bid", getString(payload, "auctionSealedBidTemplate"));
      PluginSettings.BroadcastSettings broadcastSettings =
          new PluginSettings.BroadcastSettings(getBoolean(payload, "enabled"), templates);
      long version = runtimeConfigService.updateBroadcast(broadcastSettings);
      publishRuntimeConfigRefresh(version);

      adminAuditService.log(admin, "BROADCAST_UPDATE", "broadcast", null, broadcastSettingsJson(broadcastSettings), clientIp(exchange));
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminNotificationSettingsUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      RuntimeConfigService.NotificationSettings defaults = RuntimeConfigService.NotificationSettings.defaults();
      JsonObject rawTemplates = payload.has("templates") && payload.get("templates").isJsonObject()
          ? payload.getAsJsonObject("templates")
          : new JsonObject();
      Map<String, String> templates = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : defaults.templates().entrySet()) {
        String key = entry.getKey();
        String fallback = entry.getValue();
        String value = rawTemplates.has(key) && !rawTemplates.get(key).isJsonNull()
            ? rawTemplates.get(key).getAsString()
            : fallback;
        templates.put(key, value == null || value.isBlank() ? fallback : value.trim());
      }
      RuntimeConfigService.NotificationSettings notificationSettings = new RuntimeConfigService.NotificationSettings(
          getBoolean(payload, "marketEventsEnabled"),
          getBoolean(payload, "deliveryMailboxEventsEnabled"),
          templates).normalized();
      long version = runtimeConfigService.updateNotificationSettings(notificationSettings);
      publishRuntimeConfigRefresh(version);

      adminAuditService.log(
          admin,
          "NOTIFICATION_SETTINGS_UPDATE",
          "notification",
          null,
          notificationSettingsJson(notificationSettings),
          clientIp(exchange));
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("notification", notificationSettingsJson(notificationSettings));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminVisualSettingsUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    String method = exchange.getRequestMethod();
    if (method.equalsIgnoreCase("GET")) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
        JsonObject response = new JsonObject();
        response.add("visual", visualSettingsJson(visualCustomizationService.readSettings()));
        sendJson(exchange, 200, response);
      });
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      VisualCustomizationService.VisualSettings settings = new VisualCustomizationService.VisualSettings(
          getBoolean(payload, "globalCustomIconEnabled"),
          getBoolean(payload, "globalCustomNameEnabled"),
          payload.has("officialProductCustomIconEnabled")
              ? getBoolean(payload, "officialProductCustomIconEnabled")
              : true,
          payload.has("officialProductCustomNameEnabled")
              ? getBoolean(payload, "officialProductCustomNameEnabled")
              : true,
          payload.has("officialProductUploadImageEnabled")
              ? getBoolean(payload, "officialProductUploadImageEnabled")
              : true,
          payload.has("marketListingCustomIconEnabled")
              ? getBoolean(payload, "marketListingCustomIconEnabled")
              : true,
          payload.has("marketListingCustomNameEnabled")
              ? getBoolean(payload, "marketListingCustomNameEnabled")
              : true,
          payload.has("marketListingUploadImageEnabled")
              ? getBoolean(payload, "marketListingUploadImageEnabled")
              : true,
          VisualCustomizationService.VisualPolicyMode.fromRaw(
              getOptionalString(payload, "iconPolicyMode").orElse("SOFT")),
          VisualCustomizationService.VisualPolicyMode.fromRaw(
              getOptionalString(payload, "namePolicyMode").orElse("SOFT")));
      long version = visualCustomizationService.updateSettings(settings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = visualSettingsJson(settings);
      adminAuditService.log(admin, "VISUAL_SETTINGS_UPDATE", "visual_settings", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("visual", visualSettingsJson(settings));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminUserVisualPermission(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    String method = exchange.getRequestMethod();
    if (method.equalsIgnoreCase("GET")) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.USER_SUPPORT);
        Map<String, String> query = parseQuery(exchange);
        long userId = parseLong(query.get("userId")) == null ? -1L : parseLong(query.get("userId"));
        if (userId <= 0L) {
          String identifier = query.get("identifier");
          if (identifier == null || identifier.isBlank()) {
            throw new ServiceException("bad_request", "Missing userId or identifier");
          }
          userId = adminService.lookupUser(identifier.trim())
              .map(AdminService.UserSupportView::userId)
              .orElseThrow(() -> new ServiceException("not_found", "User not found"));
        }
        VisualCustomizationService.ResolvedPermission resolved =
            visualCustomizationService.resolvePermission(userId);
        JsonObject response = userVisualPermissionJson(resolved);
        AdminService.UserSupportView userView = adminService.lookupUser(String.valueOf(userId))
            .orElseThrow(() -> new ServiceException("not_found", "User not found"));
        addListingLimitJson(
            response,
            userMarketSettingsService.resolveListingLimit(
                userId,
                userView.boundUuid(),
                settingsSupplier.get().marketMaxActiveListings()));
        sendJson(exchange, 200, response);
      });
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.USER_SUPPORT);
      long userId = resolveUserId(payload);
      VisualCustomizationService.VisualPermission iconPermission =
          VisualCustomizationService.VisualPermission.fromRaw(
              getOptionalString(payload, "iconPermission").orElse("INHERIT"));
      VisualCustomizationService.VisualPermission namePermission =
          VisualCustomizationService.VisualPermission.fromRaw(
              getOptionalString(payload, "namePermission").orElse("INHERIT"));
      VisualCustomizationService.VisualPermission uploadPermission =
          VisualCustomizationService.VisualPermission.fromRaw(
              getOptionalString(payload, "uploadPermission").orElse("INHERIT"));
      Integer listingLimitOverride = payload.has("listingLimitOverride") && !payload.get("listingLimitOverride").isJsonNull()
          ? (int) getLong(payload, "listingLimitOverride", 0L)
          : null;
      visualCustomizationService.upsertUserPermission(
          userId,
          iconPermission,
          namePermission,
          uploadPermission);
      userMarketSettingsService.upsertUserSettings(userId, listingLimitOverride);
      VisualCustomizationService.ResolvedPermission resolved =
          visualCustomizationService.resolvePermission(userId);
      JsonObject response = userVisualPermissionJson(resolved);
      AdminService.UserSupportView userView = adminService.lookupUser(String.valueOf(userId))
          .orElseThrow(() -> new ServiceException("not_found", "User not found"));
      addListingLimitJson(
          response,
          userMarketSettingsService.resolveListingLimit(
              userId,
              userView.boundUuid(),
              settingsSupplier.get().marketMaxActiveListings()));
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("userId", userId);
      detail.addProperty("iconPermission", iconPermission.name());
      detail.addProperty("namePermission", namePermission.name());
      detail.addProperty("uploadPermission", uploadPermission.name());
      if (listingLimitOverride == null) {
        detail.add("listingLimitOverride", JsonNull.INSTANCE);
      } else {
        detail.addProperty("listingLimitOverride", listingLimitOverride);
      }
      adminAuditService.log(
          admin,
          "USER_VISUAL_PERMISSION_UPDATE",
          "user_visual_permission",
          String.valueOf(userId),
          detail,
          clientIp(exchange));
    });
  }

  private void handleAdminMaterialOverridesList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      String keyword = query.get("keyword");
      int limit = parseInt(query.get("limit"), 200);
      List<MaterialVisualService.MaterialVisualEntry> entries = materialVisualService.list(keyword, limit);
      JsonObject response = new JsonObject();
      response.add("overrides", materialOverrideListJson(entries));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminMaterialOverridesUpsert(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String materialKey = normalizeMaterialField(getString(payload, "materialKey"), "materialKey");
      MaterialVisualService.MaterialVisualEntry existing =
          materialVisualService.findByMaterialKey(materialKey).orElse(null);

      boolean hasDisplayField = payload.has("displayNameOverride");
      String displayNameOverride = hasDisplayField
          ? normalizeMaterialDisplayName(payload.get("displayNameOverride"))
          : (existing == null ? null : existing.displayNameOverride());
      boolean hasIconField = payload.has("iconPath");
      String iconPath = hasIconField
          ? normalizeMaterialIconPath(payload.get("iconPath"))
          : (existing == null ? null : existing.iconPath());

      if ((displayNameOverride == null || displayNameOverride.isBlank())
          && (iconPath == null || iconPath.isBlank())) {
        throw new ServiceException("bad_request", "Display name and icon path cannot both be empty");
      }

      MaterialVisualService.MaterialVisualEntry saved =
          materialVisualService.upsert(materialKey, displayNameOverride, iconPath, admin.username());
      JsonObject response = materialOverrideJson(saved);
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("materialKey", saved.materialKey());
      if (saved.displayNameOverride() == null) {
        detail.add("displayNameOverride", JsonNull.INSTANCE);
      } else {
        detail.addProperty("displayNameOverride", saved.displayNameOverride());
      }
      if (saved.iconPath() == null) {
        detail.add("iconPath", JsonNull.INSTANCE);
      } else {
        detail.addProperty("iconPath", saved.iconPath());
      }
      adminAuditService.log(
          admin,
          "MATERIAL_OVERRIDE_UPSERT",
          "material_override",
          saved.materialKey(),
          detail,
          clientIp(exchange));
    });
  }

  private void handleAdminMaterialOverridesDelete(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String materialKey = normalizeMaterialField(getString(payload, "materialKey"), "materialKey");
      MaterialVisualService.MaterialVisualEntry existing =
          materialVisualService.findByMaterialKey(materialKey).orElse(null);
      boolean deleted = materialVisualService.delete(materialKey);
      if (deleted && existing != null && existing.iconPath() != null) {
        deleteManagedMaterialIcon(existing.iconPath());
      }
      JsonObject response = new JsonObject();
      response.addProperty("deleted", deleted);
      response.addProperty("materialKey", materialKey);
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("materialKey", materialKey);
      detail.addProperty("deleted", deleted);
      adminAuditService.log(
          admin,
          "MATERIAL_OVERRIDE_DELETE",
          "material_override",
          materialKey,
          detail,
          clientIp(exchange));
    });
  }

  private void handleAdminMaterialOverrideIconUpload(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      String materialKey = normalizeMaterialField(query.get("material"), "material");
      String ext = resolveIconUploadExtension(
          query.get("filename"),
          exchange.getRequestHeaders().getFirst("X-File-Name"),
          exchange.getRequestHeaders().getFirst("Content-Type"));
      byte[] content = readRequestBodyWithLimit(exchange, MATERIAL_ICON_MAX_UPLOAD_BYTES);
      if (content.length == 0) {
        throw new ServiceException("bad_request", "Empty file content");
      }

      Path iconRoot = resolveMaterialIconRoot();
      String fileName = materialKey.toLowerCase(Locale.ROOT)
          + "-"
          + System.currentTimeMillis()
          + "-"
          + UUID.randomUUID().toString().substring(0, 8)
          + "."
          + ext;
      Path output = iconRoot.resolve(fileName).normalize();
      if (!output.startsWith(iconRoot)) {
        throw new ServiceException("bad_request", "Invalid upload target");
      }
      Files.write(
          output,
          content,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);

      String iconPath = "/uploads/material-icons/" + fileName;
      MaterialVisualService.MaterialVisualEntry existing =
          materialVisualService.findByMaterialKey(materialKey).orElse(null);
      String displayNameOverride = existing == null ? null : existing.displayNameOverride();
      MaterialVisualService.MaterialVisualEntry saved =
          materialVisualService.upsert(materialKey, displayNameOverride, iconPath, admin.username());

      if (existing != null
          && existing.iconPath() != null
          && !existing.iconPath().isBlank()
          && !existing.iconPath().equals(saved.iconPath())) {
        deleteManagedMaterialIcon(existing.iconPath());
      }

      JsonObject response = materialOverrideJson(saved);
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("materialKey", saved.materialKey());
      detail.addProperty("iconPath", saved.iconPath());
      detail.addProperty("sizeBytes", content.length);
      adminAuditService.log(
          admin,
          "MATERIAL_OVERRIDE_ICON_UPLOAD",
          "material_override",
          saved.materialKey(),
          detail,
          clientIp(exchange));
    });
  }

  private void handleAdminOrdersList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.ORDER_VIEW);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 120);
      Long cursor = parseLong(query.get("cursor"));
      String status = query.get("status");
      Long userId = parseLong(query.get("userId"));
      String orderNo = query.get("orderNo");
      String username = query.get("username");
      String keyword = query.get("keyword");
      String currency = query.get("currency");
      String productType = query.get("productType");
      List<OrderService.AdminOrderView> orders = orderService.listOrdersForAdmin(
          limit,
          cursor,
          status,
          userId,
          orderNo,
          username,
          keyword,
          currency,
          productType);
      JsonArray array = new JsonArray();
      for (OrderService.AdminOrderView adminOrder : orders) {
        OrderService.OrderView order = adminOrder.order();
        JsonObject row = new JsonObject();
        row.addProperty("id", order.id());
        row.addProperty("orderNo", order.orderNo());
        row.addProperty("status", order.status());
        row.addProperty("currency", order.currency().name());
        row.addProperty("totalAmount", order.totalAmount());
        row.addProperty("createdAt", order.createdAt().toString());
        if (order.deliveredAt() == null) {
          row.add("deliveredAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("deliveredAt", order.deliveredAt().toString());
        }
        if (order.refundedAt() == null) {
          row.add("refundedAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("refundedAt", order.refundedAt().toString());
        }
        if (order.refundDeadline() == null) {
          row.add("refundDeadline", JsonNull.INSTANCE);
        } else {
          row.addProperty("refundDeadline", order.refundDeadline().toString());
        }
        row.addProperty("userId", order.userId());
        row.addProperty("username", adminOrder.username());
        if (adminOrder.boundUuid() == null) {
          row.add("boundUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("boundUuid", adminOrder.boundUuid().toString());
        }
        row.addProperty("mcUuid", order.mcUuid().toString());
        row.addProperty("sku", order.productSku());
        row.addProperty("productTitle", order.productTitle());
        if (order.productRemark() == null) {
          row.add("productRemark", JsonNull.INSTANCE);
        } else {
          row.addProperty("productRemark", order.productRemark());
        }
        row.addProperty("productType", order.productType());
        if (order.itemMaterial() == null) {
          row.add("itemMaterial", JsonNull.INSTANCE);
        } else {
          row.addProperty("itemMaterial", order.itemMaterial());
        }
        row.addProperty("quantity", order.quantity());
        row.addProperty("unitPrice", order.unitPrice());
        if (order.groupBuyVoucherCode() == null) {
          row.add("groupBuyVoucherCode", JsonNull.INSTANCE);
        } else {
          row.addProperty("groupBuyVoucherCode", order.groupBuyVoucherCode());
        }
        if (order.groupBuyVoucherStatus() == null) {
          row.add("groupBuyVoucherStatus", JsonNull.INSTANCE);
        } else {
          row.addProperty("groupBuyVoucherStatus", order.groupBuyVoucherStatus());
        }
        if (order.groupBuyVoucherConsumedAt() == null) {
          row.add("groupBuyVoucherConsumedAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("groupBuyVoucherConsumedAt", order.groupBuyVoucherConsumedAt().toString());
        }
        array.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("orders", array);
      sendJson(exchange, 200, response);
      adminAuditService.log(admin, "ORDER_LIST", "order", null, null, clientIp(exchange));
    });
  }

  private void handleAdminMarketListings(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.MARKET_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 200);
      String status = query.get("status");
      String seller = query.get("seller");
      String buyer = query.get("buyer");
      String material = query.get("material");
      String keyword = query.get("keyword");
      String currency = query.get("currency");
      List<MarketService.AdminListingView> listings = marketService.listAllListings(
          status,
          seller,
          buyer,
          material,
          keyword,
          currency,
          limit);
      JsonArray rows = new JsonArray();
      for (MarketService.AdminListingView listing : listings) {
        JsonObject row = new JsonObject();
        row.addProperty("id", listing.id());
        row.addProperty("sellerUserId", listing.sellerUserId());
        row.addProperty("sellerName", listing.sellerName());
        row.addProperty("sellerUuid", listing.sellerUuid().toString());
        if (listing.buyerUserId() == null) {
          row.add("buyerUserId", JsonNull.INSTANCE);
        } else {
          row.addProperty("buyerUserId", listing.buyerUserId());
        }
        if (listing.buyerName() == null) {
          row.add("buyerName", JsonNull.INSTANCE);
        } else {
          row.addProperty("buyerName", listing.buyerName());
        }
        if (listing.buyerUuid() == null) {
          row.add("buyerUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("buyerUuid", listing.buyerUuid().toString());
        }
        row.addProperty("currency", listing.currency().name());
        row.addProperty("price", listing.price());
        row.addProperty("quantity", listing.quantity());
        row.addProperty("quantityTotal", listing.quantityTotal());
        row.addProperty("side", listing.side().name());
        if (listing.tag() == null) {
          row.add("tag", JsonNull.INSTANCE);
        } else {
          row.addProperty("tag", listing.tag());
        }
        row.addProperty("tagVersion", listing.tagVersion());
        row.addProperty("escrowTotal", listing.escrowTotal());
        row.addProperty("escrowRemaining", listing.escrowRemaining());
        row.addProperty("itemMaterial", listing.itemMaterial());
        if (listing.displayNameOverride() == null) {
          row.add("displayNameOverride", JsonNull.INSTANCE);
        } else {
          row.addProperty("displayNameOverride", listing.displayNameOverride());
        }
        if (listing.displayMaterial() == null) {
          row.add("displayMaterial", JsonNull.INSTANCE);
        } else {
          row.addProperty("displayMaterial", listing.displayMaterial());
        }
        if (listing.displayIconPath() == null) {
          row.add("displayIconPath", JsonNull.INSTANCE);
        } else {
          row.addProperty("displayIconPath", listing.displayIconPath());
        }
        row.addProperty("itemMetaJson", listing.itemMetaJson());
        if (listing.remark() == null) {
          row.add("remark", JsonNull.INSTANCE);
        } else {
          row.addProperty("remark", listing.remark());
        }
        row.addProperty("status", listing.status());
        row.addProperty("createdAt", listing.createdAt().toString());
        if (listing.soldAt() == null) {
          row.add("soldAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("soldAt", listing.soldAt().toString());
        }
        if (listing.unlistedAt() == null) {
          row.add("unlistedAt", JsonNull.INSTANCE);
        } else {
          row.addProperty("unlistedAt", listing.unlistedAt().toString());
        }
        rows.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("listings", rows);
      sendJson(exchange, 200, response);
      adminAuditService.log(admin, "MARKET_LIST", "market", null, null, clientIp(exchange));
    });
  }

  private void handleAdminMarketUnlist(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.MARKET_MANAGE);
      long listingId = getLong(payload, "listingId", -1L);
      MarketService.UnlistResult result = marketService.adminUnlist(listingId);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("currency", result.currency().name());
      response.addProperty("price", result.price());
      response.addProperty("quantity", result.quantity());
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("listingId", result.listingId());
      adminAuditService.log(admin, "MARKET_UNLIST", "listing", String.valueOf(result.listingId()), detail, clientIp(exchange));
    });
  }

  private void handleAdminUserLookup(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.USER_SUPPORT);
      Map<String, String> query = parseQuery(exchange);
      String identifier = query.get("identifier");
      AdminService.UserSupportView userView = adminService.lookupUser(identifier)
          .orElseThrow(() -> new ServiceException("not_found", "User not found"));
      JsonObject response = new JsonObject();
      response.addProperty("id", userView.userId());
      response.addProperty("username", userView.username());
      response.addProperty("authState", userView.authState());
      response.addProperty("createdAt", userView.createdAt().toString());
      response.addProperty("shopCoin", userView.shopCoin());
      response.addProperty("gameCoin", userView.gameCoin());
      if (userView.boundUuid() == null) {
        response.add("boundUuid", JsonNull.INSTANCE);
      } else {
        response.addProperty("boundUuid", userView.boundUuid().toString());
      }
      sendJson(exchange, 200, response);
      adminAuditService.log(admin, "USER_LOOKUP", "user", String.valueOf(userView.userId()), null, clientIp(exchange));
    });
  }

  private void handleAdminUsersList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, AdminPermission.USER_SUPPORT);
      Map<String, String> query = parseQuery(exchange);
      String keyword = query.get("keyword");
      int limit = parseInt(query.get("limit"), 120);
      List<AdminService.UserListItem> users = adminService.listUsers(keyword, limit);
      JsonArray array = new JsonArray();
      for (AdminService.UserListItem user : users) {
        JsonObject row = new JsonObject();
        row.addProperty("id", user.userId());
        row.addProperty("username", user.username());
        row.addProperty("authState", user.authState());
        row.addProperty("createdAt", user.createdAt().toString());
        row.addProperty("shopCoin", user.shopCoin());
        row.addProperty("gameCoin", user.gameCoin());
        if (user.boundUuid() == null) {
          row.add("boundUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("boundUuid", user.boundUuid().toString());
        }
        array.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("users", array);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminResetPassword(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.USER_SUPPORT);
      long userId = resolveUserId(payload);
      String newPassword = getString(payload, "newPassword");
      adminService.resetPassword(userId, newPassword);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("userId", userId);
      adminAuditService.log(admin, "USER_RESET_PASSWORD", "user", String.valueOf(userId), detail, clientIp(exchange));
    });
  }

  private void handleAdminUnbind(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.USER_SUPPORT);
      long userId = resolveUserId(payload);
      adminService.unbindUser(userId);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("userId", userId);
      adminAuditService.log(admin, "USER_UNBIND", "user", String.valueOf(userId), detail, clientIp(exchange));
    });
  }

  private void handleAdminForceLogout(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.USER_SUPPORT);
      long userId = resolveUserId(payload);
      adminService.forceLogout(userId);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("userId", userId);
      adminAuditService.log(admin, "USER_FORCE_LOGOUT", "user", String.valueOf(userId), detail, clientIp(exchange));
    });
  }

  private void handleAdminWalletAdjust(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.USER_SUPPORT);
      long userId = resolveUserId(payload);
      CurrencyType currency = CurrencyType.fromConfig(getString(payload, "currency"));
      long delta = getLong(payload, "delta", 0L);
      String reason = getOptionalString(payload, "reason").orElse("ADMIN_ADJUST");
      WalletService.WalletBalance balance = adminService.adjustWallet(userId, currency, delta, reason);
      JsonObject response = new JsonObject();
      response.addProperty("shopCoin", balance.shopCoin());
      response.addProperty("gameCoin", balance.gameCoin());
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("userId", userId);
      detail.addProperty("currency", currency.name());
      detail.addProperty("delta", delta);
      detail.addProperty("reason", reason);
      adminAuditService.log(admin, "USER_WALLET_ADJUST", "wallet", String.valueOf(userId), detail, clientIp(exchange));
    });
  }

  private void handleAdminAuditList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.AUDIT_VIEW);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 200);
      List<AdminAuditService.AuditView> logs = adminAuditService.list(limit);
      JsonArray array = new JsonArray();
      for (AdminAuditService.AuditView log : logs) {
        JsonObject row = new JsonObject();
        row.addProperty("id", log.id());
        row.addProperty("adminUserId", log.adminUserId());
        row.addProperty("adminUsername", log.adminUsername());
        row.addProperty("adminRole", log.adminRole());
        row.addProperty("action", log.action());
        if (log.targetType() == null) {
          row.add("targetType", JsonNull.INSTANCE);
        } else {
          row.addProperty("targetType", log.targetType());
        }
        if (log.targetId() == null) {
          row.add("targetId", JsonNull.INSTANCE);
        } else {
          row.addProperty("targetId", log.targetId());
        }
        if (log.detailJson() == null) {
          row.add("detailJson", JsonNull.INSTANCE);
        } else {
          row.addProperty("detailJson", log.detailJson());
        }
        if (log.sourceIp() == null) {
          row.add("sourceIp", JsonNull.INSTANCE);
        } else {
          row.addProperty("sourceIp", log.sourceIp());
        }
        row.addProperty("createdAt", log.createdAt().toString());
        array.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("logs", array);
      sendJson(exchange, 200, response);
      adminAuditService.log(admin, "AUDIT_LIST", "audit", null, null, clientIp(exchange));
    });
  }

  private void handleAdminNotificationAnnounce(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String title = getString(payload, "title").trim();
      String content = getString(payload, "content").trim();
      if (title.isBlank() || content.isBlank()) {
        throw new ServiceException("bad_request", "Title and content must not be empty");
      }
      int delivered = notificationService.createSystemAnnouncement(title, content);
      JsonObject response = new JsonObject();
      response.addProperty("delivered", delivered);
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("title", title);
      detail.addProperty("delivered", delivered);
      adminAuditService.log(
          admin,
          "NOTIFICATION_ANNOUNCE",
          "notification",
          null,
          detail,
          clientIp(exchange));
    });
  }

  private void handleAdminUsersMeta(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireSuperAdmin(exchange, null);
      Map<String, String> query = parseQuery(exchange);
      String locale = query.containsKey("locale")
          ? readLocaleField(query.get("locale"), "locale")
          : settingsSupplier.get().defaultLocale();
      MessageService messageService = new MessageService(plugin, settingsSupplier);
      JsonObject response = new JsonObject();
      JsonArray groups = new JsonArray();
      for (AdminService.PermissionGroup group : adminService.listPermissionGroups()) {
        JsonObject groupJson = new JsonObject();
        groupJson.addProperty("key", group.key());
        groupJson.addProperty("label", messageService.get(locale, group.label()));
        JsonArray permissions = new JsonArray();
        for (AdminService.PermissionDefinition permission : group.permissions()) {
          JsonObject item = new JsonObject();
          item.addProperty("code", permission.code());
          item.addProperty("label", messageService.get(locale, permission.label()));
          item.addProperty("description", messageService.get(locale, permission.description()));
          permissions.add(item);
        }
        groupJson.add("permissions", permissions);
        groups.add(groupJson);
      }
      JsonArray templates = new JsonArray();
      for (AdminService.PermissionTemplate template : adminService.listPermissionTemplates()) {
        JsonObject item = new JsonObject();
        item.addProperty("key", template.key());
        item.addProperty("label", messageService.get(locale, template.label()));
        item.addProperty("description", messageService.get(locale, template.description()));
        item.addProperty("superAdmin", template.superAdmin());
        JsonArray permissions = new JsonArray();
        for (String code : template.permissions()) {
          permissions.add(code);
        }
        item.add("permissions", permissions);
        templates.add(item);
      }
      response.add("groups", groups);
      response.add("templates", templates);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminAdminUsersList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireSuperAdmin(exchange, null);
      JsonArray rows = new JsonArray();
      for (AdminService.AdminAccessView item : adminService.listAdmins()) {
        rows.add(adminAccessJson(item));
      }
      JsonObject response = new JsonObject();
      response.add("admins", rows);
      sendJson(exchange, 200, response);
      adminAuditService.log(admin, "ADMIN_USER_LIST", "admin_user", null, null, clientIp(exchange));
    });
  }

  private void handleAdminAdminUsersUpsert(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireSuperAdmin(exchange, payload);
      String identifier = getOptionalString(payload, "identifier")
          .or(() -> getOptionalString(payload, "username"))
          .orElseThrow(() -> new ServiceException("bad_request", "Missing field: identifier"));
      boolean superAdmin = payload.has("isSuperAdmin") && payload.get("isSuperAdmin").getAsBoolean();
      String templateKey = getOptionalString(payload, "templateKey").orElse(null);
      List<String> permissionCodes = getStringArray(payload, "permissions");
      java.util.Set<AdminPermission> permissions = java.util.EnumSet.noneOf(AdminPermission.class);
      for (String code : permissionCodes) {
        permissions.add(AdminPermission.valueOf(code.trim().toUpperCase(Locale.ROOT)));
      }

      AdminService.AdminAccessView updated =
          adminService.upsertAdmin(admin.userId(), identifier, superAdmin, permissions, templateKey);
      sendJson(exchange, 200, adminAccessJson(updated));

      JsonObject detail = new JsonObject();
      detail.addProperty("identifier", identifier);
      detail.addProperty("isSuperAdmin", updated.isSuperAdmin());
      detail.addProperty("templateKey", updated.templateKey());
      JsonArray permissionArray = new JsonArray();
      for (String code : updated.permissions()) {
        permissionArray.add(code);
      }
      detail.add("permissions", permissionArray);
      adminAuditService.log(
          admin,
          "ADMIN_USER_UPSERT",
          "admin_user",
          String.valueOf(updated.userId()),
          detail,
          clientIp(exchange));
    });
  }

  private void handleAdminAdminUsersActive(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireSuperAdmin(exchange, payload);
      long userId = getLong(payload, "userId", -1L);
      boolean active = payload.has("active") && payload.get("active").getAsBoolean();
      if (userId <= 0L) {
        throw new ServiceException("bad_request", "Invalid admin user id");
      }
      AdminService.AdminAccessView updated = adminService.setAdminActive(admin.userId(), userId, active);
      sendJson(exchange, 200, adminAccessJson(updated));

      JsonObject detail = new JsonObject();
      detail.addProperty("active", updated.active());
      detail.addProperty("isSuperAdmin", updated.isSuperAdmin());
      adminAuditService.log(
          admin,
          active ? "ADMIN_USER_ENABLE" : "ADMIN_USER_DISABLE",
          "admin_user",
          String.valueOf(updated.userId()),
          detail,
          clientIp(exchange));
    });
  }

  private void handleStatic(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    String path = exchange.getRequestURI().getPath();
    if (path.startsWith("/api/")) {
      sendJson(exchange, 404, errorJson("not_found", "API endpoint not found"));
      return;
    }

    String relativePath = path.equals("/") ? "index.html" : path.substring(1);

    if (!relativePath.contains(".")) {
      if (relativePath.startsWith("admin")) {
        relativePath = "admin.html";
      } else {
        relativePath = "index.html";
      }
    }

    if (relativePath.contains("..")) {
      sendJson(exchange, 400, errorJson("bad_request", "Invalid static path"));
      return;
    }

    Path targetFile = resolveStaticFile(relativePath);
    if (targetFile == null) {
      sendJson(exchange, 404, errorJson("not_found", "Static file not found"));
      return;
    }

    byte[] content = Files.readAllBytes(targetFile);
    exchange.getResponseHeaders().set("Content-Type", contentType(targetFile));
    applyCorsHeaders(exchange);
    exchange.sendResponseHeaders(200, content.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(content);
    }
  }

  private Path resolveStaticFile(String relativePath) {
    Path userCandidate = resolveUnderRoot(webUserRoot, relativePath);
    if (userCandidate != null && Files.isRegularFile(userCandidate)) {
      return userCandidate;
    }
    Path systemCandidate = resolveUnderRoot(staticRoot, relativePath);
    if (systemCandidate != null && Files.isRegularFile(systemCandidate)) {
      return systemCandidate;
    }
    return null;
  }

  private Path resolveUnderRoot(Path root, String relativePath) {
    if (root == null) {
      return null;
    }
    Path candidate = root.resolve(relativePath).normalize();
    if (!candidate.startsWith(root)) {
      return null;
    }
    return candidate;
  }

  private boolean canRefund(OrderService.OrderView order, LocalDateTime now) {
    if (order == null) {
      return false;
    }
    String voucherStatus = order.groupBuyVoucherStatus();
    if (voucherStatus != null) {
      if ("REFUNDED".equalsIgnoreCase(voucherStatus) || "CONSUMED".equalsIgnoreCase(voucherStatus)) {
        return false;
      }
      if (settingsSupplier.get().refundUndeliveredEnabled() && "ISSUED".equalsIgnoreCase(voucherStatus)) {
        return true;
      }
    }

    if (settingsSupplier.get().refundUndeliveredEnabled()) {
      return "PENDING".equalsIgnoreCase(order.status())
          || "WAIT_CLAIM".equalsIgnoreCase(order.status());
    }
    return "PENDING".equalsIgnoreCase(order.status())
        && order.refundDeadline() != null
        && now.isBefore(order.refundDeadline());
  }

  private AuthService.AuthUser requireAuth(HttpExchange exchange, JsonObject payload) {
    String headerToken = readHeaderToken(exchange);
    String bodyToken = payload == null
        ? null
        : getOptionalString(payload, "sessionToken").orElse(null);

    String token = headerToken != null ? headerToken : bodyToken;
    if (token == null) {
      throw new ServiceException("auth_required", "Missing session token");
    }

    return authService.findUserBySession(token)
        .orElseThrow(() -> new ServiceException("auth_invalid", "Session token is invalid or expired"));
  }

  private Optional<AuthService.AuthUser> findOptionalAuth(HttpExchange exchange) {
    String token = readHeaderToken(exchange);
    if (token == null) {
      return Optional.empty();
    }
    return authService.findUserBySession(token);
  }

  private AdminService.AdminUser requireAdmin(
      HttpExchange exchange,
      JsonObject payload,
      AdminPermission permission) {
    AuthService.AuthUser user = requireAuth(exchange, payload);
    return adminService.requireAdmin(user, permission);
  }

  private AdminService.AdminUser requireSuperAdmin(HttpExchange exchange, JsonObject payload) {
    AuthService.AuthUser user = requireAuth(exchange, payload);
    return adminService.requireSuperAdmin(user);
  }

  private JsonObject adminProfileJson(AdminService.AdminUser admin) {
    JsonObject response = new JsonObject();
    response.addProperty("id", admin.userId());
    response.addProperty("username", admin.username());
    response.addProperty("role", admin.roleLabel());
    response.addProperty("isSuperAdmin", admin.isSuperAdmin());
    response.addProperty("canSetZeroPrice", admin.allows(AdminPermission.PRODUCT_ZERO_PRICE));
    response.addProperty("canManageAdmins", admin.isSuperAdmin());
    if (admin.templateKey() == null) {
      response.add("templateKey", JsonNull.INSTANCE);
    } else {
      response.addProperty("templateKey", admin.templateKey());
    }
    if (admin.boundUuid() == null) {
      response.add("boundUuid", JsonNull.INSTANCE);
    } else {
      response.addProperty("boundUuid", admin.boundUuid().toString());
    }
    JsonArray permissions = new JsonArray();
    for (String code : admin.permissionCodes()) {
      permissions.add(code);
    }
    response.add("permissions", permissions);
    return response;
  }

  private JsonObject adminAccessJson(AdminService.AdminAccessView admin) {
    JsonObject response = new JsonObject();
    response.addProperty("userId", admin.userId());
    response.addProperty("username", admin.username());
    response.addProperty("role", admin.roleLabel());
    response.addProperty("active", admin.active());
    response.addProperty("isSuperAdmin", admin.isSuperAdmin());
    if (admin.templateKey() == null) {
      response.add("templateKey", JsonNull.INSTANCE);
    } else {
      response.addProperty("templateKey", admin.templateKey());
    }
    if (admin.boundUuid() == null) {
      response.add("boundUuid", JsonNull.INSTANCE);
    } else {
      response.addProperty("boundUuid", admin.boundUuid().toString());
    }
    response.addProperty("createdAt", admin.createdAt().toString());
    response.addProperty("updatedAt", admin.updatedAt().toString());
    JsonArray permissions = new JsonArray();
    for (String code : admin.permissions()) {
      permissions.add(code);
    }
    response.add("permissions", permissions);
    return response;
  }

  private void addProductJson(
      JsonObject row,
      ProductService.ProductView product,
      boolean includePersonalLimitRemaining) {
    row.addProperty("id", product.id());
    row.addProperty("sku", product.sku());
    row.addProperty("title", product.title());
    if (product.remark() == null) {
      row.add("remark", JsonNull.INSTANCE);
    } else {
      row.addProperty("remark", product.remark());
    }
    row.addProperty("currency", product.currency().name());
    row.addProperty("price", product.price());
    row.addProperty("productType", product.productType().name());
    row.addProperty("dynamicPricingEnabled", product.dynamicPricingEnabled());
    row.addProperty("dynamicAlgorithm", product.dynamicAlgorithm());
    if (product.dynamicParamsJson() == null) {
      row.add("dynamicParamsJson", JsonNull.INSTANCE);
    } else {
      row.addProperty("dynamicParamsJson", product.dynamicParamsJson());
    }
    if (product.dynamicBasePrice() == null) {
      row.add("dynamicBasePrice", JsonNull.INSTANCE);
    } else {
      row.addProperty("dynamicBasePrice", product.dynamicBasePrice());
    }
    if (product.dynamicFloorPrice() == null) {
      row.add("dynamicFloorPrice", JsonNull.INSTANCE);
    } else {
      row.addProperty("dynamicFloorPrice", product.dynamicFloorPrice());
    }
    if (product.dynamicCapPrice() == null) {
      row.add("dynamicCapPrice", JsonNull.INSTANCE);
    } else {
      row.addProperty("dynamicCapPrice", product.dynamicCapPrice());
    }
    if (product.dynamicPriceStep() == null) {
      row.add("dynamicPriceStep", JsonNull.INSTANCE);
    } else {
      row.addProperty("dynamicPriceStep", product.dynamicPriceStep());
    }
    row.addProperty("dynamicDemandScore", product.dynamicDemandScore());
    addBusinessDateTime(row, "publishAt", product.publishAt());
    addBusinessDateTime(row, "unpublishAt", product.unpublishAt());
    if (product.itemMaterial() == null) {
      row.add("itemMaterial", JsonNull.INSTANCE);
    } else {
      row.addProperty("itemMaterial", product.itemMaterial());
    }
    if (product.displayNameOverride() == null) {
      row.add("displayNameOverride", JsonNull.INSTANCE);
    } else {
      row.addProperty("displayNameOverride", product.displayNameOverride());
    }
    if (product.displayMaterial() == null) {
      row.add("displayMaterial", JsonNull.INSTANCE);
    } else {
      row.addProperty("displayMaterial", product.displayMaterial());
    }
    if (product.displayIconPath() == null) {
      row.add("displayIconPath", JsonNull.INSTANCE);
    } else {
      row.addProperty("displayIconPath", product.displayIconPath());
    }
    if (product.itemAmount() == null) {
      row.add("itemAmount", JsonNull.INSTANCE);
    } else {
      row.addProperty("itemAmount", product.itemAmount());
    }
    if (product.stockRemaining() == null) {
      row.add("stockRemaining", JsonNull.INSTANCE);
    } else {
      row.addProperty("stockRemaining", product.stockRemaining());
    }
    if (product.perUserLimit() == null) {
      row.add("perUserLimit", JsonNull.INSTANCE);
    } else {
      row.addProperty("perUserLimit", product.perUserLimit());
    }
    if (includePersonalLimitRemaining) {
      if (product.personalLimitRemaining() == null) {
        row.add("personalLimitRemaining", JsonNull.INSTANCE);
      } else {
        row.addProperty("personalLimitRemaining", product.personalLimitRemaining());
      }
    }
    if (product.effectType() == null) {
      row.add("effectType", JsonNull.INSTANCE);
    } else {
      row.addProperty("effectType", product.effectType());
    }
    if (product.effectSeconds() == null) {
      row.add("effectSeconds", JsonNull.INSTANCE);
    } else {
      row.addProperty("effectSeconds", product.effectSeconds());
    }
    if (product.effectAmplifier() == null) {
      row.add("effectAmplifier", JsonNull.INSTANCE);
    } else {
      row.addProperty("effectAmplifier", product.effectAmplifier());
    }
  }

  private List<String> getStringArray(JsonObject payload, String field) {
    if (!payload.has(field) || payload.get(field).isJsonNull()) {
      return List.of();
    }
    JsonElement value = payload.get(field);
    if (!value.isJsonArray()) {
      throw new ServiceException("bad_request", "Field must be an array: " + field);
    }
    JsonArray array = value.getAsJsonArray();
    List<String> values = new java.util.ArrayList<>();
    for (JsonElement element : array) {
      if (element == null || element.isJsonNull()) {
        continue;
      }
      values.add(element.getAsString());
    }
    return values;
  }

  private String readHeaderToken(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null) {
      return null;
    }
    String trimmed = header.trim();
    if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
      return trimmed.substring("Bearer ".length()).trim();
    }
    return null;
  }

  private long resolveUserId(JsonObject payload) {
    if (payload.has("userId")) {
      long userId = getLong(payload, "userId", -1L);
      if (userId > 0) {
        return userId;
      }
    }
    String identifier = getOptionalString(payload, "identifier")
        .or(() -> getOptionalString(payload, "username"))
        .orElseThrow(() -> new ServiceException("bad_request", "Missing field: identifier"));
    return adminService.lookupUser(identifier)
        .map(AdminService.UserSupportView::userId)
        .orElseThrow(() -> new ServiceException("not_found", "User not found"));
  }

  private String clientIp(HttpExchange exchange) {
    String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return exchange.getRemoteAddress().getAddress().getHostAddress();
  }

  private JsonObject readJson(HttpExchange exchange) throws IOException {
    try (InputStream inputStream = exchange.getRequestBody();
         InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
      JsonElement parsed = JsonParser.parseReader(reader);
      if (!parsed.isJsonObject()) {
        throw new ServiceException("bad_request", "JSON payload must be an object");
      }
      return parsed.getAsJsonObject();
    }
  }

  private void withServiceHandling(HttpExchange exchange, CheckedRunnable runnable) throws IOException {
    try {
      runnable.run();
    } catch (ServiceException exception) {
      sendJson(exchange, 400, errorJson(exception.code(), exception.getMessage()));
    } catch (Exception exception) {
      plugin.getLogger().log(java.util.logging.Level.SEVERE, "HTTP request failed", exception);
      sendJson(exchange, 500, errorJson("internal_error", "Server internal error"));
    }
  }

  private JsonObject sessionResponse(AuthService.AuthResult result) {
    JsonObject response = new JsonObject();
    response.addProperty("sessionToken", result.sessionToken());
    response.addProperty("expiresAt", result.expiresAt().toString());
    JsonObject user = userResponse(result.user());
    response.add("user", user);
    response.addProperty("username", result.user().username());
    if (result.user().boundUuid() == null) {
      response.add("boundUuid", JsonNull.INSTANCE);
    } else {
      response.addProperty("boundUuid", result.user().boundUuid().toString());
    }
    return response;
  }

  private JsonObject userResponse(AuthService.AuthUser user) {
    JsonObject response = new JsonObject();
    response.addProperty("id", user.id());
    response.addProperty("username", user.username());
    if (user.boundUuid() == null) {
      response.add("boundUuid", JsonNull.INSTANCE);
    } else {
      response.addProperty("boundUuid", user.boundUuid().toString());
    }
    response.add("visualPermission", resolveUserVisualPermissionJson(user));
    return response;
  }

  private JsonObject rechargeCreateResultJson(RechargeService.RechargeCreateResult result) {
    JsonObject response = new JsonObject();
    response.addProperty("success", result.success());
    response.addProperty("orderId", result.orderId());
    addNullableString(response, "providerOrderId", result.providerOrderId());
    addNullableString(response, "payUrl", result.payUrl());
    addNullableString(response, "qrCodeUrl", result.qrCodeUrl());
    addNullableInstant(response, "expireTime", result.expireTime());
    addNullableString(response, "errorCode", result.errorCode());
    addNullableString(response, "message", result.message());
    return response;
  }

  private JsonObject rechargeOrderJson(RechargeService.RechargeOrder order) {
    JsonObject response = new JsonObject();
    response.addProperty("orderId", order.orderId());
    response.addProperty("userId", order.userId());
    addNullableString(response, "playerUuid", order.playerUuid() == null ? null : order.playerUuid().toString());
    response.addProperty("amountMinor", order.amountMinor());
    response.addProperty("currency", order.currency());
    response.addProperty("coinAmount", order.coinAmount());
    response.addProperty("status", order.status().name());
    addNullableString(response, "provider", order.provider());
    addNullableString(response, "providerOrderId", order.providerOrderId());
    addNullableString(response, "payUrl", order.payUrl());
    addNullableString(response, "qrCodeUrl", order.qrCodeUrl());
    addNullableInstant(response, "expireTime", order.expireTime());
    addNullableInstant(response, "paidTime", order.paidTime());
    addNullableInstant(response, "creditedTime", order.creditedTime());
    addNullableInstant(response, "createdAt", order.createdAt());
    addNullableInstant(response, "updatedAt", order.updatedAt());
    addNullableString(response, "errorCode", order.errorCode());
    addNullableString(response, "errorMessage", order.errorMessage());
    return response;
  }

  private JsonObject rechargePaymentSettingsJson(PluginSettings.PaymentSettings settings) {
    JsonObject response = new JsonObject();
    response.add("currencies", stringArrayJson(settings.rechargeCurrencies()));
    response.add("methods", paymentMethodArrayJson(settings.rechargeMethods()));
    return response;
  }

  private JsonObject paymentProviderInfoJson(WebShopXPaymentBridge.PaymentProviderInfo info) {
    JsonObject response = new JsonObject();
    if (info == null) {
      response.addProperty("available", false);
      response.add("providerId", JsonNull.INSTANCE);
      response.add("displayName", JsonNull.INSTANCE);
      response.add("supportedMethods", new JsonArray());
      response.add("supportedCurrencies", new JsonArray());
      return response;
    }
    response.addProperty("available", info.available());
    addNullableString(response, "providerId", info.providerId());
    addNullableString(response, "displayName", info.displayName());
    response.add("supportedMethods", paymentMethodArrayJson(List.copyOf(info.supportedMethods())));
    response.add("supportedCurrencies", stringArrayJson(List.copyOf(info.supportedCurrencies())));
    return response;
  }

  private JsonArray stringArrayJson(List<String> values) {
    JsonArray array = new JsonArray();
    if (values == null) {
      return array;
    }
    for (String value : values) {
      if (value != null) {
        array.add(value);
      }
    }
    return array;
  }

  private JsonArray paymentMethodArrayJson(List<PaymentMethod> methods) {
    JsonArray array = new JsonArray();
    if (methods == null) {
      return array;
    }
    for (PaymentMethod method : methods) {
      if (method != null) {
        array.add(method.name());
      }
    }
    return array;
  }

  private void addNullableString(JsonObject object, String key, String value) {
    if (value == null) {
      object.add(key, JsonNull.INSTANCE);
      return;
    }
    object.addProperty(key, value);
  }

  private void addNullableInstant(JsonObject object, String key, java.time.Instant value) {
    if (value == null) {
      object.add(key, JsonNull.INSTANCE);
      return;
    }
    object.addProperty(key, value.toString());
  }

  private JsonObject resolveUserVisualPermissionJson(AuthService.AuthUser user) {
    VisualCustomizationService.ResolvedPermission permission =
        visualCustomizationService.resolvePermission(user.id());
    return userVisualPermissionJson(permission);
  }

  private boolean ensureMethod(HttpExchange exchange, String method) throws IOException {
    ensureRequestStart(exchange);
    if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
      sendJson(exchange, 405, errorJson("method_not_allowed", "Method not allowed"));
      return false;
    }
    return true;
  }

  private void ensureRequestStart(HttpExchange exchange) {
    if (exchange == null) {
      return;
    }
    Object started = exchange.getAttribute(REQUEST_START_NANOS_ATTR);
    if (started instanceof Long) {
      return;
    }
    exchange.setAttribute(REQUEST_START_NANOS_ATTR, System.nanoTime());
  }

  private boolean isPreflight(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
      return false;
    }
    applyCorsHeaders(exchange);
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
    return true;
  }

  private String contentType(Path targetFile) {
    Path fileNamePath = targetFile.getFileName();
    if (fileNamePath == null) {
      return "application/octet-stream";
    }
    String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
    if (fileName.endsWith(".html")) {
      return "text/html; charset=utf-8";
    }
    if (fileName.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (fileName.endsWith(".js")) {
      return "application/javascript; charset=utf-8";
    }
    if (fileName.endsWith(".json")) {
      return "application/json; charset=utf-8";
    }
    if (fileName.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    if (fileName.endsWith(".png")) {
      return "image/png";
    }
    if (fileName.endsWith(".svg")) {
      return "image/svg+xml; charset=utf-8";
    }
    if (fileName.endsWith(".webp")) {
      return "image/webp";
    }
    return "application/octet-stream";
  }

  private String getString(JsonObject payload, String key) {
    JsonElement value = payload.get(key);
    if (value == null || value.isJsonNull()) {
      throw new ServiceException("bad_request", "Missing field: " + key);
    }
    return value.getAsString();
  }

  private Optional<String> getOptionalString(JsonObject payload, String key) {
    if (payload == null || !payload.has(key)) {
      return Optional.empty();
    }
    JsonElement value = payload.get(key);
    if (value == null || value.isJsonNull()) {
      return Optional.empty();
    }
    String text = value.getAsString();
    if (text.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(text);
  }

  private PaymentMethod parsePaymentMethod(String raw) {
    PaymentMethod method = PluginSettings.parsePaymentMethod(raw);
    if (method == null) {
      throw new ServiceException("METHOD_UNSUPPORTED", "Unsupported payment method: " + raw);
    }
    return method;
  }

  private java.time.LocalDateTime getOptionalDateTime(JsonObject payload, String key) {
    Optional<String> raw = getOptionalString(payload, key);
    if (raw.isEmpty()) {
      return null;
    }
    try {
      return TimeSupport.parseClientDateTimeToUtc(raw.get(), settingsSupplier.get().timeZone());
    } catch (java.time.format.DateTimeParseException exception) {
      throw new ServiceException("bad_request", "Invalid datetime: " + key);
    }
  }

  private void addBusinessDateTime(JsonObject object, String key, LocalDateTime utcDateTime) {
    if (utcDateTime == null) {
      object.add(key, JsonNull.INSTANCE);
      return;
    }
    object.addProperty(key, TimeSupport.formatBusinessIsoOffset(utcDateTime, settingsSupplier.get().timeZone()));
  }

  private long getLong(JsonObject payload, String key, long fallback) {
    if (!payload.has(key)) {
      return fallback;
    }
    JsonElement value = payload.get(key);
    if (value == null || value.isJsonNull()) {
      return fallback;
    }
    try {
      return value.getAsLong();
    } catch (NumberFormatException exception) {
      throw new ServiceException("bad_request", "Invalid number: " + key);
    }
  }

  private boolean getBoolean(JsonObject payload, String key) {
    JsonElement value = payload.get(key);
    if (value == null || value.isJsonNull()) {
      throw new ServiceException("bad_request", "Missing field: " + key);
    }
    return value.getAsBoolean();
  }

  private double getDouble(JsonObject payload, String key) {
    JsonElement value = payload.get(key);
    if (value == null || value.isJsonNull()) {
      throw new ServiceException("bad_request", "Missing field: " + key);
    }
    try {
      return value.getAsDouble();
    } catch (NumberFormatException exception) {
      throw new ServiceException("bad_request", "Invalid number: " + key);
    }
  }

  private int countJsonArray(JsonObject payload, String key) {
    if (payload == null || !payload.has(key)) {
      return 0;
    }
    JsonElement value = payload.get(key);
    if (value == null || value.isJsonNull() || !value.isJsonArray()) {
      return 0;
    }
    return value.getAsJsonArray().size();
  }

  private Long parseLong(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private int parseInt(String raw, int fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }

  private boolean parseBoolean(String raw) {
    if (raw == null) {
      return false;
    }
    return raw.equalsIgnoreCase("1")
        || raw.equalsIgnoreCase("true")
        || raw.equalsIgnoreCase("yes");
  }

  private Map<String, String> parseQuery(HttpExchange exchange) {
    String rawQuery = exchange.getRequestURI().getRawQuery();
    Map<String, String> query = new HashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return query;
    }
    String[] entries = rawQuery.split("&");
    for (String entry : entries) {
      String[] parts = entry.split("=", 2);
      if (parts.length == 0 || parts[0].isBlank()) {
        continue;
      }
      String key = decodeUrl(parts[0]);
      String value = parts.length > 1 ? decodeUrl(parts[1]) : "";
      query.put(key, value);
    }
    return query;
  }

  private String decodeUrl(String raw) {
    return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
  }

  private double clampPercent(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(100.0, value));
  }

  private String normalizeCurrencyField(String value, String fieldName, int maxLength) {
    if (value == null) {
      throw new ServiceException("bad_request", "Missing field: " + fieldName);
    }
    String normalized = value.trim();
    if (normalized.isBlank()) {
      throw new ServiceException("bad_request", "Field must not be empty: " + fieldName);
    }
    if (normalized.length() > maxLength) {
      throw new ServiceException(
          "bad_request",
          "Field is too long (max " + maxLength + "): " + fieldName);
    }
    return normalized;
  }

  private int clampInt(long value, int min, int max, String fieldName) {
    if (value < min || value > max) {
      throw new ServiceException("bad_request", "Field out of range: " + fieldName);
    }
    return (int) value;
  }

  private String readLocaleField(String value, String fieldName) {
    String normalized = String.valueOf(value == null ? "" : value).trim().replace('_', '-');
    if (normalized.isBlank()) {
      throw new ServiceException("bad_request", "Missing field: " + fieldName);
    }
    if (normalized.equalsIgnoreCase("zh") || normalized.regionMatches(true, 0, "zh-", 0, 3)) {
      return "zh-CN";
    }
    if (normalized.equalsIgnoreCase("en") || normalized.regionMatches(true, 0, "en-", 0, 3)) {
      return "en-US";
    }
    String[] segments = normalized.split("-");
    if (segments.length == 0 || segments[0].isBlank()) {
      throw new ServiceException("bad_request", "Invalid locale: " + fieldName);
    }
    String language = segments[0].toLowerCase();
    if (segments.length == 1) {
      return language;
    }
    String region = segments[1].length() == 2
        ? segments[1].toUpperCase()
        : segments[1].toLowerCase();
    if (segments.length == 2) {
      return language + "-" + region;
    }
    StringBuilder builder = new StringBuilder(language).append('-').append(region);
    for (int i = 2; i < segments.length; i++) {
      String part = segments[i].trim();
      if (!part.isEmpty()) {
        builder.append('-').append(part.toLowerCase());
      }
    }
    return builder.toString();
  }

  private ZoneId readTimeZoneField(String value, String fieldName) {
    try {
      return ZoneId.of(String.valueOf(value == null ? "" : value).trim());
    } catch (Exception exception) {
      throw new ServiceException("bad_request", "Invalid timezone: " + fieldName);
    }
  }

  private String normalizeLogDirectory(String value) {
    String normalized = String.valueOf(value == null ? "" : value).trim();
    if (normalized.isBlank()) {
      throw new ServiceException("bad_request", "Field must not be empty: directory");
    }
    if (normalized.contains("..") || normalized.startsWith("/") || normalized.startsWith("\\")) {
      throw new ServiceException("bad_request", "Invalid log directory");
    }
    return normalized;
  }

  private URI parseManifestUri(String rawUrl) {
    String normalized = String.valueOf(rawUrl == null ? "" : rawUrl).trim();
    if (normalized.isBlank()) {
      throw new ServiceException("bad_request", "Missing field: url");
    }
    URI uri;
    try {
      uri = URI.create(normalized);
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("bad_request", "Invalid manifest URL");
    }
    validateManifestUri(uri);
    return uri;
  }

  private void validateManifestUri(URI uri) {
    if (uri == null || uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
      throw new ServiceException("bad_request", "Manifest URL must use https");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new ServiceException("bad_request", "Manifest URL host is invalid");
    }
    if (uri.getUserInfo() != null) {
      throw new ServiceException("bad_request", "Manifest URL must not include credentials");
    }
    if (!isAllowedManifestHost(host)) {
      throw new ServiceException("bad_request", "Manifest URL host is not allowed");
    }
  }

  private boolean isAllowedManifestHost(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    while (normalized.startsWith(".")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith(".")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    for (String allowed : MANIFEST_HOST_ALLOWLIST) {
      if (normalized.equals(allowed) || normalized.endsWith("." + allowed)) {
        return true;
      }
    }
    return false;
  }

  private JsonObject fetchRemoteManifest(URI uri, RemoteFetchOptions remoteFetchOptions) {
    byte[] body = fetchRemoteResourceWithRetry(
        uri,
        "manifest",
        "application/json",
        "WebShopX-Manifest-Proxy",
        Duration.ofSeconds(20),
        REMOTE_MANIFEST_MAX_BYTES,
        remoteFetchOptions);

    String text = new String(body, StandardCharsets.UTF_8).trim();
    if (text.isEmpty()) {
      throw new ServiceException("bad_gateway", "Manifest response is empty");
    }
    JsonElement parsed;
    try {
      parsed = JsonParser.parseString(text);
    } catch (Exception exception) {
      throw new ServiceException("bad_gateway", "Manifest JSON is invalid");
    }
    if (!parsed.isJsonObject()) {
      throw new ServiceException("bad_gateway", "Manifest JSON must be an object");
    }
    return parsed.getAsJsonObject();
  }

  private byte[] fetchRemoteBinary(URI uri, String label, int maxBytes, RemoteFetchOptions remoteFetchOptions) {
    return fetchRemoteResourceWithRetry(
        uri,
        label,
        "*/*",
        "WebShopX-Locale-Sync",
        Duration.ofSeconds(30),
        maxBytes,
        remoteFetchOptions);
  }

  private byte[] fetchRemoteResourceWithRetry(
      URI uri,
      String label,
      String accept,
      String userAgent,
      Duration timeout,
      int maxBytes,
      RemoteFetchOptions remoteFetchOptions) {
    List<URI> candidates = buildRemoteFetchCandidates(uri, remoteFetchOptions);
    if (candidates.isEmpty()) {
      throw new ServiceException("bad_request", "No valid URL candidate for " + label);
    }
    Exception lastFailure = null;
    int lastStatus = 0;
    int totalAttempts = Math.max(REMOTE_FETCH_MAX_ATTEMPTS, candidates.size());
    for (int attempt = 1; attempt <= totalAttempts; attempt++) {
      URI attemptUri = candidates.get((attempt - 1) % candidates.size());
      int attemptStatus = 0;
      HttpRequest request = HttpRequest.newBuilder(attemptUri)
          .timeout(timeout)
          .header("Accept", accept)
          .header("User-Agent", userAgent)
          .GET()
          .build();
      try {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.uri() != null) {
          validateManifestUri(response.uri());
        }
        int status = response.statusCode();
        attemptStatus = status;
        lastStatus = status;
        if (status < 200 || status >= 300) {
          if (attempt < totalAttempts && isRetriableHttpStatus(status)) {
            logRemoteRetry(label, attemptUri, attempt, totalAttempts, "HTTP " + status);
            sleepBeforeRemoteRetry(attempt);
            continue;
          }
          throw new ServiceException("bad_gateway", label + " upstream HTTP " + status);
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
          if (attempt < totalAttempts) {
            logRemoteRetry(label, attemptUri, attempt, totalAttempts, "empty response");
            sleepBeforeRemoteRetry(attempt);
            continue;
          }
          throw new ServiceException("bad_gateway", label + " response is empty");
        }
        if (body.length > maxBytes) {
          throw new ServiceException("bad_request", label + " response is too large");
        }
        return body;
      } catch (ServiceException exception) {
        lastFailure = exception;
        boolean retryable = isRetriableServiceException(exception, attemptStatus);
        if (attempt >= totalAttempts || !retryable) {
          throw exception;
        }
        logRemoteRetry(label, attemptUri, attempt, totalAttempts, exception.getMessage());
        sleepBeforeRemoteRetry(attempt);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new ServiceException("bad_gateway", label + " fetch interrupted");
      } catch (IOException exception) {
        lastFailure = exception;
        if (attempt >= totalAttempts) {
          throw new ServiceException("bad_gateway", "Failed to fetch " + label);
        }
        logRemoteRetry(label, attemptUri, attempt, totalAttempts, exception.getClass().getSimpleName());
        sleepBeforeRemoteRetry(attempt);
      }
    }
    if (lastFailure instanceof ServiceException serviceException) {
      throw serviceException;
    }
    if (lastStatus > 0) {
      throw new ServiceException("bad_gateway", label + " upstream HTTP " + lastStatus);
    }
    throw new ServiceException("bad_gateway", "Failed to fetch " + label);
  }

  private boolean isRetriableServiceException(ServiceException exception, int attemptStatus) {
    if (exception == null) {
      return false;
    }
    if ("bad_request".equals(exception.code())) {
      return false;
    }
    if (attemptStatus > 0 && !isRetriableHttpStatus(attemptStatus)) {
      return false;
    }
    return true;
  }

  private boolean isRetriableHttpStatus(int status) {
    return status == 408 || status == 425 || status == 429 || status >= 500;
  }

  private void logRemoteRetry(String label, URI uri, int attempt, int totalAttempts, String reason) {
    plugin.getLogger().warning(
        "[l10n] Remote fetch retry " + attempt + "/" + totalAttempts
            + " for " + label + " (" + uri + "), reason: " + reason);
  }

  private void sleepBeforeRemoteRetry(int attempt) {
    long delay = REMOTE_FETCH_RETRY_BASE_DELAY_MS * Math.max(1, attempt);
    try {
      Thread.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ServiceException("bad_gateway", "Remote fetch retry interrupted");
    }
  }

  private List<URI> buildRemoteFetchCandidates(URI sourceUri, RemoteFetchOptions remoteFetchOptions) {
    java.util.LinkedHashMap<String, URI> map = new java.util.LinkedHashMap<>();
    addFetchCandidate(map, sourceUri);
    if (remoteFetchOptions == null || !remoteFetchOptions.enableGithubMirrorFallback()) {
      return List.copyOf(map.values());
    }

    String host = normalizeHost(sourceUri == null ? null : sourceUri.getHost());
    if (host.isEmpty()) {
      return List.copyOf(map.values());
    }

    if (isGitHubOriginHost(host)) {
      String target = sourceUri.toString();
      addGithubProxyCandidates(map, target, remoteFetchOptions.preferredGithubProxyPrefix());
      return List.copyOf(map.values());
    }

    if (isGitHubMirrorHost(host)) {
      String wrappedTarget = extractWrappedTargetUrl(sourceUri);
      if (!wrappedTarget.isBlank()) {
        addFetchCandidateFromText(map, wrappedTarget);
        addGithubProxyCandidates(map, wrappedTarget, remoteFetchOptions.preferredGithubProxyPrefix());
      }
    }

    return List.copyOf(map.values());
  }

  private boolean isGithubMirrorFallbackEnabledForManifest(URI manifestUri) {
    String host = normalizeHost(manifestUri == null ? null : manifestUri.getHost());
    return isGitHubOriginHost(host) || isGitHubMirrorHost(host);
  }

  private RemoteFetchOptions resolveRemoteFetchOptions(URI manifestUri, String rawMode, String rawPreferredPrefix) {
    boolean defaultFallback = isGithubMirrorFallbackEnabledForManifest(manifestUri);
    String mode = normalizeGithubProxyMode(rawMode);
    boolean fallbackEnabled = switch (mode) {
      case GITHUB_PROXY_MODE_OFF -> false;
      case GITHUB_PROXY_MODE_ON -> true;
      default -> defaultFallback;
    };
    if (!fallbackEnabled) {
      return new RemoteFetchOptions(false, "");
    }
    String preferredPrefix = normalizeGithubProxyPrefix(rawPreferredPrefix);
    if (!preferredPrefix.isBlank() && !isKnownGithubProxyPrefix(preferredPrefix)) {
      throw new ServiceException("bad_request", "Invalid field: githubProxyPrefix");
    }
    return new RemoteFetchOptions(true, preferredPrefix);
  }

  private String normalizeGithubProxyMode(String rawMode) {
    String normalized = String.valueOf(rawMode == null ? "" : rawMode).trim().toLowerCase(Locale.ROOT);
    if (GITHUB_PROXY_MODE_ON.equals(normalized) || GITHUB_PROXY_MODE_OFF.equals(normalized)) {
      return normalized;
    }
    return GITHUB_PROXY_MODE_AUTO;
  }

  private String normalizeGithubProxyPrefix(String rawPrefix) {
    String normalized = String.valueOf(rawPrefix == null ? "" : rawPrefix).trim();
    if (normalized.isBlank() || !normalized.regionMatches(true, 0, "https://", 0, 8)) {
      return "";
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.isBlank() ? "" : normalized + "/";
  }

  private boolean isKnownGithubProxyPrefix(String prefix) {
    String normalized = normalizeGithubProxyPrefix(prefix);
    if (normalized.isBlank()) {
      return false;
    }
    for (String candidate : GITHUB_PROXY_PREFIXES) {
      if (normalized.equals(normalizeGithubProxyPrefix(candidate))) {
        return true;
      }
    }
    return false;
  }

  private void addGithubProxyCandidates(
      java.util.LinkedHashMap<String, URI> map,
      String targetUrl,
      String preferredPrefix) {
    String preferred = normalizeGithubProxyPrefix(preferredPrefix);
    if (!preferred.isBlank()) {
      addFetchCandidateFromText(map, preferred + targetUrl);
    }
    for (String prefix : GITHUB_PROXY_PREFIXES) {
      String normalizedPrefix = normalizeGithubProxyPrefix(prefix);
      if (normalizedPrefix.equals(preferred)) {
        continue;
      }
      addFetchCandidateFromText(map, normalizedPrefix + targetUrl);
    }
  }

  private static final class RemoteFetchOptions {
    private final boolean enableGithubMirrorFallback;
    private final String preferredGithubProxyPrefix;

    private RemoteFetchOptions(boolean enableGithubMirrorFallback, String preferredGithubProxyPrefix) {
      this.enableGithubMirrorFallback = enableGithubMirrorFallback;
      this.preferredGithubProxyPrefix =
          String.valueOf(preferredGithubProxyPrefix == null ? "" : preferredGithubProxyPrefix);
    }

    private boolean enableGithubMirrorFallback() {
      return enableGithubMirrorFallback;
    }

    private String preferredGithubProxyPrefix() {
      return preferredGithubProxyPrefix;
    }
  }

  private void addFetchCandidate(java.util.LinkedHashMap<String, URI> map, URI candidate) {
    if (candidate == null) {
      return;
    }
    try {
      validateManifestUri(candidate);
      map.putIfAbsent(candidate.toString(), candidate);
    } catch (ServiceException exception) {
      // ignore invalid candidate
    }
  }

  private void addFetchCandidateFromText(java.util.LinkedHashMap<String, URI> map, String rawCandidate) {
    if (rawCandidate == null || rawCandidate.isBlank()) {
      return;
    }
    try {
      addFetchCandidate(map, URI.create(rawCandidate));
    } catch (IllegalArgumentException exception) {
      // ignore invalid candidate
    }
  }

  private String normalizeHost(String host) {
    String normalized = String.valueOf(host == null ? "" : host).trim().toLowerCase(Locale.ROOT);
    while (normalized.startsWith(".")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith(".")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private boolean isGitHubOriginHost(String host) {
    String normalized = normalizeHost(host);
    return normalized.equals("github.com")
        || normalized.equals("githubusercontent.com")
        || normalized.equals("objects.githubusercontent.com")
        || normalized.equals("release-assets.githubusercontent.com");
  }

  private boolean isGitHubMirrorHost(String host) {
    String normalized = normalizeHost(host);
    return normalized.equals("edgeone.gh-proxy.com")
        || normalized.equals("hk.gh-proxy.com")
        || normalized.equals("gh-proxy.com")
        || normalized.equals("gh.llkk.cc");
  }

  private String extractWrappedTargetUrl(URI mirrorUri) {
    if (mirrorUri == null) {
      return "";
    }
    String raw = String.valueOf(mirrorUri.toString()).trim();
    int marker = raw.indexOf("/https://");
    if (marker >= 0) {
      return raw.substring(marker + 1).trim();
    }
    if (raw.startsWith("https://")) {
      return "";
    }
    return "";
  }

  private List<ManifestLocalePackage> parseManifestLocalePackages(JsonObject manifest) {
    JsonElement localesElement = manifest.get("locales");
    if (localesElement == null || !localesElement.isJsonArray()) {
      return List.of();
    }
    String manifestVersion = getOptionalString(manifest, "version").orElse("manifest");
    List<ManifestLocalePackage> list = new java.util.ArrayList<>();
    for (JsonElement element : localesElement.getAsJsonArray()) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      String locale = canonicalizeLocaleTag(
          getOptionalString(row, "locale")
              .or(() -> getOptionalString(row, "code"))
              .or(() -> getOptionalString(row, "tag"))
              .orElse(""));
      if (locale.isBlank()) {
        continue;
      }
      String packageUrl = getOptionalString(row, "packageUrl")
          .or(() -> getOptionalString(row, "url"))
          .orElse("");
      if (packageUrl.isBlank()) {
        continue;
      }
      String version = getOptionalString(row, "version").orElse(manifestVersion);
      String name = getOptionalString(row, "name").orElse(locale);
      String nativeName = getOptionalString(row, "nativeName").orElse(name);
      list.add(new ManifestLocalePackage(locale, name, nativeName, version, packageUrl));
    }
    return list;
  }

  private List<ManifestThemePackage> parseManifestThemePackages(JsonObject manifest) {
    JsonElement themesElement = manifest.get("themes");
    if (themesElement == null || !themesElement.isJsonArray()) {
      return List.of();
    }
    String manifestVersion = getOptionalString(manifest, "version").orElse("manifest");
    List<ManifestThemePackage> list = new java.util.ArrayList<>();
    for (JsonElement element : themesElement.getAsJsonArray()) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      String themeId = normalizeThemeId(
          getOptionalString(row, "themeId")
              .or(() -> getOptionalString(row, "id"))
              .or(() -> getOptionalString(row, "key"))
              .or(() -> getOptionalString(row, "theme"))
              .orElse(""));
      if (themeId.isBlank()) {
        continue;
      }
      String packageUrl = getOptionalString(row, "packageUrl")
          .or(() -> getOptionalString(row, "url"))
          .or(() -> getOptionalString(row, "package"))
          .orElse("");
      if (packageUrl.isBlank()) {
        continue;
      }
      String version = getOptionalString(row, "version").orElse(manifestVersion);
      String name = getOptionalString(row, "name").orElse(themeId);
      list.add(new ManifestThemePackage(themeId, name, version, packageUrl));
    }
    return list;
  }

  private String canonicalizeLocaleTag(String raw) {
    String text = String.valueOf(raw == null ? "" : raw).trim().replace('_', '-');
    if (text.isBlank()) {
      return "";
    }
    String[] segments = Arrays.stream(text.split("-"))
        .map(String::trim)
        .filter(part -> !part.isEmpty())
        .toArray(String[]::new);
    if (segments.length == 0) {
      return "";
    }
    String language = segments[0].toLowerCase(Locale.ROOT);
    if (segments.length == 1) {
      if ("zh".equals(language)) {
        return "zh-CN";
      }
      if ("en".equals(language)) {
        return "en-US";
      }
      return language;
    }
    String region = segments[1].length() == 2
        ? segments[1].toUpperCase(Locale.ROOT)
        : segments[1].toLowerCase(Locale.ROOT);
    if (segments.length == 2) {
      return language + "-" + region;
    }
    StringBuilder builder = new StringBuilder(language).append('-').append(region);
    for (int i = 2; i < segments.length; i++) {
      builder.append('-').append(segments[i].toLowerCase(Locale.ROOT));
    }
    return builder.toString();
  }

  private String normalizeThemeId(String rawThemeId) {
    String normalized = String.valueOf(rawThemeId == null ? "" : rawThemeId).trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return "";
    }
    normalized = normalized.replace(' ', '-');
    if (!normalized.matches("^[a-z0-9][a-z0-9._-]{0,47}$")) {
      return "";
    }
    return normalized;
  }

  private record ManifestLocalePackage(
      String locale,
      String name,
      String nativeName,
      String version,
      String packageUrl) {}

    private record ManifestThemePackage(
      String themeId,
      String name,
      String version,
      String packageUrl) {}

  private JsonArray materialOverrideListJson(List<MaterialVisualService.MaterialVisualEntry> entries) {
    JsonArray array = new JsonArray();
    for (MaterialVisualService.MaterialVisualEntry entry : entries) {
      array.add(materialOverrideJson(entry));
    }
    return array;
  }

  private JsonObject materialOverrideJson(MaterialVisualService.MaterialVisualEntry entry) {
    JsonObject row = new JsonObject();
    row.addProperty("materialKey", entry.materialKey());
    if (entry.displayNameOverride() == null) {
      row.add("displayNameOverride", JsonNull.INSTANCE);
    } else {
      row.addProperty("displayNameOverride", entry.displayNameOverride());
    }
    if (entry.iconPath() == null) {
      row.add("iconPath", JsonNull.INSTANCE);
    } else {
      row.addProperty("iconPath", entry.iconPath());
    }
    if (entry.updatedBy() == null) {
      row.add("updatedBy", JsonNull.INSTANCE);
    } else {
      row.addProperty("updatedBy", entry.updatedBy());
    }
    if (entry.updatedAt() == null) {
      row.add("updatedAt", JsonNull.INSTANCE);
    } else {
      row.addProperty("updatedAt", entry.updatedAt().toString());
    }
    return row;
  }

  private String normalizeMaterialField(String value, String fieldName) {
    String normalized = String.valueOf(value == null ? "" : value)
        .trim()
        .toUpperCase(Locale.ROOT)
        .replace("MINECRAFT:", "")
        .replaceAll("[^A-Z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
    if (normalized.isBlank() || normalized.length() > 64) {
      throw new ServiceException("bad_request", "Invalid material field: " + fieldName);
    }
    return normalized;
  }

  private String normalizeMaterialDisplayName(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return null;
    }
    String text = element.getAsString().trim();
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > 128) {
      throw new ServiceException("bad_request", "displayNameOverride is too long");
    }
    return text;
  }

  private String normalizeMaterialIconPath(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return null;
    }
    String text = element.getAsString().trim();
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > 255) {
      throw new ServiceException("bad_request", "iconPath is too long");
    }
    if (!text.startsWith("/")) {
      throw new ServiceException("bad_request", "iconPath must start with '/'");
    }
    if (text.contains("..")) {
      throw new ServiceException("bad_request", "iconPath is invalid");
    }
    return text;
  }

  private String resolveIconUploadExtension(String queryFilename, String headerFilename, String contentType) {
    String extension = extractFileExtension(queryFilename);
    if (extension == null) {
      extension = extractFileExtension(headerFilename);
    }
    if (extension == null) {
      extension = extensionFromContentType(contentType);
    }
    if (extension == null || !MATERIAL_ICON_ALLOWED_EXTENSIONS.contains(extension)) {
      throw new ServiceException("bad_request", "Unsupported icon file type");
    }
    return extension;
  }

  private String extractFileExtension(String rawFilename) {
    String fileName = String.valueOf(rawFilename == null ? "" : rawFilename).trim().replace('\\', '/');
    if (fileName.isBlank()) {
      return null;
    }
    int slash = fileName.lastIndexOf('/');
    if (slash >= 0 && slash < fileName.length() - 1) {
      fileName = fileName.substring(slash + 1);
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot >= fileName.length() - 1) {
      return null;
    }
    String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    return extension.isBlank() ? null : extension;
  }

  private String extensionFromContentType(String contentType) {
    String normalized = String.valueOf(contentType == null ? "" : contentType)
        .trim()
        .toLowerCase(Locale.ROOT);
    if (normalized.startsWith("image/png")) {
      return "png";
    }
    if (normalized.startsWith("image/webp")) {
      return "webp";
    }
    if (normalized.startsWith("image/jpeg") || normalized.startsWith("image/jpg")) {
      return "jpg";
    }
    if (normalized.startsWith("image/gif")) {
      return "gif";
    }
    return null;
  }

  private byte[] readRequestBodyWithLimit(HttpExchange exchange, int maxBytes) throws IOException {
    try (InputStream inputStream = exchange.getRequestBody();
         ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = inputStream.read(buffer)) >= 0) {
        total += read;
        if (total > maxBytes) {
          throw new ServiceException("bad_request", "File is too large");
        }
        outputStream.write(buffer, 0, read);
      }
      return outputStream.toByteArray();
    }
  }

  private Path resolveMaterialIconRoot() throws IOException {
    return resolveUserUploadRoot("material-icons");
  }

  private Path resolveProductIconRoot() throws IOException {
    return resolveUserUploadRoot("product-icons");
  }

  private Path resolveListingIconRoot() throws IOException {
    return resolveUserUploadRoot("listing-icons");
  }

  private Path resolveUserUploadRoot(String subDirectory) throws IOException {
    if (webUserRoot == null) {
      throw new ServiceException("internal_error", "User web root is not initialized");
    }
    Path iconRoot = webUserRoot.resolve("uploads").resolve(subDirectory).normalize();
    if (!iconRoot.startsWith(webUserRoot)) {
      throw new ServiceException("bad_request", "Invalid icon storage path");
    }
    Files.createDirectories(iconRoot);
    return iconRoot;
  }

  private void deleteManagedMaterialIcon(String iconPath) {
    String normalized = String.valueOf(iconPath == null ? "" : iconPath).trim().replace('\\', '/');
    if (!normalized.startsWith("/uploads/material-icons/")) {
      return;
    }
    String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
    if (fileName.isBlank()) {
      return;
    }
    try {
      Path iconRoot = resolveMaterialIconRoot();
      Path target = iconRoot.resolve(fileName).normalize();
      if (!target.startsWith(iconRoot)) {
        return;
      }
      Files.deleteIfExists(target);
    } catch (Exception exception) {
      MessageService ms = new MessageService(plugin, settingsSupplier);
      plugin.getLogger().warning(ms.formatConsole("console.failed_cleanup_old_material", Map.of("reason", exception.getMessage())));
    }
  }

  private void deleteManagedProductIcon(String iconPath) {
    deleteManagedUploadedIcon(iconPath, "/uploads/product-icons/", "product icon", this::resolveProductIconRoot);
  }

  private void deleteManagedListingIcon(String iconPath) {
    deleteManagedUploadedIcon(iconPath, "/uploads/listing-icons/", "listing icon", this::resolveListingIconRoot);
  }

  private void deleteManagedUploadedIcon(
      String iconPath,
      String expectedPrefix,
      String label,
      CheckedPathSupplier rootSupplier) {
    String normalized = String.valueOf(iconPath == null ? "" : iconPath).trim().replace('\\', '/');
    if (!normalized.startsWith(expectedPrefix)) {
      return;
    }
    String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
    if (fileName.isBlank()) {
      return;
    }
    try {
      Path iconRoot = rootSupplier.get();
      Path target = iconRoot.resolve(fileName).normalize();
      if (!target.startsWith(iconRoot)) {
        return;
      }
      Files.deleteIfExists(target);
    } catch (Exception exception) {
      MessageService ms = new MessageService(plugin, settingsSupplier);
      plugin.getLogger().warning(ms.formatConsole("console.failed_cleanup_old", Map.of("label", label, "reason", exception.getMessage())));
    }
  }

  private void publishRuntimeConfigRefresh(long version) {
    if (plugin instanceof WebShopPlugin webShopPlugin) {
      schedulerBridge.runGlobal(webShopPlugin::reloadRuntimeBusinessSettings);
    }
    if (clusterEventBusService != null) {
      clusterEventBusService.publishConfigRefresh(version);
    }
  }

  private <T> T awaitPlayerTask(UUID playerUuid, java.util.function.Function<Player, T> task) {
    try {
      return schedulerBridge
          .supplyPlayer(playerUuid, task)
          .get(10L, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ServiceException("sync_interrupted", "Player task interrupted");
    } catch (TimeoutException exception) {
      throw new ServiceException("sync_timeout", "Player task timed out");
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Player task failed", cause);
    }
  }

  private JsonObject errorJson(String code, String message) {
    JsonObject response = new JsonObject();
    response.addProperty("error", code);
    response.addProperty("message", message);
    return response;
  }

  private void sendJson(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
    byte[] body = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    applyCorsHeaders(exchange);
    exchange.sendResponseHeaders(statusCode, body.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(body);
    }
    recordApiTelemetry(exchange, statusCode, payload);
  }

  private void recordApiTelemetry(HttpExchange exchange, int statusCode, JsonObject payload) {
    if (bStatsTelemetryService == null || exchange == null || exchange.getRequestURI() == null) {
      return;
    }
    String path = exchange.getRequestURI().getPath();
    if (path == null || !path.startsWith("/api/")) {
      return;
    }
    String locale = resolveTelemetryLocale(exchange);
    long durationMillis = resolveRequestDurationMillis(exchange);
    String errorCode = resolveErrorCodeFromPayload(statusCode, payload);
    String responseState = resolveResponseState(payload);
    bStatsTelemetryService.recordApiRequest(
        path,
        statusCode,
        locale,
        durationMillis,
        errorCode,
        responseState);
  }

  private long resolveRequestDurationMillis(HttpExchange exchange) {
    Object started = exchange.getAttribute(REQUEST_START_NANOS_ATTR);
    if (!(started instanceof Long startNanos)) {
      return 0L;
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    if (elapsedNanos <= 0L) {
      return 0L;
    }
    return elapsedNanos / 1_000_000L;
  }

  private String resolveErrorCodeFromPayload(int statusCode, JsonObject payload) {
    if (statusCode < 400) {
      return "ok";
    }
    if (payload != null && payload.has("error") && !payload.get("error").isJsonNull()) {
      String value = asTelemetryString(payload.get("error"));
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    if (statusCode >= 500) {
      return "internal_error";
    }
    return "unknown_error";
  }

  private String resolveResponseState(JsonObject payload) {
    if (payload == null || !payload.has("state") || payload.get("state").isJsonNull()) {
      return null;
    }
    String state = asTelemetryString(payload.get("state"));
    return state == null || state.isBlank() ? null : state.trim();
  }

  private String asTelemetryString(JsonElement value) {
    if (value == null || value.isJsonNull()) {
      return null;
    }
    if (value.isJsonPrimitive()) {
      try {
        return value.getAsString();
      } catch (UnsupportedOperationException ignored) {
        return null;
      }
    }
    return value.toString();
  }

  private String resolveTelemetryLocale(HttpExchange exchange) {
    try {
      Map<String, String> query = parseQuery(exchange);
      String localeFromQuery = query.get("locale");
      if (localeFromQuery != null && !localeFromQuery.isBlank()) {
        String normalized = canonicalizeLocaleTag(localeFromQuery);
        if (!normalized.isBlank()) {
          return normalized;
        }
      }
    } catch (Exception ignored) {
      // Keep fallback behavior below.
    }

    String explicit = exchange.getRequestHeaders().getFirst("X-WebShop-Locale");
    if (explicit != null && !explicit.isBlank()) {
      String normalized = canonicalizeLocaleTag(explicit);
      if (!normalized.isBlank()) {
        return normalized;
      }
    }

    String acceptLanguage = exchange.getRequestHeaders().getFirst("Accept-Language");
    if (acceptLanguage != null && !acceptLanguage.isBlank()) {
      String firstToken = acceptLanguage.split(",", 2)[0].trim();
      if (!firstToken.isBlank()) {
        String language = firstToken.split(";", 2)[0].trim();
        String normalized = canonicalizeLocaleTag(language);
        if (!normalized.isBlank()) {
          return normalized;
        }
      }
    }
    return settingsSupplier.get().defaultLocale();
  }

  private void applyCorsHeaders(HttpExchange exchange) {
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.getResponseHeaders().set(
        "Access-Control-Allow-Headers",
        "Content-Type, Authorization");
    exchange.getResponseHeaders().set(
        "Access-Control-Allow-Methods",
        "GET, POST, OPTIONS");
  }

  @FunctionalInterface
  private interface CheckedRunnable {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface CheckedPathSupplier {
    Path get() throws Exception;
  }
}

