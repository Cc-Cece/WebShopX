package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class MaterialMappingTest {

  @Test
  void knownBlockOfAliasesShouldResolve() {
    assertNotNull(resolveMaterial("BLOCK_OF_EMERALD"));
    assertNotNull(resolveMaterial("BLOCK_OF_DIAMOND"));
  }

  @Test
  void materialZhMapShouldContainResolvableMaterialKeys() {
    InputStream stream = MaterialMappingTest.class.getClassLoader()
        .getResourceAsStream("web/i18n/materials/zh-CN.json");
    assertNotNull(stream, "web/i18n/materials/zh-CN.json not found in test resources");
    JsonObject map = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
        .getAsJsonObject();

    List<String> unresolved = new ArrayList<>();
    int resolved = 0;
    for (String key : map.keySet()) {
      Material material = resolveMaterial(key);
      if (material == null || material == Material.AIR) {
        unresolved.add(key);
      } else {
        resolved++;
      }
    }
    System.out.println(
        "zh-CN material map resolved keys: " + resolved + ", unresolved keys: " + unresolved.size());
    assertTrue(
        resolved > 100,
        "Expected the material locale to contain enough resolvable material keys, actual=" + resolved);
  }

  private Material resolveMaterial(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim();
    Material material = Material.matchMaterial(normalized);
    if (material != null) {
      return material;
    }
    String key = normalized.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
    material = Material.matchMaterial(key);
    if (material != null) {
      return material;
    }
    if (key.startsWith("BLOCK_OF_") && key.length() > "BLOCK_OF_".length()) {
      material = Material.matchMaterial(key.substring("BLOCK_OF_".length()) + "_BLOCK");
      if (material != null) {
        return material;
      }
    }
    return Material.matchMaterial(normalized.toLowerCase(Locale.ROOT));
  }
}
