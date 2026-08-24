package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.webshopx.platform.PlatformIdentity;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper adapter for the platform-neutral release lookup use case. */
final class PluginUpdateService {
  private final JavaPlugin plugin;
  private final SharedUpdateService shared;

  PluginUpdateService(JavaPlugin plugin) {
    this(
        plugin,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        new Gson());
  }

  PluginUpdateService(JavaPlugin plugin, HttpClient httpClient, Gson ignoredGson) {
    this.plugin = plugin;
    RuntimeContext runtime = runtimeContext(plugin.getServer());
    shared = new SharedUpdateService(
        new PlatformIdentity(
            runtime.platform(),
            runtime.loader(),
            runtime.minecraftVersion(),
            plugin.getServer().getVersion(),
            plugin.getName(),
            "paper-runtime"),
        currentVersion(),
        httpClient);
  }

  synchronized JsonObject getUpdateState(boolean forceRefresh) {
    return shared.state(forceRefresh);
  }

  JsonObject publicState() {
    return shared.publicState();
  }

  JsonObject evaluateVersions(JsonArray versions, Instant checkedAt) {
    return shared.evaluate(versions, checkedAt);
  }

  static RuntimeContext runtimeContext(Server server) {
    String name = server.getName().trim();
    String version = server.getMinecraftVersion().trim();
    String normalized = name.toLowerCase(Locale.ROOT);
    String loader = normalized.contains("folia") ? "folia" : "paper";
    String platform = normalized.contains("purpur") ? "purpur"
        : normalized.contains("folia") ? "folia"
        : normalized.contains("paper") ? "paper"
        : normalized.contains("spigot") ? "spigot"
        : normalized.contains("bukkit") ? "bukkit"
        : fallback(normalized, "unknown");
    return new RuntimeContext(platform, loader, version);
  }

  static String extractReleaseVersion(String raw) {
    return SharedUpdateService.extractReleaseVersion(raw);
  }

  static int compareReleaseVersions(String left, String right) {
    return SharedUpdateService.compareVersions(left, right);
  }

  private String currentVersion() {
    return fallback(plugin.getDescription().getVersion(), "unknown");
  }

  private static String fallback(String value, String replacement) {
    return value == null || value.isBlank() ? replacement : value;
  }

  record RuntimeContext(String platform, String loader, String minecraftVersion) {}
}
