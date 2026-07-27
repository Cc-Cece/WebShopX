package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class ProductSnapshotMetadataRepairTest {
  @Test
  void detectsMissingMalformedAndValidContainerMetadata() {
    assertTrue(ProductService.needsContainerMetadataRepair(null));
    assertTrue(ProductService.needsContainerMetadataRepair(""));
    assertTrue(ProductService.needsContainerMetadataRepair("{"));
    assertTrue(ProductService.needsContainerMetadataRepair(
        "{\"material\":\"RED_SHULKER_BOX\",\"amount\":1}"));
    assertTrue(ProductService.needsContainerMetadataRepair(
        "{\"material\":\"RED_SHULKER_BOX\",\"containerItems\":null}"));
    assertTrue(ProductService.needsContainerMetadataRepair(
        "{\"material\":\"BUNDLE\",\"containerItems\":[]}"));
    assertFalse(ProductService.needsContainerMetadataRepair(
        "{\"material\":\"BUNDLE\",\"containerItems\":[],"
            + "\"containerCapacity\":64,\"containerOccupancy\":0}"));
    assertFalse(ProductService.needsContainerMetadataRepair(
        "{\"material\":\"RED_SHULKER_BOX\",\"containerItems\":[]}"));
  }

  @Test
  void refreshesProjectionWithoutReplacingSnapshotIdentityOrBlob() throws Exception {
    byte[] blob = new byte[] {1, 2, 3};
    String hash = ItemSnapshotCodec.sha256Hex(blob);
    ItemSnapshotCodec.Snapshot rebuilt = new ItemSnapshotCodec.Snapshot(
        blob,
        "{\"material\":\"RED_SHULKER_BOX\",\"amount\":1,\"containerItems\":[]}",
        hash);

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE official_item_snapshots ("
          + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
          + "item_hash TEXT NOT NULL UNIQUE, "
          + "item_blob BLOB NOT NULL, "
          + "item_meta_json TEXT NOT NULL, "
          + "item_material TEXT NOT NULL)");
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO official_item_snapshots "
              + "(item_hash, item_blob, item_meta_json, item_material) "
              + "VALUES (?, ?, ?, ?)")) {
        insert.setString(1, hash);
        insert.setBytes(2, blob);
        insert.setString(3, "{\"material\":\"RED_SHULKER_BOX\",\"amount\":1}");
        insert.setString(4, "RED_SHULKER_BOX");
        assertEquals(1, insert.executeUpdate());
      }

      ProductService.updateSnapshotProjection(
          connection, 1L, rebuilt, "RED_SHULKER_BOX");

      try (ResultSet result = statement.executeQuery(
          "SELECT id, item_hash, item_blob, item_meta_json, item_material "
              + "FROM official_item_snapshots WHERE id = 1")) {
        assertTrue(result.next());
        assertEquals(1L, result.getLong("id"));
        assertEquals(hash, result.getString("item_hash"));
        assertEquals(3, result.getBytes("item_blob").length);
        assertEquals(rebuilt.itemMetaJson(), result.getString("item_meta_json"));
        assertEquals("RED_SHULKER_BOX", result.getString("item_material"));
      }
    }
  }
}
