package com.webshopx;

final class MysqlSchemaProvider implements SchemaProvider {
  @Override
  public void ensureSchema(DatabaseManager databaseManager, PluginSettings settings) {
    new SchemaManager().ensureSchema(databaseManager, settings);
  }
}
