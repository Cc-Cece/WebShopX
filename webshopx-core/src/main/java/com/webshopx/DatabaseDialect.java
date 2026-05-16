package com.webshopx;

interface DatabaseDialect {
  DbType dbType();

  String driverClassName();

  static DatabaseDialect forType(DbType dbType) {
    if (dbType == DbType.SQLITE) {
      return new SqliteDialect();
    }
    return new MysqlDialect(dbType == null ? DbType.MYSQL : dbType);
  }
}
