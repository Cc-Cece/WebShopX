package com.webshopx;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

class PlayerJoinListener implements Listener {
  private final JavaPlugin plugin;
  private final DeliveryService deliveryService;
  private final PlayerPresenceService playerPresenceService;

  PlayerJoinListener(
      JavaPlugin plugin,
      DeliveryService deliveryService,
      PlayerPresenceService playerPresenceService) {
    this.plugin = plugin;
    this.deliveryService = deliveryService;
    this.playerPresenceService = playerPresenceService;
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    String playerName = event.getPlayer().getName();
    java.util.UUID playerUuid = event.getPlayer().getUniqueId();
    plugin.getServer().getScheduler().runTaskAsynchronously(
        plugin,
        () -> {
          try {
            playerPresenceService.markOnline(playerUuid, playerName);
          } catch (Exception exception) {
            plugin.getLogger().warning("Failed to mark player online in presence table: " + exception.getMessage());
          }
        });
    plugin.getServer().getScheduler().runTaskLater(
        plugin,
        () -> deliveryService.processPlayerJoin(event.getPlayer()),
        40L);
  }
}
