package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessageBundleYamlTest {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
  private static final List<String> BUNDLES = List.of(
      "messages/messages.zh-CN.yml",
      "messages/messages.en-US.yml");

  @Test
  void bundledMessagesShouldParseAsBukkitYaml() {
    for (String bundle : BUNDLES) {
      InputStream stream = MessageBundleYamlTest.class.getClassLoader().getResourceAsStream(bundle);
      assertNotNull(stream, "Missing message bundle: " + bundle);
      YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
          new InputStreamReader(stream, StandardCharsets.UTF_8));
      assertFalse(configuration.getKeys(true).isEmpty(), "Empty message bundle: " + bundle);
    }
  }

  @Test
  void bundledMessagesShouldHaveMatchingKeysTypesAndPlaceholders() {
    Map<String, Object> english = leafValues(load(BUNDLES.get(1)));
    Map<String, Object> chinese = leafValues(load(BUNDLES.get(0)));
    assertEquals(english.keySet(), chinese.keySet(), "Message bundle key trees differ");
    for (String key : english.keySet()) {
      Object enValue = english.get(key);
      Object zhValue = chinese.get(key);
      assertEquals(enValue.getClass(), zhValue.getClass(), "Message value type differs: " + key);
      if (enValue instanceof String enText && zhValue instanceof String zhText) {
        assertEquals(placeholders(enText), placeholders(zhText), "Placeholders differ: " + key);
      }
    }
  }

  private static YamlConfiguration load(String bundle) {
    InputStream stream = MessageBundleYamlTest.class.getClassLoader().getResourceAsStream(bundle);
    assertNotNull(stream, "Missing message bundle: " + bundle);
    return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
  }

  private static Map<String, Object> leafValues(YamlConfiguration configuration) {
    Map<String, Object> result = new TreeMap<>();
    for (Map.Entry<String, Object> entry : configuration.getValues(true).entrySet()) {
      if (!(entry.getValue() instanceof org.bukkit.configuration.ConfigurationSection)) {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  private static Set<String> placeholders(String text) {
    Set<String> result = new java.util.TreeSet<>();
    Matcher matcher = PLACEHOLDER.matcher(text);
    while (matcher.find()) {
      result.add(matcher.group(1));
    }
    return result;
  }
}
