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

  PlayerQuitListener(
      JavaPlugin plugin,
      PlayerPresenceService playerPresenceService,
      SchedulerBridge schedulerBridge) {
    this.plugin = plugin;
    this.playerPresenceService = playerPresenceService;
    this.schedulerBridge = schedulerBridge;
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    schedulerBridge.runAsync(() -> {
      try {
        playerPresenceService.markOffline(uuid);
      } catch (Exception exception) {
        MessageService ms = new MessageService(plugin, () -> PluginSettings.fromConfig(plugin.getConfig()));
        plugin.getLogger().warning(ms.formatConsole("console.failed_mark_player_offline", MapUtils.mapOf("reason", exception.getMessage())));
      }
    });
  }
}
