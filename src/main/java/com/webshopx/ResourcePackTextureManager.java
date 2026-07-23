package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Read-only resource-pack resolver. It intentionally resolves an item model to a
 * representative PNG instead of attempting to reproduce Minecraft's 3D renderer.
 */
final class ResourcePackTextureManager implements AutoCloseable {
  private static final long MAX_PACK_BYTES = 512L * 1024 * 1024;
  private static final int MAX_ENTRIES = 100_000;
  private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
  private static final int MAX_TEXTURE_BYTES = 8 * 1024 * 1024;

  private final JavaPlugin plugin;
  private volatile List<PackSource> packs = List.of();
  private volatile String revision = "none";

  ResourcePackTextureManager(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  synchronized void reload() {
    close();
    List<ConfiguredPack> configured = configuredPacks();
    List<PackSource> loaded = new ArrayList<>();
    for (ConfiguredPack value : configured) {
      try {
        PackSource source = PackSource.open(value.path(), value.priority());
      if (source != null) {
        loaded.add(source);
      }
      } catch (Exception exception) {
        plugin.getLogger().warning("Skipping resource pack " + value.path() + ": " + exception.getMessage());
      }
    }
    loaded.sort(Comparator.comparingInt(PackSource::priority).reversed());
    packs = List.copyOf(loaded);
    revision = hashRevision(loaded);
    plugin.getLogger().info("Resource-pack texture index ready: " + loaded.size()
        + " pack(s), revision " + revision);
  }

  Optional<ResolvedTexture> resolve(
      String rawItemId,
      Integer customModelData,
      String explicitModelId) {
    Identifier item = Identifier.parse(rawItemId, "minecraft");
    if (explicitModelId != null && !explicitModelId.isBlank()) {
      String texture = resolveModelTexture(explicitModelId, 0, 0);
      byte[] modelBytes = texture == null ? null : readLayered(
          texturePath(Identifier.parse(texture, item.namespace())),
          0,
          MAX_TEXTURE_BYTES);
      if (modelBytes != null && isPng(modelBytes)) {
        return Optional.of(
            new ResolvedTexture(modelBytes, revision, "resource-pack", texture));
      }
    }
    for (int packIndex = 0; packIndex < packs.size(); packIndex++) {
      PackSource pack = packs.get(packIndex);
      String model = resolveItemModel(pack, item, customModelData);
      if (model == null) continue;
      String texture = resolveModelTexture(model, 0, 0);
      if (texture == null) continue;
      byte[] bytes = readLayered(texturePath(Identifier.parse(texture, item.namespace())), 0,
          MAX_TEXTURE_BYTES);
      if (bytes != null && isPng(bytes)) {
        return Optional.of(new ResolvedTexture(bytes, revision, pack.name(), texture));
      }
    }
    // A pack may directly supply an item texture without a model.
    String direct = "assets/" + item.namespace() + "/textures/item/" + item.path() + ".png";
    byte[] bytes = readLayered(direct, 0, MAX_TEXTURE_BYTES);
    return bytes != null && isPng(bytes)
        ? Optional.of(new ResolvedTexture(bytes, revision, "resource-pack", item.toString()))
        : Optional.empty();
  }

  String revision() {
    return revision;
  }

  private String resolveItemModel(PackSource pack, Identifier item, Integer cmd) {
    JsonObject modern = readJson(pack,
        "assets/" + item.namespace() + "/items/" + item.path() + ".json");
    if (modern != null) {
      String result = modernModel(modern.get("model"));
      if (result != null) return result;
    }

    String legacyId = item.namespace() + ":item/" + item.path();
    JsonObject legacy = readJson(pack, modelPath(Identifier.parse(legacyId, item.namespace())));
    if (legacy == null) return null;
    if (cmd != null && legacy.has("overrides") && legacy.get("overrides").isJsonArray()) {
      for (JsonElement element : legacy.getAsJsonArray("overrides")) {
        if (!element.isJsonObject()) continue;
        JsonObject override = element.getAsJsonObject();
        JsonObject predicate = override.has("predicate") && override.get("predicate").isJsonObject()
            ? override.getAsJsonObject("predicate") : null;
        if (predicate != null && predicate.has("custom_model_data")
            && cmd.doubleValue() >= predicate.get("custom_model_data").getAsDouble()
            && override.has("model")) {
          legacyId = override.get("model").getAsString();
        }
      }
    }
    return legacyId;
  }

  private String modernModel(JsonElement element) {
    if (element == null || !element.isJsonObject()) return null;
    JsonObject object = element.getAsJsonObject();
    String type = string(object, "type");
    if ((type == null || type.endsWith(":model")) && object.has("model")) {
      return object.get("model").getAsString();
    }
    // Select/condition/range dispatch is deliberately conservative: choose the
    // first explicit model as a stable web preview.
    for (String key : List.of("model", "fallback", "on_true", "on_false")) {
      String nested = modernModel(object.get(key));
      if (nested != null) return nested;
    }
    for (String key : List.of("cases", "entries", "models")) {
      JsonArray array = object.has(key) && object.get(key).isJsonArray()
          ? object.getAsJsonArray(key) : null;
      if (array == null) continue;
      for (JsonElement child : array) {
        String nested = modernModel(child.isJsonObject() && child.getAsJsonObject().has("model")
            ? child.getAsJsonObject().get("model") : child);
        if (nested != null) return nested;
      }
    }
    return null;
  }

  private String resolveModelTexture(String modelId, int startPack, int depth) {
    if (depth > 16) return null;
    Identifier model = Identifier.parse(modelId, "minecraft");
    JsonObject json = readJsonLayered(modelPath(model), startPack);
    if (json == null) return null;
    if (json.has("textures") && json.get("textures").isJsonObject()) {
      JsonObject textures = json.getAsJsonObject("textures");
      for (String key : List.of("layer0", "all", "particle", "texture")) {
        String value = string(textures, key);
        if (value != null && !value.startsWith("#")) return value;
      }
      for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
        if (entry.getValue().isJsonPrimitive()) {
          String value = entry.getValue().getAsString();
          if (!value.startsWith("#")) return value;
        }
      }
    }
    String parent = string(json, "parent");
    return parent == null ? null : resolveModelTexture(parent, startPack, depth + 1);
  }

