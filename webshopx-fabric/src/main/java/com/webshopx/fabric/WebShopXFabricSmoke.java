package com.webshopx.fabric;

import com.webshopx.core.M0RuntimeBootstrap;
import java.nio.file.Path;

public final class WebShopXFabricSmoke {

  private WebShopXFabricSmoke() {
  }

  public static void main(String[] args) throws Exception {
    M0RuntimeBootstrap.RuntimeHandle runtimeHandle = M0RuntimeBootstrap.start(
        "fabric",
        resolveVersion(),
        Path.of("build", "m0-runtime"),
        System.out::println);
    System.out.println("[M0] Fabric smoke health=" + runtimeHandle.healthEndpoint());
    runtimeHandle.close();
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXFabricSmoke.class.getPackage();
    String implementationVersion = selfPackage == null ? null : selfPackage.getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
  }
}
