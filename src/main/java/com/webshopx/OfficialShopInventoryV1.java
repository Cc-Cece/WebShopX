package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * V1 bridge that turns a trusted, live player inventory item into an immutable official-shop
 * snapshot product without removing the source item.
 *
 * <p>The existing official shop remains the source of truth for SKU, price, stock, limits,
 * dynamic pricing and order state. A snapshot product is carried through the existing
 * GIVE_CUSTOM_ITEM command delivery path using a private console-only command whose payload is
 * only an immutable snapshot hash. The browser never submits serialized item bytes.</p>
 */
final class OfficialShopInventoryV1 implements CommandExecutor {
  private static final String HTTP_PATH = "/api/admin/products/from-inventory";
  private static final String INTERNAL_COMMAND = "webshopx-internal";
  private static final String INTERNAL_SUBCOMMAND = "snapshot-give";
  private static final int MAX_BODY_BYTES = 256 * 1024;
  private static volatile OfficialShopInventoryV1 instance;

  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final Gson gson;
  private final ItemSnapshotCodec itemCodec;
  private final InventoryService inventoryService;
  private final MailboxService mailboxService;
  private final SchedulerBridge schedulerBridge;
  private volatile HttpServer installedServer;

  static synchronized void bootstrap(JavaPlugin plugin, DatabaseManager databaseManager, Gson gson) {
    if (instance != null) {
      return;
    }
    instance = new OfficialShopInventoryV1(plugin, databaseManager, gson);
  }

