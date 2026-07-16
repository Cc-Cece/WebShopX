package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

class MarketLimitationService {
  private static final Set<String> ALL_SIDES = Set.of("SELL", "BUY");
  private static final Set<String> ALL_TRADE_MODES = Set.of("DIRECT", "AUCTION");

  private final RuntimeConfigService runtimeConfigService;
  private final ItemSnapshotCodec itemSnapshotCodec;
  private volatile CachedConfig cachedConfig;

  MarketLimitationService(RuntimeConfigService runtimeConfigService, ItemSnapshotCodec itemSnapshotCodec) {
    this.runtimeConfigService = runtimeConfigService;
    this.itemSnapshotCodec = itemSnapshotCodec;
  }

  void invalidateCache() {
    cachedConfig = null;
  }

  Decision evaluate(DecisionContext context, boolean bypass) {
    if (bypass) {
      return Decision.allowBypass();
    }

    LimitationConfig config = loadConfig();
    String side = context.side().toUpperCase(Locale.ROOT);
    String tradeMode = context.tradeMode().toUpperCase(Locale.ROOT);
    String currency = context.currency().toUpperCase(Locale.ROOT);
    String tag = normalizeTagCode(context.tag());

    if (config.defaultDenySides().contains(side)) {
      return Decision.deny("limitation_side_not_allowed", null, null);
    }
    if (config.defaultDenyCurrencies().contains(currency)) {
      return Decision.deny("limitation_currency_not_allowed", null, null);
    }

    Set<String> allowedSides = config.defaultAllowSides().isEmpty()
        ? new LinkedHashSet<>(ALL_SIDES)
        : new LinkedHashSet<>(config.defaultAllowSides());
    Set<String> allowedTradeModes = config.defaultAllowTradeModes().isEmpty()
        ? new LinkedHashSet<>(ALL_TRADE_MODES)
        : new LinkedHashSet<>(config.defaultAllowTradeModes());
    Set<String> allowedCurrencies = new LinkedHashSet<>(config.defaultAllowCurrencies());
    Set<String> allowedTags = new LinkedHashSet<>(config.defaultAllowTags());

    boolean currencyRestricted = !allowedCurrencies.isEmpty();
    boolean tagRestricted = !allowedTags.isEmpty();
    CreateCost createCost = config.defaultCreateCost();
    String forcedTag = null;

    for (Rule rule : config.rules()) {
      if (!rule.matches(context, itemSnapshotCodec)) {
        continue;
      }
      Action action = rule.action();
      if (action.deny()) {
        String denyCode = action.denyCode() == null
            ? "limitation_item_forbidden"
            : action.denyCode();
        return Decision.deny(denyCode, rule.id(), rule.priority());
      }
      if (!action.sideWhitelist().isEmpty()) {
        allowedSides.retainAll(action.sideWhitelist());
      }
      if (!action.tradeModeWhitelist().isEmpty()) {
        allowedTradeModes.retainAll(action.tradeModeWhitelist());
      }
      if (!action.currencyWhitelist().isEmpty()) {
        if (!currencyRestricted) {
          allowedCurrencies = new LinkedHashSet<>(action.currencyWhitelist());
          currencyRestricted = true;
        } else {
          allowedCurrencies.retainAll(action.currencyWhitelist());
        }
      }
      if (!action.tagWhitelist().isEmpty()) {
        if (!tagRestricted) {
          allowedTags = new LinkedHashSet<>(action.tagWhitelist());
          tagRestricted = true;
        } else {
          allowedTags.retainAll(action.tagWhitelist());
        }
      }
      if (action.createCostOverride() != null) {
        createCost = action.createCostOverride();
      }
      if (action.forcedTag() != null && !action.forcedTag().isBlank()) {
        forcedTag = action.forcedTag();
      }
    }

    if (!allowedSides.contains(side)) {
      return Decision.deny("limitation_side_not_allowed", null, null);
    }
    if (!allowedTradeModes.contains(tradeMode)) {
      return Decision.deny("limitation_trade_mode_not_allowed", null, null);
    }
    if (currencyRestricted && !allowedCurrencies.contains(currency)) {
      return Decision.deny("limitation_currency_not_allowed", null, null);
    }
    if (tagRestricted && tag != null && !allowedTags.contains(tag)) {
      return Decision.deny("invalid_tag", null, null);
    }

    return Decision.allow(
        allowedSides,
        allowedTradeModes,
        currencyRestricted ? allowedCurrencies : Collections.emptySet(),
        tagRestricted ? allowedTags : Collections.emptySet(),
        createCost,
        forcedTag);
  }

