package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.core.WebShopXCoreRuntime;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EntrypointSmokeTest {
  @AfterEach void stop() { LoaderRuntime.stop(); }

  @Test void entrypointStartsExactlyOneReadyRuntime() throws Exception {
    String family = System.getProperty("webshopx.platform-family");
    String className = switch (family) {
      case "fabric" -> "com.webshopx.fabric.WebShopXFabricMod";
      case "forge" -> "com.webshopx.forge.WebShopXForgeMod";
      case "neoforge" -> "com.webshopx.neoforge.WebShopXNeoForgeMod";
      default -> throw new IllegalStateException("unknown family " + family);
    };
    Object entrypoint = Class.forName(className).getConstructor().newInstance();
    if (family.equals("fabric")) {
      Method initialize = entrypoint.getClass().getMethod("onInitialize");
      initialize.invoke(entrypoint);
    }
    assertTrue(LoaderRuntime.active().isPresent());
    assertEquals(WebShopXCoreRuntime.State.READY, LoaderRuntime.active().orElseThrow().state());
    assertEquals(family, LoaderRuntime.active().orElseThrow().platform().identity().platform());
  }
}
