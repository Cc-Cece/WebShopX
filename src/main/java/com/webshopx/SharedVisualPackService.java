package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/** Cluster-safe visual-pack use cases shared by Loader HTTP adapters. */
public final class SharedVisualPackService {
  public static final int MAX_UPLOAD_BYTES = 64 * 1024 * 1024;
  private static final long MAX_EXPANDED_BYTES = 256L * 1024L * 1024L;
  private static final int MAX_FILES = 12_000;
  private static final int MAX_ENTRIES = 10_000;
  private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
  private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
  private static final Set<String> OPTIONAL_FILES =
      Set.of("metadata/export-report.json", "metadata/languages.json");

  private final DatabaseManager database;
  private final Gson gson;

  public SharedVisualPackService(DatabaseManager database) {
    this.database = Objects.requireNonNull(database, "database");
    gson = CommerceJson.create();
  }

  public PackRecord install(byte[] zipBytes, String uploadedBy) {
    return install(zipBytes, uploadedBy, MAX_EXPANDED_BYTES);
  }

  public PackRecord install(byte[] zipBytes, String uploadedBy, long expandedLimit) {
    if (zipBytes == null || zipBytes.length == 0 || zipBytes.length > MAX_UPLOAD_BYTES) {
      throw new ServiceException("bad_request", "Visual pack must be between 1 byte and 64 MiB");
    }
    ParsedPack parsed = parse(zipBytes, Math.min(MAX_EXPANDED_BYTES, Math.max(1, expandedLimit)));
    String versionId = sha256(zipBytes).substring(0, 24);
    String owner = uploadedBy == null || uploadedBy.isBlank() ? "system" : uploadedBy;
    database.inTransaction(
        connection -> {
          int order = 0;
          try (PreparedStatement statement = connection.prepareStatement(
                  "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM visual_packs");
              ResultSet result = statement.executeQuery()) {
            if (result.next()) order = result.getInt(1);
          }
          String prefix = "/visual-packs/" + parsed.packId() + "/";
          try (PreparedStatement statement = connection.prepareStatement(
              "DELETE FROM shared_binary_assets WHERE asset_path>=? AND asset_path<?")) {
            statement.setString(1, prefix);
            statement.setString(2, prefix + '\uffff');
            statement.executeUpdate();
          }
          insertAsset(connection, prefix + versionId + "/original.zip",
              "application/zip", zipBytes, owner);
          for (Map.Entry<String, byte[]> entry : parsed.files().entrySet()) {
            if (entry.getKey().equals("manifest.json")) continue;
            insertAsset(
                connection,
                prefix + versionId + "/" + entry.getKey(),
                entry.getKey().endsWith(".png") ? "image/png" : "application/json",
                entry.getValue(),
                owner);
          }
          String sql = database.dbType().isSqlite() ? """
              INSERT INTO visual_packs (
                pack_id,pack_name,version_id,enabled,sort_order,icons_enabled,
                translations_enabled,manifest_json,file_size,entry_count,uploaded_by)
              VALUES (?,?,?,FALSE,?,?,?,?,?,?,?)
              ON CONFLICT(pack_id) DO UPDATE SET
                pack_name=excluded.pack_name,version_id=excluded.version_id,
                icons_enabled=excluded.icons_enabled,
                translations_enabled=excluded.translations_enabled,
                manifest_json=excluded.manifest_json,file_size=excluded.file_size,
                entry_count=excluded.entry_count,uploaded_by=excluded.uploaded_by,
                updated_at=CURRENT_TIMESTAMP
              """ : """
              INSERT INTO visual_packs (
                pack_id,pack_name,version_id,enabled,sort_order,icons_enabled,
                translations_enabled,manifest_json,file_size,entry_count,uploaded_by)
              VALUES (?,?,?,FALSE,?,?,?,?,?,?,?)
              ON DUPLICATE KEY UPDATE
                pack_name=VALUES(pack_name),version_id=VALUES(version_id),
                icons_enabled=VALUES(icons_enabled),
                translations_enabled=VALUES(translations_enabled),
                manifest_json=VALUES(manifest_json),file_size=VALUES(file_size),
                entry_count=VALUES(entry_count),uploaded_by=VALUES(uploaded_by),
                updated_at=CURRENT_TIMESTAMP
              """;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parsed.packId());
            statement.setString(2, parsed.packName());
            statement.setString(3, versionId);
            statement.setInt(4, order);
            statement.setBoolean(5, parsed.icons());
            statement.setBoolean(6, parsed.translations());
            statement.setString(7, gson.toJson(parsed.manifest()));
            statement.setLong(8, zipBytes.length);
            statement.setInt(9, parsed.entries());
            statement.setString(10, owner);
            statement.executeUpdate();
          }
          return null;
        });
    return find(parsed.packId()).orElseThrow();
  }

  public List<PackRecord> list() {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement("""
              SELECT pack_id,pack_name,version_id,enabled,sort_order,icons_enabled,
                     translations_enabled,manifest_json,file_size,entry_count,
                     uploaded_by,created_at,updated_at
              FROM visual_packs ORDER BY sort_order ASC,pack_id ASC
              """); ResultSet result = statement.executeQuery()) {
            List<PackRecord> records = new ArrayList<>();
            while (result.next()) records.add(read(result));
            return List.copyOf(records);
          }
        });
  }

  public Optional<PackRecord> find(String packId) {
    String normalized = normalizePackId(packId);
    return list().stream().filter(pack -> pack.packId().equals(normalized)).findFirst();
  }

  public PackRecord updateState(
      String packId, Boolean enabled, Boolean iconsEnabled, Boolean translationsEnabled) {
    PackRecord current = find(packId)
        .orElseThrow(() -> new ServiceException("not_found", "Visual pack not found"));
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement("""
              UPDATE visual_packs SET enabled=?,icons_enabled=?,translations_enabled=?,
                updated_at=CURRENT_TIMESTAMP WHERE pack_id=?
              """)) {
            statement.setBoolean(1, enabled == null ? current.enabled() : enabled);
            statement.setBoolean(2,
                iconsEnabled == null ? current.iconsEnabled() : iconsEnabled);
            statement.setBoolean(3,
                translationsEnabled == null
                    ? current.translationsEnabled() : translationsEnabled);
            statement.setString(4, current.packId());
            statement.executeUpdate();
          }
          return null;
        });
    return find(current.packId()).orElseThrow();
  }

  public void move(String packId, int delta) {
    String normalized = normalizePackId(packId);
    List<PackRecord> packs = list();
    int index = -1;
    for (int candidate = 0; candidate < packs.size(); candidate++) {
      if (packs.get(candidate).packId().equals(normalized)) index = candidate;
    }
    if (index < 0) throw new ServiceException("not_found", "Visual pack not found");
    int other = index + delta;
    if (other < 0 || other >= packs.size()) return;
    PackRecord first = packs.get(index);
    PackRecord second = packs.get(other);
    database.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(
              "UPDATE visual_packs SET sort_order=?,updated_at=CURRENT_TIMESTAMP WHERE pack_id=?")) {
            statement.setInt(1, second.sortOrder());
            statement.setString(2, first.packId());
            statement.executeUpdate();
            statement.setInt(1, first.sortOrder());
            statement.setString(2, second.packId());
            statement.executeUpdate();
          }
          return null;
        });
  }

  public boolean delete(String packId) {
    String normalized = normalizePackId(packId);
    return database.inTransaction(
        connection -> {
          String prefix = "/visual-packs/" + normalized + "/";
          try (PreparedStatement assets = connection.prepareStatement(
                  "DELETE FROM shared_binary_assets WHERE asset_path>=? AND asset_path<?");
              PreparedStatement pack = connection.prepareStatement(
                  "DELETE FROM visual_packs WHERE pack_id=?")) {
            assets.setString(1, prefix);
            assets.setString(2, prefix + '\uffff');
            assets.executeUpdate();
            pack.setString(1, normalized);
            return pack.executeUpdate() > 0;
          }
        });
  }

  public byte[] original(String packId) {
    PackRecord pack = find(packId)
        .orElseThrow(() -> new ServiceException("not_found", "Visual pack not found"));
    return asset("/visual-packs/" + pack.packId() + "/" + pack.versionId()
        + "/original.zip");
  }

  public Optional<ResolvedVisual> resolve(String itemId) {
    String normalized = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return Optional.empty();
    List<PackRecord> packs = new ArrayList<>(list());
    packs.sort(Comparator.comparingInt(PackRecord::sortOrder).thenComparing(PackRecord::packId));
    for (PackRecord pack : packs) {
      if (!pack.enabled() || !pack.iconsEnabled()) continue;
      JsonArray entries = JsonParser.parseString(pack.manifestJson())
          .getAsJsonObject().getAsJsonArray("entries");
      for (JsonElement element : entries) {
        JsonObject entry = element.getAsJsonObject();
        if (normalized.equals(entry.get("itemId").getAsString().toLowerCase(Locale.ROOT))
            && entry.has("icon")) {
          return Optional.of(new ResolvedVisual(
              normalized,
              "/visual-packs/" + pack.packId() + "/" + pack.versionId() + "/"
                  + entry.get("icon").getAsString(),
              pack.packId(),
              entry.get("translationKey").getAsString()));
        }
      }
    }
    return Optional.empty();
  }

  private ParsedPack parse(byte[] zipBytes, long expandedLimit) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    long total = 0;
    try (ZipInputStream zip = new ZipInputStream(
        new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName().replace('\\', '/');
        validatePath(name);
        if (entry.isDirectory()) continue;
        if (files.size() >= MAX_FILES || files.containsKey(name)) {
          throw invalid("Visual pack has too many or duplicate files");
        }
        if (!allowed(name)) throw invalid("Unsupported file: " + name);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) >= 0) {
          if (read == 0) continue;
          total += read;
          if (total > expandedLimit) throw invalid("Visual pack expands beyond its limit");
          output.write(buffer, 0, read);
        }
        files.put(name, output.toByteArray());
      }
    } catch (ServiceException failure) {
      throw failure;
    } catch (Exception failure) {
      throw invalid("Visual pack ZIP is invalid");
    }
    byte[] manifestBytes = requiredFile(files, "manifest.json", 4 * 1024 * 1024);
    JsonObject manifest = jsonObject(manifestBytes, "manifest.json");
    try {
      if (manifest.get("schemaVersion").getAsInt() != 2) {
        throw invalid("Unsupported schemaVersion");
      }
      JsonObject pack = manifest.getAsJsonObject("pack");
      String packId = normalizePackId(pack.get("id").getAsString());
      String packName = pack.has("name") ? pack.get("name").getAsString().trim() : packId;
      if (packName.isBlank() || packName.length() > 255) throw invalid("Invalid pack name");
      JsonObject content = manifest.has("content")
          ? manifest.getAsJsonObject("content") : new JsonObject();
      boolean icons = !content.has("icons") || content.get("icons").getAsBoolean();
      boolean translations = !content.has("translations")
          || content.get("translations").getAsBoolean();
      if (!icons && !translations) throw invalid("Visual pack has no usable content");
      if (!icons && files.keySet().stream().anyMatch(name -> name.startsWith("icons/"))) {
        throw invalid("Language-only pack contains icons");
      }
      if (!translations
          && files.keySet().stream().anyMatch(name -> name.startsWith("translations/"))) {
        throw invalid("Icon-only pack contains translations");
      }
      JsonArray entries = manifest.getAsJsonArray("entries");
      if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
        throw invalid("Invalid visual entry count");
      }
      JsonObject catalog = jsonObject(
          requiredFile(files, "catalog/items.json", MAX_JSON_BYTES), "catalog/items.json");
      Set<String> seen = new java.util.HashSet<>();
      for (JsonElement element : entries) {
        if (!element.isJsonObject()) throw invalid("Visual entry must be an object");
        JsonObject row = element.getAsJsonObject();
        String itemId = row.get("itemId").getAsString().toLowerCase(Locale.ROOT);
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !seen.add(itemId)) {
          throw invalid("Invalid or duplicate itemId: " + itemId);
        }
        String translationKey = row.get("translationKey").getAsString();
        if (!translationKey.matches("[a-zA-Z0-9_.:/-]{1,255}")) {
          throw invalid("Invalid translation key");
        }
        if (!catalog.has(itemId) || !catalog.get(itemId).isJsonObject()
            || !translationKey.equals(
                catalog.getAsJsonObject(itemId).get("translationKey").getAsString())) {
          throw invalid("Catalog mismatch: " + itemId);
        }
        if (icons) validateIcon(files, row);
        else if (row.has("icon") || row.has("sha256")) {
          throw invalid("Language-only pack contains icon metadata");
        }
      }
      if (catalog.size() != seen.size()) throw invalid("Catalog entry count mismatch");
      validateLocales(files, manifest, seen, translations);
      return new ParsedPack(packId, packName, manifest, Map.copyOf(files),
          entries.size(), icons, translations);
    } catch (ServiceException failure) {
      throw failure;
    } catch (Exception failure) {
      throw invalid("Visual pack manifest is invalid");
    }
  }

  private static void validateIcon(Map<String, byte[]> files, JsonObject row) {
    if (!row.has("icon") || !row.has("sha256")) throw invalid("Missing icon metadata");
    String path = row.get("icon").getAsString().replace('\\', '/');
    if (!path.matches("icons/[a-z0-9._/-]+\\.png") || path.contains("..")) {
      throw invalid("Invalid icon path");
    }
    byte[] bytes = requiredFile(files, path, MAX_ICON_BYTES);
    String expected = row.get("sha256").getAsString().replaceFirst("^sha256:", "");
    if (!expected.matches("(?i)[a-f0-9]{64}") || !sha256(bytes).equalsIgnoreCase(expected)) {
      throw invalid("Icon hash mismatch: " + path);
    }
    try {
      if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P'
          || bytes[2] != 'N' || bytes[3] != 'G') throw invalid("Icon is not PNG: " + path);
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null || image.getWidth() < 1 || image.getHeight() < 1
          || image.getWidth() > 1024 || image.getHeight() > 1024) {
        throw invalid("Invalid icon dimensions: " + path);
      }
    } catch (java.io.IOException failure) {
      throw invalid("Invalid icon: " + path);
    }
  }

  private static void validateLocales(
      Map<String, byte[]> files, JsonObject manifest, Set<String> itemIds, boolean translations) {
    JsonArray locales = manifest.getAsJsonArray("locales");
    if (locales == null || locales.size() > 256
        || (translations && locales.isEmpty()) || (!translations && !locales.isEmpty())) {
      throw invalid("Invalid locale count");
    }
    Set<String> seen = new java.util.HashSet<>();
    for (JsonElement element : locales) {
      String locale = element.getAsString().toLowerCase(Locale.ROOT).replace('-', '_');
      if (!locale.matches("[a-z0-9_]{2,32}") || !seen.add(locale)) {
        throw invalid("Invalid or duplicate locale");
      }
      JsonObject names = jsonObject(requiredFile(
          files, "translations/" + locale + ".json", MAX_JSON_BYTES), locale);
      for (Map.Entry<String, JsonElement> translated : names.entrySet()) {
        if (!itemIds.contains(translated.getKey().toLowerCase(Locale.ROOT))
            || !translated.getValue().isJsonPrimitive()
            || translated.getValue().getAsString().isBlank()
            || translated.getValue().getAsString().length() > 512) {
          throw invalid("Invalid translated item: " + translated.getKey());
        }
      }
    }
  }

  private static boolean allowed(String name) {
    return name.equals("manifest.json") || name.equals("catalog/items.json")
        || name.equals("catalog/enchantments.json") || OPTIONAL_FILES.contains(name)
        || name.matches("icons/[a-z0-9._/-]+\\.png")
        || name.matches("(?:translations|enchantments)/[a-z0-9_]{2,32}\\.json");
  }

  private static void validatePath(String name) {
    if (name.isBlank() || name.length() > 320 || name.startsWith("/") || name.contains("../")
        || name.contains(":/") || name.indexOf('\0') >= 0) {
      throw invalid("Unsafe ZIP path");
    }
  }

  private static byte[] requiredFile(Map<String, byte[]> files, String path, int max) {
    byte[] bytes = files.get(path);
    if (bytes == null || bytes.length == 0 || bytes.length > max) {
      throw invalid("Missing or oversized file: " + path);
    }
    return bytes;
  }

  private static JsonObject jsonObject(byte[] bytes, String path) {
    try {
      JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
      if (!parsed.isJsonObject()) throw invalid("Expected JSON object: " + path);
      return parsed.getAsJsonObject();
    } catch (com.google.gson.JsonParseException failure) {
      throw invalid("Invalid JSON: " + path);
    }
  }

  private static ServiceException invalid(String message) {
    return new ServiceException("visual_pack_invalid", message);
  }

  private static String normalizePackId(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
      throw new ServiceException("bad_request", "Invalid visual pack id");
    }
    return normalized;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static void insertAsset(
      java.sql.Connection connection,
      String path,
      String mimeType,
      byte[] content,
      String owner) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO shared_binary_assets"
            + " (asset_path,mime_type,content_blob,sha256,owner) VALUES (?,?,?,?,?)")) {
      statement.setString(1, path);
      statement.setString(2, mimeType);
      statement.setBytes(3, content);
      statement.setString(4, sha256(content));
      statement.setString(5, owner);
      statement.executeUpdate();
    }
  }

  private byte[] asset(String path) {
    return database.withConnection(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(
              "SELECT content_blob FROM shared_binary_assets WHERE asset_path=?")) {
            statement.setString(1, path);
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) throw new ServiceException("not_found", "Visual asset not found");
              return result.getBytes(1);
            }
          }
        });
  }

  private static PackRecord read(ResultSet result) throws java.sql.SQLException {
    Timestamp created = result.getTimestamp("created_at");
    Timestamp updated = result.getTimestamp("updated_at");
    return new PackRecord(
        result.getString("pack_id"), result.getString("pack_name"),
        result.getString("version_id"), result.getBoolean("enabled"),
        result.getInt("sort_order"), result.getBoolean("icons_enabled"),
        result.getBoolean("translations_enabled"), result.getString("manifest_json"),
        result.getLong("file_size"), result.getInt("entry_count"),
        result.getString("uploaded_by"),
        created == null ? null : created.toLocalDateTime(),
        updated == null ? null : updated.toLocalDateTime());
  }

  public record PackRecord(
      String packId, String packName, String versionId, boolean enabled, int sortOrder,
      boolean iconsEnabled, boolean translationsEnabled, String manifestJson,
      long fileSize, int entryCount, String uploadedBy,
      LocalDateTime createdAt, LocalDateTime updatedAt) {}

  public record ResolvedVisual(
      String itemId, String iconPath, String packId, String translationKey) {}

  private record ParsedPack(
      String packId, String packName, JsonObject manifest, Map<String, byte[]> files,
      int entries, boolean icons, boolean translations) {}
}
