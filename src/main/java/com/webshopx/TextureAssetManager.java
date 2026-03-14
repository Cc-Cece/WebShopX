package com.webshopx;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Downloads and extracts Minecraft textures from the matching client JAR.
 */
class TextureAssetManager {
  private static final String CLIENT_JAR_URL =
      "https://bmclapi2.bangbang93.com/version/%s/client";
  private static final String ITEM_PREFIX = "assets/minecraft/textures/item/";
  private static final String BLOCK_PREFIX = "assets/minecraft/textures/block/";

  private final JavaPlugin plugin;
  private final HttpClient httpClient;

  TextureAssetManager(JavaPlugin plugin) {
    this.plugin = plugin;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  void ensureLocalTextureCache(Path staticRoot, String minecraftVersion) {
    if (minecraftVersion == null || minecraftVersion.isBlank()) {
      plugin.getLogger().warning("Skip texture cache: missing minecraft version.");
      return;
    }

    Path textureRoot = staticRoot.resolve("textures");
    Path markerFile = textureRoot.resolve(".version");
    if (isCacheReady(textureRoot, markerFile, minecraftVersion)) {
      return;
    }

    try {
      recreateDirectory(textureRoot);
      Path clientJar = downloadClientJar(minecraftVersion);
      try {
        ExtractionStats stats = extractTextureFiles(clientJar, textureRoot);
        writeMarker(markerFile, minecraftVersion);
        plugin.getLogger().info(
            "Texture cache ready: " + stats.total() + " files (item="
                + stats.itemCount() + ", block=" + stats.blockCount() + ").");
      } finally {
        Files.deleteIfExists(clientJar);
      }
    } catch (Exception exception) {
      plugin.getLogger().warning(
          "Texture cache init failed, keep remote fallback only: " + exception.getMessage());
    }
  }

  private boolean isCacheReady(Path textureRoot, Path markerFile, String minecraftVersion) {
    if (!Files.isDirectory(textureRoot) || !Files.isRegularFile(markerFile)) {
      return false;
    }
    try {
      String cachedVersion = Files.readString(markerFile, StandardCharsets.UTF_8).trim();
      if (!cachedVersion.equals(minecraftVersion)) {
        return false;
      }
      return Files.isDirectory(textureRoot.resolve("item"))
          && Files.isDirectory(textureRoot.resolve("block"));
    } catch (IOException exception) {
      return false;
    }
  }

  private void recreateDirectory(Path directory) throws IOException {
    if (Files.exists(directory)) {
      try (Stream<Path> stream = Files.walk(directory)) {
        stream.sorted(Comparator.reverseOrder())
            .forEach(path -> {
              try {
                Files.deleteIfExists(path);
              } catch (IOException exception) {
                throw new IllegalStateException("Failed to clean old texture cache", exception);
              }
            });
      }
    }
    Files.createDirectories(directory);
  }

  private Path downloadClientJar(String minecraftVersion) throws IOException, InterruptedException {
    String url = String.format(Locale.ROOT, CLIENT_JAR_URL, minecraftVersion);
    Path tempJar = Files.createTempFile("webshopx-mc-client-", ".jar");
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(4))
        .GET()
        .build();
    HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempJar));
    if (response.statusCode() / 100 != 2) {
      Files.deleteIfExists(tempJar);
      throw new IOException("Download failed with HTTP " + response.statusCode());
    }
    return tempJar;
  }

  private ExtractionStats extractTextureFiles(Path clientJar, Path textureRoot) throws IOException {
    int itemCount = 0;
    int blockCount = 0;
    try (JarFile jarFile = new JarFile(clientJar.toFile())) {
      var entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        String relativePath = toRelativeTexturePath(entry.getName());
        if (relativePath == null) {
          continue;
        }

        Path outputPath = textureRoot.resolve(relativePath).normalize();
        if (!outputPath.startsWith(textureRoot)) {
          continue;
        }
        Path outputParent = outputPath.getParent();
        if (outputParent != null) {
          Files.createDirectories(outputParent);
        }
        try (InputStream inputStream = jarFile.getInputStream(entry)) {
          Files.copy(inputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }

        if (relativePath.startsWith("item/")) {
          itemCount++;
        } else if (relativePath.startsWith("block/")) {
          blockCount++;
        }
      }
    }
    return new ExtractionStats(itemCount, blockCount);
  }

  private String toRelativeTexturePath(String entryName) {
    if (entryName.startsWith(ITEM_PREFIX)) {
      return "item/" + entryName.substring(ITEM_PREFIX.length());
    }
    if (entryName.startsWith(BLOCK_PREFIX)) {
      return "block/" + entryName.substring(BLOCK_PREFIX.length());
    }
    return null;
  }

  private void writeMarker(Path markerFile, String minecraftVersion) throws IOException {
    Files.writeString(markerFile, minecraftVersion, StandardCharsets.UTF_8);
  }

  private record ExtractionStats(int itemCount, int blockCount) {
    int total() {
      return itemCount + blockCount;
    }
  }
}
