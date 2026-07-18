package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
  private final AtomicBoolean reconnectImmediately = new AtomicBoolean(false);
  private final AtomicLong reconnectCount = new AtomicLong();
  private final AtomicLong requestsReceived = new AtomicLong();
  private final AtomicLong requestsSucceeded = new AtomicLong();
  private final AtomicLong requestsFailed = new AtomicLong();

  private volatile WebSocket webSocket;
  private volatile Instant connectedAt;
  private volatile Instant lastHeartbeatAt;
  private volatile String lastError;
  private volatile int heartbeatSeconds;
  private volatile int reconnectMinSeconds;
  private volatile int reconnectMaxSeconds;
  private volatile int rpcTimeoutSeconds;
  private final String installationId;

  RelayConnectorService(
      JavaPlugin plugin,
      PluginSettings.RelaySettings settings,
      RelayRpcRouter rpcRouter) {
    this.plugin = plugin;
    this.settings = settings;
    this.rpcRouter = rpcRouter;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
    this.heartbeatSeconds = settings.heartbeatSeconds();
    this.reconnectMinSeconds = settings.reconnectMinSeconds();
    this.reconnectMaxSeconds = settings.reconnectMaxSeconds();
    this.rpcTimeoutSeconds = settings.rpcTimeoutSeconds();
    loadCachedConnectionPolicy();
    this.installationId = loadInstallationId();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(Math.max(2, rpcTimeoutSeconds)))
        .build();
    this.executorService = Executors.newSingleThreadScheduledExecutor(new RelayThreadFactory());
  }

  void start() {
    if (!settings.shouldConnect()) {
      lastError = "Relay URL or access key is not configured";
      plugin.getLogger().info("Relay is enabled but URL/access key is missing; connector will stay offline.");
      return;
    }
    plugin.getLogger().info("Starting Relay connector: " + settings.endpoint());
    if (settings.endpoint().regionMatches(true, 0, "http://", 0, "http://".length())) {
      plugin.getLogger().warning("Relay endpoint uses unencrypted WebSocket (ws://); use HTTPS/WSS in production.");
    }
    scheduleConnect(0);
    scheduleHeartbeat();
  }

  RelayStatus status() {
    return new RelayStatus(
        settings.enabled(),
        webSocket != null && !webSocket.isOutputClosed() && !webSocket.isInputClosed(),
        settings.endpoint(),
        installationId,
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
    WebSocket current = webSocket;
    if (closed.get()
        || !settings.shouldConnect()
        || (current != null && !current.isInputClosed() && !current.isOutputClosed())
        || !connecting.compareAndSet(false, true)) {
      return;
    }
    try {
      URI uri = connectorUri();
      httpClient.newWebSocketBuilder()
          .header("Authorization", "Bearer " + settings.accessKey())
          .connectTimeout(Duration.ofSeconds(Math.max(2, rpcTimeoutSeconds)))
          .buildAsync(uri, new RelayListener())
          .whenComplete((socket, throwable) -> {
            connecting.set(false);
            if (throwable != null) {
              handleDisconnect(null, "connect_failed: " + rootMessage(throwable), throwable);
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
      handleDisconnect(null, "connect_failed: " + rootMessage(exception), exception);
    }
  }

  private void sendHello(WebSocket socket) {
    JsonObject hello = new JsonObject();
    hello.addProperty("type", "hello");
    hello.addProperty("protocolVersion", 1);
    hello.addProperty("accessKey", settings.accessKey());
    hello.addProperty("installationId", installationId);
    hello.addProperty("machineName", resolveMachineName());
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
    executorService.schedule(
        () -> {
          if (closed.get()) {
            return;
          }
          WebSocket socket = webSocket;
          if (socket == null || socket.isInputClosed() || socket.isOutputClosed()) {
            scheduleHeartbeat();
            return;
          }
          JsonObject ping = new JsonObject();
          ping.addProperty("type", "ping");
          ping.addProperty("time", Instant.now().toString());
          socket.sendText(gson.toJson(ping), true)
              .exceptionally(throwable -> {
                handleDisconnect(socket, "heartbeat_failed: " + rootMessage(throwable), throwable);
                return null;
              });
          scheduleHeartbeat();
        },
        heartbeatSeconds,
        TimeUnit.SECONDS);
  }

  private void scheduleConnect(long delaySeconds) {
    if (closed.get()) {
      return;
    }
    executorService.schedule(this::connect, Math.max(0L, delaySeconds), TimeUnit.SECONDS);
  }

  private void handleDisconnect(WebSocket expectedSocket, String message, Throwable throwable) {
    WebSocket socket = webSocket;
    if (expectedSocket != null && socket != expectedSocket) {
      return;
    }
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
      long delay = reconnectImmediately.getAndSet(false) ? 0 : reconnectDelaySeconds(count);
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
    long min = Math.max(1, reconnectMinSeconds);
    long max = Math.max(min, reconnectMaxSeconds);
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
    return URI.create(scheme + rest + "/connector/ws");
  }

  private String loadInstallationId() {
    Path file = plugin.getDataFolder().toPath().resolve("relay-installation-id");
    try {
      if (Files.isRegularFile(file)) {
        String value = Files.readString(file).trim();
        if (!value.isBlank()) return value;
      }
      Files.createDirectories(file.getParent());
      String value = UUID.randomUUID().toString();
      Files.writeString(file, value + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      return value;
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to load Relay installation ID", exception);
    }
  }

  private void loadCachedConnectionPolicy() {
    Path file = plugin.getDataFolder().toPath().resolve("relay-connection-policy.json");
    try {
      if (Files.isRegularFile(file)) {
        JsonElement parsed = JsonParser.parseString(Files.readString(file));
        if (parsed.isJsonObject()) applyConnectionPolicyObject(parsed.getAsJsonObject(), false);
      }
    } catch (Exception exception) {
      plugin.getLogger().log(Level.WARNING, "Ignoring invalid cached Relay connection policy", exception);
    }
  }

  private void applyConnectionPolicy(JsonObject message) {
    if (!message.has("connectionPolicy") || !message.get("connectionPolicy").isJsonObject()) return;
    applyConnectionPolicyObject(message.getAsJsonObject("connectionPolicy"), true);
  }

  private void applyConnectionPolicyObject(JsonObject policy, boolean persist) {
    int nextHeartbeat = clamp(intOrDefault(policy, "heartbeatSeconds", heartbeatSeconds), 10, 300);
    int nextReconnectMin = clamp(intOrDefault(policy, "reconnectMinSeconds", reconnectMinSeconds), 1, 300);
    int nextReconnectMax = clamp(intOrDefault(policy, "reconnectMaxSeconds", reconnectMaxSeconds), nextReconnectMin, 900);
    int nextRpcTimeout = clamp(intOrDefault(policy, "rpcTimeoutSeconds", rpcTimeoutSeconds), 2, 120);
    heartbeatSeconds = nextHeartbeat;
    reconnectMinSeconds = nextReconnectMin;
    reconnectMaxSeconds = nextReconnectMax;
    rpcTimeoutSeconds = nextRpcTimeout;
    if (!persist) return;
    JsonObject cached = new JsonObject();
    cached.addProperty("heartbeatSeconds", nextHeartbeat);
    cached.addProperty("reconnectMinSeconds", nextReconnectMin);
    cached.addProperty("reconnectMaxSeconds", nextReconnectMax);
    cached.addProperty("rpcTimeoutSeconds", nextRpcTimeout);
    Path file = plugin.getDataFolder().toPath().resolve("relay-connection-policy.json");
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, gson.toJson(cached) + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (Exception exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to cache Relay connection policy", exception);
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private String resolveMachineName() {
    for (String key : List.of("HOSTNAME", "COMPUTERNAME")) {
      String value = System.getenv(key);
      if (value != null && !value.isBlank()) return value.trim();
    }
    try { return java.net.InetAddress.getLocalHost().getHostName(); }
    catch (Exception ignored) { return "unknown"; }
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
      applyConnectionPolicy(message);
      boolean ok = !message.has("ok") || message.get("ok").getAsBoolean();
      String status = optionalString(message, "status");
      if (ok && "pending_binding".equals(status)) {
        lastError = null;
        plugin.getLogger().info("Relay authenticated; waiting for dashboard binding. Installation ID: " + installationId);
      } else if (ok) {
        reconnectCount.set(0);
      }
      if (!ok) {
        lastError = optionalString(message, "error");
      }
      lastHeartbeatAt = Instant.now();
      return;
    }
    if ("installation.bound".equals(type)) {
      reconnectImmediately.set(true);
      plugin.getLogger().info("Relay installation bound to project: " + optionalString(message, "projectSlug"));
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
          handleDisconnect(socket, "send_failed: " + rootMessage(throwable), throwable);
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
        intOrDefault(message, "timeoutMs", rpcTimeoutSeconds * 1000));
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
      handleDisconnect(webSocket, "closed(" + statusCode + "): " + reason, null);
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      handleDisconnect(webSocket, "websocket_error: " + rootMessage(error), error);
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
