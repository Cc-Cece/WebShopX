package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webshopx.core.SharedRouteContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Supplier;

/** HTTP facade for cart, checkout, coupons, memberships and promotion management. */
final class CommerceHttpApi {
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private final AuthService authService;
  private final AdminService adminService;
  private final CartService cartService;
  private final PromotionService promotionService;
  private final CouponService couponService;
  private final MembershipService membershipService;
  private final CheckoutQuoteService quoteService;
  private final CheckoutService checkoutService;
  private final CommerceRefundService refundService;
  private final SharedPromotionService sharedPromotions;
  private final Supplier<PluginSettings> settingsSupplier;
  private final Gson gson = CommerceJson.create();

  CommerceHttpApi(
      DatabaseManager databaseManager,
      AuthService authService,
      AdminService adminService,
      ProductService productService,
      MarketService marketService,
      WalletService walletService,
      OrderService orderService,
      Supplier<PluginSettings> settingsSupplier) {
    this.authService = authService;
    this.adminService = adminService;
    this.sharedPromotions = new SharedPromotionService(databaseManager);
    this.cartService = sharedPromotions.cartService();
    this.promotionService = sharedPromotions.promotionService();
    this.couponService = sharedPromotions.couponService();
    this.membershipService = sharedPromotions.membershipService();
    CommerceCheckoutPort checkoutPort =
        new PaperCommerceCheckoutAdapter(
            databaseManager, productService, marketService, orderService);
    this.quoteService =
        new CheckoutQuoteService(
            databaseManager, cartService, checkoutPort, promotionService, membershipService);
    this.checkoutService =
        new CheckoutService(
            databaseManager,
            cartService,
            quoteService,
            couponService,
            walletService,
            checkoutPort,
            membershipService);
    this.refundService = new CommerceRefundService(databaseManager, walletService, couponService);
    this.settingsSupplier = java.util.Objects.requireNonNull(settingsSupplier, "settingsSupplier");
  }

  void register(HttpServer server) {
    context(server, "/api/cart", this::cart);
    context(server, "/api/cart/lines/add", this::cartAdd);
    context(server, "/api/cart/lines/update", this::cartUpdate);
    context(server, "/api/cart/lines/remove", this::cartRemove);
    context(server, "/api/cart/clear", this::cartClear);
    context(server, "/api/checkout/quote", this::quote);
    context(server, "/api/checkout/submit", this::checkout);
    context(server, "/api/checkouts/refund", this::refund);
    context(server, "/api/coupons/mine", this::couponsMine);
    context(server, "/api/coupons/claim", this::couponClaim);
    context(server, "/api/membership/me", this::membershipMine);
    context(server, "/api/membership/redeem", this::membershipRedeem);
    context(server, "/api/seller/promotions/list", this::sellerPromotions);
    context(server, "/api/seller/promotions/create", this::sellerPromotionCreate);
    context(server, "/api/seller/promotions/action", this::sellerPromotionAction);
    context(server, "/api/admin/promotions/list", this::adminPromotions);
    context(server, "/api/admin/promotions/create", this::adminPromotionCreate);
    context(server, "/api/admin/promotions/publish", this::adminPromotionPublish);
    context(server, "/api/admin/promotions/action", this::adminPromotionAction);
    context(server, "/api/admin/promotions/emergency-stop", this::emergencyStop);
    context(server, "/api/admin/coupons/grant", this::adminCouponGrant);
    context(server, "/api/admin/coupons/templates", this::adminCouponTemplates);
    context(server, "/api/admin/coupons/templates/create", this::adminCouponTemplateCreate);
    context(server, "/api/admin/membership/grant", this::adminMembershipGrant);
    context(server, "/api/admin/membership/revoke", this::adminMembershipRevoke);
    context(server, "/api/admin/membership/plans", this::adminMembershipPlans);
    context(server, "/api/admin/membership/plans/create", this::adminMembershipPlanCreate);
    context(server, "/api/admin/membership/plans/publish", this::adminMembershipPlanPublish);
    context(server, "/api/admin/membership/products/bind", this::adminMembershipProductBind);
    context(server, "/api/admin/membership/codes/create", this::adminMembershipCodeCreate);
  }

  private void context(HttpServer server, String path, Endpoint endpoint) {
    SharedRouteContract.requireDeclared(path);
    server.createContext(path, exchange -> handle(exchange, endpoint));
  }

