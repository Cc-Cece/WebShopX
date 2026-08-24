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
import com.webshopx.SharedPromotionService;
import com.webshopx.WalletService;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.PlatformIdentity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
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
    UUID player = UUID.randomUUID();
    long user = auth.setPasswordFromGame(player, "ApiPlayer", "api-secret").userId();
    supportTargetUuid = UUID.randomUUID();
    auth.setPasswordFromGame(supportTargetUuid, "SupportTarget", "target-secret");
    wallets.adjustBalance(user, CurrencyType.SHOP_COIN, 500, "TEST", "api-seed");
    SharedCommerceService commerce = new SharedCommerceService(database, wallets);
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
    JsonObject product =
        JsonParser.parseString(get("/api/products", null).body())
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject();
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
    assertEquals(200, get("/api/admin/auth/me", token).statusCode());
    assertEquals(200, get("/api/admin/users/list", token).statusCode());
    HttpResponse<String> lookup = get("/api/admin/users/lookup?identifier=SupportTarget", token);
    assertEquals(200, lookup.statusCode(), lookup.body());
    long targetId =
        JsonParser.parseString(lookup.body()).getAsJsonObject().get("id").getAsLong();
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
}
