package com.webshopx;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

class PlayerJoinListener implements Listener {
  private final JavaPlugin plugin;
  private final DeliveryService deliveryService;
  private final PlayerPresenceService playerPresenceService;
  private final SchedulerBridge schedulerBridge;

  PlayerJoinListener(
      JavaPlugin plugin,
      DeliveryService deliveryService,
      PlayerPresenceService playerPresenceService,
      SchedulerBridge schedulerBridge) {
    this.plugin = plugin;
    this.deliveryService = deliveryService;
    this.playerPresenceService = playerPresenceService;
    this.schedulerBridge = schedulerBridge;
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    String playerName = event.getPlayer().getName();
    java.util.UUID playerUuid = event.getPlayer().getUniqueId();
    schedulerBridge.runAsync(() -> {
      try {
        playerPresenceService.markOnline(playerUuid, playerName);
      } catch (Exception exception) {
        MessageService ms = new MessageService(plugin, () -> PluginSettings.fromConfig(plugin.getConfig()));
        plugin.getLogger().warning(ms.formatConsole("console.failed_mark_player_online", MapUtils.mapOf("reason", exception.getMessage())));
      }
    });
    schedulerBridge.runPlayerLater(
        event.getPlayer(),
        40L,
        () -> deliveryService.processPlayerJoin(event.getPlayer()),
        null);
  }
}
