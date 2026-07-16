package com.webshopx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;

class StaticAssetInstaller {
  private static final String WEB_ASSET_MANIFEST = "web/assets-manifest.txt";
  private static final String LEGACY_MANAGED_HASH_FILE = "webshopx-custom-assets.properties";

  private static final List<String> OBSOLETE_WEB_ASSETS = List.of(
      "admin.html",
      "help.html",
      "css/light.css",
      "css/dark.css",
      "css/styles.css",
      "js/i18n.js",
      "js/app.js",
      "js/admin.js",
      "js/help.js",
      "vendor/marked.min.js",
      "vendor/purify.min.js",
      "vendor/katex.min.js",
      "vendor/auto-render.min.js",
      "vendor/katex.min.css",
      "material_zh.json",
      "i18n/help/zh-CN.json",
      "i18n/help/en-US.json",
      "docs/help.zh-CN.md",
      "docs/help.en-US.md",
      "docs/manual.zh-CN.md",
      "docs/manual.en-US.md",
      "docs/index.json");

  private final JavaPlugin plugin;

  StaticAssetInstaller(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  Path install(String staticRoot, PluginSettings settings) {
    Path outputRoot = plugin.getDataFolder().toPath().resolve(staticRoot);
    try {
      Files.createDirectories(outputRoot);
      Files.deleteIfExists(plugin.getDataFolder().toPath().resolve(LEGACY_MANAGED_HASH_FILE));
      deleteObsoleteAssets(outputRoot);
      for (String assetPath : loadManagedAssets()) {
        copyAsset(assetPath, outputRoot);
      }
      writeRuntimeConfig(outputRoot, settings);
      return outputRoot;
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to install static web assets", exception);
    }
  }

  private void deleteObsoleteAssets(Path outputRoot) throws IOException {
    for (String relativePath : OBSOLETE_WEB_ASSETS) {
      Files.deleteIfExists(outputRoot.resolve(relativePath));
    }
    for (String relativeDirectory : List.of("css", "js", "vendor", "i18n/help", "docs")) {
      Path directory = outputRoot.resolve(relativeDirectory);
      if (Files.isDirectory(directory)) {
        try (Stream<Path> entries = Files.list(directory)) {
          if (entries.findAny().isEmpty()) {
            Files.deleteIfExists(directory);
          }
        }
      }
    }
  }

  private List<String> loadManagedAssets() throws IOException {
    try (InputStream inputStream = plugin.getResource(WEB_ASSET_MANIFEST)) {
      if (inputStream == null) {
        throw new IOException("Missing embedded web asset manifest: " + WEB_ASSET_MANIFEST);
      }
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::trim)
          .filter(line -> !line.isBlank() && !line.startsWith("#"))
          .map(line -> line.startsWith("web/") ? line : "web/" + line)
          .distinct()
          .sorted()
          .toList();
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
        MessageService ms = new MessageService(plugin, () -> PluginSettings.fromConfig(plugin.getConfig()));
        plugin.getLogger().warning(ms.formatConsole("console.missing_embedded_asset", MapUtils.mapOf("asset", assetPath)));
        return;
      }
      Files.copy(inputStream, outputFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }


  private void writeRuntimeConfig(Path outputRoot, PluginSettings settings) throws IOException {
    Path outputFile = outputRoot.resolve("config.js");
    String platformRuntime = detectPlatformRuntime();
    String minecraftVersion = resolveMinecraftVersion();
    String script = "window.WEBSHOPX_CONFIG = Object.assign({}, window.WEBSHOPX_CONFIG || {}, {"
        + System.lineSeparator()
        + "  apiBaseUrl: \"" + escapeJs(settings.apiBaseUrl()) + "\","
        + System.lineSeparator()
        + "  publicUrl: \"" + escapeJs(settings.embeddedWebSettings().publicUrl()) + "\","
        + System.lineSeparator()
        + "  serverMode: \"" + settings.serverMode().name() + "\","
        + System.lineSeparator()
        + "  defaultLocale: \"" + escapeJs(settings.defaultLocale()) + "\","
        + System.lineSeparator()
        + "  platformRuntime: \"" + escapeJs(platformRuntime) + "\","
        + System.lineSeparator()
        + "  minecraftVersion: \"" + escapeJs(minecraftVersion) + "\","
        + System.lineSeparator()
        + "  leaderboardEnabled: " + settings.leaderboardSettings().enabled() + ","
        + System.lineSeparator()
        + "  leaderboardShowOnlineStatus: " + settings.leaderboardSettings().showOnlineStatus() + ","
        + System.lineSeparator()
        + "  leaderboardDefaultMetric: \"" + settings.leaderboardSettings().defaultMetric().name() + "\"," 
        + System.lineSeparator()
        + "  leaderboardDefaultOrder: \"" + settings.leaderboardSettings().defaultOrder().name() + "\"," 
        + System.lineSeparator()
        + "  supportedLocales: [\"zh-CN\", \"en-US\"]"
        + System.lineSeparator()
        + "});"
        + System.lineSeparator();
    Files.writeString(outputFile, script, StandardCharsets.UTF_8);
  }

  private String detectPlatformRuntime() {
    try {
      Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
      return "folia";
    } catch (Throwable ignored) {
      return "paper";
    }
  }

  private String resolveMinecraftVersion() {
    String version = plugin.getServer().getMinecraftVersion();
    if (version != null && !version.isBlank()) {
      return version.trim();
    }
    String bukkitVersion = plugin.getServer().getBukkitVersion();
    if (bukkitVersion == null || bukkitVersion.isBlank()) {
      return "";
    }
    int separator = bukkitVersion.indexOf('-');
    if (separator <= 0) {
      return bukkitVersion.trim();
    }
    return bukkitVersion.substring(0, separator).trim();
  }

  private String escapeJs(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }

}
