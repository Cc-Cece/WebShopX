package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M3RuntimeCompatibilityTest {

  private static final Gson GSON = new Gson();

  @Test
  void mainlineAndBackportHaveConsistentBehaviorOnSharedConfigAndDatabase() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-m3-shared-");
    Path sqlitePath = tempRoot.resolve("shared").resolve("m3-shared.sqlite").toAbsolutePath();
    String adminToken = "m3-admin-token";

    writeRuntimeConfig(tempRoot, "fabric-1.21.x", sqlitePath, adminToken);
    writeRuntimeConfig(tempRoot, "fabric-1.20.6", sqlitePath, adminToken);

    HttpClient client = HttpClient.newHttpClient();
    ScenarioResult mainline;
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        "test-mainline",
        tempRoot,
        ignored -> {
        })) {
      mainline = runScenario(client, handle.endpoint(), "m3_mainline", adminToken);
    }

    ScenarioResult backport;
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.20.6",
        "test-backport",
        tempRoot,
        ignored -> {
        })) {
      backport = runScenario(client, handle.endpoint(), "m3_backport", adminToken);
    }

    assertEquals(mainline.productId(), backport.productId());
    assertEquals(mainline.createdState(), backport.createdState());
    assertEquals(mainline.existingState(), backport.existingState());
    assertEquals(mainline.currency(), backport.currency());
    assertEquals(mainline.totalAmount(), backport.totalAmount());
    assertEquals(mainline.balanceAfter(), backport.balanceAfter());
    assertEquals(mainline.insufficientStatus(), backport.insufficientStatus());
    assertEquals(mainline.insufficientCode(), backport.insufficientCode());

    assertTrue(Files.exists(sqlitePath));
    Map<String, String> probes = readRuntimeProbes(sqlitePath);
    assertEquals("test-mainline", probes.get("fabric-1.21.x"));
    assertEquals("test-backport", probes.get("fabric-1.20.6"));
  }

  @Test
  void backportRuntimeMeetsMinimumPerformanceBudget() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-m3-perf-");
    Path sqlitePath = tempRoot.resolve("shared").resolve("m3-performance.sqlite").toAbsolutePath();
    String adminToken = "m3-perf-token";

    writeRuntimeConfig(tempRoot, "neoforge-1.20.6", sqlitePath, adminToken);

    HttpClient client = HttpClient.newHttpClient();
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "neoforge-1.20.6",
        "test-backport-perf",
        tempRoot,
        ignored -> {
        })) {
      String endpoint = handle.endpoint();
      JsonObject product = upsertProduct(client, endpoint, "m3_perf_pack", 100, adminToken);
      long productId = product.get("id").getAsLong();

      long userId = registerUser(client, endpoint, "perf_user_" + UUID.randomUUID().toString().replace("-", "")).get("id").getAsLong();
      adjustWallet(client, endpoint, userId, 200_000L, "PERF_TOPUP", "perf-topup", adminToken);

      int orderCount = 120;
      long startNanos = System.nanoTime();
      for (int i = 0; i < orderCount; i++) {
        JsonObject orderPayload = new JsonObject();
        orderPayload.addProperty("userId", userId);
        orderPayload.addProperty("productId", productId);
        orderPayload.addProperty("quantity", 1);
        orderPayload.addProperty("idempotencyKey", "perf-order-" + i);
        JsonObject created = postJsonExpect(
            client,
            endpoint + "/api/m2/orders",
            GSON.toJson(orderPayload),
            null,
            201);
        assertEquals("CREATED", created.get("state").getAsString());
      }
      long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

      JsonObject orders = getJsonExpect(client, endpoint + "/api/m2/orders?userId=" + userId, null, 200);
      JsonArray items = orders.getAsJsonArray("items");
      assertEquals(orderCount, items.size());
      assertTrue(elapsedMillis < 15_000L, "M3 minimum performance budget exceeded: " + elapsedMillis + "ms");
    }
  }

  private ScenarioResult runScenario(HttpClient client, String endpoint, String prefix, String adminToken) throws Exception {
    JsonObject product = upsertProduct(client, endpoint, "m3_shared_sku", 200, adminToken);
    long productId = product.get("id").getAsLong();

    long lowBalanceUser = registerUser(client, endpoint, prefix + "_low_" + randomSuffix()).get("id").getAsLong();
    JsonObject lowBalanceOrder = new JsonObject();
    lowBalanceOrder.addProperty("userId", lowBalanceUser);
    lowBalanceOrder.addProperty("productId", productId);
    lowBalanceOrder.addProperty("quantity", 1);
    lowBalanceOrder.addProperty("idempotencyKey", prefix + "_low_order");
    JsonObject insufficient = postJsonExpect(
        client,
        endpoint + "/api/m2/orders",
        GSON.toJson(lowBalanceOrder),
        null,
        409);

    long paidUser = registerUser(client, endpoint, prefix + "_paid_" + randomSuffix()).get("id").getAsLong();
    adjustWallet(client, endpoint, paidUser, 500L, "TEST_CREDIT", prefix + "_credit", adminToken);

    String idempotencyKey = prefix + "_order";
    JsonObject createPayload = new JsonObject();
    createPayload.addProperty("userId", paidUser);
    createPayload.addProperty("productId", productId);
    createPayload.addProperty("quantity", 1);
    createPayload.addProperty("idempotencyKey", idempotencyKey);

    JsonObject created = postJsonExpect(client, endpoint + "/api/m2/orders", GSON.toJson(createPayload), null, 201);
    JsonObject existing = postJsonExpect(client, endpoint + "/api/m2/orders", GSON.toJson(createPayload), null, 200);
    JsonObject walletAfter = getJsonExpect(client, endpoint + "/api/m2/wallet/" + paidUser, null, 200);

    return new ScenarioResult(
        productId,
        created.get("state").getAsString(),
        existing.get("state").getAsString(),
        created.get("currency").getAsString(),
        created.get("totalAmount").getAsLong(),
        walletAfter.get("shopCoin").getAsLong(),
        insufficient.get("error").getAsString(),
        409);
  }

  private JsonObject upsertProduct(HttpClient client, String endpoint, String sku, long price, String adminToken) throws Exception {
    JsonObject payload = new JsonObject();
    payload.addProperty("sku", sku);
    payload.addProperty("title", "M3 Shared Product");
    payload.addProperty("currency", "SHOP_COIN");
    payload.addProperty("price", price);
    payload.addProperty("active", true);
    return postJsonExpect(client, endpoint + "/api/m2/products", GSON.toJson(payload), adminToken, 200);
  }

  private JsonObject registerUser(HttpClient client, String endpoint, String username) throws Exception {
    return postJsonExpect(client, endpoint + "/api/m2/users/register", "{\"username\":\"" + username + "\"}", null, 201);
  }

  private void adjustWallet(
      HttpClient client,
      String endpoint,
      long userId,
      long delta,
      String bizType,
      String bizId,
      String adminToken) throws Exception {
    JsonObject payload = new JsonObject();
    payload.addProperty("userId", userId);
    payload.addProperty("currency", "SHOP_COIN");
    payload.addProperty("delta", delta);
    payload.addProperty("bizType", bizType);
    payload.addProperty("bizId", bizId);
    postJsonExpect(client, endpoint + "/api/m2/wallet/adjust", GSON.toJson(payload), adminToken, 200);
  }

  private void writeRuntimeConfig(Path tempRoot, String runtimeId, Path sqlitePath, String adminToken) throws Exception {
    Path runtimeRoot = tempRoot.resolve(runtimeId);
    Files.createDirectories(runtimeRoot);
    Path configPath = runtimeRoot.resolve("m2-runtime.properties");
    String sqlitePathText = sqlitePath.toString().replace("\\", "/");
    Files.writeString(configPath, String.join(System.lineSeparator(),
        "http.host=127.0.0.1",
        "http.port=0",
        "sqlite.path=" + sqlitePathText,
        "admin.token=" + adminToken));
  }

  private Map<String, String> readRuntimeProbes(Path sqlitePath) throws Exception {
    Map<String, String> probes = new HashMap<>();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath())) {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT runtime_id, version FROM m2_runtime_probe")) {
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            probes.put(resultSet.getString("runtime_id"), resultSet.getString("version"));
          }
        }
      }
    }
    return probes;
  }

  private JsonObject getJsonExpect(HttpClient client, String url, String adminToken, int expectedStatus) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .GET()
        .uri(URI.create(url));
    if (adminToken != null) {
      builder.header("X-WebShopX-Admin-Token", adminToken);
    }
    HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return GSON.fromJson(response.body(), JsonObject.class);
  }

  private JsonObject postJsonExpect(
      HttpClient client,
      String url,
      String body,
      String adminToken,
      int expectedStatus) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (adminToken != null) {
      builder.header("X-WebShopX-Admin-Token", adminToken);
    }
    HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return GSON.fromJson(response.body(), JsonObject.class);
  }

  private String randomSuffix() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private record ScenarioResult(
      long productId,
      String createdState,
      String existingState,
      String currency,
      long totalAmount,
      long balanceAfter,
      String insufficientCode,
      int insufficientStatus) {
  }
}
