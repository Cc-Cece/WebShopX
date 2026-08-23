package com.webshopx.loader;

import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.SchemaProvider;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Logger;

/** Loader-owned standalone database with a forward-only compatibility marker. */
final class SharedDatabaseRuntime implements AutoCloseable {
  private final DatabaseManager database;

  private SharedDatabaseRuntime(DatabaseManager database) {
    this.database = database;
  }

  static SharedDatabaseRuntime start(Path dataDirectory) {
    DatabaseSettings settings = new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 2,
        dataDirectory.resolve("webshopx.db").toString(), "WAL", "NORMAL",
        5_000, 5, List.of(10, 50, 100, 250, 500));
    DatabaseManager database = new DatabaseManager(
        Logger.getLogger("com.webshopx.loader.database"), settings);
    database.start();
    SharedDatabaseRuntime runtime = new SharedDatabaseRuntime(database);
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    return runtime;
  }

  @Override public void close() { database.close(); }
}
