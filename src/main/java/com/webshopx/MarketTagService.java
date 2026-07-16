package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

class MarketTagService {
  private static final String FALLBACK_DEFAULT_TAG = "default";

  private final SqlProvider sqlProvider;
  private final RuntimeConfigService runtimeConfigService;
  private final ItemSnapshotCodec itemSnapshotCodec;
  private volatile CachedConfig cachedConfig;

  MarketTagService(
      SqlProvider sqlProvider,
      RuntimeConfigService runtimeConfigService,
      ItemSnapshotCodec itemSnapshotCodec) {
    this.sqlProvider = sqlProvider;
    this.runtimeConfigService = runtimeConfigService;
    this.itemSnapshotCodec = itemSnapshotCodec;
  }

  void invalidateCache() {
    cachedConfig = null;
  }

  TagAssignment resolveTag(
      String requestedTag,
      byte[] rawItemBlob,
      String itemMetaJson,
      String itemMaterial) {
    return resolveTags(
        requestedTag == null ? Collections.emptyList() : List.of(requestedTag),
        rawItemBlob,
        itemMetaJson,
        itemMaterial).get(0);
  }

  List<TagAssignment> resolveTags(
      List<String> requestedTags,
      byte[] rawItemBlob,
      String itemMetaJson,
      String itemMaterial) {
    TagConfig config = loadConfig();
    ItemStack itemStack = toItemStack(rawItemBlob, itemMaterial);
    LinkedHashMap<String, TagAssignment> assignments = new LinkedHashMap<>();

    if (requestedTags != null && !requestedTags.isEmpty()) {
      if (!config.playersCanSelectTags()) {
        throw new ServiceException("tag_selection_disabled", "Players cannot select tags");
      }
      for (String raw : requestedTags) {
        String requested = normalizeTagCode(raw);
        if (requested == null) {
          continue;
        }
        TagDefinition definition = config.tagsByCode().get(requested);
        if (definition == null) {
          throw new ServiceException("invalid_tag", "Tag does not exist");
        }
        if (!definition.enabled()) {
          throw new ServiceException("tag_disabled", "Tag is disabled");
        }
        if (FALLBACK_DEFAULT_TAG.equals(definition.code())) {
          continue;
        }
        assignments.putIfAbsent(
            definition.code(),
            new TagAssignment(definition.code(), config.tagVersion(), "MANUAL"));
      }
    }

    String itemMaterialName = itemStack.getType().name();
    String nbtSearchText = buildNbtSearchText(itemStack, itemMetaJson);
    for (TagDefinition definition : config.orderedTags()) {
      if (!definition.enabled()) {
        continue;
      }
      if (FALLBACK_DEFAULT_TAG.equals(definition.code())
          || (definition.materialIn().isEmpty() && definition.nbtHasAny().isEmpty())) {
        continue;
      }
      if (!definition.materialIn().isEmpty()
          && !definition.materialIn().contains(itemMaterialName)) {
        continue;
      }
      if (!definition.nbtHasAny().isEmpty()) {
        boolean matched = false;
        for (String keyword : definition.nbtHasAny()) {
          if (nbtSearchText.contains(keyword.toLowerCase(Locale.ROOT))) {
            matched = true;
            break;
          }
        }
        if (!matched) {
          continue;
        }
      }
      assignments.putIfAbsent(
          definition.code(),
          new TagAssignment(definition.code(), config.tagVersion(), "AUTO"));
    }
    if (assignments.size() > config.maxTagsPerItem()) {
      throw new ServiceException(
          "tag_limit_exceeded",
          "A listing can have at most " + config.maxTagsPerItem() + " tags");
    }
    if (assignments.isEmpty()) {
      assignments.put(
          FALLBACK_DEFAULT_TAG,
          new TagAssignment(FALLBACK_DEFAULT_TAG, config.tagVersion(), "SYSTEM"));
    }
    return List.copyOf(assignments.values());
  }

  int maxTagsPerItem() {
    return loadConfig().maxTagsPerItem();
  }

  boolean playersCanSelectTags() {
    return loadConfig().playersCanSelectTags();
  }

  int currentTagVersion() {
    return loadConfig().tagVersion();
  }

