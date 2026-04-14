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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

class EmbeddedWebServer {
  private final JavaPlugin plugin;
  private final Supplier<PluginSettings> settingsSupplier;
  private final AuthService authService;
  private final WalletService walletService;
  private final RedeemCodeService redeemCodeService;
  private final ProductService productService;
  private final OrderService orderService;
  private final MarketService marketService;
  private final AdminService adminService;
  private final AdminAuditService adminAuditService;
  private final Gson gson;

  private HttpServer server;
  private ExecutorService executorService;
  private Path staticRoot;

  EmbeddedWebServer(
      JavaPlugin plugin,
      Supplier<PluginSettings> settingsSupplier,
      AuthService authService,
      WalletService walletService,
      RedeemCodeService redeemCodeService,
      ProductService productService,
      OrderService orderService,
      MarketService marketService,
      AdminService adminService,
      AdminAuditService adminAuditService) {
    this.plugin = plugin;
    this.settingsSupplier = settingsSupplier;
    this.authService = authService;
    this.walletService = walletService;
    this.redeemCodeService = redeemCodeService;
    this.productService = productService;
    this.orderService = orderService;
    this.marketService = marketService;
    this.adminService = adminService;
    this.adminAuditService = adminAuditService;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  void start(Path staticRoot) throws IOException {
    stop();
    this.staticRoot = staticRoot;
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
    server.createContext("/api/redeem/use", this::handleRedeemUse);
    server.createContext("/api/products", this::handleProducts);
    server.createContext("/api/orders", this::handleOrders);
    server.createContext("/api/orders/list", this::handleOrdersList);
    server.createContext("/api/orders/refund", this::handleOrdersRefund);
    server.createContext("/api/orders/policy", this::handleOrdersPolicy);
    server.createContext("/api/meta/currency", this::handleCurrencyMeta);
    server.createContext("/api/meta/materials", this::handleMaterialMeta);
    server.createContext("/api/market/listings", this::handleMarketListings);
    server.createContext("/api/market/buy", this::handleMarketBuy);
    server.createContext("/api/market/bid", this::handleMarketBid);
    server.createContext("/api/market/unlist", this::handleMarketUnlist);
    server.createContext("/api/market/pause", this::handleMarketPause);
    server.createContext("/api/market/resume", this::handleMarketResume);
    server.createContext("/api/market/price", this::handleMarketPrice);
    server.createContext("/api/market/remark", this::handleMarketRemark);
    server.createContext("/api/market/settings", this::handleMarketSettings);
    server.createContext("/api/market/supply/refresh", this::handleMarketSupplyRefresh);
    server.createContext("/api/admin/auth/login", this::handleAdminLogin);
    server.createContext("/api/admin/auth/me", this::handleAdminMe);
    server.createContext("/api/admin/auth/logout", this::handleAdminLogout);
    server.createContext("/api/admin/redeem/create", this::handleAdminRedeemCreate);
    server.createContext("/api/admin/redeem/list", this::handleAdminRedeemList);
    server.createContext("/api/admin/products/list", this::handleAdminProductsList);
    server.createContext("/api/admin/products/upsert", this::handleAdminProductsUpsert);
    server.createContext("/api/admin/products/active", this::handleAdminProductsActive);
    server.createContext("/api/admin/products/reset-limit", this::handleAdminProductsResetLimit);
    server.createContext("/api/admin/group-buy/consume", this::handleAdminGroupBuyConsume);
    server.createContext("/api/admin/orders/list", this::handleAdminOrdersList);
    server.createContext("/api/admin/economy/settings", this::handleAdminEconomySettings);
    server.createContext("/api/admin/economy/exchange", this::handleAdminExchangeUpdate);
    server.createContext("/api/admin/economy/market", this::handleAdminMarketEconomyUpdate);
    server.createContext("/api/admin/market/listings", this::handleAdminMarketListings);
    server.createContext("/api/admin/market/unlist", this::handleAdminMarketUnlist);
    server.createContext("/api/admin/users/lookup", this::handleAdminUserLookup);
    server.createContext("/api/admin/users/list", this::handleAdminUsersList);
    server.createContext("/api/admin/users/reset-password", this::handleAdminResetPassword);
    server.createContext("/api/admin/users/unbind", this::handleAdminUnbind);
    server.createContext("/api/admin/users/logout", this::handleAdminForceLogout);
    server.createContext("/api/admin/users/wallet-adjust", this::handleAdminWalletAdjust);
    server.createContext("/api/admin/audit/list", this::handleAdminAuditList);
    server.createContext("/api/admin/admin-users/meta", this::handleAdminUsersMeta);
    server.createContext("/api/admin/admin-users/list", this::handleAdminAdminUsersList);
    server.createContext("/api/admin/admin-users/upsert", this::handleAdminAdminUsersUpsert);
    server.createContext("/api/admin/admin-users/active", this::handleAdminAdminUsersActive);

    // Only serve static files in INTERNAL mode
    if (serverMode == PluginSettings.ServerMode.INTERNAL) {
      server.createContext("/", this::handleStatic);
    }

    server.start();
    plugin.getLogger().info("Embedded HTTP server started at " + webSettings.host() + ":"
        + webSettings.port() + " (mode: " + serverMode + ")");
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
      response.addProperty("timeZone", settingsSupplier.get().timeZone().getId());
      sendJson(exchange, 200, response);
    });
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
        row.addProperty("itemMaterial", listing.itemMaterial());
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
      sendJson(exchange, 200, response);
    });
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
      String remark = getOptionalString(payload, "remark").orElse(null);
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
        Long auctionStartPrice = payload.has("auctionStartPrice") && !payload.get("auctionStartPrice").isJsonNull()
          ? getLong(payload, "auctionStartPrice", 0L)
          : null;
        Long auctionMinIncrement = payload.has("auctionMinIncrement") && !payload.get("auctionMinIncrement").isJsonNull()
          ? getLong(payload, "auctionMinIncrement", 0L)
          : null;
        LocalDateTime auctionEndAt = getOptionalDateTime(payload, "auctionEndAt");
      MarketService.ListingSettingsUpdateResult result = marketService.updateListingSettings(
          user.id(),
          listingId,
          price,
          currency,
          remark,
          supplyBatchSize,
          supplyMaxStock,
          tradeMode,
          dynamicPricingEnabled,
          dynamicBasePrice,
          dynamicFloorPrice,
          dynamicCapPrice,
          dynamicPriceStep,
          auctionStartPrice,
          auctionMinIncrement,
          auctionEndAt);
      JsonObject response = new JsonObject();
      response.addProperty("listingId", result.listingId());
      response.addProperty("currency", result.currency().name());
      response.addProperty("price", result.price());
      response.addProperty("sourceMode", result.sourceMode().name());
        response.addProperty("tradeMode", result.tradeMode().name());
      response.addProperty("quantityTotal", result.quantityTotal());
        response.addProperty("dynamicPricingEnabled", result.dynamicPricingEnabled());
        response.addProperty("dynamicDemandScore", result.dynamicDemandScore());
      if (result.remark() == null) {
        response.add("remark", JsonNull.INSTANCE);
      } else {
        response.addProperty("remark", result.remark());
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
      ProductService.AdminProductInput input = new ProductService.AdminProductInput(
          getString(payload, "sku"),
          getString(payload, "title"),
          getOptionalString(payload, "remark").orElse(null),
          CurrencyType.fromConfig(getString(payload, "currency")),
          getLong(payload, "price", 0L),
          getOptionalString(payload, "productType").orElse("COMMAND"),
          getOptionalString(payload, "commandTemplate").orElse(""),
          getOptionalString(payload, "itemMaterial").orElse(null),
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
      response.add("currency", currencyJson);
      response.add("vault", vaultJson);
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
      double shopRatio = getDouble(payload, "shopToGameRatio");
      boolean gameEnabled = getBoolean(payload, "gameToShopEnabled");
      double gameRatio = getDouble(payload, "gameToShopRatio");

      updateConfig(config -> {
        config.set("exchange.shopcoin-to-gamecoin.enabled", shopEnabled);
        config.set("exchange.shopcoin-to-gamecoin.ratio", Math.max(0.0, shopRatio));
        config.set("exchange.gamecoin-to-shopcoin.enabled", gameEnabled);
        config.set("exchange.gamecoin-to-shopcoin.ratio", Math.max(0.0, gameRatio));
      });

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
      double fee = getDouble(payload, "tradeFeePercent");
      double tax = getDouble(payload, "tradeTaxPercent");

      updateConfig(config -> {
        config.set("economy.market.trade-fee-percent", clampPercent(fee));
        config.set("economy.market.trade-tax-percent", clampPercent(tax));
      });

      JsonObject detail = new JsonObject();
      detail.addProperty("tradeFeePercent", fee);
      detail.addProperty("tradeTaxPercent", tax);
      adminAuditService.log(admin, "MARKET_ECONOMY_UPDATE", "market", null, detail, clientIp(exchange));

      JsonObject response = new JsonObject();
      response.addProperty("status", "ok");
      sendJson(exchange, 200, response);
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
        row.addProperty("itemMaterial", listing.itemMaterial());
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

  private void handleAdminUsersMeta(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "GET")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      requireSuperAdmin(exchange, null);
      JsonObject response = new JsonObject();
      JsonArray groups = new JsonArray();
      for (AdminService.PermissionGroup group : adminService.listPermissionGroups()) {
        JsonObject groupJson = new JsonObject();
        groupJson.addProperty("key", group.key());
        groupJson.addProperty("label", group.label());
        JsonArray permissions = new JsonArray();
        for (AdminService.PermissionDefinition permission : group.permissions()) {
          JsonObject item = new JsonObject();
          item.addProperty("code", permission.code());
          item.addProperty("label", permission.label());
          item.addProperty("description", permission.description());
          permissions.add(item);
        }
        groupJson.add("permissions", permissions);
        groups.add(groupJson);
      }
      JsonArray templates = new JsonArray();
      for (AdminService.PermissionTemplate template : adminService.listPermissionTemplates()) {
        JsonObject item = new JsonObject();
        item.addProperty("key", template.key());
        item.addProperty("label", template.label());
        item.addProperty("description", template.description());
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
    if (relativePath.contains("..")) {
      sendJson(exchange, 400, errorJson("bad_request", "Invalid static path"));
      return;
    }

    Path targetFile = staticRoot.resolve(relativePath).normalize();
    if (!targetFile.startsWith(staticRoot) || !Files.isRegularFile(targetFile)) {
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
    addBusinessDateTime(row, "publishAt", product.publishAt());
    addBusinessDateTime(row, "unpublishAt", product.unpublishAt());
    if (product.itemMaterial() == null) {
      row.add("itemMaterial", JsonNull.INSTANCE);
    } else {
      row.addProperty("itemMaterial", product.itemMaterial());
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
    return response;
  }

  private boolean ensureMethod(HttpExchange exchange, String method) throws IOException {
    if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
      sendJson(exchange, 405, errorJson("method_not_allowed", "Method not allowed"));
      return false;
    }
    return true;
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

  private void updateConfig(java.util.function.Consumer<org.bukkit.configuration.file.FileConfiguration> updater) {
    org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
    updater.accept(config);
    plugin.saveConfig();
    if (plugin instanceof WebShopPlugin webShopPlugin) {
      org.bukkit.Bukkit.getScheduler().runTask(plugin, webShopPlugin::reloadRuntimeConfig);
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
}
