package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

class WalletService {
  private final DatabaseManager databaseManager;
  private final Supplier<PluginSettings> settingsSupplier;

  WalletService(DatabaseManager databaseManager, Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.settingsSupplier = settingsSupplier;
  }

  WalletBalance getBalance(long userId) {
    return databaseManager.withConnection(connection -> {
      ensureWallet(connection, userId);
      return readBalance(connection, userId, false);
    });
  }

  WalletBalance exchange(
      long userId,
      CurrencyType fromCurrency,
      CurrencyType toCurrency,
      long amount,
      String idempotencyKey) {
    if (fromCurrency == toCurrency) {
      throw new ServiceException("invalid_exchange", "Exchange currency direction is invalid");
    }
    if (amount <= 0) {
      throw new ServiceException("invalid_amount", "Exchange amount must be positive");
    }

    PluginSettings.ExchangeDirection direction =
        settingsSupplier.get().exchangeSettings().direction(fromCurrency, toCurrency);
    if (!direction.enabled()) {
      throw new ServiceException("exchange_disabled", "Exchange direction is disabled");
    }

    long converted = (long) Math.floor(amount * direction.ratio());
    if (converted <= 0) {
      throw new ServiceException("invalid_ratio", "Exchange ratio results in zero output");
    }

    String outBizId = idempotencyKey + ":out";
    String inBizId = idempotencyKey + ":in";
    return databaseManager.inTransaction(connection -> {
      ensureWallet(connection, userId);
      boolean outApplied = applyDelta(
          connection,
          userId,
          fromCurrency,
          -amount,
          "EXCHANGE_OUT",
          outBizId,
          true);
      if (!outApplied) {
        return readBalance(connection, userId, false);
      }
      applyDelta(connection, userId, toCurrency, converted, "EXCHANGE_IN", inBizId, false);
      return readBalance(connection, userId, false);
    });
  }

  WalletBalance adjustBalance(long userId, CurrencyType currency, long delta, String reason, String bizId) {
    if (currency == null) {
      throw new ServiceException("invalid_currency", "Currency is required");
    }
    if (delta == 0) {
      throw new ServiceException("invalid_amount", "Delta cannot be zero");
    }
    String normalizedReason = reason == null || reason.isBlank() ? "ADMIN_ADJUST" : reason.trim();
    return databaseManager.inTransaction(connection -> {
      ensureWallet(connection, userId);
      boolean applied = applyDelta(
          connection,
          userId,
          currency,
          delta,
          normalizedReason,
          bizId,
          delta < 0);
      if (!applied) {
        return readBalance(connection, userId, false);
      }
      return readBalance(connection, userId, false);
    });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Column name comes from enum and cannot be user controlled")
  boolean applyDelta(
      Connection connection,
      long userId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      boolean enforceBalance) throws SQLException {
    ensureWallet(connection, userId);
    long walletId = readWalletId(connection, userId, true);

    if (!insertLedger(connection, walletId, currency, delta, bizType, bizId)) {
      return false;
    }

    String column = currency.columnName();
    String sql = "UPDATE wallets SET " + column + " = " + column + " + ? WHERE id = ?";
    if (enforceBalance) {
      sql += " AND " + column + " + ? >= 0";
    }

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, delta);
      statement.setLong(2, walletId);
      if (enforceBalance) {
        statement.setLong(3, delta);
      }
      int updated = statement.executeUpdate();
      if (updated == 0) {
        throw new ServiceException("insufficient_funds", "Wallet balance is insufficient");
      }
    }

    return true;
  }

  private boolean insertLedger(
      Connection connection,
      long walletId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId) throws SQLException {
    String sql = """
        INSERT INTO wallet_ledger (wallet_id, currency, delta, biz_type, biz_id)
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE id = id
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, walletId);
      statement.setString(2, currency.name());
      statement.setLong(3, delta);
      statement.setString(4, bizType);
      statement.setString(5, bizId);
      return statement.executeUpdate() == 1;
    }
  }

  private void ensureWallet(Connection connection, long userId) throws SQLException {
    String sql = """
        INSERT INTO wallets (user_id)
        VALUES (?)
        ON DUPLICATE KEY UPDATE user_id = user_id
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private long readWalletId(Connection connection, long userId, boolean forUpdate) throws SQLException {
    String lockClause = forUpdate ? " FOR UPDATE" : "";
    String sql = "SELECT id FROM wallets WHERE user_id = ?" + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("wallet_missing", "Wallet does not exist");
        }
        return resultSet.getLong("id");
      }
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private WalletBalance readBalance(Connection connection, long userId, boolean forUpdate)
      throws SQLException {
    String lockClause = forUpdate ? " FOR UPDATE" : "";
    String sql = "SELECT shop_coin, game_coin FROM wallets WHERE user_id = ?" + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("wallet_missing", "Wallet does not exist");
        }
        return new WalletBalance(resultSet.getLong("shop_coin"), resultSet.getLong("game_coin"));
      }
    }
  }

  record WalletBalance(long shopCoin, long gameCoin) {
  }
}
