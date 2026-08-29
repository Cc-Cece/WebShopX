package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

class RelayRpcRouter {
  private static final Set<String> ALLOWED_METHODS = Set.of(
      "health.check",
      "server.info",
      "auth.login",
      "auth.me",
      "auth.logout",
      "products.list",
      "orders.place",
      "orders.list",
      "admin.auth.login",
      "admin.auth.me",
      "admin.relay.status",
      "admin.products.list",
      "admin.orders.list",
      "asset.get",
      "http.allowed");

  private final JavaPlugin plugin;
  private final Supplier<PluginSettings> settingsSupplier;
  private final Supplier<RelayStatus> relayStatusSupplier;
  private final AuthService authService;
  private final ProductService productService;
  private final OrderService orderService;
  private final AdminService adminService;
  private final AdminAuditService adminAuditService;
  private final VisualCustomizationService visualCustomizationService;
  private final VisualPackService visualPackService;
  private final Path staticRoot;
  private final Path webUserRoot;
  private final RelayLocalHttpBridge localHttpBridge;

  RelayRpcRouter(
      JavaPlugin plugin,
      Supplier<PluginSettings> settingsSupplier,
      Supplier<RelayStatus> relayStatusSupplier,
      AuthService authService,
      ProductService productService,
      OrderService orderService,
      AdminService adminService,
      AdminAuditService adminAuditService,
      VisualCustomizationService visualCustomizationService,
      VisualPackService visualPackService,
      Path staticRoot,
      Path webUserRoot,
      RelayLocalHttpBridge localHttpBridge) {
    this.plugin = plugin;
    this.settingsSupplier = settingsSupplier;
    this.relayStatusSupplier = relayStatusSupplier;
    this.authService = authService;
    this.productService = productService;
    this.orderService = orderService;
    this.adminService = adminService;
    this.adminAuditService = adminAuditService;
    this.visualCustomizationService = visualCustomizationService;
    this.visualPackService = visualPackService;
    this.staticRoot = staticRoot == null ? null : staticRoot.toAbsolutePath().normalize();
    this.webUserRoot = webUserRoot == null ? null : webUserRoot.toAbsolutePath().normalize();
    this.localHttpBridge = localHttpBridge;
  }

  RelayRpcResponse route(RelayRpcRequest request) {
    String id = request == null ? "" : request.id();
    try {
      if (request == null || request.method() == null || request.method().isBlank()) {
        return RelayRpcResponse.error(id, 400, "bad_request", "RPC method is required");
      }
      if (!ALLOWED_METHODS.contains(request.method())) {
        return RelayRpcResponse.error(id, 404, "unknown_method", "RPC method is not allowed");
      }
      if ("http.allowed".equals(request.method())) {
        return localHttpBridge.forward(request);
      }
      if ("asset.get".equals(request.method())) {
        return readAsset(request);
      }
      JsonObject payload = switch (request.method()) {
        case "health.check" -> health();
        case "server.info" -> serverInfo();
        case "auth.login" -> login(request.payload());
        case "auth.me" -> userMe(request);
        case "auth.logout" -> logout(request);
        case "products.list" -> products(request);
        case "orders.place" -> placeOrder(request);
        case "orders.list" -> listOrders(request);
        case "admin.auth.login" -> adminLogin(request.payload());
        case "admin.auth.me" -> adminMe(request);
        case "admin.relay.status" -> adminRelayStatus(request);
        case "admin.products.list" -> adminProductsList(request);
        case "admin.orders.list" -> adminOrdersList(request);
        default -> throw new ServiceException("unknown_method", "RPC method is not allowed");
      };
      return RelayRpcResponse.ok(id, 200, payload);
    } catch (ServiceException exception) {
      return RelayRpcResponse.error(id, statusFor(exception.code()), exception.code(), exception.getMessage());
    } catch (Exception exception) {
      plugin.getLogger().log(java.util.logging.Level.WARNING, "Relay RPC failed", exception);
      return RelayRpcResponse.error(id, 500, "internal_error", "Server internal error");
    }
  }

  private JsonObject health() {
    JsonObject response = new JsonObject();
    response.addProperty("status", "ok");
    response.addProperty("time", LocalDateTime.now().toString());
    response.addProperty("deploymentMode", settingsSupplier.get().deploymentMode().configValue());
    return response;
  }

