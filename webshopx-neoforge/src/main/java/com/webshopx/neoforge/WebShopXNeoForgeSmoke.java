package com.webshopx.neoforge;

import com.webshopx.core.M2RuntimeBootstrap;
import java.nio.file.Path;

public final class WebShopXNeoForgeSmoke {

  private WebShopXNeoForgeSmoke() {
  }

  public static void main(String[] args) throws Exception {
    M2RuntimeBootstrap.RuntimeHandle runtimeHandle = M2RuntimeBootstrap.start(
        "neoforge-1.21.x",
        resolveVersion(),
        Path.of("build", "m2-runtime"),
        System.out::println);
    System.out.println("[M2] NeoForge smoke endpoint=" + runtimeHandle.endpoint());
    runtimeHandle.close();
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXNeoForgeSmoke.class.getPackage();
    String implementationVersion = selfPackage == null ? null : selfPackage.getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
  }
}
