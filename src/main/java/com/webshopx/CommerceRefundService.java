package com.webshopx;

import com.google.gson.Gson;
import com.webshopx.promotion.pricing.PricingEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Refunds frozen unit amounts; it never recalculates current promotions. */
class CommerceRefundService {
  private final DatabaseManager databaseManager;
  private final WalletService walletService;
  private final CouponService couponService;
  private final Gson gson = CommerceJson.create();

  CommerceRefundService(
      DatabaseManager databaseManager, WalletService walletService, CouponService couponService) {
    this.databaseManager = databaseManager;
    this.walletService = walletService;
    this.couponService = couponService;
  }

  RefundResult refund(long userId, String checkoutNo, long lineId, int quantity, String key) {
    if (quantity <= 0)
      throw new ServiceException("invalid_refund_quantity", "Refund quantity must be positive");
    return databaseManager.inTransaction(c -> refund(c, userId, checkoutNo, lineId, quantity, key));
  }

  private RefundResult refund(Connection c, long user, String no, long lineId, int qty, String key)
      throws SQLException {
    ExistingRefund existing = findExisting(c, user, key);
    if (existing != null)
      return new RefundResult("EXISTING", existing.amount(), existing.quantity(), false);
    Line line = lockLine(c, user, no, lineId);
    int remaining = line.quantity() - line.refundedQuantity();
    if (qty > remaining)
      throw new ServiceException("refund_exceeds_remaining", "Refund exceeds remaining quantity");
    List<PaymentUnit> units = readRefundUnits(c, lineId, qty);
    if (units.size() != qty)
      throw new ServiceException(
          "refund_reconciliation_failed", "Frozen payment units are incomplete");
    long amount = 0;
    for (PaymentUnit unit : units) amount = Math.addExact(amount, unit.finalAmount());
    long refundRequestId;
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO refund_requests"
                + " (user_id,order_ref,idempotency_key,status,refund_amount,refund_quantity,completed_at)"
                + " VALUES (?,?,?,'COMPLETED',?,?,CURRENT_TIMESTAMP)",
            java.sql.Statement.RETURN_GENERATED_KEYS)) {
      s.setLong(1, user);
      s.setString(2, no + ":" + lineId);
      s.setString(3, key);
      s.setLong(4, amount);
      s.setInt(5, qty);
      s.executeUpdate();
      try (ResultSet keys = s.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("Refund request id missing");
        refundRequestId = keys.getLong(1);
      }
    }
    walletService.applyDelta(
        c,
        user,
        CurrencyType.valueOf(line.currency()),
        amount,
        "CHECKOUT_REFUND",
        no + ":" + key,
        false);
    try (PreparedStatement s =
        c.prepareStatement(
            "UPDATE checkout_order_lines SET"
                + " refunded_quantity=refunded_quantity+?,refunded_amount=refunded_amount+?,status=CASE"
                + " WHEN refunded_quantity+?=quantity THEN 'REFUNDED' ELSE 'PARTIALLY_REFUNDED' END"
                + " WHERE id=? AND refunded_amount+?<=final_amount")) {
      s.setInt(1, qty);
      s.setLong(2, amount);
      s.setInt(3, qty);
      s.setLong(4, lineId);
      s.setLong(5, amount);
      if (s.executeUpdate() != 1)
        throw new ServiceException("refund_reconciliation_failed", "Refund invariant failed");
    }
    markUnitsRefunded(c, units);
    insertAdjustment(c, refundRequestId, lineId, amount, line.currency());
    boolean full = isCheckoutFullyRefunded(c, line.checkoutId());
    if (full) {
      try (PreparedStatement s =
          c.prepareStatement(
              "UPDATE checkout_orders SET status='REFUNDED',updated_at=CURRENT_TIMESTAMP WHERE"
                  + " id=?")) {
        s.setLong(1, line.checkoutId());
        s.executeUpdate();
      }
      returnCoupons(c, line.checkoutId(), no + ":" + key);
    } else {
      try (PreparedStatement s =
          c.prepareStatement(
              "UPDATE checkout_orders SET status='PARTIALLY_REFUNDED',updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?")) {
        s.setLong(1, line.checkoutId());
        s.executeUpdate();
      }
    }
    return new RefundResult("CREATED", amount, qty, full);
  }

  private void returnCoupons(Connection c, long checkoutId, String refundId) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT snapshot_json FROM checkout_price_snapshots WHERE checkout_id=?")) {
      s.setLong(1, checkoutId);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return;
        CheckoutQuoteService.Quote q =
            gson.fromJson(r.getString(1), CheckoutQuoteService.Quote.class);
        for (PricingEngine.Application a : q.pricing().applications())
          if (a.userCouponId() != null)
            couponService.returnAfterFullRefund(c, a.userCouponId(), checkoutId, refundId);
      }
    }
  }

  private Line lockLine(Connection c, long user, String no, long lineId) throws SQLException {
    String sql =
        "SELECT l.id,l.quantity,l.refunded_quantity,l.final_amount,l.currency,g.checkout_id FROM"
            + " checkout_order_lines l JOIN checkout_order_groups g ON g.id=l.group_id JOIN"
            + " checkout_orders o ON o.id=g.checkout_id WHERE o.user_id=? AND o.checkout_no=? AND"
            + " l.id=?"
            + databaseManager.sqlProvider().forUpdateClause();
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setLong(1, user);
      s.setString(2, no);
      s.setLong(3, lineId);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next())
          throw new ServiceException("checkout_line_missing", "Checkout line is not visible");
        return new Line(
            lineId,
            r.getInt("quantity"),
            r.getInt("refunded_quantity"),
            r.getLong("final_amount"),
            r.getString("currency"),
            r.getLong("checkout_id"));
      }
    }
  }

  private ExistingRefund findExisting(Connection c, long user, String key) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT refund_amount,refund_quantity FROM refund_requests WHERE user_id=? AND"
                + " idempotency_key=?")) {
      s.setLong(1, user);
      s.setString(2, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? new ExistingRefund(r.getLong(1), r.getInt(2)) : null;
      }
    }
  }

  private List<PaymentUnit> readRefundUnits(Connection c, long lineId, int quantity)
      throws SQLException {
    java.util.ArrayList<PaymentUnit> values = new java.util.ArrayList<>();
    String sql =
        "SELECT id,final_amount FROM checkout_line_payment_units WHERE checkout_line_id=? AND"
            + " refunded=FALSE ORDER BY unit_index"
            + databaseManager.sqlProvider().forUpdateClause();
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setLong(1, lineId);
      try (ResultSet r = s.executeQuery()) {
        while (r.next() && values.size() < quantity)
          values.add(new PaymentUnit(r.getLong(1), r.getLong(2)));
      }
    }
    return List.copyOf(values);
  }

  private void markUnitsRefunded(Connection c, List<PaymentUnit> units) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "UPDATE checkout_line_payment_units SET refunded=TRUE WHERE id=? AND refunded=FALSE")) {
      for (PaymentUnit unit : units) {
        s.setLong(1, unit.id());
        s.addBatch();
      }
      int[] counts = s.executeBatch();
      for (int count : counts)
        if (count != 1)
          throw new ServiceException(
              "refund_reconciliation_failed", "Payment unit was already refunded");
    }
  }

  private void insertAdjustment(
      Connection c, long requestId, long lineId, long amount, String currency) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO commerce_refund_adjustments"
                + " (refund_request_id,checkout_line_id,adjustment_type,funder_type,amount,currency,detail_json)"
                + " VALUES (?,?,'BUYER_REFUND','BUYER',?,?,?)")) {
      s.setLong(1, requestId);
      s.setLong(2, lineId);
      s.setLong(3, amount);
      s.setString(4, currency);
      s.setString(5, "{\"frozenUnitAllocation\":true}");
      s.executeUpdate();
    }
  }

  private boolean isCheckoutFullyRefunded(Connection c, long id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT COUNT(*) FROM checkout_order_lines l JOIN checkout_order_groups g ON"
                + " g.id=l.group_id WHERE g.checkout_id=? AND l.refunded_quantity<l.quantity")) {
      s.setLong(1, id);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getLong(1) == 0;
      }
    }
  }

  private record Line(
      long id,
      int quantity,
      int refundedQuantity,
      long finalAmount,
      String currency,
      long checkoutId) {}

  private record ExistingRefund(long amount, int quantity) {}

  private record PaymentUnit(long id, long finalAmount) {}

  record RefundResult(String state, long amount, int quantity, boolean checkoutFullyRefunded) {}
}
