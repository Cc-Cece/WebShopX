package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlProviderCoverageTest {
  @Test
  void everySqlProviderMethodShouldBeCallableForMysqlAndSqlite() {
    List<String> mysqlSql = collectAllSql(SqlProvider.forType(DbType.MYSQL));
    List<String> mariadbSql = collectAllSql(SqlProvider.forType(DbType.MARIADB));
    List<String> sqliteSql = collectAllSql(SqlProvider.forType(DbType.SQLITE));

    assertEquals(DbType.MYSQL, SqlProvider.forType(DbType.MYSQL).dbType());
    assertEquals(DbType.MARIADB, SqlProvider.forType(DbType.MARIADB).dbType());
    assertEquals(DbType.SQLITE, SqlProvider.forType(DbType.SQLITE).dbType());
    assertFalse(mysqlSql.isEmpty());
    assertFalse(mariadbSql.isEmpty());
    assertFalse(sqliteSql.isEmpty());
  }

  private static List<String> collectAllSql(SqlProvider provider) {
    List<String> values = new ArrayList<>();
    values.add(provider.forUpdateClause());
    values.add(provider.castAsText("u.id"));
    values.add(provider.coalesce("u.username", "''"));
    values.add(provider.currentTimestampMinusSecondsExpr());
    values.add(provider.insertWalletIfMissingSql());
    values.add(provider.insertWalletLedgerIfAbsentSql());
    values.add(provider.upsertAdminAccessSql());
    values.add(provider.upsertMaterialVisualSql());
    values.add(provider.upsertMarketTagSql());
    values.add(provider.upsertProductUserUsageSql());
    values.add(provider.upsertProductSeedSql());
    values.add(provider.upsertPlayerPresenceOnlineSql());
    values.add(provider.insertRedeemCodeIfAbsentSql());
    values.add(provider.upsertRedeemUsageSql());
    values.add(provider.insertRuntimeConfigIfMissingSql());
    values.add(provider.upsertRuntimeConfigSql());
    values.add(provider.upsertWebshopMetaSql());
    values.add(provider.upsertUserMarketSettingsSql());
    values.add(provider.upsertVisualSettingsSql());
    values.add(provider.upsertUserVisualPermissionSql());
    for (String value : values) {
      assertNotNull(value);
    }
    return values;
  }
}