  void syncDictionary(Connection connection) throws SQLException {
    TagConfig config = loadConfig();
    try (PreparedStatement disable = connection.prepareStatement(
        "UPDATE market_tags SET enabled = FALSE WHERE code <> ?")) {
      disable.setString(1, FALLBACK_DEFAULT_TAG);
      disable.executeUpdate();
    }
    String upsertSql = marketTagUpsertSql();
    try (PreparedStatement statement = connection.prepareStatement(upsertSql)) {
      for (TagDefinition definition : config.orderedTags()) {
        statement.setString(1, definition.code());
        statement.setString(2, definition.displayName());
        statement.setBoolean(3, definition.enabled());
        statement.setInt(4, definition.priority());
        statement.addBatch();
      }
      if (!config.tagsByCode().containsKey(config.defaultTag())) {
        statement.setString(1, config.defaultTag());
        statement.setString(2, config.defaultTag());
        statement.setBoolean(3, true);
        statement.setInt(4, Integer.MAX_VALUE);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private String marketTagUpsertSql() {
    return sqlProvider.upsertMarketTagSql();
  }

  List<TagMeta> listTagMeta(Connection connection) throws SQLException {
    syncDictionary(connection);
    TagConfig config = loadConfig();
    Map<String, long[]> activeCounts = loadActiveCounts(connection);
    List<TagMeta> result = new ArrayList<>();
    String sql = """
        SELECT code, display_name, enabled, priority
        FROM market_tags
        ORDER BY priority ASC, code ASC
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String code = resultSet.getString("code");
        TagDefinition definition = config.tagsByCode().get(code);
        long[] counts = activeCounts.getOrDefault(code, new long[] {0L, 0L});
        result.add(new TagMeta(
            code,
            resultSet.getString("display_name"),
            resultSet.getBoolean("enabled"),
            resultSet.getInt("priority"),
            definition == null ? null : definition.id(),
            definition != null,
            definition != null && definition.system(),
            definition == null ? "primary" : definition.color(),
            definition == null ? "" : definition.description(),
            counts[0],
            counts[1]));
      }
    }
    return result;
  }

  private Map<String, long[]> loadActiveCounts(Connection connection) throws SQLException {
    Map<String, long[]> counts = new LinkedHashMap<>();
    String sql = """
        SELECT ml.market_side, mlt.tag_code, COUNT(*) AS total
        FROM market_listings ml
        JOIN market_listing_tags mlt ON mlt.listing_id = ml.id
        WHERE ml.status = 'ACTIVE'
        GROUP BY ml.market_side, mlt.tag_code
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String side = String.valueOf(resultSet.getString("market_side"))
            .trim()
            .toUpperCase(Locale.ROOT);
        String tagCode = String.valueOf(resultSet.getString("tag_code"))
            .trim()
            .toLowerCase(Locale.ROOT);
        long total = resultSet.getLong("total");
        long[] bucket = counts.computeIfAbsent(tagCode, ignored -> new long[] {0L, 0L});
        if ("BUY".equals(side)) {
          bucket[1] += total;
        } else {
          bucket[0] += total;
        }
      }
    }
    return counts;
  }

  private ItemStack toItemStack(byte[] rawItemBlob, String itemMaterial) {
    if (rawItemBlob != null && rawItemBlob.length > 0) {
      try {
        return itemSnapshotCodec.deserialize(rawItemBlob);
      } catch (Exception ignored) {
        // Fallback to material-based template.
      }
    }
    Material material = Material.matchMaterial(String.valueOf(itemMaterial).trim().toUpperCase(Locale.ROOT));
    if (material == null || material == Material.AIR) {
      material = Material.STONE;
    }
    return new ItemStack(material, 1);
  }

  private String buildNbtSearchText(ItemStack itemStack, String itemMetaJson) {
    StringBuilder builder = new StringBuilder();
    if (itemMetaJson != null) {
      builder.append(itemMetaJson.toLowerCase(Locale.ROOT));
    }
    builder.append(' ').append(itemStack.getType().name().toLowerCase(Locale.ROOT));

    ItemMeta itemMeta = itemStack.getItemMeta();
    if (itemMeta == null) {
      return builder.toString();
    }
    for (NamespacedKey key : itemMeta.getPersistentDataContainer().getKeys()) {
      builder.append(' ').append(key.getNamespace().toLowerCase(Locale.ROOT));
      builder.append(' ').append(key.getKey().toLowerCase(Locale.ROOT));
      builder.append(' ').append(key.toString().toLowerCase(Locale.ROOT));
    }
    return builder.toString();
  }

  private synchronized TagConfig loadConfig() {
    RuntimeConfigService.ConfigDocument document = runtimeConfigService.readMarketTagsConfig();
    CachedConfig existing = cachedConfig;
    if (existing != null && existing.version() == document.version()) {
      return existing.config();
    }
    TagConfig parsed = parseConfig(document.config());
    cachedConfig = new CachedConfig(document.version(), parsed);
    return parsed;
  }

  private TagConfig parseConfig(JsonObject root) {
    int tagVersion = Math.max(1, readInt(root, 1, "tagVersion", "tag-version", "version"));
    int maxTagsPerItem = Math.max(1, Math.min(10, readInt(root, 3, "maxTagsPerItem")));
    boolean playersCanSelectTags = readBoolean(root, true, "playersCanSelectTags");
    String defaultTag = normalizeTagCode(readString(root, null, "defaultTag", "default-tag"));
    if (defaultTag == null) {
      defaultTag = FALLBACK_DEFAULT_TAG;
    }

    JsonArray rawTags = readArray(root, "tags");
    Map<String, TagDefinition> byCode = new LinkedHashMap<>();
    for (JsonElement element : rawTags) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject row = element.getAsJsonObject();
      String code = normalizeTagCode(readString(row, null, "code"));
      if (code == null) {
        continue;
      }
      String displayName = readString(row, code, "displayName", "display-name");
      String id = readString(row, code, "id");
      boolean system = readBoolean(row, false, "system");
      String color = readString(row, "primary", "color");
      String description = readString(row, "", "description");
      int priority = readInt(row, 1000, "priority");
      boolean enabled = readBoolean(row, true, "enabled");
      JsonObject match = readObject(row, "match");
      Set<String> materialIn = normalizeMaterialSet(readArray(match, "materialIn", "material-in"));
      Set<String> nbtHasAny = normalizeKeywordSet(readArray(match, "nbtHasAny", "nbt-has-any"));
      byCode.put(code, new TagDefinition(id, code, displayName, enabled, system, priority, color, description, materialIn, nbtHasAny));
    }

    List<TagDefinition> ordered = new ArrayList<>(byCode.values());
    ordered.sort((left, right) -> {
      int priorityCompare = Integer.compare(left.priority(), right.priority());
      if (priorityCompare != 0) {
        return priorityCompare;
      }
      return left.code().compareTo(right.code());
    });

    return new TagConfig(
        tagVersion,
        defaultTag,
        maxTagsPerItem,
        playersCanSelectTags,
        byCode,
        ordered);
  }

