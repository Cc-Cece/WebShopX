package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessageBundleYamlTest {
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
}
