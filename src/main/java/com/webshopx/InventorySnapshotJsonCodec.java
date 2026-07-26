package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

final class InventorySnapshotJsonCodec {
  private final Gson gson;

  InventorySnapshotJsonCodec(Gson gson) {
    this.gson = gson;
  }

  JsonObject encode(InventoryService.Snapshot snapshot) {
    JsonObject response = new JsonObject();
    JsonArray slots = new JsonArray();
    for (InventoryService.SlotView slot : snapshot.slots()) {
      JsonObject row = new JsonObject();
      row.addProperty("kind", slot.kind());
      row.addProperty("index", slot.index());
      row.addProperty("label", slot.label());
      row.add("item", slot.item() == null ? JsonNull.INSTANCE : encodeItem(slot.item()));
      slots.add(row);
    }
    response.addProperty("revision", snapshot.revision());
    response.add("slots", slots);
    return response;
  }

  private JsonObject encodeItem(InventoryService.ItemView item) {
    JsonObject view = new JsonObject();
    view.addProperty("material", item.material());
    view.addProperty("name", item.name());
    view.addProperty("amount", item.amount());
    view.addProperty("maxStackSize", item.maxStackSize());
    view.addProperty("fingerprint", item.fingerprint());
    view.add("lore", gson.toJsonTree(item.lore()));
    view.add("enchantments", gson.toJsonTree(item.enchantments()));
    if (item.customModelData() != null) {
      view.addProperty("customModelData", item.customModelData());
    }
    if (item.itemModel() != null) {
      view.addProperty("itemModel", item.itemModel());
    }
    view.addProperty("recyclable", true);
    view.addProperty("listable", true);
    JsonArray contents = new JsonArray();
    for (InventoryService.ItemView child : item.containerItems()) {
      JsonObject entry = new JsonObject();
      entry.addProperty("slot", child.containerSlot());
      entry.add("item", encodeItem(child));
      contents.add(entry);
    }
    if (!contents.isEmpty()
        || item.material().endsWith("_SHULKER_BOX")
        || "BUNDLE".equals(item.material())
        || item.material().endsWith("_BUNDLE")) {
      view.add("containerItems", contents);
    }
    return view;
  }
}
