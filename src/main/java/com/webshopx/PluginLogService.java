package com.webshopx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;

class PluginLogService {
  private final JavaPlugin plugin;
  private Handler fileHandler;

  PluginLogService(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  void apply(PluginSettings.LoggingSettings settings) {
    close();
    if (settings == null || !settings.enabled()) {
      return;
    }

    try {
      Path dir = resolveLogDirectory(settings.directory());
      Files.createDirectories(dir);
      cleanupOldLogs(settings);

      int maxBytes = Math.max(1, settings.maxFileSizeMb()) * 1024 * 1024;
      int maxFiles = Math.max(1, settings.maxFiles());
      String pattern = dir.resolve("webshopx-%g.log").toString();
      FileHandler handler = new FileHandler(pattern, maxBytes, maxFiles, true);
      handler.setFormatter(new SimpleFormatter());
      handler.setLevel(toJdkLevel(settings.level()));

      Logger logger = plugin.getLogger();
      logger.addHandler(handler);
      logger.setLevel(toJdkLevel(settings.level()));
      fileHandler = handler;
      logger.info("File logging enabled at: " + dir.toAbsolutePath());
    } catch (IOException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to initialize file logger", exception);
    }
  }

  void cleanupOldLogs(PluginSettings.LoggingSettings settings) {
    if (settings == null || settings.retentionDays() <= 0) {
      return;
    }
    Path dir = resolveLogDirectory(settings.directory());
    if (!Files.exists(dir)) {
      return;
    }
    Instant cutoff = Instant.now().minus(settings.retentionDays(), ChronoUnit.DAYS);
    try (Stream<Path> stream = Files.list(dir)) {
      stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".log"))
          .forEach(path -> tryDeleteIfExpired(path, cutoff));
    } catch (IOException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to clean old log files", exception);
    }
  }

  void close() {
    if (fileHandler == null) {
      return;
    }
    Logger logger = plugin.getLogger();
    logger.removeHandler(fileHandler);
    fileHandler.close();
    fileHandler = null;
  }

  private Path resolveLogDirectory(String configured) {
    String directory = configured == null || configured.isBlank() ? "logs" : configured.trim();
    return plugin.getDataFolder().toPath().resolve(directory);
  }

  private void tryDeleteIfExpired(Path path, Instant cutoff) {
    try {
      FileTime modifiedAt = Files.getLastModifiedTime(path);
      if (modifiedAt.toInstant().isBefore(cutoff)) {
        Files.deleteIfExists(path);
      }
    } catch (IOException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to delete old log file: " + path, exception);
    }
  }

  private Level toJdkLevel(PluginSettings.LogLevel level) {
    if (level == null) {
      return Level.INFO;
    }
    return switch (level) {
      case ERROR -> Level.SEVERE;
      case WARN -> Level.WARNING;
      case DEBUG -> Level.FINE;
      case TRACE -> Level.FINEST;
      case INFO -> Level.INFO;
    };
  }
}

