package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

class RelayConnectorService implements AutoCloseable {
  private final JavaPlugin plugin;
  private final PluginSettings.RelaySettings settings;
  private final RelayRpcRouter rpcRouter;
  private final Gson gson;
  private final HttpClient httpClient;
  private final ScheduledExecutorService executorService;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean connecting = new AtomicBoolean(false);
  private final AtomicLong reconnectCount = new AtomicLong();
  private final AtomicLong requestsReceived = new AtomicLong();
  private final AtomicLong requestsSucceeded = new AtomicLong();
  private final AtomicLong requestsFailed = new AtomicLong();

  private volatile WebSocket webSocket;
  private volatile Instant connectedAt;
  private volatile Instant lastHeartbeatAt;
  private volatile String lastError;

  RelayConnectorService(
      JavaPlugin plugin,
      PluginSettings.RelaySettings settings,
      RelayRpcRouter rpcRouter) {
    this.plugin = plugin;
    this.settings = settings;
    this.rpcRouter = rpcRouter;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(Math.max(2, settings.rpcTimeoutSeconds())))
        .build();
    this.executorService = Executors.newSingleThreadScheduledExecutor(new RelayThreadFactory());
  }

  void start() {
    if (!settings.shouldConnect()) {
      lastError = "Relay endpoint or connector token is not configured";
      plugin.getLogger().info("Relay is enabled but endpoint/token is missing; connector will stay offline.");
      return;
    }
    plugin.getLogger().info("Starting Relay connector: " + settings.endpoint());
    scheduleConnect(0);
    scheduleHeartbeat();
  }

  RelayStatus status() {
    return new RelayStatus(
        settings.enabled(),
        webSocket != null && !webSocket.isOutputClosed() && !webSocket.isInputClosed(),
        settings.endpoint(),
        settings.serverId(),
        connectedAt,
        lastHeartbeatAt,
        reconnectCount.get(),
        requestsReceived.get(),
        requestsSucceeded.get(),
        requestsFailed.get(),
        lastError);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    WebSocket socket = webSocket;
    webSocket = null;
    if (socket != null) {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin_shutdown");
      } catch (Exception ignored) {
        // The connector is shutting down; nothing useful can be recovered here.
      }
    }
    executorService.shutdownNow();
  }

  private void connect() {
    if (closed.get() || !settings.shouldConnect() || !connecting.compareAndSet(false, true)) {
      return;
    }
    try {
      URI uri = connectorUri();
      httpClient.newWebSocketBuilder()
          .header("Authorization", "Bearer " + settings.connectorToken())
          .connectTimeout(Duration.ofSeconds(Math.max(2, settings.rpcTimeoutSeconds())))
          .buildAsync(uri, new RelayListener())
          .whenComplete((socket, throwable) -> {
            connecting.set(false);
            if (throwable != null) {
              handleDisconnect("connect_failed: " + rootMessage(throwable), throwable);
              return;
            }
            webSocket = socket;
            connectedAt = Instant.now();
            lastHeartbeatAt = connectedAt;
            lastError = null;
            sendHello(socket);
            plugin.getLogger().info("Relay connected: " + uri);
          });
    } catch (Exception exception) {
      connecting.set(false);
      handleDisconnect("connect_failed: " + rootMessage(exception), exception);
    }
  }

  private void sendHello(WebSocket socket) {
    JsonObject hello = new JsonObject();
    hello.addProperty("type", "hello");
    hello.addProperty("protocolVersion", 1);
    hello.addProperty("serverId", settings.serverId());
    hello.addProperty("token", settings.connectorToken());
    hello.addProperty("pluginVersion", plugin.getDescription().getVersion());
    hello.addProperty("minecraftVersion", resolveMinecraftVersion());
    com.google.gson.JsonArray capabilities = new com.google.gson.JsonArray();
    for (String capability : List.of("health", "auth", "products", "orders", "admin")) {
      capabilities.add(capability);
    }
    hello.add("capabilities", capabilities);
    socket.sendText(gson.toJson(hello), true);
  }

  private void scheduleHeartbeat() {
    executorService.scheduleAtFixedRate(
        () -> {
          if (closed.get()) {
            return;
          }
          WebSocket socket = webSocket;
          if (socket == null || socket.isInputClosed() || socket.isOutputClosed()) {
            return;
          }
          JsonObject ping = new JsonObject();
          ping.addProperty("type", "ping");
          ping.addProperty("time", Instant.now().toString());
          socket.sendText(gson.toJson(ping), true)
              .exceptionally(throwable -> {
                handleDisconnect("heartbeat_failed: " + rootMessage(throwable), throwable);
                return null;
              });
        },
        settings.heartbeatSeconds(),
        settings.heartbeatSeconds(),
        TimeUnit.SECONDS);
  }

  private void scheduleConnect(long delaySeconds) {
    if (closed.get()) {
      return;
    }
    executorService.schedule(this::connect, Math.max(0L, delaySeconds), TimeUnit.SECONDS);
  }

  private void handleDisconnect(String message, Throwable throwable) {
    WebSocket socket = webSocket;
    webSocket = null;
    connectedAt = null;
    lastError = message;
    long count = reconnectCount.incrementAndGet();
    if (throwable == null) {
      plugin.getLogger().info("Relay disconnected: " + message);
    } else {
      plugin.getLogger().log(Level.WARNING, "Relay disconnected: " + message, throwable);
    }
    if (!closed.get()) {
      long delay = reconnectDelaySeconds(count);
      plugin.getLogger().info("Relay reconnect scheduled in " + delay + "s.");
      scheduleConnect(delay);
    }
    if (socket != null && !socket.isOutputClosed()) {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
      } catch (Exception ignored) {
        // Best-effort cleanup only.
      }
    }
  }

  private long reconnectDelaySeconds(long count) {
    long min = Math.max(1, settings.reconnectMinSeconds());
    long max = Math.max(min, settings.reconnectMaxSeconds());
    long exponential = min * (1L << Math.min(6, Math.max(0, count - 1)));
    return Math.min(max, exponential);
  }

  private URI connectorUri() {
    String endpoint = settings.endpoint();
    String scheme;
    String rest;
    if (endpoint.regionMatches(true, 0, "https://", 0, "https://".length())) {
      scheme = "wss://";
      rest = endpoint.substring("https://".length());
    } else if (endpoint.regionMatches(true, 0, "http://", 0, "http://".length())) {
      scheme = "ws://";
      rest = endpoint.substring("http://".length());
    } else {
      scheme = "wss://";
      rest = endpoint;
    }
    String separator = settings.websocketPath().contains("?") ? "&" : "?";
    String serverId = URLEncoder.encode(settings.serverId(), StandardCharsets.UTF_8);
    return URI.create(scheme + rest + settings.websocketPath() + separator + "server_id=" + serverId);
  }

  private void handleTextMessage(String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    JsonObject message;
    try {
      JsonElement parsed = JsonParser.parseString(text);
      if (!parsed.isJsonObject()) {
        return;
      }
      message = parsed.getAsJsonObject();
    } catch (RuntimeException exception) {
      lastError = "invalid_json: " + exception.getMessage();
      return;
    }

    String type = optionalString(message, "type");
    if ("hello.ack".equals(type)) {
      boolean ok = !message.has("ok") || message.get("ok").getAsBoolean();
      if (!ok) {
        lastError = optionalString(message, "error");
      }
      lastHeartbeatAt = Instant.now();
      return;
    }
    if ("pong".equals(type)) {
      lastHeartbeatAt = Instant.now();
      return;
    }
    if ("ping".equals(type)) {
      lastHeartbeatAt = Instant.now();
      JsonObject pong = new JsonObject();
      pong.addProperty("type", "pong");
      pong.addProperty("time", Instant.now().toString());
      WebSocket socket = webSocket;
      if (socket != null) {
        socket.sendText(gson.toJson(pong), true);
      }
      return;
    }
    if ("rpc.request".equals(type)) {
      executorService.execute(() -> handleRpcRequest(message));
    }
  }

  private void handleRpcRequest(JsonObject message) {
    requestsReceived.incrementAndGet();
    RelayRpcRequest request = parseRpcRequest(message);
    RelayRpcResponse response = rpcRouter.route(request);
    if (response.ok()) {
      requestsSucceeded.incrementAndGet();
    } else {
      requestsFailed.incrementAndGet();
    }
    JsonObject outbound = new JsonObject();
    outbound.addProperty("type", "rpc.response");
    outbound.addProperty("id", response.id());
    outbound.addProperty("ok", response.ok());
    outbound.addProperty("status", response.status());
    if (response.ok()) {
      outbound.add("payload", response.payload() == null ? new JsonObject() : response.payload());
    } else {
      JsonObject error = new JsonObject();
      error.addProperty("code", response.error().code());
      error.addProperty("message", response.error().message());
      outbound.add("error", error);
    }
    WebSocket socket = webSocket;
    if (socket == null || socket.isOutputClosed()) {
      requestsFailed.incrementAndGet();
      lastError = "Cannot send RPC response because websocket is closed";
      return;
    }
    socket.sendText(gson.toJson(outbound), true)
        .exceptionally(throwable -> {
          requestsFailed.incrementAndGet();
          handleDisconnect("send_failed: " + rootMessage(throwable), throwable);
          return null;
        });
  }

  private RelayRpcRequest parseRpcRequest(JsonObject message) {
    JsonObject http = objectOrEmpty(message, "http");
    JsonObject auth = objectOrEmpty(message, "auth");
    JsonObject payload = objectOrEmpty(message, "payload");
    return new RelayRpcRequest(
        optionalString(message, "id"),
        optionalString(message, "method"),
        new RelayRpcRequest.HttpEnvelope(
            optionalString(http, "method"),
            optionalString(http, "path"),
            parseStringMap(objectOrEmpty(http, "query")),
            parseStringMap(objectOrEmpty(http, "headers"))),
        new RelayRpcRequest.AuthEnvelope(optionalString(auth, "token")),
        payload,
        optionalString(message, "idempotencyKey"),
        intOrDefault(message, "timeoutMs", settings.rpcTimeoutSeconds() * 1000));
  }

  private Map<String, String> parseStringMap(JsonObject object) {
    java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      JsonElement value = entry.getValue();
      if (value == null || value.isJsonNull()) {
        result.put(entry.getKey(), "");
      } else {
        result.put(entry.getKey(), value.getAsString());
      }
    }
    return result;
  }

  private JsonObject objectOrEmpty(JsonObject object, String key) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonObject()) {
      return new JsonObject();
    }
    return object.getAsJsonObject(key);
  }

  private String optionalString(JsonObject object, String key) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return null;
    }
    String value = object.get(key).getAsString();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private int intOrDefault(JsonObject object, String key, int fallback) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return fallback;
    }
    try {
      return object.get(key).getAsInt();
    } catch (RuntimeException exception) {
      return fallback;
    }
  }

  private String resolveMinecraftVersion() {
    String version = plugin.getServer().getMinecraftVersion();
    if (version != null && !version.isBlank()) {
      return version;
    }
    String bukkitVersion = plugin.getServer().getBukkitVersion();
    if (bukkitVersion == null || bukkitVersion.isBlank()) {
      return "unknown";
    }
    int separator = bukkitVersion.indexOf('-');
    return separator <= 0 ? bukkitVersion : bukkitVersion.substring(0, separator);
  }

  private String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current != null && current.getCause() != null) {
      current = current.getCause();
    }
    String message = current == null ? null : current.getMessage();
    return message == null || message.isBlank()
        ? Objects.toString(throwable.getClass().getSimpleName(), "error")
        : message;
  }

  private final class RelayListener implements WebSocket.Listener {
    private final StringBuilder textBuffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      WebSocket.Listener.super.onOpen(webSocket);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      textBuffer.append(data);
      if (last) {
        String text = textBuffer.toString();
        textBuffer.setLength(0);
        handleTextMessage(text);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      handleDisconnect("closed(" + statusCode + "): " + reason, null);
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      handleDisconnect("websocket_error: " + rootMessage(error), error);
    }
  }

  private static final class RelayThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "webshopx-relay");
      thread.setDaemon(true);
      return thread;
    }
  }
}
