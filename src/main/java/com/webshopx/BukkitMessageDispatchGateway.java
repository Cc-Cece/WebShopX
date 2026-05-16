package com.webshopx;

import com.webshopx.platform.MessageDispatchGateway;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;

final class BukkitMessageDispatchGateway implements MessageDispatchGateway {

  private final JavaPlugin plugin;
  private final MessageService messageService;

  BukkitMessageDispatchGateway(JavaPlugin plugin, MessageService messageService) {
    this.plugin = plugin;
    this.messageService = messageService;
  }

  @Override
  public String resolve(String key, Map<String, String> placeholders) {
    return messageService.formatConsole(key, placeholders == null ? Map.of() : placeholders);
  }

  @Override
  public void info(String key, Map<String, String> placeholders) {
    String message = resolve(key, placeholders);
    String normalized = message == null ? "" : message.trim();
    if (!normalized.isEmpty()) {
      plugin.getLogger().info(normalized);
    }
  }

  @Override
  public void warn(String key, Map<String, String> placeholders) {
    String message = resolve(key, placeholders);
    String normalized = message == null ? "" : message.trim();
    if (!normalized.isEmpty()) {
      plugin.getLogger().warning(normalized);
    }
  }
}
