package com.webshopx;

import com.google.gson.Gson;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.plugin.java.JavaPlugin;

final class InventoryReadSnapshotService {
  private final DatabaseManager databaseManager;
  private final InventorySnapshotJsonCodec codec;
  private final JavaPlugin plugin;
  private final AtomicLong lastCleanupMillis = new AtomicLong();

  InventoryReadSnapshotService(DatabaseManager databaseManager, Gson gson, JavaPlugin plugin) {
    this.databaseManager = databaseManager;
    this.codec = new InventorySnapshotJsonCodec(gson);
    this.plugin = plugin;
    OfficialShopInventoryV1.bootstrap(plugin, databaseManager, gson);
  }

  void save(
      UUID playerUuid,
      InventoryService.InventorySource source,
      InventoryService.Snapshot snapshot) {
    String json = codec.encode(snapshot).toString();
    long capturedEpochMillis = System.currentTimeMillis();
    databaseManager.inTransaction(connection -> {
      String sql = databaseManager.dbType().isSqlite()
          ? """
              INSERT INTO inventory_read_snapshots (
                player_uuid, inventory_source, snapshot_json, captured_epoch_ms, captured_at
              ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
              ON CONFLICT(player_uuid, inventory_source) DO UPDATE SET
                snapshot_json = excluded.snapshot_json,
                captured_epoch_ms = excluded.captured_epoch_ms,
                captured_at = CURRENT_TIMESTAMP
              """
          : """
              INSERT INTO inventory_read_snapshots (
                player_uuid, inventory_source, snapshot_json, captured_epoch_ms, captured_at
              ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
              ON DUPLICATE KEY UPDATE
                snapshot_json = VALUES(snapshot_json),
                captured_epoch_ms = VALUES(captured_epoch_ms),
                captured_at = CURRENT_TIMESTAMP
              """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        statement.setString(2, source.name());
        statement.setString(3, json);
        statement.setLong(4, capturedEpochMillis);
        statement.executeUpdate();
      }
      return null;
    });
    cleanupExpiredIfDue();
  }

  private void cleanupExpiredIfDue() {
    long now = System.currentTimeMillis();
    long previous = lastCleanupMillis.get();
    if (now - previous < 24L * 60 * 60 * 1000
        || !lastCleanupMillis.compareAndSet(previous, now)) {
      return;
    }
    int retentionDays = Math.max(
        1,
        plugin.getConfig().getInt("webshop.inventory-read-snapshots.retention-days", 30));
    long cutoff = now - retentionDays * 24L * 60 * 60 * 1000;
    databaseManager.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM inventory_read_snapshots WHERE captured_epoch_ms < ?")) {
        statement.setLong(1, cutoff);
        statement.executeUpdate();
      }
      return null;
    });
  }

  Optional<StoredSnapshot> find(
      UUID playerUuid,
      InventoryService.InventorySource source) {
    return databaseManager.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT snapshot_json, captured_epoch_ms
          FROM inventory_read_snapshots
          WHERE player_uuid = ? AND inventory_source = ?
          """)) {
        statement.setString(1, playerUuid.toString());
        statement.setString(2, source.name());
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            return Optional.empty();
          }
          Instant instant = Instant.ofEpochMilli(rows.getLong("captured_epoch_ms"));
          return Optional.of(new StoredSnapshot(rows.getString("snapshot_json"), instant));
        }
      }
    });
  }

  record StoredSnapshot(String snapshotJson, Instant capturedAt) {}
}
