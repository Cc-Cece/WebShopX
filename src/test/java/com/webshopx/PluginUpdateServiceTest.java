package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

class PluginUpdateServiceTest {
  @Test
  void extractsReleaseVersionsFromProjectBuildNames() {
    assertEquals("3.0.0", PluginUpdateService.extractReleaseVersion("dev-v3.0.0"));
    assertEquals("2.1.0", PluginUpdateService.extractReleaseVersion("2.1.0-p206"));
    assertEquals("1.2.9", PluginUpdateService.extractReleaseVersion("snapshot-v1.2.9-p182"));
  }

  @Test
  void comparesReleaseVersionsNumerically() {
    assertEquals(1, PluginUpdateService.compareReleaseVersions("3.0.0", "2.10.9"));
    assertEquals(-1, PluginUpdateService.compareReleaseVersions("2.9.0", "2.10.0"));
    assertEquals(0, PluginUpdateService.compareReleaseVersions("2.1", "2.1.0"));
  }

  @Test
  void treatsPurpurAsPaperAndOnlyExplicitFoliaAsFolia() {
    PluginUpdateService.RuntimeContext purpur = PluginUpdateService.runtimeContext(
        server("Purpur", "1.21.10"));
    assertEquals("purpur", purpur.platform());
    assertEquals("paper", purpur.loader());

    PluginUpdateService.RuntimeContext folia = PluginUpdateService.runtimeContext(
        server("Folia", "1.21.10"));
    assertEquals("folia", folia.platform());
    assertEquals("folia", folia.loader());
  }

  private Server server(String name, String minecraftVersion) {
    return (Server) Proxy.newProxyInstance(
        Server.class.getClassLoader(),
        new Class<?>[] {Server.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> name;
          case "getMinecraftVersion" -> minecraftVersion;
          default -> defaultValue(method.getReturnType());
        });
  }

  private Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}
