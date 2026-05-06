package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

class LeaderboardService {
  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;

  LeaderboardService(JavaPlugin plugin, DatabaseManager databaseManager) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
  }

  LeaderboardResult list(
      String metricRaw,
      String orderRaw,
      String rangeRaw,
      int limit,
      Long viewerUserId,
      boolean includeOnlineStatus,
      PluginSettings.LeaderboardSettings settings) {
    PluginSettings.LeaderboardMetric metric =
        metricRaw == null || metricRaw.isBlank()
            ? settings.defaultMetric()
            : PluginSettings.LeaderboardMetric.fromRaw(metricRaw);
    PluginSettings.SortDirection order =
        orderRaw == null || orderRaw.isBlank()
            ? settings.defaultOrder()
            : PluginSettings.SortDirection.fromRaw(orderRaw);
    LeaderboardRange requestedRange = LeaderboardRange.fromRaw(rangeRaw);
    LeaderboardRange effectiveRange =
        metric == PluginSettings.LeaderboardMetric.ONLINE_TIME
            ? LeaderboardRange.TOTAL
            : requestedRange;
    int normalizedLimit = Math.max(1, Math.min(limit, 200));

    List<UserBaseRow> baseRows = readBaseRows(Math.max(600, normalizedLimit * 6));
    Map<Long, Long> rangeScores = readRangeScores(metric, effectiveRange);
    Map<Long, OnlineRuntimeStat> runtimeStats =
        readRuntimeStats(baseRows, metric == PluginSettings.LeaderboardMetric.ONLINE_TIME || includeOnlineStatus);

    List<RankedUser> ranked = new ArrayList<>();
    for (UserBaseRow row : baseRows) {
      OnlineRuntimeStat runtime = runtimeStats.getOrDefault(row.userId(), OnlineRuntimeStat.EMPTY);
      long score = computeScore(row, metric, effectiveRange, rangeScores, runtime);
      ranked.add(new RankedUser(row, score, runtime));
    }

    Comparator<RankedUser> comparator = Comparator
        .comparingLong(RankedUser::score)
        .thenComparing(r -> r.base().createdAt())
        .thenComparingLong(r -> r.base().userId());
    if (order == PluginSettings.SortDirection.DESC) {
      comparator = comparator.reversed();
    }
    ranked.sort(comparator);

    long total = ranked.size();
    List<LeaderboardEntry> entries = new ArrayList<>();
    Long myRank = null;
    int rank = 1;
    for (RankedUser row : ranked) {
      if (viewerUserId != null && viewerUserId == row.base().userId()) {
        myRank = (long) rank;
      }
      if (entries.size() < normalizedLimit) {
        entries.add(new LeaderboardEntry(
            rank,
            row.base().userId(),
            row.base().username(),
            row.base().boundUuid(),
            row.base().shopCoin(),
            row.base().gameCoin(),
            row.runtime().onlineTimeMinutes(),
            row.score(),
            includeOnlineStatus && row.runtime().online()));
      }
      rank++;
    }

    return new LeaderboardResult(metric, order, requestedRange, effectiveRange, total, myRank, entries);
  }

  private List<UserBaseRow> readBaseRows(int limit) {
    int normalizedLimit = Math.max(1, Math.min(limit, 5000));
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT u.id, u.username, u.bound_uuid, u.created_at,
                 COALESCE(w.shop_coin, 0) AS shop_coin,
                 COALESCE(w.game_coin, 0) AS game_coin
          FROM web_users u
          LEFT JOIN wallets w ON w.user_id = u.id
          WHERE u.auth_state = 'ACTIVE'
          ORDER BY u.created_at DESC, u.id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, normalizedLimit);
        try (ResultSet resultSet = statement.executeQuery()) {
          List<UserBaseRow> rows = new ArrayList<>();
          while (resultSet.next()) {
            String rawBoundUuid = resultSet.getString("bound_uuid");
            UUID boundUuid = null;
            if (rawBoundUuid != null && !rawBoundUuid.isBlank()) {
              try {
                boundUuid = UUID.fromString(rawBoundUuid);
              } catch (IllegalArgumentException ignored) {
                boundUuid = null;
              }
            }
            rows.add(new UserBaseRow(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                boundUuid,
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getLong("shop_coin"),
                resultSet.getLong("game_coin")));
          }
          return rows;
        }
      }
    });
  }

  private Map<Long, Long> readRangeScores(
      PluginSettings.LeaderboardMetric metric,
      LeaderboardRange range) {
    if (range == LeaderboardRange.TOTAL) {
      return Map.of();
    }
    CurrencyType currency;
    if (metric == PluginSettings.LeaderboardMetric.SHOP_COIN) {
      currency = CurrencyType.SHOP_COIN;
    } else if (metric == PluginSettings.LeaderboardMetric.GAME_COIN) {
      currency = CurrencyType.GAME_COIN;
    } else {
      return Map.of();
    }

    LocalDateTime since = switch (range) {
      case WEEK -> LocalDateTime.now().minusDays(7);
      case MONTH -> LocalDateTime.now().minusDays(30);
      default -> null;
    };
    if (since == null) {
      return Map.of();
    }

    return databaseManager.withConnection(connection -> readLedgerDeltaScores(connection, currency, since));
  }

  private Map<Long, Long> readLedgerDeltaScores(
      Connection connection,
      CurrencyType currency,
      LocalDateTime since) throws SQLException {
    String sql = """
        SELECT w.user_id, COALESCE(SUM(l.delta), 0) AS score
        FROM wallet_ledger l
        JOIN wallets w ON w.id = l.wallet_id
        WHERE l.currency = ? AND l.created_at >= ?
        GROUP BY w.user_id
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, currency.name());
      statement.setTimestamp(2, Timestamp.valueOf(since));
      try (ResultSet resultSet = statement.executeQuery()) {
        Map<Long, Long> rows = new HashMap<>();
        while (resultSet.next()) {
          rows.put(resultSet.getLong("user_id"), resultSet.getLong("score"));
        }
        return rows;
      }
    }
  }

  private Map<Long, OnlineRuntimeStat> readRuntimeStats(List<UserBaseRow> users, boolean needRuntime) {
    if (!needRuntime || users.isEmpty()) {
      return Map.of();
    }

    try {
      return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
        Map<Long, OnlineRuntimeStat> stats = new HashMap<>();
        for (UserBaseRow row : users) {
          boolean online = isOnline(row);
          long onlineMinutes = readOnlineTimeMinutes(row);
          stats.put(row.userId(), new OnlineRuntimeStat(online, onlineMinutes));
        }
        return stats;
      }).get();
    } catch (Exception exception) {
      plugin.getLogger().warning("Failed to read leaderboard runtime stats: " + exception.getMessage());
      return users.stream().collect(Collectors.toMap(UserBaseRow::userId, ignored -> OnlineRuntimeStat.EMPTY));
    }
  }

  private boolean isOnline(UserBaseRow row) {
    Player player = null;
    if (row.boundUuid() != null) {
      player = Bukkit.getPlayer(row.boundUuid());
    }
    if (player == null && row.username() != null && !row.username().isBlank()) {
      player = Bukkit.getPlayerExact(row.username());
    }
    return player != null && player.isOnline();
  }

  private long readOnlineTimeMinutes(UserBaseRow row) {
    try {
      OfflinePlayer player;
      if (row.boundUuid() != null) {
        player = Bukkit.getOfflinePlayer(row.boundUuid());
      } else if (row.username() != null && !row.username().isBlank()) {
        player = Bukkit.getOfflinePlayer(row.username());
      } else {
        return 0L;
      }
      int ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
      return Math.max(0L, ticks / 20L / 60L);
    } catch (Exception exception) {
      return 0L;
    }
  }

  private long computeScore(
      UserBaseRow row,
      PluginSettings.LeaderboardMetric metric,
      LeaderboardRange range,
      Map<Long, Long> rangeScores,
      OnlineRuntimeStat runtime) {
    if (metric == PluginSettings.LeaderboardMetric.SHOP_COIN) {
      return range == LeaderboardRange.TOTAL ? row.shopCoin() : rangeScores.getOrDefault(row.userId(), 0L);
    }
    if (metric == PluginSettings.LeaderboardMetric.GAME_COIN) {
      return range == LeaderboardRange.TOTAL ? row.gameCoin() : rangeScores.getOrDefault(row.userId(), 0L);
    }
    return runtime.onlineTimeMinutes();
  }

  enum LeaderboardRange {
    TOTAL,
    WEEK,
    MONTH;

    static LeaderboardRange fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return TOTAL;
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      if ("WEEK".equals(normalized) || "7D".equals(normalized)) {
        return WEEK;
      }
      if ("MONTH".equals(normalized) || "30D".equals(normalized)) {
        return MONTH;
      }
      return TOTAL;
    }
  }

  record LeaderboardResult(
      PluginSettings.LeaderboardMetric metric,
      PluginSettings.SortDirection order,
      LeaderboardRange requestedRange,
      LeaderboardRange effectiveRange,
      long total,
      Long myRank,
      List<LeaderboardEntry> entries) {
  }

  record LeaderboardEntry(
      int rank,
      long userId,
      String username,
      UUID boundUuid,
      long shopCoin,
      long gameCoin,
      long onlineTimeMinutes,
      long score,
      boolean online) {
  }

  private record UserBaseRow(
      long userId,
      String username,
      UUID boundUuid,
      LocalDateTime createdAt,
      long shopCoin,
      long gameCoin) {
  }

  private record RankedUser(UserBaseRow base, long score, OnlineRuntimeStat runtime) {
  }

  private record OnlineRuntimeStat(boolean online, long onlineTimeMinutes) {
    private static final OnlineRuntimeStat EMPTY = new OnlineRuntimeStat(false, 0L);
  }
}

