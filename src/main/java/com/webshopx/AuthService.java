package com.webshopx;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

class AuthService {
  private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{3,32}$";
  private static final String STATE_ACTIVE = "ACTIVE";

  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final Supplier<PluginSettings> settingsSupplier;
  private final PasswordHasher passwordHasher;
  private final SecureRandom secureRandom;

  AuthService(DatabaseManager databaseManager, Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.settingsSupplier = settingsSupplier;
    this.passwordHasher = new PasswordHasher();
    this.secureRandom = new SecureRandom();
  }

  AuthResult login(String identifier, String password) {
    validatePassword(password);
    if (identifier == null || identifier.isBlank()) {
      throw new ServiceException("invalid_identifier", "Username or UUID is required");
    }

    long userId = databaseManager.withConnection(connection -> verifyUser(connection, identifier, password));
    return createSession(userId);
  }

  InGamePasswordResult setPasswordFromGame(UUID playerUuid, String playerName, String password) {
    if (playerUuid == null) {
      throw new ServiceException("bad_request", "Player UUID is required");
    }
    validateCredentials(playerName, password);

    InGamePasswordResult result = databaseManager.inTransaction(connection ->
        upsertPlayerAccount(connection, playerUuid, playerName.trim(), password));
    logoutAllSessions(result.userId());
    return result;
  }

