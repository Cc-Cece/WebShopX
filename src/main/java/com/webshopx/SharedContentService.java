package com.webshopx;

import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Platform-neutral content-management use cases shared by Paper and Loader HTTP adapters. */
public final class SharedContentService {
  private final DatabaseManager database;
  private final HomepageService homepage;
  private final MaterialVisualService materialVisuals;
  private final VisualCustomizationService visualCustomization;

  public SharedContentService(DatabaseManager database) {
    this.database = Objects.requireNonNull(database, "database");
    homepage = new HomepageService(database);
    materialVisuals = new MaterialVisualService(database);
    visualCustomization = new VisualCustomizationService(database);
  }

  public JsonObject homepage() {
    return homepage.publicDocument();
  }

  public JsonObject homepageDraft() {
    return homepage.draftState();
  }

  public JsonObject saveHomepageDraft(JsonObject document, String author) {
    return homepage.saveDraft(document, author);
  }

  public JsonObject publishHomepage(String author) {
    return homepage.publish(author);
  }

  public JsonObject homepageRevisions() {
    return homepage.revisions();
  }

  public JsonObject restoreHomepage(String revisionId, String author) {
    return homepage.restore(revisionId, author);
  }

  public JsonObject homepageAssets() {
    return homepage.assets();
  }

  public List<JsonObject> materialOverrides() {
    return materialJson(materialVisuals.listAll());
  }

  public List<JsonObject> materialOverrides(String keyword, int limit) {
    return materialJson(materialVisuals.list(keyword, limit));
  }

  public JsonObject upsertMaterialOverride(
      String materialKey, String displayNameOverride, String iconPath, String updatedBy) {
    return materialJson(
        materialVisuals.upsert(materialKey, displayNameOverride, iconPath, updatedBy));
  }

  public boolean deleteMaterialOverride(String materialKey) {
    return materialVisuals.delete(materialKey);
  }

  public JsonObject userVisualPermission(long userId, int globalListingLimit) {
    return userVisualPermissionJson(
        visualCustomization.resolvePermission(userId),
        readListingLimitOverride(userId),
        globalListingLimit);
  }

  public JsonObject updateUserVisualPermission(
      long userId,
      String iconPermission,
      String namePermission,
      String uploadPermission,
      Integer listingLimitOverride,
      int globalListingLimit) {
    visualCustomization.upsertUserPermission(
        userId,
        VisualCustomizationService.VisualPermission.fromRaw(iconPermission),
        VisualCustomizationService.VisualPermission.fromRaw(namePermission),
        VisualCustomizationService.VisualPermission.fromRaw(uploadPermission));
    upsertListingLimitOverride(userId, listingLimitOverride);
    return userVisualPermission(userId, globalListingLimit);
  }