  private OfficialShopInventoryV1(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      Gson gson) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.gson = gson;
    this.itemCodec = new ItemSnapshotCodec();
    this.inventoryService = new InventoryService(itemCodec);
    this.mailboxService = new MailboxService(databaseManager);
    this.schedulerBridge = readPluginField("schedulerBridge", SchedulerBridge.class);
    ensureSchema();
    registerInternalCommand();
    schedulerBridge.runGlobalTimer(this::installEndpointIfReady, 10L, 20L);
  }

  private void registerInternalCommand() {
    var command = plugin.getCommand(INTERNAL_COMMAND);
    if (command == null) {
      throw new IllegalStateException("Internal snapshot delivery command is missing from plugin.yml");
    }
    command.setExecutor(this);
  }

  private void ensureSchema() {
    databaseManager.withConnection(connection -> {
      String sql;
      if (databaseManager.dbType().isSqlite()) {
        sql = """
            CREATE TABLE IF NOT EXISTS official_item_snapshots (
              item_hash TEXT NOT NULL PRIMARY KEY,
              item_blob BLOB NOT NULL,
              item_meta_json TEXT NOT NULL,
              item_material TEXT NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
      } else {
        sql = """
            CREATE TABLE IF NOT EXISTS official_item_snapshots (
              item_hash CHAR(64) NOT NULL,
              item_blob LONGBLOB NOT NULL,
              item_meta_json LONGTEXT NOT NULL,
              item_material VARCHAR(64) NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (item_hash)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
      }
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.executeUpdate();
      }
      return null;
    });
  }

  private void installEndpointIfReady() {
    try {
      EmbeddedWebServer embedded = readPluginField("embeddedWebServer", EmbeddedWebServer.class);
      if (embedded == null) {
        return;
      }
      Field serverField = EmbeddedWebServer.class.getDeclaredField("server");
      serverField.setAccessible(true);
      HttpServer server = (HttpServer) serverField.get(embedded);
      if (server == null || server == installedServer) {
        return;
      }
      try {
        server.createContext(HTTP_PATH, this::handleCreateFromInventory);
      } catch (IllegalArgumentException alreadyRegistered) {
        // The same server instance already owns the exact context. Treat it as installed.
      }
      installedServer = server;
      plugin.getLogger().info("Official inventory snapshot endpoint enabled: " + HTTP_PATH);
    } catch (ReflectiveOperationException exception) {
      plugin.getLogger().warning(
          "Could not attach official inventory snapshot endpoint: " + exception.getMessage());
    }
  }

  private void handleCreateFromInventory(HttpExchange exchange) throws IOException {
    applyCors(exchange);
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, 405, "method_not_allowed", "Use POST");
      return;
    }

    try {
      JsonObject payload = readJson(exchange);
      AuthService authService = readPluginField("authService", AuthService.class);
      AdminService adminService = readPluginField("adminService", AdminService.class);
      ProductService productService = readPluginField("productService", ProductService.class);
      AdminAuditService auditService = readPluginField("adminAuditService", AdminAuditService.class);
      if (authService == null || adminService == null || productService == null) {
        throw new ServiceException("service_unavailable", "WebShopX services are not ready");
      }

      String token = readBearerToken(exchange);
      AuthService.AuthUser user = authService.findUserBySession(token)
          .orElseThrow(() -> new ServiceException("auth_required", "Admin session is required"));
      AdminService.AdminUser admin = adminService.requireAdmin(user, AdminPermission.PRODUCT_MANAGE);
      if (user.boundUuid() == null) {
        throw new ServiceException("not_bound", "Admin account is not bound to a Minecraft player");
      }

      int slot = requiredInt(payload, "slot");
      Integer containerSlot = nullableInt(payload, "containerSlot");
      String revision = requiredString(payload, "revision");
      String fingerprint = requiredString(payload, "fingerprint");
      InventoryService.InventorySource source = InventoryService.InventorySource.parse(
          optionalString(payload, "inventory", "PLAYER"));

      ItemStack sourceItem;
      try {
        sourceItem = schedulerBridge.supplyPlayer(user.boundUuid(), player ->
            inventoryService.resolve(
                inventoryFor(player, source),
                source,
                revision,
                slot,
                containerSlot,
                fingerprint))
            .get(5L, TimeUnit.SECONDS);
      } catch (Exception exception) {
        Throwable cause = rootCause(exception);
        if (cause instanceof ServiceException serviceException) {
          throw serviceException;
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
        if (message.contains("offline")) {
          throw new ServiceException(
              "player_offline", "V1 requires the admin player to be online while capturing an item");
        }
        throw new ServiceException("inventory_capture_failed", "Could not read the selected live item");
      }

      ItemStack unit = sourceItem.clone();
      unit.setAmount(1);
      ItemSnapshotCodec.Snapshot snapshot = itemCodec.serialize(unit);
      saveSnapshot(unit, snapshot);

      String hash = snapshot.itemHash();
      String sku = optionalString(payload, "sku", "").trim();
      if (sku.isEmpty()) {
        sku = "inv-" + hash.substring(0, 12);
      }
      String title = optionalString(payload, "title", "").trim();
      if (title.isEmpty()) {
        title = itemTitle(unit);
      }
      long price = requiredLong(payload, "price");
      boolean allowZeroPrice = admin.allows(AdminPermission.PRODUCT_ZERO_PRICE);
      CurrencyType currency = CurrencyType.fromConfig(optionalString(payload, "currency", "SHOP_COIN"));
      Integer itemAmount = nullablePositiveInt(payload, "itemAmount");
      Integer perUserLimit = nullablePositiveInt(payload, "perUserLimit");
      String remark = nullableString(payload, "remark");
      String displayName = nullableString(payload, "displayNameOverride");
      String displayMaterial = nullableString(payload, "displayMaterial");
      boolean active = !payload.has("active") || payload.get("active").getAsBoolean();

      String commandTemplate = INTERNAL_COMMAND
          + " " + INTERNAL_SUBCOMMAND
          + " {player} " + hash + " %amount% %order%";

      ProductService.AdminProductInput input = new ProductService.AdminProductInput(
          sku,
          title,
          remark,
          currency,
          price,
          "GIVE_CUSTOM_ITEM",
          commandTemplate,
          unit.getType().name(),
          displayName,
          displayMaterial,
          null,
          itemAmount,
          perUserLimit,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          active);

      ProductService.ProductView product = productService.upsertProduct(input, allowZeroPrice);
      JsonObject response = new JsonObject();
      response.addProperty("id", product.id());
      response.addProperty("sku", product.sku());
      response.addProperty("title", product.title());
      response.addProperty("productType", product.productType().name());
      response.addProperty("fulfillmentType", "ITEM_SNAPSHOT");
      response.addProperty("itemMaterial", unit.getType().name());
      response.addProperty("itemHash", hash);
      response.addProperty("itemMetaJson", snapshot.itemMetaJson());
      response.addProperty("sourceItemPreserved", true);
      response.addProperty("active", product.active());

      if (auditService != null) {
        JsonObject detail = new JsonObject();
        detail.addProperty("sku", product.sku());
        detail.addProperty("itemHash", hash);
        detail.addProperty("itemMaterial", unit.getType().name());
        detail.addProperty("inventory", source.name());
        detail.addProperty("slot", slot);
        detail.addProperty("sourceItemPreserved", true);
        auditService.log(
            admin,
            "PRODUCT_IMPORT_INVENTORY",
            "product",
            product.sku(),
            detail,
            clientIp(exchange));
      }
      sendJson(exchange, 200, response);
    } catch (ServiceException exception) {
      sendError(exchange, statusFor(exception.code()), exception.code(), exception.getMessage());
    } catch (Exception exception) {
      databaseManager.logFailure("Failed to create official product from inventory", exception);
      sendError(exchange, 500, "internal_error", "Could not create official inventory product");
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!INTERNAL_COMMAND.equalsIgnoreCase(command.getName())) {
      return false;
    }
    if (!(sender instanceof ConsoleCommandSender)) {
      sender.sendMessage("This command is reserved for WebShopX delivery.");
      return true;
    }
    if (args.length != 5 || !INTERNAL_SUBCOMMAND.equalsIgnoreCase(args[0])) {
      throw new CommandException("Invalid WebShopX snapshot delivery payload");
    }

    String playerName = args[1];
    String hash = args[2].toLowerCase();
    int amount;
    try {
      amount = Integer.parseInt(args[3]);
    } catch (NumberFormatException exception) {
      throw new CommandException("Invalid snapshot delivery quantity", exception);
    }
    String orderNo = args[4];
    if (!hash.matches("[0-9a-f]{64}") || amount <= 0 || amount > 100_000) {
      throw new CommandException("Invalid snapshot delivery arguments");
    }

    SnapshotRow snapshot = readSnapshot(hash);
    if (snapshot == null) {
      throw new CommandException("Official item snapshot is missing: " + hash);
    }
    OrderOwner owner = readOrderOwner(orderNo);
    if (owner == null) {
      throw new CommandException("Official order is missing: " + orderNo);
    }

    Player online = plugin.getServer().getPlayerExact(playerName);
    if (online == null || !online.isOnline()) {
      throw new CommandException("Snapshot delivery player is offline");
    }

    try {
      schedulerBridge.supplyPlayer(online.getUniqueId(), player -> {
        ItemStack item = itemCodec.deserialize(snapshot.itemBlob());
        item.setAmount(1);
        if (canFitEntirely(player.getInventory(), item, amount)) {
          addEntirely(player.getInventory(), item, amount);
        } else {
          mailboxService.enqueueItem(
              owner.userId(),
              player.getUniqueId(),
              item,
              amount,
              "ORDER",
              orderNo,
              "Official snapshot item moved to mailbox because inventory is full");
        }
        return null;
      }).get(5L, TimeUnit.SECONDS);
      return true;
    } catch (Exception exception) {
      Throwable cause = rootCause(exception);
      throw new CommandException(
          "Snapshot item delivery failed: "
              + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()),
          cause);
    }
  }

  private void saveSnapshot(ItemStack item, ItemSnapshotCodec.Snapshot snapshot) {
    databaseManager.withConnection(connection -> {
      String sql = databaseManager.dbType().isSqlite()
          ? "INSERT OR IGNORE INTO official_item_snapshots "
              + "(item_hash, item_blob, item_meta_json, item_material) VALUES (?, ?, ?, ?)"
          : "INSERT IGNORE INTO official_item_snapshots "
              + "(item_hash, item_blob, item_meta_json, item_material) VALUES (?, ?, ?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, snapshot.itemHash());
        statement.setBytes(2, snapshot.rawItemBlob());
        statement.setString(3, snapshot.itemMetaJson());
        statement.setString(4, item.getType().name());
        statement.executeUpdate();
      }
      return null;
    });
  }

  private SnapshotRow readSnapshot(String hash) {
    return databaseManager.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT item_blob, item_meta_json, item_material FROM official_item_snapshots WHERE item_hash = ?")) {
        statement.setString(1, hash);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            return null;
          }
          return new SnapshotRow(
              rows.getBytes("item_blob"),
              rows.getString("item_meta_json"),
              rows.getString("item_material"));
        }
      }
    });
  }

  private OrderOwner readOrderOwner(String orderNo) {
    return databaseManager.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT user_id, mc_uuid FROM orders WHERE order_no = ? LIMIT 1")) {
        statement.setString(1, orderNo);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            return null;
          }
          String uuid = rows.getString("mc_uuid");
          return new OrderOwner(
              rows.getLong("user_id"),
              uuid == null ? null : UUID.fromString(uuid));
        }
      }
    });
  }

  private boolean canFitEntirely(PlayerInventory inventory, ItemStack item, int amount) {
    int capacity = 0;
    int maxStack = Math.max(1, item.getMaxStackSize());
    for (ItemStack existing : inventory.getStorageContents()) {
      if (existing == null || existing.getType() == Material.AIR) {
        capacity += maxStack;
      } else if (existing.isSimilar(item)) {
        capacity += Math.max(0, Math.min(maxStack, existing.getMaxStackSize()) - existing.getAmount());
      }
      if (capacity >= amount) {
        return true;
      }
    }
    return capacity >= amount;
  }

  private void addEntirely(PlayerInventory inventory, ItemStack source, int amount) {
    int remaining = amount;
    int maxStack = Math.max(1, source.getMaxStackSize());
    while (remaining > 0) {
      int chunk = Math.min(maxStack, remaining);
      ItemStack stack = source.clone();
      stack.setAmount(chunk);
      Map<Integer, ItemStack> leftovers = inventory.addItem(stack);
      if (!leftovers.isEmpty()) {
        throw new IllegalStateException("Inventory capacity changed during snapshot delivery");
      }
      remaining -= chunk;
    }
  }

  private Inventory inventoryFor(Player player, InventoryService.InventorySource source) {
    return source == InventoryService.InventorySource.ENDER_CHEST
        ? player.getEnderChest()
        : player.getInventory();
  }

  private String itemTitle(ItemStack item) {
    ItemMeta meta = item.getItemMeta();
    if (meta != null && meta.hasDisplayName() && meta.getDisplayName() != null
        && !meta.getDisplayName().isBlank()) {
      return meta.getDisplayName();
    }
    return item.getType().name();
  }

  private JsonObject readJson(HttpExchange exchange) throws IOException {
    long declaredLength = -1L;
    String rawLength = exchange.getRequestHeaders().getFirst("Content-Length");
    if (rawLength != null) {
      try {
        declaredLength = Long.parseLong(rawLength);
      } catch (NumberFormatException ignored) {
        declaredLength = -1L;
      }
    }
    if (declaredLength > MAX_BODY_BYTES) {
      throw new ServiceException("payload_too_large", "Request body is too large");
    }
    try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
      JsonObject payload = JsonParser.parseReader(reader).getAsJsonObject();
      if (gson.toJson(payload).getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
        throw new ServiceException("payload_too_large", "Request body is too large");
      }
      return payload;
    } catch (IllegalStateException exception) {
      throw new ServiceException("bad_request", "Request body must be a JSON object");
    }
  }

  private String readBearerToken(HttpExchange exchange) {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new ServiceException("auth_required", "Missing Bearer session token");
    }
    String token = authorization.substring(7).trim();
    if (token.isEmpty()) {
      throw new ServiceException("auth_required", "Missing Bearer session token");
    }
    return token;
  }

  private void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
    byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
    applyCors(exchange);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private void sendError(HttpExchange exchange, int status, String code, String message)
      throws IOException {
    JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message == null ? code : message);
    sendJson(exchange, status, error);
  }

  private void applyCors(HttpExchange exchange) {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !origin.isBlank()) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
      exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
      exchange.getResponseHeaders().set("Vary", "Origin");
    }
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
    exchange.getResponseHeaders().set(
        "Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With");
  }

  private int statusFor(String code) {
    if ("auth_required".equals(code)) {
      return 401;
    }
    if ("forbidden".equals(code) || "not_admin".equals(code)) {
      return 403;
    }
    if ("inventory_changed".equals(code)) {
      return 409;
    }
    if ("service_unavailable".equals(code)) {
      return 503;
    }
    return 400;
  }

  private String clientIp(HttpExchange exchange) {
    return exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null
        ? null
        : exchange.getRemoteAddress().getAddress().getHostAddress();
  }

  private String requiredString(JsonObject payload, String key) {
    String value = nullableString(payload, key);
    if (value == null || value.isBlank()) {
      throw new ServiceException("bad_request", "Missing field: " + key);
    }
    return value.trim();
  }

  private String optionalString(JsonObject payload, String key, String fallback) {
    String value = nullableString(payload, key);
    return value == null ? fallback : value;
  }

  private String nullableString(JsonObject payload, String key) {
    if (!payload.has(key) || payload.get(key).isJsonNull()) {
      return null;
    }
    String value = payload.get(key).getAsString();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private int requiredInt(JsonObject payload, String key) {
    if (!payload.has(key) || payload.get(key).isJsonNull()) {
      throw new ServiceException("bad_request", "Missing field: " + key);
    }
    return payload.get(key).getAsInt();
  }

  private long requiredLong(JsonObject payload, String key) {
    if (!payload.has(key) || payload.get(key).isJsonNull()) {
      throw new ServiceException("bad_request", "Missing field: " + key);
    }
    return payload.get(key).getAsLong();
  }

  private Integer nullableInt(JsonObject payload, String key) {
    return !payload.has(key) || payload.get(key).isJsonNull() ? null : payload.get(key).getAsInt();
  }

  private Integer nullablePositiveInt(JsonObject payload, String key) {
    Integer value = nullableInt(payload, key);
    return value == null || value <= 0 ? null : value;
  }

  private Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private <T> T readPluginField(String fieldName, Class<T> type) {
    try {
      Field field = WebShopPlugin.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      Object value = field.get(plugin);
      return value == null ? null : type.cast(value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Could not access WebShopX service: " + fieldName, exception);
    }
  }

  private record SnapshotRow(byte[] itemBlob, String itemMetaJson, String itemMaterial) {
  }

  private record OrderOwner(long userId, UUID ownerUuid) {
  }
}