  private synchronized LimitationConfig loadConfig() {
    RuntimeConfigService.ConfigDocument document = runtimeConfigService.readMarketLimitationConfig();
    CachedConfig existing = cachedConfig;
    if (existing != null && existing.version() == document.version()) {
      return existing.config();
    }
    LimitationConfig parsed = parseConfig(document.config());
    cachedConfig = new CachedConfig(document.version(), parsed);
    return parsed;
  }

  private LimitationConfig parseConfig(JsonObject root) {
    JsonObject defaults = readObject(root, "default");
    JsonObject defaultDeny = readObject(defaults, "deny");
    JsonObject defaultAllow = readObject(defaults, "allow");

    Set<String> defaultDenySides = readUpperSet(readArray(defaultDeny, "marketSides", "market-sides"));
    Set<String> defaultDenyCurrencies = readUpperSet(readArray(defaultDeny, "currencies"));
    Set<String> defaultAllowSides = readUpperSet(readArray(defaultAllow, "marketSides", "market-sides"));
    Set<String> defaultAllowTradeModes = readUpperSet(readArray(defaultAllow, "tradeModes", "trade-modes"));
    Set<String> defaultAllowCurrencies = readUpperSet(readArray(defaultAllow, "currencies"));
    Set<String> defaultAllowTags = readTagSet(readArray(defaultAllow, "tags"));
    CreateCost defaultCreateCost = parseCreateCost(readObject(defaults, "createCost", "create-cost"));

    List<Rule> rules = new ArrayList<>();
    JsonArray rawRules = readArray(root, "rules");
    for (JsonElement rawRule : rawRules) {
      if (rawRule == null || !rawRule.isJsonObject()) {
        continue;
      }
      JsonObject ruleMap = rawRule.getAsJsonObject();
      String id = readString(ruleMap, null, "id");
      if (id == null || id.isBlank()) {
        continue;
      }
      int priority = readInt(ruleMap, 1000, "priority");
      JsonObject when = readObject(ruleMap, "when");
      JsonObject item = readObject(when, "item");
      JsonObject player = readObject(when, "player");
      Set<String> sideIn = readUpperSet(readArray(when, "sideIn", "side-in"));
      Set<String> itemMaterialIn = readUpperSet(readArray(item, "materialIn", "material-in"));
      Set<String> itemNbtHasAny = readKeywordSet(readArray(item, "nbtHasAny", "nbt-has-any"));
      Set<String> lacksPermission = readPermissionSet(readArray(player, "lacksPermission", "lacks-permission"));

      JsonObject actionMap = readObject(ruleMap, "action");
      boolean deny = readBoolean(actionMap, false, "deny");
      String code = readString(actionMap, null, "code");
      Set<String> sideWhitelist = readUpperSet(readArray(actionMap, "sideWhitelist", "side-whitelist"));
      Set<String> tradeModeWhitelist = readUpperSet(readArray(actionMap, "tradeModeWhitelist", "trade-mode-whitelist"));
      Set<String> currencyWhitelist = readUpperSet(readArray(actionMap, "currencyWhitelist", "currency-whitelist"));
      Set<String> tagWhitelist = readTagSet(readArray(actionMap, "tagWhitelist", "tag-whitelist"));
      CreateCost createCost = parseCreateCost(readObject(actionMap, "createCost", "create-cost"));
      String forcedTag = normalizeTagCode(readString(actionMap, null, "forcedTag", "forced-tag"));

      Condition condition = new Condition(sideIn, itemMaterialIn, itemNbtHasAny, lacksPermission);
      Action action = new Action(
          deny,
          code,
          sideWhitelist,
          tradeModeWhitelist,
          currencyWhitelist,
          tagWhitelist,
          createCost,
          forcedTag);
      rules.add(new Rule(id, priority, condition, action));
    }
    rules.sort((left, right) -> Integer.compare(left.priority(), right.priority()));

    return new LimitationConfig(
        defaultDenySides,
        defaultDenyCurrencies,
        defaultAllowSides,
        defaultAllowTradeModes,
        defaultAllowCurrencies,
        defaultAllowTags,
        defaultCreateCost,
        rules);
  }

