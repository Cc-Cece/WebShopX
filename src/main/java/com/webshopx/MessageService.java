package com.webshopx;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

class MessageService {
  private final JavaPlugin plugin;
  private final Supplier<PluginSettings> settingsSupplier;
  private final Map<String, YamlConfiguration> bundles = new HashMap<>();

  MessageService(JavaPlugin plugin, Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.settingsSupplier = settingsSupplier;
  }

  String resolveLocale(CommandSender sender) {
    if (sender instanceof Player player) {
      String clientLocale = resolvePlayerLocale(player);
      if (clientLocale != null) {
        return clientLocale;
      }
    }
    return normalizeLocale(settingsSupplier.get().defaultLocale());
  }

  String get(CommandSender sender, String key) {
    return get(resolveLocale(sender), key);
  }

  String get(CommandSender sender, String key, Map<String, ?> params) {
    return format(resolveLocale(sender), key, params);
  }

  String get(String locale, String key) {
    return resolveValue(normalizeLocale(locale), key);
  }

  String format(String locale, String key, Map<String, ?> params) {
    return applyParams(resolveValue(normalizeLocale(locale), key), params);
  }

  String format(CommandSender sender, String key, Map<String, ?> params) {
    return format(resolveLocale(sender), key, params);
  }

  // Convenience methods for console/server-side localized messages
  String getConsole(String key) {
    return get(normalizeLocale(settingsSupplier.get().defaultLocale()), key);
  }

  String formatConsole(String key, Map<String, ?> params) {
    return format(normalizeLocale(settingsSupplier.get().defaultLocale()), key, params);
  }

  List<String> getList(CommandSender sender, String key) {
    return getList(resolveLocale(sender), key, Collections.emptyMap());
  }

  List<String> getList(CommandSender sender, String key, Map<String, ?> params) {
    return getList(resolveLocale(sender), key, params);
  }

  List<String> getList(String locale, String key, Map<String, ?> params) {
    String normalizedLocale = normalizeLocale(locale);
    YamlConfiguration bundle = loadBundle(normalizedLocale);
    List<String> values = bundle.getStringList(key);
    if (!values.isEmpty()) {
      return values.stream()
          .map(value -> applyParams(value, params))
          .toList();
    }
    if (bundle.isString(key)) {
      return List.of(applyParams(bundle.getString(key, key), params));
    }
    String fallbackLocale = normalizeLocale(settingsSupplier.get().defaultLocale());
    if (!fallbackLocale.equals(normalizedLocale)) {
      YamlConfiguration fallback = loadBundle(fallbackLocale);
      List<String> fallbackValues = fallback.getStringList(key);
      if (!fallbackValues.isEmpty()) {
        return fallbackValues.stream()
            .map(value -> applyParams(value, params))
            .toList();
      }
      if (fallback.isString(key)) {
        return List.of(applyParams(fallback.getString(key, key), params));
      }
    }
    return List.of();
  }

  private String resolveValue(String locale, String key) {
    YamlConfiguration bundle = loadBundle(locale);
    if (bundle.isString(key)) {
      return bundle.getString(key, key);
    }
    String fallbackLocale = normalizeLocale(settingsSupplier.get().defaultLocale());
    if (!fallbackLocale.equals(locale)) {
      YamlConfiguration fallback = loadBundle(fallbackLocale);
      if (fallback.isString(key)) {
        return fallback.getString(key, key);
      }
    }
    return key;
  }

  private String applyParams(String template, Map<String, ?> params) {
    String text = template == null ? "" : template;
    if (params == null || params.isEmpty()) {
      return text;
    }
    String formatted = text;
    for (Map.Entry<String, ?> entry : params.entrySet()) {
      String token = "{" + entry.getKey() + "}";
      String replacement = String.valueOf(entry.getValue());
      formatted = formatted.replace(token, replacement);
    }
    return formatted;
  }

  private YamlConfiguration loadBundle(String locale) {
    return bundles.computeIfAbsent(locale, this::readBundle);
  }

  private YamlConfiguration readBundle(String locale) {
    Path messageDir = plugin.getDataFolder().toPath().resolve("messages");
    Path externalYml = messageDir.resolve("messages." + locale + ".yml");
    Path externalYaml = messageDir.resolve("messages." + locale + ".yaml");
    Path externalPath = Files.exists(externalYml) ? externalYml : externalYaml;
    if (Files.exists(externalPath)) {
      try (InputStreamReader reader =
          new InputStreamReader(Files.newInputStream(externalPath), StandardCharsets.UTF_8)) {
        return YamlConfiguration.loadConfiguration(reader);
      } catch (Exception exception) {
        plugin.getLogger().warning("Failed to load external message bundle: " + externalPath);
      }
    }

    String resourcePath = "messages/messages." + locale + ".yml";
    try (InputStream inputStream = plugin.getResource(resourcePath)) {
      if (inputStream == null) {
        plugin.getLogger().fine(() -> "Message bundle not found, will fallback at runtime: " + resourcePath);
        return new YamlConfiguration();
      }
      try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
        return YamlConfiguration.loadConfiguration(reader);
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to load message bundle: " + resourcePath, exception);
    }
  }

  private String resolvePlayerLocale(Player player) {
    String locale = invokeLocaleMethod(player, "locale");
    if (locale == null) {
      locale = invokeLocaleMethod(player, "getLocale");
    }
    return locale == null ? null : normalizeLocale(locale);
  }

  private String invokeLocaleMethod(Player player, String methodName) {
    try {
      Method method = player.getClass().getMethod(methodName);
      Object value = method.invoke(player);
      if (value == null) {
        return null;
      }
      if (value instanceof java.util.Locale locale) {
        return locale.toLanguageTag();
      }
      return value.toString();
    } catch (Exception exception) {
      return null;
    }
  }

  private String normalizeLocale(String rawLocale) {
    String locale = rawLocale == null ? "" : rawLocale.trim().replace('_', '-');
    if (locale.isEmpty()) {
      return "zh-CN";
    }
    String lower = locale.toLowerCase();
    if (lower.equals("zh")) {
      return "zh-CN";
    }
    if (lower.equals("en")) {
      return "en-US";
    }
    String[] segments = locale.split("-");
    if (segments.length == 0 || segments[0].isBlank()) {
      return "zh-CN";
    }
    String language = segments[0].toLowerCase();
    if (segments.length == 1) {
      return language;
    }
    String region = segments[1].length() == 2
        ? segments[1].toUpperCase()
        : segments[1].toLowerCase();
    if (segments.length == 2) {
      return language + "-" + region;
    }
    StringBuilder builder = new StringBuilder(language).append('-').append(region);
    for (int i = 2; i < segments.length; i++) {
      String part = segments[i].trim();
      if (!part.isEmpty()) {
        builder.append('-').append(part.toLowerCase());
      }
    }
    return builder.toString();
  }
}
