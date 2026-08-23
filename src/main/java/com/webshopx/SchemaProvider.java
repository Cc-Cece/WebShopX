package com.webshopx;

import java.time.ZoneId;

public interface SchemaProvider {
  void ensureSchema(DatabaseManager databaseManager, ZoneId timeZone);

  static SchemaProvider forType(DbType dbType) {
    if (dbType == DbType.SQLITE) {
      return new SqliteSchemaProvider();
    }
    return new MysqlSchemaProvider();
  }
}
