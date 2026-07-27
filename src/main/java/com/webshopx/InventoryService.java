package com.webshopx;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

final class InventoryService {
  private static final int MAX_PREVIEW_DEPTH = 8;
  private static final int MAX_PREVIEW_CHILDREN = 64;
  private final ItemSnapshotCodec codec;

  InventoryService(ItemSnapshotCodec codec) {
    this.codec = codec;
  }

  Snapshot snapshot(Inventory inventory, InventorySource source) {
    List<SlotView> slots = new ArrayList<>();
    StringBuilder evidence = new StringBuilder(source.name()).append(':');
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack stack = inventory.getItem(slot);
      ItemView item = view(stack);
      slots.add(new SlotView(kind(source, slot), slot, label(source, slot), item));
      evidence.append(slot).append(':')
          .append(item == null ? "-" : item.fingerprint() + ":" + item.amount()).append(';');
    }
    return new Snapshot(ItemSnapshotCodec.sha256Hex(evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), slots);
  }

  Snapshot offlineSnapshot(InventorySource source) {
    List<SlotView> slots = new ArrayList<>();
    int size = source == InventorySource.ENDER_CHEST ? 27 : 41;
    for (int slot = 0; slot < size; slot++) {
      slots.add(new SlotView(kind(source, slot), slot, label(source, slot), null));
    }
    return new Snapshot("", slots);
  }

  Withdrawal withdraw(
      Inventory inventory,
      InventorySource source,
      String expectedRevision,
      int slot,
      Integer containerSlot,
      String fingerprint,
      int quantity) {
    if (!snapshot(inventory, source).revision().equals(expectedRevision)) {
      throw new ServiceException("inventory_changed", "Inventory changed; refresh and select the item again");
    }
    if (slot < 0 || slot >= inventory.getSize() || quantity <= 0) {
      throw new ServiceException("invalid_inventory_request", "Inventory slot or quantity is invalid");
    }
    ItemStack outer = inventory.getItem(slot);
    if (outer == null || outer.getType() == Material.AIR) {
      throw new ServiceException("inventory_changed", "Selected inventory slot is empty");
    }
    if (containerSlot == null) {
      assertItem(outer, fingerprint, quantity);
      ItemStack withdrawn = outer.clone();
      withdrawn.setAmount(quantity);
      ItemStack remaining = outer.clone();
      remaining.setAmount(outer.getAmount() - quantity);
      inventory.setItem(slot, remaining.getAmount() <= 0 ? null : remaining);
      return new Withdrawal(withdrawn, () -> inventory.setItem(slot, outer));
    }
    if (!(outer.getItemMeta() instanceof BlockStateMeta blockMeta)
        || !(blockMeta.getBlockState() instanceof Container container)
        || containerSlot < 0 || containerSlot >= container.getInventory().getSize()) {
      throw new ServiceException("invalid_container_slot", "Selected item is not a supported container");
    }
    ItemStack inner = container.getInventory().getItem(containerSlot);
    assertItem(inner, fingerprint, quantity);
    ItemStack withdrawn = inner.clone();
    withdrawn.setAmount(quantity);
    ItemStack remaining = inner.clone();
    remaining.setAmount(inner.getAmount() - quantity);
    container.getInventory().setItem(containerSlot, remaining.getAmount() <= 0 ? null : remaining);
    blockMeta.setBlockState(container);
    ItemStack updatedOuter = outer.clone();
    updatedOuter.setItemMeta(blockMeta);
    inventory.setItem(slot, updatedOuter);
    return new Withdrawal(withdrawn, () -> inventory.setItem(slot, outer));
  }

  ItemStack resolve(
      Inventory inventory,
      InventorySource source,
      String expectedRevision,
      int slot,
      Integer containerSlot,
      String fingerprint) {
    if (!snapshot(inventory, source).revision().equals(expectedRevision)) {
      throw new ServiceException("inventory_changed", "Inventory changed; refresh and select the item again");
    }
    if (slot < 0 || slot >= inventory.getSize()) {
      throw new ServiceException("invalid_inventory_request", "Inventory slot is invalid");
    }
    ItemStack outer = inventory.getItem(slot);
    if (containerSlot == null) {
      assertItem(outer, fingerprint, 1);
      return outer.clone();
    }
    if (!(outer != null && outer.getItemMeta() instanceof BlockStateMeta blockMeta)
        || !(blockMeta.getBlockState() instanceof Container container)
        || containerSlot < 0 || containerSlot >= container.getInventory().getSize()) {
      throw new ServiceException("invalid_container_slot", "Selected item is not a supported container");
    }
    ItemStack inner = container.getInventory().getItem(containerSlot);
    assertItem(inner, fingerprint, 1);
    return inner.clone();
  }

  private void assertItem(ItemStack stack, String fingerprint, int quantity) {
    if (stack == null || stack.getType() == Material.AIR || stack.getAmount() < quantity) {
      throw new ServiceException("inventory_changed", "Selected item quantity changed");
    }
    ItemStack unit = stack.clone();
    unit.setAmount(1);
    if (!codec.serialize(unit).itemHash().equals(fingerprint)) {
      throw new ServiceException("inventory_changed", "Selected item changed");
    }
  }

  private ItemView view(ItemStack stack) {
    return view(stack, 0);
  }

  private ItemView view(ItemStack stack, int depth) {
    if (stack == null || stack.getType() == Material.AIR) return null;
    ItemStack unit = stack.clone();
    unit.setAmount(1);
    ItemMeta meta = stack.getItemMeta();
    List<ItemView> contents = new ArrayList<>();
    if (depth < MAX_PREVIEW_DEPTH
        && stack.getType().name().endsWith("_SHULKER_BOX")
        && meta instanceof BlockStateMeta blockMeta
        && blockMeta.getBlockState() instanceof Container container) {
      for (int index = 0; index < container.getInventory().getSize(); index++) {
        ItemView child = view(container.getInventory().getItem(index), depth + 1);
        if (child != null) contents.add(child.withContainerSlot(index));
      }
    } else if (depth < MAX_PREVIEW_DEPTH && meta instanceof BundleMeta bundleMeta) {
      List<ItemStack> bundleItems = bundleMeta.getItems();
      for (int index = 0;
          index < bundleItems.size() && index < MAX_PREVIEW_CHILDREN;
          index++) {
        ItemView child = view(bundleItems.get(index), depth + 1);
        if (child != null) contents.add(child.withContainerSlot(index));
      }
    }
    return new ItemView(
        stack.getType().name(),
        meta != null && meta.hasDisplayName() ? meta.getDisplayName() : stack.getType().name(),
        stack.getAmount(),
        stack.getMaxStackSize(),
        codec.serialize(unit).itemHash(),
        meta != null && meta.hasLore() ? List.copyOf(meta.getLore()) : List.of(),
        enchantments(stack),
        meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : null,
        resolveItemModel(meta),
        meta instanceof BundleMeta ? 64 : null,
        meta instanceof BundleMeta bundleMeta
            ? ItemSnapshotCodec.bundleOccupancy(bundleMeta.getItems()) : null,
        contents,
        null);
  }

  private List<String> enchantments(ItemStack stack) {
    List<String> values = new ArrayList<>();
    stack.getEnchantments().forEach((enchantment, level) ->
        values.add(enchantment.getKey().getKey() + " " + level));
    if (stack.getItemMeta() instanceof EnchantmentStorageMeta storageMeta) {
      storageMeta.getStoredEnchants().forEach((enchantment, level) ->
          values.add(enchantment.getKey().getKey() + " " + level));
    }
    return List.copyOf(values);
  }

  private String resolveItemModel(ItemMeta meta) {
    if (meta == null) {
      return null;
    }
    try {
      Object value = meta.getClass().getMethod("getItemModel").invoke(meta);
      return value == null ? null : value.toString();
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }

  private String kind(InventorySource source, int slot) {
    if (source == InventorySource.ENDER_CHEST) return "ENDER_CHEST";
    if (slot < 9) return "HOTBAR";
    if (slot < 36) return "MAIN";
    if (slot == 40) return "OFFHAND";
    return "ARMOR";
  }

  private String label(InventorySource source, int slot) {
    if (source == InventorySource.ENDER_CHEST) return "末影箱第 " + (slot + 1) + " 格";
    if (slot < 9) return "快捷栏第 " + (slot + 1) + " 格";
    if (slot < 36) return "背包第 " + (slot + 1) + " 格";
    if (slot == 40) return "副手";
    return "装备栏第 " + (slot - 35) + " 格";
  }

  record Snapshot(String revision, List<SlotView> slots) {}
  record SlotView(String kind, int index, String label, ItemView item) {}
  record Withdrawal(ItemStack item, Runnable restore) {}
  enum InventorySource {
    PLAYER,
    ENDER_CHEST;

    static InventorySource parse(String value) {
      return "ENDER_CHEST".equalsIgnoreCase(String.valueOf(value)) ? ENDER_CHEST : PLAYER;
    }
  }
  record ItemView(
      String material,
      String name,
      int amount,
      int maxStackSize,
      String fingerprint,
      List<String> lore,
      List<String> enchantments,
      Integer customModelData,
      String itemModel,
      Integer containerCapacity,
      Integer containerOccupancy,
      List<ItemView> containerItems,
      Integer containerSlot) {
    ItemView withContainerSlot(int slot) {
      return new ItemView(material, name, amount, maxStackSize, fingerprint, lore, enchantments,
          customModelData, itemModel, containerCapacity, containerOccupancy, containerItems, slot);
    }
  }
}
