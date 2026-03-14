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
    long userId = databaseManager.inTransaction(connection -> createUser(connection, username, password));
    return createSession(userId);
  }

  AuthResult login(String username, String password) {
    long userId = databaseManager.withConnection(connection -> verifyUser(connection, username, password));
    return createSession(userId);
  }

  Optional<AuthUser> findUserBySession(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return databaseManager.withConnection(connection -> readUserBySession(connection, token.trim()));
  }

  private void validateCredentials(String username, String password) {
    if (username == null || !username.matches(USERNAME_PATTERN)) {
      throw new ServiceException("invalid_username", "Username must be 3-32 letters, numbers, or underscore");
    }
    if (password == null || password.length() < 8 || password.length() > 64) {
      throw new ServiceException("invalid_password", "Password length must be between 8 and 64");
    }
  }

  private long createUser(Connection connection, String username, String password) throws SQLException {
    if (userExists(connection, username)) {
      throw new ServiceException("username_exists", "Username already exists");
    }

    String salt = passwordHasher.newSalt();
    String hash = passwordHasher.hash(password, salt);
    String insertUserSql = """
        INSERT INTO web_users (username, password_hash, password_salt)
        VALUES (?, ?, ?)
        """;

    long userId;
    try (PreparedStatement statement =
             connection.prepareStatement(insertUserSql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, username);
      statement.setString(2, hash);
      statement.setString(3, salt);
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated user id");
        }
        userId = keyResult.getLong(1);
      }
    }

    String insertWalletSql = "INSERT INTO wallets (user_id) VALUES (?)";
    try (PreparedStatement walletStatement = connection.prepareStatement(insertWalletSql)) {
      walletStatement.setLong(1, userId);
      walletStatement.executeUpdate();
    }
    return userId;
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

  private long verifyUser(Connection connection, String username, String password) throws SQLException {
    String sql = """
        SELECT id, password_hash, password_salt
        FROM web_users
        WHERE username = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("invalid_credentials", "Invalid username or password");
        }
        String hash = resultSet.getString("password_hash");
        String salt = resultSet.getString("password_salt");
        if (!passwordHasher.verify(password, salt, hash)) {
          throw new ServiceException("invalid_credentials", "Invalid username or password");
        }
        return resultSet.getLong("id");
      }
    }
  }

  private AuthResult createSession(long userId) {
    String token = randomToken(settingsSupplier.get().accessTokenLength());
    LocalDateTime expiresAt =
        LocalDateTime.now().plusHours(Math.max(1, settingsSupplier.get().sessionExpireHours()));
    databaseManager.withConnection(connection -> {
      String sql = """
          INSERT INTO web_sessions (token, user_id, expires_at)
          VALUES (?, ?, ?)
          """;
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
    String sql = """
        SELECT u.id, u.username, u.bound_uuid
        FROM web_sessions s
        JOIN web_users u ON u.id = s.user_id
        WHERE s.token = ? AND s.expires_at > NOW()
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, token);
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

  record AuthUser(long id, String username, UUID boundUuid) {
  }

  record AuthResult(AuthUser user, String sessionToken, LocalDateTime expiresAt) {
  }
}
