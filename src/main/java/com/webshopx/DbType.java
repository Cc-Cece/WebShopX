package com.webshopx;

import java.util.Locale;

public enum DbType {
  MYSQL,
  MARIADB,
  SQLITE;

  public static DbType fromRaw(String raw) {
    if (raw == null || raw.isBlank()) {
      return MYSQL;
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "mysql", "my_sql" -> MYSQL;
      case "mariadb", "maria", "maria_db" -> MARIADB;
      case "sqlite", "sqlite3" -> SQLITE;
      default -> MYSQL;
    };
  }

  public boolean isMysqlFamily() {
    return this == MYSQL || this == MARIADB;
  }

  public boolean isSqlite() {
    return this == SQLITE;
  }
}
