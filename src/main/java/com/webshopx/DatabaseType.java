package com.webshopx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.bukkit.plugin.java.JavaPlugin;

enum DatabaseType {
  MARIADB,
  SQLITE;

  static DatabaseType detect(JavaPlugin plugin) {
    try (InputStream inputStream = plugin.getResource("database-provider.txt")) {
      if (inputStream == null) {
        return MARIADB;
      }
      return fromRaw(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to read bundled database provider", exception);
    }
  }

  static DatabaseType fromRaw(String raw) {
    String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if ("sqlite".equals(normalized)) {
      return SQLITE;
    }
    return MARIADB;
  }
}