  private Integer readListingLimitOverride(long userId) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(
              "SELECT listing_limit_override FROM user_market_settings WHERE user_id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) return null;
              Object value = result.getObject(1);
              return value == null ? null : ((Number) value).intValue();
            }
          }
        });
  }

  private void upsertListingLimitOverride(long userId, Integer override) {
    Integer normalized = override == null || override <= 0 ? null : Math.min(override, 1_000);
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(database.sqlProvider().upsertUserMarketSettingsSql())) {
            statement.setLong(1, userId);
            if (normalized == null) statement.setObject(2, null);
            else statement.setInt(2, normalized);
            statement.executeUpdate();
          }
          return null;
        });
  }

  private static JsonObject userVisualPermissionJson(
      VisualCustomizationService.ResolvedPermission resolved,
      Integer listingLimitOverride,
      int globalListingLimit) {
    int global = Math.max(1, globalListingLimit);
    JsonObject result = new JsonObject();
    result.addProperty("userId", resolved.userId());
    result.addProperty("iconPermission", resolved.iconPermission().name());
    result.addProperty("namePermission", resolved.namePermission().name());
    result.addProperty("uploadPermission", resolved.uploadPermission().name());
    result.addProperty("customIconAllowed", resolved.customIconAllowed());
    result.addProperty("customNameAllowed", resolved.customNameAllowed());
    result.addProperty("customUploadAllowed", resolved.customUploadAllowed());
    result.add("settings", visualSettingsJson(resolved.settings()));
    result.addProperty(
        "listingLimitEffective", listingLimitOverride == null ? global : listingLimitOverride);
    result.addProperty(
        "listingLimitSource", listingLimitOverride == null ? "GLOBAL_DEFAULT" : "USER_OVERRIDE");
    result.addProperty("playerOnline", false);
    result.addProperty("globalDefaultLimit", global);
    if (listingLimitOverride == null) result.add("listingLimitOverride", null);
    else result.addProperty("listingLimitOverride", listingLimitOverride);
    result.add("permissionLimit", null);
    return result;
  }

  private static JsonObject visualSettingsJson(
      VisualCustomizationService.VisualSettings settings) {
    JsonObject result = new JsonObject();
    result.addProperty("globalCustomIconEnabled", settings.globalCustomIconEnabled());
    result.addProperty("globalCustomNameEnabled", settings.globalCustomNameEnabled());
    result.addProperty(
        "officialProductCustomIconEnabled", settings.officialProductCustomIconEnabled());
    result.addProperty(
        "officialProductCustomNameEnabled", settings.officialProductCustomNameEnabled());
    result.addProperty(
        "officialProductUploadImageEnabled", settings.officialProductUploadImageEnabled());
    result.addProperty(
        "marketListingCustomIconEnabled", settings.marketListingCustomIconEnabled());
    result.addProperty(
        "marketListingCustomNameEnabled", settings.marketListingCustomNameEnabled());
    result.addProperty(
        "marketListingUploadImageEnabled", settings.marketListingUploadImageEnabled());
    result.addProperty("iconPolicyMode", settings.iconPolicyMode().name());
    result.addProperty("namePolicyMode", settings.namePolicyMode().name());
    result.add("iconPriority", CommerceJson.create().toJsonTree(settings.iconPriority()));
    result.add("namePriority", CommerceJson.create().toJsonTree(settings.namePriority()));
    return result;
  }

  public LeaderboardResult leaderboard(
      String metricRaw, String orderRaw, String rangeRaw, int requestedLimit, Long viewerUserId) {
    String metric = normalizeChoice(metricRaw, "SHOP_COIN", "SHOP_COIN", "GAME_COIN");
    String order = normalizeChoice(orderRaw, "DESC", "ASC", "DESC");
    String range = normalizeChoice(rangeRaw, "TOTAL", "TOTAL", "WEEK", "MONTH");
    int limit = Math.max(1, Math.min(requestedLimit, 200));
    List<LeaderboardUser> users = readLeaderboardUsers();
    Map<Long, Long> rangeScores =
        range.equals("TOTAL") ? Map.of() : readRangeScores(metric, range);
    List<RankedLeaderboardUser> ranked = new ArrayList<>(users.size());
    for (LeaderboardUser user : users) {
      long score =
          range.equals("TOTAL")
              ? (metric.equals("SHOP_COIN") ? user.shopCoin() : user.gameCoin())
              : rangeScores.getOrDefault(user.userId(), 0L);
      ranked.add(new RankedLeaderboardUser(user, score));
    }
    Comparator<RankedLeaderboardUser> comparator =
        Comparator.comparingLong(RankedLeaderboardUser::score)
            .thenComparing(row -> row.user().createdAt())
            .thenComparingLong(row -> row.user().userId());
    if (order.equals("DESC")) comparator = comparator.reversed();
    ranked.sort(comparator);
    List<LeaderboardEntry> entries = new ArrayList<>();
    Long myRank = null;
    for (int index = 0; index < ranked.size(); index++) {
      RankedLeaderboardUser row = ranked.get(index);
      long rank = index + 1L;
      if (viewerUserId != null && viewerUserId.longValue() == row.user().userId()) myRank = rank;
      if (entries.size() < limit) {
        entries.add(
            new LeaderboardEntry(
                rank,
                row.user().userId(),
                row.user().username(),
                row.user().boundUuid(),
                row.user().shopCoin(),
                row.user().gameCoin(),
                0L,
                row.score(),
                false));
      }
    }
    return new LeaderboardResult(
        metric, order, range, range, ranked.size(), myRank, List.copyOf(entries));
  }

  public Map<String, Long> overviewStats() {
    return database.withConnection(
        connection -> {
          Map<String, Long> values = new java.util.LinkedHashMap<>();
          values.put("onlinePlayers", 0L);
          values.put("maxPlayers", 0L);
          values.put("totalUsers", count(connection, "SELECT COUNT(*) FROM web_users"));
          values.put(
              "boundUsers",
              count(connection, "SELECT COUNT(*) FROM web_users WHERE bound_uuid IS NOT NULL"));
          values.put("totalProducts", count(connection, "SELECT COUNT(*) FROM products"));
          values.put(
              "activeProducts",
              count(connection, "SELECT COUNT(*) FROM products WHERE active=TRUE"));
          values.put("totalOrders", count(connection, "SELECT COUNT(*) FROM orders"));
          values.put(
              "completedOrders",
              count(
                  connection,
                  "SELECT COUNT(*) FROM orders WHERE UPPER(status) IN"
                      + " ('ISSUED','COMPLETED','DELIVERED')"));
          values.put(
              "refundedOrders",
              count(connection, "SELECT COUNT(*) FROM orders WHERE UPPER(status)='REFUNDED'"));
          values.put(
              "totalRevenue",
              count(
                  connection,
                  "SELECT COALESCE(SUM(total_amount),0) FROM orders"
                      + " WHERE UPPER(status)<>'REFUNDED'"));
          values.put(
              "activeListings",
              count(
                  connection,
                  "SELECT COUNT(*) FROM market_listings WHERE UPPER(status)='ACTIVE'"));
          values.put("totalTrades", count(connection, "SELECT COUNT(*) FROM market_trades"));
          values.put(
              "completedTrades",
              count(
                  connection,
                  "SELECT COUNT(*) FROM market_trades WHERE UPPER(status) IN"
                      + " ('PAID','SETTLED','COMPLETED','DELIVERED')"));
          values.put(
              "totalTradeVolume",
              count(
                  connection,
                  "SELECT COALESCE(SUM(total_price),0) FROM market_trades"
                      + " WHERE UPPER(status)<>'REFUNDED'"));
          values.put("totalRedeemCodes", count(connection, "SELECT COUNT(*) FROM redeem_codes"));
          values.put(
              "totalRedeemUses",
              count(connection, "SELECT COALESCE(SUM(used_count),0) FROM redeem_codes"));
          return Map.copyOf(values);
        });
  }

  private static long count(java.sql.Connection connection, String sql) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      return result.next() ? result.getLong(1) : 0L;
    }
  }

  private List<LeaderboardUser> readLeaderboardUsers() {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT u.id,u.username,u.bound_uuid,u.created_at,"
                          + "COALESCE(w.shop_coin,0),COALESCE(w.game_coin,0) FROM web_users u"
                          + " LEFT JOIN wallets w ON w.user_id=u.id WHERE u.auth_state='ACTIVE'");
              ResultSet result = statement.executeQuery()) {
            List<LeaderboardUser> values = new ArrayList<>();
            while (result.next()) {
              String uuid = result.getString(3);
              values.add(
                  new LeaderboardUser(
                      result.getLong(1),
                      result.getString(2),
                      uuid == null || uuid.isBlank() ? null : UUID.fromString(uuid),
                      result.getTimestamp(4).toInstant(),
                      result.getLong(5),
                      result.getLong(6)));
            }
            return values;
          }
        });
  }

  private Map<Long, Long> readRangeScores(String metric, String range) {
    Instant since =
        Instant.now().minus(range.equals("WEEK") ? 7L : 30L, ChronoUnit.DAYS);
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT w.user_id,COALESCE(SUM(l.delta),0) FROM wallet_ledger l"
                      + " JOIN wallets w ON w.id=l.wallet_id WHERE l.currency=?"
                      + " AND l.created_at>=? GROUP BY w.user_id")) {
            statement.setString(1, metric);
            statement.setTimestamp(2, Timestamp.from(since));
            try (ResultSet result = statement.executeQuery()) {
              Map<Long, Long> values = new HashMap<>();
              while (result.next()) values.put(result.getLong(1), result.getLong(2));
              return Map.copyOf(values);
            }
          }
        });
  }

  private static String normalizeChoice(String raw, String fallback, String... choices) {
    String value = raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
    for (String choice : choices) {
      if (choice.equals(value)) return value;
    }
    throw new ServiceException("invalid_leaderboard_filter", "Leaderboard filter is invalid");
  }

  private static List<JsonObject> materialJson(
      List<MaterialVisualService.MaterialVisualEntry> entries) {
    List<JsonObject> result = new ArrayList<>(entries.size());
    entries.forEach(entry -> result.add(materialJson(entry)));
    return List.copyOf(result);
  }

  private static JsonObject materialJson(MaterialVisualService.MaterialVisualEntry entry) {
    JsonObject result = new JsonObject();
    result.addProperty("materialKey", entry.materialKey());
    if (entry.displayNameOverride() == null) result.add("displayNameOverride", null);
    else result.addProperty("displayNameOverride", entry.displayNameOverride());
    if (entry.iconPath() == null) result.add("iconPath", null);
    else result.addProperty("iconPath", entry.iconPath());
    result.addProperty("updatedBy", entry.updatedBy());
    if (entry.updatedAt() == null) result.add("updatedAt", null);
    else result.addProperty("updatedAt", entry.updatedAt().toString());
    return result;
  }

  public record LeaderboardEntry(
      long rank,
      long userId,
      String username,
      UUID boundUuid,
      long shopCoin,
      long gameCoin,
      long onlineTimeMinutes,
      long score,
      boolean online) {}

  public record LeaderboardResult(
      String metric,
      String order,
      String requestedRange,
      String effectiveRange,
      long total,
      Long myRank,
      List<LeaderboardEntry> entries) {}

  private record LeaderboardUser(
      long userId,
      String username,
      UUID boundUuid,
      Instant createdAt,
      long shopCoin,
      long gameCoin) {}

  private record RankedLeaderboardUser(LeaderboardUser user, long score) {}
}
