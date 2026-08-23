package com.webshopx;

import com.webshopx.promotion.pricing.PricingEngine;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

/** Single-display membership codes with hashed storage and idempotent redemption. */
final class MembershipCodeService {
  private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
  private final DatabaseManager databaseManager;
  private final MembershipService membershipService;
  private final SecureRandom random = new SecureRandom();

  MembershipCodeService(DatabaseManager databaseManager, MembershipService membershipService) {
    this.databaseManager = databaseManager;
    this.membershipService = membershipService;
  }

  CreatedCode create(long actorId, long planVersionId, int maxUses, Instant validUntil) {
    if (planVersionId <= 0 || maxUses <= 0) {
      throw new ServiceException("invalid_membership_code", "Membership code input is invalid");
    }
    String plain = newCode();
    databaseManager.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO membership_codes (code_hash, plan_version_id, max_uses, "
                      + "valid_until, created_by) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, hash(plain));
            statement.setLong(2, planVersionId);
            statement.setInt(3, maxUses);
            if (validUntil == null) statement.setObject(4, null);
            else statement.setTimestamp(4, Timestamp.from(validUntil));
            statement.setLong(5, actorId);
            statement.executeUpdate();
          }
          return null;
        });
    return new CreatedCode(plain, planVersionId, maxUses, validUntil);
  }

  MembershipService.Membership redeem(long userId, String code, String requestId) {
    if (requestId == null || requestId.isBlank()) {
      throw new ServiceException("invalid_idempotency_key", "Request id is required");
    }
    return databaseManager.inTransaction(
        connection -> {
          try (PreparedStatement existing =
              connection.prepareStatement(
                  "SELECT membership_id FROM membership_code_redemptions WHERE user_id = ? "
                      + "AND request_id = ?")) {
            existing.setLong(1, userId);
            existing.setString(2, requestId);
            try (ResultSet result = existing.executeQuery()) {
              if (result.next()) {
                return membershipService.readMembership(connection, result.getLong(1));
              }
            }
          }
          CodeRow row = lock(connection, code);
          if (!"ACTIVE".equals(row.status())
              || row.usedCount() >= row.maxUses()
              || row.validUntil() != null && !Instant.now().isBefore(row.validUntil())) {
            throw new ServiceException(
                "membership_code_unavailable", "Membership code is unavailable");
          }
          String grantKey = "MEMBERSHIP_CODE:" + row.id() + ":" + userId;
          MembershipService.Membership membership =
              membershipService.grant(
                  connection,
                  userId,
                  row.planVersionId(),
                  "CODE",
                  String.valueOf(row.id()),
                  grantKey,
                  null);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO membership_code_redemptions (membership_code_id, user_id, "
                      + "membership_id, request_id) VALUES (?, ?, ?, ?)",
                  Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, row.id());
            statement.setLong(2, userId);
            statement.setLong(3, membership.id());
            statement.setString(4, requestId);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE membership_codes SET used_count = used_count + 1, status = CASE WHEN"
                      + " used_count + 1 >= max_uses THEN 'CONSUMED' ELSE status END WHERE id = ?"
                      + " AND used_count < max_uses")) {
            statement.setLong(1, row.id());
            if (statement.executeUpdate() != 1) {
              throw new ServiceException(
                  "membership_code_unavailable", "Membership code is unavailable");
            }
          }
          return membership;
        });
  }

  private CodeRow lock(java.sql.Connection connection, String code) throws SQLException {
    String sql =
        "SELECT id, plan_version_id, status, max_uses, used_count, valid_until "
            + "FROM membership_codes WHERE code_hash = ?"
            + databaseManager.sqlProvider().forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, hash(code));
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new ServiceException("membership_code_invalid", "Membership code is invalid");
        }
        Timestamp expiry = result.getTimestamp(6);
        return new CodeRow(
            result.getLong(1),
            result.getLong(2),
            result.getString(3),
            result.getInt(4),
            result.getInt(5),
            expiry == null ? null : expiry.toInstant());
      }
    }
  }

  private String newCode() {
    StringBuilder value = new StringBuilder("WSX-");
    for (int index = 0; index < 16; index++) {
      if (index > 0 && index % 4 == 0) value.append('-');
      value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return value.toString();
  }

  private String hash(String code) {
    if (code == null || code.isBlank()) {
      throw new ServiceException("membership_code_invalid", "Membership code is required");
    }
    return PricingEngine.stableHash(code.trim().toUpperCase(Locale.ROOT));
  }

  private record CodeRow(
      long id, long planVersionId, String status, int maxUses, int usedCount, Instant validUntil) {}

  record CreatedCode(String code, long planVersionId, int maxUses, Instant validUntil) {}
}
