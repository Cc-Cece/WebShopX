package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

class DeliveryService {
  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ItemSnapshotCodec itemSnapshotCodec;

  DeliveryService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
    this.itemSnapshotCodec = new ItemSnapshotCodec();
  }

  void processDueDeliveries(UUID playerUuid) {
    try {
      processCommandDeliveries(playerUuid);
      processMarketItemDeliveries(playerUuid);
    } catch (Exception exception) {
      databaseManager.logFailure("Failed to process delivery queue", exception);
    }
  }

  private void processCommandDeliveries(UUID playerUuid) {
    List<CommandDeliveryTask> tasks = databaseManager.withConnection(
        connection -> readDueCommandTasks(connection, playerUuid));
    for (CommandDeliveryTask task : tasks) {
      handleCommandTask(task);
    }
  }

  private void processMarketItemDeliveries(UUID playerUuid) {
    List<MarketItemDeliveryTask> tasks = databaseManager.withConnection(
        connection -> readDueMarketItemTasks(connection, playerUuid));
    for (MarketItemDeliveryTask task : tasks) {
      handleMarketTask(task);
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Query template is built from constant fragments only")
  private List<CommandDeliveryTask> readDueCommandTasks(Connection connection, UUID playerUuid)
      throws SQLException {
    String filterByPlayer = playerUuid == null ? "" : " AND dq.mc_uuid = ?";
    String sql = """
        SELECT dq.id, dq.order_id, dq.item_id, dq.mc_uuid, dq.command_text, dq.quantity, dq.retry_count,
               o.order_no
        FROM delivery_queue dq
        JOIN orders o ON o.id = dq.order_id
        WHERE dq.status = 'PENDING'
          AND dq.next_retry_at <= NOW()
        """ + filterByPlayer + " ORDER BY dq.id ASC LIMIT ?";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameterIndex = 1;
      if (playerUuid != null) {
        statement.setString(parameterIndex++, playerUuid.toString());
      }
      statement.setInt(parameterIndex, Math.max(1, settingsSupplier.get().deliveryBatchSize()));

      List<CommandDeliveryTask> tasks = new ArrayList<>();
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          tasks.add(new CommandDeliveryTask(
              resultSet.getLong("id"),
              resultSet.getLong("order_id"),
              resultSet.getLong("item_id"),
              UUID.fromString(resultSet.getString("mc_uuid")),
              resultSet.getString("command_text"),
              resultSet.getInt("quantity"),
              resultSet.getInt("retry_count"),
              resultSet.getString("order_no")));
        }
      }
      return tasks;
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Query template is built from constant fragments only")
  private List<MarketItemDeliveryTask> readDueMarketItemTasks(Connection connection, UUID playerUuid)
      throws SQLException {
    String filterByPlayer = playerUuid == null ? "" : " AND md.target_uuid = ?";
    String sql = """
        SELECT md.id, md.listing_id, md.target_user_id, md.target_uuid, md.item_blob, md.quantity,
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

      List<MarketItemDeliveryTask> tasks = new ArrayList<>();
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          tasks.add(new MarketItemDeliveryTask(
              resultSet.getLong("id"),
              resultSet.getLong("listing_id"),
              resultSet.getLong("target_user_id"),
              UUID.fromString(resultSet.getString("target_uuid")),
              resultSet.getBytes("item_blob"),
              resultSet.getInt("quantity"),
              resultSet.getString("delivery_type"),
              resultSet.getInt("retry_count")));
        }
      }
      return tasks;
    }
  }

  private void handleCommandTask(CommandDeliveryTask task) {
    Player player = Bukkit.getPlayer(task.playerUuid());
    if (player == null || !player.isOnline()) {
      rescheduleCommand(task.id(), "Player is offline", false);
      return;
    }

    String command = renderCommand(task.commandText(), player.getName(), task.quantity(), task.orderNo());
    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    if (!success) {
      rescheduleCommand(task.id(), "Command execution returned false", true);
      return;
    }

    markCommandDelivered(task.orderId(), task.id());
  }

  private void handleMarketTask(MarketItemDeliveryTask task) {
    Player player = Bukkit.getPlayer(task.targetUuid());
    if (player == null || !player.isOnline()) {
      rescheduleMarket(task.id(), "Player is offline", false);
      return;
    }

    ItemStack itemStack = itemSnapshotCodec.deserialize(task.itemBlob());
    if (itemStack.getType() == Material.AIR) {
      rescheduleMarket(task.id(), "Item snapshot is empty", true);
      return;
    }

    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack);
    if (!leftovers.isEmpty()) {
      rescheduleMarket(task.id(), "Inventory is full, retrying later", false);
      return;
    }
    markMarketDelivered(task.id());
  }

  private void markCommandDelivered(long orderId, long deliveryId) {
    databaseManager.inTransaction(connection -> {
      String updateDeliverySql = """
          UPDATE delivery_queue
          SET status = 'DELIVERED', delivered_at = NOW(), last_error = NULL
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateDeliverySql)) {
        statement.setLong(1, deliveryId);
        statement.executeUpdate();
      }

      String updateOrderSql = """
          UPDATE orders
          SET status = 'DELIVERED', delivered_at = NOW()
          WHERE id = ?
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

  private void markMarketDelivered(long deliveryId) {
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE market_item_deliveries
          SET status = 'DELIVERED', delivered_at = NOW(), last_error = NULL
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, deliveryId);
        statement.executeUpdate();
      }
      return null;
    });
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

  private String renderCommand(String template, String playerName, int quantity, String orderNo) {
    return template
        .replace("%player%", playerName)
        .replace("%amount%", Integer.toString(quantity))
        .replace("%order%", orderNo);
  }

  private String truncate(String text, int maxLength) {
    if (text == null || text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength);
  }

  private record CommandDeliveryTask(
      long id,
      long orderId,
      long itemId,
      UUID playerUuid,
      String commandText,
      int quantity,
      int retryCount,
      String orderNo) {
  }

  private record MarketItemDeliveryTask(
      long id,
      long listingId,
      long targetUserId,
      UUID targetUuid,
      byte[] itemBlob,
      int quantity,
      String deliveryType,
      int retryCount) {
  }
}
