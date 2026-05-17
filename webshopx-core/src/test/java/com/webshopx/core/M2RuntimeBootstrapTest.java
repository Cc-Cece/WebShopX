package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
      JsonObject firstAdjust = postJson(client, endpoint + "/api/m2/wallet/adjust", GSON.toJson(adjustPayload), "test-admin-token");
      assertTrue(firstAdjust.get("applied").getAsBoolean());
      assertEquals(500L, firstAdjust.get("balance").getAsLong());
      assertEquals(500L, firstAdjust.get("delta").getAsLong());

      JsonObject duplicateAdjust = postJson(client, endpoint + "/api/m2/wallet/adjust", GSON.toJson(adjustPayload), "test-admin-token");
      assertFalse(duplicateAdjust.get("applied").getAsBoolean());
      assertEquals(500L, duplicateAdjust.get("balance").getAsLong());
      assertEquals(0L, duplicateAdjust.get("delta").getAsLong());

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

  @Test
  void runtimeServesWebResourcesAndReturnsApiCompatibilityErrors() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-m2-static-test-");
    Path runtimeRoot = tempRoot.resolve("fabric-1.21.x");
    Files.createDirectories(runtimeRoot);
    Files.writeString(runtimeRoot.resolve("m2-runtime.properties"), String.join(System.lineSeparator(),
        "http.host=127.0.0.1",
        "http.port=0",
        "sqlite.path=data/test-m2.sqlite",
        "admin.token="));

    HttpClient client = HttpClient.newHttpClient();
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        "test",
        tempRoot,
        ignored -> {
        })) {
      String endpoint = handle.endpoint();

      HttpResponse<String> home = getText(client, endpoint + "/", null);
      assertEquals(200, home.statusCode());
      assertTrue(home.body().contains("WebShopX"));

      HttpResponse<String> admin = getText(client, endpoint + "/admin", null);
      assertEquals(200, admin.statusCode());
      assertTrue(admin.body().contains("window.WEBSHOPX_VERSION"));

      HttpResponse<String> config = getText(client, endpoint + "/config.js", null);
      assertEquals(200, config.statusCode());
      assertTrue(config.body().contains("window.WEBSHOPX_CONFIG"));

      HttpResponse<String> stylesheet = getText(client, endpoint + "/css/styles.css", null);
      assertEquals(200, stylesheet.statusCode());
      assertNotNull(stylesheet.headers().firstValue("content-type").orElse(null));

      HttpResponse<String> legacyApi = getText(client, endpoint + "/api/auth/login", null);
      assertEquals(501, legacyApi.statusCode());
      JsonObject payload = GSON.fromJson(legacyApi.body(), JsonObject.class);
      assertEquals("mod_endpoint_not_implemented", payload.get("error").getAsString());
    }
  }

  @Test
  void compatibilityApiSupportsAuthWalletProductsAndOrdersLoop() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-compat-test-");
    Path runtimeRoot = tempRoot.resolve("fabric-1.21.x");
    Files.createDirectories(runtimeRoot);
    Files.writeString(runtimeRoot.resolve("m2-runtime.properties"), String.join(System.lineSeparator(),
        "http.host=127.0.0.1",
        "http.port=0",
        "sqlite.path=data/test-m2.sqlite",
        "admin.token="));

    HttpClient client = HttpClient.newHttpClient();
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        "test",
        tempRoot,
        ignored -> {
        })) {
      String endpoint = handle.endpoint();

      JsonObject productPayload = new JsonObject();
      productPayload.addProperty("sku", "compat_pack");
      productPayload.addProperty("title", "Compat Pack");
      productPayload.addProperty("currency", "SHOP_COIN");
      productPayload.addProperty("price", 100);
      productPayload.addProperty("active", true);
      JsonObject product = postJson(client, endpoint + "/api/m2/products", GSON.toJson(productPayload), "admin-secret");
      long productId = product.get("id").getAsLong();
      assertTrue(productId > 0);

      JsonObject login = postJson(client, endpoint + "/api/auth/login", """
          {"identifier":"compat_user","password":"password123"}
          """, null);
      String sessionToken = login.get("sessionToken").getAsString();
      long userId = login.getAsJsonObject("user").get("id").getAsLong();
      assertTrue(userId > 0);

      JsonObject adjustPayload = new JsonObject();
      adjustPayload.addProperty("userId", userId);
      adjustPayload.addProperty("currency", "SHOP_COIN");
      adjustPayload.addProperty("delta", 500);
      adjustPayload.addProperty("bizType", "TEST_CREDIT");
      adjustPayload.addProperty("bizId", "compat-credit-1");
      postJson(client, endpoint + "/api/m2/wallet/adjust", GSON.toJson(adjustPayload), null);

      JsonObject wallet = getJsonWithBearer(client, endpoint + "/api/wallet", sessionToken);
      assertEquals(500L, wallet.get("shopCoin").getAsLong());

      JsonObject order = postJsonWithBearer(client, endpoint + "/api/orders", """
          {"productId":%d,"quantity":2,"idempotencyKey":"compat-order-1"}
          """.formatted(productId), sessionToken);
      assertEquals("CREATED", order.get("state").getAsString());
      assertEquals("DELIVERED", order.get("orderStatus").getAsString());

      JsonObject orders = getJsonWithBearer(client, endpoint + "/api/orders/list?limit=50", sessionToken);
      JsonArray orderItems = orders.getAsJsonArray("orders");
      assertEquals(1, orderItems.size());
      assertEquals("DELIVERED", orderItems.get(0).getAsJsonObject().get("status").getAsString());

      JsonObject refund = postJsonWithBearer(client, endpoint + "/api/orders/refund", """
          {"orderNo":"%s"}
          """.formatted(order.get("orderNo").getAsString()), sessionToken);
      assertEquals("REFUNDED", refund.get("status").getAsString());
      assertEquals(500L, refund.get("shopCoin").getAsLong());

      JsonObject currencyMeta = getJson(client, endpoint + "/api/meta/currency", null);
      assertTrue(currencyMeta.has("exchange"));

      JsonObject market = getJson(client, endpoint + "/api/market/listings?limit=30", null);
      assertTrue(market.has("listings"));
    }
  }

  @Test
  void adminCompatibilityApiSupportsLoginAndCoreReadEndpoints() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-admin-compat-test-");
    Path runtimeRoot = tempRoot.resolve("fabric-1.21.x");
    Files.createDirectories(runtimeRoot);
    Files.writeString(runtimeRoot.resolve("m2-runtime.properties"), String.join(System.lineSeparator(),
        "http.host=127.0.0.1",
        "http.port=0",
        "sqlite.path=data/test-m2.sqlite",
        "admin.token=admin-secret"));

    HttpClient client = HttpClient.newHttpClient();
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        "test",
        tempRoot,
        ignored -> {
        })) {
      String endpoint = handle.endpoint();

      JsonObject productPayload = new JsonObject();
      productPayload.addProperty("sku", "admin_pack");
      productPayload.addProperty("title", "Admin Pack");
      productPayload.addProperty("currency", "SHOP_COIN");
      productPayload.addProperty("price", 100);
      productPayload.addProperty("active", true);
      JsonObject product = postJson(client, endpoint + "/api/m2/products", GSON.toJson(productPayload), "admin-secret");
      long productId = product.get("id").getAsLong();
      assertTrue(productId > 0);

      JsonObject userLogin = postJson(client, endpoint + "/api/auth/login", """
          {"identifier":"admin_order_user","password":"password123"}
          """, null);
      String userToken = userLogin.get("sessionToken").getAsString();
      long userId = userLogin.getAsJsonObject("user").get("id").getAsLong();
      assertTrue(userId > 0);

      JsonObject adjustPayload = new JsonObject();
      adjustPayload.addProperty("userId", userId);
      adjustPayload.addProperty("currency", "SHOP_COIN");
      adjustPayload.addProperty("delta", 500);
      adjustPayload.addProperty("bizType", "TEST_CREDIT");
      adjustPayload.addProperty("bizId", "admin-compat-credit-1");
      postJson(client, endpoint + "/api/m2/wallet/adjust", GSON.toJson(adjustPayload), "admin-secret");

      postJsonWithBearer(client, endpoint + "/api/orders", """
          {"productId":%d,"quantity":1,"idempotencyKey":"admin-compat-order-1"}
          """.formatted(productId), userToken);

      JsonObject adminLogin = postJson(client, endpoint + "/api/admin/auth/login", """
          {"identifier":"admin","password":"admin-secret"}
          """, null);
      String adminToken = adminLogin.get("sessionToken").getAsString();
      assertFalse(adminToken.isBlank());
      assertTrue(adminLogin.getAsJsonObject("admin").get("isSuperAdmin").getAsBoolean());

      JsonObject adminProfile = getJsonWithBearer(client, endpoint + "/api/admin/auth/me", adminToken);
      assertEquals("admin", adminProfile.get("username").getAsString());

      JsonObject adminProducts = getJsonWithBearer(client, endpoint + "/api/admin/products/list?includeInactive=true&limit=20", adminToken);
      assertTrue(adminProducts.getAsJsonArray("products").size() >= 1);

      JsonObject adminOrders = getJsonWithBearer(client, endpoint + "/api/admin/orders/list?limit=20", adminToken);
      assertTrue(adminOrders.getAsJsonArray("orders").size() >= 1);

      JsonObject economy = getJsonWithBearer(client, endpoint + "/api/admin/economy/settings", adminToken);
      assertTrue(economy.has("exchange"));
      assertTrue(economy.has("currency"));

      JsonObject locales = getJsonWithBearer(client, endpoint + "/api/admin/locales", adminToken);
      assertTrue(locales.getAsJsonArray("locales").size() >= 1);

      JsonObject themes = getJsonWithBearer(client, endpoint + "/api/admin/themes", adminToken);
      assertTrue(themes.getAsJsonArray("themes").size() >= 1);

      JsonObject users = getJsonWithBearer(client, endpoint + "/api/admin/users/list?limit=20", adminToken);
      assertTrue(users.getAsJsonArray("users").size() >= 1);

      JsonObject lookup = getJsonWithBearer(client, endpoint + "/api/admin/users/lookup?identifier=admin_order_user", adminToken);
      assertEquals("admin_order_user", lookup.get("username").getAsString());
      long lookedUpUserId = lookup.get("id").getAsLong();
      assertTrue(lookedUpUserId > 0);

      JsonObject visualGet = getJsonWithBearer(client, endpoint + "/api/admin/users/visual-permission?userId=" + lookedUpUserId, adminToken);
      assertTrue(visualGet.has("iconPermission"));

      JsonObject visualPost = postJsonWithBearer(client, endpoint + "/api/admin/users/visual-permission", """
          {"userId":%d,"iconPermission":"ALLOW","namePermission":"DENY","uploadPermission":"INHERIT","listingLimitOverride":12}
          """.formatted(lookedUpUserId), adminToken);
      assertEquals(12, visualPost.get("listingLimitEffective").getAsInt());

      JsonObject adminMeta = getJsonWithBearer(client, endpoint + "/api/admin/admin-users/meta?locale=zh-CN", adminToken);
      assertTrue(adminMeta.getAsJsonArray("groups").size() >= 1);

      JsonObject adminList = getJsonWithBearer(client, endpoint + "/api/admin/admin-users/list", adminToken);
      assertTrue(adminList.has("admins"));

      JsonObject audit = getJsonWithBearer(client, endpoint + "/api/admin/audit/list?limit=20", adminToken);
      assertTrue(audit.has("logs"));

      JsonObject marketTagsConfig = getJsonWithBearer(client, endpoint + "/api/admin/market/tags-config", adminToken);
      assertTrue(marketTagsConfig.has("config"));

      JsonObject marketLimitationConfig = getJsonWithBearer(client, endpoint + "/api/admin/market/limitation-config", adminToken);
      assertTrue(marketLimitationConfig.has("config"));

      JsonObject marketListings = getJsonWithBearer(client, endpoint + "/api/admin/market/listings?limit=20", adminToken);
      assertTrue(marketListings.has("listings"));

      JsonObject redeemListBefore = getJsonWithBearer(client, endpoint + "/api/admin/redeem/list?limit=20", adminToken);
      assertTrue(redeemListBefore.has("codes"));

      JsonObject redeemCreate = postJsonWithBearer(client, endpoint + "/api/admin/redeem/create", """
          {"shopCoin":100,"gameCoin":50,"maxUses":10,"perUserMaxUses":1}
          """, adminToken);
      assertTrue(redeemCreate.has("code"));

      JsonObject redeemListAfter = getJsonWithBearer(client, endpoint + "/api/admin/redeem/list?limit=20", adminToken);
      assertTrue(redeemListAfter.getAsJsonArray("codes").size() >= 1);

      postJsonWithBearer(client, endpoint + "/api/admin/auth/logout", "{}", adminToken);
      HttpResponse<String> afterLogout = getTextWithBearer(client, endpoint + "/api/admin/auth/me", adminToken);
      assertEquals(401, afterLogout.statusCode());
    }
  }

  @Test
  void adminUploadedProductIconCanBeServedFromUploadsPath() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-admin-upload-test-");
    Path runtimeRoot = tempRoot.resolve("fabric-1.21.x");
    Files.createDirectories(runtimeRoot);
    Files.writeString(runtimeRoot.resolve("m2-runtime.properties"), String.join(System.lineSeparator(),
        "http.host=127.0.0.1",
        "http.port=0",
        "sqlite.path=data/test-m2.sqlite",
        "admin.token=admin-secret"));

    HttpClient client = HttpClient.newHttpClient();
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        "test",
        tempRoot,
        ignored -> {
        })) {
      String endpoint = handle.endpoint();

      JsonObject adminLogin = postJson(client, endpoint + "/api/admin/auth/login", """
          {"identifier":"admin","password":"admin-secret"}
          """, null);
      String adminToken = adminLogin.get("sessionToken").getAsString();
      assertFalse(adminToken.isBlank());

      JsonObject productPayload = new JsonObject();
      productPayload.addProperty("sku", "upload_icon_pack");
      productPayload.addProperty("title", "Upload Icon Pack");
      productPayload.addProperty("currency", "SHOP_COIN");
      productPayload.addProperty("price", 100);
      productPayload.addProperty("active", true);
      JsonObject product = postJson(client, endpoint + "/api/m2/products", GSON.toJson(productPayload), "admin-secret");
      long productId = product.get("id").getAsLong();
      assertTrue(productId > 0);

      byte[] fakePng = "not-a-real-png-but-bytes".getBytes(StandardCharsets.UTF_8);
      JsonObject upload = postBytesWithBearer(
          client,
          endpoint + "/api/admin/products/icon?productId=" + productId + "&filename=icon-test.png",
          fakePng,
          "image/png",
          adminToken);
      assertTrue(upload.has("displayIconPath"));

      String iconPath = upload.get("displayIconPath").getAsString();
      HttpResponse<byte[]> iconResponse = getBytes(client, endpoint + "/" + iconPath, null);
      assertEquals(200, iconResponse.statusCode());
      assertEquals(fakePng.length, iconResponse.body().length);
    }
  }

  @Test
  void adminUploadedLocaleAndThemePackagesCanBeServedFromRuntimeRoutes() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-admin-l10n-theme-upload-test-");
    Path runtimeRoot = tempRoot.resolve("fabric-1.21.x");
    Files.createDirectories(runtimeRoot);
    Files.writeString(runtimeRoot.resolve("m2-runtime.properties"), String.join(System.lineSeparator(),
        "http.host=127.0.0.1",
        "http.port=0",
        "sqlite.path=data/test-m2.sqlite",
        "admin.token=admin-secret"));

    HttpClient client = HttpClient.newHttpClient();
    try (M2RuntimeBootstrap.RuntimeHandle handle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        "test",
        tempRoot,
        ignored -> {
        })) {
      String endpoint = handle.endpoint();
      JsonObject adminLogin = postJson(client, endpoint + "/api/admin/auth/login", """
          {"identifier":"admin","password":"admin-secret"}
          """, null);
      String adminToken = adminLogin.get("sessionToken").getAsString();
      assertFalse(adminToken.isBlank());

      byte[] localeZip = createZipBytes(
          "web/i18n/app/ja-JP.json", "{\"greeting\":\"こんにちは\"}",
          "web/i18n/admin/ja-JP.json", "{\"adminGreeting\":\"管理画面\"}");
      String localeBase64 = java.util.Base64.getEncoder().encodeToString(localeZip);
      JsonObject localeUpload = postJsonWithBearer(client, endpoint + "/api/admin/locales/upload", """
          {"fileName":"ja-JP.zip","contentBase64":"%s","source":"upload"}
          """.formatted(localeBase64), adminToken);
      assertTrue(localeUpload.has("importedAssets"));
      HttpResponse<String> localeAppResponse = getText(client, endpoint + "/i18n/app/ja-JP.json", null);
      assertEquals(200, localeAppResponse.statusCode());
      assertTrue(localeAppResponse.body().contains("こんにちは"));
      HttpResponse<String> localeAdminResponse = getText(client, endpoint + "/i18n/admin/ja-JP.json", null);
      assertEquals(200, localeAdminResponse.statusCode());
      assertTrue(localeAdminResponse.body().contains("管理画面"));

      byte[] themeZip = createZipBytes(
          "themes/moon/light.css", "body{background:#fff;}",
          "themes/moon/dark.css", "body{background:#111;}");
      String themeBase64 = java.util.Base64.getEncoder().encodeToString(themeZip);
      JsonObject themeUpload = postJsonWithBearer(client, endpoint + "/api/admin/themes/upload", """
          {"fileName":"moon.zip","contentBase64":"%s","source":"upload"}
          """.formatted(themeBase64), adminToken);
      assertTrue(themeUpload.has("importedAssets"));
      HttpResponse<String> lightThemeResponse = getText(client, endpoint + "/themes/moon/light.css", null);
      assertEquals(200, lightThemeResponse.statusCode());
      assertTrue(lightThemeResponse.body().contains("background:#fff"));
      HttpResponse<String> darkThemeResponse = getText(client, endpoint + "/themes/moon/dark.css", null);
      assertEquals(200, darkThemeResponse.statusCode());
      assertTrue(darkThemeResponse.body().contains("background:#111"));
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

  private HttpResponse<String> getText(HttpClient client, String url, String adminToken) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .GET()
        .uri(URI.create(url));
    if (adminToken != null) {
      builder.header("X-WebShopX-Admin-Token", adminToken);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonObject getJsonWithBearer(HttpClient client, String url, String token) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .GET()
        .uri(URI.create(url))
        .header("Authorization", "Bearer " + token)
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    return GSON.fromJson(response.body(), JsonObject.class);
  }

  private JsonObject postJsonWithBearer(HttpClient client, String url, String body, String token) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    return GSON.fromJson(response.body(), JsonObject.class);
  }

  private JsonObject postBytesWithBearer(
      HttpClient client,
      String url,
      byte[] body,
      String contentType,
      String token) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", contentType)
        .header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    return GSON.fromJson(response.body(), JsonObject.class);
  }

  private HttpResponse<String> getTextWithBearer(HttpClient client, String url, String token) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .GET()
        .uri(URI.create(url))
        .header("Authorization", "Bearer " + token)
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> getBytes(HttpClient client, String url, String adminToken) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .GET()
        .uri(URI.create(url));
    if (adminToken != null) {
      builder.header("X-WebShopX-Admin-Token", adminToken);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private byte[] createZipBytes(String firstName, String firstContent, String secondName, String secondContent) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zipOutputStream = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      ZipEntry firstEntry = new ZipEntry(firstName);
      zipOutputStream.putNextEntry(firstEntry);
      zipOutputStream.write(firstContent.getBytes(StandardCharsets.UTF_8));
      zipOutputStream.closeEntry();

      ZipEntry secondEntry = new ZipEntry(secondName);
      zipOutputStream.putNextEntry(secondEntry);
      zipOutputStream.write(secondContent.getBytes(StandardCharsets.UTF_8));
      zipOutputStream.closeEntry();
    }
    return output.toByteArray();
  }
}
