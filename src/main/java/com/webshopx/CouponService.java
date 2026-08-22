package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Account-bound coupon lifecycle with conditional, idempotent transitions. */
class CouponService {
  private final DatabaseManager databaseManager;

  CouponService(DatabaseManager databaseManager) { this.databaseManager = databaseManager; }

  UserCoupon claim(long userId, long templateId, String requestId) {
    String biz = normalizeKey(requestId);
    return databaseManager.inTransaction(connection -> {
      UserCoupon existing = findByEventBiz(connection, userId, "CLAIMED", "CLAIM", biz);
      if (existing != null) return existing;
      Template template = lockTemplate(connection, templateId);
      if (!"ACTIVE".equals(template.status())) throw new ServiceException("DISABLED", "Coupon is not claimable");
      long userCount = count(connection, "SELECT COUNT(*) FROM user_coupons WHERE template_id = ? AND user_id = ?", templateId, userId);
      if (userCount >= template.perSubjectClaimLimit()) throw new ServiceException("USER_LIMIT_REACHED", "Coupon claim limit reached");
      long issued = count(connection, "SELECT COUNT(*) FROM user_coupons WHERE template_id = ?", templateId, null);
      if (template.issueLimit() != null && issued >= template.issueLimit()) throw new ServiceException("STOCK_EXHAUSTED", "Coupon stock exhausted");
      Instant now = Instant.now();
      Instant from = template.validFrom() == null ? now : template.validFrom();
      Instant until = template.validUntil();
      if (template.validDurationSeconds() != null) until = now.plusSeconds(template.validDurationSeconds());
      long id;
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO user_coupons (serial_no, template_id, rule_version_id, user_id, status, valid_from, valid_until, source_type, source_ref) VALUES (?, ?, ?, ?, 'AVAILABLE', ?, ?, 'CLAIM', ?)", Statement.RETURN_GENERATED_KEYS)) {
        statement.setString(1, "CPN-" + UUID.randomUUID()); statement.setLong(2, template.id());
        statement.setLong(3, template.ruleVersionId()); statement.setLong(4, userId);
        statement.setTimestamp(5, Timestamp.from(from)); setTime(statement, 6, until); statement.setString(7, biz);
        statement.executeUpdate(); try (ResultSet keys = statement.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Coupon id missing"); id = keys.getLong(1); }
      }
      addEvent(connection, id, "CLAIMED", null, "AVAILABLE", "CLAIM", biz, "USER", String.valueOf(userId));
      return read(connection, id);
    });
  }

  UserCoupon grant(long userId, long templateId, String sourceType, String sourceRef, String bizKey, long actorId) {
    String key = normalizeKey(bizKey);
    return databaseManager.inTransaction(connection -> {
      UserCoupon existing = findByEventBiz(connection, userId, "GRANTED", sourceType, key);
      if (existing != null) return existing;
      Template template = lockTemplate(connection, templateId);
      Instant now = Instant.now(); Instant until = template.validUntil();
      if (template.validDurationSeconds() != null) until = now.plusSeconds(template.validDurationSeconds());
      long id;
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO user_coupons (serial_no, template_id, rule_version_id, user_id, status, valid_from, valid_until, source_type, source_ref) VALUES (?, ?, ?, ?, 'AVAILABLE', ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
        statement.setString(1, "CPN-" + UUID.randomUUID()); statement.setLong(2, templateId);
        statement.setLong(3, template.ruleVersionId()); statement.setLong(4, userId);
        statement.setTimestamp(5, Timestamp.from(now)); setTime(statement, 6, until);
        statement.setString(7, sourceType); statement.setString(8, sourceRef); statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Coupon id missing"); id = keys.getLong(1); }
      }
      addEvent(connection, id, "GRANTED", null, "AVAILABLE", sourceType, key, "ADMIN", String.valueOf(actorId));
      return read(connection, id);
    });
  }

