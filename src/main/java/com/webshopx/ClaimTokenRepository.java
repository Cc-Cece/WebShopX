package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Helpers for persisting claim tokens on orders and market trades.
 */
final class ClaimTokenRepository {
  private ClaimTokenRepository() {
  }

  static String ensureOrderToken(Connection connection, long orderId) throws SQLException {
    return ensureToken(
        connection,
        "SELECT claim_token FROM orders WHERE id = ?" + lockClause(connection),
        "UPDATE orders SET claim_token = ? WHERE id = ? AND (claim_token IS NULL OR claim_token = '')",
        orderId,
        ClaimTokenGenerator::newOrderToken,
        "order");
  }

  static void clearOrderToken(Connection connection, long orderId) throws SQLException {
    clearToken(connection, "UPDATE orders SET claim_token = NULL WHERE id = ?", orderId);
  }

  static String ensureMarketTradeToken(Connection connection, long tradeId) throws SQLException {
    return ensureToken(
        connection,
        "SELECT claim_token FROM market_trades WHERE id = ?" + lockClause(connection),
        "UPDATE market_trades SET claim_token = ? WHERE id = ? AND (claim_token IS NULL OR claim_token = '')",
        tradeId,
        ClaimTokenGenerator::newMarketToken,
        "market trade");
  }

  static void clearMarketTradeToken(Connection connection, long tradeId) throws SQLException {
    clearToken(connection, "UPDATE market_trades SET claim_token = NULL WHERE id = ?", tradeId);
  }

  private static String ensureToken(
      Connection connection,
      String selectSql,
      String updateSql,
      long id,
      java.util.concurrent.Callable<String> generator,
      String entityLabel) throws SQLException {
    String existing = null;
    try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
      statement.setLong(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("Missing " + entityLabel + " " + id + " when assigning claim token");
        }
        existing = resultSet.getString("claim_token");
      }
    }
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    for (int attempt = 0; attempt < 8; attempt++) {
      String token;
      try {
        token = generator.call();
      } catch (Exception exception) {
        throw new IllegalStateException("Failed to generate claim token", exception);
      }
      try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
        statement.setString(1, token);
        statement.setLong(2, id);
        int updated = statement.executeUpdate();
        if (updated > 0) {
          return token;
        }
      } catch (SQLException exception) {
        if (isDuplicateKey(exception)) {
          continue;
        }
        throw exception;
      }
    }
    throw new IllegalStateException("Failed to assign claim token for " + entityLabel + " " + id);
  }

  private static void clearToken(Connection connection, String sql, long id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  private static boolean isDuplicateKey(SQLException exception) {
    if (exception == null) {
      return false;
    }
    String state = exception.getSQLState();
    int errorCode = exception.getErrorCode();
    String message = exception.getMessage();
    if ("23000".equals(state) || errorCode == 1062) {
      return true;
    }
    return message != null && message.toLowerCase(Locale.ROOT).contains("duplicate");
  }

  private static String lockClause(Connection connection) throws SQLException {
    String productName = connection.getMetaData().getDatabaseProductName();
    if (productName == null) {
      return " FOR UPDATE";
    }
    return productName.toLowerCase(Locale.ROOT).contains("sqlite") ? "" : " FOR UPDATE";
  }
}
