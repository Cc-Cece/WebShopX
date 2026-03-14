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
import org.bukkit.plugin.java.JavaPlugin;

class EmbeddedWebServer {
  private final JavaPlugin plugin;
  private final Supplier<PluginSettings> settingsSupplier;
  private final AuthService authService;
  private final BindingService bindingService;
  private final WalletService walletService;
  private final RedeemCodeService redeemCodeService;
  private final ProductService productService;
  private final OrderService orderService;
  private final MarketService marketService;
  private final Gson gson;

  private HttpServer server;
  private ExecutorService executorService;
  private Path staticRoot;

  EmbeddedWebServer(
      JavaPlugin plugin,
      Supplier<PluginSettings> settingsSupplier,
      AuthService authService,
      BindingService bindingService,
      WalletService walletService,
      RedeemCodeService redeemCodeService,
      ProductService productService,
      OrderService orderService,
      MarketService marketService) {
    this.plugin = plugin;
    this.settingsSupplier = settingsSupplier;
    this.authService = authService;
    this.bindingService = bindingService;
    this.walletService = walletService;
    this.redeemCodeService = redeemCodeService;
    this.productService = productService;
    this.orderService = orderService;
    this.marketService = marketService;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  void start(Path staticRoot) throws IOException {
    stop();
    this.staticRoot = staticRoot;
    PluginSettings.EmbeddedWebSettings webSettings = settingsSupplier.get().embeddedWebSettings();

    InetSocketAddress address = new InetSocketAddress(webSettings.host(), webSettings.port());
    server = HttpServer.create(address, 0);
    executorService = Executors.newFixedThreadPool(8);
    server.setExecutor(executorService);

    server.createContext("/health", this::handleHealth);
    server.createContext("/api/auth/register", this::handleRegister);
    server.createContext("/api/auth/login", this::handleLogin);
    server.createContext("/api/bind/request", this::handleBindRequest);
    server.createContext("/api/wallet", this::handleWallet);
    server.createContext("/api/wallet/exchange", this::handleExchange);
    server.createContext("/api/redeem/use", this::handleRedeemUse);
    server.createContext("/api/products", this::handleProducts);
    server.createContext("/api/orders", this::handleOrders);
    server.createContext("/api/market/listings", this::handleMarketListings);
    server.createContext("/api/market/buy", this::handleMarketBuy);
    server.createContext("/api/market/unlist", this::handleMarketUnlist);
    server.createContext("/", this::handleStatic);

    server.start();
    plugin.getLogger().info("Embedded HTTP server started at " + webSettings.host() + ":"
        + webSettings.port());
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

  private void handleRegister(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      JsonObject payload = readJson(exchange);
      String username = getString(payload, "username");
      String password = getString(payload, "password");
      AuthService.AuthResult result = authService.register(username, password);
      JsonObject response = sessionResponse(result);
      sendJson(exchange, 200, response);
    });
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
      String username = getString(payload, "username");
      String password = getString(payload, "password");
      AuthService.AuthResult result = authService.login(username, password);
      JsonObject response = sessionResponse(result);
      sendJson(exchange, 200, response);
    });
  }

  private void handleBindRequest(HttpExchange exchange) throws IOException {
    if (isPreflight(exchange)) {
      return;
    }
    if (!ensureMethod(exchange, "POST")) {
      return;
    }
    withServiceHandling(exchange, () -> {
      AuthService.AuthUser user = requireAuth(exchange, null);
      String bindCode = bindingService.createBindRequest(user.id());
      JsonObject response = new JsonObject();
      response.addProperty("bindCode", bindCode);
      response.addProperty("expiresInMinutes", settingsSupplier.get().bindRequestExpireMinutes());
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
      response.addProperty("shopCoin", balance.shopCoin());
      response.addProperty("gameCoin", balance.gameCoin());
      response.addProperty("boundUuid", user.boundUuid() == null ? null : user.boundUuid().toString());
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
      List<ProductService.ProductView> products = productService.listActiveProducts();
      JsonArray array = new JsonArray();
      for (ProductService.ProductView product : products) {
        JsonObject item = new JsonObject();
        item.addProperty("id", product.id());
        item.addProperty("sku", product.sku());
        item.addProperty("title", product.title());
        item.addProperty("currency", product.currency().name());
        item.addProperty("price", product.price());
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
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      OrderService.OrderPlacementResult result = orderService.placeOrder(
          user.id(),
          productId,
          quantity,
          idempotencyKey);
      JsonObject response = new JsonObject();
      response.addProperty("state", result.state().name());
      response.addProperty("orderNo", result.orderNo());
      response.addProperty("currency", result.currency().name());
      response.addProperty("totalAmount", result.totalAmount());
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
      List<MarketService.ListingView> listings;
      if (mineOnly) {
        AuthService.AuthUser user = requireAuth(exchange, null);
        listings = marketService.listOwnListings(user.id(), limit);
      } else {
        listings = marketService.listActiveListings(limit);
      }

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
        row.addProperty("itemMaterial", listing.itemMaterial());
        row.addProperty("itemMetaJson", listing.itemMetaJson());
        row.addProperty("status", listing.status());
        row.addProperty("createdAt", listing.createdAt().toString());
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
      String idempotencyKey = getOptionalString(payload, "idempotencyKey")
          .orElse(UUID.randomUUID().toString());
      MarketService.TradeResult result = marketService.buyListing(user.id(), listingId, idempotencyKey);
      JsonObject response = new JsonObject();
      response.addProperty("state", result.state().name());
      response.addProperty("tradeId", result.tradeId());
      response.addProperty("listingId", result.listingId());
      response.addProperty("currency", result.currency().name());
      response.addProperty("totalPrice", result.totalPrice());
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
    response.addProperty("username", result.user().username());
    if (result.user().boundUuid() == null) {
      response.add("boundUuid", JsonNull.INSTANCE);
    } else {
      response.addProperty("boundUuid", result.user().boundUuid().toString());
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
