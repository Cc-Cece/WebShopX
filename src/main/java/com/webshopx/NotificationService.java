package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class NotificationService {
  private static final int DEFAULT_LIMIT = 30;
  private static final int MAX_LIMIT = 100;

  private final DatabaseManager databaseManager;

  public NotificationService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  public List<NotificationView> listForUser(
      long userId, int requestedLimit, Long cursor, boolean unreadOnly) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> {
      StringBuilder sql = new StringBuilder(
          """
          SELECT id, user_id, type, title, content, data_json, is_read, created_at, read_at
          FROM notifications
          WHERE user_id = ?
          """);
      List<Object> params = new ArrayList<>();
      params.add(userId);
      if (cursor != null && cursor > 0L) {
        sql.append(" AND id < ?");
        params.add(cursor);
      }
      if (unreadOnly) {
        sql.append(" AND is_read = FALSE");
      }
      sql.append(" ORDER BY id DESC LIMIT ?");
      params.add(limit);

      try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
        for (int i = 0; i < params.size(); i++) {
          statement.setObject(i + 1, params.get(i));
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          List<NotificationView> rows = new ArrayList<>();
          while (resultSet.next()) {
            rows.add(new NotificationView(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("type"),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getString("data_json"),
                resultSet.getBoolean("is_read"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("read_at") == null
                    ? null
                    : resultSet.getTimestamp("read_at").toLocalDateTime()));
          }
          return rows;
        }
      }
    });
  }

  public long countUnread(long userId) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT COUNT(*) AS cnt
          FROM notifications
          WHERE user_id = ?
            AND is_read = FALSE
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getLong("cnt") : 0L;
        }
      }
    });
  }

  public int markRead(long userId, long notificationId) {
    if (notificationId <= 0L) {
      return 0;
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE notifications
          SET is_read = TRUE,
              read_at = CURRENT_TIMESTAMP
          WHERE id = ?
            AND user_id = ?
            AND is_read = FALSE
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, notificationId);
        statement.setLong(2, userId);
        return statement.executeUpdate();
      }
    });
  }

  public int markAllRead(long userId) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          UPDATE notifications
          SET is_read = TRUE,
              read_at = CURRENT_TIMESTAMP
          WHERE user_id = ?
            AND is_read = FALSE
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        return statement.executeUpdate();
      }
    });
  }

  public void createNotification(long userId, String type, String title, String content) {
    createNotification(userId, type, title, content, null);
  }

  public void createNotification(
      long userId, String type, String title, String content, String dataJson) {
    if (userId <= 0L) {
      return;
    }
    String normalizedTitle = normalizeText(title, 128);
    String normalizedContent = normalizeText(content, 4000);
    if (normalizedTitle.isBlank() || normalizedContent.isBlank()) {
      return;
    }
    String normalizedType = normalizeType(type);
    databaseManager.withConnection(connection -> {
      insertNotification(connection, userId, normalizedType, normalizedTitle, normalizedContent, dataJson);
      return null;
    });
  }

  public void createNotifications(List<Long> userIds, String type, String title, String content) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }
    String normalizedTitle = normalizeText(title, 128);
    String normalizedContent = normalizeText(content, 4000);
    if (normalizedTitle.isBlank() || normalizedContent.isBlank()) {
      return;
    }
    String normalizedType = normalizeType(type);
    Set<Long> targets = new LinkedHashSet<>();
    for (Long userId : userIds) {
      if (userId != null && userId > 0L) {
        targets.add(userId);
      }
    }
    if (targets.isEmpty()) {
      return;
    }
    databaseManager.withConnection(connection -> {
      String sql = """
          INSERT INTO notifications (user_id, type, title, content, data_json, is_read, created_at)
          VALUES (?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (Long userId : targets) {
          statement.setLong(1, userId);
          statement.setString(2, normalizedType);
          statement.setString(3, normalizedTitle);
          statement.setString(4, normalizedContent);
          statement.setString(5, null);
          statement.addBatch();
        }
        statement.executeBatch();
      }
      return null;
    });
  }

  public int createSystemAnnouncement(String title, String content) {
    String normalizedTitle = normalizeText(title, 128);
    String normalizedContent = normalizeText(content, 4000);
    if (normalizedTitle.isBlank() || normalizedContent.isBlank()) {
      return 0;
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          INSERT INTO notifications (user_id, type, title, content, data_json, is_read, created_at)
          SELECT id, 'SYSTEM_ANNOUNCEMENT', ?, ?, NULL, FALSE, CURRENT_TIMESTAMP
          FROM web_users
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalizedTitle);
        statement.setString(2, normalizedContent);
        return statement.executeUpdate();
      }
    });
  }

  public Long findUserIdByBoundUuid(UUID boundUuid) {
    if (boundUuid == null) {
      return null;
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT id
          FROM web_users
          WHERE bound_uuid = ?
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, boundUuid.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return resultSet.getLong("id");
        }
      }
    });
  }

  private void insertNotification(
      Connection connection,
      long userId,
      String type,
      String title,
      String content,
      String dataJson) throws SQLException {
    String sql = """
        INSERT INTO notifications (user_id, type, title, content, data_json, is_read, created_at)
        VALUES (?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, type);
      statement.setString(3, title);
      statement.setString(4, content);
      statement.setString(5, dataJson);
      statement.executeUpdate();
    }
  }

  private int normalizeLimit(int requestedLimit) {
    if (requestedLimit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requestedLimit, MAX_LIMIT);
  }

  private String normalizeType(String type) {
    if (type == null || type.isBlank()) {
      return "GENERAL";
    }
    String normalized = type.trim().toUpperCase();
    return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
  }

  private String normalizeText(String text, int maxLength) {
    if (text == null) {
      return "";
    }
    String normalized = text.trim();
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength);
  }

  public record NotificationView(
      long id,
      long userId,
      String type,
      String title,
      String content,
      String dataJson,
      boolean read,
      LocalDateTime createdAt,
      LocalDateTime readAt) {
  }
}

