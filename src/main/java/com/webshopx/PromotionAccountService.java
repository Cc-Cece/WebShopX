package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Transactional budget/stock ledger. */
class PromotionAccountService {
  private final DatabaseManager databaseManager;
  PromotionAccountService(DatabaseManager databaseManager) { this.databaseManager = databaseManager; }

  void consume(Connection connection, long accountId, long amount, long count, String bizType, String bizId)
      throws SQLException {
    if (amount < 0 || count < 0) throw new ServiceException("invalid_account_delta", "Account delta cannot be negative");
    if (ledgerExists(connection, accountId, bizType, bizId)) return;
    String sql = "UPDATE promotion_accounts SET consumed_amount = consumed_amount + ?, consumed_count = consumed_count + ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND (limit_amount IS NULL OR consumed_amount + reserved_amount + ? <= limit_amount) AND (limit_count IS NULL OR consumed_count + reserved_count + ? <= limit_count)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, amount); statement.setLong(2, count); statement.setLong(3, accountId);
      statement.setLong(4, amount); statement.setLong(5, count);
      if (statement.executeUpdate() != 1) throw new ServiceException("BUDGET_EXHAUSTED", "Promotion budget is exhausted");
    }
    addLedger(connection, accountId, amount, count, bizType, bizId, false);
  }

  void refund(Connection connection, long accountId, long amount, long count, String bizType, String bizId)
      throws SQLException {
    if (ledgerExists(connection, accountId, bizType, bizId)) return;
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE promotion_accounts SET refunded_amount = refunded_amount + ?, refunded_count = refunded_count + ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND consumed_amount >= refunded_amount + ? AND consumed_count >= refunded_count + ?")) {
      statement.setLong(1, amount); statement.setLong(2, count); statement.setLong(3, accountId);
      statement.setLong(4, amount); statement.setLong(5, count);
      if (statement.executeUpdate() != 1) throw new ServiceException("funding_reconciliation_failed", "Refund exceeds consumed promotion funding");
    }
    addLedger(connection, accountId, amount, count, bizType, bizId, true);
  }

  private boolean ledgerExists(Connection c, long id, String type, String biz) throws SQLException { try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM promotion_account_ledger WHERE account_id=? AND biz_type=? AND biz_id=?")){s.setLong(1,id);s.setString(2,type);s.setString(3,biz);try(ResultSet r=s.executeQuery()){return r.next();}} }
  private void addLedger(Connection c,long id,long amount,long count,String type,String biz,boolean refund)throws SQLException{try(PreparedStatement s=c.prepareStatement("INSERT INTO promotion_account_ledger (account_id, consumed_delta, refunded_delta, biz_type, biz_id) VALUES (?, ?, ?, ?, ?)")){s.setLong(1,id);s.setLong(2,refund?0:amount);s.setLong(3,refund?amount:0);s.setString(4,type);s.setString(5,biz);s.executeUpdate();}}
}
