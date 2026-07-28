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
import com.webshopx.payment.api.PaymentConfigUpdateRequest;
import com.webshopx.payment.api.PaymentConfigUpdateResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

class EmbeddedWebServer {
  private static final String REQUEST_START_NANOS_ATTR = "webshopx.request.startNanos";
  private final JavaPlugin plugin;
  private final SchedulerBridge schedulerBridge;
  private final DatabaseManager databaseManager;
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
  private final HomepageService homepageService;
  private final ClusterEventBusService clusterEventBusService;
  private final BStatsTelemetryService bStatsTelemetryService;
  private final PluginUpdateService pluginUpdateService;
  private final InventoryReadSnapshotService inventoryReadSnapshotService;
  private final OfflineInventoryFeatureService offlineInventoryFeatureService;
  private final PlayerDataInventoryService playerDataInventoryService;
  private final Gson gson;
  private final HttpClient textureHttpClient;
  private final VisualPackService visualPackService;
  private final ItemSnapshotCodec inventoryItemCodec = new ItemSnapshotCodec();
  private final InventoryService inventoryService = new InventoryService(inventoryItemCodec);
  private final InventorySnapshotJsonCodec inventorySnapshotJsonCodec;
  private final InventoryOperationService inventoryOperationService;
  private final MailboxService mailboxService;
  private final MailboxCenterService mailboxCenterService;
  private final RefundPolicyService refundPolicyService;
  private static final int MATERIAL_ICON_MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
  private static final Set<String> MATERIAL_ICON_ALLOWED_EXTENSIONS =
      Set.of("png", "webp", "jpg", "jpeg", "gif");
  private static final int HOME_ASSET_MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

  private final LocaleCenterService localeCenterService;

  private static final Map<String, RedirectEntry> redirectMap = new java.util.concurrent.ConcurrentHashMap<>();

  private HttpServer server;
  private ExecutorService executorService;
  private Path staticRoot;
  private Path webUserRoot;

