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

  public SharedContentService(DatabaseManager database) {
    this.database = Objects.requireNonNull(database, "database");
    homepage = new HomepageService(database);
    materialVisuals = new MaterialVisualService(database);
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
