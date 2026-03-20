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
  private final MessageService messageService;
  private final ItemSnapshotCodec itemSnapshotCodec;
  private final Map<UUID, Long> claimHintSentAt = new HashMap<>();

  DeliveryService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      WalletService walletService,
      Supplier<PluginSettings> settingsSupplier,
      MessageService messageService) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.walletService = walletService;
    this.settingsSupplier = settingsSupplier;
    this.messageService = messageService;
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

  void processPlayerJoin(Player player) {
    UUID playerUuid = player.getUniqueId();
    try {
      promoteOfflineRetries(playerUuid);
      processDueDeliveries(playerUuid);
      notifyClaimHint(player);
    } catch (Exception exception) {
      databaseManager.logFailure("Failed to process player join deliveries", exception);
    }
  }

  ClaimSummary claimPending(Player player, String token) {
    ClaimFilters filters = resolveClaimFilters(player, token);
    int success = 0;
    int failed = 0;

    if (filters.includeCommands()) {
      List<CommandDeliveryTask> commandTasks = databaseManager.withConnection(
          connection -> readClaimCommandTasks(connection, filters.commandOwnerUuid(), filters.orderNoFilter()));
      for (CommandDeliveryTask task : commandTasks) {
        if (handleCommandTask(task, true, player)) {
          success++;
        } else {
          failed++;
        }
      }
    }

    if (filters.includeMarket()) {
      List<MarketItemDeliveryTask> marketTasks = databaseManager.withConnection(
          connection -> readClaimMarketTasks(connection, filters.marketOwnerUuid(), filters.tradeIdFilter()));
      for (MarketItemDeliveryTask task : marketTasks) {
        if (handleMarketTask(task, true, player)) {
          success++;
        } else {
          failed++;
        }
      }
    }

    return new ClaimSummary(success, failed);
  }

  private ClaimFilters resolveClaimFilters(Player player, String rawToken) {
    UUID playerUuid = player.getUniqueId();
    if (rawToken == null || rawToken.isBlank() || rawToken.equalsIgnoreCase("all")) {
      return new ClaimFilters(playerUuid, null, true, playerUuid, null, true);
    }
    String normalized = rawToken.trim();
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (upper.startsWith("CLM-")) {
      OrderClaimTarget target = findOrderClaimTarget(upper);
      validateSharedClaim(playerUuid, target.ownerUuid());
      return new ClaimFilters(target.ownerUuid(), target.orderNo(), true, null, null, false);
    }
    if (upper.startsWith("MCL-")) {
      MarketClaimTarget target = findMarketClaimTarget(upper);
      validateSharedClaim(playerUuid, target.ownerUuid());
      return new ClaimFilters(null, null, false, target.ownerUuid(), target.tradeId(), true);
    }
    if (upper.startsWith("ODR-")) {
      return new ClaimFilters(playerUuid, upper, true, null, null, false);
    }
    if (upper.startsWith("MKT-")) {
      Long tradeId = parseTradeIdFilter(upper);
      if (tradeId == null) {
        throw new ServiceException("claim_token_invalid", "领取命令格式不正确，请重新复制。");
      }
      return new ClaimFilters(null, null, false, playerUuid, tradeId, true);
    }

    String orderNoFilter = normalizeOrderNoFilter(upper);
    if (orderNoFilter != null) {
      return new ClaimFilters(playerUuid, orderNoFilter, true, null, null, false);
    }
    Long tradeIdFilter = parseTradeIdFilter(upper);
    if (tradeIdFilter != null) {
      return new ClaimFilters(null, null, false, playerUuid, tradeIdFilter, true);
    }
    return new ClaimFilters(playerUuid, null, true, playerUuid, null, true);
  }

  private void validateSharedClaim(UUID requester, UUID owner) {
    if (requester.equals(owner)) {
      return;
    }
    if (settingsSupplier.get().allowSharedClaimCommand()) {
      return;
    }
    throw new ServiceException("claim_forbidden", "该领取命令仅限订单本人使用。");
  }

  private OrderClaimTarget findOrderClaimTarget(String token) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT id, order_no, mc_uuid
          FROM orders
          WHERE claim_token = ?
            AND status = 'WAIT_CLAIM'
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, token);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new ServiceException("claim_token_invalid", "领取命令已失效，请在订单界面重新复制。");
          }
          String uuidRaw = resultSet.getString("mc_uuid");
          return new OrderClaimTarget(
              resultSet.getLong("id"),
              resultSet.getString("order_no"),
              uuidRaw == null ? null : UUID.fromString(uuidRaw));
        }
      }
    });
  }

  private MarketClaimTarget findMarketClaimTarget(String token) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT mt.id, md.target_uuid
          FROM market_trades mt
          JOIN market_item_deliveries md ON md.trade_id = mt.id
          WHERE mt.claim_token = ?
            AND mt.status = 'WAIT_CLAIM'
            AND md.status = 'WAIT_CLAIM'
          ORDER BY md.id ASC
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, token);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new ServiceException("claim_token_invalid", "领取命令已失效，请在订单界面重新复制。");
          }
          String uuidRaw = resultSet.getString("target_uuid");
          UUID ownerUuid = uuidRaw == null ? null : UUID.fromString(uuidRaw);
          if (ownerUuid == null) {
            throw new ServiceException("claim_token_invalid", "领取命令暂不可用，请稍后重试。");
          }
          return new MarketClaimTarget(resultSet.getLong("id"), ownerUuid);
        }
      }
    });
  }

  void notifyClaimHint(Player player) {
    int pending = countPendingClaimTasks(player.getUniqueId());
    if (pending <= 0) {
      return;
    }
    sendWarnActionBar(player, msg(player, "chat.delivery.claim_hint", Map.of("count", pending)));
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
      sendWarnActionBar(player, msg(player, "chat.delivery.claim_hint", Map.of("count", pending)));
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

  private void promoteOfflineRetries(UUID playerUuid) {
    databaseManager.withConnection(connection -> {
      String commandSql = """
          UPDATE delivery_queue dq
          JOIN orders o ON o.id = dq.order_id
          SET dq.next_retry_at = NOW()
          WHERE dq.mc_uuid = ?
            AND dq.status = 'PENDING'
            AND o.status = 'PENDING'
            AND dq.last_error = '玩家离线'
            AND dq.next_retry_at > NOW()
          """;
      try (PreparedStatement statement = connection.prepareStatement(commandSql)) {
        statement.setString(1, playerUuid.toString());
        statement.executeUpdate();
      }

      String marketSql = """
          UPDATE market_item_deliveries
          SET next_retry_at = NOW()
          WHERE target_uuid = ?
            AND status = 'PENDING'
            AND last_error = '玩家离线'
            AND next_retry_at > NOW()
          """;
      try (PreparedStatement statement = connection.prepareStatement(marketSql)) {
        statement.setString(1, playerUuid.toString());
        statement.executeUpdate();
      }
      return null;
    });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Query template is built from constant fragments only")
  private List<CommandDeliveryTask> readClaimCommandTasks(
      Connection connection,
      UUID ownerUuid,
      String orderNoFilter) throws SQLException {
    if (ownerUuid == null) {
      return List.of();
    }
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
      statement.setString(parameterIndex++, ownerUuid.toString());
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
      UUID targetUuid,
      Long tradeIdFilter) throws SQLException {
    if (targetUuid == null && tradeIdFilter == null) {
      return List.of();
    }
    String targetFilter = targetUuid == null ? "" : "md.target_uuid = ? AND ";
    String filterByTrade = tradeIdFilter == null ? "" : " AND md.trade_id = ?";
    String sql = """
        SELECT md.id, md.listing_id, md.trade_id, md.target_user_id, md.target_uuid, md.item_blob, md.quantity,
               md.delivery_type, md.retry_count
        FROM market_item_deliveries md
        WHERE """ + targetFilter + """
          AND md.status = 'WAIT_CLAIM'
        """ + filterByTrade + " ORDER BY md.id ASC";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameterIndex = 1;
      if (targetUuid != null) {
        statement.setString(parameterIndex++, targetUuid.toString());
      }
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
          if (usesQuantityPlaceholder(task.commandText())) {
            String command = renderCommand(task.commandText(), player.getName(), task.quantity(), task.orderNo());
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (!success) {
              throw new IllegalStateException("Command execution returned false");
            }
          } else {
            for (int count = 0; count < Math.max(1, task.quantity()); count++) {
              String command = renderCommand(task.commandText(), player.getName(), 1, task.orderNo());
              boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
              if (!success) {
                throw new IllegalStateException("Command execution returned false");
              }
            }
          }
        }
        case GIVE_ITEM -> executeGiveItem(task, player);
        case POTION_EFFECT -> executePotion(task, player);
      }
      markCommandDelivered(task.orderId(), task.id(), claimMode);
      if (claimMode) {
        notifyDeliverySuccess(player, msg(player, "chat.delivery.claim_success_order",
            Map.of("orderNo", task.orderNo())));
      } else {
        notifyDeliverySuccess(player, msg(player, "chat.delivery.deliver_success_order",
            Map.of("orderNo", task.orderNo())));
      }
      return true;
    } catch (Exception exception) {
      String raw = exception.getMessage() == null ? msg(player, "chat.delivery.generic_failed") : exception.getMessage();
      String error = truncate(localizeDeliveryError(player, raw), 255);
      if (claimMode) {
        markCommandWaitClaim(task.orderId(), task.id(), error);
        sendWarnActionBar(player, msg(player, "chat.delivery.claim_failed", Map.of("reason", error)));
      } else if (task.retryCount() + 1 >= MAX_AUTO_RETRY_BEFORE_CLAIM) {
        markCommandWaitClaim(task.orderId(), task.id(), error);
        sendWarnActionBar(player, msg(player, "chat.delivery.auto_claim_hint",
            Map.of("token", task.orderNo())));
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
        notifyDeliverySuccess(player, msg(player, "chat.delivery.claim_success_market",
            Map.of("token", token)));
      } else {
        notifyDeliverySuccess(player, msg(player, "chat.delivery.deliver_success_market",
            Map.of("token", token)));
      }
      return true;
    } catch (Exception exception) {
      String raw = exception.getMessage() == null ? msg(player, "chat.delivery.generic_failed") : exception.getMessage();
      String error = truncate(localizeDeliveryError(player, raw), 255);
      if (claimMode) {
        markMarketWaitClaim(task, error);
        sendWarnActionBar(player, msg(player, "chat.delivery.claim_failed", Map.of("reason", error)));
      } else if (task.retryCount() + 1 >= MAX_AUTO_RETRY_BEFORE_CLAIM) {
        markMarketWaitClaim(task, error);
        String token = task.tradeId() == null ? "#" + task.listingId() : "MKT-" + task.tradeId();
        sendWarnActionBar(player, msg(player, "chat.delivery.auto_claim_hint", Map.of("token", token)));
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
          SET status = 'DELIVERED', delivered_at = NOW(), claim_token = NULL
          WHERE id = ?
            AND status IN ('PENDING', 'WAIT_CLAIM')
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
    if (!"PENDING".equalsIgnoreCase(trade.status()) && !"WAIT_CLAIM".equalsIgnoreCase(trade.status())) {
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
        SET status = 'DELIVERED', settled_at = NOW(), claim_token = NULL
        WHERE id = ? AND status IN ('PENDING', 'WAIT_CLAIM')
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

  private void markCommandWaitClaim(long orderId, long deliveryId, String errorMessage) {
    databaseManager.withConnection(connection -> {
      ClaimTokenRepository.ensureOrderToken(connection, orderId);
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
      String updateOrderSql = """
          UPDATE orders
          SET status = 'WAIT_CLAIM'
          WHERE id = ?
            AND status IN ('PENDING', 'WAIT_CLAIM')
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateOrderSql)) {
        statement.setLong(1, orderId);
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

  private void markMarketWaitClaim(MarketItemDeliveryTask task, String errorMessage) {
    databaseManager.withConnection(connection -> {
      if (task.tradeId() != null) {
        ClaimTokenRepository.ensureMarketTradeToken(connection, task.tradeId());
        String updateTradeSql = """
            UPDATE market_trades
            SET status = 'WAIT_CLAIM'
            WHERE id = ?
              AND status IN ('PENDING', 'WAIT_CLAIM')
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateTradeSql)) {
          statement.setLong(1, task.tradeId());
          statement.executeUpdate();
        }
      }
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
        statement.setLong(2, task.id());
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

  private boolean usesQuantityPlaceholder(String template) {
    if (template == null || template.isBlank()) {
      return false;
    }
    String normalized = template.toLowerCase(java.util.Locale.ROOT);
    return normalized.contains("%amount%")
        || normalized.contains("{amount}")
        || normalized.contains("%quantity%")
        || normalized.contains("{quantity}");
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

  private String localizeDeliveryError(Player player, String message) {
    if (message == null || message.isBlank()) {
      return msg(player, "chat.delivery.generic_failed");
    }
    String raw = message.trim();
    String normalized = message.toLowerCase(Locale.ROOT);
    if (normalized.contains("inventory is full") || raw.contains("背包已满")) {
      return msg(player, "chat.delivery.inventory_full");
    }
    if (normalized.contains("player is offline") || raw.contains("玩家离线")) {
      return msg(player, "chat.delivery.player_offline");
    }
    if (normalized.contains("item snapshot is empty") || raw.contains("物品快照为空")) {
      return msg(player, "chat.delivery.empty_snapshot");
    }
    if (normalized.contains("invalid item material") || raw.contains("物品材质无效")) {
      return msg(player, "chat.delivery.invalid_material");
    }
    if (normalized.contains("invalid potion effect") || raw.contains("药水效果无效")) {
      return msg(player, "chat.delivery.invalid_potion");
    }
    return message;
  }

  private String msg(Player player, String key) {
    return messageService.get(player, key);
  }

  private String msg(Player player, String key, Map<String, ?> params) {
    return messageService.format(player, key, params);
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

  private record ClaimFilters(
      UUID commandOwnerUuid,
      String orderNoFilter,
      boolean includeCommands,
      UUID marketOwnerUuid,
      Long tradeIdFilter,
      boolean includeMarket) {
  }

  private record OrderClaimTarget(long orderId, String orderNo, UUID ownerUuid) {
  }

  private record MarketClaimTarget(long tradeId, UUID ownerUuid) {
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

