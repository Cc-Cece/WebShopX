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
  private final DatabaseDialect dialect;
  private HikariDataSource dataSource;

  DatabaseManager(JavaPlugin plugin, PluginSettings.DatabaseSettings settings) {
    this.plugin = plugin;
    this.settings = settings;
    this.dialect = createDialect(settings.type());
  }

  void start() {
    dialect.ensureDriverLoaded();
    HikariConfig hikariConfig = new HikariConfig();
    dialect.configureHikari(plugin, settings, hikariConfig);
    try {
      this.dataSource = new HikariDataSource(hikariConfig);
    } catch (RuntimeException exception) {
      if (settings.type() == DatabaseType.MARIADB && containsGssApi(exception)) {
        throw new IllegalStateException(
            "Database authentication is using GSSAPI/SSPI. "
                + "Please create a password-based SQL user and update config.yml.",
            exception);
      }
      throw exception;
    }
  }

  void ensureSchema() {
    dialect.schemaManager().ensureSchema(this);
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

  DatabaseType type() {
    return settings.type();
  }

  boolean isSqlite() {
    return settings.type() == DatabaseType.SQLITE;
  }

  String lockClause(boolean forUpdate) {
    return dialect.lockClause(forUpdate);
  }

  String currentTimestampExpression() {
    return dialect.currentTimestampExpression();
  }

  String coalesce(String expression, String fallbackExpression) {
    return dialect.coalesce(expression, fallbackExpression);
  }

  String castAsText(String expression) {
    return dialect.castAsText(expression);
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

  private DatabaseDialect createDialect(DatabaseType databaseType) {
    return switch (databaseType) {
      case SQLITE -> new SqliteDialect();
      case MARIADB -> new MariaDbDialect();
    };
  }
}
