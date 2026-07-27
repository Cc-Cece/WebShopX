package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventorySnapshotJsonCodecTest {
  @Test
  void encodesDisplayOnlySnapshotIncludingContainerContents() {
    InventoryService.ItemView child = new InventoryService.ItemView(
        "DIAMOND",
        "Diamond",
        3,
        64,
        "child-fingerprint",
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        List.of(new InventoryService.ItemView(
            "BUNDLE", "Bundle", 1, 1, "nested", List.of(), List.of(),
            null, null, 64, 0, List.of(), 0)),
        2);
    InventoryService.ItemView container = new InventoryService.ItemView(
        "SHULKER_BOX",
        "Shulker Box",
        1,
        1,
        "container-fingerprint",
        List.of("Stored items"),
        List.of(),
        7,
        null,
        null,
        null,
        List.of(child),
        null);
    InventoryService.Snapshot snapshot = new InventoryService.Snapshot(
        "revision",
        List.of(new InventoryService.SlotView("ENDER_CHEST", 0, "Ender chest slot 1", container)));

    JsonObject encoded = new InventorySnapshotJsonCodec(new Gson()).encode(snapshot);
    JsonObject item = encoded.getAsJsonArray("slots").get(0).getAsJsonObject()
        .getAsJsonObject("item");

    assertEquals("revision", encoded.get("revision").getAsString());
    assertEquals("SHULKER_BOX", item.get("material").getAsString());
    assertEquals(7, item.get("customModelData").getAsInt());
    assertEquals(2, item.getAsJsonArray("containerItems").get(0).getAsJsonObject()
        .get("slot").getAsInt());
    assertEquals("BUNDLE", item.getAsJsonArray("containerItems").get(0).getAsJsonObject()
        .getAsJsonObject("item").getAsJsonArray("containerItems").get(0).getAsJsonObject()
        .getAsJsonObject("item").get("material").getAsString());
    JsonObject bundle = item.getAsJsonArray("containerItems").get(0).getAsJsonObject()
        .getAsJsonObject("item").getAsJsonArray("containerItems").get(0).getAsJsonObject()
        .getAsJsonObject("item");
    assertEquals(64, bundle.get("containerCapacity").getAsInt());
    assertEquals(0, bundle.get("containerOccupancy").getAsInt());
    assertTrue(item.get("listable").getAsBoolean());
    assertFalse(encoded.has("rawItemBlob"));
  }
}
