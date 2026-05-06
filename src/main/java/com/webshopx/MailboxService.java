package com.webshopx;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    UUID playerUuid = player.getUniqueId();
    int claimed = 0;
    int failed = 0;
    List<MailboxItemTask> tasks = readPendingTasks(playerUuid, limit);
    for (MailboxItemTask task : tasks) {
      try {
        ItemStack baseItem = itemSnapshotCodec.deserialize(task.itemBlob());
        addItemToInventory(player, baseItem, task.quantity());
        markClaimed(task.id());
        claimed++;
      } catch (Exception exception) {
        markFailed(task.id(), normalizeText(exception.getMessage(), 255));
        failed++;
      }
    }
    int remaining = countPending(playerUuid);
    return new MailboxClaimSummary(claimed, failed, remaining);
  }

  private List<MailboxItemTask> readPendingTasks(UUID playerUuid, int limit) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT id, item_blob, quantity
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
                resultSet.getInt("quantity")));
          }
          return tasks;
        }
      }
    });
  }

  private void markClaimed(long mailboxId) {
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE mailbox_items
          SET status = 'CLAIMED',
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

  private void markFailed(long mailboxId, String error) {
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE mailbox_items
          SET last_error = ?
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalizeText(error, 255));
        statement.setLong(2, mailboxId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private void addItemToInventory(Player player, ItemStack source, int totalAmount) {
    if (source == null || source.getType() == Material.AIR) {
      throw new IllegalStateException("待领取物品为空");
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

  private record MailboxItemTask(long id, byte[] itemBlob, int quantity) {
  }
}
