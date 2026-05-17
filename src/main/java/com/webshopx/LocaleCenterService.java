package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.bukkit.plugin.java.JavaPlugin;

class LocaleCenterService {
  private static final int PACKAGE_MAX_BYTES = 20 * 1024 * 1024;
  private static final int ENTRY_MAX_BYTES = 2 * 1024 * 1024;
  private static final int ENTRY_LIMIT = 1024;
  private static final Pattern MESSAGE_FILE_PATTERN =
      Pattern.compile("^messages/messages\\.([A-Za-z0-9_-]+)\\.ya?ml$", Pattern.CASE_INSENSITIVE);
  private static final Pattern WEB_FILE_PATTERN =
      Pattern.compile("^web/i18n/([a-z0-9-]+)/([A-Za-z0-9_-]+)\\.json$", Pattern.CASE_INSENSITIVE);
  private static final Set<String> BUILTIN_LOCALES = Set.of("zh-CN", "en-US");
  private static final Set<String> ALLOWED_NAMESPACES =
      Set.of("app", "admin", "help", "market-algorithms", "materials");

  private final JavaPlugin plugin;
  private final Supplier<Path> userWebRootSupplier;
  private final Gson gson;

  LocaleCenterService(JavaPlugin plugin, Supplier<Path> userWebRootSupplier) {
    this.plugin = plugin;
    this.userWebRootSupplier = userWebRootSupplier;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  synchronized JsonObject listState() {
    return copyJsonObject(readState());
  }

  synchronized JsonObject updateDefaultLocale(String rawLocale) {
    JsonObject state = readState();
    String locale = normalizeLocale(rawLocale);
    if (locale.isBlank()) {
      throw new ServiceException("bad_request", "Invalid locale: defaultLocale");
    }
    state.addProperty("defaultLocale", locale);
    saveState(state);
    return copyJsonObject(state);
  }

  synchronized JsonObject applyLocaleAction(String rawLocale, String rawAction) {
    String locale = normalizeLocale(rawLocale);
    String action = String.valueOf(rawAction == null ? "" : rawAction).trim().toLowerCase(Locale.ROOT);
    if (locale.isBlank() || action.isBlank()) {
      throw new ServiceException("bad_request", "Missing locale action payload");
    }

    JsonObject state = readState();
    JsonArray locales = ensureLocaleArray(state);
    JsonObject target = null;
    for (JsonElement element : locales) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      if (locale.equals(normalizeLocale(getAsString(row, "locale")))) {
        target = row;
        break;
      }
    }
    if (target == null) {
      throw new ServiceException("not_found", "Locale not found");
    }

    boolean builtIn = getAsBoolean(target, "builtIn", BUILTIN_LOCALES.contains(locale));
    switch (action) {
      case "toggleweb" -> target.addProperty("webEnabled", !getAsBoolean(target, "webEnabled", true));
      case "togglegame" -> target.addProperty("gameEnabled", !getAsBoolean(target, "gameEnabled", true));
      case "publish" -> {
        String status = getAsString(target, "status");
        target.addProperty("status", "published".equalsIgnoreCase(status) ? "draft" : "published");
      }
      case "remove" -> {
        if (builtIn) {
          throw new ServiceException("bad_request", "Built-in locale cannot be removed");
        }
        JsonArray next = new JsonArray();
        for (JsonElement element : locales) {
          if (!element.isJsonObject()) {
            continue;
          }
          JsonObject row = element.getAsJsonObject();
          if (!locale.equals(normalizeLocale(getAsString(row, "locale")))) {
            next.add(row);
          }
        }
        state.add("locales", next);
        if (locale.equals(normalizeLocale(getAsString(state, "defaultLocale")))) {
          state.addProperty("defaultLocale", "zh-CN");
        }
        state.addProperty("lastSyncAt", Instant.now().toString());
        saveState(state);
        return copyJsonObject(state);
      }
      default -> throw new ServiceException("bad_request", "Unsupported locale action: " + action);
    }

    target.addProperty("updatedAt", Instant.now().toString());
    state.addProperty("lastSyncAt", Instant.now().toString());
    saveState(state);
    return copyJsonObject(state);
  }

