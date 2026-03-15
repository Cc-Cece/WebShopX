package com.webshopx;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

class PlayerJoinListener implements Listener {
  private final JavaPlugin plugin;
  private final DeliveryService deliveryService;

  PlayerJoinListener(JavaPlugin plugin, DeliveryService deliveryService) {
    this.plugin = plugin;
    this.deliveryService = deliveryService;
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      deliveryService.processDueDeliveries(event.getPlayer().getUniqueId());
      deliveryService.notifyClaimHint(event.getPlayer());
    }, 40L);
  }
}
