package com.webshopx;

import java.util.Locale;

enum DbType {
  MYSQL,
  MARIADB,
  SQLITE;

  static DbType fromRaw(String raw) {
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

  boolean isMysqlFamily() {
    return this == MYSQL || this == MARIADB;
  }

  boolean isSqlite() {
    return this == SQLITE;
  }
}
