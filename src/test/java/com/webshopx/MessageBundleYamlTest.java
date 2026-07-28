package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessageBundleYamlTest {
  private static final List<String> BUNDLES = List.of(
      "messages/messages.zh-CN.yml",
      "messages/messages.en-US.yml");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}\\s]+}");

  @Test
  void bundledMessagesShouldParseAsBukkitYaml() {
    for (String bundle : BUNDLES) {
      YamlConfiguration configuration = load(bundle);
      assertFalse(configuration.getKeys(true).isEmpty(), "Empty message bundle: " + bundle);
    }
  }

  @Test
  void bundledMessagesShouldHaveMatchingKeysAndPlaceholders() {
    YamlConfiguration zh = load("messages/messages.zh-CN.yml");
    YamlConfiguration en = load("messages/messages.en-US.yml");

    assertEquals(zh.getKeys(true), en.getKeys(true), "Locale message key sets must match");

    for (String key : zh.getKeys(true)) {
      Object zhValue = zh.get(key);
      Object enValue = en.get(key);
      if (!(zhValue instanceof String zhText) || !(enValue instanceof String enText)) {
        continue;
      }
      assertEquals(
          placeholders(zhText),
          placeholders(enText),
          "Placeholder mismatch for locale key: " + key);
    }
  }

  private static YamlConfiguration load(String bundle) {
    InputStream stream = MessageBundleYamlTest.class.getClassLoader().getResourceAsStream(bundle);
    assertNotNull(stream, "Missing message bundle: " + bundle);
    YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
        new InputStreamReader(stream, StandardCharsets.UTF_8));
    assertTrue(configuration.getKeys(true).size() > 0, "Empty message bundle: " + bundle);
    return configuration;
  }

  private static Set<String> placeholders(String value) {
    Set<String> result = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(value);
    while (matcher.find()) {
      result.add(matcher.group());
    }
    return result;
  }
}
