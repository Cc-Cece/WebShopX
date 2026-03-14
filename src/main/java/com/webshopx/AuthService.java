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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

class AuthService {
  private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{3,32}$";
  private static final String STATE_ACTIVE = "ACTIVE";
  private static final String STATE_PENDING_BIND = "PENDING_BIND";
  private static final String STATE_PENDING_PASSWORD = "PENDING_PASSWORD";
  private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;
  private final PasswordHasher passwordHasher;
  private final SecureRandom secureRandom;

  AuthService(DatabaseManager databaseManager, Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
    this.passwordHasher = new PasswordHasher();
    this.secureRandom = new SecureRandom();
  }

  AuthResult register(String username, String password) {
    validateCredentials(username, password);
    long userId = databaseManager.inTransaction(connection -> {
      long createdUserId = createUser(
          connection,
          username,
          password,
          STATE_ACTIVE);
      ensureWallet(connection, createdUserId);
      return createdUserId;
    });
    return createSession(userId);
  }

  RegisterStartResult startRegistration(String username) {
    validateUsername(username);
    int expireMinutes = Math.max(1, settingsSupplier.get().bindRequestExpireMinutes());
    LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(expireMinutes);

    return databaseManager.inTransaction(connection -> {
      if (userExists(connection, username)) {
        throw new ServiceException("username_exists", "Username already exists");
      }

      long userId = createPendingUser(connection, username);
      String bindCode = createBindCode(connection, userId, expiresAt);
      return new RegisterStartResult(username, bindCode, expireMinutes);
    });
  }

  RegisterStatusResult queryRegistrationStatus(String bindCode) {
    if (bindCode == null || bindCode.isBlank()) {
      return new RegisterStatusResult(RegistrationStatus.INVALID_CODE, null, null);
    }

    return databaseManager.withConnection(connection -> {
      RegistrationRow row = readRegistrationRow(connection, bindCode);
      if (row == null) {
        return new RegisterStatusResult(RegistrationStatus.INVALID_CODE, null, null);
      }

      LocalDateTime now = LocalDateTime.now();
      if (!row.used()) {
        if (row.expiresAt().isBefore(now)) {
          return new RegisterStatusResult(
              RegistrationStatus.EXPIRED,
              row.username(),
              row.boundUuid());
        }
        return new RegisterStatusResult(
            RegistrationStatus.WAITING_BIND,
            row.username(),
            row.boundUuid());
      }

      if (row.boundUuid() == null) {
        return new RegisterStatusResult(
            RegistrationStatus.WAITING_BIND,
            row.username(),
            null);
      }

      if (STATE_ACTIVE.equals(row.authState())) {
        return new RegisterStatusResult(
            RegistrationStatus.COMPLETED,
            row.username(),
            row.boundUuid());
      }
      if (STATE_PENDING_PASSWORD.equals(row.authState())) {
        return new RegisterStatusResult(
            RegistrationStatus.NEED_PASSWORD,
            row.username(),
            row.boundUuid());
      }

      return new RegisterStatusResult(
          RegistrationStatus.WAITING_BIND,
          row.username(),
          row.boundUuid());
    });
  }

  AuthResult finishRegistration(String bindCode, String password) {
    validatePassword(password);
    if (bindCode == null || bindCode.isBlank()) {
      throw new ServiceException("invalid_code", "Bind code is required");
    }

    long userId = databaseManager.inTransaction(connection -> {
      RegistrationRow row = readRegistrationRowForUpdate(connection, bindCode);
      if (row == null) {
        throw new ServiceException("invalid_code", "Bind code does not exist");
      }
      if (!row.used() || row.boundUuid() == null) {
        throw new ServiceException("wait_bind", "Minecraft account is not bound yet");
      }
      if (!STATE_PENDING_PASSWORD.equals(row.authState())) {
        if (STATE_ACTIVE.equals(row.authState())) {
          throw new ServiceException("already_completed", "Registration has already completed");
        }
        throw new ServiceException("invalid_state", "Registration state is invalid");
      }

      String salt = passwordHasher.newSalt();
      String hash = passwordHasher.hash(password, salt);
      String sql = "UPDATE web_users SET password_hash = ?, password_salt = ?, auth_state = ? "
          + "WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, hash);
        statement.setString(2, salt);
        statement.setString(3, STATE_ACTIVE);
        statement.setLong(4, row.userId());
        statement.executeUpdate();
      }
      return row.userId();
    });

