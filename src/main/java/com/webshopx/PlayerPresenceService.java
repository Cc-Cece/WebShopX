package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

public class PlayerPresenceService {
  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final Supplier<PresenceSettings> settingsSupplier;

  public PlayerPresenceService(DatabaseManager databaseManager, Supplier<PresenceSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.settingsSupplier = settingsSupplier;
  }

  public void markOnline(UUID playerUuid, String username) {
    if (playerUuid == null) {
      return;
    }
    String serverId = currentServerId();
    if (serverId == null || serverId.isBlank()) {
      return;
    }
    databaseManager.withConnection(connection -> {
      String sql = sqlProvider.upsertPlayerPresenceOnlineSql();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        statement.setString(2, username == null ? "" : username);
        statement.setString(3, serverId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  public void markOffline(UUID playerUuid) {
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

  public void markServerOffline(String serverId) {
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

  public String resolveOnlineServer(UUID playerUuid) {
    return databaseManager.withConnection(connection -> resolveOnlineServer(connection, playerUuid));
  }

  public String resolveOnlineServer(Connection connection, UUID playerUuid) throws SQLException {
    if (playerUuid == null) {
      return null;
    }
    int ttlSeconds = settingsSupplier.get().presenceTtlSeconds();
    String sql =
        """
        SELECT server_id
        FROM player_presence
        WHERE mc_uuid = ?
          AND online = TRUE
          AND updated_at >= %s
        LIMIT 1
        """
            .formatted(sqlProvider.currentTimestampMinusSecondsExpr());
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

  public String currentServerId() {
    return settingsSupplier.get().serverId();
  }

  public record PresenceSettings(String serverId, int presenceTtlSeconds) {
    public PresenceSettings {
      serverId = serverId == null ? "" : serverId.trim();
      presenceTtlSeconds = Math.max(30, Math.min(86_400, presenceTtlSeconds));
    }
  }
}
