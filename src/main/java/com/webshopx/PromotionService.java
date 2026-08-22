package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.webshopx.promotion.pricing.PricingEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Campaign lifecycle, immutable rule versions and production rule projection. */
class PromotionService {
  private final DatabaseManager databaseManager;
  private final Gson gson = CommerceJson.create();

  PromotionService(DatabaseManager databaseManager) { this.databaseManager = databaseManager; }

  Campaign createDraft(long actorId, CampaignDraft draft) {
    validateDraft(draft);
    return databaseManager.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO promotion_campaigns (code, owner_type, owner_id, name, description, status, start_at, end_at, created_by, updated_by) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS)) {
        statement.setString(1, draft.code().trim()); statement.setString(2, draft.ownerType().name());
        setNullableLong(statement, 3, draft.ownerId()); statement.setString(4, draft.name().trim());
        statement.setString(5, draft.description()); setTime(statement, 6, draft.startAt());
        setTime(statement, 7, draft.endAt()); statement.setLong(8, actorId); statement.setLong(9, actorId);
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          if (!keys.next()) throw new SQLException("Campaign id missing");
          return readCampaign(connection, keys.getLong(1));
        }
      }
    });
  }

  PublishedRule publish(long actorId, long campaignId, long expectedVersion, RuleDraft draft) {
    validateRule(draft);
    return databaseManager.inTransaction(connection -> {
      Campaign campaign = lockCampaign(connection, campaignId);
      if (campaign.version() != expectedVersion) throw new ServiceException("version_conflict", "Campaign changed");
      if (!(campaign.status().equals("DRAFT") || campaign.status().equals("PAUSED"))) {
        throw new ServiceException("PUBLISHED_VERSION_IMMUTABLE", "Create a revision for published rules");
      }
      int nextVersion = nextRuleVersion(connection, campaignId);
      JsonArray scope = new JsonArray(); draft.lineIds().stream().sorted().forEach(scope::add);
      String scopeJson = scope.toString();
      String rulesHash = PricingEngine.stableHash(campaignId + "|" + nextVersion + "|" + draft + "|" + scopeJson);
      long id;
      String sql = "INSERT INTO promotion_rule_versions (campaign_id, version, rule_kind, direction, layer, stacking_slot, stacking_policy, exclusive_group, priority, max_per_order, threshold_type, threshold_value, threshold_basis_layer, discount_basis_layer, repeat_mode, max_repeat_count, discount_amount, discount_bps, max_discount_amount, funding_mode, platform_share_bps, min_payable_override, allow_zero_payable, rule_json, scope_json, eligibility_json, stacking_json, refund_policy_json, display_json, rules_hash, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
        int i = 1; statement.setLong(i++, campaignId); statement.setInt(i++, nextVersion);
        statement.setString(i++, draft.ruleKind()); statement.setString(i++, draft.direction());
        statement.setInt(i++, draft.layer()); statement.setString(i++, draft.slot());
        statement.setString(i++, draft.stackingPolicy()); statement.setString(i++, draft.exclusiveGroup());
        statement.setInt(i++, draft.priority()); statement.setString(i++, draft.thresholdType());
        statement.setLong(i++, draft.thresholdValue()); statement.setInt(i++, draft.thresholdBasisLayer());
        statement.setInt(i++, draft.discountBasisLayer()); statement.setString(i++, draft.repeatMode());
        statement.setInt(i++, draft.maxRepeatCount()); statement.setLong(i++, draft.discountAmount());
        statement.setInt(i++, draft.discountBps()); setNullableLong(statement, i++, draft.maxDiscountAmount());
        statement.setString(i++, draft.fundingMode()); statement.setInt(i++, draft.platformShareBps());
        setNullableLong(statement, i++, draft.minPayableOverride()); statement.setBoolean(i++, draft.allowZeroPayable());
        statement.setString(i++, gson.toJson(draft)); statement.setString(i++, scopeJson);
        statement.setString(i++, draft.eligibilityJson()); statement.setString(i++, draft.stackingJson());
        statement.setString(i++, draft.refundPolicyJson()); statement.setString(i++, draft.displayJson());
        statement.setString(i++, rulesHash); statement.setLong(i, actorId); statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          if (!keys.next()) throw new SQLException("Rule version id missing"); id = keys.getLong(1);
        }
      }
      String status = campaign.startAt() != null && campaign.startAt().isAfter(Instant.now()) ? "SCHEDULED" : "ACTIVE";
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE promotion_campaigns SET current_version_id = ?, status = ?, version = version + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setLong(1, id); statement.setString(2, status); statement.setLong(3, actorId);
        statement.setLong(4, campaignId); statement.executeUpdate();
      }
      return new PublishedRule(id, campaignId, nextVersion, rulesHash, status);
    });
  }

  Campaign transition(long campaignId, String action, long actorId) {
    String normalized = action.toUpperCase(Locale.ROOT);
    return databaseManager.inTransaction(connection -> {
      Campaign campaign = lockCampaign(connection, campaignId);
      String next = switch (normalized) {
        case "PAUSE" -> campaign.status().matches("ACTIVE|SCHEDULED") ? "PAUSED" : null;
        case "RESUME" -> campaign.status().equals("PAUSED") ? "ACTIVE" : null;
        case "END" -> campaign.status().equals("DRAFT") ? "TERMINATED" : "ENDED";
        default -> null;
      };
      if (next == null) throw new ServiceException("invalid_campaign_transition", "Campaign state transition is invalid");
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE promotion_campaigns SET status = ?, version = version + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setString(1, next); statement.setLong(2, actorId); statement.setLong(3, campaignId);
        statement.executeUpdate();
      }
      return readCampaign(connection, campaignId);
    });
  }

  List<PricingEngine.Rule> activeRules(long userId, Instant at, Set<String> pricingLineIds) {
    return databaseManager.withConnection(connection -> {
      String sql = "SELECT r.*, c.start_at, c.end_at FROM promotion_rule_versions r JOIN promotion_campaigns c ON c.current_version_id = r.id WHERE c.status = 'ACTIVE' AND (c.start_at IS NULL OR c.start_at <= ?) AND (c.end_at IS NULL OR c.end_at > ?) ORDER BY r.layer, r.priority, r.id";
      List<PricingEngine.Rule> rules = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        Timestamp time = Timestamp.from(at); statement.setTimestamp(1, time); statement.setTimestamp(2, time);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            Set<String> scope = parseScope(result.getString("scope_json"));
            if (scope.contains("*")) scope = pricingLineIds;
            Set<String> overlap = new HashSet<>(scope); overlap.retainAll(pricingLineIds);
            if (overlap.isEmpty()) continue;
            String eligibility = result.getString("eligibility_json");
            String required = null;
            try { JsonObject json = gson.fromJson(eligibility, JsonObject.class); if (json != null && json.has("requiredEntitlement")) required = json.get("requiredEntitlement").getAsString(); } catch (RuntimeException ignored) { }
            Long userCouponId = findAvailableCoupon(connection, userId, result.getLong("id"));
            if (result.getString("rule_kind").contains("COUPON") && userCouponId == null) continue;
            rules.add(new PricingEngine.Rule(String.valueOf(result.getLong("id")), result.getInt("layer"),
                result.getInt("priority"), result.getString("stacking_slot"), overlap,
                PricingEngine.ThresholdType.valueOf(result.getString("threshold_type")),
                result.getLong("threshold_value"), basis(result.getInt("threshold_basis_layer")),
                basis(result.getInt("discount_basis_layer")),
                PricingEngine.RepeatMode.valueOf(result.getString("repeat_mode")),
                result.getInt("max_repeat_count"), result.getLong("discount_amount"),
                result.getInt("discount_bps"), nullableLong(result, "max_discount_amount"),
                PricingEngine.FundingMode.valueOf(result.getString("funding_mode")),
                result.getInt("platform_share_bps"), nullableLong(result, "min_payable_override"),
                result.getBoolean("allow_zero_payable"), "EXCLUSIVE_CHECKOUT".equals(result.getString("stacking_policy")),
                result.getString("exclusive_group"), required, userCouponId,
                toInstant(result.getTimestamp("start_at")), toInstant(result.getTimestamp("end_at"))));
          }
        }
      }
      return List.copyOf(rules);
    });
  }

  private Long findAvailableCoupon(Connection connection, long userId, long ruleVersionId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id FROM user_coupons WHERE user_id = ? AND rule_version_id = ? AND status = 'AVAILABLE' AND valid_from <= CURRENT_TIMESTAMP AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP) ORDER BY valid_until, id LIMIT 1")) {
      statement.setLong(1, userId); statement.setLong(2, ruleVersionId);
      try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : null; }
    }
  }

  List<Campaign> list(String ownerType, Long ownerId) {
    return databaseManager.withConnection(connection -> {
      StringBuilder sql = new StringBuilder("SELECT * FROM promotion_campaigns WHERE 1=1");
      if (ownerType != null) sql.append(" AND owner_type = ?");
      if (ownerId != null) sql.append(" AND owner_id = ?");
      sql.append(" ORDER BY id DESC");
      List<Campaign> campaigns = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
        int i = 1; if (ownerType != null) statement.setString(i++, ownerType); if (ownerId != null) statement.setLong(i, ownerId);
        try (ResultSet result = statement.executeQuery()) { while (result.next()) campaigns.add(readCampaign(result)); }
      }
      return List.copyOf(campaigns);
    });
  }

  private Campaign lockCampaign(Connection connection, long id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT * FROM promotion_campaigns WHERE id = ?" + databaseManager.sqlProvider().forUpdateClause())) {
      statement.setLong(1, id); try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("campaign_missing", "Campaign does not exist"); return readCampaign(result);
      }
    }
  }
  private Campaign readCampaign(Connection connection, long id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM promotion_campaigns WHERE id = ?")) {
      statement.setLong(1, id); try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ServiceException("campaign_missing", "Campaign does not exist"); return readCampaign(result);
      }
    }
  }
  private Campaign readCampaign(ResultSet r) throws SQLException { return new Campaign(r.getLong("id"), r.getString("code"), r.getString("owner_type"), nullableLong(r, "owner_id"), r.getString("name"), r.getString("description"), r.getString("status"), toInstant(r.getTimestamp("start_at")), toInstant(r.getTimestamp("end_at")), nullableLong(r, "current_version_id"), r.getLong("version")); }
  private int nextRuleVersion(Connection c, long id) throws SQLException { try (PreparedStatement s = c.prepareStatement("SELECT COALESCE(MAX(version), 0) + 1 FROM promotion_rule_versions WHERE campaign_id = ?")) { s.setLong(1, id); try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); } } }
  private Set<String> parseScope(String raw) { Set<String> ids = new HashSet<>(); try { for (var item : gson.fromJson(raw, JsonArray.class)) ids.add(item.getAsString()); } catch (RuntimeException ignored) { } return ids; }
  private PricingEngine.Basis basis(int layer) { return layer == 0 ? PricingEngine.Basis.P0 : PricingEngine.Basis.CURRENT; }
  private void validateDraft(CampaignDraft d) { if (d == null || d.code() == null || d.code().isBlank() || d.name() == null || d.name().isBlank() || d.endAt() != null && d.startAt() != null && !d.endAt().isAfter(d.startAt())) throw new ServiceException("invalid_campaign", "Campaign input is invalid"); if (d.ownerType() == OwnerType.SELLER && d.ownerId() == null) throw new ServiceException("invalid_campaign_owner", "Seller is required"); }
  private void validateRule(RuleDraft r) {
    if (r == null || r.lineIds() == null || r.lineIds().isEmpty() || r.discountAmount() < 0
        || r.discountBps() < 0 || r.discountBps() > 10000
        || r.platformShareBps() < 0 || r.platformShareBps() > 10000) {
      throw new ServiceException("invalid_rule", "Promotion rule is invalid");
    }
    if (r.allowZeroPayable() && r.minPayableOverride() != null && r.minPayableOverride() > 0) {
      throw new ServiceException("invalid_zero_payable",
          "Allow-zero payable cannot be combined with a positive minimum payable");
    }
  }
  private static Long nullableLong(ResultSet r, String c) throws SQLException { Object v = r.getObject(c); return v == null ? null : ((Number) v).longValue(); }
  private static Instant toInstant(Timestamp t) { return t == null ? null : t.toInstant(); }
  private static void setTime(PreparedStatement s, int i, Instant v) throws SQLException { if (v == null) s.setObject(i, null); else s.setTimestamp(i, Timestamp.from(v)); }
  private static void setNullableLong(PreparedStatement s, int i, Long v) throws SQLException { if (v == null) s.setObject(i, null); else s.setLong(i, v); }

  enum OwnerType { PLATFORM, SELLER }
  record CampaignDraft(String code, OwnerType ownerType, Long ownerId, String name, String description, Instant startAt, Instant endAt) {}
  record RuleDraft(String ruleKind, String direction, int layer, String slot, String stackingPolicy,
      String exclusiveGroup, int priority, String thresholdType, long thresholdValue,
      int thresholdBasisLayer, int discountBasisLayer, String repeatMode, int maxRepeatCount,
      long discountAmount, int discountBps, Long maxDiscountAmount, String fundingMode,
      int platformShareBps, Long minPayableOverride, boolean allowZeroPayable, Set<String> lineIds,
      String eligibilityJson, String stackingJson, String refundPolicyJson, String displayJson) {}
  record Campaign(long id, String code, String ownerType, Long ownerId, String name, String description,
      String status, Instant startAt, Instant endAt, Long currentVersionId, long version) {}
  record PublishedRule(long ruleVersionId, long campaignId, int version, String rulesHash, String campaignStatus) {}
}
