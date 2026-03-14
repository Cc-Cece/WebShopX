package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

final class ItemSnapshotCodec {

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
    JsonObject meta = new JsonObject();
    meta.addProperty("material", itemStack.getType().name());
    meta.addProperty("amount", itemStack.getAmount());

    ItemMeta itemMeta = itemStack.getItemMeta();
    if (itemMeta == null) {
      return meta;
    }

    if (itemMeta.hasDisplayName()) {
      meta.addProperty("displayName", itemMeta.getDisplayName());
    }
    if (itemMeta.hasLore()) {
      JsonArray loreArray = new JsonArray();
      for (String line : itemMeta.getLore()) {
        loreArray.add(line);
      }
      meta.add("lore", loreArray);
    }
    if (!itemMeta.getEnchants().isEmpty()) {
      JsonObject enchantments = new JsonObject();
      for (Map.Entry<Enchantment, Integer> entry : itemMeta.getEnchants().entrySet()) {
        Enchantment enchantment = entry.getKey();
        if (enchantment.getKey() == null) {
          continue;
        }
        enchantments.addProperty(enchantment.getKey().toString(), entry.getValue());
      }
      meta.add("enchants", enchantments);
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
    return meta;
  }

  private String sha256Hex(byte[] data) {
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