  private JsonObject serverInfo() {
    JsonObject response = new JsonObject();
    response.addProperty("status", "ok");
    response.addProperty("pluginVersion", plugin.getDescription().getVersion());
    response.addProperty("minecraftVersion", resolveMinecraftVersion());
    response.addProperty("serverName", Bukkit.getServer().getName());
    response.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
    response.addProperty("maxPlayers", Bukkit.getMaxPlayers());
    response.addProperty("deploymentMode", settingsSupplier.get().deploymentMode().configValue());
    response.add("relay", relayStatusJson(relayStatusSupplier.get()));
    return response;
  }

  private RelayRpcResponse readAsset(RelayRpcRequest request) {
    String id = request == null ? "" : request.id();
    String path = request == null || request.http() == null ? null : request.http().path();
    try {
      byte[] visualPackAsset = readVisualPackAsset(path).orElse(null);
      if (visualPackAsset != null) {
        JsonObject response = new JsonObject();
        response.addProperty("path", normalizeAssetPath(path));
        response.addProperty("contentType", "image/png");
        response.addProperty("bodyBase64", Base64.getEncoder().encodeToString(visualPackAsset));
        response.addProperty("sizeBytes", visualPackAsset.length);
        response.addProperty("etag", "\"" + hexDigest(visualPackAsset) + "\"");
        response.addProperty("cacheSeconds", 31536000);
        return RelayRpcResponse.ok(id, 200, response);
      }
      Path asset = resolveRelayAsset(path);
      if (asset == null) {
        return RelayRpcResponse.error(id, 404, "not_found", "Asset not found");
      }
      long size = Files.size(asset);
      if (size > 3L * 1024L * 1024L) {
        return RelayRpcResponse.error(id, 413, "asset_too_large", "Asset is too large");
      }
      byte[] content = Files.readAllBytes(asset);
      long modifiedMillis = Files.getLastModifiedTime(asset).toMillis();
      JsonObject response = new JsonObject();
      response.addProperty("path", normalizeAssetPath(path));
      response.addProperty("contentType", assetContentType(asset));
      response.addProperty("bodyBase64", Base64.getEncoder().encodeToString(content));
      response.addProperty("sizeBytes", content.length);
      response.addProperty("lastModified", java.time.Instant.ofEpochMilli(modifiedMillis).toString());
      response.addProperty("etag", "W/\"" + content.length + "-" + modifiedMillis + "\"");
      response.addProperty("cacheSeconds", 86400);
      return RelayRpcResponse.ok(id, 200, response);
    } catch (ServiceException exception) {
      return RelayRpcResponse.error(id, statusFor(exception.code()), exception.code(), exception.getMessage());
    } catch (Exception exception) {
      plugin.getLogger().log(java.util.logging.Level.WARNING, "Relay asset read failed", exception);
      return RelayRpcResponse.error(id, 500, "asset_read_failed", "Failed to read asset");
    }
  }