  private JsonObject readObject(JsonObject root, String... keys) {
    if (root == null || keys == null) {
      return new JsonObject();
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonElement element = root.get(key);
      if (element != null && element.isJsonObject()) {
        return element.getAsJsonObject();
      }
    }
    return new JsonObject();
  }

  private JsonArray readArray(JsonObject root, String... keys) {
    if (root == null || keys == null) {
      return new JsonArray();
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonElement element = root.get(key);
      if (element != null && element.isJsonArray()) {
        return element.getAsJsonArray();
      }
    }
    return new JsonArray();
  }

  private String readString(JsonObject root, String fallback, String... keys) {
    if (root == null || keys == null) {
      return fallback;
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonElement element = root.get(key);
      if (element == null || element.isJsonNull()) {
        continue;
      }
      try {
        String value = element.getAsString();
        if (value != null && !value.isBlank()) {
          return value.trim();
        }
      } catch (Exception ignored) {
        // continue
      }
    }
    return fallback;
  }

  private int readInt(JsonObject root, int fallback, String... keys) {
    if (root == null || keys == null) {
      return fallback;
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonElement element = root.get(key);
      if (element == null || element.isJsonNull()) {
        continue;
      }
      try {
        return element.getAsInt();
      } catch (Exception ignored) {
        // continue
      }
    }
    return fallback;
  }

  private boolean readBoolean(JsonObject root, boolean fallback, String... keys) {
    if (root == null || keys == null) {
      return fallback;
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonElement element = root.get(key);
      if (element == null || element.isJsonNull()) {
        continue;
      }
      try {
        return element.getAsBoolean();
      } catch (Exception ignored) {
        // continue
      }
    }
    return fallback;
  }

  private Set<String> normalizeMaterialSet(JsonArray array) {
    if (array == null) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement entry : array) {
      if (entry == null || entry.isJsonNull()) {
        continue;
      }
      String normalized = String.valueOf(entry.getAsString()).trim().toUpperCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        result.add(normalized);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private Set<String> normalizeKeywordSet(JsonArray array) {
    if (array == null) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement entry : array) {
      if (entry == null || entry.isJsonNull()) {
        continue;
      }
      String normalized = String.valueOf(entry.getAsString()).trim();
      if (!normalized.isEmpty()) {
        result.add(normalized);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private String normalizeTagCode(String raw) {
    return MarketTagCodes.normalize(raw);
  }

  private record CachedConfig(long version, TagConfig config) {
  }

  private record TagConfig(
      int tagVersion,
      String defaultTag,
      int maxTagsPerItem,
      boolean playersCanSelectTags,
      Map<String, TagDefinition> tagsByCode,
      List<TagDefinition> orderedTags) {
  }

  private record TagDefinition(
      String id,
      String code,
      String displayName,
      boolean enabled,
      boolean system,
      int priority,
      String color,
      String description,
      Set<String> materialIn,
      Set<String> nbtHasAny) {
  }

  record TagAssignment(String code, int tagVersion, String source) {
  }

  record TagMeta(
      String code,
      String displayName,
      boolean enabled,
      int priority,
      String id,
      boolean configured,
      boolean system,
      String color,
      String description,
      long activeSellCount,
      long activeBuyCount) {
  }
}
