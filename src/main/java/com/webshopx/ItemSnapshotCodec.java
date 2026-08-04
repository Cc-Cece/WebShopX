package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

final class ItemSnapshotCodec {
  private static final int MAX_PREVIEW_DEPTH = 8;
  private static final int MAX_PREVIEW_CHILDREN = 64;

  Snapshot serialize(ItemStack itemStack) {
    if (itemStack == null || itemStack.getType() == Material.AIR) {
      throw new ServiceException("invalid_item", "Cannot snapshot empty item");
    }
    byte[] raw = toBinary(itemStack);
    return new Snapshot(
        raw,
        toMetaJson(itemStack).toString(),
        sha256Hex(raw));
  }

  @SuppressFBWarnings(
      value = "OBJECT_DESERIALIZATION",
      justification = "Item blobs are produced by this plugin and only deserialized back into Bukkit ItemStack snapshots")
  ItemStack deserialize(byte[] rawItemBlob) {
    if (rawItemBlob == null || rawItemBlob.length == 0) {
      throw new ServiceException("invalid_item_blob", "Item snapshot blob is empty");
    }
    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(rawItemBlob);
         BukkitObjectInputStream objectInputStream = new BukkitObjectInputStream(inputStream)) {
      Object value = objectInputStream.readObject();
      if (!(value instanceof ItemStack itemStack)) {
        throw new IllegalStateException("Snapshot payload is not an ItemStack");
      }
      return itemStack;
    } catch (IOException | ClassNotFoundException exception) {
      throw new IllegalStateException("Failed to deserialize item snapshot", exception);
    }
  }

  Snapshot validateRoundTrip(ItemStack itemStack) {
    ItemStack normalized = itemStack.clone();
    normalized.setAmount(1);
    Snapshot first = serialize(normalized);
    ItemStack restored = deserialize(first.rawItemBlob());
    restored.setAmount(1);
    Snapshot second = serialize(restored);
    if (restored.getType() != normalized.getType()
        || !first.itemHash().equals(second.itemHash())
        || !first.itemMetaJson().equals(second.itemMetaJson())) {
      throw new ServiceException(
          "unsupported_item_snapshot",
          "This item cannot be preserved by the current server runtime");
    }
    return first;
  }

  String toBase64(byte[] rawItemBlob) {
    return Base64.getEncoder().encodeToString(rawItemBlob);
  }

  private byte[] toBinary(ItemStack itemStack) {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
         BukkitObjectOutputStream objectOutputStream = new BukkitObjectOutputStream(outputStream)) {
      objectOutputStream.writeObject(itemStack);
      objectOutputStream.flush();
      return outputStream.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to serialize item snapshot", exception);
    }
  }

  private JsonObject toMetaJson(ItemStack itemStack) {
    return toMetaJson(itemStack, 0);
  }

  private JsonObject toMetaJson(ItemStack itemStack, int depth) {
    JsonObject meta = new JsonObject();
    meta.addProperty("material", itemStack.getType().name());
    meta.addProperty("amount", itemStack.getAmount());
    meta.addProperty("maxStackSize", itemStack.getMaxStackSize());

    ItemMeta itemMeta = itemStack.getItemMeta();
    if (itemMeta == null) {
      return meta;
    }

    if (itemMeta.hasDisplayName()) {
      meta.addProperty("displayName", itemMeta.getDisplayName());
    }
    if (itemMeta.hasLore()) {
      var lore = itemMeta.getLore();
      if (lore != null) {
        JsonArray loreArray = new JsonArray();
        for (String line : lore) {
          loreArray.add(line);
        }
        meta.add("lore", loreArray);
      }
    }
    JsonObject enchantments = serializeEnchantments(itemMeta.getEnchants());
    if (enchantments.size() > 0) {
      meta.add("enchants", enchantments);
    }
    if (itemMeta instanceof EnchantmentStorageMeta storageMeta) {
      JsonObject storedEnchantments = serializeEnchantments(storageMeta.getStoredEnchants());
      if (storedEnchantments.size() > 0) {
        meta.add("storedEnchants", storedEnchantments);
      }
    }
    if (itemMeta instanceof Damageable damageable) {
      int maxDurability = itemStack.getType().getMaxDurability();
      if (maxDurability > 0) {
        int current = Math.max(0, maxDurability - damageable.getDamage());
        int percent = (int) Math.floor((current * 100.0D) / maxDurability);
        meta.addProperty("durabilityCurrent", current);
        meta.addProperty("durabilityMax", maxDurability);
        meta.addProperty("durabilityPercent", percent);
      }
    }
    if (itemMeta.isUnbreakable()) {
      meta.addProperty("unbreakable", true);
    }
    if (itemMeta.hasCustomModelData()) {
      meta.addProperty("customModelData", itemMeta.getCustomModelData());
    }
    if (itemMeta instanceof Repairable repairable && repairable.hasRepairCost()) {
      meta.addProperty("repairCost", repairable.getRepairCost());
    }
    addContainerPreview(meta, itemStack, itemMeta, depth);
    return meta;
  }

  private void addContainerPreview(
      JsonObject meta, ItemStack itemStack, ItemMeta itemMeta, int depth) {
    boolean shulker = itemStack.getType().name().endsWith("_SHULKER_BOX");
    boolean bundle = itemMeta instanceof BundleMeta;
    if (!shulker && !bundle) return;

    JsonArray contents = new JsonArray();
    if (depth < MAX_PREVIEW_DEPTH
        && shulker
        && itemMeta instanceof BlockStateMeta blockMeta
        && blockMeta.getBlockState() instanceof Container container) {
      for (int slot = 0; slot < container.getInventory().getSize(); slot++) {
        addPreviewEntry(contents, container.getInventory().getItem(slot), slot, depth + 1);
      }
    } else if (depth < MAX_PREVIEW_DEPTH && itemMeta instanceof BundleMeta bundleMeta) {
      List<ItemStack> items = bundleMeta.getItems();
      for (int index = 0;
          index < items.size() && index < MAX_PREVIEW_CHILDREN;
          index++) {
        addPreviewEntry(contents, items.get(index), index, depth + 1);
      }
      meta.addProperty("containerCapacity", 64);
      meta.addProperty("containerOccupancy", bundleOccupancy(items));
    }
    meta.add("containerItems", contents);
  }

  static int bundleOccupancy(List<ItemStack> items) {
    if (items == null || items.isEmpty()) {
      return 0;
    }
    int occupancy = 0;
    for (ItemStack item : items) {
      if (item == null || item.getType() == Material.AIR) {
        continue;
      }
      ItemMeta meta = item.getItemMeta();
      if (meta instanceof BundleMeta nestedBundle) {
        occupancy += 4 + bundleOccupancy(nestedBundle.getItems());
      } else {
        int maxStackSize = Math.max(1, item.getMaxStackSize());
        int occupancyPerItem = 64 / maxStackSize + (64 % maxStackSize == 0 ? 0 : 1);
        occupancy += occupancyPerItem * item.getAmount();
      }
    }
    return Math.min(64, occupancy);
  }

  private void addPreviewEntry(
      JsonArray contents, ItemStack child, int slot, int depth) {
    if (child == null || child.getType() == Material.AIR) return;
    JsonObject entry = new JsonObject();
    entry.addProperty("slot", slot);
    entry.add("item", toMetaJson(child, depth));
    contents.add(entry);
  }

  private JsonObject serializeEnchantments(Map<Enchantment, Integer> enchantments) {
    JsonObject result = new JsonObject();
    if (enchantments == null || enchantments.isEmpty()) {
      return result;
    }
    for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
      Enchantment enchantment = entry.getKey();
      if (enchantment == null || enchantment.getKey() == null) {
        continue;
      }
      result.addProperty(enchantment.getKey().toString(), entry.getValue());
    }
    return result;
  }

  static String sha256Hex(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data);
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        builder.append(Character.forDigit((value >>> 4) & 0xF, 16));
        builder.append(Character.forDigit(value & 0xF, 16));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
    }
  }

  record Snapshot(byte[] rawItemBlob, String itemMetaJson, String itemHash) {
  }
}