  private Optional<byte[]> readVisualPackAsset(String rawPath) {
    String relative = normalizeAssetPath(rawPath);
    if (!relative.toLowerCase(Locale.ROOT).startsWith("visual-packs/")) {
      return Optional.empty();
    }
    String assetPath = relative.substring("visual-packs/".length());
    String[] parts = assetPath.split("/", 3);
    if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
      throw new ServiceException("bad_request", "Invalid visual pack asset path");
    }
    return visualPackService.readAsset(parts[0], parts[1], parts[2]);
  }

  private String hexDigest(byte[] content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      StringBuilder value = new StringBuilder(digest.length * 2);
      for (byte part : digest) {
        value.append(String.format("%02x", part));
      }
      return value.toString();
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private Path resolveRelayAsset(String rawPath) throws IOException {
    String relativePath = normalizeAssetPath(rawPath);
    if (relativePath.toLowerCase(Locale.ROOT).startsWith("visual-packs/")) {
      return materializeVisualPackAsset(relativePath);
    }
    if (!isRelayAssetPathAllowed(relativePath)) {
      throw new ServiceException("bad_request", "Asset path is not allowed");
    }
    Path userCandidate = resolveUnderRoot(webUserRoot, relativePath);
    if (userCandidate != null && Files.isRegularFile(userCandidate)) {
      return userCandidate;
    }
    Path staticCandidate = resolveUnderRoot(staticRoot, relativePath);
    if (staticCandidate != null && Files.isRegularFile(staticCandidate)) {
      return staticCandidate;
    }
    return null;
  }

  private Path materializeVisualPackAsset(String relativePath) throws IOException {
    String[] parts = relativePath.substring("visual-packs/".length()).split("/", 3);
    if (parts.length != 3 || visualPackService == null) {
      return null;
    }
    Optional<byte[]> content = visualPackService.readAsset(parts[0], parts[1], parts[2]);
    if (content.isEmpty()) {
      return null;
    }
    Path cacheRoot = plugin.getDataFolder().toPath().resolve("relay-asset-cache").toAbsolutePath().normalize();
    Path target = cacheRoot.resolve(relativePath).normalize();
    if (!target.startsWith(cacheRoot)) {
      return null;
    }
    Files.createDirectories(target.getParent());
    Files.write(target, content.get());
    return target;
  }

  private String normalizeAssetPath(String rawPath) {
    String normalized = String.valueOf(rawPath == null ? "" : rawPath).trim().replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    try {
      normalized = java.net.URLDecoder.decode(normalized, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("bad_request", "Invalid asset path encoding");
    }
    normalized = normalized.replace('\\', '/');
    if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("..") || normalized.contains("\u0000")) {
      throw new ServiceException("bad_request", "Invalid asset path");
    }
    return normalized;
  }

  private boolean isRelayAssetPathAllowed(String relativePath) {
    String lower = relativePath.toLowerCase(Locale.ROOT);
    boolean allowedPrefix = lower.startsWith("uploads/")
        || lower.startsWith("textures/")
        || lower.startsWith("themes/")
        || lower.startsWith("i18n/")
        || lower.startsWith("docs/")
        || lower.startsWith("visual-packs/");
    if (!allowedPrefix) {
      return false;
    }
    return lower.endsWith(".png")
        || lower.endsWith(".jpg")
        || lower.endsWith(".jpeg")
        || lower.endsWith(".webp")
        || lower.endsWith(".gif")
        || lower.endsWith(".svg")
        || lower.endsWith(".ico")
        || lower.endsWith(".css")
        || lower.endsWith(".json")
        || lower.endsWith(".md")
        || lower.endsWith(".txt")
        || lower.endsWith(".woff")
        || lower.endsWith(".woff2");
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

  private String assetContentType(Path targetFile) {
    Path fileNamePath = targetFile.getFileName();
    if (fileNamePath == null) {
      return "application/octet-stream";
    }
    String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
    if (fileName.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (fileName.endsWith(".json")) {
      return "application/json; charset=utf-8";
    }
    if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    if (fileName.endsWith(".png")) {
      return "image/png";
    }
    if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (fileName.endsWith(".webp")) {
      return "image/webp";
    }
    if (fileName.endsWith(".gif")) {
      return "image/gif";
    }
    if (fileName.endsWith(".svg")) {
      return "image/svg+xml; charset=utf-8";
    }
    if (fileName.endsWith(".ico")) {
      return "image/x-icon";
    }
    if (fileName.endsWith(".woff")) {
      return "font/woff";
    }
    if (fileName.endsWith(".woff2")) {
      return "font/woff2";
    }
    return "application/octet-stream";
  }

  private JsonObject login(JsonObject payload) {
    String identifier = getOptionalString(payload, "identifier")
        .or(() -> getOptionalString(payload, "username"))
        .orElseThrow(() -> new ServiceException("bad_request", "Missing field: identifier"));
    AuthService.AuthResult result = authService.login(identifier, getString(payload, "password"));
    return sessionResponse(result);
  }

  private JsonObject userMe(RelayRpcRequest request) {
    return userResponse(requireAuth(request, request.payload()));
  }

  private JsonObject logout(RelayRpcRequest request) {
    String token = requestToken(request, request.payload());
    if (token == null) {
      throw new ServiceException("auth_required", "Missing session token");
    }
    authService.findUserBySession(token)
        .orElseThrow(() -> new ServiceException("auth_invalid", "Session token is invalid or expired"));
    authService.logout(token);
    JsonObject response = new JsonObject();
    response.addProperty("status", "ok");
    return response;
  }

  private JsonObject products(RelayRpcRequest request) {
    Optional<AuthService.AuthUser> user = findOptionalAuth(request);
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
    return response;
  }

  private JsonObject placeOrder(RelayRpcRequest request) {
    JsonObject payload = request.payload() == null ? new JsonObject() : request.payload();
    AuthService.AuthUser user = requireAuth(request, payload);
    long productId = getLong(payload, "productId", -1L);
    int quantity = (int) getLong(payload, "quantity", 1L);
    String deliveryMode = getOptionalString(payload, "deliveryMode").orElse(null);
    String idempotencyKey = getOptionalString(payload, "idempotencyKey")
        .or(() -> Optional.ofNullable(request.idempotencyKey()))
        .map(String::trim)
        .filter(value -> !value.isEmpty() && value.length() <= 128)
        .orElseThrow(() -> new ServiceException(
            "idempotency_key_required", "Idempotency key is required"));
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
    addNullableDateTime(response, "refundDeadline", result.refundDeadline());
    addNullableString(response, "groupBuyVoucherCode", result.groupBuyVoucherCode());
    addNullableString(response, "groupBuyVoucherStatus", result.groupBuyVoucherStatus());
    addNullableDateTime(response, "groupBuyVoucherConsumedAt", result.groupBuyVoucherConsumedAt());
    return response;
  }

  private JsonObject listOrders(RelayRpcRequest request) {
    AuthService.AuthUser user = requireAuth(request, null);
    Map<String, String> query = request.http() == null ? Map.of() : request.http().query();
    int limit = parseInt(query.get("limit"), 30);
    Long cursor = parseLong(query.get("cursor"));
    List<OrderService.OrderView> orders = orderService.listOrdersForUser(user.id(), limit, cursor);
    JsonArray array = new JsonArray();
    LocalDateTime now = LocalDateTime.now();
    for (OrderService.OrderView order : orders) {
      JsonObject row = orderJson(order);
      row.addProperty("canRefund", canRefund(order, now));
      array.add(row);
    }
    JsonObject response = new JsonObject();
    response.add("orders", array);
    response.addProperty("cooldownSeconds", settingsSupplier.get().orderCooldownSeconds());
    response.addProperty("refundUndeliveredEnabled", settingsSupplier.get().refundUndeliveredEnabled());
    response.addProperty("sharedClaimAllowed", settingsSupplier.get().allowSharedClaimCommand());
    return response;
  }

  private JsonObject adminLogin(JsonObject payload) {
    String identifier = getOptionalString(payload, "identifier")
        .or(() -> getOptionalString(payload, "username"))
        .orElseThrow(() -> new ServiceException("bad_request", "Missing field: identifier"));
    AdminService.AdminLoginResult result = adminService.login(identifier, getString(payload, "password"));
    JsonObject response = sessionResponse(result.authResult());
    response.add("admin", adminProfileJson(result.admin()));
    if (adminAuditService != null) {
      adminAuditService.log(result.admin(), "ADMIN_LOGIN", "admin", result.authResult().user().username(), null, "relay");
    }
    return response;
  }

  private JsonObject adminMe(RelayRpcRequest request) {
    AuthService.AuthUser user = requireAuth(request, null);
    return adminProfileJson(adminService.getAdminUser(user));
  }

  private JsonObject adminRelayStatus(RelayRpcRequest request) {
    AuthService.AuthUser user = requireAuth(request, null);
    adminService.requireAdmin(user, null);
    JsonObject response = new JsonObject();
    response.add("relay", relayStatusJson(relayStatusSupplier.get()));
    response.add("server", serverInfo());
    return response;
  }

  private JsonObject adminProductsList(RelayRpcRequest request) {
    AuthService.AuthUser user = requireAuth(request, null);
    AdminService.AdminUser admin = adminService.requireAdmin(user, AdminPermission.PRODUCT_MANAGE);
    Map<String, String> query = request.http() == null ? Map.of() : request.http().query();
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
    if (adminAuditService != null) {
      adminAuditService.log(admin, "PRODUCT_LIST", "product", null, null, "relay");
    }
    return response;
  }

  private JsonObject adminOrdersList(RelayRpcRequest request) {
    AuthService.AuthUser user = requireAuth(request, null);
    AdminService.AdminUser admin = adminService.requireAdmin(user, AdminPermission.ORDER_VIEW);
    Map<String, String> query = request.http() == null ? Map.of() : request.http().query();
    List<OrderService.AdminOrderView> orders = orderService.listOrdersForAdmin(
        parseInt(query.get("limit"), 120),
        parseLong(query.get("cursor")),
        query.get("status"),
        parseLong(query.get("userId")),
        query.get("orderNo"),
        query.get("username"),
        query.get("keyword"),
        query.get("currency"),
        query.get("productType"));
    JsonArray array = new JsonArray();
    for (OrderService.AdminOrderView adminOrder : orders) {
      JsonObject row = orderJson(adminOrder.order());
      row.addProperty("userId", adminOrder.order().userId());
      row.addProperty("username", adminOrder.username());
      addNullableString(row, "boundUuid", adminOrder.boundUuid() == null ? null : adminOrder.boundUuid().toString());
      array.add(row);
    }
    JsonObject response = new JsonObject();
    response.add("orders", array);
    if (adminAuditService != null) {
      adminAuditService.log(admin, "ORDER_LIST", "order", null, null, "relay");
    }
    return response;
  }

  private AuthService.AuthUser requireAuth(RelayRpcRequest request, JsonObject payload) {
    String token = requestToken(request, payload);
    if (token == null) {
      throw new ServiceException("auth_required", "Missing session token");
    }
    return authService.findUserBySession(token)
        .orElseThrow(() -> new ServiceException("auth_invalid", "Session token is invalid or expired"));
  }

  private Optional<AuthService.AuthUser> findOptionalAuth(RelayRpcRequest request) {
    String token = requestToken(request, request == null ? null : request.payload());
    if (token == null) {
      return Optional.empty();
    }
    return authService.findUserBySession(token);
  }

  private String requestToken(RelayRpcRequest request, JsonObject payload) {
    if (request != null && request.auth() != null && request.auth().token() != null && !request.auth().token().isBlank()) {
      return request.auth().token().trim();
    }
    return getOptionalString(payload, "sessionToken").orElse(null);
  }

  private JsonObject sessionResponse(AuthService.AuthResult result) {
    JsonObject response = new JsonObject();
    response.addProperty("sessionToken", result.sessionToken());
    response.addProperty("expiresAt", result.expiresAt().toString());
    response.add("user", userResponse(result.user()));
    response.addProperty("username", result.user().username());
    addNullableString(response, "boundUuid", result.user().boundUuid() == null ? null : result.user().boundUuid().toString());
    return response;
  }

  private JsonObject userResponse(AuthService.AuthUser user) {
    JsonObject response = new JsonObject();
    response.addProperty("id", user.id());
    response.addProperty("username", user.username());
    addNullableString(response, "boundUuid", user.boundUuid() == null ? null : user.boundUuid().toString());
    response.add("visualPermission", resolveUserVisualPermissionJson(user));
    return response;
  }

  private JsonObject resolveUserVisualPermissionJson(AuthService.AuthUser user) {
    JsonObject response = new JsonObject();
    if (visualCustomizationService == null) {
      response.addProperty("canCustomize", false);
      response.addProperty("source", "none");
      response.add("expiresAt", JsonNull.INSTANCE);
      return response;
    }
    VisualCustomizationService.ResolvedPermission permission = visualCustomizationService.resolvePermission(user.id());
    response.addProperty("userId", permission.userId());
    response.addProperty("iconPermission", permission.iconPermission().name());
    response.addProperty("namePermission", permission.namePermission().name());
    response.addProperty("uploadPermission", permission.uploadPermission().name());
    response.addProperty("customIconAllowed", permission.customIconAllowed());
    response.addProperty("customNameAllowed", permission.customNameAllowed());
    response.addProperty("customUploadAllowed", permission.customUploadAllowed());
    response.add("settings", visualSettingsJson(permission.settings()));
    return response;
  }

  private JsonObject visualSettingsJson(VisualCustomizationService.VisualSettings settings) {
    JsonObject json = new JsonObject();
    json.addProperty("globalCustomIconEnabled", settings.globalCustomIconEnabled());
    json.addProperty("globalCustomNameEnabled", settings.globalCustomNameEnabled());
    json.addProperty("officialProductCustomIconEnabled", settings.officialProductCustomIconEnabled());
    json.addProperty("officialProductCustomNameEnabled", settings.officialProductCustomNameEnabled());
    json.addProperty("officialProductUploadImageEnabled", settings.officialProductUploadImageEnabled());
    json.addProperty("marketListingCustomIconEnabled", settings.marketListingCustomIconEnabled());
    json.addProperty("marketListingCustomNameEnabled", settings.marketListingCustomNameEnabled());
    json.addProperty("marketListingUploadImageEnabled", settings.marketListingUploadImageEnabled());
    json.addProperty("iconPolicyMode", settings.iconPolicyMode().name());
    json.addProperty("namePolicyMode", settings.namePolicyMode().name());
    return json;
  }

  private JsonObject adminProfileJson(AdminService.AdminUser admin) {
    JsonObject response = new JsonObject();
    response.addProperty("id", admin.userId());
    response.addProperty("username", admin.username());
    response.addProperty("role", admin.roleLabel());
    response.addProperty("isSuperAdmin", admin.isSuperAdmin());
    response.addProperty("canSetZeroPrice", admin.allows(AdminPermission.PRODUCT_ZERO_PRICE));
    response.addProperty("canManageAdmins", admin.isSuperAdmin());
    addNullableString(response, "templateKey", admin.templateKey());
    addNullableString(response, "boundUuid", admin.boundUuid() == null ? null : admin.boundUuid().toString());
    JsonArray permissions = new JsonArray();
    for (String code : admin.permissionCodes()) {
      permissions.add(code);
    }
    response.add("permissions", permissions);
    return response;
  }

  private void addProductJson(JsonObject row, ProductService.ProductView product, boolean includePersonalLimitRemaining) {
    row.addProperty("id", product.id());
    row.addProperty("sku", product.sku());
    row.addProperty("title", product.title());
    addNullableString(row, "remark", product.remark());
    row.addProperty("currency", product.currency().name());
    row.addProperty("price", product.price());
    row.addProperty("productType", product.productType().name());
    row.addProperty("dynamicPricingEnabled", product.dynamicPricingEnabled());
    row.addProperty("dynamicAlgorithm", product.dynamicAlgorithm());
    addNullableString(row, "dynamicParamsJson", product.dynamicParamsJson());
    addNullableNumber(row, "dynamicBasePrice", product.dynamicBasePrice());
    addNullableNumber(row, "dynamicFloorPrice", product.dynamicFloorPrice());
    addNullableNumber(row, "dynamicCapPrice", product.dynamicCapPrice());
    addNullableNumber(row, "dynamicPriceStep", product.dynamicPriceStep());
    row.addProperty("dynamicDemandScore", product.dynamicDemandScore());
    addBusinessDateTime(row, "publishAt", product.publishAt());
    addBusinessDateTime(row, "unpublishAt", product.unpublishAt());
    addNullableString(row, "itemMaterial", product.itemMaterial());
    addNullableString(row, "displayNameOverride", product.displayNameOverride());
    addNullableString(row, "displayMaterial", product.displayMaterial());
    addNullableString(row, "displayIconPath", product.displayIconPath());
    addNullableNumber(row, "itemAmount", product.itemAmount());
    addNullableNumber(row, "stockRemaining", product.stockRemaining());
    addNullableNumber(row, "perUserLimit", product.perUserLimit());
    if (includePersonalLimitRemaining) {
      addNullableNumber(row, "personalLimitRemaining", product.personalLimitRemaining());
    }
    addNullableString(row, "effectType", product.effectType());
    addNullableNumber(row, "effectSeconds", product.effectSeconds());
    addNullableNumber(row, "effectAmplifier", product.effectAmplifier());
  }

  private JsonObject orderJson(OrderService.OrderView order) {
    JsonObject row = new JsonObject();
    row.addProperty("id", order.id());
    row.addProperty("orderNo", order.orderNo());
    row.addProperty("status", order.status());
    row.addProperty("currency", order.currency().name());
    row.addProperty("totalAmount", order.totalAmount());
    row.addProperty("createdAt", order.createdAt().toString());
    addNullableString(row, "mcUuid", order.mcUuid() == null ? null : order.mcUuid().toString());
    addNullableDateTime(row, "deliveredAt", order.deliveredAt());
    addNullableDateTime(row, "refundedAt", order.refundedAt());
    addNullableDateTime(row, "refundDeadline", order.refundDeadline());
    row.addProperty("sku", order.productSku());
    row.addProperty("productTitle", order.productTitle());
    addNullableString(row, "productRemark", order.productRemark());
    row.addProperty("productType", order.productType());
    addNullableString(row, "itemMaterial", order.itemMaterial());
    addNullableNumber(row, "itemAmount", order.itemAmount());
    addNullableString(row, "effectType", order.effectType());
    addNullableNumber(row, "effectSeconds", order.effectSeconds());
    addNullableNumber(row, "effectAmplifier", order.effectAmplifier());
    row.addProperty("quantity", order.quantity());
    row.addProperty("unitPrice", order.unitPrice());
    addNullableString(row, "groupBuyVoucherCode", order.groupBuyVoucherCode());
    addNullableString(row, "groupBuyVoucherStatus", order.groupBuyVoucherStatus());
    addNullableDateTime(row, "groupBuyVoucherConsumedAt", order.groupBuyVoucherConsumedAt());
    addNullableString(row, "claimToken", order.claimToken());
    return row;
  }

  private JsonObject relayStatusJson(RelayStatus status) {
    JsonObject response = new JsonObject();
    if (status == null) {
      response.addProperty("enabled", false);
      response.addProperty("connected", false);
      return response;
    }
    response.addProperty("enabled", status.enabled());
    response.addProperty("connected", status.connected());
    response.addProperty("endpoint", status.endpoint());
    response.addProperty("serverId", status.serverId());
    addNullableString(response, "connectedAt", status.connectedAt() == null ? null : status.connectedAt().toString());
    addNullableString(response, "lastHeartbeatAt", status.lastHeartbeatAt() == null ? null : status.lastHeartbeatAt().toString());
    response.addProperty("reconnectCount", status.reconnectCount());
    response.addProperty("requestsReceived", status.requestsReceived());
    response.addProperty("requestsSucceeded", status.requestsSucceeded());
    response.addProperty("requestsFailed", status.requestsFailed());
    addNullableString(response, "lastError", status.lastError());
    return response;
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
      return "PENDING".equalsIgnoreCase(order.status()) || "WAIT_CLAIM".equalsIgnoreCase(order.status());
    }
    return "PENDING".equalsIgnoreCase(order.status())
        && order.refundDeadline() != null
        && now.isBefore(order.refundDeadline());
  }

  private void addBusinessDateTime(JsonObject object, String key, LocalDateTime utcDateTime) {
    if (utcDateTime == null) {
      object.add(key, JsonNull.INSTANCE);
      return;
    }
    object.addProperty(key, TimeSupport.formatBusinessIsoOffset(utcDateTime, settingsSupplier.get().timeZone()));
  }

  private void addNullableDateTime(JsonObject object, String key, LocalDateTime value) {
    if (value == null) {
      object.add(key, JsonNull.INSTANCE);
    } else {
      object.addProperty(key, value.toString());
    }
  }

  private void addNullableString(JsonObject object, String key, String value) {
    if (value == null) {
      object.add(key, JsonNull.INSTANCE);
    } else {
      object.addProperty(key, value);
    }
  }

  private void addNullableNumber(JsonObject object, String key, Number value) {
    if (value == null) {
      object.add(key, JsonNull.INSTANCE);
    } else {
      object.addProperty(key, value);
    }
  }

  private String getString(JsonObject payload, String key) {
    JsonElement value = payload == null ? null : payload.get(key);
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
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  private long getLong(JsonObject payload, String key, long fallback) {
    if (payload == null || !payload.has(key)) {
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
    if (raw == null || raw.isBlank()) {
      return false;
    }
    return raw.equals("1") || raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes");
  }

  private int statusFor(String code) {
    if ("auth_required".equals(code) || "auth_invalid".equals(code)) {
      return 401;
    }
    if ("forbidden".equals(code) || "not_admin".equals(code)) {
      return 403;
    }
    if ("unknown_method".equals(code)) {
      return 404;
    }
    return 400;
  }

  private String resolveMinecraftVersion() {
    String version = Bukkit.getServer().getMinecraftVersion();
    if (version != null && !version.isBlank()) {
      return version;
    }
    String bukkitVersion = Bukkit.getServer().getBukkitVersion();
    if (bukkitVersion == null || bukkitVersion.isBlank()) {
      return "unknown";
    }
    int separator = bukkitVersion.indexOf('-');
    return separator <= 0 ? bukkitVersion : bukkitVersion.substring(0, separator);
  }
}
