package com.webshopx;

final class SqliteDialect implements DatabaseDialect {
  @Override
  public DbType dbType() {
    return DbType.SQLITE;
  }

  @Override
  public String driverClassName() {
    return "org.sqlite.JDBC";
  }
}