  private CreateCost parseCreateCost(JsonObject root) {
    if (root == null || root.entrySet().isEmpty()) {
      return new CreateCost(false, "INHERIT", 0L);
    }
    boolean enabled = readBoolean(root, false, "enabled");
    String currency = readString(root, "INHERIT", "currency");
    if (currency == null || currency.isBlank()) {
      currency = "INHERIT";
    } else {
      currency = currency.trim().toUpperCase(Locale.ROOT);
    }
    long amount = Math.max(0L, readLong(root, 0L, "amount"));
    return new CreateCost(enabled, currency, amount);
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

  private long readLong(JsonObject root, long fallback, String... keys) {
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
        return element.getAsLong();
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

  private Set<String> readUpperSet(JsonArray array) {
    if (array == null) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement raw : array) {
      if (raw == null || raw.isJsonNull()) {
        continue;
      }
      String normalized = String.valueOf(raw.getAsString()).trim().toUpperCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        result.add(normalized);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private Set<String> readTagSet(JsonArray array) {
    if (array == null) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement raw : array) {
      if (raw == null || raw.isJsonNull()) {
        continue;
      }
      String normalized = normalizeTagCode(String.valueOf(raw.getAsString()));
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private Set<String> readKeywordSet(JsonArray array) {
    if (array == null) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement raw : array) {
      if (raw == null || raw.isJsonNull()) {
        continue;
      }
      String normalized = String.valueOf(raw.getAsString()).trim();
      if (!normalized.isEmpty()) {
        result.add(normalized);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private Set<String> readPermissionSet(JsonArray array) {
    if (array == null) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (JsonElement raw : array) {
      if (raw == null || raw.isJsonNull()) {
        continue;
      }
      String normalized = String.valueOf(raw.getAsString()).trim();
      if (!normalized.isEmpty()) {
        result.add(normalized);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private String normalizeTagCode(String raw) {
    return MarketTagCodes.normalize(raw);
  }

  private record CachedConfig(long version, LimitationConfig config) {
  }

  private record LimitationConfig(
      Set<String> defaultDenySides,
      Set<String> defaultDenyCurrencies,
      Set<String> defaultAllowSides,
      Set<String> defaultAllowTradeModes,
      Set<String> defaultAllowCurrencies,
      Set<String> defaultAllowTags,
      CreateCost defaultCreateCost,
      List<Rule> rules) {
  }

  private record Rule(String id, int priority, Condition condition, Action action) {
    boolean matches(DecisionContext context, ItemSnapshotCodec itemSnapshotCodec) {
      return condition.matches(context, itemSnapshotCodec);
    }
  }

  private record Condition(
      Set<String> sideIn,
      Set<String> itemMaterialIn,
      Set<String> itemNbtHasAny,
      Set<String> playerLacksPermission) {
    boolean matches(DecisionContext context, ItemSnapshotCodec itemSnapshotCodec) {
      if (!sideIn.isEmpty()
          && !sideIn.contains(context.side().toUpperCase(Locale.ROOT))) {
        return false;
      }
      if (!itemMaterialIn.isEmpty()) {
        String material = String.valueOf(context.itemMaterial())
            .trim()
            .toUpperCase(Locale.ROOT);
        if (!itemMaterialIn.contains(material)) {
          return false;
        }
      }
      if (!itemNbtHasAny.isEmpty()) {
        String nbtSearchText = buildNbtSearchText(context, itemSnapshotCodec);
        boolean matched = false;
        for (String keyword : itemNbtHasAny) {
          if (nbtSearchText.contains(keyword.toLowerCase(Locale.ROOT))) {
            matched = true;
            break;
          }
        }
        if (!matched) {
          return false;
        }
      }
      if (!playerLacksPermission.isEmpty()) {
        Set<String> permissions = context.playerPermissions();
        boolean lacksAny = false;
        for (String permission : playerLacksPermission) {
          String normalized = permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
          if (normalized.isEmpty()) {
            continue;
          }
          if (permissions == null || !permissions.contains(normalized)) {
            lacksAny = true;
            break;
          }
        }
        if (!lacksAny) {
          return false;
        }
      }
      return true;
    }

    private String buildNbtSearchText(
        DecisionContext context,
        ItemSnapshotCodec itemSnapshotCodec) {
      ItemStack itemStack = null;
      if (context.rawItemBlob() != null && context.rawItemBlob().length > 0) {
        try {
          itemStack = itemSnapshotCodec.deserialize(context.rawItemBlob());
        } catch (Exception ignored) {
          // Fallback to item material if blob is broken.
        }
      }
      if (itemStack == null) {
        Material material = Material.matchMaterial(
            String.valueOf(context.itemMaterial()).trim().toUpperCase(Locale.ROOT));
        if (material == null || material == Material.AIR) {
          material = Material.STONE;
        }
        itemStack = new ItemStack(material, 1);
      }
      StringBuilder builder = new StringBuilder();
      if (context.itemMetaJson() != null) {
        builder.append(context.itemMetaJson().toLowerCase(Locale.ROOT));
      }
      builder.append(' ').append(itemStack.getType().name().toLowerCase(Locale.ROOT));
      ItemMeta itemMeta = itemStack.getItemMeta();
      if (itemMeta != null) {
        for (NamespacedKey key : itemMeta.getPersistentDataContainer().getKeys()) {
          builder.append(' ').append(key.getNamespace().toLowerCase(Locale.ROOT));
          builder.append(' ').append(key.getKey().toLowerCase(Locale.ROOT));
          builder.append(' ').append(key.toString().toLowerCase(Locale.ROOT));
        }
      }
      return builder.toString();
    }
  }

  private record Action(
      boolean deny,
      String denyCode,
      Set<String> sideWhitelist,
      Set<String> tradeModeWhitelist,
      Set<String> currencyWhitelist,
      Set<String> tagWhitelist,
      CreateCost createCostOverride,
      String forcedTag) {
  }

  record CreateCost(boolean enabled, String currency, long amount) {
  }

  record Decision(
      boolean allowed,
      String denyCode,
      Set<String> allowedSides,
      Set<String> allowedTradeModes,
      Set<String> allowedCurrencies,
      Set<String> allowedTags,
      CreateCost createCost,
      String forcedTag,
      String ruleId,
      Integer rulePriority) {
    static Decision allowBypass() {
      return allow(
          new LinkedHashSet<>(ALL_SIDES),
          new LinkedHashSet<>(ALL_TRADE_MODES),
          Collections.emptySet(),
          Collections.emptySet(),
          new CreateCost(false, "INHERIT", 0L),
          null);
    }

    static Decision allow(
        Set<String> allowedSides,
        Set<String> allowedTradeModes,
        Set<String> allowedCurrencies,
        Set<String> allowedTags,
        CreateCost createCost,
        String forcedTag) {
      return new Decision(
          true,
          null,
          Collections.unmodifiableSet(new LinkedHashSet<>(allowedSides)),
          Collections.unmodifiableSet(new LinkedHashSet<>(allowedTradeModes)),
          Collections.unmodifiableSet(new LinkedHashSet<>(allowedCurrencies)),
          Collections.unmodifiableSet(new LinkedHashSet<>(allowedTags)),
          createCost == null ? new CreateCost(false, "INHERIT", 0L) : createCost,
          forcedTag,
          null,
          null);
    }

    static Decision deny(String denyCode, String ruleId, Integer rulePriority) {
      return new Decision(
          false,
          denyCode,
          Collections.emptySet(),
          Collections.emptySet(),
          Collections.emptySet(),
          Collections.emptySet(),
          new CreateCost(false, "INHERIT", 0L),
          null,
          ruleId,
          rulePriority);
    }
  }

  record DecisionContext(
      java.util.UUID playerUuid,
      Set<String> playerPermissions,
      String side,
      String tradeMode,
      String currency,
      String tag,
      String itemMaterial,
      byte[] rawItemBlob,
      String itemMetaJson) {
  }
}
