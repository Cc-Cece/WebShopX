package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Cluster-persistent, Loader-neutral language package center. */
public final class SharedLocaleCenterService {
  public static final int PACKAGE_MAX_BYTES = 20 * 1024 * 1024;
  public static final int ENCODED_PACKAGE_MAX_BYTES = 28 * 1024 * 1024;
  private static final int ENTRY_MAX_BYTES = 2 * 1024 * 1024;
  private static final int ENTRY_LIMIT = 1024;
  private static final String CONFIG_KEY = "locale_center";
  private static final Set<String> BUILT_INS = Set.of("zh-CN", "en-US");
  private static final Set<String> NAMESPACES = Set.of("app", "admin", "help", "market-algorithms");
  private static final Pattern WEB_FILE = Pattern.compile(
      "^web/i18n/([a-z0-9-]+)/([A-Za-z0-9_-]+)\\.json$", Pattern.CASE_INSENSITIVE);
  private static final Pattern GAME_FILE = Pattern.compile(
      "^messages/messages\\.([A-Za-z0-9_-]+)\\.ya?ml$", Pattern.CASE_INSENSITIVE);

  private final SharedRuntimeConfigService config;

  public SharedLocaleCenterService(SharedRuntimeConfigService config) {
    this.config = java.util.Objects.requireNonNull(config, "config");
  }

  public JsonObject listState() {
    return publicState(initializedState());
  }

  public JsonArray listPublicLocales() {
    JsonArray result = new JsonArray();
    for (JsonElement element : locales(initializedState())) {
      JsonObject locale = element.getAsJsonObject();
      if (booleanValue(locale, "webEnabled", false)) result.add(locale.deepCopy());
    }
    return result;
  }

  public JsonObject updateDefaultLocale(String rawLocale) {
    String locale = normalizeLocale(rawLocale);
    JsonObject updated = config.mutate(CONFIG_KEY, state -> {
      initialize(state);
      if (findLocale(state, locale) == null) {
        throw new ServiceException("not_found", "Locale not found");
      }
      state.addProperty("defaultLocale", locale);
      touch(state);
      return state;
    }).config();
    return publicState(updated);
  }

  public JsonObject applyAction(String rawLocale, String rawAction) {
    String locale = normalizeLocale(rawLocale);
    String action = rawAction == null ? "" : rawAction.trim().toLowerCase(Locale.ROOT);
    JsonObject updated = config.mutate(CONFIG_KEY, state -> {
      initialize(state);
      JsonObject target = findLocale(state, locale);
      if (target == null) throw new ServiceException("not_found", "Locale not found");
      switch (action) {
        case "toggleweb" -> target.addProperty(
            "webEnabled", !booleanValue(target, "webEnabled", true));
        case "togglegame" -> target.addProperty(
            "gameEnabled", !booleanValue(target, "gameEnabled", true));
        case "publish" -> target.addProperty(
            "status", "published".equals(stringValue(target, "status")) ? "draft" : "published");
        case "remove" -> removeLocale(state, target, locale);
        default -> throw new ServiceException("bad_request", "Unsupported locale action: " + action);
      }
      target.addProperty("updatedAt", Instant.now().toString());
      touch(state);
      return state;
    }).config();
    return publicState(updated);
  }

