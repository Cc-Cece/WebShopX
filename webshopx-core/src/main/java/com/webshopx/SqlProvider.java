package com.webshopx;

interface SqlProvider {
  DbType dbType();

  String forUpdateClause();

  String castAsText(String expression);

  String coalesce(String expression, String fallbackExpression);

  String currentTimestampMinusSecondsExpr();

  String insertWalletIfMissingSql();

  String insertWalletLedgerIfAbsentSql();

  String upsertAdminAccessSql();

  String upsertMaterialVisualSql();

  String upsertMarketTagSql();

  String upsertProductUserUsageSql();

  String upsertProductSeedSql();

  String upsertPlayerPresenceOnlineSql();

  String insertRedeemCodeIfAbsentSql();

  String upsertRedeemUsageSql();

  String insertRuntimeConfigIfMissingSql();

  String upsertRuntimeConfigSql();

  String upsertWebshopMetaSql();

  String upsertUserMarketSettingsSql();

  String upsertVisualSettingsSql();

  String upsertUserVisualPermissionSql();

  static SqlProvider forType(DbType dbType) {
    if (dbType == DbType.SQLITE) {
      return new SqliteSqlProvider();
    }
    return new MysqlSqlProvider(dbType == null ? DbType.MYSQL : dbType);
  }
}