  void logout(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    databaseManager.withConnection(connection -> {
      String sql = "DELETE FROM web_sessions WHERE token = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, token.trim());
        statement.executeUpdate();
      }
      return null;
    });
  }

  void logoutAllSessions(long userId) {
    databaseManager.withConnection(connection -> {
      String sql = "DELETE FROM web_sessions WHERE user_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  Optional<AuthUser> findUserBySession(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return databaseManager.withConnection(connection -> readUserBySession(connection, token.trim()));
  }

  long createUserForAdminBootstrap(Connection connection, String username, String password)
      throws SQLException {
    validateCredentials(username, password);
    long userId = createUser(connection, username.trim(), password, STATE_ACTIVE, null);
    ensureWalletExists(connection, userId);
    return userId;
  }

  void resetPassword(long userId, String newPassword) {
    validatePassword(newPassword);
    databaseManager.withConnection(connection -> {
      String salt = passwordHasher.newSalt();
      String hash = passwordHasher.hash(newPassword, salt);
      String sql = "UPDATE web_users SET password_hash = ?, password_salt = ?, auth_state = ? "
          + "WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, hash);
        statement.setString(2, salt);
        statement.setString(3, STATE_ACTIVE);
        statement.setLong(4, userId);
        int updated = statement.executeUpdate();
        if (updated == 0) {
          throw new ServiceException("user_missing", "User not found");
        }
      }
      return null;
    });
  }

  private InGamePasswordResult upsertPlayerAccount(
      Connection connection,
      UUID playerUuid,
      String playerName,
      String password) throws SQLException {
    UserAccount existingByUuid = readUserByBoundUuidForUpdate(connection, playerUuid);
    UserAccount existingByUsername = readUserByUsernameForUpdate(connection, playerName);

    if (existingByUuid != null
        && existingByUsername != null
        && existingByUuid.userId() != existingByUsername.userId()) {
      throw new ServiceException("username_exists", "Username already exists");
    }
    if (existingByUuid == null
        && existingByUsername != null
        && existingByUsername.boundUuid() != null
        && !playerUuid.equals(existingByUsername.boundUuid())) {
      throw new ServiceException("username_exists", "Username already exists");
    }

    String salt = passwordHasher.newSalt();
    String hash = passwordHasher.hash(password, salt);
    UserAccount target = existingByUuid != null ? existingByUuid : existingByUsername;

    if (target == null) {
      long userId = createUser(connection, playerName, password, STATE_ACTIVE, playerUuid);
      ensureWalletExists(connection, userId);
      return new InGamePasswordResult(userId, playerName, true);
    }

    String sql = "UPDATE web_users SET username = ?, password_hash = ?, password_salt = ?, "
        + "bound_uuid = ?, auth_state = ? WHERE id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, playerName);
      statement.setString(2, hash);
      statement.setString(3, salt);
      statement.setString(4, playerUuid.toString());
      statement.setString(5, STATE_ACTIVE);
      statement.setLong(6, target.userId());
      statement.executeUpdate();
    }
    ensureWalletExists(connection, target.userId());
    return new InGamePasswordResult(target.userId(), playerName, false);
  }

  private void validateCredentials(String username, String password) {
    validateUsername(username);
    validatePassword(password);
  }

  private void validateUsername(String username) {
    if (username == null || !username.matches(USERNAME_PATTERN)) {
      throw new ServiceException(
          "invalid_username",
          "Username must be 3-32 letters, numbers, or underscore");
    }
  }

  private void validatePassword(String password) {
    if (password == null || password.length() < 8 || password.length() > 64) {
      throw new ServiceException(
          "invalid_password",
          "Password length must be between 8 and 64");
    }
  }

  private long createUser(
      Connection connection,
      String username,
      String password,
      String state,
      UUID boundUuid) throws SQLException {
    if (userExists(connection, username)) {
      throw new ServiceException("username_exists", "Username already exists");
    }

    String salt = passwordHasher.newSalt();
    String hash = passwordHasher.hash(password, salt);
    String insertUserSql = "INSERT INTO web_users "
        + "(username, password_hash, password_salt, auth_state, bound_uuid) "
        + "VALUES (?, ?, ?, ?, ?)";

    try (PreparedStatement statement =
             connection.prepareStatement(insertUserSql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, username);
      statement.setString(2, hash);
      statement.setString(3, salt);
      statement.setString(4, state);
      statement.setString(5, boundUuid == null ? null : boundUuid.toString());
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated user id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private void ensureWalletExists(Connection connection, long userId) throws SQLException {
    String insertWalletSql = sqlProvider.insertWalletIfMissingSql();
    try (PreparedStatement statement = connection.prepareStatement(insertWalletSql)) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }

  private boolean userExists(Connection connection, String username) throws SQLException {
    String sql = "SELECT id FROM web_users WHERE username = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private UserAccount readUserByUsernameForUpdate(Connection connection, String username)
      throws SQLException {
    String sql =
        "SELECT id, username, bound_uuid FROM web_users WHERE username = ?" + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return toUserAccount(resultSet);
      }
    }
  }

  private UserAccount readUserByBoundUuidForUpdate(Connection connection, UUID playerUuid)
      throws SQLException {
    String sql =
        "SELECT id, username, bound_uuid FROM web_users WHERE bound_uuid = ?" + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, playerUuid.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return toUserAccount(resultSet);
      }
    }
  }

  private UserAccount toUserAccount(ResultSet resultSet) throws SQLException {
    String boundUuidRaw = resultSet.getString("bound_uuid");
    UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
    return new UserAccount(
        resultSet.getLong("id"),
        resultSet.getString("username"),
        boundUuid);
  }

  private long verifyUser(Connection connection, String identifier, String password)
      throws SQLException {
    String normalizedIdentifier = identifier.trim();
    String uuidText = tryParseUuid(normalizedIdentifier)
        .map(UUID::toString)
        .orElse(null);

    String sql = "SELECT id, password_hash, password_salt "
        + "FROM web_users "
        + "WHERE auth_state = ? AND (username = ? OR bound_uuid = ?) "
        + "LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, STATE_ACTIVE);
      statement.setString(2, normalizedIdentifier);
      statement.setString(3, uuidText);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("invalid_credentials", "Invalid username/uuid or password");
        }
        String hash = resultSet.getString("password_hash");
        String salt = resultSet.getString("password_salt");
        if (!passwordHasher.verify(password, salt, hash)) {
          throw new ServiceException("invalid_credentials", "Invalid username/uuid or password");
        }
        return resultSet.getLong("id");
      }
    }
  }

  private Optional<UUID> tryParseUuid(String identifier) {
    try {
      return Optional.of(UUID.fromString(identifier));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private AuthResult createSession(long userId) {
    String token = randomToken(settingsSupplier.get().accessTokenLength());
    LocalDateTime expiresAt =
        LocalDateTime.now().plusHours(Math.max(1, settingsSupplier.get().sessionExpireHours()));
    databaseManager.withConnection(connection -> {
      String sql = "INSERT INTO web_sessions (token, user_id, expires_at) VALUES (?, ?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, token);
        statement.setLong(2, userId);
        statement.setTimestamp(3, Timestamp.valueOf(expiresAt));
        statement.executeUpdate();
        return null;
      }
    });

    AuthUser user = findUserBySession(token)
        .orElseThrow(() -> new IllegalStateException("Session created but user cannot be loaded"));
    return new AuthResult(user, token, expiresAt);
  }

  private String randomToken(int requiredLength) {
    int normalizedLength = Math.max(24, requiredLength);
    int bytes = (int) Math.ceil(normalizedLength * 0.75D);
    byte[] data = new byte[bytes];
    secureRandom.nextBytes(data);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    if (token.length() > normalizedLength) {
      return token.substring(0, normalizedLength);
    }
    return token;
  }

  private Optional<AuthUser> readUserBySession(Connection connection, String token) throws SQLException {
    String sql = "SELECT u.id, u.username, u.bound_uuid "
        + "FROM web_sessions s "
        + "JOIN web_users u ON u.id = s.user_id "
        + "WHERE s.token = ? AND s.expires_at > CURRENT_TIMESTAMP AND u.auth_state = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, token);
      statement.setString(2, STATE_ACTIVE);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        String boundUuidRaw = resultSet.getString("bound_uuid");
        UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
        return Optional.of(new AuthUser(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            boundUuid));
      }
    }
  }

  record AuthUser(long id, String username, UUID boundUuid) {
  }

  record AuthResult(AuthUser user, String sessionToken, LocalDateTime expiresAt) {
  }

  record InGamePasswordResult(long userId, String username, boolean created) {
  }

  private record UserAccount(long userId, String username, UUID boundUuid) {
  }
}

