package com.webshopx;

import com.webshopx.promotion.pricing.PricingEngine;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Immutable membership plan versions and official product bindings. */
final class MembershipCatalogService {
  private final DatabaseManager databaseManager;

  MembershipCatalogService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  Plan createPlan(long actorId, PlanDraft draft) {
    validatePlan(draft);
    return databaseManager.inTransaction(connection -> {
      String sql = "INSERT INTO membership_plans (code, name, description, status, created_by) "
          + "VALUES (?, ?, ?, 'DRAFT', ?)";
      long id;
      try (PreparedStatement statement = connection.prepareStatement(
          sql, Statement.RETURN_GENERATED_KEYS)) {
        statement.setString(1, draft.code().trim());
        statement.setString(2, draft.name().trim());
        statement.setString(3, draft.description());
        statement.setLong(4, actorId);
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          if (!keys.next()) {
            throw new SQLException("Membership plan id missing");
          }
          id = keys.getLong(1);
        }
      }
      return new Plan(id, draft.code().trim(), draft.name().trim(), draft.description(),
          "DRAFT", null);
    });
  }

  PlanVersion publishVersion(long actorId, long planId, VersionDraft draft) {
    validateVersion(draft);
    return databaseManager.inTransaction(connection -> {
      int version;
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT COALESCE(MAX(version), 0) + 1 FROM membership_plan_versions WHERE plan_id = ?")) {
        statement.setLong(1, planId);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          version = result.getInt(1);
        }
      }
      String canonical = planId + "|" + version + "|" + draft;
      String hash = PricingEngine.stableHash(canonical);
      long id;
      String sql = "INSERT INTO membership_plan_versions (plan_id, version, level_code, "
          + "level_rank, duration_mode, duration_value, renewal_mode, upgrade_policy_json, "
          + "refund_policy_json, benefits_json, version_hash, created_by) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(
          sql, Statement.RETURN_GENERATED_KEYS)) {
        statement.setLong(1, planId);
        statement.setInt(2, version);
        statement.setString(3, draft.levelCode());
        statement.setInt(4, draft.levelRank());
        statement.setString(5, draft.durationMode());
        if (draft.durationValue() == null) {
          statement.setObject(6, null);
        } else {
          statement.setLong(6, draft.durationValue());
        }
        statement.setString(7, draft.renewalMode());
        statement.setString(8, draft.upgradePolicyJson());
        statement.setString(9, draft.refundPolicyJson());
        statement.setString(10, draft.benefitsJson());
        statement.setString(11, hash);
        statement.setLong(12, actorId);
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          if (!keys.next()) {
            throw new SQLException("Membership plan version id missing");
          }
          id = keys.getLong(1);
        }
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE membership_plans SET current_version_id = ?, status = 'ACTIVE', "
              + "updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setLong(1, id);
        statement.setLong(2, planId);
        if (statement.executeUpdate() != 1) {
          throw new ServiceException("membership_plan_missing", "Membership plan does not exist");
        }
      }
      return new PlanVersion(id, planId, version, draft.levelCode(), draft.levelRank(),
          draft.durationMode(), draft.durationValue(), draft.renewalMode(),
          draft.benefitsJson(), hash);
    });
  }

  void bindProduct(long actorId, long productId, long planVersionId) {
    databaseManager.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT product_type FROM products WHERE id = ?")) {
        statement.setLong(1, productId);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            throw new ServiceException("product_missing", "Product does not exist");
          }
          if (!"MEMBERSHIP".equals(result.getString(1))) {
            throw new ServiceException("invalid_product_type", "Product must use MEMBERSHIP type");
          }
        }
      }
      String updateSql = "UPDATE membership_product_bindings SET plan_version_id = ?, "
          + "active = TRUE, created_by = ?, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?";
      int updated;
      try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
        statement.setLong(1, planVersionId);
        statement.setLong(2, actorId);
        statement.setLong(3, productId);
        updated = statement.executeUpdate();
      }
      if (updated == 0) {
        String insertSql = "INSERT INTO membership_product_bindings "
            + "(product_id, plan_version_id, created_by) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
        statement.setLong(1, productId);
        statement.setLong(2, planVersionId);
        statement.setLong(3, actorId);
        statement.executeUpdate();
        }
      }
      return null;
    });
  }

  List<Plan> listPlans() {
    return databaseManager.withConnection(connection -> {
      List<Plan> values = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id, code, name, description, status, current_version_id "
              + "FROM membership_plans ORDER BY id DESC");
           ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          Object versionId = result.getObject(6);
          values.add(new Plan(result.getLong(1), result.getString(2), result.getString(3),
              result.getString(4), result.getString(5),
              versionId == null ? null : ((Number) versionId).longValue()));
        }
      }
      return List.copyOf(values);
    });
  }

  private void validatePlan(PlanDraft draft) {
    if (draft == null || draft.code() == null || draft.code().isBlank()
        || draft.name() == null || draft.name().isBlank()) {
      throw new ServiceException("invalid_membership_plan", "Plan code and name are required");
    }
  }

  private void validateVersion(VersionDraft draft) {
    if (draft == null || draft.levelCode() == null || draft.levelCode().isBlank()
        || draft.levelRank() < 0 || !SetSupport.DURATION_MODES.contains(draft.durationMode())
        || !"PERMANENT".equals(draft.durationMode())
            && (draft.durationValue() == null || draft.durationValue() <= 0)) {
      throw new ServiceException("invalid_membership_version", "Membership version is invalid");
    }
  }

  record PlanDraft(String code, String name, String description) {
  }

  record VersionDraft(String levelCode, int levelRank, String durationMode,
      Long durationValue, String renewalMode, String upgradePolicyJson,
      String refundPolicyJson, String benefitsJson) {
  }

  record Plan(long id, String code, String name, String description, String status,
      Long currentVersionId) {
  }

  record PlanVersion(long id, long planId, int version, String levelCode, int levelRank,
      String durationMode, Long durationValue, String renewalMode, String benefitsJson,
      String versionHash) {
  }

  private static final class SetSupport {
    private static final java.util.Set<String> DURATION_MODES =
        java.util.Set.of("PERMANENT", "DAYS", "SECONDS");

    private SetSupport() {
    }
  }
}
