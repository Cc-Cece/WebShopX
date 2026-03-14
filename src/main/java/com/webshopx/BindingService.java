package com.webshopx;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

class BindingService {
  private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;
  private final SecureRandom secureRandom;

  BindingService(DatabaseManager databaseManager, Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
    this.secureRandom = new SecureRandom();
  }

  String createBindRequest(long userId) {
    return databaseManager.inTransaction(connection -> {
      if (userHasBoundUuid(connection, userId)) {
        throw new ServiceException("already_bound", "User has already bound a Minecraft account");
      }
      int expireMinutes = Math.max(1, settingsSupplier.get().bindRequestExpireMinutes());
      LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(expireMinutes);

      for (int attempt = 0; attempt < 5; attempt++) {
        String code = randomCode(8);
        if (insertBindRequest(connection, code, userId, expiresAt)) {
          return code;
        }
      }
      throw new IllegalStateException("Could not allocate unique bind code");
    });
  }

  BindResult bindPlayer(UUID playerUuid, String bindCode) {
    if (bindCode == null || bindCode.isBlank()) {
      return new BindResult(BindStatus.INVALID_CODE, null);
    }
    return databaseManager.inTransaction(connection -> bindPlayerInTransaction(
        connection,
        playerUuid,
        bindCode.trim().toUpperCase(Locale.ROOT)));
  }

  private BindResult bindPlayerInTransaction(Connection connection, UUID playerUuid, String bindCode)
      throws SQLException {
    BindRequest bindRequest = readBindRequestForUpdate(connection, bindCode);
    if (bindRequest == null) {
      return new BindResult(BindStatus.INVALID_CODE, null);
    }
    if (bindRequest.used()) {
      return new BindResult(BindStatus.ALREADY_USED, null);
    }
    if (bindRequest.expiresAt().isBefore(LocalDateTime.now())) {
      return new BindResult(BindStatus.EXPIRED, null);
    }
    if (userHasBoundUuid(connection, bindRequest.userId())) {
      return new BindResult(BindStatus.USER_ALREADY_BOUND, null);
    }
    if (uuidAlreadyBound(connection, playerUuid)) {
      return new BindResult(BindStatus.PLAYER_ALREADY_BOUND, null);
    }

    String username = readUsername(connection, bindRequest.userId());
    String currentState = readAuthStateForUpdate(connection, bindRequest.userId());
    String nextState = currentState;
    if ("PENDING_BIND".equals(currentState)) {
      nextState = "PENDING_PASSWORD";
    }

    String updateUserSql = "UPDATE web_users SET bound_uuid = ?, auth_state = ? WHERE id = ?";
    try (PreparedStatement statement = connection.prepareStatement(updateUserSql)) {
      statement.setString(1, playerUuid.toString());
      statement.setString(2, nextState);
      statement.setLong(3, bindRequest.userId());
      statement.executeUpdate();
    }

    String updateBindSql = "UPDATE bind_requests SET used = TRUE, used_at = NOW() WHERE bind_code = ?";
    try (PreparedStatement statement = connection.prepareStatement(updateBindSql)) {
      statement.setString(1, bindCode);
      statement.executeUpdate();
    }

    return new BindResult(BindStatus.SUCCESS, username);
  }

  private boolean insertBindRequest(
      Connection connection,
      String bindCode,
      long userId,
      LocalDateTime expiresAt) throws SQLException {
    String sql = """
        INSERT INTO bind_requests (bind_code, user_id, expires_at, used)
        VALUES (?, ?, ?, FALSE)
        ON DUPLICATE KEY UPDATE bind_code = bind_code
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, bindCode);
      statement.setLong(2, userId);
      statement.setTimestamp(3, Timestamp.valueOf(expiresAt));
      return statement.executeUpdate() == 1;
    }
  }

  private String readUsername(Connection connection, long userId) throws SQLException {
    String sql = "SELECT username FROM web_users WHERE id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "User not found");
        }
        return resultSet.getString("username");
      }
    }
  }

  private String readAuthStateForUpdate(Connection connection, long userId) throws SQLException {
    String sql = "SELECT auth_state FROM web_users WHERE id = ? FOR UPDATE";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "User not found");
        }
        String state = resultSet.getString("auth_state");
        return state == null ? "ACTIVE" : state;
      }
    }
  }

  private BindRequest readBindRequestForUpdate(Connection connection, String bindCode) throws SQLException {
    String sql = """
        SELECT user_id, expires_at, used
        FROM bind_requests
        WHERE bind_code = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, bindCode);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        Timestamp expiresAt = resultSet.getTimestamp("expires_at");
        return new BindRequest(
            resultSet.getLong("user_id"),
            expiresAt.toLocalDateTime(),
            resultSet.getBoolean("used"));
      }
    }
  }

  private boolean userHasBoundUuid(Connection connection, long userId) throws SQLException {
    String sql = "SELECT bound_uuid FROM web_users WHERE id = ? FOR UPDATE";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "User not found");
        }
        return resultSet.getString("bound_uuid") != null;
      }
    }
  }

  private boolean uuidAlreadyBound(Connection connection, UUID playerUuid) throws SQLException {
    String sql = "SELECT id FROM web_users WHERE bound_uuid = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, playerUuid.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private String randomCode(int length) {
    StringBuilder builder = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      int pointer = secureRandom.nextInt(CODE_ALPHABET.length());
      builder.append(CODE_ALPHABET.charAt(pointer));
    }
    return builder.toString();
  }

  private record BindRequest(long userId, LocalDateTime expiresAt, boolean used) {
  }

  enum BindStatus {
    SUCCESS,
    INVALID_CODE,
    EXPIRED,
    ALREADY_USED,
    USER_ALREADY_BOUND,
    PLAYER_ALREADY_BOUND
  }

  record BindResult(BindStatus status, String username) {
  }
}
