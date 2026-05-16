package com.webshopx;

final class MysqlDialect implements DatabaseDialect {
  private final DbType dbType;

  MysqlDialect(DbType dbType) {
    this.dbType = dbType == null ? DbType.MYSQL : dbType;
  }

  @Override
  public DbType dbType() {
    return dbType;
  }

  @Override
  public String driverClassName() {
    return "org.mariadb.jdbc.Driver";
  }
}
