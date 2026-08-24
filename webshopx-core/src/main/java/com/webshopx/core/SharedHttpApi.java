package com.webshopx.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
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
import com.webshopx.RefundPolicyService;
import com.webshopx.ServiceException;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedCommerceService.ProductInput;
import com.webshopx.SharedCommerceService.ProductKind;
import com.webshopx.SharedCommerceService.PurchaseRequest;
import com.webshopx.SharedContentService;
import com.webshopx.SharedMarketEscrowService;
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
  private final SharedMarketEscrowService marketEscrow;
  private final SharedContentService content;
  private final SharedPromotionService promotions;
  private final RedeemCodeService redeemCodes;
  private final NotificationService notifications;
  private final AdminService administration;
  private final AdminAuditService audit;
  private final RefundPolicyService refundPolicies;
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
      SharedMarketEscrowService marketEscrow,
      SharedContentService content,
      SharedPromotionService promotions,
      RedeemCodeService redeemCodes,
      NotificationService notifications,
      AdminService administration,
      AdminAuditService audit,
      RefundPolicyService refundPolicies,
      PlatformIdentity identity,
      CapabilitySnapshot capabilities) {
    this.auth = Objects.requireNonNull(auth, "auth");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.commerce = Objects.requireNonNull(commerce, "commerce");
    this.marketEscrow = Objects.requireNonNull(marketEscrow, "marketEscrow");
    this.content = Objects.requireNonNull(content, "content");
    this.promotions = Objects.requireNonNull(promotions, "promotions");
    this.redeemCodes = Objects.requireNonNull(redeemCodes, "redeemCodes");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
    this.administration = Objects.requireNonNull(administration, "administration");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.refundPolicies = Objects.requireNonNull(refundPolicies, "refundPolicies");
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
        respond(
            exchange,
            200,
            Map.of("products", commerce.products(false).stream().map(SharedHttpApi::productJson).toList()));
      } else if (path.equals("/api/products/quote") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var quote =
            commerce.quoteProduct(
                requiredLong(input, "productId"), optionalInt(input, "quantity", 1));
        JsonObject response = new JsonObject();
        response.addProperty("productId", quote.productId());
        response.addProperty("dynamicPricingMode", "ORDER_FIXED");
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
        respond(exchange, 200, response);
      } else if (path.equals("/api/products/price-trend") && method(exchange, "GET")) {
        long productId = Long.parseLong(requiredQuery(exchange, "productId"));
        respond(
            exchange,
            200,
            Map.of(
                "productId",
                productId,
                "history",
                commerce.productPriceTrend(productId, queryInt(exchange, "limit", 30))));
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
      } else if (path.equals("/api/orders/list") && method(exchange, "GET")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            Map.of(
                "orders",
                commerce.orders(
                    current.id(), queryInt(exchange, "limit", 30), queryLong(exchange, "cursor")),
                "cooldownSeconds",
                0,
                "refundUndeliveredEnabled",
                true,
                "sharedClaimAllowed",
                true));
      } else if (path.equals("/api/orders/policy") && method(exchange, "GET")) {
        respond(
            exchange,
            200,
            Map.of(
                "cooldownSeconds", 0,
                "refundEnabled", true,
                "refundUndeliveredEnabled", true,
                "marketFeePercent", 0,
                "marketTaxPercent", 0,
                "marketSupplyAutoRefreshThreshold", 0,
                "sharedClaimAllowed", true));
      } else if (path.equals("/api/orders/delivery-status") && method(exchange, "GET")) {
        var current = user(exchange);
        respond(
            exchange,
            200,
            deliveryStatusJson(
                commerce.deliveryStatus(current.id(), requiredQuery(exchange, "orderNo"))));
      } else if (path.equals("/api/orders/refund") && method(exchange, "POST")) {
        var current = user(exchange);
        SharedCommerceService.RefundResult refund =
            commerce.refundOrder(current.id(), requiredString(body(exchange), "orderNo"));
        respond(
            exchange,
            200,
            Map.of(
                "orderNo", refund.orderNo(),
                "refundAmount", refund.refundAmount(),
                "refundQuantity", refund.refundQuantity(),
                "earnedQuantity", refund.refundQuantity(),
                "shopCoin", refund.balance().shopCoin(),
                "gameCoin", refund.balance().gameCoin()));
      } else if (path.equals("/api/orders/discard") && method(exchange, "POST")) {
        var current = user(exchange);
        String orderNo = requiredString(body(exchange), "orderNo");
        commerce.discardOrder(current.id(), orderNo);
        respond(exchange, 200, Map.of("orderNo", orderNo, "status", "CANCELLED"));
      } else if (path.equals("/api/deliveries") && method(exchange, "GET")) {
        var user = user(exchange);
        if (user.boundUuid() == null) throw new ServiceException("not_bound", "User is not bound");
        respond(exchange, 200, commerce.pendingDeliveries(user.boundUuid(), identity.serverId()));
      } else if (path.equals("/api/mailbox/list") && method(exchange, "GET")) {
        var current = user(exchange);
        var items =
            commerce.mailboxItems(
                current.id(), queryInt(exchange, "limit", 50), queryLong(exchange, "cursor"));
        respond(exchange, 200, Map.of("items", items, "count", items.size()));
      } else if (path.equals("/api/mailbox/count") && method(exchange, "GET")) {
        var current = user(exchange);
        respond(exchange, 200, Map.of("count", commerce.mailboxCount(current.id())));
      } else if (path.startsWith("/api/mailbox/")
          && path.endsWith("/claim")
          && method(exchange, "POST")) {
        var current = boundUser(exchange);
        String entryId =
            path.substring("/api/mailbox/".length(), path.length() - "/claim".length());
        respond(
            exchange,
            200,
            marketEscrow.claimMailbox(
                new SharedMarketEscrowService.MailboxClaimRequest(
                    current.id(), current.boundUuid(), entryId)));
      } else if (path.startsWith("/api/mailbox/")
          && path.endsWith("/refund")
          && method(exchange, "POST")) {
        throw new ServiceException(
            "capability_unavailable", "Standalone mailbox items are not refundable");
      } else if (path.equals("/api/inventory/snapshot") && method(exchange, "GET")) {
        var current = boundUser(exchange);
        String inventory = query(exchange, "inventory");
        if (inventory != null && !"PLAYER".equalsIgnoreCase(inventory)) {
          throw new ServiceException(
              "inventory_source_unavailable", "This platform does not expose that inventory");
        }
        respond(exchange, 200, inventoryJson(marketEscrow.inventory(current.boundUuid())));
      } else if (path.equals("/api/inventory/list") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        String inventory = optionalString(input, "inventory", "PLAYER");
        if (!"PLAYER".equalsIgnoreCase(inventory)) {
          throw new ServiceException(
              "inventory_source_unavailable", "This platform does not expose that inventory");
        }
        if (!"LIST".equalsIgnoreCase(optionalString(input, "action", "LIST"))) {
          throw new ServiceException(
              "unsupported_trade_mode", "Loader inventory listing currently supports DIRECT");
        }
        SharedCommerceService.Listing listing =
            marketEscrow.createSellListing(
                new SharedMarketEscrowService.Request(
                    current.id(),
                    current.boundUuid(),
                    currency(input, "currency"),
                    requiredLong(input, "price"),
                    optionalInt(input, "quantity", 1),
                    requiredString(input, "idempotencyKey"),
                    requiredString(input, "fingerprint"),
                    true,
                    optionalString(input, "remark", null)));
        respond(
            exchange,
            200,
            Map.of(
                "listingId", listing.id(),
                "state", "SUCCESS",
                "revision", "refresh-required"));
      } else if (path.equals("/api/inventory/discard") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        String inventory = optionalString(input, "inventory", "PLAYER");
        if (!"PLAYER".equalsIgnoreCase(inventory)
            || (input.has("containerSlot") && !input.get("containerSlot").isJsonNull())) {
          throw new ServiceException(
              "invalid_inventory_request", "Only top-level player inventory can be discarded");
        }
        respond(
            exchange,
            200,
            marketEscrow.discard(
                new SharedMarketEscrowService.DiscardRequest(
                    current.id(),
                    current.boundUuid(),
                    optionalInt(input, "quantity", 1),
                    requiredString(input, "idempotencyKey"),
                    requiredString(input, "fingerprint"),
                    true)));
      } else if (path.equals("/api/inventory/matches") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        requireTopLevelPlayerInventory(input);
        var matches =
            marketEscrow.matches(
                new SharedMarketEscrowService.MatchRequest(
                    current.id(),
                    current.boundUuid(),
                    optionalInt(input, "quantity", 1),
                    requiredString(input, "fingerprint"),
                    true));
        JsonArray rows = new JsonArray();
        for (var match : matches) {
          JsonObject row = new JsonObject();
          row.addProperty("id", String.valueOf(match.listingId()));
          row.addProperty("source", "PLAYER_BUY_ORDER");
          row.addProperty("sourceName", "User #" + match.buyerUserId());
          row.addProperty("remaining", match.remaining());
          row.addProperty("currency", match.currency().name());
          row.addProperty("unitPrice", match.unitPrice());
          row.addProperty("sellerReceive", match.sellerReceive());
          row.addProperty("fee", match.fee());
          row.addProperty("quotedQuantity", match.quotedQuantity());
          rows.add(row);
        }
        respond(exchange, 200, Map.of("matches", rows));
      } else if (path.equals("/api/inventory/fulfill") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        requireTopLevelPlayerInventory(input);
        String listingRaw = requiredString(input, "listingId");
        if (listingRaw.startsWith("official:")) {
          throw new ServiceException(
              "capability_unavailable", "Official recycle products are not configured");
        }
        var trade =
            marketEscrow.fulfill(
                new SharedMarketEscrowService.FulfillRequest(
                    current.id(),
                    current.boundUuid(),
                    Long.parseLong(listingRaw),
                    optionalInt(input, "quantity", 1),
                    requiredString(input, "idempotencyKey"),
                    requiredString(input, "fingerprint"),
                    true,
                    input.has("expectedUnitPrice")
                        ? input.get("expectedUnitPrice").getAsLong()
                        : null,
                    input.has("expectedBuyerTotal")
                        ? input.get("expectedBuyerTotal").getAsLong()
                        : null));
        JsonObject response = new JsonObject();
        response.addProperty("state", "SUCCESS");
        response.addProperty("tradeId", trade.id());
        response.addProperty("listingId", trade.listingId());
        response.addProperty("currency", trade.currency().name());
        response.addProperty("quantity", trade.quantity());
        response.addProperty("totalPrice", trade.total());
        response.addProperty("buyerTotal", trade.total());
        response.addProperty("sellerReceive", trade.total());
        response.addProperty("feeAmount", 0);
        response.addProperty("taxAmount", 0);
        respond(exchange, 200, response);
      } else if (path.equals("/api/market/listings") && method(exchange, "GET")) {
        boolean mineOnly = queryBoolean(exchange, "mine", false);
        Long ownerId = mineOnly ? user(exchange).id() : null;
        int limit = Math.max(1, Math.min(queryInt(exchange, "limit", 100), 200));
        List<JsonObject> listings =
            commerce.listings(mineOnly).stream()
                .filter(listing -> ownerId == null || listing.sellerUserId() == ownerId)
                .limit(limit)
                .map(this::marketListingJson)
                .toList();
        respond(exchange, 200, Map.of("listings", listings));
      } else if (path.equals("/api/market/listings/create") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        String side = optionalString(input, "side", "SELL").toUpperCase(Locale.ROOT);
        if ("BUY".equals(side)) {
          var listing =
              commerce.createBuyListing(
                  new SharedCommerceService.BuyListingRequest(
                      current.id(),
                      current.boundUuid(),
                      currency(input, "currency"),
                      requiredLong(input, "price"),
                      optionalInt(input, "quantity", 1),
                      requiredString(input, "itemMaterial"),
                      requiredString(input, "idempotencyKey"),
                      optionalString(input, "remark", null)));
          respond(
              exchange,
              200,
              marketListingJson(listing));
        } else if ("SELL".equals(side)) {
          var listing =
              marketEscrow.createSellListing(
                  new SharedMarketEscrowService.Request(
                      current.id(),
                      current.boundUuid(),
                      currency(input, "currency"),
                      requiredLong(input, "price"),
                      optionalInt(input, "quantity", 1),
                      requiredString(input, "idempotencyKey"),
                      optionalString(input, "expectedPayloadHash", null),
                      input.has("allowOffline") && input.get("allowOffline").getAsBoolean(),
                      optionalString(input, "remark", null)));
          respond(
              exchange,
              200,
              marketListingJson(listing));
        } else {
          throw new ServiceException("invalid_market_side", "Market side is invalid");
        }
      } else if (path.equals("/api/market/quote") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        int quantity =
            input.has("buyQuantity")
                ? optionalInt(input, "buyQuantity", 1)
                : input.has("sellQuantity")
                    ? optionalInt(input, "sellQuantity", 1)
                    : optionalInt(input, "quantity", 1);
        var quote =
            commerce.quoteListing(current.id(), requiredLong(input, "listingId"), quantity);
        JsonObject response = new JsonObject();
        response.addProperty("listingId", quote.listingId());
        response.addProperty("currency", quote.currency().name());
        response.addProperty("side", quote.side());
        response.addProperty("unitPrice", quote.unitPrice());
        response.addProperty("firstUnitPrice", quote.unitPrice());
        response.addProperty("lastUnitPrice", quote.unitPrice());
        response.addProperty("averageUnitPrice", quote.unitPrice());
        response.addProperty("quantity", quote.quantity());
        response.addProperty("totalPrice", quote.totalPrice());
        response.addProperty("buyerTotal", quote.buyerTotal());
        response.addProperty("sellerReceive", quote.sellerReceive());
        response.addProperty("feeAmount", quote.feeAmount());
        response.addProperty("taxAmount", quote.taxAmount());
        response.addProperty("dynamicPricingEnabled", false);
        response.addProperty("dynamicPricingMode", "ORDER_FIXED");
        response.addProperty("currentDemandScore", 0);
        response.addProperty("nextDemandScore", 0);
        response.addProperty("nextUnitPrice", quote.unitPrice());
        respond(exchange, 200, response);
      } else if (path.equals("/api/market/price-trend") && method(exchange, "GET")) {
        long listingId = Long.parseLong(requiredQuery(exchange, "listingId"));
        respond(
            exchange,
            200,
            Map.of(
                "listingId",
                listingId,
                "history",
                commerce.marketPriceTrend(listingId, queryInt(exchange, "limit", 30))));
      } else if (path.equals("/api/market/pause") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        var listing = commerce.pauseListing(current.id(), requiredLong(input, "listingId"));
        respond(exchange, 200, Map.of("listingId", listing.id(), "status", listing.status()));
      } else if (path.equals("/api/market/resume") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        var listing = commerce.resumeListing(current.id(), requiredLong(input, "listingId"));
        respond(exchange, 200, Map.of("listingId", listing.id(), "status", listing.status()));
      } else if (path.equals("/api/market/price") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        var listing =
            commerce.updateListingPrice(
                current.id(), requiredLong(input, "listingId"), requiredLong(input, "price"));
        respond(
            exchange,
            200,
            Map.of(
                "listingId", listing.id(),
                "currency", listing.currency().name(),
                "price", listing.price()));
      } else if (path.equals("/api/market/remark") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        var listing =
            commerce.updateListingRemark(
                current.id(),
                requiredLong(input, "listingId"),
                optionalString(input, "remark", null));
        JsonObject response = new JsonObject();
        response.addProperty("listingId", listing.id());
        if (listing.remark() == null) response.add("remark", JsonNull.INSTANCE);
        else response.addProperty("remark", listing.remark());
        respond(exchange, 200, response);
      } else if (path.equals("/api/market/settings") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        requireDirectListingSettings(input);
        long listingId = requiredLong(input, "listingId");
        var listing =
            commerce.updateListingSettings(
                current.id(),
                listingId,
                requiredLong(input, "price"),
                currency(input, "currency"),
                optionalString(input, "remark", null));
        if (input.has("refundPolicyPreset")
            && !input.get("refundPolicyPreset").isJsonNull()
            && !input.get("refundPolicyPreset").getAsString().isBlank()) {
          refundPolicies.updateListingPolicy(
              current.id(),
              listingId,
              input.get("refundPolicyPreset").getAsString(),
              nullableInt(input, "refundWindowMinutes"));
        }
        respond(exchange, 200, marketListingJson(listing));
      } else if (path.equals("/api/market/unlist") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        var listing = commerce.unlist(current.id(), requiredLong(input, "listingId"));
        respond(
            exchange,
            200,
            Map.of(
                "listingId", listing.id(),
                "currency", listing.currency().name(),
                "price", listing.price(),
                "quantity", listing.quantity()));
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
                    input.has("buyQuantity")
                        ? optionalInt(input, "buyQuantity", 1)
                        : optionalInt(input, "quantity", 1),
                    requiredString(input, "idempotencyKey"),
                    identity.serverId(),
                    input.has("expectedUnitPrice")
                        ? input.get("expectedUnitPrice").getAsLong()
                        : null,
                    input.has("expectedBuyerTotal")
                        ? input.get("expectedBuyerTotal").getAsLong()
                        : null)));
      } else if (path.equals("/api/market/sell-to-buy") && method(exchange, "POST")) {
        var current = boundUser(exchange);
        JsonObject input = body(exchange);
        int quantity =
            input.has("sellQuantity")
                ? optionalInt(input, "sellQuantity", 1)
                : optionalInt(input, "quantity", 1);
        var trade =
            marketEscrow.fulfill(
                new SharedMarketEscrowService.FulfillRequest(
                    current.id(),
                    current.boundUuid(),
                    requiredLong(input, "listingId"),
                    quantity,
                    requiredString(input, "idempotencyKey"),
                    null,
                    true,
                    input.has("expectedUnitPrice")
                        ? input.get("expectedUnitPrice").getAsLong()
                        : null,
                    input.has("expectedBuyerTotal")
                        ? input.get("expectedBuyerTotal").getAsLong()
                        : null));
        respond(exchange, 200, marketTradeJson(trade));
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
        var current = user(exchange);
        respond(
            exchange,
            200,
            commerce.rechargeForUser(current.id(), requiredQuery(exchange, "orderId")));
      } else if (path.equals("/api/recharge/cancel") && method(exchange, "POST")) {
        var current = user(exchange);
        var cancelled =
            commerce.cancelRecharge(current.id(), requiredString(body(exchange), "orderId"));
        respond(
            exchange,
            200,
            Map.of(
                "success", true,
                "orderId", cancelled.orderId(),
                "status", cancelled.status()));
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
      } else if (path.equals("/api/homepage") && method(exchange, "GET")) {
        respond(exchange, 200, content.homepage());
      } else if (path.equals("/api/homepage/status") && method(exchange, "GET")) {
        respond(
            exchange,
            200,
            Map.of(
                "online",
                true,
                "minecraftVersion",
                identity.minecraftVersion(),
                "platform",
                identity.platform()));
      } else if (path.equals("/api/leaderboard/config") && method(exchange, "GET")) {
        respond(
            exchange,
            200,
            Map.of(
                "leaderboard",
                Map.of(
                    "enabled", true,
                    "showOnlineStatus", false,
                    "defaultMetric", "SHOP_COIN",
                    "defaultOrder", "DESC")));
      } else if (path.equals("/api/leaderboard/list") && method(exchange, "GET")) {
        Long viewer =
            auth.findUserBySession(token(exchange)).map(AuthService.AuthUser::id).orElse(null);
        var result =
            content.leaderboard(
                query(exchange, "metric"),
                query(exchange, "order"),
                query(exchange, "range"),
                queryInt(exchange, "limit", 100),
                viewer);
        JsonObject response = new JsonObject();
        response.add(
            "leaderboard",
            gson.toJsonTree(
                Map.of(
                    "enabled", true,
                    "showOnlineStatus", false,
                    "defaultMetric", "SHOP_COIN",
                    "defaultOrder", "DESC")));
        response.addProperty("metric", result.metric());
        response.addProperty("order", result.order());
        response.addProperty("requestedRange", result.requestedRange());
        response.addProperty("effectiveRange", result.effectiveRange());
        response.addProperty("total", result.total());
        if (result.myRank() == null) response.add("myRank", JsonNull.INSTANCE);
        else response.addProperty("myRank", result.myRank());
        response.add("entries", gson.toJsonTree(result.entries()));
        respond(exchange, 200, response);
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
        respond(exchange, 200, Map.of("defaultLocale", "zh-CN", "locales", List.of()));
      } else if (path.equals("/api/meta/material-overrides") && method(exchange, "GET")) {
        respond(exchange, 200, content.materialOverrides());
      } else if (path.equals("/api/meta/materials") && method(exchange, "GET")) {
        respond(exchange, 200, List.of());
      } else if (path.equals("/api/meta/market-tags") && method(exchange, "GET")) {
        respond(exchange, 200, List.of());
      } else if (path.equals("/api/meta/currency") && method(exchange, "GET")) {
        JsonObject response = new JsonObject();
        response.add("shopCoin", currencyDisplay("ShopCoin", "SC"));
        response.add("gameCoin", currencyDisplay("GameCoin", "GC"));
        response.add(
            "exchange",
            gson.toJsonTree(
                Map.of(
                    "shopToGame", Map.of("enabled", false, "ratio", 0),
                    "gameToShop", Map.of("enabled", false, "ratio", 0))));
        response.add(
            "payment",
            gson.toJsonTree(
                Map.of(
                    "enabled", false,
                    "primaryRechargeCurrency", "CNY",
                    "providers", List.of())));
        response.add("paymentProviders", new JsonArray());
        response.addProperty("timeZone", "UTC");
        respond(exchange, 200, response);
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
      } else if (path.equals("/api/admin/homepage/draft") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), AdminPermission.HOMEPAGE_MANAGE);
        respond(exchange, 200, content.homepageDraft());
      } else if (path.equals("/api/admin/homepage/draft") && method(exchange, "PUT")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.HOMEPAGE_MANAGE);
        JsonObject document =
            input.has("document") && input.get("document").isJsonObject()
                ? input.getAsJsonObject("document")
                : input;
        JsonObject saved = content.saveHomepageDraft(document, actor.username());
        audit.log(
            actor,
            "HOMEPAGE_DRAFT_SAVE",
            "homepage",
            saved.get("id").getAsString(),
            new JsonObject(),
            clientIp(exchange));
        respond(exchange, 200, saved);
      } else if (path.equals("/api/admin/homepage/publish") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.HOMEPAGE_MANAGE);
        JsonObject published = content.publishHomepage(actor.username());
        audit.log(
            actor,
            "HOMEPAGE_PUBLISH",
            "homepage",
            published.get("id").getAsString(),
            new JsonObject(),
            clientIp(exchange));
        respond(exchange, 200, published);
      } else if (path.equals("/api/admin/homepage/revisions") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), AdminPermission.HOMEPAGE_MANAGE);
        respond(exchange, 200, content.homepageRevisions());
      } else if (path.equals("/api/admin/homepage/restore") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.HOMEPAGE_MANAGE);
        String revisionId = requiredString(input, "revisionId");
        JsonObject restored = content.restoreHomepage(revisionId, actor.username());
        audit.log(
            actor,
            "HOMEPAGE_RESTORE",
            "homepage",
            revisionId,
            new JsonObject(),
            clientIp(exchange));
        respond(exchange, 200, restored);
      } else if (path.equals("/api/admin/homepage/assets") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), AdminPermission.HOMEPAGE_MANAGE);
        respond(exchange, 200, content.homepageAssets());
      } else if (path.equals("/api/admin/material-overrides/list") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), AdminPermission.ECONOMY_MANAGE);
        respond(
            exchange,
            200,
            Map.of(
                "items",
                content.materialOverrides(
                    query(exchange, "keyword"), queryInt(exchange, "limit", 300))));
      } else if (path.equals("/api/admin/material-overrides/upsert") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.ECONOMY_MANAGE);
        JsonObject saved =
            content.upsertMaterialOverride(
                requiredString(input, "materialKey"),
                optionalString(input, "displayNameOverride", null),
                optionalString(input, "iconPath", null),
                actor.username());
        audit.log(
            actor,
            "MATERIAL_OVERRIDE_UPSERT",
            "material_override",
            requiredString(input, "materialKey"),
            input.deepCopy(),
            clientIp(exchange));
        respond(exchange, 200, saved);
      } else if (path.equals("/api/admin/material-overrides/delete") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.ECONOMY_MANAGE);
        String materialKey = requiredString(input, "materialKey");
        boolean deleted = content.deleteMaterialOverride(materialKey);
        audit.log(
            actor,
            "MATERIAL_OVERRIDE_DELETE",
            "material_override",
            materialKey,
            input.deepCopy(),
            clientIp(exchange));
        respond(exchange, 200, Map.of("deleted", deleted, "materialKey", materialKey));
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
      } else if (path.equals("/api/admin/refund-policy") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), AdminPermission.PRODUCT_MANAGE);
        respond(exchange, 200, refundPolicies.getPolicy());
      } else if (path.equals("/api/admin/refund-policy") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PRODUCT_MANAGE);
        var policy =
            refundPolicies.updatePolicy(
                new RefundPolicyService.Policy(
                    optionalBoolean(input, "selfServiceEnabled", false),
                    optionalBoolean(input, "mailboxPendingRefundEnabled", false),
                    nullableInt(input, "fixedPriceWindowMinutes"),
                    nullableInt(input, "dynamicPriceWindowMinutes"),
                    optionalBoolean(input, "partialRefundEnabled", false),
                    optionalInt(input, "maxSelfServiceRefundsPerDay", 5),
                    optionalBoolean(input, "orderLevelPolicyEnabled", false)));
        JsonObject detail = gson.toJsonTree(policy).getAsJsonObject();
        audit.log(
            actor,
            "REFUND_POLICY_UPDATE",
            "refund_policy",
            null,
            detail,
            clientIp(exchange));
        respond(exchange, 200, policy);
      } else if (path.equals("/api/admin/products/refund-policy")
          && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PRODUCT_MANAGE);
        var policy =
            refundPolicies.updateProductPolicy(
                requiredLong(input, "productId"),
                optionalString(input, "refundPolicy", "INHERIT"),
                nullableInt(input, "refundWindowMinutes"),
                optionalString(input, "partialRefundPolicy", "INHERIT"));
        JsonObject detail = gson.toJsonTree(policy).getAsJsonObject();
        audit.log(
            actor,
            "PRODUCT_REFUND_POLICY_UPDATE",
            "product",
            Long.toString(policy.productId()),
            detail,
            clientIp(exchange));
        JsonObject response = new JsonObject();
        response.addProperty("productId", policy.productId());
        response.addProperty("refundPolicy", policy.refundPolicy());
        if (policy.windowMinutes() == null) response.add("refundWindowMinutes", JsonNull.INSTANCE);
        else response.addProperty("refundWindowMinutes", policy.windowMinutes());
        response.addProperty("partialRefundPolicy", policy.partialPolicy());
        respond(exchange, 200, response);
      } else if (path.equals("/api/admin/overview/stats") && method(exchange, "GET")) {
        administration.requireAdmin(user(exchange), null);
        respond(exchange, 200, content.overviewStats());
      } else if (path.equals("/api/admin/orders/list") && method(exchange, "GET")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.ORDER_VIEW);
        var orders =
            commerce.adminOrders(
                queryInt(exchange, "limit", 120),
                queryLong(exchange, "cursor"),
                query(exchange, "status"),
                queryLong(exchange, "userId"),
                query(exchange, "orderNo"),
                query(exchange, "keyword"),
                query(exchange, "currency"),
                query(exchange, "productType"));
        audit.log(actor, "ORDER_LIST", "order", null, null, clientIp(exchange));
        respond(
            exchange,
            200,
            Map.of("orders", orders.stream().map(this::adminOrderJson).toList()));
      } else if (path.equals("/api/admin/market/listings") && method(exchange, "GET")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MARKET_MANAGE);
        var listings = commerce.listings(true);
        audit.log(actor, "MARKET_LIST", "listing", null, null, clientIp(exchange));
        respond(
            exchange,
            200,
            Map.of("listings", listings.stream().map(this::marketListingJson).toList()));
      } else if (path.equals("/api/admin/market/unlist") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.MARKET_MANAGE);
        var listing = commerce.forceUnlist(requiredLong(input, "listingId"));
        audit.log(
            actor,
            "MARKET_UNLIST",
            "listing",
            String.valueOf(listing.id()),
            input,
            clientIp(exchange));
        respond(
            exchange,
            200,
            Map.of(
                "listingId", listing.id(),
                "currency", listing.currency().name(),
                "price", listing.price(),
                "quantity", listing.quantity()));
      } else if (path.equals("/api/admin/products/list") && method(exchange, "GET")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PRODUCT_MANAGE);
        boolean includeInactive = queryBoolean(exchange, "includeInactive", false);
        var products = commerce.products(includeInactive);
        audit.log(actor, "PRODUCT_LIST", "product", null, null, clientIp(exchange));
        respond(
            exchange,
            200,
            Map.of("products", products.stream().map(SharedHttpApi::productJson).toList()));
      } else if (path.equals("/api/admin/products/upsert") && method(exchange, "POST")) {
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PRODUCT_MANAGE);
        var product = commerce.upsertProduct(productInput(body(exchange)));
        audit.log(
            actor,
            "PRODUCT_UPSERT",
            "product",
            String.valueOf(product.id()),
            null,
            clientIp(exchange));
        respond(exchange, 200, productJson(product));
      } else if (path.equals("/api/admin/products/active") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var actor = administration.requireAdmin(user(exchange), AdminPermission.PRODUCT_MANAGE);
        boolean active = input.has("active") && input.get("active").getAsBoolean();
        var product = commerce.setProductActive(requiredLong(input, "productId"), active);
        audit.log(
            actor,
            "PRODUCT_ACTIVE",
            "product",
            String.valueOf(product.id()),
            input,
            clientIp(exchange));
        respond(exchange, 200, Map.of("id", product.id(), "active", product.active()));
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
            case "forbidden", "not_admin", "listing_forbidden", "recharge_forbidden" -> 403;
            case "not_found", "product_not_found", "order_not_found", "listing_not_found" -> 404;
            case "insufficient_funds",
                    "insufficient_stock",
                    "stock_conflict",
                    "listing_conflict",
                    "listing_unavailable",
                    "price_changed",
                    "inventory_conflict",
                    "order_conflict" ->
                409;
            case "inventory_unavailable",
                    "inventory_outcome_unknown",
                    "delivery_outcome_unknown",
                    "refund_outcome_unknown" ->
                503;
            case "capability_unavailable" -> 501;
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

  private static JsonObject productJson(SharedCommerceService.Product product) {
    JsonObject result = new JsonObject();
    result.addProperty("id", product.id());
    result.addProperty("sku", product.sku());
    result.addProperty("title", product.title());
    if (product.remark() == null) result.add("remark", JsonNull.INSTANCE);
    else result.addProperty("remark", product.remark());
    result.addProperty("currency", product.currency().name());
    result.addProperty("price", product.price());
    result.addProperty("productType", product.kind().name());
    result.addProperty("commandTemplate", product.commandTemplate());
    if (product.registryId() == null) result.add("itemMaterial", JsonNull.INSTANCE);
    else result.addProperty("itemMaterial", product.registryId());
    if (product.stockRemaining() == null) result.add("stock", JsonNull.INSTANCE);
    else result.addProperty("stock", product.stockRemaining());
    result.addProperty("unlimitedStock", product.stockRemaining() == null);
    result.addProperty("active", product.active());
    result.addProperty("purchasable", product.active());
    result.addProperty("dynamicPricingEnabled", false);
    result.addProperty("dynamicPricingMode", "ORDER_FIXED");
    return result;
  }

  private static JsonObject currencyDisplay(String name, String shortName) {
    JsonObject result = new JsonObject();
    result.addProperty("name", name);
    result.addProperty("short", shortName);
    return result;
  }

  private JsonObject marketListingJson(SharedCommerceService.Listing listing) {
    JsonObject result = new JsonObject();
    result.addProperty("id", listing.id());
    result.addProperty("sellerUserId", listing.sellerUserId());
    result.addProperty("sellerUuid", listing.sellerId().toString());
    result.addProperty("currency", listing.currency().name());
    result.addProperty("price", listing.price());
    result.addProperty("quantity", listing.quantity());
    result.addProperty("quantityTotal", listing.quantity());
    result.addProperty("side", listing.side());
    result.addProperty("escrowTotal", listing.escrowTotal());
    result.addProperty("escrowRemaining", listing.escrowRemaining());
    result.addProperty("itemMaterial", listing.item().registryId());
    result.addProperty("itemMetaJson", gson.toJson(listing.item().summary()));
    result.addProperty("itemFingerprint", listing.item().payloadHash());
    if (listing.remark() == null) result.add("remark", JsonNull.INSTANCE);
    else result.addProperty("remark", listing.remark());
    result.addProperty("status", listing.status());
    result.addProperty("sourceMode", "PLAYER");
    result.addProperty("tradeMode", "DIRECT");
    result.addProperty("dynamicPricingEnabled", false);
    result.addProperty("dynamicPricingMode", "ORDER_FIXED");
    result.add("tags", new JsonArray());
    result.add("displayNameOverride", JsonNull.INSTANCE);
    result.add("displayMaterial", JsonNull.INSTANCE);
    result.add("displayIconPath", JsonNull.INSTANCE);
    return result;
  }

  private static JsonObject marketTradeJson(SharedCommerceService.MarketTrade trade) {
    JsonObject response = new JsonObject();
    response.addProperty("state", "SUCCESS");
    response.addProperty("tradeId", trade.id());
    response.addProperty("listingId", trade.listingId());
    response.addProperty("currency", trade.currency().name());
    response.addProperty("quantity", trade.quantity());
    response.addProperty("totalPrice", trade.total());
    response.addProperty("buyerTotal", trade.total());
    response.addProperty("sellerReceive", trade.total());
    response.addProperty("feeAmount", 0);
    response.addProperty("taxAmount", 0);
    return response;
  }

  private JsonObject adminOrderJson(SharedCommerceService.AdminOrderView view) {
    JsonObject result = gson.toJsonTree(view.order()).getAsJsonObject();
    result.addProperty("userId", view.userId());
    result.addProperty("username", view.username());
    if (view.boundUuid() == null) result.add("boundUuid", JsonNull.INSTANCE);
    else result.addProperty("boundUuid", view.boundUuid().toString());
    result.addProperty("mcUuid", view.order().playerUuid());
    return result;
  }

  private static ProductInput productInput(JsonObject input) {
    String type =
        input.has("productType")
            ? requiredString(input, "productType")
            : requiredString(input, "kind");
    Integer stock =
        input.has("itemAmount") && !input.get("itemAmount").isJsonNull()
            ? input.get("itemAmount").getAsInt()
            : input.has("stock") && !input.get("stock").isJsonNull()
                ? input.get("stock").getAsInt()
                : null;
    ProductKind kind = ProductKind.valueOf(type.toUpperCase(Locale.ROOT));
    String registryId =
        input.has("itemMaterial")
            ? optionalString(input, "itemMaterial", null)
            : optionalString(input, "registryId", null);
    if (kind == ProductKind.GIVE_ITEM && registryId != null) {
      registryId = registryId.trim().toLowerCase(Locale.ROOT);
      if (!registryId.contains(":")) registryId = "minecraft:" + registryId;
    }
    return new ProductInput(
        requiredString(input, "sku"),
        requiredString(input, "title"),
        optionalString(input, "remark", null),
        CurrencyType.valueOf(requiredString(input, "currency").toUpperCase(Locale.ROOT)),
        requiredLong(input, "price"),
        kind,
        input.has("commandTemplate")
            ? optionalString(input, "commandTemplate", "")
            : optionalString(input, "command", ""),
        registryId,
        stock,
        !input.has("active") || input.get("active").getAsBoolean());
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

  private JsonObject inventoryJson(SharedMarketEscrowService.InventoryView view) {
    var snapshot = view.snapshot();
    int size = snapshot.items().size() + snapshot.freeSlots();
    Map<Integer, ItemEnvelope> bySlot = new LinkedHashMap<>();
    int fallbackSlot = 0;
    for (ItemEnvelope item : snapshot.items()) {
      int slot;
      try {
        slot = Integer.parseInt(item.summary().getOrDefault("webshopx.slot", "-1"));
      } catch (NumberFormatException ignored) {
        slot = -1;
      }
      while (slot < 0 && bySlot.containsKey(fallbackSlot)) fallbackSlot++;
      bySlot.put(slot < 0 ? fallbackSlot++ : slot, item);
    }
    JsonArray slots = new JsonArray();
    for (int index = 0; index < size; index++) {
      JsonObject slot = new JsonObject();
      slot.addProperty("kind", inventorySlotKind(index));
      slot.addProperty("index", index);
      slot.addProperty("label", "Slot " + index);
      ItemEnvelope item = bySlot.get(index);
      slot.add("item", item == null ? JsonNull.INSTANCE : inventoryItemJson(item));
      slots.add(slot);
    }
    JsonObject response = new JsonObject();
    response.addProperty("online", view.online());
    response.addProperty("readOnly", false);
    response.addProperty("snapshotSource", view.online() ? "LIVE" : "PLAYERDATA");
    response.addProperty("offlineWriteEnabled", !view.online());
    response.addProperty("inventory", "PLAYER");
    response.addProperty("revision", Long.toUnsignedString(snapshot.version()));
    response.addProperty("refreshedAt", java.time.Instant.now().toString());
    response.addProperty("capturedAt", java.time.Instant.now().toString());
    response.add("slots", slots);
    return response;
  }

  private JsonObject deliveryStatusJson(SharedCommerceService.DeliveryStatus status) {
    JsonObject response = new JsonObject();
    response.addProperty("orderNo", status.orderNo());
    response.addProperty("status", status.status());
    response.addProperty("deliverySource", "SHARED_QUEUE");
    response.addProperty("playerOnline", false);
    JsonArray tasks = new JsonArray();
    for (SharedCommerceService.DeliveryTask task : status.deliveryTasks()) {
      JsonObject row = gson.toJsonTree(task).getAsJsonObject();
      row.addProperty("remainingQuantity", Math.max(0, task.quantity() - task.deliveredQuantity()));
      row.add("mailboxStatus", JsonNull.INSTANCE);
      row.addProperty("mailboxQuantity", 0);
      row.add("mailboxCreatedAt", JsonNull.INSTANCE);
      row.add("mailboxClaimedAt", JsonNull.INSTANCE);
      row.add("mailboxReason", JsonNull.INSTANCE);
      tasks.add(row);
    }
    response.add("deliveryTasks", tasks);
    return response;
  }

  private JsonObject inventoryItemJson(ItemEnvelope item) {
    String material = item.registryId();
    int separator = material.indexOf(':');
    if (separator >= 0) material = material.substring(separator + 1);
    material = material.toUpperCase(Locale.ROOT);
    JsonObject response = new JsonObject();
    response.addProperty("material", material);
    response.addProperty("name", item.summary().getOrDefault("name", material));
    response.addProperty("amount", item.count());
    response.addProperty("maxStackSize", 64);
    response.addProperty("fingerprint", item.payloadHash());
    response.add("lore", new JsonArray());
    response.add("enchantments", new JsonArray());
    response.add("itemMeta", gson.toJsonTree(item.summary()));
    response.addProperty("recyclable", true);
    response.addProperty("listable", true);
    return response;
  }

  private static String inventorySlotKind(int slot) {
    if (slot < 9) return "HOTBAR";
    if (slot >= 36 && slot <= 39) return "ARMOR";
    if (slot == 40) return "OFFHAND";
    return "MAIN";
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

  private static void requireTopLevelPlayerInventory(JsonObject input) {
    String inventory = optionalString(input, "inventory", "PLAYER");
    if (!"PLAYER".equalsIgnoreCase(inventory)
        || (input.has("containerSlot") && !input.get("containerSlot").isJsonNull())) {
      throw new ServiceException(
          "invalid_inventory_request", "Only top-level player inventory is supported");
    }
  }

  private static void requireDirectListingSettings(JsonObject input) {
    if (input.has("tags")
        && input.get("tags").isJsonArray()
        && !input.getAsJsonArray("tags").isEmpty()) {
      throw new ServiceException("capability_unavailable", "Listing tags are not configured");
    }
    String tradeMode = optionalString(input, "tradeMode", "DIRECT");
    if (!"DIRECT".equalsIgnoreCase(tradeMode)) {
      throw new ServiceException("capability_unavailable", "Auction settings are unavailable");
    }
    if (optionalBoolean(input, "dynamicPricingEnabled", false)) {
      throw new ServiceException("capability_unavailable", "Dynamic pricing is unavailable");
    }
    for (String key :
        List.of(
            "displayNameOverride",
            "displayMaterial",
            "displayIconPath",
            "dynamicAlgorithm",
            "dynamicPricingMode",
            "dynamicParamsJson",
            "dynamicBasePrice",
            "dynamicFloorPrice",
            "dynamicCapPrice",
            "dynamicPriceStep",
            "auctionAlgorithm",
            "auctionParamsJson",
            "auctionStartPrice",
            "auctionMinIncrement",
            "auctionEndAt",
            "supplyBatchSize",
            "supplyMaxStock",
            "supplyAccessProtected")) {
      if (input.has(key) && !input.get(key).isJsonNull()) {
        if (input.get(key).isJsonPrimitive()
            && input.get(key).getAsJsonPrimitive().isString()
            && input.get(key).getAsString().isBlank()) continue;
        throw new ServiceException(
            "capability_unavailable", "Advanced listing settings are unavailable");
      }
    }
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

  private static Integer nullableInt(JsonObject input, String key) {
    return input.has(key) && !input.get(key).isJsonNull() ? input.get(key).getAsInt() : null;
  }

  private static boolean optionalBoolean(JsonObject input, String key, boolean fallback) {
    return input.has(key) && !input.get(key).isJsonNull()
        ? input.get(key).getAsBoolean()
        : fallback;
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
