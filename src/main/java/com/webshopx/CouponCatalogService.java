package com.webshopx;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Coupon template lifecycle. Templates always point to an immutable promotion rule version. */
final class CouponCatalogService {
  private final DatabaseManager databaseManager;

  CouponCatalogService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  Template create(long actorId, TemplateDraft draft) {
    validate(draft);
    return databaseManager.inTransaction(
        connection -> {
          String sql =
              "INSERT INTO coupon_templates (code, campaign_id, rule_version_id, "
                  + "owner_type, owner_id, name, claim_mode, status, issue_limit, "
                  + "per_subject_claim_limit, per_subject_use_limit, validity_mode, valid_from, "
                  + "valid_until, valid_duration_seconds, created_by) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?)";
          long id;
          try (PreparedStatement statement =
              connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int index = 1;
            statement.setString(index++, draft.code().trim());
            statement.setLong(index++, draft.campaignId());
            statement.setLong(index++, draft.ruleVersionId());
            statement.setString(index++, draft.ownerType());
            if (draft.ownerId() == null) statement.setObject(index++, null);
            else statement.setLong(index++, draft.ownerId());
            statement.setString(index++, draft.name().trim());
            statement.setString(index++, draft.claimMode());
            if (draft.issueLimit() == null) statement.setObject(index++, null);
            else statement.setLong(index++, draft.issueLimit());
            statement.setInt(index++, draft.perSubjectClaimLimit());
            statement.setInt(index++, draft.perSubjectUseLimit());
            statement.setString(index++, draft.validityMode());
            setTime(statement, index++, draft.validFrom());
            setTime(statement, index++, draft.validUntil());
            if (draft.validDurationSeconds() == null) statement.setObject(index++, null);
            else statement.setLong(index++, draft.validDurationSeconds());
            statement.setLong(index, actorId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
              if (!keys.next()) throw new SQLException("Coupon template id missing");
              id = keys.getLong(1);
            }
          }
          return read(connection, id);
        });
  }

  List<Template> list() {
    return databaseManager.withConnection(
        connection -> {
          List<Template> values = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT * FROM coupon_templates ORDER BY id DESC");
              ResultSet result = statement.executeQuery()) {
            while (result.next()) values.add(read(result));
          }
          return List.copyOf(values);
        });
  }

  private Template read(java.sql.Connection connection, long id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT * FROM coupon_templates WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("Coupon template missing");
        return read(result);
      }
    }
  }

  private Template read(ResultSet result) throws SQLException {
    Object ownerId = result.getObject("owner_id");
    Object issueLimit = result.getObject("issue_limit");
    return new Template(
        result.getLong("id"),
        result.getString("code"),
        result.getLong("campaign_id"),
        result.getLong("rule_version_id"),
        result.getString("owner_type"),
        ownerId == null ? null : ((Number) ownerId).longValue(),
        result.getString("name"),
        result.getString("claim_mode"),
        result.getString("status"),
        issueLimit == null ? null : ((Number) issueLimit).longValue(),
        result.getInt("per_subject_claim_limit"),
        result.getString("validity_mode"));
  }

  private void validate(TemplateDraft draft) {
    if (draft == null
        || draft.code() == null
        || draft.code().isBlank()
        || draft.name() == null
        || draft.name().isBlank()
        || draft.campaignId() <= 0
        || draft.ruleVersionId() <= 0
        || draft.perSubjectClaimLimit() <= 0
        || draft.perSubjectUseLimit() <= 0) {
      throw new ServiceException("invalid_coupon_template", "Coupon template is invalid");
    }
  }

  private static void setTime(PreparedStatement statement, int index, Instant value)
      throws SQLException {
    if (value == null) statement.setObject(index, null);
    else statement.setTimestamp(index, Timestamp.from(value));
  }

  record TemplateDraft(
      String code,
      long campaignId,
      long ruleVersionId,
      String ownerType,
      Long ownerId,
      String name,
      String claimMode,
      Long issueLimit,
      int perSubjectClaimLimit,
      int perSubjectUseLimit,
      String validityMode,
      Instant validFrom,
      Instant validUntil,
      Long validDurationSeconds) {}

  record Template(
      long id,
      String code,
      long campaignId,
      long ruleVersionId,
      String ownerType,
      Long ownerId,
      String name,
      String claimMode,
      String status,
      Long issueLimit,
      int perSubjectClaimLimit,
      String validityMode) {}
}
