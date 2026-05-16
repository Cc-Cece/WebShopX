package com.webshopx;

import com.webshopx.platform.PlayerContextGateway;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class BukkitPlayerContextGateway implements PlayerContextGateway {

  @Override
  public <T> T supplyPlayer(UUID playerId, Function<PlayerHandle, T> task) {
    Player player = Bukkit.getPlayer(playerId);
    return task.apply(new BukkitPlayerHandle(playerId, player));
  }

  private record BukkitPlayerHandle(UUID uniqueId, Player player) implements PlayerHandle {
    @Override
    public boolean online() {
      return player != null && player.isOnline();
    }
  }
}
