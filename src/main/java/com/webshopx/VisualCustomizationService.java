package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;

class VisualCustomizationService {
  private static final String SETTINGS_KEY = "visual_customization";

  private final DatabaseManager databaseManager;
  private final Gson gson;

  VisualCustomizationService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  VisualSettings readSettings() {
    return databaseManager.withConnection(this::readSettings);
  }

  long updateSettings(VisualSettings settings) {
    VisualSettings normalized = normalizeSettings(settings);
    return databaseManager.inTransaction(connection -> {
      String sql = """
          INSERT INTO runtime_config (config_key, config_value, version)
          VALUES (?, ?, 1)
          ON DUPLICATE KEY UPDATE
            config_value = VALUES(config_value),
            version = version + 1,
            updated_at = CURRENT_TIMESTAMP
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, SETTINGS_KEY);
        statement.setString(2, serializeSettings(normalized));
        statement.executeUpdate();
      }
      return readConfigVersion(connection, SETTINGS_KEY);
    });
  }

  UserVisualPermission readUserPermission(long userId) {
    if (userId <= 0L) {
      throw new ServiceException("bad_request", "User id must be positive");
    }
    return databaseManager.withConnection(connection -> readUserPermission(connection, userId));
  }

  UserVisualPermission upsertUserPermission(
      long userId,
      VisualPermission iconPermission,
      VisualPermission namePermission) {
    if (userId <= 0L) {
      throw new ServiceException("bad_request", "User id must be positive");
    }
    VisualPermission normalizedIcon = iconPermission == null ? VisualPermission.INHERIT : iconPermission;
    VisualPermission normalizedName = namePermission == null ? VisualPermission.INHERIT : namePermission;
    return databaseManager.inTransaction(connection -> {
      ensureUserExists(connection, userId);
      String sql = """
          INSERT INTO user_visual_permissions (user_id, icon_permission, name_permission)
          VALUES (?, ?, ?)
          ON DUPLICATE KEY UPDATE
            icon_permission = VALUES(icon_permission),
            name_permission = VALUES(name_permission),
            updated_at = CURRENT_TIMESTAMP
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setString(2, normalizedIcon.name());
        statement.setString(3, normalizedName.name());
        statement.executeUpdate();
      }
      return readUserPermission(connection, userId);
    });
  }

  ResolvedPermission resolvePermission(long userId) {
    VisualSettings settings = readSettings();
    UserVisualPermission userPermission = readUserPermission(userId);
    boolean allowIcon = resolveToggle(settings.globalCustomIconEnabled(), userPermission.iconPermission());
    boolean allowName = resolveToggle(settings.globalCustomNameEnabled(), userPermission.namePermission());
    return new ResolvedPermission(
        userPermission.userId(),
        allowIcon,
        allowName,
        userPermission.iconPermission(),
        userPermission.namePermission(),
        settings);
  }

  private VisualSettings readSettings(Connection connection) throws SQLException {
    String sql = """
        SELECT config_value
        FROM runtime_config
        WHERE config_key = ?
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, SETTINGS_KEY);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return VisualSettings.defaults();
        }
        String raw = resultSet.getString("config_value");
        return parseSettings(raw);
      }
    }
  }

  private VisualSettings parseSettings(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return VisualSettings.defaults();
    }
    try {
      JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
      return normalizeSettings(new VisualSettings(
          readBoolean(root, "globalCustomIconEnabled", true),
          readBoolean(root, "globalCustomNameEnabled", true),
          VisualPolicyMode.fromRaw(readString(root, "iconPolicyMode", "SOFT")),
          VisualPolicyMode.fromRaw(readString(root, "namePolicyMode", "SOFT"))));
    } catch (Exception exception) {
      return VisualSettings.defaults();
    }
  }

  private String serializeSettings(VisualSettings settings) {
    JsonObject root = new JsonObject();
    root.addProperty("globalCustomIconEnabled", settings.globalCustomIconEnabled());
    root.addProperty("globalCustomNameEnabled", settings.globalCustomNameEnabled());
    root.addProperty("iconPolicyMode", settings.iconPolicyMode().name());
    root.addProperty("namePolicyMode", settings.namePolicyMode().name());
    return gson.toJson(root);
  }

  private VisualSettings normalizeSettings(VisualSettings settings) {
    if (settings == null) {
      return VisualSettings.defaults();
    }
    return new VisualSettings(
        settings.globalCustomIconEnabled(),
        settings.globalCustomNameEnabled(),
        settings.iconPolicyMode() == null ? VisualPolicyMode.SOFT : settings.iconPolicyMode(),
        settings.namePolicyMode() == null ? VisualPolicyMode.SOFT : settings.namePolicyMode());
  }

  private UserVisualPermission readUserPermission(Connection connection, long userId) throws SQLException {
    String sql = """
        SELECT user_id, icon_permission, name_permission, updated_at
        FROM user_visual_permissions
        WHERE user_id = ?
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return new UserVisualPermission(userId, VisualPermission.INHERIT, VisualPermission.INHERIT, null);
        }
        java.sql.Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new UserVisualPermission(
            resultSet.getLong("user_id"),
            VisualPermission.fromRaw(resultSet.getString("icon_permission")),
            VisualPermission.fromRaw(resultSet.getString("name_permission")),
            updatedAt == null ? null : updatedAt.toLocalDateTime());
      }
    }
  }

  private boolean resolveToggle(boolean globalEnabled, VisualPermission permission) {
    VisualPermission normalized = permission == null ? VisualPermission.INHERIT : permission;
    if (normalized == VisualPermission.DENY) {
      return false;
    }
    if (normalized == VisualPermission.ALLOW) {
      return true;
    }
    return globalEnabled;
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

  private long readConfigVersion(Connection connection, String key) throws SQLException {
    String sql = "SELECT version FROM runtime_config WHERE config_key = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return 0L;
        }
        return resultSet.getLong("version");
      }
    }
  }

  private boolean readBoolean(JsonObject object, String key, boolean fallback) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return fallback;
    }
    try {
      return object.get(key).getAsBoolean();
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String readString(JsonObject object, String key, String fallback) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return fallback;
    }
    try {
      String value = object.get(key).getAsString();
      if (value == null || value.isBlank()) {
        return fallback;
      }
      return value.trim();
    } catch (Exception exception) {
      return fallback;
    }
  }

  enum VisualPermission {
    INHERIT,
    ALLOW,
    DENY;

    static VisualPermission fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return INHERIT;
      }
      try {
        return VisualPermission.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        return INHERIT;
      }
    }
  }

  enum VisualPolicyMode {
    SOFT,
    HARD;

    static VisualPolicyMode fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return SOFT;
      }
      try {
        return VisualPolicyMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        return SOFT;
      }
    }
  }

  record VisualSettings(
      boolean globalCustomIconEnabled,
      boolean globalCustomNameEnabled,
      VisualPolicyMode iconPolicyMode,
      VisualPolicyMode namePolicyMode) {
    static VisualSettings defaults() {
      return new VisualSettings(true, true, VisualPolicyMode.SOFT, VisualPolicyMode.SOFT);
    }
  }

  record UserVisualPermission(
      long userId,
      VisualPermission iconPermission,
      VisualPermission namePermission,
      LocalDateTime updatedAt) {
  }

  record ResolvedPermission(
      long userId,
      boolean customIconAllowed,
      boolean customNameAllowed,
      VisualPermission iconPermission,
      VisualPermission namePermission,
      VisualSettings settings) {
  }
}
