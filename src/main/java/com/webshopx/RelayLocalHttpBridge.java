package com.webshopx;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

class RelayLocalHttpBridge {
  private final HttpClient httpClient;
  private final PluginSettings.EmbeddedWebSettings webSettings;
  private final int timeoutSeconds;

  RelayLocalHttpBridge(PluginSettings.EmbeddedWebSettings webSettings, int timeoutSeconds) {
    this.webSettings = webSettings;
    this.timeoutSeconds = Math.max(2, timeoutSeconds);
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(this.timeoutSeconds))
        .build();
  }

  RelayRpcResponse forward(RelayRpcRequest request) {
    if (request == null || request.http() == null) {
      return RelayRpcResponse.error("", 400, "bad_request", "HTTP envelope is required");
    }
    String path = request.http().path();
    if (!isAllowed(path)) {
      return RelayRpcResponse.error(request.id(), 404, "unknown_method", "Relay path is not allowed");
    }
    try {
      URI uri = localUri(path, request.http().query());
      String method = normalizeMethod(request.http().method());
      RequestBody body = requestBody(request);
      String contentType = contentType(request, body);
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(timeoutSeconds))
          .header("Accept", "application/json")
          .header("X-Forwarded-Proto", "https")
          .header("X-Forwarded-For", "relay");
      builder.header("Content-Type", contentType);
      if (request.auth() != null && request.auth().token() != null && !request.auth().token().isBlank()) {
        builder.header("Authorization", "Bearer " + request.auth().token().trim());
      }
      if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
        builder.header("Idempotency-Key", request.idempotencyKey().trim());
      }
      if ("GET".equals(method)) {
        builder.GET();
      } else if ("POST".equals(method)) {
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes()));
      } else {
        return RelayRpcResponse.error(request.id(), 405, "method_not_allowed", "Method not allowed");
      }
      HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonObject payload = parseJsonResponse(response.body());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return RelayRpcResponse.ok(request.id(), response.statusCode(), payload);
      }
      String code = payload.has("error") && !payload.get("error").isJsonNull()
          ? payload.get("error").getAsString()
          : "relay_http_error";
      String message = payload.has("message") && !payload.get("message").isJsonNull()
          ? payload.get("message").getAsString()
          : "Local WebShopX API returned " + response.statusCode();
      return RelayRpcResponse.error(request.id(), response.statusCode(), code, message);
    } catch (Exception exception) {
      return RelayRpcResponse.error(request.id(), 502, "local_api_unavailable", exception.getMessage());
    }
  }

  private URI localUri(String path, Map<String, String> query) {
    StringBuilder uri = new StringBuilder();
    uri.append("http://").append(localHost()).append(':').append(webSettings.port());
    uri.append(path.startsWith("/") ? path : "/" + path);
    if (query != null && !query.isEmpty()) {
      uri.append('?');
      boolean first = true;
      for (Map.Entry<String, String> entry : query.entrySet()) {
        if (!first) {
          uri.append('&');
        }
        first = false;
        uri.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
      }
    }
    return URI.create(uri.toString());
  }

  private String localHost() {
    String host = webSettings.host();
    if (host == null || host.isBlank()
        || "0.0.0.0".equals(host)
        || "::".equals(host)
        || "[::]".equals(host)) {
      return "127.0.0.1";
    }
    return host;
  }

  private String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private String normalizeMethod(String method) {
    return method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
  }

  private JsonObject parseJsonResponse(String body) {
    if (body == null || body.isBlank()) {
      return new JsonObject();
    }
    JsonElement parsed = JsonParser.parseString(body);
    if (parsed.isJsonObject()) {
      return parsed.getAsJsonObject();
    }
    JsonObject wrapper = new JsonObject();
    wrapper.add("value", parsed);
    return wrapper;
  }

  static boolean isAllowed(String path) {
    if (path == null || !path.startsWith("/api/")) {
      return false;
    }
    return !path.startsWith("/api/setup/")
        && !path.startsWith("/api/relay/")
        && !path.equals("/api/setup")
        && !path.equals("/api/relay");
  }

  private RequestBody requestBody(RelayRpcRequest request) {
    JsonObject payload = request.payload();
    if (payload == null) {
      return new RequestBody("{}".getBytes(StandardCharsets.UTF_8), null);
    }
    if (payload.has("__relayBodyBase64") && payload.get("__relayBodyBase64").getAsBoolean()) {
      String encoded = payload.has("bodyBase64") && !payload.get("bodyBase64").isJsonNull()
          ? payload.get("bodyBase64").getAsString()
          : "";
      String contentType = payload.has("contentType") && !payload.get("contentType").isJsonNull()
          ? payload.get("contentType").getAsString()
          : null;
      return new RequestBody(Base64.getDecoder().decode(encoded), contentType);
    }
    return new RequestBody(payload.toString().getBytes(StandardCharsets.UTF_8), null);
  }

  private String contentType(RelayRpcRequest request, RequestBody body) {
    if (body.contentType() != null && !body.contentType().isBlank()) {
      return body.contentType();
    }
    if (request.http().headers() != null) {
      String contentType = request.http().headers().get("content-type");
      if (contentType != null && !contentType.isBlank()) {
        return contentType;
      }
    }
    return "application/json; charset=utf-8";
  }

  private record RequestBody(byte[] bytes, String contentType) {
  }
}
