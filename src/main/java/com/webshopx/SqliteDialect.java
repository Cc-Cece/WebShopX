package com.webshopx;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.java.JavaPlugin;

final class SqliteDialect implements DatabaseDialect {
  @Override
  public DatabaseType type() {
    return DatabaseType.SQLITE;
  }

  @Override
  public void ensureDriverLoaded() {
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException("SQLite JDBC driver is missing from plugin jar", exception);
    }
  }

  @Override
  public void configureHikari(
      JavaPlugin plugin,
      PluginSettings.DatabaseSettings settings,
      HikariConfig hikariConfig) {
    PluginSettings.SqliteSettings database = settings.sqlite();
    Path databaseFile = database.resolveFile(plugin);
    Path parent = databaseFile.getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException exception) {
        throw new IllegalStateException("Failed to create SQLite data directory", exception);
      }
    }

    hikariConfig.setDriverClassName("org.sqlite.JDBC");
    hikariConfig.setJdbcUrl("jdbc:sqlite:" + databaseFile);
    hikariConfig.addDataSourceProperty("busy_timeout", Integer.toString(database.busyTimeoutMs()));
    hikariConfig.addDataSourceProperty("foreign_keys", "true");
    hikariConfig.addDataSourceProperty("journal_mode", "WAL");
    hikariConfig.setMaximumPoolSize(1);
    hikariConfig.setMinimumIdle(1);
    hikariConfig.setConnectionTimeout(10_000L);
    hikariConfig.setValidationTimeout(5_000L);
    hikariConfig.setConnectionTestQuery("SELECT 1");
    hikariConfig.setPoolName("webshop-db-sqlite");
  }

  @Override
  public DatabaseSchemaManager schemaManager() {
    return new SqliteSchemaManager();
  }

  @Override
  public boolean supportsSelectForUpdate() {
    return false;
  }

  @Override
  public String currentTimestampExpression() {
    return "CURRENT_TIMESTAMP";
  }

  @Override
  public String castAsText(String expression) {
    return "CAST(" + expression + " AS TEXT)";
  }
}
