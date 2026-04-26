package com.webshopx;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

class PlayerQuitListener implements Listener {
  private final JavaPlugin plugin;
  private final PlayerPresenceService playerPresenceService;

  PlayerQuitListener(JavaPlugin plugin, PlayerPresenceService playerPresenceService) {
    this.plugin = plugin;
    this.playerPresenceService = playerPresenceService;
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    plugin.getServer().getScheduler().runTaskAsynchronously(
        plugin,
        () -> {
          try {
            playerPresenceService.markOffline(uuid);
          } catch (Exception exception) {
            plugin.getLogger().warning("Failed to mark player offline in presence table: " + exception.getMessage());
          }
        });
  }
}
