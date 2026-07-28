package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;

class RefundPolicyService {
  static final String CONFIG_KEY = "refund_policy";
  private static final Policy DEFAULT_POLICY = new Policy(true, true, 10, 3, true, 5);

  private final DatabaseManager databaseManager;
  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

  RefundPolicyService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  Policy getPolicy() {
    return databaseManager.withConnection(this::readPolicy);
  }

  Policy updatePolicy(Policy requested) {
    Policy normalized = normalize(requested);
    return databaseManager.inTransaction(connection -> {
      String json = gson.toJson(normalized);
      int updated;
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE runtime_config SET config_value = ?, version = version + 1, "
              + "updated_at = CURRENT_TIMESTAMP WHERE config_key = ?")) {
        statement.setString(1, json);
        statement.setString(2, CONFIG_KEY);
        updated = statement.executeUpdate();
      }
      if (updated == 0) {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO runtime_config (config_key, config_value, version) VALUES (?, ?, 1)")) {
          statement.setString(1, CONFIG_KEY);
          statement.setString(2, json);
          statement.executeUpdate();
        }
      }
      return normalized;
    });
  }

  ProductPolicy updateProductPolicy(
      long productId, String refundPolicy, Integer windowMinutes, String partialPolicy) {
    String normalizedRefund = normalizedEnum(refundPolicy, "INHERIT");
    String normalizedPartial = normalizedEnum(partialPolicy, "INHERIT");
    if (!java.util.Set.of("INHERIT", "CUSTOM", "DISABLED").contains(normalizedRefund)) {
      throw new ServiceException("invalid_refund_policy", "Invalid product refund policy");
    }
    if (!java.util.Set.of("INHERIT", "ALLOW", "DENY").contains(normalizedPartial)) {
      throw new ServiceException("invalid_partial_refund_policy", "Invalid partial refund policy");
    }
    Integer normalizedWindow = normalizeWindow(windowMinutes);
    if ("CUSTOM".equals(normalizedRefund) && windowMinutes != null && normalizedWindow == 0) {
      normalizedWindow = 0;
    }
    Integer finalWindow = normalizedWindow;
    return databaseManager.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE products
          SET refund_policy = ?, refund_window_minutes = ?, partial_refund_policy = ?
          WHERE id = ?
          """)) {
        statement.setString(1, normalizedRefund);
        if (finalWindow == null) {
          statement.setObject(2, null);
        } else {
          statement.setInt(2, finalWindow);
        }
        statement.setString(3, normalizedPartial);
        statement.setLong(4, productId);
        if (statement.executeUpdate() == 0) {
          throw new ServiceException("product_missing", "Product not found");
        }
      }
      return new ProductPolicy(productId, normalizedRefund, finalWindow, normalizedPartial);
    });
  }

  ResolvedPolicy resolveProductPolicy(
      Connection connection, long productId, boolean dynamicPricing, String productType)
      throws SQLException {
    Policy global = readPolicy(connection);
    String refundPolicy = "INHERIT";
    Integer customWindow = null;
    String partialPolicy = "INHERIT";
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT refund_policy, refund_window_minutes, partial_refund_policy "
            + "FROM products WHERE id = ?")) {
      statement.setLong(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          refundPolicy = normalizedEnum(resultSet.getString("refund_policy"), "INHERIT");
          Object window = resultSet.getObject("refund_window_minutes");
          customWindow = window instanceof Number number ? Math.max(0, number.intValue()) : null;
          partialPolicy = normalizedEnum(resultSet.getString("partial_refund_policy"), "INHERIT");
        }
      }
    }
    boolean allowed = global.selfServiceEnabled() && !"DISABLED".equals(refundPolicy);
    Integer window = "CUSTOM".equals(refundPolicy)
        ? customWindow
        : dynamicPricing ? global.dynamicPriceWindowMinutes() : global.fixedPriceWindowMinutes();
    boolean typeAllowsPartial = isItemLike(productType);
    boolean partialAllowed = switch (partialPolicy) {
      case "ALLOW" -> true;
      case "DENY" -> false;
      default -> global.partialRefundEnabled() && typeAllowsPartial;
    };
    return new ResolvedPolicy(allowed, window, partialAllowed, refundPolicy, partialPolicy);
  }

  void freezeOrderPolicy(
      Connection connection,
      long orderId,
      long productId,
      boolean dynamicPricing,
      String productType,
      LocalDateTime createdAt) throws SQLException {
    ResolvedPolicy resolved =
        resolveProductPolicy(connection, productId, dynamicPricing, productType);
    LocalDateTime deadline = resolved.allowed() && resolved.windowMinutes() != null
        ? createdAt.plusMinutes(resolved.windowMinutes())
        : null;
    JsonObject snapshot = new JsonObject();
    snapshot.addProperty("allowed", resolved.allowed());
    if (resolved.windowMinutes() == null) {
      snapshot.add("windowMinutes", null);
    } else {
      snapshot.addProperty("windowMinutes", resolved.windowMinutes());
    }
    snapshot.addProperty("partialAllowed", resolved.partialAllowed());
    snapshot.addProperty("productPolicy", resolved.productPolicy());
    snapshot.addProperty("partialPolicy", resolved.partialPolicy());
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE orders
        SET refund_allowed = ?, partial_refund_allowed = ?, refund_deadline = ?,
            refund_policy_json = ?
        WHERE id = ?
        """)) {
      statement.setBoolean(1, resolved.allowed());
      statement.setBoolean(2, resolved.partialAllowed());
      if (deadline == null) {
        statement.setObject(3, null);
      } else {
        statement.setTimestamp(3, Timestamp.valueOf(deadline));
      }
      statement.setString(4, gson.toJson(snapshot));
      statement.setLong(5, orderId);
      statement.executeUpdate();
    }
  }

  private Policy readPolicy(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT config_value FROM runtime_config WHERE config_key = ? LIMIT 1")) {
      statement.setString(1, CONFIG_KEY);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return DEFAULT_POLICY;
        }
        try {
          JsonObject root = JsonParser.parseString(resultSet.getString("config_value")).getAsJsonObject();
          return normalize(new Policy(
              booleanValue(root, "selfServiceEnabled", DEFAULT_POLICY.selfServiceEnabled()),
              booleanValue(root, "mailboxPendingRefundEnabled",
                  DEFAULT_POLICY.mailboxPendingRefundEnabled()),
              nullableInt(root, "fixedPriceWindowMinutes",
                  DEFAULT_POLICY.fixedPriceWindowMinutes()),
              nullableInt(root, "dynamicPriceWindowMinutes",
                  DEFAULT_POLICY.dynamicPriceWindowMinutes()),
              booleanValue(root, "partialRefundEnabled", DEFAULT_POLICY.partialRefundEnabled()),
              intValue(root, "maxSelfServiceRefundsPerDay",
                  DEFAULT_POLICY.maxSelfServiceRefundsPerDay())));
        } catch (RuntimeException ignored) {
          return DEFAULT_POLICY;
        }
      }
    }
  }

  private Policy normalize(Policy policy) {
    if (policy == null) {
      return DEFAULT_POLICY;
    }
    return new Policy(
        policy.selfServiceEnabled(),
        policy.mailboxPendingRefundEnabled(),
        normalizeWindow(policy.fixedPriceWindowMinutes()),
        normalizeWindow(policy.dynamicPriceWindowMinutes()),
        policy.partialRefundEnabled(),
        Math.max(0, Math.min(1000, policy.maxSelfServiceRefundsPerDay())));
  }

  private Integer normalizeWindow(Integer value) {
    return value == null ? null : Math.max(0, Math.min(525_600, value));
  }

  private boolean isItemLike(String productType) {
    String normalized = productType == null ? "" : productType.toUpperCase(Locale.ROOT);
    return normalized.contains("ITEM");
  }

  private String normalizedEnum(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
  }

  private boolean booleanValue(JsonObject root, String key, boolean fallback) {
    return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsBoolean() : fallback;
  }

  private int intValue(JsonObject root, String key, int fallback) {
    return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsInt() : fallback;
  }

  private Integer nullableInt(JsonObject root, String key, Integer fallback) {
    return root.has(key) ? root.get(key).isJsonNull() ? null : root.get(key).getAsInt() : fallback;
  }

  record Policy(
      boolean selfServiceEnabled,
      boolean mailboxPendingRefundEnabled,
      Integer fixedPriceWindowMinutes,
      Integer dynamicPriceWindowMinutes,
      boolean partialRefundEnabled,
      int maxSelfServiceRefundsPerDay) {
  }

  record ResolvedPolicy(
      boolean allowed,
      Integer windowMinutes,
      boolean partialAllowed,
      String productPolicy,
      String partialPolicy) {
  }

  record ProductPolicy(
      long productId, String refundPolicy, Integer windowMinutes, String partialPolicy) {
  }
}