  EmbeddedWebServer(
      JavaPlugin plugin,
      SchedulerBridge schedulerBridge,
      DatabaseManager databaseManager,
      Supplier<PluginSettings> settingsSupplier,
      AuthService authService,
      WalletService walletService,
      RechargeService rechargeService,
      RedeemCodeService redeemCodeService,
      ProductService productService,
      OrderService orderService,
      DeliveryService deliveryService,
      MarketService marketService,
      NotificationService notificationService,
      AdminService adminService,
      AdminAuditService adminAuditService,
      LeaderboardService leaderboardService,
      MaterialVisualService materialVisualService,
      VisualCustomizationService visualCustomizationService,
      VisualPackService visualPackService,
      UserMarketSettingsService userMarketSettingsService,
      RuntimeConfigService runtimeConfigService,
      HomepageService homepageService,
      ClusterEventBusService clusterEventBusService,
      BStatsTelemetryService bStatsTelemetryService,
      InventoryReadSnapshotService inventoryReadSnapshotService,
      OfflineInventoryFeatureService offlineInventoryFeatureService,
      PlayerDataInventoryService playerDataInventoryService) {
    this.plugin = plugin;
    this.schedulerBridge = schedulerBridge;
    this.databaseManager = databaseManager;
    this.inventoryOperationService = new InventoryOperationService(databaseManager);
    this.mailboxService = new MailboxService(databaseManager);
    this.mailboxCenterService = new MailboxCenterService(
        orderService, mailboxService, deliveryService, offlineInventoryFeatureService,
        playerDataInventoryService);
    this.refundPolicyService = new RefundPolicyService(databaseManager);
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
    this.visualPackService = visualPackService;
    this.userMarketSettingsService = userMarketSettingsService;
    this.runtimeConfigService = runtimeConfigService;
    this.homepageService = homepageService;
    this.clusterEventBusService = clusterEventBusService;
    this.bStatsTelemetryService = bStatsTelemetryService;
    this.inventoryReadSnapshotService = inventoryReadSnapshotService;
    this.offlineInventoryFeatureService = offlineInventoryFeatureService;
    this.playerDataInventoryService = playerDataInventoryService;
    this.pluginUpdateService = new PluginUpdateService(plugin);
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
    this.inventorySnapshotJsonCodec = new InventorySnapshotJsonCodec(gson);
    this.textureHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    this.localeCenterService = new LocaleCenterService(plugin, () -> this.webUserRoot);
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
    server.createContext("/api/recharge/redirect", this::handleRechargeRedirect);
    server.createContext("/api/redeem/use", this::handleRedeemUse);
    server.createContext("/api/products", this::handleProducts);
    server.createContext("/api/orders", this::handleOrders);
    server.createContext("/api/orders/list", this::handleOrdersList);
    server.createContext("/api/orders/refund", this::handleOrdersRefund);
    server.createContext("/api/orders/policy", this::handleOrdersPolicy);
    server.createContext("/api/orders/delivery-status", this::handleOrdersDeliveryStatus);
    server.createContext("/api/mailbox/list", this::handleMailboxList);
    server.createContext("/api/mailbox/count", this::handleMailboxCount);
    server.createContext("/api/mailbox/", this::handleMailboxEntry);
    server.createContext("/api/notifications/list", this::handleNotificationsList);
    server.createContext("/api/notifications/unread-count", this::handleNotificationsUnreadCount);
    server.createContext("/api/notifications/mark-read", this::handleNotificationsMarkRead);
    server.createContext("/api/meta/currency", this::handleCurrencyMeta);
    server.createContext("/api/meta/materials", this::handleMaterialMeta);
    server.createContext("/api/meta/material-overrides", this::handleMaterialOverrideMeta);
    server.createContext("/api/meta/market-tags", this::handleMarketTagsMeta);
    server.createContext("/api/market/auction-display-settings", this::handleAuctionDisplaySettings);
    server.createContext("/api/meta/locales", this::handleMetaLocales);
    server.createContext("/api/meta/version", this::handleMetaVersion);
    server.createContext("/api/locales/", this::handlePublicLocaleMessages);
    server.createContext("/api/leaderboard/config", this::handleLeaderboardConfig);
    server.createContext("/api/leaderboard/list", this::handleLeaderboardList);
    server.createContext("/api/homepage", this::handlePublicHomepage);
    server.createContext("/api/homepage/status", this::handlePublicHomepageStatus);
    server.createContext("/api/products/quote", this::handleProductsQuote);
    server.createContext("/api/products/price-trend", this::handleProductsPriceTrend);
    server.createContext("/api/market/listings", this::handleMarketListings);
    server.createContext("/api/market/listings/create", this::handleMarketListingsCreate);
    server.createContext("/api/market/price-trend", this::handleMarketPriceTrend);
    server.createContext("/api/market/quote", this::handleMarketQuote);
    server.createContext("/api/market/buy", this::handleMarketBuy);
    server.createContext("/api/market/sell-to-buy", this::handleMarketSellToBuy);
    server.createContext("/api/market/bid", this::handleMarketBid);
    server.createContext("/api/market/auction-insights", this::handleMarketAuctionInsights);
    server.createContext("/api/market/unlist", this::handleMarketUnlist);
    server.createContext("/api/market/pause", this::handleMarketPause);
    server.createContext("/api/market/resume", this::handleMarketResume);
    server.createContext("/api/market/price", this::handleMarketPrice);
    server.createContext("/api/market/remark", this::handleMarketRemark);
    server.createContext("/api/market/settings", this::handleMarketSettings);
    server.createContext("/api/market/icon/upload", this::handleMarketIconUpload);
    server.createContext("/api/market/supply/refresh", this::handleMarketSupplyRefresh);
    server.createContext("/api/inventory/snapshot", this::handleInventorySnapshot);
    server.createContext("/api/inventory/list", this::handleInventoryList);
    server.createContext("/api/inventory/matches", this::handleInventoryMatches);
    server.createContext("/api/inventory/fulfill", this::handleInventoryFulfill);
    server.createContext("/api/admin/auth/login", this::handleAdminLogin);
    server.createContext("/api/admin/auth/me", this::handleAdminMe);
    server.createContext("/api/admin/auth/logout", this::handleAdminLogout);
    server.createContext("/api/admin/overview/stats", this::handleAdminOverviewStats);
    server.createContext("/api/admin/locales", this::handleAdminLocalesList);
    server.createContext("/api/admin/locales/default", this::handleAdminLocalesDefault);
    server.createContext("/api/admin/locales/action", this::handleAdminLocalesAction);
    server.createContext("/api/admin/locales/upload", this::handleAdminLocalesUpload);
    server.createContext("/api/admin/redeem/create", this::handleAdminRedeemCreate);
    server.createContext("/api/admin/redeem/list", this::handleAdminRedeemList);
    server.createContext("/api/admin/products/list", this::handleAdminProductsList);
    server.createContext("/api/admin/products/upsert", this::handleAdminProductsUpsert);
    server.createContext("/api/admin/products/from-inventory", this::handleAdminProductFromInventory);
    server.createContext("/api/admin/products/snapshot-history", this::handleAdminProductSnapshotHistory);
    server.createContext("/api/admin/products/snapshot-rollback", this::handleAdminProductSnapshotRollback);
    server.createContext("/api/admin/products/icon", this::handleAdminProductIconUpload);
    server.createContext("/api/admin/products/active", this::handleAdminProductsActive);
    server.createContext("/api/admin/products/reset-limit", this::handleAdminProductsResetLimit);
    server.createContext("/api/admin/refund-policy", this::handleAdminRefundPolicy);
    server.createContext("/api/admin/products/refund-policy", this::handleAdminProductRefundPolicy);
    server.createContext("/api/admin/group-buy/consume", this::handleAdminGroupBuyConsume);
    server.createContext("/api/admin/orders/list", this::handleAdminOrdersList);
    server.createContext("/api/admin/economy/settings", this::handleAdminEconomySettings);
    server.createContext("/api/admin/economy/exchange", this::handleAdminExchangeUpdate);
    server.createContext("/api/admin/economy/market", this::handleAdminMarketEconomyUpdate);
    server.createContext("/api/admin/economy/leaderboard", this::handleAdminLeaderboardSettingsUpdate);
    server.createContext("/api/admin/economy/currency", this::handleAdminCurrencyDisplayUpdate);
    server.createContext("/api/admin/economy/recharge-payment", this::handleAdminRechargePaymentUpdate);
    server.createContext("/api/admin/economy/payment-provider-config", this::handleAdminPaymentProviderConfig);
    server.createContext("/api/admin/market/tags-config", this::handleAdminMarketTagsConfig);
    server.createContext("/api/admin/market/limitation-config", this::handleAdminMarketLimitationConfig);
    server.createContext("/api/admin/system/webshop", this::handleAdminWebshopRuntimeUpdate);
    server.createContext("/api/admin/system/home-link", this::handleAdminHomeLinkUpdate);
    server.createContext("/api/admin/system/market", this::handleAdminMarketRuntimeUpdate);
    server.createContext("/api/admin/system/auction-display", this::handleAdminAuctionDisplayUpdate);
    server.createContext("/api/admin/system/maintenance", this::handleAdminMaintenanceSettingsUpdate);
    server.createContext("/api/admin/system/logging", this::handleAdminLoggingSettingsUpdate);
    server.createContext("/api/admin/system/broadcast", this::handleAdminBroadcastSettingsUpdate);
    server.createContext("/api/admin/system/notification", this::handleAdminNotificationSettingsUpdate);
    server.createContext("/api/admin/system/offline-inventory", this::handleAdminOfflineInventory);
    server.createContext("/api/admin/system/update", this::handleAdminPluginUpdate);
    server.createContext("/api/admin/homepage/draft", this::handleAdminHomepageDraft);
    server.createContext("/api/admin/homepage/publish", this::handleAdminHomepagePublish);
    server.createContext("/api/admin/homepage/revisions", this::handleAdminHomepageRevisions);
    server.createContext("/api/admin/homepage/restore", this::handleAdminHomepageRestore);
    server.createContext("/api/admin/homepage/assets", this::handleAdminHomepageAssets);
    server.createContext("/api/admin/visual/settings", this::handleAdminVisualSettingsUpdate);
    server.createContext("/api/admin/visual-packs", this::handleAdminVisualPacks);
    server.createContext("/api/admin/visual-packs/upload", this::handleAdminVisualPackUpload);
    server.createContext("/api/admin/visual-packs/state", this::handleAdminVisualPackState);
    server.createContext("/api/admin/visual-packs/move", this::handleAdminVisualPackMove);
    server.createContext("/api/admin/visual-packs/delete", this::handleAdminVisualPackDelete);
    server.createContext("/api/admin/visual-packs/download", this::handleAdminVisualPackDownload);
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
    server.createContext("/api/admin/users/migrate-uuid", this::handleAdminMigrateUuid);
    server.createContext("/api/admin/users/logout", this::handleAdminForceLogout);
    server.createContext("/api/admin/users/wallet-adjust", this::handleAdminWalletAdjust);
    server.createContext("/api/admin/users/visual-permission", this::handleAdminUserVisualPermission);
    server.createContext("/api/admin/audit/list", this::handleAdminAuditList);
    server.createContext("/api/admin/notifications/announce", this::handleAdminNotificationAnnounce);
    server.createContext("/api/admin/admin-users/meta", this::handleAdminUsersMeta);
    server.createContext("/api/admin/admin-users/list", this::handleAdminAdminUsersList);
    server.createContext("/api/admin/admin-users/upsert", this::handleAdminAdminUsersUpsert);
    server.createContext("/api/admin/admin-users/active", this::handleAdminAdminUsersActive);
    server.createContext("/home-assets/", this::handleHomepageAsset);
    server.createContext("/textures/", this::handleTextureAsset);
    server.createContext("/visual-packs/", this::handleVisualPackAsset);

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
        addBusinessDateTime(row, "createdAt", entry.createdAt());
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
      String currency = getOptionalString(payload, "currency")
          .orElse(settingsSupplier.get().paymentSettings().primaryRechargeCurrency());
      PaymentMethod preferredMethod = getOptionalString(payload, "paymentMethod")
          .or(() -> getOptionalString(payload, "preferredMethod"))
          .map(this::parsePaymentMethod)
          .orElse(null);
      String methodCode = getOptionalString(payload, "methodCode").orElse(null);
      String locale = getOptionalString(payload, "locale").orElse(null);
      String baseUrl = resolveBaseUrl(exchange);
      RechargeService.RechargeCreateResult result = rechargeService.createRechargeOrder(
          new RechargeService.RechargeCreateRequest(
              user.id(),
              user.boundUuid(),
              amountMinor,
              currency,
              0L,
              preferredMethod,
              methodCode,
              "WEB",
              baseUrl,
              locale));
      JsonObject response = rechargeCreateResultJson(exchange, result);
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
      sendJson(exchange, 200, rechargeOrderJson(exchange, order));
    });
  }

  private void handleRechargeRedirect(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    String id = parseQuery(exchange).get("id");
    if (id == null || id.isBlank()) {
      sendJson(exchange, 400, errorJson("BAD_REQUEST", "Missing redirect ID"));
      return;
    }
    RedirectEntry entry = redirectMap.get(id);
    if (entry == null || System.currentTimeMillis() - entry.createdAt > 900000L) {
      sendJson(exchange, 404, errorJson("NOT_FOUND", "Redirect link expired or not found"));
      return;
    }
    exchange.getResponseHeaders().set("Location", entry.url);
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
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
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/api/products/quote")) {
      handleProductsQuote(exchange);
      return;
    }
    if (path.equals("/api/products/price-trend")) {
      handleProductsPriceTrend(exchange);
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

  private void handleProductsQuote(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      long productId = getLong(payload, "productId", -1L);
      int quantity = (int) getLong(payload, "quantity", 1L);
      ProductService.ProductPriceQuote quote = productService.quoteProduct(productId, quantity);
      JsonObject response = productPriceQuoteJson(quote);
      response.addProperty("productId", productId);
      sendJson(exchange, 200, response);
    });
  }

  private void handleProductsPriceTrend(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      Map<String, String> query = parseQuery(exchange);
      Long productIdRaw = parseLong(query.get("productId"));
      if (productIdRaw == null || productIdRaw <= 0L) {
        throw new ServiceException("bad_request", "Missing field: productId");
      }
      long productId = productIdRaw;
      int limit = parseInt(query.get("limit"), 30);
      List<ProductService.ProductPriceTrendPoint> points = productService.listPriceTrend(productId, limit);
      JsonArray history = new JsonArray();
      for (ProductService.ProductPriceTrendPoint point : points) {
        JsonObject row = new JsonObject();
        row.addProperty("orderItemId", point.orderItemId());
        row.addProperty("price", point.price());
        row.addProperty("quantity", point.quantity());
        addBusinessDateTime(row, "createdAt", point.createdAt());
        history.add(row);
      }
      JsonObject response = new JsonObject();
      response.addProperty("productId", productId);
      response.add("history", history);
      sendJson(exchange, 200, response);
    });
  }

  private void handleOrders(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/api/orders/list")) {
      handleOrdersList(exchange);
      return;
    }
    if (path.equals("/api/orders/refund")) {
      handleOrdersRefund(exchange);
      return;
    }
    if (path.equals("/api/orders/discard")) {
      handleOrdersDiscard(exchange);
      return;
    }
    if (path.equals("/api/orders/policy")) {
      handleOrdersPolicy(exchange);
      return;
    }
    if (path.equals("/api/orders/delivery-status")) {
      handleOrdersDeliveryStatus(exchange);
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
        addBusinessDateTime(response, "refundDeadline", result.refundDeadline());
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
        addBusinessDateTime(response, "groupBuyVoucherConsumedAt", result.groupBuyVoucherConsumedAt());
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
        row.addProperty("displayStatus", order.displayStatus());
        row.addProperty("currency", order.currency().name());
        row.addProperty("totalAmount", order.totalAmount());
        addBusinessDateTime(row, "createdAt", order.createdAt());
        if (order.mcUuid() == null) {
          row.add("mcUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("mcUuid", order.mcUuid().toString());
        }
        if (order.deliveredAt() == null) {
          row.add("deliveredAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "deliveredAt", order.deliveredAt());
        }
        if (order.refundedAt() == null) {
          row.add("refundedAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "refundedAt", order.refundedAt());
        }
        if (order.refundDeadline() == null) {
          row.add("refundDeadline", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "refundDeadline", order.refundDeadline());
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
        if (order.itemMetaJson() == null) {
          row.add("itemMetaJson", JsonNull.INSTANCE);
        } else {
          row.addProperty("itemMetaJson", order.itemMetaJson());
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
        row.addProperty("refundAmount", order.refundAmount());
        row.addProperty("refundQuantity", order.refundQuantity());
        row.addProperty("earnedQuantity", order.earnedQuantity());
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
          addBusinessDateTime(row, "groupBuyVoucherConsumedAt", order.groupBuyVoucherConsumedAt());
        }
        if (order.claimToken() == null) {
          row.add("claimToken", JsonNull.INSTANCE);
        } else {
          row.addProperty("claimToken", order.claimToken());
        }

        OrderService.RefundEligibility refundEligibility =
            orderService.refundEligibility(user.id(), order.orderNo());
        boolean canRefund = refundEligibility.refundable();
        row.addProperty("canRefund", canRefund);
        row.addProperty("refundableQuantity", refundEligibility.refundableQuantity());
        row.addProperty("expectedRefundAmount", refundEligibility.refundAmount());
        addNullableString(row, "refundReason", refundEligibility.reason());
        if (order.refundDeadline() == null) {
          row.add("refundRemainingSeconds", JsonNull.INSTANCE);
        } else {
          row.addProperty(
              "refundRemainingSeconds",
              Math.max(
                  0L,
                  java.time.Duration.between(
                      LocalDateTime.now(), order.refundDeadline()).toSeconds()));
        }
        row.addProperty("canDiscard", canDiscard(order, now));
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
      response.addProperty("refundAmount", result.refundAmount());
      response.addProperty("refundQuantity", result.refundQuantity());
      response.addProperty("earnedQuantity", result.earnedQuantity());
      response.addProperty("shopCoin", result.balance().shopCoin());
      response.addProperty("gameCoin", result.balance().gameCoin());
      sendJson(exchange, 200, response);
    });
  }

  private void handleOrdersDiscard(HttpExchange exchange) throws IOException {
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
      OrderService.DiscardResult result = orderService.discardOrder(user.id(), orderNo);
      JsonObject response = new JsonObject();
      response.addProperty("orderNo", result.orderNo());
      response.addProperty("status", "CANCELLED");
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

  private void handleMailboxList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 50);
      Long cursor = parseLong(query.get("cursor"));
      JsonArray items = new JsonArray();
      for (MailboxCenterService.MailboxEntry entry : mailboxCenterService.list(user.id(), limit, cursor)) {
        items.add(mailboxEntryJson(entry));
      }
      JsonObject response = new JsonObject();
      response.add("items", items);
      response.addProperty("count", items.size());
      sendJson(exchange, 200, response);
    });
  }

  private void handleMailboxCount(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      JsonObject response = new JsonObject();
      response.addProperty("count", mailboxCenterService.count(user.id()));
      sendJson(exchange, 200, response);
    });
  }

  private void handleMailboxEntry(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      String path = exchange.getRequestURI().getPath();
      String prefix = "/api/mailbox/";
      String refundSuffix = "/refund";
      String claimSuffix = "/claim";
      boolean refund = path.endsWith(refundSuffix);
      boolean claim = path.endsWith(claimSuffix);
      if (!path.startsWith(prefix) || (!refund && !claim)) {
        throw new ServiceException("mailbox_entry_missing", "Mailbox entry was not found");
      }
      String suffix = refund ? refundSuffix : claimSuffix;
      String encodedEntryId = path.substring(prefix.length(), path.length() - suffix.length());
      String entryId = URLDecoder.decode(encodedEntryId, StandardCharsets.UTF_8);
      if (claim) {
        MailboxCenterService.ClaimResult result =
            mailboxCenterService.claim(user.id(), entryId);
        JsonObject response = new JsonObject();
        response.addProperty("entryId", result.entryId());
        response.addProperty("success", result.success());
        response.addProperty("failed", result.failed());
        sendJson(exchange, 200, response);
        return;
      }
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      OrderService.RefundResult result =
          mailboxCenterService.refund(user.id(), entryId, idempotencyKey);
      JsonObject response = new JsonObject();
      response.addProperty("entryId", entryId);
      response.addProperty("orderNo", result.orderNo());
      response.addProperty("refundAmount", result.refundAmount());
      response.addProperty("refundQuantity", result.refundQuantity());
      response.addProperty("earnedQuantity", result.earnedQuantity());
      response.addProperty("shopCoin", result.balance().shopCoin());
      response.addProperty("gameCoin", result.balance().gameCoin());
      sendJson(exchange, 200, response);
    });
  }

  private JsonObject mailboxEntryJson(MailboxCenterService.MailboxEntry entry) {
    JsonObject row = new JsonObject();
    row.addProperty("id", entry.id());
    row.addProperty("type", entry.type());
    row.addProperty("sourceType", entry.sourceType());
    row.addProperty("sourceRef", entry.sourceRef());
    row.addProperty("title", entry.title());
    if (entry.material() == null) {
      row.add("material", JsonNull.INSTANCE);
    } else {
      row.addProperty("material", entry.material());
    }
    row.addProperty("quantity", entry.quantity());
    row.addProperty("deliveredQuantity", entry.deliveredQuantity());
    row.addProperty("refundableQuantity", entry.refundableQuantity());
    row.addProperty("status", entry.status());
    addBusinessDateTime(row, "createdAt", entry.createdAt());
    row.addProperty("collectible", entry.collectible());
    row.addProperty("refundable", entry.refundable());
    if (entry.refundReason() == null) {
      row.add("refundReason", JsonNull.INSTANCE);
    } else {
      row.addProperty("refundReason", entry.refundReason());
    }
    if (entry.refundDeadline() == null) {
      row.add("refundDeadline", JsonNull.INSTANCE);
    } else {
      addBusinessDateTime(row, "refundDeadline", entry.refundDeadline());
    }
    if (entry.reason() == null) {
      row.add("reason", JsonNull.INSTANCE);
    } else {
      row.addProperty("reason", entry.reason());
    }
    row.addProperty("refundAmount", entry.refundAmount());
    addNullableString(row, "refundCurrency", entry.refundCurrency());
    addNullableString(row, "refundPolicy", entry.refundPolicy());
    row.addProperty("partialRefundAllowed", entry.partialRefundAllowed());
    addNullableString(row, "sourceOrderNo", entry.sourceOrderNo());
    addNullableString(row, "sourceRoute", entry.sourceRoute());
    addNullableString(row, "lastDeliveryError", entry.lastDeliveryError());
    row.addProperty("deliveryInProgress", entry.deliveryInProgress());
    row.addProperty("refundInProgress", entry.refundInProgress());
    addNullableString(row, "displayName", entry.displayName());
    addNullableString(row, "iconUrl", entry.iconUrl());
    addNullableString(row, "itemMetaJson", entry.itemMetaJson());
    if (entry.refundRemainingSeconds() == null) {
      row.add("refundRemainingSeconds", JsonNull.INSTANCE);
    } else {
      row.addProperty("refundRemainingSeconds", entry.refundRemainingSeconds());
    }
    return row;
  }

  private void handleOrdersDeliveryStatus(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      Map<String, String> query = parseQuery(exchange);
      String orderNo = query.get("orderNo");
      if (orderNo == null || orderNo.isBlank()) {
        sendJson(exchange, 400, errorJson("bad_request", "Order number is required"));
        return;
      }
      OrderService.DeliveryStatusResponse status = orderService.getDeliveryStatusForOrder(user.id(), orderNo);
      
      JsonObject response = new JsonObject();
      response.addProperty("orderNo", status.orderNo());
      response.addProperty("status", status.status());
      response.addProperty("deliverySource", status.deliverySource());
      response.addProperty("playerOnline", status.playerOnline());
      
      JsonArray array = new JsonArray();
      for (OrderService.DeliveryTaskView task : status.deliveryTasks()) {
        JsonObject row = new JsonObject();
        row.addProperty("id", task.id());
        row.addProperty("status", task.status());
        row.addProperty("retryCount", task.retryCount());
        if (task.lastError() == null) {
          row.add("lastError", JsonNull.INSTANCE);
        } else {
          row.addProperty("lastError", task.lastError());
        }
        if (task.nextRetryAt() == null) {
          row.add("nextRetryAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "nextRetryAt", task.nextRetryAt());
        }
        if (task.deliveredAt() == null) {
          row.add("deliveredAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "deliveredAt", task.deliveredAt());
        }
        if (task.claimedAt() == null) {
          row.add("claimedAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "claimedAt", task.claimedAt());
        }
        if (task.createdAt() == null) {
          row.add("createdAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "createdAt", task.createdAt());
        }
        row.addProperty("quantity", task.quantity());
        row.addProperty("deliveredQuantity", task.deliveredQuantity());
        row.addProperty("remainingQuantity", Math.max(0, task.quantity() - task.deliveredQuantity()));
        row.addProperty("deliveryKind", task.deliveryKind());
        if (task.targetServerId() == null) {
          row.add("targetServerId", JsonNull.INSTANCE);
        } else {
          row.addProperty("targetServerId", task.targetServerId());
        }
        if (task.mailboxStatus() == null) {
          row.add("mailboxStatus", JsonNull.INSTANCE);
        } else {
          row.addProperty("mailboxStatus", task.mailboxStatus());
        }
        row.addProperty("mailboxQuantity", task.mailboxQuantity());
        if (task.mailboxCreatedAt() == null) {
          row.add("mailboxCreatedAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "mailboxCreatedAt", task.mailboxCreatedAt());
        }
        if (task.mailboxClaimedAt() == null) {
          row.add("mailboxClaimedAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "mailboxClaimedAt", task.mailboxClaimedAt());
        }
        if (task.mailboxReason() == null) {
          row.add("mailboxReason", JsonNull.INSTANCE);
        } else {
          row.addProperty("mailboxReason", task.mailboxReason());
        }
        array.add(row);
      }
      response.add("deliveryTasks", array);
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
        addBusinessDateTime(row, "createdAt", notification.createdAt());
        if (notification.readAt() == null) {
          row.add("readAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "readAt", notification.readAt());
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
      response.add("paymentProviders", paymentProviderInfosJson(rechargeService.paymentProviderInfos()));
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
      String requestedLocale = normalizeVisualLocale(parseQuery(exchange).get("locale"));
      JsonObject response = new JsonObject();
      JsonArray overrides = new JsonArray();
      for (VisualPackService.ResolvedVisual visual : visualPackService.resolvedVisuals()) {
        JsonObject row = new JsonObject();
        row.addProperty("materialKey", visual.itemId());
        row.addProperty("itemId", visual.itemId());
        if (visual.iconPath() == null) {
          row.add("iconPath", JsonNull.INSTANCE);
        } else {
          row.addProperty("iconPath", visual.iconPath());
        }
        if (visual.translationKey() == null) {
          row.add("translationKey", JsonNull.INSTANCE);
        } else {
          row.addProperty("translationKey", visual.translationKey());
        }
        JsonObject localizedNames = new JsonObject();
        addLocalizedName(localizedNames, visual.localizedNames(), requestedLocale);
        addLocalizedName(localizedNames, visual.localizedNames(), "en_us");
        row.add("localizedNames", localizedNames);
        row.add("displayNameOverride", JsonNull.INSTANCE);
        row.addProperty("source", "visual-pack");
        row.addProperty("packId", visual.packId());
        overrides.add(row);
      }
      for (JsonElement row : materialOverrideListJson(materialVisualService.listAll())) {
        overrides.add(row);
      }
      response.add("overrides", overrides);
      JsonArray materialLocales = new JsonArray();
      visualPackService.availableTranslationLocales().forEach(materialLocales::add);
      response.add("locales", materialLocales);
      JsonObject languageMetadata = new JsonObject();
      visualPackService.availableLanguageMetadata().forEach((locale, descriptor) -> {
        JsonObject row = new JsonObject();
        row.addProperty("name", descriptor.name());
        row.addProperty("region", descriptor.region());
        row.addProperty("bidirectional", descriptor.bidirectional());
        languageMetadata.add(locale, row);
      });
      response.add("languageMetadata", languageMetadata);
      JsonObject enchantments = new JsonObject();
      visualPackService.resolvedEnchantments(requestedLocale).forEach((id, descriptor) -> {
        JsonObject row = new JsonObject();
        if (descriptor.name() != null) {
          row.addProperty("name", descriptor.name());
        }
        if (descriptor.description() != null) {
          row.addProperty("description", descriptor.description());
        }
        if (descriptor.englishName() != null) {
          row.addProperty("englishName", descriptor.englishName());
        }
        if (descriptor.englishDescription() != null) {
          row.addProperty("englishDescription", descriptor.englishDescription());
        }
        enchantments.add(id, row);
      });
      response.add("enchantments", enchantments);
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
        if (tag.id() == null) {
          row.add("id", JsonNull.INSTANCE);
        } else {
          row.addProperty("id", tag.id());
        }
        row.addProperty("configured", tag.configured());
        row.addProperty("system", tag.system());
        row.addProperty("color", tag.color());
        row.addProperty("description", tag.description());
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
      response.addProperty("maxTagsPerItem", marketService.maxTagsPerItem());
      response.addProperty("playersCanSelectTags", marketService.playersCanSelectTags());
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
    json.addProperty("rechargeOrderExpireMinutes", settings.rechargeOrderExpireMinutes());
    json.addProperty("allowSharedClaimCommand", settings.allowSharedClaimCommand());
    json.addProperty("refundUndeliveredEnabled", settings.refundUndeliveredEnabled());
    json.addProperty("advancedRecycleEnabled", settings.advancedRecycleEnabled());
    json.addProperty("timeZone", settings.timeZone().getId());
    json.addProperty("homeUrl", runtimeConfigService.homeUrl(settings));
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
    PluginSettings.EmbeddedWebSettings web = settings.embeddedWebSettings();
    json.addProperty("webServerMode", settings.serverMode().name());
    json.addProperty("staticWebEnabled", settings.serverMode() == PluginSettings.ServerMode.INTERNAL);
    json.addProperty("listenAddress", web.host() + ":" + web.port());
    json.addProperty("publicUrl", web.publicUrl());
    json.addProperty("publicApiUrl", web.publicApiUrl());
    json.addProperty("publicApiUrlSource", web.publicApiUrlSource());
    json.addProperty("corsEnabled", web.corsEnabled());
    json.addProperty("corsAllowedOriginCount", web.corsAllowedOrigins().size());
    JsonArray corsOrigins = new JsonArray();
    web.corsAllowedOrigins().forEach(corsOrigins::add);
    json.add("corsAllowedOrigins", corsOrigins);
    json.addProperty("restartRequiredForChanges", true);
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
    JsonArray iconPriority = new JsonArray();
    normalized.iconPriority().forEach(iconPriority::add);
    json.add("iconPriority", iconPriority);
    JsonArray namePriority = new JsonArray();
    normalized.namePriority().forEach(namePriority::add);
    json.add("namePriority", namePriority);
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
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/api/market/listings/create")) {
      handleMarketListingsCreate(exchange);
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      Map<String, String> query = parseQuery(exchange);
      int limit = parseInt(query.get("limit"), 100);
      boolean mineOnly = parseBoolean(query.get("mine"));
      boolean leadingOnly = parseBoolean(query.get("leading"));
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
      MarketService.TradeMode tradeMode = null;
      String modeRaw = query.containsKey("mode") ? query.get("mode") : query.get("tradeMode");
      if (modeRaw != null && !modeRaw.isBlank()) {
        tradeMode = MarketService.TradeMode.fromRaw(modeRaw);
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
      Long auctionHighestBidderUserId = null;
      boolean activeOnly = !mineOnly;
      if (mineOnly || leadingOnly) {
        AuthService.AuthUser user = requireAuth(exchange, null);
        if (mineOnly) {
          sellerUserId = user.id();
        }
        if (leadingOnly) {
          auctionHighestBidderUserId = user.id();
          activeOnly = true;
        }
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
          tradeMode,
          tag,
          tags,
          auctionHighestBidderUserId,
          limit);
      List<MarketService.ListingView> listings = marketService.listListings(listingQuery);

      JsonArray rows = new JsonArray();
      for (MarketService.ListingView listing : listings) {
        JsonObject row = new JsonObject();
        row.addProperty("id", listing.id());
        RefundPolicyService.ListingPolicy listingPolicy =
            refundPolicyService.getListingPolicy(listing.id());
        row.addProperty("refundPolicyPreset", listingPolicy.preset());
        if (listingPolicy.windowMinutes() == null) {
          row.add("refundWindowMinutes", JsonNull.INSTANCE);
        } else {
          row.addProperty("refundWindowMinutes", listingPolicy.windowMinutes());
        }
        row.addProperty(
            "orderLevelRefundPolicyEnabled",
            refundPolicyService.getPolicy().orderLevelPolicyEnabled());
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
        JsonArray listingTags = new JsonArray();
        for (String tagCode : marketService.listListingTags(listing.id())) {
          listingTags.add(tagCode);
        }
        row.add("tags", listingTags);
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
        addBusinessDateTime(row, "createdAt", listing.createdAt());
        row.addProperty("sourceMode", listing.sourceMode().name());
        row.addProperty("tradeMode", listing.tradeMode().name());
        row.addProperty("dynamicPricingEnabled", listing.dynamicPricingEnabled());
        row.addProperty("dynamicAlgorithm", listing.dynamicAlgorithm());
        row.addProperty("dynamicPricingMode", listing.dynamicPricingMode());
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
        boolean sealedAuction = "VICKREY_AUCTION_V1".equalsIgnoreCase(listing.auctionAlgorithm())
            && "ACTIVE".equalsIgnoreCase(listing.status());
        if (sealedAuction || listing.auctionHighestBid() == null) {
          row.add("auctionHighestBid", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBid", listing.auctionHighestBid());
        }
        if (sealedAuction || listing.auctionHighestBidderUserId() == null) {
          row.add("auctionHighestBidderUserId", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBidderUserId", listing.auctionHighestBidderUserId());
        }
        if (sealedAuction || listing.auctionHighestBidderUuid() == null) {
          row.add("auctionHighestBidderUuid", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBidderUuid", listing.auctionHighestBidderUuid().toString());
        }
        if (sealedAuction || listing.auctionHighestBidderName() == null) {
          row.add("auctionHighestBidderName", JsonNull.INSTANCE);
        } else {
          row.addProperty("auctionHighestBidderName", listing.auctionHighestBidderName());
        }
        row.addProperty("auctionBidCount", listing.auctionBidCount());
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
        row.addProperty("supplyAccessProtected", listing.supplyAccessProtected());
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
          addBusinessDateTime(row, "supplyLastLoadedAt", listing.supplyLastLoadedAt());
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
      List<String> requestedTags = getStringList(payload, "tags");
      if (requestedTags.isEmpty() && tag != null) {
        requestedTags = List.of(tag);
      }
      final List<String> selectedTags = requestedTags;
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
            selectedTags);
      } else {
        if (!tradeMode.equals("DIRECT")) {
          throw new ServiceException("invalid_trade_mode", "SELL listing creation currently supports DIRECT only");
        }
        result = awaitPlayerTask(
            user.boundUuid(),
            player -> marketService.createListingFromPlayer(player, price, quantity, currency, selectedTags));
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

  private void handleMarketPriceTrend(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      Map<String, String> query = parseQuery(exchange);
      Long listingIdRaw = parseLong(query.get("listingId"));
      if (listingIdRaw == null || listingIdRaw <= 0L) {
        throw new ServiceException("bad_request", "Missing field: listingId");
      }
      long listingId = listingIdRaw;
      int limit = parseInt(query.get("limit"), 30);
      List<MarketService.PriceTrendPoint> points = marketService.listPriceTrend(listingId, limit);
      JsonArray history = new JsonArray();
      for (MarketService.PriceTrendPoint point : points) {
        JsonObject row = new JsonObject();
        row.addProperty("tradeId", point.tradeId());
        row.addProperty("price", point.price());
        row.addProperty("quantity", point.quantity());
        addBusinessDateTime(row, "createdAt", point.createdAt());
        history.add(row);
      }
      JsonObject response = new JsonObject();
      response.addProperty("listingId", listingId);
      response.add("history", history);
      sendJson(exchange, 200, response);
    });
  }

  private void handleMarketQuote(HttpExchange exchange) throws IOException {
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
      int quantity = payload.has("buyQuantity")
          ? (int) getLong(payload, "buyQuantity", 1L)
          : payload.has("sellQuantity")
              ? (int) getLong(payload, "sellQuantity", 1L)
              : (int) getLong(payload, "quantity", 1L);
      MarketService.PurchaseQuote quote = marketService.quotePurchase(user.id(), listingId, quantity);
      sendJson(exchange, 200, toPurchaseQuoteJson(quote));
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
      Long expectedUnitPrice = getOptionalPositiveLong(payload, "expectedUnitPrice");
      Long expectedBuyerTotal = getOptionalPositiveLong(payload, "expectedBuyerTotal");
      MarketService.TradeResult result =
          marketService.buyListing(
              user.id(),
              listingId,
              buyQuantity,
              idempotencyKey,
              deliveryMode,
              expectedUnitPrice,
              expectedBuyerTotal);
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
      Long expectedUnitPrice = getOptionalPositiveLong(payload, "expectedUnitPrice");
      Long expectedBuyerTotal = getOptionalPositiveLong(payload, "expectedBuyerTotal");
      MarketService.TradeResult result = marketService.fulfillBuyOrder(
          user.id(),
          listingId,
          sellQuantity,
          idempotencyKey,
          deliveryMode,
          expectedUnitPrice,
          expectedBuyerTotal);
      sendJson(exchange, 200, toTradeResultJson(result));
    });
  }

  private JsonObject toPurchaseQuoteJson(MarketService.PurchaseQuote quote) {
    JsonObject response = new JsonObject();
    response.addProperty("listingId", quote.listingId());
    response.addProperty("currency", quote.currency().name());
    response.addProperty("side", quote.side().name());
    response.addProperty("unitPrice", quote.unitPrice());
    response.addProperty("firstUnitPrice", quote.firstUnitPrice());
    response.addProperty("lastUnitPrice", quote.lastUnitPrice());
    response.addProperty("averageUnitPrice", quote.averageUnitPrice());
    response.addProperty("quantity", quote.quantity());
    response.addProperty("totalPrice", quote.totalPrice());
    response.addProperty("buyerTotal", quote.buyerTotal());
    response.addProperty("sellerReceive", quote.sellerReceive());
    response.addProperty("feeAmount", quote.feeAmount());
    response.addProperty("taxAmount", quote.taxAmount());
    response.addProperty("dynamicPricingEnabled", quote.dynamicPricingEnabled());
    response.addProperty("dynamicPricingMode", quote.dynamicPricingMode().name());
    response.addProperty("currentDemandScore", quote.currentDemandScore());
    response.addProperty("nextDemandScore", quote.nextDemandScore());
    response.addProperty("nextUnitPrice", quote.nextUnitPrice());
    return response;
  }

  private JsonObject productPriceQuoteJson(ProductService.ProductPriceQuote quote) {
    JsonObject response = new JsonObject();
    response.addProperty("dynamicPricingMode", quote.pricingMode().name());
    response.addProperty("firstUnitPrice", quote.firstUnitPrice());
    response.addProperty("lastUnitPrice", quote.lastUnitPrice());
    response.addProperty("averageUnitPrice", quote.averageUnitPrice());
    response.addProperty("unitPrice", quote.averageUnitPrice());
    response.addProperty("nextUnitPrice", quote.nextUnitPrice());
    response.addProperty("quantity", quote.quantity());
    response.addProperty("totalAmount", quote.totalAmount());
    response.addProperty("totalPrice", quote.totalAmount());
    response.addProperty("currentDemandScore", quote.currentDemandScore());
    response.addProperty("nextDemandScore", quote.nextDemandScore());
    return response;
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
      addBusinessDateTime(response, "refundDeadline", result.refundDeadline());
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

  private void handleMarketAuctionInsights(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      Long parsedListingId = parseLong(parseQuery(exchange).get("listingId"));
      long listingId = parsedListingId == null ? -1L : parsedListingId;
      Long viewerUserId = findOptionalAuth(exchange).map(AuthService.AuthUser::id).orElse(null);
      MarketService.AuctionInsights insights = marketService.getAuctionInsights(listingId, viewerUserId);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", insights.listingId());
      response.addProperty("algorithm", insights.algorithm());
      response.addProperty("bidCount", insights.bidCount());
      response.addProperty("participantCount", insights.participantCount());
      response.addProperty("sealed", insights.sealed());
      if (insights.myBid() == null) {
        response.add("myBid", JsonNull.INSTANCE);
      } else {
        response.addProperty("myBid", insights.myBid());
      }
      response.addProperty("myStatus", insights.myStatus());
      JsonArray points = new JsonArray();
      for (MarketService.AuctionInsightPoint point : insights.pricePoints()) {
        JsonObject row = new JsonObject();
        row.addProperty("amount", point.amount());
        row.addProperty("bidderName", point.bidderName());
        addBusinessDateTime(row, "createdAt", point.createdAt());
        points.add(row);
      }
      response.add("pricePoints", points);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAuctionDisplaySettings(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () ->
        sendJson(exchange, 200, runtimeConfigService.readAuctionDisplayConfig().config()));
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
      List<String> requestedTags = getStringList(payload, "tags");
      if (requestedTags.isEmpty() && tag != null) {
        requestedTags = List.of(tag);
      }
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
      Boolean supplyAccessProtected = payload.has("supplyAccessProtected")
          && !payload.get("supplyAccessProtected").isJsonNull()
          ? payload.get("supplyAccessProtected").getAsBoolean()
          : null;
        String tradeMode = getOptionalString(payload, "tradeMode").orElse(null);
        Boolean dynamicPricingEnabled = payload.has("dynamicPricingEnabled")
          && !payload.get("dynamicPricingEnabled").isJsonNull()
          ? payload.get("dynamicPricingEnabled").getAsBoolean()
          : null;
        String dynamicAlgorithm = getOptionalString(payload, "dynamicAlgorithm").orElse(null);
        String dynamicPricingMode = getOptionalString(payload, "dynamicPricingMode").orElse(null);
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
          requestedTags,
          remark,
          displayNameOverride,
          displayMaterial,
          displayIconPath,
          supplyBatchSize,
          supplyMaxStock,
          supplyAccessProtected,
          tradeMode,
          dynamicPricingEnabled,
          dynamicAlgorithm,
          dynamicPricingMode,
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
      if (payload.has("refundPolicyPreset")) {
        refundPolicyService.updateListingPolicy(
            user.id(),
            listingId,
            getOptionalString(payload, "refundPolicyPreset").orElse("UNCLAIMED"),
            nullableInteger(payload, "refundWindowMinutes"));
      }
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
      response.addProperty("supplyAccessProtected", result.supplyAccessProtected());
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

  private void handleAdminOverviewStats(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, null);
      JsonObject response = databaseManager.withConnection(connection -> {
        JsonObject stats = new JsonObject();
        stats.addProperty("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
        stats.addProperty("maxPlayers", plugin.getServer().getMaxPlayers());
        stats.addProperty("totalUsers", queryLong(connection, "SELECT COUNT(*) FROM web_users"));
        stats.addProperty(
            "boundUsers",
            queryLong(connection, "SELECT COUNT(*) FROM web_users WHERE bound_uuid IS NOT NULL"));
        stats.addProperty("totalProducts", queryLong(connection, "SELECT COUNT(*) FROM products"));
        stats.addProperty(
            "activeProducts",
            queryLong(connection, "SELECT COUNT(*) FROM products WHERE active = TRUE"));
        stats.addProperty("totalOrders", queryLong(connection, "SELECT COUNT(*) FROM orders"));
        stats.addProperty(
            "completedOrders",
            queryLong(
                connection,
                "SELECT COUNT(*) FROM orders WHERE UPPER(status) IN ('ISSUED', 'COMPLETED', 'DELIVERED')"));
        stats.addProperty(
            "refundedOrders",
            queryLong(connection, "SELECT COUNT(*) FROM orders WHERE UPPER(status) = 'REFUNDED'"));
        stats.addProperty(
            "totalRevenue",
            queryLong(
                connection,
                "SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE UPPER(status) <> 'REFUNDED'"));
        stats.addProperty(
            "activeListings",
            queryLong(connection, "SELECT COUNT(*) FROM market_listings WHERE UPPER(status) = 'ACTIVE'"));
        stats.addProperty("totalTrades", queryLong(connection, "SELECT COUNT(*) FROM market_trades"));
        stats.addProperty(
            "completedTrades",
            queryLong(
                connection,
                "SELECT COUNT(*) FROM market_trades WHERE UPPER(status) IN ('PENDING', 'WAIT_CLAIM', 'ISSUED', 'COMPLETED', 'DELIVERED')"));
        stats.addProperty(
            "totalTradeVolume",
            queryLong(
                connection,
                "SELECT COALESCE(SUM(total_price), 0) FROM market_trades WHERE UPPER(status) <> 'REFUNDED'"));
        stats.addProperty("totalRedeemCodes", queryLong(connection, "SELECT COUNT(*) FROM redeem_codes"));
        stats.addProperty("totalRedeemUses", queryLong(connection, "SELECT COALESCE(SUM(used_count), 0) FROM redeem_codes"));
        return stats;
      });
      sendJson(exchange, 200, response);
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

  private void handleInventorySnapshot(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      InventoryService.InventorySource source = InventoryService.InventorySource.parse(
          parseQuery(exchange).get("inventory"));
      JsonObject response;
      try {
        response = awaitPlayerTask(
            user.boundUuid(),
            player -> inventorySnapshotJson(player, source));
      } catch (ServiceException exception) {
        if (!"player_offline".equals(exception.code())) {
          throw exception;
        }
        response = offlineInventorySnapshotJson(user, source);
      }
      sendJson(exchange, 200, response);
    });
  }

  private void handleInventoryList(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      int slot = (int) getLong(payload, "slot", -1L);
      int quantity = (int) getLong(payload, "quantity", 1L);
      String fingerprint = getString(payload, "fingerprint");
      String revision = getString(payload, "revision");
      long price = getLong(payload, "price", 0L);
      CurrencyType currency = CurrencyType.fromConfig(
          getOptionalString(payload, "currency").orElse("SHOP_COIN"));
      List<String> tags = getStringList(payload, "tags");
      String action = getOptionalString(payload, "action").orElse("LIST").toUpperCase(Locale.ROOT);
      boolean auction = "AUCTION".equals(action);
      String idempotencyKey = getString(payload, "idempotencyKey");
      InventoryService.InventorySource source = inventorySource(payload);
      InventoryOperationService.Existing existing = inventoryOperationService.find(user.id(), idempotencyKey);
      if (existing != null) {
        sendJson(exchange, 200, inventoryExistingJson(existing));
        return;
      }
      inventoryOperationService.begin(
          user.id(), idempotencyKey, action, slot,
          payload.has("containerSlot") && !payload.get("containerSlot").isJsonNull()
              ? payload.get("containerSlot").getAsInt() : null,
          fingerprint, quantity);
      MarketService.ListingCreateResult result;
      try {
        try {
          result = awaitPlayerTask(user.boundUuid(), player -> {
            Integer containerSlot = payload.has("containerSlot")
                    && !payload.get("containerSlot").isJsonNull()
                ? payload.get("containerSlot").getAsInt() : null;
            InventoryService.Withdrawal withdrawal = inventoryService.withdraw(
                playerInventory(player, source), source, revision, slot, containerSlot,
                fingerprint, quantity);
            try {
              return marketService.createConfiguredListingFromStack(
                  player, withdrawal.item(), price, currency, tags,
                  inventoryListingConfig(payload, auction), idempotencyKey);
            } catch (RuntimeException exception) {
              withdrawal.restore().run();
              throw exception;
            }
          });
        } catch (ServiceException exception) {
          if (!"player_offline".equals(exception.code())) {
            throw exception;
          }
          if (payload.has("containerSlot") && !payload.get("containerSlot").isJsonNull()) {
            throw new ServiceException(
                "unsupported_offline_item", "Nested container listing is not supported while offline");
          }
          offlineInventoryFeatureService.requireWriteEnabled();
          try (PlayerDataInventoryService.OfflineWithdrawal withdrawal =
                   playerDataInventoryService.withdraw(
                       user.boundUuid(), source, revision, slot, fingerprint, quantity,
                       user.id(), idempotencyKey)) {
            try {
              result = marketService.createConfiguredListingFromStack(
                  user.boundUuid(), user.username(), withdrawal.item(), price, currency, tags,
                  inventoryListingConfig(payload, auction), idempotencyKey);
              withdrawal.commit();
            } catch (RuntimeException marketFailure) {
              withdrawal.rollback();
              throw marketFailure;
            }
          }
        }
      } catch (RuntimeException exception) {
        inventoryOperationService.reject(user.id(), idempotencyKey, serviceErrorCode(exception));
        throw exception;
      }
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("state", "SUCCESS");
      response.addProperty("revision", "refresh-required");
      inventoryOperationService.complete(user.id(), idempotencyKey, result.listingId(), gson.toJson(response));
      sendJson(exchange, 200, response);
    });
  }

  private MarketService.InventoryListingConfig inventoryListingConfig(
      JsonObject payload, boolean auction) {
    JsonObject dynamicParams = payload.has("dynamicParams")
            && payload.get("dynamicParams").isJsonObject()
        ? payload.getAsJsonObject("dynamicParams") : new JsonObject();
    JsonObject auctionParams = payload.has("auctionParams")
            && payload.get("auctionParams").isJsonObject()
        ? payload.getAsJsonObject("auctionParams") : new JsonObject();
    return new MarketService.InventoryListingConfig(
        auction ? "AUCTION" : "DIRECT",
        !auction && payload.has("dynamicPricingEnabled")
            && payload.get("dynamicPricingEnabled").getAsBoolean(),
        getOptionalString(payload, "dynamicAlgorithm").orElse(null),
        getOptionalString(payload, "dynamicPricingMode").orElse(null),
        gson.toJson(dynamicParams),
        getOptionalPositiveLong(payload, "dynamicBasePrice"),
        getOptionalPositiveLong(payload, "dynamicFloorPrice"),
        getOptionalPositiveLong(payload, "dynamicCapPrice"),
        getOptionalPositiveLong(payload, "dynamicPriceStep"),
        getOptionalString(payload, "auctionAlgorithm").orElse(null),
        gson.toJson(auctionParams),
        getOptionalPositiveLong(payload, "auctionStartPrice"),
        getOptionalPositiveLong(payload, "auctionMinIncrement"),
        getOptionalDateTime(payload, "auctionEndAt"),
        getOptionalString(payload, "remark").orElse(null),
        getOptionalString(payload, "displayName").orElse(null),
        getOptionalString(payload, "displayMaterial").orElse(null));
  }

  private void handleInventoryMatches(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) return;
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      int slot = (int) getLong(payload, "slot", -1L);
      int quantity = (int) getLong(payload, "quantity", 1L);
      String fingerprint = getString(payload, "fingerprint");
      String revision = getString(payload, "revision");
      InventoryService.InventorySource source = inventorySource(payload);
      Integer containerSlot = payload.has("containerSlot") && !payload.get("containerSlot").isJsonNull()
          ? payload.get("containerSlot").getAsInt() : null;
      ItemStack item = awaitPlayerTask(user.boundUuid(), player ->
          inventoryService.resolve(
              playerInventory(player, source), source, revision, slot, containerSlot, fingerprint));
      JsonArray matches = new JsonArray();
      if (!item.hasItemMeta()) {
        for (ProductService.ProductView product : productService.listActiveProductsForUser(user.id())) {
          if (product.productType() != ProductService.ProductType.RECYCLE_ITEM
              || !item.getType().name().equalsIgnoreCase(product.itemMaterial())) continue;
          ProductService.ProductPriceQuote quote;
          try {
            quote = productService.quoteOrderPrice(product, quantity);
          } catch (ServiceException ignored) {
            continue;
          }
          JsonObject row = new JsonObject();
          row.addProperty("id", "official:" + product.id());
          row.addProperty("source", "OFFICIAL_SHOP");
          row.addProperty("sourceName", product.title());
          row.addProperty("remaining", product.personalLimitRemaining() == null
              ? 2147483647 : product.personalLimitRemaining());
          row.addProperty("currency", product.currency().name());
          row.addProperty("unitPrice", quote.averageUnitPrice());
          row.addProperty("sellerReceive", quote.totalAmount());
          row.addProperty("fee", 0);
          row.addProperty("quotedQuantity", quantity);
          matches.add(row);
        }
      }
      for (MarketService.BuyOrderMatch match : marketService.listMatchingBuyOrders(user.id(), item, quantity)) {
        JsonObject row = new JsonObject();
        row.addProperty("id", String.valueOf(match.listingId()));
        row.addProperty("source", "PLAYER_BUY_ORDER");
        row.addProperty("sourceName", match.buyerName());
        row.addProperty("remaining", match.remaining());
        row.addProperty("currency", match.currency().name());
        row.addProperty("unitPrice", match.unitPrice());
        row.addProperty("sellerReceive", match.sellerReceive());
        row.addProperty("fee", match.feeAmount());
        row.addProperty("quotedQuantity", match.quotedQuantity());
        matches.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("matches", matches);
      sendJson(exchange, 200, response);
    });
  }

  private void handleInventoryFulfill(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) return;
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      int slot = (int) getLong(payload, "slot", -1L);
      int quantity = (int) getLong(payload, "quantity", 1L);
      String targetId = getString(payload, "listingId");
      String fingerprint = getString(payload, "fingerprint");
      String revision = getString(payload, "revision");
      String idempotencyKey = getString(payload, "idempotencyKey");
      Long expectedUnitPrice = getOptionalPositiveLong(payload, "expectedUnitPrice");
      Long expectedBuyerTotal = getOptionalPositiveLong(payload, "expectedBuyerTotal");
      Integer containerSlot = payload.has("containerSlot") && !payload.get("containerSlot").isJsonNull()
          ? payload.get("containerSlot").getAsInt() : null;
      InventoryService.InventorySource source = inventorySource(payload);
      InventoryOperationService.Existing existing = inventoryOperationService.find(user.id(), idempotencyKey);
      if (existing != null) {
        sendJson(exchange, 200, inventoryExistingJson(existing));
        return;
      }
      inventoryOperationService.begin(
          user.id(), idempotencyKey, "FULFILL", slot, containerSlot, fingerprint, quantity);
      JsonObject response;
      long referenceId;
      try {
        response = awaitPlayerTask(user.boundUuid(), player -> {
        InventoryService.Withdrawal withdrawal = inventoryService.withdraw(
            playerInventory(player, source), source, revision, slot, containerSlot,
            fingerprint, quantity);
        try {
          JsonObject resultJson = new JsonObject();
          if (targetId.startsWith("official:")) {
            long productId = Long.parseLong(targetId.substring("official:".length()));
            OrderService.OrderPlacementResult result = orderService.placeRecycleOrderFromStack(
                user.id(), productId, withdrawal.item(), quantity, idempotencyKey);
            resultJson.addProperty("state", "SUCCESS");
            resultJson.addProperty("orderNo", result.orderNo());
            resultJson.addProperty("totalAmount", result.totalAmount());
          } else {
            long listingId = Long.parseLong(targetId);
            MarketService.TradeResult result = marketService.fulfillBuyOrderFromStack(
                user.id(), listingId, withdrawal.item(), quantity, idempotencyKey,
                expectedUnitPrice, expectedBuyerTotal);
            resultJson = toTradeResultJson(result);
            resultJson.addProperty("state", "SUCCESS");
          }
          return resultJson;
        } catch (RuntimeException exception) {
          withdrawal.restore().run();
          throw exception;
        }
        });
        referenceId = targetId.startsWith("official:")
            ? Long.parseLong(targetId.substring("official:".length()))
            : Long.parseLong(targetId);
      } catch (RuntimeException exception) {
        inventoryOperationService.reject(user.id(), idempotencyKey, serviceErrorCode(exception));
        throw exception;
      }
      inventoryOperationService.complete(user.id(), idempotencyKey, referenceId, gson.toJson(response));
      sendJson(exchange, 200, response);
    });
  }

  private JsonObject inventorySnapshotJson(
      Player player,
      InventoryService.InventorySource source) {
    InventoryService.Snapshot snapshot =
        inventoryService.snapshot(playerInventory(player, source), source);
    UUID playerUuid = player.getUniqueId();
    schedulerBridge.runAsync(() -> {
      try {
        inventoryReadSnapshotService.save(playerUuid, source, snapshot);
      } catch (Exception exception) {
        plugin.getLogger().warning(
            "Could not update inventory read snapshot: " + exception.getMessage());
      }
    });
    return inventorySnapshotJson(snapshot, true, source);
  }

  private JsonObject inventorySnapshotJson(
      InventoryService.Snapshot snapshot,
      boolean online,
      InventoryService.InventorySource source) {
    JsonObject response = inventorySnapshotJsonCodec.encode(snapshot);
    response.addProperty("online", online);
    response.addProperty("readOnly", !online);
    response.addProperty("snapshotSource", online ? "LIVE" : "NONE");
    response.addProperty("inventory", source.name());
    response.addProperty("revision", snapshot.revision());
    String now = java.time.Instant.now().toString();
    response.addProperty("refreshedAt", now);
    response.addProperty("capturedAt", now);
    response.addProperty("offlineWriteEnabled", false);
    response.addProperty("offlineOfficialShopCaptureEnabled", false);
    response.addProperty("offlineOfficialShopCaptureAllowed", false);
    return response;
  }

  private JsonObject offlineInventorySnapshotJson(
      AuthService.AuthUser user,
      InventoryService.InventorySource source) {
    OfflineInventoryFeatureService.State feature = offlineInventoryFeatureService.state();
    boolean officialCaptureAllowed = false;
    try {
      AdminService.AdminUser admin =
          adminService.requireAdmin(user, AdminPermission.PRODUCT_MANAGE);
      officialCaptureAllowed =
          admin.allows(AdminPermission.PRODUCT_OFFLINE_INVENTORY_IMPORT);
    } catch (ServiceException ignored) {
      // A normal user may still use the separately controlled offline-write feature.
    }
    boolean officialCaptureAvailable =
        feature.officialShopCaptureEnabled() && officialCaptureAllowed;
    if (feature.enabled() || officialCaptureAvailable) {
      InventoryService.Snapshot snapshot =
          playerDataInventoryService.read(user.boundUuid(), source);
      JsonObject response = inventorySnapshotJson(snapshot, false, source);
      response.addProperty("readOnly", !feature.enabled());
      response.addProperty("snapshotSource", "PLAYERDATA");
      response.addProperty("offlineWriteEnabled", feature.enabled());
      response.addProperty(
          "offlineOfficialShopCaptureEnabled", feature.officialShopCaptureEnabled());
      response.addProperty("offlineOfficialShopCaptureAllowed", officialCaptureAllowed);
      return response;
    }
    Optional<InventoryReadSnapshotService.StoredSnapshot> stored =
        inventoryReadSnapshotService.find(user.boundUuid(), source);
    if (stored.isEmpty()) {
      return inventorySnapshotJson(inventoryService.offlineSnapshot(source), false, source);
    }
    try {
      InventoryReadSnapshotService.StoredSnapshot value = stored.get();
      JsonObject response = JsonParser.parseString(value.snapshotJson()).getAsJsonObject();
      response.addProperty("online", false);
      response.addProperty("readOnly", true);
      response.addProperty("snapshotSource", "LAST_ONLINE");
      response.addProperty("inventory", source.name());
      response.addProperty("capturedAt", value.capturedAt().toString());
      response.addProperty("refreshedAt", value.capturedAt().toString());
      response.addProperty("offlineWriteEnabled", false);
      response.addProperty(
          "offlineOfficialShopCaptureEnabled", feature.officialShopCaptureEnabled());
      response.addProperty("offlineOfficialShopCaptureAllowed", officialCaptureAllowed);
      return response;
    } catch (Exception exception) {
      plugin.getLogger().warning(
          "Could not read inventory snapshot for " + user.boundUuid() + ": " + exception.getMessage());
      return inventorySnapshotJson(inventoryService.offlineSnapshot(source), false, source);
    }
  }

  private InventoryService.InventorySource inventorySource(JsonObject payload) {
    return InventoryService.InventorySource.parse(
        getOptionalString(payload, "inventory").orElse("PLAYER"));
  }

  private org.bukkit.inventory.Inventory playerInventory(
      Player player,
      InventoryService.InventorySource source) {
    return source == InventoryService.InventorySource.ENDER_CHEST
        ? player.getEnderChest()
        : player.getInventory();
  }

  private JsonObject inventoryExistingJson(InventoryOperationService.Existing existing) {
    if ("SUCCESS".equals(existing.state()) && existing.resultJson() != null) {
      return JsonParser.parseString(existing.resultJson()).getAsJsonObject();
    }
    if ("SUCCESS".equals(existing.state())) {
      JsonObject response = new JsonObject();
      response.addProperty("state", "SUCCESS");
      response.addProperty("idempotentReplay", true);
      response.addProperty("referenceId", existing.referenceId());
      return response;
    }
    if ("PENDING".equals(existing.state())) {
      throw new ServiceException("operation_pending", "The same inventory operation is still pending");
    }
    throw new ServiceException(
        existing.errorCode() == null ? "operation_rejected" : existing.errorCode(),
        "The same inventory operation was already rejected");
  }

  private String serviceErrorCode(RuntimeException exception) {
    if (exception instanceof ServiceException serviceException) {
      return serviceException.code();
    }
    Throwable cause = exception.getCause();
    if (cause instanceof ServiceException serviceException) return serviceException.code();
    return "internal_error";
  }

  private void handlePublicHomepage(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    sendJson(exchange, 200, homepageService.publicDocument());
  }

  private void handlePublicHomepageStatus(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) return;
    if (!ensureMethod(exchange, "GET")) return;
    JsonObject response = new JsonObject();
    response.addProperty("online", true);
    response.addProperty("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
    response.addProperty("maxPlayers", plugin.getServer().getMaxPlayers());
    response.addProperty("minecraftVersion", plugin.getServer().getMinecraftVersion());
    if (plugin.getServer().getOnlinePlayers().isEmpty()) {
      response.add("averagePlayerPing", JsonNull.INSTANCE);
    } else {
      double averagePing = plugin.getServer().getOnlinePlayers().stream()
          .mapToInt(Player::getPing)
          .average()
          .orElse(0.0D);
      response.addProperty("averagePlayerPing", Math.round(averagePing));
    }
    sendJson(exchange, 200, response);
  }

  private void handleAdminHomepageDraft(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.HOMEPAGE_MANAGE);
        sendJson(exchange, 200, homepageService.draftState());
      });
      return;
    }
    if (!ensureMethod(exchange, "PUT")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.HOMEPAGE_MANAGE);
      JsonObject document = payload.has("document") && payload.get("document").isJsonObject()
          ? payload.getAsJsonObject("document") : payload;
      JsonObject response = homepageService.saveDraft(document, admin.username());
      adminAuditService.log(admin, "HOMEPAGE_DRAFT_SAVE", "homepage", response.get("id").getAsString(),
          new JsonObject(), clientIp(exchange));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminHomepagePublish(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) return;
    if (!ensureMethod(exchange, "POST")) return;
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.HOMEPAGE_MANAGE);
      JsonObject response = homepageService.publish(admin.username());
      adminAuditService.log(admin, "HOMEPAGE_PUBLISH", "homepage", response.get("id").getAsString(),
          new JsonObject(), clientIp(exchange));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminHomepageRevisions(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) return;
    if (!ensureMethod(exchange, "GET")) return;
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, AdminPermission.HOMEPAGE_MANAGE);
      sendJson(exchange, 200, homepageService.revisions());
    });
  }

  private void handleAdminHomepageRestore(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) return;
    if (!ensureMethod(exchange, "POST")) return;
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.HOMEPAGE_MANAGE);
      JsonObject response = homepageService.restore(getString(payload, "revisionId"), admin.username());
      adminAuditService.log(admin, "HOMEPAGE_RESTORE", "homepage", getString(payload, "revisionId"),
          new JsonObject(), clientIp(exchange));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminHomepageAssets(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.HOMEPAGE_MANAGE);
        sendJson(exchange, 200, homepageService.assets());
      });
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin = requireAdmin(exchange, null, AdminPermission.HOMEPAGE_MANAGE);
      Map<String, String> query = parseQuery(exchange);
      String extension = resolveIconUploadExtension(query.get("filename"), null,
          exchange.getRequestHeaders().getFirst("Content-Type"));
      byte[] content = readRequestBodyWithLimit(exchange, HOME_ASSET_MAX_UPLOAD_BYTES);
      if (content.length == 0) {
        throw new ServiceException("bad_request", "Empty file content");
      }
      if (!isHomepageImage(content, extension)) {
        throw new ServiceException("bad_request", "File content does not match a supported image format");
      }
      Path root = resolveHomepageAssetRoot();
      String fileName = "home-" + System.currentTimeMillis() + "-"
          + UUID.randomUUID().toString().substring(0, 8) + "." + extension;
      Path output = root.resolve(fileName).normalize();
      if (!output.startsWith(root)) {
        throw new ServiceException("bad_request", "Invalid upload target");
      }
      Files.write(output, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      String sha256;
      try {
        sha256 = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      } catch (java.security.NoSuchAlgorithmException exception) {
        throw new IllegalStateException("SHA-256 is unavailable", exception);
      }
      JsonObject detail = new JsonObject();
      detail.addProperty("fileName", fileName);
      adminAuditService.log(admin, "HOMEPAGE_ASSET_UPLOAD", "home_asset", fileName,
          detail, clientIp(exchange));
      String originalName = query.getOrDefault("filename", fileName);
      String mimeType = exchange.getRequestHeaders().getFirst("Content-Type");
      JsonObject response = homepageService.recordAsset(fileName, originalName,
          mimeType == null ? contentType(output) : mimeType, content.length, sha256, admin.username());
      sendJson(exchange, 200, response);
    });
  }

  private void handleHomepageAsset(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    String prefix = "/home-assets/";
    String relative = exchange.getRequestURI().getPath().substring(prefix.length());
    if (relative.isBlank() || relative.contains("..") || relative.contains("/") || relative.contains("\\")) {
      sendJson(exchange, 400, errorJson("bad_request", "Invalid asset path"));
      return;
    }
    Path root = resolveHomepageAssetRoot();
    Path file = root.resolve(relative).normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file)) {
      sendJson(exchange, 404, errorJson("not_found", "Asset not found"));
      return;
    }
    byte[] content = Files.readAllBytes(file);
    exchange.getResponseHeaders().set("Content-Type", contentType(file));
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
    applyCorsHeaders(exchange);
    exchange.sendResponseHeaders(200, content.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(content);
    }
  }

  private Path resolveHomepageAssetRoot() throws IOException {
    Path root = plugin.getDataFolder().toPath().resolve("home-assets").toAbsolutePath().normalize();
    Files.createDirectories(root);
    return root;
  }

  private boolean isHomepageImage(byte[] content, String extension) {
    if (content == null || content.length < 12) {
      return false;
    }
    return switch (extension) {
      case "png" -> content[0] == (byte) 0x89 && content[1] == 0x50
          && content[2] == 0x4e && content[3] == 0x47;
      case "jpg", "jpeg" -> content[0] == (byte) 0xff && content[1] == (byte) 0xd8;
      case "gif" -> content[0] == 0x47 && content[1] == 0x49 && content[2] == 0x46;
      case "webp" -> content[0] == 0x52 && content[1] == 0x49 && content[2] == 0x46
          && content[3] == 0x46 && content[8] == 0x57 && content[9] == 0x45
          && content[10] == 0x42 && content[11] == 0x50;
      default -> false;
    };
  }

  private void handleMetaVersion(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> sendJson(exchange, 200, pluginUpdateService.publicState()));
  }

  private void handleAdminPluginUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    String method = exchange.getRequestMethod();
    if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("POST")) {
      sendJson(exchange, 405, errorJson("method_not_allowed", "Use GET or POST"));
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, null);
      sendJson(exchange, 200, pluginUpdateService.getUpdateState(method.equalsIgnoreCase("POST")));
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
          addBusinessDateTime(row, "expiresAt", code.expiresAt());
        }
        if (code.createdAt() == null) {
          row.add("createdAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "createdAt", code.createdAt());
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

  private void handleAdminRefundPolicy(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.PRODUCT_MANAGE);
        sendJson(exchange, 200, refundPolicyJson(refundPolicyService.getPolicy()));
      });
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin =
          requireAdmin(exchange, payload, AdminPermission.PRODUCT_MANAGE);
      RefundPolicyService.Policy policy = refundPolicyService.updatePolicy(
          new RefundPolicyService.Policy(
              getBoolean(payload, "selfServiceEnabled"),
              getBoolean(payload, "mailboxPendingRefundEnabled"),
              nullableInteger(payload, "fixedPriceWindowMinutes"),
              nullableInteger(payload, "dynamicPriceWindowMinutes"),
              getBoolean(payload, "partialRefundEnabled"),
              (int) getLong(payload, "maxSelfServiceRefundsPerDay", 5L),
              getBoolean(payload, "orderLevelPolicyEnabled")));
      sendJson(exchange, 200, refundPolicyJson(policy));
      adminAuditService.log(
          admin, "REFUND_POLICY_UPDATE", "refund_policy", null,
          refundPolicyJson(policy), clientIp(exchange));
    });
  }

  private void handleAdminProductRefundPolicy(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin =
          requireAdmin(exchange, payload, AdminPermission.PRODUCT_MANAGE);
      RefundPolicyService.ProductPolicy policy = refundPolicyService.updateProductPolicy(
          getLong(payload, "productId", -1L),
          getOptionalString(payload, "refundPolicy").orElse("INHERIT"),
          nullableInteger(payload, "refundWindowMinutes"),
          getOptionalString(payload, "partialRefundPolicy").orElse("INHERIT"));
      JsonObject response = new JsonObject();
      response.addProperty("productId", policy.productId());
      response.addProperty("refundPolicy", policy.refundPolicy());
      if (policy.windowMinutes() == null) {
        response.add("refundWindowMinutes", JsonNull.INSTANCE);
      } else {
        response.addProperty("refundWindowMinutes", policy.windowMinutes());
      }
      response.addProperty("partialRefundPolicy", policy.partialPolicy());
      sendJson(exchange, 200, response);
      adminAuditService.log(
          admin, "PRODUCT_REFUND_POLICY_UPDATE", "product",
          Long.toString(policy.productId()), response, clientIp(exchange));
    });
  }

  private JsonObject refundPolicyJson(RefundPolicyService.Policy policy) {
    JsonObject response = new JsonObject();
    response.addProperty("selfServiceEnabled", policy.selfServiceEnabled());
    response.addProperty(
        "mailboxPendingRefundEnabled", policy.mailboxPendingRefundEnabled());
    if (policy.fixedPriceWindowMinutes() == null) {
      response.add("fixedPriceWindowMinutes", JsonNull.INSTANCE);
    } else {
      response.addProperty("fixedPriceWindowMinutes", policy.fixedPriceWindowMinutes());
    }
    if (policy.dynamicPriceWindowMinutes() == null) {
      response.add("dynamicPriceWindowMinutes", JsonNull.INSTANCE);
    } else {
      response.addProperty("dynamicPriceWindowMinutes", policy.dynamicPriceWindowMinutes());
    }
    response.addProperty("partialRefundEnabled", policy.partialRefundEnabled());
    response.addProperty("orderLevelPolicyEnabled", policy.orderLevelPolicyEnabled());
    response.addProperty(
        "maxSelfServiceRefundsPerDay", policy.maxSelfServiceRefundsPerDay());
    return response;
  }

  private Integer nullableInteger(JsonObject payload, String key) {
    if (!payload.has(key) || payload.get(key).isJsonNull()) {
      return null;
    }
    return payload.get(key).getAsInt();
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
      String dynamicPricingMode = getOptionalString(payload, "dynamicPricingMode").orElse(null);
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
      String productTypeRaw = getOptionalString(payload, "productType").orElse("COMMAND");
      String normalizedProductType = productTypeRaw.trim().toUpperCase(Locale.ROOT);
      if (("RECYCLE_COMMAND_ITEM".equals(normalizedProductType)
          || "RECYCLE_CUSTOM_ITEM".equals(normalizedProductType))
          && !settingsSupplier.get().advancedRecycleEnabled()) {
        throw new ServiceException("feature_disabled", "Advanced recycle is disabled");
      }
      ProductService.AdminProductInput input = new ProductService.AdminProductInput(
          getString(payload, "sku"),
          getString(payload, "title"),
          getOptionalString(payload, "remark").orElse(null),
          CurrencyType.fromConfig(getString(payload, "currency")),
          getLong(payload, "price", 0L),
          productTypeRaw,
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
            dynamicPricingMode,
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

  private void handleAdminProductFromInventory(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AuthService.AuthUser user = requireAuth(exchange, payload);
      AdminService.AdminUser admin =
          adminService.requireAdmin(user, AdminPermission.PRODUCT_MANAGE);
      if (user.boundUuid() == null) {
        throw new ServiceException("uuid_not_bound", "Minecraft UUID is not bound");
      }
      int slot = (int) getLong(payload, "slot", -1L);
      Integer containerSlot = payload.has("containerSlot")
              && !payload.get("containerSlot").isJsonNull()
          ? payload.get("containerSlot").getAsInt() : null;
      InventoryService.InventorySource source = inventorySource(payload);
      String inventoryMode = getOptionalString(payload, "inventoryMode")
          .orElse("TEMPLATE").toUpperCase(Locale.ROOT);
      if (!"TEMPLATE".equals(inventoryMode)) {
        throw new ServiceException(
            "invalid_inventory_mode",
            "Official products created from inventory only support snapshot templates");
      }
      ItemStack item;
      boolean offlineCapture = false;
      try {
        item = awaitPlayerTask(user.boundUuid(), player -> inventoryService.resolve(
            playerInventory(player, source),
            source,
            getString(payload, "revision"),
            slot,
            containerSlot,
            getString(payload, "fingerprint")));
      } catch (ServiceException exception) {
        if (!"player_offline".equals(exception.code())) throw exception;
        if (!admin.allows(AdminPermission.PRODUCT_OFFLINE_INVENTORY_IMPORT)) {
          throw new ServiceException(
              "forbidden", "Offline inventory import permission is required");
        }
        offlineInventoryFeatureService.requireOfficialShopCaptureEnabled();
        offlineCapture = true;
        item = playerDataInventoryService.offlineResolve(
            user.boundUuid(), source, getString(payload, "revision"), slot,
            containerSlot,
            getString(payload, "fingerprint"));
      }
      ItemStack template = item.clone();
      template.setAmount(1);
      ItemSnapshotCodec.Snapshot snapshot = inventoryItemCodec.validateRoundTrip(template);
      long replaceProductId = getLong(payload, "productId", -1L);
      if (replaceProductId > 0L) {
        ProductService.ProductView replaced = productService.replaceSnapshot(
            replaceProductId, snapshot, template.getType().name(), admin.userId());
        JsonObject response = new JsonObject();
        addProductJson(response, replaced, false);
        response.addProperty("itemHash", snapshot.itemHash());
        sendJson(exchange, 200, response);
        JsonObject detail = new JsonObject();
        detail.addProperty("itemHash", snapshot.itemHash());
        detail.addProperty("boundUuid", user.boundUuid().toString());
        detail.addProperty("inventorySource", source.name());
        detail.addProperty("slot", slot);
        if (containerSlot != null) detail.addProperty("containerSlot", containerSlot);
        detail.addProperty("itemMaterial", template.getType().name());
        detail.addProperty("source", offlineCapture ? "PLAYERDATA" : "LIVE");
        adminAuditService.log(
            admin, offlineCapture ? "OFFLINE_PRODUCT_IMPORT" : "PRODUCT_SNAPSHOT_REPLACE", "product",
            Long.toString(replaceProductId), detail, clientIp(exchange));
        return;
      }
      String requestedSku = getOptionalString(payload, "sku").orElse("");
      String sku = requestedSku.isBlank()
          ? "inv-" + snapshot.itemHash().substring(0, 12)
          : requestedSku;
      String stockMode = getOptionalString(payload, "stockMode")
          .orElse("UNLIMITED").toUpperCase(Locale.ROOT);
      Integer stock = "LIMITED".equals(stockMode)
          ? (int) getLong(payload, "stock", 0L) : null;
      ProductService.ProductView product = productService.createSnapshotProduct(
          new ProductService.SnapshotProductInput(
              sku,
              getOptionalString(payload, "title").filter(value -> !value.isBlank())
                  .orElse(template.getType().name()),
              getOptionalString(payload, "remark").orElse(null),
              CurrencyType.fromConfig(getOptionalString(payload, "currency").orElse("SHOP_COIN")),
              getLong(payload, "price", 0L),
              stock,
              payload.has("perUserLimit") && !payload.get("perUserLimit").isJsonNull()
                  ? (int) getLong(payload, "perUserLimit", 0L) : null,
              !payload.has("active") || payload.get("active").getAsBoolean(),
              admin.userId(),
              "TEMPLATE"),
          snapshot,
          template.getType().name(),
          admin.allows(AdminPermission.PRODUCT_ZERO_PRICE));
      JsonObject response = new JsonObject();
      addProductJson(response, product, false);
      response.addProperty("itemHash", snapshot.itemHash());
      response.addProperty("itemMaterial", template.getType().name());
      response.addProperty("itemMetaJson", snapshot.itemMetaJson());
      sendJson(exchange, 201, response);
      JsonObject detail = new JsonObject();
      detail.addProperty("sku", product.sku());
      detail.addProperty("productId", product.id());
      detail.addProperty("boundUuid", user.boundUuid().toString());
      detail.addProperty("inventorySource", source.name());
      detail.addProperty("slot", slot);
      if (containerSlot != null) detail.addProperty("containerSlot", containerSlot);
      detail.addProperty("itemMaterial", template.getType().name());
      detail.addProperty("itemHash", snapshot.itemHash());
      detail.addProperty("source", offlineCapture ? "PLAYERDATA" : "LIVE");
      adminAuditService.log(
          admin,
          offlineCapture ? "OFFLINE_PRODUCT_IMPORT" : "PRODUCT_CREATE_FROM_INVENTORY",
          "product", product.sku(), detail, clientIp(exchange));
    });
  }

  private void handleAdminProductSnapshotHistory(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "GET")) return;
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin =
          requireAdmin(exchange, null, AdminPermission.PRODUCT_MANAGE);
      Long productId = parseLong(parseQuery(exchange).get("productId"));
      if (productId == null || productId <= 0L) {
        throw new ServiceException("bad_request", "Missing productId");
      }
      JsonArray versions = new JsonArray();
      for (ProductService.SnapshotVersionView version :
          productService.listSnapshotVersions(productId)) {
        JsonObject row = new JsonObject();
        row.addProperty("id", version.id());
        row.addProperty("version", version.version());
        row.addProperty("itemHash", version.itemHash());
        row.addProperty("itemMaterial", version.itemMaterial());
        row.addProperty("itemMetaJson", version.itemMetaJson());
        row.addProperty("createdBy", version.createdBy());
        row.addProperty("activeFrom", version.activeFrom().toString());
        row.addProperty("active", version.active());
        versions.add(row);
      }
      JsonObject response = new JsonObject();
      response.add("versions", versions);
      sendJson(exchange, 200, response);
      adminAuditService.log(
          admin, "PRODUCT_SNAPSHOT_HISTORY", "product",
          Long.toString(productId), null, clientIp(exchange));
    });
  }

  private void handleAdminProductSnapshotRollback(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) return;
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin =
          requireAdmin(exchange, payload, AdminPermission.PRODUCT_MANAGE);
      long productId = getLong(payload, "productId", -1L);
      int version = (int) getLong(payload, "version", -1L);
      ProductService.ProductView product =
          productService.rollbackSnapshot(productId, version, admin.userId());
      JsonObject response = new JsonObject();
      addProductJson(response, product, false);
      sendJson(exchange, 200, response);
      JsonObject detail = new JsonObject();
      detail.addProperty("sourceVersion", version);
      adminAuditService.log(
          admin, "PRODUCT_SNAPSHOT_ROLLBACK", "product",
          Long.toString(productId), detail, clientIp(exchange));
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
        addBusinessDateTime(response, "consumedAt", result.consumedAt());
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
      response.add("auctionDisplay", runtimeConfigService.readAuctionDisplayConfig().config());
      response.add("leaderboard", leaderboardSettingsJson(settings));
      response.add("webshopRuntime", webshopRuntimeJson(settings));
      response.add("rechargePayment", rechargePaymentSettingsJson(settings.paymentSettings()));
      response.add("paymentProviders", paymentProviderInfosJson(rechargeService.paymentProviderInfos()));
      response.add("marketRuntime", marketRuntimeJson(settings));
      response.add("maintenance", maintenanceSettingsJson(settings.maintenanceSettings()));
      response.add("logging", loggingSettingsJson(settings.loggingSettings()));
      response.add("broadcast", broadcastSettingsJson(settings.broadcastSettings()));
      response.add("notification", notificationSettingsJson(runtimeConfigService.readNotificationSettings()));
      response.add("offlineInventory", offlineInventoryStateJson());
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
      PluginSettings.PaymentSettings paymentSettings = new PluginSettings.PaymentSettings(
          "",
          currencies,
          PluginSettings.normalizePaymentMethods(getStringArray(payload, "methods")),
          parseRechargeRates(payload));
      validatePaymentRoutes(paymentSettings.rechargeRates());
      long version = runtimeConfigService.updatePaymentRecharge(paymentSettings);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.add("currencies", stringArrayJson(paymentSettings.rechargeCurrencies()));
      detail.add("methods", paymentMethodArrayJson(paymentSettings.rechargeMethods()));
      detail.add("rates", rechargeRateArrayJson(paymentSettings.rechargeRates()));
      adminAuditService.log(admin, "RECHARGE_PAYMENT_UPDATE", "payment", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.add("rechargePayment", rechargePaymentSettingsJson(paymentSettings));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminPaymentProviderConfig(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
        JsonObject response = new JsonObject();
        Map<String, String> query = parseQuery(exchange);
        String locale = query.get("locale");
        String providerId = query.get("providerId");
        response.add("provider", rechargeService.paymentProviderConfiguration(providerId, locale)
            .map(gson::toJsonTree).orElse(JsonNull.INSTANCE));
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
      Map<String, Object> changes = payload.has("changes") && payload.get("changes").isJsonObject()
          ? gson.fromJson(payload.getAsJsonObject("changes"), Map.class)
          : Map.of();
      Set<String> clearedSecrets = new LinkedHashSet<>(getStringArray(payload, "clearedSecrets"));
      String providerId = getString(payload, "providerId");
      PaymentConfigUpdateResult result = rechargeService.updatePaymentProviderConfiguration(
          providerId, new PaymentConfigUpdateRequest(changes, clearedSecrets));

      JsonObject detail = new JsonObject();
      detail.addProperty("status", result.status().name());
      detail.addProperty("providerId", providerId);
      detail.addProperty("changedFieldCount", changes.size());
      detail.addProperty("clearedSecretCount", clearedSecrets.size());
      adminAuditService.log(admin, "PAYMENT_PROVIDER_CONFIG_UPDATE", "payment_provider", null,
          detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.add("result", gson.toJsonTree(result));
      String locale = getString(payload, "locale");
      response.add("provider", rechargeService.paymentProviderConfiguration(providerId, locale)
          .map(gson::toJsonTree).orElse(JsonNull.INSTANCE));
      sendJson(exchange, result.status().name().equals("REJECTED") ? 422 : 200, response);
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
        int rechargeOrderExpireMinutes = clampInt(
          getLong(payload, "rechargeOrderExpireMinutes", 15L), 1, 24 * 60 * 30, "rechargeOrderExpireMinutes");
      boolean allowSharedClaimCommand = getBoolean(payload, "allowSharedClaimCommand");
      boolean refundUndeliveredEnabled = getBoolean(payload, "refundUndeliveredEnabled");
      boolean advancedRecycleEnabled = getBoolean(payload, "advancedRecycleEnabled");
      ZoneId timeZone = readTimeZoneField(getString(payload, "timeZone"), "timeZone");

      RuntimeConfigService.RuntimeSettingsUpdate update = new RuntimeConfigService.RuntimeSettingsUpdate(
          defaultLocale,
          sessionExpireHours,
          bindRequestExpireMinutes,
          accessTokenLength,
          deliveryBatchSize,
          deliveryRetrySeconds,
          orderCooldownSeconds,
          rechargeOrderExpireMinutes,
          allowSharedClaimCommand,
          refundUndeliveredEnabled,
          advancedRecycleEnabled,
          timeZone);
      long version = runtimeConfigService.updateWebshopRuntime(update);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("defaultLocale", defaultLocale);
      detail.addProperty("timeZone", timeZone.getId());
      detail.addProperty("deliveryBatchSize", deliveryBatchSize);
      detail.addProperty("deliveryRetrySeconds", deliveryRetrySeconds);
      detail.addProperty("orderCooldownSeconds", orderCooldownSeconds);
      detail.addProperty("rechargeOrderExpireMinutes", rechargeOrderExpireMinutes);
      detail.addProperty("advancedRecycleEnabled", advancedRecycleEnabled);
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
      VisualCustomizationService.VisualSettings current =
          visualCustomizationService.readSettings();
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
              getOptionalString(payload, "namePolicyMode").orElse("SOFT")),
          payload.has("iconPriority") ? getStringArray(payload, "iconPriority") : current.iconPriority(),
          payload.has("namePriority") ? getStringArray(payload, "namePriority") : current.namePriority());
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
        row.addProperty("displayStatus", order.displayStatus());
        row.addProperty("currency", order.currency().name());
        row.addProperty("totalAmount", order.totalAmount());
        addBusinessDateTime(row, "createdAt", order.createdAt());
        if (order.deliveredAt() == null) {
          row.add("deliveredAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "deliveredAt", order.deliveredAt());
        }
        if (order.refundedAt() == null) {
          row.add("refundedAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "refundedAt", order.refundedAt());
        }
        if (order.refundDeadline() == null) {
          row.add("refundDeadline", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "refundDeadline", order.refundDeadline());
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
          addBusinessDateTime(row, "groupBuyVoucherConsumedAt", order.groupBuyVoucherConsumedAt());
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
        addBusinessDateTime(row, "createdAt", listing.createdAt());
        if (listing.soldAt() == null) {
          row.add("soldAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "soldAt", listing.soldAt());
        }
        if (listing.unlistedAt() == null) {
          row.add("unlistedAt", JsonNull.INSTANCE);
        } else {
          addBusinessDateTime(row, "unlistedAt", listing.unlistedAt());
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
      addBusinessDateTime(response, "createdAt", userView.createdAt());
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
        addBusinessDateTime(row, "createdAt", user.createdAt());
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
        addBusinessDateTime(row, "createdAt", log.createdAt());
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
      relativePath = "index.html";
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

  private void handleAdminOfflineInventory(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
      withServiceHandling(exchange, () -> {
        requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
        sendJson(exchange, 200, offlineInventoryStateJson());
      });
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin =
          requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      boolean enabled = getBoolean(payload, "enabled");
      boolean acknowledged = getBoolean(payload, "acknowledged");
      boolean officialShopCaptureEnabled =
          payload.has("officialShopCaptureEnabled")
              ? payload.get("officialShopCaptureEnabled").getAsBoolean()
              : offlineInventoryFeatureService.state().officialShopCaptureEnabled();
      long version =
          offlineInventoryFeatureService.update(
              enabled, acknowledged, officialShopCaptureEnabled, admin.username());
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("enabled", enabled);
      detail.addProperty("officialShopCaptureEnabled", officialShopCaptureEnabled);
      detail.addProperty("riskAckVersion", OfflineInventoryFeatureService.RISK_ACK_VERSION);
      adminAuditService.log(
          admin, enabled ? "OFFLINE_INVENTORY_ENABLE" : "OFFLINE_INVENTORY_DISABLE",
          "offline_inventory", null, detail, clientIp(exchange));
      sendJson(exchange, 200, offlineInventoryStateJson());
    });
  }

  private JsonObject offlineInventoryStateJson() {
    OfflineInventoryFeatureService.State state = offlineInventoryFeatureService.state();
    JsonObject response = new JsonObject();
    response.addProperty("enabled", state.enabled());
    response.addProperty("requested", state.requested());
    response.addProperty("forceDisabled", state.forceDisabled());
    response.addProperty(
        "officialShopCaptureEnabled", state.officialShopCaptureEnabled());
    response.addProperty("riskAckVersion", state.riskAckVersion());
    response.addProperty("requiredRiskAckVersion", OfflineInventoryFeatureService.RISK_ACK_VERSION);
    response.addProperty("version", state.version());
    if (state.enabledAt() != null) response.addProperty("enabledAt", state.enabledAt());
    if (state.enabledBy() != null) response.addProperty("enabledBy", state.enabledBy());
    return response;
  }

  private void handleAdminVisualPacks(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      JsonObject response = new JsonObject();
      JsonArray packs = new JsonArray();
      for (VisualPackService.PackRecord pack : visualPackService.list()) {
        packs.add(visualPackJson(pack));
      }
      response.add("packs", packs);
      response.addProperty("priorityRule", "FIRST_ENABLED_MATCH");
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminVisualPackUpload(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AdminService.AdminUser admin =
          requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      byte[] bytes = readRequestBodyWithLimit(exchange, VisualPackService.MAX_UPLOAD_BYTES);
      VisualPackService.PackRecord pack = visualPackService.install(bytes, admin.username());
      adminAuditService.log(
          admin, "VISUAL_PACK_UPLOAD", "visual_pack", pack.packId(),
          visualPackJson(pack), clientIp(exchange));
      sendJson(exchange, 200, visualPackJson(pack));
    });
  }

  private void handleAdminVisualPackState(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin =
          requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String packId = getString(payload, "packId");
      Boolean enabled = payload.has("enabled") ? getBoolean(payload, "enabled") : null;
      Boolean iconsEnabled =
          payload.has("iconsEnabled") ? getBoolean(payload, "iconsEnabled") : null;
      Boolean translationsEnabled =
          payload.has("translationsEnabled") ? getBoolean(payload, "translationsEnabled") : null;
      VisualPackService.PackRecord pack = visualPackService.updateState(
          packId, enabled, iconsEnabled, translationsEnabled);
      adminAuditService.log(
          admin, "VISUAL_PACK_STATE", "visual_pack", pack.packId(),
          visualPackJson(pack), clientIp(exchange));
      sendJson(exchange, 200, visualPackJson(pack));
    });
  }

  private void handleAdminVisualPackMove(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin =
          requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String packId = getString(payload, "packId");
      String direction = getString(payload, "direction").trim().toUpperCase(Locale.ROOT);
      if (!direction.equals("UP") && !direction.equals("DOWN")) {
        throw new ServiceException("bad_request", "direction must be UP or DOWN");
      }
      visualPackService.move(packId, direction.equals("UP") ? -1 : 1);
      JsonObject detail = new JsonObject();
      detail.addProperty("direction", direction);
      adminAuditService.log(
          admin, "VISUAL_PACK_MOVE", "visual_pack", packId,
          detail, clientIp(exchange));
      sendJson(exchange, 200, detail);
    });
  }

  private void handleAdminVisualPackDelete(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin =
          requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      String packId = getString(payload, "packId");
      boolean deleted = visualPackService.delete(packId);
      JsonObject response = new JsonObject();
      response.addProperty("packId", packId);
      response.addProperty("deleted", deleted);
      adminAuditService.log(
          admin, "VISUAL_PACK_DELETE", "visual_pack", packId,
          response, clientIp(exchange));
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminVisualPackDownload(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireAdmin(exchange, null, AdminPermission.ECONOMY_MANAGE);
      String packId = parseQuery(exchange).get("packId");
      byte[] bytes = visualPackService.readOriginal(packId)
          .orElseThrow(() -> new ServiceException("not_found", "Visual pack not found"));
      exchange.getResponseHeaders().set("Content-Type", "application/zip");
      exchange.getResponseHeaders().set(
          "Content-Disposition", "attachment; filename=\"" + packId + ".zip\"");
      applyCorsHeaders(exchange);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
  }

  private void handleTextureAsset(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "GET")) {
      return;
    }
    String path = exchange.getRequestURI().getPath();
    String relative = path.startsWith("/textures/")
        ? path.substring("/textures/".length())
        : "";
    if (relative.startsWith("resolved/")) {
      handleResolvedTexture(exchange, relative.substring("resolved/".length()));
      return;
    }
    if (!relative.matches("(?:item|block)/[a-z0-9_./-]+\\.png")
        || relative.contains("..")) {
      sendJson(exchange, 400, errorJson("bad_request", "Invalid texture path"));
      return;
    }
    Path textureRoot = staticRoot.resolve("textures").normalize();
    Path target = textureRoot.resolve(relative).normalize();
    if (!target.startsWith(textureRoot)) {
      sendJson(exchange, 400, errorJson("bad_request", "Invalid texture path"));
      return;
    }
    byte[] content;
    if (Files.isRegularFile(target)) {
      content = Files.readAllBytes(target);
    } else {
      content = downloadTextureAsset(relative);
      if (content == null) {
        sendJson(exchange, 404, errorJson("not_found", "Texture not found"));
        return;
      }
      try {
        Files.createDirectories(target.getParent());
        Files.write(target, content);
      } catch (IOException exception) {
        plugin.getLogger().fine("Could not cache texture " + relative + ": " + exception.getMessage());
      }
    }
    exchange.getResponseHeaders().set("Content-Type", "image/png");
    exchange.getResponseHeaders().set("Cache-Control", "public, max-age=604800, immutable");
    applyCorsHeaders(exchange);
    exchange.sendResponseHeaders(200, content.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(content);
    }
  }

  private void handleVisualPackAsset(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange) || !ensureMethod(exchange, "GET")) {
      return;
    }
    String path = exchange.getRequestURI().getPath();
    String relative = path.substring("/visual-packs/".length());
    String[] parts = relative.split("/", 3);
    if (parts.length != 3) {
      sendJson(exchange, 404, errorJson("not_found", "Visual asset not found"));
      return;
    }
    Optional<byte[]> bytes = visualPackService.readAsset(parts[0], parts[1], parts[2]);
    if (bytes.isEmpty()) {
      sendJson(exchange, 404, errorJson("not_found", "Visual asset not found"));
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "image/png");
    exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
    applyCorsHeaders(exchange);
    exchange.sendResponseHeaders(200, bytes.get().length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes.get());
    }
  }

  private void handleResolvedTexture(HttpExchange exchange, String relative) throws IOException {
    if (!relative.matches("[a-z0-9_.-]+/[a-z0-9_./-]+\\.png") || relative.contains("..")) {
      sendJson(exchange, 400, errorJson("bad_request", "Invalid resolved texture path"));
      return;
    }
    String withoutExtension = relative.substring(0, relative.length() - 4);
    int slash = withoutExtension.indexOf('/');
    String itemId = withoutExtension.substring(0, slash) + ":"
        + withoutExtension.substring(slash + 1);
    Optional<VisualPackService.ResolvedVisual> resolved = visualPackService.resolve(itemId);
    if (resolved.isEmpty() || resolved.get().iconPath() == null) {
      sendJson(exchange, 404, errorJson("not_found", "Resolved texture not found"));
      return;
    }
    VisualPackService.ResolvedVisual visual = resolved.get();
    String assetPath = visual.iconPath().substring("/visual-packs/".length());
    String[] assetParts = assetPath.split("/", 3);
    byte[] bytes = visualPackService.readAsset(assetParts[0], assetParts[1], assetParts[2])
        .orElseThrow(() -> new ServiceException("not_found", "Resolved texture not found"));
    exchange.getResponseHeaders().set("Content-Type", "image/png");
    exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
    exchange.getResponseHeaders().set("X-WebShopX-Texture-Source", "visual-pack");
    applyCorsHeaders(exchange);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(bytes);
    }
  }

  private byte[] downloadTextureAsset(String relative) {
    String minecraftVersion = plugin.getServer().getMinecraftVersion();
    List<String> versions = minecraftVersion.startsWith("1.21")
        ? List.of(minecraftVersion, "1.21")
        : List.of(minecraftVersion, "1.20.6");
    for (String version : versions) {
      String url = "https://mcasset.cloud/" + version
          + "/assets/minecraft/textures/" + relative;
      try {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<byte[]> response =
            textureHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 200
            && response.body().length > 0
            && response.body().length <= 2 * 1024 * 1024) {
          return response.body();
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return null;
      } catch (Exception ignored) {
        // Try the compatible fallback version.
      }
    }
    return null;
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
    if ("REFUNDED".equalsIgnoreCase(order.status())
        || "CANCELLED".equalsIgnoreCase(order.status())
        || "REFUNDED".equalsIgnoreCase(order.displayStatus())
        || "CANCELLED".equalsIgnoreCase(order.displayStatus())
        || "CLAIMED".equalsIgnoreCase(order.displayStatus())) {
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
      return order.refundAmount() > 0L
          && ("PENDING".equalsIgnoreCase(order.status())
              || "WAIT_CLAIM".equalsIgnoreCase(order.status())
              || "WAIT_CLAIM".equalsIgnoreCase(order.displayStatus()));
    }
    return "PENDING".equalsIgnoreCase(order.status())
        && order.refundDeadline() != null
        && now.isBefore(order.refundDeadline());
  }

  private void handleAdminAuctionDisplayUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.ECONOMY_MANAGE);
      int chartPoints = clampInt(getLong(payload, "chartPoints", 10L), 1, 100, "chartPoints");
      int timelineEntries = clampInt(getLong(payload, "timelineEntries", 5L), 1, 100, "timelineEntries");
      long version = runtimeConfigService.updateAuctionDisplayConfig(chartPoints, timelineEntries);
      publishRuntimeConfigRefresh(version);
      JsonObject detail = new JsonObject();
      detail.addProperty("chartPoints", chartPoints);
      detail.addProperty("timelineEntries", timelineEntries);
      adminAuditService.log(admin, "AUCTION_DISPLAY_UPDATE", "auction_display", null, detail, clientIp(exchange));
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("chartPoints", chartPoints);
      response.addProperty("timelineEntries", timelineEntries);
      sendJson(exchange, 200, response);
    });
  }

  private void handleAdminMigrateUuid(HttpExchange exchange) throws IOException {
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
      UUID oldUuid = parseRequiredUuid(payload, "oldUuid");
      UUID newUuid = parseRequiredUuid(payload, "newUuid");
      AdminService.UuidMigrationResult result = adminService.migrateUserUuid(userId, oldUuid, newUuid);

      JsonObject migrated = new JsonObject();
      result.migrated().forEach(migrated::addProperty);
      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("userId", result.userId());
      response.addProperty("oldUuid", result.oldUuid().toString());
      response.addProperty("newUuid", result.newUuid().toString());
      response.add("migrated", migrated);
      sendJson(exchange, 200, response);

      JsonObject detail = new JsonObject();
      detail.addProperty("userId", userId);
      detail.addProperty("oldUuid", oldUuid.toString());
      detail.addProperty("newUuid", newUuid.toString());
      detail.add("migrated", migrated.deepCopy());
      adminAuditService.log(admin, "USER_UUID_MIGRATE", "user", String.valueOf(userId), detail, clientIp(exchange));
    });
  }

  private void handlePublicLocaleMessages(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      String path = exchange.getRequestURI().getPath();
      String prefix = "/api/locales/";
      String suffix = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
      if (!suffix.endsWith("/messages")) {
        throw new ServiceException("not_found", "Locale messages endpoint not found");
      }
      String rawLocale = suffix.substring(0, suffix.length() - "/messages".length());
      sendJson(exchange, 200, localeCenterService.readPublicWebMessages(rawLocale));
    });
  }

  private boolean canDiscard(OrderService.OrderView order, LocalDateTime now) {
    if (order == null || canRefund(order, now)) {
      return false;
    }
    String status = order.status();
    String displayStatus = order.displayStatus();
    String voucherStatus = order.groupBuyVoucherStatus();
    if ("REFUNDED".equalsIgnoreCase(status)
        || "CANCELLED".equalsIgnoreCase(status)
        || "CLAIMED".equalsIgnoreCase(displayStatus)
        || "REFUNDED".equalsIgnoreCase(displayStatus)
        || "CANCELLED".equalsIgnoreCase(displayStatus)
        || "CONSUMED".equalsIgnoreCase(voucherStatus)
        || "REFUNDED".equalsIgnoreCase(voucherStatus)
        || "CANCELLED".equalsIgnoreCase(voucherStatus)) {
      return false;
    }
    if ("ISSUED".equalsIgnoreCase(voucherStatus)) {
      return true;
    }
    return "PENDING".equalsIgnoreCase(displayStatus)
        || "WAIT_CLAIM".equalsIgnoreCase(displayStatus)
        || "PENDING".equalsIgnoreCase(status)
        || "WAIT_CLAIM".equalsIgnoreCase(status);
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
    addBusinessDateTime(response, "createdAt", admin.createdAt());
    addBusinessDateTime(response, "updatedAt", admin.updatedAt());
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
    if (product.productType() == ProductService.ProductType.SNAPSHOT_ITEM) {
      ProductService.SnapshotView snapshot = productService.readSnapshot(product.id());
      row.addProperty("itemHash", snapshot.itemHash());
      row.addProperty("itemMaterial", snapshot.itemMaterial());
      row.addProperty("itemMetaJson", snapshot.itemMetaJson());
      row.addProperty("inventoryMode", productService.readInventoryMode(product.id()));
      row.addProperty("productSemantic", product.productSemantic().name());
      row.addProperty("fulfillmentType", product.fulfillmentType().name());
    }
    row.addProperty("dynamicPricingEnabled", product.dynamicPricingEnabled());
    row.addProperty("dynamicAlgorithm", product.dynamicAlgorithm());
    row.addProperty("dynamicPricingMode", product.dynamicPricingMode());
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

  private List<PluginSettings.RechargeRate> parseRechargeRates(JsonObject payload) {
    if (payload == null || !payload.has("rates") || payload.get("rates").isJsonNull()) {
      return List.of();
    }
    JsonElement value = payload.get("rates");
    if (!value.isJsonArray()) {
      throw new ServiceException("bad_request", "Field must be an array: rates");
    }
    List<PluginSettings.RechargeRate> rates = new java.util.ArrayList<>();
    for (JsonElement element : value.getAsJsonArray()) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject item = element.getAsJsonObject();
      String providerId = getString(item, "providerId");
      if (providerId.isBlank()) {
        throw new ServiceException("bad_request", "providerId is required for every payment route");
      }
      PaymentMethod method = parsePaymentMethod(getString(item, "method"));
      String currency = getString(item, "currency");
      if (!currency.trim().toUpperCase(Locale.ROOT).matches("^[A-Z]{3,8}$")) {
        throw new ServiceException("bad_request", "Invalid rate currency: " + currency);
      }
      long coinsPerUnit = getLong(item, "coinsPerUnit", 0L);
      if (coinsPerUnit <= 0L) {
        throw new ServiceException("bad_request", "coinsPerUnit must be greater than 0");
      }
      rates.add(new PluginSettings.RechargeRate(providerId, method, currency, coinsPerUnit));
    }
    return rates;
  }

  private void handleAdminHomeLinkUpdate(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      AdminService.AdminUser admin = requireAdmin(exchange, payload, AdminPermission.HOMEPAGE_MANAGE);
      String homeUrl = getString(payload, "homeUrl").trim();
      if (!homeUrl.isBlank()) {
        try {
          java.net.URI uri = java.net.URI.create(homeUrl);
          if (!uri.isAbsolute()
              || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException();
          }
        } catch (IllegalArgumentException exception) {
          throw new ServiceException("bad_request", "homeUrl must be an absolute HTTP(S) URL");
        }
      }
      long version = runtimeConfigService.updateHomeUrl(homeUrl);
      publishRuntimeConfigRefresh(version);

      JsonObject detail = new JsonObject();
      detail.addProperty("homeUrl", homeUrl);
      adminAuditService.log(admin, "HOME_LINK_UPDATE", "home_link", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      response.addProperty("version", version);
      sendJson(exchange, 200, response);
    });
  }

  private void validatePaymentRoutes(List<PluginSettings.RechargeRate> routes) {
    Map<String, WebShopXPaymentBridge.PaymentProviderInfo> providers = new java.util.LinkedHashMap<>();
    for (WebShopXPaymentBridge.PaymentProviderInfo provider : rechargeService.paymentProviderInfos()) {
      providers.put(provider.providerId(), provider);
    }
    Set<String> combinations = new LinkedHashSet<>();
    for (PluginSettings.RechargeRate route : routes) {
      String key = route.method().name() + ":" + route.currency();
      if (!combinations.add(key)) {
        throw new ServiceException("payment_route_conflict",
            "Only one provider may serve " + route.method() + " " + route.currency());
      }
      WebShopXPaymentBridge.PaymentProviderInfo provider = providers.get(route.providerId());
      if (provider == null) {
        throw new ServiceException("payment_provider_not_found",
            "Payment provider was not found: " + route.providerId());
      }
      if (!provider.supportedMethods().contains(route.method())
          || !provider.supportedCurrencies().contains(route.currency())) {
        throw new ServiceException("payment_route_unsupported",
            "Payment provider " + route.providerId() + " does not support "
                + route.method() + " " + route.currency());
      }
    }
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

  private UUID parseRequiredUuid(JsonObject payload, String field) {
    String value = getString(payload, field).trim().toLowerCase(Locale.ROOT);
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value)) {
        throw new IllegalArgumentException("Non-canonical UUID");
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("bad_request", "Invalid UUID field: " + field);
    }
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
      int status = switch (exception.code()) {
        case "auth_required", "auth_invalid" -> 401;
        case "forbidden", "not_admin" -> 403;
        case "player_offline", "player_state_changed", "inventory_changed",
            "operation_pending", "product_conflict", "offline_official_capture_disabled" -> 409;
        default -> 400;
      };
      sendJson(exchange, status, errorJson(exception.code(), exception.getMessage()));
    } catch (Exception exception) {
      plugin.getLogger().log(java.util.logging.Level.SEVERE, "HTTP request failed", exception);
      sendJson(exchange, 500, errorJson("internal_error", "Server internal error"));
    }
  }

  private JsonObject sessionResponse(AuthService.AuthResult result) {
    JsonObject response = new JsonObject();
    response.addProperty("sessionToken", result.sessionToken());
    addBusinessDateTime(response, "expiresAt", result.expiresAt());
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

  private JsonObject rechargeCreateResultJson(HttpExchange exchange, RechargeService.RechargeCreateResult result) {
    JsonObject response = new JsonObject();
    response.addProperty("success", result.success());
    response.addProperty("orderId", result.orderId());
    addNullableString(response, "providerOrderId", result.providerOrderId());
    addNullableString(response, "payUrl", result.payUrl());
    addNullableString(response, "qrCodeUrl", processQrCodeUrl(exchange, result.qrCodeUrl()));
    addNullableInstant(response, "expireTime", result.expireTime());
    addNullableString(response, "errorCode", result.errorCode());
    addNullableString(response, "message", result.message());
    return response;
  }

  private JsonObject rechargeOrderJson(HttpExchange exchange, RechargeService.RechargeOrder order) {
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
    addNullableString(response, "qrCodeUrl", processQrCodeUrl(exchange, order.qrCodeUrl()));
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
    response.add("rates", rechargeRateArrayJson(settings.rechargeRates()));
    return response;
  }

  private JsonArray rechargeRateArrayJson(List<PluginSettings.RechargeRate> rates) {
    JsonArray array = new JsonArray();
    if (rates == null) {
      return array;
    }
    for (PluginSettings.RechargeRate rate : rates) {
      if (rate == null) {
        continue;
      }
      JsonObject item = new JsonObject();
      item.addProperty("providerId", rate.providerId());
      item.addProperty("method", rate.method().name());
      item.addProperty("currency", rate.currency());
      item.addProperty("coinsPerUnit", rate.coinsPerUnit());
      array.add(item);
    }
    return array;
  }

  private JsonObject paymentProviderInfoJson(WebShopXPaymentBridge.PaymentProviderInfo info) {
    JsonObject response = new JsonObject();
    if (info == null) {
      response.addProperty("available", false);
      response.add("providerId", JsonNull.INSTANCE);
      response.add("displayName", JsonNull.INSTANCE);
      response.add("supportedMethods", new JsonArray());
      response.add("supportedCurrencies", new JsonArray());
      response.addProperty("rechargeRateEditable", true);
      response.addProperty("settlementAmountMode", "WEBSHOPX_CALCULATED");
      response.addProperty("rechargeRateNotice", "");
      return response;
    }
    response.addProperty("available", info.available());
    addNullableString(response, "providerId", info.providerId());
    addNullableString(response, "displayName", info.displayName());
    response.add("supportedMethods", paymentMethodArrayJson(List.copyOf(info.supportedMethods())));
    response.add("supportedCurrencies", stringArrayJson(List.copyOf(info.supportedCurrencies())));
    response.addProperty("rechargeRateEditable", info.rechargeRateEditable());
    response.addProperty("settlementAmountMode", info.settlementAmountMode());
    response.addProperty("rechargeRateNotice", info.rechargeRateNotice());
    return response;
  }

  private JsonArray paymentProviderInfosJson(List<WebShopXPaymentBridge.PaymentProviderInfo> infos) {
    JsonArray array = new JsonArray();
    for (WebShopXPaymentBridge.PaymentProviderInfo info : infos) {
      array.add(paymentProviderInfoJson(info));
    }
    return array;
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

  private Long getOptionalPositiveLong(JsonObject payload, String key) {
    if (!payload.has(key) || payload.get(key) == null || payload.get(key).isJsonNull()) {
      return null;
    }
    long value = getLong(payload, key, 0L);
    return value > 0L ? value : null;
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

  private List<String> getStringList(JsonObject payload, String key) {
    if (payload == null || key == null || !payload.has(key) || payload.get(key).isJsonNull()) {
      return Collections.emptyList();
    }
    JsonElement value = payload.get(key);
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (value.isJsonArray()) {
      for (JsonElement entry : value.getAsJsonArray()) {
        if (entry != null && !entry.isJsonNull()) {
          String text = entry.getAsString().trim();
          if (!text.isEmpty()) {
            result.add(text);
          }
        }
      }
    } else if (value.isJsonPrimitive()) {
      for (String entry : value.getAsString().split(",")) {
        String text = entry.trim();
        if (!text.isEmpty()) {
          result.add(text);
        }
      }
    }
    return List.copyOf(result);
  }

  private long queryLong(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      if (resultSet.next()) {
        return Math.max(0L, resultSet.getLong(1));
      }
    }
    return 0L;
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

  private JsonArray materialOverrideListJson(List<MaterialVisualService.MaterialVisualEntry> entries) {
    JsonArray array = new JsonArray();
    for (MaterialVisualService.MaterialVisualEntry entry : entries) {
      array.add(materialOverrideJson(entry));
    }
    return array;
  }

  private void addLocalizedName(
      JsonObject target, Map<String, String> localizedNames, String locale) {
    if (locale == null || locale.isBlank() || target.has(locale)) {
      return;
    }
    String value = localizedNames.get(locale);
    if (value == null && locale.contains("_")) {
      value = localizedNames.get(locale.substring(0, locale.indexOf('_')));
    }
    if (value != null && !value.isBlank()) {
      target.addProperty(locale, value);
    }
  }

  private String normalizeVisualLocale(String value) {
    String normalized = value == null
        ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    return normalized.matches("[a-z0-9_]{2,32}") ? normalized : "en_us";
  }

  private JsonObject visualPackJson(VisualPackService.PackRecord pack) {
    JsonObject row = new JsonObject();
    row.addProperty("packId", pack.packId());
    row.addProperty("name", pack.packName());
    row.addProperty("versionId", pack.versionId());
    row.addProperty("enabled", pack.enabled());
    row.addProperty("sortOrder", pack.sortOrder());
    row.addProperty("iconsEnabled", pack.iconsEnabled());
    row.addProperty("translationsEnabled", pack.translationsEnabled());
    row.addProperty("fileSize", pack.fileSize());
    row.addProperty("entryCount", pack.entryCount());
    row.addProperty("uploadedBy", pack.uploadedBy());
    row.addProperty("createdAt", pack.createdAt() == null ? null : pack.createdAt().toString());
    row.addProperty("updatedAt", pack.updatedAt() == null ? null : pack.updatedAt().toString());
    try {
      JsonObject manifest = JsonParser.parseString(pack.manifestJson()).getAsJsonObject();
      row.add("environment", manifest.has("environment")
          ? manifest.get("environment") : new JsonObject());
      row.add("render", manifest.has("render") ? manifest.get("render") : new JsonObject());
      row.add("locales", manifest.has("locales") ? manifest.get("locales") : new JsonArray());
    } catch (Exception ignored) {
      row.add("environment", new JsonObject());
      row.add("render", new JsonObject());
    }
    return row;
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
      addBusinessDateTime(row, "updatedAt", entry.updatedAt());
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
      if (cause instanceof IllegalStateException
          && "player is offline".equalsIgnoreCase(cause.getMessage())) {
        throw new ServiceException("player_offline", "Player is offline");
      }
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
    PluginSettings.EmbeddedWebSettings webSettings = settingsSupplier.get().embeddedWebSettings();
    if (!webSettings.corsEnabled()) {
      return;
    }

    String origin = exchange.getRequestHeaders().getFirst("Origin");
    List<String> allowed = webSettings.corsAllowedOrigins();
    if (allowed.contains("*")) {
      if (origin != null && !origin.isBlank()) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
      } else {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      }
    } else if (origin != null && allowed.contains(origin)) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    }

    exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
    exchange.getResponseHeaders().set(
        "Access-Control-Allow-Headers",
        "Content-Type, Authorization, X-Requested-With, Accept, Origin");
    exchange.getResponseHeaders().set(
        "Access-Control-Allow-Methods",
        "GET, POST, PUT, DELETE, OPTIONS, PATCH");
  }

  @FunctionalInterface
  private interface CheckedRunnable {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface CheckedPathSupplier {
    Path get() throws Exception;
  }

  private static class RedirectEntry {
    final String url;
    final long createdAt;

    RedirectEntry(String url, long createdAt) {
      this.url = url;
      this.createdAt = createdAt;
    }
  }

  private String getOrCreateShortRedirect(String payUrl) {
    if (payUrl == null || payUrl.isBlank()) {
      return "";
    }
    cleanExpiredRedirects();
    for (Map.Entry<String, RedirectEntry> entry : redirectMap.entrySet()) {
      if (payUrl.equals(entry.getValue().url)) {
        return entry.getKey();
      }
    }
    String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    redirectMap.put(shortId, new RedirectEntry(payUrl, System.currentTimeMillis()));
    return shortId;
  }

  private void cleanExpiredRedirects() {
    long now = System.currentTimeMillis();
    redirectMap.entrySet().removeIf(entry -> now - entry.getValue().createdAt > 900000L);
  }

  private String resolveBaseUrl(HttpExchange exchange) {
    if (exchange == null) {
      return "";
    }
    String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
    if (proto == null || proto.isBlank()) {
      proto = "http";
    }
    String host = exchange.getRequestHeaders().getFirst("Host");
    if (host == null || host.isBlank()) {
      InetSocketAddress local = exchange.getLocalAddress();
      if (local != null) {
        host = local.getHostString() + ":" + local.getPort();
      } else {
        host = "localhost";
      }
    }
    return proto + "://" + host;
  }

  private String processQrCodeUrl(HttpExchange exchange, String qrUrl) {
    if (qrUrl == null || qrUrl.isBlank()) {
      return null;
    }
    if (qrUrl.startsWith("data:")) {
      return qrUrl;
    }
    if (qrUrl.startsWith("http://") || qrUrl.startsWith("https://")) {
      String shortId = getOrCreateShortRedirect(qrUrl);
      String baseUrl = resolveBaseUrl(exchange);
      String shortUrl = baseUrl + "/api/recharge/redirect?id=" + shortId;
      String svgBase64 = tryGenerateQrCodeSvg(shortUrl);
      if (svgBase64 != null) {
        return svgBase64;
      } else {
        return null;
      }
    }
    return qrUrl;
  }

  private static String tryGenerateQrCodeSvg(String content) {
    if (content == null || content.isBlank()) {
      return null;
    }
    try {
      Class<?> qrCodeClass = null;
      Class<?> ecClass = null;
      Object ecLevel = null;

      try {
        qrCodeClass = Class.forName("top.mrxiaom.qrcode.QRCode");
        ecClass = Class.forName("top.mrxiaom.qrcode.enums.ErrorCorrectionLevel");
        for (Object constant : ecClass.getEnumConstants()) {
          if ("M".equals(((Enum<?>) constant).name())) {
            ecLevel = constant;
            break;
          }
        }
      } catch (ClassNotFoundException e1) {
        try {
          qrCodeClass = Class.forName("com.webshopx.payments.libs.qrcode.QRCode");
          ecClass = Class.forName("com.webshopx.payments.libs.qrcode.enums.ErrorCorrectionLevel");
          for (Object constant : ecClass.getEnumConstants()) {
            if ("M".equals(((Enum<?>) constant).name())) {
              ecLevel = constant;
              break;
            }
          }
        } catch (ClassNotFoundException e2) {
          return null;
        }
      }

      if (qrCodeClass == null || ecLevel == null) {
        return null;
      }

      java.lang.reflect.Method createMethod = qrCodeClass.getMethod("create", String.class, ecClass);
      Object qrCodeInstance = createMethod.invoke(null, content, ecLevel);

      java.lang.reflect.Method getModuleCountMethod = qrCodeClass.getMethod("getModuleCount");
      int modules = (Integer) getModuleCountMethod.invoke(qrCodeInstance);

      java.lang.reflect.Method isDarkMethod = qrCodeClass.getMethod("isDark", int.class, int.class);

      int quiet = 4;
      int size = modules + quiet * 2;
      StringBuilder svg = new StringBuilder(4096);
      svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
          .append(size)
          .append(' ')
          .append(size)
          .append("\" shape-rendering=\"crispEdges\">")
          .append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>")
          .append("<path fill=\"#000\" d=\"");
      for (int row = 0; row < modules; row++) {
        for (int col = 0; col < modules; col++) {
          boolean isDark = (Boolean) isDarkMethod.invoke(qrCodeInstance, row, col);
          if (isDark) {
            svg.append('M')
                .append(col + quiet)
                .append(' ')
                .append(row + quiet)
                .append("h1v1h-1z");
          }
        }
      }
      svg.append("\"/></svg>");
      String encoded = java.util.Base64.getEncoder().encodeToString(svg.toString().getBytes(StandardCharsets.UTF_8));
      return "data:image/svg+xml;base64," + encoded;
    } catch (Throwable ignored) {
      return null;
    }
  }
}

