package com.webshopx;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

class RedeemCodeService {
  private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final WalletService walletService;
  private final SecureRandom secureRandom;

  RedeemCodeService(DatabaseManager databaseManager, WalletService walletService) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.walletService = walletService;
    this.secureRandom = new SecureRandom();
  }

  String createCode(
      long shopCoin,
      long gameCoin,
      int maxUses,
      int perUserMaxUses,
      Integer expiresInMinutes,
      String preferredCode) {
    if (shopCoin <= 0 && gameCoin <= 0) {
      throw new ServiceException("invalid_amount", "At least one currency amount must be positive");
    }

    int normalizedMaxUses = Math.max(1, maxUses);
    int normalizedPerUserMaxUses = Math.max(1, perUserMaxUses);
    LocalDateTime expiresAt = null;
    if (expiresInMinutes != null && expiresInMinutes > 0) {
      expiresAt = LocalDateTime.now().plusMinutes(expiresInMinutes);
    }

    if (preferredCode != null && !preferredCode.isBlank()) {
      String fixedCode = normalizeCode(preferredCode);
      if (!insertCode(
          fixedCode,
          shopCoin,
          gameCoin,
          normalizedMaxUses,
          normalizedPerUserMaxUses,
          expiresAt)) {
        throw new ServiceException("code_exists", "Redeem code already exists");
      }
      return fixedCode;
    }

    for (int attempt = 0; attempt < 6; attempt++) {
      String generated = randomCode(12);
      if (insertCode(
          generated,
          shopCoin,
          gameCoin,
          normalizedMaxUses,
          normalizedPerUserMaxUses,
          expiresAt)) {
        return generated;
      }
    }
    throw new IllegalStateException("Could not generate a unique redeem code");
  }

  RedeemResult redeem(long userId, String rawCode) {
    String code = normalizeCode(rawCode);
    RedeemStatus status = databaseManager.inTransaction(connection -> redeemInTransaction(connection, userId, code));
    WalletService.WalletBalance balance = walletService.getBalance(userId);
    return new RedeemResult(status, balance);
  }

  List<RedeemCodeView> listCodes(int requestedLimit) {
    int limit = Math.min(Math.max(1, requestedLimit), 500);
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT code, shop_coin, game_coin, max_uses, per_user_max_uses,
                 used_count, expires_at, active, created_at
          FROM redeem_codes
          ORDER BY created_at DESC
          LIMIT ?
          """;
      List<RedeemCodeView> results = new java.util.ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, limit);
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            java.sql.Timestamp expires = resultSet.getTimestamp("expires_at");
            java.sql.Timestamp created = resultSet.getTimestamp("created_at");
            results.add(new RedeemCodeView(
                resultSet.getString("code"),
                resultSet.getLong("shop_coin"),
                resultSet.getLong("game_coin"),
                resultSet.getInt("max_uses"),
                resultSet.getInt("per_user_max_uses"),
                resultSet.getInt("used_count"),
                expires == null ? null : expires.toLocalDateTime(),
                resultSet.getBoolean("active"),
                created == null ? null : created.toLocalDateTime()));
          }
        }
      }
      return results;
    });
  }

  private RedeemStatus redeemInTransaction(Connection connection, long userId, String code)
      throws SQLException {
    RedeemRow row = readCodeForUpdate(connection, code);
    if (row == null || !row.active()) {
      return RedeemStatus.INVALID_CODE;
    }
    if (row.expiresAt() != null && row.expiresAt().isBefore(LocalDateTime.now())) {
      return RedeemStatus.EXPIRED;
    }
    if (row.usedCount() >= row.maxUses()) {
      return RedeemStatus.OUT_OF_STOCK;
    }

    int userUsedCount = readUserUsageForUpdate(connection, code, userId);
    if (userUsedCount >= row.perUserMaxUses()) {
      if (row.perUserMaxUses() <= 1) {
        return RedeemStatus.ALREADY_USED;
      }
      return RedeemStatus.USER_LIMIT_REACHED;
    }

    if (row.shopCoin() > 0) {
      walletService.applyDelta(
          connection,
          userId,
          CurrencyType.SHOP_COIN,
          row.shopCoin(),
          "REDEEM_CODE",
          code + ":shop",
          false);
    }
    if (row.gameCoin() > 0) {
      walletService.applyDelta(
          connection,
          userId,
          CurrencyType.GAME_COIN,
          row.gameCoin(),
          "REDEEM_CODE",
          code + ":game",
          false);
    }

    incrementUserUsage(connection, code, userId);

    String updateSql = "UPDATE redeem_codes SET used_count = used_count + 1 WHERE code = ?";
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      statement.setString(1, code);
      statement.executeUpdate();
    }
    return RedeemStatus.SUCCESS;
  }

  private boolean insertCode(
      String code,
      long shopCoin,
      long gameCoin,
      int maxUses,
      int perUserMaxUses,
      LocalDateTime expiresAt) {
    return databaseManager.withConnection(connection -> {
      String sql = sqlProvider.insertRedeemCodeIfAbsentSql();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, code);
        statement.setLong(2, shopCoin);
        statement.setLong(3, gameCoin);
        statement.setInt(4, maxUses);
        statement.setInt(5, perUserMaxUses);
        if (expiresAt == null) {
          statement.setTimestamp(6, null);
        } else {
          statement.setTimestamp(6, Timestamp.valueOf(expiresAt));
        }
        int updated = statement.executeUpdate();
        if (updated == 1) {
          return true;
        }
        return false;
      }
    });
  }

  private RedeemRow readCodeForUpdate(Connection connection, String code) throws SQLException {
    String sql =
        """
        SELECT shop_coin, game_coin, max_uses, per_user_max_uses, used_count, expires_at, active
        FROM redeem_codes
        WHERE code = ?
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        Timestamp expiresAt = resultSet.getTimestamp("expires_at");
        LocalDateTime expires = expiresAt == null ? null : expiresAt.toLocalDateTime();
        return new RedeemRow(
            resultSet.getLong("shop_coin"),
            resultSet.getLong("game_coin"),
            resultSet.getInt("max_uses"),
            resultSet.getInt("per_user_max_uses"),
            resultSet.getInt("used_count"),
            expires,
            resultSet.getBoolean("active"));
      }
    }
  }

  private int readUserUsageForUpdate(Connection connection, String code, long userId) throws SQLException {
    String sql =
        """
        SELECT use_count
        FROM redeem_usage
        WHERE code = ? AND user_id = ?
        %s
        """
            .formatted(sqlProvider.forUpdateClause());
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, code);
      statement.setLong(2, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return 0;
        }
        return Math.max(0, resultSet.getInt("use_count"));
      }
    }
  }

  private void incrementUserUsage(Connection connection, String code, long userId) throws SQLException {
    String sql = sqlProvider.upsertRedeemUsageSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, code);
      statement.setLong(2, userId);
      statement.executeUpdate();
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

  private String normalizeCode(String rawCode) {
    if (rawCode == null || rawCode.isBlank()) {
      throw new ServiceException("invalid_code", "Redeem code is required");
    }
    String code = rawCode.trim().toUpperCase(Locale.ROOT);
    if (!code.matches("^[A-Z0-9_-]{4,32}$")) {
      throw new ServiceException("invalid_code", "Redeem code format is invalid");
    }
    return code;
  }

  private record RedeemRow(
      long shopCoin,
      long gameCoin,
      int maxUses,
      int perUserMaxUses,
      int usedCount,
      LocalDateTime expiresAt,
      boolean active) {
  }

  enum RedeemStatus {
    SUCCESS,
    INVALID_CODE,
    EXPIRED,
    OUT_OF_STOCK,
    ALREADY_USED,
    USER_LIMIT_REACHED
  }

  record RedeemResult(RedeemStatus status, WalletService.WalletBalance balance) {
  }

  record RedeemCodeView(
      String code,
      long shopCoin,
      long gameCoin,
      int maxUses,
      int perUserMaxUses,
      int usedCount,
      LocalDateTime expiresAt,
      boolean active,
      LocalDateTime createdAt) {
  }
}

