package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class SqliteOfficialSnapshotMigrationTest {
  @Test
  void upgradesLegacySnapshotTableWithoutId() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
         Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys = ON");
      statement.execute("""
          CREATE TABLE official_item_snapshots (
            item_hash TEXT NOT NULL UNIQUE,
            item_blob BLOB NOT NULL,
            item_meta_json TEXT NOT NULL,
            item_material TEXT NOT NULL
          )
          """);
      statement.execute("""
          CREATE TABLE products (
            id INTEGER PRIMARY KEY,
            snapshot_id INTEGER NULL
          )
          """);
      statement.execute("""
          CREATE TABLE product_item_snapshots (
            product_id INTEGER NOT NULL,
            snapshot_id INTEGER NOT NULL,
            FOREIGN KEY (snapshot_id) REFERENCES official_item_snapshots(id)
          )
          """);
      statement.execute("""
          INSERT INTO official_item_snapshots (
            item_hash, item_blob, item_meta_json, item_material
          ) VALUES ('old-hash', X'01', '{}', 'STONE')
          """);

      Method migration = SqliteSchemaProvider.class.getDeclaredMethod(
          "migrateOfficialItemSnapshots", Connection.class);
      migration.setAccessible(true);
      migration.invoke(new SqliteSchemaProvider(), connection);

      try (ResultSet existing = statement.executeQuery(
          "SELECT id FROM official_item_snapshots WHERE item_hash = 'old-hash'")) {
        assertEquals(1L, existing.getLong("id"));
      }
      statement.execute("""
          INSERT INTO official_item_snapshots (
            item_hash, item_blob, item_meta_json, item_material
          ) VALUES ('new-hash', X'02', '{}', 'DIRT')
          """);
      try (ResultSet inserted = statement.executeQuery(
          "SELECT id FROM official_item_snapshots WHERE item_hash = 'new-hash'")) {
        assertNotNull(inserted.getObject("id"));
        assertEquals(2L, inserted.getLong("id"));
      }
    }
  }
}