  private Object cart(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "GET");
    return sharedPromotions.cart(user(exchange).id());
  }

  private Object cartAdd(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return sharedPromotions.addCartLine(
        user(exchange).id(), gson.fromJson(body, SharedPromotionService.CartAdd.class));
  }

  private Object cartUpdate(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return sharedPromotions.updateCartLine(
        user(exchange).id(), gson.fromJson(body, SharedPromotionService.CartUpdate.class));
  }

  private Object cartRemove(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return sharedPromotions.removeCartLine(
        user(exchange).id(), requiredLong(body, "lineId"), requiredLong(body, "expectedVersion"));
  }

  private Object cartClear(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return sharedPromotions.clearCart(user(exchange).id(), requiredLong(body, "expectedVersion"));
  }

  private Object quote(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return quoteService.quote(
        user(exchange).id(), gson.fromJson(body, CheckoutQuoteService.QuoteCommand.class));
  }

  private Object checkout(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return checkoutService.submit(
        user(exchange).id(), gson.fromJson(body, CheckoutService.SubmitCommand.class));
  }

  private Object refund(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return refundService.refund(
        user(exchange).id(),
        requiredString(body, "checkoutNo"),
        requiredLong(body, "lineId"),
        requiredInt(body, "quantity"),
        requiredString(body, "idempotencyKey"));
  }

  private Object couponsMine(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "GET");
    String status = query(exchange, "status");
    return sharedPromotions.coupons(user(exchange).id(), status);
  }

  private Object couponClaim(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return sharedPromotions.claimCoupon(
        user(exchange).id(), requiredLong(body, "templateId"), requiredString(body, "requestId"));
  }

  private Object membershipMine(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "GET");
    return sharedPromotions.memberships(user(exchange).id());
  }

  private Object membershipRedeem(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return sharedPromotions.redeemMembership(
        user(exchange).id(), requiredString(body, "code"), requiredString(body, "requestId"));
  }

  private Object sellerPromotions(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "GET");
    long sellerId = user(exchange).id();
    return sharedPromotions.sellerCampaigns(sellerId);
  }

  private Object sellerPromotionCreate(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    return sharedPromotions.createSellerCampaign(
        user(exchange).id(), gson.fromJson(body, SharedPromotionService.SellerCampaignInput.class));
  }

  private Object sellerPromotionAction(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AuthService.AuthUser seller = user(exchange);
    return sharedPromotions.transitionSellerCampaign(
        seller.id(), requiredLong(body, "campaignId"), requiredString(body, "action"));
  }

  private Object adminPromotions(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "GET");
    admin(exchange, AdminPermission.PROMOTION_VIEW);
    String ownerType = query(exchange, "ownerType");
    return sharedPromotions.campaigns(ownerType);
  }

  private Object adminPromotionCreate(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.PROMOTION_MANAGE);
    return sharedPromotions.createCampaign(
        admin.userId(), gson.fromJson(body, SharedPromotionService.CampaignInput.class));
  }

  private Object adminPromotionPublish(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.PROMOTION_MANAGE);
    return sharedPromotions.publishCampaign(
        admin.userId(),
        requiredLong(body, "campaignId"),
        requiredLong(body, "expectedVersion"),
        gson.fromJson(
            body.getAsJsonObject("rule"), SharedPromotionService.PromotionRuleInput.class));
  }

  private Object adminPromotionAction(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.PROMOTION_MANAGE);
    return sharedPromotions.transitionCampaign(
        admin.userId(), requiredLong(body, "campaignId"), requiredString(body, "action"));
  }

  private Object emergencyStop(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.PROMOTION_EMERGENCY_STOP);
    return sharedPromotions.emergencyStop(admin.userId());
  }

  private Object adminCouponGrant(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.COUPON_GRANT);
    return sharedPromotions.grantCoupon(
        admin.userId(),
        requiredLong(body, "userId"),
        requiredLong(body, "templateId"),
        requiredString(body, "idempotencyKey"));
  }

  private Object adminCouponTemplates(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "GET");
    admin(exchange, AdminPermission.COUPON_MANAGE);
    return sharedPromotions.couponTemplates();
  }

  private Object adminCouponTemplateCreate(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.COUPON_MANAGE);
    return sharedPromotions.createCouponTemplate(
        admin.userId(), gson.fromJson(body, SharedPromotionService.CouponTemplateInput.class));
  }

  private Object adminMembershipGrant(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.MEMBERSHIP_GRANT);
    return sharedPromotions.grantMembership(
        admin.userId(),
        requiredLong(body, "userId"),
        requiredLong(body, "planVersionId"),
        requiredString(body, "idempotencyKey"));
  }

  private Object adminMembershipRevoke(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.MEMBERSHIP_MANAGE);
    return sharedPromotions.revokeMembership(
        admin.userId(), requiredLong(body, "membershipId"), requiredString(body, "reason"));
  }

  private Object adminMembershipPlans(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "GET");
    admin(exchange, AdminPermission.MEMBERSHIP_VIEW);
    return sharedPromotions.membershipPlans();
  }

  private Object adminMembershipPlanCreate(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.MEMBERSHIP_MANAGE);
    return sharedPromotions.createMembershipPlan(
        admin.userId(), gson.fromJson(body, SharedPromotionService.MembershipPlanInput.class));
  }

  private Object adminMembershipPlanPublish(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.MEMBERSHIP_MANAGE);
    return sharedPromotions.publishMembershipPlan(
        admin.userId(),
        requiredLong(body, "planId"),
        gson.fromJson(
            body.getAsJsonObject("version"), SharedPromotionService.MembershipVersionInput.class));
  }

  private Object adminMembershipProductBind(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.MEMBERSHIP_MANAGE);
    sharedPromotions.bindMembershipProduct(
        admin.userId(), requiredLong(body, "productId"), requiredLong(body, "planVersionId"));
    return java.util.Map.of("status", "ok");
  }

  private Object adminMembershipCodeCreate(HttpExchange exchange, JsonObject body) {
    requireMethod(exchange, "POST");
    AdminService.AdminUser admin = admin(exchange, AdminPermission.MEMBERSHIP_MANAGE);
    Instant validUntil =
        body.has("validUntil") && !body.get("validUntil").isJsonNull()
            ? Instant.parse(body.get("validUntil").getAsString())
            : null;
    return sharedPromotions.createMembershipCode(
        admin.userId(),
        requiredLong(body, "planVersionId"),
        requiredInt(body, "maxUses"),
        validUntil);
  }

  private void handle(HttpExchange exchange, Endpoint endpoint) throws IOException {
    addSecurityHeaders(exchange);
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    try {
      JsonObject body = readBody(exchange);
      send(exchange, 200, endpoint.execute(exchange, body));
    } catch (BodyTooLargeException exception) {
      send(exchange, 413, new ErrorResponse("body_too_large", exception.getMessage()));
    } catch (ServiceException exception) {
      int status =
          switch (exception.code()) {
            case "auth_required", "auth_invalid" -> 401;
            case "admin_forbidden", "promotion_forbidden" -> 403;
            case "quote_missing", "membership_missing" -> 404;
            case "method_not_allowed" -> 405;
            case "CART_VERSION_CONFLICT",
                    "PRICE_CHANGED",
                    "QUOTE_EXPIRED",
                    "QUOTE_INPUT_CHANGED",
                    "IDEMPOTENCY_CONFLICT",
                    "PINNED_RULE_UNAVAILABLE",
                    "PINNED_RULE_NOT_APPLICABLE",
                    "PINNED_RULE_CONFLICT" ->
                409;
            default -> 400;
          };
      send(exchange, status, new ErrorResponse(exception.code(), exception.getMessage()));
    } catch (RuntimeException exception) {
      send(exchange, 500, new ErrorResponse("internal_error", "Internal server error"));
    }
  }

  private AuthService.AuthUser user(HttpExchange exchange) {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new ServiceException("auth_required", "Missing session token");
    }
    String token = authorization.substring(7).trim();
    return authService
        .findUserBySession(token)
        .orElseThrow(
            () -> new ServiceException("auth_invalid", "Session token is invalid or expired"));
  }

  private AdminService.AdminUser admin(HttpExchange exchange, AdminPermission permission) {
    return adminService.requireAdmin(user(exchange), permission);
  }

  private JsonObject readBody(HttpExchange exchange) throws IOException {
    String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
    if (contentLength != null) {
      try {
        if (Long.parseLong(contentLength) > MAX_BODY_BYTES) {
          throw new BodyTooLargeException("Request body exceeds 64 KiB");
        }
      } catch (NumberFormatException exception) {
        throw new ServiceException("bad_request", "Invalid Content-Length");
      }
    }
    byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw new BodyTooLargeException("Request body exceeds 64 KiB");
    }
    if (bytes.length == 0) {
      return new JsonObject();
    }
    return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
  }

  private void send(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void addSecurityHeaders(HttpExchange exchange) {
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange
        .getResponseHeaders()
        .set(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; font-src 'self' data:; "
                + "style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; "
                + "object-src 'none'; base-uri 'self'");
    PluginSettings.EmbeddedWebSettings web = settingsSupplier.get().embeddedWebSettings();
    if (!web.corsEnabled()) {
      return;
    }
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (web.corsAllowedOrigins().contains("*")) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    } else if (origin != null && web.corsAllowedOrigins().contains(origin)) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
      exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
      exchange.getResponseHeaders().add("Vary", "Origin");
    }
    exchange
        .getResponseHeaders()
        .set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  }

  private void requireMethod(HttpExchange exchange, String method) {
    if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
      throw new ServiceException("method_not_allowed", "Method is not allowed");
    }
  }

  private long requiredLong(JsonObject body, String name) {
    if (!body.has(name)) {
      throw new ServiceException("bad_request", "Missing field: " + name);
    }
    return body.get(name).getAsLong();
  }

  private int requiredInt(JsonObject body, String name) {
    return Math.toIntExact(requiredLong(body, name));
  }

  private String requiredString(JsonObject body, String name) {
    if (!body.has(name) || body.get(name).isJsonNull() || body.get(name).getAsString().isBlank()) {
      throw new ServiceException("bad_request", "Missing field: " + name);
    }
    return body.get(name).getAsString();
  }

  private String query(HttpExchange exchange, String name) {
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null) {
      return null;
    }
    for (String pair : raw.split("&")) {
      String[] parts = pair.split("=", 2);
      if (parts[0].equals(name)) {
        return parts.length == 1
            ? ""
            : java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  @FunctionalInterface
  private interface Endpoint {
    Object execute(HttpExchange exchange, JsonObject body);
  }

  private record ErrorResponse(String code, String message) {}

  private static final class BodyTooLargeException extends RuntimeException {
    private BodyTooLargeException(String message) {
      super(message);
    }
  }
}
