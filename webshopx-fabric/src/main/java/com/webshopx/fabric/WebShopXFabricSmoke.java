package com.webshopx.fabric;

import com.webshopx.core.M2RuntimeBootstrap;
import java.nio.file.Path;

public final class WebShopXFabricSmoke {

  private WebShopXFabricSmoke() {
  }

  public static void main(String[] args) throws Exception {
    M2RuntimeBootstrap.RuntimeHandle runtimeHandle = M2RuntimeBootstrap.start(
        "fabric-1.21.x",
        resolveVersion(),
        Path.of("build", "m2-runtime"),
        System.out::println);
    System.out.println("[M2] Fabric smoke endpoint=" + runtimeHandle.endpoint());
    runtimeHandle.close();
  }

  private static String resolveVersion() {
    Package selfPackage = WebShopXFabricSmoke.class.getPackage();
    String implementationVersion = selfPackage == null ? null : selfPackage.getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
  }
}
