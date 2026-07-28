package com.webshopx;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

class MailboxService {
  private static final int DEFAULT_CLAIM_BATCH_SIZE = 100;

  private final DatabaseManager databaseManager;
  private final ItemSnapshotCodec itemSnapshotCodec;

  MailboxService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
    this.itemSnapshotCodec = new ItemSnapshotCodec();
  }

  void enqueueItem(
      long userId,
      UUID targetUuid,
      ItemStack source,
      int totalAmount,
      String sourceType,
      String sourceRef,
      String reason) {
    if (userId <= 0L || targetUuid == null || source == null || source.getType() == Material.AIR) {
      throw new ServiceException("mailbox_invalid_item", "Cannot enqueue empty mailbox item");
    }
    ItemStack snapshotItem = source.clone();
    snapshotItem.setAmount(1);
    ItemSnapshotCodec.Snapshot snapshot = itemSnapshotCodec.serialize(snapshotItem);
    databaseManager.withConnection(connection -> {
      String sql = """
          INSERT INTO mailbox_items (
            user_id,
            target_uuid,
            source_type,
            source_ref,
            item_blob,
            quantity,
            reason,
            status,
            created_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setString(2, targetUuid.toString());
        statement.setString(3, normalizeText(sourceType, 24));
        statement.setString(4, normalizeText(sourceRef, 64));
        statement.setBytes(5, snapshot.rawItemBlob());
        statement.setInt(6, Math.max(1, totalAmount));
        statement.setString(7, normalizeText(reason, 255));
        statement.executeUpdate();
      }
      return null;
    });
  }

  int countPending(UUID playerUuid) {
    if (playerUuid == null) {
      return 0;
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT COUNT(*) AS cnt
          FROM mailbox_items
          WHERE target_uuid = ?
            AND status = 'PENDING'
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getInt("cnt") : 0;
        }
      }
    });
  }

  MailboxClaimSummary claimPending(Player player) {
    return claimPending(player, DEFAULT_CLAIM_BATCH_SIZE);
  }

  MailboxClaimSummary claimPending(Player player, int limit) {
    if (player == null) {
      return new MailboxClaimSummary(0, 0, 0);
    }
    return claimTasks(player, readPendingTasks(player.getUniqueId(), limit));
  }

  MailboxClaimSummary claimEntry(
      Player player, long userId, Long mailboxId, String sourceType, String sourceRef) {
    if (player == null || userId <= 0L) {
      return new MailboxClaimSummary(0, 0, 0);
    }
    List<MailboxItemTask> tasks = databaseManager.withConnection(connection -> {
      String idClause = mailboxId == null ? "" : " AND id = ?";
      String sourceClause = mailboxId == null
          ? " AND source_type = ? AND source_ref = ?" : "";
      String sql = """
          SELECT id, item_blob, quantity, delivered_quantity
          FROM mailbox_items
          WHERE user_id = ?
            AND target_uuid = ?
            AND status = 'PENDING'
          """ + idClause + sourceClause + " ORDER BY id ASC";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setString(2, player.getUniqueId().toString());
        if (mailboxId != null) {
          statement.setLong(3, mailboxId);
        } else {
          statement.setString(3, sourceType);
          statement.setString(4, sourceRef);
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          List<MailboxItemTask> rows = new ArrayList<>();
          while (resultSet.next()) {
            rows.add(new MailboxItemTask(
                resultSet.getLong("id"),
                resultSet.getBytes("item_blob"),
                resultSet.getInt("quantity"),
                resultSet.getInt("delivered_quantity")));
          }
          return rows;
        }
      }
    });
    return claimTasks(player, tasks);
  }

  OfflineReservation reserveOfflineEntry(
      long userId, UUID targetUuid, Long mailboxId, String sourceType, String sourceRef) {
    if (userId <= 0L || targetUuid == null) {
      throw new ServiceException("mailbox_entry_missing", "mailbox_entry_missing");
    }
    String token = "offline:" + UUID.randomUUID();
    List<MailboxItemTask> tasks = databaseManager.inTransaction(connection -> {
      String idClause = mailboxId == null ? "" : " AND id = ?";
      String sourceClause = mailboxId == null
          ? " AND source_type = ? AND source_ref = ?" : "";
      String sql = """
          SELECT id, item_blob, quantity, delivered_quantity
          FROM mailbox_items
          WHERE user_id = ? AND target_uuid = ? AND status = 'PENDING'
          """ + idClause + sourceClause + " ORDER BY id ASC";
      List<MailboxItemTask> selected = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setString(2, targetUuid.toString());
        if (mailboxId != null) {
          statement.setLong(3, mailboxId);
        } else {
          statement.setString(3, sourceType);
          statement.setString(4, sourceRef);
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            selected.add(new MailboxItemTask(
                resultSet.getLong("id"), resultSet.getBytes("item_blob"),
                resultSet.getInt("quantity"), resultSet.getInt("delivered_quantity")));
          }
        }
      }
      for (MailboxItemTask task : selected) {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE mailbox_items SET status = 'PROCESSING', last_error = ?
            WHERE id = ? AND status = 'PENDING'
            """)) {
          update.setString(1, token);
          update.setLong(2, task.id());
          if (update.executeUpdate() != 1) {
            throw new ServiceException(
                "delivery_in_progress", "delivery_in_progress");
          }
        }
      }
      return selected;
    });
    List<ItemStack> items = new ArrayList<>();
    try {
      for (MailboxItemTask task : tasks) {
        ItemStack base = itemSnapshotCodec.deserialize(task.itemBlob());
        int remaining = task.remainingQuantity();
        int maxStack = Math.max(1, base.getMaxStackSize());
        while (remaining > 0) {
          ItemStack stack = base.clone();
          stack.setAmount(Math.min(maxStack, remaining));
          items.add(stack);
          remaining -= stack.getAmount();
        }
      }
    } catch (RuntimeException exception) {
      releaseOffline(token, "MAILBOX_SNAPSHOT_RESTORE_FAILED");
      throw exception;
    }
    return new OfflineReservation(token, List.copyOf(items), tasks.size());
  }

  private void completeOffline(String token) {
    databaseManager.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE mailbox_items
          SET status = 'CLAIMED', delivered_quantity = quantity,
              claimed_at = CURRENT_TIMESTAMP, last_error = NULL
          WHERE status = 'PROCESSING' AND last_error = ?
          """)) {
        statement.setString(1, token);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private void releaseOffline(String token, String error) {
    databaseManager.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE mailbox_items SET status = 'PENDING', last_error = ?
          WHERE status = 'PROCESSING' AND last_error = ?
          """)) {
        statement.setString(1, normalizeText(error, 255));
        statement.setString(2, token);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private MailboxClaimSummary claimTasks(Player player, List<MailboxItemTask> tasks) {
    UUID playerUuid = player.getUniqueId();
    int claimed = 0;
    int failed = 0;
    for (MailboxItemTask task : tasks) {
      try {
        ItemStack baseItem = itemSnapshotCodec.deserialize(task.itemBlob());
        int remainingQuantity = task.remainingQuantity();
        if (remainingQuantity <= 0) {
          markClaimed(task.id(), 0);
          claimed++;
          continue;
        }
        int deliveredNow = addItemToInventory(player, baseItem, remainingQuantity);
        if (deliveredNow >= remainingQuantity) {
          markClaimed(task.id(), deliveredNow);
          claimed++;
        } else {
          markProgress(task.id(), deliveredNow, "inventory is full");
          failed++;
        }
      } catch (Exception exception) {
        markProgress(task.id(), 0, normalizeText(exception.getMessage(), 255));
        failed++;
      }
    }
    int remaining = countPending(playerUuid);
    return new MailboxClaimSummary(claimed, failed, remaining);
  }

  List<MailboxItemView> listPending(UUID playerUuid, int offset, int limit) {
    if (playerUuid == null) {
      return List.of();
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT id, source_type, source_ref, item_blob, quantity, delivered_quantity, reason
          FROM mailbox_items
          WHERE target_uuid = ?
            AND status = 'PENDING'
          ORDER BY id ASC
          LIMIT ? OFFSET ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        statement.setInt(2, Math.max(1, limit));
        statement.setInt(3, Math.max(0, offset));
        try (ResultSet resultSet = statement.executeQuery()) {
          List<MailboxItemView> items = new ArrayList<>();
          while (resultSet.next()) {
            ItemStack item = itemSnapshotCodec.deserialize(resultSet.getBytes("item_blob"));
            items.add(new MailboxItemView(
                resultSet.getLong("id"),
                item,
                Math.max(0, resultSet.getInt("quantity") - resultSet.getInt("delivered_quantity")),
                resultSet.getString("source_type"),
                resultSet.getString("source_ref"),
                resultSet.getString("reason")));
          }
          return items;
        }
      }
    });
  }

  List<StandaloneMailboxItem> listStandalonePending(long userId, int limit) {
    if (userId <= 0L) {
      return List.of();
    }
    return databaseManager.withConnection(connection -> {
      String sql =
          """
          SELECT id, target_uuid, source_type, source_ref, item_blob, quantity, delivered_quantity,
                 reason, last_error, created_at
          FROM mailbox_items
          WHERE user_id = ?
            AND status = 'PENDING'
            AND UPPER(source_type) NOT IN ('ORDER', 'MARKET')
          ORDER BY created_at DESC, id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setInt(2, Math.max(1, limit));
        try (ResultSet resultSet = statement.executeQuery()) {
          List<StandaloneMailboxItem> items = new ArrayList<>();
          while (resultSet.next()) {
            ItemStack item = itemSnapshotCodec.deserialize(resultSet.getBytes("item_blob"));
            ItemSnapshotCodec.Snapshot snapshot = itemSnapshotCodec.serialize(item);
            Timestamp createdAt = resultSet.getTimestamp("created_at");
            items.add(new StandaloneMailboxItem(
                resultSet.getLong("id"),
                resultSet.getString("target_uuid"),
                item,
                resultSet.getInt("quantity"),
                Math.max(0, resultSet.getInt("delivered_quantity")),
                resultSet.getString("source_type"),
                resultSet.getString("source_ref"),
                resultSet.getString("reason"),
                resultSet.getString("last_error"),
                createdAt == null ? LocalDateTime.now() : createdAt.toLocalDateTime(),
                snapshot.itemMetaJson()));
          }
          return items;
        }
      }
    });
  }

  PendingContext findPendingContext(
      long userId, String sourceType, String sourceRef) {
    if (userId <= 0L || sourceType == null || sourceRef == null) {
      return new PendingContext(null, null);
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT reason, last_error
          FROM mailbox_items
          WHERE user_id = ?
            AND source_type = ?
            AND source_ref = ?
            AND status = 'PENDING'
          ORDER BY id DESC
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setString(2, sourceType);
        statement.setString(3, sourceRef);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next()
              ? new PendingContext(
                  resultSet.getString("reason"), resultSet.getString("last_error"))
              : new PendingContext(null, null);
        }
      }
    });
  }

  int countStandalonePending(long userId) {
    if (userId <= 0L) {
      return 0;
    }
    return databaseManager.withConnection(connection -> {
      String sql =
          """
          SELECT COUNT(*) AS cnt
          FROM mailbox_items
          WHERE user_id = ?
            AND status = 'PENDING'
            AND UPPER(source_type) NOT IN ('ORDER', 'MARKET')
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getInt("cnt") : 0;
        }
      }
    });
  }

  private List<MailboxItemTask> readPendingTasks(UUID playerUuid, int limit) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT id, item_blob, quantity, delivered_quantity
          FROM mailbox_items
          WHERE target_uuid = ?
            AND status = 'PENDING'
          ORDER BY id ASC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        statement.setInt(2, Math.max(1, limit));
        try (ResultSet resultSet = statement.executeQuery()) {
          List<MailboxItemTask> tasks = new ArrayList<>();
          while (resultSet.next()) {
            tasks.add(new MailboxItemTask(
                resultSet.getLong("id"),
                resultSet.getBytes("item_blob"),
                resultSet.getInt("quantity"),
                Math.max(0, resultSet.getInt("delivered_quantity"))));
          }
          return tasks;
        }
      }
    });
  }

  private void markClaimed(long mailboxId, int deliveredNow) {
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE mailbox_items
          SET status = 'CLAIMED',
              delivered_quantity = quantity,
              claimed_at = CURRENT_TIMESTAMP,
              last_error = NULL
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, mailboxId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  void enqueueItem(
      java.sql.Connection connection,
      long userId,
      UUID targetUuid,
      ItemSnapshotCodec.Snapshot snapshot,
      int totalAmount,
      String sourceType,
      String sourceRef,
      String reason) throws java.sql.SQLException {
    String sql = """
        INSERT INTO mailbox_items (
          user_id, target_uuid, source_type, source_ref, item_blob,
          quantity, reason, status, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
        """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, targetUuid.toString());
      statement.setString(3, normalizeText(sourceType, 24));
      statement.setString(4, normalizeText(sourceRef, 64));
      statement.setBytes(5, snapshot.rawItemBlob());
      statement.setInt(6, Math.max(1, totalAmount));
      statement.setString(7, normalizeText(reason, 255));
      statement.executeUpdate();
    }
  }

  private void markFailed(long mailboxId, String error) {
    markProgress(mailboxId, 0, error);
  }

  private void markProgress(long mailboxId, int deliveredNow, String error) {
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE mailbox_items
          SET delivered_quantity = CASE
                WHEN delivered_quantity + ? > quantity THEN quantity
                ELSE delivered_quantity + ?
              END,
              last_error = ?
          WHERE id = ?
          """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, Math.max(0, deliveredNow));
        statement.setInt(2, Math.max(0, deliveredNow));
        statement.setString(3, normalizeText(error, 255));
        statement.setLong(4, mailboxId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private int addItemToInventory(Player player, ItemStack source, int totalAmount) {
    if (source == null || source.getType() == Material.AIR) {
      throw new IllegalStateException("item snapshot is empty");
    }
    int remaining = Math.max(1, totalAmount);
    int delivered = 0;
    int maxStack = Math.max(1, source.getMaxStackSize());
    while (remaining > 0) {
      if (player == null || !player.isOnline()) {
        break;
      }
      int chunk = Math.min(maxStack, remaining);
      ItemStack stack = source.clone();
      stack.setAmount(chunk);
      Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
      int leftoverAmount = leftovers.values().stream()
          .filter(item -> item != null && item.getType() != Material.AIR)
          .mapToInt(ItemStack::getAmount)
          .sum();
      int accepted = Math.max(0, chunk - leftoverAmount);
      delivered += accepted;
      remaining -= accepted;
      if (leftoverAmount > 0 || accepted <= 0) {
        break;
      }
    }
    return delivered;
  }

  private String normalizeText(String text, int maxLength) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String normalized = text.trim();
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength);
  }

  record MailboxClaimSummary(int success, int failed, int remaining) {
  }

  record MailboxItemView(
      long id,
      ItemStack item,
      int remainingQuantity,
      String sourceType,
      String sourceRef,
      String reason) {
  }

  record StandaloneMailboxItem(
      long id,
      String targetUuid,
      ItemStack item,
      int quantity,
      int deliveredQuantity,
      String sourceType,
      String sourceRef,
      String reason,
      String lastError,
      LocalDateTime createdAt,
      String itemMetaJson) {
    int remainingQuantity() {
      return Math.max(0, quantity - deliveredQuantity);
    }
  }

  record PendingContext(String reason, String lastError) {
  }

  final class OfflineReservation {
    private final String token;
    private final List<ItemStack> items;
    private final int taskCount;
    private boolean finished;

    private OfflineReservation(String token, List<ItemStack> items, int taskCount) {
      this.token = token;
      this.items = items;
      this.taskCount = taskCount;
    }

    List<ItemStack> items() {
      return items;
    }

    int taskCount() {
      return taskCount;
    }

    void complete() {
      completeOffline(token);
      finished = true;
    }

    void release(String error) {
      if (!finished) {
        releaseOffline(token, error);
        finished = true;
      }
    }
  }

  private record MailboxItemTask(long id, byte[] itemBlob, int quantity, int deliveredQuantity) {
    int remainingQuantity() {
      return Math.max(0, quantity - Math.max(0, deliveredQuantity));
    }
  }
}
