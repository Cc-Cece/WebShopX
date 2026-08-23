package com.webshopx.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webshopx.AdminPermission;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.CurrencyType;
import com.webshopx.ServiceException;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedCommerceService.ProductInput;
import com.webshopx.SharedCommerceService.ProductKind;
import com.webshopx.SharedCommerceService.PurchaseRequest;
import com.webshopx.WalletService;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.PlatformIdentity;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small platform-neutral HTTP surface used by dedicated-server Loader distributions. */
public final class SharedHttpApi implements AutoCloseable {
  private static final int MAX_BODY = 64 * 1024;
  private final AuthService auth;
  private final WalletService wallets;
  private final SharedCommerceService commerce;
  private final AdminService administration;
  private final PlatformIdentity identity;
  private final CapabilitySnapshot capabilities;
  private final String allowedOrigin;
  private final Gson gson = new Gson();
  private final ExecutorService executor;
  private final HttpServer server;

  public SharedHttpApi(String host, int port, String allowedOrigin, AuthService auth,
      WalletService wallets, SharedCommerceService commerce, AdminService administration,
      PlatformIdentity identity, CapabilitySnapshot capabilities) {
    this.auth = Objects.requireNonNull(auth, "auth");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.commerce = Objects.requireNonNull(commerce, "commerce");
    this.administration = Objects.requireNonNull(administration, "administration");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.allowedOrigin = allowedOrigin == null ? "" : allowedOrigin.trim();
    try {
      server = HttpServer.create(new InetSocketAddress(host, port), 64);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot bind WebShopX HTTP API", failure);
    }
    executor = Executors.newFixedThreadPool(
        Math.max(2, Math.min(16, Integer.getInteger("webshopx.http.threads", 4))), runnable -> {
          Thread thread = new Thread(runnable, "webshopx-http");
          thread.setDaemon(true);
          return thread;
        });
    server.setExecutor(executor);
    server.createContext("/", this::dispatch);
  }

  public void start() { server.start(); }
  public int port() { return server.getAddress().getPort(); }

