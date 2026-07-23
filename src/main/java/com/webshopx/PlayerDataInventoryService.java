package com.webshopx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.io.SNBTUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.NumberTag;
import net.querz.nbt.tag.Tag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

final class PlayerDataInventoryService {
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);
  private final JavaPlugin plugin;
  private final InventoryLockManager lockManager;
  private final ItemSnapshotCodec itemCodec;

  PlayerDataInventoryService(
      JavaPlugin plugin, InventoryLockManager lockManager, ItemSnapshotCodec itemCodec) {
    this.plugin = plugin;
    this.lockManager = lockManager;
    this.itemCodec = itemCodec;
  }

  InventoryService.Snapshot read(UUID playerUuid, InventoryService.InventorySource source) {
    try (InventoryLockManager.Guard ignored = lockManager.acquire(playerUuid, LOCK_TIMEOUT)) {
      assertOffline(playerUuid);
      PlayerFile playerFile = load(playerUuid);
      return snapshot(playerFile.root(), source);
    }
  }

  OfflineWithdrawal withdraw(
      UUID playerUuid,
      InventoryService.InventorySource source,
      String expectedRevision,
      int slot,
      String fingerprint,
      int quantity,
      long userId,
      String operationId) {
    InventoryLockManager.Guard guard = lockManager.acquire(playerUuid, LOCK_TIMEOUT);
    try {
      assertOffline(playerUuid);
      PlayerFile playerFile = load(playerUuid);
      InventoryService.Snapshot current = snapshot(playerFile.root(), source);
      if (!current.revision().equals(expectedRevision)) {
        throw new ServiceException(
            "inventory_changed", "Inventory changed; refresh and select the item again");
      }
      if (quantity <= 0) {
        throw new ServiceException("invalid_inventory_request", "Quantity must be positive");
      }
      ListTag<CompoundTag> items = itemList(playerFile.root(), source);
      CompoundTag selected = findSlot(items, source, slot);
      if (selected == null) {
        throw new ServiceException("item_not_found", "Selected inventory slot is empty");
      }
      int available = itemCount(selected);
      if (available < quantity) {
        throw new ServiceException("insufficient_amount", "Selected item quantity changed");
      }
      String actualFingerprint = fingerprint(selected);
      if (!actualFingerprint.equals(fingerprint)) {
        throw new ServiceException("inventory_changed", "Selected item changed");
      }
      CompoundTag withdrawnTag = selected.clone();
      setItemCount(withdrawnTag, quantity);
      if (available == quantity) {
        items.remove(items.indexOf(selected));
      } else {
        setItemCount(selected, available - quantity);
      }
      ItemStack item = toItemStack(withdrawnTag);
      assertOffline(playerUuid);
      Backup backup = safeWrite(playerFile, userId, operationId);
      return new OfflineWithdrawal(item, backup, guard);
    } catch (RuntimeException exception) {
      guard.close();
      throw exception;
    }
  }

  private InventoryService.Snapshot snapshot(
      CompoundTag root, InventoryService.InventorySource source) {
    int size = source == InventoryService.InventorySource.ENDER_CHEST ? 27 : 41;
    List<InventoryService.SlotView> slots = new ArrayList<>(size);
    ListTag<CompoundTag> items = itemList(root, source);
    StringBuilder evidence = new StringBuilder(source.name()).append(':');
    for (int slot = 0; slot < size; slot++) {
      CompoundTag item = findSlot(items, source, slot);
      InventoryService.ItemView view = item == null ? null : view(item);
      slots.add(new InventoryService.SlotView(kind(source, slot), slot, label(source, slot), view));
      evidence.append(slot).append(':')
          .append(view == null ? "-" : view.fingerprint() + ":" + view.amount()).append(';');
    }
    return new InventoryService.Snapshot(
        ItemSnapshotCodec.sha256Hex(evidence.toString().getBytes(StandardCharsets.UTF_8)), slots);
  }

  @SuppressWarnings("unchecked")
  private ListTag<CompoundTag> itemList(
      CompoundTag root, InventoryService.InventorySource source) {
    String key = source == InventoryService.InventorySource.ENDER_CHEST
        ? "EnderItems" : "Inventory";
    ListTag<?> existing = root.getListTag(key);
    if (existing == null) {
      ListTag<CompoundTag> created = new ListTag<>(CompoundTag.class);
      root.put(key, created);
      return created;
    }
    return (ListTag<CompoundTag>) existing;
  }

  private CompoundTag findSlot(
      ListTag<CompoundTag> items, InventoryService.InventorySource source, int webSlot) {
    int dataSlot = toDataSlot(source, webSlot);
    for (CompoundTag item : items) {
      if (item.getByte("Slot") == (byte) dataSlot) {
        return item;
      }
    }
    return null;
  }

  private int toDataSlot(InventoryService.InventorySource source, int webSlot) {
    if (source == InventoryService.InventorySource.ENDER_CHEST) {
      return webSlot;
    }
    if (webSlot >= 0 && webSlot <= 35) {
      return webSlot;
    }
    if (webSlot >= 36 && webSlot <= 39) {
      return 100 + (webSlot - 36);
    }
    if (webSlot == 40) {
      return -106;
    }
    return Integer.MIN_VALUE;
  }

  private InventoryService.ItemView view(CompoundTag item) {
    String id = item.getString("id");
    String materialName = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
    int amount = itemCount(item);
    String name = materialName.toUpperCase(Locale.ROOT);
    ItemStack converted = null;
    try {
      converted = toItemStack(item);
      if (converted.hasItemMeta() && converted.getItemMeta().hasDisplayName()) {
        name = converted.getItemMeta().getDisplayName();
      }
    } catch (RuntimeException ignored) {
      // Read-only display remains available even when a new component cannot
      // yet be converted by this server runtime.
    }
    return new InventoryService.ItemView(
        materialName.toUpperCase(Locale.ROOT),
        name,
        amount,
        converted == null ? 64 : converted.getMaxStackSize(),
        fingerprint(item),
        converted != null && converted.hasItemMeta() && converted.getItemMeta().hasLore()
            ? List.copyOf(converted.getItemMeta().getLore()) : List.of(),
        converted == null ? List.of() : converted.getEnchantments().entrySet().stream()
            .map(entry -> entry.getKey().getKey().getKey() + " " + entry.getValue()).toList(),
        converted != null && converted.hasItemMeta() && converted.getItemMeta().hasCustomModelData()
            ? converted.getItemMeta().getCustomModelData() : null,
        null,
        List.of(),
        null);
  }

  private String fingerprint(CompoundTag item) {
    CompoundTag normalized = item.clone();
    normalized.remove("Slot");
    setItemCount(normalized, 1);
    return ItemSnapshotCodec.sha256Hex(
        snbt(normalized).getBytes(StandardCharsets.UTF_8));
  }

  private int itemCount(CompoundTag item) {
    Tag<?> count = item.containsKey("count") ? item.get("count") : item.get("Count");
    if (count instanceof NumberTag<?> number) {
      return number.asInt();
    }
    return 0;
  }

  private void setItemCount(CompoundTag item, int count) {
    if (item.containsKey("count")) {
      item.putInt("count", count);
    } else {
      item.putByte("Count", (byte) count);
    }
  }

  private ItemStack toItemStack(CompoundTag item) {
    String id = item.getString("id");
    StringBuilder itemString = new StringBuilder(id);
    CompoundTag components = item.getCompoundTag("components");
    if (components != null && components.size() > 0) {
      itemString.append('[');
      boolean first = true;
      for (var entry : components.entrySet()) {
        if (!first) {
          itemString.append(',');
        }
        first = false;
        itemString.append(entry.getKey()).append('=')
            .append(snbt(entry.getValue()));
      }
      itemString.append(']');
    } else {
      CompoundTag tag = item.getCompoundTag("tag");
      if (tag != null && tag.size() > 0) {
        itemString.append(snbt(tag));
      }
    }
    ItemStack stack;
    try {
      stack = Bukkit.getItemFactory().createItemStack(itemString.toString());
    } catch (IllegalArgumentException exception) {
      Material material = Material.matchMaterial(id);
      if (material == null || components != null || item.getCompoundTag("tag") != null) {
        throw new ServiceException(
            "unsupported_server_version", "This item data is not supported by the server runtime");
      }
      stack = new ItemStack(material);
    }
    stack.setAmount(itemCount(item));
    // Force serialization now; if Bukkit cannot preserve this item, do not
    // commit a market listing that differs from the removed playerdata item.
    itemCodec.serialize(stack);
    return stack;
  }

  private PlayerFile load(UUID playerUuid) {
    Path path = playerDataPath(playerUuid);
    if (!Files.isRegularFile(path)) {
      throw new ServiceException("playerdata_read_failed", "Player data file does not exist");
    }
    try {
      NamedTag named = NBTUtil.read(path.toFile());
      if (!(named.getTag() instanceof CompoundTag root)) {
        throw new IOException("Root NBT tag is not a compound");
      }
      return new PlayerFile(path, named.getName(), root);
    } catch (IOException exception) {
      throw new ServiceException("playerdata_read_failed", "Could not read player data");
    }
  }

  private String snbt(Tag<?> tag) {
    try {
      return SNBTUtil.toSNBT(tag);
    } catch (IOException exception) {
      throw new ServiceException("playerdata_verify_failed", "Could not normalize player item data");
    }
  }

  private Path playerDataPath(UUID playerUuid) {
    List<World> worlds = plugin.getServer().getWorlds();
    if (worlds.isEmpty()) {
      throw new ServiceException("playerdata_read_failed", "No Bukkit world is loaded");
    }
    return worlds.get(0).getWorldFolder().toPath()
        .resolve("playerdata")
        .resolve(playerUuid.toString() + ".dat")
        .toAbsolutePath()
        .normalize();
  }

  private Backup safeWrite(PlayerFile playerFile, long userId, String operationId) {
    Path original = playerFile.path();
    Path directory = original.getParent();
    Path temp = directory.resolve(original.getFileName() + ".wsx.tmp");
    Path backupDir = directory.resolve(".webshopx-backups");
    String backupName = original.getFileName() + "." + System.currentTimeMillis() + ".bak";
    Path backup = backupDir.resolve(backupName);
    Path journal = backupDir.resolve(
        original.getFileName() + "." + operationId.replaceAll("[^A-Za-z0-9_.-]", "_")
            + ".journal");
    try {
      Files.createDirectories(backupDir);
      Files.copy(original, backup, StandardCopyOption.COPY_ATTRIBUTES);
      Properties recovery = new Properties();
      recovery.setProperty("userId", Long.toString(userId));
      recovery.setProperty("operationId", operationId);
      recovery.setProperty("original", original.toString());
      recovery.setProperty("backup", backup.toString());
      try (var output = Files.newOutputStream(journal)) {
        recovery.store(output, "WebShopX offline inventory recovery journal");
      }
      NBTUtil.write(
          new NamedTag(playerFile.rootName(), playerFile.root()), temp.toFile());
      NamedTag verified = NBTUtil.read(temp.toFile());
      if (!(verified.getTag() instanceof CompoundTag verifiedRoot)
          || !SNBTUtil.toSNBT(verifiedRoot).equals(SNBTUtil.toSNBT(playerFile.root()))) {
        throw new IOException("Temporary playerdata verification failed");
      }
      try {
        Files.move(
            temp, original,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temp, original, StandardCopyOption.REPLACE_EXISTING);
      }
      pruneBackups(backupDir, original.getFileName().toString());
      return new Backup(original, backup, journal);
    } catch (IOException exception) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {
        // Keep the primary write error.
      }
      throw new ServiceException("playerdata_write_failed", "Could not safely write player data");
    }
  }

  void recoverPending(InventoryOperationService operations) {
    for (World world : plugin.getServer().getWorlds()) {
      Path backupDir = world.getWorldFolder().toPath()
          .resolve("playerdata")
          .resolve(".webshopx-backups");
      if (!Files.isDirectory(backupDir)) {
        continue;
      }
      try (var files = Files.list(backupDir)) {
        for (Path journal : files.filter(path ->
            path.getFileName().toString().endsWith(".journal")).toList()) {
          recoverJournal(journal, operations);
        }
      } catch (IOException exception) {
        plugin.getLogger().warning(
            "Could not scan offline inventory recovery journals: " + exception.getMessage());
      }
    }
  }

  private void recoverJournal(Path journal, InventoryOperationService operations) {
    Properties recovery = new Properties();
    try (var input = Files.newInputStream(journal)) {
      recovery.load(input);
      long userId = Long.parseLong(recovery.getProperty("userId"));
      String operationId = recovery.getProperty("operationId");
      InventoryOperationService.Existing existing = operations.find(userId, operationId);
      if (existing != null && "SUCCESS".equals(existing.state())) {
        Files.deleteIfExists(journal);
        return;
      }
      Path original = Path.of(recovery.getProperty("original")).toAbsolutePath().normalize();
      Path backup = Path.of(recovery.getProperty("backup")).toAbsolutePath().normalize();
      Path expectedRoot = journal.getParent().getParent().toAbsolutePath().normalize();
      if (!original.getParent().equals(expectedRoot) || !backup.getParent().equals(journal.getParent())) {
        throw new IOException("Recovery journal paths escaped the playerdata directory");
      }
      Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
      if (existing != null && "PENDING".equals(existing.state())) {
        operations.reject(userId, operationId, "recovered_after_restart");
      }
      Files.deleteIfExists(journal);
      plugin.getLogger().warning(
          "Recovered an interrupted offline inventory operation: " + operationId);
    } catch (IOException | RuntimeException exception) {
      plugin.getLogger().severe(
          "Offline inventory recovery requires manual attention for "
              + journal + ": " + exception.getMessage());
    }
  }

  private void pruneBackups(Path backupDir, String prefix) throws IOException {
    try (var files = Files.list(backupDir)) {
      List<Path> matching = files
          .filter(path -> path.getFileName().toString().startsWith(prefix + "."))
          .sorted(Comparator.comparingLong(this::lastModified).reversed())
          .toList();
      for (int index = 3; index < matching.size(); index++) {
        Files.deleteIfExists(matching.get(index));
      }
    }
  }

  private long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException exception) {
      return Long.MIN_VALUE;
    }
  }

  private void assertOffline(UUID playerUuid) {
    if (Bukkit.getPlayer(playerUuid) != null) {
      throw new ServiceException(
          "player_state_changed", "Player came online during the offline inventory operation");
    }
  }

  private String kind(InventoryService.InventorySource source, int slot) {
    if (source == InventoryService.InventorySource.ENDER_CHEST) return "ENDER_CHEST";
    if (slot < 9) return "HOTBAR";
    if (slot < 36) return "MAIN";
    if (slot == 40) return "OFFHAND";
    return "ARMOR";
  }

  private String label(InventoryService.InventorySource source, int slot) {
    if (source == InventoryService.InventorySource.ENDER_CHEST) {
      return "末影箱第 " + (slot + 1) + " 格";
    }
    if (slot < 9) {
      return "快捷栏第 " + (slot + 1) + " 格";
    }
    if (slot < 36) {
      return "背包第 " + (slot + 1) + " 格";
    }
    if (slot == 40) {
      return "副手";
    }
    return "装备栏第 " + (slot - 35) + " 格";
  }

  record PlayerFile(Path path, String rootName, CompoundTag root) {}
  record Backup(Path original, Path backup, Path journal) {
    void restore() {
      try {
        Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(journal);
      } catch (IOException exception) {
        throw new ServiceException("recovery_required", "Could not restore playerdata backup");
      }
    }

    void complete() {
      try {
        Files.deleteIfExists(journal);
      } catch (IOException exception) {
        // A committed operation is authoritative in the database. Leaving the
        // journal is safe: startup recovery observes SUCCESS and removes it.
      }
    }
  }

  final class OfflineWithdrawal implements AutoCloseable {
    private final ItemStack item;
    private final Backup backup;
    private final InventoryLockManager.Guard guard;
    private boolean closed;

    private OfflineWithdrawal(
        ItemStack item, Backup backup, InventoryLockManager.Guard guard) {
      this.item = item;
      this.backup = backup;
      this.guard = guard;
    }

    ItemStack item() {
      return item;
    }

    void rollback() {
      backup.restore();
    }

    void commit() {
      backup.complete();
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        guard.close();
      }
    }
  }
}
