package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.webshopx.promotion.pricing.MathSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Direction-aware benefits for recycle payouts, recharge, auctions and other non-cart domains. */
final class DomainBenefitService {
  private final DatabaseManager databaseManager;
  private final Gson gson = CommerceJson.create();

  DomainBenefitService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  BenefitQuote quote(
      Connection connection,
      long userId,
      String direction,
      String sourceKey,
      String currency,
      long baseAmount,
      int quantity)
      throws SQLException {
    Instant now = Instant.now();
    Set<String> entitlements = entitlements(connection, userId, now);
    List<AppliedBenefit> applications = new ArrayList<>();
    long value = baseAmount;
    String sql =
        "SELECT r.* FROM promotion_rule_versions r JOIN promotion_campaigns c "
            + "ON c.current_version_id = r.id WHERE c.status = 'ACTIVE' "
            + "AND r.direction = ? AND (c.start_at IS NULL OR c.start_at <= ?) "
            + "AND (c.end_at IS NULL OR c.end_at > ?) ORDER BY r.layer, r.priority, r.id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, direction);
      statement.setTimestamp(2, Timestamp.from(now));
      statement.setTimestamp(3, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          if (!matches(result.getString("scope_json"), sourceKey)) continue;
          if (!eligible(result.getString("eligibility_json"), entitlements)) continue;
          String thresholdType = result.getString("threshold_type");
          long measured = "QUANTITY".equals(thresholdType) ? quantity : baseAmount;
          if (!"NONE".equals(thresholdType) && measured < result.getLong("threshold_value")) {
            continue;
          }
          int repeats = 1;
          if ("EVERY_FULL_THRESHOLD".equals(result.getString("repeat_mode"))) {
            repeats =
                (int)
                    Math.min(
                        result.getInt("max_repeat_count"),
                        measured / Math.max(1, result.getLong("threshold_value")));
          }
          long adjustment =
              result.getLong("discount_amount") > 0
                  ? Math.multiplyExact(result.getLong("discount_amount"), repeats)
                  : MathSupport.roundHalfUp(value, result.getInt("discount_bps"));
          Object cap = result.getObject("max_discount_amount");
          if (cap != null) adjustment = Math.min(adjustment, ((Number) cap).longValue());
          if (adjustment <= 0) continue;
          if ("USER_RECEIVES".equals(direction)) value = Math.addExact(value, adjustment);
          else
            value =
                Math.max(
                    result.getBoolean("allow_zero_payable") ? 0 : 1,
                    Math.subtractExact(value, Math.min(value, adjustment)));
          applications.add(
              new AppliedBenefit(
                  result.getLong("id"),
                  result.getInt("layer"),
                  result.getString("stacking_slot"),
                  adjustment,
                  result.getString("funding_mode"),
                  result.getInt("platform_share_bps")));
        }
      }
    }
    return new BenefitQuote(
        direction, sourceKey, currency, baseAmount, value, List.copyOf(applications));
  }

  void persistGrant(Connection connection, BenefitQuote quote, String bizKey) throws SQLException {
    long adjustment = Math.abs(quote.finalAmount() - quote.baseAmount());
    if (adjustment == 0) return;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO benefit_grants (benefit_type, benefit_ref, amount, currency, "
                + "trigger_status, status, grant_biz_key, granted_at, detail_json) "
                + "VALUES (?, ?, ?, ?, 'COMPLETED', 'GRANTED', ?, CURRENT_TIMESTAMP, ?)")) {
      statement.setString(1, quote.direction());
      statement.setString(2, quote.sourceKey());
      statement.setLong(3, adjustment);
      statement.setString(4, quote.currency());
      statement.setString(5, bizKey);
      statement.setString(6, gson.toJson(quote));
      statement.executeUpdate();
    }
  }

  private Set<String> entitlements(Connection connection, long userId, Instant now)
      throws SQLException {
    Set<String> values = new HashSet<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT entitlement_code FROM user_entitlements WHERE user_id = ? "
                + "AND status = 'ACTIVE' AND starts_at <= ? "
                + "AND (expires_at IS NULL OR expires_at > ?)")) {
      statement.setLong(1, userId);
      statement.setTimestamp(2, Timestamp.from(now));
      statement.setTimestamp(3, Timestamp.from(now));
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) values.add(result.getString(1));
      }
    }
    return values;
  }

  private boolean matches(String raw, String sourceKey) {
    try {
      JsonArray scopes = gson.fromJson(raw, JsonArray.class);
      for (var scope : scopes) {
        String value = scope.getAsString();
        if ("*".equals(value)
            || value.equals(sourceKey)
            || value.endsWith(":*")
                && sourceKey.startsWith(value.substring(0, value.length() - 1))) {
          return true;
        }
      }
    } catch (RuntimeException ignored) {
      // Invalid published scope is ignored rather than broadening eligibility.
    }
    return false;
  }

  private boolean eligible(String raw, Set<String> entitlements) {
    try {
      JsonObject value = gson.fromJson(raw, JsonObject.class);
      return value == null
          || !value.has("requiredEntitlement")
          || entitlements.contains(value.get("requiredEntitlement").getAsString());
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  record AppliedBenefit(
      long ruleVersionId,
      int layer,
      String slot,
      long adjustmentAmount,
      String fundingMode,
      int platformShareBps) {}

  record BenefitQuote(
      String direction,
      String sourceKey,
      String currency,
      long baseAmount,
      long finalAmount,
      List<AppliedBenefit> applications) {}
}
