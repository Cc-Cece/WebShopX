package com.webshopx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;

class StaticAssetInstaller {
  private static final String MANAGED_HASH_FILE = "webshopx-custom-assets.properties";
  private static final String BUILTIN_MANUAL_MD5 = "386cd67d1cc355b0a98b1271088ce9d8";
  private static final Pattern DOC_FILE_PATTERN =
      Pattern.compile("^(?<key>.+?)(?:\\.(?<locale>[A-Za-z]{2}(?:-[A-Za-z]{2})?))?$");

  private static final Map<String, Set<String>> LEGACY_EMBEDDED_HASHES = Map.of(
      "web/docs/manual.zh-CN.md", Set.of(BUILTIN_MANUAL_MD5));

  private static final List<String> ASSETS = List.of(
      "web/index.html",
      "web/admin.html",
      "web/css/light.css",
      "web/css/dark.css",
      "web/css/styles.css",
      "web/js/i18n.js",
      "web/js/app.js",
      "web/js/admin.js",
      "web/js/help.js",
      "web/i18n/materials/zh-CN.json",
      "web/i18n/materials/en-US.json",
      "web/i18n/market-algorithms/zh-CN.json",
      "web/i18n/market-algorithms/en-US.json",
      "web/vendor/marked.min.js",
      "web/vendor/purify.min.js",
      "web/vendor/katex.min.js",
      "web/vendor/auto-render.min.js",
      "web/vendor/katex.min.css");

  private static final List<String> CUSTOMIZABLE_ASSETS = List.of(
      "web/help.html",
      "web/docs/help.zh-CN.md",
      "web/docs/help.en-US.md",
      "web/docs/manual.zh-CN.md",
      "web/docs/manual.en-US.md");

  private final JavaPlugin plugin;