  synchronized InstallOutcome installBase64Package(
      String fileName,
      String contentBase64,
      InstallOptions options) {
    if (contentBase64 == null || contentBase64.isBlank()) {
      throw new ServiceException("bad_request", "Missing field: contentBase64");
    }
    String normalizedFileName = String.valueOf(fileName == null ? "" : fileName).trim();
    if (!normalizedFileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      throw new ServiceException("bad_request", "Only .zip language package is supported");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(contentBase64);
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("bad_request", "contentBase64 is invalid");
    }
    return installZipPackage(bytes, options);
  }

  synchronized InstallOutcome installZipPackage(byte[] zipBytes, InstallOptions options) {
    if (zipBytes == null || zipBytes.length == 0) {
      throw new ServiceException("bad_request", "Language package is empty");
    }
    if (zipBytes.length > PACKAGE_MAX_BYTES) {
      throw new ServiceException("bad_request", "Language package is too large");
    }

    ExtractedPackage extracted = extractPackage(zipBytes);
    if (extracted.localeMap().isEmpty()) {
      throw new ServiceException("bad_request", "No locale file found in package");
    }

    JsonObject state = readState();
    JsonArray locales = ensureLocaleArray(state);
    List<JsonObject> changed = new ArrayList<>();

    for (Map.Entry<String, ExtractedLocale> entry : extracted.localeMap().entrySet()) {
      String locale = entry.getKey();
      ExtractedLocale data = entry.getValue();
      writeLocaleFiles(locale, data);

      JsonObject existing = findLocale(locales, locale);
      JsonObject next = existing == null ? new JsonObject() : existing;
      boolean builtIn = getAsBoolean(next, "builtIn", BUILTIN_LOCALES.contains(locale));

      String name = options.name();
      if (name == null || name.isBlank()) {
        name = locale;
      }
      String nativeName = String.valueOf(options.nativeName() == null ? "" : options.nativeName()).trim();
      String version = String.valueOf(options.version() == null ? "" : options.version()).trim();
      if (version.isBlank()) {
        version = "pkg-" + Instant.now().toString();
      }
      String source = String.valueOf(options.source() == null ? "upload" : options.source()).trim().toLowerCase(Locale.ROOT);
      if (source.isBlank()) {
        source = "upload";
      }

      next.addProperty("locale", locale);
      next.addProperty("name", name);
      next.addProperty("nativeName", nativeName);
      next.addProperty("source", source);
      next.addProperty("version", version);
      next.addProperty("builtIn", builtIn);
      next.addProperty("status", getAsString(next, "status").isBlank() ? "draft" : getAsString(next, "status"));
      if (!next.has("webEnabled")) {
        next.addProperty("webEnabled", false);
      }
      if (!next.has("gameEnabled")) {
        next.addProperty("gameEnabled", false);
      }
      next.addProperty("updatedAt", Instant.now().toString());

      if (existing == null) {
        locales.add(next);
      }
      changed.add(copyJsonObject(next));
    }

    state.addProperty("lastSyncAt", Instant.now().toString());
    saveState(state);
    return new InstallOutcome(copyJsonObject(state), changed, extracted.fileCount());
  }

  synchronized JsonArray listPublicWebLocales() {
    JsonObject state = readState();
    JsonArray locales = ensureLocaleArray(state);
    Map<String, JsonObject> result = new LinkedHashMap<>();

    for (JsonElement element : locales) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      String locale = normalizeLocale(getAsString(row, "locale"));
      if (locale.isBlank()) {
        continue;
      }
      boolean webEnabled = getAsBoolean(row, "webEnabled", true);
      if (!webEnabled) {
        continue;
      }
      result.put(locale, localeEntryJson(row, locale));
    }

    for (String builtIn : BUILTIN_LOCALES) {
      result.putIfAbsent(builtIn, builtinEntryJson(builtIn));
    }

