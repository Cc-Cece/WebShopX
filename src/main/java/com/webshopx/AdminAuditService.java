package com.webshopx;

import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

class AdminAuditService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 500;

  private final DatabaseManager databaseManager;

  AdminAuditService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  void log(
      AdminService.AdminUser admin,
      String action,
      String targetType,
      String targetId,
      JsonObject detail,
      String sourceIp) {
    if (admin == null) {
      return;
    }
    String sql = """
        INSERT INTO admin_audit_logs (
          admin_user_id, admin_role, action, target_type, target_id, detail_json, source_ip
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    databaseManager.withConnection(connection -> {
      insertAuditLog(connection, sql, admin, action, targetType, targetId, detail, sourceIp);
      return null;
    });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Audit insert uses a fixed SQL statement and binds every external value")
  private void insertAuditLog(
      Connection connection,
      String sql,
      AdminService.AdminUser admin,
      String action,
      String targetType,
      String targetId,
      JsonObject detail,
      String sourceIp) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, admin.userId());
      statement.setString(2, admin.role().name());
      statement.setString(3, action);
      statement.setString(4, targetType);
      statement.setString(5, targetId);
      statement.setString(6, detail == null ? null : detail.toString());
      statement.setString(7, sourceIp);
      statement.executeUpdate();
    }
  }

  List<AuditView> list(int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> readAuditLogs(connection, limit));
  }

  private List<AuditView> readAuditLogs(Connection connection, int limit) throws SQLException {
    String sql = """
        SELECT a.id, a.admin_user_id, a.admin_role, a.action, a.target_type, a.target_id,
               a.detail_json, a.source_ip, a.created_at, u.username
        FROM admin_audit_logs a
        JOIN web_users u ON u.id = a.admin_user_id
        ORDER BY a.id DESC
        LIMIT ?
        """;
    List<AuditView> logs = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, limit);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          logs.add(new AuditView(
              resultSet.getLong("id"),
              resultSet.getLong("admin_user_id"),
              resultSet.getString("username"),
              resultSet.getString("admin_role"),
              resultSet.getString("action"),
              resultSet.getString("target_type"),
              resultSet.getString("target_id"),
              resultSet.getString("detail_json"),
              resultSet.getString("source_ip"),
              resultSet.getTimestamp("created_at").toLocalDateTime()));
        }
      }
    }
    return logs;
  }

  private int normalizeLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  record AuditView(
      long id,
      long adminUserId,
      String adminUsername,
      String adminRole,
      String action,
      String targetType,
      String targetId,
      String detailJson,
      String sourceIp,
      java.time.LocalDateTime createdAt) {
  }
}
