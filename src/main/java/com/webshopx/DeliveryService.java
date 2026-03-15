package com.webshopx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

class DeliveryService {
  private static final int MAX_AUTO_RETRY_BEFORE_CLAIM = 3;
  private static final long CLAIM_HINT_INTERVAL_MS = 30_000L;

  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final WalletService walletService;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ItemSnapshotCodec itemSnapshotCodec;
  private final Map<UUID, Long> claimHintSentAt = new HashMap<>();

  DeliveryService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      WalletService walletService,
      Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.walletService = walletService;
    this.settingsSupplier = settingsSupplier;
    this.itemSnapshotCodec = new ItemSnapshotCodec();
  }

  void processDueDeliveries(UUID playerUuid) {
    try {
      processCommandDeliveries(playerUuid);
      processMarketItemDeliveries(playerUuid);
      if (playerUuid == null) {
        notifyClaimHintsForOnlinePlayers();
      }
    } catch (Exception exception) {
      databaseManager.logFailure("Failed to process delivery queue", exception);
    }
  }

  ClaimSummary claimPending(Player player, String token) {
    UUID playerUuid = player.getUniqueId();
    String orderNoFilter = normalizeOrderNoFilter(token);
    Long tradeIdFilter = parseTradeIdFilter(token);
    int success = 0;
    int failed = 0;

    List<CommandDeliveryTask> commandTasks = databaseManager.withConnection(
        connection -> readClaimCommandTasks(connection, playerUuid, orderNoFilter));
    for (CommandDeliveryTask task : commandTasks) {
      if (handleCommandTask(task, true, player)) {
        success++;
      } else {
        failed++;
      }
    }

    List<MarketItemDeliveryTask> marketTasks = databaseManager.withConnection(
        connection -> readClaimMarketTasks(connection, playerUuid, tradeIdFilter));
    for (MarketItemDeliveryTask task : marketTasks) {
      if (handleMarketTask(task, true, player)) {
        success++;
      } else {
        failed++;
      }
    }

    return new ClaimSummary(success, failed);
  }

  void notifyClaimHint(Player player) {
    int pending = countPendingClaimTasks(player.getUniqueId());
    if (pending <= 0) {
      return;
    }
    sendWarnActionBar(player, "你有 " + pending + " 条待领取发货，请输入 /ws claim");
    claimHintSentAt.put(player.getUniqueId(), System.currentTimeMillis());
  }

  private void notifyClaimHintsForOnlinePlayers() {
    long now = System.currentTimeMillis();
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID uuid = player.getUniqueId();
      long last = claimHintSentAt.getOrDefault(uuid, 0L);
      if (now - last < CLAIM_HINT_INTERVAL_MS) {
        continue;
      }
      int pending = countPendingClaimTasks(uuid);
      if (pending <= 0) {
        claimHintSentAt.remove(uuid);
        continue;
      }
      sendWarnActionBar(player, "你有 " + pending + " 条待领取发货，请输入 /ws claim");
      claimHintSentAt.put(uuid, now);
    }
  }

  private void processCommandDeliveries(UUID playerUuid) {
    List<CommandDeliveryTask> tasks = databaseManager.withConnection(
        connection -> readDueCommandTasks(connection, playerUuid));
    for (CommandDeliveryTask task : tasks) {
      handleCommandTask(task, false, null);
    }
  }

  private void processMarketItemDeliveries(UUID playerUuid) {
    List<MarketItemDeliveryTask> tasks = databaseManager.withConnection(
        connection -> readDueMarketItemTasks(connection, playerUuid));
    for (MarketItemDeliveryTask task : tasks) {
      handleMarketTask(task, false, null);
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Query template is built from constant fragments only")
  private List<CommandDeliveryTask> readClaimCommandTasks(
      Connection connection,
      UUID playerUuid,
      String orderNoFilter) throws SQLException {
    String filterByOrder = orderNoFilter == null ? "" : " AND o.order_no = ?";
    String sql = """
        SELECT dq.id, dq.order_id, dq.item_id, dq.mc_uuid, dq.command_text,
               dq.delivery_kind, dq.payload_json, dq.quantity, dq.retry_count,
               o.order_no
        FROM delivery_queue dq
        JOIN orders o ON o.id = dq.order_id
        WHERE dq.mc_uuid = ?
          AND dq.status = 'WAIT_CLAIM'
        """ + filterByOrder + " ORDER BY dq.id ASC";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameterIndex = 1;
      statement.setString(parameterIndex++, playerUuid.toString());
      if (orderNoFilter != null) {
        statement.setString(parameterIndex, orderNoFilter);
      }
      return readCommandTasks(statement.executeQuery());
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Query template is built from constant fragments only")
  private List<MarketItemDeliveryTask> readClaimMarketTasks(
      Connection connection,
      UUID playerUuid,
      Long tradeIdFilter) throws SQLException {
    String filterByTrade = tradeIdFilter == null ? "" : " AND md.trade_id = ?";
    String sql = """
        SELECT md.id, md.listing_id, md.trade_id, md.target_user_id, md.target_uuid, md.item_blob, md.quantity,
               md.delivery_type, md.retry_count
        FROM market_item_deliveries md
        WHERE md.target_uuid = ?
          AND md.status = 'WAIT_CLAIM'
        """ + filterByTrade + " ORDER BY md.id ASC";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameterIndex = 1;
      statement.setString(parameterIndex++, playerUuid.toString());
      if (tradeIdFilter != null) {
        statement.setLong(parameterIndex, tradeIdFilter);
      }
      return readMarketTasks(statement.executeQuery());
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Query template is built from constant fragments only")
  private List<CommandDeliveryTask> readDueCommandTasks(Connection connection, UUID playerUuid)
      throws SQLException {
    String filterByPlayer = playerUuid == null ? "" : " AND dq.mc_uuid = ?";
    String sql = """
        SELECT dq.id, dq.order_id, dq.item_id, dq.mc_uuid, dq.command_text,
               dq.delivery_kind, dq.payload_json, dq.quantity, dq.retry_count,
               o.order_no
        FROM delivery_queue dq
        JOIN orders o ON o.id = dq.order_id
        WHERE dq.status = 'PENDING'
          AND o.status = 'PENDING'
          AND dq.next_retry_at <= NOW()
        """ + filterByPlayer + " ORDER BY dq.id ASC LIMIT ?";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameterIndex = 1;
      if (playerUuid != null) {
        statement.setString(parameterIndex++, playerUuid.toString());
      }
      statement.setInt(parameterIndex, Math.max(1, settingsSupplier.get().deliveryBatchSize()));

      return readCommandTasks(statement.executeQuery());
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Query template is built from constant fragments only")
  private List<MarketItemDeliveryTask> readDueMarketItemTasks(Connection connection, UUID playerUuid)
      throws SQLException {
    String filterByPlayer = playerUuid == null ? "" : " AND md.target_uuid = ?";
    String sql = """
        SELECT md.id, md.listing_id, md.trade_id, md.target_user_id, md.target_uuid, md.item_blob, md.quantity,
               md.delivery_type, md.retry_count
        FROM market_item_deliveries md
        WHERE md.status = 'PENDING'
          AND md.next_retry_at <= NOW()
        """ + filterByPlayer + " ORDER BY md.id ASC LIMIT ?";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameterIndex = 1;
      if (playerUuid != null) {
        statement.setString(parameterIndex++, playerUuid.toString());
      }
      statement.setInt(parameterIndex, Math.max(1, settingsSupplier.get().deliveryBatchSize()));

      return readMarketTasks(statement.executeQuery());
    }
  }

  private boolean handleCommandTask(CommandDeliveryTask task, boolean claimMode, Player forcedPlayer) {
    Player player = forcedPlayer == null ? Bukkit.getPlayer(task.playerUuid()) : forcedPlayer;
    if (player == null || !player.isOnline()) {
      if (!claimMode) {
        rescheduleCommand(task.id(), "玩家离线", false);
      }
      return false;
    }

    try {
      DeliveryKind kind = DeliveryKind.fromRaw(task.deliveryKind());
      switch (kind) {
        case COMMAND -> {
          String command = renderCommand(task.commandText(), player.getName(), task.quantity(), task.orderNo());
          boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
          if (!success) {
            throw new IllegalStateException("Command execution returned false");
          }
        }
        case GIVE_ITEM -> executeGiveItem(task, player);
        case POTION_EFFECT -> executePotion(task, player);
      }
      markCommandDelivered(task.orderId(), task.id(), claimMode);
      if (claimMode) {
        notifyDeliverySuccess(player, "领取成功：" + task.orderNo());
      } else {
        notifyDeliverySuccess(player, "发货成功：" + task.orderNo());
      }
      return true;
    } catch (Exception exception) {
      String raw = exception.getMessage() == null ? "发货失败" : exception.getMessage();
      String error = truncate(localizeDeliveryError(raw), 255);
      if (claimMode) {
        markCommandWaitClaim(task.id(), error);
        sendWarnActionBar(player, "领取失败：" + error);
      } else if (task.retryCount() + 1 >= MAX_AUTO_RETRY_BEFORE_CLAIM) {
        markCommandWaitClaim(task.id(), error);
        sendWarnActionBar(player, "自动发货失败，已转手动领取：/ws claim " + task.orderNo());
      } else {
        rescheduleCommand(task.id(), error, true);
      }
      return false;
    }
  }

  private boolean handleMarketTask(MarketItemDeliveryTask task, boolean claimMode, Player forcedPlayer) {
    Player player = forcedPlayer == null ? Bukkit.getPlayer(task.targetUuid()) : forcedPlayer;
    if (player == null || !player.isOnline()) {
      if (!claimMode) {
        rescheduleMarket(task.id(), "玩家离线", false);
      }
      return false;
    }

    try {
      ItemStack itemStack = itemSnapshotCodec.deserialize(task.itemBlob());
      if (itemStack.getType() == Material.AIR) {
        throw new IllegalStateException("物品快照为空");
      }

      addItemToInventory(player, itemStack, task.quantity());

      markMarketDelivered(task, claimMode);
      String token = task.tradeId() == null ? "#" + task.listingId() : "MKT-" + task.tradeId();
      if (claimMode) {
        notifyDeliverySuccess(player, "领取成功：" + token);
      } else {
        notifyDeliverySuccess(player, "发货成功：" + token);
      }
      return true;
    } catch (Exception exception) {
      String raw = exception.getMessage() == null ? "发货失败" : exception.getMessage();
      String error = truncate(localizeDeliveryError(raw), 255);
      if (claimMode) {
        markMarketWaitClaim(task.id(), error);
        sendWarnActionBar(player, "领取失败：" + error);
      } else if (task.retryCount() + 1 >= MAX_AUTO_RETRY_BEFORE_CLAIM) {
        markMarketWaitClaim(task.id(), error);
        String token = task.tradeId() == null ? "#" + task.listingId() : "MKT-" + task.tradeId();
        sendWarnActionBar(player, "自动发货失败，已转手动领取：/ws claim " + token);
      } else {
        rescheduleMarket(task.id(), error, true);
      }
      return false;
    }
  }

  private void markCommandDelivered(long orderId, long deliveryId, boolean claimMode) {
    databaseManager.inTransaction(connection -> {
      String updateDeliverySql = """
          UPDATE delivery_queue
          SET status = 'DELIVERED',
              delivered_at = NOW(),
              claimed_at = CASE WHEN ? THEN NOW() ELSE claimed_at END,
              last_error = NULL
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateDeliverySql)) {
        statement.setBoolean(1, claimMode);
        statement.setLong(2, deliveryId);
        statement.executeUpdate();
      }

      String updateOrderSql = """
          UPDATE orders
          SET status = 'DELIVERED', delivered_at = NOW()
          WHERE id = ?
            AND status = 'PENDING'
            AND NOT EXISTS (
              SELECT 1
              FROM delivery_queue dq
              WHERE dq.order_id = ?
                AND dq.status <> 'DELIVERED'
            )
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateOrderSql)) {
        statement.setLong(1, orderId);
        statement.setLong(2, orderId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private void markMarketDelivered(MarketItemDeliveryTask task, boolean claimMode) {
    databaseManager.inTransaction(connection -> {
      String updateDeliverySql = """
          UPDATE market_item_deliveries
          SET status = 'DELIVERED',
              delivered_at = NOW(),
              claimed_at = CASE WHEN ? THEN NOW() ELSE claimed_at END,
              last_error = NULL
          WHERE id = ?
            AND status IN ('PENDING', 'WAIT_CLAIM')
          """;
      int changed;
      try (PreparedStatement statement = connection.prepareStatement(updateDeliverySql)) {
        statement.setBoolean(1, claimMode);
        statement.setLong(2, task.id());
        changed = statement.executeUpdate();
      }
      if (changed <= 0) {
        return null;
      }
      if ("SALE".equalsIgnoreCase(task.deliveryType())) {
        settleMarketTrade(connection, task.tradeId(), task.listingId());
      }
      return null;
    });
  }

  private void settleMarketTrade(Connection connection, Long tradeId, long listingId) throws SQLException {
    MarketTradeSettlement trade = readTradeForSettlement(connection, tradeId, listingId);
    if (trade == null) {
      return;
    }
    if (!"PENDING".equalsIgnoreCase(trade.status())) {
      return;
    }
    CurrencyType currency = CurrencyType.valueOf(trade.currency());
    String sellerBizId = "mkt-sell:" + trade.tradeId();
    if (trade.sellerReceive() > 0) {
      walletService.applyDelta(
          connection,
          trade.sellerUserId(),
          currency,
          trade.sellerReceive(),
          "MARKET_SELL",
          sellerBizId,
          false);
    }

    long sinkAmount = trade.feeAmount() + trade.taxAmount();
    if (sinkAmount > 0) {
      applyEconomySink(connection, currency, sinkAmount, trade.tradeId());
    }

    String settleSql = """
        UPDATE market_trades
        SET status = 'DELIVERED', settled_at = NOW()
        WHERE id = ? AND status = 'PENDING'
        """;
    try (PreparedStatement statement = connection.prepareStatement(settleSql)) {
      statement.setLong(1, trade.tradeId());
      statement.executeUpdate();
    }
  }

  private MarketTradeSettlement readTradeForSettlement(Connection connection, Long tradeId, long listingId)
      throws SQLException {
    String sql;
    if (tradeId != null) {
      sql = """
          SELECT id, seller_user_id, currency, seller_receive, fee_amount, tax_amount, status
          FROM market_trades
          WHERE id = ?
          FOR UPDATE
          """;
    } else {
      sql = """
          SELECT id, seller_user_id, currency, seller_receive, fee_amount, tax_amount, status
          FROM market_trades
          WHERE listing_id = ?
          ORDER BY id DESC
          LIMIT 1
          FOR UPDATE
          """;
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, tradeId == null ? listingId : tradeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return new MarketTradeSettlement(
            resultSet.getLong("id"),
            resultSet.getLong("seller_user_id"),
            resultSet.getString("currency"),
            resultSet.getLong("seller_receive"),
            resultSet.getLong("fee_amount"),
            resultSet.getLong("tax_amount"),
            resultSet.getString("status"));
      }
    }
  }

  private void rescheduleCommand(long deliveryId, String errorMessage, boolean countFailure) {
    databaseManager.withConnection(connection -> {
      int delaySeconds = Math.max(5, settingsSupplier.get().deliveryRetrySeconds());
      LocalDateTime retryAt = LocalDateTime.now().plusSeconds(delaySeconds);

      String sql = """
          UPDATE delivery_queue
          SET retry_count = retry_count + ?,
              last_error = ?,
              next_retry_at = ?,
              status = 'PENDING'
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, countFailure ? 1 : 0);
        statement.setString(2, truncate(errorMessage, 255));
        statement.setTimestamp(3, Timestamp.valueOf(retryAt));
        statement.setLong(4, deliveryId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private void markCommandWaitClaim(long deliveryId, String errorMessage) {
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE delivery_queue
          SET status = 'WAIT_CLAIM',
              retry_count = retry_count + 1,
              last_error = ?,
              next_retry_at = NOW()
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, truncate(errorMessage, 255));
        statement.setLong(2, deliveryId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private void rescheduleMarket(long deliveryId, String errorMessage, boolean countFailure) {
    databaseManager.withConnection(connection -> {
      int delaySeconds = Math.max(5, settingsSupplier.get().deliveryRetrySeconds());
      LocalDateTime retryAt = LocalDateTime.now().plusSeconds(delaySeconds);
      String sql = """
          UPDATE market_item_deliveries
          SET retry_count = retry_count + ?,
              last_error = ?,
              next_retry_at = ?,
              status = 'PENDING'
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, countFailure ? 1 : 0);
        statement.setString(2, truncate(errorMessage, 255));
        statement.setTimestamp(3, Timestamp.valueOf(retryAt));
        statement.setLong(4, deliveryId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private void markMarketWaitClaim(long deliveryId, String errorMessage) {
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE market_item_deliveries
          SET status = 'WAIT_CLAIM',
              retry_count = retry_count + 1,
              last_error = ?,
              next_retry_at = NOW()
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, truncate(errorMessage, 255));
        statement.setLong(2, deliveryId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private String renderCommand(String template, String playerName, int quantity, String orderNo) {
    String rendered = template
        .replace("{player}", playerName)
        .replace("%player%", playerName)
        .replace("%amount%", Integer.toString(quantity))
        .replace("%order%", orderNo);
    // Console command should not start with leading slash.
    if (rendered.startsWith("/")) {
      rendered = rendered.substring(1);
    }
    return rendered.trim();
  }

  private void executeGiveItem(CommandDeliveryTask task, Player player) {
    JsonObject payload = parsePayload(task.payloadJson());
    String materialRaw = payload.has("material") ? payload.get("material").getAsString() : "";
    int amount = payload.has("amount") ? payload.get("amount").getAsInt() : task.quantity();
    Material material = resolveMaterial(materialRaw);
    if (material == null || material == Material.AIR) {
      throw new ServiceException("invalid_delivery_payload", "物品材质无效");
    }
    addItemToInventory(player, new ItemStack(material, 1), amount);
  }

  private void executePotion(CommandDeliveryTask task, Player player) {
    JsonObject payload = parsePayload(task.payloadJson());
    String effectRaw = payload.has("effect") ? payload.get("effect").getAsString() : "";
    int seconds = payload.has("seconds") ? payload.get("seconds").getAsInt() : 30;
    int amplifier = payload.has("amplifier") ? payload.get("amplifier").getAsInt() : 0;

    PotionEffectType effectType = resolvePotionEffectType(effectRaw);
    if (effectType == null) {
      throw new ServiceException("invalid_delivery_payload", "药水效果无效");
    }
    int durationTicks = Math.max(20, seconds * 20);
    player.addPotionEffect(new PotionEffect(effectType, durationTicks, Math.max(0, amplifier)), true);
  }

  private void addItemToInventory(Player player, ItemStack source, int totalAmount) {
    if (source == null || source.getType() == Material.AIR) {
      throw new IllegalStateException("待发放物品为空");
    }
    int remaining = Math.max(1, totalAmount);
    int maxStack = Math.max(1, source.getMaxStackSize());
    while (remaining > 0) {
      int chunk = Math.min(maxStack, remaining);
      ItemStack stack = source.clone();
      stack.setAmount(chunk);
      Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
      if (!leftovers.isEmpty()) {
        throw new IllegalStateException("背包已满");
      }
      remaining -= chunk;
    }
  }

  private Material resolveMaterial(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim();
    Material material = Material.matchMaterial(normalized);
    if (material != null) {
      return material;
    }
    String key = normalized.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
    material = Material.matchMaterial(key);
    if (material != null) {
      return material;
    }
    if (key.startsWith("BLOCK_OF_") && key.length() > "BLOCK_OF_".length()) {
      material = Material.matchMaterial(key.substring("BLOCK_OF_".length()) + "_BLOCK");
      if (material != null) {
        return material;
      }
    }
    return Material.matchMaterial(normalized.toLowerCase(Locale.ROOT));
  }

  private void notifyDeliverySuccess(Player player, String text) {
    player.sendActionBar(Component.text(text, NamedTextColor.GREEN));
    player.sendMessage(Component.text(text, NamedTextColor.GREEN));
  }

  private void sendWarnActionBar(Player player, String text) {
    player.sendActionBar(Component.text(text, NamedTextColor.YELLOW));
  }

  private String localizeDeliveryError(String message) {
    if (message == null || message.isBlank()) {
      return "发货失败";
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    if (normalized.contains("inventory is full")) {
      return "背包已满";
    }
    if (normalized.contains("player is offline")) {
      return "玩家离线";
    }
    if (normalized.contains("item snapshot is empty")) {
      return "物品快照为空";
    }
    if (normalized.contains("invalid item material")) {
      return "物品材质无效";
    }
    if (normalized.contains("invalid potion effect")) {
      return "药水效果无效";
    }
    return message;
  }

  private PotionEffectType resolvePotionEffectType(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    NamespacedKey key = normalized.contains(":")
        ? NamespacedKey.fromString(normalized)
        : NamespacedKey.minecraft(normalized);
    if (key == null) {
      return null;
    }
    return PotionEffectType.getByKey(key);
  }

  private JsonObject parsePayload(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return new JsonObject();
    }
    try {
      return JsonParser.parseString(payloadJson).getAsJsonObject();
    } catch (Exception exception) {
      throw new ServiceException("invalid_delivery_payload", "Payload json is invalid");
    }
  }

  private String normalizeOrderNoFilter(String token) {
    if (token == null || token.isBlank() || token.equalsIgnoreCase("all")) {
      return null;
    }
    String normalized = token.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("ODR-")) {
      return normalized;
    }
    return null;
  }

  private Long parseTradeIdFilter(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    String normalized = token.trim().toUpperCase(Locale.ROOT);
    if (!normalized.startsWith("MKT-")) {
      return null;
    }
    String rawId = normalized.substring(4);
    try {
      long tradeId = Long.parseLong(rawId);
      return tradeId > 0 ? tradeId : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private int countPendingClaimTasks(UUID playerUuid) {
    return databaseManager.withConnection(connection -> {
      String commandSql = """
          SELECT COUNT(*) AS cnt
          FROM delivery_queue
          WHERE mc_uuid = ?
            AND status = 'WAIT_CLAIM'
          """;
      int commandCount;
      try (PreparedStatement statement = connection.prepareStatement(commandSql)) {
        statement.setString(1, playerUuid.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          commandCount = resultSet.next() ? resultSet.getInt("cnt") : 0;
        }
      }

      String marketSql = """
          SELECT COUNT(*) AS cnt
          FROM market_item_deliveries
          WHERE target_uuid = ?
            AND status = 'WAIT_CLAIM'
          """;
      int marketCount;
      try (PreparedStatement statement = connection.prepareStatement(marketSql)) {
        statement.setString(1, playerUuid.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          marketCount = resultSet.next() ? resultSet.getInt("cnt") : 0;
        }
      }
      return commandCount + marketCount;
    });
  }

  private List<CommandDeliveryTask> readCommandTasks(ResultSet resultSet) throws SQLException {
    List<CommandDeliveryTask> tasks = new ArrayList<>();
    while (resultSet.next()) {
      tasks.add(new CommandDeliveryTask(
          resultSet.getLong("id"),
          resultSet.getLong("order_id"),
          resultSet.getLong("item_id"),
          UUID.fromString(resultSet.getString("mc_uuid")),
          resultSet.getString("command_text"),
          resultSet.getString("delivery_kind"),
          resultSet.getString("payload_json"),
          resultSet.getInt("quantity"),
          resultSet.getInt("retry_count"),
          resultSet.getString("order_no")));
    }
    return tasks;
  }

  private List<MarketItemDeliveryTask> readMarketTasks(ResultSet resultSet) throws SQLException {
    List<MarketItemDeliveryTask> tasks = new ArrayList<>();
    while (resultSet.next()) {
      tasks.add(new MarketItemDeliveryTask(
          resultSet.getLong("id"),
          resultSet.getLong("listing_id"),
          (Long) resultSet.getObject("trade_id"),
          resultSet.getLong("target_user_id"),
          UUID.fromString(resultSet.getString("target_uuid")),
          resultSet.getBytes("item_blob"),
          resultSet.getInt("quantity"),
          resultSet.getString("delivery_type"),
          resultSet.getInt("retry_count")));
    }
    return tasks;
  }

  private String truncate(String text, int maxLength) {
    if (text == null || text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength);
  }

  private void applyEconomySink(Connection connection, CurrencyType currency, long amount, long tradeId)
      throws SQLException {
    PluginSettings.InflationSettings inflation = settingsSupplier.get().economySettings().inflationSettings();
    long treasuryUserId = inflation.treasuryUserId();
    if (inflation.mode() != PluginSettings.InflationMode.TREASURY || treasuryUserId <= 0) {
      return;
    }
    if (!userExists(connection, treasuryUserId)) {
      return;
    }
    String sinkBizId = "mkt-sink:" + tradeId;
    walletService.applyDelta(
        connection,
        treasuryUserId,
        currency,
        amount,
        "MARKET_SINK",
        sinkBizId,
        false);
  }

  private boolean userExists(Connection connection, long userId) throws SQLException {
    String sql = "SELECT 1 FROM web_users WHERE id = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private record CommandDeliveryTask(
      long id,
      long orderId,
      long itemId,
      UUID playerUuid,
      String commandText,
      String deliveryKind,
      String payloadJson,
      int quantity,
      int retryCount,
      String orderNo) {
  }

  private record MarketItemDeliveryTask(
      long id,
      long listingId,
      Long tradeId,
      long targetUserId,
      UUID targetUuid,
      byte[] itemBlob,
      int quantity,
      String deliveryType,
      int retryCount) {
  }

  enum DeliveryKind {
    COMMAND,
    GIVE_ITEM,
    POTION_EFFECT;

    static DeliveryKind fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return COMMAND;
      }
      try {
        return DeliveryKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        throw new ServiceException("invalid_delivery_kind", "Delivery kind is invalid");
      }
    }
  }

  record ClaimSummary(int success, int failed) {
  }

  private record MarketTradeSettlement(
      long tradeId,
      long sellerUserId,
      String currency,
      long sellerReceive,
      long feeAmount,
      long taxAmount,
      String status) {
  }
}

