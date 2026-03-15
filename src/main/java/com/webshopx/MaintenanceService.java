package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.bukkit.plugin.java.JavaPlugin;

class MaintenanceService {
  private static final String STATE_PENDING_BIND = "PENDING_BIND";
  private static final String STATE_PENDING_PASSWORD = "PENDING_PASSWORD";

  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;
  private final PluginLogService pluginLogService;

  MaintenanceService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      Supplier<PluginSettings> settingsSupplier,
      PluginLogService pluginLogService) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
    this.pluginLogService = pluginLogService;
  }

  void runCleanup() {
    PluginSettings.MaintenanceSettings settings = settingsSupplier.get().maintenanceSettings();
    int intervalMinutes = settings.cleanupIntervalMinutes();
    if (intervalMinutes <= 0) {
      return;
    }

    CleanupResult result = databaseManager.withConnection(connection -> {
      LocalDateTime now = LocalDateTime.now();
      int sessions = deleteExpiredSessions(connection, now);
      int bindRequests = deleteOldBindRequests(connection, now, settings.bindRequestRetentionHours());
      int pendingBindUsers =
          deletePendingUsers(connection, now, settings.pendingBindRetentionHours(), STATE_PENDING_BIND);
      int pendingPasswordUsers =
          deletePendingUsers(connection, now, settings.pendingPasswordRetentionHours(), STATE_PENDING_PASSWORD);
      int redeemCodes = deleteOldRedeemCodes(connection, now, settings.redeemCodeRetentionDays());
      return new CleanupResult(sessions, bindRequests, pendingBindUsers, pendingPasswordUsers, redeemCodes);
    });

    if (result.total() > 0) {
      plugin.getLogger().info(
          String.format(
              "Maintenance cleanup done: sessions=%d, bindRequests=%d, pendingBindUsers=%d,"
                  + " pendingPasswordUsers=%d, redeemCodes=%d",
              result.sessions,
              result.bindRequests,
              result.pendingBindUsers,
              result.pendingPasswordUsers,
              result.redeemCodes));
    }
    if (pluginLogService != null) {
      pluginLogService.cleanupOldLogs(settingsSupplier.get().loggingSettings());
    }
  }

  private int deleteExpiredSessions(Connection connection, LocalDateTime now) throws SQLException {
    String sql = "DELETE FROM web_sessions WHERE expires_at < ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.valueOf(now));
      return statement.executeUpdate();
    }
  }

  private int deleteOldBindRequests(
      Connection connection,
      LocalDateTime now,
      int retentionHours) throws SQLException {
    if (retentionHours <= 0) {
      return 0;
    }
    LocalDateTime cutoff = now.minusHours(retentionHours);
    String sql = """
        DELETE FROM bind_requests
        WHERE (expires_at < ? AND used = FALSE)
           OR (used = TRUE AND used_at IS NOT NULL AND used_at < ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      Timestamp cutoffTs = Timestamp.valueOf(cutoff);
      statement.setTimestamp(1, cutoffTs);
      statement.setTimestamp(2, cutoffTs);
      return statement.executeUpdate();
    }
  }

  private int deletePendingUsers(
      Connection connection,
      LocalDateTime now,
      int retentionHours,
      String state) throws SQLException {
    if (retentionHours <= 0) {
      return 0;
    }
    LocalDateTime cutoff = now.minusHours(retentionHours);
    String sql = "DELETE FROM web_users WHERE auth_state = ? AND created_at < ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, state);
      statement.setTimestamp(2, Timestamp.valueOf(cutoff));
      return statement.executeUpdate();
    }
  }

  private int deleteOldRedeemCodes(
      Connection connection,
      LocalDateTime now,
      int retentionDays) throws SQLException {
    if (retentionDays <= 0) {
      return 0;
    }
    LocalDateTime cutoff = now.minusDays(retentionDays);
    String sql = """
        DELETE FROM redeem_codes
        WHERE (expires_at IS NOT NULL AND expires_at < ?)
           OR (max_uses > 0 AND used_count >= max_uses AND created_at < ?)
           OR (active = FALSE AND created_at < ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      Timestamp cutoffTs = Timestamp.valueOf(cutoff);
      statement.setTimestamp(1, cutoffTs);
      statement.setTimestamp(2, cutoffTs);
      statement.setTimestamp(3, cutoffTs);
      return statement.executeUpdate();
    }
  }

  private static final class CleanupResult {
    private final int sessions;
    private final int bindRequests;
    private final int pendingBindUsers;
    private final int pendingPasswordUsers;
    private final int redeemCodes;

    private CleanupResult(
        int sessions,
        int bindRequests,
        int pendingBindUsers,
        int pendingPasswordUsers,
        int redeemCodes) {
      this.sessions = sessions;
      this.bindRequests = bindRequests;
      this.pendingBindUsers = pendingBindUsers;
      this.pendingPasswordUsers = pendingPasswordUsers;
      this.redeemCodes = redeemCodes;
    }

    private int total() {
      return sessions + bindRequests + pendingBindUsers + pendingPasswordUsers + redeemCodes;
    }
  }
}
