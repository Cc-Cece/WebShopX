package com.webshopx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class RelaySetupService {
  private final WebShopPlugin plugin;
  private final SchedulerBridge scheduler;
  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  private final AtomicBoolean running = new AtomicBoolean(false);

  RelaySetupService(WebShopPlugin plugin, SchedulerBridge scheduler) {
    this.plugin = plugin;
    this.scheduler = scheduler;
  }

  boolean start(String endpoint, Consumer<SetupEvent> callback) {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    scheduler.runAsync(() -> run(endpoint, callback));
    return true;
  }

  private void run(String endpoint, Consumer<SetupEvent> callback) {
    try {
      String base = endpoint == null ? "" : endpoint.trim().replaceAll("/+$", "");
      URI baseUri = URI.create(base);
      String host = baseUri.getHost() == null ? "" : baseUri.getHost();
      boolean loopback = host.equalsIgnoreCase("localhost")
          || host.equals("127.0.0.1")
          || host.equals("::1");
      if (!"https".equalsIgnoreCase(baseUri.getScheme())
          && !("http".equalsIgnoreCase(baseUri.getScheme()) && loopback)) {
        throw new IllegalArgumentException("Relay authorization requires HTTPS");
      }
      JsonObject requestJson = new JsonObject();
      requestJson.addProperty("installationId", RelayConnectorService.loadInstallationId(plugin));
      requestJson.addProperty("serverName", plugin.getServer().getName());
      URI createUri = URI.create(base + "/api/platform/device-authorizations");
      HttpRequest request = HttpRequest.newBuilder(createUri)
          .timeout(Duration.ofSeconds(15))
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      JsonObject created = parseSuccess(response);
      String pollToken = created.get("pollToken").getAsString();
      String authorizationUrl = created.get("authorizationUrl").getAsString();
      Instant expiresAt = Instant.parse(created.get("expiresAt").getAsString());
      emit(callback, new SetupEvent("created", authorizationUrl, null, null));
      while (Instant.now().isBefore(expiresAt)) {
        Thread.sleep(2000L);
        JsonObject pollJson = new JsonObject();
        pollJson.addProperty("pollToken", pollToken);
        URI pollUri = URI.create(base + "/api/platform/device-authorizations/poll");
        HttpRequest poll = HttpRequest.newBuilder(pollUri)
            .timeout(Duration.ofSeconds(15))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(pollJson.toString()))
            .build();
        HttpResponse<String> pollResponse = client.send(
            poll,
            HttpResponse.BodyHandlers.ofString());
        JsonObject result = parseSuccess(pollResponse);
        if ("authorized".equals(result.get("status").getAsString())) {
          String accessKey = result.get("accessKey").getAsString();
          emit(callback, new SetupEvent("authorized", null, accessKey, null));
          return;
        }
      }
      emit(callback, new SetupEvent("failed", null, null, "Authorization expired"));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      emit(callback, new SetupEvent("failed", null, null, "Authorization interrupted"));
    } catch (Exception exception) {
      emit(callback, new SetupEvent("failed", null, null, exception.getMessage()));
    } finally {
      running.set(false);
    }
  }

  private JsonObject parseSuccess(HttpResponse<String> response) {
    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
    boolean failed = response.statusCode() < 200
        || response.statusCode() >= 300
        || (body.has("ok") && !body.get("ok").getAsBoolean());
    if (failed) {
      String message = body.has("message")
          ? body.get("message").getAsString()
          : "HTTP " + response.statusCode();
      throw new IllegalStateException(message);
    }
    return body;
  }

  private void emit(Consumer<SetupEvent> callback, SetupEvent event) {
    scheduler.runGlobal(() -> callback.accept(event));
  }

  record SetupEvent(String status, String authorizationUrl, String accessKey, String error) {}
}