  void consume(Connection connection, long couponId, long userId, long checkoutId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE user_coupons SET status = 'CONSUMED', consumed_checkout_id = ?, consumed_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ? AND status = 'AVAILABLE' AND valid_from <= CURRENT_TIMESTAMP AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)")) {
      statement.setLong(1, checkoutId); statement.setLong(2, couponId); statement.setLong(3, userId);
      if (statement.executeUpdate() != 1) throw new ServiceException("ALREADY_USED", "Coupon is not available");
    }
    addEvent(connection, couponId, "CONSUMED", "AVAILABLE", "CONSUMED", "CHECKOUT", String.valueOf(checkoutId), "SYSTEM", null);
  }

  void returnAfterFullRefund(Connection connection, long couponId, long checkoutId, String refundId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE user_coupons SET status = CASE WHEN valid_until IS NOT NULL AND valid_until <= CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'AVAILABLE' END, consumed_checkout_id = NULL, consumed_at = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND consumed_checkout_id = ? AND status = 'CONSUMED'")) {
      statement.setLong(1, couponId); statement.setLong(2, checkoutId);
      if (statement.executeUpdate() == 1) addEvent(connection, couponId, "RETURNED", "CONSUMED", "AVAILABLE", "REFUND", refundId, "SYSTEM", null);
    }
  }

  List<UserCoupon> mine(long userId, String status) {
    return databaseManager.withConnection(connection -> {
      String sql = "SELECT * FROM user_coupons WHERE user_id = ?" + (status == null ? "" : " AND status = ?") + " ORDER BY valid_until, id";
      List<UserCoupon> coupons = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId); if (status != null) statement.setString(2, status);
        try (ResultSet result = statement.executeQuery()) { while (result.next()) coupons.add(read(result)); }
      }
      return List.copyOf(coupons);
    });
  }

  List<Long> availableIds(Connection connection, long userId) throws SQLException {
    List<Long> ids = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id FROM user_coupons WHERE user_id = ? AND status = 'AVAILABLE' AND valid_from <= CURRENT_TIMESTAMP AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP) ORDER BY valid_until, id")) {
      statement.setLong(1, userId); try (ResultSet result = statement.executeQuery()) { while (result.next()) ids.add(result.getLong(1)); }
    }
    return List.copyOf(ids);
  }

  private Template lockTemplate(Connection connection, long id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT * FROM coupon_templates WHERE id = ?" + databaseManager.sqlProvider().forUpdateClause())) {
      statement.setLong(1, id); try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("coupon_template_missing", "Coupon template does not exist");
        return new Template(id, result.getLong("rule_version_id"), result.getString("status"),
            nullableLong(result, "issue_limit"), result.getInt("per_subject_claim_limit"),
            toInstant(result.getTimestamp("valid_from")), toInstant(result.getTimestamp("valid_until")),
            nullableLong(result, "valid_duration_seconds"));
      }
    }
  }
  private long count(Connection c, String sql, long first, Long second) throws SQLException { try (PreparedStatement s = c.prepareStatement(sql)) { s.setLong(1, first); if (second != null) s.setLong(2, second); try (ResultSet r = s.executeQuery()) { r.next(); return r.getLong(1); } } }
  private UserCoupon findByEventBiz(Connection c, long userId, String event, String bizType, String bizId) throws SQLException { String sql = "SELECT u.* FROM user_coupons u JOIN coupon_events e ON e.user_coupon_id = u.id WHERE u.user_id = ? AND e.event_type = ? AND e.biz_type = ? AND e.biz_id = ?"; try (PreparedStatement s = c.prepareStatement(sql)) { s.setLong(1, userId); s.setString(2, event); s.setString(3, bizType); s.setString(4, bizId); try (ResultSet r = s.executeQuery()) { return r.next() ? read(r) : null; } } }
  private UserCoupon read(Connection c, long id) throws SQLException { try (PreparedStatement s = c.prepareStatement("SELECT * FROM user_coupons WHERE id = ?")) { s.setLong(1, id); try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new SQLException("Coupon missing"); return read(r); } } }
  private UserCoupon read(ResultSet r) throws SQLException { return new UserCoupon(r.getLong("id"), r.getString("serial_no"), r.getLong("template_id"), r.getLong("rule_version_id"), r.getLong("user_id"), r.getString("status"), toInstant(r.getTimestamp("valid_from")), toInstant(r.getTimestamp("valid_until")), nullableLong(r, "consumed_checkout_id")); }
  private void addEvent(Connection c, long id, String event, String from, String to, String bizType, String bizId, String actorType, String actorId) throws SQLException { try (PreparedStatement s = c.prepareStatement("INSERT INTO coupon_events (user_coupon_id, event_type, from_status, to_status, biz_type, biz_id, actor_type, actor_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { s.setLong(1, id); s.setString(2, event); s.setString(3, from); s.setString(4, to); s.setString(5, bizType); s.setString(6, bizId); s.setString(7, actorType); s.setString(8, actorId); s.executeUpdate(); } }
  private String normalizeKey(String key) { if (key == null || key.isBlank() || key.length() > 128) throw new ServiceException("invalid_idempotency_key", "Idempotency key is required"); return key.trim(); }
  private static Long nullableLong(ResultSet r, String c) throws SQLException { Object value = r.getObject(c); return value == null ? null : ((Number) value).longValue(); }
  private static Instant toInstant(Timestamp t) { return t == null ? null : t.toInstant(); }
  private static void setTime(PreparedStatement s, int i, Instant v) throws SQLException { if (v == null) s.setObject(i, null); else s.setTimestamp(i, Timestamp.from(v)); }

  record Template(long id, long ruleVersionId, String status, Long issueLimit, int perSubjectClaimLimit,
                  Instant validFrom, Instant validUntil, Long validDurationSeconds) {}
  record UserCoupon(long id, String serialNo, long templateId, long ruleVersionId, long userId,
                    String status, Instant validFrom, Instant validUntil, Long consumedCheckoutId) {}
}
