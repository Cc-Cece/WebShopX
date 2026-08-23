package com.webshopx.loader;

import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.logging.Logger;

/** Loader-owned standalone database with a forward-only compatibility marker. */
final class SharedDatabaseRuntime implements AutoCloseable {
  static final int SCHEMA_VERSION = 1;
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
    runtime.migrate();
    return runtime;
  }

  private void migrate() {
    database.inTransaction(connection -> {
      try (PreparedStatement create = connection.prepareStatement(
          "CREATE TABLE IF NOT EXISTS webshopx_schema_state ("
              + "component VARCHAR(64) PRIMARY KEY, schema_version INTEGER NOT NULL)")) {
        create.execute();
      }
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT OR IGNORE INTO webshopx_schema_state(component,schema_version) VALUES (?,?)")) {
        insert.setString(1, "mod-runtime");
        insert.setInt(2, SCHEMA_VERSION);
        insert.executeUpdate();
      }
      try (PreparedStatement verify = connection.prepareStatement(
          "SELECT schema_version FROM webshopx_schema_state WHERE component=?")) {
        verify.setString(1, "mod-runtime");
        try (var result = verify.executeQuery()) {
          if (!result.next() || result.getInt(1) != SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported WebShopX Mod schema version");
          }
        }
      }
      return null;
    });
  }

  @Override public void close() { database.close(); }
}
