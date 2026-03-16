package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

class AdminService {
  private final DatabaseManager databaseManager;
  private final AuthService authService;
  private final WalletService walletService;

  AdminService(DatabaseManager databaseManager, AuthService authService, WalletService walletService) {
    this.databaseManager = databaseManager;
    this.authService = authService;
    this.walletService = walletService;
  }

  void ensureBootstrapAdmin(PluginSettings.AdminBootstrapSettings settings) {
    if (settings == null || !settings.enabled()) {
      return;
    }
    String username = settings.username();
    String password = settings.password();
    AdminRole role = AdminRole.fromRaw(settings.role());
    if (username == null || username.isBlank()) {
      return;
    }

    databaseManager.inTransaction(connection -> {
      Long userId = findUserIdByUsername(connection, username);
      if (userId == null) {
        userId = authService.createUserForAdminBootstrap(connection, username, password);
      }
      upsertAdminRole(connection, userId, role);
      return null;
    });
  }

  AdminLoginResult login(String identifier, String password) {
    AuthService.AuthResult result = authService.login(identifier, password);
    AdminRecord adminRecord = readAdminRecord(result.user().id());
    if (adminRecord == null || !adminRecord.active()) {
      authService.logout(result.sessionToken());
      throw new ServiceException("not_admin", "Admin permission required");
    }
    return new AdminLoginResult(result, adminRecord.role());
  }

  AdminUser requireAdmin(AuthService.AuthUser user, AdminPermission permission) {
    AdminRecord record = readAdminRecord(user.id());
    if (record == null || !record.active()) {
      throw new ServiceException("forbidden", "Admin permission required");
    }
    if (!record.role().allows(permission)) {
      throw new ServiceException("forbidden", "Admin permission denied");
    }
    return new AdminUser(user.id(), user.username(), user.boundUuid(), record.role());
  }

  AdminUser getAdminUser(AuthService.AuthUser user) {
    AdminRecord record = readAdminRecord(user.id());
    if (record == null || !record.active()) {
      throw new ServiceException("forbidden", "Admin permission required");
    }
    return new AdminUser(user.id(), user.username(), user.boundUuid(), record.role());
  }

  Optional<UserSupportView> lookupUser(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return Optional.empty();
    }
    return databaseManager.withConnection(connection -> {
      UserRow userRow = findUserByIdentifier(connection, identifier);
      if (userRow == null) {
        return Optional.empty();
      }
      WalletService.WalletBalance balance = walletService.getBalance(userRow.id());
      return Optional.of(new UserSupportView(
          userRow.id(),
          userRow.username(),
          userRow.boundUuid(),
          userRow.authState(),
          userRow.createdAt(),
          balance.shopCoin(),
          balance.gameCoin()));
    });
  }

  void resetPassword(long userId, String newPassword) {
    authService.resetPassword(userId, newPassword);
    authService.logoutAllSessions(userId);
  }

  void unbindUser(long userId) {
    databaseManager.withConnection(connection -> {
      String sql = "UPDATE web_users SET bound_uuid = NULL WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  void forceLogout(long userId) {
    authService.logoutAllSessions(userId);
  }

  WalletService.WalletBalance adjustWallet(long userId, CurrencyType currency, long delta, String reason) {
    String normalizedReason = reason == null || reason.isBlank() ? "ADMIN_ADJUST" : reason.trim();
    if (normalizedReason.length() > 32) {
      normalizedReason = normalizedReason.substring(0, 32);
    }
    String bizId = "admin:" + userId + ":" + System.currentTimeMillis();
    return walletService.adjustBalance(userId, currency, delta, normalizedReason, bizId);
  }

  private Long findUserIdByUsername(Connection connection, String username) throws SQLException {
    String sql = "SELECT id FROM web_users WHERE username = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, username.trim());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return resultSet.getLong("id");
      }
    }
  }

  private void upsertAdminRole(Connection connection, long userId, AdminRole role) throws SQLException {
    String sql = """
        INSERT INTO web_admins (user_id, role, active)
        VALUES (?, ?, TRUE)
        ON DUPLICATE KEY UPDATE role = VALUES(role), active = TRUE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, role.name());
      statement.executeUpdate();
    }
  }

  private AdminRecord readAdminRecord(long userId) {
    return databaseManager.withConnection(connection -> {
      String sql = "SELECT role, active FROM web_admins WHERE user_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          AdminRole role = AdminRole.fromRaw(resultSet.getString("role"));
          boolean active = resultSet.getBoolean("active");
          return new AdminRecord(role, active);
        }
      }
    });
  }

  private UserRow findUserByIdentifier(Connection connection, String identifier) throws SQLException {
    String trimmed = identifier.trim();
    Long userId = tryParseLong(trimmed);
    UUID uuid = tryParseUuid(trimmed);

    String sql = "SELECT id, username, bound_uuid, auth_state, created_at FROM web_users "
        + "WHERE id = ? OR username = ? OR bound_uuid = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId == null ? -1L : userId);
      statement.setString(2, trimmed);
      statement.setString(3, uuid == null ? null : uuid.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        String boundUuidRaw = resultSet.getString("bound_uuid");
        UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
        return new UserRow(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            boundUuid,
            resultSet.getString("auth_state"),
            resultSet.getTimestamp("created_at").toLocalDateTime());
      }
    }
  }

  private UUID tryParseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  java.util.List<UserListItem> listUsers(String keyword, int limit) {
    int normalizedLimit = Math.max(1, Math.min(limit, 300));
    String likeKeyword = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT u.id, u.username, u.bound_uuid, u.auth_state, u.created_at,
                 COALESCE(w.shop_coin, 0) AS shop_coin,
                 COALESCE(w.game_coin, 0) AS game_coin
          FROM web_users u
          LEFT JOIN wallets w ON w.user_id = u.id
          WHERE (? IS NULL OR u.username LIKE ? OR u.bound_uuid LIKE ? OR CAST(u.id AS CHAR) LIKE ?)
          ORDER BY u.created_at DESC, u.id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, likeKeyword);
        statement.setString(2, likeKeyword);
        statement.setString(3, likeKeyword);
        statement.setString(4, likeKeyword);
        statement.setInt(5, normalizedLimit);
        try (ResultSet resultSet = statement.executeQuery()) {
          java.util.List<UserListItem> rows = new java.util.ArrayList<>();
          while (resultSet.next()) {
            String boundUuidRaw = resultSet.getString("bound_uuid");
            UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
            rows.add(new UserListItem(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                boundUuid,
                resultSet.getString("auth_state"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getLong("shop_coin"),
                resultSet.getLong("game_coin")));
          }
          return rows;
        }
      }
    });
  }

  private Long tryParseLong(String raw) {
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  record AdminLoginResult(AuthService.AuthResult authResult, AdminRole role) {
  }

  record AdminUser(long userId, String username, UUID boundUuid, AdminRole role) {
  }

  private record AdminRecord(AdminRole role, boolean active) {
  }

  private record UserRow(
      long id,
      String username,
      UUID boundUuid,
      String authState,
      LocalDateTime createdAt) {
  }

  record UserSupportView(
      long userId,
      String username,
      UUID boundUuid,
      String authState,
      LocalDateTime createdAt,
      long shopCoin,
      long gameCoin) {
  }

  record UserListItem(
      long userId,
      String username,
      UUID boundUuid,
      String authState,
      LocalDateTime createdAt,
      long shopCoin,
      long gameCoin) {
  }
}
