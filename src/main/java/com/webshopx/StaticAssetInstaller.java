package com.webshopx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
      "web/material_zh.json",
      "web/css/light.css",
      "web/css/dark.css",
      "web/css/styles.css",
      "web/js/app.js",
      "web/js/admin.js");

  private final JavaPlugin plugin;

  StaticAssetInstaller(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  Path install(String staticRoot, PluginSettings settings) {
    Path outputRoot = plugin.getDataFolder().toPath().resolve(staticRoot);
    try {
      Files.createDirectories(outputRoot);
      for (String assetPath : ASSETS) {
        copyAsset(assetPath, outputRoot);
      }
      writeRuntimeConfig(outputRoot, settings);
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

  private void writeRuntimeConfig(Path outputRoot, PluginSettings settings) throws IOException {
    Path outputFile = outputRoot.resolve("config.js");
    String script = "window.WEBSHOPX_CONFIG = Object.assign({}, window.WEBSHOPX_CONFIG || {}, {"
        + System.lineSeparator()
        + "  apiBaseUrl: \"" + escapeJs(settings.apiBaseUrl()) + "\","
        + System.lineSeparator()
        + "  serverMode: \"" + settings.serverMode().name() + "\""
        + System.lineSeparator()
        + "});"
        + System.lineSeparator();
    Files.writeString(outputFile, script, StandardCharsets.UTF_8);
  }

  private String escapeJs(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }
}
