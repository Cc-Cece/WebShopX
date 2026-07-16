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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.bukkit.plugin.java.JavaPlugin;

class ThemeCenterService {
  private static final int PACKAGE_MAX_BYTES = 12 * 1024 * 1024;
  private static final int ENTRY_MAX_BYTES = 2 * 1024 * 1024;
  private static final int ENTRY_LIMIT = 256;
  private static final Set<String> BUILTIN_THEMES = Set.of("default");
  private static final Pattern THEME_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,47}$");

  private final JavaPlugin plugin;
  private final Supplier<Path> userWebRootSupplier;
  private final Gson gson;

  ThemeCenterService(JavaPlugin plugin, Supplier<Path> userWebRootSupplier) {
    this.plugin = plugin;
    this.userWebRootSupplier = userWebRootSupplier;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  synchronized JsonObject listState() {
    return copyJsonObject(readState());
  }

  synchronized JsonObject updateDefaultTheme(String rawThemeId) {
    JsonObject state = readState();
    String themeId = normalizeThemeId(rawThemeId);
    if (themeId.isBlank()) {
      throw new ServiceException("bad_request", "Invalid theme id: defaultTheme");
    }
    JsonObject target = findTheme(ensureThemeArray(state), themeId);
    if (target == null) {
      throw new ServiceException("not_found", "Theme not found");
    }
    if (!getAsBoolean(target, "webEnabled", true)) {
      throw new ServiceException("bad_request", "Default theme must be web enabled");
    }
    state.addProperty("defaultTheme", themeId);
    state.addProperty("lastSyncAt", Instant.now().toString());
    saveState(state);
    return copyJsonObject(state);
  }

  synchronized JsonObject applyThemeAction(String rawThemeId, String rawAction) {
    String themeId = normalizeThemeId(rawThemeId);
    String action = String.valueOf(rawAction == null ? "" : rawAction).trim().toLowerCase(Locale.ROOT);
    if (themeId.isBlank() || action.isBlank()) {
      throw new ServiceException("bad_request", "Missing theme action payload");
    }

    JsonObject state = readState();
    JsonArray themes = ensureThemeArray(state);
    JsonObject target = findTheme(themes, themeId);
    if (target == null) {
      throw new ServiceException("not_found", "Theme not found");
    }

    boolean builtIn = getAsBoolean(target, "builtIn", BUILTIN_THEMES.contains(themeId));
    switch (action) {
      case "toggleweb" -> target.addProperty("webEnabled", !getAsBoolean(target, "webEnabled", true));
      case "remove" -> {
        if (builtIn) {
          throw new ServiceException("bad_request", "Built-in theme cannot be removed");
        }
        JsonArray next = new JsonArray();
        for (JsonElement element : themes) {
          if (!element.isJsonObject()) {
            continue;
          }
          JsonObject row = element.getAsJsonObject();
          if (!themeId.equals(normalizeThemeId(getAsString(row, "themeId")))) {
            next.add(row);
          }
        }
        state.add("themes", next);
        if (themeId.equals(normalizeThemeId(getAsString(state, "defaultTheme")))) {
          state.addProperty("defaultTheme", "default");
        }
        state.addProperty("lastSyncAt", Instant.now().toString());
        saveState(state);
        return copyJsonObject(state);
      }
      default -> throw new ServiceException("bad_request", "Unsupported theme action: " + action);
    }

    if (!getAsBoolean(target, "webEnabled", true)
        && themeId.equals(normalizeThemeId(getAsString(state, "defaultTheme")))) {
      state.addProperty("defaultTheme", "default");
    }
    target.addProperty("updatedAt", Instant.now().toString());
    state.addProperty("lastSyncAt", Instant.now().toString());
    saveState(state);
    return copyJsonObject(state);
  }

  synchronized InstallOutcome installBase64Package(String fileName, String contentBase64, InstallOptions options) {
    if (contentBase64 == null || contentBase64.isBlank()) {
      throw new ServiceException("bad_request", "Missing field: contentBase64");
    }
    String normalizedFileName = String.valueOf(fileName == null ? "" : fileName).trim();
    if (!normalizedFileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      throw new ServiceException("bad_request", "Only .zip theme package is supported");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(contentBase64);
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("bad_request", "contentBase64 is invalid");
    }
    return installZipPackage(bytes, options.withFallbackThemeId(deriveThemeIdFromFilename(normalizedFileName)));
  }

  synchronized InstallOutcome installZipPackage(byte[] zipBytes, InstallOptions options) {
    if (zipBytes == null || zipBytes.length == 0) {
      throw new ServiceException("bad_request", "Theme package is empty");
    }
    if (zipBytes.length > PACKAGE_MAX_BYTES) {
      throw new ServiceException("bad_request", "Theme package is too large");
    }

    ExtractedTheme extracted = extractPackage(zipBytes);
    if (extracted.lightCss() == null || extracted.darkCss() == null) {
      throw new ServiceException("bad_request", "Theme package must include light.css and dark.css");
    }

    String themeId = normalizeThemeId(options.themeId());
    if (themeId.isBlank()) {
      throw new ServiceException("bad_request", "Theme id is required");
    }
    writeThemeFiles(themeId, extracted);

    JsonObject state = readState();
    JsonArray themes = ensureThemeArray(state);
    JsonObject existing = findTheme(themes, themeId);
    JsonObject next = existing == null ? new JsonObject() : existing;
    boolean builtIn = getAsBoolean(next, "builtIn", BUILTIN_THEMES.contains(themeId));
    String name = String.valueOf(options.name() == null ? "" : options.name()).trim();
    if (name.isBlank()) {
      name = defaultNameFor(themeId);
    }
    String version = String.valueOf(options.version() == null ? "" : options.version()).trim();
    if (version.isBlank()) {
      version = "pkg-" + Instant.now();
    }
    String source = String.valueOf(options.source() == null ? "upload" : options.source()).trim().toLowerCase(Locale.ROOT);
    if (source.isBlank()) {
      source = "upload";
    }

    next.addProperty("themeId", themeId);
    next.addProperty("name", name);
    next.addProperty("source", source);
    next.addProperty("version", version);
    next.addProperty("builtIn", builtIn);
    next.addProperty("status", getAsString(next, "status").isBlank() ? "published" : getAsString(next, "status"));
    if (!next.has("webEnabled")) {
      next.addProperty("webEnabled", true);
    }
    next.addProperty("updatedAt", Instant.now().toString());

    if (existing == null) {
      themes.add(next);
    }
    if (normalizeThemeId(getAsString(state, "defaultTheme")).isBlank()) {
      state.addProperty("defaultTheme", themeId);
    }
    state.addProperty("lastSyncAt", Instant.now().toString());
    saveState(state);
    return new InstallOutcome(copyJsonObject(state), List.of(copyJsonObject(next)), extracted.fileCount());
  }

  synchronized JsonArray listPublicWebThemes() {
    JsonObject state = readState();
    JsonArray themes = ensureThemeArray(state);
    Map<String, JsonObject> result = new LinkedHashMap<>();

    for (JsonElement element : themes) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      String themeId = normalizeThemeId(getAsString(row, "themeId"));
      if (themeId.isBlank() || !getAsBoolean(row, "webEnabled", true)) {
        continue;
      }
      result.put(themeId, themeEntryJson(row, themeId));
    }
    for (String builtIn : BUILTIN_THEMES) {
      result.putIfAbsent(builtIn, builtinEntryJson(builtIn));
    }
    JsonArray array = new JsonArray();
    result.values().forEach(array::add);
    return array;
  }

  private JsonObject themeEntryJson(JsonObject source, String themeId) {
    JsonObject row = new JsonObject();
    row.addProperty("themeId", themeId);
    row.addProperty("name", defaultIfBlank(getAsString(source, "name"), defaultNameFor(themeId)));
    row.addProperty("source", defaultIfBlank(getAsString(source, "source"), BUILTIN_THEMES.contains(themeId) ? "built-in" : "upload"));
    row.addProperty("version", defaultIfBlank(getAsString(source, "version"), BUILTIN_THEMES.contains(themeId) ? "builtin-1" : "upload"));
    return row;
  }

  private JsonObject builtinEntryJson(String themeId) {
    JsonObject row = new JsonObject();
    row.addProperty("themeId", themeId);
    row.addProperty("name", defaultNameFor(themeId));
    row.addProperty("source", "built-in");
    row.addProperty("version", "builtin-1");
    return row;
  }

  private ExtractedTheme extractPackage(byte[] zipBytes) {
    byte[] lightCss = null;
    byte[] darkCss = null;
    int files = 0;
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      int scanned = 0;
      while ((entry = zis.getNextEntry()) != null) {
        scanned += 1;
        if (scanned > ENTRY_LIMIT) {
          throw new ServiceException("bad_request", "Theme package contains too many entries");
        }
        if (entry.isDirectory()) {
          continue;
        }
        String normalized = normalizeEntryName(entry.getName()).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.contains("..")) {
          continue;
        }
        byte[] content = readZipEntry(zis, ENTRY_MAX_BYTES);
        if (endsWithPath(normalized, "light.css")) {
          lightCss = content;
          files += 1;
          continue;
        }
        if (endsWithPath(normalized, "dark.css")) {
          darkCss = content;
          files += 1;
        }
      }
    } catch (IOException exception) {
      throw new ServiceException("bad_request", "Failed to read theme package");
    }
    return new ExtractedTheme(lightCss, darkCss, files);
  }

  private boolean endsWithPath(String normalizedPath, String fileName) {
    if (normalizedPath.equals(fileName)) {
      return true;
    }
    return normalizedPath.endsWith("/" + fileName);
  }

  private byte[] readZipEntry(ZipInputStream zis, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = zis.read(buffer)) > 0) {
      total += read;
      if (total > maxBytes) {
        throw new ServiceException("bad_request", "Theme package entry is too large");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private void writeThemeFiles(String themeId, ExtractedTheme data) {
    try {
      Path userWebRoot = userWebRootSupplier.get();
      if (userWebRoot == null) {
        throw new ServiceException("internal_error", "User web root is not initialized");
      }
      Path root = userWebRoot.resolve("themes").resolve(themeId).normalize();
      if (!root.startsWith(userWebRoot)) {
        throw new ServiceException("bad_request", "Invalid theme id path");
      }
      Files.createDirectories(root);
      Files.write(root.resolve("light.css"), data.lightCss(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      Files.write(root.resolve("dark.css"), data.darkCss(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (IOException exception) {
      throw new ServiceException("internal_error", "Failed to write theme files");
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

  private String normalizeThemeId(String rawThemeId) {
    String normalized = String.valueOf(rawThemeId == null ? "" : rawThemeId).trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return "";
    }
    normalized = normalized.replace(' ', '-');
    if (!THEME_ID_PATTERN.matcher(normalized).matches()) {
      return "";
    }
    return normalized;
  }

  private String deriveThemeIdFromFilename(String fileName) {
    String name = String.valueOf(fileName == null ? "" : fileName).trim().toLowerCase(Locale.ROOT);
    if (name.endsWith(".zip")) {
      name = name.substring(0, name.length() - 4);
    }
    name = name.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
    if (name.startsWith("-")) {
      name = name.substring(1);
    }
    if (name.endsWith("-")) {
      name = name.substring(0, name.length() - 1);
    }
    if (name.isBlank()) {
      return "theme-" + Instant.now().getEpochSecond();
    }
    if (name.length() > 48) {
      name = name.substring(0, 48);
    }
    return name;
  }

  private JsonObject findTheme(JsonArray array, String themeId) {
    for (JsonElement element : array) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      if (themeId.equals(normalizeThemeId(getAsString(row, "themeId")))) {
        return row;
      }
    }
    return null;
  }

  private JsonObject readState() {
    JsonObject state = loadStateFromDisk();
    ensureBuiltinThemes(state);
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
      if (!state.has("defaultTheme") || normalizeThemeId(getAsString(state, "defaultTheme")).isBlank()) {
        state.addProperty("defaultTheme", "default");
      }
      if (!state.has("themes") || !state.get("themes").isJsonArray()) {
        state.add("themes", new JsonArray());
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
      throw new ServiceException("internal_error", "Failed to persist theme state");
    }
  }

  private Path stateFile() {
    return plugin.getDataFolder().toPath().resolve("l10n").resolve("themes.json");
  }

  private JsonObject defaultState() {
    JsonObject state = new JsonObject();
    state.addProperty("defaultTheme", "default");
    state.addProperty("lastSyncAt", (String) null);
    JsonArray themes = new JsonArray();
    themes.add(defaultRecord("default", true));
    state.add("themes", themes);
    return state;
  }

  private JsonObject defaultRecord(String themeId, boolean builtIn) {
    JsonObject row = new JsonObject();
    row.addProperty("themeId", themeId);
    row.addProperty("name", defaultNameFor(themeId));
    row.addProperty("source", builtIn ? "built-in" : "upload");
    row.addProperty("version", builtIn ? "builtin-1" : "draft");
    row.addProperty("status", "published");
    row.addProperty("webEnabled", true);
    row.addProperty("builtIn", builtIn);
    row.addProperty("updatedAt", Instant.now().toString());
    return row;
  }

  private void ensureBuiltinThemes(JsonObject state) {
    JsonArray themes = ensureThemeArray(state);
    for (String themeId : BUILTIN_THEMES) {
      JsonObject row = findTheme(themes, themeId);
      if (row == null) {
        themes.add(defaultRecord(themeId, true));
      } else {
        row.addProperty("builtIn", true);
        if (!row.has("webEnabled")) {
          row.addProperty("webEnabled", true);
        }
        if (getAsString(row, "source").isBlank()) {
          row.addProperty("source", "built-in");
        }
        if (getAsString(row, "version").isBlank()) {
          row.addProperty("version", "builtin-1");
        }
        if (getAsString(row, "name").isBlank()) {
          row.addProperty("name", defaultNameFor(themeId));
        }
      }
    }
  }

  private JsonArray ensureThemeArray(JsonObject state) {
    JsonElement element = state.get("themes");
    if (element == null || !element.isJsonArray()) {
      JsonArray array = new JsonArray();
      state.add("themes", array);
      return array;
    }
    return element.getAsJsonArray();
  }

  private JsonObject copyJsonObject(JsonObject source) {
    return JsonParser.parseString(gson.toJson(source)).getAsJsonObject();
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

  private String defaultNameFor(String themeId) {
    if ("default".equals(themeId)) {
      return "Default";
    }
    return themeId;
  }

  static InstallOptions installOptionsFromJson(JsonObject payload) {
    String source = payload == null ? "upload" : getString(payload, "source", "upload");
    String version = payload == null ? "" : getString(payload, "version", "");
    String name = payload == null ? "" : getString(payload, "name", "");
    String themeId = payload == null ? "" : getString(payload, "themeId", "");
    return new InstallOptions(source, version, name, themeId);
  }

  private static String getString(JsonObject payload, String key, String fallback) {
    if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
      return fallback;
    }
    String value = payload.get(key).getAsString();
    return value == null ? fallback : value.trim();
  }

  record InstallOptions(String source, String version, String name, String themeId) {
    InstallOptions withFallbackThemeId(String fallbackThemeId) {
      String normalized = String.valueOf(themeId == null ? "" : themeId).trim();
      if (!normalized.isEmpty()) {
        return this;
      }
      return new InstallOptions(source, version, name, fallbackThemeId);
    }
  }

  record InstallOutcome(JsonObject state, List<JsonObject> changed, int fileCount) {}

  private record ExtractedTheme(byte[] lightCss, byte[] darkCss, int fileCount) {}
}
