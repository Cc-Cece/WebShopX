package com.webshopx;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

class MessageService {
  private static final Set<String> SUPPORTED_LOCALES = Set.of("zh-CN", "en-US");

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
    YamlConfiguration bundle = loadBundle(normalizeLocale(locale));
    List<String> values = bundle.getStringList(key);
    if (values.isEmpty() && bundle.isString(key)) {
      return List.of(applyParams(bundle.getString(key, key), params));
    }
    return values.stream()
        .map(value -> applyParams(value, params))
        .toList();
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
    String resourcePath = "messages/messages." + locale + ".yml";
    try (InputStream inputStream = plugin.getResource(resourcePath)) {
      if (inputStream == null) {
        throw new IllegalStateException("Missing message bundle: " + resourcePath);
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
    if (lower.equals("zh") || lower.startsWith("zh-")) {
      return "zh-CN";
    }
    if (lower.equals("en") || lower.startsWith("en-")) {
      return "en-US";
    }
    return SUPPORTED_LOCALES.contains(locale) ? locale : "zh-CN";
  }
}
