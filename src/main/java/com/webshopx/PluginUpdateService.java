package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

final class PluginUpdateService {
  private static final URI MODRINTH_VERSIONS_URI =
      URI.create("https://api.modrinth.com/v2/project/webshopx/version");
  private static final String MODRINTH_CHANGELOG_URL =
      "https://modrinth.com/plugin/webshopx/changelog";
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);
  private static final Duration FAILURE_CACHE_TTL = Duration.ofMinutes(5);
  private static final Pattern RELEASE_VERSION_PATTERN =
      Pattern.compile("(?i)(?:^|[^0-9])(\\d+(?:\\.\\d+){1,3})(?:[^0-9]|$)");

  private final JavaPlugin plugin;
  private final HttpClient httpClient;
  private final Gson gson;
  private JsonObject cachedResult;
  private Instant cachedAt;

  PluginUpdateService(JavaPlugin plugin) {
    this(
        plugin,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        new Gson());
  }

  PluginUpdateService(JavaPlugin plugin, HttpClient httpClient, Gson gson) {
    this.plugin = plugin;
    this.httpClient = httpClient;
    this.gson = gson;
  }

  synchronized JsonObject getUpdateState(boolean forceRefresh) {
    Instant now = Instant.now();
    Duration cacheTtl = cachedResult != null
        && "check_failed".equals(string(cachedResult, "status")) ? FAILURE_CACHE_TTL : CACHE_TTL;
    if (!forceRefresh && cachedResult != null && cachedAt != null
        && now.isBefore(cachedAt.plus(cacheTtl))) {
      return copy(cachedResult);
    }

    try {
      JsonObject result = fetchAndEvaluate(now);
      cachedResult = copy(result);
      cachedAt = now;
      return result;
    } catch (Exception exception) {
      plugin.getLogger().warning("Failed to check WebShopX updates: " + exception.getMessage());
      if (cachedResult != null) {
        JsonObject stale = copy(cachedResult);
        stale.addProperty("stale", true);
        stale.addProperty("lastError", safeError(exception));
        return stale;
      }
      JsonObject failed = baseResult(now);
      failed.addProperty("status", "check_failed");
      failed.addProperty("lastError", safeError(exception));
      cachedResult = copy(failed);
      cachedAt = now;
      return failed;
    }
  }

  JsonObject publicState() {
    JsonObject full = getUpdateState(false);
    JsonObject result = new JsonObject();
    copyProperty(full, result, "status");
    copyProperty(full, result, "currentVersion");
    copyProperty(full, result, "latestVersion");
    copyProperty(full, result, "latestName");
    copyProperty(full, result, "publishedAt");
    copyProperty(full, result, "checkedAt");
    copyProperty(full, result, "stale");
    result.addProperty("modrinthUrl", MODRINTH_CHANGELOG_URL);
    return result;
  }

  private JsonObject fetchAndEvaluate(Instant checkedAt) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(MODRINTH_VERSIONS_URI)
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("User-Agent", "WebShopX/" + currentVersion())
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Modrinth returned HTTP " + response.statusCode());
    }
    JsonElement parsed = JsonParser.parseString(response.body());
    if (!parsed.isJsonArray()) {
      throw new IOException("Modrinth returned an invalid version list");
    }
    return evaluateVersions(parsed.getAsJsonArray(), checkedAt);
  }

  JsonObject evaluateVersions(JsonArray versions, Instant checkedAt) {
    JsonObject result = baseResult(checkedAt);
    String currentRelease = extractReleaseVersion(currentVersion());
    List<JsonObject> releases = new ArrayList<>();
    for (JsonElement element : versions) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject version = element.getAsJsonObject();
      if (!"release".equalsIgnoreCase(string(version, "version_type"))) {
        continue;
      }
      String release = releaseKey(version);
      if (!release.isBlank()) {
        releases.add(version);
      }
    }
    if (releases.isEmpty()) {
      result.addProperty("status", "up_to_date");
      return result;
    }

    releases.sort(Comparator
        .comparing((JsonObject row) -> releaseKey(row), PluginUpdateService::compareReleaseVersions)
        .reversed()
        .thenComparing(PluginUpdateService::publishedAt, Comparator.reverseOrder()));
    String latestRelease = releaseKey(releases.get(0));
    JsonObject latestPublished = releases.get(0);
    result.addProperty("latestVersion", latestRelease);
    result.addProperty("latestName", fallback(string(latestPublished, "name"), latestRelease));
    result.addProperty("publishedAt", string(latestPublished, "date_published"));
    result.addProperty("releaseType", string(latestPublished, "version_type"));
    if (compareReleaseVersions(latestRelease, currentRelease) <= 0) {
      result.addProperty("status", "up_to_date");
      return result;
    }

    List<JsonObject> latestCandidates = releases.stream()
        .filter(row -> latestRelease.equals(releaseKey(row)))
        .toList();
    JsonObject compatible = latestCandidates.stream()
        .filter(this::isRuntimeCompatible)
        .sorted(Comparator.comparing(PluginUpdateService::publishedAt).reversed())
        .findFirst()
        .orElse(null);
    JsonObject chosen = compatible == null ? latestCandidates.get(0) : compatible;

    result.addProperty("status", compatible == null ? "no_compatible_build" : "update_available");
    result.addProperty("latestVersion", latestRelease);
    result.addProperty("latestName", fallback(string(chosen, "name"), latestRelease));
    result.addProperty("publishedAt", string(chosen, "date_published"));
    result.addProperty("releaseType", string(chosen, "version_type"));
    result.addProperty("changelog", string(chosen, "changelog"));
    result.addProperty("modrinthUrl", MODRINTH_CHANGELOG_URL);
    result.addProperty("compatible", compatible != null);
    result.add("gameVersions", arrayCopy(chosen, "game_versions"));
    result.add("loaders", arrayCopy(chosen, "loaders"));
    if (compatible != null) {
      JsonObject file = primaryFile(compatible);
      if (file != null) {
        result.addProperty("fileName", string(file, "filename"));
        result.addProperty("downloadUrl", string(file, "url"));
      }
    }
    return result;
  }

  private JsonObject baseResult(Instant checkedAt) {
    RuntimeContext runtime = runtimeContext(plugin.getServer());
    JsonObject result = new JsonObject();
    result.addProperty("status", "unknown");
    result.addProperty("currentVersion", currentVersion());
    result.addProperty("latestVersion", "");
    result.addProperty("latestName", "");
    result.addProperty("checkedAt", checkedAt.toString());
    result.addProperty("stale", false);
    result.addProperty("compatible", false);
    result.addProperty("platform", runtime.platform());
    result.addProperty("loader", runtime.loader());
    result.addProperty("minecraftVersion", runtime.minecraftVersion());
    result.addProperty("channel", "release");
    result.addProperty("modrinthUrl", MODRINTH_CHANGELOG_URL);
    result.addProperty("downloadUrl", "");
    result.addProperty("fileName", "");
    result.addProperty("changelog", "");
    result.addProperty("publishedAt", "");
    result.add("gameVersions", new JsonArray());
    result.add("loaders", new JsonArray());
    return result;
  }

  private boolean isRuntimeCompatible(JsonObject version) {
    RuntimeContext runtime = runtimeContext(plugin.getServer());
    JsonArray gameVersions = version.has("game_versions")
        && version.get("game_versions").isJsonArray()
        ? version.getAsJsonArray("game_versions") : new JsonArray();
    boolean gameMatches = containsIgnoreCase(gameVersions, runtime.minecraftVersion());
    JsonArray loaders = version.has("loaders") && version.get("loaders").isJsonArray()
        ? version.getAsJsonArray("loaders") : new JsonArray();
    boolean loaderMatches = "folia".equals(runtime.loader())
        ? containsIgnoreCase(loaders, "folia")
        : containsAnyIgnoreCase(loaders, List.of("paper", "purpur", "spigot", "bukkit"));
    return gameMatches && loaderMatches;
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
    Matcher matcher = RELEASE_VERSION_PATTERN.matcher(String.valueOf(raw == null ? "" : raw));
    return matcher.find() ? matcher.group(1) : "0.0.0";
  }

  static int compareReleaseVersions(String left, String right) {
    String[] leftParts = extractReleaseVersion(left).split("\\.");
    String[] rightParts = extractReleaseVersion(right).split("\\.");
    int length = Math.max(leftParts.length, rightParts.length);
    for (int index = 0; index < length; index++) {
      int leftValue = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
      int rightValue = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
      int compared = Integer.compare(leftValue, rightValue);
      if (compared != 0) {
        return compared;
      }
    }
    return 0;
  }

  private String currentVersion() {
    return fallback(plugin.getDescription().getVersion(), "unknown");
  }

  private static String releaseKey(JsonObject version) {
    String number = fallback(string(version, "version_number"), string(version, "name"));
    return extractReleaseVersion(number);
  }

  private static Instant publishedAt(JsonObject version) {
    try {
      return Instant.parse(string(version, "date_published"));
    } catch (Exception ignored) {
      return Instant.EPOCH;
    }
  }

  private static JsonObject primaryFile(JsonObject version) {
    if (!version.has("files") || !version.get("files").isJsonArray()) {
      return null;
    }
    JsonObject fallback = null;
    for (JsonElement element : version.getAsJsonArray("files")) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject file = element.getAsJsonObject();
      if (string(file, "url").isBlank()) {
        continue;
      }
      if (fallback == null) {
        fallback = file;
      }
      if (file.has("primary") && file.get("primary").getAsBoolean()) {
        return file;
      }
    }
    return fallback;
  }

  private static JsonArray arrayCopy(JsonObject source, String key) {
    return source.has(key) && source.get(key).isJsonArray()
        ? source.getAsJsonArray(key).deepCopy() : new JsonArray();
  }

  private static boolean containsAnyIgnoreCase(JsonArray array, List<String> values) {
    return values.stream().anyMatch(value -> containsIgnoreCase(array, value));
  }

  private static boolean containsIgnoreCase(JsonArray array, String expected) {
    if (array == null || expected == null || expected.isBlank()) {
      return false;
    }
    for (JsonElement element : array) {
      if (element.isJsonPrimitive() && expected.equalsIgnoreCase(element.getAsString())) {
        return true;
      }
    }
    return false;
  }

  private static String string(JsonObject object, String key) {
    return object != null && object.has(key) && object.get(key).isJsonPrimitive()
        ? object.get(key).getAsString().trim() : "";
  }

  private static String fallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String safeError(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  private static void copyProperty(JsonObject source, JsonObject target, String key) {
    if (source.has(key)) {
      target.add(key, source.get(key).deepCopy());
    }
  }

  private JsonObject copy(JsonObject source) {
    return gson.fromJson(gson.toJson(source), JsonObject.class);
  }

  record RuntimeContext(String platform, String loader, String minecraftVersion) {}
}
