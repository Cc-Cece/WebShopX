package com.webshopx;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.plugin.java.JavaPlugin;

interface DatabaseDialect {
  DatabaseType type();

  void ensureDriverLoaded();

  void configureHikari(
      JavaPlugin plugin,
      PluginSettings.DatabaseSettings settings,
      HikariConfig hikariConfig);

  DatabaseSchemaManager schemaManager();

  boolean supportsSelectForUpdate();

  String currentTimestampExpression();

  String castAsText(String expression);

  default String lockClause(boolean forUpdate) {
    return forUpdate && supportsSelectForUpdate() ? " FOR UPDATE" : "";
  }

  default String coalesce(String expression, String fallbackExpression) {
    return "COALESCE(" + expression + ", " + fallbackExpression + ")";
  }
}
