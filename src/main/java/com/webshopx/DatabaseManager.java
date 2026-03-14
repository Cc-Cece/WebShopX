package com.webshopx;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

class DatabaseManager {
  private final JavaPlugin plugin;
  private final PluginSettings.DatabaseSettings settings;
  private HikariDataSource dataSource;

  DatabaseManager(JavaPlugin plugin, PluginSettings.DatabaseSettings settings) {
    this.plugin = plugin;
    this.settings = settings;
  }

  void start() {
    ensureDriverLoaded();
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
    hikariConfig.setJdbcUrl(settings.jdbcUrl());
    hikariConfig.setUsername(settings.username());
    hikariConfig.setPassword(settings.password());
    hikariConfig.addDataSourceProperty(
        "restrictedAuth",
        "mysql_native_password,caching_sha2_password,client_ed25519");
    hikariConfig.setMaximumPoolSize(Math.max(2, settings.poolSize()));
    hikariConfig.setConnectionTimeout(10_000L);
    hikariConfig.setValidationTimeout(5_000L);
    hikariConfig.setPoolName("webshop-db");
    try {
      this.dataSource = new HikariDataSource(hikariConfig);
    } catch (RuntimeException exception) {
      if (containsGssApi(exception)) {
        throw new IllegalStateException(
            "Database authentication is using GSSAPI/SSPI. "
                + "Please create a password-based SQL user and update config.yml.",
            exception);
      }
      throw exception;
    }
  }

  private void ensureDriverLoaded() {
    try {
      Class.forName("org.mariadb.jdbc.Driver");
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException("MariaDB JDBC driver is missing from plugin jar", exception);
    }
  }

  private boolean containsGssApi(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      String className = current.getClass().getName().toLowerCase();
      String message = current.getMessage();
      String normalizedMessage = message == null ? "" : message.toLowerCase();
      if (className.contains("gss")
          || className.contains("sspi")
          || normalizedMessage.contains("gssapi")
          || normalizedMessage.contains("sspi")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  void close() {
    if (dataSource != null) {
      dataSource.close();
      dataSource = null;
    }
  }

  Connection getConnection() throws SQLException {
    if (dataSource == null) {
      throw new IllegalStateException("Data source is not initialized");
    }
    return dataSource.getConnection();
  }

  <T> T withConnection(SqlFunction<T> function) {
    try (Connection connection = getConnection()) {
      return function.apply(connection);
    } catch (SQLException exception) {
      throw new DataAccessException("Database operation failed", exception);
    }
  }

  <T> T inTransaction(SqlFunction<T> function) {
    try (Connection connection = getConnection()) {
      boolean previousAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        T result = function.apply(connection);
        connection.commit();
        return result;
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(previousAutoCommit);
      }
    } catch (SQLException exception) {
      throw new DataAccessException("Transaction failed", exception);
    }
  }

  void logFailure(String message, Exception exception) {
    Logger logger = plugin.getLogger();
    logger.log(Level.SEVERE, message, exception);
  }

  @FunctionalInterface
  interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
  }

  static class DataAccessException extends RuntimeException {
    DataAccessException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