    JsonArray array = new JsonArray();
    result.values().forEach(array::add);
    return array;
  }

  private JsonObject localeEntryJson(JsonObject source, String locale) {
    JsonObject row = new JsonObject();
    row.addProperty("locale", locale);
    row.addProperty("name", defaultIfBlank(getAsString(source, "name"), defaultNameFor(locale)));
    row.addProperty("nativeName", defaultIfBlank(getAsString(source, "nativeName"), defaultNativeNameFor(locale)));
    row.addProperty("source", defaultIfBlank(getAsString(source, "source"), BUILTIN_LOCALES.contains(locale) ? "built-in" : "upload"));
    return row;
  }

  private JsonObject builtinEntryJson(String locale) {
    JsonObject row = new JsonObject();
    row.addProperty("locale", locale);
    row.addProperty("name", defaultNameFor(locale));
    row.addProperty("nativeName", defaultNativeNameFor(locale));
    row.addProperty("source", "built-in");
    return row;
  }

  private ExtractedPackage extractPackage(byte[] zipBytes) {
    Map<String, ExtractedLocale> localeMap = new LinkedHashMap<>();
    int files = 0;

    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      int scanned = 0;
      while ((entry = zis.getNextEntry()) != null) {
        scanned += 1;
        if (scanned > ENTRY_LIMIT) {
          throw new ServiceException("bad_request", "Language package contains too many entries");
        }
        if (entry.isDirectory()) {
          continue;
        }

        String normalized = normalizeEntryName(entry.getName());
        if (normalized.isEmpty() || normalized.contains("..")) {
          continue;
        }

        byte[] content = readZipEntry(zis, ENTRY_MAX_BYTES);
        Matcher messageMatcher = MESSAGE_FILE_PATTERN.matcher(normalized);
        if (messageMatcher.matches()) {
          String locale = normalizeLocale(messageMatcher.group(1));
          if (locale.isBlank()) {
            continue;
          }
          ExtractedLocale localeEntry = localeMap.computeIfAbsent(locale, ignored -> new ExtractedLocale());
          localeEntry.messageFile = content;
          files += 1;
          continue;
        }

        Matcher webMatcher = WEB_FILE_PATTERN.matcher(normalized);
        if (webMatcher.matches()) {
          String namespace = String.valueOf(webMatcher.group(1)).toLowerCase(Locale.ROOT);
          if (!ALLOWED_NAMESPACES.contains(namespace)) {
            continue;
          }
          String locale = normalizeLocale(webMatcher.group(2));
          if (locale.isBlank()) {
            continue;
          }
          ExtractedLocale localeEntry = localeMap.computeIfAbsent(locale, ignored -> new ExtractedLocale());
          localeEntry.webFiles.put(namespace, content);
          files += 1;
        }
      }
    } catch (IOException exception) {
      throw new ServiceException("bad_request", "Failed to read language package");
    }

    return new ExtractedPackage(localeMap, files);
  }

  private byte[] readZipEntry(ZipInputStream zis, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = zis.read(buffer)) > 0) {
      total += read;
      if (total > maxBytes) {
        throw new ServiceException("bad_request", "Language package entry is too large");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private void writeLocaleFiles(String locale, ExtractedLocale data) {
    try {
      if (data.messageFile != null && data.messageFile.length > 0) {
        Path target = plugin.getDataFolder().toPath().resolve("messages").resolve("messages." + locale + ".yml");
        Files.createDirectories(target.getParent());
        Files.write(target, data.messageFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      }

      if (!data.webFiles.isEmpty()) {
        Path userWebRoot = userWebRootSupplier.get();
        if (userWebRoot == null) {
          throw new ServiceException("internal_error", "User web root is not initialized");
        }
        for (Map.Entry<String, byte[]> entry : data.webFiles.entrySet()) {
          Path target = userWebRoot.resolve("i18n")
              .resolve(entry.getKey())
              .resolve(locale + ".json")
              .normalize();
          if (!target.startsWith(userWebRoot)) {
            throw new ServiceException("bad_request", "Invalid locale file path");
          }
          Files.createDirectories(target.getParent());
          Files.write(target, entry.getValue(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
      }
    } catch (IOException exception) {
      throw new ServiceException("internal_error", "Failed to write locale files");
    }
  }

  private String normalizeEntryName(String value) {
    String normalized = String.valueOf(value == null ? "" : value).replace('\\', '/').trim();
    while (normalized.startsWith("./")) {
      normalized = normalized.substring(2);
    }
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  private JsonObject findLocale(JsonArray array, String locale) {
    for (JsonElement element : array) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      if (locale.equals(normalizeLocale(getAsString(row, "locale")))) {
        return row;
      }
    }
    return null;
  }

  private JsonObject readState() {
    JsonObject state = loadStateFromDisk();
    ensureBuiltinLocales(state);
    return state;
  }

  private JsonObject loadStateFromDisk() {
    Path stateFile = stateFile();
    if (!Files.exists(stateFile)) {
      JsonObject state = defaultState();
      saveState(state);
      return state;
    }
    try (Reader reader = new InputStreamReader(Files.newInputStream(stateFile), StandardCharsets.UTF_8)) {
      JsonElement parsed = JsonParser.parseReader(reader);
      if (!parsed.isJsonObject()) {
        JsonObject state = defaultState();
        saveState(state);
        return state;
      }
      JsonObject state = parsed.getAsJsonObject();
      if (!state.has("defaultLocale") || normalizeLocale(getAsString(state, "defaultLocale")).isBlank()) {
        state.addProperty("defaultLocale", "zh-CN");
      }
      if (!state.has("locales") || !state.get("locales").isJsonArray()) {
        state.add("locales", new JsonArray());
      }
      return state;
    } catch (Exception exception) {
      JsonObject state = defaultState();
      saveState(state);
      return state;
    }
  }

  private void saveState(JsonObject state) {
    Path stateFile = stateFile();
    try {
      Files.createDirectories(stateFile.getParent());
      byte[] data = gson.toJson(state).getBytes(StandardCharsets.UTF_8);
      Files.write(stateFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (IOException exception) {
      throw new ServiceException("internal_error", "Failed to persist locale state");
    }
  }

  private Path stateFile() {
    return plugin.getDataFolder().toPath().resolve("l10n").resolve("locales.json");
  }

  private JsonObject defaultState() {
    JsonObject state = new JsonObject();
    state.addProperty("defaultLocale", "zh-CN");
    state.addProperty("lastSyncAt", (String) null);
    JsonArray locales = new JsonArray();
    locales.add(defaultRecord("zh-CN", true));
    locales.add(defaultRecord("en-US", true));
    state.add("locales", locales);
    return state;
  }

  private JsonObject defaultRecord(String locale, boolean builtIn) {
    JsonObject row = new JsonObject();
    row.addProperty("locale", locale);
    row.addProperty("name", defaultNameFor(locale));
    row.addProperty("nativeName", defaultNativeNameFor(locale));
    row.addProperty("source", builtIn ? "built-in" : "upload");
    row.addProperty("version", builtIn ? "builtin-1" : "draft");
    row.addProperty("status", "published");
    row.addProperty("webEnabled", true);
    row.addProperty("gameEnabled", true);
    row.addProperty("builtIn", builtIn);
    row.addProperty("updatedAt", Instant.now().toString());
    return row;
  }

  private void ensureBuiltinLocales(JsonObject state) {
    JsonArray locales = ensureLocaleArray(state);
    for (String locale : BUILTIN_LOCALES) {
      JsonObject row = findLocale(locales, locale);
      if (row == null) {
        locales.add(defaultRecord(locale, true));
      } else {
        row.addProperty("builtIn", true);
        if (getAsString(row, "status").isBlank()) {
          row.addProperty("status", "published");
        }
        if (!row.has("webEnabled")) {
          row.addProperty("webEnabled", true);
        }
        if (!row.has("gameEnabled")) {
          row.addProperty("gameEnabled", true);
        }
        if (getAsString(row, "source").isBlank()) {
          row.addProperty("source", "built-in");
        }
        if (getAsString(row, "version").isBlank()) {
          row.addProperty("version", "builtin-1");
        }
        if (getAsString(row, "name").isBlank()) {
          row.addProperty("name", defaultNameFor(locale));
        }
        if (getAsString(row, "nativeName").isBlank()) {
          row.addProperty("nativeName", defaultNativeNameFor(locale));
        }
      }
    }
  }

  private JsonArray ensureLocaleArray(JsonObject state) {
    JsonElement element = state.get("locales");
    if (element == null || !element.isJsonArray()) {
      JsonArray array = new JsonArray();
      state.add("locales", array);
      return array;
    }
    return element.getAsJsonArray();
  }

  private JsonObject copyJsonObject(JsonObject source) {
    return JsonParser.parseString(gson.toJson(source)).getAsJsonObject();
  }

  private String normalizeLocale(String rawLocale) {
    String locale = String.valueOf(rawLocale == null ? "" : rawLocale).trim().replace('_', '-');
    if (locale.isBlank()) {
      return "";
    }
    String lower = locale.toLowerCase(Locale.ROOT);
    if (lower.equals("zh")) {
      return "zh-CN";
    }
    if (lower.equals("en")) {
      return "en-US";
    }
    String[] segments = locale.split("-");
    if (segments.length == 0 || segments[0].isBlank()) {
      return "";
    }
    String language = segments[0].toLowerCase(Locale.ROOT);
    if (segments.length == 1) {
      return language;
    }
    String region = segments[1].length() == 2
        ? segments[1].toUpperCase(Locale.ROOT)
        : segments[1].toLowerCase(Locale.ROOT);
    if (segments.length == 2) {
      return language + "-" + region;
    }
    StringBuilder builder = new StringBuilder(language).append('-').append(region);
    for (int i = 2; i < segments.length; i++) {
      String part = segments[i].trim();
      if (!part.isEmpty()) {
        builder.append('-').append(part.toLowerCase(Locale.ROOT));
      }
    }
    return builder.toString();
  }

  private String getAsString(JsonObject object, String key) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return "";
    }
    return object.get(key).getAsString().trim();
  }

  private boolean getAsBoolean(JsonObject object, String key, boolean fallback) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return fallback;
    }
    try {
      return object.get(key).getAsBoolean();
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String defaultIfBlank(String value, String fallback) {
    String normalized = String.valueOf(value == null ? "" : value).trim();
    return normalized.isEmpty() ? fallback : normalized;
  }

  private String defaultNameFor(String locale) {
    if ("zh-CN".equals(locale)) {
      return "Simplified Chinese";
    }
    if ("en-US".equals(locale)) {
      return "English";
    }
    return locale;
  }

  private String defaultNativeNameFor(String locale) {
    if ("zh-CN".equals(locale)) {
      return "简体中文";
    }
    if ("en-US".equals(locale)) {
      return "English";
    }
    return defaultNameFor(locale);
  }

  static InstallOptions installOptionsFromJson(JsonObject payload) {
    String source = payload == null ? "upload" : getString(payload, "source", "upload");
    String version = payload == null ? "" : getString(payload, "version", "");
    String name = payload == null ? "" : getString(payload, "name", "");
    String nativeName = payload == null ? "" : getString(payload, "nativeName", "");
    return new InstallOptions(source, version, name, nativeName);
  }

  private static String getString(JsonObject payload, String key, String fallback) {
    if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
      return fallback;
    }
    String value = payload.get(key).getAsString();
    return value == null ? fallback : value.trim();
  }

  record InstallOptions(String source, String version, String name, String nativeName) {}

  record InstallOutcome(JsonObject state, List<JsonObject> changed, int fileCount) {}

  private static final class ExtractedPackage {
    private final Map<String, ExtractedLocale> localeMap;
    private final int fileCount;

    private ExtractedPackage(Map<String, ExtractedLocale> localeMap, int fileCount) {
      this.localeMap = localeMap;
      this.fileCount = fileCount;
    }

    Map<String, ExtractedLocale> localeMap() {
      return localeMap;
    }

    int fileCount() {
      return fileCount;
    }
  }

  private static final class ExtractedLocale {
    private byte[] messageFile;
    private final Map<String, byte[]> webFiles = new HashMap<>();
  }
}
