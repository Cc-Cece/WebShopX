package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.bukkit.plugin.java.JavaPlugin;

final class VisualPackService {
  static final int MAX_UPLOAD_BYTES = 64 * 1024 * 1024;
  private static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
  private static final int MAX_FILES = 12_000;
  private static final int MAX_ENTRIES = 10_000;
  private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
  private static final Set<String> OPTIONAL_FILES = Set.of("metadata/export-report.json");

  private final DatabaseManager databaseManager;
  private final JavaPlugin plugin;
  private final Gson gson;
  private final Path root;
  private volatile Map<String, ResolvedVisual> resolved = Map.of();

  VisualPackService(DatabaseManager databaseManager, JavaPlugin plugin, Gson gson) {
    this.databaseManager = databaseManager;
    this.plugin = plugin;
    this.gson = gson;
    this.root = plugin.getDataFolder().toPath().resolve("visual-packs").toAbsolutePath().normalize();
    try {
      Files.createDirectories(root);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not create visual-packs directory", exception);
    }
    rebuildIndex();
  }

  synchronized PackRecord install(byte[] zipBytes, String uploadedBy) {
    if (zipBytes.length == 0 || zipBytes.length > MAX_UPLOAD_BYTES) {
      throw new ServiceException("bad_request", "Visual pack must be between 1 byte and 64 MiB");
    }
    String versionId = sha256(zipBytes).substring(0, 24);
    Path temporary = null;
    Path versionDirectory = null;
    try {
      temporary = Files.createTempFile(root, ".upload-", ".zip");
      Files.write(temporary, zipBytes);
      ValidatedPack validated = validate(temporary);
      versionDirectory = root.resolve(validated.packId()).resolve(versionId).normalize();
      ensureUnderRoot(versionDirectory);
      Files.createDirectories(versionDirectory);
      extractValidated(temporary, versionDirectory, validated);
      Files.copy(temporary, versionDirectory.resolve("original.zip"),
          StandardCopyOption.REPLACE_EXISTING);
      upsert(validated, versionId, zipBytes.length, uploadedBy);
      rebuildIndex();
      return find(validated.packId()).orElseThrow();
    } catch (ServiceException exception) {
      deleteTreeQuietly(versionDirectory);
      throw exception;
    } catch (Exception exception) {
      deleteTreeQuietly(versionDirectory);
      throw new ServiceException("visual_pack_invalid",
          "Visual pack import failed: " + exception.getMessage());
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
      }
    }
  }

  List<PackRecord> list() {
    return databaseManager.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT pack_id, pack_name, version_id, enabled, sort_order, icons_enabled,
                 translations_enabled, manifest_json, file_size, entry_count,
                 uploaded_by, created_at, updated_at
          FROM visual_packs
          ORDER BY sort_order ASC, pack_id ASC
          """);
           ResultSet resultSet = statement.executeQuery()) {
        List<PackRecord> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(read(resultSet));
        }
        return rows;
      }
    });
  }

  Optional<PackRecord> find(String packId) {
    String normalized = normalizePackId(packId);
    return list().stream().filter(row -> row.packId().equals(normalized)).findFirst();
  }

  synchronized PackRecord updateState(
      String packId, Boolean enabled, Boolean iconsEnabled, Boolean translationsEnabled) {
    PackRecord current = find(packId)
        .orElseThrow(() -> new ServiceException("not_found", "Visual pack not found"));
    databaseManager.withConnection(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE visual_packs
          SET enabled = ?, icons_enabled = ?, translations_enabled = ?,
              updated_at = CURRENT_TIMESTAMP
          WHERE pack_id = ?
          """)) {
        statement.setBoolean(1, enabled == null ? current.enabled() : enabled);
        statement.setBoolean(2, iconsEnabled == null ? current.iconsEnabled() : iconsEnabled);
        statement.setBoolean(3,
            translationsEnabled == null ? current.translationsEnabled() : translationsEnabled);
        statement.setString(4, current.packId());
        statement.executeUpdate();
      }
      return null;
    });
    rebuildIndex();
    return find(current.packId()).orElseThrow();
  }

  synchronized void move(String packId, int delta) {
    List<PackRecord> rows = list();
    int index = -1;
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).packId().equals(normalizePackId(packId))) {
        index = i;
        break;
      }
    }
    int other = index + delta;
    if (index < 0) {
      throw new ServiceException("not_found", "Visual pack not found");
    }
    if (other < 0 || other >= rows.size()) {
      return;
    }
    PackRecord first = rows.get(index);
    PackRecord second = rows.get(other);
    databaseManager.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE visual_packs SET sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE pack_id = ?")) {
        statement.setInt(1, second.sortOrder());
        statement.setString(2, first.packId());
        statement.executeUpdate();
        statement.setInt(1, first.sortOrder());
        statement.setString(2, second.packId());
        statement.executeUpdate();
      }
      return null;
    });
    rebuildIndex();
  }

  synchronized boolean delete(String packId) {
    PackRecord current = find(packId).orElse(null);
    if (current == null) {
      return false;
    }
    boolean deleted = databaseManager.withConnection(connection -> {
      try (PreparedStatement statement =
               connection.prepareStatement("DELETE FROM visual_packs WHERE pack_id = ?")) {
        statement.setString(1, current.packId());
        return statement.executeUpdate() > 0;
      }
    });
    if (deleted) {
      deleteTreeQuietly(root.resolve(current.packId()));
      rebuildIndex();
    }
    return deleted;
  }

  Optional<byte[]> readOriginal(String packId) {
    return find(packId).flatMap(pack -> readFile(
        root.resolve(pack.packId()).resolve(pack.versionId()).resolve("original.zip")));
  }

  Optional<byte[]> readAsset(String packId, String versionId, String relative) {
    if (versionId == null || !versionId.matches("[a-f0-9]{24}")) {
      return Optional.empty();
    }
    if (!relative.matches("icons/[a-z0-9._/-]+\\.png") || relative.contains("..")) {
      return Optional.empty();
    }
    Path path = root.resolve(normalizePackId(packId)).resolve(versionId).resolve(relative).normalize();
    ensureUnderRoot(path);
    return readFile(path);
  }

  List<ResolvedVisual> resolvedVisuals() {
    return List.copyOf(resolved.values());
  }

  Optional<ResolvedVisual> resolve(String itemId) {
    return Optional.ofNullable(resolved.get(
        itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT)));
  }

  private ValidatedPack validate(Path zipPath) throws Exception {
    try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
      if (zip.size() > MAX_FILES) {
        throw new ServiceException("visual_pack_invalid", "Visual pack contains too many files");
      }
      long total = 0L;
      Map<String, ZipEntry> files = new HashMap<>();
      var enumeration = zip.entries();
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        String name = entry.getName().replace('\\', '/');
        validateZipPath(name);
        if (entry.isDirectory()) {
          continue;
        }
        if (!(name.equals("manifest.json") || OPTIONAL_FILES.contains(name)
            || name.matches("icons/[a-z0-9._/-]+\\.png"))) {
          throw new ServiceException("visual_pack_invalid", "Unsupported file: " + name);
        }
        long size = entry.getSize();
        if (size < 0 || size > MAX_UNCOMPRESSED_BYTES) {
          throw new ServiceException("visual_pack_invalid", "Invalid ZIP entry size");
        }
        total += size;
        if (total > MAX_UNCOMPRESSED_BYTES || files.put(name, entry) != null) {
          throw new ServiceException("visual_pack_invalid", "Visual pack is too large or duplicated");
        }
      }
      ZipEntry manifestEntry = files.get("manifest.json");
      if (manifestEntry == null || manifestEntry.getSize() > 4 * 1024 * 1024) {
        throw new ServiceException("visual_pack_invalid", "Missing or oversized manifest.json");
      }
      JsonObject manifest;
      try (var reader = new java.io.InputStreamReader(
          zip.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
        manifest = JsonParser.parseReader(reader).getAsJsonObject();
      }
      if (!manifest.has("schemaVersion") || manifest.get("schemaVersion").getAsInt() != 1) {
        throw new ServiceException("visual_pack_invalid", "Unsupported schemaVersion");
      }
      JsonObject pack = manifest.getAsJsonObject("pack");
      String packId = normalizePackId(pack.get("id").getAsString());
      String packName = pack.has("name") ? pack.get("name").getAsString().trim() : packId;
      if (packName.isBlank() || packName.length() > 255) {
        throw new ServiceException("visual_pack_invalid", "Invalid visual pack name");
      }
      JsonArray entries = manifest.getAsJsonArray("entries");
      if (entries == null || entries.size() == 0 || entries.size() > MAX_ENTRIES) {
        throw new ServiceException("visual_pack_invalid", "Invalid visual entry count");
      }
      Map<String, VisualEntry> validatedEntries = new LinkedHashMap<>();
      for (JsonElement element : entries) {
        JsonObject row = element.getAsJsonObject();
        String itemId = row.get("itemId").getAsString().toLowerCase(Locale.ROOT);
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
          throw new ServiceException("visual_pack_invalid", "Invalid itemId: " + itemId);
        }
        String icon = row.get("icon").getAsString().replace('\\', '/');
        if (!icon.matches("icons/[a-z0-9._/-]+\\.png") || icon.contains("..")) {
          throw new ServiceException("visual_pack_invalid", "Invalid icon path");
        }
        ZipEntry iconEntry = files.get(icon);
        if (iconEntry == null || iconEntry.getSize() <= 0 || iconEntry.getSize() > MAX_ICON_BYTES) {
          throw new ServiceException("visual_pack_invalid", "Missing or oversized icon: " + icon);
        }
        byte[] bytes = zip.getInputStream(iconEntry).readAllBytes();
        String expected = row.get("sha256").getAsString().replaceFirst("^sha256:", "");
        if (!sha256(bytes).equalsIgnoreCase(expected)) {
          throw new ServiceException("visual_pack_invalid", "Icon hash mismatch: " + icon);
        }
        validatePng(bytes, icon);
        if (validatedEntries.putIfAbsent(itemId, new VisualEntry(itemId, icon)) != null) {
          throw new ServiceException("visual_pack_invalid", "Duplicate itemId: " + itemId);
        }
      }
      return new ValidatedPack(packId, packName, manifest, List.copyOf(validatedEntries.values()));
    }
  }

  private void extractValidated(Path zipPath, Path destination, ValidatedPack pack) throws IOException {
    try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
      Set<String> allowed = new java.util.HashSet<>();
      allowed.add("manifest.json");
      allowed.addAll(OPTIONAL_FILES);
      pack.entries().forEach(entry -> allowed.add(entry.iconPath()));
      for (String name : allowed) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
          continue;
        }
        Path target = destination.resolve(name).normalize();
        ensureUnderRoot(target);
        Files.createDirectories(target.getParent());
        try (var input = zip.getInputStream(entry)) {
          Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private void upsert(
      ValidatedPack pack, String versionId, long fileSize, String uploadedBy) {
    databaseManager.inTransaction(connection -> {
      int order = 0;
      try (PreparedStatement statement =
               connection.prepareStatement("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM visual_packs");
           ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          order = resultSet.getInt(1);
        }
      }
      boolean sqlite = databaseManager.dbType().isSqlite();
      String sql = sqlite ? """
          INSERT INTO visual_packs (
            pack_id, pack_name, version_id, enabled, sort_order, icons_enabled,
            translations_enabled, manifest_json, file_size, entry_count, uploaded_by
          ) VALUES (?, ?, ?, FALSE, ?, TRUE, FALSE, ?, ?, ?, ?)
          ON CONFLICT(pack_id) DO UPDATE SET
            pack_name = excluded.pack_name, version_id = excluded.version_id,
            manifest_json = excluded.manifest_json, file_size = excluded.file_size,
            entry_count = excluded.entry_count, uploaded_by = excluded.uploaded_by,
            updated_at = CURRENT_TIMESTAMP
          """ : """
          INSERT INTO visual_packs (
            pack_id, pack_name, version_id, enabled, sort_order, icons_enabled,
            translations_enabled, manifest_json, file_size, entry_count, uploaded_by
          ) VALUES (?, ?, ?, FALSE, ?, TRUE, FALSE, ?, ?, ?, ?)
          ON DUPLICATE KEY UPDATE
            pack_name = VALUES(pack_name), version_id = VALUES(version_id),
            manifest_json = VALUES(manifest_json), file_size = VALUES(file_size),
            entry_count = VALUES(entry_count), uploaded_by = VALUES(uploaded_by),
            updated_at = CURRENT_TIMESTAMP
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, pack.packId());
        statement.setString(2, pack.packName());
        statement.setString(3, versionId);
        statement.setInt(4, order);
        statement.setString(5, gson.toJson(pack.manifest()));
        statement.setLong(6, fileSize);
        statement.setInt(7, pack.entries().size());
        statement.setString(8, uploadedBy);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private synchronized void rebuildIndex() {
    Map<String, ResolvedVisual> next = new LinkedHashMap<>();
    for (PackRecord pack : list()) {
      if (!pack.enabled() || !pack.iconsEnabled()) {
        continue;
      }
      JsonArray entries = JsonParser.parseString(pack.manifestJson())
          .getAsJsonObject().getAsJsonArray("entries");
      for (JsonElement element : entries) {
        JsonObject row = element.getAsJsonObject();
        String itemId = row.get("itemId").getAsString().toLowerCase(Locale.ROOT);
        next.putIfAbsent(itemId, new ResolvedVisual(
            itemId,
            "/visual-packs/" + pack.packId() + "/" + pack.versionId() + "/"
                + row.get("icon").getAsString(),
            pack.packId()));
      }
    }
    resolved = Map.copyOf(next);
  }

  private PackRecord read(ResultSet resultSet) throws java.sql.SQLException {
    Timestamp created = resultSet.getTimestamp("created_at");
    Timestamp updated = resultSet.getTimestamp("updated_at");
    return new PackRecord(
        resultSet.getString("pack_id"),
        resultSet.getString("pack_name"),
        resultSet.getString("version_id"),
        resultSet.getBoolean("enabled"),
        resultSet.getInt("sort_order"),
        resultSet.getBoolean("icons_enabled"),
        resultSet.getBoolean("translations_enabled"),
        resultSet.getString("manifest_json"),
        resultSet.getLong("file_size"),
        resultSet.getInt("entry_count"),
        resultSet.getString("uploaded_by"),
        created == null ? null : created.toLocalDateTime(),
        updated == null ? null : updated.toLocalDateTime());
  }

  private static void validatePng(byte[] bytes, String path) throws IOException {
    if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P'
        || bytes[2] != 'N' || bytes[3] != 'G') {
      throw new ServiceException("visual_pack_invalid", "Icon is not PNG: " + path);
    }
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
    if (image == null || image.getWidth() < 1 || image.getHeight() < 1
        || image.getWidth() > 1024 || image.getHeight() > 1024) {
      throw new ServiceException("visual_pack_invalid", "Invalid icon dimensions: " + path);
    }
  }

  private static void validateZipPath(String name) {
    if (name.isBlank() || name.startsWith("/") || name.contains("../")
        || name.contains(":/") || name.indexOf('\0') >= 0) {
      throw new ServiceException("visual_pack_invalid", "Unsafe ZIP path");
    }
  }

  private static String normalizePackId(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
      throw new ServiceException("bad_request", "Invalid visual pack id");
    }
    return normalized;
  }

  private void ensureUnderRoot(Path path) {
    if (!path.toAbsolutePath().normalize().startsWith(root)) {
      throw new ServiceException("bad_request", "Unsafe visual pack path");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Optional<byte[]> readFile(Path path) {
    try {
      return Files.isRegularFile(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty();
    } catch (IOException exception) {
      return Optional.empty();
    }
  }

  private void deleteTreeQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Path normalized = path.toAbsolutePath().normalize();
      ensureUnderRoot(normalized);
      if (!Files.exists(normalized)) {
        return;
      }
      try (var stream = Files.walk(normalized)) {
        stream.sorted(Comparator.reverseOrder()).forEach(entry -> {
          try {
            Files.deleteIfExists(entry);
          } catch (IOException exception) {
            plugin.getLogger().warning("Could not delete visual pack file: " + entry);
          }
        });
      }
    } catch (Exception exception) {
      plugin.getLogger().warning("Could not clean visual pack directory: " + exception.getMessage());
    }
  }

  record PackRecord(
      String packId,
      String packName,
      String versionId,
      boolean enabled,
      int sortOrder,
      boolean iconsEnabled,
      boolean translationsEnabled,
      String manifestJson,
      long fileSize,
      int entryCount,
      String uploadedBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
  }

  record ResolvedVisual(String itemId, String iconPath, String packId) {
  }

  private record VisualEntry(String itemId, String iconPath) {
  }

  private record ValidatedPack(
      String packId, String packName, JsonObject manifest, List<VisualEntry> entries) {
  }
}
