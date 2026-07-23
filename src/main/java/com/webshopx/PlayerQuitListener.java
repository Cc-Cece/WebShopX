package com.webshopx;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

class PlayerQuitListener implements Listener {
  private final JavaPlugin plugin;
  private final PlayerPresenceService playerPresenceService;
  private final SchedulerBridge schedulerBridge;
  private final InventoryReadSnapshotService inventoryReadSnapshotService;
  private final InventoryService inventoryService = new InventoryService(new ItemSnapshotCodec());

  PlayerQuitListener(
      JavaPlugin plugin,
      PlayerPresenceService playerPresenceService,
      SchedulerBridge schedulerBridge,
      InventoryReadSnapshotService inventoryReadSnapshotService) {
    this.plugin = plugin;
    this.playerPresenceService = playerPresenceService;
    this.schedulerBridge = schedulerBridge;
    this.inventoryReadSnapshotService = inventoryReadSnapshotService;
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    InventoryService.Snapshot playerSnapshot = captureSnapshot(
        event, event.getPlayer().getInventory(), InventoryService.InventorySource.PLAYER);
    InventoryService.Snapshot enderSnapshot = captureSnapshot(
        event, event.getPlayer().getEnderChest(), InventoryService.InventorySource.ENDER_CHEST);
    schedulerBridge.runAsync(() -> {
      saveSnapshot(uuid, InventoryService.InventorySource.PLAYER, playerSnapshot);
      saveSnapshot(uuid, InventoryService.InventorySource.ENDER_CHEST, enderSnapshot);
      try {
        playerPresenceService.markOffline(uuid);
      } catch (Exception exception) {
        MessageService ms = new MessageService(plugin, () -> PluginSettings.fromConfig(plugin.getConfig()));
        plugin.getLogger().warning(ms.formatConsole("console.failed_mark_player_offline", MapUtils.mapOf("reason", exception.getMessage())));
      }
    });
  }

  private InventoryService.Snapshot captureSnapshot(
      PlayerQuitEvent event,
      org.bukkit.inventory.Inventory inventory,
      InventoryService.InventorySource source) {
    try {
      return inventoryService.snapshot(inventory, source);
    } catch (Exception exception) {
      plugin.getLogger().warning(
          "Could not capture " + source.name() + " snapshot on quit for "
              + event.getPlayer().getName() + ": " + exception.getMessage());
      return null;
    }
  }

  private void saveSnapshot(
      UUID uuid,
      InventoryService.InventorySource source,
      InventoryService.Snapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    try {
      inventoryReadSnapshotService.save(uuid, source, snapshot);
    } catch (Exception exception) {
      plugin.getLogger().warning(
          "Could not persist " + source.name()
              + " snapshot on quit: " + exception.getMessage());
    }
  }
}
