package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

class PlayerPresenceService {
  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;

  PlayerPresenceService(DatabaseManager databaseManager, Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
  }

  void markOnline(UUID playerUuid, String username) {
    if (playerUuid == null) {
      return;
    }
    String serverId = currentServerId();
    if (serverId == null || serverId.isBlank()) {
      return;
    }
    databaseManager.withConnection(connection -> {
      String sql = """
          INSERT INTO player_presence (mc_uuid, username, server_id, online, updated_at)
          VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP)
          ON DUPLICATE KEY UPDATE
            username = VALUES(username),
            server_id = VALUES(server_id),
            online = TRUE,
            updated_at = CURRENT_TIMESTAMP
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        statement.setString(2, username == null ? "" : username);
        statement.setString(3, serverId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  void markOffline(UUID playerUuid) {
    if (playerUuid == null) {
      return;
    }
    String serverId = currentServerId();
    if (serverId == null || serverId.isBlank()) {
      return;
    }
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE player_presence
          SET online = FALSE, updated_at = CURRENT_TIMESTAMP
          WHERE mc_uuid = ?
            AND server_id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        statement.setString(2, serverId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  void markServerOffline(String serverId) {
    if (serverId == null || serverId.isBlank()) {
      return;
    }
    databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE player_presence
          SET online = FALSE, updated_at = CURRENT_TIMESTAMP
          WHERE server_id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, serverId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  String resolveOnlineServer(UUID playerUuid) {
    return databaseManager.withConnection(connection -> resolveOnlineServer(connection, playerUuid));
  }

  String resolveOnlineServer(Connection connection, UUID playerUuid) throws SQLException {
    if (playerUuid == null) {
      return null;
    }
    int ttlSeconds = Math.max(30, settingsSupplier.get().clusterSettings().presenceTtlSeconds());
    String sql = """
        SELECT server_id
        FROM player_presence
        WHERE mc_uuid = ?
          AND online = TRUE
          AND updated_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? SECOND)
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, playerUuid.toString());
      statement.setInt(2, ttlSeconds);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        String serverId = resultSet.getString("server_id");
        if (serverId == null || serverId.isBlank()) {
          return null;
        }
        return serverId;
      }
    }
  }

  String currentServerId() {
    return settingsSupplier.get().clusterSettings().serverId();
  }
}