  StaticAssetInstaller(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  Path install(String staticRoot, PluginSettings settings) {
    Path outputRoot = plugin.getDataFolder().toPath().resolve(staticRoot);
    Path managedHashFile = plugin.getDataFolder().toPath().resolve(MANAGED_HASH_FILE);
    try {
      Files.createDirectories(outputRoot);
      Properties managedHashes = loadManagedHashes(managedHashFile);
      for (String assetPath : ASSETS) {
        copyAsset(assetPath, outputRoot);
      }
      for (String assetPath : CUSTOMIZABLE_ASSETS) {
        syncCustomizableAsset(assetPath, outputRoot, managedHashes);
      }
      saveManagedHashes(managedHashFile, managedHashes);
      writeDocsManifest(outputRoot);
      writeRuntimeConfig(outputRoot, settings);
      return outputRoot;
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to install static web assets", exception);
    }
  }

  private void copyAsset(String assetPath, Path outputRoot) throws IOException {
    String relative = assetPath.substring("web/".length());
    Path outputFile = outputRoot.resolve(relative);
    Path parent = outputFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    try (InputStream inputStream = plugin.getResource(assetPath)) {
      if (inputStream == null) {
        plugin.getLogger().log(Level.WARNING, "Missing embedded asset: {0}", assetPath);
        return;
      }
      Files.copy(inputStream, outputFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void syncCustomizableAsset(String assetPath, Path outputRoot, Properties managedHashes) throws IOException {
    String relative = assetPath.substring("web/".length());
    Path outputFile = outputRoot.resolve(relative);
    Path parent = outputFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    byte[] embeddedBytes = readEmbeddedBytes(assetPath);
    if (embeddedBytes == null) {
      return;
    }
    String embeddedHash = md5Hex(embeddedBytes);

    if (!Files.exists(outputFile)) {
      Files.write(outputFile, embeddedBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      managedHashes.setProperty(assetPath, embeddedHash);
      return;
    }

    String localHash = md5Hex(Files.readAllBytes(outputFile));
    String managedHash = normalizeHash(managedHashes.getProperty(assetPath));

    if (managedHash == null) {
      if (localHash.equals(embeddedHash) || isLegacyEmbeddedHash(assetPath, localHash)) {
        managedHash = localHash;
        managedHashes.setProperty(assetPath, managedHash);
      } else {
        return;
      }
    }

    if (!localHash.equals(managedHash)) {
      return;
    }

    if (!localHash.equals(embeddedHash)) {
      Files.write(outputFile, embeddedBytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
    managedHashes.setProperty(assetPath, embeddedHash);
  }

  private Properties loadManagedHashes(Path managedHashFile) throws IOException {
    Properties properties = new Properties();
    if (!Files.exists(managedHashFile)) {
      return properties;
    }
    try (InputStream inputStream = Files.newInputStream(managedHashFile)) {
      properties.load(inputStream);
    }
    return properties;
  }

  private void saveManagedHashes(Path managedHashFile, Properties managedHashes) throws IOException {
    Path parent = managedHashFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (OutputStream outputStream = Files.newOutputStream(
        managedHashFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)) {
      managedHashes.store(outputStream, "Managed hashes for customizable WebShopX assets");
    }
  }

  private byte[] readEmbeddedBytes(String assetPath) throws IOException {
    try (InputStream inputStream = plugin.getResource(assetPath)) {
      if (inputStream == null) {
        plugin.getLogger().log(Level.WARNING, "Missing embedded asset: {0}", assetPath);
        return null;
      }
      return inputStream.readAllBytes();
    }
  }

  private boolean isLegacyEmbeddedHash(String assetPath, String md5) {
    Set<String> candidates = LEGACY_EMBEDDED_HASHES.get(assetPath);
    return candidates != null && candidates.contains(md5);
  }

  private String normalizeHash(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("^[0-9a-f]{32}$")) {
      return null;
    }
    return normalized;
  }

  private String md5Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      byte[] hashed = digest.digest(content);
      StringBuilder builder = new StringBuilder(hashed.length * 2);
      for (byte value : hashed) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("MD5 algorithm is unavailable", exception);
    }
  }

  private void writeDocsManifest(Path outputRoot) throws IOException {
    Path docsRoot = outputRoot.resolve("docs");
    if (!Files.isDirectory(docsRoot)) {
      return;
    }

    List<Path> docFiles;
    try (Stream<Path> stream = Files.list(docsRoot)) {
      docFiles = stream
          .filter(Files::isRegularFile)
          .filter((path) -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
          .sorted(Comparator.comparing(
              (Path path) -> path.getFileName().toString(),
              String.CASE_INSENSITIVE_ORDER))
          .toList();
    }

    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"documents\": [\n");
    for (int index = 0; index < docFiles.size(); index += 1) {
      DocDescriptor descriptor = parseDocDescriptor(docFiles.get(index));
      json.append("    {\n");
      json.append("      \"id\": \"")
          .append(escapeJson(descriptor.id()))
          .append("\",\n");
      json.append("      \"key\": \"")
          .append(escapeJson(descriptor.key()))
          .append("\",\n");
      if (descriptor.locale().isBlank()) {
        json.append("      \"locale\": null,\n");
      } else {
        json.append("      \"locale\": \"")
            .append(escapeJson(descriptor.locale()))
            .append("\",\n");
      }
      json.append("      \"title\": \"")
          .append(escapeJson(descriptor.title()))
          .append("\",\n");
      json.append("      \"file\": \"")
          .append(escapeJson(descriptor.file()))
          .append("\"\n");
      json.append("    }");
      if (index < docFiles.size() - 1) {
        json.append(",");
      }
      json.append("\n");
    }
    json.append("  ]\n");
    json.append("}\n");

    Files.writeString(docsRoot.resolve("index.json"), json.toString(), StandardCharsets.UTF_8);
  }

  private DocDescriptor parseDocDescriptor(Path docPath) throws IOException {
    String fileName = docPath.getFileName().toString();
    String baseName = fileName.substring(0, fileName.length() - 3);

    Matcher matcher = DOC_FILE_PATTERN.matcher(baseName);
    String key = baseName;
    String locale = "";
    if (matcher.matches()) {
      key = matcher.group("key");
      String rawLocale = matcher.group("locale");
      if (rawLocale != null) {
        locale = rawLocale;
      }
    }

    String id = locale.isBlank() ? key : key + "." + locale;
    String headingTitle = readFirstHeading(docPath);
    String title = headingTitle.isBlank() ? id : headingTitle;
    return new DocDescriptor(id, key, locale, title, fileName);
  }

  private String readFirstHeading(Path docPath) throws IOException {
    try (Stream<String> lines = Files.lines(docPath, StandardCharsets.UTF_8)) {
      String headingLine = lines
          .map(String::trim)
          .filter((line) -> line.startsWith("#"))
          .findFirst()
          .orElse("");
      if (headingLine.isBlank()) {
        return "";
      }
      return headingLine
          .replaceFirst("^#+\\s+", "")
          .replaceFirst("\\s+\\{#[^}]+}\\s*$", "")
          .trim();
    }
  }

  private String escapeJson(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n");
  }

  private void writeRuntimeConfig(Path outputRoot, PluginSettings settings) throws IOException {
    Path outputFile = outputRoot.resolve("config.js");
    String script = "window.WEBSHOPX_CONFIG = Object.assign({}, window.WEBSHOPX_CONFIG || {}, {"
        + System.lineSeparator()
        + "  apiBaseUrl: \"" + escapeJs(settings.apiBaseUrl()) + "\","
        + System.lineSeparator()
        + "  serverMode: \"" + settings.serverMode().name() + "\","
        + System.lineSeparator()
        + "  defaultLocale: \"" + escapeJs(settings.defaultLocale()) + "\","
        + System.lineSeparator()
        + "  docsManifest: \"docs/index.json\","
        + System.lineSeparator()
        + "  helpDefaultDoc: \"manual\","
        + System.lineSeparator()
        + "  supportedLocales: [\"zh-CN\", \"en-US\"]"
        + System.lineSeparator()
        + "});"
        + System.lineSeparator();
    Files.writeString(outputFile, script, StandardCharsets.UTF_8);
  }

  private String escapeJs(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }

  private record DocDescriptor(String id, String key, String locale, String title, String file) {
  }
}