    return createSession(userId);
  }

  AuthResult login(String identifier, String password) {
    validatePassword(password);
    if (identifier == null || identifier.isBlank()) {
      throw new ServiceException("invalid_identifier", "Username or UUID is required");
    }

    long userId = databaseManager.withConnection(connection -> verifyUser(connection, identifier, password));
    return createSession(userId);
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
    long userId = createUser(connection, username, password, STATE_ACTIVE);
    ensureWallet(connection, userId);
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

  private long createPendingUser(Connection connection, String username) throws SQLException {
    String tempPassword = randomToken(24);
    String salt = passwordHasher.newSalt();
    String hash = passwordHasher.hash(tempPassword, salt);

    String insertUserSql = "INSERT INTO web_users "
        + "(username, password_hash, password_salt, auth_state) "
        + "VALUES (?, ?, ?, ?)";
    long userId;
    try (PreparedStatement statement =
             connection.prepareStatement(insertUserSql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, username);
      statement.setString(2, hash);
      statement.setString(3, salt);
      statement.setString(4, STATE_PENDING_BIND);
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated user id");
        }
        userId = keyResult.getLong(1);
      }
    }

    ensureWallet(connection, userId);
    return userId;
  }

  private long createUser(
      Connection connection,
      String username,
      String password,
      String state) throws SQLException {
    if (userExists(connection, username)) {
      throw new ServiceException("username_exists", "Username already exists");
    }

    String salt = passwordHasher.newSalt();
    String hash = passwordHasher.hash(password, salt);
    String insertUserSql = "INSERT INTO web_users "
        + "(username, password_hash, password_salt, auth_state) "
        + "VALUES (?, ?, ?, ?)";

    try (PreparedStatement statement =
             connection.prepareStatement(insertUserSql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, username);
      statement.setString(2, hash);
      statement.setString(3, salt);
      statement.setString(4, state);
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated user id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private void ensureWallet(Connection connection, long userId) throws SQLException {
    String insertWalletSql = "INSERT INTO wallets (user_id) VALUES (?)";
    try (PreparedStatement statement = connection.prepareStatement(insertWalletSql)) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }

  private String createBindCode(Connection connection, long userId, LocalDateTime expiresAt)
      throws SQLException {
    for (int attempt = 0; attempt < 5; attempt++) {
      String bindCode = randomBindCode(8);
      if (insertBindRequest(connection, bindCode, userId, expiresAt)) {
        return bindCode;
      }
    }
    throw new IllegalStateException("Could not allocate unique bind code");
  }

  private boolean insertBindRequest(
      Connection connection,
      String bindCode,
      long userId,
      LocalDateTime expiresAt) throws SQLException {
    String sql = "INSERT INTO bind_requests (bind_code, user_id, expires_at, used) "
        + "VALUES (?, ?, ?, FALSE) "
        + "ON DUPLICATE KEY UPDATE bind_code = bind_code";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, bindCode);
      statement.setLong(2, userId);
      statement.setTimestamp(3, Timestamp.valueOf(expiresAt));
      return statement.executeUpdate() == 1;
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

  private String randomBindCode(int length) {
    StringBuilder builder = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      int pointer = secureRandom.nextInt(CODE_ALPHABET.length());
      builder.append(CODE_ALPHABET.charAt(pointer));
    }
    return builder.toString();
  }

  private Optional<AuthUser> readUserBySession(Connection connection, String token) throws SQLException {
    String sql = "SELECT u.id, u.username, u.bound_uuid "
        + "FROM web_sessions s "
        + "JOIN web_users u ON u.id = s.user_id "
        + "WHERE s.token = ? AND s.expires_at > NOW() AND u.auth_state = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, token);
      statement.setString(2, STATE_ACTIVE);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        String boundUuidRaw = resultSet.getString("bound_uuid");
        UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
        return Optional.of(new AuthUser(resultSet.getLong("id"), resultSet.getString("username"),
            boundUuid));
      }
    }
  }

  private RegistrationRow readRegistrationRow(Connection connection, String bindCode)
      throws SQLException {
    String sql = "SELECT br.user_id, br.expires_at, br.used, u.username, u.bound_uuid, u.auth_state "
        + "FROM bind_requests br "
        + "JOIN web_users u ON u.id = br.user_id "
        + "WHERE br.bind_code = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, bindCode.trim().toUpperCase(Locale.ROOT));
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return toRegistrationRow(resultSet);
      }
    }
  }

  private RegistrationRow readRegistrationRowForUpdate(Connection connection, String bindCode)
      throws SQLException {
    String sql = "SELECT br.user_id, br.expires_at, br.used, u.username, u.bound_uuid, u.auth_state "
        + "FROM bind_requests br "
        + "JOIN web_users u ON u.id = br.user_id "
        + "WHERE br.bind_code = ? FOR UPDATE";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, bindCode.trim().toUpperCase(Locale.ROOT));
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return toRegistrationRow(resultSet);
      }
    }
  }

  private RegistrationRow toRegistrationRow(ResultSet resultSet) throws SQLException {
    String boundUuidRaw = resultSet.getString("bound_uuid");
    UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
    return new RegistrationRow(
        resultSet.getLong("user_id"),
        resultSet.getTimestamp("expires_at").toLocalDateTime(),
        resultSet.getBoolean("used"),
        resultSet.getString("username"),
        boundUuid,
        resultSet.getString("auth_state"));
  }

  record AuthUser(long id, String username, UUID boundUuid) {
  }

  record AuthResult(AuthUser user, String sessionToken, LocalDateTime expiresAt) {
  }

  record RegisterStartResult(String username, String bindCode, int expiresInMinutes) {
  }

  enum RegistrationStatus {
    WAITING_BIND,
    NEED_PASSWORD,
    COMPLETED,
    EXPIRED,
    INVALID_CODE
  }

  record RegisterStatusResult(RegistrationStatus status, String username, UUID boundUuid) {
  }

  private record RegistrationRow(
      long userId,
      LocalDateTime expiresAt,
      boolean used,
      String username,
      UUID boundUuid,
      String authState) {
  }
}
