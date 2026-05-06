package com.webshopx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

class UserMarketSettingsService {
  private final DatabaseManager databaseManager;

  UserMarketSettingsService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  UserMarketSettings readUserSettings(long userId) {
    if (userId <= 0L) {
      throw new ServiceException("bad_request", "User id must be positive");
    }
    return databaseManager.withConnection(connection -> readUserSettings(connection, userId));
  }

  UserMarketSettings upsertUserSettings(long userId, Integer listingLimitOverride) {
    if (userId <= 0L) {
      throw new ServiceException("bad_request", "User id must be positive");
    }
    Integer normalized = normalizeListingLimit(listingLimitOverride);
    return databaseManager.inTransaction(connection -> {
      ensureUserExists(connection, userId);
      String sql =
          databaseManager.dbType().isSqlite()
              ? """
              INSERT INTO user_market_settings (user_id, listing_limit_override)
              VALUES (?, ?)
              ON CONFLICT(user_id) DO UPDATE SET
                listing_limit_override = excluded.listing_limit_override,
                updated_at = CURRENT_TIMESTAMP
              """
              : """
              INSERT INTO user_market_settings (user_id, listing_limit_override)
              VALUES (?, ?)
              ON DUPLICATE KEY UPDATE
                listing_limit_override = VALUES(listing_limit_override),
                updated_at = CURRENT_TIMESTAMP
              """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        if (normalized == null) {
          statement.setNull(2, java.sql.Types.INTEGER);
        } else {
          statement.setInt(2, normalized);
        }
        statement.executeUpdate();
      }
      return readUserSettings(connection, userId);
    });
  }

  ResolvedListingLimit resolveListingLimit(long userId, UUID boundUuid, int globalDefaultLimit) {
    int baseLimit = Math.max(1, globalDefaultLimit);
    UserMarketSettings settings = readUserSettings(userId);
    Integer override = settings.listingLimitOverride();
    Player player = boundUuid == null ? null : Bukkit.getPlayer(boundUuid);
    Integer permissionLimit = player == null ? null : resolvePermissionLimit(player, baseLimit);

    if (override != null && override > 0) {
      return new ResolvedListingLimit(
          userId,
          override,
          ListingLimitSource.USER_OVERRIDE,
          override,
          permissionLimit,
          baseLimit,
          player != null,
          settings.updatedAt());
    }
    if (permissionLimit != null && permissionLimit > baseLimit) {
      return new ResolvedListingLimit(
          userId,
          permissionLimit,
          ListingLimitSource.PERMISSION_NODE,
          null,
          permissionLimit,
          baseLimit,
          true,
          settings.updatedAt());
    }
    return new ResolvedListingLimit(
        userId,
        baseLimit,
        ListingLimitSource.GLOBAL_DEFAULT,
        null,
        permissionLimit,
        baseLimit,
        player != null,
        settings.updatedAt());
  }

  private UserMarketSettings readUserSettings(Connection connection, long userId) throws SQLException {
    String sql = """
        SELECT user_id, listing_limit_override, updated_at
        FROM user_market_settings
        WHERE user_id = ?
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return new UserMarketSettings(userId, null, null);
        }
        java.sql.Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        Integer override = (Integer) resultSet.getObject("listing_limit_override");
        return new UserMarketSettings(
            resultSet.getLong("user_id"),
            normalizeListingLimit(override),
            updatedAt == null ? null : updatedAt.toLocalDateTime());
      }
    }
  }

  private Integer normalizeListingLimit(Integer value) {
    if (value == null) {
      return null;
    }
    return value > 0 ? Math.min(value, 1000) : null;
  }

  private int resolvePermissionLimit(Player player, int baseLimit) {
    int maxLimit = Math.max(1, baseLimit);
    for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
      if (!info.getValue()) {
        continue;
      }
      String permission = info.getPermission();
      if (permission == null) {
        continue;
      }
      String normalized = permission.toLowerCase(Locale.ROOT);
      if (!normalized.startsWith("webshop.market.limit.")) {
        continue;
      }
      String suffix = normalized.substring("webshop.market.limit.".length());
      try {
        int value = Integer.parseInt(suffix);
        if (value > maxLimit) {
          maxLimit = value;
        }
      } catch (NumberFormatException ignored) {
        continue;
      }
    }
    return maxLimit;
  }

  private void ensureUserExists(Connection connection, long userId) throws SQLException {
    String sql = "SELECT 1 FROM web_users WHERE id = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("not_found", "User not found");
        }
      }
    }
  }

  enum ListingLimitSource {
    USER_OVERRIDE,
    PERMISSION_NODE,
    GLOBAL_DEFAULT
  }

  record UserMarketSettings(long userId, Integer listingLimitOverride, LocalDateTime updatedAt) {
  }

  record ResolvedListingLimit(
      long userId,
      int effectiveLimit,
      ListingLimitSource source,
      Integer listingLimitOverride,
      Integer permissionLimit,
      int globalDefaultLimit,
      boolean playerOnline,
      LocalDateTime updatedAt) {
  }
}
