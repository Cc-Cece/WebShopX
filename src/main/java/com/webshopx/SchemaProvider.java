package com.webshopx;

interface SchemaProvider {
  void ensureSchema(DatabaseManager databaseManager, PluginSettings settings);

  static SchemaProvider forType(DbType dbType) {
    if (dbType == DbType.SQLITE) {
      return new SqliteSchemaProvider();
    }
    return new MysqlSchemaProvider();
  }
}
