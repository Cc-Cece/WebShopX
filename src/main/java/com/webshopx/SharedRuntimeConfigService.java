package com.webshopx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Loader-neutral access to the runtime configuration documents shared with Paper nodes. */
public final class SharedRuntimeConfigService {
  private static final Set<String> KEYS =
      Set.of(
          "exchange",
          "market_economy",
          "leaderboard",
          "currency_display",
          "webshop_runtime",
          "home_link",
          "market_runtime",
          "market_tags",
          "market_limitation",
          "auction_display",
          "maintenance",
          "logging",
          "broadcast",
          "notification",
          "payment_recharge",
          "offline_inventory",
          "locale_center",
          "visual_settings");

  private final DatabaseManager database;

  public SharedRuntimeConfigService(DatabaseManager database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  public ConfigDocument read(String key) {
    requireKey(key);
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT config_value,version FROM runtime_config WHERE config_key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) return new ConfigDocument(new JsonObject(), 0L);
              return new ConfigDocument(parseObject(result.getString(1)), result.getLong(2));
            }
          }
        });
  }

  public ConfigDocument update(String key, JsonObject config) {
    requireKey(key);
    JsonObject copy = parseObject(Objects.requireNonNull(config, "config").toString());
    return database.inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE runtime_config SET config_value=?,version=version+1,"
                      + "updated_at=CURRENT_TIMESTAMP WHERE config_key=?")) {
            statement.setString(1, copy.toString());
            statement.setString(2, key);
            if (statement.executeUpdate() == 0) {
              try (PreparedStatement insert =
                  connection.prepareStatement(
                      "INSERT INTO runtime_config (config_key,config_value,version) VALUES (?,?,1)")) {
                insert.setString(1, key);
                insert.setString(2, copy.toString());
                insert.executeUpdate();
              }
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT config_value,version FROM runtime_config WHERE config_key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new SQLException("runtime config update disappeared");
              return new ConfigDocument(parseObject(result.getString(1)), result.getLong(2));
            }
          }
        });
  }

  public ConfigDocument mutate(String key, UnaryOperator<JsonObject> mutation) {
    requireKey(key);
    Objects.requireNonNull(mutation, "mutation");
    return database.inTransaction(
        connection -> {
          try (PreparedStatement insert =
              connection.prepareStatement(database.sqlProvider().insertRuntimeConfigIfMissingSql())) {
            insert.setString(1, key);
            insert.setString(2, "{}");
            insert.executeUpdate();
          }
          JsonObject current;
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT config_value FROM runtime_config WHERE config_key=?"
                      + database.sqlProvider().forUpdateClause())) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new SQLException("runtime config row is missing");
              current = parseObject(result.getString(1));
            }
          }
          JsonObject next = parseObject(
              Objects.requireNonNull(mutation.apply(current.deepCopy()), "mutation result").toString());
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE runtime_config SET config_value=?,version=version+1,"
                      + "updated_at=CURRENT_TIMESTAMP WHERE config_key=?")) {
            statement.setString(1, next.toString());
            statement.setString(2, key);
            statement.executeUpdate();
          }
          return new ConfigDocument(next, readVersion(connection, key));
        });
  }

  private static long readVersion(java.sql.Connection connection, String key) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT version FROM runtime_config WHERE config_key=?")) {
      statement.setString(1, key);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("runtime config version is missing");
        return result.getLong(1);
      }
    }
  }

  private static JsonObject parseObject(String raw) {
    try {
      var parsed = JsonParser.parseString(raw == null ? "{}" : raw);
      return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    } catch (RuntimeException ignored) {
      return new JsonObject();
    }
  }

  private static void requireKey(String key) {
    if (!KEYS.contains(key)) {
      throw new ServiceException("invalid_config", "Runtime configuration key is not supported");
    }
  }

  public record ConfigDocument(JsonObject config, long version) {}
}
