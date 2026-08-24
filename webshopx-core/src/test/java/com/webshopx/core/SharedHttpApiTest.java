package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webshopx.AdminAuditService;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.CurrencyType;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.NotificationService;
import com.webshopx.RedeemCodeService;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceCheckoutAdapter;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedContentService;
import com.webshopx.SharedMarketEscrowService;
import com.webshopx.SharedPromotionService;
import com.webshopx.WalletService;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.testkit.InMemoryInventoryGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedHttpApiTest {
  @TempDir Path temporaryDirectory;
  private DatabaseManager database;
  private SharedHttpApi api;
  private HttpClient client;
  private String base;
  private UUID supportTargetUuid;
  private UUID player;
  private InMemoryInventoryGateway inventories;

  @BeforeEach
  void start() {
    database =
        new DatabaseManager(
            null,
            new DatabaseSettings(
                DbType.SQLITE,
                "",
                0,
                "",
                "",
                "",
                false,
                false,
                "",
                2,
                temporaryDirectory.resolve("api.db").toString(),
                "WAL",
                "NORMAL",
                5_000,
                3,
                List.of(10, 25, 50)));
    database.start();
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    AuthService auth = new AuthService(database, () -> new AuthService.SessionSettings(40, 2));
    WalletService wallets =
        new WalletService(database, WalletService.ExchangePolicy::disabled, null, null);
    player = UUID.randomUUID();
    long user = auth.setPasswordFromGame(player, "ApiPlayer", "api-secret").userId();
    supportTargetUuid = UUID.randomUUID();
    long supportTarget =
        auth.setPasswordFromGame(supportTargetUuid, "SupportTarget", "target-secret").userId();
    wallets.adjustBalance(user, CurrencyType.SHOP_COIN, 500, "TEST", "api-seed");
    wallets.adjustBalance(
        supportTarget, CurrencyType.GAME_COIN, 100, "TEST", "api-market-buyer-seed");
    SharedCommerceService commerce = new SharedCommerceService(database, wallets);
    inventories = new InMemoryInventoryGateway(36);
    var item =
        new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture"))
            .create(
                "fixture",
                1,
                new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:test"),
                "minecraft:diamond",
                5,
                new byte[] {1, 2, 3},
                Map.of());
    inventories
        .compareAndApply(new InventoryMutation("seed", player, 0, List.of(item), List.of()))
        .toCompletableFuture()
        .join();
    commerce.createProduct(
        new SharedCommerceService.ProductInput(
            "API_STONE",
            "API Stone",
            null,
            CurrencyType.SHOP_COIN,
            50,
            SharedCommerceService.ProductKind.GIVE_ITEM,
            "",
            "minecraft:stone",
            5,
            true));
    AdminService admin = new AdminService(database, auth, wallets);
    admin.ensureBootstrapAdmin(
        new AdminService.AdminBootstrapSettings(true, "ApiPlayer", "api-secret", "SUPER_ADMIN"));
    EnumMap<CapabilitySnapshot.Capability, CapabilitySnapshot.CapabilityState> states =
        new EnumMap<>(CapabilitySnapshot.Capability.class);
    states.put(
        CapabilitySnapshot.Capability.HTTP_API,
        CapabilitySnapshot.CapabilityState.available("test"));
    api =
        new SharedHttpApi(
            "127.0.0.1",
            0,
            "https://shop.example",
            auth,
            wallets,
            commerce,
            new SharedMarketEscrowService(database, commerce, inventories),
            new SharedContentService(database),
            new SharedPromotionService(
                database, wallets, new SharedCommerceCheckoutAdapter(commerce, "node-a")),
            new RedeemCodeService(database, wallets),
            new NotificationService(database),
            admin,
            new AdminAuditService(database),
            new PlatformIdentity("fabric", "fabric", "1.20.1", "test", "node-a", "sha256:test"),
            new CapabilitySnapshot(Instant.now(), states));
    api.start();
    base = "http://127.0.0.1:" + api.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() {
    if (api != null) api.close();
    if (database != null) database.close();
  }

  @Test
  void authenticatedPurchaseIsIdempotentAndCorsIsExact() throws Exception {
    assertEquals(200, get("/health", null).statusCode());
    assertEquals(401, get("/api/wallet", null).statusCode());
    HttpResponse<String> login =
        post(
            "/api/auth/login",
            "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            null);
    assertEquals(200, login.statusCode());
    String token =
        JsonParser.parseString(login.body()).getAsJsonObject().get("token").getAsString();
    assertEquals(
        500,
        JsonParser.parseString(get("/api/wallet", token).body())
            .getAsJsonObject()
            .get("shopCoin")
            .getAsInt());
    JsonObject leaderboardConfig =
        JsonParser.parseString(get("/api/leaderboard/config", null).body()).getAsJsonObject();
    assertTrue(
        leaderboardConfig
            .getAsJsonObject("leaderboard")
            .get("enabled")
            .getAsBoolean());
    JsonObject leaderboard =
        JsonParser.parseString(
                get(
                        "/api/leaderboard/list?metric=SHOP_COIN&order=DESC&range=TOTAL&limit=10",
                        token)
                    .body())
            .getAsJsonObject();
    assertEquals(1, leaderboard.get("myRank").getAsLong());
    assertEquals(
        "ApiPlayer",
        leaderboard
            .getAsJsonArray("entries")
            .get(0)
            .getAsJsonObject()
            .get("username")
            .getAsString());
    JsonObject product =
        JsonParser.parseString(get("/api/products", null).body())
            .getAsJsonObject()
            .getAsJsonArray("products")
            .get(0)
            .getAsJsonObject();
    assertEquals("GIVE_ITEM", product.get("productType").getAsString());
    assertEquals(5, product.get("stock").getAsInt());
    JsonObject productQuote =
        JsonParser.parseString(
                post(
                        "/api/products/quote",
                        "{\"productId\":" + product.get("id").getAsLong() + ",\"quantity\":2}",
                        null,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals(100, productQuote.get("totalAmount").getAsLong());
    HttpResponse<String> emptyCart = get("/api/cart", token);
    assertEquals(200, emptyCart.statusCode());
    long cartVersion =
        JsonParser.parseString(emptyCart.body()).getAsJsonObject().get("version").getAsLong();
    String cartAdd =
        "{\"sourceType\":\"OFFICIAL_PRODUCT\",\"sourceId\":"
            + product.get("id").getAsLong()
            + ",\"quantity\":1,\"deliveryMode\":\"MAILBOX\",\"expectedVersion\":"
            + cartVersion
            + ",\"sourceVersion\":\"fixture-v1\",\"metadataJson\":\"{}\"}";
    HttpResponse<String> addedCart = post("/api/cart/lines/add", cartAdd, token, null);
    assertEquals(200, addedCart.statusCode());
    JsonObject addedCartJson = JsonParser.parseString(addedCart.body()).getAsJsonObject();
    assertEquals(1, addedCartJson.getAsJsonArray("lines").size());
    assertEquals(200, get("/api/coupons/mine", token).statusCode());
    assertEquals(200, get("/api/membership/me", token).statusCode());
    long populatedCartVersion = addedCartJson.get("version").getAsLong();
    long cartLineId =
        addedCartJson.getAsJsonArray("lines").get(0).getAsJsonObject().get("id").getAsLong();
    HttpResponse<String> quoted =
        post(
            "/api/checkout/quote",
            "{\"cartVersion\":"
                + populatedCartVersion
                + ",\"lineIds\":["
                + cartLineId
                + "],\"selectedRuleIds\":[],\"disabledRuleIds\":[]}",
            token,
            null);
    assertEquals(200, quoted.statusCode(), quoted.body());
    String quoteId =
        JsonParser.parseString(quoted.body()).getAsJsonObject().get("id").getAsString();
    String checkoutBody =
        "{\"quoteId\":\""
            + quoteId
            + "\",\"cartVersion\":"
            + populatedCartVersion
            + ",\"idempotencyKey\":\"api-checkout-1\"}";
    HttpResponse<String> checkout = post("/api/checkout/submit", checkoutBody, token, null);
    assertEquals(200, checkout.statusCode(), checkout.body());
    HttpResponse<String> checkoutReplay = post("/api/checkout/submit", checkoutBody, token, null);
    assertEquals(200, checkoutReplay.statusCode(), checkoutReplay.body());
    assertEquals(
        JsonParser.parseString(checkout.body()).getAsJsonObject().get("checkoutNo"),
        JsonParser.parseString(checkoutReplay.body()).getAsJsonObject().get("checkoutNo"));
    assertEquals(
        450,
        JsonParser.parseString(get("/api/wallet", token).body())
            .getAsJsonObject()
            .get("shopCoin")
            .getAsInt());
    String order =
        "{\"productId\":"
            + product.get("id").getAsLong()
            + ",\"quantity\":2,\"idempotencyKey\":\"api-order-1\"}";
    String first = post("/api/orders", order, token, null).body();
    String second = post("/api/orders", order, token, null).body();
    assertEquals(JsonParser.parseString(first), JsonParser.parseString(second));
    assertEquals(
        350,
        JsonParser.parseString(get("/api/wallet", token).body())
            .getAsJsonObject()
            .get("shopCoin")
            .getAsInt());
    assertEquals(
        2, JsonParser.parseString(get("/api/deliveries", token).body()).getAsJsonArray().size());
    JsonObject productTrend =
        JsonParser.parseString(
                get(
                        "/api/products/price-trend?productId="
                            + product.get("id").getAsLong(),
                        token)
                    .body())
            .getAsJsonObject();
    assertTrue(productTrend.getAsJsonArray("history").size() >= 1);
    assertEquals(
        50,
        productTrend
            .getAsJsonArray("history")
            .get(0)
            .getAsJsonObject()
            .get("price")
            .getAsLong());

    HttpResponse<String> allowed = post("/api/auth/logout", "{}", token, "https://shop.example");
    assertEquals(
        "https://shop.example",
        allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    HttpResponse<String> rejected =
        post(
            "/api/auth/login",
            "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            "https://evil.example");
    assertFalse(rejected.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    assertTrue(rejected.headers().firstValue("Content-Security-Policy").isPresent());
  }

  @Test
  void marketListingEscrowsServerInventoryAndReplaysIdempotently() throws Exception {
    HttpResponse<String> login =
        post(
            "/api/auth/login",
            "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            null);
    String token =
        JsonParser.parseString(login.body()).getAsJsonObject().get("token").getAsString();
    String buyerToken =
        JsonParser.parseString(
                post(
                        "/api/auth/login",
                        "{\"identifier\":\"SupportTarget\",\"password\":\"target-secret\"}",
                        null,
                        null)
                    .body())
            .getAsJsonObject()
            .get("token")
            .getAsString();
    String request =
        "{\"side\":\"SELL\",\"currency\":\"GAME_COIN\",\"price\":17,"
            + "\"quantity\":2,\"idempotencyKey\":\"market-secure-1\","
            + "\"item\":{\"registryId\":\"minecraft:netherite_block\",\"count\":64}}";
    HttpResponse<String> created = post("/api/market/listings/create", request, token, null);
    assertEquals(200, created.statusCode(), created.body());
    JsonObject listing = JsonParser.parseString(created.body()).getAsJsonObject();
    assertEquals(
        "minecraft:diamond", listing.getAsJsonObject("item").get("registryId").getAsString());
    assertEquals(2, listing.get("quantity").getAsInt());

    HttpResponse<String> replay = post("/api/market/listings/create", request, token, null);
    assertEquals(200, replay.statusCode(), replay.body());
    assertEquals(
        listing.get("id").getAsLong(),
        JsonParser.parseString(replay.body()).getAsJsonObject().get("id").getAsLong());
    assertEquals(
        3,
        inventories.snapshot(player, false).toCompletableFuture().join()
                instanceof com.webshopx.platform.PlatformResult.Success<?> success
            ? ((com.webshopx.platform.InventoryTypes.InventorySnapshot) success.value())
                .items()
                .get(0)
                .count()
            : -1);

    long listingId = listing.get("id").getAsLong();
    JsonObject quote =
        JsonParser.parseString(
                post(
                        "/api/market/quote",
                        "{\"listingId\":" + listingId + ",\"buyQuantity\":2}",
                        buyerToken,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals(34, quote.get("buyerTotal").getAsLong());
    assertEquals("SELL", quote.get("side").getAsString());

    String buyRequest =
        "{\"listingId\":"
            + listingId
            + ",\"buyQuantity\":1,\"expectedUnitPrice\":17,\"expectedBuyerTotal\":17,"
            + "\"idempotencyKey\":\"market-buy-1\"}";
    assertEquals(
        409,
        post(
                "/api/market/buy",
                "{\"listingId\":"
                    + listingId
                    + ",\"buyQuantity\":1,\"expectedUnitPrice\":99,"
                    + "\"expectedBuyerTotal\":99,\"idempotencyKey\":\"market-stale-1\"}",
                buyerToken,
                null)
            .statusCode());
    JsonObject trade =
        JsonParser.parseString(post("/api/market/buy", buyRequest, buyerToken, null).body())
            .getAsJsonObject();
    JsonObject tradeReplay =
        JsonParser.parseString(post("/api/market/buy", buyRequest, buyerToken, null).body())
            .getAsJsonObject();
    assertEquals(trade.get("id").getAsLong(), tradeReplay.get("id").getAsLong());
    assertEquals(
        83,
        JsonParser.parseString(get("/api/wallet", buyerToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    JsonObject trend =
        JsonParser.parseString(
                get("/api/market/price-trend?listingId=" + listingId, buyerToken).body())
            .getAsJsonObject();
    assertEquals(1, trend.getAsJsonArray("history").size());
    assertEquals(17, trend.getAsJsonArray("history").get(0).getAsJsonObject().get("price").getAsLong());

    JsonObject paused =
        JsonParser.parseString(
                post(
                        "/api/market/pause",
                        "{\"listingId\":" + listingId + "}",
                        token,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals("PAUSED", paused.get("status").getAsString());
    JsonObject price =
        JsonParser.parseString(
                post(
                        "/api/market/price",
                        "{\"listingId\":" + listingId + ",\"price\":23}",
                        token,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals(23, price.get("price").getAsLong());
    JsonObject remark =
        JsonParser.parseString(
                post(
                        "/api/market/remark",
                        "{\"listingId\":" + listingId + ",\"remark\":\"loader sale\"}",
                        token,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals("loader sale", remark.get("remark").getAsString());
    JsonObject resumed =
        JsonParser.parseString(
                post(
                        "/api/market/resume",
                        "{\"listingId\":" + listingId + "}",
                        token,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals("ACTIVE", resumed.get("status").getAsString());

    String unlistRequest = "{\"listingId\":" + listingId + "}";
    assertEquals(200, post("/api/market/unlist", unlistRequest, token, null).statusCode());
    assertEquals(200, post("/api/market/unlist", unlistRequest, token, null).statusCode());
    int returnTasks =
        database.withConnection(
            connection -> {
              try (var statement =
                  connection.prepareStatement(
                      "SELECT COUNT(*) FROM market_item_deliveries WHERE listing_id=?"
                          + " AND delivery_type='UNLIST'")) {
                statement.setLong(1, listingId);
                try (var result = statement.executeQuery()) {
                  result.next();
                  return result.getInt(1);
                }
              }
            });
    assertEquals(1, returnTasks);
  }

  @Test
  void inventorySnapshotAndListingMatchTheBrowserContract() throws Exception {
    HttpResponse<String> login =
        post(
            "/api/auth/login",
            "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            null);
    String token =
        JsonParser.parseString(login.body()).getAsJsonObject().get("token").getAsString();
    HttpResponse<String> snapshotResponse = get("/api/inventory/snapshot?inventory=PLAYER", token);
    assertEquals(200, snapshotResponse.statusCode(), snapshotResponse.body());
    JsonObject snapshot = JsonParser.parseString(snapshotResponse.body()).getAsJsonObject();
    assertTrue(snapshot.get("online").getAsBoolean());
    assertEquals(36, snapshot.getAsJsonArray("slots").size());
    JsonObject item =
        snapshot.getAsJsonArray("slots").get(0).getAsJsonObject().getAsJsonObject("item");
    assertEquals("DIAMOND", item.get("material").getAsString());
    String fingerprint = item.get("fingerprint").getAsString();
    String request =
        "{\"inventory\":\"PLAYER\",\"action\":\"LIST\",\"currency\":\"GAME_COIN\","
            + "\"price\":19,\"quantity\":2,\"idempotencyKey\":\"inventory-list-1\","
            + "\"fingerprint\":\""
            + fingerprint
            + "\"}";
    HttpResponse<String> created = post("/api/inventory/list", request, token, null);
    assertEquals(200, created.statusCode(), created.body());
    long listingId =
        JsonParser.parseString(created.body()).getAsJsonObject().get("listingId").getAsLong();
    HttpResponse<String> replay = post("/api/inventory/list", request, token, null);
    assertEquals(200, replay.statusCode(), replay.body());
    assertEquals(
        listingId,
        JsonParser.parseString(replay.body()).getAsJsonObject().get("listingId").getAsLong());
    JsonObject refreshed =
        JsonParser.parseString(get("/api/inventory/snapshot?inventory=PLAYER", token).body())
            .getAsJsonObject();
    assertEquals(
        3,
        refreshed
            .getAsJsonArray("slots")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("item")
            .get("amount")
            .getAsInt());
  }

  @Test
  void inventoryDiscardIsServerValidatedAndIdempotent() throws Exception {
    HttpResponse<String> login =
        post(
            "/api/auth/login",
            "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            null);
    String token =
        JsonParser.parseString(login.body()).getAsJsonObject().get("token").getAsString();
    JsonObject snapshot =
        JsonParser.parseString(get("/api/inventory/snapshot?inventory=PLAYER", token).body())
            .getAsJsonObject();
    String fingerprint =
        snapshot
            .getAsJsonArray("slots")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("item")
            .get("fingerprint")
            .getAsString();
    String request =
        "{\"inventory\":\"PLAYER\",\"quantity\":2,"
            + "\"idempotencyKey\":\"discard-1\",\"fingerprint\":\""
            + fingerprint
            + "\"}";
    HttpResponse<String> discarded = post("/api/inventory/discard", request, token, null);
    assertEquals(200, discarded.statusCode(), discarded.body());
    assertEquals(
        2,
        JsonParser.parseString(discarded.body())
            .getAsJsonObject()
            .get("discardedQuantity")
            .getAsInt());
    assertEquals(200, post("/api/inventory/discard", request, token, null).statusCode());
    JsonObject refreshed =
        JsonParser.parseString(get("/api/inventory/snapshot?inventory=PLAYER", token).body())
            .getAsJsonObject();
    assertEquals(
        3,
        refreshed
            .getAsJsonArray("slots")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("item")
            .get("amount")
            .getAsInt());
  }

  @Test
  void orderHistoryDeliveryAndRefundAreConsistentAndIdempotent() throws Exception {
    HttpResponse<String> login =
        post(
            "/api/auth/login",
            "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            null);
    String token =
        JsonParser.parseString(login.body()).getAsJsonObject().get("token").getAsString();
    JsonObject order =
        JsonParser.parseString(
                post(
                        "/api/orders",
                        "{\"productId\":1,\"quantity\":2,\"idempotencyKey\":\"order-view-1\"}",
                        token,
                        null)
                    .body())
            .getAsJsonObject();
    String orderNo = order.get("orderNo").getAsString();
    HttpResponse<String> list = get("/api/orders/list?limit=10", token);
    assertEquals(200, list.statusCode(), list.body());
    JsonObject listed =
        JsonParser.parseString(list.body())
            .getAsJsonObject()
            .getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject();
    assertEquals(orderNo, listed.get("orderNo").getAsString());
    assertTrue(listed.get("canRefund").getAsBoolean());
    HttpResponse<String> delivery = get("/api/orders/delivery-status?orderNo=" + orderNo, token);
    assertEquals(200, delivery.statusCode(), delivery.body());
    assertEquals(
        "PENDING",
        JsonParser.parseString(delivery.body())
            .getAsJsonObject()
            .getAsJsonArray("deliveryTasks")
            .get(0)
            .getAsJsonObject()
            .get("status")
            .getAsString());
    String refundRequest = "{\"orderNo\":\"" + orderNo + "\"}";
    HttpResponse<String> refunded = post("/api/orders/refund", refundRequest, token, null);
    assertEquals(200, refunded.statusCode(), refunded.body());
    assertEquals(
        100,
        JsonParser.parseString(refunded.body()).getAsJsonObject().get("refundAmount").getAsInt());
    assertEquals(200, post("/api/orders/refund", refundRequest, token, null).statusCode());
    assertEquals(
        500,
        JsonParser.parseString(get("/api/wallet", token).body())
            .getAsJsonObject()
            .get("shopCoin")
            .getAsInt());
    assertEquals(200, get("/api/orders/policy", token).statusCode());
  }

  @Test
  void undeliveredOrderCanBeDiscardedWithoutARefund() throws Exception {
    HttpResponse<String> login =
        post(
            "/api/auth/login",
            "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            null);
    String token =
        JsonParser.parseString(login.body()).getAsJsonObject().get("token").getAsString();
    JsonObject order =
        JsonParser.parseString(
                post(
                        "/api/orders",
                        "{\"productId\":1,\"quantity\":2,\"idempotencyKey\":\"order-discard-1\"}",
                        token,
                        null)
                    .body())
            .getAsJsonObject();
    String request = "{\"orderNo\":\"" + order.get("orderNo").getAsString() + "\"}";
    assertEquals(200, post("/api/orders/discard", request, token, null).statusCode());
    assertEquals(200, post("/api/orders/discard", request, token, null).statusCode());
    assertEquals(
        400,
        JsonParser.parseString(get("/api/wallet", token).body())
            .getAsJsonObject()
            .get("shopCoin")
            .getAsInt());
  }

  @Test
  void oversizedAndMalformedRequestsFailClosed() throws Exception {
    assertEquals(413, post("/api/auth/login", "x".repeat(70_000), null, null).statusCode());
    assertEquals(400, post("/api/auth/login", "[]", null, null).statusCode());
  }

  @Test
  void adminSupportAndAccessManagementMatchFrontendContract() throws Exception {
    HttpResponse<String> login =
        post(
            "/api/admin/auth/login",
            "{\"username\":\"ApiPlayer\",\"password\":\"api-secret\"}",
            null,
            null);
    assertEquals(200, login.statusCode(), login.body());
    JsonObject loginJson = JsonParser.parseString(login.body()).getAsJsonObject();
    assertTrue(loginJson.has("token"));
    assertTrue(loginJson.has("admin"));
    String token = loginJson.get("token").getAsString();
    assertFalse(
        JsonParser.parseString(get("/api/homepage", null).body())
            .getAsJsonObject()
            .get("enabled")
            .getAsBoolean());
    assertEquals(200, get("/api/homepage/status", null).statusCode());
    assertEquals(200, get("/api/admin/homepage/draft", token).statusCode());
    HttpResponse<String> savedHomepage =
        put(
            "/api/admin/homepage/draft",
            "{\"schemaVersion\":2,\"enabled\":true,\"site\":{},\"theme\":{},"
                + "\"sections\":[{\"id\":\"hero\",\"type\":\"hero\"}]}",
            token);
    assertEquals(200, savedHomepage.statusCode(), savedHomepage.body());
    assertEquals(200, post("/api/admin/homepage/publish", "{}", token, null).statusCode());
    assertTrue(
        JsonParser.parseString(get("/api/homepage", null).body())
            .getAsJsonObject()
            .get("enabled")
            .getAsBoolean());
    assertEquals(200, get("/api/admin/homepage/revisions", token).statusCode());
    assertEquals(200, get("/api/admin/homepage/assets", token).statusCode());
    assertEquals(
        200,
        post(
                "/api/admin/material-overrides/upsert",
                "{\"materialKey\":\"minecraft:stone\","
                    + "\"displayNameOverride\":\"Polished API Stone\","
                    + "\"iconPath\":\"/assets/stone.webp\"}",
                token,
                null)
            .statusCode());
    assertEquals(
        1,
        JsonParser.parseString(get("/api/meta/material-overrides", null).body())
            .getAsJsonArray()
            .size());
    assertEquals(
        1,
        JsonParser.parseString(get("/api/admin/material-overrides/list", token).body())
            .getAsJsonObject()
            .getAsJsonArray("items")
            .size());
    assertEquals(
        200,
        post(
                "/api/admin/material-overrides/delete",
                "{\"materialKey\":\"minecraft:stone\"}",
                token,
                null)
            .statusCode());
    assertEquals(200, get("/api/admin/auth/me", token).statusCode());
    assertEquals(200, get("/api/admin/users/list", token).statusCode());
    HttpResponse<String> lookup = get("/api/admin/users/lookup?identifier=SupportTarget", token);
    assertEquals(200, lookup.statusCode(), lookup.body());
    long targetId = JsonParser.parseString(lookup.body()).getAsJsonObject().get("id").getAsLong();
    assertEquals(
        200,
        post(
                "/api/admin/users/wallet-adjust",
                "{\"userId\":"
                    + targetId
                    + ",\"currency\":\"SHOP_COIN\",\"delta\":25,\"reason\":\"SUPPORT\"}",
                token,
                null)
            .statusCode());
    UUID migratedUuid = UUID.randomUUID();
    assertEquals(
        200,
        post(
                "/api/admin/users/migrate-uuid",
                "{\"userId\":"
                    + targetId
                    + ",\"oldUuid\":\""
                    + supportTargetUuid
                    + "\",\"newUuid\":\""
                    + migratedUuid
                    + "\"}",
                token,
                null)
            .statusCode());
    assertEquals(
        200,
        post(
                "/api/admin/users/reset-password",
                "{\"userId\":" + targetId + ",\"newPassword\":\"target-new-secret\"}",
                token,
                null)
            .statusCode());
    assertEquals(
        200,
        post(
                "/api/admin/admin-users/upsert",
                "{\"identifier\":\"SupportTarget\",\"isSuperAdmin\":false,"
                    + "\"permissions\":[\"USER_SUPPORT\"]}",
                token,
                null)
            .statusCode());
    assertEquals(200, get("/api/admin/admin-users/meta?locale=en-US", token).statusCode());
    JsonObject admins =
        JsonParser.parseString(get("/api/admin/admin-users/list", token).body()).getAsJsonObject();
    assertEquals(2, admins.getAsJsonArray("admins").size());
    assertEquals(
        200,
        post(
                "/api/admin/admin-users/active",
                "{\"userId\":" + targetId + ",\"active\":false}",
                token,
                null)
            .statusCode());
    assertEquals(
        200,
        post("/api/admin/users/logout", "{\"userId\":" + targetId + "}", token, null).statusCode());
    assertEquals(
        200,
        post("/api/admin/users/unbind", "{\"userId\":" + targetId + "}", token, null).statusCode());
    assertEquals(200, get("/api/admin/audit/list", token).statusCode());
    assertEquals(200, post("/api/admin/auth/logout", "{}", token, null).statusCode());
    assertEquals(401, get("/api/admin/auth/me", token).statusCode());
  }

  @Test
  void servesLandingAndDistinguishesContractCapabilities() throws Exception {
    HttpResponse<String> landing = get("/", null);
    assertEquals(200, landing.statusCode());
    assertTrue(landing.body().contains("WebShopX"));
    assertEquals(501, get("/api/orders/refund", null).statusCode());
    assertEquals(404, get("/not-a-webshopx-route.json", null).statusCode());
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body, String token, String origin)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (origin != null) request.header("Origin", origin);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> put(String path, String body, String token) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
