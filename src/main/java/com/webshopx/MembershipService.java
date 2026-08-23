package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Membership grants from purchase, code or admin and entitlement projection. */
class MembershipService {
  private final DatabaseManager databaseManager;

  MembershipService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  Membership grant(
      long userId,
      long planVersionId,
      String sourceType,
      String sourceRef,
      String bizKey,
      Long grantedBy) {
    if (bizKey == null || bizKey.isBlank())
      throw new ServiceException("invalid_idempotency_key", "Grant key is required");
    return databaseManager.inTransaction(
        connection ->
            grant(connection, userId, planVersionId, sourceType, sourceRef, bizKey, grantedBy));
  }

  Membership grant(
      Connection connection,
      long userId,
      long planVersionId,
      String sourceType,
      String sourceRef,
      String bizKey,
      Long grantedBy)
      throws SQLException {
    Membership existing = findByGrantKey(connection, bizKey);
    if (existing != null) return existing;
    PlanVersion version = readPlanVersion(connection, planVersionId);
    Instant now = Instant.now();
    Instant expiry = expiry(now, version.durationMode(), version.durationValue());
    long id;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO user_memberships (user_id, plan_id, plan_version_id, level_code, status,"
                + " starts_at, expires_at, source_type, source_ref, grant_biz_key) VALUES (?, ?, ?,"
                + " ?, 'ACTIVE', ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, userId);
      statement.setLong(2, version.planId());
      statement.setLong(3, version.id());
      statement.setString(4, version.levelCode());
      statement.setTimestamp(5, Timestamp.from(now));
      if (expiry == null) statement.setObject(6, null);
      else statement.setTimestamp(6, Timestamp.from(expiry));
      statement.setString(7, sourceType);
      statement.setString(8, sourceRef);
      statement.setString(9, bizKey);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("Membership id missing");
        id = keys.getLong(1);
      }
    }
    String entitlementKey = bizKey + ":MEMBER_STACKING";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO user_entitlements (user_id, entitlement_code, source_type, source_ref,"
                + " starts_at, expires_at, status, grant_biz_key, granted_by, reason) VALUES (?,"
                + " 'MEMBER_STACKING', 'MEMBERSHIP', ?, ?, ?, 'ACTIVE', ?, ?, ?)")) {
      statement.setLong(1, userId);
      statement.setString(2, String.valueOf(id));
      statement.setTimestamp(3, Timestamp.from(now));
      if (expiry == null) statement.setObject(4, null);
      else statement.setTimestamp(4, Timestamp.from(expiry));
      statement.setString(5, entitlementKey);
      if (grantedBy == null) statement.setObject(6, null);
      else statement.setLong(6, grantedBy);
      statement.setString(7, sourceType + ":" + sourceRef);
      statement.executeUpdate();
    }
    return read(connection, id);
  }

  Membership revoke(long membershipId, long actorId, String reason) {
    if (reason == null || reason.isBlank())
      throw new ServiceException("reason_required", "Reason is required");
    return databaseManager.inTransaction(
        connection -> {
          Membership current = read(connection, membershipId);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE user_memberships SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,"
                      + " version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND"
                      + " status IN ('ACTIVE','PENDING')")) {
            statement.setLong(1, membershipId);
            if (statement.executeUpdate() != 1)
              throw new ServiceException(
                  "invalid_membership_transition", "Membership cannot be revoked");
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE user_entitlements SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                      + " WHERE source_type = 'MEMBERSHIP' AND source_ref = ? AND status ="
                      + " 'ACTIVE'")) {
            statement.setString(1, String.valueOf(membershipId));
            statement.executeUpdate();
          }
          return read(connection, membershipId);
        });
  }

  Set<String> activeEntitlements(Connection connection, long userId, Instant at)
      throws SQLException {
    Set<String> values = new HashSet<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT entitlement_code FROM user_entitlements WHERE user_id = ? AND status = 'ACTIVE'"
                + " AND starts_at <= ? AND (expires_at IS NULL OR expires_at > ?)")) {
      statement.setLong(1, userId);
      statement.setTimestamp(2, Timestamp.from(at));
      statement.setTimestamp(3, Timestamp.from(at));
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) values.add(result.getString(1));
      }
    }
    return Set.copyOf(values);
  }

  List<Membership> list(long userId) {
    return databaseManager.withConnection(
        connection -> {
          List<Membership> values = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT * FROM user_memberships WHERE user_id = ? ORDER BY id DESC")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) values.add(read(result));
            }
          }
          return List.copyOf(values);
        });
  }

  Membership readMembership(Connection connection, long membershipId) throws SQLException {
    return read(connection, membershipId);
  }

  private Membership findByGrantKey(Connection c, String key) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT * FROM user_memberships WHERE grant_biz_key = ?")) {
      s.setString(1, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? read(r) : null;
      }
    }
  }

  private PlanVersion readPlanVersion(Connection c, long id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id, plan_id, level_code, duration_mode, duration_value FROM"
                + " membership_plan_versions WHERE id = ?")) {
      s.setLong(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next())
          throw new ServiceException(
              "membership_plan_missing", "Membership plan version does not exist");
        return new PlanVersion(
            id,
            r.getLong("plan_id"),
            r.getString("level_code"),
            r.getString("duration_mode"),
            nullableLong(r, "duration_value"));
      }
    }
  }

  private Membership read(Connection c, long id) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT * FROM user_memberships WHERE id = ?")) {
      s.setLong(1, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next())
          throw new ServiceException("membership_missing", "Membership does not exist");
        return read(r);
      }
    }
  }

  private Membership read(ResultSet r) throws SQLException {
    return new Membership(
        r.getLong("id"),
        r.getLong("user_id"),
        r.getLong("plan_id"),
        r.getLong("plan_version_id"),
        r.getString("level_code"),
        r.getString("status"),
        r.getTimestamp("starts_at").toInstant(),
        toInstant(r.getTimestamp("expires_at")),
        r.getString("source_type"),
        r.getString("source_ref"));
  }

  private Instant expiry(Instant start, String mode, Long value) {
    if ("PERMANENT".equals(mode)) return null;
    long duration = value == null ? 0 : value;
    return switch (mode) {
      case "DAYS" -> start.plus(duration, ChronoUnit.DAYS);
      case "SECONDS" -> start.plusSeconds(duration);
      default ->
          throw new ServiceException("invalid_duration_mode", "Membership duration is invalid");
    };
  }

  private static Long nullableLong(ResultSet r, String c) throws SQLException {
    Object v = r.getObject(c);
    return v == null ? null : ((Number) v).longValue();
  }

  private static Instant toInstant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  record PlanVersion(
      long id, long planId, String levelCode, String durationMode, Long durationValue) {}

  record Membership(
      long id,
      long userId,
      long planId,
      long planVersionId,
      String levelCode,
      String status,
      Instant startsAt,
      Instant expiresAt,
      String sourceType,
      String sourceRef) {}
}
