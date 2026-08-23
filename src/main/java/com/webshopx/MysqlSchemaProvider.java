package com.webshopx;

import java.time.ZoneId;

final class MysqlSchemaProvider implements SchemaProvider {
  @Override
  public void ensureSchema(DatabaseManager databaseManager, ZoneId timeZone) {
    new SchemaManager().ensureSchema(databaseManager, timeZone);
  }
}
