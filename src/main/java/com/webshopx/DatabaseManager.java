package com.webshopx;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class DatabaseManager {
  private static final Pattern SQLITE_FOR_UPDATE_PATTERN =
      Pattern.compile("(?i)\\s+FOR\\s+UPDATE\\b");
  private static final Pattern SQLITE_NOW_PATTERN =
      Pattern.compile("(?i)\\bNOW\\s*\\(\\s*\\)");
  private static final Pattern SQLITE_DATE_SUB_SECONDS_PATTERN =
      Pattern.compile(
          "(?i)DATE_SUB\\s*\\(\\s*(?:CURRENT_TIMESTAMP|NOW\\s*\\(\\s*\\))\\s*,\\s*INTERVAL\\s*\\?\\s*SECOND\\s*\\)");
  private static final Pattern SQLITE_IFNULL_PATTERN =
      Pattern.compile("(?i)\\bIFNULL\\s*\\(");
  private static final Pattern SQLITE_CAST_AS_CHAR_PATTERN =
      Pattern.compile("(?i)CAST\\s*\\((.+?)\\s+AS\\s+CHAR\\s*\\)");

  private final Logger logger;
  private final DatabaseSettings settings;
  private final DatabaseDialect dialect;
  private final SqlProvider sqlProvider;
  private HikariDataSource dataSource;

  public DatabaseManager(Logger logger, DatabaseSettings settings) {
    this.logger = logger == null ? Logger.getLogger(DatabaseManager.class.getName()) : logger;
    this.settings = settings;
    this.dialect = DatabaseDialect.forType(settings.type());
    this.sqlProvider = SqlProvider.forType(settings.type());
  }

  public void start() {
    ensureDriverLoaded();
    if (dialect.dbType().isSqlite()) {
      ensureSqliteDirectoryExists();
      this.dataSource = createDataSource(settings.sqliteJdbcUrl());
      return;
    }
    try {
      this.dataSource = createDataSource(settings.mysqlJdbcUrl(false));
    } catch (RuntimeException exception) {
      if (containsGssApi(exception)) {
        throw new IllegalStateException(
            "Database authentication is using GSSAPI/SSPI. "
                + "Please create a password-based SQL user and update config.yml.",
            exception);
      }
      if (containsMissingRsaPublicKey(exception) && settings.canAutoRetryWithPublicKeyRetrieval()) {
        logger.warning("console.database_retry_with_public_key_retrieval");
        this.dataSource = createDataSource(settings.mysqlJdbcUrl(true));
        return;
      }
      if (containsMissingRsaPublicKey(exception)) {
        throw new IllegalStateException(
            "Database authentication requires RSA public key exchange. "
                + "Enable database.use-ssl, keep database.allow-public-key-retrieval=true, "
                + "configure database.server-rsa-public-key-file, or switch the SQL user to "
                + "mysql_native_password.",
            exception);
      }
      throw exception;
    }
  }

  private HikariDataSource createDataSource(String jdbcUrl) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setDriverClassName(dialect.driverClassName());
    hikariConfig.setJdbcUrl(jdbcUrl);
    int maxPoolSize =
        dialect.dbType().isSqlite()
            ? Math.max(1, Math.min(2, settings.poolSize()))
            : Math.max(2, settings.poolSize());
    hikariConfig.setMaximumPoolSize(maxPoolSize);
    hikariConfig.setConnectionTimeout(10_000L);
    hikariConfig.setValidationTimeout(5_000L);
    hikariConfig.setPoolName("webshop-db");
    if (dialect.dbType().isMysqlFamily()) {
      hikariConfig.setUsername(settings.username());
      hikariConfig.setPassword(settings.password());
      hikariConfig.addDataSourceProperty(
          "restrictedAuth",
          "mysql_native_password,caching_sha2_password,client_ed25519");
    } else {
      hikariConfig.setConnectionInitSql("PRAGMA foreign_keys=ON");
      hikariConfig.addDataSourceProperty("transaction_mode", "IMMEDIATE");
      hikariConfig.addDataSourceProperty(
          "busy_timeout",
          String.valueOf(settings.normalizedSqliteBusyTimeoutMs()));
    }
    return new HikariDataSource(hikariConfig);
  }

  private void ensureSqliteDirectoryExists() {
    String sqliteFile = settings.normalizedSqliteFile();
    if (sqliteFile.equals(":memory:")) {
      return;
    }
    Path dbPath = Path.of(sqliteFile);
    Path parent = dbPath.getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Failed to create SQLite directory: " + parent.toAbsolutePath(),
          exception);
    }
  }

  private void ensureDriverLoaded() {
    try {
      Class.forName(dialect.driverClassName());
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException(
          "Database JDBC driver is missing from plugin jar: " + dialect.driverClassName(),
          exception);
    }
  }

  private boolean containsGssApi(Throwable throwable) {
    return containsText(throwable, "gss", "sspi", "gssapi");
  }

  private boolean containsMissingRsaPublicKey(Throwable throwable) {
    return containsText(
        throwable,
        "rsa public key is not available client side",
        "serverrsapublickeyfile",
        "allowpublickeyretrieval");
  }

  private boolean containsText(Throwable throwable, String... needles) {
    Throwable current = throwable;
    while (current != null) {
      String className = current.getClass().getName().toLowerCase();
      String message = current.getMessage();
      String normalizedMessage = message == null ? "" : message.toLowerCase();
      for (String needle : needles) {
        String normalizedNeedle = needle.toLowerCase();
        if (className.contains(normalizedNeedle) || normalizedMessage.contains(normalizedNeedle)) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  public void close() {
    if (dataSource != null) {
      dataSource.close();
      dataSource = null;
    }
  }

  public Connection getConnection() throws SQLException {
    if (dataSource == null) {
      throw new IllegalStateException("Data source is not initialized");
    }
    Connection connection = dataSource.getConnection();
    if (dialect.dbType().isSqlite()) {
      applySqlitePragmas(connection);
    }
    return connection;
  }

  public <T> T withConnection(SqlFunction<T> function) {
    return executeWithRetry("Database operation failed", false, function);
  }

  public <T> T inTransaction(SqlFunction<T> function) {
    return executeWithRetry("Transaction failed", true, function);
  }

  void logFailure(String message, Exception exception) {
    logger.log(Level.SEVERE, message, exception);
  }

  private Connection adaptConnectionForDialect(Connection connection) {
    if (!dialect.dbType().isSqlite()) {
      return connection;
    }
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class[] {Connection.class},
            (proxy, method, args) -> invokeWithSqliteAdaptation(connection, method, args));
  }

  private Object invokeWithSqliteAdaptation(Connection connection, Method method, Object[] args)
      throws Throwable {
    Object[] adaptedArgs = adaptSqlArgsIfNeeded(method, args);
    try {
      return method.invoke(connection, adaptedArgs);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }

  private Object[] adaptSqlArgsIfNeeded(Method method, Object[] args) {
    if (args == null || args.length == 0 || !(args[0] instanceof String sql)) {
      return args;
    }
    String methodName = method.getName();
    if (!methodName.startsWith("prepare") && !"nativeSQL".equals(methodName)) {
      return args;
    }
    String sanitizedSql = sanitizeSqlForSqlite(sql);
    if (sanitizedSql.equals(sql)) {
      return args;
    }
    Object[] adapted = args.clone();
    adapted[0] = sanitizedSql;
    return adapted;
  }

  private String sanitizeSqlForSqlite(String sql) {
    if (sql == null || sql.isBlank()) {
      return sql;
    }
    String sanitized = SQLITE_FOR_UPDATE_PATTERN.matcher(sql).replaceAll("");
    sanitized = SQLITE_NOW_PATTERN.matcher(sanitized).replaceAll("CURRENT_TIMESTAMP");
    sanitized =
        SQLITE_DATE_SUB_SECONDS_PATTERN
            .matcher(sanitized)
            .replaceAll("datetime(CURRENT_TIMESTAMP, '-' || ? || ' seconds')");
    sanitized = SQLITE_IFNULL_PATTERN.matcher(sanitized).replaceAll("COALESCE(");
    sanitized = SQLITE_CAST_AS_CHAR_PATTERN.matcher(sanitized).replaceAll("CAST($1 AS TEXT)");
    return sanitized;
  }

  private void applySqlitePragmas(Connection connection) throws SQLException {
    executePragma(connection, "PRAGMA foreign_keys=ON");
    executePragma(connection, "PRAGMA journal_mode=" + settings.normalizedSqliteJournalMode());
    executePragma(connection, "PRAGMA synchronous=" + settings.normalizedSqliteSynchronous());
    executePragma(connection, "PRAGMA busy_timeout=" + settings.normalizedSqliteBusyTimeoutMs());
  }

  private void executePragma(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  private <T> T executeWithRetry(String failureMessage, boolean transactional, SqlFunction<T> function) {
    int maxRetries = dialect.dbType().isSqlite() ? settings.normalizedSqliteMaxRetries()
        : transactional && dialect.dbType().isMysqlFamily() ? 3 : 0;
    int attempt = 0;
    while (true) {
      try (Connection connection = getConnection()) {
        Connection adaptedConnection = adaptConnectionForDialect(connection);
        if (!transactional) {
          return function.apply(adaptedConnection);
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
          T result = function.apply(adaptedConnection);
          connection.commit();
          return result;
        } catch (Exception exception) {
          connection.rollback();
          throw exception;
        } finally {
          connection.setAutoCommit(previousAutoCommit);
        }
      } catch (SQLException exception) {
        boolean retryable = isRetryableTransactionException(exception);
        boolean canRetry = attempt < maxRetries && retryable;
        if (!canRetry) {
          if (retryable) {
            logger.log(Level.SEVERE, "console.database_retry_exhausted attempt=" + attempt, exception);
          }
          throw new DataAccessException(failureMessage, exception);
        }
        int nextAttempt = attempt + 1;
        Level level = nextAttempt >= maxRetries ? Level.WARNING : Level.FINE;
        logger.log(level, "console.database_retrying attempt=" + nextAttempt + " max=" + maxRetries);
        sleepBeforeRetry(attempt);
        attempt = nextAttempt;
      }
    }
  }

  private boolean isRetryableSqliteException(SQLException exception) {
    return containsText(exception, "database is locked", "sqlite_busy", "sqlite_locked", "busy");
  }

  private boolean isRetryableTransactionException(SQLException exception) {
    if (dialect.dbType().isSqlite()) {
      return isRetryableSqliteException(exception);
    }
    if (!dialect.dbType().isMysqlFamily()) {
      return false;
    }
    SQLException current = exception;
    while (current != null) {
      if ("40001".equals(current.getSQLState())
          || current.getErrorCode() == 1213
          || current.getErrorCode() == 1205) {
        return true;
      }
      current = current.getNextException();
    }
    return containsText(exception, "deadlock found", "lock wait timeout");
  }

  private void sleepBeforeRetry(int attempt) {
    List<Integer> backoff = settings.normalizedSqliteRetryBackoffMs();
    if (backoff.isEmpty()) {
      return;
    }
    int index = Math.min(attempt, backoff.size() - 1);
    int delayMs = Math.max(0, backoff.get(index));
    if (delayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  public DbType dbType() {
    return dialect.dbType();
  }

  public DatabaseDialect dialect() {
    return dialect;
  }

  public SqlProvider sqlProvider() {
    return sqlProvider;
  }

  @FunctionalInterface
  public interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
  }

  static class DataAccessException extends RuntimeException {
    DataAccessException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
