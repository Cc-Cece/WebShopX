package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.CurrencyType;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceService;
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

  @BeforeEach void start() {
    database = new DatabaseManager(null, new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 2,
        temporaryDirectory.resolve("api.db").toString(), "WAL", "NORMAL",
        5_000, 3, List.of(10, 25, 50)));
    database.start();
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    AuthService auth = new AuthService(database, () -> new AuthService.SessionSettings(40, 2));
    WalletService wallets = new WalletService(
        database, WalletService.ExchangePolicy::disabled, null, null);
    UUID player = UUID.randomUUID();
    long user = auth.setPasswordFromGame(player, "ApiPlayer", "api-secret").userId();
    wallets.adjustBalance(user, CurrencyType.SHOP_COIN, 500, "TEST", "api-seed");
    SharedCommerceService commerce = new SharedCommerceService(database, wallets);
    commerce.createProduct(new SharedCommerceService.ProductInput(
        "API_STONE", "API Stone", null, CurrencyType.SHOP_COIN, 50,
        SharedCommerceService.ProductKind.GIVE_ITEM, "", "minecraft:stone", 5, true));
    AdminService admin = new AdminService(database, auth, wallets);
    EnumMap<CapabilitySnapshot.Capability, CapabilitySnapshot.CapabilityState> states =
        new EnumMap<>(CapabilitySnapshot.Capability.class);
    states.put(CapabilitySnapshot.Capability.HTTP_API,
        CapabilitySnapshot.CapabilityState.available("test"));
    api = new SharedHttpApi("127.0.0.1", 0, "https://shop.example", auth, wallets,
        commerce, admin, new PlatformIdentity("fabric", "fabric", "1.20.1", "test",
        "node-a", "sha256:test"), new CapabilitySnapshot(Instant.now(), states));
    api.start();
    base = "http://127.0.0.1:" + api.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach void stop() {
    if (api != null) api.close();
    if (database != null) database.close();
  }

  @Test void authenticatedPurchaseIsIdempotentAndCorsIsExact() throws Exception {
    assertEquals(200, get("/health", null).statusCode());
    assertEquals(401, get("/api/wallet", null).statusCode());
    HttpResponse<String> login = post("/api/auth/login",
        "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}", null, null);
    assertEquals(200, login.statusCode());
    String token = JsonParser.parseString(login.body()).getAsJsonObject().get("token").getAsString();
    assertEquals(500, JsonParser.parseString(get("/api/wallet", token).body())
        .getAsJsonObject().get("shopCoin").getAsInt());
    JsonObject product = JsonParser.parseString(get("/api/products", null).body())
        .getAsJsonArray().get(0).getAsJsonObject();
    String order = "{\"productId\":" + product.get("id").getAsLong()
        + ",\"quantity\":2,\"idempotencyKey\":\"api-order-1\"}";
    String first = post("/api/orders", order, token, null).body();
    String second = post("/api/orders", order, token, null).body();
    assertEquals(JsonParser.parseString(first), JsonParser.parseString(second));
    assertEquals(400, JsonParser.parseString(get("/api/wallet", token).body())
        .getAsJsonObject().get("shopCoin").getAsInt());
    assertEquals(1, JsonParser.parseString(get("/api/deliveries", token).body())
        .getAsJsonArray().size());

    HttpResponse<String> allowed = post("/api/auth/logout", "{}", token,
        "https://shop.example");
    assertEquals("https://shop.example",
        allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    HttpResponse<String> rejected = post("/api/auth/login",
        "{\"identifier\":\"ApiPlayer\",\"password\":\"api-secret\"}", null,
        "https://evil.example");
    assertFalse(rejected.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    assertTrue(rejected.headers().firstValue("Content-Security-Policy").isPresent());
  }

  @Test void oversizedAndMalformedRequestsFailClosed() throws Exception {
    assertEquals(413, post("/api/auth/login", "x".repeat(70_000), null, null).statusCode());
    assertEquals(400, post("/api/auth/login", "[]", null, null).statusCode());
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body, String token, String origin)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (origin != null) request.header("Origin", origin);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
