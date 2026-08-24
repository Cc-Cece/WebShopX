package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webshopx.platform.PlatformIdentity;
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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Platform-neutral Modrinth release lookup for Paper and Loader distributions. */
public final class SharedUpdateService {
  private static final URI VERSIONS_URI =
      URI.create("https://api.modrinth.com/v2/project/webshopx/version");
  private static final String CHANGELOG_URL = "https://modrinth.com/plugin/webshopx/changelog";
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);
  private static final Duration FAILURE_CACHE_TTL = Duration.ofMinutes(5);
  private static final Pattern RELEASE_VERSION_PATTERN =
      Pattern.compile("(?i)(?:^|[^0-9])(\\d+(?:\\.\\d+){1,3})(?:[^0-9]|$)");

  private final PlatformIdentity identity;
  private final String currentVersion;
  private final HttpClient client;
  private JsonObject cached;
  private Instant cachedAt;

  public SharedUpdateService(PlatformIdentity identity) {
    this(identity, detectedVersion(),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  SharedUpdateService(PlatformIdentity identity, String currentVersion, HttpClient client) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.currentVersion = fallback(currentVersion, "unknown");
    this.client = Objects.requireNonNull(client, "client");
  }

  public synchronized JsonObject state(boolean forceRefresh) {
    Instant now = Instant.now();
    Duration ttl = cached != null && "check_failed".equals(string(cached, "status"))
        ? FAILURE_CACHE_TTL : CACHE_TTL;
    if (!forceRefresh && cached != null && cachedAt != null && now.isBefore(cachedAt.plus(ttl))) {
      return cached.deepCopy();
    }
    try {
      JsonObject result = fetch(now);
      cached = result.deepCopy();
      cachedAt = now;
      return result;
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      if (cached != null) {
        JsonObject stale = cached.deepCopy();
        stale.addProperty("stale", true);
        stale.addProperty("lastError", safeError(failure));
        return stale;
      }
      JsonObject result = base(now);
      result.addProperty("status", "check_failed");
      result.addProperty("lastError", safeError(failure));
      cached = result.deepCopy();
      cachedAt = now;
      return result;
    }
  }

  public JsonObject publicState() {
    JsonObject full = state(false);
    JsonObject result = new JsonObject();
    for (String key : List.of(
        "status", "currentVersion", "latestVersion", "latestName", "publishedAt",
        "checkedAt", "stale", "compatible", "platform", "loader", "minecraftVersion",
        "channel", "modrinthUrl")) {
      if (full.has(key)) result.add(key, full.get(key).deepCopy());
    }
    return result;
  }

  JsonObject evaluate(JsonArray versions, Instant checkedAt) {
    JsonObject result = base(checkedAt);
    String currentRelease = extractReleaseVersion(currentVersion);
    List<JsonObject> releases = new ArrayList<>();
    for (JsonElement element : versions) {
      if (!element.isJsonObject()) continue;
      JsonObject version = element.getAsJsonObject();
      if ("release".equalsIgnoreCase(string(version, "version_type"))
          && !releaseKey(version).isBlank()) releases.add(version);
    }
    if (releases.isEmpty()) {
      result.addProperty("status", "up_to_date");
      return result;
    }
    releases.sort(Comparator
        .comparing((JsonObject row) -> releaseKey(row), SharedUpdateService::compareVersions)
        .reversed().thenComparing(SharedUpdateService::publishedAt, Comparator.reverseOrder()));
    String latestRelease = releaseKey(releases.get(0));
    JsonObject latestPublished = releases.get(0);
    result.addProperty("latestVersion", latestRelease);
    result.addProperty("latestName", fallback(string(latestPublished, "name"), latestRelease));
    result.addProperty("publishedAt", string(latestPublished, "date_published"));
    if (compareVersions(latestRelease, currentRelease) <= 0) {
      result.addProperty("status", "up_to_date");
      return result;
    }
    List<JsonObject> candidates = releases.stream()
        .filter(row -> latestRelease.equals(releaseKey(row))).toList();
    JsonObject compatible = candidates.stream().filter(this::compatible)
        .sorted(Comparator.comparing(SharedUpdateService::publishedAt).reversed())
        .findFirst().orElse(null);
    JsonObject chosen = compatible == null ? candidates.get(0) : compatible;
    result.addProperty("status", compatible == null
        ? "no_compatible_build" : "update_available");
    result.addProperty("latestName", fallback(string(chosen, "name"), latestRelease));
    result.addProperty("publishedAt", string(chosen, "date_published"));
    result.addProperty("releaseType", string(chosen, "version_type"));
    result.addProperty("changelog", string(chosen, "changelog"));
    result.addProperty("compatible", compatible != null);
    result.add("gameVersions", array(chosen, "game_versions"));
    result.add("loaders", array(chosen, "loaders"));
    if (compatible != null) {
      JsonObject file = primaryFile(compatible);
      if (file != null) {
        result.addProperty("fileName", string(file, "filename"));
        result.addProperty("downloadUrl", string(file, "url"));
      }
    }
    return result;
  }

  private JsonObject fetch(Instant checkedAt) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(VERSIONS_URI)
        .timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
        .header("User-Agent", "WebShopX/" + currentVersion).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Modrinth returned HTTP " + response.statusCode());
    }
    JsonElement parsed = JsonParser.parseString(response.body());
    if (!parsed.isJsonArray()) throw new IOException("Modrinth returned an invalid version list");
    return evaluate(parsed.getAsJsonArray(), checkedAt);
  }

  private JsonObject base(Instant checkedAt) {
    JsonObject result = new JsonObject();
    result.addProperty("status", "unknown");
    result.addProperty("currentVersion", currentVersion);
    result.addProperty("latestVersion", "");
    result.addProperty("latestName", "");
    result.addProperty("checkedAt", checkedAt.toString());
    result.addProperty("stale", false);
    result.addProperty("compatible", false);
    result.addProperty("platform", identity.platform());
    result.addProperty("loader", identity.loader());
    result.addProperty("minecraftVersion", identity.minecraftVersion());
    result.addProperty("channel", "release");
    result.addProperty("modrinthUrl", CHANGELOG_URL);
    result.addProperty("downloadUrl", "");
    result.addProperty("fileName", "");
    result.addProperty("changelog", "");
    result.addProperty("publishedAt", "");
    result.add("gameVersions", new JsonArray());
    result.add("loaders", new JsonArray());
    return result;
  }

  private boolean compatible(JsonObject version) {
    if (!contains(array(version, "game_versions"), identity.minecraftVersion())) return false;
    JsonArray loaders = array(version, "loaders");
    String loader = identity.loader().toLowerCase(Locale.ROOT);
    if (loader.equals("paper") || loader.equals("purpur") || loader.equals("spigot")) {
      return containsAny(loaders, List.of("paper", "purpur", "spigot", "bukkit"));
    }
    if (loader.equals("folia")) return contains(loaders, "folia");
    return contains(loaders, loader);
  }

  public static String extractReleaseVersion(String raw) {
    Matcher matcher = RELEASE_VERSION_PATTERN.matcher(String.valueOf(raw == null ? "" : raw));
    return matcher.find() ? matcher.group(1) : "0.0.0";
  }

  public static int compareVersions(String left, String right) {
    String[] leftParts = extractReleaseVersion(left).split("\\.");
    String[] rightParts = extractReleaseVersion(right).split("\\.");
    int length = Math.max(leftParts.length, rightParts.length);
    for (int index = 0; index < length; index++) {
      int leftValue = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
      int rightValue = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
      int compared = Integer.compare(leftValue, rightValue);
      if (compared != 0) return compared;
    }
    return 0;
  }

  private static String detectedVersion() {
    String configured = System.getProperty("webshopx.version", "");
    if (!configured.isBlank()) return configured;
    Package source = SharedUpdateService.class.getPackage();
    return source == null ? "unknown" : fallback(source.getImplementationVersion(), "unknown");
  }

  private static String releaseKey(JsonObject version) {
    return extractReleaseVersion(fallback(string(version, "version_number"), string(version, "name")));
  }

  private static Instant publishedAt(JsonObject version) {
    try {
      return Instant.parse(string(version, "date_published"));
    } catch (Exception ignored) {
      return Instant.EPOCH;
    }
  }

  private static JsonObject primaryFile(JsonObject version) {
    JsonObject fallback = null;
    for (JsonElement element : array(version, "files")) {
      if (!element.isJsonObject()) continue;
      JsonObject file = element.getAsJsonObject();
      if (string(file, "url").isBlank()) continue;
      if (fallback == null) fallback = file;
      if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
    }
    return fallback;
  }

  private static JsonArray array(JsonObject source, String key) {
    return source.has(key) && source.get(key).isJsonArray()
        ? source.getAsJsonArray(key).deepCopy() : new JsonArray();
  }

  private static boolean containsAny(JsonArray array, List<String> values) {
    return values.stream().anyMatch(value -> contains(array, value));
  }

  private static boolean contains(JsonArray array, String expected) {
    for (JsonElement element : array) {
      if (element.isJsonPrimitive() && expected.equalsIgnoreCase(element.getAsString())) return true;
    }
    return false;
  }

  private static String string(JsonObject object, String key) {
    return object != null && object.has(key) && object.get(key).isJsonPrimitive()
        ? object.get(key).getAsString().trim() : "";
  }

  private static String fallback(String value, String replacement) {
    return value == null || value.isBlank() ? replacement : value;
  }

  private static String safeError(Exception failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }
}
