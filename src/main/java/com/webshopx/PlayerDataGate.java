package com.webshopx;

import java.time.Duration;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

final class PlayerDataGate implements Listener {
  private final InventoryLockManager lockManager;

  PlayerDataGate(InventoryLockManager lockManager) {
    this.lockManager = lockManager;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void beforeLogin(AsyncPlayerPreLoginEvent event) {
    UUID playerUuid = event.getUniqueId();
    if (!lockManager.isLocked(playerUuid)) {
      return;
    }
    try (InventoryLockManager.Guard ignored =
             lockManager.acquire(playerUuid, Duration.ofSeconds(15))) {
      // Holding and releasing the same lock is the gate: playerdata loading may
      // proceed only after an in-flight offline transaction has committed.
    } catch (ServiceException exception) {
      event.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          "Your inventory is being updated. Please reconnect in a few seconds.");
    }
  }
}