  private JsonObject readJsonLayered(String path, int start) {
    for (int index = start; index < packs.size(); index++) {
      JsonObject json = readJson(packs.get(index), path);
      if (json != null) return json;
    }
    return null;
  }

  private JsonObject readJson(PackSource pack, String path) {
    byte[] bytes = pack.read(path, MAX_JSON_BYTES);
    if (bytes == null) return null;
    try {
      return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception exception) {
      plugin.getLogger().fine("Invalid resource-pack JSON " + path + " in " + pack.name());
      return null;
    }
  }

  private byte[] readLayered(String path, int start, int limit) {
    for (int index = start; index < packs.size(); index++) {
      byte[] bytes = packs.get(index).read(path, limit);
      if (bytes != null) return bytes;
    }
    return null;
  }

  private List<ConfiguredPack> configuredPacks() {
    List<ConfiguredPack> result = new ArrayList<>();
    List<Map<?, ?>> rows = plugin.getConfig().getMapList("webshop.textures.resource-packs");
    for (Map<?, ?> row : rows) {
      String raw = String.valueOf(row.containsKey("path") ? row.get("path") : "").trim();
      if (raw.isEmpty()) continue;
      Object priorityValue = row.get("priority");
      int priority = priorityValue instanceof Number number ? number.intValue() : 0;
      result.add(new ConfiguredPack(resolveConfiguredPath(raw), priority));
    }
    Path defaultDirectory = plugin.getDataFolder().toPath().resolve("resourcepacks");
    if (result.isEmpty()) {
      try {
        Files.createDirectories(defaultDirectory);
        try (var stream = Files.list(defaultDirectory)) {
          stream.filter(path -> Files.isDirectory(path)
                  || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
              .sorted().forEach(path -> result.add(new ConfiguredPack(path, 0)));
        }
      } catch (IOException exception) {
        plugin.getLogger().warning("Could not scan resourcepacks directory: " + exception.getMessage());
      }
    }
    return result;
  }

  private Path resolveConfiguredPath(String raw) {
    Path path = Path.of(raw);
    if (!path.isAbsolute()) path = plugin.getServer().getWorldContainer().toPath().resolve(path);
    return path.toAbsolutePath().normalize();
  }

  @Override
  public synchronized void close() {
    for (PackSource pack : packs) pack.close();
    packs = List.of();
  }

  private static String modelPath(Identifier id) {
    return "assets/" + id.namespace() + "/models/" + id.path() + ".json";
  }

  private static String texturePath(Identifier id) {
    return "assets/" + id.namespace() + "/textures/" + id.path() + ".png";
  }

  private static String string(JsonObject object, String key) {
    return object != null && object.has(key) && object.get(key).isJsonPrimitive()
        ? object.get(key).getAsString() : null;
  }

  private static boolean isPng(byte[] bytes) {
    return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
        && bytes[2] == 0x4e && bytes[3] == 0x47;
  }

  private static String hashRevision(List<PackSource> sources) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (PackSource source : sources) {
        digest.update((source.name() + ":" + source.priority() + ":" + source.modified())
            .getBytes(StandardCharsets.UTF_8));
      }
      return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    } catch (Exception exception) {
      return Long.toHexString(System.currentTimeMillis());
    }
  }

  record ResolvedTexture(byte[] bytes, String revision, String source, String textureId) {}
  private record ConfiguredPack(Path path, int priority) {}
  private record Identifier(String namespace, String path) {
    static Identifier parse(String raw, String defaultNamespace) {
      String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replace('\\', '/');
      int colon = value.indexOf(':');
      String namespace = colon >= 0 ? value.substring(0, colon) : defaultNamespace;
      String path = colon >= 0 ? value.substring(colon + 1) : value;
      path = path.replaceAll("[^a-z0-9_./-]", "_");
      namespace = namespace.replaceAll("[^a-z0-9_.-]", "_");
      return new Identifier(namespace.isEmpty() ? "minecraft" : namespace, path);
    }
    @Override public String toString() { return namespace + ":" + path; }
  }

  private interface PackSource {
    byte[] read(String path, int maxBytes);
    int priority();
    String name();
    long modified();
    void close();

    static PackSource open(Path path, int priority) throws IOException {
      if (!Files.exists(path)) return null;
      if (Files.isDirectory(path)) return new DirectoryPack(path, priority);
      if (Files.size(path) > MAX_PACK_BYTES) throw new IOException("pack exceeds 512 MiB");
      return new ZipPack(path, priority);
    }
  }

  private record DirectoryPack(Path root, int priority) implements PackSource {
    @Override public byte[] read(String relative, int maxBytes) {
      try {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)
            || Files.size(target) > maxBytes) return null;
        return Files.readAllBytes(target);
      } catch (IOException ignored) { return null; }
    }
    @Override public String name() {
      Path fileName = root.getFileName();
      return fileName == null ? root.toString() : fileName.toString();
    }
    @Override public long modified() {
      try { return Files.getLastModifiedTime(root).toMillis(); }
      catch (IOException ignored) { return 0; }
    }
    @Override public void close() {}
  }

  private static final class ZipPack implements PackSource {
    private final Path path;
    private final int priority;
    private final ZipFile zip;
    private final Map<String, ZipEntry> entries;
    ZipPack(Path path, int priority) throws IOException {
      this.path = path;
      this.priority = priority;
      this.zip = new ZipFile(path.toFile());
      this.entries = new HashMap<>();
      var enumeration = zip.entries();
      int count = 0;
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        if (++count > MAX_ENTRIES) {
          zip.close();
          throw new IOException("pack contains too many files");
        }
        String name = entry.getName().replace('\\', '/');
        if (!entry.isDirectory() && !name.startsWith("/") && !name.contains("../")) {
          entries.put(name, entry);
        }
      }
    }
    @Override public byte[] read(String relative, int maxBytes) {
      ZipEntry entry = entries.get(relative);
      if (entry == null || entry.getSize() > maxBytes) return null;
      try (InputStream input = zip.getInputStream(entry)) {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        return bytes.length > maxBytes ? null : bytes;
      } catch (IOException ignored) { return null; }
    }
    @Override public int priority() { return priority; }
    @Override public String name() {
      Path fileName = path.getFileName();
      return fileName == null ? path.toString() : fileName.toString();
    }
    @Override public long modified() {
      try { return Files.getLastModifiedTime(path).toMillis(); }
      catch (IOException ignored) { return 0; }
    }
    @Override public void close() {
      try {
        zip.close();
      } catch (IOException exception) {
        // Closing during shutdown is best-effort; there is no recovery action.
      }
    }
  }
}
