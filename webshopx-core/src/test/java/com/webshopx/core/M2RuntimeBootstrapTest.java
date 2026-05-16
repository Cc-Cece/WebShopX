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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2RuntimeBootstrapTest {

  private static final Gson GSON = new Gson();

  @Test
  void runtimeSupportsUserWalletProductOrderLoop() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-m2-test-");
    Path runtimeRoot = tempRoot.resolve("fabric-1.21.x");
    Files.createDirectories(runtimeRoot);
    Path configPath = runtimeRoot.resolve("m2-runtime.properties");
    Files.writeString(configPath, String.join(System.lineSeparator(),
        "http.host=127.0.0.1",
        "http.port=0",
        "sqlite.path=data/test-m2.sqlite",
        "admin.token=test-admin-token"));

    HttpClient client = HttpClient.newHttpClient();
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        "test",
        tempRoot,
        ignored -> {
        })) {
      String endpoint = handle.endpoint();

      JsonObject registerResponse = postJson(client, endpoint + "/api/m2/users/register", """
          {"username":"user_%s"}
          """.formatted(UUID.randomUUID().toString().replace("-", "")), null);
      long userId = registerResponse.get("id").getAsLong();
      assertTrue(userId > 0);

      JsonObject walletBefore = getJson(client, endpoint + "/api/m2/wallet/" + userId, null);
      assertEquals(0L, walletBefore.get("shopCoin").getAsLong());

      JsonObject productPayload = new JsonObject();
      productPayload.addProperty("sku", "starter_pack");
      productPayload.addProperty("title", "Starter Pack");
      productPayload.addProperty("currency", "SHOP_COIN");
      productPayload.addProperty("price", 100);
      productPayload.addProperty("active", true);
      JsonObject product = postJson(
          client,
          endpoint + "/api/m2/products",
          GSON.toJson(productPayload),
          "test-admin-token");
      long productId = product.get("id").getAsLong();
      assertTrue(productId > 0);

      JsonObject adjustPayload = new JsonObject();
      adjustPayload.addProperty("userId", userId);
      adjustPayload.addProperty("currency", "SHOP_COIN");
      adjustPayload.addProperty("delta", 500);
      adjustPayload.addProperty("bizType", "TEST_CREDIT");
      adjustPayload.addProperty("bizId", "credit-1");
      postJson(client, endpoint + "/api/m2/wallet/adjust", GSON.toJson(adjustPayload), "test-admin-token");

      JsonObject orderPayload = new JsonObject();
      orderPayload.addProperty("userId", userId);
      orderPayload.addProperty("productId", productId);
      orderPayload.addProperty("quantity", 2);
      orderPayload.addProperty("idempotencyKey", "order-key-1");
      JsonObject order = postJson(client, endpoint + "/api/m2/orders", GSON.toJson(orderPayload), null);
      assertEquals("CREATED", order.get("state").getAsString());
      assertEquals(200L, order.get("totalAmount").getAsLong());

      JsonObject walletAfter = getJson(client, endpoint + "/api/m2/wallet/" + userId, null);
      assertEquals(300L, walletAfter.get("shopCoin").getAsLong());

      JsonObject orders = getJson(client, endpoint + "/api/m2/orders?userId=" + userId, null);
      JsonArray items = orders.getAsJsonArray("items");
      assertEquals(1, items.size());
    }
  }

  private JsonObject getJson(HttpClient client, String url, String adminToken) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .GET()
        .uri(URI.create(url));
    if (adminToken != null) {
      builder.header("X-WebShopX-Admin-Token", adminToken);
    }
    HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    return GSON.fromJson(response.body(), JsonObject.class);
  }

  private JsonObject postJson(HttpClient client, String url, String body, String adminToken) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (adminToken != null) {
      builder.header("X-WebShopX-Admin-Token", adminToken);
    }
    HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    return GSON.fromJson(response.body(), JsonObject.class);
  }
}
