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
import com.webshopx.RefundPolicyService;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceCheckoutAdapter;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedContentService;
import com.webshopx.SharedMarketEscrowService;
import com.webshopx.SharedPromotionService;
import com.webshopx.WalletService;
import com.webshopx.payment.api.PaymentConfigDescriptor;
import com.webshopx.payment.api.PaymentConfigSnapshot;
import com.webshopx.payment.api.PaymentConfigUpdateRequest;
import com.webshopx.payment.api.PaymentConfigUpdateResult;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import com.webshopx.platform.SupplyInventoryGateway;
import com.webshopx.testkit.InMemoryInventoryGateway;
import java.io.ByteArrayOutputStream;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
  private long playerUserId;
  private InMemoryInventoryGateway inventories;
  private ItemEnvelope inventoryFixture;
  private SharedCommerceService commerce;
  private AuthService auth;
  private WalletService wallets;
  private FixtureSupplyGateway supplyGateway;

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
    auth = new AuthService(database, () -> new AuthService.SessionSettings(40, 2));
    wallets = new WalletService(database, WalletService.ExchangePolicy::disabled, null, null);
    player = UUID.randomUUID();
    playerUserId = auth.setPasswordFromGame(player, "ApiPlayer", "api-secret").userId();
    long user = playerUserId;
    supportTargetUuid = UUID.randomUUID();
    long supportTarget =
        auth.setPasswordFromGame(supportTargetUuid, "SupportTarget", "target-secret").userId();
    wallets.adjustBalance(user, CurrencyType.SHOP_COIN, 500, "TEST", "api-seed");
    wallets.adjustBalance(
        supportTarget, CurrencyType.GAME_COIN, 100, "TEST", "api-market-buyer-seed");
    commerce = new SharedCommerceService(database, wallets);
    commerce.registerPaymentProvider(
        new SharedCommerceService.PaymentProvider() {
          @Override
          public String id() {
            return "fixture-pay";
          }

          @Override
          public SharedCommerceService.PaymentSession create(
              String orderId, long amountMinor, String currency, String description) {
            return new SharedCommerceService.PaymentSession(
                "provider-" + orderId,
                "https://pay.example/" + orderId,
                null,
                Instant.now().plusSeconds(600));
          }

          @Override
          public SharedCommerceService.PaymentProviderConfiguration configuration(String locale) {
            return new SharedCommerceService.PaymentProviderConfiguration(
                id(),
                "Fixture Pay",
                new PaymentConfigDescriptor(1, List.of()),
                new PaymentConfigSnapshot(Map.of("endpoint", "https://pay.example"), Set.of("secret")),
                Set.of("en-US", "zh-CN"));
          }

          @Override
          public PaymentConfigUpdateResult updateConfiguration(PaymentConfigUpdateRequest request) {
            return PaymentConfigUpdateResult.applied("updated");
          }
        });
    inventories = new InMemoryInventoryGateway(36);
    inventoryFixture =
        new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture"))
            .create(
                "fixture",
                1,
                new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:test"),
                "minecraft:diamond",
                5,
                new byte[] {1, 2, 3},
                Map.of());
    supplyGateway = new FixtureSupplyGateway(inventoryFixture, 12);
    inventories
            .compareAndApply(
                new InventoryMutation("seed", player, 0, List.of(inventoryFixture), List.of()))
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
            new RefundPolicyService(database),
            new com.webshopx.SharedRuntimeConfigService(database),
            new PlatformIdentity("fabric", "fabric", "1.20.1", "test", "node-a", "sha256:test"),
            new CapabilitySnapshot(Instant.now(), states),
            supplyGateway);
    api.start();
    base = "http://127.0.0.1:" + api.port();
    client = HttpClient.newHttpClient();
  }

  @Test
  void supplyRefreshUsesAuthenticatedSharedContractAndReplaysIdempotently() throws Exception {
    long listingId = commerce.createListing(new SharedCommerceService.ListingRequest(
        playerUserId, player, CurrencyType.SHOP_COIN, 10, 1, inventoryFixture, null)).id();
    database.withConnection(connection -> {
      try (var statement = connection.prepareStatement(
          "UPDATE market_listings SET source_mode='SUPPLY',supply_world='minecraft:overworld',"
              + "supply_x=4,supply_y=65,supply_z=8,supply_batch_size=5,supply_max_stock=10,"
              + "quantity=0,status='SUPPLY_EMPTY' WHERE id=?")) {
        statement.setLong(1, listingId);
        statement.executeUpdate();
      }
      return null;
    });
    String token = JsonParser.parseString(post(
        "/api/auth/login", "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
        null, null).body()).getAsJsonObject().get("token").getAsString();
    String body = "{\"listingId\":" + listingId + ",\"idempotencyKey\":\"http-supply-1\"}";
    String otherToken = JsonParser.parseString(post(
        "/api/auth/login", "{\"identifier\":\"SupportTarget\",\"password\":\"target-secret\"}",
        null, null).body()).getAsJsonObject().get("token").getAsString();
    assertEquals(403, post("/api/market/supply/refresh", body, otherToken, null).statusCode());

    HttpResponse<String> first = post("/api/market/supply/refresh", body, token, null);
    HttpResponse<String> replay = post("/api/market/supply/refresh", body, token, null);
    assertEquals(200, first.statusCode());
    assertEquals(first.body(), replay.body());
    JsonObject result = JsonParser.parseString(first.body()).getAsJsonObject();
    assertEquals(5, result.get("loadedAmount").getAsInt());
    assertEquals(5, result.get("currentStock").getAsInt());
    assertEquals(7, supplyGateway.quantity);
    HttpResponse<String> settings = post(
        "/api/market/settings",
        "{\"listingId\":" + listingId + ",\"price\":12,\"currency\":\"SHOP_COIN\","
            + "\"remark\":\"restocked\",\"tradeMode\":\"DIRECT\","
            + "\"dynamicPricingEnabled\":false,\"supplyBatchSize\":4,"
            + "\"supplyMaxStock\":9,\"supplyAccessProtected\":false}",
        token, null);
    assertEquals(200, settings.statusCode(), settings.body());
    JsonObject updated = JsonParser.parseString(settings.body()).getAsJsonObject();
    assertEquals(4, updated.get("supplyBatchSize").getAsInt());
    assertEquals(9, updated.get("supplyMaxStock").getAsInt());
    assertFalse(updated.get("supplyAccessProtected").getAsBoolean());

    JsonObject listing = JsonParser.parseString(get("/api/market/listings", token).body())
        .getAsJsonObject().getAsJsonArray("listings").asList().stream()
        .map(element -> element.getAsJsonObject())
        .filter(candidate -> candidate.get("id").getAsLong() == listingId)
        .findFirst().orElseThrow();
    assertEquals("SUPPLY", listing.get("sourceMode").getAsString());
    assertEquals(5, listing.get("supplyLoadedTotal").getAsInt());
    assertEquals(5, listing.get("quantity").getAsInt());
    long manualId = commerce.createListing(new SharedCommerceService.ListingRequest(
        playerUserId, player, CurrencyType.SHOP_COIN, 10, 1, inventoryFixture, null)).id();
    JsonObject manual = JsonParser.parseString(get("/api/market/listings", token).body())
        .getAsJsonObject().getAsJsonArray("listings").asList().stream()
        .map(element -> element.getAsJsonObject())
        .filter(candidate -> candidate.get("id").getAsLong() == manualId)
        .findFirst().orElseThrow();
    assertEquals("MANUAL", manual.get("sourceMode").getAsString());
  }

  @Test
  void reconcilesUnknownSupplyThroughOwnerAndAuditedAdminContracts() throws Exception {
    long listingId = commerce.createListing(new SharedCommerceService.ListingRequest(
        playerUserId, player, CurrencyType.SHOP_COIN, 10, 1, inventoryFixture, null)).id();
    database.withConnection(connection -> {
      try (var statement = connection.prepareStatement(
          "UPDATE market_listings SET source_mode='SUPPLY',supply_world='minecraft:overworld',"
              + "supply_x=4,supply_y=65,supply_z=8,supply_batch_size=5,supply_max_stock=10,"
              + "quantity=0,status='SUPPLY_EMPTY' WHERE id=?")) {
        statement.setLong(1, listingId);
        statement.executeUpdate();
      }
      return null;
    });
    String ownerToken = JsonParser.parseString(post(
        "/api/auth/login", "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
        null, null).body()).getAsJsonObject().get("token").getAsString();
    String supportToken = JsonParser.parseString(post(
        "/api/auth/login", "{\"identifier\":\"SupportTarget\",\"password\":\"target-secret\"}",
        null, null).body()).getAsJsonObject().get("token").getAsString();

    supplyGateway.returnUnknown = true;
    supplyGateway.retainReconciliation = true;
    HttpResponse<String> unknown = post(
        "/api/market/supply/refresh",
        "{\"listingId\":" + listingId + ",\"idempotencyKey\":\"http-unknown-owner\"}",
        ownerToken, null);
    assertEquals(503, unknown.statusCode(), unknown.body());
    HttpResponse<String> ownerReconciled = post(
        "/api/market/supply/reconcile", "{\"operationId\":\"http-unknown-owner\"}",
        ownerToken, null);
    assertEquals(200, ownerReconciled.statusCode(), ownerReconciled.body());
    assertEquals(5, JsonParser.parseString(ownerReconciled.body())
        .getAsJsonObject().get("loadedAmount").getAsInt());

    supplyGateway.retainReconciliation = false;
    supplyGateway.reconciliation = null;
    post(
        "/api/market/supply/refresh",
        "{\"listingId\":" + listingId + ",\"idempotencyKey\":\"http-unknown-admin\"}",
        ownerToken, null);
    String resolution = "{\"operationId\":\"http-unknown-admin\","
        + "\"resolution\":\"APPLIED\",\"removedQuantity\":5}";
    assertEquals(403, get(
        "/api/admin/market/supply/unknown", supportToken).statusCode());
    HttpResponse<String> unknownList = get(
        "/api/admin/market/supply/unknown", ownerToken);
    assertEquals(200, unknownList.statusCode(), unknownList.body());
    assertTrue(unknownList.body().contains("http-unknown-admin"));
    assertEquals(403, post(
        "/api/admin/market/supply/reconcile", resolution, supportToken, null).statusCode());
    HttpResponse<String> adminReconciled = post(
        "/api/admin/market/supply/reconcile", resolution, ownerToken, null);
    assertEquals(200, adminReconciled.statusCode(), adminReconciled.body());
    assertTrue(get("/api/admin/audit/list", ownerToken).body()
        .contains("MARKET_SUPPLY_RECONCILE"));
  }

  @Test
  void inspectsNearbyContainerAndCreatesSupplyListingWithoutClientMod() throws Exception {
    String token = JsonParser.parseString(post(
        "/api/auth/login", "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
        null, null).body()).getAsJsonObject().get("token").getAsString();
    HttpResponse<String> inspection = get(
        "/api/market/supply/inspect?world=minecraft:overworld&x=4&y=65&z=8", token);
    assertEquals(200, inspection.statusCode(), inspection.body());
    assertEquals(inventoryFixture.payloadHash(),
        JsonParser.parseString(inspection.body()).getAsJsonObject().getAsJsonArray("items")
            .get(0).getAsJsonObject().get("payloadHash").getAsString());
    String request = "{\"side\":\"SELL\",\"sourceMode\":\"SUPPLY\","
        + "\"currency\":\"SHOP_COIN\",\"price\":30,"
        + "\"supplyWorld\":\"minecraft:overworld\",\"supplyX\":4,\"supplyY\":65,"
        + "\"supplyZ\":8,\"supplyBatchSize\":5,\"supplyMaxStock\":10,"
        + "\"supplyAccessProtected\":true,\"expectedPayloadHash\":\""
        + inventoryFixture.payloadHash() + "\",\"idempotencyKey\":\"http-supply-create\"}";

    HttpResponse<String> created = post("/api/market/listings/create", request, token, null);
    HttpResponse<String> replay = post("/api/market/listings/create", request, token, null);
    assertEquals(200, created.statusCode(), created.body());
    assertEquals(created.body(), replay.body());
    JsonObject listing = JsonParser.parseString(created.body()).getAsJsonObject();
    assertEquals("SUPPLY", listing.get("sourceMode").getAsString());
    assertEquals(5, listing.get("quantity").getAsInt());
    assertEquals(7, supplyGateway.quantity);
  }

  @AfterEach
  void stop() {
    if (api != null) api.close();
    if (database != null) database.close();
  }

  @Test
  void authenticatedPurchaseIsIdempotentAndCorsIsExact() throws Exception {
    assertEquals(200, get("/health", null).statusCode());
    JsonObject currencyMeta =
        JsonParser.parseString(get("/api/meta/currency", null).body()).getAsJsonObject();
    assertEquals("SC", currencyMeta.getAsJsonObject("shopCoin").get("short").getAsString());
    assertFalse(
        currencyMeta
            .getAsJsonObject("exchange")
            .getAsJsonObject("shopToGame")
            .get("enabled")
            .getAsBoolean());
    JsonObject localeMeta =
        JsonParser.parseString(get("/api/meta/locales", null).body()).getAsJsonObject();
    assertEquals("zh-CN", localeMeta.get("defaultLocale").getAsString());
    assertEquals(2, localeMeta.getAsJsonArray("locales").size());
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
    String contentSecurityPolicy =
        rejected.headers().firstValue("Content-Security-Policy").orElseThrow();
    assertTrue(contentSecurityPolicy.contains("frame-ancestors 'self'"));
    assertTrue(contentSecurityPolicy.contains("object-src 'none'"));
  }

  @Test
  void rechargeStatusAndCancellationEnforceOwnershipAndAreIdempotent() throws Exception {
    String ownerToken =
        JsonParser.parseString(
                post(
                        "/api/auth/login",
                        "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
                        null,
                        null)
                    .body())
            .getAsJsonObject()
            .get("token")
            .getAsString();
    String otherToken =
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
    HttpResponse<String> created =
        post(
            "/api/recharge/create",
            "{\"amountMinor\":100,\"currency\":\"CNY\",\"coinAmount\":10,"
                + "\"provider\":\"fixture-pay\",\"idempotencyKey\":\"recharge-api-1\"}",
            ownerToken,
            null);
    assertEquals(200, created.statusCode(), created.body());
    JsonObject createdRecharge = JsonParser.parseString(created.body()).getAsJsonObject();
    String orderId = createdRecharge.get("orderId").getAsString();
    String redirectPath = createdRecharge.get("payUrl").getAsString();
    assertTrue(redirectPath.startsWith("/api/recharge/redirect?id="));
    HttpResponse<String> redirect = get(redirectPath, null);
    assertEquals(302, redirect.statusCode());
    assertEquals(
        "https://pay.example/" + orderId,
        redirect.headers().firstValue("Location").orElseThrow());
    assertEquals(
        403,
        get("/api/recharge/status?orderId=" + orderId, otherToken).statusCode());
    String cancelBody = "{\"orderId\":\"" + orderId + "\"}";
    assertEquals(403, post("/api/recharge/cancel", cancelBody, otherToken, null).statusCode());
    assertEquals(200, post("/api/recharge/cancel", cancelBody, ownerToken, null).statusCode());
    assertEquals(200, post("/api/recharge/cancel", cancelBody, ownerToken, null).statusCode());
    JsonObject status =
        JsonParser.parseString(
                get("/api/recharge/status?orderId=" + orderId, ownerToken).body())
            .getAsJsonObject();
    assertEquals("CANCELLED", status.get("status").getAsString());
  }

  @Test
  void standaloneMailboxClaimUsesNativeInventoryAndIsIdempotent() throws Exception {
    var mailboxItem =
        new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture"))
            .create(
                "fixture",
                1,
                new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:test"),
                "minecraft:diamond",
                2,
                new byte[] {4, 5, 6},
                Map.of());
    long mailboxId =
        database.inTransaction(
            connection -> {
              long userId;
              try (var user =
                      connection.prepareStatement(
                          "SELECT id FROM web_users WHERE username='ApiPlayer'");
                  var result = user.executeQuery()) {
                result.next();
                userId = result.getLong(1);
              }
              try (var statement =
                  connection.prepareStatement(
                      "INSERT INTO mailbox_items"
                          + " (user_id,target_uuid,source_type,source_ref,item_blob,quantity,reason)"
                          + " VALUES (?,?,?,?,?,?,?)",
                      java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, userId);
                statement.setString(2, player.toString());
                statement.setString(3, "DELIVERY");
                statement.setString(4, "fixture-mailbox");
                statement.setBytes(5, new ItemEnvelopeBinaryCodec().encode(mailboxItem));
                statement.setInt(6, 2);
                statement.setString(7, "offline delivery");
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                  keys.next();
                  return keys.getLong(1);
                }
              }
            });
    String token =
        JsonParser.parseString(
                post(
                        "/api/auth/login",
                        "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
                        null,
                        null)
                    .body())
            .getAsJsonObject()
            .get("token")
            .getAsString();
    JsonObject mailbox =
        JsonParser.parseString(get("/api/mailbox/list", token).body()).getAsJsonObject();
    assertEquals(1, mailbox.get("count").getAsInt());
    String entryId =
        mailbox.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString();
    assertEquals("MAILBOX:" + mailboxId, entryId);
    String claimPath = "/api/mailbox/" + entryId + "/claim";
    assertEquals(200, post(claimPath, "{}", token, null).statusCode());
    assertEquals(200, post(claimPath, "{}", token, null).statusCode());
    assertEquals(0, JsonParser.parseString(get("/api/mailbox/count", token).body())
        .getAsJsonObject().get("count").getAsInt());
    int inventoryTotal =
        ((com.webshopx.platform.PlatformResult.Success<
                    com.webshopx.platform.InventoryTypes.InventorySnapshot>)
                inventories.snapshot(player, false).toCompletableFuture().join())
            .value()
            .items()
            .stream()
            .mapToInt(com.webshopx.platform.ItemEnvelope::count)
            .sum();
    assertEquals(7, inventoryTotal);
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
    assertEquals("minecraft:diamond", listing.get("itemMaterial").getAsString());
    assertFalse(listing.has("item"));
    assertFalse(listing.toString().contains("nativePayload"));
    assertEquals(2, listing.get("quantity").getAsInt());

    HttpResponse<String> replay = post("/api/market/listings/create", request, token, null);
    assertEquals(200, replay.statusCode(), replay.body());
    assertEquals(
        listing.get("id").getAsLong(),
        JsonParser.parseString(replay.body()).getAsJsonObject().get("id").getAsLong());
    JsonObject publicListings =
        JsonParser.parseString(get("/api/market/listings", null).body()).getAsJsonObject();
    JsonObject publicListing = publicListings.getAsJsonArray("listings").get(0).getAsJsonObject();
    assertEquals("minecraft:diamond", publicListing.get("itemMaterial").getAsString());
    assertFalse(publicListing.has("item"));
    assertFalse(publicListing.toString().contains("nativePayload"));
    assertEquals(
        1,
        JsonParser.parseString(get("/api/market/listings?mine=true", token).body())
            .getAsJsonObject()
            .getAsJsonArray("listings")
            .size());
    assertEquals(
        0,
        JsonParser.parseString(get("/api/market/listings?mine=true", buyerToken).body())
            .getAsJsonObject()
            .getAsJsonArray("listings")
            .size());
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
    JsonObject buyerMailbox =
        JsonParser.parseString(get("/api/mailbox/list", buyerToken).body()).getAsJsonObject();
    assertEquals(1, buyerMailbox.get("count").getAsInt());
    String marketEntryId =
        buyerMailbox
            .getAsJsonArray("items")
            .get(0)
            .getAsJsonObject()
            .get("id")
            .getAsString();
    assertTrue(marketEntryId.startsWith("MARKET:"));
    assertEquals(
        200,
        post("/api/mailbox/" + marketEntryId + "/claim", "{}", buyerToken, null)
            .statusCode());
    assertEquals(
        200,
        post("/api/mailbox/" + marketEntryId + "/claim", "{}", buyerToken, null)
            .statusCode());
    assertEquals(
        0,
        JsonParser.parseString(get("/api/mailbox/count", buyerToken).body())
            .getAsJsonObject()
            .get("count")
            .getAsInt());
    int buyerInventoryTotal =
        ((com.webshopx.platform.PlatformResult.Success<
                    com.webshopx.platform.InventoryTypes.InventorySnapshot>)
                inventories.snapshot(supportTargetUuid, false).toCompletableFuture().join())
            .value()
            .items()
            .stream()
            .mapToInt(com.webshopx.platform.ItemEnvelope::count)
            .sum();
    assertEquals(1, buyerInventoryTotal);
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
    JsonObject settingsUpdated =
        JsonParser.parseString(
                post(
                        "/api/market/settings",
                        "{\"listingId\":"
                            + listingId
                            + ",\"price\":25,\"currency\":\"GAME_COIN\","
                            + "\"remark\":\"combined settings\",\"tags\":[],"
                            + "\"tradeMode\":\"DIRECT\",\"dynamicPricingEnabled\":false}",
                        token,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals(25, settingsUpdated.get("price").getAsLong());
    assertEquals("combined settings", settingsUpdated.get("remark").getAsString());
    HttpResponse<String> dynamicSettings = post(
        "/api/market/settings",
        "{\"listingId\":"
            + listingId
            + ",\"price\":25,\"currency\":\"GAME_COIN\","
            + "\"tradeMode\":\"DIRECT\",\"dynamicPricingEnabled\":true,"
            + "\"dynamicAlgorithm\":\"LINEAR_DEMAND_V1\","
            + "\"dynamicPricingMode\":\"PER_UNIT_MARGINAL\",\"dynamicPriceStep\":2}",
        token,
        null);
    assertEquals(200, dynamicSettings.statusCode(), dynamicSettings.body());
    JsonObject dynamicListing = JsonParser.parseString(dynamicSettings.body()).getAsJsonObject();
    assertTrue(dynamicListing.get("dynamicPricingEnabled").getAsBoolean());
    assertEquals("PER_UNIT_MARGINAL", dynamicListing.get("dynamicPricingMode").getAsString());

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

    String buyListingRequest =
        "{\"side\":\"BUY\",\"currency\":\"GAME_COIN\",\"price\":10,"
            + "\"quantity\":2,\"itemMaterial\":\"DIAMOND\","
            + "\"idempotencyKey\":\"market-buy-listing-1\"}";
    JsonObject buyListing =
        JsonParser.parseString(
                post(
                        "/api/market/listings/create",
                        buyListingRequest,
                        buyerToken,
                        null)
                    .body())
            .getAsJsonObject();
    JsonObject buyListingReplay =
        JsonParser.parseString(
                post(
                        "/api/market/listings/create",
                        buyListingRequest,
                        buyerToken,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals("BUY", buyListing.get("side").getAsString());
    assertEquals(buyListing.get("id").getAsLong(), buyListingReplay.get("id").getAsLong());
    assertEquals(
        63,
        JsonParser.parseString(get("/api/wallet", buyerToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    JsonObject repricedBuy =
        JsonParser.parseString(
                post(
                        "/api/market/price",
                        "{\"listingId\":" + buyListing.get("id").getAsLong() + ",\"price\":12}",
                        buyerToken,
                        null)
                    .body())
            .getAsJsonObject();
    assertEquals(12, repricedBuy.get("price").getAsLong());
    assertEquals(
        59,
        JsonParser.parseString(get("/api/wallet", buyerToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    JsonObject sellerInventory =
        JsonParser.parseString(get("/api/inventory/snapshot?inventory=PLAYER", token).body())
            .getAsJsonObject();
    String sellerFingerprint =
        sellerInventory
            .getAsJsonArray("slots")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("item")
            .get("fingerprint")
            .getAsString();
    String matchRequest =
        "{\"inventory\":\"PLAYER\",\"quantity\":1,\"fingerprint\":\""
            + sellerFingerprint
            + "\"}";
    JsonObject matches =
        JsonParser.parseString(post("/api/inventory/matches", matchRequest, token, null).body())
            .getAsJsonObject();
    assertEquals(1, matches.getAsJsonArray("matches").size());
    String fulfillRequest =
        "{\"inventory\":\"PLAYER\",\"listingId\":\""
            + buyListing.get("id").getAsLong()
            + "\",\"quantity\":1,\"fingerprint\":\""
            + sellerFingerprint
            + "\",\"expectedUnitPrice\":12,\"expectedBuyerTotal\":12,"
            + "\"idempotencyKey\":\"inventory-fulfill-1\"}";
    JsonObject fulfilled =
        JsonParser.parseString(
                post("/api/inventory/fulfill", fulfillRequest, token, null).body())
            .getAsJsonObject();
    JsonObject fulfilledReplay =
        JsonParser.parseString(
                post("/api/inventory/fulfill", fulfillRequest, token, null).body())
            .getAsJsonObject();
    assertEquals(
        fulfilled.get("tradeId").getAsLong(), fulfilledReplay.get("tradeId").getAsLong());
    assertEquals(
        29,
        JsonParser.parseString(get("/api/wallet", token).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    String sellToBuyRequest =
        "{\"listingId\":"
            + buyListing.get("id").getAsLong()
            + ",\"sellQuantity\":1,\"expectedUnitPrice\":12,"
            + "\"expectedBuyerTotal\":12,\"idempotencyKey\":\"market-sell-to-buy-1\"}";
    JsonObject soldToBuy =
        JsonParser.parseString(
                post("/api/market/sell-to-buy", sellToBuyRequest, token, null).body())
            .getAsJsonObject();
    JsonObject soldToBuyReplay =
        JsonParser.parseString(
                post("/api/market/sell-to-buy", sellToBuyRequest, token, null).body())
            .getAsJsonObject();
    assertEquals(soldToBuy.get("tradeId").getAsLong(), soldToBuyReplay.get("tradeId").getAsLong());
    assertEquals(
        41,
        JsonParser.parseString(get("/api/wallet", token).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    assertEquals(
        1,
        inventories.snapshot(player, false).toCompletableFuture().join()
                instanceof com.webshopx.platform.PlatformResult.Success<?> success
            ? ((com.webshopx.platform.InventoryTypes.InventorySnapshot) success.value())
                .items()
                .get(0)
                .count()
            : -1);
    assertEquals(
        59,
        JsonParser.parseString(get("/api/wallet", buyerToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
  }

  @Test
  void officialRecycleWithdrawsNativeItemsCreditsWalletAndReplays() throws Exception {
    var recycle = commerce.createProduct(new SharedCommerceService.ProductInput(
        "API_DIAMOND_RECYCLE", "Diamond recycle", null, CurrencyType.SHOP_COIN, 7,
        SharedCommerceService.ProductKind.RECYCLE_ITEM, "", "minecraft:diamond", null, true));
    String token = JsonParser.parseString(
            post("/api/auth/login", "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
                null, null).body())
        .getAsJsonObject().get("token").getAsString();
    JsonObject inventory = JsonParser.parseString(
        get("/api/inventory/snapshot?inventory=PLAYER", token).body()).getAsJsonObject();
    String fingerprint = inventory.getAsJsonArray("slots").get(0).getAsJsonObject()
        .getAsJsonObject("item").get("fingerprint").getAsString();
    String matchBody = "{\"inventory\":\"PLAYER\",\"quantity\":2,\"fingerprint\":\""
        + fingerprint + "\"}";
    JsonObject matches = JsonParser.parseString(
        post("/api/inventory/matches", matchBody, token, null).body()).getAsJsonObject();
    JsonObject official = matches.getAsJsonArray("matches").asList().stream()
        .map(element -> element.getAsJsonObject())
        .filter(row -> row.get("id").getAsString().equals("official:" + recycle.id()))
        .findFirst().orElseThrow();
    assertEquals(14, official.get("sellerReceive").getAsLong());
    String fulfill = "{\"inventory\":\"PLAYER\",\"listingId\":\"official:"
        + recycle.id() + "\",\"quantity\":2,\"fingerprint\":\"" + fingerprint
        + "\",\"expectedUnitPrice\":7,\"expectedBuyerTotal\":14,"
        + "\"idempotencyKey\":\"official-recycle-1\"}";
    HttpResponse<String> first = post("/api/inventory/fulfill", fulfill, token, null);
    HttpResponse<String> replay = post("/api/inventory/fulfill", fulfill, token, null);
    assertEquals(200, first.statusCode(), first.body());
    assertEquals(JsonParser.parseString(first.body()), JsonParser.parseString(replay.body()));
    assertEquals(514, JsonParser.parseString(get("/api/wallet", token).body())
        .getAsJsonObject().get("shopCoin").getAsLong());
    var nativeSnapshot = (com.webshopx.platform.PlatformResult.Success<
        com.webshopx.platform.InventoryTypes.InventorySnapshot>)
        inventories.snapshot(player, false).toCompletableFuture().join();
    assertEquals(3, nativeSnapshot.value().items().get(0).count());
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
  void englishAuctionFreezesBidsSettlesAndDeliversThroughMailbox() throws Exception {
    String sellerToken =
        JsonParser.parseString(
                post(
                        "/api/auth/login",
                        "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}",
                        null,
                        null)
                    .body())
            .getAsJsonObject()
            .get("token")
            .getAsString();
    String bidderToken =
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
    UUID alternateBidderUuid = UUID.randomUUID();
    long alternateBidder =
        auth.setPasswordFromGame(alternateBidderUuid, "AlternateBidder", "alternate-secret")
            .userId();
    wallets.adjustBalance(
        alternateBidder, CurrencyType.GAME_COIN, 100, "TEST", "alternate-auction-seed");
    String alternateToken =
        JsonParser.parseString(
                post(
                        "/api/auth/login",
                        "{\"identifier\":\"AlternateBidder\",\"password\":\"alternate-secret\"}",
                        null,
                        null)
                    .body())
            .getAsJsonObject()
            .get("token")
            .getAsString();
    String fingerprint =
        JsonParser.parseString(get("/api/inventory/snapshot", sellerToken).body())
            .getAsJsonObject()
            .getAsJsonArray("slots")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("item")
            .get("fingerprint")
            .getAsString();
    String create =
        "{\"inventory\":\"PLAYER\",\"action\":\"AUCTION\","
            + "\"currency\":\"GAME_COIN\",\"price\":50,\"quantity\":1,"
            + "\"fingerprint\":\""
            + fingerprint
            + "\",\"auctionAlgorithm\":\"ENGLISH_AUCTION_V1\","
            + "\"auctionStartPrice\":50,\"auctionMinIncrement\":5,\"auctionEndAt\":\""
            + Instant.now().plusSeconds(90)
            + "\",\"idempotencyKey\":\"auction-create-1\"}";
    JsonObject created =
        JsonParser.parseString(post("/api/inventory/list", create, sellerToken, null).body())
            .getAsJsonObject();
    long listingId = created.get("listingId").getAsLong();
    JsonObject auctionListing =
        JsonParser.parseString(get("/api/market/listings?mine=true", sellerToken).body())
            .getAsJsonObject()
            .getAsJsonArray("listings")
            .get(0)
            .getAsJsonObject();
    assertEquals("AUCTION", auctionListing.get("tradeMode").getAsString());
    assertEquals("ENGLISH_AUCTION_V1", auctionListing.get("auctionAlgorithm").getAsString());

    String firstBid =
        "{\"listingId\":"
            + listingId
            + ",\"bidAmount\":60,\"idempotencyKey\":\"auction-bid-1\"}";
    JsonObject bid =
        JsonParser.parseString(post("/api/market/bid", firstBid, bidderToken, null).body())
            .getAsJsonObject();
    JsonObject replay =
        JsonParser.parseString(post("/api/market/bid", firstBid, bidderToken, null).body())
            .getAsJsonObject();
    assertEquals(bid.get("bidId").getAsLong(), replay.get("bidId").getAsLong());
    assertEquals(
        40,
        JsonParser.parseString(get("/api/wallet", bidderToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    assertEquals(
        409,
        post(
                "/api/market/buy",
                "{\"listingId\":"
                    + listingId
                    + ",\"quantity\":1,\"idempotencyKey\":\"auction-direct-buy\"}",
                bidderToken,
                null)
            .statusCode());
    assertEquals(
        409,
        post(
                "/api/market/unlist",
                "{\"listingId\":" + listingId + "}",
                sellerToken,
                null)
            .statusCode());
    assertEquals(
        200,
        post(
                "/api/market/bid",
                "{\"listingId\":"
                    + listingId
                    + ",\"bidAmount\":70,\"idempotencyKey\":\"auction-bid-2\"}",
                alternateToken,
                null)
            .statusCode());
    assertEquals(
        100,
        JsonParser.parseString(get("/api/wallet", bidderToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    assertEquals(
        30,
        JsonParser.parseString(get("/api/wallet", alternateToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    assertEquals(
        200,
        post(
                "/api/market/bid",
                "{\"listingId\":"
                    + listingId
                    + ",\"bidAmount\":80,\"idempotencyKey\":\"auction-bid-3\"}",
                bidderToken,
                null)
            .statusCode());
    assertEquals(
        20,
        JsonParser.parseString(get("/api/wallet", bidderToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    assertEquals(
        100,
        JsonParser.parseString(get("/api/wallet", alternateToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    JsonObject insights =
        JsonParser.parseString(
                get("/api/market/auction-insights?listingId=" + listingId, bidderToken).body())
            .getAsJsonObject();
    assertEquals(3, insights.get("bidCount").getAsInt());
    assertEquals(2, insights.get("participantCount").getAsInt());
    assertEquals(80, insights.get("myBid").getAsLong());

    database.withConnection(
        connection -> {
          try (var statement =
              connection.prepareStatement(
                  "UPDATE market_listings SET auction_end_at=CURRENT_TIMESTAMP"
                      + " WHERE id=?")) {
            statement.setLong(1, listingId);
            statement.executeUpdate();
          }
          return null;
        });
    assertEquals(1, commerce.settleExpiredAuctions(20));
    assertEquals(0, commerce.settleExpiredAuctions(20));
    assertEquals(
        80,
        JsonParser.parseString(get("/api/wallet", sellerToken).body())
            .getAsJsonObject()
            .get("gameCoin")
            .getAsInt());
    JsonObject mailbox =
        JsonParser.parseString(get("/api/mailbox/list", bidderToken).body()).getAsJsonObject();
    assertEquals(1, mailbox.get("count").getAsInt());
    String entryId =
        mailbox.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString();
    assertEquals(
        200,
        post("/api/mailbox/" + entryId + "/claim", "{}", bidderToken, null).statusCode());
    assertEquals(
        1,
        ((com.webshopx.platform.PlatformResult.Success<
                        com.webshopx.platform.InventoryTypes.InventorySnapshot>)
                    inventories.snapshot(supportTargetUuid, false).toCompletableFuture().join())
                .value()
                .items()
                .stream()
                .mapToInt(com.webshopx.platform.ItemEnvelope::count)
                .sum());
  }

  @Test
  void vickreyDutchAndCandleAuctionsPreserveTheirAlgorithmContracts() throws Exception {
    String sellerToken = login("ApiPlayer", "api-secret");
    String bidderToken = login("SupportTarget", "target-secret");
    UUID winnerUuid = UUID.randomUUID();
    long winnerUser = auth.setPasswordFromGame(winnerUuid, "SealedWinner", "winner-secret").userId();
    wallets.adjustBalance(winnerUser, CurrencyType.GAME_COIN, 200, "TEST", "sealed-seed");
    String winnerToken = login("SealedWinner", "winner-secret");
    String fingerprint = inventoryFingerprint(sellerToken);

    long vickreyId = createAuction(
        sellerToken, fingerprint, "VICKREY_AUCTION_V1", 50, 5,
        "{\"reservePrice\":60}", "vickrey-create");
    assertEquals(200, post(
        "/api/market/bid",
        "{\"listingId\":" + vickreyId
            + ",\"bidAmount\":70,\"idempotencyKey\":\"sealed-loser\"}",
        bidderToken, null).statusCode());
    JsonObject winningBid = JsonParser.parseString(post(
        "/api/market/bid",
        "{\"listingId\":" + vickreyId
            + ",\"bidAmount\":90,\"idempotencyKey\":\"sealed-winner\"}",
        winnerToken, null).body()).getAsJsonObject();
    assertEquals("SEALED", winningBid.get("status").getAsString());
    JsonObject sealedInsights = JsonParser.parseString(get(
        "/api/market/auction-insights?listingId=" + vickreyId, bidderToken).body())
        .getAsJsonObject();
    assertTrue(sealedInsights.get("sealed").getAsBoolean());
    assertEquals(0, sealedInsights.getAsJsonArray("pricePoints").size());
    JsonObject publicListing = JsonParser.parseString(get(
        "/api/market/listings", bidderToken).body()).getAsJsonObject()
        .getAsJsonArray("listings").get(0).getAsJsonObject();
    assertTrue(!publicListing.has("auctionHighestBid")
        || publicListing.get("auctionHighestBid").isJsonNull());
    assertEquals(409, post(
        "/api/market/unlist", "{\"listingId\":" + vickreyId + "}", sellerToken, null)
        .statusCode());

    expireAuction(vickreyId);
    assertEquals(1, commerce.settleExpiredAuctions(20));
    assertEquals(70, wallet(sellerToken));
    assertEquals(100, wallet(bidderToken));
    assertEquals(130, wallet(winnerToken));
    assertEquals(1, JsonParser.parseString(get("/api/mailbox/list", winnerToken).body())
        .getAsJsonObject().get("count").getAsInt());

    fingerprint = inventoryFingerprint(sellerToken);
    long dutchId = createAuction(
        sellerToken, fingerprint, "DUTCH_AUCTION_V1", 25, 1,
        "{\"floorPrice\":10}", "dutch-create");
    assertEquals(409, post(
        "/api/market/bid",
        "{\"listingId\":" + dutchId
            + ",\"bidAmount\":25,\"idempotencyKey\":\"dutch-bid\"}",
        bidderToken, null).statusCode());
    JsonObject dutchQuote = JsonParser.parseString(post(
        "/api/market/quote", "{\"listingId\":" + dutchId + ",\"quantity\":1}",
        bidderToken, null).body()).getAsJsonObject();
    long dutchPrice = dutchQuote.get("unitPrice").getAsLong();
    assertTrue(dutchPrice >= 10 && dutchPrice <= 25);
    assertEquals(200, post(
        "/api/market/buy",
        "{\"listingId\":" + dutchId
            + ",\"quantity\":1,\"expectedUnitPrice\":" + dutchPrice
            + ",\"idempotencyKey\":\"dutch-buy\"}", bidderToken, null).statusCode());
    assertEquals(100 - dutchPrice, wallet(bidderToken));
    assertEquals(70 + dutchPrice, wallet(sellerToken));

    fingerprint = inventoryFingerprint(sellerToken);
    long candleId = createAuction(
        sellerToken, fingerprint, "CANDLE_AUCTION_V1", 20, 2,
        "{\"maxExtensionSeconds\":120}", "candle-create");
    SharedCommerceService.AuctionDetails candle = commerce.auctionDetails(candleId);
    assertTrue(!candle.endAt().isBefore(candle.publicEndAt()));
    assertTrue(!candle.endAt().isAfter(candle.publicEndAt().plusSeconds(120)));

    fingerprint = inventoryFingerprint(sellerToken);
    long antiSnipingId = createAuction(
        sellerToken, fingerprint, "ENGLISH_AUCTION_V1", 20, 2,
        "{\"antiSnipingWindowSeconds\":120,\"antiSnipingExtendSeconds\":60}",
        "anti-sniping-create");
    Instant beforeExtension = commerce.auctionDetails(antiSnipingId).endAt();
    assertEquals(200, post(
        "/api/market/bid",
        "{\"listingId\":" + antiSnipingId
            + ",\"bidAmount\":20,\"idempotencyKey\":\"anti-sniping-bid\"}",
        bidderToken, null).statusCode());
    assertEquals(beforeExtension.plusSeconds(60), commerce.auctionDetails(antiSnipingId).endAt());

    fingerprint = inventoryFingerprint(sellerToken);
    long reserveId = createAuction(
        sellerToken, fingerprint, "ENGLISH_AUCTION_V1", 20, 2,
        "{\"reservePrice\":40}", "reserve-create");
    long balanceBeforeReserveBid = wallet(bidderToken);
    assertEquals(200, post(
        "/api/market/bid",
        "{\"listingId\":" + reserveId
            + ",\"bidAmount\":25,\"idempotencyKey\":\"reserve-bid\"}",
        bidderToken, null).statusCode());
    expireAuction(reserveId);
    assertEquals(1, commerce.settleExpiredAuctions(20));
    assertEquals(balanceBeforeReserveBid, wallet(bidderToken));
    assertEquals("UNLISTED", commerce.listing(reserveId).status());
  }

  private String login(String username, String password) throws Exception {
    return JsonParser.parseString(post(
        "/api/auth/login",
        "{\"identifier\":\"" + username + "\",\"password\":\"" + password + "\"}",
        null, null).body()).getAsJsonObject().get("token").getAsString();
  }

  private String inventoryFingerprint(String token) throws Exception {
    return JsonParser.parseString(get("/api/inventory/snapshot", token).body()).getAsJsonObject()
        .getAsJsonArray("slots").get(0).getAsJsonObject().getAsJsonObject("item")
        .get("fingerprint").getAsString();
  }

  private long createAuction(
      String token, String fingerprint, String algorithm, long start, long increment,
      String params, String key) throws Exception {
    String request = "{\"inventory\":\"PLAYER\",\"action\":\"AUCTION\","
        + "\"currency\":\"GAME_COIN\",\"price\":" + start + ",\"quantity\":1,"
        + "\"fingerprint\":\"" + fingerprint + "\",\"auctionAlgorithm\":\""
        + algorithm + "\",\"auctionStartPrice\":" + start + ",\"auctionMinIncrement\":"
        + increment + ",\"auctionEndAt\":\"" + Instant.now().plusSeconds(90)
        + "\",\"auctionParams\":" + params + ",\"idempotencyKey\":\"" + key + "\"}";
    HttpResponse<String> response = post("/api/inventory/list", request, token, null);
    assertEquals(200, response.statusCode(), response.body());
    return JsonParser.parseString(response.body()).getAsJsonObject().get("listingId").getAsLong();
  }

  private void expireAuction(long listingId) {
    database.withConnection(connection -> {
      try (var statement = connection.prepareStatement(
          "UPDATE market_listings SET auction_end_at=CURRENT_TIMESTAMP WHERE id=?")) {
        statement.setLong(1, listingId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private long wallet(String token) throws Exception {
    return JsonParser.parseString(get("/api/wallet", token).body()).getAsJsonObject()
        .get("gameCoin").getAsLong();
  }

  @Test
  void visualPacksAreValidatedStoredAndResolvedFromSharedAssets() throws Exception {
    String token = login("ApiPlayer", "api-secret");
    byte[] png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    String hash = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(png));
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("manifest.json", ("{\"schemaVersion\":2,\"pack\":{\"id\":\"fixture-pack\","
        + "\"name\":\"Fixture Pack\"},\"content\":{\"icons\":true,"
        + "\"translations\":false},\"locales\":[],\"entries\":[{"
        + "\"itemId\":\"minecraft:stone\",\"icon\":\"icons/stone.png\","
        + "\"sha256\":\"" + hash + "\",\"translationKey\":\"block.minecraft.stone\"}]}"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    files.put("catalog/items.json",
        "{\"minecraft:stone\":{\"translationKey\":\"block.minecraft.stone\"}}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    files.put("icons/stone.png", png);
    byte[] archive = binaryZip(files);

    HttpResponse<String> uploaded = postBinary(
        "/api/admin/visual-packs/upload", archive, token, "application/zip");
    assertEquals(200, uploaded.statusCode(), uploaded.body());
    JsonObject pack = JsonParser.parseString(uploaded.body()).getAsJsonObject();
    assertEquals("fixture-pack", pack.get("packId").getAsString());
    assertEquals(1, pack.get("entryCount").getAsInt());
    assertEquals(200, post(
        "/api/admin/visual-packs/state",
        "{\"packId\":\"fixture-pack\",\"enabled\":true}", token, null).statusCode());

    String version = pack.get("versionId").getAsString();
    HttpResponse<byte[]> direct = getBytes(
        "/visual-packs/fixture-pack/" + version + "/icons/stone.png", null);
    assertEquals(200, direct.statusCode());
    assertTrue(java.util.Arrays.equals(png, direct.body()));
    assertEquals("nosniff", direct.headers().firstValue("X-Content-Type-Options").orElseThrow());
    HttpResponse<byte[]> resolved = getBytes("/textures/resolved/minecraft/stone.png", null);
    assertEquals(200, resolved.statusCode());
    assertTrue(java.util.Arrays.equals(png, resolved.body()));
    assertEquals("visual-pack",
        resolved.headers().firstValue("X-WebShopX-Texture-Source").orElseThrow());
    assertEquals(200, get("/api/admin/visual-packs?unused=true", token).statusCode());
    assertTrue(java.util.Arrays.equals(
        archive, getBytes("/api/admin/visual-packs/download?packId=fixture-pack", token).body()));

    byte[] unsafe = binaryZip(Map.of("../escape.json", "{}".getBytes()));
    assertEquals(400, postBinary(
        "/api/admin/visual-packs/upload", unsafe, token, "application/zip").statusCode());
    assertEquals(200, post(
        "/api/admin/visual-packs/delete", "{\"packId\":\"fixture-pack\"}", token, null)
        .statusCode());
    assertEquals(404, get(
        "/visual-packs/fixture-pack/" + version + "/icons/stone.png", null).statusCode());
  }

  private static byte[] localeZip(Map<String, String> entries) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] binaryZip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  @Test
  void adminRuntimeConfigurationIsAuthorizedDurableAndPaperCompatible() throws Exception {
    String adminToken = login("ApiPlayer", "api-secret");
    assertEquals(401, get("/api/admin/economy/settings", null).statusCode());

    HttpResponse<String> exchange = post(
        "/api/admin/economy/exchange",
        "{\"shopToGameEnabled\":true,\"shopToGameRatio\":2.5,"
            + "\"gameToShopEnabled\":false,\"gameToShopRatio\":0.4}",
        adminToken, null);
    assertEquals(200, exchange.statusCode(), exchange.body());
    JsonObject settings = JsonParser.parseString(
        get("/api/admin/economy/settings", adminToken).body()).getAsJsonObject();
    assertTrue(settings.getAsJsonObject("exchange").getAsJsonObject("shopToGame")
        .get("enabled").getAsBoolean());
    assertEquals(2.5, settings.getAsJsonObject("exchange").getAsJsonObject("shopToGame")
        .get("ratio").getAsDouble());

    HttpResponse<String> tags = post(
        "/api/admin/market/tags-config",
        "{\"config\":{\"tags\":[{\"id\":\"rare\",\"label\":\"Rare\"}]}}",
        adminToken, null);
    assertEquals(200, tags.statusCode(), tags.body());
    JsonObject storedTags = JsonParser.parseString(
        get("/api/admin/market/tags-config", adminToken).body()).getAsJsonObject();
    assertEquals(1, storedTags.getAsJsonObject("config").getAsJsonArray("tags").size());
    assertTrue(storedTags.get("version").getAsLong() >= 1);

    HttpResponse<String> notification = post(
        "/api/admin/system/notification",
        "{\"marketEventsEnabled\":true,\"deliveryMailboxEventsEnabled\":false,"
            + "\"templates\":{\"auction-win\":\"Won {listingId}\"}}",
        adminToken, null);
    assertEquals(200, notification.statusCode(), notification.body());
    JsonObject refreshed = JsonParser.parseString(
        get("/api/admin/economy/settings", adminToken).body()).getAsJsonObject();
    assertTrue(refreshed.getAsJsonObject("notification")
        .get("marketEventsEnabled").getAsBoolean());
  }

  @Test
  void paymentProviderConfigurationRequiresAdminAndNeverReturnsSecretValues() throws Exception {
    String adminToken = login("ApiPlayer", "api-secret");
    assertEquals(
        401,
        get("/api/admin/economy/payment-provider-config?providerId=fixture-pay", null)
            .statusCode());
    HttpResponse<String> read = get(
        "/api/admin/economy/payment-provider-config?providerId=fixture-pay&locale=zh-CN",
        adminToken);
    assertEquals(200, read.statusCode(), read.body());
    JsonObject provider = JsonParser.parseString(read.body()).getAsJsonObject()
        .getAsJsonObject("provider");
    assertEquals("Fixture Pay", provider.get("displayName").getAsString());
    assertTrue(provider.getAsJsonObject("snapshot").getAsJsonArray("configuredSecrets")
        .toString().contains("secret"));
    assertFalse(provider.getAsJsonObject("snapshot").getAsJsonObject("values").has("secret"));

    String submittedSecret = "must-never-be-returned-or-audited";
    HttpResponse<String> update = post(
        "/api/admin/economy/payment-provider-config",
        "{\"providerId\":\"fixture-pay\",\"locale\":\"zh-CN\","
            + "\"changes\":{\"secret\":\"" + submittedSecret + "\"},"
            + "\"clearedSecrets\":[]}",
        adminToken,
        null);
    assertEquals(200, update.statusCode(), update.body());
    assertFalse(update.body().contains(submittedSecret));
    HttpResponse<String> auditLog = get("/api/admin/audit/list?limit=20", adminToken);
    assertEquals(200, auditLog.statusCode(), auditLog.body());
    assertTrue(auditLog.body().contains("PAYMENT_PROVIDER_CONFIG_UPDATE"));
    assertFalse(auditLog.body().contains(submittedSecret));
  }

  @Test
  void groupBuyVoucherPurchaseConsumeAndRefundAreTransactional() throws Exception {
    var product = commerce.createProduct(
        new SharedCommerceService.ProductInput(
            "GROUP_FIXTURE",
            "Group Fixture",
            null,
            CurrencyType.SHOP_COIN,
            25,
            SharedCommerceService.ProductKind.GROUP_BUY_VOUCHER,
            "",
            null,
            3,
            true));
    String token = login("ApiPlayer", "api-secret");
    String purchaseBody =
        "{\"productId\":" + product.id()
            + ",\"quantity\":1,\"idempotencyKey\":\"group-buy-1\"}";
    HttpResponse<String> purchased = post("/api/orders", purchaseBody, token, null);
    assertEquals(200, purchased.statusCode(), purchased.body());
    JsonObject order = JsonParser.parseString(purchased.body()).getAsJsonObject();
    String code = order.get("groupBuyVoucherCode").getAsString();
    assertTrue(code.matches("GB-[A-F0-9]{12}"));
    assertEquals("ISSUED", order.get("groupBuyVoucherStatus").getAsString());
    assertTrue(commerce.pendingDeliveries(player, "node-a").isEmpty());
    JsonObject listed = JsonParser.parseString(get("/api/orders/list", token).body())
        .getAsJsonObject();
    assertEquals(
        code,
        listed.getAsJsonArray("orders").get(0).getAsJsonObject()
            .get("groupBuyVoucherCode").getAsString());

    HttpResponse<String> duplicate = post("/api/orders", purchaseBody, token, null);
    assertEquals(200, duplicate.statusCode(), duplicate.body());
    assertEquals(
        code,
        JsonParser.parseString(duplicate.body()).getAsJsonObject()
            .get("groupBuyVoucherCode").getAsString());

    HttpResponse<String> consumed = post(
        "/api/admin/group-buy/consume",
        "{\"code\":\"" + code.toLowerCase() + "\"}",
        token,
        null);
    assertEquals(200, consumed.statusCode(), consumed.body());
    assertEquals(
        "CONSUMED",
        JsonParser.parseString(consumed.body()).getAsJsonObject().get("status").getAsString());
    assertEquals(
        409,
        post(
                "/api/admin/group-buy/consume",
                "{\"code\":\"" + code + "\"}",
                token,
                null)
            .statusCode());
    assertEquals(
        409,
        post(
                "/api/orders/refund",
                "{\"orderNo\":\"" + order.get("orderNo").getAsString() + "\"}",
                token,
                null)
            .statusCode());

    HttpResponse<String> refundable = post(
        "/api/orders",
        "{\"productId\":" + product.id()
            + ",\"quantity\":1,\"idempotencyKey\":\"group-buy-2\"}",
        token,
        null);
    JsonObject refundableOrder = JsonParser.parseString(refundable.body()).getAsJsonObject();
    String refundableCode = refundableOrder.get("groupBuyVoucherCode").getAsString();
    HttpResponse<String> refund = post(
        "/api/orders/refund",
        "{\"orderNo\":\"" + refundableOrder.get("orderNo").getAsString() + "\"}",
        token,
        null);
    assertEquals(200, refund.statusCode(), refund.body());
    assertEquals(
        "REFUNDED",
        commerce.groupBuyVoucher(playerUserId, refundableOrder.get("id").getAsLong()).status());
    assertEquals(
        400,
        post(
                "/api/admin/group-buy/consume",
                "{\"code\":\"" + refundableCode + "\"}",
                token,
                null)
            .statusCode());
  }

  @Test
  void localePackagesAreValidatedPersistedPublishedAndNeverExposeStoredPayloads() throws Exception {
    String token = login("ApiPlayer", "api-secret");
    LinkedHashMap<String, String> entries = new LinkedHashMap<>();
    entries.put("web/i18n/app/fr-FR.json", "{\"welcome\":\"Bonjour\"}");
    entries.put("web/i18n/admin/fr-FR.json", "{\"title\":\"Administration\"}");
    entries.put("messages/messages.fr-FR.yml", "welcome: Bonjour\n");
    String encoded = Base64.getEncoder().encodeToString(localeZip(entries));
    HttpResponse<String> upload = post(
        "/api/admin/locales/upload",
        "{\"fileName\":\"fr.zip\",\"contentBase64\":\"" + encoded + "\","
            + "\"name\":\"French\",\"nativeName\":\"Français\",\"version\":\"1.0.0\"}",
        token,
        null);
    assertEquals(200, upload.statusCode(), upload.body());
    assertEquals(3, JsonParser.parseString(upload.body()).getAsJsonObject()
        .get("fileCount").getAsInt());
    assertFalse(upload.body().contains("gameYamlBase64"));
    assertFalse(upload.body().contains("Bonjour"));

    HttpResponse<String> state = get("/api/admin/locales", token);
    assertEquals(200, state.statusCode(), state.body());
    assertFalse(state.body().contains("bundles"));
    assertEquals(3, JsonParser.parseString(state.body()).getAsJsonObject()
        .getAsJsonArray("locales").size());
    assertEquals(
        404,
        get("/api/locales/fr-FR/messages", null).statusCode());
    assertEquals(
        200,
        post(
                "/api/admin/locales/action",
                "{\"locale\":\"fr-FR\",\"action\":\"toggleWeb\"}",
                token,
                null)
            .statusCode());
    HttpResponse<String> messages = get("/api/locales/fr-FR/messages", null);
    assertEquals(200, messages.statusCode(), messages.body());
    assertEquals(
        "Bonjour",
        JsonParser.parseString(messages.body()).getAsJsonObject()
            .getAsJsonObject("app").get("welcome").getAsString());
    assertEquals(
        200,
        post(
                "/api/admin/locales/default",
                "{\"defaultLocale\":\"fr_FR\"}",
                token,
                null)
            .statusCode());
    JsonObject meta = JsonParser.parseString(get("/api/meta/locales", null).body()).getAsJsonObject();
    assertEquals("fr-FR", meta.get("defaultLocale").getAsString());

    String unsafe = Base64.getEncoder().encodeToString(
        localeZip(Map.of("../web/i18n/app/evil.json", "{}")));
    assertEquals(
        400,
        post(
                "/api/admin/locales/upload",
                "{\"fileName\":\"unsafe.zip\",\"contentBase64\":\"" + unsafe + "\"}",
                token,
                null)
            .statusCode());
  }

  @Test
  void adminCanCaptureACompleteNativeInventorySnapshotProduct() throws Exception {
    String token = login("ApiPlayer", "api-secret");
    JsonObject inventory = JsonParser.parseString(
        get("/api/inventory/snapshot?inventory=PLAYER", token).body()).getAsJsonObject();
    String revision = inventory.get("revision").getAsString();
    JsonObject occupied = null;
    for (var element : inventory.getAsJsonArray("slots")) {
      JsonObject slot = element.getAsJsonObject();
      if (!slot.get("item").isJsonNull()) {
        occupied = slot;
        break;
      }
    }
    assertTrue(occupied != null);
    int slot = occupied.get("index").getAsInt();
    String fingerprint = occupied.getAsJsonObject("item").get("fingerprint").getAsString();
    HttpResponse<String> created = post(
        "/api/admin/products/from-inventory",
        "{\"revision\":\"" + revision + "\",\"slot\":" + slot
            + ",\"fingerprint\":\"" + fingerprint + "\","
            + "\"sku\":\"CAPTURED_NATIVE\",\"title\":\"Captured Native\","
            + "\"currency\":\"SHOP_COIN\",\"price\":30,"
            + "\"stockMode\":\"LIMITED\",\"stock\":5}",
        token,
        null);
    assertEquals(201, created.statusCode(), created.body());
    JsonObject product = JsonParser.parseString(created.body()).getAsJsonObject();
    assertEquals("SNAPSHOT_ITEM", product.get("productType").getAsString());
    assertEquals("minecraft:diamond", product.get("itemMaterial").getAsString());
    assertTrue(product.get("itemHash").getAsString().startsWith("sha256:"));
    long productId = product.get("id").getAsLong();

    HttpResponse<String> history = get(
        "/api/admin/products/snapshot-history?productId=" + productId, token);
    assertEquals(200, history.statusCode(), history.body());
    assertEquals(1, JsonParser.parseString(history.body()).getAsJsonObject()
        .getAsJsonArray("versions").size());
    HttpResponse<String> purchased = post(
        "/api/orders",
        "{\"productId\":" + productId
            + ",\"quantity\":2,\"idempotencyKey\":\"captured-native-buy\"}",
        token,
        null);
    assertEquals(200, purchased.statusCode(), purchased.body());
    SharedCommerceService.Delivery delivery = commerce.pendingDeliveries(player, "node-a").stream()
        .filter(value -> value.payloadJson() != null && value.payloadJson().contains("envelopeBase64"))
        .findFirst().orElseThrow();
    String encodedEnvelope = JsonParser.parseString(delivery.payloadJson()).getAsJsonObject()
        .get("envelopeBase64").getAsString();
    ItemEnvelope restored = new ItemEnvelopeBinaryCodec().decode(
        Base64.getDecoder().decode(encodedEnvelope));
    assertEquals(inventoryFixture.payloadHash(), restored.payloadHash());
    assertEquals(
        java.util.Arrays.toString(inventoryFixture.payload()),
        java.util.Arrays.toString(restored.payload()));
  }

  @Test
  void adminCanConfigureAndResetAnEnforcedPerUserProductLimit() throws Exception {
    String token = login("ApiPlayer", "api-secret");
    HttpResponse<String> created =
        post(
            "/api/admin/products/upsert",
            "{\"sku\":\"API_LIMITED\",\"title\":\"API Limited\","
                + "\"currency\":\"SHOP_COIN\",\"price\":10,"
                + "\"productType\":\"COMMAND\",\"commandTemplate\":\"say paid\","
                + "\"perUserLimit\":2,\"active\":true}",
            token,
            null);
    assertEquals(200, created.statusCode(), created.body());
    JsonObject product = JsonParser.parseString(created.body()).getAsJsonObject();
    assertEquals(2, product.get("perUserLimit").getAsInt());
    long productId = product.get("id").getAsLong();
    assertEquals(
        200,
        post(
                "/api/orders",
                "{\"productId\":" + productId
                    + ",\"quantity\":2,\"idempotencyKey\":\"api-limit-first\"}",
                token,
                null)
            .statusCode());
    HttpResponse<String> rejected =
        post(
            "/api/orders",
            "{\"productId\":" + productId
                + ",\"quantity\":1,\"idempotencyKey\":\"api-limit-rejected\"}",
            token,
            null);
    assertEquals(409, rejected.statusCode(), rejected.body());
    assertEquals(
        "product_limit_reached",
        JsonParser.parseString(rejected.body()).getAsJsonObject().get("error").getAsString());
    HttpResponse<String> reset =
        post(
            "/api/admin/products/reset-limit",
            "{\"productId\":" + productId + "}",
            token,
            null);
    assertEquals(200, reset.statusCode(), reset.body());
    assertEquals(
        1,
        JsonParser.parseString(reset.body()).getAsJsonObject().get("resetCount").getAsInt());
    assertEquals(
        200,
        post(
                "/api/orders",
                "{\"productId\":" + productId
                    + ",\"quantity\":1,\"idempotencyKey\":\"api-limit-after-reset\"}",
                token,
                null)
            .statusCode());
  }

  @Test
  void adminCanResolveAndUpdateLoaderUserVisualPermissions() throws Exception {
    String token = login("ApiPlayer", "api-secret");
    HttpResponse<String> updated =
        post(
            "/api/admin/users/visual-permission",
            "{\"userId\":" + playerUserId
                + ",\"iconPermission\":\"DENY\",\"namePermission\":\"ALLOW\","
                + "\"uploadPermission\":\"DENY\",\"listingLimitOverride\":47}",
            token,
            null);
    assertEquals(200, updated.statusCode(), updated.body());
    JsonObject permission = JsonParser.parseString(updated.body()).getAsJsonObject();
    assertEquals("DENY", permission.get("iconPermission").getAsString());
    assertEquals("ALLOW", permission.get("namePermission").getAsString());
    assertFalse(permission.get("customIconAllowed").getAsBoolean());
    assertTrue(permission.get("customNameAllowed").getAsBoolean());
    assertEquals(47, permission.get("listingLimitEffective").getAsInt());
    assertEquals("USER_OVERRIDE", permission.get("listingLimitSource").getAsString());
    HttpResponse<String> loaded =
        get(
            "/api/admin/users/visual-permission?identifier=ApiPlayer",
            token);
    assertEquals(200, loaded.statusCode(), loaded.body());
    assertEquals(
        permission.get("listingLimitEffective").getAsInt(),
        JsonParser.parseString(loaded.body())
            .getAsJsonObject()
            .get("listingLimitEffective")
            .getAsInt());
  }

  @Test
  void binaryImagesAreValidatedPersistedAndServedAcrossSharedRoutes() throws Exception {
    String token = login("ApiPlayer", "api-secret");
    byte[] png = new byte[] {
      (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0
    };
    long productId = commerce.products(false).stream()
        .filter(product -> product.sku().equals("API_STONE"))
        .findFirst()
        .orElseThrow()
        .id();
    HttpResponse<String> productUpload = postBinary(
        "/api/admin/products/icon?productId=" + productId + "&filename=stone.png",
        png,
        token,
        "image/png");
    assertEquals(200, productUpload.statusCode(), productUpload.body());
    String productPath = JsonParser.parseString(productUpload.body()).getAsJsonObject()
        .get("displayIconPath").getAsString();
    assertBinaryAsset(productPath, png);

    HttpResponse<String> materialUpload = postBinary(
        "/api/admin/material-overrides/icon?material=STONE&filename=stone.png",
        png,
        token,
        "image/png");
    assertEquals(200, materialUpload.statusCode(), materialUpload.body());
    String materialPath = JsonParser.parseString(materialUpload.body()).getAsJsonObject()
        .get("iconPath").getAsString();
    assertBinaryAsset(materialPath, png);

    HttpResponse<String> homepageUpload = postBinary(
        "/api/admin/homepage/assets?filename=hero.png", png, token, "image/png");
    assertEquals(200, homepageUpload.statusCode(), homepageUpload.body());
    String homepagePath = JsonParser.parseString(homepageUpload.body()).getAsJsonObject()
        .get("url").getAsString();
    assertBinaryAsset(homepagePath, png);

    var listing = commerce.createListing(
        new SharedCommerceService.ListingRequest(
            playerUserId,
            player,
            CurrencyType.SHOP_COIN,
            25,
            1,
            inventoryFixture,
            "icon fixture"));
    HttpResponse<String> listingUpload = postBinary(
        "/api/market/icon/upload?listingId=" + listing.id() + "&filename=listing.png",
        png,
        token,
        "image/png");
    assertEquals(200, listingUpload.statusCode(), listingUpload.body());
    String listingPath = JsonParser.parseString(listingUpload.body()).getAsJsonObject()
        .get("displayIconPath").getAsString();
    assertBinaryAsset(listingPath, png);

    HttpResponse<String> invalid = postBinary(
        "/api/admin/products/icon?productId=" + productId + "&filename=fake.png",
        new byte[12],
        token,
        "image/png");
    assertEquals(400, invalid.statusCode(), invalid.body());
  }

  private void assertBinaryAsset(String path, byte[] expected) throws Exception {
    HttpResponse<byte[]> loaded = client.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, loaded.statusCode());
    assertTrue(java.util.Arrays.equals(expected, loaded.body()));
    assertEquals("nosniff", loaded.headers().firstValue("X-Content-Type-Options").orElse(null));
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
    JsonObject overview =
        JsonParser.parseString(get("/api/admin/overview/stats", token).body()).getAsJsonObject();
    assertEquals(2, overview.get("totalUsers").getAsLong());
    assertEquals(1, overview.get("totalProducts").getAsLong());
    long adminProductId =
        JsonParser.parseString(get("/api/products", null).body())
            .getAsJsonObject()
            .getAsJsonArray("products")
            .get(0)
            .getAsJsonObject()
            .get("id")
            .getAsLong();
    assertEquals(
        200,
        post(
                "/api/orders",
                "{\"productId\":"
                    + adminProductId
                    + ",\"quantity\":1,\"idempotencyKey\":\"admin-order-view-1\"}",
                token,
                null)
            .statusCode());
    JsonObject adminOrders =
        JsonParser.parseString(
                get("/api/admin/orders/list?keyword=ApiPlayer&limit=20", token).body())
            .getAsJsonObject();
    assertEquals(1, adminOrders.getAsJsonArray("orders").size());
    assertEquals(
        "ApiPlayer",
        adminOrders
            .getAsJsonArray("orders")
            .get(0)
            .getAsJsonObject()
            .get("username")
            .getAsString());
    assertEquals(200, get("/api/admin/market/listings", token).statusCode());
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
    HttpResponse<String> productUpsert =
        post(
            "/api/admin/products/upsert",
            "{\"sku\":\"API_STONE\",\"title\":\"Managed Stone\","
                + "\"remark\":\"admin update\",\"currency\":\"SHOP_COIN\","
                + "\"price\":55,\"productType\":\"GIVE_ITEM\","
                + "\"itemMaterial\":\"STONE\",\"itemAmount\":8,\"active\":true}",
            token,
            null);
    assertEquals(200, productUpsert.statusCode(), productUpsert.body());
    JsonObject managedProduct = JsonParser.parseString(productUpsert.body()).getAsJsonObject();
    assertEquals(55, managedProduct.get("price").getAsLong());
    assertEquals(8, managedProduct.get("stock").getAsInt());
    JsonObject defaultRefundPolicy =
        JsonParser.parseString(get("/api/admin/refund-policy", token).body()).getAsJsonObject();
    assertTrue(defaultRefundPolicy.get("selfServiceEnabled").getAsBoolean());
    HttpResponse<String> refundPolicyUpdate =
        post(
            "/api/admin/refund-policy",
            "{\"selfServiceEnabled\":true,\"mailboxPendingRefundEnabled\":true,"
                + "\"fixedPriceWindowMinutes\":15,\"dynamicPriceWindowMinutes\":4,"
                + "\"partialRefundEnabled\":false,\"maxSelfServiceRefundsPerDay\":7,"
                + "\"orderLevelPolicyEnabled\":true}",
            token,
            null);
    assertEquals(200, refundPolicyUpdate.statusCode(), refundPolicyUpdate.body());
    assertEquals(
        15,
        JsonParser.parseString(get("/api/admin/refund-policy", token).body())
            .getAsJsonObject()
            .get("fixedPriceWindowMinutes")
            .getAsInt());
    HttpResponse<String> productRefundPolicy =
        post(
            "/api/admin/products/refund-policy",
            "{\"productId\":"
                + managedProduct.get("id").getAsLong()
                + ",\"refundPolicy\":\"CUSTOM\",\"refundWindowMinutes\":20,"
                + "\"partialRefundPolicy\":\"DENY\"}",
            token,
            null);
    assertEquals(200, productRefundPolicy.statusCode(), productRefundPolicy.body());
    assertEquals(
        "CUSTOM",
        JsonParser.parseString(productRefundPolicy.body())
            .getAsJsonObject()
            .get("refundPolicy")
            .getAsString());
    assertEquals(
        1,
        JsonParser.parseString(
                get("/api/admin/products/list?includeInactive=true", token).body())
            .getAsJsonObject()
            .getAsJsonArray("products")
            .size());
    assertEquals(
        200,
        post(
                "/api/admin/products/active",
                "{\"productId\":" + managedProduct.get("id").getAsLong() + ",\"active\":false}",
                token,
                null)
            .statusCode());
    assertEquals(
        0,
        JsonParser.parseString(get("/api/products", null).body())
            .getAsJsonObject()
            .getAsJsonArray("products")
            .size());
    assertEquals(200, get("/api/admin/audit/list", token).statusCode());
    assertEquals(200, post("/api/admin/auth/logout", "{}", token, null).statusCode());
    assertEquals(401, get("/api/admin/auth/me", token).statusCode());
  }

  @Test
  void servesLandingAndDistinguishesContractCapabilities() throws Exception {
    HttpResponse<String> landing = get("/", null);
    assertEquals(200, landing.statusCode());
    assertTrue(landing.body().contains("WebShopX"));
    HttpResponse<String> spaRoute = get("/account", null);
    assertEquals(200, spaRoute.statusCode());
    assertEquals(
        "text/html; charset=utf-8",
        spaRoute.headers().firstValue("Content-Type").orElseThrow());
    assertTrue(spaRoute.body().contains("WebShopX"));
    HttpResponse<String> wrongMethod = get("/api/orders/refund", null);
    assertEquals(405, wrongMethod.statusCode());
    assertTrue(wrongMethod.body().contains("method_not_allowed"));
    assertEquals(404, get("/not-a-webshopx-route.json", null).statusCode());
  }

  @Test
  void acceptsTheExistingFrontendUsernameLoginContract() throws Exception {
    HttpResponse<String> response = post(
        "/api/auth/login",
        "{\"username\":\"ApiPlayer\",\"password\":\"api-secret\"}",
        null,
        null);
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().contains("ApiPlayer"));
  }

  private static final class FixtureSupplyGateway implements SupplyInventoryGateway {
    private final ItemEnvelope template;
    private int quantity;
    private long version = 1;
    private boolean returnUnknown;
    private boolean retainReconciliation;
    private SupplyWithdrawal reconciliation;

    private FixtureSupplyGateway(ItemEnvelope template, int quantity) {
      this.template = template;
      this.quantity = quantity;
    }

    @Override
    public synchronized java.util.concurrent.CompletionStage<PlatformResult<SupplySnapshot>> snapshot(
        SupplyLocation location) {
      return CompletableFuture.completedFuture(PlatformResult.success(
          new SupplySnapshot(version, quantity == 0 ? List.of() : List.of(withCount(quantity)))));
    }

    @Override
    public synchronized java.util.concurrent.CompletionStage<PlatformResult<SupplySnapshot>> inspect(
        UUID playerId, SupplyLocation location) {
      return snapshot(location);
    }

    @Override
    public synchronized java.util.concurrent.CompletionStage<PlatformResult<SupplyWithdrawal>>
        compareAndWithdraw(SupplyWithdrawalRequest request) {
      if (request.expectedVersion() != version) {
        return CompletableFuture.completedFuture(
            new PlatformResult.Conflict<>(request.operationId(), Long.toString(version)));
      }
      int removed = Math.min(quantity, request.maximumQuantity());
      quantity -= removed;
      version++;
      SupplyWithdrawal withdrawal =
          new SupplyWithdrawal(version, removed == 0 ? null : withCount(removed), removed);
      if (returnUnknown) {
        if (retainReconciliation) reconciliation = withdrawal;
        return CompletableFuture.completedFuture(
            new PlatformResult.UnknownOutcome<>(request.operationId(), removed > 0));
      }
      return CompletableFuture.completedFuture(PlatformResult.success(withdrawal));
    }

    @Override
    public synchronized java.util.concurrent.CompletionStage<PlatformResult<SupplyWithdrawal>>
        reconcile(SupplyWithdrawalRequest request) {
      return CompletableFuture.completedFuture(reconciliation == null
          ? new PlatformResult.UnknownOutcome<>(request.operationId(), false)
          : PlatformResult.success(reconciliation));
    }

    private ItemEnvelope withCount(int count) {
      return new ItemEnvelope(
          template.schemaVersion(), template.codec(), template.codecVersion(),
          template.compatibilityDomain(), template.registryId(), count,
          template.payloadEncoding(), template.payload(), template.payloadHash(),
          template.summary(), template.createdAt());
    }
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> getBytes(String path, String token) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
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

  private HttpResponse<String> postBinary(
      String path, byte[] body, String token, String contentType) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
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
