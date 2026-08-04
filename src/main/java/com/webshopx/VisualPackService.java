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
  private static final String BUILTIN_PACK_RESOURCE =
      "visual-packs/builtin-minecraft-languages.zip";
  private static final String BUILTIN_PACK_ID =
      "minecraft-1.21.10-minecraft-languages";
  private static final String BUILTIN_UPLOADER = "builtin";
  static final int MAX_UPLOAD_BYTES = 64 * 1024 * 1024;
  private static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
  private static final int MAX_FILES = 12_000;
  private static final int MAX_ENTRIES = 10_000;
  private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
  private static final int MAX_LOCALES = 256;
  private static final int MAX_TRANSLATION_BYTES = 8 * 1024 * 1024;
  private static final Set<String> OPTIONAL_FILES = Set.of(
      "metadata/export-report.json", "metadata/languages.json");

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
    installBundledLanguagePack();
    rebuildIndex();
  }

  private void installBundledLanguagePack() {
    try (var input = plugin.getResource(BUILTIN_PACK_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException(
            "Missing bundled language pack: " + BUILTIN_PACK_RESOURCE);
      }
      byte[] bytes = input.readAllBytes();
      String versionId = sha256(bytes).substring(0, 24);
      PackRecord current = find(BUILTIN_PACK_ID).orElse(null);
      if (current == null || !versionId.equals(current.versionId())
          || !BUILTIN_UPLOADER.equals(current.uploadedBy())) {
        install(bytes, BUILTIN_UPLOADER);
      }
      updateState(BUILTIN_PACK_ID, true, false, true);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not install bundled language pack", exception);
    }
  }

  synchronized PackRecord install(byte[] zipBytes, String uploadedBy) {
    return install(zipBytes, uploadedBy, MAX_UNCOMPRESSED_BYTES);
  }

  synchronized PackRecord install(byte[] zipBytes, String uploadedBy, long maxUncompressedBytes) {
    if (zipBytes.length == 0 || zipBytes.length > MAX_UPLOAD_BYTES) {
      throw new ServiceException("bad_request", "Visual pack must be between 1 byte and 64 MiB");
    }
    String versionId = sha256(zipBytes).substring(0, 24);
    Path temporary = null;
    Path versionDirectory = null;
    try {
      temporary = Files.createTempFile(root, ".upload-", ".zip");
      Files.write(temporary, zipBytes);
      ValidatedPack validated = validate(temporary, Math.min(MAX_UNCOMPRESSED_BYTES, Math.max(1, maxUncompressedBytes)));
      if (BUILTIN_PACK_ID.equals(validated.packId())
          && !BUILTIN_UPLOADER.equals(uploadedBy)) {
        throw new ServiceException("bad_request", "Built-in language pack cannot be replaced");
      }
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
    if (BUILTIN_PACK_ID.equals(current.packId())
        && (Boolean.FALSE.equals(enabled)
            || Boolean.TRUE.equals(iconsEnabled)
            || Boolean.FALSE.equals(translationsEnabled))) {
      throw new ServiceException("bad_request", "Built-in language pack must remain enabled");
    }
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
    if (BUILTIN_PACK_ID.equals(normalizePackId(packId))) {
      return;
    }
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
    if (BUILTIN_PACK_ID.equals(current.packId())) {
      throw new ServiceException("bad_request", "Built-in language pack cannot be deleted");
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

  List<String> availableTranslationLocales() {
    java.util.TreeSet<String> locales = new java.util.TreeSet<>();
    for (PackRecord pack : list()) {
      if (!pack.enabled() || !pack.translationsEnabled()) {
        continue;
      }
      JsonObject manifest = JsonParser.parseString(pack.manifestJson()).getAsJsonObject();
      JsonArray rows = manifest.getAsJsonArray("locales");
      if (rows == null) {
        continue;
      }
      for (JsonElement row : rows) {
        locales.add(normalizeLocale(row.getAsString()));
      }
    }
    return List.copyOf(locales);
  }

  Map<String, LanguageDescriptor> availableLanguageMetadata() {
    Map<String, LanguageDescriptor> metadata = new LinkedHashMap<>();
    List<PackRecord> packs = new ArrayList<>(list());
    packs.sort(Comparator
        .comparing((PackRecord pack) -> BUILTIN_UPLOADER.equals(pack.uploadedBy()))
        .thenComparingInt(PackRecord::sortOrder)
        .thenComparing(PackRecord::packId));
    for (PackRecord pack : packs) {
      if (!pack.enabled() || !pack.translationsEnabled()) {
        continue;
      }
      Path path = root.resolve(pack.packId()).resolve(pack.versionId())
          .resolve("metadata").resolve("languages.json");
      readJsonFile(path).ifPresent(languages -> languages.entrySet().forEach(entry -> {
        String locale = normalizeLocale(entry.getKey());
        if (metadata.containsKey(locale) || !entry.getValue().isJsonObject()) {
          return;
        }
        JsonObject row = entry.getValue().getAsJsonObject();
        String name = row.has("name") ? row.get("name").getAsString().trim() : "";
        String region = row.has("region") ? row.get("region").getAsString().trim() : "";
        boolean bidirectional =
            row.has("bidirectional") && row.get("bidirectional").getAsBoolean();
        if (!name.isBlank() || !region.isBlank()) {
          metadata.put(locale, new LanguageDescriptor(name, region, bidirectional));
        }
      }));
    }
    return Map.copyOf(metadata);
  }

  Map<String, ResolvedEnchantment> resolvedEnchantments(String requestedLocale) {
    String locale = normalizeLocale(requestedLocale);
    Map<String, ResolvedEnchantmentBuilder> builders = new LinkedHashMap<>();
    List<PackRecord> packs = new ArrayList<>(list());
    packs.sort(Comparator
        .comparing((PackRecord pack) -> BUILTIN_UPLOADER.equals(pack.uploadedBy()))
        .thenComparingInt(PackRecord::sortOrder)
        .thenComparing(PackRecord::packId));
    for (PackRecord pack : packs) {
      if (!pack.enabled() || !pack.translationsEnabled()) {
        continue;
      }
      Path base = root.resolve(pack.packId()).resolve(pack.versionId()).resolve("enchantments");
      readJsonFile(base.resolve(locale + ".json"))
          .ifPresent(rows -> mergeEnchantments(builders, rows, false));
      readJsonFile(base.resolve("en_us.json"))
          .ifPresent(rows -> mergeEnchantments(builders, rows, true));
    }
    Map<String, ResolvedEnchantment> resolvedEnchantments = new LinkedHashMap<>();
    builders.forEach((id, builder) -> resolvedEnchantments.put(id, builder.build()));
    return Map.copyOf(resolvedEnchantments);
  }

  private static void mergeEnchantments(
      Map<String, ResolvedEnchantmentBuilder> builders,
      JsonObject rows,
      boolean english) {
    rows.entrySet().forEach(entry -> {
      if (!entry.getValue().isJsonObject()) {
        return;
      }
      String id = entry.getKey().toLowerCase(Locale.ROOT);
      JsonObject row = entry.getValue().getAsJsonObject();
      ResolvedEnchantmentBuilder builder =
          builders.computeIfAbsent(id, ignored -> new ResolvedEnchantmentBuilder(id));
      String name = row.has("name") ? row.get("name").getAsString().trim() : "";
      String description =
          row.has("description") ? row.get("description").getAsString().trim() : "";
      if (english) {
        if (builder.englishName == null && !name.isBlank()) {
          builder.englishName = name;
        }
        if (builder.englishDescription == null && !description.isBlank()) {
          builder.englishDescription = description;
        }
      } else {
        if (builder.name == null && !name.isBlank()) {
          builder.name = name;
        }
        if (builder.description == null && !description.isBlank()) {
          builder.description = description;
        }
      }
    });
  }

  Optional<ResolvedVisual> resolve(String itemId) {
    return Optional.ofNullable(resolved.get(
        itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT)));
  }

  private ValidatedPack validate(Path zipPath, long maxUncompressedBytes) throws Exception {
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
        if (!(name.equals("manifest.json") || name.equals("catalog/items.json")
            || name.equals("catalog/enchantments.json")
            || OPTIONAL_FILES.contains(name)
            || name.matches("icons/[a-z0-9._/-]+\\.png")
            || name.matches("enchantments/[a-z0-9_]{2,32}\\.json")
            || name.matches("translations/[a-z0-9_]{2,32}\\.json"))) {
          throw new ServiceException("visual_pack_invalid", "Unsupported file: " + name);
        }
        long size = entry.getSize();
        if (size < 0 || size > maxUncompressedBytes) {
          throw new ServiceException("visual_pack_invalid", "Invalid ZIP entry size");
        }
        total += size;
        if (total > maxUncompressedBytes || files.put(name, entry) != null) {
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
      if (!manifest.has("schemaVersion") || manifest.get("schemaVersion").getAsInt() != 2) {
        throw new ServiceException("visual_pack_invalid", "Unsupported schemaVersion");
      }
      JsonObject content = manifest.has("content") && manifest.get("content").isJsonObject()
          ? manifest.getAsJsonObject("content") : null;
      boolean includesIcons =
          content == null || !content.has("icons") || content.get("icons").getAsBoolean();
      boolean includesTranslations =
          content == null || !content.has("translations")
              || content.get("translations").getAsBoolean();
      if (!includesIcons && !includesTranslations) {
        throw new ServiceException("visual_pack_invalid", "Visual pack contains no usable content");
      }
      if (!includesIcons && files.keySet().stream().anyMatch(name -> name.startsWith("icons/"))) {
        throw new ServiceException("visual_pack_invalid", "Language-only pack contains icons");
      }
      if (!includesTranslations
          && files.keySet().stream().anyMatch(name -> name.startsWith("translations/"))) {
        throw new ServiceException("visual_pack_invalid", "Icon-only pack contains translations");
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
      JsonArray localeArray = manifest.getAsJsonArray("locales");
      if (localeArray == null || localeArray.size() > MAX_LOCALES
          || (includesTranslations && localeArray.size() == 0)
          || (!includesTranslations && localeArray.size() != 0)) {
        throw new ServiceException("visual_pack_invalid", "Invalid locale count");
      }
      List<String> locales = new ArrayList<>();
      for (JsonElement element : localeArray) {
        String locale = normalizeLocale(element.getAsString());
        if (locales.contains(locale)) {
          throw new ServiceException("visual_pack_invalid", "Duplicate locale: " + locale);
        }
        locales.add(locale);
      }
      ZipEntry languagesEntry = files.get("metadata/languages.json");
      if (languagesEntry != null) {
        if (!includesTranslations || languagesEntry.getSize() <= 0
            || languagesEntry.getSize() > MAX_TRANSLATION_BYTES) {
          throw new ServiceException("visual_pack_invalid", "Invalid language metadata");
        }
        JsonObject languageMetadata =
            readJsonObject(zip, languagesEntry, "metadata/languages.json");
        for (Map.Entry<String, JsonElement> metadataEntry : languageMetadata.entrySet()) {
          String locale = normalizeLocale(metadataEntry.getKey());
          if (!locales.contains(locale) || !metadataEntry.getValue().isJsonObject()) {
            throw new ServiceException("visual_pack_invalid",
                "Invalid language metadata locale: " + metadataEntry.getKey());
          }
          JsonObject row = metadataEntry.getValue().getAsJsonObject();
          String name = row.has("name") ? row.get("name").getAsString().trim() : "";
          String region = row.has("region") ? row.get("region").getAsString().trim() : "";
          if (name.length() > 128 || region.length() > 128
              || (row.has("bidirectional") && !row.get("bidirectional").isJsonPrimitive())) {
            throw new ServiceException("visual_pack_invalid",
                "Invalid language metadata: " + metadataEntry.getKey());
          }
        }
      }
      ZipEntry enchantmentCatalogEntry = files.get("catalog/enchantments.json");
      if (enchantmentCatalogEntry != null) {
        if (!includesTranslations || enchantmentCatalogEntry.getSize() < 0
            || enchantmentCatalogEntry.getSize() > MAX_TRANSLATION_BYTES) {
          throw new ServiceException("visual_pack_invalid", "Invalid enchantment catalog");
        }
        JsonObject enchantmentCatalog =
            readJsonObject(zip, enchantmentCatalogEntry, "catalog/enchantments.json");
        for (Map.Entry<String, JsonElement> enchantment : enchantmentCatalog.entrySet()) {
          if (!enchantment.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
              || !enchantment.getValue().isJsonObject()) {
            throw new ServiceException("visual_pack_invalid",
                "Invalid enchantment catalog entry: " + enchantment.getKey());
          }
        }
        for (String locale : locales) {
          String path = "enchantments/" + locale + ".json";
          ZipEntry entry = files.get(path);
          if (entry == null || entry.getSize() < 0
              || entry.getSize() > MAX_TRANSLATION_BYTES) {
            throw new ServiceException("visual_pack_invalid",
                "Missing enchantment translation file: " + locale);
          }
          JsonObject rows = readJsonObject(zip, entry, path);
          for (Map.Entry<String, JsonElement> enchantment : rows.entrySet()) {
            if (!enchantmentCatalog.has(enchantment.getKey())
                || !enchantment.getValue().isJsonObject()) {
              throw new ServiceException("visual_pack_invalid",
                  "Unknown translated enchantment: " + enchantment.getKey());
            }
          }
        }
      } else if (files.keySet().stream().anyMatch(name -> name.startsWith("enchantments/"))) {
        throw new ServiceException("visual_pack_invalid",
            "Enchantment translations require a catalog");
      }

      ZipEntry catalogEntry = files.get("catalog/items.json");
      if (catalogEntry == null || catalogEntry.getSize() <= 0
          || catalogEntry.getSize() > MAX_TRANSLATION_BYTES) {
        throw new ServiceException("visual_pack_invalid", "Missing or oversized item catalog");
      }
      JsonObject catalog = readJsonObject(zip, catalogEntry, "catalog/items.json");
      Map<String, VisualEntry> validatedEntries = new LinkedHashMap<>();
      for (JsonElement element : entries) {
        JsonObject row = element.getAsJsonObject();
        String itemId = row.get("itemId").getAsString().toLowerCase(Locale.ROOT);
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
          throw new ServiceException("visual_pack_invalid", "Invalid itemId: " + itemId);
        }
        String icon = null;
        if (includesIcons) {
          if (!row.has("icon") || !row.has("sha256")) {
            throw new ServiceException("visual_pack_invalid", "Missing icon metadata");
          }
          icon = row.get("icon").getAsString().replace('\\', '/');
          if (!icon.matches("icons/[a-z0-9._/-]+\\.png") || icon.contains("..")) {
            throw new ServiceException("visual_pack_invalid", "Invalid icon path");
          }
          ZipEntry iconEntry = files.get(icon);
          if (iconEntry == null || iconEntry.getSize() <= 0
              || iconEntry.getSize() > MAX_ICON_BYTES) {
            throw new ServiceException("visual_pack_invalid", "Missing or oversized icon: " + icon);
          }
          byte[] bytes = zip.getInputStream(iconEntry).readAllBytes();
          String expected = row.get("sha256").getAsString().replaceFirst("^sha256:", "");
          if (!sha256(bytes).equalsIgnoreCase(expected)) {
            throw new ServiceException("visual_pack_invalid", "Icon hash mismatch: " + icon);
          }
          validatePng(bytes, icon);
        } else if (row.has("icon") || row.has("sha256")) {
          throw new ServiceException(
              "visual_pack_invalid", "Language-only pack contains icon metadata");
        }
        String translationKey = row.get("translationKey").getAsString().trim();
        if (!translationKey.matches("[a-zA-Z0-9_.:/-]{1,255}")) {
          throw new ServiceException("visual_pack_invalid", "Invalid translation key");
        }
        JsonObject catalogRow = catalog.has(itemId) && catalog.get(itemId).isJsonObject()
            ? catalog.getAsJsonObject(itemId) : null;
        if (catalogRow == null
            || !translationKey.equals(catalogRow.get("translationKey").getAsString())) {
          throw new ServiceException("visual_pack_invalid", "Catalog mismatch: " + itemId);
        }
        if (validatedEntries.putIfAbsent(
            itemId, new VisualEntry(itemId, icon, translationKey)) != null) {
          throw new ServiceException("visual_pack_invalid", "Duplicate itemId: " + itemId);
        }
      }
      if (catalog.size() != validatedEntries.size()) {
        throw new ServiceException("visual_pack_invalid", "Catalog entry count mismatch");
      }
      Map<String, Map<String, String>> translations = new LinkedHashMap<>();
      for (String locale : locales) {
        String path = "translations/" + locale + ".json";
        ZipEntry translationEntry = files.get(path);
        if (translationEntry == null || translationEntry.getSize() < 0
            || translationEntry.getSize() > MAX_TRANSLATION_BYTES) {
          throw new ServiceException("visual_pack_invalid", "Missing translation file: " + locale);
        }
        JsonObject language = readJsonObject(zip, translationEntry, path);
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> translated : language.entrySet()) {
          String itemId = translated.getKey().toLowerCase(Locale.ROOT);
          if (!validatedEntries.containsKey(itemId) || !translated.getValue().isJsonPrimitive()) {
            throw new ServiceException("visual_pack_invalid",
                "Unknown translated item: " + translated.getKey());
          }
          String name = translated.getValue().getAsString().trim();
          if (name.isBlank() || name.length() > 512) {
            throw new ServiceException("visual_pack_invalid", "Invalid translated item name");
          }
          names.put(itemId, name);
        }
        translations.put(locale, Map.copyOf(names));
      }
      return new ValidatedPack(
          packId, packName, manifest, List.copyOf(validatedEntries.values()),
          List.copyOf(locales), Map.copyOf(translations),
          includesIcons, includesTranslations);
    }
  }

  private void extractValidated(Path zipPath, Path destination, ValidatedPack pack) throws IOException {
    try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
      Set<String> allowed = new java.util.HashSet<>();
      allowed.add("manifest.json");
      allowed.add("catalog/items.json");
      if (zip.getEntry("catalog/enchantments.json") != null) {
        allowed.add("catalog/enchantments.json");
        pack.locales().forEach(locale -> allowed.add("enchantments/" + locale + ".json"));
      }
      allowed.addAll(OPTIONAL_FILES);
      if (pack.includesIcons()) {
        pack.entries().forEach(entry -> allowed.add(entry.iconPath()));
      }
      pack.locales().forEach(locale -> allowed.add("translations/" + locale + ".json"));
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
          ) VALUES (?, ?, ?, FALSE, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(pack_id) DO UPDATE SET
            pack_name = excluded.pack_name, version_id = excluded.version_id,
            icons_enabled = excluded.icons_enabled,
            translations_enabled = excluded.translations_enabled,
            manifest_json = excluded.manifest_json, file_size = excluded.file_size,
            entry_count = excluded.entry_count, uploaded_by = excluded.uploaded_by,
            updated_at = CURRENT_TIMESTAMP
          """ : """
          INSERT INTO visual_packs (
            pack_id, pack_name, version_id, enabled, sort_order, icons_enabled,
            translations_enabled, manifest_json, file_size, entry_count, uploaded_by
          ) VALUES (?, ?, ?, FALSE, ?, ?, ?, ?, ?, ?, ?)
          ON DUPLICATE KEY UPDATE
            pack_name = VALUES(pack_name), version_id = VALUES(version_id),
            icons_enabled = VALUES(icons_enabled),
            translations_enabled = VALUES(translations_enabled),
            manifest_json = VALUES(manifest_json), file_size = VALUES(file_size),
            entry_count = VALUES(entry_count), uploaded_by = VALUES(uploaded_by),
            updated_at = CURRENT_TIMESTAMP
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, pack.packId());
        statement.setString(2, pack.packName());
        statement.setString(3, versionId);
        statement.setInt(4, order);
        statement.setBoolean(5, pack.includesIcons());
        statement.setBoolean(6, pack.includesTranslations());
        statement.setString(7, gson.toJson(pack.manifest()));
        statement.setLong(8, fileSize);
        statement.setInt(9, pack.entries().size());
        statement.setString(10, uploadedBy);
        statement.executeUpdate();
      }
      return null;
    });
  }

  private synchronized void rebuildIndex() {
    Map<String, ResolvedVisualBuilder> builders = new LinkedHashMap<>();
    List<PackRecord> packs = new ArrayList<>(list());
    packs.sort(Comparator
        .comparing((PackRecord pack) -> BUILTIN_UPLOADER.equals(pack.uploadedBy()))
        .thenComparingInt(PackRecord::sortOrder)
        .thenComparing(PackRecord::packId));
    for (PackRecord pack : packs) {
      if (!pack.enabled() || (!pack.iconsEnabled() && !pack.translationsEnabled())) {
        continue;
      }
      JsonObject manifest = JsonParser.parseString(pack.manifestJson()).getAsJsonObject();
      JsonArray entries = manifest.getAsJsonArray("entries");
      for (JsonElement element : entries) {
        JsonObject row = element.getAsJsonObject();
        String itemId = row.get("itemId").getAsString().toLowerCase(Locale.ROOT);
        ResolvedVisualBuilder builder =
            builders.computeIfAbsent(itemId, ignored -> new ResolvedVisualBuilder(itemId));
        if (pack.iconsEnabled() && row.has("icon") && builder.iconPath == null) {
          builder.iconPath = "/visual-packs/" + pack.packId() + "/" + pack.versionId() + "/"
              + row.get("icon").getAsString();
          builder.packId = pack.packId();
        }
        if (pack.translationsEnabled() && builder.translationKey == null) {
          builder.translationKey = row.get("translationKey").getAsString();
          if (builder.packId == null) {
            builder.packId = pack.packId();
          }
        }
      }
      if (pack.translationsEnabled()) {
        JsonArray locales = manifest.getAsJsonArray("locales");
        for (JsonElement localeElement : locales) {
          String locale = normalizeLocale(localeElement.getAsString());
          Path path = root.resolve(pack.packId()).resolve(pack.versionId())
              .resolve("translations").resolve(locale + ".json");
          readJsonFile(path).ifPresent(language -> language.entrySet().forEach(entry -> {
            String itemId = entry.getKey().toLowerCase(Locale.ROOT);
            ResolvedVisualBuilder builder = builders.get(itemId);
            if (builder != null && !builder.localizedNames.containsKey(locale)) {
              builder.localizedNames.put(locale, entry.getValue().getAsString());
            }
          }));
        }
      }
    }
    Map<String, ResolvedVisual> next = new LinkedHashMap<>();
    builders.forEach((itemId, builder) -> next.put(itemId, builder.build()));
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

  private static JsonObject readJsonObject(ZipFile zip, ZipEntry entry, String path)
      throws IOException {
    try (var reader = new java.io.InputStreamReader(
        zip.getInputStream(entry), StandardCharsets.UTF_8)) {
      JsonElement parsed = JsonParser.parseReader(reader);
      if (!parsed.isJsonObject()) {
        throw new ServiceException("visual_pack_invalid", "Expected JSON object: " + path);
      }
      return parsed.getAsJsonObject();
    } catch (com.google.gson.JsonParseException | IllegalStateException exception) {
      throw new ServiceException("visual_pack_invalid", "Invalid JSON: " + path);
    }
  }

  private static Optional<JsonObject> readJsonFile(Path path) {
    try {
      if (!Files.isRegularFile(path)) {
        return Optional.empty();
      }
      JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
      return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
    } catch (Exception ignored) {
      return Optional.empty();
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

  private static String normalizeLocale(String value) {
    String normalized = value == null
        ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    if (!normalized.matches("[a-z0-9_]{2,32}")) {
      throw new ServiceException("visual_pack_invalid", "Invalid locale: " + value);
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

  record ResolvedVisual(
      String itemId,
      String iconPath,
      String packId,
      String translationKey,
      Map<String, String> localizedNames) {
  }

  record LanguageDescriptor(String name, String region, boolean bidirectional) {
  }

  record ResolvedEnchantment(
      String enchantmentId,
      String name,
      String description,
      String englishName,
      String englishDescription) {
  }

  private static final class ResolvedEnchantmentBuilder {
    private final String enchantmentId;
    private String name;
    private String description;
    private String englishName;
    private String englishDescription;

    private ResolvedEnchantmentBuilder(String enchantmentId) {
      this.enchantmentId = enchantmentId;
    }

    private ResolvedEnchantment build() {
      return new ResolvedEnchantment(
          enchantmentId, name, description, englishName, englishDescription);
    }
  }

  private record VisualEntry(String itemId, String iconPath, String translationKey) {
  }

  private record ValidatedPack(
      String packId,
      String packName,
      JsonObject manifest,
      List<VisualEntry> entries,
      List<String> locales,
      Map<String, Map<String, String>> translations,
      boolean includesIcons,
      boolean includesTranslations) {
  }

  private static final class ResolvedVisualBuilder {
    private final String itemId;
    private String iconPath;
    private String packId;
    private String translationKey;
    private final Map<String, String> localizedNames = new LinkedHashMap<>();

    private ResolvedVisualBuilder(String itemId) {
      this.itemId = itemId;
    }

    private ResolvedVisual build() {
      return new ResolvedVisual(
          itemId, iconPath, packId, translationKey, Map.copyOf(localizedNames));
    }
  }
}