  public InstallOutcome installBase64(
      String fileName, String encoded, InstallOptions options) {
    if (options == null) options = new InstallOptions(null, null, null, "upload");
    InstallOptions resolvedOptions = options;
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      throw new ServiceException("bad_request", "Only .zip language package is supported");
    }
    if (encoded == null || encoded.isBlank() || encoded.length() > ENCODED_PACKAGE_MAX_BYTES) {
      throw new ServiceException("bad_request", "Language package is empty or too large");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException failure) {
      throw new ServiceException("bad_request", "contentBase64 is invalid");
    }
    Extracted extracted = extract(bytes);
    JsonObject updated = config.mutate(CONFIG_KEY, state -> {
      initialize(state);
      JsonObject bundles = state.getAsJsonObject("bundles");
      extracted.locales().forEach((locale, bundle) -> {
        bundles.add(locale, bundle.deepCopy());
        JsonObject row = findLocale(state, locale);
        if (row == null) {
          row = new JsonObject();
          locales(state).add(row);
        }
        row.addProperty("locale", locale);
        row.addProperty("name", blankFallback(resolvedOptions.name(), locale));
        row.addProperty("nativeName", blankFallback(resolvedOptions.nativeName(), locale));
        row.addProperty("source", blankFallback(resolvedOptions.source(), "upload"));
        row.addProperty(
            "version", blankFallback(resolvedOptions.version(), "pkg-" + Instant.now()));
        row.addProperty("builtIn", BUILT_INS.contains(locale));
        if (!row.has("status")) row.addProperty("status", "draft");
        if (!row.has("webEnabled")) row.addProperty("webEnabled", false);
        if (!row.has("gameEnabled")) row.addProperty("gameEnabled", false);
        row.addProperty("updatedAt", Instant.now().toString());
      });
      touch(state);
      return state;
    }).config();
    JsonArray changed = new JsonArray();
    extracted.locales().keySet().forEach(locale -> changed.add(findLocale(updated, locale).deepCopy()));
    return new InstallOutcome(publicState(updated), changed, extracted.fileCount());
  }

  public JsonObject readPublicMessages(String rawLocale) {
    String locale = normalizeLocale(rawLocale);
    JsonObject state = initializedState();
    JsonObject row = findLocale(state, locale);
    if (row == null || !booleanValue(row, "webEnabled", false)) {
      throw new ServiceException("not_found", "Locale is not enabled for Web");
    }
    JsonObject bundle = state.getAsJsonObject("bundles").getAsJsonObject(locale);
    if (bundle == null || !bundle.has("web")) return new JsonObject();
    return bundle.getAsJsonObject("web").deepCopy();
  }

  private JsonObject initializedState() {
    JsonObject current = config.read(CONFIG_KEY).config();
    if (current.has("locales") && current.has("bundles")) return current;
    return config.mutate(CONFIG_KEY, state -> {
      initialize(state);
      return state;
    }).config();
  }

  private static void initialize(JsonObject state) {
    if (!state.has("defaultLocale")) state.addProperty("defaultLocale", "zh-CN");
    if (!state.has("lastSyncAt")) state.addProperty("lastSyncAt", Instant.now().toString());
    if (!state.has("locales") || !state.get("locales").isJsonArray()) {
      state.add("locales", new JsonArray());
    }
    if (!state.has("bundles") || !state.get("bundles").isJsonObject()) {
      state.add("bundles", new JsonObject());
    }
    for (String locale : List.of("zh-CN", "en-US")) {
      if (findLocale(state, locale) == null) {
        JsonObject row = new JsonObject();
        row.addProperty("locale", locale);
        row.addProperty("name", locale);
        row.addProperty("nativeName", locale);
        row.addProperty("source", "builtin");
        row.addProperty("version", "builtin");
        row.addProperty("builtIn", true);
        row.addProperty("status", "published");
        row.addProperty("webEnabled", true);
        row.addProperty("gameEnabled", true);
        row.addProperty("updatedAt", Instant.now().toString());
        locales(state).add(row);
      }
    }
  }

  private static Extracted extract(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > PACKAGE_MAX_BYTES) {
      throw new ServiceException("bad_request", "Language package is empty or too large");
    }
    Map<String, JsonObject> bundles = new LinkedHashMap<>();
    int entries = 0;
    int matchedEntries = 0;
    int total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        if (++entries > ENTRY_LIMIT) {
          throw new ServiceException("bad_request", "Language package has too many files");
        }
        String name = entry.getName().replace('\\', '/');
        if (name.startsWith("/") || name.contains("../") || name.contains(":/")) {
          throw new ServiceException("bad_request", "Language package contains an unsafe path");
        }
        byte[] content = readEntry(zip);
        total += content.length;
        if (total > PACKAGE_MAX_BYTES) {
          throw new ServiceException("bad_request", "Expanded language package is too large");
        }
        Matcher web = WEB_FILE.matcher(name);
        Matcher game = GAME_FILE.matcher(name);
        if (web.matches() && NAMESPACES.contains(web.group(1).toLowerCase(Locale.ROOT))) {
          String locale = normalizeLocale(web.group(2));
          JsonElement parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
          if (!parsed.isJsonObject()) {
            throw new ServiceException("bad_request", "Locale JSON must contain an object");
          }
          JsonObject bundle = bundles.computeIfAbsent(locale, ignored -> emptyBundle());
          String namespace = web.group(1).equalsIgnoreCase("market-algorithms")
              ? "marketAlgorithms" : web.group(1).toLowerCase(Locale.ROOT);
          bundle.getAsJsonObject("web").add(namespace, parsed.getAsJsonObject());
          matchedEntries++;
        } else if (game.matches()) {
          String locale = normalizeLocale(game.group(1));
          bundles.computeIfAbsent(locale, ignored -> emptyBundle())
              .addProperty("gameYamlBase64", Base64.getEncoder().encodeToString(content));
          matchedEntries++;
        }
      }
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof ServiceException serviceFailure) throw serviceFailure;
      throw new ServiceException("bad_request", "Language package could not be read");
    }
    if (bundles.isEmpty()) {
      throw new ServiceException("bad_request", "No locale file found in package");
    }
    return new Extracted(Map.copyOf(bundles), matchedEntries);
  }

  private static byte[] readEntry(ZipInputStream zip) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = zip.read(buffer)) >= 0) {
      if (read == 0) continue;
      if (output.size() + read > ENTRY_MAX_BYTES) {
        throw new ServiceException("bad_request", "Language package file is too large");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static JsonObject emptyBundle() {
    JsonObject bundle = new JsonObject();
    bundle.add("web", new JsonObject());
    return bundle;
  }

  private static void removeLocale(JsonObject state, JsonObject target, String locale) {
    if (booleanValue(target, "builtIn", BUILT_INS.contains(locale))) {
      throw new ServiceException("bad_request", "Built-in locale cannot be removed");
    }
    JsonArray next = new JsonArray();
    for (JsonElement element : locales(state)) {
      if (!locale.equals(stringValue(element.getAsJsonObject(), "locale"))) next.add(element);
    }
    state.add("locales", next);
    state.getAsJsonObject("bundles").remove(locale);
    if (locale.equals(stringValue(state, "defaultLocale"))) {
      state.addProperty("defaultLocale", "zh-CN");
    }
  }

  private static JsonObject publicState(JsonObject state) {
    JsonObject copy = state.deepCopy();
    copy.remove("bundles");
    return copy;
  }

  private static JsonArray locales(JsonObject state) {
    return state.getAsJsonArray("locales");
  }

  private static JsonObject findLocale(JsonObject state, String locale) {
    for (JsonElement element : locales(state)) {
      if (locale.equals(stringValue(element.getAsJsonObject(), "locale"))) {
        return element.getAsJsonObject();
      }
    }
    return null;
  }

  private static String normalizeLocale(String raw) {
    String value = raw == null ? "" : raw.trim().replace('_', '-');
    if (!value.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})?")) {
      throw new ServiceException("bad_request", "Invalid locale");
    }
    String[] parts = value.split("-", 2);
    return parts.length == 1
        ? parts[0].toLowerCase(Locale.ROOT)
        : parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toUpperCase(Locale.ROOT);
  }

  private static boolean booleanValue(JsonObject value, String key, boolean fallback) {
    return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsBoolean() : fallback;
  }

  private static String stringValue(JsonObject value, String key) {
    return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
  }

  private static String blankFallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static void touch(JsonObject state) {
    state.addProperty("lastSyncAt", Instant.now().toString());
  }

  public record InstallOptions(String name, String nativeName, String version, String source) {}
  public record InstallOutcome(JsonObject state, JsonArray changed, int fileCount) {}
  private record Extracted(Map<String, JsonObject> locales, int fileCount) {}
}