  private void dispatch(HttpExchange exchange) throws IOException {
    try {
      applySecurityHeaders(exchange);
      if ("OPTIONS".equals(exchange.getRequestMethod())) {
        respond(exchange, 204, Map.of());
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/health") && method(exchange, "GET")) {
        respond(exchange, 200, Map.of("status", "UP", "platform", identity,
            "capabilities", Map.of("capturedAt", capabilities.capturedAt().toString(),
                "states", capabilities.states())));
      } else if (path.equals("/") && method(exchange, "GET")) {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>WebShopX</title>"
            + "<main><h1>WebShopX</h1><p>Server API is ready.</p></main>")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      } else if (path.equals("/api/auth/login") && method(exchange, "POST")) {
        JsonObject input = body(exchange);
        var result = auth.login(requiredString(input, "identifier"), requiredString(input, "password"));
        respond(exchange, 200, Map.of("token", result.sessionToken(),
            "expiresAt", result.expiresAt().toString(),
            "user", result.user()));
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
        respond(exchange, 200, wallets.listRecentLedger(user.id(), queryInt(exchange, "limit", 20))
            .stream().map(entry -> Map.of(
                "currency", entry.currency(), "delta", entry.delta(), "bizType", entry.bizType(),
                "bizId", entry.bizId(), "createdAt", entry.createdAt().toString())).toList());
      } else if (path.equals("/api/products") && method(exchange, "GET")) {
        respond(exchange, 200, commerce.products(false));
      } else if (path.equals("/api/orders") && method(exchange, "POST")) {
        var user = user(exchange);
        if (user.boundUuid() == null) throw new ServiceException("not_bound", "User is not bound");
        JsonObject input = body(exchange);
        respond(exchange, 200, commerce.purchase(new PurchaseRequest(user.id(), user.boundUuid(),
            requiredLong(input, "productId"), optionalInt(input, "quantity", 1),
            requiredString(input, "idempotencyKey"), identity.serverId())));
      } else if (path.equals("/api/deliveries") && method(exchange, "GET")) {
        var user = user(exchange);
        if (user.boundUuid() == null) throw new ServiceException("not_bound", "User is not bound");
        respond(exchange, 200, commerce.pendingDeliveries(user.boundUuid(), identity.serverId()));
      } else if (path.equals("/api/admin/products") && method(exchange, "POST")) {
        var user = user(exchange);
        administration.requireAdmin(user, AdminPermission.PRODUCT_MANAGE);
        JsonObject input = body(exchange);
        Integer stock = input.has("stock") && !input.get("stock").isJsonNull()
            ? input.get("stock").getAsInt() : null;
        respond(exchange, 200, commerce.createProduct(new ProductInput(
            requiredString(input, "sku"), requiredString(input, "title"),
            optionalString(input, "remark", null),
            CurrencyType.valueOf(requiredString(input, "currency").toUpperCase()),
            requiredLong(input, "price"),
            ProductKind.valueOf(requiredString(input, "kind").toUpperCase()),
            optionalString(input, "command", ""), optionalString(input, "registryId", null),
            stock, !input.has("active") || input.get("active").getAsBoolean())));
      } else if (path.equals("/api/admin/wallet-adjust") && method(exchange, "POST")) {
        var actor = user(exchange);
        administration.requireAdmin(actor, AdminPermission.USER_SUPPORT);
        JsonObject input = body(exchange);
        respond(exchange, 200, wallets.adjustBalance(requiredLong(input, "userId"),
            CurrencyType.valueOf(requiredString(input, "currency").toUpperCase()),
            requiredLong(input, "delta"), "ADMIN_ADJUST", requiredString(input, "idempotencyKey")));
      } else {
        respond(exchange, 404, error("not_found", "Route was not found"));
      }
    } catch (BodyTooLarge failure) {
      respond(exchange, 413, error("body_too_large", failure.getMessage()));
    } catch (ServiceException failure) {
      int status = switch (failure.code()) {
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
      System.err.printf("[WebShopX] HTTP request failed path=%s type=%s%n",
          exchange.getRequestURI().getPath(), failure.getClass().getSimpleName());
      respond(exchange, 500, error("internal_error", "Request failed"));
    }
  }

  private AuthService.AuthUser user(HttpExchange exchange) {
    return auth.findUserBySession(token(exchange))
        .orElseThrow(() -> new ServiceException("unauthorized", "Authentication required"));
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

  private void applySecurityHeaders(HttpExchange exchange) {
    Headers headers = exchange.getResponseHeaders();
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Cache-Control", "no-store");
    headers.set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (!allowedOrigin.isEmpty() && allowedOrigin.equals(origin)) {
      headers.set("Access-Control-Allow-Origin", origin);
      headers.set("Vary", "Origin");
      headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key");
      headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }
  }

  private void respond(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] bytes = status == 204 ? new byte[0] : gson.toJson(value).getBytes(StandardCharsets.UTF_8);
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
    try { return value == null ? -1 : Integer.parseInt(value); }
    catch (NumberFormatException ignored) { return -1; }
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
  private static int optionalInt(JsonObject input, String key, int fallback) {
    return input.has(key) ? input.get(key).getAsInt() : fallback;
  }
  private static int queryInt(HttpExchange exchange, String key, int fallback) {
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null) return fallback;
    for (String part : query.split("&")) {
      String[] pair = part.split("=", 2);
      if (pair[0].equals(key) && pair.length == 2) {
        try { return Integer.parseInt(pair[1]); } catch (NumberFormatException ignored) { return fallback; }
      }
    }
    return fallback;
  }

  @Override public void close() {
    server.stop(1);
    executor.shutdownNow();
  }

  private static final class BodyTooLarge extends RuntimeException {
    BodyTooLarge(String message) { super(message); }
  }
}
