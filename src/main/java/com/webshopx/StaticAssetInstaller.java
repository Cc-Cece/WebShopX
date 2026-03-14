package com.webshopx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

class StaticAssetInstaller {
  private static final List<String> ASSETS = List.of(
      "web/index.html",
      "web/admin.html",
      "web/app.js",
      "web/admin.js",
      "web/styles.css",
      "web/material_zh.txt",
      "web/material_zh.json");

  private final JavaPlugin plugin;

  StaticAssetInstaller(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  Path install(String staticRoot) {
    Path outputRoot = plugin.getDataFolder().toPath().resolve(staticRoot);
    try {
      Files.createDirectories(outputRoot);
      for (String assetPath : ASSETS) {
        copyAsset(assetPath, outputRoot);
      }
      return outputRoot;
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to install static web assets", exception);
    }
  }

  private void copyAsset(String assetPath, Path outputRoot) throws IOException {
    String relative = assetPath.substring("web/".length());
    Path outputFile = outputRoot.resolve(relative);
    Path parent = outputFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    try (InputStream inputStream = plugin.getResource(assetPath)) {
      if (inputStream == null) {
        plugin.getLogger().log(Level.WARNING, "Missing embedded asset: {0}", assetPath);
        return;
      }
      Files.copy(inputStream, outputFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
