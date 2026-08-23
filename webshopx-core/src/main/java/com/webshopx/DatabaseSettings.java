package com.webshopx;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Platform-neutral database configuration shared by Paper and server Mod runtimes. */
public record DatabaseSettings(
    DbType type,
    String host,
    int port,
    String schema,
    String username,
    String password,
    boolean useSsl,
    boolean allowPublicKeyRetrieval,
    String serverRsaPublicKeyFile,
    int poolSize,
    String sqliteFile,
    String sqliteJournalMode,
    String sqliteSynchronous,
    int sqliteBusyTimeoutMs,
    int sqliteMaxRetries,
    List<Integer> sqliteRetryBackoffMs) {

  public String jdbcUrl() { return jdbcUrl(false); }

  public String jdbcUrl(boolean forceAllowPublicKeyRetrieval) {
    return type.isSqlite() ? sqliteJdbcUrl() : mysqlJdbcUrl(forceAllowPublicKeyRetrieval);
  }

  public String mysqlJdbcUrl(boolean forceAllowPublicKeyRetrieval) {
    String sslParam = useSsl ? "true" : "false";
    boolean retrieve = !useSsl && (forceAllowPublicKeyRetrieval || allowPublicKeyRetrieval);
    String rsa = trimmedServerRsaPublicKeyFile();
    StringBuilder url = new StringBuilder(String.format(Locale.ROOT,
        "jdbc:mariadb://%s:%d/%s?useSsl=%s&characterEncoding=utf8", host, port, schema, sslParam));
    if (retrieve) url.append("&allowPublicKeyRetrieval=true");
    if (!rsa.isEmpty()) url.append("&serverRsaPublicKeyFile=")
        .append(URLEncoder.encode(rsa, StandardCharsets.UTF_8));
    return url.toString();
  }

  public String sqliteJdbcUrl() { return "jdbc:sqlite:" + normalizedSqliteFile(); }
  public String normalizedSqliteFile() {
    return sqliteFile == null || sqliteFile.isBlank() ? "plugins/WebShopX/webshopx.db" : sqliteFile.trim();
  }
  public String normalizedSqliteJournalMode() {
    String value = sqliteJournalMode == null ? "" : sqliteJournalMode.trim().toUpperCase(Locale.ROOT);
    return List.of("DELETE", "TRUNCATE", "PERSIST", "MEMORY", "WAL", "OFF").contains(value) ? value : "WAL";
  }
  public String normalizedSqliteSynchronous() {
    String value = sqliteSynchronous == null ? "" : sqliteSynchronous.trim().toUpperCase(Locale.ROOT);
    return List.of("OFF", "NORMAL", "FULL", "EXTRA").contains(value) ? value : "NORMAL";
  }
  public int normalizedSqliteBusyTimeoutMs() { return Math.max(0, sqliteBusyTimeoutMs); }
  public int normalizedSqliteMaxRetries() { return Math.min(10, Math.max(0, sqliteMaxRetries)); }
  public List<Integer> normalizedSqliteRetryBackoffMs() {
    if (sqliteRetryBackoffMs == null || sqliteRetryBackoffMs.isEmpty()) return List.of(10, 50, 100);
    List<Integer> values = new ArrayList<>();
    for (Integer value : sqliteRetryBackoffMs) if (value != null && value >= 0) values.add(value);
    return values.isEmpty() ? List.of(10, 50, 100) : Collections.unmodifiableList(values);
  }
  public boolean canAutoRetryWithPublicKeyRetrieval() {
    return type.isMysqlFamily() && !useSsl && !allowPublicKeyRetrieval && trimmedServerRsaPublicKeyFile().isEmpty();
  }
  public String trimmedServerRsaPublicKeyFile() {
    return serverRsaPublicKeyFile == null ? "" : serverRsaPublicKeyFile.trim();
  }
  public boolean usesDefaultPlaceholders() {
    return type.isMysqlFamily() && "127.0.0.1".equals(host) && port == 3306
        && "webshop".equals(schema) && "webshop".equals(username) && "change_me".equals(password);
  }
}
