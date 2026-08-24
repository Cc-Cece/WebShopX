package com.webshopx.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webshopx.AdminAuditService;
import com.webshopx.AdminPermission;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.CommerceJson;
import com.webshopx.CurrencyType;
import com.webshopx.NotificationService;
import com.webshopx.RedeemCodeService;
import com.webshopx.ServiceException;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedCommerceService.ProductInput;
import com.webshopx.SharedCommerceService.ProductKind;
import com.webshopx.SharedCommerceService.PurchaseRequest;
import com.webshopx.SharedPromotionService;
import com.webshopx.WalletService;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small platform-neutral HTTP surface used by dedicated-server Loader distributions. */
public final class SharedHttpApi implements AutoCloseable {
  private static final int MAX_BODY = 64 * 1024;
  private final AuthService auth;
  private final WalletService wallets;
  private final SharedCommerceService commerce;
  private final SharedPromotionService promotions;
  private final RedeemCodeService redeemCodes;
  private final NotificationService notifications;
  private final AdminService administration;
  private final AdminAuditService audit;
  private final PlatformIdentity identity;
  private final CapabilitySnapshot capabilities;
  private final String allowedOrigin;
  private final Gson gson = CommerceJson.create();
  private final ExecutorService executor;
  private final HttpServer server;

  public SharedHttpApi(
      String host,
      int port,
      String allowedOrigin,
      AuthService auth,
      WalletService wallets,
      SharedCommerceService commerce,
      SharedPromotionService promotions,
      RedeemCodeService redeemCodes,
      NotificationService notifications,
      AdminService administration,
      AdminAuditService audit,
      PlatformIdentity identity,
      CapabilitySnapshot capabilities) {
    this.auth = Objects.requireNonNull(auth, "auth");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.commerce = Objects.requireNonNull(commerce, "commerce");
    this.promotions = Objects.requireNonNull(promotions, "promotions");
    this.redeemCodes = Objects.requireNonNull(redeemCodes, "redeemCodes");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
    this.administration = Objects.requireNonNull(administration, "administration");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.allowedOrigin = allowedOrigin == null ? "" : allowedOrigin.trim();
    try {
      server = HttpServer.create(new InetSocketAddress(host, port), 64);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot bind WebShopX HTTP API", failure);
    }
    executor =
        Executors.newFixedThreadPool(
            Math.max(2, Math.min(16, Integer.getInteger("webshopx.http.threads", 4))),
            runnable -> {
              Thread thread = new Thread(runnable, "webshopx-http");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(executor);
    server.createContext("/", this::dispatch);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    try {
      applySecurityHeaders(exchange);
      if ("OPTIONS".equals(exchange.getRequestMethod())) {
        respond(exchange, 204, Map.of());
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/health") && method(exchange, "GET")) {
        respond(
            exchange,
            200,
            Map.of(
                "status",
                "UP",
                "platform",
                identity,
                "capabilities",
                Map.of(
                    "capturedAt",
                    capabilities.capturedAt().toString(),
                    "states",
                    capabilities.states())));
      } else if (path.equals("/config.js") && method(exchange, "GET")) {
        byte[] script =
            ("window.WEBSHOPX_CONFIG=Object.assign({},window.WEBSHOPX_CONFIG||{},"
                    + "{apiBaseUrl:'/api',serverMode:'INTERNAL',locale:{preferApi:true,"
                    + "fallbackLocale:'zh-CN'}});")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
        exchange.sendResponseHeaders(200, script.length);
        exchange.getResponseBody().write(script);
        exchange.close();
      } else if (!path.startsWith("/api/") && method(exchange, "GET")) {
        serveStatic(exchange, path);
      } else if (path.equals("/api/auth/login") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var result =
            auth.login(requiredString(input, "identifier"), requiredString(input, "password"));
        respond(
            exchange,
            200,
            Map.of(
                "token",
                result.sessionToken(),
                "expiresAt",
                result.expiresAt().toString(),
                "user",
                result.user()));
      } else if (path.equals("/api/auth/me") && method(exchange, "GET")) {
        respond(exchange, 200, user(exchange));
      } else if (path.equals("/api/auth/logout") && method(exchange, "POST")) {
        auth.logout(token(exchange));
        respond(exchange, 200, Map.of("ok", true));
      } else if (path.equals("/api/wallet") && method(exchange, "GET")) {
        var user = user(exchange);
        respond(exchange, 200, wallets.getBalance(user.id()));
      } else if (path.equals("/api/wallet/ledger") && method(exchange, "GET")) {
        var user = user(exchange);
        respond(
            exchange,
            200,
            wallets.listRecentLedger(user.id(), queryInt(exchange, "limit", 20)).stream()
                .map(
                    entry ->
                        Map.of(
                            "currency",
                            entry.currency(),
                            "delta",
                            entry.delta(),
                            "bizType",
                            entry.bizType(),
                            "bizId",
                            entry.bizId(),
                            "createdAt",
                            entry.createdAt().toString()))
                .toList());
      } else if (path.equals("/api/wallet/exchange") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            wallets.exchange(
                current.id(),
                currency(input, "from"),
                currency(input, "to"),
                requiredLong(input, "amount"),
                requiredString(input, "idempotencyKey")));
      } else if (path.equals("/api/redeem/use") && method(exchange, "POST")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            redeemCodes.redeem(current.id(), requiredString(body(exchange), "code")));
      } else if (path.equals("/api/products") && method(exchange, "GET")) {
        respond(exchange, 200, commerce.products(false));
      } else if (path.equals("/api/orders") && method(exchange, "POST")) {
        var user = user(exchange);
        if (user.boundUuid() == null) throw new ServiceException("not_bound", "User is not bound");
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            commerce.purchase(
                new PurchaseRequest(
                    user.id(),
                    user.boundUuid(),
                    requiredLong(input, "productId"),
                    optionalInt(input, "quantity", 1),
                    requiredString(input, "idempotencyKey"),
                    identity.serverId())));
      } else if (path.equals("/api/deliveries") && method(exchange, "GET")) {
        var user = user(exchange);
        if (user.boundUuid() == null) throw new ServiceException("not_bound", "User is not bound");
        respond(exchange, 200, commerce.pendingDeliveries(user.boundUuid(), identity.serverId()));
      } else if (path.equals("/api/mailbox/list") && method(exchange, "GET")) {
        var current = boundUser(exchange);
        respond(
            exchange, 200, commerce.pendingDeliveries(current.boundUuid(), identity.serverId()));
      } else if (path.equals("/api/mailbox/count") && method(exchange, "GET")) {
        var current = boundUser(exchange);
        respond(
            exchange,
            200,
            Map.of(
                "count",
                commerce.pendingDeliveries(current.boundUuid(), identity.serverId()).size()));
      } else if (path.equals("/api/market/listings") && method(exchange, "GET")) {
        respond(exchange, 200, commerce.listings(false));
      } else if (path.equals("/api/market/listings/create") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        if (!input.has("item") || !input.get("item").isJsonObject()) {
          throw new IllegalArgumentException("item is required");
        }
        ItemEnvelope item = gson.fromJson(input.get("item"), ItemEnvelope.class);
        respond(
            exchange,
            200,
            commerce.createListing(
                new SharedCommerceService.ListingRequest(
                    current.id(),
                    current.boundUuid(),
                    currency(input, "currency"),
                    requiredLong(input, "price"),
                    optionalInt(input, "quantity", item.count()),
                    item,
                    optionalString(input, "remark", null))));
      } else if (path.equals("/api/market/buy") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            commerce.buyListing(
                new SharedCommerceService.MarketBuyRequest(
                    current.id(),
                    current.boundUuid(),
                    requiredLong(input, "listingId"),
                    optionalInt(input, "quantity", 1),
                    requiredString(input, "idempotencyKey"),
                    identity.serverId())));
      } else if (path.equals("/api/recharge/create") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            commerce.createRecharge(
                new SharedCommerceService.RechargeRequest(
                    current.id(),
                    current.boundUuid(),
                    requiredLong(input, "amountMinor"),
                    requiredString(input, "currency"),
                    requiredLong(input, "coinAmount"),
                    requiredString(input, "provider"),
                    requiredString(input, "idempotencyKey"),
                    optionalString(input, "description", "WebShopX recharge"))));
      } else if (path.equals("/api/recharge/status") && method(exchange, "GET")) {
        user(exchange);
        respond(exchange, 200, commerce.rechargeByOrder(requiredQuery(exchange, "orderId")));
      } else if (path.equals("/api/notifications/list") && method(exchange, "GET")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            notifications.listForUser(
                current.id(),
                queryInt(exchange, "limit", 30),
                queryLong(exchange, "cursor"),
                queryBoolean(exchange, "unreadOnly", false)));
      } else if (path.equals("/api/notifications/unread-count") && method(exchange, "GET")) {
        var current = user(exchange);
        respond(exchange, 200, Map.of("count", notifications.countUnread(current.id())));
      } else if (path.equals("/api/notifications/mark-read") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        int changed =
            input.has("all") && input.get("all").getAsBoolean()
                ? notifications.markAllRead(current.id())
                : notifications.markRead(current.id(), requiredLong(input, "notificationId"));
        respond(exchange, 200, Map.of("updated", changed));
      } else if (path.equals("/api/cart") && method(exchange, "GET")) {
        respond(exchange, 200, promotions.cart(user(exchange).id()));
      } else if (path.equals("/api/cart/lines/add") && method(exchange, "POST")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            promotions.addCartLine(
                current.id(), gson.fromJson(body(exchange), SharedPromotionService.CartAdd.class)));
      } else if (path.equals("/api/cart/lines/update") && method(exchange, "POST")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            promotions.updateCartLine(
                current.id(),
                gson.fromJson(body(exchange), SharedPromotionService.CartUpdate.class)));
      } else if (path.equals("/api/cart/lines/remove") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.removeCartLine(
                current.id(),
                requiredLong(input, "lineId"),
                requiredLong(input, "expectedVersion")));
      } else if (path.equals("/api/cart/clear") && method(exchange, "POST")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            promotions.clearCart(current.id(), requiredLong(body(exchange), "expectedVersion")));
      } else if (path.equals("/api/checkout/quote") && method(exchange, "POST")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            promotions.quote(
                current.id(),
                gson.fromJson(body(exchange), SharedPromotionService.CheckoutQuoteInput.class)));
      } else if (path.equals("/api/checkout/submit") && method(exchange, "POST")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            promotions.checkout(
                current.id(),
                gson.fromJson(body(exchange), SharedPromotionService.CheckoutSubmitInput.class)));
      } else if (path.equals("/api/checkouts/refund") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.refund(
                current.id(),
                requiredString(input, "checkoutNo"),
                requiredLong(input, "lineId"),
                Math.toIntExact(requiredLong(input, "quantity")),
                requiredString(input, "idempotencyKey")));
      } else if (path.equals("/api/coupons/mine") && method(exchange, "GET")) {
        respond(exchange, 200, promotions.coupons(user(exchange).id(), query(exchange, "status")));
      } else if (path.equals("/api/coupons/claim") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.claimCoupon(
                current.id(),
                requiredLong(input, "templateId"),
                requiredString(input, "requestId")));
      } else if (path.equals("/api/membership/me") && method(exchange, "GET")) {
        respond(exchange, 200, promotions.memberships(user(exchange).id()));
      } else if (path.equals("/api/membership/redeem") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.redeemMembership(
                current.id(), requiredString(input, "code"), requiredString(input, "requestId")));
      } else if (path.equals("/api/seller/promotions/list") && method(exchange, "GET")) {
        respond(exchange, 200, promotions.sellerCampaigns(user(exchange).id()));
      } else if (path.equals("/api/seller/promotions/create") && method(exchange, "POST")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            promotions.createSellerCampaign(
                current.id(),
                gson.fromJson(body(exchange), SharedPromotionService.SellerCampaignInput.class)));
      } else if (path.equals("/api/seller/promotions/action") && method(exchange, "POST")) {
        var current = user(exchange);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.transitionSellerCampaign(
                current.id(), requiredLong(input, "campaignId"), requiredString(input, "action")));
      } else if (path.equals("/api/meta/version") && method(exchange, "GET")) {
        respond(
            exchange,
            200,
            Map.of(
                "platform",
                identity.platform(),
                "minecraft",
                identity.minecraftVersion(),
                "loader",
                identity.loaderVersion(),
                "serverId",
                identity.serverId()));
      } else if (path.equals("/api/meta/locales") && method(exchange, "GET")) {
        respond(exchange, 200, Map.of("default", "zh-CN", "supported", List.of("zh-CN", "en-US")));
      } else if (path.equals("/api/meta/material-overrides") && method(exchange, "GET")) {
        respond(exchange, 200, List.of());
      } else if (path.equals("/api/meta/materials") && method(exchange, "GET")) {
        respond(exchange, 200, List.of());
      } else if (path.equals("/api/meta/market-tags") && method(exchange, "GET")) {
        respond(exchange, 200, List.of());
      } else if (path.equals("/api/meta/currency") && method(exchange, "GET")) {
        respond(exchange, 200, Map.of("currencies", List.of("SHOP_COIN", "GAME_COIN")));
      } else if (path.equals("/api/admin/auth/login") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var result =
            administration.login(
                input.has("identifier")
                    ? requiredString(input, "identifier")
                    : requiredString(input, "username"),
                requiredString(input, "password"));
        respond(
            exchange,
            200,
            Map.of(
                "token",
                result.authResult().sessionToken(),
                "expiresAt",
                result.authResult().expiresAt().toString(),
                "user",
                result.authResult().user(),
                "admin",
                result.admin()));
        audit.log(
            result.admin(),
            "ADMIN_LOGIN",
            "admin",
            result.authResult().user().username(),
            null,
            clientIp(exchange));
      } else if (path.equals("/api/admin/auth/me") && method(exchange, "GET")) {
        respond(exchange, 200, administration.getAdminUser(user(exchange)));
      } else if (path.equals("/api/admin/auth/logout") && method(exchange, "POST")) {
        String sessionToken = token(exchange);
        var current = user(exchange);
        var actor = administration.getAdminUser(current);
        auth.logout(sessionToken);
        audit.log(actor, "ADMIN_LOGOUT", "admin", current.username(), null, clientIp(exchange));
        respond(exchange, 200, Map.of("status", "ok"));
      } else if (path.equals("/api/admin/users/list") && method(exchange, "GET")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.USER_SUPPORT);
        respond(
            exchange,
            200,
            Map.of(
                "users",
                administration
                    .listUsers(query(exchange, "keyword"), queryInt(exchange, "limit", 120))
                    .stream()
                    .map(SharedHttpApi::userJson)
                    .toList()));
      } else if (path.equals("/api/admin/users/lookup") && method(exchange, "GET")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.USER_SUPPORT);
        var found =
            administration
                .lookupUser(requiredQuery(exchange, "identifier"))
                .orElseThrow(() -> new ServiceException("not_found", "User not found"));
        audit.log(
            actor, "USER_LOOKUP", "user", String.valueOf(found.userId()), null, clientIp(exchange));
        respond(exchange, 200, userJson(found));
      } else if (path.equals("/api/admin/users/reset-password") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.USER_SUPPORT);
        long target = resolveUserId(input);
        administration.resetPassword(target, requiredString(input, "newPassword"));
        audit.log(
            actor, "USER_RESET_PASSWORD", "user", String.valueOf(target), null, clientIp(exchange));
        respond(exchange, 200, Map.of("status", "ok"));
      } else if (path.equals("/api/admin/users/unbind") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.USER_SUPPORT);
        long target = resolveUserId(input);
        administration.unbindUser(target);
        audit.log(actor, "USER_UNBIND", "user", String.valueOf(target), null, clientIp(exchange));
        respond(exchange, 200, Map.of("status", "ok"));
      } else if (path.equals("/api/admin/users/logout") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.USER_SUPPORT);
        long target = resolveUserId(input);
        administration.forceLogout(target);
        audit.log(
            actor, "USER_FORCE_LOGOUT", "user", String.valueOf(target), null, clientIp(exchange));
        respond(exchange, 200, Map.of("status", "ok"));
      } else if (path.equals("/api/admin/users/wallet-adjust") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.USER_SUPPORT);
        long target = resolveUserId(input);
        CurrencyType currency = currency(input, "currency");
        long delta = requiredLong(input, "delta");
        String reason = optionalString(input, "reason", "ADMIN_ADJUST");
        var balance = administration.adjustWallet(target, currency, delta, reason);
        JsonObject detail = new JsonObject();
        detail.addProperty("currency", currency.name());
        detail.addProperty("delta", delta);
        detail.addProperty("reason", reason);
        audit.log(
            actor,
            "USER_WALLET_ADJUST",
            "wallet",
            String.valueOf(target),
            detail,
            clientIp(exchange));
        respond(exchange, 200, balance);
      } else if (path.equals("/api/admin/users/migrate-uuid") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.USER_SUPPORT);
        long target = resolveUserId(input);
        UUID oldUuid = UUID.fromString(requiredString(input, "oldUuid"));
        UUID newUuid = UUID.fromString(requiredString(input, "newUuid"));
        var result = administration.migrateUserUuid(target, oldUuid, newUuid);
        JsonObject detail = new JsonObject();
        detail.addProperty("oldUuid", oldUuid.toString());
        detail.addProperty("newUuid", newUuid.toString());
        audit.log(
            actor, "USER_UUID_MIGRATE", "user", String.valueOf(target), detail, clientIp(exchange));
        respond(exchange, 200, result);
      } else if (path.equals("/api/admin/admin-users/meta") && method(exchange, "GET")) {
        administration.requireSuperAdmin(user(exchange));
        respond(
            exchange,
            200,
            Map.of(
                "groups",
                administration.listPermissionGroups(),
                "templates",
                administration.listPermissionTemplates()));
      } else if (path.equals("/api/admin/admin-users/list") && method(exchange, "GET")) {
        var actor = administration.requireSuperAdmin(user(exchange));
        var admins = administration.listAdmins();
        audit.log(actor, "ADMIN_USER_LIST", "admin_user", null, null, clientIp(exchange));
        respond(
            exchange,
            200,
            Map.of("admins", admins.stream().map(SharedHttpApi::adminAccessJson).toList()));
      } else if (path.equals("/api/admin/admin-users/upsert") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireSuperAdmin(user(exchange));
        String identifier =
            input.has("identifier")
                ? requiredString(input, "identifier")
                : requiredString(input, "username");
        boolean superAdmin = input.has("isSuperAdmin") && input.get("isSuperAdmin").getAsBoolean();
        Set<AdminPermission> permissions = EnumSet.noneOf(AdminPermission.class);
        if (input.has("permissions") && input.get("permissions").isJsonArray()) {
          input
              .getAsJsonArray("permissions")
              .forEach(
                  permission ->
                      permissions.add(
                          AdminPermission.valueOf(
                              permission.getAsString().trim().toUpperCase(Locale.ROOT))));
        }
        var updated =
            administration.upsertAdmin(
                actor.userId(),
                identifier,
                superAdmin,
                permissions,
                optionalString(input, "templateKey", null));
        audit.log(
            actor,
            "ADMIN_USER_UPSERT",
            "admin_user",
            String.valueOf(updated.userId()),
            null,
            clientIp(exchange));
        respond(exchange, 200, adminAccessJson(updated));
      } else if (path.equals("/api/admin/admin-users/active") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireSuperAdmin(user(exchange));
        long target = requiredLong(input, "userId");
        boolean active = input.has("active") && input.get("active").getAsBoolean();
        var updated = administration.setAdminActive(actor.userId(), target, active);
        audit.log(
            actor,
            active ? "ADMIN_USER_ENABLE" : "ADMIN_USER_DISABLE",
            "admin_user",
            String.valueOf(target),
            null,
            clientIp(exchange));
        respond(exchange, 200, adminAccessJson(updated));
      } else if (path.equals("/api/admin/audit/list") && method(exchange, "GET")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.AUDIT_VIEW);
        respond(
            exchange,
            200,
            Map.of(
                "logs",
                audit.list(queryInt(exchange, "limit", 100)).stream()
                    .map(SharedHttpApi::auditJson)
                    .toList()));
      } else if (path.equals("/api/admin/redeem/list") && method(exchange, "GET")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.REDEEM_MANAGE);
        respond(exchange, 200, redeemCodes.listCodes(queryInt(exchange, "limit", 100)));
      } else if (path.equals("/api/admin/redeem/create") && method(exchange, "POST")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.REDEEM_MANAGE);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            Map.of(
                "code",
                redeemCodes.createCode(
                    optionalLong(input, "shopCoin", 0),
                    optionalLong(input, "gameCoin", 0),
                    optionalInt(input, "maxUses", 1),
                    optionalInt(input, "perUserMaxUses", 1),
                    input.has("expiresInMinutes") ? input.get("expiresInMinutes").getAsInt() : null,
                    optionalString(input, "code", null))));
      } else if (path.equals("/api/admin/notifications/announce") && method(exchange, "POST")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.USER_SUPPORT);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            Map.of(
                "created",
                notifications.createSystemAnnouncement(
                    requiredString(input, "title"), requiredString(input, "content"))));
      } else if (path.equals("/api/admin/promotions/list") && method(exchange, "GET")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.PROMOTION_VIEW);
        respond(exchange, 200, promotions.campaigns(query(exchange, "ownerType")));
      } else if (path.equals("/api/admin/promotions/create") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PROMOTION_MANAGE);
        respond(
            exchange,
            200,
            promotions.createCampaign(
                actor.userId(),
                gson.fromJson(body(exchange), SharedPromotionService.CampaignInput.class)));
      } else if (path.equals("/api/admin/promotions/publish") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PROMOTION_MANAGE);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.publishCampaign(
                actor.userId(),
                requiredLong(input, "campaignId"),
                requiredLong(input, "expectedVersion"),
                gson.fromJson(
                    requiredObject(input, "rule"),
                    SharedPromotionService.PromotionRuleInput.class)));
      } else if (path.equals("/api/admin/promotions/action") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PROMOTION_MANAGE);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.transitionCampaign(
                actor.userId(),
                requiredLong(input, "campaignId"),
                requiredString(input, "action")));
      } else if (path.equals("/api/admin/promotions/emergency-stop") && method(exchange, "POST")) {
        var actor =
            administration.requireAdmin(user(exchange), AdminPermission.PROMOTION_EMERGENCY_STOP);
        respond(exchange, 200, promotions.emergencyStop(actor.userId()));
      } else if (path.equals("/api/admin/coupons/grant") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.COUPON_GRANT);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.grantCoupon(
                actor.userId(),
                requiredLong(input, "userId"),
                requiredLong(input, "templateId"),
                requiredString(input, "idempotencyKey")));
      } else if (path.equals("/api/admin/coupons/templates") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), AdminPermission.COUPON_MANAGE);
        respond(exchange, 200, promotions.couponTemplates());
      } else if (path.equals("/api/admin/coupons/templates/create") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.COUPON_MANAGE);
        respond(
            exchange,
            200,
            promotions.createCouponTemplate(
                actor.userId(),
                gson.fromJson(body(exchange), SharedPromotionService.CouponTemplateInput.class)));
      } else if (path.equals("/api/admin/membership/grant") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MEMBERSHIP_GRANT);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.grantMembership(
                actor.userId(),
                requiredLong(input, "userId"),
                requiredLong(input, "planVersionId"),
                requiredString(input, "idempotencyKey")));
      } else if (path.equals("/api/admin/membership/revoke") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MEMBERSHIP_MANAGE);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.revokeMembership(
                actor.userId(),
                requiredLong(input, "membershipId"),
                requiredString(input, "reason")));
      } else if (path.equals("/api/admin/membership/plans") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), AdminPermission.MEMBERSHIP_VIEW);
        respond(exchange, 200, promotions.membershipPlans());
      } else if (path.equals("/api/admin/membership/plans/create") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MEMBERSHIP_MANAGE);
        respond(
            exchange,
            200,
            promotions.createMembershipPlan(
                actor.userId(),
                gson.fromJson(body(exchange), SharedPromotionService.MembershipPlanInput.class)));
      } else if (path.equals("/api/admin/membership/plans/publish") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MEMBERSHIP_MANAGE);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.publishMembershipPlan(
                actor.userId(),
                requiredLong(input, "planId"),
                gson.fromJson(
                    requiredObject(input, "version"),
                    SharedPromotionService.MembershipVersionInput.class)));
      } else if (path.equals("/api/admin/membership/products/bind") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MEMBERSHIP_MANAGE);
        JsonObject input = body(exchange);
        promotions.bindMembershipProduct(
            actor.userId(), requiredLong(input, "productId"), requiredLong(input, "planVersionId"));
        respond(exchange, 200, Map.of("status", "ok"));
      } else if (path.equals("/api/admin/membership/codes/create") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MEMBERSHIP_MANAGE);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            promotions.createMembershipCode(
                actor.userId(),
                requiredLong(input, "planVersionId"),
                Math.toIntExact(requiredLong(input, "maxUses")),
                input.has("validUntil") && !input.get("validUntil").isJsonNull()
                    ? java.time.Instant.parse(input.get("validUntil").getAsString())
                    : null));
      } else if (path.equals("/api/admin/products") && method(exchange, "POST")) {
        var user = user(exchange);
        administration.requireAdmin(user, AdminPermission.PRODUCT_MANAGE);
        JsonObject input = body(exchange);
        Integer stock =
            input.has("stock") && !input.get("stock").isJsonNull()
                ? input.get("stock").getAsInt()
                : null;
        respond(
            exchange,
            200,
            commerce.createProduct(
                new ProductInput(
                    requiredString(input, "sku"),
                    requiredString(input, "title"),
                    optionalString(input, "remark", null),
                    CurrencyType.valueOf(requiredString(input, "currency").toUpperCase()),
                    requiredLong(input, "price"),
                    ProductKind.valueOf(requiredString(input, "kind").toUpperCase()),
                    optionalString(input, "command", ""),
                    optionalString(input, "registryId", null),
                    stock,
                    !input.has("active") || input.get("active").getAsBoolean())));
      } else if (path.equals("/api/admin/wallet-adjust") && method(exchange, "POST")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.USER_SUPPORT);
        JsonObject input = body(exchange);
        respond(
            exchange,
            200,
            wallets.adjustBalance(
                requiredLong(input, "userId"),
                CurrencyType.valueOf(requiredString(input, "currency").toUpperCase()),
                requiredLong(input, "delta"),
                "ADMIN_ADJUST",
                requiredString(input, "idempotencyKey")));
      } else if (SharedRouteContract.routes().contains(path)) {
        respond(
            exchange,
            501,
            error(
                "capability_unavailable",
                "The route is part of the shared contract but is unavailable on this server"));
      } else {
        respond(exchange, 404, error("not_found", "Route was not found"));
      }
    } catch (BodyTooLarge failure) {
      respond(exchange, 413, error("body_too_large", failure.getMessage()));
    } catch (ServiceException failure) {
      int status =
          switch (failure.code()) {
            case "invalid_session", "unauthorized" -> 401;
            case "forbidden", "not_admin" -> 403;
            case "not_found", "product_not_found" -> 404;
            case "insufficient_funds", "insufficient_stock", "stock_conflict" -> 409;
            default -> 400;
          };
      respond(exchange, status, error(failure.code(), failure.getMessage()));
    } catch (IllegalArgumentException failure) {
      respond(exchange, 400, error("bad_request", failure.getMessage()));
    } catch (RuntimeException failure) {
      System.err.printf(
          "[WebShopX] HTTP request failed path=%s type=%s%n",
          exchange.getRequestURI().getPath(), failure.getClass().getSimpleName());
      respond(exchange, 500, error("internal_error", "Request failed"));
    }
  }

  private AuthService.AuthUser user(HttpExchange exchange) {
    return auth.findUserBySession(token(exchange))
        .orElseThrow(() -> new ServiceException("unauthorized", "Authentication required"));
  }

  private AuthService.AuthUser boundUser(HttpExchange exchange) {
    AuthService.AuthUser current = user(exchange);
    if (current.boundUuid() == null) throw new ServiceException("not_bound", "User is not bound");
    return current;
  }

  private long resolveUserId(JsonObject input) {
    if (input.has("userId") && !input.get("userId").isJsonNull()) {
      long userId = input.get("userId").getAsLong();
      if (userId > 0) return userId;
    }
    String identifier =
        input.has("identifier")
            ? requiredString(input, "identifier")
            : requiredString(input, "username");
    return administration
        .lookupUser(identifier)
        .map(AdminService.UserSupportView::userId)
        .orElseThrow(() -> new ServiceException("not_found", "User not found"));
  }

  private static String clientIp(HttpExchange exchange) {
    return exchange.getRemoteAddress() == null
        ? null
        : exchange.getRemoteAddress().getAddress().getHostAddress();
  }

  private static Map<String, Object> userJson(AdminService.UserSupportView view) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", view.userId());
    result.put("username", view.username());
    result.put("boundUuid", view.boundUuid() == null ? null : view.boundUuid().toString());
    result.put("authState", view.authState());
    result.put("createdAt", view.createdAt().toString());
    result.put("shopCoin", view.shopCoin());
    result.put("gameCoin", view.gameCoin());
    return result;
  }

  private static Map<String, Object> userJson(AdminService.UserListItem view) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", view.userId());
    result.put("username", view.username());
    result.put("boundUuid", view.boundUuid() == null ? null : view.boundUuid().toString());
    result.put("authState", view.authState());
    result.put("createdAt", view.createdAt().toString());
    result.put("shopCoin", view.shopCoin());
    result.put("gameCoin", view.gameCoin());
    return result;
  }

  private static Map<String, Object> adminAccessJson(AdminService.AdminAccessView view) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("userId", view.userId());
    result.put("username", view.username());
    result.put("boundUuid", view.boundUuid() == null ? null : view.boundUuid().toString());
    result.put("isSuperAdmin", view.isSuperAdmin());
    result.put("active", view.active());
    result.put("roleLabel", view.roleLabel());
    result.put("templateKey", view.templateKey());
    result.put("permissions", view.permissions());
    result.put("createdAt", view.createdAt().toString());
    result.put("updatedAt", view.updatedAt().toString());
    return result;
  }

  private static Map<String, Object> auditJson(AdminAuditService.AuditView view) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", view.id());
    result.put("adminUserId", view.adminUserId());
    result.put("adminUsername", view.adminUsername());
    result.put("adminRole", view.adminRole());
    result.put("action", view.action());
    result.put("targetType", view.targetType());
    result.put("targetId", view.targetId());
    result.put("detailJson", view.detailJson());
    result.put("sourceIp", view.sourceIp());
    result.put("createdAt", view.createdAt().toString());
    return result;
  }

  private static String token(HttpExchange exchange) {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new ServiceException("unauthorized", "Bearer token required");
    }
    return authorization.substring(7).trim();
  }

  private JsonObject body(HttpExchange exchange) throws IOException {
    int declared = parseLength(exchange.getRequestHeaders().getFirst("Content-Length"));
    if (declared > MAX_BODY) throw new BodyTooLarge("Request body exceeds 64 KiB");
    byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
    if (bytes.length > MAX_BODY) throw new BodyTooLarge("Request body exceeds 64 KiB");
    if (bytes.length == 0) return new JsonObject();
    var value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
    if (!value.isJsonObject()) throw new IllegalArgumentException("JSON object required");
    return value.getAsJsonObject();
  }

  private static JsonObject requiredObject(JsonObject input, String name) {
    if (!input.has(name) || !input.get(name).isJsonObject()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return input.getAsJsonObject(name);
  }

  private void applySecurityHeaders(HttpExchange exchange) {
    Headers headers = exchange.getResponseHeaders();
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Cache-Control", "no-store");
    headers.set(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' data: blob:; "
            + "font-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; "
            + "frame-ancestors 'none'; object-src 'none'; base-uri 'self'");
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (!allowedOrigin.isEmpty() && allowedOrigin.equals(origin)) {
      headers.set("Access-Control-Allow-Origin", origin);
      headers.set("Vary", "Origin");
      headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key");
      headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }
  }

  private void serveStatic(HttpExchange exchange, String path) throws IOException {
    if (path.indexOf('\0') >= 0 || path.contains("..") || path.contains("\\")) {
      respond(exchange, 400, error("invalid_path", "Static path is invalid"));
      return;
    }
    String relative = path.equals("/") ? "index.html" : path.substring(1);
    byte[] bytes = resource("web/" + relative);
    if (bytes == null && !relative.contains(".")) bytes = resource("web/index.html");
    if (bytes == null && relative.equals("index.html")) {
      bytes =
          ("<!doctype html><meta charset=utf-8><title>WebShopX</title>"
                  + "<main><h1>WebShopX</h1><p>Server API is ready.</p></main>")
              .getBytes(StandardCharsets.UTF_8);
    }
    if (bytes == null) {
      respond(exchange, 404, error("not_found", "Static asset was not found"));
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", contentType(relative));
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private byte[] resource(String name) throws IOException {
    try (var input = SharedHttpApi.class.getClassLoader().getResourceAsStream(name)) {
      return input == null ? null : input.readAllBytes();
    }
  }

  private static String contentType(String path) {
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".html")) return "text/html; charset=utf-8";
    if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
    if (lower.endsWith(".css")) return "text/css; charset=utf-8";
    if (lower.endsWith(".json")) return "application/json; charset=utf-8";
    if (lower.endsWith(".svg")) return "image/svg+xml";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".webp")) return "image/webp";
    if (lower.endsWith(".woff2")) return "font/woff2";
    return "application/octet-stream";
  }

  private void respond(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] bytes =
        status == 204 ? new byte[0] : gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static Map<String, Object> error(String code, String message) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("error", code);
    value.put("message", message);
    return value;
  }

  private static boolean method(HttpExchange exchange, String expected) {
    return expected.equals(exchange.getRequestMethod());
  }

  private static int parseLength(String value) {
    try {
      return value == null ? -1 : Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static String requiredString(JsonObject input, String key) {
    if (!input.has(key) || input.get(key).isJsonNull() || input.get(key).getAsString().isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return input.get(key).getAsString();
  }

  private static String optionalString(JsonObject input, String key, String fallback) {
    return !input.has(key) || input.get(key).isJsonNull() ? fallback : input.get(key).getAsString();
  }

  private static long requiredLong(JsonObject input, String key) {
    if (!input.has(key)) throw new IllegalArgumentException(key + " is required");
    return input.get(key).getAsLong();
  }

  private static long optionalLong(JsonObject input, String key, long fallback) {
    return input.has(key) ? input.get(key).getAsLong() : fallback;
  }

  private static CurrencyType currency(JsonObject input, String key) {
    return CurrencyType.valueOf(requiredString(input, key).toUpperCase());
  }

  private static int optionalInt(JsonObject input, String key, int fallback) {
    return input.has(key) ? input.get(key).getAsInt() : fallback;
  }

  private static int queryInt(HttpExchange exchange, String key, int fallback) {
    String value = query(exchange, key);
    if (value == null) return fallback;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static Long queryLong(HttpExchange exchange, String key) {
    String value = query(exchange, key);
    if (value == null) return null;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static boolean queryBoolean(HttpExchange exchange, String key, boolean fallback) {
    String value = query(exchange, key);
    return value == null ? fallback : Boolean.parseBoolean(value);
  }

  private static String requiredQuery(HttpExchange exchange, String key) {
    String value = query(exchange, key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
    return value;
  }

  private static String query(HttpExchange exchange, String key) {
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null) return null;
    for (String part : query.split("&")) {
      String[] pair = part.split("=", 2);
      if (pair[0].equals(key) && pair.length == 2) {
        return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  @Override
  public void close() {
    server.stop(1);
    executor.shutdownNow();
  }

  private static final class BodyTooLarge extends RuntimeException {
    BodyTooLarge(String message) {
      super(message);
    }
  }
}
