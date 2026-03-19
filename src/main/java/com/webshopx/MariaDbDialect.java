package com.webshopx;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.plugin.java.JavaPlugin;

final class MariaDbDialect implements DatabaseDialect {
  @Override
  public DatabaseType type() {
    return DatabaseType.MARIADB;
  }

  @Override
  public void ensureDriverLoaded() {
    try {
      Class.forName("org.mariadb.jdbc.Driver");
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException("MariaDB JDBC driver is missing from plugin jar", exception);
    }
  }

  @Override
  public void configureHikari(
      JavaPlugin plugin,
      PluginSettings.DatabaseSettings settings,
      HikariConfig hikariConfig) {
    PluginSettings.MariaDbSettings database = settings.mariaDb();
    hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
    hikariConfig.setJdbcUrl(database.jdbcUrl());
    hikariConfig.setUsername(database.username());
    hikariConfig.setPassword(database.password());
    hikariConfig.addDataSourceProperty(
        "restrictedAuth",
        "mysql_native_password,caching_sha2_password,client_ed25519");
    hikariConfig.setMaximumPoolSize(Math.max(2, database.poolSize()));
    hikariConfig.setConnectionTimeout(10_000L);
    hikariConfig.setValidationTimeout(5_000L);
    hikariConfig.setPoolName("webshop-db-mariadb");
  }

  @Override
  public DatabaseSchemaManager schemaManager() {
    return new SchemaManager();
  }

  @Override
  public boolean supportsSelectForUpdate() {
    return true;
  }

  @Override
  public String currentTimestampExpression() {
    return "NOW()";
  }

  @Override
  public String castAsText(String expression) {
    return "CAST(" + expression + " AS CHAR)";
  }
}
